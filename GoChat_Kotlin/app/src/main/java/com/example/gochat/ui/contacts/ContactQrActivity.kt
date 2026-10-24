package com.example.gochat.ui.contacts

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.core.ui.applyEdgeToEdge
import com.example.gochat.core.ui.applyStatusBarInset
import com.example.gochat.core.ui.applySystemBarInsets
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.databinding.ActivityContactQrBinding
import com.example.gochat.ui.chat.ChatRoomActivity
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * WhatsApp-style contact QR screen: shows the user's own QR code (encoding
 * gochat://user/<pin>) and lets them scan another user's code to start a chat.
 */
@AndroidEntryPoint
class ContactQrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactQrBinding

    private var qrBitmap: android.graphics.Bitmap? = null

    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var chatRepository: ChatRepository

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            startChatWithPin(extractPin(contents))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityContactQrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarContactQr.applyStatusBarInset()
        binding.contactQrRoot.applySystemBarInsets(applyTop = false, applyBottom = true)

        binding.toolbarContactQr.setNavigationOnClickListener { finish() }
        binding.btnScanContactQr.setOnClickListener { launchScanner() }
        binding.btnShareMyQr.setOnClickListener { shareMyCode() }

        renderMyCode()

        // If opened via a gochat://user/<pin> deep link, resolve straight to a chat.
        intent?.getStringExtra(EXTRA_RESOLVE_PIN)?.takeIf { it.isNotBlank() }?.let {
            startChatWithPin(it)
        }
    }

    private fun renderMyCode() {
        val pin = tokenManager.userPin.orEmpty()
        val name = tokenManager.userDisplayName?.ifBlank { null } ?: "Me"
        binding.tvQrName.text = name
        binding.tvQrPin.text = if (pin.isNotBlank()) "PIN: $pin" else "PIN unavailable"

        if (pin.isBlank()) {
            binding.ivQr.visibility = View.INVISIBLE
            return
        }
        try {
            val content = "gochat://user/$pin"
            val bitmap = BarcodeEncoder().encodeBitmap(content, BarcodeFormat.QR_CODE, 600, 600)
            binding.ivQr.setImageBitmap(bitmap)
            qrBitmap = bitmap
        } catch (_: Exception) {
            binding.ivQr.visibility = View.INVISIBLE
        }
    }

    /** Shares the user's QR image plus the gochat://user/<pin> deep link via the system share sheet. */
    private fun shareMyCode() {
        val pin = tokenManager.userPin.orEmpty()
        if (pin.isBlank()) {
            Toast.makeText(this, "Your code isn't available yet", Toast.LENGTH_SHORT).show()
            return
        }
        val name = tokenManager.userDisplayName?.ifBlank { null } ?: "me"
        val link = "gochat://user/$pin"
        val text = "Add $name on GoChat 👋\nTap this link (or scan my QR code): $link"

        val imageUri = try {
            qrBitmap?.let { bmp ->
                val file = java.io.File(cacheDir, "my_gochat_qr.png")
                java.io.FileOutputStream(file).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                androidx.core.content.FileProvider.getUriForFile(
                    this, "${applicationContext.packageName}.fileprovider", file
                )
            }
        } catch (_: Exception) {
            null
        }

        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            if (imageUri != null) {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, imageUri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
        }
        startActivity(android.content.Intent.createChooser(share, "Share my GoChat code"))
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan a GoChat contact code")
            setBeepEnabled(true)
            setOrientationLocked(false)
            setBarcodeImageEnabled(false)
        }
        scanLauncher.launch(options)
    }

    /** Accepts gochat://user/<pin>, a bare pin, or a pin with surrounding whitespace. */
    private fun extractPin(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("gochat://user", ignoreCase = true)) {
            Uri.parse(trimmed).lastPathSegment.orEmpty()
        } else {
            trimmed
        }
    }

    private fun startChatWithPin(pin: String) {
        if (pin.isBlank()) {
            Toast.makeText(this, "Invalid contact code", Toast.LENGTH_SHORT).show()
            return
        }
        val myPin = tokenManager.userPin
        if (!myPin.isNullOrBlank() && pin.equals(myPin, ignoreCase = true)) {
            Toast.makeText(this, "That's your own code", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnScanContactQr.isEnabled = false
        lifecycleScope.launch {
            val userResult = authRepository.lookupUserByPin(pin)
            val user = userResult.getOrNull()
            if (user == null) {
                binding.btnScanContactQr.isEnabled = true
                Toast.makeText(this@ContactQrActivity, "No GoChat user found for that code", Toast.LENGTH_LONG).show()
                return@launch
            }
            val convResult = chatRepository.createConversation(
                name = user.displayName.ifBlank { "User ${user.pin}" },
                memberIds = listOf(user.id),
                isGroup = false
            )
            binding.btnScanContactQr.isEnabled = true
            val conv = convResult.getOrNull()
            if (conv != null) {
                openChatRoom(conv)
                finish()
            } else {
                Toast.makeText(this@ContactQrActivity, "Couldn't start the chat. Try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openChatRoom(conversation: Conversation) {
        val intent = android.content.Intent(this, ChatRoomActivity::class.java).apply {
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_ID, conversation.id)
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, conversation.title)
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_AVATAR, conversation.avatarUrl)
            putExtra(ChatRoomActivity.EXTRA_IS_ONLINE, conversation.isOnline)
            putExtra(ChatRoomActivity.EXTRA_LAST_SEEN, conversation.lastSeen ?: 0L)
        }
        startActivity(intent)
    }

    companion object {
        const val EXTRA_RESOLVE_PIN = "extra_resolve_pin"
    }
}
