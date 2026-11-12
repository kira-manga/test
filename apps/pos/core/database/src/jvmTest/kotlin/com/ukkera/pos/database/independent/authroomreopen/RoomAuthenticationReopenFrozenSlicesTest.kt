package com.ukkera.pos.database

import androidx.room3.Room
import com.ukkera.pos.security.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** Wires REMP-R10's retained-state/currentness observables through actual confirmed
 * grouped-service close and fresh Room/service open. Native keys, peers, issuer
 * and boot observations remain synthetic. No OS-process, RPC or full-case claim. */
class RoomAuthenticationReopenFrozenSlicesTest {
    @Test fun rempR10_retainedDelayAuditAndOldBearersAcrossServiceReopen(): Unit = runBlocking {
        withFixture("retained") { fixture, schedule, path ->
            prepareRetainedDelay(fixture, schedule)
            val before = fixture.snapshot("before-reopen")
            val buckets = fixture.throttleStates("A")
            val business = businessRows(path)
            val oldAccess = fixture.access("B")
            val oldRefresh = fixture.refresh("B")
            fixture.closeService()
            fixture.reopenStoredService(schedule.restartedObservation())
            val reopened = fixture.snapshot("after-reopen")
            assertSameRetainedState(before, reopened)
            assertEquals(buckets, fixture.throttleStates("A"), "Reopen must read, not re-provision, both retained buckets")
            assertEquals(business, businessRows(path))
            assertEquals(LocalSessionViewResult.Denied, fixture.endpoint.current(oldAccess))
            assertEquals(LocalSessionViewResult.Denied, fixture.endpoint.current(fixture.access("B")))
            assertRefused(fixture.endpoint.refresh(oldRefresh))
            assertRefused(fixture.endpoint.refresh(fixture.refresh("B")))
            val calls = schedule.verificationCount
            assertIs<LocalAuthenticationResult.Denied>(fixture.signIn("A"))
            assertEquals(calls, schedule.verificationCount)
            // REMP-R10 preserves protection; it does not prohibit recording the
            // denied observation under a fresh storage revision.
            val deniedBuckets = fixture.throttleStates("A")
            assertEquals(buckets.size, deniedBuckets.size)
            for ((retained, observed) in buckets.zip(deniedBuckets)) {
                assertEquals(retained.key, observed.key)
                assertEquals(retained.failures, observed.failures)
                assertEquals(retained.lastObservation, observed.lastObservation)
                assertEquals(retained.remainingDelayMillis, observed.remainingDelayMillis)
                assertEquals(retained.activeAttempt, observed.activeAttempt)
            }
            schedule.control("at:${schedule.schedule.lastIndex}", fixture)
            val signedIn = assertIs<LocalAuthenticationResult.Authenticated>(fixture.signIn("A")).tokens
            assertIs<LocalSessionViewResult.Current>(fixture.endpoint.current(fixture.access(signedIn, "A")))
            schedule.control("matched", fixture)
            assertEquals(business, businessRows(path))
            assertTrue(fixture.snapshot("after-local-relogin").events.map { it.eventId }.containsAll(before.events.map { it.eventId }))
        }
    }

    @Test fun rempR10_rollbackAndNewBootCannotCreditRetainedDelay(): Unit = runBlocking {
        withFixture("boot") { fixture, schedule, path ->
            prepareRetainedDelay(fixture, schedule)
            val before = fixture.snapshot("before-clock-reopen")
            val business = businessRows(path)
            val calls = schedule.verificationCount
            val retainedDelay = schedule.schedule.last().getValue("delay_ms").jsonPrimitive.long
            val originalMoment = schedule.currentMoment
            fixture.closeService()
            fixture.reopenStoredService(schedule.restartedObservation(originalMoment.copy(
                elapsedMillis = originalMoment.elapsedMillis - 1)))
            // REMP-R10 requires refusal, not a particular application denial
            // enum for an unproven clock. No credential is issued in either case.
            val rollback = fixture.signIn("A")
            assertTrue(rollback is LocalAuthenticationResult.Denied || rollback == LocalAuthenticationResult.Unavailable)
            assertEquals(calls, schedule.verificationCount)
            assertTrue(fixture.throttleStates("A").all {
                it.failures == schedule.schedule.size && it.remainingDelayMillis >= retainedDelay && it.activeAttempt == null
            })
            fixture.closeService()
            // A new boot's monotonic zero is controlled DATA, not a wall-clock
            // translation or an observation of a real OS reboot.
            val newBoot = MonotonicMoment(authV1EphemeralId(), 0)
            fixture.reopenStoredService(schedule.restartedObservation(newBoot))
            assertSameRetainedState(before, fixture.snapshot("after-new-boot-open"))
            assertIs<LocalAuthenticationResult.Denied>(fixture.signIn("A"))
            assertEquals(calls, schedule.verificationCount)
            assertTrue(fixture.throttleStates("A").all {
                it.failures == schedule.schedule.size && it.remainingDelayMillis == retainedDelay &&
                    it.lastObservation == newBoot && it.activeAttempt == null
            })
            schedule.observeAt(newBoot.copy(elapsedMillis = retainedDelay - 1))
            assertIs<LocalAuthenticationResult.Denied>(fixture.signIn("A"))
            assertEquals(calls, schedule.verificationCount)
            schedule.observeAt(newBoot.copy(elapsedMillis = retainedDelay))
            val signedIn = assertIs<LocalAuthenticationResult.Authenticated>(fixture.signIn("A")).tokens
            assertIs<LocalSessionViewResult.Current>(fixture.endpoint.current(fixture.access(signedIn, "A")))
            assertTrue(fixture.throttleStates("A").all { it.failures == 0 && it.remainingDelayMillis == 0L && it.activeAttempt == null })
            assertTrue(fixture.snapshot("after-boot-local-relogin").events.map { it.eventId }.containsAll(before.events.map { it.eventId }))
            assertEquals(business, businessRows(path))
        }
    }

