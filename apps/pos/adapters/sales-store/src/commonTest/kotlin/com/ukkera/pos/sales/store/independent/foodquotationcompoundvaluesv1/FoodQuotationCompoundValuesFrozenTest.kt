package com.ukkera.pos.sales.store.independent.foodquotationcompoundvaluesv1

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** Pure FQCV-001–006, wired only after the independent compound-value v1 freeze.
 * All expected fields are literal frozen inputs, never projector/checker outputs.
 * Fixture signatures/IDs establish no source, tax, native or execution authority. */
class FoodQuotationCompoundValuesFrozenTest {
    @Test
    fun fqcv001_twoOrderedRoutesProjectAndCheckEveryFrozenFact() {
        val q = quotation()
        val qBefore = quoteBytes(q)
        val routes = routes()
        val suffix = suffix()
        val projected = project(q, routes, suffix)
        assertEquals(expected(), projected)
        val projectedBefore = snapshotBytes(projected)
        assertEquals(expected(), FoodRecipeRouting.requireOriginalFacts(q, projected))
        assertEquals(qBefore, quoteBytes(q))
        assertEquals(projectedBefore, snapshotBytes(projected))
        assertEquals(routes(), routes)
        assertEquals(suffix(), suffix)
    }

    @Test
    fun fqcv001_reversedRoutesRefuseWithoutInputMutation() {
        val q = quotation()
        val qBefore = quoteBytes(q)
        val reversed = routes().reversed()
        val before = reversed.toList()
        val sources = suffix()
        assertFails { project(q, reversed, sources) }
        println("FQCV-001 REFUSED fromQuotation construction/projection; checker UNEXECUTED")
        assertEquals(qBefore, quoteBytes(q))
        assertEquals(before, reversed)
        assertEquals(suffix(), sources)
    }

    @Test
    fun fqcv002_duplicateStationRefusesWithoutInputMutation() {
        val q = quotation()
        val qBefore = quoteBytes(q)
        val duplicate = listOf(routes()[0], routes()[1].copy(stationId = u(6140)))
        val before = duplicate.toList()
        val sources = suffix()
        assertFails { project(q, duplicate, sources) }
        println("FQCV-002 REFUSED fromQuotation construction/projection; checker UNEXECUTED")
        assertEquals(qBefore, quoteBytes(q))
        assertEquals(before, duplicate)
        assertEquals(suffix(), sources)
    }

    @Test
    fun fqcv003_reversedNonemptyModifierFactsRefuseAtCorrespondence() {
        val q = quotation()
        refuseCandidate("FQCV-003", q) {
            expected().copy(modifierIds = listOf(u(6121), u(6120)))
        }
    }

    @Test
    fun fqcv004_sameSkuComponentsRemainSeparateAndEqualTotalCollapseRefuses() {
        val q = quotation()
        val qBefore = quoteBytes(q)
        val projected = project(q)
        assertEquals(expected(), projected)
        assertEquals(listOf(u(6110), u(6111), u(6112)), projected.ingredients.map { it.componentId })
        assertEquals(listOf(u(6106), u(6106)), projected.ingredients.drop(1).map { it.skuId })
        assertEquals(listOf(quantity(22, 120), quantity(22, 30)), projected.ingredients.drop(1).map { it.requiredBaseQuantity })
        assertEquals(150L, projected.ingredients.drop(1).sumOf { it.requiredBaseQuantity.scaled })
        val collapsedIngredients = listOf(
            ingredients()[0], ingredients()[1].copy(quantityPerYield = quantity(22, 150),
                requiredBaseQuantity = quantity(22, 150)))
        assertEquals(150L, collapsedIngredients.drop(1).sumOf { it.requiredBaseQuantity.scaled })
        refuseCandidate("FQCV-004", q) { expected().copy(ingredients = collapsedIngredients) }
        assertEquals(qBefore, quoteBytes(q))
    }

    @Test
    fun fqcv005_lawfulAlternativeStationVersionRetainsItsOwnExactSuffix() {
        val q = quotation()
        val qBefore = quoteBytes(q)
        val alternative = suffix(alternative = true)
        val projected = project(q, routeSources = alternative)
        assertEquals(expected(alternative = true), projected)
        val before = snapshotBytes(projected)
        assertEquals(expected(alternative = true), FoodRecipeRouting.requireOriginalFacts(q, projected))
        assertEquals(u(6153), projected.sources.last().id)
        assertEquals(Revision(2), projected.sources.last().revision)
        assertEquals(snapshotId, projected.snapshotId)
        assertEquals(before, snapshotBytes(projected))
        assertEquals(qBefore, quoteBytes(q))
        assertEquals(suffix(alternative = true), alternative)
    }

