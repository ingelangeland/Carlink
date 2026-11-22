package no.carlink

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * App-oversikten som legger seg over hele skjermen:
 *  - appene sentrert, toner inn/ut med en liten zoom
 *  - skjermen bak blir uskarp og får et røykfarget filter (coverets mørke farge),
 *    nesten klart bak appene og gradvis mørkere mot kantene – toner inn/ut samtidig
 *  - øverst: velg om apper åpnes i fullskjerm, høyre tredjedel, delt skjerm eller vindu
 */
@SuppressLint("ViewConstructor")
class AppDrawer(ctx: Context, private val blurTarget: View) : ScopedFrame(ctx) {


    var isOpen = false
        private set

    private val smoke = View(ctx)
    private val smokeBg = GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT }
    private val panel = LinearLayout(ctx)
    private val modeRow = LinearLayout(ctx)
    private val hint = ctx.label("", 12f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER }
    private val scroll = android.widget.ScrollView(ctx)
    private val rowsBox = LinearLayout(ctx)
    private var lastCols = -1
    private var apps: List<AppCatalog.App> = emptyList()
    private var anim: ValueAnimator? = null
    private var progress = 0f
    private val colW = 112.dp
    private val rowH = 118.dp

    init {
        visibility = INVISIBLE
        isClickable = true                      // fanger trykk så de ikke går til skjermen bak
        setOnClickListener { close() }          // trykk utenfor appene lukker
        smoke.background = smokeBg
        addView(smoke, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER_HORIZONTAL
        modeRow.orientation = LinearLayout.HORIZONTAL
        modeRow.gravity = Gravity.CENTER
        panel.addView(modeRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dp })
        rowsBox.orientation = LinearLayout.VERTICAL
        scroll.apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true          // mange apper: toner mykt ut i bunnen når man ruller
            setFadingEdgeLength(48.dp)
            overScrollMode = OVER_SCROLL_NEVER
            addView(rowsBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { topMargin = 14.dp })
        // Trykk i selve app-området (mellom appene, rundt valgknappene) skal IKKE lukke – bare trykk utenfor
        panel.isClickable = true
        rowsBox.isClickable = true
        modeRow.isClickable = true
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        buildModeRow()
    }

    override fun onStart(scope: CoroutineScope) {
        scope.launch { Ambient.theme.collect { applyTheme(it) } }
        // Rutenettet bygges på forhånd (skjult), så det er klart når oversikten åpnes
        scope.launch { AppCatalog.apps.collect { apps = it; lastCols = -1; layoutGrid() } }

    }

    // ------------------------------------------------------------------ åpne / lukke

    fun open() {
        if (isOpen) return
        isOpen = true
        visibility = VISIBLE
        buildModeRow()
        layoutGrid()
        Ambient.overlayOpen = true
        animateTo(1f, 240)
    }

    fun close(immediate: Boolean = false) {
        if (!isOpen) return
        isOpen = false
        Ambient.overlayOpen = false
        if (immediate) { anim?.cancel(); setProgress(0f); visibility = INVISIBLE; return }
        animateTo(0f, 200)
    }

    private fun animateTo(target: Float, ms: Long) {
        anim?.cancel()
        // Appene animeres som ett ferdig bilde (grafikklag) i stedet for å tegnes på nytt hvert bilde
        panel.setLayerType(LAYER_TYPE_HARDWARE, null)
        anim = ValueAnimator.ofFloat(progress, target).apply {
            duration = ms
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    panel.setLayerType(LAYER_TYPE_NONE, null)
                    if (!isOpen && progress <= 0.001f) visibility = INVISIBLE
                }
            })
            start()
        }
    }

    /** p = 0 (skjult) … 1 (helt åpen): uskarphet, røyk og apper følger hverandre. */
    private var blurStep = -1

    private fun setProgress(p: Float) {
        progress = p
        smoke.alpha = p
        panel.alpha = p
        val s = 0.94f + 0.06f * p
        panel.scaleX = s; panel.scaleY = s
        if (Build.VERSION.SDK_INT >= 31) {
            // Uskarpheten oppdateres i 5 trinn i stedet for hvert bilde: bakgrunnen står stille mellom
            // trinnene og trenger ikke regnes ut på nytt → jevn animasjon også på svakere bilskjermer
            val step = kotlin.math.ceil(p * 5f).toInt().coerceIn(0, 5)
            if (step != blurStep) {
                blurStep = step
                val r = 26f * resources.displayMetrics.density * step / 5f
                blurTarget.setRenderEffect(if (step == 0) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
            }
        }
    }

    // ------------------------------------------------------------------ utseende

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        smokeBg.gradientRadius = hypot(w.toFloat(), h.toFloat()) / 2f
        post { layoutGrid() }
    }

    private fun applyTheme(t: Ambient.Theme) {
        val d = Ambient.deep(t.dark)
        // Nesten klart bak appene, gradvis mørkere mot kanten («røykfarget», ikke svart)
        smokeBg.setColors(
            intArrayOf(Ambient.alpha(d, 0x2E), Ambient.alpha(d, 0x38), Ambient.alpha(d, 0x8C), Ambient.alpha(d, 0xC4)),
            floatArrayOf(0f, 0.38f, 0.72f, 1f),
        )
        styleModeRow(t)
    }

    /**
     * Appene legges i rader som hver er sentrert (også den siste, ufullstendige raden).
     * Bruker opptil 88 % av bredden og 72 % av høyden; flere apper enn det → rulling.
     */
    private fun layoutGrid() {
        if (width == 0 || apps.isEmpty()) return
        val maxW = min((width * 0.88f).toInt(), 1400.dp)
        val cols = max(1, min(apps.size, maxW / colW))
        val panelW = cols * colW
        val rows = ceil(apps.size / cols.toFloat()).toInt()
        val boxH = min(rows * rowH, (height * 0.72f).toInt())
        (panel.layoutParams as LayoutParams).width = panelW
        (scroll.layoutParams as LinearLayout.LayoutParams).height = boxH
        if (cols != lastCols || rowsBox.childCount != rows) {
            lastCols = cols
            rowsBox.removeAllViews()
            apps.chunked(cols).forEach { chunk ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                chunk.forEach { a -> row.addView(appTile(a), LinearLayout.LayoutParams(colW, rowH)) }
                rowsBox.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        panel.requestLayout()
    }

    /**
     * En rute i rutenettet. Bare selve ikonet + navnet kan trykkes; det tomme rommet rundt
     * (mellom appene) gjør ingenting, så man ikke åpner en app ved et uhell.
     */
    private fun appTile(a: AppCatalog.App): View = FrameLayout(context).apply {
        val hit = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            addView(ImageView(context).apply { setImageDrawable(a.icon) }, LinearLayout.LayoutParams(64.dp, 64.dp))
            addView(context.label(a.label, 13f, 0xFFFFFFFF.toInt()).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                maxWidth = colW - 16.dp
                setPadding(0, 8.dp, 0, 0); setShadowLayer(8f, 0f, 1f, 0x99000000.toInt())
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            isClickable = true
            setOnClickListener { openApp(a, Launch.defaultMode(context)) }
            setOnLongClickListener { v -> appMenu(v, a); true }
        }
        addView(hit, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = 10.dp })
    }

    private fun buildModeRow() {
        modeRow.removeAllViews()
        Launch.Mode.entries.forEach { m ->
            modeRow.addView(context.label(m.label, 14f, 0xFFFFFFFF.toInt(), true).apply {
                tag = m
                gravity = Gravity.CENTER
                setPadding(18.dp, 9.dp, 18.dp, 9.dp)
                setOnClickListener { Launch.setDefaultMode(context, m); styleModeRow(Ambient.theme.value) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 4.dp; rightMargin = 4.dp
            })
        }
        styleModeRow(Ambient.theme.value)
    }

    private fun styleModeRow(t: Ambient.Theme) {
        val cur = Launch.defaultMode(context)
        val darkText = ColorUtils.calculateLuminance(t.accent) > 0.5
        for (i in 0 until modeRow.childCount) {
            val v = modeRow.getChildAt(i) as TextView
            val on = v.tag == cur
            v.background = if (on) rounded(t.accent, 20) else rounded(Ambient.alpha(Ambient.deep(t.dark), 0x80), 20, 0x33FFFFFF)
            v.setTextColor(if (on && darkText) 0xFF101418.toInt() else 0xFFFFFFFF.toInt())
        }
        hint.text = when {
            cur == Launch.Mode.RIGHT_THIRD && !Launch.windowsSupported(context) ->
                "Skjermen har ikke vindusmodus – appen åpnes i delt skjerm. Dra skillelinjen til ⅓."
            cur == Launch.Mode.WINDOW && !Launch.windowsSupported(context) ->
                "Skjermen har ikke vindusmodus – appen åpnes i fullskjerm."
            else -> "Trykk for å åpne · hold inne for flere valg"
        }
    }

    // ------------------------------------------------------------------ apper

    private fun openApp(a: AppCatalog.App, mode: Launch.Mode) {
        Launch.app(context, a.component, mode)
        close(immediate = mode == Launch.Mode.FULL)
    }

    private fun appMenu(anchor: View, a: AppCatalog.App) {
        PopupMenu(context, anchor).apply {
            Launch.Mode.entries.forEachIndexed { i, m -> menu.add(0, i, i, "Åpne: ${m.label}") }
            val fav = Favorites.contains(context, a.component)
            menu.add(0, 100, 100, if (fav) "Fjern fra Hjem" else "Legg til på Hjem")
            setOnMenuItemClickListener {
                if (it.itemId == 100) {
                    if (fav) Favorites.remove(context, a.component) else Favorites.add(context, a.component)
                } else openApp(a, Launch.Mode.entries[it.itemId])
                true
            }
            show()
        }
    }
}
