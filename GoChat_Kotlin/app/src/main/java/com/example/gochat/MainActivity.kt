package com.example.gochat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.gochat.data.api.TokenManager
import com.example.gochat.databinding.ActivityMainBinding
import com.example.gochat.ui.auth.LoginActivity
import com.example.gochat.ui.calls.CallsFragment
import com.example.gochat.ui.chat.ChatListFragment
import com.example.gochat.ui.chat.ChatListViewModel
import com.example.gochat.ui.settings.SettingsFragment
import com.example.gochat.ui.stories.StoriesFragment
import com.example.gochat.ui.marketplace.MarketplaceFragment
import com.example.gochat.core.crypto.EncryptionManager
import com.example.gochat.ui.marketplace.ProductDetailsActivity
import com.example.gochat.ui.marketplace.StorefrontActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val chatViewModel: ChatListViewModel by viewModels()
    
    @Inject
    lateinit var encryptionManager: EncryptionManager
    
    @Inject
    lateinit var tokenManager: TokenManager

    private val chatListFragment by lazy { ChatListFragment() }
    private val storiesFragment by lazy { StoriesFragment() }
    private val marketplaceFragment by lazy { MarketplaceFragment() }
    private val callsFragment by lazy { CallsFragment() }
    private val settingsFragment by lazy { SettingsFragment() }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Log.w("MainActivity", "POST_NOTIFICATIONS permission not granted by user")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!tokenManager.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkNotificationPermission()

        if (savedInstanceState == null) {
            if (tokenManager.isBiometricLockEnabled) {
                showBiometricPrompt()
            } else {
                switchFragment(chatListFragment)
            }
        }

        setupBottomNavigation()
        observeUnreadBadge()

        // Initialize E2EE Keys
        lifecycleScope.launch {
            encryptionManager.initializeAndRegisterKeys()
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        
        // gochat://store/{pin}
        if (data.scheme == "gochat" && data.host == "store") {
            val pin = data.lastPathSegment
            if (!pin.isNullOrBlank()) {
                val storeIntent = Intent(this, StorefrontActivity::class.java).apply {
                    putExtra("store_id", pin)
                    putExtra("store_pin", pin)
                }
                startActivity(storeIntent)
            }
        }
        
        // gochat://product/{id}
        if (data.scheme == "gochat" && data.host == "product") {
            val productId = data.lastPathSegment
            if (!productId.isNullOrBlank()) {
                val productIntent = Intent(this, ProductDetailsActivity::class.java).apply {
                    putExtra("product_id", productId)
                }
                startActivity(productIntent)
            }
        }
    }


    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        chatViewModel.connectWebSocket()
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> {
                    switchFragment(chatListFragment)
                    true
                }
                R.id.nav_status -> {
                    switchFragment(storiesFragment)
                    true
                }
                R.id.nav_marketplace -> {
                    switchFragment(marketplaceFragment)
                    true
                }
                R.id.nav_calls -> {
                    switchFragment(callsFragment)
                    true
                }
                R.id.nav_settings -> {
                    switchFragment(settingsFragment)
                    true
                }
                else -> false
            }
        }
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        Toast.makeText(this@MainActivity, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    switchFragment(chatListFragment)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Prompt remains open on failed attempt
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_lock_title))
            .setSubtitle(getString(R.string.biometric_lock_subtitle))
            .setNegativeButtonText(getString(R.string.biometric_lock_cancel))
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun observeUnreadBadge() {
        val badge = binding.bottomNav.getOrCreateBadge(R.id.nav_chats).apply {
            backgroundColor = ContextCompat.getColor(this@MainActivity, R.color.gochat_accent)
            badgeTextColor = ContextCompat.getColor(this@MainActivity, R.color.black)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.totalUnreadCount.collect { unread ->
                    if (unread > 0) {
                        badge.isVisible = true
                        badge.number = unread
                    } else {
                        badge.isVisible = false
                    }
                }
            }
        }
    }
}