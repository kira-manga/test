package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.PhysicalRecordingAction
import com.ukkera.pos.database.PhysicalRecordingAdmission
import com.ukkera.pos.database.PhysicalRecordingParticipants
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.ExternalTenderKind
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable

/** Installed immutable metadata, not free space, a native lease, or a caller's qualification flag. */
public class LocalRetailJournalParticipants(
    public val masterOwner: LocalPhysicalCatalogueBinding,
    public val clientOwner: LocalPhysicalCatalogueBinding,
    public val masterStorageProfile: LocalPhysicalSpoolStorageProfileV1,
    public val clientStorageProfile: LocalPhysicalSpoolStorageProfileV1,
) {
    init {
        require(masterOwner.role == LocalPhysicalSpoolRole.MASTER && clientOwner.role == LocalPhysicalSpoolRole.CLIENT &&
            masterOwner.scope == clientOwner.scope)
        // A single device is allowed. Distinct native role files are established by B1, not this DTO.
    }
    override fun toString(): String = "LocalRetailJournalParticipantsV3([REDACTED])"
}

/** Trusted installed metadata source. MUST NOT open files or consult native capacity in a loan. */
public fun interface TransactionRetailJournalParticipantsSource {
    public suspend fun current(view: CommandReadView, participants: PhysicalRecordingParticipants): LocalRetailJournalParticipants?
}

public object UnavailableRetailJournalParticipantsSource : TransactionRetailJournalParticipantsSource {
    override suspend fun current(view: CommandReadView, participants: PhysicalRecordingParticipants): LocalRetailJournalParticipants? = null
}

@Serializable
internal data class LocalRetailJournalOwnerV3(
    val installationId: DomainId, val deviceId: DomainId, val catalogueId: DomainId, val keyId: DomainId,
) {
    fun binding(scope: BranchScope, role: LocalPhysicalSpoolRole): LocalPhysicalCatalogueBinding =
        LocalPhysicalCatalogueBinding(scope, installationId, deviceId, catalogueId, role, keyId)
    companion object {
        fun from(owner: LocalPhysicalCatalogueBinding): LocalRetailJournalOwnerV3 =
            LocalRetailJournalOwnerV3(owner.installationId, owner.deviceId, owner.catalogueId, owner.keyId)
    }
}

/** Derived from the full current retail cut and installed owners, not from a capacity response. */
@Serializable
internal data class LocalRetailDomainRequirementV3(
    val schemaVersion: Int,
    val target: LocalJournalTargetV3,
    val amount: Money,
    val tender: LocalTenderViewV1,
    val documentRevision: Revision,
    val sessionRevision: Revision,
    val authority: AuthorityIdentity,
    val masterOwner: LocalRetailJournalOwnerV3,
    val clientOwner: LocalRetailJournalOwnerV3,
    val financialResources: List<RecoveryResourceKey>,
)

