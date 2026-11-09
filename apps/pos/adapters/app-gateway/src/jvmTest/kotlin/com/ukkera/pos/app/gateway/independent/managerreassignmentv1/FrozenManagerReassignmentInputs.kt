package com.ukkera.pos.app.gateway.independent.managerreassignmentv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Semantic bindings for frozen manager-reassignment v1; none is native authority or provisioning. */
internal object FrozenManagerReassignmentV1 {
    private val coordinates = mapOf(
        "O1" to 1, "C1" to 2, "B1" to 3, "MGR-B1" to 4, "EMP-C" to 5,
        "EMP-A" to 6, "EMP-B" to 7, "EMP-D" to 8, "SESSION-MGR-ORIGINAL" to 9,
        "DEVICE-MGR-B1" to 10, "ORDER-MAIN" to 11, "OP-MRA-001" to 12,
        "EVIDENCE-MGR-001" to 13, "LINE-SHIRT" to 14, "PORTION-SHIRT" to 15,
        "SKU-SHIRT-M-BLUE" to 16, "LINE-FABRIC" to 17, "PORTION-FABRIC" to 18,
        "SKU-FABRIC" to 19, "UNIT-EACH" to 20, "UNIT-METRE" to 21,
        "LEGACY-HANDOFF-COMPLETED" to 30, "ORDER-A-OTHER" to 33,
        "EVIDENCE-MGR-002" to 34, "UNIT-CLOCK" to 35, "CONNECTION" to 36,
        "VARIANT-SHIRT-M-BLUE" to 39,
    )
    fun id(semantic: String): DomainId = DomainId("4d520000-0000-4000-8000-" +
        coordinates.getValue(semantic).toString().padStart(12, '0'))
    fun operation(semantic: String = "OP-MRA-001"): OperationId = OperationId(id(semantic).value)
    val scope = BranchScope(id("O1"), id("C1"), id("B1"))
    val manager = id("MGR-B1")
    val creator = id("EMP-C")
    val previousOwner = id("EMP-A")
    val recipient = id("EMP-B")
    val alternateRecipient = id("EMP-D")
    val target = id("ORDER-MAIN")
    val session = id("SESSION-MGR-ORIGINAL")
    val device = id("DEVICE-MGR-B1")
    // Public create -> offer -> accept yields revision 2. R17 is an equality token, not 17.
    val r16 = Revision(1)
    val r17 = Revision(2)
    val r18 = Revision(3)
    val reason = "OWNER_ABSENT.cover-20261030"
    val attestedAt = UtcInstant.parseCanonical("2026-10-30T09:00:00Z")
    val committedAt = UtcInstant.parseCanonical("2026-10-30T09:05:00Z")
    val acceptedReasons = listOf("A", "a".repeat(128), reason, "غياب_الموظف١")
    val rejectedReasons = listOf("", "a".repeat(129), "OWNER ABSENT", " OWNER_ABSENT",
        "OWNER_ABSENT\n", "OWNER/ABSENT", "ABSENT🙂", "e\u0301")
    val wire = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        encodeDefaults = true
        isLenient = false
        coerceInputValues = false
    }

    fun request(reasonCode: String = reason) = LocalCollectionStockRequestV2.ReassignOrder(
        target, r17, previousOwner, recipient, reasonCode,
        LocalCashEvidenceV1(id("EVIDENCE-MGR-001"), attestedAt))

    fun intent(request: LocalCollectionStockRequestV2.ReassignOrder = this.request(),
        operationId: OperationId = operation(), formVersion: Int = 2,
        values: List<LocalScalarInput> = inputs(request)): LocalCommandIntent =
        LocalCommandIntent(operationId, LocalAction.ORDER_REASSIGN, formVersion, null, values)

    fun inputs(request: LocalCollectionStockRequestV2.ReassignOrder = this.request()): List<LocalScalarInput> = listOf(
        LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(request.orderId)),
        LocalScalarInput(LocalField.EXPECTED_REVISION, LocalScalarValue.Integer(request.expectedOrderRevision.value)),
        LocalScalarInput(LocalField.PREVIOUS_OWNER_ID, LocalScalarValue.Identifier(request.previousOwnerId)),
        LocalScalarInput(LocalField.RECIPIENT_ID, LocalScalarValue.Identifier(request.recipientId)),
        LocalScalarInput(LocalField.REASON, LocalScalarValue.Text(request.reasonCode)),
        LocalScalarInput(LocalField.EVIDENCE_ID, LocalScalarValue.Identifier(request.evidence.evidenceId)),
        LocalScalarInput(LocalField.OCCURRED_AT, LocalScalarValue.Text(request.evidence.occurredAt.canonicalString())),
    )

    fun candidate(): LocalProfiledOrderV2 {
        val shirt = Quantity(id("UNIT-EACH"), 2, 0)
        val fabric = Quantity(id("UNIT-METRE"), 15, 1)
        val selection = LocalOrderSelectionV1(LocalSalesActivity.CLOTHING, LocalSalesChannel.COUNTER,
            Currency.EGP, Revision(5), listOf(
                LocalOrderLineV1(id("LINE-SHIRT"), id("PORTION-SHIRT"), id("SKU-SHIRT-M-BLUE"), shirt,
                    variantId = id("VARIANT-SHIRT-M-BLUE")),
                LocalOrderLineV1(id("LINE-FABRIC"), id("PORTION-FABRIC"), id("SKU-FABRIC"), fabric)))
        fun component(portion: String, kind: String, amount: Long) = LocalOrderComponentV1(
            "$portion/$kind", id(portion), null, kind, Money(Currency.EGP, amount), "SYNTHETIC-ILLUSTRATIVE-14")
        return LocalProfiledOrderV2(LocalOrderViewV1(target, previousOwner, r17, Revision(5), selection,
            Money(Currency.EGP, 29640), Money(Currency.EGP, 0), Money(Currency.EGP, 29640), listOf(
                LocalOrderPriceLineV1(id("PORTION-SHIRT"), shirt, Money(Currency.EGP, 22800)),
                LocalOrderPriceLineV1(id("PORTION-FABRIC"), fabric, Money(Currency.EGP, 6840))), listOf(
                component("PORTION-SHIRT", "ITEM_NET", 20000), component("PORTION-SHIRT", "ITEM_TAX", 2800),
                component("PORTION-FABRIC", "ITEM_NET", 6000), component("PORTION-FABRIC", "ITEM_TAX", 840)),
            null, null, null, null), LocalOrderStockProfileV2.NORMALIZED)
    }

    fun query(search: String = "reassign:${target.value}") = LocalWorkflowQuery(LocalWorkflow.ORDERS, search)
    fun readBody(orders: List<LocalProfiledOrderV2> = listOf(candidate()), nextAfter: DomainId? = null): StoredBytes =
        body(LocalOrderReadResultV2(orders, nextAfter), LocalOrderReadResultV2.serializer())
    fun readReply(orders: List<LocalProfiledOrderV2> = listOf(candidate())): ReadReply.Data =
        ReadReply.Data(readBody(orders), Revision(1), attestedAt)
    fun <T> body(value: T, serializer: KSerializer<T>): StoredBytes =
        StoredBytes(wire.encodeToString(serializer, value).encodeToByteArray())
    fun objectBody(value: JsonObject): StoredBytes = body(value, JsonObject.serializer())
    fun objectOf(body: StoredBytes): JsonObject = wire.parseToJsonElement(body.copyBytes().decodeToString()).jsonObject

    fun hint(target: DomainId = this.target, action: SecurityAction = SecurityAction.ORDER_REASSIGN,
        effect: GrantEffect = GrantEffect.ALLOW) = PermissionGrant(Permission(action, PermissionVerb.PERFORM),
        scope, effect, namedSubjectId = manager, targetIds = setOf(target))

    fun sessionView(grants: List<PermissionGrant> = listOf(hint())) = LocalSessionView(manager, scope, device, session,
        SessionGeneration(1), LocalLoginIdentifier("Synthetic manager"),
        ActivatedLocalPolicy(scope, manager, Revision(1), grants, LocalSessionPolicy(), ThrottlePolicy()),
        MonotonicMoment(id("UNIT-CLOCK"), 1), MonotonicMoment(id("UNIT-CLOCK"), 2))

    /** A controlled reply DTO for rendering/recovery units, never an observed Master commit. */
    fun recordedReply(operationId: OperationId = operation(), replayed: Boolean = false): MutationReply.Recorded =
        MutationReply.Recorded(operationId, manager, true, "sales.applied",
            body(LocalSalesResultV1.Applied(LocalSalesAction.ORDER_REASSIGNED, target,
                listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, target, r18))), LocalSalesResultV1.serializer()),
            Revision(1), JournalSequence(1), committedAt, replayed)
}

