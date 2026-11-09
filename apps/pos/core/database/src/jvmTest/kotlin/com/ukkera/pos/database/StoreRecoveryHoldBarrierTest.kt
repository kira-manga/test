package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.recovery.BoundedRecoveryScope
import com.ukkera.pos.recovery.EvidenceVerification
import com.ukkera.pos.recovery.JournalPosition
import com.ukkera.pos.recovery.RecoveryAdmissionMachine
import com.ukkera.pos.recovery.RecoveryAdmissionPolicy
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.recovery.RecoveryCheckpoint
import com.ukkera.pos.recovery.RecoveryEvidenceAssertion
import com.ukkera.pos.recovery.RecoveryEvidenceRef
import com.ukkera.pos.recovery.RecoveryEvidenceVerifier
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.recovery.RecoveryResourceSelector
import com.ukkera.pos.recovery.ResourceAdmissionDecision
import com.ukkera.pos.recovery.ResourceReadmission
import com.ukkera.pos.recovery.ResourceReconciliation
import com.ukkera.pos.recovery.ResourceReconciliationKind
import com.ukkera.pos.recovery.TransitionDisposition
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real local files/model transitions, with explicitly SYNTHETIC trust and recovery evidence. */
class StoreRecoveryHoldBarrierTest {
    @Test fun healthyGuardedCommandCapturesItsFootprintAndRestrictionExactlyOnce() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val handler = BarrierCounter(f.handler)
            assertTrue(assertIs<MutationReply.Recorded>(f.barrierStore(handler).execute(testPrincipal(), f.request(880))).committed)
            assertEquals(1, handler.footprintReads)
            assertEquals(1, handler.barrierReads)
            assertEquals(1, handler.evaluations)
            assertEquals(1L, f.count("operation_result"))
        } finally { f.close() }
    }

    @Test fun genuinelyReadmittedWholeKindAndUnrelatedAliasHoldsDenyBeforeEvaluationWithoutWrites() = runTest {
        val selectors = listOf<List<RecoveryResourceSelector>?>(null,
            listOf(RecoveryResourceSelector.Kind(RecoveryResourceKind.STOCK)),
            listOf(RecoveryResourceSelector.Resource(BARRIER_ALIAS)))
        for ((index, selection) in selectors.withIndex()) {
            val f = StoreFixture()
            try {
                f.seed()
                val stock = if (index == 2) BARRIER_ALIAS else BARRIER_STOCK
                val state = syntheticBarrierRecovery(selection, setOf(BARRIER_AUTH, stock))
                // Genuine readmissions make the existing exact-key policy positive.
                assertEquals(ResourceAdmissionDecision.ADMITTED,
                    RecoveryAdmissionPolicy.evaluate(state, TEST_SCOPE.branchId, setOf(BARRIER_AUTH, BARRIER_STOCK)))
                assertTrue(state.readmissions.any { it.resource == stock })
                f.installSyntheticBarrierRecovery(state)
                val before = f.barrierSnapshot()
                val handler = BarrierCounter(f.handler)
                val reply = assertIs<MutationReply.Deferred>(f.barrierStore(handler).execute(testPrincipal(), f.request(881 + index)))
                assertEquals(DeferredReason.RESOURCE_RECONCILIATION_REQUIRED, reply.reason)
                assertEquals(1, f.handler.prepareCount)
                assertEquals(0, handler.evaluations)
                assertEquals(before, f.barrierSnapshot())
                assertNull(f.database.facts().aggregate(TEST_SCOPE.storageKey(), "qualification.counter", testId(10).value))
            } finally { f.close() }
        }
    }

    @Test fun aRestrictionNeverSuppliesMissingOrSubstitutedCurrentActorFootprint() = runTest {
        for (footprint in listOf(emptySet(), setOf(BARRIER_STOCK),
            setOf(BARRIER_STOCK, RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, testId(888))))) {
            val f = StoreFixture()
            try {
                f.seed()
                val handler = BarrierCounter(f.handler, footprint = footprint)
                val before = f.barrierSnapshot()
                assertEquals(DeferredReason.RESOURCE_RECONCILIATION_REQUIRED,
                    assertIs<MutationReply.Deferred>(f.barrierStore(handler).execute(testPrincipal(), f.request(889))).reason)
                assertEquals(0, handler.evaluations)
                assertEquals(before, f.barrierSnapshot())
            } finally { f.close() }
        }
    }

    @Test fun aGuardedKindNeedNotAppearInTheFootprintToDenyAnUnrelatedAlias() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val state = syntheticBarrierRecovery(listOf(RecoveryResourceSelector.Resource(BARRIER_ALIAS)), setOf(BARRIER_AUTH))
            assertEquals(ResourceAdmissionDecision.ADMITTED,
                RecoveryAdmissionPolicy.evaluate(state, TEST_SCOPE.branchId, setOf(BARRIER_AUTH)))
            f.installSyntheticBarrierRecovery(state)
            // Deliberately incomplete STOCK footprint cannot use kind-subset filtering
            // to bypass a restrictive guard. No operation is allowed by this fixture.
            val handler = BarrierCounter(f.handler, footprint = setOf(BARRIER_AUTH))
            val before = f.barrierSnapshot()
            assertEquals(DeferredReason.RESOURCE_RECONCILIATION_REQUIRED,
                assertIs<MutationReply.Deferred>(f.barrierStore(handler).execute(testPrincipal(), f.request(890))).reason)
            assertEquals(0, handler.evaluations)
            assertEquals(before, f.barrierSnapshot())
        } finally { f.close() }
    }

    @Test fun authorizationOnlyRecoveryStillNeedsReadmissionButIsNotAPermanentRestoredBranchBan() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val selection = emptyList<RecoveryResourceSelector>() // Machine always preserves AUTHORIZATION hold.
            val pending = syntheticBarrierRecovery(selection, emptySet())
            f.installSyntheticBarrierRecovery(pending)
            val handler = BarrierCounter(f.handler)
            val request = f.request(891)
            val store = f.barrierStore(handler)
            val before = f.barrierSnapshot()
            assertEquals(DeferredReason.RESOURCE_RECONCILIATION_REQUIRED,
                assertIs<MutationReply.Deferred>(store.execute(testPrincipal(), request)).reason)
            assertEquals(0, handler.evaluations)
            assertEquals(before, f.barrierSnapshot())
            val admitted = syntheticBarrierRecovery(selection, setOf(BARRIER_AUTH))
            assertEquals(pending.incidentId, admitted.incidentId)
            assertTrue(admitted.holds.isNotEmpty())
            f.installSyntheticBarrierRecovery(admitted)
            assertTrue(assertIs<MutationReply.Recorded>(store.execute(testPrincipal(), request)).committed)
            assertEquals(1, handler.evaluations)
            assertEquals(1L, f.count("operation_result"))
        } finally { f.close() }
    }

    @Test fun actualCommittedReplayPrecedesTheBarrierButStillRequiresCurrentResultAuthorization() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val principal = testPrincipal()
            val handler = BarrierCounter(f.handler)
            val store = f.barrierStore(handler)
            val request = f.request(892, CounterInput(claim = "barrier-custody"))
            val original = assertIs<MutationReply.Recorded>(store.execute(principal, request))
            assertTrue(original.committed)
            assertEquals(1, handler.evaluations)
            val state = syntheticBarrierRecovery(listOf(RecoveryResourceSelector.Kind(RecoveryResourceKind.STOCK)),
                setOf(BARRIER_AUTH, BARRIER_STOCK))
            assertEquals(ResourceAdmissionDecision.ADMITTED,
                RecoveryAdmissionPolicy.evaluate(state, TEST_SCOPE.branchId, setOf(BARRIER_AUTH, BARRIER_STOCK)))
            f.installSyntheticBarrierRecovery(state)
            val before = f.barrierSnapshot()
            val replay = assertIs<MutationReply.Recorded>(store.execute(principal, request))
            assertTrue(replay.replayed)
            assertEquals(original.result, replay.result)
            assertEquals(original.code, replay.code)
            assertEquals(original.originalActorId, replay.originalActorId)
            assertEquals(original.authorityEpoch, replay.authorityEpoch)
            assertEquals(original.sequence, replay.sequence)
            assertEquals(original.recordedAt, replay.recordedAt)
            assertEquals(1, f.handler.prepareCount)
            assertEquals(1, handler.evaluations)
            assertEquals(before, f.barrierSnapshot())
            assertEquals(DeferredReason.RESOURCE_RECONCILIATION_REQUIRED,
                assertIs<MutationReply.Deferred>(store.execute(principal,
                    f.request(893, CounterInput(expectedRevision = Revision(1))))).reason)
            assertEquals(2, f.handler.prepareCount)
            assertEquals(1, handler.evaluations)
            assertEquals(before, f.barrierSnapshot())
            f.setAuth("disabled")
            val disabled = f.barrierSnapshot()
            assertEquals(DeferredReason.AUTHORIZATION_DENIED,
                assertIs<MutationReply.Deferred>(store.execute(principal, request)).reason)
            assertEquals(2, f.handler.prepareCount)
            assertEquals(1, handler.evaluations)
            assertEquals(disabled, f.barrierSnapshot())
        } finally { f.close() }
    }

    @Test fun existingHandlerDefaultDoesNotSilentlyAcquireANewRestriction() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val state = syntheticBarrierRecovery(null, setOf(BARRIER_AUTH, BARRIER_STOCK))
            f.installSyntheticBarrierRecovery(state)
            assertEquals(ResourceAdmissionDecision.ADMITTED,
                RecoveryAdmissionPolicy.evaluate(state, TEST_SCOPE.branchId, setOf(BARRIER_AUTH, BARRIER_STOCK)))
            val reply = assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), f.request(894)))
            assertTrue(reply.committed)
            assertFalse(reply.replayed)
        } finally { f.close() }
    }
}

