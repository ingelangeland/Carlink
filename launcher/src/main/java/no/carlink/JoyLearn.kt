package no.carlink

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Joysticken lærer av hvordan du bruker den. Bare noen få tall, lagret på skjermen:
 *  - rekkevidden til stikka (hvor kantene faktisk er)
 *  - hvor rolig hånden/bilen er (styrer hvor mye bevegelsen glattes ut)
 *  - hvor mye stikka glipper når du trykker (styrer grensen mellom klikk og rulling)
 *  - bom per app (klikk rett ved siden av, eller Tilbake rett etter) → litt sterkere magnet der
 * Alt justeres i små steg innenfor faste grenser, og kan nullstilles under Settings → Joystick.
 */
class JoyLearn(private val ctx: Context) {
    var loX = 10f; private set
    var hiX = 245f; private set
    var loY = 10f; private set
    var hiY = 245f; private set
    private var noise = 1.5f
    private var slip = 25f
    private val boost = HashMap<String, Float>()
    var taps = 0; private set
    var misses = 0; private set
    private var dirty = false
    private var savedAt = 0L
    private var lastRawX = -1
    private var lastRawY = -1

    init { load() }

    fun enabled() = ctx.prefs().getBoolean("joyLearn", true)

    // ---------------------------------------------------------------- rekkevidde og ro

    fun observe(x: Int, y: Int) {
        if (!enabled()) return
        // Kantene utvides raskt når stikka går lenger ut enn før (innenfor rimelige grenser)
        if (x < loX) { loX = max(0f, loX - (loX - x) * 0.5f).coerceAtMost(40f); dirty = true }
        if (x > hiX) { hiX = min(255f, hiX + (x - hiX) * 0.5f).coerceAtLeast(215f); dirty = true }
        if (y < loY) { loY = max(0f, loY - (loY - y) * 0.5f).coerceAtMost(40f); dirty = true }
        if (y > hiY) { hiY = min(255f, hiY + (y - hiY) * 0.5f).coerceAtLeast(215f); dirty = true }
        // Små utslag når stikka nesten står i ro = skjelving (vei, hånd)
        if (lastRawX >= 0) {
            val d = abs(x - lastRawX) + abs(y - lastRawY)
            if (d <= 4) { noise = noise * 0.98f + d * 0.02f; dirty = true }
        }
        lastRawX = x; lastRawY = y
        maybeSave()
    }

    /** 0–1 langs hver akse, med en liten margin så kantene er lette å nå. */
    fun mapX(v: Int): Float = if (!enabled()) ((v - 10) / 235f).coerceIn(0f, 1f)
        else ((v - (loX + 3)) / ((hiX - 3) - (loX + 3))).coerceIn(0f, 1f)
    fun mapY(v: Int): Float = if (!enabled()) ((v - 10) / 235f).coerceIn(0f, 1f)
        else ((v - (loY + 3)) / ((hiY - 3) - (loY + 3))).coerceIn(0f, 1f)

    /** Hvor fort prikken følger (mer skjelving → mer utglatting). */
    fun follow(): Float = if (!enabled()) 0.45f else (0.55f - (noise - 1f) * 0.08f).coerceIn(0.28f, 0.55f)

    // ---------------------------------------------------------------- trykk

    fun pressSlip(px: Float) {
        if (!enabled() || px > 200f) return
        slip = slip * 0.9f + px * 0.1f; dirty = true
    }

    /** Hvor langt du må dra mens du holder inne før det blir rulling (skjermpunkter). */
    fun dragThreshold(): Float = if (!enabled()) 70f else (slip * 1.6f + 40f).coerceIn(60f, 140f)

    // ---------------------------------------------------------------- bom per app

    fun boost(pkg: String?): Float = if (!enabled() || pkg == null) 0f else boost[pkg] ?: 0f

    fun tapResult(pkg: String?, miss: Boolean) {
        if (!enabled() || pkg == null) return
        taps++
        val b = boost[pkg] ?: 0f
        boost[pkg] = if (miss) { misses++; (b + 0.04f).coerceAtMost(0.35f) } else (b - 0.004f).coerceAtLeast(0f)
        if (boost.size > 40) boost.entries.minByOrNull { it.value }?.let { boost.remove(it.key) }
        dirty = true; maybeSave()
    }

    // ---------------------------------------------------------------- lagring

    private fun maybeSave() {
        val now = SystemClock.elapsedRealtime()
        if (dirty && now - savedAt > 30_000) save()
    }

    fun save() {
        savedAt = SystemClock.elapsedRealtime(); dirty = false
        val b = JSONObject(); boost.forEach { (k, v) -> b.put(k, v.toDouble()) }
        val o = JSONObject().put("loX", loX.toDouble()).put("hiX", hiX.toDouble()).put("loY", loY.toDouble()).put("hiY", hiY.toDouble())
            .put("noise", noise.toDouble()).put("slip", slip.toDouble()).put("taps", taps).put("misses", misses).put("boost", b)
        ctx.prefs().edit().putString("joyLearnData", o.toString()).apply()
    }

    private fun load() {
        val s = ctx.prefs().getString("joyLearnData", null) ?: return
        runCatching {
            val o = JSONObject(s)
            loX = o.optDouble("loX", 10.0).toFloat(); hiX = o.optDouble("hiX", 245.0).toFloat()
            loY = o.optDouble("loY", 10.0).toFloat(); hiY = o.optDouble("hiY", 245.0).toFloat()
            noise = o.optDouble("noise", 1.5).toFloat(); slip = o.optDouble("slip", 25.0).toFloat()
            taps = o.optInt("taps"); misses = o.optInt("misses")
            o.optJSONObject("boost")?.let { b -> b.keys().forEach { k -> boost[k] = b.optDouble(k).toFloat() } }
        }
    }

    fun reset() {
        loX = 10f; hiX = 245f; loY = 10f; hiY = 245f; noise = 1.5f; slip = 25f
        boost.clear(); taps = 0; misses = 0
        ctx.prefs().edit().remove("joyLearnData").apply()
    }

    fun summary(): String {
        val steady = when { noise < 1.2f -> "very steady"; noise < 2.2f -> "steady"; else -> "shaky" }
        val apps = boost.filterValues { it > 0.05f }.size
        return "Range ${loX.toInt()}–${hiX.toInt()} × ${loY.toInt()}–${hiY.toInt()} · $steady · press slip ${slip.toInt()} px · " +
            "$taps taps, $misses misses" + if (apps > 0) " · stronger magnet in $apps app(s)" else ""
    }
}
