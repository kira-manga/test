package com.ukkera.pos.app.gateway.independent.approvalretirementnoadmission

import com.ukkera.pos.app.*
import com.ukkera.pos.app.LocalArrivalActorSelectionModeV2
import com.ukkera.pos.app.LocalArrivalActorSelectionV2
import com.ukkera.pos.app.LocalArrivalApprovalModeV2
import com.ukkera.pos.app.LocalArrivalApprovalRouteV2
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.independent.arrivalapprovalclient.AawClientCredentialInputs
import com.ukkera.pos.app.gateway.independent.arrivalapprovalclient.SyntheticArrivalApprovalEncryptedJournal
import com.ukkera.pos.catalog.store.LocalBeginPurchasingDocumentV1
import com.ukkera.pos.catalog.store.LocalCompletePurchasingDocumentV1
import com.ukkera.pos.database.*
import com.ukkera.pos.database.LocalActionApprovalRetirementAttempt as MasterRetirementAttempt
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import kotlin.test.*

/** Exact frozen PDF bytes and aliases, not authenticated originals or current history. */
internal object ArrivalRetirementFrozenV2 {
    val directory: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/arrival-retirement-no-admission-v2") }
        .firstOrNull { Files.isRegularFile(it.resolve("freeze.json")) }
        ?: error("Frozen arrival-retirement-no-admission-v2 packet is required")
    val data: JsonObject = Json.parseToJsonElement(Files.readString(directory.resolve("fixtures.json"))).jsonObject
    fun section(name: String): JsonObject = data.getValue(name).jsonObject
    fun id(alias: String): DomainId = DomainId(UUID.nameUUIDFromBytes("arrna-v2:$alias".encodeToByteArray()).toString())
    fun operation(alias: String): OperationId = OperationId(id(alias).value)
}

/**
 * One exact unapproved 24-unit A or distinct 12-unit B under the same Separate rule.
 * Unspecified UUID/revision/clock carriers and view hints are synthetic client inputs.
 * Physical source generations, authenticated conversion/history and original session/
 * drawer authority are not supplied by these public types or by a PDF digest.
 */