private val BARRIER_AUTH = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, TEST_ACTOR)
private val BARRIER_STOCK = RecoveryResourceKey(RecoveryResourceKind.STOCK, testId(10))
private val BARRIER_ALIAS = RecoveryResourceKey(RecoveryResourceKind.STOCK, testId(899))

private class BarrierCounter(
    private val original: CounterHandler,
    private val footprint: Set<RecoveryResourceKey>? = null,
) : MasterCommandHandler by original {
    var footprintReads = 0
    var barrierReads = 0
    var evaluations = 0
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val prepared = original.prepare(normalized, view, context)
        val resources = footprint ?: prepared.affectedResources
        return object : PreparedMutation() {
            override val requirement = prepared.requirement
            override val affectedResources: Set<RecoveryResourceKey>
                get() { footprintReads++; return resources }
            override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind>
                get() { barrierReads++; return setOf(RecoveryResourceKind.STOCK) }
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                evaluations++
                return prepared.evaluate(authorization, recordedAt)
            }
        }
    }
}

private fun StoreFixture.barrierStore(handler: MasterCommandHandler): AtomicMasterStore =
    AtomicMasterStore(database, TEST_SCOPE, TEST_AUTHORITY, listOf(handler), auth, runtime, MasterClock { TEST_TIME })

