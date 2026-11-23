package no.carlink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Øverste lag i launcheren. Bytter den lille musepilen ut med en stor, godt synlig prikk
 * (i coverfargen) som vokser litt når den står over noe som kan trykkes.
 * Gjelder joystick/mus i CarLink. Valg: Normal / Stor / Ekstra stor / Av.
 */
class PointerRoot(ctx: Context) : FrameLayout(ctx) {

    enum class Size(val label: String, val dp: Int) { NORMAL("Normal", 26), LARGE("Large", 38), XLARGE("Extra large", 54), OFF("Off", 0) }

    companion object {
        fun size(ctx: Context): Size =
            runCatching { Size.valueOf(ctx.prefs().getString("pointer", "NORMAL")!!) }.getOrDefault(Size.NORMAL)
        fun setSize(ctx: Context, s: Size) = ctx.prefs().edit().putString("pointer", s.name).apply()
    }

    private var plain: PointerIcon? = null
    private var hot: PointerIcon? = null
    private var accent = C.ACCENT

    /** Lages på nytt bare når fargen eller størrelsen endres (to små bilder). */
    fun refresh(color: Int = accent) {
        accent = color
        val s = size(context)
        if (s == Size.OFF) { plain = null; hot = null; return }
        plain = icon(s.dp, color, false)
        hot = icon((s.dp * 1.35f).toInt(), color, true)
    }

    private fun icon(sizeDp: Int, color: Int, ring: Boolean): PointerIcon {
        val px = sizeDp.dp
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val r = px / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x66000000; c.drawCircle(r, r, r, p)                                // mørk kant: synlig på lys bakgrunn
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(r, r, r - 2.dp, p)                 // hvit ring: synlig på mørk bakgrunn
        p.color = color or 0xFF000000.toInt(); c.drawCircle(r, r, r - 5.dp, p)       // fylt med coverfargen
        if (ring) { p.color = 0xFFFFFFFF.toInt(); c.drawCircle(r, r, r * 0.22f, p) }   // over en knapp: hvit kjerne
        return PointerIcon.create(b, r, r)
    }

    private var lastHot: View? = null

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        val target = buttonAt(this, event.getX(pointerIndex), event.getY(pointerIndex))
        // A soft sound each time the dot moves onto a new button (choice in Settings → Joystick)
        if (target != null && target !== lastHot) HoverSound.play(context)
        lastHot = target
        val normal = plain ?: return super.onResolvePointerIcon(event, pointerIndex)
        return if (target != null) hot else normal
    }

    /** The button under the point, or null (fast: follows only the branch under the dot). */
    private fun buttonAt(v: View, x: Float, y: Float): View? {
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                val c = v.getChildAt(i)
                if (c.visibility != VISIBLE || c.alpha < 0.05f) continue
                val cx = x + v.scrollX - c.left - c.translationX
                val cy = y + v.scrollY - c.top - c.translationY
                if (cx < 0 || cy < 0 || cx >= c.width || cy >= c.height) continue
                buttonAt(c, cx, cy)?.let { return it }
                if (c.isClickable) return null   // covering surface (e.g. behind the app overview): stop here
            }
        }
        // Large touch areas (map, background that closes) don't count as a "button"
        return if (v !== this && v.isClickable && v.isEnabled && v.width.toLong() * v.height < width.toLong() * height / 4) v else null
    }
}
