package no.carlink

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

/**
 * Går gjennom manglende tillatelser én om gangen, slik andre apper gjør:
 * en kort forklaring med «Gå til innstillinger» / «Avbryt», eller rett til Androids egen spørsmålsboks.
 * Hvert punkt spørres høyst én gang hver gang appen åpnes; «Avbryt» hopper over det.
 */
class SetupGuide(private val act: Activity) {

    class Step(
        val key: String,
        val title: String,
        val text: String?,          // null = vis Androids egen spørsmålsboks direkte
        val button: String = "Gå til innstillinger",
        val done: () -> Boolean,
        val action: () -> Unit,
    )

    private val steps = mutableListOf<Step>()
    private val asked = mutableSetOf<String>()
    private var dialog: AlertDialog? = null

    fun add(step: Step) = apply { steps += step }

    /** Kall i onResume og etter hvert svar: viser neste punkt som mangler. */
    fun next() {
        if (dialog?.isShowing == true || act.isFinishing) return
        val s = steps.firstOrNull { it.key !in asked && !it.done() } ?: return
        asked += s.key
        if (s.text == null) { runCatching { s.action() }; return }
        dialog = AlertDialog.Builder(act)
            .setTitle(s.title)
            .setMessage(s.text)
            .setPositiveButton(s.button) { _, _ -> runCatching { s.action() } }
            .setNegativeButton("Avbryt") { _, _ -> act.window.decorView.post { next() } }
            .setCancelable(true)
            .setOnCancelListener { act.window.decorView.post { next() } }
            .show()
    }

    companion object {
        /** Åpner bryteren for varseltilgang direkte for denne appen (Android 11+), ellers listen. */
        fun openNotificationAccess(act: Activity) {
            val cn = ComponentName(act, MediaListener::class.java).flattenToString()
            if (Build.VERSION.SDK_INT >= 30) {
                val ok = runCatching {
                    act.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn))
                }.isSuccess
                if (ok) return
            }
            act.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }
}
