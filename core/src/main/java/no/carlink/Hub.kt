package no.carlink

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Felles tilstand i appen – deles mellom tjenesten og skjermene. */
object Hub {
    data class Media(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val playing: Boolean = false,
        val durationMs: Long = 0,
        val positionMs: Long = 0,
        val updatedAt: Long = SystemClock.elapsedRealtime(),
        val art: Bitmap? = null,
        val artId: Int = 0,
        val app: String = "",
        val pkg: String = "",
    ) {
        fun currentPosition(): Long {
            if (!playing) return positionMs
            val p = positionMs + (SystemClock.elapsedRealtime() - updatedAt)
            return if (durationMs > 0) p.coerceAtMost(durationMs) else p
        }
    }

    data class Loc(
        val lat: Double, val lon: Double, val bearing: Float, val speed: Float, val hasBearing: Boolean,
        val accuracy: Float = 5f, val altitude: Double = 0.0,
    )

    /** Neste manøver fra Google Maps-navigasjonen på telefonen. */
    data class NavInfo(val title: String, val text: String, val sub: String, val icon: Bitmap?, val iconId: Int)

    val status = MutableStateFlow("Not started")
    val connected = MutableStateFlow(false)
    val peerName = MutableStateFlow("")
    /** Sender: lyd strømmes ut. Mottaker: lyd fra telefonen spilles av her. */
    val audioStreaming = MutableStateFlow(false)

    /** Musikk som spilles på denne enheten (via MediaListener). */
    val localMedia = MutableStateFlow<Media?>(null)
    /** Musikk mottatt fra den andre enheten. */
    val remoteMedia = MutableStateFlow<Media?>(null)
    /** Posisjon mottatt fra senderen. */
    val remoteLoc = MutableStateFlow<Loc?>(null)
    /** Sving-for-sving fra Google Maps på denne enheten (telefonen). */
    val localNav = MutableStateFlow<NavInfo?>(null)
    /** Sving-for-sving mottatt fra telefonen (bilskjermen). */
    val remoteNav = MutableStateFlow<NavInfo?>(null)
    /** Google Maps-lenker (sted/rute) delt fra telefonen. */
    val mapsLinks = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Navigasjonsmål sendt fra telefonen. */
    val navRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Lydnivå (bass/diskant, 0–1) fra lyden som spilles på skjermen – brukes av lydbølgene. */
    @Volatile var audioLow = 0f
    @Volatile var audioHigh = 0f
    @Volatile var audioLevelAt = 0L

    @Volatile var sender: ((JSONObject) -> Unit)? = null

    fun send(o: JSONObject): Boolean {
        val s = sender ?: return false
        s(o); return true
    }

    /** play / pause / toggle / next / prev – går til telefonen hvis den spiller, ellers lokalt. */
    /** Settes av demomodus, så knappene virker uten telefon. */
    @Volatile var demoHandler: ((String) -> Unit)? = null

    /** Hvilken kilde som sist begynte å spille: true = telefonen, false = musikk på denne skjermen. */
    @Volatile var remoteActive = true

    /**
     * Skal knappene/visningen følge telefonens musikk? Den som spiller vinner; spiller ingen
     * (f.eks. et lite opphold mellom to sanger), gjelder den som sist startet avspilling.
     */
    fun useRemote(c: Boolean, r: Media?, l: Media?): Boolean {
        if (!c || r == null) return false
        if (l == null) return true
        if (r.playing != l.playing) return r.playing
        return remoteActive
    }

    /**
     * Bare én kilde skal spille om gangen: starter du musikk på telefonen, pauses musikken på
     * skjermen – og omvendt. Kjøres på bilskjermen mens telefonen er koblet til.
     */
    suspend fun arbitrate() = kotlinx.coroutines.coroutineScope {
        launch {
            var was = remoteMedia.value?.playing == true
            remoteMedia.collect { r ->
                val now = r?.playing == true
                if (now && !was) {
                    remoteActive = true
                    if (localMedia.value?.playing == true) { MediaListener.control("pause"); AppLog.i("music", "phone started – paused music on the screen") }
                }
                was = now
            }
        }
        launch {
            var was = localMedia.value?.playing == true
            localMedia.collect { l ->
                val now = l?.playing == true
                if (now && !was) {
                    remoteActive = false
                    if (remoteMedia.value?.playing == true) { send(JSONObject().put("t", "cmd").put("a", "pause")); AppLog.i("music", "screen started – paused the phone") }
                }
                was = now
            }
        }
    }

    fun mediaCommand(action: String) {
        demoHandler?.let { it(action); return }
        if (useRemote(connected.value, remoteMedia.value, localMedia.value)) {
            send(JSONObject().put("t", "cmd").put("a", action))
        } else {
            MediaListener.control(action)
        }
    }
}
