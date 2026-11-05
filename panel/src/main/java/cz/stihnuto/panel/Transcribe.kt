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
        val data = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        val d = data.array()
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + d.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(d.size)
        return h.array() + d
    }

    /** Vrátí přepsaný text, nebo vyhodí výjimku se srozumitelnou zprávou. */
    fun text(app: App, pcm: ShortArray): String {
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
            .put(JSONObject().put("text", "Přepiš přesně, co je v nahrávce řečeno česky. Vrať jen samotný text, " +
                "bez uvozovek a komentářů. Když nikdo nemluví, vrať prázdný text."))
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav")
                .put("data", Base64.encodeToString(wav(pcm), Base64.NO_WRAP)))))))
        var last: Exception? = null
        for (model in listOf("gemini-flash-lite-latest", "gemini-flash-lite-latest")) {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 10000; c.readTimeout = 15000
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", app.settings.geminiKey.trim())
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val t = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                if (code == 503) { last = java.io.IOException("Gemini je přetížený"); continue }
                if (code !in 200..299) throw java.io.IOException("Přepis řeči selhal ($code)")
                val parts = JSONObject(t).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    ?: return ""
                return (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.takeIf { p -> !p.optBoolean("thought") }?.optString("text") }
                    .joinToString(" ").trim().trim('"', '„', '“')
            } catch (e: java.net.SocketTimeoutException) {
                last = java.io.IOException("Přepis řeči nestihl odpovědět")
            } finally {
                c.disconnect()
            }
        }
        throw last ?: java.io.IOException("Přepis řeči selhal")
    }
}
