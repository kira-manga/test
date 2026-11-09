package com.ukkera.pos.app.gateway.independent.foodquotedaddv2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Controlled gateway inputs only. Session/display coordinates are supplied host
 * metadata, never the native DRAFT, CATALOG or original-source bindings.
 */
internal object FoodQuotedAddBoundedUiInputs {
    val scope: BranchScope = BranchScope(
        DomainId("00000000-0000-4000-8000-00000000a001"),
        DomainId("00000000-0000-4000-8000-00000000a002"),
        DomainId("00000000-0000-4000-8000-00000000a003"))
    val owner: DomainId = DomainId("00000000-0000-4000-8000-00000000e001")
    val device: DomainId = DomainId("00000000-0000-4000-8000-00000000e003")
    val session: DomainId = DomainId("00000000-0000-4000-8000-00000000e004")
    val selectorOrder: DomainId = DomainId("00000000-0000-4000-8000-00000000a901")
    val displayedOrderRevision: Revision = Revision(37)
    val displayedCatalogRevision: Revision = Revision(103)
    val displayedCutRevision: Revision = Revision(151)
    val observedAt: UtcInstant = UtcInstant.parseCanonical("2026-10-31T00:00:00Z")
    val firstOperation: OperationId = OperationId("00000000-0000-4000-8000-00000000d001")
    val recipe: DomainId = DomainId("00000000-0000-4000-8000-00000000a301")
    val outputSku: DomainId = DomainId("00000000-0000-4000-8000-00000000a201")
    val choice: DomainId = DomainId("00000000-0000-4000-8000-00000000a303")
    val portionUnit: DomainId = DomainId("00000000-0000-4000-8000-00000000a501")

    fun menuQuery(): LocalWorkflowQuery = LocalWorkflowQuery(LocalWorkflow.FOOD,
        food = LocalFoodUiQuery.Menu(orderId = selectorOrder, expectedOrderStorageRevision = displayedOrderRevision))

    fun installation(normalizedFood: Boolean = true, quotationRead: Boolean = true,
        previewRead: Boolean = false): LocalWorkflowInstallation = LocalWorkflowInstallation(
        buildSet {
            add(LocalFoodProfileV1.COMMAND)
            if (normalizedFood) add(LocalFoodStockCodecV2.COMMAND)
        }, buildSet {
            add(LocalFoodProfileV1.QUERY)
            add(LocalFoodMenuCodecV1.QUERY)
            if (quotationRead) add(LocalFoodQuotationProfileReadCodecV1.QUERY)
            if (previewRead) add(LocalFoodSelectionPreviewCodecV1.QUERY)
        })

    fun menuPage(): LocalFoodMenuPageV1 = LocalFoodMenuPageV1(displayedCatalogRevision,
        listOf(LocalFoodMenuRecipeV1(recipe, Revision(11), outputSku, Quantity(portionUnit, 1, 0), true,
            listOf(LocalFoodMenuChoiceV1(choice, LocalNamesV1(choice.value, ""), emptyList(), outputSku)))), null)

    /** Supplied empty owner detail only; this is not a draft-start or native order read. */
    fun ownerDetail(): LocalFoodOrderDetailV1 = LocalFoodOrderDetailV1(
        LocalFoodOrderSummaryV1(selectorOrder, owner, displayedOrderRevision, LocalSalesActivity.RESTAURANT,
            LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "Table Q2"), 0, null, null),
        emptyList(), Money(Currency.EGP, 0), false)
}

/**
 * A supplied LocalMasterClient response seam and a controlled client journal.
 * No protected Master, authenticator, native storage, admitted RPC or genuine
 * catalog/source loan is installed. Original lookup always remains deferred.
 */
internal class FoodQuotedAddFrozenGatewayFixture(
    val installed: LocalWorkflowInstallation = FoodQuotedAddBoundedUiInputs.installation(),
    restored: StoredClientIntentV1? = null,
) {
    val endpoint: FoodQuotedAddControlledEndpoint = FoodQuotedAddControlledEndpoint()
    val journal: FoodQuotedAddControlledJournal = FoodQuotedAddControlledJournal(restored)
    val gateway: LocalEndpointWorkflowGateway = gatewayWith(installed)

    fun gatewayWith(installation: LocalWorkflowInstallation): LocalEndpointWorkflowGateway =
        LocalEndpointWorkflowGateway(endpoint, journal, LocalWorkflowCredentialSource { lease() }, installation)

    fun lease(): LocalWorkflowCredentialLease = LocalWorkflowCredentialLease(
        LocalGatewayCredentials(FoodQuotedAddBoundedUiInputs.device, OpaqueCredential(ByteArray(16)))) {
        endpoint.currentLifetime
    }

    fun assertNoStageOrDispatch() {
        assertNull(journal.entry)
        assertEquals(0, journal.stageCalls)
        assertNoMutationDispatch()
    }

    fun assertNoMutationDispatch() {
        assertEquals(0, endpoint.executeCalls)
        assertEquals(0, journal.dispatchCalls)
        assertEquals(0, journal.resultWrites)
        assertEquals(0, journal.acknowledgements)
    }
}

