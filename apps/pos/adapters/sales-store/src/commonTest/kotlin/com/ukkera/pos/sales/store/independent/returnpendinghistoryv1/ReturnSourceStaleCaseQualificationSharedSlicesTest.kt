@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.sales.store.independent.returnpendinghistoryv1

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.RetainedAggregateWrite
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.store.LocalCashEvidenceV1
import com.ukkera.pos.sales.store.LocalReturnCaseAssessmentV1
import com.ukkera.pos.sales.store.LocalReturnCaseCommandCodecV1
import com.ukkera.pos.sales.store.LocalReturnCaseCut
import com.ukkera.pos.sales.store.LocalReturnCaseEventV1
import com.ukkera.pos.sales.store.LocalReturnCaseFactsV1
import com.ukkera.pos.sales.store.LocalReturnCaseProductionIdentity
import com.ukkera.pos.sales.store.LocalReturnCaseProfileV1
import com.ukkera.pos.sales.store.LocalReturnCaseReceiptBindingV1
import com.ukkera.pos.sales.store.LocalReturnCaseRequestV1
import com.ukkera.pos.sales.store.LocalReturnCaseResultV1
import com.ukkera.pos.sales.store.LocalReturnCaseSourceQualificationEvaluation
import com.ukkera.pos.sales.store.LocalReturnCaseSourceReferenceV1
import com.ukkera.pos.sales.store.LocalReturnCaseStageV1
import com.ukkera.pos.sales.store.LocalReturnCaseStateV1
import com.ukkera.pos.sales.store.LocalReturnCaseWorkV1
import com.ukkera.pos.sales.store.LocalReturnClaimFactsV1
import com.ukkera.pos.sales.store.LocalReturnRequestedRemedyV1
import com.ukkera.pos.sales.store.LocalSalesRow
import com.ukkera.pos.sales.store.PreparedSalesReturnCaseSourceV1
import com.ukkera.pos.sales.store.independent.returnfactualsourcepending.FrozenReturnFactualSourceInputs
import com.ukkera.pos.sales.store.localSalesDecode
import com.ukkera.pos.sales.store.localSalesEncode
import com.ukkera.pos.sales.store.returnCaseAppendProduction
import com.ukkera.pos.sales.store.returnCaseHistorySourceStaleQualification
import com.ukkera.pos.sales.store.returnCaseRecordProduction
import com.ukkera.pos.sales.store.returnCaseSourceQualificationProduction
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shared CAS slices of frozen RPH-14 and RFSP-09/v3, with RFSP-12 original-data
 * preservation restrictions. R_INTAKE and R_EVIDENCE are captured from actual
 * shared RECORD/APPEND proposals, not fixed revision or increment expectations.
 * Their unchanged schemas/payloads become inert row DATA, not durable commits.
 *
 * RPH G4 leaves the exact stale-CASE refusal code unspecified. The shared slice
 * asserts rejection before assessment and unchanged CASE/event/work/request data;
 * it does not prescribe a new code or measure durable business-effect balances.
 * Supplementary retained-profile vectors use that captured rejection, not an
 * independently invented result golden. A matching DATA predicate is not native
 * history acceptance, a complete producer join, or proof of absent effects.
 *
 * The controlled source never prepares or returns an assessment. A null original
 * isolates the CASE guard; it does not prove receipt absence. Revision zero in
 * the DATA predicate is only its initial-entitlement profile discriminant. Genuine
 * entitlement 7, F-CASE revision 11, signed sources, original/current authorization,
 * native history, persistence, admitted RPC, retry/restart and whole RPH/RFSP cases
 * remain unbound. No security, admission, session or native capability is built.
 */
