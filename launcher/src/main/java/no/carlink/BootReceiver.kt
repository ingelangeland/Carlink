package no.carlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Bilskjermen starter (eller våkner fra «hurtigstart»): åpne CarLink med en gang, så du ikke
 * må trykke Hjem. Skjermens eget oppstartsvalg pekte på Car Launcher PRO, som er avinstallert.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        AppLog.init(ctx); AppLog.i("app", "boot: ${i.action}")
        runCatching {
            ctx.startActivity(Intent(ctx, ReceiverActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
        SystemFix.apply(ctx)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ SystemFix.healJoystick(ctx) }, 2500)
    }
}

/** Små rettelser i bilskjermens oppsett som krever root. Kjøres i bakgrunnen, høyst én gang per oppstart. */
object SystemFix {
    /**
     * Sikkerhetsnett: krasjer CarLink 3 ganger på 2 minutter, slås fabrikkens hjemmeskjerm på igjen,
     * så bilskjermen aldri står uten hjemmeskjerm. Kjøres før appen avsluttes.
     */
    fun installCrashGuard(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val p = ctx.prefs()
                val now = System.currentTimeMillis()
                val times = (p.getString("crashTimes", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
                    .filter { now - it < 120_000 } + now
                p.edit().putString("crashTimes", times.joinToString(",")).commit()
                val homes = p.getString("disabledHomes", "") ?: ""
                if (times.size >= 3 && homes.isNotBlank()) {
                    p.edit().putBoolean("disableFactoryHome", false).putString("disabledHomes", "").commit()
                    val proc = ProcessBuilder("su", "-c", "sh").start()
                    proc.outputStream.bufferedWriter().use { w -> homes.split(',').forEach { w.write("pm enable $it\n") }; w.write("exit\n") }
                    proc.waitFor()
                }
            }
            prev?.uncaughtException(t, e)
        }
    }

    @Volatile private var done = false

    fun apply(ctx: Context) {
        if (done) return
        done = true
        val pkg = ctx.packageName
        Thread {
            // Skjermens eget «start denne appen ved oppstart» → CarLink
            // Fabrikkens hjemmeskjerm av, så bilskjermen bare har CarLink å starte når bilen slås på
            // (bilskjermen avslutter alle apper når bilen slås av). Slås på igjen ved krasj-løkke.
            if (ctx.prefs().getBoolean("disableFactoryHome", true)) {
                val homes = runCatching {
                    ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                        .map { it.activityInfo.packageName }.filter { it != pkg && it != "com.android.settings" }.distinct()
                }.getOrDefault(emptyList())
                if (homes.isNotEmpty()) {
                    Root.run(homes.joinToString("\n") { "pm disable $it" } + "\npm set-home-activity $pkg/no.carlink.ReceiverActivity")
                    ctx.prefs().edit().putString("disabledHomes", homes.joinToString(",")).apply()
                    AppLog.i("app", "factory home switched off: ${homes.joinToString()}")
                }
            }
            val cur = Root.run("settings get system SETTING_LUNCH_FIRST_APP")?.lines()?.lastOrNull { it.isNotBlank() }?.trim()
            if (cur != null && cur != pkg) {
                Root.run("settings put system SETTING_LUNCH_FIRST_APP $pkg")
                AppLog.i("app", "startup app changed from $cur to $pkg")
            }
        }.start()
    }

    /** Musestyringen skal kjøre, men tjenesten er ikke i gang (f.eks. etter en oppdatering): slå den på igjen. */
    fun healJoystick(ctx: Context) {
        val wanted = ctx.prefs().getBoolean("joyMouse", false) || JoyMouseService.homeOnly(ctx)
        if (!wanted || JoyMouseService.running()) return
        Thread {
            Thread.sleep(3000)
            if (JoyMouseService.running()) return@Thread   // koblet til mens vi ventet
            val c = JoyMouseService.COMPONENT
            val d = "$"
            // Ta den ut og inn igjen, så Android kobler den til på nytt
            Root.run("""
                cur=${d}(settings get secure enabled_accessibility_services | sed 's#$c##g; s#::#:#g; s#^:##; s#:${d}##')
                if [ -z "${d}cur" ] || [ "${d}cur" = "null" ]; then settings put secure enabled_accessibility_services $c
                else settings put secure enabled_accessibility_services "${d}cur"; sleep 1; settings put secure enabled_accessibility_services "${d}cur:$c"; fi
                settings put secure accessibility_enabled 1
            """.trimIndent())
            AppLog.i("joystick", "mouse service was not running – re-enabled")
        }.start()
    }
}
