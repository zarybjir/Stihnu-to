package cz.stihnuto.panel

import android.content.Context

/** Veškeré nastavení. Mění se přes webovou stránku http://<ip-hodin>:8080. */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("panel", Context.MODE_PRIVATE)

    var place: String by str("place", "Praha")
    var lat: Double by dbl("lat", 50.0755)
    var lon: Double by dbl("lon", 14.4378)

    /** Pořadí oken, oddělené čárkou. Okno, které tu není, se nezobrazí. */
    var pages: String by str("pages", "clock,weather,hourly,radar,music,heating,claude")
    var homePage: String by str("home_page", "clock")
    /** Po kolika minutách bez dotyku se vrátit na domovské okno (0 = nikdy). */
    var idleMinutes: Int by int("idle_minutes", 3)

    var tapoUser: String by str("tapo_user", "")
    var tapoPass: String by str("tapo_pass", "")
    var hubIp: String by str("hub_ip", "")
    var heatStep: Double by dbl("heat_step", 0.5)

    var anthropicKey: String by str("anthropic_key", "")
    var claudeModel: String by str("claude_model", "claude-opus-5-5")

    /** Prázdné = vlastní radar (RainViewer). Jinak se otevře tahle webová stránka. */
    var radarUrl: String by str("radar_url", "")
    var radarZoom: Int by int("radar_zoom", 7)

    var nightFrom: Int by int("night_from", 22)
    var nightTo: Int by int("night_to", 7)
    /** Jas v noci 1–100 %, 0 = neměnit. */
    var nightBrightness: Int by int("night_brightness", 8)

    fun pageList(): List<String> =
        pages.split(',').map { it.trim() }.filter { it.isNotEmpty() }

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
