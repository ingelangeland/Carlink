package no.carlink

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Målinger til loggen, så vi kan se hva som tar for lang tid og hva som hakker:
 *  - oppstart: hvor lang tid hvert steg tar fra Android starter CarLink til første bilde er på skjermen
 *  - frys: om skjermen står stille (hovedtråden opptatt) i over 0,7 s, med hva den holdt på med
 *  - hakking: bilder som tar for lang tid å tegne, oppsummert per minutt (bare når det skjer)
 *  - minne: CarLinks og systemets minne hvert 15. minutt mens skjermen er på
 * Alt er billig: tidsstempler, én liten sjekk annethvert sekund og Androids egne bilde-målinger.
 * Ingenting av dette holder skjermen våken.
 */
object Perf {
    // ---------------------------------------------------------------- oppstart

    private val marks = ArrayList<Pair<String, Long>>()
    private var startupDone = false

    /** Millisekunder siden Android startet CarLinks prosess. */
    fun sinceProcessStart(): Long =
        if (Build.VERSION.SDK_INT >= 24) SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() else -1

    @Synchronized fun startup(step: String) {
        if (startupDone || marks.any { it.first == step }) return
        val t = sinceProcessStart()
        marks += step to t
        AppLog.i("startup", "${sec(t)}  $step")
    }

    /** Første bilde er på skjermen: skriv en oppsummering av oppstarten. */
    @Synchronized fun startupFinished(step: String) {
        if (startupDone) return
        startup(step)
        startupDone = true
        var prev = 0L
        val parts = marks.joinToString(" → ") { (n, t) -> val d = t - prev; prev = t; "$n +${sec(d)}" }
        val up = SystemClock.elapsedRealtime() / 1000
        // Android starter ofte CarLink i bakgrunnen ~10 s etter ACC av; da vises skjermen først når bilen slås på
        val kind = when {
            marks.last().second > 120_000 -> "CarLink was already running in the background"
            up < 180 -> "screen started ${up} s ago"
            else -> "CarLink restarted"
        }
        AppLog.i("startup", "SUMMARY ($kind) → on screen in ${sec(marks.last().second)}: $parts")
    }

    /** Kjør [block] og skriv i loggen hvis det tok minst [warnMs]. */
    inline fun <T> timed(tag: String, what: String, warnMs: Long, block: () -> T): T {
        val t0 = SystemClock.uptimeMillis()
        try { return block() } finally {
            val d = SystemClock.uptimeMillis() - t0
            if (d >= warnMs) AppLog.w(tag, "slow: $what took ${d} ms")
        }
    }

    // ---------------------------------------------------------------- frys og minne

    /** Settes når skjermen slås av/på: ingen sjekker mens den er av. */
    @Volatile var paused = false
    /** Hva som vises nå (for loggen), settes av bilskjermens hovedvindu. */
    @Volatile var where: () -> String = { "" }
    @Volatile private var watching = false

    fun startWatchdog() {
        if (watching) return
        watching = true
        Thread({ watchLoop() }, "carlink-watchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    private fun watchLoop() {
        val main = Handler(Looper.getMainLooper())
        var reports = 0; var hourStart = SystemClock.uptimeMillis()
        var memAt = SystemClock.uptimeMillis() - 14 * 60_000L   // første minnemåling etter ca. 1 min
        while (true) {
            runCatching {
                Thread.sleep(2000)
                if (paused) return@runCatching
                val now = SystemClock.uptimeMillis()
                if (now - hourStart > 3_600_000) { hourStart = now; reports = 0 }
                if (now - memAt >= 15 * 60_000L) { memAt = now; AppLog.i("memory", memSummary()) }
                val posted = SystemClock.uptimeMillis()
                val ran = AtomicLong(0)
                main.post { ran.set(SystemClock.uptimeMillis()) }
                Thread.sleep(700)
                if (ran.get() != 0L) return@runCatching
                // Hovedtråden har ikke svart på 0,7 s: noter hva den holder på med, og vent til den svarer
                val stack = Looper.getMainLooper().thread.stackTrace.take(14)
                val label = runCatching { where() }.getOrDefault("")
                var waited = 0
                while (ran.get() == 0L && waited < 30_000) { Thread.sleep(50); waited += 50 }
                val blocked = (if (ran.get() != 0L) ran.get() else SystemClock.uptimeMillis()) - posted
                if (reports++ < 20) {
                    AppLog.w("perf", "screen froze for ${blocked} ms${if (label.isNotBlank()) " (showing: $label)" else ""}" +
                        (if (ran.get() == 0L) " – still frozen after 30 s" else "") +
                        "\n" + stack.joinToString("\n") { "    at $it" })
                }
            }
        }
    }

    /** «app 85 MB (java 22 of 192, native 30) · free 640 of 1850 MB» */
    fun memSummary(): String {
        val rt = Runtime.getRuntime()
        val java = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        val native = android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576
        val rss = runCatching {
            File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()?.div(1024)
        }.getOrNull()
        var total = 0L; var avail = 0L
        runCatching {
            File("/proc/meminfo").useLines { seq ->
                seq.take(5).forEach { l ->
                    val kb = l.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0
                    if (l.startsWith("MemTotal:")) total = kb else if (l.startsWith("MemAvailable:")) avail = kb
                }
            }
        }
        return "app ${rss ?: "?"} MB (java $java of ${rt.maxMemory() / 1_048_576}, native $native)" +
            (if (total > 0) " · free system memory ${avail / 1024} of ${total / 1024} MB" else "")
    }

    // ---------------------------------------------------------------- hakking

    private var frameThread: Handler? = null

    /**
     * Tellinger av bilder som tegnes for sakte i [activity]. Androids egne målinger brukes (ingen ekstra
     * tegning). Skrives i loggen høyst én gang per minutt, og bare hvis noe faktisk hakket.
     */
    fun watchFrames(activity: Activity) {
        if (Build.VERSION.SDK_INT < 24) return
        val h = frameThread ?: Handler(HandlerThread("carlink-frames", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
            .also { frameThread = it }
        val stats = FrameStats()
        runCatching {
            activity.window.addOnFrameMetricsAvailableListener(Window.OnFrameMetricsAvailableListener { _, m, _ ->
                if (Build.VERSION.SDK_INT >= 26 && m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
                stats.add(m.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000)
            }, h)
        }
    }

    private class FrameStats {
        var start = SystemClock.uptimeMillis(); var total = 0; var slow = 0; var freeze = 0
        var worst = 0L; var worstWhere = ""

        fun add(ms: Long) {
            val now = SystemClock.uptimeMillis()
            if (now - start > 60_000) report(now)
            total++
            if (ms > 34) slow++
            if (ms > 200) freeze++
            if (ms > worst) { worst = ms; worstWhere = runCatching { where() }.getOrDefault("") }
        }

        private fun report(now: Long) {
            if (slow >= 5 || freeze > 0) AppLog.w("perf", "jerky screen: $slow of $total pictures slow (>34 ms), $freeze over 200 ms, " +
                "worst $worst ms${if (worstWhere.isNotBlank()) " (showing: $worstWhere)" else ""}")
            start = now; total = 0; slow = 0; freeze = 0; worst = 0; worstWhere = ""
        }
    }

    private fun sec(ms: Long) = String.format(java.util.Locale("nb", "NO"), "%.2f s", ms / 1000.0)
}
