package cz.stihnuto.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime

/** Minimal Golemio client for the widget — same request as the web app. */
object Golemio {

    class ApiException(message: String) : Exception(message)

    fun fetchDepartures(apiKey: String, stopName: String): List<Departure> {
        val query = "names=" + URLEncoder.encode(stopName, "UTF-8") +
            "&preferredTimezone=" + URLEncoder.encode("Europe/Prague", "UTF-8") +
            "&includeMetroTrains=true&limit=60"
        val url = URL("https://api.golemio.cz/v2/pid/departureboards/?$query")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("x-access-token", apiKey)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code == 429) throw ApiException("příliš mnoho dotazů")
            if (code == 401 || code == 403) throw ApiException("neplatný API klíč")
            if (code !in 200..299) throw ApiException("HTTP $code")
            val root = JSONObject(body)
            if (root.has("error_status")) throw ApiException("zastávka nenalezena")
            return parse(root)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(root: JSONObject): List<Departure> {
        val arr = root.optJSONArray("departures") ?: return emptyList()
        val out = ArrayList<Departure>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val route = d.optJSONObject("route")
            val trip = d.optJSONObject("trip")
            val delay = d.optJSONObject("delay")
            val ts = d.optJSONObject("departure_timestamp") ?: d.optJSONObject("arrival_timestamp")
            val timeMs = parseTime(str(ts, "predicted") ?: str(ts, "scheduled")) ?: continue

            // delay values only mean something when the vehicle is tracked
            val tracked = delay != null && delay.optBoolean("is_available", false)
            var delaySec: Int? = null
            if (tracked && delay != null) {
                if (delay.has("seconds") && !delay.isNull("seconds")) {
                    delaySec = delay.optInt("seconds")
                } else if (delay.has("minutes") && !delay.isNull("minutes")) {
                    delaySec = delay.optInt("minutes") * 60
                }
            }

            out.add(
                Departure(
                    line = str(route, "short_name") ?: "?",
                    // trip.headsign is the direction; last_stop is NOT (see web app)
                    headsign = str(trip, "headsign") ?: "",
                    timeMs = timeMs,
                    delaySec = delaySec,
                    tracked = tracked,
                    canceled = trip != null && trip.optBoolean("is_canceled", false)
                )
            )
        }
        out.sortBy { it.timeMs }
        return out
    }

    private fun str(o: JSONObject?, key: String): String? {
        if (o == null || !o.has(key) || o.isNull(key)) return null
        val s = o.optString(key, "")
        return if (s.isEmpty()) null else s
    }

    private fun parseTime(s: String?): Long? {
        if (s.isNullOrEmpty()) return null
        return try {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                Instant.parse(s).toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }
}
