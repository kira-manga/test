package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Public destination category only; no private filesystem path or implicit cloud provider. */
@Serializable
public enum class LocalBranchReportExportDestinationV1 { DESKTOP_LOCAL_FILE, ANDROID_LOCAL_DOCUMENT, IOS_LOCAL_DOCUMENT }

/** Null retains the original PAGE selector; this extension never represents period totals. */
@Serializable
public enum class LocalBranchReportExportCoverageV1 { COMPLETE_CURRENT_SECTION_V2 }

/** REQUESTED/UNKNOWN never establish that no disclosure occurred. */
@Serializable
public enum class LocalBranchReportExportStageV1 {
    REQUESTED, CLIENT_ATTESTED_PUBLISHED, CANCELLED_BEFORE_PUBLICATION, PUBLICATION_OUTCOME_UNKNOWN,
}

@Serializable
public enum class LocalBranchReportExportOutcomeV1 {
    CLIENT_ATTESTED_PUBLISHED, CANCELLED_BEFORE_PUBLICATION, PUBLICATION_OUTCOME_UNKNOWN,
}

/** Client-reported metadata, NOT master-certified source freshness or proof of external file IO. */
@Serializable
public data class LocalBranchReportExportFileV1(
    public val sourceBranchRevision: Revision,
    public val sourceObservedAt: UtcInstant,
    public val contentDigest: PayloadDigest,
    public val byteCount: Int,
) { init { require(byteCount in 1..65_536) } }

@Serializable
public sealed interface LocalBranchReportExportRequestV1 {
    public val exportId: DomainId

    @Serializable @SerialName("BEGIN")
    @OptIn(ExperimentalSerializationApi::class)
    public data class Begin(
        override val exportId: DomainId,
        public val query: LocalBranchReportQueryV1,
        public val nativeReceiptId: DomainId,
        public val destination: LocalBranchReportExportDestinationV1,
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val coverage: LocalBranchReportExportCoverageV1? = null,
    ) : LocalBranchReportExportRequestV1 {
        init {
            require(query.delivery == LocalBranchReportDeliveryV1.EXPORT)
            require(coverage == null || query.after == null && query.section != LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS)
        }
    }

    @Serializable @SerialName("FINISH")
    public data class Finish(
        override val exportId: DomainId,
        public val expectedRevision: Revision,
        public val nativeReceiptId: DomainId,
        public val outcome: LocalBranchReportExportOutcomeV1,
        public val file: LocalBranchReportExportFileV1? = null,
    ) : LocalBranchReportExportRequestV1 {
        init {
            require(expectedRevision.value in 0L..2L)
            require(outcome != LocalBranchReportExportOutcomeV1.CLIENT_ATTESTED_PUBLISHED || file != null)
        }
    }
}

/** Metadata only. Audit revision, source cut, and command epoch/sequence are NOT interchangeable. */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
public data class LocalBranchReportExportViewV1(
    public val exportId: DomainId,
    public val ownerId: DomainId,
    public val query: LocalBranchReportQueryV1,
    public val nativeReceiptId: DomainId,
    public val destination: LocalBranchReportExportDestinationV1,
    public val revision: Revision,
    public val stage: LocalBranchReportExportStageV1,
    public val originalOperationId: OperationId,
    public val lastOperationId: OperationId,
    public val requestedAt: UtcInstant,
    public val lastReportedAt: UtcInstant?,
    public val file: LocalBranchReportExportFileV1?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val coverage: LocalBranchReportExportCoverageV1? = null,
) {
    init { require(coverage == null || query.after == null && query.section != LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS) }
}

@Serializable
public enum class LocalBranchReportExportRejectionV1 {
    EXPORT_ID_EXISTS, NOT_FOUND, OWNER_REQUIRED, REVISION_CONFLICT, NATIVE_RECEIPT_MISMATCH,
    TERMINAL_OR_UNSUPPORTED_TRANSITION, FILE_IDENTITY_CONFLICT,
}

@Serializable
public sealed interface LocalBranchReportExportResultV1 {
    @Serializable @SerialName("RECORDED")
    public data class Recorded(public val view: LocalBranchReportExportViewV1) : LocalBranchReportExportResultV1
    @Serializable @SerialName("REJECTED")
    public data class Rejected(public val reason: LocalBranchReportExportRejectionV1) : LocalBranchReportExportResultV1
    @Serializable @SerialName("NOT_FOUND")
    public data object NotFound : LocalBranchReportExportResultV1
}

@Serializable
internal data class LocalBranchReportExportQueryV1(val exportId: DomainId)

/** Preserve original operation ID/canonical bytes in the real native journal before submission. */
public object LocalBranchReportExportCodecV1 {
    public val COMMAND: CommandKey = CommandKey("sales.branch-report-export", 1)
    public val QUERY: ReadKey = ReadKey("sales.branch-report-export.read", 1)

    public fun command(operationId: OperationId, request: LocalBranchReportExportRequestV1): MutationRequest =
        MutationRequest(operationId, COMMAND, encode(request))
    public fun encode(request: LocalBranchReportExportRequestV1): StoredBytes =
        localSalesEncode(request, LocalBranchReportExportRequestV1.serializer(), 4_096)
    public fun decodeRequest(body: StoredBytes): LocalBranchReportExportRequestV1 =
        localSalesDecode(body, LocalBranchReportExportRequestV1.serializer(), 4_096)
    public fun read(exportId: DomainId): ReadRequest = ReadRequest(QUERY,
        localSalesEncode(LocalBranchReportExportQueryV1(exportId), LocalBranchReportExportQueryV1.serializer(), 1_024))
    public fun decodeResult(body: StoredBytes): LocalBranchReportExportResultV1 =
        localSalesDecode(body, LocalBranchReportExportResultV1.serializer(), 16_384)
    internal fun result(value: LocalBranchReportExportResultV1): StoredBytes =
        localSalesEncode(value, LocalBranchReportExportResultV1.serializer(), 16_384)
}
