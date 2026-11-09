@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.sales.store.independent.returnsourceomittedexpectation

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.RetainedAggregateWrite
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.RetainedClaimChange
import com.ukkera.pos.database.RetainedJobIntent
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Clause-specific RSE-v1 DATA projections, NOT passing native case families.
 * Shared result/cut/resource checks and literal selector exclusions neither supply
 * genuine originals nor run protected authorization, native history, SQLite/RPC,
 * recovery, restart, current disclosure or the complete business observation B.
 */
class ReturnSourceOmittedExpectationSharedSlicesTest {
    private val frozen = ReturnSourceOmittedExpectationFrozenInputs

    @Test
    fun rse_v1_02_conflictClause_revisionSevenScalarGuardDoesNotInventInitialEvidence() {
        val original = frozen.bytes
        val result = assertNotNull(returnCaseEntitlementRevisionGuard(null, frozen.currentEntitlementRevision))
        assertConflict(result)
        assertContentEquals(frozen.body.encodeToByteArray(), original.copyBytes())
        assertNull(frozen.request.expectedEntitlementRevision)
        // Exactly the frozen observed-7 scalar comparison, not an authentic source cut.
    }

    @Test
    fun rse_v1_02_conflictClause_initialDataPreparedNullProducerPreservesCutAndCompleteInputResources() {
        val data = ReturnSourceOmittedExpectationInitialData()
        val before = data.snapshot()
        val production = returnCaseUnavailableQualificationProduction(data.identity, data.request, data.before, data.original)
        assertConflict(production.propose(data.now))
        assertConflict(production.propose(data.now))
        assertEquals(before, data.snapshot())
        assertEquals(data.expectedResources(), production.affectedResources)
        assertEquals(Permission(SecurityAction.RETURN_CLAIM, PermissionVerb.PERFORM), production.requirement.permission)
        assertEquals(data.scope, production.requirement.scope)
        val binding = assertNotNull(production.requirement.binding)
        assertEquals(data.caseId, binding.documentId)
        assertEquals(data.identity.operationId, binding.operationId)
        assertEquals(data.identity.semanticDigest, binding.payloadDigest)
        assertNull(data.request.expectedEntitlementRevision)
        assertNull(data.before.decision)
        assertNull(data.before.row.value.acceptedReturnId)
        // This actual prepared-null proposal has no prepare/assess argument. No
        // live dispatch call-count, native effects or durable retry claim is made.
    }

    @Test
    fun rse_v1_03_originalClause_exactInitialDataSelectsOnlyBoundedInertRefusalShape() {
        val data = ReturnSourceOmittedExpectationInitialData()
        val before = data.snapshot()
        val retained = data.retained()
        assertTrue(data.matches(retained))
        assertFalse(data.matches(retained, entitlementRevision = frozen.currentEntitlementRevision))
        assertNull(retained.nativeOriginalProvenance)
        assertFalse(retained.committed)
        assertTrue(retained.aggregateWrites.isEmpty())
        assertTrue(retained.claimChanges.isEmpty())
        assertTrue(retained.jobs.isEmpty())
        assertContentEquals(frozen.body.encodeToByteArray(), retained.normalized.copyBytes())
        assertContentEquals(conflictBytes().copyBytes(), retained.result.copyBytes())
        assertEquals(before, data.snapshot())
        // True means DATA selection only. Revision zero is a separate input to
        // this initial-only selector, never inferred as F-SOURCE's original.
    }

    @Test
    fun rse_v1_05_b_c_retainedCommandDataChangesRefuseIndependently() {
        val data = ReturnSourceOmittedExpectationInitialData()
        val before = data.snapshot()
        assertTrue(data.matches(data.retained()))
        for (command in listOf(CommandKey("sales.other.write", 1), CommandKey("sales.return.case.write", 2))) {
            assertFalse(data.matches(data.retained(command = command)), command.toString())
        }
        val changedType = StoredBytes(frozen.body.replace("\"QUALIFY\"", "\"RECORD\"").encodeToByteArray())
        assertFalse(data.matches(data.retained(normalized = changedType)), "retained type")
        val variants = listOf(
            data.request.copy(caseId = frozen.otherCaseId),
            data.request.copy(expectedCaseRevision = Revision(12)),
            data.request.copy(expectedEntitlementRevision = Revision(0)),
            data.request.copy(expectedEntitlementRevision = Revision(7)))
        for (request in variants) {
            val changed = data.retained(normalized = LocalReturnCaseCommandCodecV1.encode(request))
            assertFalse(data.matches(changed), "retained bytes versus original request")
            assertFalse(data.matches(changed, request), "changed request versus original CASE/omission")
        }
        assertEquals(before, data.snapshot())
        // Operation-producer authenticity and OPERATION_PAYLOAD_MISMATCH need the kernel.
    }

