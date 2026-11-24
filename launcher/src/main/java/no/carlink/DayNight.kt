package no.carlink

import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dag/natt for kartet.
 *  Auto: følger bilens lyssignal hvis bilenheten har det (Android-nattmodus som skifter med
 *        frontlysene), ellers solen der du er (regnes ut lokalt, uten internett).
 */
object DayNight {
    enum class Mode(val label: String) { AUTO("Auto"), DAY("Day"), NIGHT("Night") }

    val night = MutableStateFlow(true)
    /** Natt-signal fra bilen (Android sitt nattmodus-flagg, som skjermen setter når lyset slås på). */
    var systemNight: Boolean? = null; private set

    fun mode(ctx: Context): Mode =
        runCatching { Mode.valueOf(ctx.prefs().getString("mapMode", "AUTO")!!) }.getOrDefault(Mode.AUTO)

    fun setMode(ctx: Context, m: Mode) { ctx.prefs().edit().putString("mapMode", m.name).apply(); update(ctx) }

    /** Kalles ved oppstart og når Android bytter dag/natt. Et bytte mens appen kjører = bilen har lyssignal. */
    fun onSystem(ctx: Context, cfg: Configuration) {
        val n = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val prev = systemNight
        systemNight = n
        if (prev != null && prev != n) ctx.prefs().edit().putBoolean("carNightSignal", true).apply()
        update(ctx)
    }

    fun update(ctx: Context) {
        val p = ctx.prefs()
        Hub.remoteLoc.value?.let { p.edit().putFloat("lastLat", it.lat.toFloat()).putFloat("lastLon", it.lon.toFloat()).apply() }
        night.value = when (mode(ctx)) {
            Mode.DAY -> false
            Mode.NIGHT -> true
            Mode.AUTO -> {
                val sys = systemNight
                if (sys != null && p.getBoolean("carNightSignal", false)) sys
                else sunElevation(p.getFloat("lastLat", 59.91f).toDouble(), p.getFloat("lastLon", 10.75f).toDouble(),
                    System.currentTimeMillis()) < -3.0   // litt etter solnedgang / før soloppgang
            }
        }
    }

    /** Solens høyde over horisonten i grader (forenklet astronomisk formel, nøyaktig nok til dag/natt). */
    fun sunElevation(lat: Double, lon: Double, timeMs: Long): Double {
        val d = timeMs / 86_400_000.0 + 2440587.5 - 2451545.0
        val rad = Math.PI / 180
        val g = (357.529 + 0.98560028 * d) * rad
        val q = 280.459 + 0.98564736 * d
        val l = (q + 1.915 * sin(g) + 0.020 * sin(2 * g)) * rad
        val e = (23.439 - 0.00000036 * d) * rad
        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        val gmst = (18.697374558 + 24.06570982441908 * d) % 24
        val ha = (gmst * 15 + lon) * rad - ra
        return asin(sin(lat * rad) * sin(dec) + cos(lat * rad) * cos(dec) * cos(ha)) / rad
    }
}
