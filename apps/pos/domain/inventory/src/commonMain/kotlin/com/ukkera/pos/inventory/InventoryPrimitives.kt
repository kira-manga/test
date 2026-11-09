package com.ukkera.pos.inventory

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

/** These are planning failures, never evidence that a payment failed or did not occur. */
public enum class InventoryFailure {
    STALE_REVISION,
    DUPLICATE_SOURCE,
    INVALID_QUANTITY,
    UNIT_OR_SCALE_MISMATCH,
    INVALID_STATE,
    INSUFFICIENT_STOCK,
    UNSAFE_STOCK,
    WRONG_SCOPE,
    WRONG_OWNERSHIP,
    RESERVATION_NOT_ACTIVE,
    COLLECTION_RESOLUTION_REQUIRED,
    INVALID_ORIGINAL_ALLOCATION,
    KITCHEN_CONFIRMATION_REQUIRED,
    KITCHEN_ALREADY_STARTED,
    UNSUPPORTED_LINEAGE,
    NEGATIVE_VALUE,
    BOUND_EXCEEDED,
}

public class InventoryRejection(public val failure: InventoryFailure, message: String) :
    IllegalArgumentException(message)

internal fun guard(condition: Boolean, failure: InventoryFailure, message: String) {
    if (!condition) throw InventoryRejection(failure, message)
}

internal fun requireRevision(actual: Revision, expected: Revision) {
    guard(actual == expected, InventoryFailure.STALE_REVISION, "The authoritative inventory cut changed")
}

internal fun requireSameUnit(left: Quantity, right: Quantity) {
    guard(left.unitId == right.unitId && left.scale == right.scale,
        InventoryFailure.UNIT_OR_SCALE_MISMATCH, "Use the pinned base unit and exact stock scale")
}

internal fun positive(quantity: Quantity) {
    guard(quantity.scaled > 0, InventoryFailure.INVALID_QUANTITY, "Quantity must be positive")
}

internal fun zeroLike(quantity: Quantity): Quantity = Quantity(quantity.unitId, 0, quantity.scale)

internal fun minor(value: Long): ExactRational = ExactRational.ratio(value, 1)

/** A copy with no mutable collection implementation exposed, including on JVM. */
internal fun <T> frozenList(items: Iterable<T>): List<T> = FrozenList(items.toList())

private class FrozenList<T>(private val items: List<T>) : AbstractList<T>() {
    override val size: Int get() = items.size
    override fun get(index: Int): T = items[index]
}

public data class InventoryScope(public val companyId: DomainId, public val branchId: DomainId)

public sealed class StockOwnership {
    public data object CompanyOwned : StockOwnership()
    public data class SupplierOwned(public val supplierId: DomainId) : StockOwnership()
}

/** FEFO lots share this pool; a lot is deliberately NOT a valuation key. */
public data class StockPoolKey(
    public val scope: InventoryScope,
    public val skuId: DomainId,
    public val warehouseId: DomainId,
    public val ownership: StockOwnership = StockOwnership.CompanyOwned,
)

public enum class StockCustody { RELEASED, QUARANTINE, REJECTED_HELD }

/** A null lot is one explicit untracked identity, not SQL-null uniqueness semantics. */
public data class StockPositionKey(
    public val pool: StockPoolKey,
    public val lotId: DomainId?,
    public val custody: StockCustody = StockCustody.RELEASED,
    public val serialId: DomainId? = null,
)

public enum class StockSafety { CLEAR, RECALLED, EXPIRED, COUNT_REVIEW }

public enum class StockSerialRegistrationStatus { LOCAL_PENDING_REGISTRATION, REGISTERED }

public data class StockPosition(
    public val key: StockPositionKey,
    public val onHand: Quantity,
    public val safety: StockSafety = StockSafety.CLEAR,
    public val expiresAtEpochMillis: Long? = null,
    /** Qualified registration identity, never a cashier-supplied sellable flag. */
    public val serialRegistrationId: DomainId? = null,
) {
    init {
        guard(onHand.scaled >= 0, InventoryFailure.INVALID_QUANTITY, "Physical stock cannot be negative")
        guard(expiresAtEpochMillis == null || expiresAtEpochMillis >= 0,
            InventoryFailure.INVALID_STATE, "Expiry must be a nonnegative authoritative instant")
        guard(key.pool.ownership == StockOwnership.CompanyOwned || key.custody != StockCustody.RELEASED,
            InventoryFailure.WRONG_OWNERSHIP, "Supplier-owned custody is not sellable stock")
        guard(key.serialId != null || serialRegistrationId == null, InventoryFailure.INVALID_STATE,
            "Nonserialized stock cannot carry a serial registration")
        guard(key.serialId == null || key.custody != StockCustody.RELEASED || serialRegistrationId != null,
            InventoryFailure.UNSAFE_STOCK, "Pending serial identity cannot be released custody")
        requireSerialQuantity(key, onHand, allowZero = true)
    }

    public val serialRegistration: StockSerialRegistrationStatus? get() = key.serialId?.let {
        if (serialRegistrationId == null) StockSerialRegistrationStatus.LOCAL_PENDING_REGISTRATION else StockSerialRegistrationStatus.REGISTERED
    }

    public fun isSellableAt(nowEpochMillis: Long): Boolean =
        key.pool.ownership == StockOwnership.CompanyOwned &&
            key.custody == StockCustody.RELEASED && safety == StockSafety.CLEAR &&
            (key.serialId == null || serialRegistrationId != null) &&
            (expiresAtEpochMillis == null || nowEpochMillis < expiresAtEpochMillis)
}

public data class StockAllocation(
    public val allocationId: DomainId,
    public val lineId: DomainId,
    public val position: StockPositionKey,
    public val quantity: Quantity,
) {
    init { positive(quantity); requireSerialQuantity(position, quantity) }
}
