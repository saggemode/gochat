package com.example.gochat.ui.stories

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.StoryViewer
import com.example.gochat.databinding.ItemStoryViewerBinding

class StoryViewersAdapter(
    private var viewersList: List<StoryViewer>
) : RecyclerView.Adapter<StoryViewersAdapter.ViewerViewHolder>() {

    class ViewerViewHolder(val binding: ItemStoryViewerBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewerViewHolder {
        val binding = ItemStoryViewerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewerViewHolder, position: Int) {
        val viewer = viewersList[position]
        with(holder.binding) {
            tvViewerName.text = viewer.displayName.ifBlank { "Contact" }
            tvViewedTime.text = viewer.viewedAt.ifBlank { "Just now" }

            MediaImageHelper.loadSafeImage(
                imageView = ivViewerAvatar,
                url = viewer.avatarUrl,
                isCircle = true,
                placeholderRes = R.drawable.ic_account,
                errorRes = R.drawable.ic_account
            )
        }
    }

    override fun getItemCount(): Int = viewersList.size

    fun submitList(newList: List<StoryViewer>) {
        viewersList = newList
        notifyDataSetChanged()
    }
}
