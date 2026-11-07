package cz.stihnuto.panel

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Test mikrofonu: zkusí postupně různé vstupy Androidu a změří hlasitost.
 * Na hodinách od Googlu bývá jeden vstup „zamčený“ pro Hey Google (ticho),
 * jiný ale může fungovat. Nejlepší se uloží pro hlasovou aktivaci.
 */
object MicProbe {
    private val sources = listOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.UNPROCESSED,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.CAMCORDER,
        MediaRecorder.AudioSource.DEFAULT,
    )

    /** Další vstup v pořadí (pro ruční zkoušení). */
    fun nextSource(src: Int): Int {
        val i = sources.indexOf(src)
        return sources[(i + 1).mod(sources.size)]
    }

    fun name(src: Int) = when (src) {
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "rozpoznávání"
        MediaRecorder.AudioSource.MIC -> "mikrofon"
        MediaRecorder.AudioSource.UNPROCESSED -> "surový"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "hovor"
        MediaRecorder.AudioSource.CAMCORDER -> "kamera"
        MediaRecorder.AudioSource.DEFAULT -> "výchozí"
        else -> "$src"
    }

    @Volatile var result: String = ""
        private set
    @Volatile var running = false
        private set

    /**
     * Chytrý test: u každého vstupu nahraje, co řekneš, nechá to přepsat Gemini
     * a vybere ten, kterému nejlíp rozumí (ne ten nejhlasitější). Bez klíče ke
     * Gemini spadne na obyčejný test hlasitosti.
     */
    fun smartTest(app: App, done: () -> Unit) {
        if (running) return
        if (!Transcribe.available(app)) { run(app, done); return }
        running = true
        val wasOn = VoiceWake.running
        VoiceWake.stop()
        Thread({
            Thread.sleep(500)
            val lines = mutableListOf<String>()
            var best = -1
            var bestScore = 0
            for (src in sources) {
                result = "Mluv (třeba „jedna dva tři“) — zkouším vstup „${name(src)}“…"
                app.main.post(done)
                val pcm = record(src, 2800)
                if (pcm == null) { lines += "${name(src)}: nejde otevřít"; continue }
                val text = try { Transcribe.text(app, pcm) } catch (e: Exception) { "" }
                val score = text.count { it.isLetterOrDigit() }
                lines += "${name(src)}: " + if (text.isBlank()) "—" else "„$text“"
                if (score > bestScore) { bestScore = score; best = src }
            }
            result = if (best >= 0 && bestScore >= 2) {
                app.settings.micSource = best
                "Nejlíp rozuměl vstup „${name(best)}“ ✓\n" + lines.joinToString("\n")
            } else {
                "Zatím žádný vstup nerozuměl — zkus to v tichu a mluv zřetelně.\n" + lines.joinToString("\n")
            }
            running = false
            if (wasOn || app.settings.voiceWake) VoiceWake.start(app)
            app.main.post(done)
        }, "mic-smart").apply { isDaemon = true; start() }
    }

    /** Nahraje `ms` milisekund z daného vstupu. */
    private fun record(src: Int, ms: Int): ShortArray? {
        var rec: AudioRecord? = null
        return try {
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(src, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16000))
            if (rec.state != AudioRecord.STATE_INITIALIZED) return null
            rec.startRecording()
            val out = java.io.ByteArrayOutputStream()
            val buf = ShortArray(1600)
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    val bb = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) bb.putShort(buf[i])
                    out.write(bb.array())
                }
            }
            val bytes = out.toByteArray()
            ShortArray(bytes.size / 2).also { java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        } catch (e: Exception) {
            null
        } finally {
            try { rec?.stop(); rec?.release() } catch (_: Exception) {}
        }
    }

    /** Spustí test (asi 10 s). Během něj mluv u hodin. */
    fun run(app: App, done: () -> Unit) {
        if (running) return
        running = true
        val wasOn = VoiceWake.running
        VoiceWake.stop()
        Thread({
            Thread.sleep(500) // ať hlasová aktivace uvolní mikrofon
            val lines = mutableListOf<String>()
            var best = -1
            var bestRms = 0
            for (src in sources) {
                result = "Mluv u hodin… zkouším vstup „${name(src)}“"
                app.main.post(done)
                val rms = measure(src)
                lines += "${name(src)}: " + when {
                    rms < 0 -> "nejde otevřít"
                    rms < 30 -> "ticho ($rms)"
                    else -> "zvuk $rms"
                }
                if (rms > bestRms) { bestRms = rms; best = src }
            }
            result = if (best >= 0 && bestRms >= 30) {
                app.settings.micSource = best
                "Vybrán vstup „${name(best)}“ ✓\n" + lines.joinToString(" · ")
            } else {
                "Všechny vstupy jsou tiché — mikrofon nejspíš drží Google.\n" + lines.joinToString(" · ")
            }
            running = false
            if (wasOn || app.settings.voiceWake) VoiceWake.start(app)
            app.main.post(done)
        }, "mic-probe").apply { isDaemon = true; start() }
    }

    /** Průměrná hlasitost (RMS) za 1,5 s, nebo -1 když vstup nejde otevřít. */
    private fun measure(src: Int): Int {
        var rec: AudioRecord? = null
        return try {
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(src, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16000))
            if (rec.state != AudioRecord.STATE_INITIALIZED) return -1
            rec.startRecording()
            val buf = ShortArray(1600)
            var sum = 0.0
            var count = 0
            val end = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < end) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                count += n
            }
            if (count == 0) 0 else Math.sqrt(sum / count).toInt()
        } catch (e: Exception) {
            Log.w("Panel", "mic probe $src", e)
            -1
        } finally {
            try { rec?.stop(); rec?.release() } catch (_: Exception) {}
        }
    }
}
