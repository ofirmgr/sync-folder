package com.ofir.syncfolder.sync

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Encodes SMS backups as newline-delimited JSON, one calendar month per Drive file.
 *
 * Schema 3 replaced schema 2's one-file-per-message layout: a 10k-message history
 * meant 10k Drive files and two API calls each, which starved folder sync. Lines are
 * encoded one at a time so memory stays bounded by a single message.
 */
internal object SmsBackupWriter {

    const val SCHEMA_VERSION = 3

    /** Matches schema 2 names, e.g. "sms-<uuid>-4821.json". Used only for cleanup. */
    private val LEGACY_NAME = Regex("""^sms-.+-\d+\.json$""")

    private val MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM")

    fun fileName(installationId: String, month: String): String =
        "sms-$installationId-$month.ndjson"

    fun isLegacyFileName(name: String): Boolean = LEGACY_NAME.matches(name)

    /** The "YYYY-MM" bucket a message belongs to, in the device's current zone. */
    fun monthOf(dateMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        MONTH_FORMAT.format(Instant.ofEpochMilli(dateMs).atZone(zone))

    /** The first line of every file: identifies the schema and the month it covers. */
    fun header(month: String, messageCount: Int): String =
        JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("month", month)
            .put("messageCount", messageCount)
            .toString()

    /** One message as a single NDJSON line. Never contains a raw newline. */
    fun encode(message: Map<String, Any?>): String {
        val json = JSONObject()
        message.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        return JSONObject().put("message", json).toString()
    }
}
