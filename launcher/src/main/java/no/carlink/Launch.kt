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

    enum class Mode { FULL, ADJACENT, WINDOW }

    private fun prepare(ctx: Context, i: Intent, mode: Mode): android.os.Bundle? {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (mode == Mode.ADJACENT) i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
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
        if (!quiet) Toast.makeText(ctx, "Fant ingen app som kan åpne dette", Toast.LENGTH_SHORT).show()
        false
    } catch (e: SecurityException) {
        if (!quiet) Toast.makeText(ctx, "Ikke tillatt: ${e.message}", Toast.LENGTH_SHORT).show()
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
