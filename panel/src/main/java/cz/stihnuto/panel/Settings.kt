package cz.stihnuto.panel

import android.content.Context

/** Veškeré nastavení. Mění se přes webovou stránku http://<ip-hodin>:8080. */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("panel", Context.MODE_PRIVATE)

    var place: String by str("place", "Praha")
    var lat: Double by dbl("lat", 50.0755)
    var lon: Double by dbl("lon", 14.4378)

    /** Pořadí oken, oddělené čárkou. Okno, které tu není, se nezobrazí. */
    var pages: String by str("pages", "clock,calendar,weather,hourly,radar,music,heating,claude")

    /** Odkazy na kalendáře ve formátu iCal (.ics), každý na nový řádek. */
    var calendarUrls: String by str("calendar_urls", "")
    var homePage: String by str("home_page", "clock")
    /** Po kolika minutách bez dotyku se vrátit na domovské okno (0 = nikdy). */
    var idleMinutes: Int by int("idle_minutes", 3)

    var tapoUser: String by str("tapo_user", "")
    var tapoPass: String by str("tapo_pass", "")
    var hubIp: String by str("hub_ip", "")
    var heatStep: Double by dbl("heat_step", 0.5)

    /** claude | gemini */
    var aiProvider: String by str("ai_provider", "claude")
    var geminiKey: String by str("gemini_key", "")
    var geminiModel: String by str("gemini_model", "gemini-flash-lite-latest")
    var anthropicKey: String by str("anthropic_key", "")
    var claudeModel: String by str("claude_model", "claude-opus-5-5")

    /** Prázdné = vlastní radar (RainViewer). Jinak se otevře tahle webová stránka. */
    var radarUrl: String by str("radar_url", "")
    var radarZoom: Int by int("radar_zoom", 7)

    /** Hlasová aktivace (offline poslouchání na frázi). */
    var voiceWake: Boolean
        get() = p.getBoolean("voice_wake", false)
        set(v) { p.edit().putBoolean("voice_wake", v).apply() }
    var wakePhrase: String by str("wake_phrase", "prosím Pepo")

    /** Jas přes den 1–100 %, 0 = nechat automatiku hodin. */
    var dayBrightness: Int by int("day_brightness", 0)
    var nightFrom: Int by int("night_from", 22)
    var nightTo: Int by int("night_to", 5)
    /** Jas v noci 1–100 %, 0 = neměnit. */
    var nightBrightness: Int by int("night_brightness", 8)

    /** Okno Aplikace je vždy poslední — přes něj jdou aktualizace. */
    init {
        if (p.getInt("pages_version", 1) < 2) {
            val saved = p.getString("pages", null)
            if (saved != null && "calendar" !in saved) {
                p.edit().putString("pages", saved.replaceFirst("clock", "clock,calendar")).apply()
            }
            p.edit().putInt("pages_version", 2).apply()
        }
        // noční ztlumení nově do 5:00 (dřív do 7:00)
        if (p.getInt("night_version", 1) < 2) {
            if (p.getInt("night_to", 5) == 7) p.edit().putInt("night_to", 5).apply()
            p.edit().putInt("night_version", 2).apply()
        }
    }

    fun pageList(): List<String> =
        pages.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "apps" } + "apps"

    // --- jednoduché delegáty nad SharedPreferences
    private fun str(key: String, def: String) = Pref({ p.getString(key, def) ?: def }) {
        p.edit().putString(key, it).apply()
    }

    private fun int(key: String, def: Int) = Pref({ p.getInt(key, def) }) {
        p.edit().putInt(key, it).apply()
    }

    private fun dbl(key: String, def: Double) =
        Pref({ p.getString(key, null)?.toDoubleOrNull() ?: def }) {
            p.edit().putString(key, it.toString()).apply()
        }

    class Pref<T>(val get: () -> T, val set: (T) -> Unit) {
        operator fun getValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): T = get()
        operator fun setValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>, v: T) = set(v)
    }
}
