package com.example.gochat.ui.moments

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.data.model.Moment
import com.example.gochat.data.repository.MomentsRepository
import com.example.gochat.databinding.ActivityMomentsBinding
import com.example.gochat.databinding.ItemMomentBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** WeChat-style Moments feed: posts from people you follow, with like + comment. */
@AndroidEntryPoint
class MomentsActivity : AppCompatActivity() {

    @Inject
    lateinit var momentsRepository: MomentsRepository

    @Inject
    lateinit var walletRepository: com.example.gochat.data.repository.WalletRepository

    private lateinit var binding: ActivityMomentsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMomentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackMoments.setOnClickListener { finish() }
        binding.btnNewMoment.setOnClickListener { showCreateDialog() }
        binding.swipeMoments.setOnRefreshListener { load() }
        binding.tvWalletBalanceMoments.setOnClickListener {
            startActivity(android.content.Intent(this, com.example.gochat.ui.wallet.WalletActivity::class.java))
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val wallet = withContext(Dispatchers.IO) { walletRepository.getWallet().getOrNull() }
            if (wallet != null) {
                binding.tvWalletBalanceMoments.text = "🧧 " +
                    com.example.gochat.ui.wallet.WalletActivity.formatMinor(wallet.balance, wallet.currency)
                binding.tvWalletBalanceMoments.visibility = View.VISIBLE
            }
        }
    }

    private fun load() {
        binding.swipeMoments.isRefreshing = true
        lifecycleScope.launch {
            val feed = withContext(Dispatchers.IO) { momentsRepository.getFeed().getOrNull() ?: emptyList() }
            render(feed)
            binding.swipeMoments.isRefreshing = false
        }
    }

    private fun render(moments: List<Moment>) {
        binding.containerMoments.removeAllViews()
        if (moments.isEmpty()) {
            binding.containerMoments.addView(binding.tvMomentsEmpty)
            binding.tvMomentsEmpty.visibility = View.VISIBLE
            return
        }
        moments.forEach { m ->
            val item = ItemMomentBinding.inflate(layoutInflater, binding.containerMoments, false)
            item.tvMomentAuthor.text = m.userDisplayName.ifBlank { "Someone" }
            item.tvMomentContent.text = m.displayText
            val preview = m.comments.take(2).joinToString("\n") {
                "${it.userDisplayName.ifBlank { "Someone" }}: ${it.text}"
            }
            if (preview.isNotBlank()) {
                item.tvMomentComments.visibility = View.VISIBLE
                item.tvMomentComments.text = preview
            }
            item.btnMomentLike.text = "${if (m.hasLiked) "♥" else "♡"} ${m.likesCount}"
            item.btnMomentComment.text = "💬 ${m.commentCount}"
            item.btnMomentLike.setOnClickListener {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { momentsRepository.like(m.id) }
                    load()
                }
            }
            item.btnMomentComment.setOnClickListener { showCommentDialog(m) }
            binding.containerMoments.addView(item.root)
        }
    }

    private fun showCreateDialog() {
        val input = EditText(this).apply {
            hint = "Share a moment…"
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle("New moment")
            .setView(input)
            .setPositiveButton("Post") { _, _ ->
                val content = input.text?.toString()?.trim().orEmpty()
                if (content.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    val res = withContext(Dispatchers.IO) { momentsRepository.create(content) }
                    if (res.isSuccess) load()
                    else Toast.makeText(this@MomentsActivity, "Failed to post", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(com.example.gochat.R.string.btn_cancel), null)
            .show()
    }

    private fun showCommentDialog(moment: Moment) {
        val input = EditText(this).apply {
            hint = "Add a comment…"
            isSingleLine = true
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle("Comment")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { momentsRepository.comment(moment.id, text) }
                    if (ok) load()
                    else Toast.makeText(this@MomentsActivity, "Failed to comment", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(com.example.gochat.R.string.btn_cancel), null)
            .show()
    }
}
