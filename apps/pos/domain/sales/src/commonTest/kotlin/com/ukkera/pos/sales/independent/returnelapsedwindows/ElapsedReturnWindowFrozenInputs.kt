package com.ukkera.pos.sales.independent.returnelapsedwindows

/** Exact numeric projections from return-elapsed-windows-v1; no policy or evidence is inferred. */
internal data class FrozenElapsedInstantTuple(
    val epochSecond: String,
    val nanosecond: String,
)

internal data class FrozenElapsedTimingVector(
    val id: String,
    val approvedDays: Int,
    val durationHours: String,
    val durationSeconds: String,
    val approvedStart: FrozenElapsedInstantTuple,
    val authoritativeSubmission: FrozenElapsedInstantTuple,
    val expectedExpiry: FrozenElapsedInstantTuple,
    val expectedTiming: String,
)

internal data class FrozenNonpositivePeriodVector(
    val id: String,
    val receiptWindowDays: String,
    val expectedState: String,
)

/**
 * Frozen inputs: qa/pos/designs/return-elapsed-windows-v1/fixtures.json.
 * Freeze digest: b2e49ebf555b7d531bdbb73dc9bd083af5dc3fe8731709f55e45d3ee0a0887b2.
 *
 * Only numeric fields exercised by the timing helper are projected. UTC text is
 * not a canonical-wire oracle. Zone/configuration, processing-history, source
 * trust, applicability, allocation, qualification and all business effects remain
 * outside this pure helper binding. The wide tuples do not assert an ISO range.
 *
 * Columns: id|approvedDays|durationHours|durationSeconds|start seconds|start nanos|
 * submission seconds|submission nanos|expiry seconds|expiry nanos|expectedTiming.
 */
internal object ElapsedReturnWindowFrozenInputs {
    // configurable_periods
    val configurablePeriods: List<FrozenElapsedTimingVector> = timingVectors(
        """
        CFG-14|14|336|1209600|1790858096|250000000|1792154096|250000000|1792067696|250000000|OUTSIDE_WINDOW
        CFG-21|21|504|1814400|1790858096|250000000|1792154096|250000000|1792672496|250000000|WITHIN_WINDOW
        CFG-30|30|720|2592000|1790858096|250000000|1792154096|250000000|1793450096|250000000|WITHIN_WINDOW
        CFG-45|45|1080|3888000|1790858096|250000000|1792154096|250000000|1794746096|250000000|WITHIN_WINDOW
        CFG-90|90|2160|7776000|1790858096|250000000|1792154096|250000000|1798634096|250000000|WITHIN_WINDOW
        """.trimIndent(),
    )

    // millisecond_boundary
    val millisecondBoundary: List<FrozenElapsedTimingVector> = timingVectors(
        """
        MS14-BEFORE|14|336|1209600|1790858096|250000000|1792067696|249000000|1792067696|250000000|WITHIN_WINDOW
        MS14-AT|14|336|1209600|1790858096|250000000|1792067696|250000000|1792067696|250000000|WITHIN_WINDOW
        MS14-AFTER|14|336|1209600|1790858096|250000000|1792067696|251000000|1792067696|250000000|OUTSIDE_WINDOW
        """.trimIndent(),
    )

    // nanosecond_boundary
    val nanosecondBoundary: List<FrozenElapsedTimingVector> = timingVectors(
        """
        NS14-BEFORE|14|336|1209600|1790858096|123456789|1792067696|123456788|1792067696|123456789|WITHIN_WINDOW
        NS14-AT|14|336|1209600|1790858096|123456789|1792067696|123456789|1792067696|123456789|WITHIN_WINDOW
        NS14-AFTER|14|336|1209600|1790858096|123456789|1792067696|123456790|1792067696|123456789|OUTSIDE_WINDOW
        """.trimIndent(),
    )

    // fractional_carry
    val fractionalCarry: List<FrozenElapsedTimingVector> = timingVectors(
        """
        CARRY14-BEFORE|14|336|1209600|1790858096|999999999|1792067696|999999998|1792067696|999999999|WITHIN_WINDOW
        CARRY14-AT|14|336|1209600|1790858096|999999999|1792067696|999999999|1792067696|999999999|WITHIN_WINDOW
        CARRY14-AFTER-NEXT-SECOND|14|336|1209600|1790858096|999999999|1792067697|000000000|1792067696|999999999|OUTSIDE_WINDOW
        """.trimIndent(),
    )

