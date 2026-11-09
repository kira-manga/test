package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CredentialAttemptOutcome
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.PersistentLoginThrottle
import com.ukkera.pos.security.PersistentThrottleState
import com.ukkera.pos.security.PersistentThrottleStore
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.ThrottleAttempt
import com.ukkera.pos.security.ThrottleCompletion
import com.ukkera.pos.security.ThrottleDenial
import com.ukkera.pos.security.ThrottleDimension
import com.ukkera.pos.security.ThrottleKey
import com.ukkera.pos.security.ThrottlePermit
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.security.ThrottleReservation
import com.ukkera.pos.security.ThrottleTransaction
import com.ukkera.pos.security.LocalAuthConfiguration
import com.ukkera.pos.security.LocalPasswordHasher
import com.ukkera.pos.security.PasswordHashRecord
import com.ukkera.pos.security.PasswordVerificationResult
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One privately captured credential attempt, not login or session issuance. The expensive human
 * verifier runs outside these transactions. The existing paired-bucket reducer/helper stays
 * unchanged; its callbacks borrow the SAME IMMEDIATE transaction as dependency validation.
 *
 * Missing/stale/unreadable state never becomes a fresh bucket. This detects supported local
 * incarnation changes, not malicious replacement of a database and all of its protected rows.
 */
