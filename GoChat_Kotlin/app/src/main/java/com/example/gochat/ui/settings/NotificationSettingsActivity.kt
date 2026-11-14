package com.example.gochat.ui.settings

import android.app.TimePickerDialog
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.gochat.core.notification.NotificationHelper
import com.example.gochat.data.api.TokenManager
import com.example.gochat.databinding.ActivityNotificationSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Global notification preferences: custom tone, previews, badge, reaction alerts,
 * and quiet hours. Per-chat priority is toggled from each chat's menu.
 */
@AndroidEntryPoint
class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationSettingsBinding

    @Inject
    lateinit var tokenManager: TokenManager

    private val tonePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        tokenManager.notificationToneUri = uri?.toString()
        NotificationHelper.applyCustomTone(this, uri?.toString())
        renderToneLabel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackNotifSettings.setOnClickListener { finish() }

        // Toggles
        binding.switchPreview.isChecked = tokenManager.notificationPreviewEnabled
        binding.switchPreview.setOnCheckedChangeListener { _, v -> tokenManager.notificationPreviewEnabled = v }

        binding.switchBadge.isChecked = tokenManager.notificationBadgeEnabled
        binding.switchBadge.setOnCheckedChangeListener { _, v -> tokenManager.notificationBadgeEnabled = v }

        binding.switchReactions.isChecked = tokenManager.reactionNotificationsEnabled
        binding.switchReactions.setOnCheckedChangeListener { _, v -> tokenManager.reactionNotificationsEnabled = v }

        binding.switchQuiet.isChecked = tokenManager.quietHoursEnabled
        binding.switchQuiet.setOnCheckedChangeListener { _, v ->
            tokenManager.quietHoursEnabled = v
            updateQuietRowsEnabled(v)
        }
        updateQuietRowsEnabled(tokenManager.quietHoursEnabled)

        // Tone picker
        binding.rowTone.setOnClickListener { openTonePicker() }
        renderToneLabel()

        // Quiet-hours time rows
        binding.rowQuietStart.setOnClickListener {
            pickTime(tokenManager.quietHoursStartMinutes) { mins ->
                tokenManager.quietHoursStartMinutes = mins
                binding.tvQuietStart.text = formatMinutes(mins)
            }
        }
        binding.rowQuietEnd.setOnClickListener {
            pickTime(tokenManager.quietHoursEndMinutes) { mins ->
                tokenManager.quietHoursEndMinutes = mins
                binding.tvQuietEnd.text = formatMinutes(mins)
            }
        }
        binding.tvQuietStart.text = formatMinutes(tokenManager.quietHoursStartMinutes)
        binding.tvQuietEnd.text = formatMinutes(tokenManager.quietHoursEndMinutes)
    }

    private fun updateQuietRowsEnabled(enabled: Boolean) {
        binding.rowQuietStart.isEnabled = enabled
        binding.rowQuietEnd.isEnabled = enabled
        binding.rowQuietStart.alpha = if (enabled) 1f else 0.4f
        binding.rowQuietEnd.alpha = if (enabled) 1f else 0.4f
    }

    private fun openTonePicker() {
        val current = tokenManager.notificationToneUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Notification tone")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        }
        try {
            tonePicker.launch(intent)
        } catch (_: Exception) {
            // Some ROMs lack a ringtone picker; ignore.
        }
    }

    private fun renderToneLabel() {
        val uriStr = tokenManager.notificationToneUri
        binding.tvToneValue.text = if (uriStr.isNullOrBlank()) {
            "Default"
        } else {
            try {
                RingtoneManager.getRingtone(this, Uri.parse(uriStr))?.getTitle(this) ?: "Custom"
            } catch (_: Exception) {
                "Custom"
            }
        }
    }

    private fun pickTime(currentMinutes: Int, onPicked: (Int) -> Unit) {
        val h = currentMinutes / 60
        val m = currentMinutes % 60
        TimePickerDialog(this, { _, hour, minute -> onPicked(hour * 60 + minute) }, h, m, true).show()
    }

    private fun formatMinutes(mins: Int): String =
        String.format(java.util.Locale.getDefault(), "%02d:%02d", mins / 60, mins % 60)
}
