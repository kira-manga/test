package com.ukkera.pos.app.gateway.independent.foodemptycheckv1

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
 * Controlled common-code slices of qa/pos/freezes/food-empty-check-v1 EFD-02–06.
 * The current view, lookup replies and client journal are test seams. They establish
 * neither current Master authority nor a committed check, native durability or RPC.
 * Recorded replies and qualified absence are not fabricated to reach dispatch.
 */
public class FoodEmptyCheckFrozenGatewaySlicesTest {
    @Test
    public fun efd02ExplicitStagingPreservesAllThreeExactServiceInputsAndOriginalWrapper(): Unit = runTest {
        emptyServiceInputs.forEach { input ->
            val fixture = EmptyGatewayFixture()
            val intent = emptyIntent(input)
            val staged = assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
            val original = assertNotNull(fixture.journal.entry)
            val expected = LocalFoodDraftCreateV1(intent.orderId(), LocalSalesActivity.RESTAURANT,
                LocalFoodServiceV1(LocalSalesChannel.valueOf(input.channel), input.reference, input.note.orEmpty()))
            val expectedRequest = LocalFoodStockCodecV2.command(intent.operationId,
                LocalFoodStockRequestV2.CreateDraft(expected))

            assertEquals(intent.operationId, staged.operation.operationId)
            assertEquals(LocalAction.FOOD_CREATE_DRAFT, staged.operation.action)
            assertEquals(intent.operationId, original.operationId)
            assertEquals(LocalAction.FOOD_CREATE_DRAFT, original.localAction)
            assertEquals(fixture.journal.owner, original.owner)
            assertEquals(expectedRequest.command, original.request().command)
            assertEquals(expectedRequest.payload, original.request().payload)
            assertNull(original.request().actionApprovalId)
            assertEquals(expected, savedFoodDraft(original.request()))
            assertTrue(actionMatches(LocalAction.FOOD_CREATE_DRAFT, original.request()))
            assertFalse(actionMatches(LocalAction.FOOD_CREATE, original.request()))
            original.validate(fixture.journal.owner)
            assertEquals(1, fixture.journal.stageCalls)
            fixture.assertNoBusinessDispatch()
            assertTrue(fixture.endpoint.lookups.isEmpty(), "Staging does not recover or submit")
        }
    }

