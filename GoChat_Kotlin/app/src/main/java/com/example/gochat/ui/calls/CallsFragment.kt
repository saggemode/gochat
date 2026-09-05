package com.example.gochat.ui.calls

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.data.model.CallType
import com.example.gochat.data.repository.CallRepository
import com.example.gochat.data.api.ApiConstants
import com.example.gochat.databinding.FragmentCallsBinding
import kotlinx.coroutines.launch

class CallsFragment : Fragment() {

    private var _binding: FragmentCallsBinding? = null
    private val binding get() = _binding!!

    private lateinit var callRepository: CallRepository
    private lateinit var callAdapter: CallAdapter

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
        callRepository = CallRepository(requireContext())

        setupRecyclerView()
        setupListeners()
        observeCalls()
    }

    override fun onResume() {
        super.onResume()
        refreshCallHistory()
    }

    private fun setupRecyclerView() {
        callAdapter = CallAdapter { record ->
            viewLifecycleOwner.lifecycleScope.launch {
                val callType = if (record.type == CallType.VIDEO) "video" else "voice"
                Toast.makeText(requireContext(), "Starting $callType call with ${record.peerName}...", Toast.LENGTH_SHORT).show()
                val result = callRepository.startCall(record.peerId, callType)
                result.onFailure {
                    Toast.makeText(requireContext(), it.localizedMessage ?: "Failed to start call", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.rvCalls.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = callAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupListeners() {
        binding.swipeRefreshCalls.setOnRefreshListener {
            refreshCallHistory()
        }

        binding.tileCreateCallLink.setOnClickListener {
            val linkId = System.currentTimeMillis().toString().takeLast(6)
            val callLink = "${ApiConstants.BASE_URL}/call/room_$linkId"

            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("GoChat Call Link", callLink)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(requireContext(), "🔗 Call link generated & copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeCalls() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                callRepository.observeCalls().collect { calls ->
                    if (calls.isEmpty()) {
                        binding.layoutEmptyCalls.visibility = View.VISIBLE
                        binding.rvCalls.visibility = View.GONE
                        binding.tvRecentLabel.visibility = View.GONE
                    } else {
                        binding.layoutEmptyCalls.visibility = View.GONE
                        binding.rvCalls.visibility = View.VISIBLE
                        binding.tvRecentLabel.visibility = View.VISIBLE
                        callAdapter.submitList(calls)
                    }
                }
            }
        }
    }

    private fun refreshCallHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.swipeRefreshCalls.isRefreshing = true
            callRepository.refreshCallHistory()
            binding.swipeRefreshCalls.isRefreshing = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
