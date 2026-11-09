@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.rpc.independent.foodfinancialrpcv1

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.LocalMasterFoodCashFinancialRpcKeysV1
import com.ukkera.pos.database.LocalMasterFoodCashFinancialRpcProtocolV1
import com.ukkera.pos.database.LocalMasterFoodCheckRpcProtocolV1
import com.ukkera.pos.database.ReadKey
import dev.p2pkit.rpc.RpcRetrySafety
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FFRC-001/002/016 protocol and inert key selection only; no canonical dispatch or admission. */
class FoodFinancialRpcV1ProtocolSupportingTest {
    @Test
    fun ffrc001ThreeExplicitVersionOneProceduresHaveNoRequestSelectedProfile() {
        FoodFinancialRpcV1Frozen.fixtures
        val procedures = listOf(LocalMasterFoodCashFinancialRpcProtocolV1.READ,
            LocalMasterFoodCashFinancialRpcProtocolV1.EXECUTE, LocalMasterFoodCashFinancialRpcProtocolV1.LOOKUP)
        assertEquals(listOf("pos.food-cash-financial.read", "pos.food-cash-financial.execute",
            "pos.food-cash-financial.lookup"), procedures.map { it.name })
        procedures.forEach { procedure ->
            assertEquals(1, procedure.version)
            assertEquals(RpcRetrySafety.NeverReinvoke, procedure.retrySafety)
            val requestFields = (0 until procedure.request.descriptor.elementsCount)
                .map { procedure.request.descriptor.getElementName(it) }
            assertFalse("profile" in requestFields, "The business carrier must not select its route profile")
        }
        assertTrue(procedures.map { it.name }.toSet().intersect(setOf(
            LocalMasterFoodCheckRpcProtocolV1.READ.name, LocalMasterFoodCheckRpcProtocolV1.EXECUTE.name,
            LocalMasterFoodCheckRpcProtocolV1.LOOKUP.name)).isEmpty())
    }

    @Test
    fun ffrc002TransportCommandMembershipHasOnlyTheTwoFrozenKeys() {
        FoodFinancialRpcV1Frozen.fixtures
        val admitted = setOf(CommandKey("sales.food.stock", 2), CommandKey("sales.food.payment", 1))
        val names = listOf("sales.food.stock", "sales.food.payment", "sales.food.financial",
            "sales.food.write", "sales.food.table.assignment", "stock.normalized", "sales.order.write")
        for (name in names) for (version in listOf(1, 2, 3)) {
            val key = CommandKey(name, version)
            assertEquals(key in admitted, LocalMasterFoodCashFinancialRpcKeysV1.accepts(key), name + "/" + version)
        }
        admitted.forEach { assertTrue(LocalMasterFoodCashFinancialRpcKeysV1.accepts(it)) }
        // Shared-key Draft/Add/Send/RecordOriginal refusal requires installed canonical handlers.
    }

    @Test
    fun ffrc001And016TransportReadMembershipIsTheExactSevenKeySet() {
        FoodFinancialRpcV1Frozen.fixtures
        val admitted = setOf(
            ReadKey("sales.food.read", 1),
            ReadKey("sales.food.check-kitchen.read", 1), ReadKey("sales.food.check-kitchen.read", 2),
            ReadKey("sales.food.check-kitchen.read", 3),
            ReadKey("stock.normalized.profile", 2),
            ReadKey("sales.food.financial.read", 1), ReadKey("sales.food.payment.read", 1),
        )
        assertEquals(7, admitted.size)
        val names = admitted.map { it.name }.toSet() + setOf("local.food.menu", "local.food.station-directory",
            "sales.food.quotation.profile", "sales.food.selection-preview", "stock.costs", "sales.order.read")
        for (name in names) for (version in listOf(1, 2, 3, 4)) {
            val key = ReadKey(name, version)
            assertEquals(key in admitted, LocalMasterFoodCashFinancialRpcKeysV1.accepts(key), name + "/" + version)
        }
        admitted.forEach { assertTrue(LocalMasterFoodCashFinancialRpcKeysV1.accepts(it)) }
        // This does not classify selectors under a shared read key or grant any read permission.
    }
}
