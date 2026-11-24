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
class AppDrawer(ctx: Context, blurTarget: View) : GlassOverlay(ctx, blurTarget) {


    private val panel = LinearLayout(ctx)
    override val body: View get() = panel
    private val modeRow = LinearLayout(ctx)
    private val hint = ctx.label("", 12f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER }
    private val scroll = android.widget.ScrollView(ctx)
    private val rowsBox = LinearLayout(ctx)
    private var lastCols = -1
    private var apps: List<AppCatalog.App> = emptyList()
    private val colW = 112.dp
    private val rowH = 118.dp

    init {
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
        super.onStart(scope)
        // Rutenettet bygges på forhånd (skjult), så det er klart når oversikten åpnes
        scope.launch { AppCatalog.apps.collect { apps = it; lastCols = -1; layoutGrid() } }

    }

    override fun onOpening() {
        buildModeRow()
        layoutGrid()
    }

    // ------------------------------------------------------------------ utseende

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { layoutGrid() }
    }

    override fun onTheme(t: Ambient.Theme) = styleModeRow(t)

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
                "This screen has no window mode – the app opens in split screen. Drag the divider to ⅓."
            cur == Launch.Mode.WINDOW && !Launch.windowsSupported(context) ->
                "This screen has no window mode – the app opens full screen."
            else -> "Tap to open · hold for more options"
        }
    }

    // ------------------------------------------------------------------ apper

    private fun openApp(a: AppCatalog.App, mode: Launch.Mode) {
        Launch.app(context, a.component, mode)
        close(immediate = mode == Launch.Mode.FULL)
    }

    /** Valg for en app (dialog i stedet for liten meny – virker godt med joysticken). */
    private fun appMenu(anchor: View, a: AppCatalog.App) {
        val fav = Favorites.contains(context, a.component)
        val items = Launch.Mode.entries.map { m -> "Open: ${m.label}" to { openApp(a, m) } } +
            ((if (fav) "Remove from Home" else "Add to Home") to {
                if (fav) Favorites.remove(context, a.component)
                else if (Favorites.count(context) >= HomeEditor.MAX)
                    android.widget.Toast.makeText(context, "Home is full (${HomeEditor.MAX}) – remove one first", android.widget.Toast.LENGTH_LONG).show()
                else Favorites.add(context, a.component)
            })
        android.app.AlertDialog.Builder(context).setTitle(a.label)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Cancel", null).show()
    }
}
