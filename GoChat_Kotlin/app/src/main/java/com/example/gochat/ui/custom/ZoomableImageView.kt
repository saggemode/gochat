package com.example.gochat.ui.custom

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)
    private var mode = NONE

    private val lastTouch = PointF()
    private val startTouch = PointF()
    private var minScale = 1f
    private var maxScale = 5f

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }

    init {
        scaleType = ScaleType.MATRIX
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val currentPoint = PointF(event.x, event.y)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch.set(currentPoint)
                startTouch.set(lastTouch)
                mode = DRAG
                parent?.requestDisallowInterceptTouchEvent(getCurrentScale() > 1.05f)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastTouch.set(currentPoint)
                startTouch.set(lastTouch)
                mode = ZOOM
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG) {
                    val deltaX = currentPoint.x - lastTouch.x
                    val deltaY = currentPoint.y - lastTouch.y
                    if (getCurrentScale() > 1.05f) {
                        matrix.postTranslate(deltaX, deltaY)
                        fixTranslation()
                        imageMatrix = matrix
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    lastTouch.set(currentPoint.x, currentPoint.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
                if (getCurrentScale() <= 1.05f) {
                    resetZoom()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }

        imageMatrix = matrix
        invalidate()
        return true
    }

    private fun getCurrentScale(): Float {
        matrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun fixTranslation() {
        matrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val scale = matrixValues[Matrix.MSCALE_X]

        val drawable = drawable ?: return
        val origW = drawable.intrinsicWidth.toFloat()
        val origH = drawable.intrinsicHeight.toFloat()
        if (origW <= 0 || origH <= 0) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        val contentW = origW * scale
        val contentH = origH * scale

        val minTransX = if (contentW > viewW) viewW - contentW else (viewW - contentW) / 2f
        val maxTransX = if (contentW > viewW) 0f else (viewW - contentW) / 2f

        val minTransY = if (contentH > viewH) viewH - contentH else (viewH - contentH) / 2f
        val maxTransY = if (contentH > viewH) 0f else (viewH - contentH) / 2f

        val fixedX = transX.coerceIn(minTransX, maxTransX)
        val fixedY = transY.coerceIn(minTransY, maxTransY)

        matrixValues[Matrix.MTRANS_X] = fixedX
        matrixValues[Matrix.MTRANS_Y] = fixedY
        matrix.setValues(matrixValues)
    }

    fun resetZoom() {
        val drawable = drawable ?: return
        val dWidth = drawable.intrinsicWidth.toFloat()
        val dHeight = drawable.intrinsicHeight.toFloat()
        val vWidth = width.toFloat()
        val vHeight = height.toFloat()
        if (dWidth <= 0 || dHeight <= 0 || vWidth <= 0 || vHeight <= 0) return

        matrix.reset()
        val scale = minOf(vWidth / dWidth, vHeight / dHeight)
        val dx = (vWidth - dWidth * scale) / 2f
        val dy = (vHeight - dHeight * scale) / 2f

        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        imageMatrix = matrix
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { resetZoom() }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var scaleFactor = detector.scaleFactor
            val currentScale = getCurrentScale()
            val targetScale = currentScale * scaleFactor

            if (targetScale in minScale..maxScale) {
                matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                fixTranslation()
                imageMatrix = matrix
            }
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val currentScale = getCurrentScale()
            if (currentScale > 1.2f) {
                resetZoom()
            } else {
                matrix.postScale(2.5f, 2.5f, e.x, e.y)
                fixTranslation()
                imageMatrix = matrix
                invalidate()
            }
            return true
        }
    }
}
