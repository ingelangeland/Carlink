package no.carlink

import android.content.Context
import android.content.Intent

/**
 * Meny-knappen ved joysticken: kort trykk, to trykk og langt trykk kan hver få en app eller en
 * CarLink-funksjon (Settings → Joystick). Lagres som tekst: «recents», «app:com.foo» osv.
 */
object MenuButton {
    enum class Press(val key: String, val title: String, val default: String) {
        SHORT("menuShort", "Menu button – one press", "recents"),
        DOUBLE("menuDouble", "Menu button – two presses", "apps"),
        LONG("menuLong", "Menu button – hold", "settings"),
    }

    /** CarLink-funksjoner som kan velges (nøkkel → navn). Apper kommer i tillegg. */
    val functions = linkedMapOf(
        "none" to "Nothing",
        "home" to "Home screen",
        "apps" to "All apps",
        "recents" to "Open apps (switcher)",
        "music" to "Music view",
        "map" to "Map",
        "settings" to "CarLink settings",
        "screenoff" to "Screen off",
        "car" to "Car's own system",
    )

    fun get(ctx: Context, p: Press): String = ctx.prefs().getString(p.key, p.default) ?: p.default
    fun set(ctx: Context, p: Press, v: String) = ctx.prefs().edit().putString(p.key, v).apply()

    fun label(ctx: Context, v: String): String =
        if (v.startsWith("app:")) runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(v.removePrefix("app:"), 0)).toString()
        }.getOrDefault(v.removePrefix("app:"))
        else functions[v] ?: v

    /** Utfør valget. [svc] gir tilgang til «skjerm av» og bilens system. */
    fun run(ctx: Context, v: String, svc: JoyMouseService?) {
        AppLog.i("joystick", "menu button → $v")
        when {
            v == "none" -> Unit
            v.startsWith("app:") -> ctx.packageManager.getLaunchIntentForPackage(v.removePrefix("app:"))?.let {
                runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }
            }
            v == "screenoff" -> svc?.blackout(true)
            v == "car" -> svc?.openCarSystemNow()
            else -> runCatching {
                ctx.startActivity(Intent(ctx, ReceiverActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra("show", when (v) { "apps" -> "drawer"; "settings" -> "menu"; else -> v }))
            }
        }
    }
}
