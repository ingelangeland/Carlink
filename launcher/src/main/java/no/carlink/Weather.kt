package no.carlink

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** Temperatur og værsymbol fra MET Norway (api.met.no, gratis, uten nøkkel) for posisjonen. */
object Weather {
    data class Now(val tempC: Int, val symbol: String)

    val now = MutableStateFlow<Now?>(null)
    private var lastFetch = 0L

    @SuppressLint("MissingPermission")
    suspend fun refresh(ctx: Context) = withContext(Dispatchers.IO) {
        if (now.value != null && SystemClock.elapsedRealtime() - lastFetch < 15 * 60_000) return@withContext
        val pos = Hub.remoteLoc.value?.let { it.lat to it.lon } ?: run {
            if (!Perms.has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) return@withContext
            val lm = ctx.getSystemService(LocationManager::class.java)
            val l = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            l?.let { it.latitude to it.longitude }
        } ?: return@withContext
        try {
            val url = URL(String.format(Locale.US,
                "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.3f&lon=%.3f", pos.first, pos.second))
            val c = url.openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "CarLink/1.0 (${ctx.packageName})")
            c.connectTimeout = 8000; c.readTimeout = 8000
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val data = json.getJSONObject("properties").getJSONArray("timeseries").getJSONObject(0).getJSONObject("data")
            val temp = data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature")
            val sym = data.optJSONObject("next_1_hours")?.optJSONObject("summary")?.optString("symbol_code").orEmpty()
            now.value = Now(temp.roundToInt(), sym)
            lastFetch = SystemClock.elapsedRealtime()
        } catch (_: Exception) {}
    }

    fun icon(sym: String): String {
        val night = sym.endsWith("_night") || Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it < 6 || it >= 22 }
        return when {
            "thunder" in sym -> "⛈"
            "snow" in sym || "sleet" in sym -> "❄"
            "rain" in sym -> "🌧"
            "fog" in sym -> "🌫"
            sym.startsWith("cloudy") -> "☁"
            "partlycloudy" in sym || "fair" in sym -> if (night) "☁" else "⛅"
            "clearsky" in sym -> if (night) "🌙" else "☀"
            else -> "☁"
        }
    }
}
