package com.ukkera.pos.app.gateway.independent.foodblockedunpaidstatev1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRelationalQueriesV2
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import kotlin.test.*

/** Controlled public DTO/read and client-input seams only; no owned Master/history fixture. */
internal object FbuGatewayInputs {
    fun id(suffix: String) = DomainId("fd300000-0000-4000-8000-${suffix.padStart(12, '0')}")
    val owner = id("040")
    val device = id("051")
    val scope = BranchScope(id("001"), id("010"), id("020"))
    val time = UtcInstant.parseCanonical("2026-11-01T08:00:00Z")
    const val itemNote = "Serve together / يُقدَّم معًا"
    const val seatNote = "Seat 2 / المقعد ٢"
    val one = Quantity(id("240"), 1, 0)
    val r0 = FbuGatewayOrder(id("100"), LocalSalesActivity.RESTAURANT, emptyList(), 0)
    val c0 = FbuGatewayOrder(id("101"), LocalSalesActivity.CAFE, emptyList(), 0)
    val r2 = FbuGatewayOrder(id("102"), LocalSalesActivity.RESTAURANT, listOf(id("301"), id("302")), 20_000)
    val c2 = FbuGatewayOrder(id("103"), LocalSalesActivity.CAFE, listOf(id("311"), id("312")), 6_000)
    val mixed = FbuGatewayOrder(id("106"), LocalSalesActivity.RESTAURANT,
        listOf(id("321"), id("322"), id("323")), 30_000, setOf(id("321")))
    val issued = FbuGatewayOrder(id("108"), LocalSalesActivity.RESTAURANT,
        listOf(id("301"), id("302")), 20_000, setOf(id("301"), id("302")))

    @OptIn(ExperimentalSerializationApi::class)
    val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    fun bytes(text: String) = StoredBytes(text.encodeToByteArray())
    fun legacyBytes(value: LocalFoodReadResultV1) = bytes(json.encodeToString(LocalFoodReadResultV1.serializer(), value))
    fun wrapperBytes(value: LocalFoodCheckKitchenResultV1) = bytes(json.encodeToString(LocalFoodCheckKitchenResultV1.serializer(), value))

    fun food(order: FbuGatewayOrder, visible: List<DomainId> = order.portionIds, next: DomainId? = null): LocalFoodReadResultV1 {
        val summary = LocalFoodOrderSummaryV1(order.orderId, owner, Revision(7), order.activity,
            LocalFoodServiceV1(order.channel, if (order.channel == LocalSalesChannel.DINE_IN) id("090").value else "FBU-P001",
                if (order.channel == LocalSalesChannel.DINE_IN) seatNote else ""), order.portionIds.size, null, null)
        return LocalFoodReadResultV1(detail = LocalFoodOrderDetailV1(summary, visible.map { portion(order, it) },
            Money(Currency.EGP, order.quoteMinor), false), nextAfter = next)
    }

    fun wrapper(order: FbuGatewayOrder, status: LocalFoodCheckKitchenStatusV1? = LocalFoodCheckKitchenStatusV1.UNAVAILABLE,
        visible: List<DomainId> = order.portionIds, next: DomainId? = null): LocalFoodCheckKitchenResultV1 =
        LocalFoodCheckKitchenResultV1(food(order, visible, next), status?.let {
            LocalFoodCheckKitchenObservationV1(order.orderId, Revision(7), order.portionIds.size - order.issuedIds.size, it)
        })

