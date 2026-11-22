package no.carlink

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Albumcoveret som bakgrunn, laget for bred skjerm (16:9):
 *  - Hele skjermen: uskarp versjon av coveret (fyller alt, ingen svarte felt).
 *  - Høyre side: hele coveret skarpt i full høyde – på 16:9 er det nøyaktig 56 % av bredden,
 *    så ingenting kuttes bort. Venstre kant tones mykt ut i bakgrunnen.
 *  - Toning i coverets egen mørke farge der det ligger tekst og knapper.
 * Fargene fra coveret deles ut via Ambient.theme.
 */
@SuppressLint("ViewConstructor")
class ArtBackground(ctx: Context) : ScopedFrame(ctx) {

    // Uskarpe lag (hele skjermen) og skarpe lag (kvadrat til høyre), to av hver for myk overgang
    private val a = ImageView(ctx)
    private val b = ImageView(ctx)
    private var front = a
    private val sa = FadeEdgeImageView(ctx)
    private val sb = FadeEdgeImageView(ctx)
    private var sharpFront = sa
    private var lastId = Int.MIN_VALUE
    private val drifts = mutableListOf<ObjectAnimator>()
    private var themeAnim: ValueAnimator? = null

    // Toning: fra venstre (kort og apper) og nedenfra (musikklinjen) + litt øverst
    private val scrimSide = GradientDrawable().apply { orientation = GradientDrawable.Orientation.LEFT_RIGHT }
    private val scrimVert = GradientDrawable().apply { orientation = GradientDrawable.Orientation.TOP_BOTTOM }

