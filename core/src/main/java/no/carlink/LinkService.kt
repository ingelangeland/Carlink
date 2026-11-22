package no.carlink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Forgrunnstjeneste som holder forbindelsen mellom de to enhetene.
 *
 *  SENDER (telefon):   TCP-server på Wi-Fi + Bluetooth-klient.
 *                      Kobler til skjermen over Bluetooth, sender sine IP-adresser
 *                      (og ev. hotspot-navn/passord), og venter på at skjermen kobler til over Wi-Fi.
 *  MOTTAKER (skjerm):  Bluetooth-server + TCP-klient.
 */
class LinkService : Service() {

    companion object {
        private const val ACTION_SENDER = "no.carlink.SENDER"
        private const val ACTION_RECEIVER = "no.carlink.RECEIVER"
        private const val ACTION_STOP = "no.carlink.STOP"
        private const val ACTION_AUDIO_START = "no.carlink.AUDIO_START"
        private const val ACTION_AUDIO_STOP = "no.carlink.AUDIO_STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_MUTE = "mute"
        private const val EXTRA_ADDR = "addr"
        private const val EXTRA_HOTSPOT = "hotspot"
        private const val EXTRA_FORCE = "force"
        private const val CHANNEL = "link"
        private const val NOTIF_ID = 42

        fun startSender(ctx: Context, address: String, hotspot: Boolean) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, LinkService::class.java).setAction(ACTION_SENDER)
                .putExtra(EXTRA_ADDR, address).putExtra(EXTRA_HOTSPOT, hotspot)
        )

        fun startReceiver(ctx: Context, force: Boolean = false) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, LinkService::class.java).setAction(ACTION_RECEIVER).putExtra(EXTRA_FORCE, force)
        )

        /** Start lydstrømming (kalles etter at brukeren har godtatt lydopptak). */
        fun startAudio(ctx: Context, resultCode: Int, data: Intent, mutePhone: Boolean) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, LinkService::class.java).setAction(ACTION_AUDIO_START)
                .putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data).putExtra(EXTRA_MUTE, mutePhone)
        )

        fun stopAudio(ctx: Context) {
            ctx.startService(Intent(ctx, LinkService::class.java).setAction(ACTION_AUDIO_STOP))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, LinkService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var mode: String? = null
    private val closeables = Collections.synchronizedSet(mutableSetOf<Closeable>())
    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val main = Handler(Looper.getMainLooper())

    // Lyd
    private var projection: MediaProjection? = null
    private var audioJob: Job? = null
    private var audioRxJob: Job? = null
    @Volatile private var audioRxSock: Socket? = null
    private var muteWanted = false
    private var savedVolume = -1
    @Volatile private var sessionPeer: InetAddress? = null
    @Volatile private var sessionNetwork: Network? = null
    private var wifiLocks: List<WifiManager.WifiLock> = emptyList()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); stopSelf(); return START_NOT_STICKY }
            ACTION_AUDIO_START -> {
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                if (data != null) startAudioCapture(code, data, intent.getBooleanExtra(EXTRA_MUTE, false))
                return START_NOT_STICKY
            }
            ACTION_AUDIO_STOP -> { stopAudioCapture(); return START_NOT_STICKY }
            ACTION_SENDER -> {
                if (!goForeground(true)) return START_NOT_STICKY
                val addr = intent.getStringExtra(EXTRA_ADDR) ?: return START_NOT_STICKY
                val hs = intent.getBooleanExtra(EXTRA_HOTSPOT, false)
                restart("sender") { senderLoop(addr, hs) }
            }
            ACTION_RECEIVER -> {
                if (!goForeground(false)) return START_NOT_STICKY
                val force = intent.getBooleanExtra(EXTRA_FORCE, false)
                if (!force && mode == "receiver" && job?.isActive == true) return START_STICKY
                restart("receiver") { receiverLoop() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- forgrunn / status

    private fun notification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val open = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LinkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(if (mode == "sender") "CarLink Sender" else "CarLink")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stopp", stop)
            .build()
    }

    private fun goForeground(sender: Boolean, audio: Boolean = false): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tilkobling", NotificationManager.IMPORTANCE_LOW))
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (sender && Perms.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (sender && (audio || projection != null) && Perms.has(this, Manifest.permission.RECORD_AUDIO)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(Hub.status.value), type)
            true
        } catch (e: Exception) {
            Hub.status.value = "Mangler tillatelser: ${e.message}"
            stopSelf()
            false
        }
    }

    private fun setStatus(s: String) {
        Hub.status.value = s
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(s)) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- livssyklus

    private fun restart(m: String, block: suspend CoroutineScope.() -> Unit) {
        job?.cancel()
        audioJob?.cancel(); stopAudioPlayback()
        closeAll(); releaseWifi(); stopHotspot()
        mode = m
        job = scope.launch(block = block)
    }

    private fun shutdown() {
        job?.cancel(); job = null
        stopAudioCapture(); stopAudioPlayback(); stopMockGps()
        closeAll(); releaseWifi(); stopHotspot()
        Hub.sender = null
        Hub.connected.value = false
        Hub.remoteMedia.value = null
        Hub.status.value = "Stoppet"
        mode = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun <T : Closeable> track(c: T): T { closeables += c; return c }
    private fun close(c: Closeable?) { if (c == null) return; closeables -= c; runCatching { c.close() } }
    private fun closeAll() {
        val list = synchronized(closeables) { closeables.toList().also { closeables.clear() } }
        list.forEach { runCatching { it.close() } }
    }

    private fun btAdapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter

    // ---------------------------------------------------------------- SENDER

    @SuppressLint("MissingPermission")
    private suspend fun senderLoop(address: String, useHotspot: Boolean) {
        val adapter = btAdapter() ?: return setStatus("Denne enheten har ikke Bluetooth")
        var hs: JSONObject? = null
        if (useHotspot) {
            setStatus("Starter direkte Wi-Fi …")
            hs = startHotspot() ?: return setStatus("Kunne ikke starte direkte Wi-Fi (slå av vanlig hotspot og prøv igjen)")
        }
        val server = try {
            track(ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Protocol.PORT)) })
        } catch (e: IOException) {
            return setStatus("Kunne ikke åpne port ${Protocol.PORT}: ${e.message}")
        }

        while (coroutineContext.isActive) {
            try {
                val ips = localIps()
                if (ips.isEmpty()) {
                    setStatus("Ingen Wi-Fi-adresse. Koble til Wi-Fi, slå på hotspot, eller bruk «direkte Wi-Fi».")
                    delay(5000); continue
                }
                setStatus("Kobler til skjermen via Bluetooth …")
                val msg = JSONObject().put("t", "wifi").put("ips", JSONArray(ips))
                    .put("port", Protocol.PORT).put("name", Build.MODEL)
                hs?.let { msg.put("ssid", it.getString("ssid")).put("pass", it.getString("pass")).put("sec", it.optString("sec")) }

                adapter.cancelDiscovery()
                val bt = track(adapter.getRemoteDevice(address).createInsecureRfcommSocketToServiceRecord(Protocol.SERVICE_UUID))
                bt.connect()
                val w = bt.outputStream.bufferedWriter()
                w.write(msg.toString()); w.newLine(); w.flush()
                setStatus("Venter på at skjermen kobler til over Wi-Fi …")
                val reply = bt.inputStream.bufferedReader().readLine()?.let { JSONObject(it) }
                close(bt)
                if (reply?.optString("t") != "ok") {
                    throw IOException(reply?.optString("why")?.ifBlank { null } ?: "Skjermen nådde ikke telefonen over Wi-Fi")
                }
                server.soTimeout = 10_000
                val sock = server.accept()
                Hub.peerName.value = reply.optString("name")
                runSession(sock, isSender = true)
                setStatus("Frakoblet – prøver igjen …")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("Feil: ${e.message ?: e.javaClass.simpleName}. Prøver igjen …")
            }
            Hub.connected.value = false
            delay(3000)
        }
    }

    /** Telefonens IPv4-adresser på Wi-Fi/hotspot (mobilnett utelates). */
    private fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("ccmni") && !it.name.startsWith("tun") && !it.name.startsWith("dummy") }
            .sortedBy { n -> if (listOf("wlan", "ap", "swlan", "softap", "p2p").any { n.name.startsWith(it) }) 0 else 1 }
            .flatMap { n -> n.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress } }
            .distinct()
    } catch (_: Exception) { emptyList() }

    @SuppressLint("MissingPermission")
    private suspend fun startHotspot(): JSONObject? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            try {
                wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                        hotspot = res
                        val o = JSONObject()
                        if (Build.VERSION.SDK_INT >= 30) {
                            val c = res.softApConfiguration
                            @Suppress("DEPRECATION")
                            o.put("ssid", c.ssid ?: "").put("pass", c.passphrase ?: "")
                            o.put("sec", if (c.securityType == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE) "wpa3" else "wpa2")
                        } else {
                            @Suppress("DEPRECATION")
                            val c = res.wifiConfiguration
                            @Suppress("DEPRECATION")
                            o.put("ssid", c?.SSID?.trim('"') ?: "").put("pass", c?.preSharedKey?.trim('"') ?: "").put("sec", "wpa2")
                        }
                        if (cont.isActive) cont.resume(o)
                    }
                    override fun onFailed(reason: Int) { if (cont.isActive) cont.resume(null) }
                    override fun onStopped() { hotspot = null }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun stopHotspot() { runCatching { hotspot?.close() }; hotspot = null }

    // ---------------------------------------------------------------- MOTTAKER

    @SuppressLint("MissingPermission")
    private suspend fun receiverLoop() {
        val adapter = btAdapter() ?: return setStatus("Denne enheten har ikke Bluetooth")
        while (coroutineContext.isActive) {
            try {
                if (!adapter.isEnabled) { setStatus("Slå på Bluetooth"); delay(4000); continue }
                setStatus("Venter på telefonen via Bluetooth …")
                val ss = track(adapter.listenUsingInsecureRfcommWithServiceRecord("CarLink", Protocol.SERVICE_UUID))
                val bt = track(ss.accept())
                close(ss)
                val reader = bt.inputStream.bufferedReader()
                val writer = bt.outputStream.bufferedWriter()
                val msg = JSONObject(reader.readLine() ?: throw IOException("Bluetooth lukket"))

                var network: Network? = null
                val ssid = msg.optString("ssid")
                if (ssid.isNotEmpty()) {
                    setStatus("Kobler til Wi-Fi «$ssid» …")
                    network = connectWifi(ssid, msg.optString("pass"), msg.optString("sec"))
                }

                setStatus("Kobler til telefonen over Wi-Fi …")
                val ips = msg.getJSONArray("ips")
                val port = msg.optInt("port", Protocol.PORT)
                var sock: Socket? = null
                for (attempt in 0 until 3) {
                    for (i in 0 until ips.length()) {
                        try {
                            val s = network?.socketFactory?.createSocket() ?: Socket()
                            s.connect(InetSocketAddress(ips.getString(i), port), 2500)
                            sock = s; break
                        } catch (_: IOException) {}
                    }
                    if (sock != null) break
                    delay(1500)
                }
                val reply = JSONObject().put("t", if (sock != null) "ok" else "fail").put("name", Build.MODEL)
                if (sock == null) reply.put("why", "Skjermen fant ikke telefonen på Wi-Fi. Er begge på samme nett?")
                writer.write(reply.toString()); writer.newLine(); writer.flush()
                delay(300)
                close(bt)
                if (sock == null) throw IOException("Fant ikke telefonen på Wi-Fi – er begge på samme nett?")

                Hub.peerName.value = msg.optString("name")
                sessionNetwork = network
                runSession(sock, isSender = false)
                sessionNetwork = null
                setStatus("Frakoblet")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("Feil: ${e.message ?: e.javaClass.simpleName}")
            }
            Hub.connected.value = false
            releaseWifi()
            delay(2000)
        }
    }

    /** Kobler skjermen til telefonens direkte Wi-Fi (Android viser en bekreftelsesdialog). */
    private suspend fun connectWifi(ssid: String, pass: String, sec: String): Network? =
        suspendCancellableCoroutine { cont ->
            val cm = getSystemService(ConnectivityManager::class.java)
            val b = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (sec == "wpa3") b.setWpa3Passphrase(pass) else b.setWpa2Passphrase(pass)
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(b.build())
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { if (cont.isActive) cont.resume(network) }
                override fun onUnavailable() { if (cont.isActive) cont.resume(null) }
            }
            releaseWifi()
            netCallback = cb
            try { cm.requestNetwork(req, cb, 60_000) } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
        }

    private fun releaseWifi() {
        val cb = netCallback ?: return
        netCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
    }

    // ---------------------------------------------------------------- økt (begge roller)

    private suspend fun runSession(sock: Socket, isSender: Boolean) = coroutineScope {
        track(sock)
        sessionPeer = sock.inetAddress
        holdLocks(true)
        sock.tcpNoDelay = true
        sock.soTimeout = 20_000
        val out = Channel<String>(64)
        Hub.sender = { o -> out.trySend(o.toString()) }
        Hub.connected.value = true
        val peer = Hub.peerName.value
        setStatus(if (isSender) "Tilkoblet skjermen ${peer}" else "Tilkoblet telefonen ${peer}")

        val writerJob = launch {
            val w = sock.getOutputStream().bufferedWriter()
            for (line in out) { w.write(line); w.newLine(); w.flush() }
        }
        val pingJob = launch { while (isActive) { delay(5000); out.trySend("{\"t\":\"ping\"}") } }
        val feedJob = if (isSender) launch(Dispatchers.Main) { senderFeeds() } else null
        if (isSender) startAudioIfReady()

        try {
            val r = sock.getInputStream().bufferedReader()
            while (isActive) {
                val line = r.readLine() ?: break
                val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                handle(o, isSender)
            }
        } catch (_: IOException) {
        } finally {
            Hub.sender = null
            Hub.connected.value = false
            Hub.remoteMedia.value = null
            Hub.remoteNav.value = null
            if (isSender) audioJob?.cancel() else { stopAudioPlayback(); stopMockGps() }
            holdLocks(false)
            feedJob?.cancel(); pingJob.cancel(); writerJob.cancel()
            out.close()
            close(sock)
        }
    }

    private fun handle(o: JSONObject, isSender: Boolean) {
        when (o.optString("t")) {
            "cmd" -> if (isSender) MediaListener.control(o.optString("a"))
            "media" -> if (!isSender) {
                Hub.remoteMedia.value = if (o.optBoolean("none")) null else Protocol.parseMedia(o, Hub.remoteMedia.value)
            }
            "loc" -> if (!isSender) Protocol.parseLoc(o).let { Hub.remoteLoc.value = it; pushMockGps(it) }
            "navinfo" -> if (!isSender) {
                Hub.remoteNav.value = if (o.optBoolean("none")) null else Protocol.parseNav(o, Hub.remoteNav.value)
            }
            "mapsurl" -> if (!isSender) main.post { Hub.mapsLinks.tryEmit(o.optString("url")) }
            "audio" -> if (!isSender) startAudioPlayback(o)
            "audio_stop" -> if (!isSender) stopAudioPlayback()
            "nav" -> if (!isSender) main.post { Hub.navRequests.tryEmit(o.optString("q")) }
        }
    }

    /** Sender musikk og GPS-posisjon fra telefonen så lenge økta varer. */
    @SuppressLint("MissingPermission")
    private suspend fun CoroutineScope.senderFeeds() {
        var lastArt = Int.MIN_VALUE
        launch {
            Hub.localMedia.collect { m ->
                if (m == null) {
                    Hub.send(JSONObject().put("t", "media").put("none", true))
                } else {
                    val withArt = m.artId != lastArt
                    lastArt = m.artId
                    Hub.send(Protocol.media(m, withArt))
                }
            }
        }
        var lastIcon = Int.MIN_VALUE
        launch {
            Hub.localNav.collect { n ->
                if (n == null) { Hub.send(JSONObject().put("t", "navinfo").put("none", true)); lastIcon = Int.MIN_VALUE }
                else { val withIcon = n.iconId != lastIcon; lastIcon = n.iconId; Hub.send(Protocol.nav(n, withIcon)) }
            }
        }
        // Send posisjonen jevnlig på nytt, også mens musikken spiller (holder fremdriften i synk).
        launch {
            while (isActive) {
                delay(10_000)
                Hub.localMedia.value?.let { Hub.send(Protocol.media(it, false)) }
            }
        }

        if (!Perms.has(this@LinkService, Manifest.permission.ACCESS_FINE_LOCATION)) return
        val lm = getSystemService(LocationManager::class.java)
        var lastGps = 0L
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                if (l.provider == LocationManager.GPS_PROVIDER) lastGps = SystemClock.elapsedRealtime()
                else if (SystemClock.elapsedRealtime() - lastGps < 5000) return
                Hub.send(Protocol.loc(l))
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                    lm.getLastKnownLocation(p)?.let { Hub.send(Protocol.loc(it)) }
                }
            } catch (_: Exception) {}
        }
        try { awaitCancellation() } finally { lm.removeUpdates(listener) }
    }

    // ---------------------------------------------------------------- LYD OVER WI-FI (sender)

    /**
     * Tar opp lyden som spilles i andre apper på telefonen (Android 10+ AudioPlaybackCapture)
     * og sender den som rå PCM over en egen TCP-forbindelse.
     */
    private fun startAudioCapture(code: Int, data: Intent, mute: Boolean) {
        if (mode != "sender") { Hub.status.value = "Koble til skjermen først"; return }
        if (!goForeground(true, audio = true)) return
        stopAudioCapture()
        val mp = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        } catch (e: Exception) { null }
        if (mp == null) { setStatus("Fikk ikke tilgang til lydopptak"); return }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { if (projection === mp) stopAudioCapture() } }
        }, main)
        projection = mp
        muteWanted = mute
        startAudioIfReady()
    }

    private fun stopAudioCapture() {
        audioJob?.cancel(); audioJob = null
        val mp = projection
        projection = null
        runCatching { mp?.stop() }
        Hub.audioStreaming.value = false
        if (mode == "sender" && Hub.connected.value) Hub.send(JSONObject().put("t", "audio_stop"))
    }

    private fun startAudioIfReady() {
        if (projection == null || !Hub.connected.value || audioJob?.isActive == true) return
        audioJob = scope.launch { audioSenderLoop() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun audioSenderLoop() {
        val mp = projection ?: return
        val rate = Protocol.AUDIO_RATE
        var record: AudioRecord? = null
        var server: ServerSocket? = null
        var sock: Socket? = null
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(max(minBuf, rate * 4 / 5))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            record = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IOException("lydopptak kunne ikke startes")

            val srv = track(ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Protocol.AUDIO_PORT)); soTimeout = 10_000 })
            server = srv
            Hub.send(JSONObject().put("t", "audio").put("port", Protocol.AUDIO_PORT).put("rate", rate).put("ch", 2))
            val s = track(srv.accept())
            sock = s
            close(srv); server = null
            s.tcpNoDelay = true
            s.sendBufferSize = 64 * 1024
            val out = s.getOutputStream()

            if (muteWanted) mutePhone(true)
            rec.startRecording()
            Hub.audioStreaming.value = true
            setStatus("Tilkoblet · strømmer lyd til skjermen")
            // Opptak og sending går i hver sin tråd med kø imellom, slik at et kort Wi-Fi-hikk
            // ikke stopper opptaket (som ellers ville gitt hull i lyden).
            val queue = LinkedBlockingDeque<ByteArray>()
            val maxQueued = 150 // 3 s à 20 ms
            coroutineScope {
                val writer = launch {
                    try {
                        while (isActive) {
                            val c = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                            out.write(c)
                        }
                    } catch (_: IOException) {} // brudd -> løkka under avslutter
                }
                try {
                    val chunk = rate * 4 / 50 // 20 ms
                    while (isActive) {
                        val buf = ByteArray(chunk)
                        var got = 0
                        while (got < chunk) {
                            val n = rec.read(buf, got, chunk - got)
                            if (n < 0) throw IOException("lydopptak feilet ($n)")
                            got += n
                        }
                        queue.offer(buf)
                        while (queue.size > maxQueued) queue.pollFirst()
                        if (!writer.isActive) throw IOException("forbindelsen til skjermen brøt")
                    }
                } finally { writer.cancel() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (Hub.connected.value) setStatus("Lydstrømming stoppet: ${e.message}")
        } finally {
            Hub.audioStreaming.value = false
            mutePhone(false)
            runCatching { record?.stop() }
            record?.release()
            close(server); close(sock)
        }
    }

    /** Demper telefonens egen avspilling mens lyden går til skjermen. */
    private fun mutePhone(on: Boolean) {
        val am = getSystemService(AudioManager::class.java)
        try {
            if (on && savedVolume < 0) {
                savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            } else if (!on && savedVolume >= 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
                savedVolume = -1
            }
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- LYD OVER WI-FI (mottaker)

    private fun startAudioPlayback(o: JSONObject) {
        stopAudioPlayback()
        val peer = sessionPeer ?: return
        val net = sessionNetwork
        audioRxJob = scope.launch {
            var player: AudioTrack? = null
            try {
                val rate = o.optInt("rate", Protocol.AUDIO_RATE)
                val s = track(net?.socketFactory?.createSocket() ?: Socket())
                audioRxSock = s
                s.receiveBufferSize = 64 * 1024
                s.connect(InetSocketAddress(peer, o.optInt("port", Protocol.AUDIO_PORT)), 4000)
                s.soTimeout = 8000
                val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                val p = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(max(minBuf * 2, rate * 4 / 5))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                player = p
                val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
                val chunkBytes = rate * 4 / 50                       // 20 ms per bit
                val targetMs = prefs().getInt("audioBufferMs", 600)  // justeres under «Mer» på skjermen
                val target = (targetMs / 20).coerceAtLeast(5)        // antall biter før avspilling starter
                val maxChunks = target * 3                           // tak, så forsinkelsen ikke vokser
                val queue = LinkedBlockingDeque<ByteArray>()

                // Tråd 1: les fra Wi-Fi inn i bufferen.
                val reader = launch {
                    try {
                        while (isActive) {
                            val c = ByteArray(chunkBytes)
                            input.readFully(c)
                            queue.offer(c)
                            while (queue.size > maxChunks) queue.pollFirst()
                        }
                    } catch (_: IOException) {} // brudd -> avspillingsløkka avslutter når bufferen er tom
                }
                // Tråd 2 (denne): spill fra bufferen. Går den tom, pauser vi og fyller opp igjen
                // i stedet for å hakke.
                var buffering = true
                var lp = 0f
                Hub.audioStreaming.value = true
                while (isActive && (reader.isActive || queue.isNotEmpty())) {
                    if (buffering) {
                        if (queue.size < target) { delay(20); continue }
                        buffering = false
                        p.play()
                    }
                    val c = queue.poll(150, TimeUnit.MILLISECONDS)
                    if (c == null) { p.pause(); buffering = true; continue }
                    p.write(c, 0, c.size)
                    // Enkel bass/diskant-måling til lydbølgene på hjemmeskjermen
                    var sl = 0.0; var sh = 0.0; var i = 0
                    while (i + 1 < c.size) {
                        val v = ((c[i + 1].toInt() shl 8) or (c[i].toInt() and 0xFF)).toShort() / 32768f
                        lp += 0.06f * (v - lp)
                        val hp = v - lp
                        sl += (lp * lp).toDouble(); sh += (hp * hp).toDouble()
                        i += 4
                    }
                    val cnt = (c.size / 4).coerceAtLeast(1)
                    Hub.audioLow = (sqrt(sl / cnt) * 3.2).toFloat().coerceAtMost(1f)
                    Hub.audioHigh = (sqrt(sh / cnt) * 4.5).toFloat().coerceAtMost(1f)
                    Hub.audioLevelAt = SystemClock.elapsedRealtime()
                }
                reader.cancel()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                Hub.audioStreaming.value = false
                runCatching { player?.pause(); player?.flush(); player?.stop() }
                player?.release()
                close(audioRxSock); audioRxSock = null
            }
        }
    }

    private fun stopAudioPlayback() {
        audioRxJob?.cancel(); audioRxJob = null
        close(audioRxSock); audioRxSock = null
        if (mode == "receiver") Hub.audioStreaming.value = false
    }

    /** Hindrer at Wi-Fi går i strømsparing (vanligste årsak til hakking) og at telefonen sovner. */
    @Suppress("DEPRECATION")
    private fun holdLocks(on: Boolean) {
        if (on) {
            if (wifiLocks.isNotEmpty()) return
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            wifiLocks = listOf(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WifiManager.WIFI_MODE_FULL_LOW_LATENCY).mapNotNull { m ->
                runCatching { wm.createWifiLock(m, "carlink:$m").apply { setReferenceCounted(false); acquire() } }.getOrNull()
            }
            wakeLock = runCatching {
                getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carlink:link")
                    .apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
            }.getOrNull()
        } else {
            wifiLocks.forEach { runCatching { it.release() } }
            wifiLocks = emptyList()
            runCatching { wakeLock?.release() }
            wakeLock = null
        }
    }

    // ---------------------------------------------------------------- telefonens GPS for hele bilskjermen

    private var mockReady = false

    /**
     * Gir telefonens posisjon videre som skjermens GPS, slik at også Google Maps og andre apper
     * på bilskjermen bruker den. Krever at CarLink er valgt som «app for simulert posisjon»
     * i utvikleralternativene på skjermen.
     */
    @Suppress("DEPRECATION")
    private fun pushMockGps(l: Hub.Loc) {
        if (!prefs().getBoolean("mockGps", false)) { if (mockReady) stopMockGps(); return }
        val lm = getSystemService(LocationManager::class.java)
        val p = LocationManager.GPS_PROVIDER
        try {
            if (!mockReady) {
                runCatching { lm.removeTestProvider(p) }
                lm.addTestProvider(p, false, true, false, false, true, true, true, 1, 1)
                lm.setTestProviderEnabled(p, true)
                mockReady = true
            }
            val loc = Location(p).apply {
                latitude = l.lat; longitude = l.lon; altitude = l.altitude
                speed = l.speed; if (l.hasBearing) bearing = l.bearing
                accuracy = l.accuracy.coerceAtLeast(1f)
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                verticalAccuracyMeters = 10f; speedAccuracyMetersPerSecond = 1f; bearingAccuracyDegrees = 10f
            }
            lm.setTestProviderLocation(p, loc)
        } catch (e: SecurityException) {
            mockReady = false
            setStatus("Velg CarLink som app for simulert posisjon i utvikleralternativene")
        } catch (_: Exception) {}
    }

    private fun stopMockGps() {
        if (!mockReady) return
        mockReady = false
        runCatching { getSystemService(LocationManager::class.java).removeTestProvider(LocationManager.GPS_PROVIDER) }
    }
}
