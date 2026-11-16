package com.example.gochat.ui.channels

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.data.model.Channel
import com.example.gochat.data.repository.ChannelRepository
import com.example.gochat.databinding.ActivityChannelsBinding
import com.example.gochat.databinding.ItemChannelBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import javax.inject.Inject

/** Browse, create, and open channels (one-to-many broadcast). */
@AndroidEntryPoint
class ChannelsActivity : AppCompatActivity() {

    @Inject
    lateinit var channelRepository: ChannelRepository

    private lateinit var binding: ActivityChannelsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackChannels.setOnClickListener { finish() }
        binding.btnNewChannel.setOnClickListener { showCreateDialog() }
        binding.swipeChannels.setOnRefreshListener { load() }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        binding.swipeChannels.isRefreshing = true
        lifecycleScope.launch {
            val channels = withContext(Dispatchers.IO) {
                channelRepository.listChannels().getOrNull() ?: emptyList()
            }
            render(channels)
            binding.swipeChannels.isRefreshing = false
        }
    }

    private fun render(channels: List<Channel>) {
        binding.containerChannels.removeAllViews()
        if (channels.isEmpty()) {
            binding.containerChannels.addView(binding.tvChannelsEmpty)
            binding.tvChannelsEmpty.visibility = View.VISIBLE
            return
        }
        val nf = NumberFormat.getIntegerInstance()
        channels.forEach { ch ->
            val item = ItemChannelBinding.inflate(layoutInflater, binding.containerChannels, false)
            item.tvChannelName.text = ch.name.ifBlank { "Channel" }
            if (ch.description.isNotBlank()) {
                item.tvChannelDesc.text = ch.description
            } else {
                item.tvChannelDesc.visibility = View.GONE
            }
            val n = ch.subscribersCount
            item.tvChannelSubs.text = "${nf.format(n)} ${if (n == 1) "subscriber" else "subscribers"}"
            item.root.setOnClickListener {
                startActivity(Intent(this, ChannelViewActivity::class.java).apply {
                    putExtra(ChannelViewActivity.EXTRA_CHANNEL_ID, ch.id)
                    putExtra(ChannelViewActivity.EXTRA_CHANNEL_NAME, ch.name)
                })
            }
            binding.containerChannels.addView(item.root)
        }
    }

    private fun showCreateDialog() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val nameInput = EditText(this).apply { hint = "Channel name"; isSingleLine = true }
        val descInput = EditText(this).apply { hint = "Description (optional)" }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(nameInput)
            addView(descInput)
        }
        AlertDialog.Builder(this)
            .setTitle("New channel")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(this, "Enter a channel name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                createChannel(name, descInput.text?.toString()?.trim().orEmpty())
            }
            .setNegativeButton(getString(com.example.gochat.R.string.btn_cancel), null)
            .show()
    }

    private fun createChannel(name: String, description: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { channelRepository.createChannel(name, description) }
            result.onSuccess { ch ->
                startActivity(Intent(this@ChannelsActivity, ChannelViewActivity::class.java).apply {
                    putExtra(ChannelViewActivity.EXTRA_CHANNEL_ID, ch.id)
                    putExtra(ChannelViewActivity.EXTRA_CHANNEL_NAME, ch.name)
                })
            }.onFailure {
                Toast.makeText(this@ChannelsActivity, it.message ?: "Failed to create channel", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