internal fun JsonObject.changed(key: String, value: JsonElement): JsonObject =
    JsonObject(toMutableMap().also { it[key] = value })
internal fun JsonObject.without(key: String): JsonObject = JsonObject(toMutableMap().also { it.remove(key) })

/** Actual adapter with controlled credential, installation, session, journal and reply seams. */
internal class ReassignmentGatewayControl(
    commands: Set<CommandKey> = setOf(LocalCollectionStockCodecV2.COMMAND),
    reads: Set<ReadKey> = setOf(LocalManagerReassignmentCodec.QUERY, LocalManagerOrderReviewCodec.QUERY),
    grants: List<PermissionGrant> = listOf(FrozenManagerReassignmentV1.hint()),
) {
    val journal = ControlledReassignmentJournal(LocalClientIdentity(FrozenManagerReassignmentV1.scope,
        FrozenManagerReassignmentV1.manager, FrozenManagerReassignmentV1.device))
    val endpoint = ControlledReassignmentEndpoint(FrozenManagerReassignmentV1.sessionView(grants))
    var leaseCurrent = true
    val gateway = LocalEndpointWorkflowGateway(endpoint, journal, LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(LocalGatewayCredentials(FrozenManagerReassignmentV1.id("CONNECTION"),
            OpaqueCredential(ByteArray(16) { 7 }))) { leaseCurrent }
    }, LocalWorkflowInstallation(commands, reads))

    fun assertNoDispatch() = assertTrue(endpoint.executions.isEmpty(), "Controlled endpoint must receive no mutation")
    fun assertOnlyTargetReads(expected: Int) {
        assertEquals(List(expected) { LocalManagerReassignmentCodec.QUERY }, endpoint.reads.map { it.query })
        assertEquals(List(expected) { LocalManagerOrderReviewQueryV1(FrozenManagerReassignmentV1.target) }, endpoint.selectors)
        assertEquals(List(expected) { setOf("orderId") }, endpoint.selectorFields)
    }
}

