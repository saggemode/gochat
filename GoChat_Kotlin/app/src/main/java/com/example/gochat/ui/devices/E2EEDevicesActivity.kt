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
import com.example.gochat.core.ui.applyEdgeToEdge
import com.example.gochat.core.ui.applyStatusBarInset
import com.example.gochat.core.ui.applySystemBarInsets
import com.example.gochat.core.crypto.EncryptionManager
import com.example.gochat.data.model.E2EEDevice
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.databinding.ActivityE2eeDevicesBinding
import com.example.gochat.databinding.ItemE2eeDeviceBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lists the account's E2EE (Signal-key) devices — the installs that hold their own
 * encryption keys and receive a copy of every message via multi-device fan-out.
 * Distinct from [LinkedDevicesActivity], which manages web/login sessions.
 * Source: GET /auth/e2ee/devices; unlink: DELETE /auth/e2ee/devices/{device_id}.
 */
@AndroidEntryPoint
class E2EEDevicesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityE2eeDevicesBinding

    @Inject
    lateinit var authRepo: AuthRepository

    @Inject
    lateinit var encryptionManager: EncryptionManager

    private val devicesAdapter = DevicesAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityE2eeDevicesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarE2EEDevices.applyStatusBarInset()
        binding.root.applySystemBarInsets(applyTop = false, applyBottom = true)

        binding.toolbarE2EEDevices.setNavigationOnClickListener { finish() }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = devicesAdapter
        binding.btnLinkDevice.setOnClickListener {
            startActivity(android.content.Intent(this, DevicePairingActivity::class.java))
        }
        binding.btnRestoreHistory.setOnClickListener {
            com.example.gochat.ui.backup.RestoreBackupActivity.startForLinkedDevice(this)
        }
        // A companion (device_number > 1) is the case that most needs history onboarding.
        binding.tvCompanionHint.visibility =
            if (encryptionManager.localDeviceNumber() > 1) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        // Loads on first show and refreshes after returning from pairing so a newly
        // linked device appears.
        loadDevices()
    }

    private fun loadDevices() {
        binding.pbLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = authRepo.getE2EEDevices()
            binding.pbLoading.visibility = View.GONE
            result.onSuccess { devices ->
                devicesAdapter.submitList(devices)
                binding.tvNoDevices.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
                // Observing >1 registered device flips the send path to multi-device
                // fan-out (the go-live gate). Sticky; see EncryptionManager.
                if (devices.size > 1) encryptionManager.markAccountMultiDevice()
            }.onFailure { err ->
                Toast.makeText(this@E2EEDevicesActivity, err.message ?: "Failed to load devices", Toast.LENGTH_SHORT).show()
                binding.tvNoDevices.visibility = View.VISIBLE
            }
        }
    }

    private fun confirmUnlink(device: E2EEDevice) {
        AlertDialog.Builder(this)
            .setTitle("Unlink ${device.displayName}?")
            .setMessage(
                "This device will stop receiving your messages and will need to be " +
                "linked again to use GoChat. Continue?"
            )
            .setPositiveButton("Unlink") { _, _ -> performUnlink(device.deviceId) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performUnlink(deviceId: String) {
        binding.pbLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = authRepo.removeE2EEDevice(deviceId)
            binding.pbLoading.visibility = View.GONE
            result.onSuccess {
                Toast.makeText(this@E2EEDevicesActivity, "Device unlinked", Toast.LENGTH_SHORT).show()
                loadDevices()
            }.onFailure { err ->
                Toast.makeText(this@E2EEDevicesActivity, err.message ?: "Failed to unlink device", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Adapter ──────────────────────────────────────────────────

    private inner class DevicesAdapter : RecyclerView.Adapter<DevicesAdapter.ViewHolder>() {

        private val items = mutableListOf<E2EEDevice>()

        fun submitList(newList: List<E2EEDevice>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemE2eeDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val b: ItemE2eeDeviceBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(device: E2EEDevice) {
                b.tvDeviceName.text = device.displayName
                b.tvBadgeCurrent.visibility = if (device.isCurrent) View.VISIBLE else View.GONE

                val status = buildString {
                    append("ID …${device.shortId}")
                    val seen = device.lastSeen.take(19)
                    if (seen.isNotBlank()) append(" • last seen $seen")
                }
                b.tvDeviceStatus.text = status

                // The primary device can't be unlinked (server refuses too).
                if (device.isPrimary) {
                    b.btnUnlinkDevice.visibility = View.GONE
                    b.root.setOnClickListener(null)
                } else {
                    b.btnUnlinkDevice.visibility = View.VISIBLE
                    b.btnUnlinkDevice.setOnClickListener { confirmUnlink(device) }
                    b.root.setOnClickListener { confirmUnlink(device) }
                }
            }
        }
    }
}
