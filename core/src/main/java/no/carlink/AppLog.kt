package no.carlink

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CarLinks egen logg (tilkobling, lyd, oppstart, hakking, minne, feil og krasj).
 *
 * - Én fil per dag i files/logs (carlink-ÅÅÅÅ-MM-DD.txt), de siste 7 dagene og høyst 8 MB beholdes.
 * - Nye linjer legges til i fila etter høyst 3 sekunder (feil med en gang), så nesten ingenting
 *   går tapt når bilskjermen tvangsstopper CarLink ved ACC av. Bare de nye linjene skrives.
 * - Skrivingen skjer i en egen tråd med lav prioritet, så den aldri forsinker skjermen.
 * Sendes til Claude fra Settings → Updates → «Send log to Claude».
 */
object AppLog {
    private const val MEM_MAX = 1500
    private const val KEEP_DAYS = 7
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val FLUSH_MS = 3000L

    private val lines = ArrayDeque<String>(MEM_MAX)
    private val pending = ArrayList<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val fileLock = Any()
    @Volatile private var dir: File? = null
    @Volatile private var writer: Handler? = null
    private var flushQueued = false
    private var lastDay = ""

    fun init(ctx: Context) {
        if (dir != null) return
        synchronized(this) {
            if (dir != null) return
            val d = File(ctx.filesDir, "logs").apply { mkdirs() }
            // Den gamle loggfila (før én fil per dag) flyttes inn og ryddes bort når den blir gammel
            File(ctx.filesDir, "carlink-log.txt").let { if (it.exists()) it.renameTo(File(d, "carlink-0000-earlier.txt")) }
            val previous = lastLine(d)
            dir = d
            writer = Handler(HandlerThread("carlink-log", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
            val info = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
            @Suppress("DEPRECATION")
            val code = info?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
            val up = SystemClock.elapsedRealtime() / 1000
            i("app", "--- ${ctx.packageName} started · ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · " +
                "${info?.versionName ?: "?"} ($code) · ${if (up < 180) "right after the screen started (${up} s)" else "screen running ${up / 60} min"}" +
                (previous?.let { " · previous log entry ${it.take(18)}" } ?: ""))
            writer?.post { tidy(); exitReasons(ctx) }
            ctx.applicationContext.registerComponentCallbacks(memoryWatch)
            val before = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                runCatching { e("CRASH", "in thread ${t.name}", e); saveNow() }
                before?.uncaughtException(t, e)
            }
        }
    }

    fun i(tag: String, msg: String) = add("$tag: ${msg.replace(Regex("current_uid \\d+\\s*"), "")}", false)

    /** Noe som ikke er en feil, men som er tregere eller rarere enn det skal være. */
    fun w(tag: String, msg: String) = add("$tag WARN: $msg", false)

    fun e(tag: String, msg: String, t: Throwable? = null) =
        add("$tag ERROR: $msg" + (t?.let { "\n" + android.util.Log.getStackTraceString(it).lines().take(25).joinToString("\n") } ?: ""), true)

    private fun add(text: String, urgent: Boolean) {
        val line = synchronized(fmt) { fmt.format(Date()) } + " " + text
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MEM_MAX) lines.removeFirst()
        }
        val w = writer
        synchronized(pending) {
            pending.add(line)
            if (w == null) { while (pending.size > 3000) pending.removeAt(0); return }   // før init: vent
            if (flushQueued && !urgent) return
            flushQueued = true
        }
        if (urgent) w?.post(flushTask) else w?.postDelayed(flushTask, FLUSH_MS)
    }

    private val flushTask = Runnable { runCatching { saveNow() } }

    /** De siste ca. 1500 linjene (raskt, fra minnet). */
    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }

    /** Hele loggen for de siste dagene (fra filene), høyst [maxChars] tegn – de nyeste beholdes. */
    fun dumpAll(maxChars: Int): String {
        saveNow()
        val files = logFiles()
        val parts = ArrayList<String>()
        var left = maxChars.toLong()
        for (f in files.asReversed()) {
            if (left <= 0) break
            val text = tail(f, left)
            parts.add(0, text)
            left -= text.length
        }
        return parts.joinToString("\n").trim()
    }

    /** Skriver ventende linjer til dagens fil nå (kalles også ved krasj og før logg sendes). */
    fun saveNow() {
        val d = dir ?: return
        val batch = synchronized(pending) {
            flushQueued = false
            if (pending.isEmpty()) return
            ArrayList(pending).also { pending.clear() }
        }
        synchronized(fileLock) {
            val day = synchronized(dayFmt) { dayFmt.format(Date()) }
            FileOutputStream(File(d, "carlink-$day.txt"), true).bufferedWriter().use { w ->
                batch.forEach { w.write(it); w.write("\n") }
            }
            if (day != lastDay) { lastDay = day; tidy() }
        }
    }

    // ---------------------------------------------------------------- rydding og hjelpere

    private fun logFiles(): List<File> =
        dir?.listFiles { f -> f.name.startsWith("carlink-") && f.name.endsWith(".txt") }?.sortedBy { it.name }.orEmpty()

    /** Behold de siste 7 dagene og høyst 8 MB. */
    private fun tidy() = runCatching {
        val files = logFiles().toMutableList()
        val tooOld = System.currentTimeMillis() - (KEEP_DAYS + 1) * 86_400_000L
        while (files.isNotEmpty() && (files.size > KEEP_DAYS || files.sumOf { it.length() } > MAX_BYTES || files.first().lastModified() < tooOld)) {
            if (files.size == 1) break
            files.removeAt(0).delete()
        }
    }

    private fun lastLine(d: File): String? = runCatching {
        val f = d.listFiles { x -> x.name.startsWith("carlink-") }?.maxByOrNull { it.name } ?: return null
        tail(f, 400).lines().lastOrNull { it.isNotBlank() }
    }.getOrNull()

    /** De siste [max] tegnene av en fil uten å lese hele fila. */
    private fun tail(f: File, max: Long): String = runCatching {
        RandomAccessFile(f, "r").use { r ->
            val start = (r.length() - max).coerceAtLeast(0)
            r.seek(start)
            val b = ByteArray((r.length() - start).toInt()); r.readFully(b)
            String(b).let { if (start > 0) it.substringAfter('\n') else it }
        }
    }.getOrDefault("")

    /** Hvorfor forrige kjøring sluttet (bare Android 11+, dvs. telefonen). */
    private fun exitReasons(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val seenFile = File(dir, ".exit-seen")
            val seen = seenFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
            val list = ctx.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(ctx.packageName, 0, 5).filter { it.timestamp > seen }
            list.asReversed().forEach { x ->
                i("app", "previous run ended ${synchronized(fmt) { fmt.format(Date(x.timestamp)) }}: ${exitName(x.reason)}" +
                    (x.description?.let { " ($it)" } ?: "") + " · memory ${x.pss / 1024} MB")
            }
            list.maxOfOrNull { it.timestamp }?.let { seenFile.writeText(it.toString()) }
        }
    }

    private fun exitName(r: Int) = when (r) {
        1 -> "exited by itself"; 2 -> "killed by Android (signal)"; 3 -> "killed by Android to free memory"
        4 -> "crashed"; 5 -> "crashed (native)"; 6 -> "not responding (ANR)"; 7 -> "failed to start"
        8 -> "permission changed"; 9 -> "used too much CPU/memory"; 10 -> "stopped by the user"
        11 -> "dependency died"; 12 -> "killed by Android"; 13 -> "freezer"; 14 -> "package state changed"
        15 -> "package updated"; else -> "reason $r"
    }

    /** Android sier fra når minnet blir trangt: skriv det i loggen (høyst én gang per 10 min per nivå). */
    private val memoryWatch = object : ComponentCallbacks2 {
        private val last = HashMap<Int, Long>()
        override fun onTrimMemory(level: Int) {
            if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return   // vanlig: appen er bare skjult
            val now = SystemClock.elapsedRealtime()
            if (now - (last[level] ?: -600_000L) < 600_000) return
            last[level] = now
            val what = when {
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "very low (CarLink may be closed next)"
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "low, CarLink is in the background"
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "critical while CarLink is running"
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "low while CarLink is running"
                else -> "getting low"
            }
            w("memory", "Android says memory is $what (level $level) · ${Perf.memSummary()}")
        }
        override fun onLowMemory() = w("memory", "Android says memory is almost gone · ${Perf.memSummary()}")
        override fun onConfigurationChanged(c: Configuration) {}
    }
}
