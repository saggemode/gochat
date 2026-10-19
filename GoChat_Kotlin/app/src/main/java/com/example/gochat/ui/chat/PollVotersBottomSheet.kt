package com.example.gochat.ui.chat

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.gochat.R
import com.example.gochat.data.model.PollData
import com.example.gochat.databinding.BottomSheetPollVotersBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class PollVotersBottomSheet(
    private val pollData: PollData,
    private val currentUserId: String
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPollVotersBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetPollVotersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvPollVotersTitle.text = if (pollData.isQuiz) "🧠 Quiz Breakdown" else "📊 Poll Results"
        binding.tvPollVotersSubtitle.text = "${pollData.question} (${pollData.totalVotes} total votes)"
        binding.btnCloseVoters.setOnClickListener { dismiss() }

        renderVoterBreakdown()
    }

    private fun renderVoterBreakdown() {
        val container = binding.layoutVotersContent
        container.removeAllViews()

        val totalVotes = pollData.totalVotes.coerceAtLeast(1)

        pollData.options.forEach { opt ->
            val optView = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 16)
            }

            // Option Title + Votes Count
            val headerRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val titleTv = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val isCorrectAnswer = pollData.isQuiz && opt.id == pollData.correctOptionId
                text = if (isCorrectAnswer) "✓ ${opt.text} (Correct)" else opt.text
                textSize = 14f
                setTextColor(if (isCorrectAnswer) ContextCompat.getColor(requireContext(), R.color.online_green) else Color.WHITE)
            }
            headerRow.addView(titleTv)

            val percent = (opt.votes.toFloat() / totalVotes * 100).toInt()
            val statsTv = TextView(requireContext()).apply {
                text = "${opt.votes} votes ($percent%)"
                textSize = 12f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.gochat_text_secondary))
            }
            headerRow.addView(statsTv)
            optView.addView(headerRow)

            // Voters List
            if (pollData.isAnonymous) {
                val anonTv = TextView(requireContext()).apply {
                    setPadding(0, 8, 0, 0)
                    text = "🔒 Anonymous voters"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.gochat_text_muted))
                }
                optView.addView(anonTv)
            } else if (opt.voterIds.isEmpty()) {
                val noneTv = TextView(requireContext()).apply {
                    setPadding(0, 8, 0, 0)
                    text = "No votes yet"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.gochat_text_muted))
                }
                optView.addView(noneTv)
            } else {
                opt.voterIds.forEachIndexed { idx, vid ->
                    val displayName = opt.voterNames.getOrNull(idx)?.ifBlank { null }
                        ?: if (vid == currentUserId) "You" else "User ${vid.takeLast(4)}"
                    val voterTv = TextView(requireContext()).apply {
                        setPadding(12, 6, 0, 2)
                        text = "• $displayName"
                        textSize = 13f
                        setTextColor(if (vid == currentUserId) ContextCompat.getColor(requireContext(), R.color.gochat_emerald_light) else ContextCompat.getColor(requireContext(), R.color.gochat_text_primary))
                    }
                    optView.addView(voterTv)
                }
            }

            // Separator
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    topMargin = 16
                }
                setBackgroundColor(Color.parseColor("#20FFFFFF"))
            }
            optView.addView(divider)

            container.addView(optView)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "PollVotersBottomSheet"
    }
}
