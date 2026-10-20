package com.example.gochat.ui.custom

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.max

/**
 * Three dots that bounce in a staggered wave — the WhatsApp-style "is typing" animation.
 * Self-contained (no Lottie/extra deps); animates only while attached and [isPlaying].
 */
class TypingIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8696A0") // muted grey, like received-text secondary
    }
    private val dotCount = 3
    private var phase = 0f
    private var animator: ValueAnimator? = null

    var dotColor: Int
        get() = dotPaint.color
        set(value) { dotPaint.color = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val dotRadius = 3.5f * density
    private val dotSpacing = 5f * density      // gap between dots
    private val bounceHeight = 4f * density

    fun start() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (dotCount * dotRadius * 2 + (dotCount - 1) * dotSpacing + paddingLeft + paddingRight).toInt()
        val height = (dotRadius * 2 + bounceHeight + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(
            resolveSize(max(width, suggestedMinimumWidth), widthMeasureSpec),
            resolveSize(max(height, suggestedMinimumHeight), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val baseline = height - paddingBottom - dotRadius
        val startX = paddingLeft + dotRadius
        for (i in 0 until dotCount) {
            // Each dot lags the previous by 1/3 of the cycle, producing a travelling wave.
            val t = (phase - i / dotCount.toFloat())
            val norm = ((t % 1f) + 1f) % 1f
            // Triangle wave 0→1→0 so the dot rises then falls.
            val lift = 1f - abs(norm * 2f - 1f)
            val cx = startX + i * (dotRadius * 2 + dotSpacing)
            val cy = baseline - lift * bounceHeight
            dotPaint.alpha = (140 + 115 * lift).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, dotRadius, dotPaint)
        }
    }
}
