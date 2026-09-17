package com.example.gochat.ui.settings

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.core.sound.ChatSoundManager
import com.example.gochat.core.theme.ThemeManager
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.databinding.BottomSheetPickAvatarBinding
import com.example.gochat.databinding.DialogEditProfileBinding
import com.example.gochat.databinding.FragmentSettingsBinding
import com.example.gochat.ui.auth.LoginActivity
import com.example.gochat.ui.backup.ChatBackupActivity
import com.example.gochat.ui.devices.LinkedDevicesActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()
    
    @Inject
    lateinit var soundManager: ChatSoundManager
    
    @Inject
    lateinit var authRepository: AuthRepository

    private var cameraTempPhotoUri: Uri? = null

    // Permission launcher for camera
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCameraCapture()
        } else {
            Toast.makeText(requireContext(), getString(R.string.error_camera_permission), Toast.LENGTH_SHORT).show()
        }
    }

    // Camera capture launcher
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraTempPhotoUri?.let { uri ->
                viewModel.uploadAndSetAvatar(uri)
            }
        }
    }

    // Gallery picker launcher
    private val pickGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.uploadAndSetAvatar(it) }
    }

    private val pickSentSoundLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            soundManager.setSentSound(uri)
            Toast.makeText(requireContext(), getString(R.string.toast_outgoing_sound_updated), Toast.LENGTH_SHORT).show()
        }
    }

    private val pickReceivedSoundLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            soundManager.setReceivedSound(uri)
            Toast.makeText(requireContext(), getString(R.string.toast_incoming_sound_updated), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupClickListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadProfile()
    }

    private fun setupClickListeners() {
        with(binding) {
            // Profile photo pick
            layoutAvatarContainer.setOnClickListener {
                if (!viewModel.isUploadingAvatar.value) {
                    showPickAvatarBottomSheet()
                }
            }

            // Edit profile dialog
            btnEditProfile.setOnClickListener {
                showEditProfileDialog()
            }

            tileProfile.setOnClickListener {
                showEditProfileDialog()
            }

            // PIN copy
            tvSettingsUserPin.setOnClickListener {
                val pin = viewModel.pin.value
                if (pin.isNotBlank() && pin != "N/A") {
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.label_gochat_pin), pin))
                    Toast.makeText(requireContext(), getString(R.string.toast_pin_copied, pin), Toast.LENGTH_SHORT).show()
                }
            }

            // Linked Devices
            tileLinkedDevices.setOnClickListener {
                startActivity(Intent(requireContext(), LinkedDevicesActivity::class.java))
            }

            // Chat Backup
            tileChatBackup.setOnClickListener {
                startActivity(Intent(requireContext(), ChatBackupActivity::class.java))
            }

            // Theme & Dark Mode
            val currentMode = ThemeManager.getThemeMode(requireContext())
            tvThemeSubtitle.text = ThemeManager.getThemeTitle(currentMode)
            tileTheme.setOnClickListener {
                showThemeDialog()
            }

            // Privacy Settings
            tilePrivacy.setOnClickListener {
                startActivity(Intent(requireContext(), PrivacySettingsActivity::class.java))
            }

            // Notifications
            tileNotifications.setOnClickListener {
                showNotificationSettingsDialog()
            }

            // Storage
            tileStorage.setOnClickListener {
                startActivity(Intent(requireContext(), com.example.gochat.ui.storage.StorageManagementActivity::class.java))
            }

            // Logout
            tileLogout.setOnClickListener {
                showLogoutConfirmation()
            }
        }
    }

    private fun showStorageDialog() {
        val options = arrayOf(
            "Clear image cache",
            "Clear voice note cache",
            "Network usage info"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("Data and Storage")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        MediaImageHelper.clearImageCache(requireContext())
                        Toast.makeText(requireContext(), "Image cache cleared", Toast.LENGTH_SHORT).show()
                    }
                    1 -> Toast.makeText(requireContext(), "Voice cache cleared", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(requireContext(), "Network usage: 12.4 MB", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton(getString(R.string.btn_close), null)
            .show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Display Name
                launch {
                    viewModel.displayName.collect { name ->
                        binding.tvSettingsUserName.text = name.ifBlank { getString(R.string.placeholder_user_name) }
                    }
                }

                // Status Text / Bio
                launch {
                    viewModel.statusText.collect { status ->
                        binding.tvSettingsStatus.text = status.ifBlank { getString(R.string.default_status_text) }
                    }
                }

                // PIN
                launch {
                    viewModel.pin.collect { pin ->
                        binding.tvSettingsUserPin.text = getString(R.string.generated_pin_prefix, pin.ifBlank { "N/A" })
                    }
                }

                // Phone
                launch {
                    viewModel.phone.collect { phone ->
                        binding.tvSettingsPhone.text = phone.ifBlank { getString(R.string.placeholder_phone_connected) }
                    }
                }

                // Avatar URL
                launch {
                    viewModel.avatarUrl.collect { avatar ->
                        MediaImageHelper.loadSafeImage(
                            imageView = binding.ivSettingsAvatar,
                            url = avatar,
                            isCircle = true,
                            placeholderRes = R.drawable.ic_account,
                            errorRes = R.drawable.ic_account
                        )
                    }
                }

                // Avatar Uploading state
                launch {
                    viewModel.isUploadingAvatar.collect { isUploading ->
                        binding.layoutAvatarLoading.visibility = if (isUploading) View.VISIBLE else View.GONE
                        binding.ivCameraBadge.visibility = if (isUploading) View.GONE else View.VISIBLE
                    }
                }

                // Toast/Event Messages
                launch {
                    viewModel.eventMessage.collect { message ->
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showPickAvatarBottomSheet() {
        val sheet = BottomSheetDialog(requireContext())
        val sheetBinding = BottomSheetPickAvatarBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        sheetBinding.btnOptionCamera.setOnClickListener {
            sheet.dismiss()
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCameraCapture()
            } else {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        sheetBinding.btnOptionGallery.setOnClickListener {
            sheet.dismiss()
            pickGalleryLauncher.launch("image/*")
        }

        sheet.show()
    }

    private fun launchCameraCapture() {
        try {
            val imageDir = File(requireContext().cacheDir, "images").apply {
                if (!exists()) mkdirs()
            }
            val photoFile = File(imageDir, "profile_avatar_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().applicationContext.packageName}.fileprovider",
                photoFile
            )
            cameraTempPhotoUri = uri
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.error_camera_init_with_msg, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditProfileDialog() {
        val dialogBinding = DialogEditProfileBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Pre-fill current values
        dialogBinding.etEditDisplayName.setText(viewModel.displayName.value)
        dialogBinding.etEditStatusText.setText(viewModel.statusText.value)

        // Quick presets
        val statusInput = dialogBinding.etEditStatusText
        dialogBinding.chipAvailable.setOnClickListener { statusInput.setText(getString(R.string.status_available)) }
        dialogBinding.chipBusy.setOnClickListener { statusInput.setText(getString(R.string.status_busy)) }
        dialogBinding.chipAtWork.setOnClickListener { statusInput.setText(getString(R.string.status_at_work)) }
        dialogBinding.chipInMeeting.setOnClickListener { statusInput.setText(getString(R.string.status_in_meeting)) }
        dialogBinding.chipDefaultStatus.setOnClickListener { statusInput.setText(getString(R.string.default_status_text)) }

        dialogBinding.btnCancelEdit.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSaveEdit.setOnClickListener {
            val newName = dialogBinding.etEditDisplayName.text?.toString().orEmpty().trim()
            val newStatus = dialogBinding.etEditStatusText.text?.toString().orEmpty().trim()

            if (newName.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.error_empty_display_name), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialogBinding.btnSaveEdit.isEnabled = false
            dialogBinding.pbSavingProfile.visibility = View.VISIBLE

            viewModel.updateProfile(newName, newStatus)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showThemeDialog() {
        val currentMode = ThemeManager.getThemeMode(requireContext())
        val options = arrayOf(
            getString(R.string.theme_system_default),
            getString(R.string.theme_dark_mode),
            getString(R.string.theme_light_mode)
        )
        val selectedIndex = when (currentMode) {
            ThemeManager.THEME_DARK -> 1
            ThemeManager.THEME_LIGHT -> 2
            else -> 0
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_theme_title))
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                val newMode = when (which) {
                    1 -> ThemeManager.THEME_DARK
                    2 -> ThemeManager.THEME_LIGHT
                    else -> ThemeManager.THEME_SYSTEM
                }
                ThemeManager.setThemeMode(requireContext(), newMode)
                binding.tvThemeSubtitle.text = ThemeManager.getThemeTitle(newMode)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showNotificationSettingsDialog() {
        val soundStatus = if (soundManager.isInChatSoundsEnabled()) getString(R.string.status_on) else getString(R.string.status_off)
        val options = arrayOf(
            getString(R.string.settings_in_chat_sounds_format, soundStatus),
            getString(R.string.settings_outgoing_tone),
            getString(R.string.settings_incoming_tone)
        )

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_notifications_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val current = soundManager.isInChatSoundsEnabled()
                        soundManager.setInChatSoundsEnabled(!current)
                        val status = if (!current) getString(R.string.status_enabled) else getString(R.string.status_disabled)
                        Toast.makeText(requireContext(), getString(R.string.toast_in_chat_sounds_status, status), Toast.LENGTH_SHORT).show()
                    }
                    1 -> launchRingtonePicker(pickSentSoundLauncher, soundManager.getSentSound())
                    2 -> launchRingtonePicker(pickReceivedSoundLauncher, soundManager.getReceivedSound())
                }
            }
            .setPositiveButton(getString(R.string.btn_close), null)
            .show()
    }

    private fun launchRingtonePicker(launcher: ActivityResultLauncher<Intent>, currentUri: Uri?) {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.dialog_select_sound_title))
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
        }
        launcher.launch(intent)
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.btn_logout))
            .setMessage(getString(R.string.dialog_logout_confirmation_desc)) // need to add
            .setPositiveButton(getString(R.string.btn_logout)) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    authRepository.logout()
                    val intent = Intent(requireContext(), LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
