package cz.stihnuto.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Card(
    val id: String,
    val name: String,
    val stopName: String,
    val excludedKeys: Set<String>
)

data class Departure(
    val line: String,
    val headsign: String,
    val timeMs: Long,
    val delaySec: Int?,
    val tracked: Boolean,
    val canceled: Boolean
) {
    val key: String get() = "$line|$headsign"
}

class StopDepartures(val fetchedAt: Long, val items: List<Departure>)

/**
 * Native copy of the web app's data. The web app stays the source of truth:
 * it mirrors "cards" and "settings" here through [NativeBridge] so the widget
 * can read them without the WebView running.
 */
object Store {
    private const val PREFS = "stihnu"

    private fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveRaw(c: Context, key: String, json: String) {
        prefs(c).edit().putString("data_$key", json).apply()
    }

    fun apiKey(c: Context): String {
        val raw = prefs(c).getString("data_settings", null) ?: return ""
        return try {
            JSONObject(raw).optString("golemioKey", "").trim()
        } catch (e: Exception) {
            ""
        }
    }

    fun cards(c: Context): List<Card> {
        val raw = prefs(c).getString("data_cards", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Card>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val stop = o.optString("stopName", "")
                if (id.isEmpty() || stop.isEmpty()) continue
                val excluded = HashSet<String>()
                val ex = o.optJSONArray("excludedKeys")
                if (ex != null) {
                    for (j in 0 until ex.length()) excluded.add(ex.optString(j))
                }
                out.add(Card(id, o.optString("name", stop), stop, excluded))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun card(c: Context, id: String?): Card? {
        if (id == null) return null
        return cards(c).firstOrNull { it.id == id }
    }

    fun widgetCard(c: Context, widgetId: Int): String? =
        prefs(c).getString("widget_$widgetId", null)

    fun setWidgetCard(c: Context, widgetId: Int, cardId: String) {
        prefs(c).edit().putString("widget_$widgetId", cardId).apply()
    }

    fun removeWidget(c: Context, widgetId: Int) {
        prefs(c).edit().remove("widget_$widgetId").apply()
    }

    fun saveDepartures(c: Context, stopName: String, items: List<Departure>) {
        val arr = JSONArray()
        for (d in items) {
            val o = JSONObject()
            o.put("line", d.line)
            o.put("headsign", d.headsign)
            o.put("timeMs", d.timeMs)
            if (d.delaySec != null) o.put("delaySec", d.delaySec)
            o.put("tracked", d.tracked)
            o.put("canceled", d.canceled)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("fetchedAt", System.currentTimeMillis())
        root.put("items", arr)
        prefs(c).edit().putString("dep_$stopName", root.toString()).apply()
    }

    fun departures(c: Context, stopName: String): StopDepartures? {
        val raw = prefs(c).getString("dep_$stopName", null) ?: return null
        return try {
            val o = JSONObject(raw)
            StopDepartures(o.optLong("fetchedAt", 0L), parseSlim(o.optJSONArray("items") ?: JSONArray()))
        } catch (e: Exception) {
            null
        }
    }

    /** Parses the compact departure list written by the web app or by [saveDepartures]. */
    fun parseSlim(arr: JSONArray): List<Departure> {
        val out = ArrayList<Departure>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optLong("timeMs", 0L)
            if (t <= 0L) continue
            val delay: Int? =
                if (o.has("delaySec") && !o.isNull("delaySec")) o.optInt("delaySec") else null
            out.add(
                Departure(
                    line = o.optString("line", "?"),
                    headsign = o.optString("headsign", ""),
                    timeMs = t,
                    delaySec = delay,
                    tracked = o.optBoolean("tracked", false),
                    canceled = o.optBoolean("canceled", false)
                )
            )
        }
        out.sortBy { it.timeMs }
        return out
    }
}
