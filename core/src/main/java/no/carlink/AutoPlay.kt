package no.carlink

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Starter/pauser musikken når telefonen kobles til/fra (valg på bilskjermen, standard JA).
 * Virker med alle apper som har vanlige medieknapper. Appen selv husker hvor du var.
 */
object AutoPlay {
    fun enabled(ctx: Context) = ctx.prefs().getBoolean("autoPlay", true)

    /** «Spill»: i appen som vises, ellers sist brukte musikkapp (Android sender medieknappen dit). */
    fun resume(ctx: Context) {
        if (Hub.localMedia.value?.playing == true) return
        if (Hub.localMedia.value != null) { MediaListener.control("play"); return }
        runCatching {
            val am = ctx.getSystemService(AudioManager::class.java)
            val t = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        }
    }

    fun pause() {
        if (Hub.localMedia.value?.playing == true) MediaListener.control("pause")
    }
}
