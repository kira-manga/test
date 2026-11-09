package com.ukkera.pos.app.gateway.independent.fooddeliverydraftsv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import kotlin.test.*

/** Frozen delivery input coordinates; public response construction is not a protected read. */
internal object FdpGatewayInputs {
    private val f = FdpFrozenData
    fun id(group: String, key: String): DomainId = DomainId(f.text(group, key))
    val owner = DomainId(f.nestedText("actors", "ownerCashier", "actorId"))
    val device = DomainId(f.nestedText("actors", "ownerCashier", "deviceId"))
    val session = DomainId(f.nestedText("actors", "ownerCashier", "sessionId"))
    val scope = BranchScope(id("scope", "organizationId"), id("scope", "foodCompanyId"), id("scope", "foodBranchId"))
    val one = Quantity(id("catalog", "servingUnitId"), 1, 0)
    val time = UtcInstant.parseCanonical(f.text("clock", "createdAtUtc"))
    val deliveryFields = mapOf("recipient" to LocalField.FOOD_DELIVERY_RECIPIENT,
        "contact" to LocalField.FOOD_DELIVERY_CONTACT, "address" to LocalField.FOOD_DELIVERY_ADDRESS)
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }

    fun bytes(text: String): StoredBytes = StoredBytes(text.encodeToByteArray())
    fun legacyBytes(food: LocalFoodReadResultV1): StoredBytes = bytes(json.encodeToString(LocalFoodReadResultV1.serializer(), food))
    fun wrapperBytes(wrapper: LocalFoodCheckKitchenResultV1): StoredBytes =
        bytes(json.encodeToString(LocalFoodCheckKitchenResultV1.serializer(), wrapper))
    fun details(fields: Map<String, String> = f.baseFields()): LocalFoodDeliveryDetailsV1 =
        LocalFoodDeliveryDetailsV1(fields.getValue("recipient"), fields.getValue("contact"), fields.getValue("address"))

    fun intent(activity: String = "RESTAURANT", fields: Map<String, String> = f.baseFields(),
        reference: String? = "DEL-SYN-030", channel: String = "DELIVERY", formVersion: Int = 2): LocalCommandIntent {
        val orderId = when (channel) {
            "DELIVERY" -> id("orderIds", if (activity == "CAFE") "deliveryCafe" else "deliveryRestaurant")
            "TAKEAWAY" -> id("orderIds", "takeaway")
            "DINE_IN" -> id("orderIds", "dineIn")
            else -> error("Unspecified frozen channel")
        }
        return LocalCommandIntent(OperationId(f.text("operationIds", "create")), LocalAction.FOOD_CREATE_DRAFT,
            formVersion, null, listOf(
                LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(orderId)),
                LocalScalarInput(LocalField.ACTIVITY, LocalScalarValue.Choice(activity)),
                LocalScalarInput(LocalField.CHANNEL, LocalScalarValue.Choice(channel)),
            ) + listOfNotNull(reference?.let { LocalScalarInput(LocalField.SERVICE_REFERENCE, LocalScalarValue.Text(it)) }) +
                LocalScalarInput(LocalField.SEAT_NOTE, LocalScalarValue.Text("")) + fields.map { (key, value) ->
                    LocalScalarInput(deliveryFields.getValue(key), LocalScalarValue.Text(value))
                })
    }

    fun expectedDeliveryPayload(activity: String, fields: Map<String, String>): String {
        val root = Json.parseToJsonElement(f.canonicalDraft).jsonObject
        val request = root.getValue("request").jsonObject
        val orderId = id("orderIds", if (activity == "CAFE") "deliveryCafe" else "deliveryRestaurant")
        return JsonObject(root + ("request" to JsonObject(request +
            ("orderId" to JsonPrimitive(orderId.value)) + ("activity" to JsonPrimitive(activity)) +
            ("deliveryDetails" to JsonObject(fields.mapValues { JsonPrimitive(it.value) }))))).toString()
    }

    /** Preparation domain revision/name are constructor metadata, not historical evidence. */
    fun food(channel: LocalSalesChannel = LocalSalesChannel.DELIVERY): LocalFoodReadResultV1 {
        val orderId = id("orderIds", if (channel == LocalSalesChannel.DELIVERY) "deliveryRestaurant" else "takeaway")
        val reference = if (channel == LocalSalesChannel.DELIVERY) "DEL-SYN-030" else "PU-SYN-030"
        val portions = listOf("selectedFirst", "selectedSecond").map { key ->
            val portionId = id("portionIds", key)
            LocalFoodPortionViewV1(portionId, "Synthetic quoted burger", one, emptyList(), "",
                FoodPreparationState(portionId, Revision(1), FoodPreparationStatus.NOT_SENT))
        }
        return LocalFoodReadResultV1(detail = LocalFoodOrderDetailV1(
            LocalFoodOrderSummaryV1(orderId, owner, Revision(30), LocalSalesActivity.RESTAURANT,
                LocalFoodServiceV1(channel, reference, ""), 2, null, null), portions, Money(Currency.EGP, 22_800), true))
    }

    fun wrapper(): LocalFoodCheckKitchenResultV1 {
        val order = id("orderIds", "deliveryRestaurant")
        return LocalFoodCheckKitchenResultV1(food(),
            LocalFoodCheckKitchenObservationV1(order, Revision(30), 2, LocalFoodCheckKitchenStatusV1.UNAVAILABLE),
            LocalFoodDeliveryObservationV1(order, Revision(30), details()))
    }

    /** Exact frozen malformed/mismatched semantic attempts; no original/frame is fabricated. */
    fun invalidWrappers(): List<Pair<String, String>> {
        val root = Json.parseToJsonElement(wrapperBytes(wrapper()).copyBytes().decodeToString()).jsonObject
        val observation = root.getValue("delivery").jsonObject
        val nonDeliveryOrder = JsonPrimitive(f.text("orderIds", "takeaway"))
        val nonDeliveryKitchen = JsonObject(root.getValue("kitchen").jsonObject + ("orderId" to nonDeliveryOrder))
        return listOf(
            f.caseId(f.ownCheckVariants[1]) to JsonObject(root - "delivery").toString(),
            f.caseId(f.ownCheckVariants[2]) to JsonObject(root + ("delivery" to JsonObject(observation +
                ("details" to f.metadataVariants[2].getValue("deliveryDetails"))))).toString(),
            f.caseId(f.ownCheckVariants[3]) to JsonObject(root + ("delivery" to JsonObject(observation +
                ("orderId" to JsonPrimitive(f.text("orderIds", "otherOwner")))))).toString(),
            f.caseId(f.ownCheckVariants[4]) to JsonObject(root + ("delivery" to JsonObject(observation +
                ("orderStorageRevision" to JsonPrimitive("20"))))).toString(),
            f.caseId(f.ownCheckVariants[5]) to JsonObject(root + ("food" to Json.parseToJsonElement(
                legacyBytes(food(LocalSalesChannel.TAKEAWAY)).copyBytes().decodeToString())) +
                ("kitchen" to nonDeliveryKitchen) + ("delivery" to JsonObject(observation + ("orderId" to nonDeliveryOrder)))).toString(),
        )
    }
}

