package com.tvdeck.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.URL
import java.net.URLConnection

/**
 * Makes TV control work on a Wi-Fi that has no internet.
 * Android normally sends app traffic over mobile data when the Wi-Fi has no internet, so the TV
 * (which lives on the Wi-Fi) can't be reached. We pin the app to the Wi-Fi network instead.
 */
object NetBinder {
    var wifiConnected by mutableStateOf(false)
    var wifiNoInternet by mutableStateOf(false)
    @Volatile var network: Network? = null
    private var cb: ConnectivityManager.NetworkCallback? = null
    private var cm: ConnectivityManager? = null

    fun start(ctx: Context) {
        if (cb != null) return
        val m = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        cm = m
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                wifiConnected = true
                try { m.bindProcessToNetwork(n) } catch (_: Exception) {}
            }

            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                wifiNoInternet = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }

            override fun onLost(n: Network) {
                if (network == n) {
                    network = null
                    wifiConnected = false
                    wifiNoInternet = false
                    try { m.bindProcessToNetwork(null) } catch (_: Exception) {}
                }
            }
        }
        try {
            m.requestNetwork(req, c)
            cb = c
        } catch (_: Exception) {}
    }

    /** A network that really reaches the internet (mobile data when the Wi-Fi has none), if any. */
    @Suppress("DEPRECATION")
    private fun internetNetwork(): Network? {
        val m = cm ?: return null
        return try {
            m.allNetworks.firstOrNull { n ->
                val caps = m.getNetworkCapabilities(n)
                caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        } catch (_: Exception) { null }
    }

    /** Opens a web connection over a network that has internet (used for icon lookups and downloads). */
    fun open(url: String): URLConnection {
        val u = URL(url)
        val n = internetNetwork()
        return if (n != null) n.openConnection(u) else u.openConnection()
    }
}
