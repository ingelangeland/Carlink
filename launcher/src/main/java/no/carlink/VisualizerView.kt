package no.carlink

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Moderne lydvisning med to stiler (trykk for å bytte):
 *  - BØLGE: myke, flytende bølger i tre lag, speilet rundt midtlinjen, med glød.
 *  - KAPSLER: speilvendte avrundede stolper som vokser ut fra midten, med glød.
 * Fargene kommer fra coveret. Kantene fader ut. Når lyden strømmes til skjermen følger
 * visningen den ekte lyden (bass til venstre, diskant til høyre), ellers en rolig animasjon.
 */
class VisualizerView(ctx: Context) : View(ctx) {

    enum class Style { WAVE, CAPSULES }

    var accent = C.ACCENT
        set(v) { field = v; shadersDirty = true }
    var playing = false
    var style: Style = runCatching { Style.valueOf(ctx.prefs().getString("vizStyle", "WAVE")!!) }.getOrDefault(Style.WAVE)
        private set

    private val n = 36
    private val h = FloatArray(n) { 0.04f }
    private var t = 0f
    private val d = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    // «Glød» som bred, svak strek i stedet for uskarphetsfilter (mye billigere å tegne)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var shadersDirty = true
    private var colorA = 0; private var colorB = 0; private var colorC = 0
    private val path = Path()
    private val rect = RectF()

