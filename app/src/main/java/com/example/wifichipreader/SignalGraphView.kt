package com.example.wifichipreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class SignalGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxDataPoints = 60 // Храним историю за 60 секунд
    private val data = mutableListOf<Int>()

    private val linePaint = Paint().apply {
        color = Color.parseColor("#4CAF50") // Зеленая линия
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#334CAF50") // Полупрозрачная зеленая заливка
        style = Paint.Style.FILL
    }

    fun addDataPoint(rssi: Int) {
        if (data.size >= maxDataPoints) {
            data.removeAt(0) // Удаляем самую старую точку
        }
        data.add(rssi)
        invalidate() // Даем команду перерисовать график
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val path = Path()
        val fillPath = Path()

        val stepX = viewWidth / (maxDataPoints - 1).coerceAtLeast(1)

        // Задаем пределы сигнала: от -100 (дно) до -30 (максимум)
        val minY = -100f
        val maxY = -30f

        var firstX = 0f
        var lastX = 0f

        for (i in data.indices) {
            val x = i * stepX
            // Нормализуем значение от 0.0 до 1.0
            val normalized = ((data[i] - minY) / (maxY - minY)).coerceIn(0f, 1f)
            // Инвертируем Y, так как в Canvas координата 0 находится сверху
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

        // Замыкаем контур для заливки
        fillPath.lineTo(lastX, viewHeight)
        fillPath.lineTo(firstX, viewHeight)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}