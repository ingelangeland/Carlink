package no.carlink

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Lexus Remote Touch som mus i alle apper.
 *
 * Canbus-boksen sender joystickens posisjon (0–255 i hver retning) til systemappen
 * CarInfoService, som skriver hver melding i systemloggen og selv bare lager Tab/Enter.
 * Her leses posisjonen fra loggen (med root), en prikk tegnes over alle apper, trykk blir
 * klikk og hold-og-dra blir rulling. Tab/Enter fra bilen blokkeres mens dette er på.
 */
class JoyMouseService : AccessibilityService() {

    companion object {
        const val COMPONENT = "no.carlink.launcher/no.carlink.JoyMouseService"
        @Volatile var instance: JoyMouseService? = null
        @Volatile var lastFrameAt = 0L
        const val CAR_SYSTEM = "com.spd.xhsntg"
        fun wanted(ctx: Context) = ctx.prefs().getBoolean("joyMouse", false)
        /** Bilens eget system bare ved dobbeltklikk på Hjem (scrolleknappene ruller i stedet). */
        fun homeOnly(ctx: Context) = ctx.prefs().getBoolean("carHomeOnly", true)
        fun running() = instance != null
    }

    private val main = Handler(Looper.getMainLooper())
    var learn: JoyLearn? = null; private set
    @Volatile private var currentPkg: String? = null
    private var wm: WindowManager? = null
    private var overlay: FrameLayout? = null
    private var dot: DotView? = null
    private var screenW = 1920f
    private var screenH = 720f

    // Ønsket posisjon (fra bilen) og tegnet posisjon (glidende mot ønsket)
    @Volatile private var tx = -1f
    @Volatile private var ty = -1f
    private var cx = -1f
    private var cy = -1f
    private var lastMoveAt = 0L
    private var animating = false

    // Trykk og dra
    private var pressed = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var dragging = false
    private var stroke: GestureDescription.StrokeDescription? = null
    private var strokeBusy = false
    private var strokeX = 0f
    private var strokeY = 0f
    private var releasePending = false

    // Hover
    private var lastHoverCheck = 0L
    private var hoverKey: String? = null

