package no.carlink

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Joystick: lær opp». Leder deg gjennom bevegelsene (uten at du må trykke på skjermen),
 * tar opp nøyaktig hva joysticken og knappene sender, og lagrer opptaket i Nedlastinger
 * og sender det til telefonen, så det kan deles med Claude.
 */
class JoystickActivity : AppCompatActivity() {

    private class Step(val text: String, val seconds: Int, val capture: Boolean = false)

    private val steps = listOf(
        Step("Move the joystick slowly around in a circle", 8),
        Step("Push the joystick UP and release – a few times", 6),
        Step("Push the joystick DOWN and release – a few times", 6),
        Step("Push the joystick LEFT and release – a few times", 6),
        Step("Push the joystick RIGHT and release – a few times", 6),
        Step("Press the joystick DOWN (click) and release – 3 times", 7),
        Step("Hold the joystick pressed and move it up and down", 8),
        Step("Use the SCROLL BUTTON next to it: up and down a few times", 8),
        Step("Press the other buttons by the joystick, one at a time (Back, Menu, Map …)", 12),
        Step("Raw movement: move the joystick around again", 8, capture = true),
    )

    private val log = StringBuilder(64 * 1024)
    private var lines = 0
    private var events = 0
    private var stepName = "start"
    private var recording = false
    private val t0 = SystemClock.uptimeMillis()

    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var counter: TextView
    private lateinit var area: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(C.BG); setPadding(40.dp, 30.dp, 40.dp, 30.dp)
            isFocusable = true; isFocusableInTouchMode = true
        }
        col.addView(label("Joystick: train", 16f, C.ACCENT, true).apply { gravity = Gravity.CENTER })
        title = label("", 30f, C.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, 18.dp, 0, 12.dp) }
        info = label("", 16f, C.SUB).apply { gravity = Gravity.CENTER }
        counter = label("", 14f, C.OK).apply { gravity = Gravity.CENTER; setPadding(0, 20.dp, 0, 0) }
        col.addView(title); col.addView(info); col.addView(counter)
        area = col
        setContentView(col)
        if (Build.VERSION.SDK_INT >= 26) col.setOnCapturedPointerListener { _, e -> record("RAW", e); true }
        header()
        lifecycleScope.launch { run() }
    }

    private suspend fun run() {
        title.text = "Get ready"
        info.text = "Follow the instructions on the screen. No need to touch the screen.\nStarting in 4 seconds …"
        delay(4000)
        recording = true
        for ((i, s) in steps.withIndex()) {
            stepName = "${i + 1}"
            log.append("\n### Step ${i + 1}: ${s.text}\n")
            title.text = s.text
            if (s.capture && Build.VERSION.SDK_INT >= 26) { area.requestFocus(); area.requestPointerCapture() }
            for (left in s.seconds downTo 1) {
                info.text = "Step ${i + 1} of ${steps.size} · $left s"
                delay(1000)
            }
            if (s.capture && Build.VERSION.SDK_INT >= 26) area.releasePointerCapture()
        }
        recording = false
        counter.text = "Received: $events signals"
        title.text = "Saving …"; info.text = ""
        val name = "CarLink-joystick-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".txt"
        val saved = withContext(Dispatchers.IO) { save(name, log.toString()) }
        val sent = Hub.send(JSONObject().put("t", "joylog").put("name", name).put("text", log.toString()))
        title.text = "Done!"
        info.text = buildString {
            append("$events signals recorded.\n\n")
            if (saved) append("Saved in Downloads: $name\n")
            append(if (sent) "Sent to the phone – tap the notification there to share it with Claude."
                   else "The phone isn't connected. Connect and run the recording again to send it.")
            append("\n\nReturning in 15 seconds.")
        }
        delay(15_000)
        finish()
    }

    // ---------------------------------------------------------------- opptak

    private fun header() {
        log.append("CarLink joystick recording\n")
        log.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        val dm = resources.displayMetrics
        log.append("Screen: ${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi} dpi\n\nInput devices:\n")
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            log.append("- #$id \"${d.name}\" vendor=${d.vendorId} product=${d.productId} sources=${sources(d.sources)} external=${if (Build.VERSION.SDK_INT >= 29) d.isExternal else "?"}\n")
            d.motionRanges.forEach { r ->
                log.append("    axis ${MotionEvent.axisToString(r.axis)} ${r.min}..${r.max} source=${sources(r.source)}\n")
            }
        }
    }

    private fun sources(s: Int): String {
        val names = listOf(
            InputDevice.SOURCE_MOUSE to "MOUSE", InputDevice.SOURCE_MOUSE_RELATIVE to "MOUSE_RELATIVE",
            InputDevice.SOURCE_TOUCHPAD to "TOUCHPAD", InputDevice.SOURCE_TOUCHSCREEN to "TOUCHSCREEN",
            InputDevice.SOURCE_TRACKBALL to "TRACKBALL", InputDevice.SOURCE_JOYSTICK to "JOYSTICK",
            InputDevice.SOURCE_GAMEPAD to "GAMEPAD", InputDevice.SOURCE_DPAD to "DPAD",
            InputDevice.SOURCE_KEYBOARD to "KEYBOARD", InputDevice.SOURCE_ROTARY_ENCODER to "ROTARY",
        )
        return names.filter { (bit, _) -> s and bit == bit }.joinToString("|") { it.second } + " (0x" + Integer.toHexString(s) + ")"
    }

    private fun t() = SystemClock.uptimeMillis() - t0

    private fun add(line: String) {
        if (!recording) return
        events++
        if (lines < 8000) { log.append(line).append('\n'); lines++ }
        if (events % 5 == 0) counter.text = "Received: $events signals"
    }

    private fun record(kind: String, e: MotionEvent) {
        val dev = e.device?.name ?: "?"
        add("${t()} $kind ${MotionEvent.actionToString(e.action)} x=%.1f y=%.1f rx=%.2f ry=%.2f vs=%.2f hs=%.2f buttons=%d source=%s device=\"%s\"".format(
            Locale.US, e.x, e.y, e.getAxisValue(MotionEvent.AXIS_RELATIVE_X), e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y),
            e.getAxisValue(MotionEvent.AXIS_VSCROLL), e.getAxisValue(MotionEvent.AXIS_HSCROLL), e.buttonState, sources(e.source), dev))
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!recording) return super.dispatchKeyEvent(e)
        add("${t()} KEY ${if (e.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"} ${KeyEvent.keyCodeToString(e.keyCode)} repeat=${e.repeatCount} source=${sources(e.source)} device=\"${e.device?.name}\"")
        return true   // alle taster fanges under opptaket (også Back), så opptaket ikke avbrytes
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (!recording) return super.dispatchTouchEvent(e)
        record("TOUCH", e); return true
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (!recording) return super.dispatchGenericMotionEvent(e)
        record("HOVER", e); return true
    }

    override fun dispatchTrackballEvent(e: MotionEvent): Boolean {
        if (!recording) return super.dispatchTrackballEvent(e)
        record("TRACKBALL", e); return true
    }

    /** Lagres i Nedlastinger (synlig i Filer-appen) – ingen tillatelse trengs på Android 10+. */
    private fun save(name: String, text: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 29) {
            // Older Android: the app's own folder on the storage (Android/data/no.carlink.launcher/files)
            java.io.File(getExternalFilesDir(null) ?: filesDir, name).writeText(text); return@runCatching true
        }
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
        contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        true
    }.getOrDefault(false)
}
