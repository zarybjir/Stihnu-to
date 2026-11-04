package cz.stihnuto.panel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimální HTTP klient — bez knihoven, ať je aplikace co nejlehčí. */
object Http {
    private const val UA = "PanelHodiny/0.1 (Android)"

    fun bytes(url: String, timeoutMs: Int = 15000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", UA)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} pro $url")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    fun text(url: String): String = String(bytes(url), Charsets.UTF_8)

    fun bitmap(url: String): Bitmap? = try {
        val b = bytes(url)
        BitmapFactory.decodeByteArray(b, 0, b.size)
    } catch (e: Exception) {
        null
    }
}
