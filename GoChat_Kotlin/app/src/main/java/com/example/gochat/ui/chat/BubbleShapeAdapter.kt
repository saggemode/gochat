package com.example.gochat.ui.chat

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.core.wallpaper.BubbleShape
import com.example.gochat.core.wallpaper.ChatBubbleHelper
import com.example.gochat.databinding.ItemBubbleShapeBinding

class BubbleShapeAdapter(
    private val shapes: List<BubbleShape> = BubbleShape.entries,
    private var selectedShape: BubbleShape,
    private var accentColor: Int,
    private val onShapeClick: (BubbleShape) -> Unit
) : RecyclerView.Adapter<BubbleShapeAdapter.BubbleShapeViewHolder>() {

    fun setSelectedShape(shape: BubbleShape) {
        val oldPos = shapes.indexOf(selectedShape)
        selectedShape = shape
        val newPos = shapes.indexOf(selectedShape)
        if (oldPos != -1) notifyItemChanged(oldPos)
        if (newPos != -1) notifyItemChanged(newPos)
    }

    fun setAccentColor(color: Int) {
        if (accentColor != color) {
            accentColor = color
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BubbleShapeViewHolder {
        val binding = ItemBubbleShapeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BubbleShapeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BubbleShapeViewHolder, position: Int) {
        holder.bind(shapes[position])
    }

    override fun getItemCount(): Int = shapes.size

    inner class BubbleShapeViewHolder(private val binding: ItemBubbleShapeBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(shape: BubbleShape) {
            binding.tvBubbleName.text = shape.displayName

            // Mini bubble graphic
            binding.viewMiniBubble.background = ChatBubbleHelper.getMiniPreviewDrawable(
                binding.root.context,
                shape,
                accentColor
            )

            val isSelected = shape == selectedShape
            binding.cardBubbleShape.strokeWidth = if (isSelected) 3 else 0
            binding.cardBubbleShape.strokeColor = accentColor
            binding.ivBubbleSelected.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.ivBubbleSelected.backgroundTintList = ColorStateList.valueOf(accentColor)

            binding.cardBubbleShape.setOnClickListener {
                onShapeClick(shape)
            }
        }
    }
}
