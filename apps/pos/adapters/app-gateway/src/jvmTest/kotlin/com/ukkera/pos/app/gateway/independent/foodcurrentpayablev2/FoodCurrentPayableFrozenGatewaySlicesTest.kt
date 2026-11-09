package com.ukkera.pos.app.gateway.independent.foodcurrentpayablev2

import com.ukkera.pos.app.gateway.GatewayAccess
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialLease
import com.ukkera.pos.app.gateway.LocalWorkflowInstallation
import com.ukkera.pos.app.gateway.foodRead
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenResultV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenResultV2
import com.ukkera.pos.sales.store.LocalFoodOrderDetailV1
import com.ukkera.pos.sales.store.LocalFoodOrderSummaryV1
import com.ukkera.pos.sales.store.LocalFoodPortionViewV1
import com.ukkera.pos.sales.store.LocalFoodQueryV1
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/**
 * Scripted client-boundary subassertions of FCPV2-004/005/009. ReadReply.Data here is test
 * input: it establishes neither protected serving nor authentication, original custody or RPC.
 */
class FoodCurrentPayableFrozenGatewaySlicesTest {
    private val v2 = ReadKey("sales.food.check-kitchen.read", 2)
    private val v1 = ReadKey("sales.food.check-kitchen.read", 1)
    private val legacy = ReadKey("sales.food.read", 1)

    @Test
    fun fcpv2004_advertisedVersionTwoHasPrecedenceAndUnchangedSelector() = runTest {
        val h = Harness(setOf(v1, v2, legacy))
        try {
            h.endpoint.respond = { request ->
                if (request.query != v2) error("Unexpected lower-version request")
                h.reply(h.v2Frame(h.food()))
            }
            val result = foodRead(h.access, LocalFoodQueryV1.Order(h.order, limit = 1))
            assertEquals(Money(Currency.EGP, 39_000), result.currentUnpaidPayable, "FCPV2-004(a)")
            assertEquals(Money(Currency.EGP, 50_000), result.result.detail?.originalQuotedTotal)
            assertEquals(h.order, result.result.detail?.summary?.orderId)
            assertEquals(Revision(3), result.result.detail?.summary?.revision)
            assertEquals(listOf(v2), h.endpoint.requests.map { it.query })
            assertEquals("{\"type\":\"ORDER\",\"orderId\":\"${h.order.value}\",\"afterPortionId\":null,\"limit\":1}",
                h.endpoint.requests.single().payload.copyBytes().decodeToString())
        } finally { h.lease.destroy() }
    }

    @Test
    fun fcpv2004_absentVersionTwoLeavesHistoricalResultsWithoutCurrentEstimate() = runTest {
        for (oldKey in listOf(v1, legacy)) {
            val h = Harness(setOf(oldKey))
            try {
                h.endpoint.respond = { request ->
                    if (request.query != oldKey) error("Unexpected protocol request")
                    h.reply(h.oldFrame(h.food(), oldKey == v1))
                }
                val result = foodRead(h.access, LocalFoodQueryV1.Order(h.order, limit = 1))
                assertNull(result.currentUnpaidPayable, "FCPV2-004(b)/(c): old history is not current")
                assertEquals(Money(Currency.EGP, 50_000), result.result.detail?.originalQuotedTotal)
                assertEquals(listOf(oldKey), h.endpoint.requests.map { it.query })
            } finally { h.lease.destroy() }
        }
        // This binds an admitted legacy purpose when the diagnostic key is absent; it does
        // not prescribe the real installation's behavior when every FOOD read is absent.
    }

    @Test
    fun fcpv2005_failedAdvertisedVersionTwoNeverSelectsOfferedOldFallback() = runTest {
        for (failure in listOf("transport", "refusal", "missing-current")) {
            val h = Harness(setOf(v1, v2, legacy))
            try {
                val oldAccess = GatewayAccess(h.endpoint, h.lease, h.view,
                    LocalWorkflowInstallation(emptySet(), setOf(v1, legacy)))
                h.endpoint.respond = { h.reply(h.oldFrame(h.food(originalMinor = 22_000, revision = Revision(1)), true)) }
                val previous = foodRead(oldAccess, LocalFoodQueryV1.Order(h.order, limit = 1))
                assertEquals(Money(Currency.EGP, 22_000), previous.result.detail?.originalQuotedTotal)
                assertNull(previous.currentUnpaidPayable)
                val beforeFailure = h.endpoint.requests.size
                h.endpoint.respond = { request ->
                    if (request.query != v2) h.reply(h.oldFrame(h.food(), request.query == v1))
                    else when (failure) {
                        "transport" -> error("Synthetic interrupted transport")
                        "refusal" -> ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
                        else -> {
                            val complete = Json.parseToJsonElement(h.v2Frame(h.food())).jsonObject
                            h.reply(JsonObject(complete - "currentUnpaidPayable").toString())
                        }
                    }
                }
                assertFails("FCPV2-005: $failure refuses instead of a downgraded success") {
                    foodRead(h.access, LocalFoodQueryV1.Order(h.order, limit = 1))
                }
                assertEquals(listOf(v2), h.endpoint.requests.drop(beforeFailure).map { it.query },
                    "FCPV2-005: no lower-version request after failure")
                assertEquals(Money(Currency.EGP, 22_000), previous.result.detail?.originalQuotedTotal,
                    "FCPV2-005: prior history remains its own observation")
                assertNull(previous.currentUnpaidPayable)
            } finally { h.lease.destroy() }
        }
        // No retry count or genuine protected-refusal/transport fixture is inferred from this script.
    }

