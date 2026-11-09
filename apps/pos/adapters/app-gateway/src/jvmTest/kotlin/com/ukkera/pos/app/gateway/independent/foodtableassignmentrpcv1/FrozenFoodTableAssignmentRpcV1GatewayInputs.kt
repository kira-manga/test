@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.app.gateway.independent.foodtableassignmentrpcv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.WarehouseRpcControlledJournal
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.WarehouseRpcControlledMaster
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.assertEquals

/** FTA-RPC-v1-S03 fixture A. Inputs are frozen semantics bound to the existing V1
 * schema, not a generated production outcome, golden history or native source proof. */
internal object FrozenFoodTableAssignmentRpcV1GatewayInputs {
    private val directory = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/food-table-assignment-rpc") }
        .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
        ?: error("Frozen table-assignment RPC inputs are unavailable")
    private val frozen = Json.parseToJsonElement(Files.readString(directory.resolve("fixtures.json"))).jsonObject
        .also { assertEquals("FTA-RPC-v1", it.getValue("specification").jsonPrimitive.content) }
    private val ids = frozen.getValue("ids").jsonObject
    private val command = frozen.getValue("commands").jsonObject.getValue("A").jsonObject
    private val binding = command.getValue("expected_binding").jsonObject
    fun id(key: String): DomainId = DomainId(ids.getValue(key).jsonPrimitive.content)
    private fun commandId(key: String): DomainId = id(command.getValue(key).jsonPrimitive.content)
    private fun revision(key: String): Revision = Revision(command.getValue(key).jsonPrimitive.long)

    val scope = BranchScope(id("organization"), id("food_company"), id("branch"))
    val actor = id("owner")
    val device = id("admitted_client")
    val owner = LocalClientIdentity(scope, actor, device)
    val operation = OperationId(commandId("operation").value)
    val request = LocalFoodTableAssignmentRequestV1(commandId("order"), revision("observed_order_revision"),
        commandId("floor"), revision("observed_floor_revision"), commandId("table"),
        revision("observed_table_revision"), null).also {
        assertEquals("absent", command.getValue("observed_assignment").jsonPrimitive.content)
    }
    val expectedBinding = LocalFoodTableBindingV1(Revision(binding.getValue("storageRevision").jsonPrimitive.long),
        request.expectedOrderRevision, id(binding.getValue("floor").jsonPrimitive.content), request.expectedFloorRevision,
        id(binding.getValue("table").jsonPrimitive.content), request.expectedTableRevision)
    val expectedSuccess = LocalFoodTableAssignmentResultV1("food.table.assigned",
        id(binding.getValue("order").jsonPrimitive.content), request.expectedOrderRevision, expectedBinding)

    // RB04 supporting schema binding: this fixed existing refusal is supplied DATA,
    // not evidence that the Master actually derived order_stale for fixture A.
    const val refusalCode = "food.table.assignment.order_stale"
    val expectedRefusal = LocalFoodTableAssignmentResultV1(refusalCode, null, null, null)

    // Existing Revision/DomainId V1 serializers use decimal strings/canonical UUIDs.
    // Construct the input fields directly; do not serialize a production proposal.
    val successJson: JsonObject = buildJsonObject {
        put("code", "food.table.assigned")
        put("orderId", expectedSuccess.orderId!!.value)
        put("orderRevision", request.expectedOrderRevision.value.toString())
        put("assignment", buildJsonObject {
            put("revision", expectedBinding.revision.value.toString())
            put("observedOrderRevision", request.expectedOrderRevision.value.toString())
            put("floorId", expectedBinding.floorId.value)
            put("floorRevision", request.expectedFloorRevision.value.toString())
            put("tableId", expectedBinding.tableId.value)
            put("tableRevision", request.expectedTableRevision.value.toString())
        })
    }
    val successPayload = payload(successJson)
    val refusalPayload = payload(buildJsonObject {
        put("code", refusalCode)
        put("orderId", JsonNull)
        put("orderRevision", JsonNull)
        put("assignment", JsonNull)
    })

    fun bytes(value: String): StoredBytes = StoredBytes(value.encodeToByteArray())
    fun payload(value: JsonObject): StoredBytes = bytes(value.toString())
    fun resultWith(field: String, value: JsonElement): StoredBytes = payload(JsonObject(successJson.toMutableMap().apply {
        put(field, value)
    }))
    fun bindingWith(field: String, value: JsonElement): StoredBytes = resultWith("assignment",
        JsonObject(successJson.getValue("assignment").jsonObject.toMutableMap().apply { put(field, value) }))

