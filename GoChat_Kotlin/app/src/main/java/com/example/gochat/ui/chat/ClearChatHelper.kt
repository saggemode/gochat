package com.example.gochat.ui.chat

import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import com.example.gochat.R
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.databinding.DialogClearChatBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ClearChatHelper {

    fun showClearChatDialog(
        context: Context,
        coroutineScope: CoroutineScope,
        chatRepository: ChatRepository,
        conversationId: String,
        onCleared: (() -> Unit)? = null
    ) {
        if (conversationId.isBlank()) return

        coroutineScope.launch {
            val starredCount = chatRepository.getStarredMessagesCount(conversationId)
            withContext(Dispatchers.Main) {
                val binding = DialogClearChatBinding.inflate(LayoutInflater.from(context))

                if (starredCount > 0) {
                    binding.cbDeleteStarred.text = context.getString(
                        R.string.clear_chat_delete_starred_with_count,
                        starredCount
                    )
                } else {
                    binding.cbDeleteStarred.text = context.getString(R.string.clear_chat_delete_starred)
                }
                binding.cbDeleteStarred.isChecked = false
                binding.cbDeleteMedia.isChecked = false

                val dialog = MaterialAlertDialogBuilder(context)
                    .setView(binding.root)
                    .create()

                binding.btnClearChatCancel.setOnClickListener {
                    dialog.dismiss()
                }

                binding.btnClearChatConfirm.setOnClickListener {
                    dialog.dismiss()
                    val deleteStarred = binding.cbDeleteStarred.isChecked
                    coroutineScope.launch {
                        chatRepository.clearChat(conversationId, deleteStarred)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_chat_cleared),
                                Toast.LENGTH_SHORT
                            ).show()
                            onCleared?.invoke()
                        }
                    }
                }

                dialog.show()
            }
        }
    }
}
