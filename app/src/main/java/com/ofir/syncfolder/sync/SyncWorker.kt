// Copyright © 2026 Ofir Meguri
// Licensed under the Apache License, Version 2.0
// See LICENSE file for details.

package com.ofir.syncfolder.sync

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.ofir.syncfolder.MainActivity
import com.ofir.syncfolder.R
import com.ofir.syncfolder.SyncApp
import com.ofir.syncfolder.auth.AuthManager
import com.ofir.syncfolder.data.AppDb
import com.ofir.syncfolder.data.Prefs
import com.ofir.syncfolder.drive.TokenExpiredException

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val snap = prefs.snapshot()

        if (snap.termsAcceptedVersion < Prefs.CURRENT_TERMS_VERSION) {
            return failure("Open the app and accept the current Terms of Use")
        }
        if (snap.autoSync && !snap.backgroundSyncConsent) {
            return failure("Open the app and approve background sync")
        }

        val treeUriStr = snap.treeUri ?: return failure("No folder selected")
        val folderName = snap.folderName ?: "Synced Folder"

        setForeground(makeForegroundInfo("Syncing $folderName…"))

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
                treeUri = Uri.parse(treeUriStr),
                accessToken = accessToken,
                folderName = folderName,
                db = db,
                extensionFilter = SyncEngine.parseExtensionFilter(snap.extensionFilter),
                syncFromMs = snap.syncFromMs
            ) { currentFile, done ->
                setProgressAsync(workDataOf("current" to currentFile, "done" to done))
            }
            prefs.setLastSync(System.currentTimeMillis())
            Result.success(workDataOf("uploaded" to result.uploaded, "skipped" to result.skipped))
        } catch (e: TokenExpiredException) {
            prefs.setNeedsReauth(true)
            showReauthNotification()
            failure("Token expired — open app to re-authorize")
        } catch (e: Exception) {
            failure(e.message ?: "Sync failed")
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
        const val PERIODIC_WORK_NAME = "sync_periodic"
        private const val NOTIF_SYNC_ID = 1001
        private const val NOTIF_REAUTH_ID = 1002
    }
}
