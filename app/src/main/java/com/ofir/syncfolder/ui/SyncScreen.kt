package com.ofir.syncfolder.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.GetCredentialException
import androidx.documentfile.provider.DocumentFile
import com.ofir.syncfolder.BuildConfig
import com.ofir.syncfolder.auth.AuthManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(viewModel: SyncViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val credentialManager = remember { CredentialManager.create(context) }
    val authManager = remember { AuthManager(context) }

    // Drive authorization result launcher
    val driveAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onDriveAuthResult(result.data)
    }

    // Folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val displayName = DocumentFile.fromTreeUri(context, uri)?.name ?: "Selected Folder"
            viewModel.setFolder(uri, displayName)
        }
    }

    // Collect one-shot events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.LaunchDriveAuth -> driveAuthLauncher.launch(event.request)
                is UiEvent.Toast -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync Folder") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // ── Account section ──────────────────────────────────────────
            AccountSection(
                email = state.accountEmail,
                needsReauth = state.needsReauth,
                signingIn = state.status is SyncUiState.Status.SigningIn,
                onSignIn = {
                    scope.launch {
                        // Try returning accounts first; fall back to account picker on NoCredentialException
                        val req = authManager.buildSignInRequest(BuildConfig.SERVER_CLIENT_ID)
                        try {
                            val result = credentialManager.getCredential(context, req)
                            viewModel.onCredentialReceived(result.credential)
                        } catch (_: GetCredentialException) {
                            try {
                                val result = credentialManager.getCredential(
                                    context,
                                    authManager.buildSignInRequestFresh(BuildConfig.SERVER_CLIENT_ID)
                                )
                                viewModel.onCredentialReceived(result.credential)
                            } catch (e: GetCredentialException) {
                                snackbarHostState.showSnackbar("Sign-in failed: ${e.message}")
                            }
                        }
                    }
                },
                onSignOut = viewModel::signOut,
                onReauth = viewModel::requestDriveAuth
            )

            HorizontalDivider()

            // ── Folder section ───────────────────────────────────────────
            FolderSection(
                folderName = state.folderName,
                syncFromMs = state.syncFromMs,
                extensionFilter = state.extensionFilter,
                onPickFolder = { folderPickerLauncher.launch(null) },
                onSyncFromMsChange = viewModel::setSyncFromMs,
                onExtensionFilterChange = viewModel::setExtensionFilter
            )

            HorizontalDivider()

            // ── Auto-sync toggle ─────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto Sync", style = MaterialTheme.typography.bodyLarge)
                    Text("Upload changes every 15 minutes", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = state.autoSync,
                    onCheckedChange = viewModel::setAutoSync,
                    enabled = state.accountEmail != null && state.folderName != null
                )
            }

            HorizontalDivider()

            // ── Sync now button ──────────────────────────────────────────
            Button(
                onClick = viewModel::syncNow,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.accountEmail != null &&
                        state.folderName != null &&
                        state.status !is SyncUiState.Status.Syncing
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Sync Now")
            }

            // ── Status area ──────────────────────────────────────────────
            StatusSection(state)
        }
    }
}

@Composable
private fun AccountSection(
    email: String?,
    needsReauth: Boolean,
    signingIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onReauth: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Google Account", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        if (email == null) {
            Button(onClick = onSignIn, enabled = !signingIn, modifier = Modifier.fillMaxWidth()) {
                if (signingIn) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Signing in…")
                } else {
                    Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Sign in with Google")
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(email, style = MaterialTheme.typography.bodyMedium)
                    if (needsReauth) {
                        Text("Drive access lost — tap to re-authorize",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                if (needsReauth) {
                    TextButton(onClick = onReauth) { Text("Fix") }
                } else {
                    TextButton(onClick = onSignOut) { Text("Sign out") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderSection(
    folderName: String?,
    syncFromMs: Long?,
    extensionFilter: String,
    onPickFolder: () -> Unit,
    onSyncFromMsChange: (Long?) -> Unit,
    onExtensionFilterChange: (String) -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Folder to sync", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(folderName ?: "Pick a folder…")
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sync from date", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Only files added or changed on or after this date are synced",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(syncFromMs?.let(::formatDate) ?: "All files")
                }
                if (syncFromMs != null) {
                    IconButton(onClick = { onSyncFromMsChange(null) }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear date")
                    }
                }
            }
        }

        OutlinedTextField(
            value = extensionFilter,
            onValueChange = onExtensionFilterChange,
            label = { Text("File extensions to sync") },
            placeholder = { Text("e.g. jpg, png, pdf") },
            supportingText = { Text("Leave empty to sync all files") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = syncFromMs ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onSyncFromMsChange(datePickerState.selectedDateMillis)
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

private fun formatDate(ms: Long): String =
    java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(ms))

@Composable
private fun StatusSection(state: SyncUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(state.lastSyncLabel(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        when (val s = state.status) {
            is SyncUiState.Status.Syncing -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        if (state.currentFile != null) "Uploading ${state.currentFile} (${state.filesDone} done)"
                        else "Syncing…",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            is SyncUiState.Status.Done -> {
                Text("✓ Done — ${s.uploaded} uploaded, ${s.skipped} skipped",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            is SyncUiState.Status.Error -> {
                Text("Error: ${s.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            else -> {}
        }
    }
}
