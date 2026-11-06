package com.ofir.syncfolder.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

internal object SmsBackup {
    const val AVAILABLE = true

    fun hasPermission(context: Context): Boolean = SmsSyncEngine.hasPermission(context)

    fun enqueueAutoSync(context: Context) = SmsSyncWorker.enqueueAutoSync(context)

    fun cancelAutoSync(context: Context) = SmsSyncWorker.cancel(context)

    fun enqueueManual(context: Context, constraints: Constraints): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SmsSyncWorker>()
            .setInputData(workDataOf(SmsSyncWorker.KEY_TRIGGER to SmsSyncWorker.TRIGGER_MANUAL))
            .setConstraints(constraints)
            .build()
            .also { WorkManager.getInstance(context).enqueue(it) }
}
