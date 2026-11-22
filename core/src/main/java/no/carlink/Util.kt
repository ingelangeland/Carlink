package no.carlink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

/** Fargepalett (mørkt bil-tema). */
object C {
    const val BG = 0xFF0E1116.toInt()
    const val PANEL = 0xFF171B22.toInt()
    const val PANEL2 = 0xFF242A35.toInt()
    const val RAIL = 0xFF08090C.toInt()
    const val ACCENT = 0xFF4C8DFF.toInt()
    const val OK = 0xFF34C759.toInt()
    const val WARN = 0xFFFF9F0A.toInt()
    const val TEXT = 0xFFE8EAED.toInt()
    const val SUB = 0xFF9AA0A6.toInt()
    const val DIV = 0xFF2A303A.toInt()
}

fun Context.prefs() = getSharedPreferences("carlink", Context.MODE_PRIVATE)

fun rounded(color: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusDp.dp.toFloat()
    if (stroke != null) setStroke(1.dp, stroke)
}

fun Context.label(text: CharSequence = "", sp: Float = 15f, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun Context.pill(text: String, color: Int = C.PANEL2, onClick: () -> Unit): TextView =
    label(text, 14f, C.TEXT, true).apply {
        background = rounded(color, 22)
        setPadding(18.dp, 12.dp, 18.dp, 12.dp)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

/** FrameLayout med en coroutine-scope som lever mens viewet er festet til vinduet. */
open class ScopedFrame(ctx: Context) : FrameLayout(ctx) {
    private var scope: CoroutineScope? = null
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val s = MainScope(); scope = s; onStart(s)
    }
    override fun onDetachedFromWindow() {
        scope?.cancel(); scope = null; onStop()
        super.onDetachedFromWindow()
    }
    protected open fun onStart(scope: CoroutineScope) {}
    protected open fun onStop() {}
}

object Perms {
    fun forRole(sender: Boolean): Array<String> {
        val l = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            l += Manifest.permission.BLUETOOTH_CONNECT
            l += Manifest.permission.BLUETOOTH_SCAN
            if (!sender) l += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= 33) {
            l += Manifest.permission.POST_NOTIFICATIONS
            if (sender) l += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return l.toTypedArray()
    }
    fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    fun missing(ctx: Context, arr: Array<String>) = arr.filter { !has(ctx, it) }
}
