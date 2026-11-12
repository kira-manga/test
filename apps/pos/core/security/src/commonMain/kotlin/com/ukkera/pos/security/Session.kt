package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.MinorUnitsSerializer
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = SessionGenerationSerializer::class)
public data class SessionGeneration(public val value: Long) {
    init {
        require(value >= 0) { "Session generation cannot be negative" }
    }

    public fun next(): SessionGeneration {
        check(value != Long.MAX_VALUE) { "Session generation exhausted; remain locked" }
        return SessionGeneration(value + 1)
    }
}

public object SessionGenerationSerializer : KSerializer<SessionGeneration> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SessionGeneration", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: SessionGeneration): Unit =
        MinorUnitsSerializer.serialize(encoder, value.value)
    override fun deserialize(decoder: Decoder): SessionGeneration {
        val value = MinorUnitsSerializer.deserialize(decoder)
        if (value < 0) throw SerializationException("Session generation cannot be negative")
        return SessionGeneration(value)
    }
}

/** A ticket captures a UI lifecycle, not an authentication credential or a durable operation. */
public class SessionFenceTicket internal constructor(
    internal val owner: InteractiveSessionFence,
    public val generation: SessionGeneration,
)

/**
 * UI-event-loop-confined generation gate. Invalidate synchronously before any suspendable logout,
 * user/company switch, host detach or view teardown. Delayed completions from old contexts cannot
 * publish through [publishIfCurrent]. A new fence instance also rejects old tickets.
 *
 * This is deliberately NOT the server session registry. Do not use it to authorize mutations.
 * All publish/invalidate calls must use the same UI event loop; this is not a cross-thread lock.
 */
public class InteractiveSessionFence(initialGeneration: SessionGeneration = SessionGeneration(0)) {
    public var generation: SessionGeneration = initialGeneration
        private set
    private var exhausted: Boolean = initialGeneration.value == Long.MAX_VALUE

    public fun capture(): SessionFenceTicket {
        check(!exhausted) { "Session generation exhausted; remain locked" }
        return SessionFenceTicket(this, generation)
    }

    public fun invalidate(): SessionGeneration {
        if (generation.value >= Long.MAX_VALUE - 1) {
            generation = SessionGeneration(Long.MAX_VALUE)
            exhausted = true
            return generation // Teardown must still run; never throw before hiding the old user.
        }
        generation = generation.next()
        return generation
    }

    public fun isCurrent(ticket: SessionFenceTicket): Boolean =
        !exhausted && ticket.owner === this && ticket.generation == generation

    public fun publishIfCurrent(ticket: SessionFenceTicket, publish: () -> Unit): Boolean {
        if (!isCurrent(ticket)) return false
        publish()
        return true
    }
}

public enum class SessionStatus {
    ACTIVE,
    REVOKED,
}

@Serializable
public data class LocalSessionRecord(
    public val sessionId: DomainId,
    public val scope: BranchScope,
    public val actorId: DomainId,
    public val deviceId: DomainId,
    public val generation: SessionGeneration,
    public val status: SessionStatus,
    public val authenticationPurpose: AuthenticationPurpose,
)

/**
 * Account state and sessions are one protected durable aggregate. A locally disabled account
 * cannot be reenabled by stale directory synchronization. Reactivation needs a distinct,
 * authorized local transition; [disable] has no inverse here.
 */
public class LocalAccountSessionState(
    public val scope: BranchScope,
    public val actorId: DomainId,
    public val enabled: Boolean,
    public val revision: Revision,
    public val generation: SessionGeneration,
    sessions: List<LocalSessionRecord>,
) {
    init {
        require(sessions.size <= 256) { "Session history requires bounded archival" }
        require(sessions.all { it.scope == scope && it.actorId == actorId })
        require(sessions.map { it.sessionId }.toSet().size == sessions.size)
        require(sessions.all { it.generation.value <= generation.value })
        require(enabled || sessions.none { it.status == SessionStatus.ACTIVE })
    }

    public val sessions: List<LocalSessionRecord> = sessions.toList()
}

public data class LocalSessionPolicy(public val maximumConcurrentSessions: Int = 1) {
    init {
        require(maximumConcurrentSessions in 1..16)
    }
}

public sealed interface SessionTransition {
    public data class Applied(
        public val state: LocalAccountSessionState,
        public val newSession: LocalSessionRecord?,
        public val revokedSessionIds: Set<DomainId>,
    ) : SessionTransition

    public data class Denied(public val reason: SessionTransitionDenial) : SessionTransition
}

