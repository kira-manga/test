package com.ukkera.pos.app.gateway.independent.foodcurrentpayablev3

import com.ukkera.pos.app.gateway.GatewayAccess
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialLease
import com.ukkera.pos.app.gateway.LocalWorkflowInstallation
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
import com.ukkera.pos.sales.FoodPreparation
import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.FoodStationStatus
import com.ukkera.pos.sales.FoodStationTask
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV2
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV3
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenObservationV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenResultV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenResultV2
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenResultV3
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenStatusV1
import com.ukkera.pos.sales.store.LocalFoodOrderDetailV1
import com.ukkera.pos.sales.store.LocalFoodOrderSummaryV1
import com.ukkera.pos.sales.store.LocalFoodPortionViewV1
import com.ukkera.pos.sales.store.LocalFoodReadResultV1
import com.ukkera.pos.sales.store.LocalFoodServiceV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

/**
 * Controlled client inputs only. Credentials/view/lease are presentation fixtures;
 * the endpoint supplies scripted DATA and implements no accepting authentication or native source service.
 */
internal class FoodCurrentPayableV3GatewayInputs(keys: Set<ReadKey>) {
    fun source(number: Int) = DomainId("75000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    fun portion(number: Int) = DomainId("40000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    fun fullPortion(number: Int) = DomainId("63000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    val order = DomainId("20000000-0000-4000-8000-000000000001")
    val emptyOrder = DomainId("20000000-0000-4000-8000-000000000003")
    val fullOrder = DomainId("20000000-0000-4000-8000-000000000005")
    val owner = DomainId("10000000-0000-4000-8000-000000000004")
    val otherOwner = DomainId("10000000-0000-4000-8000-000000000005")
    private val scope = BranchScope(DomainId("10000000-0000-4000-8000-000000000001"),
        DomainId("10000000-0000-4000-8000-000000000002"), DomainId("10000000-0000-4000-8000-000000000003"))
    private val device = DomainId("10000000-0000-4000-8000-000000000007")
    val at = UtcInstant.parseCanonical("2026-11-06T09:00:00Z")
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    var clientLeaseCurrent = true
    val view = LocalSessionView(owner, scope, device, source(1), SessionGeneration(1), LocalLoginIdentifier("synthetic-owner"),
        ActivatedLocalPolicy(scope, owner, Revision(1), emptyList(), LocalSessionPolicy(), ThrottlePolicy()),
        MonotonicMoment(source(2), 10_000), MonotonicMoment(source(2), 20_000))
    val lease = LocalWorkflowCredentialLease(LocalGatewayCredentials(source(3), OpaqueCredential(ByteArray(16)))) { clientLeaseCurrent }
    val endpoint = ControlledReadEndpoint()
    val access = GatewayAccess(endpoint, lease, view, LocalWorkflowInstallation(emptySet(), keys))
    fun accessFor(installed: LocalWorkflowInstallation) = GatewayAccess(endpoint, lease, view, installed)
    fun egp(minor: Long) = Money(Currency.EGP, minor)

    fun state(id: DomainId, status: FoodPreparationStatus, served: Boolean = false): FoodPreparationState {
        val number = id.value.takeLast(3).toInt()
        var current = FoodPreparationState(id, Revision(0), FoodPreparationStatus.NOT_SENT)
        if (status == FoodPreparationStatus.NOT_SENT) return current
        if (status == FoodPreparationStatus.CANCELLED_BEFORE_START)
            return FoodPreparation.requestCancellation(current, current.revision, source(4), at)
        current = FoodPreparation.sent(current, current.revision, source(100 + number), source(200 + number),
            listOf(FoodStationTask(source(300 + number), source(400), FoodStationDeliveryMode.DISPLAY, FoodStationStatus.QUEUED)), at)
        if (status == FoodPreparationStatus.QUEUED) return current
        current = FoodPreparation.start(current, current.revision, current.tasks.single().stationId, at)
        if (status == FoodPreparationStatus.PREPARING) return current
        current = FoodPreparation.ready(current, current.revision, current.tasks.single().stationId, at)
        return if (served) FoodPreparation.serve(current, current.revision, at) else current
    }

    fun mix(visible: List<Int> = listOf(1), next: DomainId? = portion(1), prepared: Boolean = true,
        allSent: Boolean = false, cancelA: Boolean = false, revision: Revision = Revision(41),
        returnedOrder: DomainId = order, returnedOwner: DomainId = owner): LocalFoodReadResultV1 {
        val summary = LocalFoodOrderSummaryV1(returnedOrder, returnedOwner, revision, LocalSalesActivity.RESTAURANT,
            LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "Controlled frozen MIX", ""), 6, null, null)
        val portions = visible.map { number ->
            val status = when {
                number == 6 || number == 1 && cancelA -> FoodPreparationStatus.CANCELLED_BEFORE_START
                !prepared -> FoodPreparationStatus.NOT_SENT
                number == 1 -> if (allSent) FoodPreparationStatus.QUEUED else FoodPreparationStatus.NOT_SENT
                number == 2 -> FoodPreparationStatus.QUEUED
                number == 3 -> FoodPreparationStatus.PREPARING
                else -> FoodPreparationStatus.READY
            }
            view(portion(number), state(portion(number), status, served = number == 5 && prepared))
        }
        return LocalFoodReadResultV1(detail = LocalFoodOrderDetailV1(summary, portions, egp(30_803), false), nextAfter = next)
    }

    fun empty(): LocalFoodReadResultV1 = LocalFoodReadResultV1(detail = LocalFoodOrderDetailV1(
        LocalFoodOrderSummaryV1(emptyOrder, owner, Revision(1), LocalSalesActivity.RESTAURANT,
            LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "Controlled EMPTY", ""), 0, null, null), emptyList(), egp(0), false))

