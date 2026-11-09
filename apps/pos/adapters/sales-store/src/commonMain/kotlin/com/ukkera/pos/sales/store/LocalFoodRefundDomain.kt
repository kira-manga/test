package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** Full genuine FOOD cut plus actual installed native metadata; never a capacity response. */
@Serializable
internal data class LocalFoodRefundDomainRequirement(val version: Int, val target: LocalFoodRefundTarget,
    val amount: Money, val refundRevision: Revision, val sessionRevision: Revision,
    val obligationSourcesDigest: PayloadDigest, val authority: AuthorityIdentity,
    val masterOwner: LocalFoodJournalOwner, val clientOwner: LocalFoodJournalOwner,
    val financialResources: List<RecoveryResourceKey>)

internal fun foodRefundDomainBytes(value: LocalFoodRefundDomainRequirement): StoredBytes = localSalesEncode(
    value, LocalFoodRefundDomainRequirement.serializer(), LocalFoodRefundProfile.MAX_DOMAIN_BYTES)
internal fun foodRefundDomain(requirement: LocalPhysicalJournalRequirementV1): LocalFoodRefundDomainRequirement =
    localSalesDecode(requirement.canonicalDomainRequirement, LocalFoodRefundDomainRequirement.serializer(),
        LocalFoodRefundProfile.MAX_DOMAIN_BYTES)
internal fun foodRefundSortedResources(values: Set<RecoveryResourceKey>): List<RecoveryResourceKey> = values.sortedWith(
    compareBy<RecoveryResourceKey> { it.kind.name }.thenBy { it.resourceId.value })

/** Pure bounded display projection. Parsing this never obtains a journal or instruction. */
public class LocalFoodRefundPhysicalProjection private constructor(public val requirement: LocalPhysicalJournalRequirementV1,
    public val target: LocalFoodRefundTarget, public val amount: Money) {
    public companion object {
        public fun parse(canonicalRequirement: StoredBytes): LocalFoodRefundPhysicalProjection {
            val requirement = LocalPhysicalJournalRequirementV1.decode(canonicalRequirement)
            LocalFoodRefundCashJournalDomain.validateRequirement(requirement)
            val domain = foodRefundDomain(requirement)
            return LocalFoodRefundPhysicalProjection(requirement, domain.target, domain.amount)
        }
    }
}

/** Concrete closed native FOOD codec, explicitly separate from both retail money domains. */
public object LocalFoodRefundCashJournalDomain : LocalPhysicalJournalDomain {
    override val command: CommandKey get() = LocalFoodRefundProfile.COMMAND
    override val domainProfile: String get() = LocalFoodRefundProfile.DOMAIN
    override val startKind: LocalPhysicalJournalStartKind get() = LocalPhysicalJournalStartKind.REFUND
    override fun validateRequirement(requirement: LocalPhysicalJournalRequirementV1) {
        val domain = foodRefundDomain(requirement)
        val target = domain.target
        check(domain.version == 1 && requirement.command == command && requirement.domainProfile == domainProfile &&
            requirement.targetBinding == foodRefundTargetBinding(target) &&
            target.intentOperationId == requirement.intentOperationId && target.recordingOperationId == requirement.recordingOperationId &&
            target.resolutionOperationId == requirement.resolutionOperationId && target.attemptId == requirement.attemptId &&
            domain.amount.currency == Currency.EGP && domain.amount.minor > 0L &&
            domain.masterOwner.deviceId == requirement.masterDeviceId && domain.clientOwner.deviceId == requirement.clientDeviceId &&
            domain.authority.deviceId == requirement.masterDeviceId &&
            domain.financialResources.size in 1..LocalFoodRefundProfile.MAX_FINANCIAL_RESOURCES &&
            domain.financialResources == foodRefundSortedResources(domain.financialResources.toSet()) &&
            domain.financialResources.containsAll(setOf(entitlementResource(target.refundId), entitlementResource(target.orderId),
                orderResource(target.collectionId), cashResource(target.sessionId))) && foodRefundDomainBytes(domain) == requirement.canonicalDomainRequirement)
    }
    override fun classifyOriginal(requirement: LocalPhysicalJournalRequirementV1, body: StoredBytes): LocalPhysicalOriginalKind {
        validateRequirement(requirement)
        check(LocalFoodRefundCodec.normalize(body) == body)
        val original = LocalFoodRefundCodec.decode(body) as? LocalFoodRefundRequest.RecordOriginal
            ?: error("food.refund.original_subtype")
        check(original.target == foodRefundDomain(requirement).target)
        return if (original.outcome is LocalFoodRefundOutcome.Unknown) LocalPhysicalOriginalKind.UNKNOWN else LocalPhysicalOriginalKind.KNOWN
    }
    override fun validateResolution(requirement: LocalPhysicalJournalRequirementV1, original: StoredBytes,
        resolverActorId: DomainId, resolverAuthorization: StoredBytes, body: StoredBytes) {
        check(classifyOriginal(requirement, original) == LocalPhysicalOriginalKind.UNKNOWN &&
            resolverActorId == requirement.originalActorId && LocalFoodRefundCodec.normalize(body) == body)
        val resolution = LocalFoodRefundCodec.decode(body) as? LocalFoodRefundRequest.RecordResolution
            ?: error("food.refund.resolution_subtype")
        check(resolution.target == foodRefundDomain(requirement).target &&
            resolution.originalBodyDigest == sha256(original.copyBytes()) &&
            resolution.resolverActorId == resolverActorId && resolution.outcome !is LocalFoodRefundOutcome.Unknown &&
            resolverAuthorization == foodRefundResolutionDescriptor(requirement, original, resolverActorId, body))
    }
}

