package com.ukkera.pos.sales.store.independent.returnfactualsourcepending

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptEventV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptProfileV1
import com.ukkera.pos.sales.store.LocalReturnCaseFactualSourcesV1
import com.ukkera.pos.sales.store.LocalSalesSourceUnavailable
import com.ukkera.pos.sales.store.retailCustomerReceiptEventKey
import com.ukkera.pos.sales.store.retailCustomerReceiptEventMembershipValue
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Eight bounded L1 literal/failure methods from frozen return-factual-source-pending-v1.
 * The two raw events are inert unsigned format inputs, not genuine paid originals,
 * observations, retained producers, native BEFOREs, grants or committed results.
 * The membership decoder checks literal integrity, locator and scope only; complete
 * event census/pagination, chronology, producer joins and original quantity/unit
 * validation remain unbound. The controlled view establishes no positive absence.
 *
 * No whole RFSP case, PENDING transaction, Master/RPC/native/SQLite integration,
 * legal assessment, persistence, current access/revocation or zero-effects claim
 * follows from these slices. Existing promise/wire/assessment-field slices are
 * deliberately not repeated here.
 */
class ReturnFactualSourcePendingFrozenValidationSlicesTest {
    private val frozen: FrozenReturnFactualSourceInputs = FrozenReturnFactualSourceInputs

    @Test
    fun rfsp03_pinnedObservationFields_literalPreservationOnly() {
        // Two decoder invocations, one per frozen F-RECEIPT observation.
        val events = frozen.observations.indices.map { index ->
            decodeLiteral(frozen.eventBytes(index), index)
        }
        for ((index, event) in events.withIndex()) {
            val request = frozen.request(index)
            val zone = request.getValue("receiptTimeZoneId").jsonPrimitive.content
            assertEquals(frozen.operation(index), event.operationId)
            assertEquals(frozen.saleId, event.after.source.saleId)
            assertEquals(frozen.collectorId, event.after.source.originalCollectorId)
            assertEquals(frozen.paidFinalizeAt, event.after.source.paidFinalizeAt)
            assertEquals(frozen.receivedAt(index), event.receivedAt)
            assertEquals(zone, event.receiptTimeZoneId)
            assertEquals(frozen.recordedAt(index), event.recordedAt)
            assertEquals(frozen.headRevision(index), event.after.headRevision)
            assertEquals(frozen.saleId, event.result.saleId)
            assertEquals(frozen.operation(index), event.result.eventOperationId)
            assertEquals(frozen.headRevision(index), event.result.headRevision)
            assertEquals(frozen.receivedAt(index), event.result.receivedAt)
            assertEquals(zone, event.result.receiptTimeZoneId)
            assertEquals(frozen.recordedAt(index), event.result.recordedAt)

            val requestedPortions = request.getValue("portions").jsonArray.map { it.jsonObject }
            assertEquals(requestedPortions.map { it.getValue("portionId").jsonPrimitive.content },
                event.portions.map { it.portionId.value })
            for ((position, rawPortion) in requestedPortions.withIndex()) {
                val originalPortion = frozen.sale.getValue("portions").jsonArray.single {
                    it.jsonObject.getValue("portionId") == rawPortion.getValue("portionId")
                }.jsonObject
                val quantity = event.portions[position].quantity
                assertEquals(originalPortion.getValue("precision").jsonPrimitive.int, quantity.scale)
                // Both frozen portions have precision 0. No EA catalogue identity is claimed.
                assertEquals(rawPortion.getValue("quantity").jsonPrimitive.content, quantity.scaled.toString())
            }
        }

        val finalHead = events.last().after
        assertEquals(frozen.completeHead.getValue("eventCount").jsonPrimitive.int, finalHead.eventOperationIds.size)
        assertEquals(frozen.observations.indices.map(frozen::operation), finalHead.eventOperationIds)
        assertEquals(frozen.sale.getValue("portions").jsonArray.map {
            it.jsonObject.getValue("portionId").jsonPrimitive.content
        }, finalHead.recordedPortions.map { it.portionId.value })
        assertEquals(frozen.completeHead.getValue("recordedQuantities").jsonArray.map { it.jsonPrimitive.content },
            finalHead.recordedPortions.map { it.quantity.scaled.toString() })
        assertEquals(frozen.completeHead.getValue("cumulativeRecordedQuantity").jsonPrimitive.content,
            finalHead.recordedPortions.sumOf { it.quantity.scaled }.toString())
        // Original sold quantities are fixture inputs, not quantities authenticated by this decoder.
    }

    @Test
    fun rfsp04_wrongEventLocator_literalMembershipRejected() {
        // F-SOURCE-DAMAGE.wrong_event_producer: locator disagreement slice only.
        // The actual retained producer/result relationship is not constructed.
        val payload = frozen.eventBytes(0)
        val wrongLocator = retailCustomerReceiptEventKey(frozen.operation(1))
        val digest = sha256(payload.copyBytes())
        assertFailsWith<LocalSalesSourceUnavailable> {
            retailCustomerReceiptEventMembershipValue(wrongLocator, Revision(1),
                LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA, payload, digest, frozen.scope)
        }
    }

