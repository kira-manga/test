package com.ukkera.pos.sales.store.independent.foodatomicquotationbindingv1

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.pricing.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.FoodFulfillmentStatus
import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pure slices of unchanged FAQB-038/039/040/044, using the independently frozen
 * quotation values and food-quotation-compound-values-v1/price-inputs-v3.json.
 * These values establish no source authenticity, producer custody, native history,
 * execution authority, durable ingress, stock movement or collection result. */
class FoodAtomicStructuralBatchFrozenTest {
    private val inputs = FrozenFoodAtomicQuotationBindingInputs

    @Test
    fun faqb038_allQuotedRetainsOriginalPricingAndLiteralMembership() = retained(BatchShape.ALL_QUOTED)

    @Test
    fun faqb038_mixedRetainsOriginalPricingAndActualBodies() = retained(BatchShape.MIXED)

    @Test
    fun faqb038_allRoutedRetainsEveryOriginalSidecar() = retained(BatchShape.ALL_ROUTED)

    @Test
    fun faqb038_completeOrderedMembershipRefusesIndependentMutations() {
        val mutations = listOf(
            Mutation("portion-missing-P1") { it.copy(portions = it.portions.drop(1)) },
            Mutation("portion-missing-P2") { it.copy(portions = it.portions.dropLast(1)) },
            Mutation("portion-extra-P3") { it.copy(portions = it.portions + extraPortion()) },
            Mutation("portion-duplicate") { it.copy(portions = listOf(it.portions[0], it.portions[0])) },
            Mutation("portion-reordered") { it.copy(portions = it.portions.reversed()) },
            Mutation("portion-substituted-P3") { it.copy(portions = listOf(it.portions[0], extraPortion())) },
            Mutation("sidecar-missing-P1") { it.copy(sidecars = it.sidecars.drop(1)) },
            Mutation("sidecar-missing-P2") { it.copy(sidecars = it.sidecars.dropLast(1)) },
            Mutation("sidecar-extra-orphan-P3") { it.copy(sidecars = it.sidecars + extraSidecar()) },
            Mutation("sidecar-duplicate") { it.copy(sidecars = listOf(it.sidecars[0], it.sidecars[0])) },
            Mutation("sidecar-reordered") { it.copy(sidecars = it.sidecars.reversed()) },
            Mutation("sidecar-substituted-P3") { it.copy(sidecars = listOf(it.sidecars[0], extraSidecar())) },
            Mutation("price-line-missing-P1") { it.withPriceLines(it.lines.drop(1)) },
            Mutation("price-line-extra-P3") { it.withPriceLines(it.lines + it.lines[0].copy(portionId = u(303))) },
            Mutation("price-line-duplicate") { it.withPriceLines(listOf(it.lines[0], it.lines[0])) },
            Mutation("price-line-substituted-P3") { it.withPriceLines(listOf(it.lines[0], it.lines[1].copy(portionId = u(303)))) },
            Mutation("price-source-missing-P1") { it.withSources(it.sources.drop(1)) },
            Mutation("price-source-extra-P3") { it.withSources(it.sources + it.sources[0].copy(portionId = u(303))) },
            Mutation("price-source-duplicate") { it.withSources(listOf(it.sources[0], it.sources[0])) },
            Mutation("price-source-reordered") { it.withSources(it.sources.reversed()) },
            Mutation("price-source-substituted-P3") { it.withSources(listOf(it.sources[0], it.sources[1].copy(portionId = u(303)))) },
            Mutation("order-wrong-branch") { it.copy(order = it.order.copy(scope = wrongScope())) },
            Mutation("portion-wrong-branch") { it.withPortion(0, it.portions[0].copy(scope = wrongScope())) },
            Mutation("sidecar-wrong-branch") { it.withSidecar(0, it.sidecars[0].copy(scope = wrongScope())) },
            Mutation("price-batch-wrong-branch") { it.copy(priceBatch = it.priceBatch.copy(scope = wrongScope())) },
            Mutation("order-identity") { it.copy(order = it.order.copy(id = u(202))) },
            Mutation("portion-order") { it.withPortionOrder(0, u(202)) },
            Mutation("sidecar-order") { it.withSidecarBody(0) { body -> body.copy(orderId = u(202)) } },
            Mutation("price-batch-order") { it.withPriceBody { body -> body.copy(orderId = u(202)) } },
            Mutation("portion-batch") { it.withPortionBatch(0, u(202)) },
            Mutation("sidecar-batch") { it.withSidecarBody(0) { body -> body.copy(priceBatchId = u(202)) } },
            Mutation("price-batch-identity") { it.copy(priceBatch = it.priceBatch.copy(id = u(202))) },
            Mutation("batch-not-in-order") { it.withOrder { body -> body.copy(batchIds = listOf(u(202))) } },
            Mutation("member-not-in-order") { it.withOrder { body -> body.copy(portionIds = listOf(u(301), u(303))) } },
        )
        for (shape in BatchShape.entries) {
            val original = checkedFixture(shape)
            for (mutation in mutations) refuse("FAQB-038", original, mutation)
            // Fixture/order assertions run OUTSIDE refusal capture: a bad harness cannot pass.
            val reordered = original.withReorderedStoredPriceLines()
            refuse("FAQB-038", original, Mutation("price-line-reordered") { reordered })
        }
    }

