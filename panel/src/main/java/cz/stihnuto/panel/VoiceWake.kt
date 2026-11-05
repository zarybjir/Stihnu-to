package cz.stihnuto.panel

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.text.Normalizer
import java.util.zip.ZipInputStream

/**
 * Hlasová aktivace „prosím Pepo“: hodiny pořád poslouchají (offline, Vosk),
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

    /** Okno Claude se přihlásí, aby povel zobrazilo a odpověď přečetlo nahlas. */
    var onCommand: ((String) -> Unit)? = null
    /** Volá se, když zazní aktivační fráze (zobrazit „Poslouchám…“). */
    var onWake: (() -> Unit)? = null

    private var model: Model? = null
    @Volatile private var worker: Thread? = null
    /** Hlasitost mikrofonu (RMS) za poslední chvíli — diagnostika. */
    @Volatile var level = 0
        private set
    private var awaitingCommand = false
    private var wakeAt = 0L
    private var paused = false

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
        try {
            if (!modelReady(app)) download(app)
            status = "Načítám model…"
            val m = model ?: Model(modelPath(app)).also { model = it }
            val recognizer = Recognizer(m, 16000f)
            val src = app.settings.micSource
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(src, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16000))
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("mikrofon (vstup $src) nejde otevřít")
            rec.startRecording()
            status = "Poslouchám na „${app.settings.wakePhrase}“ (vstup ${MicProbe.name(src)})"
            val buf = ShortArray(1600) // 0,1 s
            var sum = 0.0; var count = 0
            while (worker === Thread.currentThread()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                count += n
                if (count >= 8000) { level = Math.sqrt(sum / count).toInt(); sum = 0.0; count = 0 }
                if (paused) continue
                if (recognizer.acceptWaveForm(buf, n)) {
                    handle(app, JSONObject(recognizer.result).optString("text"))
                } else {
                    partial(app, JSONObject(recognizer.partialResult).optString("partial"))
                }
            }
            recognizer.close()
        } catch (e: Throwable) {
            Log.w("Panel", "voice wake", e)
            status = "Hlasová aktivace nejde spustit: ${e.message ?: e.javaClass.simpleName}"
            if (worker === Thread.currentThread()) worker = null
        } finally {
            try { rec?.stop(); rec?.release() } catch (_: Exception) {}
        }
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

    /** „prosím pepo“ → první slova celá, poslední jen začátek (Pepo/Pepa/Pepu…). */
    private fun wakeRegex(app: App): Regex {
        val words = norm(app.settings.wakePhrase).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return Regex("$^")
        val parts = words.mapIndexed { i, w -> if (i == words.size - 1) Regex.escape(w.take(3)) + "[a-z]*" else Regex.escape(w) }
        return Regex("\\b" + parts.joinToString("\\s+") + "\\b")
    }

    private fun partial(app: App, raw: String) {
        if (raw.isNotBlank()) { lastHeard = raw; lastHeardAt = System.currentTimeMillis() }
        if (!awaitingCommand && wakeRegex(app).containsMatchIn(norm(raw))) wake()
    }

    /** Klepnutí na mikrofon: další věta je povel (bez „prosím Pepo“). */
    fun captureCommand(): Boolean {
        if (worker == null) return false
        pause(false)
        wake()
        return true
    }

    private fun wake() {
        awaitingCommand = true
        wakeAt = System.currentTimeMillis()
        onWake?.invoke()
    }

    private fun handle(app: App, raw: String) {
        val text = raw.trim()
        if (text.isNotEmpty()) { lastHeard = text; lastHeardAt = System.currentTimeMillis() }
        if (awaitingCommand && System.currentTimeMillis() - wakeAt > 10_000) awaitingCommand = false
        val n = norm(text)
        val m = wakeRegex(app).find(n)
        val command: String? = when {
            m != null -> {
                // povel může být hned za frází: „prosím Pepo, zatop v obýváku“
                val words = text.split(Regex("\\s+"))
                val skip = norm(n.substring(0, m.range.last + 1)).split(' ').size
                words.drop(skip).joinToString(" ").trim().ifEmpty { null }.also { if (it == null) wake() }
            }
            awaitingCommand && text.isNotEmpty() -> text
            else -> null
        }
        if (command != null) {
            awaitingCommand = false
            Log.i("Panel", "povel: $command")
            val target = onCommand
            if (target != null) target(command) else {
                cz.stihnuto.panel.pages.ClaudePage.assistant(app).ask(command) { }
            }
        }
    }
}
