package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.database.requireStockRelational
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.PaidSale
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

/** An exact immutable materialization, not merely an ID, signature or current activation pointer. */
@Serializable
public data class LocalReturnCaseSourceReferenceV1(
    public val kind: String, public val id: DomainId, public val schema: String,
    public val storageRevision: Revision, public val digest: PayloadDigest,
    public val producerOperationId: OperationId, public val producerActorId: DomainId,
    public val producerCommand: String, public val producerVersion: Int,
) {
    init {
        AggregateKey(kind, id); CommandKey(producerCommand, producerVersion)
        require(schema.isNotBlank() && schema.length <= 128 && storageRevision.value == 1L)
    }
}

@Serializable
public data class LocalSalesReturnPortionPromiseV1(
    public val portionId: DomainId, public val skuId: DomainId, public val categoryCode: String,
    public val policyId: DomainId, public val policyRevision: Revision,
    public val promiseSource: LocalReturnCaseSourceReferenceV1,
)

/**
 * Proposed capture lifecycle ONLY. The quote owner must insert this alongside the protected
 * quote and propagate its distinct reference through collection/sale/entitlement under a new
 * explicit profile. This cut does not alter those producers or backfill legacy receipts.
 */
@Serializable
public data class LocalSalesReturnPolicyCaptureV1(
    public val organizationId: DomainId, public val companyId: DomainId, public val branchId: DomainId,
    public val id: DomainId, public val orderId: DomainId, public val quoteRevision: Revision,
    public val quoteDigest: PayloadDigest, public val originalQuoteOperationId: OperationId,
    public val portions: List<LocalSalesReturnPortionPromiseV1>,
    public val profile: String = "LOCAL_RETAIL_ORIGINAL_PROMISE_V1",
)

public object LocalSalesReturnPolicyCaptureCodecV1 {
    public const val KIND: String = "sales.return.sale-policy"
    public const val SCHEMA: String = "sales.return.sale-policy/v1"
    public fun encode(value: LocalSalesReturnPolicyCaptureV1): StoredBytes {
        require(value.profile == "LOCAL_RETAIL_ORIGINAL_PROMISE_V1" && value.id.value == value.originalQuoteOperationId.value &&
            value.portions.size in 1..256 && value.portions.map { it.portionId }.distinct().size == value.portions.size &&
            value.portions == value.portions.sortedBy { it.portionId.value }) { "sales.return.policy_capture" }
        value.portions.forEach { localReason(it.categoryCode) }
        return localSalesEncode(value, LocalSalesReturnPolicyCaptureV1.serializer())
    }
    public fun decode(body: StoredBytes): LocalSalesReturnPolicyCaptureV1 =
        localSalesDecode(body, LocalSalesReturnPolicyCaptureV1.serializer()).also { require(encode(it) == body) }
}

/** Assessment of the requested ordinary remedy, NOT closure of other applicable rights. */
@Serializable
public enum class LocalReturnOrdinaryAssessmentV1 { ELIGIBLE, PENDING, INELIGIBLE }

@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class LocalReturnCaseAssessmentV1(
    public val outcome: LocalReturnOrdinaryAssessmentV1, public val reasonCode: String,
    public val route: String,
    public val policyId: DomainId? = null, public val policyRevision: Revision? = null,
    public val salePolicyCapture: LocalReturnCaseSourceReferenceV1? = null,
    /** Immutable currently-applicable rights/activation materializations selected by the trusted source. */
    public val applicableRights: List<LocalReturnCaseSourceReferenceV1> = emptyList(),
    public val customerReceipts: List<LocalReturnCaseSourceReferenceV1> = emptyList(),
    public val customerReceivedAt: UtcInstant? = null, public val receiptTimeZoneId: String? = null,
    public val remedyDueAt: UtcInstant? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val originalCommercialTiming: LocalReturnCommercialTimingV1? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val ordinaryTiming: LocalReturnOrdinaryTimingV1? = null,
) {
    init {
        returnCaseCode(reasonCode); returnCaseCode(route)
        require(applicableRights.size <= 8 && customerReceipts.size <= 8)
        require((policyId == null) == (policyRevision == null))
        receiptTimeZoneId?.let(::returnCaseTimeZone)
        require((customerReceivedAt == null) == (receiptTimeZoneId == null))
        require(originalCommercialTiming == null || outcome == LocalReturnOrdinaryAssessmentV1.PENDING)
        require(ordinaryTiming == null || outcome != LocalReturnOrdinaryAssessmentV1.INELIGIBLE)
        require(ordinaryTiming == null || outcome != LocalReturnOrdinaryAssessmentV1.ELIGIBLE ||
            ordinaryTiming.status == LocalReturnCommercialWindowStatusV1.WITHIN)
        if (outcome != LocalReturnOrdinaryAssessmentV1.PENDING) require(policyId != null && salePolicyCapture != null &&
            applicableRights.isNotEmpty() && customerReceipts.isNotEmpty() && customerReceivedAt != null)
    }
}

