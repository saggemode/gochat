package com.example.gochat.ui.chat

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R
import com.example.gochat.databinding.ActivityGroupCreateBinding
import com.example.gochat.databinding.ItemSelectedMemberBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GroupCreateActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEMBER_IDS = "extra_member_ids"
        const val EXTRA_MEMBER_NAMES = "extra_member_names"
    }

    private lateinit var binding: ActivityGroupCreateBinding
    private val viewModel: GroupCreateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupCreateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val memberIds = intent.getStringArrayExtra(EXTRA_MEMBER_IDS)?.toList() ?: emptyList()
        val memberNames = intent.getStringArrayExtra(EXTRA_MEMBER_NAMES)?.toList() ?: emptyList()

        setupToolbar()
        setupMembersList(memberNames)
        observeViewModel()
        
        binding.fabCreate.setOnClickListener {
            val groupName = binding.etGroupName.text?.toString()?.trim().orEmpty()
            if (groupName.isEmpty()) {
                Toast.makeText(this, "Please enter a group subject", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            viewModel.createGroup(groupName, memberIds)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupMembersList(names: List<String>) {
        binding.tvParticipantsCount.text = "PARTICIPANTS: ${names.size}"
        binding.rvSelectedMembers.layoutManager = 
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvSelectedMembers.adapter = SelectedMembersAdapter(names)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                binding.fabCreate.isEnabled = !loading
            }
        }

        lifecycleScope.launch {
            viewModel.successEvent.collect { conversation ->
                val intent = Intent(this@GroupCreateActivity, ChatRoomActivity::class.java).apply {
                    putExtra(ChatRoomActivity.EXTRA_CONVERSATION_ID, conversation.id)
                    putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, conversation.title)
                    putExtra(ChatRoomActivity.EXTRA_IS_GROUP, true)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                finish()
            }
        }

        lifecycleScope.launch {
            viewModel.errorEvent.collect { error ->
                Toast.makeText(this@GroupCreateActivity, "Error: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private class SelectedMembersAdapter(private val names: List<String>) :
        RecyclerView.Adapter<SelectedMembersAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemSelectedMemberBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemSelectedMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.binding.tvName.text = names[position].split(" ").firstOrNull() ?: names[position]
            holder.binding.ivAvatar.setImageResource(R.drawable.ic_account)
        }

        override fun getItemCount() = names.size
    }
}
