package no.carlink

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** Felles «stemning» for launcheren: hvilken musikk som vises, og fargene hentet fra coveret. */
object Ambient {
    /** accent = lys, mettet farge (streker, fremdrift, lydbølger). dark = mørk tone til glass og toning. */
    data class Theme(val accent: Int, val dark: Int)

    val DEFAULT = Theme(C.ACCENT, 0xFF121722.toInt())
    val theme = MutableStateFlow(DEFAULT)
    val accent = MutableStateFlow(C.ACCENT)

    /**
     * Hvor mye hjelp teksten øverst til høyre (fart og vær) trenger for å være lesbar, 0–1.
     * Måles fra coveret akkurat der teksten står: lys og urolig bakgrunn gir høy verdi.
     */
    val topRightNeed = MutableStateFlow(0f)

    /**
     * Navigasjon som vises i kortet og i kartet: Google Maps på bilenheten hvis den navigerer
     * (uansett hvordan ruten ble startet), ellers Google Maps på telefonen.
     */
    val nav: kotlinx.coroutines.flow.Flow<Hub.NavInfo?> =
        kotlinx.coroutines.flow.combine(Hub.localNav, Hub.remoteNav) { car, phone -> car ?: phone }

    /** App-oversikten dekker skjermen: animasjoner bak (lydbølgen) kan hvile. */
    @Volatile var overlayOpen = false

    /** Musikk fra telefonen hvis den spiller, ellers musikk som spilles på skjermen selv (f.eks. Tidal i bilen). */
    val media: Flow<Pair<Hub.Media?, String>> =
        combine(Hub.connected, Hub.remoteMedia, Hub.localMedia, Hub.audioStreaming) { c, r, l, a ->
            val sound = if (a) " · LYD PÅ SKJERMEN" else ""
            if (Hub.useRemote(c, r, l)) r!! to "FRA TELEFONEN · ${r.app.uppercase()}$sound"
            else l to (l?.app?.uppercase() ?: "")
        }

    /** Gjør en farge lys og mettet nok til å synes på mørk bakgrunn. */
    fun readable(color: Int): Int {
        var c = color or 0xFF000000.toInt()
        if (ColorUtils.calculateLuminance(c) < 0.22) c = ColorUtils.blendARGB(c, Color.WHITE, 0.35f)
        return c
    }

    /** Gjør en farge mørk nok til at hvit tekst alltid kan leses oppå. */
    fun deep(color: Int): Int {
        var c = color or 0xFF000000.toInt()
        var i = 0
        while (ColorUtils.calculateLuminance(c) > 0.06 && i < 8) { c = ColorUtils.blendARGB(c, Color.BLACK, 0.3f); i++ }
        return c
    }

    fun alpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)

    fun blend(a: Theme, b: Theme, f: Float) =
        Theme(ColorUtils.blendARGB(a.accent, b.accent, f), ColorUtils.blendARGB(a.dark, b.dark, f))
}
