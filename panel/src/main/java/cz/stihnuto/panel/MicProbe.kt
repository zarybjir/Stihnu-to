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
