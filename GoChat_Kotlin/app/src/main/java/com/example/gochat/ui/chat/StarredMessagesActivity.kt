package com.example.gochat.ui.chat

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.data.model.Message
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.databinding.ActivityStarredMessagesBinding
import com.example.gochat.core.ui.applyEdgeToEdge
import com.example.gochat.core.ui.applyStatusBarInset
import com.example.gochat.core.ui.applySystemBarInsets
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class StarredMessagesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_CONVERSATION_TITLE = "extra_conversation_title"
    }

    private lateinit var binding: ActivityStarredMessagesBinding
    private lateinit var adapter: StarredMessagesAdapter

    @Inject
    lateinit var chatRepo: ChatRepository

    private val conversationId: String by lazy {
        intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
    }

    private val conversationTitle: String by lazy {
        intent.getStringExtra(EXTRA_CONVERSATION_TITLE).orEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityStarredMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.applyStatusBarInset()
        binding.root.applySystemBarInsets(applyTop = false, applyBottom = true)

        setupToolbar()
        setupRecyclerView()
        observeStarredMessages()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        if (conversationTitle.isNotBlank()) {
            binding.toolbar.title = "Starred • $conversationTitle"
        } else {
            binding.toolbar.title = "Starred Messages"
        }
    }

    private fun setupRecyclerView() {
        adapter = StarredMessagesAdapter(
            onItemClick = { message ->
                openMessageInChat(message)
            },
            onUnstarClick = { message ->
                unstarMessage(message)
            }
        )

        binding.rvStarredMessages.layoutManager = LinearLayoutManager(this)
        binding.rvStarredMessages.adapter = adapter
    }

    private var conversationTitlesMap: Map<String, String> = emptyMap()

    private fun observeStarredMessages() {
        binding.pbLoading.visibility = View.VISIBLE

        if (conversationId.isBlank()) {
            lifecycleScope.launch {
                chatRepo.observeConversations().collectLatest { convs ->
                    conversationTitlesMap = convs.associate { it.id to it.title }
                    adapter.conversationTitles = conversationTitlesMap
                }
            }
        }

        lifecycleScope.launch {
            val flow = if (conversationId.isNotBlank()) {
                chatRepo.observeStarredMessagesForConversation(conversationId)
            } else {
                chatRepo.observeStarredMessages()
            }

            flow.collectLatest { list ->
                binding.pbLoading.visibility = View.GONE
                adapter.submitList(list)
                if (list.isEmpty()) {
                    binding.layoutEmptyState.visibility = View.VISIBLE
                    binding.rvStarredMessages.visibility = View.GONE
                } else {
                    binding.layoutEmptyState.visibility = View.GONE
                    binding.rvStarredMessages.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun openMessageInChat(message: Message) {
        val targetConvId = if (message.conversationId.isNotBlank()) message.conversationId else conversationId
        val resolvedTitle = if (conversationTitle.isNotBlank()) {
            conversationTitle
        } else {
            conversationTitlesMap[targetConvId].orEmpty()
        }
        val intent = Intent(this, ChatRoomActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_ID, targetConvId)
            putExtra(ChatRoomActivity.EXTRA_TARGET_MESSAGE_ID, message.id)
            if (resolvedTitle.isNotBlank()) {
                putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, resolvedTitle)
            }
        }
        startActivity(intent)
    }

    private fun unstarMessage(message: Message) {
        lifecycleScope.launch {
            chatRepo.toggleMessageStar(message.id, false)
            Toast.makeText(this@StarredMessagesActivity, "Message unstarred", Toast.LENGTH_SHORT).show()
        }
    }
}
