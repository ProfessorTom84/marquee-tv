package app.marquee.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import androidx.tv.material3.Text
import app.marquee.tv.data.Title
import app.marquee.tv.ui.theme.C
import app.marquee.tv.ui.theme.T
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Layered glass: tint + hairline border + bright top edge. Blur comes from the blurred art behind it. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(14.dp), tint: Color = C.GlassCool, stroke: Color = C.Stroke, glow: Color? = null): Modifier =
    this
        .then(if (glow != null) Modifier.shadow(24.dp, shape, ambientColor = glow, spotColor = glow) else Modifier)
        .clip(shape)
        .background(tint)
        .border(1.dp, stroke, shape)
        .drawWithContent {
            drawContent()
            drawLine(C.Highlight, Offset(size.width * 0.04f, 0.75f), Offset(size.width * 0.96f, 0.75f), strokeWidth = 1.5f)
        }

/** Film grain over everything, generated once. */
private var grainCache: ImageBitmap? = null
private var grainBrushCache: ShaderBrush? = null
fun grainBrush(): ShaderBrush = grainBrushCache ?: ShaderBrush(ImageShader(grain(), TileMode.Repeated, TileMode.Repeated)).also { grainBrushCache = it }

private fun grain(): ImageBitmap = grainCache ?: run {
    val n = 160
    val r = Random(7)
    val px = IntArray(n * n) { val v = r.nextInt(256); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
    Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap().also { grainCache = it }
}

@Composable
fun GrainOverlay(alpha: Float = 0.055f) {
    val brush = remember { ShaderBrush(ImageShader(grain(), TileMode.Repeated, TileMode.Repeated)) }
    // Drawn with alpha directly: no offscreen layer to composite every frame.
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawRect(brush, alpha = alpha) }
}

/** Generic focus + click surface that works with the D-pad. */
@Composable
fun Focusable(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onFocus: (Boolean) -> Unit = {},
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged {
                val now = it.isFocused || it.hasFocus
                if (it.isFocused && !focused) SoundFx.focus()
                focused = now; onFocus(focused)
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { SoundFx.select(); onClick() }),
    ) { content(focused) }
}

@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    height: Dp = 34.dp,
    onClick: () -> Unit,
) {
    Focusable(modifier, onClick) { f ->
        val scale by animateFloatAsState(if (f) 1.06f else 1f, spring(stiffness = 500f), label = "s")
        val bg by animateColorAsState(if (primary || f) C.Text else Color(0x1AFFFFFF), label = "bg")
        val fg = if (primary || f) Color(0xFF120D08) else C.Text
        Row(
            Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .then(if (f) Modifier.shadow(18.dp, CircleShape, ambientColor = C.Amber, spotColor = C.Amber) else Modifier)
                .clip(CircleShape).background(bg)
                .then(if (f) Modifier.border(2.dp, C.Amber, CircleShape) else Modifier.border(1.dp, C.Stroke, CircleShape))
                .height(height).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            icon?.invoke()
            Text(text, style = T.sans(11.sp, FontWeight.ExtraBold, fg), maxLines = 1)
        }
    }
}

@Composable
fun Chip(text: String, color: Color = C.Text, bg: Color = Color(0x99100C08), modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text(text, style = T.sans(7.sp, FontWeight.ExtraBold, color, 0.4.sp), maxLines = 1)
    }
}

/** A rating normalised to 10, with where it came from. */
data class RatingInfo(val score10: Double, val display: String, val percent: Boolean, val source: String)

fun parseRating(label: String?): RatingInfo? {
    label ?: return null
    val num = Regex("[0-9]+(\\.[0-9]+)?").find(label)?.value?.toDoubleOrNull() ?: return null
    return when {
        label.startsWith("AUD") -> RatingInfo(num / 10, "${num.toInt()}%", true, "Audience score")
        label.startsWith("RT") -> RatingInfo(num / 10, "${num.toInt()}%", true, "Critics score")
        label.startsWith("IMDb") -> RatingInfo(num, "%.1f".format(num), false, "IMDb")
        label.startsWith("TMDB") -> RatingInfo(num, "%.1f".format(num), false, "TMDB users")
        else -> RatingInfo(num, "%.1f".format(num), false, "Rating")
    }
}

