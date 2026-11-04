package cz.stihnuto.panel

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import org.json.JSONObject

/** Aplikace, kterou panel umí sám stáhnout a nainstalovat (i aktualizovat). */
class Installable(
    val key: String,
    val name: String,
    val description: String,
    val packageName: String,
    /** Vrátí adresu APK ke stažení (může se ptát GitHubu — volá se v pozadí). */
    val apkUrl: () -> String,
)

/**
 * Stahuje a instaluje aplikace přímo na hodinách. Android se na displeji
 * jednou zeptá na potvrzení (a poprvé na povolení instalace z panelu).
 */
object Updater {
    const val URL = "https://github.com/zarybjir/Stihnu-to/releases/download/panel-latest/panel.apk"

    val catalog: List<Installable> by lazy {
        listOf(
            Installable(
                "panel", "Panel", "Tahle aplikace — nejnovější verze z GitHubu.",
                "cz.stihnuto.panel",
            ) { URL },
            Installable(
                "simpmusic", "SimpMusic", "YouTube Music bez reklam (open source).",
                "com.maxrave.simpmusic",
            ) { githubApk("maxrave-dev/SimpMusic", prefer = listOf("foss")) },
            Installable(
                "newpipe", "NewPipe", "Videa z YouTube bez reklam (open source).",
                "org.schabi.newpipe",
            ) { githubApk("TeamNewPipe/NewPipe") },
            Installable(
                "heliboard", "Klávesnice HeliBoard", "Klávesnice na displeji — pro přihlášení a hledání (open source).",
                "helium314.keyboard",
            ) { githubApk("Helium314/HeliBoard", prefer = listOf("release"), avoid = listOf("debug", "nouserlib")) },
        )
    }

    private val statuses = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val sessionKeys = java.util.concurrent.ConcurrentHashMap<Int, String>()

    fun status(key: String): String = statuses[key] ?: ""

    /** Stav aktualizace panelu (pro webové nastavení). */
    val status: String get() = status("panel")

    fun update(app: App) = install(app, catalog.first { it.key == "panel" })

    // --- Panel jako hlavní (domovská) obrazovka hodin -------------------

    private fun homeAlias(ctx: Context) =
        android.content.ComponentName(ctx, "cz.stihnuto.panel.HomeAlias")

    fun isHomeEnabled(ctx: Context) = ctx.packageManager.getComponentEnabledSetting(homeAlias(ctx)) ==
        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Zapne/vypne panel jako domovskou obrazovku a otevře volbu, kterou Android nabídne. */
    fun setHome(ctx: Context, enabled: Boolean) {
        ctx.packageManager.setComponentEnabledSetting(
            homeAlias(ctx),
            if (enabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
        ctx.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun installedVersion(ctx: Context, pkg: String): String? = try {
        ctx.packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    } catch (e: Exception) {
        null
    }

    fun install(app: App, item: Installable) {
        statuses[item.key] = "Hledám nejnovější verzi…"
        app.io.execute {
            try {
                val url = item.apkUrl()
                statuses[item.key] = "Stahuji…"
                val apk = Http.bytes(url, 120_000)
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(item.packageName) }
                val id = installer.createSession(params)
                sessionKeys[id] = item.key
                installer.openSession(id).use { s ->
                    s.openWrite("app.apk", 0, apk.size.toLong()).use { it.write(apk); s.fsync(it) }
                    val pi = PendingIntent.getBroadcast(
                        app, id, Intent(app, InstallResult::class.java).putExtra("key", item.key),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    s.commit(pi.intentSender)
                }
                statuses[item.key] = "Staženo (${apk.size / 1024 / 1024} MB) — potvrď instalaci na displeji."
            } catch (e: Exception) {
                Log.w("Panel", "install ${item.key}", e)
                statuses[item.key] = "Nepovedlo se: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Najde v posledním vydání na GitHubu APK pro procesor hodin. */
    private fun githubApk(repo: String, prefer: List<String> = emptyList(), avoid: List<String> = emptyList()): String {
        val rel = JSONObject(Http.text("https://api.github.com/repos/$repo/releases/latest"))
        val assets = rel.getJSONArray("assets")
        val names = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.getString("name").endsWith(".apk", ignoreCase = true) }
        if (names.isEmpty()) throw IllegalStateException("$repo nemá APK ke stažení")
        val abis = Build.SUPPORTED_ABIS.toList()
        val otherAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").filter { it !in abis }
        fun score(n: String): Int {
            val s = n.lowercase()
            if (otherAbis.any { s.contains(it) } || s.contains("vivo")) return -100
            var p = 0
            abis.forEachIndexed { i, abi -> if (s.contains(abi)) p += 10 - i }
            if (s.contains("universal")) p += 3
            prefer.forEach { if (s.contains(it)) p += 2 }
            avoid.forEach { if (s.contains(it)) p -= 2 }
            return p
        }
        val best = names.maxByOrNull { score(it.getString("name")) }!!
        if (score(best.getString("name")) < 0) throw IllegalStateException("Žádná verze pro tento procesor")
        return best.getString("browser_download_url")
    }

    class InstallResult : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val key = intent.getStringExtra("key") ?: "panel"
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    confirm?.let(context::startActivity)
                }
                PackageInstaller.STATUS_SUCCESS -> statuses[key] = "Nainstalováno."
                PackageInstaller.STATUS_FAILURE_ABORTED -> statuses[key] = "Instalace zrušena."
                else -> statuses[key] =
                    "Instalace selhala: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
            }
        }
    }
}
