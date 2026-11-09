package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** Frozen values with explicit synthetic scalar bindings; no genuine catalog or native provenance. */
internal object RetailCustomerReceiptFrozenV1GatewayFixture {
    val scope = BranchScope(id("01000000", 1), id("02000000", 1), id("03000000", 1))
    val collector = id("04000000", 1)
    val recorderB = id("04000000", 2)
    val lookupC = id("04000000", 3)
    val device = id("05000000", 2)
    val sale = id("06000000", 1)
    val unknownSale = id("06000000", 99)
    val finalize = operation("08000000", 1)
    val eachPortion = id("09000000", 1)
    val kgPortion = id("09000000", 2)
    val e1 = operation("10000000", 1)
    val e2 = operation("10000000", 2)
    val e3 = operation("10000000", 3)
    val unknownEvent = operation("10000000", 99)
    val uiOperation = operation("18000000", 1)
    val eachUnit = id("0b000000", 1)
    val kgUnit = id("0b000000", 2)
    val gramUnit = id("0b000000", 3)
    val paidAt = instant(1_767_261_600_000)
    val e1ReceivedAt = instant(1_767_306_600_000)
    val e1RecordedAt = instant(1_767_355_200_000)
    val uiReceivedAt = instant(1_767_694_500_000)
    val uiRecordedAt = instant(1_767_694_800_000)
    val e3ReceivedAt = instant(1_767_862_800_000)
    val e3RecordedAt = instant(1_767_863_100_000)
    const val zone = "Africa/Cairo"
    // Literal binding of the public canonical committed envelope, not a new refusal oracle.
    const val committedCode = "committed"
    const val refusedCode = "sales.customer_receipt.refused"