fun ratingColor(score10: Double) = when {
    score10 >= 8.0 -> Color(0xFF7BD88F)
    score10 >= 7.0 -> Color(0xFFF5C451)
    else -> Color(0xFFB7BCC8)
}

/**
 * Ratings as icons instead of abbreviations: a star for 10-point scores (IMDb, TMDB),
 * a filling ring for percentages (critics or audience). Green is great, gold is good, grey is mixed.
 */
@Composable
fun RatingBadge(label: String?, showSource: Boolean = false, iconSize: Dp = 11.dp, textSize: androidx.compose.ui.unit.TextUnit = 9.sp) {
    val r = parseRating(label) ?: return
    val col = ratingColor(r.score10)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.foundation.Canvas(Modifier.size(iconSize)) {
            if (r.percent) {
                val sw = size.minDimension * 0.2f
                val inset = sw / 2
                val arcSize = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw)
                drawArc(Color(0x40FFFFFF), 0f, 360f, false, Offset(inset, inset), arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                drawArc(col, -90f, (360f * r.score10 / 10f).toFloat(), false, Offset(inset, inset), arcSize,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            } else {
                val path = androidx.compose.ui.graphics.Path()
                val cx = size.width / 2; val cy = size.height / 2
                val outer = size.minDimension / 2; val inner = outer * 0.45f
                for (i in 0 until 10) {
                    val rad = if (i % 2 == 0) outer else inner
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val x = cx + (rad * kotlin.math.cos(a)).toFloat(); val y = cy + (rad * kotlin.math.sin(a)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, col)
            }
        }
        Text(r.display, style = T.sans(textSize, FontWeight.ExtraBold, col))
        if (showSource) Text(r.source, style = T.sans(textSize * 0.85f, FontWeight.SemiBold, C.TextDim))
    }
}

/**
 * Ratings in plain words, on the hero and detail pages only ("IMDb 8.4", "Rotten Tomatoes 92%").
 * Cards stay clean. (Showing the IMDb or Rotten Tomatoes logos needs a licence from them.)
 */
@Composable
fun RatingChip(label: String?) {
    val r = parseRating(label) ?: return
    val source = when {
        label!!.startsWith("IMDb") -> "IMDb"
        label.startsWith("RT") -> "Rotten Tomatoes"
        label.startsWith("AUD") -> "Audience score"
        label.startsWith("TMDB") -> "TMDB"
        else -> "Rated"
    }
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(color = C.TextDim, fontWeight = FontWeight.SemiBold)) { append("$source  ") }
            withStyle(androidx.compose.ui.text.SpanStyle(color = C.Text, fontWeight = FontWeight.ExtraBold)) { append(r.display) }
        },
        style = T.sans(11.sp),
    )
}

@Composable
fun OutlineTag(label: String) {
    Box(Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(label.uppercase(), style = T.sans(8.sp, FontWeight.ExtraBold, C.Text, 0.4.sp))
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = T.rowTitle)
        if (subtitle.isNotBlank()) Text(subtitle, style = T.small, modifier = Modifier.padding(bottom = 1.dp))
    }
}

