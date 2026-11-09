@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.app.gateway.independent.ordinaryreturnqualification

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
import kotlin.time.Instant

/** Semantic F1/F2 values from ordinary-return-qualification/fixtures.json v1.
 * Additional IDs fill public DTO coordinates only; none proves an original source. */
internal object OrdinaryReturnGatewayInputs {
    fun id(suffix: Int) = DomainId("00000000-0000-4000-8000-${suffix.toString().padStart(12, '0')}")
    fun instant(literal: String) = UtcInstant(Instant.parse(literal))
    val scope = BranchScope(id(101), id(201), id(301))
    val owner = id(402)
    val device = id(452)
    val sale = id(501)
    val portion = id(502)
    val receiptId = id(503)
    val inspectionId = id(504)
    val caseId = id(701)
    val unit = id(702)
    val claimEvidenceId = id(703)
    val policyId = id(704)
    val captureId = id(705)
    val rightsId = id(706)
    val recordOperation = OperationId(id(602).value)
    val appendOperation = OperationId(id(603).value)
    val qualifyOperation = OperationId(id(604).value)
    val receivedAt = instant("2026-10-20T14:27:18.125Z")
    val recordedAt = instant("2026-11-03T14:27:18.124Z")
    val lateRecordAt = instant("2026-11-03T14:27:18.126Z")
    val claimedAt = instant("2026-11-03T16:10:00.000+02:00")
    val appendAt = instant("2026-11-05T08:01:10.500Z")
    val qualifyAt = instant("2026-11-06T09:00:00.000Z")
    const val inspectionLiteral = "2026-11-05T10:00:00.987+02:00"
    val inspectionAt = instant("2026-11-05T08:00:00.987Z")
    const val narrative = "The size feels different. I request a return; staff have not inspected it yet."
    const val assertedCondition = "Unworn; customer assertion only, not employee inspection"
    val quantity = Quantity(unit, 2, 0)
    val inspection get() = LocalReturnOrdinaryInspectionV1(LocalReturnInspectionConditionV1.UNCHANGED,
        LocalCashEvidenceV1(inspectionId, inspectionAt), inspectionLiteral)

    @OptIn(ExperimentalSerializationApi::class)
    val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }

    fun facts(reason: LocalReturnClaimReasonV1? = LocalReturnClaimReasonV1.CHANGE_OF_MIND,
        remedy: LocalReturnRequestedRemedyV1 = LocalReturnRequestedRemedyV1.REFUND) = LocalReturnCaseFactsV1(
        LocalReturnClaimFactsV1(sale, portion, quantity, null, claimedAt, narrative,
            LocalCashEvidenceV1(claimEvidenceId, claimedAt)), assertedCondition, remedy, "Africa/Cairo", reason)

    fun pendingView(recordTime: UtcInstant = recordedAt, revision: Revision = Revision(1),
        facts: LocalReturnCaseFactsV1 = facts()): LocalReturnCaseViewV1 {
        val binding = LocalReturnCaseReceiptBindingV1(sale, portion, quantity, LocalCashEvidenceV1(claimEvidenceId, claimedAt))
        return LocalReturnCaseViewV1(LocalReturnCaseSummaryV1(caseId, revision, LocalReturnCaseStageV1.PENDING,
            recordTime, sale, portion, null, null, null, null, null), recordOperation, facts, binding,
            null, null, (revision.value + 1L).toInt())
    }

    fun assessedView(late: Boolean = false, documentary: Boolean = false): LocalReturnCaseViewV1 {
        val submitted = if (late) lateRecordAt else recordedAt
        val outcome = if (late) LocalReturnOrdinaryAssessmentV1.PENDING else LocalReturnOrdinaryAssessmentV1.ELIGIBLE
        val timing = LocalReturnOrdinaryTimingV1.observe("ordinary_clothing_unchanged", 14, 14, receiptId,
            receivedAt, "Africa/Cairo", recordOperation, submitted)
        val decision = LocalReturnCaseDecisionViewV1(qualifyOperation, outcome, "ORDINARY_REVIEW", "CONTINUING_RIGHTS_REVIEW",
            policyId, Revision(1), captureId, listOf(rightsId), listOf(receiptId), receivedAt, "Africa/Cairo", null,
            originalCommercialTiming = if (documentary) LocalReturnCommercialTimingV1.observe(14, receiptId,
                receivedAt, "Africa/Cairo", recordOperation, submitted) else null,
            ordinaryTiming = timing, ordinaryInspection = inspection)
        val base = pendingView(submitted, Revision(2))
        return base.copy(summary = base.summary.copy(stage = if (late) LocalReturnCaseStageV1.PENDING
            else LocalReturnCaseStageV1.BOUND_UNACCEPTED, decisionOperationId = qualifyOperation,
            ordinaryAssessment = outcome, route = decision.route), qualifiedClaimRevision = if (late) null else Revision(0),
            decision = decision)
    }

    fun receipt(revision: Revision = Revision(7)) = LocalReturnCaseReadResultV1.Receipt(sale, revision,
        listOf(LocalReturnReceiptLineV1(portion, Quantity(unit, 6, 0), Quantity(unit, 5, 0), Money(Currency.EGP, 70_452))), null)

    fun view(): LocalSessionView {
        val permissions = (listOf(LocalAction.RETURN_CASE_RECORD, LocalAction.RETURN_CASE_RECEIPT,
            LocalAction.RETURN_CASE_QUALIFY, LocalAction.RETURN_CASE_ACCEPT).map { checkNotNull(actionPermission(it)) } +
            listOf(Permission(SecurityAction.RETURN_CLAIM, PermissionVerb.PERFORM),
                Permission(SecurityAction.RETURN_RECEIPT_LOOKUP, PermissionVerb.PERFORM))).distinct()
        val boot = id(707)
        return LocalSessionView(owner, scope, device, id(708), SessionGeneration(1), LocalLoginIdentifier("return-owner"),
            ActivatedLocalPolicy(scope, owner, Revision(1), permissions.map { PermissionGrant(it, scope, GrantEffect.ALLOW) },
                LocalSessionPolicy(), ThrottlePolicy()), MonotonicMoment(boot, 1_000), MonotonicMoment(boot, 2_000))
    }

    fun resultBody(value: LocalReturnCaseResultV1) = StoredBytes(json.encodeToString(
        LocalReturnCaseResultV1.serializer(), value).encodeToByteArray())
}

