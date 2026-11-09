package com.ukkera.pos.sales.store.independent.foodatomicquotationbindingv1

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.ukkera.pos.sales.store.independent.foodatomicquotationbindingv1.FrozenFoodAtomicQuotationBindingInputs as F

/** Bounded FAQB-040 W01–W15 construction/projection/correspondence/schema slices.
 * Every oracle comes from the atomic v1 freeze and its independently frozen value companion.
 * A constructor/decoder refusal does not exercise correspondence, original verification or ingress.
 * PRICE_BATCH/membership/aliases, genuine producers and all native/business effects remain unbound.
 * The one-key, empty-modifier fixture cannot qualify multi-key or modifier-order controls. */
class FoodAtomicQuotationBindingFrozenValuesTest {
    @Test
    fun faqb040_lawfulP1P2ProjectionAndCorrespondenceRetainEveryOriginalFact() {
        for (portionId in listOf(F.u(301), F.u(302))) {
            val quotation = F.quotation(portionId)
            val before = F.quotationBytes(quotation)
            val snapshot = F.project(quotation)
            assertQuotation(quotation, portionId)
            assertSnapshot(snapshot, portionId)
            val snapshotBefore = F.snapshotBytes(snapshot)
            val checked = FoodRecipeRouting.requireOriginalFacts(quotation, snapshot)
            assertSnapshot(checked, portionId)
            assertEquals(before, F.quotationBytes(quotation))
            assertEquals(snapshotBefore, F.snapshotBytes(snapshot))
            assertEquals(snapshotBefore, F.snapshotBytes(checked))
            println("FAQB-040 $portionId: projection-success; correspondence-success; structural-only")
        }
    }

    @Test
    fun faqb040W01_arbitraryQuotationAndSnapshotIdentitiesRefuseAtConstruction() {
        refusesQuotationConstruction("W01 quotation identity") { F.quotation(quotationId = F.u(140)) }
        refusesSnapshot("W01 snapshot identity") { it.copy(snapshotId = F.u(140)) }
    }

    @Test
    fun faqb040W02_changedRequiredIdentityAndDefinitionFactsRefuse() {
        val deltas = listOf(
            SnapshotDelta("portion identity") { it.copy(portionId = F.u(302), snapshotId = F.snapshot2Id) },
            SnapshotDelta("activity") { it.copy(activity = LocalSalesActivity.CAFE) },
            SnapshotDelta("channel") { it.copy(channel = LocalSalesChannel.TAKEAWAY) },
            SnapshotDelta("recipe identity") { it.copy(recipeId = F.u(100)) },
            SnapshotDelta("recipe definition revision") { it.copy(recipeRevision = Revision(6)) },
            SnapshotDelta("publication identity") { it.copy(publicationId = F.u(150),
                snapshotId = LocalFoodProfileV1.recipeSnapshotId(it.portionId, F.u(150), it.routeVersionId)) },
            SnapshotDelta("output identity") { it.copy(outputSkuId = F.u(101)) },
            SnapshotDelta("output definition revision") { it.copy(outputSkuRevision = Revision(4)) },
        )
        deltas.forEach { refusesSnapshot("W02 ${it.name}", it.change) }
    }

    @Test
    fun faqb040W03_changedSelectionDescriptionAndQuantityFactsRefuse() {
        val deltas = listOf(
            SnapshotDelta("choice") { it.copy(choiceId = F.u(110)) },
            SnapshotDelta("modifier facts") { it.copy(modifierIds = listOf(F.u(112))) },
            SnapshotDelta("literal description") { it.copy(description = "Burger!") },
            SnapshotDelta("yield") { it.copy(yieldQuantity = F.quantity(20, 2)) },
            SnapshotDelta("portion quantity") { it.copy(portionQuantity = F.quantity(20, 2)) },
            SnapshotDelta("pricing SKU") { it.copy(pricingSkuId = F.u(100)) },
        )
        deltas.forEach { refusesSnapshot("W03 ${it.name}", it.change) }
    }

