package com.ukkera.pos.app.gateway.independent.foodemptykitchenstatusv1

import com.ukkera.pos.app.gateway.FoodReadCut
import com.ukkera.pos.app.gateway.GatewayAccess
import com.ukkera.pos.app.gateway.GatewayFailure
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialLease
import com.ukkera.pos.app.gateway.LocalWorkflowInstallation
import com.ukkera.pos.app.gateway.foodRead
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenObservationV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenResultV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenStatusV1
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** FEKS-L1-012 only: controlled public responses, not protected Master or authentic history evidence. */
class FoodEmptyKitchenStatusFrozenConsumerTest {
    @Test
    fun feksL1012CountZeroVisibleNotSentIsRefusedAtTheActualConsumer() = runTest {
        val food = suppliedFood(NONEMPTY_ORDER, Revision(13), 2, listOf(suppliedP01()), 33_000)
        val payload = encodedResult(food, observation(NONEMPTY_ORDER, Revision(13), 0,
            LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK))

        // Construction/decoding must succeed first: only the actual consumer sees this contradiction.
        val decoded = LocalFoodCheckKitchenCodecV1.result(payload)
        assertEquals(NONEMPTY_ORDER, decoded.food.detail?.summary?.orderId)
        assertEquals(Revision(13), decoded.food.detail?.summary?.revision)
        assertEquals(listOf(P01), decoded.food.detail?.portions?.map { it.portionId })
        assertEquals(FoodPreparationStatus.NOT_SENT, decoded.food.detail?.portions?.single()?.preparation?.status)
        assertEquals(0, decoded.kitchen?.unsentPortionCount)

        val query = LocalFoodQueryV1.Order(NONEMPTY_ORDER, limit = 1)
        withControlledReply(query, payload) { access ->
            assertFailsWith<GatewayFailure> { foodRead(access, query) }
        }
    }

    @Test
    fun feksL1012AnEmptyPagePreservesTheValidHiddenWorkControl() = runTest {
        val payload = encodedResult(suppliedFood(NONEMPTY_ORDER, Revision(13), 2, emptyList(), 33_000),
            observation(NONEMPTY_ORDER, Revision(13), 2, LocalFoodCheckKitchenStatusV1.UNAVAILABLE))
        val query = LocalFoodQueryV1.Order(NONEMPTY_ORDER, limit = 1)
        withControlledReply(query, payload) { access ->
            assertCut(foodRead(access, query), NONEMPTY_ORDER, Revision(13), 2, 2,
                LocalFoodCheckKitchenStatusV1.UNAVAILABLE)
        }
    }

    @Test
    fun feksL1012SuppliedActualEmptyCutPreservesTheExactFields() = runTest {
        val payload = encodedResult(suppliedFood(EMPTY_ORDER, Revision(7), 0, emptyList(), 0),
            observation(EMPTY_ORDER, Revision(7), 0, LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK))
        for (limit in listOf(1, 8)) {
            val query = LocalFoodQueryV1.Order(EMPTY_ORDER, limit = limit)
            withControlledReply(query, payload) { access ->
                assertCut(foodRead(access, query), EMPTY_ORDER, Revision(7), 0, 0,
                    LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK)
            }
        }
    }

    @Test
    fun feksL1012NonzeroCountAgainstSuppliedCompleteEmptyFactIsRefused() = runTest {
        val food = suppliedFood(EMPTY_ORDER, Revision(7), 0, emptyList(), 0)
        val kitchen = observation(EMPTY_ORDER, Revision(7), 1, LocalFoodCheckKitchenStatusV1.UNAVAILABLE)
        // The two constituents are valid public values; their inconsistent received envelope is not.
        val payload = StoredBytes(JsonObject(linkedMapOf(
            "food" to responseJson.encodeToJsonElement(LocalFoodReadResultV1.serializer(), food),
            "kitchen" to responseJson.encodeToJsonElement(LocalFoodCheckKitchenObservationV1.serializer(), kitchen),
        )).toString().encodeToByteArray())
        val query = LocalFoodQueryV1.Order(EMPTY_ORDER, limit = 1)
        withControlledReply(query, payload) { access ->
            // Refusal is at the consumer's real decoder/constructor boundary, not hidden-page inference.
            assertFailsWith<IllegalArgumentException> { foodRead(access, query) }
        }
    }
}

private val EMPTY_ORDER = DomainId("f3100000-0000-4000-8000-000000001001")
private val NONEMPTY_ORDER = DomainId("f3100000-0000-4000-8000-000000002001")
private val OWNER = DomainId("f3100000-0000-4000-8000-000000000011")
private val DEVICE = DomainId("f3100000-0000-4000-8000-000000000022")
private val P01 = DomainId("f3100000-0000-4000-8000-000000000101")
private val SERVING_UNIT = DomainId("f3100000-0000-4000-8000-000000000209")
private val SCOPE = BranchScope(DomainId("f3100000-0000-4000-8000-000000000001"),
    DomainId("f3100000-0000-4000-8000-000000000002"), DomainId("f3100000-0000-4000-8000-000000000003"))

