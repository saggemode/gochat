package com.example.gochat.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.R
import com.example.gochat.core.media.MediaAutoDownloadManager
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.databinding.ActivityPrivacySettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

@AndroidEntryPoint
class PrivacySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacySettingsBinding
    
    @Inject
    lateinit var authRepo: AuthRepository

    @Inject
    lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        binding.switchAppLock.isChecked = tokenManager.isBiometricLockEnabled

        // Media Auto-Download summaries
        updateMediaAutoDownloadSummaries()
    }

    private fun updateMediaAutoDownloadSummaries() {
        binding.tvCellularAutoDownloadSummary.text = MediaAutoDownloadManager.getCellularSummary(this)
        binding.tvWifiAutoDownloadSummary.text = MediaAutoDownloadManager.getWifiSummary(this)
    }

    private fun setupListeners() {
        binding.btnLastSeen.setOnClickListener { showPrivacyDialog("last_seen_privacy", "Last Seen & Online Status") }
        binding.btnProfilePhoto.setOnClickListener { showPrivacyDialog("profile_photo_privacy", "Profile Picture Visibility") }
        binding.btnStatus.setOnClickListener { showPrivacyDialog("status_privacy", "Status Visibility") }

        binding.switchReadReceipts.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                val res = authRepo.updatePrivacySettings(readReceipts = isChecked)
                if (res.isFailure) {
                    Toast.makeText(this@PrivacySettingsActivity, "Failed to update read receipts", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnAppLock.setOnClickListener {
            val newState = !binding.switchAppLock.isChecked
            tokenManager.isBiometricLockEnabled = newState
            binding.switchAppLock.isChecked = newState
        }

        binding.btnAutoDownloadCellular.setOnClickListener {
            showCellularAutoDownloadDialog()
        }

        binding.btnAutoDownloadWifi.setOnClickListener {
            showWifiAutoDownloadDialog()
        }
    }

    private fun showPrivacyDialog(key: String, title: String) {
        val options = arrayOf(
            getString(R.string.visibility_everyone),
            getString(R.string.visibility_contacts),
            getString(R.string.visibility_nobody)
        )
        val values = arrayOf("everyone", "contacts", "nobody")
        
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                val selectedValue = values[which]
                lifecycleScope.launch {
                    val result = when (key) {
                        "last_seen_privacy" -> authRepo.updatePrivacySettings(lastSeen = selectedValue, online = selectedValue)
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

    private fun showCellularAutoDownloadDialog() {
        val items = arrayOf("Photos", "Audio", "Videos", "Documents")
        val keys = arrayOf(
            MediaAutoDownloadManager.KEY_CELLULAR_PHOTOS,
            MediaAutoDownloadManager.KEY_CELLULAR_AUDIO,
            MediaAutoDownloadManager.KEY_CELLULAR_VIDEOS,
            MediaAutoDownloadManager.KEY_CELLULAR_DOCUMENTS
        )
        val checkedItems = booleanArrayOf(
            MediaAutoDownloadManager.isCellularEnabled(this, keys[0]),
            MediaAutoDownloadManager.isCellularEnabled(this, keys[1]),
            MediaAutoDownloadManager.isCellularEnabled(this, keys[2]),
            MediaAutoDownloadManager.isCellularEnabled(this, keys[3])
        )

        AlertDialog.Builder(this)
            .setTitle("When using mobile data")
            .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                for (i in keys.indices) {
                    MediaAutoDownloadManager.setCellularEnabled(this, keys[i], checkedItems[i])
                }
                updateMediaAutoDownloadSummaries()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWifiAutoDownloadDialog() {
        val items = arrayOf("Photos", "Audio", "Videos", "Documents")
        val keys = arrayOf(
            MediaAutoDownloadManager.KEY_WIFI_PHOTOS,
            MediaAutoDownloadManager.KEY_WIFI_AUDIO,
            MediaAutoDownloadManager.KEY_WIFI_VIDEOS,
            MediaAutoDownloadManager.KEY_WIFI_DOCUMENTS
        )
        val checkedItems = booleanArrayOf(
            MediaAutoDownloadManager.isWifiEnabled(this, keys[0]),
            MediaAutoDownloadManager.isWifiEnabled(this, keys[1]),
            MediaAutoDownloadManager.isWifiEnabled(this, keys[2]),
            MediaAutoDownloadManager.isWifiEnabled(this, keys[3])
        )

        AlertDialog.Builder(this)
            .setTitle("When connected on Wi-Fi")
            .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                for (i in keys.indices) {
                    MediaAutoDownloadManager.setWifiEnabled(this, keys[i], checkedItems[i])
                }
                updateMediaAutoDownloadSummaries()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
