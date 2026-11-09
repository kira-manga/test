package com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Unchanged warehouse-rpc-workflow/v1 inputs. Named extra slots are inert SUPPORT carriers. */
internal object FrozenWarehouseRpcV1 {
    private val directory = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/warehouse-rpc-workflow") }
        .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
        ?: error("Frozen warehouse workflow inputs are unavailable")
    val frozen: JsonObject = Json.parseToJsonElement(Files.readString(directory.resolve("fixtures.json")))
        .jsonObject.also { assertEquals("warehouse-rpc-workflow/v1", it.text("design_version")) }
    val auth = frozen.getValue("F-AUTH").jsonObject
    val directoryInputs = frozen.getValue("F-DIRECTORY").jsonObject
    val createInputs = frozen.getValue("F-CREATE").jsonObject
    val renameInputs = frozen.getValue("F-RENAME").jsonObject
    val retireInputs = frozen.getValue("F-RETIRE").jsonObject
    val invalidInputs = frozen.getValue("F-INVALID").jsonObject
    val profileInputs = frozen.getValue("F-PROFILE").jsonObject
    val bearerInputs = frozen.getValue("F-BEARER").jsonObject
    private fun authId(key: String) = DomainId(auth.text(key))
    val scope = BranchScope(authId("organization_a"), authId("company_a"), authId("branch_a"))
    val otherScope = scope.copy(branchId = authId("branch_b"))
    val actor = authId("actor_a")
    val actorB = authId("actor_b")
    val device = authId("device_a")
    val otherDevice = authId("device_second")
    val owner = LocalClientIdentity(scope, actor, device)
    val deviceOwner = LocalClientDeviceIdentity(scope, device)
    val policyRevision = Revision(auth.text("activated_policy_revision").toLong())
    val observedRevision = Revision(directoryInputs.text("observed_branch_revision").toLong())
    val storageRevision = Revision(directoryInputs.getValue("generation").jsonObject.text("storage_revision").toLong())
    // The existing warehouse projection joins domain revision to storage revision minus one.
    // This is a representation of the frozen storage-12 seed, not an invented commit result.
    val seedDomainRevision = Revision(storageRevision.value - 1)
    val warehouses: List<LocalWarehouseV1> = run {
        val bounds = directoryInputs.getValue("generation").jsonObject.getValue("i_inclusive").jsonArray
        (bounds[0].jsonPrimitive.int..bounds[1].jsonPrimitive.int).map { i ->
            LocalWarehouseV1(DomainId("018b1000-0000-7000-8000-${i.toString(16).padStart(12, '0')}"),
                LocalNamesV1("Warehouse ${i.toString().padStart(3, '0')}", "مخزن ${i.toString().padStart(3, '0')}"), true)
        }.also { assertEquals(directoryInputs.getValue("initial_count").jsonPrimitive.int, it.size) }
    }
    val first = warehouses.first()
    val second = warehouses[1]
    val created = warehouse(createInputs, "warehouse_id")
    val renamed = warehouse(renameInputs, "warehouse_id")
    val createOperation = OperationId(createInputs.text("operation_id"))
    val renameOperation = OperationId(renameInputs.text("operation_id"))
    val competingOperation = OperationId(renameInputs.text("competing_operation_id"))
    val retireExistingOperation = OperationId(retireInputs.text("existing_operation_id"))
    val retireNewOperation = OperationId(retireInputs.text("new_operation_id"))
    val otherOperation = OperationId(frozen.getValue("F-ACK").jsonObject.text("original_b_id"))
    val retirementCode = retireInputs.text("expected_reason")
    val wire = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
    // These slots identify controlled DTOs only; they do not prove an admitted peer or a session.
    fun slot(name: String): DomainId = DomainId(UUID.nameUUIDFromBytes(
        ("warehouse-rpc-support-v1:$name").encodeToByteArray()).toString())
    val connection = slot("connection")
    val session = slot("session")
    val boot = slot("clock")
    val at = UtcInstant.parseCanonical("2026-11-04T00:00:00Z")

    private fun warehouse(value: JsonObject, idKey: String) = LocalWarehouseV1(DomainId(value.text(idKey)),
        LocalNamesV1(value.text("name_en"), value.text("name_ar")), value.getValue("active").jsonPrimitive.boolean)