/** Exercises the actual shared gateway. Replies and DURABLE acknowledgements below
 * are controlled DATA, not native admission, authority, persistence or original history. */
internal class OrdinaryReturnGatewayFixture {
    val input = OrdinaryReturnGatewayInputs
    val endpoint = OrdinaryReturnControlledEndpoint()
    val journal = OrdinaryReturnControlledJournal(LocalClientIdentity(input.scope, input.owner, input.device))
    fun gateway() = LocalEndpointWorkflowGateway(endpoint, journal, LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(LocalGatewayCredentials(input.device, OpaqueCredential(ByteArray(16)))) { true }
    }, LocalWorkflowInstallation(setOf(LocalReturnCaseProfileV1.COMMAND), setOf(LocalReturnCaseReadCodecV1.QUERY)))
    val gateway = gateway()

    fun query(kind: LocalRecordKind = LocalRecordKind.RETURN_CASE) = LocalWorkflowQuery(LocalWorkflow.RETURNS,
        reference = LocalRecordReference(kind, input.caseId))

    fun intent(form: LocalActionForm, operation: OperationId = input.qualifyOperation,
        values: Map<LocalField, LocalScalarValue> = emptyMap(), reference: LocalRecordReference? =
            LocalRecordReference(LocalRecordKind.RETURN_CASE, input.caseId)): LocalCommandIntent = LocalCommandIntent(
        operation, form.action, form.version, reference, form.fields.mapNotNull { field ->
            val scalar = field as LocalFormField.Scalar
            (values[field.field] ?: scalar.initialValue)?.let { LocalScalarInput(field.field, it) }
        })

    fun inspectionValues() = mapOf(
        LocalField.ORDINARY_INSPECTION_CONDITION to LocalScalarValue.Choice("UNCHANGED"),
        LocalField.ORDINARY_INSPECTION_EVIDENCE_ID to LocalScalarValue.Identifier(input.inspectionId),
        LocalField.ORDINARY_INSPECTION_OCCURRED_AT to LocalScalarValue.Text(input.inspectionLiteral))

    fun recordValues(reason: LocalReturnClaimReasonV1? = LocalReturnClaimReasonV1.CHANGE_OF_MIND): Map<LocalField, LocalScalarValue> =
        buildMap {
            put(LocalField.CASE_ID, LocalScalarValue.Identifier(input.caseId))
            put(LocalField.SALE_ID, LocalScalarValue.Identifier(input.sale))
            put(LocalField.PORTION_ID, LocalScalarValue.Identifier(input.portion))
            put(LocalField.UNIT, LocalScalarValue.Identifier(input.unit))
            put(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(0))
            put(LocalField.QUANTITY, LocalScalarValue.Decimal("2"))
            put(LocalField.CLAIMED_AT, LocalScalarValue.Text(input.claimedAt.canonicalString()))
            put(LocalField.CLAIM_TIME_ZONE, LocalScalarValue.Text("Africa/Cairo"))
            put(LocalField.REASON, LocalScalarValue.Text(input.narrative))
            put(LocalField.CONDITION, LocalScalarValue.Text(input.assertedCondition))
            put(LocalField.REQUESTED_REMEDY, LocalScalarValue.Choice("REFUND"))
            put(LocalField.EVIDENCE_ID, LocalScalarValue.Identifier(input.claimEvidenceId))
            put(LocalField.OCCURRED_AT, LocalScalarValue.Text(input.claimedAt.canonicalString()))
            if (reason != null) put(LocalField.CLAIMED_REASON, LocalScalarValue.Choice(reason.name))
        }
}

