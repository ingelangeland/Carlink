package no.carlink

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextClock
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.carlink.launcher.R
import kotlin.math.roundToInt

/**
 * Hjemmeskjermen. Albumcoveret fyller hele bakgrunnen (ArtBackground) og alt her ligger oppå:
 * toppstripe · venstre: klokkekort + favorittapper · høyre: åpent (coveret synes) · musikklinje.
 * Alle flater fargelegges med coverets farger (Ambient.theme).
 */
@SuppressLint("ViewConstructor")
class HomeView(ctx: Context, private val actions: Actions) : ScopedFrame(ctx) {

    interface Actions {
        fun openMap()
        fun openMusic()
        fun openApps()
        fun openSplit()
        fun openSettings()
    }

    /** En flate som skal fargelegges etter coveret. */
    private class Glass(
        val view: View, val alpha: Int, val radiusDp: Int,
        val solid: Boolean = false, val soft: Boolean = false, val featherDp: Int = FEATHER,
    )

    private val glass = mutableListOf<Glass>()
    private val favGlass = mutableListOf<Glass>()

    private val weatherTv = ctx.label("", 20f, 0xFFFFFFFF.toInt()).shadow()
    private val speedTv = ctx.label("", 15f, 0xFFFFFFFF.toInt(), true).shadow()
    private val statusDot = View(ctx)
    private val statusTv = ctx.label("", 13f, 0xE6FFFFFF.toInt(), true).shadow()

    private val clockCard = LinearLayout(ctx)
    private val timeTv = TextClock(ctx)
    private val dateTv = TextClock(ctx)
    private var favPills: List<View> = emptyList()
    private val accentLine = View(ctx)
    private val sourceTv = ctx.label("", 11f, C.ACCENT, true).apply { letterSpacing = 0.08f }

    private val navBox = LinearLayout(ctx)
    private val navIcon = ImageView(ctx)
    private val navTitle = ctx.label("", 30f, 0xFFFFFFFF.toInt(), true).shadow()
    private val navText = ctx.label("", 17f, 0xE6FFFFFF.toInt()).apply { maxLines = 3; gravity = Gravity.CENTER; maxWidth = 380.dp }
    private val navSub = ctx.label("", 14f, C.OK, true)

    private val favGrid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val leftCol = LinearLayout(ctx)
    private val rightCol = FrameLayout(ctx)
    private var ultraWide = false

    private val cover = ImageView(ctx)
    private val title = ctx.label("", 18f, 0xFFFFFFFF.toInt(), true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.shadow()
    private val artist = ctx.label("", 14f, 0xC0FFFFFF.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(C.ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
    }
    private val playBtn = ImageView(ctx)
    private val viz = VisualizerView(ctx)
    private var media: Hub.Media? = null

    init {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(28.dp, 16.dp, 28.dp, 18.dp) }

        // ---------- toppstripe
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // Venstre: alle apper + telefonstatus
        top.addView(roundIcon(R.drawable.ic_grid, 52) { actions.openApps() })
        top.addView(statusDot, lp(10.dp, 10.dp).apply { leftMargin = 18.dp; rightMargin = 8.dp })
        statusDot.background = rounded(C.WARN, 5)
        top.addView(statusTv, lp(WRAP, WRAP))
        // Midten: snarveier
        top.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(roundIcon(R.drawable.ic_tune, 44) { actions.openSettings() })
        top.addView(roundIcon(R.drawable.ic_nav, 44) { actions.openMap() }, lp(44.dp, 44.dp).apply { leftMargin = 14.dp })
        top.addView(View(ctx).apply { setBackgroundColor(0x40FFFFFF) }, lp(1.dp, 26.dp).apply { leftMargin = 14.dp; rightMargin = 14.dp })
        top.addView(roundIcon(R.drawable.ic_split, 44) { actions.openSplit() })
        top.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        // Høyre: fart + vær
        top.addView(speedTv)
        top.addView(weatherTv, lp(WRAP, WRAP).apply { leftMargin = 18.dp })
        col.addView(top, lp(MATCH, 56.dp))

        // ---------- midten
        val mid = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 10.dp, 0, 14.dp) }

