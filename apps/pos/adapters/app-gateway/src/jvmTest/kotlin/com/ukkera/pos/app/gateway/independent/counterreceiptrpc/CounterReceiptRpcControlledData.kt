package com.ukkera.pos.app.gateway.independent.counterreceiptrpc

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import kotlin.time.Instant

/**
 * Frozen v1 semantic DATA and v1.1 envelope binding. Head/Event/Applied values
 * below are controlled port inputs, never genuine Finalize or durable receipts.
 */
internal object CounterReceiptRpcData {
    private const val DESIGN = "qa/pos/designs/counter-receipt-rpc/"
    private fun frozen(name: String): JsonObject {
        val file = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve(DESIGN + name) }.firstOrNull { Files.isRegularFile(it) }
            ?: error("Missing frozen counter receipt input: " + name)
        return Json.parseToJsonElement(Files.readString(file)).jsonObject
    }
    val fixtures by lazy { frozen("fixtures.json") }
    val resultBindings by lazy { frozen("result-bindings-v1.1.json") }
    val wire = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; coerceInputValues = false }
    private val ids get() = fixtures.obj("ids")
    val sale get() = DomainId(ids.text("sale"))
    val owner get() = DomainId(ids.text("OWNER"))
    val otherActor get() = DomainId(ids.text("OTHER"))
    val device get() = DomainId(ids.text("CLIENT_DEVICE"))
    val operation get() = OperationId(ids.text("E1"))
    val otherOperation get() = OperationId(ids.text("E2"))
    val scope get() = BranchScope(DomainId(ids.text("organization")), DomainId(ids.text("retailCompany")), DomainId(ids.text("branch")))
    val observation get() = fixtures.obj("observations").obj("E1")
    val observedAt get() = instant(observation.obj("payload").text("receivedAt"))
    val recordedAt get() = instant(observation.text("masterCommitAt"))
    val committedCode get() = resultBindings.obj("binding").obj("Applied").text("code")
    val refusedCode get() = resultBindings.obj("binding").obj("durableOrdinaryRefusal").text("code")
    fun id(name: String) = DomainId(ids.text(name))
    fun instant(text: String) = UtcInstant(Instant.parse(text))
    // Independent scalar bindings for logical base units, not original catalog evidence.
    fun unit(name: String): DomainId = when (name) {
        "ITEM" -> DomainId("62000000-0000-4000-8000-000000000001")
        "KG" -> DomainId("62000000-0000-4000-8000-000000000002")
        else -> error("Unbound logical base unit")
    }
    fun quantity(name: String, scale: Int, value: String) = Quantity(unit(name),
        BigDecimal(value).setScale(scale, RoundingMode.UNNECESSARY).unscaledValue().longValueExact(), scale)

    fun original() = fixtures.obj("baseSale").let { original ->
        LocalRetailCustomerReceiptOriginalV1(sale, owner, instant(original.text("paidFinalizeAt")),
            original.array("originalPortions").map { it.jsonObject.let { row ->
                LocalRetailCustomerReceiptPortionV1(DomainId(row.text("portionId")),
                    quantity(row.text("unit"), row.text("quantityScale").toInt(), row.text("soldQuantity")))
            } })
    }
    fun request(): LocalRetailCustomerReceiptRequestV1 = observation.obj("payload").let { payload ->
        LocalRetailCustomerReceiptRequestV1(sale, Revision(payload.text("expectedHeadRevision").toLong()), observedAt,
            payload.text("receiptTimeZoneId"), LocalRetailCustomerReceiptObservationV1.valueOf(payload.text("observation")),
            payload.array("portions").map { it.jsonObject.let { row ->
                val descriptor = row.obj("quantity")
                LocalRetailCustomerReceiptPortionV1(DomainId(row.text("portionId")),
                    quantity(descriptor.text("unit"), descriptor.text("scale").toInt(), descriptor.text("value")))
            } })
    }
    fun headReference() = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT, sale)
    fun eventReference() = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_EVENT, DomainId(operation.value), parentId = sale)
    fun batchReference() = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_BATCH,
        original().portions.first().portionId, Revision(0), sale)
    fun query(workflow: LocalWorkflow = LocalWorkflow.ORDERS, search: String = "customer-receipt:${sale.value}",
        reference: LocalRecordReference? = null) = LocalWorkflowQuery(workflow, search, reference,
        returns = if (workflow == LocalWorkflow.RETURNS) LocalReturnUiQuery(LocalReturnSection.RECEIPTS) else null)

    fun values(): List<LocalScalarInput> = fixtures.obj("batchInput").let { input -> listOf(
        LocalScalarInput(LocalField.SALE_ID, LocalScalarValue.Identifier(DomainId(input.text("SALE_ID")))),
        LocalScalarInput(LocalField.EXPECTED_CUSTOMER_RECEIPT_REVISION, LocalScalarValue.Integer(input.text("EXPECTED_CUSTOMER_RECEIPT_REVISION").toLong())),
        LocalScalarInput(LocalField.CUSTOMER_RECEIVED_AT, LocalScalarValue.Text(input.text("CUSTOMER_RECEIVED_AT"))),
        LocalScalarInput(LocalField.CUSTOMER_RECEIPT_TIME_ZONE, LocalScalarValue.Choice(input.text("CUSTOMER_RECEIPT_TIME_ZONE"))),
    ) }
    fun rows(): List<LocalInputLine> = fixtures.obj("batchInput").array("LINES").map { it.jsonObject.let { row ->
        val portion = DomainId(row.text("PORTION_ID"))
        LocalInputLine(portion, listOf(LocalScalarInput(LocalField.PORTION_ID, LocalScalarValue.Identifier(portion)),
            LocalScalarInput(LocalField.UNIT, LocalScalarValue.Identifier(unit(row.text("UNIT")))),
            LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(row.text("QUANTITY_SCALE").toLong())),
            LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal(row.text("QUANTITY")))))
    } }
    fun intent(values: List<LocalScalarInput> = this.values(), rows: List<LocalInputLine> = this.rows(),
        reference: LocalRecordReference = batchReference()) = LocalCommandIntent(operation,
        LocalAction.RETAIL_CUSTOMER_RECEIPT, fixtures.obj("batchInput").text("formVersion").toInt(), reference,
        values, LocalField.LINES, rows)

    fun applied() = LocalRetailCustomerReceiptResultV1.Applied(sale, operation,
        Revision(observation.text("expectedNewHeadRevision").toLong()), observedAt, request().receiptTimeZoneId, recordedAt)
    fun resultBytes(result: LocalRetailCustomerReceiptResultV1) = StoredBytes(
        wire.encodeToString(LocalRetailCustomerReceiptResultV1.serializer(), result).encodeToByteArray())
    fun readBytes(result: LocalRetailCustomerReceiptReadResultV1) = StoredBytes(
        wire.encodeToString(LocalRetailCustomerReceiptReadResultV1.serializer(), result).encodeToByteArray())
    fun reply(payload: StoredBytes = resultBytes(applied()), committed: Boolean = true, code: String = committedCode,
        operation: OperationId = this.operation, actor: DomainId = owner, recordedAt: UtcInstant = this.recordedAt,
        replayed: Boolean = false) = MutationReply.Recorded(operation, actor, committed, code, payload,
        Revision(1), JournalSequence(1), recordedAt, replayed)
    fun event() = LocalRetailCustomerReceiptEventViewV1(sale, operation, Revision(1), request().observation,
        request().portions, observedAt, request().receiptTimeZoneId, recordedAt, owner, original().paidFinalizeAt,
        owner, OperationId(id("FINALIZE_OPERATION").value), owner,
        sha256("controlled original source DATA".encodeToByteArray()), sha256("controlled event DATA".encodeToByteArray()))
    fun laterHead() = LocalRetailCustomerReceiptReadResultV1.Head(LocalRetailCustomerReceiptHeadViewV1(original(), Revision(2), 2,
        listOf(LocalRetailCustomerReceiptPortionV1(id("ITEM"), quantity("ITEM", 0, "3")),
            LocalRetailCustomerReceiptPortionV1(id("KG"), quantity("KG", 3, "0.750"))), otherOperation))
}

