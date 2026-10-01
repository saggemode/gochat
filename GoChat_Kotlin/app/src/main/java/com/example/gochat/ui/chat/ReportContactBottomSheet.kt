package com.example.gochat.ui.chat

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.gochat.R
import com.example.gochat.data.model.ReportReason
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.databinding.BottomSheetReportContactBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * WhatsApp-style Report Contact BottomSheet Dialog.
 *
 * Allows reporting a user/account for spam, abuse, fraud, or violations:
 * - Forwards recent messages (up to 5) as evidence to trust & safety.
 * - Provides categorized report reasons.
 * - Option to simultaneously block the contact and clear the chat.
 */
class ReportContactBottomSheet(
    private val targetUserId: String,
    private val targetUserName: String,
    private val conversationId: String? = null,
    private val chatRepository: ChatRepository,
    private val onReportCompleted: ((blocked: Boolean) -> Unit)? = null
) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "ReportContactBottomSheet"
    }

    private var _binding: BottomSheetReportContactBinding? = null
    private val binding get() = _binding!!

    private var selectedReason: ReportReason = ReportReason.SPAM

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetReportContactBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val displayName = targetUserName.ifBlank { "this contact" }
        binding.tvReportTitle.text = getString(R.string.report_contact_title, displayName)
        binding.btnSubmitReport.text = getString(R.string.action_report_contact, displayName)

        // Populate reasons
        setupReasonsList()

        binding.btnCloseReport.setOnClickListener {
            dismiss()
        }

        binding.btnCancelReport.setOnClickListener {
            dismiss()
        }

        binding.cbAlsoBlock.setOnCheckedChangeListener { _, isChecked ->
            binding.btnSubmitReport.text = if (isChecked) {
                getString(R.string.action_report_and_block)
            } else {
                getString(R.string.action_report_contact, displayName)
            }
        }

        binding.btnSubmitReport.setOnClickListener {
            submitReport()
        }
    }

    private fun setupReasonsList() {
        val radioColorState = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(
                Color.parseColor("#10B981"), // Emerald
                Color.parseColor("#9CA3AF")  // Muted gray
            )
        )

        ReportReason.values().forEachIndexed { index, reason ->
            val rb = RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = reason.displayLabel
                setTextColor(Color.parseColor("#F3F4F6"))
                textSize = 14f
                setPadding(12, 10, 12, 10)
                buttonTintList = radioColorState
                isChecked = index == 0
            }
            rb.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedReason = reason
                }
            }
            binding.rgReportReasons.addView(rb)
        }
    }

    private fun submitReport() {
        val details = binding.etReportDetails.text.toString().trim()
        val alsoBlock = binding.cbAlsoBlock.isChecked

        binding.pbReportProgress.visibility = View.VISIBLE
        binding.btnSubmitReport.isEnabled = false
        binding.btnCancelReport.isEnabled = false

        lifecycleScope.launch {
            try {
                // Collect evidence message IDs if conversationId is provided
                val evidenceMsgIds = mutableListOf<String>()
                if (!conversationId.isNullOrBlank()) {
                    try {
                        val messages = chatRepository.getMediaMessages(conversationId).firstOrNull().orEmpty()
                        evidenceMsgIds.addAll(messages.takeLast(5).map { it.id })
                    } catch (_: Exception) {}
                }

                // 1. Submit report to backend
                chatRepository.reportUser(
                    reportedUserId = targetUserId,
                    reportedUserName = targetUserName,
                    conversationId = conversationId,
                    reason = selectedReason.apiValue,
                    details = details,
                    evidenceMsgIds = evidenceMsgIds,
                    alsoBlock = alsoBlock
                )

                Toast.makeText(
                    requireContext(),
                    getString(R.string.report_submitted_toast),
                    Toast.LENGTH_LONG
                ).show()

                onReportCompleted?.invoke(alsoBlock)
                dismiss()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Failed to submit report: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
                binding.pbReportProgress.visibility = View.GONE
                binding.btnSubmitReport.isEnabled = true
                binding.btnCancelReport.isEnabled = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
