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
    const val URL = "https://github.com/zarybjir/Hodiny/releases/download/panel-latest/panel.apk"

    val catalog: List<Installable> by lazy {
        listOf(
            Installable(
                "panel", "Panel", "Tahle aplikace — nejnovější verze z GitHubu.",
                "cz.stihnuto.panel",
            ) { URL },
            Installable(
                "newpipe", "NewPipe", "Hudba a videa z YouTube bez reklam (open source) — přes něj hraje hudba.",
                "org.schabi.newpipe",
            ) { githubApk("TeamNewPipe/NewPipe") },
            Installable(
                "heliboard", "Klávesnice HeliBoard", "Klávesnice na displeji — pro přihlášení a hledání (open source).",
                "helium314.keyboard",
            ) { githubApk("Helium314/HeliBoard", prefer = listOf("release"), avoid = listOf("debug", "nouserlib")) },
        )
    }

    private val statuses = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Co se právě stahuje — další klepnutí na „Aktualizovat“ nesmí spustit druhé stahování. */
    private val busy = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Větší APK než tohle je určitě chyba (panel má ~35 MB). */
    private const val MAX_APK = 300L * 1024 * 1024

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
        if (!busy.add(item.key)) return // už se stahuje
        statuses[item.key] = "Hledám nejnovější verzi…"
        app.io.execute {
            val installer = app.packageManager.packageInstaller
            var sessionId = -1
            try {
                val url = item.apkUrl()
                if (!url.startsWith("https://github.com/")) throw IllegalStateException("nečekaná adresa ke stažení")
                statuses[item.key] = "Stahuji…"
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(item.packageName) }
                sessionId = installer.createSession(params)
                var size = 0L
                installer.openSession(sessionId).use { s ->
                    // Rovnou z internetu do instalátoru — desítky MB se nedrží v paměti hodin.
                    Http.open(url, 120_000) { input, total, conn ->
                        // GitHub přesměrovává na své úložiště (jeho adresa se časem mění) — stačí trvat
                        // na šifrovaném spojení; pravost aktualizace hlídá Android podpisem aplikace.
                        if (conn.url.protocol != "https") throw IllegalStateException("stahování není šifrované")
                        if (total > MAX_APK) throw IllegalStateException("soubor je podezřele velký")
                        s.openWrite("app.apk", 0, if (total > 0) total else -1).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var shown = -1L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                size += n
                                if (size > MAX_APK) throw IllegalStateException("soubor je podezřele velký")
                                out.write(buf, 0, n)
                                val mb = size / 1024 / 1024
                                if (mb != shown) {
                                    shown = mb
                                    statuses[item.key] = if (total > 0) "Stahuji… ${100 * size / total} % ($mb z ${total / 1024 / 1024} MB)"
                                    else "Stahuji… $mb MB"
                                }
                            }
                            s.fsync(out)
                        }
                        if (total > 0 && size != total) throw java.io.IOException("stahování se přerušilo")
                    }
                    if (size < 100_000) throw java.io.IOException("stažený soubor je neúplný")
                    val pi = PendingIntent.getBroadcast(
                        app, sessionId, Intent(app, InstallResult::class.java).putExtra("key", item.key),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    s.commit(pi.intentSender)
                }
                sessionId = -1 // předáno systému, dál se o ni stará on
                statuses[item.key] = "Staženo (${size / 1024 / 1024} MB) — potvrď instalaci na displeji."
            } catch (e: Exception) {
                Log.w("Panel", "install ${item.key}", e)
                statuses[item.key] = "Nepovedlo se: ${e.message ?: e.javaClass.simpleName}"
                // nedokončenou instalaci zahodit, ať nezabírá místo v úložišti
                if (sessionId >= 0) try { installer.abandonSession(sessionId) } catch (_: Exception) {}
            } finally {
                busy.remove(item.key)
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
