package com.ukkera.pos.sales.store.independent.foodkitchenexecutionv1

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockAllocationRequest
import com.ukkera.pos.inventory.store.LocalStockDemandLine
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Partial L1 FKEX-01 request-classification only. A null extra kitchen restriction
 * is NOT an authority grant, a successful Draft/Add or a no-effects persistence pass.
 * No FoodRow, retained original, stock cut, writer or historical source is constructed.
 */
public class FoodKitchenExecutionFrozenDraftClassifierSlicesTest {
    @Test
    public fun fkex01DEmptyProtectedDraftClassificationDoesNotAskForKitchenExecution(): Unit = runTest {
        for (activity in FoodKitchenExecutionFrozenInputs.activities) {
            for (service in FoodKitchenExecutionFrozenInputs.representableServices) {
                assertNoKitchenPrerequisite(LocalFoodStockRequestV2.ActivityProtected(
                    LocalFoodDraftCreateV1(FoodKitchenExecutionFrozenInputs.order, activity, service)))
            }
        }
    }

    @Test
    public fun fkex01A0ExactGuardedAdditionToEmptyClassificationDoesNotAskForKitchenExecution(): Unit = runTest {
        assertNoKitchenPrerequisite(FoodKitchenExecutionFrozenInputs.addToEmpty())
    }

    @Test
    public fun fkex01A1ExactGuardedAdditionToNonemptyClassificationDoesNotAskForKitchenExecution(): Unit = runTest {
        assertNoKitchenPrerequisite(FoodKitchenExecutionFrozenInputs.addToNonempty())
    }

    private suspend fun assertNoKitchenPrerequisite(request: LocalFoodStockRequestV2) {
        val view = FoodKitchenExecutionFrozenUnreadView()
        val source = FoodKitchenExecutionFrozenRefusingSource()
        assertNull(foodStockKitchenExecutionAdmission(request, view, source))
        assertEquals(0, view.reads)
        assertEquals(0, source.calls)
    }
}

/** Every read fails; this cannot act as an accepting empty stock/history view. */
private class FoodKitchenExecutionFrozenUnreadView : CommandReadView {
    override val scope: BranchScope = FoodKitchenExecutionFrozenInputs.scope
    var reads: Int = 0
    private fun unexpected(): Nothing { reads++; error("No current or historical authority is supplied") }
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = unexpected()
    override suspend fun claim(key: ClaimKey): DomainId? = unexpected()
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = unexpected()
    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage = unexpected()
    override suspend fun claimKeys(namespace: String, prefix: String, after: String?, limit: Int): ClaimKeyPage = unexpected()
}

/** No accepting quotation, activity, reservation, history or execution implementation. */
private class FoodKitchenExecutionFrozenRefusingSource : TransactionFoodStockSource {
    var calls: Int = 0
    private fun unexpected(): Nothing { calls++; error("No source authority or execution support is supplied") }
    override suspend fun prepareSelectionPreview(view: CommandReadView, activity: LocalSalesActivity,
        service: LocalFoodServiceV1, catalogRevision: Revision,
        portions: List<LocalFoodPortionSelectionV1>): PreparedFoodSelectionPreviewV1? = unexpected()
    override suspend fun prepare(view: CommandReadView, activity: LocalSalesActivity, service: LocalFoodServiceV1,
        catalogRevision: Revision, portions: List<LocalFoodPortionSelectionV1>): PreparedFoodRecipeBatchV1? = unexpected()
    override suspend fun currentForSend(view: CommandReadView, snapshot: LocalFoodRecipeSnapshotV1): Boolean = unexpected()
    override suspend fun prepareKitchenExecution(view: CommandReadView, activity: LocalSalesActivity,
        service: LocalFoodServiceV1, snapshots: List<LocalFoodRecipeSnapshotV1>): PreparedMutationAdmissionRestriction? = unexpected()
    override suspend fun requireHistorical(view: CommandReadView, snapshot: LocalFoodRecipeSnapshotV1): Unit = unexpected()
    override suspend fun prepareActivity(view: CommandReadView, activity: LocalSalesActivity): PreparedFoodActivityAdmission = unexpected()
    override suspend fun requireOriginalActivity(sources: LocalCommerceOriginalSources,
        activity: LocalSalesActivity): Set<RecoveryResourceKey> = unexpected()
    override suspend fun requireOriginalHistorical(sources: LocalCommerceOriginalSources,
        snapshot: LocalFoodRecipeSnapshotV1): Set<RecoveryResourceKey> = unexpected()
    override suspend fun requireOriginalCurrentForSend(sources: LocalCommerceOriginalSources,
        snapshot: LocalFoodRecipeSnapshotV1): Set<RecoveryResourceKey> = unexpected()
    override suspend fun requireOriginalQuote(sources: LocalCommerceOriginalSources, activity: LocalSalesActivity,
        service: LocalFoodServiceV1, catalogRevision: Revision, selections: List<LocalFoodPortionSelectionV1>,
        quoted: LocalFoodQuotedBatchV1): Set<RecoveryResourceKey> = unexpected()
    override fun requireLegacyRecipe(snapshot: LocalFoodRecipeSnapshotV1, scope: BranchScope): Unit = unexpected()
    override suspend fun observeReservationAuthority(view: CommandReadView,
        lines: List<LocalStockAllocationRequest>): Set<RecoveryResourceKey> = unexpected()
    override suspend fun requireOriginalReservationAuthority(sources: LocalCommerceOriginalSources,
        line: LocalStockDemandLine): Set<RecoveryResourceKey> = unexpected()
}
