package com.example.gochat.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import coil.load
import coil.transform.CircleCropTransformation
import com.example.gochat.R
import com.example.gochat.data.api.TokenManager
import com.example.gochat.databinding.FragmentSettingsBinding
import com.example.gochat.ui.auth.LoginActivity
import com.example.gochat.ui.backup.ChatBackupActivity

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
                Toast.makeText(requireContext(), "Privacy settings", Toast.LENGTH_SHORT).show()
            }

            tileNotifications.setOnClickListener {
                Toast.makeText(requireContext(), "Notification tones & preferences", Toast.LENGTH_SHORT).show()
            }

            tileLogout.setOnClickListener {
                showLogoutConfirmation(tokenManager)
            }
        }
    }

    private fun showLogoutConfirmation(tokenManager: TokenManager) {
        AlertDialog.Builder(requireContext())
            .setTitle("Log Out")
            .setMessage("Are you sure you want to log out of GoChat?")
            .setPositiveButton("Log Out") { _, _ ->
                tokenManager.clearAll()
                val intent = Intent(requireContext(), LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
