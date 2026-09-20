package com.example.gochat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.gochat.R
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.databinding.BottomSheetToneAssistantBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ToneAssistantBottomSheet(
    private val originalText: String,
    private val onApply: (String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetToneAssistantBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var chatRepo: ChatRepository

    private var currentTone = "professional"
    private var currentPolishedResult = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetToneAssistantBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvOriginalDraft.text = originalText

        binding.btnClose.setOnClickListener { dismiss() }

        // Setup tone selector chips
        binding.chipProfessional.setOnClickListener { selectTone("professional") }
        binding.chipCasual.setOnClickListener { selectTone("casual") }
        binding.chipConcise.setOnClickListener { selectTone("concise") }
        binding.chipPersuasive.setOnClickListener { selectTone("persuasive") }

        binding.btnCopyResult.setOnClickListener {
            if (currentPolishedResult.isNotBlank()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Polished Message", currentPolishedResult)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnApplyTone.setOnClickListener {
            val textToApply = if (currentPolishedResult.isNotBlank()) currentPolishedResult else originalText
            onApply(textToApply)
            dismiss()
        }

        // Fetch initial tone
        fetchAdjustedTone("professional")
    }

    private fun selectTone(tone: String) {
        if (currentTone == tone) return
        currentTone = tone
        updateChipStyles(tone)
        fetchAdjustedTone(tone)
    }

    private fun updateChipStyles(selectedTone: String) {
        val chips = listOf(
            binding.chipProfessional to "professional",
            binding.chipCasual to "casual",
            binding.chipConcise to "concise",
            binding.chipPersuasive to "persuasive"
        )
        for ((chip, tone) in chips) {
            if (tone == selectedTone) {
                chip.setBackgroundResource(R.drawable.bg_chip_selected)
                chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            } else {
                chip.setBackgroundResource(R.drawable.bg_chip_unselected)
                chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            }
        }
    }

    private fun fetchAdjustedTone(tone: String) {
        binding.pbLoading.visibility = View.VISIBLE
        binding.layoutResult.visibility = View.GONE
        binding.btnApplyTone.isEnabled = false

        lifecycleScope.launch {
            val result = chatRepo.adjustTone(originalText, tone)
            binding.pbLoading.visibility = View.GONE
            binding.layoutResult.visibility = View.VISIBLE
            binding.btnApplyTone.isEnabled = true

            result.onSuccess { adjusted ->
                currentPolishedResult = adjusted
                binding.tvPolishedResult.text = adjusted
                binding.tvResultToneLabel.text = "✨ POLISHED (${tone.uppercase()})"
            }.onFailure { err ->
                currentPolishedResult = originalText
                binding.tvPolishedResult.text = originalText
                binding.tvResultToneLabel.text = "ORIGINAL (AI OFFLINE)"
                Toast.makeText(requireContext(), "Tone service unavailable, showing draft", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ToneAssistantBottomSheet"
    }
}