    val wire = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; coerceInputValues = false }
    val original = LocalRetailCustomerReceiptOriginalV1(sale, collector, paidAt,
        listOf(each(3), kg(2_750_000)))
    val headReference = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT, sale)
    val kgReference = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_PORTION, kgPortion, Revision(1), sale)
    val uiEventReference = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_EVENT, DomainId(uiOperation.value), parentId = sale)
    val headSearch = "customer-receipt:${sale.value}"
    fun eventSearch(operation: OperationId) = "customer-receipt-event:${sale.value}:${operation.value}"

    fun head(state: Int): LocalRetailCustomerReceiptReadResultV1 = when (state) {
        0 -> LocalRetailCustomerReceiptReadResultV1.NotRecorded(original)
        1 -> LocalRetailCustomerReceiptReadResultV1.Head(LocalRetailCustomerReceiptHeadViewV1(original, Revision(1), 1,
            listOf(each(1), kg(1_125_000)), e1))
        2 -> LocalRetailCustomerReceiptReadResultV1.Head(LocalRetailCustomerReceiptHeadViewV1(original, Revision(2), 2,
            listOf(each(3), kg(1_750_000)), e2))
        3 -> LocalRetailCustomerReceiptReadResultV1.Head(LocalRetailCustomerReceiptHeadViewV1(original, Revision(3), 3,
            listOf(each(3), kg(2_750_000)), e3))
        else -> error("Only frozen S0/S1/S2/S3 are constructed")
    }

    fun uiRequest() = LocalRetailCustomerReceiptRequestV1(sale, Revision(1), uiReceivedAt, zone,
        LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER, listOf(kg(625_000)))

    fun e1Request() = LocalRetailCustomerReceiptRequestV1(sale, Revision(0), e1ReceivedAt, zone,
        LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER, listOf(each(1), kg(1_125_000)))

    fun intent(values: List<LocalScalarInput> = uiValues()) = LocalCommandIntent(uiOperation,
        LocalAction.RETAIL_CUSTOMER_RECEIPT, 1, kgReference, values)

    fun uiValues() = listOf(
        LocalScalarInput(LocalField.SALE_ID, LocalScalarValue.Identifier(sale)),
        LocalScalarInput(LocalField.PORTION_ID, LocalScalarValue.Identifier(kgPortion)),
        LocalScalarInput(LocalField.EXPECTED_CUSTOMER_RECEIPT_REVISION, LocalScalarValue.Integer(1)),
        LocalScalarInput(LocalField.UNIT, LocalScalarValue.Identifier(kgUnit)),
        LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(6)),
        LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal("0.625000")),
        LocalScalarInput(LocalField.CUSTOMER_RECEIVED_AT, LocalScalarValue.Text(uiReceivedAt.canonicalString())),
        LocalScalarInput(LocalField.CUSTOMER_RECEIPT_TIME_ZONE, LocalScalarValue.Choice(zone)),
    )

    fun event(operation: OperationId): LocalRetailCustomerReceiptEventViewV1 {
        val (revision, received, recorded, portions) = when (operation) {
            e1 -> EventNumbers(1, e1ReceivedAt, e1RecordedAt, listOf(each(1), kg(1_125_000)))
            e2 -> EventNumbers(2, uiReceivedAt, uiRecordedAt, listOf(each(2), kg(625_000)))
            e3 -> EventNumbers(3, e3ReceivedAt, e3RecordedAt, listOf(kg(1_000_000)))
            uiOperation -> EventNumbers(2, uiReceivedAt, uiRecordedAt, listOf(kg(625_000)))
            else -> error("No latest-event substitution for an unknown operation")
        }
        return LocalRetailCustomerReceiptEventViewV1(sale, operation, Revision(revision),
            LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER, portions, received, zone, recorded,
            collector, paidAt, collector, finalize, collector,
            sha256("synthetic original Finalize representation".encodeToByteArray()),
            sha256("synthetic event representation ${operation.value}".encodeToByteArray()))
    }

    fun resultBytes(value: LocalRetailCustomerReceiptResultV1): StoredBytes =
        StoredBytes(wire.encodeToString(LocalRetailCustomerReceiptResultV1.serializer(), value).encodeToByteArray())
    fun readBytes(value: LocalRetailCustomerReceiptReadResultV1): StoredBytes =
        StoredBytes(wire.encodeToString(LocalRetailCustomerReceiptReadResultV1.serializer(), value).encodeToByteArray())
    fun uiResult() = LocalRetailCustomerReceiptResultV1.Applied(sale, uiOperation, Revision(2), uiReceivedAt, zone, uiRecordedAt)
    fun reply(payload: StoredBytes = resultBytes(uiResult()), committed: Boolean = true, code: String = committedCode,
        operation: OperationId = uiOperation, actor: DomainId = collector, recordedAt: UtcInstant = uiRecordedAt,
        replayed: Boolean = false) = MutationReply.Recorded(operation, actor, committed, code, payload,
        Revision(1), JournalSequence(1), recordedAt, replayed)

    fun each(scaled: Long) = LocalRetailCustomerReceiptPortionV1(eachPortion, Quantity(eachUnit, scaled, 0))
    fun kg(scaled: Long, unit: DomainId = kgUnit) = LocalRetailCustomerReceiptPortionV1(kgPortion, Quantity(unit, scaled, 6))
    fun instant(epochMillis: Long) = UtcInstant(Instant.fromEpochMilliseconds(epochMillis))
    fun id(prefix: String, suffix: Int) = DomainId("$prefix-0000-4000-8000-${suffix.toString().padStart(12, '0')}")
    fun operation(prefix: String, suffix: Int) = OperationId(id(prefix, suffix).value)
    private data class EventNumbers(val revision: Long, val received: UtcInstant, val recorded: UtcInstant,
        val portions: List<LocalRetailCustomerReceiptPortionV1>)
}

/** Actual shared gateway, controlled trusted ports. Nothing here qualifies a real Master or SQLite journal. */
internal class ControlledRetailReceiptGateway(
    actor: DomainId = RetailCustomerReceiptFrozenV1GatewayFixture.collector,
    record: Boolean = true,
    lookup: Boolean = true,
    commandInstalled: Boolean = true,
    ownerAccess: OwnerAccess = OwnerAccess.OWN_ONLY,
) {
    private val f = RetailCustomerReceiptFrozenV1GatewayFixture
    val journal = ControlledReceiptJournal(LocalClientIdentity(f.scope, actor, f.device))
    val endpoint = ControlledReceiptEndpoint(actor, record, lookup, ownerAccess)
    var lifetimeCurrent = true
    val credentials = LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(LocalGatewayCredentials(f.id("13000000", 1), OpaqueCredential(ByteArray(16) { 7 }))) {
            lifetimeCurrent
        }
    }
    val installed = LocalWorkflowInstallation(if (commandInstalled) setOf(LocalRetailCustomerReceiptCodec.COMMAND) else emptySet(),
        setOf(LocalRetailCustomerReceiptReadCodec.QUERY))
    val gateway = LocalEndpointWorkflowGateway(endpoint, journal, credentials, installed)
    fun reopenedGateway() = LocalEndpointWorkflowGateway(endpoint, journal, credentials, installed)
}

