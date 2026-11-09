package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest

/** Synthetic transactional fixture ONLY, not production "durability" or an OS-store adapter. */
private class FixtureThrottleStore : PersistentThrottleStore {
    var records: Map<ThrottleKey, PersistentThrottleState> = emptyMap()
    var failReads: Boolean = false
    var failReplacementAt: Int? = null
    var failCommit: Boolean = false
    private val lock = Mutex()

    fun provision(deviceIds: List<DomainId> = listOf(id(6))) {
        val keys = listOf(ThrottleKey(branch, id(4), ThrottleDimension.Account)) +
            deviceIds.map { ThrottleKey(branch, id(4), ThrottleDimension.Device(it)) }
        records = keys.associateWith { PersistentThrottleState(it, Revision(0), 0, moment(), 0, null) }
    }

    override suspend fun <T> transaction(block: suspend (ThrottleTransaction) -> T): T = lock.withLock {
        val working = records.toMutableMap()
        var replacements = 0
        val transaction = object : ThrottleTransaction {
            override suspend fun read(key: ThrottleKey): PersistentThrottleState? {
                check(!failReads) { "synthetic unreadable protected store" }
                return working[key]
            }

            override suspend fun replace(expectedRevision: Revision, state: PersistentThrottleState): Boolean {
                replacements++
                if (failReplacementAt == replacements) return false
                if (working[state.key]?.revision != expectedRevision) return false
                working[state.key] = state
                return true
            }
        }
        val result = block(transaction)
        check(!failCommit) { "synthetic durability failure" }
        records = working
        result
    }
}

class ThrottleTest {
    private val accountKey = ThrottleKey(branch, id(4), ThrottleDimension.Account)

    private fun initial(
        failures: Int = 0,
        delay: Long = 0,
        observed: MonotonicMoment = moment(),
    ): PersistentThrottleState = PersistentThrottleState(accountKey, Revision(0), failures, observed, delay, null)

    @Test
    fun lockoutEscalatesAndNeverResetsOnRebootOrWallClockChanges() {
        val policy = ThrottlePolicy(freeFailures = 0)
        val first = assertIs<ThrottleTransition.Reserved>(ThrottleReducer.begin(initial(), id(10), moment(), policy))
        val failure = assertIs<ThrottleCompletionTransition.Completed>(
            ThrottleReducer.complete(first.state, id(10), CredentialAttemptOutcome.MISMATCH, moment(100), policy),
        )
        assertEquals(1_000, failure.state.remainingDelayMillis)
        val waited = assertIs<ThrottleTransition.Blocked>(ThrottleReducer.begin(failure.state, id(11), moment(600), policy))
        assertEquals(500, waited.state.remainingDelayMillis)
        // There is deliberately no wall-clock parameter to manipulate.
        val restarted = assertIs<ThrottleTransition.Blocked>(
            ThrottleReducer.begin(waited.state, id(12), moment(0, id(901)), policy),
        )
        assertEquals(500, restarted.state.remainingDelayMillis)
        val stillHeld = assertIs<ThrottleTransition.Blocked>(
            ThrottleReducer.begin(restarted.state, id(12), moment(499, id(901)), policy),
        )
        val next = assertIs<ThrottleTransition.Reserved>(
            ThrottleReducer.begin(stillHeld.state, id(12), moment(500, id(901)), policy),
        )
        val secondFailure = assertIs<ThrottleCompletionTransition.Completed>(
            ThrottleReducer.complete(next.state, id(12), CredentialAttemptOutcome.MISMATCH, moment(600, id(901)), policy),
        )
        assertEquals(2, secondFailure.state.failures)
        assertEquals(2_000, secondFailure.state.remainingDelayMillis)
    }

    @Test
    fun sameBootMonotonicRollbackCannotBypassDelayOrGuessReservation() {
        val state = initial(delay = 1_000, observed = moment(10_000))
        val blocked = assertIs<ThrottleTransition.Blocked>(
            ThrottleReducer.begin(state, id(10), moment(9_999), ThrottlePolicy()),
        )
        assertEquals(ThrottleDenial.CLOCK_UNPROVEN, blocked.reason)
        assertEquals(state, blocked.state)
    }

