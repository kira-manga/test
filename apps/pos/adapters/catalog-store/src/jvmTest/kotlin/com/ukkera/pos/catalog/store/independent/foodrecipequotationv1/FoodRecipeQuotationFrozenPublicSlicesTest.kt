package com.ukkera.pos.catalog.store.independent.foodrecipequotationv1

import com.ukkera.pos.sales.store.LocalFoodQuotationProfileV1
import com.ukkera.pos.sales.store.LocalFoodRecipeQuotationV1
import com.ukkera.pos.sales.store.LocalFoodSourceReferenceV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Bounded PUBLIC slices of food-recipe-quotation-v1, frozen content
 * 787d3fd17389cbf6beec2192fe0fc5b9ce19397a7bb526c7eb5bd6403c6da2a9.
 * The shared constructor's route field uses key-binding-v1, content
 * df1f4dc642cae470c56c2fee6e16de5bdde76fb5638f60d50cdde2b83e6c96be.
 * Its corrected UUID assertions are tested separately; parent literal assertions
 * remain UNBOUND. Constructor fields and "{}" prove shape only, not a signature,
 * installed source, unit registry, authority, pricing, business effects or history.
 * Genuine branch/legacy/source/Master/native/RPC/integration checks remain UNBOUND.
 */
class FoodRecipeQuotationFrozenPublicSlicesTest {
    private val f = FrozenFoodRecipeQuotationPublicInputs
    private val json = Json { encodeDefaults = true }

