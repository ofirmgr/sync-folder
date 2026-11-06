package com.ofir.syncfolder.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequest

@Suppress("UNUSED_PARAMETER")
internal object SmsBackup {
    const val AVAILABLE = false

    fun hasPermission(context: Context): Boolean = false

    fun enqueueAutoSync(context: Context) = Unit

    fun cancelAutoSync(context: Context) = Unit

    fun enqueueManual(context: Context, constraints: Constraints): OneTimeWorkRequest? = null
}