    @Test
    fun faqb039_selectionAndOriginalPriceFactsMustAgree() {
        val mutations = listOf(
            Mutation("quantity") { it.withSelection(1) { value -> value.copy(quantity = inputs.quantity(20, 2)) } },
            Mutation("pricing-SKU") { it.withSources(listOf(it.sources[0], it.sources[1].copy(skuId = u(100)))) },
            Mutation("commercial-line") { it.withSelection(1) { value -> value.copy(lineId = u(302)) } },
            Mutation("price-revision") { it.withSources(listOf(it.sources[0], it.sources[1].copy(companyPriceRevision = Revision(2)))) },
            Mutation("effective-unit-price") {
                val changed = it.sources[1].copy(companyUnitPrice = UnitPrice.parse(Currency.EGP, "140.00"),
                    effectiveUnitPrice = UnitPrice.parse(Currency.EGP, "140.00"))
                it.withSources(listOf(it.sources[0], changed))
            },
            Mutation("stored-unit-price") { it.withPriceLines(listOf(it.lines[0], it.lines[1].copy(unitPrice = UnitPrice.parse(Currency.EGP, "140.00")))) },
            Mutation("recipe") { it.withSelection(1) { value -> value.copy(recipeId = u(111)) } },
            Mutation("output") { it.withSelection(1) { value -> value.copy(itemId = u(101)) } },
            Mutation("choice") { it.withSelection(1) { value -> value.copy(choiceId = u(110)) } },
            Mutation("modifier-set") { it.withSelection(1) { value -> value.copy(modifierIds = listOf(u(120))) } },
        )
        for (shape in BatchShape.entries) {
            val original = checkedFixture(shape)
            for (mutation in mutations) refuse("FAQB-039", original, mutation)
        }
    }

    @Test
    fun faqb039_literalOriginalFactsAndProvenanceCannotReplaceExpectedQuotedPair() {
        val original = checkedFixture(BatchShape.ALL_QUOTED)
        // Expected input is constructed directly from the frozen inputs, not from a join result.
        val expected = LocalFoodRecipeQuotationBatchV1(original.snapshot,
            listOf(inputs.quotation(u(301)), inputs.quotation(u(302))), frozenPriceSources())
        val mutations = listOf(
            Mutation("description") { it.withSidecarQuotation(0, inputs.quotation(description = "Burger changed")) },
            Mutation("literal-signed-bytes") { it.withSidecarQuotation(0, inputs.quotation(signedPublication = inputs.SIGNED_PUBLICATION + " ")) },
            Mutation("original-source-prefix-order") { it.withSidecarQuotation(0, inputs.quotation(sourceValues = inputs.originalSources().reversed())) },
            Mutation("original-component-order") { it.withSidecarQuotation(0, inputs.quotation(ingredientValues = inputs.ingredients().reversed())) },
            Mutation("company-publication") { it.withSources(listOf(it.sources[0].copy(companyPublicationId = u(151)), it.sources[1])) },
            Mutation("company-rule") { it.withSources(listOf(it.sources[0].copy(companyPriceRuleId = u(150)), it.sources[1])) },
            Mutation("company-provenance-digest") {
                it.withSources(listOf(it.sources[0].copy(companyPriceDigest = PayloadDigest("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")), it.sources[1]))
            },
        )
        for (mutation in mutations) refuse("FAQB-039", original, mutation, "requireFoodQuotationPair") {
            requireFoodQuotationPair(it.order, it.portions[0], it.sidecars[0], it.priceBatch, expected)
        }
    }

