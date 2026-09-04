package com.example.gochat.ui.backup

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.gochat.R
import com.example.gochat.core.backup.GoogleDriveBackupManager
import com.example.gochat.databinding.ActivityChatBackupBinding
import com.example.gochat.databinding.DialogBackupPasswordBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatBackupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBackupBinding
    private val viewModel: ChatBackupViewModel by viewModels()
    private lateinit var driveManager: GoogleDriveBackupManager

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                viewModel.setGoogleAccount(account.email)
                Toast.makeText(this, "Connected Google Account: ${account.email}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Google Sign-In failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        driveManager = GoogleDriveBackupManager(this)

        setupToolbar()
        setupActions()
        observeState()
    }

    private fun setupToolbar() {
        binding.toolbarBackup.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupActions() {
        binding.btnBackupNow.setOnClickListener {
            showPasswordDialog(isRestore = false) { password ->
                viewModel.createBackup(password, uploadToDrive = true) { success, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnRestoreGoogleDrive.setOnClickListener {
            showPasswordDialog(isRestore = true) { password ->
                viewModel.restoreFromGoogleDrive(password) { success, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnRestoreLocal.setOnClickListener {
            showLocalBackupsPicker()
        }

        binding.tileGoogleAccount.setOnClickListener {
            try {
                val intent = driveManager.getSignInIntent()
                googleSignInLauncher.launch(intent)
            } catch (e: Exception) {
                showManualGoogleAccountDialog()
            }
        }

        binding.tileAutoBackup.setOnClickListener {
            showFrequencyDialog()
        }

        binding.switchIncludeMedia.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setIncludeMedia(isChecked)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.lastBackup.collect { meta ->
                        if (meta != null) {
                            val sdf = SimpleDateFormat("MMM dd, yyyy · hh:mm a", Locale.getDefault())
                            val dateStr = sdf.format(Date(meta.createdAt))
                            val prefix = if (meta.isCloudBackup) "Google Drive · " else "Local · "
                            binding.tvLastBackupDate.text = "$prefix$dateStr"

                            binding.tvMetaSize.text = meta.formattedSize
                            binding.tvMetaChats.text = "${meta.conversationCount}"
                            binding.tvMetaMessages.text = "${meta.messageCount}"
                            binding.tvMetaMedia.text = "${meta.mediaCount} files"

                            binding.dividerMeta.visibility = View.VISIBLE
                            binding.layoutMetaDetails.visibility = View.VISIBLE
                        } else {
                            binding.tvLastBackupDate.text = "Never backed up"
                            binding.dividerMeta.visibility = View.GONE
                            binding.layoutMetaDetails.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.isOperating.collect { operating ->
                        binding.layoutBackupProgress.visibility = if (operating) View.VISIBLE else View.GONE
                        binding.btnBackupNow.isEnabled = !operating
                        binding.btnRestoreGoogleDrive.isEnabled = !operating
                        binding.btnRestoreLocal.isEnabled = !operating
                    }
                }

                launch {
                    viewModel.progress.collect { prog ->
                        val percent = (prog * 100).toInt().coerceIn(0, 100)
                        binding.pbBackupProgress.progress = percent
                        binding.tvBackupProgressPercent.text = "$percent%"
                    }
                }

                launch {
                    viewModel.statusMessage.collect { status ->
                        binding.tvBackupProgressStatus.text = status
                    }
                }

                launch {
                    viewModel.googleAccountEmail.collect { email ->
                        binding.tvGoogleAccountEmail.text = email ?: "Tap to connect Google Drive"
                    }
                }

                launch {
                    viewModel.autoBackupFrequency.collect { freq ->
                        binding.tvAutoBackupFrequency.text = freq
                    }
                }

                launch {
                    viewModel.includeMedia.collect { inc ->
                        binding.switchIncludeMedia.isChecked = inc
                    }
                }
            }
        }
    }

    private fun showPasswordDialog(isRestore: Boolean, onConfirmed: (String) -> Unit) {
        val dialog = Dialog(this, R.style.Theme_GoChat)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dialogBinding = DialogBackupPasswordBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        if (isRestore) {
            dialogBinding.tvBackupPasswordTitle.text = "Decrypt Backup"
            dialogBinding.tvBackupPasswordDesc.text = "Enter the password used when creating this backup to restore chats."
            dialogBinding.btnConfirmBackupPassword.text = "Restore"
        } else {
            dialogBinding.tvBackupPasswordTitle.text = "Set Backup Password"
            dialogBinding.tvBackupPasswordDesc.text = "Enter a password to encrypt your chat backup. You will need this password to restore your chats."
            dialogBinding.btnConfirmBackupPassword.text = "Back Up"
        }

        dialogBinding.btnCancelBackupPassword.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnConfirmBackupPassword.setOnClickListener {
            val pass = dialogBinding.etBackupPassword.text?.toString().orEmpty()
            if (pass.length < 4) {
                dialogBinding.tilBackupPassword.error = "Password must be at least 4 characters"
                return@setOnClickListener
            }
            dialog.dismiss()
            onConfirmed(pass)
        }

        dialog.show()
    }

    private fun showLocalBackupsPicker() {
        val localFiles = viewModel.getLocalBackupFiles()
        if (localFiles.isEmpty()) {
            Toast.makeText(this, "No local .gcbackup file found. Create a backup first.", Toast.LENGTH_LONG).show()
            return
        }

        val names = localFiles.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Local Backup")
            .setItems(names) { _, which ->
                val selectedFile = localFiles[which]
                showPasswordDialog(isRestore = true) { password ->
                    viewModel.restoreFromLocal(selectedFile, password) { success, msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFrequencyDialog() {
        val options = arrayOf("Daily", "Weekly", "Monthly", "Off")
        AlertDialog.Builder(this)
            .setTitle("Auto-Backup to Google Drive")
            .setItems(options) { _, which ->
                viewModel.setAutoBackupFrequency(options[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualGoogleAccountDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. user@gmail.com"
            setTextColor(getColor(R.color.gochat_text_primary))
            setHintTextColor(getColor(R.color.gochat_text_muted))
        }

        AlertDialog.Builder(this)
            .setTitle("Connect Google Account")
            .setMessage("Enter the Google Drive account email for cloud backups:")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val email = input.text.toString().trim()
                if (email.contains("@")) {
                    viewModel.setGoogleAccount(email)
                    Toast.makeText(this, "Google Account connected: $email", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