    @Test
    fun fqcv006_projectionRetainsFactsAfterEachIndependentInputHandleChanges() {
        repeat(6) { selected ->
            val modifiers = modifiers().toMutableList()
            val ingredients = ingredients().toMutableList()
            val keys = keys().toMutableList()
            val originalSources = originalSources().toMutableList()
            val routes = routes().toMutableList()
            val suffix = suffix().toMutableList()
            val handles: List<MutableList<*>> = listOf(modifiers, ingredients, keys, originalSources, routes, suffix)
            val before = handles.map { it.toList() }
            val q = quotation(modifiers, ingredients, keys, originalSources)
            val qBefore = quoteBytes(q)
            val projected = project(q, routes, suffix)
            assertEquals(expected(), projected)
            handles.forEachIndexed { index, handle -> assertEquals(before[index], handle.toList()) }
            assertEquals(qBefore, quoteBytes(q))
            handles[selected].clear()
            assertEquals(expected(), projected, "projection input " + selected)
            assertEquals(qBefore, quoteBytes(q))
            assertExposedSnapshotRetained(projected)
        }
    }

    @Test
    fun fqcv006_checkerRetainsSeparatelyMutableCandidateLists() {
        repeat(4) { selected ->
            val q = quotation()
            val qBefore = quoteBytes(q)
            val modifiers = modifiers().toMutableList()
            val ingredients = ingredients().toMutableList()
            val routes = routes().toMutableList()
            val sources = (originalSources() + suffix()).toMutableList()
            val candidate = expected().copy(modifierIds = modifiers, ingredients = ingredients,
                routes = routes, sources = sources)
            val handles: List<MutableList<*>> = listOf(modifiers, ingredients, routes, sources)
            val before = handles.map { it.toList() }
            val checked = FoodRecipeRouting.requireOriginalFacts(q, candidate)
            assertEquals(expected(), checked)
            assertEquals(expected(), candidate)
            handles.forEachIndexed { index, handle -> assertEquals(before[index], handle.toList()) }
            assertEquals(qBefore, quoteBytes(q))
            handles[selected].clear()
            assertEquals(expected(), checked, "checker input " + selected)
            assertEquals(qBefore, quoteBytes(q))
            assertExposedSnapshotRetained(checked)
        }
    }

    @Test
    fun fqcv006_preparedSetRetainsOriginalKeysAfterClearAndSubstitution() {
        repeat(2) { selected ->
            val q = quotation()
            val qBefore = quoteBytes(q)
            val candidate = expected()
            val before = snapshotBytes(candidate)
            val checked = FoodRecipeRouting.requireOriginalFacts(q, candidate)
            assertEquals(qBefore, quoteBytes(q))
            assertEquals(before, snapshotBytes(candidate))
            val checkedBefore = snapshotBytes(checked)
            val input = resources().toMutableSet()
            val prepared = PreparedFoodRecipeRouting(scope, checked, input)
            assertEquals(resources(), input)
            assertEquals(before, snapshotBytes(candidate))
            assertEquals(checkedBefore, snapshotBytes(checked))
            assertEquals(qBefore, quoteBytes(q))
            assertEquals(scope, prepared.scope)
            assertEquals(expected(), prepared.snapshot)
            assertEquals(resources(), prepared.recoveryResources)
            if (selected == 0) input.clear() else {
                input.remove(RecoveryResourceKey(RecoveryResourceKind.STOCK, u(6106)))
                input.add(RecoveryResourceKey(RecoveryResourceKind.STOCK, u(6160)))
            }
            assertEquals(resources(), prepared.recoveryResources)
            assertExposedSnapshotRetained(prepared.snapshot)
            runCatching { (prepared.recoveryResources as MutableSet<RecoveryResourceKey>).clear() }
            assertEquals(resources(), prepared.recoveryResources)
            runCatching { (prepared.recoveryResources as MutableSet<RecoveryResourceKey>)
                .add(RecoveryResourceKey(RecoveryResourceKind.STOCK, u(6160))) }
            assertEquals(resources(), prepared.recoveryResources)
            assertEquals(expected(), prepared.snapshot)
            assertEquals(checkedBefore, snapshotBytes(checked))
            assertEquals(before, snapshotBytes(candidate))
            assertEquals(qBefore, quoteBytes(q))
        }
    }

