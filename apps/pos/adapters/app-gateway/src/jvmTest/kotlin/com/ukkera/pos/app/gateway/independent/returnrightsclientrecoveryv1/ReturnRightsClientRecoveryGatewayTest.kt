package com.ukkera.pos.app.gateway.independent.returnrightsclientrecoveryv1

import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalPendingResult
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalStageResult
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.StoredKernelResultV1
import com.ukkera.pos.app.gateway.decodeHex
import com.ukkera.pos.app.gateway.returnRightsContinuationHint
import com.ukkera.pos.catalog.store.LocalReturnRightsActivationCodecV1
import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real shared gateway behavior over controlled replies, credentials and original-owner custody. */
class ReturnRightsClientRecoveryGatewayTest {
    private val inputs = FrozenReturnRightsClientRecoveryInputs

    @Test
    fun rrc004GenericRawRequestEnvelopeOrApprovalNeverStagesTheAction() = runTest {
        val envelope = inputs.fixture("definitionFixtures", "F-DEF-0").rrcString("signedEnvelopeCanonicalJson")
        for (raw in listOf(null, inputs.requestText("F-REQUEST-0"), envelope, inputs.requestText("F-REQUEST-BAD-APPROVAL-FIELD"))) {
            val harness = ControlledReturnRightsClientRecoveryHarness()
            try {
                val result = assertIs<LocalStageResult.NotStaged>(harness.gateway.stage(inputs.intent(raw)))
                assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, result.problem)
                assertNull(harness.journal.entry)
                harness.assertNoNewWork("generic preparation")
                assertTrue(harness.master.lookups.isEmpty())
            } finally { harness.close() }
        }
    }

    @Test
    fun rrc007UnknownResponseReconnectAndEveryLookupVariantRetainOriginalCASAndIdentity() = runTest {
        val workflow = inputs.objectValue("recoveryWorkflow")
        for (variant in workflow.getValue("variants").jsonArray.map { it.jsonObject }) {
            val name = variant.rrcString("lookupOutcome") + variant["currentOriginalResubmissionPermitted"]
            val original = inputs.entry()
            val harness = ControlledReturnRightsClientRecoveryHarness(initial = original)
            try {
                assertFrozenReturnRightsOriginal(original, name)
                // An actual first submit traverses lookup, dispatch custody and a controlled unknown reply.
                assertIs<LocalOperationResult.Unresolved>(harness.gateway.submit(inputs.operationId))
                assertEquals(listOf("lookup", "execute"), harness.master.events, name)
                assertEquals(1, harness.master.mutations.size, name)
                assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, assertNotNull(harness.journal.entry).localPhase, name)
                val oldConnection = harness.master.connections.single()
                val baseline = harness.master.mutations.size
                val dispatches = harness.journal.dispatchCalls
                val eventStart = harness.master.events.size
                assertEquals(workflow.rrcString("routingEpochBefore"), harness.master.routingEpoch, name)
                harness.reconnect()
                assertEquals(workflow.rrcString("routingEpochAfter"), harness.master.routingEpoch, name)
                assertEquals(workflow.rrcString("currentPointerRevisionAfterReconnect"), harness.master.currentPointerRevision, name)

                val outcome = variant.rrcString("lookupOutcome")
                harness.master.lookupReply = when (outcome) {
                    "OUTCOME_UNKNOWN" -> LookupReply.Deferred(inputs.operationId, DeferredReason.HISTORY_INCOMPLETE)
                    "IN_PROGRESS" -> LookupReply.Deferred(inputs.operationId, DeferredReason.SERVICE_BUSY)
                    "NOT_RECORDED" -> LookupReply.AbsentAtCurrentWriter(inputs.operationId)
                    "COMMITTED", "REJECTED" -> LookupReply.Found(inputs.recorded(variant.rrcString("recordedReplyFixture")))
                    else -> error("Unknown frozen lookup variant")
                }
                if (outcome == "NOT_RECORDED") {
                    harness.replaceGrants(if (variant.rrcFlag("currentOriginalResubmissionPermitted"))
                        ControlledReturnRightsClientRecoveryHarness.branchAllow() else emptyList())
                } else if (outcome == "COMMITTED" || outcome == "REJECTED") {
                    // A controlled permitted original-result lookup is separate from a new activation hint.
                    harness.replaceGrants(emptyList())
                }
                val result = harness.gateway.submit(inputs.operationId)
                val expectedSubmissions = variant.rrcString("expectedActivationSubmissions").toInt()
                assertEquals(expectedSubmissions, harness.master.mutations.size - baseline, name)
                assertEquals(dispatches + expectedSubmissions, harness.journal.dispatchCalls, name)
                assertEquals(if (expectedSubmissions == 1) listOf("lookup", "execute") else listOf("lookup"),
                    harness.master.events.drop(eventStart), name)
                assertNotEquals(oldConnection, harness.master.connections.last(), name)
                assertTrue(harness.master.lookups.all { it == original.identity() }, name)
                for (request in harness.master.mutations) {
                    assertReturnRightsOriginalRequest(original, request, name)
                    val decoded = LocalReturnRightsActivationCodecV1.decodeActivate(request.payload)
                    assertEquals("0", decoded.expectedActiveRevision.value.toString(), name)
                    assertNotEquals(harness.master.currentPointerRevision, decoded.expectedActiveRevision.value.toString(), name)
                }
                assertTrue(harness.master.mutationOwners.all { it == (inputs.owner.actorId to inputs.owner.deviceId) }, name)
                if (expectedSubmissions == 1) {
                    val submitted = harness.master.mutations.last()
                    assertEquals(variant.rrcString("expectedOperationId"), submitted.operationId.value, name)
                    assertEquals(variant.rrcString("expectedActorId"), harness.master.mutationOwners.last().first.value, name)
                    assertEquals(variant.rrcString("expectedSavedDeviceId"), harness.master.mutationOwners.last().second.value, name)
                    assertEquals(inputs.request(variant.rrcString("expectedSubmissionRequestFixture")), submitted.payload, name)
                    assertEquals(variant.rrcString("expectedCAS"),
                        LocalReturnRightsActivationCodecV1.decodeActivate(submitted.payload).expectedActiveRevision.value.toString(), name)
                }
                if (outcome == "COMMITTED" || outcome == "REJECTED") {
                    val replyFixture = variant.rrcString("recordedReplyFixture")
                    val expectedReply = inputs.recorded(replyFixture)
                    val retained = assertNotNull(assertNotNull(harness.journal.entry, name).recorded, name)
                    assertEquals(StoredKernelResultV1.from(expectedReply), retained, name)
                    assertFrozenReturnRightsReceipt(replyFixture, original, expectedReply,
                        assertIs<LocalOperationResult.Recorded>(result, name))
                    if (outcome == "COMMITTED") {
                        val retainedBytes = decodeHex(retained.payloadHex, 65_536)
                        val active = try {
                            assertNotNull(LocalReturnRightsActivationCodecV1.decodeResult(StoredBytes(retainedBytes)).activeRevision, name)
                        } finally { retainedBytes.fill(0) }
                        assertEquals(variant.rrcString("expectedPresentedActiveRevision"), active.value.toString(), name)
                        assertNotEquals("6", active.value.toString(), name)
                    }
                } else {
                    assertIs<LocalOperationResult.Unresolved>(result, name)
                    assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, assertNotNull(harness.journal.entry).localPhase, name)
                    assertNull(harness.journal.entry?.recorded, name)
                }
                assertReturnRightsOriginalRetained(original, assertNotNull(harness.journal.entry), name)
                assertEquals(0, harness.journal.stageCalls, name)
                assertTrue(harness.master.reads.isEmpty(), name)
            } finally { harness.close() }
        }
    }

    @Test
    fun rrc007ChangedGenericInputCannotForkOrOverwriteTheOriginalOperation() = runTest {
        val original = inputs.entry()
        val harness = ControlledReturnRightsClientRecoveryHarness(initial = original)
        try {
            val result = assertIs<LocalStageResult.Uncertain>(harness.gateway.stage(inputs.intent(inputs.requestText("F-REQUEST-LARGE"))))
            assertEquals(original.operationId, result.operationId)
            assertReturnRightsOriginalRetained(original, assertNotNull(harness.journal.entry), "changed input")
            harness.assertNoNewWork("changed input")
            assertTrue(harness.master.lookups.isEmpty())
        } finally { harness.close() }
    }

    @Test
    fun rrc008EveryIdentityTransitionRetainsTheOriginalPrivateAttribution() {
        val original = inputs.entry()
        for (fixture in inputs.matrix("identityTransitionMatrix")) {
            val harness = ControlledReturnRightsClientRecoveryHarness(initial = original)
            try {
                val actor = DomainId(fixture.rrcString("currentActorId"))
                val device = DomainId(fixture.rrcString("currentDeviceId"))
                val current = LocalClientIdentity(inputs.scope, actor, device)
                harness.replaceSession(actor, device, grants = if (fixture.rrcFlag("currentNewActivationGrant"))
                    ControlledReturnRightsClientRecoveryHarness.branchAllow(current) else emptyList())
                assertReturnRightsOriginalRetained(original, assertNotNull(harness.journal.entry), fixture.rrcString("id"))
                harness.assertNoNewWork(fixture.rrcString("id"))
            } finally { harness.close() }
        }
        // No accepting delegated owner is constructed. Positive cross-device release needs a genuine recovery path.
    }

    @Test
    fun rrc008UnrelatedAndDeniedTransitionsCannotExposeOrSubmitTheOriginalOwnerJournal() = runTest {
        for (fixture in inputs.matrix("identityTransitionMatrix").filter { !it.rrcFlag("expectedOriginalReceiptReleased") }) {
            val name = fixture.rrcString("id")
            val original = inputs.entry()
            val harness = ControlledReturnRightsClientRecoveryHarness(initial = original)
            try {
                val current = LocalClientIdentity(inputs.scope, DomainId(fixture.rrcString("currentActorId")),
                    DomainId(fixture.rrcString("currentDeviceId")))
                harness.replaceSession(current.actorId, current.deviceId, grants = if (fixture.rrcFlag("currentNewActivationGrant"))
                    ControlledReturnRightsClientRecoveryHarness.branchAllow(current) else emptyList())
                harness.master.lookupReply = LookupReply.Found(inputs.recorded("F-RESULT-APPLIED-0"))
                assertEquals(LocalServiceProblem.SESSION_ENDED,
                    assertIs<LocalPendingResult.Unavailable>(harness.gateway.pending(), name).problem, name)
                for (result in listOf(harness.gateway.lookup(inputs.operationId), harness.gateway.submit(inputs.operationId)))
                    assertEquals(LocalServiceProblem.SESSION_ENDED, assertIs<LocalOperationResult.Unresolved>(result, name).problem, name)
                assertTrue(harness.master.lookups.isEmpty(), name)
                assertReturnRightsOriginalRetained(original, assertNotNull(harness.journal.entry), name)
                harness.assertNoNewWork(name)
            } finally { harness.close() }
        }
        // The owner guard refuses before Master lookup; explicit Master recovery-deny enforcement is not inferred.
    }

    @Test
    fun rrc009BranchOnlyAllowProducesAnAdvisoryContinuationAndOriginalPendingIdentity() = runTest {
        val fixture = inputs.fixture("hintFixtures", "F-HINT-ALLOW-BRANCH")
        val owner = inputs.owner.copy(scope = inputs.scope(fixture.getValue("journalScope").jsonObject))
        val original = inputs.entry()
        val harness = ControlledReturnRightsClientRecoveryHarness(initial = original, owner = owner,
            supported = fixture.rrcFlag("commandSupported"), grants = listOf(ControlledReturnRightsClientRecoveryHarness.grant(
                owner, DomainId(fixture.rrcString("grantedBranchId")), GrantEffect.ALLOW)))
        try {
            val access = harness.access()
            assertEquals(DomainId(fixture.rrcString("expectedRequestedBranchId")), original.owner.scope.branchId)
            assertEquals(inputs.scope(fixture.getValue("sessionScope").jsonObject), harness.view.scope)
            assertEquals(fixture.rrcFlag("expectedContinuation"), access.canContinue(original))
            assertEquals(fixture.rrcFlag("expectedContinuation"), returnRightsContinuationHint(access, original))
            val pending = assertIs<LocalPendingResult.Available>(harness.gateway.pending()).operations.single()
            assertEquals(original.operationId, pending.operationId)
            assertEquals(original.localAction, pending.action)
            assertTrue(pending.submitAllowed)
            assertEquals(listOf(original.identity()), harness.master.lookups)
            assertReturnRightsOriginalRetained(original, assertNotNull(harness.journal.entry), "branch hint")
            harness.assertNoNewWork("branch hint")
        } finally { harness.close() }
    }

    @Test
    fun rrc010MissingSupportOrSavedAndSessionScopeMismatchCannotContinue() = runTest {
        val original = inputs.entry()
        val unsupported = inputs.fixture("hintFixtures", "F-HINT-NO-SUPPORT")
        val noSupport = ControlledReturnRightsClientRecoveryHarness(initial = original, supported = unsupported.rrcFlag("commandSupported"))
        try {
            assertEquals(unsupported.rrcFlag("expectedContinuation"), noSupport.access().canContinue(original))
            val pending = assertIs<LocalPendingResult.Available>(noSupport.gateway.pending()).operations.single()
            assertFalse(pending.submitAllowed)
            assertReturnRightsOriginalRetained(original, assertNotNull(noSupport.journal.entry), "missing support")
            noSupport.assertNoNewWork("missing support")
        } finally { noSupport.close() }
        for (fixture in inputs.matrix("hintScopeMatrix")) {
            val name = fixture.rrcString("id")
            val scope = inputs.scope(fixture.getValue("journalScope").jsonObject)
            val sessionScope = inputs.scope(fixture.getValue("sessionScope").jsonObject)
            val entry = inputs.invalidEntry(original, JsonObject(mapOf("scope" to fixture.getValue("journalScope"))))
            val harness = ControlledReturnRightsClientRecoveryHarness(owner = original.owner.copy(scope = scope))
            try {
                val current = original.owner.copy(scope = sessionScope)
                harness.replaceSession(current.actorId, current.deviceId, sessionScope,
                    ControlledReturnRightsClientRecoveryHarness.branchAllow(current))
                assertEquals(fixture.rrcFlag("expectedContinuation"), harness.access().canContinue(entry), name)
                harness.assertNoNewWork(name)
                assertTrue(harness.master.lookups.isEmpty(), name)
            } finally { harness.close() }
        }
        // Malformed and cross-labelled saved inputs use the exact RRC-006 matrix.
    }

    @Test
    fun rrc011UnrelatedTargetAllowsAndActualBranchDenialsNeverPermitContinuation() = runTest {
        val original = inputs.entry()
        val matrix = inputs.matrix("permissionTargetMatrix")
        assertEquals(10, matrix.size)
        for (fixture in matrix) {
            val name = fixture.rrcString("id")
            assertEquals(inputs.scope, inputs.scope(fixture.getValue("journalScope").jsonObject), name)
            assertEquals(inputs.scope, inputs.scope(fixture.getValue("sessionScope").jsonObject), name)
            assertEquals(inputs.scope.branchId, DomainId(fixture.rrcString("expectedRequestedBranchId")), name)
            val grants = buildList {
                add(ControlledReturnRightsClientRecoveryHarness.grant(inputs.owner,
                    DomainId(fixture.getValue("allowTarget").jsonObject.rrcString("id")), GrantEffect.ALLOW))
                if (fixture.rrcFlag("actualBranchAllow")) addAll(ControlledReturnRightsClientRecoveryHarness.branchAllow())
                if (fixture.rrcFlag("explicitActualBranchDeny")) add(ControlledReturnRightsClientRecoveryHarness.grant(
                    inputs.owner, DomainId(fixture.rrcString("expectedRequestedBranchId")), GrantEffect.DENY))
            }
            val harness = ControlledReturnRightsClientRecoveryHarness(grants = grants, supported = fixture.rrcFlag("commandSupported"))
            try {
                // Branch-only RRC-009 and every unrelated-target allow discriminate the actual requested target.
                assertEquals(fixture.rrcFlag("expectedContinuation"), harness.access().canContinue(original), name)
                assertEquals(fixture.rrcFlag("expectedContinuation"), returnRightsContinuationHint(harness.access(), original), name)
                harness.assertNoNewWork(name)
                assertTrue(harness.master.lookups.isEmpty(), name)
            } finally { harness.close() }
        }
    }
}