internal class ArrivalRetirementNoAdmissionInputs(val name: String = "A") {
    init { require(name in setOf("A", "B")) }
    private val frozen = ArrivalRetirementFrozenV2.section("record_$name")
    private fun id(alias: String): DomainId = ArrivalRetirementFrozenV2.id(alias)
    val requester = id("requester-A")
    val approver = id("approver-A")
    val scope = BranchScope(id("organization-A"), id("company-A"), id("branch-A"))
    val owner = LocalClientIdentity(scope, requester, id("requester-device-A"))
    val operationId = ArrivalRetirementFrozenV2.operation("business-Record-$name")
    val approvalId = id("approval-$name")
    val supplier = id("supplier-A")
    val warehouseId = id("warehouse-A")
    val deliveryId = id("delivery-$name")
    val lineId = id("line-$name")
    val sourceId = id("source-$name")
    val custodyId = id("custody-$name")
    val documentId = id("original-$name")
    val clockId = id("inert-clock")
    val cartons = frozen.getValue("cartons").jsonPrimitive.int
    val unitsPerCarton = frozen.getValue("units_per_carton_snapshot").jsonPrimitive.int
    val baseUnits = frozen.getValue("observed_base_units").jsonPrimitive.int
    val originalBytes = Files.readAllBytes(ArrivalRetirementFrozenV2.directory.resolve("original-$name.pdf"))
    private val originalDigest = if (name == "A") ArrivalRetirementFrozenV2.section("original_A")
        .getValue("sha256").jsonPrimitive.content else frozen.getValue("original_sha256").jsonPrimitive.content
    private val originalLength = if (name == "A") ArrivalRetirementFrozenV2.section("original_A")
        .getValue("byte_count").jsonPrimitive.int else frozen.getValue("original_byte_count").jsonPrimitive.int
    val requesterPolicyRevision = Revision(1)
    private val unitId = id("unit")
    private val sourceDigest = sha256("inert source-coordinate; not authenticated history".encodeToByteArray())
    private fun source(kind: String, sourceId: DomainId) = LocalArrivalSourceCoordinateV2(kind, sourceId, Revision(1), sourceDigest)
    val policy = LocalArrivalPolicyCoordinateV2(scope, id("purchasing-policy-A"), id("purchasing-publication-A"),
        Revision(5), source("arrna.policy.active", scope.branchId),
        source("arrna.policy.publication", id("purchasing-publication-A")),
        UtcInstant.parseCanonical("2026-11-01T00:00:00Z"), null)
    val catalog = LocalArrivalCatalogCoordinateV2(scope, id("catalog-A"), id("catalog-publication-A"), Revision(1),
        source("arrna.catalog.active", scope.branchId), source("arrna.catalog.publication", id("catalog-publication-A")))
    val retention = LocalArrivalRetentionCoordinateV2(id("supplier-delivery-original-A"), Revision(2))
    val documents = listOf(LocalArrivalOriginalCoordinateV2(scope, supplier, documentId, Revision(1),
        PayloadDigest(originalDigest), originalLength, PurchasingEvidenceMediaV1.PDF, PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY))
    val route = LocalArrivalApprovalRouteV2(LocalArrivalApprovalModeV2.SEPARATE,
        requesters = LocalArrivalActorSelectionV2(LocalArrivalActorSelectionModeV2.CURRENT_BRANCH_GRANTEES),
        approvers = LocalArrivalActorSelectionV2(LocalArrivalActorSelectionModeV2.CURRENT_BRANCH_GRANTEES))
    val rule = LocalArrivalRuleOptionV2(id("record-separate-rule-A"), LocalAction.ARRIVAL_RECORD, false, 1, 1,
        listOf(PurchasingEvidenceMediaV1.PDF), listOf(PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY), retention, route,
        "Frozen Separate Record rule/publication 5; one required supplier-delivery PDF, zero optional. " +
            "Supplier-owned held uninspected observation; no price, invoice, payment or PO allocation. " +
            "Supplied documentary metadata only, not an authenticated rule or native approval consumption.")
    private val options = LocalArrivalEvidenceOptionsV2(policy, catalog, requesterPolicyRevision, listOf(rule), documents)
    val selection = LocalArrivalEvidenceInputV2(policy, catalog, requesterPolicyRevision, rule, retention, documents, options.equalityDigest)
    private fun storeSource(value: LocalArrivalSourceCoordinateV2) =
        LocalArrivalSourceSelectionV2(value.kind, value.id, value.storageRevision, value.digest)
    val evidence = LocalArrivalEvidenceSelectionV2("ARRIVAL-$name",
        LocalArrivalPolicySelectionV2(scope, policy.policyId, policy.publicationId, policy.publicationRevision,
            storeSource(policy.activePointer), storeSource(policy.publication), policy.validFrom, policy.validUntil),
        LocalArrivalCatalogSelectionV2(scope, catalog.catalogId, catalog.publicationId, catalog.revision,
            storeSource(catalog.activePointer), storeSource(catalog.publication)), rule.ruleId,
        documents.map { LocalArrivalDocumentSelectionV2(it.scope, it.supplierId, it.documentId, it.storageRevision,
            it.exactBytesDigest, it.byteLength, it.media.name, it.purpose.name) },
        LocalArrivalRetentionSelectionV2(retention.classId, retention.revision))
    val item = LocalArrivalItemSelectionV2(id("sku-A"), id("product-A"), Revision(1), unitId, 0,
        Revision(1), Revision(1), Revision(1), null)
    val record = LocalRecordUnpricedArrivalV2(deliveryId, supplier, Revision(1), requesterPolicyRevision,
        supplierDeliveryReference = "ARRIVAL-$name", items = listOf(item),
        warehouses = listOf(LocalArrivalWarehouseSelectionV2(warehouseId, Revision(1))),
        lines = listOf(LocalArrivalLineV2(lineId, sourceId, custodyId, item.skuId, warehouseId,
            Quantity.parse(unitId, baseUnits.toString(), 0), disposition = LocalArrivalDispositionV1.UNINSPECTED_HELD,
            ownership = LocalArrivalOwnershipV1.SUPPLIER_ATTESTED, ownershipReference = "Supplier-held observation ARRIVAL-$name")),
        evidence = evidence, reasonCode = "arrna.record.observation")
    val request = LocalUnpricedArrivalCommandsV2.record(operationId, record)
    // The UI snapshot preserves the frozen carton arithmetic; the command carries base units.
    val intent = LocalCommandIntent(operationId, LocalAction.ARRIVAL_RECORD, 2,
        LocalRecordReference(LocalRecordKind.ARRIVAL_WAREHOUSE_V2, warehouseId, parentId = supplier),
        listOf(LocalScalarInput(LocalField.OBSERVATION_REFERENCE, LocalScalarValue.Text("ARRIVAL-$name"))),
        LocalField.LINES, listOf(LocalInputLine(lineId,
            listOf(LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal(baseUnits.toString()))))), selection)
    val summary = listOf(LocalRecordValue(text("Original observation"), text("$cartons cartons x $unitsPerCarton = $baseUnits units")),
        LocalRecordValue(text("Disposition"), text("UNINSPECTED_HELD / supplier-owned physically held")),
        LocalRecordValue(text("Financial status"), text("UNPRICED_UNRESOLVED; no price/invoice/payment/PO allocation")),
        LocalRecordValue(text("Origin documentary snapshot"), text("requester-A / session-A0 / drawer-A")))
    val confirmationDigest = sha256(ArrivalRetirementFrozenV2.section(if (name == "A") "confirmation_A" else "record_B")
        .toString().encodeToByteArray())

    init {
        assertEquals(cartons * unitsPerCarton, baseUnits)
        assertEquals(if (name == "A") 24 else 12, baseUnits)
        assertEquals(originalLength, originalBytes.size)
        assertEquals(originalDigest, sha256(originalBytes).value)
    }

    fun staged(): StoredClientIntentV1 = StoredClientIntentV1.staged(owner, intent, request, text("Saved unapproved Record $name"), summary)
    fun ready(entry: StoredClientIntentV1): StoredClientIntentV1 = entry.copy(actionApproval = checkNotNull(entry.actionApproval).copy(
        phase = LocalActionApprovalPhase.READY_TO_CONFIRM.name,
        preview = StoredClientActionApprovalPreviewV1(requester, requesterPolicyRevision, scope, deliveryId, Revision(0),
            entry.semanticSha256, operationId, record.reasonCode, confirmationDigest)))
    fun sessionView(actor: DomainId = requester, requestHint: Boolean = true, recovered: Boolean = false): LocalSessionView =
        LocalSessionView(actor, scope, owner.deviceId, id(if (recovered) "session-A1" else "session-A0"), SessionGeneration(1),
            LocalLoginIdentifier("SYN-REQUESTER-A"), ActivatedLocalPolicy(scope, actor, requesterPolicyRevision,
                if (requestHint) listOf(PermissionGrant(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.REQUEST),
                    scope, GrantEffect.ALLOW)) else emptyList(), LocalSessionPolicy(), ThrottlePolicy()),
            MonotonicMoment(clockId, 10), MonotonicMoment(clockId, 20))
    fun historicalReceipt(entry: StoredClientIntentV1): LocalActionApprovalReceipt {
        val preview = checkNotNull(entry.actionApproval?.preview)
        return LocalActionApprovalReceipt(approvalId, requester, approver, preview.requirement(), preview.confirmationDigest,
            MonotonicMoment(clockId, 1), MonotonicMoment(clockId, 100), null)
    }
    companion object { fun text(value: String) = LocalText(value, value) }
}