    @Test
    public fun efd03AbsentEmptyAndAsciiBlankReferencesAndUnsupportedModeCannotStage(): Unit = runTest {
        val valid = emptyServiceInputs.first()
        val candidates = listOf<String?>(null, "", "   ").map { reference ->
            val intent = emptyIntent(valid)
            intent.withValues(intent.values.filterNot { it.field == LocalField.SERVICE_REFERENCE } +
                listOfNotNull(reference?.let { LocalScalarInput(LocalField.SERVICE_REFERENCE, LocalScalarValue.Text(it)) }))
        } + emptyIntent(valid).replace(LocalField.CHANNEL, LocalScalarValue.Choice("UNSUPPORTED_MODE"))

        candidates.forEach { candidate ->
            val fixture = EmptyGatewayFixture()
            val rejected = assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(candidate))
            assertEquals(LocalServiceProblem.INVALID_INPUT, rejected.problem)
            assertNull(fixture.journal.entry)
            assertEquals(0, fixture.journal.stageCalls)
            fixture.assertNoBusinessDispatch()
        }
    }

    @Test
    public fun efd04CurrentCreateRemovalOrChangedPrincipalCannotStageThePriorInput(): Unit = runTest {
        val removed = EmptyGatewayFixture()
        val intent = emptyIntent(emptyServiceInputs.first())
        removed.endpoint.view = emptyCurrentView(allowCreate = false, policyRevision = 12)
        assertEquals(LocalServiceProblem.DENIED,
            assertIs<LocalStageResult.NotStaged>(removed.gateway.stage(intent)).problem)
        assertNull(removed.journal.entry)
        assertEquals(0, removed.journal.stageCalls)
        removed.assertNoBusinessDispatch()

        listOf(
            emptyCurrentView(actor = EMPTY_ORDINARY_B),
            emptyCurrentView(scope = EMPTY_SCOPE.copy(branchId = EMPTY_OTHER_BRANCH)),
            emptyCurrentView(scope = EMPTY_SCOPE.copy(companyId = EMPTY_OTHER_COMPANY)),
        ).forEach { changed ->
            val fixture = EmptyGatewayFixture()
            fixture.endpoint.view = changed
            assertEquals(intent.operationId,
                assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(intent)).operationId)
            assertNull(fixture.journal.entry)
            assertEquals(0, fixture.journal.stageCalls)
            fixture.assertNoBusinessDispatch()
        }
    }

    @Test
    public fun efd05IdenticalReplayAndChangedLabelsKeepTheExactOriginalStagedBody(): Unit = runTest {
        val fixture = EmptyGatewayFixture()
        val intent = emptyIntent(emptyServiceInputs.first())
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
        val original = assertNotNull(fixture.journal.entry)
        val originalRequest = original.request()
        val originalIdentity = original.identity()

        // The controlled lookup is explicitly uncertain; no original check is asserted.
        assertEquals(intent.operationId,
            assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(intent)).operationId)
        assertEquals(listOf(originalIdentity), fixture.endpoint.lookups)
        val mismatches = listOf(
            intent.replace(LocalField.CHANNEL, LocalScalarValue.Choice("COUNTER")),
            intent.replace(LocalField.SERVICE_REFERENCE,
                LocalScalarValue.Text(emptyServiceInputs[1].reference)),
            intent.replace(LocalField.SEAT_NOTE, LocalScalarValue.Text(emptyServiceInputs[2].note!!)),
        )
        mismatches.forEach { mismatch ->
            assertEquals(intent.operationId,
                assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(mismatch)).operationId)
            assertEquals(original, fixture.journal.entry)
            assertEquals(originalRequest.payload, assertNotNull(fixture.journal.entry).request().payload)
        }
        assertEquals(1, fixture.journal.stageCalls)
        assertEquals(listOf(originalIdentity), fixture.endpoint.lookups,
            "A conflicting form does not become a recovery request for another body")
        fixture.assertNoBusinessDispatch()

        assertFails { original.copy(action = LocalAction.FOOD_CREATE.name).validate(fixture.journal.owner) }
        assertNull(savedFoodDraft(MutationRequest(original.operationId, originalRequest.command,
            originalRequest.payload, DomainId.random())))
        assertEquals(original, fixture.journal.entry)
    }

    @Test
    public fun efd06UnresolvedLookupAcrossGatewayReconstructionRetainsOriginalActorBodyAndOperation(): Unit = runTest {
        val fixture = EmptyGatewayFixture()
        val intent = emptyIntent(emptyServiceInputs[2])
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
        val original = assertNotNull(fixture.journal.entry)
        val submitted = assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(intent.operationId))
        assertEquals(intent.operationId, submitted.operationId)
        assertEquals(LocalServiceProblem.UNAVAILABLE, submitted.problem)

        // Reconstructing this adapter is not a process restart or durable-storage proof.
        val reconstructed = fixture.newGateway()
        val recovered = assertIs<LocalOperationResult.Unresolved>(reconstructed.lookup(intent.operationId))
        assertEquals(intent.operationId, recovered.operationId)
        assertEquals(listOf(original.identity(), original.identity()), fixture.endpoint.lookups)
        assertEquals(original, fixture.journal.entry)
        assertEquals(1, fixture.journal.stageCalls)
        fixture.assertNoBusinessDispatch()

        fixture.endpoint.view = emptyCurrentView(allowCreate = false, policyRevision = 12)
        fixture.endpoint.lookupReason = DeferredReason.AUTHORIZATION_DENIED
        val denied = assertIs<LocalOperationResult.Unresolved>(reconstructed.lookup(intent.operationId))
        assertEquals(LocalServiceProblem.DENIED, denied.problem)
        assertEquals(original, fixture.journal.entry)
        assertEquals(original.identity(), fixture.endpoint.lookups.last())
        fixture.assertNoBusinessDispatch()
    }

    @Test
    public fun efd02EmptyCheckOffersNoSendHandoffOrCollectionWorkDespiteAvailableHints(): Unit = runTest {
        val fixture = EmptyGatewayFixture()
        val orderId = DomainId.random()
        val readRevision = Revision(13) // Controlled current input, not a creation-revision oracle.
        val body = LocalFoodUiBody.Check(LocalFoodUiCheck(orderId, EMPTY_OWNER, readRevision,
            "RESTAURANT", LocalFoodUiService("DINE_IN", "T-02", emptyServiceInputs.first().note!!),
            0, null, null), emptyList(), Money(Currency.EGP, 0), false)
        fixture.withHintAccess { access ->
            val create = assertNotNull(foodCreateDraftForm(access))
            val channels = create.fields.filterIsInstance<LocalFormField.Scalar>()
                .single { it.field == LocalField.CHANNEL }.choices.map { it.code }
            assertTrue(channels.containsAll(emptyServiceInputs.map { it.channel }))
            val forms = foodCheckForms(access, body) + listOfNotNull(foodBeginCollectionForm(access, body))
            assertTrue(forms.none { it.action in setOf(LocalAction.FOOD_SEND, LocalAction.FOOD_OFFER_HANDOFF,
                LocalAction.FOOD_BEGIN_COLLECTION) })
            assertEquals(0L, body.originalQuotedTotal.minor)
            assertTrue(body.portions.isEmpty())
        }
        fixture.assertNoBusinessDispatch()
        assertEquals(0, fixture.journal.stageCalls)
    }
}

