package cz.stihnuto.panel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import cz.stihnuto.panel.tapo.TapoChild
import cz.stihnuto.panel.tapo.TapoHub
import cz.stihnuto.panel.weather.Weather
import cz.stihnuto.panel.weather.WeatherApi
import cz.stihnuto.panel.web.WebServer
import java.util.concurrent.Executors

/**
 * Sdílený stav aplikace. Všechny akce (dotyk, webová stránka, Claude) jdou přes
 * stejné metody, takže se chovají vždy stejně.
 */
class App : Application() {
    lateinit var settings: Settings
    val io = Executors.newFixedThreadPool(4)
    val main = Handler(Looper.getMainLooper())

    lateinit var weather: WeatherRepo
    lateinit var heating: HeatingRepo
    lateinit var media: MediaRepo
    lateinit var calendar: CalendarRepo
    lateinit var radar: RadarRepo
    lateinit var co2: Co2Meter
    private var server: WebServer? = null

    /** Nastaví MainActivity — přepne okno. */
    var navigator: ((String) -> Unit)? = null
    var currentPage: String = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        weather = WeatherRepo(this)
        heating = HeatingRepo(this)
        media = MediaRepo(this)
        calendar = CalendarRepo(this)
        radar = RadarRepo(this)
        co2 = Co2Meter(this)
        Overlay.startWatchdog(this)
        main.postDelayed({ VoiceWake.start(this) }, 15_000)
        server = WebServer(this, 8080).also { it.start() }
    }

    fun showPage(id: String): Boolean {
        if (id !in settings.pageList()) return false
        main.post { navigator?.invoke(id) }
        return true
    }

    fun onSettingsChanged() {
        heating.reset()
        weather.refresh(force = true)
        calendar.refresh(force = true)
        main.post { navigator?.invoke("__reload__") }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

/** Jednoduchý „pozorovatel": okna se přihlásí, dokud jsou vidět. */
open class Repo(protected val app: App) {
    private val listeners = mutableSetOf<() -> Unit>()

    fun listen(l: () -> Unit) { listeners += l; l() }
    fun unlisten(l: () -> Unit) { listeners -= l }
    protected fun notifyChanged() {
        app.main.post { listeners.toList().forEach { it() } }
    }
    protected val hasListeners get() = listeners.isNotEmpty()
}

class WeatherRepo(app: App) : Repo(app) {
    @Volatile var data: Weather? = null; private set
    @Volatile var error: String? = null; private set
    private var loading = false

    init {
        refresh(force = true)
        tick()
    }

    private fun tick() {
        app.main.postDelayed({ refresh(); tick() }, 5 * 60_000L)
    }

    fun refresh(force: Boolean = false) {
        val age = System.currentTimeMillis() - (data?.fetchedAt ?: 0)
        if (loading || (!force && age < 15 * 60_000L)) return
        loading = true
        val s = app.settings
        app.io.execute {
            try {
                data = WeatherApi.fetch(s.lat, s.lon)
                error = null
            } catch (e: Exception) {
                Log.w("Panel", "počasí", e)
                error = "Počasí nejde načíst (${e.message ?: e.javaClass.simpleName})"
            } finally {
                loading = false
                notifyChanged()
            }
        }
    }
}

class CalendarRepo(app: App) : Repo(app) {
    @Volatile var events: List<cz.stihnuto.panel.calendar.CalEvent> = emptyList(); private set
    @Volatile var error: String? = null; private set
    @Volatile var loaded = false; private set
    private var fetchedAt = 0L
    private var loading = false

    val urls get() = app.settings.calendarUrls.split('\n', ' ', ',').map { it.trim() }
        .filter { it.startsWith("http") || it.startsWith("webcal") }

    init {
        refresh(force = true)
        tick()
    }

    private fun tick() {
        app.main.postDelayed({ refresh(); tick() }, 5 * 60_000L)
    }

    fun refresh(force: Boolean = false) {
        if (loading || (!force && System.currentTimeMillis() - fetchedAt < 15 * 60_000L)) return
        val list = urls
        if (list.isEmpty()) {
            events = emptyList(); error = null; loaded = true; notifyChanged(); return
        }
        loading = true
        app.io.execute {
            val now = java.time.LocalDateTime.now()
            val from = now.toLocalDate().atStartOfDay()
            val to = from.plusDays(15)
            val all = mutableListOf<cz.stihnuto.panel.calendar.CalEvent>()
            val errors = mutableListOf<String>()
            for (u in list) {
                try {
                    val text = Http.text(u.replaceFirst("webcal://", "https://"))
                    all += cz.stihnuto.panel.calendar.Ics.parse(text, from, to)
                } catch (e: Exception) {
                    Log.w("Panel", "kalendář", e)
                    errors += e.message ?: e.javaClass.simpleName
                }
            }
            events = all.sortedWith(compareBy({ it.start.toLocalDate() }, { !it.allDay }, { it.start }))
            error = if (errors.isEmpty()) null else "Kalendář nejde načíst (${errors.first()})"
            fetchedAt = System.currentTimeMillis()
            loaded = true
            loading = false
            notifyChanged()
        }
    }

    /** Události, které ještě neskončily. */
    fun upcoming(): List<cz.stihnuto.panel.calendar.CalEvent> {
        val now = java.time.LocalDateTime.now()
        return events.filter { it.end.isAfter(now) || (it.allDay && !it.start.toLocalDate().isBefore(now.toLocalDate())) }
    }
}

class HeatingRepo(app: App) : Repo(app) {
    @Volatile var children: List<TapoChild> = emptyList(); private set
    @Volatile var error: String? = null; private set
    @Volatile var loaded = false; private set
    private var hub: TapoHub? = null
    private var lastFetch = 0L

    /** Cílové teploty nastavené na displeji, ale ještě neodeslané (zpoždění kvůli opakovanému klepání). */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Double>()

    init { tick() }

    private fun tick() {
        val interval = if (hasListeners) 60_000L else 5 * 60_000L
        if (System.currentTimeMillis() - lastFetch >= interval) refresh()
        app.main.postDelayed({ tick() }, 15_000L)
    }

    val configured get() = app.settings.hubIp.isNotBlank() && app.settings.tapoUser.isNotBlank()

    fun reset() {
        synchronized(this) { hub = null }
        loaded = false
        children = emptyList()
        refresh()
    }

    private fun hub(): TapoHub = synchronized(this) {
        hub ?: TapoHub(app.settings.hubIp.trim(), app.settings.tapoUser.trim(), app.settings.tapoPass)
            .also { hub = it }
    }

    fun refresh() {
        if (!configured) {
            error = null; loaded = true; notifyChanged(); return
        }
        lastFetch = System.currentTimeMillis()
        app.io.execute {
            try {
                val list = hub().children()
                children = list.map { c -> pending[c.id]?.let { c.withTarget(it) } ?: c }
                error = null
            } catch (e: Exception) {
                Log.w("Panel", "tapo", e)
                error = e.message ?: e.javaClass.simpleName
            }
            loaded = true
            notifyChanged()
        }
    }

    fun find(nameOrId: String): TapoChild? {
        val q = nameOrId.trim().lowercase()
        return children.firstOrNull { it.id == nameOrId }
            ?: children.firstOrNull { it.name.lowercase() == q }
            ?: children.firstOrNull { it.name.lowercase().contains(q) || q.contains(it.name.lowercase()) }
    }

    /** Změní cíl hned na displeji, odešle až po chvilce klidu. */
    fun nudgeTarget(id: String, delta: Double) {
        val c = children.firstOrNull { it.id == id } ?: return
        val base = pending[id] ?: c.targetTemp ?: 20.0
        val t = (Math.round((base + delta) * 2) / 2.0).coerceIn(c.minTemp, c.maxTemp)
        pending[id] = t
        children = children.map { if (it.id == id) it.withTarget(t) else it }
        notifyChanged()
        app.main.removeCallbacksAndMessages(id)
        app.main.postAtTime({ setTarget(id, t) }, id, android.os.SystemClock.uptimeMillis() + 1500)
    }

    fun setTarget(id: String, temp: Double, done: ((String?) -> Unit)? = null) {
        val c = children.firstOrNull { it.id == id }
        val t = c?.let { temp.coerceIn(it.minTemp, it.maxTemp) } ?: temp
        children = children.map { if (it.id == id) it.withTarget(t) else it }
        notifyChanged()
        app.io.execute {
            val err = try {
                hub().setTarget(id, t); null
            } catch (e: Exception) {
                e.message ?: "chyba"
            }
            pending.remove(id)
            if (err != null) error = "Nastavení selhalo: $err"
            done?.invoke(err)
            refresh()
        }
    }

    fun setOn(id: String, on: Boolean, done: ((String?) -> Unit)? = null) {
        app.io.execute {
            val err = try {
                hub().setOn(id, on); null
            } catch (e: Exception) {
                e.message ?: "chyba"
            }
            if (err != null) error = "Nastavení selhalo: $err"
            done?.invoke(err)
            refresh()
        }
    }

    // --- scény
    class Scene(val name: String, val temps: Map<String, Double>, val off: Boolean = false) {
        fun tempFor(id: String) = temps[id] ?: temps["*"] ?: 20.0
    }

    fun scenes(): List<Scene> {
        val saved = try { org.json.JSONObject(app.settings.heatScenes) } catch (e: Exception) { org.json.JSONObject() }
        fun load(name: String, def: Double): Scene {
            val o = saved.optJSONObject(name)
            val m = mutableMapOf("*" to def)
            o?.keys()?.forEach { k -> m[k] = o.getDouble(k) }
            return Scene(name, m)
        }
        return listOf(load("Doma", 21.0), load("Noc", 18.0), load("Pryč", 16.0), Scene("Vše vypnout", emptyMap(), off = true))
    }

    /** Průměrná teplota scény přes hlavice, které jsou vidět (pro popisek tlačítka). */
    fun sceneLabelTemp(s: Scene): Double {
        val valves = children.filter { it.isValve }
        if (valves.isEmpty()) return s.temps["*"] ?: 20.0
        return Math.round(valves.map { s.tempFor(it.id) }.average() * 2) / 2.0
    }

    /** Scéna, které teď odpovídají všechny hlavice (nebo null). */
    fun activeScene(): Scene? {
        val valves = children.filter { it.isValve && it.online }
        if (valves.isEmpty()) return null
        return scenes().firstOrNull { s ->
            valves.all { v -> if (s.off) !v.on else v.on && v.targetTemp != null && Math.abs(v.targetTemp - s.tempFor(v.id)) < 0.26 }
        }
    }

    /** Uloží současné cíle hlavic do scény. */
    fun saveScene(name: String): Boolean {
        val valves = children.filter { it.isValve && it.on && it.targetTemp != null }
        if (valves.isEmpty()) return false
        val all = try { org.json.JSONObject(app.settings.heatScenes) } catch (e: Exception) { org.json.JSONObject() }
        val o = org.json.JSONObject()
        valves.forEach { o.put(it.id, it.targetTemp!!) }
        o.put("*", valves.map { it.targetTemp!! }.average())
        all.put(name, o)
        app.settings.heatScenes = all.toString()
        notifyChanged()
        return true
    }

    /** Nastaví všechny hlavice podle scény. */
    fun applyScene(name: String, done: ((String?) -> Unit)? = null) {
        val s = scenes().firstOrNull { it.name.equals(name, true) }
            ?: return done?.invoke("scéna $name neexistuje") ?: Unit
        val valves = children.filter { it.isValve }
        // displej hned ukáže nový stav, hlavice se nastaví na pozadí
        children = children.map { c ->
            if (!c.isValve) c
            else if (s.off) TapoChild(c.id, c.name, c.category, c.online, c.currentTemp, c.targetTemp, c.minTemp, c.maxTemp,
                false, false, c.humidity, c.batteryLow)
            else c.withTarget(s.tempFor(c.id).coerceIn(c.minTemp, c.maxTemp))
        }
        notifyChanged()
        app.io.execute {
            val errors = mutableListOf<String>()
            for (v in valves) {
                try {
                    if (s.off) hub().setOn(v.id, false)
                    else {
                        if (!v.on) hub().setOn(v.id, true)
                        hub().setTarget(v.id, s.tempFor(v.id).coerceIn(v.minTemp, v.maxTemp))
                    }
                } catch (e: Exception) {
                    errors += "${v.name}: ${e.message ?: "chyba"}"
                }
            }
            val err = errors.takeIf { it.isNotEmpty() }?.joinToString()
            if (err != null) error = "Scéna se nenastavila celá: $err"
            done?.invoke(err)
            refresh()
        }
    }

    private fun TapoChild.withTarget(t: Double) = TapoChild(
        id, name, category, online, currentTemp, t, minTemp, maxTemp, true, heating, humidity, batteryLow,
    )
}

/** Přehrávání v jiných aplikacích (YouTube Music, YouTube…). */
class MediaRepo(app: App) : Repo(app) {
    private val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val component = ComponentName(app, MediaListener::class.java)

    var controller: MediaController? = null; private set
    var permissionMissing = false; private set

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = notifyChanged()
        override fun onMetadataChanged(metadata: MediaMetadata?) = notifyChanged()
        override fun onSessionDestroyed() { attach() }
    }

    init {
        attach()
        try {
            msm.addOnActiveSessionsChangedListener({ attach() }, component, app.main)
        } catch (e: SecurityException) {
            permissionMissing = true
        }
    }

    fun attach() {
        val list = try {
            permissionMissing = false
            msm.getActiveSessions(component)
        } catch (e: SecurityException) {
            permissionMissing = true
            emptyList()
        }
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, app.main)
        }
        notifyChanged()
    }

    val isPlaying get() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
    val title get() = controller?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
    val artist get() = controller?.metadata?.let {
        it.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
    }
    val art get() = controller?.metadata?.let {
        it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
    }
    val appName get() = controller?.packageName?.let { pkg ->
        APPS.entries.firstOrNull { it.value == pkg }?.key ?: pkg
    }

    /** play, pause, toggle, next, previous, volume_up, volume_down. */
    fun command(action: String): Boolean {
        val t = controller?.transportControls
        when (action) {
            "volume_up" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "play" -> t?.play() ?: return false
            "pause" -> t?.pause() ?: return false
            "toggle" -> (if (isPlaying) t?.pause() else t?.play()) ?: return false
            "next" -> t?.skipToNext() ?: return false
            "previous" -> t?.skipToPrevious() ?: return false
            else -> return false
        }
        app.main.postDelayed({ notifyChanged() }, 400)
        return true
    }

    val volumePercent get() =
        100 * audio.getStreamVolume(AudioManager.STREAM_MUSIC) / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    fun setVolume(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent.coerceIn(0, 100) * max + 50) / 100, AudioManager.FLAG_SHOW_UI)
        notifyChanged()
    }

    /** Otevře aplikaci (youtube_music, youtube). Vrací false, když není nainstalovaná. */
    fun openApp(key: String, query: String? = null): Boolean {
        val pkgs = when (key) {
            "youtube" -> listOf(APPS.getValue("youtube"), APPS.getValue("newpipe"))
            "youtube_music" -> listOf(APPS.getValue("youtube_music"), APPS.getValue("simpmusic"))
            else -> listOf(APPS[key] ?: key)
        }
        for (pkg in pkgs) {
            val intent = if (!query.isNullOrBlank()) {
                Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra("query", query)
                    .takeIf { it.resolveActivity(app.packageManager) != null }
            } else null
            val launch = intent ?: app.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(launch)
            return true
        }
        return false
    }

    companion object {
        val APPS = linkedMapOf(
            "youtube_music" to "com.google.android.apps.youtube.music",
            "youtube" to "com.google.android.youtube",
            "simpmusic" to "com.maxrave.simpmusic",
            "newpipe" to "org.schabi.newpipe",
        )
    }
}