    fun full(visible: List<Int>, next: DomainId?): LocalFoodReadResultV1 = LocalFoodReadResultV1(detail = LocalFoodOrderDetailV1(
        LocalFoodOrderSummaryV1(fullOrder, owner, Revision(41), LocalSalesActivity.RESTAURANT,
            LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "Controlled FULL32", ""), 32, null, null), visible.map { number ->
            val status = when ((number - 1) % 4) {
                0 -> FoodPreparationStatus.NOT_SENT
                1 -> FoodPreparationStatus.QUEUED
                2 -> FoodPreparationStatus.PREPARING
                else -> FoodPreparationStatus.READY
            }
            view(fullPortion(number), state(fullPortion(number), status, served = number % 8 == 0))
        }, egp(3_648), false), nextAfter = next)

    private fun view(id: DomainId, preparation: FoodPreparationState) = LocalFoodPortionViewV1(id,
        "Controlled frozen serving", Quantity(source(5), 1, 0), emptyList(), "", preparation)

    fun frame(key: ReadKey, food: LocalFoodReadResultV1 = mix(), current: Money? = egp(19_403),
        unsentCount: Int = 1): String {
        val summary = checkNotNull(food.detail).summary
        val kitchen = LocalFoodCheckKitchenObservationV1(summary.orderId, summary.revision, unsentCount,
            if (unsentCount == 0) LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK else LocalFoodCheckKitchenStatusV1.UNAVAILABLE)
        return when (key) {
            LocalFoodCheckKitchenCodecV3.QUERY -> json.encodeToString(LocalFoodCheckKitchenResultV3.serializer(),
                LocalFoodCheckKitchenResultV3(food, kitchen, currentUnpaidPayable = current))
            LocalFoodCheckKitchenCodecV2.QUERY -> json.encodeToString(LocalFoodCheckKitchenResultV2.serializer(),
                LocalFoodCheckKitchenResultV2(food, kitchen, currentUnpaidPayable = current))
            LocalFoodCheckKitchenCodecV1.QUERY -> json.encodeToString(LocalFoodCheckKitchenResultV1.serializer(),
                LocalFoodCheckKitchenResultV1(food, kitchen))
            else -> json.encodeToString(LocalFoodReadResultV1.serializer(), food)
        }
    }
    fun reply(text: String) = ReadReply.Data(StoredBytes(text.encodeToByteArray()), Revision(90), at)
}

internal class ControlledReadEndpoint : LocalMasterClient {
    override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    val requests = mutableListOf<ReadRequest>()
    var mutationCalls = 0
    var lookupCalls = 0
    var respond: suspend (ReadRequest) -> ReadReply = { error("No controlled read input") }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        requests += request
        return respond(request)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        mutationCalls++
        error("Read presentation must not submit a mutation")
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookupCalls++
        error("Read presentation must not recover a mutation")
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = error("No accepting authentication fixture")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("No accepting authentication fixture")
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = error("No accepting authentication fixture")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("No accepting authentication fixture")
}
