package no.carlink

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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

    /** Når bilens lys får gi nattnivå: 0 = lav sol (under 10°), 1 = skumring (under 3°), 2 = alltid, 3 = aldri. */
    fun lightsRule(ctx: Context) = ctx.prefs().getInt("brightLightsSun", 0)

    private fun sunElevation(ctx: Context): Double {
        val p = ctx.prefs()
        return DayNight.sunElevation(p.getFloat("lastLat", 59.91f).toDouble(), p.getFloat("lastLon", 10.75f).toDouble(), System.currentTimeMillis())
    }

    fun isNight(ctx: Context): Boolean {
        val sun = sunElevation(ctx)
        if (sun < -3.0) return true                       // sola har gått ned
        if (!carLights(ctx)) return false
        return when (lightsRule(ctx)) {
            0 -> sun < 10.0                               // lav sol: ettermiddag, skumring, vinterdager
            1 -> sun < 3.0                                // skumring
            2 -> true
            else -> false
        }
    }

    /** Bilens lys-signal: bilskjermens egen innstilling (1 = lys på), eller Androids nattmodus-flagg. */
    fun carLights(ctx: Context): Boolean =
        runCatching { Settings.System.getInt(ctx.contentResolver, "SETTING_SCREEN_ILL", -1) }.getOrDefault(-1) == 1 ||
            DayNight.systemNight == true

    /**
     * Bilskjermen har sin egen nedre grense (140 av 255) og demper selv om natta. Mens automatisk
     * lysstyrke er på, senkes grensen og den egne dempingen slås av (verdiene huskes og settes tilbake).
     */
    fun takeOver(ctx: Context, on: Boolean) {
        val p = ctx.prefs()
        Thread {
            if (on) {
                if (!p.contains("vendorBrightMin")) {
                    val min = Root.run("settings get system SETTING_SCREEN_BRIGHTNESS_MIN")?.lines()?.lastOrNull { it.isNotBlank() }?.trim()
                    val dec = Root.run("settings get system SETTING_SCREEN_BRIGHTNESS_NIGHT_DECLINE")?.lines()?.lastOrNull { it.isNotBlank() }?.trim()
                    p.edit().putString("vendorBrightMin", min ?: "140").putString("vendorNightDecline", dec ?: "50").apply()
                }
                Root.run("settings put system SETTING_SCREEN_BRIGHTNESS_MIN 3\nsettings put system SETTING_SCREEN_BRIGHTNESS_NIGHT_DECLINE 0")
            } else if (p.contains("vendorBrightMin")) {
                Root.run("settings put system SETTING_SCREEN_BRIGHTNESS_MIN ${p.getString("vendorBrightMin", "140")}\n" +
                    "settings put system SETTING_SCREEN_BRIGHTNESS_NIGHT_DECLINE ${p.getString("vendorNightDecline", "50")}")
                p.edit().remove("vendorBrightMin").remove("vendorNightDecline").apply()
            }
        }.start()
    }

    /** Kalles hvert minutt og når bilens lys-signal endrer seg. */
    private var tookOver = false

    fun tick(ctx: Context) {
        if (!enabled(ctx) || paused || android.os.SystemClock.uptimeMillis() < previewUntil) return
        if (!tookOver) { tookOver = true; takeOver(ctx, true) }
        val night = isNight(ctx)
        if (night != lastNight) { AppLog.i("light", if (night) "night level" else "day level"); lastNight = night }
        setPercent(ctx, if (night) nightLevel(ctx) else dayLevel(ctx))
    }

    fun setPercent(ctx: Context, pct: Int) {
        ensurePermission(ctx)
        target = (pct.coerceIn(1, 100) * 255 / 100).coerceIn(3, 255)
        if (current < 0) {
            current = runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(target)
            // Første gang etter oppstart: bilskjermen kan ha satt skjermlyset selv (mørkt) uten å endre
            // Androids verdi. Skriv verdien på nytt (litt av og så på) så lyset faktisk settes.
            val v = current
            write(ctx, (v - 2).coerceAtLeast(1)); write(ctx, v)
            AppLog.i("light", "start: brightness $v → target $target")
        }
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

    /** Vis et valgt nivå i noen sekunder, så du ser hvordan det blir – så tilbake til riktig nivå. */
    fun preview(ctx: Context, pct: Int) {
        if (paused) return
        previewUntil = android.os.SystemClock.uptimeMillis() + 4000
        setPercent(ctx, pct)
        main.postDelayed({ previewUntil = 0; tick(ctx) }, 4100)
    }
    private var previewUntil = 0L

    /** Skjermen selv (bilskjermens programvare) endret lysstyrken: sett vår tilbake. */
    fun systemChanged(ctx: Context) {
        if (!enabled(ctx) || paused || target < 0) return
        val v = runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(target)
        if (abs(v - target) > 6 && current == target) {
            AppLog.i("light", "screen changed brightness to $v – setting it back to $target")
            current = v; step(ctx.applicationContext)
        }
    }

    fun status(ctx: Context): String {
        val night = isNight(ctx)
        val lights = carLights(ctx)
        val why = when {
            night && !lights -> "after sunset"
            night -> "car lights on, sun is low"
            lights && lightsRule(ctx) != 3 -> "car lights on, but the sun is still high (%.0f°)".format(sunElevation(ctx))
            else -> "daytime"
        }
        return "Now: ${if (night) "night" else "day"} level ($why)"
    }

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
