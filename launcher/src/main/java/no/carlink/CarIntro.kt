package no.carlink

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Når bilen slås på: la bilens eget startbilde (Lexus-bildet i bilskjermens system) stå noen
 * sekunder før CarLinks hjemskjerm kommer. Musikken som startes opp igjen venter til etterpå.
 */
object CarIntro {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var checked = false
        private set
    val choices = intArrayOf(0, 2, 4, 6, 8)

    fun seconds(ctx: Context) = ctx.prefs().getInt("introSecs", 4)

    /** Kalles første gang CarLinks skjerm lages i en ny prosess. */
    fun maybeShow(ctx: Context) {
        if (checked) return
        checked = true
        val secs = seconds(ctx)
        if (secs <= 0 || Hub.parked.value) return
        // Bare når bilen nettopp ble slått på / skjermen startet: CarLink har ikke levd det siste minuttet.
        // (Ikke etter en oppdatering av appen mens du kjører.)
        val aliveAt = ctx.prefs().getLong("resumeAliveAt", 0)
        if (aliveAt > 0 && System.currentTimeMillis() - aliveAt < 60_000) {
            AppLog.i("app", "start screen skipped: CarLink was running ${(System.currentTimeMillis() - aliveAt) / 1000} s ago (app restart, not the car)")
            return
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(JoyMouseService.CAR_SYSTEM) ?: return
        val app = ctx.applicationContext
        Hub.introUntil = SystemClock.elapsedRealtime() + secs * 1000L + 1500
        AppLog.i("app", "car switched on – showing the car's start screen for $secs s")
        runCatching { app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        main.postDelayed({
            val front = JoyMouseService.instance?.frontPackage()
            // Har du selv gått videre (ryggekamera, en app), lar vi det være
            if (front == null || front == JoyMouseService.CAR_SYSTEM) {
                runCatching {
                    app.startActivity(Intent(app, ReceiverActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
        }, secs * 1000L)
    }
}
