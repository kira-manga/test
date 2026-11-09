package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchRecord.Read
import com.ukkera.pos.types.DomainId
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Executes the effective frozen v2 + errata launch-record cases against the real
 * production [LocalMasterLaunchRecord] with a controlled byte storage.
 *
 * Controlled policy only: no native file/fsync, Room, process restart, coordinator,
 * session, LAN or owner acceptance is qualified here.
 */
public class LocalMasterLaunchRecordV2QualificationTest {
    private val fixtures = FrozenLaunchRecordV2Fixtures

    // FLI2-READ-001 through FLI2-READ-027
    @Test
    public fun fli2Read001ObservedMissingIsAbsent() {
        val storage = ControlledLaunchStorage(null)
        val actual = LocalMasterLaunchRecord(storage).read()
        assertEquals(Read.Absent, actual, "FLI2-READ-001 observed missing must be Absent")
        assertEquals(1, storage.readCount, "FLI2-READ-001 reads")
        assertEquals(0, storage.writeCalls, "FLI2-READ-001 must not write")
        assertNull(storage.storedBytes(), "FLI2-READ-001 must not create a record")
    }

    @Test
    public fun fli2Read002ValidStop() = assertReadOnlyCase("FLI2-READ-002")

    @Test
    public fun fli2Read003ValidRunA() = assertReadOnlyCase("FLI2-READ-003")

    @Test
    public fun fli2Read004ReversedRunB() = assertReadOnlyCase("FLI2-READ-004")

    @Test
    public fun fli2Read005ReadExceptionIsUnresolvedNotAbsent() {
        val storage = ControlledLaunchStorage(null, readThrows = true)
        val actual = LocalMasterLaunchRecord(storage).read()
        assertEquals(Read.Unresolved, actual, "FLI2-READ-005 storage exception must be Unresolved")
        assertEquals(1, storage.readCount, "FLI2-READ-005 reads")
        assertEquals(0, storage.writeCalls, "FLI2-READ-005 must not write")
        assertNull(storage.storedBytes(), "FLI2-READ-005 must not create a record")
    }

    @Test
    public fun fli2Read006EmptyIsUnresolved() = assertReadOnlyCase("FLI2-READ-006")

    @Test
    public fun fli2Read007MalformedUtf8() = assertReadOnlyCase("FLI2-READ-007")

    @Test
    public fun fli2Read008BomNulAndValidNonAscii() = assertReadOnlyCase("FLI2-READ-008")

    @Test
    public fun fli2Read009CrLfAndCrOnly() = assertReadOnlyCase("FLI2-READ-009")

    @Test
    public fun fli2Read010MissingFinalLf() = assertReadOnlyCase("FLI2-READ-010")

    @Test
    public fun fli2Read011ExtraFinalLf() = assertReadOnlyCase("FLI2-READ-011")

    @Test
    public fun fli2Read012ExtraAndBlankFields() = assertReadOnlyCase("FLI2-READ-012")

    @Test
    public fun fli2Read013UnknownVersion() = assertReadOnlyCase("FLI2-READ-013")

    @Test
    public fun fli2Read014UnknownDisposition() = assertReadOnlyCase("FLI2-READ-014")

    @Test
    public fun fli2Read015CaseChanges() = assertReadOnlyCase("FLI2-READ-015")

    @Test
    public fun fli2Read016WhitespaceChanges() = assertReadOnlyCase("FLI2-READ-016")

    @Test
    public fun fli2Read017HeaderOnly() = assertReadOnlyCase("FLI2-READ-017")

    @Test
    public fun fli2Read018UppercaseCoordinateInEveryPosition() = assertReadOnlyCase("FLI2-READ-018")

    @Test
    public fun fli2Read019LeadingSpaceCoordinateInEveryPosition() = assertReadOnlyCase("FLI2-READ-019")

    @Test
    public fun fli2Read020TrailingSpaceCoordinateInEveryPosition() = assertReadOnlyCase("FLI2-READ-020")

    @Test
    public fun fli2Read021NonCanonicalUuidForms() = assertReadOnlyCase("FLI2-READ-021")

    @Test
    public fun fli2Read022MissingCoordinateInEveryPosition() = assertReadOnlyCase("FLI2-READ-022")

    @Test
    public fun fli2Read023DuplicateFieldLineInPlace() = assertReadOnlyCase("FLI2-READ-023")

    @Test
    public fun fli2Read024AppendedDuplicateFieldLine() = assertReadOnlyCase("FLI2-READ-024")

    @Test
    public fun fli2Read025SizeBoundaries() = assertReadOnlyCase("FLI2-READ-025")

    @Test
    public fun fli2Read026MixedNoRepairAndAbsent() {
        assertSingleRead("STOP-VALID", LaunchObservation.Stopped, "FLI2-READ-026 STOP")
        assertSingleRead("RUN-A-VALID", LaunchObservation.Running("V-A"), "FLI2-READ-026 RUN A")
        assertSingleRead("MISSING-FINAL-LF-STOP", LaunchObservation.Unresolved, "FLI2-READ-026 truncated STOP")
        assertSingleRead(null, LaunchObservation.Absent, "FLI2-READ-026 observed missing")
    }

    @Test
    public fun fli2Read027CurrentReadAfterStorageChanges() {
        val storage = ControlledLaunchStorage(fixtures.record("RUN-A-VALID").bytes)
        val record = LocalMasterLaunchRecord(storage)
        assertObservation(LaunchObservation.Running("V-A"), record.read(), "FLI2-READ-027 initial A")
        assertStored(storage, fixtures.record("RUN-A-VALID").bytes, "FLI2-READ-027 initial bytes changed")

        storage.setObservedBytes(fixtures.record("RUN-B-REVERSED-VALID").bytes)
        assertObservation(LaunchObservation.Running("V-B-REVERSED"), record.read(), "FLI2-READ-027 replacement B")
        assertStored(storage, fixtures.record("RUN-B-REVERSED-VALID").bytes, "FLI2-READ-027 B bytes changed")

        storage.setObservedBytes(fixtures.record("STOP-VALID").bytes)
        assertObservation(LaunchObservation.Stopped, record.read(), "FLI2-READ-027 replacement STOP")
        assertStored(storage, fixtures.record("STOP-VALID").bytes, "FLI2-READ-027 STOP bytes changed")

        assertEquals(3, storage.readCount, "FLI2-READ-027 reads the current storage each time")
        assertEquals(0, storage.writeCalls, "FLI2-READ-027 is read-only")
    }

