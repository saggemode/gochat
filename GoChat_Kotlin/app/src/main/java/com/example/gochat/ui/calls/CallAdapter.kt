package com.example.gochat.ui.calls

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.example.gochat.R
import com.example.gochat.data.model.CallRecord
import com.example.gochat.data.model.CallStatus
import com.example.gochat.data.model.CallType
import com.example.gochat.databinding.ItemCallRecordBinding
import java.text.SimpleDateFormat
import java.util.*

class CallAdapter(
    private val onCallAction: (CallRecord) -> Unit,
    private val onItemClick: ((CallRecord) -> Unit)? = null
) : ListAdapter<CallRecord, CallAdapter.CallViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallViewHolder {
        val binding = ItemCallRecordBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CallViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CallViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CallViewHolder(
        private val binding: ItemCallRecordBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(record: CallRecord) {
            with(binding) {
                tvCallPeerName.text = record.peerName

                // Direction and Status
                when (record.status) {
                    CallStatus.INCOMING -> {
                        ivCallDirection.setImageResource(R.drawable.ic_call_incoming)
                        tvCallPeerName.setTextColor(ContextCompat.getColor(root.context, R.color.gochat_text_primary))
                    }
                    CallStatus.OUTGOING -> {
                        ivCallDirection.setImageResource(R.drawable.ic_call_outgoing)
                        tvCallPeerName.setTextColor(ContextCompat.getColor(root.context, R.color.gochat_text_primary))
                    }
                    CallStatus.MISSED -> {
                        ivCallDirection.setImageResource(R.drawable.ic_call_missed)
                        tvCallPeerName.setTextColor(ContextCompat.getColor(root.context, R.color.gochat_error))
                    }
                }

                // Time format
                val timeStr = if (DateUtils.isToday(record.timestamp)) {
                    "Today, " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(record.timestamp))
                } else {
                    SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date(record.timestamp))
                }
                tvCallTime.text = timeStr

                // Avatar
                if (record.peerAvatar.isNotBlank()) {
                    ivCallAvatar.load(record.peerAvatar) {
                        crossfade(true)
                        placeholder(R.drawable.ic_account)
                        error(R.drawable.ic_account)
                        transformations(CircleCropTransformation())
                    }
                } else {
                    ivCallAvatar.setImageResource(R.drawable.ic_account)
                }

                // Action icon (Audio phone or Video camera)
                if (record.type == CallType.VIDEO) {
                    btnCallAction.setImageResource(R.drawable.ic_videocam)
                } else {
                    btnCallAction.setImageResource(R.drawable.ic_tab_calls)
                }

                btnCallAction.setOnClickListener {
                    onCallAction(record)
                }

                root.setOnClickListener {
                    onItemClick?.invoke(record)
                }
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<CallRecord>() {
            override fun areItemsTheSame(oldItem: CallRecord, newItem: CallRecord) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: CallRecord, newItem: CallRecord) =
                oldItem == newItem
        }
    }
}
