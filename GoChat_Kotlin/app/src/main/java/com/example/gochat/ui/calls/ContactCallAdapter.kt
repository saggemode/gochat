package com.example.gochat.ui.calls

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.example.gochat.R
import com.example.gochat.data.model.Conversation
import com.example.gochat.databinding.ItemContactCallBinding

class ContactCallAdapter(
    private val onVoiceCall: (Conversation) -> Unit,
    private val onVideoCall: (Conversation) -> Unit
) : ListAdapter<Conversation, ContactCallAdapter.ContactViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding = ItemContactCallBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ContactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ContactViewHolder(
        private val binding: ItemContactCallBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(conv: Conversation) {
            with(binding) {
                tvContactName.text = conv.title.ifBlank { "GoChat Contact" }
                tvContactStatus.text = if (conv.partnerPin != null && conv.partnerPin.isNotBlank()) {
                    "PIN: ${conv.partnerPin}"
                } else if (conv.isGroup) {
                    "Group (${conv.memberIds.size} members)"
                } else {
                    "Tap to call"
                }

                if (conv.avatarUrl.isNotBlank()) {
                    ivContactAvatar.load(conv.avatarUrl) {
                        crossfade(true)
                        placeholder(R.drawable.ic_account)
                        error(R.drawable.ic_account)
                        transformations(CircleCropTransformation())
                    }
                } else {
                    ivContactAvatar.setImageResource(R.drawable.ic_account)
                }

                btnVoiceCall.setOnClickListener {
                    onVoiceCall(conv)
                }

                btnVideoCall.setOnClickListener {
                    onVideoCall(conv)
                }
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<Conversation>() {
            override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation) =
                oldItem == newItem
        }
    }
}
