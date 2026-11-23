package no.carlink

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Built-in updates without any app store: checks the same place the APK files are published,
 * downloads the new version and installs it with Android's own installer.
 * Android 12+: after the first self-update, later updates install without a prompt.
 */
object Updater {
    private const val BASE = "https://github.com/ingelangeland/Carlink/releases/download/siste/"
    private const val NOTIF = 45

    data class Info(val versionCode: Long, val versionName: String, val apk: String)

    /** Set by each app: which APK file in the release is this app. */
    @Volatile var apkName: String = ""
    val available = MutableStateFlow<Info?>(null)
    val status = MutableStateFlow("")
    @Volatile private var lastCheck = 0L

    fun currentVersion(ctx: Context): Pair<Long, String> = runCatching {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        (if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()) to (p.versionName ?: "")
    }.getOrDefault(0L to "")

    fun autoCheck(ctx: Context) = ctx.prefs().getBoolean("autoUpdate", true)

    /** Checks for a newer version (at most every [minIntervalMs]). Returns the update, or null if up to date. */
    suspend fun check(ctx: Context, minIntervalMs: Long = 0, notify: Boolean = false): Info? = withContext(Dispatchers.IO) {
        if (SystemClock.elapsedRealtime() - lastCheck < minIntervalMs && lastCheck != 0L) return@withContext available.value
        lastCheck = SystemClock.elapsedRealtime()
        status.value = "Checking for updates …"
        try {
            val c = URL(BASE + "version.json").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "CarLink-updater")
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val vc = j.getLong("versionCode")
            val mine = currentVersion(ctx).first
            val info = if (vc > mine) Info(vc, j.optString("versionName", "1.0.$vc"), apkName) else null
            available.value = info
            status.value = if (info == null) "Up to date" else "Version ${info.versionName} is available"
            if (info != null && notify) notifyAvailable(ctx, info)
            info
        } catch (e: Exception) {
            status.value = "Couldn't check for updates (${e.message ?: "no connection"})"
            null
        }
    }

    /** Downloads and installs the update. Asks once for "install unknown apps" if needed. */
    suspend fun install(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val info = available.value ?: check(ctx) ?: return@withContext false
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            status.value = "Allow CarLink to install updates, then tap Update again"
            withContext(Dispatchers.Main) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return@withContext false
        }
        try {
            status.value = "Downloading version ${info.versionName} …"
            val file = File(ctx.cacheDir, "update.apk")
            val c = URL(BASE + info.apk).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "CarLink-updater")
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { input -> file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L; var lastPct = -1
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != lastPct && pct % 10 == 0) { lastPct = pct; status.value = "Downloading … $pct %" } }
                }
            } }
            status.value = "Installing …"
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val cb = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java).setPackage(ctx.packageName), flags)
                s.commit(cb.intentSender)
            }
            true
        } catch (e: Exception) {
            status.value = "Update failed: ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    private fun notifyAvailable(ctx: Context, info: Info) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("update", "Updates", NotificationManager.IMPORTANCE_DEFAULT))
            val open = (ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return)
                .putExtra("carlink_update", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, 6, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NOTIF, NotificationCompat.Builder(ctx, "update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("CarLink update available")
                .setContentText("Version ${info.versionName} – tap to update")
                .setContentIntent(pi).setAutoCancel(true).build())
        }
    }

    internal fun clearNotification(ctx: Context) = runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF) }
}

/** Result from Android's installer. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Updater.status.value = "Confirm the update"
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> { Updater.status.value = "Updated"; Updater.available.value = null; Updater.clearNotification(ctx) }
            else -> Updater.status.value = "Update not installed: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelled")
        }
    }
}
