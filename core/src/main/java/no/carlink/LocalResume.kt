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
        // 1) Lever appen ikke (bilskjermen lukker alle apper når bilen slås av): vekk den i bakgrunnen
        //    slik Android Auto gjør (kobler til appens musikktjeneste) – uten å vise den på skjermen.
        //    Å åpne appen teller som «du brukte den», og da gidder ikke YouTube Music å hente køen din.
        val alive = Hub.localMedia.value?.pkg == pkg
        if (!alive) {
            playButton(ctx, pkg, play = false)
            if (!connectBrowser(ctx, pkg)) ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
                AppLog.i("music", "opening $pkg briefly so it starts up")
                runCatching { ctx.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
                openedForLoad = true
                main.postDelayed({ backHome(ctx, pkg) }, 3000)
            }
        }
        // 2) Vent på internett (høyst 2 minutter), så spill – og sjekk at det faktisk spiller
        waitForInternet(ctx, SystemClock.elapsedRealtime(), minWaitMs = if (alive) 0 else 3500) { waited ->
            AppLog.i("music", "internet ready after ${waited / 1000} s – pressing play")
            val t0 = SystemClock.elapsedRealtime()
            playLoop(ctx, pkg, Attempt(t0))
        }
    }

    /**
     * Får musikken i gang uten å vise appen:
     *  1) Vent til appen er klar (har en mediesesjon eller bakgrunnstilkobling) – høyst 15 s.
     *  2) Play i bakgrunnen: med en gang den er klar, etter 2 s og etter 4 s.
     *  3) «Neste sang» etter 6 s, så «spill noe» (din vanlige miks) etter 8 s – hvis køen er tom.
     *  4) Bare hvis det fortsatt er stille, og du ikke holder på med noe på skjermen: åpne appen.
     * Etter en vanlig standby er appen klar med en gang og spiller på første play.
     */
    private class Attempt(val start: Long) {
        var readyAt = 0L; var opened = 0L; var lastSend = 0L; var step = 0
    }
    private var openedForLoad = false

    // ---- bakgrunnstilkobling til musikkappen (MediaBrowser, slik Android Auto gjør)
    private var browser: android.media.browse.MediaBrowser? = null
    @Volatile private var browserCtl: android.media.session.MediaController? = null
    @Volatile private var browserPkg: String? = null

    private fun connectBrowser(ctx: Context, pkg: String): Boolean = runCatching {
        val svc = ctx.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo ?: return false
        main.post {
            runCatching { browser?.disconnect() }
            browserCtl = null; browserPkg = pkg
            val app = ctx.applicationContext
            lateinit var b: android.media.browse.MediaBrowser
            b = android.media.browse.MediaBrowser(app, android.content.ComponentName(svc.packageName, svc.name), object : android.media.browse.MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    browserCtl = runCatching { android.media.session.MediaController(app, b.sessionToken) }.getOrNull()
                    AppLog.i("music", "woke $pkg in the background (without showing it)")
                }
                override fun onConnectionFailed() { AppLog.i("music", "$pkg refused a background connection") }
                override fun onConnectionSuspended() { browserCtl = null }
            }, null)
            browser = b
            runCatching { b.connect() }.onFailure { AppLog.i("music", "background connection failed: ${it.message}") }
        }
        true
    }.getOrDefault(false)

    private fun disconnectBrowser() {
        main.postDelayed({ runCatching { browser?.disconnect() }; browser = null; browserCtl = null; browserPkg = null }, 5000)
    }

    private fun ctl(pkg: String) = browserCtl?.takeIf { browserPkg == pkg }

    private fun playLoop(ctx: Context, pkg: String, a: Attempt) {
        val now = SystemClock.elapsedRealtime()
        if (Hub.localMedia.value?.playing == true || Hub.remoteMedia.value?.playing == true) {
            AppLog.i("music", "$pkg is playing (after ${(now - a.start) / 1000} s${if (a.opened > 0) ", app was shown" else ""})")
            backHome(ctx, pkg); disconnectBrowser(); return
        }
        if (a.readyAt == 0L && (MediaListener.hasSession(pkg) || ctl(pkg) != null)) {
            a.readyAt = now
            if (now - a.start > 500) AppLog.i("music", "$pkg is ready after ${(now - a.start) / 1000} s")
        }
        if (now - a.start > 40_000) { AppLog.i("music", "$pkg still not playing – giving up"); backHome(ctx, pkg); disconnectBrowser(); return }
        when {
            a.opened > 0 -> if (now - a.lastSend >= 2000) { a.lastSend = now; play(ctx, pkg) }
            a.readyAt == 0L -> {
                if (now - a.start > 15_000) openApp(ctx, pkg, a, "never got ready")
                else if (now - a.lastSend >= 3000) { a.lastSend = now; playButton(ctx, pkg) }
            }
            else -> {
                val since = now - a.readyAt
                when {
                    a.step == 0 -> { a.step = 1; play(ctx, pkg) }
                    a.step == 1 && since >= 2000 -> { a.step = 2; play(ctx, pkg) }
                    a.step == 2 && since >= 4000 -> { a.step = 3; play(ctx, pkg) }
                    a.step == 3 && since >= 6000 -> {
                        a.step = 4
                        val c = ctl(pkg)
                        if (c != null) { c.transportControls.skipToNext(); AppLog.i("music", "next sent to $pkg (in the background)") }
                        else if (MediaListener.controlPkg(pkg, "next")) AppLog.i("music", "next sent to $pkg (in the background)")
                    }
                    a.step == 4 && since >= 8000 -> {
                        a.step = 5
                        // Køen er tom (YouTube Music har glemt den): be den spille noe – din vanlige miks
                        ctl(pkg)?.let { c -> c.transportControls.playFromSearch("", android.os.Bundle()); AppLog.i("music", "asked $pkg to play something (queue empty?)") }
                    }
                    a.step == 5 && since >= 11_000 -> { a.step = 6; openApp(ctx, pkg, a, "ignores play and next") }
                }
            }
        }
        main.postDelayed({ playLoop(ctx, pkg, a) }, 500)
    }

    /** Åpne appen på skjermen – men aldri midt i noe du holder på med (meny, en annen app). */
    private fun openApp(ctx: Context, pkg: String, a: Attempt, why: String) {
        a.opened = SystemClock.elapsedRealtime(); a.lastSend = a.opened
        val front = Hub.frontApp?.invoke()
        val busy = SystemClock.elapsedRealtime() - Hub.userActiveAt < 10_000
        if (busy || (front != null && front != ctx.packageName)) {
            AppLog.i("music", "$pkg $why – not opening it (you are using the screen: $front)"); return
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        AppLog.i("music", "$pkg $why – opening it so it loads")
        openedForLoad = true
        runCatching { ctx.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
    }

    /** Tilbake til hjemmeskjermen – bare hvis appen vi åpnet fortsatt er den som vises. */
    private fun backHome(ctx: Context, pkg: String) {
        if (!openedForLoad) return
        openedForLoad = false
        main.postDelayed({
            val front = Hub.frontApp?.invoke()
            if (front != null && front != pkg) return@postDelayed
            runCatching {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }, 500)
    }

    private fun play(ctx: Context, pkg: String) {
        if (Hub.localMedia.value?.playing == true) return
        // Styr akkurat denne appen direkte; har den ingen økt ennå, send «play»-knappen til den
        val c = ctl(pkg)
        if (c != null) { c.transportControls.play(); AppLog.i("music", "play sent to $pkg") }
        else if (MediaListener.controlPkg(pkg, "play")) AppLog.i("music", "play sent to $pkg")
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