    @Test
    public fun fli2eRead001ExactTwelveFieldDuplicateValues() = assertReadOnlyCase("FLI2E-READ-001")

    // FLI2-MATCH-001 through FLI2-MATCH-006
    @Test
    public fun fli2Match001ExactVectorComparisons() {
        val storage = ControlledLaunchStorage(fixtures.record("RUN-A-VALID").bytes)
        assertMatchingTable(storage, "RUN-A-VALID", "FLI2-MATCH-001")
    }

    @Test
    public fun fli2Match002EveryCoordinateDeltaIsFalse() {
        val storage = ControlledLaunchStorage(fixtures.record("RUN-A-VALID").bytes)
        val record = LocalMasterLaunchRecord(storage)
        for (index in 1..12) {
            val vectorId = "V-DELTA-${index.toString().padStart(2, '0')}"
            val running = assertIs<Read.Running>(record.read(), "FLI2-MATCH-002/$vectorId expected Running")
            assertTrue(running.matches(fixtures.vector("V-A").coordinates()), "FLI2-MATCH-002/$vectorId must still match V-A")
            assertFalse(running.matches(fixtures.vector(vectorId).coordinates()), "FLI2-MATCH-002/CHG-$index must not match")
        }
        assertEquals(0, storage.writeCalls, "FLI2-MATCH-002 is read-only")
        assertStored(storage, fixtures.record("RUN-A-VALID").bytes, "FLI2-MATCH-002 bytes changed")
    }

    @Test
    public fun fli2Match003ReversedVectorComparisons() {
        val storage = ControlledLaunchStorage(fixtures.record("RUN-B-REVERSED-VALID").bytes)
        assertMatchingTable(storage, "RUN-B-REVERSED-VALID", "FLI2-MATCH-003")
    }

    @Test
    public fun fli2Match004ChangingStorageObservations() {
        val storage = ControlledLaunchStorage(fixtures.record("RUN-A-VALID").bytes)
        val record = LocalMasterLaunchRecord(storage)
        val a = assertIs<Read.Running>(record.read(), "FLI2-MATCH-004 expected RUN A")
        assertTrue(a.matches(fixtures.vector("V-A").coordinates()), "FLI2-MATCH-004 A must match A")
        assertStored(storage, fixtures.record("RUN-A-VALID").bytes, "FLI2-MATCH-004 A bytes changed")

        storage.setObservedBytes(fixtures.record("RUN-B-REVERSED-VALID").bytes)
        val b = assertIs<Read.Running>(record.read(), "FLI2-MATCH-004 expected RUN B")
        assertTrue(b.matches(fixtures.vector("V-B-REVERSED").coordinates()), "FLI2-MATCH-004 B must match B")
        assertFalse(b.matches(fixtures.vector("V-A").coordinates()), "FLI2-MATCH-004 B must not match A")
        assertEquals(0, storage.writeCalls, "FLI2-MATCH-004 is read-only")
        assertStored(storage, fixtures.record("RUN-B-REVERSED-VALID").bytes, "FLI2-MATCH-004 bytes changed")
    }

    @Test
    public fun fli2Match005SwapPermutation() {
        val storage = ControlledLaunchStorage(fixtures.record("RUN-A-VALID").bytes)
        val record = LocalMasterLaunchRecord(storage)
        val swap = fixtures.vector("V-PERMUTE-SWAP-01-12").coordinates()
        val a = assertIs<Read.Running>(record.read(), "FLI2-MATCH-005 expected RUN A")
        assertFalse(a.matches(swap), "FLI2-MATCH-005 A must not match the swap vector")
        assertTrue(a.matches(fixtures.vector("V-A").coordinates()), "FLI2-MATCH-005 A must match A")
        assertStored(storage, fixtures.record("RUN-A-VALID").bytes, "FLI2-MATCH-005 A bytes changed")

        storage.setObservedBytes(fixtures.record("RUN-SWAP-01-12-VALID").bytes)
        val swapped = assertIs<Read.Running>(record.read(), "FLI2-MATCH-005 expected RUN swap")
        assertTrue(swapped.matches(swap), "FLI2-MATCH-005 swap record must match the swap vector")
        assertFalse(swapped.matches(fixtures.vector("V-A").coordinates()), "FLI2-MATCH-005 swap record must not match A")
        assertFalse(swapped.matches(fixtures.vector("V-B-REVERSED").coordinates()), "FLI2-MATCH-005 swap record must not match B")
        assertEquals(0, storage.writeCalls, "FLI2-MATCH-005 is read-only")
        assertStored(storage, fixtures.record("RUN-SWAP-01-12-VALID").bytes, "FLI2-MATCH-005 bytes changed")
    }

