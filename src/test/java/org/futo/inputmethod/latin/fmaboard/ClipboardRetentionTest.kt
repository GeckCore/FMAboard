package org.futo.inputmethod.latin.fmaboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardRetentionTest {
    private val now = 100L * ClipboardRetention.HOUR_MILLIS
    private val cutoff = now - 24L * ClipboardRetention.HOUR_MILLIS

    @Test fun expiresAt24HoursButKeepsNewerClips() {
        assertTrue(ClipboardRetention.isExpired(cutoff - 1, false, now, 24))
        assertTrue(ClipboardRetention.isExpired(cutoff, false, now, 24))
        assertFalse(ClipboardRetention.isExpired(cutoff + 1, false, now, 24))
        assertFalse(ClipboardRetention.isExpired(now, false, now, 24))
    }

    @Test fun pinnedClipsNeverExpire() {
        assertFalse(ClipboardRetention.isExpired(0, true, now, 24))
        assertFalse(ClipboardRetention.isExpired(cutoff, true, now, 0))
    }

    @Test fun respectsCustomDurationAndFutureTimestamps() {
        assertFalse(ClipboardRetention.isExpired(cutoff, false, now, 48))
        assertTrue(ClipboardRetention.isExpired(cutoff, false, now, 1))
        assertFalse(ClipboardRetention.isExpired(now + 1, false, now, 24))
        assertFalse(ClipboardRetention.isExpired(now, false, now - 1, 24))
    }
}
