package com.ukkera.pos.sales.store.independent.foodtableassignmentrpcv1

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.PreparedMutationAdmissionRestriction
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.sales.store.independent.foodtableassignment.FtaSalesDirectory
import com.ukkera.pos.sales.store.independent.foodtableassignment.ftaAssignmentHandler
import com.ukkera.pos.sales.store.independent.foodtableassignment.ftaAssignmentReadHandler
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FTA-RPC-v1-S01 handler-membership subset; no business evaluation or native source binding. */
class FoodTableAssignmentRpcFrozenSalesSupportTest {
    @Test
    fun ftaRpcS01AssignmentAndBindingReadAdmitFrozenAOnlyForTheAssignmentProfile() {
        val directory = FtaSalesDirectory()
        val assignment = ftaAssignmentHandler(directory)
        val bindingRead = ftaAssignmentReadHandler(directory)
        val command = LocalFoodTableAssignmentCodecV1.command(FrozenRpcSalesInput.operationA, FrozenRpcSalesInput.assignmentA)
        val read = LocalFoodTableAssignmentReadCodecV1.read(LocalFoodTableAssignmentQueryV1(FrozenRpcSalesInput.order))
        val normalizedCommand = assignment.normalize(command.payload)
        val normalizedRead = bindingRead.normalize(read.payload)

        assertEquals(FrozenRpcSalesInput.operationA, command.operationId)
        assertEquals(command.command, assignment.key)
        assertEquals(read.query, bindingRead.key)
        assertEquals(command.payload, normalizedCommand)
        assertEquals(read.payload, normalizedRead)
        assertTrue(assignment.supportsRpcWorkflow(assignmentProfile, normalizedCommand))
        assertTrue(bindingRead.supportsRpcWorkflow(assignmentProfile, normalizedRead))
        for (profile in LocalMasterRpcWorkflowProfile.entries.filter { it != assignmentProfile }) {
            assertFalse(assignment.supportsRpcWorkflow(profile, normalizedCommand), profile.name)
            assertFalse(bindingRead.supportsRpcWorkflow(profile, normalizedRead), profile.name)
        }
        assertEquals(0, directory.currentReads)
    }

    @Test
    fun ftaRpcS01FoodReadAdmitsOwnAndExactDetailButNotStationTaskOrHandoffSelectors() {
        val recipes = UnexpectedRpcRecipeSource()
        val food = LocalFoodReadHandler(recipes, LocalStockRepository())
        val intended = listOf(LocalFoodQueryV1.Own(), LocalFoodQueryV1.Order(FrozenRpcSalesInput.order),
            LocalFoodQueryV1.Order(FrozenRpcSalesInput.secondOrder))
        for (query in intended) {
            val read = LocalFoodReadCodecV1.read(query)
            val normalized = food.normalize(read.payload)
            assertEquals(read.query, food.key)
            assertEquals(read.payload, normalized)
            assertTrue(food.supportsRpcWorkflow(assignmentProfile, normalized))
        }
        // Syntactically valid frozen IDs are inert negative selector coordinates, not
        // fabricated station/task records or evidence of their existence.
        val prohibited = listOf(LocalFoodQueryV1.Station(FrozenRpcSalesInput.floor),
            LocalFoodQueryV1.Task(FrozenRpcSalesInput.floor, FrozenRpcSalesInput.firstPortion),
            LocalFoodQueryV1.Handoff(FrozenRpcSalesInput.order))
        for (query in prohibited) {
            val normalized = food.normalize(LocalFoodReadCodecV1.encode(query))
            assertFalse(food.supportsRpcWorkflow(assignmentProfile, normalized))
        }
        assertEquals(0, recipes.calls)
    }

    @Test
    fun ftaRpcS01NewProfileRefusesKitchenReadAndFoodCommandFamiliesBeforeRecipeEvaluation() {
        val recipes = UnexpectedRpcRecipeSource()
        val stock = LocalStockRepository()
        val detail = LocalFoodQueryV1.Order(FrozenRpcSalesInput.order)
        val kitchenV1 = LocalFoodCheckKitchenReadHandler(recipes, stock)
        val kitchenV2 = LocalFoodCheckKitchenReadHandlerV2(recipes, stock)
        val foodCommand = LocalFoodHandler(stock, recipes)
        val send = LocalFoodCodecV1.command(FrozenRpcSalesInput.operationA,
            LocalFoodRequestV1.Send(FrozenRpcSalesInput.order, Revision(7),
                listOf(FrozenRpcSalesInput.firstPortion, FrozenRpcSalesInput.secondPortion)))

        assertFalse(kitchenV1.supportsRpcWorkflow(assignmentProfile,
            kitchenV1.normalize(LocalFoodCheckKitchenCodecV1.encode(detail))))
        assertFalse(kitchenV2.supportsRpcWorkflow(assignmentProfile,
            kitchenV2.normalize(LocalFoodCheckKitchenCodecV2.encode(detail))))
        assertEquals(send.command, foodCommand.key)
        assertFalse(foodCommand.supportsRpcWorkflow(assignmentProfile, foodCommand.normalize(send.payload)))
        assertEquals(0, recipes.calls)
    }

