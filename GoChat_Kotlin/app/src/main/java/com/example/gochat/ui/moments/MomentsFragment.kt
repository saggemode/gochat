package com.example.gochat.ui.moments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.gochat.R
import com.example.gochat.data.model.Moment
import com.example.gochat.data.repository.MomentsRepository
import com.example.gochat.databinding.ActivityMomentsBinding
import com.example.gochat.databinding.ItemMomentBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Moments feed as a bottom-nav tab (same UI as MomentsActivity, minus the back
 * button since a tab has no parent to return to).
 */
@AndroidEntryPoint
class MomentsFragment : Fragment() {

    @Inject
    lateinit var momentsRepository: MomentsRepository

    @Inject
    lateinit var walletRepository: com.example.gochat.data.repository.WalletRepository

    private var _binding: ActivityMomentsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityMomentsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // A tab has no back navigation.
        binding.btnBackMoments.visibility = View.GONE
        binding.btnNewMoment.setOnClickListener { showCreateDialog() }
        binding.swipeMoments.setOnRefreshListener { load() }
        binding.tvWalletBalanceMoments.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), com.example.gochat.ui.wallet.WalletActivity::class.java))
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        refreshWalletBalance()
    }

    /** Loads the wallet balance into the top-bar chip; hidden if unavailable. */
    private fun refreshWalletBalance() {
        _binding ?: return
        lifecycleScope.launch {
            val wallet = walletRepository.getWallet().getOrNull()
            val b = _binding ?: return@launch
            if (wallet != null) {
                b.tvWalletBalanceMoments.text = "🧧 " +
                    com.example.gochat.ui.wallet.WalletActivity.formatMinor(wallet.balance, wallet.currency)
                b.tvWalletBalanceMoments.visibility = View.VISIBLE
            }
        }
    }

    private fun load() {
        binding.swipeMoments.isRefreshing = true
        lifecycleScope.launch {
            val feed = withContext(Dispatchers.IO) { momentsRepository.getFeed().getOrNull() ?: emptyList() }
            _binding ?: return@launch
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
        val input = EditText(requireContext()).apply {
            hint = "Share a moment…"
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("New moment")
            .setView(input)
            .setPositiveButton("Post") { _, _ ->
                val content = input.text?.toString()?.trim().orEmpty()
                if (content.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    val res = withContext(Dispatchers.IO) { momentsRepository.create(content) }
                    if (res.isSuccess) load()
                    else Toast.makeText(requireContext(), "Failed to post", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showCommentDialog(moment: Moment) {
        val input = EditText(requireContext()).apply {
            hint = "Add a comment…"
            isSingleLine = true
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Comment")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { momentsRepository.comment(moment.id, text) }
                    if (ok) load()
                    else Toast.makeText(requireContext(), "Failed to comment", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