    // calendar_crossings
    val calendarCrossings: List<FrozenElapsedTimingVector> = timingVectors(
        """
        LEAP14-BEFORE|14|336|1209600|1708063200|500000000|1709272800|499999999|1709272800|500000000|WITHIN_WINDOW
        LEAP14-AT|14|336|1209600|1708063200|500000000|1709272800|500000000|1709272800|500000000|WITHIN_WINDOW
        LEAP14-AFTER|14|336|1209600|1708063200|500000000|1709272800|500000001|1709272800|500000000|OUTSIDE_WINDOW
        MONTH30-BEFORE|30|720|2592000|1769885112|250000000|1772477112|249999999|1772477112|250000000|WITHIN_WINDOW
        MONTH30-AT|30|720|2592000|1769885112|250000000|1772477112|250000000|1772477112|250000000|WITHIN_WINDOW
        MONTH30-AFTER|30|720|2592000|1769885112|250000000|1772477112|250000001|1772477112|250000000|OUTSIDE_WINDOW
        LEAP365-BEFORE|365|8760|31536000|1709164800|125000000|1740700800|124999999|1740700800|125000000|WITHIN_WINDOW
        LEAP365-AT|365|8760|31536000|1709164800|125000000|1740700800|125000000|1740700800|125000000|WITHIN_WINDOW
        LEAP365-AFTER|365|8760|31536000|1709164800|125000000|1740700800|125000001|1740700800|125000000|OUTSIDE_WINDOW
        MIDNIGHT1-BEFORE|1|24|86400|1790899199|999999999|1790985599|999999998|1790985599|999999999|WITHIN_WINDOW
        MIDNIGHT1-AT|1|24|86400|1790899199|999999999|1790985599|999999999|1790985599|999999999|WITHIN_WINDOW
        MIDNIGHT1-AFTER|1|24|86400|1790899199|999999999|1790985600|000000000|1790985599|999999999|OUTSIDE_WINDOW
        """.trimIndent(),
    )

    // dst_utc_invariance.vectors
    val dstUtcTuples: List<FrozenElapsedTimingVector> = timingVectors(
        """
        CAIRO-SPRING14-BEFORE|14|336|1209600|1775781000|250000000|1776990600|249000000|1776990600|250000000|WITHIN_WINDOW
        CAIRO-SPRING14-AT|14|336|1209600|1775781000|250000000|1776990600|250000000|1776990600|250000000|WITHIN_WINDOW
        CAIRO-SPRING14-AFTER|14|336|1209600|1775781000|250000000|1776990600|251000000|1776990600|250000000|OUTSIDE_WINDOW
        CAIRO-FALL14-BEFORE|14|336|1209600|1792110600|250000000|1793320200|249000000|1793320200|250000000|WITHIN_WINDOW
        CAIRO-FALL14-AT|14|336|1209600|1792110600|250000000|1793320200|250000000|1793320200|250000000|WITHIN_WINDOW
        CAIRO-FALL14-AFTER|14|336|1209600|1792110600|250000000|1793320200|251000000|1793320200|250000000|OUTSIDE_WINDOW
        """.trimIndent(),
    )

    // dst_utc_invariance.repeatedWallReadingProbe.vectors
    val repeatedOffsetTuples: List<FrozenElapsedTimingVector> = timingVectors(
        """
        REPEATED-EARLIER-OFFSET|14|336|1209600|1792099800|250000000|1793305800|250000000|1793309400|250000000|WITHIN_WINDOW
        REPEATED-EXACT-LATER-OFFSET|14|336|1209600|1792099800|250000000|1793309400|250000000|1793309400|250000000|WITHIN_WINDOW
        REPEATED-AFTER|14|336|1209600|1792099800|250000000|1793309400|251000000|1793309400|250000000|OUTSIDE_WINDOW
        """.trimIndent(),
    )

    // closing_cutoff.vectors
    val closingCutoffTuples: List<FrozenElapsedTimingVector> = timingVectors(
        """
        CLOSE14-BEFORE|14|336|1209600|1790877900|250000000|1792087500|249000000|1792087500|250000000|WITHIN_WINDOW
        CLOSE14-AT|14|336|1209600|1790877900|250000000|1792087500|250000000|1792087500|250000000|WITHIN_WINDOW
        CLOSE14-AFTER|14|336|1209600|1790877900|250000000|1792087500|251000000|1792087500|250000000|OUTSIDE_WINDOW
        """.trimIndent(),
    )

    // later_processing
    val laterProcessingTuples: List<FrozenElapsedTimingVector> = timingVectors(
        """
        PROCESS14-BEFORE|14|336|1209600|1790858096|250000000|1792067696|249000000|1792067696|250000000|WITHIN_WINDOW
        PROCESS14-AT|14|336|1209600|1790858096|250000000|1792067696|250000000|1792067696|250000000|WITHIN_WINDOW
        PROCESS14-AFTER|14|336|1209600|1790858096|250000000|1792067696|251000000|1792067696|250000000|OUTSIDE_WINDOW
        """.trimIndent(),
    )