    @Test
    fun faqb040W04_orderedComponentMembershipSkuRevisionAndQuantityRefuse() {
        val deltas = listOf(
            SnapshotDelta("missing component") { it.copy(ingredients = it.ingredients.dropLast(1)) },
            SnapshotDelta("extra component") { it.copy(ingredients = it.ingredients +
                it.ingredients.first().copy(componentId = F.u(120))) },
            SnapshotDelta("reordered components") { it.copy(ingredients = it.ingredients.reversed()) },
            SnapshotDelta("component identity") { it.copy(ingredients = listOf(
                it.ingredients[0].copy(componentId = F.u(112)), it.ingredients[1])) },
            SnapshotDelta("ingredient SKU") { it.copy(ingredients = listOf(
                it.ingredients[0].copy(skuId = F.u(100)), it.ingredients[1])) },
            SnapshotDelta("ingredient definition revision") { it.copy(ingredients = listOf(
                it.ingredients[0].copy(skuRevision = Revision(3)), it.ingredients[1])) },
            SnapshotDelta("ingredient quantity") { it.copy(ingredients = listOf(it.ingredients[0],
                it.ingredients[1].copy(quantityPerYield = F.quantity(22, 149),
                    requiredBaseQuantity = F.quantity(22, 149)))) },
        )
        deltas.forEach { refusesSnapshot("W04 ${it.name}", it.change) }
    }

    @Test
    fun faqb040W05_nonrepresentableRoundedRescaledAndIncompatibleQuantitiesRefuse() {
        val deltas = listOf(
            SnapshotDelta("fractional ingredient requirement") { it.copy(yieldQuantity = F.quantity(20, 3)) },
            SnapshotDelta("rounded ingredient requirement") { it.copy(ingredients = listOf(
                it.ingredients[0].copy(requiredBaseQuantity = F.quantity(21, 2)), it.ingredients[1])) },
            SnapshotDelta("rescaled ingredient requirement") { it.copy(ingredients = listOf(
                it.ingredients[0].copy(requiredBaseQuantity = F.quantity(21, 10, 1)), it.ingredients[1])) },
            SnapshotDelta("incompatible ingredient unit") { it.copy(ingredients = listOf(
                it.ingredients[0].copy(requiredBaseQuantity = F.quantity(22, 1)), it.ingredients[1])) },
            SnapshotDelta("incompatible yield scale") { it.copy(yieldQuantity = F.quantity(20, 10, 1)) },
        )
        deltas.forEach { refusesSnapshot("W05 ${it.name}", it.change) }
    }

    @Test
    fun faqb040W06_literalSignedBytesAndExactOrderedOriginalPrefixRefuseSubstitution() {
        val deltas = listOf(
            SnapshotDelta("signed bytes") { it.copy(signedPublication = it.signedPublication + " ") },
            SnapshotDelta("original reference substituted") { it.copy(sources = listOf(
                it.sources[0].copy(digest = it.sources[1].digest)) + it.sources.drop(1)) },
            SnapshotDelta("original reference omitted") { it.copy(sources = it.sources.drop(1)) },
            SnapshotDelta("original reference duplicated") { it.copy(sources = listOf(it.sources[0]) + it.sources) },
            SnapshotDelta("original references reordered") { it.copy(sources = listOf(
                it.sources[1], it.sources[0]) + it.sources.drop(2)) },
            SnapshotDelta("original-looking publication extended") { it.copy(sources =
                it.sources.take(2) + it.sources[0].copy(id = F.u(151)) + it.sources.drop(2)) },
        )
        deltas.forEach { refusesSnapshot("W06 ${it.name}", it.change) }
    }

    @Test
    fun faqb040W07_invalidOriginalReferenceGenerationSigningAndProducerShapeRefuse() {
        for (index in 0..1) {
            val original = F.originalSources()
            refusesQuotationConstruction("W07 reference $index zero generation") {
                F.quotation(sourceValues = original.mapIndexed { i, source ->
                    if (i == index) source.copy(revision = Revision(0)) else source })
            }
            refusesQuotationConstruction("W07 reference $index missing signing key") {
                F.quotation(sourceValues = original.mapIndexed { i, source ->
                    if (i == index) source.copy(signingKeyId = null) else source })
            }
        }
        refusesQuotationConstruction("W07 absent FOOD producing operation") {
            val original = F.originalSources()
            F.quotation(sourceValues = listOf(original[0].copy(operationId = null), original[1]))
        }
        refusesQuotationConstruction("W07 catalog producing operation present") {
            val original = F.originalSources()
            F.quotation(sourceValues = listOf(original[0], original[1].copy(operationId = F.op(170))))
        }
    }

