package no.carlink

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.widget.Toast

/** Åpne andre apper: fullskjerm, ved siden av (Androids delte skjerm) eller som flytende vindu. */
object Launch {
    const val MAPS = "com.google.android.apps.maps"

    /** FULL = hele skjermen, RIGHT_THIRD = vindu i høyre tredjedel, ADJACENT = Androids delte skjerm, WINDOW = flytende vindu. */
    enum class Mode(val label: String) { FULL("Full screen"), RIGHT_THIRD("Right ⅓"), ADJACENT("Split screen"), WINDOW("Window") }

    /** Valgt standard for hvordan apper åpnes (velges øverst i app-oversikten). */
    fun defaultMode(ctx: Context): Mode =
        runCatching { Mode.valueOf(ctx.prefs().getString("openMode", "FULL")!!) }.getOrDefault(Mode.FULL)

    fun setDefaultMode(ctx: Context, m: Mode) = ctx.prefs().edit().putString("openMode", m.name).apply()

    /**
     * Om skjermen støtter frie vinduer (desktop-/vindusmodus). Uten dette ignorerer Android
     * vindusstørrelsen, og «Høyre ⅓» faller tilbake til delt skjerm.
     */
    fun windowsSupported(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            runCatching { android.provider.Settings.Global.getInt(ctx.contentResolver, "enable_freeform_support", 0) != 0 }.getOrDefault(false)

    private fun prepare(ctx: Context, i: Intent, mode: Mode): android.os.Bundle? {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (mode == Mode.ADJACENT) i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (mode == Mode.RIGHT_THIRD) {
            val dm = ctx.resources.displayMetrics
            if (!windowsSupported(ctx)) {
                // Ingen vindusmodus: bruk Androids delte skjerm (skillelinjen kan dras til ⅓)
                i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                return null
            }
            i.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val left = dm.widthPixels * 2 / 3
            return ActivityOptions.makeBasic().setLaunchBounds(Rect(left, 0, dm.widthPixels, dm.heightPixels)).toBundle()
        }
        if (mode == Mode.WINDOW) {
            i.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val dm = ctx.resources.displayMetrics
            val w = (dm.widthPixels * 0.45f).toInt(); val h = (dm.heightPixels * 0.7f).toInt()
            val left = dm.widthPixels - w - 24.dp; val top = 60.dp
            return ActivityOptions.makeBasic().setLaunchBounds(Rect(left, top, left + w, top + h)).toBundle()
        }
        return null
    }

    fun start(ctx: Context, i: Intent, mode: Mode = Mode.FULL, quiet: Boolean = false): Boolean = try {
        val opts = prepare(ctx, i, mode)
        ctx.startActivity(i, opts)
        true
    } catch (e: ActivityNotFoundException) {
        if (!quiet) Toast.makeText(ctx, "No app found that can open this", Toast.LENGTH_SHORT).show()
        false
    } catch (e: SecurityException) {
        if (!quiet) Toast.makeText(ctx, "Not allowed: ${e.message}", Toast.LENGTH_SHORT).show()
        false
    }

    fun app(ctx: Context, cn: ComponentName, mode: Mode) {
        start(ctx, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn), mode)
    }

    fun installed(ctx: Context, pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun openMaps(ctx: Context, query: String?, mode: Mode) {
        val i = if (query.isNullOrBlank()) {
            ctx.packageManager.getLaunchIntentForPackage(MAPS) ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))).apply {
                if (installed(ctx, MAPS)) setPackage(MAPS)
            }
        }
        start(ctx, i, mode)
    }

    /** Åpner et sted/en rute som er delt fra Google Maps på telefonen. */
    fun openMapsUrl(ctx: Context, url: String) {
        if (url.isBlank()) return
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (installed(ctx, MAPS)) i.setPackage(MAPS)
        if (!start(ctx, i, Mode.FULL, quiet = true)) start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** Starter sving-for-sving-navigasjon i Google Maps (faller tilbake til søk). */
    fun navigate(ctx: Context, q: String) {
        if (q.isBlank()) return
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q))).setPackage(MAPS)
        if (!start(ctx, i, Mode.FULL, quiet = true)) openMaps(ctx, q, Mode.FULL)
    }
}
