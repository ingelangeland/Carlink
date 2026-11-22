package no.carlink

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.appcompat.widget.AppCompatImageView

/** Bilde der venstre kant (og litt av bunnen) tones gradvis ut, så det glir inn i bakgrunnen. */
class FadeEdgeImageView(ctx: Context) : AppCompatImageView(ctx) {

    var fadeLeft = 0.38f
    var fadeBottom = 0.18f

    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var leftShader: Shader? = null
    private var bottomShader: Shader? = null

    init {
        // Eget grafikklag: kantene tones ut én gang per bilde; overtoninger (alpha) koster nesten ingenting
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val fw = w * fadeLeft; val fh = h * fadeBottom
        leftShader = LinearGradient(0f, 0f, fw, 0f, 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        bottomShader = LinearGradient(0f, h - fh, 0f, h.toFloat(), 0xFF000000.toInt(), 0x33000000, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        super.onDraw(canvas)
        mask.shader = leftShader
        canvas.drawRect(0f, 0f, width * fadeLeft, height.toFloat(), mask)
        val fh = height * fadeBottom
        mask.shader = bottomShader
        canvas.drawRect(0f, height - fh, width.toFloat(), height.toFloat(), mask)
    }
}
