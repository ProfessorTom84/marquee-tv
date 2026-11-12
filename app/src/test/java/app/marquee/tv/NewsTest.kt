package app.marquee.tv

import app.marquee.tv.news.NewsFeeds
import app.marquee.tv.news.NewsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NewsTest {
    @Test fun googleNewsTitlesLoseTheirOutletSuffix() {
        val xml = """<rss><channel><title>Top stories</title>
            <item><title>Big storm heads east - CNBC</title><link>https://news.google.com/rss/articles/abc</link>
            <pubDate>Thu, 12 Nov 2026 14:12:00 GMT</pubDate><source url="https://www.cnbc.com">CNBC</source></item>
            <item><title>Tom &amp; Jerry&#39;s &quot;return&quot; - The Verge</title><link>https://x</link><source url="v">The Verge</source></item>
            </channel></rss>"""
        val h = NewsFeeds.parse(xml, "Top stories")
        assertEquals(2, h.size)
        assertEquals("Big storm heads east", h[0].title)
        assertEquals("CNBC", h[0].source)
        assertEquals("https://news.google.com/rss/articles/abc", h[0].link)
        assertEquals(Instant.parse("2026-11-12T14:12:00Z"), h[0].published)
        assertEquals("Tom & Jerry's \"return\"", h[1].title)
    }

    @Test fun bbcCdataAndNprEntities() {
        val bbc = """<item><title><![CDATA[Why the UK economy is resilient]]></title><description><![CDATA[x]]></description>
            <link>https://www.bbc.co.uk/news/articles/c1?at_medium=RSS&amp;at_campaign=rss</link><pubDate>Thu, 12 Nov 2026 13:27:09 GMT</pubDate></item>"""
        val b = NewsFeeds.parse(bbc, "BBC News").single()
        assertEquals("Why the UK economy is resilient", b.title)
        assertEquals("BBC News", b.source)
        assertEquals("https://www.bbc.co.uk/news/articles/c1?at_medium=RSS&at_campaign=rss", b.link)
        val npr = """<item><title>Indonesia&apos;s VP resigns</title><link>https://www.npr.org/a</link><pubDate>Thu, 12 Nov 2026 09:22:50 -0500</pubDate></item>"""
        val n = NewsFeeds.parse(npr, "NPR").single()
        assertEquals("Indonesia's VP resigns", n.title)
        assertEquals(Instant.parse("2026-11-12T14:22:50Z"), n.published)
    }

    @Test fun feedsAndTimes() {
        assertTrue(NewsFeeds.url(NewsSource.TOP, "gb", "")!!.contains("gl=GB&ceid=GB:en"))
        assertTrue(NewsFeeds.url(NewsSource.LOCAL, "US", "New York")!!.contains("q=New+York"))
        assertNull(NewsFeeds.url(NewsSource.LOCAL, "US", ""))
        assertNull(NewsFeeds.url(NewsSource.OFF, "US", "x"))
        val now = Instant.parse("2026-11-12T15:00:00Z")
        assertEquals("12 min ago", NewsFeeds.ago(Instant.parse("2026-11-12T14:48:00Z"), now))
        assertEquals("3 h ago", NewsFeeds.ago(Instant.parse("2026-11-12T12:00:00Z"), now))
        assertEquals("", NewsFeeds.ago(null, now))
        assertTrue(NewsFeeds.parse("<rss>no items</rss>", "x").isEmpty())
    }
}
