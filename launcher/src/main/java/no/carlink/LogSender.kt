package no.carlink

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Send log to Claude»: CarLinks egen logg for de siste dagene + det siste fra systemloggen, sendt via
 * telefonen (som legger til sin egen logg før du deler den).
 */
object LogSender {
    /** Systemlogg-merker som skjules fordi de skriver så mye at loggen ellers bare rekker noen sekunder tilbake. */
    private const val QUIET = "GM:S chatty:S"

    fun send(ctx: Context): String {
        AppLog.i("app", "log sent by user")
        val crash = Root.run("logcat -d -b crash -v time", 10).orEmpty().takeLast(60_000)
        val sys = Root.run("logcat -d -v time -t 20000 $QUIET", 25)?.takeLast(1_500_000) ?: "(system log not available)"
        // Skjermlyset: bilskjermens innstillinger og selve lys-styringen i maskinvaren (for nattmodus)
        val display = Root.run("""
            echo "car lights (SETTING_SCREEN_ILL): ${'$'}(settings get system SETTING_SCREEN_ILL)"
            settings list system | grep -aiE "bright|ill|dim|night|backl|lcd"
            for f in /sys/class/leds/*/brightness /sys/class/leds/*/max_brightness /sys/class/backlight/*/brightness /sys/class/backlight/*/actual_brightness /sys/class/backlight/*/max_brightness; do [ -e "${'$'}f" ] && echo "${'$'}f = ${'$'}(cat ${'$'}f 2>/dev/null)"; done
            getprop | grep -iE "bright|backl|ill|dim|lcd"
        """.trimIndent(), 10).orEmpty()
        val events = Root.run("logcat -d -b events -v time -t 400 am_proc_start:I am_focused_activity:I am_set_resumed_activity:I am_kill:I am_low_memory:I am_anr:I am_crash:I *:S", 10).orEmpty()
        // Minne akkurat nå: hele skjermen og de 15 største appene
        val memory = Root.run("""
            head -4 /proc/meminfo
            echo; echo "biggest apps (MB):"
            ps -A -o RSS,NAME 2>/dev/null | sort -rn | head -15 | while read r n; do echo "  ${'$'}((r / 1024))  ${'$'}n"; done
            echo; echo "system log buffer:"; logcat -g 2>/dev/null
        """.trimIndent(), 10).orEmpty()
        val text = buildString {
            append("CarLink log ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\n\n")
            append("## CarLink events (last days)\n").append(AppLog.dumpAll(2_500_000)).append("\n\n")
            JoyMouseService.instance?.learn?.let { append("## Joystick learning\n").append(it.summary()).append("\n\n") }
            append("## Memory now\n").append("CarLink: ${Perf.memSummary()}\n").append(memory).append("\n\n")
            append("## Crashes (system)\n").append(crash.ifBlank { "none" }).append("\n\n")
            append("## Screen light\n").append(display).append("\n\n")
            append("## App starts, switches, kills\n").append(events.takeLast(60_000)).append("\n\n")
            append("## System log: busiest tags (candidates to hide)\n").append(busiestTags(sys)).append("\n\n")
            append("## System log (last few minutes)\n").append(sys)
        }
        val name = "CarLink-log-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".txt"
        val sent = Hub.send(JSONObject().put("t", "joylog").put("name", name).put("text", text))
        val saved = SystemReport.save(ctx, name, text)
        return when {
            sent -> "Log sent to the phone – tap the notification there to share it"
            saved -> "Phone not connected – log saved in Downloads: $name"
            else -> "Phone not connected and the log could not be saved"
        }
    }

    /** Hvilke merker som fyller systemloggen mest, og hvor mange sekunder loggen dekker. */
    private fun busiestTags(sys: String): String {
        val re = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d)\.\d+ [VDIWEF]/([^(:]+)""")
        val counts = HashMap<String, Int>()
        var first: String? = null; var last: String? = null; var n = 0
        sys.lineSequence().forEach { l ->
            val m = re.find(l) ?: return@forEach
            if (first == null) first = m.groupValues[1]
            last = m.groupValues[1]; n++
            val tag = m.groupValues[2].trim()
            counts[tag] = (counts[tag] ?: 0) + 1
        }
        if (n == 0) return "(no lines)"
        return "covers $first – $last, $n lines\n" +
            counts.entries.sortedByDescending { it.value }.take(15).joinToString("\n") { "  ${it.value * 100 / n} %  ${it.key} (${it.value})" }
    }
}
