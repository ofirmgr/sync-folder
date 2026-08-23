// Copyright © 2026 Ofir Meguri
// Licensed under the Apache License, Version 2.0
// See LICENSE file for details.

package com.ofir.syncfolder.sync

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object SyncAlarmScheduler {
    private const val REQUEST_CODE = 2001
    const val ALARM_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

    @SuppressLint("ScheduleExactAlarm")
    fun schedule(context: Context, delayMs: Long = ALARM_INTERVAL_MS) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, SyncAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMs = System.currentTimeMillis() + delayMs
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        } catch (e: Exception) {
            android.util.Log.e("SyncAlarmScheduler", "Failed to schedule alarm", e)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, SyncAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }
}
