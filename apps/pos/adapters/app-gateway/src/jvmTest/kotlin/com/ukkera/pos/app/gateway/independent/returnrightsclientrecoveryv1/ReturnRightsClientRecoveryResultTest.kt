package com.ukkera.pos.app.gateway.independent.returnrightsclientrecoveryv1

import com.ukkera.pos.app.LocalAcknowledgementResult
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingResult
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.gateway.renderRecorded
import com.ukkera.pos.catalog.store.LocalReturnRightsActivationCodecV1
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Frozen minimal result joins and controlled protected dispatch; no authentic content is supplied. */
class ReturnRightsClientRecoveryResultTest {
    private val inputs = FrozenReturnRightsClientRecoveryInputs

    @Test
    fun rrc012FullyJoinedAppliedReplyOffersOnlyTheOriginalHistoricalSelector() {
        val fixture = inputs.fixture("resultFixtures", "F-RESULT-APPLIED-0")
        val reply = inputs.recorded("F-RESULT-APPLIED-0")
        val decoded = LocalReturnRightsActivationCodecV1.decodeResult(reply.result)
        assertEquals(fixture.rrcString("outerCode"), decoded.code)
        assertTrue(reply.committed)
        assertEquals(inputs.scope.company(), decoded.reference.company)
        assertEquals(inputs.id("publication"), decoded.publicationId)
        assertEquals(inputs.id("publication"), decoded.reference.publicationId)
        assertEquals(inputs.id("policy"), decoded.policyId)
        assertEquals(inputs.id("policy"), decoded.reference.policyId)
        assertEquals("0", decoded.reference.companyRevision.value.toString())
        assertEquals(inputs.id("signingKey"), decoded.reference.signingKeyId)
        assertEquals(inputs.fixture("definitionFixtures", "F-DEF-0").rrcString("bodyDigestSha256"), decoded.reference.bodyDigest.value)
        assertEquals("1", assertNotNull(decoded.activeRevision).value.toString())
        renderFixture("F-RESULT-APPLIED-0")
    }

    @Test
    fun rrc013EveryFrozenIdentityReferenceAndDigestMismatchStaysGeneric() {
        val matrix = inputs.fixtures("resultFixtures").filter { it.first.startsWith("F-RESULT-JOIN-") }
        assertEquals(10, matrix.size)
        matrix.forEach { renderFixture(it.first) }
    }

    @Test
    fun rrc014OrdinaryRefusalsReservedSignatureAndAllFlagConflictsStayDistinct() {
        val refusals = inputs.fixtures("resultFixtures").filter { it.first.startsWith("F-RESULT-REFUSAL-") }
        val flags = inputs.fixtures("resultFixtures").filter { it.first.startsWith("F-RESULT-FLAG-") }
        assertEquals(3, refusals.size)
        assertEquals(6, flags.size)
        (refusals + ("F-RESULT-RESERVED-SIGNATURE" to inputs.fixture("resultFixtures", "F-RESULT-RESERVED-SIGNATURE")) + flags)
            .forEach { renderFixture(it.first) }
    }

    @Test
    fun rrc015EveryMalformedOrUnknownMinimalResultHasNoPolicyRoute() {
        val matrix = inputs.fixtures("resultFixtures").filter { it.first.startsWith("F-RESULT-BAD-") }
        assertEquals(5, matrix.size)
        matrix.forEach { renderFixture(it.first) }
    }

    @Test
    fun rrc016LargeCASRetainsExactStringsAndEveryMaximumOverflowRemainsGeneric() {
        for (name in listOf("F-REQUEST-LARGE", "F-REQUEST-MAX")) {
            val fixture = inputs.fixture("requestFixtures", name)
            val wire = inputs.request(name)
            val decoded = LocalReturnRightsActivationCodecV1.decodeActivate(wire)
            assertEquals(fixture.rrcString("expectedActiveRevision"), decoded.expectedActiveRevision.value.toString(), name)
            assertEquals(wire, LocalReturnRightsActivationCodecV1.normalizeActivate(wire), name)
            assertEquals(wire, LocalReturnRightsActivationCodecV1.activate(inputs.operationId, decoded).payload, name)
            assertEquals(wire, inputs.entry(name).request().payload, name)
        }
        val large = LocalReturnRightsActivationCodecV1.decodeResult(inputs.recorded("F-RESULT-APPLIED-LARGE").result)
        assertEquals("9007199254740993", large.reference.companyRevision.value.toString())
        assertEquals("9007199254740994", assertNotNull(large.activeRevision).value.toString())
        renderFixture("F-RESULT-APPLIED-LARGE")
        val overflow = inputs.fixtures("resultFixtures").filter { it.first.startsWith("F-RESULT-OVERFLOW-") }
        assertEquals(3, overflow.size)
        overflow.forEach { renderFixture(it.first) }
    }

