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
import kotlinx.coroutines.flow.first
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
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
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
        private const val EXTRA_AUTO = "auto"
        private const val EXTRA_TRIGGER = "trigger"
        private const val EXTRA_UP = "up"
        private const val ACTION_TRIGGER = "no.carlink.TRIGGER"
        private const val NOTIF_AUDIO = 43
        /** Hvor lenge telefonen venter etter at bilens Bluetooth er borte før CarLink stopper. */
        const val STOP_DELAY_MS = 120_000L

        /** Kjører senderen nå? (brukes av Bluetooth-mottakeren) */
        @Volatile var senderRunning = false; private set
        private const val CHANNEL = "link"
        private const val NOTIF_ID = 42

        /**
         * Start telefonsiden. [address] = skjermens Bluetooth (kan være null: da venter telefonen bare
         * på at skjermen finner den over Wi-Fi/hotspot). [trigger] = Bluetooth-enheten som startet det automatisk.
         */
        fun startSender(ctx: Context, address: String?, hotspot: Boolean, auto: Boolean = false, trigger: String? = null) =
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, LinkService::class.java).setAction(ACTION_SENDER)
                    .putExtra(EXTRA_ADDR, address).putExtra(EXTRA_HOTSPOT, hotspot)
                    .putExtra(EXTRA_AUTO, auto).putExtra(EXTRA_TRIGGER, trigger)
            )

        /** En utløser-enhet (f.eks. bilens Bluetooth) ble koblet fra. */
        fun triggerLost(ctx: Context, address: String) {
            ctx.startService(Intent(ctx, LinkService::class.java).setAction(ACTION_TRIGGER)
                .putExtra(EXTRA_TRIGGER, address).putExtra(EXTRA_UP, false))
        }

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

    // Automatisk start/stopp
    private var autoStarted = false
    private val triggersUp = mutableSetOf<String>()
    private val stopLater = Runnable { shutdown(); stopSelf() }
    // Automatisk spill/pause: venter 5 s etter frakobling (korte brudd skal ikke pause musikken)
    @Volatile private var peerAutoPlay = false
    private val pauseLater = Runnable { AutoPlay.pause() }
    // Skjermen: bare én økt om gangen (Bluetooth og Wi-Fi-søk kan begge finne telefonen)
    private val rxBusy = AtomicBoolean(false)

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
                val addr = intent.getStringExtra(EXTRA_ADDR)?.ifBlank { null }
                val hs = intent.getBooleanExtra(EXTRA_HOTSPOT, false)
                val auto = intent.getBooleanExtra(EXTRA_AUTO, false)
                intent.getStringExtra(EXTRA_TRIGGER)?.let { triggersUp += it }
                main.removeCallbacks(stopLater)
                // Bilen kom tilbake innen 2 min: bare fortsett
                if (auto && mode == "sender" && job?.isActive == true) {
                    if (!Hub.connected.value) setStatus("Waiting for the screen …")
                    return START_STICKY
                }
                autoStarted = auto
                if (!auto) triggersUp.clear()
                restart("sender") { senderLoop(addr, hs) }
            }
            ACTION_TRIGGER -> {
                if (mode != "sender") { if (mode == null) stopSelf(); return START_NOT_STICKY }
                val a = intent.getStringExtra(EXTRA_TRIGGER) ?: return START_STICKY
                triggersUp -= a
                if (autoStarted && triggersUp.isEmpty()) {
                    main.removeCallbacks(stopLater)
                    main.postDelayed(stopLater, STOP_DELAY_MS)
                    setStatus("Car disconnected – CarLink stops in 2 minutes")
                }
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
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun goForeground(sender: Boolean, audio: Boolean = false): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW))
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (sender && Perms.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (sender && (audio || projection != null) && Perms.has(this, Manifest.permission.RECORD_AUDIO)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return try {
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, notification(Hub.status.value), type)
            } catch (e: SecurityException) {
                // Startet i bakgrunnen uten «posisjon hele tiden»: fortsett uten GPS-typen
                ServiceCompat.startForeground(this, NOTIF_ID, notification(Hub.status.value),
                    type and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION.inv())
            }
            true
        } catch (e: Exception) {
            Hub.status.value = "Missing permissions: ${e.message}"
            stopSelf()
            false
        }
    }

    /** Reserve: varsel «Strøm lyd til skjermen» ved automatisk start (kan slås av i appen). */
    private fun audioReminder(show: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (!show || !prefs().getBoolean("audioReminder", false)) { nm.cancel(NOTIF_AUDIO); return }
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("audio", "Audio to screen", NotificationManager.IMPORTANCE_DEFAULT))
            val i = (packageManager.getLaunchIntentForPackage(packageName) ?: return)
                .putExtra("carlink_audio", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(this, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NOTIF_AUDIO, NotificationCompat.Builder(this, "audio")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("CarLink is connected")
                .setContentText("Tap to stream audio over Wi-Fi to the screen")
                .setContentIntent(pi).setAutoCancel(true).build())
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
        senderRunning = m == "sender"
        rxBusy.set(false)
        job = scope.launch(block = block)
    }

    private fun shutdown() {
        job?.cancel(); job = null
        stopAudioCapture(); stopAudioPlayback(); stopMockGps()
        closeAll(); releaseWifi(); stopHotspot()
        Hub.sender = null
        Hub.connected.value = false
        Hub.remoteMedia.value = null
        Hub.status.value = "Stopped"
        mode = null
        senderRunning = false
        autoStarted = false
        triggersUp.clear()
        main.removeCallbacks(stopLater)
        audioReminder(false)
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

    /**
     * Telefonen er TCP-server. Skjermen finner den på to måter:
     *  1) Bluetooth direkte til skjermen (hvis valgt): telefonen forteller sine IP-adresser.
     *  2) Wi-Fi alene: skjermen er på telefonens hotspot og prøver «ruteren» (= telefonen) selv.
     */
    @SuppressLint("MissingPermission")
    private suspend fun senderLoop(address: String?, useHotspot: Boolean) = coroutineScope {
        var hs: JSONObject? = null
        if (useHotspot) {
            setStatus("Starting direct Wi-Fi …")
            hs = (if (Build.VERSION.SDK_INT >= 29) startHotspot() else null) ?: return@coroutineScope setStatus("Couldn't start direct Wi-Fi (turn off the regular hotspot and try again)")
        }
        val server = try {
            track(ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Protocol.PORT)) })
        } catch (e: IOException) {
            return@coroutineScope setStatus("Couldn't open port ${Protocol.PORT}: ${e.message}")
        }
        val adapter = btAdapter()
        if (address != null && adapter != null) launch { btAnnounceLoop(adapter, address, hs) }
        else setStatus(if (autoStarted) "Started automatically – waiting for the screen over Wi-Fi …" else "Waiting for the screen over Wi-Fi/hotspot …")

        server.soTimeout = 2000
        while (isActive) {
            val sock = try { runInterruptibleAccept(server) } catch (_: SocketTimeoutException) { continue }
                catch (e: IOException) { if (!isActive) break; delay(1000); continue }
            try {
                runSession(sock, isSender = true)
            } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            Hub.connected.value = false
            if (isActive) setStatus(if (triggersUp.isEmpty() && autoStarted) "Car disconnected – CarLink will stop shortly"
                                     else "Disconnected – waiting for the screen …")
        }
    }

    private suspend fun runInterruptibleAccept(server: ServerSocket): Socket = withContext(Dispatchers.IO) { server.accept() }

    /** Forteller skjermen (via Bluetooth) hvor telefonen er på Wi-Fi, til skjermen har koblet seg til. */
    @SuppressLint("MissingPermission")
    private suspend fun btAnnounceLoop(adapter: BluetoothAdapter, address: String, hs: JSONObject?) {
        var fails = 0
        while (coroutineContext.isActive) {
            if (Hub.connected.value) { fails = 0; delay(2000); continue }
            try {
                val ips = localIps()
                if (ips.isEmpty()) {
                    setStatus("No Wi-Fi address. Turn on the hotspot, connect to Wi-Fi, or use \"direct Wi-Fi\".")
                    delay(5000); continue
                }
                if (!adapter.isEnabled) { setStatus("Bluetooth is off – waiting for the screen over Wi-Fi …"); delay(5000); continue }
                setStatus("Connecting to the screen …")
                val msg = JSONObject().put("t", "wifi").put("ips", JSONArray(ips))
                    .put("port", Protocol.PORT).put("name", Build.MODEL)
                hs?.let { msg.put("ssid", it.getString("ssid")).put("pass", it.getString("pass")).put("sec", it.optString("sec")) }

                adapter.cancelDiscovery()
                val bt = track(adapter.getRemoteDevice(address).createInsecureRfcommSocketToServiceRecord(Protocol.SERVICE_UUID))
                try {
                    bt.connect()
                    val w = bt.outputStream.bufferedWriter()
                    w.write(msg.toString()); w.newLine(); w.flush()
                    setStatus("Waiting for the screen to connect over Wi-Fi …")
                    val reply = bt.inputStream.bufferedReader().readLine()?.let { JSONObject(it) }
                    if (reply?.optString("t") == "busy") { delay(5000); continue }
                    if (reply?.optString("t") != "ok") {
                        throw IOException(reply?.optString("why")?.ifBlank { null } ?: "The screen couldn't reach the phone over Wi-Fi")
                    }
                    Hub.peerName.value = reply.optString("name")
                } finally { close(bt) }
                // Vent til økta er i gang (eller gi opp etter 10 s)
                var waited = 0
                while (!Hub.connected.value && waited < 10_000) { delay(250); waited += 250 }
                fails = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fails++
                if (!Hub.connected.value) setStatus("Screen not found via Bluetooth – also trying Wi-Fi …")
            }
            // Prøv sjeldnere når skjermen ikke svarer (sparer strøm)
            delay(if (fails > 3) 15_000L else 3_000L)
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
    @androidx.annotation.RequiresApi(29)
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

    /**
     * Skjermen venter på telefonen på to måter samtidig:
     *  - Bluetooth: telefonen sier fra og forteller IP-adressen sin.
     *  - Wi-Fi: hvis skjermen er på telefonens hotspot, prøver den «ruteren» (= telefonen) hvert 3. sekund.
     *    Da trengs ingen Bluetooth mellom skjermen og telefonen i det hele tatt.
     */
    private suspend fun receiverLoop() = coroutineScope {
        val incoming = Channel<Pair<Socket, Network?>>(Channel.UNLIMITED)
        // Husk hvor musikken sist spilte (bilskjermen selv eller telefonen) – overlever strømbrudd
        launch {
            kotlinx.coroutines.flow.combine(Hub.localMedia, Hub.remoteMedia) { l, r ->
                when { l?.playing == true -> "car"; r?.playing == true -> "phone"; else -> null }
            }.collect { src -> if (src != null && src != prefs().getString("lastSource", null)) prefs().edit().putString("lastSource", src).apply() }
        }
        btAdapter()?.let { ad -> launch { btListenLoop(ad, incoming) } }
        launch { wifiProbeLoop(incoming) }
        setStatus("Waiting for the phone …")
        for ((sock, net) in incoming) {
            try {
                prefs().edit().putString("lastPhoneIp", sock.inetAddress.hostAddress).apply()
                sessionNetwork = net
                runSession(sock, isSender = false)
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
            } finally {
                sessionNetwork = null
                Hub.connected.value = false
                if (net != null) releaseWifi()
                rxBusy.set(false)
            }
            setStatus("Disconnected – waiting for the phone …")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun btListenLoop(adapter: BluetoothAdapter, incoming: Channel<Pair<Socket, Network?>>) {
        while (coroutineContext.isActive) {
            try {
                if (!adapter.isEnabled) { delay(4000); continue }
                val ss = track(adapter.listenUsingInsecureRfcommWithServiceRecord("CarLink", Protocol.SERVICE_UUID))
                val bt = track(withContext(Dispatchers.IO) { ss.accept() })
                close(ss)
                val reader = bt.inputStream.bufferedReader()
                val writer = bt.outputStream.bufferedWriter()
                val msg = JSONObject(reader.readLine() ?: throw IOException("Bluetooth closed"))
                if (rxBusy.get()) {
                    writer.write(JSONObject().put("t", "busy").toString()); writer.newLine(); writer.flush()
                    delay(300); close(bt); continue
                }

                var network: Network? = null
                val ssid = msg.optString("ssid")
                if (ssid.isNotEmpty()) {
                    setStatus("Connecting to Wi-Fi \"$ssid\" …")
                    network = connectWifi(ssid, msg.optString("pass"), msg.optString("sec"))
                }

                setStatus("Connecting to the phone over Wi-Fi …")
                val ips = msg.getJSONArray("ips")
                val port = msg.optInt("port", Protocol.PORT)
                var sock: Socket? = null
                for (attempt in 0 until 3) {
                    for (i in 0 until ips.length()) {
                        val s = network?.socketFactory?.createSocket() ?: Socket()
                        try {
                            s.connect(InetSocketAddress(ips.getString(i), port), 2500)
                            sock = s; break
                        } catch (_: IOException) { runCatching { s.close() } }
                    }
                    if (sock != null) break
                    delay(1500)
                }
                val claimed = sock != null && rxBusy.compareAndSet(false, true)
                if (sock != null && !claimed) runCatching { sock.close() }
                val reply = JSONObject().put("t", if (sock != null) "ok" else "fail").put("name", Build.MODEL)
                if (sock == null) reply.put("why", "The screen couldn't find the phone on Wi-Fi. Are both on the same network?")
                writer.write(reply.toString()); writer.newLine(); writer.flush()
                delay(300)
                close(bt)
                if (sock == null) { setStatus("Phone not found on Wi-Fi – are both on the same network?"); if (network != null) releaseWifi() }
                else if (claimed) { Hub.peerName.value = msg.optString("name"); incoming.send(sock to network) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!rxBusy.get()) setStatus("Bluetooth: ${e.message ?: e.javaClass.simpleName}")
                delay(2000)
            }
        }
    }

    /** Prøver telefonen direkte på Wi-Fi: hotspot-«ruteren» og siste kjente adresse. Billig: ett forsøk hvert 3. s. */
    private suspend fun wifiProbeLoop(incoming: Channel<Pair<Socket, Network?>>) {
        val cm = getSystemService(ConnectivityManager::class.java)
        // Skjermen av (bilen slått av / hvilemodus): ikke let etter telefonen før skjermen er på igjen
        val screenOn = kotlinx.coroutines.flow.MutableStateFlow(getSystemService(PowerManager::class.java).isInteractive)
        val screenRx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { screenOn.value = i.action == Intent.ACTION_SCREEN_ON }
        }
        ContextCompat.registerReceiver(this, screenRx, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        try { probeWhileOn(cm, screenOn, incoming) } finally { runCatching { unregisterReceiver(screenRx) } }
    }

    private suspend fun probeWhileOn(
        cm: ConnectivityManager,
        screenOn: kotlinx.coroutines.flow.StateFlow<Boolean>,
        incoming: Channel<Pair<Socket, Network?>>,
    ) {
        while (coroutineContext.isActive) {
            if (!screenOn.value) { screenOn.first { it } }   // venter uten å bruke strøm
            if (!rxBusy.get()) {
                val candidates = LinkedHashMap<String, Network?>()
                try {
                    @Suppress("DEPRECATION")
                    for (n in cm.allNetworks) {
                        val caps = cm.getNetworkCapabilities(n) ?: continue
                        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                        val lp = cm.getLinkProperties(n) ?: continue
                        lp.routes.mapNotNull { it.gateway as? Inet4Address }
                            .filter { !it.isAnyLocalAddress }.forEach { candidates[it.hostAddress!!] = n }
                        if (Build.VERSION.SDK_INT >= 30) (lp.dhcpServerAddress as? Inet4Address)?.let { candidates[it.hostAddress!!] = n }
                    }
                } catch (_: Exception) {}
                prefs().getString("lastPhoneIp", null)?.let { if (it !in candidates) candidates[it] = null }
                // Test på én enhet: Sender-appen installert på samme enhet
                if (packageName != "no.carlink.sender" &&
                    runCatching { packageManager.getPackageInfo("no.carlink.sender", 0) }.isSuccess) candidates["127.0.0.1"] = null
                for ((ip, net) in candidates) {
                    if (rxBusy.get()) break
                    var s: Socket? = null
                    try {
                        s = net?.socketFactory?.createSocket() ?: Socket()
                        val sock = s
                        withContext(Dispatchers.IO) { sock.connect(InetSocketAddress(ip, Protocol.PORT), 1200) }
                        if (rxBusy.compareAndSet(false, true)) { incoming.send(sock to null); s = null; break }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) {
                    } finally { s?.let { runCatching { it.close() } } }   // lukk mislykkede forsøk (ingen lekkasje)
                }
            }
            delay(3000)
        }
    }

    /** Kobler skjermen til telefonens direkte Wi-Fi (Android viser en bekreftelsesdialog). */
    private suspend fun connectWifi(ssid: String, pass: String, sec: String): Network? =
        if (Build.VERSION.SDK_INT < 29) null   // older Android: use the phone's hotspot instead
        else suspendCancellableCoroutine { cont ->
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
        setStatus(if (isSender) "Connected to screen ${peer}" else "Connected to phone ${peer}")

        val writerJob = launch {
            val w = sock.getOutputStream().bufferedWriter()
            for (line in out) { w.write(line); w.newLine(); w.flush() }
        }
        out.trySend(JSONObject().put("t", "hello").put("name", Build.MODEL).toString())
        main.removeCallbacks(pauseLater)
        if (!isSender) {
            // Skjermen bestemmer: send valget til telefonen, og start musikken der den sist spilte
            val auto = AutoPlay.enabled(this@LinkService)
            val last = prefs().getString("lastSource", "phone")
            out.trySend(JSONObject().put("t", "cfg").put("autoPlay", auto).put("last", last).toString())
            if (auto && last == "car") main.postDelayed({ AutoPlay.resume(this@LinkService) }, 1500)
        }
        if (isSender && autoStarted && projection == null) audioReminder(true)
        val pingJob = launch { while (isActive) { delay(5000); out.trySend("{\"t\":\"ping\"}") } }
        val feedJob = if (isSender) launch(Dispatchers.Main) { senderFeeds() } else null
        if (isSender) startAudioIfReady()

        try {
            val r = sock.getInputStream().bufferedReader()
            while (isActive) {
                val line = r.readLine() ?: break
                val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                // En feil i én melding skal aldri bryte forbindelsen
                runCatching { handle(o, isSender) }
            }
        } catch (_: IOException) {
        } finally {
            Hub.sender = null
            Hub.connected.value = false
            Hub.remoteMedia.value = null
            Hub.remoteNav.value = null
            if (isSender) { audioJob?.cancel(); audioReminder(false) } else { stopAudioPlayback(); stopMockGps() }
            val auto = if (isSender) peerAutoPlay else AutoPlay.enabled(this@LinkService)
            if (auto) { main.removeCallbacks(pauseLater); main.postDelayed(pauseLater, 5000) }
            holdLocks(false)
            feedJob?.cancel(); pingJob.cancel(); writerJob.cancel()
            out.close()
            close(sock)
        }
    }

    private fun handle(o: JSONObject, isSender: Boolean) {
        when (o.optString("t")) {
            "hello" -> o.optString("name").takeIf { it.isNotBlank() }?.let {
                Hub.peerName.value = it
                setStatus(if (isSender) "Connected to screen $it" else "Connected to phone $it")
            }
            "cmd" -> if (isSender) MediaListener.control(o.optString("a"))
            "joylog" -> if (isSender) JoyLog.received(this, o.optString("name", "CarLink-joystick.txt"), o.optString("text"))
            "cfg" -> if (isSender) {
                peerAutoPlay = o.optBoolean("autoPlay", false)
                if (peerAutoPlay && o.optString("last") == "phone") main.postDelayed({ AutoPlay.resume(this) }, 1500)
            }
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
                    // JPEG-koding av coveret skjer utenfor hovedtråden
                    Hub.send(withContext(Dispatchers.Default) { Protocol.media(m, withArt) })
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
        if (Build.VERSION.SDK_INT < 29) { setStatus("Audio streaming needs Android 10 on the phone"); return }
        if (mode != "sender") { Hub.status.value = "Connect to the screen first"; return }
        if (!goForeground(true, audio = true)) return
        audioReminder(false)
        stopAudioCapture()
        val mp = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        } catch (e: Exception) { null }
        if (mp == null) { setStatus("Audio capture was not allowed"); return }
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
        if (Build.VERSION.SDK_INT >= 29) audioJob = scope.launch { audioSenderLoop() }
    }

    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(29)
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
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IOException("audio capture could not start")

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
            setStatus("Connected · streaming audio to the screen")
            // Opptak og sending går i hver sin tråd med kø imellom, slik at et kort Wi-Fi-hikk
            // ikke stopper opptaket (som ellers ville gitt hull i lyden).
            val queue = LinkedBlockingDeque<ByteArray>()
            val maxQueued = 150 // 3 s à 20 ms
            // Gjenbruk lydbuffere (unngår 50 nye minneblokker i sekundet og rykk fra søppeltømming)
            val pool = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
            coroutineScope {
                val writer = launch {
                    try {
                        while (isActive) {
                            val c = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                            out.write(c)
                            pool.offer(c)
                        }
                    } catch (_: IOException) {} // brudd -> løkka under avslutter
                }
                try {
                    val chunk = rate * 4 / 50 // 20 ms
                    while (isActive) {
                        val buf = pool.poll() ?: ByteArray(chunk)
                        var got = 0
                        while (got < chunk) {
                            val n = rec.read(buf, got, chunk - got)
                            if (n < 0) throw IOException("audio capture failed ($n)")
                            got += n
                        }
                        queue.offer(buf)
                        while (queue.size > maxQueued) queue.pollFirst()?.let { pool.offer(it) }
                        if (!writer.isActive) throw IOException("the connection to the screen was lost")
                    }
                } finally { writer.cancel() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (Hub.connected.value) setStatus("Audio streaming stopped: ${e.message}")
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
                // Gjenbruk av buffere: unngår 50 nye minneblokker i sekundet
                val pool = java.util.concurrent.ArrayBlockingQueue<ByteArray>(maxChunks + 16)

                // Tråd 1: les fra Wi-Fi inn i bufferen.
                val reader = launch {
                    try {
                        while (isActive) {
                            val c = pool.poll() ?: ByteArray(chunkBytes)
                            input.readFully(c)
                            queue.offer(c)
                            while (queue.size > maxChunks) queue.pollFirst()?.let { pool.offer(it) }
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
                    pool.offer(c)
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
                if (Build.VERSION.SDK_INT >= 26) { verticalAccuracyMeters = 10f; speedAccuracyMetersPerSecond = 1f; bearingAccuracyDegrees = 10f }
            }
            lm.setTestProviderLocation(p, loc)
        } catch (e: SecurityException) {
            mockReady = false
            setStatus("Select CarLink as the mock location app in developer options")
        } catch (_: Exception) {}
    }

    private fun stopMockGps() {
        if (!mockReady) return
        mockReady = false
        runCatching { getSystemService(LocationManager::class.java).removeTestProvider(LocationManager.GPS_PROVIDER) }
    }
}
