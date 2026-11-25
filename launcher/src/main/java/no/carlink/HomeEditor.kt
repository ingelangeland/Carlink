package no.carlink

import android.content.Context

/**
 * Velg hvilke apper som ligger på hjemmeskjermen: én liste med avkrysning (lett å bruke med
 * joysticken og prikken). Maks 4. Rekkefølgen beholdes; nye legges sist.
 */
object HomeEditor {
    const val MAX = 4

    fun show(ctx: Context) {
        val apps = AppCatalog.apps.value
        if (apps.isEmpty()) return
        val current = Favorites.list(ctx)
        val checked = BooleanArray(apps.size) { apps[it].component in current }
        CarMenu.pick(ctx, "Apps on the home screen", apps.map { it.label }, apps.map { it.icon }, checked, MAX) { ch ->
            val chosen = apps.indices.filter { ch[it] }.map { apps[it].component }
            val kept = current.filter { it in chosen }
            Favorites.set(ctx, kept + chosen.filter { it !in kept })
        }
    }

    /** Valg for én app på hjemmeskjermen (i stedet for en liten meny som lukker seg lett). */
    fun appOptions(ctx: Context, cn: android.content.ComponentName, label: String) {
        val pos = Favorites.position(ctx, cn)
        val items = mutableListOf(
            CarMenu.Item("Open side by side (split screen)") { Launch.app(ctx, cn, Launch.Mode.ADJACENT) },
            CarMenu.Item("Open as floating window") { Launch.app(ctx, cn, Launch.Mode.WINDOW) },
        )
        if (pos > 0) items += CarMenu.Item("Move left") { Favorites.move(ctx, cn, -1) }
        if (pos in 0 until Favorites.count(ctx) - 1) items += CarMenu.Item("Move right") { Favorites.move(ctx, cn, +1) }
        items += CarMenu.Item("Choose apps on Home …") { show(ctx) }
        items += CarMenu.Item("Remove from Home", danger = true) { Favorites.remove(ctx, cn) }
        val icon = runCatching { ctx.packageManager.getActivityIcon(cn) }.getOrNull()
        CarMenu.show(ctx, label, items, icon)
    }
}