    // Additional slots provide only controlled credential/result-envelope DATA.
    // Frozen operation, actor, scope, device, order and destination IDs are never regenerated.
    private fun slot(name: String): DomainId = DomainId(UUID.nameUUIDFromBytes(
        ("fta-rpc-v1-controlled:$name").encodeToByteArray()).toString())
    private val session = slot("session")
    private val boot = slot("boot")
    private val at = UtcInstant.parseCanonical("2026-11-04T00:00:00Z")

    fun carrier(committed: Boolean = true, code: String = if (committed) "committed" else refusalCode,
        result: StoredBytes = if (committed) successPayload else refusalPayload,
        operationId: OperationId = operation, originalActorId: DomainId = actor,
        sequence: JournalSequence = JournalSequence(1)): MutationReply.Recorded =
        MutationReply.Recorded(operationId, originalActorId, committed, code, result, Revision(1), sequence, at, true)

    fun view(): LocalSessionView = LocalSessionView(actor, scope, device, session, SessionGeneration(1),
        LocalLoginIdentifier("table-assignment-support"), ActivatedLocalPolicy(scope, actor, Revision(1),
            listOf(PermissionGrant(Permission(SecurityAction.ORDER_EDIT, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW),
                PermissionGrant(Permission(SecurityAction.ORDER_READ, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW),
                PermissionGrant(Permission(SecurityAction.FLOOR_READ, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW)),
            LocalSessionPolicy(), ThrottlePolicy()), MonotonicMoment(boot, 1_000), MonotonicMoment(boot, 2_000))

    fun credentials(): LocalGatewayCredentials = LocalGatewayCredentials(slot("connection"), OpaqueCredential(ByteArray(16)))

    fun entry(): StoredClientIntentV1 {
        val intent = LocalCommandIntent(operation, LocalAction.FOOD_ASSIGN_TABLE, 1, null, listOf(
            LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(request.orderId)),
            LocalScalarInput(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, LocalScalarValue.Integer(request.expectedOrderRevision.value)),
            LocalScalarInput(LocalField.FLOOR_ID, LocalScalarValue.Identifier(request.floorId)),
            LocalScalarInput(LocalField.EXPECTED_FLOOR_STORAGE_REVISION, LocalScalarValue.Integer(request.expectedFloorRevision.value)),
            LocalScalarInput(LocalField.TABLE_ID, LocalScalarValue.Identifier(request.tableId)),
            LocalScalarInput(LocalField.EXPECTED_TABLE_STORAGE_REVISION, LocalScalarValue.Integer(request.expectedTableRevision.value))))
        return StoredClientIntentV1.staged(owner, intent, LocalFoodTableAssignmentCodecV1.command(operation, request),
            text("Table assignment", "تعيين الطاولة"),
            listOf(LocalRecordValue(text("Order", "الطلب"), literal(request.orderId.value))))
    }
}

/** Actual gateway, with reused controlled order ports. No native client storage,
 * admitted RPC, original-history qualification or transport Receipt is installed. */
internal class FoodTableAssignmentRpcV1ControlledFixture(entry: StoredClientIntentV1 = FrozenFoodTableAssignmentRpcV1GatewayInputs.entry()) {
    private val input = FrozenFoodTableAssignmentRpcV1GatewayInputs
    val events = mutableListOf<String>()
    var supplied: MutationReply.Recorded? = input.carrier()
    val master = WarehouseRpcControlledMaster(events).also { master ->
        // All replies actually used by these tests are replaced; no warehouse outcome is reused.
        master.view = input.view()
        master.currentReply = { LocalSessionViewResult.Current(master.view) }
        master.readReply = { ReadReply.Unavailable(ReadFailure.UNKNOWN_QUERY) }
        master.lookupReply = { identity -> supplied?.let { LookupReply.Found(it) }
            ?: LookupReply.Deferred(identity.operationId, DeferredReason.WRITER_NOT_QUALIFIED) }
        master.executeReply = { MutationReply.Deferred(it.operationId, DeferredReason.WRITER_NOT_QUALIFIED) }
    }
    val journal = WarehouseRpcControlledJournal(input.owner, events).also { it.entries[entry.operationId] = entry }
    private val leases = mutableListOf<LocalWorkflowCredentialLease>()
    private val credentials = LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(input.credentials()) { true }.also(leases::add)
    }
    val installed = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS)
    val gateway = LocalEndpointWorkflowGateway(master, journal, credentials, installed)
    suspend fun close() { leases.forEach { it.destroy() }; journal.close() }
}

internal suspend fun <T> withFoodTableAssignmentRpcV1Fixture(
    entry: StoredClientIntentV1 = FrozenFoodTableAssignmentRpcV1GatewayInputs.entry(),
    body: suspend (FoodTableAssignmentRpcV1ControlledFixture) -> T,
): T {
    val fixture = FoodTableAssignmentRpcV1ControlledFixture(entry)
    return try { body(fixture) } finally { fixture.close() }
}