    /** Constructor refusal is allowed by the freeze, but cannot execute the checker. */
    private fun refuseCandidate(caseId: String, q: LocalFoodRecipeQuotationV1,
        construct: () -> LocalFoodRecipeSnapshotV1) {
        val qBefore = quoteBytes(q)
        val candidate = try { construct() } catch (_: Throwable) {
            assertEquals(qBefore, quoteBytes(q))
            println(caseId + " REFUSED public candidate construction; checker UNEXECUTED")
            return
        }
        val before = snapshotBytes(candidate)
        assertFails { FoodRecipeRouting.requireOriginalFacts(q, candidate) }
        assertEquals(before, snapshotBytes(candidate))
        assertEquals(qBefore, quoteBytes(q))
        println(caseId + " REFUSED requireOriginalFacts; candidate construction completed")
    }

    private fun assertExposedSnapshotRetained(snapshot: LocalFoodRecipeSnapshotV1) {
        val lists: List<List<*>> = listOf(snapshot.modifierIds, snapshot.ingredients, snapshot.routes, snapshot.sources)
        for (list in lists) {
            runCatching { (list as MutableList<*>).clear() }
            assertEquals(expected(), snapshot)
            runCatching { (list as MutableList<*>).reverse() }
            assertEquals(expected(), snapshot)
            @Suppress("UNCHECKED_CAST")
            runCatching { (list as MutableList<Any?>).add(list.first()) }
            assertEquals(expected(), snapshot)
        }
    }

    private fun project(q: LocalFoodRecipeQuotationV1, routeValues: List<LocalFoodStationRouteV1> = routes(),
        routeSources: List<LocalFoodSourceReferenceV1> = suffix()): LocalFoodRecipeSnapshotV1 =
        FoodRecipeRouting.fromQuotation(q, u(6150), u(6160), routeValues, routeSources)

    private fun quotation(modifiers: List<DomainId> = modifiers(), components: List<LocalFoodIngredientV1> = ingredients(),
        routeKeys: List<DomainId> = keys(), sources: List<LocalFoodSourceReferenceV1> = originalSources()): LocalFoodRecipeQuotationV1 =
        LocalFoodRecipeQuotationV1(DomainId("d91edfd7-e7d6-4239-f8e4-5851064b81cd"), u(6180),
            LocalSalesActivity.RESTAURANT, LocalSalesChannel.DINE_IN, u(6100), Revision(5), u(6101),
            u(6102), Revision(3), u(6104), modifiers, "Burger", quantity(20, 1), quantity(20, 1), u(6103),
            components, routeKeys, SIGNED_PUBLICATION, sources)

    /** Independent full snapshot constructor facts, never a projector-derived oracle. */
    private fun expected(alternative: Boolean = false): LocalFoodRecipeSnapshotV1 =
        LocalFoodRecipeSnapshotV1(snapshotId, u(6180), u(6150), LocalSalesActivity.RESTAURANT,
            LocalSalesChannel.DINE_IN, u(6100), Revision(5), u(6101), u(6102), Revision(3), u(6104),
            modifiers(), "Burger", quantity(20, 1), quantity(20, 1), u(6103), ingredients(), u(6160),
            routes(), SIGNED_PUBLICATION, originalSources() + suffix(alternative))

