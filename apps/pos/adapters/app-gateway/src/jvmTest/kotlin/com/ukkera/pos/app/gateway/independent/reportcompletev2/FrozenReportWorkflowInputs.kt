package com.ukkera.pos.app.gateway.independent.reportcompletev2

import com.ukkera.pos.app.LocalBranchReportExportCoverage
import com.ukkera.pos.app.LocalBranchReportExportSelection
import com.ukkera.pos.app.LocalBranchReportSection
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

internal data class FrozenWorkflowRead(
    val stage: String,
    val query: LocalBranchReportQueryV1,
    val page: LocalBranchReportResultV1.Page,
    val revision: Revision,
    val observedAt: UtcInstant,
)

/** Frozen RE32-DESIGN-v1 / RE32-SUPPLEMENT-v2.0 inputs, not observed production outputs. */
internal object FrozenReportWorkflowInputs {
    val actorId = DomainId("20000000-0000-4000-8000-000000000001")
    val companyId = DomainId("10000000-0000-4000-8000-000000000001")
    val branchId = DomainId("11000000-0000-4000-8000-000000000001")
    val authorityEpoch = Revision(4)
    val sourceRevision = Revision(7009)
    val changedRevision = Revision(7010)
    val baseObservation = UtcInstant.parseCanonical("2026-10-30T12:00:00Z")
    val c1Time = UtcInstant.parseCanonical("2026-10-30T12:00:30Z")
    val c2Time = UtcInstant.parseCanonical("2026-10-30T12:00:50Z")
    val header = listOf("profile", "section", "branch_revision", "observed_at_utc", "page_after", "next_after", "document_id", "document_revision", "related_document_id", "cash_session_id", "status", "cash_session_business_date", "pending_count", "metric", "currency", "minor_units")
    private val sourceColumns = listOf(
        listOf("current-local-documents-page/v1", "SAFE_TRANSFERS", "7009", "2026-10-30T12:00:01Z", "", "30000000-0000-4000-8000-000000000005", "30000000-0000-4000-8000-000000000001", "1", "31000000-0000-4000-8000-000000000001", "32000000-0000-4000-8000-000000000001", "AWAITING_RECEIPT", "", "1", "TRANSFER_AMOUNT", "EGP", "100"),
        listOf("current-local-documents-page/v1", "SAFE_TRANSFERS", "7009", "2026-10-30T12:00:01Z", "", "30000000-0000-4000-8000-000000000005", "30000000-0000-4000-8000-000000000002", "1", "31000000-0000-4000-8000-000000000002", "32000000-0000-4000-8000-000000000001", "AWAITING_RECEIPT", "", "1", "TRANSFER_AMOUNT", "EGP", "200"),
        listOf("current-local-documents-page/v1", "SAFE_TRANSFERS", "7009", "2026-10-30T12:00:01Z", "", "30000000-0000-4000-8000-000000000005", "30000000-0000-4000-8000-000000000003", "1", "31000000-0000-4000-8000-000000000003", "32000000-0000-4000-8000-000000000001", "AWAITING_RECEIPT", "", "1", "TRANSFER_AMOUNT", "EGP", "300"),
        listOf("current-local-documents-page/v1", "SAFE_TRANSFERS", "7009", "2026-10-30T12:00:01Z", "", "30000000-0000-4000-8000-000000000005", "30000000-0000-4000-8000-000000000004", "1", "31000000-0000-4000-8000-000000000004", "32000000-0000-4000-8000-000000000001", "AWAITING_RECEIPT", "", "1", "TRANSFER_AMOUNT", "EGP", "400"),
        listOf("current-local-documents-page/v1", "SAFE_TRANSFERS", "7009", "2026-10-30T12:00:01Z", "", "30000000-0000-4000-8000-000000000005", "30000000-0000-4000-8000-000000000005", "1", "31000000-0000-4000-8000-000000000005", "32000000-0000-4000-8000-000000000001", "AWAITING_RECEIPT", "", "1", "TRANSFER_AMOUNT", "EGP", "500"),
        listOf("current-local-documents-page/v1", "SAFE_TRANSFERS", "7009", "2026-10-30T12:00:02Z", "30000000-0000-4000-8000-000000000005", "30000000-0000-4000-8000-000000000010", "30000000-0000-4000-8000-000000000006", "1", "31000000-0000-4000-8000-000000000006", "32000000-0000-4000-8000-000000000001", "AWAITING_RECEIPT", "", "1", "TRANSFER_AMOUNT", "EGP", "600"),
        listOf("current-local-documents-page/v1", "SAFE_TRANSFERS", "7009", "2026-10-30T12:00:02Z", "30000000-0000-4000-8000-000000000005", "30000000-0000-4000-8000-000000000010", "30000000-0000-4000-8000-000000000007", "1", "31000000-0000-4000-8000-000000000007", "32000000-0000-4000-8000-000000000001", "AWAITING_RECEIPT", "", "1", "TRANSFER_AMOUNT", "EGP", "700"),
        listOf("current-local-documents-page/v1", "SAFE_TRANSFERS", "7009", "2026-10-30T12:00:02Z", "30000000-0000-4000-8000-000000000005", "30000000-0000-4000-8000-000000000010", "30000000-0000-4000-8000-000000000008", "1", "31000000-0000-4000-8000-000000000008", "32000000-0000-4000-8000-000000000001", "AWAITING_RECEIPT", "", "1", "TRANSFER_AMOUNT", "EGP", "800"),
        listOf("current-local-documents-page/v1", "SAFE_TRANSFERS", "7009", "2026-10-30T12:00:02Z", "30000000-0000-4000-8000-000000000005", "30000000-0000-4000-8000-000000000010", "30000000-0000-4000-8000-000000000009", "1", "31000000-0000-4000-8000-000000000009", "32000000-0000-4000-8000-000000000001", "AWAITING_RECEIPT", "", "1", "TRANSFER_AMOUNT", "EGP", "900"),
        listOf("current-local-documents-page/v1", "SAFE_TRANSFERS", "7009", "2026-10-30T12:00:02Z", "30000000-0000-4000-8000-000000000005", "30000000-0000-4000-8000-000000000010", "30000000-0000-4000-8000-000000000010", "1", "31000000-0000-4000-8000-000000000010", "32000000-0000-4000-8000-000000000001", "AWAITING_RECEIPT", "", "1", "TRANSFER_AMOUNT", "EGP", "1000"),
        listOf("current-local-documents-page/v1", "SAFE_TRANSFERS", "7009", "2026-10-30T12:00:03Z", "30000000-0000-4000-8000-000000000010", "", "30000000-0000-4000-8000-000000000011", "1", "31000000-0000-4000-8000-000000000011", "32000000-0000-4000-8000-000000000001", "AWAITING_RECEIPT", "", "1", "TRANSFER_AMOUNT", "EGP", "1100"),
        listOf("current-local-documents-page/v1", "SAFE_TRANSFERS", "7009", "2026-10-30T12:00:03Z", "30000000-0000-4000-8000-000000000010", "", "30000000-0000-4000-8000-000000000012", "1", "31000000-0000-4000-8000-000000000012", "32000000-0000-4000-8000-000000000001", "AWAITING_RECEIPT", "", "1", "TRANSFER_AMOUNT", "EGP", "1200"),
    )
    private val sourceRows = sourceColumns.map { fields ->
        LocalBranchReportRowV1(
            id = DomainId(fields[6]), revision = Revision(fields[7].toLong()),
            relatedDocumentId = DomainId(fields[8]), cashSessionId = DomainId(fields[9]),
            status = fields[10], businessDate = null, pendingCount = fields[12].toInt(),
            amounts = listOf(LocalBranchReportAmountV1(LocalBranchReportMetricV1.valueOf(fields[13]),
                Money(Currency.valueOf(fields[14]), fields[15].toLong()))),
        )
    }

