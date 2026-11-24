package no.carlink

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.util.Calendar
import kotlin.math.abs

/**
 * Lysstyrke på bilskjermen med to settpunkter (dag/natt, valgt under Settings → Screen).
 * Natt når sola har gått ned, eller når bilen sier fra at lyset er på OG klokka er etter et
 * valgt tidspunkt (i Norge er lyset alltid på, så signalet alene ville gitt natt midt på dagen).
 * Endringer glir mykt over ca. 2 sekunder.
 */
object Brightness {
    private val main = Handler(Looper.getMainLooper())
    private var current = -1
    private var target = -1
    @Volatile private var granted = false
    @Volatile private var paused = false   // skjermen er «av» – ikke rør lyset
    private var lastNight: Boolean? = null

    fun enabled(ctx: Context) = ctx.prefs().getBoolean("brightAuto", false)
    fun dayLevel(ctx: Context) = ctx.prefs().getInt("brightDay", 90)
    fun nightLevel(ctx: Context) = ctx.prefs().getInt("brightNight", 15)
    /** Minutter etter midnatt; -1 = bilens signal brukes ikke. */
    fun lightsAfter(ctx: Context) = ctx.prefs().getInt("brightLightsAfter", 15 * 60)

    fun isNight(ctx: Context): Boolean {
        val p = ctx.prefs()
        val sunDown = DayNight.sunElevation(p.getFloat("lastLat", 59.91f).toDouble(), p.getFloat("lastLon", 10.75f).toDouble(),
            System.currentTimeMillis()) < -3.0
        if (sunDown) return true
        val after = lightsAfter(ctx)
        if (after < 0 || DayNight.systemNight != true) return false
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) >= after
    }

    /** Kalles hvert minutt og når bilens lys-signal endrer seg. */
    fun tick(ctx: Context) {
        if (!enabled(ctx) || paused) return
        val night = isNight(ctx)
        if (night != lastNight) { AppLog.i("light", if (night) "night level" else "day level"); lastNight = night }
        setPercent(ctx, if (night) nightLevel(ctx) else dayLevel(ctx))
    }

    fun setPercent(ctx: Context, pct: Int) {
        ensurePermission(ctx)
        target = (pct.coerceIn(1, 100) * 255 / 100).coerceIn(3, 255)
        if (current < 0) current = runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(target)
        main.removeCallbacksAndMessages(this)
        step(ctx.applicationContext)
    }

    private fun step(ctx: Context) {
        if (current == target || target < 0) return
        val d = target - current
        current += if (abs(d) <= 4) d else d / 6 + (if (d > 0) 1 else -1)
        write(ctx, current)
        main.postAtTime({ step(ctx) }, this, android.os.SystemClock.uptimeMillis() + 80)
    }

    private fun write(ctx: Context, v: Int) {
        runCatching {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
        }.onFailure { granted = false }
    }

    private fun ensurePermission(ctx: Context) {
        if (granted || Settings.System.canWrite(ctx)) { granted = true; return }
        granted = true
        Thread { Root.run("appops set ${ctx.packageName} WRITE_SETTINGS allow") }.start()
    }

    // ---------------------------------------------------------------- «skjerm av»

    private var before = -1

    /** Skjermen helt mørk (lyset av), musikken spiller videre. */
    fun blackout(ctx: Context, on: Boolean) {
        if (on) {
            paused = true
            ensurePermission(ctx)
            before = runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(200)
            main.removeCallbacksAndMessages(this)
            current = 0; target = 0; write(ctx, 0)
        } else {
            paused = false
            current = 0
            if (enabled(ctx)) { lastNight = null; tick(ctx) }
            else { target = if (before > 0) before else 200; step(ctx.applicationContext) }
        }
    }
}