    fun grant(action: SecurityAction, verb: PermissionVerb, targets: Set<DomainId>? = null) =
        PermissionGrant(Permission(action, verb), scope, GrantEffect.ALLOW, targetIds = targets)
    fun grants(read: Boolean = true, configure: Boolean = true, recovery: Boolean = true): List<PermissionGrant> = buildList {
        if (read) add(grant(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM))
        if (configure) add(grant(SecurityAction.WAREHOUSE_CONFIGURE, PermissionVerb.ADMINISTER))
        if (recovery) add(grant(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM))
    }
    fun view(actor: DomainId = this.actor, device: DomainId = this.device, scope: BranchScope = this.scope,
        grants: List<PermissionGrant> = this.grants()): LocalSessionView {
        val policy = ActivatedLocalPolicy(scope, actor, policyRevision, grants,
            LocalSessionPolicy(1), ThrottlePolicy(0, 1_000, 1_000, 1_000))
        return LocalSessionView(actor, scope, device, session, SessionGeneration(1), LocalLoginIdentifier("warehouse-support"),
            policy, MonotonicMoment(boot, 100_000), MonotonicMoment(boot, 100_000))
    }
    fun bearer(): ByteArray = bearerInputs.text("original_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun credentials(): LocalGatewayCredentials {
        val bytes = bearer()
        return try { LocalGatewayCredentials(connection, OpaqueCredential(bytes)) } finally { bytes.fill(0) }
    }
    fun query(reference: LocalRecordReference? = null, search: String = "", cursor: String? = null) =
        LocalWorkflowQuery(LocalWorkflow.SETUP, search = search, reference = reference, cursor = cursor)
    fun reference(value: LocalWarehouseV1 = first) =
        LocalRecordReference(LocalRecordKind.WAREHOUSE, value.id, seedDomainRevision)

    fun create() = intent(createOperation, created, null)
    fun rename() = intent(renameOperation, renamed,
        Revision(renameInputs.text("displayed_expected_storage_revision").toLong()), reference(first))
    fun retire(existing: Boolean): LocalCommandIntent = if (existing)
        intent(retireExistingOperation, first.copy(active = false),
            Revision(retireInputs.text("existing_expected_storage_revision").toLong()), reference(first))
    else intent(retireNewOperation, created.copy(active = false), null)
    fun intent(operation: OperationId, warehouse: LocalWarehouseV1, expected: Revision?,
        reference: LocalRecordReference? = null, version: Int = 1) = LocalCommandIntent(operation,
        LocalAction.WAREHOUSE_CONFIGURE, version, reference, buildList {
            add(LocalScalarInput(LocalField.WAREHOUSE_ID, LocalScalarValue.Identifier(warehouse.id)))
            add(LocalScalarInput(LocalField.NEW_ITEM, LocalScalarValue.Flag(expected == null)))
            if (expected != null) add(LocalScalarInput(LocalField.EXPECTED_STORAGE_REVISION, LocalScalarValue.Integer(expected.value)))
            add(LocalScalarInput(LocalField.NAME_ENGLISH, LocalScalarValue.Text(warehouse.names.english)))
            add(LocalScalarInput(LocalField.NAME_ARABIC, LocalScalarValue.Text(warehouse.names.arabic)))
            add(LocalScalarInput(LocalField.ACTIVE, LocalScalarValue.Flag(warehouse.active)))
        })
    fun body(intent: LocalCommandIntent): LocalConfigurationRequestV1.ConfigureWarehouse {
        fun value(field: LocalField) = intent.values.single { it.field == field }.value
        val expected = intent.values.singleOrNull { it.field == LocalField.EXPECTED_STORAGE_REVISION }?.value
            ?.let { Revision((it as LocalScalarValue.Integer).value) }
        return LocalConfigurationRequestV1.ConfigureWarehouse(expected, LocalWarehouseV1(
            (value(LocalField.WAREHOUSE_ID) as LocalScalarValue.Identifier).value,
            LocalNamesV1((value(LocalField.NAME_ENGLISH) as LocalScalarValue.Text).value,
                (value(LocalField.NAME_ARABIC) as LocalScalarValue.Text).value),
            (value(LocalField.ACTIVE) as LocalScalarValue.Flag).value))
    }
    fun request(intent: LocalCommandIntent) = MutationRequest(intent.operationId, LocalConfigurationProfile.COMMAND,
        LocalConfigurationCodec.encode(body(intent)))
    fun saved(intent: LocalCommandIntent, owner: LocalClientIdentity = this.owner) = StoredClientIntentV1.staged(owner,
        intent, request(intent), LocalText("Warehouse original", "عملية المخزن الأصلية"),
        listOf(LocalRecordValue(LocalText("Warehouse", "المخزن"),
            LocalText(body(intent).warehouse.names.english, body(intent).warehouse.names.arabic))))
    fun assertOriginal(intent: LocalCommandIntent, retained: StoredClientIntentV1) {
        val expected = saved(intent, retained.owner)
        assertEquals(intent.operationId, retained.operationId)
        assertEquals(owner, retained.owner)
        assertEquals(expected.request().command, retained.request().command)
        assertEquals(expected.request().payload, retained.request().payload)
        assertEquals(expected.semanticSha256, retained.semanticSha256)
        assertEquals(expected.inputDigest, retained.inputDigest)
        assertNull(retained.request().actionApprovalId)
        assertEquals(body(intent), assertIs<LocalConfigurationRequestV1.ConfigureWarehouse>(
            LocalConfigurationCodec.decode(retained.request().payload)))
    }
    fun row(value: LocalWarehouseV1) = LocalConfigurationListItemV1(value.id, seedDomainRevision,
        storageRevision, value.names, value.active, null)
    fun data(page: LocalConfigurationPageV1, revision: Revision = observedRevision) = ReadReply.Data(
        bytes(wire.encodeToJsonElement(LocalConfigurationPageV1.serializer(), page)), revision, at)
    fun bytes(value: JsonElement) = StoredBytes(wire.encodeToString(JsonElement.serializer(), value).encodeToByteArray())
    /** A controlled projection, NOT the production keyset/storage implementation. */
    fun read(request: ReadRequest): ReadReply {
        val query = LocalConfigurationCodec.decodeQuery(request.payload)
        val after = query.after
        val selected = warehouses.filter { (query.id == null || it.id == query.id) &&
            (after == null || it.id.value > after.value) }
        val page = selected.take(query.limit)
        return data(LocalConfigurationPageV1(LocalConfigurationProfile.WAREHOUSE, page.map(::row),
            if (selected.size > page.size) page.last().id else null,
            detail = if (query.id != null) page.singleOrNull()?.let { LocalConfigurationDetailV1.Warehouse(it) } else null))
    }
    /** Inert terminal carrier; no mutation, durability, audit/outbox or writer is supplied here. */
    fun recorded(request: MutationRequest, replayed: Boolean = false): MutationReply.Recorded {
        val body = assertIs<LocalConfigurationRequestV1.ConfigureWarehouse>(LocalConfigurationCodec.decode(request.payload))
        val committed = body.warehouse.active
        val code = if (committed) "committed" else retirementCode
        val value = if (committed) LocalConfigurationResultV1("configuration.applied", body.warehouse.id,
            body.expectedStorageRevision ?: Revision(0)) else LocalConfigurationResultV1(code, null, null)
        return MutationReply.Recorded(request.operationId, actor, committed, code,
            bytes(wire.encodeToJsonElement(LocalConfigurationResultV1.serializer(), value)),
            Revision(1), JournalSequence(1), at, replayed)
    }
    fun replay(value: MutationReply.Recorded, operation: OperationId = value.operationId,
        actor: DomainId = value.originalActorId, result: StoredBytes = value.result,
        sequence: JournalSequence = value.sequence): MutationReply.Recorded = MutationReply.Recorded(operation, actor,
        value.committed, value.code, result, value.authorityEpoch, sequence, value.recordedAt, true)
}

internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

/** An event barrier over a controlled port, not evidence of a native/durable milestone. */
internal class WarehouseRpcGate {
    val entered = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    suspend fun await() { entered.complete(Unit); released.await() }
    fun release() { released.complete(Unit) }
}

internal enum class WarehouseRpcWriteCut(val retain: Boolean, val answer: JournalWrite) {
    DURABLE(true, JournalWrite.DURABLE), FAILED(false, JournalWrite.UNAVAILABLE),
    RETAINED_REPLY_UNKNOWN(true, JournalWrite.UNAVAILABLE),
}

/** Only the shared gateway's data/order handling is under test; this map is not protected storage. */
internal class WarehouseRpcControlledJournal(owner: LocalClientIdentity, val events: MutableList<String>) : LocalClientIntentJournal(owner) {
    val entries = linkedMapOf<OperationId, StoredClientIntentV1>()
    var readable = true
    var stageCut = WarehouseRpcWriteCut.DURABLE
    var dispatchCut = WarehouseRpcWriteCut.DURABLE
    var rememberCut = WarehouseRpcWriteCut.DURABLE
    var ackCut = WarehouseRpcWriteCut.DURABLE
    var stageGate: WarehouseRpcGate? = null
    var dispatchStoredGate: WarehouseRpcGate? = null
    var rememberGate: WarehouseRpcGate? = null
    var ackGate: WarehouseRpcGate? = null
    var stageCalls = 0
    var dispatchCalls = 0
    var rememberCalls = 0
    var ackCalls = 0
    val cleared = mutableListOf<OperationId>()
    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> =
        if (readable) JournalRead.Value(entries[operationId]) else JournalRead.Unavailable
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> =
        if (readable) JournalRead.Value(entries.values.filterNot { it.acknowledged }) else JournalRead.Unavailable
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        stageCalls++
        intent.validate(owner)
        events += "intent.before-retention"
        stageGate?.await()
        val prior = entries[intent.operationId]
        if (prior != null && !prior.sameIntent(intent)) return JournalWrite.IDENTITY_CONFLICT
        if (stageCut.retain) { entries[intent.operationId] = prior ?: intent; events += "intent.retained" }
        return stageCut.answer
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite {
        dispatchCalls++
        val original = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        if (dispatchCut.retain) {
            entries[operationId] = original.copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED.name)
            events += "dispatch.retained"
        }
        dispatchStoredGate?.await()
        return dispatchCut.answer
    }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        rememberCalls++
        val original = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        events += "result.before-retention"
        rememberGate?.await()
        if (original.recorded != null && original.recorded != result) return JournalWrite.IDENTITY_CONFLICT
        if (rememberCut.retain) {
            entries[operationId] = original.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = result)
            events += "result.retained"
        }
        return rememberCut.answer
    }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        ackCalls++
        val original = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        if (original.recorded != result) return JournalWrite.IDENTITY_CONFLICT
        events += "ack.before-retention"
        ackGate?.await()
        if (ackCut.retain) {
            entries[operationId] = original.copy(acknowledged = true)
            if (!original.acknowledged) cleared += operationId
            events += "ack.retained"
        }
        return ackCut.answer
    }
    override suspend fun close() = Unit
}

