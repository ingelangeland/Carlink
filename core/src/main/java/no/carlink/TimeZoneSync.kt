package no.carlink

import android.content.Context
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Setter bilskjermens tidssone lik telefonens. Krever root (Android lar ikke vanlige apper
 * endre tidssonen). Gjøres bare når den faktisk er forskjellig.
 */
object TimeZoneSync {
    fun apply(ctx: Context, zone: String) {
        if (!ctx.prefs().getBoolean("tzFromPhone", true)) return
        if (TimeZone.getDefault().id == zone || zone !in TimeZone.getAvailableIDs()) return
        AppLog.i("time", "setting time zone to $zone (was ${TimeZone.getDefault().id})")
        Thread {
            runCatching {
                val p = ProcessBuilder("su", "-c", "sh").redirectErrorStream(true).start()
                p.outputStream.bufferedWriter().use {
                    // IAlarmManager.setTimeZone (Android 9: kode 3) + egenskapen som leses ved oppstart
                    it.write("service call alarm 3 s16 $zone\nsetprop persist.sys.timezone $zone\nsettings put global auto_time_zone 0\nexit\n")
                }
                p.waitFor(8, TimeUnit.SECONDS)
            }
        }.start()
    }
}
