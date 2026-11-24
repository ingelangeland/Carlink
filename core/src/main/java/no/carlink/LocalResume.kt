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
    private var lastWrite = 0L
    private var lastWake = 0L

    fun enabled(ctx: Context) = ctx.prefs().getBoolean("resumeLocal", true)

    /** Kjører så lenge tjenesten går: noterer når musikken spilte og når skjermen var i bruk. */
    suspend fun track(ctx: Context) {
        if (!startedOnce) { startedOnce = true; val snap = snapshot(ctx); main.postDelayed({ wake(ctx, "start", snap) }, 2500) }
        // Skjermen kan sove uten å si fra (bilen av en kort stund): da står «oppetiden» stille
        // mens klokka går. Et hopp i forskjellen betyr at den nettopp våknet.
        var slept = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
        while (coroutineContext.isActive) {
            val s = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
            if (s - slept > 30_000) {
                AppLog.i("music", "screen woke from sleep (${(s - slept) / 1000} s)")
                val snap = snapshot(ctx)
                lastWrite = System.currentTimeMillis()
                main.postDelayed({ wake(ctx, "after sleep", snap) }, 2000)
            }
            slept = s
            val now = System.currentTimeMillis()
            if (now - lastWrite >= 15_000) {
                lastWrite = now
                val p = ctx.prefs().edit().putLong("resumeAliveAt", now)
                Hub.localMedia.value?.takeIf { it.playing && it.pkg.isNotBlank() && it.pkg != ctx.packageName }?.let {
                    p.putLong("resumePlayingAt", now).putString("resumePkg", it.pkg)
                }
                p.apply()
            }
            delay(5_000)
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
            val snap = snapshot(ctx)
            lastWrite = System.currentTimeMillis()
            main.postDelayed({ wake(ctx, "screen on", snap) }, 2000)
        }
    }

    /** Hva som gjaldt da skjermen sist var i bruk – leses før noe nytt skrives over det. */
    private fun snapshot(ctx: Context) = ctx.prefs().let { Triple(it.getString("resumePkg", null), it.getLong("resumePlayingAt", 0), it.getLong("resumeAliveAt", 0)) }

    private fun wake(ctx: Context, why: String, snap: Triple<String?, Long, Long>) {
        if (!enabled(ctx)) return
        val t = SystemClock.elapsedRealtime()
        if (t - lastWake < 20_000) return
        lastWake = t
        val pr = ctx.prefs()
        val pkg = snap.first ?: return
        val playingAt = snap.second
        val aliveAt = snap.third
        if (pr.getString("lastSource", "phone") != "car") { AppLog.i("music", "no resume ($why): phone was the last source"); return }
        // Bilskjermen pauser musikken når bilen slås av, men CarLink lever en stund til før den
        // avsluttes – derfor godtas det at musikken stoppet inntil 2 minutter før slutten
        if (playingAt == 0L || playingAt < aliveAt - 120_000) { AppLog.i("music", "no resume ($why): music was paused before the car was switched off"); return }
        if (Hub.localMedia.value?.playing == true || Hub.remoteMedia.value?.playing == true) return
        resumeNow(ctx, pkg, why)
    }

    /** Start musikken i [pkg]: spill direkte hvis appen lever, ellers åpne den et øyeblikk først. */
    private var lastResume = 0L

    fun resumeNow(ctx: Context, pkg: String, why: String) {
        val t = SystemClock.elapsedRealtime()
        if (t - lastResume < 20_000) return            // allerede i gang (oppstart og telefon samtidig)
        lastResume = t
        AppLog.i("music", "resuming $pkg ($why)")
        // 1) Appens egen økt finnes fortsatt (skjermen var bare i hvilemodus)
        val cur = Hub.localMedia.value
        if (cur != null && cur.pkg == pkg) { MediaListener.control("play"); return }
        if (Hub.localMedia.value?.playing == true) return
        // 2) Appen er avsluttet (bilskjermen lukker alle apper når bilen slås av): start den i
        //    bakgrunnen ved å åpne den et øyeblikk, gå tilbake, og be den spille.
        playButton(ctx, pkg)
        main.postDelayed({
            if (Hub.localMedia.value?.playing == true || Hub.remoteMedia.value?.playing == true) return@postDelayed
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: run { AutoPlay.resume(ctx); return@postDelayed }
            AppLog.i("music", "opening $pkg briefly to resume")
            runCatching { ctx.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
            main.postDelayed({
                // Tilbake til hjemmeskjermen (CarLink) og spill
                runCatching {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION))
                }
                main.postDelayed({
                    if (Hub.localMedia.value?.playing == true) return@postDelayed
                    val cur = Hub.localMedia.value
                    if (cur != null && cur.pkg == pkg) MediaListener.control("play") else { playButton(ctx, pkg); AutoPlay.resume(ctx) }
                }, 1200)
            }, 3000)
        }, 2500)
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
