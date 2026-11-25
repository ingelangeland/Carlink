package no.carlink.diagnose

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Linjer fra CarLinks logg med en av [tags], nyeste først. */
private fun logLines(log: List<String>, vararg tags: String, n: Int = 25) =
    log.filter { l -> tags.any { l.contains(" $it") } }.takeLast(n).reversed().map { shortTime(it) }

// ---------------------------------------------------------------------------------- lys

class Light(ctx: Context) : Section(ctx, "Screen light") {
    data class D(val prefs: Map<String, String>, val ill: String, val hw: String, val log: List<String>)

    override fun collect(): Any {
        val p = CarLinkData.prefs().filterKeys { k -> listOf("bright", "night", "dusk", "curve", "dim", "day").any { k.lowercase().contains(it) } }
        val out = Sh.run("settings get system SETTING_SCREEN_ILL; echo ---; for f in /sys/class/leds/lcd-backlight/brightness /sys/class/leds/lcd-backlight/max_brightness; do [ -e \$f ] && echo \"\$(basename \$(dirname \$f))/\$(basename \$f) = \$(cat \$f)\"; done; echo ---; settings get system screen_brightness")
        val parts = out.split("---")
        return D(p, parts.getOrNull(0)?.trim().orEmpty(), (parts.getOrNull(1).orEmpty().trim() + "\nAndroid brightness setting = " + parts.getOrNull(2)?.trim()).trim(), logLines(CarLinkData.log(), "light:", n = 20))
    }

    override fun render(d: Any) {
        d as D
        val c = ctx.card(); c.add(ctx.label("Now", 18f, C.TEXT, true))
        kv(c, "Car lights (headlights signal)", when (d.ill) { "1" -> "ON"; "0" -> "off"; else -> d.ill.ifBlank { "?" } })
        d.hw.lines().filter { it.isNotBlank() }.forEach { l -> kv(c, l.substringBefore(" = "), l.substringAfter(" = ")) }
        body.add(c, top = 14.dp)
        lines("CarLink's light settings", d.prefs.map { "${it.key} = ${it.value}" })
        lines("What CarLink did with the light (newest first)", d.log)
    }

    override fun report(d: Any): String { d as D
        return "Car lights=${d.ill} · ${d.hw.replace("\n", " · ")}\nSettings: ${d.prefs.entries.joinToString(", ") { "${it.key}=${it.value}" }}\n" + d.log.take(12).joinToString("\n") { "  $it" }
    }
}

// ---------------------------------------------------------------------------------- musikk

class Music(ctx: Context) : Section(ctx, "Music and sound") {
    data class D(val sessions: List<String>, val focus: List<String>, val log: List<String>)

    override fun collect(): Any {
        val ms = Sh.run("dumpsys media_session 2>/dev/null", 10)
        // Hver økt: «pkg/… (userId=0)» etterfulgt av «state=PlaybackState {state=3, …}»
        val sessions = ArrayList<String>()
        var pkg: String? = null
        ms.lines().forEach { l ->
            Regex("""package=([\w.]+)""").find(l)?.let { pkg = it.groupValues[1] }
            Regex("""state=PlaybackState \{state=(\d+)""").find(l)?.let { m ->
                val st = when (m.groupValues[1]) { "3" -> "playing"; "2" -> "paused"; "1" -> "stopped"; "6" -> "buffering"; "0" -> "none"; "7" -> "error"; else -> "state ${m.groupValues[1]}" }
                pkg?.let { sessions += "$it: $st" }; pkg = null
            }
        }
        val audio = Sh.run("dumpsys audio 2>/dev/null | grep -A6 'Audio Focus stack'", 8).lines().drop(1).filter { it.isNotBlank() }.map { it.trim().take(140) }.take(5)
        return D(sessions.distinct(), audio, logLines(CarLinkData.log(), "music:", n = 30))
    }

    override fun render(d: Any) {
        d as D
        lines("Music apps right now", d.sessions, "No music app has a session")
        lines("Who has the sound (audio focus)", d.focus, "Nobody")
        lines("What CarLink did with the music (newest first)", d.log)
    }

    override fun report(d: Any): String { d as D
        return "Sessions: ${d.sessions.joinToString(", ").ifBlank { "none" }}\nFocus: ${d.focus.firstOrNull() ?: "none"}\n" + d.log.take(25).joinToString("\n") { "  $it" }
    }
}

// ---------------------------------------------------------------------------------- nett

class Network(ctx: Context) : Section(ctx, "Network") {
    data class D(val wifi: Map<String, String>, val internet: Boolean, val pings: List<String>, val timelines: List<String>)

    override fun collect(): Any {
        val st = Sh.run("wpa_cli -i wlan0 status 2>/dev/null; echo ---; wpa_cli -i wlan0 signal_poll 2>/dev/null; echo ---; ip route 2>/dev/null | grep default", 8)
        val parts = st.split("---")
        val kv = LinkedHashMap<String, String>()
        (parts.getOrNull(0).orEmpty() + "\n" + parts.getOrNull(1).orEmpty()).lines().forEach { l -> if ('=' in l) kv[l.substringBefore('=').trim()] = l.substringAfter('=').trim() }
        val gw = Regex("via ([0-9.]+)").find(parts.getOrNull(2).orEmpty())?.groupValues?.get(1)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val inet = runCatching { cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true }.getOrDefault(false)
        val pings = ArrayList<String>()
        fun ping(name: String, host: String) {
            val o = Sh.run("ping -c 3 -W 2 $host 2>&1 | tail -2", 12)
            val loss = Regex("(\\d+)% packet loss").find(o)?.groupValues?.get(1)
            val avg = Regex("= [0-9.]+/([0-9.]+)/").find(o)?.groupValues?.get(1)
            pings += "$name ($host): " + if (loss == null) "no answer" else "${avg?.let { "${it.toDouble().toInt()} ms" } ?: "–"}, $loss % lost"
        }
        if (gw != null) ping("Phone (hotspot)", gw)
        ping("Internet", "8.8.8.8")
        val tl = CarLinkData.log().filter { it.contains("timeline: SUMMARY") }.takeLast(10).reversed().map { l ->
            shortTime(l).replace(Regex("timeline: SUMMARY (.*?) → connected in ([0-9,.]+ s).*"), "$1 → connected in $2")
        }
        return D(kv, inet, pings, tl)
    }

