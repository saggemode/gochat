package com.example.gochat.ui.contacts

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.SyncedContact
import com.example.gochat.databinding.ItemContactActionBinding
import com.example.gochat.databinding.ItemContactHeaderBinding
import com.example.gochat.databinding.ItemContactSelectBinding

sealed class ContactListItem {
    data class Action(val id: String, val title: String, val iconRes: Int) : ContactListItem()
    data class Header(val title: String, val count: Int) : ContactListItem()
    data class Contact(val contact: SyncedContact, val isSelected: Boolean = false) : ContactListItem()
}

class SelectContactAdapter(
    private val onActionClicked: (String) -> Unit,
    private val onContactClicked: (SyncedContact) -> Unit,
    private val onInviteClicked: (SyncedContact) -> Unit
) : ListAdapter<ContactListItem, RecyclerView.ViewHolder>(ContactDiffCallback) {

    var isMultiSelectMode = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    companion object {
        private const val TYPE_ACTION = 0
        private const val TYPE_HEADER = 1
        private const val TYPE_CONTACT = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ContactListItem.Action -> TYPE_ACTION
            is ContactListItem.Header -> TYPE_HEADER
            is ContactListItem.Contact -> TYPE_CONTACT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ACTION -> {
                val binding = ItemContactActionBinding.inflate(inflater, parent, false)
                ActionViewHolder(binding)
            }
            TYPE_HEADER -> {
                val binding = ItemContactHeaderBinding.inflate(inflater, parent, false)
                HeaderViewHolder(binding)
            }
            else -> {
                val binding = ItemContactSelectBinding.inflate(inflater, parent, false)
                ContactViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ContactListItem.Action -> (holder as ActionViewHolder).bind(item)
            is ContactListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is ContactListItem.Contact -> (holder as ContactViewHolder).bind(item)
        }
    }

    inner class ActionViewHolder(private val binding: ItemContactActionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(action: ContactListItem.Action) {
            binding.tvActionTitle.text = action.title
            binding.ivActionIcon.setImageResource(action.iconRes)
            binding.root.setOnClickListener { onActionClicked(action.id) }
        }
    }

    inner class HeaderViewHolder(private val binding: ItemContactHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(header: ContactListItem.Header) {
            binding.tvHeaderTitle.text = header.title
            binding.tvHeaderCount.text = header.count.toString()
        }
    }

    inner class ContactViewHolder(private val binding: ItemContactSelectBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ContactListItem.Contact) {
            val contact = item.contact
            with(binding) {
                tvName.text = contact.displayName

                if (contact.isRegistered) {
                    // Registered user on GoChat
                    val statusBio = contact.statusText.ifBlank { "Hey there! I am using GoChat." }
                    tvSubtitle.text = statusBio
                    tvSubtitle.visibility = View.VISIBLE

                    viewOnlineDot.visibility = if (contact.isOnline) View.VISIBLE else View.GONE
                    btnInvite.visibility = View.GONE

                    MediaImageHelper.loadSafeImage(
                        imageView = ivAvatar,
                        url = contact.avatarUrl,
                        isCircle = true,
                        placeholderRes = R.drawable.ic_account,
                        errorRes = R.drawable.ic_account
                    )

                    cbSelect.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
                    cbSelect.isChecked = item.isSelected

                    root.setOnClickListener { onContactClicked(contact) }
                } else {
                    // Unregistered contact to invite
                    tvSubtitle.text = contact.phone
                    tvSubtitle.visibility = if (contact.phone.isNotBlank()) View.VISIBLE else View.GONE

                    viewOnlineDot.visibility = View.GONE
                    btnInvite.visibility = View.VISIBLE
                    cbSelect.visibility = View.GONE
                    ivAvatar.setImageResource(R.drawable.ic_account)

                    btnInvite.setOnClickListener { onInviteClicked(contact) }
                    root.setOnClickListener { onInviteClicked(contact) }
                }
            }
        }
    }

    object ContactDiffCallback : DiffUtil.ItemCallback<ContactListItem>() {
        override fun areItemsTheSame(oldItem: ContactListItem, newItem: ContactListItem): Boolean {
            return when {
                oldItem is ContactListItem.Action && newItem is ContactListItem.Action -> oldItem.id == newItem.id
                oldItem is ContactListItem.Header && newItem is ContactListItem.Header -> oldItem.title == newItem.title
                oldItem is ContactListItem.Contact && newItem is ContactListItem.Contact -> {
                    val oldC = oldItem.contact
                    val newC = newItem.contact
                    if (oldC.isRegistered && newC.isRegistered) oldC.finalUserId == newC.finalUserId
                    else oldC.phone == newC.phone
                }
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: ContactListItem, newItem: ContactListItem): Boolean {
            return oldItem == newItem
        }
    }
}
