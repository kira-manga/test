package com.ukkera.pos.database

import com.ukkera.pos.security.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import kotlin.test.*

/** Execution wiring for REMP-R03 wrong-password refusal and REMP-R04's exact
 * frozen schedule. Time is controlled DATA, not a native clock or restart proof.
 * Includes admitted-device switching during account delay. Captured-permit reuse
 * and verifier failure are not covered by this TCP executor. */
internal class RoomAuthenticationTcpThrottleSlice(repository: Path) : LocalAuthenticationEnvironment {
    val inputs: JsonObject
    val schedule: List<JsonObject>
    val policy: ThrottlePolicy
    private val moment: AtomicReference<MonotonicMoment>
    private val issuer = authV1EphemeralId()
    private val verifications = AtomicInteger()
    private var baselineVerifications = 0
    private var baselineEvents = emptySet<com.ukkera.pos.types.DomainId>()
    private lateinit var baseline: AuthV1Snapshot
    private lateinit var otherDevice: PersistentThrottleState
    private var switchedChecks = 0

    init {
        val directory = repository.resolve("qa/pos/designs/remote-employee-authentication")
        val bytes = Files.readAllBytes(directory.resolve("fixtures.json"))
        assertEquals("922509c2936525d54f02e541bd9d521d825ab5451fd75dd5e00dec0ea5f7c9ee", authV1Sha256(bytes))
        assertEquals("11733db43b1db894b73ded18006f0a6cdb86a653f0e94917826c16efc80ea46b",
            authV1Sha256(Files.readAllBytes(directory.resolve("cases.md"))))
        inputs = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        schedule = inputs.getValue("wrong_password_schedule").jsonArray.map { it.jsonObject }
        val throttle = inputs.getValue("throttle_policy").jsonObject
        policy = ThrottlePolicy(throttle.number("freeFailures").toInt(), throttle.number("initialDelayMillis"),
            throttle.number("maximumDelayMillis"), throttle.number("attemptTimeoutMillis"))
        moment = AtomicReference(MonotonicMoment(authV1EphemeralId(), schedule.first().number("complete_elapsed_ms")))
    }

    override fun observe(): LocalAuthenticationObservation = LocalAuthenticationObservation(issuer, moment.get())

    val currentMoment: MonotonicMoment get() = moment.get()
    val verificationCount: Int get() = verifications.get()
    fun observeAt(value: MonotonicMoment) { moment.set(value) }

    /** New synthetic issuer for a genuinely new grouped service, never an old
     * credential/physical-owner adoption. Boot/elapsed remain controlled DATA. */
    fun restartedObservation(value: MonotonicMoment = moment.get()): LocalAuthenticationEnvironment {
        moment.set(value)
        val newIssuer = authV1EphemeralId()
        return LocalAuthenticationEnvironment { LocalAuthenticationObservation(newIssuer, moment.get()) }
    }

    /** Counts calls without substituting any verification result or KDF. */
    fun observeHasher(delegate: LocalPasswordHasher): LocalPasswordHasher = object : LocalPasswordHasher by delegate {
        override suspend fun verify(password: CharArray, expectedBinding: CredentialBinding,
            record: PasswordHashRecord): PasswordVerificationResult {
            verifications.incrementAndGet()
            return delegate.verify(password, expectedBinding, record)
        }
    }

    suspend fun control(command: String, fixture: AuthV1Fixture): String {
        if (command == "begin") {
            baselineVerifications = verifications.get()
            baseline = fixture.snapshot("throttle-baseline")
            baselineEvents = baseline.events.map { it.eventId }.toSet()
            otherDevice = fixture.throttleStates("A", connection = "B").last()
            return "ok"
        }
        if (command == "switching-complete") {
            assertEquals(schedule.count { it.number("delay_ms") > 0 }, switchedChecks)
            return "ok"
        }
        if (command == "matched") {
            assertEquals(inputs.getValue("valid_match_after_delay_elapsed_ms").jsonPrimitive.long, moment.get().elapsedMillis)
            assertEquals(baselineVerifications + schedule.size + 1, verifications.get())
            val states = fixture.throttleStates("A")
            assertTrue(states.all { it.failures == 0 && it.remainingDelayMillis == 0L && it.activeAttempt == null })
            val after = fixture.snapshot("throttle-valid-match")
            assertEquals(1, after.accounts.getValue("A").authV1Active().size)
            assertTrue(after.events.map { it.eventId }.containsAll(baselineEvents), "Historical audit retained")
            return "ok"
        }
        val (phase, indexText) = command.split(":")
        val row = schedule[indexText.toInt()]
        val failure = row.number("failure").toInt()
        when (phase) {
            "before" -> moment.set(moment.get().copy(elapsedMillis = row.number("next_eligible_elapsed_ms") - 1))
            "at" -> moment.set(moment.get().copy(elapsedMillis = row.number("next_eligible_elapsed_ms")))
            "completed", "blocked", "switched" -> {
                assertEquals(baselineVerifications + failure, verifications.get(), "No expensive verification while delayed")
                val states = fixture.throttleStates("A")
                val delay = if (phase == "completed") row.number("delay_ms") else 1L
                val observed = if (phase == "completed") row.number("complete_elapsed_ms")
                    else row.number("next_eligible_elapsed_ms") - 1
                assertEquals(2, states.size)
                for (state in states) {
                    assertEquals(failure, state.failures)
                    assertEquals(delay, state.remainingDelayMillis)
                    assertEquals(observed, state.lastObservation.elapsedMillis)
                    assertNull(state.activeAttempt)
                }
                if (phase == "switched") {
                    assertTrue(row.number("delay_ms") > 0)
                    val switched = fixture.throttleStates("A", connection = "B")
                    assertEquals(2, switched.size)
                    val account = switched.first()
                    assertEquals(failure, account.failures, "Device switch retains account failures")
                    assertEquals(1L, account.remainingDelayMillis, "Device switch retains account hold")
                    assertEquals(observed, account.lastObservation.elapsedMillis)
                    assertNull(account.activeAttempt)
                    val device = switched.last()
                    assertEquals(otherDevice.failures, device.failures)
                    assertEquals(otherDevice.remainingDelayMillis, device.remainingDelayMillis)
                    assertNull(device.activeAttempt)
                    switchedChecks++
                }
                val after = fixture.snapshot("throttle-$phase-$failure")
                for (who in listOf("B", "M")) {
                    assertTrue(authV1SameObservedAccount(baseline.accounts.getValue(who), after.accounts.getValue(who)),
                        "Account A's throttle must preserve unrelated $who")
                }
                assertEquals(baseline.admissions.keys, after.admissions.keys)
                for (who in baseline.admissions.keys) {
                    val before = baseline.admissions.getValue(who)
                    val current = after.admissions.getValue(who)
                    assertEquals(before.scope, current.scope)
                    assertEquals(before.deviceId, current.deviceId)
                    assertEquals(before.revision, current.revision)
                    assertEquals(before.admitted, current.admitted)
                    assertEquals(before.publicKeyIdentifier, current.publicKeyIdentifier)
                    assertEquals(before.publicFingerprint, current.publicFingerprint)
                    assertEquals(before.capabilities, current.capabilities)
                }
                assertTrue(after.accounts.getValue("A").state.sessions.isEmpty(), "No session or issuance for refused password")
                assertTrue(after.events.map { it.eventId }.containsAll(baselineEvents), "Historical audit retained")
            }
            else -> error("Unknown frozen schedule control")
        }
        return "ok"
    }

    private fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.long
}
