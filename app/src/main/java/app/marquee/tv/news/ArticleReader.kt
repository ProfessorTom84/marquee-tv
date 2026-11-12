package app.marquee.tv.news

import app.marquee.tv.net.Http
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** A news story pulled out of its web page, to read on the TV: text, pictures and captions, no menus or ads. */
@Serializable
data class Article(
    val title: String,
    val site: String,
    val byline: String,
    val published: String,
    val heroImage: String?,
    val blocks: List<Block>,
    val url: String,
) {
    enum class Kind { PARA, HEADING, IMAGE }

    /** A paragraph or heading ([text]), or a picture ([url], with its caption in [text]). */
    @Serializable
    data class Block(val kind: Kind, val text: String = "", val url: String = "") {
        companion object {
            fun para(text: String) = Block(Kind.PARA, text)
            fun heading(text: String) = Block(Kind.HEADING, text)
            fun image(url: String, caption: String = "") = Block(Kind.IMAGE, caption, url)
        }
    }
}

/**
 * Stories Marquee has read, kept on the TV so they open at once, and the links that couldn't be read, so they
 * aren't tried again for a while. Good stories are kept three days; failures are retried after six hours.
 */
object ArticleStore {
    @Serializable
    private data class Entry(val at: Long, val article: Article?)

    private const val KEEP_MS = 3L * 24 * 3600_000
    private const val RETRY_MS = 6L * 3600_000
    @Volatile private var dir: File? = null
    private val memory = ConcurrentHashMap<String, Entry>()

