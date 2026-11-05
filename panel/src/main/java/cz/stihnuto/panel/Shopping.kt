package cz.stihnuto.panel

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Nákup do Kuchapky (mobil): položky jdou přes ntfy.sh do schránky s náhodným
 * názvem (kód se opíše do Kuchapky). Kuchapka převzetí potvrdí do „…-ack“;
 * do té doby hodiny položku občas pošlou znovu (ntfy drží zprávy jen ~12 h).
 */
class Shopping(private val app: App) {
    class Item(val id: String, val text: String, val at: Long, var sentAt: Long, var taken: Boolean)

    private val p = app.getSharedPreferences("shopping", 0)
    @Volatile var items: List<Item> = load(); private set

    /** 12 znaků bez snadno zaměnitelných (0/O, 1/I/L). */
    val code: String
        get() = p.getString("code", null) ?: run {
            val abc = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
            val r = SecureRandom()
            String(CharArray(12) { abc[r.nextInt(abc.length)] }).also { p.edit().putString("code", it).apply() }
        }
    val codePretty get() = code.chunked(4).joinToString("-")
    private val topic get() = "kuchapka-" + code.lowercase()

    init { tick() }

    /** Přidá položky („2 kg brambor“, „mléko“) a hned je pošle. */
    fun add(texts: List<String>) {
        val now = System.currentTimeMillis()
        val fresh = texts.map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { i, t ->
            Item("h$now-$i", t, now, 0L, false)
        }
        if (fresh.isEmpty()) return
        items = items + fresh
        save()
        app.io.execute { sendDue(); }
    }

    fun clearTaken() {
        items = items.filter { !it.taken }
        save()
    }

    fun pending() = items.filter { !it.taken }

    private fun tick() {
        if (items.any { !it.taken }) app.io.execute {
            try { checkAcks(); sendDue() } catch (e: Exception) { Log.w("Panel", "shopping", e) }
        }
        // převzaté položky po dni zmizí
        val old = System.currentTimeMillis() - 24 * 3600_000L
        if (items.any { it.taken && it.at < old }) { items = items.filter { !(it.taken && it.at < old) }; save() }
        app.main.postDelayed({ tick() }, 2 * 60_000L)
    }

    /** Pošle, co ještě nebylo posláno, nebo co se posílalo před víc než 6 h. */
    @Synchronized private fun sendDue() {
        val now = System.currentTimeMillis()
        var changed = false
        for (it in items) {
            if (it.taken || now - it.sentAt < 6 * 3600_000L) continue
            try {
                val c = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 10000; c.readTimeout = 10000
                c.outputStream.use { o -> o.write(JSONObject().put("id", it.id).put("text", it.text).toString().toByteArray()) }
                if (c.responseCode in 200..299) { it.sentAt = now; changed = true }
                c.disconnect()
            } catch (e: Exception) {
                Log.w("Panel", "shopping send", e)
            }
        }
        if (changed) save()
    }

    private fun checkAcks() {
        val c = URL("https://ntfy.sh/$topic-ack/json?poll=1&since=all").openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        val acked = mutableSetOf<String>()
        c.inputStream.bufferedReader().readLines().forEach { line ->
            val ev = try { JSONObject(line) } catch (e: Exception) { return@forEach }
            if (ev.optString("event") != "message") return@forEach
            val a = try { JSONObject(ev.optString("message")).optJSONArray("ack") } catch (e: Exception) { null } ?: return@forEach
            for (i in 0 until a.length()) acked += a.optString(i)
        }
        c.disconnect()
        var changed = false
        items.forEach { if (!it.taken && it.id in acked) { it.taken = true; changed = true } }
        if (changed) { save(); app.main.post { listeners.forEach { it() } } }
    }

    val listeners = mutableListOf<() -> Unit>()

    private fun load(): List<Item> = try {
        val a = JSONArray(p.getString("items", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Item(o.getString("id"), o.getString("text"), o.optLong("at"), o.optLong("sent"), o.optBoolean("taken"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save() {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.at).put("sent", it.sentAt).put("taken", it.taken)) }
        p.edit().putString("items", a.toString()).apply()
        app.main.post { listeners.forEach { it() } }
    }
}
