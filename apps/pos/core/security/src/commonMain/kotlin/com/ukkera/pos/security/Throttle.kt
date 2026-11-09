package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException

public sealed interface ThrottleDimension {
    public data object Account : ThrottleDimension
    public data class Device(public val deviceId: DomainId) : ThrottleDimension
}

public data class ThrottleKey(
    public val scope: BranchScope,
    public val actorId: DomainId,
    public val dimension: ThrottleDimension,
)

public data class ThrottleAttempt(
    public val attemptId: DomainId,
    public val startedAt: MonotonicMoment,
)

/**
 * Provisioned durable state; missing/corrupt/unreadable state is NOT a fresh zero-attempt state.
 * Monotonic boot identity and remaining hold survive restart. No wall-clock value is trusted.
 */
public data class PersistentThrottleState(
    public val key: ThrottleKey,
    public val revision: Revision,
    public val failures: Int,
    public val lastObservation: MonotonicMoment,
    public val remainingDelayMillis: Long,
    public val activeAttempt: ThrottleAttempt?,
) {
    init {
        require(failures >= 0 && remainingDelayMillis in 0..86_400_000)
    }
}

public data class ThrottlePolicy(
    public val freeFailures: Int = 3,
    public val initialDelayMillis: Long = 1_000,
    public val maximumDelayMillis: Long = 900_000,
    public val attemptTimeoutMillis: Long = 120_000,
) {
    init {
        require(freeFailures in 0..5)
        require(initialDelayMillis in 1_000..60_000)
        require(maximumDelayMillis in initialDelayMillis..86_400_000)
        require(attemptTimeoutMillis in 1_000..300_000)
    }
}

public enum class ThrottleDenial {
    DELAYED,
    ATTEMPT_IN_PROGRESS,
    CLOCK_UNPROVEN,
    STORE_UNAVAILABLE,
    STALE_ATTEMPT,
    COUNTER_EXHAUSTED,
}

public enum class CredentialAttemptOutcome {
    MATCH,
    MISMATCH,
    VERIFIER_UNAVAILABLE,
}

public sealed interface ThrottleTransition {
    public data class Reserved(public val state: PersistentThrottleState) : ThrottleTransition
    public data class Blocked(
        public val state: PersistentThrottleState,
        public val reason: ThrottleDenial,
    ) : ThrottleTransition
}

public sealed interface ThrottleCompletionTransition {
    public data class Completed(
        public val state: PersistentThrottleState,
        public val outcome: CredentialAttemptOutcome,
    ) : ThrottleCompletionTransition

    public data class Rejected(
        public val state: PersistentThrottleState,
        public val reason: ThrottleDenial,
    ) : ThrottleCompletionTransition
}

/**
 * Pure state machine. Reserve durably BEFORE an expensive verifier or PIN check. A crash with an
 * outstanding reservation counts as failure, not a free guess. Changing devices cannot reset the
 * account bucket. Same-boot monotonic rollback denies; a new boot conservatively rearms remaining
 * delay instead of trusting elapsed wall time. Persist every returned state atomically.
 */
public object ThrottleReducer {
    public fun begin(
        state: PersistentThrottleState,
        attemptId: DomainId,
        now: MonotonicMoment,
        policy: ThrottlePolicy,
    ): ThrottleTransition {
        if (state.revision.value == Long.MAX_VALUE) {
            return ThrottleTransition.Blocked(state, ThrottleDenial.COUNTER_EXHAUSTED)
        }
        if (sameBootRollback(state.lastObservation, now)) {
            return ThrottleTransition.Blocked(state, ThrottleDenial.CLOCK_UNPROVEN)
        }
        val observed = observe(state, now)
        val active = observed.activeAttempt
        if (active != null) {
            if (active.startedAt.bootId == now.bootId && now.elapsedMillis >= active.startedAt.elapsedMillis &&
                now.elapsedMillis - active.startedAt.elapsedMillis < policy.attemptTimeoutMillis
            ) {
                return ThrottleTransition.Blocked(observed, ThrottleDenial.ATTEMPT_IN_PROGRESS)
            }
            return ThrottleTransition.Blocked(
                failed(observed, now, policy),
                ThrottleDenial.DELAYED,
            )
        }
        if (observed.remainingDelayMillis > 0) {
            return ThrottleTransition.Blocked(observed, ThrottleDenial.DELAYED)
        }
        return ThrottleTransition.Reserved(
            observed.copy(activeAttempt = ThrottleAttempt(attemptId, now)),
        )
    }

