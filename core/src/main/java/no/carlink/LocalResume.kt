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
    const val USER_PAUSED = "resumeUserPaused3"
    @Volatile private var wasPlaying = false
    @Volatile private var pausedAt = 0L
    private var carOffAt = 0L

    /**
     * Bilen slås av (hvileskjermen kommer / forbindelsen faller). Bilskjermen pauser selv musikken
     * i samme øyeblikk – det er ikke deg. Var musikken pauset minst 5 s FØR dette, var det deg:
     * da startes den ikke av seg selv neste gang.
     */
    fun carOff(ctx: Context) {
        val t = SystemClock.elapsedRealtime()
        if (t - carOffAt < 30_000) return
        carOffAt = t
        val lm = Hub.localMedia.value
        val mine = lm != null && lm.playing != true && pausedAt > 0 && t - pausedAt > 5_000
        ctx.prefs().edit().putBoolean(USER_PAUSED, mine).apply()
        AppLog.i("music", if (mine) "car off – you had paused ${lm?.app} ${(t - pausedAt) / 1000} s before, it won't start by itself"
                          else "car off – music will start by itself next time")
    }
    private val main = Handler(Looper.getMainLooper())
    private var offAt = 0L
    private var startedOnce = false
    private var lastWrite = 0L
    private var lastWake = -1_000_000L   // ikke 0: klokka starter på 0 etter en omstart

    fun enabled(ctx: Context) = ctx.prefs().getBoolean("resumeLocal", true)

    /** Kjører så lenge tjenesten går: noterer når musikken spilte og når skjermen var i bruk. */
    suspend fun track(ctx: Context) {
        if (!startedOnce) { startedOnce = true; val snap = snapshot(ctx); main.postDelayed({ wake(ctx, "start", snap) }, 2500) }
        // Skjermen kan sove uten å si fra (bilen av en kort stund): da står «oppetiden» stille
        // mens klokka går. Et hopp i forskjellen betyr at den nettopp våknet.
        var slept = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

        while (coroutineContext.isActive) {
            // Pauset du musikken selv (pause i mer enn 10 s mens bilen er på)? Da skal den ikke startes
            // av seg selv neste gang. (Bilskjermens egen pause når bilen slås av varer bare et øyeblikk.)
            val lm = Hub.localMedia.value
            if (lm?.playing == true) { wasPlaying = true; pausedAt = 0 }
            else if (lm != null && wasPlaying && pausedAt == 0L) pausedAt = SystemClock.elapsedRealtime()
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
    private var lastResume = -1_000_000L

    /** Du trykket play/neste og ingenting spiller: start den siste musikkappen (samme vei som ved oppstart). */
    fun playLast(ctx: Context): Boolean {
        // Var telefonen siste kilde (og den er koblet til), skal play gå dit som før
        if (Hub.connected.value && ctx.prefs().getString("lastSource", "phone") != "car") return false
        val pkg = Hub.localMedia.value?.pkg?.takeIf { it.isNotBlank() } ?: ctx.prefs().getString("resumePkg", null)
        if (pkg == null) { MediaListener.control("play"); return true }
        if (SystemClock.elapsedRealtime() - lastResume < 25_000) { play(ctx, pkg); return true }   // er allerede i gang
        lastResume = -1_000_000L
        resumeNow(ctx, pkg, "you pressed play")
        return true
    }

    fun resumeNow(ctx: Context, pkg: String, why: String) {
        val t = SystemClock.elapsedRealtime()
        // Bilens startbilde vises: start musikken når det er ferdig
        if (Hub.introUntil > t) { main.postDelayed({ resumeNow(ctx, pkg, why) }, Hub.introUntil - t + 300); return }
        if (t - lastResume < 45_000) return            // allerede i gang (oppstart og telefon samtidig)
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
            retryPlay(ctx, pkg, 1)
        }
    }

    /** Sjekk hvert 2. sekund at det spiller; prøv igjen (vekselvis direkte og med play-knappen), høyst 12 ganger.
     *  Etter en kald start (omstart av skjermen) har ikke YouTube Music lastet køen sin ennå og overser «play»
     *  («player wasn't loaded»). Da åpnes appen og får stå fremme til den spiller, og vi sender «neste»,
     *  som får den til å laste køen og begynne å spille. */
    private var openedForLoad = false
    private fun retryPlay(ctx: Context, pkg: String, n: Int) {
        main.postDelayed({
            if (Hub.localMedia.value?.playing == true || Hub.remoteMedia.value?.playing == true) {
                AppLog.i("music", "$pkg is playing (after ${n * 2} s)"); backHome(ctx); return@postDelayed
            }
            if (n >= 12) { AppLog.i("music", "$pkg still not playing after 24 s – giving up"); backHome(ctx); return@postDelayed }
            when (n) {
                2 -> {   // 4 s uten lyd: appen er nok ikke klar – åpne den og la den stå fremme
                    ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
                        AppLog.i("music", "$pkg ignores play (cold start?) – opening it so it loads")
                        openedForLoad = true
                        runCatching { ctx.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
                    }
                }
                4, 7, 10 -> {
                    if (MediaListener.controlPkg(pkg, "next")) AppLog.i("music", "next sent to $pkg to make it load")
                    else play(ctx, pkg)
                }
                else -> if (n % 2 == 1) play(ctx, pkg) else playButton(ctx, pkg)
            }
            retryPlay(ctx, pkg, n + 1)
        }, 2000)
    }

    /** Lukk appen vi åpnet for å få den til å laste, og gå tilbake til hjemmeskjermen. */
    private fun backHome(ctx: Context) {
        if (!openedForLoad) return
        openedForLoad = false
        main.postDelayed({
            runCatching {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }, 1500)
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
