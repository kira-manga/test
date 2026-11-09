package com.ukkera.pos.catalog.store.independent.foodrecipequotationv1

import com.ukkera.pos.sales.store.LocalFoodRecipeQuotationV1
import com.ukkera.pos.types.DomainId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

/**
 * Separately versioned PUBLIC UUID route-key assertions: FRQK-C002, the PUBLIC
 * FRQ-009 part of FRQK-C003, and FRQK-C004. Frozen content identity:
 * df1f4dc642cae470c56c2fee6e16de5bdde76fb5638f60d50cdde2b83e6c96be.
 * No prior parent literal assertion is relabeled PASS. FRQK-C001 / SOURCE FRQ-010
 * and the SOURCE FRQ-015 remainder of FRQK-C003 remain UNBOUND. These identities
 * establish no station, branch routing, signing trust, fulfillment or permission.
 */
class FoodRecipeQuotationKeyBindingFrozenPublicSlicesTest {
    private val f = FrozenFoodRecipeQuotationPublicInputs
    private val json = Json { encodeDefaults = true }

    @Test
    fun frqkC002ExactTypedRouteIdentityOrderSurvivesShapeRoundTrip() {
        val expected = listOf(DomainId("01000000-0000-0000-0000-000000000001"),
            DomainId("01000000-0000-0000-0000-000000000002"))
        val value = f.quotation()
        assertEquals(expected, value.routeKeys)
        val encoded = encode(value)
        val decoded = json.decodeFromString(LocalFoodRecipeQuotationV1.serializer(), encoded)
        assertEquals(expected, decoded.routeKeys)
        assertEquals(encoded, encode(decoded))
        assertFalse(value.routeKeys.any { it.value == "hot.main" || it.value == "drink.bar" })
    }

    @Test
    fun frqkC004FiveExactFrozenRouteKeyCardinalityAndDuplicateBindings() {
        val first = DomainId("01000000-0000-0000-0000-000000000001")
        val four = listOf(first, DomainId("01000000-0000-0000-0000-000000000002"),
            DomainId("01000000-0000-0000-0000-000000000003"),
            DomainId("01000000-0000-0000-0000-000000000004"))
        for ((name, routeKeys) in listOf("route_keys-count-1" to listOf(first), "route_keys-count-4" to four)) {
            assertEquals(routeKeys, f.quotation(routeKeys = routeKeys).routeKeys, name)
        }
        assertEquals(f.expectedShapeFacts(), f.facts(f.quotation()))
        for ((name, routeKeys) in listOf("route_keys-count-0" to emptyList<DomainId>(),
            "route_keys-count-5" to four + DomainId("01000000-0000-0000-0000-000000000005"),
            "duplicate-route_keys" to listOf(first, first))) {
            assertFails(name) { f.quotation(routeKeys = routeKeys) }
        }
    }

    @Test
    fun frqkC003PublicCallerAndExposedRouteMutationsRetainOriginalTypedIdentities() {
        val routeKeys = f.routeKeys.toMutableList()
        val value = f.quotation(routeKeys = routeKeys)
        val original = f.facts(value)
        val encoded = encode(value)
        assertEquals(f.expectedShapeFacts(), original)
        val verify = {
            assertEquals(original, f.facts(value))
            assertEquals(listOf(f.hotMain, f.drinkBar), value.routeKeys)
            assertFalse(f.replacementRoute in value.routeKeys)
            assertEquals(encoded, encode(value))
        }
        mutateCallerList(routeKeys, f.replacementRoute, verify)
        attemptExposedMutations({ value.routeKeys }, f.replacementRoute, verify)
        // No preparation, loan closure, captured pricing or SOURCE FRQ-015 is bound.
    }

    private fun encode(value: LocalFoodRecipeQuotationV1): String =
        json.encodeToString(LocalFoodRecipeQuotationV1.serializer(), value)
}
