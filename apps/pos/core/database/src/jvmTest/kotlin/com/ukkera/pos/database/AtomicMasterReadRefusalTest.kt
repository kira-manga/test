package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.types.Revision
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Bounded protected-read unit regressions with controlled transaction failures.
 * Purchasing documentary intake v2: DI2-007/018/019/023/025/027 and DOC-A/I/R/P.
 * Real SQLite is used, but the fixture's trusted identity/authority/history are
 * synthetic. These checks do not qualify native custody, production composition,
 * original-history setup, RPC, installed-device or owner acceptance.
 */
class AtomicMasterReadRefusalTest {
    /** RBR-001: revoked/disabled current identity wins before a typed selection is evaluated. */
    @Test fun currentIdentityDenialPrecedesTypedSelectionChecks() = runTest {
        for (kind in ReadRefusalKind.entries) {
            for (state in listOf("revoked", "disabled")) {
                withReadRefusalFixture { fixture ->
                    fixture.setAuthorization(state)
                    fixture.readHandler.refusal = kind
                    fixture.readHandler.stageBeforeFailure = true
                    val before = fixture.snapshot()
                    val store = fixture.store()
                    assertReadUnavailable(ReadFailure.AUTHORIZATION_DENIED, store.read(testPrincipal(), fixture.request()))
                    assertEquals(0, fixture.readHandler.prepareCalls)
                    assertEquals(0, fixture.readHandler.projectCalls)
                    assertEquals(before, fixture.snapshot())
                    assertTrue(store.readAdmissionCurrent(), "An ordinary current-identity denial must not poison physical admission")
                    fixture.setAuthorization("enabled")
                    fixture.readHandler.permitNextRead()
                    assertPermittedRead(store, fixture)
                }
            }
        }
    }

    /** RBR-001: action grants are rechecked before a selection refusal in projection. */
    @Test fun currentActionDenialPrecedesProjectionSelectionChecks() = runTest {
        for (kind in ReadRefusalKind.entries) {
            withReadRefusalFixture { fixture ->
                fixture.setAuthorization("no-grants")
                fixture.readHandler.refusal = kind
                fixture.readHandler.phase = ReadRefusalPhase.PROJECT
                val before = fixture.snapshot()
                val store = fixture.store()
                assertReadUnavailable(ReadFailure.AUTHORIZATION_DENIED, store.read(testPrincipal(), fixture.request()))
                assertEquals(1, fixture.readHandler.prepareCalls)
                assertEquals(0, fixture.readHandler.projectCalls)
                assertEquals(before, fixture.snapshot())
                assertTrue(store.readAdmissionCurrent())
                fixture.setAuthorization("enabled")
                fixture.readHandler.permitNextRead()
                assertPermittedRead(store, fixture)
            }
        }
    }

    /** RBR-002: exact transaction restoration and normal loan return qualify known denial. */
    @Test fun typedAuthorizationDenialRollsBackAndLeavesSameStoreUsable() = runTest {
        exerciseOrdinaryRefusal(ReadRefusalKind.AUTHORIZATION, ReadFailure.AUTHORIZATION_DENIED)
    }

    /** RBR-003: invalid selection is malformed input, while the same store stays usable. */
    @Test fun typedInvalidSelectionRollsBackAndLeavesSameStoreUsable() = runTest {
        exerciseOrdinaryRefusal(ReadRefusalKind.INVALID_SELECTION, ReadFailure.MALFORMED_REQUEST)
    }

    /** RBR-004: a failed rollback cannot be downgraded because the original exception is typed. */
    @Test fun failedRollbackReturnsStorageUncertainAndDeniesPhysicalAdmission() {
        for (kind in ReadRefusalKind.entries) {
            runReadFaultProbe(kind, ReadBoundaryFault.BEFORE_ROLLBACK)
            runReadFaultProbe(kind, ReadBoundaryFault.AFTER_ROLLBACK)
        }
    }

    /** Outer-loan failure is observed after real successful rollback, before a reply can escape. */
    @Test fun failedOuterLoanReturnAfterRollbackRemainsStorageUncertain() {
        for (kind in ReadRefusalKind.entries) runReadFaultProbe(kind, ReadBoundaryFault.OUTER_LOAN_RETURN)
    }

