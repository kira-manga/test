package com.ukkera.pos.sales.store

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.CashMovement
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/**
 * Read projection of one journaled non-sale movement, not a domain effect, command,
 * provenance proof or a declaration that the generic aggregate table is immutable.
 * The handler only inserts this row; the kernel journal is the immutable fact.
 */
public class CashNonSaleMovementProjection internal constructor(
    public val scope: BranchScope,
    public val sourceId: DomainId,
    public val operationId: OperationId,
    public val originalActorId: DomainId,
    public val semanticDigest: PayloadDigest,
    public val sessionId: DomainId,
    public val drawerId: DomainId,
    public val previousDomainRevision: Revision,
    public val domainRevision: Revision,
    public val cashLedgerRevision: Revision,
    public val kind: NonSaleCashKind,
    public val amount: Money,
    public val delta: Money,
    public val cashReceived: Money,
    public val changeGiven: Money,
    public val evidenceId: DomainId,
    public val occurredAt: UtcInstant,
    public val reason: String,
    public val recordedAt: UtcInstant,
)

public object CashNonSaleMovementCodec {
    /** Metadata must be independently read from the protected row, not its body. */
    public fun decode(
        payload: StoredBytes,
        expectedScope: BranchScope,
        expectedSourceId: DomainId,
        expectedStorageRevision: Revision,
    ): CashNonSaleMovementProjection {
        require(expectedStorageRevision == Revision(1)) { "Cash movement projection must be insert-only" }
        val stored = decodeBounded(payload, StoredCashNonSaleMovement.serializer(), CashNonSaleStoreProfile.MAX_ROW_BYTES)
        validateMovement(stored)
        require(stored.scope.restore() == expectedScope && stored.sourceId == expectedSourceId) {
            "Cash movement projection binding mismatch"
        }
        return stored.restore()
    }

    internal fun encode(
        context: CashNonSaleContext,
        before: CashSession,
        after: CashSession,
        request: CashNonSaleRequestV1,
        movement: CashMovement,
        recordedAt: UtcInstant,
    ): StoredBytes {
        val stored = StoredCashNonSaleMovement(1, CashMovementScope.from(context.scope), movement.sourceId,
            movement.operationId, movement.custodianId, context.semanticDigest.value, movement.sessionId,
            before.drawerId, before.revision, after.revision, after.cashLedgerRevision, request.kind,
            request.amount, movement.delta, movement.cashReceived, movement.changeGiven,
            movement.evidence.evidenceId, UtcInstant(movement.evidence.occurredAt), checkNotNull(movement.reason), recordedAt)
        validateMovement(stored)
        return encodeBounded(stored, StoredCashNonSaleMovement.serializer(), CashNonSaleStoreProfile.MAX_ROW_BYTES)
    }
}

@Serializable
private data class CashMovementScope(val organizationId: DomainId, val companyId: DomainId, val branchId: DomainId) {
    fun restore(): BranchScope = BranchScope(organizationId, companyId, branchId)

    companion object {
        fun from(value: BranchScope): CashMovementScope = CashMovementScope(value.organizationId, value.companyId, value.branchId)
    }
}

@Serializable
private data class StoredCashNonSaleMovement(
    val schemaVersion: Int,
    val scope: CashMovementScope,
    val sourceId: DomainId,
    val operationId: OperationId,
    val originalActorId: DomainId,
    val semanticDigest: String,
    val sessionId: DomainId,
    val drawerId: DomainId,
    val previousDomainRevision: Revision,
    val domainRevision: Revision,
    val cashLedgerRevision: Revision,
    val kind: NonSaleCashKind,
    val amount: Money,
    val delta: Money,
    val cashReceived: Money,
    val changeGiven: Money,
    val evidenceId: DomainId,
    val occurredAt: UtcInstant,
    val reason: String,
    val recordedAt: UtcInstant,
) {
    fun restore(): CashNonSaleMovementProjection = CashNonSaleMovementProjection(scope.restore(), sourceId,
        operationId, originalActorId, PayloadDigest(semanticDigest), sessionId, drawerId, previousDomainRevision,
        domainRevision, cashLedgerRevision, kind, amount, delta, cashReceived, changeGiven,
        evidenceId, occurredAt, reason, recordedAt)
}

private fun validateMovement(value: StoredCashNonSaleMovement) {
    require(value.schemaVersion == 1) { "Unsupported cash movement schema" }
    PayloadDigest(value.semanticDigest)
    require(value.domainRevision == value.previousDomainRevision.next() && value.cashLedgerRevision.value > 0L &&
        value.cashLedgerRevision <= value.domainRevision) { "Cash movement revision mismatch" }
    SalesStorageRevision.toStorage(value.domainRevision)
    require(value.amount.minor > 0L && value.reason.isNotBlank() && value.reason.length <= 256) {
        "Invalid non-sale movement evidence"
    }
    val expectedDelta = if (value.kind == NonSaleCashKind.NON_SALE_PAY_IN) value.amount else -value.amount
    val zero = Money(value.amount.currency, 0)
    require(value.delta == expectedDelta && value.cashReceived == zero && value.changeGiven == zero) {
        "Invalid non-sale movement category or amount"
    }
}
