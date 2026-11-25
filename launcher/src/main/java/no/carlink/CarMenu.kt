package no.carlink

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * Menyer i CarLinks egen stil (mørkt glass, avrundede rader, aksentfargen fra coveret) i stedet
 * for Androids grå standardvinduer. Store rader, lette å treffe med joysticken.
 */
object CarMenu {
    class Item(val text: String, val danger: Boolean = false, val run: () -> Unit)

    private fun base(ctx: Context): Pair<Dialog, LinearLayout> {
        val t = Ambient.theme.value
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 22.dp, 24.dp, 18.dp)
            background = rounded(Ambient.alpha(Ambient.deep(t.dark), 0xF5), 28, 0x26FFFFFF)
        }
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.55f)
        }
        return d to box
    }

    private fun header(ctx: Context, box: LinearLayout, title: String, icon: Drawable?, sub: String? = null) {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (icon != null) {
            val wrap = android.widget.FrameLayout(ctx).apply { background = rounded(0xF2FFFFFF.toInt(), 22) }
            wrap.addView(ImageView(ctx).apply { setImageDrawable(icon) }, android.widget.FrameLayout.LayoutParams(30.dp, 30.dp, Gravity.CENTER))
            r.addView(wrap, LinearLayout.LayoutParams(44.dp, 44.dp).apply { rightMargin = 14.dp })
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ctx.label(title, 20f, C.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (sub != null) col.addView(ctx.label(sub, 13.5f, 0xA6FFFFFF.toInt()).apply { setPadding(0, 2.dp, 0, 0) })
        r.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 14.dp })
    }

    private fun row(ctx: Context, text: String, color: Int = C.TEXT, onClick: () -> Unit): TextView = ctx.label(text, 17f, color, true).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(20.dp, 0, 20.dp, 0)
        background = rounded(0x14FFFFFF, 16)
        isClickable = true; isFocusable = true
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { onClick() }
    }

    private fun LinearLayout.addRow(v: View) =
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 58.dp).apply { topMargin = 8.dp })

    /** Liste med valg. Trykk på et valg lukker menyen og utfører det. */
    fun show(ctx: Context, title: String, items: List<Item>, icon: Drawable? = null, sub: String? = null) {
        val (d, box) = base(ctx)
        header(ctx, box, title, icon, sub)
        items.forEach { it0 -> box.addRow(row(ctx, it0.text, if (it0.danger) 0xFFFF8A80.toInt() else C.TEXT) { d.dismiss(); it0.run() }) }
        box.addRow(row(ctx, "Cancel", Ambient.theme.value.accent.let { if (ColorUtils.calculateLuminance(it) < 0.15) C.TEXT else it }) { d.dismiss() }.apply {
            background = null; gravity = Gravity.CENTER
        })
        d.show()
        d.window?.setLayout(560.dp, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Velg flere (med avkrysning), høyst [max]. [save] får de valgte, i listens rekkefølge. */
    fun pick(ctx: Context, title: String, labels: List<String>, icons: List<Drawable?>, checked: BooleanArray, max: Int, save: (BooleanArray) -> Unit) {
        val (d, box) = base(ctx)
        val t = Ambient.theme.value
        val counter = ctx.label("", 13.5f, 0xA6FFFFFF.toInt())
        fun count() { counter.text = "${checked.count { it }} of $max chosen" }
        header(ctx, box, title, null)
        box.addView(counter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 4.dp })
        count()
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val checks = ArrayList<View>()
        fun paint(i: Int) {
            checks[i].background = if (checked[i]) rounded(t.accent, 13) else rounded(0x00000000, 13, 0x80FFFFFF.toInt())
        }
        labels.forEachIndexed { i, l ->
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(14.dp, 0, 18.dp, 0)
                background = rounded(0x14FFFFFF, 16)
                isClickable = true
            }
            icons.getOrNull(i)?.let { ic -> r.addView(ImageView(ctx).apply { setImageDrawable(ic) }, LinearLayout.LayoutParams(34.dp, 34.dp).apply { rightMargin = 14.dp }) }
            r.addView(ctx.label(l, 17f, C.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val c = View(ctx); checks += c
            r.addView(c, LinearLayout.LayoutParams(26.dp, 26.dp))
            paint(i)
            r.setOnClickListener {
                if (!checked[i] && checked.count { it } >= max) {
                    android.widget.Toast.makeText(ctx, "Room for $max apps – untick one first", android.widget.Toast.LENGTH_SHORT).show()
                } else { checked[i] = !checked[i]; paint(i); count() }
            }
            list.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 58.dp).apply { topMargin = 8.dp })
        }
        box.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = true; addView(list) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val dark = ColorUtils.calculateLuminance(t.accent) > 0.5
        buttons.addView(row(ctx, "Cancel") { d.dismiss() }.apply { gravity = Gravity.CENTER; background = null },
            LinearLayout.LayoutParams(160.dp, 54.dp).apply { rightMargin = 10.dp })
        buttons.addView(row(ctx, "Save", if (dark) 0xFF101418.toInt() else C.TEXT) { d.dismiss(); save(checked) }.apply {
            gravity = Gravity.CENTER; background = rounded(t.accent, 16)
        }, LinearLayout.LayoutParams(160.dp, 54.dp))
        box.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 14.dp })
        d.show()
        val h = (ctx.resources.displayMetrics.heightPixels * 0.9f).toInt()
        d.window?.setLayout(600.dp, h)
    }
}
