package com.example.gochat.ui.contacts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.R
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.model.SyncedContact
import com.example.gochat.databinding.ActivitySelectContactBinding
import com.example.gochat.databinding.DialogNewChatByPinBinding
import com.example.gochat.ui.chat.ChatRoomActivity
import kotlinx.coroutines.launch

class SelectContactActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySelectContactBinding
    private val viewModel: SelectContactViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.updatePermissionStatus(isGranted)
        if (!isGranted) {
            Toast.makeText(this, "Contacts permission allows GoChat to find your friends", Toast.LENGTH_LONG).show()
        }
    }

    private lateinit var adapter: SelectContactAdapter

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, SelectContactActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelectContactBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupSearch()
        setupListeners()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnSearch.setOnClickListener {
            binding.layoutSearchBar.visibility = View.VISIBLE
            binding.etSearchQuery.requestFocus()
        }

        binding.btnRefresh.setOnClickListener {
            viewModel.checkPermissionAndSync(force = true)
        }
    }

    private fun setupRecyclerView() {
        adapter = SelectContactAdapter(
            onActionClicked = { actionId ->
                when (actionId) {
                    "action_group" -> {
                        Toast.makeText(this, "Group creation coming soon!", Toast.LENGTH_SHORT).show()
                    }
                    "action_pin" -> {
                        showChatByPinDialog()
                    }
                }
            },
            onContactClicked = { contact ->
                openChatWithContact(contact)
            },
            onInviteClicked = { contact ->
                inviteContact(contact)
            }
        )

        binding.rvContacts.layoutManager = LinearLayoutManager(this)
        binding.rvContacts.adapter = adapter
    }

    private fun setupSearch() {
        binding.btnCloseSearch.setOnClickListener {
            binding.etSearchQuery.setText("")
            viewModel.setSearchQuery("")
            binding.layoutSearchBar.visibility = View.GONE
        }

        binding.etSearchQuery.doAfterTextChanged { text ->
            viewModel.setSearchQuery(text?.toString().orEmpty())
        }
    }

    private fun setupListeners() {
        binding.btnGrantPermission.setOnClickListener {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.checkPermissionAndSync(force = true)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.registeredCount.collect { count ->
                        binding.toolbar.subtitle = if (count > 0) {
                            "$count contacts on GoChat"
                        } else {
                            "Select contact"
                        }
                    }
                }

                launch {
                    viewModel.hasPermission.collect { granted ->
                        binding.cardPermissionNotice.visibility = if (!granted) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.isLoading.collect { loading ->
                        binding.swipeRefresh.isRefreshing = loading
                        binding.progressBar.visibility = if (loading && adapter.currentList.isEmpty()) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.uiItems.collect { items ->
                        adapter.submitList(items)

                        val hasContacts = items.any { it is ContactListItem.Contact }
                        val isSearching = viewModel.searchQuery.value.isNotEmpty()

                        if (!hasContacts && isSearching) {
                            binding.layoutEmptyState.visibility = View.VISIBLE
                            binding.tvEmptyTitle.text = "No contacts match"
                            binding.tvEmptySubtitle.text = "No contacts found matching \"${viewModel.searchQuery.value}\""
                        } else if (items.isEmpty()) {
                            binding.layoutEmptyState.visibility = View.VISIBLE
                            binding.tvEmptyTitle.text = "No contacts yet"
                            binding.tvEmptySubtitle.text = "Invite your friends to GoChat or start a chat using their 6-character PIN."
                        } else {
                            binding.layoutEmptyState.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun openChatWithContact(contact: SyncedContact) {
        binding.progressBar.visibility = View.VISIBLE
        viewModel.openOrCreateChat(contact) { conv ->
            binding.progressBar.visibility = View.GONE
            if (conv != null) {
                openChatRoom(conv)
                finish()
            } else {
                Toast.makeText(this, "Failed to start chat with ${contact.displayName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openChatRoom(conversation: Conversation) {
        val intent = Intent(this, ChatRoomActivity::class.java).apply {
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_ID, conversation.id)
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, conversation.title)
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_AVATAR, conversation.avatarUrl)
        }
        startActivity(intent)
    }

    private fun inviteContact(contact: SyncedContact) {
        try {
            val phone = contact.phone.ifBlank { "" }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("sms:$phone")
                putExtra("sms_body", "Hey! Let's chat on GoChat. It's fast, private, and secure. Download it at: https://gochat.im")
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open SMS application", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showChatByPinDialog() {
        val dialogBinding = DialogNewChatByPinBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.btnCancelPin.setOnClickListener { dialog.dismiss() }

        dialogBinding.btnConnectPin.setOnClickListener {
            val pin = dialogBinding.etPinInput.text?.toString()?.trim()?.uppercase().orEmpty()
            if (pin.length < 4) {
                dialogBinding.tvPinError.visibility = View.VISIBLE
                dialogBinding.tvPinError.text = "Please enter a valid 6-character PIN"
                return@setOnClickListener
            }

            dialogBinding.tvPinError.visibility = View.GONE
            dialogBinding.btnConnectPin.isEnabled = false

            viewModel.allContacts.value.firstOrNull { it.pin.equals(pin, ignoreCase = true) }?.let { matched ->
                dialog.dismiss()
                openChatWithContact(matched)
                return@setOnClickListener
            }

            // Otherwise search via backend
            dialog.dismiss()
            Toast.makeText(this, "Connecting to PIN: $pin…", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }
}
