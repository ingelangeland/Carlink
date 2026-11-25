package no.carlink.diagnose

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Oppdaterer diagnoseappen selv, og kan installere CarLink på nytt (de to kan redde hverandre).
 * Lastes ned fra samme sted som CarLink og installeres med root.
 */
object Installer {
    private const val BASE = "https://github.com/ingelangeland/Carlink/releases/download/siste/"

    fun version(ctx: Context, pkg: String): Long? = runCatching {
        val p = ctx.packageManager.getPackageInfo(pkg, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    }.getOrNull()

    fun latest(): Long? = runCatching { JSONObject(get(BASE + "version.json").decodeToString()).getLong("versionCode") }.getOrNull()

    /** Last ned [apk] og installer den. Gir en kort tekst om hvordan det gikk. */
    fun install(ctx: Context, apk: String, status: (String) -> Unit): String = runCatching {
        status("Downloading $apk …")
        val f = File(ctx.cacheDir, "install.apk")
        f.writeBytes(get(BASE + apk))
        status("Installing …")
        val tmp = "/data/local/tmp/carlink-install.apk"
        val out = Sh.run("cp '${f.absolutePath}' $tmp && chmod 644 $tmp && pm install -r $tmp; rm -f $tmp", 150)
        f.delete()
        if (out.contains("Success")) "Installed" else "Install failed: ${out.trim().lines().lastOrNull() ?: "?"}"
    }.getOrElse { "Failed: ${it.message ?: it.javaClass.simpleName}" }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "CarLink-diagnose")
        if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
        return c.inputStream.use { it.readBytes() }
    }
}
