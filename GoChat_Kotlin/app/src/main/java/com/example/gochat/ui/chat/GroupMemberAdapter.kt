package com.example.gochat.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.GroupMember
import com.example.gochat.databinding.ItemGroupMemberBinding

class GroupMemberAdapter(
    private val onItemClicked: (GroupMember) -> Unit,
    private val onItemLongClicked: ((GroupMember) -> Unit)? = null
) : ListAdapter<GroupMember, GroupMemberAdapter.MemberViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
        val binding = ItemGroupMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MemberViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MemberViewHolder(private val binding: ItemGroupMemberBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(member: GroupMember) {
            with(binding) {
                tvName.text = member.displayName.ifBlank { "User ${member.id.takeLast(4)}" }
                tvStatus.text = if (member.isOnline) "Online" else "Offline"
                
                MediaImageHelper.loadSafeImage(
                    imageView = ivAvatar,
                    url = member.avatarUrl,
                    isCircle = true,
                    placeholderRes = R.drawable.ic_account,
                    errorRes = R.drawable.ic_account
                )

                // Show role badge
                if (member.role == "admin" || member.role == "owner") {
                    tvRole.visibility = View.VISIBLE
                    tvRole.text = member.role.replaceFirstChar { it.uppercase() }
                } else {
                    tvRole.visibility = View.GONE
                }

                root.setOnClickListener { onItemClicked(member) }
                root.setOnLongClickListener {
                    onItemLongClicked?.invoke(member)
                    true
                }
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<GroupMember>() {
        override fun areItemsTheSame(oldItem: GroupMember, newItem: GroupMember) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: GroupMember, newItem: GroupMember) = oldItem == newItem
    }
}
