package com.ukkera.pos.database.independent.receiptselectorv2

import com.ukkera.pos.database.RetainedAggregateWrite
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.RuntimeRetailCustomerReceiptEventLocator
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptCodec
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptEventViewV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptHeadViewV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptProfileV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptResultV1
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Supporting L1 observations of actual receipt classification and selected-cut
 * validation using independently frozen v2 inputs and controlled DATA membership.
 * No accepting native source, admission, protected authority, durable business
 * operation, RPC, retry/restart or concurrency schedule is supplied by this test.
 * Passing these methods would not pass any complete RSEL-01..07 case.
 */
class ReceiptSelectorV2SupportingTest {
    private val f = FrozenReceiptSelectorV2Data
    private val bridge = ReceiptSelectorProductionDataBridge

    @Test
    fun rsel01_completeSelectedDataCutPreservesExactHistoricalEventsAtEveryFrozenDirectoryCount() = runTest {
        for (profile in f.profiles) {
            val cut = f.selected()
            val view = ControlledReceiptSelectorView(cut, profile)
            val before = cut.snapshot()
            // All 32832 G513 requests remain in this lazy DATA family, not a smaller
            // native fixture or a claim that they were dispatched/committed.
            assertEquals(profile * 64, view.unrelatedData.count())
            val selected = assertNotNull(bridge.findHead(view, cut.original))
            assertFrozenSelectedHead(bridge.headView(cut.original, selected), "base")
            val events = bridge.eventViews(selected)
            assertEquals(listOf(f.operation("E1"), f.operation("E2"), f.operation("E3")), events.map { it.eventOperationId })
            assertExactEvent(events.first(), cut.observations.first())
            assertExactEvent(events.last(), cut.observations.last())
            assertEquals(0, view.globalScans)
            assertEquals(1, view.membershipReads)
            assertEquals(listOf(f.membershipPrefix(f.selectedSaleId) to 65), view.claimRanges)
            assertEquals(cut.observations.map { it.operation }, view.originalReads)
            // The first event must prove that no predecessor HEAD_BEFORE exists.
            // That one selected null probe is not an unrelated or missing event row.
            val absentFirstBefore = f.beforeKey(cut.observations.first().operation)
            assertTrue(absentFirstBefore !in cut.rows)
            val allowedSelectedKeys = cut.rows.keys + absentFirstBefore
            assertTrue(view.aggregateReads.all { key -> key in allowedSelectedKeys })
            assertEquals(setOf(absentFirstBefore), view.aggregateReads.filterNot { it in cut.rows }.toSet())
            assertEquals(before, cut.snapshot(), "Supplied DATA remains exact; this is not a durable business-delta proof")
        }
    }

    @Test
    fun rsel01And05_publicInstalledClassifierBindsExactFrozenEventAndRetainedData() = runTest {
        val cut = f.selected("W-then-L")
        for (input in cut.observations) {
            val row = assertNotNull(cut.rows[f.eventKey(input.operation)])
            val original = assertNotNull(cut.originals[input.operation])
            assertEquals(cut.sale.saleId, bridge.classifier.saleId(f.scope, row, original))
            assertEquals(input.request, LocalRetailCustomerReceiptCodec.decode(original.normalized))
            val result = LocalRetailCustomerReceiptCodec.decodeResult(original.result) as LocalRetailCustomerReceiptResultV1.Applied
            assertEquals(input.operation, result.eventOperationId)
            assertEquals(input.request.expectedHeadRevision.value + 1, result.headRevision.value)
            assertEquals(input.request.receivedAt, result.receivedAt)
            assertEquals(input.recordedAt, result.recordedAt)
            assertEquals(f.ZONE, result.receiptTimeZoneId)
        }
        // E1-E3's required original journal coordinates are retained literally;
        // new W/L sequence and all native chronological admission remain unbound.
        assertEquals(listOf(1010L, 1011L, 1012L), cut.observations.take(3).map { cut.originals.getValue(it.operation).sequence.value })
    }

