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
        getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
    }.getOrDefault(true)

    override fun onResume() {
        super.onResume()
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
                        statusText.text = if (c) "Tilkoblet" else if (s.startsWith("Venter")) "Venter" else "Frakoblet"
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
            guide.add(SetupGuide.Step("home", "CarLink som hjemmeskjerm", null,
                done = { isDefaultHome() },
                action = {
                    val rm = getSystemService(android.app.role.RoleManager::class.java)
                    homeRole.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME))
                }))
            guide.add(SetupGuide.Step("notif", "Vis musikk som spilles på skjermen",
                "For å vise cover og styre musikk som spilles på skjermen selv (f.eks. Tidal i bilen), trenger CarLink varseltilgang.\n\n" +
                    "Slå på «CarLink» på neste side.",
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
        rail.addView(railItem(R.drawable.ic_home, "Hjem") { showHome() })
        rail.addView(railItem(R.drawable.ic_map, "Kart") { setLayout(listOf(PaneType.MAP)) })
        rail.addView(railItem(R.drawable.ic_music, "Musikk") { setLayout(listOf(PaneType.MUSIC)) })
        rail.addView(railItem(R.drawable.ic_grid, "Apper") { drawer.open() })
        rail.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))

        rail.addView(label("DELT", 10f, C.SUB, true).apply { gravity = Gravity.CENTER })
        for (n in 1..3) {
            val b = label("$n", 15f, C.TEXT, true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { setCount(n) }
            }
            countButtons += b
            rail.addView(b, LinearLayout.LayoutParams(40.dp, 36.dp).apply { topMargin = 6.dp })
        }
        rail.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(railItem(R.drawable.ic_tune, "Mer") { showSettings() })
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
        override fun makeDefaultHome() {
            runCatching {
                val rm = getSystemService(android.app.role.RoleManager::class.java)
                homeRole.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME))
            }.onFailure { Launch.start(this@ReceiverActivity, Intent(Settings.ACTION_HOME_SETTINGS)) }
        }
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
        override fun makeDiscoverable() {
            Launch.start(this@ReceiverActivity, Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
        }
    }

    /** Forklarer engangsoppsettet for «telefonens GPS i alle apper». */
    private fun explainMockGps() {
        run {
            AlertDialog.Builder(this)
                .setTitle("Telefonens GPS i alle apper")
                .setMessage(
                    "Én gang: Innstillinger → Om enheten → trykk 7 ganger på «Delversjonsnummer» for å slå på utvikleralternativer. " +
                        "Gå så til Utvikleralternativer → «Velg app for simulert posisjon» → CarLink.\n\n" +
                        "Da bruker Google Maps og andre apper på skjermen telefonens posisjon."
                )
                .setPositiveButton("Åpne utvikleralternativer") { _, _ ->
                    Launch.start(this, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }
                .setNegativeButton("OK", null)
                .show()
        }
    }
}