    /** RBR-005: an inherited transaction remains owned by its outer caller. */
    @Test fun enclosingTransactionCannotPublishAnOrdinarySelectionRefusal() = runTest {
        for (kind in ReadRefusalKind.entries) {
            withReadRefusalFixture { fixture ->
                fixture.readHandler.refusal = kind
                val before = fixture.snapshot()
                val store = fixture.store()
                val principal = testPrincipal()
                fixture.database.useWriterConnection { outer ->
                    requireDurableWriter(outer)
                    outer.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                        fixture.stageSentinel()
                        assertReadUnavailable(ReadFailure.STORAGE_UNCERTAIN, store.read(principal, fixture.request()))
                        assertEquals(0, fixture.readHandler.prepareCalls, "The owned-transaction guard must run before the handler")
                        assertEquals(0, fixture.readHandler.projectCalls)
                        assertFalse(store.readAdmissionCurrent())
                        assertTrue(outer.inTransaction(), "The inner read must not claim it rolled back its outer owner's work")
                        assertTrue(fixture.database.facts().aggregate(TEST_SCOPE.storageKey(),
                            READ_REFUSAL_SENTINEL.kind, READ_REFUSAL_SENTINEL.id.value) != null)
                        rollback(Unit)
                    }
                }
                assertEquals(before, fixture.snapshot(), "Only the actual outer owner's rollback restores its staged sentinel")
            }
        }
    }

    /** RBR-006: a real stored payload/hash mismatch follows genuine corruption handling. */
    @Test fun genericDurableCorruptionStaysUncertainAfterSuccessfulRollback() = runTest {
        withReadRefusalFixture { fixture ->
            fixture.seedCorruptAggregate()
            fixture.readHandler.corruptStoredRead = true
            fixture.readHandler.stageBeforeFailure = true
            val before = fixture.snapshot()
            val store = fixture.store()
            fixture.driver.observe()
            val reply = store.read(testPrincipal(), fixture.request())
            fixture.driver.stopObserving()
            assertReadUnavailable(ReadFailure.STORAGE_UNCERTAIN, reply)
            assertEquals(1, fixture.readHandler.prepareCalls)
            assertEquals(0, fixture.readHandler.projectCalls)
            assertEquals(0, fixture.driver.commits.get())
            assertEquals(1, fixture.driver.successfulRollbacks.get())
            assertEquals(before, fixture.snapshot())
            assertFalse(store.readAdmissionCurrent(), "Successful SQL rollback does not turn corrupt evidence into an ordinary denial")
        }
    }

    /** RBR-007: generic exception messages confer no known-refusal classification. */
    @Test fun genericFailureTextCannotManufactureAuthorizationOrMalformedRefusals() = runTest {
        for (message in listOf("AUTHORIZATION_DENIED", "MALFORMED_REQUEST", "original actor required", "invalid selection")) {
            for (phase in ReadRefusalPhase.entries) {
                withReadRefusalFixture { fixture ->
                    fixture.readHandler.genericFailure = IllegalArgumentException(message)
                    fixture.readHandler.phase = phase
                    fixture.readHandler.stageBeforeFailure = true
                    val before = fixture.snapshot()
                    val store = fixture.store()
                    fixture.driver.observe()
                    val reply = store.read(testPrincipal(), fixture.request())
                    fixture.driver.stopObserving()
                    assertReadUnavailable(ReadFailure.STORAGE_UNCERTAIN, reply)
                    assertEquals(0, fixture.driver.commits.get())
                    assertEquals(1, fixture.driver.successfulRollbacks.get())
                    assertEquals(before, fixture.snapshot())
                    assertFalse(store.readAdmissionCurrent())
                }
            }
        }
    }

    private suspend fun exerciseOrdinaryRefusal(kind: ReadRefusalKind, expected: ReadFailure) {
        for (phase in ReadRefusalPhase.entries) {
            withReadRefusalFixture { fixture ->
                fixture.readHandler.refusal = kind
                fixture.readHandler.phase = phase
                fixture.readHandler.stageBeforeFailure = true
                val before = fixture.snapshot()
                val principal = testPrincipal()
                val store = fixture.store()
                assertTrue(store.readAdmissionCurrent())
                fixture.driver.observe()
                val reply = store.read(principal, fixture.request())
                fixture.driver.stopObserving()
                assertReadUnavailable(expected, reply)
                assertEquals(1, fixture.readHandler.prepareCalls)
                assertEquals(if (phase == ReadRefusalPhase.PROJECT) 1 else 0, fixture.readHandler.projectCalls)
                assertEquals(0, fixture.driver.commits.get(), "Typed refusal must not commit even the staged synthetic sentinel")
                assertEquals(1, fixture.driver.successfulRollbacks.get())
                assertEquals(before, fixture.snapshot(), "Every original financial/protected row remains exact after rollback")
                assertTrue(store.readAdmissionCurrent(), "Known denial after normal rollback/loan return must not trip physical admission")

                fixture.readHandler.permitNextRead()
                assertPermittedRead(store, fixture)
                assertEquals(before, fixture.snapshot(), "The subsequent permitted read is read-only")
                val result = assertIs<MutationReply.Recorded>(store.execute(principal, fixture.counterRequest()))
                assertTrue(result.committed, "A subsequent valid synthetic command must use the same store")
                assertEquals("1", result.result.copyBytes().decodeToString())
                assertTrue(store.readAdmissionCurrent())
            }
        }
    }
}

private suspend fun withReadRefusalFixture(block: suspend (ReadRefusalFixture) -> Unit) {
    val fixture = ReadRefusalFixture()
    try { fixture.seed(); block(fixture) } finally { fixture.close() }
}

private fun assertReadUnavailable(expected: ReadFailure, actual: ReadReply) {
    assertEquals(expected, assertIs<ReadReply.Unavailable>(actual).reason)
}

