package app.marquee.tv

import app.marquee.tv.net.Http
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class HttpDiskCacheTest {
    private val hits = AtomicInteger()
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response =
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"n":${hits.incrementAndGet()}}""")
    }
    private lateinit var url: String

    @Before fun start() {
        server.start(5000, true)
        url = "http://127.0.0.1:${server.listeningPort}/thing"
        Http.init(Files.createTempDirectory("httpcache").toFile())
    }

    @After fun stop() = server.stop()

    @Test fun lookupsSurviveARestart() = runBlocking {
        assertEquals("""{"n":1}""", Http.getText(url, cacheMinutes = 60))
        Http.forgetMemory() // as if Marquee restarted or updated
        assertEquals("""{"n":1}""", Http.getText(url, cacheMinutes = 60))
        assertEquals(1, hits.get())
        // Uncached calls always go to the network.
        Http.getText("$url?fresh", cacheMinutes = 0)
        assertEquals(2, hits.get())
    }

    @Test fun clearingTheCacheFetchesAgain() = runBlocking {
        Http.getText(url, cacheMinutes = 60)
        Http.clearCache()
        Thread.sleep(200) // the disk copy is deleted in the background
        assertEquals("""{"n":2}""", Http.getText(url, cacheMinutes = 60))
    }
}
