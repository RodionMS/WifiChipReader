package com.example.wifichipreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class SignalGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxDataPoints = 60
    private val data = mutableListOf<Int>()

    private val linePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.accent_green)
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.graph_fill)
        style = Paint.Style.FILL
    }

    fun addDataPoint(rssi: Int) {
        if (data.size >= maxDataPoints) {
            data.removeAt(0)
        }
        data.add(rssi)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val path = Path()
        val fillPath = Path()

        val stepX = viewWidth / (maxDataPoints - 1).coerceAtLeast(1)

        val minY = -100f
        val maxY = -30f

        var firstX = 0f
        var lastX = 0f

        for (i in data.indices) {
            val x = i * stepX
            val normalized = ((data[i] - minY) / (maxY - minY)).coerceIn(0f, 1f)
            val y = viewHeight - (normalized * viewHeight)

            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, y)
                firstX = x
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            lastX = x
        }

        fillPath.lineTo(lastX, viewHeight)
        fillPath.lineTo(firstX, viewHeight)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}