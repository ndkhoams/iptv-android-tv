package com.samsung.tvremote

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * Custom audio waveform equalizer visualizer for voice listening mode.
 * Renders animated vertical bars oscillating with idle wave and mic RMS audio level.
 */
class VoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 7
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var targetRms: Float = 0f
    private var currentRms: Float = 0f
    private var phase: Float = 0f

    private var animator: ValueAnimator? = null

    // Base fractions for symmetric bell curve
    private val baseFractions = floatArrayOf(0.30f, 0.52f, 0.80f, 1.0f, 0.80f, 0.52f, 0.30f)

    init {
        startAnimation()
    }

    fun startAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                phase = va.animatedValue as Float
                // Smooth interpolation towards incoming audio RMS
                currentRms += (targetRms - currentRms) * 0.25f
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
        currentRms = 0f
        targetRms = 0f
        invalidate()
    }

    fun setRms(rmsdB: Float) {
        // SpeechRecognizer rmsdB typically ranges from -2dB to 10+ dB
        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        targetRms = normalized
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val totalSpacing = w * 0.38f
        val spacing = totalSpacing / (barCount + 1)
        val barWidth = (w - totalSpacing) / barCount
        val minBarHeight = h * 0.16f
        val maxAvailable = h * 0.88f

        for (i in 0 until barCount) {
            val cx = spacing * (i + 1) + barWidth * (i + 0.5f)

            // Base bell curve multiplier
            val baseFrac = baseFractions[i]

            // Idle waving wave effect
            val waveOffset = sin(phase + i * 0.85f) * 0.20f

            // Dynamic height based on RMS volume and wave
            val dynamicFactor = (baseFrac * (0.36f + waveOffset) + currentRms * baseFrac * 0.64f).coerceIn(0.12f, 1.0f)
            val barHeight = minBarHeight + (maxAvailable - minBarHeight) * dynamicFactor

            val top = (h - barHeight) / 2f
            val bottom = top + barHeight
            val left = cx - barWidth / 2f
            val right = cx + barWidth / 2f

            // Modern glowing gradient: Bright Cyan (#00E5FF) to Royal Blue (#007AFF)
            val shader = LinearGradient(
                cx, top, cx, bottom,
                Color.parseColor("#00E5FF"),
                Color.parseColor("#007AFF"),
                Shader.TileMode.CLAMP
            )
            paint.shader = shader

            rect.set(left, top, right, bottom)
            val cornerRadius = barWidth / 2f
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}
