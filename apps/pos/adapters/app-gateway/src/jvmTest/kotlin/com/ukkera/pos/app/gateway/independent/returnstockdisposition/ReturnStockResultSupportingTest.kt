@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.app.gateway.independent.returnstockdisposition

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.StoredKernelResultV1
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** RSD-v1 supplemental saved-result admission only. Controlled Recorded replies do
 * not prove a Master commit, original-cost restoration, actual refund, durable restart,
 * current protected read authority or any required L2–L5 frozen-case execution. */
class ReturnStockResultSupportingTest {
    private val input = ReturnStockFrozenInputs

    @Test
    fun rsd003_rsd010_rsd013_rsd014_exactPartialAndFullResultsRecoverOnlyTheSavedStockOperation() = runTest {
        val variants = listOf(
            Triple(input.coreOriginal(), "1", applied(1, 1, 1, LocalReturnStockStatusV1.PARTIALLY_POSTED)),
            Triple(input.coreOriginal(), "2", applied(2, 0, 1, LocalReturnStockStatusV1.POSTED)),
            Triple(input.partialOriginal(), "1", applied(2, 0, 2, LocalReturnStockStatusV1.POSTED)))
        for ((source, quantity, value) in variants) {
            val fixture = stagedFixture(source, quantity)
            val original = assertNotNull(fixture.journal.entry)
            val reply = fixture.endpoint.recorded(value)
            fixture.endpoint.onExecute = { reply }
            val recorded = assertIs<LocalOperationResult.Recorded>(fixture.gateway.submit(input.operation))
            assertEquals(LocalRecordedDecision.APPLIED, recorded.decision)
            assertEquals(input.stockReference, assertNotNull(recorded.result).reference)
            assertNull(recorded.food)
            assertEquals(LocalReturnStockOperationDetails(input.operation, input.sale, input.caseId, input.returnId,
                input.restoration, source.dispositionRevision, value.revision), recorded.returnStock)
            val retained = assertNotNull(assertNotNull(fixture.journal.entry).recorded)
            assertFalse(assertNotNull(fixture.journal.entry).acknowledged)
            val readCount = fixture.endpoint.reads.size
            fixture.endpoint.source = LocalReturnStockReadResultV1.NotAvailable
            fixture.endpoint.onLookup = { LookupReply.Found(reply) }
            val recovered = assertIs<LocalOperationResult.Recorded>(fixture.gateway().lookup(input.operation))
            assertEquals(recorded.returnStock, recovered.returnStock)
            assertEquals(recorded.result, recovered.result)
            assertEquals(retained, assertNotNull(fixture.journal.entry).recorded)
            assertEquals(original.request().payload, assertNotNull(fixture.journal.entry).request().payload)
            assertEquals(original.identity(), assertNotNull(fixture.journal.entry).identity())
            assertEquals(readCount, fixture.endpoint.reads.size)
            assertEquals(listOf(LocalReturnStockProfileV1.COMMAND), fixture.endpoint.executed.map { it.command })
            assertTrue(fixture.endpoint.lookedUp.all { it == original.identity() })
            assertEquals(0, fixture.journal.acknowledgeCalls)
            assertIs<LocalAcknowledgementResult.Acknowledged>(fixture.gateway.acknowledgeRecorded(input.operation))
            assertEquals(1, fixture.journal.acknowledgeCalls)
            assertTrue(assertNotNull(fixture.journal.entry).acknowledged)
            assertEquals(1, fixture.endpoint.executed.size)
        }
        // These are exact client-visible acceptance quantities only; pool/value/due/cash remain UNBOUND.
    }

