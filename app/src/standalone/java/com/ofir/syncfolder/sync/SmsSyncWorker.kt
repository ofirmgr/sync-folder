// Copyright © 2026 Ofir Meguri
// Licensed under the Apache License, Version 2.0
// See LICENSE file for details.

package com.ofir.syncfolder.sync

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.ofir.syncfolder.MainActivity
import com.ofir.syncfolder.SyncApp
import com.ofir.syncfolder.auth.AuthManager
import com.ofir.syncfolder.data.Prefs
import com.ofir.syncfolder.drive.TokenExpiredException

/**
 * Backs up SMS in separate work whenever automatic folder sync runs.
 *
 * SMS backup used to run inside [SyncWorker] ahead of the folder upload. A first
 * backfill is thousands of messages long, so folder sync never got a turn and the
 * 15-minute chain kept cancelling the run before it finished. Separate unique work
 * means neither side can starve or cancel the other.
 */
class SmsSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER) ?: TRIGGER_MANUAL
        android.util.Log.i(TAG, "doWork starting. trigger=$trigger, attempt=$runAttemptCount")

        val prefs = Prefs(applicationContext)
        val snap = prefs.snapshot()

        if (trigger == TRIGGER_CLEANUP_LEGACY) return runLegacyCleanup(prefs)

        if (!snap.smsSync) {
            android.util.Log.i(TAG, "SMS backup is off, exiting")
            return Result.success()
        }
        if (trigger != TRIGGER_MANUAL && !snap.autoSync) {
            android.util.Log.i(TAG, "autoSync is off, exiting")
            return Result.success()
        }
        if (snap.termsAcceptedVersion < Prefs.CURRENT_TERMS_VERSION ||
            !snap.backgroundSyncConsent ||
            snap.accountEmail.isNullOrBlank()
        ) {
            return Result.success(workDataOf(KEY_ERROR to "Open the app to finish SMS backup setup"))
        }
        if (!SmsSyncEngine.hasPermission(applicationContext)) {
            return Result.success(workDataOf(KEY_ERROR to "SMS permission is not granted"))
        }

        val outcome = runBackup(prefs, snap.accountEmail, trigger)
        android.util.Log.i(TAG, "SMS backup finished with result=$outcome")

        // Retries remain in this unique work; the next automatic sync can start
        // another backup after completion. There is no separate SMS timer.
        return outcome
    }

    private suspend fun runBackup(prefs: Prefs, accountEmail: String, trigger: String): Result {
        val isManual = trigger == TRIGGER_MANUAL
        if (isManual) {
            try {
                setForeground(makeForegroundInfo("Backing up SMS…"))
            } catch (_: Exception) {
                // Fall back gracefully to background execution.
            }
        }

        val auth = AuthManager(applicationContext)
        val (token, pendingIntent) = auth.authorizeForDrive(accountEmail)
        if (pendingIntent != null) {
            prefs.setNeedsReauth(true)
            return Result.failure(workDataOf(KEY_ERROR to "Re-authentication required"))
        }
        var accessToken = token
            ?: return Result.failure(workDataOf(KEY_ERROR to "Could not get access token"))

        return try {
            val result = try {
                SmsSyncEngine.sync(applicationContext, accessToken, accountEmail, prefs, progressReporter(isManual))
            } catch (_: TokenExpiredException) {
                // Access tokens are short-lived; ask Play services for a replacement
                // before requiring the user to open the app again.
                val (refreshed, refreshedIntent) = auth.authorizeForDrive(accountEmail)
                if (refreshedIntent != null || refreshed == null) {
                    prefs.setNeedsReauth(true)
                    return Result.failure(workDataOf(KEY_ERROR to "Re-authentication required"))
                }
                accessToken = refreshed
                SmsSyncEngine.sync(applicationContext, accessToken, accountEmail, prefs, progressReporter(isManual))
            }
            prefs.setNeedsReauth(false)
            Result.success(
                workDataOf(
                    KEY_UPLOADED to result.uploaded,
                    KEY_SKIPPED to result.skipped,
                    KEY_FAILED to result.failed
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: TokenExpiredException) {
            prefs.setNeedsReauth(true)
            Result.failure(workDataOf(KEY_ERROR to "Token expired — open app to re-authorize"))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "SMS backup exception: ${e.message}", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "SMS backup failed")))
        }
    }

    private suspend fun runLegacyCleanup(prefs: Prefs): Result {
        val email = prefs.snapshot().accountEmail
            ?: return Result.failure(workDataOf(KEY_ERROR to "Sign in to Google first"))
        return try {
            val (token, pendingIntent) = AuthManager(applicationContext).authorizeForDrive(email)
            if (pendingIntent != null || token == null) {
                return Result.failure(workDataOf(KEY_ERROR to "Re-authentication required"))
            }
            val trashed = SmsSyncEngine.cleanupLegacyFiles(token, prefs)
            android.util.Log.i(TAG, "Legacy SMS cleanup trashed $trashed files")
            Result.success(workDataOf(KEY_TRASHED to trashed))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Legacy SMS cleanup failed: ${e.message}", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Cleanup failed")))
        }
    }

    /**
     * Only the manual backup screen reads progress, and every report is a write to
     * WorkManager's database — so background runs report nothing and manual runs are
     * throttled, matching [SyncWorker]'s reporter.
     */
    private fun progressReporter(isManual: Boolean): (String, Int, Boolean) -> Unit {
        if (!isManual) return { _, _, _ -> }
        var lastReportMs = 0L
        return { label, done, uploading ->
            val now = SystemClock.elapsedRealtime()
            if (uploading || now - lastReportMs >= PROGRESS_INTERVAL_MS) {
                lastReportMs = now
                setProgressAsync(workDataOf(KEY_CURRENT to label, KEY_DONE to done))
            }
        }
    }

    @SuppressLint("InlinedApi")
    private fun makeForegroundInfo(text: String): ForegroundInfo {
        val intent = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(applicationContext, SyncApp.CHANNEL_ID)
            .setContentTitle("SMS backup")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setContentIntent(intent)
            .build()
        // A distinct ID from SyncWorker's 1001, so folder and SMS progress coexist.
        return ForegroundInfo(NOTIF_SMS_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "SmsSyncWorker"

        /** Its own unique work, so SyncWorker's chain can never cancel it. */
        const val CHAIN_WORK_NAME = "sms_sync_chain"

        const val KEY_TRIGGER = "trigger"
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_AUTO = "auto"
        const val TRIGGER_CLEANUP_LEGACY = "cleanup_legacy"

        const val KEY_CURRENT = "current"
        const val KEY_DONE = "done"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILED = "failed"
        const val KEY_TRASHED = "trashed"
        const val KEY_ERROR = "error"

        private const val PROGRESS_INTERVAL_MS = 500L
        private const val NOTIF_SMS_ID = 1003
        private const val MAX_RETRIES = 3

        /** Runs alongside auto-sync without cancelling a pending or active backup. */
        fun enqueueAutoSync(context: Context) {
            val work = OneTimeWorkRequestBuilder<SmsSyncWorker>()
                .setInputData(workDataOf(KEY_TRIGGER to TRIGGER_AUTO))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(CHAIN_WORK_NAME, ExistingWorkPolicy.KEEP, work)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(CHAIN_WORK_NAME)
        }

        /** One-shot, destructive: trashes the schema-2 one-file-per-message backups. */
        fun enqueueLegacyCleanup(context: Context) {
            val work = OneTimeWorkRequestBuilder<SmsSyncWorker>()
                .setInputData(workDataOf(KEY_TRIGGER to TRIGGER_CLEANUP_LEGACY))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("sms_legacy_cleanup", ExistingWorkPolicy.KEEP, work)
        }
    }
}
