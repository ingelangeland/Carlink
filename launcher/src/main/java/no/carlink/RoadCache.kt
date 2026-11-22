package no.carlink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Veiene rundt bilen (bare veier – ingen bygninger, parker osv.) for minikartet i navigasjonskortet.
 * Hentes fra OpenStreetMap (Overpass, gratis) for ca. 900 m rundt bilen, og på nytt først når bilen
 * har kjørt 400 m. Tegnes én gang til et lite bilde som kortet bare flytter og roterer (billig).
 */
object RoadCache {
    class Road(val pts: DoubleArray, val cls: Int, val name: String)   // pts = lat,lon,lat,lon …
    class Area(val lat: Double, val lon: Double, val roads: List<Road>)

    const val RADIUS_M = 800
    val area = MutableStateFlow<Area?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastTry = 0L

    /** Kalles med bilens posisjon mens navigasjonskortet vises. Henter bare når det trengs. */
    fun near(lat: Double, lon: Double) {
        val a = area.value
        if (a != null && meters(a.lat, a.lon, lat, lon) < 400) return
        if (job?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTry < 20_000) return          // høyst ett forsøk hvert 20. sekund
        lastTry = now
        job = scope.launch(Dispatchers.IO) {
            // Først: siste lagrede område (vises med en gang, også uten nett)
            if (area.value == null) loadCached(lat, lon)?.let { area.value = it; if (meters(it.lat, it.lon, lat, lon) < 400) return@launch }
            // Så: to offentlige tjenere, den andre hvis den første er treg
            for (server in SERVERS) {
                val r = runCatching { fetch(server, lat, lon) }
                r.onSuccess { a ->
                    android.util.Log.i("CarLinkRoads", "${a.roads.size} veier hentet fra $server")
                    area.value = a; return@launch
                }.onFailure { android.util.Log.w("CarLinkRoads", "feil fra $server: ${it.message}") }
            }
        }
    }

    private val SERVERS = listOf("https://overpass.kumi.systems/api/interpreter", "https://overpass-api.de/api/interpreter")
    @Volatile var cacheDir: java.io.File? = null      // settes av appen: siste område lagres til neste oppstart

    private fun fetch(server: String, lat: Double, lon: Double): Area {
        val q = "[out:json][timeout:12];way(around:$RADIUS_M,$lat,$lon)[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|" +
            "unclassified|residential|living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"];out tags geom qt;"
        val c = URL(server).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 25_000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("User-Agent", "CarLink/1.0 (Android-bilskjerm)")
        c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
        if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
        val txt = c.inputStream.bufferedReader().use { it.readText() }
        val a = parse(txt, lat, lon)
        runCatching { cacheDir?.let { java.io.File(it, "veier.json").writeText("$lat,$lon\n$txt") } }
        return a
    }

    private fun loadCached(lat: Double, lon: Double): Area? = runCatching {
        val f = java.io.File(cacheDir ?: return null, "veier.json")
        if (!f.exists()) return null
        val txt = f.readText(); val nl = txt.indexOf('\n')
        val (la, lo) = txt.substring(0, nl).split(",").map { it.toDouble() }
        if (meters(la, lo, lat, lon) > RADIUS_M) return null
        parse(txt.substring(nl + 1), la, lo)
    }.getOrNull()

    private fun parse(txt: String, lat: Double, lon: Double): Area {
        val els = JSONObject(txt).getJSONArray("elements")
        val roads = ArrayList<Road>(els.length())
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val g = e.optJSONArray("geometry") ?: continue
            val tags = e.optJSONObject("tags")
            val pts = DoubleArray(g.length() * 2)
            for (k in 0 until g.length()) { val p = g.getJSONObject(k); pts[2 * k] = p.getDouble("lat"); pts[2 * k + 1] = p.getDouble("lon") }
            roads += Road(pts, roadClass(tags?.optString("highway") ?: ""), tags?.optString("name") ?: "")
        }
        return Area(lat, lon, roads)
    }

    private fun roadClass(h: String) = when (h) {
        "motorway", "trunk" -> 0
        "primary", "secondary" -> 1
        "tertiary" -> 2
        "service" -> 4
        else -> 3
    }

    /** Bredde i dp for hver veiklasse (store veier tydeligst). */
    val widthDp = floatArrayOf(7f, 5.5f, 4.5f, 3.5f, 2f)

    fun meters(la1: Double, lo1: Double, la2: Double, lo2: Double): Double =
        hypot((la2 - la1) * 110_540.0, (lo2 - lo1) * 111_320.0 * cos(Math.toRadians(la1)))

    /** Posisjon i bildet (piksler) for en koordinat. */
    fun project(a: Area, pxPerM: Float, half: Float, lat: Double, lon: Double, out: FloatArray) {
        out[0] = (half + (lon - a.lon) * 111_320.0 * cos(Math.toRadians(a.lat)) * pxPerM).toFloat()
        out[1] = (half - (lat - a.lat) * 110_540.0 * pxPerM).toFloat()
    }

    /** Tegner alle veiene som en gråtonemaske (lite minne; fargen settes når den vises). */
    fun render(a: Area, pxPerM: Float, density: Float): Bitmap {
        val size = (RADIUS_M * 2 * pxPerM).toInt().coerceIn(64, 2048)
        val half = size / 2f
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val xy = FloatArray(2)
        // Små veier først, store oppå
        for (cls in 4 downTo 0) {
            p.strokeWidth = widthDp[cls] * density
            p.alpha = if (cls == 4) 140 else 255
            val path = Path()
            for (r in a.roads) {
                if (r.cls != cls) continue
                for (k in 0 until r.pts.size / 2) {
                    project(a, pxPerM, half, r.pts[2 * k], r.pts[2 * k + 1], xy)
                    if (k == 0) path.moveTo(xy[0], xy[1]) else path.lineTo(xy[0], xy[1])
                }
            }
            c.drawPath(path, p)
        }
        return b
    }

    /** Veien navigasjonen svinger inn på (f.eks. «… inn på Ringveien»), som strek i bildets koordinater. */
    fun targetPath(a: Area, pxPerM: Float, name: String): Path? {
        val n = name.trim().lowercase()
        if (n.length < 3) return null
        val half = (RADIUS_M * 2 * pxPerM).toInt().coerceIn(64, 2048) / 2f
        val path = Path(); val xy = FloatArray(2); var any = false
        for (r in a.roads) {
            if (r.name.lowercase() != n) continue
            any = true
            for (k in 0 until r.pts.size / 2) {
                project(a, pxPerM, half, r.pts[2 * k], r.pts[2 * k + 1], xy)
                if (k == 0) path.moveTo(xy[0], xy[1]) else path.lineTo(xy[0], xy[1])
            }
        }
        return if (any) path else null
    }

    /** Finner gatenavnet i instruksjonen fra Google Maps: «Sving til høyre inn på Ringveien» → «Ringveien». */
    fun streetFrom(title: String, text: String): String? {
        for (s in listOf(text, title)) {
            for (key in listOf(" inn på ", " mot ", " på ", " onto ", " toward ", " on ")) {
                val i = s.lastIndexOf(key)
                if (i >= 0) return s.substring(i + key.length).trim().trimEnd('.')
            }
        }
        return title.takeIf { t -> t.any { it.isLetter() } && t.none { it.isDigit() } }
    }
}
