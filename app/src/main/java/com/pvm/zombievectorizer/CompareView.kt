package com.pvm.zombievectorizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Muestra la imagen original y el vector, separados por un deslizador que se arrastra con el dedo. */
class CompareView(ctx: Context) : View(ctx) {
    var before: Bitmap? = null
    var after: Bitmap? = null
    private var split = 0.5f
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.GREEN; strokeWidth = 3f * d }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.GREEN }
    private val knobText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.BG; textSize = 16f * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA000000.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.TEXT; textSize = 12f * d; isFakeBoldText = true }
    private val back = Paint().apply { color = Pal.PANEL2 }
    private val dst = RectF()

    override fun onDraw(c: Canvas) {
        val a = after ?: return
        val b = before ?: a
        val s = min(width.toFloat() / a.width, height.toFloat() / a.height)
        val dw = a.width * s
        val dh = a.height * s
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        dst.set(left, top, left + dw, top + dh)
        c.drawRect(dst, back)
        c.drawBitmap(b, null, dst, paint)
        val sx = left + dw * split
        c.save()
        c.clipRect(sx, top, left + dw, top + dh)
        c.drawBitmap(a, null, dst, paint)
        c.restore()
        c.drawLine(sx, top, sx, top + dh, line)
        val cy = top + dh / 2f
        c.drawCircle(sx, cy, 17f * d, knob)
        c.drawText("◄►", sx, cy + 6f * d, knobText)
        tag(c, "ORIGINAL", left + 8f * d, top + 8f * d, false)
        tag(c, "VECTOR", left + dw - 8f * d, top + 8f * d, true)
    }

    private fun tag(c: Canvas, text: String, x: Float, y: Float, alignRight: Boolean) {
        val w = label.measureText(text)
        val pad = 6f * d
        val l = if (alignRight) x - w - 2 * pad else x
        c.drawRoundRect(l, y, l + w + 2 * pad, y + 22f * d, 8f * d, 8f * d, pill)
        c.drawText(text, l + pad, y + 15f * d, label)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val a = after ?: return false
        val s = min(width.toFloat() / a.width, height.toFloat() / a.height)
        val dw = a.width * s
        val left = (width - dw) / 2f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                split = ((e.x - left) / dw).coerceIn(0f, 1f)
                invalidate()
            }
        }
        return true
    }
}