    @Test
    fun rfsp06_otherSellingBranchOrCompany_literalScopeRejected() {
        val payload = frozen.eventBytes(0)
        val variants = listOf(
            "other selling branch" to frozen.otherBranchScope,
            "other company" to frozen.otherCompanyScope,
        )
        for ((variant, scope) in variants) {
            assertFailsWith<LocalSalesSourceUnavailable>(variant) {
                decodeLiteral(payload, scope = scope)
            }
        }
    }

    @Test
    fun rfsp14_corruptTruncatedOrIncompleteEvent_literalIntegrityRejected() {
        // Finite raw-byte fault slices of RFSP-14, not a genuine crash/restore cut.
        // Prepare every mutation before the rejection assertion; no failing DTO
        // constructor or stored-row fabrication can satisfy the assertion.
        val original = frozen.eventBytes(0)
        val originalBytes = original.copyBytes()
        val truncated = StoredBytes(originalBytes.copyOf(originalBytes.size - 1))
        val withoutAfter = editLiteral(original) { JsonObject(it - "after") }
        val withoutResult = editLiteral(original) { JsonObject(it - "result") }
        val variants = listOf(
            DamagedLiteral("digest mismatch", original, sha256("{}".encodeToByteArray())),
            DamagedLiteral("truncated JSON", truncated, sha256(truncated.copyBytes())),
            DamagedLiteral("missing after", withoutAfter, sha256(withoutAfter.copyBytes())),
            DamagedLiteral("missing result", withoutResult, sha256(withoutResult.copyBytes())),
        )
        for (variant in variants) {
            assertFailsWith<LocalSalesSourceUnavailable>(variant.name) {
                decodeLiteral(variant.payload, digest = variant.digest)
            }
        }
    }

    @Test
    fun rfsp11_missingObservedTimeOrExplicitZone_literalEvidenceRejected() {
        val original = frozen.eventBytes(0)
        val variants = listOf(
            "missing_observed_time" to "receivedAt",
            "device_default_zone" to "receiptTimeZoneId",
        ).map { (id, field) ->
            val missingValue = frozen.invalidObservations.getValue(id).getValue(field)
            id to editLiteral(original) { JsonObject(it + (field to missingValue)) }
        }
        for ((id, payload) in variants) {
            assertFailsWith<LocalSalesSourceUnavailable>(id) {
                decodeLiteral(payload)
            }
        }
    }

    @Test
    fun rfsp11_futureObservation_literalEvidenceRejected() {
        val future = frozen.invalidObservations.getValue("future_to_commit")
        val receivedAt = future.getValue("receivedAt")
        val recordedAt = future.getValue("masterRecordedAt")
        // Mutate root AND result as raw JSON before invoking the decoder. An
        // invalid Applied DTO constructed in the assertion would test the harness.
        val payload = editLiteral(frozen.eventBytes(0)) { raw ->
            val result = JsonObject(raw.getValue("result").jsonObject + mapOf(
                "receivedAt" to receivedAt, "recordedAt" to recordedAt,
            ))
            JsonObject(raw + mapOf("receivedAt" to receivedAt, "recordedAt" to recordedAt, "result" to result))
        }
        assertFailsWith<LocalSalesSourceUnavailable> {
            decodeLiteral(payload)
        }
    }

    @Test
    fun rfsp04_missingOriginal_factualReaderUnavailableWithoutAbsenceClaim() = runTest {
        // F-SOURCE-DAMAGE.missing_original. This is a controlled failure premise,
        // not an authoritative native proof of original/head/event absence.
        val view = UnavailableFactualView()
        assertFailsWith<LocalSalesSourceUnavailable> {
            LocalReturnCaseFactualSourcesV1.read(view, frozen.saleId)
        }
    }

    @Test
    fun rfsp13_factualReaderCancellation_propagatesOnly() = runTest {
        // RFSP-13 cancellation assertion only; no session/revocation race is bound.
        val cancellation = CancellationException("RFSP-13 synthetic source-read cancellation")
        val view = UnavailableFactualView(cancellation)
        assertFailsWith<CancellationException> {
            LocalReturnCaseFactualSourcesV1.read(view, frozen.saleId)
        }
    }

    private fun decodeLiteral(payload: StoredBytes, index: Int = 0,
        scope: BranchScope = frozen.scope, digest: PayloadDigest = sha256(payload.copyBytes())):
        LocalRetailCustomerReceiptEventV1 =
        retailCustomerReceiptEventMembershipValue(retailCustomerReceiptEventKey(frozen.operation(index)),
            Revision(1), LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA, payload, digest, scope)

    private fun editLiteral(payload: StoredBytes, edit: (JsonObject) -> JsonObject): StoredBytes =
        StoredBytes(edit(Json.parseToJsonElement(payload.copyBytes().decodeToString()).jsonObject)
            .toString().encodeToByteArray())

    private data class DamagedLiteral(val name: String, val payload: StoredBytes, val digest: PayloadDigest)

    /** No genuine transaction, row, retained producer, native witness or pagination is supplied. */
    private class UnavailableFactualView(private val aggregateFailure: CancellationException? = null) : CommandReadView {
        override val scope: BranchScope = FrozenReturnFactualSourceInputs.scope
        override suspend fun aggregate(key: AggregateKey): StoredAggregate? {
            aggregateFailure?.let { throw it }
            return null
        }
        override suspend fun claim(key: ClaimKey): DomainId? = null
        override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = null
    }
}
