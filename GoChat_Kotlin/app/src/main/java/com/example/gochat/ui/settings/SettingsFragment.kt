package com.example.gochat.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import com.example.gochat.core.theme.ThemeManager
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.databinding.BottomSheetPickAvatarBinding
import com.example.gochat.databinding.DialogEditProfileBinding
import com.example.gochat.databinding.FragmentSettingsBinding
import com.example.gochat.ui.auth.LoginActivity
import com.example.gochat.ui.backup.ChatBackupActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    private var cameraTempPhotoUri: Uri? = null

    // Permission launcher for camera
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCameraCapture()
        } else {
            Toast.makeText(requireContext(), "Camera permission required to take photos", Toast.LENGTH_SHORT).show()
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
                    clipboard.setPrimaryClip(ClipData.newPlainText("GoChat PIN", pin))
                    Toast.makeText(requireContext(), "Copied PIN $pin to clipboard!", Toast.LENGTH_SHORT).show()
                }
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
                AlertDialog.Builder(requireContext())
                    .setTitle("Notification Preferences")
                    .setMultiChoiceItems(
                        arrayOf("Message Notifications", "Group Notifications", "Call Vibration", "In-Chat Sounds"),
                        booleanArrayOf(true, true, true, true)
                    ) { _, _, _ -> }
                    .setPositiveButton("Save", null)
                    .show()
            }

            // Logout
            tileLogout.setOnClickListener {
                showLogoutConfirmation()
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Display Name
                launch {
                    viewModel.displayName.collect { name ->
                        binding.tvSettingsUserName.text = name.ifBlank { "GoChat User" }
                    }
                }

                // Status Text / Bio
                launch {
                    viewModel.statusText.collect { status ->
                        binding.tvSettingsStatus.text = status.ifBlank { "Hey there! I am using GoChat." }
                    }
                }

                // PIN
                launch {
                    viewModel.pin.collect { pin ->
                        binding.tvSettingsUserPin.text = "GoChat PIN: ${pin.ifBlank { "N/A" }}"
                    }
                }

                // Phone
                launch {
                    viewModel.phone.collect { phone ->
                        binding.tvSettingsPhone.text = phone.ifBlank { "Phone number connected" }
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
            Toast.makeText(requireContext(), "Unable to initialize camera: ${e.message}", Toast.LENGTH_SHORT).show()
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
        dialogBinding.chipAvailable.setOnClickListener { statusInput.setText("Available") }
        dialogBinding.chipBusy.setOnClickListener { statusInput.setText("Busy") }
        dialogBinding.chipAtWork.setOnClickListener { statusInput.setText("At work") }
        dialogBinding.chipInMeeting.setOnClickListener { statusInput.setText("In a meeting") }
        dialogBinding.chipDefaultStatus.setOnClickListener { statusInput.setText("Hey there! I am using GoChat.") }

        dialogBinding.btnCancelEdit.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSaveEdit.setOnClickListener {
            val newName = dialogBinding.etEditDisplayName.text?.toString().orEmpty().trim()
            val newStatus = dialogBinding.etEditStatusText.text?.toString().orEmpty().trim()

            if (newName.isBlank()) {
                Toast.makeText(requireContext(), "Display name cannot be empty", Toast.LENGTH_SHORT).show()
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
            "System Default (Auto)",
            "Dark Mode (Emerald)",
            "Light Mode (Clean)"
        )
        val selectedIndex = when (currentMode) {
            ThemeManager.THEME_DARK -> 1
            ThemeManager.THEME_LIGHT -> 2
            else -> 0
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Choose Theme")
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
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Log Out")
            .setMessage("Are you sure you want to log out of GoChat?")
            .setPositiveButton("Log Out") { _, _ ->
                val authRepository = AuthRepository(requireContext().applicationContext)
                viewLifecycleOwner.lifecycleScope.launch {
                    authRepository.logout()
                    val intent = Intent(requireContext(), LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
