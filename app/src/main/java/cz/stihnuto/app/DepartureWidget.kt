package cz.stihnuto.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class DepartureWidget : AppWidgetProvider() {

    // System tick (every 30 min) and right after a widget is added.
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.renderAll(context)
        refreshAsync(context, force = false)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) Store.removeWidget(context, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            WidgetUpdater.ACTION_REFRESH -> refreshAsync(context, force = true)
            WidgetUpdater.ACTION_RERENDER -> {
                WidgetUpdater.renderAll(context)
                if (WidgetUpdater.needsData(context)) refreshAsync(context, force = false)
            }
        }
    }

    private fun refreshAsync(context: Context, force: Boolean) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                WidgetUpdater.refreshBlocking(app, force)
            } catch (e: Exception) {
                // never crash the launcher's broadcast
            } finally {
                pending.finish()
            }
        }.start()
    }
}
