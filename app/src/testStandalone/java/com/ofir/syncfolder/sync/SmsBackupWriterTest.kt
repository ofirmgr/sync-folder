package com.ofir.syncfolder.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SmsBackupWriterTest {

    private val jerusalem: ZoneId = ZoneId.of("Asia/Jerusalem")

    @Test fun preservesUnicodeEscapesNullsAndLongMetadata() {
        val body = "שלום 👋\n\"quoted\"\\tab\t\u0000"
        val output = SmsBackupWriter.encode(linkedMapOf(
            "_id" to 9999999999L, "body" to body, "address" to "+972501234567",
            "subject" to null, "date" to 1790000000123L, "sub_id" to 2, "type" to 1
        ))
        val message = JSONObject(output).getJSONObject("message")
        assertEquals(body, message.getString("body"))
        assertEquals(9999999999L, message.getLong("_id"))
        assertEquals(1790000000123L, message.getLong("date"))
        assertEquals("+972501234567", message.getString("address"))
        assertTrue(message.has("subject"))
        assertTrue(message.isNull("subject"))
        assertEquals(2, message.getInt("sub_id"))
    }

    /** A body containing a newline must not split one message across two NDJSON lines. */
    @Test fun encodedMessageIsAlwaysASingleLine() {
        val encoded = SmsBackupWriter.encode(mapOf(
            "_id" to 1L, "body" to "line one\nline two\r\nline three", "date" to 0L
        ))
        assertEquals(1, encoded.lines().size)
        assertEquals(
            "line one\nline two\r\nline three",
            JSONObject(encoded).getJSONObject("message").getString("body")
        )
    }

    @Test fun headerCarriesSchemaVersionMonthAndCount() {
        val header = JSONObject(SmsBackupWriter.header("2026-09", 412))
        assertEquals(3, header.getInt("schemaVersion"))
        assertEquals("2026-09", header.getString("month"))
        assertEquals(412, header.getInt("messageCount"))
    }

    @Test fun fileNamesAreOnePerMonthPerInstallation() {
        assertEquals("sms-install-a-2026-09.ndjson", SmsBackupWriter.fileName("install-a", "2026-09"))
        assertNotEquals(
            SmsBackupWriter.fileName("install-a", "2026-09"),
            SmsBackupWriter.fileName("install-a", "2026-10")
        )
        assertNotEquals(
            SmsBackupWriter.fileName("install-a", "2026-09"),
            SmsBackupWriter.fileName("install-b", "2026-09")
        )
    }

    @Test fun messagesBucketByLocalCalendarMonth() {
        // Jerusalem runs ahead of UTC, so just after local midnight the month has
        // already turned over locally while UTC is still in the previous one. The
        // bucket must follow the device's zone, not the raw epoch value.
        val justIntoOctober = LocalDateTime.of(2026, 10, 1, 1, 0)
            .atZone(jerusalem).toInstant().toEpochMilli()
        assertEquals("2026-10", SmsBackupWriter.monthOf(justIntoOctober, jerusalem))
        assertEquals("2026-09", SmsBackupWriter.monthOf(justIntoOctober, ZoneId.of("UTC")))

        val justBeforeOctober = LocalDateTime.of(2026, 9, 30, 23, 0)
            .atZone(jerusalem).toInstant().toEpochMilli()
        assertEquals("2026-09", SmsBackupWriter.monthOf(justBeforeOctober, jerusalem))
    }

    /** Messages with no date land in a real bucket rather than crashing the run. */
    @Test fun zeroDateStillProducesAMonth() {
        assertEquals("1970-01", SmsBackupWriter.monthOf(0L, ZoneId.of("UTC")))
    }

    @Test fun legacyNamesAreRecognisedAndNewOnesAreNot() {
        assertTrue(SmsBackupWriter.isLegacyFileName("sms-3f2a-4821.json"))
        assertTrue(SmsBackupWriter.isLegacyFileName("sms-install-a-1.json"))
        assertFalse(SmsBackupWriter.isLegacyFileName("sms-install-a-2026-09.ndjson"))
        assertFalse(SmsBackupWriter.isLegacyFileName("holiday-photo.json"))
    }
}
