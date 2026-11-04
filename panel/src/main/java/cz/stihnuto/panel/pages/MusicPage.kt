package cz.stihnuto.panel.pages

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import cz.stihnuto.panel.App
import cz.stihnuto.panel.Ui
import cz.stihnuto.panel.Ui.gone

/** Co hraje (YouTube Music a jiné), ovládání a rychlé spuštění aplikací. */
class MusicPage(ctx: Context) : Page(ctx) {
    private val app = App.instance
    private val u = Ui.unit(ctx)
    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((u * 6).toInt(), (u * 6).toInt(), (u * 6).toInt(), (u * 8).toInt())
    }
    override val view: View = root

    private val art = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = Ui.rounded(Ui.CARD, u * 3)
        clipToOutline = true
    }
    private val appLabel = Ui.text(ctx, "", u * 4.5f, Ui.DIM)
    private val title = Ui.text(ctx, "", u * 8f, Ui.TEXT, Ui.MEDIUM).apply {
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    private val artist = Ui.text(ctx, "", u * 5.5f, Ui.DIM).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val play = IconButton(ctx, IconButton.PLAY, u * 22) { app.media.command("toggle") }
    private val volume = Ui.text(ctx, "", u * 5f, Ui.DIM).apply { gravity = Gravity.CENTER }
    private val controls = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
    private val hint = Ui.text(ctx, "", u * 4.5f, Ui.DIM)
    private val grant = Ui.button(ctx, "Povolit přístup k přehrávání", u * 5, Ui.ACCENT, Ui.BG) { openListenerSettings() }

    private val onMedia = { update() }

    init {
        val side = (u * 62).toInt()
        root.addView(art, LinearLayout.LayoutParams(side, side))

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((u * 6).toInt(), 0, 0, 0)
        }
        col.addView(appLabel)
        col.addView(title, lp(top = u * 1.5f))
        col.addView(artist, lp(top = u * 1.5f))

        controls.addView(IconButton(ctx, IconButton.PREV, u * 16) { app.media.command("previous") })
        controls.addView(play, LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = (u * 3).toInt(); rightMargin = (u * 3).toInt()
        })
        controls.addView(IconButton(ctx, IconButton.NEXT, u * 16) { app.media.command("next") })
        controls.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(Ui.button(ctx, "−", u * 7) { app.media.command("volume_down"); update() },
            LinearLayout.LayoutParams((u * 14).toInt(), (u * 14).toInt()))
        controls.addView(volume, LinearLayout.LayoutParams((u * 16).toInt(), -2))
        controls.addView(Ui.button(ctx, "+", u * 7) { app.media.command("volume_up"); update() },
            LinearLayout.LayoutParams((u * 14).toInt(), (u * 14).toInt()))
        col.addView(controls, lp(top = u * 5))
        col.addView(hint, lp(top = u * 3))
        col.addView(grant, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (u * 3).toInt() })

        val apps = LinearLayout(ctx)
        apps.addView(Ui.button(ctx, "YouTube Music", u * 5) { open("youtube_music") })
        apps.addView(Ui.button(ctx, "YouTube", u * 5) { open("youtube") },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (u * 3).toInt() })
        col.addView(apps, lp(top = u * 5))

        root.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun lp(top: Float) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = top.toInt() }

    /** Otevře systémové nastavení „Přístup k oznámením“ — přes něj Android povolí ovládat hudbu. */
    private fun openListenerSettings() {
        val intents = listOf(
            android.content.Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                    android.content.ComponentName(ctx, cz.stihnuto.panel.MediaListener::class.java).flattenToString()),
            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        )
        for (i in intents) {
            try {
                ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // zkusí další variantu
            }
        }
        hint.text = "Nastavení oprávnění se na hodinách nedá otevřít. Půjde to přes počítač (adb)."
    }

    private fun open(key: String) {
        if (!app.media.openApp(key)) {
            hint.text = "Aplikace není nainstalovaná — nainstaluj ji v okně Aplikace (poslední okno)."
            App.instance.showPage("apps")
            hint.gone(false)
        }
    }

    private fun update() {
        val m = app.media
        if (m.permissionMissing) {
            title.text = "Chybí oprávnění"
            artist.text = ""
            appLabel.text = ""
            hint.text = "Klepni na tlačítko, v seznamu zapni „Panel“ a vrať se zpět."
            hint.gone(false)
            grant.gone(false)
            controls.gone(true)
            art.setImageBitmap(null)
            return
        }
        controls.gone(false)
        grant.gone(true)
        val t = m.title
        title.text = t ?: "Nic nehraje"
        artist.text = m.artist ?: if (t == null) "Spusť YouTube Music tlačítkem dole" else ""
        appLabel.text = m.appName?.let { "Hraje v: $it" } ?: ""
        art.setImageBitmap(m.art)
        play.icon = if (m.isPlaying) IconButton.PAUSE else IconButton.PLAY
        volume.text = "${m.volumePercent} %"
        hint.gone(hint.text.isNullOrEmpty())
    }

    override fun onShow() {
        hint.text = ""
        app.media.attach()
        app.media.listen(onMedia)
    }

    override fun onHide() {
        app.media.unlisten(onMedia)
    }

    /** Kulaté tlačítko s nakreslenou ikonou (fonty na hodinách nemusí mít symboly). */
    class IconButton(ctx: Context, var iconInit: Int, size: Float, val onClick: () -> Unit) : View(ctx) {
        var icon = iconInit
            set(v) { field = v; invalidate() }
        private val s = size.toInt()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        init { layoutParams = FrameLayout.LayoutParams(s, s) }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(s, s)

        override fun onDraw(c: Canvas) {
            val r = width / 2f
            p.color = if (icon == PLAY || icon == PAUSE) Ui.TEXT else Ui.CARD_HI
            c.drawCircle(r, r, r, p)
            p.color = if (icon == PLAY || icon == PAUSE) Ui.BG else Ui.TEXT
            val k = r * 0.36f
            when (icon) {
                PLAY -> c.drawPath(Path().apply {
                    moveTo(r - k * 0.7f, r - k); lineTo(r + k, r); lineTo(r - k * 0.7f, r + k); close()
                }, p)
                PAUSE -> {
                    c.drawRect(r - k * 0.8f, r - k, r - k * 0.25f, r + k, p)
                    c.drawRect(r + k * 0.25f, r - k, r + k * 0.8f, r + k, p)
                }
                PREV, NEXT -> {
                    c.save()
                    if (icon == PREV) c.scale(-1f, 1f, r, r)
                    c.drawPath(Path().apply {
                        moveTo(r - k, r - k * 0.8f); lineTo(r, r); lineTo(r - k, r + k * 0.8f); close()
                        moveTo(r, r - k * 0.8f); lineTo(r + k, r); lineTo(r, r + k * 0.8f); close()
                    }, p)
                    c.drawRect(r + k, r - k * 0.8f, r + k * 1.2f, r + k * 0.8f, p)
                    c.restore()
                }
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> alpha = 0.6f
                MotionEvent.ACTION_UP -> { alpha = 1f; onClick() }
                MotionEvent.ACTION_CANCEL -> alpha = 1f
            }
            return true
        }

        companion object {
            const val PLAY = 0
            const val PAUSE = 1
            const val PREV = 2
            const val NEXT = 3
        }
    }
}
