@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.app.gateway.independent.ordinaryreturnqualification

import com.ukkera.pos.app.*
import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

/** Frozen RQ v1 supporting gateway slices only. Controlled public reads and the
 * in-memory journal do not prove native sources, protected authority or restart. */
class OrdinaryReturnPreparationAndReadFrozenTest {
    private val input = OrdinaryReturnGatewayInputs

    @Test
    fun rq001_rq011_rq034_typedIntakeIsSeparateFromNarrativeAndLegacyOmission() = runTest {
        for (typed in listOf(LocalReturnClaimReasonV1.CHANGE_OF_MIND, null)) {
            val fixture = OrdinaryReturnGatewayFixture()
            val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(LocalWorkflowQuery(LocalWorkflow.RETURNS))).page
            val form = page.forms.single { it.action == LocalAction.RETURN_CASE_RECORD }
            val intent = fixture.intent(form, input.recordOperation, fixture.recordValues(typed), reference = null)
            assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
            val saved = assertNotNull(fixture.journal.entry)
            val request = assertIs<LocalReturnCaseRequestV1.Record>(LocalReturnCaseCommandCodecV1.decode(saved.request().payload))
            assertEquals(typed, request.facts.claimedReason)
            assertEquals(input.narrative, request.facts.asserted.reason)
            assertEquals(input.assertedCondition, request.facts.condition)
            assertEquals(LocalReturnRequestedRemedyV1.REFUND, request.facts.requestedRemedy)
            assertEquals(input.claimedAt, request.facts.asserted.claimedAt)
            val facts = input.json.parseToJsonElement(saved.request().payload.copyBytes().decodeToString()).jsonObject
                .getValue("facts").jsonObject
            assertEquals(typed != null, "claimedReason" in facts)
            assertEquals(input.owner, saved.owner.actorId)
            assertEquals(input.recordOperation, saved.operationId)
            assertTrue(fixture.endpoint.executed.isEmpty())
            assertTrue(fixture.endpoint.lookedUp.isEmpty())
        }
    }

    @Test
    fun rq001_rq015_inspectionPreparationRetainsExactLiteralAndIndependentUtc() = runTest {
        val fixture = OrdinaryReturnGatewayFixture()
        val form = qualifyForm(fixture)
        val intent = fixture.intent(form, values = fixture.inspectionValues() +
            (LocalField.RECORD_ORIGINAL_COMMERCIAL_TIMING to LocalScalarValue.Flag(true)))
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
        val saved = assertNotNull(fixture.journal.entry)
        val request = assertIs<LocalReturnCaseRequestV1.Qualify>(LocalReturnCaseCommandCodecV1.decode(saved.request().payload))
        val inspection = assertNotNull(request.ordinaryInspection)
        assertEquals(input.inspectionLiteral, inspection.reportedOccurredAt)
        assertEquals(input.inspectionAt, inspection.evidence.occurredAt)
        assertEquals(input.inspectionId, inspection.evidence.evidenceId)
        assertEquals(LocalReturnInspectionConditionV1.UNCHANGED, inspection.condition)
        assertTrue(request.recordOriginalCommercialTiming)
        assertEquals(fixture.endpoint.caseValue.summary.revision, request.expectedCaseRevision)
        assertEquals(Revision(7), request.expectedEntitlementRevision) // Public receipt DATA only.
        assertEquals(input.owner, saved.owner.actorId)
        assertTrue(saved.summary.any { it.value.english == input.inspectionLiteral })
        assertTrue(fixture.endpoint.executed.isEmpty())
        assertTrue(fixture.endpoint.lookedUp.isEmpty())
    }

    @Test
    fun rq014_partialInspectionCannotBeStagedAndCustomerConditionIsNotInspection() = runTest {
        for (missing in listOf(LocalField.ORDINARY_INSPECTION_CONDITION, LocalField.ORDINARY_INSPECTION_EVIDENCE_ID,
            LocalField.ORDINARY_INSPECTION_OCCURRED_AT)) {
            val fixture = OrdinaryReturnGatewayFixture()
            val intent = fixture.intent(qualifyForm(fixture), values = fixture.inspectionValues() - missing)
            assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(intent))
            assertNull(fixture.journal.entry)
            assertEquals(0, fixture.journal.stageCalls)
            assertTrue(fixture.endpoint.executed.isEmpty())
            assertEquals(input.assertedCondition, fixture.endpoint.caseValue.facts.condition)
        }
        val fixture = OrdinaryReturnGatewayFixture()
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(fixture.intent(qualifyForm(fixture))))
        val request = assertIs<LocalReturnCaseRequestV1.Qualify>(LocalReturnCaseCommandCodecV1.decode(
            assertNotNull(fixture.journal.entry).request().payload))
        assertNull(request.ordinaryInspection)
    }

    @Test
    fun rq004_rq028_rq035_originalInspectionRequestSurvivesChangedCurrentReadsAndUnsupportedReply() = runTest {
        val fixture = OrdinaryReturnGatewayFixture()
        val originalIntent = fixture.intent(qualifyForm(fixture), values = fixture.inspectionValues())
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(originalIntent))
        val original = assertNotNull(fixture.journal.entry)
        val body = original.request().payload
        val identity = original.identity()
        val changedLiteral = "2026-11-05T08:00:00.987Z" // Same instant, different retained original assertion.
        val changedIntent = LocalCommandIntent(originalIntent.operationId, originalIntent.action,
            originalIntent.formVersion, originalIntent.reference, originalIntent.values.map {
                if (it.field == LocalField.ORDINARY_INSPECTION_OCCURRED_AT)
                    LocalScalarInput(it.field, LocalScalarValue.Text(changedLiteral)) else it
            })
        assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(changedIntent))
        assertEquals(original, fixture.journal.entry)
        assertTrue(fixture.endpoint.executed.isEmpty())

        fixture.endpoint.caseValue = input.pendingView(revision = Revision(4))
        fixture.endpoint.receiptValue = input.receipt(Revision(9))
        fixture.endpoint.onLookup = { LookupReply.Deferred(it.operationId, DeferredReason.HISTORY_INCOMPLETE) }
        assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(original.operationId))
        assertEquals(body, assertNotNull(fixture.journal.entry).request().payload)
        fixture.endpoint.onLookup = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
        fixture.endpoint.onExecute = { MutationReply.Deferred(it.operationId, DeferredReason.UNKNOWN_COMMAND) }
        val reopenedGateway = fixture.gateway() // A new coordinator, not a native process/disk restart.
        assertIs<LocalOperationResult.Unresolved>(reopenedGateway.submit(original.operationId))
        assertEquals(1, fixture.endpoint.executed.size)
        assertEquals(body, fixture.endpoint.executed.single().payload)
        assertEquals(original.operationId, fixture.endpoint.executed.single().operationId)
        assertEquals(identity, assertNotNull(fixture.journal.entry).identity())
        assertEquals(input.inspectionLiteral, assertIs<LocalReturnCaseRequestV1.Qualify>(
            LocalReturnCaseCommandCodecV1.decode(fixture.endpoint.executed.single().payload)).ordinaryInspection?.reportedOccurredAt)
        assertEquals(1, fixture.journal.stageCalls)
        assertEquals(0, fixture.journal.rememberCalls)
        assertEquals(0, fixture.journal.acknowledgeCalls)
    }

    @Test
    fun rq003_rq004_rq031_latePendingIsReadableWithBothRetainedTimingSections() = runTest {
        val fixture = OrdinaryReturnGatewayFixture()
        fixture.endpoint.caseValue = input.assessedView(late = true, documentary = true)
        for (kind in listOf(LocalRecordKind.RETURN_CASE, LocalRecordKind.RETURN_CASE_HISTORY)) {
            val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query(kind))).page
            assertTrue(page.forms.none { it.action == LocalAction.RETURN_CASE_ACCEPT })
            assertTrue(page.records.all { it.values.size <= 32 })
            val decision = page.records.single { it.reference.kind == LocalRecordKind.RETURN_CASE_EVENT &&
                it.reference.id.value == input.qualifyOperation.value }
            val text = decision.values.joinToString("\n") { it.value.english }
            assertEquals(2, decision.values.count { it.value.english == LocalReturnCommercialWindowStatusV1.OUTSIDE.name })
            assertTrue(text.contains(input.inspectionLiteral))
            assertTrue(text.contains(input.inspectionId.value))
            assertTrue(text.contains(input.recordOperation.value))
            assertTrue(text.contains(input.lateRecordAt.canonicalString()))
            assertTrue(text.contains("2026-11-03T14:27:18.125Z"))
            assertTrue(text.contains(input.receiptId.value))
            assertTrue(text.contains(input.policyId.value))
            assertTrue(text.contains(input.captureId.value))
            assertTrue(text.contains(input.rightsId.value))
            assertTrue(text.contains("ordinary_clothing_unchanged"))
            assertTrue(decision.values.any { it.value.english.contains("14") && it.value.english.contains("\n") })
        }
        assertEquals(input.narrative, fixture.endpoint.caseValue.facts.asserted.reason)
        assertEquals(input.lateRecordAt, fixture.endpoint.caseValue.summary.recordedAt)
        assertNull(fixture.journal.entry)
        assertTrue(fixture.endpoint.executed.isEmpty())
    }

    @Test
    fun rq014_rq016_rq017_rq032_incompleteEligiblePublicProofIsUnavailable() = runTest {
        val base = input.assessedView()
        val decision = assertNotNull(base.decision)
        val variants = listOf(
            decision.copy(ordinaryInspection = null), decision.copy(ordinaryTiming = null),
            decision.copy(policyId = null), decision.copy(policyRevision = null),
            decision.copy(salePromiseCaptureId = null), decision.copy(applicableRightsIds = emptyList()),
            decision.copy(customerReceiptIds = emptyList()), decision.copy(customerReceiptIds = listOf(input.receiptId, input.id(709))))
        for (variant in variants) {
            val fixture = OrdinaryReturnGatewayFixture()
            fixture.endpoint.caseValue = base.copy(decision = variant)
            assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(fixture.query()))
            assertNull(fixture.journal.entry)
            assertTrue(fixture.endpoint.executed.isEmpty())
        }
    }

    private suspend fun qualifyForm(fixture: OrdinaryReturnGatewayFixture): LocalActionForm =
        assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page.forms.single {
            it.action == LocalAction.RETURN_CASE_QUALIFY
        }
}
