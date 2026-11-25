package no.carlink.diagnose

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Joystick og knapper: viser direkte det bilen sender (stikka, klikk, knappene ved joysticken og
 * rattknappene), og har en mustest der du treffer mål på skjermen – samme test hver gang,
 * så resultatene kan sammenlignes over tid.
 */
class Joystick(ctx: Context) : Section(ctx, "Joystick and buttons") {
    @Volatile private var proc: Process? = null
    private val stick = Stick(ctx)
    private val events = ctx.label("", 15f, C.DIM).apply { setLineSpacing(0f, 1.2f) }
    private val stateTv = ctx.label("Move the joystick …", 16f, C.TEXT, true)
    private val recent = ArrayDeque<String>()
    private var frames = 0; private var clicks = 0
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun collect(): Any = MouseTest.history(ctx)

    @Suppress("UNCHECKED_CAST")
    override fun render(d: Any) {
        val hist = d as List<MouseTest.Result>
        val top = ctx.hrow().apply { gravity = android.view.Gravity.TOP }
        val live = ctx.card()
        live.add(ctx.label("Live from the car", 18f, C.TEXT, true))
        (stick.parent as? android.view.ViewGroup)?.removeView(stick)
        (stateTv.parent as? android.view.ViewGroup)?.removeView(stateTv)
        (events.parent as? android.view.ViewGroup)?.removeView(events)
        val inner = ctx.hrow().apply { gravity = android.view.Gravity.TOP }
        inner.add(stick, 220.dp, 220.dp, right = 18.dp)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.add(stateTv); col.add(events, top = 8.dp)
        inner.add(col, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        live.add(inner, top = 10.dp)
        top.add(live, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f, right = 14.dp)

        val t = ctx.card()
        t.add(ctx.label("Mouse test", 18f, C.TEXT, true))
        t.add(ctx.label("Hit ${MouseTest.TARGETS} targets with the joystick. Same test every time, so the results can be compared.", 14f, C.DIM), top = 6.dp)
        t.add(ctx.button("Start the mouse test", 0x664FC3F7) { MouseTest.start(ctx) { refresh() } }, LinearLayout.LayoutParams.WRAP_CONTENT, top = 12.dp)
        if (hist.isEmpty()) t.add(ctx.label("No tests yet", 15f, C.DIM), top = 12.dp)
        hist.takeLast(6).reversed().forEach { r -> t.add(ctx.label(r.line(), 15f, C.TEXT), top = 10.dp) }
        top.add(t, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        body.add(top, top = 14.dp)
    }

    override fun onShow() {
        super.onShow()
        if (proc != null) return
        proc = Sh.stream("exec logcat -v raw -T 1 -s XCarBusLexusCar:D") { l ->
            val i = l.indexOf("rxData:"); if (i < 0) return@stream
            val p = l.substring(i + 7).substringBefore(',').trim().split(' ').map { it.toIntOrNull(16) ?: -1 }
            main.post { frame(p) }
        }
    }

    override fun onHide() { runCatching { proc?.destroy() }; proc = null }

    private fun frame(b: List<Int>) {
        if (b.size < 4) return
        when {
            b[0] == 0x57 && b.size >= 14 && b[8] == 0x10 -> { frames++; stick.set(b[12], b[13]); stateTv.text = "Position x ${b[12]}  y ${b[13]}" }
            b[0] == 0x22 && b[2] == 0x10 -> when (b[3]) { 1 -> { clicks++; ev("Joystick pressed") }; 0 -> ev("Joystick released"); 2 -> ev("Joystick held and moved (scroll)") }
            b[0] == 0x22 && b[2] == 0x2a -> if (b[3] == 1) ev("MENU button")
            b[0] == 0x22 && b[2] == 0x32 -> if (b[3] == 1) ev("Scroll UP button")
            b[0] == 0x22 && b[2] == 0x33 -> if (b[3] == 1) ev("Scroll DOWN button")
            b[0] == 0x22 && b[2] == 0x34 -> if (b[3] == 1) ev("HOME button")
            b[0] == 0x21 && b[2] == 0x11 -> if (b[3] == 1) ev("Steering wheel: NEXT")
            b[0] == 0x21 && b[2] == 0x10 -> if (b[3] == 1) ev("Steering wheel: PREVIOUS")
            b[0] == 0x22 && b[2] != 0x81 && b[2] != 0x82 -> if (b[3] == 1) ev("Button 0x%02x".format(b[2]))
        }
    }

    private fun ev(s: String) {
        recent.addFirst("${fmt.format(Date())}  $s"); while (recent.size > 10) recent.removeLast()
        events.text = recent.joinToString("\n")
    }

    override fun report(d: Any): String {
        @Suppress("UNCHECKED_CAST") val hist = d as List<MouseTest.Result>
        return if (hist.isEmpty()) "Mouse test: never run" else "Mouse tests (newest last):\n" + hist.takeLast(10).joinToString("\n") { "  " + it.line() }
    }

    /** Stikkas posisjon (0–255 på hver akse) med spor. */
    private class Stick(ctx: Context) : View(ctx) {
        private var x = -1; private var y = -1
        private val trail = ArrayDeque<Pair<Int, Int>>()
        private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x55FFFFFF }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.ACCENT }
        private val tr = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x664FC3F7 }
        fun set(nx: Int, ny: Int) { x = nx; y = ny; trail.addLast(nx to ny); while (trail.size > 60) trail.removeFirst(); invalidate() }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.drawRoundRect(1f, 1f, w - 1, h - 1, 24f, 24f, box)
            c.drawLine(w / 2, 0f, w / 2, h, box); c.drawLine(0f, h / 2, w, h / 2, box)
            trail.forEach { (a, b) -> c.drawCircle(a / 255f * w, (1 - b / 255f) * h, 4f, tr) }
            if (x >= 0) c.drawCircle(x / 255f * w, (1 - y / 255f) * h, 14f, dot)
        }
    }
}