internal class ControlledReceiptJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    val entries = linkedMapOf<OperationId, StoredClientIntentV1>()
    val acknowledgements = mutableListOf<OperationId>()
    var acknowledgeAvailable = true
    var rememberAvailable = true
    var stagedCalls = 0
    override suspend fun find(operationId: OperationId) = JournalRead.Value(entries[operationId])
    override suspend fun pending() = JournalRead.Value(entries.values.filter { !it.acknowledged })
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        stagedCalls++
        intent.validate(owner)
        val previous = entries[intent.operationId]
        if (previous != null && !previous.sameIntent(intent)) return JournalWrite.IDENTITY_CONFLICT
        entries[intent.operationId] = intent
        return JournalWrite.DURABLE
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite {
        val entry = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        entries[operationId] = entry.copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED.name)
        return JournalWrite.DURABLE
    }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        if (!rememberAvailable) return JournalWrite.UNAVAILABLE
        val entry = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        if (entry.recorded != null && entry.recorded != result) return JournalWrite.IDENTITY_CONFLICT
        entries[operationId] = entry.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = result)
        return JournalWrite.DURABLE
    }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        acknowledgements += operationId
        if (!acknowledgeAvailable) return JournalWrite.UNAVAILABLE
        val entry = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        if (entry.recorded != result) return JournalWrite.IDENTITY_CONFLICT
        entries[operationId] = entry.copy(acknowledged = true)
        return JournalWrite.DURABLE
    }
    override suspend fun close() = Unit
}

internal class ControlledReceiptEndpoint(private val actor: DomainId, record: Boolean, lookup: Boolean,
    ownerAccess: OwnerAccess) : LocalMasterClient {
    private val f = RetailCustomerReceiptFrozenV1GatewayFixture
    override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    var grants = buildList {
        if (record) add(PermissionGrant(Permission(SecurityAction.CUSTOMER_RECEIPT_RECORD, PermissionVerb.PERFORM),
            f.scope, GrantEffect.ALLOW, ownerAccess = ownerAccess))
        if (lookup) add(PermissionGrant(Permission(SecurityAction.RETURN_RECEIPT_LOOKUP, PermissionVerb.PERFORM),
            f.scope, GrantEffect.ALLOW))
    }
    var head: LocalRetailCustomerReceiptReadResultV1 = f.head(1)
    var readFailure: ReadFailure? = null
    var readPayload: StoredBytes? = null
    var onRead: (() -> Unit)? = null
    var onLookup: (() -> Unit)? = null
    var currentAvailable = true
    var executionReply: MutationReply = f.reply()
    var found: MutationReply.Recorded? = null
    val queries = mutableListOf<LocalRetailCustomerReceiptQueryV1>()
    val executions = mutableListOf<MutationRequest>()
    val lookups = mutableListOf<OperationIdentity>()

    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        if (!currentAvailable) return LocalSessionViewResult.Denied
        return LocalSessionViewResult.Current(LocalSessionView(actor, f.scope, f.device, f.id("11000000", 1),
            SessionGeneration(1), LocalLoginIdentifier("synthetic-receipt-observer"),
            ActivatedLocalPolicy(f.scope, actor, Revision(7), grants, LocalSessionPolicy(), ThrottlePolicy()),
            MonotonicMoment(f.id("12000000", 1), 10_000), MonotonicMoment(f.id("12000000", 1), 20_000)))
    }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        if (request.query != LocalRetailCustomerReceiptReadCodec.QUERY) return ReadReply.Unavailable(ReadFailure.UNKNOWN_QUERY)
        val query = f.wire.decodeFromString(LocalRetailCustomerReceiptQueryV1.serializer(), request.payload.copyBytes().decodeToString())
        queries += query
        val value = when (query) {
            is LocalRetailCustomerReceiptQueryV1.Head -> if (query.saleId == f.sale) head else LocalRetailCustomerReceiptReadResultV1.NotAvailable
            is LocalRetailCustomerReceiptQueryV1.Event -> if (query.saleId != f.sale) LocalRetailCustomerReceiptReadResultV1.NotAvailable
                else if (query.operationId in setOf(f.e1, f.e2, f.e3, f.uiOperation))
                    LocalRetailCustomerReceiptReadResultV1.Event(f.event(query.operationId))
                else LocalRetailCustomerReceiptReadResultV1.EventNotRecorded(query.saleId, query.operationId)
        }
        onRead?.invoke()
        return readFailure?.let { ReadReply.Unavailable(it) }
            ?: ReadReply.Data(readPayload ?: f.readBytes(value), Revision(1), f.e3RecordedAt)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executions += request
        return executionReply
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        onLookup?.invoke()
        return found?.let { LookupReply.Found(it) } ?: LookupReply.AbsentAtCurrentWriter(identity.operationId)
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = LocalSignOutResult.Unavailable
}
