package no.carlink

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Felles liste over installerte apper, lastet én gang i bakgrunnen når launcheren starter
 * (så app-oversikten åpner uten forsinkelse), og oppdatert når apper installeres/fjernes.
 */
object AppCatalog {
    data class App(val label: String, val component: ComponentName, val icon: Drawable)

    val apps = MutableStateFlow<List<App>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private var pending: Job? = null

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        reload(app, 0)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { reload(app, 800) }
        }, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Samler flere endringer (f.eks. under en oppdatering) til én ny innlasting. */
    private fun reload(ctx: Context, wait: Long) {
        pending?.cancel()
        pending = scope.launch {
            delay(wait)
            val pm = ctx.packageManager
            val list = runCatching {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .filter { it.activityInfo.packageName != ctx.packageName }
                    .map { App(it.loadLabel(pm).toString(), ComponentName(it.activityInfo.packageName, it.activityInfo.name), it.loadIcon(pm)) }
                    .sortedBy { it.label.lowercase() }
            }.getOrNull() ?: return@launch
            apps.value = list
            Favorites.changed.value++   // fjernede apper forsvinner også fra Hjem
        }
    }
}
