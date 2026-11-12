package app.marquee.tv

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.marquee.tv.news.Article
import app.marquee.tv.news.Headline
import app.marquee.tv.ui.screens.ArticleContent
import app.marquee.tv.ui.theme.MarqueeTheme
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

class ArticleScreenTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1920, screenHeight = 1080, xdpi = 320, ydpi = 320,
            density = Density.XHIGH, orientation = ScreenOrientation.LANDSCAPE, softButtons = false,
        ),
        environment = app.cash.paparazzi.detectEnvironment().copy(compileSdkVersion = 34),
    )

    private val h = Headline("City council approves new waterfront park after two-year fight", "The Local Times", "https://example.com/1",
        java.time.Instant.now().minusSeconds(14 * 60))

    @Test fun reader() {
        val text = "The council voted 7 to 2 on Tuesday night to approve the plan, ending a dispute that has divided the neighbourhood since the old ferry terminal closed. Supporters packed the chamber."
        val a = Article(h.title, "The Local Times", "Jane Doe", "", null, listOf(
            Article.Block.Para(text), Article.Block.Para(text),
            Article.Block.Heading("What happens next"), Article.Block.Para(text),
        ), "https://example.com/1")
        paparazzi.snapshot { MarqueeTheme { ArticleContent(h, a, failed = false) } }
    }
}