    @Test
    public fun fli2Match006ReturnToPreviousVectorUsesCurrentValue() {
        val storage = ControlledLaunchStorage(fixtures.record("RUN-A-VALID").bytes)
        val record = LocalMasterLaunchRecord(storage)
        assertTrue(assertIs<Read.Running>(record.read(), "FLI2-MATCH-006 first A").matches(fixtures.vector("V-A").coordinates()))
        assertStored(storage, fixtures.record("RUN-A-VALID").bytes, "FLI2-MATCH-006 first A bytes changed")

        storage.setObservedBytes(fixtures.record("RUN-B-REVERSED-VALID").bytes)
        assertFalse(assertIs<Read.Running>(record.read(), "FLI2-MATCH-006 B").matches(fixtures.vector("V-A").coordinates()))
        assertStored(storage, fixtures.record("RUN-B-REVERSED-VALID").bytes, "FLI2-MATCH-006 B bytes changed")

        storage.setObservedBytes(fixtures.record("RUN-A-VALID").bytes)
        assertTrue(assertIs<Read.Running>(record.read(), "FLI2-MATCH-006 second A").matches(fixtures.vector("V-A").coordinates()))
        assertStored(storage, fixtures.record("RUN-A-VALID").bytes, "FLI2-MATCH-006 second A bytes changed")
        assertEquals(3, storage.readCount, "FLI2-MATCH-006 reads each current value")
        assertEquals(0, storage.writeCalls, "FLI2-MATCH-006 is read-only")
    }

    // FLI2-RETAIN-001 through FLI2-RETAIN-023
    @Test
    public fun fli2Retain001AbsentToStop() = runRetentionScenario("RS-001")

    @Test
    public fun fli2Retain002RunAToStop() = runRetentionScenario("RS-002")

    @Test
    public fun fli2Retain003IdempotentStop() = runRetentionScenario("RS-003")

    @Test
    public fun fli2Retain004InvalidToStop() {
        runRetentionScenario("RS-004")
        runRetentionScenario("RS-010")
        runRetentionScenario("RS-029")
    }

    @Test
    public fun fli2Retain005AbsentToRunA() = runRetentionScenario("RS-005")

    @Test
    public fun fli2Retain006RunAToRunB() = runRetentionScenario("RS-006")

    @Test
    public fun fli2Retain007StopToRunA() = runRetentionScenario("RS-007")

    @Test
    public fun fli2Retain008InvalidToRunA() {
        runRetentionScenario("RS-008")
        runRetentionScenario("RS-030")
        runRetentionScenario("RS-031")
    }

    @Test
    public fun fli2Retain009IdempotentRunA() = runRetentionScenario("RS-009")

    @Test
    public fun fli2Retain010WriteThrowsBeforeBytes() {
        runRetentionScenario("RS-011")
        runRetentionScenario("RS-012")
    }

    @Test
    public fun fli2Retain011WriteThrowsAfterPartialPrefix() {
        for (index in 13..18) runRetentionScenario("RS-${index.toString().padStart(3, '0')}")
    }

    @Test
    public fun fli2Retain012WriteThrowsAfterFullBytes() {
        runRetentionScenario("RS-019")
        runRetentionScenario("RS-020")
    }

    @Test
    public fun fli2Retain013VerificationObservedMissing() = runRetentionScenario("RS-021")

    @Test
    public fun fli2Retain014VerificationReturnsOldRun() = runRetentionScenario("RS-022")

    @Test
    public fun fli2Retain015VerificationTruncated() = runRetentionScenario("RS-023")

    @Test
    public fun fli2Retain016VerificationReturnsOtherRun() = runRetentionScenario("RS-024")

    @Test
    public fun fli2Retain017VerificationThrows() = runRetentionScenario("RS-025")

    @Test
    public fun fli2Retain018VerificationWrongDispositionOversizeAndCase() {
        runRetentionScenario("RS-026")
        runRetentionScenario("RS-027")
        runRetentionScenario("RS-028")
    }

    @Test
    public fun fli2Retain019FailureMatrixHasNoRetryOrExtraWrite() {
        for (index in 11..28) runRetentionScenario("RS-${index.toString().padStart(3, '0')}")
    }

    @Test
    public fun fli2Retain020SuccessRequiresVerification() {
        runRetentionScenario("RS-001")
        runRetentionScenario("RS-005")
    }

    @Test
    public fun fli2Retain021ExactRetainedBytes() {
        runRetentionScenario("RS-001")
        runRetentionScenario("RS-005")
        runRetentionScenario("RS-006")
    }

    @Test
    public fun fli2Retain022ReadThenExplicitStopThenRead() {
        val invalid = fixtures.record("SIZE-1024")
        val storage = ControlledLaunchStorage(invalid.bytes)
        val record = LocalMasterLaunchRecord(storage)

        assertEquals(Read.Unresolved, record.read(), "FLI2-RETAIN-022 first read must be Unresolved")
        assertEquals(1, storage.readCount, "FLI2-RETAIN-022 first read count")
        assertEquals(0, storage.writeCalls, "FLI2-RETAIN-022 first read must not repair")
        assertEquals(0, storage.verificationReads, "FLI2-RETAIN-022 first read is not verification")
        assertStored(storage, invalid.bytes, "FLI2-RETAIN-022 first read changed bytes")

        record.retainStop()
        assertEquals(1, storage.writeCalls, "FLI2-RETAIN-022 write count")
        assertEquals(1, storage.verificationReads, "FLI2-RETAIN-022 verification count")
        assertEquals(listOf("W:STOP-VALID", "V:STOP-VALID"), storage.trace(), "FLI2-RETAIN-022 retention transition")
        assertStored(storage, fixtures.record("STOP-VALID").bytes, "FLI2-RETAIN-022 retained STOP")

        assertEquals(Read.Stopped, record.read(), "FLI2-RETAIN-022 third read must see STOP")
        assertEquals(3, storage.readCount, "FLI2-RETAIN-022 includes first, verification and final reads")
        assertEquals(1, storage.verificationReads, "FLI2-RETAIN-022 final read is not retention verification")
        assertEquals(1, storage.writeCalls, "FLI2-RETAIN-022 final read must not write")
    }

    @Test
    public fun fli2Retain023OldOrFullBytesNeverReportSuccess() {
        runRetentionScenario("RS-011")
        runRetentionScenario("RS-013")
        runRetentionScenario("RS-014")
        runRetentionScenario("RS-019")
        runRetentionScenario("RS-022")
    }

