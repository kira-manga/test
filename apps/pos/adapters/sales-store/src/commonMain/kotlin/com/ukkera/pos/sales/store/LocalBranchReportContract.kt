package com.ukkera.pos.sales.store

import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Current local document projections, NOT period revenue, fiscal accounts or company totals. */
@Serializable
public enum class LocalBranchReportSectionV1 {
    CASH_SESSIONS, COLLECTIONS, PAID_SALES, ACCEPTED_RETURNS, SAFE_TRANSFERS,
    /** Separate durable FOOD kinds: their IDs may equal a retail document ID. */
    FOOD_COLLECTIONS, FOOD_PAID_SALES, FOOD_REFUND_OBLIGATIONS,
    /** Linked documentary classifications, never newly collected money. */
    PAYMENT_CORRECTIONS,
    /** Selected complete normalized retail paid-sale classification; not a period or till census. */
    RETAIL_TENDER_BREAKDOWNS,
}

/** An EXPORT request requires both viewing and explicit export authority at the master. */
@Serializable
public enum class LocalBranchReportDeliveryV1 { VIEW, EXPORT }

/** Absent profile retains the original immutable projection/CSV contract. */
@Serializable
public enum class LocalBranchReportProjectionV1 { FOOD_REFUND_PAYOUT_V2, PAYMENT_CORRECTION_V2, RETAIL_TENDER_BREAKDOWN_V3 }

