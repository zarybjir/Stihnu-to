package cz.stihnuto.panel.pages

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cz.stihnuto.panel.App
import cz.stihnuto.panel.Ui
import cz.stihnuto.panel.Updater

/** Instalace a aktualizace jedním klepnutím: panel, YouTube Music, YouTube. */
class AppsPage(ctx: Context) : Page(ctx) {
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
    private val statusViews = mutableMapOf<String, TextView>()
    private val tick = object : Runnable {
        override fun run() {
            refreshStatuses()
            app.main.postDelayed(this, 1000)
        }
    }

    private fun render() {
        list.removeAllViews()
        statusViews.clear()
        list.addView(Ui.text(ctx, "Aplikace", u * 7, Ui.TEXT, Ui.MEDIUM))
        for (item in Updater.catalog) {
            val installed = Updater.installedVersion(ctx, item.packageName)
            val row = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(Ui.CARD, u * 3)
                setPadding((u * 4).toInt(), (u * 3).toInt(), (u * 3).toInt(), (u * 3).toInt())
            }
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(Ui.text(ctx, item.name + (installed?.let { "  ·  $it" } ?: ""), u * 5.5f, Ui.TEXT, Ui.MEDIUM))
            col.addView(Ui.text(ctx, item.description, u * 4f, Ui.DIM))
            val st = Ui.text(ctx, "", u * 4f, Ui.ACCENT)
            statusViews[item.key] = st
            col.addView(st)
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

            val label = when {
                item.key == "panel" -> "Aktualizovat"
                installed == null -> "Nainstalovat"
                else -> "Aktualizovat"
            }
            row.addView(Ui.button(ctx, label, u * 4.5f, Ui.ACCENT, Ui.BG) { Updater.install(app, item) })
            if (installed != null && item.key != "panel") {
                row.addView(Ui.button(ctx, "Otevřít", u * 4.5f) {
                    ctx.packageManager.getLaunchIntentForPackage(item.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(ctx::startActivity)
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (u * 2).toInt() })
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 3).toInt() })
        }
        if (Updater.installedVersion(ctx, "helium314.keyboard") != null) {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            val enabled = imm.enabledInputMethodList.any { it.packageName == "helium314.keyboard" }
            val kRow = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(Ui.CARD, u * 3)
                setPadding((u * 4).toInt(), (u * 3).toInt(), (u * 3).toInt(), (u * 3).toInt())
            }
            val kCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            kCol.addView(Ui.text(ctx, "Klávesnice", u * 5.5f, Ui.TEXT, Ui.MEDIUM))
            kCol.addView(Ui.text(ctx, if (enabled) "Zapnutá. Tlačítkem „Vybrat“ ji nastav jako hlavní."
                else "1) Zapnout: v seznamu zapni HeliBoard a vrať se. 2) Vybrat: zvol HeliBoard.", u * 4f, Ui.DIM))
            kRow.addView(kCol, LinearLayout.LayoutParams(0, -2, 1f))
            kRow.addView(Ui.button(ctx, "Zapnout", u * 4.5f, if (enabled) Ui.CARD_HI else Ui.ACCENT, if (enabled) Ui.TEXT else Ui.BG) {
                try {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    statusViews["heliboard"]?.text = "Nastavení klávesnic se nedá otevřít."
                }
            })
            kRow.addView(Ui.button(ctx, "Vybrat", u * 4.5f, if (enabled) Ui.ACCENT else Ui.CARD_HI, if (enabled) Ui.BG else Ui.TEXT) {
                imm.showInputMethodPicker()
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (u * 2).toInt() })
            list.addView(kRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 3).toInt() })
        }

        val ovl = cz.stihnuto.panel.Overlay.allowed(ctx)
        val ovlRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.CARD, u * 3)
            setPadding((u * 4).toInt(), (u * 3).toInt(), (u * 3).toInt(), (u * 3).toInt())
        }
        val ovlCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        ovlCol.addView(Ui.text(ctx, "Návrat do panelu", u * 5.5f, Ui.TEXT, Ui.MEDIUM))
        ovlCol.addView(Ui.text(ctx, if (ovl) "Zapnuto: tlačítko zpět přes jiné aplikace, spuštění po restartu, návrat po nečinnosti."
            else "Povol „Zobrazení přes jiné aplikace“ — pak se panel spustí i po restartu a z YouTube se vrátíš tlačítkem v rohu.",
            u * 4f, Ui.DIM))
        ovlRow.addView(ovlCol, LinearLayout.LayoutParams(0, -2, 1f))
        if (!ovl) ovlRow.addView(Ui.button(ctx, "Povolit", u * 4.5f, Ui.ACCENT, Ui.BG) {
            cz.stihnuto.panel.Overlay.requestPermission(ctx)
        })
        list.addView(ovlRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 3).toInt() })

        val home = Updater.isHomeEnabled(ctx)
        val homeRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.CARD, u * 3)
            setPadding((u * 4).toInt(), (u * 3).toInt(), (u * 3).toInt(), (u * 3).toInt())
        }
        val homeCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        homeCol.addView(Ui.text(ctx, "Hlavní obrazovka", u * 5.5f, Ui.TEXT, Ui.MEDIUM))
        homeCol.addView(Ui.text(ctx, if (home) "Panel je hlavní obrazovka. Původní hodiny se dají vrátit."
            else "Hodiny se vrací na původní obrazovku. Nastav panel jako hlavní — v nabídce zvol Panel a „Vždy“.",
            u * 4f, Ui.DIM))
        homeRow.addView(homeCol, LinearLayout.LayoutParams(0, -2, 1f))
        homeRow.addView(Ui.button(ctx, if (home) "Vrátit původní" else "Nastavit panel", u * 4.5f,
            if (home) Ui.CARD_HI else Ui.ACCENT, if (home) Ui.TEXT else Ui.BG) {
            Updater.setHome(ctx, !home)
            app.main.postDelayed({ render() }, 500)
        })
        list.addView(homeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 3).toInt() })

        list.addView(Ui.text(ctx, "Po stažení potvrď instalaci na displeji. Poprvé Android požádá o povolení " +
            "instalovat aplikace z Panelu — povol ho a vrať se zpět.", u * 3.8f, Ui.DIM),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 4).toInt() })
        refreshStatuses()
    }

    private var lastStatuses = ""

    private fun refreshStatuses() {
        val all = Updater.catalog.joinToString("|") { Updater.status(it.key) }
        for (item in Updater.catalog) statusViews[item.key]?.let { v ->
            val s = Updater.status(item.key)
            v.text = s
            v.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
        }
        // po dokončení instalace překreslit (verze, tlačítka)
        if (all != lastStatuses && all.contains("Nainstalováno")) {
            lastStatuses = all
            render()
        }
        lastStatuses = all
    }

    override fun onShow() {
        render()
        app.main.post(tick)
    }

    override fun onHide() {
        app.main.removeCallbacks(tick)
    }
}
