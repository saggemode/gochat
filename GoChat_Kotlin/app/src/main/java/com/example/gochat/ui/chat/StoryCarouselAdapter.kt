package com.example.gochat.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.example.gochat.R
import com.example.gochat.data.model.UserStories
import com.example.gochat.databinding.ItemStoryCarouselBinding

class StoryCarouselAdapter(
    private var storiesList: List<UserStories>,
    private val onStoryClicked: (UserStories) -> Unit
) : RecyclerView.Adapter<StoryCarouselAdapter.StoryViewHolder>() {

    class StoryViewHolder(val binding: ItemStoryCarouselBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoryViewHolder {
        val binding = ItemStoryCarouselBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return StoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StoryViewHolder, position: Int) {
        val userStory = storiesList[position]
        with(holder.binding) {
            // Name: "My status" or first name
            tvStoryName.text = if (userStory.isMe) {
                "My status"
            } else {
                userStory.userName.split(" ").firstOrNull() ?: userStory.userName
            }

            // Avatar image loading with MediaImageHelper
            com.example.gochat.core.media.MediaImageHelper.loadSafeImage(
                ivStoryAvatar,
                userStory.userAvatar,
                isCircle = true,
                placeholderRes = R.drawable.ic_account,
                errorRes = R.drawable.ic_account
            )

            // Green ring if user has active stories
            val hasStories = userStory.stories.isNotEmpty()
            layoutAvatarRing.setBackgroundResource(
                if (hasStories) R.drawable.bg_story_ring else 0
            )

            // Add status badge for 'My Status' when empty
            ivAddStatusBadge.visibility = if (userStory.isMe && !hasStories) {
                View.VISIBLE
            } else {
                View.GONE
            }

            root.setOnClickListener {
                onStoryClicked(userStory)
            }
        }
    }

    override fun getItemCount(): Int = storiesList.size

    fun submitList(newList: List<UserStories>) {
        storiesList = newList
        notifyDataSetChanged()
    }
}
