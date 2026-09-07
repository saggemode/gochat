package com.example.gochat.ui.security

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import coil.load
import coil.transform.CircleCropTransformation
import com.example.gochat.R
import com.example.gochat.core.crypto.SecurityVerificationManager
import com.example.gochat.data.api.TokenManager
import com.example.gochat.databinding.ActivitySecurityVerificationBinding
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SecurityVerificationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_PEER_USER_ID = "extra_peer_user_id"
        const val EXTRA_PEER_PIN = "extra_peer_pin"
        const val EXTRA_PEER_NAME = "extra_peer_name"
        const val EXTRA_PEER_AVATAR = "extra_peer_avatar"
    }

    private lateinit var binding: ActivitySecurityVerificationBinding

    @Inject
    lateinit var verificationManager: SecurityVerificationManager

    @Inject
    lateinit var tokenManager: TokenManager

    private var safetyNumber: String = ""
    private var conversationId: String = ""

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val isValid = verificationManager.validateScannedQr(
                scannedData = result.contents,
                expectedConversationId = conversationId,
                expectedSafetyNumber = safetyNumber
            )
            if (isValid) {
                verificationManager.setVerified(conversationId, true)
                updateVerifiedStatus(true)
                Toast.makeText(this, getString(R.string.security_verification_matching), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.security_verification_not_matching), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        val peerUserId = intent.getStringExtra(EXTRA_PEER_USER_ID).orEmpty()
        val peerPin = intent.getStringExtra(EXTRA_PEER_PIN)
        val peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "User"
        val peerAvatar = intent.getStringExtra(EXTRA_PEER_AVATAR).orEmpty()

        setupToolbar()
        loadPeerInfo(peerName, peerAvatar)
        generateAndDisplaySafetyNumber(peerUserId, peerPin)
        setupListeners()
        updateVerifiedStatus(verificationManager.isVerified(conversationId))
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadPeerInfo(name: String, avatarUrl: String) {
        binding.ivPartnerAvatar.load(avatarUrl) {
            placeholder(R.drawable.ic_account)
            error(R.drawable.ic_account)
            transformations(CircleCropTransformation())
        }
        binding.tvVerificationDesc.text = getString(R.string.security_verification_desc, name)
    }

    private fun generateAndDisplaySafetyNumber(peerUserId: String, peerPin: String?) {
        val myUserId = tokenManager.userId ?: ""
        val myPin = tokenManager.userPin

        safetyNumber = verificationManager.generateSafetyNumber(
            myUserId = myUserId,
            peerUserId = peerUserId,
            conversationId = conversationId,
            myPin = myPin,
            peerPin = peerPin
        )

        displaySafetyNumberBlocks(safetyNumber)
        displayQrCode(safetyNumber)
    }

    private fun displaySafetyNumberBlocks(number: String) {
        val blocks = number.split(" ")
        binding.gridSafetyNumber.removeAllViews()
        blocks.forEach { block ->
            val tv = TextView(this).apply {
                text = block
                setTextColor(Color.WHITE)
                textSize = 18f
                setPadding(16, 16, 16, 16)
                gravity = Gravity.CENTER
            }
            binding.gridSafetyNumber.addView(tv)
        }
    }

    private fun displayQrCode(number: String) {
        try {
            val payload = verificationManager.generateQrPayload(conversationId, number)
            val barcodeEncoder = BarcodeEncoder()
            val bitmap = barcodeEncoder.encodeBitmap(payload, BarcodeFormat.QR_CODE, 600, 600)
            binding.ivQrCode.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupListeners() {
        binding.btnScanCode.setOnClickListener {
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            options.setPrompt(getString(R.string.security_verification_scan_code))
            options.setCameraId(0)
            options.setBeepEnabled(false)
            options.setBarcodeImageEnabled(true)
            scanLauncher.launch(options)
        }
    }

    private fun updateVerifiedStatus(isVerified: Boolean) {
        binding.layoutVerifiedStatus.visibility = if (isVerified) View.VISIBLE else View.GONE
    }
}
