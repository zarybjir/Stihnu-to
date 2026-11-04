package cz.stihnuto.panel.pages

import android.content.Context
import android.view.View

/**
 * Jedno okno panelu.
 *
 * Nové okno přidáš takhle:
 *  1. napiš třídu, která dědí z [Page] (vzor: [ClockPage]),
 *  2. přidej jeden řádek do [Pages.all],
 *  3. v nastavení (http://<ip-hodin>:8080) ho zařaď do pořadí oken.
 */
abstract class Page(val ctx: Context) {
    abstract val view: View

    /** Okno se právě ukázalo — začni obnovovat data. */
    open fun onShow() {}

    /** Okno zmizelo — přestaň obnovovat, uvolni paměť. */
    open fun onHide() {}
}

object Pages {
    class Info(val title: String, val create: (Context) -> Page)

    val all: Map<String, Info> = linkedMapOf(
        "clock" to Info("Hodiny") { ClockPage(it) },
        "calendar" to Info("Kalendář") { CalendarPage(it) },
        "weather" to Info("Počasí") { WeatherPage(it) },
        "hourly" to Info("Po hodinách") { HourlyPage(it) },
        "radar" to Info("Radar") { RadarPage(it) },
        "music" to Info("Hudba") { MusicPage(it) },
        "heating" to Info("Topení") { HeatingPage(it) },
        "claude" to Info("Claude") { ClaudePage(it) },
        "apps" to Info("Aplikace") { AppsPage(it) },
    )
}