/** All identities/values here are loaded by sales, never qualification claims accepted from the UI. */
public data class SalesReturnCaseQualificationRequestV1(
    public val scope: BranchScope, public val caseId: DomainId,
    public val intakeDigest: PayloadDigest, public val bindingDigest: PayloadDigest,
    public val facts: LocalReturnCaseFactsV1, public val binding: LocalReturnCaseReceiptBindingV1,
    public val originalSale: PaidSale,
    public val intakeRecordedAt: UtcInstant, public val claimantOperatorId: DomainId,
    public val intakeOperationId: OperationId,
    public val recordOriginalCommercialTiming: Boolean = false,
    public val ordinaryInspection: LocalReturnOrdinaryInspectionV1? = null,
)

public fun interface TransactionSalesReturnCaseSourceV1 {
    /**
     * Borrowed transaction only. Verify original quote→sale promise capture, original retail
     * portion, actual persisted customer receipt/quantity/timezone, and the protected currently
     * applicable rights basis. Never shorten the promised/legal baseline. Missing capture or
     * evidence returns null/pending; claimed/asserted dates are not original delivery evidence.
     * The source must not retain view, do IO, write records, or manufacture authority.
     */
    public suspend fun prepare(view: CommandReadView, request: SalesReturnCaseQualificationRequestV1): PreparedSalesReturnCaseSourceV1?
}

public interface PreparedSalesReturnCaseSourceV1 {
    public val scope: BranchScope
    /** Includes current rights activation, original policy, receipt, fulfillment and authority resources. */
    public val recoveryResources: Set<RecoveryResourceKey>
    /** Pure assessment of the exact prepared request after current action authorization. */
    public fun assess(recordedAt: UtcInstant): LocalReturnCaseAssessmentV1
    /**
     * Exact references known during prepare. The returned assessment may only use these
     * references; sales validates their immutable rows and original retained producers before
     * evaluation. Therefore an assessment cannot introduce an unobserved source after auth.
     */
    public val sourceReferences: List<LocalReturnCaseSourceReferenceV1>
}

public object UnavailableSalesReturnCaseSourceV1 : TransactionSalesReturnCaseSourceV1 {
    override public suspend fun prepare(view: CommandReadView, request: SalesReturnCaseQualificationRequestV1): PreparedSalesReturnCaseSourceV1? = null
}

internal fun LocalReturnCaseAssessmentV1.sources(): List<LocalReturnCaseSourceReferenceV1> =
    (listOfNotNull(salePolicyCapture) + applicableRights + customerReceipts).distinct()

internal fun returnCaseTimeZone(value: String) {
    require(value.length in 1..64 && value.all { it.isLetterOrDigit() || it in "/_+-:" }) { "sales.return.timezone" }
}

internal fun returnCaseCode(value: String) {
    require(value.length in 1..128 && value.all { it.isLetterOrDigit() || it in "._-" }) { "sales.return.case_code" }
}

internal suspend fun requireReturnCaseSources(view: CommandReadView, references: List<LocalReturnCaseSourceReferenceV1>) {
    requireLocalSalesSource(references.size <= 17 && references.distinct().size == references.size)
    for (reference in references) {
        val key = AggregateKey(reference.kind, reference.id)
        val row = LocalSalesStorage.required(view, key, reference.schema)
        requireLocalSalesSource(row.revision == reference.storageRevision && row.digest == reference.digest &&
            sha256(row.payload.copyBytes()) == reference.digest)
        if (reference.kind == LocalSalesReturnPolicyCaptureCodecV2.KIND && reference.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA) {
            val capture = validateLocalSalesSource { LocalSalesReturnPolicyCaptureCodecV2.decode(row.payload) }
            val producer = (view.requireStockRelational().originals.read(reference.producerOperationId) as? com.ukkera.pos.database.RetainedOriginalV2.Streamed)?.evidence
                ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
            requireLocalSalesSource(capture.scope == view.scope && capture.id == reference.id &&
                capture.originalQuoteOperationId == reference.producerOperationId && producer.identity.scope == view.scope &&
                producer.identity.producer.operationId == reference.producerOperationId &&
                producer.identity.producer.originalActorId == reference.producerActorId &&
                producer.identity.producer.command == CommandKey(reference.producerCommand, reference.producerVersion) &&
                producer.identity.producer.command == com.ukkera.pos.database.StockStreamingCommandsV2.COLLECTION)
            commerceRepository(view).requireRelationalProducer(view, producer)
            requireCommerceWrite(producer, row)
            continue
        }
        val producer = view.retainedCommand(reference.producerOperationId)
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        requireLocalSalesSource(producer.committed && producer.scope == view.scope &&
            producer.operationId == reference.producerOperationId && producer.originalActorId == reference.producerActorId &&
            producer.command == CommandKey(reference.producerCommand, reference.producerVersion) &&
            semanticDigest(producer.scope, producer.originalActorId, producer.command, producer.normalized) == producer.semanticDigest)
        val write = producer.aggregateWrites.singleOrNull { it.key == key }
        requireLocalSalesSource(write != null && write.storageGeneration == row.revision && write.schema == row.schema &&
            write.payload == row.payload && write.payloadDigest == reference.digest)
    }
}

internal fun returnCaseSourceResources(references: List<LocalReturnCaseSourceReferenceV1>): Set<RecoveryResourceKey> = buildSet {
    references.forEach {
        add(entitlementResource(it.id))
        add(entitlementResource(DomainId(it.producerOperationId.value)))
        add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.producerActorId))
    }
}