    @Test
    fun rsel02_nullMembershipIsUnavailableAndMissingE2CannotYieldAnIntactSelectedPrefix() = runTest {
        val noCapability = f.selected().also { it.members = null }
        assertRejectedCut(noCapability)
        val missing = f.selected().also { it.rows.remove(f.eventKey(f.operation("E2"))) }
        assertRejectedCut(missing)

        val truncated = f.selected().also { cut ->
            cut.members = assertNotNull(cut.members).filterNot { it.key == f.eventKey(f.operation("E2")) }
        }
        assertRejectedCut(truncated)

        // Literal F-EMPTY control: a supplied complete empty membership can produce
        // a null head cut. It is NOT a genuine paid original or protected NotRecorded.
        val empty = f.empty()
        val before = empty.snapshot()
        assertNull(bridge.findHead(ControlledReceiptSelectorView(empty, 513), empty.original))
        assertEquals(before, empty.snapshot())
        empty.members = null
        assertRejectedCut(empty)
    }

    @Test
    fun rsel03_extraDuplicateOrphanAndCumulativeDamageRejectTheWholeSelectedDataCut() = runTest {
        val extra = f.selected()
        val fourth = f.selected("W-wins").rows.getValue(f.eventKey(f.operation("W")))
        val rawFourth = raw(fourth)
        val x = f.operation("X")
        val after = rawFourth.getValue("after").jsonObject
        val afterX = JsonObject(after + mapOf(
            "lastOperationId" to JsonPrimitive(x.value),
            "eventOperationIds" to JsonArray(listOf("E1", "E2", "E3", "X").map { JsonPrimitive(f.operation(it).value) }),
        ))
        val resultX = JsonObject(rawFourth.getValue("result").jsonObject + ("eventOperationId" to JsonPrimitive(x.value)))
        val extraBytes = bridge.canonical("LocalRetailCustomerReceiptEventV1", JsonObject(rawFourth + mapOf(
            "operationId" to JsonPrimitive(x.value), "after" to afterX, "result" to resultX,
        )))
        val extraRow = StoredAggregate(f.eventKey(x), Revision(1), fourth.schema, extraBytes, sha256(extraBytes.copyBytes()))
        extra.rows[extraRow.key] = extraRow
        // The complete supplied actual-row membership includes the extra while the
        // frozen head/claims still name only E1-E3. No tail is silently discarded.
        extra.members = (assertNotNull(extra.members) + locator(extraRow)).sortedBy { it.key.id.value }
        assertRejectedCut(extra)

        val duplicate = f.selected().also { cut ->
            val e2 = assertNotNull(cut.members).single { it.key == f.eventKey(f.operation("E2")) }
            cut.members = (assertNotNull(cut.members) + e2).sortedBy { it.key.id.value }
        }
        assertRejectedCut(duplicate)
        val inconsistentDuplicate = f.selected().also { cut ->
            val e2 = assertNotNull(cut.members).single { it.key == f.eventKey(f.operation("E2")) }
            cut.members = (assertNotNull(cut.members) + e2.copy(digest = sha256("different E2 DATA".encodeToByteArray())))
                .sortedBy { it.key.id.value }
        }
        assertRejectedCut(inconsistentDuplicate)

        val orphan = f.selected().also { it.rows.remove(f.headKey(f.selectedSaleId)) }
        assertRejectedCut(orphan)

        val cumulative = f.selected()
        val head = cumulative.rows.getValue(f.headKey(f.selectedSaleId))
        val rawHead = raw(head)
        val portions = rawHead.getValue("recordedPortions").jsonArray.map { portion ->
            val value = portion.jsonObject
            if (value.text("portionId") != f.id("P-A").value) value else JsonObject(value +
                ("quantity" to JsonObject(value.getValue("quantity").jsonObject + ("scaled" to JsonPrimitive("46")))))
        }
        replaceRow(cumulative, head, "LocalRetailCustomerReceiptHeadV1", JsonObject(rawHead + ("recordedPortions" to JsonArray(portions))))
        assertRejectedCut(cumulative)
    }

