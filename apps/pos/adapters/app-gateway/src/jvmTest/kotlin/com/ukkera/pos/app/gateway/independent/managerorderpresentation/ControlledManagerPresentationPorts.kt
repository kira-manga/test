package com.ukkera.pos.app.gateway.independent.managerorderpresentation

import com.ukkera.pos.app.LocalField
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalRecord
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowPage
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.gateway.JournalRead
import com.ukkera.pos.app.gateway.JournalWrite
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.LocalClientIntentJournal
import com.ukkera.pos.app.gateway.LocalEndpointWorkflowGateway
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialLease
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialSource
import com.ukkera.pos.app.gateway.LocalWorkflowInstallation
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.app.gateway.StoredKernelResultV1
import com.ukkera.pos.app.gateway.fieldLabel
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.LocalManagerOrderReviewQueryV1
import com.ukkera.pos.sales.store.LocalOrderLineV1
import com.ukkera.pos.sales.store.LocalOrderPriceLineV1
import com.ukkera.pos.sales.store.LocalOrderReadResultV2
import com.ukkera.pos.sales.store.LocalOrderSelectionV1
import com.ukkera.pos.sales.store.LocalOrderStockProfileV2
import com.ukkera.pos.sales.store.LocalOrderViewV1
import com.ukkera.pos.sales.store.LocalProfiledOrderV2
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.math.BigDecimal
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Semantic values from qa/pos/freezes/manager-order-presentation/fixtures-v2.json; no native schema oracle. */
internal object ManagerPresentationInputs {
    fun id(suffix: Int): DomainId = DomainId("00000000-0000-4000-8000-${suffix.toString().padStart(12, '0')}")
    val scope = BranchScope(id(1), id(11), id(21))
    val manager = id(101)
    val owner = id(102)
    val earlierCollector = id(103)
    val cashier = id(104)
    val client = id(202)
    val managerLease = id(301)
    val cashierLease = id(302)
    val legacy = id(401)
    val normalized = id(402)
    val absent = id(403)
    val conflicting = id(404)
    val foodOnly = id(405)
    val manyPortions = id(406)
    val unknownOriginal = OperationId(id(1201).value)
    val committedOriginal = OperationId(id(1301).value)
    const val cursor = "cursor.v1.synthetic.01"
    val managerKey = ReadKey("sales.order.manager-review", 1)

    // Missing boot/connection, line/unit and v1 portion coordinates are constructor-only.
    // They are deliberately outside the frozen namespace and are never asserted as oracles.
    fun constructorCoordinate(ordinal: Int): DomainId =
        DomainId("7f250000-0000-4000-8000-${ordinal.toString().padStart(12, '0')}")

    fun hint(action: SecurityAction = SecurityAction.ORDER_REASSIGN,
        effect: GrantEffect = GrantEffect.ALLOW, target: DomainId = legacy): PermissionGrant =
        PermissionGrant(Permission(action, PermissionVerb.PERFORM), scope, effect,
            namedSubjectId = manager, targetIds = setOf(target))

    fun view(grants: List<PermissionGrant>, revision: Revision = Revision(43)): LocalSessionView =
        LocalSessionView(manager, scope, client, managerLease, SessionGeneration(1),
            LocalLoginIdentifier("Mira Manager"),
            ActivatedLocalPolicy(scope, manager, revision, grants, LocalSessionPolicy(), ThrottlePolicy()),
            MonotonicMoment(constructorCoordinate(1), 1), MonotonicMoment(constructorCoordinate(1), 2))

    fun query(target: DomainId = legacy, reference: LocalRecordReference? = null): LocalWorkflowQuery =
        LocalWorkflowQuery(LocalWorkflow.ORDERS, "manager:${target.value}", reference)

    val legacyExpected = BoundManagerOrder(legacy, 17, "LEGACY_BOUNDED", 34200, 1500, id(601), null, id(801),
        listOf(BoundManagerPortion(null, id(501), "2", 25650), BoundManagerPortion(null, id(502), "1", 8550)),
        beforeDiscountPayable = 35910, advertisedPayableDiscount = 1710)
    val normalizedExpected = BoundManagerOrder(normalized, 23, "NORMALIZED", 21660, 0, id(602), id(701), null,
        listOf(BoundManagerPortion(null, id(503), "1.250", 11400), BoundManagerPortion(null, id(504), "2", 10260)),
        beforeDiscountPayable = 21660, advertisedPayableDiscount = 0)
    val manyExpected = BoundManagerOrder(manyPortions, 31, "NORMALIZED", 35340, 0, id(603), id(702), null,
        (1..31).map { BoundManagerPortion(id(2000 + it), id(2200 + it), "1", 1140) },
        beforeDiscountPayable = 35340, advertisedPayableDiscount = 0)