/** Scripted replies do not authenticate the synthetic bearer or enforce Master authority. */
internal class WarehouseRpcControlledMaster(private val events: MutableList<String>) : LocalMasterClient {
    override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    var view = FrozenWarehouseRpcV1.view()
    var currentReply: () -> LocalSessionViewResult = { LocalSessionViewResult.Current(view) }
    val reads = mutableListOf<ReadRequest>()
    val mutations = mutableListOf<MutationRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    val terminalCarriers = linkedMapOf<OperationId, MutationReply.Recorded>()
    var readGate: WarehouseRpcGate? = null
    var executeGate: WarehouseRpcGate? = null
    var lookupGate: WarehouseRpcGate? = null
    var readReply: (ReadRequest) -> ReadReply = FrozenWarehouseRpcV1::read
    var lookupReply: (OperationIdentity) -> LookupReply = { identity -> terminalCarriers[identity.operationId]
        ?.let { LookupReply.Found(FrozenWarehouseRpcV1.replay(it)) } ?: LookupReply.AbsentAtCurrentWriter(identity.operationId) }
    var executeReply: (MutationRequest) -> MutationReply = { request -> FrozenWarehouseRpcV1.recorded(request)
        .also { terminalCarriers[request.operationId] = it } }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = LocalSignOutResult.Unavailable
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = currentReply()
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        readGate?.await()
        return readReply(request)
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        lookupGate?.await()
        return lookupReply(identity)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        events += "execute.enter"
        mutations += request
        val reply = executeReply(request)
        executeGate?.await()
        return reply
    }
}

internal class WarehouseRpcGatewayFixture(profile: LocalMasterRpcWorkflowProfile = LocalMasterRpcWorkflowProfile.WAREHOUSES) {
    val events = mutableListOf<String>()
    val master = WarehouseRpcControlledMaster(events)
    val journal = WarehouseRpcControlledJournal(FrozenWarehouseRpcV1.owner, events)
    var current = true
    private val leases = mutableListOf<LocalWorkflowCredentialLease>()
    val credentials = LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(FrozenWarehouseRpcV1.credentials()) { current }.also(leases::add)
    }
    val installed = LocalWorkflowInstallation.directoryOnly(profile)
    val gateway = LocalEndpointWorkflowGateway(master, journal, credentials, installed)
    suspend fun <T> withReadAccess(body: suspend (GatewayAccess) -> T): T {
        val lease = LocalWorkflowCredentialLease(FrozenWarehouseRpcV1.credentials()) { current }
        return try { body(GatewayAccess(master, lease, master.view, installed)) } finally { lease.destroy() }
    }
    suspend fun close() { leases.forEach { it.destroy() }; journal.close() }
}

internal suspend fun <T> withWarehouseRpcFixture(profile: LocalMasterRpcWorkflowProfile = LocalMasterRpcWorkflowProfile.WAREHOUSES,
    body: suspend (WarehouseRpcGatewayFixture) -> T): T {
    val fixture = WarehouseRpcGatewayFixture(profile)
    return try { body(fixture) } finally { fixture.close() }
}