    /**
     * Already-issued members are PUBLIC RESPONSE inputs only. The pure sent transition supplies
     * a QUEUED representative, not a historical issue or accepting retained producer. Domain
     * revisions, DISPLAY, timestamp and reservation coordinates are constructor-only metadata.
     * Exact frozen issue/task labels cannot establish inventory/custody or current authority.
     */
    private fun portion(order: FbuGatewayOrder, portionId: DomainId): LocalFoodPortionViewV1 {
        val unsent = FoodPreparationState(portionId, Revision(1), FoodPreparationStatus.NOT_SENT)
        val preparation = if (portionId !in order.issuedIds) unsent else {
            val mixedMember = order == mixed
            val second = portionId == id("302")
            val issueId = id(if (mixedMember) "500" else if (second) "502" else "501")
            val taskId = id(if (mixedMember) "600" else if (second) "602" else "601")
            FoodPreparation.sent(unsent, unsent.revision, id("540"), issueId,
                listOf(FoodStationTask(taskId, id("071"), FoodStationDeliveryMode.DISPLAY, FoodStationStatus.QUEUED)), time)
        }
        return LocalFoodPortionViewV1(portionId, "Synthetic serving / حصة تجريبية", one, emptyList(), itemNote,
            preparation, if (order == mixed) null else order.portionIds.first(), seatNote)
    }

    fun menu(order: FbuGatewayOrder) = LocalFoodUiBody.Menu(Revision(5), listOf(LocalFoodUiRecipe(
        id(if (order.activity == LocalSalesActivity.CAFE) "211" else "210"), Revision(2),
        id(if (order.activity == LocalSalesActivity.CAFE) "201" else "200"), one, true,
        listOf(LocalFoodUiChoice(id(if (order.activity == LocalSalesActivity.CAFE) "231" else "230"),
            LocalText("Synthetic choice", "اختيار تجريبي"), emptyList())))))

    fun sendIntent(order: FbuGatewayOrder) = LocalCommandIntent(OperationId(id("403").value), LocalAction.FOOD_SEND, 1, null,
        listOf(LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(order.orderId)),
            LocalScalarInput(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, LocalScalarValue.Integer(7)),
            LocalScalarInput(LocalField.PORTION_ID, LocalScalarValue.References(order.portionIds.map {
                LocalRecordReference(LocalRecordKind.FOOD_PORTION, it, Revision(1), order.orderId)
            }))))

    fun beginIntent(order: FbuGatewayOrder) = LocalCommandIntent(OperationId(id("405").value), LocalAction.FOOD_BEGIN_COLLECTION, 1, null,
        listOf(LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(order.orderId)),
            LocalScalarInput(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, LocalScalarValue.Integer(7)),
            LocalScalarInput(LocalField.COLLECTION_ID, LocalScalarValue.Identifier(id("541"))),
            LocalScalarInput(LocalField.SESSION_ID, LocalScalarValue.Identifier(id("092"))),
            // The freeze prescribes no cash-session domain revision. This is not financial admission evidence.
            LocalScalarInput(LocalField.EXPECTED_SESSION_REVISION, LocalScalarValue.Integer(1)),
            LocalScalarInput(LocalField.AMOUNT, LocalScalarValue.Decimal(when (order.quoteMinor) {
                20_000L -> "200.00"; 6_000L -> "60.00"; 30_000L -> "300.00"; else -> error("Unbound fixture amount")
            }))))
}

internal data class FbuGatewayOrder(val orderId: DomainId, val activity: LocalSalesActivity,
    val portionIds: List<DomainId>, val quoteMinor: Long, val issuedIds: Set<DomainId> = emptySet(),
    val channel: LocalSalesChannel = LocalSalesChannel.DINE_IN)

internal class FbuGatewayFixture(val order: FbuGatewayOrder = FbuGatewayInputs.r2, diagnosticInstalled: Boolean = true) {
    val endpoint = FbuPublicEndpoint(order)
    val journal = FbuInputJournal(LocalClientIdentity(FbuGatewayInputs.scope, FbuGatewayInputs.owner, FbuGatewayInputs.device))
    var lifetimeCurrent = true
    val installation = LocalWorkflowInstallation(setOf(LocalFoodProfileV1.COMMAND, LocalFoodStockCodecV2.COMMAND,
        LocalFoodFinancialProfileV1.COMMAND), setOf(LocalFoodProfileV1.QUERY, LocalFoodFinancialProfileV1.QUERY,
        LocalStockRelationalQueriesV2.PROFILE) + if (diagnosticInstalled) setOf(LocalFoodCheckKitchenCodecV1.QUERY) else emptySet())
    private fun lease() = LocalWorkflowCredentialLease(LocalGatewayCredentials(FbuGatewayInputs.device,
        OpaqueCredential(ByteArray(16)))) { lifetimeCurrent }
    val gateway = LocalEndpointWorkflowGateway(endpoint, journal, LocalWorkflowCredentialSource { lease() }, installation)
    suspend fun <T> withAccess(block: suspend (GatewayAccess) -> T): T {
        val lease = lease()
        return try { block(GatewayAccess(endpoint, lease, endpoint.view, installation)) } finally { lease.destroy() }
    }
    fun query() = LocalWorkflowQuery(LocalWorkflow.FOOD, food = LocalFoodUiQuery.Check(order.orderId))
    fun assertNoDispatch() { assertEquals(0, endpoint.executeCalls); assertTrue(endpoint.lookups.isEmpty()) }
    fun assertNothingStaged() { assertNull(journal.entry); assertEquals(0, journal.stageCalls); assertNoDispatch() }
}

