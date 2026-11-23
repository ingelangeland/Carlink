package no.carlink

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import no.carlink.launcher.R
import kotlin.math.min

/**
 * Innstillinger i samme stil som app-oversikten: uskarp, røykfarget bakgrunn, farger fra coveret,
 * gjennomsiktige flater. Grupper til venstre, valg (brytere og valgknapper) til høyre.
 * Store trykkflater, så det er lett å bruke med joystick på avstand.
 */
@SuppressLint("ViewConstructor")
class SettingsPanel(ctx: Context, blurTarget: View, private val host: Host) : GlassOverlay(ctx, blurTarget) {

    /** Det menyen trenger fra hjemmeskjermen. */
    interface Host {
        fun restartLink()
        fun makeDefaultHome()
        fun openJoystickTraining()
        fun mockGpsChanged(on: Boolean)
        fun uiScaleChanged()
        fun demoChanged(on: Boolean)
        fun pointerChanged()
        fun openNotificationAccess()
        fun openAndroidSettings()
        fun openBluetoothSettings()
        fun makeDiscoverable()
        fun openAppPermissions()
        fun openDeveloperOptions()
        fun isDefaultHome(): Boolean
        fun checkUpdates()
        fun installUpdate()
    }

    private enum class Group(val label: String, val icon: Int) {
        MUSIC("Music", R.drawable.ic_music), MAP("Map", R.drawable.ic_map),
        JOYSTICK("Joystick", R.drawable.ic_joystick), SCREEN("Screen", R.drawable.ic_screen),
        LINK("Connection", R.drawable.ic_wifi), ACCESS("Permissions", R.drawable.ic_lock), UPDATES("Updates", R.drawable.ic_update), ANDROID("Android settings", R.drawable.ic_settings),
    }

