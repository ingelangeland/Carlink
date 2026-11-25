package no.carlink.diagnose

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object C {
    const val BG = 0xFF16191F.toInt()
    const val CARD = 0xFF20242C.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val DIM = 0xA6FFFFFF.toInt()
    const val ACCENT = 0xFF4FC3F7.toInt()
    const val OK = 0xFF4CD964.toInt()
    const val WARN = 0xFFFFC83D.toInt()
    const val BAD = 0xFFFF5A52.toInt()
}

val Int.dp: Int get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

fun rounded(color: Int, radiusDp: Int, stroke: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radiusDp.dp.toFloat()
    if (stroke != 0) setStroke(1.dp, stroke)
}

fun Context.label(text: String, size: Float = 16f, color: Int = C.TEXT, bold: Boolean = false) = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

/** Stor knapp, lett å treffe med joysticken. */
fun Context.button(text: String, color: Int = 0x2EFFFFFF, onClick: () -> Unit) = label(text, 16f, C.TEXT, true).apply {
    gravity = Gravity.CENTER
    setPadding(26.dp, 16.dp, 26.dp, 16.dp)
    background = rounded(color, 16)
    isClickable = true
    setOnClickListener { onClick() }
}

fun LinearLayout.add(v: View, w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, weight: Float = 0f, top: Int = 0, right: Int = 0) =
    addView(v, LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top; rightMargin = right })

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(22.dp, 16.dp, 22.dp, 16.dp)
    background = rounded(C.CARD, 18)
}

fun Context.hrow(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