public enum class SessionTransitionDenial {
    ACCOUNT_DISABLED,
    EXPLICIT_DEVICE_MOVE_REQUIRED,
    SESSION_LIMIT,
    DUPLICATE_SESSION_ID,
    GENERATION_EXHAUSTED,
    SESSION_HISTORY_FULL,
    SESSION_NOT_ACTIVE,
    ACCOUNT_ALREADY_ENABLED,
}

/**
 * Pure reducers for an authenticated local sign-in/reset/logout path. The adapter must generate
 * unpredictable session IDs and token secrets, verify the human and admitted destination device,
 * and commit this aggregate plus token revocations atomically before acknowledging.
 */
public object LocalSessions {
    public fun signIn(
        state: LocalAccountSessionState,
        sessionId: DomainId,
        deviceId: DomainId,
        policy: LocalSessionPolicy = LocalSessionPolicy(),
        explicitDeviceMove: Boolean = false,
        purpose: AuthenticationPurpose = AuthenticationPurpose.INTERACTIVE,
    ): SessionTransition {
        if (!state.enabled) return SessionTransition.Denied(SessionTransitionDenial.ACCOUNT_DISABLED)
        if (purpose == AuthenticationPurpose.SERVICE) {
            throw IllegalArgumentException("Service authentication is not an employee session")
        }
        if (state.sessions.any { it.sessionId == sessionId }) {
            return SessionTransition.Denied(SessionTransitionDenial.DUPLICATE_SESSION_ID)
        }
        if (state.sessions.size == 256) return SessionTransition.Denied(SessionTransitionDenial.SESSION_HISTORY_FULL)
        val active = state.sessions.filter { it.status == SessionStatus.ACTIVE }
        if (active.isNotEmpty() && policy.maximumConcurrentSessions == 1 && !explicitDeviceMove) {
            return SessionTransition.Denied(SessionTransitionDenial.EXPLICIT_DEVICE_MOVE_REQUIRED)
        }
        if (active.size >= policy.maximumConcurrentSessions && !explicitDeviceMove) {
            return SessionTransition.Denied(SessionTransitionDenial.SESSION_LIMIT)
        }
        // Reserve the final counter value for irreversible, whole-account lockdown.
        if (state.generation.value >= Long.MAX_VALUE - 1 || state.revision.value >= Long.MAX_VALUE - 1) {
            return SessionTransition.Denied(SessionTransitionDenial.GENERATION_EXHAUSTED)
        }
        val next = state.generation.next()
        val revoke = if (explicitDeviceMove) active.map { it.sessionId }.toSet() else emptySet()
        val retained = state.sessions.map {
            if (it.sessionId in revoke) it.copy(status = SessionStatus.REVOKED) else it
        }
        val session = LocalSessionRecord(
            sessionId, state.scope, state.actorId, deviceId, next, SessionStatus.ACTIVE, purpose,
        )
        return SessionTransition.Applied(
            LocalAccountSessionState(
                state.scope, state.actorId, state.enabled, state.revision.next(), next, retained + session,
            ),
            session,
            revoke,
        )
    }

    public fun revoke(state: LocalAccountSessionState, sessionId: DomainId): SessionTransition =
        revokeMatching(state, setOf(sessionId), disableAccount = false)

    /** All sessions revoked without disabling the credential/account; used by an authorized reset. */
    public fun revokeAll(state: LocalAccountSessionState): SessionTransition =
        revokeMatching(state, state.sessions.map { it.sessionId }.toSet(), disableAccount = false)

    /** A caller must establish why these exact sessions are no longer usable; this is not a clock. */
    public fun revokeSelected(state: LocalAccountSessionState, sessionIds: Set<DomainId>): SessionTransition =
        revokeMatching(state, sessionIds.toSet(), disableAccount = false)

