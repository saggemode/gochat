package com.example.gochat.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.R
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.databinding.ActivityPrivacySettingsBinding
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class PrivacySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacySettingsBinding
    private lateinit var authRepo: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authRepo = AuthRepository(this)

        setupToolbar()
        loadSettings()
        setupListeners()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            authRepo.getPrivacySettings().onSuccess { json ->
                val everyone = getString(R.string.visibility_everyone)
                binding.tvLastSeenValue.text = json["last_seen_privacy"]?.jsonPrimitive?.contentOrNull?.replaceFirstChar { it.uppercase() } ?: everyone
                binding.tvProfilePhotoValue.text = json["profile_photo_privacy"]?.jsonPrimitive?.contentOrNull?.replaceFirstChar { it.uppercase() } ?: everyone
                binding.tvStatusValue.text = json["status_privacy"]?.jsonPrimitive?.contentOrNull?.replaceFirstChar { it.uppercase() } ?: everyone
                binding.switchReadReceipts.isChecked = json["read_receipts_enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            }
        }
    }

    private fun setupListeners() {
        binding.btnLastSeen.setOnClickListener { showPrivacyDialog("last_seen_privacy") }
        binding.btnProfilePhoto.setOnClickListener { showPrivacyDialog("profile_photo_privacy") }
        binding.btnStatus.setOnClickListener { showPrivacyDialog("status_privacy") }

        binding.switchReadReceipts.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                authRepo.updatePrivacySettings(readReceipts = isChecked)
            }
        }
    }

    private fun showPrivacyDialog(key: String) {
        val options = arrayOf(
            getString(R.string.visibility_everyone),
            getString(R.string.visibility_contacts),
            getString(R.string.visibility_nobody)
        )
        val values = arrayOf("everyone", "contacts", "nobody")
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_select_visibility_title)) // need to add this
            .setItems(options) { _, which ->
                val selectedValue = values[which]
                lifecycleScope.launch {
                    val result = when (key) {
                        "last_seen_privacy" -> authRepo.updatePrivacySettings(lastSeen = selectedValue)
                        "profile_photo_privacy" -> authRepo.updatePrivacySettings(profilePhoto = selectedValue)
                        "status_privacy" -> authRepo.updatePrivacySettings(status = selectedValue)
                        else -> Result.failure(Exception("Invalid key"))
                    }
                    if (result.isSuccess) {
                        loadSettings()
                    } else {
                        Toast.makeText(this@PrivacySettingsActivity, getString(R.string.error_privacy_update), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }
}
