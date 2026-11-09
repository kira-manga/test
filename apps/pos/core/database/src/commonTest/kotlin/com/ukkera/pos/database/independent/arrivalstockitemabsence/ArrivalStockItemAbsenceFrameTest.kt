package com.ukkera.pos.database.independent.arrivalstockitemabsence

import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.RetainedCommandFrameV2
import com.ukkera.pos.database.RetainedCommandFramesV2
import com.ukkera.pos.database.StockEstateRecordV2
import com.ukkera.pos.database.StockQueryV2
import com.ukkera.pos.database.StreamedSourceObservationV2
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs

/** Real core frame codec, accessed as a friend test; no independent historical byte golden is claimed. */
class ArrivalStockItemAbsenceFrameTest {
    @Test
    fun additiveItemAbsenceKeepsEverySourceKindDistinctOnRoundTrip() {
        // ASIA-03: all prior source meanings plus the additive item absence retain identity.
        val current = asiaCurrent()
        val row = asiaPosition()
        val query = StockQueryV2.LotPositions(ASIA_SKU_B, ASIA_LOT)
        val sources = listOf(
            StreamedSourceObservationV2.Aggregate(current.key, current.generation, current.schema,
                current.payload, current.payloadHash, ASIA_PRODUCER),
            StreamedSourceObservationV2.Claim(ClaimKey("qualification.source", ASIA_SKU_A.value), null),
            current,
            StreamedSourceObservationV2.StockRange(query, 0, sha256(byteArrayOf())),
            StreamedSourceObservationV2.StockMember(query, 0, row),
            StreamedSourceObservationV2.CurrentAbsent(ASIA_NON_STOCK),
            StreamedSourceObservationV2.EstateMember(0,
                StockEstateRecordV2.Aggregate(current.key, current.generation, current.schema,
                    current.payload, current.payloadHash)),
            StreamedSourceObservationV2.EstateEnd(0, sha256(byteArrayOf())),
            StreamedSourceObservationV2.StockItemAbsent(ASIA_ITEM),
        )
        val tags = mutableListOf<Int>()
        val decoded = sources.map { source ->
            val bytes = RetainedCommandFramesV2.encode(RetainedCommandFrameV2.Source(source))
            try {
                tags += asiaInt(bytes, asiaSourceTagOffset(bytes))
                assertIs<RetainedCommandFrameV2.Source>(RetainedCommandFramesV2.decode(bytes)).value
            } finally { bytes.fill(0) }
        }
        assertEquals(sources, decoded)
        assertEquals(sources.size, tags.toSet().size)
    }

    @Test
    fun unknownKindsAndEveryTruncatedItemAbsencePrefixAreRefused() {
        // ASIA-03: malformed framing cannot silently become any kind of absence.
        val bytes = RetainedCommandFramesV2.encode(RetainedCommandFrameV2.Source(
            StreamedSourceObservationV2.StockItemAbsent(ASIA_ITEM),
        ))
        try {
            for (length in 0 until bytes.size) {
                assertFails("prefix length $length") { RetainedCommandFramesV2.decode(bytes.copyOf(length)) }
            }
            for (unknown in listOf(0, -1, Int.MAX_VALUE)) {
                val changed = bytes.copyOf()
                try {
                    asiaPutInt(changed, asiaSourceTagOffset(changed), unknown)
                    assertFails { RetainedCommandFramesV2.decode(changed) }
                } finally { changed.fill(0) }
            }
        } finally { bytes.fill(0) }
    }

    @Test
    fun unsupportedFrameVersionsAndNegativeStorageGenerationAreRefused() {
        // ASIA-02/04: retained row storage generations remain distinct from domain source revision zero.
        for (generation in listOf(7L, Long.MAX_VALUE)) {
            val source = asiaCurrent(generation = generation)
            val bytes = RetainedCommandFramesV2.encode(RetainedCommandFrameV2.Source(source))
            try {
                assertEquals(source, assertIs<RetainedCommandFrameV2.Source>(RetainedCommandFramesV2.decode(bytes)).value)
                val tagAt = asiaSourceTagOffset(bytes)
                var generationAt = tagAt + 4
                generationAt += 4 + asiaInt(bytes, generationAt)
                generationAt += 4 + asiaInt(bytes, generationAt)
                val changed = bytes.copyOf()
                try {
                    asiaPutLong(changed, generationAt, -1)
                    assertFails { RetainedCommandFramesV2.decode(changed) }
                } finally { changed.fill(0) }
            } finally { bytes.fill(0) }
        }
        assertFails { Revision(-1) }

        val absence = RetainedCommandFramesV2.encode(RetainedCommandFrameV2.Source(
            StreamedSourceObservationV2.StockItemAbsent(ASIA_ITEM),
        ))
        try {
            val versionAt = 4 + asiaInt(absence, 0) - 1
            for (unsupportedVersion in listOf('1', '3')) {
                val changed = absence.copyOf()
                try {
                    changed[versionAt] = unsupportedVersion.code.toByte()
                    assertFails { RetainedCommandFramesV2.decode(changed) }
                } finally { changed.fill(0) }
            }
        } finally { absence.fill(0) }
    }
}
