package com.example.gochat.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R

class SwipeToReplyCallback(
    context: Context,
    private val onSwipe: (Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

    private val replyIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_reply)?.apply {
        setTint(ContextCompat.getColor(context, R.color.gochat_emerald_light))
    }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.gochat_emerald_dark)
        style = Paint.Style.FILL
    }

    private val density = context.resources.displayMetrics.density
    private val triggerThresholdPx = 72f * density
    private val maxSwipePx = 100f * density
    private val iconSize = (24f * density).toInt()
    private val circleRadius = 18f * density
    private val iconPadding = 24f * density

    private var hasTriggered = false

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // Prevent dismissal: ItemTouchHelper should never remove message items
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = Float.MAX_VALUE
    override fun getSwipeEscapeVelocity(defaultValue: Float): Float = Float.MAX_VALUE

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            val itemView = viewHolder.itemView

            // Reset trigger state when user releases touch
            if (!isCurrentlyActive && dX == 0f) {
                hasTriggered = false
            }

            // Apply elastic rubber-band curve past threshold
            val scrollX = if (dX <= triggerThresholdPx) {
                dX
            } else {
                triggerThresholdPx + (dX - triggerThresholdPx) * 0.35f
            }.coerceAtMost(maxSwipePx)

            val progress = (scrollX / triggerThresholdPx).coerceIn(0f, 1f)

            // Draw circle background & reply icon behind the sliding message bubble
            if (scrollX > 8f) {
                val centerY = itemView.top + itemView.height / 2f
                val centerX = itemView.left + iconPadding + circleRadius

                // Draw background circle scaling up with progress
                val currentRadius = circleRadius * (0.6f + 0.4f * progress)
                circlePaint.alpha = (progress * 80).toInt()
                c.drawCircle(centerX, centerY, currentRadius, circlePaint)

                // Draw reply icon
                replyIcon?.let {
                    val halfIcon = (iconSize * (0.7f + 0.3f * progress) / 2f).toInt()
                    it.alpha = (progress * 255).toInt()
                    it.setBounds(
                        (centerX - halfIcon).toInt(),
                        (centerY - halfIcon).toInt(),
                        (centerX + halfIcon).toInt(),
                        (centerY + halfIcon).toInt()
                    )
                    it.draw(c)
                }
            }

            // Trigger reply once when threshold is reached
            if (dX >= triggerThresholdPx && isCurrentlyActive && !hasTriggered) {
                hasTriggered = true
                itemView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val pos = viewHolder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onSwipe(pos)
                }
            }

            super.onChildDraw(c, recyclerView, viewHolder, scrollX, dY, actionState, isCurrentlyActive)
        } else {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }
}

