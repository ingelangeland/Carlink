package no.carlink

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log

/**
 * Kjøres som eget program med root (app_process), startet av JoyMouseService.
 *
 * Registrerer seg som Androids «aktivitetsvakt» og sier nei når bilens eget system
 * (com.spd.xhsntg) prøver å åpne seg rett etter et trykk på pilknappene ved joysticken.
 * Alt annet slipper igjennom: ryggekamera, langt trykk på Hjem (CarLink åpner da med
 * ACTION_MAIN) og alle andre apper. Stopper av seg selv når CarLink lukker forbindelsen.
 */
object StartGuard {
    private const val TAG = "CarLinkGuard"
    private const val DESC = "android.app.IActivityController"
    private const val CAR = "com.spd.xhsntg"

    @Volatile private var arrowAt = 0L

    @JvmStatic
    fun main(args: Array<String>) {
        val controller = object : Binder() {
            init { attachInterface(null, DESC) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
                runCatching { data.enforceInterface(DESC) }
                val result: Int = when (code) {
                    FIRST_CALL_TRANSACTION -> {      // boolean activityStarting(Intent, String)
                        val intent = if (data.readInt() != 0) Intent.CREATOR.createFromParcel(data) else null
                        val pkg = data.readString()
                        if (allow(intent, pkg)) 1 else 0
                    }
                    FIRST_CALL_TRANSACTION + 1 -> 1  // activityResuming
                    FIRST_CALL_TRANSACTION + 2 -> 1  // appCrashed: vanlig behandling
                    FIRST_CALL_TRANSACTION + 3 -> 0  // appEarlyNotResponding
                    FIRST_CALL_TRANSACTION + 4 -> 0  // appNotResponding: vanlig dialog
                    FIRST_CALL_TRANSACTION + 5 -> -1 // systemNotResponding: vanlig behandling
                    else -> 1
                }
                reply?.writeNoException()
                reply?.writeInt(result)
                return true
            }
        }
        try {
            val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
            val ctl = Class.forName("android.app.IActivityController\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, controller)
            am.javaClass.getMethod("setActivityController", Class.forName(DESC), Boolean::class.javaPrimitiveType)
                .invoke(am, ctl, false)
            Log.i(TAG, "guard active")
            println("GUARD-OK")
        } catch (e: Throwable) {
            Log.e(TAG, "could not register", e)
            println("GUARD-FAIL $e")
            return
        }
        Thread({ readCarFrames() }, "guard-log").apply { isDaemon = true; start() }
        // Kjør til CarLink lukker forbindelsen
        runCatching { while (System.`in`.read() >= 0) { /* ingenting */ } }
        Log.i(TAG, "guard stopping")
        System.exit(0)
    }

    private fun allow(intent: Intent?, pkg: String?): Boolean {
        if (pkg != CAR || intent == null) return true
        if (intent.action != null) return true            // CarLinks lange Hjem-trykk, og andre som starter den vanlig
        // Bilens programvare starter med en tom handling. Vent litt på loggen hvis trykket ikke er sett ennå.
        val deadline = SystemClock.elapsedRealtime() + 400
        while (SystemClock.elapsedRealtime() - arrowAt > 1500 && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(10)
        }
        val block = SystemClock.elapsedRealtime() - arrowAt <= 1500
        if (block) Log.i(TAG, "blocked car system after arrow button")
        return !block
    }

    private fun readCarFrames() {
        while (true) {
            runCatching {
                val p = ProcessBuilder("logcat", "-v", "raw", "-T", "1", "-s", "XCarBusLexusCar:D").start()
                p.inputStream.bufferedReader().useLines { lines ->
                    for (l in lines) {
                        val i = l.indexOf("rxData:22 2 3")
                        if (i >= 0) {
                            val parts = l.substring(i + 7).split(' ', ',')
                            val code = parts.getOrNull(2)?.toIntOrNull(16)
                            if (code == 0x32 || code == 0x33) arrowAt = SystemClock.elapsedRealtime()
                        }
                    }
                }
            }
            SystemClock.sleep(2000)
        }
    }
}
