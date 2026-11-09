package com.ukkera.pos.app.gateway.independent.foodactivityauthorityv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * L1 slices of food-activity-authority-v1 FAA-054/059/060.
 * Current-session hints and client storage are controlled inputs. No admitted
 * Master, native activity source, durable commit, process restart or RPC is supplied.
 * Lookup stays deferred; no recorded outcome or qualified absence reaches dispatch.
 */
public class FoodActivityAuthorityFrozenGatewaySlicesTest {
    @Test
    public fun faa059CurrentFormKeepsVersionTwoAndExactFoodChoices(): Unit {
        val fixture = FoodActivityGatewayFixture()
        fixture.withHintAccess { access ->
            val form = assertNotNull(foodCreateDraftForm(access))
            assertEquals(LocalAction.FOOD_CREATE_DRAFT, form.action)
            assertEquals(2, form.version)
            assertEquals(listOf("RESTAURANT", "CAFE"), form.fields.filterIsInstance<LocalFormField.Scalar>()
                .single { it.field == LocalField.ACTIVITY }.choices.map { it.code })
        }
        assertNull(fixture.journal.entry)
        assertEquals(0, fixture.journal.stageCalls)
        fixture.assertNoBusinessDispatch()
        assertTrue(fixture.endpoint.lookups.isEmpty())
    }

    @Test
    public fun faa054And059CurrentRestaurantStagesOneActivityProtectedPayload(): Unit = runTest {
        assertExactStaging(2, LocalSalesActivity.RESTAURANT)
    }

    @Test
    public fun faa054And059CurrentCafeStagesOneActivityProtectedPayload(): Unit = runTest {
        assertExactStaging(2, LocalSalesActivity.CAFE)
    }

    @Test
    public fun faa059LegacyRestaurantStagesUnchangedCreateDraftPayload(): Unit = runTest {
        assertExactStaging(1, LocalSalesActivity.RESTAURANT)
    }

    @Test
    public fun faa059LegacyCafeStagesUnchangedCreateDraftPayload(): Unit = runTest {
        assertExactStaging(1, LocalSalesActivity.CAFE)
    }

