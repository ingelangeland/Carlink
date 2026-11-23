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
    private val smokeBg = GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT }
    private var anim: ValueAnimator? = null
    private var progress = 0f
    private var blurStep = -1

    init {
        visibility = INVISIBLE
        isClickable = true                      // fanger trykk så de ikke går til skjermen bak
        setOnClickListener { close() }          // trykk utenfor innholdet lukker
        smoke.background = smokeBg
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
        visibility = VISIBLE
        onOpening()
        openCount++; Ambient.overlayOpen = true
        animateTo(1f, 240)
    }

    fun close(immediate: Boolean = false) {
        if (!isOpen) return
        isOpen = false
        openCount = (openCount - 1).coerceAtLeast(0); Ambient.overlayOpen = openCount > 0
        if (immediate) { anim?.cancel(); setProgress(0f); visibility = INVISIBLE; return }
        animateTo(0f, 200)
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
                    if (!isOpen && progress <= 0.001f) visibility = INVISIBLE
                }
            })
            start()
        }
    }

    /** p = 0 (skjult) … 1 (helt åpen): uskarphet, røyk og innhold følger hverandre. */
    private fun setProgress(p: Float) {
        progress = p
        smoke.alpha = p
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

    private fun applySmoke(t: Ambient.Theme) {
        val d = Ambient.deep(t.dark)
        // Nesten klart i midten, gradvis mørkere mot kanten («røykfarget», ikke svart)
        smokeBg.setColorsAt(
            intArrayOf(Ambient.alpha(d, 0x2E), Ambient.alpha(d, 0x38), Ambient.alpha(d, 0x8C), Ambient.alpha(d, 0xC4)),
            floatArrayOf(0f, 0.38f, 0.72f, 1f),
        )
    }
}
