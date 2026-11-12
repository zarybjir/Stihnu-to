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
 * pak naskočí hned. Stahuje se vždy jen nový snímek.
 *
 * Mapa se dá přibližovat a posouvat. Pohled (střed + přiblížení) se drží
 * v souřadnicích Web Mercator jako zlomek světa 0..1, takže se při změně
 * přiblížení nic nepřepočítává. Podkladová mapa se po změně pohledu složí
 * znovu na pozadí; do té doby se kreslí ta stará, roztažená/posunutá na nové místo.
 */
class RadarRepo(app: App) : Repo(app) {
    /** Hotová podkladová mapa a kus světa, který pokrývá (zlomky 0..1). */
    class Base(val bmp: Bitmap, val left: Double, val top: Double, val right: Double, val bottom: Double)

    @Volatile var base: Base? = null; private set
    /** Snímky radaru (čas UTC v sekundách → vyříznutá mapová část ČHMÚ). */
    @Volatile var frames: List<Pair<Long, Bitmap>> = emptyList(); private set
    @Volatile var error: String? = null; private set
    /** Skládá se nová podkladová mapa (po posunu nebo přiblížení). */
    @Volatile var mapLoading = false; private set
    private val cache = linkedMapOf<String, Bitmap>()
    private var loading = false

    val width: Int
    val height: Int

    // --- pohled
    @Volatile var zoom: Int = app.settings.radarZoom.coerceIn(MIN_ZOOM, MAX_ZOOM); private set
    @Volatile var cx: Double = homeX(); private set
    @Volatile var cy: Double = homeY(); private set
    private var baseGen = 0

    init {
        val m = app.resources.displayMetrics
        width = maxOf(m.widthPixels, m.heightPixels)
        height = minOf(m.widthPixels, m.heightPixels)
        app.main.postDelayed({ refresh(); tick() }, 20_000) // nezdržovat start aplikace
    }

    private fun tick() {
        app.main.postDelayed({ refresh(); tick() }, 10 * 60_000L)
    }

    val lat get() = app.settings.lat
    val lon get() = app.settings.lon
    val inCzechia get() = lon in CZ_W..CZ_E && lat in CZ_S..CZ_N

    fun homeX() = normX(app.settings.lon)
    fun homeY() = normY(app.settings.lat)
    val atHome get() = zoom == app.settings.radarZoom.coerceIn(MIN_ZOOM, MAX_ZOOM) &&
        Math.abs(cx - homeX()) < 1e-9 && Math.abs(cy - homeY()) < 1e-9

    /** Měřítko kreslení (mapové pixely → obrazovka), stejně jako dřív podle výšky. */
    fun scale(h: Int) = (h / 480f).coerceAtLeast(1f)

    /** Kde na obrazovce [w]×[h] leží bod světa (zlomky 0..1) při současném pohledu. */
    fun toScreenX(nx: Double, w: Int, h: Int) = ((nx - cx) * worldSize(zoom) * scale(h) + w / 2.0).toFloat()
    fun toScreenY(ny: Double, w: Int, h: Int) = ((ny - cy) * worldSize(zoom) * scale(h) + h / 2.0).toFloat()

    // --- ovládání pohledu
    fun zoomBy(d: Int) {
        val z = (zoom + d).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (z == zoom) return
        zoom = z
        viewChanged()
    }

    /** Posun o zlomek obrazovky (dx, dy = −1..1, kladné doprava/dolů). */
    fun pan(dx: Double, dy: Double, w: Int, h: Int) {
        val k = 1.0 / (worldSize(zoom) * scale(h))
        cx = (cx + dx * w * k).coerceIn(0.0, 1.0)
        cy = (cy + dy * h * k).coerceIn(0.02, 0.98)
        viewChanged()
    }

    fun resetView() {
        zoom = app.settings.radarZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        cx = homeX(); cy = homeY()
        viewChanged()
    }

    val canZoomIn get() = zoom < MAX_ZOOM
    val canZoomOut get() = zoom > MIN_ZOOM