    private val frame = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            // Ikke synlig (f.eks. delt visning eller skjerm av): ingen tegning, sjekk sjelden
            if (!isShown || Ambient.overlayOpen) { postDelayed(this, 500); return }
            val active = step()
            invalidate()
            // 30 bilder/s er nok for en jevn bølge og halverer arbeidet mot 60
            postDelayed(this, if (active) 33L else 250L)
        }
    }

    init {
        // Tegnes i et eget programvarelag: et lite felt som er billig å tegne med prosessoren,
        // og som unngår feil i enkelte grafikkdrivere (oppdaget i emulator-simuleringen).
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setOnClickListener {
            style = if (style == Style.WAVE) Style.CAPSULES else Style.WAVE
            context.prefs().edit().putString("vizStyle", style.name).apply()
            invalidate()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); live += this; post(frame) }
    override fun onDetachedFromWindow() { live -= this; removeCallbacks(frame); super.onDetachedFromWindow() }

    companion object {
        private val live = mutableSetOf<VisualizerView>()
        /** Stilen er endret i innstillingene: oppdater lydbølgene som vises. */
        fun styleChanged(ctx: Context) {
            val st = runCatching { Style.valueOf(ctx.prefs().getString("vizStyle", "WAVE")!!) }.getOrDefault(Style.WAVE)
            live.forEach { it.style = st; it.invalidate() }
        }
    }
    override fun onSizeChanged(w: Int, hh: Int, ow: Int, oh: Int) { super.onSizeChanged(w, hh, ow, oh); shadersDirty = true }

    // ------------------------------------------------------------------ bevegelse

    private fun step(): Boolean {
        val live = SystemClock.elapsedRealtime() - Hub.audioLevelAt < 250
        var moving = false
        for (i in 0 until n) {
            val x = i / (n - 1f)
            val wobble = 0.55f + 0.45f * sin(t * 5.1f + i * 0.9f) * cos(t * 2.3f + i * 0.55f)
            val target = when {
                live -> (Hub.audioLow * (1f - x) * 1.3f + Hub.audioHigh * x * 1.6f + 0.05f) * (0.55f + wobble * 0.9f)
                playing -> (0.30f + 0.20f * sin(t * 1.6f + i * 0.35f)) * (0.65f + wobble * 0.55f)
                else -> 0.04f
            }.coerceIn(0.03f, 1f)
            val k = if (target > h[i]) 0.65f else 0.22f
            val nv = h[i] + (target - h[i]) * k
            if (abs(nv - h[i]) > 0.002f) moving = true
            h[i] = nv
        }
        t += 0.033f
        return moving || live || playing
    }

    // ------------------------------------------------------------------ tegning

    private fun ensureShaders() {
        if (!shadersDirty) return
        shadersDirty = false
        colorA = accent
        colorB = ColorUtils.blendARGB(accent, 0xFFFF4FD8.toInt(), 0.5f)
        colorC = ColorUtils.blendARGB(accent, 0xFF6FE7FF.toInt(), 0.6f)
        val w = width.toFloat()
        // Fargeovergang og uttoning mot kantene i én enkel gradient (gjennomsiktig i endene):
        // ingen ekstra tegnelag og ingen sammensatte shadere → lett for svake grafikkbrikker
        fun faded(): Shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(colorA and 0x00FFFFFF, colorA, colorB, colorC, colorC and 0x00FFFFFF),
            floatArrayOf(0f, 0.12f, 0.5f, 0.88f, 1f),
            Shader.TileMode.CLAMP,
        )
        fill.shader = faded()
        line.shader = faded()
        glow.shader = faded()
    }

    override fun onDraw(c: Canvas) {
        if (width == 0 || height == 0) return
        ensureShaders()
        when (style) {
            Style.WAVE -> drawWave(c)
            Style.CAPSULES -> drawCapsules(c)
        }
    }

    /** Glatt kurve gjennom punktene (Catmull-Rom → Bézier), speilet rundt midten. */
    private fun wavePath(scale: Float, phase: Float, mirror: Float) {
        val w = width.toFloat()
        val cy = height / 2f
        val amp = height / 2f * 0.92f
        val m = n + 2
        val xs = FloatArray(m) { it * w / (m - 1) }
        val ys = FloatArray(m) { j ->
            val i = (j - 1).coerceIn(0, n - 1)
            val v = if (j == 0 || j == m - 1) 0f else h[i] * scale * (0.85f + 0.15f * sin(t * 3f + i * 0.7f + phase))
            cy - v * amp
        }
        path.reset()
        path.moveTo(xs[0], cy)
        path.lineTo(xs[0], ys[0])
        for (j in 0 until m - 1) {
            val x0 = xs[(j - 1).coerceAtLeast(0)]; val y0 = ys[(j - 1).coerceAtLeast(0)]
            val x1 = xs[j]; val y1 = ys[j]; val x2 = xs[j + 1]; val y2 = ys[j + 1]
            val x3 = xs[(j + 2).coerceAtMost(m - 1)]; val y3 = ys[(j + 2).coerceAtMost(m - 1)]
            path.cubicTo(x1 + (x2 - x0) / 6f, y1 + (y2 - y0) / 6f, x2 - (x3 - x1) / 6f, y2 - (y3 - y1) / 6f, x2, y2)
        }
        // Speilet nedre halvdel (litt lavere, som en refleks)
        for (j in m - 1 downTo 1) {
            val yr = cy + (cy - ys[j - 1]) * mirror
            val xr = xs[j - 1]
            val yPrev = cy + (cy - ys[j]) * mirror
            path.quadTo(xs[j], yPrev, (xs[j] + xr) / 2f, (yPrev + yr) / 2f)
        }
        path.lineTo(xs[0], cy)
        path.close()
    }

    private fun drawWave(c: Canvas) {
        // Tre lag: bakerst bredt og svakt, fremst skarpt med glødende kant
        val layers = listOf(Triple(1.0f, 0.0f, 0x40), Triple(0.72f, 2.1f, 0x66), Triple(0.5f, 4.2f, 0xB3))
        for ((scale, phase, alpha) in layers) {
            wavePath(scale, phase, 0.55f)
            fill.alpha = alpha
            c.drawPath(path, fill)
        }
        // Glødende toppkant på fremste lag
        wavePath(1.0f, 0f, 0.55f)
        glow.alpha = 0x40
        glow.style = Paint.Style.STROKE; glow.strokeWidth = 7f * d
        c.drawPath(path, glow)
        line.alpha = 0xFF
        line.strokeWidth = 1.6f * d
        c.drawPath(path, line)
        // Tynn midtlinje
        line.alpha = 0x55; line.strokeWidth = 1f * d
        c.drawLine(0f, height / 2f, width.toFloat(), height / 2f, line)
    }

    private fun drawCapsules(c: Canvas) {
        val slot = width / n.toFloat()
        val bw = (slot * 0.5f).coerceAtLeast(2f * d)
        val cy = height / 2f
        glow.style = Paint.Style.FILL
        for (pass in 0..1) {
            for (i in 0 until n) {
                val half = (h[i] * height / 2f * 0.95f).coerceAtLeast(bw / 2)
                val x = i * slot + (slot - bw) / 2
                rect.set(x, cy - half, x + bw, cy + half * 0.7f) // nedre del litt kortere = refleks
                if (pass == 0) {
                    glow.alpha = 0x38
                    rect.inset(-2.5f * d, -2.5f * d)
                    c.drawRoundRect(rect, bw, bw, glow)
                    rect.inset(2.5f * d, 2.5f * d)
                }
                else { fill.alpha = 0xFF; c.drawRoundRect(rect, bw / 2, bw / 2, fill) }
            }
        }
    }
}
