@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.app.gateway.independent.returnstockdisposition

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** CORE/MEASURE quantities from return-stock-disposition/fixtures.json v1.
 * IDs, revisions and times are public carrier coordinates, not original-history proof.
 * No inventory value, cash, entitlement, inspection policy or native source is bound. */
internal object ReturnStockFrozenInputs {
    fun id(suffix: Int) = DomainId("00000000-0000-4000-8000-${suffix.toString().padStart(12, '0')}")
    val scope = BranchScope(id(8101), id(8102), id(8103))
    val owner = id(8104)
    val device = id(8105)
    val session = id(8106)
    val sale = id(8110)
    val caseId = id(8111)
    val returnId = id(8112)
    val portion = id(8113)
    val issue = id(8114)
    val allocation = id(8115)
    val sku = id(8116)
    val warehouse = id(8117)
    val restoration = id(8118)
    val evidence = id(8119)
    val each = id(8120)
    val kilogram = id(8121)
    val operation = OperationId(id(8122).value)
    val otherOperation = OperationId(id(8123).value)
    val occurredAt = UtcInstant(Instant.parse("2026-11-03T14:00:00.000Z"))
    val observedAt = UtcInstant(Instant.parse("2026-11-03T14:01:00.000Z"))
    val stockReference get() = LocalRecordReference(LocalRecordKind.RETURN_STOCK, returnId, parentId = caseId)
    val postingPermission = Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM)
    fun core(quantity: Long) = Quantity(each, quantity, 0)
    fun measure(scaled: Long) = Quantity(kilogram, scaled, 2)

    fun coreOriginal() = LocalReturnStockReadResultV1.Original(
        sale, caseId, returnId, Revision(3), portion, core(2), LocalReturnDispositionV1.INSPECTED_SELLABLE,
        Revision(0), core(0), core(2), LocalReturnStockStatusV1.PENDING, issue, Revision(2),
        listOf(LocalReturnStockAllocationViewV1(allocation, sku, warehouse, null,
            core(4), core(1), core(3), "CLEAR", null)), null, null)

    /** The first explicit CORE post consumes one current accepted unit, not its other
     * unaccepted original unit. This is controlled read DATA, not an executed posting. */
    fun partialOriginal(): LocalReturnStockReadResultV1.Original = coreOriginal().let { original ->
        original.copy(dispositionRevision = Revision(1), postedQuantity = core(1), remainingQuantity = core(1),
            status = LocalReturnStockStatusV1.PARTIALLY_POSTED,
            allocations = original.allocations.map { it.copy(restoredQuantity = core(2), remainingQuantity = core(2)) },
            lastPostingOperationId = otherOperation)
    }

    fun measureOriginal(): LocalReturnStockReadResultV1.Original = coreOriginal().copy(
        acceptedQuantity = measure(25), postedQuantity = measure(0), remainingQuantity = measure(25),
        allocations = listOf(LocalReturnStockAllocationViewV1(allocation, id(8124), warehouse, null,
            measure(75), measure(0), measure(75), "CLEAR", null)))

    fun sessionView(): LocalSessionView {
        val boot = id(8125)
        return LocalSessionView(owner, scope, device, session, SessionGeneration(1), LocalLoginIdentifier("stock-operator"),
            ActivatedLocalPolicy(scope, owner, Revision(1),
                listOf(PermissionGrant(postingPermission, scope, GrantEffect.ALLOW)),
                LocalSessionPolicy(), ThrottlePolicy()), MonotonicMoment(boot, 1_000), MonotonicMoment(boot, 2_000))
    }

    @OptIn(ExperimentalSerializationApi::class)
    val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }

    fun resultBody(value: LocalReturnStockResultV1) = StoredBytes(json.encodeToString(
        LocalReturnStockResultV1.serializer(), value).encodeToByteArray())
}

/** Actual common gateway with controlled public reads, replies and an in-memory journal.
 * DURABLE is a seam response here, never evidence of native durability or Master admission. */