// Separate inert constructor coordinates: neither session issuance nor transport/clock evidence.
private val CONTROL_SESSION = DomainId("7f310000-0000-4000-8000-000000000001")
private val CONTROL_CONNECTION = DomainId("7f310000-0000-4000-8000-000000000002")
private val CONTROL_BOOT = DomainId("7f310000-0000-4000-8000-000000000003")
private val responseJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
}

private fun suppliedP01(): LocalFoodPortionViewV1 = LocalFoodPortionViewV1(
    portionId = P01, description = "Burger", quantity = Quantity(SERVING_UNIT, 2, 0),
    modifierIds = emptyList(), itemNote = "",
    preparation = FoodPreparationState(P01, Revision(0), FoodPreparationStatus.NOT_SENT),
    lineId = null, seatNote = "",
)

private fun suppliedFood(orderId: DomainId, revision: Revision, wholePortionCount: Int,
    visible: List<LocalFoodPortionViewV1>, originalMinor: Long): LocalFoodReadResultV1 =
    LocalFoodReadResultV1(detail = LocalFoodOrderDetailV1(
        summary = LocalFoodOrderSummaryV1(orderId, OWNER, revision, LocalSalesActivity.RESTAURANT,
            LocalFoodServiceV1(LocalSalesChannel.DINE_IN, if (orderId == EMPTY_ORDER) "EMPTY-1" else "NONEMPTY2-CONTROL"),
            wholePortionCount, handoffId = null, intendedRecipientId = null),
        portions = visible, originalQuotedTotal = Money(Currency.EGP, originalMinor),
        chargeResolutionRequired = false,
    ))

private fun observation(orderId: DomainId, revision: Revision, count: Int,
    status: LocalFoodCheckKitchenStatusV1): LocalFoodCheckKitchenObservationV1 =
    LocalFoodCheckKitchenObservationV1(orderId, revision, count, status)

private fun encodedResult(food: LocalFoodReadResultV1, kitchen: LocalFoodCheckKitchenObservationV1): StoredBytes =
    StoredBytes(responseJson.encodeToString(LocalFoodCheckKitchenResultV1.serializer(),
        LocalFoodCheckKitchenResultV1(food, kitchen)).encodeToByteArray())

private fun assertCut(cut: FoodReadCut, orderId: DomainId, revision: Revision, wholePortionCount: Int,
    unsentCount: Int, status: LocalFoodCheckKitchenStatusV1) {
    val detail = assertNotNull(cut.result.detail)
    val kitchen = assertNotNull(cut.kitchen)
    assertEquals(orderId, detail.summary.orderId)
    assertEquals(OWNER, detail.summary.ownerId)
    assertEquals(revision, detail.summary.revision)
    assertEquals(wholePortionCount, detail.summary.portionCount)
    assertEquals(emptyList(), detail.portions)
    assertEquals(orderId, kitchen.orderId)
    assertEquals(revision, kitchen.orderStorageRevision)
    assertEquals(unsentCount, kitchen.unsentPortionCount)
    assertEquals(status, kitchen.status)
    assertNull(cut.result.unavailable)
    assertNull(cut.result.nextAfter)
}

private suspend fun <T> withControlledReply(query: LocalFoodQueryV1.Order, payload: StoredBytes,
    consume: suspend (GatewayAccess) -> T): T {
    val view = LocalSessionView(OWNER, SCOPE, DEVICE, CONTROL_SESSION, SessionGeneration(1),
        LocalLoginIdentifier(OWNER.value),
        ActivatedLocalPolicy(SCOPE, OWNER, Revision(1), emptyList(), LocalSessionPolicy(), ThrottlePolicy()),
        MonotonicMoment(CONTROL_BOOT, 1_000), MonotonicMoment(CONTROL_BOOT, 2_000))
    var locallyOwned = true
    // Owned inert bytes meet the constructor bound only; no authentication operation consumes them.
    val lease = LocalWorkflowCredentialLease(LocalGatewayCredentials(CONTROL_CONNECTION,
        OpaqueCredential(ByteArray(16)))) { locallyOwned }
    try {
        return consume(GatewayAccess(ControlledReplyPort(LocalFoodCheckKitchenCodecV1.read(query), payload),
            lease, view, LocalWorkflowInstallation(emptySet(), setOf(LocalFoodCheckKitchenCodecV1.QUERY))))
    } finally {
        locallyOwned = false
        lease.destroy()
    }
}

/** Only a fixed response port is supplied; all authentication, health and business ports fail on use. */
private class ControlledReplyPort(private val expected: ReadRequest, private val payload: StoredBytes) : LocalMasterClient {
    override val health: StateFlow<LocalMasterEndpointHealth> get() = forbidden("health")

    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        assertEquals(expected.query, request.query)
        assertContentEquals(expected.payload.copyBytes(), request.payload.copyBytes())
        // Envelope metadata is constructor scaffolding, not an independently observed durable cut.
        return ReadReply.Data(payload, Revision(1), UtcInstant.parseCanonical("2026-10-31T00:00:00Z"))
    }

    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = forbidden("current")
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply = forbidden("execute")
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply = forbidden("lookup")
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = forbidden("signIn")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = forbidden("refresh")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = forbidden("logout")

    private fun forbidden(port: String): Nothing = throw AssertionError("No qualified $port boundary is supplied by this result-consumer control")
}
