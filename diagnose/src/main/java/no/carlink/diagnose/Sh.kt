package no.carlink.diagnose

import java.util.concurrent.TimeUnit

/** Kommandoer med root («su -c sh» med skriptet på standard inn, slik bilskjermen vil ha det). */
object Sh {
    fun run(script: String, timeoutS: Long = 10): String = runCatching {
        val p = ProcessBuilder("su", "-c", "sh").redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().use { out.append(it.readText()) } } }.apply { start() }
        p.outputStream.bufferedWriter().use { it.write(script); it.write("\nexit\n") }
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroy(); return@runCatching out.toString() + "\n(timed out)" }
        reader.join(2000)
        out.toString()
    }.getOrDefault("(root not available)")

    /** Starter en root-prosess som skriver linjer fortløpende (f.eks. logcat). Stoppes med destroy(). */
    fun stream(script: String, onLine: (String) -> Unit): Process? = runCatching {
        val p = ProcessBuilder("su", "-c", "sh").redirectErrorStream(true).start()
        p.outputStream.write((script + "\n").toByteArray()); p.outputStream.flush()
        Thread({ runCatching { p.inputStream.bufferedReader().forEachLine(onLine) } }, "diag-stream").apply { isDaemon = true; start() }
        p
    }.getOrNull()
}

/** CarLinks egne filer (leses med root – diagnoseappen endrer dem aldri). */
object CarLinkData {
    const val PKG = "no.carlink.launcher"
    private const val DIR = "/data/data/$PKG"

    /** CarLinks innstillinger som navn → verdi (tekst). */
    fun prefs(): Map<String, String> {
        val xml = Sh.run("cat $DIR/shared_prefs/carlink.xml 2>/dev/null")
        val out = LinkedHashMap<String, String>()
        Regex("""<(string|int|long|boolean|float) name="([^"]+)"(?: value="([^"]*)")?\s*/?>(?:([^<]*)</string>)?""").findAll(xml).forEach { m ->
            out[m.groupValues[2]] = unescape(if (m.groupValues[1] == "string") m.groupValues[4] else m.groupValues[3])
        }
        return out
    }

    /** CarLinks egen logg (de siste ca. 1500 linjene). */
    fun log(): List<String> = Sh.run("cat $DIR/files/carlink-log.txt 2>/dev/null", 10).lines()

    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
