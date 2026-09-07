package com.example.gochat.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupCreateViewModel @Inject constructor(
    application: Application,
    private val chatRepository: ChatRepository
) : AndroidViewModel(application) {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvent: SharedFlow<String> = _errorEvent.asSharedFlow()

    private val _successEvent = MutableSharedFlow<Conversation>(extraBufferCapacity = 1)
    val successEvent: SharedFlow<Conversation> = _successEvent.asSharedFlow()

    fun createGroup(name: String, memberIds: List<String>) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = chatRepository.createConversation(
                name = name,
                memberIds = memberIds,
                isGroup = true
            )
            _isLoading.value = false
            
            result.fold(
                onSuccess = { _successEvent.tryEmit(it) },
                onFailure = { _errorEvent.tryEmit(it.message ?: "Failed to create group") }
            )
        }
    }
}
