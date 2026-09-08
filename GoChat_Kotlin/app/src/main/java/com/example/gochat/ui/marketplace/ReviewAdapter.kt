package com.example.gochat.ui.marketplace

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Review
import com.example.gochat.databinding.ItemReviewBinding
import com.example.gochat.databinding.ItemReviewImageBinding

class ReviewAdapter : ListAdapter<Review, ReviewAdapter.ReviewViewHolder>(ReviewDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReviewViewHolder {
        val binding = ItemReviewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ReviewViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ReviewViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ReviewViewHolder(private val binding: ItemReviewBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(review: Review) {
            binding.tvReviewerName.text = review.userName
            binding.tvReviewComment.text = review.comment
            binding.tvReviewRating.text = "${review.rating}.0 ★"
            binding.tvReviewDate.text = review.createdAt ?: "Recently"
            binding.tvHelpfulCount.text = "Found helpful by ${review.helpfulCount} people"

            MediaImageHelper.loadSafeImage(binding.ivReviewerAvatar, review.userAvatar, isCircle = true)

            if (review.imageUrls.isNotEmpty()) {
                binding.rvReviewImages.visibility = View.VISIBLE
                val imageAdapter = ReviewImageAdapter()
                binding.rvReviewImages.adapter = imageAdapter
                imageAdapter.submitList(review.imageUrls)
            } else {
                binding.rvReviewImages.visibility = View.GONE
            }
        }
    }

    class ReviewDiffCallback : DiffUtil.ItemCallback<Review>() {
        override fun areItemsTheSame(oldItem: Review, newItem: Review): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Review, newItem: Review): Boolean = oldItem == newItem
    }
}

class ReviewImageAdapter : ListAdapter<String, ReviewImageAdapter.ImageViewHolder>(StringDiffCallback()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemReviewImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ImageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        MediaImageHelper.loadSafeImage(holder.binding.ivReviewImage, getItem(position), cornerRadiusDp = 8f)
    }

    inner class ImageViewHolder(val binding: ItemReviewImageBinding) : RecyclerView.ViewHolder(binding.root)

    class StringDiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
    }
}
