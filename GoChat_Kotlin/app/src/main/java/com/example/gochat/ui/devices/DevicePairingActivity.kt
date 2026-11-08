package com.example.gochat.ui.devices

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.core.crypto.DevicePairingPayload
import com.example.gochat.core.crypto.EncryptionManager
import com.example.gochat.core.ui.applyEdgeToEdge
import com.example.gochat.core.ui.applyStatusBarInset
import com.example.gochat.core.ui.applySystemBarInsets
import com.example.gochat.data.api.TokenManager
import com.example.gochat.databinding.ActivityDevicePairingBinding
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * E2EE multi-device pairing (Phase 3). Both devices are already logged into the same
 * account; a companion also publishes its own Signal keys at startup. This screen is
 * the human-verified trust step: each device shows a QR of its own identity, and the
 * existing device scans the companion's, verifies its identity fingerprint against the
 * server's stored record (MITM / key-swap detection), and authorizes it — flipping the
 * account to multi-device so sends fan out to every device.
 */
@AndroidEntryPoint
class DevicePairingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDevicePairingBinding

    @Inject lateinit var encryptionManager: EncryptionManager
    @Inject lateinit var tokenManager: TokenManager

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) handleScanned(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityDevicePairingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarPairing.applyStatusBarInset()
        binding.root.applySystemBarInsets(applyTop = false, applyBottom = true)
        binding.toolbarPairing.setNavigationOnClickListener { finish() }

        binding.btnScanOther.setOnClickListener { startScanner() }

        showOwnQr()
    }

    private fun showOwnQr() {
        val userId = tokenManager.userId.orEmpty()
        val identityB64 = encryptionManager.localDeviceIdentityB64()
        val fingerprint = encryptionManager.identityFingerprint(identityB64)
        val deviceNumber = encryptionManager.localDeviceNumber()

        val payload = DevicePairingPayload(
            userId = userId,
            deviceNumber = deviceNumber,
            deviceId = tokenManager.getDeviceId(),
            fingerprint = fingerprint,
            identityKeyB64 = identityB64
        )

        binding.tvThisDevice.text = "This device • " +
            if (deviceNumber <= 1) "Primary device" else "Device $deviceNumber"
        binding.tvFingerprint.text = fingerprint.ifBlank { "unavailable" }

        try {
            val bitmap = BarcodeEncoder().encodeBitmap(payload.toQrString(), BarcodeFormat.QR_CODE, 600, 600)
            binding.ivQrCode.setImageBitmap(bitmap)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not render QR code", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the QR code shown on your other device")
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        scanLauncher.launch(options)
    }

    private fun handleScanned(data: String) {
        val payload = DevicePairingPayload.parse(data)
        if (payload == null) {
            Toast.makeText(this, "That isn't a GoChat device code", Toast.LENGTH_SHORT).show()
            return
        }
        val myUserId = tokenManager.userId.orEmpty()
        if (myUserId.isBlank() || payload.userId != myUserId) {
            Toast.makeText(this, "That device is signed in to a different account", Toast.LENGTH_LONG).show()
            return
        }
        if (payload.deviceId == tokenManager.getDeviceId()) {
            Toast.makeText(this, "That's this device", Toast.LENGTH_SHORT).show()
            return
        }
        verifyAndConfirm(payload)
    }

    /** Verify the scanned fingerprint against the server's stored identity for that
     *  device, then ask the user to confirm the codes match on both screens. */
    private fun verifyAndConfirm(payload: DevicePairingPayload) {
        binding.pbLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val serverIdentity = encryptionManager.serverIdentityForOwnDevice(payload.deviceNumber)
            val serverFingerprint = encryptionManager.identityFingerprint(serverIdentity)
            binding.pbLoading.visibility = View.GONE

            val scannedFp = payload.fingerprint
            // Trust checks: (1) the scanned fingerprint must match the full identity in
            // the QR, and (2) it must match what the server stores for that device.
            val selfConsistent = scannedFp.isBlank() ||
                scannedFp == encryptionManager.identityFingerprint(payload.identityKeyB64)
            val matchesServer = serverFingerprint.isNotBlank() && serverFingerprint == scannedFp

            val warning = when {
                !selfConsistent -> "⚠️ This code is inconsistent — do not trust it."
                serverFingerprint.isBlank() -> "⚠️ Couldn't confirm this device with the server. Only link if you recognize it."
                !matchesServer -> "⚠️ The security code does NOT match the server record. This could indicate tampering — do not link."
                else -> null
            }

            val deviceLabel = if (payload.deviceNumber <= 1) "Primary device" else "Device ${payload.deviceNumber}"
            val message = buildString {
                append("Device: $deviceLabel\n")
                append("Security code: ${scannedFp.ifBlank { "unavailable" }}\n\n")
                if (warning != null) append("$warning\n\n")
                append("Make sure this code matches the one shown on the other device, then link it. Linked devices receive a copy of your messages.")
            }

            val builder = AlertDialog.Builder(this@DevicePairingActivity)
                .setTitle("Link $deviceLabel?")
                .setMessage(message)
                .setNegativeButton("Cancel", null)
            // Only offer a plain "Link" when everything verified; otherwise require an
            // explicit "Link anyway" so the user acknowledges the warning.
            if (warning == null) {
                builder.setPositiveButton("Link device") { _, _ -> confirmPairing(payload) }
            } else {
                builder.setPositiveButton("Link anyway") { _, _ -> confirmPairing(payload) }
            }
            builder.show()
        }
    }

    private fun confirmPairing(payload: DevicePairingPayload) {
        encryptionManager.recordPairedDevice(payload.deviceId, payload.identityKeyB64)
        Toast.makeText(this, "Device linked. Your messages will now sync to it.", Toast.LENGTH_LONG).show()
        finish()
    }
}
