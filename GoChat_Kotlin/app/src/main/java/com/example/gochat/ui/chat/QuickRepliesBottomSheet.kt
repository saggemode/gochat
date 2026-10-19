package com.example.gochat.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.data.model.QuickReply
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.BottomSheetQuickRepliesBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class QuickRepliesBottomSheet(
    private val marketplaceRepository: MarketplaceRepository,
    private val onQuickReplySelected: (String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetQuickRepliesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: QuickRepliesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetQuickRepliesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = QuickRepliesAdapter { quickReply ->
            onQuickReplySelected(quickReply.replyText)
            dismiss()
        }

        binding.rvQuickReplies.layoutManager = LinearLayoutManager(requireContext())
        binding.rvQuickReplies.adapter = adapter

        setupActions()
        loadQuickReplies()
    }

    private fun setupActions() {
        binding.btnAddNewTemplate.setOnClickListener {
            val isFormVisible = binding.layoutCreateQuickReply.visibility == View.VISIBLE
            binding.layoutCreateQuickReply.visibility = if (isFormVisible) View.GONE else View.VISIBLE
            if (!isFormVisible) {
                binding.etNewShortcut.requestFocus()
            }
        }

        binding.btnCancelCreate.setOnClickListener {
            binding.layoutCreateQuickReply.visibility = View.GONE
            binding.etNewShortcut.text.clear()
            binding.etNewReplyText.text.clear()
        }

        binding.btnSaveQuickReply.setOnClickListener {
            val rawShortcut = binding.etNewShortcut.text.toString().trim()
            val replyText = binding.etNewReplyText.text.toString().trim()

            if (rawShortcut.isBlank()) {
                binding.etNewShortcut.error = "Enter shortcut (e.g. /hours)"
                return@setOnClickListener
            }
            if (replyText.isBlank()) {
                binding.etNewReplyText.error = "Enter reply text"
                return@setOnClickListener
            }

            val shortcut = if (rawShortcut.startsWith("/")) rawShortcut else "/$rawShortcut"
            saveNewQuickReply(shortcut, replyText)
        }
    }

    private fun loadQuickReplies() {
        lifecycleScope.launch {
            val result = marketplaceRepository.getQuickReplies()
            val list = result.getOrDefault(emptyList())
            renderList(list)
        }
    }

    private fun saveNewQuickReply(shortcut: String, replyText: String) {
        lifecycleScope.launch {
            val result = marketplaceRepository.createQuickReply(shortcut, replyText)
            if (result.isSuccess) {
                Toast.makeText(requireContext(), "Template $shortcut saved!", Toast.LENGTH_SHORT).show()
                binding.layoutCreateQuickReply.visibility = View.GONE
                binding.etNewShortcut.text.clear()
                binding.etNewReplyText.text.clear()
                loadQuickReplies()
            } else {
                Toast.makeText(requireContext(), "Failed to save template", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderList(list: List<QuickReply>) {
        if (list.isEmpty()) {
            binding.rvQuickReplies.visibility = View.GONE
            binding.tvEmptyQuickReplies.visibility = View.VISIBLE
        } else {
            binding.rvQuickReplies.visibility = View.VISIBLE
            binding.tvEmptyQuickReplies.visibility = View.GONE
            adapter.submitList(list)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "QuickRepliesBottomSheet"
    }
}
