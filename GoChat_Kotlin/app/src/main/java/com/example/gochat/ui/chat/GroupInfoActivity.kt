package com.example.gochat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.GroupMember
import com.example.gochat.data.model.MessageType
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.databinding.ActivityGroupInfoBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class GroupInfoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_GROUP_NAME = "extra_group_name"
        const val EXTRA_GROUP_AVATAR = "extra_group_avatar"
        const val EXTRA_MEMBER_IDS = "extra_member_ids"
    }

    @Inject
    lateinit var chatRepository: ChatRepository

    private lateinit var binding: ActivityGroupInfoBinding
    private val viewModel: GroupInfoViewModel by viewModels()
    private lateinit var memberAdapter: GroupMemberAdapter
    private val convId: String by lazy { intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty() }

    private var currentName: String = ""
    private var currentAvatar: String = ""
    private var currentDesc: String = ""

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { uploadAndSetAvatar(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentName = intent.getStringExtra(EXTRA_GROUP_NAME) ?: getString(R.string.group_info_title)
        currentAvatar = intent.getStringExtra(EXTRA_GROUP_AVATAR).orEmpty()
        val memberIds = intent.getStringArrayListExtra(EXTRA_MEMBER_IDS) ?: arrayListOf()

        setupToolbar(currentName)
        setupUI()
        observeViewModel()
        setupSharedMedia(convId, currentName)

        viewModel.loadGroupData(convId, memberIds)
    }

    private fun setupToolbar(name: String) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.collapsingToolbar.title = name
        binding.tvGroupName.text = name
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupUI() {
        renderAvatar(currentAvatar)

        // Change group avatar click
        binding.btnChangeAvatar.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        binding.ivGroupAvatar.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // Edit group name click
        binding.layoutGroupName.setOnClickListener {
            showEditGroupNameDialog()
        }
        binding.ivEditGroupName.setOnClickListener {
            showEditGroupNameDialog()
        }

        // Edit group description click
        binding.layoutDescription.setOnClickListener {
            showEditDescriptionDialog()
        }
        binding.ivEditDescription.setOnClickListener {
            showEditDescriptionDialog()
        }

        // Add member button click
        binding.btnAddMember.setOnClickListener {
            showAddMemberDialog()
        }

        // Invite link button
        binding.btnInviteLink.setOnClickListener {
            viewModel.generateInviteLink(convId) { code ->
                val link = "https://gochat.link/join/$code"
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_invite_link_title))
                    .setMessage(link)
                    .setPositiveButton(getString(R.string.btn_copy)) { _, _ ->
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Group Link", link)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, getString(R.string.toast_link_copied), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(getString(R.string.btn_close), null)
                    .show()
            }
        }

        // Manage bot button
        binding.btnManageBot.setOnClickListener {
            val intent = Intent(this, BotManagementActivity::class.java).apply {
                putExtra(BotManagementActivity.EXTRA_CONVERSATION_ID, convId)
            }
            startActivity(intent)
        }

        // Export group chat button
        binding.btnExportGroupChat.setOnClickListener {
            val sheet = ExportChatBottomSheet(
                conversationId = convId,
                conversationTitle = currentName,
                chatRepository = chatRepository
            )
            sheet.show(supportFragmentManager, ExportChatBottomSheet.TAG)
        }

        binding.btnClearGroupChat.setOnClickListener {
            ClearChatHelper.showClearChatDialog(
                context = this,
                coroutineScope = lifecycleScope,
                chatRepository = chatRepository,
                conversationId = convId
            )
        }

        // Exit group button
        binding.btnExitGroup.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_exit_group_title))
                .setMessage(getString(R.string.dialog_exit_group_desc))
                .setPositiveButton(getString(R.string.exit_group)) { _, _ ->
                    viewModel.removeMember(convId, viewModel.currentUserId)
                    finish()
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    private fun renderAvatar(avatarUrl: String) {
        if (avatarUrl.isNotBlank()) {
            MediaImageHelper.loadSafeImage(
                imageView = binding.ivGroupAvatar,
                url = avatarUrl,
                isCircle = false,
                placeholderRes = R.drawable.ic_account,
                errorRes = R.drawable.ic_account
            )
        }
    }

    private fun observeViewModel() {
        memberAdapter = GroupMemberAdapter(
            onItemClicked = { member ->
                openDirectChat(member)
            },
            onItemLongClicked = { member ->
                showMemberActions(member)
            }
        )
        binding.rvMembers.layoutManager = LinearLayoutManager(this)
        binding.rvMembers.adapter = memberAdapter

        // Metadata updates
        lifecycleScope.launch {
            viewModel.metadata.collect { meta ->
                meta?.let {
                    if (!it.name.isNullOrBlank()) {
                        currentName = it.name
                        binding.tvGroupName.text = it.name
                        binding.collapsingToolbar.title = it.name
                    }
                    if (!it.avatarUrl.isNullOrBlank()) {
                        currentAvatar = it.avatarUrl
                        renderAvatar(it.avatarUrl)
                    }
                    currentDesc = it.description
                    binding.tvDescription.text = it.description.ifBlank {
                        getString(R.string.placeholder_group_description)
                    }
                }
            }
        }

        // Role & Admin status
        lifecycleScope.launch {
            viewModel.currentUserRole.collect { role ->
                if (role.equals("owner", ignoreCase = true) || role.equals("admin", ignoreCase = true)) {
                    binding.tvUserRoleBadge.visibility = View.VISIBLE
                    binding.tvUserRoleBadge.text = if (role.equals("owner", ignoreCase = true)) {
                        "Group Creator • Admin"
                    } else {
                        "Group Admin"
                    }
                } else {
                    binding.tvUserRoleBadge.visibility = View.GONE
                }
            }
        }

        // Members list
        lifecycleScope.launch {
            viewModel.members.collect { memberList ->
                binding.tvMemberCount.text = getString(R.string.member_count_format, memberList.size)
                memberAdapter.submitList(memberList)
            }
        }
    }

    private fun showEditGroupNameDialog() {
        val input = EditText(this).apply {
            setText(currentName)
            setSelection(text.length)
            hint = getString(R.string.label_group_name)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_edit_group_name))
            .setView(container)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    viewModel.updateGroupDetails(convId, name = newName) { success, err ->
                        if (success) {
                            currentName = newName
                            binding.tvGroupName.text = newName
                            binding.collapsingToolbar.title = newName
                            Toast.makeText(this, getString(R.string.toast_group_updated), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, err ?: getString(R.string.toast_group_update_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showEditDescriptionDialog() {
        val input = EditText(this).apply {
            setText(currentDesc)
            setSelection(text.length)
            hint = getString(R.string.label_group_description)
            minLines = 3
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_edit_group_desc))
            .setView(container)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val newDesc = input.text.toString().trim()
                viewModel.updateGroupDetails(convId, description = newDesc) { success, err ->
                    if (success) {
                        currentDesc = newDesc
                        binding.tvDescription.text = newDesc.ifBlank { getString(R.string.placeholder_group_description) }
                        Toast.makeText(this, getString(R.string.toast_group_updated), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, err ?: getString(R.string.toast_group_update_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun uploadAndSetAvatar(uri: Uri) {
        lifecycleScope.launch {
            Toast.makeText(this@GroupInfoActivity, "Uploading group photo...", Toast.LENGTH_SHORT).show()
            val bytes = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes == null || bytes.isEmpty()) {
                Toast.makeText(this@GroupInfoActivity, "Failed to read image", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
            val fileName = "group_avatar_${System.currentTimeMillis()}.jpg"

            val uploadedUrl = chatRepository.uploadMedia(bytes, mimeType, fileName)
            if (!uploadedUrl.isNullOrBlank()) {
                viewModel.updateGroupDetails(convId, avatarUrl = uploadedUrl) { success, _ ->
                    if (success) {
                        currentAvatar = uploadedUrl
                        renderAvatar(uploadedUrl)
                        Toast.makeText(this@GroupInfoActivity, getString(R.string.toast_group_updated), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@GroupInfoActivity, getString(R.string.toast_group_update_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this@GroupInfoActivity, "Failed to upload group photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAddMemberDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.dialog_add_member_hint)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_add_member_title))
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val target = input.text.toString().trim()
                if (target.isNotBlank()) {
                    viewModel.addMember(convId, target) { success ->
                        if (success) {
                            Toast.makeText(this, getString(R.string.toast_member_added), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, getString(R.string.toast_member_add_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showMemberActions(member: GroupMember) {
        val isMe = member.id == viewModel.currentUserId
        val isAdmin = viewModel.isAdminOrOwner.value

        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // Option 1: Message
        if (!isMe) {
            options.add(getString(R.string.option_message_user, member.displayName.ifBlank { "User" }))
            actions.add { openDirectChat(member) }
        }

        // Admin options
        if (isAdmin && !isMe) {
            val isTargetAdmin = member.role.equals("admin", ignoreCase = true) || member.role.equals("owner", ignoreCase = true)
            if (isTargetAdmin) {
                // If target is admin (and not owner), can demote
                if (!member.role.equals("owner", ignoreCase = true)) {
                    options.add(getString(R.string.option_demote_admin))
                    actions.add { viewModel.demoteMember(convId, member.id) }
                }
            } else {
                options.add(getString(R.string.option_promote_admin))
                actions.add { viewModel.promoteMember(convId, member.id) }
            }

            // Remove option
            if (!member.role.equals("owner", ignoreCase = true)) {
                options.add(getString(R.string.option_remove_group))
                actions.add {
                    AlertDialog.Builder(this)
                        .setTitle("Remove Member?")
                        .setMessage("Remove ${member.displayName.ifBlank { "this member" }} from the group?")
                        .setPositiveButton("Remove") { _, _ -> viewModel.removeMember(convId, member.id) }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                }
            }
        }

        if (options.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle(member.displayName.ifBlank { "Member Options" })
            .setItems(options.toTypedArray()) { _, which ->
                actions[which].invoke()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun openDirectChat(member: GroupMember) {
        if (member.id == viewModel.currentUserId) return
        val intent = Intent(this, ChatRoomActivity::class.java).apply {
            putExtra(ChatRoomActivity.EXTRA_PARTNER_ID, member.id)
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, member.displayName.ifBlank { "User" })
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_AVATAR, member.avatarUrl)
            putExtra(ChatRoomActivity.EXTRA_IS_GROUP, false)
        }
        startActivity(intent)
    }

    private fun setupSharedMedia(convId: String, groupName: String) {
        val thumbAdapter = MediaPreviewThumbAdapter { message ->
            val intent = Intent(this, MediaViewerActivity::class.java).apply {
                putExtra(MediaViewerActivity.EXTRA_MEDIA_URL, message.mediaUrl)
                putExtra(MediaViewerActivity.EXTRA_IS_VIDEO, message.type == MessageType.VIDEO)
                putExtra(MediaViewerActivity.EXTRA_TITLE, message.senderName)
            }
            startActivity(intent)
        }
        binding.rvMediaPreview.layoutManager = LinearLayoutManager(
            this, LinearLayoutManager.HORIZONTAL, false
        )
        binding.rvMediaPreview.adapter = thumbAdapter

        lifecycleScope.launch {
            chatRepository.getSharedMediaCount(convId).collect { count ->
                binding.tvMediaCount.text = if (count > 0) count.toString() else getString(R.string.no_media_items)
            }
        }

        lifecycleScope.launch {
            chatRepository.getRecentMediaPreviews(convId).collect { previews ->
                thumbAdapter.submitList(previews)
                binding.rvMediaPreview.visibility = if (previews.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }

        binding.cardSharedMedia.setOnClickListener {
            val intent = Intent(this, SharedMediaActivity::class.java).apply {
                putExtra(SharedMediaActivity.EXTRA_CONVERSATION_ID, convId)
                putExtra(SharedMediaActivity.EXTRA_TITLE, groupName)
            }
            startActivity(intent)
        }
    }
}
