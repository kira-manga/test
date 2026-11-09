package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Independent human purposes. Neither one is an installer, employee or device grant. */
public enum class LocalCompanyHeadPurposeV1 { POLICY_PUBLISH, FULL_D7_INSTALLATION_APPROVE }

public enum class LocalCompanyHeadLifetimeV1 { UNTIL_LOCAL_DISABLE }

public enum class LocalCompanyAuthorityFailure {
    HEAD_ENROLLMENT_REQUIRED,
    ORIGINAL_TARGET_OWNER_REQUIRED,
    ORIGINAL_RESULT_REQUIRED,
    ORIGINAL_KEY_PREPARATION_REQUIRED,
    ORIGINAL_ATTEMPT_SETTLEMENT_REQUIRED,
    NATIVE_UNAVAILABLE,
}

/** A UI result is not a verifier, owned native history loan, employee or authority proof. */
public sealed interface LocalCompanyAuthorityResult<out T> {
    public class Value<T>(public val value: T) : LocalCompanyAuthorityResult<T> {
        override fun toString(): String = "LocalCompanyAuthorityResultV1([REDACTED])"
    }
    public data class Unavailable(
        public val reason: LocalCompanyAuthorityFailure,
        public val nativeReason: SelfOwnedInstallationFailureV1? = null,
    ) : LocalCompanyAuthorityResult<Nothing>
}

/** Explicit NEW local company and separately named head, never existing-company acquisition. */
public class LocalCompanyFoundingFactsV1(
    public val company: SelfOwnedCompanyFactsV1,
    public val headName: String,
    public val headLogin: String,
    public val headLifetime: LocalCompanyHeadLifetimeV1,
    purposes: Set<LocalCompanyHeadPurposeV1>,
) {
    private val ownedPurposes = purposes.toSet()
    public val purposes: Set<LocalCompanyHeadPurposeV1> get() = ownedPurposes.toSet()
    init {
        localCompanyHeadName(headName); selfOwnedLogin(headLogin)
        require(headLifetime == LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE)
        // This explicit fresh profile has TWO real producers, not an implicit future-purpose grant.
        require(ownedPurposes == LocalCompanyHeadPurposeV1.entries.toSet())
    }
    override fun toString(): String = "LocalCompanyFoundingFactsV1([REDACTED])"
}

public class LocalCompanyFoundingAction internal constructor(
    internal val owner: Any,
    internal val originalId: DomainId,
    public val companyName: String,
    public val installerName: String,
    public val installerLogin: String,
    public val headName: String,
    public val headLogin: String,
    public val headLifetime: LocalCompanyHeadLifetimeV1,
    public val rootFingerprint: String,
    public val organizationId: DomainId,
    public val companyId: DomainId,
    public val headAuthorityId: DomainId,
    public val headEnrollmentId: DomainId,
    public val protection: SelfOwnedKeyProtectionV1,
    /** Positive retained enrollment DATA. True forbids another password/enrollment ceremony. */
    public val enrollmentRetained: Boolean,
    internal val boot: Int,
    internal val expiresElapsedMillis: Long,
) {
    public val purposes: Set<LocalCompanyHeadPurposeV1> get() = LocalCompanyHeadPurposeV1.entries.toSet()
    override fun toString(): String = "LocalCompanyFoundingActionV1([REDACTED])"
}

public class LocalCompanyAuthorityView internal constructor(
    public val rootId: DomainId,
    public val organizationId: DomainId,
    public val companyId: DomainId,
    public val companyName: String,
    public val rootFingerprint: String,
    public val protection: SelfOwnedKeyProtectionV1,
    public val headAuthorityId: DomainId,
    public val headEnrollmentId: DomainId,
    public val headName: String,
    public val headLogin: String,
    public val headAccountRevision: Revision,
    public val headCredentialRevision: Revision,
    public val headEnabled: Boolean,
    public val policyIssuanceEnabled: Boolean,
    public val installationIssuanceEnabled: Boolean,
    public val foundingBodyDigest: PayloadDigest,
) {
    override fun toString(): String = "LocalCompanyAuthorityViewV1([REDACTED])"
}

/**
 * Complete A0a values, not an alternative business-policy rule model. The explicitly chosen
 * grants describe precisely what the root will endorse; rules describe what this publication
 * enables. Empty rules enable nothing. Neither syntactic grant IDs nor selectors prove source
 * qualification. This bounded native producer accepts only its original branch and explicit
 * supplier/SKU selectors. Every rule must have one exact, independently complete matching grant.
 * The expected predecessor selects a retained original revision, not a new root or clock.
 */
