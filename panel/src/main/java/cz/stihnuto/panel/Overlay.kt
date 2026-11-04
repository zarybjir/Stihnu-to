package cz.stihnuto.panel

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Hodiny nemají tlačítko Domů. Když je panel schovaný (běží YouTube Music,
 * nebo se hodiny vrátily na původní ciferník), ukáže se v rohu malé
 * tlačítko zpět do panelu. Po pár minutách bez hudby se panel vrátí sám.
 * Vyžaduje oprávnění „Zobrazení přes jiné aplikace“.
 */
object Overlay {
    private var button: View? = null
    @Volatile var panelVisible = false
    private var hiddenSince = 0L

    fun allowed(ctx: Context) = Settings.canDrawOverlays(ctx)

    /** Otevře systémové nastavení oprávnění pro panel. */
    fun requestPermission(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }

    fun openPanel(ctx: Context) {
        ctx.startActivity(
            Intent(ctx, MainActivity::class.java)
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
        // malá prodleva — při přepnutí uvnitř panelu (např. potvrzení instalace) neblikat
        app.main.postDelayed({ if (!panelVisible) show(app) }, 1500)
    }

    /** Kontrola každých 30 s: návrat do panelu, když nic nehraje. */
    fun startWatchdog(app: App) {
        app.main.postDelayed(object : Runnable {
            override fun run() {
                val idle = app.settings.idleMinutes
                if (!panelVisible && idle > 0 && allowed(app) && !app.media.isPlaying &&
                    System.currentTimeMillis() - hiddenSince > idle * 60_000L
                ) {
                    openPanel(app)
                }
                app.main.postDelayed(this, 30_000)
            }
        }, 30_000)
    }

    private fun show(app: App) {
        if (button != null || !allowed(app)) return
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = (Ui.unit(app) * 13).toInt()
        val v = BackButton(app)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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

    private fun hide() {
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
