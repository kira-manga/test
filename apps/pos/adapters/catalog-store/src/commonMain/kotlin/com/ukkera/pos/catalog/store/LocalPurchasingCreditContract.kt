@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.catalog.store

import com.ukkera.pos.inventory.normalizedPurchaseReference
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

/** DEBIT is retained as an explicit unsupported request, never treated as a negative credit. */
@Serializable
public enum class LocalSupplierAdjustmentKindV1 { CREDIT, DEBIT }

/**
 * One agreed external credit is allocated completely to one locally posted invoice.
 * The original reference and evidence ID are immutable; their permanent claims prevent
 * another operation/ID/reference from consuming the same source again. Expected revisions
 * are storage generations; null means the corresponding row was observed absent.
 *
 * Signed/zero/excess amounts and DEBIT deliberately reach the authorized durable refusal
 * path. This contract does not represent an unapplied supplier balance, refund or payment.
 */
@Serializable
public data class LocalRecordSupplierCreditV1(
    public val creditId: DomainId, public val invoiceId: DomainId, public val supplierId: DomainId,
    public val expectedInvoiceRevision: Revision, public val expectedProgressRevision: Revision,
    public val expectedPayableRevision: Revision?, public val expectedCreditRevision: Revision?,
    public val expectedRemainingDue: Money?,
    public val reference: String, public val creditDate: BusinessDate, public val sourceEvidenceId: DomainId,
    public val kind: LocalSupplierAdjustmentKindV1, public val amount: Money, public val agreed: Boolean,
    public val reasonCode: String,
    /** Appended and default-omitted: genuine pre-settlement CREDIT v1 bytes remain canonical. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val expectedSettlementRevision: Revision? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val expectedAllocatedPayment: Money? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val expectedReservedCash: Money? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val expectedAvailableToAllocate: Money? = null,
) {
    init { normalizedPurchaseReference(reference); purchasingReason(reasonCode) }
}

@Serializable
public data class LocalPurchasingCreditSourceQueryV1(public val invoiceId: DomainId)

/** A cost-authorized observed cut, not a reservation or purchasing approval. */
@Serializable
public data class LocalPurchasingCreditSourceV1(
    public val query: LocalPurchasingCreditSourceQueryV1, public val sourcePresent: Boolean?,
    public val invoiceRevision: Revision?, public val progressRevision: Revision?, public val payableRevision: Revision?,
    public val creditRevision: Revision?, public val supplierId: DomainId?, public val supplierClaim: Money?,
    public val recognizedPayable: Money?, public val appliedCredit: Money?, public val remainingDue: Money?,
    public val availability: LocalPurchasingReadAvailabilityV1 = LocalPurchasingReadAvailabilityV1.AVAILABLE,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val settlementRevision: Revision? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val allocatedPayment: Money? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val reservedCash: Money? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val availableToAllocate: Money? = null,
) {
    init {
        if (availability == LocalPurchasingReadAvailabilityV1.AVAILABLE) {
            require(sourcePresent != null)
            if (sourcePresent == true) {
                require(invoiceRevision != null && progressRevision != null && supplierId != null && supplierClaim != null)
                require((payableRevision == null) == (recognizedPayable == null))
                require((payableRevision == null) == (appliedCredit == null) && (payableRevision == null) == (remainingDue == null))
                require(creditRevision == null || payableRevision != null)
                require(settlementRevision == null || payableRevision != null)
                require(listOf(allocatedPayment, reservedCash, availableToAllocate).all { it == null } ||
                    payableRevision != null && listOf(allocatedPayment, reservedCash, availableToAllocate).all { it != null })
                require(settlementRevision == null || allocatedPayment != null)
            } else require(invoiceRevision == null && progressRevision == null && payableRevision == null &&
                creditRevision == null && supplierId == null && supplierClaim == null && recognizedPayable == null &&
                appliedCredit == null && remainingDue == null && settlementRevision == null && allocatedPayment == null &&
                reservedCash == null && availableToAllocate == null)
        } else require(sourcePresent == null && invoiceRevision == null && progressRevision == null && payableRevision == null &&
            creditRevision == null && supplierId == null && supplierClaim == null && recognizedPayable == null &&
            appliedCredit == null && remainingDue == null && settlementRevision == null && allocatedPayment == null &&
                reservedCash == null && availableToAllocate == null)
    }
}

/** Original financial correction and its immutable invoice allocation; amounts require cost permission. */
@Serializable
public data class LocalPurchasingCreditViewV1(
    public val id: DomainId, public val invoiceId: DomainId, public val revision: Revision,
    public val reference: String, public val creditDate: BusinessDate, public val sourceEvidenceId: DomainId,
    public val amount: Money, public val recognizedBefore: Money, public val appliedBefore: Money,
    public val remainingBefore: Money, public val remainingAfter: Money,
    public val payableRevision: Revision, public val creditRevision: Revision,
)
