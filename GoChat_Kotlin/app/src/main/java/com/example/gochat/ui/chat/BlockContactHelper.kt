package com.example.gochat.ui.chat

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentManager
import com.example.gochat.R
import com.example.gochat.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Helper utility to present standard WhatsApp-style Block, Unblock, and Report flows.
 */
object BlockContactHelper {

    /**
     * Shows a confirmation dialog to block a contact.
     */
    fun showBlockConfirmationDialog(
        context: Context,
        coroutineScope: CoroutineScope,
        chatRepository: ChatRepository,
        userId: String,
        userName: String,
        onBlocked: (() -> Unit)? = null
    ) {
        val displayName = userName.ifBlank { "this contact" }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.block_user_title, displayName))
            .setMessage(context.getString(R.string.block_user_message))
            .setPositiveButton(context.getString(R.string.action_block)) { _, _ ->
                coroutineScope.launch {
                    chatRepository.blockUser(userId, userName)
                    Toast.makeText(
                        context,
                        context.getString(R.string.user_blocked_toast, displayName),
                        Toast.LENGTH_SHORT
                    ).show()
                    onBlocked?.invoke()
                }
            }
            .setNegativeButton(context.getString(R.string.btn_cancel), null)
            .show()
    }

    /**
     * Shows a confirmation dialog to unblock a contact.
     */
    fun showUnblockConfirmationDialog(
        context: Context,
        coroutineScope: CoroutineScope,
        chatRepository: ChatRepository,
        userId: String,
        userName: String,
        onUnblocked: (() -> Unit)? = null
    ) {
        val displayName = userName.ifBlank { "this contact" }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.unblock_user_title, displayName))
            .setMessage(context.getString(R.string.unblock_user_message))
            .setPositiveButton(context.getString(R.string.action_unblock)) { _, _ ->
                coroutineScope.launch {
                    chatRepository.unblockUser(userId)
                    Toast.makeText(
                        context,
                        context.getString(R.string.user_unblocked_toast, displayName),
                        Toast.LENGTH_SHORT
                    ).show()
                    onUnblocked?.invoke()
                }
            }
            .setNegativeButton(context.getString(R.string.btn_cancel), null)
            .show()
    }

    /**
     * Shows the Report Contact BottomSheet Dialog.
     */
    fun showReportBottomSheet(
        fragmentManager: FragmentManager,
        userId: String,
        userName: String,
        conversationId: String?,
        chatRepository: ChatRepository,
        onReportCompleted: ((blocked: Boolean) -> Unit)? = null
    ) {
        val sheet = ReportContactBottomSheet(
            targetUserId = userId,
            targetUserName = userName,
            conversationId = conversationId,
            chatRepository = chatRepository,
            onReportCompleted = onReportCompleted
        )
        sheet.show(fragmentManager, ReportContactBottomSheet.TAG)
    }
}
