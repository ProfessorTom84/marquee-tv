package app.marquee.tv.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.marquee.tv.data.Row as DataRow
import app.marquee.tv.data.Title
import app.marquee.tv.recs.FeedLayout
import app.marquee.tv.recs.HomeData
import app.marquee.tv.system.PerfCheck
import app.marquee.tv.ui.components.CardBake
import app.marquee.tv.ui.components.CardZoom
import app.marquee.tv.ui.components.Chip
import app.marquee.tv.ui.components.Focusable
import app.marquee.tv.ui.components.PinnedRow
import app.marquee.tv.ui.components.cardImageUrl
import app.marquee.tv.ui.components.dominantColor
import app.marquee.tv.ui.components.fallbackBrush
import app.marquee.tv.ui.components.rememberRowStart
import app.marquee.tv.ui.theme.C
import app.marquee.tv.ui.theme.T
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest

/*
 * The shelf: the two rows at the foot of the Dashboard.
 *
 * The row you are on sits on top, with full-size tiles; the tile in focus is larger still and carries its details.
 * The row after it shows underneath, named, smaller and dimmer. Down moves both up by one. Nothing else on the
 * screen moves, so the sky above stays open however far down the rows you go.
 *
 * Underneath it is an ordinary list of rows, all the same height, in a window exactly tall enough for one row and the
 * top of the next. A row's tiles grow as it becomes the row in focus (they are laid out from its place between "next"
 * and "in focus", so growing never recomposes anything).
 */

/** Sizes, in dp at a 960 × 540 TV screen. */
internal object ShelfSize {
    val start = 48.dp
    val gap = 11.dp
    /** Above every row's tiles: the next row's name sits here; for the row in focus it is room for the tile's glow. */
    val head = 25.dp
    /** The tiles' strip: as tall as the tile in focus. */
    val strip = 90.dp
    val row = head + strip
    val nextTile = 58.5.dp
    /** One row and the top of the next: its name and its (smaller) tiles. */
    val height = row + head + nextTile

    const val NEXT_W = 104f
    const val REST_W = 120f
    const val FOCUS_W = 160f
    const val ASPECT = 9f / 16f

    /**
     * A row's padding: its start margin, and enough room after its last tile for that tile to reach the start too.
     * The tile in focus then always sits at the start margin, to the very end of a row, and a row never shifts
     * sideways when the tile in focus changes size.
     */
    @Composable
    fun rowPadding(): PaddingValues {
        val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
        return PaddingValues(start = start, end = (screen - start.value - FOCUS_W).coerceAtLeast(start.value).dp)
    }
}

/** One row of the shelf: a row of titles, or the apps. */
internal sealed interface Shelf {
    val key: String
    /** Short, for the line that names every row. */
    val name: String
    /** In full, above the row when it is the next one. */
    val title: String

    data class Titles(val data: DataRow) : Shelf {
        override val key get() = data.key
        override val name get() = when (data.key) {
            FeedLayout.FOR_YOU -> "For you"
            FeedLayout.YOUTUBE -> "YouTube"
            FeedLayout.TRENDING -> "Trending"
            FeedLayout.TOP_RATED -> "Top rated"
            else -> data.title
        }
        override val title get() = data.title
    }

    data object Apps : Shelf {
        override val key = "apps"
        override val name = "Apps"
        override val title = "Apps"
    }
}

/**
 * The rows in order: "For you" first, the apps second, then everything else. When "Continue watching" is the row
 * after "For you", it goes ahead of the apps: what you were watching is then one press of Down away, and already
 * showing underneath when you arrive.
 */
internal fun shelves(home: HomeData?): List<Shelf> {
    val rows = home?.rows.orEmpty().distinctBy { it.key }.filter { it.key != Shelf.Apps.key && it.items.isNotEmpty() }
    val lead = when {
        rows.firstOrNull()?.key != FeedLayout.FOR_YOU -> 0
        rows.getOrNull(1)?.key == FeedLayout.CONTINUE -> 2
        else -> 1
    }
    return rows.take(lead).map { Shelf.Titles(it) } + Shelf.Apps + rows.drop(lead).map { Shelf.Titles(it) }
}

/**
 * One tile. Its size comes from where its row is between "next" (0) and "in focus" (1), [rowT], and from whether the
 * tile itself has focus; both are read while laying out. In the next row tiles sit at the top of the strip; in the
 * row in focus they are centred in it.
 */
