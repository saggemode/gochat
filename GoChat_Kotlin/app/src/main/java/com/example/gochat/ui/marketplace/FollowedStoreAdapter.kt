package com.example.gochat.ui.marketplace

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Store
import com.example.gochat.databinding.ItemFollowedStoreBinding

class FollowedStoreAdapter(
    private val onClick: (Store) -> Unit,
    private val onToggleFollow: (Store) -> Unit
) : ListAdapter<FollowedStoreAdapter.FollowedStoreItem, FollowedStoreAdapter.ViewHolder>(DIFF) {

    data class FollowedStoreItem(
        val store: Store,
        val isFollowing: Boolean = true
    )

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FollowedStoreItem>() {
            override fun areItemsTheSame(old: FollowedStoreItem, new: FollowedStoreItem) = old.store.id == new.store.id
            override fun areContentsTheSame(old: FollowedStoreItem, new: FollowedStoreItem) = old == new
        }
    }

    inner class ViewHolder(private val binding: ItemFollowedStoreBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FollowedStoreItem) {
            val store = item.store

            binding.tvStoreName.text = store.name
            binding.tvStoreCategory.text = buildString {
                append(store.category.ifBlank { "General Retail" })
                if (store.address.isNotBlank()) {
                    append(" • ")
                    append(store.address)
                }
            }
            binding.tvStoreDescription.text = store.description.ifBlank {
                "Explore products from ${store.name}"
            }

            // Verified badge
            binding.ivVerified.visibility = if (store.isVerified) View.VISIBLE else View.GONE

            // Store logo
            if (!store.logoUrl.isNullOrBlank()) {
                MediaImageHelper.loadSafeImage(binding.ivStoreLogo, store.logoUrl, isCircle = true)
            } else {
                binding.ivStoreLogo.setImageResource(R.drawable.ic_storefront)
            }

            // Follow button state
            updateFollowButton(item.isFollowing)

            // Click listeners
            binding.cardStore.setOnClickListener { onClick(store) }
            binding.btnFollowToggle.setOnClickListener { onToggleFollow(store) }
        }

        private fun updateFollowButton(isFollowing: Boolean) {
            val ctx = binding.root.context
            if (isFollowing) {
                binding.btnFollowToggle.text = "Following"
                binding.btnFollowToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#374151"))
                binding.btnFollowToggle.setTextColor(Color.WHITE)
            } else {
                binding.btnFollowToggle.text = "Follow"
                binding.btnFollowToggle.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.gochat_accent)
                )
                binding.btnFollowToggle.setTextColor(Color.BLACK)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFollowedStoreBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