class ReturnSourceStaleCaseQualificationSharedSlicesTest {
    @Test
    fun rph14_staleIntakeRevisionAfterAppend_rejectsBeforeAssessmentSharedOnly() {
        val data = SourceStaleCaseDataFixture()
        val source = UncalledQualificationSource(data.scope)
        val before = data.snapshot()
        val requestBefore = LocalReturnCaseCommandCodecV1.encode(data.request).copyBytes()

        assertEquals(data.intakeRevision, data.recordCut.row.value.revision)
        assertEquals(data.evidenceRevision, data.before.row.value.revision)
        assertNotEquals(data.intakeRevision, data.evidenceRevision)
        assertEquals(data.appendIdentity.operationId, data.before.row.value.bindingOperationId)
        assertEquals(data.recordRequest.facts, data.before.row.value.facts)
        assertEquals(LocalReturnCaseStageV1.PENDING, data.before.row.value.stage)
        assertNull(data.before.row.value.acceptedReturnId)

        val rejection = data.reject(source)

        assertIs<LocalReturnCaseResultV1.Refused>(LocalReturnCaseCommandCodecV1.decodeResult(rejection.result))
        assertEquals(0, source.assessmentCalls)
        assertEquals(before, data.snapshot())
        assertContentEquals(requestBefore, LocalReturnCaseCommandCodecV1.encode(data.request).copyBytes())
        // Reject has no aggregate/claim/job proposal fields. No store is executed.
    }

    @Test
    fun rfsp12_repeatedStaleQualificationPureEvaluation_preservesCapturedResultAndIdentity() {
        val data = SourceStaleCaseDataFixture()
        val source = UncalledQualificationSource(data.scope)
        val before = data.snapshot()
        val first = data.reject(source)
        val original = data.retained(first)
        val originalBody = original.normalized.copyBytes()
        val originalResult = original.result.copyBytes()
        val originalIdentity = data.qualificationIdentity

        repeat(2) {
            val repeated = data.reject(source)
            assertEquals(first.code, repeated.code)
            assertContentEquals(originalResult, repeated.result.copyBytes())
        }

        assertEquals(0, source.assessmentCalls)
        assertEquals(before, data.snapshot())
        assertEquals(originalIdentity, data.qualificationIdentity)
        assertEquals(originalIdentity.operationId, original.operationId)
        assertEquals(originalIdentity.actorId, original.originalActorId)
        assertEquals(originalIdentity.semanticDigest, original.semanticDigest)
        assertContentEquals(originalBody, original.normalized.copyBytes())
        assertContentEquals(originalResult, original.result.copyBytes())
        // Repeated pure evaluation, not duplicate delivery or durable retry/replay.
    }

    @Test
    fun staleCaseHistoryProfile_capturedSharedRefusal_matchesInertDataOnly() {
        val data = SourceStaleCaseDataFixture()
        val original = data.retained(data.reject(UncalledQualificationSource(data.scope)))
        val bodyBefore = original.normalized.copyBytes()
        val resultBefore = original.result.copyBytes()

        assertFalse(original.committed)
        assertNull(original.nativeOriginalProvenance)
        assertTrue(original.aggregateWrites.isEmpty())
        assertTrue(original.claimChanges.isEmpty())
        assertTrue(original.jobs.isEmpty())
        assertTrue(data.matches(original))

        assertContentEquals(bodyBefore, original.normalized.copyBytes())
        assertContentEquals(resultBefore, original.result.copyBytes())
        // Canonical producer/predicate agreement only; no independent code oracle.
    }

    @Test
    fun rfsp12_changedOriginalBodyOrActor_cannotMatchExactProfileInertDataOnly() {
        val data = SourceStaleCaseDataFixture()
        val rejection = data.reject(UncalledQualificationSource(data.scope))
        val original = data.retained(rejection)
        val variants = listOf(
            data.request.copy(caseId = data.otherCaseId),
            data.request.copy(expectedEntitlementRevision = null),
            data.request.copy(recordOriginalCommercialTiming = false),
        )

        for (changedRequest in variants) {
            val changedBody = LocalReturnCaseCommandCodecV1.encode(changedRequest)
            assertNotEquals(original.normalized, changedBody)
            assertFalse(data.matches(original, changedRequest))
            assertFalse(data.matches(data.retained(rejection, normalized = changedBody)))
        }
        val changedActor = data.retained(rejection, actorId = data.otherActorId)
        assertNotEquals(original.semanticDigest, changedActor.semanticDigest)
        assertFalse(data.matches(changedActor))
        assertEquals(data.qualificationIdentity.operationId, original.operationId)
        assertEquals(data.qualificationIdentity.actorId, original.originalActorId)
        assertEquals(LocalReturnCaseCommandCodecV1.encode(data.request), original.normalized)
        assertEquals(rejection.result, original.result)
        // No current-access denial or kernel operation-identity conflict is exercised.
    }

