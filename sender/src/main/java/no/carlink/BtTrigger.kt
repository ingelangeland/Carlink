package no.carlink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat

/**
 * Starter CarLink når telefonen kobles til en valgt Bluetooth-enhet (f.eks. bilens eget Bluetooth
 * eller skjermen), og ber den stoppe 2 minutter etter at alle valgte enheter er borte.
 * Android vekker appen for disse hendelsene – den ligger ikke og bruker strøm i mellomtiden.
 */
class BtTrigger : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val dev = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val p = ctx.prefs()
        if (!p.getBoolean("auto", false)) return
        if (dev.address !in triggers(ctx)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                AppLog.init(ctx)
                ConnTimeline.start("car Bluetooth connected (${runCatching { dev.name }.getOrNull() ?: dev.address})")
                runCatching { start(ctx, dev.address) }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                if (LinkService.senderRunning) runCatching { LinkService.triggerLost(ctx, dev.address) }
        }
    }

    companion object {
        fun triggers(ctx: Context): Set<String> = ctx.prefs().getStringSet("triggers", emptySet()) ?: emptySet()

        fun start(ctx: Context, trigger: String) {
            val p = ctx.prefs()
            LinkService.startSender(ctx, p.getString("btAddr", null)?.ifBlank { null }, false, auto = true, trigger = trigger)
        }

        /** Ved åpning av appen: er en utløser allerede tilkoblet (f.eks. etter omstart)? Start i så fall. */
        @SuppressLint("MissingPermission")
        fun checkNow(ctx: Context) {
            if (!ctx.prefs().getBoolean("auto", false) || LinkService.senderRunning) return
            val t = triggers(ctx)
            val bonded = runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices }.getOrNull() ?: return
            bonded.firstOrNull { it.address in t && isConnected(it) }?.let { runCatching { start(ctx, it.address) } }
        }

        private fun isConnected(d: BluetoothDevice): Boolean =
            runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)
    }
}
