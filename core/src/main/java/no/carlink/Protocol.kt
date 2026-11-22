package no.carlink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Meldinger sendes som én JSON per linje over TCP (Wi-Fi).
 * Bluetooth (RFCOMM) brukes kun til å utveksle Wi-Fi-adresse / hotspot-info.
 *
 *  wifi  sender -> mottaker (BT)  {ips:[..], port, name, ssid?, pass?, sec?}
 *  ok/fail mottaker -> sender (BT)
 *  media sender -> mottaker       {title, artist, album, playing, dur, pos, app, artId, art?(base64 jpeg)}
 *  loc   sender -> mottaker       {lat, lon, brg, spd, hasBrg}
 *  nav   sender -> mottaker       {q}               -> start navigasjon i Google Maps på skjermen
 *  mapsurl sender -> mottaker     {url}             -> delt sted/rute fra Google Maps, åpnes på skjermen
 *  navinfo sender -> mottaker     {title,text,sub,iconId,icon?} eller {none:true} -> neste sving
 *  cmd   mottaker -> sender       {a: play|pause|toggle|next|prev}
 *  audio sender -> mottaker       {port, rate, ch}  -> mottaker kobler til lydporten
 *  audio_stop sender -> mottaker
 *  ping  begge veier
 */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("8f6a2c1e-3b7d-4e59-a1c2-5d9e0f7b3a64")
    const val PORT = 47800
    /** Egen TCP-port for rå lyd (PCM 16-bit stereo 48 kHz, ca. 1,5 Mbit/s). */
    const val AUDIO_PORT = 47801
    const val AUDIO_RATE = 48000

    fun media(m: Hub.Media, withArt: Boolean): JSONObject = JSONObject().apply {
        put("t", "media")
        put("title", m.title); put("artist", m.artist); put("album", m.album)
        put("playing", m.playing); put("dur", m.durationMs); put("pos", m.currentPosition())
        put("app", m.app); put("artId", m.artId)
        val art = m.art
        if (withArt && art != null) put("art", encode(art))
    }

    fun parseMedia(o: JSONObject, prev: Hub.Media?): Hub.Media {
        val artId = o.optInt("artId")
        val art = when {
            o.has("art") -> decode(o.getString("art"))
            prev != null && prev.artId == artId -> prev.art
            else -> null
        }
        return Hub.Media(
            title = o.optString("title"), artist = o.optString("artist"), album = o.optString("album"),
            playing = o.optBoolean("playing"), durationMs = o.optLong("dur"), positionMs = o.optLong("pos"),
            updatedAt = SystemClock.elapsedRealtime(), art = art, artId = artId, app = o.optString("app"),
        )
    }

    fun loc(l: Location): JSONObject = JSONObject()
        .put("t", "loc").put("lat", l.latitude).put("lon", l.longitude)
        .put("brg", l.bearing.toDouble()).put("spd", l.speed.toDouble()).put("hasBrg", l.hasBearing())
        .put("acc", l.accuracy.toDouble()).put("alt", l.altitude)

    fun parseLoc(o: JSONObject) = Hub.Loc(
        o.getDouble("lat"), o.getDouble("lon"),
        o.optDouble("brg", 0.0).toFloat(), o.optDouble("spd", 0.0).toFloat(), o.optBoolean("hasBrg"),
        o.optDouble("acc", 5.0).toFloat(), o.optDouble("alt", 0.0),
    )

    fun nav(n: Hub.NavInfo, withIcon: Boolean): JSONObject = JSONObject().apply {
        put("t", "navinfo"); put("title", n.title); put("text", n.text); put("sub", n.sub); put("iconId", n.iconId)
        val ic = n.icon
        if (withIcon && ic != null) put("icon", encodePng(ic))
    }

    fun parseNav(o: JSONObject, prev: Hub.NavInfo?): Hub.NavInfo {
        val id = o.optInt("iconId")
        val icon = when {
            o.has("icon") -> decode(o.getString("icon"))
            prev != null && prev.iconId == id -> prev.icon
            else -> null
        }
        return Hub.NavInfo(o.optString("title"), o.optString("text"), o.optString("sub"), icon, id)
    }

    private fun encodePng(b: Bitmap): String {
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun encode(b: Bitmap): String {
        val soft = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        val scale = min(1f, 1024f / max(soft.width, soft.height)) // stort nok til å fylle bilskjermen
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(
            soft, (soft.width * scale).toInt().coerceAtLeast(1), (soft.height * scale).toInt().coerceAtLeast(1), true
        ) else soft
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun decode(s: String): Bitmap? = runCatching {
        val bytes = Base64.decode(s, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
