package org.futo.inputmethod.latin.fmaboard

/** FMAboard: clips live for 24 hours; pinning protects them from expiry. */
object ClipboardRetention {
    const val DEFAULT_HOURS = 24
    const val HOUR_MILLIS = 60L * 60L * 1000L

    fun isExpired(timestamp: Long, pinned: Boolean, now: Long, hours: Int): Boolean =
        !pinned && timestamp <= now - hours.coerceAtLeast(0) * HOUR_MILLIS
}
