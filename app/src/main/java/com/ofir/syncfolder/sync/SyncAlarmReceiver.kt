// Copyright © 2026 Ofir Meguri
// Licensed under the Apache License, Version 2.0
// See LICENSE file for details.

package com.ofir.syncfolder.sync

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ofir.syncfolder.MainActivity
import com.ofir.syncfolder.R
import com.ofir.syncfolder.SyncApp
import com.ofir.syncfolder.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SyncAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val prefs = Prefs(context)
                val snap = prefs.snapshot()

                if (snap.autoSync && snap.backgroundSyncConsent && snap.termsAcceptedVersion >= Prefs.CURRENT_TERMS_VERSION) {
                    // Reschedule next alarm
                    SyncAlarmScheduler.schedule(context)

                    // Ensure WorkManager work is scheduled and alive
                    SyncWorker.ensureScheduled(context)

                    // Start foreground service if enabled
                    try {
                        val serviceIntent = Intent(context, SyncForegroundService::class.java)
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } catch (e: Exception) {
                        android.util.Log.e("SyncAlarmReceiver", "Failed to start SyncForegroundService", e)
                    }

                    // Check for stuck sync condition (> 2 hours without successful sync)
                    val lastSyncMs = snap.lastSyncMs ?: 0L
                    val now = System.currentTimeMillis()
                    val twoHoursMs = 2 * 60 * 60 * 1000L

                    if (lastSyncMs > 0 && (now - lastSyncMs) > twoHoursMs) {
                        showStalledNotification(context, lastSyncMs)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SyncAlarmReceiver", "Error processing alarm", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showStalledNotification(context: Context, lastSyncMs: Long) {
        val activityIntent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val lastSyncStr = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(lastSyncMs))

        val notif = NotificationCompat.Builder(context, SyncApp.CHANNEL_ID)
            .setContentTitle("Auto-sync stalled")
            .setContentText("Auto-sync hasn't run since $lastSyncStr. Tap to open app and check status.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_STALLED_ID, notif)
    }

    companion object {
        private const val NOTIF_STALLED_ID = 1003
    }
}
