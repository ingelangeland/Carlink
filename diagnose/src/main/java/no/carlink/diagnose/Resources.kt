package no.carlink.diagnose

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Liten rute med systemressurser (samme som i CarLinks app-veksler): prosessor med kurve,
 * minne, temperatur, lagring og bilens batterispenning. Måler bare mens den vises.
 */
class ResourcesView(ctx: Context) : LinearLayout(ctx) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var proc: Process? = null
    private val cpuTv = value(); private val memTv = value(); private val tempTv = value()
    private val diskTv = value(); private val voltTv = value()
    private val graph = Graph(ctx)
    private var lastIdle = -1L; private var lastTotal = -1L

    init {
        orientation = VERTICAL
        setPadding(22.dp, 16.dp, 22.dp, 16.dp)
        background = rounded(C.CARD, 18)
        add(ctx.label("System", 18f, C.TEXT, true))
        row("Processor", cpuTv)
        add(graph, h = 46.dp, top = 4.dp)
        row("Memory", memTv); row("Temperature", tempTv); row("Storage", diskTv); row("Car battery", voltTv)
    }

    private fun value() = context.label("–", 15f, C.TEXT, true)
    private fun row(name: String, v: TextView) {
        val r = context.hrow()
        r.add(context.label(name, 15f, C.DIM), 0, LayoutParams.WRAP_CONTENT, 1f)
        r.add(v, LayoutParams.WRAP_CONTENT)
        add(r, top = 12.dp)
    }

    fun start() {
        if (proc != null) return
        lastIdle = -1; graph.clear(); disk()
        val block = ArrayList<String>()
        proc = Sh.stream("i=0; while true; do echo CPU; head -1 /proc/stat; echo MEM; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; " +
            "echo TEMP; for z in /sys/class/thermal/thermal_zone*; do echo \"$(cat \$z/type 2>/dev/null) $(cat \$z/temp 2>/dev/null)\"; done; " +
            "if [ \$((i % 10)) -eq 0 ]; then echo VOLT; logcat -d -t 2000 -s SpdService-HandlerManager:I 2>/dev/null | grep -o 'voltage=[0-9]*' | tail -1; fi; " +
            "echo END; i=\$((i+1)); sleep 1; done") { l ->
            if (l == "END") { val b = ArrayList(block); block.clear(); main.post { parse(b) } } else block.add(l)
        }
    }

    fun stop() { runCatching { proc?.destroy() }; proc = null }

    private fun disk() = runCatching {
        val s = StatFs(Environment.getDataDirectory().path)
        val total = s.totalBytes / 1e9; val free = s.availableBytes / 1e9
        diskTv.text = String.format("%.0f / %.0f GB", total - free, total)
        diskTv.setTextColor(if (free / total < 0.1) C.WARN else C.TEXT)
    }

    private fun parse(lines: List<String>) {
        if (proc == null) return
        var sec = ""; var memTotal = 0L; var memAvail = 0L
        val temps = ArrayList<Pair<String, Double>>()
        for (l in lines) {
            if (l in setOf("CPU", "MEM", "TEMP", "VOLT")) { sec = l; continue }
            when (sec) {
                "CPU" -> if (l.startsWith("cpu ")) {
                    val n = l.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                    if (n.size >= 5) {
                        val idle = n[3] + n[4]; val total = n.sum()
                        if (lastIdle >= 0 && total > lastTotal) {
                            val pct = (100.0 * (1 - (idle - lastIdle).toDouble() / (total - lastTotal))).coerceIn(0.0, 100.0)
                            cpuTv.text = "${pct.toInt()} %"; cpuTv.setTextColor(if (pct > 85) C.WARN else C.TEXT); graph.add(pct.toFloat())
                        }
                        lastIdle = idle; lastTotal = total
                    }
                }
                "MEM" -> { val kb = l.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0; if (l.startsWith("MemTotal")) memTotal = kb else memAvail = kb }
                "TEMP" -> { val i = l.lastIndexOf(' '); val raw = l.substring(i + 1).toDoubleOrNull()
                    if (i > 0 && raw != null) { val c = if (raw > 1000) raw / 1000 else raw; if (c in 5.0..130.0) temps += l.substring(0, i).lowercase() to c } }
                "VOLT" -> l.substringAfter("voltage=").trim().toIntOrNull()?.takeIf { it in 50..200 }?.let { v ->
                    voltTv.text = String.format(java.util.Locale("nb", "NO"), "%.1f V", v / 10f)
                    voltTv.setTextColor(when { v < 118 || v > 150 -> C.BAD; v < 122 -> C.WARN; else -> C.TEXT })
                }
            }
        }
        if (memTotal > 0) {
            memTv.text = String.format("%.1f / %.1f GB", (memTotal - memAvail) / 1048576.0, memTotal / 1048576.0)
            memTv.setTextColor(if (memAvail < memTotal / 10) C.WARN else C.TEXT)
        }
        val t = temps.filter { (n, _) -> "cpu" in n || "tsens" in n || "apc" in n }.maxOfOrNull { it.second } ?: temps.maxOfOrNull { it.second }
        if (t != null) { tempTv.text = "${t.toInt()} °C"; tempTv.setTextColor(when { t >= 85 -> C.BAD; t >= 70 -> C.WARN; else -> C.TEXT }) }
    }

    private class Graph(ctx: Context) : View(ctx) {
        private val values = ArrayDeque<Float>()
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * ctx.resources.displayMetrics.density; color = C.ACCENT }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x334FC3F7 }
        fun clear() { values.clear(); invalidate() }
        fun add(v: Float) { values.addLast(v); while (values.size > 40) values.removeFirst(); invalidate() }
        override fun onDraw(c: Canvas) {
            if (values.size < 2) return
            val w = width.toFloat(); val h = height.toFloat(); val step = w / 39f
            val p = Path(); val f = Path()
            values.forEachIndexed { i, v ->
                val x = w - (values.size - 1 - i) * step; val y = h - v / 100f * (h - 4) - 2
                if (i == 0) { p.moveTo(x, y); f.moveTo(x, h); f.lineTo(x, y) } else { p.lineTo(x, y); f.lineTo(x, y) }
            }
            f.lineTo(w, h); f.close(); c.drawPath(f, fill); c.drawPath(p, line)
        }
    }

    companion object {
        /** Ett øyeblikksbilde til rapporten. */
        fun snapshot(): String {
            val out = Sh.run("head -1 /proc/stat; sleep 1; head -1 /proc/stat; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; " +
                "for z in /sys/class/thermal/thermal_zone*; do echo \"T $(cat \$z/type 2>/dev/null) $(cat \$z/temp 2>/dev/null)\"; done; " +
                "logcat -d -t 3000 -s SpdService-HandlerManager:I 2>/dev/null | grep -o 'voltage=[0-9]*' | tail -1; df -h /data | tail -1; cat /proc/uptime")
            val cpu = out.lines().filter { it.startsWith("cpu ") }.map { l -> l.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() } }
            val pct = if (cpu.size == 2 && cpu[0].size >= 5) {
                val di = (cpu[1][3] + cpu[1][4]) - (cpu[0][3] + cpu[0][4]); val dt = cpu[1].sum() - cpu[0].sum()
                if (dt > 0) (100 - di * 100 / dt).toString() + " %" else "?"
            } else "?"
            val mt = Regex("MemTotal:\\s+(\\d+)").find(out)?.groupValues?.get(1)?.toLong() ?: 0
            val ma = Regex("MemAvailable:\\s+(\\d+)").find(out)?.groupValues?.get(1)?.toLong() ?: 0
            val temps = out.lines().filter { it.startsWith("T ") }.mapNotNull { l -> l.substringAfterLast(' ').toDoubleOrNull()?.let { if (it > 1000) it / 1000 else it } }.filter { it in 5.0..130.0 }
            val volt = Regex("voltage=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
            val up = out.lines().lastOrNull { it.matches(Regex("[0-9.]+ [0-9.]+")) }?.substringBefore('.')?.toLongOrNull()
            return "CPU $pct · memory ${if (mt > 0) String.format("%.1f/%.1f GB", (mt - ma) / 1048576.0, mt / 1048576.0) else "?"} · " +
                "hottest ${temps.maxOrNull()?.toInt() ?: "?"} °C · battery ${volt?.let { String.format("%.1f V", it / 10f) } ?: "?"} · " +
                "storage ${out.lines().firstOrNull { it.contains("/data") }?.trim()?.split(Regex("\\s+"))?.let { "${it.getOrNull(2)} used of ${it.getOrNull(1)}" } ?: "?"} · " +
                "up ${up?.let { "${it / 3600} h ${it % 3600 / 60} min" } ?: "?"}"
        }
    }
}
