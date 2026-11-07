package cz.stihnuto.panel.claude

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import cz.stihnuto.panel.App
import cz.stihnuto.panel.Ui
import cz.stihnuto.panel.pages.Pages
import cz.stihnuto.panel.weather.WeatherApi
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Claude na hodinách. Rozumí češtině a přes nástroje ovládá panel:
 * přepíná okna, nastavuje topení, ovládá hudbu a ví, jaké je počasí.
 */
class Assistant(private val app: App) {
    private var client: AnthropicClient? = null
    private var clientKey = ""
    private val history = mutableListOf<MessageParam>()
    private var lastUse = 0L
    // Systémový text se v rámci rozhovoru nemění (jinak by se rozbily „thinking" bloky a přišla chyba 400).
    private var systemCached: String? = null

    /** Zeptá se Clauda. Běží v pozadí, výsledek vrátí do [done] (v pozadí). */
    fun ask(text: String, done: (String) -> Unit) {
        app.io.execute {
            if (app.settings.aiProvider == "gemini") {
                done(try {
                    askGemini(text)
                } catch (e: Exception) {
                    Log.w("Panel", "gemini", e)
                    synchronized(this) { gHistory.clear() }
                    e.message ?: "Nepovedlo se spojit s Gemini."
                })
                return@execute
            }
            done(try {
                askBlocking(text)
            } catch (e: UnauthorizedException) {
                "Klíč k Claude API nefunguje. Zkontroluj ho v nastavení."
            } catch (e: RateLimitException) {
                "Claude je momentálně přetížený, zkus to za chvilku."
            } catch (e: AnthropicServiceException) {
                Log.w("Panel", "claude", e)
                synchronized(this) { resetConversation() }
                val why = (e.message ?: "").let { m ->
                    Regex("\"message\"\\s*:\\s*\"([^\"]+)").find(m)?.groupValues?.get(1) ?: m
                }.take(160)
                "Claude vrátil chybu ${e.statusCode()}. $why"
            } catch (e: Exception) {
                Log.w("Panel", "claude", e)
                synchronized(this) { resetConversation() }
                "Nepovedlo se spojit s Claude (${e.message ?: e.javaClass.simpleName})."
            })
        }
    }

    @Synchronized
    private fun askBlocking(text: String): String {
        val key = app.settings.anthropicKey.trim()
        if (key.isEmpty()) return "Nejdřív vlož klíč k Claude API v nastavení na počítači."
        if (client == null || key != clientKey) {
            client?.close()
            client = AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(25)).build()
            clientKey = key
        }
        // Po pěti minutách ticha začne nový rozhovor.
        if (System.currentTimeMillis() - lastUse > 5 * 60_000L) resetConversation()
        lastUse = System.currentTimeMillis()

