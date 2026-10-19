package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class LinearPriceGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint().apply {
        color = 0xFFE0E0E0.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val progressPaint = Paint().apply {
        color = 0xFF2E7D32.toInt() // M3 Green
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val pinPaint = Paint().apply {
        color = 0xFF1976D2.toInt() // Blue Pin
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val pinBorderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = 0xFF333333.toInt()
        textSize = 32f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private var minPriceText = "최저가"
    private var maxPriceText = "최고가"
    private var currentPriceText = "평균가"
    private var percent = 50 // 0 to 100%

    fun setPriceData(minFormatted: String, maxFormatted: String, currentFormatted: String, posPercent: Int) {
        minPriceText = minFormatted
        maxPriceText = maxFormatted
        currentPriceText = currentFormatted
        percent = posPercent.coerceIn(5, 95)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0 || h <= 0) return

        val paddingLeft = 40f
        val paddingRight = 40f
        val trackWidth = w - paddingLeft - paddingRight
        val trackY = h * 0.5f
        val trackHeight = 20f

        // 1. 전체 트랙 선
        val trackRect = RectF(paddingLeft, trackY - trackHeight / 2f, w - paddingRight, trackY + trackHeight / 2f)
        canvas.drawRoundRect(trackRect, trackHeight / 2f, trackHeight / 2f, trackPaint)

        // 2. 진행도 채우기 선
        val pinX = paddingLeft + (trackWidth * (percent / 100f))
        val progressRect = RectF(paddingLeft, trackY - trackHeight / 2f, pinX, trackY + trackHeight / 2f)
        canvas.drawRoundRect(progressRect, trackHeight / 2f, trackHeight / 2f, progressPaint)

        // 3. 현재 포지션 핀 (Circle Marker)
        canvas.drawCircle(pinX, trackY, 22f, pinPaint)
        canvas.drawCircle(pinX, trackY, 22f, pinBorderPaint)

        // 4. 텍스트 라벨 (최저가 / 현재 포지션 / 최고가)
        textPaint.textSize = 28f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = 0xFF757575.toInt()
        canvas.drawText(minPriceText, paddingLeft, h - 10f, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(maxPriceText, w - paddingRight, h - 10f, textPaint)

        // 현재 포지션 핀 상단 라벨
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = 0xFF1976D2.toInt()
        textPaint.textSize = 32f
        canvas.drawText("📍 $currentPriceText", pinX.coerceIn(100f, w - 100f), trackY - 32f, textPaint)
    }
}