internal suspend fun prepareRetailRequirement(
    view: CommandReadView, context: CommandContext, source: TransactionRetailJournalParticipantsSource,
    target: LocalJournalTargetV3, amount: Money, tender: LocalTenderViewV1,
    documentRevision: Revision, sessionRevision: Revision, masterAllocationId: DomainId, clientAllocationId: DomainId,
    financialResources: Set<RecoveryResourceKey>,
): LocalPhysicalJournalRequirementV1? {
    val participants = context.physicalParticipants ?: return null
    val installed = source.current(view, participants) ?: return null
    check(context.scope == view.scope && installed.masterOwner.scope == view.scope && installed.clientOwner.scope == view.scope &&
        installed.masterOwner.deviceId == participants.authority.deviceId && installed.clientOwner.deviceId == participants.clientDeviceId &&
        target.intentOperationId == context.operationId && context.key == retailJournalCommandV3(target.kind)) { "sales.journal.participants" }
    if (financialResources.size !in 1..LocalFinancialJournalProfileV3.MAX_FINANCIAL_RESOURCES) return null
    val domain = LocalRetailDomainRequirementV3(3, target, amount, tender, documentRevision, sessionRevision,
        participants.authority, LocalRetailJournalOwnerV3.from(installed.masterOwner),
        LocalRetailJournalOwnerV3.from(installed.clientOwner), sortedRetailResources(financialResources))
    return try {
        LocalPhysicalJournalRequirementV1(context.scope, context.originalActorId, context.operationId, context.semanticDigest,
            target.recordingOperationId, target.resolutionOperationId, target.attemptId, retailJournalProfileV3(target.kind),
            context.key, retailJournalTargetBindingV3(target), masterAllocationId, clientAllocationId,
            installed.masterOwner.deviceId, installed.clientOwner.deviceId, installed.masterStorageProfile,
            installed.clientStorageProfile, retailDomainBytesV3(domain)).also { retailJournalDomain(target.kind).validateRequirement(it) }
    } catch (_: LocalSalesWireCapacityExceeded) {
        null // A bounded planning cut is refused, never truncated or given fabricated backing.
    } catch (_: IllegalArgumentException) {
        // Only the PURE canonical constructor/codec is inside this try. Its total shared
        // 8192-byte frame bound can refuse even when the domain part fits. No IO is caught.
        null
    }
}

internal fun retailDomainBytesV3(value: LocalRetailDomainRequirementV3): StoredBytes = localSalesEncode(
    value, LocalRetailDomainRequirementV3.serializer(), LocalFinancialJournalProfileV3.MAX_DOMAIN_REQUIREMENT_BYTES)

internal fun retailDomainRequirementV3(requirement: LocalPhysicalJournalRequirementV1): LocalRetailDomainRequirementV3 =
    localSalesDecode(requirement.canonicalDomainRequirement, LocalRetailDomainRequirementV3.serializer(),
        LocalFinancialJournalProfileV3.MAX_DOMAIN_REQUIREMENT_BYTES)

internal fun sortedRetailResources(resources: Set<RecoveryResourceKey>): List<RecoveryResourceKey> =
    resources.sortedWith(compareBy<RecoveryResourceKey> { it.kind.name }.thenBy { it.resourceId.value })

internal fun retailAdmission(requirement: LocalPhysicalJournalRequirementV1, action: PhysicalRecordingAction): PhysicalRecordingAdmission =
    PhysicalRecordingAdmission(action, requirement.domainProfile, requirement.canonicalBytes)

internal fun retailJournalDomain(kind: SalesRecordingKind): LocalPhysicalJournalDomain = when (kind) {
    SalesRecordingKind.PAYMENT_ATTESTATION -> LocalRetailPaymentJournalDomain
    SalesRecordingKind.REFUND_ATTESTATION -> LocalRetailRefundJournalDomain
}