    @Test
    fun faqb039_routedOriginalFactsCannotDriftFromLiteralSidecar() {
        val changes: List<Pair<String, (LocalFoodRecipeSnapshotV1) -> LocalFoodRecipeSnapshotV1>> = listOf(
            "description" to { value: LocalFoodRecipeSnapshotV1 -> value.copy(description = "Burger changed") },
            "literal-signed-bytes" to { value: LocalFoodRecipeSnapshotV1 -> value.copy(signedPublication = value.signedPublication + " ") },
            "original-source-prefix-order" to { value: LocalFoodRecipeSnapshotV1 -> value.copy(sources = value.sources.take(2).reversed() + value.sources.drop(2)) },
            "original-component-order" to { value: LocalFoodRecipeSnapshotV1 -> value.copy(ingredients = value.ingredients.reversed()) },
            "original-component-quantity" to { value: LocalFoodRecipeSnapshotV1 ->
                val meat = value.ingredients[1].copy(quantityPerYield = inputs.quantity(22, 149), requiredBaseQuantity = inputs.quantity(22, 149))
                value.copy(ingredients = listOf(value.ingredients[0], meat))
            },
        )
        for (shape in listOf(BatchShape.MIXED, BatchShape.ALL_ROUTED)) {
            val original = checkedFixture(shape)
            for ((name, change) in changes) refuse("FAQB-039", original,
                Mutation(name) { it.withRoutedRecipe(0, change) })
        }
    }

    @Test
    fun faqb040_w16OriginalGenerationAndOperationMustMatch() {
        val mutations = listOf(
            Mutation("W16-sidecar-operation") { it.withSidecar(0, it.sidecars[0].copy(operationId = inputs.op(402))) },
            Mutation("W16-price-batch-operation") { it.copy(priceBatch = it.priceBatch.copy(operationId = inputs.op(402))) },
            Mutation("W16-sidecar-generation-zero") { it.withSidecar(0, it.sidecars[0].copy(revision = Revision(0))) },
            Mutation("W16-sidecar-generation-two") { it.withSidecar(0, it.sidecars[0].copy(revision = Revision(2))) },
            Mutation("W16-price-batch-generation-zero") { it.copy(priceBatch = it.priceBatch.copy(revision = Revision(0))) },
            Mutation("W16-price-batch-generation-two") { it.copy(priceBatch = it.priceBatch.copy(revision = Revision(2))) },
            Mutation("W16-sidecar-pair-identity") { it.withSidecar(0, it.sidecars[0].copy(id = inputs.quotation2Id)) },
        )
        for (shape in BatchShape.entries) {
            val original = checkedFixture(shape)
            for (mutation in mutations) refuse("FAQB-040", original, mutation)
        }
        val quoted = checkedFixture(BatchShape.ALL_QUOTED)
        refuse("FAQB-040", quoted, Mutation("W16-initial-quoted-operation") {
            it.withPortion(0, it.portions[0].copy(operationId = inputs.op(501)))
        })
    }

    @Test
    fun faqb040_w17AndW18RequireActualBindingRevisionShape() {
        for (shape in listOf(BatchShape.MIXED, BatchShape.ALL_ROUTED)) {
            val original = checkedFixture(shape)
            refuse("FAQB-040", original, Mutation("W17-routed-initial-revision") {
                val row = it.portions[0]
                val body = row.body as FoodBodyV1.Portion
                it.withPortion(0, row.copy(revision = Revision(1), body = body.copy(preparation = inputs.preparation(u(301), 0))))
            })
        }
        for (shape in BatchShape.entries) {
            val original = checkedFixture(shape)
            refuse("FAQB-040", original, Mutation("W18-storage-preparation-revision") {
                it.withPortion(0, it.portions[0].copy(revision = it.portions[0].revision.next()))
            })
        }
    }

