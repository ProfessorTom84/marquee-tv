package app.marquee.tv.news

import app.marquee.tv.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** A news story pulled out of its web page, to read on the TV: text, pictures and captions, no menus or ads. */
data class Article(
    val title: String,
    val site: String,
    val byline: String,
    val published: String,
    val heroImage: String?,
    val blocks: List<Block>,
    val url: String,
) {
    sealed interface Block {
        data class Para(val text: String) : Block
        data class Heading(val text: String) : Block
        data class Image(val url: String, val caption: String) : Block
    }
}

/**
 * Fetches a story's page and keeps just the story, like a browser's reader mode. Marquee identifies itself
 * honestly; when a page can't be read (paywalls, sites that turn it away, Google News links), it returns null and
 * the reader offers a QR code to read the story on your phone instead.
 */
object ArticleReader {
    /** Google News links lead to a Google page, not the story, so those stories open as a QR code instead. */
    fun canOpen(link: String) = link.startsWith("http") && !link.contains("news.google.com/")

    suspend fun read(link: String): Article? = withContext(Dispatchers.IO) {
        if (!canOpen(link)) return@withContext null
        val html = runCatching { Http.getText(link, mapOf("User-Agent" to NewsFeeds.USER_AGENT, "Accept" to "text/html"), cacheMinutes = 60) }.getOrNull()
            ?: return@withContext null
        extract(html, link)
    }

    /** Lines that aren't the story: ads, sign-up and donation prompts, "Watch:" links to other stories. */
    private val boilerplate = Regex(
        "^(advertisement\\b|ad\\b|read more\\b|read next\\b|sign up\\b|subscribe\\b|follow us\\b|share this\\b|copyright\\b|©|" +
            "related:|watch:|listen:|read:|more:|see also:|getty images\\b|image source\\b|" +
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
                    if (t.length >= 25 && !boilerplate.containsMatchIn(t)) blocks += Article.Block.Para(t)
                }
                "h2", "h3" -> e.text().trim().takeIf { it.length in 3..140 }?.let { blocks += Article.Block.Heading(it) }
                "img" -> imageUrl(doc, e)?.takeIf { seenImages.add(it) }?.let { blocks += Article.Block.Image(it, "") }
                "figcaption" -> {
                    val last = blocks.lastOrNull()
                    val cap = e.text().trim()
                    if (last is Article.Block.Image && last.caption.isBlank() && cap.isNotBlank()) blocks[blocks.lastIndex] = last.copy(caption = cap)
                }
            }
        }
        // Not enough on the page itself (built by scripts)? Many news sites also publish the text as structured data.
        if (blocks.count { it is Article.Block.Para } < 3 && ldBody != null) {
            blocks.removeAll { it is Article.Block.Para || it is Article.Block.Heading }
            ldBody.split(Regex("\\n+|(?<=[.!?\"”])\\s{2,}")).map { it.trim() }.filter { it.length >= 25 }.forEach { blocks += Article.Block.Para(it) }
        }
        if (blocks.count { it is Article.Block.Para } < 2) return null
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
