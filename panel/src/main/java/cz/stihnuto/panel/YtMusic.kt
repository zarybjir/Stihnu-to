package cz.stihnuto.panel

import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Vyhledání v YouTube Music a spuštění v aplikaci na hodinách.
 * Hledá přes veřejné rozhraní webu music.youtube.com (bez klíče a bez
 * přihlášení), přehrává aplikace, ve které jsi přihlášený (Premium).
 */
object YtMusic {
    class Hit(val title: String, val subtitle: String, val url: String)

    /** kind: song | album | playlist | artist | any */
    fun search(query: String, kind: String = "song"): Hit? {
        val params = when (kind) {
            "song" -> "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
            "album" -> "EgWKAQIYAWoKEAkQAxAEEAoQBQ%3D%3D"
            "playlist" -> "EgeKAQQoAEABagoQAxAEEAoQCRAF"
            else -> null
        }
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX").put("clientVersion", "1.20241111.01.00")
                .put("hl", "cs").put("gl", "CZ")))
            .put("query", query)
        if (params != null) body.put("params", Uri.decode(params))

        val c = URL("https://music.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36")
        c.setRequestProperty("Origin", "https://music.youtube.com")
        val json = try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) throw java.io.IOException("YouTube Music: HTTP ${c.responseCode}")
            JSONObject(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
        } finally {
            c.disconnect()
        }

        // první položka s videem nebo playlistem
        var hit: Hit? = null
        walk(json) { o ->
            if (hit != null) return@walk
            val item = o.optJSONObject("musicResponsiveListItemRenderer")
                ?: o.optJSONObject("musicCardShelfRenderer") ?: return@walk
            val texts = mutableListOf<String>()
            walk(item) { t -> t.optJSONArray("runs")?.let { runs ->
                for (i in 0 until runs.length()) runs.optJSONObject(i)?.optString("text")?.let { if (it.isNotBlank()) texts += it }
            } }
            val s = item.toString()
            val video = Regex("\"videoId\":\"([\\w-]{11})\"").find(s)?.groupValues?.get(1)
            val list = Regex("\"playlistId\":\"([\\w-]+)\"").find(s)?.groupValues?.get(1)
            val browse = Regex("\"browseId\":\"(MPREb_[\\w-]+)\"").find(s)?.groupValues?.get(1)
            val url = when {
                kind == "song" && video != null -> "https://music.youtube.com/watch?v=$video"
                (kind == "playlist" || kind == "album") && list != null -> "https://music.youtube.com/playlist?list=$list"
                video != null -> "https://music.youtube.com/watch?v=$video" + (list?.let { "&list=$it" } ?: "")
                list != null -> "https://music.youtube.com/playlist?list=$list"
                else -> null
            } ?: return@walk
            hit = Hit(texts.firstOrNull() ?: query, texts.drop(1).filter { it != " • " && it != "•" }.take(2).joinToString(", "), url)
        }
        return hit
    }

    /**
     * Pustí odkaz v NewPipe (lehčí než YouTube Music na hodinách).
     * Odkaz z music.youtube.com se převede na youtube.com; u skladby se přidá
     * mix (RD…), aby po ní hrály podobné.
     */
    fun open(app: App, url: String): Boolean {
        val pkg = MediaRepo.APPS.getValue("newpipe")
        if (Updater.installedVersion(app, pkg) == null) return false
        val u = Uri.parse(url)
        val video = u.getQueryParameter("v")
        val list = u.getQueryParameter("list")
        val yt = when {
            video != null -> "https://www.youtube.com/watch?v=$video&list=${list ?: "RD$video"}"
            list != null -> "https://www.youtube.com/playlist?list=$list"
            else -> url.replace("music.youtube.com", "www.youtube.com")
        }
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(yt)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(app.packageManager) == null) return false
        app.startActivity(i)
        return true
    }

    private fun walk(v: Any?, f: (JSONObject) -> Unit) {
        when (v) {
            is JSONObject -> {
                f(v)
                val keys = v.keys()
                while (keys.hasNext()) walk(v.opt(keys.next()), f)
            }
            is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), f)
        }
    }
}
