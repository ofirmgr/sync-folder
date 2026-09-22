package com.ofir.syncfolder.data

import org.junit.Assert.*
import org.junit.Test

/**
 * The month state is what makes a cancelled SMS backfill resumable, so a parse
 * failure must degrade to "nothing backed up yet" rather than throwing inside the
 * worker.
 */
class SmsMonthStateTest {

    @Test fun roundTripsEveryMonth() {
        val state = mapOf(
            "2026-08" to SmsMonthState(fileId = "1AbC", count = 412, maxMessageId = 9_812L),
            "2026-09" to SmsMonthState(fileId = "2DeF", count = 37, maxMessageId = 10_038L)
        )
        val parsed = Prefs.parseMonthState(Prefs.serializeMonthState(state))
        assertEquals(state, parsed)
    }

    @Test fun emptyAndMissingStateParseToNoMonths() {
        assertTrue(Prefs.parseMonthState(null).isEmpty())
        assertTrue(Prefs.parseMonthState("").isEmpty())
        assertTrue(Prefs.parseMonthState("   ").isEmpty())
        assertTrue(Prefs.parseMonthState("{}").isEmpty())
    }

    @Test fun corruptStateParsesToNoMonthsInsteadOfThrowing() {
        assertTrue(Prefs.parseMonthState("not json").isEmpty())
        assertTrue(Prefs.parseMonthState("""{"2026-09":{"id":"x"}}""").isEmpty())
    }

    /** A changed count or a new highest ID is what triggers a month's re-upload. */
    @Test fun fingerprintDistinguishesChangedMonths() {
        val stored = SmsMonthState(fileId = "1AbC", count = 412, maxMessageId = 9_812L)
        assertEquals(stored, stored.copy())
        assertNotEquals(stored, stored.copy(count = 413))
        assertNotEquals(stored, stored.copy(maxMessageId = 9_813L))
    }
}