    fun init(d: File) {
        dir = d.apply { mkdirs() }
        Thread {
            val now = System.currentTimeMillis()
            runCatching { d.listFiles()?.filter { now - it.lastModified() > KEEP_MS }?.forEach { it.delete() } }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun file(link: String) = dir?.let { d ->
        File(d, java.security.MessageDigest.getInstance("SHA-1").digest(link.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
    }

    private fun fresh(e: Entry) = System.currentTimeMillis() - e.at < (if (e.article != null) KEEP_MS else RETRY_MS)

    /** Whether this link has an answer already; then [article] is the story, or null if it couldn't be read. */
    fun known(link: String): Boolean = entry(link) != null
    fun article(link: String): Article? = entry(link)?.article

    private fun entry(link: String): Entry? {
        memory[link]?.let { return it.takeIf(::fresh) }
        val e = runCatching { file(link)?.takeIf { it.exists() }?.readText()?.let { Http.json.decodeFromString(Entry.serializer(), it) } }.getOrNull() ?: return null
        memory[link] = e
        return e.takeIf(::fresh)
    }

    fun put(link: String, article: Article?) {
        val e = Entry(System.currentTimeMillis(), article)
        memory[link] = e
        runCatching { file(link)?.writeText(Http.json.encodeToString(Entry.serializer(), e)) }
    }
}

/**
 * Fetches a story's page and keeps just the story, like a browser's reader mode. Marquee identifies itself
 * honestly and never works around a site that turns it away: a page that can't be read properly (refused,
 * paywalled, video-only, an aggregator's page) gives null, and the news only ever shows stories that didn't.
 */
object ArticleReader {
    suspend fun read(link: String): Article? = withContext(Dispatchers.IO) {
        if (!link.startsWith("http")) return@withContext null
        if (ArticleStore.known(link)) return@withContext ArticleStore.article(link)
        val html = try {
            Http.getText(link, mapOf("User-Agent" to NewsFeeds.USER_AGENT, "Accept" to "text/html"), cacheMinutes = 0)
        } catch (e: app.marquee.tv.net.HttpException) {
            ArticleStore.put(link, null); return@withContext null // the site said no: remember that
        } catch (e: Exception) {
            return@withContext null // no connection or a timeout: try again next time
        }
        val a = runCatching { extract(html, link) }.getOrNull()
        ArticleStore.put(link, a)
        a
    }

    /** Lines that aren't the story: ads, sign-up and donation prompts, "Watch:" links to other stories. */
    private val boilerplate = Regex(
        "^(advertisement\\b|ad\\b|read more\\b|read next\\b|sign up\\b|subscribe\\b|follow us\\b|share this\\b|copyright\\b|©|" +
            "related:|watch:|watch live\\b|listen:|read:|more:|see also:|getty images\\b|image source\\b|" +
            "stand with (free|independent)|support (our|independent|local|free) journalism|join press pass|donate\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val badImage = Regex("logo|icon|sprite|avatar|pixel|placeholder|spacer|1x1|badge|\\.svg", RegexOption.IGNORE_CASE)

    /** The extraction itself: pure, so it's unit-tested. */
    fun extract(html: String, url: String): Article? {
        val doc = Jsoup.parse(html, url)
        fun meta(vararg keys: String) = keys.firstNotNullOfOrNull { k ->
            doc.selectFirst("meta[property=$k], meta[name=$k]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
        }
        val title = meta("og:title", "twitter:title") ?: doc.selectFirst("h1")?.text() ?: doc.title()
        val site = meta("og:site_name") ?: runCatching { java.net.URI(url).host.removePrefix("www.") }.getOrDefault("")
        val hero = meta("og:image", "twitter:image")?.let { abs(doc, it) }
        val published = meta("article:published_time", "datePublished") ?: doc.selectFirst("time[datetime]")?.attr("datetime").orEmpty()
        val byline = (meta("author", "article:author") ?: doc.selectFirst("[rel=author], [class*=byline], [data-testid*=byline]")?.text())
            ?.let(::cleanByline)?.takeIf { it.length in 3..80 && !it.startsWith("http") }.orEmpty()
        val ldBody = jsonLdBody(doc)

        doc.select(
            "script, style, noscript, iframe, form, nav, header, footer, aside, button, svg, [aria-hidden=true], " +
                "[class*=newsletter], [class*=related], [class*=promo], [class*=share], [class*=social], [class*=advert], [id*=newsletter]",
        ).remove()

        // The story's container: where the most paragraph text sits (a paragraph's parent scores fully, its grandparent half).
        val score = HashMap<Element, Int>()
        doc.select("p").forEach { p ->
            val n = p.text().length
            if (n < 40) return@forEach
            p.parent()?.let { score.merge(it, n, Int::plus) }
            p.parent()?.parent()?.let { score.merge(it, n / 2, Int::plus) }
        }
        val box = score.maxByOrNull { it.value }?.key

        val blocks = mutableListOf<Article.Block>()
        val seenImages = mutableSetOf<String>().apply { hero?.let { add(it) } }
        box?.select("p, h2, h3, img, figcaption")?.forEach { e ->
            when (e.tagName()) {
                "p" -> {
                    if (e.closest("figcaption") != null) return@forEach
                    val t = e.text().trim()
                    if (t.length >= 25 && !boilerplate.containsMatchIn(t)) blocks += Article.Block.para(t)
                }
                "h2", "h3" -> e.text().trim().takeIf { it.length in 3..140 }?.let { blocks += Article.Block.heading(it) }
                "img" -> imageUrl(doc, e)?.takeIf { seenImages.add(it) }?.let { blocks += Article.Block.image(it) }
                "figcaption" -> {
                    val last = blocks.lastOrNull()
                    val cap = e.text().trim()
                    if (last != null && last.kind == Article.Kind.IMAGE && last.text.isBlank() && cap.isNotBlank()) blocks[blocks.lastIndex] = last.copy(text = cap)
                }
            }
        }
        // Not enough on the page itself (built by scripts)? Many news sites also publish the text as structured data.
        fun paras() = blocks.filter { it.kind == Article.Kind.PARA }
        if (paras().size < 4 && ldBody != null) {
            blocks.removeAll { it.kind != Article.Kind.IMAGE }
            ldBody.split(Regex("\\n+|(?<=[.!?\"”])\\s{2,}")).map { it.trim() }.filter { it.length >= 25 }.forEach { blocks += Article.Block.para(it) }
        }
        // A real story, not a teaser above a paywall or a caption under a video.
        if (paras().size < 4 || paras().sumOf { it.text.length } < 600) return null
        return Article(title.trim(), site, byline, published, hero, blocks.take(120), url)
    }

    /** "By — Associated Press Associated Press" → "Associated Press". */
    fun cleanByline(raw: String): String {
        var b = raw.replace(Regex("\\s+"), " ").trim().replace(Regex("^(by)\\b[\\s:—–-]*", RegexOption.IGNORE_CASE), "").trim()
        // The same name written twice in a row (visible text plus a hidden copy).
        val half = b.length / 2
        if (b.length % 2 == 1 && b.substring(0, half) == b.substring(half + 1)) b = b.substring(0, half)
        return b
    }

    private fun abs(doc: Document, u: String): String? =
        runCatching { java.net.URI(doc.location()).resolve(u.trim()).toString() }.getOrNull()?.takeIf { it.startsWith("http") }

    /** The best source for an image: lazy-loading attributes first, then the widest srcset entry, then src. */
    private fun imageUrl(doc: Document, img: Element): String? {
        val w = img.attr("width").toIntOrNull(); val h = img.attr("height").toIntOrNull()
        if ((w != null && w < 200) || (h != null && h < 120)) return null
        val srcset = listOf("data-srcset", "srcset").map { img.attr(it) }.firstOrNull { it.isNotBlank() }
            ?: img.parent()?.takeIf { it.tagName() == "picture" }?.selectFirst("source[srcset]")?.attr("srcset")
        val fromSet = srcset?.split(',')?.mapNotNull { part ->
            val bits = part.trim().split(Regex("\\s+"))
            val u = bits.getOrNull(0) ?: return@mapNotNull null
            val size = bits.getOrNull(1)?.removeSuffix("w")?.removeSuffix("x")?.toDoubleOrNull() ?: 0.0
            u to size
        }?.filter { it.second <= 1600 || it.second < 10 }?.maxByOrNull { it.second }?.first
        val raw = listOf("data-src", "data-original", "data-lazy-src").map { img.attr(it) }.firstOrNull { it.isNotBlank() }
            ?: fromSet ?: img.attr("src")
        if (raw.isBlank() || raw.startsWith("data:") || badImage.containsMatchIn(raw)) return null
        return abs(doc, raw)
    }

    /** A NewsArticle's "articleBody" from the page's structured data (JSON-LD), if it has one. */
    private fun jsonLdBody(doc: Document): String? = doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { s ->
        runCatching { findBody(kotlinx.serialization.json.Json.parseToJsonElement(s.data())) }.getOrNull()
    }?.takeIf { it.length > 200 }

    private fun findBody(e: kotlinx.serialization.json.JsonElement, depth: Int = 0): String? = when {
        depth > 8 -> null
        e is kotlinx.serialization.json.JsonObject ->
            (e["articleBody"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: e.values.firstNotNullOfOrNull { findBody(it, depth + 1) }
        e is kotlinx.serialization.json.JsonArray -> e.firstNotNullOfOrNull { findBody(it, depth + 1) }
        else -> null
    }
}