    // FLI2-CONC-001, FLI2-CONC-003 and FLI2E-CONC-001
    @Test
    public fun fli2Conc001BothSuccessSerializedTransitions() {
        val scenario = fixtures.concurrencyScenarios.getValue("CS-001")
        val orders = linkedMapOf<String, Int>()
        repeat(CONCURRENT_TRIALS) { trial ->
            val context = "FLI2-CONC-001 trial ${trial + 1}"
            val pause = FirstWritePause(context)
            val storage = ControlledLaunchStorage(afterWriteAttempt = pause::afterWriteAttempt)
            val record = LocalMasterLaunchRecord(storage)
            // Alternate the caller held at the first write. Before releasing it,
            // require observable contention by the second caller on the real
            // record monitor; storage itself does not block that caller.
            val stopFirst = trial % 2 == 0
            val results = if (stopFirst) {
                concurrentInvocations(
                    context, storage, pause,
                    { record.retainStop() },
                    { record.retainRunning(fixtures.vector("V-A").coordinates()) },
                )
            } else {
                concurrentInvocations(
                    context, storage, pause,
                    { record.retainRunning(fixtures.vector("V-A").coordinates()) },
                    { record.retainStop() },
                )
            }
            val stopResult = if (stopFirst) results[0] else results[1]
            val runResult = if (stopFirst) results[1] else results[0]
            assertEquals(LaunchInvocationResult.Success, stopResult, "$context retainStop")
            assertEquals(LaunchInvocationResult.Success, runResult, "$context retainRunning")
            assertEquals(2, storage.writeCalls, "$context writes")
            assertEquals(2, storage.verificationReads, "$context verifications")
            assertEquals(2, storage.readCount, "$context reads")
            val trace = storage.trace()
            assertTrue(trace in scenario.eventPatterns, "$context trace $trace is not an allowed non-interleaved pattern")
            val finalTarget = trace[3].substringAfter("V:")
            assertEquals(finalTarget, fixtures.label(storage.storedBytes()), "$context final bytes")
            assertEquals(scenario.successes, 2, "$context frozen success count")
            assertEquals(scenario.failures, 0, "$context frozen failure count")
            assertEquals(scenario.writeCalls, storage.writeCalls, "$context frozen write count")
            assertEquals(scenario.verificationReads, storage.verificationReads, "$context frozen verification count")
            assertTrue(finalTarget in scenario.finalStateOptions, "$context final state is not a frozen option")
            orders.merge(trace.joinToString("|"), 1, Int::plus)
            assertEquals(2, storage.writeAttempts().size, "$context write attempts")
        }
        println("FLI2-CONC-001 observed serialized order counts across $CONCURRENT_TRIALS trials: $orders")
    }

    @Test
    public fun fli2Conc003TwoRunningVectorsSerialized() {
        val scenario = fixtures.concurrencyScenarios.getValue("CS-003")
        val orders = linkedMapOf<String, Int>()
        repeat(CONCURRENT_TRIALS) { trial ->
            val context = "FLI2-CONC-003 trial ${trial + 1}"
            val pause = FirstWritePause(context)
            val storage = ControlledLaunchStorage(afterWriteAttempt = pause::afterWriteAttempt)
            val record = LocalMasterLaunchRecord(storage)
            val aFirst = trial % 2 == 0
            val results = if (aFirst) {
                concurrentInvocations(
                    context, storage, pause,
                    { record.retainRunning(fixtures.vector("V-A").coordinates()) },
                    { record.retainRunning(fixtures.vector("V-B-REVERSED").coordinates()) },
                )
            } else {
                concurrentInvocations(
                    context, storage, pause,
                    { record.retainRunning(fixtures.vector("V-B-REVERSED").coordinates()) },
                    { record.retainRunning(fixtures.vector("V-A").coordinates()) },
                )
            }
            val aResult = if (aFirst) results[0] else results[1]
            val bResult = if (aFirst) results[1] else results[0]
            assertEquals(LaunchInvocationResult.Success, aResult, "$context RUN A")
            assertEquals(LaunchInvocationResult.Success, bResult, "$context RUN B")
            assertEquals(2, storage.writeCalls, "$context writes")
            assertEquals(2, storage.verificationReads, "$context verifications")
            assertEquals(2, storage.readCount, "$context reads")
            val trace = storage.trace()
            assertTrue(trace in scenario.eventPatterns, "$context trace $trace is not an allowed non-interleaved pattern")
            val finalTarget = trace[3].substringAfter("V:")
            assertEquals(finalTarget, fixtures.label(storage.storedBytes()), "$context final bytes")
            assertEquals(scenario.successes, 2, "$context frozen success count")
            assertEquals(scenario.failures, 0, "$context frozen failure count")
            assertEquals(scenario.writeCalls, storage.writeCalls, "$context frozen write count")
            assertEquals(scenario.verificationReads, storage.verificationReads, "$context frozen verification count")
            assertTrue(finalTarget in scenario.finalStateOptions, "$context final state is not a frozen option")
            val winningVector = when (finalTarget) {
                "RUN-A-VALID" -> "V-A"
                "RUN-B-REVERSED-VALID" -> "V-B-REVERSED"
                else -> error("$context has an unknown final target $finalTarget")
            }
            val losingVector = if (winningVector == "V-A") "V-B-REVERSED" else "V-A"
            val final = assertIs<Read.Running>(record.read(), "$context final observation")
            assertTrue(final.matches(fixtures.vector(winningVector).coordinates()), "$context must match $winningVector")
            assertFalse(final.matches(fixtures.vector(losingVector).coordinates()), "$context must not match $losingVector")
            assertEquals(3, storage.readCount, "$context includes one independent final observation")
            assertEquals(2, storage.verificationReads, "$context final observation is not verification")
            assertEquals(2, storage.writeAttempts().size, "$context write attempts")
            orders.merge(trace.joinToString("|"), 1, Int::plus)
        }
        println("FLI2-CONC-003 observed serialized order counts across $CONCURRENT_TRIALS trials: $orders")
    }

