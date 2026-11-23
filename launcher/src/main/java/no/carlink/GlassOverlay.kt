package no.carlink

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Felles «glass»-lag for app-oversikten og innstillingene, så de ser og oppfører seg likt:
 *  - skjermen bak blir uskarp og røykfarget i coverets mørke farge (lysere i midten, mørkere mot kanten)
 *  - innholdet toner inn med en liten zoom
 *  - trykk utenfor innholdet lukker
 */
abstract class GlassOverlay(ctx: Context, private val blurTarget: View) : ScopedFrame(ctx) {

    companion object {
        private var openCount = 0
    }

    var isOpen = false
        private set

    /** Selve innholdet (panelet) som toner inn/ut. Settes av subklassen. */
    protected abstract val body: View

    private val smoke = View(ctx)
    /** Android 9–11 har ikke innebygd uskarphet: der brukes et lite, uskarpt bilde av skjermen bak. */
    private val frost = android.widget.ImageView(ctx).apply {
        scaleType = android.widget.ImageView.ScaleType.FIT_XY
        alpha = 0f
    }
    private val oldAndroid = Build.VERSION.SDK_INT < 31
    private val smokeBg = GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT }
    private var anim: ValueAnimator? = null
    private var progress = 0f
    private var blurStep = -1

    init {
        visibility = INVISIBLE
        isClickable = true                      // fanger trykk så de ikke går til skjermen bak
        setOnClickListener { close() }          // trykk utenfor innholdet lukker
        smoke.background = smokeBg
        if (oldAndroid) addView(frost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(smoke, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onStart(scope: CoroutineScope) {
        scope.launch { Ambient.theme.collect { applySmoke(it); onTheme(it) } }
    }

    protected open fun onTheme(t: Ambient.Theme) {}
    protected open fun onOpening() {}

    fun open() {
        if (isOpen) return
        isOpen = true
        if (oldAndroid) snapshotBehind()
        visibility = VISIBLE
        onOpening()
        openCount++; Ambient.overlayOpen = true
        animateTo(1f, 160)
    }

    fun close(immediate: Boolean = false) {
        if (!isOpen) return
        isOpen = false
        openCount = (openCount - 1).coerceAtLeast(0); Ambient.overlayOpen = openCount > 0
        if (immediate) { anim?.cancel(); setProgress(0f); visibility = INVISIBLE; frost.setImageDrawable(null); return }
        animateTo(0f, 130)
    }

    private fun animateTo(target: Float, ms: Long) {
        anim?.cancel()
        // Innholdet animeres som ett ferdig bilde (grafikklag) i stedet for å tegnes på nytt hvert bilde
        body.setLayerType(LAYER_TYPE_HARDWARE, null)
        anim = ValueAnimator.ofFloat(progress, target).apply {
            duration = ms
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    body.setLayerType(LAYER_TYPE_NONE, null)
                    if (!isOpen && progress <= 0.001f) { visibility = INVISIBLE; frost.setImageDrawable(null) }
                }
            })
            start()
        }
    }

    /** p = 0 (skjult) … 1 (helt åpen): uskarphet, røyk og innhold følger hverandre. */
    private fun setProgress(p: Float) {
        progress = p
        smoke.alpha = p
        frost.alpha = p
        body.alpha = p
        val s = 0.94f + 0.06f * p
        body.scaleX = s; body.scaleY = s
        if (Build.VERSION.SDK_INT >= 31) {
            // Uskarpheten oppdateres i 5 trinn: bakgrunnen står stille mellom trinnene → jevn animasjon
            val step = ceil(p * 5f).toInt().coerceIn(0, 5)
            if (step != blurStep) {
                blurStep = step
                val r = 26f * resources.displayMetrics.density * step / 5f
                blurTarget.setRenderEffect(if (step == 0) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        smokeBg.gradientRadius = hypot(w.toFloat(), h.toFloat()) / 2f
    }

    /** Tegner skjermen bak i 1/8 størrelse og gjør den uskarp (raskt: ca. 240×90 punkter). */
    private fun snapshotBehind() {
        val w = blurTarget.width; val h = blurTarget.height
        if (w <= 0 || h <= 0) return
        val f = 8
        val bw = (w / f).coerceAtLeast(1); val bh = (h / f).coerceAtLeast(1)
        val bmp = runCatching { android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
        runCatching {
            val c = android.graphics.Canvas(bmp)
            c.scale(bw.toFloat() / w, bh.toFloat() / h)
            blurTarget.draw(c)
            boxBlur(bmp, 3, 3)
            frost.setImageBitmap(bmp)
        }
    }

    private fun boxBlur(b: android.graphics.Bitmap, r: Int, passes: Int) {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(passes) {
            blurLine(px, tmp, w, h, r, true)
            blurLine(tmp, px, w, h, r, false)
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
        val lines = if (horiz) h else w; val len = if (horiz) w else h
        for (l in 0 until lines) {
            var sr = 0; var sg = 0; var sb = 0
            fun at(i: Int): Int { val k = i.coerceIn(0, len - 1); return if (horiz) src[l * w + k] else src[k * w + l] }
            for (i in -r..r) { val c = at(i); sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            val n = 2 * r + 1
            for (i in 0 until len) {
                val o = if (horiz) l * w + i else i * w + l
                dst[o] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                val a = at(i + r + 1); val d = at(i - r)
                sr += ((a shr 16) and 255) - ((d shr 16) and 255)
                sg += ((a shr 8) and 255) - ((d shr 8) and 255)
                sb += (a and 255) - (d and 255)
            }
        }
    }

    private fun applySmoke(t: Ambient.Theme) {
        val d = Ambient.deep(t.dark)
        if (oldAndroid) {
            // Uten ekte uskarphet trengs litt tettere røyk for at teksten skal være lett å lese
            smokeBg.setColorsAt(
                intArrayOf(Ambient.alpha(d, 0x5C), Ambient.alpha(d, 0x6C), Ambient.alpha(d, 0xA8), Ambient.alpha(d, 0xD8)),
                floatArrayOf(0f, 0.38f, 0.72f, 1f),
            )
            return
        }
        // Nesten klart i midten, gradvis mørkere mot kanten («røykfarget», ikke svart)
        smokeBg.setColorsAt(
            intArrayOf(Ambient.alpha(d, 0x2E), Ambient.alpha(d, 0x38), Ambient.alpha(d, 0x8C), Ambient.alpha(d, 0xC4)),
            floatArrayOf(0f, 0.38f, 0.72f, 1f),
        )
    }
}
