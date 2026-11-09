package com.ukkera.pos.sales.store.independent.reportcompletev2

import com.ukkera.pos.sales.store.LocalBranchReportAmountV1
import com.ukkera.pos.sales.store.LocalBranchReportDeliveryV1
import com.ukkera.pos.sales.store.LocalBranchReportMetricV1
import com.ukkera.pos.sales.store.LocalBranchReportQueryV1
import com.ukkera.pos.sales.store.LocalBranchReportResultV1
import com.ukkera.pos.sales.store.LocalBranchReportRowV1
import com.ukkera.pos.sales.store.LocalBranchReportSectionV1
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

internal data class BoundCsvPage(val page: LocalBranchReportResultV1.Page, val revision: Revision,
    val observedAt: UtcInstant)

internal data class BoundCompleteCsvFixture(
    val query: LocalBranchReportQueryV1,
    val pages: List<BoundCsvPage>,
    val confirmedRevision: Revision,
    val confirmedAt: UtcInstant,
    val expectedByteCount: Int?,
    val expectedSha256: String?,
    val expectedKeys: List<String>,
    val expectedCompletionRecord: List<String>?,
    val expectedBytes: ByteArray? = null,
)

/** Exact inputs and reference hashes from RE32-SUPPLEMENT-v2.0, including its final binding annex. */
internal object FrozenCompleteCsvSupplementInputs {
    val header = listOf("profile", "section", "branch_revision", "observed_at_utc", "page_after", "next_after", "document_id", "document_revision", "related_document_id", "cash_session_id", "status", "cash_session_business_date", "pending_count", "metric", "currency", "minor_units")
    private val dataTimes = listOf(
        "2026-10-30T12:00:01Z",
        "2026-10-30T12:00:02Z",
        "2026-10-30T12:00:03Z",
        "2026-10-30T12:00:04Z",
        "2026-10-30T12:00:05Z",
        "2026-10-30T12:00:06Z",
        "2026-10-30T12:00:07Z",
        "2026-10-30T12:00:08Z",
        "2026-10-30T12:00:09Z",
        "2026-10-30T12:00:10Z",
        "2026-10-30T12:00:11Z",
        "2026-10-30T12:00:12Z",
        "2026-10-30T12:00:13Z",
        "2026-10-30T12:00:14Z",
        "2026-10-30T12:00:15Z",
        "2026-10-30T12:00:16Z",
        "2026-10-30T12:00:17Z",
        "2026-10-30T12:00:18Z",
        "2026-10-30T12:00:19Z",
        "2026-10-30T12:00:20Z",
        "2026-10-30T12:00:21Z",
        "2026-10-30T12:00:22Z",
        "2026-10-30T12:00:23Z",
        "2026-10-30T12:00:24Z",
        "2026-10-30T12:00:25Z",
        "2026-10-30T12:00:26Z",
        "2026-10-30T12:00:27Z",
        "2026-10-30T12:00:28Z",
        "2026-10-30T12:00:29Z",
        "2026-10-30T12:00:30Z",
        "2026-10-30T12:00:31Z",
        "2026-10-30T12:00:32Z",
        "2026-10-30T12:00:33Z",
        "2026-10-30T12:00:34Z",
        "2026-10-30T12:00:35Z",
        "2026-10-30T12:00:36Z",
        "2026-10-30T12:00:37Z",
        "2026-10-30T12:00:38Z",
        "2026-10-30T12:00:39Z",
        "2026-10-30T12:00:40Z",
        "2026-10-30T12:00:41Z",
        "2026-10-30T12:00:42Z",
        "2026-10-30T12:00:43Z",
        "2026-10-30T12:00:44Z",
        "2026-10-30T12:00:45Z",
        "2026-10-30T12:00:46Z",
        "2026-10-30T12:00:47Z",
        "2026-10-30T12:00:48Z",
        "2026-10-30T12:00:49Z",
        "2026-10-30T12:00:50Z",
        "2026-10-30T12:00:51Z",
        "2026-10-30T12:00:52Z",
        "2026-10-30T12:00:53Z",
        "2026-10-30T12:00:54Z",
        "2026-10-30T12:00:55Z",
        "2026-10-30T12:00:56Z",
        "2026-10-30T12:00:57Z",
        "2026-10-30T12:00:58Z",
        "2026-10-30T12:00:59Z",
        "2026-10-30T12:01:00Z",
        "2026-10-30T12:01:01Z",
        "2026-10-30T12:01:02Z",
        "2026-10-30T12:01:03Z",
        "2026-10-30T12:01:04Z",
        "2026-10-30T12:01:05Z",
        "2026-10-30T12:01:06Z",
        "2026-10-30T12:01:07Z",
        "2026-10-30T12:01:08Z",
        "2026-10-30T12:01:09Z",
        "2026-10-30T12:01:10Z",
        "2026-10-30T12:01:11Z",
        "2026-10-30T12:01:12Z",
        "2026-10-30T12:01:13Z",
        "2026-10-30T12:01:14Z",
        "2026-10-30T12:01:15Z",
        "2026-10-30T12:01:16Z",
        "2026-10-30T12:01:17Z",
        "2026-10-30T12:01:18Z",
        "2026-10-30T12:01:19Z",
        "2026-10-30T12:01:20Z",
        "2026-10-30T12:01:21Z",
        "2026-10-30T12:01:22Z",
        "2026-10-30T12:01:23Z",
        "2026-10-30T12:01:24Z",
        "2026-10-30T12:01:25Z",
        "2026-10-30T12:01:26Z",
        "2026-10-30T12:01:27Z",
        "2026-10-30T12:01:28Z",
        "2026-10-30T12:01:29Z",
        "2026-10-30T12:01:30Z",
        "2026-10-30T12:01:31Z",
        "2026-10-30T12:01:32Z",
        "2026-10-30T12:01:33Z",
        "2026-10-30T12:01:34Z",
        "2026-10-30T12:01:35Z",
        "2026-10-30T12:01:36Z",
        "2026-10-30T12:01:37Z",
        "2026-10-30T12:01:38Z",
        "2026-10-30T12:01:39Z",
        "2026-10-30T12:01:40Z",
        "2026-10-30T12:01:41Z",
        "2026-10-30T12:01:42Z",
        "2026-10-30T12:01:43Z",
        "2026-10-30T12:01:44Z",
        "2026-10-30T12:01:45Z",
        "2026-10-30T12:01:46Z",
        "2026-10-30T12:01:47Z",
        "2026-10-30T12:01:48Z",
        "2026-10-30T12:01:49Z",
        "2026-10-30T12:01:50Z",
        "2026-10-30T12:01:51Z",
        "2026-10-30T12:01:52Z",
        "2026-10-30T12:01:53Z",
        "2026-10-30T12:01:54Z",
        "2026-10-30T12:01:55Z",
        "2026-10-30T12:01:56Z",
        "2026-10-30T12:01:57Z",
        "2026-10-30T12:01:58Z",
        "2026-10-30T12:01:59Z",
        "2026-10-30T12:02:00Z",
        "2026-10-30T12:02:01Z",
        "2026-10-30T12:02:02Z",
        "2026-10-30T12:02:03Z",
        "2026-10-30T12:02:04Z",
        "2026-10-30T12:02:05Z",
        "2026-10-30T12:02:06Z",
        "2026-10-30T12:02:07Z",
        "2026-10-30T12:02:08Z",
    )
    private val exactF12Bytes = listOf(
        "\"profile\",\"section\",\"branch_revision\",\"observed_at_utc\",\"page_after\",\"next_after\",\"document_id\",\"document_revision\",\"related_document_id\",\"cash_session_id\",\"status\",\"cash_session_business_date\",\"pending_count\",\"metric\",\"currency\",\"minor_units\"\r\n",
        "\"current-local-documents-page/v1\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:01Z\",\"\",\"30000000-0000-4000-8000-000000000005\",\"30000000-0000-4000-8000-000000000001\",\"1\",\"31000000-0000-4000-8000-000000000001\",\"32000000-0000-4000-8000-000000000001\",\"AWAITING_RECEIPT\",\"\",\"1\",\"TRANSFER_AMOUNT\",\"EGP\",\"100\"\r\n",
        "\"current-local-documents-page/v1\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:01Z\",\"\",\"30000000-0000-4000-8000-000000000005\",\"30000000-0000-4000-8000-000000000002\",\"1\",\"31000000-0000-4000-8000-000000000002\",\"32000000-0000-4000-8000-000000000001\",\"AWAITING_RECEIPT\",\"\",\"1\",\"TRANSFER_AMOUNT\",\"EGP\",\"200\"\r\n",
        "\"current-local-documents-page/v1\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:01Z\",\"\",\"30000000-0000-4000-8000-000000000005\",\"30000000-0000-4000-8000-000000000003\",\"1\",\"31000000-0000-4000-8000-000000000003\",\"32000000-0000-4000-8000-000000000001\",\"AWAITING_RECEIPT\",\"\",\"1\",\"TRANSFER_AMOUNT\",\"EGP\",\"300\"\r\n",
        "\"current-local-documents-page/v1\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:01Z\",\"\",\"30000000-0000-4000-8000-000000000005\",\"30000000-0000-4000-8000-000000000004\",\"1\",\"31000000-0000-4000-8000-000000000004\",\"32000000-0000-4000-8000-000000000001\",\"AWAITING_RECEIPT\",\"\",\"1\",\"TRANSFER_AMOUNT\",\"EGP\",\"400\"\r\n",
        "\"current-local-documents-page/v1\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:01Z\",\"\",\"30000000-0000-4000-8000-000000000005\",\"30000000-0000-4000-8000-000000000005\",\"1\",\"31000000-0000-4000-8000-000000000005\",\"32000000-0000-4000-8000-000000000001\",\"AWAITING_RECEIPT\",\"\",\"1\",\"TRANSFER_AMOUNT\",\"EGP\",\"500\"\r\n",
        "\"current-local-documents-page/v1\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:02Z\",\"30000000-0000-4000-8000-000000000005\",\"30000000-0000-4000-8000-000000000010\",\"30000000-0000-4000-8000-000000000006\",\"1\",\"31000000-0000-4000-8000-000000000006\",\"32000000-0000-4000-8000-000000000001\",\"AWAITING_RECEIPT\",\"\",\"1\",\"TRANSFER_AMOUNT\",\"EGP\",\"600\"\r\n",
        "\"current-local-documents-page/v1\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:02Z\",\"30000000-0000-4000-8000-000000000005\",\"30000000-0000-4000-8000-000000000010\",\"30000000-0000-4000-8000-000000000007\",\"1\",\"31000000-0000-4000-8000-000000000007\",\"32000000-0000-4000-8000-000000000001\",\"AWAITING_RECEIPT\",\"\",\"1\",\"TRANSFER_AMOUNT\",\"EGP\",\"700\"\r\n",
        "\"current-local-documents-page/v1\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:02Z\",\"30000000-0000-4000-8000-000000000005\",\"30000000-0000-4000-8000-000000000010\",\"30000000-0000-4000-8000-000000000008\",\"1\",\"31000000-0000-4000-8000-000000000008\",\"32000000-0000-4000-8000-000000000001\",\"AWAITING_RECEIPT\",\"\",\"1\",\"TRANSFER_AMOUNT\",\"EGP\",\"800\"\r\n",
        "\"current-local-documents-page/v1\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:02Z\",\"30000000-0000-4000-8000-000000000005\",\"30000000-0000-4000-8000-000000000010\",\"30000000-0000-4000-8000-000000000009\",\"1\",\"31000000-0000-4000-8000-000000000009\",\"32000000-0000-4000-8000-000000000001\",\"AWAITING_RECEIPT\",\"\",\"1\",\"TRANSFER_AMOUNT\",\"EGP\",\"900\"\r\n",
        "\"current-local-documents-page/v1\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:02Z\",\"30000000-0000-4000-8000-000000000005\",\"30000000-0000-4000-8000-000000000010\",\"30000000-0000-4000-8000-000000000010\",\"1\",\"31000000-0000-4000-8000-000000000010\",\"32000000-0000-4000-8000-000000000001\",\"AWAITING_RECEIPT\",\"\",\"1\",\"TRANSFER_AMOUNT\",\"EGP\",\"1000\"\r\n",
        "\"current-local-documents-page/v1\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:03Z\",\"30000000-0000-4000-8000-000000000010\",\"\",\"30000000-0000-4000-8000-000000000011\",\"1\",\"31000000-0000-4000-8000-000000000011\",\"32000000-0000-4000-8000-000000000001\",\"AWAITING_RECEIPT\",\"\",\"1\",\"TRANSFER_AMOUNT\",\"EGP\",\"1100\"\r\n",
        "\"current-local-documents-page/v1\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:03Z\",\"30000000-0000-4000-8000-000000000010\",\"\",\"30000000-0000-4000-8000-000000000012\",\"1\",\"31000000-0000-4000-8000-000000000012\",\"32000000-0000-4000-8000-000000000001\",\"AWAITING_RECEIPT\",\"\",\"1\",\"TRANSFER_AMOUNT\",\"EGP\",\"1200\"\r\n",
        "\"complete-current-section/v2\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:30Z\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"\r\n",
    ).joinToString("").encodeToByteArray()
    private val exactEmptyBytes = listOf(
        "\"profile\",\"section\",\"branch_revision\",\"observed_at_utc\",\"page_after\",\"next_after\",\"document_id\",\"document_revision\",\"related_document_id\",\"cash_session_id\",\"status\",\"cash_session_business_date\",\"pending_count\",\"metric\",\"currency\",\"minor_units\"\r\n",
        "\"current-local-documents-page/v1\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:01Z\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"\r\n",
        "\"complete-current-section/v2\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:30Z\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"\r\n",
    ).joinToString("").encodeToByteArray()
    val f12: BoundCompleteCsvFixture = fixture(FrozenCompleteReportInputs.sourceMinorAmounts, 5, "2026-10-30T12:00:30Z",
        4154, "b099ea125d4a8aa8a50b1f406a6ce0d2ccc30bbbbba4383653a29bb195d630ac", exactF12Bytes)
    val empty: BoundCompleteCsvFixture = fixture(emptyList(), 5, "2026-10-30T12:00:30Z",
        477, "3b146ded57c5342e66279ad2d9089958581ef5dc023a9f20286f4f7d2f92a33a", exactEmptyBytes)
    val pages128: BoundCompleteCsvFixture = fixture(List(128) { 1L }, 1, "2026-10-30T12:05:00Z",
        43167, "a747594ff9dc65a804b1c87d467f029eba2e081f5b8e0a71816da222e93775be")
    val pages129: BoundCompleteCsvFixture = pages128.copy(
        pages = pages128.pages.dropLast(1) + pages128.pages.last().let { last ->
            last.copy(page = last.page.copy(nextAfter = FrozenCompleteReportInputs.key(128)))
        }, expectedByteCount = null, expectedSha256 = null, expectedCompletionRecord = null,
    )
    val bytesUnder: BoundCompleteCsvFixture = fixture(List(182) { 1000000000000L } + listOf(10L) + List(6) { 1L }, 5, "2026-10-30T12:05:00Z",
        65535, "55c30bdebb8e0d432efcaf08de6e3d732a57c682c0ae09d4fb4b94e178eddd18")
    val bytesEqual: BoundCompleteCsvFixture = fixture(List(182) { 1000000000000L } + listOf(100L) + List(6) { 1L }, 5, "2026-10-30T12:05:00Z",
        65536, "49df83e563c6343523ba56799da78417d29e66f1dd61ccebdb47c804c2add81a")
    val bytesOver: BoundCompleteCsvFixture = fixture(List(182) { 1000000000000L } + listOf(1000L) + List(6) { 1L }, 5, "2026-10-30T12:05:00Z",
        65537, "d93ec985464c9fa07fc159b24c3e341600584948e1b732d5a848a8edbfa2888d")