    @Test
    fun faqb040W08_mandatoryReferenceIdsDigestsAndValueEncodingsRefuseAtDecode() {
        for ((index, source) in F.originalSources().withIndex()) {
            val encoded = localSalesEncode(source, LocalFoodSourceReferenceV1.serializer())
            val original = localSalesJson.parseToJsonElement(encoded.copyBytes().decodeToString()).jsonObject
            val variants = listOf(
                "absent id" to JsonObject(original - "id"),
                "absent authorizedBy" to JsonObject(original - "authorizedBy"),
                "null id" to JsonObject(original + ("id" to JsonNull)),
                "null authorizedBy" to JsonObject(original + ("authorizedBy" to JsonNull)),
                "malformed UUID" to JsonObject(original + ("id" to JsonPrimitive("not-a-uuid"))),
                "malformed authority UUID" to JsonObject(original + ("authorizedBy" to JsonPrimitive("not-a-uuid"))),
                "malformed digest" to JsonObject(original + ("digest" to JsonPrimitive("not-a-digest"))),
                "incompatible revision encoding" to JsonObject(original + ("revision" to JsonPrimitive(1))),
                "incompatible digest encoding" to JsonObject(original + ("digest" to JsonPrimitive(1))),
            )
            for ((name, candidate) in variants) {
                val invocation = runCatching { localSalesDecode(StoredBytes(candidate.toString().encodeToByteArray()),
                    LocalFoodSourceReferenceV1.serializer()) }
                assertTrue(invocation.isFailure, "FAQB-040 W08 reference $index $name must refuse")
                assertEquals(encoded, localSalesEncode(source, LocalFoodSourceReferenceV1.serializer()))
                println("FAQB-040 W08 reference $index $name: value-decode-rejection; quotation/checker UNBOUND")
            }
        }
    }

    @Test
    fun faqb040W09_requiredRouteKeysCannotBeMissingExtraOrReplacedByStationIdentity() {
        val routes = F.routes()
        refusesProjection("W09 missing key", routes = emptyList())
        refusesProjection("W09 extra key", routes = routes + routes[0].copy(routeKey = F.u(112), stationId = F.u(134)))
        refusesProjection("W09 station substituted for symbolic key", routes = listOf(routes[0].copy(routeKey = F.u(130))))
    }

    @Test
    fun faqb040W10_stationDefinitionRevisionMustBePositive() {
        refusesProjection("W10 zero station revision", routes = listOf(F.routes()[0].copy(stationRevision = Revision(0))))
    }

    @Test
    fun faqb040W11_routeAndStationVersionMembershipAndDerivedSnapshotIdentityRefuse() {
        val sources = F.routeSources()
        refusesProjection("W11 route version absent", routeVersionId = F.u(133))
        refusesProjection("W11 station version missing", sources = listOf(sources[0]))
        refusesProjection("W11 extra station version", sources = sources + sources[1].copy(id = F.u(140), operationId = F.op(140)))
        refusesProjection("W11 route version substituted", sources = listOf(
            sources[0].copy(id = F.u(133), operationId = F.op(133)), sources[1]))
        refusesSnapshot("W11 nonmatching derived snapshot identity") { it.copy(snapshotId = F.u(132)) }
    }

    @Test
    fun faqb040W12_appendedNativeVersionReferenceShapeMustRemainExact() {
        for (index in 0..1) {
            val sources = F.routeSources()
            val deltas: List<Pair<String, (LocalFoodSourceReferenceV1) -> LocalFoodSourceReferenceV1>> = listOf(
                "publication kind" to { it.copy(kind = "local.food.publication") },
                "zero storage generation" to { it.copy(revision = Revision(0)) },
                "signing key present" to { it.copy(signingKeyId = F.u(160)) },
                "original operation absent" to { it.copy(operationId = null) },
            )
            for ((name, change) in deltas) refusesProjection("W12 reference $index $name",
                sources = sources.mapIndexed { i, source -> if (i == index) change(source) else source })
        }
    }

    @Test
    fun faqb040W13_appendedOperationAndVersionIdentityMustAgree() {
        val sources = F.routeSources()
        refusesProjection("W13 route operation mismatch", sources = listOf(
            sources[0].copy(operationId = F.op(135)), sources[1]))
        refusesProjection("W13 station operation mismatch", sources = listOf(
            sources[0], sources[1].copy(operationId = F.op(132))))
    }

    @Test
    fun faqb040W14_appendedSourcesCannotDuplicateOrReplacePublicationsOrRequiredVersions() {
        val sources = F.routeSources()
        refusesProjection("W14 original publication appended", sources = listOf(sources[0], F.originalSources()[0]))
        refusesProjection("W14 original catalog publication appended", sources = listOf(sources[0], F.originalSources()[1]))
        refusesProjection("W14 required native version duplicated", sources = listOf(sources[0], sources[0]))
        refusesSnapshot("W14 original prefix replaced by another publication") { it.copy(sources = listOf(
            it.sources[0].copy(kind = "local.catalog.publication", id = F.u(151), operationId = null)) + it.sources.drop(1)) }
    }