internal data class ArrivalRetirementHandoff(val attempt: MasterRetirementAttempt, val request: MutationRequest,
    val durable: StoredClientIntentV1)

internal class ArrivalRetirementHold {
    val entered = CompletableDeferred<ArrivalRetirementHandoff>()
    val reply = CompletableDeferred<LocalActionApprovalRetireResult>()
    suspend fun receive(handoff: ArrivalRetirementHandoff): LocalActionApprovalRetireResult {
        check(entered.complete(handoff))
        return reply.await()
    }
}

/** Controlled replies/view hints only. No accepting Master, authentication or business handler. */
internal class ArrivalRetirementNoAdmissionEndpoint(private val durable: suspend (OperationId) -> StoredClientIntentV1,
    inputs: ArrivalRetirementNoAdmissionInputs) : LocalMasterClient, LocalMasterApprovalClient {
    override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    var view = inputs.sessionView()
    var currentDenied = false
    var approvalStatus: LocalActionApprovalStatus? = null
    val historicalReceipts = mutableMapOf<DomainId, LocalActionApprovalReceipt>()
    val retirements = mutableListOf<ArrivalRetirementHandoff>()
    val approvalLookups = mutableListOf<Pair<DomainId, PayloadDigest>>()
    val businessLookups = mutableListOf<OperationIdentity>()
    val executions = mutableListOf<MutationRequest>()
    var previews = 0
    var issues = 0
    var onRetire: suspend (ArrivalRetirementHandoff) -> LocalActionApprovalRetireResult = {
        LocalActionApprovalRetireResult.NotAdmitted(it.attempt, LocalActionApprovalNoAdmissionReason.QUEUE_FULL)
    }
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        if (currentDenied) LocalSessionViewResult.Denied else LocalSessionViewResult.Current(view)
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean) = LocalAuthenticationResult.Unavailable
    override suspend fun refresh(credentials: LocalGatewayCredentials) = LocalAuthenticationResult.Unavailable
    override suspend fun logout(credentials: LocalGatewayCredentials) = LocalSignOutResult.Denied
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executions += request
        return MutationReply.Deferred(request.operationId, DeferredReason.AUTHORIZATION_DENIED)
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        businessLookups += identity
        return LookupReply.AbsentAtCurrentWriter(identity.operationId)
    }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest) = ReadReply.Unavailable(ReadFailure.UNKNOWN_QUERY)
    override suspend fun previewActionApproval(requestor: LocalGatewayCredentials, request: MutationRequest,
        retiredApprovalId: DomainId?): LocalActionApprovalPreviewResult { previews++; return LocalActionApprovalPreviewResult.Denied }
    override suspend fun issueActionApproval(approvalId: DomainId, requestor: LocalGatewayCredentials, request: MutationRequest,
        confirmationDigest: PayloadDigest, approverLogin: LocalLoginIdentifier, approverPassword: CharArray,
        retiredApprovalId: DomainId?): LocalActionApprovalIssueResult { issues++; return LocalActionApprovalIssueResult.StorageUncertain }
    override suspend fun lookupActionApproval(approvalId: DomainId, requestor: LocalGatewayCredentials,
        confirmationDigest: PayloadDigest): LocalActionApprovalStatus {
        approvalLookups += approvalId to confirmationDigest
        return approvalStatus ?: LocalActionApprovalStatus.Recorded(checkNotNull(historicalReceipts[approvalId]))
    }
    override suspend fun retireActionApproval(approvalId: DomainId, requestor: LocalGatewayCredentials,
        request: MutationRequest, confirmationDigest: PayloadDigest) = LocalActionApprovalRetireResult.Unavailable
    override suspend fun retireActionApprovalAttempt(attempt: MasterRetirementAttempt, requestor: LocalGatewayCredentials,
        request: MutationRequest): LocalActionApprovalRetireResult {
        val entry = durable(request.operationId)
        assertEquals(attempt, checkNotNull(entry.actionApproval?.retirementAttempt).boundAttempt(entry))
        assertTrue(attempt.matches(request))
        val handoff = ArrivalRetirementHandoff(attempt, request, entry)
        retirements += handoff
        return onRetire(handoff)
    }
}

