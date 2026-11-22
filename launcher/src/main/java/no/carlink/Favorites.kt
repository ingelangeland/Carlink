package no.carlink

import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Favorittapper på hjemmeskjermen. Legg til/fjern med langt trykk i app-listen. */
object Favorites {
    private const val KEY = "favs"
    val changed = MutableStateFlow(0)

    private val defaults = listOf(
        Launch.MAPS,
        "com.aspiro.tidal",
        "com.google.android.dialer",
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.google.android.youtube",
        "com.google.android.apps.nbu.files",
    )

    fun list(ctx: Context): List<ComponentName> {
        val raw = ctx.prefs().getString(KEY, null)
        if (raw == null) {
            val d = defaults.mapNotNull { ctx.packageManager.getLaunchIntentForPackage(it)?.component }.take(5)
            save(ctx, d); return d
        }
        return raw.split("|").filter { it.isNotBlank() }.mapNotNull { ComponentName.unflattenFromString(it) }
    }

    fun contains(ctx: Context, cn: ComponentName) = list(ctx).contains(cn)

    fun add(ctx: Context, cn: ComponentName) {
        val l = list(ctx); if (cn in l) return
        save(ctx, l + cn)
    }

    fun remove(ctx: Context, cn: ComponentName) = save(ctx, list(ctx) - cn)

    private fun save(ctx: Context, l: List<ComponentName>) {
        ctx.prefs().edit().putString(KEY, l.joinToString("|") { it.flattenToString() }).apply()
        changed.value = changed.value + 1
    }
}