    /** Replace just this interactive session; other explicitly concurrent sessions are untouched. */
    public fun rotate(
        state: LocalAccountSessionState,
        previousSessionId: DomainId,
        newSessionId: DomainId,
    ): SessionTransition {
        if (!state.enabled) return SessionTransition.Denied(SessionTransitionDenial.ACCOUNT_DISABLED)
        val previous = state.sessions.singleOrNull { it.sessionId == previousSessionId &&
            it.status == SessionStatus.ACTIVE && it.authenticationPurpose == AuthenticationPurpose.INTERACTIVE }
            ?: return SessionTransition.Denied(SessionTransitionDenial.SESSION_NOT_ACTIVE)
        if (state.sessions.any { it.sessionId == newSessionId }) return SessionTransition.Denied(SessionTransitionDenial.DUPLICATE_SESSION_ID)
        if (state.sessions.size == 256) return SessionTransition.Denied(SessionTransitionDenial.SESSION_HISTORY_FULL)
        if (state.generation.value >= Long.MAX_VALUE - 1 || state.revision.value >= Long.MAX_VALUE - 1) {
            return SessionTransition.Denied(SessionTransitionDenial.GENERATION_EXHAUSTED)
        }
        val generation = state.generation.next()
        val next = previous.copy(sessionId = newSessionId, generation = generation)
        return SessionTransition.Applied(
            LocalAccountSessionState(state.scope, state.actorId, true, state.revision.next(), generation,
                state.sessions.map { if (it.sessionId == previousSessionId) it.copy(status = SessionStatus.REVOKED) else it } + next),
            next, setOf(previousSessionId),
        )
    }

    /** A newer explicit administrative resolution preserves every revoked identity. The
     * protected adapter must separately prove current authority and dependency revisions. */
    public fun reactivate(state: LocalAccountSessionState): SessionTransition {
        if (state.enabled) return SessionTransition.Denied(SessionTransitionDenial.ACCOUNT_ALREADY_ENABLED)
        if (state.revision.value >= Long.MAX_VALUE - 1 || state.generation.value >= Long.MAX_VALUE - 1)
            return SessionTransition.Denied(SessionTransitionDenial.GENERATION_EXHAUSTED)
        check(state.sessions.none { it.status == SessionStatus.ACTIVE })
        return SessionTransition.Applied(LocalAccountSessionState(state.scope, state.actorId, true,
            state.revision.next(), state.generation.next(), state.sessions), null, emptySet())
    }

    public fun disable(state: LocalAccountSessionState): SessionTransition =
        revokeMatching(state, state.sessions.map { it.sessionId }.toSet(), disableAccount = true)

    private fun revokeMatching(
        state: LocalAccountSessionState,
        sessionIds: Set<DomainId>,
        disableAccount: Boolean,
    ): SessionTransition {
        if (state.revision.value >= Long.MAX_VALUE - 1 || state.generation.value >= Long.MAX_VALUE - 1) {
            // Every exhausted transition has the same terminal shape, never a same-revision
            // partial revoke that a stale concurrent writer could undo.
            val terminal = LocalAccountSessionState(
                state.scope, state.actorId, false, Revision(Long.MAX_VALUE), SessionGeneration(Long.MAX_VALUE),
                state.sessions.map { it.copy(status = SessionStatus.REVOKED) },
            )
            return SessionTransition.Applied(
                terminal, null,
                state.sessions.filter { it.status == SessionStatus.ACTIVE }.map { it.sessionId }.toSet(),
            )
        }
        val revoked = state.sessions.filter {
            it.status == SessionStatus.ACTIVE && it.sessionId in sessionIds
        }.map { it.sessionId }.toSet()
        val result = LocalAccountSessionState(
            state.scope, state.actorId, state.enabled && !disableAccount,
            state.revision.next(), state.generation.next(),
            state.sessions.map { if (it.sessionId in revoked) it.copy(status = SessionStatus.REVOKED) else it },
        )
        return SessionTransition.Applied(result, null, revoked)
    }
}

/**
 * No default implementation. An adapter must serialize these mutations with authorization and
 * financial writes in its durable branch store. An IO/key failure denies auth and preserves data;
 * it must never clear sessions/credentials or substitute an empty in-memory registry.
 *
 * Once either counter reaches MAX_VALUE, replacement must only accept the terminal shape
 * (both counters MAX_VALUE, disabled, every session revoked). Enforce that invariant even for
 * stale replacements; never accept an active same-revision state. The pure reducer reserves the
 * final value for this irreversible lockdown; no reset-to-zero recovery path is provided.
 */
public interface LocalSessionTransaction {
    public suspend fun readAccount(scope: BranchScope, actorId: DomainId): LocalAccountSessionState?
    public suspend fun replaceAccount(expectedRevision: Revision, state: LocalAccountSessionState): Boolean
}

/** Immutable original ownership survives logout, disable, explicit device move and recovery. */
@Serializable
public data class PendingOperationOwner(
    public val operationId: OperationId,
    public val scope: BranchScope,
    public val originalActorId: DomainId,
    public val originatingDeviceId: DomainId,
    public val originatingSessionId: DomainId,
)
