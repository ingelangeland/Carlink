package no.carlink

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.InputDevice
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Systemrapport for bilskjermen: finner programmet som gjør joysticken om til Tab/Enter,
 * og om skjermen har root. Leser bare – endrer ingenting.
 */
object SystemReport {

    private val interestingPerms = listOf(
        "android.permission.INJECT_EVENTS", "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.MANAGE_USB", "android.permission.READ_INPUT_STATE",
        "android.permission.SET_POINTER_SPEED", "android.permission.MONITOR_INPUT",
        "android.permission.STATUS_BAR_SERVICE", "android.permission.INTERNAL_SYSTEM_WINDOW",
        "android.permission.SYSTEM_ALERT_WINDOW", "android.permission.CHANGE_COMPONENT_ENABLED_STATE",
    )
    private val hintWords = Regex("(?i)key|input|mouse|touch|joy|remote|knob|canbus|can\\.|mcu|xhd|lexus|toyota|car|wheel|steer|swc|navi|launcher|ui\\.|service|hal")

    fun build(ctx: Context): String = buildString {
        append("CarLink system report ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\n")
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.DEVICE} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n")

        append("## System properties\n")
        for (k in listOf("ro.build.type", "ro.build.tags", "ro.debuggable", "ro.secure", "ro.adb.secure",
                "ro.build.display.id", "ro.build.fingerprint", "ro.build.version.security_patch", "ro.product.board",
                "ro.board.platform", "persist.sys.usb.config", "sys.usb.config", "sys.usb.state", "service.adb.tcp.port",
                "persist.adb.tcp.port", "init.svc.adbd", "ro.boot.selinux", "ro.build.selinux", "persist.sys.locale",
                "ro.product.vendor.model", "ro.vendor.build.fingerprint")) {
            append("$k = ${prop(k)}\n")
        }

        append("\n## Root\n")
        val suPaths = listOf("/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su", "/vendor/bin/su",
            "/system/app/Superuser.apk", "/system/app/SuperSU", "/data/adb/magisk", "/system/xbin/daemonsu")
        for (p in suPaths) if (File(p).exists()) append("found: $p\n")
        append("su -c id: ${run("su", "-c", "id")}\n")
        append("id: ${run("id")}\n")
        append("getenforce: ${run("getenforce")}\n")

        append("\n## Input devices (Android)\n")
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            append("#$id \"${d.name}\" descriptor=${d.descriptor.take(12)} vendor=${d.vendorId} product=${d.productId} sources=0x${Integer.toHexString(d.sources)}\n")
        }
        append("\n## /proc/bus/input/devices\n")
        append(runCatching { File("/proc/bus/input/devices").readText() }.getOrElse { "not readable: ${it.message}" })
        append("\n## /dev/input\n")
        File("/dev/input").listFiles()?.sortedBy { it.name }?.forEach {
            append("${it.name} read=${it.canRead()} write=${it.canWrite()}\n")
        } ?: append("not listable\n")

        append("\n## Accessibility\n")
        append("enabled services: ${Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services")}\n")

        append("\n## Settings that may control the joystick\n")
        for ((name, uri) in listOf("system" to Settings.System.CONTENT_URI, "global" to Settings.Global.CONTENT_URI, "secure" to Settings.Secure.CONTENT_URI)) {
            runCatching {
                ctx.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val k = c.getString(0) ?: continue; val v = c.getString(1)
                        if (name == "system" || hintWords.containsMatchIn(k)) append("$name: $k = $v\n")
                    }
                }
            }.onFailure { append("$name: not readable (${it.message})\n") }
        }

        append("\n## Packages that can send keys or watch input (most likely candidates first)\n")
        val pm = ctx.packageManager
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.MATCH_DISABLED_COMPONENTS
        @Suppress("DEPRECATION")
        val pkgs = runCatching { pm.getInstalledPackages(flags) }.getOrElse {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }
        data class Row(val score: Int, val text: String)
        val rows = pkgs.map { p ->
            val ai = p.applicationInfo
            val perms = p.requestedPermissions?.filter { it in interestingPerms }.orEmpty()
            val sys = ai != null && ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val label = runCatching { ai?.let { pm.getApplicationLabel(it).toString() } }.getOrNull() ?: ""
            val services = p.services?.map { it.name.substringAfterLast('.') + (it.permission?.let { pr -> "[${pr.substringAfterLast('.')}]" } ?: "") }.orEmpty()
            val score = (if ("android.permission.INJECT_EVENTS" in perms) 100 else 0) +
                perms.size * 10 + (if (hintWords.containsMatchIn(p.packageName)) 5 else 0) + (if (sys) 1 else 0)
            Row(score, buildString {
                append("${p.packageName} \"$label\" ")
                append(if (sys) "system" else "user")
                if (ai?.sourceDir != null) append(" ${ai.sourceDir}")
                if (ai?.enabled == false) append(" DISABLED")
                if (p.sharedUserId != null) append(" uid=${p.sharedUserId}")
                append(" v=${p.versionName}\n")
                if (perms.isNotEmpty()) append("    perms: ${perms.joinToString { it.substringAfterLast('.') }}\n")
                if (services.isNotEmpty()) append("    services: ${services.take(25).joinToString()}\n")
            })
        }.sortedByDescending { it.score }
        rows.forEach { append(it.text) }
        append("\n(${rows.size} packages)\n")
    }

    /** Lager rapporten, lagrer den i Nedlastinger og sender den til telefonen. */
    fun runAndSend(ctx: Context): String {
        val text = runCatching { build(ctx) }.getOrElse { "Report failed: $it" }
        val name = "CarLink-system-report-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".txt"
        val saved = save(ctx, name, text)
        val sent = Hub.send(JSONObject().put("t", "joylog").put("name", name).put("text", text))
        return when {
            sent -> "System report sent to the phone – tap the notification there to share it"
            saved -> "Phone not connected – report saved in Downloads: $name"
            else -> "Phone not connected and the report could not be saved"
        }
    }

    private fun save(ctx: Context, name: String, text: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 29) {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val f = runCatching { File(dir, name).also { it.writeText(text) } }.getOrNull()
                ?: File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, name).also { it.writeText(text) }
            return@runCatching f.exists()
        }
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri: Uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        true
    }.getOrDefault(false)

    private fun prop(k: String): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java).invoke(null, k) as String
    }.getOrDefault("?").ifEmpty { "(empty)" }

    /** Kjører en kommando med tidsgrense (så en hengende «su» ikke låser appen). */
    private fun run(vararg cmd: String): String = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (!p.waitFor(6, TimeUnit.SECONDS)) { p.destroy(); return@runCatching "timeout" }
        p.inputStream.bufferedReader().readText().trim().take(300).ifEmpty { "(exit ${p.exitValue()})" }
    }.getOrElse { "not available (${it.javaClass.simpleName})" }
}
