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
        // adresa nastavení pro PC / mobil
        val ips = cz.stihnuto.panel.web.WebServer.localIps()
        list.addView(Ui.text(ctx, "Nastavení v prohlížeči (stejná WiFi):", u * 4f, Ui.DIM))
        list.addView(Ui.text(ctx, ips.firstOrNull()?.let { "http://${it.second}:8080" } ?: "hodiny nejsou připojené k síti",
            u * 6.5f, Ui.PRIMARY, Ui.MEDIUM))
        if (ips.size > 1) list.addView(Ui.text(ctx, "Další adresy: " +
            ips.drop(1).joinToString { "${it.second} (${it.first})" }, u * 3.6f, Ui.DIM))
        // diagnostika: proč se nastavení neotevře v prohlížeči
        val diagRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        diagText = Ui.text(ctx, diagNote, u * 3.8f, Ui.ACCENT)
        diagRow.addView(diagText, LinearLayout.LayoutParams(0, -2, 1f))
        diagRow.addView(Ui.button(ctx, "Test sítě", u * 4.2f) { runDiagnostics() })
        list.addView(diagRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 1.5f).toInt() })
        list.addView(View(ctx), LinearLayout.LayoutParams(1, (u * 4).toInt()))

        // měřič CO₂ přes USB
        val m = app.co2
        val cRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.CARD, u * 3)
            setPadding((u * 4).toInt(), (u * 3).toInt(), (u * 3).toInt(), (u * 3).toInt())
        }
        val cCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        cCol.addView(Ui.text(ctx, "Měřič CO₂ (USB)", u * 5.5f, Ui.TEXT, Ui.MEDIUM))
        val vals = listOfNotNull(
            m.co2?.let { "$it ppm" }, m.temp?.let { "${Ui.num(it)} °C" }, m.humidity?.let { "${Math.round(it)} %" },
        ).joinToString(" · ")
        cCol.addView(Ui.text(ctx, if (vals.isNotEmpty()) "$vals — ${m.status}" else m.status, u * 3.8f,
            if (m.fresh) Ui.GOOD else Ui.DIM))
        cRow.addView(cCol, LinearLayout.LayoutParams(0, -2, 1f))
        cRow.addView(Ui.button(ctx, "Připojit", u * 4.2f) { m.connect(); app.main.postDelayed({ render() }, 800) })
        list.addView(cRow, LinearLayout.LayoutParams(-1, -2))
        list.addView(View(ctx), LinearLayout.LayoutParams(1, (u * 4).toInt()))

        // poslat klíč / kalendář z mobilu přes QR kód
        list.addView(phoneBox())
        list.addView(View(ctx), LinearLayout.LayoutParams(1, (u * 4).toInt()))

        // asistent: výběr a klíč přímo na hodinách
        list.addView(assistantBox())
        list.addView(View(ctx), LinearLayout.LayoutParams(1, (u * 4).toInt()))

        // jas přes den: 0 = automatika hodin
        val br = app.settings.dayBrightness
        val bRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.CARD, u * 3)
            setPadding((u * 4).toInt(), (u * 2.5f).toInt(), (u * 3).toInt(), (u * 2.5f).toInt())
        }
        bRow.addView(Ui.text(ctx, "Jas přes den", u * 5.5f, Ui.TEXT, Ui.MEDIUM), LinearLayout.LayoutParams(0, -2, 1f))
        fun setBr(v: Int) {
            app.settings.dayBrightness = v.coerceIn(0, 100)
            (ctx as? android.app.Activity)?.window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = if (v <= 0) android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else v / 100f
                w.attributes = lp
            }
            render()
        }
        val bb = (u * 12).toInt()
        bRow.addView(Ui.button(ctx, "Auto", u * 4.2f, if (br == 0) Ui.PRIMARY else Ui.CARD_HI, if (br == 0) Ui.BG else Ui.TEXT) { setBr(0) })
        bRow.addView(Ui.button(ctx, "−", u * 6f) { setBr(if (br == 0) 70 else (br - 10).coerceAtLeast(10)) },
            LinearLayout.LayoutParams(bb, bb).apply { leftMargin = (u * 2).toInt() })
        bRow.addView(Ui.text(ctx, if (br == 0) "auto" else "$br %", u * 5f, Ui.TEXT, Ui.MEDIUM).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams((u * 16).toInt(), -2))
        bRow.addView(Ui.button(ctx, "+", u * 6f) { setBr(if (br == 0) 80 else (br + 10).coerceAtMost(100)) },
            LinearLayout.LayoutParams(bb, bb))
        list.addView(bRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (u * 5).toInt() })

        renderWindows()
        list.addView(Ui.text(ctx, "Aplikace", u * 7, Ui.TEXT, Ui.MEDIUM),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 6).toInt() })
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

        val vw = app.settings.voiceWake
        val vRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.CARD, u * 3)
            setPadding((u * 4).toInt(), (u * 3).toInt(), (u * 3).toInt(), (u * 3).toInt())
        }
        val vCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        vCol.addView(Ui.text(ctx, "Hlasová aktivace „${app.settings.wakePhrase}“", u * 5.5f, Ui.TEXT, Ui.MEDIUM))
        vCol.addView(Ui.text(ctx, "Řekni frázi a hned povel, např. „prosím Pepo, zatop v obýváku na 22“. Poslouchá se " +
            "přímo v hodinách, Claudovi jde jen povel. Poprvé stáhne český model (46 MB).", u * 3.8f, Ui.DIM))
        voiceStatus = Ui.text(ctx, cz.stihnuto.panel.VoiceWake.status, u * 3.8f, Ui.ACCENT)
        vCol.addView(voiceStatus)
        vRow.addView(vCol, LinearLayout.LayoutParams(0, -2, 1f))
        vRow.addView(Ui.button(ctx, if (vw) "Vypnout" else "Zapnout", u * 4.5f,
            if (vw) Ui.CARD_HI else Ui.ACCENT, if (vw) Ui.TEXT else Ui.BG) {
            app.settings.voiceWake = !vw
            if (!vw) cz.stihnuto.panel.VoiceWake.start(app) else cz.stihnuto.panel.VoiceWake.stop()
            render()
        })
        list.addView(vRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 3).toInt() })

        val ovl = cz.stihnuto.panel.Overlay.allowed(ctx)
        val ovlRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.CARD, u * 3)
            setPadding((u * 4).toInt(), (u * 3).toInt(), (u * 3).toInt(), (u * 3).toInt())
        }
        val ovlCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        ovlCol.addView(Ui.text(ctx, "Návrat do panelu", u * 5.5f, Ui.TEXT, Ui.MEDIUM))
        ovlCol.addView(Ui.text(ctx, if (ovl) "Zapnuto: tlačítko zpět přes jiné aplikace, spuštění po restartu, návrat po nečinnosti."
            else "Zapni službu Panel v Usnadnění: v seznamu klepni na Panel → zapnout → Povolit. Pak se panel spustí po restartu a z YouTube se vrátíš tlačítkem v rohu.",
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

        // --- úložiště a úklid starých instalaček
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val free = stat.availableBytes / 1_000_000
        val total = stat.totalBytes / 1_000_000
        val apks = downloadedApks()
        val apkMb = apks.sumOf { it.length() } / 1_000_000
        val sRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.CARD, u * 3)
            setPadding((u * 4).toInt(), (u * 3).toInt(), (u * 3).toInt(), (u * 3).toInt())
        }
        val sCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        sCol.addView(Ui.text(ctx, "Úložiště", u * 5.5f, Ui.TEXT, Ui.MEDIUM))
        sCol.addView(Ui.text(ctx, "Volno ${"%.1f".format(free / 1000.0)} GB z ${"%.1f".format(total / 1000.0)} GB" +
            if (apks.isNotEmpty()) " · staré instalačky: ${apks.size} ($apkMb MB)" else "", u * 4f, Ui.DIM))
        storageMsg = Ui.text(ctx, storageNote, u * 4f, Ui.ACCENT).also { if (storageNote.isEmpty()) it.visibility = View.GONE }
        sCol.addView(storageMsg)
        sRow.addView(sCol, LinearLayout.LayoutParams(0, -2, 1f))
        sRow.addView(Ui.button(ctx, "Smazat instalačky", u * 4.5f, Ui.CARD_HI) { cleanDownloads() })
        list.addView(sRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 3).toInt() })

        list.addView(Ui.text(ctx, "Po stažení potvrď instalaci na displeji. Poprvé Android požádá o povolení " +
            "instalovat aplikace z Panelu — povol ho a vrať se zpět.", u * 3.8f, Ui.DIM),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 4).toInt() })
        refreshStatuses()
    }

    // --- Okna: zapnout/vypnout a pořadí -----------------------------------

    private var windowsChanged = false

    /** Pořadí: zapnutá okna v nastaveném pořadí, pod nimi vypnutá. */
    private fun windowOrder(): Pair<MutableList<String>, List<String>> {
        val on = app.settings.pageList().filter { it != "apps" && it in Pages.all }.toMutableList()
        val off = Pages.all.keys.filter { it != "apps" && it !in on }
        return on to off
    }

    private fun saveWindows(on: List<String>) {
        app.settings.pages = on.joinToString(",")
        if (app.settings.homePage !in on) app.settings.homePage = on.firstOrNull() ?: "clock"
        windowsChanged = true
        render()
    }

    private fun renderWindows() {
        list.addView(Ui.text(ctx, "Okna", u * 7, Ui.TEXT, Ui.MEDIUM))
        list.addView(Ui.text(ctx, "Šipkami změníš pořadí, ★ = domovské okno, vypnutá se nezobrazují. Okno Aplikace je vždy poslední.",
            u * 3.8f, Ui.DIM), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (u * 1).toInt() })
        val (on, off) = windowOrder()
        (on + off).forEach { id ->
            val enabled = id in on
            val i = on.indexOf(id)
            val row = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(Ui.CARD, u * 3)
                setPadding((u * 4).toInt(), (u * 1.5f).toInt(), (u * 2).toInt(), (u * 1.5f).toInt())
                alpha = if (enabled) 1f else 0.55f
            }
            val home = if (id == app.settings.homePage) "  · domovské" else ""
            row.addView(Ui.text(ctx, (Pages.all[id]?.title ?: id) + home, u * 5f, Ui.TEXT, Ui.MEDIUM),
                LinearLayout.LayoutParams(0, -2, 1f))
            val b = (u * 11).toInt()
            if (enabled) {
                row.addView(Ui.button(ctx, "▲", u * 4.5f, if (i > 0) Ui.CARD_HI else Ui.CARD, if (i > 0) Ui.TEXT else Ui.FAINT) {
                    if (i > 0) { on.add(i - 1, on.removeAt(i)); saveWindows(on) }
                }, LinearLayout.LayoutParams(b, b))
                row.addView(Ui.button(ctx, "▼", u * 4.5f, if (i < on.size - 1) Ui.CARD_HI else Ui.CARD,
                    if (i < on.size - 1) Ui.TEXT else Ui.FAINT) {
                    if (i < on.size - 1) { on.add(i + 1, on.removeAt(i)); saveWindows(on) }
                }, LinearLayout.LayoutParams(b, b).apply { leftMargin = (u * 1.5f).toInt() })
                row.addView(Ui.button(ctx, "★", u * 4.5f, if (home.isNotEmpty()) Ui.PRIMARY else Ui.CARD_HI,
                    if (home.isNotEmpty()) Ui.BG else Ui.TEXT) {
                    app.settings.homePage = id; windowsChanged = true; render()
                }, LinearLayout.LayoutParams(b, b).apply { leftMargin = (u * 1.5f).toInt() })
            }
            row.addView(Ui.button(ctx, if (enabled) "Vypnout" else "Zapnout", u * 4f,
                if (enabled) Ui.CARD_HI else Ui.PRIMARY, if (enabled) Ui.TEXT else Ui.BG) {
                if (enabled) {
                    if (on.size > 1) { on.remove(id); saveWindows(on) }
                } else {
                    on.add(id); saveWindows(on)
                }
            }, LinearLayout.LayoutParams((u * 24).toInt(), -2).apply { leftMargin = (u * 2).toInt() })
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 1.5f).toInt() })
        }
    }

    private var qrBitmap: android.graphics.Bitmap? = null

    private fun phoneBox(): View {
        val box = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.CARD, u * 3)
            setPadding((u * 4).toInt(), (u * 3).toInt(), (u * 3).toInt(), (u * 3).toInt())
        }
        val inbox = cz.stihnuto.panel.PhoneInbox
        val url = inbox.url
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(ctx, "Poslat z mobilu", u * 5.5f, Ui.TEXT, Ui.MEDIUM))
        if (url == null) {
            col.addView(Ui.text(ctx, "Klíč Gemini/Claude nebo odkaz na kalendář pošleš z mobilu — bez psaní na hodinách.",
                u * 3.8f, Ui.DIM))
            box.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(Ui.button(ctx, "Zobrazit QR", u * 4.2f, Ui.ACCENT, Ui.BG) {
                inbox.onChange = { render() }
                inbox.start(app)
                render()
            })
        } else {
            col.addView(Ui.text(ctx, "1. Načti QR kód foťákem v mobilu.\n2. Na stránce klepni na tlačítko pro novou zprávu, " +
                "vlož klíč nebo odkaz a odešli.", u * 3.8f, Ui.DIM))
            col.addView(Ui.text(ctx, url, u * 3.6f, Ui.PRIMARY).apply { setTextIsSelectable(false) })
            col.addView(Ui.text(ctx, inbox.status, u * 4.2f, Ui.ACCENT, Ui.MEDIUM),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = (u * 1.5f).toInt() })
            col.addView(Ui.button(ctx, "Hotovo", u * 4.2f) {
                inbox.stop(); qrBitmap = null; render()
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (u * 2).toInt() })
            box.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            val size = (u * 38).toInt()
            val bmp = qrBitmap ?: inbox.qr(url, size).also { qrBitmap = it }
            box.addView(android.widget.ImageView(ctx).apply { setImageBitmap(bmp) },
                LinearLayout.LayoutParams(size, size).apply { leftMargin = (u * 3).toInt() })
        }
        return box
    }

    private var diagText: TextView? = null
    private var diagNote = ""

    private fun runDiagnostics() {
        diagNote = "Testuji…"; diagText?.text = diagNote
        app.io.execute {
            fun can(host: String, port: Int) = try {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 2500) }; true
            } catch (e: Exception) { false }
            val ip = cz.stihnuto.panel.web.WebServer.localIp()
            val local = can("127.0.0.1", 8080)
            val wifi = ip != null && can(ip, 8080)
            val hubIp = app.settings.hubIp.trim()
            val hub = hubIp.isNotEmpty() && can(hubIp, 80)
            val web = try { cz.stihnuto.panel.Http.text("https://api.open-meteo.com/v1/forecast?latitude=50&longitude=14&current=temperature_2m"); true } catch (e: Exception) { false }
            val verdict = when {
                !local -> "Server panelu neběží — restartuj panel."
                !wifi -> "Server běží, ale hodiny nepouští spojení zvenku na svou adresu."
                hubIp.isNotEmpty() && !hub -> "Hub $hubIp není z hodin dosažitelný — špatná IP, nebo router izoluje zařízení."
                else -> "Hodiny jsou v pořádku. Když se stránka v mobilu neotevře, router nejspíš brání spojení mezi zařízeními (izolace klientů) nebo mobil není v téže síti."
            }
            diagNote = "server ${if (local) "✓" else "✗"} · WiFi adresa ${if (wifi) "✓" else "✗"} · hub ${if (hub) "✓" else "✗"} · internet ${if (web) "✓" else "✗"}\n$verdict"
            app.main.post { diagText?.text = diagNote }
        }
    }

    /** Výběr asistenta a klíč ke Gemini — bez prohlížeče (klávesnice HeliBoard). */
    private fun assistantBox(): View {
        val s = app.settings
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD, u * 3)
            setPadding((u * 4).toInt(), (u * 3).toInt(), (u * 3).toInt(), (u * 3).toInt())
        }
        val top = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(ctx, "Asistent", u * 5.5f, Ui.TEXT, Ui.MEDIUM), LinearLayout.LayoutParams(0, -2, 1f))
        for ((key, label) in listOf("gemini" to "Gemini", "claude" to "Claude")) {
            val on = s.aiProvider == key
            top.addView(Ui.button(ctx, label, u * 4.2f, if (on) Ui.PRIMARY else Ui.CARD_HI, if (on) Ui.BG else Ui.TEXT) {
                s.aiProvider = key; render()
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (u * 2).toInt() })
        }
        box.addView(top)
        val field = android.widget.EditText(ctx).apply {
            hint = if (s.aiProvider == "gemini") (if (s.geminiKey.isNotEmpty()) "Klíč Gemini uložen — přepiš pro změnu" else "Klíč Gemini (AIza…)")
            else (if (s.anthropicKey.isNotEmpty()) "Klíč Claude uložen — přepiš pro změnu" else "Klíč Claude (sk-ant-…)")
            setTextColor(Ui.TEXT); setHintTextColor(Ui.DIM)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u * 4.5f)
            background = Ui.rounded(Ui.CARD_HI, u * 2)
            setPadding((u * 3).toInt(), (u * 2).toInt(), (u * 3).toInt(), (u * 2).toInt())
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Ui.button(ctx, "Uložit", u * 4.2f, Ui.ACCENT, Ui.BG) {
            val k = field.text.toString().trim()
            if (k.isNotEmpty()) { if (s.aiProvider == "gemini") s.geminiKey = k else s.anthropicKey = k }
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(field.windowToken, 0)
            render()
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (u * 2).toInt() })
        box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 2).toInt() })
        // model
        val models = if (s.aiProvider == "gemini") listOf("gemini-flash-lite-latest" to "Rychlý (Lite)", "gemini-flash-latest" to "Chytřejší (Flash)")
        else listOf("claude-haiku-5-5" to "Haiku (levný, rychlý)", "claude-opus-5-5" to "Opus (nejchytřejší)")
        val current = if (s.aiProvider == "gemini") s.geminiModel else s.claudeModel
        val mRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        mRow.addView(Ui.text(ctx, "Model:", u * 4f, Ui.DIM), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = (u * 2).toInt() })
        for ((id, label) in models) {
            val on = current == id
            mRow.addView(Ui.button(ctx, label, u * 3.8f, if (on) Ui.PRIMARY else Ui.CARD_HI, if (on) Ui.BG else Ui.TEXT) {
                if (s.aiProvider == "gemini") s.geminiModel = id else s.claudeModel = id
                render()
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = (u * 2).toInt() })
        }
        box.addView(mRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 2).toInt() })
        box.addView(Ui.text(ctx, "Klíč Gemini zdarma: aistudio.google.com/apikey · Klíč pošleš i přes „Poslat z mobilu“", u * 3.6f, Ui.DIM))
        return box
    }

    private var storageMsg: TextView? = null
    private var voiceStatus: TextView? = null
    private var storageNote = ""

    private fun note(t: String) {
        storageNote = t
        storageMsg?.apply { text = t; visibility = View.VISIBLE }
    }

    private fun downloadsDir() =
        @Suppress("DEPRECATION") android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)

    private fun downloadedApks(): List<java.io.File> = try {
        downloadsDir().listFiles { f -> f.isFile && f.name.endsWith(".apk", true) }?.toList().orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    /** Smaže .apk ze složky Stažené (zůstaly tam po instalaci přes prohlížeč). */
    private fun cleanDownloads() {
        val act = ctx as? android.app.Activity
        val perm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ctx.checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            act?.requestPermissions(arrayOf(perm, android.Manifest.permission.READ_EXTERNAL_STORAGE), 7)
            note("Povol přístup k souborům a klepni na Smazat znovu.")
            return
        }
        val files = downloadedApks()
        val mb = files.sumOf { it.length() } / 1_000_000
        val deleted = files.count { it.delete() }
        note(if (files.isEmpty()) "Ve složce Stažené nejsou žádné instalačky."
        else "Smazáno $deleted z ${files.size} souborů ($mb MB).")
        app.main.postDelayed({ render() }, 1500)
    }

    private var lastStatuses = ""

    private fun refreshStatuses() {
        val vw = cz.stihnuto.panel.VoiceWake
        val ago = (System.currentTimeMillis() - vw.lastHeardAt) / 1000
        voiceStatus?.text = vw.status + if (vw.running) {
            if (vw.lastHeardAt == 0L) "\nZatím nic neslyšel — zkus mluvit nahlas u hodin (mikrofon možná drží Google)."
            else "\nNaposledy slyšeno (před $ago s): „${vw.lastHeard}“"
        } else ""
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
        cz.stihnuto.panel.PhoneInbox.stop()
        qrBitmap = null
        // změny oken se projeví po odchodu z tohoto okna
        if (windowsChanged) {
            windowsChanged = false
            app.main.post { app.navigator?.invoke("__reload__") }
        }
    }
}
