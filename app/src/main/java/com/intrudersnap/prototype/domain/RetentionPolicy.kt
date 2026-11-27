package com.intrudersnap.prototype.domain

/** How long security records are kept. Changing it requires owner authentication. */
object RetentionPolicy {

    val ALLOWED_DAYS = listOf(30, 90, 180, 365)
    const val DEFAULT_DAYS = 90
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun sanitize(days: Int): Int = if (days in ALLOWED_DAYS) days else DEFAULT_DAYS

    /** Records with a timestamp strictly before the returned value are deleted. */
    fun cutoff(now: Long, days: Int): Long = now - sanitize(days) * DAY_MS
}
