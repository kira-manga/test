package com.ukkera.pos.database.independent.receiptselectorv2

import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptCodec
import com.ukkera.pos.types.OperationId
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Frozen v2 growth INPUT preservation only, through the actual request codec.
 * Every 32832 G513 request is generated; none is dispatched, journaled or admitted.
 * No native history, unrelated aggregate byte bound or durable growth is qualified.
 */
class ReceiptSelectorV2GrowthInputsSupportingTest {
    private val f = FrozenReceiptSelectorV2Data

    @Test
    fun all32832UnrelatedRequestsPreserveFrozenIdsQuantitiesAndFreshObservationChronology() {
        val identities = mutableSetOf<OperationId>()
        var count = 0
        var byteTotal = 0L
        var maximumRequestBytes = 0
        var currentSale = 0
        var saleQuantity = 0L
        var previous: ReceiptSelectorGrowthObservation? = null
        for (input in f.growth(513)) {
            if (input.saleOrdinal != currentSale) {
                if (currentSale != 0) assertEquals(64L, saleQuantity)
                currentSale = input.saleOrdinal
                saleQuantity = 0L
            }
            assertEquals(f.actorB, input.actor)
            assertEquals(input.saleOrdinal.toLong(), input.originalSequence.value)
            assertTrue(input.originalSequence.value in 1L..513L && input.originalSequence.value < 1000)
            assertEquals(input.eventOrdinal.toLong() - 1, input.request.expectedHeadRevision.value)
            assertEquals(f.ZONE, input.request.receiptTimeZoneId)
            val portion = input.request.portions.single()
            assertEquals(1L, portion.quantity.scaled)
            assertEquals(0, portion.quantity.scale)
            assertEquals(f.unitId, portion.quantity.unitId)
            saleQuantity += portion.quantity.scaled
            assertTrue(input.request.receivedAt < input.recordedAt)
            assertTrue(f.instant("2026-11-01T07:00:00Z") <= input.request.receivedAt)
            assertTrue(identities.add(input.operation), "No duplicate or collapsed G513 operation")
            previous?.let { prior ->
                assertTrue(prior.operation.value < input.operation.value)
                if (prior.saleOrdinal == input.saleOrdinal) {
                    assertTrue(prior.recordedAt < input.request.receivedAt)
                }
            }
            val body = LocalRetailCustomerReceiptCodec.encode(input.request)
            assertEquals(input.request, LocalRetailCustomerReceiptCodec.decode(body))
            assertEquals(body, LocalRetailCustomerReceiptCodec.normalize(body))
            assertTrue(body.size <= 8_192)
            maximumRequestBytes = maxOf(maximumRequestBytes, body.size)
            byteTotal += body.size
            previous = input
            count++
        }
        assertEquals(64L, saleQuantity)
        assertEquals(513, currentSale)
        assertEquals(32_832, count)
        assertEquals(32_832, identities.size)
        assertTrue(byteTotal > 65_536, "The full corpus is intentionally larger than one sale aggregate")
        assertTrue(maximumRequestBytes in 1..8_192)
        val final = assertNotNull(previous)
        assertEquals("f0000000-0000-4000-8000-000002010040", final.operation.value)
        assertEquals("2026-11-03T10:42:09Z", final.recordedAt.canonicalString())
        val first = f.growth(513).first()
        assertEquals("d0000000-0000-4000-8000-000000010000", first.request.saleId.value)
        assertEquals("c0000000-0000-4000-8000-000000010000", first.request.portions.single().portionId.value)
        assertEquals("e0000000-0000-4000-8000-000000010000", first.finalize.value)
        assertEquals("f0000000-0000-4000-8000-000000010001", first.operation.value)
        assertEquals("2026-11-03T10:30:02Z", first.request.receivedAt.canonicalString())
        assertEquals("2026-11-03T10:30:03Z", first.recordedAt.canonicalString())
        assertEquals(0, f.growth(0).count())
        assertEquals(4_160, f.growth(65).count())
    }

    @Test
    fun ordinaryOverlapAndRestartPhaseInputsRetainTheirExactFrozenLastCommitInstants() {
        val allPhases = f.root.getValue("F-GROWTH").jsonObject.getValue("growthPhases").jsonObject
        for (schedule in listOf("RSEL-01-02-03-06-07", "RSEL-04", "RSEL-05")) {
            val expected = allPhases.getValue(schedule).jsonArray
            val firstPhaseLast = f.growth(65, schedule).last()
            val final = f.growth(513, schedule).last()
            assertEquals(expected[0].jsonObject.text("lastCommit"), firstPhaseLast.recordedAt.canonicalString())
            assertEquals(expected[1].jsonObject.text("lastCommit"), final.recordedAt.canonicalString())
            assertEquals(64, firstPhaseLast.eventOrdinal)
            assertEquals(64, final.eventOrdinal)
            assertEquals(65, firstPhaseLast.saleOrdinal)
            assertEquals(513, final.saleOrdinal)
        }
        val wCommit = f.instant("2026-11-03T11:00:00Z")
        val overlapStart = f.growth(513, "RSEL-04").first { it.saleOrdinal == 66 }
        val overlapEnd = f.growth(513, "RSEL-04").last()
        assertTrue(overlapStart.recordedAt < wCommit && overlapEnd.recordedAt > wCommit)
        val restartFirst = f.growth(513, "RSEL-05").first()
        val restartLast = f.growth(513, "RSEL-05").last()
        assertTrue(restartFirst.request.receivedAt > wCommit)
        assertTrue(restartLast.recordedAt < f.instant("2026-11-03T11:06:00Z"))
        val later = f.selectedWrite("L")
        assertEquals(f.instant("2026-11-03T11:05:00Z"), later.request.receivedAt)
        assertEquals(f.instant("2026-11-03T11:06:00Z"), later.recordedAt)
        // A delayed L dispatch is permitted; the frozen observed instant is not
        // rewritten to the end of growth or to the authoritative commit instant.
    }
}