private data class EmptyServiceInput(val channel: String, val reference: String, val note: String?)
private val emptyServiceInputs = listOf(
    EmptyServiceInput("DINE_IN", "T-02", "Seat 02 / المقعد 02"),
    EmptyServiceInput("COUNTER", "Counter 01 / الكاونتر 01", null),
    EmptyServiceInput("TAKEAWAY", "  Pickup 0007 / استلام 0007  ", "For pickup / للاستلام"),
)
private val EMPTY_SCOPE = BranchScope(DomainId("a0000000-0000-4000-8000-000000000001"),
    DomainId("a0000000-0000-4000-8000-000000000002"), DomainId("a0000000-0000-4000-8000-000000000004"))
private val EMPTY_OWNER = DomainId("b0000000-0000-4000-8000-000000000001")
private val EMPTY_ORDINARY_B = DomainId("b0000000-0000-4000-8000-000000000002")
private val EMPTY_DEVICE = DomainId("d0000000-0000-4000-8000-000000000001")
private val EMPTY_OTHER_BRANCH = DomainId("a0000000-0000-4000-8000-000000000005")
private val EMPTY_OTHER_COMPANY = DomainId("a0000000-0000-4000-8000-000000000003")
private val EMPTY_CONSTRUCTOR_BOOT = DomainId("7f500000-0000-4000-8000-000000000001")

private fun emptyIntent(input: EmptyServiceInput): LocalCommandIntent = LocalCommandIntent(
    OperationId.random(), LocalAction.FOOD_CREATE_DRAFT, 1, reference = null, values = listOf(
        LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(DomainId.random())),
        LocalScalarInput(LocalField.ACTIVITY, LocalScalarValue.Choice("RESTAURANT")),
        LocalScalarInput(LocalField.CHANNEL, LocalScalarValue.Choice(input.channel)),
        LocalScalarInput(LocalField.SERVICE_REFERENCE, LocalScalarValue.Text(input.reference)),
    ) + listOfNotNull(input.note?.let { LocalScalarInput(LocalField.SEAT_NOTE, LocalScalarValue.Text(it)) }))

private fun LocalCommandIntent.orderId(): DomainId =
    (values.single { it.field == LocalField.ORDER_ID }.value as LocalScalarValue.Identifier).value
private fun LocalCommandIntent.replace(field: LocalField, value: LocalScalarValue): LocalCommandIntent =
    withValues(values.filterNot { it.field == field } + LocalScalarInput(field, value))
