package com.ukkera.pos.sales.store.independent.foodoriginalquotationmembershipv1

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.ukkera.pos.sales.store.independent.foodoriginalquotationmembershipv1.FrozenFoodOriginalQuotationMembershipInputs as F

/** Actual structural boundaries for FQM-001–007; FQM-008 has no genuine integration fixture. */
class FoodOriginalQuotationMembershipFrozenTest {
    @Test
    fun fqm001_allQuotedMixedAndAllRoutedRetainOriginalsAndKeepTheStrictWrapper() {
        for (bodies in FrozenMembershipBodies.entries) {
            val input = F.input(bodies)
            succeeds("FQM-001 $bodies original", input)
            if (bodies == FrozenMembershipBodies.QUOTED) {
                succeeds("FQM-001 all-quoted strict", input, Boundary.STRICT)
            } else {
                refuses("FQM-001 $bodies strict", input, Boundary.STRICT)
            }
        }
        val allRouted = F.input(FrozenMembershipBodies.ROUTED)
        succeeds("FQM-001 all-routed collection", allRouted.orderBody {
            it.copy(financialCollectionId = F.u(117))
        })
        succeeds("FQM-001 all-routed closed", allRouted.orderBody {
            it.copy(financialCollectionId = F.u(117), closedByOperationId = F.op(120))
        })
    }

    @Test
    fun fqm002_anyQuotedMemberRefusesCollectionHandoffAndValidClosedOrder() {
        for (bodies in listOf(FrozenMembershipBodies.QUOTED, FrozenMembershipBodies.MIXED)) {
            val input = F.input(bodies)
            val variants = listOf(
                "collection" to input.orderBody { it.copy(financialCollectionId = F.u(117)) },
                "handoff" to input.orderBody { it.copy(handoff = FoodHandoffV1(F.u(118), F.u(112), F.u(113),
                    UtcInstant.parseCanonical("2026-10-31T12:00:00Z"))) },
                "valid-closed-order" to input.orderBody {
                    it.copy(financialCollectionId = F.u(117), closedByOperationId = F.op(120))
                })
            for ((name, variant) in variants) for (boundary in Boundary.entries) {
                refuses("FQM-002 $bodies $name $boundary", variant, boundary)
            }
        }
    }

    @Test
    fun fqm003_exactOrderedMembershipSubstitutionAndCommercialAnchorRefuse() {
        val input = F.input()
        val variants = listOf(
            "order-batch-membership" to input.orderBody { it.copy(batchIds = listOf(F.u(121))) },
            "order-portion-membership" to input.orderBody { it.copy(portionIds = listOf(F.u(4), F.u(122))) },
            "portion-missing" to input.copy(portions = listOf(input.portions[0])),
            "portion-duplicate" to input.copy(portions = listOf(input.portions[0], input.portions[0])),
            "portion-extra" to input.copy(portions = listOf(input.portions[0], input.portions[1], input.portions[0])),
            "sidecar-missing" to input.copy(sidecars = listOf(input.sidecars[0])),
            "sidecar-duplicate" to input.copy(sidecars = listOf(input.sidecars[0], input.sidecars[0])),
            "sidecar-extra" to input.copy(sidecars = listOf(input.sidecars[0], input.sidecars[1], input.sidecars[0])),
            "portion-order" to input.copy(portions = input.portions.reversed()),
            "sidecar-order" to input.copy(sidecars = input.sidecars.reversed()),
            "both-orders" to input.copy(portions = input.portions.reversed(), sidecars = input.sidecars.reversed()))
        variants.forEach { (name, variant) -> refuses("FQM-003 $name", variant) }

        val qx = F.quotation(F.u(90), F.quotationXId)
        val rx = F.routedPortion(F.u(90), F.candidate(F.u(90), F.snapshotAXId))
        val substitution = input.copy(portions = listOf(input.portions[0], rx),
            sidecars = listOf(input.sidecars[0], F.sidecar(qx)))
        refuses("FQM-003 coherent substituted member", substitution)

        val unanchored = input.portion(0) {
            val body = it.body as FoodBodyV1.QuotedPortion
            it.copy(body = body.copy(selection = body.selection.copy(lineId = F.u(120))))
        }.portion(1) {
            val body = it.body as FoodBodyV1.Portion
            it.copy(body = body.copy(selection = body.selection.copy(lineId = F.u(120))))
        }.copy(priceBatch = F.priceBatch(lineId = F.u(120)))
        refuses("FQM-003 unanchored shared commercial line", unanchored)
    }

