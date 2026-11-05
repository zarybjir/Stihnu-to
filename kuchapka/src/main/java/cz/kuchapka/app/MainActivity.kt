package cz.kuchapka.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.WindowManager
import java.net.HttpURLConnection
import java.net.URL

/** Kuchapka jako aplikace: webová Kuchapka běží uvnitř z vlastních souborů. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(0xFFFAFAFA.toInt())
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
        }
        web.addJavascriptInterface(Bridge(this), "KuchapkaAndroid")

        web.webViewClient = object : WebViewClient() {
            // Soubory appky se servírují z https adresy (localStorage a fetch fungují jako v prohlížeči).
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.host != HOST || !u.path.orEmpty().startsWith("/web/")) return null
                val path = u.path!!.removePrefix("/")
                return try {
                    WebResourceResponse(mime(path), "utf-8", assets.open(path)).apply {
                        responseHeaders = mapOf("Cache-Control" to "no-cache")
                    }
                } catch (e: Exception) {
                    WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (e: ActivityNotFoundException) {}
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // „Obnovit ze zálohy“ — výběr souboru
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                return try { startActivityForResult(i, PICK); true } catch (e: Exception) { fileCallback = null; false }
            }
        }
        if (savedInstanceState == null) web.loadUrl(START) else web.restoreState(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        PollJob.schedule(this)
        checkUpdate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        PollJob.clearNotification(this)
        web.evaluateJavascript("typeof pollHodiny==='function'&&pollHodiny()", null)
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PICK) {
            val uri = data?.data
            fileCallback?.onReceiveValue(if (resultCode == RESULT_OK && uri != null) arrayOf(uri) else null)
            fileCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // web si vede historii (recept → seznam), zpět jde po ní
        if (web.canGoBack()) web.goBack() else finish()
    }

    fun keepScreenOn(on: Boolean) = runOnUiThread {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Nová verze na GitHubu → nabídnout stažení. */
    private fun checkUpdate() {
        Thread {
            try {
                val c = URL(UPDATE_INFO).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val txt = c.inputStream.bufferedReader().readText()
                val latest = Regex("versionCode[= ](\\d+)").find(txt)?.groupValues?.get(1)?.toIntOrNull() ?: return@Thread
                val mine = packageManager.getPackageInfo(packageName, 0).let {
                    if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode
                }
                if (latest > mine) runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    AlertDialog.Builder(this)
                        .setTitle("Nová verze Kuchapky")
                        .setMessage("Je k dispozici novější verze aplikace. Recepty zůstanou zachované.")
                        .setPositiveButton("Stáhnout") { _, _ ->
                            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL))) } catch (e: Exception) {}
                        }
                        .setNegativeButton("Později", null)
                        .show()
                }
            } catch (e: Exception) {
                // bez internetu nic
            }
        }.start()
    }

    private fun mime(p: String) = when (p.substringAfterLast('.')) {
        "html" -> "text/html"
        "js" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val START = "https://$HOST/web/index.html"
        private const val PICK = 7
        const val APK_URL = "https://github.com/zarybjir/Stihnu-to/releases/download/kuchapka-latest/kuchapka.apk"
        const val UPDATE_INFO = "https://github.com/zarybjir/Stihnu-to/releases/download/kuchapka-latest/version.txt"
    }
}
