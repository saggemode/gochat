package com.example.gochat.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.gochat.R
import kotlin.math.max

/**
 * A custom view that draws a visual waveform.
 * Supports real-time updates for recording and static display for playback.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }

    private var barColor: Int = ContextCompat.getColor(context, R.color.gochat_emerald_light)
    private var progressColor: Int = ContextCompat.getColor(context, R.color.gochat_emerald_primary)
    
    private var bars = mutableListOf<Float>()
    private var progress: Float = 0f // 0.0 to 1.0

    private var barWidth = 6f
    private var barGap = 4f
    private var minBarHeight = 4f

    fun setWaveform(data: List<Float>) {
        bars.clear()
        bars.addAll(data)
        invalidate()
    }

    fun addBar(amplitude: Float) {
        // Normalize amplitude to 0.1 - 1.0 range roughly
        val normalized = (amplitude / 32767f).coerceIn(0.1f, 1.0f)
        bars.add(normalized)
        if (bars.size * (barWidth + barGap) > width) {
            bars.removeAt(0)
        }
        invalidate()
    }

    var onSeekListener: ((progress: Float) -> Unit)? = null
    var isInteractive: Boolean = true

    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        invalidate()
    }

    fun clear() {
        bars.clear()
        invalidate()
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!isInteractive || width == 0) return super.onTouchEvent(event)

        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val p = (event.x / width.toFloat()).coerceIn(0f, 1f)
                setProgress(p)
                onSeekListener?.invoke(p)
                return true
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val p = (event.x / width.toFloat()).coerceIn(0f, 1f)
                setProgress(p)
                onSeekListener?.invoke(p)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return

        val centerY = height / 2f
        val totalBarWidth = barWidth + barGap
        
        // Calculate how many bars we can fit
        val maxBars = (width / totalBarWidth).toInt()
        val barsToDraw = if (bars.size > maxBars) bars.takeLast(maxBars) else bars
        
        val startX = 0f
        
        barsToDraw.forEachIndexed { index, amplitude ->
            val barHeight = max(amplitude * height * 0.8f, minBarHeight)
            val left = startX + index * totalBarWidth
            val top = centerY - barHeight / 2f
            val right = left + barWidth
            val bottom = centerY + barHeight / 2f

            val barProgress = (index.toFloat() / barsToDraw.size)
            paint.color = if (barProgress <= progress) progressColor else barColor
            
            canvas.drawRoundRect(left, top, right, bottom, barWidth / 2f, barWidth / 2f, paint)
        }
    }
}