internal class ReturnStockGatewayFixture {
    private val input = ReturnStockFrozenInputs
    val endpoint = ReturnStockControlledEndpoint()
    val journal = ReturnStockControlledJournal(LocalClientIdentity(input.scope, input.owner, input.device))
    var leaseCurrent = true
    fun gateway() = LocalEndpointWorkflowGateway(endpoint, journal, LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(LocalGatewayCredentials(input.device, OpaqueCredential(ByteArray(16)))) { leaseCurrent }
    }, LocalWorkflowInstallation(setOf(LocalReturnStockProfileV1.COMMAND), setOf(LocalReturnStockProfileV1.QUERY)))
    val gateway = gateway()

    fun query() = LocalWorkflowQuery(LocalWorkflow.RETURNS, reference = input.stockReference)

    fun intent(form: LocalActionForm, quantity: String = "1", custody: LocalReturnStockCustodyV1? = LocalReturnStockCustodyV1.RELEASED,
        operation: OperationId = input.operation, values: Map<LocalField, LocalScalarValue> = emptyMap(),
        lines: List<LocalInputLine>? = null): LocalCommandIntent {
        val explicit = mapOf(
            LocalField.RESTORATION_ID to LocalScalarValue.Identifier(input.restoration),
            LocalField.EVIDENCE_ID to LocalScalarValue.Identifier(input.evidence),
            LocalField.OCCURRED_AT to LocalScalarValue.Text(input.occurredAt.canonicalString())) + values
        val group = form.fields.filterIsInstance<LocalFormField.Lines>().single()
        return LocalCommandIntent(operation, form.action, form.version, input.stockReference,
            form.fields.filterIsInstance<LocalFormField.Scalar>().mapNotNull { field ->
                (explicit[field.field] ?: field.initialValue)?.let { LocalScalarInput(field.field, it) }
            }, group.field, lines ?: group.initialLines.mapIndexed { index, row ->
                if (index == 0) line(row.lineId, quantity, custody) else row
            })
    }

    fun line(allocation: DomainId = input.allocation, quantity: String = "1",
        custody: LocalReturnStockCustodyV1? = LocalReturnStockCustodyV1.RELEASED,
        lineId: DomainId = allocation) = LocalInputLine(lineId, listOfNotNull(
        LocalScalarInput(LocalField.ALLOCATION_ID, LocalScalarValue.Identifier(allocation)),
        LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal(quantity)),
        custody?.let { LocalScalarInput(LocalField.CUSTODY, LocalScalarValue.Choice(it.name)) }))
}

internal fun LocalCommandIntent.withValues(values: List<LocalScalarInput>) = LocalCommandIntent(
    operationId, action, formVersion, reference, values, lineField, lines)

/** No private CASE or financial read is installed; an unexpected query fails the harness. */
internal class ReturnStockControlledEndpoint : LocalMasterClient {
    private val input = ReturnStockFrozenInputs
    var source: LocalReturnStockReadResultV1 = input.coreOriginal()
    val reads = mutableListOf<ReadRequest>()
    val selectors = mutableListOf<LocalReturnStockQueryV1>()
    val executed = mutableListOf<MutationRequest>()
    val lookedUp = mutableListOf<OperationIdentity>()
    var onExecute: suspend (MutationRequest) -> MutationReply = {
        MutationReply.Deferred(it.operationId, DeferredReason.UNKNOWN_COMMAND)
    }
    var onLookup: suspend (OperationIdentity) -> LookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("No native endpoint health bound")
    override suspend fun current(credentials: LocalGatewayCredentials) = LocalSessionViewResult.Current(input.sessionView())
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        check(request.query == LocalReturnStockProfileV1.QUERY)
        selectors += input.json.decodeFromString(LocalReturnStockQueryV1.serializer(), request.payload.copyBytes().decodeToString())
        return ReadReply.Data(StoredBytes(input.json.encodeToString(LocalReturnStockReadResultV1.serializer(), source)
            .encodeToByteArray()), Revision(4), input.observedAt)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executed += request
        return onExecute(request)
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookedUp += identity
        return onLookup(identity)
    }
    fun recorded(value: LocalReturnStockResultV1, operation: OperationId = input.operation,
        actor: DomainId = input.owner, committed: Boolean = value is LocalReturnStockResultV1.Applied,
        code: String = if (committed) "committed" else "sales.return.stock.rejected") = MutationReply.Recorded(
        operation, actor, committed, code, input.resultBody(value), Revision(5), JournalSequence(1), input.observedAt, replayed = false)
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = error("No native authentication bound")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("No native refresh bound")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("No native logout bound")
}

/** Explicit counters support only preparation/retention/ACK ordering at the client seam. */
internal class ReturnStockControlledJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    var entry: StoredClientIntentV1? = null
    var stageCalls = 0
    var rememberCalls = 0
    var acknowledgeCalls = 0
    override suspend fun find(operationId: OperationId) = JournalRead.Value(entry?.takeIf { it.operationId == operationId })
    override suspend fun pending() = JournalRead.Value(listOfNotNull(entry?.takeUnless { it.acknowledged }))
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        stageCalls++
        check(entry == null)
        entry = intent
        return JournalWrite.DURABLE
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite {
        check(entry?.operationId == operationId)
        entry = checkNotNull(entry).copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED.name)
        return JournalWrite.DURABLE
    }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        rememberCalls++
        check(entry?.operationId == operationId)
        entry = checkNotNull(entry).copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = result)
        return JournalWrite.DURABLE
    }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        acknowledgeCalls++
        check(entry?.operationId == operationId && entry?.recorded == result)
        entry = checkNotNull(entry).copy(acknowledged = true)
        return JournalWrite.DURABLE
    }
    override suspend fun close() = Unit
}
