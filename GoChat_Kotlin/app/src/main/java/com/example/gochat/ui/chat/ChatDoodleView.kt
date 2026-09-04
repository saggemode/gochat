package com.example.gochat.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.gochat.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Custom View that renders the classic subtle WhatsApp doodle pattern
 * over a dark gradient background.
 */
class ChatDoodleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var doodleColor: Int = ContextCompat.getColor(context, R.color.whatsapp_doodle_tint)
    private var doodleOpacity: Float = 0.05f

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val tempRect = RectF()
    private val path = Path()

    init {
        updatePaintAlphas()
    }

    private fun updatePaintAlphas() {
        val strokeAlpha = (255 * doodleOpacity).toInt().coerceIn(0, 255)
        val fillAlpha = (255 * (doodleOpacity * 0.7f)).toInt().coerceIn(0, 255)

        strokePaint.color = doodleColor
        strokePaint.alpha = strokeAlpha

        fillPaint.color = doodleColor
        fillPaint.alpha = fillAlpha
    }

    fun setDoodleOpacity(opacity: Float) {
        this.doodleOpacity = opacity.coerceIn(0f, 1f)
        updatePaintAlphas()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val density = resources.displayMetrics.density
        val spacing = 72f * density
        val cols = (width / spacing).toInt() + 2
        val rows = (height / spacing).toInt() + 2

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = c * spacing + (if (r % 2 == 0) 0f else spacing * 0.5f)
                val y = r * spacing
                val iconIndex = (r * 7 + c * 13) % 8

                canvas.save()
                canvas.translate(x, y)

                val angle = (sin((r + c).toDouble()) * 14.0).toFloat()
                canvas.rotate(angle)

                drawDoodleIcon(canvas, iconIndex, density)

                canvas.restore()
            }
        }
    }

    private fun drawDoodleIcon(canvas: Canvas, iconIndex: Int, density: Float) {
        val s = density * 1.1f

        when (iconIndex) {
            0 -> {
                // Chat bubble
                tempRect.set(-10f * s, -8f * s, 10f * s, 8f * s)
                canvas.drawRoundRect(tempRect, 5f * s, 5f * s, strokePaint)
                path.reset()
                path.moveTo(-4f * s, 8f * s)
                path.lineTo(-8f * s, 12f * s)
                path.lineTo(0f, 8f * s)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
            1 -> {
                // Star
                path.reset()
                for (i in 0 until 5) {
                    val a1 = (i * 4 * Math.PI) / 5 - Math.PI / 2
                    val px = (cos(a1) * 8.0 * s).toFloat()
                    val py = (sin(a1) * 8.0 * s).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                canvas.drawPath(path, strokePaint)
            }
            2 -> {
                // Heart
                path.reset()
                path.moveTo(0f, 3f * s)
                path.cubicTo(-6f * s, -4f * s, -10f * s, 2f * s, 0f, 9f * s)
                path.cubicTo(10f * s, 2f * s, 6f * s, -4f * s, 0f, 3f * s)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
            3 -> {
                // Music note
                canvas.drawCircle(-4f * s, 4f * s, 3f * s, fillPaint)
                canvas.drawCircle(4f * s, 2f * s, 3f * s, fillPaint)
                canvas.drawLine(-1f * s, 4f * s, -1f * s, -6f * s, strokePaint)
                canvas.drawLine(7f * s, 2f * s, 7f * s, -8f * s, strokePaint)
                canvas.drawLine(-1f * s, -6f * s, 7f * s, -8f * s, strokePaint)
            }
            4 -> {
                // Smile face
                canvas.drawCircle(0f, 0f, 8f * s, strokePaint)
                canvas.drawCircle(-3f * s, -2f * s, 1.2f * s, fillPaint)
                canvas.drawCircle(3f * s, -2f * s, 1.2f * s, fillPaint)
                path.reset()
                path.moveTo(-4f * s, 2f * s)
                path.quadTo(0f, 6f * s, 4f * s, 2f * s)
                canvas.drawPath(path, strokePaint)
            }
            5 -> {
                // Sparkle / Diamond
                path.reset()
                path.moveTo(0f, -8f * s)
                path.quadTo(0f, 0f, 8f * s, 0f)
                path.quadTo(0f, 0f, 0f, 8f * s)
                path.quadTo(0f, 0f, -8f * s, 0f)
                path.quadTo(0f, 0f, 0f, -8f * s)
                path.close()
                canvas.drawPath(path, strokePaint)
            }
            6 -> {
                // Location pin
                canvas.drawCircle(0f, -3f * s, 4f * s, strokePaint)
                path.reset()
                path.moveTo(-3f * s, -1f * s)
                path.lineTo(0f, 6f * s)
                path.lineTo(3f * s, -1f * s)
                canvas.drawPath(path, strokePaint)
            }
            7 -> {
                // Concentric circles
                canvas.drawCircle(0f, 0f, 3.5f * s, strokePaint)
                canvas.drawCircle(0f, 0f, 7.5f * s, strokePaint)
            }
        }
    }
}
