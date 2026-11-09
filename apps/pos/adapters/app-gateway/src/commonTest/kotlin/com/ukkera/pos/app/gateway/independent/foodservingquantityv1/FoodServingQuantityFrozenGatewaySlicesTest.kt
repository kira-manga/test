package com.ukkera.pos.app.gateway.independent.foodservingquantityv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.LocalFoodMenuCodecV1
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Common-code refusal/task-mapping slices of food-serving-quantity-v1 FQ03/FQ05.
 * Current policy views, task values and the client journal are controlled seams;
 * they establish no actual Master authority, durable task, stock issue, RPC or restart.
 * Positive scripted preparation/readback bindings are host-only tests, kept separately.
 */
public class FoodServingQuantityFrozenGatewaySlicesTest {
    @Test
    public fun fq03RevokedEditCannotStageQuantityTwoOrReadProtectedSources(): Unit = runTest {
        val fixture = FoodQuantityGatewayFixture()
        fixture.endpoint.view = foodQuantityCurrentView(allowEdit = false, policyRevision = 12)
        val result = assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(foodQuantityIntent()))
        assertEquals(LocalServiceProblem.DENIED, result.problem)
        assertNull(fixture.journal.entry)
        assertEquals(0, fixture.journal.stageCalls)
        assertTrue(fixture.endpoint.reads.isEmpty())
        fixture.assertNoBusinessDispatch()
    }

    @Test
    public fun fq03OtherEmployeeCompanyBranchOrDeviceCannotAcquireTheOriginalInput(): Unit = runTest {
        val original = foodQuantityIntent()
        listOf(
            foodQuantityCurrentView(actor = FOOD_QUANTITY_OTHER_ACTOR),
            foodQuantityCurrentView(scope = FOOD_QUANTITY_SCOPE.copy(companyId = FOOD_QUANTITY_OTHER_COMPANY)),
            foodQuantityCurrentView(scope = FOOD_QUANTITY_SCOPE.copy(branchId = FOOD_QUANTITY_OTHER_BRANCH)),
            foodQuantityCurrentView(device = FOOD_QUANTITY_OTHER_DEVICE),
        ).forEach { changed ->
            val fixture = FoodQuantityGatewayFixture()
            fixture.endpoint.view = changed
            assertEquals(original.operationId,
                assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(original)).operationId)
            assertNull(fixture.journal.entry)
            assertEquals(0, fixture.journal.stageCalls)
            assertTrue(fixture.endpoint.reads.isEmpty())
            fixture.assertNoBusinessDispatch()
        }
    }

    @Test
    public fun fq05StationTaskMappingRetainsEachOriginalQuantityAndBothExactLineNotes(): Unit {
        val originalTasks = listOf(FOOD_QUANTITY_LINE, FOOD_QUANTITY_SECOND).mapIndexed { index, portion ->
            LocalFoodStationTaskViewV1(FOOD_QUANTITY_ORDER, Revision(7), portion, Revision(1),
                DomainId("f7000000-0000-4000-8000-00000000000${index + 1}"), FOOD_QUANTITY_STATION,
                "Burger / برجر", Quantity(FOOD_QUANTITY_UNIT, 1, 0), FOOD_QUANTITY_ITEM_NOTE, "T-02",
                FoodStationStatus.QUEUED, FoodPreparationStatus.QUEUED, FoodStationDeliveryMode.DISPLAY,
                seatNote = FOOD_QUANTITY_SEAT_NOTE)
        }
        val displayed = originalTasks.map(::foodTask)
        assertEquals(2, displayed.size)
        assertEquals(2, displayed.map { it.taskId }.distinct().size)
        assertEquals(listOf(FOOD_QUANTITY_LINE, FOOD_QUANTITY_SECOND), displayed.map { it.portionId })
        displayed.forEach { task ->
            assertEquals(FOOD_QUANTITY_ORDER, task.orderId)
            assertEquals(FOOD_QUANTITY_STATION, task.stationId)
            assertEquals(Quantity(FOOD_QUANTITY_UNIT, 1, 0), task.quantity)
            assertEquals(FOOD_QUANTITY_ITEM_NOTE, task.itemNote)
            assertEquals(FOOD_QUANTITY_SEAT_NOTE, task.seatNote)
            assertEquals("T-02", task.serviceReference)
            assertEquals(LocalFoodTaskState.QUEUED, task.taskState)
            assertEquals(LocalFoodPreparation.QUEUED, task.preparation)
            assertEquals(LocalFoodDeliveryMode.DISPLAY, task.deliveryMode)
        }
        // Two supplied projections do not prove two durable tasks or an ingredient issue.
    }
}

internal val FOOD_QUANTITY_SCOPE: BranchScope = BranchScope(
    DomainId("a0000000-0000-4000-8000-000000000001"),
    DomainId("a0000000-0000-4000-8000-000000000002"),
    DomainId("a0000000-0000-4000-8000-000000000004"))
