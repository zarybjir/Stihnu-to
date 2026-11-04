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

    /** Zeptá se Clauda. Běží v pozadí, výsledek vrátí do [done] (v pozadí). */
    fun ask(text: String, done: (String) -> Unit) {
        app.io.execute {
            done(try {
                askBlocking(text)
            } catch (e: UnauthorizedException) {
                "Klíč k Claude API nefunguje. Zkontroluj ho v nastavení."
            } catch (e: RateLimitException) {
                "Claude je teď přetížený, zkus to za chvilku."
            } catch (e: AnthropicServiceException) {
                Log.w("Panel", "claude", e)
                "Claude vrátil chybu ${e.statusCode()}."
            } catch (e: Exception) {
                Log.w("Panel", "claude", e)
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
            client = AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(60)).build()
            clientKey = key
        }
        // Po pěti minutách ticha začne nový rozhovor.
        if (System.currentTimeMillis() - lastUse > 5 * 60_000L) history.clear()
        lastUse = System.currentTimeMillis()

        history += MessageParam.ofUser(text)
        repeat(6) {
            val params = MessageCreateParams.builder()
                .model(app.settings.claudeModel.ifBlank { "claude-opus-5-5" })
                .maxTokens(16000L)
                .system(systemPrompt())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .tools(TOOLS.map { com.anthropic.models.messages.ToolUnion.ofTool(it) })
                .messages(history)
                // Když bezpečnostní filtr odmítne dotaz, server ho sám zkusí jiným modelem.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build()
            val resp: Message = client!!.messages().create(params)
            history += resp.toParam()

            val stop = resp.stopReason().orElse(null)
            if (stop == StopReason.REFUSAL) {
                history.clear()
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

    private fun systemPrompt(): String {
        val pages = app.settings.pageList().joinToString { "$it (${Pages.all[it]?.title ?: it})" }
        return """
            Jsi hlasový asistent na chytrých hodinách v domácnosti. Mluv česky.
            Odpovědi se čtou nahlas: jedna až dvě krátké věty, bez odrážek a bez markdownu.
            Ovládáš panel nástroji. Než něco nastavíš, nemusíš se ptát — prostě to udělej a stručně potvrď.
            Když se ptají na počasí nebo stav domu, nejdřív zavolej get_status.
            Okna panelu: $pages.
            Teď je ${Ui.dateLong(LocalDateTime.now().toLocalDate())}, ${Ui.time(LocalDateTime.now())}.
        """.trimIndent()
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
        "media_control" -> {
            val action = input["action"]?.toString() ?: ""
            val vol = (input["volume_percent"] as? Number)?.toInt()
            if (vol != null) { onMain { app.media.setVolume(vol) }; "Hlasitost $vol %." }
            else if (onMain { app.media.command(action) } == true) "Hotovo: $action."
            else "Nic nehraje, není co ovládat. Můžeš otevřít aplikaci nástrojem open_app."
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

        private fun tool(name: String, desc: String, props: Map<String, Map<String, Any>>, required: List<String> = emptyList()) =
            Tool.builder().name(name).description(desc).inputSchema(schema(props, required)).build()

        val TOOLS: List<Tool> by lazy {
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
                    "media_control", "Ovládá právě hrající hudbu/video (YouTube Music apod.).",
                    mapOf(
                        "action" to mapOf("type" to "string", "enum" to listOf("play", "pause", "toggle", "next", "previous", "volume_up", "volume_down")),
                        "volume_percent" to mapOf("type" to "integer", "description" to "Nastaví hlasitost 0–100 (místo action)."),
                    ),
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
