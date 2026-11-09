package com.ukkera.pos.app.gateway.independent.returnabsentexpectationv3

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Frozen values plus disjoint public-DTO constructor coordinates; never authentic sources. */
internal object ReturnAbsentGatewayInputs {
    val missingCase = DomainId("c1530000-0000-4000-8000-000000000001")
    val presentCase = DomainId("c1530000-0000-4000-8000-000000000002")
    val omittedOperation = OperationId("c1531000-0000-4000-8000-000000000001")
    val presentOperation = OperationId("c1531000-0000-4000-8000-000000000004")
    const val omittedBody = """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11"}"""
    const val presentCorrectBody = """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000002","expectedCaseRevision":"11","expectedEntitlementRevision":"7"}"""

    fun coordinate(suffix: String) = DomainId("c1532000-0000-4000-8000-${suffix.padStart(12, '0')}")
    val scope = BranchScope(coordinate("1"), coordinate("2"), coordinate("3"))
    val owner = coordinate("10")
    val device = coordinate("11")
    val missingSale = coordinate("20")
    val presentSale = coordinate("21")
    val portion = coordinate("22")
    val evidence = coordinate("23")
    val unit = coordinate("24")
    val originalOperation = OperationId(coordinate("25").value)
    val claimedAt = UtcInstant.parseCanonical("2026-10-31T09:00:00Z")
    val assertedReceivedAt = UtcInstant.parseCanonical("2026-10-18T09:00:00Z")
    val claimedQuantity = Quantity(unit, 1, 0)

    @OptIn(ExperimentalSerializationApi::class)
    val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }

    fun body(value: LocalReturnCaseReadResultV1) = StoredBytes(json.encodeToString(
        LocalReturnCaseReadResultV1.serializer(), value).encodeToByteArray())

    fun caseView(caseId: DomainId, revision: Revision = Revision(11)): LocalReturnCaseViewV1 {
        val saleId = if (caseId == presentCase) presentSale else missingSale
        val facts = LocalReturnCaseFactsV1(LocalReturnClaimFactsV1(saleId, portion, claimedQuantity,
            assertedReceivedAt, claimedAt, "change of mind", LocalCashEvidenceV1(evidence, claimedAt)),
            "unchanged, inspection pending", LocalReturnRequestedRemedyV1.REFUND, "Africa/Cairo")
        return LocalReturnCaseViewV1(LocalReturnCaseSummaryV1(caseId, revision, LocalReturnCaseStageV1.PENDING,
            claimedAt, saleId, portion, null, null, null, null, null), originalOperation, facts,
            LocalReturnCaseReceiptBindingV1(saleId, portion, claimedQuantity, LocalCashEvidenceV1(evidence, claimedAt)),
            null, null, (revision.value + 1).toInt())
    }

    fun receipt(saleId: DomainId): LocalReturnCaseReadResultV1.Receipt = LocalReturnCaseReadResultV1.Receipt(
        saleId, Revision(7), listOf(LocalReturnReceiptLineV1(portion, Quantity(unit, 2, 0), Quantity(unit, 2, 0),
            Money(Currency.EGP, 22_800))), null)

    /** Generation, policy revision, monotonic values and grants are display-only DTO metadata. */
    fun view(denied: Boolean = false): LocalSessionView {
        val permissions = listOf(LocalAction.RETURN_CASE_QUALIFY, LocalAction.RETURN_CASE_ACCEPT)
            .map { checkNotNull(actionPermission(it)) }.distinct()
        val boot = coordinate("30")
        return LocalSessionView(owner, scope, device, coordinate("31"), SessionGeneration(1), LocalLoginIdentifier("owner"),
            ActivatedLocalPolicy(scope, owner, Revision(1), permissions.map { PermissionGrant(it, scope,
                if (denied) GrantEffect.DENY else GrantEffect.ALLOW) }, LocalSessionPolicy(), ThrottlePolicy()),
            MonotonicMoment(boot, 1_000), MonotonicMoment(boot, 2_000))
    }
}

