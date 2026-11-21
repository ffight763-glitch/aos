package org.anthezis.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Weather for the home widget from Open-Meteo (no key). Where is the phone? Tried in this order: the last location Android
 * knows, a fresh network fix (both need the location permission), and finally an approximate place from the IP address, so the
 * widget works even when no location is available.
 */
object Weather {
    private fun prefs(c: Context) = c.getSharedPreferences("aos_weather", Context.MODE_PRIVATE)

    /** More about the weather ("Feels 3° · Humidity 80% · Wind 12 km/h · 2° / 8°") from the last download, or null. */
    fun detail(c: Context): String? = prefs(c).getString("detail", null)

    fun hasPermission(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Forget the cached reading so the next [load] downloads again. */
    fun invalidate(c: Context) = prefs(c).edit().putLong("at", 0).apply()

    private fun symbol(code: Int) = when (code) {
        0 -> "☀️"; 1, 2 -> "⛅"; 3 -> "☁️"; 45, 48 -> "🌫️"
        in 51..57 -> "🌦️"; in 61..67, in 80..82 -> "🌧️"; in 71..77, 85, 86 -> "❄️"
        in 95..99 -> "⛈️"; else -> "🌡️"
    }

    private fun json(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.setRequestProperty("User-Agent", "AOS-Home")
        return JSONObject(conn.inputStream.bufferedReader().readText())
    }

    private fun gpsLocation(app: Context): Location? {
        if (!hasPermission(app)) return null
        val lm = app.getSystemService(LocationManager::class.java)
        try {
            val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: SecurityException) { null } }.maxByOrNull { it.time }
            if (last != null) return last
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                val latch = CountDownLatch(1); var got: Location? = null
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, app.mainExecutor) { got = it; latch.countDown() }
                latch.await(6, TimeUnit.SECONDS)
                return got
            }
        } catch (_: Exception) { }
        return null
    }

    /** "☀️ 12° Kyiv" from the cache when it is under 30 minutes old, otherwise downloaded. [done] runs on a background thread; null = no data. */
    fun load(c: Context, done: (String?) -> Unit) {
        val app = c.applicationContext
        val p = prefs(app)
        if (System.currentTimeMillis() - p.getLong("at", 0) < 30 * 60_000L) { done(p.getString("text", null)); return }
        Thread {
            try {
                var lat: Double; var lon: Double; var place = ""
                val loc = gpsLocation(app)
                if (loc != null) { lat = loc.latitude; lon = loc.longitude }
                else {
                    val ip = json("https://ipwho.is/")
                    if (!ip.optBoolean("success", false)) { done(p.getString("text", null)); return@Thread }
                    lat = ip.getDouble("latitude"); lon = ip.getDouble("longitude"); place = ip.optString("city")
                }
                val all = json("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,apparent_temperature,relative_humidity_2m,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min&timezone=auto")
                val cur = all.getJSONObject("current")
                val day = all.optJSONObject("daily")
                val detail = "Feels " + Math.round(cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m"))) + "°  ·  Humidity " + Math.round(cur.optDouble("relative_humidity_2m", 0.0)) + "%  ·  Wind " + Math.round(cur.optDouble("wind_speed_10m", 0.0)) + " km/h" +
                    (day?.let { d -> "  ·  " + Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)) + "° / " + Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)) + "°" } ?: "")
                p.edit().putString("detail", detail).apply()
                val text = symbol(cur.getInt("weather_code")) + " " + Math.round(cur.getDouble("temperature_2m")) + "°" + if (place.isNotBlank()) "  $place" else ""
                p.edit().putString("text", text).putLong("at", System.currentTimeMillis()).apply()
                done(text)
            } catch (_: Exception) { done(p.getString("text", null)) }
        }.start()
    }
}