    @Test
    fun frq001FourFrozenIdentityVectorsAreExactRepeatedAndDistinct() {
        val vectors = listOf(
            IdentityVector(f.pa, f.u1,
                "ukkera.local-food/v1/recipe-quotation/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222221",
                "e898beb991ad94ba9aad9c1e80acef5cf99edbf6c86bdb8dc0a85ca737769ebb",
                "e898beb9-91ad-94ba-9aad-9c1e80acef5c"),
            IdentityVector(f.pb, f.u1,
                "ukkera.local-food/v1/recipe-quotation/11111111-1111-1111-1111-111111111112/22222222-2222-2222-2222-222222222221",
                "e5e8a4ce2bfb2da58b1550807a25f088c1b65f87ba9ec259006dc92de45e3da0",
                "e5e8a4ce-2bfb-2da5-8b15-50807a25f088"),
            IdentityVector(f.pa, f.u2,
                "ukkera.local-food/v1/recipe-quotation/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222",
                "5f0a9f1131f7d343fb34a2a91350735b797bff868ba2d85c3a8c7a4ee897cc9e",
                "5f0a9f11-31f7-d343-fb34-a2a91350735b"),
            IdentityVector(f.pb, f.u2,
                "ukkera.local-food/v1/recipe-quotation/11111111-1111-1111-1111-111111111112/22222222-2222-2222-2222-222222222222",
                "31ccee09044aa85181dc01102fe157e4ab98046a92dbcd54dfc531ea1f439fa9",
                "31ccee09-044a-a851-81dc-01102fe157e4"),
        )
        val observed = vectors.map { vector ->
            val digest = MessageDigest.getInstance("SHA-256").digest(vector.inputUtf8.encodeToByteArray())
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            assertEquals(vector.fullSha256, digest, "Frozen reference input/digest")
            val first = LocalFoodQuotationProfileV1.quotationId(vector.portionId, vector.publicationId)
            val second = LocalFoodQuotationProfileV1.quotationId(vector.portionId, vector.publicationId)
            assertEquals(DomainId(vector.expectedId), first)
            assertEquals(first, second)
            assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(first.value))
            first
        }
        assertNotEquals(observed[0], observed[1])
        assertNotEquals(observed[0], observed[2])
        assertNotEquals(observed[1], observed[3])
        assertNotEquals(observed[2], observed[3])
        // No genuine branch configuration or routed legacy identity is bound here.
    }

    @Test
    fun frq002AllOriginalNonRouteShapeFactsRoundTripWithoutExecutionFields() {
        val value = f.quotation()
        assertEquals(f.expectedShapeFacts() - "routeKeys", f.facts(value) - "routeKeys")
        val encoded = encode(value)
        val decoded = json.decodeFromString(LocalFoodRecipeQuotationV1.serializer(), encoded)
        assertEquals(f.expectedShapeFacts() - "routeKeys", f.facts(decoded) - "routeKeys")
        assertEquals(encoded, encode(decoded)) // Observed round trip, not a new frozen wire encoding.
        val forbiddenFragments = listOf("routeversion", "warehouse", "station", "reservation", "issue", "task", "payment")
        val executionKeys = keys(json.parseToJsonElement(encoded)).filter { key ->
            forbiddenFragments.any { fragment -> fragment in key.lowercase() }
        }
        assertEquals(emptyList(), executionKeys)
        // Exact UUID route facts are FRQK-C002, never a pass of parent literal labels.
    }

    @Test
    fun frq003AllEightFoodActivityChannelPairsRetainTheirSuppliedFacts() {
        for (activity in listOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE)) {
            for (channel in listOf(LocalSalesChannel.DINE_IN, LocalSalesChannel.COUNTER,
                LocalSalesChannel.TAKEAWAY, LocalSalesChannel.DELIVERY)) {
                val value = f.quotation(activity = activity, channel = channel)
                assertEquals(activity, value.activity)
                assertEquals(channel, value.channel)
            }
        }
    }

    @Test
    fun frq003ClothingAndSupermarketShapesReject() {
        assertBaseShape()
        for (activity in listOf(LocalSalesActivity.CLOTHING, LocalSalesActivity.SUPERMARKET)) {
            assertFails("FRQ-003 activity=$activity") { f.quotation(activity = activity) }
        }
    }

    @Test
    fun frq004AllEightFrozenNonpositiveRevisionVariantsReject() {
        assertBaseShape()
        for (revision in listOf(0L, -1L)) {
            assertFails("recipe_revision:$revision") { f.quotation(recipeRevision = Revision(revision)) }
            assertFails("output_sku_revision:$revision") { f.quotation(outputSkuRevision = Revision(revision)) }
            for ((index, component) in listOf(f.cb, f.ca).withIndex()) {
                assertFails("ingredients.$component.sku_revision:$revision") {
                    val ingredients = f.ingredients().toMutableList()
                    ingredients[index] = ingredients[index].copy(skuRevision = Revision(revision))
                    f.quotation(ingredients = ingredients)
                }
            }
        }
    }

    @Test
    fun frq004AllTwelveFrozenNonpositiveQuantityVariantsReject() {
        assertBaseShape()
        for (amount in listOf(0L, -1L)) {
            assertFails("yield.scaled:$amount") { f.quotation(yieldQuantity = f.yieldQuantity.copy(scaled = amount)) }
            assertFails("serving_quantity.scaled:$amount") { f.quotation(portionQuantity = f.portionQuantity.copy(scaled = amount)) }
            for ((index, component) in listOf(f.cb, f.ca).withIndex()) {
                assertFails("ingredients.$component.quantity_per_yield.scaled:$amount") {
                    val ingredients = f.ingredients().toMutableList()
                    ingredients[index] = ingredients[index].copy(
                        quantityPerYield = ingredients[index].quantityPerYield.copy(scaled = amount))
                    f.quotation(ingredients = ingredients)
                }
                assertFails("ingredients.$component.required_base_amount.scaled:$amount") {
                    val ingredients = f.ingredients().toMutableList()
                    ingredients[index] = ingredients[index].copy(
                        requiredBaseQuantity = ingredients[index].requiredBaseQuantity.copy(scaled = amount))
                    f.quotation(ingredients = ingredients)
                }
            }
        }
    }

    @Test
    fun frq004ExistingQuantityScaleMinusOneAndSevenReject() {
        assertBaseShape()
        for (scale in listOf(-1, 7)) {
            assertFails("quantity.scale:$scale") {
                f.quotation(yieldQuantity = Quantity(f.serving, 4, scale))
            }
        }
    }

    @Test
    fun frq004FrozenConsistentScaleZeroAndSixAmountsAreAcceptedExactly() {
        for (vector in listOf(
            ScaleVector(0, 4, 2, 200, 100),
            ScaleVector(6, 4_000_000, 2_000_000, 200_000_000, 100_000_000),
        )) {
            val ingredients = f.ingredients().toMutableList()
            ingredients[1] = ingredients[1].copy(
                quantityPerYield = Quantity(f.gram, vector.perYieldScaled, vector.scale),
                requiredBaseQuantity = Quantity(f.gram, vector.requiredScaled, vector.scale))
            val yield = Quantity(f.serving, vector.yieldScaled, vector.scale)
            val serving = Quantity(f.serving, vector.servingScaled, vector.scale)
            val value = f.quotation(yieldQuantity = yield, portionQuantity = serving, ingredients = ingredients)
            assertEquals(yield, value.yieldQuantity)
            assertEquals(serving, value.portionQuantity)
            assertEquals(ingredients, value.ingredients)
        }
    }

    @Test
    fun frq004AllFourFrozenDimensionMismatchesRejectDespiteNumericFacts() {
        assertBaseShape()
        assertFails("yield-serving-unit-mismatch") {
            f.quotation(portionQuantity = f.portionQuantity.copy(unitId = f.gram))
        }
        assertFails("yield-serving-scale-mismatch") {
            f.quotation(portionQuantity = f.portionQuantity.copy(scale = 1))
        }
        assertFails("ingredient-required-unit-mismatch") {
            val ingredients = f.ingredients().toMutableList()
            ingredients[1] = ingredients[1].copy(requiredBaseQuantity =
                ingredients[1].requiredBaseQuantity.copy(unitId = f.milliliter))
            f.quotation(ingredients = ingredients)
        }
        assertFails("ingredient-required-scale-mismatch") {
            val ingredients = f.ingredients().toMutableList()
            ingredients[1] = ingredients[1].copy(requiredBaseQuantity =
                ingredients[1].requiredBaseQuantity.copy(scale = 2))
            f.quotation(ingredients = ingredients)
        }
    }

    @Test
    fun frq005FrozenTwoAndOneServingIngredientAmountsAreExact() {
        val two = f.quotation()
        assertEquals(listOf(200_000L, 100_000L), two.ingredients.map { it.requiredBaseQuantity.scaled })
        val ingredients = f.ingredients().mapIndexed { index, ingredient -> ingredient.copy(
            requiredBaseQuantity = ingredient.requiredBaseQuantity.copy(scaled = listOf(100_000L, 50_000L)[index])) }
        val one = f.quotation(portionQuantity = Quantity(f.serving, 1, 0), ingredients = ingredients)
        assertEquals(listOf(f.cb, f.ca), one.ingredients.map { it.componentId })
        assertEquals(listOf(100_000L, 50_000L), one.ingredients.map { it.requiredBaseQuantity.scaled })
        assertEquals(listOf(400_000L, 200_000L), one.ingredients.map { it.quantityPerYield.scaled })
        assertEquals(listOf(f.milliliter, f.gram), one.ingredients.map { it.requiredBaseQuantity.unitId })
        assertEquals(listOf(3, 3), one.ingredients.map { it.requiredBaseQuantity.scale })
    }

    @Test
    fun frq005FrozenExactDivisionRetainsTwoThousandWithoutRounding() {
        val value = f.arithmeticQuotation(3, 2, 3_000, 2_000)
        assertEquals(Quantity(f.serving, 3, 0), value.yieldQuantity)
        assertEquals(Quantity(f.serving, 2, 0), value.portionQuantity)
        assertEquals(Quantity(f.gram, 3_000, 3), value.ingredients.single().quantityPerYield)
        assertEquals(Quantity(f.gram, 2_000, 3), value.ingredients.single().requiredBaseQuantity)
    }

    @Test
    fun frq005FrozenNearbyWrongRequiredAmountsReject() {
        f.arithmeticQuotation(3, 2, 3_000, 2_000)
        for (supplied in listOf(1_999L, 2_001L)) {
            assertFails("wrong-required:$supplied") { f.arithmeticQuotation(3, 2, 3_000, supplied) }
        }
    }

    @Test
    fun frq005FrozenNonintegralTruncationAndRoundingSubstitutesReject() {
        assertBaseShape()
        for (supplied in listOf(333L, 334L)) {
            assertFails("nonintegral:$supplied") { f.arithmeticQuotation(3, 1, 1_000, supplied) }
        }
    }

    @Test
    fun frq005FrozenLongOverflowRejectsTheSuppliedLongMaximumSubstitute() {
        assertBaseShape()
        assertFails("Long-overflow; mathematical requirement=18446744073709551614") {
            f.arithmeticQuotation(1, 2, Long.MAX_VALUE, Long.MAX_VALUE)
        }
    }

    @Test
    fun frq006FrozenIngredientCountsOneSixteenZeroAndSeventeen() {
        for (count in listOf(1, 16)) {
            val ingredients = f.ingredientCount(count)
            assertEquals(ingredients, f.quotation(ingredients = ingredients).ingredients)
        }
        assertBaseShape()
        for (count in listOf(0, 17)) {
            assertFails("ingredients-count-$count") { f.quotation(ingredients = f.ingredientCount(count)) }
        }
    }

    @Test
    fun frq006FrozenRepeatedComponentIdentityRejects() {
        assertBaseShape()
        val ingredients = f.ingredients().toMutableList()
        ingredients[1] = ingredients[1].copy(componentId = f.cb)
        assertFails("duplicate-ingredients.component_id") { f.quotation(ingredients = ingredients) }
    }

    @Test
    fun frq006FrozenDistinctComponentsUsingTheSameSkuAreNotCollapsed() {
        val ingredients = f.ingredients().map { ingredient -> ingredient.copy(
            skuId = f.ingredientA, skuRevision = Revision(5),
            quantityPerYield = ingredient.quantityPerYield.copy(unitId = f.gram),
            requiredBaseQuantity = ingredient.requiredBaseQuantity.copy(unitId = f.gram)) }
        val value = f.quotation(ingredients = ingredients)
        assertEquals(listOf(f.cb, f.ca), value.ingredients.map { it.componentId })
        assertEquals(listOf(f.ingredientA, f.ingredientA), value.ingredients.map { it.skuId })
        assertEquals(ingredients, value.ingredients)
    }

    @Test
    fun frq006FrozenModifierCountsAndRepeatedIdentity() {
        for (count in listOf(0, 16)) {
            val modifiers = f.modifierCount(count)
            assertEquals(modifiers, f.quotation(modifiers = modifiers).modifierIds)
        }
        assertBaseShape()
        assertFails("modifier_ids-count-17") { f.quotation(modifiers = f.modifierCount(17)) }
        assertFails("duplicate-modifier_ids") { f.quotation(modifiers = listOf(f.m2, f.m2)) }
    }

    @Test
    fun frq006FrozenReferenceCountsAndRepeatedKindIdPair() {
        for (count in listOf(2, 32)) {
            val sources = f.sourceCount(count)
            assertEquals(sources, f.quotation(sources = sources).sources)
        }
        assertBaseShape()
        for (count in listOf(1, 33)) {
            assertFails("source_references-count-$count") { f.quotation(sources = f.sourceCount(count)) }
        }
        val sources = f.sources()
        assertFails("duplicate-source_references.kind-id-pair") {
            f.quotation(sources = listOf(sources[0], sources[0], sources[2]))
        }
    }

    @Test
    fun frq007FrozenLengthWhitespaceAndSupplementaryCharacterBoundaries() {
        val accepted = listOf("description-323" to "A".repeat(323),
            "description-no-trim" to " A ", "description-supplementary-323" to "\uD83D\uDE00".repeat(161) + "x")
        for ((name, description) in accepted) {
            assertEquals(description, f.quotation(description = description).description, name)
        }
        assertEquals(323, accepted[2].second.length)
        assertBaseShape()
        for ((name, description) in listOf("description-324" to "A".repeat(324),
            "description-empty" to "", "description-blank" to "   ",
            "description-supplementary-324" to "\uD83D\uDE00".repeat(162))) {
            assertFails(name) { f.quotation(description = description) }
        }
    }

    @Test
    fun frq007AllThirtyThreeFrozenControlCodepointsReject() {
        assertBaseShape()
        val codepoints = (0..31).toList() + 127
        assertEquals(33, codepoints.size)
        for (codepoint in codepoints) {
            assertFails("description-controls:U+${codepoint.toString(16).padStart(4, '0')}") {
                f.quotation(description = "A${codepoint.toChar()}B")
            }
        }
    }

    @Test
    fun frq008FrozenPublicationUtf8BoundsAndInvalidSurrogates() {
        for ((name, text) in listOf("publication-shape-only" to "{}",
            "publication-ASCII-16384" to "x".repeat(16_384),
            "publication-UTF8-16384" to "\u00e9".repeat(8_192))) {
            assertEquals(text, f.quotation(signedPublication = text).signedPublication, name)
        }
        assertBaseShape()
        for ((name, text) in listOf("publication-empty" to "",
            "publication-ASCII-16385" to "x".repeat(16_385),
            "publication-UTF8-16385" to "\u00e9".repeat(8_192) + "x",
            "publication-lone-high-surrogate" to "\uD800", "publication-lone-low-surrogate" to "\uDC00")) {
            assertFails(name) { f.quotation(signedPublication = text) }
        }
    }

    @Test
    fun frq008AllTenFrozenSourceReferenceShapeRefusals() {
        assertBaseShape()
        rejectReferences("missing-FOOD") { it.drop(1) }
        rejectReferences("two-FOOD") { it + it[0].copy(id = f.u2) }
        rejectReferences("wrong-FOOD-publication") { listOf(it[0].copy(id = f.u2)) + it.drop(1) }
        rejectReferences("missing-FOOD-key") { listOf(it[0].copy(signingKeyId = null)) + it.drop(1) }
        rejectReferences("missing-FOOD-operation") { listOf(it[0].copy(operationId = null)) + it.drop(1) }
        rejectReferences("unknown-kind") { listOf(it[0], it[1].copy(kind = "local.untrusted.publication"), it[2]) }
        rejectReferences("missing-catalog-key") { listOf(it[0], it[1].copy(signingKeyId = null), it[2]) }
        rejectReferences("catalog-operation") { listOf(it[0], it[1].copy(operationId = f.recipeOperation), it[2]) }
        rejectReferences("catalog-zero-revision") { listOf(it[0], it[1].copy(revision = Revision(0)), it[2]) }
        rejectReferences("catalog-negative-revision") { listOf(it[0], it[1].copy(revision = Revision(-1)), it[2]) }
    }

    @Test
    fun frq009CallerModifierIngredientAndReferenceMutationsCannotRewriteFacts() {
        val modifiers = f.modifiers.toMutableList()
        val ingredients = f.ingredients().toMutableList()
        val sources = f.sources().toMutableList()
        val value = f.quotation(modifiers = modifiers, ingredients = ingredients, sources = sources)
        val original = f.facts(value)
        val encoded = encode(value)
        assertEquals(f.expectedShapeFacts(), original)
        val verify = { assertEquals(original, f.facts(value)); assertEquals(encoded, encode(value)) }
        mutateCallerList(modifiers, f.m1, verify)
        mutateCallerList(ingredients, ingredients[1], verify)
        mutateCallerList(sources, sources[0].copy(id = f.u2), verify)
        // Route caller mutation is separately asserted as FRQK-C003.
    }

    @Test
    fun frq009ExposedModifierIngredientAndReferenceMutationsCannotRewriteFacts() {
        val value = f.quotation()
        val original = f.facts(value)
        val encoded = encode(value)
        assertEquals(f.expectedShapeFacts(), original)
        val verify = { assertEquals(original, f.facts(value)); assertEquals(encoded, encode(value)) }
        attemptExposedMutations({ value.modifierIds }, f.m1, verify)
        attemptExposedMutations({ value.ingredients }, f.ingredients()[1], verify)
        attemptExposedMutations({ value.sources }, f.sources()[0].copy(id = f.u2), verify)
    }

    private fun assertBaseShape() {
        assertEquals(f.expectedShapeFacts(), f.facts(f.quotation()))
    }

    private fun rejectReferences(name: String, mutate: (List<LocalFoodSourceReferenceV1>) -> List<LocalFoodSourceReferenceV1>) {
        assertFails(name) { f.quotation(sources = mutate(f.sources())) }
    }

    private fun encode(value: LocalFoodRecipeQuotationV1): String =
        json.encodeToString(LocalFoodRecipeQuotationV1.serializer(), value)

    private fun keys(value: JsonElement): List<String> = when (value) {
        is JsonObject -> value.keys.toList() + value.values.flatMap { keys(it) }
        is JsonArray -> value.flatMap { keys(it) }
        else -> emptyList()
    }

    private data class IdentityVector(val portionId: DomainId, val publicationId: DomainId,
        val inputUtf8: String, val fullSha256: String, val expectedId: String)

    private data class ScaleVector(val scale: Int, val yieldScaled: Long, val servingScaled: Long,
        val perYieldScaled: Long, val requiredScaled: Long)
}