        val left = leftCol.apply { orientation = LinearLayout.VERTICAL }
        clockCard.apply {
            orientation = LinearLayout.VERTICAL
            setPadding((26 + FEATHER).dp, (14 + FEATHER).dp, (26 + FEATHER).dp, (18 + FEATHER).dp)
            addView(timeTv.apply {
                format24Hour = "HH:mm"; format12Hour = "HH:mm"
                setTextColor(0xFFFFFFFF.toInt()); textSize = 64f
                typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
                includeFontPadding = false
                letterSpacing = -0.02f
                shadow()
            })
            addView(accentLine, LinearLayout.LayoutParams(44.dp, 3.dp).apply { topMargin = 8.dp; bottomMargin = 10.dp })
            addView(dateTv.apply {
                format24Hour = "EEEE d. MMMM"; format12Hour = "EEEE d. MMMM"
                setTextColor(0xE6FFFFFF.toInt()); textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0.03f
                shadow()
            })
            setOnClickListener { actions.openSettings() }
        }
        glass += Glass(clockCard, 0xB3, 34, soft = true)
        left.addView(clockCard, lp(MATCH, WRAP).apply { setMargins(-FEATHER.dp, -FEATHER.dp, -FEATHER.dp, -FEATHER.dp) })
        left.clipChildren = false; left.clipToPadding = false
        mid.clipChildren = false; mid.clipToPadding = false
        col.clipChildren = false
        left.addView(favGrid, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 12.dp })
        favGrid.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) post { layoutFavGrid() } }
        mid.addView(left, LinearLayout.LayoutParams(0, MATCH, 0.44f))

        // Høyre side står åpen så coveret synes. Under navigasjon vises neste sving her.
        val right = rightCol
        navBox.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28.dp, 22.dp, 28.dp, 22.dp)
            visibility = GONE
            addView(navIcon, LinearLayout.LayoutParams(84.dp, 84.dp))
            addView(navTitle, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 6.dp })
            addView(navText, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 4.dp })
            addView(navSub, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 6.dp })
            setOnClickListener { actions.openMap() }
        }
        glass += Glass(navBox, 0xD9, 28)
        right.addView(navBox, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        // Trykk på det åpne coveret = musikkvisning
        right.setOnClickListener { actions.openMusic() }
        mid.addView(right, LinearLayout.LayoutParams(0, MATCH, 0.56f).apply { leftMargin = 28.dp })
        col.addView(mid, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // ---------- musikklinje
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 + BAR_FEATHER).dp, (10 + BAR_FEATHER).dp, (22 + BAR_FEATHER).dp, (10 + BAR_FEATHER).dp)
        }
        glass += Glass(bar, 0xBF, 40, soft = true, featherDp = BAR_FEATHER)
        bar.addView(ctrl(R.drawable.ic_prev, 46) { Hub.mediaCommand("prev") })
        playBtn.apply {
            setImageResource(R.drawable.ic_play)
            setPadding(16.dp, 16.dp, 16.dp, 16.dp)
            setOnClickListener { Hub.mediaCommand("toggle") }
        }
        glass += Glass(playBtn, 0xFF, 30, solid = true)
        bar.addView(playBtn, lp(60.dp, 60.dp).apply { leftMargin = 8.dp; rightMargin = 8.dp })
        bar.addView(ctrl(R.drawable.ic_next, 46) { Hub.mediaCommand("next") })
        cover.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0x33FFFFFF, 12)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setOnClickListener { actions.openMusic() }
        }
        bar.addView(cover, lp(54.dp, 54.dp).apply { leftMargin = 20.dp })
        val txt = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 0, 18.dp, 0)
            addView(sourceTv)
            addView(title); addView(artist)
            addView(progress, LinearLayout.LayoutParams(MATCH, 3.dp).apply { topMargin = 7.dp })
            setOnClickListener { actions.openMusic() }
        }
        bar.addView(txt, LinearLayout.LayoutParams(0, WRAP, 0.42f))
        bar.addView(viz, LinearLayout.LayoutParams(0, 46.dp, 0.58f))
        if (DebugFlags.noVisualizer) viz.visibility = View.INVISIBLE
        // Linjen er større enn plassen sin med fadingen, så selve flaten står der den sto
        col.addView(bar, lp(MATCH, (88 + 2 * BAR_FEATHER).dp).apply {
            setMargins(-BAR_FEATHER.dp, -BAR_FEATHER.dp, -BAR_FEATHER.dp, -BAR_FEATHER.dp)
        })

        addView(col, LayoutParams(MATCH, MATCH))
        applyTheme(Ambient.theme.value)
    }

    // ------------------------------------------------------------------ farger fra coveret

    private fun glassBg(t: Ambient.Theme, g: Glass): android.graphics.drawable.Drawable = when {
        g.solid -> rounded(t.accent, g.radiusDp)
        // Myk flate uten kant: fader ut mot sidene i stedet for å slutte brått
        g.soft -> SoftPanelDrawable(Ambient.alpha(t.dark, g.alpha), g.radiusDp.dp.toFloat(), g.featherDp.dp.toFloat())
        else -> rounded(Ambient.alpha(t.dark, g.alpha), g.radiusDp, Ambient.alpha(t.accent, 0x38))
    }

    private fun applyTheme(t: Ambient.Theme) {
        (glass + favGlass).forEach { it.view.background = glassBg(t, it) }
        accentLine.background = rounded(t.accent, 2)
        progress.progressTintList = ColorStateList.valueOf(t.accent)
        sourceTv.setTextColor(t.accent)
        speedTv.setTextColor(t.accent)
        viz.accent = t.accent
        // Spill-knappen er fylt med aksentfargen -> mørkt ikon hvis fargen er lys
        playBtn.setColorFilter(if (androidx.core.graphics.ColorUtils.calculateLuminance(t.accent) > 0.5) t.dark else 0xFFFFFFFF.toInt())
    }

    /**
     * Diskret lesbarhet for fart og vær: ingen flate, bare en myk skygge tett rundt bokstavene
     * i coverets mørke farge. Nesten usynlig på rolig, mørk bakgrunn – sterkere bare når
     * bakgrunnen bak teksten er lys eller urolig.
     */
    private fun protectTopRight(t: Ambient.Theme, need: Float) {
        val radius = (3f + 9f * need) * resources.displayMetrics.density
        val alpha = (0x38 + (0xD0 - 0x38) * need).toInt()
        val shadow = Ambient.alpha(Ambient.deep(t.dark), alpha)
        speedTv.setShadowLayer(radius, 0f, 0f, shadow)
        weatherTv.setShadowLayer(radius, 0f, 0f, shadow)
    }

    // ------------------------------------------------------------------ data

    override fun onStart(scope: CoroutineScope) {
        scope.launch { Ambient.theme.collect { applyTheme(it) } }
        scope.launch {
            combine(Ambient.theme, Ambient.topRightNeed) { t, need -> t to need }.collect { (t, need) -> protectTopRight(t, need) }
        }
        scope.launch { Ambient.media.collect { (m, src) -> render(m, src) } }
        scope.launch {
            while (isActive) {
                val m = media
                progress.progress = if (m != null && m.durationMs > 0) (m.currentPosition() * 1000 / m.durationMs).toInt() else 0
                delay(500)
            }
        }
        scope.launch {
            Hub.remoteLoc.collect { l -> speedTv.text = if (l == null) "" else "${(l.speed * 3.6f).roundToInt()} km/t" }
        }
        scope.launch {
            combine(Hub.connected, Hub.peerName, Hub.audioStreaming) { c, p, a -> Triple(c, p, a) }.collect { (c, p, a) ->
                statusDot.background = rounded(if (c) C.OK else C.WARN, 5)
                statusTv.text = when {
                    c && a -> "${p.ifBlank { "Telefon" }} · lyd"
                    c -> p.ifBlank { "Telefon tilkoblet" }
                    else -> "Venter på telefon"
                }
            }
        }
        scope.launch {
            Hub.remoteNav.collect { n ->
                navBox.visibility = if (n == null) GONE else VISIBLE
                if (n != null) {
                    navIcon.setImageBitmap(n.icon); navIcon.setColorFilter(0xFFFFFFFF.toInt())
                    navIcon.visibility = if (n.icon == null) GONE else VISIBLE
                    navTitle.text = n.title
                    navText.text = n.text; navText.visibility = if (n.text.isBlank()) GONE else VISIBLE
                    navSub.text = n.sub; navSub.visibility = if (n.sub.isBlank()) GONE else VISIBLE
                }
            }
        }
        scope.launch { Weather.now.collect { w -> weatherTv.text = w?.let { "${Weather.icon(it.symbol)}  ${it.tempC}°" } ?: "" } }
        scope.launch { while (isActive) { Weather.refresh(context); delay(60_000) } }
        scope.launch { Favorites.changed.collect { loadFavorites() } }
    }

    private fun render(m: Hub.Media?, src: String) {
        media = m
        viz.playing = m?.playing == true
        playBtn.setImageResource(if (m?.playing == true) R.drawable.ic_pause else R.drawable.ic_play)
        if (m == null) {
            sourceTv.text = ""
            title.text = "Ingen musikk"
            artist.text = if (Hub.connected.value) "Start musikk på telefonen" else "Venter på telefonen …"
            cover.setImageResource(R.drawable.ic_music); cover.scaleType = ImageView.ScaleType.CENTER_INSIDE
            return
        }
        sourceTv.text = m.app.uppercase()
        title.text = m.title.ifBlank { "Ukjent tittel" }
        artist.text = listOf(m.artist, m.album).filter { it.isNotBlank() }.joinToString(" · ")
        if (m.art != null) { cover.scaleType = ImageView.ScaleType.CENTER_CROP; cover.setImageBitmap(m.art) }
        else { cover.scaleType = ImageView.ScaleType.CENTER_INSIDE; cover.setImageResource(R.drawable.ic_music) }
    }

    private data class Fav(val cn: ComponentName, val label: String, val icon: Drawable)

    private suspend fun loadFavorites() {
        val pm = context.packageManager
        val favs = withContext(Dispatchers.IO) {
            Favorites.list(context).mapNotNull { cn ->
                runCatching { pm.getActivityInfo(cn, 0) }.getOrNull()?.let { Fav(cn, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            }
        }
        favGlass.clear()
        val pills = favs.map { f -> favPill(f.icon, f.label, false, { Launch.app(context, f.cn, Launch.defaultMode(context)) }) { v -> favMenu(v, f.cn) } }.toMutableList()
        if (favs.size < 6) pills += favPill(context.getDrawable(R.drawable.ic_add), "Legg til", true, { actions.openApps() }, null)
        favPills = pills
        layoutFavGrid()
        applyTheme(Ambient.theme.value)
    }

    /** 2 eller 3 kolonner etter hvor mye høyde det er (f.eks. 1280×720 vs 1920×1080). */
    private fun layoutFavGrid() {
        val n = favPills.size
        if (n == 0) { favGrid.removeAllViews(); return }
        val rowH = 66.dp
        val rows = (favGrid.height / rowH).coerceAtLeast(1)
        val cols = if (favGrid.height == 0) 2 else ((n + rows - 1) / rows).coerceIn(2, 3)
        favGrid.removeAllViews()
        favPills.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        val shown = if (favGrid.height == 0) favPills else favPills.take(rows * cols)
        shown.chunked(cols).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until cols) {
                val v = chunk.getOrNull(i) ?: View(context)
                // Knappene overlapper litt, så fadingen ikke lager store hull mellom dem
                row.addView(v, LinearLayout.LayoutParams(0, (54 + PILL_FEATHER).dp, 1f).apply {
                    if (i > 0) leftMargin = (6 - PILL_FEATHER).dp
                    if (i < cols - 1) rightMargin = (6 - PILL_FEATHER).dp
                })
            }
            favGrid.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = (12 - PILL_FEATHER).dp })
        }
    }

    /**
     * Tilpasser seg skjermformatet:
     *  - Høyre side er nøyaktig så bred som det skarpe coveret i bakgrunnen (kvadrat i full høyde):
     *    56 % på 16:9, 43 % på 21:9.
     *  - 16:9: klokke over apper. 21:9 og bredere: klokke og apper i hver sin kolonne.
     *  - Mindre klokke på lave skjermer (f.eks. 1280×720 eller 1024×600).
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) post { adaptToAspect(w, h) }
        val compact = h < 560.dp
        timeTv.textSize = if (compact) 46f else 64f
        dateTv.textSize = if (compact) 14f else 16f
        clockCard.setPadding((22 + FEATHER).dp, (if (compact) 8 else 12).dp + FEATHER.dp, (22 + FEATHER).dp, (if (compact) 10 else 16).dp + FEATHER.dp)
    }

    private fun adaptToAspect(w: Int, h: Int) {
        val coverFrac = minOf(h.toFloat(), w * 0.62f) / w
        (leftCol.layoutParams as LinearLayout.LayoutParams).weight = 1f - coverFrac
        (rightCol.layoutParams as LinearLayout.LayoutParams).weight = coverFrac

        val ultra = w.toFloat() / h >= 2.0f
        if (ultra != ultraWide || leftCol.childCount > 0 && (favGrid.layoutParams as LinearLayout.LayoutParams).weight == 0f) {
            ultraWide = ultra
            val cardLp = clockCard.layoutParams as LinearLayout.LayoutParams
            val gridLp = favGrid.layoutParams as LinearLayout.LayoutParams
            if (ultra) {
                leftCol.orientation = LinearLayout.HORIZONTAL
                leftCol.gravity = Gravity.CENTER_VERTICAL
                cardLp.width = 0; cardLp.height = WRAP; cardLp.weight = 0.46f
                gridLp.width = 0; gridLp.height = MATCH; gridLp.weight = 0.54f
                gridLp.topMargin = 0; gridLp.leftMargin = 24.dp
                favGrid.gravity = Gravity.CENTER_VERTICAL
            } else {
                leftCol.orientation = LinearLayout.VERTICAL
                leftCol.gravity = Gravity.NO_GRAVITY
                cardLp.width = MATCH; cardLp.height = WRAP; cardLp.weight = 0f
                gridLp.width = MATCH; gridLp.height = 0; gridLp.weight = 1f
                gridLp.topMargin = 12.dp; gridLp.leftMargin = 0
                favGrid.gravity = Gravity.TOP
            }
            clockCard.layoutParams = cardLp
            favGrid.layoutParams = gridLp
        }
        leftCol.requestLayout()
        (leftCol.parent as? View)?.requestLayout()
    }

    private fun favMenu(anchor: View, cn: ComponentName) {
        PopupMenu(context, anchor).apply {
            menu.add(0, 1, 0, "Åpne ved siden av (delt skjerm)")
            menu.add(0, 2, 1, "Åpne som flytende vindu")
            val pos = Favorites.position(context, cn)
            if (pos > 0) menu.add(0, 4, 2, "Flytt frem")
            if (pos in 0 until Favorites.count(context) - 1) menu.add(0, 5, 3, "Flytt bak")
            menu.add(0, 3, 4, "Fjern fra Hjem")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> Launch.app(context, cn, Launch.Mode.ADJACENT)
                    2 -> Launch.app(context, cn, Launch.Mode.WINDOW)
                    3 -> Favorites.remove(context, cn)
                    4 -> Favorites.move(context, cn, -1)
                    5 -> Favorites.move(context, cn, +1)
                }
                true
            }
            show()
        }
    }

    // ------------------------------------------------------------------ byggeklosser

    private fun favPill(icon: Drawable?, text: String, tintIcon: Boolean, click: () -> Unit, longClick: ((View) -> Unit)?): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 + PILL_FEATHER).dp, 0, (10 + PILL_FEATHER).dp, 0)
            val iconWrap = FrameLayout(context).apply { background = rounded(0xF2FFFFFF.toInt(), 21) }
            iconWrap.addView(ImageView(context).apply {
                setImageDrawable(icon)
                if (tintIcon) setColorFilter(0xFF202124.toInt())
            }, FrameLayout.LayoutParams(28.dp, 28.dp, Gravity.CENTER))
            addView(iconWrap, LinearLayout.LayoutParams(42.dp, 42.dp))
            addView(context.label(text, 15f, 0xFFFFFFFF.toInt(), true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }.shadow(), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = 12.dp })
            isClickable = true
            setOnClickListener { click() }
            if (longClick != null) setOnLongClickListener { longClick(it); true }
            favGlass += Glass(this, 0xA6, 24, soft = true, featherDp = PILL_FEATHER)
        }

    private fun roundIcon(res: Int, sizeDp: Int, click: () -> Unit) = ImageView(context).apply {
        setImageResource(res)
        val p = (sizeDp * 0.26f).toInt().dp
        setPadding(p, p, p, p)
        setOnClickListener { click() }
        layoutParams = lp(sizeDp.dp, sizeDp.dp)
        glass += Glass(this, 0x8C, sizeDp / 2)
    }

    private fun ctrl(res: Int, sizeDp: Int, click: () -> Unit) = ImageView(context).apply {
        setImageResource(res)
        setPadding(10.dp, 10.dp, 10.dp, 10.dp)
        setOnClickListener { click() }
        layoutParams = lp(sizeDp.dp, sizeDp.dp)
    }

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun TextView.shadow() = apply { setShadowLayer(10f, 0f, 2f, 0x99000000.toInt()) }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        /** Hvor bred den myke fadingen rundt klokkekortet er (dp). */
        const val FEATHER = 20
        const val BAR_FEATHER = 16
        const val PILL_FEATHER = 8
    }
}
