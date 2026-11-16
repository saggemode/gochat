package com.example.gochat.ui.wallet

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.data.model.RedPacketDetail
import com.example.gochat.data.repository.WalletRepository
import com.example.gochat.databinding.ActivityRedPacketBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Opens a red packet: shows the greeting and either lets the viewer claim their
 * random share or, if they already claimed (or it's empty), shows the outcome.
 * Claiming credits the viewer's wallet server-side.
 */
@AndroidEntryPoint
class RedPacketDetailActivity : AppCompatActivity() {

    @Inject
    lateinit var walletRepository: WalletRepository

    private lateinit var binding: ActivityRedPacketBinding
    private var packetId: String = ""
    private var currency: String = "NGN"

    companion object {
        const val EXTRA_PACKET_ID = "extra_packet_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRedPacketBinding.inflate(layoutInflater)
        setContentView(binding.root)

        packetId = intent.getStringExtra(EXTRA_PACKET_ID).orEmpty()
        if (packetId.isBlank()) { finish(); return }

        binding.btnCloseRedPacket.setOnClickListener { finish() }
        binding.btnOpenRedPacket.setOnClickListener { claim() }
        load()
    }

    private fun load() {
        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { walletRepository.getRedPacket(packetId) }
            setLoading(false)
            result.onSuccess { render(it) }
                .onFailure {
                    Toast.makeText(this@RedPacketDetailActivity, it.message ?: "Red packet not found", Toast.LENGTH_SHORT).show()
                    finish()
                }
        }
    }

    private fun render(detail: RedPacketDetail) {
        binding.tvRedPacketGreeting.text = detail.message
        binding.tvRedPacketRemaining.text = "${detail.remainingCount} of ${detail.totalCount} left"

        if (detail.claimedByMe && detail.myAmount >= 0) {
            showClaimedAmount(detail.myAmount)
        } else if (detail.remainingCount <= 0) {
            binding.btnOpenRedPacket.visibility = View.GONE
            binding.tvRedPacketRemaining.text = "This red packet is fully claimed"
        } else {
            binding.btnOpenRedPacket.visibility = View.VISIBLE
            binding.tvRedPacketAmount.visibility = View.GONE
        }
    }

    private fun claim() {
        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { walletRepository.claimRedPacket(packetId) }
            setLoading(false)
            result.onSuccess { showClaimedAmount(it.amount) }
                .onFailure {
                    Toast.makeText(this@RedPacketDetailActivity, it.message ?: "Could not open", Toast.LENGTH_SHORT).show()
                    load()
                }
        }
    }

    private fun showClaimedAmount(amountMinor: Long) {
        binding.btnOpenRedPacket.visibility = View.GONE
        binding.tvRedPacketAmount.visibility = View.VISIBLE
        binding.tvRedPacketAmount.text = WalletActivity.formatMinor(amountMinor, currency)
    }

    private fun setLoading(loading: Boolean) {
        binding.progressRedPacket.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnOpenRedPacket.isEnabled = !loading
    }
}