    @Test
    fun rsel03_wrongOriginalPortionFinalizeCollectorOrLatestProvenanceIsUnavailable() = runTest {
        val unrelated = f.growth(513).first()
        for (field in listOf("portion", "finalize", "collector", "sale")) {
            val cut = f.selected()
            val operation = f.operation(if (field == "sale") "E3" else "E2")
            val row = cut.rows.getValue(f.eventKey(operation))
            val value = raw(row)
            val damaged = if (field == "portion") {
                val portion = value.getValue("portions").jsonArray.single().jsonObject
                JsonObject(value + ("portions" to JsonArray(listOf(JsonObject(portion +
                    ("portionId" to JsonPrimitive(unrelated.request.portions.single().portionId.value)))))))
            } else {
                val after = value.getValue("after").jsonObject
                val source = after.getValue("source").jsonObject
                val change = when (field) {
                    "finalize" -> "finalizeOperationId" to JsonPrimitive(unrelated.finalize.value)
                    "collector" -> "originalCollectorId" to JsonPrimitive(f.actorB.value)
                    else -> "saleId" to JsonPrimitive(unrelated.request.saleId.value)
                }
                JsonObject(value + ("after" to JsonObject(after + ("source" to JsonObject(source + change)))))
            }
            replaceRow(cut, row, "LocalRetailCustomerReceiptEventV1", damaged, updateLocator = true)
            assertRejectedCut(cut)
        }

        val wrongLatest = f.selected()
        val head = wrongLatest.rows.getValue(f.headKey(f.selectedSaleId))
        replaceRow(wrongLatest, head, "LocalRetailCustomerReceiptHeadV1", JsonObject(raw(head) +
            ("lastOperationId" to JsonPrimitive(unrelated.operation.value))))
        assertRejectedCut(wrongLatest)
    }

    @Test
    fun rsel03_nativeLocatorDataRequiresExactKeyRevisionSchemaDigestAndOrder() = runTest {
        for (variant in listOf("digest", "generation", "schema", "returned_key", "unsorted")) {
            val cut = f.selected()
            val members = assertNotNull(cut.members)
            val latest = members.single { it.key == f.eventKey(f.operation("E3")) }
            when (variant) {
                "digest" -> cut.members = members.map { if (it == latest) it.copy(digest = sha256("wrong latest locator".encodeToByteArray())) else it }
                "generation" -> cut.members = members.map { if (it == latest) it.copy(revision = Revision(2)) else it }
                "schema" -> cut.members = members.map { if (it == latest) it.copy(schema = LocalRetailCustomerReceiptProfileV1.HEAD_SCHEMA) else it }
                "returned_key" -> {
                    val row = cut.rows.getValue(latest.key)
                    cut.rows[latest.key] = StoredAggregate(f.eventKey(f.operation("X")), row.revision, row.schema, row.payload, row.digest)
                }
                else -> cut.members = members.reversed()
            }
            assertRejectedCut(cut)
        }
    }

