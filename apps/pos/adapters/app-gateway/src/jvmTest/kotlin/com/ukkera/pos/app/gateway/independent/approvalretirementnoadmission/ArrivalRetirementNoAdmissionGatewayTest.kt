@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.approvalretirementnoadmission

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.database.LocalActionApprovalRetirementAttempt as MasterRetirementAttempt
import com.ukkera.pos.inventory.store.LocalUnpricedArrivalCommandsV2
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * ARRNA v2 partial client slices. Exact negative origins are controlled in-process
 * replies; the gateway, full binding validation, whole-record CAS and encrypted
 * journal/reopen are real. No native admission, authority or business is invented.
 */
class ArrivalRetirementNoAdmissionGatewayTest {
    @Test
    fun arrna01FreshI1IsDurableBeforeHandoffAndRetainsSameApprovalI0() = runTest {
        withArrivalRetirementNoAdmissionFixture { fixture ->
            val hold = ArrivalRetirementHold()
            fixture.endpoint.onRetire = hold::receive
            val call = async { fixture.gateway.retireActionApproval(fixture.inputs.operationId) }
            val handoff = hold.entered.await()
            val saved = fixture.latest()
            assertEquals(saved, handoff.durable)
            assertEquals(saved, fixture.journal.savedInvocations.last())
            assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED, saved.actionApproval?.localPhase)
            val i0 = checkNotNull(fixture.i0.actionApproval?.retirementAttempt)
            val i1 = checkNotNull(saved.actionApproval?.retirementAttempt)
            assertNotEquals(i0.attemptId, i1.attemptId)
            assertEquals(i0.sequence + 1, i1.sequence)
            assertEquals(listOf(i0), saved.actionApproval?.completedRetirementAttempts)
            assertEquals(fixture.inputs.approvalId, handoff.attempt.approvalId)
            assertEquals(fixture.inputs.operationId, handoff.attempt.operationId)
            assertEquals(fixture.inputs.request.command, handoff.attempt.command)
            assertEquals(sha256(fixture.inputs.request.payload.copyBytes()), handoff.attempt.originalPayloadDigest)
            assertEquals(fixture.inputs.confirmationDigest, handoff.attempt.confirmationDigest)
            assertEquals(fixture.inputs.request.operationId, handoff.request.operationId)
            assertEquals(fixture.inputs.request.command, handoff.request.command)
            assertEquals(fixture.inputs.request.payload, handoff.request.payload)
            assertEquals(fixture.inputs.request.actionApprovalId, handoff.request.actionApprovalId)
            assertEquals(1, fixture.endpoint.retirements.size - fixture.baselineRetirements)
            fixture.assertOriginal(saved)
            // A held duplicate tap lookup is NOT bound here: the current caller holds
            // the requests mutex. This distinct post-return uncertain variant is lookup.
            hold.reply.complete(LocalActionApprovalRetireResult.Unavailable)
            assertIs<LocalActionApprovalResult.Unavailable>(call.await())
            val lookups = fixture.endpoint.approvalLookups.size
            assertIs<LocalActionApprovalResult.Available>(fixture.gateway.retireActionApproval(fixture.inputs.operationId))
            assertEquals(lookups + 1, fixture.endpoint.approvalLookups.size)
            assertEquals(1, fixture.endpoint.retirements.size - fixture.baselineRetirements)
            assertEquals(saved, fixture.latest())
            fixture.assertNoBusinessOrIssuance()
        }
    }

    @Test
    fun arrna02ExactLiveNegativeCompletesOnlyI1AndSurvivesFreshReopen() = runTest {
        for (reason in LocalActionApprovalNoAdmissionReason.entries) {
            withArrivalRetirementNoAdmissionFixture { fixture ->
                fixture.endpoint.onRetire = { LocalActionApprovalRetireResult.NotAdmitted(it.attempt, reason) }
                val result = assertIs<LocalActionApprovalResult.Available>(fixture.gateway.retireActionApproval(fixture.inputs.operationId))
                val completed = fixture.latest()
                val ceremony = checkNotNull(completed.actionApproval)
                assertEquals(LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED, ceremony.localPhase)
                assertEquals(reason.name, ceremony.retirementAttempt?.noAdmissionReason)
                assertEquals(listOf(checkNotNull(fixture.i0.actionApproval?.retirementAttempt)), ceremony.completedRetirementAttempts)
                assertFalse(result.operation.submitAllowed)
                assertNull(ceremony.retirement)
                assertNull(ceremony.receipt?.consumedByOperationId)
                fixture.assertOriginal(completed)
                fixture.reopen()
                assertEquals(completed, fixture.latest())
                fixture.endpoint.view = fixture.inputs.sessionView(recovered = true)
                val recovered = assertIs<LocalPendingResult.Available>(fixture.gateway.pending()).operations.single()
                assertEquals(LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED, recovered.actionApproval?.phase)
                assertEquals(ceremony.completedRetirementAttempts.map { it.ui() }, recovered.actionApproval?.completedRetirementAttempts)
                assertEquals(ceremony.retirementAttempt?.ui(), recovered.actionApproval?.retirementAttempt)
                assertFalse(recovered.submitAllowed)
                assertFalse(assertIs<LocalOperationResult.Pending>(fixture.gateway.submit(fixture.inputs.operationId)).operation.submitAllowed)
                assertEquals(completed, fixture.latest())
                assertEquals(1, fixture.endpoint.retirements.size - fixture.baselineRetirements)
                fixture.assertOriginal(fixture.latest())
                fixture.assertNoBusinessOrIssuance()
            }
        }
    }

    @Test
    fun arrna03EveryGenericRetirementReplyLeavesI1LookupOnlyAfterReopen() = runTest {
        val rows = listOf(LocalActionApprovalRetireResult.Denied to LocalServiceProblem.DENIED,
            LocalActionApprovalRetireResult.Busy to LocalServiceProblem.RETRY_LATER,
            LocalActionApprovalRetireResult.Unavailable to LocalServiceProblem.UNAVAILABLE,
            LocalActionApprovalRetireResult.StorageUncertain to LocalServiceProblem.STORAGE_UNCERTAIN)
        for ((reply, problem) in rows) {
            withArrivalRetirementNoAdmissionFixture { fixture ->
                fixture.endpoint.onRetire = { reply }
                val result = assertIs<LocalActionApprovalResult.Unavailable>(fixture.gateway.retireActionApproval(fixture.inputs.operationId))
                assertEquals(problem, result.problem)
                val unresolved = assertUnresolved(fixture)
                fixture.reopen()
                assertEquals(unresolved, fixture.latest())
                val lookups = fixture.endpoint.approvalLookups.size
                val recovered = assertIs<LocalActionApprovalResult.Available>(fixture.gateway.retireActionApproval(fixture.inputs.operationId)).operation
                assertEquals(lookups + 1, fixture.endpoint.approvalLookups.size)
                assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED, recovered.actionApproval?.phase)
                assertFalse(recovered.submitAllowed)
                assertEquals(unresolved, fixture.latest())
                assertEquals(1, fixture.endpoint.retirements.size - fixture.baselineRetirements)
                fixture.assertNoBusinessOrIssuance()
            }
        }
    }

    @Test
    fun arrna03TransportErrorAndCancelledWaitCannotReconstructNoAdmission() = runTest {
        withArrivalRetirementNoAdmissionFixture { fixture ->
            fixture.endpoint.onRetire = { throw IllegalStateException("Controlled lost or invalid transport response") }
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN,
                assertIs<LocalActionApprovalResult.Unavailable>(fixture.gateway.retireActionApproval(fixture.inputs.operationId)).problem)
            val unresolved = assertUnresolved(fixture)
            fixture.reopen()
            assertEquals(unresolved, fixture.latest())
            val lookups = fixture.endpoint.approvalLookups.size
            assertIs<LocalActionApprovalResult.Available>(fixture.gateway.retireActionApproval(fixture.inputs.operationId))
            assertEquals(lookups + 1, fixture.endpoint.approvalLookups.size)
            assertEquals(unresolved, fixture.latest())
            assertEquals(1, fixture.endpoint.retirements.size - fixture.baselineRetirements)
            fixture.assertNoBusinessOrIssuance()
        }
        withArrivalRetirementNoAdmissionFixture { fixture ->
            val hold = ArrivalRetirementHold()
            fixture.endpoint.onRetire = hold::receive
            val call = async { fixture.gateway.retireActionApproval(fixture.inputs.operationId) }
            hold.entered.await()
            call.cancel()
            call.join()
            assertTrue(call.isCancelled)
            val unresolved = assertUnresolved(fixture)
            // No controlled negative was delivered. Caller cancellation is not an
            // endpoint admission result or a claim that accepted work cannot execute.
            hold.reply.complete(LocalActionApprovalRetireResult.Unavailable)
            fixture.reopen()
            assertEquals(unresolved, fixture.latest())
            val lookups = fixture.endpoint.approvalLookups.size
            assertIs<LocalActionApprovalResult.Available>(fixture.gateway.retireActionApproval(fixture.inputs.operationId))
            assertEquals(lookups + 1, fixture.endpoint.approvalLookups.size)
            assertEquals(unresolved, fixture.latest())
            assertEquals(1, fixture.endpoint.retirements.size - fixture.baselineRetirements)
            fixture.assertNoBusinessOrIssuance()
        }
    }

    @Test
    fun arrna04EqualCopiesAndOtherOriginBindingsCannotCompleteLiveI1() = runTest {
        val changes: List<(MasterRetirementAttempt) -> MasterRetirementAttempt> = listOf(
            { it.copy() },
            { it.copy(attemptId = ArrivalRetirementFrozenV2.id("I-other")) },
            { it.copy(sequence = it.sequence + 1) },
            { it.copy(approvalId = ArrivalRetirementFrozenV2.id("approval-B")) },
            { it.copy(operationId = ArrivalRetirementFrozenV2.operation("business-Record-B")) },
            { it.copy(command = CommandKey("qualification.arrna.other", 1)) },
            { it.copy(originalPayloadDigest = PayloadDigest("1".repeat(64))) },
            { it.copy(confirmationDigest = PayloadDigest("2".repeat(64))) })
        for (change in changes) {
            withArrivalRetirementNoAdmissionFixture { fixture ->
                fixture.endpoint.onRetire = {
                    val copy = change(it.attempt)
                    assertNotSame(it.attempt, copy)
                    LocalActionApprovalRetireResult.NotAdmitted(copy, LocalActionApprovalNoAdmissionReason.QUEUE_FULL)
                }
                assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN,
                    assertIs<LocalActionApprovalResult.Unavailable>(fixture.gateway.retireActionApproval(fixture.inputs.operationId)).problem)
                val unresolved = assertUnresolved(fixture)
                fixture.reopen()
                assertEquals(unresolved, fixture.latest())
                val lookups = fixture.endpoint.approvalLookups.size
                assertFalse(assertIs<LocalActionApprovalResult.Available>(fixture.gateway.retireActionApproval(fixture.inputs.operationId)).operation.submitAllowed)
                assertEquals(lookups + 1, fixture.endpoint.approvalLookups.size)
                assertEquals(1, fixture.endpoint.retirements.size - fixture.baselineRetirements)
                assertEquals(unresolved, fixture.latest())
                fixture.assertNoBusinessOrIssuance()
            }
        }
    }

    @Test
    fun arrna04ChangedRecordPayloadOrPreviewFailsDurableBindingWithoutRewritingA() = runTest {
        withArrivalRetirementNoAdmissionFixture { fixture ->
            fixture.endpoint.onRetire = { LocalActionApprovalRetireResult.Unavailable }
            assertIs<LocalActionApprovalResult.Unavailable>(fixture.gateway.retireActionApproval(fixture.inputs.operationId))
            val entry = assertUnresolved(fixture)
            val original = fixture.inputs.record
            val line = original.lines.single()
            val other = ArrivalRetirementNoAdmissionInputs("B")
            val supplierB = ArrivalRetirementFrozenV2.id("supplier-B")
            val changedRecords = listOf(
                original.copy(lines = listOf(line.copy(baseQuantity = Quantity.parse(line.baseQuantity.unitId, "12", 0)))),
                original.copy(lines = listOf(line.copy(baseQuantity = Quantity.parse(line.baseQuantity.unitId, "20", 0)))),
                original.copy(lines = listOf(line.copy(sourceId = other.sourceId, custodyUnitId = other.custodyId))),
                original.copy(supplierId = supplierB, evidence = original.evidence.copy(
                    documents = original.evidence.documents.map { it.copy(supplierId = supplierB) })),
                original.copy(evidence = original.evidence.copy(documents = other.evidence.documents)),
                original.copy(evidence = original.evidence.copy(ruleId = ArrivalRetirementFrozenV2.id("other-rule"))),
                original.copy(evidence = original.evidence.copy(policy = original.evidence.policy.copy(publicationRevision = Revision(6)))))
            val pending = checkNotNull(entry.actionApproval?.retirementAttempt)
            val live = pending.boundAttempt(entry)
            for (record in changedRecords) {
                val request = LocalUnpricedArrivalCommandsV2.record(entry.operationId, record)
                assertFalse(live.matches(request))
                val changed = MasterRetirementAttempt.capture(live.attemptId, live.sequence, live.approvalId, request, live.confirmationDigest)
                assertFailsWith<IllegalArgumentException> {
                    checkNotNull(entry.actionApproval).withNoAdmission(entry, changed, LocalActionApprovalNoAdmissionReason.QUEUE_FULL)
                }
                assertEquals(entry, fixture.latest())
            }
            assertFailsWith<IllegalArgumentException> {
                checkNotNull(entry.actionApproval).withNoAdmission(entry, live.copy(confirmationDigest = PayloadDigest("3".repeat(64))),
                    LocalActionApprovalNoAdmissionReason.ENDPOINT_UNAVAILABLE)
            }
            fixture.reopen()
            assertEquals(entry, fixture.latest())
            fixture.assertOriginal(entry)
            fixture.assertNoBusinessOrIssuance()
        }
    }

    @Test
    fun arrna05StaleI1CompletionCasCannotReleaseHeldI2AcrossReopen() = runTest {
        withArrivalRetirementNoAdmissionFixture { fixture ->
            assertIs<LocalActionApprovalResult.Available>(fixture.gateway.retireActionApproval(fixture.inputs.operationId))
            val pendingI1 = fixture.journal.savedInvocations.last()
            val completedI1 = fixture.latest()
            val i1 = checkNotNull(completedI1.actionApproval?.retirementAttempt)
            val hold = ArrivalRetirementHold()
            fixture.endpoint.onRetire = hold::receive
            val retry = async { fixture.gateway.retireActionApproval(fixture.inputs.operationId) }
            val handoff = hold.entered.await()
            val pendingI2 = fixture.latest()
            val i2 = checkNotNull(pendingI2.actionApproval?.retirementAttempt)
            assertEquals(pendingI1.actionApproval?.localPhase, pendingI2.actionApproval?.localPhase)
            assertEquals(i1.sequence + 1, i2.sequence)
            assertNotEquals(i1.attemptId, i2.attemptId)
            assertEquals(listOf(checkNotNull(fixture.i0.actionApproval?.retirementAttempt), i1), pendingI2.actionApproval?.completedRetirementAttempts)
            // The actual journal CAS is the old completion delivery seam. A single
            // public suspend call is not fabricated into a duplicate callback stream.
            assertEquals(JournalWrite.IDENTITY_CONFLICT, fixture.journal.updateActionApproval(pendingI1, completedI1))
            assertEquals(pendingI2, fixture.latest())
            fixture.reopen()
            assertEquals(pendingI2, fixture.latest())
            assertNull(pendingI2.actionApproval?.retirementAttempt?.noAdmissionReason)
            fixture.assertOriginal(pendingI2)
            hold.reply.complete(LocalActionApprovalRetireResult.NotAdmitted(handoff.attempt, LocalActionApprovalNoAdmissionReason.ENDPOINT_UNAVAILABLE))
            assertIs<LocalActionApprovalResult.Available>(retry.await())
            val completedI2 = fixture.latest()
            assertEquals(i2.copy(noAdmissionReason = LocalActionApprovalNoAdmissionReason.ENDPOINT_UNAVAILABLE.name),
                completedI2.actionApproval?.retirementAttempt)
            assertEquals(listOf(checkNotNull(fixture.i0.actionApproval?.retirementAttempt), i1), completedI2.actionApproval?.completedRetirementAttempts)
            assertEquals(2, fixture.endpoint.retirements.size - fixture.baselineRetirements)
            fixture.assertOriginal(completedI2)
            fixture.assertNoBusinessOrIssuance()
        }
    }

    @Test
    fun arrna06SupportedLegacyUnresolvedRecordHasNoAcceptingInvocationDefault() = runTest {
        withArrivalRetirementNoAdmissionFixture(completedI0 = false) { fixture ->
            val attached = fixture.latest()
            val legacy = attached.copy(actionApproval = checkNotNull(attached.actionApproval).copy(
                phase = LocalActionApprovalPhase.RETIRE_UNRESOLVED.name))
            assertEquals(JournalWrite.DURABLE, fixture.journal.updateActionApproval(attached, legacy))
            val encoded = journalJson.encodeToString(StoredClientIntentV1.serializer(), legacy)
            assertFalse(encoded.contains("\"retirementAttempt\""))
            assertFalse(encoded.contains("\"completedRetirementAttempts\""))
            fixture.reopen()
            assertEquals(legacy, fixture.latest())
            val equalCoordinates = MasterRetirementAttempt.capture(ArrivalRetirementFrozenV2.id("I1"), 1,
                fixture.inputs.approvalId, fixture.inputs.request, fixture.inputs.confirmationDigest)
            assertFails { checkNotNull(legacy.actionApproval).withNoAdmission(legacy, equalCoordinates, LocalActionApprovalNoAdmissionReason.QUEUE_FULL) }
            val lookups = fixture.endpoint.approvalLookups.size
            val result = assertIs<LocalActionApprovalResult.Available>(fixture.gateway.retireActionApproval(fixture.inputs.operationId)).operation
            assertEquals(lookups + 1, fixture.endpoint.approvalLookups.size)
            assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED, result.actionApproval?.phase)
            assertNull(result.actionApproval?.retirementAttempt)
            assertFalse(result.submitAllowed)
            assertEquals(0, fixture.endpoint.retirements.size)
            assertEquals(legacy, fixture.latest())
            fixture.assertOriginal(legacy)
            fixture.assertNoBusinessOrIssuance()
        }
    }

    @Test
    fun arrna07InitialKnownNoCommitSaveFailurePreservesI0AndPreventsHandoff() = runTest {
        withArrivalRetirementNoAdmissionFixture { fixture ->
            fixture.journal.failFreshInvocation = true
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN,
                assertIs<LocalActionApprovalResult.Unavailable>(fixture.gateway.retireActionApproval(fixture.inputs.operationId)).problem)
            assertEquals(1, fixture.journal.rejectedWrites.size)
            assertEquals(fixture.i0, fixture.latest())
            assertEquals(0, fixture.endpoint.retirements.size - fixture.baselineRetirements)
            fixture.reopen()
            assertEquals(fixture.i0, fixture.latest())
            fixture.assertOriginal(fixture.latest())
            fixture.assertNoBusinessOrIssuance()
        }
    }

    @Test
    fun arrna07CompletionKnownNoCommitSaveFailureLeavesI1LookupOnly() = runTest {
        withArrivalRetirementNoAdmissionFixture { fixture ->
            fixture.journal.failCompletion = true
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN,
                assertIs<LocalActionApprovalResult.Unavailable>(fixture.gateway.retireActionApproval(fixture.inputs.operationId)).problem)
            val unresolved = assertUnresolved(fixture)
            val (before, attempted) = fixture.journal.rejectedWrites.single()
            assertEquals(unresolved, before)
            assertEquals(LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED, attempted.actionApproval?.localPhase)
            fixture.reopen()
            assertEquals(unresolved, fixture.latest())
            val lookups = fixture.endpoint.approvalLookups.size
            assertIs<LocalActionApprovalResult.Available>(fixture.gateway.retireActionApproval(fixture.inputs.operationId))
            assertEquals(lookups + 1, fixture.endpoint.approvalLookups.size)
            assertEquals(unresolved, fixture.latest())
            assertEquals(1, fixture.endpoint.retirements.size - fixture.baselineRetirements)
            fixture.assertNoBusinessOrIssuance()
        }
    }

    private suspend fun assertUnresolved(fixture: ArrivalRetirementNoAdmissionFixture): StoredClientIntentV1 {
        val entry = fixture.latest()
        assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED, entry.actionApproval?.localPhase)
        assertNull(entry.actionApproval?.retirementAttempt?.noAdmissionReason)
        assertNotNull(entry.actionApproval?.retirementAttempt)
        assertEquals(listOf(checkNotNull(fixture.i0.actionApproval?.retirementAttempt)), entry.actionApproval?.completedRetirementAttempts)
        fixture.assertOriginal(entry)
        return entry
    }
}
