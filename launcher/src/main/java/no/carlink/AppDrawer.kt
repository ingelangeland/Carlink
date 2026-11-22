package no.carlink

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
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

    data class App(val label: String, val component: ComponentName, val icon: Drawable)

    var isOpen = false
        private set

    private val smoke = View(ctx)
    private val smokeBg = GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT }
    private val panel = LinearLayout(ctx)
    private val modeRow = LinearLayout(ctx)
    private val hint = ctx.label("", 12f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER }
    private val grid = GridView(ctx)
    private var apps: List<App> = emptyList()
    private var anim: ValueAnimator? = null
    private var progress = 0f
    private val colW = 112.dp
    private val rowH = 118.dp

    private val adapter = object : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(i: Int) = apps[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
            val v = (convert as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(6.dp, 12.dp, 6.dp, 10.dp)
                addView(ImageView(context), LinearLayout.LayoutParams(64.dp, 64.dp))
                addView(context.label("", 13f, 0xFFFFFFFF.toInt()).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                    setPadding(0, 8.dp, 0, 0); setShadowLayer(8f, 0f, 1f, 0x99000000.toInt())
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            val a = apps[i]
            (v.getChildAt(0) as ImageView).setImageDrawable(a.icon)
            (v.getChildAt(1) as TextView).text = a.label
            return v
        }
    }

    init {
        visibility = GONE
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
        grid.apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = colW
            stretchMode = GridView.STRETCH_SPACING_UNIFORM
            gravity = Gravity.CENTER
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            adapter = this@AppDrawer.adapter
            setOnItemClickListener { _, _, i, _ -> openApp(apps[i], Launch.defaultMode(context)) }
            setOnItemLongClickListener { _, v, i, _ -> appMenu(v, apps[i]); true }
        }
        panel.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { topMargin = 14.dp })
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        buildModeRow()
    }

    override fun onStart(scope: CoroutineScope) {
        scope.launch { Ambient.theme.collect { applyTheme(it) } }
        scope.launch { Favorites.changed.collect { if (isOpen) adapter.notifyDataSetChanged() } }
    }

    // ------------------------------------------------------------------ åpne / lukke

    fun open() {
        if (isOpen) return
        isOpen = true
        visibility = VISIBLE
        buildModeRow()
        if (apps.isEmpty()) loadApps() else layoutGrid()
        animateTo(1f, 240)
    }

    fun close(immediate: Boolean = false) {
        if (!isOpen) return
        isOpen = false
        if (immediate) { anim?.cancel(); setProgress(0f); visibility = GONE; return }
        animateTo(0f, 200)
    }

    private fun animateTo(target: Float, ms: Long) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(progress, target).apply {
            duration = ms
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (!isOpen && progress <= 0.001f) visibility = GONE }
            })
            start()
        }
    }

    /** p = 0 (skjult) … 1 (helt åpen): uskarphet, røyk og apper følger hverandre. */
    private fun setProgress(p: Float) {
        progress = p
        smoke.alpha = p
        panel.alpha = p
        val s = 0.94f + 0.06f * p
        panel.scaleX = s; panel.scaleY = s
        if (Build.VERSION.SDK_INT >= 31) {
            val r = 26f * resources.displayMetrics.density * p
            blurTarget.setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
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

    /** Rutenettet sentreres og får bare så mange rader som trengs (maks ca. 70 % av høyden). */
    private fun layoutGrid() {
        if (width == 0 || apps.isEmpty()) return
        val maxW = min((width * 0.78f).toInt(), 1100.dp)
        val cols = max(1, min(apps.size, maxW / colW))
        val panelW = cols * colW + 24.dp
        val rows = ceil(apps.size / cols.toFloat()).toInt()
        val gridH = min(rows * rowH, (height * 0.68f).toInt())
        (panel.layoutParams as LayoutParams).width = panelW
        (grid.layoutParams as LinearLayout.LayoutParams).height = gridH
        panel.requestLayout()
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

    private fun loadApps() {
        val pm = context.packageManager
        kotlinx.coroutines.MainScope().launch {
            apps = withContext(Dispatchers.IO) {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .filter { it.activityInfo.packageName != context.packageName }
                    .map { App(it.loadLabel(pm).toString(), ComponentName(it.activityInfo.packageName, it.activityInfo.name), it.loadIcon(pm)) }
                    .sortedBy { it.label.lowercase() }
            }
            adapter.notifyDataSetChanged()
            layoutGrid()
        }
    }

    private fun openApp(a: App, mode: Launch.Mode) {
        Launch.app(context, a.component, mode)
        close(immediate = mode == Launch.Mode.FULL)
    }

    private fun appMenu(anchor: View, a: App) {
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