/** Deterministic fallback art so cards never look empty. */
fun fallbackBrush(seed: String): Brush {
    val palettes = listOf(
        listOf(Color(0xFFE19A4C), Color(0xFF8A4A1E), Color(0xFF2A1409)),
        listOf(Color(0xFF9A3030), Color(0xFF3A1214), Color(0xFF120709)),
        listOf(Color(0xFF3B6FD8), Color(0xFF3A1E6B), Color(0xFF0F0A22)),
        listOf(Color(0xFF2F7D6B), Color(0xFF133229), Color(0xFF07120F)),
        listOf(Color(0xFF5C6B7A), Color(0xFF222A33), Color(0xFF0A0D11)),
        listOf(Color(0xFFD5A15A), Color(0xFF5A3412), Color(0xFF140B04)),
    )
    return Brush.linearGradient(palettes[(seed.hashCode() and 0x7fffffff) % palettes.size])
}

/** Pulls the dominant color from artwork so focus glow and glass can be tinted to match. */
suspend fun dominantColor(context: android.content.Context, loader: ImageLoader, url: String?): Color? {
    url ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val req = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
            val bmp = ((loader.execute(req) as? SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@runCatching null
            val p = Palette.from(bmp).generate()
            val sw = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch ?: return@runCatching null
            Color(sw.rgb)
        }.getOrNull()
    }
}

/** What a card plays while you rest on it: an ad-free trailer, or a slideshow of stills. */
data class CardPreview(val videoUrl: String?, val images: List<String>)

/** Supplies previews to cards; null turns card previews off (e.g. Cinematic, which previews in the hero). */
val LocalCardPreviews = androidx.compose.runtime.staticCompositionLocalOf<(suspend (Title) -> CardPreview?)?> { null }