/** Model transitions with a SYNTHETIC verifier, not a production recovery/provisioning adapter. */
internal fun syntheticBarrierRecovery(
    selectors: List<RecoveryResourceSelector>?,
    resourcesToReadmit: Set<RecoveryResourceKey>,
): RecoveryAdmissionState {
    val incident = testId(900)
    val checkpoint = RecoveryCheckpoint(testId(901),
        JournalPosition(TEST_SCOPE.branchId, TEST_AUTHORITY, JournalSequence(0)), "b".repeat(64))
    val machine = RecoveryAdmissionMachine(object : RecoveryEvidenceVerifier {
        override fun verify(assertion: RecoveryEvidenceAssertion): EvidenceVerification = EvidenceVerification.VERIFIED
    })
    fun evidence(id: Int) = RecoveryEvidenceRef(testId(id), "c".repeat(64))
    var state = machine.restore(TEST_SCOPE.branchId, incident, checkpoint, emptyList())
    if (selectors != null) {
        val bound = machine.boundAffectedScope(state, state.revision, BoundedRecoveryScope(testId(902),
            TEST_SCOPE.branchId, incident, checkpoint, selectors, TEST_ACTOR, evidence(903), listOf(evidence(904))))
        assertEquals(TransitionDisposition.APPLIED, bound.disposition)
        state = bound.state
    }
    resourcesToReadmit.forEachIndexed { index, resource ->
        val holds = state.holds.filter { hold -> hold.selectors.any {
            when (it) {
                RecoveryResourceSelector.WholeBranch -> true
                is RecoveryResourceSelector.Kind -> it.kind == resource.kind
                is RecoveryResourceSelector.Resource -> it.resource == resource
            }
        } }.map { it.holdId }.toSet()
        assertTrue(holds.isNotEmpty(), "The fixture must really reconcile an existing hold")
        val reconciliation = ResourceReconciliation(testId(910 + index * 2), incident, TEST_SCOPE.branchId,
            checkpoint, resource, ResourceReconciliationKind.entries.single { it.resourceKind == resource.kind },
            TEST_ACTOR, evidence(950 + index * 2), listOf(evidence(951 + index * 2)), emptySet(), holds, null)
        val reconciled = machine.reconcile(state, state.revision, reconciliation)
        assertEquals(TransitionDisposition.APPLIED, reconciled.disposition)
        val admitted = machine.readmit(reconciled.state, reconciled.state.revision, ResourceReadmission(
            testId(911 + index * 2), incident, TEST_SCOPE.branchId, resource, reconciliation.reconciliationId,
            TEST_ACTOR, evidence(970 + index), emptySet(), holds))
        assertEquals(TransitionDisposition.APPLIED, admitted.disposition)
        assertTrue(admitted.state.readmissions.any { it.resource == resource && it.coveredHoldIds == holds })
        state = admitted.state
    }
    return state
}

/** Installs only model-produced state in a disposable test file; not an administrative command. */
internal suspend fun StoreFixture.installSyntheticBarrierRecovery(state: RecoveryAdmissionState) {
    assertEquals(TEST_SCOPE.branchId, state.branchId)
    database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            connection.usePrepared("UPDATE branch_authority SET recoveryJson=? WHERE scope=?") {
                it.bindText(1, Json.encodeToString(state)); it.bindText(2, TEST_SCOPE.storageKey()); it.step()
            }
        }
    }
}

private data class BarrierSnapshot(val totalChanges: Long, val branch: BranchRow, val commandCounts: List<Long>)

private suspend fun StoreFixture.barrierSnapshot(): BarrierSnapshot {
    val changes = database.useWriterConnection { connection ->
        connection.usePrepared("SELECT total_changes()") { assertTrue(it.step()); it.getLong(0) }
    }
    val branch = assertNotNull(database.facts().branch(TEST_SCOPE.storageKey()))
    val counts = listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent", "unique_claim").map { count(it) }
    return BarrierSnapshot(changes, branch, counts)
}
