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

    /** Minutter etter solnedgang før det alltid er nattnivå (15/30/45/60). */
    fun nightAfter(ctx: Context) = ctx.prefs().getInt("brightNightAfter", 30).coerceAtLeast(30)
    /** Mellom solnedgang og nattgrensen: får bilens lys gi nattnivå? */
    fun lightsAfterSunset(ctx: Context) = ctx.prefs().getBoolean("brightLightsAfterSunset", true)

    private const val HORIZON = -0.833   // offisiell solnedgang (sola helt under horisonten)

    private fun elevation(ctx: Context, t: Long): Double {
        val p = ctx.prefs()
        return DayNight.sunElevation(p.getFloat("lastLat", 59.91f).toDouble(), p.getFloat("lastLon", 10.75f).toDouble(), t)
    }

    /** Minutter siden sola gikk ned, eller -1 hvis sola er oppe. */
    private fun minutesSinceSunset(ctx: Context, now: Long = System.currentTimeMillis()): Int {
        if (elevation(ctx, now) >= HORIZON) return -1
        var t = now
        for (k in 1..540) {                       // inntil 18 timer tilbake, 2 minutter av gangen
            t -= 120_000
            if (elevation(ctx, t) >= HORIZON) return k * 2
        }
        return 24 * 60                            // midtvinter uten soloppgang: alltid natt
    }

    /** Tidspunktet for dagens (eller siste) solnedgang, til visning. */
    private fun sunsetTime(ctx: Context): Long? {
        val now = System.currentTimeMillis()
        val since = minutesSinceSunset(ctx, now)
        if (since in 0..1000) return now - since * 60_000L
        var t = now
        for (k in 1..720) { t += 120_000; if (elevation(ctx, t) < HORIZON) return t }
        return null
    }

    fun isNight(ctx: Context): Boolean {
        val since = minutesSinceSunset(ctx)
        if (since < 0) return false                                   // sola er oppe: alltid dag
        if (since >= nightAfter(ctx)) return true                    // godt etter solnedgang: alltid natt
        return lightsAfterSunset(ctx) && carLights(ctx)              // skumring: bilens lys bestemmer
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

    // ---- Når bilens lys er på, setter bilskjermen selve skjermlyset rett i maskinvaren (forbi Android).
    //      Da skriver CarLink nivået dit selv (root), og passer på at det blir stående.
    private const val BACKLIGHT = "/sys/class/leds/lcd-backlight/brightness"
    @Volatile private var hwBusy = false
    private var lastHw = -1

    private fun writeHardware(ctx: Context, v: Int, why: String) {
        if (hwBusy) return
        hwBusy = true
        Thread {
            val cur = Root.run("cat $BACKLIGHT")?.lines()?.lastOrNull { it.isNotBlank() }?.trim()?.toIntOrNull()
            if (cur != null && kotlin.math.abs(cur - v) > 3) {
                Root.run("echo $v > $BACKLIGHT")
                if (lastHw != v || why != "watch") AppLog.i("light", "screen light set directly: $cur → $v ($why)")
            }
            lastHw = v
            hwBusy = false
        }.start()
    }

    /** Bilens lys slått av/på: bilskjermen setter sitt eget lys – vent litt og sett vårt. */
    fun carLightsChanged(ctx: Context) {
        tick(ctx)
        if (!enabled(ctx) || paused) return
        main.postDelayed({ if (target >= 0 && carLights(ctx)) writeHardware(ctx, target, "car lights") }, 700)
        main.postDelayed({ if (target >= 0 && carLights(ctx)) writeHardware(ctx, target, "car lights") }, 2500)
    }

    /** Hvert 10. sekund mens bilens lys er på: står lyset fortsatt der vi satte det? */
    fun watchHardware(ctx: Context) {
        if (!enabled(ctx) || paused || target < 0 || !carLights(ctx)) return
        writeHardware(ctx, target, "watch")
    }

    private fun step(ctx: Context) {
        if (current == target || target < 0) {
            if (target >= 0 && carLights(ctx) && enabled(ctx)) writeHardware(ctx, target, "level")
            return
        }
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
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val sunset = sunsetTime(ctx)
        val times = sunset?.let { " · sunset ${fmt.format(it)}, night from ${fmt.format(it + nightAfter(ctx) * 60_000L)}" } ?: ""
        val since = minutesSinceSunset(ctx)
        val why = when {
            since < 0 -> "sun is up"
            night && since >= nightAfter(ctx) -> "after sunset"
            night -> "dusk and car lights on"
            else -> "dusk"
        }
        return "Now: ${if (night) "night" else "day"} level ($why)$times"
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
