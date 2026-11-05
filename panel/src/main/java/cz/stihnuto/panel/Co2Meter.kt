package cz.stihnuto.panel

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Měřič CO₂ TFA AirCO2ntrol Coach (31.5009) / Mini (31.5006) přes USB.
 * Zařízení se hlásí jako HID (VID 0x04D9, PID 0xA052) a posílá 8bajtové
 * zprávy: CO₂ (0x50), teplota (0x42), vlhkost (0x41, jen Coach).
 * Starší kusy zprávy „šifrují“ — postup podle projektu co2meter/co2mon.
 */
class Co2Meter(app: App) : Repo(app) {
    @Volatile var co2: Int? = null; private set
    @Volatile var temp: Double? = null; private set
    @Volatile var humidity: Double? = null; private set
    @Volatile var updatedAt = 0L; private set
    @Volatile var status = "Měřič není připojený"; private set

    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private var reader: Thread? = null

    init {
        val f = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> { stop(); status = "Měřič odpojen"; notifyChanged() }
                    else -> connect()
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(r, f)
        app.main.postDelayed({ connect() }, 5000)
    }

    /** Seznam USB zařízení, která hodiny vidí (pro test). */
    fun devices(): List<String> = usb.deviceList.values.map {
        "%04x:%04x %s".format(it.vendorId, it.productId, it.productName ?: "")
    }

    val fresh get() = co2 != null && System.currentTimeMillis() - updatedAt < 2 * 60_000L

    fun connect() {
        if (reader != null) return
        val all = usb.deviceList.values
        val dev = all.firstOrNull { it.vendorId == VID && it.productId == PID }
        if (dev == null) {
            status = if (all.isEmpty()) "Hodiny nevidí žádné USB zařízení (port je možná jen nabíjecí)"
            else "Měřič nenalezen. Hodiny vidí: ${devices().joinToString()}"
            notifyChanged(); return
        }
        if (!usb.hasPermission(dev)) {
            status = "Povol přístup k USB na displeji"
            notifyChanged()
            val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(dev, pi)
            return
        }
        start(dev)
    }

    private fun start(dev: UsbDevice) {
        val conn = usb.openDevice(dev) ?: run { status = "USB nejde otevřít"; notifyChanged(); return }
        val intf = dev.getInterface(0)
        conn.claimInterface(intf, true)
        val ep = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: run {
            status = "Měřič nemá datový kanál"; notifyChanged(); conn.close(); return
        }
        val key = ByteArray(8) // klíč „0“ — u nešifrujících kusů je jedno jaký
        // HID SET_REPORT (feature) s klíčem — měřič pak začne posílat data
        conn.controlTransfer(0x21, 0x09, 0x0300, intf.id, key, key.size, 2000)
        status = "Připojeno, čekám na data…"
        notifyChanged()
        reader = Thread({
            val buf = ByteArray(8)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val n = conn.bulkTransfer(ep, buf, 8, 5000)
                    if (n != 8) continue
                    val d = decode(buf, key) ?: continue
                    val op = d[0]
                    val v = (d[1] shl 8) or d[2]
                    var changed = true
                    when (op) {
                        0x50 -> co2 = v
                        0x42 -> temp = v / 16.0 - 273.15
                        0x41 -> if (v in 1..10000) humidity = v / 100.0
                        else -> changed = false
                    }
                    if (changed) {
                        updatedAt = System.currentTimeMillis()
                        status = "Měří ✓"
                        notifyChanged()
                    }
                }
            } catch (e: Exception) {
                Log.w("Panel", "co2", e)
            } finally {
                try { conn.releaseInterface(intf); conn.close() } catch (_: Exception) {}
            }
        }, "co2").apply { isDaemon = true; start() }
    }

    fun stop() {
        reader?.interrupt()
        reader = null
    }

    /** Vrátí 5 bajtů (op, hi, lo, sum, 0x0d) jako Int, nebo null při chybě. */
    private fun decode(raw: ByteArray, key: ByteArray): IntArray? {
        val p = IntArray(8) { raw[it].toInt() and 0xFF }
        if (valid(p)) return p
        // „šifrované“ zprávy starších kusů
        val k = IntArray(8) { key[it].toInt() and 0xFF }
        val cstate = intArrayOf(0x48, 0x74, 0x65, 0x6D, 0x70, 0x39, 0x39, 0x65)
        val shuffle = intArrayOf(2, 4, 0, 7, 1, 6, 5, 3)
        val p1 = IntArray(8)
        for (i in 0 until 8) p1[shuffle[i]] = p[i]
        val p2 = IntArray(8) { p1[it] xor k[it] }
        val p3 = IntArray(8) { ((p2[it] shr 3) or (p2[(it + 7) % 8] shl 5)) and 0xFF }
        val out = IntArray(8) { i ->
            val c = ((cstate[i] shr 4) or (cstate[i] shl 4)) and 0xFF
            (0x100 + p3[i] - c) and 0xFF
        }
        return if (valid(out)) out else null
    }

    private fun valid(d: IntArray) = d[4] == 0x0D && ((d[0] + d[1] + d[2]) and 0xFF) == d[3]

    companion object {
        const val VID = 0x04D9
        const val PID = 0xA052
        const val ACTION_PERMISSION = "cz.stihnuto.panel.USB_PERMISSION"

        /** Barva a popis podle koncentrace CO₂. */
        fun level(ppm: Int): Pair<Int, String> = when {
            ppm < 800 -> Ui.GOOD to "dobrý vzduch"
            ppm < 1200 -> Ui.ACCENT to "vyvětrat"
            else -> Ui.HEAT to "větrat hned"
        }
    }
}