@Composable
internal fun ShelfTile(
    rowT: () -> Float,
    modifier: Modifier = Modifier,
    glow: Color? = null,
    lifted: Boolean = false, // drawn as if in focus (an app being moved)
    onFocus: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focusT = animateFloatAsState(if (focused || lifted) 1f else 0f, tween(180, easing = FastOutSlowInEasing), label = "tile")
    val size = Modifier.layout { measurable, _ ->
        val r = rowT().coerceIn(0f, 1f)
        val inFocus = ShelfSize.REST_W + (ShelfSize.FOCUS_W - ShelfSize.REST_W) * focusT.value
        val w = (ShelfSize.NEXT_W + (inFocus - ShelfSize.NEXT_W) * r).dp.toPx()
        val h = w * ShelfSize.ASPECT
        val strip = ShelfSize.strip.toPx()
        val tile = measurable.measure(Constraints.fixed(w.toInt(), h.toInt()))
        layout(tile.width, strip.toInt()) { tile.place(0, (r * (strip - h) / 2f).toInt()) }
    }
    Focusable(modifier.then(size), onClick = onClick, onFocus = { focused = it; onFocus(it) }) { f ->
        val shape = RoundedCornerShape(if (f) 10.dp else 8.dp)
        Box(
            Modifier.fillMaxSize()
                // Only the tile in focus casts a (coloured) shadow: resting shadows cost real GPU time on a TV.
                .then(if (f || lifted) Modifier.shadow(18.dp, shape, ambientColor = glow ?: C.Amber, spotColor = glow ?: C.Amber) else Modifier)
                .then(if (f || lifted) Modifier.border(2.dp, if (lifted) C.Amber else Color(0xF2FFF0DC), shape) else Modifier),
        ) { content(f) }
    }
}

private val TileShadow = Shadow(Color(0xB3000000), Offset(0f, 1f), 6f)

