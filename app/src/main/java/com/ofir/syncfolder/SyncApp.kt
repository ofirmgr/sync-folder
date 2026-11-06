// Copyright © 2026 Ofir Meguri
// Licensed under the Apache License, Version 2.0
// See LICENSE file for details.

package com.ofir.syncfolder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.ofir.syncfolder.data.Prefs
import com.ofir.syncfolder.sync.SmsSyncWorker
import com.ofir.syncfolder.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scheduleBackgroundSync()
    }

    private fun scheduleBackgroundSync() {
        CoroutineScope(Dispatchers.Default).launch {
            val prefs = Prefs(this@SyncApp)
            val snap = prefs.snapshot()
            if (snap.autoSync && snap.backgroundSyncConsent && snap.termsAcceptedVersion >= Prefs.CURRENT_TERMS_VERSION) {
                // Not scheduleAll: its REPLACE would cancel a worker that cold-started this process.
                SyncWorker.ensureScheduled(this@SyncApp)
                SmsSyncWorker.enqueueAutoSync(this@SyncApp)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "sync_progress"
    }
}
