package com.ukkera.pos.sales.store.independent.reportcompletev2

import com.ukkera.pos.sales.store.LocalBranchReportSectionCsvV2
import com.ukkera.pos.security.sha256
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CSV assembly slices only; source reads, confirmation authorization and native IO are separate. */
class CompleteReportFrozenCsvSlicesTest {
    private val f = FrozenCompleteReportInputs
    private val bound = FrozenCompleteCsvSupplementInputs

    @Test
    fun RE32_002_completeF12MatchesTheIndependentCsvAndPreservesEverySourceRecord() {
        val fixture = bound.f12
        val bytes = assemble(fixture)
        try {
            assertContentEquals(checkNotNull(fixture.expectedBytes), bytes)
            assertMatchesFrozenContainer(fixture, bytes)
            val records = observeQuotedReportCsv(bytes)
            assertEquals(1, records.count { it == bound.header })
            val documentIndex = bound.header.indexOf("document_id")
            val amountIndex = bound.header.indexOf("minor_units")
            val pendingIndex = bound.header.indexOf("pending_count")
            val businessRows = records.drop(1).filter { it[documentIndex].isNotEmpty() }
            assertEquals(f.sourceKeys.map { it.value }, businessRows.map { it[documentIndex] })
            assertEquals(f.sourceMinorAmounts.map { it.toString() }, businessRows.map { it[amountIndex] })
            assertEquals(List(12) { "1" }, businessRows.map { it[pendingIndex] })
        } finally { bytes.fill(0) }
    }

    @Test
    fun RE32_002_emptySectionRetainsMetadataWithoutABusinessDocument() {
        val fixture = bound.empty
        val bytes = assemble(fixture)
        try {
            assertContentEquals(checkNotNull(fixture.expectedBytes), bytes)
            assertMatchesFrozenContainer(fixture, bytes)
            val records = observeQuotedReportCsv(bytes)
            val documentIndex = bound.header.indexOf("document_id")
            assertEquals(0, records.drop(1).count { it[documentIndex].isNotEmpty() })
        } finally { bytes.fill(0) }
    }

    @Test
    fun RE32_003_repeatedBackwardAndCyclicCursorsCannotReturnACompletePrefix() {
        val fixture = bound.f12
        val variants = listOf(1 to f.key(5), 1 to f.key(4), 2 to f.key(5))
        for ((acceptedPages, badNext) in variants) {
            val csv = LocalBranchReportSectionCsvV2(fixture.query)
            try {
                for (page in fixture.pages.take(acceptedPages)) csv.append(page.page, page.revision, page.observedAt)
                val faulted = fixture.pages[acceptedPages]
                assertFails {
                    csv.append(faulted.page.copy(nextAfter = badNext), faulted.revision, faulted.observedAt)
                }
                assertFails { csv.finish(fixture.confirmedRevision, fixture.confirmedAt) }
            } finally { csv.clear() }
        }
    }

    @Test
    fun RE32_004_changedSourceRevisionCannotCompleteTheAcceptedPrefix() {
        val fixture = bound.f12
        val csv = LocalBranchReportSectionCsvV2(fixture.query)
        try {
            val first = fixture.pages[0]
            csv.append(first.page, first.revision, first.observedAt)
            val second = fixture.pages[1]
            assertFails { csv.append(second.page, f.changedRevision, second.observedAt) }
            assertFails { csv.finish(fixture.confirmedRevision, fixture.confirmedAt) }
        } finally { csv.clear() }
    }

    @Test
    fun RE32_S203_nonemptyMetriclessDocumentCannotBeSilentlyOmitted() {
        val fixture = bound.f12
        val csv = LocalBranchReportSectionCsvV2(fixture.query)
        try {
            val first = fixture.pages[0]
            csv.append(first.page, first.revision, first.observedAt)
            val second = fixture.pages[1]
            val metricless = second.page.copy(rows = second.page.rows.map { row ->
                if (row.id == f.key(6)) row.copy(amounts = emptyList()) else row
            })
            assertFails { csv.append(metricless, second.revision, second.observedAt) }
            assertFails { csv.finish(fixture.confirmedRevision, fixture.confirmedAt) }
        } finally { csv.clear() }
    }

