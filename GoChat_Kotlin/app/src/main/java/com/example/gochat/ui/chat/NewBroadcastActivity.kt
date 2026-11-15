package com.example.gochat.ui.chat

import android.os.Bundle
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.databinding.ActivityNewBroadcastBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * WhatsApp-style broadcast: pick several contacts and send one message to each as an
 * individual 1:1 chat (recipients don't see each other). Client-side fan-out over the
 * user's existing 1:1 chat partners.
 */
@AndroidEntryPoint
class NewBroadcastActivity : AppCompatActivity() {

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var tokenManager: TokenManager

    private lateinit var binding: ActivityNewBroadcastBinding
    private val checkBoxes = mutableListOf<CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewBroadcastBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackBroadcast.setOnClickListener { finish() }
        binding.btnSendBroadcast.setOnClickListener { sendBroadcast() }
        loadContacts()
    }

    private fun loadContacts() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                val myId = tokenManager.userId.orEmpty()
                try {
                    chatRepository.getAllConversationsList()
                        .filter { !it.isGroup }
                        .mapNotNull { c ->
                            val pid = c.memberIds.firstOrNull { it != myId && it.isNotBlank() } ?: return@mapNotNull null
                            pid to c.title.ifBlank { "Contact" }
                        }
                        .distinctBy { it.first }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            if (list.isEmpty()) {
                binding.tvBroadcastEmpty.visibility = android.view.View.VISIBLE
                return@launch
            }
            list.forEach { (id, name) ->
                val cb = CheckBox(this@NewBroadcastActivity).apply {
                    text = name
                    tag = id
                    textSize = 16f
                    setPadding(paddingLeft + 24, 24, paddingRight, 24)
                    setOnCheckedChangeListener { _, _ -> updateCount() }
                }
                checkBoxes.add(cb)
                binding.containerContacts.addView(cb)
            }
        }
    }

    private fun updateCount() {
        val n = checkBoxes.count { it.isChecked }
        binding.tvSelectedCount.text = "$n selected"
    }

    private fun sendBroadcast() {
        val msg = binding.etBroadcastMessage.text?.toString()?.trim().orEmpty()
        if (msg.isBlank()) {
            Toast.makeText(this, "Type a message to broadcast", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = checkBoxes.filter { it.isChecked }.map { (it.tag as String) to it.text.toString() }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Select at least one contact", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnSendBroadcast.isEnabled = false
        Toast.makeText(this, "Sending broadcast…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val sent = withContext(Dispatchers.IO) {
                var count = 0
                val existing = try { chatRepository.getAllConversationsList() } catch (_: Exception) { emptyList() }
                for ((id, name) in selected) {
                    try {
                        val conv = existing.firstOrNull { !it.isGroup && it.memberIds.contains(id) }
                            ?: chatRepository.createConversation(name, listOf(id)).getOrNull()
                        if (conv != null) {
                            chatRepository.sendMessage(conversationId = conv.id, content = msg, type = 0)
                            count++
                        }
                    } catch (_: Exception) {}
                }
                count
            }
            Toast.makeText(
                this@NewBroadcastActivity,
                "Broadcast sent to $sent ${if (sent == 1) "contact" else "contacts"}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }
}
