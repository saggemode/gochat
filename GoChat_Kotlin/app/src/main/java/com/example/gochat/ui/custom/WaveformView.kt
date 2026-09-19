package com.example.gochat.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.gochat.R
import kotlin.math.max

/**
 * A custom view that draws a visual waveform.
 * Supports real-time updates for recording and interactive scrubbable display for playback preview.
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

    private var barColor: Int = ContextCompat.getColor(context, R.color.gochat_text_muted)
    private var progressColor: Int = ContextCompat.getColor(context, R.color.gochat_emerald_primary)
    private var liveRecordingColor: Int = ContextCompat.getColor(context, R.color.gochat_emerald_light)

    private val bars = mutableListOf<Float>()
    private val rawAmplitudes = mutableListOf<Float>()

    private var progress: Float = 0f // 0.0 to 1.0
    private var isLiveRecording: Boolean = false

    private var barWidth = 7f
    private var barGap = 4f
    private var minBarHeight = 5f

    var onSeekListener: ((progress: Float) -> Unit)? = null
    var isInteractive: Boolean = true

    fun setWaveform(data: List<Float>) {
        isLiveRecording = false
        bars.clear()
        bars.addAll(data)
        invalidate()
    }

    fun addBar(amplitude: Float) {
        val normalized = (amplitude / 32767f).coerceIn(0.12f, 1.0f)
        rawAmplitudes.add(normalized)

        if (!isLiveRecording) {
            isLiveRecording = true
        }

        bars.add(normalized)
        val totalBarWidth = barWidth + barGap
        if (width > 0 && bars.size * totalBarWidth > width) {
            bars.removeAt(0)
        }
        invalidate()
    }

    fun switchToRecordingMode() {
        isLiveRecording = true
        isInteractive = false
        bars.clear()
        rawAmplitudes.clear()
        progress = 0f
        invalidate()
    }

    fun switchToPreviewMode(customBars: List<Float>? = null) {
        isLiveRecording = false
        isInteractive = true
        progress = 0f
        bars.clear()

        val normalized = customBars ?: getNormalizedWaveform(calculateOptimalBarCount())
        bars.addAll(normalized)
        invalidate()
    }

    fun getNormalizedWaveform(targetCount: Int = 36): List<Float> {
        if (rawAmplitudes.isEmpty()) {
            return List(targetCount) { 0.25f }
        }

        if (rawAmplitudes.size <= targetCount) {
            val step = rawAmplitudes.size.toFloat() / targetCount
            return List(targetCount) { i ->
                val index = (i * step).toInt().coerceIn(0, rawAmplitudes.size - 1)
                rawAmplitudes[index]
            }
        }

        val result = mutableListOf<Float>()
        val chunkSize = rawAmplitudes.size.toFloat() / targetCount

        for (i in 0 until targetCount) {
            val startIdx = (i * chunkSize).toInt()
            val endIdx = ((i + 1) * chunkSize).toInt().coerceAtMost(rawAmplitudes.size)
            if (startIdx < endIdx) {
                var maxVal = 0.12f
                for (j in startIdx until endIdx) {
                    if (rawAmplitudes[j] > maxVal) maxVal = rawAmplitudes[j]
                }
                result.add(maxVal)
            } else {
                result.add(0.2f)
            }
        }
        return result
    }

    private fun calculateOptimalBarCount(): Int {
        val viewWidth = if (width > 0) width else 300
        val count = (viewWidth / (barWidth + barGap)).toInt()
        return count.coerceIn(24, 60)
    }

    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        invalidate()
    }

    fun clear() {
        bars.clear()
        rawAmplitudes.clear()
        progress = 0f
        isLiveRecording = false
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInteractive || width == 0 || isLiveRecording) return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val p = (event.x / width.toFloat()).coerceIn(0f, 1f)
                setProgress(p)
                onSeekListener?.invoke(p)
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
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

        val maxBars = (width / totalBarWidth).toInt().coerceAtLeast(1)
        val barsToDraw = if (bars.size > maxBars && isLiveRecording) {
            bars.takeLast(maxBars)
        } else {
            bars
        }

        val startX = 0f

        barsToDraw.forEachIndexed { index, amplitude ->
            val barHeight = max(amplitude * height * 0.85f, minBarHeight)
            val left = startX + index * totalBarWidth
            val top = centerY - barHeight / 2f
            val right = left + barWidth
            val bottom = centerY + barHeight / 2f

            if (isLiveRecording) {
                paint.color = liveRecordingColor
            } else {
                val barProgress = (index.toFloat() / barsToDraw.size)
                paint.color = if (barProgress <= progress) progressColor else barColor
            }

            canvas.drawRoundRect(left, top, right, bottom, barWidth / 2f, barWidth / 2f, paint)
        }
    }
}
