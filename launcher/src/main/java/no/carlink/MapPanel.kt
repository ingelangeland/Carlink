package no.carlink

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/** Live kart (OpenStreetMap, mørkt tema) som følger telefonens GPS. */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class MapPanel(ctx: Context) : ScopedFrame(ctx) {

    private val map = MapView(ctx)
    private val marker = Marker(map)
    private var follow = true
    private var myLoc: MyLocationNewOverlay? = null
    private val followBtn: android.widget.TextView
    private val navCard: LinearLayout
    private val navIcon = android.widget.ImageView(ctx)
    private val navTitle = ctx.label("", 20f, C.TEXT, true)
    private val navText = ctx.label("", 13f, C.TEXT).apply { maxLines = 2 }
    private val navSub = ctx.label("", 12f, C.OK, true)

    private val darkTiles = XYTileSource(
        "CartoDark", 0, 20, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/dark_all/",
            "https://b.basemaps.cartocdn.com/dark_all/",
            "https://c.basemaps.cartocdn.com/dark_all/",
        ),
        "© OpenStreetMap-bidragsytere © CARTO",
    )

    init {
        map.setTileSource(darkTiles)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.isTilesScaledToDpi = true
        map.controller.setZoom(16.0)
        map.controller.setCenter(GeoPoint(59.9139, 10.7522))
        map.overlays.add(CopyrightOverlay(ctx).apply { setTextColor(C.SUB) })
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        marker.icon = BitmapDrawable(resources, arrow())
        marker.isFlat = true
        marker.setInfoWindow(null)
        map.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_MOVE) setFollow(false); false }
        addView(map, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Søkefelt øverst -> åpner Google Maps
        val search = EditText(ctx).apply {
            hint = "Søk sted i Google Maps …"
            setHintTextColor(C.SUB); setTextColor(C.TEXT); textSize = 14f
            background = rounded(0xE6171B22.toInt(), 22)
            setPadding(16.dp, 10.dp, 16.dp, 10.dp)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { v, _, _ ->
                Launch.openMaps(context, v.text.toString(), Launch.Mode.ADJACENT); v.text = null; true
            }
        }
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { setMargins(10.dp, 6.dp, 10.dp, 0) })

        // Sving-for-sving fra Google Maps på telefonen
        navCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xF20F5132.toInt(), 16)
            setPadding(12.dp, 10.dp, 14.dp, 10.dp)
            visibility = GONE
            addView(navIcon, LinearLayout.LayoutParams(48.dp, 48.dp).apply { rightMargin = 12.dp })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(navTitle); addView(navText); addView(navSub)
            })
            setOnClickListener { Launch.openMaps(context, null, Launch.Mode.ADJACENT) }
        }
        addView(navCard, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(10.dp, 56.dp, 10.dp, 0)
        })

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        followBtn = ctx.pill("◎ Følger") { setFollow(!follow) }
        buttons.addView(followBtn)
        buttons.addView(ctx.pill("Åpne Maps ⇆", C.ACCENT) { Launch.openMaps(context, null, Launch.Mode.ADJACENT) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp })
        buttons.addView(ctx.pill("Maps fullskjerm") { Launch.openMaps(context, null, Launch.Mode.FULL) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp })
        addView(buttons, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 12.dp, 22.dp) })
    }

    private fun setFollow(f: Boolean) {
        if (follow == f) return
        follow = f
        followBtn.text = if (f) "◎ Følger" else "◎ Følg"
        followBtn.background = rounded(if (f) C.PANEL2 else C.WARN, 22)
        if (f) {
            Hub.remoteLoc.value?.let { map.controller.animateTo(GeoPoint(it.lat, it.lon)) }
            myLoc?.enableFollowLocation()
        } else myLoc?.disableFollowLocation()
    }

    override fun onStart(scope: CoroutineScope) {
        map.onResume()
        scope.launch {
            Hub.remoteNav.collect { n ->
                navCard.visibility = if (n == null) GONE else VISIBLE
                if (n != null) {
                    navTitle.text = n.title
                    navText.text = n.text
                    navText.visibility = if (n.text.isBlank()) GONE else VISIBLE
                    navSub.text = n.sub
                    navSub.visibility = if (n.sub.isBlank()) GONE else VISIBLE
                    navIcon.setImageBitmap(n.icon)
                    navIcon.setColorFilter(0xFFFFFFFF.toInt())
                    navIcon.visibility = if (n.icon == null) GONE else VISIBLE
                }
            }
        }
        scope.launch {
            Hub.remoteLoc.collect { l ->
                if (l == null) { ensureOwnLocation(); return@collect }
                myLoc?.let { it.disableMyLocation(); map.overlays.remove(it); myLoc = null }
                val gp = GeoPoint(l.lat, l.lon)
                if (!map.overlays.contains(marker)) map.overlays.add(marker)
                marker.position = gp
                if (l.hasBearing) marker.rotation = 360f - l.bearing
                if (follow) map.controller.animateTo(gp)
                map.invalidate()
            }
        }
    }

    /** Ingen telefon tilkoblet: bruk skjermens egen posisjon hvis den har GPS. */
    private fun ensureOwnLocation() {
        if (myLoc != null || !Perms.has(context, Manifest.permission.ACCESS_FINE_LOCATION)) return
        myLoc = MyLocationNewOverlay(GpsMyLocationProvider(context), map).apply {
            enableMyLocation(); if (follow) enableFollowLocation()
        }
        map.overlays.add(myLoc)
    }

    override fun onStop() {
        myLoc?.disableMyLocation()
        map.onPause()
    }

    private fun arrow(): Bitmap {
        val s = 44.dp
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Path().apply {
            moveTo(s / 2f, s * 0.08f); lineTo(s * 0.86f, s * 0.9f); lineTo(s / 2f, s * 0.7f); lineTo(s * 0.14f, s * 0.9f); close()
        }
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.ACCENT })
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f.dp(); color = 0xFFFFFFFF.toInt() })
        return b
    }

    private fun Float.dp(): Float = this * resources.displayMetrics.density
}
