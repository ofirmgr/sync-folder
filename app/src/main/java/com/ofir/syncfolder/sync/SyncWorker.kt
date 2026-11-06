// Copyright © 2026 Ofir Meguri
// Licensed under the Apache License, Version 2.0
// See LICENSE file for details.

package com.ofir.syncfolder.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit
import androidx.work.*
import com.ofir.syncfolder.MainActivity
import com.ofir.syncfolder.R
import com.ofir.syncfolder.SyncApp
import com.ofir.syncfolder.auth.AuthManager
import com.ofir.syncfolder.data.AppDb
import com.ofir.syncfolder.data.Prefs
import com.ofir.syncfolder.data.PrefsData
import com.ofir.syncfolder.drive.TokenExpiredException
import kotlinx.coroutines.CancellationException

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        makeForegroundInfo(applicationContext.getString(R.string.notif_sync_title))

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER) ?: TRIGGER_MANUAL
        android.util.Log.i("SyncWorker", "doWork starting. trigger=$trigger, attempt=$runAttemptCount")
        val prefs = Prefs(applicationContext)
        val snap = prefs.snapshot()

        if (trigger != TRIGGER_MANUAL && !snap.autoSync) {
            // Auto-sync is off. This is the only exit path allowed to end the chain
            // without re-arming it.
            android.util.Log.i("SyncWorker", "autoSync is off, exiting without rearm")
            return Result.success()
        }

        // Trigger SMS before folder validation or I/O, including SMS-only setups.
        // Unique KEEP work preserves any backup already running or waiting to retry.
        if (trigger != TRIGGER_MANUAL && snap.smsSync) {
            SmsBackup.enqueueAutoSync(applicationContext)
        }

        val result = runSync(prefs, snap, trigger)
        android.util.Log.i("SyncWorker", "runSync finished with result=$result")

        // Every other exit path must re-arm, or auto-sync stops permanently. Retry is
        // excluded: WorkManager keeps the same unique work alive for the retry, and
        // re-enqueueing with REPLACE here would cancel it.
        if (result is SyncOutcome.Retry) return Result.retry()

        rearm(trigger)

        // A failed background run must not cancel the appended scheduling chain.
        return when (result) {
            is SyncOutcome.Complete -> Result.success(result.output)
            is SyncOutcome.Failed -> if (trigger == TRIGGER_MANUAL) {
                Result.failure(result.output)
            } else Result.success(result.output)
            SyncOutcome.Retry -> Result.retry()
        }
    }

    private sealed class SyncOutcome {
        data class Complete(val output: Data) : SyncOutcome()
        data class Failed(val output: Data) : SyncOutcome()
        data object Retry : SyncOutcome()
    }

    /** Re-enqueues the one-shot request that fired this run, if it was a one-shot. */
    private fun rearm(trigger: String) {
        when (trigger) {
            // Append behind the currently-running unique work. REPLACE cancels this
            // worker before it can return its result (observed as WorkerStoppedException).
            TRIGGER_CHAIN -> scheduleChain(
                applicationContext,
                policy = ExistingWorkPolicy.APPEND_OR_REPLACE
            )
            TRIGGER_CONTENT -> scheduleContentTrigger(
                applicationContext,
                policy = ExistingWorkPolicy.APPEND_OR_REPLACE
            )
            // The watchdog is periodic and re-arms itself, but it is also what revives
            // the one-shots after a force-stop or a missed re-enqueue. KEEP so a healthy
            // chain is left alone rather than having its delay reset every 15 minutes.
            TRIGGER_WATCHDOG -> {
                scheduleChain(applicationContext, policy = ExistingWorkPolicy.KEEP)
                scheduleContentTrigger(applicationContext, policy = ExistingWorkPolicy.KEEP)
            }
            // Manual runs are not scheduled.
        }
    }

    private suspend fun runSync(prefs: Prefs, snap: PrefsData, trigger: String): SyncOutcome {
        val isManual = trigger == TRIGGER_MANUAL
        if (snap.accountEmail.isNullOrBlank()) return failure("Sign in to Google before syncing")

        if (snap.termsAcceptedVersion < Prefs.CURRENT_TERMS_VERSION) {
            return failure("Open the app and accept the current Terms of Use")
        }
        if (snap.autoSync && !snap.backgroundSyncConsent) {
            return failure("Open the app and approve background sync")
        }

        // SMS backup has its own worker; this one only handles the folder.
        val treeUriStr = snap.treeUri ?: return failure("No folder selected")
        val treeUri = Uri.parse(treeUriStr)
        val hasPersistedReadAccess = applicationContext.contentResolver.persistedUriPermissions.any {
            it.isReadPermission && it.uri == treeUri
        }
        if (!hasPersistedReadAccess) {
            android.util.Log.e("SyncWorker", "Persisted folder permission is missing for $treeUri")
            return failure("Folder access expired — open the app and select the folder again")
        }
        val folderName = snap.folderName ?: "Synced Folder"

        if (isManual) {
            try {
                setForeground(makeForegroundInfo("Syncing $folderName…"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fall back gracefully to background execution if foreground service promotion fails
            }
        }

        val auth = AuthManager(applicationContext)
        val (token, pendingIntent) = auth.authorizeForDrive(snap.accountEmail)

        if (pendingIntent != null) {
            prefs.setNeedsReauth(true)
            showReauthNotification()
            return failure("Re-authentication required")
        }
        var accessToken = token ?: return failure("Could not get access token")

        return try {
            val db = AppDb.getInstance(applicationContext)
            suspend fun syncWithCurrentToken() = SyncEngine.sync(
                context = applicationContext,
                treeUri = treeUri,
                accessToken = accessToken,
                folderName = folderName,
                db = db,
                extensionFilter = SyncEngine.parseExtensionFilter(snap.extensionFilter),
                syncFromMs = snap.syncFromMs,
                onProgress = progressReporter(isManual)
            )

            val result = try {
                syncWithCurrentToken()
            } catch (_: TokenExpiredException) {
                // Access tokens are short-lived. Ask Play services for a replacement
                // before requiring the user to open the app again.
                val (refreshedToken, refreshedPendingIntent) = auth.authorizeForDrive(snap.accountEmail)
                if (refreshedPendingIntent != null || refreshedToken == null) {
                    prefs.setNeedsReauth(true)
                    showReauthNotification()
                    return failure("Re-authentication required")
                }
                accessToken = refreshedToken
                syncWithCurrentToken()
            }
            prefs.setLastSync(System.currentTimeMillis())
            prefs.setNeedsReauth(false)
            SyncOutcome.Complete(workDataOf("uploaded" to result.uploaded, "skipped" to result.skipped, "failed" to result.failed))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: TokenExpiredException) {
            prefs.setNeedsReauth(true)
            showReauthNotification()
            failure("Token expired — open app to re-authorize")
        } catch (e: FolderListingException) {
            android.util.Log.e("SyncWorker", "Folder listing failed: ${e.message}", e)
            // Transient SAF/provider listing failure. Retry with backoff instead of
            // reporting a false success that would advance last_sync.
            if (runAttemptCount < MAX_LISTING_RETRIES) SyncOutcome.Retry
            else failure("Could not read folder contents — try again")
        } catch (e: Exception) {
            android.util.Log.e("SyncWorker", "Sync exception occurred: ${e.message}", e)
            failure(e.message ?: "Sync failed")
        }
    }

    /**
     * SyncEngine reports progress for every file that passes the extension filter —
     * thousands per run on a large folder — and each report is a write to WorkManager's
     * database. Only the manual sync screen ever reads it, so background runs report
     * nothing at all and manual runs are throttled.
     */
    private fun progressReporter(isManual: Boolean): (String, Int, Boolean) -> Unit {
        if (!isManual) return { _, _, _ -> }
        var lastReportMs = 0L
        return { currentFile, done, uploading ->
            val now = SystemClock.elapsedRealtime()
            if (uploading || now - lastReportMs >= PROGRESS_INTERVAL_MS) {
                lastReportMs = now
                setProgressAsync(workDataOf("current" to currentFile, "done" to done))
            }
        }
    }

    private fun failure(msg: String) =
        SyncOutcome.Failed(workDataOf("error" to msg))

    @SuppressLint("InlinedApi")
    private fun makeForegroundInfo(text: String): ForegroundInfo {
        val notif = NotificationCompat.Builder(applicationContext, SyncApp.CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.notif_sync_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                applicationContext.getString(android.R.string.cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
            )
            .build()
        return ForegroundInfo(NOTIF_SYNC_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun showReauthNotification() {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pi = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(applicationContext, SyncApp.CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.notif_reauth_title))
            .setContentText(applicationContext.getString(R.string.notif_reauth_text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_REAUTH_ID, notif)
    }

    companion object {
        /** Periodic watchdog that re-arms the chain if it ever dies. */
        const val PERIODIC_WORK_NAME = "sync_periodic"
        /** The self-rescheduling one-shot that does the routine polling. */
        const val CHAIN_WORK_NAME = "sync_chain"
        /** The one-shot parked on a MediaStore content-URI trigger. */
        const val CONTENT_WORK_NAME = "sync_content"

        const val KEY_TRIGGER = "trigger"
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_CHAIN = "chain"
        const val TRIGGER_WATCHDOG = "watchdog"
        const val TRIGGER_CONTENT = "content"

        /**
         * How long the chain waits between runs. Increased to 15 minutes to reduce job
         * queue pressure and comply with Android 16+ active bucket execution caps.
         */
        const val SYNC_INTERVAL_MINUTES = 15L

        /** Debounce for the content trigger, so a burst of new files is one run. */
        private const val TRIGGER_DELAY_SECONDS = 10L

        private const val PROGRESS_INTERVAL_MS = 500L
        private const val NOTIF_SYNC_ID = 1001
        private const val NOTIF_REAUTH_ID = 1002
        private const val MAX_LISTING_RETRIES = 5

        private fun networkConstraints() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)

        /** Arms every background path. Safe to call repeatedly. */
        fun scheduleAll(context: Context) {
            ensureScheduled(context)
            // Independent unique work, so a long SMS backfill cannot delay or cancel
            // the folder chain — and vice versa.
            SmsBackup.enqueueAutoSync(context)
        }

        /** Restores missing background work without replacing a worker that started the app. */
        fun ensureScheduled(context: Context) {
            scheduleChain(context)
            scheduleWatchdog(context)
            scheduleContentTrigger(context)
            SyncAlarmScheduler.schedule(context)
        }

        fun cancelAll(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(CHAIN_WORK_NAME)
            wm.cancelUniqueWork(PERIODIC_WORK_NAME)
            wm.cancelUniqueWork(CONTENT_WORK_NAME)
            SmsBackup.cancelAutoSync(context)
            SyncAlarmScheduler.cancel(context)
        }

        /**
         * A PeriodicWorkRequest is clamped to a 15-minute floor; a one-shot that
         * re-enqueues itself is not. WorkManager persists it and reschedules it across
         * reboot via its own RescheduleReceiver, so no boot receiver is needed here.
         */
        fun scheduleChain(
            context: Context,
            delayMinutes: Long = SYNC_INTERVAL_MINUTES,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
        ) {
            android.util.Log.i("SyncWorker", "scheduleChain with delay=$delayMinutes mins, policy=$policy")
            val work = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_TRIGGER to TRIGGER_CHAIN))
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(networkConstraints().build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(CHAIN_WORK_NAME, policy, work)
        }

        fun scheduleWatchdog(context: Context) {
            android.util.Log.i("SyncWorker", "scheduleWatchdog")
            val work = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_TRIGGER to TRIGGER_WATCHDOG))
                .setConstraints(networkConstraints().build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, work)
        }

        /**
         * The only mechanism where the OS watches for us while our process is dead.
         * JobScheduler fires this when MediaStore indexes a new file, which for a
         * recording folder is within seconds of the file being written.
         *
         * Registering it without the matching read permission would park a job that can
         * never fire, so it is skipped instead — the chain still covers the folder.
         */
        fun scheduleContentTrigger(
            context: Context,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
        ) {
            if (!hasMediaPermission(context)) return
            android.util.Log.i("SyncWorker", "scheduleContentTrigger with policy=$policy")
            val constraints = networkConstraints()
                .addContentUriTrigger(MediaStore.Files.getContentUri("external"), true)
                .setTriggerContentUpdateDelay(TRIGGER_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()
            val work = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_TRIGGER to TRIGGER_CONTENT))
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(CONTENT_WORK_NAME, policy, work)
        }

        fun hasMediaPermission(context: Context): Boolean {
            val permission =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
                else Manifest.permission.READ_EXTERNAL_STORAGE
            return ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}
