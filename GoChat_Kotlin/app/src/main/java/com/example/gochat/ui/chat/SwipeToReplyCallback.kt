package com.example.gochat.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R
import kotlin.math.min

class SwipeToReplyCallback(
    context: Context,
    private val onSwipe: (Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

    private val replyIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_reply)
    private val iconPadding = 32
    
    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // We don't actually want to remove the item, so we notify adapter to restore it
        // and trigger the reply action
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.5f

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
            val scrollX = min(dX, 150f) // Limit swipe distance
            
            // Draw reply icon
            replyIcon?.let {
                val iconSize = it.intrinsicWidth
                val top = itemView.top + (itemView.height - iconSize) / 2
                val bottom = top + iconSize
                
                // Animate alpha based on swipe distance
                it.alpha = (min(scrollX / 100f, 1f) * 255).toInt()
                
                val left = itemView.left + iconPadding
                val right = left + iconSize
                it.setBounds(left, top, right, bottom)
                it.draw(c)
            }
            
            // Trigger reply if swiped far enough
            if (dX >= 150f && isCurrentlyActive) {
                onSwipe(viewHolder.adapterPosition)
            }

            super.onChildDraw(c, recyclerView, viewHolder, scrollX, dY, actionState, isCurrentlyActive)
        }
    }
}
