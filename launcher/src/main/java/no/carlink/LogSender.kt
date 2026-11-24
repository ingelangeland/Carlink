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
        val events = Root.run("logcat -d -b events -v time -t 400 am_proc_start:I am_focused_activity:I am_set_resumed_activity:I am_kill:I *:S", 10).orEmpty()
        val text = buildString {
            append("CarLink log ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\n\n")
            append("## CarLink events\n").append(AppLog.dump()).append("\n\n")
            JoyMouseService.instance?.learn?.let { append("## Joystick learning\n").append(it.summary()).append("\n\n") }
            append("## Crashes (system)\n").append(crash.ifBlank { "none" }).append("\n\n")
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
