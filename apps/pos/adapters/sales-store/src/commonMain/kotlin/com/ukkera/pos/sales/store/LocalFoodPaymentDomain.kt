package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** Catalogue identity DATA only. These fields never open, enroll, replace or adopt an owner. */
@Serializable
internal data class LocalFoodJournalOwner(val installationId: DomainId, val deviceId: DomainId,
    val catalogueId: DomainId, val keyId: DomainId) {
    fun binding(scope: BranchScope, role: LocalPhysicalSpoolRole): LocalPhysicalCatalogueBinding =
        LocalPhysicalCatalogueBinding(scope, installationId, deviceId, catalogueId, role, keyId)
    companion object {
        fun from(owner: LocalPhysicalCatalogueBinding): LocalFoodJournalOwner = LocalFoodJournalOwner(
            owner.installationId, owner.deviceId, owner.catalogueId, owner.keyId)
    }
}

/** Full genuine FOOD cut plus actual installed native metadata; never a capacity response. */
@Serializable
internal data class LocalFoodPaymentDomainRequirement(val version: Int, val target: LocalFoodPaymentTarget,
    val amount: Money, val orderStorageRevision: Revision, val collectionRevision: Revision,
    val sessionRevision: Revision, val stockSourcesDigest: PayloadDigest,
    val stockDeadlineExclusive: UtcInstant?, val authority: AuthorityIdentity,
    val masterOwner: LocalFoodJournalOwner, val clientOwner: LocalFoodJournalOwner,
    val financialResources: List<RecoveryResourceKey>)

internal fun foodPaymentDomainBytes(value: LocalFoodPaymentDomainRequirement): StoredBytes = localSalesEncode(
    value, LocalFoodPaymentDomainRequirement.serializer(), LocalFoodPaymentProfile.MAX_DOMAIN_BYTES)
internal fun foodPaymentDomain(requirement: LocalPhysicalJournalRequirementV1): LocalFoodPaymentDomainRequirement =
    localSalesDecode(requirement.canonicalDomainRequirement, LocalFoodPaymentDomainRequirement.serializer(),
        LocalFoodPaymentProfile.MAX_DOMAIN_BYTES)
internal fun foodPaymentSortedResources(values: Set<RecoveryResourceKey>): List<RecoveryResourceKey> = values.sortedWith(
    compareBy<RecoveryResourceKey> { it.kind.name }.thenBy { it.resourceId.value })

/** Pure bounded display projection. Parsing this never obtains a journal or instruction. */
public class LocalFoodPaymentPhysicalProjection private constructor(public val requirement: LocalPhysicalJournalRequirementV1,
    public val target: LocalFoodPaymentTarget, public val amount: Money) {
    public companion object {
        public fun parse(canonicalRequirement: StoredBytes): LocalFoodPaymentPhysicalProjection {
            val requirement = LocalPhysicalJournalRequirementV1.decode(canonicalRequirement)
            LocalFoodCashJournalDomain.validateRequirement(requirement)
            val domain = foodPaymentDomain(requirement)
            return LocalFoodPaymentPhysicalProjection(requirement, domain.target, domain.amount)
        }
    }
}

