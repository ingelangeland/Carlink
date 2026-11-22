package no.carlink

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Mørk flate uten skarp kant: kantene fader mykt ut over [featherPx] (som en uskarp skygge).
 * Brukes bak klokka, så den smelter inn i coveret i bakgrunnen.
 */
class SoftPanelDrawable(color: Int, private val radiusPx: Float, private val featherPx: Float) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        maskFilter = BlurMaskFilter(featherPx, BlurMaskFilter.Blur.NORMAL)
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        // Trekk inn med uskarphets-bredden, så hele fadingen havner innenfor viewet
        rect.set(b.left + featherPx, b.top + featherPx, b.right - featherPx, b.bottom - featherPx)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