internal fun validBranchReportProfile(section: LocalBranchReportSectionV1,
    profile: LocalBranchReportProjectionV1?): Boolean = when (profile) {
    null -> section !in setOf(LocalBranchReportSectionV1.PAYMENT_CORRECTIONS, LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS)
    LocalBranchReportProjectionV1.FOOD_REFUND_PAYOUT_V2 -> section == LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS
    LocalBranchReportProjectionV1.PAYMENT_CORRECTION_V2 -> section == LocalBranchReportSectionV1.PAYMENT_CORRECTIONS
    LocalBranchReportProjectionV1.RETAIL_TENDER_BREAKDOWN_V3 -> section == LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
public data class LocalBranchReportQueryV1(
    public val section: LocalBranchReportSectionV1,
    public val after: DomainId? = null,
    public val limit: Int = 5,
    public val delivery: LocalBranchReportDeliveryV1 = LocalBranchReportDeliveryV1.VIEW,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val profile: LocalBranchReportProjectionV1? = null,
    /** Exact source selection, never a cursor or a branch/period membership claim. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val selectedSaleId: DomainId? = null,
) {
    init {
        require(limit in 1..5)
        require(validBranchReportProfile(section, profile))
        require(if (section == LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS)
            selectedSaleId != null && after == null && limit == 1 else selectedSaleId == null)
    }
}

/** Closed categories prevent a collection, float or safe transfer becoming reported sales. */
@Serializable
public enum class LocalBranchReportMetricV1 {
    OPENING_FLOAT, CASH_RECEIVED, CHANGE_GIVEN, CASH_REFUNDS, PAY_INS, PAY_OUTS, SAFE_DROPS,
    EXPECTED_CASH, RESERVED_CASH_OUT, EXTERNAL_COLLECTIONS, EXTERNAL_REFUNDS,
    CLOSING_EXPECTED, ACTUAL_COUNT, VARIANCE, RETAINED_FLOAT, ACTUAL_SAFE_DELIVERY,
    LOCKED_PAYABLE, ACTUAL_COLLECTIONS, REMAINING_COLLECTION, CASH_COLLECTIONS,
    PAID_DOCUMENT_TOTAL_BEFORE_DISCOUNTS, ADVERTISED_DISCOUNT, PAID_DOCUMENT_TOTAL,
    ITEM_NET, ITEM_TAX, CHARGE_NET, CHARGE_TAX,
    ACCEPTED_RETURN_AMOUNT, ACTUAL_REFUNDS, REFUND_DUE, TRANSFER_AMOUNT,
    CANCELLED_COLLECTION_REFUND_LIABILITY,
    PREVIOUS_CLASSIFICATION_DELTA, CORRECTED_CLASSIFICATION_DELTA,
    CASH_CLASSIFICATION_DELTA, PHYSICAL_CASH_DELTA,
}

@Serializable
public data class LocalBranchReportAmountV1(public val metric: LocalBranchReportMetricV1, public val amount: Money)

/** Safe linked summary only: no raw terminal/reference, attachment, customer or private reason. */
@Serializable
public data class LocalBranchReportPaymentCorrectionV2(
    public val paymentAttemptId: DomainId,
    public val originalCloseOperationId: OperationId,
    public val originalCollectorId: DomainId,
    public val correctionActorId: DomainId,
    public val previousClassification: LocalTenderViewV1,
    public val correctedClassification: LocalTenderViewV1,
    public val recordedAt: UtcInstant,
)

/** Safe full membership of one original payment; correction metadata identifies the latest
 * validated head, not a newly applied collection or raw private correction evidence. */
@Serializable
public data class LocalBranchReportRetailTenderPaymentV3(
    public val paymentAttemptId: DomainId,
    public val amount: Money,
    public val originalClassification: LocalTenderViewV1,
    public val reconciledClassification: LocalTenderViewV1,
    public val latestCorrectionId: DomainId?,
    public val correctionCount: Long,
    public val latestCorrectionRecordedAt: UtcInstant?,
)

/** All immutable payments of the selected paid sale, qualified at the outer read checkpoint.
 * Not all collections/refunds in its cash session; no posting period is inferred. */
@Serializable
public data class LocalBranchReportRetailTenderBreakdownV3(
    public val originalCloseOperationId: OperationId,
    public val originalCollectorId: DomainId,
    public val payments: List<LocalBranchReportRetailTenderPaymentV3>,
)

/** No payer, payment reference, credential, private reason, cost or margin is exported here. */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
public data class LocalBranchReportRowV1(
    public val id: DomainId,
    public val revision: Revision,
    public val relatedDocumentId: DomainId? = null,
    public val cashSessionId: DomainId? = null,
    public val status: String,
    /** Original cash-session date ONLY. A missing sale posting date is never invented. */
    public val businessDate: BusinessDate? = null,
    public val pendingCount: Int = 0,
    public val amounts: List<LocalBranchReportAmountV1>,
    /** FOOD storage generation is distinct from the domain revision above. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val storageRevision: Revision? = null,
    /** Genuine FOOD paid-document finalization only; never the clock or a cash-session date. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val finalizedAt: UtcInstant? = null,
    /** Optional original paid document of a FOOD cancellation; not the current refund's date. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val originalPaidDocumentId: DomainId? = null,
    /** Present only under the explicit payment-correction projection. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val paymentCorrection: LocalBranchReportPaymentCorrectionV2? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val retailTenderBreakdown: LocalBranchReportRetailTenderBreakdownV3? = null,
) {
    init {
        require(status.length in 1..48 && status.all { it in 'A'..'Z' || it == '_' })
        require(pendingCount >= 0 && amounts.size <= 24 && amounts.map { it.metric }.distinct().size == amounts.size)
    }
}

@Serializable
public sealed interface LocalBranchReportResultV1 {
    /** The envelope ReadReply.branchRevision/observedAt qualifies this page, not an earlier page. */
    @Serializable @SerialName("PAGE")
    @OptIn(ExperimentalSerializationApi::class)
    public data class Page(
        public val section: LocalBranchReportSectionV1,
        public val delivery: LocalBranchReportDeliveryV1,
        public val after: DomainId?,
        public val rows: List<LocalBranchReportRowV1>,
        public val nextAfter: DomainId?,
        /** Echo the exact authorized query profile, including on an empty page. */
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val profile: LocalBranchReportProjectionV1? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val selectedSaleId: DomainId? = null,
    ) : LocalBranchReportResultV1 {
        init {
            require(validBranchReportProfile(section, profile))
            require(if (section == LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS)
                selectedSaleId != null && after == null && nextAfter == null && rows.size == 1 && rows.single().id == selectedSaleId
                else selectedSaleId == null)
        }
    }
}

public object LocalBranchReportReadCodec {
    public val QUERY: ReadKey = ReadKey("sales.branch-report.read", 1)
    public fun request(query: LocalBranchReportQueryV1): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun encode(query: LocalBranchReportQueryV1): StoredBytes =
        localSalesEncode(query, LocalBranchReportQueryV1.serializer(), 2_048)
    public fun decode(body: StoredBytes): LocalBranchReportResultV1 =
        localSalesDecode(body, LocalBranchReportResultV1.serializer())

    /** Scalar/shape checks only, shared by presentation and CSV. Never grants source authority. */
    public fun validPaymentCorrectionRow(row: LocalBranchReportRowV1, observedAt: UtcInstant): Boolean =
        validPaymentCorrectionReportRow(row, observedAt)

    /** Scalar/shape validation only; the genuine Master separately qualifies every source/head. */
    public fun validRetailTenderBreakdownRow(row: LocalBranchReportRowV1, observedAt: UtcInstant): Boolean =
        validRetailTenderBreakdownReportRow(row, observedAt)
}
