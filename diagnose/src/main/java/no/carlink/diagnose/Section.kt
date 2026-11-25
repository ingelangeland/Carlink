package no.carlink.diagnose

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Én fane i diagnoseappen. [collect] henter tallene (i en egen tråd), [render] viser dem,
 * og [report] lager den korte teksten som kommer med i rapporten.
 * Ingenting måles når fanen ikke vises.
 */
abstract class Section(val ctx: Context, val title: String) {
    protected val main = Handler(Looper.getMainLooper())
    protected val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 24.dp, 24.dp) }
    open val view: View by lazy { ScrollView(ctx).apply { isVerticalScrollBarEnabled = true; addView(body) } }
    @Volatile protected var data: Any? = null
    @Volatile private var loading = false

    abstract fun collect(): Any
    abstract fun render(d: Any)
    abstract fun report(d: Any): String

    open fun onShow() { refresh() }
    open fun onHide() {}

    fun refresh() {
        if (loading) return
        loading = true
        if (data == null) { body.removeAllViews(); body.add(ctx.label("Reading …", 18f, C.DIM)) }
        Thread {
            val d = runCatching { collect() }.getOrElse { "Error: ${it.message}" }
            data = d; loading = false
            main.post { body.removeAllViews(); header(); runCatching { render(d) }.onFailure { body.add(ctx.label("Error: ${it.message}", 16f, C.BAD)) } }
        }.start()
    }

    /** Til rapporten: hent ferske tall (blokkerer – kalles fra en tråd). */
    fun reportNow(): String = runCatching { report(collect()) }.getOrElse { "(error: ${it.message})" }

    protected open fun header() {
        val r = ctx.hrow()
        r.add(ctx.label(title, 26f, C.TEXT, true), 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        r.add(ctx.button("Refresh") { refresh() }, LinearLayout.LayoutParams.WRAP_CONTENT)
        body.add(r)
    }

    // ---- byggeklosser
    protected fun cardOf(title: String, vararg lines: Pair<String, Int>): LinearLayout {
        val c = ctx.card()
        c.add(ctx.label(title, 18f, C.TEXT, true))
        lines.forEach { (t, col) -> c.add(ctx.label(t, 15f, col).apply { setLineSpacing(0f, 1.15f) }, top = 6.dp) }
        body.add(c, top = 14.dp)
        return c
    }

    protected fun lines(title: String, items: List<String>, empty: String = "Nothing recorded") =
        cardOf(title, *(if (items.isEmpty()) listOf(empty to C.DIM) else items.map { it to C.DIM }).toTypedArray())

    protected fun kv(c: LinearLayout, k: String, v: String, col: Int = C.TEXT) {
        val r = ctx.hrow()
        r.add(ctx.label(k, 15f, C.DIM), 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        r.add(ctx.label(v, 15f, col, true), LinearLayout.LayoutParams.WRAP_CONTENT)
        c.add(r, top = 8.dp)
    }
}

/** Tidsstempelet foran hver linje i CarLinks logg: «11-25 22:00:15.286 …» → «22:00:15 …» */
fun shortTime(line: String): String = if (line.length > 19 && line[2] == '-' && line[5] == ' ') line.substring(6, 14) + line.substring(18) else line