private suspend fun assertPermittedRead(store: AtomicMasterStore, fixture: ReadRefusalFixture) {
    val reply = assertIs<ReadReply.Data>(store.read(testPrincipal(), fixture.request()))
    assertEquals(READ_REFUSAL_PAYLOAD, reply.payload)
    assertEquals(Revision(0), reply.branchRevision)
    assertEquals(TEST_TIME, reply.observedAt)
    assertTrue(store.readAdmissionCurrent())
}

/**
 * A failed recycle can strand the pool. Isolate that deliberate fault and verify a
 * post-assertion witness; never allow database close or test-process teardown to
 * masquerade as successful rollback/loan return.
 */
private fun runReadFaultProbe(kind: ReadRefusalKind, fault: ReadBoundaryFault) {
    val directory = Files.createTempDirectory("master-read-fault-")
    val file = directory.resolve("master.sqlite")
    val outputFile = directory.resolve("probe.log")
    val errorFile = directory.resolve("probe.err")
    val java = Path.of(System.getProperty("java.home")).resolve("bin").resolve("java")
    val classpath = System.getProperty("ukkera.test.runtime.classpath")
    assertTrue(!classpath.isNullOrBlank(), "Run through the configured database JVM test task")
    // Do not merge JVM launcher diagnostics (for example an inherited JAVA_TOOL_OPTIONS
    // "Picked up ..." line) into the probe's stdout protocol. The child remains under the
    // host admission environment; only the streams are separated for an exact result check.
    val process = ProcessBuilder(java.toString(), "-Xmx96m", "-cp", classpath,
        ReadBoundaryLoanReturnProbe::class.java.name, file.toString(), kind.name, fault.name)
        .redirectOutput(outputFile.toFile()).redirectError(errorFile.toFile()).start()
    try {
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "The disposable rollback/loan-return probe must terminate")
        assertTrue(Files.size(outputFile) <= 16_384, "Unexpected probe stdout size")
        assertTrue(Files.size(errorFile) <= 16_384, "Unexpected probe stderr size")
        val output = Files.readString(outputFile)
        val errors = Files.readString(errorFile)
        assertEquals(
            0,
            process.exitValue(),
            "Read-boundary probe failed: " + output.take(4_000) + errors.take(4_000),
        )
        assertEquals("READ_BOUNDARY_FAULT_PASSED:" + kind.name + ":" + fault.name + System.lineSeparator(), output)
    } finally {
        if (process.isAlive) process.destroyForcibly()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        directory.toFile().deleteRecursively()
    }
}

/** Disposable synthetic fault probe only; it does not reopen or bless native storage. */
object ReadBoundaryLoanReturnProbe {
    @JvmStatic fun main(args: Array<String>) {
        try {
            require(args.size == 3)
            val kind = ReadRefusalKind.valueOf(args[1])
            val fault = ReadBoundaryFault.valueOf(args[2])
            runBlocking {
                val fixture = ReadRefusalFixture(path = Path.of(args[0]))
                fixture.seed()
                fixture.readHandler.refusal = kind
                fixture.readHandler.stageBeforeFailure = true
                fixture.readHandler.fault = fault
                val store = fixture.store()
                assertTrue(store.readAdmissionCurrent())
                fixture.driver.observe()
                assertReadUnavailable(ReadFailure.STORAGE_UNCERTAIN, store.read(testPrincipal(), fixture.request()))
                assertEquals(1, fixture.readHandler.prepareCalls)
                assertEquals(0, fixture.readHandler.projectCalls)
                assertEquals(0, fixture.driver.commits.get())
                assertFalse(store.readAdmissionCurrent())
                if (fault == ReadBoundaryFault.OUTER_LOAN_RETURN) {
                    assertEquals(1, fixture.driver.successfulRollbacks.get())
                    assertEquals(0, fixture.driver.rollbackFailures.get())
                    assertEquals(1, fixture.driver.outerReturnFailures.get())
                    assertTrue(fixture.driver.actualOuterRecycleReached.get())
                    assertFalse(fixture.driver.activeAtFailure.get())
                } else {
                    assertEquals(1, fixture.driver.rollbackFailures.get())
                    assertEquals(0, fixture.driver.outerReturnFailures.get())
                    assertEquals(fault == ReadBoundaryFault.BEFORE_ROLLBACK, fixture.driver.activeAtFailure.get())
                    if (fault == ReadBoundaryFault.AFTER_ROLLBACK) assertEquals(1, fixture.driver.successfulRollbacks.get())
                }
                // Deliberately no cooperative close: all required observations precede process
                // teardown, which cleans only this probe's own synthetic disposable file.
            }
            println("READ_BOUNDARY_FAULT_PASSED:" + kind.name + ":" + fault.name)
            System.out.flush()
            Runtime.getRuntime().halt(0)
        } catch (failure: Throwable) {
            failure.printStackTrace(System.err)
            System.err.flush()
            Runtime.getRuntime().halt(1)
        }
    }
}
