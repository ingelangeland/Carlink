package no.carlink

import android.app.Application
import org.osmdroid.config.Configuration

class CarLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().load(this, prefs())
        Configuration.getInstance().userAgentValue = packageName
    }
}
