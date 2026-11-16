package com.example.gochat.ui.channels

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.model.Channel
import com.example.gochat.data.model.ChannelMessage
import com.example.gochat.data.repository.ChannelRepository
import com.example.gochat.databinding.ActivityChannelViewBinding
import com.example.gochat.databinding.ItemChannelPostBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** A single channel: header, subscribe toggle, posts, and an owner composer. */
@AndroidEntryPoint
class ChannelViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
    }

    @Inject lateinit var channelRepository: ChannelRepository
    @Inject lateinit var tokenManager: TokenManager

    private lateinit var binding: ActivityChannelViewBinding
    private val channelId by lazy { intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty() }
    private val timeFmt by lazy { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }

    private var isSubscribed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (channelId.isBlank()) { finish(); return }

        binding.tvChannelViewTitle.text = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "Channel"
        binding.btnBackChannelView.setOnClickListener { finish() }
        binding.swipeChannelPosts.setOnRefreshListener { load() }
        binding.btnSubscribe.setOnClickListener { toggleSubscribe() }
        binding.btnSendChannelPost.setOnClickListener { publishPost() }
        binding.btnDeleteChannel.setOnClickListener { confirmDelete() }
        load()
    }

    private fun load() {
        binding.swipeChannelPosts.isRefreshing = true
        lifecycleScope.launch {
            val myId = tokenManager.userId.orEmpty()
            val channel: Channel?
            val subscribed: Boolean
            val posts: List<ChannelMessage>
            withContext(Dispatchers.IO) {
                channel = channelRepository.getChannel(channelId).getOrNull()
                subscribed = channelRepository.listChannels(subscribedOnly = true).getOrNull()?.any { it.id == channelId } ?: false
                posts = channelRepository.getMessages(channelId).getOrNull() ?: emptyList()
            }
            isSubscribed = subscribed
            bindHeader(channel, myId)
            renderPosts(posts)
            binding.swipeChannelPosts.isRefreshing = false
        }
    }

    private fun bindHeader(channel: Channel?, myId: String) {
        if (channel != null) {
            binding.tvChannelViewTitle.text = channel.name.ifBlank { "Channel" }
            if (channel.description.isNotBlank()) {
                binding.tvChannelViewDesc.visibility = View.VISIBLE
                binding.tvChannelViewDesc.text = channel.description
            }
            val n = channel.subscribersCount
            binding.tvChannelViewSubs.text = "${NumberFormat.getIntegerInstance().format(n)} ${if (n == 1) "subscriber" else "subscribers"}"
            val isOwner = channel.createdBy.isNotBlank() && channel.createdBy == myId
            binding.layoutComposer.visibility = if (isOwner) View.VISIBLE else View.GONE
            binding.btnDeleteChannel.visibility = if (isOwner) View.VISIBLE else View.GONE
        }
        binding.btnSubscribe.text = if (isSubscribed) "Unsubscribe" else "Subscribe"
    }

    private fun renderPosts(posts: List<ChannelMessage>) {
        binding.containerPosts.removeAllViews()
        if (posts.isEmpty()) {
            binding.containerPosts.addView(binding.tvPostsEmpty)
            binding.tvPostsEmpty.visibility = View.VISIBLE
            return
        }
        posts.forEach { post ->
            val item = ItemChannelPostBinding.inflate(layoutInflater, binding.containerPosts, false)
            item.tvPostContent.text = post.content
            val ms = if (post.createdAt < 100_000_000_000L) post.createdAt * 1000 else post.createdAt
            item.tvPostTime.text = timeFmt.format(Date(ms))
            binding.containerPosts.addView(item.root)
        }
    }

    private fun toggleSubscribe() {
        binding.btnSubscribe.isEnabled = false
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (isSubscribed) channelRepository.unsubscribe(channelId) else channelRepository.subscribe(channelId)
            }
            binding.btnSubscribe.isEnabled = true
            if (ok) load() else Toast.makeText(this@ChannelViewActivity, "Action failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun publishPost() {
        val content = binding.etChannelPost.text?.toString()?.trim().orEmpty()
        if (content.isBlank()) return
        binding.btnSendChannelPost.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { channelRepository.publish(channelId, content) }
            binding.btnSendChannelPost.isEnabled = true
            result.onSuccess {
                binding.etChannelPost.text?.clear()
                load()
            }.onFailure {
                Toast.makeText(this@ChannelViewActivity, it.message ?: "Failed to post", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete channel")
            .setMessage("Delete this channel and all its posts? This cannot be undone.")
            .setPositiveButton(getString(com.example.gochat.R.string.action_delete)) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { channelRepository.deleteChannel(channelId) }
                    if (ok) finish() else Toast.makeText(this@ChannelViewActivity, "Failed to delete", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(com.example.gochat.R.string.btn_cancel), null)
            .show()
    }
}