    @Test
    fun staleCaseHistoryProfile_changedCapturedResultOrCode_cannotMatchInertDataOnly() {
        val data = SourceStaleCaseDataFixture()
        val rejection = data.reject(UncalledQualificationSource(data.scope))
        val resultBytes = rejection.result.copyBytes()
        val variants = listOf(
            data.retained(rejection, result = data.recordProposal.result),
            data.retained(rejection, result = StoredBytes(resultBytes.copyOf(resultBytes.size - 1))),
            data.retained(rejection, code = "committed"),
        )

        for (changed in variants) {
            assertEquals(sha256(changed.result.copyBytes()), changed.resultHash)
            assertFalse(data.matches(changed))
        }
        assertContentEquals(resultBytes, rejection.result.copyBytes())
        // The changed committed label is DATA, not a fabricated successful commit.
    }

    @Test
    fun rph14_addedCaseEventOrWorkWrite_cannotMatchEffectFreeProfileInertDataOnly() {
        val data = SourceStaleCaseDataFixture()
        val rejection = data.reject(UncalledQualificationSource(data.scope))
        val before = data.snapshot()

        for (write in data.appendWrites()) {
            assertFalse(data.matches(data.retained(rejection, writes = listOf(write))), write.key.kind)
        }

        assertEquals(before, data.snapshot())
        assertTrue(data.retained(rejection).aggregateWrites.isEmpty())
        // These are unchanged proposal payloads added as retained-write DATA only.
    }

    @Test
    fun staleCaseHistoryProfile_nonstaleOrNoninitialExpectations_remainOutsideInertProfile() {
        val data = SourceStaleCaseDataFixture()
        val rejection = data.reject(UncalledQualificationSource(data.scope))
        val currentCaseRequest = data.request.copy(expectedCaseRevision = data.evidenceRevision)
        val omittedRequest = data.request.copy(expectedEntitlementRevision = null)
        val noninitialRequest = data.request.copy(expectedEntitlementRevision = Revision(7))

        for (request in listOf(currentCaseRequest, omittedRequest)) {
            val original = data.retained(rejection, normalized = LocalReturnCaseCommandCodecV1.encode(request))
            assertFalse(data.matches(original, request))
        }
        val noninitial = data.retained(rejection, normalized = LocalReturnCaseCommandCodecV1.encode(noninitialRequest))
        assertFalse(data.matches(noninitial, noninitialRequest, entitlementRevision = Revision(7)))
        // Scalar profile exclusions, not RFSP-09's genuine observed-7 transaction.
    }
}

/** A guard sentinel only: no assessment, signed source or trusted prepare is supplied. */
private class UncalledQualificationSource(override val scope: BranchScope) : PreparedSalesReturnCaseSourceV1 {
    override val recoveryResources: Set<RecoveryResourceKey> = emptySet()
    override val sourceReferences: List<LocalReturnCaseSourceReferenceV1> = emptyList()
    var assessmentCalls: Int = 0
        private set

    override fun assess(recordedAt: UtcInstant): LocalReturnCaseAssessmentV1 {
        assessmentCalls += 1
        error("Stale CASE shared evaluation must not assess")
    }
}

/**
 * Visibility suppressions construct only inert StoredAggregate/retained evidence/
 * retained-write DATA. Scope/case/qualification/other-actor IDs and quantity/time
 * literals project RFSP inputs; reason/condition project RPH CLAIM_UNBOUND.
 * Record/append/unit/evidence/assignment/authentication IDs and zero bookkeeping
 * metadata are unasserted shape fillers. The unit ID does not authenticate EA.
 */
