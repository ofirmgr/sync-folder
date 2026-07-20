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

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER) ?: TRIGGER_MANUAL
        val prefs = Prefs(applicationContext)
        val snap = prefs.snapshot()

        if (trigger != TRIGGER_MANUAL && !snap.autoSync) {
            // Auto-sync is off. This is the only exit path allowed to end the chain
            // without re-arming it.
            return Result.success()
        }

        val result = runSync(prefs, snap, trigger)

        // Every other exit path must re-arm, or auto-sync stops permanently. Retry is
        // excluded: WorkManager keeps the same unique work alive for the retry, and
        // re-enqueueing with REPLACE here would cancel it.
        if (result is Result.Retry) return result

        rearm(trigger)

        // A background sync failure belongs to this run, not to the scheduling
        // chain. APPEND_OR_REPLACE links the next request to this one, so returning
        // FAILURE would cancel the appended request and permanently stop auto-sync.
        return if (trigger != TRIGGER_MANUAL && result is Result.Failure) {
            Result.success(result.outputData)
        } else {
            result
        }
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

    private suspend fun runSync(prefs: Prefs, snap: PrefsData, trigger: String): Result {
        val isManual = trigger == TRIGGER_MANUAL

        if (snap.termsAcceptedVersion < Prefs.CURRENT_TERMS_VERSION) {
            return failure("Open the app and accept the current Terms of Use")
        }
        if (snap.autoSync && !snap.backgroundSyncConsent) {
            return failure("Open the app and approve background sync")
        }

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
            } catch (_: Exception) {
                // Fall back gracefully to background execution if foreground service promotion fails
            }
        }

        val auth = AuthManager(applicationContext)
        val (token, pendingIntent) = auth.authorizeForDrive()

        if (pendingIntent != null) {
            prefs.setNeedsReauth(true)
            showReauthNotification()
            return failure("Re-authentication required")
        }
        val accessToken = token ?: return failure("Could not get access token")

        return try {
            val db = AppDb.getInstance(applicationContext)
            val result = SyncEngine.sync(
                context = applicationContext,
                treeUri = treeUri,
                accessToken = accessToken,
                folderName = folderName,
                db = db,
                extensionFilter = SyncEngine.parseExtensionFilter(snap.extensionFilter),
                syncFromMs = snap.syncFromMs,
                onProgress = progressReporter(isManual)
            )
            prefs.setLastSync(System.currentTimeMillis())
            Result.success(workDataOf("uploaded" to result.uploaded, "skipped" to result.skipped))
        } catch (e: TokenExpiredException) {
            prefs.setNeedsReauth(true)
            showReauthNotification()
            failure("Token expired — open app to re-authorize")
        } catch (e: FolderListingException) {
            android.util.Log.e("SyncWorker", "Folder listing failed: ${e.message}", e)
            // Transient SAF/provider listing failure. Retry with backoff instead of
            // reporting a false success that would advance last_sync.
            if (runAttemptCount < MAX_LISTING_RETRIES) Result.retry()
            else failure("Could not read folder contents — try again")
        } catch (e: Exception) {
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
        Result.failure(workDataOf("error" to msg))

    @SuppressLint("InlinedApi")
    private fun makeForegroundInfo(text: String): ForegroundInfo {
        val notif = NotificationCompat.Builder(applicationContext, SyncApp.CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.notif_sync_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
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
         * How long the chain waits between runs. Deliberately not 1 minute: Android 16+
         * caps job execution at 20 minutes per rolling hour in the ACTIVE bucket, and a
         * run enumerates the whole folder. 5 minutes leaves roughly 20x headroom.
         */
        const val SYNC_INTERVAL_MINUTES = 5L

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
            scheduleChain(context)
            scheduleWatchdog(context)
            scheduleContentTrigger(context)
        }

        fun cancelAll(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(CHAIN_WORK_NAME)
            wm.cancelUniqueWork(PERIODIC_WORK_NAME)
            wm.cancelUniqueWork(CONTENT_WORK_NAME)
        }

        /**
         * A PeriodicWorkRequest is clamped to a 15-minute floor; a one-shot that
         * re-enqueues itself is not. WorkManager persists it and reschedules it across
         * reboot via its own RescheduleReceiver, so no boot receiver is needed here.
         */
        fun scheduleChain(
            context: Context,
            delayMinutes: Long = SYNC_INTERVAL_MINUTES,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE
        ) {
            val work = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_TRIGGER to TRIGGER_CHAIN))
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(networkConstraints().build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(CHAIN_WORK_NAME, policy, work)
        }

        fun scheduleWatchdog(context: Context) {
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
            policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE
        ) {
            if (!hasMediaPermission(context)) return
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
