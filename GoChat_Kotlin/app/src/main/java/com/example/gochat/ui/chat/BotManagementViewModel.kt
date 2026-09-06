package com.example.gochat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.gochat.data.model.BotConfig
import com.example.gochat.data.model.BotPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BotManagementViewModel : ViewModel() {

    private val _botConfig = MutableStateFlow<BotConfig?>(null)
    val botConfig: StateFlow<BotConfig?> = _botConfig

    fun loadBotConfig(convId: String) {
        viewModelScope.launch {
            // Fetch from repository/API
            // For now, mock data
            _botConfig.value = BotConfig(
                botId = "bot_123",
                groupId = convId,
                permissions = listOf(BotPermission.ANTI_SPAM, BotPermission.WELCOME_MEMBERS)
            )
        }
    }

    fun saveBotConfig(config: BotConfig) {
        viewModelScope.launch {
            // Save to repository/API
        }
    }
}