    @Test
    fun fcpv2009_eachBoundedVisiblePageRetainsWholeOrderCurrentAndRevision() = runTest {
        val h = Harness(setOf(v2))
        try {
            val pages = listOf(Triple(h.p1, null, h.p1), Triple(h.p3, h.p2, h.p3), Triple(h.p4, h.p3, null))
            for ((visible, after, next) in pages) {
                h.endpoint.respond = { h.reply(h.v2Frame(h.food(visible = visible, next = next))) }
                val result = foodRead(h.access, LocalFoodQueryV1.Order(h.order, after, 1))
                assertEquals(Money(Currency.EGP, 39_000), result.currentUnpaidPayable, "FCPV2-009: complete amount")
                assertEquals(Money(Currency.EGP, 50_000), result.result.detail?.originalQuotedTotal)
                assertEquals(h.order, result.result.detail?.summary?.orderId)
                assertEquals(Revision(3), result.result.detail?.summary?.revision)
                assertEquals(listOf(visible), result.result.detail?.portions?.map { it.portionId })
            }
            assertEquals(listOf(v2, v2, v2), h.endpoint.requests.map { it.query })
        } finally { h.lease.destroy() }
        // Complete source/history closure and actual server pagination remain production obligations.
    }

    private class Harness(keys: Set<ReadKey>) {
        private fun id(number: Int): DomainId = DomainId("c2820000-0000-4000-8000-${number.toString().padStart(12, '0')}")
        val order = id(1)
        private val actor = id(2)
        val p1 = id(11)
        val p2 = id(12)
        val p3 = id(13)
        val p4 = id(14)
        private val scope = BranchScope(id(21), id(22), id(23))
        private val device = id(24)
        private val json = Json { encodeDefaults = true; explicitNulls = true }
        val view = LocalSessionView(actor, scope, device, id(25), SessionGeneration(1),
            LocalLoginIdentifier("synthetic-owner"), ActivatedLocalPolicy(scope, actor, Revision(1), emptyList(),
                LocalSessionPolicy(), ThrottlePolicy()), MonotonicMoment(id(26), 10_000), MonotonicMoment(id(26), 20_000))
        val lease = LocalWorkflowCredentialLease(LocalGatewayCredentials(id(27), OpaqueCredential(ByteArray(16)))) { true }
        val endpoint = ScriptedEndpoint()
        val access = GatewayAccess(endpoint, lease, view, LocalWorkflowInstallation(emptySet(), keys))

        fun food(originalMinor: Long = 50_000, revision: Revision = Revision(3),
            visible: DomainId = p1, next: DomainId? = p1): LocalFoodReadResultV1 {
            val summary = LocalFoodOrderSummaryV1(order, actor, revision, LocalSalesActivity.RESTAURANT,
                LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "Synthetic check", ""),
                if (originalMinor == 22_000L) 2 else 4, null, null)
            return LocalFoodReadResultV1(detail = LocalFoodOrderDetailV1(summary,
                listOf(LocalFoodPortionViewV1(visible, "Synthetic frozen serving", Quantity(id(28), 1, 0), emptyList(), "",
                    FoodPreparationState(visible, Revision(0), FoodPreparationStatus.NOT_SENT))),
                Money(Currency.EGP, originalMinor), false), nextAfter = next)
        }

        fun v2Frame(food: LocalFoodReadResultV1): String = json.encodeToString(LocalFoodCheckKitchenResultV2.serializer(),
            LocalFoodCheckKitchenResultV2(food, currentUnpaidPayable = Money(Currency.EGP, 39_000)))

        fun oldFrame(food: LocalFoodReadResultV1, diagnostic: Boolean): String = if (diagnostic)
            json.encodeToString(LocalFoodCheckKitchenResultV1.serializer(), LocalFoodCheckKitchenResultV1(food))
        else json.encodeToString(LocalFoodReadResultV1.serializer(), food)

        fun reply(frame: String): ReadReply.Data = ReadReply.Data(StoredBytes(frame.encodeToByteArray()),
            Revision(30), UtcInstant.parseCanonical("2026-10-31T10:00:00Z"))
    }

    private class ScriptedEndpoint : LocalMasterClient {
        override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
        val requests = mutableListOf<ReadRequest>()
        var respond: suspend (ReadRequest) -> ReadReply = { error("Missing scripted read") }
        override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
            requests += request
            return respond(request)
        }
        override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply =
            error("Read-only test must not mutate")
        override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply =
            error("Read-only test must not recover operations")
        override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
            explicitDeviceMove: Boolean): LocalAuthenticationResult = error("No authentication fixture")
        override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("No authentication fixture")
        override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = error("No authentication fixture")
        override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("No authentication fixture")
    }
}
