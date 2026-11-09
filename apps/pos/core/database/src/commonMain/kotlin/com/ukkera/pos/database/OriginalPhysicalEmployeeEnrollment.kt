package com.ukkera.pos.database

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.NativeEmployeeClientCustody
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence

/**
 * Actual consumed/cold-admitted original enrollment. Only the original database service can
 * construct this from its native completion custody. An account row, actor selector, parsed
 * ledger, SDK employeeDeviceId or a signed definition cannot manufacture it.
 *
 * Immutable history identity only: it grants no session, present permission, unused rights,
 * physical capacity or journal ownership. In particular, history census cannot arm a Start.
 */
public class OriginalPhysicalEmployeeEnrollment internal constructor(
    internal val originalOwner: Any,
    internal val native: NativeEmployeeClientCustody,
    public val authority: AuthorityIdentity,
    public val firstActorId: DomainId,
    public val receiptDigest: PayloadDigest,
) {
    public val scope: BranchScope = native.scope
    public val actorId: DomainId = native.actorId
    public val deviceId: DomainId = native.deviceId
    public val installationId: DomainId = native.installationId
    public val enrollmentSequence: JournalSequence = JournalSequence(native.enrollmentChild.historySequence)
    public val originalRequestId: DomainId = native.request.requestId
    public val originalActionId: DomainId = native.originalActionId
    public val journalId: DomainId = native.intentJournalId
    public val journalKeyId: DomainId = native.intentJournalKeyId

    /** Same actual retained native completion, not equality of projected actor IDs. */
    public fun sameOriginalEnrollment(other: OriginalPhysicalEmployeeEnrollment): Boolean =
        originalOwner === other.originalOwner && native === other.native

    /** Referential owner identity only; immutable history is never present authorization. */
    public fun belongsToOriginalOwner(value: Any): Boolean = originalOwner === value

    override public fun toString(): String = "OriginalPhysicalEmployeeEnrollment([REDACTED])"
}