internal val FOOD_QUANTITY_OWNER: DomainId = DomainId("b0000000-0000-4000-8000-000000000001")
internal val FOOD_QUANTITY_OTHER_ACTOR: DomainId = DomainId("b0000000-0000-4000-8000-000000000002")
internal val FOOD_QUANTITY_DEVICE: DomainId = DomainId("d0000000-0000-4000-8000-000000000001")
internal val FOOD_QUANTITY_OTHER_DEVICE: DomainId = DomainId("d0000000-0000-4000-8000-000000000003")
internal val FOOD_QUANTITY_OTHER_BRANCH: DomainId = DomainId("a0000000-0000-4000-8000-000000000005")
internal val FOOD_QUANTITY_OTHER_COMPANY: DomainId = DomainId("a0000000-0000-4000-8000-000000000003")
internal val FOOD_QUANTITY_ORDER: DomainId = DomainId("f0000000-0000-4000-8000-000000000001")
internal val FOOD_QUANTITY_LINE: DomainId = DomainId("f3000000-0000-4000-8000-000000000001")
// Fixed supplemental native preparation coordinate; its ordering exercises canonical staging.
internal val FOOD_QUANTITY_SECOND: DomainId = DomainId("f3000000-0000-4000-8000-000000000000")
internal val FOOD_QUANTITY_OPERATION: OperationId = OperationId("f4000000-0000-4000-8000-000000000001")
internal val FOOD_QUANTITY_PRODUCT: DomainId = DomainId("f1000000-0000-4000-8000-000000000001")
internal val FOOD_QUANTITY_VARIANT: DomainId = DomainId("f1000000-0000-4000-8000-000000000002")
internal val FOOD_QUANTITY_CHEESE: DomainId = DomainId("f1000000-0000-4000-8000-000000000003")
internal val FOOD_QUANTITY_STATION: DomainId = DomainId("f2000000-0000-4000-8000-000000000005")
// Declared native binding for the frozen recipe's one-burger output UOM.
internal val FOOD_QUANTITY_UNIT: DomainId = DomainId("f6000000-0000-4000-8000-000000000001")
internal const val FOOD_QUANTITY_ITEM_NOTE: String = "no salt / بدون ملح"
internal const val FOOD_QUANTITY_SEAT_NOTE: String = "Seat 02 / المقعد 02"
private val FOOD_QUANTITY_CLOCK: DomainId = DomainId("f9000000-0000-4000-8000-000000000001")

internal fun foodQuantityNames(current: Boolean): LocalText = if (current) LocalText("Burger today", "برجر اليوم")
    else LocalText("Burger", "برجر")

/** Public client input only; product/variant/cheese keep the frozen native-equivalent bindings. */
internal fun foodQuantityIntent(current: Boolean = false): LocalCommandIntent {
    fun input(field: LocalField, value: LocalScalarValue): LocalScalarInput = LocalScalarInput(field, value)
    val names = foodQuantityNames(current)
    return LocalCommandIntent(FOOD_QUANTITY_OPERATION, LocalAction.FOOD_ADD, 1, null, listOf(
        input(LocalField.ORDER_ID, LocalScalarValue.Identifier(FOOD_QUANTITY_ORDER)),
        input(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, LocalScalarValue.Integer(7)),
        input(LocalField.EXPECTED_CATALOG_REVISION, LocalScalarValue.Integer(if (current) 6L else 5L)),
    ), LocalField.LINES, listOf(LocalInputLine(FOOD_QUANTITY_LINE, listOf(
        input(LocalField.RECIPE_ID, LocalScalarValue.Identifier(FOOD_QUANTITY_VARIANT)),
        input(LocalField.EXPECTED_FOOD_RECIPE_REVISION, LocalScalarValue.Integer(if (current) 2L else 1L)),
        input(LocalField.SKU_ID, LocalScalarValue.Identifier(FOOD_QUANTITY_PRODUCT)),
        input(LocalField.CHOICE_ID, LocalScalarValue.Identifier(FOOD_QUANTITY_CHEESE)),
        input(LocalField.MODIFIER_IDS, LocalScalarValue.Text(FOOD_QUANTITY_CHEESE.value)),
        input(LocalField.UNIT, LocalScalarValue.Identifier(FOOD_QUANTITY_UNIT)),
        input(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(0)),
        input(LocalField.QUANTITY, LocalScalarValue.Decimal("1")),
        input(LocalField.FOOD_SERVING_COUNT, LocalScalarValue.Integer(2)),
        input(LocalField.FOOD_PORTION_IDS, LocalScalarValue.Text("${FOOD_QUANTITY_LINE.value},${FOOD_QUANTITY_SECOND.value}")),
        input(LocalField.NAME_ENGLISH, LocalScalarValue.Text(names.english)),
        input(LocalField.NAME_ARABIC, LocalScalarValue.Text(names.arabic)),
        input(LocalField.ITEM_NOTE, LocalScalarValue.Text(FOOD_QUANTITY_ITEM_NOTE)),
        input(LocalField.LINE_SEAT_NOTE, LocalScalarValue.Text(FOOD_QUANTITY_SEAT_NOTE)),
    ))))
}