    @Test
    public fun fli2eConc001FirstWriteOrdinalFaultReleasesQueuedInvocation() {
        val scenario = fixtures.correctedConcurrency
        val assignments = linkedMapOf<String, Int>()
        repeat(CONCURRENT_TRIALS) { trial ->
            val context = "FLI2E-CONC-001 trial ${trial + 1}"
            val pause = FirstWritePause(context)
            val storage = ControlledLaunchStorage(
                writeFault = LaunchWriteFault.BeforeBytes,
                afterWriteAttempt = pause::afterWriteAttempt,
            )
            val record = LocalMasterLaunchRecord(storage)
            val stopFirst = trial % 2 == 0
            val results = if (stopFirst) {
                concurrentInvocations(
                    context, storage, pause,
                    { record.retainStop() },
                    { record.retainRunning(fixtures.vector("V-A").coordinates()) },
                )
            } else {
                concurrentInvocations(
                    context, storage, pause,
                    { record.retainRunning(fixtures.vector("V-A").coordinates()) },
                    { record.retainStop() },
                )
            }
            val stopResult = if (stopFirst) results[0] else results[1]
            val runResult = if (stopFirst) results[1] else results[0]
            assertEquals(scenario.expectedSuccesses, 1, "$context frozen success count")
            assertEquals(scenario.expectedFailures, 1, "$context frozen failure count")
            assertEquals(1, listOf(stopResult, runResult).count { it is LaunchInvocationResult.Success }, "$context successes")
            assertEquals(1, listOf(stopResult, runResult).count { it is LaunchInvocationResult.Failure }, "$context failures")
            assertEquals(scenario.expectedWriteCalls, 2, "$context frozen write count")
            assertEquals(scenario.expectedVerificationReads, 1, "$context frozen verification count")
            assertEquals(2, storage.writeCalls, "$context writes")
            assertEquals(1, storage.verificationReads, "$context verifications")
            assertEquals(1, storage.readCount, "$context reads")

            val attempts = storage.writeAttempts()
            assertEquals(2, attempts.size, "$context write attempts")
            val firstTarget = fixtures.label(attempts[0].bytes)
            val assignment = when (firstTarget) {
                "STOP-VALID" -> scenario.assignments.single { it.variant == "ORD-01" }
                "RUN-A-VALID" -> scenario.assignments.single { it.variant == "ORD-02" }
                else -> error("$context first write targeted an unexpected record $firstTarget")
            }
            val expectedFirstCaller = if (assignment.variant == "ORD-01") "retainStop" else "retainRunning:V-A"
            val expectedSuccessful = if (assignment.variant == "ORD-01") "retainRunning:V-A" else "retainStop"
            assertEquals(expectedFirstCaller, assignment.firstWriteCaller, "$context frozen first-write caller")
            assertEquals(expectedFirstCaller, assignment.failed, "$context frozen failed caller")
            assertEquals(expectedSuccessful, assignment.successful, "$context frozen successful caller")
            assertEquals(1, attempts[0].ordinal, "$context first write ordinal")
            assertTrue(attempts[0].faulted, "$context first write must be the injected fault")
            assertFalse(attempts[1].faulted, "$context second write must be normal")
            assertNull(attempts[0].stateBefore, "$context first write must observe absence")
            assertNull(attempts[1].stateBefore, "$context failed first write must leave observed absence")
            assertEquals(assignment.trace, storage.trace(), "$context trace must match the actual first-write assignment")
            assertEquals(assignment.finalState, fixtures.label(storage.storedBytes()), "$context final state")

            when (assignment.variant) {
                "ORD-01" -> {
                    assertTrue(stopResult is LaunchInvocationResult.Failure, "$context retainStop must be the failed caller")
                    assertTrue(runResult is LaunchInvocationResult.Success, "$context retainRunning must be the successful caller")
                }
                "ORD-02" -> {
                    assertTrue(stopResult is LaunchInvocationResult.Success, "$context retainStop must be the successful caller")
                    assertTrue(runResult is LaunchInvocationResult.Failure, "$context retainRunning must be the failed caller")
                }
                else -> error("$context has an unknown assignment ${assignment.variant}")
            }
            assignments.merge(assignment.variant, 1, Int::plus)
        }
        println("FLI2E-CONC-001 observed first-write assignments across $CONCURRENT_TRIALS trials: $assignments")
    }

