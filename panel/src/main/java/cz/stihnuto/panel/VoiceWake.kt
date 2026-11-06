package cz.stihnuto.panel

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.text.Normalizer
import java.util.zip.ZipInputStream

/**
 * Hlasová aktivace (aktivační slovo z nastavení): hodiny pořád poslouchají (offline, Vosk),
 * po aktivační frázi vezmou povel a pošlou ho Claudovi. Ven se posílá jen
 * samotný povel, nikdy nic jiného.
 */
object VoiceWake {
    private const val MODEL_URLS =
        "https://huggingface.co/rhasspy/vosk-models/resolve/main/cs/vosk-model-small-cs-0.4-rhasspy.zip " +
            "https://alphacephei.com/vosk/models/vosk-model-small-cs-0.4-rhasspy.zip"

    @Volatile var status: String = ""
        private set
    /** Co hodiny naposledy rozpoznaly (diagnostika). */
    @Volatile var lastHeard: String = ""
        private set
    @Volatile var lastHeardAt = 0L
        private set

    /** Posledních pár kroků hlasového ovládání (diagnostika na displeji). */
    private val events = java.util.ArrayDeque<String>()
    fun log(msg: String) = synchronized(events) {
        val t = java.time.LocalTime.now().withNano(0)
        events.addFirst("$t $msg")
        while (events.size > 6) events.removeLast()
    }
    fun events(): List<String> = synchronized(events) { events.toList() }

    /** Okno Claude se přihlásí, aby povel zobrazilo a odpověď přečetlo nahlas. */
    var onCommand: ((String) -> Unit)? = null
    /** Volá se, když zazní aktivační fráze (zobrazit „Poslouchám…“). */
    var onWake: (() -> Unit)? = null
    /** Hotová odpověď z režimu, kde Gemini odpovídá rovnou z nahrávky (zobrazit a přečíst). */
    var onAnswer: ((String) -> Unit)? = null

    private var model: Model? = null
    @Volatile private var worker: Thread? = null
    /** Hlasitost mikrofonu (RMS) za poslední chvíli — diagnostika. */
    @Volatile var level = 0
        private set
    private var awaitingCommand = false
    private var wakeAt = 0L
    private var paused = false
    private var lastPartial = ""
    private var partialAt = 0L
    /** Jak dlouho ještě brát zvuk jako řeč (doznívání po poslední hlasité části). */
    private var speechHold = 0

    private fun modelDir(app: App) = File(app.filesDir, "vosk-cs")
    fun modelReady(app: App) = File(modelDir(app), "am").exists() || modelDir(app).listFiles()?.any { File(it, "am").exists() } == true
    val running get() = worker != null

