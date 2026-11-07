package cz.stihnuto.panel

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Převod řeči na text přes Gemini (Claude zvuk nepřijímá). Použije se klíč
 * Gemini, i když asistentem je Claude — Gemini jen přepíše, co jsi řekl.
 */
object Transcribe {
    fun available(app: App) = app.settings.geminiKey.isNotBlank()

    fun wav(pcm: ShortArray): ByteArray {
        highPass(pcm, 16000, 90.0)
        normalize(pcm)
        val data = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        val d = data.array()
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + d.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(d.size)
        return h.array() + d
    }

    /** Odečte hluboký hukot a stejnosměrnou složku (ty se jinak zesílí na „vrčení"). */
    fun highPass(pcm: ShortArray, fs: Int, fc: Double) {
        val rc = 1.0 / (2 * Math.PI * fc)
        val dt = 1.0 / fs
        val a = rc / (rc + dt)
        var prevX = 0.0
        var prevY = 0.0
        for (i in pcm.indices) {
            val x = pcm[i].toDouble()
            val y = a * (prevY + x - prevX)
            prevX = x; prevY = y
            pcm[i] = y.toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Vyrovná hlasitost nahrávky na plný rozsah (ať je tichý mikrofon hodin dobře slyšet). */
    private fun normalize(pcm: ShortArray) {
        var peak = 1
        for (s in pcm) { val a = Math.abs(s.toInt()); if (a > peak) peak = a }
        if (peak < 150) return // nejspíš ticho — nezesilovat šum
        val gain = (24000.0 / peak).coerceIn(1.0, 12.0)
        if (gain <= 1.05) return
        for (i in pcm.indices) pcm[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
    }

    /** Vrátí přepsaný text, nebo vyhodí výjimku se srozumitelnou zprávou. */
    fun text(app: App, pcm: ShortArray): String =
        ask(app, pcm, "Přepiš přesně, co je v nahrávce řečeno česky. Vrať jen samotný text, " +
            "bez uvozovek a komentářů. Když nikdo nemluví, vrať prázdný text.", json = false).trim().trim('"', '„', '“')

    class Heard(val text: String, val forMe: Boolean, val command: String)

    /**
     * Přepis + rozhodnutí, jestli mluvčí mluví na asistenta (v jednom dotazu).
     * free = bez aktivační fráze; followUp = asistent právě odpovídal.
     */
    fun listen(app: App, pcm: ShortArray, free: Boolean, followUp: Boolean): Heard {
        val phrase = app.settings.wakePhrase
        val rule = if (free) """
            Asistent reaguje i bez oslovení. pro_asistenta = true, když jde o pokyn nebo otázku pro chytré hodiny
            či hlasového asistenta (počasí, čas, topení, hudba, nákupní seznam, kalendář, radar, minutka…) nebo když
            ho mluvčí osloví aktivačním slovem „$phrase“. pro_asistenta = false pro rozhovor mezi lidmi, televizi a rádio,
            telefonování, zpěv, poznámky pro sebe a věty bez jasného požadavku na asistenta.
        """ else """
            pro_asistenta = true jen tehdy, když mluvčí osloví asistenta slovem „$phrase“
            (klidně i mírně zkomoleně nebo uprostřed věty).
        """
        val follow = if (followUp) "Asistent před chvílí odpovídal — navazující otázka nebo upřesnění k rozhovoru je pro asistenta." else ""
        val prompt = """
            Nahrávka z mikrofonu chytrých hodin v české domácnosti. Hlasový asistent se jmenuje Pepo.
            Přepiš přesně, co je řečeno (česky), a rozhodni, jestli to je určené asistentovi.
            ${rule.trimIndent()}
            $follow
            Vrať JSON: {"text": "celý přepis", "pro_asistenta": true/false, "povel": "požadavek bez oslovení a bez aktivační fráze"}.
            Když nikdo nemluví, vrať {"text": "", "pro_asistenta": false, "povel": ""}.
        """.trimIndent()
        val out = ask(app, pcm, prompt, json = true)
        val j = try { JSONObject(out.substring(out.indexOf('{').coerceAtLeast(0))) } catch (e: Exception) { JSONObject() }
        return Heard(j.optString("text").trim(), j.optBoolean("pro_asistenta"), j.optString("povel").trim())
    }

    private fun ask(app: App, pcm: ShortArray, prompt: String, json: Boolean): String {
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav")
                .put("data", Base64.encodeToString(wav(pcm), Base64.NO_WRAP)))))))
        if (json) body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        var last: Exception? = null
        repeat(2) {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 10000; c.readTimeout = 15000
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", app.settings.geminiKey.trim())
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val t = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                if (code == 503) { last = java.io.IOException("Gemini je přetížený"); return@repeat }
                if (code == 429) throw java.io.IOException(
                    if (t.contains("PerDay", true) || t.contains("per day", true)) "Gemini: denní limit vyčerpán, zkus to zítra."
                    else "Gemini zahlcený (limit za minutu), zkus to za chvilku.")
                if (code !in 200..299) throw java.io.IOException("Přepis řeči selhal ($code)")
                val parts = JSONObject(t).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    ?: return ""
                return (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.takeIf { p -> !p.optBoolean("thought") }?.optString("text") }
                    .joinToString(" ")
            } catch (e: java.net.SocketTimeoutException) {
                last = java.io.IOException("Přepis řeči nestihl odpovědět")
            } finally {
                c.disconnect()
            }
        }
        throw last ?: java.io.IOException("Přepis řeči selhal")
    }
}