    @Test
    fun faqb040_w19SharedLineMustHaveAnchorAndMatchingRawSelections() {
        val mutations = listOf(
            Mutation("W19-no-member-anchor") {
                it.withSelection(0) { value -> value.copy(lineId = u(303)) }
                    .withSelection(1) { value -> value.copy(lineId = u(303)) }
            },
            Mutation("W19-item") { it.withSelection(1) { value -> value.copy(itemId = u(101)) } },
            Mutation("W19-choice") { it.withSelection(1) { value -> value.copy(choiceId = u(110)) } },
            Mutation("W19-modifier-set") { it.withSelection(1) { value -> value.copy(modifierIds = listOf(u(120))) } },
            Mutation("W19-item-note") { it.withSelection(1) { value -> value.copy(itemNote = "No salt changed") } },
            Mutation("W19-seat-note") { it.withSelection(1) { value -> value.copy(seatNote = "Seat 2 changed") } },
        )
        for (shape in BatchShape.entries) {
            val original = checkedFixture(shape)
            for (mutation in mutations) refuse("FAQB-040", original, mutation)
        }
    }

    @Test
    fun faqb040_w20QuotedMembersCannotCoexistWithCollectionOrClose() {
        for (shape in listOf(BatchShape.ALL_QUOTED, BatchShape.MIXED)) {
            val original = checkedFixture(shape)
            refuse("FAQB-040", original, Mutation("W20-collection") {
                it.withOrder { value -> value.copy(financialCollectionId = u(502)) }
            })
            refuse("FAQB-040", original, Mutation("W20-closed") {
                it.withOrder { value -> value.copy(financialCollectionId = u(502), closedByOperationId = inputs.op(502)) }
            })
        }
    }

    @Test
    fun faqb040_w20IncompatibleQuotedPreparationRefusesAtActualConstructionOrHelper() {
        val original = checkedFixture(BatchShape.ALL_QUOTED)
        val mutations = listOf(
            Mutation("W20-quoted-queued-without-issue") { it.withQuotedPreparation { value -> value.copy(status = FoodPreparationStatus.QUEUED) } },
            Mutation("W20-quoted-issue-without-required-complement") { it.withQuotedPreparation { value -> value.copy(issueId = u(501)) } },
            Mutation("W20-quoted-served-without-required-state") { it.withQuotedPreparation { value -> value.copy(fulfillment = FoodFulfillmentStatus.SERVED) } },
        )
        for (mutation in mutations) refuse("FAQB-040", original, mutation)
    }

    @Test
    fun faqb044_exactQuotedAndRoutedAliasSetsFollowCompleteBatchJoin() {
        for (shape in BatchShape.entries) {
            val original = fixture(shape)
            val before = original.bytes()
            assertOriginal(original.join(), original)
            for (index in original.portions.indices) {
                val routed = original.portions[index].body is FoodBodyV1.Portion
                val actual = foodOriginalQuotationResources(original.portions[index], original.sidecars[index], original.priceBatch)
                assertEquals(expectedAliases(index, routed), actual, shape.name + "/P" + (index + 1))
                assertEquals(if (routed) 26 else 19, actual.size)
            }
            assertEquals(before, original.bytes(), shape.name + ": aliases mutated input")
        }
    }

