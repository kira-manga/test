package com.ukkera.pos.sales.store.independent.foodroutingpreparationvaluesv2

import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.store.FoodRecipeRouting
import com.ukkera.pos.sales.store.LocalFoodRecipeQuotationV1
import com.ukkera.pos.sales.store.LocalFoodRecipeSnapshotV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.sales.store.PreparedFoodRecipeRouting
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seven frozen food-routing-preparation-values-v2 public value cases only.
 * Dummy publications/references establish no native source, custody, permission,
 * recovery-union completeness, persistence/RPC or whole business acceptance.
 * The incompatible v1 and broader routing-preparation freezes remain unbound.
 */
class FoodRoutingPreparationFrozenValuesTest {
    private val f = FrozenFoodRoutingPreparationValuesInputs

    @Test
    fun FRPV_001_LiteralOriginalFactsAreRetained() {
        val quotation = f.quotation()
        val snapshot = projectA(quotation)
        assertSnapshot(snapshot)
        assertQuotation(quotation)
    }

    @Test
    fun FRPV_002_ChangedRoutingPreservesOriginalQuotationAndPriorSnapshot() {
        val quotation = f.quotation()
        val a = projectA(quotation)
        assertSnapshot(a)
        val b = FoodRecipeRouting.fromQuotation(quotation, f.u(29), f.u(31), f.routesB(), f.sourcesB())
        assertSnapshot(b, routingB = true)
        assertSnapshot(a)
        assertQuotation(quotation)
    }