/** Keeps the focused card at the row's left edge, like Google TV and Apple TV rows. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PinnedRow(startPadding: Dp, content: @Composable () -> Unit) {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { startPadding.toPx() }
    val spec = remember(px) {
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - px
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec) { content() }
}

@Composable
private fun StillsPreview(images: List<String>, onProgress: (Float) -> Unit) {
    var i by remember(images) { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(images) {
        var t = 0
        while (true) {
            kotlinx.coroutines.delay(100); t++
            onProgress(((i * 26 + (t % 26)) / (images.size * 26f)).coerceAtMost(1f))
            if (t % 26 == 0) i = (i + 1) % images.size
        }
    }
    val zoom by rememberInfiniteTransition(label = "z").animateFloat(1f, 1.08f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "zoom")
    androidx.compose.animation.Crossfade(images[i], animationSpec = tween(700), label = "still") { u ->
        AsyncImage(u, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom })
    }
}

private val CardShade = Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xCC000000))

/** The signature card: lifts, tilts toward you and glows in the art's colour. No autoplay. */
@Composable
fun TitleCard(
    title: Title,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    showMeta: Boolean = true,
    dimmed: Boolean = false,
    previewProgress: Float? = null,
    onFocus: (Title) -> Unit = {},
    onClick: (Title) -> Unit,
) {
    val context = LocalContext.current
    var glow by remember(title.id) { mutableStateOf<Color?>(null) }
    val loader = remember { coil.Coil.imageLoader(context) }

    // Where the artwork sits on screen, so a click can grow it into the details page (no recomposition: a plain holder).
    val where = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    Focusable(modifier.width(width), onClick = {
        if (title.youtubeId == null) CardZoom.clicked(title.id, title.backdropUrl ?: title.posterUrl,
            where[0]?.takeIf { it.isAttached }?.boundsInRoot())
        onClick(title)
    }, onFocus = { if (it) onFocus(title) }) { f ->
        // Only work out the glow colour for the card you're on, not every card on screen.
        androidx.compose.runtime.LaunchedEffect(f, title.id) { if (f && glow == null) glow = dominantColor(context, loader, title.backdropUrl ?: title.posterUrl) }
        val lift by animateFloatAsState(if (f) 1.08f else 1f, spring(dampingRatio = 0.7f, stiffness = 380f), label = "lift")
        val tilt by animateFloatAsState(if (f) -6f else 0f, tween(420, easing = FastOutSlowInEasing), label = "tilt")
        val alpha by animateFloatAsState(if (f) 1f else if (dimmed) 0.65f else 0.88f, label = "a")
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            val shape = RoundedCornerShape(11.dp)
            Box(
                Modifier
                    .size(width, height)
                    .then(androidx.compose.ui.Modifier.onPlaced { where[0] = it })
                    .graphicsLayer {
                        scaleX = lift; scaleY = lift
                        rotationY = tilt; rotationX = if (f) 2.5f else 0f
                        cameraDistance = 14f * density
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.3f, 0.5f)
                        this.alpha = alpha
                    }
                    // Only the focused card casts a (coloured) shadow; twenty resting shadows cost real GPU time on a TV.
                    .then(if (f) Modifier.shadow(18.dp, shape, ambientColor = glow ?: C.Amber, spotColor = glow ?: C.Amber) else Modifier)
                    .clip(shape)
                    .background(fallbackBrush(title.id))
                    .then(if (f) Modifier.border(2.dp, Color(0xF2FFF0DC), shape) else Modifier),
            ) {
                // Cards are small: ask for a smaller TMDB size, and fall back to the poster if a thumbnail is missing
                // (YouTube has no max-res thumbnail for some videos).
                val primary = remember(title.id) { (title.backdropUrl ?: title.posterUrl)?.replace("/t/p/w1280/", "/t/p/w780/") }
                var imgFailed by remember(title.id) { mutableStateOf(false) }
                AsyncImage(
                    model = if (imgFailed) title.posterUrl else primary,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = { if (!imgFailed && title.posterUrl != null && title.posterUrl != primary) imgFailed = true },
                )
                Box(Modifier.fillMaxSize().background(CardShade))
                var logoFailed by remember(title.logoUrl) { mutableStateOf(false) }
                if (title.logoUrl != null && !logoFailed) {
                    AsyncImage(
                        model = title.logoUrl, contentDescription = title.title,
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).fillMaxWidth(0.62f).height(height * 0.28f),
                        contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                        onError = { logoFailed = true },
                    )
                } else if (title.youtubeId == null) { // video thumbnails carry their own text; the title sits below
                    Text(
                        title.title, style = T.cardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 11.dp, vertical = 9.dp),
                    )
                }
                Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    title.badge?.let { Chip(it, C.AmberSoft) }
                    if (title.badge == null && title.availability.any { it.kind == app.marquee.tv.data.AvailKind.PLEX } && title.availability.first().quality.contains("4K"))
                        Chip("ON YOUR PLEX · 4K", C.AmberSoft)
                }
                if (title.duration.isNotBlank()) Chip(title.duration, C.Text, Color(0xC0000000), Modifier.align(Alignment.BottomEnd).padding(6.dp))
                val progress = previewProgress ?: title.progress
                if (progress != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.5.dp).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.5.dp).background(C.Amber))
                    }
                }
            }
            if (showMeta) {
                val meta = when {
                    title.youtubeId != null -> listOf(title.channel, title.published).filter { it.isNotBlank() }.joinToString(" · ")
                    title.isElsewhere -> "Streaming on ${title.elsewhere.first()}"
                    f && title.reason != null -> title.reason
                    else -> title.bestAvailability?.let { Title.availabilityPhrase(it) } ?: ""
                }
                Column(Modifier.padding(start = 3.dp)) {
                    if (title.youtubeId != null) Text(title.title, style = T.sans(10.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(meta, style = T.sans(9.sp, FontWeight.Medium, if (f) C.Text else C.TextMute), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun BoxScope.Sheen() {
    val t = rememberInfiniteTransition(label = "sheen")
    val x by t.animateFloat(-1.2f, 1.6f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart), label = "x")
    Box(
        Modifier.matchParentSize().drawWithContent {
            val w = size.width
            val cx = w * x
            drawRect(
                Brush.linearGradient(
                    0f to Color.Transparent, 0.5f to Color(0x47FFFFFF), 1f to Color.Transparent,
                    start = Offset(cx - w * 0.25f, 0f), end = Offset(cx + w * 0.25f, size.height),
                ),
            )
        },
    )
}

@Composable
fun MediaRow(
    items: List<Title>,
    cardWidth: Dp,
    cardHeight: Dp,
    onFocus: (Title) -> Unit,
    onClick: (Title) -> Unit,
    dimmed: Boolean = false,
    modifier: Modifier = Modifier,
    initialFocus: Boolean = false,
    resetKey: Any? = null, // when this changes the row scrolls back to its first card (and refocuses it if initialFocus)
) {
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Duplicate keys crash a lazy row, so never trust the data to be unique.
    val unique = remember(items) { items.distinctBy { it.id } }
    // Focus the first card once, when the screen first appears. (Lazy lists rebuild a row when it scrolls back
    // into view; without this flag, coming back up to the row would yank focus back to its first card.)
    var didInitialFocus by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (initialFocus && items.isNotEmpty() && !didInitialFocus) androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        runCatching { first.requestFocus() }
        didInitialFocus = true
    }
    androidx.compose.runtime.LaunchedEffect(resetKey) {
        if (resetKey == null) return@LaunchedEffect
        rowState.scrollToItem(0)
        if (initialFocus) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    }
    PinnedRow(48.dp) {
        androidx.compose.foundation.lazy.LazyRow(
            modifier = modifier,
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 14.dp, bottom = 10.dp),
        ) {
            items(unique.size, key = { unique[it].id }, contentType = { "card" }) { i ->
                TitleCard(
                    unique[i], cardWidth, cardHeight, dimmed = dimmed, onFocus = onFocus, onClick = onClick,
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
}

@Composable
fun Divider() = Box(Modifier.width(1.dp).height(16.dp).background(Color(0x29FFFFFF)))

@Composable
fun RowScope.Grow() = Spacer(Modifier.weight(1f))

@Composable
fun QrImage(text: String, size: Dp) {
    val bmp = remember(text) { app.marquee.tv.setup.SetupServer.qr(text, 400).asImageBitmap() }
    Image(bmp, contentDescription = "QR code", modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)))
}

@Composable
fun Toggle(on: Boolean, label: String, sub: String = "", onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Focusable(modifier.fillMaxWidth(), onClick = onToggle) { f ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (f) Color(0x24FFFFFF) else Color(0x0DFFFFFF))
                .then(if (f) Modifier.border(1.5.dp, C.Amber, RoundedCornerShape(10.dp)) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = T.bodyStrong)
                if (sub.isNotBlank()) Text(sub, style = T.small)
            }
            Box(Modifier.size(38.dp, 21.dp).clip(CircleShape).background(if (on) C.Amber else Color(0x38FFFFFF)), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.padding(2.5.dp).size(16.dp).clip(CircleShape).background(C.Text))
            }
        }
    }
}

@Composable
fun ValueRow(label: String, value: String, sub: String = "", onClick: () -> Unit, modifier: Modifier = Modifier, valueStyle: TextStyle = T.sans(11.sp, FontWeight.Bold, C.AmberSoft)) {
    Focusable(modifier.fillMaxWidth(), onClick = onClick) { f ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (f) Color(0x24FFFFFF) else Color(0x0DFFFFFF))
                .then(if (f) Modifier.border(1.5.dp, C.Amber, RoundedCornerShape(10.dp)) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = T.bodyStrong)
                if (sub.isNotBlank()) Text(sub, style = T.small)
            }
            Text(value, style = valueStyle)
        }
    }
}

/** Vertical lists that only scroll when the focused thing would go off-screen (instead of re-centring it on every move). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val CalmScrollSpec = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset < 0f -> offset - 24f
        offset + size > containerSize -> offset + size - containerSize + 24f
        else -> 0f
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CalmScroll(content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides CalmScrollSpec) { content() }
