package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** No persistence/custody callback here: only the immutable bounded state owned by the native registry. */
internal data class LocalProvisionerInstallerV1(
    val installerId: DomainId,
    val name: String,
    val login: String,
    val credentialRevision: Long,
    val delegationRevision: Long,
    val enabled: Boolean,
    val salt: SelfOwnedOctetsV1,
    val verifier: SelfOwnedOctetsV1,
    val failedAttempts: Int,
    val reservationId: DomainId?,
    val throttleBoot: Int,
    val nextAllowedElapsedMillis: Long,
    val cooldownMillis: Long,
) {
    init {
        selfOwnedLabel(name); selfOwnedLogin(login)
        require(credentialRevision >= 0 && delegationRevision >= 0 && salt.size == 32 && verifier.size == 32)
        require(failedAttempts in 0..SelfOwnedInstallationProfileV1.MAXIMUM_FAILED_ATTEMPTS && throttleBoot >= 0 && nextAllowedElapsedMillis >= 0)
        require(cooldownMillis in 0..SelfOwnedInstallationProfileV1.MAXIMUM_COOLDOWN_MILLIS)
    }
    override fun toString(): String = "LocalProvisionerInstallerV1([REDACTED])"
}

internal enum class LocalProvisionerAllocationPhaseV1 { ALLOCATED, SIGNING_INTENT, GRANT_RETAINED }

internal data class LocalProvisionerAllocationV1(
    val body: SelfOwnedPreparationBodyV1,
    val phase: LocalProvisionerAllocationPhaseV1,
    val grant: SelfOwnedSignedDocumentV1?,
) {
    init {
        require((phase == LocalProvisionerAllocationPhaseV1.GRANT_RETAINED) == (grant != null))
        require(grant == null || grant.role == SelfOwnedDocumentRoleV1.NATIVE_PREPARATION)
    }
    override fun toString(): String = "LocalProvisionerAllocationV1([REDACTED])"
}

internal data class LocalProvisionerRegistryStateV1(
    val revision: Long,
    val root: SelfOwnedSignedDocumentV1,
    val delegation: SelfOwnedSignedDocumentV1,
    val issuanceEnabled: Boolean,
    val nextSerial: Long,
    val installers: List<LocalProvisionerInstallerV1>,
    val allocations: List<LocalProvisionerAllocationV1>,
) {
    init {
        require(revision in 0 until Long.MAX_VALUE && nextSerial in 1 until Long.MAX_VALUE)
        require(root.role == SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT && delegation.role == SelfOwnedDocumentRoleV1.ISSUER_DELEGATION)
        require(installers.size in 1..SelfOwnedInstallationProfileV1.MAXIMUM_INSTALLERS)
        require(allocations.size <= SelfOwnedInstallationProfileV1.MAXIMUM_ALLOCATIONS)
        require(installers.map { it.installerId }.distinct().size == installers.size && installers.map { it.login }.distinct().size == installers.size)
        require(allocations.map { it.body.coordinates.allocationId }.distinct().size == allocations.size)
        require(allocations.map { it.body.coordinates.scope }.distinct().size == allocations.size)
        require(allocations.map { it.body.serial }.distinct().size == allocations.size && allocations.all { it.body.serial < nextSerial })
    }

    fun next(
        issuanceEnabled: Boolean = this.issuanceEnabled,
        nextSerial: Long = this.nextSerial,
        installers: List<LocalProvisionerInstallerV1> = this.installers,
        allocations: List<LocalProvisionerAllocationV1> = this.allocations,
    ): LocalProvisionerRegistryStateV1 {
        if (revision == Long.MAX_VALUE - 1) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        return copy(revision = revision + 1, issuanceEnabled = issuanceEnabled, nextSerial = nextSerial,
            installers = installers.toList(), allocations = allocations.toList())
    }

    fun replaceInstaller(record: LocalProvisionerInstallerV1): LocalProvisionerRegistryStateV1 {
        require(installers.count { it.installerId == record.installerId } == 1)
        return next(installers = installers.map { if (it.installerId == record.installerId) record else it })
    }

    fun replaceAllocation(record: LocalProvisionerAllocationV1): LocalProvisionerRegistryStateV1 {
        require(allocations.count { it.body.coordinates.allocationId == record.body.coordinates.allocationId } == 1)
        return next(allocations = allocations.map { if (it.body.coordinates.allocationId == record.body.coordinates.allocationId) record else it })
    }
    override fun toString(): String = "LocalProvisionerRegistryStateV1([REDACTED])"
}