/** In-memory journal contract seam. DURABLE is a scripted enum, not persistence evidence. */
internal class ControlledReassignmentJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    val entries = linkedMapOf<OperationId, StoredClientIntentV1>()
    val calls = mutableListOf<String>()
    var stageWrites = 0
    private var active = true
    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> {
        calls += "find"
        return if (active) JournalRead.Value(entries[operationId]) else JournalRead.Unavailable
    }
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> {
        calls += "pending"
        return if (active) JournalRead.Value(entries.values.filterNot { it.acknowledged }) else JournalRead.Unavailable
    }
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        calls += "stage"
        if (!active) return JournalWrite.UNAVAILABLE
        intent.validate(owner)
        val existing = entries[intent.operationId]
        if (existing != null && !existing.sameIntent(intent)) return JournalWrite.IDENTITY_CONFLICT
        if (existing == null) { entries[intent.operationId] = intent; stageWrites++ }
        return JournalWrite.DURABLE
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite {
        calls += "dispatch"
        val entry = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        if (!active) return JournalWrite.UNAVAILABLE
        entries[operationId] = entry.copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED.name)
        return JournalWrite.DURABLE
    }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        calls += "remember"
        val entry = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        if (!active) return JournalWrite.UNAVAILABLE
        if (entry.recorded != null && entry.recorded != result) return JournalWrite.IDENTITY_CONFLICT
        entries[operationId] = entry.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = result)
        return JournalWrite.DURABLE
    }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        calls += "acknowledge"
        val entry = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        if (!active || entry.recorded != result) return JournalWrite.UNAVAILABLE
        entries[operationId] = entry.copy(acknowledged = true)
        return JournalWrite.DURABLE
    }
    override suspend fun close() { active = false }
}

/** Every response is explicitly controlled; no health value or fake native certificate is used. */
internal class ControlledReassignmentEndpoint(view: LocalSessionView) : LocalMasterClient {
    var currentReply: LocalSessionViewResult = LocalSessionViewResult.Current(view)
    var readReply: suspend (ReadRequest) -> ReadReply = { FrozenManagerReassignmentV1.readReply() }
    var lookupReply: suspend (OperationIdentity) -> LookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
    var executeReply: suspend (MutationRequest) -> MutationReply = {
        MutationReply.Deferred(it.operationId, DeferredReason.STORAGE_UNCERTAIN)
    }
    val reads = mutableListOf<ReadRequest>()
    val selectors = mutableListOf<LocalManagerOrderReviewQueryV1>()
    val selectorFields = mutableListOf<Set<String>>()
    val lookups = mutableListOf<OperationIdentity>()
    val executions = mutableListOf<MutationRequest>()
    var currentCalls = 0
    private fun unexpected(name: String): Nothing = throw AssertionError("Unexpected controlled endpoint call: $name")
    override val health: StateFlow<LocalMasterEndpointHealth> get() = unexpected("health")
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        currentCalls++
        return currentReply
    }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        assertTrue(request.query in setOf(LocalManagerReassignmentCodec.QUERY, LocalManagerOrderReviewCodec.QUERY),
            "No owner/legacy-command fallback read")
        reads += request
        val text = request.payload.copyBytes().decodeToString()
        selectorFields += FrozenManagerReassignmentV1.wire.parseToJsonElement(text).jsonObject.keys
        selectors += FrozenManagerReassignmentV1.wire.decodeFromString(LocalManagerOrderReviewQueryV1.serializer(), text)
        return readReply(request)
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        return lookupReply(identity)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executions += request
        return executeReply(request)
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = unexpected("signIn")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = unexpected("refresh")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = unexpected("logout")
}