    @Test
    fun FRPV_003_AllEightLegalActivityChannelPairsAreRetained() {
        for (activity in listOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE)) {
            for (channel in listOf(LocalSalesChannel.DINE_IN, LocalSalesChannel.COUNTER,
                LocalSalesChannel.TAKEAWAY, LocalSalesChannel.DELIVERY)) {
                val quotation = f.quotation(activity, channel)
                assertSnapshot(projectA(quotation), activity = activity, channel = channel)
                assertQuotation(quotation, activity, channel)
            }
        }
    }

    @Test
    fun FRPV_004_InputAndExposedCollectionsCannotChangeRetainedFacts() {
        val modifiers = f.modifiers().toMutableList()
        val ingredients = f.ingredients().toMutableList()
        val routeKeys = f.routeKeys().toMutableList()
        val originalSources = f.originalSources().toMutableList()
        val routes = f.routesA().toMutableList()
        val routingSources = f.sourcesA().toMutableList()
        val quotation = f.quotation(modifierIds = modifiers, ingredientValues = ingredients,
            requiredRouteKeys = routeKeys, sourceValues = originalSources)
        val snapshot = FoodRecipeRouting.fromQuotation(quotation, f.u(21), f.u(30), routes, routingSources)
        val reread = { assertQuotation(quotation); assertSnapshot(snapshot) }
        reread()
        for (holder in listOf<MutableList<*>>(modifiers, ingredients, routeKeys,
            originalSources, routes, routingSources)) {
            holder.reverse()
            reread()
            holder.clear()
            reread()
        }
        for (collection in listOf<() -> List<*>>(
            { quotation.modifierIds }, { quotation.ingredients }, { quotation.routeKeys }, { quotation.sources },
            { snapshot.modifierIds }, { snapshot.ingredients }, { snapshot.routes }, { snapshot.sources },
        )) {
            attemptMutation({ (collection() as MutableList<*>).reverse() }, reread)
            attemptMutation({ (collection() as MutableList<*>).clear() }, reread)
        }
    }

    @Test
    fun FRPV_005_PreparedScopeSnapshotAndResourcesSurviveMutationAttempts() {
        val input = f.resources().toMutableSet()
        val prepared = PreparedFoodRecipeRouting(f.scope(), projectA(f.quotation()), input)
        val unexpected = RecoveryResourceKey(RecoveryResourceKind.STOCK, f.u(31))
        val authority = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, f.u(3))
        val reread = {
            assertEquals(f.scope(), prepared.scope)
            assertSnapshot(prepared.snapshot)
            assertEquals(f.resources(), prepared.recoveryResources)
            assertTrue(unexpected !in prepared.recoveryResources)
        }
        reread()
        input.clear()
        reread()
        input.add(unexpected)
        reread()
        attemptMutation({ (prepared.recoveryResources as MutableSet<RecoveryResourceKey>).remove(authority) }, reread)
        attemptMutation({ (prepared.recoveryResources as MutableSet<RecoveryResourceKey>).add(unexpected) }, reread)
        for (collection in listOf<() -> List<*>>(
            { prepared.snapshot.modifierIds }, { prepared.snapshot.ingredients },
            { prepared.snapshot.routes }, { prepared.snapshot.sources },
        )) {
            attemptMutation({ (collection() as MutableList<*>).clear() }, reread)
        }
    }

    @Test
    fun FRPV_006_AllSixMalformedRouteVariantsRejectWithoutChangingQuotation() {
        val routes = f.routesA()
        val variants = listOf(
            "a wrong order" to listOf(routes[1], routes[0]),
            "b missing key" to listOf(routes[0]),
            "c duplicate key" to listOf(routes[0], routes[1].copy(routeKey = f.u(18))),
            "d wrong key" to listOf(routes[0], routes[1].copy(routeKey = f.u(90))),
            "e duplicate station" to listOf(routes[0], routes[1].copy(stationId = f.u(20))),
            "f nonpositive station revision" to listOf(routes[0].copy(stationRevision = Revision(0)), routes[1]),
        )
        for ((variant, input) in variants) {
            val quotation = f.quotation()
            val result = runCatching {
                FoodRecipeRouting.fromQuotation(quotation, f.u(21), f.u(30), input, f.sourcesA())
            }
            assertTrue(result.isFailure, "FRPV-006 " + variant + " must fail at projection")
            assertNull(result.getOrNull())
            assertQuotation(quotation)
        }
    }

    @Test
    fun FRPV_007_AllEightMalformedReferenceVariantsRejectWithoutChangingQuotation() {
        val variants = listOf(
            "a chosen route absent" to listOf(f.sa23, f.sa22),
            "b station version absent" to listOf(f.ra21, f.sa23),
            "c duplicate station identity" to listOf(f.ra21, f.sa23, f.sa23),
            "d nonpositive storage generation" to listOf(f.ra21.copy(revision = Revision(0)), f.sa23, f.sa22),
            "e publication appended" to listOf(f.ra21, f.sa23, f.c28),
            "f configuration signing key" to listOf(f.ra21.copy(signingKeyId = f.u(24)), f.sa23, f.sa22),
            "g missing operation identity" to listOf(f.ra21.copy(operationId = null), f.sa23, f.sa22),
            "h mismatched operation identity" to listOf(f.ra21.copy(operationId = OperationId(f.u(90).value)), f.sa23, f.sa22),
        )
        for ((variant, input) in variants) {
            val quotation = f.quotation()
            val result = runCatching {
                FoodRecipeRouting.fromQuotation(quotation, f.u(21), f.u(30), f.routesA(), input)
            }
            assertTrue(result.isFailure, "FRPV-007 " + variant + " must fail at projection")
            assertNull(result.getOrNull())
            assertQuotation(quotation)
        }
    }

    private fun projectA(quotation: LocalFoodRecipeQuotationV1): LocalFoodRecipeSnapshotV1 =
        FoodRecipeRouting.fromQuotation(quotation, f.u(21), f.u(30), f.routesA(), f.sourcesA())

    private fun assertQuotation(quotation: LocalFoodRecipeQuotationV1,
        activity: LocalSalesActivity = LocalSalesActivity.RESTAURANT,
        channel: LocalSalesChannel = LocalSalesChannel.DINE_IN) {
        assertEquals(f.quotationId, quotation.quotationId)
        assertEquals(fixedOriginalFacts(activity, channel), listOf(
            quotation.portionId, quotation.activity, quotation.channel, quotation.recipeId, quotation.recipeRevision,
            quotation.publicationId, quotation.outputSkuId, quotation.outputSkuRevision, quotation.choiceId,
            quotation.modifierIds, quotation.description, quotation.yieldQuantity, quotation.portionQuantity,
            quotation.pricingSkuId, quotation.ingredients, quotation.signedPublication,
        ))
        assertSignedBytes(quotation.signedPublication)
        assertEquals(f.routeKeys(), quotation.routeKeys)
        assertEquals(f.originalSources(), quotation.sources)
    }

    private fun assertSnapshot(snapshot: LocalFoodRecipeSnapshotV1, routingB: Boolean = false,
        activity: LocalSalesActivity = LocalSalesActivity.RESTAURANT,
        channel: LocalSalesChannel = LocalSalesChannel.DINE_IN) {
        assertEquals(if (routingB) f.snapshotBId else f.snapshotAId, snapshot.snapshotId)
        assertEquals(f.u(if (routingB) 29 else 21), snapshot.routeVersionId)
        assertEquals(f.u(if (routingB) 31 else 30), snapshot.warehouseId)
        assertEquals(if (routingB) f.routesB() else f.routesA(), snapshot.routes)
        assertEquals(fixedOriginalFacts(activity, channel), listOf(
            snapshot.portionId, snapshot.activity, snapshot.channel, snapshot.recipeId, snapshot.recipeRevision,
            snapshot.publicationId, snapshot.outputSkuId, snapshot.outputSkuRevision, snapshot.choiceId,
            snapshot.modifierIds, snapshot.description, snapshot.yieldQuantity, snapshot.portionQuantity,
            snapshot.pricingSkuId, snapshot.ingredients, snapshot.signedPublication,
        ))
        assertSignedBytes(snapshot.signedPublication)
        assertEquals(f.originalSources(), snapshot.sources.take(3))
        val appended = snapshot.sources.drop(3)
        assertEquals(3, appended.size)
        assertEquals((if (routingB) f.sourcesB() else f.sourcesA()).toSet(), appended.toSet())
    }

    private fun fixedOriginalFacts(activity: LocalSalesActivity, channel: LocalSalesChannel): List<Any> = listOf(
        f.u(4), activity, channel, f.u(6), Revision(11), f.u(5), f.u(7), Revision(7), f.u(8),
        f.modifiers(), f.DESCRIPTION, f.q(13, 400, 2), f.q(13, 150, 2), f.u(9),
        f.ingredients(), f.SIGNED_PUBLICATION,
    )

    private fun assertSignedBytes(actual: String) {
        val bytes = actual.encodeToByteArray()
        assertEquals(1533, bytes.size)
        assertContentEquals(f.SIGNED_PUBLICATION.encodeToByteArray(), bytes)
    }

    private fun attemptMutation(action: () -> Unit, reread: () -> Unit) {
        runCatching(action)
        reread()
    }
}