    fun key(ordinal: Int): DomainId = DomainId("30000000-0000-4000-8000-${ordinal.toString().padStart(12, '0')}")
    fun row(ordinal: Int): LocalBranchReportRowV1 = sourceRows[ordinal - 1]
    fun query(after: DomainId? = null): LocalBranchReportQueryV1 = LocalBranchReportQueryV1(
        LocalBranchReportSectionV1.SAFE_TRANSFERS, after, 5, LocalBranchReportDeliveryV1.EXPORT)

    val completeSelection = LocalBranchReportExportSelection(LocalBranchReportSection.SAFE_TRANSFERS,
        null, LocalBranchReportExportCoverage.COMPLETE_CURRENT_SECTION_V2)
    // The coverage argument is deliberately omitted: this is the legacy PAGE selector.
    val pageSelection = LocalBranchReportExportSelection(LocalBranchReportSection.SAFE_TRANSFERS, key(6))

    fun f12Reads(): List<FrozenWorkflowRead> {
        val data = listOf(
            FrozenWorkflowRead("D1", query(null), LocalBranchReportResultV1.Page(
                LocalBranchReportSectionV1.SAFE_TRANSFERS, LocalBranchReportDeliveryV1.EXPORT, null,
                listOf(row(1), row(2), row(3), row(4), row(5)), DomainId("30000000-0000-4000-8000-000000000005")), sourceRevision, UtcInstant.parseCanonical("2026-10-30T12:00:01Z")),
            FrozenWorkflowRead("D2", query(DomainId("30000000-0000-4000-8000-000000000005")), LocalBranchReportResultV1.Page(
                LocalBranchReportSectionV1.SAFE_TRANSFERS, LocalBranchReportDeliveryV1.EXPORT, DomainId("30000000-0000-4000-8000-000000000005"),
                listOf(row(6), row(7), row(8), row(9), row(10)), DomainId("30000000-0000-4000-8000-000000000010")), sourceRevision, UtcInstant.parseCanonical("2026-10-30T12:00:02Z")),
            FrozenWorkflowRead("D3", query(DomainId("30000000-0000-4000-8000-000000000010")), LocalBranchReportResultV1.Page(
                LocalBranchReportSectionV1.SAFE_TRANSFERS, LocalBranchReportDeliveryV1.EXPORT, DomainId("30000000-0000-4000-8000-000000000010"),
                listOf(row(11), row(12)), null), sourceRevision, UtcInstant.parseCanonical("2026-10-30T12:00:03Z")),
        )
        return data + listOf(data.first().copy(stage = "C1", observedAt = c1Time),
            data.first().copy(stage = "C2", observedAt = c2Time))
    }