    @Test
    fun rse_v1_05_e_resultDataCannotSelectPendingSuccessfulOrDifferentRejection() {
        val data = ReturnSourceOmittedExpectationInitialData()
        val before = data.snapshot()
        assertTrue(data.matches(data.retained()))
        for (code in listOf("committed", "sales.other.refused")) {
            assertFalse(data.matches(data.retained(code = code)), code)
        }
        val pending = LocalReturnCaseResultV1.Applied(LocalReturnCaseActionV1.ASSESSMENT_PENDING, data.caseId,
            Revision(12), LocalReturnCaseStageV1.PENDING, data.identity.operationId)
        val accepted = pending.copy(action = LocalReturnCaseActionV1.ACCEPTED,
            stage = LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING, returnId = frozen.dataId(80))
        for (result in listOf(LocalReturnCaseResultV1.Refused("RECEIPT_BINDING_REQUIRED"), pending, accepted)) {
            assertFalse(data.matches(data.retained(result = LocalReturnCaseCommandCodecV1.result(result))), result.toString())
        }
        assertEquals(before, data.snapshot())
        // Only exact retained result bytes/code are compared; no history is authenticated.
    }

    @Test
    fun rse_v1_04_a_c_and_05_d_caseOwnerAndScopeDataCannotSubstituteForOriginalCoordinates() {
        val data = ReturnSourceOmittedExpectationInitialData()
        val before = data.snapshot()
        val retained = data.retained()
        assertTrue(data.matches(retained))
        assertFalse(data.matches(retained, state = data.before.row.value.copy(caseId = frozen.otherCaseId)), "other CASE")
        assertFalse(data.matches(retained, state = data.before.row.value.copy(ownerId = frozen.otherOwnerId)), "other owner")
        assertFalse(data.matches(data.retained(actorId = frozen.otherOwnerId)), "other retained actor")
        for (scope in listOf(frozen.otherBranchScope, frozen.otherCompanyScope, frozen.otherOrganizationScope)) {
            assertFalse(data.matches(retained, state = data.before.row.value.copy(scope = LocalSalesScope.from(scope))), "other BEFORE scope")
            assertFalse(data.matches(data.retained(scope = scope)), "other retained scope")
        }
        assertEquals(before, data.snapshot())
        // These mismatches are not O-ACCESS tests or authenticated transplanted originals.
    }

    @Test
    fun rse_v1_06_a_addedRetainedWritesClaimsAndJobDataRemainOutsideRefusalSelector() {
        val data = ReturnSourceOmittedExpectationInitialData()
        val before = data.snapshot()
        assertTrue(data.matches(data.retained()))
        val entitlement = data.original.entitlement.stored
        val witness = RetainedAggregateWrite(AggregateKey(LocalReturnCaseProfileV1.ENTITLEMENT_BEFORE,
            DomainId(data.identity.operationId.value)), Revision(1),
            LocalSalesStoreProfile.schema(LocalReturnCaseProfileV1.ENTITLEMENT_BEFORE), entitlement.payload, entitlement.digest)
        for (write in listOf(data.retainedWrite(data.before.row.stored), data.retainedWrite(entitlement), witness)) {
            assertFalse(data.matches(data.retained(writes = listOf(write))), write.key.kind)
        }
        val claim = RetainedClaimChange(RetainedClaimAction.ACQUIRE,
            LocalReturnCaseProfileV1.source("event", DomainId(data.identity.operationId.value)), data.caseId)
        assertFalse(data.matches(data.retained(claims = listOf(claim))), "added claim DATA")
        assertFalse(data.matches(data.retained(jobs = listOf(RetainedJobIntent("synthetic.physical-action",
            StoredBytes("{}".encodeToByteArray()))))), "added job DATA")
        assertEquals(before, data.snapshot())
        // No stock/refund ledger, job execution or native complete-effect comparator is supplied.
    }

    @Test
    fun rse_v1_10_c_caseCasClause_legitimateSharedAppendToTwelveIsNotRolledBackByOmittedRequest() {
        val data = ReturnSourceOmittedExpectationInitialData()
        val retained = data.retained()
        data.append()
        assertEquals(Revision(12), data.before.row.value.revision)
        val postEdit = data.snapshot()
        val production = returnCaseUnavailableQualificationProduction(data.identity, data.request, data.before, data.original)
        assertConflict(production.propose(data.now))
        assertFalse(data.matches(retained))
        assertEquals(postEdit, data.snapshot())
        assertContentEquals(frozen.body.encodeToByteArray(), retained.normalized.copyBytes())
        // Shared CASE-CAS only: no application race or recovery against an authentic old BEFORE.
    }

    @Test
    fun rse_v1_03_d_partialSourceClause_missingReferencedCaptureDoesNotEnterAbsentDataProduction() {
        val data = ReturnSourceOmittedExpectationInitialData()
        val before = data.snapshot()
        assertConflict(returnCaseUnavailableQualificationProduction(data.identity, data.request, data.before, data.original).propose(data.now))
        assertFails("actual source admission requires the referenced capture") {
            returnCaseUnavailableQualificationProduction(data.identity, data.request, data.before,
                data.original.copy(returnPolicyCapture = null))
        }
        assertEquals(before, data.snapshot())
        // Failure proves this DATA admission check only, not O-INVALID-HISTORY/native unchanged B.
    }

    private fun assertConflict(proposal: ProposedOutcome) {
        val refusal = assertIs<ProposedOutcome.Reject>(proposal)
        assertEquals("sales.return_case.refused", refusal.code)
        assertContentEquals(conflictBytes().copyBytes(), refusal.result.copyBytes())
        assertEquals(LocalReturnCaseResultV1.Refused("REVISION_CONFLICT"), LocalReturnCaseCommandCodecV1.decodeResult(refusal.result))
        // Reject exposes no Apply writes/claims/jobs; this is not a durable absent-effects proof.
    }
}
