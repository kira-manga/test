@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A distinct supplier payout original. The generic cash-move guard remains unchanged. */
public object LocalSupplierCashProfile {
    public val COMMAND: CommandKey = CommandKey("purchasing.supplier-cash", 1)
    public val READ: ReadKey = ReadKey("purchasing.supplier-cash.read", 1)
    public const val DOMAIN: String = "purchasing.supplier-cash/v1"
    public const val SLOT: String = "purchasing.supplier-cash-slot"
    public const val ORIGINAL: String = "purchasing.supplier-cash-original"
    public const val RESOLUTION: String = "purchasing.supplier-cash-resolution"
    public const val APPLICATION: String = "purchasing.supplier-cash-application"
    public const val OPERATION_CLAIM: String = "purchasing.supplier-cash-operation.v1"
    public const val ALLOCATION_CLAIM: String = "purchasing.supplier-cash-allocation.v1"
    public const val ATTEMPT_CLAIM: String = "purchasing.supplier-cash-attempt.v1"
    public const val MAX_REQUEST_BYTES: Int = 8_192
    public const val MAX_RESULT_BYTES: Int = 16_384
    public const val MAX_DOMAIN_BYTES: Int = 6_144
    public const val MAX_SLOT_BYTES: Int = 16_384
    public const val MAX_RAW_BYTES: Int = 24_576
    public const val MAX_FINANCIAL_RESOURCES: Int = 64
    public val gatedKinds: Set<String> = setOf(PhysicalRecordingSourceProfileV1.KIND, SLOT, ORIGINAL, RESOLUTION)
    public val gatedClaims: Set<String> = setOf(OPERATION_CLAIM, ALLOCATION_CLAIM, ATTEMPT_CLAIM)
    public fun schema(kind: String): String = "$kind/v1"
}

/** The local designated posting branch is the admitted scope, never supplied in the body. */
@Serializable
public sealed interface LocalSupplierCashSource {
    public val supplierId: DomainId
    @Serializable @SerialName("INVOICE")
    public data class Invoice(
        override val supplierId: DomainId, public val invoiceId: DomainId,
        public val expectedSupplierRevision: Revision,
        public val expectedInvoiceRevision: Revision, public val expectedProgressRevision: Revision,
        public val expectedPayableRevision: Revision, public val expectedCreditRevision: Revision?,
        public val expectedSettlementRevision: Revision?, public val expectedRemainingDue: Money,
        public val expectedReservedCash: Money,
    ) : LocalSupplierCashSource {
        init { require(expectedRemainingDue.currency == Currency.EGP && expectedRemainingDue.minor >= 0L &&
            expectedReservedCash.currency == Currency.EGP && expectedReservedCash.minor in 0L..expectedRemainingDue.minor) }
    }
    @Serializable @SerialName("ADVANCE")
    public data class Advance(override val supplierId: DomainId,
        public val expectedSupplierRevision: Revision) : LocalSupplierCashSource
}

@Serializable
public data class LocalSupplierCashTarget(
    public val supplierId: DomainId, public val settlementId: DomainId, public val invoiceId: DomainId?,
    public val allocationId: DomainId?, public val sessionId: DomainId, public val attemptId: DomainId,
    public val intentOperationId: OperationId, public val recordingOperationId: OperationId,
    public val resolutionOperationId: OperationId,
) {
    init { require(setOf(intentOperationId, recordingOperationId, resolutionOperationId).size == 3)
        require((invoiceId == null) == (allocationId == null)) }
}

/**
 * Supplier evidence only, never posted multi-currency Money. The exact entered identifier,
 * signed decimal and fractional precision survive native capture/import and canonical bytes.
 * No aliases, exchange rate, trimming, scale rounding or unsupported-currency conversion.
 */
