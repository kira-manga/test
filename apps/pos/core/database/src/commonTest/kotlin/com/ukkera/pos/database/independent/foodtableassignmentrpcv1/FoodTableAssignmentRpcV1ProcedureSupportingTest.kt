package com.ukkera.pos.database.independent.foodtableassignmentrpcv1

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.LocalMasterClientRpcProtocolV1
import com.ukkera.pos.database.LocalMasterFoodTableAssignmentRpcKeysV1
import com.ukkera.pos.database.LocalMasterFoodTableAssignmentRpcProtocolV1
import com.ukkera.pos.database.ReadKey
import dev.p2pkit.rpc.RpcRetrySafety
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** FTA-RPC-v1-S01: inert descriptors/keys only, without admitted RPC or Master authority. */
class FoodTableAssignmentRpcV1ProcedureSupportingTest {
    @Test
    fun s01FixedAssignmentProceduresPreserveVersionOneCarriers() {
        val attached = LocalMasterFoodTableAssignmentRpcProtocolV1
        val original = LocalMasterClientRpcProtocolV1
        val routes = listOf(attached.READ, attached.EXECUTE, attached.LOOKUP)
        assertEquals(listOf("pos.food-table-assignment.read/1", "pos.food-table-assignment.execute/1",
            "pos.food-table-assignment.lookup/1"), routes.map { "${it.name}/${it.version}" })
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

    @Test
    fun s01OnlyOwnCheckBindingDirectoryAndAssignmentKeysAreSelected() {
        val selected = LocalMasterFoodTableAssignmentRpcKeysV1
        for (name in listOf("sales.food.read", "sales.food.table.assignment-read", "local.food.floor-read")) {
            assertTrue(selected.accepts(ReadKey(name, 1)), name)
            assertFalse(selected.accepts(ReadKey(name, 2)), "$name/v2")
        }
        assertTrue(selected.accepts(CommandKey("sales.food.table.assignment", 1)))
        assertFalse(selected.accepts(CommandKey("sales.food.table.assignment", 2)))
        for (name in listOf("local.food.menu", "sales.food.check-kitchen.read", "sales.food.quotation.profile",
            "sales.food.selection-preview", "sales.payment-correction.read", "local.configuration.read", "unknown.read")) {
            assertFalse(selected.accepts(ReadKey(name, 1)), name)
        }
        for (name in listOf("sales.food.write", "sales.food.draft", "sales.payment-correction.write",
            "local.food.floor", "local.food.table", "unknown.command")) {
            assertFalse(selected.accepts(CommandKey(name, 1)), name)
        }
        // Accepted keys still need exact selector/body classification and fresh Master authorization.
    }
}
