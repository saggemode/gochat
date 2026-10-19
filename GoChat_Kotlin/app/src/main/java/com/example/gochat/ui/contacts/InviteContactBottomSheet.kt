package com.example.gochat.ui.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.example.gochat.data.model.SyncedContact
import com.example.gochat.databinding.BottomSheetInviteContactBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

object InviteContactBottomSheet {

    fun show(
        context: Context,
        contact: SyncedContact? = null,
        userPin: String? = null,
        userDisplayName: String? = null
    ) {
        val dialog = BottomSheetDialog(context)
        val binding = BottomSheetInviteContactBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        val contactName = contact?.displayName?.trim().orEmpty()
        val contactPhone = contact?.phone?.trim().orEmpty()

        if (contactName.isNotBlank()) {
            binding.tvInviteTitle.text = "Invite $contactName"
            binding.tvInviteRecipient.text = if (contactPhone.isNotBlank()) {
                "$contactName ($contactPhone)"
            } else {
                contactName
            }
            binding.tvInviteRecipient.visibility = View.VISIBLE
        } else {
            binding.tvInviteTitle.text = "Invite to GoChat"
            binding.tvInviteRecipient.text = "Share GoChat download link with your contacts"
            binding.tvInviteRecipient.visibility = View.VISIBLE
        }

        val pinPart = if (!userPin.isNullOrBlank()) " or add my PIN: $userPin" else ""
        val greeting = if (contactName.isNotBlank()) "Hey $contactName! " else "Hey! "
        val inviteMessage = "${greeting}Let's chat on GoChat. It's fast, private, and end-to-end encrypted. Download it here: https://gochat.im/dl$pinPart"

        binding.tvInviteMessagePreview.text = inviteMessage

        // If phone is missing, update the subtitle of the SMS button
        if (contactPhone.isBlank()) {
            binding.tvSmsSubtitle.text = "Compose text message in your SMS app"
        }

        // Action 1: Send SMS
        binding.btnActionSms.setOnClickListener {
            dialog.dismiss()
            try {
                val cleanDigits = contactPhone.replace(Regex("[^0-9+]"), "")
                val smsUri = if (cleanDigits.isNotBlank()) Uri.parse("smsto:$cleanDigits") else Uri.parse("smsto:")
                val smsIntent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                    putExtra("sms_body", inviteMessage)
                }
                context.startActivity(smsIntent)
            } catch (_: Exception) {
                try {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                        val cleanDigits = contactPhone.replace(Regex("[^0-9+]"), "")
                        data = if (cleanDigits.isNotBlank()) Uri.parse("sms:$cleanDigits") else Uri.parse("sms:")
                        putExtra("sms_body", inviteMessage)
                    }
                    context.startActivity(fallbackIntent)
                } catch (_: Exception) {
                    Toast.makeText(context, "Could not open SMS application", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Action 2: Share via Apps
        binding.btnActionShare.setOnClickListener {
            dialog.dismiss()
            try {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Join me on GoChat!")
                    putExtra(Intent.EXTRA_TEXT, inviteMessage)
                }
                val chooser = Intent.createChooser(shareIntent, "Invite to GoChat via")
                context.startActivity(chooser)
            } catch (_: Exception) {
                Toast.makeText(context, "Could not open share sheet", Toast.LENGTH_SHORT).show()
            }
        }

        // Action 3: Copy Link & Message
        binding.btnActionCopy.setOnClickListener {
            dialog.dismiss()
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("GoChat Invite", inviteMessage)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Invite message copied to clipboard", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Failed to copy invite message", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }
}
