package com.multidim.tridekeract

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * Live meters for the Feel panel. Top row: what each of the 15 bands hears (its level, 0–1).
 * Bottom row: how far each hidden dimension (4D–15D) is open. Plus the raw loudness, so it's
 * clear whether sound is arriving at all.
 */
class MeterView(ctx: Context, private val viz: Viz, private val fg: Int, private val muted: Int, private val accent: Int) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f * density; color = muted; typeface = android.graphics.Typeface.MONOSPACE }
    private val lv = DoubleArray(Viz.HB)
    private val op = DoubleArray(Viz.DIM)
    private var rms = 0.0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (78 * density).toInt())
    }

    override fun onDraw(c: Canvas) {
        synchronized(viz) {
            for (i in 0 until Viz.HB) lv[i] = viz.level[i]
            for (k in 0 until Viz.DIM) op[k] = viz.hOpen[k]
            rms = viz.loudness
        }
        val w = width.toFloat()
        val label = 44 * density
        val rowH = 22 * density
        fun row(y: Float, name: String, n: Int, value: (Int) -> Double, color: Int) {
            c.drawText(name, 0f, y + rowH * 0.7f, text)
            val cw = (w - label) / n
            for (i in 0 until n) {
                val x = label + i * cw
                bar.color = muted; bar.alpha = 50
                c.drawRect(x + 1, y, x + cw - 1, y + rowH, bar)
                val v = value(i).coerceIn(0.0, 1.0).toFloat()
                bar.color = color; bar.alpha = 255
                c.drawRect(x + 1, y + rowH * (1 - v), x + cw - 1, y + rowH, bar)
            }
        }
        row(0f, "bands", Viz.HB, { lv[it] }, accent)
        row(rowH + 4 * density, "open", Viz.DIM - 3, { op[it + 3] }, fg)
        c.drawText("loudness %.3f".format(rms), 0f, rowH * 2 + 20 * density, text)
        if (isShown) postInvalidateOnAnimation()
    }
}
