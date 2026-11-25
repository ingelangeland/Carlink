package no.carlink.diagnose

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hva CarLinks «Learn from my use» har lært om joysticken, forklart enkelt, og hvordan det har
 * utviklet seg: hver gang diagnoseappen åpnes, tas et øyeblikksbilde (lagret her, ikke i CarLink).
 */
class Learning(ctx: Context) : Section(ctx, "Mouse learning") {

    data class Snap(val at: Long, val taps: Int, val misses: Int, val noise: Double, val slip: Double)
    data class D(val enabled: Boolean, val o: JSONObject?, val hist: List<Snap>, val labels: Map<String, String>)

    override fun collect(): Any {
        val p = CarLinkData.prefs()
        val o = p["joyLearnData"]?.let { runCatching { JSONObject(it) }.getOrNull() }
        val hist = history()
        val h2 = if (o != null && (hist.isEmpty() || hist.last().taps != o.optInt("taps"))) {
            val s = Snap(System.currentTimeMillis(), o.optInt("taps"), o.optInt("misses"), o.optDouble("noise", 1.5), o.optDouble("slip", 25.0))
            (hist + s).takeLast(60).also { saveHistory(it) }
        } else hist
        val labels = HashMap<String, String>()
        o?.optJSONObject("boost")?.keys()?.forEach { k ->
            labels[k] = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(k, 0)).toString() }.getOrDefault(k)
        }
        return D(p["joyLearn"] != "false", o, h2, labels)
    }

    private fun history(): List<Snap> = runCatching {
        val a = JSONArray(ctx.getSharedPreferences("diag", 0).getString("learnHist", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { s -> Snap(s.getLong("at"), s.getInt("t"), s.getInt("m"), s.getDouble("n"), s.getDouble("s")) } }
    }.getOrDefault(emptyList())

    private fun saveHistory(l: List<Snap>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("at", it.at).put("t", it.taps).put("m", it.misses).put("n", it.noise).put("s", it.slip)) }
        ctx.getSharedPreferences("diag", 0).edit().putString("learnHist", a.toString()).apply()
    }

    private fun steadiness(n: Double) = when { n < 1.2 -> "very steady"; n < 2.2 -> "steady"; else -> "shaky (more smoothing)" }

    /** Bom-andel mellom to øyeblikksbilder (trenden). */
    private fun trend(h: List<Snap>): List<String> = h.zipWithNext().mapNotNull { (a, b) ->
        val t = b.taps - a.taps; val m = b.misses - a.misses
        if (t < 5) null else "${SimpleDateFormat("dd.MM", Locale.US).format(Date(b.at))}: ${m * 100 / t} % misses in $t clicks"
    }

    override fun render(d: Any) {
        d as D
        val o = d.o
        if (!d.enabled) cardOf("Learning is OFF", "Turn it on in CarLink: Settings → Joystick → Learning." to C.WARN)
        if (o == null) { cardOf("Nothing learned yet", "CarLink hasn't saved anything about the joystick yet. Use the joystick for a while." to C.DIM); return }
        val c = ctx.card()
        c.add(ctx.label("What CarLink has learned", 18f, C.TEXT, true))
        val taps = o.optInt("taps"); val misses = o.optInt("misses")
        kv(c, "Clicks seen", "$taps")
        kv(c, "Misses (click beside a button, or Back right after)", if (taps > 0) "$misses (${misses * 100 / taps} %)" else "–",
            if (taps > 20 && misses * 100 / taps > 20) C.WARN else C.TEXT)
        kv(c, "Hand/road steadiness", steadiness(o.optDouble("noise", 1.5)))
        kv(c, "Stick slips when you press", "${o.optDouble("slip", 25.0).toInt()} px")
        kv(c, "Stick range (sideways × up/down)", "${o.optDouble("loX").toInt()}–${o.optDouble("hiX").toInt()} × ${o.optDouble("loY").toInt()}–${o.optDouble("hiY").toInt()}")
        body.add(c, top = 14.dp)
        val b = o.optJSONObject("boost")
        val apps = b?.keys()?.asSequence()?.map { it to b.optDouble(it) }?.filter { it.second > 0.01 }?.sortedByDescending { it.second }?.toList().orEmpty()
        lines("Stronger magnet in these apps (you have missed there)",
            apps.map { (k, v) -> "${d.labels[k] ?: k}: +${(v * 100).toInt()} % pull" + if (v > 0.15) " – misses often here" else "" }, "None – no app needs extra help")
        val tr = trend(d.hist)
        lines("Over time (each time Diagnose was opened)", tr.takeLast(10).reversed(), "Open Diagnose again later to see the trend")
        if (tr.size >= 3) {
            val nums = tr.mapNotNull { Regex("(\\d+) %").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val first = nums.take(nums.size / 2).average(); val last = nums.drop(nums.size / 2).average()
            cardOf("Trend", (when { last < first - 2 -> "Getting better: fewer misses than before"; last > first + 2 -> "Getting worse: more misses than before"; else -> "About the same as before" }) to C.TEXT)
        }
    }

    override fun report(d: Any): String {
        d as D
        val o = d.o ?: return "Mouse learning: nothing saved yet (enabled=${d.enabled})"
        val b = o.optJSONObject("boost")
        val apps = b?.keys()?.asSequence()?.map { "${d.labels[it] ?: it} +${(b.optDouble(it) * 100).toInt()}%" }?.joinToString(", ").orEmpty()
        return "Mouse learning (enabled=${d.enabled}): ${o}\nApps with magnet: ${apps.ifBlank { "none" }}\nTrend: ${trend(d.hist).takeLast(10).joinToString(" | ").ifBlank { "not enough data" }}"
    }
}