    private val box = LinearLayout(ctx)
    override val body: View get() = box
    private val title = ctx.label("Settings", 24f, C.TEXT, true)
    private val status = ctx.label("", 13f, 0xB3FFFFFF.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val groups = LinearLayout(ctx)
    private val page = LinearLayout(ctx)
    private val pageCard = FrameLayout(ctx)
    private var current = Group.MUSIC
    private var theme = Ambient.theme.value

    init {
        box.orientation = LinearLayout.VERTICAL
        box.isClickable = true   // trykk i menyen skal ikke lukke den

        // Overskrift over alt annet, med status til høyre
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(title)
        head.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 18.dp; bottomMargin = 3.dp })
        box.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 14.dp })

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        // Gruppene deler høyden likt, så alle (også den nederste) alltid får plass uten å kuttes
        groups.orientation = LinearLayout.VERTICAL
        row.addView(groups, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.3f))
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(20.dp, 14.dp, 20.dp, 14.dp)
        pageCard.addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(32.dp)
            addView(page)
        })
        row.addView(pageCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = 14.dp })
        box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        addView(box, LayoutParams(0, 0, Gravity.CENTER))
    }

    override fun onStart(scope: CoroutineScope) {
        super.onStart(scope)
        scope.launch { Hub.status.collect { status.text = it } }
        // The updates page follows the updater live (checking, downloading, installed …)
        scope.launch { kotlinx.coroutines.flow.combine(Updater.status, Updater.available) { a, b -> a to b }
            .collect { if (isOpen && current == Group.UPDATES) build() } }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        (box.layoutParams as LayoutParams).apply { width = min((w * 0.8f).toInt(), 1400.dp); height = (h * 0.86f).toInt() }
        box.requestLayout()
    }

    override fun onOpening() = build()

    /** Åpne direkte på en gruppe (brukes av simuleringen). */
    fun showPage(name: String) {
        runCatching { Group.valueOf(name) }.getOrNull()?.let { current = it; if (isOpen) build() }
    }

    override fun onTheme(t: Ambient.Theme) {
        theme = t
        if (isOpen) build()
    }

    // ---------------------------------------------------------------- oppbygging

    private fun build() {
        val t = theme
        pageCard.background = rounded(0x14FFFFFF, 18, 0x29FFFFFF)
        groups.removeAllViews()
        Group.entries.forEachIndexed { i, g -> groups.addView(groupPill(g, g == current, t),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { if (i > 0) topMargin = 6.dp }) }
        page.removeAllViews()
        page.addView(context.label(current.label, 18f, C.TEXT, true).apply { setPadding(0, 0, 0, 6.dp) })
        when (current) {
            Group.MUSIC -> music()
            Group.MAP -> map()
            Group.JOYSTICK -> joystick()
            Group.SCREEN -> screen()
            Group.LINK -> link()
            Group.ACCESS -> access()
            Group.UPDATES -> updates()
            Group.ANDROID -> Unit
        }
    }

    private fun music() {
        toggle("Play and pause on connect and disconnect", "Music resumes where it left off when the phone connects",
            AutoPlay.enabled(context)) { context.prefs().edit().putBoolean("autoPlay", it).apply() }
        val buf = intArrayOf(300, 600, 1000, 1500, 2500)
        choice("Audio buffer (audio over Wi-Fi)", "A larger buffer gives fewer glitches but slightly slower response",
            listOf("0,3 s", "0,6 s", "1 s", "1,5 s", "2,5 s"), buf.indexOf(context.prefs().getInt("audioBufferMs", 600)).coerceAtLeast(0)) {
            context.prefs().edit().putInt("audioBufferMs", buf[it]).apply()
        }
        choice("Sound wave", null, listOf("Wave", "Capsules"),
            if (context.prefs().getString("vizStyle", "WAVE") == "CAPSULES") 1 else 0) {
            context.prefs().edit().putString("vizStyle", if (it == 1) "CAPSULES" else "WAVE").apply()
            VisualizerView.styleChanged(context)
        }
    }

    private fun map() {
        choice("Day and night", "Auto follows the car's lights if available, otherwise the sun",
            DayNight.Mode.entries.map { it.label }, DayNight.mode(context).ordinal) { DayNight.setMode(context, DayNight.Mode.entries[it]) }
        toggle("Phone GPS in all apps", "Google Maps on the screen also uses the phone's location",
            context.prefs().getBoolean("mockGps", false)) { context.prefs().edit().putBoolean("mockGps", it).apply(); host.mockGpsChanged(it) }
    }

    private fun joystick() {
        choice("Dot as mouse pointer", "Highly visible dot in the cover color",
            PointerRoot.Size.entries.map { it.label }, PointerRoot.size(context).ordinal) {
            PointerRoot.setSize(context, PointerRoot.Size.entries[it]); host.pointerChanged()
        }
        action("Train the joystick", "Records the movements so control can be tuned to your car") { host.openJoystickTraining() }
    }

    private fun screen() {
        choice("Size of text and buttons", null, UiScale.labels.toList(),
            UiScale.factors.indexOfFirst { it == UiScale.factor(context) }.coerceAtLeast(1)) {
            context.prefs().edit().putFloat("uiScale", UiScale.factors[it]).apply(); host.uiScaleChanged()
        }
    }

    /** Alle tillatelser samlet, med status: hva som er gitt, og ett trykk til riktig sted for det som mangler. */
    private fun access() {
        fun st(ok: Boolean, missing: String) = if (ok) "✓ Granted" else missing
        action("Notification access (music and navigation)",
            st(MediaListener.isEnabled(context), "Missing – needed for music and Google Maps navigation on the screen")) { host.openNotificationAccess() }
        action("CarLink as home screen", st(host.isDefaultHome(), "Not set – tap to choose")) { host.makeDefaultHome() }
        action("Location and Bluetooth",
            st(Perms.missing(context, Perms.forRole(false)).isEmpty(), "Missing – needed for connection and map")) { host.openAppPermissions() }
        val mock = runCatching {
            val ao = context.getSystemService(android.app.AppOpsManager::class.java)
            ao.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_MOCK_LOCATION, android.os.Process.myUid(), context.packageName) ==
                android.app.AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        action("Mock location (phone GPS in all apps)",
            st(mock, "Not set – Developer options → \"Select mock location app\" → CarLink")) { host.openDeveloperOptions() }
    }

    private fun updates() {
        val (code, name) = Updater.currentVersion(context)
        action("Installed version", "$name (build $code)" + (Updater.status.value.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")) { host.checkUpdates() }
        Updater.available.value?.let { u ->
            action("Update to version ${u.versionName}", "Downloads and installs the new version") { host.installUpdate() }
        }
        action("Check for updates", null) { host.checkUpdates() }
        toggle("Check automatically", "Looks for a new version when CarLink starts and every 6 hours",
            Updater.autoCheck(context)) { context.prefs().edit().putBoolean("autoUpdate", it).apply() }
    }

    /** Oppdater visningen (f.eks. når du kommer tilbake fra Android-innstillingene). */
    fun refresh() { if (isOpen) build() }

    private fun link() {
        action("Restart the connection", null) { host.restartLink() }
        action("Make the screen visible for Bluetooth", "For 5 minutes, to pair a new phone") { host.makeDiscoverable() }
        action("Bluetooth settings", null) { host.openBluetoothSettings() }
        toggle("Demo mode", "Simulated phone, to try the screen without a phone", DemoFeeder.active) { host.demoChanged(it) }
    }

    // ---------------------------------------------------------------- byggeklosser

    private fun groupPill(g: Group, on: Boolean, t: Ambient.Theme): View {
        val fg = if (on && ColorUtils.calculateLuminance(t.accent) > 0.5) 0xFF101418.toInt() else C.TEXT
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 0, 14.dp, 0)
            background = rounded(if (on) t.accent else 0x14FFFFFF, 14)
            addView(ImageView(context).apply { setImageResource(g.icon); setColorFilter(fg) }, LinearLayout.LayoutParams(22.dp, 22.dp))
            addView(context.label(g.label, 15f, fg, on).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 12.dp })
            setOnClickListener {
                if (g == Group.ANDROID) { host.openAndroidSettings(); return@setOnClickListener }
                current = g; build()
            }
        }
    }

    private fun rowBox(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 12.dp, 0, 12.dp)
        page.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun texts(title: String, sub: String?) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(context.label(title, 16f, C.TEXT))
        if (sub != null) addView(context.label(sub, 12.5f, 0xA6FFFFFF.toInt()).apply { setPadding(0, 3.dp, 0, 0) })
    }

    private fun toggle(title: String, sub: String?, value: Boolean, set: (Boolean) -> Unit) {
        val r = rowBox()
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(texts(title, sub), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(switchView(value), LinearLayout.LayoutParams(52.dp, 30.dp).apply { leftMargin = 16.dp })
        line.setOnClickListener { set(!value); build() }
        r.addView(line)
    }

    private fun switchView(on: Boolean): View = FrameLayout(context).apply {
        background = rounded(if (on) theme.accent else 0x33FFFFFF, 15)
        addView(View(context).apply { background = rounded(0xFFFFFFFF.toInt(), 12) },
            LayoutParams(24.dp, 24.dp, Gravity.CENTER_VERTICAL or if (on) Gravity.END else Gravity.START).apply { setMargins(3.dp, 0, 3.dp, 0) })
    }

    private fun choice(title: String, sub: String?, options: List<String>, selected: Int, pick: (Int) -> Unit) {
        val r = rowBox()
        r.addView(texts(title, sub))
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 10.dp, 0, 0) }
        val darkText = ColorUtils.calculateLuminance(theme.accent) > 0.5
        options.forEachIndexed { i, o ->
            val on = i == selected
            chips.addView(context.label(o, 14f, if (on && darkText) 0xFF101418.toInt() else C.TEXT, on).apply {
                gravity = Gravity.CENTER
                setPadding(18.dp, 9.dp, 18.dp, 9.dp)
                background = rounded(if (on) theme.accent else 0x1FFFFFFF, 18)
                setOnClickListener { if (!on) { pick(i); build() } }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8.dp })
        }
        r.addView(chips)
    }

    private fun action(title: String, sub: String?, run: () -> Unit) {
        val r = rowBox()
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(texts(title, sub), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(TextView(context).apply { text = "›"; textSize = 26f; setTextColor(0xB3FFFFFF.toInt()) })
        line.setOnClickListener { run() }
        r.addView(line)
    }
}