    @Test
    fun rrc017HistoricalSelectionUsesFreshCurrentCredentialsAndExactOriginalQuery() = runTest {
        for (fixture in inputs.matrix("historicalReadMatrix")) {
            val name = fixture.rrcString("id")
            val original = inputs.entry()
            val harness = ControlledReturnRightsClientRecoveryHarness(initial = original)
            try {
                val replyName = fixture.rrcString("selectedReceiptFixture")
                harness.master.lookupReply = LookupReply.Found(inputs.recorded(replyName))
                val receipt = assertIs<LocalOperationResult.Recorded>(harness.gateway.lookup(inputs.operationId), name)
                assertFrozenReturnRightsReceipt(replyName, original, inputs.recorded(replyName), receipt)
                val historical = assertNotNull(receipt.result, name).reference
                val receiptConnection = harness.master.connections.last()
                harness.reconnect()
                assertEquals(fixture.rrcString("currentPointerRevision"), harness.master.currentPointerRevision, name)
                assertNotEquals(DomainId(fixture.rrcString("currentPublicationId")), historical.id, name)
                // Keep a permissive cached read hint: it cannot override a fresh controlled Master denial.
                harness.replaceGrants(listOf(ControlledReturnRightsClientRecoveryHarness.grant(inputs.owner, historical.id, GrantEffect.ALLOW)))
                harness.master.readReply = ReadReply.Unavailable(if (fixture.rrcFlag("currentDisclosureAuthorized"))
                    ReadFailure.WRITER_NOT_QUALIFIED else ReadFailure.AUTHORIZATION_DENIED)
                val callsBefore = harness.master.connections.size
                val selected = assertIs<LocalReadResult.Unavailable>(harness.gateway.read(
                    LocalWorkflowQuery(LocalWorkflow.CATALOG, reference = historical)), name)
                assertEquals(if (fixture.rrcFlag("currentDisclosureAuthorized")) LocalServiceProblem.UNAVAILABLE else LocalServiceProblem.DENIED,
                    selected.problem, name)
                assertEquals(callsBefore + 1, harness.master.connections.size, name)
                assertNotEquals(receiptConnection, harness.master.connections.last(), name)
                assertEquals(harness.master.connections.last(), harness.master.readConnections.single(), name)
                assertEquals(inputs.owner.actorId to inputs.owner.deviceId, harness.master.readOwners.single(), name)
                val query = inputs.fixture("queryFixtures", fixture.rrcString("expectedFreshQueryFixture"))
                val dispatched = harness.master.reads.single()
                assertEquals(query.rrcString("command"), dispatched.query.name, name)
                assertEquals(query.rrcString("version"), dispatched.query.version.toString(), name)
                assertEquals(query.rrcString("canonicalJson"), dispatched.payload.rrcText(), name)
                assertNotEquals(inputs.fixture("queryFixtures", "F-QUERY-CURRENT").rrcString("canonicalJson"), dispatched.payload.rrcText(), name)
                assertReturnRightsOriginalRetained(original, assertNotNull(harness.journal.entry), name)
                assertTrue(harness.master.mutations.isEmpty(), name)
                assertEquals(0, harness.journal.stageCalls, name)
                assertEquals(0, harness.journal.acknowledgeCalls, name)
            } finally { harness.close() }
        }
        // The allowed row proves routing only, with an explicit unavailable reply. Genuine successful history is unbound.
    }

    @Test
    fun rrc018GatewayAckIncludingDuplicatesOnlyProgressesTheRetainedOriginalReceipt() = runTest {
        for (fixture in inputs.matrix("receiptCompletionMatrix").filter { it.rrcString("action") == "ACK" }) {
            val name = fixture.rrcString("replyFixture")
            val original = inputs.entry()
            val harness = ControlledReturnRightsClientRecoveryHarness(initial = original, grants = emptyList())
            try {
                val reply = inputs.recorded(name)
                harness.master.lookupReply = LookupReply.Found(reply)
                val receipt = assertIs<LocalOperationResult.Recorded>(harness.gateway.lookup(inputs.operationId), name)
                assertFrozenReturnRightsReceipt(name, original, reply, receipt)
                val grants = returnRightsGrantScalars(harness.view.policy.grants)
                val callbacks = if (fixture.rrcFlag("duplicateCallback")) 2 else 1
                repeat(callbacks) {
                    assertEquals(original.operationId,
                        assertIs<LocalAcknowledgementResult.Acknowledged>(harness.gateway.acknowledgeRecorded(original.operationId), name).operationId, name)
                    assertReturnRightsOriginalRetained(original, assertNotNull(harness.journal.entry), name)
                    assertTrue(assertNotNull(harness.journal.entry).acknowledged, name)
                }
                assertEquals(callbacks, harness.journal.acknowledgeCalls, name)
                assertTrue(assertIs<LocalPendingResult.Available>(harness.gateway.pending(), name).operations.isEmpty(), name)
                assertFalse(harness.access().canContinue(assertNotNull(harness.journal.entry)), name)
                val currentGrants = returnRightsGrantScalars(harness.view.policy.grants)
                assertEquals(grants, currentGrants, name)
                assertEquals(fixture.rrcString("expectedPermissionGrants").toInt(),
                    currentGrants.count { it !in grants }, name)
                assertTrue(harness.master.lookups.all { it == original.identity() }, name)
                assertEquals(fixture.rrcString("expectedNewActivationSubmissions").toInt(), harness.master.mutations.size, name)
                assertEquals(fixture.rrcString("expectedPolicyContentReads").toInt(), harness.master.reads.size, name)
                assertEquals(0, harness.journal.stageCalls, name)
            } finally { harness.close() }
        }
    }

    private fun renderFixture(name: String) {
        val fixture = inputs.fixture("resultFixtures", name)
        val entry = inputs.entry(fixture.rrcString("savedRequestFixture"))
        val reply = inputs.recorded(name)
        assertFrozenReturnRightsReceipt(name, entry, reply, renderRecorded(entry, reply))
    }
}