        val model = app.settings.claudeModel.ifBlank { "claude-opus-5-5" }
        // záložní model při odmítnutí umí jen Opus / Sonnet / Fable — Haiku by vrátil chybu 400
        val fallbacks = !model.contains("haiku")
        history += MessageParam.ofUser(text)
        return try {
            conversationTurn(model, fallbacks)
        } catch (e: AnthropicServiceException) {
            // 400 bývá neplatný „thinking" blok z minulého kola (vázaný na jiný rozhovor) —
            // začni čistý rozhovor a zkus dotaz ještě jednou.
            if (e.statusCode() == 400) {
                resetConversation()
                history += MessageParam.ofUser(text)
                conversationTurn(model, fallbacks)
            } else throw e
        }
    }

    private fun resetConversation() { history.clear(); systemCached = null }

    /** Jedno „kolo": volá model, zpracuje nástroje, vrátí odpověď. */
    private fun conversationTurn(model: String, fallbacks: Boolean): String {
        repeat(6) {
            val params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(systemPrompt())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .tools(TOOLS.map { com.anthropic.models.messages.ToolUnion.ofTool(it) })
                .messages(history)
                .apply {
                    // Když bezpečnostní filtr odmítne dotaz, server ho sám zkusí jiným modelem.
                    if (fallbacks) {
                        putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                        putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    }
                }
                .build()
            val resp: Message = client!!.messages().create(params)
            history += resp.toParam()

            val stop = resp.stopReason().orElse(null)
            if (stop == StopReason.REFUSAL) {
                resetConversation()
                return "Tohle bohužel udělat nemůžu."
            }
            val calls = resp.content().mapNotNull { it.toolUse().orElse(null) }
            if (stop != StopReason.TOOL_USE || calls.isEmpty()) {
                return resp.content().mapNotNull { it.text().orElse(null)?.text() }
                    .joinToString(" ").trim().ifEmpty { "Hotovo." }
            }
            val results = calls.map { call ->
                val (out, isError) = try {
                    @Suppress("UNCHECKED_CAST")
                    val input = call._input().convert(Map::class.java) as Map<String, Any?>? ?: emptyMap()
                    runTool(call.name(), input) to false
                } catch (e: Exception) {
                    (e.message ?: "chyba") to true
                }
                ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder().toolUseId(call.id()).content(out).isError(isError).build()
                )
            }
            history += MessageParam.ofUserOfContentBlockParams(results)
        }
        return "Hotovo."
    }

    // ------------------------------------------------------------------
    // Gemini (Google) — bezplatná varianta, stejné nástroje
    // ------------------------------------------------------------------

    private val gHistory = mutableListOf<JSONObject>()
    private var gLastUse = 0L

    @Synchronized
    private fun askGemini(text: String): String {
        val key = app.settings.geminiKey.trim()
        if (key.isEmpty()) return "Nejdřív vlož klíč ke Gemini v nastavení na počítači."
        val model = app.settings.geminiModel.trim().ifEmpty { "gemini-flash-lite-latest" }
        if (System.currentTimeMillis() - gLastUse > 5 * 60_000L) gHistory.clear()
        gLastUse = System.currentTimeMillis()
        gHistory += JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))

        repeat(6) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
                .put("contents", JSONArray(gHistory))
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", GEMINI_TOOLS)))
            val resp = geminiWithRetry(model, key, body)
            val cand = resp.optJSONArray("candidates")?.optJSONObject(0)
            val content = cand?.optJSONObject("content")
            if (content == null) {
                gHistory.clear()
                return if (resp.optJSONObject("promptFeedback") != null) "Tohle bohužel udělat nemůžu." else "Gemini neodpověděl."
            }
            if (!content.has("role")) content.put("role", "model")
            gHistory += content // vrací se beze změny (včetně thoughtSignature)
            val parts = content.optJSONArray("parts") ?: JSONArray()
            val calls = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optJSONObject("functionCall") }
            if (calls.isEmpty()) {
                return (0 until parts.length()).mapNotNull { i ->
                    parts.optJSONObject(i)?.takeIf { !it.optBoolean("thought") }?.optString("text")?.takeIf { it.isNotBlank() }
                }.joinToString(" ").trim().ifEmpty { "Hotovo." }
            }
            val results = JSONArray()
            for (c in calls) {
                val name = c.optString("name")
                val args = c.optJSONObject("args") ?: JSONObject()
                @Suppress("UNCHECKED_CAST")
                val input = args.keys().asSequence().associateWith { k -> args.opt(k).let { v -> if (v == JSONObject.NULL) null else v } }
                val out = try { runTool(name, input) } catch (e: Exception) { "Chyba: ${e.message}" }
                results.put(JSONObject().put("functionResponse",
                    JSONObject().put("name", name).put("response", JSONObject().put("result", out))))
            }
            gHistory += JSONObject().put("role", "user").put("parts", results)
        }
        return "Hotovo."
    }

    /** Hlasový dotaz rovnou do Gemini (jedno volání: přepis + rozhodnutí + odpověď + akce). Async. */
    fun askAudio(pcm: ShortArray, mode: String, done: (String) -> Unit) {
        app.io.execute { done(answerAudio(pcm, mode)) }
    }

    /** Synchronní jádro. mode: direct (klepnutí) | phrase (aktivační slovo) | free. „" = nemluví na mě. */
    fun answerAudio(pcm: ShortArray, mode: String): String {
        val gate = when (mode) {
            "phrase" -> "V nahrávce je řeč z místnosti u chytrých hodin. Proveď akci a odpověz JEN tehdy, když " +
                "mluvčí řekne aktivační slovo „${app.settings.wakePhrase}“. Pokud ho neřekne, odpověz přesně slovem NIC a nevolej žádný nástroj."
            "free" -> "V nahrávce je řeč z místnosti u chytrých hodin. Proveď akci a odpověz JEN tehdy, když mluvčí " +
                "mluví na tebe (pokyn nebo otázka pro hodiny/asistenta). Jinak (hovor mezi lidmi, televize, telefon, zpěv) " +
                "odpověz přesně slovem NIC a nevolej žádný nástroj."
            else -> "V nahrávce je hlasový dotaz pro tebe. Splň ho a stručně odpověz."
        }
        val r = try {
            askGeminiAudio(pcm, gate)
        } catch (e: Exception) {
            Log.w("Panel", "gemini audio", e)
            synchronized(this) { gHistory.clear() }
            e.message ?: "Nepovedlo se spojit s Gemini."
        }
        return if (r.trim().trim('.', '!', '?', '„', '“').equals("NIC", true)) "" else r
    }

    @Synchronized
    private fun askGeminiAudio(pcm: ShortArray, gate: String): String {
        val key = app.settings.geminiKey.trim()
        if (key.isEmpty()) return "Nejdřív vlož klíč ke Gemini v nastavení na počítači."
        val model = app.settings.geminiModel.trim().ifEmpty { "gemini-flash-lite-latest" }
        if (System.currentTimeMillis() - gLastUse > 5 * 60_000L) gHistory.clear()
        gLastUse = System.currentTimeMillis()

        val userParts = JSONArray()
            .put(JSONObject().put("text", gate))
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav")
                .put("data", android.util.Base64.encodeToString(cz.stihnuto.panel.Transcribe.wav(pcm), android.util.Base64.NO_WRAP))))
        val work = mutableListOf<JSONObject>().apply { addAll(gHistory) }
        val audioIdx = work.size
        work += JSONObject().put("role", "user").put("parts", userParts)

        var answer = "Hotovo."
        repeat(6) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
                .put("contents", JSONArray(work))
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", GEMINI_TOOLS)))
            val resp = geminiWithRetry(model, key, body)
            val content = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
            if (content == null) {
                gHistory.clear()
                return if (resp.optJSONObject("promptFeedback") != null) "Tohle bohužel udělat nemůžu." else "Gemini neodpověděl."
            }
            if (!content.has("role")) content.put("role", "model")
            work += content
            val parts = content.optJSONArray("parts") ?: JSONArray()
            val calls = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optJSONObject("functionCall") }
            if (calls.isEmpty()) {
                answer = (0 until parts.length()).mapNotNull { i ->
                    parts.optJSONObject(i)?.takeIf { !it.optBoolean("thought") }?.optString("text")?.takeIf { it.isNotBlank() }
                }.joinToString(" ").trim().ifEmpty { "Hotovo." }
                persistAudio(work, audioIdx)
                return answer
            }
            val results = JSONArray()
            for (c in calls) {
                val name = c.optString("name")
                val args = c.optJSONObject("args") ?: JSONObject()
                @Suppress("UNCHECKED_CAST")
                val input = args.keys().asSequence().associateWith { k -> args.opt(k).let { v -> if (v == JSONObject.NULL) null else v } }
                val out = try { runTool(name, input) } catch (e: Exception) { "Chyba: ${e.message}" }
                results.put(JSONObject().put("functionResponse",
                    JSONObject().put("name", name).put("response", JSONObject().put("result", out))))
            }
            work += JSONObject().put("role", "user").put("parts", results)
        }
        persistAudio(work, audioIdx)
        return answer
    }

    /** Uloží rozhovor, ale velkou nahrávku nahradí textem, ať se při dalším dotazu nepřeposílá. */
    private fun persistAudio(work: List<JSONObject>, audioIdx: Int) {
        gHistory.clear()
        work.forEachIndexed { i, turn ->
            if (i == audioIdx) gHistory += JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", "(hlasový dotaz)")))
            else gHistory += turn
        }
    }

    /** Gemini bývá občas přetížený — místo dlouhého čekání to hned zkusit znovu, případně lehčím modelem. */
    private fun geminiWithRetry(model: String, key: String, body: JSONObject): JSONObject {
        val models = if (model == "gemini-flash-lite-latest") listOf(model, model) else listOf(model, "gemini-flash-lite-latest")
        var last: Exception? = null
        for (m in models) {
            try {
                return geminiPost(m, key, body)
            } catch (e: java.net.SocketTimeoutException) {
                last = java.io.IOException("Gemini neodpověděl včas.")
            } catch (e: java.io.IOException) {
                last = e
                val msg = e.message ?: ""
                if (!(msg.contains("503") || msg.contains("demand", true) || msg.contains("overload", true) || msg.contains("UNAVAILABLE"))) throw e
            }
        }
        throw last ?: java.io.IOException("Gemini neodpověděl.")
    }

    private fun geminiPost(model: String, key: String, body: JSONObject): JSONObject {
        val c = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15000
        c.readTimeout = 12000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
                throw java.io.IOException(when (code) {
                    400, 401, 403 -> "Gemini odmítl dotaz ($code). Zkontroluj klíč a název modelu. ${msg ?: ""}".trim()
                    429 -> if (msg?.contains("PerDay", true) == true || msg?.contains("per day", true) == true)
                        "Gemini: denní limit vyčerpán, zkus to zítra."
                    else "Gemini zahlcený (limit za minutu), zkus to za chvilku."
                    503 -> "Gemini je přetížený (503). ${msg ?: ""}".trim()
                    else -> "Gemini vrátil chybu $code. ${msg ?: ""}".trim()
                })
            }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    private fun systemPrompt(): String {
        systemCached?.let { return it }
        val pages = app.settings.pageList().joinToString { "$it (${Pages.all[it]?.title ?: it})" }
        return """
            Jsi hlasový asistent na chytrých hodinách v domácnosti. Mluv česky.
            Odpovědi se čtou nahlas: jedna až dvě krátké věty, bez odrážek a bez markdownu.
            Hudbu pouštěj nástrojem play_music, pauzu, další skladbu a hlasitost nástrojem media_control.
            Ovládáš panel nástroji. Než něco nastavíš, nemusíš se ptát — prostě to udělej a stručně potvrď.
            Nedoptávej se na podrobnosti: když něco chybí, zvol nejběžnější možnost a rovnou jednej
            („přidej chleba“ = položka „chleba“, „zatop“ = 22 °C, „pusť něco“ = oblíbený mix).
            Ptej se jen tehdy, když by bez odpovědi šlo opravdu udělat něco špatně.
            Text pochází z rozpoznávání řeči a může obsahovat zkomolená slova — domysli, co bylo nejspíš myšleno.
            Když tě někdo požádá o vtip, řekni krátký původní ČESKÝ vtip nebo slovní hříčku, která funguje v češtině — nikdy ne překlad z angličtiny.
            Na běžné otázky odpověz rovnou z přehledu níže. get_status volej jen, když potřebuješ podrobnosti
            (hodinová předpověď, další dny, kalendář na delší dobu).
            Okna panelu: $pages.
            Je ${Ui.dateLong(LocalDateTime.now().toLocalDate())}, ${Ui.time(LocalDateTime.now())}.

            Přehled:
            ${brief()}
        """.trimIndent().also { systemCached = it }
    }

    /** Stručný stav do zadání — většinou ušetří jeden dotaz navíc (rychlejší odpověď). */
    private fun brief(): String {
        val out = mutableListOf<String>()
        app.weather.data?.let { w ->
            out += "Venku ${Ui.num(w.current.temp)} °C, ${WeatherApi.describe(w.current.code)}, vítr ${Math.round(w.current.wind)} km/h."
            w.days.getOrNull(0)?.let { d -> out += "Dnes ${Ui.deg(d.max)}/${Ui.deg(d.min)}, srážky ${Ui.num(d.precipSum)} mm (${d.precipProb} %)." }
            w.days.getOrNull(1)?.let { d -> out += "Zítra ${Ui.deg(d.max)}/${Ui.deg(d.min)}, ${WeatherApi.describe(d.code)}, srážky ${d.precipProb} %." }
        }
        val heat = app.heating.children.filter { it.isValve }
        if (heat.isNotEmpty()) out += "Topení: " + heat.joinToString("; ") { c ->
            "${c.name} ${Ui.num(c.currentTemp)} °C" + if (!c.on) " (vypnuto)" else " → cíl ${Ui.num(c.targetTemp)} °C${if (c.heating) ", topí" else ""}"
        } + "." + (app.heating.activeScene()?.let { " Scéna: ${it.name}." } ?: "")
        app.calendar.upcoming().take(3).takeIf { it.isNotEmpty() }?.let { ev ->
            out += "Kalendář: " + ev.joinToString("; ") { e ->
                "${Ui.dayShort(e.start.toLocalDate())} ${if (e.allDay) "celý den" else Ui.time(e.start)} ${e.title}"
            } + "."
        }
        app.media.title?.let { out += "Hraje: $it${app.media.artist?.let { a -> " – $a" } ?: ""}${if (app.media.isPlaying) "" else " (pauza)"}." }
        return out.joinToString("\n")
    }

    // ------------------------------------------------------------------
    // Nástroje
    // ------------------------------------------------------------------

    private fun runTool(name: String, input: Map<String, Any?>): String = when (name) {
        "get_status" -> status().toString()
        "show_page" -> {
            val page = input["page"]?.toString() ?: ""
            if (app.showPage(page)) "Zobrazeno okno $page." else "Okno $page neexistuje."
        }
        "set_heating" -> setHeating(input)
        "add_shopping" -> {
            val raw = input["items"]
            val list = when (raw) {
                is List<*> -> raw.mapNotNull { it?.toString() }
                is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
                is String -> raw.split(',', ';', '\n')
                else -> emptyList()
            }.map { it.trim() }.filter { it.isNotEmpty() }
            if (list.isEmpty()) "Nevím, co přidat." else {
                app.shopping.add(list)
                "Přidáno do nákupu v Kuchapce: ${list.joinToString()}."
            }
        }
        "heating_scene" -> {
            val name = input["scene"]?.toString() ?: ""
            val latch = CountDownLatch(1)
            var err: String? = null
            app.heating.applyScene(name) { err = it; latch.countDown() }
            latch.await(30, TimeUnit.SECONDS)
            err?.let { "Chyba: $it" } ?: "Scéna $name nastavena."
        }
        "media_control" -> {
            val action = input["action"]?.toString() ?: ""
            val vol = (input["volume_percent"] as? Number)?.toInt()
            if (vol != null) { onMain { app.media.setVolume(vol) }; "Hlasitost $vol %." }
            else if (onMain { app.media.command(action) } == true) "Hotovo: $action."
            else "Nic nehraje, není co ovládat. Můžeš otevřít aplikaci nástrojem open_app."
        }
        "play_music" -> {
            val q = input["query"]?.toString().orEmpty()
            val kind = input["kind"]?.toString() ?: "song"
            val hit = cz.stihnuto.panel.YtMusic.search(q, kind) ?: return "Na YouTube Music jsem „$q“ nenašel."
            if (onMain { cz.stihnuto.panel.YtMusic.open(app, hit.url) } == true) {
                app.main.postDelayed({ app.showPage("music") }, 4000)
                "Pouštím: ${hit.title}${if (hit.subtitle.isNotBlank()) " (${hit.subtitle})" else ""}."
            } else "NewPipe není nainstalovaný — nainstaluj ho v okně Aplikace."
        }
        "open_app" -> {
            val appKey = input["app"]?.toString() ?: ""
            val query = input["search"]?.toString()
            if (onMain { app.media.openApp(appKey, query) } == true) "Otevřeno $appKey." else "Aplikace $appKey není nainstalovaná."
        }
        else -> throw IllegalArgumentException("Neznámý nástroj $name")
    }

    private fun setHeating(input: Map<String, Any?>): String {
        val room = input["room"]?.toString() ?: ""
        val valves = app.heating.children.filter { it.isValve }
        val targets = if (room.equals("vše", true) || room.equals("all", true)) valves
        else listOfNotNull(app.heating.find(room)?.takeIf { it.isValve })
        if (targets.isEmpty()) {
            return "Hlavice „$room“ nenalezena. Dostupné: ${valves.joinToString { it.name }}."
        }
        val temp = (input["target_temperature"] as? Number)?.toDouble()
        val on = input["on"] as? Boolean
        val errors = mutableListOf<String>()
        for (v in targets) {
            val latch = CountDownLatch(1)
            val done: (String?) -> Unit = { err -> if (err != null) errors += "${v.name}: $err"; latch.countDown() }
            when {
                temp != null -> app.heating.setTarget(v.id, temp, done)
                on != null -> app.heating.setOn(v.id, on, done)
                else -> return "Zadej teplotu nebo zapnutí/vypnutí."
            }
            latch.await(20, TimeUnit.SECONDS)
        }
        return if (errors.isEmpty()) "Nastaveno: ${targets.joinToString { it.name }}." else "Chyby: ${errors.joinToString()}"
    }

    /** Spustí věc na hlavním vlákně a počká na výsledek. */
    private fun <T> onMain(block: () -> T): T? {
        var out: T? = null
        val latch = CountDownLatch(1)
        app.main.post { try { out = block() } finally { latch.countDown() } }
        latch.await(5, TimeUnit.SECONDS)
        return out
    }

    fun status(): JSONObject {
        val j = JSONObject()
        j.put("cas", Ui.time(LocalDateTime.now()))
        j.put("okno", app.currentPage)
        app.weather.data?.let { w ->
            j.put("pocasi_ted", JSONObject()
                .put("teplota", w.current.temp).put("pocitove", w.current.feels)
                .put("popis", WeatherApi.describe(w.current.code)).put("vitr_kmh", w.current.wind)
                .put("vlhkost", w.current.humidity))
            j.put("predpoved_dny", JSONArray(w.days.take(7).map { d ->
                JSONObject().put("datum", d.date.toString()).put("den", Ui.dayName(d.date))
                    .put("max", d.max).put("min", d.min).put("srazky_mm", d.precipSum)
                    .put("srazky_pravdepodobnost", d.precipProb).put("popis", WeatherApi.describe(d.code))
            }))
            j.put("predpoved_hodiny", JSONArray(w.hours.take(24).filterIndexed { i, _ -> i % 2 == 0 }.map { h ->
                JSONObject().put("cas", h.time.toString()).put("teplota", h.temp)
                    .put("srazky_pravdepodobnost", h.precipProb).put("srazky_mm", h.precip)
            }))
        }
        j.put("topeni", JSONArray(app.heating.children.map { c ->
            JSONObject().put("nazev", c.name)
                .put("typ", if (c.isValve) "hlavice" else if (c.isThermometer) "teplomer" else c.category)
                .put("teplota", c.currentTemp).put("cil", c.targetTemp).put("zapnuto", c.on)
                .put("topi", c.heating).put("vlhkost", c.humidity).put("online", c.online)
        }))
        app.heating.error?.let { j.put("topeni_chyba", it) }
        j.put("kalendar", JSONArray(app.calendar.upcoming().take(20).map { e ->
            JSONObject().put("nazev", e.title).put("zacatek", e.start.toString()).put("konec", e.end.toString())
                .put("cely_den", e.allDay).put("misto", e.location)
        }))
        val m = app.media
        j.put("hudba", JSONObject().put("hraje", m.isPlaying).put("skladba", m.title)
            .put("interpret", m.artist).put("aplikace", m.appName).put("hlasitost", m.volumePercent))
        return j
    }

    companion object {
        private fun schema(props: Map<String, Map<String, Any>>, required: List<String>) =
            Tool.InputSchema.builder()
                .properties(Tool.InputSchema.Properties.builder().apply {
                    props.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
                }.build())
                .required(required)
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build()

        class Def(val name: String, val desc: String, val props: Map<String, Map<String, Any>>, val required: List<String>)

        private fun tool(name: String, desc: String, props: Map<String, Map<String, Any>>, required: List<String> = emptyList()) =
            Def(name, desc, props, required)

        val TOOLS: List<Tool> by lazy {
            DEFS.map { Tool.builder().name(it.name).description(it.desc).inputSchema(schema(it.props, it.required)).build() }
        }

        /** Stejné nástroje ve formátu Gemini (functionDeclarations). */
        val GEMINI_TOOLS: JSONArray by lazy {
            fun conv(v: Any?): Any? = when (v) {
                is Map<*, *> -> JSONObject().apply {
                    v.forEach { (k, x) -> put(k.toString(), if (k == "type") x.toString().uppercase() else conv(x)) }
                }
                is List<*> -> JSONArray(v.map { conv(it) })
                else -> v
            }
            JSONArray(DEFS.map { d ->
                JSONObject().put("name", d.name).put("description", d.desc).apply {
                    if (d.props.isNotEmpty()) put("parameters", JSONObject().put("type", "OBJECT")
                        .put("properties", conv(d.props)).put("required", JSONArray(d.required)))
                }
            })
        }

        val DEFS: List<Def> by lazy {
            listOf(
                tool("get_status", "Aktuální stav: čas, počasí (teď, po hodinách, 7 dní), kalendář na 2 týdny, hlavice a teploměry, co hraje.", emptyMap()),
                tool(
                    "show_page", "Přepne panel na jiné okno.",
                    mapOf("page" to mapOf("type" to "string", "enum" to Pages.all.keys.toList(),
                        "description" to "clock=hodiny, calendar=kalendář, weather=počasí na dny, hourly=po hodinách, radar, music=hudba, heating=topení, claude=asistent")),
                    listOf("page"),
                ),
                tool(
                    "set_heating", "Nastaví hlavici topení: cílovou teplotu, nebo zapnutí/vypnutí.",
                    mapOf(
                        "room" to mapOf("type" to "string", "description" to "Název hlavice (místnosti) jak je v get_status, nebo \"vše\"."),
                        "target_temperature" to mapOf("type" to "number", "description" to "Cílová teplota ve °C (5–30)."),
                        "on" to mapOf("type" to "boolean", "description" to "true = zapnout, false = vypnout (jen ochrana proti mrazu)."),
                    ),
                    listOf("room"),
                ),
                tool(
                    "add_shopping", "Přidá položky na nákupní seznam v mobilní aplikaci Kuchapka. Každá položka zvlášť, " +
                        "množství na začátek, např. [\"2 kg brambory\", \"mléko\", \"6 ks vejce\"].",
                    mapOf("items" to mapOf("type" to "array", "items" to mapOf("type" to "string"),
                        "description" to "Položky v češtině, v 1. pádě.")),
                    listOf("items"),
                ),
                tool(
                    "heating_scene", "Nastaví všechny hlavice podle scény. Doma = běžné teploty, Noc = na spaní, " +
                        "Pryč = když nikdo není doma (odjezd, dovolená), Vše vypnout = jen ochrana proti mrazu.",
                    mapOf("scene" to mapOf("type" to "string", "enum" to listOf("Doma", "Noc", "Pryč", "Vše vypnout"))),
                    listOf("scene"),
                ),
                tool(
                    "media_control", "Ovládá právě hrající hudbu/video (YouTube Music apod.).",
                    mapOf(
                        "action" to mapOf("type" to "string", "enum" to listOf("play", "pause", "toggle", "next", "previous", "volume_up", "volume_down")),
                        "volume_percent" to mapOf("type" to "integer", "description" to "Nastaví hlasitost 0–100 (místo action)."),
                    ),
                ),
                tool(
                    "play_music", "Najde hudbu na YouTube Music a pustí ji v přehrávači na hodinách. Použij vždy, když chce pustit konkrétní písničku, interpreta, album nebo playlist.",
                    mapOf(
                        "query" to mapOf("type" to "string", "description" to "Co hledat, např. \"Lucie Medvídek\" nebo \"Kryštof\"."),
                        "kind" to mapOf("type" to "string", "enum" to listOf("song", "album", "playlist", "any"),
                            "description" to "song = konkrétní skladba nebo interpret (pak hraje mix), album, playlist."),
                    ),
                    listOf("query"),
                ),
                tool(
                    "open_app", "Otevře aplikaci na hodinách, volitelně rovnou s hledáním.",
                    mapOf(
                        "app" to mapOf("type" to "string", "enum" to listOf("youtube_music", "youtube")),
                        "search" to mapOf("type" to "string", "description" to "Co vyhledat (interpret, skladba, video)."),
                    ),
                    listOf("app"),
                ),
            )
        }
    }
}
