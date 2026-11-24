package no.carlink

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Fortsetter musikken i appen som spilte på bilskjermen sist (YouTube Music, Tidal …)
 * når skjermen våkner igjen eller starter på nytt. Valg under Settings → Music.
 *
 * «Spilte sist» = musikken gikk (nesten) helt til skjermen ble slått av. Pauset du selv
 * en stund før, fortsetter den ikke.
 */
object LocalResume {
    private val main = Handler(Looper.getMainLooper())
    private var offAt = 0L
    private var startedOnce = false

    fun enabled(ctx: Context) = ctx.prefs().getBoolean("resumeLocal", true)

    /** Kjører så lenge tjenesten går: noterer når musikken spilte og når skjermen var i bruk. */
    suspend fun track(ctx: Context) {
        if (!startedOnce) { startedOnce = true; main.postDelayed({ wake(ctx, "start") }, 4000) }
        while (coroutineContext.isActive) {
            val now = System.currentTimeMillis()
            val p = ctx.prefs().edit().putLong("resumeAliveAt", now)
            Hub.localMedia.value?.takeIf { it.playing && it.pkg.isNotBlank() && it.pkg != ctx.packageName }?.let {
                p.putLong("resumePlayingAt", now).putString("resumePkg", it.pkg)
            }
            p.apply()
            delay(15_000)
        }
    }

    fun screen(ctx: Context, on: Boolean) {
        if (!on) {
            offAt = SystemClock.elapsedRealtime()
            val now = System.currentTimeMillis()
            val p = ctx.prefs().edit().putLong("resumeAliveAt", now)
            if (Hub.localMedia.value?.playing == true) p.putLong("resumePlayingAt", now)
            p.apply()
        } else if (offAt > 0 && SystemClock.elapsedRealtime() - offAt > 30_000) {
            main.postDelayed({ wake(ctx, "screen on") }, 4000)
        }
    }

    private fun wake(ctx: Context, why: String) {
        if (!enabled(ctx)) return
        val pr = ctx.prefs()
        val pkg = pr.getString("resumePkg", null) ?: return
        val playingAt = pr.getLong("resumePlayingAt", 0)
        val aliveAt = pr.getLong("resumeAliveAt", 0)
        if (pr.getString("lastSource", "phone") != "car") return
        if (playingAt == 0L || playingAt < aliveAt - 25_000) return        // var pauset før bilen ble slått av
        if (Hub.localMedia.value?.playing == true || Hub.remoteMedia.value?.playing == true) return
        AppLog.i("music", "resuming $pkg ($why)")
        // 1) Appens egen økt finnes fortsatt (skjermen var bare i hvilemodus)
        val cur = Hub.localMedia.value
        if (cur != null && cur.pkg == pkg) { MediaListener.control("play"); return }
        // 2) Etter omstart: be appen fortsette via medieknappen (YouTube Music, Spotify, Tidal støtter dette)
        playButton(ctx, pkg)
        main.postDelayed({
            if (Hub.localMedia.value?.playing != true && Hub.remoteMedia.value?.playing != true) AutoPlay.resume(ctx)
        }, 3500)
    }

    private fun playButton(ctx: Context, pkg: String) {
        val t = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            runCatching {
                ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(t, t, action, KeyEvent.KEYCODE_MEDIA_PLAY, 0)))
            }
        }
    }
}
