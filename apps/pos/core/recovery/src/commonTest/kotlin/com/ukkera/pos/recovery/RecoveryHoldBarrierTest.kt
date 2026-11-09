package com.ukkera.pos.recovery

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure policy/reducer fixtures with synthetic evidence, not protected recovery or finalizer qualification. */
class RecoveryHoldBarrierTest {
    private val f = RecoveryFixtures
    private val machine = RecoveryAdmissionMachine(ModelEvidenceVerifier())
    private val stockBarrier = setOf(RecoveryResourceKind.STOCK)
    private val stockFootprint = setOf(f.auth, f.stock)

    @Test
    fun healthyAdmissionHasNoImplicitBarrierOrRequiredKindFootprintIntersection() {
        val state = RecoveryAdmissionState.initiallyProvisioned(f.branch)
        assertOrdinary(ResourceAdmissionDecision.ADMITTED, state, stockFootprint)
        for (barrier in listOf(emptySet(), stockBarrier, RecoveryResourceKind.entries.toSet())) {
            assertEquals(ResourceAdmissionDecision.ADMITTED,
                RecoveryAdmissionPolicy.evaluate(state, f.branch, stockFootprint, barrier))
        }
        // A profile restriction does not infer domain-specific footprint completeness.
        assertOrdinary(ResourceAdmissionDecision.ADMITTED, state, setOf(f.auth))
        assertEquals(ResourceAdmissionDecision.ADMITTED,
            RecoveryAdmissionPolicy.evaluate(state, f.branch, setOf(f.auth), stockBarrier))
    }

