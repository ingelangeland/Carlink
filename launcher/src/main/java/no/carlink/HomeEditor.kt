package no.carlink

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast

/**
 * Velg hvilke apper som ligger på hjemmeskjermen: én liste med avkrysning (lett å bruke med
 * joysticken og prikken). Maks 6. Rekkefølgen beholdes; nye legges sist.
 */
object HomeEditor {
    const val MAX = 6

    fun show(ctx: Context) {
        val apps = AppCatalog.apps.value
        if (apps.isEmpty()) return
        val current = Favorites.list(ctx)
        val checked = BooleanArray(apps.size) { apps[it].component in current }
        AlertDialog.Builder(ctx)
            .setTitle("Apps on the home screen (max $MAX)")
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { d, i, on ->
                if (on && checked.count { it } > MAX) {
                    checked[i] = false
                    (d as AlertDialog).listView.setItemChecked(i, false)
                    Toast.makeText(ctx, "Room for $MAX apps – untick one first", Toast.LENGTH_SHORT).show()
                } else checked[i] = on
            }
            .setPositiveButton("Save") { _, _ ->
                val chosen = apps.indices.filter { checked[it] }.map { apps[it].component }
                val kept = current.filter { it in chosen }
                Favorites.set(ctx, kept + chosen.filter { it !in kept })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Valg for én app på hjemmeskjermen (i stedet for en liten meny som lukker seg lett). */
    fun appOptions(ctx: Context, cn: android.content.ComponentName, label: String) {
        val pos = Favorites.position(ctx, cn)
        val items = mutableListOf<Pair<String, () -> Unit>>(
            "Open side by side (split screen)" to { Launch.app(ctx, cn, Launch.Mode.ADJACENT) },
            "Open as floating window" to { Launch.app(ctx, cn, Launch.Mode.WINDOW) },
        )
        if (pos > 0) items += "Move forward" to { Favorites.move(ctx, cn, -1) }
        if (pos in 0 until Favorites.count(ctx) - 1) items += "Move back" to { Favorites.move(ctx, cn, +1) }
        items += "Remove from Home" to { Favorites.remove(ctx, cn) }
        items += "Choose apps on Home …" to { show(ctx) }
        AlertDialog.Builder(ctx).setTitle(label)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Cancel", null).show()
    }
}
