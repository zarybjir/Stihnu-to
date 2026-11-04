package cz.stihnuto.panel

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Stáhne nejnovější verzi panelu z GitHubu a nainstaluje ji přes starou.
 * Android se jednou zeptá na potvrzení přímo na displeji.
 */
object Updater {
    const val URL = "https://github.com/zarybjir/Stihnu-to/releases/download/panel-latest/panel.apk"

    @Volatile var status: String = ""
        private set

    fun update(app: App) {
        status = "Stahuji…"
        app.io.execute {
            try {
                val apk = Http.bytes(URL, 60_000)
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(app.packageName) }
                val id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    s.openWrite("panel.apk", 0, apk.size.toLong()).use { it.write(apk); s.fsync(it) }
                    val pi = PendingIntent.getBroadcast(
                        app, id, Intent(app, InstallResult::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    s.commit(pi.intentSender)
                }
                status = "Staženo (${apk.size / 1024} kB) — potvrď instalaci na hodinách."
            } catch (e: Exception) {
                Log.w("Panel", "update", e)
                status = "Aktualizace selhala: ${e.message}"
            }
        }
    }

    class InstallResult : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    confirm?.let(context::startActivity)
                }
                PackageInstaller.STATUS_SUCCESS -> status = "Nainstalováno."
                else -> status = "Instalace selhala: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
            }
        }
    }
}
