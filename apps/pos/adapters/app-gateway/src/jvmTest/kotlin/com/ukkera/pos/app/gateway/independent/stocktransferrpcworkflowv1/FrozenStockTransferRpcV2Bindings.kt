package com.ukkera.pos.app.gateway.independent.stocktransferrpcworkflowv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.WarehouseRpcControlledJournal
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.WarehouseRpcControlledMaster
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.MovingAveragePlanner
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*

/** Frozen V2 values; additional identifiers name inert supporting carrier slots only. */
internal object FrozenStockTransferRpcV2 {
    private val directory = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/stock-transfer-rpc-workflow-v2") }
        .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
        ?: error("Frozen stock-transfer V2 inputs are unavailable")
    val root = Json.parseToJsonElement(Files.readString(directory.resolve("fixtures.json"))).jsonObject.also {
        assertEquals("stock-transfer-rpc-workflow-v2", it.text("design_version"))
        assertEquals("FROZEN", it.text("freeze_state"))
    }
    val fixtures = root.getValue("fixtures").jsonObject
    val base = fixtures.getValue("F-ST-RPC-BASE").jsonObject
    private val aliases = base.getValue("aliases").jsonObject
    val original = base.getValue("original_operation").jsonObject
    val sourceInput = base.getValue("source_position").jsonObject
    val destinationInput = base.getValue("destination_position").jsonObject
    val expected = base.getValue("expected_transfer").jsonObject
    fun id(alias: String) = DomainId(aliases.text(alias))
    val scope = BranchScope(id("O1"), id("C1"), id(original.text("branch")))
    val actor = id(original.text("actor"))
    val device = id(original.text("device"))
    val costActor = id("E4")
    val owner = LocalClientIdentity(scope, actor, device)
    val operation = OperationId(aliases.text(original.text("operation")))
    val otherOperation = OperationId(aliases.text("OP_OTHER"))
    val sku = id(original.text("source_sku"))
    val missingSku = id("SKU_MISSING")
    val revision = Revision(original.text("source_revision").toLong())
    val at = UtcInstant.parseCanonical(root.text("fixture_time_utc"))
    val reason = original.text("reason")
    fun slot(name: String): DomainId = DomainId(UUID.nameUUIDFromBytes(
        "stock-transfer-rpc-support-v2:$name".encodeToByteArray()).toString())
    val unit = slot("unit-kg")
    val transferId = slot("transfer-document")
    val sourceEventId = slot("source-event")
    val destinationEventId = slot("destination-event")
    private val connection = slot("connection")
    private val session = slot("session")
    private val clock = slot("clock")
    val costDevice = slot("cost-reader-device")
    val scale = base.getValue("sku").jsonObject.getValue("allowed_fraction_digits").jsonPrimitive.int
    // Schema-only configuration/valuation cuts, not expected native history revisions.
    val schemaRevision = Revision(1)
    val warehouseStorageRevision = Revision(2)
    fun quantity(value: String) = Quantity.parse(unit, value, scale)
    val zero = quantity(sourceInput.text("reserved"))
    val source = LocalStockPositionRef(sku, id(original.text("source_warehouse")),
        id(original.text("source_lot")), StockCustody.valueOf(sourceInput.text("state")))
    val destination = source.copy(warehouseId = id(original.text("destination")))
    val existingDestination = destination.copy(lotId = id(destinationInput.text("lot")))
    val positionId = LocalStockRelationalProfileV2.positionKey(source.inScope(scope)).id
    val sourceReference = LocalRecordReference(LocalRecordKind.STOCK_CUT, positionId, parentId = sku)
    val destinationSet = base.getValue("expected_destination_set").jsonArray.map { id(it.jsonPrimitive.content) }
    val wire = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }

    fun intent(operation: OperationId = this.operation, quantity: String = original.text("quantity"),
        revision: Revision = this.revision, destinationAlias: String = original.text("destination")) = LocalCommandIntent(
        operation, LocalAction.WAREHOUSE_TRANSFER, 1, sourceReference, listOf(
            LocalScalarInput(LocalField.TRANSFER_ID, LocalScalarValue.Identifier(transferId)),
            LocalScalarInput(LocalField.SOURCE_EVENT_ID, LocalScalarValue.Identifier(sourceEventId)),
            LocalScalarInput(LocalField.DESTINATION_EVENT_ID, LocalScalarValue.Identifier(destinationEventId)),
            LocalScalarInput(LocalField.POSITION_ID, LocalScalarValue.Reference(sourceReference)),
            LocalScalarInput(LocalField.WAREHOUSE_ID, LocalScalarValue.Identifier(source.warehouseId)),
            LocalScalarInput(LocalField.LOT_ID, LocalScalarValue.Identifier(checkNotNull(source.lotId))),
            LocalScalarInput(LocalField.CUSTODY, LocalScalarValue.Choice(source.custody.name)),
            LocalScalarInput(LocalField.UNIT, LocalScalarValue.Identifier(unit)),
            LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(scale.toLong())),
            LocalScalarInput(LocalField.EXPECTED_ITEM_STORAGE_REVISION, LocalScalarValue.Integer(revision.value)),
            LocalScalarInput(LocalField.DESTINATION_ID, LocalScalarValue.Reference(
                LocalRecordReference(LocalRecordKind.WAREHOUSE, id(destinationAlias), schemaRevision))),
            LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal(quantity)),
            LocalScalarInput(LocalField.REASON, LocalScalarValue.Text(reason)),
        ))

    fun transfer() = LocalWarehouseTransferRequest(transferId, sourceEventId, destinationEventId, source,
        destination.warehouseId, revision, quantity(original.text("quantity")), reason)
    fun request(operation: OperationId = this.operation) = LocalStockRelationalCommandsV2.transfer(operation, transfer())
    fun query(reference: LocalRecordReference = LocalRecordReference(LocalRecordKind.SKU, sku),
        section: LocalInventorySection = LocalInventorySection.QUANTITIES) =
        LocalWorkflowQuery(LocalWorkflow.INVENTORY, reference = reference, inventory = section)
    fun transferQuery(section: LocalInventorySection = LocalInventorySection.QUANTITIES) =
        query(LocalRecordReference(LocalRecordKind.TRANSFER, transferId), section)

    fun assertOriginal(entry: StoredClientIntentV1, operation: OperationId = this.operation) {
        entry.validate(owner)
        assertEquals(owner, entry.owner)
        assertEquals(operation, entry.operationId)
        assertEquals(LocalAction.WAREHOUSE_TRANSFER, entry.localAction)
        assertEquals(1, entry.formVersion)
        assertEquals(root.getValue("constants").jsonObject.text("execute_command_key"),
            "${entry.commandName}/${entry.commandVersion}")
        assertEquals(request(operation).command, entry.request().command)
        assertEquals(request(operation).payload, entry.request().payload)
        assertNull(entry.request().actionApprovalId)
        val body = assertIs<LocalStockRelationalCommand.Transfer>(LocalStockRelationalCommandsV2.decode(entry.request().payload))
        assertEquals(operation, body.operationId)
        assertEquals(transfer(), body.request)
    }

    private fun grant(action: SecurityAction, targets: Set<DomainId>? = null) = PermissionGrant(
        Permission(action, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW, targetIds = targets)
    fun grants(execute: Boolean = true, costs: Boolean = false): List<PermissionGrant> = buildList {
        add(grant(SecurityAction.INVENTORY_QUANTITY_READ, setOf(sku)))
        if (execute) add(grant(SecurityAction.INVENTORY_ADJUST, setOf(transferId)))
        if (costs) add(grant(SecurityAction.INVENTORY_COST_READ, setOf(sku)))
        if (execute) add(grant(SecurityAction.OPERATION_RESULT_RECOVERY))
    }
    fun view(actor: DomainId = this.actor, device: DomainId = this.device,
        grants: List<PermissionGrant> = this.grants()): LocalSessionView {
        val policy = ActivatedLocalPolicy(scope, actor, schemaRevision, grants,
            LocalSessionPolicy(1), ThrottlePolicy(0, 1_000, 1_000, 1_000))
        return LocalSessionView(actor, scope, device, session, SessionGeneration(1),
            LocalLoginIdentifier("stock-transfer-support"), policy,
            MonotonicMoment(clock, 100_000), MonotonicMoment(clock, 100_000))
    }
    fun credentials(): LocalGatewayCredentials {
        val bytes = ByteArray(32) { (it + 1).toByte() }
        return try { LocalGatewayCredentials(connection, OpaqueCredential(bytes)) } finally { bytes.fill(0) }
    }
    fun bytes(value: JsonElement) = StoredBytes(wire.encodeToString(JsonElement.serializer(), value).encodeToByteArray())
    fun data(value: JsonElement) = ReadReply.Data(bytes(value), schemaRevision, at)
    fun profileData(normalized: Boolean) = data(buildJsonObject {
        put("version", 2); put("normalized", normalized)
    })
    private fun positionJson(position: LocalStockPositionRef) = buildJsonObject {
        put("skuId", wire.encodeToJsonElement(DomainId.serializer(), position.skuId))
        put("warehouseId", wire.encodeToJsonElement(DomainId.serializer(), position.warehouseId))
        put("lotId", position.lotId?.let { wire.encodeToJsonElement(DomainId.serializer(), it) } ?: JsonNull)
        put("custody", position.custody.name); put("supplierId", JsonNull)
    }
    private fun quantityRow(position: LocalStockPositionRef, amount: String, revision: Revision) = buildJsonObject {
        val quantity = quantity(amount)
        put("id", wire.encodeToJsonElement(DomainId.serializer(),
            LocalStockRelationalProfileV2.positionKey(position.inScope(scope)).id))
        put("position", positionJson(position)); put("revision", wire.encodeToJsonElement(Revision.serializer(), revision))
        put("onHand", wire.encodeToJsonElement(Quantity.serializer(), quantity))
        put("reserved", wire.encodeToJsonElement(Quantity.serializer(), zero))
        put("available", wire.encodeToJsonElement(Quantity.serializer(), quantity))
        put("safety", StockSafety.CLEAR.name)
        put("expiresAt", if (position.lotId == source.lotId) wire.encodeToJsonElement(UtcInstant.serializer(),
            UtcInstant.parseCanonical(sourceInput.text("expiry_utc"))) else JsonNull)
        put("expiredAtRead", false)
        put("heldQuantity", wire.encodeToJsonElement(Quantity.serializer(), zero))
        put("heldShortageQuantity", wire.encodeToJsonElement(Quantity.serializer(), zero))
    }

    /** Controlled DTO assembly only. No stock transaction or persisted readback is supplied. */
    fun quantitiesData(request: ReadRequest, currentRevision: Revision, afterTransfer: Boolean): ReadReply.Data {
        val query = Json.parseToJsonElement(request.payload.copyBytes().decodeToString()).jsonObject
        val exact = request.query == LocalStockStoreProfile.QUANTITY_POSITION
        val requestedSku = DomainId(query.text(if (exact) "skuId" else "id"))
        val missing = requestedSku == missingSku
        check(requestedSku == sku || missing)
        val allRows = if (missing) emptyList() else buildList {
            add(quantityRow(source, if (afterTransfer) expected.getValue("source_after").jsonObject.text("quantity")
                else sourceInput.text("quantity"), currentRevision))
            add(quantityRow(existingDestination, destinationInput.text("quantity"), schemaRevision))
            if (afterTransfer) add(quantityRow(destination, expected.getValue("moved").jsonObject.text("quantity"), currentRevision))
        }.sortedBy { it.text("id") }
        val rows = if (exact) allRows.filter { it.text("id") == query.text("positionId") }
            else allRows.filter { row -> query["after"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                ?.let { row.text("id") > it } ?: true }.take(query.getValue("limit").jsonPrimitive.int)
        return data(buildJsonObject {
            put("version", 2); put("skuId", wire.encodeToJsonElement(DomainId.serializer(), requestedSku))
            put("revision", if (missing) JsonNull else wire.encodeToJsonElement(Revision.serializer(), currentRevision))
            put("unitId", if (missing) JsonNull else wire.encodeToJsonElement(DomainId.serializer(), unit))
            put("scale", if (missing) JsonNull else JsonPrimitive(scale)); put("rows", JsonArray(rows)); put("nextAfter", JsonNull)
        })
    }
    fun configurationData(request: ReadRequest): ReadReply.Data {
        val query = LocalConfigurationCodec.decodeQuery(request.payload)
        check(query.kind == LocalConfigurationProfile.WAREHOUSE && query.barcode == null)
        // The controlled reply already has the frozen B1 scope. Actual Master scope enforcement is separate.
        val selected = base.getValue("warehouses").jsonArray.map { it.jsonObject }.filter {
            it.text("branch") == original.text("branch") && (query.id == null || id(it.text("id")) == query.id) &&
                (query.after == null || id(it.text("id")).value > checkNotNull(query.after).value)
        }.sortedBy { id(it.text("id")).value }.take(query.limit).map { value ->
            val alias = value.text("id")
            LocalConfigurationListItemV1(id(alias), schemaRevision, warehouseStorageRevision,
                LocalNamesV1("Warehouse $alias", "مخزن $alias"), value.getValue("active").jsonPrimitive.boolean, null)
        }
        val page = LocalConfigurationPageV1(LocalConfigurationProfile.WAREHOUSE, selected, null,
            detail = if (query.id != null) selected.singleOrNull()?.let { row ->
                LocalConfigurationDetailV1.Warehouse(LocalWarehouseV1(row.id, checkNotNull(row.names), checkNotNull(row.active)))
            } else null)
        return data(wire.encodeToJsonElement(LocalConfigurationPageV1.serializer(), page))
    }
    fun resultPayload(document: DomainId = transferId, item: DomainId = sku, itemRevision: Revision = revision.next()) = bytes(buildJsonObject {
        put("kind", LocalStockResultKind.TRANSFERRED.name)
        put("documentId", wire.encodeToJsonElement(DomainId.serializer(), document))
        put("items", buildJsonArray { add(buildJsonObject {
            put("skuId", wire.encodeToJsonElement(DomainId.serializer(), item))
            put("revision", wire.encodeToJsonElement(Revision.serializer(), itemRevision))
        }) }); put("failure", JsonNull)
    })
    /** A typed terminal carrier, not a real business commit, authority epoch or journal sequence. */
    fun recorded(operation: OperationId = this.operation, actor: DomainId = this.actor,
        payload: StoredBytes = resultPayload(), replayed: Boolean = false) = MutationReply.Recorded(
        operation, actor, true, "committed", payload, schemaRevision, JournalSequence(1), at, replayed)
    fun replay(reply: MutationReply.Recorded) = MutationReply.Recorded(reply.operationId, reply.originalActorId,
        reply.committed, reply.code, reply.result, reply.authorityEpoch, reply.sequence, reply.recordedAt, true)
    fun transferData(costs: Boolean) = data(buildJsonObject {
        put("version", 2)
        put("value", buildJsonObject {
            put("id", wire.encodeToJsonElement(DomainId.serializer(), transferId))
            put("revision", wire.encodeToJsonElement(Revision.serializer(), schemaRevision))
            put("sourceEventId", wire.encodeToJsonElement(DomainId.serializer(), sourceEventId))
            put("destinationEventId", wire.encodeToJsonElement(DomainId.serializer(), destinationEventId))
            put("source", positionJson(source)); put("destination", positionJson(destination))
            put("quantity", wire.encodeToJsonElement(Quantity.serializer(), quantity(original.text("quantity"))))
            put("transferredBy", wire.encodeToJsonElement(DomainId.serializer(), actor))
            put("recordedAt", wire.encodeToJsonElement(UtcInstant.serializer(), at)); put("reasonCode", reason)
            put("allocationId", wire.encodeToJsonElement(DomainId.serializer(), transferId))
            put("sourceValuationRevision", wire.encodeToJsonElement(Revision.serializer(), schemaRevision))
            put("destinationValuationRevision", wire.encodeToJsonElement(Revision.serializer(), schemaRevision))
            put("calculationVersion", MovingAveragePlanner.CALCULATION_VERSION); put("costsIncluded", costs)
            put("carriedValue", if (costs) wire.encodeToJsonElement(Money.serializer(), Money(Currency.EGP,
                expected.getValue("moved").jsonObject.text("carrying_value_minor").toLong())) else JsonNull)
            put("linkedCostDelta", if (costs) wire.encodeToJsonElement(Money.serializer(), Money(Currency.EGP, 0)) else JsonNull)
            put("serial", JsonNull)
        })
    })
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}

/** Reuses controlled order ports; none authenticate custody or provide native durability. */
internal class StockTransferRpcV2GatewayFixture(
    owner: LocalClientIdentity = FrozenStockTransferRpcV2.owner,
    initialView: LocalSessionView = FrozenStockTransferRpcV2.view(),
) {
    private val input = FrozenStockTransferRpcV2
    init { require(owner == LocalClientIdentity(initialView.scope, initialView.actorId, initialView.deviceId)) }
    val events = mutableListOf<String>()
    val master = WarehouseRpcControlledMaster(events).also { master ->
        master.view = initialView
        master.currentReply = { LocalSessionViewResult.Current(master.view) }
        master.readReply = ::read
        master.executeReply = { request ->
            assertEquals(input.request(request.operationId).payload, request.payload)
            input.recorded(request.operationId).also { master.terminalCarriers[request.operationId] = it }
        }
        master.lookupReply = { identity -> master.terminalCarriers[identity.operationId]
            ?.let { LookupReply.Found(input.replay(it)) } ?: LookupReply.AbsentAtCurrentWriter(identity.operationId) }
    }
    val journal = WarehouseRpcControlledJournal(owner, events)
    var current = true
    var normalized = true
    var sourceRevision = input.revision
    var afterTransfer = false
    // Scripted allowed/denied cost replies are not a current native grant evaluator.
    var costReplyAllowed = false
    private val leases = mutableListOf<LocalWorkflowCredentialLease>()
    private val credentials = LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(input.credentials()) { current }.also(leases::add)
    }
    val installed = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS)
    val gateway = LocalEndpointWorkflowGateway(master, journal, credentials, installed)

    private fun read(request: ReadRequest): ReadReply = when (request.query) {
        LocalStockRelationalQueriesV2.PROFILE -> input.profileData(normalized)
        LocalStockStoreProfile.QUANTITIES, LocalStockStoreProfile.QUANTITY_POSITION ->
            input.quantitiesData(request, sourceRevision, afterTransfer)
        LocalConfigurationProfile.QUERY -> input.configurationData(request)
        LocalStockStoreProfile.TRANSFER_DETAIL -> input.transferData(false)
        LocalStockStoreProfile.TRANSFER_COSTS -> if (costReplyAllowed) input.transferData(true)
            else ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
        else -> error("No stock-transfer supporting reply is configured for ${request.query}")
    }
    suspend fun close() { leases.forEach { it.destroy() }; journal.close() }
}

internal suspend fun <T> withStockTransferRpcV2Fixture(
    owner: LocalClientIdentity = FrozenStockTransferRpcV2.owner,
    initialView: LocalSessionView = FrozenStockTransferRpcV2.view(),
    body: suspend (StockTransferRpcV2GatewayFixture) -> T,
): T {
    val fixture = StockTransferRpcV2GatewayFixture(owner, initialView)
    return try { body(fixture) } finally { fixture.close() }
}

internal fun JsonObject.stockTransferText(key: String): String = getValue(key).jsonPrimitive.content