/** Mustesten: mål dukker opp ett om gangen på faste steder; tid og bom måles. */
object MouseTest {
    const val TARGETS = 10
    // Faste steder (andel av skjermen), så testen er lik hver gang
    private val spots = listOf(0.15f to 0.25f, 0.8f to 0.7f, 0.5f to 0.5f, 0.9f to 0.2f, 0.1f to 0.8f,
        0.35f to 0.65f, 0.65f to 0.3f, 0.25f to 0.45f, 0.75f to 0.85f, 0.55f to 0.15f)

    data class Result(val at: Long, val hits: Int, val misses: Int, val avgMs: Long, val avgMissPx: Int) {
        fun line() = "${SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(at))}: $hits/$TARGETS hit · $misses misses · ${String.format("%.1f", avgMs / 1000f)} s per target" +
            (if (misses > 0) " · missed by $avgMissPx px on average" else "")
        fun json() = org.json.JSONObject().put("at", at).put("h", hits).put("m", misses).put("ms", avgMs).put("px", avgMissPx)
    }

    fun history(ctx: Context): List<Result> = runCatching {
        val a = org.json.JSONArray(ctx.getSharedPreferences("diag", 0).getString("mouseTests", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Result(o.getLong("at"), o.getInt("h"), o.getInt("m"), o.getLong("ms"), o.getInt("px")) } }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, r: Result) {
        val list = (history(ctx) + r).takeLast(40)
        val a = org.json.JSONArray(); list.forEach { a.put(it.json()) }
        ctx.getSharedPreferences("diag", 0).edit().putString("mouseTests", a.toString()).apply()
    }

    fun start(ctx: Context, done: () -> Unit) {
        val act = ctx as? android.app.Activity ?: return
        val root = act.window.decorView as FrameLayout
        val layer = FrameLayout(ctx).apply { setBackgroundColor(0xF0101317.toInt()); isClickable = true }
        val info = ctx.label("Target 1 of $TARGETS – move the dot onto the circle and click", 18f, C.TEXT, true)
        layer.addView(info, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = 30.dp; topMargin = 20.dp })
        val size = 76.dp
        val target = TextView(ctx).apply { background = rounded(0xCC4FC3F7.toInt(), 38, 0xFFFFFFFF.toInt()) }
        layer.addView(target, FrameLayout.LayoutParams(size, size))
        val cancel = ctx.button("Stop the test") { root.removeView(layer); done() }
        layer.addView(cancel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.END).apply { rightMargin = 30.dp; topMargin = 16.dp })
        var n = 0; var hits = 0; var misses = 0; var totalMs = 0L; var missPx = 0L
        var shownAt = 0L
        fun place() {
            val (fx, fy) = spots[n]
            val w = root.width; val h = root.height
            target.x = (fx * (w - size)); target.y = (90.dp + fy * (h - size - 110.dp))
            info.text = "Target ${n + 1} of $TARGETS – move the dot onto the circle and click"
            shownAt = SystemClock.elapsedRealtime()
        }
        fun next() {
            n++
            if (n >= TARGETS) {
                val r = Result(System.currentTimeMillis(), hits, misses, if (hits > 0) totalMs / hits else 0, if (misses > 0) (missPx / misses).toInt() else 0)
                save(ctx, r); root.removeView(layer); done()
                android.widget.Toast.makeText(ctx, r.line(), android.widget.Toast.LENGTH_LONG).show()
            } else place()
        }
        target.setOnClickListener { hits++; totalMs += SystemClock.elapsedRealtime() - shownAt; next() }
        // Klikk utenfor målet = bom (avstand til midten av målet)
        layer.setOnTouchListener { _, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP) {
                val cx = target.x + size / 2f; val cy = target.y + size / 2f
                missPx += kotlin.math.hypot(e.x - cx, e.y - cy).toLong(); misses++
            }
            false
        }
        root.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        layer.post { place() }
    }
}
