package cz.stihnuto.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService

/** Po zapnutí hodin spustí panel. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Prázdná služba. Android přes ni povolí číst a ovládat přehrávání jiných
 * aplikací (MediaSessionManager). Povolí se jednou přes adb.
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        App.instance.media.attach()
    }
}