@Serializable
public data class LocalSupplierCashLiteralAmount(
    public val currencyIdentifier: String, public val decimal: String,
) {
    init {
        require(currencyIdentifier.length in 1..32 && currencyIdentifier.all { it.isLetterOrDigit() || it in "._-" })
        require(decimal.length in 1..96 && Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?").matches(decimal))
    }

    /** Inert exact-value interpretation only. The caller must still match the intended payout. */
    public fun supportedEgpOrNull(): Money? {
        if (currencyIdentifier != Currency.EGP.name) return null
        val negative = decimal.startsWith('-')
        val parts = decimal.removePrefix("-").split('.')
        val fraction = parts.getOrElse(1) { "" }
        if (fraction.length > Currency.EGP.fractionDigits) return null
        val digits = parts[0] + fraction.padEnd(Currency.EGP.fractionDigits, '0')
        val minor = ((if (negative) "-" else "") + digits).toLongOrNull() ?: return null
        return Money(Currency.EGP, minor)
    }
}

/** A physical observation is retained literally, including unsupported/excess outcomes. */
@Serializable
public sealed interface LocalSupplierCashOutcome {
    @Serializable @SerialName("UNKNOWN")
    public data class Unknown(public val evidence: LocalCashEvidenceV1) : LocalSupplierCashOutcome
    @Serializable @SerialName("NOT_PAID")
    public data class NotPaid(public val evidence: LocalCashEvidenceV1, public val reasonCode: String) : LocalSupplierCashOutcome
    @Serializable @SerialName("CASH_PAID")
    public data class CashPaid(
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val amount: Money? = null,
        public val evidence: LocalCashEvidenceV1,
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val literalAmount: LocalSupplierCashLiteralAmount? = null,
    ) : LocalSupplierCashOutcome {
        init { require((amount == null) != (literalAmount == null)) }
        public fun supportedAmountOrNull(): Money? = amount ?: literalAmount?.supportedEgpOrNull()
    }
    @Serializable @SerialName("EXTERNAL_ATTESTED")
    public data class ExternalAttested(
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val amount: Money? = null,
        public val evidence: LocalCashEvidenceV1, public val reference: String?,
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val literalAmount: LocalSupplierCashLiteralAmount? = null,
    ) : LocalSupplierCashOutcome {
        init { require((amount == null) != (literalAmount == null)) }
    }
}

@Serializable
public sealed interface LocalSupplierCashRequest {
    @Serializable @SerialName("START_CASH")
    public data class StartCash(
        public val source: LocalSupplierCashSource, public val settlementId: DomainId,
        public val allocationId: DomainId?, public val sessionId: DomainId,
        public val expectedSessionRevision: Revision, public val expectedDrawerRevision: Revision,
        public val expectedCustodianRevision: Revision, public val expectedWorkRevision: Revision,
        public val expectedAvailableCash: Money, public val amount: Money, public val attemptId: DomainId,
        public val recordingOperationId: OperationId, public val resolutionOperationId: OperationId,
        public val masterAllocationId: DomainId, public val clientAllocationId: DomainId,
        public val reasonCode: String,
    ) : LocalSupplierCashRequest {
        init { require(amount.currency == Currency.EGP && amount.minor > 0L && expectedAvailableCash.currency == Currency.EGP &&
            expectedAvailableCash.minor >= 0L && recordingOperationId != resolutionOperationId && masterAllocationId != clientAllocationId)
            require((source is LocalSupplierCashSource.Advance) == (allocationId == null)); localReason(reasonCode) }
    }
    @Serializable @SerialName("RECORD_ORIGINAL")
    public data class RecordOriginal(public val target: LocalSupplierCashTarget,
        public val outcome: LocalSupplierCashOutcome) : LocalSupplierCashRequest
    @Serializable @SerialName("RECORD_RESOLUTION")
    public data class RecordResolution(public val target: LocalSupplierCashTarget,
        public val originalBodyDigest: PayloadDigest, public val resolverActorId: DomainId,
        public val outcome: LocalSupplierCashOutcome) : LocalSupplierCashRequest
    @Serializable @SerialName("APPLY_ORIGINAL")
    public data class ApplyOriginal(public val recordingOperationId: OperationId) : LocalSupplierCashRequest
    @Serializable @SerialName("APPLY_RESOLUTION")
    public data class ApplyResolution(public val resolutionOperationId: OperationId) : LocalSupplierCashRequest
}

@Serializable public enum class LocalSupplierCashPhase { ORIGINAL, RESOLUTION }
@Serializable public enum class LocalSupplierCashAction { CASH_PAID, NOT_PAID, UNKNOWN }
@Serializable public enum class LocalSupplierCashPendingReason {
    AWAITING_FINANCIAL_APPLICATION, SOURCE_UNAVAILABLE, SOURCE_OR_CUSTODY_CHANGED,
    VALUE_OR_TENDER_REQUIRES_RECONCILIATION, STORAGE_CAPACITY_REQUIRED, ORIGINAL_APPLICATION_REQUIRED,
}
@Serializable public data class LocalSupplierCashRevision(public val kind: String, public val id: DomainId, public val revision: Revision)
@Serializable
public sealed interface LocalSupplierCashResult {
    @Serializable @SerialName("ATTEMPT_STARTED")
    public data class AttemptStarted(public val target: LocalSupplierCashTarget, public val amount: Money,
        public val requirementDigest: PayloadDigest, public val revisions: List<LocalSupplierCashRevision>) : LocalSupplierCashResult
    @Serializable @SerialName("RAW_RETAINED")
    public data class RawRetained(public val target: LocalSupplierCashTarget,
        public val phase: LocalSupplierCashPhase = LocalSupplierCashPhase.ORIGINAL) : LocalSupplierCashResult
    @Serializable @SerialName("APPLIED")
    public data class Applied(public val target: LocalSupplierCashTarget, public val action: LocalSupplierCashAction,
        public val applicationOperationId: OperationId, public val amount: Money?, public val remainingDue: Money?,
        public val unappliedAdvance: Money?, public val revisions: List<LocalSupplierCashRevision>,
        public val phase: LocalSupplierCashPhase = LocalSupplierCashPhase.ORIGINAL) : LocalSupplierCashResult
    @Serializable @SerialName("PENDING_RECONCILIATION")
    public data class Pending(public val target: LocalSupplierCashTarget, public val reason: LocalSupplierCashPendingReason,
        public val phase: LocalSupplierCashPhase = LocalSupplierCashPhase.ORIGINAL) : LocalSupplierCashResult
    @Serializable @SerialName("ALREADY_APPLIED")
    public data class AlreadyApplied(public val target: LocalSupplierCashTarget, public val applicationOperationId: OperationId,
        public val phase: LocalSupplierCashPhase = LocalSupplierCashPhase.ORIGINAL) : LocalSupplierCashResult
    @Serializable @SerialName("UNAVAILABLE")
    public data class Unavailable(public val code: String) : LocalSupplierCashResult
}

public object LocalSupplierCashCodec {
    public fun command(operationId: OperationId, request: LocalSupplierCashRequest): MutationRequest =
        MutationRequest(operationId, LocalSupplierCashProfile.COMMAND, encode(request))
    public fun encode(request: LocalSupplierCashRequest): StoredBytes {
        when (request) {
            is LocalSupplierCashRequest.RecordOriginal -> supplierCashOutcome(request.outcome)
            is LocalSupplierCashRequest.RecordResolution -> { require(request.outcome !is LocalSupplierCashOutcome.Unknown); supplierCashOutcome(request.outcome) }
            else -> Unit
        }
        return localSalesEncode(request, LocalSupplierCashRequest.serializer(), LocalSupplierCashProfile.MAX_REQUEST_BYTES)
    }
    public fun decode(bytes: StoredBytes): LocalSupplierCashRequest = localSalesDecode(bytes,
        LocalSupplierCashRequest.serializer(), LocalSupplierCashProfile.MAX_REQUEST_BYTES)
    public fun normalize(bytes: StoredBytes): StoredBytes = encode(decode(bytes))
    public fun result(value: LocalSupplierCashResult): StoredBytes = localSalesEncode(value,
        LocalSupplierCashResult.serializer(), LocalSupplierCashProfile.MAX_RESULT_BYTES)
    public fun result(bytes: StoredBytes): LocalSupplierCashResult = localSalesDecode(bytes,
        LocalSupplierCashResult.serializer(), LocalSupplierCashProfile.MAX_RESULT_BYTES)
}

public fun LocalSupplierCashRequest.StartCash.target(operationId: OperationId): LocalSupplierCashTarget =
    LocalSupplierCashTarget(source.supplierId, settlementId, (source as? LocalSupplierCashSource.Invoice)?.invoiceId,
        allocationId, sessionId, attemptId, operationId, recordingOperationId, resolutionOperationId)
public fun LocalSupplierCashTarget.factOperation(phase: LocalSupplierCashPhase): OperationId =
    if (phase == LocalSupplierCashPhase.ORIGINAL) recordingOperationId else resolutionOperationId

internal fun supplierCashOutcome(value: LocalSupplierCashOutcome): Unit = when (value) {
    // Original EGP fields keep their order/bytes. New literal evidence never uses placeholder Money.
    is LocalSupplierCashOutcome.CashPaid -> require((value.amount == null) != (value.literalAmount == null))
    is LocalSupplierCashOutcome.ExternalAttested -> require((value.amount == null) != (value.literalAmount == null) &&
        (value.reference == null || value.reference.length in 1..128))
    is LocalSupplierCashOutcome.NotPaid -> localReason(value.reasonCode)
    is LocalSupplierCashOutcome.Unknown -> Unit
}
internal fun supplierCashTargetBinding(value: LocalSupplierCashTarget): PayloadDigest = sha256(journalFrame(listOf(
    "purchasing.supplier-cash-target/v1", value.supplierId.value, value.settlementId.value, value.invoiceId?.value ?: "",
    value.allocationId?.value ?: "", value.sessionId.value, value.attemptId.value, value.intentOperationId.value,
    value.recordingOperationId.value, value.resolutionOperationId.value).map(::journalText)))
internal fun supplierCashFenceBinding(value: LocalSupplierCashTarget, resolution: Boolean = false): PayloadDigest = sha256(journalFrame(listOf(
    "purchasing.supplier-cash-future/v1", supplierCashTargetBinding(value).value,
    if (resolution) "RESOLUTION" else "ORIGINAL", (if (resolution) value.resolutionOperationId else value.recordingOperationId).value).map(::journalText)))
