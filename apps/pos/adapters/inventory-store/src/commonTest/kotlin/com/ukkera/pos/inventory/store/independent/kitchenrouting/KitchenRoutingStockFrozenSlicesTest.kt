package com.ukkera.pos.inventory.store.independent.kitchenrouting

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.store.LocalCountSurplusBasis
import com.ukkera.pos.inventory.store.LocalStockApprovedPurchaseOrderV1
import com.ukkera.pos.inventory.store.LocalStockAuthorityReader
import com.ukkera.pos.inventory.store.LocalStockItemAuthority
import com.ukkera.pos.inventory.store.LocalStockPurchaseOrderReaderV1
import com.ukkera.pos.inventory.store.LocalStockReceivingBasis
import com.ukkera.pos.inventory.store.LocalStockRelationalQueriesV2
import com.ukkera.pos.inventory.store.LocalStockRelationalServices
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.LocalStockSerialRegistration
import com.ukkera.pos.inventory.store.LocalStockWarehouseAuthority
import com.ukkera.pos.types.DomainId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Frozen KRT-P01/P02 public input slices only; no protected read or profile qualification. */
public class KitchenRoutingStockFrozenSlicesTest {
    @Test
    public fun krtP01CanonicalStationRequestConstructors() {
        val fixtures = frozenRoutingStockInputs()
        val requests = listOf("stationA", "stationB").map { alias ->
            val stationId = DomainId(fixtures.getValue("ids").jsonObject.stockText(alias))
            val expectedJson = fixtures.getValue("stationRequests").jsonObject.stockText(alias)
            val request = LocalStockRelationalQueriesV2.stationProfile(stationId)

            assertEquals(fixtures.stockReadKey(), request.query, "KRT-P01/$alias exact query")
            assertContentEquals(
                expectedJson.encodeToByteArray(), request.payload.copyBytes(),
                "KRT-P01/$alias exact canonical UTF-8 payload",
            )
            request
        }
        assertNotEquals(requests[0].payload, requests[1].payload, "KRT-P01 distinct stations")
    }

    @Test
    public fun krtP01MalformedRawRequestsRefuseNormalization() {
        val invalidRows = frozenRoutingStockInputs().getValue("stationRequests").jsonObject
            .getValue("invalid").jsonArray
        for (element in invalidRows) {
            val row = element.jsonObject
            val id = "KRT-P01/${row.stockText("id")}"
            val ports = ForbiddenStockOperationPorts()
            val handler = normalizationOnlyHandler(frozenRoutingStockInputs().stockReadKey(), ports)
            ports.assertUnused(id)

            val result = runCatching {
                handler.normalize(StoredBytes(row.stockText("json").encodeToByteArray()))
            }
            ports.assertUnused(id)
            assertTrue(result.isFailure, "$id must refuse the exact raw input")
            assertNull(result.getOrNull(), "$id has no normalized value")
        }
    }

    @Test
    public fun krtP01MalformedDomainIdsRefuseConstruction() {
        val scalars = frozenRoutingStockInputs().getValue("stationRequests").jsonObject
            .getValue("invalidDomainIdScalars").jsonArray
        for ((index, scalar) in scalars.withIndex()) {
            val result = runCatching {
                LocalStockRelationalQueriesV2.stationProfile(DomainId(scalar.jsonPrimitive.content))
            }
            assertTrue(result.isFailure, "KRT-P01/invalid-domain-id-$index must refuse construction")
            assertNull(result.getOrNull(), "KRT-P01/invalid-domain-id-$index has no request")
        }
    }

    @Test
    public fun krtP02BooleanResponseCodecRefusesFallbacks() {
        val responses = frozenRoutingStockInputs().getValue("profileResponses").jsonObject
        for ((alias, expected) in listOf("qualifiedLegacy" to false, "qualifiedNormalized" to true)) {
            val actual = LocalStockRelationalQueriesV2.normalized(
                StoredBytes(responses.stockText(alias).encodeToByteArray()),
            )
            assertEquals(expected, actual, "KRT-P02/$alias exact Boolean")
        }
        for (element in responses.getValue("invalid").jsonArray) {
            val row = element.jsonObject
            val id = "KRT-P02/${row.stockText("id")}"
            val result = runCatching {
                LocalStockRelationalQueriesV2.normalized(StoredBytes(row.stockText("json").encodeToByteArray()))
            }
            assertTrue(result.isFailure, "$id must fail decoding, not select a route")
            assertNull(result.getOrNull(), "$id has no Boolean value")
        }
    }
}

private fun JsonObject.stockText(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.stockReadKey(): ReadKey = getValue("stationReadKey").jsonObject.let {
    ReadKey(it.stockText("name"), it.getValue("version").jsonPrimitive.int)
}

/** Only the existing public registration and normalize are used; prepare is never entered. */
private fun normalizationOnlyHandler(key: ReadKey, ports: ForbiddenStockOperationPorts): MasterReadHandler =
    LocalStockRelationalServices(
        LocalStockRepository(purchaseOrderReader = ports), ports,
    ).reads.single { it.key == key }

/** Construction dependencies cannot supply a stock/profile/authority result, even by accident. */
private class ForbiddenStockOperationPorts : LocalStockAuthorityReader, LocalStockPurchaseOrderReaderV1 {
    private var calls: Int = 0

    private fun forbidden(): Nothing {
        calls++
        error("KRT-P01 normalization must not invoke an operational stock dependency")
    }

    fun assertUnused(id: String) {
        assertEquals(0, calls, "$id constructor/normalizer must not invoke operational dependencies")
    }

    override suspend fun item(view: CommandReadView, skuId: DomainId): LocalStockItemAuthority? = forbidden()
    override suspend fun warehouse(view: CommandReadView, warehouseId: DomainId): LocalStockWarehouseAuthority? = forbidden()
    override suspend fun receivingBasis(view: CommandReadView, basisId: DomainId): LocalStockReceivingBasis? = forbidden()
    override suspend fun openingBasis(view: CommandReadView, basisId: DomainId): LocalStockReceivingBasis? = forbidden()
    override suspend fun countSurplusBasis(view: CommandReadView, basisId: DomainId): LocalCountSurplusBasis? = forbidden()
    override suspend fun serialRegistration(view: CommandReadView, serialId: DomainId): LocalStockSerialRegistration? = forbidden()
    override suspend fun order(view: CommandReadView, id: DomainId): LocalStockApprovedPurchaseOrderV1? = forbidden()
}
