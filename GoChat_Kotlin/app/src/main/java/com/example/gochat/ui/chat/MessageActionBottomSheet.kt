package com.example.gochat.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.gochat.data.model.Message
import com.example.gochat.databinding.BottomSheetMessageActionsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class MessageActionBottomSheet(
    private val message: Message,
    private val onAction: (Action) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetMessageActionsBinding? = null
    private val binding get() = _binding!!

    enum class Action {
        REPLY, FORWARD, STAR, TRANSLATE, PIN, EDIT, EXPORT, DELETE, REACT_LIKE, REACT_HEART, REACT_LAUGH, REACT_WOW, REACT_SAD, REACT_PRAY
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetMessageActionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Enforce 15-minute message edit window (WhatsApp/Telegram standard)
        val isWithin15Minutes = (System.currentTimeMillis() - message.createdAt) <= 15 * 60 * 1000L
        val canEdit = message.isMe && !message.isDeleted && 
                (message.type == com.example.gochat.data.model.MessageType.TEXT) && 
                isWithin15Minutes
        binding.btnEdit.visibility = if (canEdit) View.VISIBLE else View.GONE

        // Translation visibility: available for text messages with content
        val canTranslate = !message.isDeleted && message.content.isNotBlank() &&
                message.type == com.example.gochat.data.model.MessageType.TEXT
        binding.btnTranslate.visibility = if (canTranslate) View.VISIBLE else View.GONE
        
        // Star toggle text
        binding.btnStar.text = if (message.isStarred) "Unstar" else "Star"

        // Pin toggle text
        binding.btnPin.text = if (message.isPinned) "Unpin from Top" else "Pin to Top"

        // Setup click listeners
        binding.btnReply.setOnClickListener { dismiss(); onAction(Action.REPLY) }
        binding.btnForward.setOnClickListener { dismiss(); onAction(Action.FORWARD) }
        binding.btnStar.setOnClickListener { dismiss(); onAction(Action.STAR) }
        binding.btnTranslate.setOnClickListener { dismiss(); onAction(Action.TRANSLATE) }
        binding.btnPin.setOnClickListener { dismiss(); onAction(Action.PIN) }
        binding.btnEdit.setOnClickListener { dismiss(); onAction(Action.EDIT) }
        binding.btnExport.setOnClickListener { dismiss(); onAction(Action.EXPORT) }
        binding.btnDelete.setOnClickListener { dismiss(); onAction(Action.DELETE) }

        // Reactions
        binding.btnReactLike.setOnClickListener { dismiss(); onAction(Action.REACT_LIKE) }
        binding.btnReactHeart.setOnClickListener { dismiss(); onAction(Action.REACT_HEART) }
        binding.btnReactLaugh.setOnClickListener { dismiss(); onAction(Action.REACT_LAUGH) }
        binding.btnReactWow.setOnClickListener { dismiss(); onAction(Action.REACT_WOW) }
        binding.btnReactSad.setOnClickListener { dismiss(); onAction(Action.REACT_SAD) }
        binding.btnReactPray.setOnClickListener { dismiss(); onAction(Action.REACT_PRAY) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "MessageActionBottomSheet"
    }
}