    @Test
    fun rsel04And05_completeSuppliedBeforeWinnerAndLaterCutsDoNotSubstituteTheLatestEvent() = runTest {
        // Independent already-complete DATA cuts. No concurrent CAS commit or L5
        // schedule, publication delta, lost response, restart or retry is exercised.
        for (name in listOf("base", "W-wins", "W-ALT-wins", "W-then-L")) {
            val cut = f.selected(name)
            val selected = assertNotNull(bridge.findHead(ControlledReceiptSelectorView(cut, 513), cut.original))
            assertFrozenSelectedHead(bridge.headView(cut.original, selected), name)
            val events = bridge.eventViews(selected)
            assertExactEvent(events.single { it.eventOperationId == f.operation("E1") }, cut.observations.first())
            for (alias in listOf("W", "W-ALT", "L")) {
                val expected = cut.observations.singleOrNull { it.operation == f.operation(alias) }
                if (expected == null) assertTrue(events.none { it.eventOperationId == f.operation(alias) })
                else assertExactEvent(events.single { it.eventOperationId == expected.operation }, expected)
            }
            if (name == "W-then-L") {
                val w = events.single { it.eventOperationId == f.operation("W") }
                val originalResult = LocalRetailCustomerReceiptCodec.decodeResult(cut.originals.getValue(f.operation("W")).result)
                    as LocalRetailCustomerReceiptResultV1.Applied
                assertEquals(Revision(4), w.headRevision)
                assertEquals(Revision(4), originalResult.headRevision)
                assertEquals(f.instant("2026-11-03T10:00:00Z"), w.receivedAt)
                assertEquals(f.instant("2026-11-03T11:00:00Z"), w.recordedAt)
                assertEquals(originalResult.receivedAt, w.receivedAt)
                assertEquals(originalResult.recordedAt, w.recordedAt)
            }
        }
    }

    @Test
    fun rsel05_classifierRejectsChangedOriginalContentActorResultAndExactEventAfterData() = runTest {
        val cut = f.selected("W-then-L")
        val input = f.selectedWrite("W")
        val row = cut.rows.getValue(f.eventKey(input.operation))
        val original = cut.originals.getValue(input.operation)
        val requests = listOf(
            input.request.copy(portions = listOf(f.each(f.id("P-A"), 6), f.each(f.id("P-B"), 7))),
            input.request.copy(receivedAt = f.instant("2026-11-03T10:01:00Z")),
            input.request.copy(expectedHeadRevision = Revision(4)),
        )
        for (changed in requests) {
            val normalized = LocalRetailCustomerReceiptCodec.encode(changed)
            val changedOriginal = copyOriginal(original, normalized = normalized,
                semanticDigest = semanticDigest(f.scope, f.actorA, original.command, normalized))
            assertReceiptSourceUnavailable { bridge.classifier.saleId(f.scope, row, changedOriginal) }
        }
        val changedActor = copyOriginal(original, actor = f.actorB,
            semanticDigest = semanticDigest(f.scope, f.actorB, original.command, original.normalized))
        assertReceiptSourceUnavailable { bridge.classifier.saleId(f.scope, row, changedActor) }
        val changedTime = copyOriginal(original, recordedAt = f.instant("2026-11-03T11:06:00Z"))
        assertReceiptSourceUnavailable { bridge.classifier.saleId(f.scope, row, changedTime) }
        val changedResult = copyOriginal(original, result = cut.originals.getValue(f.operation("L")).result)
        assertReceiptSourceUnavailable { bridge.classifier.saleId(f.scope, row, changedResult) }
        val omittedEvent = copyOriginal(original, writes = original.aggregateWrites.filterNot { it.key == row.key })
        assertReceiptSourceUnavailable { bridge.classifier.saleId(f.scope, row, omittedEvent) }
        val doubledEvent = copyOriginal(original, writes = original.aggregateWrites + original.aggregateWrites.single { it.key == row.key })
        assertReceiptSourceUnavailable { bridge.classifier.saleId(f.scope, row, doubledEvent) }
        assertEquals(f.selectedSaleId, bridge.classifier.saleId(f.scope, row, original))
        // These are attribution mismatches, NOT executed stable-operation conflicts
        // or proof that a currently unauthorized retry cannot disclose a result.
    }

