package cz.stihnuto.panel

import org.json.JSONObject

/**
 * České vtipy. První je „Brutopýr", pak se berou ze seznamu 1283 vtipů staženého
 * online (účet @cz_vtipy), ať se neopakují. Offline padne na zabudovaný seznam.
 */
object Jokes {
    // veřejné JSON API projektu miroslavpalansky/ceske-vtipy
    private const val URL =
        "https://script.google.com/macros/s/AKfycbyZ5LJ99eRhSv8dQpRpIzY_VJfijqP36c47P5lc_rchetqkouLyugFF0jDrzWObuOI/exec?a=data"

    private val builtin = listOf(
        "Co se stane z netopýra, když ho zabalím do krabice? Brutopýr.",
        "Přijde kostra do hospody a povídá: Jedno pivo. A hadr.",
        "Co řekne nula osmičce? Hezký pásek!",
        "Potkají se dva psi. Jeden štěkne: Haf! A ten druhý: No jo, to jsem ti chtěl říct zrovna já.",
        "Pepíčku, vyjmenuj čtyři kočkovité šelmy. No přece dvě kočky a dvě koťata.",
        "Víš, jak se řekne japonsky řidič? Takuji.",
        "Ptá se učitelka: Pepíčku, co víš o Mrtvém moři? A Pepíček: Já ani nevěděl, že bylo nemocné.",
        "Povídá šnek šnekovi: Pospěš si, nebo nám ujede autobus. A za hodinu: No vidíš, ujel.",
    )

    @Volatile private var online: List<String> = emptyList()
    @Volatile private var fetching = false
    private val shown = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Načte vtipy ze sítě na pozadí (volá se při startu a při otevření asistenta). */
    fun prefetch(app: App) {
        if (online.isNotEmpty() || fetching) return
        fetching = true
        app.io.execute {
            try {
                val arr = JSONObject(Http.text(URL)).optJSONArray("jokes")
                val out = ArrayList<String>()
                for (k in 0 until (arr?.length() ?: 0)) {
                    val t = arr!!.optJSONArray(k)?.optString(1)?.trim()
                    if (!t.isNullOrBlank() && t.length in 3..400) out.add(t)
                }
                if (out.isNotEmpty()) online = out
            } catch (e: Exception) {
                android.util.Log.w("Panel", "jokes", e)
            } finally {
                fetching = false
            }
        }
    }

    fun next(app: App): String {
        val i = app.settings.jokeIndex
        app.settings.jokeIndex = i + 1
        if (i == 0) return builtin[0] // první vždy Brutopýr
        prefetch(app)
        val pool = online
        if (pool.isNotEmpty()) {
            val fresh = pool.filter { it !in shown }
            val pick = (if (fresh.isEmpty()) { shown.clear(); pool } else fresh).random()
            shown.add(pick)
            return pick
        }
        return builtin[i % builtin.size] // offline záloha
    }

    fun isRequest(text: String) =
        Regex("\\bvtip|rozesm[ěe]j|n[ěe]co vtipn|pobav", RegexOption.IGNORE_CASE).containsMatchIn(text)
}
