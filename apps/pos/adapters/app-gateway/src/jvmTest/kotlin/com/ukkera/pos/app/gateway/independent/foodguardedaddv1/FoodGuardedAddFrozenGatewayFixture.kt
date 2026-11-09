package com.ukkera.pos.app.gateway.independent.foodguardedaddv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.pricing.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exact savedVariants coordinates; constructing these values is not legacy Master admission. */
internal enum class FoodGuardedAddFrozenSavedProfile(
    val formVersion: Int, val operationId: OperationId, val command: CommandKey, val body: String,
) {
    GUARDED(2, FoodGuardedAddFrozenInputs.operation, LocalFoodStockCodecV2.COMMAND, FoodGuardedAddFrozenInputs.GUARDED),
    LEGACY_STOCK(1, FoodGuardedAddFrozenInputs.legacyStockOperation, LocalFoodStockCodecV2.COMMAND, FoodGuardedAddFrozenInputs.LEGACY_STOCK),
    LEGACY_FOOD(1, FoodGuardedAddFrozenInputs.legacyFoodOperation, LocalFoodProfileV1.COMMAND, FoodGuardedAddFrozenInputs.LEGACY_FOOD);

    fun stored(): StoredClientIntentV1 {
        val owner = LocalClientIdentity(FoodGuardedAddFrozenInputs.scope, FoodGuardedAddFrozenInputs.actor, FoodGuardedAddFrozenInputs.device)
        val input = FoodGuardedAddFrozenInputs.intent(formVersion, operationId)
        return StoredClientIntentV1.staged(owner, input,
            MutationRequest(operationId, command, StoredBytes(body.encodeToByteArray())),
            LocalText(FoodGuardedAddFrozenInputs.restaurantOrder.value, ""),
            listOf(LocalRecordValue(LocalText(FoodGuardedAddFrozenInputs.operation.value, ""),
                LocalText(FoodGuardedAddFrozenInputs.restaurantOrder.value, ""))))
    }
}

/**
 * Controlled L1 client/read-reply seam. No Master, authenticator, SQLite, activity
 * publication, admitted transport or qualified writer is created. DURABLE is a
 * controlled client write response, not native persistence evidence. Original
 * lookup always stays deferred, so no original result or qualified absence is supplied.
 */
internal class FoodGuardedAddFrozenGatewayFixture(
    restored: StoredClientIntentV1? = null,
    guardedCommandSupported: Boolean = true,
    installation: LocalWorkflowInstallation? = null,
) {
    val journal: FoodGuardedAddFrozenControlledJournal = FoodGuardedAddFrozenControlledJournal(restored)
    val endpoint: FoodGuardedAddFrozenControlledEndpoint = FoodGuardedAddFrozenControlledEndpoint()
    val installed: LocalWorkflowInstallation = installation ?: LocalWorkflowInstallation(
        buildSet {
            add(LocalFoodProfileV1.COMMAND)
            if (guardedCommandSupported) add(LocalFoodStockCodecV2.COMMAND)
        }, setOf(LocalFoodProfileV1.QUERY, LocalFoodMenuCodecV1.QUERY, LocalFoodSelectionPreviewCodecV1.QUERY))
    val gateway: LocalEndpointWorkflowGateway = LocalEndpointWorkflowGateway(endpoint, journal,
        LocalWorkflowCredentialSource { lease() }, installed)

    fun withHintAccess(block: (GatewayAccess) -> Unit) {
        val lease = lease()
        try { block(GatewayAccess(endpoint, lease, endpoint.currentView(), installed)) }
        finally { lease.destroy() }
    }

    fun assertNoMutationDispatch() {
        assertEquals(0, endpoint.executeCalls)
        assertEquals(0, journal.dispatchCalls)
        assertEquals(0, journal.resultWrites)
        assertEquals(0, journal.acknowledgements)
        assertTrue(endpoint.authenticationMutations.isEmpty())
    }

    private fun lease(): LocalWorkflowCredentialLease = LocalWorkflowCredentialLease(
        LocalGatewayCredentials(FoodGuardedAddFrozenInputs.device, OpaqueCredential(ByteArray(16)))) { true }
}

