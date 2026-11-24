package no.carlink

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Appveksler: åpne apper i et oversiktlig rutenett, i samme glass-stil som app-oversikten.
 * Trykk = bytt til appen. Det lille krysset = lukk appen. «Close all» nederst.
 * Lista over åpne apper hentes fra Android med root (vanlige apper får ikke se den).
 */
@SuppressLint("ViewConstructor")
class RecentsPanel(ctx: Context, blurTarget: View) : GlassOverlay(ctx, blurTarget) {

    private data class Task(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable?)

    private val panel = LinearLayout(ctx)
    private val monitor = ResourceMonitor(ctx)
    private val outer = LinearLayout(ctx)
    override val body: View get() = outer
    private var appMem: Map<String, Int> = emptyMap()
    private val memLabels = HashMap<String, android.widget.TextView>()
    private val title = ctx.label("Open apps", 18f, 0xFFFFFFFF.toInt(), true).apply { gravity = Gravity.CENTER }
    private val empty = ctx.label("No other apps are open", 15f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER }
    private val scroll = android.widget.ScrollView(ctx)
    private val rowsBox = LinearLayout(ctx)
    private val closeAll = ctx.label("Close all", 14f, 0xFFFFFFFF.toInt(), true).apply {
        gravity = Gravity.CENTER; setPadding(22.dp, 10.dp, 22.dp, 10.dp)
    }
    private var tasks: List<Task> = emptyList()
    private var scope: CoroutineScope? = null
    private val colW = 150.dp
    private val rowH = 150.dp

    init {
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        rowsBox.orientation = LinearLayout.VERTICAL
        scroll.apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(40.dp)
            overScrollMode = OVER_SCROLL_NEVER
            addView(rowsBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        panel.addView(empty, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24.dp })
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { topMargin = 14.dp })
        panel.addView(closeAll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 10.dp })
        closeAll.setOnClickListener { closeAllApps() }
        panel.isClickable = true; rowsBox.isClickable = true
        // Åpne apper til venstre, systemressurser i et eget vindu til høyre
        outer.orientation = LinearLayout.HORIZONTAL
        outer.gravity = Gravity.CENTER_VERTICAL
        outer.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        outer.addView(monitor, LinearLayout.LayoutParams(300.dp, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = 28.dp })
        monitor.isClickable = true
        monitor.onAppMemory = { m -> appMem = m; memLabels.forEach { (pkg, tv) -> tv.text = memText(pkg) } }
        addView(outer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    override fun onStart(scope: CoroutineScope) {
        super.onStart(scope)
        this.scope = scope
    }

    override fun onOpening() { refresh(); monitor.start() }
    override fun onClosing() { monitor.stop() }

    private fun memText(pkg: String) = appMem[pkg]?.let { "$it MB" } ?: ""

    override fun onTheme(t: Ambient.Theme) {
        closeAll.background = rounded(Ambient.alpha(Ambient.deep(t.dark), 0x90), 20, 0x33FFFFFF)
        monitor.background = rounded(Ambient.alpha(Ambient.deep(t.dark), 0x70), 22, 0x26FFFFFF)
    }

    private fun refresh() {
        layoutGrid()
        scope?.launch {
            val list = withContext(Dispatchers.IO) { load() }
            tasks = list
            layoutGrid()
        }
    }

    /** Åpne apper fra Android (nyeste først), uten CarLink selv og systemets egne vinduer. */
    private fun load(): List<Task> {
        val out = Root.run("dumpsys activity recents", 6) ?: return emptyList()
        val pm = context.packageManager
        val skip = setOf(context.packageName, "com.android.systemui", "android", JoyMouseService.CAR_SYSTEM)
        val seen = LinkedHashSet<String>()
        Regex("""realActivity=\{?([a-zA-Z0-9_.]+)/""").findAll(out).forEach { seen.add(it.groupValues[1]) }
        val homes = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        return seen.filter { it !in skip && it !in homes && pm.getLaunchIntentForPackage(it) != null }.mapNotNull { p ->
            runCatching {
                val ai = pm.getApplicationInfo(p, 0)
                Task(p, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai))
            }.getOrNull()
        }
    }

    private fun layoutGrid() {
        if (width == 0) return
        empty.visibility = if (tasks.isEmpty()) VISIBLE else GONE
        closeAll.visibility = if (tasks.size > 1) VISIBLE else GONE
        val maxW = min((width * 0.8f).toInt() - 330.dp, 1300.dp)
        val cols = max(1, min(max(tasks.size, 1), maxW / colW))
        val rows = ceil(tasks.size / cols.toFloat()).toInt()
        (panel.layoutParams as LayoutParams).width = max(cols * colW, 360.dp)
        (scroll.layoutParams as LinearLayout.LayoutParams).height = min(rows * rowH, (height * 0.62f).toInt())
        rowsBox.removeAllViews(); memLabels.clear()
        tasks.chunked(cols).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            chunk.forEach { t -> row.addView(tile(t), LinearLayout.LayoutParams(colW, rowH)) }
            rowsBox.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        onTheme(Ambient.theme.value)
        panel.requestLayout()
    }

    private fun tile(t: Task): View = FrameLayout(context).apply {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Ambient.alpha(Ambient.deep(Ambient.theme.value.dark), 0x70), 22, 0x26FFFFFF)
            addView(ImageView(context).apply { setImageDrawable(t.icon) }, LinearLayout.LayoutParams(64.dp, 64.dp))
            addView(context.label(t.label, 13f, 0xFFFFFFFF.toInt()).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                maxWidth = colW - 30.dp; setPadding(0, 10.dp, 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            // Minnebruk for appen (fylles inn mens app-veksleren er åpen)
            addView(context.label(memText(t.pkg), 12f, 0xB3FFFFFF.toInt()).apply {
                gravity = Gravity.CENTER; memLabels[t.pkg] = this
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            isClickable = true
            setOnClickListener { switchTo(t) }
        }
        addView(card, LayoutParams(colW - 18.dp, rowH - 18.dp, Gravity.CENTER))
        // Lukk-kryss oppe til høyre
        addView(context.label("✕", 15f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xCC1A1D22.toInt(), 16, 0x44FFFFFF)
            setOnClickListener { closeApp(t) }
        }, LayoutParams(32.dp, 32.dp, Gravity.TOP or Gravity.END).apply { topMargin = 2.dp; rightMargin = 2.dp })
    }

    private fun switchTo(t: Task) {
        val i = context.packageManager.getLaunchIntentForPackage(t.pkg) ?: return
        // Åpner appens eksisterende vindu der du var (ikke en ny start)
        runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }
        close(immediate = true)
    }

    private fun closeApp(t: Task) {
        tasks = tasks.filter { it.pkg != t.pkg }
        layoutGrid()
        Thread { Root.run("am force-stop ${t.pkg}") }.start()
    }

    private fun closeAllApps() {
        val all = tasks.map { it.pkg }
        tasks = emptyList(); layoutGrid()
        Thread { Root.run(all.joinToString("\n") { "am force-stop $it" }) }.start()
    }
}