    private suspend fun prepareRetainedDelay(fixture: AuthV1Fixture, schedule: RoomAuthenticationTcpThrottleSlice) {
        schedule.control("begin", fixture)
        for ((index, _) in schedule.schedule.withIndex()) {
            val wrong = fixture.copyClientPassword("A")
            wrong[0] = if (wrong[0] == 'a') 'b' else 'a'
            try { assertRefused(fixture.signInWith("A", wrong)) } finally { wrong.fill('\u0000') }
            schedule.control("completed:$index", fixture)
            if (index != schedule.schedule.lastIndex) schedule.control("at:$index", fixture)
        }
    }

    private fun assertRefused(result: LocalAuthenticationResult) {
        assertEquals(LocalAuthenticationResult.Denied(LocalAuthenticationDenial.AUTHENTICATION_REFUSED), result)
    }

    private fun assertSameRetainedState(before: AuthV1Snapshot, after: AuthV1Snapshot) {
        assertEquals(before.accounts.keys, after.accounts.keys)
        for (who in before.accounts.keys) assertTrue(authV1SameObservedAccount(before.accounts.getValue(who),
            after.accounts.getValue(who)), "Retained account $who")
        assertEquals(before.admissions.keys, after.admissions.keys)
        for (who in before.admissions.keys) {
            val left = before.admissions.getValue(who)
            val right = after.admissions.getValue(who)
            assertEquals(left.scope, right.scope)
            assertEquals(left.deviceId, right.deviceId)
            assertEquals(left.revision, right.revision)
            assertEquals(left.admitted, right.admitted)
            assertEquals(left.publicKeyIdentifier, right.publicKeyIdentifier)
            assertEquals(left.publicFingerprint, right.publicFingerprint)
            assertEquals(left.capabilities, right.capabilities)
        }
        assertEquals(before.events.map { it.eventId }.toSet(), after.events.map { it.eventId }.toSet())
    }

    private suspend fun businessRows(path: Path): Map<String, List<List<String>>> {
        val database = buildControlledMasterRoomFixture(Room.databaseBuilder<MasterRoomDatabase>(
            path.toString(), MasterRoomConstructor::initialize))
        return try { localAuthorizationRows(database).filterKeys {
            it in setOf("operation_result", "aggregate_state", "unique_claim", "journal_envelope", "outbox_intent")
        } } finally { database.close() }
    }

    private suspend fun withFixture(name: String,
        block: suspend (AuthV1Fixture, RoomAuthenticationTcpThrottleSlice, Path) -> Unit) {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first {
            Files.isRegularFile(it.resolve("qa/pos/freezes/auth-v1/cases.json"))
        }
        val bytes = Files.readAllBytes(repository.resolve("qa/pos/freezes/auth-v1/cases.json"))
        check(authV1Sha256(bytes) == "e6fa22b836f7fd2c2face14d83544edd8554007faee6214c0ec36492a3c96100")
        val wiring = Files.readAllBytes(repository.resolve("qa/pos/wiring/auth-v1/wiring-v3.json"))
        check(authV1Sha256(wiring) == "9c6508917316935a42882019396c24a005434abd7b7f711e5ce4a624815236be")
        val root = Files.createDirectory(Files.createDirectories(Path.of("build", "auth-room-reopen"))
            .resolve(UUID.randomUUID().toString()),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        AuthV1Journal(root, UUID.randomUUID().toString().replace("-", ""),
            Json.parseToJsonElement(wiring.decodeToString()).jsonObject).use { journal ->
            val schedule = RoomAuthenticationTcpThrottleSlice(repository)
            val path = root.resolve("controlled-auth.db").toAbsolutePath()
            val fixture = AuthV1Fixture("reopen-$name", path, Json.parseToJsonElement(bytes.decodeToString()).jsonObject,
                journal, AuthV1FixtureSetup(initiallySignedIn = setOf("B", "M")),
                controlledObservation = schedule, controlledThrottlePolicy = schedule.policy,
                observeHasher = schedule::observeHasher)
            try { fixture.start(); block(fixture, schedule, path) }
            finally { fixture.dispose() }
        }
        println("Retained Room reopen diagnostics: ${root.toAbsolutePath()}")
    }
}