internal class FoodGuardedAddFrozenControlledJournal(restored: StoredClientIntentV1?) : LocalClientIntentJournal(
    LocalClientIdentity(FoodGuardedAddFrozenInputs.scope, FoodGuardedAddFrozenInputs.actor, FoodGuardedAddFrozenInputs.device)) {
    var entry: StoredClientIntentV1? = restored?.also { it.validate(owner) }
    var unavailable: Boolean = false
    var stageCalls: Int = 0
    var dispatchCalls: Int = 0
    var resultWrites: Int = 0
    var acknowledgements: Int = 0
    val reads: MutableList<String> = mutableListOf()

    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> {
        reads += "find"
        return if (unavailable) JournalRead.Unavailable else JournalRead.Value(entry?.takeIf { it.operationId == operationId })
    }
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> {
        reads += "pending"
        return if (unavailable) JournalRead.Unavailable else JournalRead.Value(listOfNotNull(entry))
    }
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
        error("No Master outcome is supplied to the controlled journal")
    }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        acknowledgements++
        error("No Master outcome is supplied to acknowledge")
    }
    override suspend fun close(): Unit = Unit
}

/** Supplied public reads plus the real pure pricing engine; current() supplies hints only. */
internal class FoodGuardedAddFrozenControlledEndpoint : LocalMasterClient {
    override val health: MutableStateFlow<LocalMasterEndpointHealth> = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    var currentActor: DomainId = FoodGuardedAddFrozenInputs.actor
    var refuseReads: Boolean = false
    var executeCalls: Int = 0
    val reads: MutableList<ReadRequest> = mutableListOf()
    val lookups: MutableList<OperationIdentity> = mutableListOf()
    val selections: MutableList<LocalFoodRequestV1.Add> = mutableListOf()
    val authenticationMutations: MutableList<String> = mutableListOf()
    val suppliedDetails: MutableList<LocalFoodOrderDetailV1> = mutableListOf()

    fun currentView(): LocalSessionView {
        val grants = listOf(SecurityAction.ORDER_READ, SecurityAction.ORDER_EDIT, SecurityAction.ORDER_CREATE,
            SecurityAction.CATALOG_READ).map { PermissionGrant(Permission(it, PermissionVerb.PERFORM),
            FoodGuardedAddFrozenInputs.scope, GrantEffect.ALLOW) }
        return LocalSessionView(currentActor, FoodGuardedAddFrozenInputs.scope, FoodGuardedAddFrozenInputs.device,
            FoodGuardedAddFrozenInputs.session, SessionGeneration(1), LocalLoginIdentifier(currentActor.value),
            ActivatedLocalPolicy(FoodGuardedAddFrozenInputs.scope, currentActor, Revision(1), grants,
                LocalSessionPolicy(), ThrottlePolicy()), MonotonicMoment(FoodGuardedAddFrozenInputs.session, 1_000),
            MonotonicMoment(FoodGuardedAddFrozenInputs.session, 2_000))
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
        check(!refuseReads) { "Saved reconstruction may not requote or select current sources" }
        return when (request.query) {
            LocalFoodProfileV1.QUERY -> {
                val query = foodGuardedAddFrozenJson.decodeFromString(LocalFoodQueryV1.serializer(), request.payload.copyBytes().decodeToString())
                check(query is LocalFoodQueryV1.Order)
                val detail = orderDetail(query.orderId)
                suppliedDetails += detail
                data(StoredBytes(foodGuardedAddFrozenJson.encodeToString(LocalFoodReadResultV1.serializer(),
                    LocalFoodReadResultV1(detail = detail)).encodeToByteArray()))
            }
            LocalFoodMenuCodecV1.QUERY -> {
                val query = foodGuardedAddFrozenJson.decodeFromString(LocalFoodMenuQueryV1.serializer(), request.payload.copyBytes().decodeToString())
                val recipe = menuRecipe(query.recipeId ?: FoodGuardedAddFrozenInputs.recipe)
                data(StoredBytes(foodGuardedAddFrozenJson.encodeToString(LocalFoodMenuPageV1.serializer(),
                    LocalFoodMenuPageV1(Revision(20), listOf(recipe), null)).encodeToByteArray()))
            }
            LocalFoodSelectionPreviewCodecV1.QUERY -> {
                val selected = LocalFoodCodecV1.decode(request.payload) as LocalFoodRequestV1.Add
                selections += selected
                val tax = TaxSnapshot(FoodGuardedAddFrozenInputs.choice, Revision(3), "EGP", PercentageRate.parse("0"))
                val resolved = selected.portions.map { portion -> ResolvedPriceLine(portion.commercialLineId, portion.portionId,
                    Revision(6), portion.quantity, UnitPrice.parse(Currency.EGP,
                        if (portion.itemId == FoodGuardedAddFrozenInputs.measuredItem) "25" else "150"), TaxInclusion.INCLUSIVE, tax) }
                val quote = OperationalPricingEngine.calculate(PricingRequest(selected.orderId, Revision(0), Currency.EGP, resolved))
                data(StoredBytes(foodGuardedAddFrozenJson.encodeToString(FoodGuardedAddFrozenPreviewWire.serializer(),
                    FoodGuardedAddFrozenPreviewWire.Available(sha256(request.payload.copyBytes()),
                        StoredPricingSnapshotCodec.encode(quote))).encodeToByteArray()))
            }
            else -> error("No other source or business read is supplied")
        }
    }

    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = authenticationMutation("signIn")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = authenticationMutation("refresh")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = authenticationMutation("logout")
    private fun authenticationMutation(name: String): Nothing {
        authenticationMutations += name
        error("No authentication mutation is supplied")
    }

