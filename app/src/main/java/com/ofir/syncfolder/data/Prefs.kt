package com.ofir.syncfolder.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "prefs")

data class PrefsData(
    val accountEmail: String? = null,
    val treeUri: String? = null,
    val folderName: String? = null,
    val autoSync: Boolean = false,
    val lastSyncMs: Long? = null,
    val needsReauth: Boolean = false,
    val syncFromMs: Long? = null,
    val extensionFilter: String? = null,
    val termsAcceptedVersion: Int = 0,
    val backgroundSyncConsent: Boolean = false
)

class Prefs(context: Context) {
    private val ds = context.dataStore

    val data: Flow<PrefsData> = ds.data.map { p ->
        PrefsData(
            accountEmail = p[KEY_EMAIL],
            treeUri = p[KEY_TREE_URI],
            folderName = p[KEY_FOLDER_NAME],
            autoSync = p[KEY_AUTO_SYNC] ?: false,
            lastSyncMs = p[KEY_LAST_SYNC],
            needsReauth = p[KEY_NEEDS_REAUTH] ?: false,
            syncFromMs = p[KEY_SYNC_FROM_MS],
            extensionFilter = p[KEY_EXTENSION_FILTER],
            termsAcceptedVersion = p[KEY_TERMS_ACCEPTED_VERSION] ?: 0,
            backgroundSyncConsent = p[KEY_BACKGROUND_SYNC_CONSENT] ?: false
        )
    }

    suspend fun snapshot(): PrefsData = data.first()

    suspend fun setAccountEmail(email: String) = ds.edit { it[KEY_EMAIL] = email }
    suspend fun setTreeUri(uri: String) = ds.edit { it[KEY_TREE_URI] = uri }
    suspend fun setFolderName(name: String) = ds.edit { it[KEY_FOLDER_NAME] = name }
    suspend fun setAutoSync(on: Boolean) = ds.edit { it[KEY_AUTO_SYNC] = on }
    suspend fun setLastSync(ms: Long) = ds.edit { it[KEY_LAST_SYNC] = ms }
    suspend fun setNeedsReauth(v: Boolean) = ds.edit { it[KEY_NEEDS_REAUTH] = v }
    suspend fun setSyncFromMs(ms: Long?) = ds.edit {
        if (ms == null) it.remove(KEY_SYNC_FROM_MS) else it[KEY_SYNC_FROM_MS] = ms
    }
    suspend fun setExtensionFilter(v: String?) = ds.edit {
        if (v.isNullOrBlank()) it.remove(KEY_EXTENSION_FILTER) else it[KEY_EXTENSION_FILTER] = v
    }
    suspend fun acceptTerms(version: Int) = ds.edit { it[KEY_TERMS_ACCEPTED_VERSION] = version }
    suspend fun setBackgroundSyncConsent(accepted: Boolean) =
        ds.edit { it[KEY_BACKGROUND_SYNC_CONSENT] = accepted }
    suspend fun clearAccount() = ds.edit {
        it.remove(KEY_EMAIL); it.remove(KEY_TOKEN); it.remove(KEY_NEEDS_REAUTH)
    }
    suspend fun clearLegacyAccessToken() = ds.edit { it.remove(KEY_TOKEN) }

    companion object {
        const val CURRENT_TERMS_VERSION = 1

        private val KEY_EMAIL = stringPreferencesKey("email")
        private val KEY_TOKEN = stringPreferencesKey("access_token")
        private val KEY_TREE_URI = stringPreferencesKey("tree_uri")
        private val KEY_FOLDER_NAME = stringPreferencesKey("folder_name")
        private val KEY_AUTO_SYNC = booleanPreferencesKey("auto_sync")
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync_ms")
        private val KEY_NEEDS_REAUTH = booleanPreferencesKey("needs_reauth")
        private val KEY_SYNC_FROM_MS = longPreferencesKey("sync_from_ms")
        private val KEY_EXTENSION_FILTER = stringPreferencesKey("extension_filter")
        private val KEY_TERMS_ACCEPTED_VERSION = intPreferencesKey("terms_accepted_version")
        private val KEY_BACKGROUND_SYNC_CONSENT = booleanPreferencesKey("background_sync_consent")
    }
}
