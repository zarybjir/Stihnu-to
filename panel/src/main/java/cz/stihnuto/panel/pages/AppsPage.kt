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