private fun LocalCommandIntent.withValues(values: List<LocalScalarInput>): LocalCommandIntent =
    LocalCommandIntent(operationId, action, formVersion, reference, values, lineField, lines)

private fun emptyCurrentView(actor: DomainId = EMPTY_OWNER, scope: BranchScope = EMPTY_SCOPE,
    allowCreate: Boolean = true, policyRevision: Long = 10): LocalSessionView {
    val actions = listOf(LocalAction.FOOD_CREATE_DRAFT, LocalAction.FOOD_ADD, LocalAction.FOOD_SEND,
        LocalAction.FOOD_OFFER_HANDOFF, LocalAction.FOOD_EDIT_SERVICE_DETAILS, LocalAction.FOOD_BEGIN_COLLECTION)
    val permissions = actions.mapNotNull(::actionPermission).distinct()
        .filter { allowCreate || it.action != SecurityAction.ORDER_CREATE }
    return LocalSessionView(actor, scope, EMPTY_DEVICE, DomainId.random(), SessionGeneration(1),
        LocalLoginIdentifier("owner-a"), ActivatedLocalPolicy(scope, actor, Revision(policyRevision),
            permissions.map { PermissionGrant(it, scope, GrantEffect.ALLOW) }, LocalSessionPolicy(), ThrottlePolicy()),
        MonotonicMoment(EMPTY_CONSTRUCTOR_BOOT, 1000), MonotonicMoment(EMPTY_CONSTRUCTOR_BOOT, 2000))
}

private class EmptyGatewayFixture {
    val journal = ControlledEmptyJournal(LocalClientIdentity(EMPTY_SCOPE, EMPTY_OWNER, EMPTY_DEVICE))
    val endpoint = ControlledEmptyEndpoint()
    val installation = LocalWorkflowInstallation(setOf(LocalFoodStockCodecV2.COMMAND, LocalFoodProfileV1.COMMAND), emptySet())
    val gateway = newGateway()
    fun newGateway(): LocalEndpointWorkflowGateway = LocalEndpointWorkflowGateway(endpoint, journal,
        LocalWorkflowCredentialSource {
            LocalWorkflowCredentialLease(LocalGatewayCredentials(DomainId.random(), OpaqueCredential(ByteArray(16)))) { true }
        }, installation)
    fun withHintAccess(block: (GatewayAccess) -> Unit) {
        val lease = LocalWorkflowCredentialLease(LocalGatewayCredentials(DomainId.random(), OpaqueCredential(ByteArray(16)))) { true }
        try { block(GatewayAccess(endpoint, lease, endpoint.view, installation)) } finally { lease.destroy() }
    }
    fun assertNoBusinessDispatch() {
        assertEquals(0, endpoint.executeCalls)
        assertEquals(0, endpoint.readCalls)
        assertEquals(0, journal.dispatchCalls)
        assertEquals(0, journal.resultWrites)
        assertEquals(0, journal.acknowledgements)
    }
}

/** Controlled storage of one client input only; DURABLE is a seam result, not persistence evidence. */
private class ControlledEmptyJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
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
        entry = intent
        return JournalWrite.DURABLE
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite { dispatchCalls++; error("No qualified absence exists") }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        resultWrites++; error("No committed result exists")
    }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        acknowledgements++; error("No committed result exists")
    }
    override suspend fun close() = Unit
}

private class ControlledEmptyEndpoint : LocalMasterClient {
    var view = emptyCurrentView()
    var lookupReason = DeferredReason.WRITER_NOT_QUALIFIED
    val lookups = mutableListOf<OperationIdentity>()
    var executeCalls = 0
    var readCalls = 0
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("No runtime health evidence")
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = LocalSessionViewResult.Current(view)
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        return LookupReply.Deferred(identity.operationId, lookupReason)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executeCalls++; error("No qualified writer/absence exists")
    }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        readCalls++; error("No production readback exists")
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = error("No authentication fixture")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("No native session refresh")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("No native session logout")
}
