package no.carlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Samarbeid med «CarLink Diagnose» (egen app på bilskjermen):
 *  - CarLink kan installere/oppdatere og åpne den (Settings → Updates)
 *  - Diagnoseappen ber CarLink lagre loggen sin før den leses, og sender rapporten til telefonen
 *    gjennom CarLinks forbindelse. Bare apper signert med samme nøkkel får lov (egen tillatelse).
 * Ingenting her kjører i bakgrunnen.
 */
object DiagnoseLink {
    const val PKG = "no.carlink.diagnose"
    private const val BASE = "https://github.com/ingelangeland/Carlink/releases/download/siste/"
    @Volatile var status = ""

    fun installedVersion(ctx: Context): Long? = runCatching {
        val p = ctx.packageManager.getPackageInfo(PKG, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    }.getOrNull()

    fun open(ctx: Context): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(PKG) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    /** Laster ned diagnoseappen fra samme sted som CarLink og installerer den med root. Kjøres i en egen tråd. */
    fun installOrUpdate(ctx: Context): Boolean = runCatching {
        status = "Checking …"
        val latest = JSONObject(get(BASE + "version.json").decodeToString()).getLong("versionCode")
        val have = installedVersion(ctx)
        if (have != null && have >= latest) { status = "Up to date"; return true }
        status = "Downloading …"
        val f = File(ctx.cacheDir, "diagnose.apk")
        f.writeBytes(get(BASE + "CarLink-Diagnose.apk"))
        status = "Installing …"
        val out = Root.run("cp '${f.absolutePath}' /data/local/tmp/carlink-diagnose.apk && chmod 644 /data/local/tmp/carlink-diagnose.apk && " +
            "pm install -r /data/local/tmp/carlink-diagnose.apk; rm -f /data/local/tmp/carlink-diagnose.apk", 120).orEmpty()
        f.delete()
        val ok = out.contains("Success")
        status = if (ok) "Installed" else "Install failed: ${out.trim().lines().lastOrNull() ?: "no root"}"
        AppLog.i("update", "CarLink Diagnose: $status")
        ok
    }.getOrElse { status = "Failed: ${it.message ?: it.javaClass.simpleName}"; false }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "CarLink-updater")
        if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
        return c.inputStream.use { it.readBytes() }
    }
}

/** Tar imot fra diagnoseappen: «lagre nå» og «send rapporten til telefonen». */
class DiagnoseReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            "no.carlink.action.SAVE_STATE" -> {
                runCatching { AppLog.saveNow() }
                runCatching { JoyMouseService.instance?.learn?.save() }
                resultData = "saved"
            }
            "no.carlink.action.SEND_REPORT" -> {
                val name = i.getStringExtra("name") ?: "CarLink-diagnose.txt"
                val text = i.getStringExtra("text").orEmpty()
                AppLog.i("app", "diagnose report sent ($name, ${text.length / 1024} kB)")
                val sent = Hub.send(JSONObject().put("t", "joylog").put("name", name).put("text", text))
                resultData = if (sent) "sent" else "offline"
            }
        }
    }
}
