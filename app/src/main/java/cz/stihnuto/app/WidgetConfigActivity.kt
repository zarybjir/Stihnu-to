package cz.stihnuto.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Shown when a widget is placed (or reconfigured): pick which card it shows. */
class WidgetConfigActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad * 2, pad, pad)
        root.setBackgroundColor(C_BG)

        val title = TextView(this)
        title.text = getString(R.string.widget_pick_title)
        title.setTextColor(C_TEXT)
        title.textSize = 20f
        title.setPadding(0, 0, 0, pad)
        root.addView(title)

        val cards = Store.cards(this)
        if (cards.isEmpty()) {
            val msg = TextView(this)
            msg.text = "Zatím tu není žádná karta. Otevři aplikaci, vytvoř kartu se zastávkou " +
                "a pak widget přidej znovu."
            msg.setTextColor(C_MUTED)
            msg.textSize = 15f
            root.addView(msg)

            val btn = Button(this)
            btn.text = "Otevřít aplikaci"
            btn.setOnClickListener {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            root.addView(btn)
        } else {
            val list = ListView(this)
            list.adapter = object : ArrayAdapter<Card>(
                this, android.R.layout.simple_list_item_2, android.R.id.text1, cards
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = super.getView(position, convertView, parent)
                    val card = cards[position]
                    val t1 = row.findViewById<TextView>(android.R.id.text1)
                    t1.text = card.name
                    t1.setTextColor(C_TEXT)
                    val t2 = row.findViewById<TextView>(android.R.id.text2)
                    t2.text = card.stopName
                    t2.setTextColor(C_MUTED)
                    return row
                }
            }
            list.setOnItemClickListener { _, _, position, _ -> pick(cards[position]) }
            root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun pick(card: Card) {
        Store.setWidgetCard(this, widgetId, card.id)
        WidgetUpdater.render(this, AppWidgetManager.getInstance(this), widgetId, null)
        // fetch fresh departures in the background
        sendBroadcast(Intent(this, DepartureWidget::class.java).setAction(WidgetUpdater.ACTION_REFRESH))
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    companion object {
        private val C_BG = 0xFF121820.toInt()
        private val C_TEXT = 0xFFF1EDE4.toInt()
        private val C_MUTED = 0xFF8A94A0.toInt()
    }
}