/** Concrete closed native FOOD codec, explicitly separate from both retail money domains. */
public object LocalFoodCashJournalDomain : LocalPhysicalJournalDomain {
    override val command: CommandKey get() = LocalFoodPaymentProfile.COMMAND
    override val domainProfile: String get() = LocalFoodPaymentProfile.DOMAIN
    override val startKind: LocalPhysicalJournalStartKind get() = LocalPhysicalJournalStartKind.PAYMENT
    override fun validateRequirement(requirement: LocalPhysicalJournalRequirementV1) {
        val domain = foodPaymentDomain(requirement)
        val target = domain.target
        check(domain.version == 1 && requirement.command == command && requirement.domainProfile == domainProfile &&
            requirement.targetBinding == foodPaymentTargetBinding(target) &&
            target.intentOperationId == requirement.intentOperationId && target.recordingOperationId == requirement.recordingOperationId &&
            target.resolutionOperationId == requirement.resolutionOperationId && target.attemptId == requirement.attemptId &&
            domain.amount.currency == Currency.EGP && domain.amount.minor > 0L && domain.orderStorageRevision.value >= 1 &&
            domain.masterOwner.deviceId == requirement.masterDeviceId && domain.clientOwner.deviceId == requirement.clientDeviceId &&
            domain.authority.deviceId == requirement.masterDeviceId &&
            domain.financialResources.size in 1..LocalFoodPaymentProfile.MAX_FINANCIAL_RESOURCES &&
            domain.financialResources == foodPaymentSortedResources(domain.financialResources.toSet()) &&
            domain.financialResources.containsAll(setOf(orderResource(target.orderId), orderResource(target.collectionId),
                cashResource(target.sessionId))) && foodPaymentDomainBytes(domain) == requirement.canonicalDomainRequirement)
    }
    override fun classifyOriginal(requirement: LocalPhysicalJournalRequirementV1, body: StoredBytes): LocalPhysicalOriginalKind {
        validateRequirement(requirement)
        check(LocalFoodPaymentCodec.normalize(body) == body)
        val original = LocalFoodPaymentCodec.decode(body) as? LocalFoodPaymentRequest.RecordOriginal
            ?: error("food.payment.original_subtype")
        check(original.target == foodPaymentDomain(requirement).target)
        return if (original.outcome is LocalFoodCashOutcome.Unknown) LocalPhysicalOriginalKind.UNKNOWN else LocalPhysicalOriginalKind.KNOWN
    }
    override fun validateResolution(requirement: LocalPhysicalJournalRequirementV1, original: StoredBytes,
        resolverActorId: DomainId, resolverAuthorization: StoredBytes, body: StoredBytes) {
        check(classifyOriginal(requirement, original) == LocalPhysicalOriginalKind.UNKNOWN &&
            LocalFoodPaymentCodec.normalize(body) == body)
        val resolution = LocalFoodPaymentCodec.decode(body) as? LocalFoodPaymentRequest.RecordResolution
            ?: error("food.payment.resolution_subtype")
        check(resolution.target == foodPaymentDomain(requirement).target &&
            resolution.originalBodyDigest == sha256(original.copyBytes()) &&
            resolution.resolverActorId == resolverActorId && resolution.outcome !is LocalFoodCashOutcome.Unknown &&
            resolverAuthorization == foodPaymentResolutionDescriptor(requirement, original, resolverActorId, body))
    }
}

/** Historical name of the existing SPI denotes installed owner metadata, not a retail tender. */
internal suspend fun prepareFoodPaymentRequirement(view: CommandReadView, context: CommandContext,
    owners: TransactionRetailJournalParticipantsSource, request: LocalFoodPaymentRequest.StartCash,
    target: LocalFoodPaymentTarget, financialResources: Set<RecoveryResourceKey>, stockDigest: PayloadDigest,
    stockDeadline: UtcInstant?): LocalPhysicalJournalRequirementV1? {
    val participants = context.physicalParticipants ?: return null
    val installed = owners.current(view, participants) ?: return null
    check(context.scope == view.scope && context.key == LocalFoodPaymentProfile.COMMAND &&
        target.intentOperationId == context.operationId && participants.originalActorId == context.originalActorId &&
        installed.masterOwner.scope == view.scope && installed.clientOwner.scope == view.scope &&
        installed.masterOwner.deviceId == participants.authority.deviceId && installed.clientOwner.deviceId == participants.clientDeviceId)
    if (financialResources.size !in 1..LocalFoodPaymentProfile.MAX_FINANCIAL_RESOURCES) return null
    return try {
        val domain = LocalFoodPaymentDomainRequirement(1, target, request.amount, request.expectedOrderStorageRevision,
            request.expectedCollectionRevision, request.expectedSessionRevision, stockDigest, stockDeadline,
            participants.authority, LocalFoodJournalOwner.from(installed.masterOwner), LocalFoodJournalOwner.from(installed.clientOwner),
            foodPaymentSortedResources(financialResources))
        LocalPhysicalJournalRequirementV1(context.scope, context.originalActorId, context.operationId, context.semanticDigest,
            target.recordingOperationId, target.resolutionOperationId, target.attemptId, LocalFoodPaymentProfile.DOMAIN,
            context.key, foodPaymentTargetBinding(target), request.masterAllocationId, request.clientAllocationId,
            installed.masterOwner.deviceId, installed.clientOwner.deviceId, installed.masterStorageProfile,
            installed.clientStorageProfile, foodPaymentDomainBytes(domain)).also { LocalFoodCashJournalDomain.validateRequirement(it) }
    } catch (_: LocalSalesWireCapacityExceeded) { null }
      catch (_: IllegalArgumentException) { null } // Only the pure bounded canonical constructors are inside this catch.
}

