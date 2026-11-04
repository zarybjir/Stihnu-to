package cz.stihnuto.panel.pages

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import cz.stihnuto.panel.App
import cz.stihnuto.panel.Ui
import cz.stihnuto.panel.tapo.TapoChild

/** Hlavice Tapo KE100 (cíl −/+, zap/vyp) a bezdrátové teploměry. */
class HeatingPage(ctx: Context) : Page(ctx) {
    private val app = App.instance
    private val u = Ui.unit(ctx)
    private val list = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((u * 5).toInt(), (u * 4).toInt(), (u * 5).toInt(), (u * 9).toInt())
    }
    override val view: View = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        addView(list)
    }
    private val onChange = { render() }

    override fun onShow() {
        app.heating.listen(onChange)
        app.heating.refresh()
    }

    override fun onHide() {
        app.heating.unlisten(onChange)
    }

    private fun render() {
        val repo = app.heating
        list.removeAllViews()

        val header = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(ctx, "Topení", u * 7, Ui.TEXT, Ui.MEDIUM), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Ui.button(ctx, "Obnovit", u * 4.2f) { repo.refresh() })
        list.addView(header)

        if (!repo.configured) {
            list.addView(message("Hub ještě není nastavený. Otevři na počítači http://${ipHint()}:8080 " +
                "a vyplň IP adresu hubu a přihlášení k Tapo účtu."))
            return
        }
        repo.error?.let { list.addView(message(it)) }
        if (!repo.loaded) {
            list.addView(message("Načítám…"))
            return
        }

        val valves = repo.children.filter { it.isValve }
        val sensors = repo.children.filter { it.isThermometer }
        if (valves.isEmpty() && sensors.isEmpty() && repo.error == null) {
            list.addView(message("Hub nehlásí žádné hlavice ani teploměry."))
        }

        // hlavice po dvou vedle sebe
        valves.chunked(2).forEach { pair ->
            val row = LinearLayout(ctx)
            pair.forEachIndexed { i, v ->
                row.addView(valveCard(v), LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (i == 1) leftMargin = (u * 3).toInt()
                })
            }
            if (pair.size == 1) row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = (u * 3).toInt() })
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 3).toInt() })
        }

        if (sensors.isNotEmpty()) {
            list.addView(Ui.text(ctx, "Teploměry", u * 4.5f, Ui.DIM),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 5).toInt() })
            sensors.chunked(3).forEach { group ->
                val row = LinearLayout(ctx)
                group.forEachIndexed { i, s ->
                    row.addView(sensorCard(s), LinearLayout.LayoutParams(0, -2, 1f).apply {
                        if (i > 0) leftMargin = (u * 3).toInt()
                    })
                }
                repeat(3 - group.size) {
                    row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = (u * 3).toInt() })
                }
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 2).toInt() })
            }
        }
    }

    private fun valveCard(v: TapoChild): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(if (v.heating && v.on) 0xFF2A1F1A.toInt() else Ui.CARD, u * 3,
                if (v.heating && v.on) (u * 0.5f).toInt() else 0, Ui.HEAT)
            setPadding((u * 4).toInt(), (u * 3).toInt(), (u * 4).toInt(), (u * 3).toInt())
            alpha = if (v.online) 1f else 0.5f
        }
        val top = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(ctx, v.name, u * 5.5f, Ui.TEXT, Ui.MEDIUM).apply { maxLines = 1 },
            LinearLayout.LayoutParams(0, -2, 1f))
        val state = when {
            !v.online -> "offline"
            !v.on -> "vypnuto"
            v.heating -> "topí"
            else -> ""
        }
        if (v.batteryLow) top.addView(Ui.text(ctx, "baterie ", u * 3.8f, Ui.HEAT))
        top.addView(Ui.text(ctx, state, u * 4.2f, if (v.heating) Ui.HEAT else Ui.DIM, Ui.MEDIUM))
        card.addView(top)

        card.addView(Ui.text(ctx, "teď ${Ui.num(v.currentTemp)} °C", u * 4.5f, Ui.DIM),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = (u * 1).toInt() })

        val ctl = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val b = (u * 15).toInt()
        ctl.addView(Ui.button(ctx, "−", u * 8) { app.heating.nudgeTarget(v.id, -app.settings.heatStep) },
            LinearLayout.LayoutParams(b, b))
        ctl.addView(Ui.text(ctx, if (v.on) "${Ui.num(v.targetTemp)}°" else "–", u * 10, Ui.TEXT, Ui.LIGHT).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        ctl.addView(Ui.button(ctx, "+", u * 8) { app.heating.nudgeTarget(v.id, app.settings.heatStep) },
            LinearLayout.LayoutParams(b, b))
        card.addView(ctl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 2).toInt() })

        val toggle = Ui.button(ctx, if (v.on) "Vypnout" else "Zapnout", u * 4f,
            if (v.on) Ui.CARD_HI else Ui.HEAT, if (v.on) Ui.DIM else Ui.BG) {
            app.heating.setOn(v.id, !v.on)
        }
        card.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 2).toInt() })
        return card
    }

    private fun sensorCard(s: TapoChild): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD, u * 3)
            setPadding((u * 4).toInt(), (u * 2.5f).toInt(), (u * 4).toInt(), (u * 2.5f).toInt())
            alpha = if (s.online) 1f else 0.5f
        }
        card.addView(Ui.text(ctx, s.name, u * 4.2f, Ui.DIM).apply { maxLines = 1 })
        val row = LinearLayout(ctx).apply { gravity = Gravity.BOTTOM }
        row.addView(Ui.text(ctx, "${Ui.num(s.currentTemp)}°", u * 8, Ui.TEXT, Ui.LIGHT))
        s.humidity?.let {
            row.addView(Ui.text(ctx, "  $it %", u * 4.5f, Ui.BLUE))
        }
        card.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (u * 1).toInt() })
        if (s.batteryLow) card.addView(Ui.text(ctx, "slabá baterie", u * 3.5f, Ui.HEAT))
        return card
    }

    private fun message(t: String) = Ui.text(ctx, t, u * 4.5f, Ui.DIM).apply {
        setPadding(0, (u * 3).toInt(), 0, 0)
    }

    private fun ipHint(): String = cz.stihnuto.panel.web.WebServer.localIp() ?: "<ip-hodin>"
}