    @Test
    fun ftaRpcS01AssignmentAndBindingReadRefuseBodySwapsAndMalformedInjectedBodies() {
        val directory = FtaSalesDirectory()
        val assignment = ftaAssignmentHandler(directory)
        val bindingRead = ftaAssignmentReadHandler(directory)
        val command = LocalFoodTableAssignmentCodecV1.encode(FrozenRpcSalesInput.assignmentA)
        val read = LocalFoodTableAssignmentReadCodecV1.encode(LocalFoodTableAssignmentQueryV1(FrozenRpcSalesInput.order))
        val own = LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Own())
        val duplicateOrder = "\"orderId\":\"${FrozenRpcSalesInput.order.value}\""
        val badCommands = listOf(read, own, StoredBytes("{".encodeToByteArray()),
            injectedRpcField(command, "\"grant\":\"ORDER_EDIT/PERFORM\""), injectedRpcField(command, duplicateOrder))
        val badReads = listOf(command, own, StoredBytes("{".encodeToByteArray()),
            injectedRpcField(read, "\"profile\":\"FOOD_CHECKS\""), injectedRpcField(read, duplicateOrder))

        for (body in badCommands) assertRpcRefused { assignment.supportsRpcWorkflow(assignmentProfile, body) }
        for (body in badReads) assertRpcRefused { bindingRead.supportsRpcWorkflow(assignmentProfile, body) }
        assertEquals(0, directory.currentReads)
    }
}

private val assignmentProfile = LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS

/** Semantic IDs and A's 7/absent/F1-2/T01-3 come only from frozen FTA-RPC-v1 fixtures. */
private object FrozenRpcSalesInput {
    private fun id(suffix: Int) = DomainId("10000000-0000-4000-8000-${suffix.toString().padStart(12, '0')}")
    val order = id(301)
    val secondOrder = id(302)
    val floor = id(101)
    val table = id(201)
    val firstPortion = id(411)
    val secondPortion = id(412)
    val operationA = OperationId(id(911).value)
    val assignmentA = LocalFoodTableAssignmentRequestV1(order, Revision(7), floor, Revision(2), table, Revision(3), null)
}

/** Existing public codecs adapt supplied input; they are not a golden-byte/result oracle. */
private fun injectedRpcField(body: StoredBytes, field: String): StoredBytes {
    val text = body.copyBytes().decodeToString()
    check(text.endsWith('}'))
    return StoredBytes((text.dropLast(1) + "," + field + "}").encodeToByteArray())
}

/** Malformed typed input may throw or report unsupported; neither is an admitted body. */
private fun assertRpcRefused(admission: () -> Boolean) {
    val supported = try { admission() } catch (_: IllegalArgumentException) { false }
    assertFalse(supported)
}

/** Deliberately unavailable sources; no recipe/history/native capability is supplied. */
private class UnexpectedRpcRecipeSource : TransactionFoodRecipeSourceV1 {
    var calls = 0
        private set
    private fun unexpected(): Nothing {
        calls++
        error("Handler profile membership must not evaluate recipe or history sources")
    }
    override suspend fun prepareSelectionPreview(view: CommandReadView, activity: LocalSalesActivity,
        service: LocalFoodServiceV1, catalogRevision: Revision,
        portions: List<LocalFoodPortionSelectionV1>): PreparedFoodSelectionPreviewV1? = unexpected()
    override suspend fun prepare(view: CommandReadView, activity: LocalSalesActivity, service: LocalFoodServiceV1,
        catalogRevision: Revision, portions: List<LocalFoodPortionSelectionV1>): PreparedFoodRecipeBatchV1? = unexpected()
    override suspend fun currentForSend(view: CommandReadView, snapshot: LocalFoodRecipeSnapshotV1): Boolean = unexpected()
    override suspend fun prepareKitchenExecution(view: CommandReadView, activity: LocalSalesActivity,
        service: LocalFoodServiceV1, snapshots: List<LocalFoodRecipeSnapshotV1>): PreparedMutationAdmissionRestriction? = unexpected()
    override suspend fun requireHistorical(view: CommandReadView, snapshot: LocalFoodRecipeSnapshotV1): Unit = unexpected()
}
