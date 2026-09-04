package com.example.gochat.ui.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gochat.core.backup.BackupMetadata
import com.example.gochat.core.backup.ChatBackupManager
import com.example.gochat.core.backup.GoogleDriveBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ChatBackupViewModel(application: Application) : AndroidViewModel(application) {

    private val backupManager = ChatBackupManager(application)
    private val driveManager = GoogleDriveBackupManager(application)
    private val prefs = application.getSharedPreferences("gochat_backup_settings", Application.MODE_PRIVATE)

    private val _lastBackup = MutableStateFlow<BackupMetadata?>(null)
    val lastBackup: StateFlow<BackupMetadata?> = _lastBackup.asStateFlow()

    private val _isOperating = MutableStateFlow(false)
    val isOperating: StateFlow<Boolean> = _isOperating.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _googleAccountEmail = MutableStateFlow<String?>(null)
    val googleAccountEmail: StateFlow<String?> = _googleAccountEmail.asStateFlow()

    private val _autoBackupFrequency = MutableStateFlow("Daily")
    val autoBackupFrequency: StateFlow<String> = _autoBackupFrequency.asStateFlow()

    private val _includeMedia = MutableStateFlow(true)
    val includeMedia: StateFlow<Boolean> = _includeMedia.asStateFlow()

    init {
        loadInitialState()
    }

    private fun loadInitialState() {
        _lastBackup.value = backupManager.getLastBackupInfo()
        _autoBackupFrequency.value = prefs.getString("auto_backup_freq", "Daily") ?: "Daily"
        _includeMedia.value = prefs.getBoolean("include_media", true)

        val signedIn = driveManager.getSignedInAccount()
        _googleAccountEmail.value = signedIn?.email ?: prefs.getString("connected_google_email", null)
    }

    fun setGoogleAccount(email: String?) {
        _googleAccountEmail.value = email
        prefs.edit().putString("connected_google_email", email).apply()
    }

    fun setAutoBackupFrequency(frequency: String) {
        _autoBackupFrequency.value = frequency
        prefs.edit().putString("auto_backup_freq", frequency).apply()
    }

    fun setIncludeMedia(include: Boolean) {
        _includeMedia.value = include
        prefs.edit().putBoolean("include_media", include).apply()
    }

    fun createBackup(
        password: String,
        uploadToDrive: Boolean = true,
        onComplete: (success: Boolean, message: String) -> Unit
    ) {
        if (_isOperating.value) return
        _isOperating.value = true
        _progress.value = 0.05f
        _statusMessage.value = "Starting encrypted backup..."

        viewModelScope.launch {
            val localResult = backupManager.createEncryptedBackup(
                password = password,
                includeMedia = _includeMedia.value
            ) { prog, msg ->
                _progress.value = prog * 0.7f // local takes 70% of total
                _statusMessage.value = msg
            }

            localResult.onSuccess { meta ->
                if (uploadToDrive) {
                    _statusMessage.value = "Syncing with Google Drive..."
                    val signedIn = driveManager.getSignedInAccount()
                    if (signedIn != null) {
                        val file = File(meta.filePath)
                        val uploadResult = driveManager.uploadBackupFile(signedIn, file) { driveProg ->
                            _progress.value = 0.7f + (driveProg * 0.3f)
                        }

                        uploadResult.onSuccess { driveFileId ->
                            val updatedMeta = meta.copy(
                                isCloudBackup = true,
                                cloudFileId = driveFileId,
                                accountEmail = signedIn.email
                            )
                            backupManager.saveLastBackupInfo(updatedMeta)
                            _lastBackup.value = updatedMeta
                            _isOperating.value = false
                            _progress.value = 1.0f
                            onComplete(true, "Backup uploaded to Google Drive successfully!")
                        }.onFailure { err ->
                            backupManager.saveLastBackupInfo(meta)
                            _lastBackup.value = meta
                            _isOperating.value = false
                            _progress.value = 1.0f
                            onComplete(true, "Local encrypted backup created (${meta.formattedSize}). Google Drive upload failed: ${err.localizedMessage}")
                        }
                    } else {
                        backupManager.saveLastBackupInfo(meta)
                        _lastBackup.value = meta
                        _isOperating.value = false
                        _progress.value = 1.0f
                        onComplete(true, "Local encrypted backup created successfully! (${meta.formattedSize}). Connect Google Drive to sync to cloud.")
                    }
                } else {
                    _lastBackup.value = meta
                    _isOperating.value = false
                    _progress.value = 1.0f
                    onComplete(true, "Local backup created successfully! (${meta.formattedSize})")
                }
            }.onFailure { e ->
                _isOperating.value = false
                onComplete(false, e.localizedMessage ?: "Backup failed")
            }
        }
    }

    fun restoreFromLocal(
        file: File,
        password: String,
        onComplete: (success: Boolean, message: String) -> Unit
    ) {
        if (_isOperating.value) return
        _isOperating.value = true
        _progress.value = 0.05f
        _statusMessage.value = "Opening backup archive..."

        viewModelScope.launch {
            val result = backupManager.restoreEncryptedBackup(file, password) { prog, msg ->
                _progress.value = prog
                _statusMessage.value = msg
            }

            _isOperating.value = false
            result.onSuccess { meta ->
                _lastBackup.value = meta
                onComplete(true, "Restored ${meta.conversationCount} chats and ${meta.messageCount} messages!")
            }.onFailure { e ->
                onComplete(false, e.localizedMessage ?: "Restore failed")
            }
        }
    }

    fun restoreFromGoogleDrive(
        password: String,
        onComplete: (success: Boolean, message: String) -> Unit
    ) {
        if (_isOperating.value) return
        _isOperating.value = true
        _progress.value = 0.05f
        _statusMessage.value = "Connecting to Google Drive..."

        viewModelScope.launch {
            val signedIn = driveManager.getSignedInAccount()
            if (signedIn == null) {
                _isOperating.value = false
                onComplete(false, "Please sign in with Google first to restore from Google Drive.")
                return@launch
            }

            _statusMessage.value = "Checking Google Drive for backups..."
            val queryResult = driveManager.queryLatestBackup(signedIn)
            val driveInfo = queryResult.getOrNull()

            if (driveInfo != null) {
                _statusMessage.value = "Downloading backup from Google Drive..."
                val destFile = File(getApplication<Application>().cacheDir, driveInfo.fileName)
                val dlResult = driveManager.downloadBackupFile(signedIn, driveInfo.fileId, destFile) { prog ->
                    _progress.value = prog * 0.4f
                }

                dlResult.onSuccess { downloadedFile ->
                    val restoreResult = backupManager.restoreEncryptedBackup(downloadedFile, password) { prog, msg ->
                        _progress.value = 0.4f + (prog * 0.6f)
                        _statusMessage.value = msg
                    }

                    _isOperating.value = false
                    restoreResult.onSuccess { meta ->
                        _lastBackup.value = meta
                        onComplete(true, "Restored ${meta.conversationCount} chats from Google Drive!")
                    }.onFailure { e ->
                        onComplete(false, e.localizedMessage ?: "Restore failed")
                    }
                }.onFailure { e ->
                    _isOperating.value = false
                    onComplete(false, "Failed to download backup: ${e.localizedMessage}")
                }
            } else {
                _isOperating.value = false
                onComplete(false, "No GoChat backup file found on Google Drive.")
            }
        }
    }

    fun getLocalBackupFiles(): List<File> = backupManager.getLocalBackupFiles()
}