internal fun foodPaymentAdmission(requirement: LocalPhysicalJournalRequirementV1,
    action: PhysicalRecordingAction): PhysicalRecordingAdmission = PhysicalRecordingAdmission(action,
    LocalFoodPaymentProfile.DOMAIN, requirement.canonicalBytes)

internal fun foodPaymentAuthorityResources(requirement: LocalPhysicalJournalRequirementV1): Set<RecoveryResourceKey> {
    val domain = foodPaymentDomain(requirement)
    return (setOf(requirement.originalActorId, requirement.attemptId, DomainId(requirement.intentOperationId.value),
        DomainId(requirement.recordingOperationId.value), DomainId(requirement.resolutionOperationId.value),
        requirement.masterAllocationId, requirement.clientAllocationId, domain.authority.assignmentId) +
        listOf(domain.masterOwner, domain.clientOwner).flatMap { listOf(it.installationId, it.deviceId, it.catalogueId, it.keyId) })
        .map(::recordingAuthority).toSet()
}

/** Both genuine immutable source rows, exactly matched to the canonical FOOD/native role pair. */
internal fun requireFoodPaymentSourcePair(requirement: LocalPhysicalJournalRequirementV1,
    changes: List<AggregateChange>): Pair<LocalPhysicalJournalSourceProjection, LocalPhysicalJournalSourceProjection> {
    LocalFoodCashJournalDomain.validateRequirement(requirement)
    check(changes.size == 2 && changes.map { it.key }.toSet() == setOf(
        AggregateKey(PhysicalRecordingSourceProfileV1.KIND, requirement.masterAllocationId),
        AggregateKey(PhysicalRecordingSourceProfileV1.KIND, requirement.clientAllocationId)))
    val domain = foodPaymentDomain(requirement)
    val parsed = changes.map { LocalPhysicalJournalSourceProjection.decode(it) }
    val master = parsed.single { it.owner.role == LocalPhysicalSpoolRole.MASTER }
    val client = parsed.single { it.owner.role == LocalPhysicalSpoolRole.CLIENT }
    check(master.requirement.canonicalBytes == requirement.canonicalBytes && client.requirement.canonicalBytes == requirement.canonicalBytes &&
        master.owner == domain.masterOwner.binding(requirement.scope, LocalPhysicalSpoolRole.MASTER) &&
        client.owner == domain.clientOwner.binding(requirement.scope, LocalPhysicalSpoolRole.CLIENT) &&
        master.allocationId == requirement.masterAllocationId && client.allocationId == requirement.clientAllocationId &&
        master.nativeIdentity.profile == requirement.masterStorageProfile && client.nativeIdentity.profile == requirement.clientStorageProfile &&
        changes.single { it.key.id == master.allocationId }.payload == master.sourceCommitment &&
        changes.single { it.key.id == client.allocationId }.payload == client.sourceCommitment &&
        (requirement.masterDeviceId != requirement.clientDeviceId || master.nativeIdentity.fileIdentity != client.nativeIdentity.fileIdentity))
    return master to client
}

internal fun foodPaymentFences(requirement: LocalPhysicalJournalRequirementV1): List<AggregateChange> {
    val target = foodPaymentDomain(requirement).target
    return listOf(false, true).map { resolution -> ReservedOperations.create(FutureOperationReservation(
        if (resolution) target.resolutionOperationId else target.recordingOperationId, requirement.originalActorId,
        LocalFoodPaymentProfile.COMMAND, foodPaymentFenceBinding(target, resolution))) }
}