public class LocalCompanyPolicyProposal(
    public val expectedPredecessor: PurchasingPublicationReferenceV1?,
    public val validity: PurchasingValidityV1,
    grants: List<PurchasingAuthorityGrantV1.PolicyPublish>,
    retentionClasses: List<PurchasingRetentionClassV1>,
    rules: List<PurchasingPolicyRuleV1>,
) {
    private val ownedGrants = grants.toList()
    private val ownedRetention = retentionClasses.toList()
    private val ownedRules = rules.toList()
    public val grants: List<PurchasingAuthorityGrantV1.PolicyPublish> get() = ownedGrants.toList()
    public val retentionClasses: List<PurchasingRetentionClassV1> get() = ownedRetention.toList()
    public val rules: List<PurchasingPolicyRuleV1> get() = ownedRules.toList()
    init {
        require(ownedGrants.size in 1..LocalCompanyPolicyFormatV1.MAX_AUTHORITY_GRANTS)
        require(ownedRetention.size <= LocalCompanyPolicyFormatV1.MAX_RETENTION_CLASSES)
        require(ownedRules.size <= LocalCompanyPolicyFormatV1.MAX_RULES)
        require(ownedGrants.zipWithNext().all { (a, b) -> a.grantId.value < b.grantId.value })
        require(ownedRetention.zipWithNext().all { (a, b) -> a.classId.value < b.classId.value })
        require(ownedRules.zipWithNext().all { (a, b) -> a.ruleId.value < b.ruleId.value })
    }
    override fun toString(): String = "LocalCompanyPolicyProposalV1([REDACTED])"
}

/** Captured native intent. Recovered signatures are data, not current publication activation. */
public class LocalCompanyPolicyAction internal constructor(
    internal val owner: Any,
    public val originalActionId: DomainId,
    public val policy: PurchasingPolicyPublicationV1,
    public val delegation: PurchasingAuthorityDelegationV1,
    public val policyBodyDigest: PayloadDigest,
    public val delegationBodyDigest: PayloadDigest,
    internal val headAccountRevision: Long,
    internal val headCredentialRevision: Long,
    internal val purposeRevision: Long,
    internal val keyRevision: Long,
    internal val boot: Int,
    internal val expiresElapsedMillis: Long,
) {
    override fun toString(): String = "LocalCompanyPolicyActionV1([REDACTED])"
}

public class LocalCompanyPolicyPublication internal constructor(
    public val originalActionId: DomainId,
    public val policy: PurchasingSignedPolicyV1,
) {
    public fun copyWireBytes(): ByteArray = LocalCompanyPolicyWireV1.encodeSignedPolicy(policy)
    override fun toString(): String = "LocalCompanyPolicyPublicationV1([REDACTED])"
}

/** Reference to the actual P4 original proposal; a public snapshot never creates this owner. */
public class LocalCompanyInstallationAction internal constructor(
    internal val owner: Any,
    internal val originalProposal: OriginalInstallationProposal,
    internal val snapshot: LocalBranchInstallationSnapshotV2,
    internal val nextAttemptSequence: Long,
    internal val headAccountRevision: Long,
    internal val headCredentialRevision: Long,
    internal val purposeRevision: Long,
    internal val keyRevision: Long,
    internal val boot: Int,
    internal val expiresElapsedMillis: Long,
) {
    /** Complete stable content for review, not only a caller-provided summary or digest. */
    public val definition: LocalBranchInstallationDefinitionV2 get() = snapshot.definition
    public val definitionDigest: PayloadDigest get() = snapshot.definitionDigest
    public val allocationDigest: PayloadDigest get() = snapshot.allocationDigest
    override fun toString(): String = "LocalCompanyInstallationActionV1([REDACTED])"
}

/** Exact historical human output DATA, not target possession, SQL admission or an employee. */
public class LocalCompanyInstallationApproval internal constructor(
    internal val originalAttempt: OriginalInstallationAttempt,
    public val humanApproval: LocalInstallationSignedApprovalV2,
) {
    public val issuedAttemptId: DomainId get() = originalAttempt.facts.issuedAttemptId
    public val attemptSequence: Long get() = originalAttempt.facts.attemptSequence
    public val originalRequestId: DomainId get() = originalAttempt.snapshot.definition.originalRequestId
    public val definitionDigest: PayloadDigest get() = originalAttempt.snapshot.definitionDigest
    public val allocationDigest: PayloadDigest get() = originalAttempt.snapshot.allocationDigest
    override fun toString(): String = "LocalCompanyInstallationApprovalV1([REDACTED])"
}

