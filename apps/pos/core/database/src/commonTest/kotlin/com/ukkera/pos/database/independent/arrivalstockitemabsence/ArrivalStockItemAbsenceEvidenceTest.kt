package com.ukkera.pos.database.independent.arrivalstockitemabsence

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.StockIndexRecordV2
import com.ukkera.pos.database.StockQueryV2
import com.ukkera.pos.database.StockReadEvidenceV1
import com.ukkera.pos.database.StockRelationalKindsV2
import com.ukkera.pos.database.StreamedSourceObservationV2
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Frozen ASIA v1 slices. Literal evidence does not establish source truth or arrival authority. */
class ArrivalStockItemAbsenceEvidenceTest {
    @Test
    fun itemAndNonStockAbsenceRoundTripWithDistinctMeaning() {
        // ASIA-01/03: the requested SKU is identical; the absent source kinds are distinct.
        val item = StreamedSourceObservationV2.StockItemAbsent(ASIA_ITEM)
        val nonStock = StreamedSourceObservationV2.CurrentAbsent(ASIA_NON_STOCK)
        val observed = asiaObservations(listOf(item, nonStock))

        assertEquals(setOf(item, nonStock), observed.sources.toSet())
        assertEquals(2, observed.sources.size)
        assertTrue(observed.absentStockItem(ASIA_ITEM))
        assertFalse(observed.absent(ASIA_ITEM))
        assertTrue(observed.absent(ASIA_NON_STOCK))
        assertFalse(observed.absentStockItem(ASIA_NON_STOCK))
        assertNull(observed.current(ASIA_ITEM))
        assertNull(observed.current(ASIA_NON_STOCK))
        assertNull(observed.claim(ClaimKey("local.stock.item-id", ASIA_SKU_A.value)))
        assertTrue(observed.sources.none { it is StreamedSourceObservationV2.Claim })

        // These are data-only read-artifact proposals, never normal item/stock/money writes.
        val changes = StockReadEvidenceV1.proposedChanges(ASIA_SCOPE, ASIA_PRODUCER, listOf(item))
        assertTrue(changes.isNotEmpty())
        assertTrue(changes.all { it.key.kind == StockReadEvidenceV1.KIND && it.expectedRevision == null })
    }

    @Test
    fun itemAbsenceRejectsOtherKindsAndInvalidCoordinates() {
        // ASIA-02/07: no position, value, activation or non-stock absence is relabeled as item absence.
        for (kind in StockRelationalKindsV2.all - StockRelationalKindsV2.ITEM) {
            assertFails(kind) { StreamedSourceObservationV2.StockItemAbsent(AggregateKey(kind, ASIA_SKU_A)) }
        }
        assertFails { StreamedSourceObservationV2.StockItemAbsent(ASIA_NON_STOCK) }
        for (kind in StockRelationalKindsV2.all) {
            assertFails(kind) { StreamedSourceObservationV2.CurrentAbsent(AggregateKey(kind, ASIA_SKU_A)) }
        }
        for (id in listOf("", "not-a-domain-id", "EE3FAD73-3A68-4225-A5EC-701B656B3935")) {
            assertFails { StreamedSourceObservationV2.StockItemAbsent(
                AggregateKey(StockRelationalKindsV2.ITEM, DomainId(id)),
            ) }
        }

        val changes = StockReadEvidenceV1.proposedChanges(ASIA_SCOPE, ASIA_PRODUCER,
            listOf(StreamedSourceObservationV2.StockItemAbsent(ASIA_ITEM)))
        assertFails {
            StockReadEvidenceV1.decodeChanges(
                ASIA_SCOPE.copy(companyId = DomainId("4fbca744-6a3f-4b4f-bb9c-fac3db509d45")),
                ASIA_PRODUCER, changes,
            )
        }
    }

