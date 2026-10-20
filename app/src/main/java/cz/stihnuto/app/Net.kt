package cz.stihnuto.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager

object Net {
    /** A network that Android itself has verified can reach the internet. */
    fun isOnline(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun isScreenOn(c: Context): Boolean {
        val pm = c.getSystemService(PowerManager::class.java) ?: return true
        return pm.isInteractive
    }
}