internal fun LocalCommandIntent.foodQuantityLineChange(field: LocalField, value: LocalScalarValue?): LocalCommandIntent =
    LocalCommandIntent(operationId, action, formVersion, reference, values, lineField, lines.map { line ->
        LocalInputLine(line.lineId, line.values.filterNot { it.field == field } +
            listOfNotNull(value?.let { LocalScalarInput(field, it) }))
    })

internal fun foodQuantityCurrentView(actor: DomainId = FOOD_QUANTITY_OWNER, scope: BranchScope = FOOD_QUANTITY_SCOPE,
    device: DomainId = FOOD_QUANTITY_DEVICE, allowEdit: Boolean = true, policyRevision: Long = 10): LocalSessionView {
    val permissions = listOf(LocalAction.FOOD_ADD, LocalAction.FOOD_CREATE, LocalAction.FOOD_SEND,
        LocalAction.FOOD_STATION_START).mapNotNull(::actionPermission).distinct()
        .filter { allowEdit || it.action != SecurityAction.ORDER_EDIT }
    return LocalSessionView(actor, scope, device, DomainId("e0000000-0000-4000-8000-000000000001"),
        SessionGeneration(1), LocalLoginIdentifier("owner-a"), ActivatedLocalPolicy(scope, actor, Revision(policyRevision),
            permissions.map { permission -> PermissionGrant(permission, scope, GrantEffect.ALLOW,
                targetIds = if (permission.action == SecurityAction.KITCHEN_TASK) setOf(FOOD_QUANTITY_STATION) else null) },
            LocalSessionPolicy(), ThrottlePolicy()),
        MonotonicMoment(FOOD_QUANTITY_CLOCK, 1000), MonotonicMoment(FOOD_QUANTITY_CLOCK, 2000))
}

internal class FoodQuantityGatewayFixture(
    val endpoint: ControlledFoodQuantityEndpoint = ControlledFoodQuantityEndpoint(),
) {
    val journal = ControlledFoodQuantityJournal(LocalClientIdentity(FOOD_QUANTITY_SCOPE, FOOD_QUANTITY_OWNER, FOOD_QUANTITY_DEVICE))
    private val installation = LocalWorkflowInstallation(setOf(LocalFoodProfileV1.COMMAND, LocalFoodStockCodecV2.COMMAND),
        setOf(LocalFoodProfileV1.QUERY, LocalFoodMenuCodecV1.QUERY))
    val gateway = newGateway()
    fun newGateway(): LocalEndpointWorkflowGateway = LocalEndpointWorkflowGateway(endpoint, journal,
        LocalWorkflowCredentialSource {
            LocalWorkflowCredentialLease(LocalGatewayCredentials(DomainId("e0000000-0000-4000-8000-000000000001"),
                OpaqueCredential(ByteArray(16)))) { true }
        }, installation)
    fun assertNoBusinessDispatch() {
        assertEquals(0, endpoint.executeCalls)
        assertEquals(0, journal.dispatchCalls)
        assertEquals(0, journal.resultWrites)
        assertEquals(0, journal.acknowledgements)
    }
}

/** Storage of one controlled client intent. DURABLE is a reply seam, not native durability. */
internal class ControlledFoodQuantityJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    var entry: StoredClientIntentV1? = null
        private set
    var stageCalls: Int = 0
        private set
    var dispatchCalls: Int = 0
        private set
    var resultWrites: Int = 0
        private set
    var acknowledgements: Int = 0
        private set
    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> =
        JournalRead.Value(entry?.takeIf { it.operationId == operationId })
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> = JournalRead.Value(listOfNotNull(entry))
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        stageCalls++
        check(entry == null)
        entry = intent
        return JournalWrite.DURABLE
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite {
        dispatchCalls++; error("No qualified absence or production dispatch is bound")
    }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        resultWrites++; error("No committed Master result is bound")
    }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        acknowledgements++; error("No committed Master result is bound")
    }
    override suspend fun close(): Unit = Unit
}

/** No authentication/authority or positive read result is fabricated by the common fixture. */
internal open class ControlledFoodQuantityEndpoint : LocalMasterClient {
    var view: LocalSessionView = foodQuantityCurrentView()
    var lookupReason: DeferredReason = DeferredReason.WRITER_NOT_QUALIFIED
    val reads: MutableList<ReadRequest> = mutableListOf()
    val lookups: MutableList<OperationIdentity> = mutableListOf()
    var executeCalls: Int = 0
        private set
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("No runtime health evidence")
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = LocalSessionViewResult.Current(view)
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        return LookupReply.Deferred(identity.operationId, lookupReason)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executeCalls++; error("No actual writer/qualified absence is bound")
    }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        error("Positive scripted reads are bound only by the separate host fixture")
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = error("No actual authentication fixture")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("No native renewal fixture")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("No native logout fixture")
}