    public fun complete(
        state: PersistentThrottleState,
        attemptId: DomainId,
        outcome: CredentialAttemptOutcome,
        now: MonotonicMoment,
        policy: ThrottlePolicy,
    ): ThrottleCompletionTransition {
        fun reject(reason: ThrottleDenial): ThrottleCompletionTransition =
            ThrottleCompletionTransition.Rejected(state, reason)
        if (state.revision.value == Long.MAX_VALUE) return reject(ThrottleDenial.COUNTER_EXHAUSTED)
        if (state.activeAttempt?.attemptId != attemptId) return reject(ThrottleDenial.STALE_ATTEMPT)
        if (sameBootRollback(state.lastObservation, now)) return reject(ThrottleDenial.CLOCK_UNPROVEN)
        val observed = observe(state, now)
        val started = checkNotNull(observed.activeAttempt).startedAt
        if (started.bootId != now.bootId || now.elapsedMillis < started.elapsedMillis ||
            now.elapsedMillis - started.elapsedMillis >= policy.attemptTimeoutMillis
        ) {
            return ThrottleCompletionTransition.Rejected(failed(observed, now, policy), ThrottleDenial.STALE_ATTEMPT)
        }
        val next = when (outcome) {
            CredentialAttemptOutcome.MATCH -> observed.copy(failures = 0, remainingDelayMillis = 0, activeAttempt = null)
            CredentialAttemptOutcome.MISMATCH -> failed(observed, now, policy)
            CredentialAttemptOutcome.VERIFIER_UNAVAILABLE -> observed.copy(
                remainingDelayMillis = maxOf(observed.remainingDelayMillis, policy.initialDelayMillis),
                activeAttempt = null,
            )
        }
        return ThrottleCompletionTransition.Completed(next, outcome)
    }

    private fun sameBootRollback(last: MonotonicMoment, now: MonotonicMoment): Boolean =
        last.bootId == now.bootId && now.elapsedMillis < last.elapsedMillis

    private fun observe(state: PersistentThrottleState, now: MonotonicMoment): PersistentThrottleState {
        val elapsed = if (state.lastObservation.bootId == now.bootId) {
            now.elapsedMillis - state.lastObservation.elapsedMillis
        } else {
            0L // A reboot or clock source replacement never grants elapsed hold time.
        }
        return state.copy(
            revision = state.revision.next(),
            remainingDelayMillis = maxOf(0, state.remainingDelayMillis - elapsed),
            lastObservation = now,
        )
    }

    private fun failed(
        state: PersistentThrottleState,
        now: MonotonicMoment,
        policy: ThrottlePolicy,
    ): PersistentThrottleState {
        val failures = if (state.failures == Int.MAX_VALUE) Int.MAX_VALUE else state.failures + 1
        var delay = if (failures > policy.freeFailures) policy.initialDelayMillis else 0L
        repeat((failures.toLong() - policy.freeFailures - 1).coerceIn(0, 31).toInt()) {
            delay = minOf(policy.maximumDelayMillis, delay * 2)
        }
        return state.copy(
            failures = failures,
            lastObservation = now,
            remainingDelayMillis = maxOf(state.remainingDelayMillis, delay),
            activeAttempt = null,
        )
    }
}

public interface ThrottleTransaction {
    /** Null means unavailable/unprovisioned: the authenticator must deny, not recreate it here. */
    public suspend fun read(key: ThrottleKey): PersistentThrottleState?
    public suspend fun replace(expectedRevision: Revision, state: PersistentThrottleState): Boolean
}

public interface PersistentThrottleStore {
    /** One durable serializable transaction; exception MUST roll back all replacements. */
    public suspend fun <T> transaction(block: suspend (ThrottleTransaction) -> T): T
}

/** Pure paired decision, shared by the actual writer and original-history verification.
 * These values grant neither a password match nor a session. A blocked peer must never leave
 * the other bucket reserved; only changed rejected/blocked states are retained in that case. */
public data class ThrottlePairDecision(
    public val account: PersistentThrottleState,
    public val device: PersistentThrottleState,
    public val denial: ThrottleDenial?,
)

public object ThrottlePairReducer {
    public fun begin(account: PersistentThrottleState, device: PersistentThrottleState,
        attemptId: DomainId, now: MonotonicMoment, policy: ThrottlePolicy): ThrottlePairDecision {
        requirePair(account, device)
        val a = ThrottleReducer.begin(account, attemptId, now, policy)
        val d = ThrottleReducer.begin(device, attemptId, now, policy)
        val denial = (a as? ThrottleTransition.Blocked)?.reason ?: (d as? ThrottleTransition.Blocked)?.reason
        return if (denial != null) ThrottlePairDecision(
            (a as? ThrottleTransition.Blocked)?.state ?: account,
            (d as? ThrottleTransition.Blocked)?.state ?: device, denial)
        else ThrottlePairDecision((a as ThrottleTransition.Reserved).state,
            (d as ThrottleTransition.Reserved).state, null)
    }

