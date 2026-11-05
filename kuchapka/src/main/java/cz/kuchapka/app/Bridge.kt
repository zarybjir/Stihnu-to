package cz.kuchapka.app

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import java.io.File

/** Co web Kuchapky v aplikaci volá přes window.KuchapkaAndroid. */
class Bridge(private val act: MainActivity) {
    /** Schránka hodin (ntfy) — aby aplikace hlídala nové položky i na pozadí. */
    @JavascriptInterface
    fun setTopic(topic: String) {
        PollJob.prefs(act).edit().putString("topic", topic).apply()
        PollJob.schedule(act)
    }

    @JavascriptInterface
    fun keepScreenOn(on: Boolean) = act.keepScreenOn(on)

    @JavascriptInterface
    fun share(text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        act.runOnUiThread { act.startActivity(Intent.createChooser(i, "Sdílet nákupní seznam")) }
    }

    /** Uloží zálohu do Stažených souborů; vrací popis místa, nebo "" při chybě. */
    @JavascriptInterface
    fun saveBackup(name: String, json: String): String = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
            }
            val uri = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            act.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, name).writeText(json)
        }
        "Stažených souborů ($name)"
    } catch (e: Exception) {
        ""
    }
}
