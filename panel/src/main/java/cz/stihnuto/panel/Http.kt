package cz.stihnuto.panel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Minimální HTTP klient — bez knihoven, ať je aplikace co nejlehčí. */
object Http {
    private const val UA = "PanelHodiny/0.1 (Android)"

    /** Strop velikosti odpovědi držené v paměti — hodiny mají málo RAM. */
    private const val MAX_BYTES = 16 * 1024 * 1024

    /**
     * Otevře adresu (jen http/https) a předá proud dat, ohlášenou délku (−1 = neznámá)
     * a spojení (kvůli kontrole, kam vedlo přesměrování). Velké soubory se tak dají
     * zpracovat postupně, bez načtení do paměti.
     */
    fun <T> open(url: String, timeoutMs: Int = 15000, block: (InputStream, Long, HttpURLConnection) -> T): T {
        val c = URL(url).openConnection() as? HttpURLConnection ?: throw IOException("Nepodporovaná adresa")
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", UA)
        try {
            // do hlášky jen server, ne celá adresa — bývá v ní tajný klíč (odkaz na kalendář)
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} (${c.url.host})")
            return c.inputStream.use { block(it, c.contentLengthLong, c) }
        } finally {
            c.disconnect()
        }
    }

    fun bytes(url: String, timeoutMs: Int = 15000, maxBytes: Int = MAX_BYTES): ByteArray =
        open(url, timeoutMs) { input, length, _ ->
            if (length > maxBytes) throw IOException("Odpověď je příliš velká")
            val out = ByteArrayOutputStream(if (length > 0) length.toInt() else 16 * 1024)
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > maxBytes) throw IOException("Odpověď je příliš velká")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }

    fun text(url: String): String = String(bytes(url), Charsets.UTF_8)

    fun bitmap(url: String): Bitmap? = try {
        val b = bytes(url)
        BitmapFactory.decodeByteArray(b, 0, b.size)
    } catch (e: Exception) {
        null
    }
}
