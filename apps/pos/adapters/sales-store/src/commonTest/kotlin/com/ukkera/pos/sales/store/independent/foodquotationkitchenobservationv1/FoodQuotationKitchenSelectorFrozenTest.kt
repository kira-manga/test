package com.ukkera.pos.sales.store.independent.foodquotationkitchenobservationv1

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV2
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenStatusV1
import com.ukkera.pos.sales.store.LocalFoodQueryV1
import com.ukkera.pos.sales.store.localSalesDecode
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** FQKO-012 enum-decoder and FQKO-013 request-only slices from the immutable v1
 * specification. These do not create Q10, observe its protected revision/count,
 * execute a Master read or qualify any whole FQKO case. */
class FoodQuotationKitchenSelectorFrozenTest {
    private val order = DomainId("30000000-0000-4000-8000-000000000010")
    private val member = DomainId("40000000-0000-4000-8000-000000010001")

    @Test
    fun fqko013_validOrderLimitsAndExactCursorSurviveBothRequestCodecs() {
        for (limit in listOf(1, 8)) for (cursor in listOf(null, member)) {
            val query = LocalFoodQueryV1.Order(order, cursor, limit)
            val first = LocalFoodCheckKitchenCodecV1.encode(query)
            val second = LocalFoodCheckKitchenCodecV2.encode(query)
            assertEquals(query, LocalFoodCheckKitchenCodecV1.query(first))
            assertEquals(query, LocalFoodCheckKitchenCodecV2.query(second))
            assertEquals(first, second)
            assertEquals(first, LocalFoodCheckKitchenCodecV1.encode(LocalFoodCheckKitchenCodecV1.query(first)))
            assertEquals(second, LocalFoodCheckKitchenCodecV2.encode(LocalFoodCheckKitchenCodecV2.query(second)))
        }
    }

    @Test
    fun fqko013_zeroAndNineLimitsRefuseRequestNormalization() {
        for (limit in listOf(0, 9)) {
            val query = LocalFoodQueryV1.Order(order, null, limit)
            assertFails { LocalFoodCheckKitchenCodecV1.encode(query) }
            assertFails { LocalFoodCheckKitchenCodecV2.encode(query) }
            val raw = bytes("""{"type":"ORDER","orderId":"${order.value}","limit":$limit}""")
            assertFails { LocalFoodCheckKitchenCodecV1.encode(LocalFoodCheckKitchenCodecV1.query(raw)) }
            assertFails { LocalFoodCheckKitchenCodecV2.encode(LocalFoodCheckKitchenCodecV2.query(raw)) }
        }
    }

    @Test
    fun fqko013_malformedIdentityCursorAndNonOrderSelectorsRefuse() {
        val candidates = listOf(
            """{"type":"ORDER","orderId":"not-a-uuid","limit":1}""",
            """{"type":"ORDER","orderId":"{${order.value}}","limit":1}""",
            """{"type":"ORDER","orderId":"${order.value}","afterPortionId":"not-a-uuid","limit":1}""",
            """{"type":"OWN","limit":1}""",
        )
        for (candidate in candidates) {
            val raw = bytes(candidate)
            assertFails { LocalFoodCheckKitchenCodecV1.encode(LocalFoodCheckKitchenCodecV1.query(raw)) }
            assertFails { LocalFoodCheckKitchenCodecV2.encode(LocalFoodCheckKitchenCodecV2.query(raw)) }
            assertEquals(candidate, raw.copyBytes().decodeToString())
        }
    }

    @Test
    fun fqko012_unsupportedAvailableCannotDecodeAsDiagnosticStatus() {
        val raw = bytes("\"AVAILABLE\"")
        assertFails { localSalesDecode(raw, LocalFoodCheckKitchenStatusV1.serializer()) }
        assertEquals("\"AVAILABLE\"", raw.copyBytes().decodeToString())
    }

    private fun bytes(value: String): StoredBytes = StoredBytes(value.encodeToByteArray())
}
