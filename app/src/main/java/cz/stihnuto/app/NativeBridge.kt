package cz.stihnuto.app

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONArray

/** Exposed to the web app as window.StihnuNative. Runs on a WebView binder thread. */
class NativeBridge(private val ctx: Context) {

    @JavascriptInterface
    fun saveData(key: String, json: String) {
        if (key != "cards" && key != "settings") return
        Store.saveRaw(ctx, key, json)
        WidgetUpdater.renderAll(ctx)
    }

    /** The app already fetched these — reuse them so the widget costs no extra API calls. */
    @JavascriptInterface
    fun saveDepartures(stopName: String, json: String) {
        val items = try {
            Store.parseSlim(JSONArray(json))
        } catch (e: Exception) {
            return
        }
        Store.saveDepartures(ctx, stopName, items)
        WidgetUpdater.renderAll(ctx)
    }
}
