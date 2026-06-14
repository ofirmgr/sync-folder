package com.ofir.syncfolder.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.credentials.Credential
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.google.android.gms.auth.api.identity.Identity
import com.ofir.syncfolder.auth.AuthManager
import com.ofir.syncfolder.data.Prefs
import com.ofir.syncfolder.sync.SyncWorker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class SyncUiState(
    val accountEmail: String? = null,
    val folderName: String? = null,
    val autoSync: Boolean = false,
    val lastSyncMs: Long? = null,
    val needsReauth: Boolean = false,
    val syncFromMs: Long? = null,
    val extensionFilter: String = "",
    val status: Status = Status.Idle,
    val currentFile: String? = null,
    val filesDone: Int = 0
) {
    sealed class Status {
        object Idle : Status()
        object SigningIn : Status()
        object Syncing : Status()
        data class Done(val uploaded: Int, val skipped: Int) : Status()
        data class Error(val message: String) : Status()
    }

    fun lastSyncLabel(): String = lastSyncMs?.let {
        "Last sync: " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(it))
    } ?: "Never synced"
}

sealed class UiEvent {
    data class LaunchDriveAuth(val request: IntentSenderRequest) : UiEvent()
    data class Toast(val message: String) : UiEvent()
}

class SyncViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private val prefs = Prefs(app)
    private val authManager = AuthManager(app)
    private val wm = WorkManager.getInstance(app)

    init {
        viewModelScope.launch {
            prefs.data.collect { p ->
                _state.update {
                    it.copy(
                        accountEmail = p.accountEmail,
                        folderName = p.folderName,
                        autoSync = p.autoSync,
                        lastSyncMs = p.lastSyncMs,
                        needsReauth = p.needsReauth,
                        syncFromMs = p.syncFromMs,
                        extensionFilter = p.extensionFilter ?: ""
                    )
                }
            }
        }
    }

    fun onCredentialReceived(credential: Credential) {
        viewModelScope.launch {
            _state.update { it.copy(status = SyncUiState.Status.SigningIn) }
            try {
                val email = authManager.extractEmail(credential)
                prefs.setAccountEmail(email)
                requestDriveAuth()
            } catch (e: Exception) {
                _state.update { it.copy(status = SyncUiState.Status.Error("Sign-in failed: ${e.message}")) }
            }
        }
    }

    fun requestDriveAuth() {
        viewModelScope.launch {
            try {
                val (token, sender) = authManager.authorizeForDrive()
                when {
                    token != null -> {
                        prefs.setAccessToken(token)
                        prefs.setNeedsReauth(false)
                        _state.update { it.copy(status = SyncUiState.Status.Idle) }
                    }
                    sender != null -> {
                        _events.send(UiEvent.LaunchDriveAuth(IntentSenderRequest.Builder(sender).build()))
                    }
                    else -> {
                        _state.update { it.copy(status = SyncUiState.Status.Error("Could not authorize Drive")) }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(status = SyncUiState.Status.Error("Drive auth failed: ${e.message}")) }
            }
        }
    }

    fun onDriveAuthResult(data: Intent?) {
        viewModelScope.launch {
            try {
                val result = Identity.getAuthorizationClient(getApplication())
                    .getAuthorizationResultFromIntent(data!!)
                result.accessToken?.let {
                    prefs.setAccessToken(it)
                    prefs.setNeedsReauth(false)
                }
                _state.update { it.copy(status = SyncUiState.Status.Idle) }
            } catch (e: Exception) {
                _state.update { it.copy(status = SyncUiState.Status.Error("Drive auth result error: ${e.message}")) }
            }
        }
    }

    fun setFolder(uri: Uri, displayName: String) {
        viewModelScope.launch {
            prefs.setTreeUri(uri.toString())
            prefs.setFolderName(displayName)
        }
    }

    fun setSyncFromMs(ms: Long?) {
        viewModelScope.launch {
            prefs.setSyncFromMs(ms)
        }
    }

    fun setExtensionFilter(value: String) {
        _state.update { it.copy(extensionFilter = value) }
        viewModelScope.launch {
            prefs.setExtensionFilter(value)
        }
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setAutoSync(enabled)
            if (enabled) schedulePeriodicSync() else wm.cancelUniqueWork(SyncWorker.PERIODIC_WORK_NAME)
        }
    }

    fun syncNow() {
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueue(work)
        _state.update { it.copy(status = SyncUiState.Status.Syncing, filesDone = 0, currentFile = null) }

        viewModelScope.launch {
            wm.getWorkInfoByIdFlow(work.id).collect { info ->
                when (info?.state) {
                    WorkInfo.State.RUNNING -> {
                        _state.update {
                            it.copy(
                                currentFile = info.progress.getString("current"),
                                filesDone = info.progress.getInt("done", 0)
                            )
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val uploaded = info.outputData.getInt("uploaded", 0)
                        val skipped = info.outputData.getInt("skipped", 0)
                        _state.update { it.copy(status = SyncUiState.Status.Done(uploaded, skipped)) }
                    }
                    WorkInfo.State.FAILED -> {
                        val err = info.outputData.getString("error") ?: "Sync failed"
                        _state.update { it.copy(status = SyncUiState.Status.Error(err)) }
                    }
                    else -> {}
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            wm.cancelUniqueWork(SyncWorker.PERIODIC_WORK_NAME)
            prefs.clearAccount()
            prefs.setAutoSync(false)
            _state.update { SyncUiState() }
        }
    }

    private fun schedulePeriodicSync() {
        val work = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(SyncWorker.PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, work)
    }
}
