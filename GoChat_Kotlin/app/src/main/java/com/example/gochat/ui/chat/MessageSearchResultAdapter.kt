package com.example.gochat.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.data.model.Message
import com.example.gochat.databinding.ItemMessageSearchResultBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows global message-content search results (from the local `messages` table) in the chat-list
 * search. Tapping a result opens that conversation jumped to the message.
 */
class MessageSearchResultAdapter(
    private val onClick: (Message) -> Unit
) : ListAdapter<Message, MessageSearchResultAdapter.VH>(DIFF) {

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    inner class VH(val binding: ItemMessageSearchResultBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            binding.tvResultTitle.text = message.senderName.ifBlank { "Message" }
            binding.tvResultSnippet.text = message.content
            binding.tvResultTime.text = timeFormat.format(Date(message.createdAt))
            binding.root.setOnClickListener { onClick(message) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMessageSearchResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Message>() {
            override fun areItemsTheSame(oldItem: Message, newItem: Message) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Message, newItem: Message) = oldItem == newItem
        }
    }
}