    @Test
    fun faqb040W15_explicitQuotedAndRoutedSchemasCannotExchangeBodyTypes() {
        for (portionId in listOf(F.u(301), F.u(302))) {
            val quoted = F.quotedEnvelope(portionId)
            val sidecar = F.sidecar(portionId)
            val quotedBytes = foodQuotationEnvelopeBytes(quoted, FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA)
            val sidecarBytes = foodQuotationEnvelopeBytes(sidecar, FoodQuotationStorageV1.RECIPE_QUOTATION_SCHEMA)
            assertEquals(quoted, foodQuotationEnvelope(FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA, quotedBytes))
            val decodedSidecar = foodQuotationEnvelope(FoodQuotationStorageV1.RECIPE_QUOTATION_SCHEMA, sidecarBytes)
            assertEquals(sidecarBytes, foodQuotationEnvelopeBytes(decodedSidecar,
                FoodQuotationStorageV1.RECIPE_QUOTATION_SCHEMA))
            val decodedBody = decodedSidecar.body as FoodBodyV1.RecipeQuotation
            assertEquals(F.u(200), decodedBody.orderId)
            assertEquals(F.u(201), decodedBody.priceBatchId)
            assertQuotation(decodedBody.quotation, portionId)
            val quotedBefore = F.envelopeBytes(quoted)
            val quotedAsRouted = runCatching { requireFoodStoredEnvelope(quoted, LocalFoodProfileV1.schema(LocalFoodProfileV1.PORTION)) }
            assertTrue(quotedAsRouted.isFailure, "W15 quotation body under routed schema")
            assertEquals(quotedBefore, F.envelopeBytes(quoted))
            println("FAQB-040 W15 $portionId quoted-as-routed: schema-rejection; native UNBOUND")

            val routed = F.routedEnvelope(portionId)
            requireFoodEnvelope(routed)
            val routedBefore = F.envelopeBytes(routed)
            val routedAsQuoted = runCatching { requireFoodQuotationEnvelope(routed, FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA) }
            assertTrue(routedAsQuoted.isFailure, "W15 routed body under quotation schema")
            assertEquals(routedBefore, F.envelopeBytes(routed))
            println("FAQB-040 W15 $portionId routed-as-quoted: schema-rejection; native UNBOUND")
        }
    }

    @Test
    fun faqb040_originalAndProjectedCollectionsRetainFactsAfterCallerMutation() {
        val modifiers = mutableListOf<DomainId>()
        val ingredients = F.ingredients().toMutableList()
        val keys = mutableListOf(F.u(120))
        val originalSources = F.originalSources().toMutableList()
        val routes = F.routes().toMutableList()
        val routeSources = F.routeSources().toMutableList()
        val quotation = F.quotation(modifierIds = modifiers, ingredientValues = ingredients,
            routeKeys = keys, sourceValues = originalSources)
        val snapshot = F.project(quotation, routeValues = routes, sourceValues = routeSources)
        val checked = FoodRecipeRouting.requireOriginalFacts(quotation, snapshot)
        val reread = { assertQuotation(quotation, F.u(301)); assertSnapshot(snapshot, F.u(301)); assertSnapshot(checked, F.u(301)) }
        reread()
        modifiers.add(F.u(112)); reread()
        for (holder in listOf<MutableList<*>>(ingredients, keys, originalSources, routes, routeSources)) {
            holder.reverse(); reread()
            holder.clear(); reread()
        }
        for (collection in listOf<() -> List<*>>(
            { quotation.modifierIds }, { quotation.ingredients }, { quotation.routeKeys }, { quotation.sources },
            { snapshot.modifierIds }, { snapshot.ingredients }, { snapshot.routes }, { snapshot.sources },
            { checked.modifierIds }, { checked.ingredients }, { checked.routes }, { checked.sources },
        )) {
            runCatching { (collection() as MutableList<*>).reverse() }; reread()
            runCatching { (collection() as MutableList<*>).clear() }; reread()
        }
        println("FAQB-040 defensive retention: original/projection/correspondence values unchanged; native UNBOUND")
    }

