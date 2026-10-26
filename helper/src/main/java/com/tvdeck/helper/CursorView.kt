package com.tvdeck.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Draws the mouse pointer and a small ring when you click. */
class CursorView(context: Context, private val density: Float) : View(context) {
    private var px = 0f
    private var py = 0f
    private var ring = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22D3EE")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val arrow = Path()

    fun setPos(x: Float, y: Float) {
        px = x
        py = y
        invalidate()
    }

    fun pulse() {
        ring = true
        invalidate()
        postDelayed({
            ring = false
            invalidate()
        }, 180)
    }

    override fun onDraw(canvas: Canvas) {
        val s = 20f * density
        arrow.reset()
        arrow.moveTo(px, py)
        arrow.lineTo(px, py + 1.45f * s)
        arrow.lineTo(px + 0.36f * s, py + 1.12f * s)
        arrow.lineTo(px + 0.62f * s, py + 1.72f * s)
        arrow.lineTo(px + 0.86f * s, py + 1.6f * s)
        arrow.lineTo(px + 0.6f * s, py + 1.02f * s)
        arrow.lineTo(px + 1.05f * s, py + 1.02f * s)
        arrow.close()
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, edge)
        if (ring) canvas.drawCircle(px, py, 26f * density, ringPaint)
    }
}
