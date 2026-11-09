package com.ukkera.pos.database.independent.retailorderrpcworkflowv1

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import dev.p2pkit.rpc.RpcRetrySafety
import kotlin.test.*

/**
 * retail-order-rpc-workflow-v1 RO-S-01/02 declaration and inert carrier SUPPORT.
 * These checks bind the fixed routes, existing keys/carriers/bounds and F-BEARER.
 * They supply no Host, authenticated access, protected dispatch, business body
 * validation, native original, retry observation or whole-case qualification.
 */
class RetailOrderRpcV1ProtocolCarrierSupportingTest {
    @Test
    fun roS01FixedRetailRoutesRetainExistingV1CarriersBoundsAndNeverReinvoke() {
        val retail = LocalMasterRetailOrderRpcProtocolV1
        val business = LocalMasterClientRpcProtocolV1
        val routes = listOf(retail.READ, retail.EXECUTE, retail.LOOKUP)
        assertEquals(listOf("pos.retail-order.read/1", "pos.retail-order.execute/1", "pos.retail-order.lookup/1"),
            routes.map { "${it.name}/${it.version}" })
        for ((route, original) in routes.zip(listOf(business.READ, business.EXECUTE, business.LOOKUP))) {
            assertSame(original.request, route.request)
            assertSame(original.response, route.response)
            assertSame(original.applicationError, route.applicationError)
            assertEquals(RpcRetrySafety.NeverReinvoke, route.retrySafety)
            assertEquals(original.requestLimitBytes, route.requestLimitBytes)
            assertEquals(original.responseLimitBytes, route.responseLimitBytes)
            assertEquals(original.errorLimitBytes, route.errorLimitBytes)
        }
    }

    @Test
    fun roS01ReadKeySelectionKeepsSkuAndOwnOrderVersionsWithoutOtherFamilies() {
        val admitted = listOf(ReadKey("local.configuration.read", 1), ReadKey("sales.order.read", 1),
            ReadKey("sales.order.read", 2))
        admitted.forEach { assertTrue(LocalMasterRetailOrderRpcKeysV1.accepts(it), it.toString()) }
        val excluded = listOf(ReadKey("local.configuration.read", 2), ReadKey("sales.order.read", 3),
            ReadKey("sales.cash.read", 1), ReadKey("sales.collection.read", 1), ReadKey("sales.financial-observation.read", 2),
            ReadKey("sales.order.manager-review", 1), ReadKey("sales.branch-report.read", 1),
            ReadKey("stock.costs", 1), ReadKey("stock.quantities", 1), ReadKey("sales.food.read", 1),
            ReadKey("sales.customer-receipt.read", 1))
        excluded.forEach { assertFalse(LocalMasterRetailOrderRpcKeysV1.accepts(it), it.toString()) }
        // Other configuration and HANDOFF share admitted keys; typed classifiers bind those probes.
    }

    @Test
    fun roS01CommandKeySelectionKeepsOnlyTheTwoExistingOrderCarrierFamilies() {
        val legacy = CommandKey("sales.order.write", 1)
        val normalized = StockStreamingCommandsV2.COLLECTION
        assertTrue(LocalMasterRetailOrderRpcKeysV1.accepts(legacy))
        assertTrue(LocalMasterRetailOrderRpcKeysV1.accepts(normalized))
        val excluded = StockStreamingCommandsV2.all - normalized + setOf(
            CommandKey(legacy.name, 2), CommandKey(normalized.name, normalized.version + 1),
            CommandKey("sales.collection.write", 1), CommandKey("sales.collection.write", 2),
            CommandKey("sales.cash.control", 1), CommandKey("sales.return.write", 1),
            CommandKey("local.configuration.write", 1), CommandKey("sales.food.write", 1))
        excluded.forEach { assertFalse(LocalMasterRetailOrderRpcKeysV1.accepts(it), it.toString()) }
        // A selected shared key does not authorize any of its excluded actions.
    }

    @Test
    fun roS02EachTypedRequestOwnsTheExactFrozen52ByteProbe() {
        for ((name, capture) in carrierConstructors()) {
            val source = probe()
            val expected = source.copyOf()
            val captured = capture(source)
            try {
                source.fill(0)
                assertContentEquals(ByteArray(52), source, name)
                captured.useBearer { actual -> assertContentEquals(expected, actual, name) }
            } finally {
                captured.destroy()
                source.fill(0)
                expected.fill(0)
            }
        }
    }

    @Test
    fun roS02EachTypedRequestRefuses51And53BytesAtConstruction() {
        for ((name, capture) in carrierConstructors()) for (size in listOf(51, 53)) {
            val source = ByteArray(size) { (it + 1).toByte() }
            try { assertFailsWith<IllegalArgumentException>("$name/$size") { capture(source) } }
            finally { source.fill(0) }
        }
        // Construction refusal precedes having a request to dispatch; no protected endpoint runs here.
    }

    private fun probe(): ByteArray =
        "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f3031323334"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** The module's decoded-request constructors expose carrier copying, not authenticated decoding. */
    private fun carrierConstructors(): List<Pair<String, (ByteArray) -> LocalMasterRpcOwnedRequestV1>> {
        val operation = OperationId("10000000-0000-4000-8000-000000000201")
        val read = ReadRequest(ReadKey("sales.order.read", 1), StoredBytes(byteArrayOf()))
        val execute = MutationRequest(operation, CommandKey("sales.order.write", 1), StoredBytes(byteArrayOf()))
        val original = OperationIdentity(DomainId("10000000-0000-4000-8000-000000000003"), operation,
            DomainId("10000000-0000-4000-8000-000000000011"), 1, "a".repeat(64))
        return listOf(
            "Read" to { access: ByteArray -> LocalMasterRpcReadRequestV1.decoded(access, read) },
            "Execute" to { access: ByteArray -> LocalMasterRpcExecuteRequestV1.decoded(access, execute) },
            "Lookup" to { access: ByteArray -> LocalMasterRpcLookupRequestV1.decoded(access, original) },
        )
    }
}
