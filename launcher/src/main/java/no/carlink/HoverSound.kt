package no.carlink

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Short, soft sound when the dot moves onto something that can be tapped.
 * Five sounds × light/dark tone, all generated in the app (no sound files), played as a
 * system sound on top of the music without pausing or lowering it.
 */
object HoverSound {
    enum class Sound(val label: String) { OFF("Off"), TICK("Soft tick"), WOOD("Wood"), BUBBLE("Bubble"), CHIME("Chime"), VELVET("Velvet") }
    enum class Tone(val label: String, val k: Double) { LIGHT("Light", 1.0), DARK("Dark", 0.72) }
    enum class Level(val label: String, val gain: Float) { LOW("Low", 0.35f), MEDIUM("Medium", 0.6f), HIGH("High", 1f) }

    private const val SR = 48_000
    private var track: AudioTrack? = null
    private var loaded: Pair<Sound, Tone>? = null
    private var lastPlay = 0L

    fun sound(ctx: Context) = runCatching { Sound.valueOf(ctx.prefs().getString("hoverSound", "VELVET")!!) }.getOrDefault(Sound.VELVET)
    fun tone(ctx: Context) = runCatching { Tone.valueOf(ctx.prefs().getString("hoverTone", "LIGHT")!!) }.getOrDefault(Tone.LIGHT)
    fun level(ctx: Context) = runCatching { Level.valueOf(ctx.prefs().getString("hoverLevel", "MEDIUM")!!) }.getOrDefault(Level.MEDIUM)

    fun set(ctx: Context, s: Sound? = null, t: Tone? = null, l: Level? = null) {
        ctx.prefs().edit().apply {
            s?.let { putString("hoverSound", it.name) }; t?.let { putString("hoverTone", it.name) }; l?.let { putString("hoverLevel", it.name) }
        }.apply()
        play(ctx, force = true)   // preview the choice
    }

    /** Called when the dot enters a new button. At most one sound per 70 ms (fast sweeps stay calm). */
    fun play(ctx: Context, force: Boolean = false) {
        val s = sound(ctx)
        if (s == Sound.OFF) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPlay < 70) return
        lastPlay = now
        runCatching {
            val key = s to tone(ctx)
            if (loaded != key || track == null) load(key)
            track?.apply {
                setVolume(level(ctx).gain)
                if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
                reloadStaticData(); play()
            }
        }
    }

    private fun load(key: Pair<Sound, Tone>) {
        track?.release(); track = null
        val pcm = synth(key.first, key.second.k)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SR)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build().apply { write(pcm, 0, pcm.size) }
        loaded = key
    }

    // ------------------------------------------------------------- sound synthesis

    private fun synth(s: Sound, k: Double): ShortArray {
        val dark = k < 1.0
        val x = when (s) {
            Sound.TICK -> tick(k, dark)
            Sound.WOOD -> wood(k, dark)
            Sound.BUBBLE -> bubble(k, dark)
            Sound.CHIME -> chime(k, dark)
            Sound.VELVET, Sound.OFF -> velvet(k, dark)
        }
        val peak = x.maxOf { abs(it) }.coerceAtLeast(1e-9)
        val target = 10.0.pow(-14.0 / 20)
        return ShortArray(x.size) { (x[it] / peak * target * 32767).toInt().toShort() }
    }

    private fun env(t: Double, attack: Double, decay: Double): Double {
        val a = (t / attack).coerceIn(0.0, 1.0)
        return (0.5 - 0.5 * cos(PI * a)) * exp(-t / decay)
    }

    private fun lowpass(x: DoubleArray, cutoff: Double): DoubleArray {
        val a = exp(-2 * PI * cutoff / SR); var p = 0.0
        return DoubleArray(x.size) { p = (1 - a) * x[it] + a * p; p }
    }

    private fun fadeEnd(x: DoubleArray, ms: Double = 7.0): DoubleArray {
        val n = (SR * ms / 1000).toInt()
        for (i in 0 until n) { val f = 1.0 - i.toDouble() / n; x[x.size - n + i] *= f * f }
        return x
    }

    private fun tick(k: Double, dark: Boolean): DoubleArray {
        val n = (SR * if (dark) 0.04 else 0.035).toInt()
        val rnd = java.util.Random(1)
        val noise = lowpass(DoubleArray(n) { rnd.nextGaussian() }, if (dark) 1400.0 else 2200.0)
        val x = DoubleArray(n) { i ->
            val t = i.toDouble() / SR
            sin(2 * PI * 820 * k * t) * env(t, if (dark) .002 else .0015, if (dark) .008 else .006) +
                noise[i] * env(t, .0008, .004) * (if (dark) 0.5 else 0.6)
        }
        return fadeEnd(lowpass(x, if (dark) 1800.0 else 3000.0))
    }

    private fun wood(k: Double, dark: Boolean): DoubleArray {
        val n = (SR * if (dark) 0.1 else 0.09).toInt(); var ph = 0.0
        val x = DoubleArray(n) { i ->
            val t = i.toDouble() / SR
            ph += 2 * PI * 480 * k * (1 - 0.06 * i / n) / SR
            (sin(ph) + 0.33 * sin(2.01 * ph) + 0.1 * sin(3.9 * ph)) * env(t, if (dark) .0025 else .002, if (dark) .026 else .022)
        }
        return fadeEnd(lowpass(x, if (dark) 1600.0 else 2500.0))
    }

    private fun bubble(k: Double, dark: Boolean): DoubleArray {
        val n = (SR * if (dark) 0.08 else 0.07).toInt(); var ph = 0.0
        val x = DoubleArray(n) { i ->
            val u = i.toDouble() / (n - 1)
            ph += 2 * PI * (260 + 420 * u.pow(0.6)) * k / SR
            sin(ph) * sin(PI * u).pow(1.5)
        }
        return fadeEnd(lowpass(x, if (dark) 1200.0 else 1800.0))
    }

    private fun chime(k: Double, dark: Boolean): DoubleArray {
        val n = (SR * if (dark) 0.18 else 0.16).toInt()
        val x = DoubleArray(n) { i ->
            val t = i.toDouble() / SR
            (sin(2 * PI * 1320 * k * t) + 0.38 * sin(2 * PI * 1980 * k * t) + 0.12 * sin(2 * PI * 2640 * k * t)) *
                env(t, if (dark) .005 else .004, if (dark) .05 else .045)
        }
        return fadeEnd(lowpass(x, if (dark) 2800.0 else 4500.0))
    }

    private fun velvet(k: Double, dark: Boolean): DoubleArray {
        val n = (SR * if (dark) 0.15 else 0.14).toInt(); var ph = 0.0
        val peak = if (dark) 0.038 else 0.035
        val x = DoubleArray(n) { i ->
            val t = i.toDouble() / SR
            ph += 2 * PI * 400 * k * (1 - 0.08 * i / n) / SR
            val w = if (t <= peak) (if (dark) .02 else .018) else (if (dark) .035 else .032)
            (sin(ph) + 0.16 * sin(2 * ph)) * exp(-0.5 * ((t - peak) / w).pow(2))
        }
        val c = if (dark) 800.0 else 1100.0
        return lowpass(lowpass(x, c), c)
    }
}
