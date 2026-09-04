package com.example.gochat.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.example.gochat.R
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.databinding.FragmentSettingsBinding
import com.example.gochat.ui.auth.LoginActivity
import com.example.gochat.ui.backup.ChatBackupActivity
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

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
        val tokenManager = TokenManager.getInstance(requireContext())

        with(binding) {
            val name = tokenManager.userDisplayName ?: "GoChat User"
            val pin = tokenManager.userPin ?: "N/A"
            val phone = tokenManager.userPhone ?: ""
            val avatar = tokenManager.userAvatarUrl.orEmpty()

            tvSettingsUserName.text = name
            tvSettingsUserPin.text = "GoChat PIN: $pin"
            tvSettingsPhone.text = phone.ifBlank { "Phone number connected" }

            if (avatar.isNotBlank()) {
                ivSettingsAvatar.load(avatar) {
                    crossfade(true)
                    placeholder(R.drawable.ic_account)
                    error(R.drawable.ic_account)
                    transformations(CircleCropTransformation())
                }
            } else {
                ivSettingsAvatar.setImageResource(R.drawable.ic_account)
            }

            tileChatBackup.setOnClickListener {
                startActivity(Intent(requireContext(), ChatBackupActivity::class.java))
            }

            tilePrivacy.setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Privacy Settings")
                    .setItems(arrayOf("Last Seen & Online: Everyone", "Read Receipts: Enabled", "Disappearing Messages: Off", "Blocked Contacts: None")) { _, _ -> }
                    .setPositiveButton("Done", null)
                    .show()
            }

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

            tileLogout.setOnClickListener {
                showLogoutConfirmation()
            }
        }
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
