package no.carlink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import kotlin.math.hypot
import kotlin.math.max

/**
 * Lydbølger for musikk som spilles på selve bilskjermen (YouTube Music, Tidal …).
 * Telefonens musikk måles allerede der den spilles av; her lyttes det på det skjermen selv
 * sender ut (Androids «Visualizer» på hovedutgangen) og bass/diskant legges i Hub.
 */
object LocalViz {
    private var viz: Visualizer? = null
    /** Skjermen som eier målingen nå. Når skjermen bygges på nytt (f.eks. ny tekststørrelse),
     *  skal den gamle ikke kunne slå av målingen den nye nettopp har startet. */
    private var owner: Any? = null
    private var peakLow = 0.05f
    private var peakHigh = 0.05f
    private var silentSince = 0L
    @Volatile private var granting = false

    fun update(ctx: Context, active: Boolean) {
        owner = ctx
        if (active) start(ctx) else stop()
    }

    fun stop(from: Any) { if (owner === from) { stop(); owner = null } }

    private fun start(ctx: Context) {
        if (viz != null) return
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Tillatelsen gis stille med root (den trengs for å «høre» lyden ut av skjermen)
            if (!granting) {
                granting = true
                Thread {
                    Root.run("pm grant ${ctx.packageName} android.permission.RECORD_AUDIO")
                    granting = false
                }.start()
            }
            return
        }
        viz = runCatching {
            Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                val rateHz = samplingRate / 1000f
                val n = captureSize
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, r: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, r: Int) {
                        if (fft != null) analyse(fft, n, rateHz)
                    }
                }, Visualizer.getMaxCaptureRate(), false, true)
                enabled = true
            }
        }.getOrNull()
    }

    fun stop() {
        runCatching { viz?.enabled = false; viz?.release() }
        viz = null
    }

    private fun analyse(fft: ByteArray, n: Int, rateHz: Float) {
        val binHz = rateHz / n
        var low = 0f; var lowN = 0; var high = 0f; var highN = 0
        var k = 1
        while (2 * k + 1 < fft.size) {
            val f = k * binHz
            val m = hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()) / 128f
            if (f in 30f..250f) { low += m; lowN++ } else if (f in 2000f..9000f) { high += m; highN++ }
            k++
        }
        low = if (lowN > 0) low / lowN else 0f
        high = if (highN > 0) high / highN else 0f
        val now = SystemClock.elapsedRealtime()
        // Ingen lyd målbar (f.eks. lyden går en annen vei i bilen): la den rolige standardbølgen gå
        if (low + high < 0.0005f) {
            if (silentSince == 0L) silentSince = now
            if (now - silentSince > 1500) return
        } else silentSince = 0L
        // Automatisk nivå: følger musikken, så både rolig og høy musikk gir fine bølger
        peakLow = max(low, peakLow * 0.995f).coerceAtLeast(0.02f)
        peakHigh = max(high, peakHigh * 0.995f).coerceAtLeast(0.01f)
        Hub.audioLow = (low / peakLow * 0.85f).coerceIn(0f, 1f)
        Hub.audioHigh = (high / peakHigh * 0.7f).coerceIn(0f, 1f)
        Hub.audioLevelAt = now
    }
}