    fun f0Reads(): List<FrozenWorkflowRead> {
        val empty = FrozenWorkflowRead("D1", query(), LocalBranchReportResultV1.Page(
            LocalBranchReportSectionV1.SAFE_TRANSFERS, LocalBranchReportDeliveryV1.EXPORT,
            null, emptyList(), null), sourceRevision, UtcInstant.parseCanonical("2026-10-30T12:00:01Z"))
        return listOf(empty, empty.copy(stage = "C1", observedAt = c1Time),
            empty.copy(stage = "C2", observedAt = c2Time))
    }

    /** PAGE semantic vector only; these are not observed pre-existing legacy byte goldens. */
    fun pageReads(): List<FrozenWorkflowRead> {
        val selected = FrozenWorkflowRead("PAGE", query(key(6)), LocalBranchReportResultV1.Page(
            LocalBranchReportSectionV1.SAFE_TRANSFERS, LocalBranchReportDeliveryV1.EXPORT,
            key(6), (7..11).map(::row), key(11)), sourceRevision, baseObservation)
        return listOf(selected, selected.copy(stage = "PAGE_RECHECK", observedAt = c2Time))
    }

    private val f12Bytes = listOf(
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
    fun f12BytesCopy(): ByteArray = f12Bytes.copyOf()
    const val F12_BYTE_COUNT: Int = 4154
    const val F12_REFERENCE_SHA256: String = "b099ea125d4a8aa8a50b1f406a6ce0d2ccc30bbbbba4383653a29bb195d630ac"
    private val f0Bytes = listOf(
        "\"profile\",\"section\",\"branch_revision\",\"observed_at_utc\",\"page_after\",\"next_after\",\"document_id\",\"document_revision\",\"related_document_id\",\"cash_session_id\",\"status\",\"cash_session_business_date\",\"pending_count\",\"metric\",\"currency\",\"minor_units\"\r\n",
        "\"current-local-documents-page/v1\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:01Z\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"\r\n",
        "\"complete-current-section/v2\",\"SAFE_TRANSFERS\",\"7009\",\"2026-10-30T12:00:30Z\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"\r\n",
    ).joinToString("").encodeToByteArray()
    fun f0BytesCopy(): ByteArray = f0Bytes.copyOf()
    const val F0_BYTE_COUNT: Int = 477
    const val F0_REFERENCE_SHA256: String = "3b146ded57c5342e66279ad2d9089958581ef5dc023a9f20286f4f7d2f92a33a"
}
