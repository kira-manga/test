package com.ukkera.pos.sales.store.independent.foodkitchenexecutionv1

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * L1 public profile bindings of frozen FKEX-01/03/04 only. Each codec preserves
 * selectors and canonical bytes; it grants no Master authority or execution.
 * Full Send/SendReserved/BeginCollection admission and every durable cut are UNBOUND.
 */
public class FoodKitchenExecutionFrozenProfileSlicesTest {
    @Test
    public fun fkex03S1SendPreservesTheOriginalFoodWriteProfileAndCompleteSelection(): Unit {
        val request = FoodKitchenExecutionFrozenInputs.send()
        val command = LocalFoodCodecV1.command(FoodKitchenExecutionFrozenInputs.operation("FKEX-03.S1"), request)
        val wire = assertCanonical(command, "FKEX-03.S1", CommandKey("sales.food.write", 1), LocalFoodCodecV1::normalize)
        assertEquals("SEND", wire.getValue("type").jsonPrimitive.content)
        assertEquals(request, LocalFoodCodecV1.decode(command.payload))
        assertSend(wire, "12")
    }

    @Test
    public fun fkex03S2SendPreservesTheStockPreparationProfileAndCompleteSelection(): Unit {
        val request = LocalFoodStockRequestV2.Preparation(FoodKitchenExecutionFrozenInputs.send())
        val command = LocalFoodStockCodecV2.command(FoodKitchenExecutionFrozenInputs.operation("FKEX-03.S2"), request)
        val wire = assertCanonical(command, "FKEX-03.S2", CommandKey("sales.food.stock", 2), LocalFoodStockCodecV2::normalize)
        assertEquals("PREPARATION", wire.getValue("type").jsonPrimitive.content)
        assertEquals(request, LocalFoodStockCodecV2.decode(command.payload))
        val selected = wire.getValue("request").jsonObject
        assertEquals("SEND", selected.getValue("type").jsonPrimitive.content)
        assertSend(selected, "12")
    }

    @Test
    public fun fkex03R1SendReservedPreservesTheFoodFinancialProfileAndLockedSelection(): Unit {
        val request = FoodKitchenExecutionFrozenInputs.sendReserved()
        val command = LocalFoodFinancialCodecV1.command(FoodKitchenExecutionFrozenInputs.operation("FKEX-03.R1"), request)
        val wire = assertCanonical(command, "FKEX-03.R1", CommandKey("sales.food.financial", 1), LocalFoodFinancialCodecV1::normalize)
        assertEquals(request, LocalFoodFinancialCodecV1.decode(command.payload))
        assertSendReserved(wire)
    }

    @Test
    public fun fkex03R2SendReservedPreservesTheStockFinancialProfileAndLockedSelection(): Unit {
        val request = LocalFoodStockRequestV2.Financial(FoodKitchenExecutionFrozenInputs.sendReserved())
        val command = LocalFoodStockCodecV2.command(FoodKitchenExecutionFrozenInputs.operation("FKEX-03.R2"), request)
        val wire = assertCanonical(command, "FKEX-03.R2", CommandKey("sales.food.stock", 2), LocalFoodStockCodecV2::normalize)
        assertEquals("FINANCIAL", wire.getValue("type").jsonPrimitive.content)
        assertEquals(request, LocalFoodStockCodecV2.decode(command.payload))
        assertSendReserved(wire.getValue("request").jsonObject)
    }

    @Test
    public fun fkex04B1BeginCollectionHasTheWholeOrderFinancialShapeWithoutASubsetFlag(): Unit {
        val request = FoodKitchenExecutionFrozenInputs.beginCollection()
        val command = LocalFoodFinancialCodecV1.command(FoodKitchenExecutionFrozenInputs.operation("FKEX-04.B1"), request)
        val wire = assertCanonical(command, "FKEX-04.B1", CommandKey("sales.food.financial", 1), LocalFoodFinancialCodecV1::normalize)
        assertEquals(request, LocalFoodFinancialCodecV1.decode(command.payload))
        assertBeginCollection(wire)
    }

    @Test
    public fun fkex04B2BeginCollectionHasTheWholeOrderStockShapeWithoutASubsetFlag(): Unit {
        val request = LocalFoodStockRequestV2.Financial(FoodKitchenExecutionFrozenInputs.beginCollection())
        val command = LocalFoodStockCodecV2.command(FoodKitchenExecutionFrozenInputs.operation("FKEX-04.B2"), request)
        val wire = assertCanonical(command, "FKEX-04.B2", CommandKey("sales.food.stock", 2), LocalFoodStockCodecV2::normalize)
        assertEquals("FINANCIAL", wire.getValue("type").jsonPrimitive.content)
        assertEquals(request, LocalFoodStockCodecV2.decode(command.payload))
        assertBeginCollection(wire.getValue("request").jsonObject)
    }

