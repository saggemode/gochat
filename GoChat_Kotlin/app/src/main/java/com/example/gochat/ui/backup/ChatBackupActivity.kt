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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ChatBackupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBackupBinding
    private val viewModel: ChatBackupViewModel by viewModels()
    
    @Inject
    lateinit var driveManager: GoogleDriveBackupManager

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                viewModel.setGoogleAccount(account.email)
                Toast.makeText(this, getString(R.string.google_account_connected_toast, account.email), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.google_signin_failed_toast, e.localizedMessage), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                            binding.tvMetaChats.text = meta.conversationCount.toString()
                            binding.tvMetaMessages.text = meta.messageCount.toString()
                            binding.tvMetaMedia.text = getString(R.string.meta_media_format, meta.mediaCount)

                            binding.dividerMeta.visibility = View.VISIBLE
                            binding.layoutMetaDetails.visibility = View.VISIBLE
                        } else {
                            binding.tvLastBackupDate.text = getString(R.string.never_backed_up)
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
                        binding.tvGoogleAccountEmail.text = email ?: getString(R.string.tap_to_connect_google)
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
            dialogBinding.tvBackupPasswordTitle.text = getString(R.string.decrypt_backup_title)
            dialogBinding.tvBackupPasswordDesc.text = getString(R.string.decrypt_backup_desc)
            dialogBinding.btnConfirmBackupPassword.text = getString(R.string.btn_restore)
        } else {
            dialogBinding.tvBackupPasswordTitle.text = getString(R.string.set_backup_password_title)
            dialogBinding.tvBackupPasswordDesc.text = getString(R.string.set_backup_password_desc)
            dialogBinding.btnConfirmBackupPassword.text = getString(R.string.btn_back_up)
        }

        dialogBinding.btnCancelBackupPassword.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnConfirmBackupPassword.setOnClickListener {
            val pass = dialogBinding.etBackupPassword.text?.toString().orEmpty()
            if (pass.length < 4) {
                dialogBinding.tilBackupPassword.error = getString(R.string.error_password_length)
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
            Toast.makeText(this, getString(R.string.error_no_local_backup), Toast.LENGTH_LONG).show()
            return
        }

        val names = localFiles.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_local_backup_title))
            .setItems(names) { _, which ->
                val selectedFile = localFiles[which]
                showPasswordDialog(isRestore = true) { password ->
                    viewModel.restoreFromLocal(selectedFile, password) { success, msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showFrequencyDialog() {
        val options = arrayOf(
            getString(R.string.freq_daily),
            getString(R.string.freq_weekly),
            getString(R.string.freq_monthly),
            getString(R.string.freq_off)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.auto_backup_dialog_title))
            .setItems(options) { _, which ->
                viewModel.setAutoBackupFrequency(options[which])
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showManualGoogleAccountDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. user@gmail.com"
            setTextColor(getColor(R.color.gochat_text_primary))
            setHintTextColor(getColor(R.color.gochat_text_muted))
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.connect_google_account_title))
            .setMessage(getString(R.string.connect_google_account_desc))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_connect)) { _, _ ->
                val email = input.text.toString().trim()
                if (email.contains("@")) {
                    viewModel.setGoogleAccount(email)
                    Toast.makeText(this, getString(R.string.google_account_connected_toast, email), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }
}
