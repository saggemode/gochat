package com.example.gochat.ui.chat

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.model.Product
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.data.repository.StoryRepository
import com.example.gochat.databinding.BottomSheetShareToChatBinding
import com.example.gochat.databinding.ItemShareConversationBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ShareToChatBottomSheet(
    private val product: Product
) : BottomSheetDialogFragment() {

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var storyRepository: StoryRepository

    @Inject
    lateinit var tokenManager: TokenManager

    private var _binding: BottomSheetShareToChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ShareConversationAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetShareToChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        loadConversations()
    }

    private fun setupUI() {
        adapter = ShareConversationAdapter { conversation ->
            dismiss()
            shareProductToConversation(conversation)
        }

        binding.rvShareConversations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvShareConversations.adapter = adapter

        // Setup My Status pinned share action
        val myAvatar = tokenManager.userAvatarUrl.orEmpty()
        MediaImageHelper.loadSafeImage(
            imageView = binding.ivMyStatusShareAvatar,
            url = myAvatar,
            isCircle = true,
            placeholderRes = R.drawable.ic_account,
            errorRes = R.drawable.ic_account
        )

        binding.btnShareToStatus.setOnClickListener {
            shareProductToStatus()
        }

        binding.btnCloseSharePicker.setOnClickListener {
            dismiss()
        }
    }

    private fun shareProductToStatus() {
        binding.btnShareToStatus.isEnabled = false
        binding.progressBarShare.visibility = View.VISIBLE

        val priceFormatted = String.format(Locale.US, "%.2f", product.price)
        val shortDesc = product.description.lineSequence().firstOrNull()?.take(100) ?: ""
        val caption = "🏷️ ${product.displayTitle} • $$priceFormatted\n$shortDesc\n#prod_${product.id}".trim()
        val mediaUrl = product.primaryImage
        val mediaType = if (mediaUrl.isNotBlank()) "image" else "text"

        lifecycleScope.launch {
            val result = storyRepository.postStory(
                mediaUrl = mediaUrl,
                caption = caption,
                mediaType = mediaType
            )
            if (_binding != null) {
                binding.progressBarShare.visibility = View.GONE
                binding.btnShareToStatus.isEnabled = true
            }

            result.onSuccess {
                context?.let { ctx ->
                    android.widget.Toast.makeText(
                        ctx,
                        "✅ Product shared to your Status!",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                dismiss()
            }.onFailure { err ->
                context?.let { ctx ->
                    android.widget.Toast.makeText(
                        ctx,
                        "Failed to share to status: ${err.message ?: "Unknown error"}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun loadConversations() {
        lifecycleScope.launch {
            binding.progressBarShare.visibility = View.VISIBLE
            val localList = chatRepository.getAllConversationsList()
            binding.progressBarShare.visibility = View.GONE

            if (localList.isNotEmpty()) {
                adapter.submitList(localList)
                binding.tvEmptyConversations.visibility = View.GONE
            } else {
                val refreshResult = chatRepository.refreshConversations()
                val refreshed = chatRepository.getAllConversationsList()
                adapter.submitList(refreshed)
                binding.tvEmptyConversations.visibility = if (refreshed.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun shareProductToConversation(conversation: Conversation) {
        val intent = Intent(requireContext(), ChatRoomActivity::class.java).apply {
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_ID, conversation.id)
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, conversation.title)
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_AVATAR, conversation.avatarUrl)
            putExtra(ChatRoomActivity.EXTRA_PRODUCT_ID, product.id)
            putExtra(ChatRoomActivity.EXTRA_PRODUCT_NAME, product.displayTitle)
            putExtra(ChatRoomActivity.EXTRA_PRODUCT_PRICE, product.price)
            putExtra(ChatRoomActivity.EXTRA_PRODUCT_IMAGE, product.primaryImage)
            putExtra(ChatRoomActivity.EXTRA_INITIAL_MESSAGE, "Check out \"${product.displayTitle}\" on GoChat Marketplace!")
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ShareConversationAdapter(
        private val onConversationSelected: (Conversation) -> Unit
    ) : ListAdapter<Conversation, ShareConversationAdapter.ViewHolder>(DiffCallback) {

        inner class ViewHolder(private val b: ItemShareConversationBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(item: Conversation) {
                b.tvConvTitle.text = item.title
                b.tvConvSubtitle.text = item.lastMessageText?.ifBlank { "Tap to share this product" }
                    ?: "Tap to share this product"

                MediaImageHelper.loadSafeImage(
                    imageView = b.ivConvAvatar,
                    url = item.avatarUrl,
                    isCircle = true
                )

                b.root.setOnClickListener {
                    onConversationSelected(item)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemShareConversationBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        companion object {
            val DiffCallback = object : DiffUtil.ItemCallback<Conversation>() {
                override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation): Boolean =
                    oldItem.id == newItem.id

                override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation): Boolean =
                    oldItem == newItem
            }
        }
    }
}