/** Only a public read/client-input seam; no accepting writer, original absence or native history. */
internal class FdpGatewayFixture(diagnosticInstalled: Boolean = true) {
    val endpoint = FdpScriptedEndpoint()
    val journal = FdpInputJournal(LocalClientIdentity(FdpGatewayInputs.scope, FdpGatewayInputs.owner, FdpGatewayInputs.device))
    var lifetimeCurrent = true
    val installation = LocalWorkflowInstallation(setOf(LocalFoodStockCodecV2.COMMAND, LocalFoodProfileV1.COMMAND),
        setOf(LocalFoodProfileV1.QUERY) + if (diagnosticInstalled) setOf(LocalFoodCheckKitchenCodecV1.QUERY) else emptySet())
    val gateway = newGateway()
    private fun lease(): LocalWorkflowCredentialLease = LocalWorkflowCredentialLease(
        LocalGatewayCredentials(FDP_CONSTRUCTOR_CONNECTION, OpaqueCredential(ByteArray(16)))) { lifetimeCurrent }
    fun newGateway(): LocalEndpointWorkflowGateway = LocalEndpointWorkflowGateway(endpoint, journal,
        LocalWorkflowCredentialSource { lease() }, installation)
    suspend fun <T> withAccess(block: suspend (GatewayAccess) -> T): T {
        val lease = lease()
        return try { block(GatewayAccess(endpoint, lease, endpoint.view, installation)) } finally { lease.destroy() }
    }
    fun query(): LocalWorkflowQuery = LocalWorkflowQuery(LocalWorkflow.FOOD,
        food = LocalFoodUiQuery.Check(FdpGatewayInputs.id("orderIds", "deliveryRestaurant")))
    fun assertNoBusinessDispatch() {
        assertEquals(0, endpoint.executeCalls)
        assertEquals(0, endpoint.authenticationCalls)
        assertEquals(0, journal.dispatchCalls)
        assertEquals(0, journal.resultWrites)
        assertEquals(0, journal.acknowledgements)
    }
    fun assertNothingStaged() {
        assertNull(journal.entry)
        assertEquals(0, journal.stageCalls)
        assertNoBusinessDispatch()
    }
}