internal class RoomPersistentThrottleStore private constructor(
    private val store: RoomLocalSecurityStore,
    private val captured: CapturedThrottleDependencies,
) {
    private val lifecycle = Mutex()
    private var reservationStarted = false
    private var completionStarted = false
    private var issuedPermit: ThrottlePermit? = null
    private var issuedAttempt: ThrottleAttempt? = null
    // The historical pure helper maps Exception to STORE_UNAVAILABLE. Do not let that erase a
    // real Room writer/cleanup uncertainty after the callback returns or is caught by the helper.
    private var storageUncertain = false
    private fun requireKnownStorageOutcome() { if (storageUncertain) localStorageUncertain() }

    /** Snapshot remains private to the captured attempt; the expensive verifier never holds Room. */
    suspend fun verifyPassword(hasher: LocalPasswordHasher, password: CharArray): PasswordVerificationResult {
        lifecycle.withLock {
            currentCoroutineContext().ensureActive()
            if (issuedPermit == null || completionStarted || verificationStarted) protectedLocalUnavailable()
            verificationStarted = true
        }
        val result = hasher.verify(password, captured.credential.binding, captured.credential)
        lifecycle.withLock {
            currentCoroutineContext().ensureActive()
            if (completionStarted) protectedLocalUnavailable()
            verificationMatched = result is PasswordVerificationResult.Match
        }
        return result
    }
    private var verificationStarted = false
    private var verificationMatched = false

    fun attemptValidAt(now: MonotonicMoment): Boolean {
        val started = issuedAttempt?.startedAt ?: return false
        return started.bootId == now.bootId && now.elapsedMillis >= started.elapsedMillis &&
            now.elapsedMillis - started.elapsedMillis < captured.policy.attemptTimeoutMillis
    }

    /** A capture represents one reserve, including denied/cancelled/uncertain reserves. */
    suspend fun reserve(now: MonotonicMoment): ThrottleReservation = lifecycle.withLock {
        currentCoroutineContext().ensureActive()
        if (reservationStarted) return@withLock ThrottleReservation.Denied(ThrottleDenial.STALE_ATTEMPT)
        reservationStarted = true
        val request = RuntimeThrottleInvocation(captured.attemptId, now)
        val reservationStore = object : PersistentThrottleStore {
            override suspend fun <T> transaction(block: suspend (ThrottleTransaction) -> T): T =
                guardedTransaction(null, request, block)
        }
        // Never use the helper's default or a supplied replacement policy.
        val result = PersistentLoginThrottle(reservationStore, captured.policy)
            .reserve(captured.scope, captured.actorId, captured.deviceId, captured.attemptId, now)
        requireKnownStorageOutcome()
        if (result is ThrottleReservation.Allowed) {
            issuedPermit = result.permit
            issuedAttempt = ThrottleAttempt(captured.attemptId, now)
        }
        result
    }

    suspend fun complete(
        permit: ThrottlePermit,
        outcome: CredentialAttemptOutcome,
        now: MonotonicMoment,
    ): ThrottleCompletion = lifecycle.withLock {
        currentCoroutineContext().ensureActive()
        // Even identical caller-supplied coordinates cannot transplant a prior permit into a
        // newly captured incarnation (e.g. following a credential/admission/policy change).
        if (permit !== issuedPermit || permit.accountKey != captured.accountKey || permit.deviceKey != captured.deviceKey ||
            permit.attemptId != captured.attemptId
        ) return@withLock ThrottleCompletion.Denied(ThrottleDenial.STORE_UNAVAILABLE)
        if (completionStarted || (outcome == CredentialAttemptOutcome.MATCH && !verificationMatched))
            return@withLock ThrottleCompletion.Denied(ThrottleDenial.STALE_ATTEMPT)
        // Monotonic timestamps need not be unique. Retire this completion capability BEFORE
        // the writer, including errors/cancellation/unknown COMMIT, so a reused ID+same tick
        // cannot give a completed old permit authority over a new reservation.
        completionStarted = true
        val expectedAttempt = checkNotNull(issuedAttempt)
        val request = RuntimeThrottleInvocation(captured.attemptId, now, outcome)
        val completionStore = object : PersistentThrottleStore {
            override suspend fun <T> transaction(block: suspend (ThrottleTransaction) -> T): T =
                guardedTransaction(expectedAttempt, request, block)
        }
        val result = PersistentLoginThrottle(completionStore, captured.policy).complete(permit, outcome, now)
        requireKnownStorageOutcome()
        result
    }

    /**
     * Actual verifier MATCH completion and the supplied session/approval/audit mutation share ONE actual
     * IMMEDIATE transaction. The helper cannot publish success if mutation/CAS/COMMIT fails.
     * Any value captured before an uncertain COMMIT is discarded, not returned as authentication.
     */
    suspend fun <T : Any> completeAndApply(
        permit: ThrottlePermit,
        now: MonotonicMoment,
        apply: suspend (RoomLocalSecurityTransaction) -> T,
    ): RoomThrottleApplication<T> = lifecycle.withLock {
        currentCoroutineContext().ensureActive()
        if (permit !== issuedPermit || permit.accountKey != captured.accountKey || permit.deviceKey != captured.deviceKey ||
            permit.attemptId != captured.attemptId || completionStarted || !verificationMatched) {
            return@withLock RoomThrottleApplication.Denied
        }
        completionStarted = true
        val expectedAttempt = checkNotNull(issuedAttempt)
        val request = RuntimeThrottleInvocation(captured.attemptId, now, CredentialAttemptOutcome.MATCH)
        var value: T? = null
        val completionStore = object : PersistentThrottleStore {
            override suspend fun <R> transaction(block: suspend (ThrottleTransaction) -> R): R =
                guardedTransaction(expectedAttempt, request, block) { transaction, result ->
                    if (result is ThrottleCompletion.Persisted && result.outcome == CredentialAttemptOutcome.MATCH) {
                        value = apply(transaction)
                    }
                }
        }
        val completed = PersistentLoginThrottle(completionStore, captured.policy).complete(permit, CredentialAttemptOutcome.MATCH, now)
        requireKnownStorageOutcome()
        if (completed is ThrottleCompletion.Persisted && completed.outcome == CredentialAttemptOutcome.MATCH) {
            RoomThrottleApplication.Applied(checkNotNull(value))
        } else RoomThrottleApplication.Denied
    }

    private suspend fun <T> guardedTransaction(
        expectedAttempt: ThrottleAttempt?,
        invocation: RuntimeThrottleInvocation,
        block: suspend (ThrottleTransaction) -> T,
        after: suspend (RoomLocalSecurityTransaction, T) -> Unit = { _, _ -> },
    ): T = try { store.transaction { transaction ->
        if (!transaction.belongsTo(captured.database)) transaction.refuse()
        val dependencies = validateCaptured(transaction)
        // Real protected reads on this same loan; no test store or caller state supplies the
        // before/after values. Legacy unowned fixtures retain their original write behavior.
        val beforeAccount = if (dependencies != null) transaction.readThrottle(captured.accountKey) ?: transaction.refuse() else null
        val beforeDevice = if (dependencies != null) transaction.readThrottle(captured.deviceKey) ?: transaction.refuse() else null
        if (expectedAttempt != null) {
            for (key in listOf(captured.accountKey, captured.deviceKey)) {
                val pending = transaction.readThrottle(key)?.activeAttempt
                // Null/different IDs keep the original helper's STALE behavior. A reused same
                // ID with a newer start must not be cleared by a still-outstanding old verifier.
                // Revisions/failures can legitimately differ/change and are NOT frozen here.
                if (pending?.attemptId == expectedAttempt.attemptId && pending != expectedAttempt) transaction.refuse()
            }
        }
        val restricted = object : ThrottleTransaction {
            private fun requireKey(key: ThrottleKey) {
                if (key != captured.accountKey && key != captured.deviceKey) transaction.refuse()
            }

            override suspend fun read(key: ThrottleKey): PersistentThrottleState? {
                requireKey(key)
                return transaction.readThrottle(key)
            }

            override suspend fun replace(expectedRevision: Revision, state: PersistentThrottleState): Boolean {
                requireKey(state.key)
                return transaction.replaceThrottle(expectedRevision, state)
            }
        }
        val result = block(restricted)
        if (dependencies != null) {
            val denial = when (result) {
                is ThrottleReservation.Allowed -> { check(invocation.outcome == null); null }
                is ThrottleReservation.Denied -> { check(invocation.outcome == null); result.reason }
                is ThrottleCompletion.Persisted -> { check(invocation.outcome == result.outcome); null }
                is ThrottleCompletion.Denied -> { check(invocation.outcome != null); result.reason }
                else -> transaction.refuse()
            }
            transaction.historyThrottle(RuntimeThrottleEvidenceV1.capture(invocation, captured.policy, dependencies,
                checkNotNull(beforeAccount), checkNotNull(beforeDevice),
                transaction.readThrottle(captured.accountKey) ?: transaction.refuse(),
                transaction.readThrottle(captured.deviceKey) ?: transaction.refuse(), denial))
        }
        // Issuance/approval finalizers can supersede the throttle purpose, never the opposite.
        // Mixed-write events do not receive throttle-only startup admission.
        after(transaction, result)
        result
    } } catch (uncertain: RoomLocalStorageUncertain) {
        storageUncertain = true
        throw uncertain
    }

    private suspend fun validateCaptured(transaction: RoomLocalSecurityTransaction): RuntimeThrottleDependenciesV1? {
        val current = transaction.readProtectedAccount(captured.scope, captured.actorId) ?: transaction.refuse()
        val account = current.state
        val policy = transaction.readPolicy(captured.scope, captured.actorId) ?: transaction.refuse()
        val admission = transaction.readAdmission(captured.scope, captured.deviceId) ?: transaction.refuse()
        if (!account.enabled || account.revision != captured.accountRevision ||
            account.generation != captured.sessionGeneration || current.credential == null ||
            current.credentialRevision != captured.credentialRevision || policy.revision != captured.policyRevision ||
            policy.throttlePolicy != captured.policy || !admission.admitted || admission.revision != captured.admissionRevision
        ) transaction.refuse()
        val configuration = captured.configuration?.let { expected ->
            val configuration = transaction.readAuthConfiguration(captured.scope, captured.actorId) ?: transaction.refuse()
            if (configuration.revision != expected.revision || configuration.login != expected.login || configuration.tokens != expected.tokens) transaction.refuse()
            configuration
        }
        if (store.database.runtimeHistoryOwner == null) return null
        fun digest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
        return RuntimeThrottleDependenciesV1(digest(ProtectedLocalSecurityCodec.encodeAccount(current)),
            digest(LocalEffectivePolicyCodec.encodePolicy(policy)), digest(ProtectedLocalSecurityCodec.encodeAdmission(admission)),
            configuration?.let { digest(LocalAuthenticationCodec.encodeConfiguration(it)) })
    }

    companion object {
        /**
         * Captures authenticated records only. Null means explicitly missing/inactive inputs;
         * corrupt/key/provider/transaction failures throw a fixed sanitized unavailable error.
         * No state is created, reset, hashed or activated by capture.
         */
        suspend fun capture(
            store: RoomLocalSecurityStore,
            scope: BranchScope,
            actorId: DomainId,
            deviceId: DomainId,
            attemptId: DomainId,
            configuration: LocalAuthConfiguration? = null,
        ): RoomPersistentThrottleStore? = store.transaction { transaction ->
            val current = transaction.readProtectedAccount(scope, actorId) ?: return@transaction null
            val account = current.state
            val policy = transaction.readPolicy(scope, actorId) ?: return@transaction null
            val admission = transaction.readAdmission(scope, deviceId) ?: return@transaction null
            if (!account.enabled || current.credential == null || !admission.admitted ||
                account.revision.value == Long.MAX_VALUE || account.generation.value == Long.MAX_VALUE
            ) return@transaction null
            val accountKey = ThrottleKey(scope, actorId, ThrottleDimension.Account)
            val deviceKey = ThrottleKey(scope, actorId, ThrottleDimension.Device(deviceId))
            // Unequal bucket revisions/failure counts are legitimate; never normalize them.
            if (transaction.readThrottle(accountKey) == null || transaction.readThrottle(deviceKey) == null) {
                return@transaction null
            }
            if (configuration != null) {
                val currentConfiguration = transaction.readAuthConfiguration(scope, actorId) ?: return@transaction null
                if (configuration.scope != scope || configuration.actorId != actorId || configuration.revision != currentConfiguration.revision ||
                    configuration.login != currentConfiguration.login || configuration.tokens != currentConfiguration.tokens) return@transaction null
            }
            RoomPersistentThrottleStore(store, CapturedThrottleDependencies(
                store.database, scope, actorId, deviceId, attemptId,
                account.revision, account.generation, current.credentialRevision,
                policy.revision, admission.revision, policy.throttlePolicy, accountKey, deviceKey, checkNotNull(current.credential), configuration,
            ))
        }
    }
}

/** Private construction; ownership uses the exact Room object, never a guessed path or ID. */
private class CapturedThrottleDependencies(
    val database: MasterRoomDatabase,
    val scope: BranchScope,
    val actorId: DomainId,
    val deviceId: DomainId,
    val attemptId: DomainId,
    val accountRevision: Revision,
    val sessionGeneration: SessionGeneration,
    val credentialRevision: Revision,
    val policyRevision: Revision,
    val admissionRevision: Revision,
    val policy: ThrottlePolicy,
    val accountKey: ThrottleKey,
    val deviceKey: ThrottleKey,
    val credential: PasswordHashRecord,
    val configuration: LocalAuthConfiguration?,
)

internal sealed interface RoomThrottleApplication<out T> {
    class Applied<T>(val value: T) : RoomThrottleApplication<T>
    data object Denied : RoomThrottleApplication<Nothing>
}