/**
 * Stable gateway custody across close/reopen of the exclusively locked real store.
 * Every fresh invocation is reopened/read after its durable write, BEFORE handoff.
 * Faults below return without delegating a write: known no-commit wrapper cuts only.
 */
internal class ArrivalRetirementNoAdmissionJournal(val host: SyntheticArrivalApprovalEncryptedJournal) : LocalClientIntentJournal(host.owner) {
    val savedInvocations = mutableListOf<StoredClientIntentV1>()
    var failFreshInvocation = false
    var failCompletion = false
    val rejectedWrites = mutableListOf<Pair<StoredClientIntentV1, StoredClientIntentV1>>()
    override suspend fun find(operationId: OperationId) = host.journal.find(operationId)
    override suspend fun pending() = host.journal.pending()
    override suspend fun stage(intent: StoredClientIntentV1) = host.journal.stage(intent)
    override suspend fun markDispatched(operationId: OperationId) = host.journal.markDispatched(operationId)
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1) = host.journal.remember(operationId, result)
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1) = host.journal.acknowledge(operationId, result)
    override suspend fun documentarySource(documentId: DomainId) = host.journal.documentarySource(documentId)
    override suspend fun documentarySources() = host.journal.documentarySources()
    override suspend fun saveDocumentarySource(source: StoredClientDocumentarySourceV1) = host.journal.saveDocumentarySource(source)
    override suspend fun updateActionApproval(previous: StoredClientIntentV1, next: StoredClientIntentV1): JournalWrite {
        val fresh = next.actionApproval?.localPhase == LocalActionApprovalPhase.RETIRE_UNRESOLVED &&
            next.actionApproval?.retirementAttempt != null && previous.actionApproval?.retirementAttempt != next.actionApproval?.retirementAttempt
        val completion = next.actionApproval?.localPhase == LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED && previous != next
        if (fresh && failFreshInvocation || completion && failCompletion) {
            if (fresh) failFreshInvocation = false else failCompletion = false
            rejectedWrites += previous to next
            return JournalWrite.UNAVAILABLE
        }
        val result = host.journal.updateActionApproval(previous, next)
        if (fresh && result == JournalWrite.DURABLE) {
            host.reopenOriginal()
            assertEquals(next, host.find(next.operationId))
            savedInvocations += next
        }
        return result
    }
    override suspend fun close() = host.retire()
}