/** A scripted public reply is not a protected Master read. Health and native authentication fail. */
internal class FbuPublicEndpoint(order: FbuGatewayOrder) : LocalMasterClient {
    val reads = mutableListOf<ReadRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    var executeCalls = 0
    var view = fbuView()
    var onRead: suspend (ReadRequest) -> ReadReply = { request ->
        data(if (request.query == LocalStockRelationalQueriesV2.PROFILE) FbuGatewayInputs.bytes("{\"version\":2,\"normalized\":false}")
        else if (request.query == LocalFoodProfileV1.QUERY) FbuGatewayInputs.legacyBytes(FbuGatewayInputs.food(order))
        else FbuGatewayInputs.wrapperBytes(FbuGatewayInputs.wrapper(order)))
    }
    // Branch revision 1 is response-constructor metadata; no storage-revision formula is asserted.
    fun data(payload: StoredBytes): ReadReply.Data = ReadReply.Data(payload, Revision(1), FbuGatewayInputs.time)
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("No runtime health evidence")
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = LocalSessionViewResult.Current(view)
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply { reads += request; return onRead(request) }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executeCalls++; error("No qualified writer or business dispatch fixture")
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity; return LookupReply.Deferred(identity.operationId, DeferredReason.WRITER_NOT_QUALIFIED)
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = error("No native authentication fixture")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("No native refresh fixture")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("No native logout fixture")
}

/** Stores one controlled CLIENT input. DURABLE is a seam reply, never native persistence evidence. */
internal class FbuInputJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    var entry: StoredClientIntentV1? = null
    var stageCalls = 0
    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> = JournalRead.Value(entry?.takeIf { it.operationId == operationId })
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> = JournalRead.Value(listOfNotNull(entry))
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite { stageCalls++; check(entry == null); entry = intent; return JournalWrite.DURABLE }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite = error("No accepting original absence")
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = error("No committed result fixture")
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = error("No committed result fixture")
    override suspend fun close() = Unit
}

/** Policy, generation and monotonic values below are display-only constructor metadata, not grants. */
private fun fbuView(): LocalSessionView {
    val f = FbuGatewayInputs
    val permissions = listOf(LocalAction.FOOD_CREATE_DRAFT, LocalAction.FOOD_ADD, LocalAction.FOOD_SEND,
        LocalAction.FOOD_BEGIN_COLLECTION, LocalAction.FOOD_EDIT_SERVICE_DETAILS).mapNotNull(::actionPermission).distinct() +
        Permission(SecurityAction.ORDER_READ, PermissionVerb.PERFORM)
    val boot = DomainId("6f290000-0000-4000-8000-000000000001")
    return LocalSessionView(f.owner, f.scope, f.device, f.id("060"), SessionGeneration(1), LocalLoginIdentifier("owner"),
        ActivatedLocalPolicy(f.scope, f.owner, Revision(1), permissions.map { PermissionGrant(it, f.scope, GrantEffect.ALLOW) },
            LocalSessionPolicy(), ThrottlePolicy()), MonotonicMoment(boot, 1000), MonotonicMoment(boot, 2000))
}