/** A title on the shelf: its artwork with its name on it; in focus, also its badge and what it is and where. */
@Composable
internal fun TitleTile(title: Title, current: Boolean, rowT: () -> Float, modifier: Modifier, onFocus: (Title) -> Unit, onClick: (Title) -> Unit) {
    val context = LocalContext.current
    val loader = remember { coil.Coil.imageLoader(context) }
    val primary = remember(title.id, title.backdropUrl, title.posterUrl) { cardImageUrl(title.backdropUrl ?: title.posterUrl) }
    var glow by remember(title.id) { mutableStateOf<Color?>(null) }
    var imgFailed by remember(title.id) { mutableStateOf(false) }
    var artLoaded by remember(title.id) { mutableStateOf(false) }
    val plain = PerfCheck.liteLevel >= 2 // the performance check's "plain cards" step
    val dens = LocalDensity.current
    // Baked once, at the size of the tile in focus (artwork, rounded corners, shade), and drawn smaller for the rest:
    // a tile never fetches or builds a second picture when it gains or loses focus.
    val painter = rememberAsyncImagePainter(
        model = remember(title.id, imgFailed, primary, plain) {
            val w = with(dens) { ShelfSize.FOCUS_W.dp.roundToPx() }
            ImageRequest.Builder(context).data(if (imgFailed) title.posterUrl else primary).size(w, (w * ShelfSize.ASPECT).toInt())
                .transformations(CardBake(context, loader, null, with(dens) { 10.dp.toPx() }, 0f, shade = !plain))
                .build()
        },
        onError = { if (!imgFailed && title.posterUrl != null && title.posterUrl != primary) imgFailed = true },
        onSuccess = { artLoaded = true },
    )
    val fallback = remember(title.id) { fallbackBrush(title.id) }
    val where = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    ShelfTile(
        rowT, modifier, glow,
        onFocus = { if (it) onFocus(title) },
        onClick = {
            if (title.youtubeId == null) CardZoom.clicked(title.id, title.backdropUrl ?: title.posterUrl, where[0]?.takeIf { it.isAttached }?.boundsInRoot())
            onClick(title)
        },
    ) { f ->
        // Only work out the glow colour for the tile you're on (from the picture that's already cached).
        if (f && glow == null) LaunchedEffect(title.id) { glow = dominantColor(context, loader, primary) }
        Box(
            Modifier.fillMaxSize().onPlaced { where[0] = it }
                .then(if (artLoaded) Modifier else Modifier.background(fallback, RoundedCornerShape(8.dp)))
                .drawBehind { with(painter) { draw(size) } },
        ) {
            if (f) {
                if (!plain) (title.badge ?: fourK(title))?.let { Chip(it, C.AmberSoft, modifier = Modifier.align(Alignment.TopStart).padding(7.dp)) }
                Column(Modifier.align(Alignment.BottomStart).padding(start = 9.dp, end = 9.dp, bottom = 7.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(title.title, style = T.sans(14.sp, FontWeight.ExtraBold).copy(lineHeight = 16.sp, shadow = TileShadow), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val about = about(title)
                    if (about.isNotBlank()) Text(about, style = T.sans(8.5.sp, FontWeight.SemiBold, Color(0xFFEDE6DA)).copy(shadow = TileShadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text(
                    title.title, style = T.sans(if (current) 10.sp else 9.sp, FontWeight.ExtraBold).copy(shadow = TileShadow), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 7.dp, end = 7.dp, bottom = 5.dp),
                )
            }
            val progress = title.progress
            if (progress != null) Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 6.dp).fillMaxWidth().height(2.dp).background(Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.dp).background(C.Amber))
            }
        }
    }
}

private fun fourK(t: Title) =
    "ON YOUR PLEX · 4K".takeIf { t.availability.firstOrNull()?.let { it.kind == app.marquee.tv.data.AvailKind.PLEX && it.quality.contains("4K") } == true }

/** The line under a focused tile's name: how long it is and where it plays, or for a video whose it is and when. */
internal fun about(t: Title): String = when {
    t.youtubeId != null -> listOf(t.channel, t.published).filter { it.isNotBlank() }.joinToString(" · ")
    t.isElsewhere -> "Streaming on ${t.elsewhere.first()}"
    else -> listOfNotNull(
        t.duration.takeIf { it.isNotBlank() } ?: t.runtimeMin?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
        t.bestAvailability?.let { a -> Title.availabilityPhrase(a) + if (a.quality.contains("4K")) " in 4K" else "" },
    ).joinToString(" · ").ifBlank { t.reason.orEmpty() }
}

/** A row of titles on the shelf. Entering it from above or below lands on the tile you last had there, or the first. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfTitles(
    items: List<Title>, current: Boolean, rowT: () -> Float,
    onFocus: (Title) -> Unit, onClick: (Title) -> Unit, initialFocus: Boolean, resetKey: Any?,
) {
    val rowState = rememberLazyListState(prefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 5) })
    val unique = remember(items) { items.distinctBy { it.id } } // duplicate keys crash a lazy row
    val first = rememberRowStart(rowState, unique.isNotEmpty(), initialFocus, resetKey)
    PinnedRow(ShelfSize.start) {
        LazyRow(
            Modifier.height(ShelfSize.strip).focusRestorer(first), state = rowState,
            horizontalArrangement = Arrangement.spacedBy(ShelfSize.gap), contentPadding = ShelfSize.rowPadding(),
        ) {
            items(unique.size, key = { unique[it].id }, contentType = { "tile" }) { i ->
                TitleTile(unique[i], current, rowT, if (i == 0) Modifier.focusRequester(first) else Modifier, onFocus, onClick)
            }
        }
    }
}

/** One row's place on the shelf: its name above (showing while it is the next row) and its tiles, dimmed until it's in focus. */
@Composable
internal fun ShelfRow(title: String, current: Boolean, modifier: Modifier = Modifier, tiles: @Composable (rowT: () -> Float) -> Unit) {
    val t = animateFloatAsState(if (current) 1f else 0f, tween(220, easing = FastOutSlowInEasing), label = "row")
    val rowT = remember(t) { { t.value } }
    Column(modifier.height(ShelfSize.row)) {
        Box(Modifier.height(ShelfSize.head).fillMaxWidth().padding(start = ShelfSize.start), contentAlignment = Alignment.BottomStart) {
            Text(
                title, style = T.sans(10.sp, FontWeight.Bold).copy(shadow = TileShadow), maxLines = 1,
                modifier = Modifier.padding(bottom = 5.dp).graphicsLayer { alpha = 0.74f * (1f - t.value) },
            )
        }
        // (Each tile is drawn fainter, rather than the row through an offscreen buffer: cheaper on the TV's GPU.)
        Box(Modifier.graphicsLayer { alpha = 0.66f + 0.34f * t.value; compositingStrategy = CompositingStrategy.ModulateAlpha }) { tiles(rowT) }
    }
}

/**
 * Every row by name, on one line. The row in focus is marked in amber and the one showing under it more faintly;
 * the line slides along as you go down, keeping the row before in view.
 */
@Composable
internal fun RowIndex(rows: List<Shelf>, current: String?, modifier: Modifier = Modifier) {
    val at = rows.indexOfFirst { it.key == current }.coerceAtLeast(0)
    val state = rememberLazyListState()
    LaunchedEffect(at) { state.animateScrollToItem((at - 1).coerceAtLeast(0)) }
    LazyRow(
        // Names that don't fit fade out at the edges rather than being cut off mid-word.
        modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            val left = if (state.canScrollBackward) Color.Transparent else Color.Black
            drawRect(Brush.horizontalGradient(0.004f to left, 0.05f to Color.Black, 0.9f to Color.Black, 0.99f to Color.Transparent), blendMode = BlendMode.DstIn)
        },
        state = state, userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(21.dp), contentPadding = PaddingValues(horizontal = ShelfSize.start),
    ) {
        items(rows.size, key = { rows[it].key }, contentType = { "name" }) { i ->
            val here = i == at; val next = i == at + 1
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    rows[i].name, maxLines = 1,
                    style = T.sans(12.sp, if (here) FontWeight.ExtraBold else FontWeight.Bold, C.Text.copy(alpha = if (here) 1f else if (next) 0.92f else 0.66f)).copy(shadow = TileShadow),
                )
                if (here) Box(Modifier.size(22.dp, 3.dp).background(C.Amber, CircleShape))
                else if (next) Box(Modifier.size(15.dp, 1.5.dp).background(Color(0x80FFFFFF), CircleShape))
            }
        }
    }
}

/** "Hold OK to move or hide an app" and the like: said on the line between the two rows, at the right. */
@Composable
internal fun ShelfHint(text: String, strong: Boolean, modifier: Modifier = Modifier) {
    Row(modifier) { Text(text, style = T.sans(9.sp, FontWeight.SemiBold, if (strong) C.AmberSoft else C.TextMute).copy(shadow = TileShadow), maxLines = 1) }
}
