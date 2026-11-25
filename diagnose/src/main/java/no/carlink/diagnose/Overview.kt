package no.carlink.diagnose

import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout

/** Forsiden: versjoner, systemressurser og knapper for å starte på nytt, oppdatere og sende rapport. */
class Overview(ctx: Context, private val sendReport: () -> Unit) : Section(ctx, "Overview") {
    private val res = ResourcesView(ctx)
    private var status = ""

    data class D(val carlink: Long?, val diag: Long?, val latest: Long?)

    override fun collect(): Any = D(Installer.version(ctx, CarLinkData.PKG), Installer.version(ctx, ctx.packageName), Installer.latest())

    override fun render(d: Any) {
        d as D
        val top = ctx.hrow().apply { gravity = android.view.Gravity.TOP }
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val v = ctx.card()
        v.add(ctx.label("Versions", 18f, C.TEXT, true))
        fun ver(name: String, have: Long?) {
            val newer = have != null && d.latest != null && d.latest > have
            kv(v, name, when { have == null -> "not installed"; newer -> "build $have · build ${d.latest} available"; else -> "build $have" },
                when { have == null -> C.BAD; newer -> C.WARN; else -> C.OK })
        }
        ver("CarLink", d.carlink); ver("CarLink Diagnose", d.diag)
        if (d.latest == null) kv(v, "Latest version", "couldn't check (no internet?)", C.DIM)
        if (status.isNotBlank()) v.add(ctx.label(status, 15f, C.ACCENT), top = 10.dp)
        left.add(v)

        val a = ctx.card()
        a.add(ctx.label("Actions", 18f, C.TEXT, true))
        val r1 = ctx.hrow()
        r1.add(ctx.button("Send report to the phone", 0x664FC3F7) { sendReport() }, LinearLayout.LayoutParams.WRAP_CONTENT, right = 12.dp)
        r1.add(ctx.button("Open CarLink") { ctx.packageManager.getLaunchIntentForPackage(CarLinkData.PKG)?.let { ctx.startActivity(it) } }, LinearLayout.LayoutParams.WRAP_CONTENT)
        a.add(r1, top = 12.dp)
        val r2 = ctx.hrow()
        r2.add(ctx.button("Update Diagnose") { install("CarLink-Diagnose.apk") }, LinearLayout.LayoutParams.WRAP_CONTENT, right = 12.dp)
        r2.add(ctx.button(if (d.carlink == null) "Install CarLink" else "Update / reinstall CarLink") { install("CarLink-bilskjerm.apk") }, LinearLayout.LayoutParams.WRAP_CONTENT)
        a.add(r2, top = 12.dp)
        val r3 = ctx.hrow()
        r3.add(ctx.button("Restart CarLink") { confirm("Restart CarLink?", "CarLink closes and opens again.") {
            Sh.run("am force-stop ${CarLinkData.PKG}; sleep 1; am start -n ${CarLinkData.PKG}/no.carlink.ReceiverActivity") } },
            LinearLayout.LayoutParams.WRAP_CONTENT, right = 12.dp)
        r3.add(ctx.button("Restart the screen", 0x55FF5A52) { confirm("Restart the screen?", "The whole car screen restarts (takes about a minute).") { Sh.run("svc power reboot || reboot") } },
            LinearLayout.LayoutParams.WRAP_CONTENT)
        a.add(r3, top = 12.dp)
        left.add(a, top = 14.dp)

        top.add(left, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, right = 14.dp)
        (res.parent as? android.view.ViewGroup)?.removeView(res)
        top.add(res, 340.dp)
        body.add(top, top = 14.dp)
        body.add(ctx.label("This app only measures while it is open on the screen – it never runs in the background or keeps the screen awake.", 13f, C.DIM), top = 16.dp)
    }

    private fun install(apk: String) {
        Thread {
            val r = Installer.install(ctx, apk) { s -> status = s; main.post { data?.let { body.removeAllViews(); header(); render(it) } } }
            status = r; main.post { refresh() }
        }.start()
    }

    private fun confirm(title: String, msg: String, run: () -> Unit) {
        AlertDialog.Builder(ctx).setTitle(title).setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> Thread { run() }.start() }.setNegativeButton("No", null).show()
    }

    override fun onShow() { super.onShow(); res.start() }
    override fun onHide() { res.stop() }

    override fun report(d: Any): String { d as D
        return "CarLink build ${d.carlink ?: "not installed"} · Diagnose build ${d.diag} · latest ${d.latest ?: "?"}\nSystem now: ${ResourcesView.snapshot()}"
    }
}
