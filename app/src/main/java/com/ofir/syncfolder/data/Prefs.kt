// Copyright © 2026 Ofir Meguri
// Licensed under the Apache License, Version 2.0
// See LICENSE file for details.

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
    val smsSync: Boolean = false,
    val backgroundSyncConsent: Boolean = false
)

/**
 * What has already been backed up for one calendar month. [count] and [maxMessageId]
 * form a cheap fingerprint: if either differs from the provider's current view of
 * that month, the month is rebuilt and re-uploaded.
 */
data class SmsMonthState(
    val fileId: String,
    val count: Int,
    val maxMessageId: Long
)

class Prefs(context: Context) {
    private val ds = context.dataStore

    val data: Flow<PrefsData> = ds.data.map { p ->
        PrefsData(
            smsSync = p[KEY_SMS_SYNC] ?: false,
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

    suspend fun setSmsSync(enabled: Boolean) = ds.edit { it[KEY_SMS_SYNC] = enabled }

    suspend fun smsBackupId(): String {
        val values = ds.edit {
            if (it[KEY_SMS_BACKUP_ID] == null) it[KEY_SMS_BACKUP_ID] = java.util.UUID.randomUUID().toString()
        }
        return requireNotNull(values[KEY_SMS_BACKUP_ID])
    }

    /**
     * Per-month backup state, keyed by "YYYY-MM". Each value records the Drive file
     * ID plus a fingerprint of the messages already uploaded for that month, so a
     * run only re-uploads months that actually changed and a cancelled run keeps
     * every month it finished.
     */
    suspend fun smsMonthState(): Map<String, SmsMonthState> =
        parseMonthState(ds.data.first()[KEY_SMS_MONTH_STATE])

    suspend fun putSmsMonthState(month: String, state: SmsMonthState) {
        ds.edit { prefs ->
            val merged = parseMonthState(prefs[KEY_SMS_MONTH_STATE]).toMutableMap()
            merged[month] = state
            prefs[KEY_SMS_MONTH_STATE] = serializeMonthState(merged)
        }
    }

    suspend fun clearSmsMonthState() = ds.edit { it.remove(KEY_SMS_MONTH_STATE) }

    suspend fun smsLegacyFilesCleaned(): Boolean = ds.data.first()[KEY_SMS_LEGACY_CLEANED] ?: false

    suspend fun setSmsLegacyFilesCleaned(cleaned: Boolean) =
        ds.edit { it[KEY_SMS_LEGACY_CLEANED] = cleaned }

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
        it.remove(KEY_SMS_SYNC)
        // The per-month Drive file IDs belong to the old account's Drive, so the
        // next account re-backfills instead of patching files it cannot see.
        it.remove(KEY_SMS_MONTH_STATE); it.remove(KEY_SMS_LEGACY_CLEANED)
    }
    suspend fun clearLegacyAccessToken() = ds.edit { it.remove(KEY_TOKEN) }

    companion object {
        // Preserve existing folder-sync consent; SMS has its own explicit opt-in disclosure.
        const val CURRENT_TERMS_VERSION = 1

        private val KEY_SMS_SYNC = booleanPreferencesKey("sms_sync")
        private val KEY_SMS_BACKUP_ID = stringPreferencesKey("sms_backup_id")
        private val KEY_SMS_MONTH_STATE = stringPreferencesKey("sms_month_state")
        private val KEY_SMS_LEGACY_CLEANED = booleanPreferencesKey("sms_legacy_cleaned")

        internal fun parseMonthState(raw: String?): Map<String, SmsMonthState> {
            if (raw.isNullOrBlank()) return emptyMap()
            return runCatching {
                val json = org.json.JSONObject(raw)
                json.keys().asSequence().associateWith { key ->
                    val entry = json.getJSONObject(key)
                    SmsMonthState(
                        fileId = entry.getString("id"),
                        count = entry.getInt("n"),
                        maxMessageId = entry.getLong("max")
                    )
                }
            }.getOrDefault(emptyMap())
        }

        internal fun serializeMonthState(state: Map<String, SmsMonthState>): String {
            val json = org.json.JSONObject()
            state.forEach { (month, value) ->
                json.put(
                    month,
                    org.json.JSONObject()
                        .put("id", value.fileId)
                        .put("n", value.count)
                        .put("max", value.maxMessageId)
                )
            }
            return json.toString()
        }

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
