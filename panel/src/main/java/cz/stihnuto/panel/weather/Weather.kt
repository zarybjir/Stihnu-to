package cz.stihnuto.panel.weather

import cz.stihnuto.panel.Http
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

class Current(
    val temp: Double, val feels: Double, val humidity: Int, val precip: Double,
    val code: Int, val wind: Double, val gusts: Double, val pressure: Double, val isDay: Boolean,
)

class Hour(
    val time: LocalDateTime, val temp: Double, val precipProb: Int, val precip: Double,
    val code: Int, val wind: Double, val isDay: Boolean,
)

class Day(
    val date: LocalDate, val code: Int, val max: Double, val min: Double,
    val precipSum: Double, val precipProb: Int, val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?, val windMax: Double, val uv: Double,
)

class Weather(val current: Current, val hours: List<Hour>, val days: List<Day>, val fetchedAt: Long)

/** Počasí z Open-Meteo (zdarma, bez klíče). */
object WeatherApi {
    fun fetch(lat: Double, lon: Double): Weather {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation," +
            "weather_code,wind_speed_10m,wind_gusts_10m,pressure_msl,is_day" +
            "&hourly=temperature_2m,precipitation_probability,precipitation,weather_code," +
            "wind_speed_10m,is_day&forecast_hours=48" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum," +
            "precipitation_probability_max,sunrise,sunset,wind_speed_10m_max,uv_index_max" +
            "&forecast_days=7&timezone=auto&wind_speed_unit=kmh"
        val j = JSONObject(Http.text(url))
        val c = j.getJSONObject("current")
        val current = Current(
            c.optDouble("temperature_2m"), c.optDouble("apparent_temperature"),
            c.optInt("relative_humidity_2m"), c.optDouble("precipitation", 0.0),
            c.optInt("weather_code"), c.optDouble("wind_speed_10m", 0.0),
            c.optDouble("wind_gusts_10m", 0.0), c.optDouble("pressure_msl", 0.0),
            c.optInt("is_day", 1) == 1,
        )
        val h = j.getJSONObject("hourly")
        val ht = h.getJSONArray("time")
        val hours = (0 until ht.length()).map { i ->
            Hour(
                LocalDateTime.parse(ht.getString(i)),
                h.getJSONArray("temperature_2m").optDouble(i),
                h.getJSONArray("precipitation_probability").optInt(i, 0),
                h.getJSONArray("precipitation").optDouble(i, 0.0),
                h.getJSONArray("weather_code").optInt(i),
                h.getJSONArray("wind_speed_10m").optDouble(i, 0.0),
                h.getJSONArray("is_day").optInt(i, 1) == 1,
            )
        }
        val d = j.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        fun dtOrNull(key: String, i: Int) =
            d.getJSONArray(key).optString(i, "").takeIf { it.length > 10 }?.let(LocalDateTime::parse)
        val days = (0 until dt.length()).map { i ->
            Day(
                LocalDate.parse(dt.getString(i)),
                d.getJSONArray("weather_code").optInt(i),
                d.getJSONArray("temperature_2m_max").optDouble(i),
                d.getJSONArray("temperature_2m_min").optDouble(i),
                d.getJSONArray("precipitation_sum").optDouble(i, 0.0),
                d.getJSONArray("precipitation_probability_max").optInt(i, 0),
                dtOrNull("sunrise", i), dtOrNull("sunset", i),
                d.getJSONArray("wind_speed_10m_max").optDouble(i, 0.0),
                d.getJSONArray("uv_index_max").optDouble(i, 0.0),
            )
        }
        return Weather(current, hours, days, System.currentTimeMillis())
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Jasno"
        1 -> "Skoro jasno"
        2 -> "Polojasno"
        3 -> "Zataženo"
        45, 48 -> "Mlha"
        51, 53, 55 -> "Mrholení"
        56, 57 -> "Mrznoucí mrholení"
        61 -> "Slabý déšť"
        63 -> "Déšť"
        65 -> "Silný déšť"
        66, 67 -> "Mrznoucí déšť"
        71 -> "Slabé sněžení"
        73 -> "Sněžení"
        75 -> "Silné sněžení"
        77 -> "Sněhová zrna"
        80, 81 -> "Přeháňky"
        82 -> "Prudké přeháňky"
        85, 86 -> "Sněhové přeháňky"
        95 -> "Bouřka"
        96, 99 -> "Bouřka s kroupami"
        else -> ""
    }
}
