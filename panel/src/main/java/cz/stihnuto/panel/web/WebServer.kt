package cz.stihnuto.panel.web

import android.util.Log
import cz.stihnuto.panel.App
import cz.stihnuto.panel.pages.ClaudePage
import cz.stihnuto.panel.pages.Pages
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Malý webový server na hodinách (port 8080):
 *  - GET  /                 nastavení v prohlížeči (PC, telefon)
 *  - GET  /api/state        stav panelu jako JSON
 *  - POST /api/page         {"page":"radar"}
 *  - POST /api/heating      {"room":"Obývák","target":22} nebo {"room":"…","on":false}
 *  - POST /api/media        {"action":"toggle|play|pause|next|previous|volume_up|volume_down"}
 *  - POST /api/play         {"query":"Kryštof","kind":"song|album|playlist"} → YouTube Music
 *  - POST /api/open         {"app":"youtube_music","search":"…"}
 *  - POST /api/ask          {"text":"…"} → odpověď Clauda
 *
 * Díky API může panel ovládat i Claude na počítači (viz README).
 */
class WebServer(private val app: App, private val port: Int) {
    private val pool = Executors.newFixedThreadPool(3)

    fun start() {
        Thread({
            while (true) {
                try {
                    ServerSocket(port).use { server ->
                        while (true) {
                            val s = server.accept()
                            pool.execute { handle(s) }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("Panel", "web server", e)
                    Thread.sleep(5000)
                }
            }
        }, "web").apply { isDaemon = true }.start()
    }

    private class Req(val method: String, val path: String, val query: Map<String, String>, val body: String) {
        fun form(): Map<String, String> = parseQuery(body)
        fun json(): JSONObject = if (body.trimStart().startsWith("{")) JSONObject(body) else JSONObject(form() + query)
    }

    private fun handle(s: Socket) {
        s.use { sock ->
            try {
                sock.soTimeout = 15000
                val input = BufferedInputStream(sock.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(len.coerceAtMost(1_000_000))
                var read = 0
                while (read < body.size) {
                    val n = input.read(body, read, body.size - read)
                    if (n < 0) break
                    read += n
                }
                val (path, qs) = parts[1].split('?', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val req = Req(parts[0], path, parseQuery(qs), String(body, 0, read, Charsets.UTF_8))
                route(req, sock.getOutputStream())
            } catch (e: Exception) {
                Log.w("Panel", "web request", e)
                try {
                    send(sock.getOutputStream(), 500, "application/json", JSONObject().put("error", e.message).toString())
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun route(r: Req, out: OutputStream) {
        when {
            r.path == "/" && r.method == "GET" -> send(out, 200, "text/html; charset=utf-8", SettingsPage.render(app))
            r.path == "/save" && r.method == "POST" -> {
                SettingsPage.save(app, r.form())
                app.onSettingsChanged()
                redirect(out, "/?ulozeno=1")
            }
            r.path == "/update" && r.method == "POST" -> {
                cz.stihnuto.panel.Updater.update(app)
                redirect(out, "/?aktualizace=1")
            }
            r.path == "/api/update" -> json(out, JSONObject().put("status", cz.stihnuto.panel.Updater.status))
            r.path == "/api/state" -> json(out, state())
            r.path == "/api/page" -> {
                val page = r.json().optString("page")
                json(out, JSONObject().put("ok", app.showPage(page)).put("pages", JSONArray(app.settings.pageList())))
            }
            r.path == "/api/heating" -> {
                val j = r.json()
                val c = app.heating.find(j.optString("room", j.optString("id")))
                if (c == null) {
                    json(out, JSONObject().put("ok", false).put("error", "hlavice nenalezena"), 404)
                } else {
                    val latch = CountDownLatch(1)
                    var err: String? = null
                    val done: (String?) -> Unit = { err = it; latch.countDown() }
                    when {
                        j.has("target") -> app.heating.setTarget(c.id, j.getDouble("target"), done)
                        j.has("on") -> app.heating.setOn(c.id, j.getBoolean("on"), done)
                        else -> { err = "chybí target nebo on"; latch.countDown() }
                    }
                    latch.await(20, TimeUnit.SECONDS)
                    json(out, JSONObject().put("ok", err == null).put("error", err))
                }
            }
            r.path == "/api/media" -> {
                val j = r.json()
                val ok = onMain {
                    if (j.has("volume")) { app.media.setVolume(j.getInt("volume")); true }
                    else app.media.command(j.optString("action"))
                }
                json(out, JSONObject().put("ok", ok == true))
            }
            r.path == "/api/play" -> {
                val j = r.json()
                val hit = cz.stihnuto.panel.YtMusic.search(j.optString("query"), j.optString("kind", "song"))
                val ok = hit != null && onMain { cz.stihnuto.panel.YtMusic.open(app, hit.url) } == true
                json(out, JSONObject().put("ok", ok).put("title", hit?.title).put("url", hit?.url))
            }
            r.path == "/api/open" -> {
                val j = r.json()
                val ok = onMain { app.media.openApp(j.optString("app"), j.optString("search").ifBlank { null }) }
                json(out, JSONObject().put("ok", ok == true))
            }
            r.path == "/api/ask" -> {
                val text = r.json().optString("text")
                val latch = CountDownLatch(1)
                var reply = ""
                ClaudePage.assistant(app).ask(text) { reply = it; latch.countDown() }
                latch.await(90, TimeUnit.SECONDS)
                json(out, JSONObject().put("reply", reply))
            }
            else -> send(out, 404, "text/plain; charset=utf-8", "Nenalezeno")
        }
    }

    private fun state(): JSONObject {
        val j = ClaudePage.assistant(app).status()
        j.put("okna", JSONArray(app.settings.pageList().map {
            JSONObject().put("id", it).put("nazev", Pages.all[it]?.title ?: it)
        }))
        return j
    }

    private fun <T> onMain(block: () -> T): T? {
        var out: T? = null
        val latch = CountDownLatch(1)
        app.main.post { try { out = block() } finally { latch.countDown() } }
        latch.await(5, TimeUnit.SECONDS)
        return out
    }

    private fun json(out: OutputStream, j: JSONObject, code: Int = 200) =
        send(out, code, "application/json; charset=utf-8", j.toString(2))

    private fun redirect(out: OutputStream, to: String) {
        out.write("HTTP/1.1 303 See Other\r\nLocation: $to\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun send(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Error" }
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    companion object {
        fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
            val i = it.indexOf('=')
            val k = if (i >= 0) it.substring(0, i) else it
            val v = if (i >= 0) it.substring(i + 1) else ""
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

        /** IP adresa hodin v domácí síti. */
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
