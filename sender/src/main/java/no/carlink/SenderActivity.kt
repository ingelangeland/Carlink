package no.carlink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.Manifest
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.view.ViewOutlineProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Telefonsiden: velg skjerm, koble til, se hva som deles. */
class SenderActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var notifBtn: TextView
    private lateinit var batteryBtn: TextView
    private lateinit var spinner: Spinner
    private lateinit var hotspot: CheckBox
    private lateinit var cover: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var playBtn: ImageButton
    private var devices: List<Pair<String, String>> = emptyList() // navn, adresse
    private lateinit var audioBtn: TextView
    private lateinit var muteBox: CheckBox
    private lateinit var autoBox: CheckBox
    private lateinit var triggerList: LinearLayout
    private lateinit var bgLocBtn: TextView
    private lateinit var joyBtn: TextView
    private lateinit var updateBtn: TextView
    private lateinit var versionTv: TextView
    private val bgLocLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateBgLoc(); guide.next() }

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) LinkService.startAudio(this, r.resultCode, d, muteBox.isChecked)
        else toast("Audio streaming cancelled")
    }
    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) askProjection() else toast("CarLink needs audio capture permission to stream the music")
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadDevices(); guide.next() }
    private val guide = SetupGuide(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        Updater.apkName = "CarLink-Sender-telefon.apk"
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp, 28.dp, 20.dp, 40.dp) }
        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(col) })

        col.addView(label("CarLink · Sender", 26f, C.TEXT, true))
        statusTv = label("", 14f, C.SUB).apply { setPadding(0, 6.dp, 0, 0) }
        col.addView(statusTv)

        // Built-in updates: shown only when a new version exists
        updateBtn = pill("Update CarLink Sender", C.ACCENT) { lifecycleScope.launch { Updater.install(this@SenderActivity) } }
        updateBtn.visibility = android.view.View.GONE
        col.addView(updateBtn, wrap(10))

        joyBtn = pill("Share the latest report from the car", C.ACCENT) { runCatching { startActivity(JoyLog.shareIntent(this)) } }
        col.addView(joyBtn, wrap(10))
        col.addView(pill("Share the phone's CarLink log", C.ACCENT) {
            runCatching { AppLog.saveNow(); JoyLog.received(this, "CarLink-phone-log.txt", AppLog.dump()); startActivity(JoyLog.shareIntent(this)) }
        }, wrap(10))

        col.section("1 · Access to music and navigation")
        notifBtn = pill("Allow access to music and navigation") { SetupGuide.openNotificationAccess(this) }
        col.addView(notifBtn, wrap())
        batteryBtn = pill("Allow CarLink to run in the background") { askBattery() }
        col.addView(batteryBtn, wrap(8))
        col.addView(label("Samsung: also choose \"Unrestricted\" under Settings → Apps → CarLink Sender → Battery.", 12f, C.SUB).apply { setPadding(0, 6.dp, 0, 0) })

        col.section("2 · Automatic start and stop")
        autoBox = CheckBox(this).apply {
            text = "Start CarLink automatically when the phone connects to one of the devices below"
            setTextColor(C.TEXT); textSize = 14f
            buttonTintList = ColorStateList.valueOf(C.ACCENT)
            isChecked = prefs().getBoolean("auto", false)
            setOnCheckedChangeListener { _, c ->
                prefs().edit().putBoolean("auto", c).apply()
                updateBgLoc()
                if (c) { BtTrigger.checkNow(this@SenderActivity); guide.next() }
            }
        }
        col.addView(autoBox, wrap())
        triggerList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(C.PANEL, 14); setPadding(10.dp, 6.dp, 10.dp, 6.dp)
        }
        col.addView(triggerList, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp })
        col.addView(label(
            "Choose e.g. the car's own Bluetooth. When the phone connects, CarLink starts, and the screen finds the phone by itself " +
                "when it's on the phone's hotspot. CarLink stops 2 minutes after Bluetooth is gone.",
            12f, C.SUB).apply { setPadding(0, 6.dp, 0, 0) })
        bgLocBtn = pill("Allow location \"all the time\" (map when CarLink starts by itself)") { askBgLoc() }
        col.addView(bgLocBtn, wrap(8))
        col.addView(CheckBox(this).apply {
            text = "Ask about audio over Wi-Fi when CarLink starts by itself (notification)"
            setTextColor(C.TEXT); textSize = 13f
            buttonTintList = ColorStateList.valueOf(C.ACCENT)
            isChecked = prefs().getBoolean("audioReminder", false)
            setOnCheckedChangeListener { _, c -> prefs().edit().putBoolean("audioReminder", c).apply() }
        }, wrap(6))

        col.section("3 · The screen (optional Bluetooth to the screen)")
        spinner = Spinner(this).apply { background = rounded(C.PANEL, 14); setPadding(8.dp, 8.dp, 8.dp, 8.dp) }
        col.addView(spinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(label("Can't find the screen? Pair the devices in Bluetooth settings first.", 12f, C.SUB).apply { setPadding(0, 6.dp, 0, 0) })
        col.addView(pill("Bluetooth settings") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, wrap(8))

        hotspot = CheckBox(this).apply {
            text = "Create direct Wi-Fi from the phone\n(use if you're not on the same Wi-Fi/hotspot – the screen then gets no internet for map tiles)"
            setTextColor(C.TEXT); textSize = 13f
            buttonTintList = ColorStateList.valueOf(C.ACCENT)
            isChecked = prefs().getBoolean("hotspot", false)
        }
        col.addView(hotspot, wrap(12))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pill("Connect", C.ACCENT) { connect() })
        row.addView(pill("Disconnect") { LinkService.stop(this) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = 10.dp })
        col.addView(row, wrap(12))

        col.section("Audio over Wi-Fi (optional)")
        col.addView(label("Music plays on the screen instead of the phone. Android asks for audio capture permission each time.", 12f, C.SUB))
        muteBox = CheckBox(this).apply {
            text = "Mute the phone while audio goes to the screen"
            setTextColor(C.TEXT); textSize = 13f
            buttonTintList = ColorStateList.valueOf(C.ACCENT)
            isChecked = prefs().getBoolean("mute", true)
            setOnCheckedChangeListener { _, c -> prefs().edit().putBoolean("mute", c).apply() }
        }
        col.addView(muteBox, wrap(6))
        audioBtn = pill("Stream audio to the screen", C.ACCENT) { toggleAudio() }
        col.addView(audioBtn, wrap(6))

        col.section("Now playing (shared with the screen)")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.PANEL, 18); setPadding(12.dp, 12.dp, 12.dp, 12.dp)
        }
        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(C.PANEL2, 12)
            outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true
        }
        card.addView(cover, LinearLayout.LayoutParams(84.dp, 84.dp))
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14.dp, 0, 8.dp, 0) }
        title = label("", 16f, C.TEXT, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        artist = label("", 13f, C.SUB).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        txt.addView(title); txt.addView(artist)
        val ctr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8.dp, 0, 0) }
        ctr.addView(ctrl(android.R.drawable.ic_media_previous, "prev"))
        playBtn = ctrl(android.R.drawable.ic_media_play, "toggle"); ctr.addView(playBtn)
        ctr.addView(ctrl(android.R.drawable.ic_media_next, "next"))
        txt.addView(ctr)
        card.addView(txt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        col.section("Send route to the screen")
        val dest = EditText(this).apply {
            hint = "Address or place"; setHintTextColor(C.SUB); setTextColor(C.TEXT)
            background = rounded(C.PANEL, 14); setPadding(14.dp, 12.dp, 14.dp, 12.dp); isSingleLine = true
        }
        col.addView(dest, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(pill("Start navigation on the screen", C.ACCENT) {
            val q = dest.text.toString().trim()
            if (q.isEmpty()) return@pill
            if (Hub.send(JSONObject().put("t", "nav").put("q", q))) toast("Sent to the screen") else toast("Not connected")
        }, wrap(8))

        col.section("Routes from Google Maps")
        col.addView(label(
            "• Start navigation in Google Maps on the phone – next turn, distance and arrival time appear on the car screen.\n" +
                "• Or tap \"Share\" on a place or route in Google Maps and choose \"Send to car\".",
            13f, C.SUB))
        versionTv = label("", 12f, C.SUB).apply { setPadding(0, 30.dp, 0, 0) }
        col.addView(versionTv)
        col.addView(pill("Check for updates") { lifecycleScope.launch { Updater.check(this@SenderActivity) } }, wrap(8))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    kotlinx.coroutines.flow.combine(Hub.status, ConnTimeline.last) { s, t -> if (t != null) "$s · last connection took $t" else s }
                        .collect { statusTv.text = it }
                }
                launch { Hub.connected.collect { statusTv.setTextColor(if (it) C.OK else C.SUB) } }
                launch { Hub.localMedia.collect { renderMedia(it) } }
                launch {
                    kotlinx.coroutines.flow.combine(Updater.available, Updater.status) { a, s -> a to s }.collect { (a, s) ->
                        updateBtn.visibility = if (a != null) android.view.View.VISIBLE else android.view.View.GONE
                        if (a != null) updateBtn.text = "Update to version ${a.versionName}"
                        val (code, name) = Updater.currentVersion(this@SenderActivity)
                        versionTv.text = "CarLink Sender $name (build $code)" + if (s.isNotBlank()) " · $s" else ""
                    }
                }
                launch {
                    Hub.audioStreaming.collect { on ->
                        audioBtn.text = if (on) "■ Stop audio streaming" else "Stream audio to the screen"
                        audioBtn.background = rounded(if (on) C.WARN else C.ACCENT, 22)
                    }
                }
            }
        }

        // Tillatelser spørres én om gangen (Androids egne spørsmål, eller forklaring + «Gå til innstillinger» / «Avbryt»)
        guide.add(SetupGuide.Step("perms", "Permissions", null,
            done = { Perms.missing(this, Perms.forRole(true)).isEmpty() },
            action = { permLauncher.launch(Perms.missing(this, Perms.forRole(true)).toTypedArray()) }))
        guide.add(SetupGuide.Step("notif", "Show music and navigation in the car",
            "CarLink needs notification access to see which music is playing and the next turn from Google Maps.\n\n" +
                "Turn on \"CarLink Sender\" on the next page.",
            done = { MediaListener.isEnabled(this) },
            action = { SetupGuide.openNotificationAccess(this) }))
        guide.add(SetupGuide.Step("battery", "Run in the background", null,
            done = { batteryOk() }, action = { askBattery() }))
        guide.add(SetupGuide.Step("bgloc", "Location when CarLink starts by itself",
            "For the map in the car to get GPS when CarLink starts automatically, location must be allowed all the time.\n\n" +
                "Choose \"Allow all the time\" on the next page.",
            done = { !autoBox.isChecked || bgLocOk() },
            action = { askBgLoc() }))
        if (intent?.getBooleanExtra("carlink_audio", false) == true) toggleAudio()
        if (intent?.getBooleanExtra("carlink_update", false) == true) lifecycleScope.launch { Updater.install(this@SenderActivity) }
        if (intent?.getBooleanExtra("autoconnect", false) == true) connect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("carlink_audio", false)) toggleAudio()
        if (intent.getBooleanExtra("carlink_update", false)) lifecycleScope.launch { Updater.install(this@SenderActivity) }
    }

    private fun bgLocOk() = Perms.has(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun updateBgLoc() {
        val ok = bgLocOk()
        bgLocBtn.text = if (ok) "✓ Location allowed all the time" else "Allow location \"all the time\" (map when CarLink starts by itself)"
        bgLocBtn.background = rounded(if (ok) C.PANEL2 else if (autoBox.isChecked) C.WARN else C.PANEL2, 22)
    }

    private fun askBgLoc() {
        if (bgLocOk()) return
        if (!Perms.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); return
        }
        // Android sender brukeren til innstillingene: velg «Tillat hele tiden»
        bgLocLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    override fun onResume() {
        super.onResume()
        val ok = MediaListener.isEnabled(this)
        notifBtn.text = if (ok) "✓ Access to music and navigation granted" else "Allow access to music and navigation"
        notifBtn.background = rounded(if (ok) C.PANEL2 else C.WARN, 22)
        val bat = batteryOk()
        batteryBtn.text = if (bat) "✓ Can run in the background" else "Allow CarLink to run in the background"
        batteryBtn.background = rounded(if (bat) C.PANEL2 else C.WARN, 22)
        updateBgLoc()
        joyBtn.visibility = if (JoyLog.exists(this)) android.view.View.VISIBLE else android.view.View.GONE
        loadDevices()
        BtTrigger.checkNow(this)
        guide.next()
        if (Updater.autoCheck(this)) lifecycleScope.launch { Updater.check(this@SenderActivity, 3 * 3_600_000L, notify = true) }
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        devices = try {
            getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.map { (it.name ?: it.address) to it.address }?.sortedBy { it.first } ?: emptyList()
        } catch (_: SecurityException) { emptyList() }
        // Første valg: ingen Bluetooth til skjermen – den finner telefonen via hotspot/Wi-Fi
        val names = listOf("None – the screen finds the phone via hotspot") + devices.map { it.first }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val last = prefs().getString("btAddr", null)
        spinner.setSelection(devices.indexOfFirst { it.second == last } + 1)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs().edit().putString("btAddr", devices.getOrNull(pos - 1)?.second ?: "").apply()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        // Utløsere: flere enheter kan velges
        triggerList.removeAllViews()
        val chosen = BtTrigger.triggers(this).toMutableSet()
        if (devices.isEmpty()) triggerList.addView(label("No paired Bluetooth devices yet", 13f, C.SUB).apply { setPadding(4.dp, 8.dp, 0, 8.dp) })
        for ((name, addr) in devices) {
            triggerList.addView(CheckBox(this).apply {
                text = name; setTextColor(C.TEXT); textSize = 14f
                buttonTintList = ColorStateList.valueOf(C.ACCENT)
                isChecked = addr in chosen
                setOnCheckedChangeListener { _, c ->
                    if (c) chosen += addr else chosen -= addr
                    prefs().edit().putStringSet("triggers", HashSet(chosen)).apply()
                }
            })
        }
    }

    private fun connect() {
        val i = spinner.selectedItemPosition - 1   // 0 = ingen Bluetooth til skjermen
        val miss = Perms.missing(this, Perms.forRole(true))
        if (miss.isNotEmpty()) { permLauncher.launch(miss.toTypedArray()); return }
        if (!MediaListener.isEnabled(this)) toast("Tip: allow notification access to share music")
        val addr = devices.getOrNull(i)?.second
        prefs().edit().putString("btAddr", addr ?: "").putBoolean("hotspot", hotspot.isChecked).apply()
        LinkService.startSender(this, addr, hotspot.isChecked)
    }

    private fun batteryOk() = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        if (batteryOk()) {
            // Allerede tillatt: åpne appens innstillinger (Samsung: Batteri → Ubegrenset)
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun toggleAudio() {
        if (Hub.audioStreaming.value) { LinkService.stopAudio(this); return }
        if (!Hub.connected.value) { toast("Connect to the screen first"); return }
        if (!Perms.has(this, Manifest.permission.RECORD_AUDIO)) { micLauncher.launch(Manifest.permission.RECORD_AUDIO); return }
        askProjection()
    }

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val i = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else mpm.createScreenCaptureIntent()
        projLauncher.launch(i)
    }

    private fun renderMedia(m: Hub.Media?) {
        if (m == null) {
            title.text = "Nothing playing"; artist.text = ""
            cover.setImageDrawable(null); playBtn.setImageResource(android.R.drawable.ic_media_play); return
        }
        title.text = m.title.ifBlank { "Unknown title" }
        artist.text = listOf(m.artist, m.app).filter { it.isNotBlank() }.joinToString(" · ")
        cover.setImageBitmap(m.art)
        playBtn.setImageResource(if (m.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    }

    private fun ctrl(icon: Int, action: String) = ImageButton(this).apply {
        setImageResource(icon); setColorFilter(C.TEXT); background = rounded(C.PANEL2, 20)
        layoutParams = LinearLayout.LayoutParams(40.dp, 40.dp).apply { rightMargin = 8.dp }
        setOnClickListener { MediaListener.control(action) }
    }

    private fun LinearLayout.section(t: String) {
        addView(label(t, 13f, C.ACCENT, true).apply { setPadding(0, 26.dp, 0, 10.dp) })
    }

    private fun wrap(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top.dp }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
