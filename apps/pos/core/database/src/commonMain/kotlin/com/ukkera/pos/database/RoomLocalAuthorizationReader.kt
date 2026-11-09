package com.ukkera.pos.database

import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalAccessIssuance
import com.ukkera.pos.security.LocalAccountSessionState
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.Revision

/**
 * Concrete, internal read-only borrower. It cannot acquire/commit a transaction, replace a
 * protected row or return a password record. The backing transaction retains every failure
 * until its owning view closes, so a caught decoder/AEAD/lifetime error cannot be forgotten.
 */
internal class RoomLocalAuthorizationReader internal constructor(
    private val transaction: RoomLocalSecurityTransaction,
) {
    internal suspend fun facts(principal: LocalEmployeePrincipal): RoomLocalAuthorizationFacts? =
        transaction.readAuthorizationFacts(principal)

    internal suspend fun handoffRecipients(principal: LocalEmployeePrincipal, orderId: com.ukkera.pos.types.DomainId,
        after: com.ukkera.pos.types.DomainId?, limit: Int): LocalHandoffRecipientPage =
        transaction.handoffRecipientPage(principal.scope, principal.actorId, orderId, after, limit)
    internal suspend fun handoffRecipient(principal: LocalEmployeePrincipal, orderId: com.ukkera.pos.types.DomainId,
        recipientId: com.ukkera.pos.types.DomainId): LocalHandoffRecipient? =
        transaction.readHandoffRecipient(principal.scope, orderId, recipientId)
    internal suspend fun handoffInboxTarget(principal: LocalEmployeePrincipal): com.ukkera.pos.types.DomainId? =
        transaction.handoffInboxTarget(principal.scope, principal.actorId)

    internal suspend fun ensureHealthy(): Unit = transaction.ensureAuthorizationHealthy()

    /** This read-only borrower has no finalization frames: closure only invalidates it.
     * The suspend signature follows the transaction owner's awaited cleanup contract. */
    internal suspend fun close(): Unit = transaction.close()
}

/** Internal projection only. Complete authenticated canonical fingerprints stay private. */
internal class RoomLocalAuthorizationFacts internal constructor(
    val account: LocalAccountSessionState,
    val credentialRevision: Revision,
    val credentialPresent: Boolean,
    val policy: ActivatedLocalPolicy,
    val admission: LocalDeviceAdmission,
    val issuance: LocalAccessIssuance,
    fingerprints: List<PayloadDigest>,
) {
    private val fingerprints = fingerprints.toList()

    init { require(this.fingerprints.size == 4) }

    internal fun sameDependencies(other: RoomLocalAuthorizationFacts): Boolean = fingerprints == other.fingerprints

    /** Only the original writer's internal capture uses these authenticated canonical hashes.
     * No protected plaintext, password/verifier or bearer is exposed by this projection. */
    internal fun runtimeCommandDependencies(): RuntimeCommandAuthorizationDependenciesV1 =
        RuntimeCommandAuthorizationDependenciesV1(fingerprints[0], fingerprints[1], fingerprints[2], fingerprints[3])

    override fun toString(): String = "RoomLocalAuthorizationFacts([REDACTED])"
}
