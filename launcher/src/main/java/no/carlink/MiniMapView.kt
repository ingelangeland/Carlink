package no.carlink

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Forenklet kart i navigasjonskortet: bare veiene, gjennomsiktige over coveret, med veien du
 * skal inn på uthevet i coverfargen og bilen som en tydelig pil. Kartet snus etter kjøreretningen.
 * Lett: veiene er ett ferdig bilde som bare flyttes/roteres; ingen tegning når bilen står stille.
 */
class MiniMapView(ctx: Context) : View(ctx) {

    private val density = resources.displayMetrics.density
    private val pxPerM = 0.55f * density                 // ca. 300 m synlig i høyden
    private var area: RoadCache.Area? = null
    private var roads: Bitmap? = null
    private var target: Path? = null
    private var targetName: String? = null
    private var renderJob: Job? = null

    // Vist posisjon (animeres mykt mellom GPS-oppdateringene)
    private var lat = 0.0; private var lon = 0.0; private var brg = 0f
    private var fromLat = 0.0; private var fromLon = 0.0; private var fromBrg = 0f
    private var toLat = 0.0; private var toLon = 0.0; private var toBrg = 0f
    private var hasPos = false
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900; interpolator = LinearInterpolator()
        addUpdateListener { v ->
            val f = v.animatedValue as Float
            lat = fromLat + (toLat - fromLat) * f; lon = fromLon + (toLon - fromLon) * f
            brg = fromBrg + angleDiff(fromBrg, toBrg) * f
            invalidate()
        }
    }

    private val roadPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = 0xFFFFFFFF.toInt(); alpha = 0x8C }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 6f * density
    }
    private val carFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val carEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = 0xFFFFFFFF.toInt() }
    private val car = Path().apply {
        val s = 13f * density
        moveTo(0f, -s); lineTo(s * 0.8f, s * 0.85f); lineTo(0f, s * 0.45f); lineTo(-s * 0.8f, s * 0.85f); close()
    }
    private val xy = FloatArray(2)

    var accent: Int = C.ACCENT
        set(v) { field = v; targetPaint.color = (v and 0x00FFFFFF) or 0xE6000000.toInt(); carFill.color = v or 0xFF000000.toInt(); invalidate() }

    init { accent = C.ACCENT }

    fun setArea(a: RoadCache.Area?) {
        if (a === area) return
        area = a
        renderJob?.cancel()
        if (a == null) { roads = null; target = null; invalidate(); return }
        val name = targetName
        renderJob = RoadCache.scope.launch {
            try {
                val bmp = RoadCache.render(a, pxPerM, density)
                val t = name?.let { RoadCache.targetPath(a, pxPerM, it) }
                post { if (area === a) { roads = bmp; target = t; invalidate() } }
            } catch (e: Exception) { android.util.Log.w("CarLinkRoads", "tegning feilet", e) }
        }
    }

    fun setTargetStreet(name: String?) {
        if (name == targetName) return
        targetName = name
        val a = area ?: return
        target = name?.let { RoadCache.targetPath(a, pxPerM, it) }
        invalidate()
    }

    fun setLocation(l: Hub.Loc) {
        RoadCache.near(l.lat, l.lon)
        val b = if (l.hasBearing) l.bearing else toBrg
        if (!hasPos) { hasPos = true; lat = l.lat; lon = l.lon; brg = b; toLat = lat; toLon = lon; toBrg = b; invalidate(); return }
        // Står stille (under 2 m og samme retning): ingen ny tegning
        if (RoadCache.meters(toLat, toLon, l.lat, l.lon) < 2.0 && kotlin.math.abs(angleDiff(toBrg, b)) < 3f) return
        fromLat = lat; fromLon = lon; fromBrg = brg
        toLat = l.lat; toLon = l.lon; toBrg = b
        if (isShown) anim.start() else { lat = toLat; lon = toLon; brg = toBrg }
    }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val a = area; val bmp = roads
        val cx = width / 2f; val cy = height * 0.62f
        if (a != null && bmp != null && hasPos) {
            RoadCache.project(a, pxPerM, bmp.width / 2f, lat, lon, xy)
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(-brg)                 // kjøreretningen alltid opp
            canvas.translate(-xy[0], -xy[1])
            canvas.drawBitmap(bmp, 0f, 0f, roadPaint)
            target?.let { canvas.drawPath(it, targetPaint) }
            canvas.restore()
        }
        if (hasPos) {
            canvas.save(); canvas.translate(cx, cy)
            canvas.drawPath(car, carFill); canvas.drawPath(car, carEdge)
            canvas.restore()
        }
    }

    private fun angleDiff(a: Float, b: Float): Float { var d = (b - a) % 360f; if (d > 180f) d -= 360f; if (d < -180f) d += 360f; return d }
}
