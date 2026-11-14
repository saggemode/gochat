package com.example.gochat.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.databinding.ActivityPrivacyCheckupBinding
import com.example.gochat.ui.devices.LinkedDevicesActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * WhatsApp-style guided privacy checkup: the key account-protection, who-can-reach-you,
 * and information-visibility settings in one place, with quick links to the full screens.
 */
@AndroidEntryPoint
class PrivacyCheckupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacyCheckupBinding

    @Inject
    lateinit var tokenManager: TokenManager

    @Inject
    lateinit var authRepo: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyCheckupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackCheckup.setOnClickListener { finish() }

        // Toggles
        binding.switchCheckupAppLock.isChecked = tokenManager.isBiometricLockEnabled
        binding.switchCheckupAppLock.setOnCheckedChangeListener { _, v -> tokenManager.isBiometricLockEnabled = v }

        binding.switchCheckupSecurity.isChecked = tokenManager.securityNotificationsEnabled
        binding.switchCheckupSecurity.setOnCheckedChangeListener { _, v -> tokenManager.securityNotificationsEnabled = v }

        binding.switchCheckupSilence.isChecked = tokenManager.silenceUnknownCallersEnabled
        binding.switchCheckupSilence.setOnCheckedChangeListener { _, v -> tokenManager.silenceUnknownCallersEnabled = v }

        // Quick links
        binding.rowCheckupVisibility.setOnClickListener {
            startActivity(Intent(this, PrivacySettingsActivity::class.java))
        }
        binding.rowCheckupNotifications.setOnClickListener {
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }
        binding.rowCheckupDevices.setOnClickListener {
            startActivity(Intent(this, LinkedDevicesActivity::class.java))
        }
        binding.rowCheckupGroups.setOnClickListener { showGroupsDialog() }

        loadGroupsValue()
    }

    override fun onResume() {
        super.onResume()
        loadGroupsValue()
    }

    private fun loadGroupsValue() {
        lifecycleScope.launch {
            authRepo.getPrivacySettings().onSuccess { json ->
                val v = json["groups_privacy"]?.jsonPrimitive?.contentOrNull ?: "everyone"
                binding.tvCheckupGroups.text = v.replaceFirstChar { it.uppercase() }
            }
        }
    }

    private fun showGroupsDialog() {
        val options = arrayOf("Everyone", "My contacts", "Nobody")
        val values = arrayOf("everyone", "contacts", "nobody")
        AlertDialog.Builder(this)
            .setTitle("Who can add me to groups")
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    val res = authRepo.updatePrivacySettings(groups = values[which])
                    if (res.isSuccess) {
                        binding.tvCheckupGroups.text = options[which]
                    } else {
                        Toast.makeText(this@PrivacyCheckupActivity, "Failed to update", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }
}