/** Historical name of the existing SPI denotes installed owner metadata, not a retail tender. */
internal suspend fun prepareFoodRefundRequirement(view: CommandReadView, context: CommandContext,
    owners: TransactionRetailJournalParticipantsSource, request: LocalFoodRefundRequest.StartCash,
    target: LocalFoodRefundTarget, financialResources: Set<RecoveryResourceKey>, obligationSourcesDigest: PayloadDigest): LocalPhysicalJournalRequirementV1? {
    val participants = context.physicalParticipants ?: return null
    val installed = owners.current(view, participants) ?: return null
    check(context.scope == view.scope && context.key == LocalFoodRefundProfile.COMMAND &&
        target.intentOperationId == context.operationId && participants.originalActorId == context.originalActorId &&
        installed.masterOwner.scope == view.scope && installed.clientOwner.scope == view.scope &&
        installed.masterOwner.deviceId == participants.authority.deviceId && installed.clientOwner.deviceId == participants.clientDeviceId)
    if (financialResources.size !in 1..LocalFoodRefundProfile.MAX_FINANCIAL_RESOURCES) return null
    return try {
        val domain = LocalFoodRefundDomainRequirement(1, target, request.amount, request.expectedRefundRevision,
            request.expectedSessionRevision, obligationSourcesDigest,
            participants.authority, LocalFoodJournalOwner.from(installed.masterOwner), LocalFoodJournalOwner.from(installed.clientOwner),
            foodRefundSortedResources(financialResources))
        LocalPhysicalJournalRequirementV1(context.scope, context.originalActorId, context.operationId, context.semanticDigest,
            target.recordingOperationId, target.resolutionOperationId, target.attemptId, LocalFoodRefundProfile.DOMAIN,
            context.key, foodRefundTargetBinding(target), request.masterAllocationId, request.clientAllocationId,
            installed.masterOwner.deviceId, installed.clientOwner.deviceId, installed.masterStorageProfile,
            installed.clientStorageProfile, foodRefundDomainBytes(domain)).also { LocalFoodRefundCashJournalDomain.validateRequirement(it) }
    } catch (_: LocalSalesWireCapacityExceeded) { null }
      catch (_: IllegalArgumentException) { null } // Only the pure bounded canonical constructors are inside this catch.
}

internal fun foodRefundAdmission(requirement: LocalPhysicalJournalRequirementV1,
    action: PhysicalRecordingAction): PhysicalRecordingAdmission = PhysicalRecordingAdmission(action,
    LocalFoodRefundProfile.DOMAIN, requirement.canonicalBytes)

internal fun foodRefundAuthorityResources(requirement: LocalPhysicalJournalRequirementV1): Set<RecoveryResourceKey> {
    val domain = foodRefundDomain(requirement)
    return (setOf(requirement.originalActorId, requirement.attemptId, DomainId(requirement.intentOperationId.value),
        DomainId(requirement.recordingOperationId.value), DomainId(requirement.resolutionOperationId.value),
        requirement.masterAllocationId, requirement.clientAllocationId, domain.authority.assignmentId) +
        listOf(domain.masterOwner, domain.clientOwner).flatMap { listOf(it.installationId, it.deviceId, it.catalogueId, it.keyId) })
        .map(::recordingAuthority).toSet()
}

