package com.example.gochat.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Message
import com.example.gochat.data.model.MessageType
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.databinding.ActivitySharedMediaBinding
import com.example.gochat.databinding.ItemSharedDocOrLinkBinding
import com.example.gochat.databinding.ItemSharedMediaGridBinding
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import javax.inject.Inject

@AndroidEntryPoint
class SharedMediaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_CONVERSATION_TITLE = "extra_conversation_title"
    }

    @Inject
    lateinit var chatRepository: ChatRepository

    private lateinit var binding: ActivitySharedMediaBinding
    private var conversationId: String = ""
    private var conversationTitle: String = ""

    private var allMessages: List<Message> = emptyList()
    private var currentTabPosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySharedMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        conversationTitle = intent.getStringExtra(EXTRA_CONVERSATION_TITLE) ?: "Chat"

        binding.tvSharedMediaHeaderSubtitle.text = conversationTitle
        binding.btnBackSharedMedia.setOnClickListener { finish() }

        setupTabs()
        observeMedia()
    }

    private fun setupTabs() {
        binding.tabLayoutSharedMedia.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTabPosition = tab?.position ?: 0
                renderCurrentTab()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun observeMedia() {
        if (conversationId.isEmpty()) return

        binding.pbLoadingShared.visibility = View.VISIBLE
        lifecycleScope.launch {
            chatRepository.observeMessages(conversationId).collectLatest { messages ->
                binding.pbLoadingShared.visibility = View.GONE
                allMessages = messages
                renderCurrentTab()
            }
        }
    }

    private fun renderCurrentTab() {
        when (currentTabPosition) {
            0 -> renderMediaTab()
            1 -> renderDocsTab()
            2 -> renderLinksTab()
        }
    }

    private fun renderMediaTab() {
        val mediaList = allMessages.filter { msg ->
            !msg.isDeleted && (
                msg.type == MessageType.IMAGE ||
                msg.type == MessageType.VIDEO ||
                (!msg.mediaUrl.isNullOrBlank() && (msg.content.contains("Photo", ignoreCase = true) || msg.content.contains("Video", ignoreCase = true)))
            )
        }

        if (mediaList.isEmpty()) {
            binding.rvSharedMedia.visibility = View.GONE
            binding.layoutEmptyShared.visibility = View.VISIBLE
            binding.ivEmptyIcon.setImageResource(R.drawable.ic_gallery)
            binding.tvEmptyTitle.text = "No media shared yet"
        } else {
            binding.rvSharedMedia.visibility = View.VISIBLE
            binding.layoutEmptyShared.visibility = View.GONE

            binding.rvSharedMedia.layoutManager = GridLayoutManager(this, 3)
            binding.rvSharedMedia.adapter = SharedMediaGridAdapter(mediaList) { clickedMsg ->
                val url = clickedMsg.mediaUrl.orEmpty()
                if (url.isNotBlank()) {
                    val intent = Intent(this, MediaViewerActivity::class.java).apply {
                        putExtra(MediaViewerActivity.EXTRA_MEDIA_URL, url)
                        putExtra(MediaViewerActivity.EXTRA_TITLE, conversationTitle)
                        putExtra(MediaViewerActivity.EXTRA_IS_VIDEO, clickedMsg.type == MessageType.VIDEO)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun renderDocsTab() {
        val docsList = allMessages.filter { msg ->
            !msg.isDeleted && (
                msg.type == MessageType.FILE ||
                msg.type == MessageType.AUDIO ||
                msg.type == MessageType.VOICE ||
                msg.content.contains("Voice Note", ignoreCase = true) ||
                msg.content.endsWith(".pdf", ignoreCase = true) ||
                msg.content.endsWith(".doc", ignoreCase = true) ||
                msg.content.endsWith(".zip", ignoreCase = true)
            )
        }

        if (docsList.isEmpty()) {
            binding.rvSharedMedia.visibility = View.GONE
            binding.layoutEmptyShared.visibility = View.VISIBLE
            binding.ivEmptyIcon.setImageResource(R.drawable.ic_attach_file)
            binding.tvEmptyTitle.text = "No documents shared yet"
        } else {
            binding.rvSharedMedia.visibility = View.VISIBLE
            binding.layoutEmptyShared.visibility = View.GONE

            binding.rvSharedMedia.layoutManager = LinearLayoutManager(this)
            binding.rvSharedMedia.adapter = SharedDocsAdapter(docsList) { clickedMsg ->
                // Navigate to ChatRoom focused on this message
                val intent = Intent(this, ChatRoomActivity::class.java).apply {
                    putExtra(ChatRoomActivity.EXTRA_CONVERSATION_ID, conversationId)
                    putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, conversationTitle)
                    putExtra(ChatRoomActivity.EXTRA_TARGET_MESSAGE_ID, clickedMsg.id)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            }
        }
    }

    private val urlPattern = Pattern.compile("https?://[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]+")

    private fun renderLinksTab() {
        val linksList = allMessages.filter { msg ->
            !msg.isDeleted && urlPattern.matcher(msg.content).find()
        }

        if (linksList.isEmpty()) {
            binding.rvSharedMedia.visibility = View.GONE
            binding.layoutEmptyShared.visibility = View.VISIBLE
            binding.ivEmptyIcon.setImageResource(R.drawable.ic_link)
            binding.tvEmptyTitle.text = "No links shared yet"
        } else {
            binding.rvSharedMedia.visibility = View.VISIBLE
            binding.layoutEmptyShared.visibility = View.GONE

            binding.rvSharedMedia.layoutManager = LinearLayoutManager(this)
            binding.rvSharedMedia.adapter = SharedLinksAdapter(linksList) { clickedMsg, linkUrl ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl))
                    startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(this, ChatRoomActivity::class.java).apply {
                        putExtra(ChatRoomActivity.EXTRA_CONVERSATION_ID, conversationId)
                        putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, conversationTitle)
                        putExtra(ChatRoomActivity.EXTRA_TARGET_MESSAGE_ID, clickedMsg.id)
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    // --- Adapters ---

    private class SharedMediaGridAdapter(
        private val items: List<Message>,
        private val onClick: (Message) -> Unit
    ) : RecyclerView.Adapter<SharedMediaGridAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemSharedMediaGridBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemSharedMediaGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val msg = items[position]
            MediaImageHelper.loadSafeImage(
                imageView = holder.binding.ivSharedThumbnail,
                url = msg.mediaUrl,
                isCircle = false,
                placeholderRes = R.drawable.ic_gallery,
                errorRes = R.drawable.ic_gallery
            )
            holder.binding.ivVideoIndicator.visibility = if (msg.type == MessageType.VIDEO) View.VISIBLE else View.GONE
            holder.binding.root.setOnClickListener { onClick(msg) }
        }

        override fun getItemCount(): Int = items.size
    }

    private class SharedDocsAdapter(
        private val items: List<Message>,
        private val onClick: (Message) -> Unit
    ) : RecyclerView.Adapter<SharedDocsAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemSharedDocOrLinkBinding) : RecyclerView.ViewHolder(binding.root)

        private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemSharedDocOrLinkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val msg = items[position]
            val isAudio = msg.type == MessageType.AUDIO || msg.type == MessageType.VOICE || msg.content.contains("Voice Note", ignoreCase = true)
            holder.binding.ivDocOrLinkIcon.setImageResource(if (isAudio) R.drawable.ic_mic else R.drawable.ic_attach_file)
            holder.binding.tvDocOrLinkTitle.text = if (isAudio) "Voice Note" else msg.content.ifBlank { "Document" }
            holder.binding.tvDocOrLinkSubtitle.text = dateFormat.format(Date(msg.createdAt))
            holder.binding.root.setOnClickListener { onClick(msg) }
        }

        override fun getItemCount(): Int = items.size
    }

    private class SharedLinksAdapter(
        private val items: List<Message>,
        private val onClick: (Message, String) -> Unit
    ) : RecyclerView.Adapter<SharedLinksAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemSharedDocOrLinkBinding) : RecyclerView.ViewHolder(binding.root)

        private val urlPattern = Pattern.compile("https?://[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]+")
        private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemSharedDocOrLinkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val msg = items[position]
            val matcher = urlPattern.matcher(msg.content)
            val extractedUrl = if (matcher.find()) matcher.group(0) ?: msg.content else msg.content

            holder.binding.ivDocOrLinkIcon.setImageResource(R.drawable.ic_link)
            holder.binding.tvDocOrLinkTitle.text = extractedUrl
            holder.binding.tvDocOrLinkSubtitle.text = "${dateFormat.format(Date(msg.createdAt))} • Tap to open"
            holder.binding.root.setOnClickListener { onClick(msg, extractedUrl) }
        }

        override fun getItemCount(): Int = items.size
    }
}