internal fun JsonObject.obj(key: String) = getValue(key).jsonObject
internal fun JsonObject.array(key: String) = getValue(key).jsonArray
internal fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

/** Real shared gateway with a reused controlled model journal API, not native durability. */
internal class CounterReceiptRpcControlledGateway(record: Boolean = true, lookup: Boolean = true) {
    private val f = CounterReceiptRpcData
    val journal = ControlledReceiptJournal(LocalClientIdentity(f.scope, f.owner, f.device))
    val endpoint = CounterReceiptRpcControlledEndpoint(record, lookup)
    var lifetimeCurrent = true
    private val credentials = LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(LocalGatewayCredentials(f.device, OpaqueCredential(ByteArray(52)))) { lifetimeCurrent }
    }
    val installed = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS)
    val gateway = LocalEndpointWorkflowGateway(endpoint, journal, credentials, installed)
    fun reopenedGateway() = LocalEndpointWorkflowGateway(endpoint, journal, credentials, installed)
}

/** Current-session projections and replies are controlled DATA, never employee authority. */
internal class CounterReceiptRpcControlledEndpoint(record: Boolean, lookup: Boolean) : LocalMasterClient {
    private val f = CounterReceiptRpcData
    override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    var currentAvailable = true
    val grants = buildList {
        if (record) add(PermissionGrant(Permission(SecurityAction.CUSTOMER_RECEIPT_RECORD, PermissionVerb.PERFORM),
            f.scope, GrantEffect.ALLOW, ownerAccess = OwnerAccess.OWN_ONLY))
        if (lookup) add(PermissionGrant(Permission(SecurityAction.RETURN_RECEIPT_LOOKUP, PermissionVerb.PERFORM), f.scope, GrantEffect.ALLOW))
    }
    var head: LocalRetailCustomerReceiptReadResultV1 = LocalRetailCustomerReceiptReadResultV1.NotRecorded(f.original())
    var executionReply: MutationReply = f.reply()
    var found: MutationReply.Recorded? = null
    val queries = mutableListOf<LocalRetailCustomerReceiptQueryV1>()
    val executions = mutableListOf<MutationRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        if (!currentAvailable) return LocalSessionViewResult.Denied
        val clock = DomainId("82000000-0000-4000-8000-000000000001")
        return LocalSessionViewResult.Current(LocalSessionView(f.owner, f.scope, f.device, f.id("OWNER_SESSION"),
            SessionGeneration(1), LocalLoginIdentifier("synthetic-counter-observer"),
            ActivatedLocalPolicy(f.scope, f.owner, Revision(1), grants, LocalSessionPolicy(), ThrottlePolicy()),
            MonotonicMoment(clock, 10_000), MonotonicMoment(clock, 20_000)))
    }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        if (request.query != LocalRetailCustomerReceiptReadCodec.QUERY) return ReadReply.Unavailable(ReadFailure.UNKNOWN_QUERY)
        val query = f.wire.decodeFromString(LocalRetailCustomerReceiptQueryV1.serializer(), request.payload.copyBytes().decodeToString())
        queries += query
        val result = when (query) {
            is LocalRetailCustomerReceiptQueryV1.Head -> if (query.saleId == f.sale) head else LocalRetailCustomerReceiptReadResultV1.NotAvailable
            is LocalRetailCustomerReceiptQueryV1.Event -> if (query.saleId != f.sale) LocalRetailCustomerReceiptReadResultV1.NotAvailable
                else if (query.operationId == f.operation) LocalRetailCustomerReceiptReadResultV1.Event(f.event())
                else LocalRetailCustomerReceiptReadResultV1.EventNotRecorded(query.saleId, query.operationId)
        }
        return ReadReply.Data(f.readBytes(result), Revision(1), f.recordedAt)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executions += request
        return executionReply
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        return found?.let { LookupReply.Found(it) } ?: LookupReply.AbsentAtCurrentWriter(identity.operationId)
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = LocalSignOutResult.Unavailable
}
