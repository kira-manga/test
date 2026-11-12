package com.ukkera.pos.database

import com.ukkera.pos.security.*
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Existing REMP-R03/R04 observables. Only the synthetic pepper port becomes unavailable;
 * the installed verifier, KDF, Room transactions and TCP exchange remain real. This is not
 * a native key/provider failure, process restart or full frozen-case qualification. */
internal class RoomAuthenticationTcpVerifierFailureSlice(repository: Path) : LocalAuthenticationEnvironment {
    private val clock = RoomAuthenticationTcpThrottleSlice(repository)
    val policy: ThrottlePolicy get() = clock.policy
    private val verifications = AtomicInteger()
    private val lastVerification = AtomicReference<PasswordVerificationResult?>()
    private var baselineVerifications = 0
    private lateinit var baseline: AuthV1Snapshot
    private lateinit var baselineBuckets: List<PersistentThrottleState>
    private lateinit var start: MonotonicMoment
    private var retainedDelay = 0L

    override fun observe(): LocalAuthenticationObservation = clock.observe()

    fun observeHasher(delegate: LocalPasswordHasher): LocalPasswordHasher = object : LocalPasswordHasher by delegate {
        override suspend fun verify(password: CharArray, expectedBinding: CredentialBinding,
            record: PasswordHashRecord): PasswordVerificationResult {
            verifications.incrementAndGet()
            val result = delegate.verify(password, expectedBinding, record)
            lastVerification.set(result)
            return result
        }
    }

    suspend fun control(command: String, fixture: AuthV1Fixture): String {
        when (command) {
            "begin" -> {
                baseline = fixture.snapshot("verifier-unavailable-baseline")
                baselineBuckets = fixture.throttleStates("A")
                baselineVerifications = verifications.get()
                start = clock.currentMoment
                fixture.setPepperUnavailable(true)
            }
            "unavailable" -> {
                assertEquals(baselineVerifications + 1, verifications.get())
                assertEquals(PasswordFailure.KEY_UNAVAILABLE,
                    assertIs<PasswordVerificationResult.Unavailable>(lastVerification.get()).reason)
                val states = fixture.throttleStates("A")
                assertEquals(baselineBuckets.size, states.size)
                for ((before, after) in baselineBuckets.zip(states)) {
                    assertEquals(before.key, after.key)
                    assertEquals(before.failures, after.failures, "Unavailable verifier is not a wrong password")
                    assertTrue(after.remainingDelayMillis >= policy.initialDelayMillis)
                    assertNull(after.activeAttempt)
                }
                // Observe the retained deadline; REMP-R03 requires at least the initial delay,
                // so no invented exact unavailable-provider penalty is imposed by this executor.
                retainedDelay = states.maxOf { it.remainingDelayMillis }
                assertNoIssuanceOrAccountChange(fixture, "verifier-unavailable-completed")
            }
            "restore" -> fixture.setPepperUnavailable(false)
            "before" -> clock.observeAt(start.copy(elapsedMillis = start.elapsedMillis + retainedDelay - 1))
            "eligible" -> clock.observeAt(start.copy(elapsedMillis = start.elapsedMillis + retainedDelay))
            "blocked", "blocked-before" -> {
                assertEquals(baselineVerifications + 1, verifications.get(), "Restoring pepper cannot bypass retained protection")
                val states = fixture.throttleStates("A")
                assertEquals(baselineBuckets.size, states.size)
                val elapsed = clock.currentMoment.elapsedMillis - start.elapsedMillis
                for ((before, after) in baselineBuckets.zip(states)) {
                    assertEquals(before.key, after.key)
                    assertEquals(before.failures, after.failures)
                    assertTrue(after.remainingDelayMillis > 0)
                    assertTrue(after.remainingDelayMillis >= policy.initialDelayMillis - elapsed)
                    assertNull(after.activeAttempt)
                }
                assertNoIssuanceOrAccountChange(fixture, "verifier-unavailable-$command")
            }
            "matched" -> {
                assertEquals(baselineVerifications + 2, verifications.get())
                assertIs<PasswordVerificationResult.Match>(lastVerification.get())
                assertTrue(fixture.throttleStates("A").all {
                    it.failures == 0 && it.remainingDelayMillis == 0L && it.activeAttempt == null
                })
                val after = fixture.snapshot("verifier-restored-current-match")
                assertEquals(1, after.accounts.getValue("A").authV1Active().size)
                assertTrue(after.events.map { it.eventId }.containsAll(baseline.events.map { it.eventId }))
            }
            else -> error("Unknown verifier-unavailability control")
        }
        return "ok"
    }

    private suspend fun assertNoIssuanceOrAccountChange(fixture: AuthV1Fixture, label: String) {
        val after = fixture.snapshot(label)
        for (who in listOf("B", "M")) {
            assertTrue(authV1SameObservedAccount(baseline.accounts.getValue(who), after.accounts.getValue(who)),
                "Verifier failure/retained delay cannot change account $who")
        }
        val original = baseline.accounts.getValue("A")
        val account = after.accounts.getValue("A")
        assertEquals(original.credentialRevision, account.credentialRevision)
        assertEquals(original.credentialPresent, account.credentialPresent)
        assertTrue(authV1SamePolicy(original.policy, account.policy))
        assertTrue(account.state.sessions.isEmpty())
        assertTrue(account.issuances.isEmpty())
        assertTrue(after.events.map { it.eventId }.containsAll(baseline.events.map { it.eventId }))
    }
}