    // Frozen-corpus integrity checks. These are fixture setup/static checks, not additional runtime cases.
    @Test
    public fun frozenCorpusReconstructsAllEffectiveRecordsAndReferences() {
        assertEquals(114, fixtures.v2RecordCount, "inherited v2 record count")
        assertEquals(12, fixtures.errataRecordCount, "added errata record count")
        assertEquals(126, fixtures.records.size, "effective record count")
        assertEquals(15, fixtures.vectors.size, "coordinate vectors")
        assertEquals(21, fixtures.matchingExpectations.values.sumOf { it.size }, "matching comparisons")
        assertEquals(31, fixtures.retentionScenarios.size, "retention scenarios")
        assertEquals(60, fixtures.effectiveCaseIds.size, "effective case definitions")
        assertEquals(fixtures.effectiveCaseIds.size, fixtures.effectiveCaseIds.toSet().size, "unique effective cases")
        assertFalse("FLI2-CONC-002" in fixtures.effectiveCaseIds, "superseded CS-002 is not effective")
        assertEquals(1, fixtures.records.values.count { it.expected == LaunchObservation.Stopped }, "STOP records")
        assertEquals(3, fixtures.records.values.count { it.expected is LaunchObservation.Running }, "RUN records")
        assertEquals(122, fixtures.records.values.count { it.expected == LaunchObservation.Unresolved }, "Unresolved records")
        assertEquals(13, fixtures.retentionScenarios.values.count { it.expected.outcome == "SUCCESS" }, "successful retention scenarios")
        assertEquals(18, fixtures.retentionScenarios.values.count { it.expected.outcome == "NON_SUCCESS" }, "non-successful retention scenarios")

        val knownCases = fixtures.effectiveCaseIds.toSet()
        for (record in fixtures.records.values) {
            assertTrue(record.caseRefs.isNotEmpty(), "${record.id} has no case reference")
            assertTrue(record.caseRefs.all { it in knownCases }, "${record.id} has a dangling case reference")
        }
        for ((recordId, expectations) in fixtures.matchingExpectations) {
            fixtures.record(recordId)
            for (vectorId in expectations.keys) fixtures.vector(vectorId)
        }
        for (scenario in fixtures.retentionScenarios.values) {
            if (scenario.initial != "OBSERVED-MISSING") fixtures.record(scenario.initial)
            scenario.partialRecord?.let(fixtures::record)
            if (scenario.verify !in setOf("exact", "notCalled", "OBSERVED-MISSING", "THROW-VERIFY")) fixtures.record(scenario.verify)
            scenario.expected.writtenRecord?.let(fixtures::record)
            if (scenario.expected.finalState != "OBSERVED-MISSING") fixtures.record(scenario.expected.finalState)
        }
        for (scenario in fixtures.concurrencyScenarios.values) {
            scenario.operations.forEach { operation ->
                if (operation.startsWith("retainRunning:")) fixtures.vector(operation.substringAfter(':'))
            }
            scenario.finalStateOptions.forEach(fixtures::record)
        }
        scenarioAssignments().forEach { assignment ->
            fixtures.record(assignment.finalState)
        }

        val readCases = buildList {
            for (index in 2..4) add("FLI2-READ-${index.toString().padStart(3, '0')}")
            for (index in 6..25) add("FLI2-READ-${index.toString().padStart(3, '0')}")
            add("FLI2E-READ-001")
        }
        val readCovered = buildSet {
            readCases.forEach { addAll(fixtures.recordIdsForCase(it)) }
            addAll(listOf("STOP-VALID", "RUN-A-VALID", "RUN-B-REVERSED-VALID", "RUN-SWAP-01-12-VALID"))
        }
        val partialRecords = setOf(
            "PARTIAL-STOP-HEADER",
            "PARTIAL-STOP-STO",
            "PARTIAL-RUN-HEADER",
            "PARTIAL-RUN-DISPOSITION",
            "PARTIAL-RUN-FIRST-PART",
            "PARTIAL-RUN-FIRST-FIELD",
        )
        assertEquals(partialRecords, fixtures.v2RecordIds - readCovered, "only the six partial write records are unread")
        assertEquals(fixtures.errataRecordIds, fixtures.recordIdsForCase("FLI2E-READ-001").toSet(), "all added records are read")

        val retentionCovered = buildSet {
            for (scenario in fixtures.retentionScenarios.values) {
                if (scenario.initial != "OBSERVED-MISSING") add(scenario.initial)
                scenario.partialRecord?.let(::add)
                if (scenario.verify !in setOf("exact", "notCalled", "OBSERVED-MISSING", "THROW-VERIFY")) add(scenario.verify)
                scenario.expected.writtenRecord?.let(::add)
                if (scenario.expected.finalState != "OBSERVED-MISSING") add(scenario.expected.finalState)
            }
        }
        assertTrue(retentionCovered.containsAll(partialRecords), "partial write records must be exercised by retention")
    }

    @Test
    public fun frozenDuplicateValuePlanCoversEveryDestinationPosition() {
        assertEquals(12, fixtures.duplicateValuePlan.size, "duplicate-value records")
        assertEquals((1..12).toSet(), fixtures.duplicateValuePlan.map { it.destinationIndex }.toSet(), "destination positions")
        val base = fixtures.vector("V-A").values
        for (plan in fixtures.duplicateValuePlan) {
            val record = fixtures.record(plan.recordId)
            assertEquals(plan.bytes, record.bytes.size, "${plan.recordId} length")
            assertEquals(plan.sha256, sha256(record.bytes), "${plan.recordId} hash")
            assertEquals(LaunchObservation.Unresolved, record.expected, "${plan.recordId} must be Unresolved")
            assertEquals((plan.destinationIndex % 12) + 1, plan.donorIndex, "${plan.recordId} frozen donor rule")
            assertEquals(listOf(plan.destinationIndex), plan.changedPositions, "${plan.recordId} changed positions")
            assertEquals(
                listOf(minOf(plan.destinationIndex, plan.donorIndex), maxOf(plan.destinationIndex, plan.donorIndex)),
                plan.duplicatePositions.sorted(),
                "${plan.recordId} duplicate positions",
            )
            assertEquals(base[plan.donorIndex - 1], plan.duplicatedValue, "${plan.recordId} duplicated value")
            assertEquals(base[plan.destinationIndex - 1], plan.removedValue, "${plan.recordId} removed value")

            val lines = record.bytes.decodeToString().split('\n')
            assertEquals(15, lines.size, "${plan.recordId} must have header, RUN, twelve fields and final empty line")
            assertEquals("RUN", lines[1], "${plan.recordId} disposition")
            val fields = lines.subList(2, 14)
            assertEquals(12, fields.size, "${plan.recordId} field count")
            fields.forEach { field -> DomainId(field) }
            assertEquals(11, fields.distinct().size, "${plan.recordId} distinct value count")
            assertEquals(plan.duplicatedValue, fields[plan.destinationIndex - 1], "${plan.recordId} destination value")
            assertEquals(plan.duplicatedValue, fields[plan.donorIndex - 1], "${plan.recordId} donor value")
        }
    }

    private fun scenarioAssignments(): List<FrozenCorrectedAssignment> = fixtures.correctedConcurrency.assignments

