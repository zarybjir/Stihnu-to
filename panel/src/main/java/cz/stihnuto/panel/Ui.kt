package cz.stihnuto.panel

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/** Barvy, písmo a pomocné funkce pro vzhled. Tmavý motiv — v noci nesvítí. */
object Ui {
    const val BG = 0xFF0B0F14.toInt()
    const val CARD = 0xFF17202A.toInt()
    const val CARD_HI = 0xFF223040.toInt()
    const val TEXT = 0xFFF2F5F8.toInt()
    const val DIM = 0xFF8A96A3.toInt()
    const val FAINT = 0xFF3A4652.toInt()
    const val ACCENT = 0xFFFFC94A.toInt()
    const val BLUE = 0xFF4FA3FF.toInt()
    const val HEAT = 0xFFFF7A45.toInt()
    const val GOOD = 0xFF5CD68A.toInt()

    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    private val DAYS = arrayOf("pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota", "neděle")
    private val DAYS_SHORT = arrayOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")
    private val MONTHS = arrayOf(
        "ledna", "února", "března", "dubna", "května", "června",
        "července", "srpna", "září", "října", "listopadu", "prosince",
    )

    fun dayName(d: LocalDate) = DAYS[d.dayOfWeek.value - 1]
    fun dayShort(d: LocalDate) = DAYS_SHORT[d.dayOfWeek.value - 1]
    fun dateLong(d: LocalDate) = "${dayName(d)} ${d.dayOfMonth}. ${MONTHS[d.monthValue - 1]}"
    fun time(t: LocalDateTime) = "%d:%02d".format(t.hour, t.minute)

    /** 21,5 */
    fun num(v: Double?, decimals: Int = 1): String =
        if (v == null || v.isNaN()) "–" else String.format(Locale("cs", "CZ"), "%.${decimals}f", v)

    fun deg(v: Double?) = if (v == null || v.isNaN()) "–" else "${Math.round(v)}°"

    /** Jednotka velikosti = 1 % výšky obrazovky. Na 4" hodinách i na tabletu vypadá stejně. */
    fun unit(ctx: Context): Float {
        val m = ctx.resources.displayMetrics
        return minOf(m.heightPixels, m.widthPixels) / 100f
    }

    fun paint(color: Int, size: Float, face: Typeface = REGULAR, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; typeface = face; textAlign = align
        }

    fun text(ctx: Context, s: String = "", size: Float, color: Int = TEXT, face: Typeface = REGULAR) =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            typeface = face
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
            includeFontPadding = false
        }

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    /** Velké dotykové tlačítko. */
    fun button(ctx: Context, label: String, size: Float, color: Int = CARD_HI, textColor: Int = TEXT, onClick: () -> Unit) =
        text(ctx, label, size, textColor, MEDIUM).apply {
            gravity = Gravity.CENTER
            background = rounded(color, size * 0.6f)
            val pad = (size * 0.5f).toInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true
            setOnClickListener { onClick() }
            setOnTouchListener { v, e ->
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> v.alpha = 0.6f
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                false
            }
        }

    fun View.gone(hide: Boolean) { visibility = if (hide) View.GONE else View.VISIBLE }
}