/** Scripted public DTOs only; native authentication, runtime health and business writes fail. */
internal class FdpScriptedEndpoint : LocalMasterClient {
    val reads = mutableListOf<ReadRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    var executeCalls = 0
    var authenticationCalls = 0
    var view = fdpConstructorView()
    var lookupReason = DeferredReason.WRITER_NOT_QUALIFIED
    var onRead: suspend (ReadRequest) -> ReadReply = { request ->
        when (request.query) {
            LocalFoodCheckKitchenCodecV1.QUERY -> data(FdpGatewayInputs.wrapperBytes(FdpGatewayInputs.wrapper()))
            LocalFoodProfileV1.QUERY -> data(FdpGatewayInputs.legacyBytes(FdpGatewayInputs.food()))
            else -> error("Unbound read selector")
        }
    }
    // Branch revision and observed timestamp are response-constructor metadata only.
    fun data(payload: StoredBytes): ReadReply.Data = ReadReply.Data(payload, Revision(1), FdpGatewayInputs.time)
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("No runtime health evidence")
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = LocalSessionViewResult.Current(view)
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        return onRead(request)
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        return LookupReply.Deferred(identity.operationId, lookupReason)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executeCalls++
        error("No qualified writer/absence or business dispatch fixture")
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult { authenticationCalls++; error("No native authentication fixture") }
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult {
        authenticationCalls++; error("No native refresh fixture")
    }
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult {
        authenticationCalls++; error("No native logout fixture")
    }
}

/** Stores one actual staged CLIENT input. DURABLE is a seam reply, not persistence evidence. */
internal class FdpInputJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    var entry: StoredClientIntentV1? = null
    var stageCalls = 0
    var dispatchCalls = 0
    var resultWrites = 0
    var acknowledgements = 0
    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> =
        JournalRead.Value(entry?.takeIf { it.operationId == operationId })
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> = JournalRead.Value(listOfNotNull(entry))
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        stageCalls++
        check(entry == null)
        intent.validate(owner)
        entry = intent
        return JournalWrite.DURABLE
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite {
        dispatchCalls++; error("No qualified original absence")
    }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        resultWrites++; error("No committed original result")
    }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        acknowledgements++; error("No committed original result")
    }
    override suspend fun close() = Unit
}

/** All policy/epoch/monotonic/credential atoms below are constructor metadata, never authority. */
private val FDP_CONSTRUCTOR_BOOT = DomainId("6d300000-0000-4000-8000-000000000001")
private val FDP_CONSTRUCTOR_CONNECTION = DomainId("6d300000-0000-4000-8000-000000000002")
private fun fdpConstructorView(): LocalSessionView {
    val f = FdpGatewayInputs
    val permissions = listOf(LocalAction.FOOD_CREATE_DRAFT, LocalAction.FOOD_ADD, LocalAction.FOOD_EDIT_SERVICE_DETAILS,
        LocalAction.FOOD_SEND).mapNotNull(::actionPermission).distinct() + Permission(SecurityAction.ORDER_READ, PermissionVerb.PERFORM)
    return LocalSessionView(f.owner, f.scope, f.device, f.session, SessionGeneration(1), LocalLoginIdentifier("synthetic-owner"),
        ActivatedLocalPolicy(f.scope, f.owner, Revision(1), permissions.map { PermissionGrant(it, f.scope, GrantEffect.ALLOW) },
            LocalSessionPolicy(), ThrottlePolicy()), MonotonicMoment(FDP_CONSTRUCTOR_BOOT, 1000),
        MonotonicMoment(FDP_CONSTRUCTOR_BOOT, 2000))
}