private class SourceStaleCaseDataFixture {
    private val frozen = FrozenReturnFactualSourceInputs
    private val rows = linkedMapOf<AggregateKey, StoredAggregate>()
    val scope: BranchScope = frozen.scope
    val actorId: DomainId = frozen.collectorId
    val otherActorId: DomainId = DomainId("a5300000-0000-4000-8000-000000000011")
    val caseId: DomainId = DomainId("c1530000-0000-4000-8000-000000000001")
    val otherCaseId: DomainId = inertId(9)
    private val evidence = LocalCashEvidenceV1(inertId(4), frozen.recordedAt(0))
    private val requestedPortion = frozen.request(0).getValue("portions").jsonArray.single().jsonObject
    private val soldPortion = frozen.sale.getValue("portions").jsonArray.single {
        it.jsonObject.getValue("portionId").jsonPrimitive.content == frozen.id("portionA").value
    }.jsonObject
    private val quantity = Quantity.parse(inertId(3), requestedPortion.getValue("quantity").jsonPrimitive.content,
        soldPortion.getValue("precision").jsonPrimitive.int)
    val recordRequest = LocalReturnCaseRequestV1.Record(caseId, LocalReturnCaseFactsV1(
        LocalReturnClaimFactsV1(null, null, null, frozen.receivedAt(0), frozen.recordedAt(0),
            "DEFECTIVE_OR_DAMAGED_ITEM", evidence),
        "ASSERTED_DEFECT", LocalReturnRequestedRemedyV1.REFUND,
        frozen.request(0).getValue("receiptTimeZoneId").jsonPrimitive.content))
    private val recordIdentity = identity(OperationId(inertId(1).value), recordRequest)
    val recordProposal: ProposedOutcome.Apply = assertIs<ProposedOutcome.Apply>(returnCaseRecordProduction(recordIdentity, recordRequest,
        work = null, dependencies = emptySet(), duplicate = false).propose(frozen.recordedAt(0)))

    init { materialize(recordProposal) }

    val recordCut: LocalReturnCaseCut = cut(recordIdentity.operationId)
    val intakeRevision: Revision = assertIs<LocalReturnCaseResultV1.Applied>(
        LocalReturnCaseCommandCodecV1.decodeResult(recordProposal.result)).caseRevision
    private val binding = LocalReturnCaseReceiptBindingV1(frozen.saleId, frozen.id("portionA"), quantity, evidence)
    private val appendRequest = LocalReturnCaseRequestV1.AppendReceiptEvidence(caseId, intakeRevision, binding)
    val appendIdentity: LocalReturnCaseProductionIdentity = identity(OperationId(inertId(2).value), appendRequest)
    private val appendProposal: ProposedOutcome.Apply = assertIs<ProposedOutcome.Apply>(returnCaseAppendProduction(appendIdentity, appendRequest,
        recordCut).propose(frozen.recordedAt(1)))

    init { materialize(appendProposal) }

    val before: LocalReturnCaseCut = cut(appendIdentity.operationId)
    val evidenceRevision: Revision = assertIs<LocalReturnCaseResultV1.Applied>(
        LocalReturnCaseCommandCodecV1.decodeResult(appendProposal.result)).caseRevision
    private val initialEntitlement = Revision(0)
    val request = LocalReturnCaseRequestV1.Qualify(caseId, intakeRevision, initialEntitlement,
        recordOriginalCommercialTiming = true)
    val qualificationIdentity: LocalReturnCaseProductionIdentity =
        identity(OperationId("a5300000-0000-4000-8000-000000000401"), request)

    fun reject(source: UncalledQualificationSource): ProposedOutcome.Reject {
        val evaluation = returnCaseSourceQualificationProduction(qualificationIdentity, request, before,
            original = null, prepared = source, references = source.sourceReferences,
            selectedPromises = emptyMap(), receiptEvents = emptyMap()).evaluate(frozen.recordedAt(1))
        return assertIs<ProposedOutcome.Reject>(assertIs<LocalReturnCaseSourceQualificationEvaluation.Completed>(evaluation).outcome)
    }

    fun matches(original: RetainedCommandEvidence, request: LocalReturnCaseRequestV1.Qualify = this.request,
        entitlementRevision: Revision = initialEntitlement): Boolean =
        returnCaseHistorySourceStaleQualification(original, request, before.row.value, entitlementRevision)