    @Test
    fun RE32_006_nonterminalOrChangedConfirmingCutCannotProduceACompleteContainer() {
        val fixture = bound.f12
        val nonterminal = LocalBranchReportSectionCsvV2(fixture.query)
        try {
            val first = fixture.pages.first()
            nonterminal.append(first.page, first.revision, first.observedAt)
            assertFails { nonterminal.finish(fixture.confirmedRevision, fixture.confirmedAt) }
        } finally { nonterminal.clear() }
        val changedConfirmation = LocalBranchReportSectionCsvV2(fixture.query)
        try {
            for (page in fixture.pages) changedConfirmation.append(page.page, page.revision, page.observedAt)
            assertFails { changedConfirmation.finish(f.changedRevision, fixture.confirmedAt) }
        } finally { changedConfirmation.clear() }
    }

    @Test
    fun RE32_007_128DataPagesAllowSeparateCompletionWithinTheByteBound() {
        val fixture = bound.pages128
        val csv = LocalBranchReportSectionCsvV2(fixture.query)
        try {
            for (page in fixture.pages) csv.append(page.page, page.revision, page.observedAt)
            assertEquals(128, csv.pageCount)
            val bytes = csv.finish(fixture.confirmedRevision, fixture.confirmedAt)
            try {
                assertEquals(128, csv.pageCount)
                assertMatchesFrozenContainer(fixture, bytes)
                assertTrue(bytes.size <= 65536)
            } finally { bytes.fill(0) }
        } finally { csv.clear() }
    }

    @Test
    fun RE32_007_128NonterminalDataPagesCannotReturnACompletePrefix() {
        val fixture = bound.pages129
        val csv = LocalBranchReportSectionCsvV2(fixture.query)
        try {
            for (page in fixture.pages.take(128)) csv.append(page.page, page.revision, page.observedAt)
            assertEquals(128, csv.pageCount)
            assertFails { csv.finish(fixture.confirmedRevision, fixture.confirmedAt) }
            // No page129 response is synthesized; Master dispatch refusal is a separate check.
        } finally { csv.clear() }
    }

    @Test
    fun RE32_008_validCsvUnderAndEqualToTheFinalByteBoundMatchTheIndependentBytes() {
        for (fixture in listOf(bound.bytesUnder, bound.bytesEqual)) {
            val bytes = assemble(fixture)
            try { assertMatchesFrozenContainer(fixture, bytes) } finally { bytes.fill(0) }
        }
    }

    @Test
    fun RE32_008_validCsvOverTheFinalByteBoundCannotReturnAPrefix() {
        var completed: ByteArray? = null
        try {
            assertFails { completed = assemble(bound.bytesOver) }
            assertNull(completed)
        } finally { completed?.fill(0) }
    }

    private fun assemble(fixture: BoundCompleteCsvFixture): ByteArray {
        val csv = LocalBranchReportSectionCsvV2(fixture.query)
        return try {
            for (page in fixture.pages) csv.append(page.page, page.revision, page.observedAt)
            csv.finish(fixture.confirmedRevision, fixture.confirmedAt)
        } finally { csv.clear() }
    }

    private fun assertMatchesFrozenContainer(fixture: BoundCompleteCsvFixture, bytes: ByteArray) {
        assertEquals(checkNotNull(fixture.expectedByteCount), bytes.size)
        assertEquals(checkNotNull(fixture.expectedSha256), sha256(bytes).value)
        val records = observeQuotedReportCsv(bytes)
        assertEquals(bound.header, records.first())
        val documentIndex = bound.header.indexOf("document_id")
        assertEquals(fixture.expectedKeys, records.drop(1).map { it[documentIndex] }.filter { it.isNotEmpty() })
        assertEquals(checkNotNull(fixture.expectedCompletionRecord), records.last())
    }
}