    private fun fixture(minors: List<Long>, limit: Int, confirmingTime: String, expectedBytesCount: Int,
        referenceSha256: String, referenceBytes: ByteArray? = null): BoundCompleteCsvFixture {
        val revision = Revision(7009)
        val rows = minors.mapIndexed { index, minor -> sourceRow(index + 1, minor) }
        val query = LocalBranchReportQueryV1(LocalBranchReportSectionV1.SAFE_TRANSFERS,
            after = null, limit = limit, delivery = LocalBranchReportDeliveryV1.EXPORT)
        val groups = if (rows.isEmpty()) listOf(emptyList()) else rows.chunked(limit)
        val pages = groups.mapIndexed { index, group ->
            val after = if (index == 0) null else groups[index - 1].last().id
            val next = if (index == groups.lastIndex) null else group.last().id
            BoundCsvPage(LocalBranchReportResultV1.Page(query.section, query.delivery, after, group, next),
                revision, UtcInstant.parseCanonical(dataTimes[index]))
        }
        val expectedMarker = listOf("complete-current-section/v2", "SAFE_TRANSFERS", "7009", confirmingTime) +
            List(12) { "" }
        return BoundCompleteCsvFixture(query, pages, revision, UtcInstant.parseCanonical(confirmingTime),
            expectedBytesCount, referenceSha256, rows.map { it.id.value }, expectedMarker, referenceBytes)
    }

    private fun sourceRow(ordinal: Int, minor: Long): LocalBranchReportRowV1 = LocalBranchReportRowV1(
        id = FrozenCompleteReportInputs.key(ordinal), revision = Revision(1),
        relatedDocumentId = DomainId("31000000-0000-4000-8000-" + ordinal.toString().padStart(12, '0')),
        cashSessionId = DomainId("32000000-0000-4000-8000-000000000001"),
        status = "AWAITING_RECEIPT", businessDate = null, pendingCount = 1,
        amounts = listOf(LocalBranchReportAmountV1(LocalBranchReportMetricV1.TRANSFER_AMOUNT,
            Money(Currency.EGP, minor))),
    )
}