/** Both genuine immutable source rows, exactly matched to the canonical FOOD/native role pair. */
internal fun requireFoodRefundSourcePair(requirement: LocalPhysicalJournalRequirementV1,
    changes: List<AggregateChange>): Pair<LocalPhysicalJournalSourceProjection, LocalPhysicalJournalSourceProjection> {
    LocalFoodRefundCashJournalDomain.validateRequirement(requirement)
    check(changes.size == 2 && changes.map { it.key }.toSet() == setOf(
        AggregateKey(PhysicalRecordingSourceProfileV1.KIND, requirement.masterAllocationId),
        AggregateKey(PhysicalRecordingSourceProfileV1.KIND, requirement.clientAllocationId)))
    val domain = foodRefundDomain(requirement)
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

internal fun foodRefundFences(requirement: LocalPhysicalJournalRequirementV1): List<AggregateChange> {
    val target = foodRefundDomain(requirement).target
    return listOf(false, true).map { resolution -> ReservedOperations.create(FutureOperationReservation(
        if (resolution) target.resolutionOperationId else target.recordingOperationId, requirement.originalActorId,
        LocalFoodRefundProfile.COMMAND, foodRefundFenceBinding(target, resolution))) }
}

internal fun foodRefundLineageClaims(requirement: LocalPhysicalJournalRequirementV1): List<ClaimChange> = listOf(
    ClaimChange.Acquire(foodRefundOperationClaim(requirement.recordingOperationId), requirement.attemptId),
    ClaimChange.Acquire(foodRefundOperationClaim(requirement.resolutionOperationId), requirement.attemptId),
    ClaimChange.Acquire(foodRefundAllocationClaim(requirement.masterAllocationId), requirement.attemptId),
    ClaimChange.Acquire(foodRefundAllocationClaim(requirement.clientAllocationId), requirement.attemptId),
    ClaimChange.Acquire(foodRefundAttemptClaim(requirement.attemptId), DomainId(requirement.recordingOperationId.value)),
)

internal class FoodRefundStartSources(val slot: LocalFoodRefundSlot,
    val changes: List<AggregateChange>, val claims: List<ClaimChange>)

/** The only Start source path: actual kernel-owned pair in the final REAL transaction. */
internal fun foodRefundStartSources(context: CommandContext,
    requirement: LocalPhysicalJournalRequirementV1): FoodRefundStartSources? {
    val native = context.physicalRecording ?: return null
    val admission = foodRefundAdmission(requirement, PhysicalRecordingAction.START_REFUND)
    if (!native.matches(admission)) return null
    val domain = foodRefundDomain(requirement)
    check(context.scope == requirement.scope && context.originalActorId == requirement.originalActorId &&
        context.operationId == requirement.intentOperationId && context.key == requirement.command &&
        context.semanticDigest == requirement.intentSemanticDigest && context.physicalParticipants?.let {
            it.authority == domain.authority && it.clientDeviceId == requirement.clientDeviceId &&
                it.originalActorId == requirement.originalActorId
        } == true)
    val pair = requireFoodRefundSourcePair(requirement, native.sourceChanges)
    val slot = LocalFoodRefundSlot(1, requirement.scope, requirement.originalActorId, requirement.intentSemanticDigest,
        domain, requirement.masterAllocationId, requirement.clientAllocationId, requirement.masterStorageProfile.name,
        requirement.clientStorageProfile.name, LocalFoodRefundSourceReference.from(pair.first), LocalFoodRefundSourceReference.from(pair.second))
    check(slot.requirement().canonicalBytes == requirement.canonicalBytes)
    return FoodRefundStartSources(slot, native.sourceChanges + foodRefundSlotWrite(slot) + foodRefundFences(requirement),
        foodRefundLineageClaims(requirement))
}

/** FOOD-specific native linkage DATA. Current authorization/audit and SQL lineage are separate. */
public object LocalFoodRefundResolutionDescriptor {
    public fun create(requirement: LocalPhysicalJournalRequirementV1, original: StoredBytes,
        resolverActorId: DomainId, resolution: StoredBytes): StoredBytes {
        val descriptor = foodRefundResolutionDescriptor(requirement, original, resolverActorId, resolution)
        LocalFoodRefundCashJournalDomain.validateResolution(requirement, original, resolverActorId, descriptor, resolution)
        return descriptor
    }
}

internal fun foodRefundResolutionDescriptor(requirement: LocalPhysicalJournalRequirementV1, original: StoredBytes,
    resolverActorId: DomainId, resolution: StoredBytes): StoredBytes = StoredBytes(journalFrame(listOf(
        "sales.food-refund-resolution-linkage/v1", requirement.scope.organizationId.value,
        requirement.scope.companyId.value, requirement.scope.branchId.value, requirement.originalActorId.value,
        requirement.intentOperationId.value, requirement.recordingOperationId.value, sha256(original.copyBytes()).value,
        requirement.attemptId.value, requirement.resolutionOperationId.value, resolverActorId.value,
        sha256(resolution.copyBytes()).value, requirement.targetBinding.value, requirement.intentSemanticDigest.value,
    ).map(::journalText), 4_096))
