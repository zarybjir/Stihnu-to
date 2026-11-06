package cz.stihnuto.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/** Horní lišta s kartami oken — rychlé přepínání. Dá se vypnout v nastavení. */
class TopBar(ctx: Context) : View(ctx) {
    private var ids: List<String> = emptyList()
    private var current = 0
    var onPick: ((Int) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val u = Ui.unit(ctx)

    fun set(ids: List<String>, current: Int) {
        if (this.ids != ids || this.current != current) { this.ids = ids; this.current = current; invalidate() }
    }

    private fun label(id: String) = when (id) {
        "clock" -> "Čas"; "calendar" -> "Kalendář"; "weather" -> "Počasí"; "hourly" -> "Předpověď"
        "radar" -> "Radar"; "music" -> "Hudba"; "heating" -> "Topení"; "claude" -> "Pepo"; "apps" -> "Aplikace"
        else -> cz.stihnuto.panel.pages.Pages.all[id]?.title ?: id
    }

    override fun onDraw(c: Canvas) {
        p.color = Ui.BG; c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        if (ids.isEmpty()) return
        val tw = width.toFloat() / ids.size
        p.textAlign = Paint.Align.CENTER
        ids.forEachIndexed { i, id ->
            val x = i * tw
            val on = i == current
            if (on) {
                p.color = Ui.PRIMARY
                c.drawRoundRect(RectF(x + u * 0.8f, u * 1.3f, x + tw - u * 0.8f, height - u * 1.3f), u * 2.4f, u * 2.4f, p)
            }
            val col = if (on) Ui.BG else Ui.DIM
            icon(c, id, x + tw / 2, height * 0.37f, u * 2.0f, col)
            p.color = col
            p.typeface = if (on) Ui.MEDIUM else Typeface.DEFAULT
            p.textSize = u * 2.15f
            c.drawText(label(id), x + tw / 2, height * 0.84f, p)
        }
        p.color = Ui.CARD_HI; c.drawRect(0f, height - 1f, width.toFloat(), height.toFloat(), p)
    }

    private fun icon(c: Canvas, id: String, cx: Float, cy: Float, r: Float, col: Int) {
        c.save()
        p.color = col; p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.16f
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1, y1, x2, y2, p)
        when (id) {
            "clock" -> { c.drawCircle(cx, cy, r, p); line(cx, cy, cx, cy - r * 0.55f); line(cx, cy, cx + r * 0.5f, cy) }
            "calendar" -> {
                c.drawRoundRect(RectF(cx - r, cy - r * 0.8f, cx + r, cy + r), r * 0.2f, r * 0.2f, p)
                line(cx - r, cy - r * 0.3f, cx + r, cy - r * 0.3f)
                line(cx - r * 0.4f, cy - r * 1.1f, cx - r * 0.4f, cy - r * 0.6f); line(cx + r * 0.4f, cy - r * 1.1f, cx + r * 0.4f, cy - r * 0.6f)
            }
            "weather" -> {
                c.drawCircle(cx, cy, r * 0.5f, p)
                for (k in 0 until 8) { val a = k * Math.PI / 4; line((cx + Math.cos(a) * r * 0.8).toFloat(), (cy + Math.sin(a) * r * 0.8).toFloat(), (cx + Math.cos(a) * r * 1.05).toFloat(), (cy + Math.sin(a) * r * 1.05).toFloat()) }
            }
            "hourly" -> for (k in 0 until 3) { val x = cx - r * 0.7f + k * r * 0.7f; line(x, cy + r * 0.7f, x, cy + r * 0.7f - (k + 1) * r * 0.4f) }
            "radar" -> { c.drawCircle(cx, cy, r, p); c.drawCircle(cx, cy, r * 0.55f, p); line(cx, cy, cx + r * 0.7f, cy - r * 0.7f); p.style = Paint.Style.FILL; c.drawCircle(cx, cy, r * 0.14f, p) }
            "music" -> {
                line(cx, cy + r * 0.6f, cx, cy - r * 0.8f); line(cx, cy - r * 0.8f, cx + r * 0.6f, cy - r)
                p.style = Paint.Style.FILL; c.drawOval(RectF(cx - r * 0.6f, cy + r * 0.3f, cx, cy + r * 0.9f), p)
            }
            "heating" -> {
                val path = Path(); path.moveTo(cx, cy - r)
                path.quadTo(cx + r * 0.9f, cy - r * 0.2f, cx + r * 0.5f, cy + r * 0.5f)
                path.arcTo(RectF(cx - r * 0.55f, cy - r * 0.15f, cx + r * 0.55f, cy + r * 0.95f), -30f, 240f, false)
                path.quadTo(cx - r * 0.7f, cy - r * 0.3f, cx, cy - r); c.drawPath(path, p)
            }
            "claude" -> { c.drawRoundRect(RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r * 0.2f), r * 0.45f, r * 0.45f, p); c.drawArc(RectF(cx - r * 0.8f, cy - r * 0.6f, cx + r * 0.8f, cy + r), 20f, 140f, false, p); line(cx, cy + r, cx, cy + r * 1.1f) }
            "apps" -> { p.style = Paint.Style.FILL; for (k in 0 until 4) { val dx = (k % 2) * r * 0.9f - r * 0.45f; val dy = (if (k < 2) -1 else 1) * r * 0.45f; c.drawRoundRect(RectF(cx + dx - r * 0.32f, cy + dy - r * 0.32f, cx + dx + r * 0.32f, cy + dy + r * 0.32f), r * 0.14f, r * 0.14f, p) } }
            else -> c.drawCircle(cx, cy, r * 0.6f, p)
        }
        p.style = Paint.Style.FILL
        c.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (ids.isEmpty()) return false
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = (e.x / (width.toFloat() / ids.size)).toInt().coerceIn(0, ids.size - 1)
            onPick?.invoke(i)
        }
        return true
    }
}