    /**
     * DTO constructor wiring, not qualification of the full frozen recorded quote.
     * EGP fills the existing two-decimal currency type only; the fixture specifies no currency.
     * Activity/channel, line/unit IDs, expectedCatalogRevision and components are constructor
     * fills, NOT oracles for stored names, human selections, original subtotal, unit prices,
     * tax allocations, native source profiles, history or custody. Payable-summary inputs come
     * directly from qa/pos/freezes/manager-order-presentation/payable-summary-supplement-v3.json;
     * merchandise-net discount remains a distinct original-detail requirement, not a DTO alias.
     */
    fun projection(expected: BoundManagerOrder): LocalProfiledOrderV2 {
        val lines = expected.portions.mapIndexed { index, portion ->
            LocalOrderLineV1(constructorCoordinate(1000 + index),
                portion.portionId ?: constructorCoordinate(2000 + index), portion.skuId,
                Quantity.parse(constructorCoordinate(3), portion.quantity, if (portion.quantity.contains('.')) 3 else 0))
        }
        return LocalProfiledOrderV2(LocalOrderViewV1(expected.target, owner, Revision(expected.revision), Revision(5),
            LocalOrderSelectionV1(LocalSalesActivity.CLOTHING, LocalSalesChannel.COUNTER, Currency.EGP, Revision(5), lines),
            Money(Currency.EGP, expected.beforeDiscountPayable), Money(Currency.EGP, expected.advertisedPayableDiscount),
            Money(Currency.EGP, expected.payable),
            lines.mapIndexed { index, line -> LocalOrderPriceLineV1(line.portionId, line.quantity,
                Money(Currency.EGP, expected.portions[index].payable)) }, emptyList(),
            expected.collection, expected.sale, expected.handoff, null), LocalOrderStockProfileV2.valueOf(expected.profile))
    }
}

internal data class BoundManagerPortion(val portionId: DomainId?, val skuId: DomainId, val quantity: String, val payable: Long)
internal data class BoundManagerOrder(val target: DomainId, val revision: Long, val profile: String, val payable: Long,
    val merchandiseNetDiscount: Long, val collection: DomainId, val sale: DomainId?, val handoff: DomainId?, val portions: List<BoundManagerPortion>,
    val beforeDiscountPayable: Long, val advertisedPayableDiscount: Long)

// This serializer constructs controlled input bodies; it is not an expected canonical vector.
internal val managerControlWire: Json = Json { encodeDefaults = true }
internal fun <T> managerControlBody(value: T, serializer: KSerializer<T>): StoredBytes {
    val bytes = managerControlWire.encodeToString(serializer, value).encodeToByteArray()
    return try { StoredBytes(bytes) } finally { bytes.fill(0) }
}
internal fun managerControlData(result: LocalOrderReadResultV2): ReadReply.Data = ReadReply.Data(
    managerControlBody(result, LocalOrderReadResultV2.serializer()), Revision(43),
    UtcInstant.parseCanonical("2026-10-30T09:00:00Z"))
internal fun managerControlData(element: JsonElement): ReadReply.Data = ReadReply.Data(
    managerControlBody(element, JsonElement.serializer()), Revision(43),
    UtcInstant.parseCanonical("2026-10-30T09:00:00Z"))
internal fun managerPresent(expected: BoundManagerOrder = ManagerPresentationInputs.legacyExpected): ReadReply.Data =
    managerControlData(LocalOrderReadResultV2(listOf(ManagerPresentationInputs.projection(expected)), null))

