@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.sales.store.independent.ordinaryreturnqualification

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer
import kotlin.test.*
import kotlin.time.Instant

/** RQ-009/014/016/031/032 DATA support through actual shared production helpers.
 * No writer, accepting source provider, history provider or authority is constructed. */
class OrdinaryReturnSharedProofFrozenTest {
    private fun id(suffix: Int) = DomainId("00000000-0000-4000-8000-${suffix.toString().padStart(12, '0')}")
    private fun instant(literal: String) = UtcInstant(Instant.parse(literal))
    private val scope = BranchScope(id(101), id(201), id(301))
    private val owner = id(402)
    private val caseId = id(740)
    private val saleId = id(501)
    private val portionId = id(502)
    private val receiptId = id(503)
    private val recordOperation = OperationId(id(602).value)
    private val qualifyOperation = OperationId(id(604).value)
    private val recordedAt = instant("2026-11-03T14:27:18.124Z")
    private val qualifiedAt = instant("2026-11-06T09:00:00.000Z")
    private val claimedAt = instant("2026-11-03T16:10:00.000+02:00")
    private val quantity = Quantity(id(741), 2, 0)
    private val evidence = LocalCashEvidenceV1(id(742), claimedAt)
    private val facts = LocalReturnCaseFactsV1(LocalReturnClaimFactsV1(saleId, portionId, quantity, null,
        claimedAt, "The size feels different. I request a return; staff have not inspected it yet.", evidence),
        "Unworn; customer assertion only, not employee inspection", LocalReturnRequestedRemedyV1.REFUND, "Africa/Cairo",
        LocalReturnClaimReasonV1.CHANGE_OF_MIND)
    private val inspection = LocalReturnOrdinaryInspectionV1(LocalReturnInspectionConditionV1.UNCHANGED,
        LocalCashEvidenceV1(id(504), instant("2026-11-05T08:00:00.987Z")), "2026-11-05T10:00:00.987+02:00")

    @Test
    fun rq014_rq016_rq031_missingSourcesKeepSharedProposalPendingAndRetainLiteralInspection() {
        val cut = recordData()
        val request = LocalReturnCaseRequestV1.Qualify(caseId, cut.row.value.revision, ordinaryInspection = inspection)
        val production = returnCaseUnavailableQualificationProduction(identity(qualifyOperation, request), request, cut, original = null)
        val outcome = assertIs<ProposedOutcome.Apply>(production.propose(qualifiedAt))
        val result = assertIs<LocalReturnCaseResultV1.Applied>(LocalReturnCaseCommandCodecV1.decodeResult(outcome.result))
        assertEquals(LocalReturnCaseActionV1.ASSESSMENT_PENDING, result.action)
        assertEquals(LocalReturnCaseStageV1.PENDING, result.stage)
        assertNull(result.claimRevision)
        assertNull(result.entitlementRevision)
        assertNull(result.returnId)
        assertNull(result.originalPayable)
        val event = localSalesDecode(outcome.changes.single { it.key == LocalReturnCaseProfileV1.key(
            LocalReturnCaseProfileV1.EVENT, DomainId(qualifyOperation.value)) }.payload, LocalReturnCaseEventV1.serializer())
        assertEquals(recordOperation, event.after.originalOperationId)
        assertEquals(recordedAt, event.after.recordedAt)
        assertEquals(qualifiedAt, event.recordedAt)
        assertEquals(facts, event.after.facts)
        assertEquals(inspection, event.ordinaryInspection)
        assertEquals("2026-11-05T10:00:00.987+02:00", event.ordinaryInspection?.reportedOccurredAt)
        assertEquals(LocalReturnOrdinaryAssessmentV1.PENDING, event.assessment?.outcome)
        assertNull(event.assessment?.ordinaryTiming)
        assertTrue(event.qualificationSources.isEmpty())
        assertTrue(outcome.changes.all { it.key.kind.startsWith("sales.return.case") })
        assertEquals(listOf(ClaimChange.Acquire(LocalReturnCaseProfileV1.source("event", DomainId(qualifyOperation.value)), caseId)),
            outcome.claims)
        assertTrue(outcome.jobs.isEmpty())
        assertEquals(facts, cut.row.value.facts)
        // Changes are inert proposals; no native original/history, commit or protected read is claimed.
    }

