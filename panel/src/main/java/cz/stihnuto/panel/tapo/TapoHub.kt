package cz.stihnuto.panel.tapo

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Jedno zařízení připojené k hubu H100. */
class TapoChild(
    val id: String,
    val name: String,
    val category: String,
    val online: Boolean,
    val currentTemp: Double?,
    val targetTemp: Double?,
    val minTemp: Double,
    val maxTemp: Double,
    /** Hlavice zapnutá (vypnutá = jen ochrana proti zamrznutí). */
    val on: Boolean,
    val heating: Boolean,
    val humidity: Int?,
    val batteryLow: Boolean,
) {
    val isValve get() = category == "subg.trv"
    val isThermometer get() = category == "subg.trigger.temp-hmdt-sensor"
}

class TapoException(msg: String, val code: Int = 0) : IOException(msg)

/**
 * Lokální ovládání hubu Tapo H100 (bez cloudu).
 *
 * Tapo zařízení používají dva šifrované protokoly — novější „KLAP" a starší
 * „AES" (výměna RSA klíčů). Zkusí se KLAP a když ho hub nezná, AES.
 * Postup odpovídá knihovně python-kasa.
 *
 * Bezpečnost: u KLAP se heslo nikam neposílá (obě strany jen prokážou, že ho znají),
 * kdežto starší AES ho pošle tomu, kdo na adrese odpoví. Proto:
 *  - adresa hubu musí být IP v domácí síti (údaje nejde přesměrovat ven),
 *  - jakmile hub jednou KLAP uměl ([klapOnly]), na AES se už nikdy nepřejde.
 */