    @Test
    fun rsd003_rsd010_rsd013_wrongIdentitySourceQuantityOrStatusIsNotRememberedPublishedOrAcknowledged() = runTest {
        val valid = applied(1, 1, 1, LocalReturnStockStatusV1.PARTIALLY_POSTED)
        val bodyVariants = listOf(
            valid.copy(returnId = input.id(8150)), valid.copy(restorationId = input.id(8151)),
            valid.copy(revision = Revision(2)),
            valid.copy(postedQuantity = input.core(2), remainingQuantity = input.core(0), status = LocalReturnStockStatusV1.POSTED),
            valid.copy(remainingQuantity = input.core(0)), valid.copy(postedQuantity = input.measure(1)),
            valid.copy(status = LocalReturnStockStatusV1.PENDING), valid.copy(status = LocalReturnStockStatusV1.POSTED))
        val replyVariants: List<(ReturnStockGatewayFixture) -> MutationReply.Recorded> = bodyVariants.map { body ->
            { fixture: ReturnStockGatewayFixture -> fixture.endpoint.recorded(body) }
        } + listOf(
            { fixture: ReturnStockGatewayFixture -> fixture.endpoint.recorded(valid, operation = input.otherOperation) },
            { fixture: ReturnStockGatewayFixture -> fixture.endpoint.recorded(valid, actor = input.id(8152)) },
            { fixture: ReturnStockGatewayFixture -> fixture.endpoint.recorded(valid, committed = false) },
            { fixture: ReturnStockGatewayFixture -> fixture.endpoint.recorded(valid, code = "sales.return.stock.rejected") })
        for (makeReply in replyVariants) {
            val fixture = stagedFixture()
            val originalBody = assertNotNull(fixture.journal.entry).request().payload
            val reply = makeReply(fixture)
            fixture.endpoint.onExecute = { reply }
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(input.operation))
            assertNotRetained(fixture)
            fixture.endpoint.onLookup = { LookupReply.Found(reply) }
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(input.operation))
            assertIs<LocalPendingResult.Unavailable>(fixture.gateway.pending(null))
            assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(input.operation))
            assertEquals(originalBody, assertNotNull(fixture.journal.entry).request().payload)
            assertNotRetained(fixture)
        }
    }

    @Test
    fun rsd010_rsd013_aDamagedCachedStockResultCannotBeArchivedAsTheOriginal() = runTest {
        val fixture = stagedFixture()
        val wrong = fixture.endpoint.recorded(applied(2, 0, 1, LocalReturnStockStatusV1.POSTED))
        // Deliberately damaged client DATA, not a successful native retention or Master observation.
        fixture.journal.entry = assertNotNull(fixture.journal.entry).copy(
            phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = StoredKernelResultV1.from(wrong))
        val damaged = fixture.journal.entry
        fixture.endpoint.onLookup = { LookupReply.Found(wrong) }
        assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(input.operation))
        assertEquals(damaged, fixture.journal.entry)
        assertEquals(0, fixture.journal.rememberCalls)
        assertEquals(0, fixture.journal.acknowledgeCalls)
        assertFalse(assertNotNull(fixture.journal.entry).acknowledged)
        assertTrue(fixture.endpoint.executed.isEmpty())
    }

    @Test
    fun rsd003_rsd014_aBoundRejectionIsNotSuccessfulStockOrPayoutAndUnknownRefusalsStayUnresolved() = runTest {
        val fixture = stagedFixture()
        val rejected = fixture.endpoint.recorded(LocalReturnStockResultV1.Rejected("ACCEPTED_QUANTITY_EXHAUSTED"))
        fixture.endpoint.onExecute = { rejected }
        val result = assertIs<LocalOperationResult.Recorded>(fixture.gateway.submit(input.operation))
        assertEquals(LocalRecordedDecision.REJECTED, result.decision)
        assertNull(result.result)
        assertNull(result.returnStock)
        assertNull(result.food)
        assertFalse(assertNotNull(fixture.journal.entry).acknowledged)
        for ((reason, committed) in listOf("UNKNOWN_DESTINATION" to false, "ACCEPTED_QUANTITY_EXHAUSTED" to true)) {
            val malformed = stagedFixture()
            malformed.endpoint.onExecute = {
                malformed.endpoint.recorded(LocalReturnStockResultV1.Rejected(reason), committed = committed)
            }
            assertIs<LocalOperationResult.Unresolved>(malformed.gateway.submit(input.operation))
            assertNotRetained(malformed)
        }
    }

    private fun applied(posted: Long, remaining: Long, revision: Long, status: LocalReturnStockStatusV1) =
        LocalReturnStockResultV1.Applied(input.returnId, input.restoration, Revision(revision),
            input.core(posted), input.core(remaining), status)

    private suspend fun stagedFixture(source: LocalReturnStockReadResultV1.Original = input.coreOriginal(),
        quantity: String = "1"): ReturnStockGatewayFixture {
        val fixture = ReturnStockGatewayFixture()
        fixture.endpoint.source = source
        val form = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page.forms.single()
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(fixture.intent(form, quantity)))
        return fixture
    }

    private fun assertNotRetained(fixture: ReturnStockGatewayFixture) {
        assertNull(assertNotNull(fixture.journal.entry).recorded)
        assertFalse(assertNotNull(fixture.journal.entry).acknowledged)
        assertEquals(0, fixture.journal.rememberCalls)
        assertEquals(0, fixture.journal.acknowledgeCalls)
    }
}
