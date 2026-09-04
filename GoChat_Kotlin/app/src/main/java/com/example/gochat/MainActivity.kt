package com.example.gochat

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val chatViewModel: ChatListViewModel by viewModels()

    private val chatListFragment by lazy { ChatListFragment() }
    private val storiesFragment by lazy { StoriesFragment() }
    private val callsFragment by lazy { CallsFragment() }
    private val settingsFragment by lazy { SettingsFragment() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tokenManager = TokenManager.getInstance(this)
        if (!tokenManager.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            switchFragment(chatListFragment)
        }

        setupBottomNavigation()
        observeUnreadBadge()
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