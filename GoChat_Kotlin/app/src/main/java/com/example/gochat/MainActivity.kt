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

    companion object {
        private const val KEY_ACTIVE_TAB = "key_active_tab"
        private const val TAG_CHATS = "tab_chats"
        private const val TAG_STATUS = "tab_status"
        private const val TAG_MARKETPLACE = "tab_marketplace"
        private const val TAG_CALLS = "tab_calls"
        private const val TAG_SETTINGS = "tab_settings"
    }

    private var currentTabTag: String = TAG_CHATS

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
                switchTab(TAG_CHATS)
            }
        } else {
            currentTabTag = savedInstanceState.getString(KEY_ACTIVE_TAB, TAG_CHATS)
            val transaction = supportFragmentManager.beginTransaction()
            for (fragment in supportFragmentManager.fragments) {
                val fTag = fragment.tag
                if (fTag != null) {
                    if (fTag == currentTabTag) {
                        transaction.show(fragment)
                    } else {
                        transaction.hide(fragment)
                    }
                }
            }
            transaction.commit()
        }

        setupBottomNavigation()
        observeUnreadBadge()

        // Initialize E2EE Keys
        lifecycleScope.launch {
            encryptionManager.initializeAndRegisterKeys()
        }

        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_ACTIVE_TAB, currentTabTag)
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
                    switchTab(TAG_CHATS)
                    true
                }
                R.id.nav_status -> {
                    switchTab(TAG_STATUS)
                    true
                }
                R.id.nav_marketplace -> {
                    switchTab(TAG_MARKETPLACE)
                    true
                }
                R.id.nav_calls -> {
                    switchTab(TAG_CALLS)
                    true
                }
                R.id.nav_settings -> {
                    switchTab(TAG_SETTINGS)
                    true
                }
                else -> false
            }
        }
    }

    private fun switchTab(tag: String) {
        val fragmentManager = supportFragmentManager
        val transaction = fragmentManager.beginTransaction()

        val currentFragment = fragmentManager.findFragmentByTag(currentTabTag)
        var targetFragment = fragmentManager.findFragmentByTag(tag)

        if (targetFragment != null && targetFragment.isAdded && !targetFragment.isHidden && currentTabTag == tag) {
            return
        }

        if (currentFragment != null && currentFragment != targetFragment) {
            transaction.hide(currentFragment)
        }

        if (targetFragment == null) {
            targetFragment = when (tag) {
                TAG_CHATS -> ChatListFragment()
                TAG_STATUS -> StoriesFragment()
                TAG_MARKETPLACE -> MarketplaceFragment()
                TAG_CALLS -> CallsFragment()
                TAG_SETTINGS -> SettingsFragment()
                else -> ChatListFragment()
            }
            transaction.add(R.id.fragmentContainer, targetFragment, tag)
        } else {
            transaction.show(targetFragment)
        }

        currentTabTag = tag
        transaction.commit()
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
                    switchTab(TAG_CHATS)
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