    private fun data(bytes: StoredBytes): ReadReply.Data = ReadReply.Data(bytes, Revision(7),
        UtcInstant.parseCanonical("2026-10-30T22:25:37Z"))
}

internal fun menuRecipe(recipeId: DomainId = FoodGuardedAddFrozenInputs.recipe): LocalFoodMenuRecipeV1 {
    val measured = recipeId == FoodGuardedAddFrozenInputs.measuredRecipe
    check(recipeId == FoodGuardedAddFrozenInputs.recipe || measured)
    return LocalFoodMenuRecipeV1(recipeId, Revision(3),
        if (measured) FoodGuardedAddFrozenInputs.measuredItem else FoodGuardedAddFrozenInputs.item,
        if (measured) Quantity(FoodGuardedAddFrozenInputs.kgUnit, 1250000, 6) else Quantity(FoodGuardedAddFrozenInputs.pieceUnit, 1, 0),
        true, listOf(LocalFoodMenuChoiceV1(FoodGuardedAddFrozenInputs.choice,
            LocalNamesV1(FoodGuardedAddFrozenInputs.choice.value, ""), emptyList(),
            if (measured) FoodGuardedAddFrozenInputs.measuredItem else FoodGuardedAddFrozenInputs.item)))
}

internal fun orderDetail(orderId: DomainId): LocalFoodOrderDetailV1 {
    val cafe = orderId == FoodGuardedAddFrozenInputs.cafeOrder
    check(orderId == FoodGuardedAddFrozenInputs.restaurantOrder || cafe)
    val existing = if (!cafe) emptyList() else listOf(LocalFoodPortionViewV1(FoodGuardedAddFrozenInputs.existingPortion,
        FoodGuardedAddFrozenInputs.existingPortion.value, Quantity(FoodGuardedAddFrozenInputs.pieceUnit, 1, 0), emptyList(), "",
        FoodPreparationState(FoodGuardedAddFrozenInputs.existingPortion, Revision(5), FoodPreparationStatus.NOT_SENT)))
    return LocalFoodOrderDetailV1(LocalFoodOrderSummaryV1(orderId, FoodGuardedAddFrozenInputs.actor, Revision(7),
        if (cafe) LocalSalesActivity.CAFE else LocalSalesActivity.RESTAURANT,
        if (cafe) LocalFoodServiceV1(LocalSalesChannel.TAKEAWAY, "SYN-PICKUP-007")
        else LocalFoodServiceV1(LocalSalesChannel.DINE_IN, FoodGuardedAddFrozenInputs.table.value),
        existing.size, null, null), existing, Money(Currency.EGP, if (cafe) 10000 else 0), false)
}

private val foodGuardedAddFrozenJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
}

/** Existing public AVAILABLE reply format only; the constructor confers no authority. */
@Serializable
private sealed interface FoodGuardedAddFrozenPreviewWire {
    @Serializable @SerialName("AVAILABLE")
    data class Available(val requestDigest: PayloadDigest, val pricing: String) : FoodGuardedAddFrozenPreviewWire
}
