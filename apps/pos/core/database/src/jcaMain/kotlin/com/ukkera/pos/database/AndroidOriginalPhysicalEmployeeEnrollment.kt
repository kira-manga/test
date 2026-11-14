package com.ukkera.pos.database

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.NativeEmployeeClientCustody

/** Exact original-service/native joins only. No public scalar/history-witness constructor. */
internal fun originalPhysicalEmployeeEnrollment(original: AndroidOriginalLocalClient,
    native: NativeEmployeeClientCustody): OriginalPhysicalEmployeeEnrollment {
    val c = original.material.coordinates
    val installed = original.receipt.core.allocation.coordinates
    check(original.isCurrent() && native.isCurrent() && native.actorId != c.firstActorId &&
        native.scope == c.scope && native.scope == original.peer.scope && native.deviceId == c.deviceId &&
        native.deviceId == original.peer.deviceId && native.installationId == c.installationId &&
        installed.scope == c.scope && installed.deviceId == c.deviceId && installed.installationId == c.installationId &&
        installed.assignmentId == c.assignmentId && installed.firstActorId == c.firstActorId &&
        native.intentJournalId != native.intentJournalKeyId)
    return OriginalPhysicalEmployeeEnrollment(original, native,
        AuthorityIdentity(c.assignmentId, c.deviceId, original.receipt.core.authorityEpoch),
        c.firstActorId, original.receipt.digest)
}

/** Native recheck outside Room, never a predicate invoked by the transaction participant view. */
public fun OriginalPhysicalEmployeeEnrollment.belongsTo(original: AndroidOriginalLocalClient): Boolean =
    originalOwner === original && original.isCurrent() && native.isCurrent()