    @Test
    fun faqb044_aliasAndOriginalBatchResultsRetainValuesAfterCallerCollectionChanges() {
        for (shape in BatchShape.entries) {
            val original = fixture(shape)
            val retained = original.join()
            val aliases = original.portions.indices.map { index ->
                foodOriginalQuotationResources(original.portions[index], original.sidecars[index], original.priceBatch)
            }
            val expected = original.portions.indices.map { index ->
                expectedAliases(index, original.portions[index].body is FoodBodyV1.Portion)
            }
            assertOriginal(retained, original)
            for (index in aliases.indices) {
                runCatching { (aliases[index] as MutableSet<RecoveryResourceKey>).clear() }
                assertEquals(expected[index], aliases[index], shape.name + ": exposed alias mutation")
            }
            for (portion in original.portions) {
                val body = portion.body as? FoodBodyV1.Portion ?: continue
                val recipeBefore = inputs.snapshotBytes(body.recipe)
                runCatching { (body.recipe.routes as MutableList<LocalFoodStationRouteV1>).clear() }
                runCatching { (body.recipe.sources as MutableList<LocalFoodSourceReferenceV1>).clear() }
                assertEquals(recipeBefore, inputs.snapshotBytes(body.recipe), shape.name + ": exposed routing mutation")
            }
            runCatching { (retained.quotations as MutableList<LocalFoodRecipeQuotationV1>).clear() }
            runCatching { (retained.priceSources as MutableList<LocalSalesPriceSourceV2>).clear() }
            val sourceInput = (original.priceBatch.body as FoodBodyV1.PriceBatch).sources as MutableList<LocalSalesPriceSourceV2>
            sourceInput.clear()
            (original.portions as MutableList<FoodEnvelopeV1>).clear()
            (original.sidecars as MutableList<FoodEnvelopeV1>).clear()
            assertEquals(listOf(inputs.quotation1Id, inputs.quotation2Id), retained.quotations.map { it.quotationId })
            assertEquals(frozenPriceSources(), retained.priceSources)
            assertEquals(original.canonicalQuote, StoredPricingSnapshotCodec.encode(retained.quote))
            for (index in aliases.indices) assertEquals(expected[index], aliases[index], shape.name + ": caller mutation")
        }
    }

    private enum class BatchShape { ALL_QUOTED, MIXED, ALL_ROUTED }

    private data class BatchFixture(
        val shape: BatchShape,
        val order: FoodEnvelopeV1,
        val portions: List<FoodEnvelopeV1>,
        val sidecars: List<FoodEnvelopeV1>,
        val priceBatch: FoodEnvelopeV1,
        val lines: List<ResolvedPriceLine>,
        val snapshot: PricingSnapshot,
        val canonicalQuote: String,
    ) {
        val sources: List<LocalSalesPriceSourceV2> get() = (priceBatch.body as FoodBodyV1.PriceBatch).sources
        fun join(): LocalFoodRecipeQuotationBatchV1 = foodOriginalQuotationBatch(order, portions, sidecars, priceBatch)
        fun bytes(): List<StoredBytes> = (listOf(order, priceBatch) + portions + sidecars).map {
            FrozenFoodAtomicQuotationBindingInputs.envelopeBytes(it)
        }
        fun withOrder(change: (FoodBodyV1.Order) -> FoodBodyV1.Order): BatchFixture =
            copy(order = order.copy(body = change(order.body as FoodBodyV1.Order)))
        fun withPriceBody(change: (FoodBodyV1.PriceBatch) -> FoodBodyV1.PriceBatch): BatchFixture =
            copy(priceBatch = priceBatch.copy(body = change(priceBatch.body as FoodBodyV1.PriceBatch)))
        fun withSources(values: List<LocalSalesPriceSourceV2>): BatchFixture = withPriceBody { it.copy(sources = values) }
        fun withPriceLines(values: List<ResolvedPriceLine>): BatchFixture {
            val changed = OperationalPricingEngine.calculate(PricingRequest(snapshot.snapshotId, Revision(0), Currency.EGP, values))
            return withPriceBody { it.copy(quote = StoredPricingSnapshotCodec.encode(changed)) }
        }
        /** The pricing factory sorts input lines; mutate the actual stored order instead. */
        fun withReorderedStoredPriceLines(): BatchFixture {
            val body = priceBatch.body as FoodBodyV1.PriceBatch
            val stored = Json.parseToJsonElement(body.quote).jsonObject
            val lines = stored.getValue("lines").jsonArray
            assertEquals(listOf("00000000-0000-0000-0000-000000000301",
                "00000000-0000-0000-0000-000000000302"),
                lines.map { it.jsonObject.getValue("portionId").jsonPrimitive.content })
            val reversed = JsonArray(lines.reversed())
            assertEquals(listOf("00000000-0000-0000-0000-000000000302",
                "00000000-0000-0000-0000-000000000301"),
                reversed.map { it.jsonObject.getValue("portionId").jsonPrimitive.content })
            val changed = JsonObject(stored + ("lines" to reversed))
            assertEquals(stored.filterKeys { it != "lines" }, changed.filterKeys { it != "lines" })
            return withPriceBody { it.copy(quote = changed.toString()) }
        }
        fun withPortion(index: Int, value: FoodEnvelopeV1): BatchFixture =
            copy(portions = portions.mapIndexed { at, original -> if (at == index) value else original })
        fun withSidecar(index: Int, value: FoodEnvelopeV1): BatchFixture =
            copy(sidecars = sidecars.mapIndexed { at, original -> if (at == index) value else original })
        fun withSidecarBody(index: Int, change: (FoodBodyV1.RecipeQuotation) -> FoodBodyV1.RecipeQuotation): BatchFixture =
            withSidecar(index, sidecars[index].copy(body = change(sidecars[index].body as FoodBodyV1.RecipeQuotation)))
        fun withSidecarQuotation(index: Int, value: LocalFoodRecipeQuotationV1): BatchFixture =
            withSidecarBody(index) { it.copy(quotation = value) }
        fun withSelection(index: Int, change: (LocalFoodPortionSelectionV1) -> LocalFoodPortionSelectionV1): BatchFixture {
            val row = portions[index]
            return withPortion(index, row.copy(body = when (val body = row.body) {
                is FoodBodyV1.QuotedPortion -> body.copy(selection = change(body.selection))
                is FoodBodyV1.Portion -> body.copy(selection = change(body.selection))
                else -> error("portion fixture required")
            }))
        }
        fun withPortionOrder(index: Int, id: DomainId): BatchFixture {
            val row = portions[index]
            return withPortion(index, row.copy(body = when (val body = row.body) {
                is FoodBodyV1.QuotedPortion -> body.copy(orderId = id)
                is FoodBodyV1.Portion -> body.copy(orderId = id)
                else -> error("portion fixture required")
            }))
        }
        fun withPortionBatch(index: Int, id: DomainId): BatchFixture {
            val row = portions[index]
            return withPortion(index, row.copy(body = when (val body = row.body) {
                is FoodBodyV1.QuotedPortion -> body.copy(priceBatchId = id)
                is FoodBodyV1.Portion -> body.copy(priceBatchId = id)
                else -> error("portion fixture required")
            }))
        }
        fun withRoutedRecipe(index: Int, change: (LocalFoodRecipeSnapshotV1) -> LocalFoodRecipeSnapshotV1): BatchFixture {
            val row = portions[index]
            val body = row.body as FoodBodyV1.Portion
            return withPortion(index, row.copy(body = body.copy(recipe = change(body.recipe))))
        }
        fun withQuotedPreparation(change: (FoodPreparationState) -> FoodPreparationState): BatchFixture {
            val row = portions[0]
            val body = row.body as FoodBodyV1.QuotedPortion
            return withPortion(0, row.copy(body = body.copy(preparation = change(body.preparation))))
        }
    }

