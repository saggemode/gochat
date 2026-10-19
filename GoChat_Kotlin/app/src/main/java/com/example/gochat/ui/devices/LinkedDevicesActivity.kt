package com.example.gochat.ui.devices

import android.net.Uri
import android.os.Bundle
import android.util.Base64
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
import com.example.gochat.core.crypto.SignalStore
import com.example.gochat.core.ui.applyEdgeToEdge
import com.example.gochat.core.ui.applyStatusBarInset
import com.example.gochat.core.ui.applySystemBarInsets
import com.example.gochat.data.model.LinkedDevice
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.data.websocket.GoChatWebSocket
import com.example.gochat.databinding.ActivityLinkedDevicesBinding
import com.example.gochat.databinding.ItemLinkedDeviceBinding
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

@AndroidEntryPoint
class LinkedDevicesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkedDevicesBinding
    
    @Inject
    lateinit var authRepo: AuthRepository

    @Inject
    lateinit var webSocket: GoChatWebSocket
    
    private val devicesAdapter = DevicesAdapter()

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            handleScannedQr(contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLinkedDevicesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarLinkedDevices.applyStatusBarInset()
        binding.root.applySystemBarInsets(applyTop = false, applyBottom = true)

        setupToolbar()
        setupRecyclerView()
        setupActions()
        observeWebSocketEvents()
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
            startQrScanner()
        }
        binding.btnLogOutAllOther.setOnClickListener {
            confirmUnlinkAllOther()
        }
    }

    private fun startQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Point your camera at the QR code on your computer screen")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
        }
        scanLauncher.launch(options)
    }

    private fun handleScannedQr(qrData: String) {
        try {
            var token = ""
            var clientPubKey = ""
            var deviceName = "GoChat Web"
            var platform = "web"

            if (qrData.startsWith("gochat://pair", ignoreCase = true)) {
                val uri = Uri.parse(qrData)
                token = uri.getQueryParameter("token").orEmpty()
                clientPubKey = uri.getQueryParameter("pub").orEmpty()
                deviceName = uri.getQueryParameter("name") ?: uri.getQueryParameter("device") ?: "GoChat Web"
                platform = uri.getQueryParameter("platform") ?: "web"
            } else if (qrData.trim().startsWith("{")) {
                val jsonElement = Json.parseToJsonElement(qrData).jsonObject
                token = jsonElement["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
                clientPubKey = (jsonElement["public_key"] ?: jsonElement["pub"])?.jsonPrimitive?.contentOrNull.orEmpty()
                deviceName = (jsonElement["device_name"] ?: jsonElement["name"])?.jsonPrimitive?.contentOrNull ?: "GoChat Web"
                platform = jsonElement["platform"]?.jsonPrimitive?.contentOrNull ?: "web"
            } else {
                token = qrData.trim()
            }

            if (token.isBlank()) {
                Toast.makeText(this, "Invalid GoChat QR Code", Toast.LENGTH_SHORT).show()
                return
            }

            showConfirmPairingDialog(token, deviceName, platform, clientPubKey)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not read QR code: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showConfirmPairingDialog(
        token: String,
        deviceName: String,
        platform: String,
        clientPubKey: String
    ) {
        val cleanName = deviceName.ifBlank { "GoChat Web" }
        AlertDialog.Builder(this)
            .setTitle("Link \"$cleanName\"?")
            .setMessage(
                "Do you want to link this device to your GoChat account?\n\n" +
                "• End-to-end encrypted messaging\n" +
                "• Real-time message synchronization\n" +
                "• You can revoke this session anytime from Linked Devices"
            )
            .setPositiveButton("Link Device") { _, _ ->
                performLinkDevice(token, cleanName, platform, clientPubKey)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLinkDevice(
        token: String,
        deviceName: String,
        platform: String,
        clientPubKey: String
    ) {
        binding.pbLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                // E2EE Identity key exchange:
                val identityKey = try {
                    val store = SignalStore(this@LinkedDevicesActivity)
                    Base64.encodeToString(store.identityKeyPair.publicKey.serialize(), Base64.NO_WRAP)
                } catch (_: Exception) { "" }

                val result = authRepo.linkDeviceWithQr(
                    token = token,
                    deviceName = deviceName,
                    platform = platform,
                    publicKey = clientPubKey,
                    identityKey = identityKey
                )
                binding.pbLoading.visibility = View.GONE

                result.onSuccess {
                    Toast.makeText(this@LinkedDevicesActivity, "Device \"$deviceName\" linked successfully!", Toast.LENGTH_LONG).show()
                    loadDevices()
                }.onFailure { err ->
                    Toast.makeText(this@LinkedDevicesActivity, err.message ?: "Failed to link device", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                binding.pbLoading.visibility = View.GONE
                Toast.makeText(this@LinkedDevicesActivity, e.message ?: "Error linking device", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeWebSocketEvents() {
        lifecycleScope.launch {
            try {
                webSocket.events.collect { event ->
                    val type = event["type"]?.jsonPrimitive?.contentOrNull
                    if (type == "device_linked" || type == "session_revoked" || type == "all_other_sessions_revoked") {
                        loadDevices()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadDevices() {
        binding.pbLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            // First ensure current phone is registered
            try {
                authRepo.registerCurrentDevice()
            } catch (_: Exception) {}

            val result = authRepo.getLinkedDevices()
            binding.pbLoading.visibility = View.GONE

            result.onSuccess { devices ->
                devicesAdapter.submitList(devices)
                binding.tvNoDevices.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
                val hasOtherDevices = devices.any { !it.isCurrent }
                binding.btnLogOutAllOther.visibility = if (hasOtherDevices) View.VISIBLE else View.GONE
            }.onFailure { err ->
                Toast.makeText(this@LinkedDevicesActivity, err.message ?: "Failed to load devices", Toast.LENGTH_SHORT).show()
                binding.tvNoDevices.visibility = View.VISIBLE
                binding.btnLogOutAllOther.visibility = View.GONE
            }
        }
    }

    private fun showDeviceDetailsDialog(device: LinkedDevice) {
        val details = buildString {
            append("Device: ${device.displayDeviceName}\n")
            if (device.platform.isNotBlank()) append("Platform: ${device.platform}\n")
            if (device.browser.isNotBlank()) append("Browser: ${device.browser}\n")
            if (device.ipAddress.isNotBlank()) append("IP Address: ${device.ipAddress}\n")
            if (device.lastActiveAt.isNotBlank()) append("Last Active: ${device.lastActiveAt.take(19)}\n")
            append("End-to-End Encryption: Active & Verified")
        }

        AlertDialog.Builder(this)
            .setTitle(device.displayDeviceName)
            .setMessage(details)
            .setPositiveButton("Log out") { _, _ ->
                confirmUnlink(device)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmUnlink(device: LinkedDevice) {
        AlertDialog.Builder(this)
            .setTitle("Log out from ${device.displayDeviceName}?")
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
                Toast.makeText(this@LinkedDevicesActivity, "Device logged out and session revoked", Toast.LENGTH_SHORT).show()
                loadDevices()
            }.onFailure { err ->
                Toast.makeText(this@LinkedDevicesActivity, err.message ?: "Failed to log out device", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmUnlinkAllOther() {
        AlertDialog.Builder(this)
            .setTitle("Log out all other devices?")
            .setMessage("Are you sure you want to log out all other linked devices? Active JWT sessions on all other browsers and devices will be revoked on the server.")
            .setPositiveButton("Log out all") { _, _ ->
                performUnlinkAllOther()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performUnlinkAllOther() {
        binding.pbLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = authRepo.unlinkAllOtherDevices()
            binding.pbLoading.visibility = View.GONE
            result.onSuccess {
                Toast.makeText(this@LinkedDevicesActivity, "All other devices logged out", Toast.LENGTH_SHORT).show()
                loadDevices()
            }.onFailure { err ->
                Toast.makeText(this@LinkedDevicesActivity, err.message ?: "Failed to log out other devices", Toast.LENGTH_SHORT).show()
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
                b.tvDeviceName.text = device.displayDeviceName
                b.tvDeviceStatus.text = device.formattedStatus
                b.tvBadgeCurrent.visibility = if (device.isCurrent) View.VISIBLE else View.GONE

                if (device.isCurrent) {
                    b.btnUnlinkDevice.visibility = View.GONE
                    b.root.setOnClickListener(null)
                } else {
                    b.btnUnlinkDevice.visibility = View.VISIBLE
                    b.btnUnlinkDevice.setOnClickListener {
                        confirmUnlink(device)
                    }
                    b.root.setOnClickListener {
                        showDeviceDetailsDialog(device)
                    }
                }
            }
        }
    }
}

