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

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.onDraw(canvas)
        val fw = width * fadeLeft
        mask.shader = LinearGradient(0f, 0f, fw, 0f, 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, fw, height.toFloat(), mask)
        val fh = height * fadeBottom
        mask.shader = LinearGradient(0f, height - fh, 0f, height.toFloat(), 0xFF000000.toInt(), 0x33000000, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, height - fh, width.toFloat(), height.toFloat(), mask)
        canvas.restoreToCount(layer)
    }
}
