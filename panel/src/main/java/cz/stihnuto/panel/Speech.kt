package cz.stihnuto.panel

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Hlas asistenta: výběr z hlasů, které umí hlasový engine hodin, a jeho výška.
 * Vlastní TextToSpeech jen pro nastavení a ukázku; asistent mluví svým.
 */
object Speech {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    private fun ensure(app: App, then: () -> Unit) {
        if (ready) { then(); return }
        if (tts == null) {
            tts = TextToSpeech(app) { st ->
                ready = st == TextToSpeech.SUCCESS &&
                    tts?.setLanguage(Locale("cs", "CZ"))?.let { it >= TextToSpeech.LANG_AVAILABLE } == true
                app.main.post { if (ready) then() }
            }
        }
    }

    /** České hlasy, které engine nabízí. */
    fun czechVoices(): List<Voice> = try {
        tts?.voices.orEmpty()
            .filter { it.locale.language == "cs" || it.name.contains("cs", true) }
            .sortedBy { it.name }
    } catch (e: Exception) { emptyList() }

    /** Nastaví na daném přehrávači uložený hlas a výšku (volá i okno asistenta). */
    fun apply(app: App, t: TextToSpeech) {
        try {
            t.setPitch(app.settings.ttsPitch / 100f)
            val name = app.settings.ttsVoice
            if (name.isNotEmpty()) t.voices?.firstOrNull { it.name == name }?.let { t.voice = it }
        } catch (e: Exception) { /* engine bez výběru hlasu */ }
    }

    /** Přepne na další český hlas a přehraje ukázku. */
    fun nextVoice(app: App) = ensure(app) {
        val vs = czechVoices()
        if (vs.isEmpty()) { sample(app); return@ensure }
        val idx = vs.indexOfFirst { it.name == app.settings.ttsVoice }
        app.settings.ttsVoice = vs[(idx + 1).mod(vs.size)].name
        sample(app)
    }

    fun setPitch(app: App, pitch: Int) {
        app.settings.ttsPitch = pitch.coerceIn(50, 150)
        sample(app)
    }

    /** Řekne krátkou ukázku aktuálním nastavením. */
    fun sample(app: App) = ensure(app) {
        val t = tts ?: return@ensure
        apply(app, t)
        t.speak("Takhle budu mluvit.", TextToSpeech.QUEUE_FLUSH, null, "sample")
    }

    /** Počet dostupných českých hlasů (pro popisek). */
    fun voiceCount(): Int = czechVoices().size
}
