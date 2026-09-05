package com.example.gochat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.example.gochat.R
import com.example.gochat.data.model.GroupMember
import com.example.gochat.databinding.ActivityGroupInfoBinding
import kotlinx.coroutines.launch

class GroupInfoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_GROUP_NAME = "extra_group_name"
        const val EXTRA_GROUP_AVATAR = "extra_group_avatar"
        const val EXTRA_MEMBER_IDS = "extra_member_ids"
    }

    private lateinit var binding: ActivityGroupInfoBinding
    private val viewModel: GroupInfoViewModel by viewModels()
    private lateinit var memberAdapter: GroupMemberAdapter
    private val convId: String by lazy { intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val groupName = intent.getStringExtra(EXTRA_GROUP_NAME) ?: "Group Info"
        val groupAvatar = intent.getStringExtra(EXTRA_GROUP_AVATAR).orEmpty()
        val memberIds = intent.getStringArrayListExtra(EXTRA_MEMBER_IDS) ?: arrayListOf()

        setupToolbar(groupName)
        setupUI(groupName, groupAvatar)
        observeViewModel()

        viewModel.loadGroupData(convId, memberIds)
    }

    private fun setupToolbar(name: String) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.collapsingToolbar.title = name
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupUI(name: String, avatar: String) {
        if (avatar.isNotBlank()) {
            binding.ivGroupAvatar.load(avatar)
        }

        binding.btnInviteLink.setOnClickListener {
            viewModel.generateInviteLink(convId) { code ->
                val link = "https://gochat.link/join/$code"
                AlertDialog.Builder(this)
                    .setTitle("Group Invite Link")
                    .setMessage(link)
                    .setPositiveButton("Copy") { _, _ ->
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Group Link", link)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }

        binding.btnExitGroup.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Exit Group?")
                .setMessage("Are you sure you want to exit this group?")
                .setPositiveButton("Exit") { _, _ ->
                    // viewModel.exitGroup(convId)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        
        binding.btnAddMember.setOnClickListener {
            Toast.makeText(this, "Add member functionality coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.metadata.collect { meta ->
                meta?.let {
                    binding.tvDescription.text = it.description.ifBlank { "Add group description" }
                }
            }
        }

        memberAdapter = GroupMemberAdapter(
            onItemClicked = { /* Show user profile */ },
            onItemLongClicked = { member ->
                showMemberActions(member)
            }
        )
        binding.rvMembers.layoutManager = LinearLayoutManager(this)
        binding.rvMembers.adapter = memberAdapter

        lifecycleScope.launch {
            viewModel.members.collect { memberList ->
                binding.tvMemberCount.text = "${memberList.size} Members"
                memberAdapter.submitList(memberList)
            }
        }
    }

    private fun showMemberActions(member: GroupMember) {
        val actions = arrayOf("Message ${member.displayName}", "Promote to Admin", "Remove from Group")
        AlertDialog.Builder(this)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> { /* Start 1:1 chat */ }
                    1 -> viewModel.promoteMember(convId, member.id)
                    2 -> viewModel.removeMember(convId, member.id)
                }
            }
            .show()
    }
}