    // overflow_exact_seconds
    val wideExactSeconds: List<FrozenElapsedTimingVector> = timingVectors(
        """
        WIDE-24856-DAYS-BEFORE|24856|596544|2147558400|0|250000000|2147558400|249999999|2147558400|250000000|WITHIN_WINDOW
        WIDE-24856-DAYS-AT|24856|596544|2147558400|0|250000000|2147558400|250000000|2147558400|250000000|WITHIN_WINDOW
        WIDE-24856-DAYS-AFTER|24856|596544|2147558400|0|250000000|2147558400|250000001|2147558400|250000000|OUTSIDE_WINDOW
        WIDE-MAX-PERIOD-AT|2147483647|51539607528|185542587100800|0|250000000|185542587100800|250000000|185542587100800|250000000|WITHIN_WINDOW
        WIDE-MAX-PERIOD-AFTER|2147483647|51539607528|185542587100800|0|250000000|185542587100800|250000001|185542587100800|250000000|OUTSIDE_WINDOW
        """.trimIndent(),
    )

    // promised_period.preservedOriginalTiming
    val preservedOriginalPromiseTuple: List<FrozenElapsedTimingVector> = timingVectors(
        """
        PROMISE-30-AT-DAY20|30|720|2592000|1790858096|250000000|1792586096|250000000|1793450096|250000000|WITHIN_WINDOW
        """.trimIndent(),
    )

    // promised_period.forbiddenReplacementCounterexample
    val forbiddenReplacementCounterexampleTuple: List<FrozenElapsedTimingVector> = timingVectors(
        """
        CURRENT-14-AT-DAY20|14|336|1209600|1790858096|250000000|1792586096|250000000|1792067696|250000000|OUTSIDE_WINDOW
        """.trimIndent(),
    )

    // history_multi_date.conditionalTimingOnly
    val conditionalMultiDateTuples: List<FrozenElapsedTimingVector> = timingVectors(
        """
        MULTI-IF-APPROVED-S1|14|336|1209600|1790858096|250000000|1792154096|250000000|1792067696|250000000|OUTSIDE_WINDOW
        MULTI-IF-APPROVED-S2|14|336|1209600|1791030896|250000000|1792154096|250000000|1792240496|250000000|WITHIN_WINDOW
        """.trimIndent(),
    )

    // unresolved_defect.conditionalTimingOnly
    val conditionalDefectTuples: List<FrozenElapsedTimingVector> = timingVectors(
        """
        DEFECT30-CONDITIONAL-BEFORE|30|720|2592000|1790858096|250000000|1793450096|249999999|1793450096|250000000|WITHIN_WINDOW
        DEFECT30-CONDITIONAL-AT|30|720|2592000|1790858096|250000000|1793450096|250000000|1793450096|250000000|WITHIN_WINDOW
        DEFECT30-CONDITIONAL-AFTER|30|720|2592000|1790858096|250000000|1793450096|250000001|1793450096|250000000|OUTSIDE_WINDOW
        """.trimIndent(),
    )

    // published_period_bounds: constructor validation only, not publication/activation.
    // PERIOD-OVER-SIGNED32 cannot enter the Int constructor and is intentionally unbound.
    val nonpositivePeriods: List<FrozenNonpositivePeriodVector> = listOf(
        FrozenNonpositivePeriodVector("PERIOD-ZERO", "0", "INVALID_INPUT"),
        FrozenNonpositivePeriodVector("PERIOD-NEGATIVE", "-1", "INVALID_INPUT"),
    )

    private fun timingVectors(rows: String): List<FrozenElapsedTimingVector> =
        rows.lineSequence().filter { it.isNotBlank() }.map { row ->
            val fields = row.split('|')
            require(fields.size == 11) { "Malformed frozen timing row: ${fields.firstOrNull()}" }
            FrozenElapsedTimingVector(
                id = fields[0],
                approvedDays = fields[1].toInt(),
                durationHours = fields[2],
                durationSeconds = fields[3],
                approvedStart = FrozenElapsedInstantTuple(fields[4], fields[5]),
                authoritativeSubmission = FrozenElapsedInstantTuple(fields[6], fields[7]),
                expectedExpiry = FrozenElapsedInstantTuple(fields[8], fields[9]),
                expectedTiming = fields[10],
            )
        }.toList()
}
