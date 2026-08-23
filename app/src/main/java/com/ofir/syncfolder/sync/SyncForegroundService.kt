// Copyright © 2026 Ofir Meguri
// Licensed under the Apache License, Version 2.0
// See LICENSE file for details.

package com.ofir.syncfolder.sync

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ofir.syncfolder.MainActivity
import com.ofir.syncfolder.R
import com.ofir.syncfolder.SyncApp
import com.ofir.syncfolder.data.Prefs
import kotlinx.coroutines.*

class SyncForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Auto Sync active in background")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_FG_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_FG_SERVICE_ID, notification)
        }

        startPollingLoop()
        return START_STICKY
    }

    private fun startPollingLoop() {
        if (pollJob?.isActive == true) return

        pollJob = serviceScope.launch {
            while (isActive) {
                val prefs = Prefs(applicationContext)
                val snap = prefs.snapshot()

                if (!snap.autoSync || !snap.backgroundSyncConsent || snap.termsAcceptedVersion < Prefs.CURRENT_TERMS_VERSION) {
                    android.util.Log.i("SyncForegroundService", "Auto-sync disabled or unconsented. Stopping service.")
                    stopSelf()
                    break
                }

                // Trigger a WorkManager check/run or rely on SyncWorker
                SyncWorker.ensureScheduled(applicationContext)

                // Wait for interval (15 mins) before next check
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val activityIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SyncApp.CHANNEL_ID)
            .setContentTitle("Sync Folder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    companion object {
        private const val NOTIF_FG_SERVICE_ID = 1004
        private const val POLL_INTERVAL_MS = 15 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            context.stopService(intent)
        }
    }
}
