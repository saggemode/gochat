package com.example.gochat.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.gochat.R
import com.example.gochat.core.export.ChatExportManager
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.model.Message
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.databinding.BottomSheetExportChatBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * BottomSheet dialog offering WhatsApp-style Export Chat options:
 * 1. Without Media (.txt)
 * 2. Include Media (.zip)
 * 3. Select Messages to Export
 */
class ExportChatBottomSheet(
    private val conversationId: String,
    private val conversationTitle: String,
    private val chatRepository: ChatRepository,
    private val targetMessages: List<Message>? = null,
    private val onSelectMessagesRequested: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetExportChatBinding? = null
    private val binding get() = _binding!!

    private var exportJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetExportChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val isSelectedMode = targetMessages != null && targetMessages.isNotEmpty()

        if (isSelectedMode) {
            val count = targetMessages!!.size
            binding.tvExportTitle.text = getString(R.string.export_selected_messages)
            binding.tvExportSubtitle.text = getString(R.string.export_selection_count, count)
            // Hide the "Select messages" row since we are already in selection mode
            binding.dividerSelectedOption.visibility = View.GONE
            binding.btnOptionSelectedMessages.visibility = View.GONE
        } else {
            binding.tvExportTitle.text = getString(R.string.export_chat_dialog_title)
            binding.tvExportSubtitle.text = "Export conversation with $conversationTitle"
            binding.dividerSelectedOption.visibility = View.VISIBLE
            binding.btnOptionSelectedMessages.visibility = View.VISIBLE
        }

        binding.btnCloseExportSheet.setOnClickListener {
            cancelExport()
            dismiss()
        }

        // 1. Without Media
        binding.btnOptionWithoutMedia.setOnClickListener {
            startExport(includeMedia = false)
        }

        // 2. Include Media
        binding.btnOptionIncludeMedia.setOnClickListener {
            startExport(includeMedia = true)
        }

        // 3. Select Messages
        binding.btnOptionSelectedMessages.setOnClickListener {
            dismiss()
            onSelectMessagesRequested?.invoke()
        }

        binding.btnCancelExport.setOnClickListener {
            cancelExport()
        }
    }

    private fun startExport(includeMedia: Boolean) {
        binding.layoutExportOptions.visibility = View.GONE
        binding.layoutDisclaimer.visibility = View.GONE
        binding.layoutExportProgress.visibility = View.VISIBLE
        isCancelable = false

        val tokenManager = TokenManager.getInstance(requireContext())
        val exportManager = ChatExportManager(requireContext(), chatRepository, tokenManager)

        exportJob = lifecycleScope.launch {
            val result = exportManager.exportChat(
                conversationId = conversationId,
                conversationTitle = conversationTitle,
                includeMedia = includeMedia,
                targetMessages = targetMessages,
                onProgress = { progress, status ->
                    lifecycleScope.launch {
                        _binding?.let { b ->
                            b.progressBarExport.progress = progress
                            b.tvExportPercent.text = "$progress%"
                            b.tvExportProgressStatus.text = status
                        }
                    }
                }
            )

            result.onSuccess { exportResult ->
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), getString(R.string.export_complete), Toast.LENGTH_SHORT).show()
                ChatExportManager.shareExport(requireContext(), exportResult, conversationTitle)
                dismiss()
            }.onFailure { err ->
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), "Export failed: ${err.message}", Toast.LENGTH_LONG).show()
                resetUI()
            }
        }
    }

    private fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        resetUI()
    }

    private fun resetUI() {
        _binding?.let { b ->
            b.layoutExportProgress.visibility = View.GONE
            b.layoutDisclaimer.visibility = View.VISIBLE
            b.layoutExportOptions.visibility = View.VISIBLE
            isCancelable = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        exportJob?.cancel()
        _binding = null
    }

    companion object {
        const val TAG = "ExportChatBottomSheet"
    }
}
