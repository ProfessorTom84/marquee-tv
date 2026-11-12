package app.marquee.tv

import app.marquee.tv.data.Settings
import app.marquee.tv.news.Headline
import app.marquee.tv.news.NewsCategory
import app.marquee.tv.news.NewsFeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NewsTest {
    @Test fun feedItemsWithOutletsDatesAndPictures() {
        val xml = """<rss><channel><title>Top stories</title>
            <item><title>Big storm heads east - CNBC</title><link>https://example.com/a</link>
            <pubDate>Thu, 12 Nov 2026 14:12:00 GMT</pubDate><source url="https://www.cnbc.com">CNBC</source>
            <media:thumbnail width="240" height="135" url="https://img.example.com/a.jpg?w=1&amp;h=2"/></item>
            <item><title>Tom &amp; Jerry&#39;s &quot;return&quot;</title><link>https://x</link>
            <media:content url="https://v.example.com/clip.mp4" medium="video"/><enclosure url="https://img.example.com/b.jpg" type="image/jpeg" length="1"/></item>
            </channel></rss>"""
        val h = NewsFeeds.parse(xml, "Top stories")
        assertEquals(2, h.size)
        assertEquals("Big storm heads east", h[0].title)
        assertEquals("CNBC", h[0].source)
        assertEquals(Instant.parse("2026-11-12T14:12:00Z"), h[0].published)
        assertEquals("https://img.example.com/a.jpg?w=1&h=2", h[0].image)
        assertEquals("Tom & Jerry's \"return\"", h[1].title)
        assertEquals("Top stories", h[1].source)
        assertEquals("https://img.example.com/b.jpg", h[1].image) // the video is skipped
    }

    @Test fun cdataEntitiesAndOffsetDates() {
        val bbc = """<item><title><![CDATA[Why the UK economy is resilient]]></title>
            <link>https://www.bbc.co.uk/news/articles/c1?at_medium=RSS&amp;at_campaign=rss</link><pubDate>Thu, 12 Nov 2026 13:27:09 GMT</pubDate></item>"""
        val b = NewsFeeds.parse(bbc, "BBC News").single()
        assertEquals("Why the UK economy is resilient", b.title)
        assertEquals("https://www.bbc.co.uk/news/articles/c1?at_medium=RSS&at_campaign=rss", b.link)
        val other = """<item><title>Indonesia&apos;s VP resigns</title><link>https://example.org/a</link><pubDate>Thu, 12 Nov 2026 09:22:50 -0500</pubDate></item>"""
        val n = NewsFeeds.parse(other, "X").single()
        assertEquals("Indonesia's VP resigns", n.title)
        assertEquals(Instant.parse("2026-11-12T14:22:50Z"), n.published)
        assertNull(n.image)
    }

    @Test fun searchFeedLinksBecomeTheStorysOwnAddress() {
        val xml = """<item><title>Hochul signs new laws</title>
            <link>http://www.bing.com/news/apiclick.aspx?ref=FexRss&amp;aid=&amp;tid=1&amp;url=https%3a%2f%2fwww.newsday.com%2fnews%2fregion-state%2fveterans-abc&amp;c=123&amp;mkt=en-us</link>
            <News:Source>Newsday</News:Source><News:Image>http://www.bing.com/th?id=X&amp;pid=News</News:Image>
            <pubDate>Thu, 12 Nov 2026 10:00:00 GMT</pubDate></item>"""
        val h = NewsFeeds.parse(xml, "State news").single()
        assertEquals("https://www.newsday.com/news/region-state/veterans-abc", h.link)
        assertEquals("Newsday", h.source)
        assertEquals("http://www.bing.com/th?id=X&pid=News", h.image)
        assertEquals("https://example.com/plain", NewsFeeds.unwrap("https://example.com/plain"))
    }

    @Test fun feedsForWhereYouLive() {
        val brooklyn = Settings(cityName = "Brooklyn", latitude = 40.65, longitude = -73.95)
        val local = NewsFeeds.feedsFor(NewsCategory.LOCAL, brooklyn, "New York")
        assertEquals("CBS New York", local[0].first)
        assertTrue(local[0].second.contains("/newyork/latest/rss/local-news"))
        assertTrue(local[1].second.contains("q=Brooklyn+New+York"))
        assertTrue(NewsFeeds.feedsFor(NewsCategory.STATE, brooklyn, "New York").all { it.second.contains("%22New+York%22") })
        // No newsroom nearby: just the search. No city: no local or state news at all.
        val boise = Settings(cityName = "Boise", latitude = 43.62, longitude = -116.2)
        assertEquals(1, NewsFeeds.feedsFor(NewsCategory.LOCAL, boise, "Idaho").size)
        assertTrue(NewsFeeds.feedsFor(NewsCategory.LOCAL, Settings(), "").isEmpty())
        assertTrue(NewsFeeds.feedsFor(NewsCategory.STATE, Settings(), "").isEmpty())
        assertEquals("CBS Bay Area", NewsFeeds.nearestStation(37.34, -121.89)?.name) // San Jose
        assertNull(NewsFeeds.nearestStation(Double.NaN, Double.NaN))
        NewsCategory.entries.filter { it != NewsCategory.LOCAL && it != NewsCategory.STATE }
            .forEach { assertTrue(it.name, NewsFeeds.feedsFor(it, brooklyn, "New York").size >= 3) }
    }

    @Test fun mixIsNewestFirstWithoutRepeatedLinks() {
        fun h(t: String, min: Long, link: String) = Headline(t, "S", link, Instant.parse("2026-11-12T12:00:00Z").plusSeconds(min * 60))
        val out = NewsFeeds.mix(listOf(listOf(h("old", 1, "https://a/1"), h("new", 9, "https://a/2")), listOf(h("same link", 5, "https://a/1"))), 10)
        assertEquals(listOf("new", "old"), out.map { it.title })
    }

    @Test fun oneStoryPerTopic() {
        fun same(a: String, b: String, places: Set<String> = emptySet()) = NewsFeeds.sameTopic(NewsFeeds.topic(a, places), NewsFeeds.topic(b, places))
        // The same person in the news all day.
        val huang = listOf(
            "Jensen Huang agrees to testify before Congress", "Five ways Jensen Huang’s ouster from Nvidia could damage the AI industry",
            "Trump floats Apple chairman Tim Cook as Jensen Huang's successor", "The rise and fall of Jensen Huang - how the tech superstar became mired in scandal",
            "Nvidia's board preparing to oust CEO Jensen Huang, sources say",
        )
        huang.drop(1).forEach { assertTrue(it, same(huang[0], it)) }
        // One event, many angles.
        val places = NewsFeeds.placeWords("Brooklyn", "New York")
        assertTrue(same("Death toll from building collapse in NYC climbs as site excavation begins", "Brooklyn community shaken after \"catastrophic\" building collapse", places))
        // Two outlets, different wording.
        assertTrue(same("1 killed, 4 injured in shooting in D.C.'s Logan Circle neighborhood", "Suspected gunman shoots 5 people in D.C., killing 1, before dying by suicide"))
        assertTrue(same("Historic SS United States sinks off Florida's coast", "Famed ship SS United States deliberately sunk off Florida coast to become a reef"))
        // Different stories stay, even when they share a company, a place or an everyday word.
        assertTrue(!same("Whoop just launched a social network", "Norwest pushes deeper into AI with new \$4B fund"))
        assertTrue(!same("Sharp swings for Nvidia and oil prices weigh on Wall Street", "Company under scrutiny for ties to Nvidia and China"))
        assertTrue(!same("Death toll from building collapse in NYC climbs", "1 dead, 1 missing and 6 injured after scaffolding collapses on Loop Parkway", places))
        assertTrue(!same("New York veterans get enhanced support under new laws", "New York students will still take Regents exams", places))
        assertTrue(!same("Trump administration announces new tariffs", "Trump administration sued over housing rule"))
    }

    @Test fun onlyPagesThatAreStories() {
        assertTrue(NewsFeeds.isStoryLink("https://www.cbsnews.com/newyork/news/building-collapse-nyc/"))
        listOf(
            "https://www.cbsnews.com/video/bolton-reacts/", "https://www.cbsnews.com/newyork/live-updates/collapse/",
            "https://www.nbcnews.com/select/shopping/brands-rcna1", "https://www.cnn.com/2026/11/11/us/live-news/collapse", "ftp://x",
        ).forEach { assertTrue(it, !NewsFeeds.isStoryLink(it)) }
    }

    @Test fun lastDayComesFirstAndOlderOnlyTopsUp() {
        val now = Instant.parse("2026-11-12T15:00:00Z")
        fun h(t: String, hoursAgo: Long?) = Headline(t, "S", "https://example.com/$t", hoursAgo?.let { now.minusSeconds(it * 3600) })
        val (today, older) = NewsFeeds.byAge(listOf(h("a", 1), h("b", 23), h("c", 25), h("d", 70), h("undated", null)), now)
        assertEquals(listOf("a", "b"), today.map { it.title })
        assertEquals(listOf("c", "d", "undated"), older.map { it.title })
        assertEquals(24L, NewsFeeds.RECENT_HOURS)
    }

    @Test fun times() {
        val now = Instant.parse("2026-11-12T15:00:00Z")
        assertEquals("12 min ago", NewsFeeds.ago(Instant.parse("2026-11-12T14:48:00Z"), now))
        assertEquals("3 h ago", NewsFeeds.ago(Instant.parse("2026-11-12T12:00:00Z"), now))
        assertEquals("", NewsFeeds.ago(null, now))
        assertTrue(NewsFeeds.parse("<rss>no items</rss>", "x").isEmpty())
    }
}
