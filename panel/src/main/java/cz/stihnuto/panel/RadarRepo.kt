package cz.stihnuto.panel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Srážkový radar ČHMÚ, stahovaný na pozadí každých 10 minut — okno Radar
 * pak naskočí hned. Stahuje se vždy jen nový snímek, mapa se ukládá na disk.
 */
class RadarRepo(app: App) : Repo(app) {
    /** Tmavá podkladová mapa velikosti obrazovky. */
    @Volatile var base: Bitmap? = null; private set
    /** Snímky radaru (čas UTC v sekundách → vyříznutá mapová část ČHMÚ). */
    @Volatile var frames: List<Pair<Long, Bitmap>> = emptyList(); private set
    @Volatile var error: String? = null; private set
    private val cache = linkedMapOf<String, Bitmap>()
    private var loading = false

    val width: Int
    val height: Int

    init {
        val m = app.resources.displayMetrics
        width = maxOf(m.widthPixels, m.heightPixels)
        height = minOf(m.widthPixels, m.heightPixels)
        app.main.postDelayed({ refresh(); tick() }, 20_000) // nezdržovat start aplikace
    }

    private fun tick() {
        app.main.postDelayed({ refresh(); tick() }, 10 * 60_000L)
    }

    val zoom get() = app.settings.radarZoom
    val lat get() = app.settings.lat
    val lon get() = app.settings.lon
    val inCzechia get() = lon in CZ_W..CZ_E && lat in CZ_S..CZ_N

    fun refresh() {
        if (loading || !inCzechia || app.settings.radarUrl.isNotBlank()) return
        loading = true
        app.io.execute {
            try {
                if (base == null) base = loadBase()
                notifyChanged()
                val names = Regex("""pacz2gmaps3\.z_max3d\.(\d{8})\.(\d{4})\.0\.png""")
                    .findAll(Http.text(DIR)).map { it.value }.distinct().sorted().toList()
                // poslední hodina po 10 minutách
                val pick = names.takeLast(13).filterIndexed { i, _ -> i % 2 == 0 }.takeLast(7)
                for (n in pick) if (n !in cache) Http.bitmap(DIR + n)?.let { cache[n] = clean(it) }
                cache.keys.filter { it !in pick }.toList().forEach { cache.remove(it)?.recycle() }
                val fmt = DateTimeFormatter.ofPattern("yyyyMMdd.HHmm")
                frames = pick.mapNotNull { n ->
                    val stamp = n.substringAfter("z_max3d.").substringBefore(".0.png")
                    cache[n]?.let { LocalDateTime.parse(stamp, fmt).toEpochSecond(ZoneOffset.UTC) to it }
                }
                error = null
            } catch (e: Exception) {
                Log.w("Panel", "radar", e)
                error = "Radar nejde načíst (${e.message ?: e.javaClass.simpleName})"
            } finally {
                loading = false
                notifyChanged()
            }
        }
    }

    /** Pixelové souřadnice (Web Mercator) na úrovni přiblížení. */
    fun worldPx(lon: Double, lat: Double): Pair<Double, Double> {
        val n = 256.0 * (1 shl zoom)
        val r = Math.toRadians(lat)
        return (lon + 180) / 360 * n to (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n
    }

    /** Levý horní roh výřezu a měřítko pro obrazovku [w]×[h]. */
    fun viewport(w: Int, h: Int): Triple<Double, Double, Float> {
        val scale = (h / 480f).coerceAtLeast(1f)
        val (cx, cy) = worldPx(lon, lat)
        return Triple(cx - w / 2 / scale, cy - h / 2 / scale, scale)
    }

    /** Podklad z disku, nebo složený z dlaždic OpenStreetMap (ztmavených). */
    private fun loadBase(): Bitmap {
        val file = File(app.cacheDir, "radar-base-${zoom}-${"%.3f".format(lat)}-${"%.3f".format(lon)}-${width}x$height.png")
        if (file.exists()) BitmapFactory.decodeFile(file.path)?.let { return it }
        app.cacheDir.listFiles { f -> f.name.startsWith("radar-base-") }?.forEach { it.delete() }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        c.drawColor(Ui.BG)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = darkMap() }
        val (left, top, scale) = viewport(width, height)
        val n = 1 shl zoom
        val tx0 = floor(left / 256).toInt()
        val ty0 = floor(top / 256).toInt()
        val tx1 = floor((left + width / scale) / 256).toInt()
        val ty1 = floor((top + height / scale) / 256).toInt()
        for (tx in tx0..tx1) for (ty in ty0..ty1) {
            if (ty < 0 || ty >= n) continue
            val bmp = Http.bitmap("https://tile.openstreetmap.org/$zoom/${((tx % n) + n) % n}/$ty.png") ?: continue
            val dx = ((tx * 256 - left) * scale).toInt()
            val dy = ((ty * 256 - top) * scale).toInt()
            c.drawBitmap(bmp, null, Rect(dx, dy, dx + (256 * scale).toInt(), dy + (256 * scale).toInt()), paint)
            bmp.recycle()
        }
        try {
            file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            Log.w("Panel", "radar cache", e)
        }
        return out
    }

    /** Světlou mapu převede na tmavou šedomodrou (jako noční režim map). */
    private fun darkMap(): ColorMatrixColorFilter {
        val gray = ColorMatrix().apply { setSaturation(0f) }
        val invert = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val tint = ColorMatrix(floatArrayOf(
            0.42f, 0f, 0f, 0f, 6f,
            0f, 0.45f, 0f, 0f, 8f,
            0f, 0f, 0.52f, 0f, 14f,
            0f, 0f, 0f, 1f, 0f,
        ))
        gray.postConcat(invert)
        gray.postConcat(tint)
        return ColorMatrixColorFilter(gray)
    }

    /** Vyřízne mapovou část snímku ČHMÚ a zprůhlední pozadí. */
    private fun clean(src: Bitmap): Bitmap {
        val dataW = minOf(598, src.width)
        val dataH = minOf(378, src.height)
        val px = IntArray(dataW * dataH)
        src.getPixels(px, 0, dataW, 0, src.height - dataH, dataW, dataH)
        src.recycle()
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            if ((c ushr 24) < 40 || (maxOf(r, g, b) - minOf(r, g, b) < 12)) px[i] = 0
        }
        return Bitmap.createBitmap(px, dataW, dataH, Bitmap.Config.ARGB_8888)
    }

    companion object {
        const val DIR = "https://opendata.chmi.cz/meteorology/weather/radar/composite/maxz/png/"
        // Oblast kompozitu ČHMÚ (EPSG:3857)
        const val CZ_W = 11.267
        const val CZ_E = 19.624
        const val CZ_S = 48.047
        const val CZ_N = 51.458
    }
}
