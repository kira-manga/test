package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

public object LocalSupplierSettlementProfile {
    public val ALLOCATE_ADVANCE: CommandKey = CommandKey("local.purchasing.advance.allocate", 1)
    public val READ_SOURCE: ReadKey = ReadKey("local.purchasing.settlement-source", 1)
    public val READ_ADVANCES: ReadKey = ReadKey("local.purchasing.advances", 1)
    public val READ_OWN_CASH: ReadKey = ReadKey("local.purchasing.supplier-cash-own", 1)
    public const val ATTEMPT: String = "local.purchasing.settlement-attempt"
    public const val PAYMENT: String = "local.purchasing.supplier-payment"
    public const val ADVANCE: String = "local.purchasing.supplier-advance"
    public const val ADVANCE_ALLOCATIONS: String = "local.purchasing.advance-allocations"
    public const val ALLOCATION: String = "local.purchasing.settlement-allocation"
    public const val INVOICE_SETTLEMENTS: String = "local.purchasing.invoice-settlements"
    public const val EVENT: String = "local.purchasing.settlement-event"
    public const val MAX_ENTRIES: Int = 64
    public const val MAX_INDEX_HISTORY: Int = 192
    public val kinds: Set<String> = setOf(ATTEMPT, PAYMENT, ADVANCE, ADVANCE_ALLOCATIONS, ALLOCATION, INVOICE_SETTLEMENTS, EVENT)
}

/** A separate nonphysical allocation of an already paid advance; no second cash movement. */
@Serializable
public data class LocalAllocateSupplierAdvanceV1(
    public val allocationId: DomainId, public val advanceId: DomainId,
    public val expectedAdvanceRevision: Revision, public val expectedAdvanceAllocationRevision: Revision,
    public val expectedUnappliedAdvance: Money, public val invoice: LocalSupplierCashSource.Invoice,
    public val amount: Money, public val reasonCode: String,
) {
    init { require(amount.currency == Currency.EGP && amount.minor > 0L && expectedUnappliedAdvance.currency == Currency.EGP &&
        expectedUnappliedAdvance.minor >= 0L); purchasingReason(reasonCode) }
}

@Serializable
public data class LocalSupplierAdvanceAllocationResultV1(public val allocationId: DomainId,
    public val invoiceId: DomainId, public val advanceId: DomainId, public val amount: Money,
    public val remainingDue: Money, public val unappliedAdvance: Money,
    public val invoiceSettlementRevision: Revision, public val advanceAllocationRevision: Revision)

@Serializable
public data class LocalSupplierSettlementSourceQueryV1(public val supplierId: DomainId,
    public val invoiceId: DomainId? = null, public val sessionId: DomainId? = null, public val advanceId: DomainId? = null) {
    init { require(advanceId == null || invoiceId != null) }
}

@Serializable
public data class LocalSupplierSettlementSourceV1(
    public val query: LocalSupplierSettlementSourceQueryV1, public val source: LocalSupplierCashSource?,
    public val sessionRevision: Revision?, public val drawerRevision: Revision?, public val custodianRevision: Revision?,
    public val workRevision: Revision?, public val availableCash: Money?, public val designatedPostingBranch: DomainId?,
    public val allocatedPayment: Money?, public val appliedCredit: Money?, public val reservedCash: Money?,
    public val availableToAllocate: Money?, public val advanceRevision: Revision?, public val advanceAllocationRevision: Revision?,
    public val unappliedAdvance: Money?, public val unavailableCode: String?,
)

@Serializable
public data class LocalSupplierAdvanceQueryV1(public val supplierId: DomainId? = null,
    public val id: DomainId? = null, public val after: DomainId? = null, public val limit: Int = 5,
    public val export: Boolean = false) { init { require(limit in 1..5 && (id == null || after == null)) } }
@Serializable
public data class LocalSupplierAdvanceViewV1(public val id: DomainId, public val supplierId: DomainId,
    public val paymentId: DomainId, public val revision: Revision, public val allocationRevision: Revision,
    public val amount: Money, public val allocated: Money, public val unapplied: Money,
    public val recordedAt: UtcInstant, public val allocationIds: List<DomainId>)
