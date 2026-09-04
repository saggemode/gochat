package com.example.gochat.ui.stories

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.example.gochat.R
import com.example.gochat.data.model.UserStories
import com.example.gochat.databinding.ItemStoryUserBinding

class StoryAdapter(
    private val onStoryClicked: (UserStories) -> Unit
) : ListAdapter<UserStories, StoryAdapter.StoryViewHolder>(DiffCallback) {

    class StoryViewHolder(val binding: ItemStoryUserBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoryViewHolder {
        val binding = ItemStoryUserBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return StoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StoryViewHolder, position: Int) {
        val userStory = getItem(position)
        with(holder.binding) {
            tvStoryUserName.text = userStory.userName

            // Relative time formatting
            val firstStory = userStory.stories.firstOrNull()
            tvStoryTime.text = firstStory?.createdAt?.ifBlank { "Recently" } ?: "Recently"

            // Story ring indicator
            viewStoryRing.setBackgroundResource(
                if (userStory.stories.isNotEmpty()) R.drawable.bg_story_ring else 0
            )

            // Avatar image loading with Coil
            val avatarToLoad = if (userStory.stories.isNotEmpty() && userStory.stories.first().mediaUrl.isNotBlank()) {
                userStory.stories.first().mediaUrl
            } else {
                userStory.userAvatar
            }

            com.example.gochat.core.media.MediaImageHelper.loadSafeImage(
                ivStoryUserAvatar,
                avatarToLoad,
                isCircle = true,
                placeholderRes = R.drawable.ic_account,
                errorRes = R.drawable.ic_account
            )

            root.setOnClickListener {
                onStoryClicked(userStory)
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<UserStories>() {
        override fun areItemsTheSame(oldItem: UserStories, newItem: UserStories): Boolean {
            return oldItem.userId == newItem.userId
        }

        override fun areContentsTheSame(oldItem: UserStories, newItem: UserStories): Boolean {
            return oldItem == newItem
        }
    }
}