/** Public carrier coordinates are intentionally not a protected Master fixture. */
internal class OrdinaryReturnControlledEndpoint : LocalMasterClient {
    private val input = OrdinaryReturnGatewayInputs
    var caseValue = input.pendingView()
    var receiptValue = input.receipt()
    val reads = mutableListOf<ReadRequest>()
    val executed = mutableListOf<MutationRequest>()
    val lookedUp = mutableListOf<OperationIdentity>()
    var onExecute: suspend (MutationRequest) -> MutationReply = { MutationReply.Deferred(it.operationId, DeferredReason.UNKNOWN_COMMAND) }
    var onLookup: suspend (OperationIdentity) -> LookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("No native runtime health bound")
    override suspend fun current(credentials: LocalGatewayCredentials) = LocalSessionViewResult.Current(input.view())
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        check(request.query == LocalReturnCaseReadCodecV1.QUERY)
        val query = input.json.decodeFromString(LocalReturnCaseQueryV1.serializer(), request.payload.copyBytes().decodeToString())
        val value: LocalReturnCaseReadResultV1 = when (query) {
            is LocalReturnCaseQueryV1.Case -> { check(query.caseId == input.caseId); LocalReturnCaseReadResultV1.Case(caseValue) }
            is LocalReturnCaseQueryV1.Receipt -> { check(query.saleId == input.sale); receiptValue }
            is LocalReturnCaseQueryV1.OwnWork -> LocalReturnCaseReadResultV1.Work(null, emptyList(), null)
            is LocalReturnCaseQueryV1.Events -> {
                check(query.caseId == input.caseId)
                val events = listOf(
                    LocalReturnCaseEventViewV1(0, input.recordOperation, caseValue.summary.recordedAt,
                        LocalReturnCaseActionV1.RECORDED, caseValue.binding, null),
                    LocalReturnCaseEventViewV1(1, input.appendOperation, input.appendAt,
                        LocalReturnCaseActionV1.EVIDENCE_APPENDED, caseValue.binding, null),
                    LocalReturnCaseEventViewV1(2, input.qualifyOperation, input.qualifyAt,
                        LocalReturnCaseActionV1.ASSESSMENT_PENDING, caseValue.binding, caseValue.decision))
                    .filter { it.ordinal > query.afterOrdinal }.take(query.limit)
                LocalReturnCaseReadResultV1.Events(input.caseId, caseValue.summary.revision, events, null)
            }
        }
        return ReadReply.Data(StoredBytes(input.json.encodeToString(LocalReturnCaseReadResultV1.serializer(), value)
            .encodeToByteArray()), Revision(1), input.qualifyAt)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executed += request; return onExecute(request)
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookedUp += identity; return onLookup(identity)
    }
    fun recorded(operation: OperationId, result: LocalReturnCaseResultV1, committed: Boolean = true) = MutationReply.Recorded(
        operation, input.owner, committed, if (committed) "sales.return_case.applied" else "sales.return_case.refused",
        input.resultBody(result), Revision(4), JournalSequence(1), input.qualifyAt, replayed = false)
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = error("No native authentication bound")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("No native refresh bound")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("No native logout bound")
}

/** In-memory client seam, with explicit counters for preflight-before-retention/ACK. */
internal class OrdinaryReturnControlledJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    var entry: StoredClientIntentV1? = null
    var stageCalls = 0
    var rememberCalls = 0
    var acknowledgeCalls = 0
    override suspend fun find(operationId: OperationId) = JournalRead.Value(entry?.takeIf { it.operationId == operationId })
    override suspend fun pending() = JournalRead.Value(listOfNotNull(entry?.takeUnless { it.acknowledged }))
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        stageCalls++; check(entry == null); entry = intent; return JournalWrite.DURABLE
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite {
        check(entry?.operationId == operationId)
        entry = checkNotNull(entry).copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED.name)
        return JournalWrite.DURABLE
    }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        rememberCalls++; check(entry?.operationId == operationId)
        entry = checkNotNull(entry).copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = result)
        return JournalWrite.DURABLE
    }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        acknowledgeCalls++; check(entry?.operationId == operationId && entry?.recorded == result)
        entry = checkNotNull(entry).copy(acknowledged = true); return JournalWrite.DURABLE
    }
    override suspend fun close() = Unit
}
