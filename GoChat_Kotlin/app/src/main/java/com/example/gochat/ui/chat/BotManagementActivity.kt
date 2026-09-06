package com.example.gochat.ui.chat

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
import com.example.gochat.data.model.BotConfig
import com.example.gochat.data.model.BotPermission
import com.example.gochat.databinding.ActivityBotManagementBinding
import com.example.gochat.databinding.ItemBotPermissionBinding
import kotlinx.coroutines.launch

class BotManagementActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    }

    private lateinit var binding: ActivityBotManagementBinding
    private val viewModel: BotManagementViewModel by viewModels()
    private val convId: String by lazy { intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty() }
    private lateinit var adapter: BotPermissionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBotManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupPermissionsList()
        observeViewModel()

        viewModel.loadBotConfig(convId)

        binding.btnSave.setOnClickListener {
            val currentConfig = viewModel.botConfig.value ?: BotConfig(botId = "bot_123", groupId = convId)
            val updatedConfig = currentConfig.copy(
                isActive = binding.switchBotActive.isChecked,
                permissions = adapter.getEnabledPermissions().toList(),
                rules = binding.etRules.text.toString()
            )
            viewModel.saveBotConfig(updatedConfig)
            Toast.makeText(this, getString(R.string.toast_bot_config_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupPermissionsList() {
        val permissions = BotPermission.values()
        adapter = BotPermissionAdapter(permissions.toList())
        binding.rvPermissions.layoutManager = LinearLayoutManager(this)
        binding.rvPermissions.adapter = adapter
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.botConfig.collect { config ->
                config?.let {
                    binding.switchBotActive.isChecked = it.isActive
                    binding.etRules.setText(it.rules)
                    adapter.updateEnabledPermissions(it.permissions.toSet())
                }
            }
        }
    }

    inner class BotPermissionAdapter(private val permissions: List<BotPermission>) :
        RecyclerView.Adapter<BotPermissionAdapter.ViewHolder>() {

        private val enabledPermissions = mutableSetOf<BotPermission>()

        fun updateEnabledPermissions(newPermissions: Set<BotPermission>) {
            enabledPermissions.clear()
            enabledPermissions.addAll(newPermissions)
            notifyDataSetChanged()
        }

        fun getEnabledPermissions(): Set<BotPermission> = enabledPermissions

        inner class ViewHolder(private val binding: ItemBotPermissionBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(permission: BotPermission) {
                binding.tvPermissionName.text = permission.name.lowercase()
                    .replace("_", " ")
                    .replaceFirstChar { it.uppercase() }
                
                binding.switchPermission.setOnCheckedChangeListener(null)
                binding.switchPermission.isChecked = enabledPermissions.contains(permission)
                binding.switchPermission.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) enabledPermissions.add(permission)
                    else enabledPermissions.remove(permission)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemBotPermissionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(permissions[position])
        }

        override fun getItemCount() = permissions.size
    }
}
