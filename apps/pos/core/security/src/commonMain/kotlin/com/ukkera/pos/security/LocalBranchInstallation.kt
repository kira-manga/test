package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable

/** The registration verifier is an injected boundary, not implementation of a central service. */
public enum class LocalInstallationSourceKind { AUTHENTICATED_OFFLINE_PACKAGE, AUTHENTICATED_REGISTRATION }

/**
 * Requested, stable installation coordinates/references, NOT evidence by construction. The
 * named human is not the first branch-local account, and package/device possession does not
 * confer that human's authority. No training/import/recovery origin is accepted by this profile.
 * Trust references identify exact verified artifacts/qualified contexts, never boolean flags.
 * Artifact boundaries must be non-recursive: source/delegation references name independently
 * available canonical bodies. A detached/outer authenticated approval can then commit the full
 * request; no reference may hash a container that embeds its own resulting definition digest.
 */
@Serializable
public data class LocalBranchInstallationIdentity(
    public val scope: BranchScope,
    public val installationId: DomainId,
    public val assignmentId: DomainId,
    public val masterDeviceId: DomainId,
    public val authorityEpoch: Revision,
    public val firstActorId: DomainId,
    public val responsibleHumanId: DomainId,
    public val humanAuthorizationReference: PayloadDigest,
    public val sourceKind: LocalInstallationSourceKind,
    public val sourceId: DomainId,
    public val sourceDigest: PayloadDigest,
    public val deviceTrustReference: PayloadDigest,
    public val storageInstanceId: DomainId,
    public val storageKeyId: DomainId,
    public val pepperKeyId: DomainId,
    public val custodyReference: PayloadDigest,
    public val freshHistoryOriginReference: PayloadDigest,
    public val monotonicContextReference: PayloadDigest,
) {
    init { require(authorityEpoch.value < Long.MAX_VALUE) }
    override fun toString(): String = "LocalBranchInstallationIdentity([REDACTED])"
}

/** Explicit first account, complete policy/configuration and exact selected master admission. */
public class LocalBranchInstallationDefinition(
    public val identity: LocalBranchInstallationIdentity,
    public val firstAccount: LocalAccountProvisioning,
    public val masterAdmission: LocalDeviceAdmission,
) {
    init {
        require(firstAccount.actorId == identity.firstActorId && firstAccount.policy.scope == identity.scope)
        require(firstAccount.policy.revision == Revision(0))
        require(firstAccount.admittedDeviceIds == setOf(identity.masterDeviceId))
        require(masterAdmission.scope == identity.scope && masterAdmission.deviceId == identity.masterDeviceId &&
            masterAdmission.revision == Revision(0) && masterAdmission.admitted)
    }
    override fun toString(): String = "LocalBranchInstallationDefinition([REDACTED])"
}

/** The service computes this digest from the complete definition; request digests are not proof. */
@Serializable
public data class LocalBranchInstallationRequest(
    public val identity: LocalBranchInstallationIdentity,
    public val definitionDigest: PayloadDigest,
) {
    override fun toString(): String = "LocalBranchInstallationRequest([REDACTED])"
}

/**
 * Trusted human-verifier output, never a DTO accepted from a transport. The verifier must
 * authenticate this named person and their exact current company installation delegation,
 * separately from package possession and separately from a branch-local employee session.
 */
public class VerifiedInstallationHuman(
    public val request: LocalBranchInstallationRequest,
    public val humanId: DomainId,
    public val authorizationReference: PayloadDigest,
    private val currentLifetime: () -> Boolean,
) {
    init {
        require(humanId == request.identity.responsibleHumanId &&
            authorizationReference == request.identity.humanAuthorizationReference)
    }
    /** Synchronous, bounded and non-reentrant check of this exact authenticated lifetime. */
    public fun isCurrent(): Boolean = currentLifetime()
    override fun toString(): String = "VerifiedInstallationHuman([REDACTED])"
}

/**
 * Trusted package/target proof: verify the authenticated source's exact definition digest,
 * scope/appointment and the SAME physical key's current possession and requested admission
 * descriptors. A copied package, a device UUID or a caller-supplied digest is insufficient.
 */
