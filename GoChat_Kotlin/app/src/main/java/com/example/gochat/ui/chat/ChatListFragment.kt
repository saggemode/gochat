package com.example.gochat.ui.chat

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.paging.LoadState
import com.example.gochat.R
import com.example.gochat.data.model.Conversation
import com.example.gochat.databinding.DialogNewChatByPinBinding
import com.example.gochat.databinding.FragmentChatListBinding
import com.example.gochat.ui.stories.StoryViewerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChatListFragment : Fragment() {

    private var _binding: FragmentChatListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChatListViewModel by activityViewModels()

    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var storyCarouselAdapter: StoryCarouselAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerViews()
        setupSearch()
        setupFilters()
        setupActions()
        observeState()
    }

    private fun setupRecyclerViews() {
        // Horizontal Stories Carousel
        storyCarouselAdapter = StoryCarouselAdapter(emptyList()) { userStories ->
            if (userStories.stories.isNotEmpty()) {
                val intent = StoryViewerActivity.createIntent(requireContext(), userStories)
                startActivity(intent)
            } else {
                Toast.makeText(requireContext(), getString(R.string.no_recent_updates_title), Toast.LENGTH_SHORT).show()
            }
        }
        binding.rvStoriesCarousel.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvStoriesCarousel.adapter = storyCarouselAdapter

        // Conversations List
        conversationAdapter = ConversationAdapter(
            onConversationClicked = { conversation ->
                viewModel.markAsRead(conversation.id)
                openChatRoom(conversation)
            },
            onConversationLongClicked = { conversation ->
                showConversationOptionsDialog(conversation)
            }
        )
        binding.rvConversations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvConversations.adapter = conversationAdapter

        // Pull to refresh
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.gochat_accent)
        )
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshData()
        }
    }

    private fun setupSearch() {
        with(binding) {
            btnOpenSearch.setOnClickListener {
                layoutToolbarNormal.visibility = View.GONE
                layoutToolbarSearch.visibility = View.VISIBLE
                etSearchQuery.requestFocus()
                showKeyboard(etSearchQuery)
            }

            btnCloseSearch.setOnClickListener {
                hideKeyboard(etSearchQuery)
                etSearchQuery.setText("")
                viewModel.setSearchQuery("")
                layoutToolbarSearch.visibility = View.GONE
                layoutToolbarNormal.visibility = View.VISIBLE
            }

            btnClearSearch.setOnClickListener {
                etSearchQuery.setText("")
                viewModel.setSearchQuery("")
            }

            etSearchQuery.doAfterTextChanged { text ->
                val query = text?.toString().orEmpty()
                btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                viewModel.setSearchQuery(query)
            }
        }
    }

    private fun setupFilters() {
        val filterViews = mapOf(
            "All" to binding.chipFilterAll,
            "Unread" to binding.chipFilterUnread,
            "Groups" to binding.chipFilterGroups,
            "Channels" to binding.chipFilterChannels
        )

        filterViews.forEach { (filterKey, textView) ->
            textView.setOnClickListener {
                viewModel.setFilter(filterKey)
            }
        }
    }

    private fun updateFilterUi(activeFilter: String) {
        val filterViews = mapOf(
            "All" to binding.chipFilterAll,
            "Unread" to binding.chipFilterUnread,
            "Groups" to binding.chipFilterGroups,
            "Channels" to binding.chipFilterChannels
        )

        filterViews.forEach { (filterKey, textView) ->
            val isSelected = filterKey == activeFilter
            textView.setBackgroundResource(
                if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
            )
            textView.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isSelected) R.color.gochat_accent else R.color.gochat_text_muted
                )
            )
        }
    }

    private fun setupActions() {
        binding.btnChatByPin.setOnClickListener {
            showChatByPinDialog()
        }

        binding.fabNewChat.setOnClickListener {
            com.example.gochat.ui.contacts.SelectContactActivity.start(requireContext())
        }

        binding.btnEmptyAction.setOnClickListener {
            if (viewModel.searchQuery.value.isNotEmpty()) {
                binding.etSearchQuery.setText("")
                viewModel.setSearchQuery("")
            } else {
                com.example.gochat.ui.contacts.SelectContactActivity.start(requireContext())
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.pagedConversations.collectLatest { pagingData ->
                        conversationAdapter.submitData(pagingData)
                    }
                }

                launch {
                    conversationAdapter.loadStateFlow.collectLatest { loadStates ->
                        val isListEmpty = loadStates.refresh is LoadState.NotLoading && conversationAdapter.itemCount == 0
                        if (isListEmpty) {
                            binding.layoutEmptyState.visibility = View.VISIBLE
                            binding.rvConversations.visibility = View.GONE
                            val query = viewModel.searchQuery.value
                            if (query.isNotEmpty()) {
                                binding.tvEmptyTitle.text = getString(R.string.no_results_found)
                                binding.tvEmptyDescription.text = getString(R.string.no_chats_match_desc, query)
                                binding.btnEmptyAction.text = getString(R.string.btn_clear_search)
                            } else {
                                binding.tvEmptyTitle.text = getString(R.string.no_conversations_title)
                                binding.tvEmptyDescription.text = getString(R.string.no_conversations_desc)
                                binding.btnEmptyAction.text = getString(R.string.btn_chat_by_pin)
                            }
                        } else {
                            binding.layoutEmptyState.visibility = View.GONE
                            binding.rvConversations.visibility = View.VISIBLE
                        }
                    }
                }

                launch {
                    viewModel.stories.collect { storiesList ->
                        storyCarouselAdapter.submitList(storiesList)
                        binding.rvStoriesCarousel.visibility =
                            if (storiesList.isNotEmpty() && viewModel.searchQuery.value.isEmpty()) {
                                View.VISIBLE
                            } else {
                                View.GONE
                            }
                    }
                }

                launch {
                    viewModel.selectedFilter.collect { filter ->
                        updateFilterUi(filter)
                    }
                }

                launch {
                    viewModel.pendingInvitationsCount.collect { count ->
                        if (count > 0) {
                            binding.layoutPendingBanner.visibility = View.VISIBLE
                            binding.tvPendingBannerText.text = if (count == 1) {
                                getString(R.string.pending_invitation_singular)
                            } else {
                                getString(R.string.pending_invitation_plural, count)
                            }
                        } else {
                            binding.layoutPendingBanner.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.isRefreshing.collect { refreshing ->
                        binding.swipeRefresh.isRefreshing = refreshing
                    }
                }

                launch {
                    viewModel.connectionState.collect { state ->
                        when (state) {
                            ConnectionState.WAITING_FOR_NETWORK -> {
                                binding.layoutNetworkStatusBanner.visibility = View.VISIBLE
                                binding.pbNetworkStatus.visibility = View.GONE
                                binding.ivNetworkStatusIcon.visibility = View.VISIBLE
                                binding.tvNetworkStatusText.text = getString(R.string.waiting_for_network)
                            }
                            ConnectionState.CONNECTING -> {
                                binding.layoutNetworkStatusBanner.visibility = View.VISIBLE
                                binding.pbNetworkStatus.visibility = View.VISIBLE
                                binding.ivNetworkStatusIcon.visibility = View.GONE
                                binding.tvNetworkStatusText.text = getString(R.string.connecting)
                            }
                            ConnectionState.CONNECTED -> {
                                binding.layoutNetworkStatusBanner.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showChatByPinDialog() {
        val dialogBinding = DialogNewChatByPinBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.btnCancelPin.setOnClickListener { dialog.dismiss() }

        dialogBinding.btnConnectPin.setOnClickListener {
            val pin = dialogBinding.etPinInput.text?.toString()?.trim()?.uppercase().orEmpty()
            if (pin.length < 4) {
                dialogBinding.tvPinError.visibility = View.VISIBLE
                dialogBinding.tvPinError.text = getString(R.string.error_invalid_pin)
                return@setOnClickListener
            }

            dialogBinding.btnConnectPin.isEnabled = false
            dialogBinding.tvPinError.visibility = View.GONE

            viewModel.startChatByPin(pin) { result ->
                result.fold(
                    onSuccess = { conversation ->
                        dialog.dismiss()
                        openChatRoom(conversation)
                    },
                    onFailure = { error ->
                        dialogBinding.btnConnectPin.isEnabled = true
                        dialogBinding.tvPinError.visibility = View.VISIBLE
                        dialogBinding.tvPinError.text = error.message ?: getString(R.string.error_user_not_found)
                    }
                )
            }
        }

        dialog.show()
    }

    private fun showConversationOptionsDialog(conversation: Conversation) {
        val options = arrayOf(
            getString(R.string.option_mark_as_read),
            getString(R.string.option_pin_conversation),
            getString(R.string.option_mute_notifications),
            getString(R.string.option_delete_conversation)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(conversation.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.markAsRead(conversation.id)
                    1 -> Toast.makeText(requireContext(), getString(R.string.toast_conversation_pinned), Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(requireContext(), getString(R.string.toast_notifications_muted), Toast.LENGTH_SHORT).show()
                    3 -> Toast.makeText(requireContext(), getString(R.string.toast_conversation_deleted), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun openChatRoom(conversation: Conversation) {
        val intent = Intent(requireContext(), ChatRoomActivity::class.java).apply {
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_ID, conversation.id)
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, conversation.title)
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_AVATAR, conversation.avatarUrl)
            putExtra(ChatRoomActivity.EXTRA_IS_ONLINE, conversation.isOnline)
            putExtra(ChatRoomActivity.EXTRA_LAST_SEEN, conversation.lastSeen ?: 0L)
            putExtra(ChatRoomActivity.EXTRA_IS_GROUP, conversation.isGroup)
        }
        startActivity(intent)
    }

    private fun showKeyboard(view: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
