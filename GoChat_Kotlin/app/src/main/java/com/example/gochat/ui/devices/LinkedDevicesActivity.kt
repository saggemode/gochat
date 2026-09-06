package com.example.gochat.ui.devices

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.data.repository.LinkedDevice
import com.example.gochat.databinding.ActivityLinkedDevicesBinding
import com.example.gochat.databinding.ItemLinkedDeviceBinding
import kotlinx.coroutines.launch

class LinkedDevicesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkedDevicesBinding
    private lateinit var authRepo: AuthRepository
    private val devicesAdapter = DevicesAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLinkedDevicesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authRepo = AuthRepository(this)

        setupToolbar()
        setupRecyclerView()
        setupActions()
        loadDevices()
    }

    private fun setupToolbar() {
        binding.toolbarLinkedDevices.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = devicesAdapter
    }

    private fun setupActions() {
        binding.btnLinkDevice.setOnClickListener {
            showLinkDeviceDialog()
        }
    }

    private fun loadDevices() {
        binding.pbLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            // First register current phone
            try {
                authRepo.registerCurrentDevice()
            } catch (_: Exception) {}

            val result = authRepo.getLinkedDevices()
            binding.pbLoading.visibility = View.GONE

            result.onSuccess { devices ->
                devicesAdapter.submitList(devices)
                binding.tvNoDevices.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
            }.onFailure { err ->
                Toast.makeText(this@LinkedDevicesActivity, err.message ?: "Failed to load devices", Toast.LENGTH_SHORT).show()
                binding.tvNoDevices.visibility = View.VISIBLE
            }
        }
    }

    private fun showLinkDeviceDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Link a Device")
            .setMessage("To use GoChat on Web or Desktop:\n\n1. Open web.gochat.app or GoChat Desktop on your other device\n2. Scan the QR code displayed on that screen\n3. Your chats and media will sync automatically with end-to-end encryption.")
            .setPositiveButton("Got it", null)
            .create()
        dialog.show()
    }

    private fun confirmUnlink(device: LinkedDevice) {
        AlertDialog.Builder(this)
            .setTitle("Log out from ${device.deviceName}?")
            .setMessage("Are you sure you want to log out from this device? You will need to link it again to use GoChat on it.")
            .setPositiveButton("Log out") { _, _ ->
                performUnlink(device.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performUnlink(deviceId: String) {
        binding.pbLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = authRepo.unlinkDevice(deviceId)
            binding.pbLoading.visibility = View.GONE
            result.onSuccess {
                Toast.makeText(this@LinkedDevicesActivity, "Device logged out", Toast.LENGTH_SHORT).show()
                loadDevices()
            }.onFailure { err ->
                Toast.makeText(this@LinkedDevicesActivity, err.message ?: "Failed to log out device", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Adapter ──────────────────────────────────────────────────

    private inner class DevicesAdapter : RecyclerView.Adapter<DevicesAdapter.ViewHolder>() {

        private val items = mutableListOf<LinkedDevice>()

        fun submitList(newList: List<LinkedDevice>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemLinkedDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val b: ItemLinkedDeviceBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(device: LinkedDevice) {
                b.tvDeviceName.text = device.deviceName

                val statusText = buildString {
                    if (device.isCurrent) {
                        append("Active now • This phone")
                    } else if (device.lastActiveAt.isNotBlank()) {
                        append("Last active: ${device.lastActiveAt.take(10)}")
                    } else {
                        append("Linked")
                    }
                    if (device.ipAddress.isNotBlank()) {
                        append(" • ${device.ipAddress}")
                    }
                }
                b.tvDeviceStatus.text = statusText
                b.tvBadgeCurrent.visibility = if (device.isCurrent) View.VISIBLE else View.GONE

                if (device.isCurrent) {
                    b.btnUnlinkDevice.visibility = View.GONE
                } else {
                    b.btnUnlinkDevice.visibility = View.VISIBLE
                    b.btnUnlinkDevice.setOnClickListener {
                        confirmUnlink(device)
                    }
                }
            }
        }
    }
}
