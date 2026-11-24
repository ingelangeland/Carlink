package no.carlink

import android.app.Application
import org.osmdroid.config.Configuration

class CarLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().load(this, prefs())
        Configuration.getInstance().userAgentValue = packageName
        AppLog.init(this)
        SystemFix.installCrashGuard(this)
        // Bilskjermen tvangsavslutter CarLink når bilen slås av; Android starter den igjen kort etter
        // (musikk-lytteren). Få musestyringen og oppstartsvakten i gang med en gang, før bilen slås på.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ SystemFix.healJoystick(this) }, 1500)
        AppCatalog.start(this)
        Updater.apkName = "CarLink-bilskjerm.apk"
    }
}
