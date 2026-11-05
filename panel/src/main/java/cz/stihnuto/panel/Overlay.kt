package cz.stihnuto.panel

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Hodiny nemají tlačítko Domů a „Zobrazení přes jiné aplikace“ na nich kvůli
 * malé paměti nejde zapnout. Proto panel používá službu Usnadnění (stejně
 * jako čtečka obrazovky). Když je zapnutá:
 *  - po restartu hodin spustí panel,
 *  - přes jiné aplikace ukáže v rohu tlačítko zpět do panelu,
 *  - když nic nehraje, po pár minutách se do panelu vrátí sama.
 */
object Overlay {
    private var button: View? = null
    @Volatile var panelVisible = false
    private var hiddenSince = 0L
    internal var service: PanelAccessibility? = null

    fun allowed(ctx: Context): Boolean {
        if (service != null) return true
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains(ComponentName(ctx, PanelAccessibility::class.java).flattenToShortString()) ||
            enabled.contains(ComponentName(ctx, PanelAccessibility::class.java).flattenToString())
    }

    /** Otevře nastavení Usnadnění, kde se zapne služba Panel. */
    fun requestPermission(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    fun openPanel(ctx: Context) {
        val c: Context = service ?: ctx
        c.startActivity(
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    fun onPanelShown() {
        panelVisible = true
        hide()
    }

    fun onPanelHidden(app: App) {
        panelVisible = false
        hiddenSince = System.currentTimeMillis()
        app.main.postDelayed({ if (!panelVisible) show() }, 1500)
    }

    /** Kontrola každých 30 s: návrat do panelu, když nic nehraje. */
    fun startWatchdog(app: App) {
        app.main.postDelayed(object : Runnable {
            override fun run() {
                val idle = app.settings.idleMinutes
                if (!panelVisible && idle > 0 && service != null && !app.media.isPlaying &&
                    System.currentTimeMillis() - hiddenSince > idle * 60_000L
                ) {
                    openPanel(app)
                }
                app.main.postDelayed(this, 30_000)
            }
        }, 30_000)
    }

    internal fun show() {
        val svc = service ?: return
        if (button != null || panelVisible) return
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = (Ui.unit(svc) * 13).toInt()
        val v = BackButton(svc)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = (size * 0.25f).toInt()
            y = (size * 0.25f).toInt()
        }
        try {
            wm.addView(v, lp)
            button = v
        } catch (e: Exception) {
            android.util.Log.w("Panel", "overlay", e)
        }
    }

    internal fun hide() {
        val v = button ?: return
        try {
            (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        button = null
    }

    /** Kulaté tlačítko s ikonou „mřížka panelu“. */
    private class BackButton(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val r = width / 2f
            p.color = 0xE62D2F31.toInt()
            c.drawCircle(r, r, r, p)
            p.color = Ui.PRIMARY
            val s = r * 0.32f
            val g = r * 0.1f
            for (i in 0..1) for (j in 0..1) {
                val x = r - s - g / 2 + i * (s + g)
                val y = r - s - g / 2 + j * (s + g)
                c.drawRoundRect(x, y, x + s, y + s, s * 0.3f, s * 0.3f, p)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> alpha = 0.6f
                MotionEvent.ACTION_UP -> { alpha = 1f; openPanel(context) }
                MotionEvent.ACTION_CANCEL -> alpha = 1f
            }
            return true
        }
    }
}

/** Služba Usnadnění: spuštění po restartu, tlačítko zpět, návrat do panelu. */
class PanelAccessibility : AccessibilityService() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var connectedAt = 0L

    override fun onServiceConnected() {
        Overlay.service = this
        connectedAt = System.currentTimeMillis()
        // Po startu hodin (a po aktualizaci) otevřít panel. Hodiny si po startu
        // ještě samy zapnou svůj ciferník přes něj, proto se to první minutu opakuje.
        for (delay in listOf(0L, 4_000L, 10_000L, 20_000L, 35_000L, 60_000L)) {
            handler.postDelayed({ if (!Overlay.panelVisible) Overlay.openPanel(this) }, delay)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        if (!Overlay.panelVisible) {
            Overlay.show()
            // krátce po startu: cokoli, co se ukáže přes panel, je ciferník hodin → zpět do panelu
            if (System.currentTimeMillis() - connectedAt < 90_000L) {
                handler.postDelayed({ if (!Overlay.panelVisible) Overlay.openPanel(this) }, 1500)
            }
        }
    }

    override fun onInterrupt() {}

    // --- povolení USB (měřič CO₂): systémové okno se na hodinách nemusí ukázat
    // nad panelem — najdeme ho přes Usnadnění a potvrdíme sami.
    private var usbUntil = 0L
    private val seen = linkedSetOf<String>()

    fun watchUsbDialog() {
        val first = usbUntil < System.currentTimeMillis()
        usbUntil = System.currentTimeMillis() + 20_000
        if (first) { seen.clear(); handler.post { scanUsb() } }
    }

    private fun scanUsb() {
        val co2 = App.instance.co2
        if (System.currentTimeMillis() > usbUntil) {
            co2.dialogNote(if (seen.isEmpty()) "systém žádné okno pro povolení neotevřel" else "okna: ${seen.joinToString()}")
            return
        }
        try {
            for (w in windows) {
                val root = w.root ?: continue
                val pkg = root.packageName?.toString() ?: continue
                if (pkg == packageName) continue
                seen += pkg + (if (android.os.Build.VERSION.SDK_INT >= 24) w.title?.let { " „$it“" } ?: "" else "")
                if (pkg != "com.android.systemui" && pkg != "android" && !pkg.contains("usb")) continue
                val texts = mutableListOf<String>()
                fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, d: Int) {
                    if (n == null || d > 25) return
                    n.text?.let { texts += it.toString() }
                    for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
                }
                walk(root, 0)
                val all = texts.joinToString(" ")
                if (!Regex("USB|CH554|Panel|zařízení|device", RegexOption.IGNORE_CASE).containsMatchIn(all)) continue
                co2.dialogNote("okno nalezeno: ${all.take(80)}")
                // „Používat ve výchozím nastavení“ zaškrtnout, ať se příště neptá
                fun boxes(n: android.view.accessibility.AccessibilityNodeInfo?, d: Int) {
                    if (n == null || d > 25) return
                    if (n.isCheckable && !n.isChecked) n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    for (i in 0 until n.childCount) boxes(n.getChild(i), d + 1)
                }
                boxes(root, 0)
                for (label in listOf("OK", "Ok", "Povolit", "Allow", "Ano", "Yes", "Vždy", "Always")) {
                    val b = root.findAccessibilityNodeInfosByText(label).firstOrNull { it.isClickable || it.parent?.isClickable == true }
                        ?: continue
                    (if (b.isClickable) b else b.parent).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    co2.dialogNote("potvrzeno tlačítkem „$label“")
                    usbUntil = 0
                    handler.postDelayed({ co2.connect() }, 1500)
                    return
                }
            }
        } catch (e: Exception) {
            co2.dialogNote("chyba při hledání okna: ${e.message}")
        }
        handler.postDelayed({ scanUsb() }, 600)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Overlay.hide()
        Overlay.service = null
        return super.onUnbind(intent)
    }
}