    private fun assertReadOnlyCase(caseId: String) {
        val recordIds = fixtures.recordIdsForCase(caseId)
        assertTrue(recordIds.isNotEmpty(), "$caseId has no frozen records")
        for (recordId in recordIds) {
            val frozen = fixtures.record(recordId)
            val storage = ControlledLaunchStorage(frozen.bytes)
            val actual = LocalMasterLaunchRecord(storage).read()
            assertObservation(frozen.expected, actual, "$caseId/$recordId")
            assertEquals(1, storage.readCount, "$caseId/$recordId reads")
            assertEquals(0, storage.writeCalls, "$caseId/$recordId must not write")
            assertStored(storage, frozen.bytes, "$caseId/$recordId stored bytes changed")
        }
    }

    private fun assertSingleRead(recordId: String?, expected: LaunchObservation, context: String) {
        val bytes = recordId?.let { fixtures.record(it).bytes }
        val storage = ControlledLaunchStorage(bytes)
        val actual = LocalMasterLaunchRecord(storage).read()
        assertObservation(expected, actual, context)
        assertEquals(1, storage.readCount, "$context reads")
        assertEquals(0, storage.writeCalls, "$context must not write")
        assertStored(storage, bytes, "$context stored bytes changed")
    }

    private fun assertObservation(expected: LaunchObservation, actual: Read, context: String) {
        when (expected) {
            LaunchObservation.Absent -> assertEquals(Read.Absent, actual, context)
            LaunchObservation.Stopped -> assertEquals(Read.Stopped, actual, context)
            LaunchObservation.Unresolved -> assertEquals(Read.Unresolved, actual, context)
            is LaunchObservation.Running -> {
                val running = assertIs<Read.Running>(actual, "$context expected Run(${expected.vectorId})")
                assertTrue(
                    running.matches(fixtures.vector(expected.vectorId).coordinates()),
                    "$context did not exactly match ${expected.vectorId}",
                )
                val storedRecordId = fixtures.label(fixtures.vector(expected.vectorId).runBytes)
                for ((vectorId, expectedMatch) in fixtures.matchingExpectations.getValue(storedRecordId)) {
                    assertEquals(
                        expectedMatch, running.matches(fixtures.vector(vectorId).coordinates()),
                        "$context frozen comparison against $vectorId",
                    )
                }
            }
        }
    }

    private fun assertMatchingTable(storage: ControlledLaunchStorage, storedRecordId: String, context: String) {
        val record = LocalMasterLaunchRecord(storage)
        val expectations = fixtures.matchingExpectations[storedRecordId]
            ?: error("$context has no frozen matching expectations for $storedRecordId")
        for ((vectorId, expected) in expectations) {
            val running = assertIs<Read.Running>(record.read(), "$context/$vectorId expected Running")
            assertEquals(expected, running.matches(fixtures.vector(vectorId).coordinates()), "$context/$vectorId")
        }
        assertEquals(0, storage.writeCalls, "$context is read-only")
        assertStored(storage, fixtures.record(storedRecordId).bytes, "$context stored bytes changed")
    }

    private fun assertStored(storage: ControlledLaunchStorage, expected: ByteArray?, context: String) {
        val actual = storage.storedBytes()
        if (expected == null) {
            assertNull(actual, context)
        } else {
            assertNotNull(actual, context)
            assertContentEquals(expected, actual, context)
        }
    }

    private fun assertFinalState(finalState: String, record: LocalMasterLaunchRecord, context: String) {
        if (finalState == "OBSERVED-MISSING") {
            assertEquals(Read.Absent, record.read(), context)
        } else {
            val frozen = fixtures.record(finalState)
            assertObservation(frozen.expected, record.read(), context)
        }
    }

    private fun intendedRecord(operation: String): FrozenLaunchRecord = when {
        operation == "retainStop" -> fixtures.record("STOP-VALID")
        operation.startsWith("retainRunning:") -> {
            val runBytes = fixtures.vector(operation.substringAfter("retainRunning:")).runBytes
            fixtures.records.values.singleOrNull { it.bytes.contentEquals(runBytes) }
                ?: error("No single frozen RUN record for $operation")
        }
        else -> error("Unknown retention operation $operation")
    }

    private fun performRetention(record: LocalMasterLaunchRecord, operation: String) {
        when {
            operation == "retainStop" -> record.retainStop()
            operation.startsWith("retainRunning:") ->
                record.retainRunning(fixtures.vector(operation.substringAfter("retainRunning:")).coordinates())
            else -> error("Unknown retention operation $operation")
        }
    }

