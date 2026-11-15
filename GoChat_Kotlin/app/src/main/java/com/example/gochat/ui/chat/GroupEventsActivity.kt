package com.example.gochat.ui.chat

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.data.model.GroupEvent
import com.example.gochat.data.repository.GroupRepository
import com.example.gochat.databinding.ActivityGroupEventsBinding
import com.example.gochat.databinding.ItemGroupEventBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Group events: list scheduled events, create new ones, and RSVP (going / maybe). */
@AndroidEntryPoint
class GroupEventsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    }

    @Inject
    lateinit var groupRepository: GroupRepository

    private lateinit var binding: ActivityGroupEventsBinding
    private val convId by lazy { intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty() }
    private val dateFmt by lazy { SimpleDateFormat("EEE, d MMM yyyy 'at' h:mm a", Locale.getDefault()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupEventsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (convId.isBlank()) { finish(); return }

        binding.btnBackEvents.setOnClickListener { finish() }
        binding.btnNewEvent.setOnClickListener { showCreateEventDialog() }
        loadEvents()
    }

    private fun loadEvents() {
        lifecycleScope.launch {
            val events = withContext(Dispatchers.IO) {
                groupRepository.listGroupEvents(convId).getOrNull() ?: emptyList()
            }
            renderEvents(events)
        }
    }

    private fun renderEvents(events: List<GroupEvent>) {
        binding.containerEvents.removeAllViews()
        binding.tvEventsEmpty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        events.forEach { ev ->
            val item = ItemGroupEventBinding.inflate(layoutInflater, binding.containerEvents, false)
            item.tvEventTitle.text = ev.title
            item.tvEventTime.text = "🕒 " + dateFmt.format(Date(ev.eventTime * 1000))
            if (ev.location.isNotBlank()) {
                item.tvEventLocation.visibility = View.VISIBLE
                item.tvEventLocation.text = "📍 " + ev.location
            }
            item.tvEventCounts.text = "${ev.goingCount} going · ${ev.maybeCount} maybe"
            item.btnEventGoing.text = if (ev.myResponse == "going") "Going ✓" else "Going"
            item.btnEventMaybe.text = if (ev.myResponse == "maybe") "Maybe ✓" else "Maybe"
            item.btnEventGoing.setOnClickListener {
                rsvp(ev.id, if (ev.myResponse == "going") "none" else "going")
            }
            item.btnEventMaybe.setOnClickListener {
                rsvp(ev.id, if (ev.myResponse == "maybe") "none" else "maybe")
            }
            binding.containerEvents.addView(item.root)
        }
    }

    private fun rsvp(eventId: String, response: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { groupRepository.rsvpGroupEvent(convId, eventId, response) }
            loadEvents()
        }
    }

    private fun showCreateEventDialog() {
        val input = EditText(this).apply {
            hint = "Event title"
            isSingleLine = true
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle("New event")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val title = input.text?.toString()?.trim().orEmpty()
                if (title.isBlank()) {
                    Toast.makeText(this, "Enter an event title", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pickDateTime(title)
            }
            .setNegativeButton(getString(com.example.gochat.R.string.btn_cancel), null)
            .show()
    }

    private fun pickDateTime(title: String) {
        val now = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                val cal = Calendar.getInstance().apply { set(year, month, day, hour, minute, 0) }
                createEvent(title, cal.timeInMillis)
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun createEvent(title: String, timeMillis: Long) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                groupRepository.createGroupEvent(convId, title, timeMillis)
            }
            if (result.isSuccess) {
                Toast.makeText(this@GroupEventsActivity, "Event created", Toast.LENGTH_SHORT).show()
                loadEvents()
            } else {
                Toast.makeText(this@GroupEventsActivity, result.exceptionOrNull()?.message ?: "Failed to create event", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