    @Test
    fun rsel07_completeCap64AndCap256DataRemainExactlyReadableWithoutDiscardingOriginalPortions() = runTest {
        val cap64 = f.cap64()
        val selected64 = assertNotNull(bridge.findHead(ControlledReceiptSelectorView(cap64, 513), cap64.original))
        val head64 = bridge.headView(cap64.original, selected64)
        assertEquals(Revision(64), head64.headRevision)
        assertEquals(64, head64.eventCount)
        assertEquals(64L, recorded(head64, f.id("P-C64-A")))
        assertEquals(0L, recorded(head64, f.id("P-C64-B")))
        assertEquals(listOf(64L, 1L), head64.original.portions.map { it.quantity.scaled })
        val events64 = bridge.eventViews(selected64)
        assertExactEvent(events64.first(), cap64.observations.first())
        assertExactEvent(events64.last(), cap64.observations.last())

        for (includeSecond in listOf(false, true)) {
            val cap256 = f.cap256(includeSecond)
            val selected256 = assertNotNull(bridge.findHead(ControlledReceiptSelectorView(cap256, 513), cap256.original))
            val head256 = bridge.headView(cap256.original, selected256)
            assertEquals(256, head256.original.portions.size)
            assertEquals((1..256).map(f::cap256Portion), head256.original.portions.map { it.portionId })
            assertEquals(512L, head256.original.portions.sumOf { it.quantity.scaled })
            assertEquals(Revision(if (includeSecond) 2L else 1L), head256.headRevision)
            assertEquals(if (includeSecond) 2 else 1, head256.eventCount)
            for (ordinal in 1..256) {
                assertEquals(2L, head256.original.portions[ordinal - 1].quantity.scaled)
                assertEquals(if (ordinal == 1 || includeSecond && ordinal == 256) 1L else 0L,
                    recorded(head256, f.cap256Portion(ordinal)))
            }
            assertEquals(if (includeSecond) f.operation("C256-E2") else f.operation("C256-E1"), head256.latestEventOperationId)
            assertTrue(cap256.rows.values.all { it.payload.size <= 65_536 })
        }
        assertTrue(cap64.rows.values.all { it.payload.size <= 65_536 })
        // These pre-existing DATA cuts do not execute the refused 65th write or the
        // permitted P256 commit; those RSEL-07 transaction obligations remain unbound.
    }

    private suspend fun assertRejectedCut(cut: ReceiptSelectorCutData) {
        val view = ControlledReceiptSelectorView(cut, 513)
        val before = cut.snapshot()
        assertReceiptSourceUnavailable { bridge.findHead(view, cut.original) }
        assertEquals(0, view.globalScans)
        assertEquals(before, cut.snapshot())
    }

    private fun assertFrozenSelectedHead(head: LocalRetailCustomerReceiptHeadViewV1, name: String) {
        val expected = f.root.getValue("F-SELECT").jsonObject.getValue("expectedCuts").jsonObject.getValue(name).jsonObject
        assertEquals(f.selectedSaleId, head.original.saleId)
        assertEquals(f.actorA, head.original.originalCollectorId)
        assertEquals(f.instant("2026-11-01T08:00:00Z"), head.original.paidFinalizeAt)
        assertEquals(listOf(f.id("P-A"), f.id("P-B")), head.original.portions.map { it.portionId })
        assertEquals(listOf(100L, 30L), head.original.portions.map { it.quantity.scaled })
        assertTrue(head.original.portions.all { it.quantity.unitId == f.unitId && it.quantity.scale == 0 })
        assertEquals(expected.getValue("revision").jsonPrimitive.int.toLong(), head.headRevision.value)
        assertEquals(expected.getValue("eventCount").jsonPrimitive.int, head.eventCount)
        val quantities = expected.getValue("recorded").jsonObject
        assertEquals(quantities.getValue("P-A").jsonPrimitive.content.toLong(), recorded(head, f.id("P-A")))
        assertEquals(quantities.getValue("P-B").jsonPrimitive.content.toLong(), recorded(head, f.id("P-B")))
        assertEquals(f.operation(expected.text("latest")), head.latestEventOperationId)
        // The genuine 80/25 remaining entitlement and all durable financial/stock
        // values are absent from this DATA seam, never inferred from sold quantities.
    }