internal class FoodQuotedAddControlledJournal(restored: StoredClientIntentV1?) : LocalClientIntentJournal(
    LocalClientIdentity(FoodQuotedAddBoundedUiInputs.scope, FoodQuotedAddBoundedUiInputs.owner,
        FoodQuotedAddBoundedUiInputs.device)) {
    var entry: StoredClientIntentV1? = restored?.also { it.validate(owner) }
    var stageCalls: Int = 0
    var dispatchCalls: Int = 0
    var resultWrites: Int = 0
    var acknowledgements: Int = 0

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
        dispatchCalls++
        error("No qualified original absence is supplied")
    }

    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        resultWrites++
        error("No Master outcome is supplied")
    }

    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        acknowledgements++
        error("No Master outcome is supplied")
    }

    override suspend fun close(): Unit = Unit
}

internal class FoodQuotedAddControlledEndpoint : LocalMasterClient {
    override val health: MutableStateFlow<LocalMasterEndpointHealth> = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    var currentLifetime: Boolean = true
    var addHintAvailable: Boolean = true
    var profileBytes: StoredBytes = StoredBytes("{\"installed\":true}".encodeToByteArray())
    var profileFailure: ReadFailure? = null
    var profileException: Exception? = null
    var readsForbidden: Boolean = false
    var executeCalls: Int = 0
    val reads: MutableList<ReadRequest> = mutableListOf()
    val lookups: MutableList<OperationIdentity> = mutableListOf()

    fun currentView(): LocalSessionView {
        val grants = buildList {
            add(PermissionGrant(Permission(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM),
                FoodQuotedAddBoundedUiInputs.scope, GrantEffect.ALLOW))
            if (addHintAvailable) add(PermissionGrant(Permission(SecurityAction.ORDER_EDIT, PermissionVerb.PERFORM),
                FoodQuotedAddBoundedUiInputs.scope, GrantEffect.ALLOW))
        }
        return LocalSessionView(FoodQuotedAddBoundedUiInputs.owner, FoodQuotedAddBoundedUiInputs.scope,
            FoodQuotedAddBoundedUiInputs.device, FoodQuotedAddBoundedUiInputs.session, SessionGeneration(1),
            LocalLoginIdentifier(FoodQuotedAddBoundedUiInputs.owner.value),
            ActivatedLocalPolicy(FoodQuotedAddBoundedUiInputs.scope, FoodQuotedAddBoundedUiInputs.owner, Revision(1),
                grants, LocalSessionPolicy(), ThrottlePolicy()),
            MonotonicMoment(FoodQuotedAddBoundedUiInputs.session, 1_000),
            MonotonicMoment(FoodQuotedAddBoundedUiInputs.session, 2_000))
    }

    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        LocalSessionViewResult.Current(currentView())

    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        return LookupReply.Deferred(identity.operationId, DeferredReason.WRITER_NOT_QUALIFIED)
    }

    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executeCalls++
        error("No qualified writer is supplied")
    }

    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        check(!readsForbidden) { "No rediscovery or current-source read is supplied for saved continuation" }
        return when (request.query) {
            LocalFoodQuotationProfileReadCodecV1.QUERY -> {
                assertEquals("{}", request.payload.copyBytes().decodeToString())
                profileException?.let { throw it }
                profileFailure?.let { return ReadReply.Unavailable(it) }
                data(profileBytes)
            }
            LocalFoodMenuCodecV1.QUERY -> data(StoredBytes(foodQuotedAddUiJson.encodeToString(
                LocalFoodMenuPageV1.serializer(), FoodQuotedAddBoundedUiInputs.menuPage()).encodeToByteArray()))
            LocalFoodProfileV1.QUERY -> {
                val query = assertIs<LocalFoodQueryV1.Order>(foodQuotedAddUiJson.decodeFromString(
                    LocalFoodQueryV1.serializer(), request.payload.copyBytes().decodeToString()))
                assertEquals(FoodQuotedAddBoundedUiInputs.selectorOrder, query.orderId)
                assertNull(query.afterPortionId)
                data(StoredBytes(foodQuotedAddUiJson.encodeToString(LocalFoodReadResultV1.serializer(),
                    LocalFoodReadResultV1(detail = FoodQuotedAddBoundedUiInputs.ownerDetail())).encodeToByteArray()))
            }
            else -> error("No other source or business read is supplied")
        }
    }

    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = error("No authentication mutation is supplied")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult =
        error("No authentication mutation is supplied")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult =
        error("No authentication mutation is supplied")

    private fun data(bytes: StoredBytes): ReadReply.Data = ReadReply.Data(bytes,
        FoodQuotedAddBoundedUiInputs.displayedCutRevision, FoodQuotedAddBoundedUiInputs.observedAt)
}

internal val foodQuotedAddUiJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
}