    private fun modifiers(): List<DomainId> = listOf(u(6120), u(6121))
    private fun keys(): List<DomainId> = listOf(u(6130), u(6131))
    private fun ingredients(): List<LocalFoodIngredientV1> = listOf(
        LocalFoodIngredientV1(u(6110), u(6105), Revision(2), quantity(21, 1), quantity(21, 1)),
        LocalFoodIngredientV1(u(6111), u(6106), Revision(4), quantity(22, 120), quantity(22, 120)),
        LocalFoodIngredientV1(u(6112), u(6106), Revision(4), quantity(22, 30), quantity(22, 30)))
    private fun routes(): List<LocalFoodStationRouteV1> = listOf(
        LocalFoodStationRouteV1(u(6130), u(6140), Revision(3), FoodStationDeliveryMode.DISPLAY),
        LocalFoodStationRouteV1(u(6131), u(6141), Revision(4), FoodStationDeliveryMode.PAPER))
    private fun originalSources(): List<LocalFoodSourceReferenceV1> = listOf(
        LocalFoodSourceReferenceV1("local.food.publication", u(6101), Revision(1),
            PayloadDigest("d325c6c6f1ca3a3683ffcd3a607abdda324a872afb3985133d3613c2af9d0d51"), u(10), u(6170), OperationId(u(6171).value)),
        LocalFoodSourceReferenceV1("local.catalog.publication", u(6172), Revision(1),
            PayloadDigest("a".repeat(64)), u(10), u(6170), null))
    private fun suffix(alternative: Boolean = false): List<LocalFoodSourceReferenceV1> = listOf(
        reference(6150, 1, 'b'), reference(6151, 1, 'c'),
        if (alternative) reference(6153, 2, 'e') else reference(6152, 1, 'd'))
    private fun reference(id: Int, revision: Long, digest: Char): LocalFoodSourceReferenceV1 =
        LocalFoodSourceReferenceV1("local.food.configuration-version", u(id), Revision(revision),
            PayloadDigest(digest.toString().repeat(64)), u(10), null, OperationId(u(id).value))
    private fun resources(): Set<RecoveryResourceKey> = setOf(
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, u(10)),
        RecoveryResourceKey(RecoveryResourceKind.STOCK, u(6106)),
        RecoveryResourceKey(RecoveryResourceKind.ORDER_CUSTODY, snapshotId))
    private fun quoteBytes(q: LocalFoodRecipeQuotationV1): StoredBytes =
        localSalesEncode(q, LocalFoodRecipeQuotationV1.serializer(), FoodQuotationStorageV1.MAX_BYTES)
    private fun snapshotBytes(snapshot: LocalFoodRecipeSnapshotV1): StoredBytes =
        localSalesEncode(snapshot, LocalFoodRecipeSnapshotV1.serializer(), FoodQuotationStorageV1.MAX_BYTES)
    private fun u(number: Int): DomainId = DomainId("00000000-0000-0000-0000-" + number.toString().padStart(12, '0'))
    private fun quantity(unit: Int, value: Long): Quantity = Quantity(u(unit), value, 0)
    private val scope: BranchScope = BranchScope(u(1), u(2), u(3))
    private val snapshotId: DomainId = DomainId("7e2cef51-233c-99f0-d6c2-77a0ca210694")

    private companion object {
        const val SIGNED_PUBLICATION: String = """{"definition":{"company":{"organizationId":"00000000-0000-0000-0000-000000000001","companyId":"00000000-0000-0000-0000-000000000002"},"publicationId":"00000000-0000-0000-0000-000000006101","recipeId":"00000000-0000-0000-0000-000000006100","revision":"5","outputSkuId":"00000000-0000-0000-0000-000000006102","outputSkuRevision":"3","yieldQuantity":{"unitId":"00000000-0000-0000-0000-000000000020","scaled":"1","scale":0},"portionQuantity":{"unitId":"00000000-0000-0000-0000-000000000020","scaled":"1","scale":0},"choices":[{"id":"00000000-0000-0000-0000-000000006104","names":{"english":"Burger","arabic":"برجر"},"modifierIds":["00000000-0000-0000-0000-000000006120","00000000-0000-0000-0000-000000006121"],"ingredients":[{"componentId":"00000000-0000-0000-0000-000000006110","skuId":"00000000-0000-0000-0000-000000006105","skuRevision":"2","quantityPerYield":{"unitId":"00000000-0000-0000-0000-000000000021","scaled":"1","scale":0}},{"componentId":"00000000-0000-0000-0000-000000006111","skuId":"00000000-0000-0000-0000-000000006106","skuRevision":"4","quantityPerYield":{"unitId":"00000000-0000-0000-0000-000000000022","scaled":"120","scale":0}},{"componentId":"00000000-0000-0000-0000-000000006112","skuId":"00000000-0000-0000-0000-000000006106","skuRevision":"4","quantityPerYield":{"unitId":"00000000-0000-0000-0000-000000000022","scaled":"30","scale":0}}],"routeKeys":["00000000-0000-0000-0000-000000006130","00000000-0000-0000-0000-000000006131"],"pricingSkuId":"00000000-0000-0000-0000-000000006103"}],"active":true},"signingKeyId":"00000000-0000-0000-0000-000000006170","signatureHex":"6767688899cd2051148c968126dbea317a46eb2bf7ac3277054694dc5315af947bf31a7489641f881c2e09afbe7739a1ad12e771686a54f5e5f16aba5e7dc303"}"""
    }
}
