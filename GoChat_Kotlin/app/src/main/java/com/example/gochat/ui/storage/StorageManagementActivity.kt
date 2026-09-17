package com.example.gochat.ui.storage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.core.storage.ChatStorageItem
import com.example.gochat.core.storage.StorageManagerHelper
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.databinding.ActivityStorageManagementBinding
import com.example.gochat.databinding.ItemStorageChatBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class StorageManagementActivity : AppCompatActivity() {

    @Inject
    lateinit var chatRepository: ChatRepository

    private lateinit var binding: ActivityStorageManagementBinding
    private val chatStorageList = mutableListOf<ChatStorageItem>()
    private lateinit var storageAdapter: StorageChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorageManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackStorage.setOnClickListener { finish() }

        binding.btnQuickClean.setOnClickListener {
            performQuickCacheClean()
        }

        setupRecyclerView()
        refreshStorageData()
    }

    private fun setupRecyclerView() {
        storageAdapter = StorageChatAdapter(chatStorageList) { item ->
            showChatCleanupDialog(item)
        }
        binding.rvStorageChats.layoutManager = LinearLayoutManager(this)
        binding.rvStorageChats.adapter = storageAdapter
    }

    private fun refreshStorageData() {
        // Overall breakdown
        val breakdown = StorageManagerHelper.getStorageBreakdown(this)
        binding.tvUsedStorageHeader.text = StorageManagerHelper.formatSize(breakdown.goChatMediaBytes)
        binding.tvFreeStorageHeader.text = "Used by GoChat • ${StorageManagerHelper.formatSize(breakdown.freeBytes)} free on device"
        
        val totalConsidered = (breakdown.goChatMediaBytes + breakdown.freeBytes).coerceAtLeast(1L)
        val progressPercent = ((breakdown.goChatMediaBytes.toDouble() / totalConsidered) * 100).toInt().coerceIn(2, 100)
        binding.pbStorageUsage.progress = progressPercent
        binding.tvMediaLegend.text = "GoChat Media (${StorageManagerHelper.formatSize(breakdown.goChatMediaBytes)})"

        // Load per-chat breakdown
        binding.pbCalculatingStorage.visibility = View.VISIBLE
        binding.tvEmptyChatsStorage.visibility = View.GONE

        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                try {
                    val convs = chatRepository.observeConversations().first()
                    StorageManagerHelper.getChatStorageItems(this@StorageManagementActivity, chatRepository, convs)
                } catch (_: Exception) {
                    emptyList()
                }
            }

            binding.pbCalculatingStorage.visibility = View.GONE
            chatStorageList.clear()
            chatStorageList.addAll(items)
            storageAdapter.notifyDataSetChanged()

            binding.tvEmptyChatsStorage.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun performQuickCacheClean() {
        lifecycleScope.launch {
            binding.btnQuickClean.isEnabled = false
            withContext(Dispatchers.IO) {
                MediaImageHelper.clearImageCache(this@StorageManagementActivity)
                cacheDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.startsWith("temp_")) {
                        file.delete()
                    }
                }
            }
            binding.btnQuickClean.isEnabled = true
            Toast.makeText(this@StorageManagementActivity, "Cache cleared successfully", Toast.LENGTH_SHORT).show()
            refreshStorageData()
        }
    }

    private fun showChatCleanupDialog(item: ChatStorageItem) {
        val photosFormatted = StorageManagerHelper.formatSize(item.photosBytes)
        val voiceFormatted = StorageManagerHelper.formatSize(item.voiceBytes)
        val totalFormatted = StorageManagerHelper.formatSize(item.totalSizeBytes)

        val message = "Conversation: ${item.title}\n" +
                "Total Storage: $totalFormatted\n" +
                "• Photos & Videos: $photosFormatted\n" +
                "• Voice Notes: $voiceFormatted\n\n" +
                "Choose what to clean:"

        val options = arrayOf(
            "Clear all media ($totalFormatted)",
            "Clear photos & videos only ($photosFormatted)",
            "Clear voice notes only ($voiceFormatted)"
        )

        AlertDialog.Builder(this)
            .setTitle("Manage Storage")
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    val freed = withContext(Dispatchers.IO) {
                        val messages = chatRepository.searchMessagesInConversation(item.conversationId, "")
                        when (which) {
                            0 -> StorageManagerHelper.clearConversationMedia(this@StorageManagementActivity, messages, clearPhotos = true, clearVoice = true)
                            1 -> StorageManagerHelper.clearConversationMedia(this@StorageManagementActivity, messages, clearPhotos = true, clearVoice = false)
                            2 -> StorageManagerHelper.clearConversationMedia(this@StorageManagementActivity, messages, clearPhotos = false, clearVoice = true)
                            else -> 0L
                        }
                    }
                    val freedFormatted = StorageManagerHelper.formatSize(freed.coerceAtLeast(item.totalSizeBytes))
                    Toast.makeText(this@StorageManagementActivity, "Cleaned $freedFormatted from ${item.title}", Toast.LENGTH_SHORT).show()
                    refreshStorageData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class StorageChatAdapter(
        private val items: List<ChatStorageItem>,
        private val onClick: (ChatStorageItem) -> Unit
    ) : RecyclerView.Adapter<StorageChatAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemStorageChatBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemStorageChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            with(holder.binding) {
                tvStorageChatTitle.text = item.title
                tvStorageChatSubtitle.text = "${item.mediaCount} media files"
                tvStorageChatSize.text = StorageManagerHelper.formatSize(item.totalSizeBytes)

                MediaImageHelper.loadSafeImage(
                    imageView = ivStorageChatAvatar,
                    url = item.avatarUrl,
                    isCircle = true,
                    placeholderRes = R.drawable.ic_account,
                    errorRes = R.drawable.ic_account
                )

                root.setOnClickListener { onClick(item) }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
