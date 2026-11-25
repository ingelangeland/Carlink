package no.carlink.diagnose

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CarLink Diagnose: faner til venstre, innhold til høyre. Alt måles bare mens appen vises –
 * når du går ut av den, stopper alt (ingen tjenester, alarmer eller wake lock).
 */
class DiagnoseActivity : Activity() {
    private lateinit var sections: List<Section>
    private val tabs = ArrayList<TextView>()
    private lateinit var content: FrameLayout
    private var current = 0
    private var visible = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sections = listOf(Overview(this) { sendReport() }, Joystick(this), Learning(this), Light(this), Music(this), Network(this), Sleep(this), Performance(this))
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(C.BG); setPadding(20.dp, 20.dp, 0, 0) }
        val rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rail.add(label("CarLink Diagnose", 20f, C.ACCENT, true).apply { setPadding(8.dp, 0, 0, 14.dp) })
        sections.forEachIndexed { i, s ->
            val t = label(s.title, 17f, C.TEXT, true).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(20.dp, 0, 12.dp, 0); isClickable = true
                setOnClickListener { show(i) }
            }
            tabs += t
            rail.add(t, h = 58.dp, top = 6.dp)
        }
        root.add(android.widget.ScrollView(this).apply { addView(rail) }, 260.dp, LinearLayout.LayoutParams.MATCH_PARENT, right = 20.dp)
        content = FrameLayout(this)
        root.add(content, 0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        setContentView(root)
        current = savedInstanceState(b)
    }

    private fun savedInstanceState(b: Bundle?) = b?.getInt("tab") ?: 0
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("tab", current) }

    override fun onResume() {
        super.onResume()
        visible = true
        askCarLinkToSave { show(current) }
    }

    override fun onPause() {
        super.onPause()
        visible = false
        sections.forEach { it.onHide() }   // stopp all måling når appen ikke vises
    }

    private fun show(i: Int) {
        if (!visible) return
        sections.forEach { it.onHide() }
        current = i
        tabs.forEachIndexed { j, t -> t.background = if (j == i) rounded(0x334FC3F7, 14) else null; t.setTextColor(if (j == i) C.TEXT else C.DIM) }
        content.removeAllViews()
        val v = sections[i].view
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        content.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        sections[i].onShow()
    }

    /** Be CarLink skrive loggen og joystick-læringen til fil før vi leser dem (høyst 1,5 s ventetid). */
    private fun askCarLinkToSave(then: () -> Unit) {
        var done = false
        val finish = { if (!done) { done = true; then() } }
        runCatching {
            sendOrderedBroadcast(Intent("no.carlink.action.SAVE_STATE").setPackage(CarLinkData.PKG), "no.carlink.permission.DIAGNOSE",
                object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { finish() } }, null, RESULT_OK, null, null)
        }.onFailure { finish() }
        content.postDelayed({ finish() }, 1500)
    }

    // ---------------------------------------------------------------- rapport

    private fun sendReport() {
        Toast.makeText(this, "Making the report …", Toast.LENGTH_SHORT).show()
        Thread {
            val now = Date()
            val text = buildString {
                append("CarLink Diagnose report ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now)}\n")
                append("Device: ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
                sections.forEach { s -> append("\n## ${s.title}\n").append(s.reportNow().trim()).append("\n") }
                val log = CarLinkData.log()
                append("\n## CarLink log (last 300 lines)\n").append(log.takeLast(300).joinToString("\n"))
            }.take(400_000)
            val name = "CarLink-diagnose-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(now) + ".txt"
            runOnUiThread { deliver(name, text) }
        }.start()
    }

    private fun deliver(name: String, text: String) {
        var answered = false
        val fallback = {
            if (!answered) {
                answered = true
                Thread {
                    val saved = saveToDownloads(name, text)
                    runOnUiThread { toast(if (saved) "Phone not connected – report saved in Downloads: $name" else "Phone not connected and the report could not be saved") }
                }.start()
            }
        }
        runCatching {
            sendOrderedBroadcast(Intent("no.carlink.action.SEND_REPORT").setPackage(CarLinkData.PKG).putExtra("name", name).putExtra("text", text),
                "no.carlink.permission.DIAGNOSE", object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (answered) return
                        if (resultData == "sent") { answered = true; toast("Report sent to the phone – tap the notification there to share it") } else fallback()
                    }
                }, null, RESULT_OK, null, null)
        }.onFailure { fallback() }
        content.postDelayed({ fallback() }, 8000)
    }

    private fun saveToDownloads(name: String, text: String): Boolean = runCatching {
        val f = java.io.File(cacheDir, name); f.writeText(text)
        val out = Sh.run("mkdir -p /sdcard/Download && cp '${f.absolutePath}' '/sdcard/Download/$name' && echo OK")
        f.delete(); out.contains("OK")
    }.getOrDefault(false)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

}
