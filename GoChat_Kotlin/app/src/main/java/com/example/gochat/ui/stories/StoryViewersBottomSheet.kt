package com.example.gochat.ui.stories

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.R
import com.example.gochat.data.model.StoryViewer
import com.example.gochat.databinding.BottomSheetStoryViewersBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class StoryViewersBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetStoryViewersBinding? = null
    private val binding get() = _binding!!

    private var viewers: List<StoryViewer> = emptyList()
    private var onDismissCallback: (() -> Unit)? = null

    companion object {
        fun newInstance(
            viewers: List<StoryViewer>,
            onDismiss: (() -> Unit)? = null
        ): StoryViewersBottomSheet {
            return StoryViewersBottomSheet().apply {
                this.viewers = viewers
                this.onDismissCallback = onDismiss
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetStoryViewersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        with(binding) {
            tvBottomSheetViewsTitle.text = getString(R.string.viewed_by_format, viewers.size)

            if (viewers.isEmpty()) {
                layoutNoViewers.visibility = View.VISIBLE
                rvStoryViewers.visibility = View.GONE
            } else {
                layoutNoViewers.visibility = View.GONE
                rvStoryViewers.visibility = View.VISIBLE

                rvStoryViewers.layoutManager = LinearLayoutManager(requireContext())
                rvStoryViewers.adapter = StoryViewersAdapter(viewers)
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