/** Runs the real shared gateway over bounded public replies and a client-input seam only. */
internal class ReturnAbsentGatewayFixture(caseId: DomainId = ReturnAbsentGatewayInputs.missingCase) {
    val endpoint = ReturnAbsentPublicEndpoint(caseId)
    val journal = ReturnAbsentInputJournal(LocalClientIdentity(ReturnAbsentGatewayInputs.scope,
        ReturnAbsentGatewayInputs.owner, ReturnAbsentGatewayInputs.device))
    var lifetimeCurrent = true
    val gateway = LocalEndpointWorkflowGateway(endpoint, journal, LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(LocalGatewayCredentials(ReturnAbsentGatewayInputs.device, OpaqueCredential(ByteArray(16)))) {
            lifetimeCurrent
        }
    }, LocalWorkflowInstallation(setOf(LocalReturnCaseProfileV1.COMMAND), setOf(LocalReturnCaseReadCodecV1.QUERY)))

    fun query() = LocalWorkflowQuery(LocalWorkflow.RETURNS,
        reference = LocalRecordReference(LocalRecordKind.RETURN_CASE, endpoint.caseValue.summary.caseId))

    fun intent(form: LocalActionForm, operationId: OperationId = ReturnAbsentGatewayInputs.omittedOperation) = LocalCommandIntent(
        operationId, form.action, form.version, LocalRecordReference(LocalRecordKind.RETURN_CASE, endpoint.caseValue.summary.caseId),
        form.fields.map { field ->
            val scalar = field as LocalFormField.Scalar
            LocalScalarInput(scalar.field, checkNotNull(scalar.initialValue))
        })

    fun assertNoDispatch() { assertEquals(0, endpoint.executeCalls); assertEquals(0, endpoint.lookupCalls) }
    fun assertNothingStaged() { assertNull(journal.entry); assertEquals(0, journal.stageCalls); assertNoDispatch() }
}

/** No protected reader, admitted RPC, genuine original, Master health or native authentication. */
internal class ReturnAbsentPublicEndpoint(caseId: DomainId) : LocalMasterClient {
    var caseValue = ReturnAbsentGatewayInputs.caseView(caseId)
    var view = ReturnAbsentGatewayInputs.view()
    var currentReply: LocalSessionViewResult? = null
    val reads = mutableListOf<ReadRequest>()
    var executeCalls = 0
    var lookupCalls = 0
    var caseReply: suspend () -> ReadReply = { data(LocalReturnCaseReadResultV1.Case(caseValue)) }
    var receiptReply: suspend () -> ReadReply = { data(LocalReturnCaseReadResultV1.NotAvailable) }

    // The branch-read revision is response-constructor metadata, not a stored aggregate revision.
    fun data(value: LocalReturnCaseReadResultV1): ReadReply.Data = ReadReply.Data(
        ReturnAbsentGatewayInputs.body(value), Revision(1), ReturnAbsentGatewayInputs.claimedAt)

    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("No runtime health fixture")
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        currentReply ?: LocalSessionViewResult.Current(view)
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        check(request.query == LocalReturnCaseReadCodecV1.QUERY)
        return when (val query = ReturnAbsentGatewayInputs.json.decodeFromString(
            LocalReturnCaseQueryV1.serializer(), request.payload.copyBytes().decodeToString())) {
            is LocalReturnCaseQueryV1.Case -> { check(query.caseId == caseValue.summary.caseId); caseReply() }
            is LocalReturnCaseQueryV1.Receipt -> { check(query.saleId == caseValue.binding?.saleId); receiptReply() }
            else -> error("Only bounded case/receipt public reads are bound")
        }
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executeCalls++; error("No qualified writer or business mutation fixture")
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookupCalls++; error("No original operation or history fixture")
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = error("No native authentication fixture")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("No native refresh fixture")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("No native logout fixture")
}

/** DURABLE is a controlled client-input seam reply, not native journal/persistence evidence. */
internal class ReturnAbsentInputJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    var entry: StoredClientIntentV1? = null
    var stageCalls = 0
    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> = JournalRead.Value(
        entry?.takeIf { it.operationId == operationId })
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> = JournalRead.Value(listOfNotNull(entry))
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        stageCalls++; check(entry == null); entry = intent; return JournalWrite.DURABLE
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite = error("No business dispatch fixture")
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = error("No committed result fixture")
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = error("No committed result fixture")
    override suspend fun close() = Unit
}