/** Actual production gateway; the endpoint/current-view/credential inputs are controlled seams. */
internal class ControlledManagerGateway(
    target: DomainId = ManagerPresentationInputs.legacy,
    grants: List<PermissionGrant> = listOf(ManagerPresentationInputs.hint(target = target)),
    reads: Set<ReadKey> = setOf(ManagerPresentationInputs.managerKey),
    clientPolicyRevision: Revision = Revision(43),
    response: suspend () -> ReadReply = { managerPresent() },
) {
    val journal = RejectingManagerReadJournal(LocalClientIdentity(
        ManagerPresentationInputs.scope, ManagerPresentationInputs.manager, ManagerPresentationInputs.client))
    val endpoint = ControlledManagerEndpoint(ManagerPresentationInputs.view(grants, clientPolicyRevision), target, response)
    var leaseCurrent = true
    var attachedLease: DomainId? = ManagerPresentationInputs.managerLease
    var credentialAcquisitions = 0
        private set
    val gateway = LocalEndpointWorkflowGateway(endpoint, journal, LocalWorkflowCredentialSource {
        credentialAcquisitions++
        val captured = attachedLease
        LocalWorkflowCredentialLease(LocalGatewayCredentials(ManagerPresentationInputs.constructorCoordinate(2),
            OpaqueCredential(ByteArray(16)))) { leaseCurrent && captured == attachedLease && captured == ManagerPresentationInputs.managerLease }
    }, LocalWorkflowInstallation(emptySet(), reads))

    fun assertReadOnly() {
        assertTrue(journal.calls.isEmpty(), "No journal find/pending/stage/dispatch/remember/acknowledge/close")
        assertEquals(0, endpoint.executeCalls)
        assertEquals(0, endpoint.lookupCalls)
        assertTrue(endpoint.rejectedCalls.isEmpty(), "No unrelated authentication/native/endpoint path")
    }
    fun assertTargetDispatch(count: Int = 1) {
        assertEquals(List(count) { ManagerPresentationInputs.managerKey }, endpoint.keys)
        assertEquals(List(count) { LocalManagerOrderReviewQueryV1(endpoint.target) }, endpoint.selectors)
        assertEquals(List(count) { setOf("orderId") }, endpoint.logicalFields,
            "No ambient employee/owner/branch/role/permission/revision/recipient/authority field")
        assertReadOnly()
    }
}

/** Every journal operation records and rejects; there is no accepting memory journal. */
internal class RejectingManagerReadJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    val calls = mutableListOf<String>()
    internal override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> = reject("find")
    internal override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> = reject("pending")
    internal override suspend fun stage(intent: StoredClientIntentV1): JournalWrite = reject("stage")
    internal override suspend fun markDispatched(operationId: OperationId): JournalWrite = reject("markDispatched")
    internal override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = reject("remember")
    internal override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = reject("acknowledge")
    override suspend fun close(): Unit = reject("close")
    private fun reject(port: String): Nothing { calls += port; throw AssertionError("Forbidden manager-review journal call: $port") }
}

/** Typed Data construction only: no actual Master authorization, storage loan or admitted RPC. */
internal class ControlledManagerEndpoint(val view: LocalSessionView, val target: DomainId,
    private val response: suspend () -> ReadReply) : LocalMasterClient {
    val keys = mutableListOf<ReadKey>()
    val selectors = mutableListOf<LocalManagerOrderReviewQueryV1>()
    val logicalFields = mutableListOf<Set<String>>()
    val rejectedCalls = mutableListOf<String>()
    var currentCalls = 0
        private set
    var executeCalls = 0
        private set
    var lookupCalls = 0
        private set
    override val health: StateFlow<LocalMasterEndpointHealth> get() = reject("health")
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = reject("signIn")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = reject("refresh")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = reject("logout")
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        currentCalls++
        return LocalSessionViewResult.Current(view)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executeCalls++; return reject("execute")
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookupCalls++; return reject("lookup")
    }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        keys += request.query
        assertEquals(ManagerPresentationInputs.managerKey, request.query, "No owner-private v1/v2, handoff or FOOD fallback")
        val bytes = request.payload.copyBytes()
        val selector = try {
            val source = bytes.decodeToString()
            logicalFields += managerControlWire.parseToJsonElement(source).jsonObject.keys
            managerControlWire.decodeFromString(LocalManagerOrderReviewQueryV1.serializer(), source)
        } finally { bytes.fill(0) }
        selectors += selector
        assertEquals(LocalManagerOrderReviewQueryV1(target), selector)
        return response()
    }
    private fun reject(port: String): Nothing { rejectedCalls += port; throw AssertionError("Forbidden manager endpoint call: $port") }
}

/** Current presentation-label binding only; the labels/format are not frozen translation oracles. */
internal fun assertBoundManagerPage(result: LocalReadResult, expected: BoundManagerOrder): LocalWorkflowPage =
    assertBoundManagerPage(assertIs<LocalReadResult.Available>(result).page, expected)