    private data class Mutation(val name: String, val change: (BatchFixture) -> BatchFixture)

    private fun checkedFixture(shape: BatchShape): BatchFixture {
        val original = fixture(shape)
        val before = original.bytes()
        assertOriginal(original.join(), original)
        assertEquals(before, original.bytes(), shape.name + ": baseline join mutated input")
        return original
    }

    private fun retained(shape: BatchShape) {
        val original = fixture(shape)
        val before = original.bytes()
        assertOriginal(original.join(), original)
        assertEquals(before, original.bytes(), shape.name + ": membership mutated input")
        assertEquals(if (shape == BatchShape.ALL_QUOTED) listOf(false, false)
            else if (shape == BatchShape.MIXED) listOf(true, false) else listOf(true, true),
            original.portions.map { it.body is FoodBodyV1.Portion })
    }

    private fun assertOriginal(actual: LocalFoodRecipeQuotationBatchV1, original: BatchFixture) {
        val quote = actual.quote
        assertEquals(u(201), quote.snapshotId)
        assertEquals(Revision(0), quote.revision)
        assertEquals(Currency.EGP, quote.currency)
        assertEquals("linear-protected-components-half-up-lr/v2", quote.profile)
        assertEquals("linear-protected-components-half-up-lr/v2", original.snapshot.profile)
        assertEquals(PromotionResolution.NONE, quote.promotionResolution)
        assertEquals(TaxPostingPolicy.OPERATIONAL_COMPONENT_ALLOCATION, quote.taxPostingPolicy)
        assertEquals(Money(Currency.EGP, 22_000), quote.beforeDiscountPayable)
        assertEquals(Money(Currency.EGP, 22_000), quote.payable)
        assertEquals(ExactRational.ratio(220, 1), quote.exactBeforeDiscountPayable)
        assertEquals(ExactRational.ratio(220, 1), quote.exactPayable)
        assertTrue(quote.charges.isEmpty())
        assertTrue(quote.discounts.isEmpty())
        assertEquals(listOf(u(301), u(302)), quote.lines.map { it.portionId })
        assertEquals(listOf(u(301), u(301)), quote.lines.map { it.lineId })
        for (line in quote.lines) {
            assertEquals(Revision(1), line.priceRevision)
            assertEquals(inputs.quantity(20, 1), line.quantity)
            assertEquals(UnitPrice.parse(Currency.EGP, "110.00"), line.unitPrice)
            assertEquals(TaxInclusion.EXCLUSIVE, line.inclusion)
            assertEquals(TaxSnapshot(u(6190), Revision(1), "SYNTHETIC-STRUCTURAL-ZERO", PercentageRate.parse("0")), line.tax)
            assertEquals(ExactRational.ratio(110, 1), line.exactOriginalItemNet)
            assertEquals(ExactRational.ratio(110, 1), line.exactItemNet)
            assertEquals(Money(Currency.EGP, 11_000), line.payable)
            val components = quote.components.filter { it.portionId == line.portionId }
            assertEquals(11_000L, components.sumOf { it.postedBeforeDiscounts.minor })
            assertEquals(0L, components.filter { it.kind == PriceComponentKind.ITEM_TAX }.sumOf { it.posted.minor })
        }
        assertEquals(original.canonicalQuote, StoredPricingSnapshotCodec.encode(quote))
        assertEquals(frozenPriceSources(), actual.priceSources)
        assertEquals(listOf(inputs.quotation1Id, inputs.quotation2Id), actual.quotations.map { it.quotationId })
        for ((index, quotation) in actual.quotations.withIndex()) {
            assertEquals(inputs.quotationBytes(inputs.quotation(u(301 + index))), inputs.quotationBytes(quotation))
            assertEquals(inputs.SIGNED_PUBLICATION, quotation.signedPublication)
            assertEquals(inputs.originalSources(), quotation.sources)
            assertEquals(inputs.ingredients(), quotation.ingredients)
        }
    }