    @Test
    fun rq014_rq016_rq032_sharedOrdinaryProofGuardCannotBindIncompleteEligibleData() {
        val cut = recordData()
        val binding = assertNotNull(cut.binding?.value?.binding)
        val request = LocalReturnCaseRequestV1.Qualify(caseId, cut.row.value.revision, Revision(7), ordinaryInspection = inspection)
        val receiptAt = instant("2026-10-20T14:27:18.125Z")
        val timing = LocalReturnOrdinaryTimingV1.observe("ordinary_clothing_unchanged", 14, 14, receiptId, receiptAt,
            "Africa/Cairo", recordOperation, recordedAt)
        // Opaque references satisfy DTO shape only. They are never loaded or treated as source proof.
        val offered = LocalReturnCaseAssessmentV1(LocalReturnOrdinaryAssessmentV1.ELIGIBLE, "ORDINARY_REVIEW", "ORDINARY_REVIEW",
            policyId = id(743), policyRevision = Revision(1), salePolicyCapture = reference(744),
            applicableRights = listOf(reference(745)), customerReceipts = listOf(reference(503)),
            customerReceivedAt = receiptAt, receiptTimeZoneId = "Africa/Cairo")
        assertFailsWith<LocalSalesSourceUnavailable> {
            requireReturnOrdinaryTiming(offered, request, cut.row.value, binding, null, null, emptyMap())
        }
        assertFailsWith<LocalSalesSourceUnavailable> {
            requireReturnOrdinaryTiming(offered.copy(ordinaryTiming = timing), request.copy(ordinaryInspection = null),
                cut.row.value, binding, null, null, emptyMap())
        }
        assertFailsWith<LocalSalesSourceUnavailable> {
            requireReturnOrdinaryTiming(offered.copy(ordinaryTiming = timing), request, cut.row.value.copy(
                facts = facts.copy(claimedReason = null)), binding, null, null, emptyMap())
        }
        assertFailsWith<LocalSalesSourceUnavailable> {
            requireReturnOrdinaryTiming(offered.copy(ordinaryTiming = timing), request, cut.row.value,
                binding, capture = null, sold = quantity, receipts = emptyMap())
        }
        assertEquals(facts, cut.row.value.facts)
        assertEquals(LocalReturnCaseStageV1.PENDING, cut.row.value.stage)
    }

    /** Record/evidence values are materialized in memory from the actual pure producer.
     * These rows are DATA and provide no native BEFORE or retained-command proof. */
    private fun recordData(): LocalReturnCaseCut {
        val request = LocalReturnCaseRequestV1.Record(caseId, facts)
        val proposal = assertIs<ProposedOutcome.Apply>(returnCaseRecordProduction(identity(recordOperation, request), request,
            work = null, dependencies = emptySet(), duplicate = false).propose(recordedAt))
        val rows = proposal.changes.associate { change -> change.key to StoredAggregate(change.key, Revision(1), change.schema,
            change.payload, sha256(change.payload.copyBytes())) }
        fun <T> row(kind: String, id: DomainId, serializer: KSerializer<T>): LocalSalesRow<T> =
            rows.getValue(LocalReturnCaseProfileV1.key(kind, id)).let { LocalSalesRow(it, localSalesDecode(it.payload, serializer)) }
        val state = row(LocalReturnCaseProfileV1.CASE, caseId, LocalReturnCaseStateV1.serializer())
        val event = row(LocalReturnCaseProfileV1.EVENT, DomainId(recordOperation.value), LocalReturnCaseEventV1.serializer())
        val work = row(LocalReturnCaseProfileV1.WORK, owner, LocalReturnCaseWorkV1.serializer())
        return LocalReturnCaseCut(state, event, work, event, null, emptySet())
    }

    private fun identity(operation: OperationId, request: LocalReturnCaseRequestV1) = LocalReturnCaseProductionIdentity(
        scope, owner, operation, semanticDigest(scope, owner, LocalReturnCaseProfileV1.COMMAND, LocalReturnCaseCommandCodecV1.encode(request)))
    private fun reference(suffix: Int) = LocalReturnCaseSourceReferenceV1("ordinary.return.data", id(suffix), "ordinary.return.data/v1",
        Revision(1), sha256("Opaque shape coordinate $suffix".encodeToByteArray()), OperationId(id(suffix).value), owner,
        "sales.return.case.write", 1)
}
