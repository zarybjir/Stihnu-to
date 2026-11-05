package cz.stihnuto.panel

import android.graphics.Bitmap
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.security.SecureRandom

/**
 * „Poslat z mobilu“: hodiny ukážou QR kód s jednorázovým kanálem ntfy.sh,
 * mobil do něj pošle text (klíč, odkaz na kalendář) a hodiny ho uloží.
 * Funguje i když se mobil a hodiny v síti nevidí.
 */
object PhoneInbox {
    @Volatile var topic: String? = null; private set
    @Volatile var status: String = ""; private set
    var onChange: (() -> Unit)? = null
    private var thread: Thread? = null

    val url get() = topic?.let { "https://ntfy.sh/$it" }

    fun start(app: App) {
        stop()
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        val t = "panel-" + (1..16).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        topic = t
        status = "Čekám na zprávu z mobilu…"
        val since = System.currentTimeMillis() / 1000
        thread = Thread({
            var last = since.toString()
            while (topic == t) {
                try {
                    val text = Http.text("https://ntfy.sh/$t/json?poll=1&since=$last")
                    for (line in text.lines().filter { it.isNotBlank() }) {
                        val j = JSONObject(line)
                        if (j.optString("event") != "message") continue
                        last = j.optString("id", last)
                        val msg = j.optString("message")
                        val result = apply(app, msg)
                        status = result
                        app.main.post { onChange?.invoke() }
                    }
                } catch (e: Exception) {
                    Log.w("Panel", "ntfy", e)
                }
                try { Thread.sleep(3000) } catch (e: InterruptedException) { return@Thread }
            }
        }, "phone-inbox").apply { isDaemon = true; start() }
    }

    fun stop() {
        topic = null
        thread?.interrupt()
        thread = null
    }

    /** Pozná, co přišlo, a uloží to. */
    private fun apply(app: App, msg: String): String {
        val s = app.settings
        val saved = mutableListOf<String>()
        for (raw in msg.split('\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            when {
                raw.startsWith("sk-ant-") -> { s.anthropicKey = raw; s.aiProvider = "claude"; saved += "klíč Claude" }
                raw.startsWith("AIza") || raw.startsWith("AQ.") -> { s.geminiKey = raw; s.aiProvider = "gemini"; saved += "klíč Gemini" }
                (raw.startsWith("http") || raw.startsWith("webcal")) && (raw.contains(".ics") || raw.contains("ical")) -> {
                    val list = s.calendarUrls.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (raw !in list) s.calendarUrls = (list + raw).joinToString("\n")
                    saved += "kalendář"
                }
            }
        }
        if (saved.isEmpty()) return "Přišla zpráva, ale nepoznal jsem v ní klíč ani odkaz na kalendář."
        app.main.post { app.onSettingsChanged() }
        return "Uloženo: ${saved.joinToString()} ✓"
    }

    fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
    }
}
