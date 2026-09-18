package com.example.gochat.ui.calls

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.transform.CircleCropTransformation
import com.example.gochat.R
import com.example.gochat.data.api.ApiConstants
import com.example.gochat.data.model.CallRecord
import com.example.gochat.data.model.CallStatus
import com.example.gochat.data.model.CallType
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.repository.CallRepository
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.databinding.BottomSheetCallDetailsBinding
import com.example.gochat.databinding.BottomSheetNewCallBinding
import com.example.gochat.databinding.FragmentCallsBinding
import com.example.gochat.ui.chat.ChatRoomActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class CallsFragment : Fragment() {

    private var _binding: FragmentCallsBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var callRepository: CallRepository

    @Inject
    lateinit var chatRepository: ChatRepository

    private lateinit var callAdapter: CallAdapter

    private enum class FilterMode { ALL, MISSED }
    private var currentFilter = FilterMode.ALL
    private var currentSearchQuery = ""
    private var cachedCallsList = listOf<CallRecord>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCallsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        observeCalls()
    }

    override fun onResume() {
        super.onResume()
        refreshCallHistory()
    }

    private fun setupRecyclerView() {
        callAdapter = CallAdapter(
            onCallAction = { record ->
                val callType = if (record.type == CallType.VIDEO) "video" else "voice"
                startCallAndNavigate(
                    targetUserId = record.peerId,
                    peerName = record.peerName,
                    peerAvatar = record.peerAvatar,
                    callType = callType
                )
            },
            onItemClick = { record ->
                showCallDetailsBottomSheet(record)
            }
        )

        binding.rvCalls.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = callAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupListeners() {
        // Pull-to-refresh
        binding.swipeRefreshCalls.setOnRefreshListener {
            refreshCallHistory()
        }

        // Create call link
        binding.tileCreateCallLink.setOnClickListener {
            val linkId = System.currentTimeMillis().toString().takeLast(6)
            val callLink = "${ApiConstants.BASE_URL}/call/room_$linkId"

            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("GoChat Call Link", callLink)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(requireContext(), "🔗 Call link copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        // Filter chips: All vs Missed
        binding.chipAllCalls.setOnClickListener {
            if (currentFilter != FilterMode.ALL) {
                currentFilter = FilterMode.ALL
                updateFilterChipStyles()
                filterAndSubmitList()
            }
        }

        binding.chipMissedCalls.setOnClickListener {
            if (currentFilter != FilterMode.MISSED) {
                currentFilter = FilterMode.MISSED
                updateFilterChipStyles()
                filterAndSubmitList()
            }
        }

        // Search toggle
        binding.btnSearchCalls.setOnClickListener {
            binding.layoutSearchCalls.visibility = View.VISIBLE
            binding.etSearchCalls.requestFocus()
        }

        binding.btnCloseSearch.setOnClickListener {
            binding.layoutSearchCalls.visibility = View.GONE
            binding.etSearchCalls.setText("")
            currentSearchQuery = ""
            filterAndSubmitList()
        }

        binding.etSearchCalls.doAfterTextChanged { text ->
            currentSearchQuery = text?.toString()?.trim().orEmpty()
            filterAndSubmitList()
        }

        // Clear call log
        binding.btnClearCallLog.setOnClickListener {
            showClearCallLogDialog()
        }

        // Floating Action Button: New Call
        binding.fabNewCall.setOnClickListener {
            showNewCallBottomSheet()
        }
    }

    private fun updateFilterChipStyles() {
        val b = _binding ?: return
        if (currentFilter == FilterMode.ALL) {
            b.chipAllCalls.setBackgroundResource(R.drawable.bg_chip_selected)
            b.chipAllCalls.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            b.chipMissedCalls.setBackgroundResource(R.drawable.bg_chip_unselected)
            b.chipMissedCalls.setTextColor(ContextCompat.getColor(requireContext(), R.color.gochat_text_secondary))
        } else {
            b.chipAllCalls.setBackgroundResource(R.drawable.bg_chip_unselected)
            b.chipAllCalls.setTextColor(ContextCompat.getColor(requireContext(), R.color.gochat_text_secondary))
            b.chipMissedCalls.setBackgroundResource(R.drawable.bg_chip_selected)
            b.chipMissedCalls.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
        }
    }

    private fun observeCalls() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                callRepository.observeCalls().collect { calls ->
                    cachedCallsList = calls
                    filterAndSubmitList()
                }
            }
        }
    }

    private fun filterAndSubmitList() {
        val b = _binding ?: return

        var filtered = cachedCallsList
        if (currentFilter == FilterMode.MISSED) {
            filtered = filtered.filter { it.status == CallStatus.MISSED }
        }

        if (currentSearchQuery.isNotBlank()) {
            filtered = filtered.filter { it.peerName.contains(currentSearchQuery, ignoreCase = true) }
        }

        if (filtered.isEmpty()) {
            b.layoutEmptyCalls.visibility = View.VISIBLE
            b.rvCalls.visibility = View.GONE
            b.tvRecentLabel.visibility = View.GONE

            if (currentFilter == FilterMode.MISSED) {
                b.tvEmptyCallsTitle.setText(R.string.no_missed_calls)
                b.tvEmptyCallsDesc.setText(R.string.no_missed_calls_desc)
            } else {
                b.tvEmptyCallsTitle.setText(R.string.no_calls_yet)
                b.tvEmptyCallsDesc.setText(R.string.no_calls_desc)
            }
        } else {
            b.layoutEmptyCalls.visibility = View.GONE
            b.rvCalls.visibility = View.VISIBLE
            b.tvRecentLabel.visibility = View.VISIBLE
            callAdapter.submitList(filtered)
        }
    }

    private fun refreshCallHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            _binding?.swipeRefreshCalls?.isRefreshing = true
            callRepository.refreshCallHistory()
            _binding?.swipeRefreshCalls?.isRefreshing = false
        }
    }

    private fun showClearCallLogDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_call_log)
            .setMessage(R.string.clear_call_log_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    callRepository.clearCallHistory()
                    Toast.makeText(requireContext(), "Call history cleared", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCallDetailsBottomSheet(record: CallRecord) {
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = BottomSheetCallDetailsBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        with(sheetBinding) {
            tvDetailPeerName.text = record.peerName

            // Avatar
            if (record.peerAvatar.isNotBlank()) {
                ivDetailAvatar.load(record.peerAvatar) {
                    crossfade(true)
                    placeholder(R.drawable.ic_account)
                    error(R.drawable.ic_account)
                    transformations(CircleCropTransformation())
                }
            } else {
                ivDetailAvatar.setImageResource(R.drawable.ic_account)
            }

            // Status and direction
            val typeStr = if (record.type == CallType.VIDEO) "video call" else "voice call"
            when (record.status) {
                CallStatus.INCOMING -> {
                    ivDetailDirection.setImageResource(R.drawable.ic_call_incoming)
                    tvDetailStatusText.text = "Incoming $typeStr"
                }
                CallStatus.OUTGOING -> {
                    ivDetailDirection.setImageResource(R.drawable.ic_call_outgoing)
                    tvDetailStatusText.text = "Outgoing $typeStr"
                }
                CallStatus.MISSED -> {
                    ivDetailDirection.setImageResource(R.drawable.ic_call_missed)
                    tvDetailStatusText.text = "Missed $typeStr"
                }
            }

            // Date & Exact Timestamp
            val fullDateFormat = SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
            tvDetailTimestamp.text = fullDateFormat.format(Date(record.timestamp))

            // Duration
            if (record.status == CallStatus.MISSED || record.durationSeconds <= 0) {
                tvDetailDuration.text = "Not answered"
                tvDetailDuration.setTextColor(ContextCompat.getColor(root.context, R.color.gochat_text_secondary))
            } else {
                val mins = record.durationSeconds / 60
                val secs = record.durationSeconds % 60
                tvDetailDuration.text = "Duration: ${if (mins > 0) "${mins}m " else ""}${secs}s"
                tvDetailDuration.setTextColor(ContextCompat.getColor(root.context, R.color.gochat_accent))
            }

            // Action: Voice Call
            btnDetailVoiceCall.setOnClickListener {
                dialog.dismiss()
                startCallAndNavigate(record.peerId, record.peerName, record.peerAvatar, "voice")
            }

            // Action: Video Call
            btnDetailVideoCall.setOnClickListener {
                dialog.dismiss()
                startCallAndNavigate(record.peerId, record.peerName, record.peerAvatar, "video")
            }

            // Action: Message
            btnDetailMessage.setOnClickListener {
                dialog.dismiss()
                val intent = Intent(requireContext(), ChatRoomActivity::class.java).apply {
                    putExtra(ChatRoomActivity.EXTRA_CONVERSATION_ID, record.peerId)
                    putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, record.peerName)
                }
                startActivity(intent)
            }

            // Action: Delete
            btnDetailDelete.setOnClickListener {
                dialog.dismiss()
                viewLifecycleOwner.lifecycleScope.launch {
                    callRepository.deleteCall(record.id)
                }
            }
        }

        dialog.show()
    }

    private fun showNewCallBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = BottomSheetNewCallBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        val contactAdapter = ContactCallAdapter(
            onVoiceCall = { conv ->
                dialog.dismiss()
                startCallAndNavigate(
                    targetUserId = conv.id,
                    peerName = conv.title,
                    peerAvatar = conv.avatarUrl,
                    callType = "voice"
                )
            },
            onVideoCall = { conv ->
                dialog.dismiss()
                startCallAndNavigate(
                    targetUserId = conv.id,
                    peerName = conv.title,
                    peerAvatar = conv.avatarUrl,
                    callType = "video"
                )
            }
        )

        sheetBinding.rvNewCallContacts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = contactAdapter
        }

        sheetBinding.btnCloseNewCall.setOnClickListener {
            dialog.dismiss()
        }

        var allConversations = listOf<Conversation>()

        viewLifecycleOwner.lifecycleScope.launch {
            chatRepository.observeConversations().collect { convs ->
                allConversations = convs
                contactAdapter.submitList(convs)
                sheetBinding.tvNoContacts.visibility = if (convs.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        sheetBinding.etSearchContacts.doAfterTextChanged { query ->
            val q = query?.toString()?.trim().orEmpty()
            val filtered = if (q.isBlank()) {
                allConversations
            } else {
                allConversations.filter { it.title.contains(q, ignoreCase = true) }
            }
            contactAdapter.submitList(filtered)
            sheetBinding.tvNoContacts.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        }

        dialog.show()
    }

    private fun startCallAndNavigate(
        targetUserId: String,
        peerName: String,
        peerAvatar: String,
        callType: String
    ) {
        val callId = "call_${System.currentTimeMillis()}"

        // Pre-insert outbound call record locally so it immediately appears in history
        val newRecord = CallRecord(
            id = callId,
            peerId = targetUserId,
            peerName = peerName.ifBlank { "GoChat Contact" },
            peerAvatar = peerAvatar,
            type = if (callType == "video") CallType.VIDEO else CallType.AUDIO,
            status = CallStatus.OUTGOING,
            durationSeconds = 0,
            timestamp = System.currentTimeMillis()
        )

        viewLifecycleOwner.lifecycleScope.launch {
            callRepository.recordCall(newRecord)
            callRepository.startCall(targetUserId, callType)
        }

        // Launch CallActivity with full metadata
        val intent = Intent(requireContext(), CallActivity::class.java).apply {
            putExtra(CallActivity.EXTRA_CALL_ID, callId)
            putExtra(CallActivity.EXTRA_TARGET_USER_ID, targetUserId)
            putExtra(CallActivity.EXTRA_PEER_NAME, peerName)
            putExtra(CallActivity.EXTRA_PEER_AVATAR, peerAvatar)
            putExtra(CallActivity.EXTRA_IS_OUTGOING, true)
            putExtra(CallActivity.EXTRA_CALL_TYPE, callType)
        }
        startActivity(intent)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refreshCallHistory()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
