package com.tvdeck.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt

/** Talks to the TVDeck Helper running on the TV (draws the cursor, performs taps). */
object CursorClient {
    const val PORT = 7788

    var ready by mutableStateOf(false)

    private val lock = Any()
    private var sock: Socket? = null
    private var out: OutputStream? = null
    private var host = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chan = Channel<String>(Channel.UNLIMITED)
    private var consumer: Job? = null
    private var mx = 0f
    private var my = 0f
    private var sx = 0f
    private var sy = 0f
    private var flushPending = false

    /** True when the helper on the TV answers. */
    suspend fun check(): Boolean = withContext(Dispatchers.IO) {
        val ok = connectBlocking()
        ready = ok
        ok
    }

    private fun connectBlocking(): Boolean {
        val h = AdbManager.deviceHost
        if (h.isEmpty()) return false
        return synchronized(lock) {
            val s0 = sock
            if (s0 != null && host == h && !s0.isClosed && out != null) {
                true
            } else {
                try { s0?.close() } catch (_: Exception) {}
                sock = null
                out = null
                try {
                    val s = Socket()
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(h, PORT), 1500)
                    sock = s
                    out = s.getOutputStream()
                    host = h
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    private fun writeOnce(line: String): Boolean {
        return try {
            synchronized(lock) {
                val o = out ?: return false
                o.write((line + "\n").toByteArray())
                o.flush()
            }
            true
        } catch (e: Exception) {
            synchronized(lock) {
                try { sock?.close() } catch (_: Exception) {}
                sock = null
                out = null
            }
            false
        }
    }

    private fun sendLine(line: String) {
        if (!writeOnce(line)) {
            if (connectBlocking() && writeOnce(line)) return
            ready = false
        }
    }

    private fun ensureConsumer() {
        if (consumer?.isActive == true) return
        consumer = scope.launch {
            while (isActive) {
                val cmd = chan.receive()
                if (cmd == "FLUSH") {
                    delay(8)
                    flushMoves()
                } else {
                    flushMoves()
                    sendLine(cmd)
                }
            }
        }
    }

    private fun flushMoves() {
        var ix = 0
        var iy = 0
        var jx = 0
        var jy = 0
        synchronized(lock) {
            ix = mx.roundToInt()
            iy = my.roundToInt()
            jx = sx.roundToInt()
            jy = sy.roundToInt()
            mx -= ix
            my -= iy
            sx -= jx
            sy -= jy
            flushPending = false
        }
        if (ix != 0 || iy != 0) sendLine("M $ix $iy")
        if (jx != 0 || jy != 0) sendLine("S $jx $jy")
    }

    private fun cmd(s: String) {
        ensureConsumer()
        chan.trySend(s)
    }

    private fun signalFlush() {
        ensureConsumer()
        synchronized(lock) {
            if (flushPending) return
            flushPending = true
        }
        chan.trySend("FLUSH")
    }

    fun move(dx: Float, dy: Float) {
        synchronized(lock) { mx += dx; my += dy }
        signalFlush()
    }

    fun scroll(dx: Float, dy: Float) {
        synchronized(lock) { sx += dx; sy += dy }
        signalFlush()
    }

    fun click() = cmd("C")
    fun longClick() = cmd("L")
    fun center() = cmd("Z")
    fun show(pinned: Boolean) = cmd(if (pinned) "V 1" else "V 0")
}
