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

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) LinkService.startAudio(this, r.resultCode, d, muteBox.isChecked)
        else toast("Lydstrømming avbrutt")
    }
    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) askProjection() else toast("CarLink trenger tillatelse til lydopptak for å strømme musikken")
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadDevices() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp, 28.dp, 20.dp, 40.dp) }
        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(col) })

        col.addView(label("CarLink · Sender", 26f, C.TEXT, true))
        statusTv = label("", 14f, C.SUB).apply { setPadding(0, 6.dp, 0, 0) }
        col.addView(statusTv)

        col.section("1 · Tilgang til musikk og navigasjon")
        notifBtn = pill("Gi tilgang til musikk og navigasjon") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        col.addView(notifBtn, wrap())
        batteryBtn = pill("Tillat at CarLink kjører i bakgrunnen") { askBattery() }
        col.addView(batteryBtn, wrap(8))
        col.addView(label("Samsung: velg også «Ubegrenset» under Innstillinger → Apper → CarLink Sender → Batteri.", 12f, C.SUB).apply { setPadding(0, 6.dp, 0, 0) })

        col.section("2 · Velg skjermen (paret via Bluetooth)")
        spinner = Spinner(this).apply { background = rounded(C.PANEL, 14); setPadding(8.dp, 8.dp, 8.dp, 8.dp) }
        col.addView(spinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(label("Finner du ikke skjermen? Par enhetene i Bluetooth-innstillingene først.", 12f, C.SUB).apply { setPadding(0, 6.dp, 0, 0) })
        col.addView(pill("Bluetooth-innstillinger") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, wrap(8))

        hotspot = CheckBox(this).apply {
            text = "Lag direkte Wi-Fi fra telefonen\n(bruk hvis dere ikke er på samme Wi-Fi/hotspot – skjermen får da ikke internett for kartfliser)"
            setTextColor(C.TEXT); textSize = 13f
            buttonTintList = ColorStateList.valueOf(C.ACCENT)
            isChecked = prefs().getBoolean("hotspot", false)
        }
        col.addView(hotspot, wrap(12))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pill("Koble til", C.ACCENT) { connect() })
        row.addView(pill("Koble fra") { LinkService.stop(this) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = 10.dp })
        col.addView(row, wrap(12))

        col.section("Lyd over Wi-Fi")
        col.addView(label("Musikken spilles av på skjermen i stedet for telefonen. Android spør om lov til lydopptak hver gang.", 12f, C.SUB))
        muteBox = CheckBox(this).apply {
            text = "Demp telefonen mens lyden går til skjermen"
            setTextColor(C.TEXT); textSize = 13f
            buttonTintList = ColorStateList.valueOf(C.ACCENT)
            isChecked = prefs().getBoolean("mute", true)
            setOnCheckedChangeListener { _, c -> prefs().edit().putBoolean("mute", c).apply() }
        }
        col.addView(muteBox, wrap(6))
        audioBtn = pill("Strøm lyd til skjermen", C.ACCENT) { toggleAudio() }
        col.addView(audioBtn, wrap(6))

        col.section("Spilles nå (deles med skjermen)")
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

        col.section("Send rute til skjermen")
        val dest = EditText(this).apply {
            hint = "Adresse eller sted"; setHintTextColor(C.SUB); setTextColor(C.TEXT)
            background = rounded(C.PANEL, 14); setPadding(14.dp, 12.dp, 14.dp, 12.dp); isSingleLine = true
        }
        col.addView(dest, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(pill("Start navigasjon på skjermen", C.ACCENT) {
            val q = dest.text.toString().trim()
            if (q.isEmpty()) return@pill
            if (Hub.send(JSONObject().put("t", "nav").put("q", q))) toast("Sendt til skjermen") else toast("Ikke tilkoblet")
        }, wrap(8))

        col.section("Ruter fra Google Maps")
        col.addView(label(
            "• Start navigasjon i Google Maps på telefonen – neste sving, avstand og ankomsttid vises på bilskjermen.\n" +
                "• Eller trykk «Del» på et sted eller en rute i Google Maps og velg «Send til bilen».",
            13f, C.SUB))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Hub.status.collect { statusTv.text = it } }
                launch { Hub.connected.collect { statusTv.setTextColor(if (it) C.OK else C.SUB) } }
                launch { Hub.localMedia.collect { renderMedia(it) } }
                launch {
                    Hub.audioStreaming.collect { on ->
                        audioBtn.text = if (on) "■ Stopp lydstrømming" else "Strøm lyd til skjermen"
                        audioBtn.background = rounded(if (on) C.WARN else C.ACCENT, 22)
                    }
                }
            }
        }

        val miss = Perms.missing(this, Perms.forRole(true))
        if (miss.isNotEmpty()) permLauncher.launch(miss.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        val ok = MediaListener.isEnabled(this)
        notifBtn.text = if (ok) "✓ Tilgang til musikk og navigasjon gitt" else "Gi tilgang til musikk og navigasjon"
        notifBtn.background = rounded(if (ok) C.PANEL2 else C.WARN, 22)
        val bat = batteryOk()
        batteryBtn.text = if (bat) "✓ Kan kjøre i bakgrunnen" else "Tillat at CarLink kjører i bakgrunnen"
        batteryBtn.background = rounded(if (bat) C.PANEL2 else C.WARN, 22)
        loadDevices()
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        devices = try {
            getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.map { (it.name ?: it.address) to it.address }?.sortedBy { it.first } ?: emptyList()
        } catch (_: SecurityException) { emptyList() }
        val names = devices.map { it.first }.ifEmpty { listOf("Ingen parede enheter") }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val last = prefs().getString("btAddr", null)
        devices.indexOfFirst { it.second == last }.takeIf { it >= 0 }?.let { spinner.setSelection(it) }
    }

    private fun connect() {
        val i = spinner.selectedItemPosition
        if (devices.isEmpty() || i !in devices.indices) { toast("Velg en paret skjerm først"); return }
        val miss = Perms.missing(this, Perms.forRole(true))
        if (miss.isNotEmpty()) { permLauncher.launch(miss.toTypedArray()); return }
        if (!MediaListener.isEnabled(this)) toast("Tips: gi varseltilgang for å dele musikk")
        val addr = devices[i].second
        prefs().edit().putString("btAddr", addr).putBoolean("hotspot", hotspot.isChecked).apply()
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
        if (!Hub.connected.value) { toast("Koble til skjermen først"); return }
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
            title.text = "Ingenting spilles"; artist.text = ""
            cover.setImageDrawable(null); playBtn.setImageResource(android.R.drawable.ic_media_play); return
        }
        title.text = m.title.ifBlank { "Ukjent tittel" }
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
