package no.carlink

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lett logg over det viktigste som skjer i CarLink (tilkobling, lyd, oppdateringer, feil og krasj).
 * Bare de siste 1500 linjene beholdes (ca. 150 kB), og fila skrives høyst én gang i minuttet.
 * Sendes til Claude fra Settings → Updates → «Send log to Claude».
 */
object AppLog {
    private const val MAX = 1500
    private val lines = ArrayDeque<String>(MAX)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null
    private var dirty = false
    private var savedAt = 0L
    @Volatile private var saving = false

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "carlink-log.txt")
        file = f
        runCatching { if (f.exists()) f.readLines().takeLast(MAX).forEach { lines.addLast(it) } }
        i("app", "--- ${ctx.packageName} started · ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · " +
            runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("?"))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { e("CRASH", "in thread ${t.name}", e); saveNow() }
            previous?.uncaughtException(t, e)
        }
    }

    fun i(tag: String, msg: String) = add("$tag: ${msg.replace(Regex("current_uid \\d+\\s*"), "")}")

    fun e(tag: String, msg: String, t: Throwable? = null) =
        add("$tag ERROR: $msg" + (t?.let { "\n" + android.util.Log.getStackTraceString(it).lines().take(25).joinToString("\n") } ?: ""))

    private fun add(text: String) {
        val line = synchronized(fmt) { fmt.format(Date()) } + " " + text
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
            dirty = true
        }
        if (SystemClock.elapsedRealtime() - savedAt > 60_000) saveLater()
    }

    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }

    private fun saveLater() {
        if (saving) return
        saving = true; savedAt = SystemClock.elapsedRealtime()
        Thread { runCatching { saveNow() }; saving = false }.start()
    }

    fun saveNow() {
        val f = file ?: return
        val text = synchronized(lines) { if (!dirty) return; dirty = false; lines.joinToString("\n") }
        f.writeText(text)
    }
}
