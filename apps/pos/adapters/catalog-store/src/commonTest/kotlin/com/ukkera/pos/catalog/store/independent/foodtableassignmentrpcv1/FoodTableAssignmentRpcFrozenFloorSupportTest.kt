package com.ukkera.pos.catalog.store.independent.foodtableassignmentrpcv1

import com.ukkera.pos.catalog.store.LocalFoodFloorQueryV1
import com.ukkera.pos.catalog.store.LocalFoodFloorReadCodecV1
import com.ukkera.pos.catalog.store.LocalFoodFloorReadHandler
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalFoodQueryV1
import com.ukkera.pos.sales.store.LocalFoodReadCodecV1
import com.ukkera.pos.sales.store.LocalFoodTableAssignmentCodecV1
import com.ukkera.pos.sales.store.LocalFoodTableAssignmentRequestV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FTA-RPC-v1-S01 floor-handler membership only; no directory prepare/project or stored proof. */
class FoodTableAssignmentRpcFrozenFloorSupportTest {
    @Test
    fun ftaRpcS01FloorTablesAndExactTableSelectorsAdmitOnlyTheAssignmentProfile() {
        val handler = LocalFoodFloorReadHandler()
        val queries = listOf(LocalFoodFloorQueryV1.Floors(), LocalFoodFloorQueryV1.Tables(FrozenRpcFloorInput.floor),
            LocalFoodFloorQueryV1.Tables(FrozenRpcFloorInput.floor, FrozenRpcFloorInput.table20),
            LocalFoodFloorQueryV1.Tables(FrozenRpcFloorInput.floor, FrozenRpcFloorInput.table21),
            LocalFoodFloorQueryV1.Table(FrozenRpcFloorInput.table))
        for (query in queries) {
            val read = LocalFoodFloorReadCodecV1.read(query)
            val normalized = handler.normalize(read.payload)
            assertEquals(read.query, handler.key)
            assertEquals(read.payload, normalized)
            assertTrue(handler.supportsRpcWorkflow(assignmentProfile, normalized))
            for (profile in LocalMasterRpcWorkflowProfile.entries.filter { it != assignmentProfile })
                assertFalse(handler.supportsRpcWorkflow(profile, normalized), profile.name)
        }
    }

    @Test
    fun ftaRpcS01FloorHandlerRefusesFoodAndAssignmentBodiesAndMalformedDirectoryInput() {
        val handler = LocalFoodFloorReadHandler()
        val tables = LocalFoodFloorReadCodecV1.encode(LocalFoodFloorQueryV1.Tables(FrozenRpcFloorInput.floor))
        val tablesText = tables.copyBytes().decodeToString()
        val invalidLimit0 = tablesText.replace("\"limit\":20", "\"limit\":0")
        check(invalidLimit0 != tablesText) { "Invalid-limit input must replace the encoded Tables limit" }
        val wrongBodies = listOf(LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Own()),
            LocalFoodTableAssignmentCodecV1.encode(FrozenRpcFloorInput.assignmentA), StoredBytes("{".encodeToByteArray()),
            appendFloorRpcField(tables, "\"grant\":\"FLOOR_READ/PERFORM\""),
            appendFloorRpcField(tables, "\"limit\":20"),
            StoredBytes(invalidLimit0.encodeToByteArray()))
        for (body in wrongBodies) {
            val supported = try { handler.supportsRpcWorkflow(assignmentProfile, body) }
                catch (_: IllegalArgumentException) { false }
            assertFalse(supported)
        }
    }
}

private val assignmentProfile = LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS

/** Exact frozen directory selector IDs and A; no native floor/table rows are fabricated. */
private object FrozenRpcFloorInput {
    private fun id(suffix: Int) = DomainId("10000000-0000-4000-8000-${suffix.toString().padStart(12, '0')}")
    val floor = id(101)
    val table = id(201)
    val table20 = id(220)
    val table21 = id(221)
    val assignmentA = LocalFoodTableAssignmentRequestV1(id(301), Revision(7), floor, Revision(2), table, Revision(3), null)
}

private fun appendFloorRpcField(body: StoredBytes, field: String): StoredBytes {
    val text = body.copyBytes().decodeToString()
    check(text.endsWith('}'))
    return StoredBytes((text.dropLast(1) + "," + field + "}").encodeToByteArray())
}
