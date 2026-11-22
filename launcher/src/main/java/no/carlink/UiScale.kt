package no.carlink

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.min

/**
 * Bilskjermer har ofte lav pikseltetthet (f.eks. 1920x720 på 160 dpi), og da blir alt
 * bitte lite. «Auto» skalerer slik at skjermhøyden tilsvarer omtrent 500 dp,
 * og brukeren kan justere større/mindre i «Mer».
 */
object UiScale {
    val factors = floatArrayOf(0.85f, 1f, 1.15f, 1.3f)
    val labels = arrayOf("Liten", "Normal (auto)", "Stor", "Ekstra stor")

    fun factor(ctx: Context) = ctx.prefs().getFloat("uiScale", 1f)
    fun label(ctx: Context) = labels[factors.indexOfFirst { it == factor(ctx) }.coerceAtLeast(0)]

    fun dpi(ctx: Context): Int {
        val sys = ctx.resources.configuration.densityDpi
        val dm = DisplayMetrics()
        runCatching {
            @Suppress("DEPRECATION")
            ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        }.onFailure { android.util.Log.w("CarLinkUi", "getRealMetrics feilet", it) }
        if (dm.widthPixels <= 0) {
            val m = ctx.resources.displayMetrics
            dm.widthPixels = m.widthPixels; dm.heightPixels = m.heightPixels
        }
        val shortPx = min(dm.widthPixels, dm.heightPixels).takeIf { it > 0 } ?: return sys
        val heightDp = shortPx * 160 / sys
        val auto = if (heightDp > 600) shortPx * 160 / 500 else sys
        val out = (auto * factor(ctx)).toInt().coerceIn(120, 640)
        android.util.Log.i("CarLinkUi", "skjerm ${dm.widthPixels}x${dm.heightPixels} system=$sys dpi -> bruker $out dpi")
        return out
    }

    /** Lager en kontekst med justert tetthet (brukes i attachBaseContext). */
    fun wrap(base: Context): Context {
        val old = base.resources.configuration
        val dpi = dpi(base)
        Ui.density = dpi / 160f
        if (dpi == old.densityDpi) return base
        android.util.Log.i("CarLinkUi", "overstyrer tetthet ${old.densityDpi} -> $dpi")
        val cfg = Configuration(old)
        cfg.densityDpi = dpi
        cfg.screenWidthDp = old.screenWidthDp * old.densityDpi / dpi
        cfg.screenHeightDp = old.screenHeightDp * old.densityDpi / dpi
        cfg.smallestScreenWidthDp = old.smallestScreenWidthDp * old.densityDpi / dpi
        return base.createConfigurationContext(cfg)
    }
}
