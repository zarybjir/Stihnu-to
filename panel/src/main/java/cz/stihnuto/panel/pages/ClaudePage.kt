package cz.stihnuto.panel.pages

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import cz.stihnuto.panel.App
import cz.stihnuto.panel.Ui
import cz.stihnuto.panel.claude.Assistant
import java.util.Locale

/** Hlasový asistent: klepni na mikrofon a řekni, co chceš. */
class ClaudePage(ctx: Context) : Page(ctx) {
    private val app = App.instance
    private val u = Ui.unit(ctx)
    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((u * 6).toInt(), (u * 5).toInt(), (u * 6).toInt(), (u * 8).toInt())
    }
    override val view: View = root

    private val mic = MicButton(ctx) { listen() }
    private val status = Ui.text(ctx, "", u * 4.2f, Ui.DIM).apply { gravity = Gravity.CENTER }
    private val you = Ui.text(ctx, "", u * 5f, Ui.DIM)
    private val answer = Ui.text(ctx, "", u * 6.5f, Ui.TEXT)
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        left.addView(mic, LinearLayout.LayoutParams((u * 34).toInt(), (u * 34).toInt()))
        left.addView(status, LinearLayout.LayoutParams((u * 50).toInt(), -2).apply { topMargin = (u * 3).toInt() })
        root.addView(left, LinearLayout.LayoutParams(-2, -1).apply { gravity = Gravity.CENTER_VERTICAL })

        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        right.addView(you)
        val scroll = ScrollView(ctx).apply { addView(answer) }
        right.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = (u * 2).toInt() })

        // rychlé dotazy — fungují i bez mikrofonu
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        listOf(
            listOf("Jak bude zítra?", "Bude dnes pršet?"),
            listOf("Kde se topí?", "Pusť hudbu"),
        ).forEach { row ->
            val r = LinearLayout(ctx)
            row.forEachIndexed { i, q ->
                r.addView(Ui.button(ctx, q, u * 4f) { ask(q) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = (u * 2).toInt() })
            }
            chips.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 2).toInt() })
        }
        right.addView(chips)
        root.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = (u * 6).toInt() })
        idle()
    }

    private fun idle() {
        status.text = if (app.settings.anthropicKey.isBlank()) "Nejdřív vlož klíč k Claude API v nastavení"
        else if (SpeechRecognizer.isRecognitionAvailable(ctx)) "Klepni a mluv" else "Rozpoznávání řeči není k dispozici — použij tlačítka"
        mic.active = false
    }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            status.text = "Rozpoznávání řeči tu není. Zkus tlačítka vpravo."
            return
        }
        tts?.stop()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status.text = "Poslouchám…"; mic.active = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { mic.level = rmsdB }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { mic.active = false; status.text = "Přemýšlím…" }
            override fun onError(error: Int) {
                mic.active = false
                status.text = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nerozuměl jsem, zkus to znovu"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chybí oprávnění k mikrofonu"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Rozpoznávání nemá internet"
                    else -> "Chyba rozpoznávání ($error)"
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) idle() else ask(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    you.text = "„$it“"
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "cs-CZ")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        r.startListening(intent)
    }

    fun ask(text: String) {
        you.text = "„$text“"
        answer.text = ""
        status.text = "Přemýšlím…"
        assistant(app).ask(text) { reply ->
            app.main.post {
                answer.text = reply
                idle()
                speak(reply)
            }
        }
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "odpoved")
    }

    override fun onShow() {
        idle()
        if (tts == null) {
            tts = TextToSpeech(ctx) { st ->
                ttsReady = st == TextToSpeech.SUCCESS &&
                    tts?.setLanguage(Locale("cs", "CZ"))?.let { it >= TextToSpeech.LANG_AVAILABLE } == true
            }
        }
    }

    override fun onHide() {
        recognizer?.destroy(); recognizer = null
        tts?.shutdown(); tts = null; ttsReady = false
    }

    companion object {
        private var shared: Assistant? = null

        /** Jeden asistent pro okno i pro webové API — sdílí rozhovor. */
        fun assistant(app: App): Assistant = shared ?: Assistant(app).also { shared = it }
    }

    /** Kulaté tlačítko mikrofonu, při poslechu pulzuje podle hlasitosti. */
    private class MicButton(ctx: Context, val onTap: () -> Unit) : View(ctx) {
        var active = false
            set(v) { field = v; invalidate() }
        var level = 0f
            set(v) { field = v; if (active) invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val r = minOf(width, height) / 2f
            val cx = width / 2f
            val cy = height / 2f
            if (active) {
                p.color = 0x33FFC94A
                c.drawCircle(cx, cy, r * (0.85f + (level.coerceIn(0f, 10f) / 10f) * 0.15f), p)
            }
            p.color = if (active) Ui.ACCENT else Ui.CARD_HI
            c.drawCircle(cx, cy, r * 0.75f, p)
            // mikrofon
            p.color = if (active) Ui.BG else Ui.TEXT
            val k = r * 0.2f
            c.drawRoundRect(RectF(cx - k * 0.7f, cy - k * 1.6f, cx + k * 0.7f, cy + k * 0.4f), k, k, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = k * 0.3f
            c.drawArc(RectF(cx - k * 1.2f, cy - k * 0.9f, cx + k * 1.2f, cy + k * 1.1f), 0f, 180f, false, p)
            c.drawLine(cx, cy + k * 1.1f, cx, cy + k * 1.6f, p)
            p.style = Paint.Style.FILL
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) onTap()
            return true
        }
    }
}
