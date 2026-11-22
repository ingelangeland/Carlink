package no.carlink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.carlink.launcher.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Demomodus: en simulert telefon inne i bilskjerm-appen. Gir alt skjermen ellers får fra
 * telefonen – musikk med cover og fremdrift, GPS langs en rute i Oslo i ca. 60 km/t,
 * sving-for-sving-navigasjon og lydnivåer til lydbølgene – så alt kan prøves uten telefon og bil.
 * Musikknappene (spill/pause, neste, forrige) virker også.
 */
object DemoFeeder {

    var active = false
        private set
    /** Vis navigasjonskort (neste sving). */
    @Volatile var showNav = false

    private var job: Job? = null
    private var idx = 0
    private var playing = true
    private var posMs = 0L
    private var posAt = 0L

    private data class Song(val title: String, val artist: String, val album: String, val durationMs: Long)

    private val songs = listOf(
        Song("Nattkjøring", "Nordlys Ekspress", "Fjordlys", 215_000),
        Song("Havbris", "Kystlinjen", "Saltvann", 188_000),
        Song("Neonfjell", "Polarnatt", "Midtvinter", 242_000),
    )

    // En runde gjennom Oslo sentrum (lat, lon)
    private val route = listOf(
        59.9111 to 10.7528, 59.9139 to 10.7450, 59.9170 to 10.7363, 59.9220 to 10.7310,
        59.9275 to 10.7335, 59.9300 to 10.7440, 59.9268 to 10.7560, 59.9200 to 10.7610, 59.9140 to 10.7600,
    )

    private lateinit var covers: List<Bitmap?>
    private var arrow: Bitmap? = null

