package app.marquee.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.marquee.tv.Nav
import app.marquee.tv.news.Article
import app.marquee.tv.news.Headline
import app.marquee.tv.news.NewsFeeds
import app.marquee.tv.ui.MarqueeVm
import app.marquee.tv.ui.components.QrImage
import app.marquee.tv.ui.theme.C
import app.marquee.tv.ui.theme.T
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * A news story read on the TV: headline, main photo, text, and the pictures and captions from the article.
 * Up and down scroll; Back closes. If the site doesn't allow it (or it's a Google News link), a QR code opens
 * the story on your phone instead.
 */
@Composable
fun ArticleScreen(vm: MarqueeVm, @Suppress("UNUSED_PARAMETER") nav: Nav, headline: Headline) {
    var article by remember(headline.link) { mutableStateOf<Article?>(null) }
    var failed by remember(headline.link) { mutableStateOf(false) }
    LaunchedEffect(headline.link) {
        val a = vm.readArticle(headline.link)
        if (a == null) failed = true else article = a
    }
    ArticleContent(headline, article, failed)
}

/** What the reader shows (separate from the loading, so it can be previewed and tested). */
@Composable
fun ArticleContent(headline: Headline, article: Article?, failed: Boolean) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val step = with(LocalDensity.current) { 300.dp.toPx() }
    val a = article
    val text = Modifier.widthIn(max = 720.dp).fillMaxWidth()

    Box(
        Modifier.fillMaxSize().background(C.Ink).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionDown, Key.PageDown -> { scope.launch { list.animateScrollBy(step) }; true }
                Key.DirectionUp, Key.PageUp -> { scope.launch { list.animateScrollBy(-step) }; true }
                else -> false
            }
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = 44.dp, bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("head") {
                Column(text, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        listOf(a?.site ?: headline.source, NewsFeeds.ago(headline.published)).filter { it.isNotBlank() }.joinToString(" · ").uppercase(),
                        style = T.eyebrow,
                    )
                    Text(a?.title ?: headline.title, style = T.serif(34.sp))
                    if (!a?.byline.isNullOrBlank()) Text("By ${a!!.byline}", style = T.sans(12.sp, FontWeight.SemiBold, C.TextDim))
                }
            }
            a?.heroImage?.let { hero -> item("hero") { Picture(hero, "", text) } }
            when {
                failed -> item("qr") { PhoneLink(headline.link, text, "This story can't be shown on the TV (the site doesn't allow it, or it's behind a paywall). Scan to read it on your phone.") }
                a == null -> item("loading") { Text("Loading the story…", style = T.body, modifier = text) }
                else -> {
                    items(a.blocks.size) { i ->
                        when (val b = a.blocks[i]) {
                            is Article.Block.Para -> Text(b.text, style = T.sans(15.sp, FontWeight.Normal, C.Text).copy(lineHeight = 24.sp), modifier = text)
                            is Article.Block.Heading -> Text(b.text, style = T.sans(18.sp, FontWeight.ExtraBold), modifier = text.padding(top = 8.dp))
                            is Article.Block.Image -> Picture(b.url, b.caption, text)
                        }
                    }
                    item("end") { PhoneLink(a.url, text, "Story from ${a.site}. Scan to read the original on your phone.") }
                }
            }
        }
        Text(
            "▲ ▼ to scroll · Back to close", style = T.sans(9.sp, FontWeight.Bold, C.TextMute),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 16.dp)
                .background(C.Ink.copy(alpha = 0.85f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun Picture(url: String, caption: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AsyncImage(
            url, caption.ifBlank { null }, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(10.dp)),
        )
        if (caption.isNotBlank()) Text(caption, style = T.sans(10.sp, color = C.TextDim))
    }
}

@Composable
private fun PhoneLink(url: String, modifier: Modifier, message: String) {
    Row(modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        QrImage(url, 120.dp)
        Text(message, style = T.body, modifier = Modifier.weight(1f))
    }
}