    @Test
    fun crashedOutstandingGuessCountsAsFailureAfterRestart() {
        val policy = ThrottlePolicy(freeFailures = 0)
        val reserved = assertIs<ThrottleTransition.Reserved>(
            ThrottleReducer.begin(initial(), id(10), moment(100), policy),
        )
        val recovered = assertIs<ThrottleTransition.Blocked>(
            ThrottleReducer.begin(reserved.state, id(11), moment(0, id(901)), policy),
        )
        assertEquals(1, recovered.state.failures)
        assertEquals(1_000, recovered.state.remainingDelayMillis)
        assertNull(recovered.state.activeAttempt)
    }

    @Test
    fun successfulCompletionResetsOnlyTheMatchingLiveAttempt() {
        val policy = ThrottlePolicy()
        val reserved = assertIs<ThrottleTransition.Reserved>(
            ThrottleReducer.begin(initial(failures = 2), id(10), moment(100), policy),
        )
        val wrong = assertIs<ThrottleCompletionTransition.Rejected>(
            ThrottleReducer.complete(reserved.state, id(11), CredentialAttemptOutcome.MATCH, moment(200), policy),
        )
        assertEquals(ThrottleDenial.STALE_ATTEMPT, wrong.reason)
        assertEquals(2, wrong.state.failures)
        val success = assertIs<ThrottleCompletionTransition.Completed>(
            ThrottleReducer.complete(reserved.state, id(10), CredentialAttemptOutcome.MATCH, moment(200), policy),
        )
        assertEquals(0, success.state.failures)
        assertNull(success.state.activeAttempt)
        val replay = assertIs<ThrottleCompletionTransition.Rejected>(
            ThrottleReducer.complete(success.state, id(10), CredentialAttemptOutcome.MATCH, moment(201), policy),
        )
        assertEquals(ThrottleDenial.STALE_ATTEMPT, replay.reason)
    }

    @Test
    fun unavailableVerifierDoesNotResetFailuresOrEraseProtectedState() {
        val policy = ThrottlePolicy()
        val reserved = assertIs<ThrottleTransition.Reserved>(
            ThrottleReducer.begin(initial(failures = 2), id(10), moment(100), policy),
        )
        val unavailable = assertIs<ThrottleCompletionTransition.Completed>(
            ThrottleReducer.complete(reserved.state, id(10), CredentialAttemptOutcome.VERIFIER_UNAVAILABLE, moment(200), policy),
        )
        assertEquals(2, unavailable.state.failures)
        assertEquals(1_000, unavailable.state.remainingDelayMillis)
        assertNull(unavailable.state.activeAttempt)
    }

    @Test
    fun expiredOrRebootedSuccessfulHashCannotAuthenticate() {
        val policy = ThrottlePolicy(freeFailures = 0, attemptTimeoutMillis = 1_000)
        val reserved = assertIs<ThrottleTransition.Reserved>(
            ThrottleReducer.begin(initial(), id(10), moment(), policy),
        )
        for (now in listOf(moment(1_000), moment(0, id(901)))) {
            val result = assertIs<ThrottleCompletionTransition.Rejected>(
                ThrottleReducer.complete(reserved.state, id(10), CredentialAttemptOutcome.MATCH, now, policy),
            )
            assertEquals(ThrottleDenial.STALE_ATTEMPT, result.reason)
            assertEquals(1, result.state.failures)
        }
    }

    @Test
    fun failureAndRevisionCountersNeverWrapOrEraseLockout() {
        val policy = ThrottlePolicy(freeFailures = 0, maximumDelayMillis = 86_400_000)
        val reserved = assertIs<ThrottleTransition.Reserved>(
            ThrottleReducer.begin(initial(failures = Int.MAX_VALUE), id(10), moment(), policy),
        )
        val failure = assertIs<ThrottleCompletionTransition.Completed>(
            ThrottleReducer.complete(reserved.state, id(10), CredentialAttemptOutcome.MISMATCH, moment(), policy),
        )
        assertEquals(Int.MAX_VALUE, failure.state.failures)
        assertEquals(86_400_000, failure.state.remainingDelayMillis)
        val exhausted = initial().copy(revision = Revision(Long.MAX_VALUE))
        assertEquals(
            ThrottleTransition.Blocked(exhausted, ThrottleDenial.COUNTER_EXHAUSTED),
            ThrottleReducer.begin(exhausted, id(10), moment(), policy),
        )
    }