    @Test
    fun genuinelyReadmittedWholeBranchStillBlocksEverySelectedKind() {
        val restored = restore()
        val state = readmit(readmit(restored, f.auth), f.stock)
        assertEquals(listOf(RecoveryResourceSelector.WholeBranch), state.holds.single().selectors)
        assertEquals(restored.holds, state.holds)
        assertOrdinary(ResourceAdmissionDecision.ADMITTED, state, stockFootprint)
        for (kind in RecoveryResourceKind.entries) {
            assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED,
                RecoveryAdmissionPolicy.evaluate(state, f.branch, stockFootprint, setOf(kind)), kind.name)
        }
    }

    @Test
    fun genuinelyReadmittedStockKindStillBlocksStockButNotAnUnrelatedKind() {
        val bounded = bound(listOf(RecoveryResourceSelector.Kind(RecoveryResourceKind.STOCK)))
        val state = readmit(readmit(bounded, f.auth), f.stock)
        assertOrdinary(ResourceAdmissionDecision.ADMITTED, state, stockFootprint)
        assertStockBlocked(state, stockFootprint)
        assertEquals(ResourceAdmissionDecision.ADMITTED,
            RecoveryAdmissionPolicy.evaluate(state, f.branch, stockFootprint, setOf(RecoveryResourceKind.CASH_CUSTODY)))
    }

    @Test
    fun exactLegacyStockAliasesBlockEvenOutsideFootprintAndAfterTheirOwnReadmission() {
        // Synthetic branch/SKU/lot/pool-shaped identities; no canonical alias equivalence is asserted.
        for (legacyId in listOf(f.branch, f.id(5_001), f.id(5_002), f.id(5_003))) {
            val legacy = RecoveryResourceKey(RecoveryResourceKind.STOCK, legacyId)
            assertFalse(legacy in stockFootprint)
            val bounded = bound(listOf(RecoveryResourceSelector.Resource(legacy)))
            val authReadmitted = readmit(bounded, f.auth)
            assertOrdinary(ResourceAdmissionDecision.ADMITTED, authReadmitted, stockFootprint)
            assertStockBlocked(authReadmitted, stockFootprint)

            val state = readmit(authReadmitted, legacy)
            assertTrue(state.readmissions.any { it.resource == legacy })
            assertOrdinary(ResourceAdmissionDecision.ADMITTED, state, stockFootprint)
            assertStockBlocked(state, stockFootprint)
            // Exact matching is also still denied after a genuine exact-key readmission.
            val exactFootprint = setOf(f.auth, legacy)
            assertOrdinary(ResourceAdmissionDecision.ADMITTED, state, exactFootprint)
            assertStockBlocked(state, exactFootprint)
        }
    }

    @Test
    fun authorizationOnlyFootprintCannotHideAnUnrelatedStockHold() {
        val legacy = RecoveryResourceKey(RecoveryResourceKind.STOCK, f.id(5_100))
        val state = readmit(bound(listOf(RecoveryResourceSelector.Resource(legacy))), f.auth)
        val affected = setOf(f.auth)
        assertFalse(affected.any { it.kind == RecoveryResourceKind.STOCK })
        assertOrdinary(ResourceAdmissionDecision.ADMITTED, state, affected)
        assertStockBlocked(state, affected)
    }

    @Test
    fun cashOnlyRecoveryAllowsIndependentStockButCannotSkipNormalCashReadmission() {
        val bounded = bound(listOf(RecoveryResourceSelector.Kind(RecoveryResourceKind.CASH_CUSTODY)))
        assertOrdinary(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED, bounded, stockFootprint)
        assertStockBlocked(bounded, stockFootprint)

        val authReadmitted = readmit(bounded, f.auth)
        assertOrdinary(ResourceAdmissionDecision.ADMITTED, authReadmitted, stockFootprint)
        assertEquals(ResourceAdmissionDecision.ADMITTED,
            RecoveryAdmissionPolicy.evaluate(authReadmitted, f.branch, stockFootprint, stockBarrier))
        val withCash = stockFootprint + f.cash
        assertOrdinary(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED, authReadmitted, withCash)
        assertStockBlocked(authReadmitted, withCash)

        val bothReadmitted = readmit(authReadmitted, f.cash)
        assertOrdinary(ResourceAdmissionDecision.ADMITTED, bothReadmitted, withCash)
        assertEquals(ResourceAdmissionDecision.ADMITTED,
            RecoveryAdmissionPolicy.evaluate(bothReadmitted, f.branch, withCash, stockBarrier))
    }

    @Test
    fun noStockSelectorsStillRequireAuthorizationButDoNotBanAllRestoredBranches() {
        val bounded = bound(emptyList())
        assertEquals(listOf(RecoveryResourceSelector.Kind(RecoveryResourceKind.AUTHORIZATION)), bounded.holds.single().selectors)
        assertOrdinary(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED, bounded, stockFootprint)
        assertStockBlocked(bounded, stockFootprint)
        val state = readmit(bounded, f.auth)
        assertNotNull(state.incidentId)
        assertTrue(state.holds.isNotEmpty())
        assertOrdinary(ResourceAdmissionDecision.ADMITTED, state, stockFootprint)
        assertEquals(ResourceAdmissionDecision.ADMITTED,
            RecoveryAdmissionPolicy.evaluate(state, f.branch, stockFootprint, stockBarrier))
    }

    @Test
    fun anExactOtherKindUsingTheSameIdIsNotAStockHold() {
        val cashAlias = RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, f.stock.resourceId)
        val state = readmit(bound(listOf(RecoveryResourceSelector.Resource(cashAlias))), f.auth)
        assertOrdinary(ResourceAdmissionDecision.ADMITTED, state, stockFootprint)
        assertEquals(ResourceAdmissionDecision.ADMITTED,
            RecoveryAdmissionPolicy.evaluate(state, f.branch, stockFootprint, stockBarrier))
        val withCash = stockFootprint + cashAlias
        assertOrdinary(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED, state, withCash)
        assertStockBlocked(state, withCash)
        val readmitted = readmit(state, cashAlias)
        assertOrdinary(ResourceAdmissionDecision.ADMITTED, readmitted, withCash)
        assertEquals(ResourceAdmissionDecision.ADMITTED,
            RecoveryAdmissionPolicy.evaluate(readmitted, f.branch, withCash, stockBarrier))
    }

    @Test
    fun mixedSelectorsAreAllInspectedRegardlessOfOrderAndUnrelatedReadmissions() {
        val legacy = RecoveryResourceKey(RecoveryResourceKind.STOCK, f.id(5_200))
        val selectors = listOf(
            RecoveryResourceSelector.Kind(RecoveryResourceKind.CASH_CUSTODY),
            RecoveryResourceSelector.Resource(f.refund),
            RecoveryResourceSelector.Resource(legacy),
        )
        for (offset in selectors.indices) {
            val ordered = selectors.drop(offset) + selectors.take(offset)
            val state = readmit(readmit(bound(ordered), f.auth), legacy)
            assertOrdinary(ResourceAdmissionDecision.ADMITTED, state, stockFootprint)
            assertStockBlocked(state, stockFootprint)
            assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED,
                RecoveryAdmissionPolicy.evaluate(state, f.branch, stockFootprint,
                    setOf(RecoveryResourceKind.PO_ALLOWANCE, RecoveryResourceKind.STOCK)))
            assertEquals(ResourceAdmissionDecision.ADMITTED,
                RecoveryAdmissionPolicy.evaluate(state, f.branch, stockFootprint, setOf(RecoveryResourceKind.PO_ALLOWANCE)))
        }
    }

    @Test
    fun laterStockHoldIsInspectedBeyondReadmittedRestoreHoldRegardlessOfReason() {
        val initial = readmit(bound(emptyList()), f.auth)
        assertEquals(listOf(RecoveryResourceSelector.Kind(RecoveryResourceKind.AUTHORIZATION)),
            initial.holds.single().selectors)
        assertOrdinary(ResourceAdmissionDecision.ADMITTED, initial, stockFootprint)
        assertEquals(ResourceAdmissionDecision.ADMITTED,
            RecoveryAdmissionPolicy.evaluate(initial, f.branch, stockFootprint, stockBarrier))

        val legacy = RecoveryResourceKey(RecoveryResourceKind.STOCK, f.id(5_400))
        assertFalse(legacy in stockFootprint)
        val proposal = f.lateTail(resource = legacy, n = 5_410)
        val recorded = machine.recordLateTail(initial, initial.revision, proposal)
        assertApplied(initial, recorded)
        val late = recorded.state
        assertEquals(2, late.holds.size)
        assertEquals(initial.holds, late.holds.dropLast(1))
        assertEquals(RecoveryHoldReason.UNMATCHED_LATE_EFFECT, late.holds.last().reason)
        assertEquals(listOf(RecoveryResourceSelector.Resource(legacy)), late.holds.last().selectors)
        assertEquals(proposal, late.lateTail.single().proposal)
        assertEquals(setOf(proposal.operation.effects.single().effectId), late.lateTail.single().unmatchedEffectIds)
        assertOrdinary(ResourceAdmissionDecision.ADMITTED, late, stockFootprint)
        assertStockBlocked(late, stockFootprint)
        val exactFootprint = setOf(f.auth, legacy)
        assertOrdinary(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED, late, exactFootprint)

        val state = readmit(late, legacy)
        assertTrue(state.readmissions.any { it.resource == legacy && it.coveredHoldIds == setOf(proposal.ingestId) })
        assertEquals(late.lateTail, state.lateTail)
        assertOrdinary(ResourceAdmissionDecision.ADMITTED, state, stockFootprint)
        assertStockBlocked(state, stockFootprint)
        assertOrdinary(ResourceAdmissionDecision.ADMITTED, state, exactFootprint)
        assertStockBlocked(state, exactFootprint)
    }

    @Test
    fun wrongBranchAndInvalidFootprintsRejectBeforeAnyMatchingHoldBarrier() {
        val restored = restore()
        val tooMany = buildSet {
            add(f.auth)
            repeat(MAX_RECOVERY_ITEMS) { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, f.id(20_000 + it))) }
        }
        assertEquals(MAX_RECOVERY_ITEMS + 1, tooMany.size)
        val invalid = listOf(emptySet(), setOf(f.stock), tooMany)
        for (affected in invalid + listOf(stockFootprint)) {
            assertEquals(ResourceAdmissionDecision.WRONG_BRANCH,
                RecoveryAdmissionPolicy.evaluate(restored, f.id(9_999), affected))
            for (barrier in listOf(emptySet(), stockBarrier)) {
                assertEquals(ResourceAdmissionDecision.WRONG_BRANCH,
                    RecoveryAdmissionPolicy.evaluate(restored, f.id(9_999), affected, barrier))
            }
        }
        for (affected in invalid) {
            assertOrdinary(ResourceAdmissionDecision.RESOURCE_FOOTPRINT_REQUIRED, restored, affected)
            assertEquals(ResourceAdmissionDecision.RESOURCE_FOOTPRINT_REQUIRED,
                RecoveryAdmissionPolicy.evaluate(restored, f.branch, affected, stockBarrier))
        }
    }

    @Test
    fun maximumValidFootprintRetainsTheOriginalInclusiveSizeBound() {
        val state = RecoveryAdmissionState.initiallyProvisioned(f.branch)
        val affected = buildSet {
            add(f.auth)
            repeat(MAX_RECOVERY_ITEMS - 1) { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, f.id(30_000 + it))) }
        }
        assertEquals(MAX_RECOVERY_ITEMS, affected.size)
        assertOrdinary(ResourceAdmissionDecision.ADMITTED, state, affected)
        assertEquals(ResourceAdmissionDecision.ADMITTED,
            RecoveryAdmissionPolicy.evaluate(state, f.branch, affected, stockBarrier))
    }

    @Test
    fun evaluationDoesNotMutateEvidenceInputsOrRetainAnImplicitRestriction() {
        val obligation = PreservedRecoveryObligation(f.id(5_300), f.refund, PreservedObligationKind.CUSTOMER_REFUND_DUE,
            f.cashier, f.identity.operationId, listOf(f.evidence(5_301)))
        val state = readmit(readmit(restore(listOf(obligation)), f.auth), f.stock)
        val before = Json.encodeToString(state)
        val affected = linkedSetOf(f.auth, f.stock)
        val barriers = linkedSetOf(RecoveryResourceKind.STOCK)
        val affectedBefore = affected.toList()
        val barriersBefore = barriers.toList()
        repeat(3) {
            assertOrdinary(ResourceAdmissionDecision.ADMITTED, state, affected)
            assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED,
                RecoveryAdmissionPolicy.evaluate(state, f.branch, affected, barriers))
            assertEquals(before, Json.encodeToString(state))
            assertEquals(affectedBefore, affected.toList())
            assertEquals(barriersBefore, barriers.toList())
        }
        assertEquals(listOf(obligation), state.obligations)
        // Subsequent callers may select no extra restriction; no global/sticky barrier is installed.
        barriers.clear()
        assertOrdinary(ResourceAdmissionDecision.ADMITTED, state, affected)
        assertEquals(ResourceAdmissionDecision.ADMITTED,
            RecoveryAdmissionPolicy.evaluate(state, f.branch, affected, barriers))
        assertEquals(before, Json.encodeToString(state))
    }

    private fun restore(obligations: List<PreservedRecoveryObligation> = emptyList()): RecoveryAdmissionState =
        machine.restore(f.branch, f.incident, f.checkpoint, obligations)

    private fun bound(selectors: List<RecoveryResourceSelector>): RecoveryAdmissionState {
        val state = restore()
        val declaration = BoundedRecoveryScope(f.id(6_000), f.branch, f.incident, f.checkpoint, selectors,
            f.approver, f.evidence(6_001), listOf(f.evidence(6_002)))
        val bounded = machine.boundAffectedScope(state, state.revision, declaration)
        assertApplied(state, bounded)
        assertEquals(declaration, bounded.state.scopeDeclaration)
        return bounded.state
    }

    private fun readmit(state: RecoveryAdmissionState, resource: RecoveryResourceKey): RecoveryAdmissionState {
        val reconciliation = f.reconciliation(state, resource)
        val reconciled = machine.reconcile(state, state.revision, reconciliation)
        assertApplied(state, reconciled)
        assertEquals(state.reconciliations + reconciliation, reconciled.state.reconciliations)
        val proposal = f.readmission(reconciled.state, reconciliation)
        val readmitted = machine.readmit(reconciled.state, reconciled.state.revision, proposal)
        assertApplied(reconciled.state, readmitted)
        assertEquals(reconciled.state.readmissions + proposal, readmitted.state.readmissions)
        assertEquals(state.holds, readmitted.state.holds)
        return readmitted.state
    }

    private fun assertApplied(before: RecoveryAdmissionState, transition: RecoveryTransition<RecoveryAdmissionState>) {
        assertEquals(TransitionDisposition.APPLIED, transition.disposition)
        assertNull(transition.refusal)
        assertEquals(before.revision.next(), transition.state.revision)
    }

    private fun assertOrdinary(
        expected: ResourceAdmissionDecision,
        state: RecoveryAdmissionState,
        affected: Set<RecoveryResourceKey>,
    ) {
        assertEquals(expected, RecoveryAdmissionPolicy.evaluate(state, f.branch, affected))
        assertEquals(expected, RecoveryAdmissionPolicy.evaluate(state, f.branch, affected, emptySet()))
    }

    private fun assertStockBlocked(state: RecoveryAdmissionState, affected: Set<RecoveryResourceKey>) {
        assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED,
            RecoveryAdmissionPolicy.evaluate(state, f.branch, affected, stockBarrier))
    }
}
