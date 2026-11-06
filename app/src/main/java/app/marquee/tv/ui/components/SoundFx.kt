package app.marquee.tv.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Marquee's interface sounds: synthesised here (no audio files), short and very quiet.
 * A soft tick as you move, a two-note "select", a falling note for Back, and a warm three-note chime
 * when Pick Together finds a match. Sounds only follow your own button presses, never automatic focus.
 */
object SoundFx {
    @Volatile var enabled = true
    private var pool: SoundPool? = null
    private var tick = 0; private var select = 0; private var back = 0; private var match = 0
    @Volatile private var lastKey = 0L
    @Volatile private var lastTick = 0L

    fun init(ctx: Context) {
        if (pool != null) return
        Thread {
            runCatching {
                val dir = File(ctx.cacheDir, "sfx").apply { mkdirs() }
                val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                ).build()
                fun load(name: String, samples: FloatArray) = p.load(File(dir, "$name-v1.wav").also { if (!it.exists()) writeWav(it, samples) }.path, 1)
                tick = load("tick", tickSound())
                select = load("select", selectSound())
                back = load("back", backSound())
                match = load("match", matchSound())
                pool = p
            }
        }.start()
    }

    fun keyPressed() { lastKey = SystemClock.uptimeMillis() }

    /** Focus moved: only tick if it was you (a key press just now), and not more than ~25 times a second. */
    fun focus() {
        val now = SystemClock.uptimeMillis()
        if (now - lastKey > 220 || now - lastTick < 40) return
        lastTick = now
        play(tick, 0.07f)
    }

    fun select() { if (SystemClock.uptimeMillis() - lastKey < 600) play(select, 0.11f) }
    fun back() = play(back, 0.09f)
    fun match() = play(match, 0.16f)

    private fun play(id: Int, vol: Float) {
        if (!enabled || id == 0) return
        pool?.play(id, vol, vol, 1, 0, 1f)
    }

    // ---------------- synthesis ----------------
    private const val RATE = 44_100

    private fun tone(seconds: Float, fn: (t: Float) -> Float) = FloatArray((seconds * RATE).toInt()) { i -> fn(i / RATE.toFloat()) }
    private fun s(f: Float, t: Float) = sin(2 * PI * f * t).toFloat()
    private fun env(t: Float, attack: Float, decay: Float) = min(1f, t / attack) * exp(-t * decay)

    private fun tickSound() = tone(0.022f) { t -> (s(2100f, t) + 0.25f * s(4200f, t)) * env(t, 0.0012f, 300f) * 0.8f }

    private fun selectSound() = tone(0.16f) { t ->
        val a = s(880f, t) * env(t, 0.003f, 40f)
        val b = if (t > 0.045f) s(1318.5f, t - 0.045f) * env(t - 0.045f, 0.003f, 32f) else 0f
        (a * 0.55f + b * 0.5f)
    }

    private fun backSound() = tone(0.14f) { t ->
        val a = s(784f, t) * env(t, 0.003f, 45f)
        val b = if (t > 0.04f) s(587.3f, t - 0.04f) * env(t - 0.04f, 0.003f, 38f) else 0f
        (a * 0.5f + b * 0.5f)
    }

    /** A soft bell arpeggio (C6, E6, G6) with a little inharmonic shimmer, like a struck glass. */
    private fun matchSound() = tone(1.4f) { t ->
        fun bell(f: Float, start: Float): Float {
            val x = t - start
            if (x < 0f) return 0f
            return (s(f, x) + 0.22f * s(f * 2.76f, x) * exp(-x * 9f) + 0.1f * s(f * 5.4f, x) * exp(-x * 16f)) * env(x, 0.004f, 3.6f)
        }
        (bell(1046.5f, 0f) + bell(1318.5f, 0.11f) + bell(1568f, 0.22f) + 0.5f * bell(2093f, 0.36f)) * 0.22f
    }

    private fun writeWav(f: File, samples: FloatArray) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { v -> data.putShort((v.coerceIn(-1f, 1f) * 32000).toInt().toShort()) }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + samples.size * 2); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(samples.size * 2)
        FileOutputStream(f).use { it.write(h.array()); it.write(data.array()) }
    }
}
