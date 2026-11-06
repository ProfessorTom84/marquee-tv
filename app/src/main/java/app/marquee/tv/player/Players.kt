package app.marquee.tv.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/** Plex's trailer service wants the account token as a header (and follows redirects), so play through OkHttp. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun buildPlayer(ctx: Context, url: String): Pair<ExoPlayer, String> {
    val uri = android.net.Uri.parse(url)
    val token = uri.getQueryParameter("X-Plex-Token")
    val builder = ExoPlayer.Builder(ctx)
    var playUrl = url
    if (token != null) {
        playUrl = uri.buildUpon().clearQuery().apply {
            uri.queryParameterNames.filter { it != "X-Plex-Token" }.forEach { n -> appendQueryParameter(n, uri.getQueryParameter(n)) }
        }.build().toString()
        val clientId = runCatching { app.marquee.tv.MarqueeApp.instance.store.value.plexClientId }.getOrDefault("marquee")
        val client = app.marquee.tv.net.Http.client.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("X-Plex-Token", token).header("X-Plex-Client-Identifier", clientId).header("X-Plex-Product", "Marquee").build())
        }.build()
        val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client)
        builder.setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(ctx, http)))
    }
    return builder.build() to playUrl
}

/**
 * Muted background trailer that fades in over the still artwork once it is actually playing.
 * Only ever given ad-free sources (your Plex server's trailers).
 */
@Composable
fun PreviewPlayer(url: String, muted: Boolean, modifier: Modifier = Modifier, onProgress: (Float) -> Unit = {}, onPlaying: (Boolean) -> Unit = {}, onEnded: () -> Unit = {}) {
    val ctx = LocalContext.current
    var playing by remember(url) { mutableStateOf(false) }
    val player = remember(url) {
        buildPlayer(ctx, url).let { (p, u) -> p.apply { setMediaItem(MediaItem.fromUri(u)) } }.apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) { playing = true; onPlaying(true) } }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) { playing = false; onPlaying(false); onEnded() } }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { playing = false; onPlaying(false); onEnded() }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release(); onPlaying(false) }
    }
    LaunchedEffect(muted, player) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(player) {
        while (true) {
            val d = player.duration
            if (d > 0) onProgress(player.currentPosition.toFloat() / d)
            delay(400)
        }
    }
    val a by animateFloatAsState(if (playing) 1f else 0f, tween(1200), label = "fade")
    AndroidView(factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } }, modifier = modifier.alpha(a))
}

/** Full-screen trailer playback with standard controls. */
class TrailerActivity : Activity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val (p, playUrl) = buildPlayer(this, url)
        player = p
        val view = PlayerView(this).apply { this.player = p; keepScreenOn = true; requestFocus() }
        setContentView(view)
        p.setMediaItem(MediaItem.fromUri(playUrl))
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) finish() }
        })
        p.prepare()
        p.playWhenReady = true
    }

    override fun onStop() { super.onStop(); player?.pause() }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }

    companion object {
        const val EXTRA_URL = "url"
        fun start(ctx: Context, url: String) = ctx.startActivity(Intent(ctx, TrailerActivity::class.java).putExtra(EXTRA_URL, url))
    }
}