    @Test
    fun fqm003_eachScopeDomainOnEveryAssignedEnvelopeRefuses() {
        val input = F.input()
        val scopes = listOf(
            "organization" to F.scope.copy(organizationId = F.u(90)),
            "company" to F.scope.copy(companyId = F.u(90)),
            "branch" to F.scope.copy(branchId = F.u(90)))
        for ((domain, scope) in scopes) {
            val variants = listOf(
                "order" to input.copy(order = input.order.copy(scope = scope)),
                "price-batch" to input.copy(priceBatch = input.priceBatch.copy(scope = scope)),
                "sidecar-Q1" to input.sidecar(0) { it.copy(scope = scope) },
                "quoted-QP1" to input.portion(0) { it.copy(scope = scope) },
                "routed-RP2" to input.portion(1) { it.copy(scope = scope) })
            variants.forEach { (name, variant) -> refuses("FQM-003 $name $domain scope", variant) }
        }
    }

    @Test
    fun fqm004_originalOperationsGenerationsAndExactSidecarLinksRefuse() {
        val input = F.input()
        val variants = listOf(
            "Q1-storage-generation" to input.sidecar(0) { it.copy(revision = Revision(2)) },
            "Q1-original-operation" to input.sidecar(0) { it.copy(operationId = F.op(121)) },
            "B-original-operation" to input.copy(priceBatch = input.priceBatch.copy(operationId = F.op(121))),
            "QP1-initial-operation" to input.portion(0) { it.copy(operationId = F.op(121)) },
            "QP1-quotation-link" to input.portion(0) {
                it.copy(body = (it.body as FoodBodyV1.QuotedPortion).copy(quotationId = F.quotation2Id))
            },
            "Q1-envelope-identity" to input.sidecar(0) { it.copy(id = F.u(90)) },
            "QP1-order-link" to input.portion(0) {
                it.copy(body = (it.body as FoodBodyV1.QuotedPortion).copy(orderId = F.u(121)))
            },
            "RP2-order-link" to input.portion(1) {
                it.copy(body = (it.body as FoodBodyV1.Portion).copy(orderId = F.u(121)))
            },
            "Q1-order-link" to input.sidecar(0) {
                it.copy(body = (it.body as FoodBodyV1.RecipeQuotation).copy(orderId = F.u(121)))
            },
            "B-order-link" to input.priceBody { it.copy(orderId = F.u(121)) },
            "QP1-batch-link" to input.portion(0) {
                it.copy(body = (it.body as FoodBodyV1.QuotedPortion).copy(priceBatchId = F.u(122)))
            },
            "RP2-batch-link" to input.portion(1) {
                it.copy(body = (it.body as FoodBodyV1.Portion).copy(priceBatchId = F.u(122)))
            },
            "Q1-batch-link" to input.sidecar(0) {
                it.copy(body = (it.body as FoodBodyV1.RecipeQuotation).copy(priceBatchId = F.u(122)))
            },
            "RP2-no-binding-generation" to input.portion(1) {
                val body = it.body as FoodBodyV1.Portion
                it.copy(revision = Revision(1), body = body.copy(preparation = F.preparation(F.u(104), 0)))
            },
            "RP2-storage-preparation-generation" to input.portion(1) { it.copy(revision = Revision(3)) },
            "QP1-storage-preparation-generation" to input.portion(0) { it.copy(revision = Revision(2)) },
            "B-storage-generation" to input.copy(priceBatch = input.priceBatch.copy(revision = Revision(2))),
            "B-snapshot-generation" to input.copy(priceBatch = F.priceBatch(snapshotRevision = Revision(1))))
        variants.forEach { (name, variant) -> refuses("FQM-004 $name", variant) }
    }

    @Test
    fun fqm005_selectionFactsAndSharedLineNotesRefuse() {
        val input = F.input()
        val changes: List<Pair<String, (LocalFoodPortionSelectionV1) -> LocalFoodPortionSelectionV1>> = listOf(
            "item" to { it.copy(itemId = F.u(90)) },
            "recipe" to { it.copy(recipeId = F.u(90)) },
            "recipe-revision" to { it.copy(expectedRecipeRevision = Revision(12)) },
            "choice" to { it.copy(choiceId = F.u(90)) },
            "modifier-missing" to { it.copy(modifierIds = listOf(F.u(11))) },
            "modifier-substitution" to { it.copy(modifierIds = listOf(F.u(11), F.u(90))) },
            "quantity" to { it.copy(quantity = F.q(13, 200, 2)) },
            "commercial-line" to { it.copy(lineId = F.u(104)) })
        for ((name, change) in changes) {
            val variant = input.portion(0) {
                val body = it.body as FoodBodyV1.QuotedPortion
                it.copy(body = body.copy(selection = change(body.selection)))
            }
            refuses("FQM-005 QP1 $name", variant)
        }
        val itemNote = input.portion(1) {
            val body = it.body as FoodBodyV1.Portion
            it.copy(body = body.copy(selection = body.selection.copy(itemNote = "No added salt!")))
        }
        refuses("FQM-005 S2 item note", itemNote)
        val seatNote = input.portion(1) {
            val body = it.body as FoodBodyV1.Portion
            it.copy(body = body.copy(selection = body.selection.copy(seatNote = "Seat 8")))
        }
        refuses("FQM-005 S2 seat note", seatNote)
    }

