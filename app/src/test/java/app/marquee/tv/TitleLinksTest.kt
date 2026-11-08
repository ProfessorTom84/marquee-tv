package app.marquee.tv

import app.marquee.tv.links.TitleLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleLinksTest {
    @Test fun watchmodeNamesMapToOurServices() {
        assertEquals(8, TitleLinks.providerFor("Netflix"))
        assertEquals(9, TitleLinks.providerFor("Prime Video"))
        assertEquals(337, TitleLinks.providerFor("Disney+"))
        assertEquals(1899, TitleLinks.providerFor("Max"))
        assertEquals(1899, TitleLinks.providerFor("HBO MAX"))
        assertEquals(350, TitleLinks.providerFor("AppleTV+"))
        assertEquals(531, TitleLinks.providerFor("Paramount+"))
        assertEquals(386, TitleLinks.providerFor("Peacock Premium"))
        // Sold through another app: not a link into that service's own app.
        assertNull(TitleLinks.providerFor("Max Amazon Channel"))
        assertNull(TitleLinks.providerFor("Paramount+ Amazon Channel"))
        assertNull(TitleLinks.providerFor("Tubi TV"))
    }

    @Test fun netflixTitleLinksBecomeWatchLinks() {
        assertEquals("https://www.netflix.com/watch/80057281", TitleLinks.forTv("https://www.netflix.com/title/80057281"))
        assertEquals("https://www.netflix.com/watch/80057281", TitleLinks.forTv("http://netflix.com/gb/title/80057281"))
        assertEquals("https://www.hulu.com/watch/abc", TitleLinks.forTv("https://www.hulu.com/watch/abc"))
    }
}