public class VerifiedInstallationTarget(
    public val request: LocalBranchInstallationRequest,
    public val deviceId: DomainId,
    public val sourceDigest: PayloadDigest,
    public val deviceTrustReference: PayloadDigest,
    private val currentLifetime: () -> Boolean,
) {
    init {
        require(deviceId == request.identity.masterDeviceId && sourceDigest == request.identity.sourceDigest &&
            deviceTrustReference == request.identity.deviceTrustReference)
    }
    public fun isCurrent(): Boolean = currentLifetime()
    override fun toString(): String = "VerifiedInstallationTarget([REDACTED])"
}

/**
 * Actual supervised writer/storage identity, non-exporting key custody, fresh-history origin
 * and monotonic-context qualification. Verification MUST reject restored/copied/training
 * namespaces and unresolved external tails; an empty SQL file alone proves none of those.
 * Hash references/status enums do not implement these checks. No accepting adapter is supplied.
 */
public class VerifiedInstallationCustody(
    public val request: LocalBranchInstallationRequest,
    public val storageInstanceId: DomainId,
    public val storageKeyId: DomainId,
    public val pepperKeyId: DomainId,
    public val custodyReference: PayloadDigest,
    public val freshHistoryOriginReference: PayloadDigest,
    public val monotonicContextReference: PayloadDigest,
    private val currentLifetime: () -> Boolean,
) {
    init {
        val identity = request.identity
        require(storageInstanceId == identity.storageInstanceId && storageKeyId == identity.storageKeyId &&
            pepperKeyId == identity.pepperKeyId && custodyReference == identity.custodyReference &&
            freshHistoryOriginReference == identity.freshHistoryOriginReference &&
            monotonicContextReference == identity.monotonicContextReference)
    }
    public fun isCurrent(): Boolean = currentLifetime()
    override fun toString(): String = "VerifiedInstallationCustody([REDACTED])"
}

/**
 * Typed output of a real verifier, not cryptographic evidence merely because it has this type.
 * Trusted native composition must supply all three independent verifications; no synthetic
 * provider may be represented as qualified production trust. Current checks do not perform IO.
 */
public class VerifiedLocalBranchInstallationCeremony(
    public val request: LocalBranchInstallationRequest,
    public val human: VerifiedInstallationHuman,
    public val target: VerifiedInstallationTarget,
    public val custody: VerifiedInstallationCustody,
    public val validFrom: LocalAuthenticationObservation,
    public val expiresAtElapsedMillis: Long,
) {
    init {
        require(human.request == request && target.request == request && custody.request == request)
        require(expiresAtElapsedMillis > validFrom.moment.elapsedMillis)
    }
    public fun isCurrent(): Boolean = human.isCurrent() && target.isCurrent() && custody.isCurrent()
    public fun validAt(current: LocalAuthenticationObservation): Boolean =
        current.issuerContextId == validFrom.issuerContextId && current.moment.bootId == validFrom.moment.bootId &&
            current.moment.elapsedMillis >= validFrom.moment.elapsedMillis &&
            current.moment.elapsedMillis < expiresAtElapsedMillis
    override fun toString(): String = "VerifiedLocalBranchInstallationCeremony([REDACTED])"
}

public fun interface LocalBranchInstallationVerifier {
    /**
     * Read-only verification, outside the Room writer and before any installation effect.
     * The authenticated package/registration approval must independently carry the approved
     * full-definition digest, using the non-recursive artifact boundaries above.
     * Installation consumption and exact-receipt recovery belong to the immutable local record.
     * Must preserve cancellation and return null when any real prerequisite is unavailable.
     */
    public suspend fun verify(request: LocalBranchInstallationRequest): VerifiedLocalBranchInstallationCeremony?
}

/**
 * Immutable initial-record commitments, not credentials, a live runtime certificate or a
 * complete-history proof. Source/key/custody references are retained exactly in [request].
 * [genesis] is computed, never a zero hash or a caller-selected branch starting hash.
 */