class TapoHub(
    private val ip: String,
    private val user: String,
    private val pass: String,
    klapOnly: Boolean = false,
    /** Zavolá se po prvním ověřeném spojení přes KLAP (uložit, že hub KLAP umí). */
    private val onKlap: () -> Unit = {},
) {
    @Volatile private var klapOnly = klapOnly
    /** Poslední spojení KLAP se ověřilo skutečným e-mailem a heslem (ne prázdnými údaji). */
    private var klapVerified = false
    private var session: Session? = null
    private val terminalUuid = b64(md5(UUID.randomUUID().toString().toByteArray()))

    @Synchronized
    fun request(method: String, params: JSONObject? = null): JSONObject {
        val body = JSONObject()
            .put("method", method)
            .put("request_time_milis", System.currentTimeMillis())
            .put("terminal_uuid", terminalUuid)
        if (params != null) body.put("params", params)
        var lastError: Exception? = null
        repeat(2) {
            val s = session?.takeIf { it.valid() } ?: connect().also { session = it }
            try {
                val resp = s.send(body.toString())
                val code = resp.optInt("error_code", 0)
                if (code == 0) return resp.optJSONObject("result") ?: JSONObject()
                if (code == 9999 || code == -1301 || code == 1002) { // sezení vypršelo
                    session = null
                    lastError = TapoException("sezení vypršelo", code)
                    return@repeat
                }
                throw TapoException("Hub vrátil chybu $code", code)
            } catch (e: SessionExpired) {
                session = null
                lastError = e
            }
        }
        throw lastError ?: TapoException("neznámá chyba")
    }

    fun children(): List<TapoChild> {
        val out = mutableListOf<TapoChild>()
        var start = 0
        while (true) {
            val r = request("get_child_device_list", JSONObject().put("start_index", start))
            val list = r.optJSONArray("child_device_list") ?: JSONArray()
            for (i in 0 until list.length()) out += parseChild(list.getJSONObject(i))
            start += list.length()
            if (list.length() == 0 || start >= r.optInt("sum", 0)) break
        }
        return out
    }

    fun setChild(deviceId: String, params: JSONObject) {
        val wrapped = JSONObject()
            .put("device_id", deviceId)
            .put("requestData", JSONObject().put("method", "set_device_info").put("params", params))
        val r = request("control_child", wrapped)
        val data = r.optJSONObject("responseData")
        val code = data?.optInt("error_code", 0) ?: 0
        if (code != 0) throw TapoException("Zařízení vrátilo chybu $code", code)
    }

    fun setTarget(deviceId: String, temp: Double) =
        setChild(deviceId, JSONObject().put("target_temp", temp).put("frost_protection_on", false))

    fun setOn(deviceId: String, on: Boolean) =
        setChild(deviceId, JSONObject().put("frost_protection_on", !on))

    private fun parseChild(j: JSONObject): TapoChild {
        val nick = j.optString("nickname", "")
        val name = try {
            String(Base64.decode(nick, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            nick
        }.ifBlank { j.optString("model", "Zařízení") }
        val states = j.optJSONArray("trv_states")
        val heating = states != null && (0 until states.length()).any { states.optString(it) == "heating" }
        fun d(key: String) = if (j.has(key) && !j.isNull(key)) j.optDouble(key) else null
        return TapoChild(
            id = j.optString("device_id"),
            name = name,
            category = j.optString("category"),
            online = j.optString("status", "online") == "online",
            currentTemp = d("current_temp"),
            targetTemp = d("target_temp"),
            minTemp = d("min_control_temp") ?: 5.0,
            maxTemp = d("max_control_temp") ?: 30.0,
            on = !j.optBoolean("frost_protection_on", false),
            heating = heating,
            humidity = if (j.has("current_humidity")) j.optInt("current_humidity") else null,
            batteryLow = j.optBoolean("at_low_battery", false),
        )
    }

    // ------------------------------------------------------------------
    // Spojení
    // ------------------------------------------------------------------

    private interface Session {
        fun valid(): Boolean
        fun send(json: String): JSONObject
    }

    private class SessionExpired : IOException("sezení vypršelo")

    private fun connect(): Session {
        if (ip.isBlank()) throw TapoException("Není nastavená IP adresa hubu")
        // Hub je vždy v domácí síti. Jméno serveru nebo veřejná adresa se odmítne,
        // aby přihlašovací údaje nešlo přesměrovat někam ven.
        if (!isHomeAddress(ip)) throw TapoException("Adresa hubu musí být IP adresa v domácí síti (např. 10.0.0.139)")
        if (user.isBlank() || pass.isBlank()) throw TapoException("Chybí e-mail nebo heslo k účtu Tapo")
        try {
            return klap().also { if (klapVerified && !klapOnly) { klapOnly = true; onKlap() } }
        } catch (e: NotKlap) {
            // Hub, který KLAP jednou uměl, ho nepřestane umět — tohle je cizí zařízení na jeho adrese.
            if (klapOnly) throw TapoException(
                "Na adrese hubu neodpovídá hub (chybí zabezpečený protokol). Zkontroluj IP adresu; " +
                    "po výměně hubu se přihlas znovu přímo na hodinách."
            )
            // starší firmware, který KLAP nikdy neuměl — zkusí se AES
        }
        return aes() ?: throw TapoException("Hub nepodporuje známý protokol")
    }

    private class NotKlap : Exception()

    // --- KLAP -------------------------------------------------------------

    private fun klap(): Session {
        val local = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val r1 = post("http://$ip/app/handshake1", local, null)
        if (r1.code == 404 || r1.code == 405 || r1.body.size != 48) throw NotKlap()
        if (r1.code != 200) throw TapoException("handshake1: HTTP ${r1.code}")
        val remote = r1.body.copyOfRange(0, 16)
        val serverHash = r1.body.copyOfRange(16, 48)

        // Novější firmware (v2), starší (v1) a prázdné údaje (hub nepřipojený k účtu).
        // Prázdné údaje zná každý, takže by se jimi za hub mohlo vydávat libovolné zařízení
        // v síti — zkouší se proto jen do chvíle, než se hub poprvé ověří skutečným heslem.
        val real = listOf(
            sha256(sha1(user.toByteArray()) + sha1(pass.toByteArray())) to 2,
            md5(md5(user.toByteArray()) + md5(pass.toByteArray())) to 1,
        )
        val blank = sha256(sha1(ByteArray(0)) + sha1(ByteArray(0))) to 2
        val candidates = if (klapOnly) real else real + blank
        val (auth, ver) = candidates.firstOrNull { (h, v) ->
            val expected = if (v == 2) sha256(local + remote + h) else sha256(local + h)
            expected.contentEquals(serverHash)
        }?.also { klapVerified = it !== blank } ?: throw TapoException(
            "Hub odmítl přihlášení — zkontroluj e-mail a heslo k Tapo účtu " +
                "a v aplikaci Tapo zapni Kompatibilitu s třetími stranami."
        )

        val cookie = r1.cookie
        val h2 = if (ver == 2) sha256(remote + local + auth) else sha256(remote + auth)
        val r2 = post("http://$ip/app/handshake2", h2, cookie)
        if (r2.code != 200) throw TapoException("handshake2: HTTP ${r2.code}")

        val key = sha256("lsk".toByteArray() + local + remote + auth).copyOf(16)
        val ivFull = sha256("iv".toByteArray() + local + remote + auth)
        val ivBase = ivFull.copyOf(12)
        var seq = java.nio.ByteBuffer.wrap(ivFull, 28, 4).int
        val sig = sha256("ldk".toByteArray() + local + remote + auth).copyOf(28)
        val expires = System.currentTimeMillis() + (r1.timeoutSec ?: 86400) * 1000L - 20 * 60 * 1000L

        return object : Session {
            override fun valid() = System.currentTimeMillis() < expires

            override fun send(json: String): JSONObject {
                seq += 1
                val seqBytes = java.nio.ByteBuffer.allocate(4).putInt(seq).array()
                val iv = ivBase + seqBytes
                val enc = aesCbc(Cipher.ENCRYPT_MODE, key, iv, json.toByteArray())
                val signature = sha256(sig + seqBytes + enc)
                val r = post("http://$ip/app/request?seq=$seq", signature + enc, cookie)
                if (r.code == 403) throw SessionExpired()
                if (r.code != 200) throw TapoException("HTTP ${r.code}")
                // 32 B podpis + aspoň jeden šifrovaný blok
                if (r.body.size < 48) throw TapoException("Hub poslal neplatnou odpověď")
                val plain = aesCbc(Cipher.DECRYPT_MODE, key, iv, r.body.copyOfRange(32, r.body.size))
                return JSONObject(String(plain, Charsets.UTF_8))
            }
        }
    }

    // --- AES (securePassthrough) ----------------------------------------

    private fun aes(): Session? {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
        val kp = kpg.generateKeyPair()
        val pem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP) + "\n-----END PUBLIC KEY-----\n"
        val hs = JSONObject().put("method", "handshake").put("params", JSONObject().put("key", pem))
        val r = post("http://$ip/app", hs.toString().toByteArray(), null, json = true)
        if (r.code != 200) return null
        val resp = JSONObject(String(r.body, Charsets.UTF_8))
        if (resp.optInt("error_code", 0) != 0) {
            throw TapoException("AES handshake: chyba ${resp.optInt("error_code")}")
        }
        val keyIv = rsaDecrypt(kp.private, Base64.decode(resp.getJSONObject("result").getString("key"), Base64.DEFAULT))
        val key = keyIv.copyOfRange(0, 16)
        val iv = keyIv.copyOfRange(16, 32)
        val cookie = r.cookie
        val expires = System.currentTimeMillis() + (r.timeoutSec ?: 86400) * 1000L - 20 * 60 * 1000L
        var url = "http://$ip/app"

        fun passthrough(json: String): JSONObject {
            val enc = Base64.encodeToString(aesCbc(Cipher.ENCRYPT_MODE, key, iv, json.toByteArray()), Base64.NO_WRAP)
            val body = JSONObject().put("method", "securePassthrough")
                .put("params", JSONObject().put("request", enc))
            val resp2 = post(url, body.toString().toByteArray(), cookie, json = true)
            if (resp2.code != 200) throw TapoException("HTTP ${resp2.code}")
            val outer = JSONObject(String(resp2.body, Charsets.UTF_8))
            val code = outer.optInt("error_code", 0)
            if (code == 9999 || code == -1301) throw SessionExpired()
            if (code != 0) throw TapoException("Hub vrátil chybu $code", code)
            val raw = outer.getJSONObject("result").getString("response")
            val plain = aesCbc(Cipher.DECRYPT_MODE, key, iv, Base64.decode(raw, Base64.DEFAULT))
            return JSONObject(String(plain, Charsets.UTF_8))
        }

        // přihlášení — nejdřív novější varianta hesla, pak starší
        val un = b64(hex(sha1(user.toByteArray())).toByteArray())
        val attempts = listOf(
            JSONObject().put("username", un).put("password2", b64(hex(sha1(pass.toByteArray())).toByteArray())),
            JSONObject().put("username", un).put("password", b64(pass.toByteArray())),
        )
        var token: String? = null
        for (params in attempts) {
            val login = JSONObject().put("method", "login_device").put("params", params)
                .put("request_time_milis", System.currentTimeMillis())
            val res = passthrough(login.toString())
            if (res.optInt("error_code", 0) == 0) {
                token = res.getJSONObject("result").getString("token")
                break
            }
        }
        if (token == null) {
            throw TapoException(
                "Hub odmítl přihlášení — zkontroluj e-mail a heslo k Tapo účtu " +
                    "a v aplikaci Tapo zapni Kompatibilitu s třetími stranami."
            )
        }
        url = "http://$ip/app?token=$token"

        return object : Session {
            override fun valid() = System.currentTimeMillis() < expires
            override fun send(json: String) = passthrough(json)
        }
    }

    // ------------------------------------------------------------------
    // Pomocné funkce
    // ------------------------------------------------------------------

    private class Resp(val code: Int, val body: ByteArray, val cookie: String?, val timeoutSec: Int?)

    private fun post(url: String, data: ByteArray, cookie: String?, json: Boolean = false): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 10000
        c.requestMethod = "POST"
        c.doOutput = true
        c.useCaches = false
        if (json) {
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("requestByApp", "true")
        } else {
            c.setRequestProperty("Content-Type", "application/octet-stream")
        }
        if (cookie != null) c.setRequestProperty("Cookie", cookie)
        try {
            c.outputStream.use { it.write(data) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            var session: String? = null
            var timeout: Int? = null
            c.headerFields["Set-Cookie"].orEmpty().plus(c.headerFields["set-cookie"].orEmpty())
                .flatMap { it.split(';') }.map { it.trim() }.forEach { part ->
                    val eq = part.indexOf('=')
                    if (eq <= 0) return@forEach
                    val k = part.substring(0, eq)
                    val v = part.substring(eq + 1)
                    when (k) {
                        "TP_SESSIONID", "SESSIONID" -> session = "TP_SESSIONID=$v"
                        "TIMEOUT" -> timeout = v.toIntOrNull()
                    }
                }
            return Resp(code, body, session ?: cookie, timeout)
        } finally {
            c.disconnect()
        }
    }

    private fun aesCbc(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    private fun rsaDecrypt(key: PrivateKey, data: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, key)
            doFinal(data)
        }

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b)
    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b)
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    companion object {
        /**
         * Je to IPv4 adresa z domácí sítě? (10.x, 172.16–31.x, 192.168.x, 169.254.x, 100.64–127.x)
         * Cokoli jiného — jméno serveru, veřejná adresa, adresa s portem či cestou — neprojde.
         */
        fun isHomeAddress(ip: String): Boolean {
            val m = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(ip) ?: return false
            val (a, b, c, d) = m.groupValues.drop(1).map { it.toInt() }
            if (a > 255 || b > 255 || c > 255 || d > 255) return false
            return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
    }
}