    private fun runRetentionScenario(id: String) {
        val scenario = fixtures.retention(id)
        val initial = if (scenario.initial == "OBSERVED-MISSING") null else fixtures.record(scenario.initial).bytes
        val fault = when (scenario.writePhase) {
            null -> null
            "beforeBytes" -> LaunchWriteFault.BeforeBytes
            "afterPartial" -> LaunchWriteFault.AfterPartial(
                fixtures.record(requireNotNull(scenario.partialRecord) { "$id has no partial record" }).bytes,
            )
            "afterFullBytes" -> LaunchWriteFault.AfterFullBytes
            else -> error("$id has an unknown write phase ${scenario.writePhase}")
        }
        val injection = when (scenario.verify) {
            "exact", "notCalled" -> LaunchVerifyInjection.Exact
            "OBSERVED-MISSING" -> LaunchVerifyInjection.ObservedMissing
            "THROW-VERIFY" -> LaunchVerifyInjection.Throws
            else -> LaunchVerifyInjection.Bytes(fixtures.record(scenario.verify).bytes)
        }
        val storage = ControlledLaunchStorage(
            initial = initial,
            writeFault = fault,
            verifyInjection = injection,
        )
        val record = LocalMasterLaunchRecord(storage)
        val intended = intendedRecord(scenario.operation)
        val failure = runCatching { performRetention(record, scenario.operation) }.exceptionOrNull()
        val expected = scenario.expected

        assertEquals(expected.writeCalls, storage.writeCalls, "$id write calls")
        assertEquals(expected.verificationReads, storage.verificationReads, "$id verification reads")
        assertEquals(1, storage.writeAttempts().size, "$id single write attempt")
        val attempt = storage.writeAttempts().single()
        assertEquals(1, attempt.ordinal, "$id write ordinal")
        assertEquals(fault != null, attempt.faulted, "$id write fault state")
        assertContentEquals(intended.bytes, attempt.bytes, "$id attempted bytes")

        if (expected.writtenRecord != null) {
            assertEquals(expected.writtenRecord, intended.id, "$id intended disposition")
        }
        if (expected.finalState == "OBSERVED-MISSING") {
            assertStored(storage, null, "$id final state")
        } else {
            assertStored(storage, fixtures.record(expected.finalState).bytes, "$id final bytes")
        }

        val trace = storage.trace()
        if (expected.outcome == "SUCCESS") {
            assertNull(failure, "$id expected a normal return")
            assertEquals(listOf("W:${intended.id}", "V:${intended.id}"), trace, "$id success must follow exact verification")
        } else {
            assertNotNull(failure, "$id expected non-success")
            when (expected.verificationReads) {
                0 -> {
                    assertEquals(1, trace.size, "$id write failure must not verify")
                    assertEquals("W:throw(${fixtures.failedWriteDisposition(intended.id)})", trace.single(), "$id write failure trace")
                }
                1 -> {
                    assertEquals(2, trace.size, "$id verification failure trace")
                    assertEquals("W:${intended.id}", trace[0], "$id write trace")
                    assertTrue(trace[1].startsWith("V:"), "$id verification trace")
                    assertFalse(trace[1] == "V:${intended.id}", "$id verification must not match the intended bytes")
                }
                else -> error("$id has an unexpected verification read count")
            }
        }

        val writesBeforeObservation = storage.writeCalls
        val verificationsBeforeObservation = storage.verificationReads
        assertFinalState(expected.finalState, record, "$id post-operation observation")
        assertEquals(writesBeforeObservation, storage.writeCalls, "$id post-operation read must not write")
        assertEquals(verificationsBeforeObservation, storage.verificationReads, "$id post-operation read is not verification")
    }

    private fun concurrentInvocations(
        context: String,
        storage: ControlledLaunchStorage,
        pause: FirstWritePause,
        first: () -> Unit,
        second: () -> Unit,
    ): List<LaunchInvocationResult> {
        val executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "launch-record-qualification").apply { isDaemon = true }
        }
        val firstThread = AtomicReference<Thread>()
        val secondThread = AtomicReference<Thread>()
        val secondInvoking = CountDownLatch(1)
        try {
            fun submit(
                invocation: () -> Unit,
                worker: AtomicReference<Thread>,
                invoking: CountDownLatch? = null,
            ) =
                executor.submit<LaunchInvocationResult> {
                    worker.set(Thread.currentThread())
                    invoking?.countDown()
                    try {
                        invocation()
                        LaunchInvocationResult.Success
                    } catch (failure: Throwable) {
                        LaunchInvocationResult.Failure(failure)
                    }
                }

            val firstFuture = submit(first, firstThread)
            assertTrue(
                pause.reached.await(CONCURRENT_GUARD_SECONDS, TimeUnit.SECONDS),
                "$context first caller did not reach write ordinal 1",
            )
            val secondFuture = submit(second, secondThread, secondInvoking)
            assertTrue(
                secondInvoking.await(CONCURRENT_GUARD_SECONDS, TimeUnit.SECONDS),
                "$context second caller did not start",
            )

            // A start signal alone cannot prove overlapping invocations. Require
            // a positive JVM observation that caller 2 is blocked by caller 1
            // inside the real record. The storage lock is free during this pause.
            val threads = ManagementFactory.getThreadMXBean()
            val ownerId = firstThread.get().threadId()
            val waiterId = secondThread.get().threadId()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONCURRENT_GUARD_SECONDS)
            while (true) {
                assertEquals(1, storage.writeCalls, "$context second caller entered storage before first transition finished")
                assertEquals(0, storage.readCount, "$context read occurred while first write was held")
                assertFalse(firstFuture.isDone, "$context first caller escaped the write pause")
                assertFalse(secondFuture.isDone, "$context second caller completed without waiting")
                val info = threads.getThreadInfo(waiterId, 64)
                if (info != null &&
                    info.lockOwnerId == ownerId &&
                    info.threadState in setOf(Thread.State.BLOCKED, Thread.State.WAITING) &&
                    info.stackTrace.any { it.className == LocalMasterLaunchRecord::class.java.name }
                ) {
                    break
                }
                if (System.nanoTime() >= deadline) {
                    throw AssertionError("$context no production-lock contention was observed before the deadlock guard")
                }
                Thread.yield()
            }
            pause.release.countDown()
            return listOf(firstFuture, secondFuture).map { future ->
                try {
                    future.get(CONCURRENT_GUARD_SECONDS, TimeUnit.SECONDS)
                } catch (failure: Throwable) {
                    throw AssertionError("$context concurrent invocation did not finish", failure)
                }
            }
        } finally {
            pause.release.countDown()
            executor.shutdownNow()
            if (!executor.awaitTermination(CONCURRENT_GUARD_SECONDS, TimeUnit.SECONDS)) {
                throw AssertionError("$context concurrent workers did not terminate")
            }
        }
    }

    private class FirstWritePause(private val context: String) {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun afterWriteAttempt(ordinal: Int) {
            if (ordinal != 1) return
            reached.countDown()
            check(release.await(CONCURRENT_GUARD_SECONDS, TimeUnit.SECONDS)) {
                "$context first-write pause was not released"
            }
        }
    }

    private sealed interface LaunchInvocationResult {
        data object Success : LaunchInvocationResult
        data class Failure(val error: Throwable) : LaunchInvocationResult
    }

    private companion object {
        const val CONCURRENT_TRIALS = 2
        const val CONCURRENT_GUARD_SECONDS = 10L
    }
}