internal class ArrivalRetirementNoAdmissionFixture private constructor(val inputs: ArrivalRetirementNoAdmissionInputs,
    val host: SyntheticArrivalApprovalEncryptedJournal) {
    val journal = ArrivalRetirementNoAdmissionJournal(host)
    val credentials = AawClientCredentialInputs()
    val endpoint = ArrivalRetirementNoAdmissionEndpoint(host::find, inputs)
    val gateway = LocalEndpointWorkflowGateway(endpoint, journal, credentials,
        LocalWorkflowInstallation(setOf(LocalUnpricedArrivalProfileV2.RECORD, LocalUnpricedArrivalProfileV2.QUALIFY,
            LocalUnpricedArrivalProfileV2.REJECT, LocalUnpricedArrivalProfileV2.DISPATCH), setOf(LocalUnpricedArrivalProfileV2.SOURCE)))
    lateinit var i0: StoredClientIntentV1
        private set
    var baselineRetirements: Int = 0
        private set
    private val attachedBaselines = mutableMapOf<OperationId, StoredClientIntentV1>()
    suspend fun latest() = host.find(inputs.operationId)
    suspend fun reopen() = host.reopenOriginal()
    suspend fun saveHistoricalAttached(otherInputs: ArrivalRetirementNoAdmissionInputs = inputs): StoredClientIntentV1 {
        val bytes = otherInputs.originalBytes
        val beginId = ArrivalRetirementFrozenV2.operation("original-${otherInputs.name}-begin")
        val begin = LocalBeginPurchasingDocumentV1(otherInputs.documentId, otherInputs.supplier, Revision(1),
            "ARRIVAL-${otherInputs.name}", null, PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY, PurchasingEvidenceMediaV1.PDF,
            "original-${otherInputs.name}.pdf", bytes.size, sha256(bytes), listOf(sha256(bytes)))
        val source = journal.prepareDocumentarySource(beginId, begin,
            listOf(ArrivalRetirementFrozenV2.operation("original-${otherInputs.name}-chunk")),
            ArrivalRetirementFrozenV2.operation("original-${otherInputs.name}-complete"),
            LocalCompletePurchasingDocumentV1(otherInputs.documentId, beginId), bytes)
        assertEquals(JournalWrite.DURABLE, journal.saveDocumentarySource(source))
        val staged = otherInputs.staged()
        assertEquals(JournalWrite.DURABLE, journal.stage(staged))
        val ready = otherInputs.ready(staged)
        assertEquals(JournalWrite.DURABLE, journal.updateActionApproval(staged, ready))
        val pending = ready.copy(actionApproval = checkNotNull(ready.actionApproval).copy(
            phase = LocalActionApprovalPhase.ISSUE_UNRESOLVED.name, approvalId = otherInputs.approvalId))
        assertEquals(JournalWrite.DURABLE, journal.updateActionApproval(ready, pending))
        // Approved seam: historical-client preservation input, NOT Issued/Ready or native authority.
        endpoint.historicalReceipts[otherInputs.approvalId] = otherInputs.historicalReceipt(pending)
        assertEquals(LocalActionApprovalPhase.ATTACHED,
            assertIs<LocalActionApprovalResult.Available>(gateway.lookupActionApproval(otherInputs.operationId)).operation.actionApproval?.phase)
        val attached = host.find(otherInputs.operationId)
        attachedBaselines.getOrPut(otherInputs.operationId) { attached }
        return attached
    }
    suspend fun assertOriginal(entry: StoredClientIntentV1, originalInputs: ArrivalRetirementNoAdmissionInputs = inputs) {
        assertTrue(originalInputs.staged().sameIntent(entry))
        assertEquals(originalInputs.owner, entry.owner)
        val originalRequest = originalInputs.request
        val currentRequest = entry.unapprovedRequest()
        assertEquals(originalRequest.operationId, currentRequest.operationId)
        assertEquals(originalRequest.command, currentRequest.command)
        assertEquals(originalRequest.payload, currentRequest.payload)
        assertEquals(originalRequest.actionApprovalId, currentRequest.actionApprovalId)
        assertEquals(originalInputs.record, LocalUnpricedArrivalCommandsV2.decodeRecordRequest(currentRequest.payload))
        val attached = checkNotNull(attachedBaselines[originalInputs.operationId])
        val originalApproval = checkNotNull(attached.actionApproval)
        val currentApproval = checkNotNull(entry.actionApproval)
        assertEquals(attached.actionApprovalId, entry.actionApprovalId)
        assertEquals(originalApproval.approvalId, currentApproval.approvalId)
        assertEquals(originalApproval.preview, currentApproval.preview)
        assertEquals(originalApproval.receipt, currentApproval.receipt)
        assertEquals(originalApproval.retiredApprovals, currentApproval.retiredApprovals)
        assertEquals(LocalArrivalApprovalModeV2.SEPARATE, entry.actionApproval?.rule?.approvalRoute?.mode)
        assertEquals(Revision(5), originalInputs.record.evidence.policy.publicationRevision)
        val source = assertNotNull(assertIs<JournalRead.Value<StoredClientDocumentarySourceV1?>>(journal.documentarySource(originalInputs.documentId)).value)
        val copy = source.copyOriginalBytes()
        try { assertContentEquals(originalInputs.originalBytes, copy) } finally { copy.fill(0) }
        assertNull(entry.recorded)
        assertFalse(entry.acknowledged)
        assertNull(entry.actionApproval?.retirement)
        assertNull(entry.actionApproval?.receipt?.consumedByOperationId)
    }
    fun assertNoBusinessOrIssuance() {
        for (command in listOf(LocalUnpricedArrivalProfileV2.RECORD, LocalUnpricedArrivalProfileV2.QUALIFY,
            LocalUnpricedArrivalProfileV2.REJECT, LocalUnpricedArrivalProfileV2.DISPATCH))
            assertEquals(0, endpoint.executions.count { it.command == command })
        assertEquals(0, endpoint.previews)
        assertEquals(0, endpoint.issues)
    }
    companion object {
        suspend fun create(completedI0: Boolean): ArrivalRetirementNoAdmissionFixture {
            val inputs = ArrivalRetirementNoAdmissionInputs()
            val host = SyntheticArrivalApprovalEncryptedJournal.create(inputs.owner)
            val fixture = ArrivalRetirementNoAdmissionFixture(inputs, host)
            try {
                fixture.saveHistoricalAttached()
                if (completedI0) {
                    val result = assertIs<LocalActionApprovalResult.Available>(fixture.gateway.retireActionApproval(inputs.operationId))
                    assertEquals(LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED, result.operation.actionApproval?.phase)
                    fixture.i0 = fixture.latest()
                    assertEquals(1, fixture.endpoint.retirements.size)
                    assertNotNull(fixture.i0.actionApproval?.retirementAttempt?.noAdmissionReason)
                    fixture.baselineRetirements = fixture.endpoint.retirements.size
                }
                return fixture
            } catch (failure: Throwable) { host.close(); throw failure }
        }
    }
}

internal suspend fun withArrivalRetirementNoAdmissionFixture(completedI0: Boolean = true,
    block: suspend (ArrivalRetirementNoAdmissionFixture) -> Unit) {
    val fixture = ArrivalRetirementNoAdmissionFixture.create(completedI0)
    try { block(fixture) } finally { fixture.host.close() }
}
