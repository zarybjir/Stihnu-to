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

    private var form: View? = null

    private fun render() {
        val repo = app.heating
        // během psaní do formuláře nepřekreslovat (smazalo by se, co je napsané)
        if ((showForm || !repo.configured) && form?.parent == list) return
        form = null
        list.removeAllViews()

        val header = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(ctx, "Topení", u * 7, Ui.TEXT, Ui.MEDIUM), LinearLayout.LayoutParams(0, -2, 1f))
        if (repo.configured) header.addView(Ui.button(ctx, "Přihlášení", u * 4.2f) {
            showForm = !showForm
            if (!showForm) { list.removeView(form); form = null }
            render()
        },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = (u * 2).toInt() })
        header.addView(Ui.button(ctx, "Obnovit", u * 4.2f) { repo.refresh() })
        list.addView(header)

        if (!repo.configured || showForm) {
            form = loginForm().also { list.addView(it) }
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

        if (valves.isNotEmpty()) list.addView(scenesRow(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 3).toInt() })
        // hlavice pod sebou, každá přes celou šířku
        valves.forEach { v ->
            list.addView(valveCard(v), LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 3).toInt() })
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

    private var cloudLog = ""

    /** Zkouška ovládání přes cloud TP-Link (mimo domácí síť). */
    private fun cloudTest() {
        cloudLog = "Zkouším cloud TP-Link…"
        render()
        val s = app.settings
        app.io.execute {
            val out = try {
                cz.stihnuto.panel.tapo.TapoCloud(s.tapoUser.trim(), s.tapoPass).test()
            } catch (e: Exception) {
                "chyba: ${e.message}"
            }
            app.main.post { cloudLog = out; render() }
        }
    }

    private var showForm = false

    /** Přihlášení k hubu přímo na hodinách (klávesnice HeliBoard z okna Aplikace). */
    private fun loginForm(): View {
        val s = app.settings
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(message("Přihlášení k hubu (Kasa KH100 / Tapo H100). Stejné údaje jako v aplikaci Kasa/Tapo. " +
            "Jde to i z mobilu na http://${ipHint()}:8080."))
        fun input(label: String, value: String, password: Boolean = false) = android.widget.EditText(ctx).apply {
            hint = label
            setText(value)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.DIM)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u * 5)
            background = Ui.rounded(Ui.CARD, u * 2)
            setPadding((u * 3).toInt(), (u * 2).toInt(), (u * 3).toInt(), (u * 2).toInt())
            isSingleLine = true
            inputType = if (password) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val ip = input("IP adresa hubu", s.hubIp)
        val user = input("E-mail k účtu Kasa / Tapo", s.tapoUser)
        val pass = input(if (s.tapoPass.isNotEmpty()) "Heslo (uložené — nech prázdné)" else "Heslo", "", password = true)
        val lp = { LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 2).toInt() } }
        col.addView(ip, lp()); col.addView(user, lp()); col.addView(pass, lp())
        col.addView(Ui.button(ctx, "Uložit a připojit", u * 5, Ui.PRIMARY, Ui.BG) {
            s.hubIp = ip.text.toString().trim()
            s.tapoUser = user.text.toString().trim()
            if (pass.text.isNotEmpty()) s.tapoPass = pass.text.toString()
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(pass.windowToken, 0)
            showForm = false
            list.removeView(form)
            form = null
            app.heating.reset()
            render()
        }, lp())
        return col
    }

    /** Podélná dlaždice hlavice: vlevo název a teplota, vpravo − cíl + a Zapnout/Vypnout. */
    private fun valveCard(v: TapoChild): View {
        val hot = v.heating && v.on
        val card = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(if (hot) Ui.SKY_BG else Ui.CARD, u * 4, if (hot) (u * 0.6f).toInt() else 0, Ui.SKY)
            setPadding((u * 4).toInt(), (u * 3).toInt(), (u * 4).toInt(), (u * 3).toInt())
            alpha = if (v.online) 1f else 0.5f
        }
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(Ui.text(ctx, v.name.replaceFirstChar { it.uppercase() }, u * 6f, Ui.TEXT, Ui.MEDIUM).apply { maxLines = 1 })
        val sub = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        sub.addView(Ui.text(ctx, "Aktuálně ${Ui.num(v.currentTemp)} °C", u * 4.2f, Ui.DIM))
        val state = when {
            !v.online -> "offline"
            !v.on -> "vypnuto"
            v.heating -> "topí"
            else -> ""
        }
        if (state.isNotEmpty()) sub.addView(Ui.text(ctx, state, u * 4.2f, if (hot) Ui.ON_SKY else Ui.DIM, Ui.MEDIUM).apply {
            if (hot) {
                background = Ui.rounded(Ui.SKY, u * 3)
                setPadding((u * 2.5f).toInt(), (u * 0.6f).toInt(), (u * 2.5f).toInt(), (u * 0.8f).toInt())
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (u * 3).toInt() })
        if (v.batteryLow) sub.addView(Ui.text(ctx, "slabá baterie", u * 3.8f, Ui.HEAT),
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (u * 3).toInt() })
        left.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (u * 1).toInt() })
        card.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        val b = (u * 14).toInt()
        fun round(label: String, bg: Int, fg: Int, onTap: () -> Unit) = Ui.button(ctx, label, u * 7, bg, fg, onTap).apply {
            background = Ui.rounded(bg, b / 2f)
            setPadding(0, 0, 0, 0)
        }
        card.addView(round("−", Ui.CARD_HI, Ui.TEXT) { app.heating.nudgeTarget(v.id, -app.settings.heatStep) },
            LinearLayout.LayoutParams(b, b))
        card.addView(Ui.text(ctx, if (v.on) "${Ui.num(v.targetTemp)}°" else "–", u * 10, if (v.on) Ui.TEXT else Ui.DIM, Ui.REGULAR)
            .apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams((u * 30).toInt(), -2))
        card.addView(round("+", if (hot) Ui.SKY else Ui.CARD_HI, if (hot) Ui.ON_SKY else Ui.TEXT) {
            app.heating.nudgeTarget(v.id, app.settings.heatStep)
        }, LinearLayout.LayoutParams(b, b))
        card.addView(Ui.button(ctx, if (v.on) "Vypnout" else "Zapnout", u * 4.5f,
            if (v.on) Ui.CARD_HI else Ui.SKY, if (v.on) Ui.TEXT else Ui.ON_SKY) {
            app.heating.setOn(v.id, !v.on)
        }.apply { background = Ui.rounded(if (v.on) Ui.CARD_HI else Ui.SKY, b / 2f) },
            LinearLayout.LayoutParams((u * 24).toInt(), b).apply { leftMargin = (u * 3).toInt() })
        return card
    }

    /** Řada scén: klepnutí = nastavit všechny hlavice, podržení = uložit současné nastavení. */
    private fun scenesRow(): View {
        val repo = app.heating
        val active = repo.activeScene()?.name
        val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        repo.scenes().forEachIndexed { i, s ->
            val on = s.name == active
            val label = if (s.off) s.name else "${s.name}  ${Ui.num(repo.sceneLabelTemp(s))}°"
            val b = Ui.button(ctx, label, u * 4.4f, if (on) Ui.SKY else Ui.CARD_HI, if (on) Ui.ON_SKY else Ui.TEXT) {
                repo.applyScene(s.name)
            }.apply {
                background = Ui.rounded(if (on) Ui.SKY else Ui.CARD_HI, u * 4.5f)
                setPadding((u * 4).toInt(), (u * 1.8f).toInt(), (u * 4).toInt(), (u * 1.8f).toInt())
                if (!s.off) setOnLongClickListener {
                    val ok = repo.saveScene(s.name)
                    android.widget.Toast.makeText(ctx,
                        if (ok) "Scéna ${s.name} uložena" else "Nejdřív zapni a nastav hlavice", android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
            }
            row.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = (u * 2.5f).toInt() })
        }
        row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(Ui.text(ctx, "podrž = uložit", u * 3.6f, Ui.DIM))
        return row
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