/** The native factory installs these two concrete codecs; it accepts no arbitrary wire-selected domain. */
public object LocalRetailPaymentJournalDomain : LocalPhysicalJournalDomain {
    override val domainProfile: String get() = LocalFinancialJournalProfileV3.PAYMENT_PROFILE
    override val command: CommandKey get() = LocalFinancialJournalProfileV3.COLLECTION_COMMAND
    override val startKind: LocalPhysicalJournalStartKind get() = LocalPhysicalJournalStartKind.PAYMENT
    override fun validateRequirement(requirement: LocalPhysicalJournalRequirementV1) {
        validateRetailRequirementV3(requirement, SalesRecordingKind.PAYMENT_ATTESTATION)
    }
    override fun classifyOriginal(requirement: LocalPhysicalJournalRequirementV1, body: StoredBytes): LocalPhysicalOriginalKind {
        validateRequirement(requirement)
        check(LocalCollectionCommandCodecV3.normalize(body) == body)
        val original = LocalCollectionCommandCodecV3.decode(body) as? LocalCollectionRequestV3.RecordOriginal
            ?: error("sales.journal.original_subtype")
        check(original.target == retailDomainRequirementV3(requirement).target)
        return if (original.outcome is LocalPaymentOutcomeV3.Unknown) LocalPhysicalOriginalKind.UNKNOWN else LocalPhysicalOriginalKind.KNOWN
    }
    override fun validateResolution(requirement: LocalPhysicalJournalRequirementV1, original: StoredBytes,
        resolverActorId: DomainId, resolverAuthorization: StoredBytes, body: StoredBytes) {
        check(classifyOriginal(requirement, original) == LocalPhysicalOriginalKind.UNKNOWN)
        check(LocalCollectionCommandCodecV3.normalize(body) == body)
        val resolution = LocalCollectionCommandCodecV3.decode(body) as? LocalCollectionRequestV3.RecordResolution
            ?: error("sales.journal.resolution_subtype")
        check(resolution.target == retailDomainRequirementV3(requirement).target &&
            resolution.originalBodyDigest == retailJournalDigestV3(original).value && resolution.resolverActorId == resolverActorId &&
            resolution.outcome !is LocalPaymentOutcomeV3.Unknown)
        check(resolverAuthorization == retailResolutionDescriptorV3(requirement, original, resolverActorId, body))
    }
}

public object LocalRetailRefundJournalDomain : LocalPhysicalJournalDomain {
    override val domainProfile: String get() = LocalFinancialJournalProfileV3.REFUND_PROFILE
    override val command: CommandKey get() = LocalFinancialJournalProfileV3.RETURN_COMMAND
    override val startKind: LocalPhysicalJournalStartKind get() = LocalPhysicalJournalStartKind.REFUND
    override fun validateRequirement(requirement: LocalPhysicalJournalRequirementV1) {
        validateRetailRequirementV3(requirement, SalesRecordingKind.REFUND_ATTESTATION)
    }
    override fun classifyOriginal(requirement: LocalPhysicalJournalRequirementV1, body: StoredBytes): LocalPhysicalOriginalKind {
        validateRequirement(requirement)
        check(LocalReturnCommandCodecV3.normalize(body) == body)
        val original = LocalReturnCommandCodecV3.decode(body) as? LocalReturnRequestV3.RecordOriginal
            ?: error("sales.journal.original_subtype")
        check(original.target == retailDomainRequirementV3(requirement).target)
        return if (original.outcome is LocalRefundOutcomeV3.Unknown) LocalPhysicalOriginalKind.UNKNOWN else LocalPhysicalOriginalKind.KNOWN
    }
    override fun validateResolution(requirement: LocalPhysicalJournalRequirementV1, original: StoredBytes,
        resolverActorId: DomainId, resolverAuthorization: StoredBytes, body: StoredBytes) {
        check(classifyOriginal(requirement, original) == LocalPhysicalOriginalKind.UNKNOWN)
        check(LocalReturnCommandCodecV3.normalize(body) == body)
        val resolution = LocalReturnCommandCodecV3.decode(body) as? LocalReturnRequestV3.RecordResolution
            ?: error("sales.journal.resolution_subtype")
        check(resolution.target == retailDomainRequirementV3(requirement).target &&
            resolution.originalBodyDigest == retailJournalDigestV3(original).value && resolution.resolverActorId == resolverActorId &&
            resolution.outcome !is LocalRefundOutcomeV3.Unknown)
        check(resolverAuthorization == retailResolutionDescriptorV3(requirement, original, resolverActorId, body))
    }
}

