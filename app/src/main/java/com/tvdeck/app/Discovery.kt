package com.tvdeck.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

/** Finds TVs on the network using their own announcements (mDNS), so they show up with real names. */
object Discovery {
    class Tv(val name: String, val host: String, val port: Int, val kind: String, val rank: Int)

    val found = mutableStateListOf<Tv>()
    val adbReady = mutableStateMapOf<String, Boolean>()
    var running by mutableStateOf(false)

    private val types = listOf(
        "_googlecast._tcp.",
        "_androidtvremote2._tcp.",
        "_amzn-wplay._tcp.",
        "_adb._tcp."
    )

    private var mgr: NsdManager? = null
    private val listeners = ArrayList<NsdManager.DiscoveryListener>()
    private val queue = ArrayList<NsdServiceInfo>()
    private var resolving = false
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(ctx: Context) {
        stop()
        val m = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        mgr = m
        running = true
        for (type in types) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    main.post { enqueue(serviceInfo) }
                }
            }
            try {
                m.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
                listeners.add(l)
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        val m = mgr
        if (m != null) {
            for (l in listeners) {
                try { m.stopServiceDiscovery(l) } catch (_: Exception) {}
            }
        }
        listeners.clear()
        queue.clear()
        resolving = false
        running = false
    }

    private fun enqueue(info: NsdServiceInfo) {
        queue.add(info)
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || queue.isEmpty()) return
        val m = mgr ?: return
        val info = queue.removeAt(0)
        resolving = true
        try {
            m.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post { resolving = false; resolveNext() }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    main.post {
                        add(serviceInfo)
                        resolving = false
                        resolveNext()
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
            resolveNext()
        }
    }

    @Suppress("DEPRECATION")
    private fun add(si: NsdServiceInfo) {
        val addr = si.host as? Inet4Address ?: return
        val ip = addr.hostAddress ?: return
        val type = si.serviceType ?: ""
        val attrs = try { si.attributes } catch (e: Exception) { null }
        val fn = attrs?.get("fn")?.let { String(it, Charsets.UTF_8) }
        val raw = (fn ?: si.serviceName ?: ip).replace("\\032", " ").replace("\\ ", " ").trim()
        val kind: String
        val rank: Int
        var port = 5555
        when {
            type.contains("googlecast") -> { kind = "Google TV / Chromecast"; rank = 4 }
            type.contains("androidtvremote2") -> { kind = "Android TV"; rank = 3 }
            type.contains("amzn-wplay") -> { kind = "Fire TV"; rank = 2 }
            else -> { kind = "ADB"; rank = 1; port = if (si.port > 0) si.port else 5555 }
        }
        val tv = Tv(raw.ifBlank { ip }, ip, port, kind, rank)
        val i = found.indexOfFirst { it.host == ip }
        if (i < 0) found.add(tv)
        else if (found[i].rank < rank) found[i] = tv
        probe(ip, port)
    }

    private fun probe(ip: String, port: Int) {
        scope.launch {
            val ok = try {
                Socket().use { it.connect(InetSocketAddress(ip, port), 800) }
                true
            } catch (e: Exception) { false }
            adbReady[ip] = ok
        }
    }
}