    fun retained(rejection: ProposedOutcome.Reject, normalized: StoredBytes = LocalReturnCaseCommandCodecV1.encode(request),
        actorId: DomainId = this.actorId, code: String = rejection.code, result: StoredBytes = rejection.result,
        writes: List<RetainedAggregateWrite> = emptyList()): RetainedCommandEvidence =
        RetainedCommandEvidence(scope, qualificationIdentity.operationId, actorId, LocalReturnCaseProfileV1.COMMAND,
            inertId(20), Revision(0), JournalSequence(0), frozen.recordedAt(1), inertId(21), Revision(0),
            semanticDigest(scope, actorId, LocalReturnCaseProfileV1.COMMAND, normalized), code, normalized, result,
            sha256(result.copyBytes()), sha256("inert previous journal bookkeeping".encodeToByteArray()),
            sha256("inert journal bookkeeping".encodeToByteArray()), writes, emptyList(), emptyList())

    fun appendWrites(): List<RetainedAggregateWrite> = appendProposal.changes.map { change ->
        val row = rows.getValue(change.key)
        RetainedAggregateWrite(row.key, row.revision, row.schema, row.payload, row.digest)
    }

    fun snapshot(): SharedSliceSnapshot = SharedSliceSnapshot(
        rows.values.sortedWith(compareBy({ it.key.kind }, { it.key.id.value })).map {
            RowDataSnapshot(it.key, it.revision, it.schema, StoredBytes(it.payload.copyBytes()), it.digest)
        },
        localSalesEncode(before.row.value, LocalReturnCaseStateV1.serializer()),
        localSalesEncode(before.event.value, LocalReturnCaseEventV1.serializer()),
        localSalesEncode(before.work.value, LocalReturnCaseWorkV1.serializer()),
        before.binding?.let { localSalesEncode(it.value, LocalReturnCaseEventV1.serializer()) },
        LocalReturnCaseCommandCodecV1.encode(recordRequest), LocalReturnCaseCommandCodecV1.encode(appendRequest),
        LocalReturnCaseCommandCodecV1.encode(request), recordProposal.result, appendProposal.result, qualificationIdentity)

    private fun identity(operationId: OperationId, request: LocalReturnCaseRequestV1): LocalReturnCaseProductionIdentity =
        LocalReturnCaseProductionIdentity(scope, actorId, operationId,
            semanticDigest(scope, actorId, LocalReturnCaseProfileV1.COMMAND, LocalReturnCaseCommandCodecV1.encode(request)))

    private fun materialize(proposal: ProposedOutcome.Apply) {
        for (change in proposal.changes) {
            assertEquals(rows[change.key]?.revision, change.expectedRevision)
            rows[change.key] = StoredAggregate(change.key, (change.expectedRevision ?: Revision(0)).next(),
                change.schema, change.payload, sha256(change.payload.copyBytes()))
        }
    }

    private fun cut(operationId: OperationId): LocalReturnCaseCut {
        val caseRow = row(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, caseId), LocalReturnCaseStateV1.serializer())
        val currentEvent = event(operationId)
        val work = row(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.WORK, actorId), LocalReturnCaseWorkV1.serializer())
        return LocalReturnCaseCut(caseRow, currentEvent, work, caseRow.value.bindingOperationId?.let(::event),
            caseRow.value.decisionOperationId?.let(::event), recoveryDependencies = emptySet())
    }

    private fun event(operationId: OperationId): LocalSalesRow<LocalReturnCaseEventV1> =
        row(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.EVENT, DomainId(operationId.value)),
            LocalReturnCaseEventV1.serializer())

    private fun <T> row(key: AggregateKey, serializer: KSerializer<T>): LocalSalesRow<T> {
        val stored = rows.getValue(key)
        return LocalSalesRow(stored, localSalesDecode(stored.payload, serializer))
    }

    private fun inertId(serial: Int): DomainId =
        DomainId("c15300f0-0000-4000-8000-${serial.toString().padStart(12, '0')}")
}

private data class RowDataSnapshot(val key: AggregateKey, val generation: Revision, val schema: String,
    val payload: StoredBytes, val digest: PayloadDigest)

private data class SharedSliceSnapshot(val rows: List<RowDataSnapshot>, val case: StoredBytes,
    val event: StoredBytes, val work: StoredBytes, val binding: StoredBytes?, val recordRequest: StoredBytes,
    val appendRequest: StoredBytes, val qualifyRequest: StoredBytes, val recordResult: StoredBytes,
    val appendResult: StoredBytes, val qualificationIdentity: LocalReturnCaseProductionIdentity)
