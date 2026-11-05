package cz.stihnuto.panel.pages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
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

    private val art = ArtView(ctx)
    private val appLabel = Ui.text(ctx, "", u * 4.5f, Ui.DIM)
    private val title = Ui.text(ctx, "", u * 8f, Ui.TEXT, Ui.MEDIUM).apply {
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    private val artist = Ui.text(ctx, "", u * 5.5f, Ui.DIM).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val play = IconButton(ctx, IconButton.PLAY, u * 20) { app.media.command("toggle") }
    private val volume = Ui.text(ctx, "", u * 5f, Ui.DIM).apply { gravity = Gravity.CENTER }
    // přehrávací tlačítka (fungují jen s přístupem k přehrávání); hlasitost jde vždy
    private val transport = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
    private val controls = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
    private val launch = Ui.button(ctx, "Otevřít přehrávač", u * 5.5f, Ui.PRIMARY, Ui.BG) { open("youtube_music") }

    private val onMedia = { update() }

    init {
        val side = (u * 60).toInt()
        root.addView(art, LinearLayout.LayoutParams(side, side))

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((u * 6).toInt(), 0, 0, 0)
        }
        col.addView(appLabel)
        col.addView(title, lp(top = u * 1.5f))
        col.addView(artist, lp(top = u * 1.5f))

        transport.addView(IconButton(ctx, IconButton.PREV, u * 15) { app.media.command("previous") })
        transport.addView(play, LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = (u * 3).toInt(); rightMargin = (u * 3).toInt()
        })
        transport.addView(IconButton(ctx, IconButton.NEXT, u * 15) { app.media.command("next") })
        controls.addView(transport)
        controls.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(Ui.button(ctx, "−", u * 7) { app.media.command("volume_down"); update() },
            LinearLayout.LayoutParams((u * 14).toInt(), (u * 14).toInt()))
        controls.addView(volume, LinearLayout.LayoutParams((u * 16).toInt(), -2))
        controls.addView(Ui.button(ctx, "+", u * 7) { app.media.command("volume_up"); update() },
            LinearLayout.LayoutParams((u * 14).toInt(), (u * 14).toInt()))
        col.addView(controls, lp(top = u * 6))
        col.addView(launch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 6).toInt() })

        root.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun lp(top: Float) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = top.toInt() }

    private fun open(key: String) {
        if (!app.media.openApp(key)) {
            App.instance.showPage("apps")
        }
    }

    private fun update() {
        val m = app.media
        val playing = !m.permissionMissing && m.title != null
        // přehrávací tlačítka a název skladby jen když panel vidí, co hraje
        transport.gone(m.permissionMissing)
        title.text = if (playing) m.title else "Hudba"
        artist.text = if (playing) m.artist ?: "" else ""
        appLabel.text = if (playing) m.appName?.let { "Hraje v: $it" } ?: "" else ""
        art.set(if (playing) m.art else null)
        play.icon = if (m.isPlaying) IconButton.PAUSE else IconButton.PLAY
        volume.text = "${m.volumePercent} %"
    }

    override fun onShow() {
        app.media.attach()
        app.media.listen(onMedia)
    }

    override fun onHide() {
        app.media.unlisten(onMedia)
    }

    /** Obal skladby; když žádný není, nakreslí notu, ať není prázdný čtverec. */
    class ArtView(ctx: Context) : View(ctx) {
        private var bmp: Bitmap? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val rect = RectF()
        private val rad = Ui.unit(ctx) * 4

        fun set(b: Bitmap?) { bmp = b; invalidate() }

        override fun onDraw(c: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            clip.reset(); clip.addRoundRect(rect, rad, rad, Path.Direction.CW)
            c.clipPath(clip)
            p.color = Ui.CARD; c.drawRect(rect, p)
            val b = bmp
            if (b != null) {
                val sc = maxOf(width.toFloat() / b.width, height.toFloat() / b.height)
                val dw = b.width * sc; val dh = b.height * sc
                c.drawBitmap(b, null, RectF((width - dw) / 2, (height - dh) / 2, (width + dw) / 2, (height + dh) / 2), p)
                return
            }
            // osminová nota uprostřed
            val s = minOf(width, height).toFloat()
            p.color = Ui.CARD_HI
            val rx = s * 0.12f; val ry = s * 0.09f
            val hx = width * 0.42f; val hy = height * 0.66f
            val stemX = hx + rx * 0.82f; val stemTop = height * 0.30f
            p.strokeWidth = s * 0.035f; p.style = Paint.Style.STROKE
            c.drawLine(stemX, hy, stemX, stemTop, p)
            p.style = Paint.Style.FILL
            // praporek
            c.drawPath(Path().apply {
                moveTo(stemX, stemTop)
                quadTo(stemX + s * 0.18f, stemTop + s * 0.06f, stemX + s * 0.10f, stemTop + s * 0.22f)
                quadTo(stemX + s * 0.14f, stemTop + s * 0.10f, stemX, stemTop + s * 0.10f)
                close()
            }, p)
            c.save(); c.rotate(-20f, hx, hy)
            c.drawOval(RectF(hx - rx, hy - ry, hx + rx, hy + ry), p)
            c.restore()
        }
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
