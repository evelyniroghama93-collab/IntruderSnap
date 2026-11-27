package com.intrudersnap.prototype.auth

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Proof that the owner authenticated very recently. It can only be created inside this module
 * by [OwnerSession] after a successful system authentication, and repository methods that
 * delete or export security data require it as a parameter.
 */
class OwnerProof internal constructor(val issuedAtElapsed: Long)

/** Pure session rules, separated from the clock for unit testing. */
object SessionPolicy {
    /** An unlocked session lasts at most this long, and always ends when the app leaves the screen. */
    const val SESSION_MS = 5 * 60_000L

    /** Destructive actions (clear history, deactivate) need an authentication this recent. */
    const val FRESH_PROOF_MS = 60_000L

    fun isActive(authenticatedAt: Long, now: Long): Boolean =
        authenticatedAt > 0 && now - authenticatedAt in 0 until SESSION_MS

    fun isFresh(authenticatedAt: Long, now: Long): Boolean =
        authenticatedAt > 0 && now - authenticatedAt in 0 until FRESH_PROOF_MS
}

/** In-memory owner session. Never persisted, so killing the process always locks the app. */
object OwnerSession {

    @Volatile
    private var authenticatedAtElapsed: Long = -1L

    private val _active = MutableStateFlow(false)

    /** Observable session state for the UI. Call [refresh] to apply time-based expiry. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun isActive(): Boolean = SessionPolicy.isActive(authenticatedAtElapsed, SystemClock.elapsedRealtime())

    fun refresh() {
        _active.value = isActive()
    }

    internal fun markAuthenticated() {
        authenticatedAtElapsed = SystemClock.elapsedRealtime()
        _active.value = true
    }

    /** Called when the app leaves the screen. */
    fun end() {
        authenticatedAtElapsed = -1L
        _active.value = false
    }

    /** Proof for normal protected actions (view, change settings, export). */
    fun proof(): OwnerProof? =
        if (isActive()) OwnerProof(authenticatedAtElapsed) else null

    /** Proof for destructive actions; requires an authentication within the last minute. */
    fun freshProof(): OwnerProof? =
        if (SessionPolicy.isFresh(authenticatedAtElapsed, SystemClock.elapsedRealtime())) OwnerProof(authenticatedAtElapsed) else null
}