    override fun render(d: Any) {
        d as D
        val c = ctx.card(); c.add(ctx.label("Wi-Fi now", 18f, C.TEXT, true))
        kv(c, "Network", d.wifi["ssid"] ?: "not connected", if (d.wifi["wpa_state"] == "COMPLETED") C.OK else C.WARN)
        kv(c, "State", d.wifi["wpa_state"] ?: "?")
        d.wifi["freq"]?.let { f -> kv(c, "Band / channel", "${if ((f.toIntOrNull() ?: 0) > 4000) "5 GHz" else "2.4 GHz"} ($f MHz)") }
        d.wifi["RSSI"]?.let { r -> val v = r.toIntOrNull() ?: 0; kv(c, "Signal", "$r dBm", if (v > -65) C.OK else if (v > -75) C.WARN else C.BAD) }
        d.wifi["LINKSPEED"]?.let { kv(c, "Speed", "$it Mbit/s") }
        d.wifi["ip_address"]?.let { kv(c, "Address", it) }
        kv(c, "Internet", if (d.internet) "works" else "not working", if (d.internet) C.OK else C.BAD)
        body.add(c, top = 14.dp)
        lines("Ping", d.pings)
        lines("How long it took to connect to the phone (newest first)", d.timelines)
    }

    override fun report(d: Any): String { d as D
        return "Wi-Fi: ${d.wifi.filterKeys { it in setOf("ssid", "wpa_state", "freq", "RSSI", "LINKSPEED") }} · internet=${d.internet}\n" +
            d.pings.joinToString("\n") { "  $it" } + "\nConnect times:\n" + d.timelines.joinToString("\n") { "  $it" }
    }
}

// ---------------------------------------------------------------------------------- oppstart og hvile

class Sleep(ctx: Context) : Section(ctx, "Start-up and sleep") {
    data class D(val uptime: String, val wakelocks: List<String>, val events: List<String>)

    override fun collect(): Any {
        val up = Sh.run("cat /proc/uptime").trim().substringBefore('.').toLongOrNull()
        val pw = Sh.run("dumpsys power 2>/dev/null | sed -n '/Wake Locks:/,/^\$/p'", 8).lines().drop(1).map { it.trim() }.filter { it.isNotBlank() }
        val ev = logLines(CarLinkData.log(), "car off", "car switched", "woke", "started ·", "boot:", "parked", "screen on", "screensaver", n = 40)
            .filter { l -> listOf("car off", "car switched", "woke", "started ·", "boot:", "parked", "screen on", "screensaver").any { l.contains(it) } }
        return D(up?.let { "${it / 3600} h ${it % 3600 / 60} min since the screen started" } ?: "?", pw, ev)
    }

    override fun render(d: Any) {
        d as D
        cardOf("Screen", d.uptime to C.TEXT)
        lines("Keeping the screen awake right now", d.wakelocks.map { it.take(150) }, "Nothing – the screen can sleep")
        lines("Recent switch on / off (newest first)", d.events)
    }

    override fun report(d: Any): String { d as D
        return "${d.uptime}\nWake locks: ${d.wakelocks.joinToString(" | ").ifBlank { "none" }}\n" + d.events.take(25).joinToString("\n") { "  $it" }
    }
}

// ---------------------------------------------------------------------------------- ytelse

class Performance(ctx: Context) : Section(ctx, "Performance") {
    data class D(val mem: List<String>, val cpu: List<String>)

    override fun collect(): Any {
        val ps = Sh.run("ps -A -o RSS,NAME 2>/dev/null", 8).lines().drop(1).mapNotNull { l ->
            val f = l.trim().split(Regex("\\s+")); val kb = f.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            (f.getOrNull(1) ?: return@mapNotNull null) to kb
        }.groupBy({ it.first.substringBefore(':') }, { it.second }).mapValues { it.value.sum() }.entries.sortedByDescending { it.value }.take(15)
        val pm = ctx.packageManager
        val mem = ps.map { (n, kb) ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(n, 0)).toString() }.getOrNull()
            "${label?.let { "$it ($n)" } ?: n}: ${kb / 1024} MB"
        }
        val top = Sh.run("top -b -n 1 -m 12 2>/dev/null", 10).lines()
        val hi = top.indexOfFirst { it.contains("PID") && it.contains("CPU") }
        val cpu = if (hi < 0) emptyList() else top.drop(hi + 1).filter { it.isNotBlank() }.map { l ->
            val f = l.trim().split(Regex("\\s+")); val ci = top[hi].trim().split(Regex("\\s+")).indexOfFirst { it.contains("CPU") }
            "${f.lastOrNull()}: ${f.getOrNull(ci) ?: "?"} % CPU"
        }.take(10)
        return D(mem, cpu)
    }

    override fun render(d: Any) {
        d as D
        lines("Memory per app (largest first)", d.mem)
        lines("Processor right now (busiest first)", d.cpu)
    }

    override fun report(d: Any): String { d as D
        return "Memory: " + d.mem.take(10).joinToString(" | ") + "\nCPU: " + d.cpu.take(6).joinToString(" | ")
    }
}
