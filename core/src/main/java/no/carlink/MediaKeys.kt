package no.carlink

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent

/**
 * Rattknappene på bilen sender vanlige medietaster (neste/forrige/play/pause) til den appen
 * Android mener spiller musikk. Mens telefonen er koblet til, har bilskjermen derfor en egen
 * «mediesesjon» som tar imot tastene og sender dem videre til telefonen.
 */
object MediaKeys {
    /** Joystick-treningen: får rattknappene (som CarLink fanger før de når skjermbildet). */
    @Volatile var keyTap: ((String) -> Unit)? = null
    private var session: MediaSession? = null

    fun actionFor(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "prev"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> "toggle"
        else -> null
    }

    fun start(ctx: Context) {
        if (session != null) return
        session = runCatching {
            MediaSession(ctx.applicationContext, "CarLink").apply {
                @Suppress("DEPRECATION")
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(intent: android.content.Intent): Boolean {
                        @Suppress("DEPRECATION")
                        val ev = intent.getParcelableExtra<KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT) ?: return false
                        if (ev.action == KeyEvent.ACTION_DOWN) keyTap?.invoke(KeyEvent.keyCodeToString(ev.keyCode))
                        val a = actionFor(ev.keyCode) ?: return false
                        if (ev.action == KeyEvent.ACTION_UP) Hub.mediaCommand(a)
                        return true
                    }
                    override fun onPlay() { keyTap?.invoke("play"); Hub.mediaCommand("play") }
                    override fun onPause() { keyTap?.invoke("pause"); Hub.mediaCommand("pause") }
                    override fun onSkipToNext() { keyTap?.invoke("next"); Hub.mediaCommand("next") }
                    override fun onSkipToPrevious() { keyTap?.invoke("previous"); Hub.mediaCommand("prev") }
                })
                isActive = true
            }
        }.getOrNull()
        update(Hub.remoteMedia.value)
    }

    /** Speiler telefonens avspilling, så bilen ser oss som «appen som spiller». */
    fun update(m: Hub.Media?) {
        val s = session ?: return
        runCatching {
            val st = if (m?.playing == true) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            s.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
                    or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(st, m?.positionMs ?: 0L, if (m?.playing == true) 1f else 0f)
                .build())
            if (m != null) s.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, m.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, m.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, m.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, m.durationMs)
                .build())
        }
    }

    fun stop() {
        runCatching { session?.isActive = false; session?.release() }
        session = null
    }
}
