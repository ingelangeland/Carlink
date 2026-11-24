package no.carlink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Systemressurser i app-veksleren: prosessor (med kurve), minne, temperatur, lagring og bilens
 * batterispenning. Måles BARE mens app-veksleren er åpen (én root-prosess som leser hvert sekund),
 * og stoppes når den lukkes – ingenting går i bakgrunnen.
 */
class ResourceMonitor(ctx: Context) : LinearLayout(ctx) {

    /** Minnebruk per app (MB), oppdateres hvert 3. sekund mens den er åpen. */
    var onAppMemory: ((Map<String, Int>) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var proc: Process? = null
    private val cpuTv = value(); private val memTv = value(); private val tempTv = value()
    private val diskTv = value(); private val voltTv = value()
    private val graph = Graph(ctx)
    private var lastIdle = -1L; private var lastTotal = -1L

    init {
        orientation = VERTICAL
        setPadding(22.dp, 18.dp, 22.dp, 18.dp)
        addView(ctx.label("System", 18f, 0xFFFFFFFF.toInt(), true).apply { gravity = Gravity.CENTER },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        row("Processor", cpuTv)
        addView(graph, LayoutParams(LayoutParams.MATCH_PARENT, 46.dp).apply { topMargin = 4.dp })
        row("Memory", memTv)
        row("Temperature", tempTv)
        row("Storage", diskTv)
        row("Car battery", voltTv)
    }

    private fun value() = context.label("–", 15f, 0xFFFFFFFF.toInt(), true).apply { gravity = Gravity.END }

    private fun row(name: String, v: TextView) {
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(context.label(name, 14f, 0xCCFFFFFF.toInt()), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(v, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 14.dp })
    }

    fun start() {
        if (proc != null) return
        lastIdle = -1; graph.clear()
        updateLocal()
        Thread({
            runCatching {
                val p = ProcessBuilder("su", "-c", "sh").redirectErrorStream(true).start()
                proc = p
                p.outputStream.write(("i=0; while true; do " +
                    "echo CPU; head -1 /proc/stat; " +
                    "echo MEM; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; " +
                    "echo TEMP; for z in /sys/class/thermal/thermal_zone*; do echo \"$(cat \$z/type 2>/dev/null) $(cat \$z/temp 2>/dev/null)\"; done; " +
                    "if [ \$((i % 3)) -eq 0 ]; then echo PS; ps -A -o RSS,NAME 2>/dev/null; fi; " +
                    "echo END; i=\$((i+1)); sleep 1; done\n").toByteArray())
                p.outputStream.flush()
                val block = ArrayList<String>()
                p.inputStream.bufferedReader().forEachLine { l ->
                    if (l == "END") { val b = ArrayList(block); block.clear(); main.post { parse(b) } } else block.add(l)
                }
            }
            proc = null
        }, "carlink-resources").apply { isDaemon = true; start() }
    }

    fun stop() {
        runCatching { proc?.destroy() }
        proc = null
    }

    private fun updateLocal() {
        runCatching {
            val s = StatFs(Environment.getDataDirectory().path)
            val total = s.totalBytes / 1e9; val free = s.availableBytes / 1e9
            diskTv.text = String.format("%.0f / %.0f GB", total - free, total)
            diskTv.setTextColor(if (free / total < 0.1) 0xFFFFC83D.toInt() else 0xFFFFFFFF.toInt())
        }
        val v = Hub.voltage.value
        if (v == null) { voltTv.text = "–"; voltTv.setTextColor(0xFFFFFFFF.toInt()) } else {
            voltTv.text = String.format(java.util.Locale("nb", "NO"), "%.1f V", v / 10f)
            voltTv.setTextColor(when { v < 118 || v > 150 -> 0xFFFF5A52.toInt(); v < 122 -> 0xFFFFC83D.toInt(); else -> 0xFFFFFFFF.toInt() })
        }
    }

    private fun parse(lines: List<String>) {
        if (proc == null) return
        var section = ""
        var memTotal = 0L; var memAvail = 0L
        val temps = ArrayList<Pair<String, Double>>()
        val apps = HashMap<String, Int>()
        for (l in lines) {
            when (l) { "CPU", "MEM", "TEMP", "PS" -> { section = l; continue } }
            when (section) {
                "CPU" -> if (l.startsWith("cpu ")) {
                    val n = l.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                    if (n.size >= 5) {
                        val idle = n[3] + n[4]; val total = n.sum()
                        if (lastIdle >= 0 && total > lastTotal) {
                            val pct = (100.0 * (1 - (idle - lastIdle).toDouble() / (total - lastTotal))).coerceIn(0.0, 100.0)
                            cpuTv.text = "${pct.toInt()} %"
                            cpuTv.setTextColor(if (pct > 85) 0xFFFFC83D.toInt() else 0xFFFFFFFF.toInt())
                            graph.add(pct.toFloat())
                        }
                        lastIdle = idle; lastTotal = total
                    }
                }
                "MEM" -> {
                    val kb = l.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0
                    if (l.startsWith("MemTotal")) memTotal = kb else memAvail = kb
                }
                "TEMP" -> {
                    val i = l.lastIndexOf(' ')
                    val raw = l.substring(i + 1).toDoubleOrNull()
                    if (i > 0 && raw != null) {
                        val c = if (raw > 1000) raw / 1000 else raw
                        if (c in 5.0..130.0) temps += l.substring(0, i).lowercase() to c
                    }
                }
                "PS" -> {
                    val f = l.trim().split(Regex("\\s+"))
                    val rss = f.getOrNull(0)?.toIntOrNull() ?: continue
                    val pkg = f.getOrNull(1)?.substringBefore(':') ?: continue
                    if ('.' in pkg) apps[pkg] = (apps[pkg] ?: 0) + rss / 1024
                }
            }
        }
        if (memTotal > 0) {
            val used = (memTotal - memAvail) / 1024.0 / 1024.0; val tot = memTotal / 1024.0 / 1024.0
            memTv.text = String.format("%.1f / %.1f GB", used, tot)
            memTv.setTextColor(if (memAvail < memTotal / 10) 0xFFFFC83D.toInt() else 0xFFFFFFFF.toInt())
        }
        // Prosessorens temperatur: soner med cpu/tsens i navnet, ellers den høyeste
        val cpuT = temps.filter { (n, _) -> "cpu" in n || "tsens" in n || "apc" in n }.maxOfOrNull { it.second } ?: temps.maxOfOrNull { it.second }
        if (cpuT != null) {
            tempTv.text = "${cpuT.toInt()} °C"
            tempTv.setTextColor(when { cpuT >= 85 -> 0xFFFF5A52.toInt(); cpuT >= 70 -> 0xFFFFC83D.toInt(); else -> 0xFFFFFFFF.toInt() })
        }
        if (apps.isNotEmpty()) onAppMemory?.invoke(apps)
        updateLocal()
    }

    /** Liten kurve over prosessorbruken de siste 40 sekundene. */
    private class Graph(ctx: Context) : View(ctx) {
        private val values = ArrayDeque<Float>()
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * ctx.resources.displayMetrics.density; color = 0xFFFFFFFF.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x33FFFFFF }
        fun clear() { values.clear(); invalidate() }
        fun add(v: Float) { values.addLast(v); while (values.size > 40) values.removeFirst(); invalidate() }
        override fun onDraw(c: Canvas) {
            if (values.size < 2) return
            line.color = Ambient.theme.value.accent
            val w = width.toFloat(); val h = height.toFloat(); val step = w / 39f
            val p = Path(); val f = Path()
            values.forEachIndexed { i, v ->
                val x = w - (values.size - 1 - i) * step; val y = h - v / 100f * (h - 4) - 2
                if (i == 0) { p.moveTo(x, y); f.moveTo(x, h); f.lineTo(x, y) } else { p.lineTo(x, y); f.lineTo(x, y) }
            }
            f.lineTo(w, h); f.close()
            c.drawPath(f, fill); c.drawPath(p, line)
        }
    }
}
