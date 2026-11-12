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
    private val long = "This is a sentence long enough to count as part of the story itself, not a caption."

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
            </div>
            <aside><p>$long (sidebar)</p></aside><footer><p>Copyright 2026 Example News. All rights reserved worldwide.</p></footer>
            </body></html>"""
        val a = ArticleReader.extract(html, "https://example.com/news/story")!!
        assertEquals("A big thing happened", a.title)
        assertEquals("Example News", a.site)
        assertEquals("Jane Doe", a.byline)
        assertEquals("https://example.com/img/hero.jpg", a.heroImage)
        val paras = a.blocks.filterIsInstance<Article.Block.Para>().map { it.text }
        assertEquals(3, paras.size)
        assertTrue(paras.all { it.contains("long enough to count") })
        assertFalse(paras.any { it.contains("menu") || it.contains("sidebar") || it.startsWith("WATCH") || it.startsWith("Stand with") })
        assertEquals(Article.Block.Heading("What happens next"), a.blocks.first { it is Article.Block.Heading })
        val img = a.blocks.filterIsInstance<Article.Block.Image>().single()
        assertEquals("https://example.com/img/inline.jpg", img.url)
        assertEquals("Rescue crews at the scene", img.caption)
    }

    @Test fun fallsBackToStructuredDataAndGivesUpOnEmptyPages() {
        val body = (1..4).joinToString("\\n") { "Paragraph $it of the story, which is long enough to keep around." }
        val ld = """<html><head><meta property="og:title" content="Scripted page">
            <script type="application/ld+json">[{"@type":"NewsArticle","articleBody":"$body"}]</script></head>
            <body><div id="app"></div></body></html>"""
        val a = ArticleReader.extract(ld, "https://example.com/s")!!
        assertEquals(4, a.blocks.count { it is Article.Block.Para })
        assertNull(ArticleReader.extract("<html><body><p>Too short.</p></body></html>", "https://example.com/x"))
    }

    @Test fun bylinesAndLinks() {
        assertEquals("Associated Press", ArticleReader.cleanByline("By — Associated Press Associated Press"))
        assertEquals("Jane Doe and John Roe", ArticleReader.cleanByline("By: Jane Doe and John Roe"))
        assertFalse(ArticleReader.canOpen("https://news.google.com/rss/articles/CBMi123?oc=5"))
        assertTrue(ArticleReader.canOpen("https://www.bbc.co.uk/news/articles/abc"))
    }
}