    /** Refusal stage is an observation, not an oracle. A constructor refusal leaves
     * the helper unexecuted; no stage or exception type qualifies native verification. */
    private fun refuse(caseId: String, original: BatchFixture, mutation: Mutation,
        boundaryName: String = "foodOriginalQuotationBatch", boundary: (BatchFixture) -> Unit = { it.join(); Unit }) {
        val before = original.bytes()
        val candidate = runCatching { mutation.change(original) }
        if (candidate.isFailure) {
            assertEquals(before, original.bytes(), caseId + "/" + mutation.name + ": construction mutated input")
            println(caseId + "/" + mutation.name + "/" + original.shape.name + " REFUSED CONSTRUCTION; helper UNEXECUTED")
            return
        }
        val value = candidate.getOrThrow()
        val candidateBefore = value.bytes()
        assertNotNull(runCatching { boundary(value) }.exceptionOrNull(), caseId + "/" + mutation.name + ": accepted")
        assertEquals(candidateBefore, value.bytes(), caseId + "/" + mutation.name + ": helper mutated candidate")
        assertEquals(before, original.bytes(), caseId + "/" + mutation.name + ": helper mutated original")
        println(caseId + "/" + mutation.name + "/" + original.shape.name + " REFUSED " + boundaryName)
    }

