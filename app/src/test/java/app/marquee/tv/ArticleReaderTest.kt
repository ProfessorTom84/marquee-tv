package app.marquee.tv

import app.marquee.tv.news.Article
import app.marquee.tv.news.ArticleReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up pages shaped like real news sites. */
class ArticleReaderTest {
    private val long = "This is a sentence long enough to count as part of the story itself, not a caption. " +
        "It carries on for a while so that four of them add up to a proper article rather than a teaser."

    private fun paras(a: Article) = a.blocks.filter { it.kind == Article.Kind.PARA }.map { it.text }

    @Test fun keepsTheStoryAndDropsTheRest() {
        val html = """<html><head><title>T</title>
            <meta property="og:title" content="A big thing happened">
            <meta property="og:site_name" content="Example News">
            <meta property="og:image" content="/img/hero.jpg">
            <meta name="author" content="By — Jane Doe">
            <meta property="article:published_time" content="2026-11-12T10:00:00Z"></head>
            <body><nav><p>$long (menu)</p></nav>
            <div class="story">
              <p>First: $long</p>
              <p>WATCH: Another story you might like, with a long enough title here</p>
              <figure><img src="/img/inline.jpg" width="1200" height="800"><figcaption>Rescue crews at the scene</figcaption></figure>
              <h2>What happens next</h2>
              <p>Second: $long</p>
              <img src="/img/logo.png">
              <p>Advertisement</p>
              <p>Stand with free and independent journalism. Join today and support the newsroom.</p>
              <p>Third: $long</p>
              <p>Fourth: $long</p>
            </div>
            <aside><p>$long (sidebar)</p></aside><footer><p>Copyright 2026 Example News. All rights reserved worldwide.</p></footer>
            </body></html>"""
        val a = ArticleReader.extract(html, "https://example.com/news/story")!!
        assertEquals("A big thing happened", a.title)
        assertEquals("Example News", a.site)
        assertEquals("Jane Doe", a.byline)
        assertEquals("https://example.com/img/hero.jpg", a.heroImage)
        assertEquals(4, paras(a).size)
        assertTrue(paras(a).all { it.contains("long enough to count") })
        assertFalse(paras(a).any { it.contains("menu") || it.contains("sidebar") || it.startsWith("WATCH") || it.startsWith("Stand with") })
        assertEquals(Article.Block.heading("What happens next"), a.blocks.first { it.kind == Article.Kind.HEADING })
        val img = a.blocks.single { it.kind == Article.Kind.IMAGE }
        assertEquals("https://example.com/img/inline.jpg", img.url)
        assertEquals("Rescue crews at the scene", img.text)
    }

    @Test fun fallsBackToStructuredData() {
        val body = (1..5).joinToString("\\n") { "Paragraph $it of the story, which is long enough to keep around, and then keeps going so that the whole thing reads like an article and not a caption under a video." }
        val ld = """<html><head><meta property="og:title" content="Scripted page">
            <script type="application/ld+json">[{"@type":"NewsArticle","articleBody":"$body"}]</script></head>
            <body><div id="app"></div></body></html>"""
        assertEquals(5, paras(ArticleReader.extract(ld, "https://example.com/s")!!).size)
    }

    /** "Only stories that open on the TV": teasers above a paywall and video pages don't count as readable. */
    @Test fun teasersAndVideoPagesAreNotStories() {
        assertNull(ArticleReader.extract("<html><body><p>Too short.</p></body></html>", "https://example.com/x"))
        val teaser = "<html><body><div><p>$long</p><p>$long</p><p>Subscribe to keep reading this story.</p></div></body></html>"
        assertNull(ArticleReader.extract(teaser, "https://example.com/paywalled"))
    }

    @Test fun bylines() {
        assertEquals("Associated Press", ArticleReader.cleanByline("By — Associated Press Associated Press"))
        assertEquals("Jane Doe and John Roe", ArticleReader.cleanByline("By: Jane Doe and John Roe"))
    }
}
