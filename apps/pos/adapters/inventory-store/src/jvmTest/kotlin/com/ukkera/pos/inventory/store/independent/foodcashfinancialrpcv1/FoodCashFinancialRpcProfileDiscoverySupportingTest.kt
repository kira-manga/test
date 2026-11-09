package com.ukkera.pos.inventory.store.independent.foodcashfinancialrpcv1

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*

/** Frozen FFRC-016/CF v1.0.0 installed classifier and normalization SUPPORT only.
 * No native activation, protected quantity grant, successful profile payload or
 * inventory/business side-effect qualification is provided by these methods. */
class FoodCashFinancialRpcProfileDiscoverySupportingTest {
    private val f get() = FoodCashFinancialRpcDiscoveryInputs
    private val profile = LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL
    private fun reads(): List<MasterReadHandler> = LocalStockRelationalServices(LocalStockRepository(),
        UnavailableLocalStockAuthority).reads

    @Test
    fun ffrc016InstalledDiscoveryAdmitsExactlyEmptyNormalizedProfileV2() {
        assertEquals("INVENTORY_QUANTITY_READ", f.root.getValue("grants").jsonObject
            .getValue("quantity_discovery").jsonPrimitive.content)
        val request = LocalStockRelationalQueriesV2.profile()
        val handler = reads().single { it.key == request.query }
        assertEquals(ReadKey("stock.normalized.profile", 2), handler.key)
        assertEquals(0, request.payload.size)
        assertEquals(request.payload, handler.normalize(request.payload))
        assertTrue(LocalMasterFoodCashFinancialRpcKeysV1.accepts(handler.key))
        for (candidate in LocalMasterRpcWorkflowProfile.entries) {
            assertEquals(candidate == profile || candidate == LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS,
                handler.supportsRpcWorkflow(candidate, request.payload), candidate.name)
        }
        assertContentEquals(byteArrayOf(), request.payload.copyBytes())
    }

    @Test
    fun ffrc016NonemptyStationHistoryAndOversizedBodiesCannotBecomeDiscovery() {
        val handler = reads().single { it.key == LocalStockRelationalQueriesV2.PROFILE }
        val bodies = listOf(StoredBytes(byteArrayOf(0)), f.bytes("{}"),
            f.bytes("{\"profile\":\"FOOD_CASH_FINANCIAL\"}"), LocalStockCommands.quantities(f.id("sku")).payload,
            LocalStockRelationalQueriesV2.stationProfile(f.id("station")).payload,
            LocalStockRelationalQueriesV2.history(f.id("sku"), f.id("warehouse")).payload,
            StoredBytes(ByteArray(2_049) { 0x20.toByte() }))
        for (body in bodies) {
            val before = body.copyBytes()
            assertFalse(handler.supportsRpcWorkflow(profile, body))
            assertFailsWith<LocalStockRejected> { handler.normalize(body) }
            assertContentEquals(before, body.copyBytes())
        }
    }

    @Test
    fun ffrc016OtherKeysAndVersionsHaveNoFinancialDiscoveryFallback() {
        val installed = reads().associateBy { it.key }
        for (request in listOf(LocalStockRelationalQueriesV2.stationProfile(f.id("station")),
            LocalStockRelationalQueriesV2.history(f.id("sku"), f.id("warehouse")))) {
            val handler = installed.getValue(request.query)
            assertEquals(request.payload, handler.normalize(request.payload))
            assertFalse(handler.supportsRpcWorkflow(profile, request.payload))
            assertFalse(LocalMasterFoodCashFinancialRpcKeysV1.accepts(request.query))
        }
        for (key in listOf(ReadKey("stock.normalized.profile", 1), ReadKey("stock.normalized.profile", 3),
            LocalStockStoreProfile.ITEMS, LocalStockStoreProfile.QUANTITIES, LocalStockStoreProfile.COSTS)) {
            assertFalse(LocalMasterFoodCashFinancialRpcKeysV1.accepts(key), key.toString())
        }
    }
}

/** Semantic synthetic IDs only, never stock source, authenticated actor or authority. */
private object FoodCashFinancialRpcDiscoveryInputs {
    private val directory = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/food-financial-rpc-v1") }
        .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
        ?: error("Frozen FOOD financial RPC inputs are unavailable")
    private val freeze = Json.parseToJsonElement(Files.readString(directory.resolve("freeze.json"))).jsonObject
    private val fixtureBytes = Files.readAllBytes(directory.resolve("fixtures.json"))
    val root = Json.parseToJsonElement(fixtureBytes.decodeToString()).jsonObject.also {
        assertEquals("1.0.0", freeze.getValue("version").jsonPrimitive.content)
        assertEquals("FROZEN", freeze.getValue("freeze_state").jsonPrimitive.content)
        assertEquals("food-financial-rpc-v1.0.0", it.getValue("fixture_version").jsonPrimitive.content)
        val frozen = freeze.getValue("frozen_files").jsonObject.getValue("fixtures.json").jsonObject
        assertEquals(frozen.getValue("bytes").jsonPrimitive.int, fixtureBytes.size)
        assertEquals(frozen.getValue("sha256").jsonPrimitive.content, sha256(fixtureBytes).value)
    }
    fun id(label: String): DomainId = DomainId(UUID.nameUUIDFromBytes(
        "food-financial-rpc-v1-discovery-support:$label".encodeToByteArray()).toString())
    fun bytes(value: String): StoredBytes = StoredBytes(value.encodeToByteArray())
}
