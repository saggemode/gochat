package com.example.gochat.ui.calls

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.gochat.R
import com.example.gochat.databinding.FragmentSimpleTabBinding

class CallsFragment : Fragment() {

    private var _binding: FragmentSimpleTabBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSimpleTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            ivTabPlaceholder.setImageResource(R.drawable.ic_tab_calls)
            tvTabTitle.text = "Call History"
            tvTabSubtitle.text = "To start a VoIP call, tap on a contact or open a chat and tap the call button."
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