internal fun foodPaymentLineageClaims(requirement: LocalPhysicalJournalRequirementV1): List<ClaimChange> = listOf(
    ClaimChange.Acquire(foodPaymentOperationClaim(requirement.recordingOperationId), requirement.attemptId),
    ClaimChange.Acquire(foodPaymentOperationClaim(requirement.resolutionOperationId), requirement.attemptId),
    ClaimChange.Acquire(foodPaymentAllocationClaim(requirement.masterAllocationId), requirement.attemptId),
    ClaimChange.Acquire(foodPaymentAllocationClaim(requirement.clientAllocationId), requirement.attemptId),
    ClaimChange.Acquire(foodPaymentAttemptClaim(requirement.attemptId), DomainId(requirement.recordingOperationId.value)),
)

internal class FoodPaymentStartSources(val slot: LocalFoodPaymentSlot,
    val changes: List<AggregateChange>, val claims: List<ClaimChange>)

/** The only Start source path: actual kernel-owned pair in the final REAL transaction. */
internal fun foodPaymentStartSources(context: CommandContext,
    requirement: LocalPhysicalJournalRequirementV1): FoodPaymentStartSources? {
    val native = context.physicalRecording ?: return null
    val admission = foodPaymentAdmission(requirement, PhysicalRecordingAction.START_PAYMENT)
    if (!native.matches(admission)) return null
    val domain = foodPaymentDomain(requirement)
    check(context.scope == requirement.scope && context.originalActorId == requirement.originalActorId &&
        context.operationId == requirement.intentOperationId && context.key == requirement.command &&
        context.semanticDigest == requirement.intentSemanticDigest && context.physicalParticipants?.let {
            it.authority == domain.authority && it.clientDeviceId == requirement.clientDeviceId &&
                it.originalActorId == requirement.originalActorId
        } == true)
    val pair = requireFoodPaymentSourcePair(requirement, native.sourceChanges)
    val slot = LocalFoodPaymentSlot(1, requirement.scope, requirement.originalActorId, requirement.intentSemanticDigest,
        domain, requirement.masterAllocationId, requirement.clientAllocationId, requirement.masterStorageProfile.name,
        requirement.clientStorageProfile.name, LocalFoodPaymentSourceReference.from(pair.first), LocalFoodPaymentSourceReference.from(pair.second))
    check(slot.requirement().canonicalBytes == requirement.canonicalBytes)
    return FoodPaymentStartSources(slot, native.sourceChanges + foodPaymentSlotWrite(slot) + foodPaymentFences(requirement),
        foodPaymentLineageClaims(requirement))
}

/** FOOD-specific native linkage DATA. Current authorization/audit and SQL lineage are separate. */
public object LocalFoodPaymentResolutionDescriptor {
    public fun create(requirement: LocalPhysicalJournalRequirementV1, original: StoredBytes,
        resolverActorId: DomainId, resolution: StoredBytes): StoredBytes {
        val descriptor = foodPaymentResolutionDescriptor(requirement, original, resolverActorId, resolution)
        LocalFoodCashJournalDomain.validateResolution(requirement, original, resolverActorId, descriptor, resolution)
        return descriptor
    }
}

internal fun foodPaymentResolutionDescriptor(requirement: LocalPhysicalJournalRequirementV1, original: StoredBytes,
    resolverActorId: DomainId, resolution: StoredBytes): StoredBytes = StoredBytes(journalFrame(listOf(
        "sales.food-payment-resolution-linkage/v1", requirement.scope.organizationId.value,
        requirement.scope.companyId.value, requirement.scope.branchId.value, requirement.originalActorId.value,
        requirement.intentOperationId.value, requirement.recordingOperationId.value, sha256(original.copyBytes()).value,
        requirement.attemptId.value, requirement.resolutionOperationId.value, resolverActorId.value,
        sha256(resolution.copyBytes()).value, requirement.targetBinding.value, requirement.intentSemanticDigest.value,
    ).map(::journalText), 4_096))
