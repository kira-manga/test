package com.ukkera.pos.database.independent.stocktransferrpcworkflowv1

import com.ukkera.pos.database.LocalMasterClientRpcProtocolV1
import com.ukkera.pos.database.LocalMasterStockTransferRpcProtocolV1
import dev.p2pkit.rpc.RpcRetrySafety
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * ST-RPC-S01 / S01-FIXED and the descriptor slice of S01-CARRIER, SUPPORT ONLY.
 * The frozen stock-transfer-rpc-workflow-v1 names are checked without an SDK call,
 * registered Host, admitted Client, original native lifetime or Master authority.
 * This does not execute carrier limits or qualify legacy retained-format compatibility.
 */
class StockTransferRpcV1ProcedureSupportingTest {
    @Test
    fun s01FixedStockTransferRolesReuseTheOriginalV1CarriersAndNeverReinvoke() {
        val original = LocalMasterClientRpcProtocolV1
        val attached = LocalMasterStockTransferRpcProtocolV1
        val routes = listOf(attached.READ, attached.EXECUTE, attached.LOOKUP)
        assertEquals(listOf("pos.stock-transfer.read/1", "pos.stock-transfer.execute/1",
            "pos.stock-transfer.lookup/1"), routes.map { "${it.name}/${it.version}" })
        for ((route, baseline) in routes.zip(listOf(original.READ, original.EXECUTE, original.LOOKUP))) {
            assertSame(baseline.request, route.request)
            assertSame(baseline.response, route.response)
            assertSame(baseline.applicationError, route.applicationError)
            assertEquals(RpcRetrySafety.NeverReinvoke, route.retrySafety)
            assertEquals(baseline.requestLimitBytes, route.requestLimitBytes)
            assertEquals(baseline.responseLimitBytes, route.responseLimitBytes)
            assertEquals(baseline.errorLimitBytes, route.errorLimitBytes)
        }
    }
}