public class LocalBranchInstallationRecord(
    public val request: LocalBranchInstallationRequest,
    public val issuerContextId: DomainId,
    public val installedAt: MonotonicMoment,
    public val accountDigest: PayloadDigest,
    public val policyDigest: PayloadDigest,
    public val configurationDigest: PayloadDigest,
    public val admissionDigest: PayloadDigest,
    public val accountThrottleDigest: PayloadDigest,
    public val deviceThrottleDigest: PayloadDigest,
    public val recoveryStateDigest: PayloadDigest,
) {
    public val genesis: PayloadDigest = installationGenesis(this)
    override fun toString(): String = "LocalBranchInstallationRecord([REDACTED])"
}

public sealed interface LocalBranchInstallationResult {
    /** Durable installation receipt only; [resumed] never resets or re-hashes the stored account. */
    public class Installed(public val record: LocalBranchInstallationRecord, public val resumed: Boolean) : LocalBranchInstallationResult {
        override fun toString(): String = "LocalBranchInstallationResult.Installed([REDACTED])"
    }
    /** Current authorized lookup found the complete supported initial store empty at its cut. */
    public data object NotRecorded : LocalBranchInstallationResult
    public data object Denied : LocalBranchInstallationResult
    public data object Unavailable : LocalBranchInstallationResult
    public data object StorageUncertain : LocalBranchInstallationResult
    public data object Busy : LocalBranchInstallationResult
}

/**
 * Production first-install/receipt service, not a seed DAO or the whole resumable setup wizard.
 * Only a completely empty supported live namespace or the EXACT initial footprint can succeed.
 * Later catalog/stock/wizard operations must resume through their own immutable identities.
 * No default account/password, training promotion, storage reset, recovery or implicit go-live.
 * Persist the installation identity before submission. Password remains caller-owned; on an
 * exact committed retry it never replaces the original credential. Lookup needs no password.
 */
public interface LocalBranchInitializer {
    public suspend fun initialize(definition: LocalBranchInstallationDefinition, password: CharArray): LocalBranchInstallationResult
    public suspend fun lookup(definition: LocalBranchInstallationDefinition): LocalBranchInstallationResult
}

/** Every field is canonical, bounded and length-framed; public hashes are not authority. */
private fun installationGenesis(record: LocalBranchInstallationRecord): PayloadDigest {
    val identity = record.request.identity
    val fields = listOf(
        "ukkera.branch-installation-genesis/v1", "fresh-live", "master-schema/6",
        identity.scope.organizationId.value, identity.scope.companyId.value, identity.scope.branchId.value,
        identity.installationId.value, identity.assignmentId.value, identity.masterDeviceId.value,
        identity.authorityEpoch.value.toString(), identity.firstActorId.value, identity.responsibleHumanId.value,
        identity.humanAuthorizationReference.value, identity.sourceKind.name, identity.sourceId.value,
        identity.sourceDigest.value, identity.deviceTrustReference.value, identity.storageInstanceId.value,
        identity.storageKeyId.value, identity.pepperKeyId.value, identity.custodyReference.value,
        identity.freshHistoryOriginReference.value, identity.monotonicContextReference.value,
        record.request.definitionDigest.value, record.issuerContextId.value, record.installedAt.bootId.value,
        record.installedAt.elapsedMillis.toString(), record.accountDigest.value, record.policyDigest.value,
        record.configurationDigest.value, record.admissionDigest.value, record.accountThrottleDigest.value,
        record.deviceThrottleDigest.value, record.recoveryStateDigest.value,
        "branch-revision/0", "journal-sequence/0", "writable-intent/true",
    ).map(String::encodeToByteArray)
    val bytes = ByteArray(4 + fields.sumOf { 4 + it.size })
    return try {
        var offset = 0
        fun integer(value: Int) {
            bytes[offset++] = (value ushr 24).toByte(); bytes[offset++] = (value ushr 16).toByte()
            bytes[offset++] = (value ushr 8).toByte(); bytes[offset++] = value.toByte()
        }
        integer(fields.size)
        for (field in fields) { integer(field.size); field.copyInto(bytes, offset); offset += field.size }
        sha256(bytes)
    } finally { bytes.fill(0); fields.forEach { it.fill(0) } }
}
