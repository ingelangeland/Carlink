package no.carlink

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tidslinje for én tilkobling: når hvert steg skjedde, regnet fra starten (bilen slått på / skjermen på).
 * Skrives i loggen («timeline: …») og oppsummeres når forbindelsen er oppe, så vi kan se hvilket
 * steg som er tregt. Koster ingenting – det noteres bare et klokkeslett når noe skjer.
 */
object ConnTimeline {
    private var t0 = 0L
    private var what = ""
    private val steps = ArrayList<Pair<String, Long>>()
    private var done = true
    /** Siste oppsummering, til visning i appen (f.eks. «14,3 s»). */
    val last = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    @Synchronized fun start(reason: String) {
        if (!done && SystemClock.elapsedRealtime() - t0 < 120_000) return   // allerede i gang
        t0 = SystemClock.elapsedRealtime(); what = reason; steps.clear(); done = false
        AppLog.i("timeline", "start: $reason")
    }

    @Synchronized fun mark(step: String) {
        if (done || steps.any { it.first == step }) return
        val t = SystemClock.elapsedRealtime() - t0
        if (t > 300_000) { done = true; return }                        // over 5 min: ikke en oppstart lenger
        steps += step to t
        AppLog.i("timeline", "${fmt(t)}  $step")
    }

    /** Forbindelsen er oppe: skriv oppsummeringen. */
    @Synchronized fun finish(step: String): JSONArray? {
        if (done) return null
        mark(step)
        done = true
        var prev = 0L
        val parts = steps.joinToString(" → ") { (n, t) -> val d = t - prev; prev = t; "$n +${fmt(d)}" }
        val total = steps.lastOrNull()?.second ?: 0
        AppLog.i("timeline", "SUMMARY $what → connected in ${fmt(total)}: $parts")
        last.value = fmt(total)
        return JSONArray().apply { steps.forEach { (n, t) -> put(JSONObject().put("s", n).put("ms", t)) } }
    }

    /** Skjermens tidslinje, mottatt på telefonen. */
    fun logRemote(start: String, arr: JSONArray) {
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }
        AppLog.i("timeline", "screen side ($start): " + list.joinToString(" → ") { "${it.optString("s")} ${fmt(it.optLong("ms"))}" })
    }

    fun startReason() = what

    private fun fmt(ms: Long) = String.format(java.util.Locale("nb", "NO"), "%.1f s", ms / 1000.0)
}