    @Test
    public fun faa054SixMalformedPayloadVectorsCannotDecodeOrNormalize(): Unit {
        val fixture = FoodActivityGatewayFixture()
        val draft = FoodActivityFrozenInputs.draftLiteral(LocalSalesActivity.RESTAURANT)
        val invalid = listOf(
            "missing_request" to "{\"type\":\"ACTIVITY_PROTECTED\"}",
            "recursive_ACTIVITY_PROTECTED_request" to
                "{\"type\":\"ACTIVITY_PROTECTED\",\"request\":${FoodActivityFrozenInputs.payloadLiteral(2, LocalSalesActivity.RESTAURANT)}}",
            "nested_CREATE_DRAFT_wrapper_in_request" to
                "{\"type\":\"ACTIVITY_PROTECTED\",\"request\":${FoodActivityFrozenInputs.payloadLiteral(1, LocalSalesActivity.RESTAURANT)}}",
            "caller_capability_certificate_instead_of_empty_draft_payload" to
                "{\"type\":\"ACTIVITY_PROTECTED\",\"request\":{\"sourceEnabled\":true}}",
            "missing_order_ID" to "{\"type\":\"ACTIVITY_PROTECTED\",\"request\":${draft.replace("\"orderId\":\"${FoodActivityFrozenInputs.orderId.value}\",", "")}}",
            "invalid_exact_activity_FOOD" to FoodActivityFrozenInputs.payloadLiteral(2, LocalSalesActivity.RESTAURANT)
                .replace("\"activity\":\"RESTAURANT\"", "\"activity\":\"FOOD\""),
        )
        invalid.forEach { (id, literal) ->
            val bytes = StoredBytes(literal.encodeToByteArray())
            assertFails(id) { LocalFoodStockCodecV2.decode(bytes) }
            assertFails(id) { LocalFoodStockCodecV2.normalize(bytes) }
            assertEquals(literal, bytes.copyBytes().decodeToString(), id)
        }
        assertNull(fixture.journal.entry)
        assertEquals(0, fixture.journal.stageCalls)
        fixture.assertNoBusinessDispatch()
        assertTrue(fixture.endpoint.lookups.isEmpty())
    }

    @Test
    public fun faa060LegacySavedIntentKeepsOriginalVersionBytesAndIdentity(): Unit = runTest {
        assertUnresolvedRestoration(1)
    }

    @Test
    public fun faa060CurrentSavedIntentKeepsOriginalVersionBytesAndIdentity(): Unit = runTest {
        assertUnresolvedRestoration(2)
    }

    private suspend fun assertExactStaging(formVersion: Int, activity: LocalSalesActivity) {
        val fixture = FoodActivityGatewayFixture()
        val intent = FoodActivityFrozenInputs.intent(formVersion, activity)
        val staged = assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
        val original = assertNotNull(fixture.journal.entry)
        val saved = original.request()
        val expected = FoodActivityFrozenInputs.draft(activity)

        assertEquals(intent.operationId, staged.operation.operationId)
        assertEquals(LocalAction.FOOD_CREATE_DRAFT, staged.operation.action)
        assertEquals(formVersion, original.formVersion)
        assertEquals(intent.operationId, original.operationId)
        assertEquals(fixture.journal.owner, original.owner)
        assertEquals(LocalFoodStockCodecV2.COMMAND, saved.command)
        assertEquals(2, saved.command.version)
        assertEquals(FoodActivityFrozenInputs.payloadLiteral(formVersion, activity), saved.payload.copyBytes().decodeToString())
        assertEquals(saved.payload, LocalFoodStockCodecV2.normalize(saved.payload))
        assertNull(saved.actionApprovalId)
        assertEquals(expected, savedFoodDraft(saved))
        when (formVersion) {
            1 -> assertEquals(expected, assertIs<LocalFoodStockRequestV2.CreateDraft>(LocalFoodStockCodecV2.decode(saved.payload)).request)
            2 -> assertEquals(expected, assertIs<LocalFoodStockRequestV2.ActivityProtected>(LocalFoodStockCodecV2.decode(saved.payload)).request)
        }
        assertTrue(actionMatches(LocalAction.FOOD_CREATE_DRAFT, saved))
        original.validate(fixture.journal.owner)
        assertEquals(1, fixture.journal.stageCalls)
        fixture.assertNoBusinessDispatch()
        assertTrue(fixture.endpoint.lookups.isEmpty(), "Staging does not request original recovery")
    }

    private suspend fun assertUnresolvedRestoration(formVersion: Int) {
        val fixture = FoodActivityGatewayFixture()
        val intent = FoodActivityFrozenInputs.intent(formVersion)
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
        val original = assertNotNull(fixture.journal.entry)
        val request = original.request()
        val identity = original.identity()

        assertEquals(intent.operationId, assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(intent)).operationId)
        assertEquals(listOf(identity), fixture.endpoint.lookups)
        assertEquals(original, fixture.journal.entry)
        assertEquals(1, fixture.journal.stageCalls)
        fixture.assertNoBusinessDispatch()

        // A canonical client-data round trip and adapter reconstruction only.
        val encoded = journalJson.encodeToString(StoredClientIntentV1.serializer(), original)
        val restored = journalJson.decodeFromString(StoredClientIntentV1.serializer(), encoded)
        restored.validate(fixture.journal.owner)
        val reopened = FoodActivityGatewayFixture(restored)
        val lookedUp = assertIs<LocalOperationResult.Unresolved>(reopened.gateway.lookup(intent.operationId))
        val submitted = assertIs<LocalOperationResult.Unresolved>(reopened.gateway.submit(intent.operationId))
        assertEquals(intent.operationId, lookedUp.operationId)
        assertEquals(intent.operationId, submitted.operationId)
        assertEquals(LocalServiceProblem.UNAVAILABLE, lookedUp.problem)
        assertEquals(LocalServiceProblem.UNAVAILABLE, submitted.problem)
        assertEquals(listOf(identity, identity), reopened.endpoint.lookups)
        assertEquals(original, reopened.journal.entry)
        assertEquals(formVersion, restored.formVersion)
        assertEquals(request.command, restored.request().command)
        assertEquals(request.payload, restored.request().payload)
        assertEquals(identity, restored.identity())
        assertEquals(FoodActivityFrozenInputs.draft(LocalSalesActivity.RESTAURANT), savedFoodDraft(restored.request()))
        assertEquals(0, reopened.journal.stageCalls)
        reopened.assertNoBusinessDispatch()
    }
}

/** Literal format bindings for the frozen logical DINE_IN empty-draft input. */
internal object FoodActivityFrozenInputs {
    val scope: BranchScope = BranchScope(DomainId("10000000-0000-4000-8000-000000000001"),
        DomainId("20000000-0000-4000-8000-000000000002"), DomainId("30000000-0000-4000-8000-000000000021"))
    val actorId: DomainId = DomainId("80000000-0000-4000-8000-000000000021")
    val deviceId: DomainId = DomainId("a0000000-0000-4000-8000-000000000021")
    val sessionId: DomainId = DomainId("90000000-0000-4000-8000-000000000021")
    val operationId: OperationId = OperationId("b0000000-0000-4000-8000-000000000001")
    private val legacyOperationId: OperationId = OperationId("b0000000-0000-4000-8000-000000000301")
    val orderId: DomainId = DomainId("f0000000-0000-4000-8000-000000000001")
    private const val reference: String = "T-02"
    private const val note: String = "Seat 02 / المقعد 02"

