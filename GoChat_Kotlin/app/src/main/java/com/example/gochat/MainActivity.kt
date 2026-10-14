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
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.gochat.core.ui.EdgeToEdgeHelper.applyEdgeToEdge
import com.example.gochat.data.api.TokenManager
import com.example.gochat.databinding.ActivityMainBinding
import com.example.gochat.ui.auth.LoginActivity
import com.example.gochat.ui.calls.CallActivity
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

    @Inject
    lateinit var callRepository: com.example.gochat.data.repository.CallRepository

    companion object {
        const val KEY_ACTIVE_TAB = "key_active_tab"
        const val TAG_CHATS = "tab_chats"
        const val TAG_STATUS = "tab_status"
        const val TAG_MARKETPLACE = "tab_marketplace"
        const val TAG_CALLS = "tab_calls"
        const val TAG_SETTINGS = "tab_settings"
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
        applyEdgeToEdge()
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

        setupWindowInsets()

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
        observeCallsBadge()
        observeIncomingCalls()

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

    /**
     * Handles window insets so that the bottom navigation bar does not overlap
     * with the system navigation bar (gesture handle / 3-button nav) on all
     * manufacturers (Samsung, Pixel, Xiaomi, OnePlus, etc.).
     */
    private fun setupWindowInsets() {
        val initialFragmentTop = binding.fragmentContainer.paddingTop
        val initialFragmentBottom = binding.fragmentContainer.paddingBottom
        val initialBottomNavBottom = binding.bottomNav.paddingBottom
        val initialMainLeft = binding.main.paddingLeft
        val initialMainRight = binding.main.paddingRight

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            // When keyboard is open, hide bottom navigation so it doesn't jump above keyboard
            val isKeyboardOpen = imeInsets.bottom > 0
            binding.bottomNav.visibility = if (isKeyboardOpen) View.GONE else View.VISIBLE
            binding.bottomNavDivider.visibility = if (isKeyboardOpen) View.GONE else View.VISIBLE

            // Pad the fragment container at top (status bar/notch) and bottom (IME if open)
            val bottomPadding = if (isKeyboardOpen) imeInsets.bottom else initialFragmentBottom
            binding.fragmentContainer.updatePadding(
                top = initialFragmentTop + insets.top,
                bottom = bottomPadding
            )

            // Pad the bottom navigation at the bottom so it clears Samsung 3-button nav / gesture bar
            binding.bottomNav.updatePadding(bottom = initialBottomNavBottom + insets.bottom)

            // Handle side insets for landscape / foldables
            view.updatePadding(
                left = initialMainLeft + insets.left,
                right = initialMainRight + insets.right
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.main)
    }

    fun navigateToTab(tag: String) {
        when (tag) {
            TAG_CHATS -> binding.bottomNav.selectedItemId = R.id.nav_chats
            TAG_STATUS -> binding.bottomNav.selectedItemId = R.id.nav_status
            TAG_CALLS -> binding.bottomNav.selectedItemId = R.id.nav_calls
            TAG_SETTINGS -> binding.bottomNav.selectedItemId = R.id.nav_settings
            else -> switchTab(tag)
        }
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

    private fun observeCallsBadge() {
        val callsBadge = binding.bottomNav.getOrCreateBadge(R.id.nav_calls).apply {
            backgroundColor = ContextCompat.getColor(this@MainActivity, R.color.gochat_error)
            badgeTextColor = ContextCompat.getColor(this@MainActivity, R.color.white)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                callRepository.observeMissedCallsCount().collect { missedCount ->
                    if (missedCount > 0) {
                        callsBadge.isVisible = true
                        callsBadge.number = missedCount
                    } else {
                        callsBadge.isVisible = false
                    }
                }
            }
        }
    }

    private fun observeIncomingCalls() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                chatViewModel.incomingCallEvent.collect { event ->
                    val intent = Intent(this@MainActivity, CallActivity::class.java).apply {
                        putExtra(CallActivity.EXTRA_CALL_ID, event.callId)
                        putExtra(CallActivity.EXTRA_TARGET_USER_ID, event.callerId)
                        putExtra(CallActivity.EXTRA_PEER_NAME, event.callerName)
                        putExtra(CallActivity.EXTRA_PEER_AVATAR, event.callerAvatar)
                        putExtra(CallActivity.EXTRA_IS_OUTGOING, false)
                        putExtra(CallActivity.EXTRA_CALL_TYPE, event.callType)
                        putExtra(CallActivity.EXTRA_AUTO_ACCEPT, false)
                    }
                    startActivity(intent)
                }
            }
        }
    }
}