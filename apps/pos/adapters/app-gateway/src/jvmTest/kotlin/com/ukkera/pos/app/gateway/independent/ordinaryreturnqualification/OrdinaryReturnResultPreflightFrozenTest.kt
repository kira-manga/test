@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.app.gateway.independent.ordinaryreturnqualification

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.StoredKernelResultV1
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** RQ-014/027/029/031/032 controlled result-release slices. Carrier construction
 * proves neither durable Master commit nor accepting source/history authority. */
class OrdinaryReturnResultPreflightFrozenTest {
    private val input = OrdinaryReturnGatewayInputs

    @Test
    fun rq014_boundReplyWithoutSavedExplicitInspectionIsNeitherRetainedNorAcknowledged() = runTest {
        val fixture = stagedFixture(withInspection = false)
        val reply = fixture.endpoint.recorded(input.qualifyOperation, boundResult())
        fixture.endpoint.onExecute = { reply }
        assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(input.qualifyOperation))
        assertNotRetained(fixture)
        fixture.endpoint.onLookup = { LookupReply.Found(reply) }
        assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(input.qualifyOperation))
        assertIs<LocalPendingResult.Unavailable>(fixture.gateway.pending(null))
        assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(input.qualifyOperation))
        assertNotRetained(fixture)
    }

    @Test
    fun rq002_rq028_rq032_malformedQualifiedRepliesCannotBecomeClientResults() = runTest {
        val result = boundResult()
        val variants = listOf(
            result.copy(caseId = input.id(720)),
            result.copy(eventOperationId = input.appendOperation),
            result.copy(action = LocalReturnCaseActionV1.ACCEPTED),
            result.copy(stage = LocalReturnCaseStageV1.PENDING),
            result.copy(claimRevision = null),
            result.copy(entitlementRevision = Revision(6)),
            result.copy(returnId = input.id(721)),
            result.copy(originalPayable = Money(Currency.EGP, 23_484)))
        for (variant in variants) {
            val fixture = stagedFixture()
            val originalBody = assertNotNull(fixture.journal.entry).request().payload
            val reply = fixture.endpoint.recorded(input.qualifyOperation, variant)
            fixture.endpoint.onExecute = { reply }
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(input.qualifyOperation))
            assertNotRetained(fixture)
            fixture.endpoint.onLookup = { LookupReply.Found(reply) }
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(input.qualifyOperation))
            assertIs<LocalPendingResult.Unavailable>(fixture.gateway.pending(null))
            assertEquals(originalBody, assertNotNull(fixture.journal.entry).request().payload)
            assertNotRetained(fixture)
        }
    }

    @Test
    fun rq029_rq032_appliedRejectionAndCommittedRefusalAreUnresolvedBeforeRetention() = runTest {
        for (refused in listOf(false, true)) {
            val fixture = stagedFixture()
            val value: LocalReturnCaseResultV1 = if (refused) LocalReturnCaseResultV1.Refused("SOURCE_UNAVAILABLE") else boundResult()
            val reply = fixture.endpoint.recorded(input.qualifyOperation, value, committed = refused)
            fixture.endpoint.onExecute = { reply }
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(input.qualifyOperation))
            assertNotRetained(fixture)
        }
    }

    @Test
    fun rq032_aMalformedCachedResultCannotBeArchivedThroughAcknowledgement() = runTest {
        val fixture = stagedFixture(withInspection = false)
        val malformed = fixture.endpoint.recorded(input.qualifyOperation, boundResult())
        // Deliberately damaged client DATA. This is not an observed Master result.
        fixture.journal.entry = assertNotNull(fixture.journal.entry).copy(
            phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = StoredKernelResultV1.from(malformed))
        val damaged = fixture.journal.entry
        fixture.endpoint.onLookup = { LookupReply.Found(malformed) }
        assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(input.qualifyOperation))
        assertEquals(0, fixture.journal.acknowledgeCalls)
        assertEquals(0, fixture.journal.rememberCalls)
        assertEquals(damaged, fixture.journal.entry)
        assertFalse(assertNotNull(fixture.journal.entry).acknowledged)
    }

    @Test
    fun rq027_rq029_pendingReplyRecoveryPreservesOriginalIdentityUntilExplicitAcknowledgement() = runTest {
        val fixture = stagedFixture(withInspection = false)
        val original = assertNotNull(fixture.journal.entry)
        val pending = boundResult().copy(action = LocalReturnCaseActionV1.ASSESSMENT_PENDING,
            stage = LocalReturnCaseStageV1.PENDING, claimRevision = null)
        val reply = fixture.endpoint.recorded(input.qualifyOperation, pending)
        fixture.endpoint.onExecute = { reply }
        assertIs<LocalOperationResult.Recorded>(fixture.gateway.submit(input.qualifyOperation))
        val retained = assertNotNull(assertNotNull(fixture.journal.entry).recorded)
        assertFalse(assertNotNull(fixture.journal.entry).acknowledged)
        fixture.endpoint.onLookup = { LookupReply.Found(reply) }
        assertIs<LocalOperationResult.Recorded>(fixture.gateway().lookup(input.qualifyOperation))
        assertEquals(retained, assertNotNull(fixture.journal.entry).recorded)
        assertEquals(original.request().payload, assertNotNull(fixture.journal.entry).request().payload)
        assertEquals(original.identity(), assertNotNull(fixture.journal.entry).identity())
        assertEquals(1, fixture.endpoint.executed.size)
        assertEquals(0, fixture.journal.acknowledgeCalls)
        assertIs<LocalAcknowledgementResult.Acknowledged>(fixture.gateway.acknowledgeRecorded(input.qualifyOperation))
        assertEquals(1, fixture.journal.acknowledgeCalls)
        assertTrue(assertNotNull(fixture.journal.entry).acknowledged)
    }

    private suspend fun stagedFixture(withInspection: Boolean = true): OrdinaryReturnGatewayFixture {
        val fixture = OrdinaryReturnGatewayFixture()
        val form = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page.forms.single {
            it.action == LocalAction.RETURN_CASE_QUALIFY
        }
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(fixture.intent(form,
            values = if (withInspection) fixture.inspectionValues() else emptyMap())))
        return fixture
    }

    /** Revisions are controlled response coordinates, not a claimed frozen F1 successor. */
    private fun boundResult() = LocalReturnCaseResultV1.Applied(LocalReturnCaseActionV1.BOUND, input.caseId,
        Revision(2), LocalReturnCaseStageV1.BOUND_UNACCEPTED, input.qualifyOperation,
        claimRevision = Revision(0), entitlementRevision = Revision(7))

    private fun assertNotRetained(fixture: OrdinaryReturnGatewayFixture) {
        assertNull(assertNotNull(fixture.journal.entry).recorded)
        assertFalse(assertNotNull(fixture.journal.entry).acknowledged)
        assertEquals(0, fixture.journal.rememberCalls)
        assertEquals(0, fixture.journal.acknowledgeCalls)
    }
}
