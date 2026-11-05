package cz.kuchapka.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Na pozadí (zhruba co 15 minut) se podívá, jestli hodiny neposlaly nové položky
 * nákupu, a ukáže upozornění. Do seznamu je přidá až web po otevření aplikace.
 */
class PollJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { check(this) } catch (e: Exception) {}
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 42

        fun prefs(c: Context) = c.getSharedPreferences("kuchapka", Context.MODE_PRIVATE)

        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            if (prefs(c).getString("topic", "").isNullOrEmpty()) { js.cancel(ID); return }
            if (js.getPendingJob(ID) != null) return
            js.schedule(JobInfo.Builder(ID, ComponentName(c, PollJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60_000L)
                .setPersisted(true)
                .build())
        }

        fun clearNotification(c: Context) {
            c.getSystemService(NotificationManager::class.java).cancel(ID)
            prefs(c).edit().remove("pending").apply()
        }

        private fun check(c: Context) {
            val p = prefs(c)
            val topic = p.getString("topic", "").orEmpty()
            if (topic.isEmpty()) return
            val conn = URL("https://ntfy.sh/$topic/json?poll=1&since=all").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 20000
            val lines = conn.inputStream.bufferedReader().readLines()
            val notified = p.getString("notified", "").orEmpty().split(',').filter { it.isNotEmpty() }.toMutableList()
            val fresh = mutableListOf<String>()
            for (line in lines) {
                val ev = try { JSONObject(line) } catch (e: Exception) { continue }
                if (ev.optString("event") != "message") continue
                val m = try { JSONObject(ev.optString("message")) } catch (e: Exception) { continue }
                val id = m.optString("id")
                if (id.isEmpty() || id in notified) continue
                notified += id
                fresh += m.optString("text")
            }
            p.edit().putString("notified", notified.takeLast(300).joinToString(",")).apply()
            if (fresh.isEmpty()) return
            val pending = (p.getString("pending", "").orEmpty().split('\n').filter { it.isNotEmpty() } + fresh)
            p.edit().putString("pending", pending.joinToString("\n")).apply()

            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("nakup", "Nákup z hodin", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(c, "nakup")
                .setSmallIcon(android.R.drawable.ic_menu_add)
                .setContentTitle("Do nákupu z hodin")
                .setContentText(pending.joinToString(", "))
                .setStyle(Notification.BigTextStyle().bigText(pending.joinToString(", ")))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try { nm.notify(ID, n) } catch (e: SecurityException) {}
        }
    }
}
