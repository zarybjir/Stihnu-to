package cz.stihnuto.panel.tapo

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Ovládání přes cloud TP-Link (stejně jako aplikace Kasa/Tapo) — když hub není
 * v téže síti. Neoficiální rozhraní, popsané projektem tplink-cloud-api.
 * Zatím jen test: přihlášení, seznam zařízení a dotaz na hlavice hubu.
 */
class TapoCloud(private val user: String, private val pass: String) {
    private class App(val host: String, val type: String, val access: String, val secret: String)

    private val kasa = App("https://n-wap.tplinkcloud.com", "Kasa_Android_Mix",
        "e37525375f8845999bcc56d5e6faa76d", "314bc6700b3140ca80bc655e527cb062")
    private val tapo = App("https://n-wap.i.tplinkcloud.com", "TP-Link_Tapo_Android",
        "4d11b6b9d5ea4d19a829adbb9714b057", "6ed7d97f3e73467f8a5bab90b577ba4c")
    private val termId = UUID.nameUUIDFromBytes(("panel-" + user).toByteArray()).toString()

    /** Projde celý postup a vrátí čitelný záznam pro displej. */
    fun test(): String {
        val log = StringBuilder()
        for (app in listOf(kasa, tapo)) {
            val name = if (app === kasa) "Kasa" else "Tapo"
            log.append("— cloud $name —\n")
            try {
                val status = post(app, app.host, "/api/v2/account/getAccountStatusAndUrl", null,
                    JSONObject().put("appType", app.type).put("cloudUserName", user))
                val regional = status.optJSONObject("result")?.optString("appServerUrl")?.takeIf { it.startsWith("http") } ?: app.host
                log.append("server: $regional\n")
                val login = post(app, regional, "/api/v2/account/login", null, JSONObject()
                    .put("appType", app.type).put("appVersion", "3.4.451").put("cloudPassword", pass)
                    .put("cloudUserName", user).put("platform", "Android").put("refreshTokenNeeded", true)
                    .put("supportBindAccount", false).put("terminalUUID", termId)
                    .put("terminalName", "Pixel").put("terminalMeta", "Pixel"))
                val code = login.optInt("error_code")
                if (code == -20677) { log.append("vyžaduje ověřovací kód (MFA) — zatím neumím\n"); continue }
                if (code != 0) { log.append("přihlášení selhalo: $code ${login.optString("msg")}\n"); continue }
                val token = login.getJSONObject("result").getString("token")
                log.append("přihlášeno ✓\n")

                val list = post(app, regional, "/", token, JSONObject().put("method", "getDeviceList"))
                val devices = list.optJSONObject("result")?.optJSONArray("deviceList") ?: JSONArray()
                log.append("zařízení: ${devices.length()}\n")
                var hub: JSONObject? = null
                for (i in 0 until devices.length()) {
                    val d = devices.getJSONObject(i)
                    val model = d.optString("deviceModel")
                    val alias = d.optString("alias").let { a ->
                        try { String(Base64.decode(a, Base64.DEFAULT), Charsets.UTF_8).takeIf { it.isNotBlank() && it.all { c -> !c.isISOControl() } } ?: a } catch (e: Exception) { a }
                    }
                    log.append("  • $model „$alias“ ${d.optString("deviceType")} ${if (d.optInt("status") == 1) "online" else "offline"}\n")
                    if (hub == null && (model.contains("H100") || model.contains("KH100") || d.optString("deviceType").contains("HUB", true))) hub = d
                }
                val h = hub ?: continue
                val server = h.optString("appServerUrl").takeIf { it.startsWith("http") } ?: regional
                val req = JSONObject().put("method", "get_child_device_list").put("params", JSONObject().put("start_index", 0))
                val resp = if (app === kasa) {
                    post(app, server, "/", token, JSONObject().put("method", "passthrough")
                        .put("params", JSONObject().put("deviceId", h.getString("deviceId")).put("requestData", req.toString())))
                } else {
                    post(app, server, "/api/v2/common/passthrough", token,
                        JSONObject().put("deviceId", h.getString("deviceId")).put("requestData", req.toString()))
                }
                val rc = resp.optInt("error_code")
                if (rc != 0) {
                    log.append("starý kanál: $rc ${resp.optString("msg")}\n")
                    iotProbe(token, h.getString("deviceId"), log)
                    continue
                }
                val data = resp.optJSONObject("result")?.opt("responseData")
                val inner = when (data) {
                    is JSONObject -> data
                    is String -> try { JSONObject(data) } catch (e: Exception) { null }
                    else -> null
                }
                val kids = inner?.optJSONObject("result")?.optJSONArray("child_device_list")
                if (kids == null) {
                    log.append("hub odpověděl, ale bez hlavic: ${data.toString().take(300)}\n")
                } else {
                    log.append("HLAVICE PŘES CLOUD ✓ (${kids.length()})\n")
                    for (i in 0 until kids.length()) {
                        val k = kids.getJSONObject(i)
                        val n = try { String(Base64.decode(k.optString("nickname"), Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { "?" }
                        log.append("  • $n: ${k.opt("current_temp")} °C → ${k.opt("target_temp")} °C\n")
                    }
                    return log.toString()
                }
            } catch (e: Exception) {
                log.append("chyba: ${e.message ?: e.javaClass.simpleName}\n")
            }
        }
        return log.toString()
    }

    /**
     * Novější kanál aplikací („IoT“, *.iot.i.tplinknbu.com): stav zařízení jako
     * „shadow“ (reported/desired). Zatím jen průzkum — vypíše, co server vrátí.
     */
    private fun iotProbe(token: String, deviceId: String, log: StringBuilder) {
        try {
            val info = iotGet("https://app-server.iot.i.tplinknbu.com/v1/server-info", null)
            val srv = info.second.let { try { JSONObject(it).optString("appServerUrl") } catch (e: Exception) { "" } }
                .takeIf { it.startsWith("http") } ?: "https://euw1-app-server.iot.i.tplinknbu.com"
            log.append("IoT server: $srv\n")
            for (path in listOf(
                "/v1/things/shadows?thingNames=" + URLEncoder.encode(deviceId, "UTF-8"),
                "/v1/things/$deviceId",
                "/v1/things?page=0&pageSize=20",
            )) {
                val (code, body) = iotGet(srv + path, token)
                log.append("GET ${path.substringBefore('?')} → $code\n  ${body.replace(Regex("\\s+"), " ").take(500)}\n")
            }
        } catch (e: Exception) {
            log.append("IoT chyba: ${e.message ?: e.javaClass.simpleName}\n")
        }
    }

    private fun iotGet(url: String, token: String?): Pair<Int, String> {
        fun once(pinned: Boolean): Pair<Int, String> {
            val c = URL(url).openConnection() as HttpURLConnection
            if (pinned && c is javax.net.ssl.HttpsURLConnection) c.sslSocketFactory = tplinkSsl
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("app-cid", "app:x:x")
            c.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel Build/UP1A)")
            if (token != null) c.setRequestProperty("Authorization", "ut|$token")
            try {
                val code = c.responseCode
                val t = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                return code to t
            } finally { c.disconnect() }
        }
        return try { once(true) } catch (e: javax.net.ssl.SSLException) { once(false) }
    }

    // ------------------------------------------------------------------

    companion object {
        /** TLS jen s certifikačními autoritami TP-Link (cloud TP-Link nepoužívá veřejné CA). */
        val tplinkSsl: javax.net.ssl.SSLSocketFactory by lazy {
            val app = cz.stihnuto.panel.App.instance
            val id = app.resources.getIdentifier("tplink_ca", "raw", app.packageName)
            val cf = java.security.cert.CertificateFactory.getInstance("X.509")
            val ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply { load(null, null) }
            app.resources.openRawResource(id).use { input ->
                cf.generateCertificates(input).forEachIndexed { i, cert -> ks.setCertificateEntry("tplink$i", cert) }
            }
            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(ks) }
            javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
        }
    }

    private fun post(app: App, base: String, path: String, token: String?, body: JSONObject): JSONObject {
        val json = body.toString()
        val q = linkedMapOf(
            "appName" to app.type, "appVer" to "3.4.451", "netType" to "wifi", "termID" to termId,
            "ospf" to "Android 14", "brand" to "TPLINK", "locale" to "en_US", "model" to "Pixel",
            "termName" to "Pixel", "termMeta" to "Pixel",
        )
        if (token != null) q["token"] = token
        val query = q.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val md5 = Base64.encodeToString(MessageDigest.getInstance("MD5").digest(json.toByteArray()), Base64.NO_WRAP)
        val nonce = UUID.randomUUID().toString()
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(app.secret.toByteArray(), "HmacSHA1")) }
        val sig = mac.doFinal("$md5\n9999999999\n$nonce\n$path".toByteArray()).joinToString("") { "%02x".format(it) }

        val c = URL(base.trimEnd('/') + path + "?" + query).openConnection() as HttpURLConnection
        if (c is javax.net.ssl.HttpsURLConnection) c.sslSocketFactory = tplinkSsl
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
        c.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel Build/UP1A)")
        c.setRequestProperty("Content-MD5", md5)
        c.setRequestProperty("X-Authorization", "Timestamp=9999999999, Nonce=$nonce, AccessKey=${app.access}, Signature=$sig")
        try {
            c.outputStream.use { it.write(json.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            if (code !in 200..299) return JSONObject().put("error_code", code).put("msg", "HTTP $code ${text.take(120)}")
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }
}
