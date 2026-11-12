package app.marquee.tv

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.marquee.tv.news.Article
import app.marquee.tv.news.NewsCategory
import app.marquee.tv.news.NewsSection
import app.marquee.tv.news.Story
import app.marquee.tv.ui.screens.NewsContent
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

    private fun story(cat: NewsCategory, title: String, source: String, min: Long) = Story(
        cat, title, source, "https://example.com/${title.hashCode()}", System.currentTimeMillis() - min * 60_000, null,
        "The council voted 7 to 2 on Tuesday night to approve the plan, ending a dispute that has divided the neighbourhood since the old ferry terminal closed.",
    )
    private val h = story(NewsCategory.LOCAL, "City council approves new waterfront park after two-year fight", "The Local Times", 14)

    @Test fun reader() {
        val text = "The council voted 7 to 2 on Tuesday night to approve the plan, ending a dispute that has divided the neighbourhood since the old ferry terminal closed. Supporters packed the chamber."
        val a = Article(h.title, "The Local Times", "Jane Doe", "", null, listOf(
            Article.Block.para(text), Article.Block.para(text),
            Article.Block.heading("What happens next"), Article.Block.para(text), Article.Block.para(text),
        ), "https://example.com/1")
        paparazzi.snapshot { MarqueeTheme { ArticleContent(h, a, failed = false) } }
    }

    @Test fun newsScreen() {
        val sections = listOf(
            NewsSection(NewsCategory.LOCAL, "Brooklyn", listOf(h, story(NewsCategory.LOCAL, "Subway line to close for weekend repairs", "CBS New York", 50),
                story(NewsCategory.LOCAL, "New bike lanes open along the waterfront", "Streetsblog", 120), story(NewsCategory.LOCAL, "School board sets vote on later start times", "The Local Times", 200),
                story(NewsCategory.LOCAL, "Farmers market moves indoors for winter", "Brooklyn Paper", 260))),
            NewsSection(NewsCategory.STATE, "New York", listOf(story(NewsCategory.STATE, "Governor signs veterans' benefits laws", "Newsday", 30),
                story(NewsCategory.STATE, "State parks recruiting seasonal rangers", "Times Union", 90), story(NewsCategory.STATE, "Legislature returns for budget talks", "Gothamist", 150))),
            NewsSection(NewsCategory.NATIONAL, "National", listOf(story(NewsCategory.NATIONAL, "Storm system to bring heavy rain to the coast", "CBS News", 40))),
            NewsSection(NewsCategory.POLITICS, "Politics", listOf(story(NewsCategory.POLITICS, "Senate reaches deal on spending bill", "PBS NewsHour", 25))),
        )
        paparazzi.snapshot { MarqueeTheme { NewsContent(sections, h) {} } }
    }
}
