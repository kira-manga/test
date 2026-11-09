package com.ukkera.pos.database.independent.kitchenrpcworkflowv1

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.LocalMasterClientRpcProtocolV1
import com.ukkera.pos.database.LocalMasterKitchenRpcKeysV1
import com.ukkera.pos.database.LocalMasterKitchenRpcProtocolV1
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StockStreamingCommandsV2
import dev.p2pkit.rpc.RpcRetrySafety
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * KRPC-001 / F-RPC-SURFACE declaration support only. The frozen kitchen RPC v1
 * routes reuse the existing carriers and bounds. No Host, admitted Client, SDK
 * call, automatic-retry observation, native source or Master authority is supplied.
 * Key selection does not validate the shared keys' selectors or action bodies.
 */
class KitchenRpcV1ProcedureSupportingTest {
    @Test
    fun krpc001FixedKitchenRoutesRetainV1CarriersBoundsAndNeverReinvoke() {
        val kitchen = LocalMasterKitchenRpcProtocolV1
        val business = LocalMasterClientRpcProtocolV1
        val routes = listOf(kitchen.READ, kitchen.EXECUTE, kitchen.LOOKUP)
        assertEquals(listOf("pos.kitchen.read/1", "pos.kitchen.execute/1", "pos.kitchen.lookup/1"),
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
    fun krpc001ReadKeySelectionKeepsTheExactStationSurfaceAndVersions() {
        val allowed = listOf(ReadKey("local.food.station-directory", 1), ReadKey("sales.food.read", 1),
            ReadKey("stock.normalized.station-profile", 2))
        allowed.forEach { key ->
            assertTrue(LocalMasterKitchenRpcKeysV1.accepts(key), key.toString())
            assertFalse(LocalMasterKitchenRpcKeysV1.accepts(ReadKey(key.name, key.version + 1)), key.toString())
        }
        // R04–R08 are existing financial/report/cost/inventory/broad-profile keys.
        // R01–R03 share the allowed FOOD key and require the typed selector checks.
        listOf(ReadKey("sales.food.financial.read", 1), ReadKey("sales.branch-report.read", 1),
            ReadKey("stock.costs", 1), ReadKey("stock.quantities", 1),
            ReadKey("stock.normalized.profile", 2), ReadKey("stock.normalized.history", 2),
            ReadKey("local.configuration.read", 1)).forEach { key ->
            assertFalse(LocalMasterKitchenRpcKeysV1.accepts(key), key.toString())
        }
    }

    @Test
    fun krpc001CommandKeySelectionKeepsBothOriginalFoodFormatsOnly() {
        val legacy = CommandKey("sales.food.write", 1)
        val normalized = StockStreamingCommandsV2.FOOD
        assertTrue(LocalMasterKitchenRpcKeysV1.accepts(legacy))
        assertTrue(LocalMasterKitchenRpcKeysV1.accepts(normalized))
        assertFalse(LocalMasterKitchenRpcKeysV1.accepts(CommandKey(legacy.name, legacy.version + 1)))
        assertFalse(LocalMasterKitchenRpcKeysV1.accepts(CommandKey(normalized.name, normalized.version + 1)))
        (StockStreamingCommandsV2.all - normalized + CommandKey("sales.food.financial", 1)).forEach { key ->
            assertFalse(LocalMasterKitchenRpcKeysV1.accepts(key), key.toString())
        }
        // START/READY exclusions within these two shared keys are a separate typed-body check.
    }
}
