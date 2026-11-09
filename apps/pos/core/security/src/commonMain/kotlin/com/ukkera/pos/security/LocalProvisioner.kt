package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

public enum class LocalProvisionerActionKind { FOUND_NEW_COMPANY, PREPARE_ORIGINAL_BRANCH }

/** Non-serializable original action bound to one actual native owner; not human verification. */
public class LocalProvisionerPendingAction internal constructor(
    internal val owner: Any,
    internal val originalId: DomainId,
    public val kind: LocalProvisionerActionKind,
    public val companyName: String,
    public val responsiblePersonName: String,
    public val installerLogin: String,
    public val branchName: String?,
    public val rootFingerprint: String?,
    public val protection: SelfOwnedKeyProtectionV1,
    public val organizationId: DomainId,
    public val companyId: DomainId,
    public val allocation: SelfOwnedAllocationCoordinatesV1?,
    public val target: SelfOwnedTargetDescriptorV1,
    internal val boot: Int,
    internal val expiresElapsedMillis: Long,
) {
    override fun toString(): String = "LocalProvisionerPendingActionV1([REDACTED])"
}

public class LocalProvisionerCompanyView internal constructor(
    public val rootId: DomainId,
    public val organizationId: DomainId,
    public val companyId: DomainId,
    public val companyName: String,
    public val responsiblePersonName: String,
    public val installerLogin: String,
    public val rootFingerprint: String,
    public val protection: SelfOwnedKeyProtectionV1,
    public val issuanceEnabled: Boolean,
) {
    override fun toString(): String = "LocalProvisionerCompanyViewV1([REDACTED])"
}

public sealed interface LocalProvisionerInspection {
    /** No retained local authority was observed; NOT proof of historical absence or installability. */
    public data object Unenrolled : LocalProvisionerInspection
    public class FoundingPending(public val action: LocalProvisionerPendingAction) : LocalProvisionerInspection
    public class Enrolled(public val company: LocalProvisionerCompanyView,
        public val originalAction: LocalProvisionerPendingAction?, public val originalGrantRetained: Boolean) : LocalProvisionerInspection
    public data class Unavailable(public val reason: SelfOwnedInstallationFailureV1) : LocalProvisionerInspection
}

public sealed interface LocalProvisionerPreparation {
    public class Prepared(public val action: LocalProvisionerPendingAction) : LocalProvisionerPreparation
    public data class Unavailable(public val reason: SelfOwnedInstallationFailureV1) : LocalProvisionerPreparation
}

public sealed interface LocalProvisionerEnrollment {
    /** Local authority/account retained, not an installer session, branch employee or master runtime. */
    public class Enrolled(public val company: LocalProvisionerCompanyView) : LocalProvisionerEnrollment
    public data class Unavailable(public val reason: SelfOwnedInstallationFailureV1) : LocalProvisionerEnrollment
}

public sealed interface LocalProvisionerGrant {
    public class Retained(public val packet: SelfOwnedPreparationPacketV1) : LocalProvisionerGrant
    /** No negative absence/creation authority. Resume only the same durable original action. */
    public data class Unresolved(public val reason: SelfOwnedInstallationFailureV1) : LocalProvisionerGrant
}

public sealed interface LocalProvisionerWithdrawal {
    public data object Retained : LocalProvisionerWithdrawal
    public data class Unavailable(public val reason: SelfOwnedInstallationFailureV1) : LocalProvisionerWithdrawal
}

/**
 * Local provisioner foundation ONLY. A concrete native factory owns the keys, registry and
 * authenticated installer work. Implementing this UI-facing interface is not native proof.
 * No method emits VerifiedInstallationHuman/Target/Custody or a Room initializer/runtime.
 * No arbitrary imported root, identity, signing callback or Boolean can enroll authority.
 *
 * Password buffers transfer to this call and are cleared, including refusal/cancellation;
 * an admitted private copy survives caller cancellation until actual native completion.
 * Failed/lost replies retain the same original IDs. No reset/delete/rekey method exists.
 */
public interface LocalProvisioner {
    /** Separate fresh head enrollment/policy surface on this SAME actual native lifetime. */
    public val companyAuthority: LocalCompanyAuthority
    public suspend fun inspect(): LocalProvisionerInspection
    public suspend fun prepareNewCompany(facts: SelfOwnedCompanyFactsV1): LocalProvisionerPreparation
    public suspend fun completeNewCompany(action: LocalProvisionerPendingAction, secret: CharArray): LocalProvisionerEnrollment
    /** Joins ONLY this actually enrolled native company. An external public root is not self-authenticating. */
    public suspend fun prepareBranchWithEnrolledCompany(facts: SelfOwnedBranchFactsV1): LocalProvisionerPreparation
    public suspend fun issueOriginalPreparation(action: LocalProvisionerPendingAction,
        installerLogin: String, secret: CharArray): LocalProvisionerGrant
    public suspend fun recoverOriginalPreparation(): LocalProvisionerGrant
    /** Denial-only and durable. It neither deletes old grants/data nor authorizes an installation. */
    public suspend fun withdrawNewIssuance(): LocalProvisionerWithdrawal
    /** Immediate bounded denial only; not durable withdrawal or proof that native handles closed. */
    public fun denyNewIssuance()
    /** Caller cancellation does not stop owned close or release custody prematurely. */
    public suspend fun close()
}

public sealed interface LocalProvisionerOpenResult {
    public class Opened(public val provisioner: LocalProvisioner) : LocalProvisionerOpenResult
    public data class Unavailable(public val reason: SelfOwnedInstallationFailureV1) : LocalProvisionerOpenResult
}
