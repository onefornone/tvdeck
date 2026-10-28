package com.tvdeck.helper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Draws a mouse cursor over everything on the TV and performs taps / scrolls.
 * The TVDeck phone app sends simple text commands over the local Wi-Fi network (port 7788):
 *   M dx dy   move the cursor        C   click          L   long click
 *   S dx dy   scroll / swipe         Z   centre cursor  V 1/0  show / hide cursor
 *   T x y     tap at screen point    W x1 y1 x2 y2 ms   swipe between screen points
 *   B back    H home                 P   ping
 */
class CursorService : AccessibilityService() {
    companion object {
        const val PORT = 7788
    }

    private var cursor: CursorView? = null
    private val ui = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private var cx = 0f
    private var cy = 0f
    private var sw = 1920
    private var sh = 1080
    @Volatile private var busy = false
    private val autoHide = Runnable { cursor?.visibility = View.INVISIBLE }

    @Suppress("DEPRECATION")
    override fun onServiceConnected() {
        super.onServiceConnected()
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        w.defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels
        sh = dm.heightPixels
        cx = sw / 2f
        cy = sh / 2f
        if (cursor == null) {
            val v = CursorView(this, dm.density)
            v.visibility = View.INVISIBLE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            w.addView(v, lp)
            v.setPos(cx, cy)
            cursor = v
        }
        startServer()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        try { server?.close() } catch (e: Exception) {}
        server = null
        super.onDestroy()
    }

    private fun startServer() {
        if (server != null) return
        Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                server = ss
                while (!ss.isClosed) {
                    val s = ss.accept()
                    Thread { serve(s) }.start()
                }
            } catch (e: Exception) {
                server = null
            }
        }.start()
    }

    private fun serve(s: Socket) {
        try {
            s.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val out = PrintWriter(s.getOutputStream(), true)
            while (true) {
                val line = reader.readLine() ?: break
                handle(line, out)
            }
        } catch (e: Exception) {
        } finally {
            try { s.close() } catch (e: Exception) {}
            ui.post { cursor?.visibility = View.INVISIBLE }
        }
    }

    private fun handle(line: String, out: PrintWriter) {
        val p = line.trim().split(" ")
        when (p[0]) {
            "M" -> if (p.size >= 3) move(p[1].toFloatOrNull() ?: 0f, p[2].toFloatOrNull() ?: 0f)
            "C" -> click(60)
            "L" -> click(700)
            "S" -> if (p.size >= 3) swipe(p[1].toFloatOrNull() ?: 0f, p[2].toFloatOrNull() ?: 0f)
            "Z" -> {
                cx = sw / 2f
                cy = sh / 2f
                ui.post {
                    cursor?.setPos(cx, cy)
                    showTemp()
                }
            }
            "V" -> ui.post { if (p.getOrNull(1) == "1") showTemp() else cursor?.visibility = View.INVISIBLE }
            "T" -> if (p.size >= 3) tapAt(p[1].toFloatOrNull() ?: 0f, p[2].toFloatOrNull() ?: 0f)
            "W" -> if (p.size >= 6) swipeAt(
                p[1].toFloatOrNull() ?: 0f, p[2].toFloatOrNull() ?: 0f,
                p[3].toFloatOrNull() ?: 0f, p[4].toFloatOrNull() ?: 0f, p[5].toLongOrNull() ?: 250L
            )
            "B" -> ui.post { performGlobalAction(GLOBAL_ACTION_BACK) }
            "H" -> ui.post { performGlobalAction(GLOBAL_ACTION_HOME) }
            "P" -> out.println("OK $sw $sh")
        }
    }

    private fun showTemp() {
        val v = cursor ?: return
        v.visibility = View.VISIBLE
        ui.removeCallbacks(autoHide)
        ui.postDelayed(autoHide, 3000)
    }

    private fun move(dx: Float, dy: Float) {
        cx = (cx + dx).coerceIn(0f, (sw - 1).toFloat())
        cy = (cy + dy).coerceIn(0f, (sh - 1).toFloat())
        val x = cx
        val y = cy
        ui.post {
            cursor?.setPos(x, y)
            showTemp()
        }
    }

    private fun click(durationMs: Long) {
        val x = cx
        val y = cy
        ui.post {
            cursor?.pulse()
            val path = Path()
            path.moveTo(x, y)
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val g = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(g, null, null)
            showTemp()
        }
    }

    private fun swipe(dx: Float, dy: Float) {
        if (busy) return
        busy = true
        val x0 = cx
        val y0 = cy
        val x1 = (x0 + dx.coerceIn(-700f, 700f)).coerceIn(1f, (sw - 2).toFloat())
        val y1 = (y0 + dy.coerceIn(-700f, 700f)).coerceIn(1f, (sh - 2).toFloat())
        ui.post {
            val path = Path()
            path.moveTo(x0, y0)
            path.lineTo(x1, y1)
            val stroke = GestureDescription.StrokeDescription(path, 0, 160)
            val g = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    busy = false
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    busy = false
                }
            }, null)
            if (!ok) busy = false
            showTemp()
        }
    }

    private fun tapAt(x: Float, y: Float) {
        val px = x.coerceIn(0f, (sw - 1).toFloat())
        val py = y.coerceIn(0f, (sh - 1).toFloat())
        ui.post {
            val path = Path()
            path.moveTo(px, py)
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
    }

    private fun swipeAt(x0: Float, y0: Float, x1: Float, y1: Float, dur: Long) {
        ui.post {
            val path = Path()
            path.moveTo(x0.coerceIn(0f, (sw - 1).toFloat()), y0.coerceIn(0f, (sh - 1).toFloat()))
            path.lineTo(x1.coerceIn(0f, (sw - 1).toFloat()), y1.coerceIn(0f, (sh - 1).toFloat()))
            val stroke = GestureDescription.StrokeDescription(path, 0, dur.coerceIn(60L, 1500L))
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
    }
}
