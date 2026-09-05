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
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
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

        val groupName = intent.getStringExtra(EXTRA_GROUP_NAME) ?: getString(R.string.group_info_title)
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
        MediaImageHelper.loadSafeImage(
            imageView = binding.ivGroupAvatar,
            url = avatar,
            isCircle = false,
            placeholderRes = R.drawable.ic_account,
            errorRes = R.drawable.ic_account
        )

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

        binding.btnExitGroup.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_exit_group_title))
                .setMessage(getString(R.string.dialog_exit_group_desc))
                .setPositiveButton(getString(R.string.exit_group)) { _, _ ->
                    // viewModel.exitGroup(convId)
                    finish()
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
        
        binding.btnAddMember.setOnClickListener {
            Toast.makeText(this, getString(R.string.toast_add_member_soon), Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.metadata.collect { meta ->
                meta?.let {
                    binding.tvDescription.text = it.description.ifBlank { getString(R.string.placeholder_group_description) }
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
                binding.tvMemberCount.text = getString(R.string.member_count_format, memberList.size)
                memberAdapter.submitList(memberList)
            }
        }
    }

    private fun showMemberActions(member: GroupMember) {
        val actions = arrayOf(
            getString(R.string.option_message_user, member.displayName),
            getString(R.string.option_promote_admin),
            getString(R.string.option_remove_group)
        )
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