    public fun complete(account: PersistentThrottleState, device: PersistentThrottleState,
        attemptId: DomainId, outcome: CredentialAttemptOutcome, now: MonotonicMoment,
        policy: ThrottlePolicy): ThrottlePairDecision {
        requirePair(account, device)
        val a = ThrottleReducer.complete(account, attemptId, outcome, now, policy)
        val d = ThrottleReducer.complete(device, attemptId, outcome, now, policy)
        val denial = (a as? ThrottleCompletionTransition.Rejected)?.reason ?:
            (d as? ThrottleCompletionTransition.Rejected)?.reason
        return if (denial != null) ThrottlePairDecision(
            (a as? ThrottleCompletionTransition.Rejected)?.state ?: account,
            (d as? ThrottleCompletionTransition.Rejected)?.state ?: device, denial)
        else ThrottlePairDecision((a as ThrottleCompletionTransition.Completed).state,
            (d as ThrottleCompletionTransition.Completed).state, null)
    }

    private fun requirePair(account: PersistentThrottleState, device: PersistentThrottleState) {
        require(account.key.scope == device.key.scope && account.key.actorId == device.key.actorId &&
            account.key.dimension == ThrottleDimension.Account && device.key.dimension is ThrottleDimension.Device)
    }
}

public class ThrottlePermit private constructor(
    public val accountKey: ThrottleKey,
    public val deviceKey: ThrottleKey,
    public val attemptId: DomainId,
) {
    internal companion object {
        internal fun reserved(accountKey: ThrottleKey, deviceKey: ThrottleKey, attemptId: DomainId): ThrottlePermit =
            ThrottlePermit(accountKey, deviceKey, attemptId)
    }
}

public sealed interface ThrottleReservation {
    public data class Allowed(public val permit: ThrottlePermit) : ThrottleReservation
    public data class Denied(public val reason: ThrottleDenial) : ThrottleReservation
}

public sealed interface ThrottleCompletion {
    public data class Persisted(public val outcome: CredentialAttemptOutcome) : ThrottleCompletion
    public data class Denied(public val reason: ThrottleDenial) : ThrottleCompletion
}

/**
 * No in-memory production implementation. The global account + admitted-device/account buckets
 * are read/reserved/finished together, before acknowledging authentication. A matching hash whose
 * durable completion fails MUST NOT create a session. This protects concurrent/restarted callers,
 * not a database maliciously rolled back by an attacker; that remains a protected-storage gate.
 */
public class PersistentLoginThrottle(
    private val store: PersistentThrottleStore,
    private val policy: ThrottlePolicy = ThrottlePolicy(),
) {
    public suspend fun reserve(
        scope: BranchScope,
        actorId: DomainId,
        deviceId: DomainId,
        attemptId: DomainId,
        now: MonotonicMoment,
    ): ThrottleReservation = try {
        store.transaction { transaction ->
            val accountKey = ThrottleKey(scope, actorId, ThrottleDimension.Account)
            val deviceKey = ThrottleKey(scope, actorId, ThrottleDimension.Device(deviceId))
            val account = transaction.read(accountKey)
            val device = transaction.read(deviceKey)
            if (account == null || device == null || account.key != accountKey || device.key != deviceKey) {
                return@transaction ThrottleReservation.Denied(ThrottleDenial.STORE_UNAVAILABLE)
            }
            val decision = ThrottlePairReducer.begin(account, device, attemptId, now, policy)
            for ((before, after) in listOf(account to decision.account, device to decision.device)) {
                if (after != before) check(transaction.replace(before.revision, after)) { "Throttle write conflict" }
            }
            decision.denial?.let { ThrottleReservation.Denied(it) }
                ?: ThrottleReservation.Allowed(ThrottlePermit.reserved(accountKey, deviceKey, attemptId))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ThrottleReservation.Denied(ThrottleDenial.STORE_UNAVAILABLE)
    }

    public suspend fun complete(
        permit: ThrottlePermit,
        outcome: CredentialAttemptOutcome,
        now: MonotonicMoment,
    ): ThrottleCompletion = try {
        store.transaction { transaction ->
            val account = transaction.read(permit.accountKey)
            val device = transaction.read(permit.deviceKey)
            if (account == null || device == null ||
                account.key != permit.accountKey || device.key != permit.deviceKey
            ) return@transaction ThrottleCompletion.Denied(ThrottleDenial.STORE_UNAVAILABLE)
            val decision = ThrottlePairReducer.complete(account, device, permit.attemptId, outcome, now, policy)
            for ((before, after) in listOf(account to decision.account, device to decision.device)) {
                if (after != before) check(transaction.replace(before.revision, after)) { "Throttle write conflict" }
            }
            decision.denial?.let { ThrottleCompletion.Denied(it) } ?: ThrottleCompletion.Persisted(outcome)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ThrottleCompletion.Denied(ThrottleDenial.STORE_UNAVAILABLE)
    }
}
