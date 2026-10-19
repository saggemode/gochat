package com.example.gochat.ui.backup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.MainActivity
import com.example.gochat.R
import com.example.gochat.core.backup.BackupMetadata
import com.example.gochat.core.backup.ChatBackupManager
import com.example.gochat.core.backup.GoogleDriveBackupInfo
import com.example.gochat.core.backup.GoogleDriveBackupManager
import com.example.gochat.core.backup.InvalidPasswordException
import com.example.gochat.core.ui.applyEdgeToEdge
import com.example.gochat.core.ui.applyStatusBarInset
import com.example.gochat.core.ui.applySystemBarInsets
import com.example.gochat.data.api.TokenManager
import com.example.gochat.databinding.ActivityRestoreBackupBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class RestoreBackupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRestoreBackupBinding

    @Inject lateinit var backupManager: ChatBackupManager
    @Inject lateinit var driveManager: GoogleDriveBackupManager
    @Inject lateinit var tokenManager: TokenManager

    private var selectedLocalBackup: File? = null
    private var selectedCloudBackup: GoogleDriveBackupInfo? = null
    private var isCloudSource = false

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, RestoreBackupActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityRestoreBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarRestore.applyStatusBarInset()
        binding.root.applySystemBarInsets(applyTop = false, applyBottom = true)

        setupListeners()
        scanForAvailableBackups()
    }

    private fun setupListeners() {
        binding.btnRestoreNow.setOnClickListener {
            if (selectedLocalBackup == null && selectedCloudBackup == null) {
                // If no backup was found, this button acts as "Continue to GoChat"
                finishAndGoToMain()
                return@setOnClickListener
            }

            val password = binding.etRestorePassword.text?.toString().orEmpty()
            if (password.length < 4) {
                binding.tilRestorePassword.error = "Password must be at least 4 characters"
                return@setOnClickListener
            }
            binding.tilRestorePassword.error = null

            performRestore(password)
        }

        binding.btnSkipRestore.setOnClickListener {
            showSkipConfirmationDialog()
        }
    }

    private fun scanForAvailableBackups() {
        binding.pbScanningBackup.visibility = View.VISIBLE
        binding.tvBackupFoundTitle.text = "Searching for backups..."
        binding.tvBackupSourceDesc.text = "Checking Google Drive and local storage..."
        binding.layoutBackupStats.visibility = View.GONE
        binding.tilRestorePassword.visibility = View.GONE
        binding.tvPasswordDisclaimer.visibility = View.GONE
        binding.btnRestoreNow.isEnabled = false

        lifecycleScope.launch {
            // 1. Check local backup files first
            val localFiles = backupManager.getLocalBackupFiles()
            val latestLocal = localFiles.firstOrNull()

            // 2. Check Google Drive if signed in
            var cloudBackup: GoogleDriveBackupInfo? = null
            val account = driveManager.getSignedInAccount()
            if (account != null) {
                val driveResult = driveManager.queryLatestBackup(account)
                cloudBackup = driveResult.getOrNull()
            }

            binding.pbScanningBackup.visibility = View.GONE

            if (cloudBackup != null) {
                // Google Drive backup found
                selectedCloudBackup = cloudBackup
                isCloudSource = true
                displayFoundBackup(
                    isCloud = true,
                    title = "Google Drive Backup",
                    source = account?.email ?: "Google Drive",
                    timestamp = cloudBackup.modifiedTime,
                    sizeBytes = cloudBackup.sizeBytes,
                    chatsCount = null
                )
            } else if (latestLocal != null) {
                // Local backup found
                selectedLocalBackup = latestLocal
                isCloudSource = false
                val cachedMeta = backupManager.getLastBackupInfo()
                displayFoundBackup(
                    isCloud = false,
                    title = "Local Encrypted Backup",
                    source = "Device Storage",
                    timestamp = latestLocal.lastModified(),
                    sizeBytes = latestLocal.length(),
                    chatsCount = cachedMeta?.conversationCount
                )
            } else {
                // No backup found
                displayNoBackupFound()
            }
        }
    }

    private fun displayFoundBackup(
        isCloud: Boolean,
        title: String,
        source: String,
        timestamp: Long,
        sizeBytes: Long,
        chatsCount: Int?
    ) {
        binding.ivBackupSourceIcon.setImageResource(
            if (isCloud) R.drawable.ic_cloud_backup else R.drawable.ic_devices
        )
        binding.tvBackupFoundTitle.text = title
        binding.tvBackupSourceDesc.text = source

        val sdf = SimpleDateFormat("MMM dd, yyyy · hh:mm a", Locale.getDefault())
        binding.tvBackupDate.text = sdf.format(Date(timestamp))
        binding.tvBackupSize.text = formatFileSize(sizeBytes)
        binding.tvBackupChatsCount.text = if (chatsCount != null && chatsCount > 0) "$chatsCount chats" else "All chats"

        binding.layoutBackupStats.visibility = View.VISIBLE
        binding.tilRestorePassword.visibility = View.VISIBLE
        binding.tvPasswordDisclaimer.visibility = View.VISIBLE
        binding.btnRestoreNow.text = "Restore Chats"
        binding.btnRestoreNow.isEnabled = true
        binding.btnSkipRestore.visibility = View.VISIBLE
    }

    private fun displayNoBackupFound() {
        binding.ivBackupSourceIcon.setImageResource(R.drawable.ic_close)
        binding.tvBackupFoundTitle.text = "No Backup Found"
        binding.tvBackupSourceDesc.text = "No previous chat backup was found on this device or cloud."

        binding.layoutBackupStats.visibility = View.GONE
        binding.tilRestorePassword.visibility = View.GONE
        binding.tvPasswordDisclaimer.visibility = View.GONE

        binding.btnRestoreNow.text = "Continue to GoChat"
        binding.btnRestoreNow.isEnabled = true
        binding.btnSkipRestore.visibility = View.GONE
    }

    private fun performRestore(password: String) {
        binding.layoutRestoreProgress.visibility = View.VISIBLE
        binding.progressRestore.progress = 5
        binding.tvRestoreStatus.text = "Starting restore..."
        binding.btnRestoreNow.isEnabled = false
        binding.btnSkipRestore.isEnabled = false
        binding.tilRestorePassword.isEnabled = false

        lifecycleScope.launch {
            try {
                val backupFileToRestore: File
                if (isCloudSource && selectedCloudBackup != null) {
                    binding.tvRestoreStatus.text = "Downloading backup from Google Drive..."
                    binding.progressRestore.progress = 20

                    val account = driveManager.getSignedInAccount()
                    if (account == null) {
                        Toast.makeText(this@RestoreBackupActivity, "Google account disconnected", Toast.LENGTH_SHORT).show()
                        resetRestoreUI()
                        return@launch
                    }

                    val tempDest = File(cacheDir, "cloud_restore_${System.currentTimeMillis()}.gcbackup")
                    val downloadRes = driveManager.downloadBackupFile(account, selectedCloudBackup!!.fileId, tempDest) { prog ->
                        binding.progressRestore.progress = (20 + (prog * 30)).toInt()
                    }

                    if (downloadRes.isFailure) {
                        Toast.makeText(this@RestoreBackupActivity, "Failed to download backup: ${downloadRes.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        resetRestoreUI()
                        return@launch
                    }
                    backupFileToRestore = downloadRes.getOrThrow()
                } else {
                    backupFileToRestore = selectedLocalBackup ?: run {
                        Toast.makeText(this@RestoreBackupActivity, "Backup file missing", Toast.LENGTH_SHORT).show()
                        resetRestoreUI()
                        return@launch
                    }
                }

                binding.tvRestoreStatus.text = "Decrypting messages and media..."
                binding.progressRestore.progress = 60

                val restoreResult = backupManager.restoreEncryptedBackup(backupFileToRestore, password) { prog, status ->
                    binding.progressRestore.progress = (60 + (prog * 40)).toInt().coerceAtMost(100)
                    binding.tvRestoreStatus.text = status
                }

                restoreResult.onSuccess { meta ->
                    Toast.makeText(
                        this@RestoreBackupActivity,
                        "Restored ${meta.conversationCount} chats and ${meta.messageCount} messages!",
                        Toast.LENGTH_LONG
                    ).show()
                    finishAndGoToMain()
                }.onFailure { err ->
                    resetRestoreUI()
                    if (err is InvalidPasswordException) {
                        binding.tilRestorePassword.error = "Incorrect backup password. Please verify and retry."
                    } else {
                        Toast.makeText(this@RestoreBackupActivity, "Restore failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                resetRestoreUI()
                Toast.makeText(this@RestoreBackupActivity, "Unexpected error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun resetRestoreUI() {
        binding.layoutRestoreProgress.visibility = View.GONE
        binding.btnRestoreNow.isEnabled = true
        binding.btnSkipRestore.isEnabled = true
        binding.tilRestorePassword.isEnabled = true
    }

    private fun showSkipConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Skip Restore?")
            .setMessage("If you skip now, you will not be able to restore your previous chat history later.")
            .setPositiveButton("Skip Anyway") { _, _ ->
                finishAndGoToMain()
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun finishAndGoToMain() {
        tokenManager.hasCompletedMigrationCheck = true
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1024 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }
}
