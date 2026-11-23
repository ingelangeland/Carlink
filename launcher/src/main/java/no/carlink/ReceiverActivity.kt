package no.carlink

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import no.carlink.launcher.R

/**
 * Skjermsiden – hjemmeskjermen i bilen.
 * Venstre: meny-skinne. Høyre: 1–3 paneler (kart / musikk / apper) med justerbar fordeling.
 */
class ReceiverActivity : AppCompatActivity() {

    private lateinit var split: SplitLayout
    private lateinit var home: HomeView
    private lateinit var drawer: AppDrawer
    private lateinit var settings: SettingsPanel
    private lateinit var rail: View
    private lateinit var pointerRoot: PointerRoot
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private val panes = mutableListOf<PaneType>()
    private val countButtons = mutableListOf<TextView>()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LinkService.startReceiver(this)
        guide.next()
    }
    private val guide = SetupGuide(this)
    private val homeRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { guide.next() }

    private fun isDefaultHome(): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29)
            getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
        else packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName == packageName
    }.getOrDefault(true)

    /** Android's own "use as home screen" prompt (Android 10+), or the home-app settings page on older versions. */
    private fun requestDefaultHome() {
        if (android.os.Build.VERSION.SDK_INT >= 29) runCatching {
            val rm = getSystemService(android.app.role.RoleManager::class.java)
            homeRole.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME)); return
        }
        Launch.start(this, Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private val rehideBars = Runnable { applySystemBars() }

    /** Skjuler status- og navigasjonslinja (valg under Settings → Screen). */
    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        val show = prefs().getBoolean("showBars", false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (show) {
            c.show(WindowInsetsCompat.Type.systemBars())
            if (android.os.Build.VERSION.SDK_INT < 30) window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            return
        }
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
        if (android.os.Build.VERSION.SDK_INT < 30) {
            // Eldre Android (bilskjermen kjører 9): de klassiske flaggene er mest pålitelige
            window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    /** Rattknappene (neste/forrige/play/pause) – går til telefonen når den spiller, ellers lokalt. */
    private var lastTab = 0L
    private var tabAccepted = false

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Joysticken som mus: bilens Tab/Enter skal ikke flytte markeringen i CarLink
        if (prefs().getBoolean("joyMouse", false) && JoyMouseService.running()) {
            val k = event.keyCode
            val virtualDev = event.device?.isVirtual ?: true
            if (k == android.view.KeyEvent.KEYCODE_TAB ||
                (virtualDev && (k == android.view.KeyEvent.KEYCODE_ENTER || k == android.view.KeyEvent.KEYCODE_DPAD_CENTER))) return true
        }
        // Bilens joystick sender 3–5 «Tab» rett etter hverandre for ett skyv: gjør det til ett steg
        if (event.keyCode == android.view.KeyEvent.KEYCODE_TAB && prefs().getBoolean("joyCollapse", true)) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val gap = event.eventTime - lastTab
                lastTab = event.eventTime
                tabAccepted = gap > 200
                if (!tabAccepted) return true
            } else if (!tabAccepted) return true
        }
        val a = MediaKeys.actionFor(event.keyCode)
        if (a != null) {
            if (event.action == android.view.KeyEvent.ACTION_UP) Hub.mediaCommand(a)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        applySystemBars()
        // Neste manglende tillatelse (etter at Androids egne spørsmål er besvart)
        if (Perms.missing(this, Perms.forRole(false)).isEmpty()) guide.next()
        if (::settings.isInitialized) settings.refresh()   // status for tilganger oppdateres når du kommer tilbake
    }

    /** Bilenheten bytter dag/natt (ofte med frontlysene): ingen omstart, bare kartet tilpasser seg. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        DayNight.onSystem(this, newConfig)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugFlags.noVisualizer = intent?.getBooleanExtra("noviz", false) == true
        DebugFlags.noArt = intent?.getBooleanExtra("noart", false) == true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        DayNight.onSystem(this, resources.configuration)
        // Sjekk dag/natt hvert minutt mens skjermen vises (billig: én liten utregning)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { DayNight.update(this@ReceiverActivity); kotlinx.coroutines.delay(60_000) }
            }
        }
        // Lydbølger for musikk som spilles på skjermen selv – bare mens CarLink vises
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    kotlinx.coroutines.flow.combine(Hub.connected, Hub.remoteMedia, Hub.localMedia) { c, r, l ->
                        l?.playing == true && !Hub.useRemote(c, r, l)
                    }.collect { LocalViz.update(this@ReceiverActivity, it) }
                } finally { LocalViz.stop(this@ReceiverActivity) }
            }
        }
        // Look for a new version at start and every 6 hours (a notification appears if there is one)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (Updater.autoCheck(this@ReceiverActivity)) Updater.check(this@ReceiverActivity, 6 * 3_600_000L, notify = true)
                    kotlinx.coroutines.delay(6 * 3_600_000L)
                }
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBars()
        // Kommer linjene fram (sveip, dialog, annen app), skjules de igjen etter noen sekunder
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener { vis ->
            if (vis and android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0) {
                window.decorView.removeCallbacks(rehideBars)
                window.decorView.postDelayed(rehideBars, 3000)
            }
        }

        // Lag 1: albumcoveret over hele skjermen. Lag 2: meny + innhold (hjem eller delt skjerm).
        val frame = PointerRoot(this).apply { setBackgroundColor(C.BG); refresh() }
        pointerRoot = frame
        // «content» = alt som blir uskarpt bak app-oversikten
        val content = FrameLayout(this)
        frame.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        content.addView(ArtBackground(this), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rail = buildRail()
        root.addView(rail, LinearLayout.LayoutParams(88.dp, LinearLayout.LayoutParams.MATCH_PARENT))
        val stack = FrameLayout(this)
        split = SplitLayout(this)
        split.onWeightsChanged = { saveState() }
        stack.addView(split, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            setMargins(10.dp, 10.dp, 10.dp, 10.dp)
        })
        home = HomeView(this, object : HomeView.Actions {
            override fun openMap() = setLayout(listOf(PaneType.MAP))
            override fun openMusic() = setLayout(listOf(PaneType.MUSIC))
            override fun openApps() = drawer.open()
            override fun openSplit() {
                if (panes.size < 2) setLayout(listOf(PaneType.MAP, PaneType.MUSIC, PaneType.APPS)) else showSplit()
            }
            override fun openSettings() = showSettings()
        })
        stack.addView(home, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(stack, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        content.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        drawer = AppDrawer(this, content)
        frame.addView(drawer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        settings = SettingsPanel(this, content, settingsHost)
        frame.addView(settings, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(frame)

        // Som hjemmeskjerm skal «tilbake» ikke lukke appen.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (settings.isOpen) settings.close() else if (drawer.isOpen) drawer.close() }
        })

        loadState()
        if (prefs().getString("mode", "home") == "home") showHome() else showSplit()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Hub.navRequests.collect { Launch.navigate(this@ReceiverActivity, it) } }
                launch { Hub.mapsLinks.collect { Launch.openMapsUrl(this@ReceiverActivity, it) } }
                launch { Ambient.theme.collect { applyChromeTheme(it) } }
                launch {
                    combine(Hub.connected, Hub.status) { c, s -> c to s }.collect { (c, s) ->
                        statusDot.background = rounded(if (c) C.OK else C.WARN, 6)
                        statusText.text = if (c) "Connected" else if (s.startsWith("Waiting")) "Waiting" else "Disconnected"
                    }
                }
            }
        }

        val demo = intent?.getBooleanExtra("demo", false) == true || prefs().getBoolean("demo", false)
        if (!demo) {
            val miss = Perms.missing(this, Perms.forRole(false))
            if (miss.isEmpty()) LinkService.startReceiver(this) else permLauncher.launch(miss.toTypedArray())
        } else DemoFeeder.start(this)
        if (!demo) {
            guide.add(SetupGuide.Step("home", "CarLink as home screen", null,
                done = { isDefaultHome() },
                action = { requestDefaultHome() }))
            guide.add(SetupGuide.Step("notif", "Show music playing on the screen",
                "To show the cover and control music playing on the screen itself (e.g. Tidal in the car), CarLink needs notification access.\n\n" +
                    "Turn on \"CarLink\" on the next page.",
                done = { MediaListener.isEnabled(this) },
                action = { SetupGuide.openNotificationAccess(this) }))
        }
        handleIntent(intent)
    }

    // ------------------------------------------------------------- meny-skinne

    private fun buildRail(): View {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xB308090C.toInt())
            setPadding(0, 14.dp, 0, 14.dp)
        }
        rail.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"; setTextColor(C.TEXT); textSize = 19f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        statusDot = View(this).apply { background = rounded(C.WARN, 6) }
        rail.addView(statusDot, LinearLayout.LayoutParams(12.dp, 12.dp).apply { topMargin = 8.dp })
        statusText = label("", 10f, C.SUB).apply { gravity = Gravity.CENTER }
        rail.addView(statusText)

        rail.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(railItem(R.drawable.ic_home, "Home") { showHome() })
        rail.addView(railItem(R.drawable.ic_map, "Map") { setLayout(listOf(PaneType.MAP)) })
        rail.addView(railItem(R.drawable.ic_music, "Music") { setLayout(listOf(PaneType.MUSIC)) })
        rail.addView(railItem(R.drawable.ic_grid, "Apps") { drawer.open() })
        rail.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))

        rail.addView(label("SPLIT", 10f, C.SUB, true).apply { gravity = Gravity.CENTER })
        for (n in 1..3) {
            val b = label("$n", 15f, C.TEXT, true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { setCount(n) }
            }
            countButtons += b
            rail.addView(b, LinearLayout.LayoutParams(40.dp, 36.dp).apply { topMargin = 6.dp })
        }
        rail.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(railItem(R.drawable.ic_tune, "More") { showSettings() })
        // Kan rulles hvis skjermen er for lav til alt (f.eks. 720 px høyde)
        return ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = rail.background
            rail.background = null
            addView(rail, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun railItem(icon: Int, text: String, click: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, 8.dp, 0, 8.dp)
        isClickable = true
        setOnClickListener { click() }
        addView(ImageView(context).apply { setImageResource(icon); setColorFilter(C.TEXT) }, LinearLayout.LayoutParams(30.dp, 30.dp))
        addView(context.label(text, 11f, C.SUB).apply { gravity = Gravity.CENTER })
    }

    // ------------------------------------------------------------- paneler

    private fun defaultWeights(n: Int) = when (n) {
        1 -> floatArrayOf(1f)
        2 -> floatArrayOf(0.6f, 0.4f)
        else -> floatArrayOf(0.45f, 0.3f, 0.25f)
    }

    private fun loadState() {
        val p = prefs()
        val list = (p.getString("panes", null) ?: "MAP,MUSIC,APPS").split(",")
            .mapNotNull { runCatching { PaneType.valueOf(it) }.getOrNull() }.take(3)
        panes.clear(); panes.addAll(list.ifEmpty { listOf(PaneType.MAP) })
        val w = p.getString("weights", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()
        // Panelene (med kart) lages først når delt visning faktisk vises → lettere oppstart
        lastWeights = if (w != null && w.size == panes.size) w else null
    }

    private fun saveState() {
        prefs().edit()
            .putString("panes", panes.joinToString(",") { it.name })
            .putString("weights", (if (split.childCount > 0) split.weights else lastWeights ?: split.weights).joinToString(","))
            .apply()
    }

    private fun rebuild(weights: FloatArray? = null) {
        split.removeAllViews()
        panes.forEach { t ->
            split.addView(PaneView(this, t) { pane, newType ->
                val idx = split.indexOfChild(pane)
                if (idx >= 0) { panes[idx] = newType; pane.show(newType); saveState() }
            })
        }
        split.setWeights(weights ?: defaultWeights(panes.size))
        saveState()
        applyChromeTheme(Ambient.theme.value)
    }

    private fun setLayout(list: List<PaneType>) {
        panes.clear(); panes.addAll(list); rebuild(); showSplit()
    }

    /** Meny og paneler i delt visning får coverets farger, og coveret synes gjennom. */
    private val pointerColor = Runnable { pointerRoot.refresh(Ambient.theme.value.accent) }

    private fun applyChromeTheme(t: Ambient.Theme) {
        rail.setBackgroundColor(Ambient.alpha(t.dark, 0xCC))
        // Prikken får coverfargen når fargeovergangen er ferdig (ikke for hvert animasjonsbilde)
        pointerRoot.removeCallbacks(pointerColor); pointerRoot.postDelayed(pointerColor, 400)
        for (i in 0 until split.childCount) {
            val pane = split.getChildAt(i)
            pane.background = rounded(Ambient.alpha(t.dark, 0xBF), 22, Ambient.alpha(t.accent, 0x30))
            (pane as? PaneView)?.accent = t.accent
        }
        countButtons.forEachIndexed { i, b ->
            b.background = rounded(if (i + 1 == panes.size) t.accent else Ambient.alpha(0xFFFFFFFF.toInt(), 0x1F), 10)
        }
        statusText.setTextColor(t.accent)
    }

    private var lastWeights: FloatArray? = null

    private fun showHome() {
        home.visibility = View.VISIBLE
        // Panelene (kart m.m.) fjernes helt mens hjemmeskjermen vises → ingen kart/GPS i bakgrunnen
        if (split.childCount > 0) { lastWeights = split.weights.copyOf(); split.removeAllViews() }
        split.visibility = View.GONE
        rail.visibility = View.GONE
        prefs().edit().putString("mode", "home").apply()
    }

    private fun showSplit() {
        if (split.childCount == 0) rebuild(lastWeights?.takeIf { it.size == panes.size })
        home.visibility = View.GONE
        split.visibility = View.VISIBLE
        rail.visibility = View.VISIBLE
        prefs().edit().putString("mode", "split").apply()
    }

    /** Hjem-knappen på enheten tar deg alltid tilbake til hjemmeskjermen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) { drawer.close(); settings.close(); showHome() }
        handleIntent(intent)
    }

    /**
     * Styring utenfra (brukes av simuleringen): --ez demo true, --es show home|nav|split|map|music|apps
     */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("demo", false)) DemoFeeder.start(this)
        if (i.getBooleanExtra("carlink_update", false) && ::settings.isInitialized) {
            settings.showPage("UPDATES"); settings.open(); lifecycleScope.launch { Updater.install(this@ReceiverActivity) }
        }
        i.getStringExtra("mapmode")?.let { m -> runCatching { DayNight.setMode(this, DayNight.Mode.valueOf(m)) } }
        val show = i.getStringExtra("show") ?: return
        DemoFeeder.showNav = show == "nav"
        if (show != "drawer" && ::drawer.isInitialized) drawer.close(immediate = true)
        if (show != "menu" && ::settings.isInitialized) settings.close(immediate = true)
        when (show) {
            "home", "nav" -> showHome()
            "split" -> setLayout(listOf(PaneType.MAP, PaneType.MUSIC, PaneType.APPS))
            "split2" -> setLayout(listOf(PaneType.MUSIC, PaneType.APPS))   // (måling uten kart)
            "map" -> setLayout(listOf(PaneType.MAP))
            "music" -> setLayout(listOf(PaneType.MUSIC))
            "apps" -> setLayout(listOf(PaneType.APPS))
            "drawer" -> { showHome(); drawer.open() }
            "menu" -> { showHome(); i.getStringExtra("menupage")?.let { settings.showPage(it) }; settings.open() }
            "joystick" -> startActivity(Intent(this, JoystickActivity::class.java))
        }
    }

    private fun setCount(n: Int) {
        while (panes.size < n) panes += PaneType.entries.firstOrNull { it !in panes } ?: PaneType.APPS
        while (panes.size > n) panes.removeAt(panes.lastIndex)
        rebuild(); showSplit()
    }

    // ------------------------------------------------------------- innstillinger

    private fun showSettings() = settings.open()

    private val settingsHost = object : SettingsPanel.Host {
        override fun restartLink() { LinkService.startReceiver(this@ReceiverActivity, force = true) }
        override fun systemBarsChanged() = applySystemBars()
        override fun joyMouseChanged(on: Boolean) {
            prefs().edit().putBoolean("joyMouse", on).apply()
            if (!on && !JoyMouseService.homeOnly(this@ReceiverActivity)) return
            if (JoyMouseService.running()) return
            lifecycleScope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Root.enableAccessibility(JoyMouseService.COMPONENT)
                }
                if (!ok) {
                    android.widget.Toast.makeText(this@ReceiverActivity,
                        "Couldn't switch it on automatically – turn on «CarLink» under Accessibility", android.widget.Toast.LENGTH_LONG).show()
                    runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
            }
        }
        override fun systemAnimationsChanged(level: Int) {
            val scale = when (level) { 1 -> "0.5"; 2 -> "0"; else -> "1" }
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ok = Root.run("""
                    settings put global window_animation_scale $scale
                    settings put global transition_animation_scale $scale
                    settings put global animator_duration_scale $scale
                """.trimIndent()) != null
                if (!ok) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(this@ReceiverActivity, "Needs root – set it under Developer options instead", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        override fun closeSettings() { if (::settings.isInitialized) settings.close() }
        override fun systemReport() {
            android.widget.Toast.makeText(this@ReceiverActivity, "Making system report …", android.widget.Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val msg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SystemReport.runAndSend(this@ReceiverActivity) }
                android.widget.Toast.makeText(this@ReceiverActivity, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        override fun makeDefaultHome() = requestDefaultHome()
        override fun openJoystickTraining() { settings.close(immediate = true); startActivity(Intent(this@ReceiverActivity, JoystickActivity::class.java)) }
        override fun mockGpsChanged(on: Boolean) { if (on) explainMockGps() }
        override fun uiScaleChanged() = recreate()
        override fun demoChanged(on: Boolean) {
            if (on) { LinkService.stop(this@ReceiverActivity); prefs().edit().putBoolean("demo", true).apply(); DemoFeeder.start(this@ReceiverActivity) }
            else { DemoFeeder.stop(); prefs().edit().putBoolean("demo", false).apply(); LinkService.startReceiver(this@ReceiverActivity, force = true) }
        }
        override fun pointerChanged() = pointerRoot.refresh()
        override fun openNotificationAccess() = SetupGuide.openNotificationAccess(this@ReceiverActivity)
        override fun openAndroidSettings() { Launch.start(this@ReceiverActivity, Intent(Settings.ACTION_SETTINGS)) }
        override fun openBluetoothSettings() { Launch.start(this@ReceiverActivity, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        override fun openAppPermissions() {
            Launch.start(this@ReceiverActivity, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        }
        override fun openDeveloperOptions() { Launch.start(this@ReceiverActivity, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        override fun isDefaultHome() = this@ReceiverActivity.isDefaultHome()
        override fun checkUpdates() { lifecycleScope.launch { Updater.check(this@ReceiverActivity) } }
        override fun installUpdate() { lifecycleScope.launch { Updater.install(this@ReceiverActivity) } }
        override fun makeDiscoverable() {
            Launch.start(this@ReceiverActivity, Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
        }
    }

    /** Forklarer engangsoppsettet for «telefonens GPS i alle apper». */
    private fun explainMockGps() {
        run {
            AlertDialog.Builder(this)
                .setTitle("Phone GPS in all apps")
                .setMessage(
                    "Once: Settings → About device → tap \"Build number\" 7 times to enable developer options. " +
                        "Then go to Developer options → \"Select mock location app\" → CarLink.\n\n" +
                        "Google Maps and other apps on the screen then use the phone's location."
                )
                .setPositiveButton("Open developer options") { _, _ ->
                    Launch.start(this, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }
                .setNegativeButton("OK", null)
                .show()
        }
    }
}