    @Test
    fun fqm005_exactOrderedPriceProvenanceAndOriginalPriceLinesRefuse() {
        val input = F.input()
        val originalSources = (input.priceBatch.body as FoodBodyV1.PriceBatch).sources
        val sourceChanges: List<Pair<String, (LocalSalesPriceSourceV2) -> LocalSalesPriceSourceV2>> = listOf(
            "scope" to { it.copy(scope = it.scope.copy(branchId = F.u(90))) },
            "portion" to { it.copy(portionId = F.u(104)) },
            "line" to { it.copy(lineId = F.u(104)) },
            "sku" to { it.copy(skuId = F.u(90)) },
            "company-price-revision" to { it.copy(companyPriceRevision = Revision(6)) },
            "company-and-effective-unit-price" to {
                it.copy(companyUnitPrice = UnitPrice.parse(Currency.EGP, "111.00"),
                    effectiveUnitPrice = UnitPrice.parse(Currency.EGP, "111.00"))
            })
        val sequences = listOf(
            "source-order" to originalSources.reversed(),
            "source-missing" to listOf(originalSources[0]),
            "source-duplicate" to listOf(originalSources[0], originalSources[0]))
        for ((name, sources) in sequences) refuses("FQM-005 $name", input.priceBody { it.copy(sources = sources) })
        for ((name, change) in sourceChanges) {
            val sources = listOf(change(originalSources[0]), originalSources[1])
            refuses("FQM-005 PS1 $name", input.priceBody { it.copy(sources = sources) })
        }

        val quantity = input.copy(priceBatch = F.priceBatch(firstQuantity = F.q(13, 200, 2),
            amounts = FrozenMembershipAmounts.FIRST_QUANTITY_200))
        refuses("FQM-005 canonical first price-line quantity", quantity)
        val revision = input.copy(priceBatch = F.priceBatch(firstPriceRevision = Revision(6)))
        refuses("FQM-005 canonical first price-line revision", revision)
        val unitPrice = input.copy(priceBatch = F.priceBatch(firstUnitPrice = UnitPrice.parse(Currency.EGP, "111.00"),
            amounts = FrozenMembershipAmounts.FIRST_PRICE_111))
        refuses("FQM-005 canonical first price-line unit price", unitPrice)
    }

    @Test
    fun fqm006_lawfulA0B0AndA1RoutedFactsRetainTheOriginalBatch() {
        val candidates = listOf(
            "A0" to F.candidate(),
            "B0" to F.candidate(snapshotId = F.snapshotB0Id, routeVersionId = F.u(29), warehouseId = F.u(31),
                routes = F.routesB, sources = F.sourcesB0),
            "A1" to F.candidate(sources = F.sourcesA1))
        for ((name, candidate) in candidates) {
            val input = F.input(FrozenMembershipBodies.ROUTED).portion(0) {
                it.copy(body = (it.body as FoodBodyV1.Portion).copy(recipe = candidate))
            }
            succeeds("FQM-006 lawful $name", input)
        }
    }

    @Test
    fun fqm006_constructibleInheritedRoutedDeltasRefuseAtTheActualMembershipHelper() {
        val deltas = frozenFoodMembershipRoutedDeltas().filterNot { it.constructorOnly }
        assertEquals(49, deltas.size, "FQM-006 constructible inherited variants")
        for (delta in deltas) {
            val input = F.input(FrozenMembershipBodies.ROUTED)
            val original = (input.portions[0].body as FoodBodyV1.Portion).recipe
            // Construction is outside the helper-failure capture: an unexpected constructor failure fails the method.
            val candidate = delta.change(original)
            val variant = input.portion(0) { it.copy(body = (it.body as FoodBodyV1.Portion).copy(recipe = candidate)) }
            refuses("FQM-006 ${delta.id}", variant)
        }
    }

    @Test
    fun fqm006_malformedInheritedRoutedDeltasRefuseOnlyAtConstructionAndRemainMembershipUnbound() {
        val deltas = frozenFoodMembershipRoutedDeltas().filter { it.constructorOnly }
        assertEquals(9, deltas.size, "FQM-006 constructor-only inherited variants")
        for (delta in deltas) {
            val input = F.input(FrozenMembershipBodies.ROUTED)
            val before = observe(input)
            val original = (input.portions[0].body as FoodBodyV1.Portion).recipe
            val construction = runCatching { delta.change(original) }
            assertEquals(before, observe(input), "${delta.id}: rejected construction preserves the original fixture")
            assertTrue(construction.isFailure, "${delta.id}: public construction must refuse; membership remains UNBOUND")
            // Neither membership helper is called for these nine values. A constructor refusal is not a helper pass.
            println("FQM-006 ${delta.id}: constructor-rejection; membership UNBOUND")
        }
    }