    private fun fixture(shape: BatchShape): BatchFixture {
        val lines = listOf(u(301), u(302)).map { portion ->
            ResolvedPriceLine(u(301), portion, Revision(1), inputs.quantity(20, 1), UnitPrice.parse(Currency.EGP, "110.00"),
                TaxInclusion.EXCLUSIVE, TaxSnapshot(u(6190), Revision(1), "SYNTHETIC-STRUCTURAL-ZERO", PercentageRate.parse("0")))
        }
        val snapshot = OperationalPricingEngine.calculate(PricingRequest(u(201), Revision(0), Currency.EGP, lines))
        val canonicalQuote = StoredPricingSnapshotCodec.encode(snapshot)
        val order = FoodEnvelopeV1(inputs.scope, "sales.food.order", u(200), Revision(1), inputs.op(401),
            FoodBodyV1.Order(u(10), u(10), LocalSalesActivity.RESTAURANT,
                LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "Table 2", "Seat 2"),
                listOf(u(301), u(302)), listOf(u(201)), Money(Currency.EGP, 22_000)))
        val portions = when (shape) {
            BatchShape.ALL_QUOTED -> listOf(inputs.quotedEnvelope(u(301)), inputs.quotedEnvelope(u(302)))
            BatchShape.MIXED -> listOf(inputs.routedEnvelope(u(301)), inputs.quotedEnvelope(u(302)))
            BatchShape.ALL_ROUTED -> listOf(inputs.routedEnvelope(u(301)), inputs.routedEnvelope(u(302)))
        }.toMutableList()
        val sidecars = listOf(inputs.sidecar(u(301)), inputs.sidecar(u(302))).toMutableList()
        val batch = FoodEnvelopeV1(inputs.scope, "sales.food.price-batch", u(201), Revision(1), inputs.op(401),
            FoodBodyV1.PriceBatch(u(200), canonicalQuote, frozenPriceSources().toMutableList()))
        return BatchFixture(shape, order, portions, sidecars, batch, lines, snapshot, canonicalQuote)
    }

    private fun frozenPriceSources(): List<LocalSalesPriceSourceV2> = listOf(u(301), u(302)).map { portion ->
        LocalSalesPriceSourceV2(inputs.scope, u(301), portion, u(101), u(150), u(151), Revision(1),
            PayloadDigest("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"),
            UnitPrice.parse(Currency.EGP, "110.00"), UnitPrice.parse(Currency.EGP, "110.00"),
            null, null, null, null, null, null)
    }

    /** P3's fixed quotation identity from the atomic freeze is used only as an extra/substituted value. */
    private fun extraSidecar(): FoodEnvelopeV1 {
        val quotation = inputs.quotation(u(303), DomainId("bc025a12-8d5f-0667-95cc-b549ca765b66"))
        return FoodEnvelopeV1(inputs.scope, "sales.food.recipe-quotation", quotation.quotationId, Revision(1), inputs.op(401),
            FoodBodyV1.RecipeQuotation(u(200), u(201), quotation))
    }

    private fun extraPortion(): FoodEnvelopeV1 {
        val quotation = (extraSidecar().body as FoodBodyV1.RecipeQuotation).quotation
        return FoodEnvelopeV1(inputs.scope, "sales.food.portion", u(303), Revision(1), inputs.op(401),
            FoodBodyV1.QuotedPortion(u(200), inputs.selection(u(303)), u(201), quotation.quotationId, inputs.preparation(u(303))))
    }

    /** Literal sets in inputs.json#independentExpected, independent of the alias implementation. */
    private fun expectedAliases(index: Int, routed: Boolean): Set<RecoveryResourceKey> {
        val custody = if (index == 0) listOf(u(200), u(301), inputs.quotation1Id, u(201))
            else listOf(u(200), u(302), inputs.quotation2Id, u(201))
        val routedCustody = if (index == 0) listOf(inputs.snapshot1Id, u(130)) else listOf(inputs.snapshot2Id, u(130))
        val authorization = listOf(10, 100, 101, 102, 103, 110, 111, 150, 151, 160, 170).map(::u)
        val routedAuthorization = listOf(130, 132, 135, 140).map(::u)
        val stock = listOf(100, 101, 102, 103).map(::u)
        return ((custody + if (routed) routedCustody else emptyList()).map {
            RecoveryResourceKey(RecoveryResourceKind.ORDER_CUSTODY, it)
        } + (authorization + if (routed) routedAuthorization else emptyList()).map {
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it)
        } + (stock + if (routed) listOf(u(140)) else emptyList()).map {
            RecoveryResourceKey(RecoveryResourceKind.STOCK, it)
        }).toSet()
    }

    private fun wrongScope(): BranchScope = inputs.scope.copy(branchId = u(4))
    private fun u(number: Int): DomainId = inputs.u(number)
}
