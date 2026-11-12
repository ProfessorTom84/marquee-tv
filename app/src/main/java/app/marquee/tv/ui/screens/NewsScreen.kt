package app.marquee.tv.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.marquee.tv.Nav
import app.marquee.tv.Screen
import app.marquee.tv.news.NewsFeeds
import app.marquee.tv.news.NewsSection
import app.marquee.tv.news.Story
import app.marquee.tv.ui.MarqueeVm
import app.marquee.tv.ui.components.Focusable
import app.marquee.tv.ui.components.PinnedRow
import app.marquee.tv.ui.components.SectionHeader
import app.marquee.tv.ui.components.fallbackBrush
import app.marquee.tv.ui.theme.C
import app.marquee.tv.ui.theme.T
import coil.compose.AsyncImage

/**
 * The news: a featured story on top, then a row of stories for each category (Local, State, National, Politics,
 * Tech, Business), each with its picture. Every story here has already been read by Marquee, so OK opens it on
 * the TV straight away.
 */
@Composable
fun NewsScreen(vm: MarqueeVm, nav: Nav, lead: Story?) {
    val sections by vm.news.collectAsState()
    NewsContent(sections, lead) { nav.go(Screen.Article(it)) }
}

private fun byline(s: Story) = listOf(s.source, NewsFeeds.ago(s.published)).filter { it.isNotBlank() }.joinToString(" · ")

@Composable
fun NewsContent(sections: List<NewsSection>, lead: Story?, onRead: (Story) -> Unit) {
    val featured = lead ?: sections.firstOrNull()?.stories?.firstOrNull()
    val featuredIn = sections.firstOrNull { sec -> sec.stories.any { it.link == featured?.link } }
    val first = remember { FocusRequester() }
    LaunchedEffect(featured?.link) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0E1428), C.Ink)))) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 30.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("title") { Text("News", style = T.serif(34.sp), modifier = Modifier.padding(start = 48.dp)) }
            if (featured == null) item("empty") {
                Text("Getting the headlines… Stories appear here once Marquee has checked they open on the TV.", style = T.body, modifier = Modifier.padding(horizontal = 48.dp))
            } else item("featured") {
                Focusable(Modifier.padding(horizontal = 48.dp).fillMaxWidth().focusRequester(first), onClick = { onRead(featured) }) { f ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF))
                            .border(if (f) 2.dp else 1.dp, if (f) Color(0xF2FFF0DC) else C.Stroke, RoundedCornerShape(14.dp)).padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StoryImage(featured, Modifier.size(380.dp, 214.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text((featuredIn?.title ?: featured.category.label).uppercase(), style = T.eyebrow)
                            Text(featured.title, style = T.serif(30.sp).copy(lineHeight = 34.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (featured.summary.isNotBlank()) Text(featured.summary, style = T.sans(12.sp, FontWeight.Medium, C.TextDim).copy(lineHeight = 18.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(if (f) "Press OK to read" else byline(featured), style = T.sans(10.sp, FontWeight.Bold, if (f) C.AmberSoft else C.Cool))
                        }
                    }
                }
            }
            sections.forEach { sec ->
                val stories = sec.stories.filter { it.link != featured?.link }
                if (stories.isNotEmpty()) item(sec.category.name) {
                    Column {
                        SectionHeader(sec.title, modifier = Modifier.padding(start = 48.dp))
                        val rowFirst = remember { FocusRequester() }
                        PinnedRow(48.dp) {
                            LazyRow(
                                Modifier.focusRestorer(rowFirst), horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 6.dp),
                            ) {
                                items(stories.size, key = { stories[it].link }) { i ->
                                    StoryCard(stories[i], if (i == 0) Modifier.focusRequester(rowFirst) else Modifier) { onRead(stories[i]) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryImage(story: Story, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(remember(story.link) { fallbackBrush(story.link) })) {
        if (story.image != null) AsyncImage(story.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun StoryCard(story: Story, modifier: Modifier, onClick: () -> Unit) {
    Focusable(modifier.width(236.dp), onClick = onClick) { f ->
        val lift by animateFloatAsState(if (f) 1.05f else 1f, label = "lift")
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(
                Modifier.graphicsLayer { scaleX = lift; scaleY = lift }
                    .then(if (f) Modifier.border(2.dp, Color(0xF2FFF0DC), RoundedCornerShape(10.dp)) else Modifier),
            ) { StoryImage(story, Modifier.size(236.dp, 133.dp)) }
            Text(story.title, style = T.sans(11.sp, FontWeight.Bold, if (f) C.Text else Color(0xFFE2DED6)).copy(lineHeight = 14.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(byline(story), style = T.sans(8.sp, color = C.Cool), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
