package cz.stihnuto.panel

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import cz.stihnuto.panel.pages.Page
import cz.stihnuto.panel.pages.Pages
import java.time.LocalTime
import kotlin.math.abs

class MainActivity : Activity() {
    private val app get() = App.instance
    private lateinit var pager: Pager
    private lateinit var dots: Dots
    private var ids: List<String> = emptyList()
    private val pages = mutableMapOf<String, Page>()
    private var index = 0
    private val idleReturn = Runnable { goHome() }
    private val brightnessTick = object : Runnable {
        override fun run() {
            applyBrightness()
            app.main.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        pager = Pager(this).apply {
            onSwipe = { dir -> show((index + dir + ids.size) % ids.size, dir) }
            onTouchAny = { resetIdle() }
        }
        root.addView(pager, FrameLayout.LayoutParams(-1, -1))
        dots = Dots(this) { i -> show(i, if (i > index) 1 else -1) }
        val u = Ui.unit(this)
        root.addView(dots, FrameLayout.LayoutParams(-1, (u * 6).toInt(), Gravity.BOTTOM))
        setContentView(root)

        app.navigator = { id ->
            if (id == "__reload__") buildPages()
            else ids.indexOf(id).takeIf { it >= 0 }?.let { show(it, if (it > index) 1 else -1) }
        }
        buildPages()

        val prefs = getSharedPreferences("panel", MODE_PRIVATE)
        if (!prefs.getBoolean("home_offered", false)) {
            prefs.edit().putBoolean("home_offered", true).apply()
            app.main.postDelayed({ Updater.setHome(this, true) }, 1500)
        } else if (!Overlay.allowed(this) && !prefs.getBoolean("accessibility_asked", false)) {
            prefs.edit().putBoolean("accessibility_asked", true).apply()
            app.main.postDelayed({ Overlay.requestPermission(this) }, 1500)
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
            "claude" in ids
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private fun buildPages() {
        pages[ids.getOrNull(index)]?.onHide()
        pager.removeAllViews()
        pages.clear()
        ids = app.settings.pageList().filter { it in Pages.all }.ifEmpty { listOf("clock") }
        for (id in ids) {
            val p = Pages.all.getValue(id).create(this)
            pages[id] = p
            p.view.visibility = View.GONE
            pager.addView(p.view, FrameLayout.LayoutParams(-1, -1))
        }
        index = ids.indexOf(app.settings.homePage).coerceAtLeast(0)
        show(index, 0)
    }

    private fun show(newIndex: Int, dir: Int) {
        val oldId = ids.getOrNull(index)
        val newId = ids[newIndex]
        val old = pages[oldId]
        val new = pages.getValue(newId)
        if (old != null && old !== new) {
            old.onHide()
            val ov = old.view
            if (dir != 0) {
                ov.animate().translationX(-dir * pager.width.toFloat()).setDuration(220)
                    .withEndAction { ov.visibility = View.GONE; ov.translationX = 0f }.start()
            } else {
                ov.visibility = View.GONE
            }
        }
        index = newIndex
        app.currentPage = newId
        new.view.visibility = View.VISIBLE
        if (dir != 0 && old !== new) {
            new.view.translationX = dir * pager.width.toFloat()
            new.view.animate().translationX(0f).setDuration(220).start()
        }
        new.onShow()
        dots.set(ids.size, index)
        resetIdle()
    }

    private fun goHome() {
        val home = ids.indexOf(app.settings.homePage)
        if (home >= 0 && home != index && app.currentPage != "music") show(home, -1)
    }

    private fun resetIdle() {
        app.main.removeCallbacks(idleReturn)
        val m = app.settings.idleMinutes
        if (m > 0) app.main.postDelayed(idleReturn, m * 60_000L)
        applyBrightness(touched = true)
    }

    private var wakeUntil = 0L

    /** V noci ztlumí displej; dotyk ho na půl minuty rozsvítí. */
    private fun applyBrightness(touched: Boolean = false) {
        val s = app.settings
        val h = LocalTime.now().hour
        val night = s.nightBrightness > 0 &&
            (if (s.nightFrom > s.nightTo) h >= s.nightFrom || h < s.nightTo else h in s.nightFrom until s.nightTo)
        if (touched && night) {
            wakeUntil = System.currentTimeMillis() + 30_000
            app.main.postDelayed({ applyBrightness() }, 30_500)
        }
        val lp = window.attributes
        lp.screenBrightness = when {
            !night -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            System.currentTimeMillis() < wakeUntil -> 0.5f
            else -> s.nightBrightness / 100f
        }
        window.attributes = lp
    }

    override fun onResume() {
        super.onResume()
        Overlay.onPanelShown()
        hideSystemBars()
        pages[ids.getOrNull(index)]?.onShow()
        app.main.post(brightnessTick)
    }

    override fun onPause() {
        super.onPause()
        Overlay.onPanelHidden(app)
        pages[ids.getOrNull(index)]?.onHide()
        app.main.removeCallbacks(brightnessTick)
    }

    override fun onDestroy() {
        app.navigator = null
        app.main.removeCallbacks(idleReturn)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goHome()
    }
}

/** Kontejner, který pozná vodorovné přejetí prstem a nechá klepnutí projít dál. */
class Pager(ctx: Context) : FrameLayout(ctx) {
    var onSwipe: ((Int) -> Unit)? = null
    var onTouchAny: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 2

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; onTouchAny?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) return true
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = e.x; downY = e.y; onTouchAny?.invoke()
        }
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val dx = e.x - downX
            if (abs(dx) > width * 0.1f && abs(dx) > abs(e.y - downY)) onSwipe?.invoke(if (dx < 0) 1 else -1)
        }
        return true
    }
}

/** Tečky dole — kde v oknech jsem. Klepnutím na tečku se přeskočí. */
class Dots(ctx: Context, val onPick: (Int) -> Unit) : View(ctx) {
    private var count = 0
    private var current = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(count: Int, current: Int) {
        this.count = count; this.current = current; invalidate()
    }

    private fun gap() = height * 0.55f

    override fun onDraw(c: Canvas) {
        if (count < 2) return
        val r = height * 0.09f
        val start = width / 2f - (count - 1) * gap() / 2
        for (i in 0 until count) {
            p.color = if (i == current) Ui.TEXT else Ui.FAINT
            c.drawCircle(start + i * gap(), height * 0.55f, if (i == current) r * 1.3f else r, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (count < 2) return false
        val start = width / 2f - (count - 1) * gap() / 2
        val i = Math.round((e.x - start) / gap())
        if (i !in 0 until count) return false
        if (e.actionMasked == MotionEvent.ACTION_UP) onPick(i)
        return true
    }
}