    private fun refusesSnapshot(label: String, change: (LocalFoodRecipeSnapshotV1) -> LocalFoodRecipeSnapshotV1) {
        val quotation = F.quotation()
        val snapshot = F.project(quotation)
        val quotationBefore = F.quotationBytes(quotation)
        val snapshotBefore = F.snapshotBytes(snapshot)
        val construction = runCatching { change(snapshot) }
        assertEquals(quotationBefore, F.quotationBytes(quotation), "$label original quotation unchanged")
        assertEquals(snapshotBefore, F.snapshotBytes(snapshot), "$label original candidate unchanged")
        if (construction.isFailure) {
            println("FAQB-040 $label: constructor-rejection; correspondence UNBOUND")
            return
        }
        val candidate = construction.getOrThrow()
        val candidateBefore = F.snapshotBytes(candidate)
        val invocation = runCatching { FoodRecipeRouting.requireOriginalFacts(quotation, candidate) }
        assertTrue(invocation.isFailure, "FAQB-040 $label must refuse at correspondence")
        assertEquals(quotationBefore, F.quotationBytes(quotation))
        assertEquals(snapshotBefore, F.snapshotBytes(snapshot))
        assertEquals(candidateBefore, F.snapshotBytes(candidate))
        println("FAQB-040 $label: correspondence-rejection; native UNBOUND")
    }

    private fun refusesQuotationConstruction(label: String, construct: () -> LocalFoodRecipeQuotationV1) {
        val original = F.quotation()
        val before = F.quotationBytes(original)
        val invocation = runCatching(construct)
        assertTrue(invocation.isFailure, "FAQB-040 $label must refuse at public quotation construction")
        assertEquals(before, F.quotationBytes(original))
        println("FAQB-040 $label: quotation-constructor-rejection; projection/correspondence UNBOUND")
    }

    private fun refusesProjection(label: String, routes: List<LocalFoodStationRouteV1> = F.routes(),
        sources: List<LocalFoodSourceReferenceV1> = F.routeSources(), routeVersionId: DomainId = F.u(132)) {
        val quotation = F.quotation()
        val before = F.quotationBytes(quotation)
        val routeBefore = routes.toList()
        val sourcesBefore = sources.toList()
        val invocation = runCatching { F.project(quotation, routeVersionId = routeVersionId,
            routeValues = routes, sourceValues = sources) }
        assertTrue(invocation.isFailure, "FAQB-040 $label must refuse at projection")
        assertEquals(before, F.quotationBytes(quotation))
        assertEquals(routeBefore, routes)
        assertEquals(sourcesBefore, sources)
        println("FAQB-040 $label: projection-rejection; correspondence/native UNBOUND")
    }

    private fun assertQuotation(value: LocalFoodRecipeQuotationV1, portionId: DomainId) {
        assertEquals(if (portionId == F.u(301)) F.quotation1Id else F.quotation2Id, value.quotationId)
        assertEquals(originalFacts(portionId), listOf(value.portionId, value.activity, value.channel, value.recipeId,
            value.recipeRevision, value.publicationId, value.outputSkuId, value.outputSkuRevision, value.choiceId,
            value.modifierIds, value.description, value.yieldQuantity, value.portionQuantity, value.pricingSkuId, value.ingredients))
        assertEquals(listOf(F.u(120)), value.routeKeys)
        assertEquals(F.originalSources(), value.sources)
        assertContentEquals(F.SIGNED_PUBLICATION.encodeToByteArray(), value.signedPublication.encodeToByteArray())
    }

    private fun assertSnapshot(value: LocalFoodRecipeSnapshotV1, portionId: DomainId) {
        assertEquals(if (portionId == F.u(301)) F.snapshot1Id else F.snapshot2Id, value.snapshotId)
        assertEquals(originalFacts(portionId), listOf(value.portionId, value.activity, value.channel, value.recipeId,
            value.recipeRevision, value.publicationId, value.outputSkuId, value.outputSkuRevision, value.choiceId,
            value.modifierIds, value.description, value.yieldQuantity, value.portionQuantity, value.pricingSkuId, value.ingredients))
        assertEquals(F.u(132), value.routeVersionId)
        assertEquals(F.u(140), value.warehouseId)
        assertEquals(F.routes(), value.routes)
        assertEquals(F.originalSources() + F.routeSources(), value.sources)
        assertContentEquals(F.SIGNED_PUBLICATION.encodeToByteArray(), value.signedPublication.encodeToByteArray())
    }

    private fun originalFacts(portionId: DomainId): List<Any> = listOf(portionId, LocalSalesActivity.RESTAURANT,
        LocalSalesChannel.DINE_IN, F.u(110), Revision(5), F.u(111), F.u(100), Revision(3), F.u(112),
        emptyList<DomainId>(), "Burger", F.quantity(20, 1), F.quantity(20, 1), F.u(101), F.ingredients())

    private data class SnapshotDelta(val name: String, val change: (LocalFoodRecipeSnapshotV1) -> LocalFoodRecipeSnapshotV1)
}
