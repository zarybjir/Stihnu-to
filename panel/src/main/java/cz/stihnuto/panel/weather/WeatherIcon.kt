package cz.stihnuto.panel.weather

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.sin

/** Ikony počasí kreslené přímo na plátno — ostré v každé velikosti, žádné obrázky. */
object WeatherIcon {
    private const val SUN = 0xFFFFC94A.toInt()
    private const val MOON = 0xFFE6E1C8.toInt()
    private const val CLOUD = 0xFFDCE3EA.toInt()
    private const val DARK_CLOUD = 0xFF8E9AA6.toInt()
    private const val RAIN = 0xFF4FA3FF.toInt()
    private const val SNOW = 0xFFFFFFFF.toInt()
    private const val BOLT = 0xFFFFD84A.toInt()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Nakreslí ikonu se středem [cx],[cy] do čtverce o straně [s]. */
    fun draw(c: Canvas, code: Int, isDay: Boolean, cx: Float, cy: Float, s: Float) {
        val u = s / 100f
        fun x(v: Float) = cx + (v - 50f) * u
        fun y(v: Float) = cy + (v - 50f) * u
        when (code) {
            0 -> sky(c, isDay, x(50f), y(50f), 26 * u)
            1, 2 -> {
                sky(c, isDay, x(36f), y(36f), 20 * u)
                cloud(c, x(56f), y(62f), 30 * u, CLOUD)
            }
            3 -> {
                cloud(c, x(40f), y(46f), 24 * u, DARK_CLOUD)
                cloud(c, x(56f), y(58f), 30 * u, CLOUD)
            }
            45, 48 -> {
                cloud(c, x(50f), y(42f), 28 * u, DARK_CLOUD)
                p.style = Paint.Style.STROKE; p.strokeWidth = 5 * u; p.color = CLOUD
                p.strokeCap = Paint.Cap.ROUND
                for (i in 0..2) c.drawLine(x(18f + i * 6), y(70f + i * 10), x(82f - i * 6), y(70f + i * 10), p)
            }
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> {
                cloud(c, x(50f), y(42f), 30 * u, if (code >= 63) DARK_CLOUD else CLOUD)
                val drops = when (code) { 51, 61, 80 -> 2; 65, 82 -> 4; else -> 3 }
                drops(c, x(50f), y(78f), u, drops)
            }
            71, 73, 75, 77, 85, 86 -> {
                cloud(c, x(50f), y(42f), 30 * u, CLOUD)
                val n = if (code == 75 || code == 86) 4 else 3
                for (i in 0 until n) flake(c, x(50f + (i - (n - 1) / 2f) * 18), y(if (i % 2 == 0) 76f else 86f), 6 * u)
            }
            95, 96, 99 -> {
                cloud(c, x(50f), y(40f), 30 * u, DARK_CLOUD)
                val path = Path().apply {
                    moveTo(x(54f), y(56f)); lineTo(x(40f), y(78f)); lineTo(x(52f), y(78f))
                    lineTo(x(44f), y(96f)); lineTo(x(66f), y(70f)); lineTo(x(54f), y(70f)); close()
                }
                p.style = Paint.Style.FILL; p.color = BOLT; c.drawPath(path, p)
            }
            else -> cloud(c, x(50f), y(52f), 30 * u, CLOUD)
        }
    }

    private fun sky(c: Canvas, isDay: Boolean, cx: Float, cy: Float, r: Float) {
        p.style = Paint.Style.FILL
        if (isDay) {
            p.color = SUN
            c.drawCircle(cx, cy, r * 0.62f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.14f; p.strokeCap = Paint.Cap.ROUND
            for (i in 0 until 8) {
                val a = Math.PI * i / 4
                c.drawLine(
                    cx + (cos(a) * r * 0.82f).toFloat(), cy + (sin(a) * r * 0.82f).toFloat(),
                    cx + (cos(a) * r * 1.08f).toFloat(), cy + (sin(a) * r * 1.08f).toFloat(), p,
                )
            }
        } else {
            p.color = MOON
            val path = Path().apply {
                addCircle(cx, cy, r * 0.8f, Path.Direction.CW)
                op(Path().apply { addCircle(cx + r * 0.45f, cy - r * 0.3f, r * 0.7f, Path.Direction.CW) },
                    Path.Op.DIFFERENCE)
            }
            c.drawPath(path, p)
        }
    }

    /** Mrak se středem dole uprostřed, [w] = polovina šířky. */
    private fun cloud(c: Canvas, cx: Float, cy: Float, w: Float, color: Int) {
        p.style = Paint.Style.FILL; p.color = color
        c.drawCircle(cx - w * 0.45f, cy, w * 0.45f, p)
        c.drawCircle(cx + w * 0.05f, cy - w * 0.3f, w * 0.6f, p)
        c.drawCircle(cx + w * 0.55f, cy + w * 0.05f, w * 0.42f, p)
        c.drawRect(cx - w * 0.45f, cy, cx + w * 0.55f, cy + w * 0.45f, p)
        c.drawCircle(cx - w * 0.45f, cy, w * 0.45f, p)
    }

    private fun drops(c: Canvas, cx: Float, cy: Float, u: Float, n: Int) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 5 * u; p.strokeCap = Paint.Cap.ROUND; p.color = RAIN
        for (i in 0 until n) {
            val x = cx + (i - (n - 1) / 2f) * 15 * u
            c.drawLine(x + 3 * u, cy - 8 * u, x - 3 * u, cy + 6 * u, p)
        }
    }

    private fun flake(c: Canvas, cx: Float, cy: Float, r: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.4f; p.strokeCap = Paint.Cap.ROUND; p.color = SNOW
        for (i in 0 until 3) {
            val a = Math.PI * i / 3
            val dx = (cos(a) * r).toFloat(); val dy = (sin(a) * r).toFloat()
            c.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, p)
        }
    }
}
