// Copyright © 2026 Ofir Meguri
// Licensed under the Apache License, Version 2.0
// See LICENSE file for details.

package com.ofir.syncfolder.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ofir.syncfolder.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SyncBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
        ) {
            return
        }

        android.util.Log.i("SyncBootReceiver", "Received action: $action")
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val prefs = Prefs(context)
                val snap = prefs.snapshot()

                if (snap.autoSync && snap.backgroundSyncConsent && snap.termsAcceptedVersion >= Prefs.CURRENT_TERMS_VERSION) {
                    SyncAlarmScheduler.schedule(context)
                    SyncWorker.ensureScheduled(context)

                    try {
                        val serviceIntent = Intent(context, SyncForegroundService::class.java)
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } catch (e: Exception) {
                        android.util.Log.e("SyncBootReceiver", "Failed to start SyncForegroundService", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SyncBootReceiver", "Error on boot/package event", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
