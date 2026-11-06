package com.ofir.syncfolder.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.ofir.syncfolder.data.Prefs
import com.ofir.syncfolder.data.SmsMonthState
import com.ofir.syncfolder.drive.DriveClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.YearMonth
import java.time.ZoneId

/**
 * Backs up SMS to Drive as one NDJSON file per calendar month.
 *
 * A cheap (_ID, DATE) scan decides which months changed since the last run, so a
 * cancelled run keeps every month it finished and an unchanged history uploads
 * nothing. No message bodies or numbers are logged.
 */
object SmsSyncEngine {
    private val mutex = Mutex()

    const val DRIVE_FOLDER_NAME = "Sync Folder SMS"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    suspend fun sync(
        context: Context,
        accessToken: String,
        accountEmail: String?,
        prefs: Prefs,
        onProgress: (String, Int, Boolean) -> Unit
    ): SyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val initial = prefs.snapshot()
            if (!initial.smsSync || initial.accountEmail != accountEmail) return@withContext SyncResult(0, 0)
            check(hasPermission(context)) { "SMS access missing — open app settings and allow SMS permission" }

            val zone = ZoneId.systemDefault()
            val live = scanMonths(context, zone)
            val stored = prefs.smsMonthState()
            val changed = live.filter { (month, summary) ->
                val prior = stored[month]
                prior == null || prior.count != summary.count || prior.maxMessageId != summary.maxMessageId
            }.keys.sorted()

            val total = live.values.sumOf { it.count }
            val skipped = total - changed.sumOf { live.getValue(it).count }
            if (changed.isEmpty()) return@withContext SyncResult(0, total)

            val drive = DriveClient(accessToken)
            val backupId = prefs.smsBackupId()
            var folderId: String? = null
            var uploaded = 0

            for ((index, month) in changed.withIndex()) {
                currentCoroutineContext().ensureActive()
                val latest = prefs.snapshot()
                if (!latest.smsSync || latest.accountEmail != initial.accountEmail) {
                    return@withContext SyncResult(uploaded, skipped)
                }
                check(hasPermission(context)) { "SMS permission was revoked; backup stopped" }

                val summary = live.getValue(month)
                onProgress("SMS ${index + 1} of ${changed.size} months ($month)", uploaded, true)

                val body = buildMonth(context, month, zone) ?: continue
                val bytes = body.toByteArray(Charsets.UTF_8)
                val destination = folderId
                    ?: drive.ensureFolder(DRIVE_FOLDER_NAME, "root").also { folderId = it }
                val name = SmsBackupWriter.fileName(backupId, month)

                val fileId = bytes.inputStream().use { stream ->
                    drive.uploadFile(
                        name = name,
                        parentId = destination,
                        stream = stream,
                        mimeType = "application/x-ndjson",
                        contentLength = bytes.size.toLong(),
                        lastModifiedMs = summary.maxDateMs,
                        knownFileId = stored[month]?.fileId
                    )
                }

                // Committed per month: a cancellation after this point never redoes it.
                prefs.putSmsMonthState(
                    month,
                    SmsMonthState(fileId = fileId, count = summary.count, maxMessageId = summary.maxMessageId)
                )
                uploaded += summary.count
                onProgress("SMS ${index + 1} of ${changed.size} months ($month)", uploaded, false)
            }

            SyncResult(uploaded, skipped)
        }
    }

    /**
     * Trashes the schema-2 one-file-per-message backups left in Drive. Destructive,
     * so it is only ever run from an explicit request, never from the sync path.
     * Returns the number of files trashed.
     */
    suspend fun cleanupLegacyFiles(accessToken: String, prefs: Prefs): Int = withContext(Dispatchers.IO) {
        val drive = DriveClient(accessToken)
        val folderId = drive.findFileId(DRIVE_FOLDER_NAME, "root") ?: return@withContext 0
        var trashed = 0
        for ((id, name) in drive.listChildren(folderId)) {
            currentCoroutineContext().ensureActive()
            if (!SmsBackupWriter.isLegacyFileName(name)) continue
            drive.trashFile(id)
            trashed++
        }
        prefs.setSmsLegacyFilesCleaned(true)
        trashed
    }

    /** Count, newest message date and highest row ID per month — no bodies read. */
    private fun scanMonths(context: Context, zone: ZoneId): Map<String, MonthSummary> {
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.DATE),
            null, null, "${Telephony.Sms._ID} ASC"
        ) ?: throw IOException("Could not read SMS messages; existing Drive backups are unchanged")

        val summaries = mutableMapOf<String, MonthSummary>()
        cursor.use { rows ->
            val idIdx = rows.getColumnIndexOrThrow(Telephony.Sms._ID)
            val dateIdx = rows.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (rows.moveToNext()) {
                val id = rows.getLong(idIdx)
                val date = if (rows.isNull(dateIdx)) 0L else rows.getLong(dateIdx)
                val month = SmsBackupWriter.monthOf(date, zone)
                val current = summaries[month]
                summaries[month] = MonthSummary(
                    count = (current?.count ?: 0) + 1,
                    maxMessageId = maxOf(current?.maxMessageId ?: Long.MIN_VALUE, id),
                    maxDateMs = maxOf(current?.maxDateMs ?: 0L, date)
                )
            }
        }
        return summaries
    }

    /** Renders one month as NDJSON: a header line then one line per message. */
    private fun buildMonth(context: Context, month: String, zone: ZoneId): String? {
        val bounds = YearMonth.parse(month)
        val start = bounds.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = bounds.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            COLUMNS,
            "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} < ?",
            arrayOf(start.toString(), end.toString()),
            "${Telephony.Sms._ID} ASC"
        ) ?: throw IOException("Could not read SMS messages; existing Drive backups are unchanged")

        return cursor.use { rows ->
            if (rows.count == 0) return@use null
            val indices = COLUMNS.map { rows.getColumnIndexOrThrow(it) }
            val out = StringBuilder(SmsBackupWriter.header(month, rows.count)).append('\n')
            while (rows.moveToNext()) {
                val message = COLUMNS.indices.associate { i ->
                    COLUMNS[i] to when (rows.getType(indices[i])) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> rows.getLong(indices[i])
                        else -> rows.getString(indices[i])
                    }
                }
                out.append(SmsBackupWriter.encode(message)).append('\n')
            }
            out.toString()
        }
    }

    private data class MonthSummary(val count: Int, val maxMessageId: Long, val maxDateMs: Long)

    // No time/type filter: includes received, sent, draft, outbox, failed and queued SMS.
    private val COLUMNS = arrayOf(
        Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
        Telephony.Sms.PERSON, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT,
        Telephony.Sms.PROTOCOL, Telephony.Sms.READ, Telephony.Sms.STATUS,
        Telephony.Sms.TYPE, Telephony.Sms.REPLY_PATH_PRESENT, Telephony.Sms.SUBJECT,
        Telephony.Sms.BODY, Telephony.Sms.SERVICE_CENTER, Telephony.Sms.LOCKED,
        Telephony.Sms.SUBSCRIPTION_ID, Telephony.Sms.ERROR_CODE, Telephony.Sms.SEEN,
        Telephony.Sms.CREATOR
    )
}
