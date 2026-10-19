package com.example.gochat.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.data.model.QuickReply
import com.example.gochat.databinding.ItemQuickReplyBinding

class QuickRepliesAdapter(
    private val onItemClicked: (QuickReply) -> Unit
) : ListAdapter<QuickReply, QuickRepliesAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemQuickReplyBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemQuickReplyBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: QuickReply) {
            val shortcutDisplay = if (item.shortcut.startsWith("/")) item.shortcut else "/${item.shortcut}"
            binding.tvQuickReplyShortcut.text = shortcutDisplay
            binding.tvQuickReplyText.text = item.replyText

            binding.root.setOnClickListener {
                onItemClicked(item)
            }
            binding.ivInsertReply.setOnClickListener {
                onItemClicked(item)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<QuickReply>() {
        override fun areItemsTheSame(oldItem: QuickReply, newItem: QuickReply): Boolean {
            return oldItem.id == newItem.id || oldItem.shortcut == newItem.shortcut
        }

        override fun areContentsTheSame(oldItem: QuickReply, newItem: QuickReply): Boolean {
            return oldItem == newItem
        }
    }
}