private fun validateRetailRequirementV3(requirement: LocalPhysicalJournalRequirementV1, kind: SalesRecordingKind) {
    val value = retailDomainRequirementV3(requirement)
    check(value.schemaVersion == 3 && value.target.kind == kind && requirement.domainProfile == retailJournalProfileV3(kind) &&
        requirement.command == retailJournalCommandV3(kind) && requirement.targetBinding == retailJournalTargetBindingV3(value.target) &&
        value.target.intentOperationId == requirement.intentOperationId && value.target.recordingOperationId == requirement.recordingOperationId &&
        value.target.resolutionOperationId == requirement.resolutionOperationId && value.target.attemptId == requirement.attemptId &&
        value.amount.minor > 0L && value.masterOwner.deviceId == requirement.masterDeviceId &&
        value.clientOwner.deviceId == requirement.clientDeviceId && value.authority.deviceId == requirement.masterDeviceId &&
        value.financialResources.size in 1..LocalFinancialJournalProfileV3.MAX_FINANCIAL_RESOURCES &&
        value.financialResources == sortedRetailResources(value.financialResources.toSet()) &&
        retailDomainBytesV3(value) == requirement.canonicalDomainRequirement)
    val tender = value.tender
    if (tender.kind == "CASH") check(tender == LocalTenderViewV1("CASH"))
    else check(tender.kind in ExternalTenderKind.entries.map { it.name } && tender.tenderId != null && tender.policyRevision != null)
    val minimum = setOf(cashResource(value.target.sessionId)) + when (kind) {
        SalesRecordingKind.PAYMENT_ATTESTATION -> setOf(orderResource(value.target.documentId))
        SalesRecordingKind.REFUND_ATTESTATION -> setOf(entitlementResource(value.target.documentId),
            entitlementResource(checkNotNull(value.target.originalSaleId)), entitlementResource(checkNotNull(value.target.originalClaimId)))
    }
    check(value.financialResources.containsAll(minimum))
}

/**
 * An exact linkage descriptor, NOT an authorization proof, session, bearer or permission.
 * The native store preserves it with the original UNKNOWN. SQL import/application separately
 * require CURRENT scoped COLLECTION_RESOLVE/REFUND_RESOLVE authorization in the actual
 * endpoint invocation. A distinct recorder never changes original actor/cash custody. Native
 * resolution retention accepts only the genuine core binding; this descriptor is not a grant.
 */
public object LocalRetailResolutionDescriptorV3 {
    public fun create(requirement: LocalPhysicalJournalRequirementV1, original: StoredBytes,
        resolverActorId: DomainId, resolution: StoredBytes): StoredBytes {
        val kind = retailDomainRequirementV3(requirement).target.kind
        val bytes = retailResolutionDescriptorV3(requirement, original, resolverActorId, resolution)
        retailJournalDomain(kind).validateResolution(requirement, original, resolverActorId, bytes, resolution)
        return bytes
    }
}

internal fun retailResolutionDescriptorV3(requirement: LocalPhysicalJournalRequirementV1, original: StoredBytes,
    resolverActorId: DomainId, resolution: StoredBytes): StoredBytes = StoredBytes(journalFrame(listOf(
        "sales.retail-resolution-linkage/v3", requirement.scope.organizationId.value, requirement.scope.companyId.value,
        requirement.scope.branchId.value, requirement.originalActorId.value, requirement.intentOperationId.value,
        requirement.recordingOperationId.value, retailJournalDigestV3(original).value, requirement.attemptId.value,
        requirement.resolutionOperationId.value, resolverActorId.value, retailJournalDigestV3(resolution).value,
        requirement.targetBinding.value, requirement.intentSemanticDigest.value,
    ).map(::journalText), 4_096))

internal fun retailJournalAuthorityResources(requirement: LocalPhysicalJournalRequirementV1): Set<RecoveryResourceKey> {
    val value = retailDomainRequirementV3(requirement)
    return (setOf(requirement.originalActorId, requirement.attemptId, DomainId(requirement.intentOperationId.value),
        DomainId(requirement.recordingOperationId.value), DomainId(requirement.resolutionOperationId.value),
        requirement.masterAllocationId, requirement.clientAllocationId, value.authority.assignmentId) +
        listOf(value.masterOwner, value.clientOwner).flatMap { listOf(it.installationId, it.deviceId, it.catalogueId, it.keyId) })
        .map(::recordingAuthority).toSet()
}
