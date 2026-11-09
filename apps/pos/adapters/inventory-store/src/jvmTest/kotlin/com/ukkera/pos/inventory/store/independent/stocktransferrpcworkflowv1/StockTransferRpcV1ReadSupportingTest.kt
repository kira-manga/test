package com.ukkera.pos.inventory.store.independent.stocktransferrpcworkflowv1

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Frozen stock-transfer-rpc-workflow-v1 value-independent SUPPORT ONLY slices.
 * These methods normalize/classify public inputs and decode controlled projections;
 * they do not prepare a business mutation, grant authority, read native storage or
 * qualify production transfer/compatibility. The v1 positive Transfer reason is
 * not changed to make its public construction succeed.
 */
class StockTransferRpcV1ReadSupportingTest {
    private val input get() = StockTransferRpcV1ReadInputs

    @Test
    fun s01LegacyKeysCannotSelectTheNormalizedExecuteAttachment() {
        val expected = input.root.getValue("constants").jsonObject.text("execute_command_key")
        val normalized = LocalStockRelationalProfileV2.COMMAND
        assertEquals(expected, "${normalized.name}/${normalized.version}")
        assertTrue(LocalMasterStockTransferRpcKeysV1.accepts(normalized))
        for (legacy in listOf(LocalStockStoreProfile.TRANSFER, LocalStockStoreProfile.TRANSFER_SERIAL,
            LocalStockStoreProfile.TRANSFER_OWNED)) {
            assertFalse(LocalMasterStockTransferRpcKeysV1.accepts(legacy))
        }
        val installed = LocalStockHandlers(LocalStockRepository(), UnavailableLocalStockAuthority).commands
        assertEquals(setOf(normalized), installed.map { it.key }.filter(LocalMasterStockTransferRpcKeysV1::accepts).toSet())
        // S01-LEGACY key-selection slice: no legacy body is executed or compatibility claimed.
    }

    @Test
    fun s02AllowedStockReadFamiliesNormalizeOnlyForTheFixedProfile() {
        val repository = LocalStockRepository()
        val handlers = (LocalStockHandlers(repository, UnavailableLocalStockAuthority).reads +
            LocalStockRelationalServices(repository, UnavailableLocalStockAuthority).reads).associateBy { it.key }
        val permitted = input.readRequests()
        for (request in permitted) {
            val handler = handlers.getValue(request.query)
            val normalized = handler.normalize(request.payload)
            assertEquals(request.payload, normalized)
            assertTrue(LocalMasterStockTransferRpcKeysV1.accepts(request.query))
            assertTrue(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS, normalized))
            // FFRC-016/CF v1.0.0 extends only the exact empty normalized profile /2 discovery.
            val financialDiscovery = request.query == LocalStockRelationalQueriesV2.PROFILE && normalized.size == 0
            if (financialDiscovery)
                assertTrue(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL, normalized))
            for (other in LocalMasterRpcWorkflowProfile.entries.filterNot {
                it == LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS ||
                    financialDiscovery && it == LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL
            })
                assertFalse(handler.supportsRpcWorkflow(other, normalized))
        }
        for (request in listOf(LocalStockCommands.receipt(input.document), LocalStockCommands.receiptCosts(input.document),
            LocalStockCommands.count(input.document), LocalStockRelationalQueriesV2.stationProfile(input.document))) {
            val handler = handlers.getValue(request.query)
            val normalized = handler.normalize(request.payload)
            assertFalse(LocalMasterStockTransferRpcKeysV1.accepts(request.query))
            assertFalse(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS, normalized))
        }
        // S02-ALLOWLIST, S02-UNRELATED; configuration and actual authorization are separate seams.
    }

    @Test
    fun s02TruncatedIdentifiersAndIncompatibleReadBodiesAreRefusedNotEmptyResults() {
        val repository = LocalStockRepository()
        val handlers = (LocalStockHandlers(repository, UnavailableLocalStockAuthority).reads +
            LocalStockRelationalServices(repository, UnavailableLocalStockAuthority).reads).associateBy { it.key }
        for (request in input.readRequests().filter { it.payload.size > 0 }) {
            val truncated = request.payload.copyBytes().let { StoredBytes(it.copyOf(it.size - 1)) }
            assertFailsWith<LocalStockRejected> { handlers.getValue(request.query).normalize(truncated) }
        }
        val quantities = LocalStockCommands.quantities(input.sku)
        val malformedId = StoredBytes(quantities.payload.copyBytes().decodeToString()
            .replace(input.sku.value, "not-a-domain-id").encodeToByteArray())
        assertFailsWith<LocalStockRejected> { handlers.getValue(quantities.query).normalize(malformedId) }
        assertFailsWith<LocalStockRejected> {
            handlers.getValue(LocalStockStoreProfile.QUANTITIES).normalize(LocalStockCommands.items().payload)
        }
        assertFailsWith<LocalStockRejected> {
            handlers.getValue(LocalStockRelationalQueriesV2.PROFILE).normalize(quantities.payload)
        }
        // S02-MALFORMED only at typed normalization, not the SDK or native business boundary.
    }

    @Test
    fun s02s06QuantityAndCostCarriersStaySeparateAndMissingMetadataStaysMissing() {
        val quantityWire = input.quantities()
        val quantities = LocalStockQueries.decodeQuantities(stockEncode(quantityWire, StockQuantitiesReadWire.serializer()))
        assertEquals(input.sku, quantities.skuId)
        assertEquals(input.revision, quantities.itemStorageRevision)
        val row = quantities.rows.single()
        assertEquals(input.source, row.position)
        assertEquals(input.quantity(input.sourceInput.text("quantity")), row.onHand)
        assertEquals(input.quantity(input.sourceInput.text("reserved")), row.reserved)
        assertEquals(row.onHand, row.available)
        val encoded = input.wire.encodeToJsonElement(StockQuantitiesReadWire.serializer(), quantityWire).jsonObject
        val leakedCost = JsonObject(encoded + ("carryingValue" to input.wire.encodeToJsonElement(Money.serializer(), input.sourceValue)))
        assertFailsWith<LocalStockRejected> { LocalStockQueries.decodeQuantities(input.bytes(leakedCost)) }

        val costWire = StockCostsReadWire(1, input.sku, input.revision, listOf(StockCostReadWire(
            LocalStockRelationalProfileV2.poolKey(input.source.inScope(input.scope).pool).id,
            input.source.warehouseId, input.revision, Revision(1), row.onHand, input.sourceValue)), null)
        val costs = LocalStockQueries.decodeCosts(stockEncode(costWire, StockCostsReadWire.serializer()))
        assertEquals(input.sourceValue, costs.rows.single().carryingValue)
        assertFailsWith<LocalStockRejected> { LocalStockQueries.decodeQuantities(stockEncode(costWire, StockCostsReadWire.serializer())) }
        assertFailsWith<LocalStockRejected> { LocalStockQueries.decodeCosts(stockEncode(quantityWire, StockQuantitiesReadWire.serializer())) }

        val missing = LocalStockQueries.decodeQuantities(stockEncode(StockQuantitiesReadWire(2,
            input.missingSku, null, null, null, emptyList(), null), StockQuantitiesReadWire.serializer()))
        assertEquals(input.missingSku, missing.skuId)
        assertNull(missing.itemStorageRevision)
        assertNull(missing.unitId)
        assertNull(missing.quantityScale)
        assertTrue(missing.rows.isEmpty())
        assertNull(missing.nextAfter)
        // S02-MISSING/S02-QUANTITYCOST/S06-QUANTITY/S06-COST projection slices, not grants or reads.
    }
}

