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
    @Volatile private var done = false

    /** Bilskjermens sidepanel (fanen på kanten av skjermen, en del av com.spd.home). */
    const val SIDE_PANEL = "com.spd.home/com.spd.ex.window.ExtendsWindowService"
    fun sidePanel(ctx: Context, show: Boolean) {
        ctx.prefs().edit().putBoolean("hideSidePanel", !show).apply()
        AppLog.i("app", "car screen side panel ${if (show) "shown" else "hidden"}")
        Thread {
            Root.run(if (show) "pm enable $SIDE_PANEL; am startservice -n $SIDE_PANEL -a com.spd.ex.window.show"
                     else "am stopservice -n $SIDE_PANEL; pm disable $SIDE_PANEL")
        }.start()
    }

    /**
     * Alt i én root-kommando (raskere oppstart):
     *  - bilskjermens egne hjemmeskjermer skal være PÅ (com.spd.home styrer oppstarten; Launcher3 startes
     *    direkte når bilen slås på – CarLink tar over etterpå) – rydder opp etter en tidligere versjon
     *  - CarLink er valgt hjemmeskjerm og står i skjermens «start ved oppstart»
     *  - logger hva som vises nå, og (én gang) hvilket signal som starter ZLink når bilen slås på
     */
    fun apply(ctx: Context) {
        if (done) return
        done = true
        val pkg = ctx.packageName
        val p = ctx.prefs()
        val probe = !p.getBoolean("accProbeDone", false)
        Thread {
            val out = Root.run(buildString {
                append("pm enable com.spd.home >/dev/null 2>&1\n")
                if (!p.getBoolean("homesRestored", false)) append("pm enable com.android.launcher3 >/dev/null 2>&1\n")
                append("pm set-home-activity $pkg/no.carlink.ReceiverActivity >/dev/null 2>&1\n")
                append("[ \"$(settings get system SETTING_LUNCH_FIRST_APP)\" = \"$pkg\" ] || { settings put system SETTING_LUNCH_FIRST_APP $pkg; echo LAUNCH_CHANGED; }\n")
                if (p.getBoolean("hideSidePanel", false)) append("am stopservice -n $SIDE_PANEL >/dev/null 2>&1; pm disable $SIDE_PANEL >/dev/null 2>&1\n")
                append("echo TOP; dumpsys activity activities | grep -m 2 mResumedActivity\n")
                if (probe) append("echo ZLINK; dumpsys package com.zjinnova.zlink | grep -B2 -A8 StartupBroadcastReceiver | head -30\n")
            }, 30) ?: return@Thread
            p.edit().putBoolean("homesRestored", true).remove("disableFactoryHome").remove("disabledHomes").remove("crashTimes").apply()
            if ("LAUNCH_CHANGED" in out) AppLog.i("app", "screen's startup app set to CarLink")
            out.substringAfter("TOP", "").substringBefore("ZLINK").lines().firstOrNull { "Activity" in it }?.let {
                AppLog.i("app", "in front at start: ${it.trim().take(200)}")
            }
            if (probe && "ZLINK" in out) {
                AppLog.i("app", "zlink startup receiver:\n" + out.substringAfter("ZLINK").lines().filter { it.isNotBlank() }.take(25).joinToString("\n"))
                p.edit().putBoolean("accProbeDone", true).apply()
            }
        }.start()
    }

    /** Musestyringen skal kjøre, men tjenesten er ikke i gang (f.eks. etter en oppdatering): slå den på igjen. */
    fun healJoystick(ctx: Context) {
        val wanted = ctx.prefs().getBoolean("joyMouse", false) || JoyMouseService.homeOnly(ctx)
        if (!wanted || JoyMouseService.running()) return
        Thread {
            Thread.sleep(1500)
            if (JoyMouseService.running()) return@Thread   // koblet til mens vi ventet
            val c = JoyMouseService.COMPONENT
            val d = "$"
            // Ta den ut og inn igjen, så Android kobler den til på nytt
            Root.run("""
                cur=${d}(settings get secure enabled_accessibility_services | sed 's#$c##g; s#::#:#g; s#^:##; s#:${d}##')
                if [ -z "${d}cur" ] || [ "${d}cur" = "null" ]; then settings put secure enabled_accessibility_services $c
                else settings put secure enabled_accessibility_services "${d}cur"; sleep 0.3; settings put secure enabled_accessibility_services "${d}cur:$c"; fi
                settings put secure accessibility_enabled 1
            """.trimIndent())
            AppLog.i("joystick", "mouse service was not running – re-enabled")
        }.start()
    }
}