    /** Spustí poslouchání (podle nastavení), případně nejdřív stáhne model. */
    fun start(app: App) {
        if (!app.settings.voiceWake || worker != null) return
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Chybí oprávnění k mikrofonu"
            return
        }
        val t = Thread({ run(app) }, "voice-wake").apply { isDaemon = true }
        worker = t
        t.start()
    }

    /** Vlastní smyčka: mikrofon (zvolený vstup) → Vosk. */
    private fun run(app: App) {
        var rec: AudioRecord? = null
        var fx: List<android.media.audiofx.AudioEffect> = emptyList()
        try {
            val mode = app.settings.voiceMode
            if (mode != "vosk" && app.settings.geminiKey.isBlank()) throw IllegalStateException("tenhle režim potřebuje klíč ke Gemini (okno Aplikace → Asistent)")
            geminiGate = mode == "gemini"
            // „free" = bez fráze (vše ke Gemini); „vosk" i „gemini" hlídají slovo offline Voskem
            val recognizer = if (mode == "free") null else {
                if (!modelReady(app)) download(app)
                status = "Načítám model…"
                Recognizer(model ?: Model(modelPath(app)).also { model = it }, 16000f)
            }
            val src = app.settings.micSource
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(src, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16000))
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("mikrofon (vstup $src) nejde otevřít")
            rec.startRecording()
            fx = attachEffects(rec.audioSessionId)
            status = when (mode) {
                "free" -> "Poslouchám vše, Gemini pozná, kdy mluvíš na mě (vstup ${MicProbe.name(src)})"
                "gemini" -> "Poslouchám na „${app.settings.wakePhrase}“ přes Gemini (vstup ${MicProbe.name(src)})"
                else -> "Poslouchám na „${app.settings.wakePhrase}“ (vstup ${MicProbe.name(src)})"
            }
            val buf = ShortArray(1600) // 0,1 s
            var sum = 0.0; var count = 0
            while (worker === Thread.currentThread()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                count += n
                if (count >= 8000) { level = Math.sqrt(sum / count).toInt(); sum = 0.0; count = 0 }
                detectSpeech(buf, n)
                val raw = buf.copyOf(n) // čistý zvuk pro nahrávky (Gemini)
                if (geminiGate && !capturingGemini) {
                    preroll.addLast(raw); while (preroll.size > 8) preroll.removeFirst()
                }
                capture?.let { if (it.feed(raw, n)) { capture = null; it.finish() } }
                wakeCap?.let { if (it.feed(raw, n)) { capturingGemini = false; resetRecog = true; wakeCap = null; it.finish() } }
                if (paused) { autoCap = null; continue }
                if (recognizer == null) { listenGemini(app, mode, raw, n); continue }
                if (capturingGemini) continue // nahráváme povel pro Gemini, Vosk přeskoč
                if (resetRecog) { recognizer.reset(); resetRecog = false; lastPartial = "" }
                amplifyBuf(buf, n) // zesílit jen pro Vosk
                if (recognizer.acceptWaveForm(buf, n)) {
                    handle(app, JSONObject(recognizer.result).optString("text"))
                    lastPartial = ""
                } else {
                    val p = JSONObject(recognizer.partialResult).optString("partial")
                    val now = System.currentTimeMillis()
                    if (p != lastPartial) { lastPartial = p; partialAt = now }
                    partial(app, p)
                    // Vosk někdy větu neukončí (šum) — po 1,2 s beze změny ber jako hotové (jen offline režim)
                    if (!geminiGate && p.isNotBlank() && now - partialAt > 1200 &&
                        (awaitingCommand || wakeRegex(app).containsMatchIn(norm(p)))
                    ) {
                        handle(app, JSONObject(recognizer.finalResult).optString("text").ifBlank { p })
                        recognizer.reset()
                        lastPartial = ""
                    }
                }
            }
            recognizer?.close()
        } catch (e: Throwable) {
            Log.w("Panel", "voice wake", e)
            status = "Hlasová aktivace nejde spustit: ${e.message ?: e.javaClass.simpleName}"
            if (worker === Thread.currentThread()) worker = null
        } finally {
            geminiGate = false; capturingGemini = false; wakeCap = null
            fx.forEach { try { it.release() } catch (_: Exception) {} }
            try { rec?.stop(); rec?.release() } catch (_: Exception) {}
        }
    }

    // --- A) automatické zesílení: tichý mikrofon hodin → úroveň, se kterou rozpoznávání pracuje
    private var gain = 10.0
    /** Úroveň šumu v místnosti (bez zesílení) — sleduje se průběžně. */
    private var noise = 25.0
    /** Byl poslední úsek řeč? (hlasitější než šum v místnosti) */
    @Volatile private var speech = false

    /** Změří hlasitost, pozná řeč a upraví zesílení — zvuk nemění (Gemini dostane čistý). */
    private fun detectSpeech(buf: ShortArray, n: Int) {
        var s = 0.0
        for (i in 0 until n) s += buf[i].toDouble() * buf[i]
        val rms = Math.sqrt(s / n)
        speech = rms > maxOf(noise * 1.6, 15.0)
        if (speech) {
            val target = (1800.0 / rms).coerceIn(1.0, 10.0)
            gain = gain * 0.7 + target * 0.3
        } else {
            noise = (noise * 0.97 + rms * 0.03).coerceAtMost(70.0)
        }
        if (speech) speechHold = 4 else if (speechHold > 0) speechHold--
    }

    /** Zesílí zvuk na místě — jen pro Vosk. Gemini dostává čistý zvuk (zesílí se až normalizací). */
    private fun amplifyBuf(buf: ShortArray, n: Int) {
        val g = if (speech || speechHold > 0) gain else minOf(gain, 3.0)
        for (i in 0 until n) buf[i] = (buf[i] * g).toInt().coerceIn(-32768, 32767).toShort()
    }

    private class Capture(val maxMs: Int = 9000, val done: (ShortArray?) -> Unit) {
        val out = java.io.ByteArrayOutputStream()
        var started = false
        var quietMs = 0
        var totalMs = 0
        var speechMs = 0
        fun feed(buf: ShortArray, n: Int): Boolean {
            val loud = speech
            val bb = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) bb.putShort(buf[i])
            out.write(bb.array())
            val ms = n * 1000 / 16000
            totalMs += ms
            if (loud) { started = true; quietMs = 0; speechMs += ms } else quietMs += ms
            return (started && quietMs > END_SILENCE_MS) || totalMs > maxMs || (!started && totalMs > 5000)
        }
        fun finish() {
            val bytes = out.toByteArray()
            val pcm = if (bytes.isNotEmpty()) java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                .let { sb -> ShortArray(sb.remaining()).also { sb.get(it) } } else null
            if (pcm != null) lastPcm = pcm
            done(pcm)
        }
    }
    @Volatile private var capture: Capture? = null
    /** Poslední nahrávka (to, co dostal Gemini) — pro tlačítko „Přehrát". */
    @Volatile var lastPcm: ShortArray? = null
        private set

    /** Přehraje poslední nahrávku nahlas, ať je slyšet, co mikrofon zachytil. */
    fun playLast(app: App) {
        val src = lastPcm ?: return
        Thread({
            val wasPaused = paused
            pause(true)
            var track: android.media.AudioTrack? = null
            try {
                val pcm = src.copyOf()
                var peak = 1
                for (v in pcm) { val a = Math.abs(v.toInt()); if (a > peak) peak = a }
                val g = (24000.0 / peak).coerceIn(1.0, 20.0)
                for (i in pcm.indices) pcm[i] = (pcm[i] * g).toInt().coerceIn(-32768, 32767).toShort()
                val min = android.media.AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                track = android.media.AudioTrack(AudioManager.STREAM_MUSIC, 16000, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, pcm.size * 2), android.media.AudioTrack.MODE_STATIC)
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((pcm.size * 1000L / 16000) + 300)
            } catch (e: Exception) {
                Log.w("Panel", "playLast", e)
            } finally {
                try { track?.stop(); track?.release() } catch (_: Exception) {}
                pause(wasPaused)
            }
        }, "play-rec").apply { isDaemon = true; start() }
    }

    /** Ticho, po kterém je věta u konce (ms). */
    const val END_SILENCE_MS = 3000

    // --- režimy přes Gemini: hodiny samy poznají začátek řeči, větu nahrají a pošlou na přepis
    private var autoCap: Capture? = null
    private val preroll = java.util.ArrayDeque<ShortArray>()
    @Volatile private var gemBusy = false
    // režim „Fráze + Gemini": Vosk zdarma pohlídá slovo, povel pak nahraje a pošle Gemini
    @Volatile private var geminiGate = false
    private var wakeCap: Capture? = null
    @Volatile private var capturingGemini = false
    @Volatile private var resetRecog = false
    /** Chvíli po odpovědi bere i navazující větu bez fráze. */
    @Volatile private var followUpUntil = 0L

    fun followUp() { followUpUntil = System.currentTimeMillis() + 10_000 }

    private fun listenGemini(app: App, mode: String, buf: ShortArray, n: Int) {
        val chunk = buf.copyOf(n)
        val cap = autoCap
        if (cap == null) {
            preroll.addLast(chunk)
            while (preroll.size > 4) preroll.removeFirst()
            if (!speech || gemBusy) return
            // začátek řeči: vezmi i kousek před ním, ať se neusekne první slovo
            val c = Capture(15_000) { pcm -> if (pcm != null) sendGemini(app, mode, pcm) }
            preroll.forEach { c.feed(it, it.size) }
            preroll.clear()
            autoCap = c
            return
        }
        if (cap.feed(chunk, chunk.size)) {
            autoCap = null
            // krátké zvuky (bouchnutí, kašel) neposílat
            if (cap.speechMs >= 400) cap.finish()
        }
    }

    private fun sendGemini(app: App, mode: String, pcm: ShortArray) {
        gemBusy = true
        val follow = System.currentTimeMillis() < followUpUntil
        app.io.execute {
            try {
                if (app.settings.aiProvider == "gemini") {
                    // Gemini odpovídá: jedno volání z nahrávky rovnou rozhodne, jedná a odpoví
                    val reply = cz.stihnuto.panel.pages.ClaudePage.assistant(app)
                        .answerAudio(pcm, if (mode == "free") "free" else "phrase")
                    if (reply.isNotBlank()) {
                        followUpUntil = 0
                        lastHeard = reply; lastHeardAt = System.currentTimeMillis()
                        log("odpověď: ${reply.take(60)}")
                        onAnswer?.invoke(reply)
                    } else log("→ nemluví na mě, ignoruji")
                } else {
                    // Claude odpovídá: Gemini nejdřív přepíše a rozhodne, pak odpoví Claude
                    val r = Transcribe.listen(app, pcm, mode == "free", follow)
                    if (r.text.isNotBlank()) { lastHeard = r.text; lastHeardAt = System.currentTimeMillis() }
                    log("věta: „${r.text}“")
                    if (r.forMe && r.command.isNotBlank()) {
                        log("→ povel: „${r.command}“")
                        followUpUntil = 0
                        val target = onCommand
                        if (target != null) target(r.command)
                        else cz.stihnuto.panel.pages.ClaudePage.assistant(app).ask(r.command) { log("odpověď: ${it.take(60)}") }
                    } else if (r.text.isNotBlank()) log("→ nemluví na mě, ignoruji")
                }
            } catch (e: Exception) {
                log("Gemini: ${e.message}")
            } finally {
                gemBusy = false
            }
        }
    }

    /** Vosk slyšel aktivační slovo → nahraj zbytek věty a pošli ji Gemini (režim „Fráze + Gemini"). */
    private fun startGeminiWake(app: App) {
        capturingGemini = true
        onWake?.invoke()
        log("Vosk slyšel slovo → nahrávám pro Gemini")
        val c = Capture(12_000) { pcm ->
            if (pcm != null) {
                gemBusy = true
                app.io.execute {
                    try {
                        val reply = cz.stihnuto.panel.pages.ClaudePage.assistant(app).answerAudio(pcm, "direct")
                        if (reply.isNotBlank()) {
                            lastHeard = reply; lastHeardAt = System.currentTimeMillis()
                            log("odpověď: ${reply.take(60)}")
                            onAnswer?.invoke(reply)
                        } else log("Gemini nic nevrátil")
                    } catch (e: Exception) {
                        log("Gemini: ${e.message}")
                    } finally { gemBusy = false }
                }
            }
        }
        preroll.forEach { c.feed(it, it.size) }
        preroll.clear()
        wakeCap = c
    }

    /** Nahraje jednu větu z mikrofonu (i když hlasová aktivace neběží). null = nic neřekl. */
    fun recordUtterance(app: App, done: (ShortArray?) -> Unit) {
        duck(app, true) // ztlumit hrající hudbu, ať je přes ni slyšet
        val wrapped: (ShortArray?) -> Unit = { pcm -> duck(app, false); done(pcm) }
        val c = Capture(done = wrapped)
        if (worker != null) { capture = c; return }
        Thread({
            var rec: AudioRecord? = null
            var fx: List<android.media.audiofx.AudioEffect> = emptyList()
            try {
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(app.settings.micSource, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16000))
                rec.startRecording()
                fx = attachEffects(rec.audioSessionId)
                val buf = ShortArray(1600)
                while (true) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    detectSpeech(buf, n)
                    if (c.feed(buf, n)) break
                }
                c.finish()
            } catch (e: Exception) {
                Log.w("Panel", "record", e)
                wrapped(null)
            } finally {
                fx.forEach { try { it.release() } catch (_: Exception) {} }
                try { rec?.stop(); rec?.release() } catch (_: Exception) {}
            }
        }, "record").apply { isDaemon = true; start() }
    }

    /** Zapne potlačení ozvěny (vlastní reproduktor hodin) a šumu na nahrávání. */
    private fun attachEffects(sessionId: Int): List<android.media.audiofx.AudioEffect> {
        // Potlačení ozvěny/šumu na tomhle mikrofonu spíš škodilo (vynulovalo zvuk) — vypnuto.
        return emptyList()
    }

    private var focusReq: AudioFocusRequest? = null

    /** Na dobu poslechu ztlumí ostatní zvuk (hudbu), ať je přes něj hlas slyšet. */
    private fun duck(app: App, on: Boolean) {
        val am = app.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        try {
            if (on) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .build()
                focusReq = req
                am.requestAudioFocus(req)
            } else {
                focusReq?.let { am.abandonAudioFocusRequest(it) }
                focusReq = null
            }
        } catch (e: Exception) { Log.w("Panel", "duck", e) }
    }

    fun stop() {
        worker = null
        awaitingCommand = false
        status = "Vypnuto"
    }

    /** Ztlumit poslouchání (když hodiny mluví nebo poslouchá jiné rozpoznávání). */
    fun pause(p: Boolean) {
        paused = p
    }

    private fun modelPath(app: App): String {
        val dir = modelDir(app)
        if (File(dir, "am").exists()) return dir.path
        return dir.listFiles()?.firstOrNull { File(it, "am").exists() }?.path ?: dir.path
    }

    private fun download(app: App) {
        val dir = modelDir(app)
        dir.deleteRecursively()
        dir.mkdirs()
        var lastError: Exception? = null
        for (url in MODEL_URLS.split(' ')) {
            try {
                status = "Stahuji český model (asi 46 MB)…"
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 60000
                c.instanceFollowRedirects = true
                if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                var read = 0L
                ZipInputStream(c.inputStream.buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val out = File(dir, e.name)
                        if (!out.canonicalPath.startsWith(dir.canonicalPath)) continue
                        if (e.isDirectory) { out.mkdirs(); continue }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                read += n
                                if (total > 0) status = "Stahuji český model… ${(100 * read / (total * 2)).coerceAtMost(99)} %"
                            }
                        }
                    }
                }
                c.disconnect()
                if (modelReady(app)) return
                throw java.io.IOException("v archivu chybí model")
            } catch (e: Exception) {
                lastError = e
                dir.deleteRecursively(); dir.mkdirs()
            }
        }
        throw lastError ?: java.io.IOException("model nejde stáhnout")
    }

    // ------------------------------------------------------------------

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Aktivační slovo z nastavení; shoda na začátek slov (rozpoznávání komolí konce). */
    private fun wakeRegex(app: App): Regex {
        val words = norm(app.settings.wakePhrase).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return Regex("$^")
        val parts = words.map { w -> Regex.escape(w.take(maxOf(4, w.length - 1))) + "[a-z]*" }
        return Regex("\\b" + parts.joinToString("\\s+(?:[a-z]+\\s+)?") + "\\b")
    }

    private fun partial(app: App, rawIn: String) {
        val raw = rawIn.replace("[unk]", " ").replace(Regex("\\s+"), " ").trim()
        if (raw.isNotBlank()) { lastHeard = raw; lastHeardAt = System.currentTimeMillis() }
        if (!awaitingCommand && !capturingGemini && wakeRegex(app).containsMatchIn(norm(raw))) {
            if (geminiGate) startGeminiWake(app) else wake()
        }
    }

    /** Klepnutí na mikrofon: další věta je povel (bez aktivačního slova). */
    fun captureCommand(): Boolean {
        if (worker == null) return false
        pause(false)
        wake()
        return true
    }

    private fun wake() {
        log("fráze zachycena")
        awaitingCommand = true
        wakeAt = System.currentTimeMillis()
        onWake?.invoke()
    }

    private fun handle(app: App, raw: String) {
        if (geminiGate) return // povel řeší Gemini z nahrávky
        val text = raw.replace("[unk]", " ").replace(Regex("\\s+"), " ").trim()
        if (text.isNotEmpty()) log("věta: „$text“")
        if (text.isNotEmpty()) { lastHeard = text; lastHeardAt = System.currentTimeMillis() }
        if (awaitingCommand && System.currentTimeMillis() - wakeAt > 10_000) awaitingCommand = false
        val n = norm(text)
        val m = wakeRegex(app).find(n)
        val command: String? = when {
            m != null -> {
                // povel může být hned za aktivačním slovem: „prosím, zatop v obýváku“
                val words = text.split(Regex("\\s+"))
                val skip = norm(n.substring(0, m.range.last + 1)).split(' ').size
                words.drop(skip).joinToString(" ").trim().ifEmpty { null }.also { if (it == null) wake() }
            }
            awaitingCommand && text.isNotEmpty() -> text
            else -> null
        }
        if (text.isNotEmpty()) log(when {
            command != null -> "→ povel: „$command“"
            m != null -> "→ fráze bez povelu, čekám na povel"
            awaitingCommand -> "→ ?"
            else -> "→ bez fráze „${app.settings.wakePhrase}“, ignoruji"
        })
        if (command != null) {
            awaitingCommand = false
            Log.i("Panel", "povel: $command")
            val target = onCommand
            if (target != null) target(command) else {
                log("okno asistenta není — ptám se na pozadí")
                cz.stihnuto.panel.pages.ClaudePage.assistant(app).ask(command) { log("odpověď: ${it.take(60)}") }
            }
        }
    }
}
