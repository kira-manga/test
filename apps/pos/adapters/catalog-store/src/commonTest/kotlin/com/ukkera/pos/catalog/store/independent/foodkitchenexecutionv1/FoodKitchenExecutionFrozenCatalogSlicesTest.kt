package com.ukkera.pos.catalog.store.independent.foodkitchenexecutionv1

import com.ukkera.pos.catalog.store.CompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.LocalCatalogFoodSource
import com.ukkera.pos.catalog.store.LocalCatalogFoodStockSource
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Partial L1 binding of frozen FKEX-03's unavailable installed execution source.
 * These are the REAL catalog adapters, with throwing read/trust dependencies.
 * Public recipe DTOs are deliberately unverified; they cannot grant activity,
 * quotation, stock, custody or execution authority. No native original is made.
 * Complete ingress/no-effects, mixed selection, instruction/capacity, positive
 * support, DELIVERY and all production families remain UNBOUND and NOT_RUN.
 */
public class FoodKitchenExecutionFrozenCatalogSlicesTest {
    @Test
    public fun fkex03RecipeCatalogCannotInferExecutionFromTwoPublicRecipeAndStationDtos(): Unit = runTest {
        assertUnavailable { verifier -> LocalCatalogFoodSource(verifier) }
    }

    @Test
    public fun fkex03StockCatalogDelegatesTheSameUnavailableExecutionWithoutReadingTrustOrHistory(): Unit = runTest {
        assertUnavailable { verifier -> LocalCatalogFoodStockSource(verifier) }
    }

    private suspend fun assertUnavailable(factory: (CompanyPublicationSignatureVerifier) -> TransactionFoodRecipeSourceV1) {
        for (activity in listOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE)) {
            for (service in FoodKitchenExecutionFrozenCatalogInputs.services) {
                for (twoStations in listOf(false, true)) {
                    val view = FoodKitchenExecutionFrozenCatalogUnreadView()
                    val verifier = FoodKitchenExecutionFrozenCatalogThrowingVerifier()
                    val source = factory(verifier)
                    val snapshots = FoodKitchenExecutionFrozenCatalogInputs.portions.map { id ->
                        FoodKitchenExecutionFrozenCatalogInputs.snapshot(id, activity, service.channel, twoStations)
                    }
                    val literalInput = snapshots.toList()
                    assertEquals(PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE,
                        source.prepareKitchenExecution(view, activity, service, snapshots))
                    assertEquals(0, view.reads)
                    assertEquals(0, verifier.calls)
                    assertEquals(literalInput, snapshots)
                    assertEquals(FoodKitchenExecutionFrozenCatalogInputs.portions, snapshots.map { it.portionId })
                    assertEquals(listOf(Quantity(FoodKitchenExecutionFrozenCatalogInputs.breadUnit, 1, 0),
                        Quantity(FoodKitchenExecutionFrozenCatalogInputs.meatUnit, 150, 0)),
                        snapshots.first().ingredients.map { it.requiredBaseQuantity })
                    // One or two route DTOs are not production execution ownership.
                    assertEquals(if (twoStations) 2 else 1, snapshots.first().routes.size)
                }
            }
        }
    }
}

/** Cannot provide current authority, a retained original or accepting empty storage. */
private class FoodKitchenExecutionFrozenCatalogUnreadView : CommandReadView {
    override val scope: BranchScope = BranchScope(DomainId("61000000-0000-4000-8000-000000000001"),
        DomainId("61000000-0000-4000-8000-000000000010"), DomainId("61000000-0000-4000-8000-000000000020"))
    var reads: Int = 0
    private fun unexpected(): Nothing { reads++; error("No native current or retained source is supplied") }
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = unexpected()
    override suspend fun claim(key: ClaimKey): DomainId? = unexpected()
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = unexpected()
    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage = unexpected()
    override suspend fun claimKeys(namespace: String, prefix: String, after: String?, limit: Int): ClaimKeyPage = unexpected()
}

