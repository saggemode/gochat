package com.example.gochat.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.model.Message
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.databinding.BottomSheetForwardMessageBinding
import com.example.gochat.databinding.ItemForwardConversationBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class ForwardMessageBottomSheet(
    private val messageToForward: Message
) : BottomSheetDialogFragment() {

    @Inject lateinit var chatRepository: ChatRepository

    private var _binding: BottomSheetForwardMessageBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ForwardConversationAdapter
    private var allConversations: List<Conversation> = emptyList()
    private val selectedConversations = mutableSetOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetForwardMessageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        loadConversations()
    }

    private fun setupUI() {
        adapter = ForwardConversationAdapter(
            isSelected = { convId -> selectedConversations.contains(convId) },
            onToggleSelection = { conversation ->
                if (selectedConversations.contains(conversation.id)) {
                    selectedConversations.remove(conversation.id)
                } else {
                    selectedConversations.add(conversation.id)
                }
                updateSelectionState()
                adapter.notifyDataSetChanged()
            }
        )

        binding.rvForwardConversations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvForwardConversations.adapter = adapter

        binding.btnCloseForward.setOnClickListener {
            dismiss()
        }

        binding.etSearchConversations.doAfterTextChanged { text ->
            filterConversations(text?.toString().orEmpty())
        }

        binding.btnSendForward.setOnClickListener {
            performForwarding()
        }

        updateSelectionState()
    }

    private fun updateSelectionState() {
        val count = selectedConversations.size
        if (count > 0) {
            binding.tvForwardSelectedCount.text = "$count chat${if (count > 1) "s" else ""} selected"
            binding.btnSendForward.isEnabled = true
            binding.btnSendForward.text = "Forward ($count)"
        } else {
            binding.tvForwardSelectedCount.text = "Select one or more chats"
            binding.btnSendForward.isEnabled = false
            binding.btnSendForward.text = "Forward"
        }
    }

    private fun loadConversations() {
        lifecycleScope.launch {
            binding.pbLoadingConversations.visibility = View.VISIBLE
            val localList = chatRepository.getAllConversationsList()
            binding.pbLoadingConversations.visibility = View.GONE

            allConversations = localList
            if (allConversations.isNotEmpty()) {
                adapter.submitList(allConversations)
                binding.tvNoChats.visibility = View.GONE
            } else {
                binding.tvNoChats.visibility = View.VISIBLE
            }
        }
    }

    private fun filterConversations(query: String) {
        val trimmed = query.trim().lowercase()
        val filtered = if (trimmed.isBlank()) {
            allConversations
        } else {
            allConversations.filter {
                it.title.lowercase().contains(trimmed) ||
                        it.lastMessageText?.lowercase()?.contains(trimmed) == true
            }
        }
        adapter.submitList(filtered)
        binding.tvNoChats.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun performForwarding() {
        val targets = selectedConversations.toList()
        if (targets.isEmpty()) return

        binding.btnSendForward.isEnabled = false
        binding.btnSendForward.text = "Forwarding..."

        lifecycleScope.launch {
            var successCount = 0
            for (targetConvId in targets) {
                val result = withContext(Dispatchers.IO) {
                    chatRepository.forwardMessage(messageToForward, targetConvId)
                }
                if (result.isSuccess) {
                    successCount++
                }
            }

            val msg = if (successCount == 1) {
                "Message forwarded!"
            } else {
                "Message forwarded to $successCount chats!"
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ForwardConversationAdapter(
        private val isSelected: (String) -> Boolean,
        private val onToggleSelection: (Conversation) -> Unit
    ) : ListAdapter<Conversation, ForwardConversationAdapter.ViewHolder>(ConvDiffCallback) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemForwardConversationBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(private val binding: ItemForwardConversationBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(conversation: Conversation) {
                binding.tvConvTitle.text = conversation.title
                val subtitle = if (conversation.isGroup) "Group chat" else conversation.lastMessageText ?: "Direct chat"
                binding.tvConvSubtitle.text = subtitle

                MediaImageHelper.loadSafeImage(
                    imageView = binding.ivConvAvatar,
                    url = conversation.avatarUrl,
                    isCircle = true,
                    placeholderRes = if (conversation.isGroup) R.drawable.ic_tab_chats else R.drawable.ic_account,
                    errorRes = if (conversation.isGroup) R.drawable.ic_tab_chats else R.drawable.ic_account
                )

                binding.cbSelectConv.isChecked = isSelected(conversation.id)

                binding.root.setOnClickListener {
                    onToggleSelection(conversation)
                }
            }
        }

        object ConvDiffCallback : DiffUtil.ItemCallback<Conversation>() {
            override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation): Boolean =
                oldItem == newItem
        }
    }
}
