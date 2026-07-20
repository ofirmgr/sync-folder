// Copyright © 2026 Ofir Meguri
// Licensed under the Apache License, Version 2.0
// See LICENSE file for details.

package com.ofir.syncfolder.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.activity.result.IntentSenderRequest
import androidx.credentials.Credential
import androidx.documentfile.provider.DocumentFile
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

data class SyncUiState(
    val accountEmail: String? = null,
    val folderName: String? = null,
    val autoSync: Boolean = false,
    val lastSyncMs: Long? = null,
    val needsReauth: Boolean = false,
    val syncFromMs: Long? = null,
    val extensionFilter: String = "",
    val termsAccepted: Boolean = false,
    val backgroundSyncConsent: Boolean = false,
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

    private val appContext: Context = app.applicationContext
    private val prefs = Prefs(app)
    private val authManager = AuthManager(app)
    private val wm = WorkManager.getInstance(app)

    init {
        viewModelScope.launch {
            prefs.clearLegacyAccessToken()

            val snap = prefs.snapshot()
            if (snap.autoSync && snap.backgroundSyncConsent && snap.termsAcceptedVersion >= Prefs.CURRENT_TERMS_VERSION) {
                SyncWorker.scheduleAll(appContext)
            }

            prefs.data.collect { p ->
                if (p.autoSync && p.termsAcceptedVersion < Prefs.CURRENT_TERMS_VERSION) {
                    prefs.setAutoSync(false)
                    SyncWorker.cancelAll(appContext)
                }
                val folderAccessMissing = p.treeUri?.let { treeUri ->
                    val uri = Uri.parse(treeUri)
                    val grantMissing = appContext.contentResolver.persistedUriPermissions.none { permission ->
                        permission.isReadPermission && permission.uri == uri
                    }
                    val unexpectedlyEmpty = runCatching {
                        DocumentFile.fromTreeUri(appContext, uri)?.listFiles()?.isEmpty() != false &&
                            com.ofir.syncfolder.data.AppDb.getInstance(appContext)
                                .fileRecordDao().count() > 0
                    }.getOrDefault(true)
                    grantMissing || unexpectedlyEmpty
                } ?: false
                _state.update {
                    it.copy(
                        accountEmail = p.accountEmail,
                        folderName = p.folderName,
                        autoSync = p.autoSync,
                        lastSyncMs = p.lastSyncMs,
                        needsReauth = p.needsReauth,
                        syncFromMs = p.syncFromMs,
                        extensionFilter = p.extensionFilter ?: "",
                        termsAccepted = p.termsAcceptedVersion >= Prefs.CURRENT_TERMS_VERSION,
                        backgroundSyncConsent = p.backgroundSyncConsent,
                        status = if (folderAccessMissing) {
                            SyncUiState.Status.Error(
                                "Folder access expired — select the folder again"
                            )
                        } else {
                            it.status
                        }
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
                if (result.accessToken != null) {
                    prefs.setNeedsReauth(false)
                    _state.update { it.copy(status = SyncUiState.Status.Idle) }
                } else {
                    _state.update {
                        it.copy(status = SyncUiState.Status.Error("Drive authorization did not return access"))
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(status = SyncUiState.Status.Error("Drive auth result error: ${e.message}")) }
            }
        }
    }

    fun setFolder(uri: Uri, displayName: String) {
        viewModelScope.launch {
            val snap = prefs.snapshot()
            if (snap.treeUri != uri.toString()) {
                prefs.setTreeUri(uri.toString())
                prefs.setFolderName(displayName)
                // Clear local sync state when switching folders to avoid the
                // "listing came back empty" error from the old folder's records.
                com.ofir.syncfolder.data.AppDb.getInstance(getApplication())
                    .fileRecordDao().clearAll()
            }
            // The user may reselect the same URI to renew a stale persisted grant.
            _state.update { it.copy(status = SyncUiState.Status.Idle) }
            if (snap.autoSync) SyncWorker.scheduleAll(appContext)
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
            if (enabled && !state.value.backgroundSyncConsent) {
                _events.send(UiEvent.Toast("Background sync consent is required"))
                return@launch
            }
            prefs.setAutoSync(enabled)
            if (enabled) SyncWorker.scheduleAll(appContext) else SyncWorker.cancelAll(appContext)
        }
    }

    fun acceptTerms() {
        viewModelScope.launch {
            prefs.acceptTerms(Prefs.CURRENT_TERMS_VERSION)
        }
    }

    fun enableAutoSyncAfterConsent() {
        viewModelScope.launch {
            prefs.setBackgroundSyncConsent(true)
            prefs.setAutoSync(true)
            SyncWorker.scheduleAll(appContext)
        }
    }

    fun syncNow() {
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to SyncWorker.TRIGGER_MANUAL))
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
            SyncWorker.cancelAll(appContext)
            prefs.clearAccount()
            prefs.setAutoSync(false)
            _state.update { SyncUiState() }
        }
    }

    /**
     * The content trigger is only registered when the permission is held, so a late
     * grant has to re-arm it.
     */
    fun onMediaPermissionResult() {
        if (state.value.autoSync) SyncWorker.scheduleContentTrigger(appContext)
    }

    /**
     * True when the OS will let background work run on its normal schedule. Without the
     * exemption a user who never opens the app drops into the Restricted standby bucket
     * and sync collapses to roughly once a day, invisibly.
     */
    fun isBatteryOptimized(): Boolean {
        val pm = appContext.getSystemService(PowerManager::class.java)
        return !pm.isIgnoringBatteryOptimizations(appContext.packageName)
    }
}
