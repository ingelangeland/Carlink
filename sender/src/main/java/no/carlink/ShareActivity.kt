package no.carlink

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import org.json.JSONObject

/**
 * Mottar «Del» fra Google Maps (eller geo:-lenker) og sender stedet/ruten til bilskjermen,
 * som åpner det i Google Maps der.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Intent.ACTION_VIEW -> intent.dataString.orEmpty()
            else -> ""
        }
        val url = Regex("""(https?://\S+|geo:\S+)""").find(text)?.value
        val msg = if (url != null) JSONObject().put("t", "mapsurl").put("url", url)
        else JSONObject().put("t", "nav").put("q", text.trim())
        val ok = text.isNotBlank() && Hub.send(msg)
        Toast.makeText(
            this,
            if (ok) "Sent to the car screen" else "Not connected to the car – open CarLink Sender and tap \"Connect\"",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }
}