    @Volatile private var readerOn = false
    @Volatile private var readerStartedAt = 0L
    private var reader: Thread? = null
    @Volatile private var proc: Process? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "joyMouse" || key == "joyInvertY" || key == "pointer" || key == "carHomeOnly" || key == "showBars") main.post { apply() }
    }

    override fun onServiceConnected() {
        instance = this
        AppLog.init(this); AppLog.i("joystick", "mouse service connected")
        learn = JoyLearn(this)
        DayNight.onSystem(this, resources.configuration)
        main.postDelayed(brightTick, 3000)
        // Tjenesten starter ofte etter at bilen allerede er slått på: se hva som vises nå
        main.postDelayed({
            val front = frontPkg()
            if (front != null) currentPkg = front
            AppLog.i("app", "in front when the mouse service started: $front")
            if (front == CAR_SYSTEM) startupCarSystem(delayMs = 500)
            else if (front != null && front in otherHomes) otherHomeShown(front)
        }, 1200)
        runCatching {
            contentResolver.registerContentObserver(android.provider.Settings.System.getUriFor(android.provider.Settings.System.SCREEN_BRIGHTNESS), false,
                object : android.database.ContentObserver(main) {
                    override fun onChange(selfChange: Boolean) = Brightness.systemChanged(this@JoyMouseService)
                })
        }
        // Bilens lys-signal (bilskjermens egen innstilling) – reager med en gang det endrer seg
        runCatching {
            contentResolver.registerContentObserver(android.provider.Settings.System.getUriFor("SETTING_SCREEN_ILL"), false,
                object : android.database.ContentObserver(main) {
                    override fun onChange(selfChange: Boolean) {
                        AppLog.i("light", "car lights ${if (Brightness.carLights(this@JoyMouseService)) "on" else "off"}")
                        Brightness.carLightsChanged(this@JoyMouseService)
                    }
                })
        }
        wm = getSystemService(WindowManager::class.java)
        prefs().registerOnSharedPreferenceChangeListener(prefListener)
        apply()
    }

    override fun onDestroy() {
        prefs().unregisterOnSharedPreferenceChangeListener(prefListener)
        learn?.save()
        stop()
        stopGuard()
        instance = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        DayNight.onSystem(this, newConfig)
        Brightness.tick(this)
    }

    // ---- Fabrikkens hjemmeskjerm dukker opp (f.eks. når bilen startes igjen): vis CarLink i stedet
    private val otherHomes: Set<String> by lazy {
        runCatching {
            packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName }
                .filter { it != packageName && it != "com.android.settings" }.toSet()
        }.getOrDefault(emptySet())
    }
    private var homeRedirectAt = 0L
    private var accOffAt = 0L
    @Volatile private var accOnAt = 0L

    /** Like etter at bilen er slått på (eller tjenesten nettopp startet). */
    private fun justStarted(): Boolean {
        val now = SystemClock.uptimeMillis()
        return (accOnAt > 0 && now - accOnAt < 45_000) || now - serviceStartedAt < 45_000
    }
    private var serviceStartedAt = SystemClock.uptimeMillis()

    /**
     * Bilskjermen viser bilens eget bilde når bilen slås på. La det stå noen sekunder (bilens egen
     * oppstartsskjerm), og vis så CarLink – med mindre du selv har bedt om bilens system (dobbeltklikk).
     */
    private fun frontPkg(): String? = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    private fun startupCarSystem(delayMs: Long = 4000) {
        if (!justStarted() || !prefs().getBoolean("replaceFactoryHome", true)) return
        if (SystemClock.elapsedRealtime() < allowCarUntil) return
        main.postDelayed({
            if (currentPkg == CAR_SYSTEM && SystemClock.elapsedRealtime() >= allowCarUntil) {
                AppLog.i("app", "car system at startup – showing CarLink")
                runCatching {
                    startActivity(android.content.Intent(this, ReceiverActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
        }, delayMs)
    }

    private fun otherHomeShown(pkg: String) {
        if (!prefs().getBoolean("replaceFactoryHome", true)) return
        val now = SystemClock.uptimeMillis()
        if (now - homeRedirectAt < 3000) return
        homeRedirectAt = now
        AppLog.i("app", "factory home ($pkg) came up – showing CarLink")
        main.postDelayed({
            runCatching {
                startActivity(android.content.Intent(this, ReceiverActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }, 300)
    }

    private var brightCount = 0
    private val brightTick = object : Runnable {
        override fun run() {
            if (++brightCount % 6 == 0) Brightness.tick(this@JoyMouseService)   // hvert minutt
            Brightness.watchHardware(this@JoyMouseService)                    // hvert 10. sekund (bare med bilens lys på)
            main.postDelayed(this, 10_000)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg == CAR_SYSTEM) carSystemShown()
            if (pkg == packageName && event.className?.toString()?.endsWith("ReceiverActivity") == true) carLinkShown()
            if (pkg == "com.spd.screen.saver") { accOffAt = SystemClock.uptimeMillis(); accOnAt = 0L }
            else if (pkg != null && accOffAt > 0 && accOnAt == 0L) { accOnAt = SystemClock.uptimeMillis(); AppLog.i("app", "car switched on (first: $pkg)") }
            if (pkg != null && pkg in otherHomes) otherHomeShown(pkg)
            if (pkg == CAR_SYSTEM) startupCarSystem()
            if (pkg != null && pkg != "com.android.systemui" && pkg != packageName + ".none") {
                if (pkg != CAR_SYSTEM && pkg != packageName && pkg !in otherHomes) { lastAppPkg = pkg; lastAppAt = SystemClock.elapsedRealtime() }
                currentPkg = pkg
            }
        }
    }
    override fun onInterrupt() {}

    // ---- Vakten som stopper bilens system før det åpner seg (se StartGuard)
    @Volatile private var guard: Process? = null
    @Volatile private var guardOk = false

    @Volatile private var guardWanted = false
    private fun startGuard() {
        guardWanted = true
        if (guard != null) return
        val apk = applicationInfo.sourceDir
        Thread({
            runCatching {
                val p = ProcessBuilder("su", "-c", "sh").redirectErrorStream(true).start()
                guard = p
                p.outputStream.write("CLASSPATH=$apk exec app_process /system/bin no.carlink.StartGuard\n".toByteArray())
                p.outputStream.flush()   // holdes åpen: når CarLink stopper, stopper vakten også
                p.inputStream.bufferedReader().useLines { lines ->
                    for (l in lines) {
                        if (l.contains("GUARD-OK")) { guardOk = true; AppLog.i("joystick", "start guard active") }
                        if (l.contains("GUARD-FAIL")) { guardOk = false; AppLog.i("joystick", "start guard failed: $l") }
                    }
                }
            }
            guardOk = false; guard = null
            AppLog.i("joystick", "start guard stopped – restarting")
            main.postDelayed({ if (guardWanted && guard == null && instance != null) startGuard() }, 3000)
        }, "carlink-guard").apply { isDaemon = true; start() }
    }

    private fun stopGuard() {
        guardWanted = false
        runCatching { guard?.outputStream?.close(); guard?.destroy() }
        guard = null; guardOk = false
    }

    private fun apply() {
        if (homeOnly(this)) startGuard() else stopGuard()
        if (wanted(this) || homeOnly(this)) start() else stop()
        if (wanted(this)) addOverlay() else removeOverlay()
        dot?.refresh()
    }

    // ------------------------------------------------------------------ knappene ved joysticken

    @Volatile private var scrollKeyAt = 0L
    @Volatile private var allowCarUntil = 0L
    private var homeUpAt = 0L
    private var homeFirstPkg: String? = null

    /** Scrolleknappen (0x32 opp / 0x33 ned) og Hjem (0x34: 1 ned, 2 holdes, 0 slipp). */
    private fun onCarButton(code: Int, state: Int) {
        val now = SystemClock.elapsedRealtime()
        when (code) {
            0x2a -> if (black != null) main.post { hideBlack() } else onMenu(state)
            0x32, 0x33 -> {
                scrollKeyAt = now
                if (state == 1 && wanted(this)) main.post { scrollAtDot(up = code == 0x32) }
            }
            // Hjem: dobbeltklikk (to slipp innen 0,5 s) bytter mellom Android og bilens system
            0x34 -> if (black != null) { homeKeyAt = now; main.post { hideBlack() } } else if (state == 0) {
                homeKeyAt = now
                val gap = now - homeUpAt
                if (homeUpAt == 0L || gap > 500) {
                    homeUpAt = now
                    homeFirstPkg = currentPkg ?: frontPkg()
                } else {
                    homeUpAt = 0L
                    if (homeFirstPkg == CAR_SYSTEM) {
                        AppLog.i("joystick", "double Home in car system → Android")   // første trykk ga allerede Hjem
                        main.post { performGlobalAction(GLOBAL_ACTION_HOME) }      // i tilfelle bilen ikke sendte Hjem
                    } else {
                        AppLog.i("joystick", "double Home → car system")
                        allowCarUntil = now + 6000
                        // Bilen sender «Hjem» når knappen slippes – åpne bilens system rett etter
                        main.postDelayed({ openCarSystem() }, 350)
                    }
                }
            }
        }
    }

    /** Bilens system på forespørsel (Meny-knappen). */
    fun openCarSystemNow() {
        allowCarUntil = SystemClock.elapsedRealtime() + 6000
        openCarSystem()
    }

    // ---- Meny-knappen (0x2a): kort, to trykk eller hold
    private var menuDownAt = 0L
    private var menuLongFired = false
    private var menuUpAt = 0L
    private val menuShort = Runnable { menuUpAt = 0L; MenuButton.run(this, MenuButton.get(this, MenuButton.Press.SHORT), this) }

    private fun onMenu(state: Int) {
        val now = SystemClock.elapsedRealtime()
        when (state) {
            1 -> { menuDownAt = now; menuLongFired = false }
            2 -> if (!menuLongFired) { menuLongFired = true; main.post { MenuButton.run(this, MenuButton.get(this, MenuButton.Press.LONG), this) } }
            0 -> {
                if (menuLongFired) return
                if (menuDownAt > 0 && now - menuDownAt > 650) {            // holdt inne uten eget «holdes»-signal
                    main.post { MenuButton.run(this, MenuButton.get(this, MenuButton.Press.LONG), this) }; return
                }
                val doubleAction = MenuButton.get(this, MenuButton.Press.DOUBLE)
                if (menuUpAt > 0 && now - menuUpAt < 450) {
                    main.removeCallbacks(menuShort); menuUpAt = 0L
                    main.post { MenuButton.run(this, doubleAction, this) }
                } else if (doubleAction == "none") {
                    main.post(menuShort)                                     // ingen dobbelt: ingen venting
                } else {
                    menuUpAt = now
                    main.postDelayed(menuShort, 450)
                }
            }
        }
    }

    private fun openCarSystem() {
        allowCarUntil = SystemClock.elapsedRealtime() + 6000
        runCatching {
            val i = packageManager.getLaunchIntentForPackage(CAR_SYSTEM) ?: return
            startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // Appen du var i sist (ikke CarLinks hjemskjerm, ikke bilens system)
    @Volatile private var lastAppPkg: String? = null
    @Volatile private var lastAppAt = 0L
    @Volatile private var homeKeyAt = 0L

    /** Gå tilbake til appen du var i (brukes når en pilknapp ved et uhell sendte deg ut av den). */
    private fun backToLastApp(why: String): Boolean {
        val app = lastAppPkg ?: return false
        val i = packageManager.getLaunchIntentForPackage(app) ?: return false
        AppLog.i("joystick", "$why → back to $app")
        runCatching {
            startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return true
    }

    /** Bilens system åpnet av scrolleknappen: gå rett tilbake. (Ryggekamera og dobbel Hjem får være.) */
    private fun carSystemShown() {
        val now = SystemClock.elapsedRealtime()
        val arrow = now - scrollKeyAt < 2500
        AppLog.i("joystick", "car system shown (guard=${guardOk}, recent arrow=$arrow)")
        if (!homeOnly(this) || now < allowCarUntil || !arrow) return
        // Vakten skulle ha stoppet dette – den rakk det ikke. Tilbake til appen du var i.
        if (!backToLastApp("car system after arrow button")) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** CarLinks hjemskjerm dukket opp rett etter en pilknapp (uten at du trykket Hjem): tilbake til appen. */
    private fun carLinkShown() {
        val now = SystemClock.elapsedRealtime()
        if (now - scrollKeyAt > 1500 || now - homeKeyAt < 2000 || now < allowCarUntil) return
        if (currentPkg == packageName || lastAppPkg == null || currentPkg != lastAppPkg) return
        backToLastApp("home screen after arrow button")
    }

    private fun scrollAtDot(up: Boolean) {
        val x = if (cx >= 0) cx else screenW / 2
        val y = if (cy >= 0) cy else screenH / 2
        val d = (screenH * 0.3f)
        val y0 = (y - d / 2).coerceIn(10f, screenH - 10f)
        val y1 = (y + d / 2).coerceIn(10f, screenH - 10f)
        // Rulle opp = fingeren drar nedover
        val p = Path().apply { if (up) { moveTo(x, y0); lineTo(x, y1) } else { moveTo(x, y1); lineTo(x, y0) } }
        runCatching {
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, 220)).build(), null, null)
        }
    }

    // ------------------------------------------------------------------ taster fra bilen

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (!readerOn || !wanted(this)) return false
        // Bilens Tab/Enter kommer uten ekte enhet (sendt av CarInfoService) – de blokkeres,
        // så markeringen ikke hopper. Tastatur og rattknapper slipper igjennom.
        if (e.keyCode == KeyEvent.KEYCODE_TAB) return true   // ingen tastatur i bilen: Tab kommer alltid fra joysticken
        val fromCar = InputDevice.getDevice(e.deviceId)?.let { it.isVirtual } ?: true
        if (fromCar && (e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) return true
        return false
    }

    // ------------------------------------------------------------------ start / stopp

    private fun start() {
        if (readerOn) return
        readerOn = true
        reader = Thread({ readLoop() }, "carlink-joymouse").apply { isDaemon = true; start() }
    }

    private fun stop() {
        readerOn = false
        runCatching { proc?.destroy() }
        proc = null
        reader?.interrupt(); reader = null
        removeOverlay()
    }

    /** Leser bilens meldinger fra systemloggen. Starter på nytt hvis noe stopper. */
    private fun readLoop() {
        while (readerOn) {
            runCatching {
                val p = ProcessBuilder("su", "-c", "sh").redirectErrorStream(true).start()
                proc = p
                readerStartedAt = SystemClock.elapsedRealtime()
                p.outputStream.write("exec logcat -v raw -T 1 -s XCarBusLexusCar:D\n".toByteArray())
                p.outputStream.flush()
                p.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!readerOn) break
                        parse(line)
                    }
                }
            }
            runCatching { proc?.destroy() }
            if (readerOn) runCatching { Thread.sleep(3000) }
        }
    }

    private fun parse(line: String) {
        val i = line.indexOf("rxData:")
        if (i < 0) return
        val end = line.indexOf(',', i).let { if (it < 0) line.length else it }
        val parts = line.substring(i + 7, end).trim().split(' ')
        if (parts.size < 4) return
        val b = IntArray(parts.size) { parts[it].toIntOrNull(16) ?: -1 }
        when {
            // Posisjon: 57 12 … byte 8 = 0x10, x i byte 12, y i byte 13
            // Trykk-melding (byte 8 = 0): ta fokus før bilens Enter kommer
            b[0] == 0x57 && b.size >= 14 && b[8] == 0 && wanted(this) -> {
                val down = b[12] != 0 || b[13] != 0
                main.post { joyActive(); if (down && SystemClock.elapsedRealtime() - readerStartedAt > 800) onPress(true) }
            }
            b[0] == 0x57 && b.size >= 14 && b[8] == 0x10 && wanted(this) -> {
                lastFrameAt = SystemClock.elapsedRealtime()
                main.post { joyActive() }
                onPosition(b[12], b[13])
            }
            // Trykk: 22 02 10 01 = ned, 22 02 10 00 = opp
            b[0] == 0x22 && b.size >= 4 && b[2] != 0x10 && b[2] != 0x81 && b[2] != 0x82 &&
                SystemClock.elapsedRealtime() - readerStartedAt > 800 -> onCarButton(b[2], b[3])
            b[0] == 0x22 && b.size >= 4 && b[2] == 0x10 && wanted(this) &&
                SystemClock.elapsedRealtime() - readerStartedAt > 800 -> {
                val down = b[3] == 1
                main.post { onPress(down) }
            }
        }
    }

    // ------------------------------------------------------------------ bevegelse

    private fun axis(v: Int): Float = ((v - 10) / 235f).coerceIn(0f, 1f)

    private fun onPosition(x: Int, y: Int) {
        if (black != null) { if (shouldWake(x, y)) main.post { hideBlack() }; return }
        lastRawX = x; lastRawY = y
        if (logging()) log("CAR pos x=$x y=$y")
        val l = learn
        l?.observe(x, y)
        val nx = l?.mapX(x) ?: axis(x)
        var ny = l?.mapY(y) ?: axis(y)
        // Bilens y-akse går motsatt vei av skjermen; «Inverted» snur den tilbake
        if (!prefs().getBoolean("joyInvertY", false)) ny = 1f - ny
        tx = nx * (screenW - 1)
        ty = ny * (screenH - 1)
        main.post { kick() }
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (tx < 0) { animating = false; return }
            val now = SystemClock.uptimeMillis()
            if (now - lastHoverCheck > 300) { lastHoverCheck = now; collectTargets() }
            // Trykk: stikka flytter seg litt når den trykkes ned. Prikken holdes i ro der den var
            // like før trykket, helt til du tydelig drar (da blir det rulling).
            if (pressed && !dragging && hypot(tx - rawPressX, ty - rawPressY) > (learn?.dragThreshold() ?: 70f) && Build.VERSION.SDK_INT >= 26) {
                dragging = true; strokeX = downX; strokeY = downY; stroke = null
                log("DRAG start from %.0f,%.0f".format(downX, downY))
            }
            when {
                pressed && !dragging -> { sx = downX; sy = downY }
                now < holdUntil -> { sx = holdX; sy = holdY }
                pressed -> { sx = tx; sy = ty; snapTarget = null }
                else -> snap(tx, ty)   // magnet mot nærmeste knapp
            }
            if (cx < 0) { cx = sx; cy = sy }
            val dx = sx - cx; val dy = sy - cy
            val f = learn?.follow() ?: 0.45f
            cx += dx * f; cy += dy * f
            placeDot()
            record(now)
            if (logging()) log("FRAME raw=%.0f,%.0f target=%.0f,%.0f dot=%.0f,%.0f pressed=%s drag=%s snap=%s".format(
                tx, ty, sx, sy, cx, cy, pressed, dragging, snapTarget?.flattenToString() ?: "-"))
            if (pressed && dragging) dragUpdate()
            hoverUpdate()
            if (abs(dx) > 0.5f || abs(dy) > 0.5f) {
                lastMoveAt = now
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                animating = false
                main.removeCallbacks(fade); main.postDelayed(fade, 10_000)
            }
        }
    }

    private fun kick() {
        dot?.let { if (it.alpha < 1f) it.animate().alpha(1f).setDuration(120).start() }
        main.removeCallbacks(fade)
        if (!animating) { animating = true; Choreographer.getInstance().postFrameCallback(frame) }
    }

    private val fade = Runnable { if (!pressed) dot?.animate()?.alpha(0f)?.setDuration(600)?.start() }

    private fun placeDot() {
        val d = dot ?: return
        d.translationX = cx - d.width / 2f
        d.translationY = cy - d.height / 2f
    }

    // ------------------------------------------------------------------ klikk og dra

    // Hvor prikken var de siste ~0,5 s (for å finne stedet like før trykket)
    private val histT = LongArray(32)
    private val histX = FloatArray(32)
    private val histY = FloatArray(32)
    private var histI = 0
    private var histN = 0
    private var rawPressX = 0f
    private var rawPressY = 0f
    private var holdUntil = 0L
    private var holdX = 0f
    private var holdY = 0f

    // ------------------------------------------------------------------ musopptak (Settings → Joystick)

    private var logUntil = 0L
    private var logStart = 0L
    private val logBuf = StringBuilder(256 * 1024)

    fun startLog(seconds: Int) {
        logBuf.setLength(0)
        logStart = SystemClock.uptimeMillis(); logUntil = logStart + seconds * 1000L
        logBuf.append("CarLink mouse log · ${Build.MODEL} · screen ${screenW.toInt()}x${screenH.toInt()} · snap=${prefs().getInt("joySnap", 1)} invertY=${prefs().getBoolean("joyInvertY", false)}\n")
        logBuf.append("time ms · event\n")
        main.postDelayed({ finishLog() }, seconds * 1000L + 200)
    }

    private fun logging() = SystemClock.uptimeMillis() < logUntil

    private fun log(line: String) {
        if (!logging() || logBuf.length > 3_000_000) return
        synchronized(logBuf) { logBuf.append(SystemClock.uptimeMillis() - logStart).append(' ').append(line).append('\n') }
    }

    private fun finishLog() {
        logUntil = 0
        val text = synchronized(logBuf) { logBuf.toString() }
        val name = "CarLink-mouse-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".txt"
        val sent = Hub.send(org.json.JSONObject().put("t", "joylog").put("name", name).put("text", text))
        runCatching { java.io.File(getExternalFilesDir(null) ?: filesDir, name).writeText(text) }
        android.widget.Toast.makeText(this,
            if (sent) "Mouse log sent to the phone – tap the notification there to share it" else "Phone not connected – log saved on the screen",
            android.widget.Toast.LENGTH_LONG).show()
    }

    private val histRX = FloatArray(32)
    private val histRY = FloatArray(32)

    private fun record(now: Long) {
        histRX[histI] = tx; histRY[histI] = ty
        histT[histI] = now; histX[histI] = cx; histY[histI] = cy
        histI = (histI + 1) % 32; if (histN < 32) histN++
    }

    /**
     * Stedet du siktet på: siste øyeblikk før trykket der stikka sto (nesten) stille.
     * Finner ingen slikt øyeblikk de siste 0,45 s → posisjonen 0,18 s før.
     */
    private fun aimBefore(now: Long): Triple<Float, Float, Float> {
        var prev = -1
        for (k in 0 until histN) {
            val i = (histI - 1 - k + 32) % 32
            val age = now - histT[i]
            if (age > 450) break
            if (age >= 40 && prev >= 0) {
                val v = hypot(histRX[prev] - histRX[i], histRY[prev] - histRY[i])
                if (v < 6f) return Triple(histX[i], histY[i], hypot(tx - histRX[i], ty - histRY[i]))
            }
            prev = i
        }
        val (x, y) = posBefore(now - 180)
        return Triple(x, y, -1f)
    }

    private fun posBefore(t: Long): Pair<Float, Float> {
        var best = -1; var oldest = -1
        for (k in 0 until histN) {
            val i = (histI - 1 - k + 32) % 32
            oldest = i
            if (histT[i] <= t) { best = i; break }
        }
        val i = if (best >= 0) best else oldest
        return if (i >= 0) histX[i] to histY[i] else cx to cy
    }

    private fun onPress(down: Boolean) {
        if (black != null) {   // trykk vekker bare skjermen, klikker ikke (men ikke rett etter at den ble slått av)
            if (down && SystemClock.uptimeMillis() - blackAt > 1500) hideBlack()
            return
        }
        log("PRESS ${if (down) "down" else "up"} dot=%.0f,%.0f raw=%.0f,%.0f".format(cx, cy, tx, ty))
        if (cx < 0) return
        if (down) {
            if (pressed) return
            pressed = true; dragging = false; releasePending = false
            val now = SystemClock.uptimeMillis()
            val (px, py, slipNow) = aimBefore(now)
            downX = px; downY = py; downAt = now
            rawPressX = tx; rawPressY = ty
            cx = px; cy = py; placeDot()
            log("PRESS aims at %.0f,%.0f (slip so far %.0f)".format(px, py, slipNow))
            kick()
            dot?.setPressed(true)
        } else {
            if (!pressed) return
            pressed = false
            // Stikka spretter tilbake når den slippes: hold prikken der den var (etter rulling: der rullingen sluttet)
            holdX = cx; holdY = cy
            holdUntil = SystemClock.uptimeMillis() + (if (dragging) 450 else 250)
            log("RELEASE at %.0f,%.0f dragging=%s held=%dms".format(cx, cy, dragging, SystemClock.uptimeMillis() - downAt))
            dot?.setPressed(false)
            if (dragging) {
                releasePending = true
                if (!strokeBusy) dragStep(end = true)
            } else {
                learn?.pressSlip(hypot(tx - rawPressX, ty - rawPressY) + 0f)
                val held = SystemClock.uptimeMillis() - downAt
                tap(downX, downY, if (held > 650) 900L else 60L)
            }
        }
    }

    // Bom: nytt klikk like ved innen 1,5 s, eller Tilbake innen 2 s
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastTapPkg: String? = null
    private var lastTapJudged = true
    private val judgeHit = Runnable { if (!lastTapJudged) { lastTapJudged = true; learn?.tapResult(lastTapPkg, miss = false) } }

    private fun judgeTap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (!lastTapJudged && now - lastTapAt < 1500 && hypot(x - lastTapX, y - lastTapY) < 140f) {
            lastTapJudged = true; learn?.tapResult(lastTapPkg, miss = true); log("LEARN miss in $lastTapPkg")
        } else if (!lastTapJudged) { lastTapJudged = true; learn?.tapResult(lastTapPkg, miss = false) }
        main.removeCallbacks(judgeHit)
        lastTapAt = now; lastTapX = x; lastTapY = y; lastTapPkg = currentPkg; lastTapJudged = false
        main.postDelayed(judgeHit, 1600)
    }

    private fun backAfterTap() {
        if (!lastTapJudged && SystemClock.uptimeMillis() - lastTapAt < 2000) {
            lastTapJudged = true; main.removeCallbacks(judgeHit); learn?.tapResult(lastTapPkg, miss = true)
            log("LEARN miss (back) in $lastTapPkg")
        }
    }

    private fun tap(x: Float, y: Float, duration: Long) {
        if (duration < 300) judgeTap(x, y)
        log("TAP at %.0f,%.0f duration=%d".format(x, y, duration))
        val p = Path().apply { moveTo(x, y) }
        runCatching {
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, duration)).build(), null, null)
        }
    }

    private fun dragUpdate() {
        if (!strokeBusy) dragStep(end = false)
    }

    /** Hold-og-dra: en sammenhengende berøring som følger prikken (rulling i lister og kart). */
    private fun dragStep(end: Boolean) {
        if (Build.VERSION.SDK_INT < 26) return
        var toX = cx; var toY = cy
        if (abs(toX - strokeX) < 1f && abs(toY - strokeY) < 1f) toY = strokeY + 1f   // tom strek godtas ikke
        val path = Path().apply { moveTo(strokeX, strokeY); lineTo(toX, toY) }
        val s = runCatching {
            stroke?.continueStroke(path, 0, 40, !end) ?: GestureDescription.StrokeDescription(path, 0, 40, !end)
        }.getOrNull() ?: run { stroke = null; return }
        log("DRAG step %.0f,%.0f -> %.0f,%.0f end=%s".format(strokeX, strokeY, toX, toY, end))
        stroke = s; strokeX = toX; strokeY = toY; strokeBusy = true
        val ok = runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(s).build(), object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) = next()
                override fun onCancelled(g: GestureDescription?) { stroke = null; next() }
                private fun next() {
                    strokeBusy = false
                    if (end) { stroke = null; dragging = false; releasePending = false; return }
                    if (releasePending) dragStep(end = true) else if (pressed && dragging) dragStep(end = false)
                }
            }, main)
        }.getOrDefault(false)
        if (!ok) { strokeBusy = false; stroke = null }
    }

    // ------------------------------------------------------------------ hover (lyd og større prikk)

    /** Knapper på skjermen akkurat nå (hentes ca. 3 ganger i sekundet mens prikken flyttes). */
    private val targets = ArrayList<Rect>(256)
    private val covers = ArrayList<Rect>(8)
    private var sx = -1f
    private var sy = -1f
    private var snapTarget: Rect? = null

    private fun collectTargets() {
        targets.clear(); covers.clear()
        runCatching {
            val ws = windows ?: return@runCatching
            for (w in ws.sortedByDescending { it.layer }) {
                // Prikk-laget vårt (og andre overlegg) dekker hele skjermen, men har ingen knapper
                if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) { w.recycle(); continue }
                val wb = Rect(); w.getBoundsInScreen(wb)
                val root = w.root
                if (root != null) { collect(root, 0); root.recycle() }
                covers.add(wb)
                w.recycle()
                if (targets.size > 400) break
            }
        }
    }

    private fun collect(n: AccessibilityNodeInfo, depth: Int) {
        if (depth > 40 || targets.size > 400 || !n.isVisibleToUser) return
        val r = Rect(); n.getBoundsInScreen(r)
        if (r.isEmpty) return
        if ((n.isClickable || n.isLongClickable) &&
            r.width().toLong() * r.height() < (screenW * screenH / 4).toLong() &&
            covers.none { it.contains(r.centerX(), r.centerY()) }) targets.add(r)   // ikke skjult av et vindu over
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            collect(c, depth + 1); c.recycle()
        }
    }

    /** Av / Lett / Sterk – hvor langt unna knappene drar, og hvor hardt. */
    private fun snap(x: Float, y: Float) {
        val level = prefs().getInt("joySnap", 1)
        if (level == 0 || targets.isEmpty()) { sx = x; sy = y; snapTarget = null; return }
        val b = learn?.boost(currentPkg) ?: 0f   // lært: sterkere magnet i apper der du bommer
        val range = (if (level == 2) 80f else 36f) * (1f + b * 1.5f)
        val pull = ((if (level == 2) 0.9f else 0.55f) + b).coerceAtMost(0.95f)
        // Sitter fast på knappen vi allerede er på til du er et stykke utenfor (Sterk)
        snapTarget?.let { t ->
            val hold = if (level == 2) range * 0.7f else 14f
            if (dist(t, x, y) <= hold) { pullTo(t, x, y, range, pull); return }
        }
        var best: Rect? = null; var bd = Float.MAX_VALUE
        for (t in targets) {
            val d = dist(t, x, y)
            if (d < bd || (d == 0f && bd == 0f && t.width() * t.height() < best!!.width() * best.height())) { bd = d; best = t }
        }
        if (best == null || bd > range) { sx = x; sy = y; snapTarget = null; return }
        snapTarget?.let { cur -> if (cur != best && dist(cur, x, y) <= bd + 12f) best = cur }
        snapTarget = best
        pullTo(best!!, x, y, range, pull)
    }

    private fun pullTo(t: Rect, x: Float, y: Float, range: Float, pull: Float) {
        val d = dist(t, x, y)
        var k = pull * (1f - (d / range).coerceIn(0f, 1f))
        if (d == 0f) k *= 0.6f   // allerede på knappen: bare et mykt drag mot midten
        // Brede eller høye flater (f.eks. hele rader i en liste) trekker bare på tvers, ellers
        // ville prikken hoppe mot midten av raden mens du beveger deg bortover den.
        val kx = if (t.width() <= 220) k else 0f
        val ky = if (t.height() <= 220) k else 0f
        sx = x + (t.exactCenterX() - x) * kx
        sy = y + (t.exactCenterY() - y) * ky
    }

    private fun dist(r: Rect, x: Float, y: Float): Float {
        val dx = maxOf(r.left - x, 0f, x - r.right)
        val dy = maxOf(r.top - y, 0f, y - r.bottom)
        return hypot(dx, dy)
    }

    /** Større prikk og en myk lyd når prikken kommer inn på en knapp. */
    private fun hoverUpdate() {
        val x = cx.toInt(); val y = cy.toInt()
        var found: Rect? = null
        for (t in targets) if (t.contains(x, y) && (found == null || t.width() * t.height() < found.width() * found.height())) found = t
        val key = found?.flattenToString()
        if (key != null && key != hoverKey) HoverSound.play(this)
        hoverKey = key
        dot?.setHot(found != null)
    }

    // ------------------------------------------------------------------ prikken

    private fun addOverlay() {
        if (overlay != null) return
        val w = wm ?: return
        val pt = Point(); @Suppress("DEPRECATION") w.defaultDisplay.getRealSize(pt)
        screenW = pt.x.toFloat(); screenH = pt.y.toFloat()
        val root = KeyEater(this)
        val d = DotView(this)
        root.addView(d, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        d.alpha = 0f
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        @Suppress("DEPRECATION") run { root.systemUiVisibility = barsFlags() }
        runCatching { w.addView(root, lp); overlay = root; dot = d; overlayLp = lp; keyFocus = false }
    }

    // ---- Bilens Tab/Enter går forbi tilgjengelighetsfilteret, rett til vinduet som har fokus.
    // Mens joysticken brukes, tar prikk-laget fokus og spiser dem, så appen under ikke hopper.
    private var overlayLp: WindowManager.LayoutParams? = null
    private var keyFocus = false
    private val dropFocus = Runnable { setKeyFocus(false) }

    private fun joyActive() {
        main.removeCallbacks(dropFocus)
        if (!keyFocus) setKeyFocus(true)
        main.postDelayed(dropFocus, 3000)
    }

    private fun setKeyFocus(on: Boolean) {
        val o = overlay ?: return; val lp = overlayLp ?: return
        keyFocus = on
        @Suppress("DEPRECATION") run { o.systemUiVisibility = barsFlags() }
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                   else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { wm?.updateViewLayout(o, lp) }
        if (on) { o.isFocusableInTouchMode = true; o.requestFocus() }
    }

    /**
     * Når prikk-laget har fokus, er det laget som bestemmer om status- og navigasjonslinja vises.
     * Det må derfor be om å skjule dem på samme måte som CarLink (valg under Settings → Screen).
     */
    @Suppress("DEPRECATION")
    private fun barsFlags(): Int =
        if (prefs().getBoolean("showBars", false)) 0
        else View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

    private val mediaKeys = setOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_RECORD, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)

    private inner class KeyEater(ctx: Context) : FrameLayout(ctx) {
        init { isFocusable = true; isFocusableInTouchMode = true }
        override fun dispatchKeyEvent(e: KeyEvent): Boolean = runCatching { eat(e) }.getOrDefault(true)

        private fun eat(e: KeyEvent): Boolean {
            log("KEY ${KeyEvent.keyCodeToString(e.keyCode)} ${if (e.action == KeyEvent.ACTION_DOWN) "down" else "up"} meta=${e.metaState}")
            when (e.keyCode) {
                KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                KeyEvent.KEYCODE_BACK -> { if (e.action == KeyEvent.ACTION_UP) { backAfterTap(); performGlobalAction(GLOBAL_ACTION_BACK) }; return true }
            }
            if (e.keyCode in mediaKeys) {
                // Rattknappene skal fortsatt styre musikken
                runCatching { getSystemService(android.media.AudioManager::class.java).dispatchMediaKeyEvent(e) }
                return true
            }
            return false
        }
    }

    // ------------------------------------------------------------------ «skjerm av»

    private var black: View? = null
    private var blackAt = 0L
    private var blackRawX = -1
    private var blackRawY = -1
    private var lastRawX = -1
    private var lastRawY = -1

    /** Stikka sklir litt etter trykket på «skjerm av»: vent 1,5 s, og krev en tydelig bevegelse. */
    private fun shouldWake(x: Int, y: Int): Boolean {
        if (SystemClock.uptimeMillis() - blackAt < 1500) { blackRawX = x; blackRawY = y; return false }
        if (blackRawX < 0) { blackRawX = x; blackRawY = y; return false }
        return abs(x - blackRawX) + abs(y - blackRawY) > 12
    }
    val isBlack get() = black != null

    /** Svart skjerm og lyset av. Musikken spiller videre. Beveg joysticken eller trykk for å vekke. */
    fun blackout(on: Boolean) = main.post { if (on) showBlack() else hideBlack() }

    @Suppress("DEPRECATION")
    private fun showBlack() {
        if (black != null) return
        val w = wm ?: return
        val v = object : FrameLayout(this) {
            init { isFocusable = true; isFocusableInTouchMode = true; setBackgroundColor(0xFF000000.toInt()) }
            override fun onTouchEvent(e: android.view.MotionEvent): Boolean { if (e.action == android.view.MotionEvent.ACTION_UP) hideBlack(); return true }
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode in mediaKeys) { runCatching { getSystemService(android.media.AudioManager::class.java).dispatchMediaKeyEvent(e) }; return true }
                if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return false
                if (e.action == KeyEvent.ACTION_UP) hideBlack()
                return true
            }
        }
        v.systemUiVisibility = barsFlags() or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE)
        runCatching { w.addView(v, lp); black = v; v.requestFocus() }
        blackAt = SystemClock.uptimeMillis(); blackRawX = lastRawX; blackRawY = lastRawY
        dot?.alpha = 0f
        Brightness.blackout(this, true)
        AppLog.i("screen", "screen off")
    }

    private fun hideBlack() {
        val v = black ?: return
        black = null
        runCatching { wm?.removeView(v) }
        Brightness.blackout(this, false)
        AppLog.i("screen", "screen on")
    }

    private fun removeOverlay() {
        overlay?.let { o -> runCatching { wm?.removeView(o) } }
        main.removeCallbacks(dropFocus)
        overlay = null; dot = null; overlayLp = null; keyFocus = false
    }

    /** Samme prikk som i CarLink: mørk kant, hvit ring og coverfargen – større over knapper. */
    private class DotView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var hot = false
        private var down = false
        private var sizePx = 26.dp

        init { refresh() }

        fun refresh() {
            val s = PointerRoot.size(context)
            sizePx = (if (s == PointerRoot.Size.OFF) PointerRoot.Size.NORMAL.dp else s.dp).dp
            requestLayout(); invalidate()
        }

        fun setHot(h: Boolean) { if (h != hot) { hot = h; invalidate() } }
        fun setPressed2(d: Boolean) { if (d != down) { down = d; invalidate() } }

        override fun setPressed(pressed: Boolean) { setPressed2(pressed) }

        override fun onMeasure(w: Int, h: Int) { val s = (sizePx * 1.4f).toInt(); setMeasuredDimension(s, s) }

        override fun onDraw(c: Canvas) {
            val full = width / 2f
            var r = if (hot) full else full / 1.4f
            if (down) r *= 0.82f
            val cx = width / 2f; val cy = height / 2f
            val accent = Ambient.theme.value.accent
            p.color = 0x66000000; c.drawCircle(cx, cy, r, p)
            p.color = 0xFFFFFFFF.toInt(); c.drawCircle(cx, cy, r - 2.dp, p)
            p.color = accent or 0xFF000000.toInt(); c.drawCircle(cx, cy, r - 5.dp, p)
            if (hot) { p.color = 0xFFFFFFFF.toInt(); c.drawCircle(cx, cy, r * 0.22f, p) }
        }
    }
}