/** UI-facing only. Actual P2/P4 composition performs the closed complete-definition join. */
public interface LocalCompanyAuthority {
    public suspend fun inspectCompanyAuthority(): LocalCompanyAuthorityResult<LocalCompanyAuthorityView>
    /**
     * Positive original founding action/data only. Does not choose IDs, generate keys or
     * replace credentials. Incomplete original key intents require explicit resume instead.
     */
    public suspend fun readOriginalCompanyAndHeadAction(): LocalCompanyAuthorityResult<LocalCompanyFoundingAction>
    /**
     * Explicitly resumes ONLY the retained original key intentions, using their exact IDs,
     * facts and original uncertainty rules. No supplied facts/secrets and no fresh fallback.
     * An already enrolled original returns enrollmentRetained=true without an enrollment write.
     */
    public suspend fun resumeOriginalCompanyAndHead(): LocalCompanyAuthorityResult<LocalCompanyFoundingAction>
    /** Original retained founding reference DATA. Reading it grants neither head nor target authority. */
    public suspend fun readOriginalInstallationHumanReference(): LocalCompanyAuthorityResult<LocalInstallationHumanReferenceV2>
    public suspend fun prepareNewCompanyAndHead(facts: LocalCompanyFoundingFactsV1): LocalCompanyAuthorityResult<LocalCompanyFoundingAction>
    /** Both independently supplied buffers transfer to this call, including refusal/cancellation. */
    public suspend fun completeNewCompanyAndHead(action: LocalCompanyFoundingAction,
        installerSecret: CharArray, headSecret: CharArray): LocalCompanyAuthorityResult<LocalCompanyAuthorityView>
    public suspend fun preparePolicyPublication(proposal: LocalCompanyPolicyProposal): LocalCompanyAuthorityResult<LocalCompanyPolicyAction>
    public suspend fun approvePolicyPublication(action: LocalCompanyPolicyAction,
        headLogin: String, secret: CharArray): LocalCompanyAuthorityResult<LocalCompanyPolicyPublication>
    public suspend fun recoverPolicyPublication(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalCompanyPolicyPublication>
    /** Requires the actual original P4 proposal, never a caller-minted snapshot/digest. */
    public suspend fun prepareOriginalInstallationDefinition(proposal: OriginalInstallationProposal):
        LocalCompanyAuthorityResult<LocalCompanyInstallationAction>
    public suspend fun approveOriginalInstallationDefinition(action: LocalCompanyInstallationAction,
        headLogin: String, secret: CharArray): LocalCompanyAuthorityResult<LocalCompanyInstallationApproval>
    /** Positive original data only; no renewed expiry, submission permission or absent-state proof. */
    public suspend fun readOriginalInstallationAttempt(): LocalCompanyAuthorityResult<LocalCompanyInstallationApproval>
    public suspend fun acknowledgeOriginalInstallationReceipt(completion: OriginalInstallationCompletion):
        LocalCompanyAuthorityResult<LocalInstallationSignedAcknowledgementV2>
    /** Denial-only exact terminal join. No head secret or new signature is required to retire. */
    public suspend fun retireOriginalInstallationAttempt(retirement: OriginalAttemptRetirement): LocalCompanyAuthorityResult<Unit>
    /** Null explicitly withdraws the head account; a purpose withdraws only that purpose. No enable/reset inverse. */
    public suspend fun withdrawHeadIssuance(purpose: LocalCompanyHeadPurposeV1?): LocalCompanyAuthorityResult<Unit>
    public fun denyHeadIssuance()
}

internal class LocalCompanyAuthorityRefusal(val reason: LocalCompanyAuthorityFailure) :
    RuntimeException("Local company authority unavailable")

internal fun localCompanyRefuse(reason: LocalCompanyAuthorityFailure): Nothing = throw LocalCompanyAuthorityRefusal(reason)

internal fun localCompanyHeadName(name: String) {
    selfOwnedLabel(name)
    require(name.length <= 128)
    val bytes = name.encodeToByteArray()
    try { require(bytes.size <= 256) } finally { bytes.fill(0) }
}