    private fun assertExactEvent(event: LocalRetailCustomerReceiptEventViewV1, expected: ReceiptSelectorObservation) {
        assertEquals(expected.request.saleId, event.saleId)
        assertEquals(expected.operation, event.eventOperationId)
        assertEquals(expected.request.expectedHeadRevision.value + 1, event.headRevision.value)
        assertEquals(expected.request.observation, event.observation)
        assertEquals(expected.request.portions, event.portions)
        assertEquals(expected.request.receivedAt, event.receivedAt)
        assertEquals(expected.request.receiptTimeZoneId, event.receiptTimeZoneId)
        assertEquals(expected.recordedAt, event.recordedAt)
        assertEquals(f.actorA, event.originalCollectorId)
        assertEquals(expected.actor, event.observingActorId)
        assertEquals(f.instant("2026-11-01T08:00:00Z"), event.paidFinalizeAt)
        val finalize = when (event.saleId) {
            f.id("S-CAP64") -> f.operation("Finalize-C64")
            f.id("S-CAP256") -> f.operation("Finalize-C256")
            else -> f.operation("Finalize-S")
        }
        assertEquals(finalize, event.sourceOperationId)
        assertEquals(f.actorA, event.sourceActorId)
        assertEquals(semanticDigest(f.scope, expected.actor, LocalRetailCustomerReceiptCodec.COMMAND,
            LocalRetailCustomerReceiptCodec.encode(expected.request)), event.eventSemanticDigest)
    }

    private fun recorded(head: LocalRetailCustomerReceiptHeadViewV1, portion: DomainId): Long =
        head.recordedPortions.singleOrNull { it.portionId == portion }?.quantity?.scaled ?: 0L

    private fun raw(row: StoredAggregate): JsonObject = bridge.json.parseToJsonElement(row.payload.copyBytes().decodeToString()).jsonObject

    private fun replaceRow(cut: ReceiptSelectorCutData, row: StoredAggregate, typeName: String,
        literal: JsonObject, updateLocator: Boolean = false) {
        // Mutation/serialization completes before the production rejection assertion.
        val bytes = bridge.canonical(typeName, literal)
        val changed = StoredAggregate(row.key, row.revision, row.schema, bytes, sha256(bytes.copyBytes()))
        cut.rows[row.key] = changed
        if (updateLocator) cut.members = assertNotNull(cut.members).map { if (it.key == row.key) locator(changed) else it }
    }

    private fun locator(row: StoredAggregate): RuntimeRetailCustomerReceiptEventLocator =
        RuntimeRetailCustomerReceiptEventLocator(row.key, row.revision, row.schema, row.digest)

    private fun copyOriginal(original: RetainedCommandEvidence, actor: DomainId = original.originalActorId,
        normalized: StoredBytes = original.normalized, semanticDigest: PayloadDigest = original.semanticDigest,
        recordedAt: UtcInstant = original.recordedAt, result: StoredBytes = original.result,
        writes: List<RetainedAggregateWrite> = original.aggregateWrites): RetainedCommandEvidence = RetainedCommandEvidence(
        original.scope, original.operationId, actor, original.command, original.assignmentId, original.authorityEpoch,
        original.sequence, recordedAt, original.authenticationId, original.policyRevision, semanticDigest, original.code,
        normalized, result, sha256(result.copyBytes()), original.previousJournalHash, original.journalHash,
        writes, original.claimChanges, original.jobs,
    )
}

/** Only this precise production failure qualifies; reflection or fixture failures do not. */
internal suspend fun assertReceiptSourceUnavailable(block: suspend () -> Any?) {
    var failure: Throwable? = null
    try { block() } catch (problem: Throwable) { failure = problem }
    val actual = assertNotNull(failure, "Expected actual sales source unavailability")
    try {
        assertEquals("com.ukkera.pos.sales.store.LocalSalesSourceUnavailable", actual.javaClass.name)
    } catch (assertion: AssertionError) {
        // Preserve the unexpected production/fixture stack in the failed test result.
        assertion.initCause(actual)
        throw assertion
    }
}
