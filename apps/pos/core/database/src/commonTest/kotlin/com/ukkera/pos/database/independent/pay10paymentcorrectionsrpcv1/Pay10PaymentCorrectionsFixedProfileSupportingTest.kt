package com.ukkera.pos.database.independent.pay10paymentcorrectionsrpcv1

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.LocalMasterClientRpcProtocolV1
import com.ukkera.pos.database.LocalMasterKitchenRpcKeysV1
import com.ukkera.pos.database.LocalMasterKitchenRpcProtocolV1
import com.ukkera.pos.database.LocalMasterPaymentCorrectionRpcKeysV1
import com.ukkera.pos.database.LocalMasterPaymentCorrectionRpcProtocolV1
import com.ukkera.pos.database.LocalMasterReportRpcKeysV1
import com.ukkera.pos.database.LocalMasterReportRpcProtocolV1
import com.ukkera.pos.database.LocalMasterRetailOrderRpcKeysV1
import com.ukkera.pos.database.LocalMasterRetailOrderRpcProtocolV1
import com.ukkera.pos.database.LocalMasterStockTransferRpcKeysV1
import com.ukkera.pos.database.LocalMasterStockTransferRpcProtocolV1
import com.ukkera.pos.database.LocalMasterWarehouseRpcProtocolV1
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StockStreamingCommandsV2
import dev.p2pkit.rpc.RpcRetrySafety
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * PC-RPC-P01–P03 / COM-PAY-10-I/E fixed-surface SUPPORT only. These inert
 * selectors/descriptors grant no employee access and do not validate Source or
 * History authorization. No Host, actual Client loan, native source, business
 * mutation, transport or bridge-dispatch observation is supplied.
 */
class Pay10PaymentCorrectionsFixedProfileSupportingTest {
    private val command = CommandKey("sales.payment-correction.write", 1)
    private val query = ReadKey("sales.payment-correction.read", 1)

    @Test
    fun pcRpcP01SupportFixedCorrectionKeysKeepOnlyTheirExactV1Identities() {
        assertTrue(LocalMasterPaymentCorrectionRpcKeysV1.accepts(command))
        assertTrue(LocalMasterPaymentCorrectionRpcKeysV1.accepts(query))
        assertEquals(setOf(command), (1..1000).map { CommandKey(command.name, it) }
            .filter(LocalMasterPaymentCorrectionRpcKeysV1::accepts).toSet())
        assertEquals(setOf(query), (1..1000).map { ReadKey(query.name, it) }
            .filter(LocalMasterPaymentCorrectionRpcKeysV1::accepts).toSet())
        assertFalse(LocalMasterPaymentCorrectionRpcKeysV1.accepts(CommandKey(query.name, 1)))
        assertFalse(LocalMasterPaymentCorrectionRpcKeysV1.accepts(ReadKey(command.name, 1)))
    }

    @Test
    fun pcRpcP02SupportUnrelatedKeysAndProfileSurfacesCannotUseCorrectionKeys() {
        val otherCommands = listOf(CommandKey("local.configuration.write", 1),
            CommandKey("sales.order.write", 1), CommandKey("sales.food.write", 1),
            CommandKey("sales.cash.control", 1), CommandKey("sales.branch-report-export", 1)) +
            StockStreamingCommandsV2.all
        for (key in otherCommands) assertFalse(LocalMasterPaymentCorrectionRpcKeysV1.accepts(key), key.toString())
        val otherQueries = listOf(ReadKey("local.configuration.read", 1), ReadKey("sales.order.read", 1),
            ReadKey("sales.order.read", 2), ReadKey("sales.food.read", 1),
            ReadKey("sales.cash.read", 1), ReadKey("sales.branch-report.read", 1),
            ReadKey("sales.branch-report-export.read", 1), ReadKey("stock.items", 1),
            ReadKey("stock.quantities", 1), ReadKey("stock.costs", 1),
            ReadKey("stock.normalized.profile", 2), ReadKey("stock.normalized.history", 2))
        for (key in otherQueries) assertFalse(LocalMasterPaymentCorrectionRpcKeysV1.accepts(key), key.toString())
        assertFalse(LocalMasterReportRpcKeysV1.accepts(command))
        assertFalse(LocalMasterStockTransferRpcKeysV1.accepts(command))
        assertFalse(LocalMasterKitchenRpcKeysV1.accepts(command))
        assertFalse(LocalMasterRetailOrderRpcKeysV1.accepts(command))
        assertFalse(LocalMasterReportRpcKeysV1.accepts(query))
        assertFalse(LocalMasterStockTransferRpcKeysV1.accepts(query))
        assertFalse(LocalMasterKitchenRpcKeysV1.accepts(query))
        assertFalse(LocalMasterRetailOrderRpcKeysV1.accepts(query))
    }

    @Test
    fun pcRpcP03SupportCorrectionProtocolDeclarationsStaySeparateAndNeverReinvoke() {
        val correction = LocalMasterPaymentCorrectionRpcProtocolV1
        val business = LocalMasterClientRpcProtocolV1
        val routes = listOf(correction.READ, correction.EXECUTE, correction.LOOKUP)
        assertEquals(listOf("pos.payment-correction.read/1", "pos.payment-correction.execute/1",
            "pos.payment-correction.lookup/1"), routes.map { "${it.name}/${it.version}" })
        for ((route, original) in routes.zip(listOf(business.READ, business.EXECUTE, business.LOOKUP))) {
            assertSame(original.request, route.request)
            assertSame(original.response, route.response)
            assertSame(original.applicationError, route.applicationError)
            assertEquals(RpcRetrySafety.NeverReinvoke, route.retrySafety)
            assertEquals(original.requestLimitBytes, route.requestLimitBytes)
            assertEquals(original.responseLimitBytes, route.responseLimitBytes)
            assertEquals(original.errorLimitBytes, route.errorLimitBytes)
        }
        val unrelatedRoutes = listOf(business.READ, business.EXECUTE, business.LOOKUP,
            LocalMasterWarehouseRpcProtocolV1.READ, LocalMasterWarehouseRpcProtocolV1.EXECUTE,
            LocalMasterWarehouseRpcProtocolV1.LOOKUP, LocalMasterReportRpcProtocolV1.READ,
            LocalMasterReportRpcProtocolV1.EXECUTE, LocalMasterReportRpcProtocolV1.LOOKUP,
            LocalMasterStockTransferRpcProtocolV1.READ, LocalMasterStockTransferRpcProtocolV1.EXECUTE,
            LocalMasterStockTransferRpcProtocolV1.LOOKUP, LocalMasterKitchenRpcProtocolV1.READ,
            LocalMasterKitchenRpcProtocolV1.EXECUTE, LocalMasterKitchenRpcProtocolV1.LOOKUP,
            LocalMasterRetailOrderRpcProtocolV1.READ, LocalMasterRetailOrderRpcProtocolV1.EXECUTE,
            LocalMasterRetailOrderRpcProtocolV1.LOOKUP)
        assertTrue(routes.map { it.name }.toSet().intersect(unrelatedRoutes.map { it.name }.toSet()).isEmpty())
        // Descriptor separation is not observation of a native-qualified bridge
        // selecting PAYMENT_CORRECTIONS or dispatching any actual RPC call.
    }
}