    fun intent(formVersion: Int, activity: LocalSalesActivity = LocalSalesActivity.RESTAURANT): LocalCommandIntent =
        LocalCommandIntent(if (formVersion == 1) legacyOperationId else operationId,
            LocalAction.FOOD_CREATE_DRAFT, formVersion, null, listOf(
                LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(orderId)),
                LocalScalarInput(LocalField.ACTIVITY, LocalScalarValue.Choice(activity.name)),
                LocalScalarInput(LocalField.CHANNEL, LocalScalarValue.Choice("DINE_IN")),
                LocalScalarInput(LocalField.SERVICE_REFERENCE, LocalScalarValue.Text(reference)),
                LocalScalarInput(LocalField.SEAT_NOTE, LocalScalarValue.Text(note)),
            ))

    fun draft(activity: LocalSalesActivity): LocalFoodDraftCreateV1 =
        LocalFoodDraftCreateV1(orderId, activity, LocalFoodServiceV1(LocalSalesChannel.DINE_IN, reference, note))

    fun draftLiteral(activity: LocalSalesActivity): String =
        "{\"orderId\":\"${orderId.value}\",\"activity\":\"${activity.name}\",\"service\":{\"channel\":\"DINE_IN\",\"reference\":\"$reference\",\"seatNote\":\"$note\"}}"

    fun payloadLiteral(formVersion: Int, activity: LocalSalesActivity): String =
        "{\"type\":\"${if (formVersion == 2) "ACTIVITY_PROTECTED" else "CREATE_DRAFT"}\",\"request\":${draftLiteral(activity)}}"

    fun currentView(): LocalSessionView = LocalSessionView(actorId, scope, deviceId, sessionId, SessionGeneration(1),
        LocalLoginIdentifier("food-owner"), ActivatedLocalPolicy(scope, actorId, Revision(11),
            listOf(PermissionGrant(Permission(SecurityAction.ORDER_CREATE, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW)),
            LocalSessionPolicy(), ThrottlePolicy()),
        MonotonicMoment(sessionId, 1_000), MonotonicMoment(sessionId, 2_000))
}

internal class FoodActivityGatewayFixture(restored: StoredClientIntentV1? = null) {
    val journal: ControlledFoodActivityJournal = ControlledFoodActivityJournal(
        LocalClientIdentity(FoodActivityFrozenInputs.scope, FoodActivityFrozenInputs.actorId, FoodActivityFrozenInputs.deviceId), restored)
    val endpoint: ControlledFoodActivityEndpoint = ControlledFoodActivityEndpoint()
    private val installation: LocalWorkflowInstallation = LocalWorkflowInstallation(setOf(LocalFoodStockCodecV2.COMMAND), emptySet())
    val gateway: LocalEndpointWorkflowGateway = LocalEndpointWorkflowGateway(endpoint, journal,
        LocalWorkflowCredentialSource { credentialLease() }, installation)

    fun withHintAccess(block: (GatewayAccess) -> Unit) {
        val lease = credentialLease()
        try { block(GatewayAccess(endpoint, lease, FoodActivityFrozenInputs.currentView(), installation)) }
        finally { lease.destroy() }
    }

    fun assertNoBusinessDispatch() {
        assertEquals(0, endpoint.executeCalls)
        assertEquals(0, endpoint.readCalls)
        assertEquals(0, journal.dispatchCalls)
        assertEquals(0, journal.resultWrites)
        assertEquals(0, journal.acknowledgements)
    }

    private fun credentialLease(): LocalWorkflowCredentialLease = LocalWorkflowCredentialLease(
        LocalGatewayCredentials(FoodActivityFrozenInputs.deviceId, OpaqueCredential(ByteArray(16)))) { true }
}

/** Stores controlled client input only; DURABLE here is not native persistence evidence. */
internal class ControlledFoodActivityJournal(owner: LocalClientIdentity, restored: StoredClientIntentV1?) : LocalClientIntentJournal(owner) {
    var entry: StoredClientIntentV1? = restored
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
        entry = intent
        return JournalWrite.DURABLE
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite {
        dispatchCalls++
        error("No qualified absence is supplied")
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

internal class ControlledFoodActivityEndpoint : LocalMasterClient {
    val lookups: MutableList<OperationIdentity> = mutableListOf()
    var executeCalls: Int = 0
    var readCalls: Int = 0
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("No native runtime is supplied")
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        LocalSessionViewResult.Current(FoodActivityFrozenInputs.currentView())
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        return LookupReply.Deferred(identity.operationId, DeferredReason.WRITER_NOT_QUALIFIED)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executeCalls++
        error("No qualified writer or absence is supplied")
    }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        readCalls++
        error("No activity source or business read is supplied")
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = error("No native authentication is supplied")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("No native refresh is supplied")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("No native logout is supplied")
}
