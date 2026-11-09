package com.ukkera.pos.database

import com.ukkera.pos.types.DomainId

/**
 * Non-authoritative DATA naming a CLIENT allocation in a future installed service's own catalogue.
 * It selects no scope, source, owner, operation, actor, body, certificate, session or result reader.
 * Constructing this value neither schedules work nor proves that the allocation exists.
 */
public data class NativeOriginalIntakeLocator(
    public val clientAllocationId: DomainId,
) {
    override public fun toString(): String = "NativeOriginalIntakeLocatorV1([REDACTED])"
}

/**
 * Fixed submission vocabulary from the SAME endpoint's bounded service queue.
 * QUEUED means admitted/coalesced service work, not native existence, SQL commit, reconciliation,
 * guaranteed processing or permission to disclose a private result.
 */
public enum class NativeOriginalIntakeSubmission {
    QUEUED,
    BUSY,
    UNAVAILABLE,
}
