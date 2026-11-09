package com.ukkera.pos.sales.store.independent.foodatomicquotationbindingv1

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.store.FoodBodyV1
import com.ukkera.pos.sales.store.LocalCommerceOriginalSources
import com.ukkera.pos.sales.store.LocalFoodRecipeQuotationV1
import com.ukkera.pos.sales.store.LocalFoodRecipeSnapshotV1
import com.ukkera.pos.sales.store.PreparedFoodRecipeRouting
import com.ukkera.pos.sales.store.TransactionFoodRecipeRoutingVerificationSource
import com.ukkera.pos.sales.store.foodPrepareQuotationRouting
import com.ukkera.pos.sales.store.foodQuotationBindingPortion
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.ukkera.pos.sales.store.independent.foodatomicquotationbindingv1.FrozenFoodAtomicQuotationBindingInputs as F

/** Only the frozen pure/port observations are wired. The unread view and explicit
 * null/error ports provide no native authority, applicability or installed binding.
 * Whole FAQB-017/018 ingress, stock and original-history assertions remain UNBOUND. */
class FoodQuotationBindingPortFrozenTest {
    @Test
    fun faqb017_portUnavailableRemainsNullWithoutChangingOriginalQuotation() = runTest {
        val quotation = F.quotation()
        val before = F.quotationBytes(quotation)
        var calls = 0
        val source = source {
            calls++
            null
        }
        assertNull(foodPrepareQuotationRouting(unreadView(), source, quotation))
        assertEquals(1, calls)
        assertEquals(before, F.quotationBytes(quotation))
    }

    @Test
    fun faqb018_portFailureCannotBecomeUnavailableOrSuccessfulPreparation() = runTest {
        val quotation = F.quotation()
        val before = F.quotationBytes(quotation)
        var calls = 0
        val source = source {
            calls++
            error("Synthetic source failure")
        }
        val result = runCatching { foodPrepareQuotationRouting(unreadView(), source, quotation) }
        assertTrue(result.isFailure)
        assertEquals(1, calls)
        assertEquals(before, F.quotationBytes(quotation))
    }

    @Test
    fun faqb040_structuralBodyProjectionRetainsSelectionPreparationAndOriginalBytes() {
        for (id in listOf(F.u(301), F.u(302))) {
            val before = F.quotedEnvelope(id)
            val quotation = F.quotation(id)
            val snapshot = F.project(quotation)
            val envelopeBytes = F.envelopeBytes(before)
            val quotationBytes = F.quotationBytes(quotation)
            val snapshotBytes = F.snapshotBytes(snapshot)
            val projected = foodQuotationBindingPortion(before, quotation, snapshot)
            assertEquals(FoodBodyV1.Portion(F.u(200), F.selection(id), F.u(201), snapshot,
                F.preparation(id)), projected)
            assertEquals(envelopeBytes, F.envelopeBytes(before))
            assertEquals(quotationBytes, F.quotationBytes(quotation))
            assertEquals(snapshotBytes, F.snapshotBytes(snapshot))
        }
    }

    private fun source(prepare: () -> PreparedFoodRecipeRouting?): TransactionFoodRecipeRoutingVerificationSource =
        object : TransactionFoodRecipeRoutingVerificationSource {
            override suspend fun prepareRouting(view: CommandReadView,
                quotation: LocalFoodRecipeQuotationV1): PreparedFoodRecipeRouting? = prepare()
            override suspend fun requireOriginalRouting(sources: LocalCommerceOriginalSources,
                quotation: LocalFoodRecipeQuotationV1, snapshot: LocalFoodRecipeSnapshotV1): Set<RecoveryResourceKey> =
                error("Original/native verification is unbound in this port-only test")
        }

    private fun unreadView(): CommandReadView = object : CommandReadView {
        override val scope = F.scope
        override suspend fun aggregate(key: AggregateKey): StoredAggregate? = error("No native fixture")
        override suspend fun claim(key: ClaimKey): DomainId? = error("No native fixture")
        override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = error("No native fixture")
    }
}