    @Test
    public fun fkex01A0GuardedAddKeepsTheExactFrozenTwoPortionsRevisionsAndNotes(): Unit {
        assertGuardedAdd(FoodKitchenExecutionFrozenInputs.addToEmpty(), FoodKitchenExecutionFrozenInputs.ADD_TO_EMPTY,
            "FKEX-01.A0", listOf(FoodKitchenExecutionFrozenInputs.p1, FoodKitchenExecutionFrozenInputs.p2))
    }

    @Test
    public fun fkex01A1GuardedAddKeepsOnlyTheExactFrozenNewPortionsInTheirOriginalOrder(): Unit {
        assertGuardedAdd(FoodKitchenExecutionFrozenInputs.addToNonempty(), FoodKitchenExecutionFrozenInputs.ADD_TO_NONEMPTY,
            "FKEX-01.A1", listOf(FoodKitchenExecutionFrozenInputs.p2, FoodKitchenExecutionFrozenInputs.p3))
    }

    private fun assertCanonical(command: MutationRequest, case: String, expectedKey: CommandKey,
        normalize: (StoredBytes) -> StoredBytes): JsonObject {
        assertEquals(FoodKitchenExecutionFrozenInputs.operation(case), command.operationId)
        assertEquals(expectedKey, command.command)
        val canonical = normalize(command.payload)
        assertEquals(command.payload, canonical)
        assertEquals(canonical, normalize(canonical))
        val actor = DomainId("61000000-0000-4000-8000-000000000030")
        // Recomputed equality only, not an invented frozen digest golden or original proof.
        assertEquals(semanticDigest(FoodKitchenExecutionFrozenInputs.scope, actor, command.command, command.payload),
            semanticDigest(FoodKitchenExecutionFrozenInputs.scope, actor, command.command, canonical))
        return Json.parseToJsonElement(canonical.copyBytes().decodeToString()).jsonObject
    }

    private fun assertSend(wire: JsonObject, revision: String) {
        assertEquals(FoodKitchenExecutionFrozenInputs.order.value, wire.getValue("orderId").jsonPrimitive.content)
        val storedRevision = wire.getValue("expectedOrderRevision").jsonPrimitive
        assertTrue(storedRevision.isString)
        assertEquals(revision, storedRevision.content)
        assertEquals(FoodKitchenExecutionFrozenInputs.selected.map { it.value },
            wire.getValue("portionIds").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(wire.containsKey("supported"))
        assertFalse(wire.containsKey("kitchenExecution"))
        assertFalse(wire.containsKey("activity"))
    }

    private fun assertSendReserved(wire: JsonObject) {
        assertEquals("SEND_RESERVED", wire.getValue("type").jsonPrimitive.content)
        assertSend(wire, "16")
        assertEquals(FoodKitchenExecutionFrozenInputs.collection.value, wire.getValue("collectionId").jsonPrimitive.content)
        // This value is codec-only metadata; native collection revision is UNBOUND.
        assertEquals(FoodKitchenExecutionFrozenInputs.bindingOnlyCollectionDomainRevision.value.toString(),
            wire.getValue("expectedCollectionRevision").jsonPrimitive.content)
    }

    private fun assertBeginCollection(wire: JsonObject) {
        assertEquals(setOf("type", "orderId", "expectedOrderRevision", "collectionId", "sessionId", "expectedSessionRevision"), wire.keys)
        assertEquals("BEGIN_COLLECTION", wire.getValue("type").jsonPrimitive.content)
        assertEquals(FoodKitchenExecutionFrozenInputs.order.value, wire.getValue("orderId").jsonPrimitive.content)
        assertEquals("12", wire.getValue("expectedOrderRevision").jsonPrimitive.content)
        assertTrue(wire.getValue("expectedOrderRevision").jsonPrimitive.isString)
        assertEquals(FoodKitchenExecutionFrozenInputs.collection.value, wire.getValue("collectionId").jsonPrimitive.content)
        assertEquals(FoodKitchenExecutionFrozenInputs.cashSession.value, wire.getValue("sessionId").jsonPrimitive.content)
        // Absence of a subset field does NOT prove the Master reads every unsent portion.
    }

    private fun assertGuardedAdd(request: LocalFoodStockRequestV2.ActivityProtectedAdd, literal: String,
        case: String, selected: List<DomainId>) {
        val command = LocalFoodStockCodecV2.command(FoodKitchenExecutionFrozenInputs.operation(case), request)
        val wire = assertCanonical(command, case, CommandKey("sales.food.stock", 2), LocalFoodStockCodecV2::normalize)
        assertEquals(literal, command.payload.copyBytes().decodeToString())
        assertEquals(setOf("type", "request"), wire.keys)
        assertEquals("ACTIVITY_PROTECTED_ADD", wire.getValue("type").jsonPrimitive.content)
        assertEquals(FoodKitchenExecutionFrozenInputs.order, request.request.orderId)
        assertEquals(Revision(12), request.request.expectedOrderRevision)
        assertEquals(Revision(9), request.request.expectedCatalogRevision)
        assertEquals(selected, request.request.portions.map { it.portionId })
        assertFalse(wire.getValue("request").jsonObject.containsKey("activity"))
    }
}
