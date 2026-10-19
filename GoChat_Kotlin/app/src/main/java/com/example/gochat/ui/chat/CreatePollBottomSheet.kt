package com.example.gochat.ui.chat

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.gochat.R
import com.example.gochat.data.model.PollData
import com.example.gochat.data.model.PollOption
import com.example.gochat.databinding.BottomSheetCreatePollBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.UUID

class CreatePollBottomSheet(
    private val currentUserId: String,
    private val onPollCreated: (PollData) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCreatePollBinding? = null
    private val binding get() = _binding!!

    private var isQuizMode = false
    private var selectedCorrectOptionIndex = -1
    private val optionViews = mutableListOf<View>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCreatePollBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCloseCreatePoll.setOnClickListener { dismiss() }

        setupModeTabs()
        setupOptions()
        setupSubmit()
    }

    private fun setupModeTabs() {
        binding.tabStandardPoll.setOnClickListener {
            if (isQuizMode) {
                isQuizMode = false
                updateModeUi()
            }
        }

        binding.tabQuizMode.setOnClickListener {
            if (!isQuizMode) {
                isQuizMode = true
                updateModeUi()
            }
        }
    }

    private fun updateModeUi() {
        if (isQuizMode) {
            binding.tabQuizMode.setBackgroundResource(R.drawable.bg_button_emerald)
            binding.tabQuizMode.setTextColor(Color.BLACK)
            binding.tabStandardPoll.setBackgroundColor(Color.TRANSPARENT)
            binding.tabStandardPoll.setTextColor(ContextCompat.getColor(requireContext(), R.color.gochat_text_secondary))

            binding.tvQuizModeInfo.visibility = View.VISIBLE
            binding.layoutQuizExplanationInput.visibility = View.VISIBLE
            binding.rowMultipleAnswers.visibility = View.GONE
            binding.switchMultipleAnswers.isChecked = false
            binding.tvCreatePollTitle.text = "Create Quiz"
            binding.btnSubmitPoll.text = "Create Quiz"
        } else {
            binding.tabStandardPoll.setBackgroundResource(R.drawable.bg_button_emerald)
            binding.tabStandardPoll.setTextColor(Color.BLACK)
            binding.tabQuizMode.setBackgroundColor(Color.TRANSPARENT)
            binding.tabQuizMode.setTextColor(ContextCompat.getColor(requireContext(), R.color.gochat_text_secondary))

            binding.tvQuizModeInfo.visibility = View.GONE
            binding.layoutQuizExplanationInput.visibility = View.GONE
            binding.rowMultipleAnswers.visibility = View.VISIBLE
            binding.tvCreatePollTitle.text = "Create Poll"
            binding.btnSubmitPoll.text = "Create Poll"
        }

        // Update radio buttons visibility in option rows
        optionViews.forEachIndexed { index, optView ->
            val rb = optView.findViewById<RadioButton>(R.id.rbCorrectAnswer)
            rb.visibility = if (isQuizMode) View.VISIBLE else View.GONE
            rb.isChecked = (isQuizMode && index == selectedCorrectOptionIndex)
        }
    }

    private fun setupOptions() {
        // Start with 2 default options
        addOptionRow("Option 1")
        addOptionRow("Option 2")

        binding.btnAddOptionRow.setOnClickListener {
            if (optionViews.size >= 8) {
                Toast.makeText(requireContext(), "Maximum 8 options allowed", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addOptionRow("Option ${optionViews.size + 1}")
        }
    }

    private fun addOptionRow(hint: String) {
        val row = layoutInflater.inflate(R.layout.item_create_poll_option, binding.layoutOptionsInputs, false)
        val et = row.findViewById<EditText>(R.id.etOptionText)
        val rb = row.findViewById<RadioButton>(R.id.rbCorrectAnswer)
        val btnRemove = row.findViewById<ImageView>(R.id.btnRemoveOption)

        et.hint = hint
        rb.visibility = if (isQuizMode) View.VISIBLE else View.GONE

        val rowIndex = optionViews.size
        optionViews.add(row)
        binding.layoutOptionsInputs.addView(row)

        rb.setOnClickListener {
            selectedCorrectOptionIndex = optionViews.indexOf(row)
            optionViews.forEachIndexed { idx, v ->
                v.findViewById<RadioButton>(R.id.rbCorrectAnswer).isChecked = (idx == selectedCorrectOptionIndex)
            }
        }

        btnRemove.setOnClickListener {
            if (optionViews.size <= 2) {
                Toast.makeText(requireContext(), "A poll requires at least 2 options", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val removeIdx = optionViews.indexOf(row)
            if (selectedCorrectOptionIndex == removeIdx) {
                selectedCorrectOptionIndex = -1
            } else if (selectedCorrectOptionIndex > removeIdx) {
                selectedCorrectOptionIndex--
            }
            optionViews.remove(row)
            binding.layoutOptionsInputs.removeView(row)
        }
    }

    private fun setupSubmit() {
        binding.btnSubmitPoll.setOnClickListener {
            val question = binding.etPollQuestion.text.toString().trim()
            if (question.isBlank()) {
                binding.etPollQuestion.error = "Please enter a question"
                binding.etPollQuestion.requestFocus()
                return@setOnClickListener
            }

            val validOptions = mutableListOf<String>()
            var correctOptionId: String? = null

            optionViews.forEachIndexed { idx, optView ->
                val text = optView.findViewById<EditText>(R.id.etOptionText).text.toString().trim()
                if (text.isNotBlank()) {
                    validOptions.add(text)
                }
            }

            if (validOptions.size < 2) {
                Toast.makeText(requireContext(), "Please provide at least 2 non-empty options", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isQuizMode) {
                if (selectedCorrectOptionIndex < 0 || selectedCorrectOptionIndex >= validOptions.size) {
                    Toast.makeText(requireContext(), "Please select the correct answer for the quiz", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            // Calculate expiration
            val now = System.currentTimeMillis()
            val expiresAt: Long? = when (binding.cgExpiration.checkedChipId) {
                R.id.chipExpire1h -> now + (1 * 3600 * 1000L)
                R.id.chipExpire24h -> now + (24 * 3600 * 1000L)
                R.id.chipExpire3d -> now + (3 * 24 * 3600 * 1000L)
                R.id.chipExpire1w -> now + (7 * 24 * 3600 * 1000L)
                else -> null
            }

            val pollId = "poll_${UUID.randomUUID()}"
            val pollOptions = validOptions.mapIndexed { idx, optText ->
                val optId = "opt_${idx + 1}_${System.currentTimeMillis() % 10000}"
                val isCorrect = isQuizMode && idx == selectedCorrectOptionIndex
                if (isCorrect) {
                    correctOptionId = optId
                }
                PollOption(
                    id = optId,
                    text = optText,
                    votes = 0,
                    isCorrect = isCorrect
                )
            }

            val poll = PollData(
                id = pollId,
                question = question,
                options = pollOptions,
                allowMultipleAnswers = !isQuizMode && binding.switchMultipleAnswers.isChecked,
                isQuiz = isQuizMode,
                correctOptionId = correctOptionId,
                explanation = binding.etQuizExplanation.text.toString().trim().ifBlank { null },
                isAnonymous = binding.switchAnonymousVoting.isChecked,
                expiresAt = expiresAt,
                isClosed = false,
                createdAt = now,
                createdBy = currentUserId
            )

            onPollCreated(poll)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CreatePollBottomSheet"
    }
}