@Serializable
public data class LocalSupplierAdvancePageV1(public val advances: List<LocalSupplierAdvanceViewV1>?,
    public val nextAfter: DomainId?, public val unavailableCode: String? = null)

/** Financial allocations only. Native originals, cashiers and reusable custody stay private. */
@Serializable
public data class LocalSupplierAllocationViewV1(public val id: DomainId, public val paymentId: DomainId,
    public val advanceId: DomainId?, public val amount: Money, public val recordedAt: UtcInstant,
    public val remainingBefore: Money, public val remainingAfter: Money)

@Serializable
public data class LocalSupplierCashQueryV1(public val recordingOperationId: OperationId)
@Serializable
public data class LocalSupplierOwnCashQueryV1(public val after: DomainId? = null, public val limit: Int = 5) {
    init { require(limit in 1..5) }
}
/** A bounded scan page; an empty own-filtered page still retains its actual scan cursor. */
@Serializable
public data class LocalSupplierOwnCashPageV1(public val entries: List<LocalSupplierCashViewV1>?,
    public val nextAfter: DomainId?, public val unavailableCode: String? = null)
@Serializable
public data class LocalSupplierCashViewV1(
    public val recordingOperationId: OperationId, public val target: LocalSupplierCashTarget?,
    public val canonicalRequirementHex: String?, public val originalBody: String?, public val resolutionBody: String?,
    public val originalApplied: LocalSupplierCashResult?, public val resolutionApplied: LocalSupplierCashResult?,
    public val amount: Money?, public val status: String?, public val unavailableCode: String? = null,
) {
    /** Bounded display/linkage bytes only; decoding cannot create a native instruction. */
    public fun requirementBytes(): StoredBytes {
        check(unavailableCode == null)
        val value = checkNotNull(canonicalRequirementHex)
        check(value.length in 2..16_384 && value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' })
        return StoredBytes(ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }).also { bytes ->
            val projection = LocalSupplierCashPhysicalProjection.parse(bytes)
            check(projection.requirement.recordingOperationId == recordingOperationId &&
                projection.domain.target == checkNotNull(target) && projection.domain.amount == checkNotNull(amount))
        }
    }
}

public object LocalSupplierSettlementCodec {
    public fun allocate(operationId: OperationId, request: LocalAllocateSupplierAdvanceV1): MutationRequest =
        MutationRequest(operationId, LocalSupplierSettlementProfile.ALLOCATE_ADVANCE, purchasingEncode(request, LocalAllocateSupplierAdvanceV1.serializer()))
    public fun allocation(bytes: StoredBytes): LocalAllocateSupplierAdvanceV1 = purchasingDecode(bytes, LocalAllocateSupplierAdvanceV1.serializer())
    public fun allocationResult(bytes: StoredBytes): LocalSupplierAdvanceAllocationResultV1 = purchasingDecode(bytes, LocalSupplierAdvanceAllocationResultV1.serializer())
    public fun source(query: LocalSupplierSettlementSourceQueryV1): StoredBytes = purchasingEncode(query, LocalSupplierSettlementSourceQueryV1.serializer())
    public fun source(bytes: StoredBytes): LocalSupplierSettlementSourceV1 = purchasingDecode(bytes, LocalSupplierSettlementSourceV1.serializer())
    public fun advances(query: LocalSupplierAdvanceQueryV1): StoredBytes = purchasingEncode(query, LocalSupplierAdvanceQueryV1.serializer())
    public fun advances(bytes: StoredBytes): LocalSupplierAdvancePageV1 = purchasingDecode(bytes, LocalSupplierAdvancePageV1.serializer())
    public fun cash(query: LocalSupplierCashQueryV1): StoredBytes = purchasingEncode(query, LocalSupplierCashQueryV1.serializer())
    public fun cash(bytes: StoredBytes): LocalSupplierCashViewV1 = purchasingDecode(bytes, LocalSupplierCashViewV1.serializer())
    public fun ownCash(query: LocalSupplierOwnCashQueryV1): StoredBytes = purchasingEncode(query, LocalSupplierOwnCashQueryV1.serializer())
    public fun ownCash(bytes: StoredBytes): LocalSupplierOwnCashPageV1 = purchasingDecode(bytes, LocalSupplierOwnCashPageV1.serializer())
}