private object StockTransferRpcV1ReadInputs {
    private val directory = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/stock-transfer-rpc-workflow") }
        .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
        ?: error("Frozen stock-transfer workflow inputs are unavailable")
    val root = Json.parseToJsonElement(Files.readString(directory.resolve("fixtures.json"))).jsonObject.also {
        assertEquals("stock-transfer-rpc-workflow-v1", it.text("design_version"))
        assertEquals("FROZEN", it.text("freeze_state"))
    }
    private val base = root.getValue("fixtures").jsonObject.getValue("F-ST-RPC-BASE").jsonObject
    private val aliases = base.getValue("aliases").jsonObject
    fun id(alias: String) = DomainId(aliases.text(alias))
    val sku = id("SKU1")
    val missingSku = id("SKU_MISSING")
    val document = id("OP1")
    val scope = BranchScope(id("O1"), id("C1"), id("B1"))
    val sourceInput = base.getValue("source_position").jsonObject
    val revision = Revision(sourceInput.text("exact_revision").toLong())
    // Inert typed unit binding only; no catalog/unit authorization is fabricated.
    private val unit = DomainId(UUID.nameUUIDFromBytes("stock-transfer-rpc-support-v1:unit-kg".encodeToByteArray()).toString())
    private val scale = base.getValue("sku").jsonObject.getValue("allowed_fraction_digits").jsonPrimitive.int
    fun quantity(value: String) = Quantity.parse(unit, value, scale)
    val source = LocalStockPositionRef(sku, id(sourceInput.text("warehouse")), id(sourceInput.text("lot")), StockCustody.RELEASED)
    val sourceValue = Money(Currency.EGP, sourceInput.text("carrying_value_minor").toLong())
    val wire = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun bytes(value: JsonElement) = StoredBytes(wire.encodeToString(JsonElement.serializer(), value).encodeToByteArray())
    fun readRequests() = listOf(LocalStockCommands.items(), LocalStockCommands.quantities(sku),
        LocalStockCommands.quantityPosition(sku, LocalStockRelationalProfileV2.positionKey(source.inScope(scope)).id),
        LocalStockCommands.costs(sku), LocalStockCommands.transferDetail(document), LocalStockCommands.transferCosts(document),
        LocalStockRelationalQueriesV2.profile(), LocalStockRelationalQueriesV2.history(sku, source.warehouseId))
    fun quantities() = StockQuantitiesReadWire(2, sku, revision, unit, scale, listOf(StockQuantityReadWire(
        LocalStockRelationalProfileV2.positionKey(source.inScope(scope)).id, StockPositionRefWire.from(source), revision,
        quantity(sourceInput.text("quantity")), quantity(sourceInput.text("reserved")), quantity(sourceInput.text("quantity")),
        StockSafety.CLEAR.name, UtcInstant.parseCanonical(sourceInput.text("expiry_utc")), false,
        quantity("0.000"), quantity("0.000"))), null)
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}

private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