    private val rebuild = Runnable { buildBaseAsync() }

    private fun viewChanged() {
        notifyChanged()
        // mapu skládat až chvilku po posledním klepnutí — při rychlém klikání se nestahuje zbytečně
        app.main.removeCallbacks(rebuild)
        app.main.postDelayed(rebuild, 600)
    }

    private fun buildBaseAsync() {
        val gen = ++baseGen
        val z = zoom; val x = cx; val y = cy
        mapLoading = true
        notifyChanged()
        app.io.execute {
            try {
                val b = loadBase(z, x, y)
                if (gen == baseGen) base = b
            } catch (e: Exception) {
                Log.w("Panel", "radar mapa", e)
            } finally {
                if (gen == baseGen) mapLoading = false
                notifyChanged()
            }
        }
    }

    fun refresh() {
        if (loading || !inCzechia || app.settings.radarUrl.isNotBlank()) return
        loading = true
        app.io.execute {
            try {
                if (base == null) base = loadBase(zoom, cx, cy)
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

    // --- dlaždice OpenStreetMap (malá paměťová zásoba — posun tam a zpět nestahuje znovu)
    private val tiles = object : LinkedHashMap<String, Bitmap>(40, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>) = size > 36
    }

    private fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        synchronized(tiles) { tiles[key]?.let { return it } }
        val bytes = try { Http.bytes("https://tile.openstreetmap.org/$key.png") } catch (e: Exception) { return null }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) ?: return null
        synchronized(tiles) { tiles[key] = bmp }
        return bmp
    }

    /** Podklad pro pohled (z, x, y): z disku (jen výchozí pohled), nebo složený z dlaždic (ztmavených). */
    private fun loadBase(z: Int, x: Double, y: Double): Base {
        val world = worldSize(z)
        val sc = scale(height)
        val halfW = width / 2.0 / sc / world
        val halfH = height / 2.0 / sc / world
        val home = z == app.settings.radarZoom.coerceIn(MIN_ZOOM, MAX_ZOOM) &&
            Math.abs(x - homeX()) < 1e-9 && Math.abs(y - homeY()) < 1e-9
        val file = File(app.cacheDir, "radar-base-$z-${"%.3f".format(lat)}-${"%.3f".format(lon)}-${width}x$height.png")
        if (home && file.exists()) BitmapFactory.decodeFile(file.path)?.let {
            return Base(it, x - halfW, y - halfH, x + halfW, y + halfH)
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        c.drawColor(Ui.BG)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = darkMap() }
        val left = (x - halfW) * world
        val top = (y - halfH) * world
        val n = 1 shl z
        val tx0 = floor(left / 256).toInt()
        val ty0 = floor(top / 256).toInt()
        val tx1 = floor((left + width / sc) / 256).toInt()
        val ty1 = floor((top + height / sc) / 256).toInt()
        for (tx in tx0..tx1) for (ty in ty0..ty1) {
            if (ty < 0 || ty >= n) continue
            val bmp = tile(z, ((tx % n) + n) % n, ty) ?: continue
            val dx = ((tx * 256 - left) * sc).toInt()
            val dy = ((ty * 256 - top) * sc).toInt()
            c.drawBitmap(bmp, null, Rect(dx, dy, dx + Math.ceil(256.0 * sc).toInt(), dy + Math.ceil(256.0 * sc).toInt()), paint)
        }
        if (home) try {
            app.cacheDir.listFiles { f -> f.name.startsWith("radar-base-") }?.forEach { it.delete() }
            file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            Log.w("Panel", "radar cache", e)
        }
        return Base(out, x - halfW, y - halfH, x + halfW, y + halfH)
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
        const val MIN_ZOOM = 5
        const val MAX_ZOOM = 10

        fun worldSize(z: Int) = 256.0 * (1 shl z)
        fun normX(lon: Double) = (lon + 180) / 360
        fun normY(lat: Double): Double {
            val r = Math.toRadians(lat)
            return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2
        }
    }
}