    init {
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF1B2440.toInt(), 0xFF0B0E14.toInt(), 0xFF2A1638.toInt())
        )
        for (iv in listOf(a, b)) {
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.alpha = 0f
            if (Build.VERSION.SDK_INT >= 31) {
                iv.setRenderEffect(RenderEffect.createBlurEffect(36f.px(), 36f.px(), Shader.TileMode.CLAMP))
            }
            // Statisk forstørrelse (ingen animasjon) så uskarpe kanter ikke synes
            iv.scaleX = 1.15f; iv.scaleY = 1.15f
            addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        // Mørkner det uskarpe laget litt, så det skarpe coveret skiller seg ut
        addView(View(ctx).apply { setBackgroundColor(0x40000000) }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        for (iv in listOf(sa, sb)) {
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.alpha = 0f
            addView(iv, LayoutParams(0, 0, Gravity.END or Gravity.CENTER_VERTICAL))
        }
        addView(View(ctx).apply { background = scrimSide }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(View(ctx).apply { background = scrimVert }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyScrim(Ambient.theme.value)
    }

    override fun onStart(scope: CoroutineScope) {
        scope.launch { Ambient.media.collect { (m, _) -> setArt(m?.art, m?.artId ?: 0) } }
        scope.launch { Ambient.theme.collect { applyScrim(it) } }
        // Ingen kontinuerlig bevegelse: bakgrunnen tegnes bare når coveret byttes (sparer strøm)
        for (iv in emptyList<View>()) {
            drifts += ObjectAnimator.ofPropertyValuesHolder(
                iv,
                PropertyValuesHolder.ofFloat(View.SCALE_X, if (iv is FadeEdgeImageView) 1f else 1.12f, if (iv is FadeEdgeImageView) 1.04f else 1.25f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, if (iv is FadeEdgeImageView) 1f else 1.12f, if (iv is FadeEdgeImageView) 1.04f else 1.25f),
            ).apply {
                duration = 45_000
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    override fun onStop() {
        drifts.forEach { it.cancel() }; drifts.clear()
        themeAnim?.cancel()
    }

    /** Skarpt cover: kvadrat i full høyde (maks 62 % av bredden), helt til høyre. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = minOf(h, (w * 0.62f).toInt())
        post {
            for (iv in listOf(sa, sb)) {
                val lp = iv.layoutParams as LayoutParams
                lp.width = size; lp.height = size
                iv.layoutParams = lp
            }
        }
    }

    private fun applyScrim(t: Ambient.Theme) {
        val d = t.dark
        scrimSide.setColors(
            intArrayOf(Ambient.alpha(d, 0xB3), Ambient.alpha(d, 0x80), Ambient.alpha(d, 0x1A), Ambient.alpha(d, 0x00)),
            floatArrayOf(0f, 0.36f, 0.5f, 1f)
        )
        scrimVert.setColors(
            intArrayOf(Ambient.alpha(d, 0x99), Ambient.alpha(d, 0x00), Ambient.alpha(d, 0x00), Ambient.alpha(d, 0xF2)),
            floatArrayOf(0f, 0.22f, 0.62f, 1f)
        )
    }

    private fun setArt(bmp: Bitmap?, id: Int) {
        if (DebugFlags.noArt) return
        if (id == lastId) return
        lastId = id
        if (bmp == null) {
            // Ingen musikk: vis et stemningsbilde (fiktiv fjord med nordlys) i stedet for tom skjerm
            val ph = placeholder ?: runCatching {
                android.graphics.BitmapFactory.decodeResource(resources, no.carlink.launcher.R.drawable.placeholder_cover)
            }.getOrNull().also { placeholder = it }
            if (ph != null) { showArt(ph); return }
            front.animate().alpha(0f).setDuration(800).start()
            sharpFront.animate().alpha(0f).setDuration(800).start()
            animateTheme(Ambient.DEFAULT)
            Ambient.topRightNeed.value = 0f
            return
        }
        showArt(bmp)
    }

    private var placeholder: Bitmap? = null

    private fun showArt(bmp: Bitmap) {
        // Uskarpt lag: liten versjon holder (og blir ekstra myk på eldre Android uten RenderEffect)
        val bw = if (Build.VERSION.SDK_INT >= 31) 256 else 32
        val blurSrc = Bitmap.createScaledBitmap(bmp, bw, (bw * bmp.height / bmp.width.coerceAtLeast(1)).coerceAtLeast(1), true)
        val next = if (front === a) b else a
        next.setImageBitmap(blurSrc)
        next.alpha = 0f
        next.animate().alpha(1f).setDuration(1100).start()
        front.animate().alpha(0f).setDuration(1100).start()
        front = next
        // Skarpt lag: hele coveret
        val sNext = if (sharpFront === sa) sb else sa
        sNext.setImageBitmap(bmp)
        sNext.alpha = 0f
        sNext.animate().alpha(1f).setDuration(1100).start()
        sharpFront.animate().alpha(0f).setDuration(1100).start()
        sharpFront = sNext

        val small = Bitmap.createScaledBitmap(bmp, 96, (96 * bmp.height / bmp.width.coerceAtLeast(1)).coerceAtLeast(1), true)
        Ambient.topRightNeed.value = measureTopRight(small)
        Palette.from(small).generate { p ->
            val dominant = p?.getDominantColor(C.ACCENT) ?: C.ACCENT
            val accent = p?.getVibrantColor(p.getLightVibrantColor(p.getLightMutedColor(dominant))) ?: dominant
            val dark = p?.getDarkMutedColor(p.getDarkVibrantColor(dominant)) ?: dominant
            animateTheme(Ambient.Theme(Ambient.readable(accent), Ambient.deep(dark)))
        }
    }

    /**
     * Måler øverste høyre hjørne av coveret (der fart og vær står): gjennomsnittlig lyshet
     * og hvor urolig det er. Mørkt og rolig → 0 (ingen hjelp trengs), lyst/urolig → opptil 1.
     */
    private fun measureTopRight(b: Bitmap): Float {
        val x0 = (b.width * 0.72f).toInt(); val y1 = (b.height * 0.16f).toInt().coerceAtLeast(1)
        var sum = 0.0; var sumSq = 0.0; var n = 0
        for (yy in 0 until y1) for (xx in x0 until b.width) {
            val c = b.getPixel(xx, yy)
            val l = (0.2126 * android.graphics.Color.red(c) + 0.7152 * android.graphics.Color.green(c) + 0.0722 * android.graphics.Color.blue(c)) / 255.0
            sum += l; sumSq += l * l; n++
        }
        if (n == 0) return 0f
        val mean = sum / n
        val std = kotlin.math.sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
        // Toppen er allerede litt tonet, så bare lyse/urolige partier krever hjelp
        return (((mean - 0.28) / 0.35) + std * 1.8).toFloat().coerceIn(0f, 1f)
    }

    /** Glir over til nye farger i stedet for å hoppe. */
    private fun animateTheme(target: Ambient.Theme) {
        themeAnim?.cancel()
        val from = Ambient.theme.value
        themeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            addUpdateListener {
                val t = Ambient.blend(from, target, it.animatedValue as Float)
                Ambient.theme.value = t
                Ambient.accent.value = t.accent
            }
            start()
        }
    }

    private fun Float.px() = this * resources.displayMetrics.density
}