    @Test
    fun aReadCutCannotBeBothPresentAndAbsentOrMakeAbsencePermanent() {
        // ASIA-01/08: a later independent present observation must not inherit the earlier absence.
        val absent = StreamedSourceObservationV2.StockItemAbsent(ASIA_ITEM)
        val before = asiaObservations(listOf(absent))
        val present = asiaCurrent()
        assertFails { StockReadEvidenceV1.proposedChanges(ASIA_SCOPE, ASIA_PRODUCER, listOf(absent, present)) }
        val after = asiaObservations(listOf(present))

        assertEquals(present, after.current(ASIA_ITEM))
        assertEquals(7L, after.current(ASIA_ITEM)?.generation?.value)
        assertFalse(after.absentStockItem(ASIA_ITEM))
        assertFalse(after.absent(ASIA_ITEM))
        assertTrue(before.absentStockItem(ASIA_ITEM))
        assertNull(before.current(ASIA_ITEM))
        assertEquals(listOf(absent), before.sources)
    }

    @Test
    fun absentItemCannotSupplyMissingPositionOrValueMembers() {
        // ASIA-07/08: a real literal range member still needs its exact current row source.
        val examples: List<Pair<StockQueryV2, StockIndexRecordV2>> = listOf(
            StockQueryV2.LotPositions(ASIA_SKU_B, ASIA_LOT) to asiaPosition(),
            StockQueryV2.PoolValues(ASIA_WAREHOUSE) to asiaValue(),
        )
        for ((query, row) in examples) {
            val range = StockReadEvidenceV1.proposedRangeSources(query, listOf(row))
            val absent = StreamedSourceObservationV2.StockItemAbsent(ASIA_ITEM)
            assertFails { StockReadEvidenceV1.proposedChanges(ASIA_SCOPE, ASIA_PRODUCER, range + absent) }
            assertFails {
                StockReadEvidenceV1.proposedChanges(ASIA_SCOPE, ASIA_PRODUCER,
                    range + absent + asiaCurrent(row.identity.key, generation = 8))
            }
            assertFails {
                StockReadEvidenceV1.proposedChanges(ASIA_SCOPE, ASIA_PRODUCER,
                    range + absent + asiaCurrent(row.identity.key, text = "different literal payload"))
            }
        }
    }

    @Test
    fun completeRangeRetainsAllMembersAndRejectsMissingOrChangedTerminals() {
        // ASIA-03/07/08: item absence neither truncates a range nor supplies its EOF/membership.
        val query = StockQueryV2.LotPositions(ASIA_SKU_B, ASIA_LOT)
        val rows: List<StockIndexRecordV2> = listOf(asiaPosition(20), asiaPosition(21))
        val range = StockReadEvidenceV1.proposedRangeSources(query, rows)
        val points = rows.map { asiaCurrent(it.identity.key) } +
            StreamedSourceObservationV2.StockItemAbsent(ASIA_ITEM)
        val observed = asiaObservations(range + points)
        val read = mutableListOf<StockIndexRecordV2>()
        observed.range(query, read::add)
        assertEquals(rows, read)
        assertTrue(observed.absentStockItem(ASIA_ITEM))
        assertTrue(rows.all { observed.current(it.identity.key) != null })

        assertFails { StockReadEvidenceV1.proposedChanges(ASIA_SCOPE, ASIA_PRODUCER,
            range.filterNot { it is StreamedSourceObservationV2.StockRange } + points) }
        val terminal = range.filterIsInstance<StreamedSourceObservationV2.StockRange>().single()
        assertFails { StockReadEvidenceV1.proposedChanges(ASIA_SCOPE, ASIA_PRODUCER,
            range.map { if (it is StreamedSourceObservationV2.StockRange) terminal.copy(count = 1) else it } + points) }
        assertFails { StockReadEvidenceV1.proposedChanges(ASIA_SCOPE, ASIA_PRODUCER,
            range.map { if (it is StreamedSourceObservationV2.StockMember && it.ordinal == 0L)
                it.copy(ordinal = 2) else it } + points) }
    }
}
