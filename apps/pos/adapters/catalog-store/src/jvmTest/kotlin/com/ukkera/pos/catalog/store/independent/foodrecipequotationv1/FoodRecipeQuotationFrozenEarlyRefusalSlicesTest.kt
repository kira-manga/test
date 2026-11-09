package com.ukkera.pos.catalog.store.independent.foodrecipequotationv1

import com.ukkera.pos.catalog.store.CompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.LocalCatalogFoodSource
import com.ukkera.pos.catalog.store.LocalCatalogFoodStockSource
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.AggregateKeyPage
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.ClaimKeyPage
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.sales.store.LocalFoodPortionSelectionV1
import com.ukkera.pos.sales.store.LocalFoodServiceV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.sales.store.TransactionFoodRecipeQuotationSourceV1
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Bounded FRQ-013 observations at both actual quotation source adapters. A trap
 * refuses EVERY scope/read/signature access; it is neither a genuine borrowed
 * loan nor an installed source, and supplies no aggregates or retained history.
 * Only exact null/no-access results are asserted, never a particular grouping
 * cause, genuine authority, pricing or durable zero-effects. Whole FRQ-013 remains
 * UNBOUND, as do its seven source-dependent variants.
 *
 * The original ordered fixture's LINE is preserved, not mapped to PA/PB. LINE
 * is absent from the member IDs, so the claimed-line fixture has an unresolved
 * anchor gap. The incompatible modifier mutation cannot establish which guard
 * produced a refusal or qualify any positive SOURCE case.
 */
class FoodRecipeQuotationFrozenEarlyRefusalSlicesTest {
    private val f = FrozenFoodRecipeQuotationPublicInputs
    // The semantic service fixture specifies DINE_IN, not a reference. Inert shape binding only.
    private val service = LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "shape-only")

    @Test
    fun frq013EmptyBatchIsExactlyNullWithoutAnyViewOrSignatureAccess() = runTest {
        observeNullWithoutAccess("empty-batch", emptyList())
    }

    @Test
    fun frq013RepeatedFrozenPaIsExactlyNullWithoutAnyViewOrSignatureAccess() = runTest {
        val ordered = orderedPortions()
        observeNullWithoutAccess("duplicate-portion", ordered + ordered.last())
    }

    @Test
    fun frq013OriginalLineClaimAndModifierMutationAreNullWithoutAnyRead() = runTest {
        val ordered = orderedPortions().toMutableList()
        ordered[1] = ordered[1].copy(modifierIds = listOf(f.m2))
        observeNullWithoutAccess("incompatible-homogeneous-line; original LINE is not a member", ordered)
    }

    private fun orderedPortions(): List<LocalFoodPortionSelectionV1> = listOf(f.pb, f.pa).map { portionId ->
        LocalFoodPortionSelectionV1(portionId, f.output, f.recipe, Revision(3), f.choice,
            listOf(f.m2, f.m1), Quantity(f.serving, 1, 0), "  لا بصل  ", f.line, "")
    }

    private suspend fun observeNullWithoutAccess(variant: String, portions: List<LocalFoodPortionSelectionV1>) {
        for (stockWrapped in listOf(false, true)) {
            val trap = RejectEveryViewAccess()
            var signatureAccesses = 0
            val signatures = CompanyPublicationSignatureVerifier { _, _, _, _ ->
                signatureAccesses += 1
                error("No-read refusal slice reached signature verification")
            }
            val source: TransactionFoodRecipeQuotationSourceV1 = if (stockWrapped)
                LocalCatalogFoodStockSource(signatures) else LocalCatalogFoodSource(signatures)
            val label = "$variant; stockWrapped=$stockWrapped"
            assertNull(source.prepareQuotation(trap, LocalSalesActivity.RESTAURANT, service, Revision(10), portions), label)
            assertEquals(emptyList(), trap.accesses, label)
            assertEquals(0, signatureAccesses, label)
        }
    }

    private class RejectEveryViewAccess : CommandReadView {
        val accesses: MutableList<String> = mutableListOf()
        override val scope: BranchScope get() = accessed("scope")
        override suspend fun aggregate(key: AggregateKey): StoredAggregate? = accessed("aggregate")
        override suspend fun claim(key: ClaimKey): DomainId? = accessed("claim")
        override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage =
            accessed("aggregateKeys")
        override suspend fun claimKeys(namespace: String, prefix: String, after: String?, limit: Int): ClaimKeyPage =
            accessed("claimKeys")
        override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? =
            accessed("retainedCommand")

        private fun accessed(name: String): Nothing {
            accesses += name
            error("No-read refusal slice accessed CommandReadView.$name")
        }
    }
}