private class FoodKitchenExecutionFrozenCatalogThrowingVerifier : CompanyPublicationSignatureVerifier {
    var calls: Int = 0
    override fun verify(company: CompanyScope, keyId: DomainId, canonicalDefinition: ByteArray, signatureHex: String): Boolean {
        calls++
        error("No trust anchor or accepting publication verification is supplied")
    }
}

private object FoodKitchenExecutionFrozenCatalogInputs {
    val portions: List<DomainId> = listOf(DomainId("61000000-0000-4000-8000-000000000110"),
        DomainId("61000000-0000-4000-8000-000000000111"))
    val breadUnit: DomainId = DomainId("61000000-0000-4000-8000-000000000084")
    val meatUnit: DomainId = DomainId("61000000-0000-4000-8000-000000000085")
    val services: List<LocalFoodServiceV1> = listOf(
        LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "61000000-0000-4000-8000-000000000092", "Synthetic seat 1"),
        LocalFoodServiceV1(LocalSalesChannel.TAKEAWAY, "SYNTHETIC-PICKUP-1"))

    // Unspecified native publication/version fields are deliberately UNBOUND public
    // DTO coordinates. They are NOT source evidence or replacements for retained IDs.
    private val unboundPublication: DomainId = DomainId("64000000-0000-4000-8000-000000000001")
    private val unboundRouteVersion: DomainId = DomainId("64000000-0000-4000-8000-000000000002")
    private const val UNVERIFIED_LITERAL: String = "UNVERIFIED_SYNTHETIC_PUBLIC_DTO_NOT_SOURCE_EVIDENCE"

    fun snapshot(id: DomainId, activity: LocalSalesActivity, channel: LocalSalesChannel,
        twoStations: Boolean): LocalFoodRecipeSnapshotV1 {
        val unit = DomainId("61000000-0000-4000-8000-000000000083")
        val item = DomainId("61000000-0000-4000-8000-000000000082")
        val bread = DomainId("61000000-0000-4000-8000-000000000087")
        val meat = DomainId("61000000-0000-4000-8000-000000000088")
        // Revision 1, description, component coordinates and DISPLAY are constructor
        // metadata only; no authentic native source, route mode or revision is claimed.
        val routes = listOf(LocalFoodStationRouteV1(DomainId("61000000-0000-4000-8000-000000000091"),
            DomainId("61000000-0000-4000-8000-000000000090"), Revision(1), FoodStationDeliveryMode.DISPLAY)) +
            if (twoStations) listOf(LocalFoodStationRouteV1(DomainId("61000000-0000-4000-8000-000000000098"),
                DomainId("61000000-0000-4000-8000-000000000097"), Revision(1), FoodStationDeliveryMode.DISPLAY)) else emptyList()
        return LocalFoodRecipeSnapshotV1(
            LocalFoodProfileV1.recipeSnapshotId(id, unboundPublication, unboundRouteVersion), id, unboundRouteVersion,
            activity, channel, DomainId("61000000-0000-4000-8000-000000000081"), Revision(7), unboundPublication,
            item, Revision(1), DomainId("61000000-0000-4000-8000-000000000086"), emptyList(),
            "Synthetic burger DTO", Quantity(unit, 1, 0), Quantity(unit, 1, 0), item,
            listOf(LocalFoodIngredientV1(bread, bread, Revision(1), Quantity(breadUnit, 1, 0), Quantity(breadUnit, 1, 0)),
                LocalFoodIngredientV1(meat, meat, Revision(1), Quantity(meatUnit, 150, 0), Quantity(meatUnit, 150, 0))),
            DomainId("61000000-0000-4000-8000-000000000093"), routes, UNVERIFIED_LITERAL,
            listOf(LocalFoodSourceReferenceV1("test.unverified-kitchen-source", unboundPublication, Revision(1),
                sha256(UNVERIFIED_LITERAL.encodeToByteArray()), DomainId("61000000-0000-4000-8000-000000000030"))))
    }
}