internal fun assertBoundManagerPage(page: LocalWorkflowPage, expected: BoundManagerOrder): LocalWorkflowPage {
    assertEquals(LocalWorkflow.ORDERS, page.workflow)
    assertTrue(page.forms.isEmpty())
    assertTrue(page.records.all { !it.selectable })
    assertNull(page.nextCursor)
    assertNull(page.priceControls)
    val summary = page.records.single { it.reference.kind == LocalRecordKind.ORDER }
    assertEquals(LocalRecordReference(LocalRecordKind.ORDER, expected.target, Revision(expected.revision)), summary.reference)
    assertLiteral(summary, "Current owner", ManagerPresentationInputs.owner.value)
    assertLiteral(summary, "Business revision", expected.revision.toString())
    assertLiteral(summary, "Stock profile", expected.profile)
    assertLiteral(summary, "Quote revision", "5")
    assertMoney(summary, "Before discount", expected.beforeDiscountPayable)
    assertMoney(summary, "Advertised discount", expected.advertisedPayableDiscount)
    assertMoney(summary, "Exact payable", expected.payable)
    // These are the independently frozen v3 payable-summary meanings. They do not substitute
    // for the v1/v2 merchandise-net discount, posted tax or component-allocation requirements.
    for ((field, value) in listOf(LocalField.COLLECTION_ID to expected.collection,
        LocalField.SALE_ID to expected.sale, LocalField.HANDOFF_ID to expected.handoff)) {
        val rendered = summary.values.filter { it.label == fieldLabel(field) }
        if (value == null) assertTrue(rendered.isEmpty()) else assertEquals(listOf(LocalText(value.value, value.value)), rendered.map { it.value })
    }
    val details = page.records.filter { it.reference.kind == LocalRecordKind.ORDER_LINE }
    assertEquals(page.records.size, 1 + details.size, "One target summary plus nonselectable details only")
    val portions = details.flatMap { record ->
        val starts = record.values.indices.filter { record.values[it].label == fieldLabel(LocalField.LINE_ID) }
        starts.mapIndexed { index, start ->
            val values = record.values.subList(start, starts.getOrNull(index + 1) ?: record.values.size)
            fun value(field: LocalField): LocalText = values.single { it.label == fieldLabel(field) }.value.also {
                assertEquals(it.english, it.arabic)
            }
            val quantity = value(LocalField.QUANTITY)
            val payable = values.single { it.label.english == "Recorded payable" }.value
            assertEquals(quantity.english, quantity.arabic)
            assertEquals(payable.english, payable.arabic)
            BoundManagerPortion(DomainId(value(LocalField.PORTION_ID).english), DomainId(value(LocalField.SKU_ID).english),
                BigDecimal(quantity.english.substringBefore(" · ")).stripTrailingZeros().toPlainString(), amountMinor(payable.english))
        }
    }
    assertEquals(expected.portions.size, portions.size)
    assertEquals(expected.portions.size, portions.map { it.skuId }.distinct().size)
    assertEquals(expected.portions.associate { it.skuId to (BigDecimal(it.quantity).stripTrailingZeros().toPlainString() to it.payable) },
        portions.associate { it.skuId to (it.quantity to it.payable) }, "Numeric quantities and recorded line payables by exact SKU")
    if (expected.portions.all { it.portionId != null })
        assertEquals(expected.portions.associate { it.portionId to it.skuId }, portions.associate { it.portionId to it.skuId },
            "Every independently frozen supported portion identity occurs exactly once")
    val notice = assertNotNull(page.notice)
    assertTrue(notice.english.isNotBlank() && notice.arabic.isNotBlank())
    // Four bilingual meanings and unrepresented names/selection/unit-price/tax semantics remain
    // separate verification gaps; nonblank text is not a claim that those meanings were tested.
    return page
}

private fun assertLiteral(record: LocalRecord, observerLabel: String, expected: String) {
    assertEquals(LocalText(expected, expected), record.values.single { it.label.english == observerLabel }.value)
}
private fun assertMoney(record: LocalRecord, observerLabel: String, expected: Long) {
    val value = record.values.single { it.label.english == observerLabel }.value
    assertEquals(expected, amountMinor(value.english))
    assertEquals(expected, amountMinor(value.arabic))
}
private fun amountMinor(value: String): Long = BigDecimal(value.substringBefore(' ')).movePointRight(2).longValueExact()

internal fun assertManagerNoSuccess(result: LocalReadResult, harness: ControlledManagerGateway) {
    assertIs<LocalReadResult.Unavailable>(result, "Failure must not publish private or successful-empty content")
    harness.assertReadOnly()
}
