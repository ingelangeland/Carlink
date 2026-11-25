package no.carlink

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** «Send log to Claude»: CarLinks egen logg + det siste fra systemloggen, sendt via telefonen. */
object LogSender {
    fun send(ctx: Context): String {
        AppLog.i("app", "log sent by user")
        runCatching { AppLog.saveNow() }
        val crash = Root.run("logcat -d -b crash -v time", 10).orEmpty().takeLast(60_000)
        // «GM» (Bluetooth-lyd) skriver hundrevis av linjer i sekundet – skjul den, så loggen rekker lenger tilbake
        val sys = Root.run("logcat -d -v time -t 6000 GM:S chatty:S", 25)?.takeLast(500_000) ?: "(system log not available)"
        // Skjermlyset: bilskjermens innstillinger og selve lys-styringen i maskinvaren (for nattmodus)
        val display = Root.run("""
            echo "car lights (SETTING_SCREEN_ILL): ${'$'}(settings get system SETTING_SCREEN_ILL)"
            settings list system | grep -aiE "bright|ill|dim|night|backl|lcd"
            for f in /sys/class/leds/*/brightness /sys/class/leds/*/max_brightness /sys/class/backlight/*/brightness /sys/class/backlight/*/actual_brightness /sys/class/backlight/*/max_brightness; do [ -e "${'$'}f" ] && echo "${'$'}f = ${'$'}(cat ${'$'}f 2>/dev/null)"; done
            getprop | grep -iE "bright|backl|ill|dim|lcd"
        """.trimIndent(), 10).orEmpty()
        // «Skjerm av» i bilskjermens nedtrekksmeny: hvilke tjenester/kommandoer bilskjermen har for skjermlyset
        val services = Root.run("""
            echo "--- services (name: [interface])"
            service list 2>/dev/null | grep -aiE "spd|car|xh|bklight|backlight|light|power|screen|mcu|hal" | head -60
            echo "--- framework / system apps from the maker"
            ls /system/framework 2>/dev/null | grep -aiE "spd|car|xh"
            ls /system/app /system/priv-app 2>/dev/null | grep -aiE "spd|systemui|car" | head -40
            for n in ${'$'}(service list 2>/dev/null | grep -aiE "spd|bklight|backlight|mcu" | sed -E 's/^[0-9]+[[:space:]]+([^:]+):.*/\1/' | head -8); do
              echo "--- dumpsys ${'$'}n"; dumpsys "${'$'}n" 2>&1 | head -60
            done
            echo "--- broadcasts and components about screen light"
            dumpsys package 2>/dev/null | grep -aiE "bklight|backlight|screen_?off|screenoff|black_?screen|lcd_?off|closescreen" | sort -u | head -60
            echo "--- quick settings tiles"
            settings get secure sysui_qs_tiles
            dumpsys activity service com.android.systemui 2>/dev/null | grep -aiE "tile|bklight|backlight|screen" | head -40
            echo "--- recent screen light requests"
            logcat -d -v time 2>/dev/null | grep -aE "SpdService-BKLight|BlackWindow|BackLightSetBrightness|BkLight status" | tail -30
        """.trimIndent(), 25).orEmpty().takeLast(80_000)
        val events = Root.run("logcat -d -b events -v time -t 400 am_proc_start:I am_focused_activity:I am_set_resumed_activity:I am_kill:I *:S", 10).orEmpty()
        val text = buildString {
            append("CarLink log ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\n\n")
            append("## CarLink events\n").append(AppLog.dump()).append("\n\n")
            JoyMouseService.instance?.learn?.let { append("## Joystick learning\n").append(it.summary()).append("\n\n") }
            append("## Crashes (system)\n").append(crash.ifBlank { "none" }).append("\n\n")
            append("## Screen light\n").append(display).append("\n\n")
            append("## Car screen services (screen off)\n").append(services).append("\n\n")
            append("## App starts and switches\n").append(events.takeLast(60_000)).append("\n\n")
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
}
