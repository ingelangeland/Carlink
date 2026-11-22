package no.carlink

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rutenett med appene på skjermenheten. Trykk = åpne, langt trykk = delt skjerm / vindu. */
@SuppressLint("ViewConstructor")
class AppsPanel(ctx: Context) : ScopedFrame(ctx) {

    data class App(val label: String, val component: ComponentName, val icon: Drawable)

    private var apps: List<App> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(i: Int) = apps[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
            val v = (convert as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(4.dp, 10.dp, 4.dp, 10.dp)
                addView(ImageView(context), LinearLayout.LayoutParams(52.dp, 52.dp))
                addView(context.label("", 12f, C.TEXT).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, 6.dp, 0, 0)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            val a = apps[i]
            (v.getChildAt(0) as ImageView).setImageDrawable(a.icon)
            (v.getChildAt(1) as TextView).text = a.label
            return v
        }
    }

    private val grid = GridView(ctx).apply {
        numColumns = GridView.AUTO_FIT
        columnWidth = 84.dp
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        verticalSpacing = 4.dp
        setPadding(8.dp, 4.dp, 8.dp, 8.dp)
        clipToPadding = false
        selector = ColorDrawable(Color.TRANSPARENT)
        adapter = this@AppsPanel.adapter
        setOnItemClickListener { _, _, i, _ -> Launch.app(context, apps[i].component, Launch.Mode.FULL) }
        setOnItemLongClickListener { _, v, i, _ -> menu(v, apps[i]); true }
    }

    init {
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onStart(scope: CoroutineScope) {
        if (apps.isNotEmpty()) return
        scope.launch {
            apps = withContext(Dispatchers.IO) { load() }
            adapter.notifyDataSetChanged()
        }
    }

    private fun load(): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { App(it.loadLabel(pm).toString(), ComponentName(it.activityInfo.packageName, it.activityInfo.name), it.loadIcon(pm)) }
            .sortedWith(compareBy<App>({ it.component.packageName != Launch.MAPS }, { it.label.lowercase() }))
    }

    private fun menu(anchor: View, app: App) {
        PopupMenu(context, anchor).apply {
            menu.add(0, 1, 0, "Åpne ved siden av (delt skjerm)")
            menu.add(0, 2, 1, "Åpne som flytende vindu")
            menu.add(0, 3, 2, "Åpne i fullskjerm")
            menu.add(0, 4, 3, "App-info")
            val fav = Favorites.contains(context, app.component)
            menu.add(0, 5, 4, if (fav) "Fjern fra Hjem" else "Legg til på Hjem")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> Launch.app(context, app.component, Launch.Mode.ADJACENT)
                    2 -> Launch.app(context, app.component, Launch.Mode.WINDOW)
                    3 -> Launch.app(context, app.component, Launch.Mode.FULL)
                    4 -> Launch.start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.component.packageName)))
                    5 -> if (Favorites.contains(context, app.component)) Favorites.remove(context, app.component) else Favorites.add(context, app.component)
                }
                true
            }
            show()
        }
    }
}