    @Test
    fun concurrentCallersGetOnlyOneDurablyReservedGuess(): Unit = runTest {
        val store = FixtureThrottleStore().also { it.provision() }
        val throttle = PersistentLoginThrottle(store)
        val outcomes = (10..29).map { attempt ->
            async { throttle.reserve(branch, id(4), id(6), id(attempt), moment()) }
        }.awaitAll()
        assertEquals(1, outcomes.count { it is ThrottleReservation.Allowed })
        assertEquals(19, outcomes.count { it == ThrottleReservation.Denied(ThrottleDenial.ATTEMPT_IN_PROGRESS) })
        val permit = outcomes.filterIsInstance<ThrottleReservation.Allowed>().single().permit
        assertEquals(
            ThrottleCompletion.Persisted(CredentialAttemptOutcome.MATCH),
            throttle.complete(permit, CredentialAttemptOutcome.MATCH, moment(100)),
        )
        assertTrue(store.records.values.all { it.activeAttempt == null })
    }

    @Test
    fun changingDevicesCannotBypassTheAccountBucket(): Unit = runTest {
        val store = FixtureThrottleStore().also { it.provision(listOf(id(6), id(16))) }
        val throttle = PersistentLoginThrottle(store, ThrottlePolicy(freeFailures = 0))
        val permit = assertIs<ThrottleReservation.Allowed>(
            throttle.reserve(branch, id(4), id(6), id(10), moment()),
        ).permit
        assertIs<ThrottleCompletion.Persisted>(throttle.complete(permit, CredentialAttemptOutcome.MISMATCH, moment(100)))
        val other = throttle.reserve(branch, id(4), id(16), id(11), moment(101))
        assertEquals(ThrottleReservation.Denied(ThrottleDenial.DELAYED), other)
        val otherDevice = checkNotNull(store.records[ThrottleKey(branch, id(4), ThrottleDimension.Device(id(16)))])
        assertNull(otherDevice.activeAttempt)
        assertEquals(0, otherDevice.failures)
    }

    @Test
    fun lockoutSurvivesNewAuthenticatorInstancesOverSameDurableState(): Unit = runTest {
        val store = FixtureThrottleStore().also { it.provision() }
        val policy = ThrottlePolicy(freeFailures = 0)
        val first = PersistentLoginThrottle(store, policy)
        val permit = assertIs<ThrottleReservation.Allowed>(first.reserve(branch, id(4), id(6), id(10), moment())).permit
        assertIs<ThrottleCompletion.Persisted>(first.complete(permit, CredentialAttemptOutcome.MISMATCH, moment(100)))
        val reopened = PersistentLoginThrottle(store, policy)
        assertEquals(
            ThrottleReservation.Denied(ThrottleDenial.DELAYED),
            reopened.reserve(branch, id(4), id(6), id(11), moment(0, id(901))),
        )
        assertEquals(1, checkNotNull(store.records[accountKey]).failures)
    }

    @Test
    fun missingAndUnreadableStoreFailsClosedWithoutRecreatingOrClearingState(): Unit = runTest {
        val store = FixtureThrottleStore()
        val throttle = PersistentLoginThrottle(store)
        assertEquals(
            ThrottleReservation.Denied(ThrottleDenial.STORE_UNAVAILABLE),
            throttle.reserve(branch, id(4), id(6), id(10), moment()),
        )
        assertTrue(store.records.isEmpty())
        store.provision()
        val before = store.records
        store.failReads = true
        assertEquals(
            ThrottleReservation.Denied(ThrottleDenial.STORE_UNAVAILABLE),
            throttle.reserve(branch, id(4), id(6), id(10), moment()),
        )
        assertEquals(before, store.records)
    }

    @Test
    fun atomicWriteConflictRollsBackBothBucketsAndNoMatchCanEscapeFailedDurability(): Unit = runTest {
        val store = FixtureThrottleStore().also { it.provision() }
        val before = store.records
        val throttle = PersistentLoginThrottle(store)
        store.failReplacementAt = 2
        assertEquals(
            ThrottleReservation.Denied(ThrottleDenial.STORE_UNAVAILABLE),
            throttle.reserve(branch, id(4), id(6), id(10), moment()),
        )
        assertEquals(before, store.records)
        store.failReplacementAt = null
        val permit = assertIs<ThrottleReservation.Allowed>(throttle.reserve(branch, id(4), id(6), id(10), moment())).permit
        val reserved = store.records
        store.failCommit = true
        assertEquals(
            ThrottleCompletion.Denied(ThrottleDenial.STORE_UNAVAILABLE),
            throttle.complete(permit, CredentialAttemptOutcome.MATCH, moment(100)),
        )
        assertEquals(reserved, store.records)
        assertFalse(store.records.values.any { it.activeAttempt == null })
    }
}
