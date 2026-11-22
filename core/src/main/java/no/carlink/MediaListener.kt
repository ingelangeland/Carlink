package no.carlink

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap
import androidx.core.app.NotificationManagerCompat
import java.util.Objects

/**
 * Leser hvilken musikk som spilles i andre apper (Spotify, YouTube Music, osv.)
 * via medieøktene. Krever at brukeren gir "varseltilgang" til CarLink.
 */
class MediaListener : NotificationListenerService() {

    companion object {
        @Volatile private var instance: MediaListener? = null
        fun control(action: String) { Handler(Looper.getMainLooper()).post { instance?.doControl(action) } }
        const val MAPS_PKG = "com.google.android.apps.maps"
        fun isEnabled(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }

    private var msm: MediaSessionManager? = null
    private var current: MediaController? = null
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = select(null)
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { select(it) }

    private fun cn() = ComponentName(this, MediaListener::class.java)

    override fun onListenerConnected() {
        instance = this
        msm = getSystemService(MediaSessionManager::class.java)
        try { msm?.addOnActiveSessionsChangedListener(sessionsListener, cn(), main) } catch (_: SecurityException) {}
        select(null)
        try { activeNotifications?.forEach { onNotificationPosted(it) } } catch (_: Exception) {}
    }

    // ---------------- Navigasjon: les Google Maps sitt navigasjonsvarsel (neste sving, avstand, ankomst)

    private var navKey: String? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MAPS_PKG) return
        val n = sbn.notification
        val isNav = n.category == Notification.CATEGORY_NAVIGATION ||
            (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (!isNav) return
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val sub = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val icon = try { n.getLargeIcon()?.loadDrawable(this)?.toBitmap(96, 96) } catch (_: Exception) { null }
        val iconId = icon?.let { b ->
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); px.contentHashCode()
        } ?: 0
        navKey = sbn.key
        Hub.localNav.value = Hub.NavInfo(title, text, sub, icon, iconId)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == navKey) { navKey = null; Hub.localNav.value = null }
    }

    override fun onListenerDisconnected() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        current?.unregisterCallback(callback)
        current = null
        instance = null
        Hub.localMedia.value = null
    }

    private fun select(list: List<MediaController>?) {
        val ctrls = list ?: try { msm?.getActiveSessions(cn()) } catch (_: SecurityException) { null } ?: emptyList()
        // Hopp over tomme økter (f.eks. Google-appen uten noe som spilles)
        val real = ctrls.filter { !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() ||
            it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val best = real.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: real.firstOrNull()
        if (best?.sessionToken != current?.sessionToken) {
            current?.unregisterCallback(callback)
            current = best
            best?.registerCallback(callback, main)
        }
        publish()
    }

    private fun publish() {
        val c = current ?: run { Hub.localMedia.value = null; return }
        val md = c.metadata
        val ps = c.playbackState
        var art: Bitmap? = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (art != null && art.config == Bitmap.Config.HARDWARE) art = art.copy(Bitmap.Config.ARGB_8888, false)

        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.description?.title?.toString() ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: md?.description?.subtitle?.toString() ?: ""
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val playing = ps?.state == PlaybackState.STATE_PLAYING
        var pos = ps?.position ?: 0L
        if (playing && ps != null && ps.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
        }
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString()
        } catch (_: Exception) { c.packageName }

        Hub.localMedia.value = Hub.Media(
            title = title, artist = artist, album = album, playing = playing,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            positionMs = pos.coerceAtLeast(0), updatedAt = SystemClock.elapsedRealtime(),
            art = art, artId = Objects.hash(title, artist, album, art?.width, art?.height), app = appName,
        )
    }

    private fun doControl(action: String) {
        val c = current ?: run { select(null); current } ?: return
        val t = c.transportControls
        when (action) {
            "play" -> t.play()
            "pause" -> t.pause()
            "toggle" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            "next" -> t.skipToNext()
            "prev" -> t.skipToPrevious()
        }
    }
}