    fun start(ctx: Context) {
        if (active) return
        active = true
        val app = ctx.applicationContext
        covers = listOf(
            runCatching { BitmapFactory.decodeResource(app.resources, R.drawable.placeholder_cover) }.getOrNull(),
            gradientCover(0xFF06283D.toInt(), 0xFF1363DF.toInt(), 0xFF47B5FF.toInt()),
            gradientCover(0xFF2B0A3D.toInt(), 0xFFF72585.toInt(), 0xFFFFB703.toInt()),
        )
        arrow = turnArrow()
        Hub.demoHandler = { a -> onCommand(a) }
        Hub.peerName.value = "Demo-telefon"
        Hub.status.value = "Demomodus – simulert telefon"
        Hub.connected.value = true
        Hub.audioStreaming.value = true
        idx = 0; playing = true; posMs = 0; posAt = SystemClock.elapsedRealtime()
        publish()

        job = MainScope().launch {
            // Neste låt når den er ferdig
            launch {
                while (isActive) {
                    delay(1000)
                    if (playing && currentPos() >= songs[idx].durationMs) next()
                }
            }
            // GPS: én posisjon i sekundet langs ruta, ca. 60 km/t
            RoadCache.demoArea(route)
            launch {
                var seg = 0
                var f = 0.0
                while (isActive) {
                    val (aLat, aLon) = route[seg]
                    val (bLat, bLon) = route[(seg + 1) % route.size]
                    val lat = aLat + (bLat - aLat) * f
                    val lon = aLon + (bLon - aLon) * f
                    val brg = Math.toDegrees(atan2((bLon - aLon) * cos(Math.toRadians(aLat)), bLat - aLat)).let { (it + 360) % 360 }
                    Hub.remoteLoc.value = Hub.Loc(lat, lon, brg.toFloat(), 16.7f, true, 5f, 20.0)
                    // segmentlengde i meter → hvor stor del av segmentet ett sekund er
                    val dy = (bLat - aLat) * 111_000.0
                    val dx = (bLon - aLon) * 111_000.0 * cos(Math.toRadians(aLat))
                    val len = max(1.0, kotlin.math.sqrt(dx * dx + dy * dy))
                    f += 16.7 / len
                    if (f >= 1.0) { f = 0.0; seg = (seg + 1) % route.size }
                    delay(1000)
                }
            }
            // Lydnivåer (bass/diskant) 50 ganger i sekundet, som når lyd strømmes
            launch {
                var t = 0.0
                while (isActive) {
                    t += 0.02
                    if (playing) {
                        val beat = 0.5 + 0.5 * max(0.0, sin(t * 2 * Math.PI * 2.0))      // 120 bpm
                        Hub.audioLow = (0.25 + 0.55 * beat * (0.7 + 0.3 * sin(t * 0.7))).toFloat()
                        Hub.audioHigh = (0.20 + 0.25 * (0.5 + 0.5 * sin(t * 9.0)) * (0.6 + 0.4 * sin(t * 0.4))).toFloat()
                        Hub.audioLevelAt = SystemClock.elapsedRealtime()
                    }
                    delay(20)
                }
            }
            // Navigasjon: avstanden telles ned, ny instruksjon når den er nådd
            launch {
                val steps = listOf(
                    "Sving til høyre" to "inn på Ringveien",
                    "Hold til venstre" to "mot E18 Drammen",
                    "I rundkjøringen, ta 2. avkjørsel" to "Kongsveien",
                    "Sving til venstre" to "inn på Fjordveien",
                )
                var step = 0
                var dist = 600
                var minutes = 14
                while (isActive) {
                    Hub.remoteNav.value = if (!showNav) null else {
                        val d = if (dist >= 1000) "%.1f km".format(dist / 1000f) else "${(dist / 10) * 10} m"
                        Hub.NavInfo(d, "${steps[step].first} ${steps[step].second}", "$minutes min · ${minutes * 1} km · ankomst 14:46", arrow, step)
                    }
                    dist -= 17
                    if (dist <= 0) { step = (step + 1) % steps.size; dist = 400 + step * 250; if (minutes > 1) minutes-- }
                    delay(1000)
                }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        active = false
        Hub.demoHandler = null
        Hub.connected.value = false
        Hub.audioStreaming.value = false
        Hub.remoteMedia.value = null
        Hub.remoteNav.value = null
        Hub.remoteLoc.value = null
        Hub.status.value = "Demomodus av"
    }

    private fun currentPos() = if (playing) posMs + (SystemClock.elapsedRealtime() - posAt) else posMs

    private fun onCommand(a: String) {
        when (a) {
            "toggle" -> { posMs = currentPos(); posAt = SystemClock.elapsedRealtime(); playing = !playing; Hub.audioStreaming.value = playing }
            "play" -> { posAt = SystemClock.elapsedRealtime(); playing = true }
            "pause" -> { posMs = currentPos(); playing = false }
            "next" -> { next(); return }
            "prev" -> { if (currentPos() > 3000) { posMs = 0 } else { idx = (idx + songs.size - 1) % songs.size; posMs = 0 }; posAt = SystemClock.elapsedRealtime() }
        }
        publish()
    }

    private fun next() {
        idx = (idx + 1) % songs.size
        posMs = 0; posAt = SystemClock.elapsedRealtime(); playing = true
        publish()
    }

    private fun publish() {
        val s = songs[idx]
        Hub.remoteMedia.value = Hub.Media(
            title = s.title, artist = s.artist, album = s.album, playing = playing,
            durationMs = s.durationMs, positionMs = currentPos(), updatedAt = SystemClock.elapsedRealtime(),
            art = covers.getOrNull(idx), artId = 9000 + idx, app = "TIDAL",
        )
    }

    /** Enkle, fargerike fiktive cover for låt 2 og 3 (så fargebyttet kan testes). */
    private fun gradientCover(c1: Int, c2: Int, c3: Int): Bitmap {
        val s = 512
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, s.toFloat(), c1, c2, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        p.shader = RadialGradient(s * 0.7f, s * 0.35f, s * 0.35f, c3, c3 and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(s * 0.7f, s * 0.35f, s * 0.35f, p)
        p.shader = null; p.color = 0x66000000
        c.drawRect(0f, s * 0.72f, s.toFloat(), s.toFloat(), p)
        return b
    }

    private fun turnArrow(): Bitmap {
        val s = 96
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 10f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val path = Path().apply { moveTo(30f, 86f); lineTo(30f, 46f); quadTo(30f, 30f, 46f, 30f); lineTo(72f, 30f) }
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        c.drawPath(Path().apply { moveTo(70f, 14f); lineTo(90f, 30f); lineTo(70f, 46f); close() }, p)
        return b
    }
}
