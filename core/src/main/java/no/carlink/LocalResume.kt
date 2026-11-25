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
    /** Du pauset musikken selv (ny nøkkel: den gamle kunne bli satt feil når musikken ikke startet). */
    const val USER_PAUSED = "resumeUserPaused2"
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
        var pausedSince = 0L
        var wasPlaying = false
        while (coroutineContext.isActive) {
            // Pauset du musikken selv (pause i mer enn 10 s mens bilen er på)? Da skal den ikke startes
            // av seg selv neste gang. (Bilskjermens egen pause når bilen slås av varer bare et øyeblikk.)
            val lm = Hub.localMedia.value
            if (lm?.playing == true) {
                wasPlaying = true; pausedSince = 0
                if (ctx.prefs().getBoolean(USER_PAUSED, false)) ctx.prefs().edit().putBoolean(USER_PAUSED, false).apply()
            } else if (lm != null && wasPlaying && !Hub.parked.value) {
                // Bare når musikk som faktisk spilte ble pauset – ikke når CarLink selv prøvde å starte den
                if (pausedSince == 0L) pausedSince = SystemClock.elapsedRealtime()
                else if (SystemClock.elapsedRealtime() - pausedSince > 10_000 && SystemClock.elapsedRealtime() - lastResume > 40_000 &&
                    !ctx.prefs().getBoolean(USER_PAUSED, false)) {
                    ctx.prefs().edit().putBoolean(USER_PAUSED, true).apply()
                    AppLog.i("music", "you paused ${lm.app} – it won't start by itself next time")
                }
            }
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
        if (pr.getBoolean(USER_PAUSED, false)) { AppLog.i("music", "no resume ($why): you had paused the music"); return }
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
        // Bilens startbilde vises: start musikken når det er ferdig
        if (Hub.introUntil > t) { main.postDelayed({ resumeNow(ctx, pkg, why) }, Hub.introUntil - t + 300); return }
        if (t - lastResume < 20_000) return            // allerede i gang (oppstart og telefon samtidig)
        lastResume = t
        AppLog.i("music", "resuming $pkg ($why)")
        if (Hub.localMedia.value?.playing == true) return
        // 1) Lever appen ikke (bilskjermen lukker alle apper når bilen slås av): åpne den et øyeblikk
        //    med en gang, så den starter opp mens vi venter på internett fra telefonens hotspot.
        val alive = Hub.localMedia.value?.pkg == pkg
        if (!alive) {
            playButton(ctx, pkg, play = false)
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
                AppLog.i("music", "opening $pkg briefly so it starts up")
                runCatching { ctx.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
                main.postDelayed({
                    runCatching {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION))
                    }
                }, 3000)
            }
        }
        // 2) Vent på internett (høyst 2 minutter), så spill – og sjekk at det faktisk spiller
        waitForInternet(ctx, SystemClock.elapsedRealtime(), minWaitMs = if (alive) 0 else 3500) { waited ->
            AppLog.i("music", "internet ready after ${waited / 1000} s – pressing play")
            play(ctx, pkg)
            main.postDelayed({
                if (Hub.localMedia.value?.playing == true || Hub.remoteMedia.value?.playing == true) {
                    AppLog.i("music", "$pkg is playing"); return@postDelayed
                }
                AppLog.i("music", "$pkg not playing yet – trying once more")
                play(ctx, pkg)
                main.postDelayed({ if (Hub.localMedia.value?.playing != true) { AppLog.i("music", "still not playing – play button to $pkg"); playButton(ctx, pkg) } }, 3000)
            }, 10_000)
        }
    }

    private fun play(ctx: Context, pkg: String) {
        if (Hub.localMedia.value?.playing == true) return
        // Styr akkurat denne appen direkte; har den ingen økt ennå, send «play»-knappen til den
        if (MediaListener.controlPkg(pkg, "play")) AppLog.i("music", "play sent to $pkg")
        else { AppLog.i("music", "play button sent to $pkg"); playButton(ctx, pkg); AutoPlay.resume(ctx) }
    }

    private fun hasInternet(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    private fun waitForInternet(ctx: Context, started: Long, minWaitMs: Long, then: (Long) -> Unit) {
        val waited = SystemClock.elapsedRealtime() - started
        if ((hasInternet(ctx) && waited >= minWaitMs) || waited > 120_000) {
            if (waited > 120_000) AppLog.i("music", "no internet after 2 min – trying anyway")
            then(waited); return
        }
        main.postDelayed({ waitForInternet(ctx, started, minWaitMs, then) }, 1000)
    }

    /** Medieknapp til appen. [play] = false: bare vekk den (starter prosessen uten å spille). */
    private fun playButton(ctx: Context, pkg: String, play: Boolean = true) {
        val t = SystemClock.uptimeMillis()
        if (!play) {
            runCatching { ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_UNKNOWN, 0))) }
            return
        }
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            runCatching {
                ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(t, t, action, KeyEvent.KEYCODE_MEDIA_PLAY, 0)))
            }
        }
    }
}
