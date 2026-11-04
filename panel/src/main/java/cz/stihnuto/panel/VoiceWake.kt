package cz.stihnuto.panel

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
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

    /** Okno Claude se přihlásí, aby povel zobrazilo a odpověď přečetlo nahlas. */
    var onCommand: ((String) -> Unit)? = null
    /** Volá se, když zazní aktivační fráze (zobrazit „Poslouchám…“). */
    var onWake: (() -> Unit)? = null

    private var model: Model? = null
    private var service: SpeechService? = null
    private var awaitingCommand = false
    private var wakeAt = 0L
    private var paused = false

    private fun modelDir(app: App) = File(app.filesDir, "vosk-cs")
    fun modelReady(app: App) = File(modelDir(app), "am").exists() || modelDir(app).listFiles()?.any { File(it, "am").exists() } == true
    val running get() = service != null

    /** Spustí poslouchání (podle nastavení), případně nejdřív stáhne model. */
    fun start(app: App) {
        if (!app.settings.voiceWake || service != null) return
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Chybí oprávnění k mikrofonu"
            return
        }
        app.io.execute {
            try {
                if (!modelReady(app)) download(app)
                status = "Načítám model…"
                val m = model ?: Model(modelPath(app)).also { model = it }
                val rec = Recognizer(m, 16000f)
                val svc = SpeechService(rec, 16000f)
                app.main.post {
                    service = svc
                    svc.startListening(listener(app))
                    status = "Poslouchám na „${app.settings.wakePhrase}“"
                }
            } catch (e: Throwable) {
                Log.w("Panel", "voice wake", e)
                status = "Hlasová aktivace nejde spustit: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun stop() {
        service?.stop()
        service?.shutdown()
        service = null
        awaitingCommand = false
        status = "Vypnuto"
    }

    /** Ztlumit poslouchání (když hodiny mluví nebo poslouchá jiné rozpoznávání). */
    fun pause(p: Boolean) {
        paused = p
        service?.setPause(p)
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

    private fun listener(app: App) = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val text = norm(JSONObject(hypothesis ?: "{}").optString("partial"))
            if (!awaitingCommand && wakeRegex(app).containsMatchIn(text)) wake()
        }

        override fun onResult(hypothesis: String?) = handle(app, JSONObject(hypothesis ?: "{}").optString("text"))
        override fun onFinalResult(hypothesis: String?) = handle(app, JSONObject(hypothesis ?: "{}").optString("text"))
        override fun onError(exception: Exception?) {
            status = "Chyba poslouchání: ${exception?.message}"
            stop()
            app.main.postDelayed({ start(app) }, 10_000)
        }
        override fun onTimeout() {}
    }

    private fun wake() {
        awaitingCommand = true
        wakeAt = System.currentTimeMillis()
        onWake?.invoke()
    }

    private fun handle(app: App, raw: String) {
        val text = raw.trim()
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