    private fun succeeds(label: String, input: FrozenMembershipInput, boundary: Boundary = Boundary.ORIGINAL) {
        val before = observe(input)
        val invocation = runCatching { invoke(boundary, input) }
        assertEquals(before, observe(input), "$label FQM-007 every input identity/body/byte and ordered list is unchanged")
        assertTrue(invocation.isSuccess, "$label expected actual helper success")
        val result = invocation.getOrThrow()
        val batch = input.priceBatch.body as FoodBodyV1.PriceBatch
        assertEquals(batch.quote, StoredPricingSnapshotCodec.encode(result.quote), "$label exact original canonical T")
        F.assertFrozenAmounts(result.quote, FrozenMembershipAmounts.ORIGINAL, label)
        assertEquals(listOf(F.u(4), F.u(104)), result.quotations.map { it.portionId }, "$label original quotation order")
        val originals = input.sidecars.map { (it.body as FoodBodyV1.RecipeQuotation).quotation }
        assertEquals(originals.map(::quotationBytes), result.quotations.map(::quotationBytes), "$label every immutable quotation field")
        assertEquals(batch.sources, result.priceSources, "$label every ordered original price-provenance field")
        println("$label: $boundary membership-success; FQM-007 inputs unchanged")
    }

    private fun refuses(label: String, input: FrozenMembershipInput, boundary: Boundary = Boundary.ORIGINAL) {
        val before = observe(input)
        // Only the actual boundary call is captured. Setup and preservation assertions cannot be counted as refusal.
        val invocation = runCatching { invoke(boundary, input) }
        assertEquals(before, observe(input), "$label FQM-007 every input identity/body/byte and ordered list is unchanged")
        assertTrue(invocation.isFailure, "$label must propagate failure, never return a null or partial batch")
        println("$label: $boundary membership-rejection; FQM-007 inputs unchanged")
    }

    private fun invoke(boundary: Boundary, input: FrozenMembershipInput): LocalFoodRecipeQuotationBatchV1 = when (boundary) {
        Boundary.ORIGINAL -> foodOriginalQuotationBatch(input.order, input.portions, input.sidecars, input.priceBatch)
        Boundary.STRICT -> foodQuotationBatch(input.order, input.portions, input.sidecars, input.priceBatch)
    }

    private fun quotationBytes(quotation: LocalFoodRecipeQuotationV1): StoredBytes =
        localSalesEncode(quotation, LocalFoodRecipeQuotationV1.serializer(), FoodQuotationStorageV1.MAX_BYTES)

    private fun observe(input: FrozenMembershipInput): MembershipObservation = MembershipObservation(
        observeEnvelope(input.order), input.portions.map(::observeEnvelope), input.sidecars.map(::observeEnvelope),
        observeEnvelope(input.priceBatch))

    private fun observeEnvelope(value: FoodEnvelopeV1): EnvelopeObservation {
        val bodyKind = when (value.body) {
            is FoodBodyV1.Order -> "ORDER"
            is FoodBodyV1.QuotedPortion -> "QUOTED_PORTION"
            is FoodBodyV1.Portion -> "PORTION"
            is FoodBodyV1.RecipeQuotation -> "RECIPE_QUOTATION"
            is FoodBodyV1.PriceBatch -> "PRICE_BATCH"
            is FoodBodyV1.OwnerIndex -> "OWNER_INDEX"
            is FoodBodyV1.StationIndex -> "STATION_INDEX"
        }
        // Raw canonical serialization preserves every nested selection/preparation/quotation/route/source field.
        // It does not validate a deliberately wrong relation before the actual helper has been called.
        val bytes = localSalesEncode(value, FoodEnvelopeV1.serializer(), FoodQuotationStorageV1.MAX_BYTES)
        return EnvelopeObservation(value.scope, value.kind, value.id, value.revision, value.operationId, bodyKind, bytes)
    }

    private enum class Boundary { ORIGINAL, STRICT }
    private data class EnvelopeObservation(val scope: BranchScope, val kind: String, val id: DomainId,
        val revision: Revision, val operationId: OperationId, val bodyKind: String, val canonicalBytes: StoredBytes)
    private data class MembershipObservation(val order: EnvelopeObservation, val portions: List<EnvelopeObservation>,
        val sidecars: List<EnvelopeObservation>, val priceBatch: EnvelopeObservation)
}
