package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** Installed catalogue metadata only. Parsing this cannot obtain native custody. */
@Serializable
public data class LocalSupplierCashJournalOwner(public val installationId: DomainId, public val deviceId: DomainId,
    public val catalogueId: DomainId, public val keyId: DomainId) {
    public fun binding(scope: BranchScope, role: LocalPhysicalSpoolRole): LocalPhysicalCatalogueBinding =
        LocalPhysicalCatalogueBinding(scope, installationId, deviceId, catalogueId, role, keyId)
    public companion object {
        public fun from(owner: LocalPhysicalCatalogueBinding): LocalSupplierCashJournalOwner =
            LocalSupplierCashJournalOwner(owner.installationId, owner.deviceId, owner.catalogueId, owner.keyId)
    }
}

@Serializable
public data class LocalSupplierCashDomainData(
    public val version: Int, public val target: LocalSupplierCashTarget, public val source: LocalSupplierCashSource,
    public val amount: Money, public val sessionRevision: Revision, public val drawerRevision: Revision,
    public val custodianRevision: Revision, public val workRevision: Revision, public val reasonCode: String,
    public val basisDigest: PayloadDigest, public val authority: AuthorityIdentity,
    public val masterOwner: LocalSupplierCashJournalOwner, public val clientOwner: LocalSupplierCashJournalOwner,
    public val financialResources: List<RecoveryResourceKey>,
)

/** Pure supplier-specific display projection; it is neither an instruction nor a source grant. */
public class LocalSupplierCashPhysicalProjection private constructor(
    public val requirement: LocalPhysicalJournalRequirementV1, public val domain: LocalSupplierCashDomainData,
) {
    public companion object {
        public fun parse(bytes: StoredBytes): LocalSupplierCashPhysicalProjection {
            val requirement = LocalPhysicalJournalRequirementV1.decode(bytes)
            LocalSupplierCashJournalDomain.validateRequirement(requirement)
            return LocalSupplierCashPhysicalProjection(requirement, supplierCashDomain(requirement))
        }
    }
}

/** Concrete supplier codec selected explicitly by the native registry, never by a retail alias. */
public object LocalSupplierCashJournalDomain : LocalPhysicalJournalDomain {
    override val command: CommandKey get() = LocalSupplierCashProfile.COMMAND
    override val domainProfile: String get() = LocalSupplierCashProfile.DOMAIN
    override val startKind: LocalPhysicalJournalStartKind get() = LocalPhysicalJournalStartKind.PAYMENT
    override fun validateRequirement(requirement: LocalPhysicalJournalRequirementV1) {
        val domain = supplierCashDomain(requirement); val target = domain.target
        check(domain.version == 1 && requirement.command == command && requirement.domainProfile == domainProfile &&
            requirement.targetBinding == supplierCashTargetBinding(target) && requirement.intentOperationId == target.intentOperationId &&
            requirement.recordingOperationId == target.recordingOperationId && requirement.resolutionOperationId == target.resolutionOperationId &&
            requirement.attemptId == target.attemptId && domain.source.supplierId == target.supplierId &&
            (domain.source as? LocalSupplierCashSource.Invoice)?.invoiceId == target.invoiceId &&
            domain.amount.currency == Currency.EGP && domain.amount.minor > 0L && domain.sessionRevision.value > 0L &&
            domain.masterOwner.deviceId == requirement.masterDeviceId && domain.clientOwner.deviceId == requirement.clientDeviceId &&
            domain.authority.deviceId == requirement.masterDeviceId && domain.financialResources.size in 1..LocalSupplierCashProfile.MAX_FINANCIAL_RESOURCES &&
            domain.financialResources == supplierCashResources(domain.financialResources.toSet()) &&
            domain.financialResources.containsAll(setOf(supplierResource(target.supplierId), supplierResource(target.settlementId),
                supplierResource(target.attemptId), supplierCashResource(target.sessionId))) &&
            supplierCashDomainBytes(domain) == requirement.canonicalDomainRequirement)
        localReason(domain.reasonCode)
    }
    override fun classifyOriginal(requirement: LocalPhysicalJournalRequirementV1, body: StoredBytes): LocalPhysicalOriginalKind {
        validateRequirement(requirement)
        check(LocalSupplierCashCodec.normalize(body) == body)
        val value = LocalSupplierCashCodec.decode(body) as? LocalSupplierCashRequest.RecordOriginal
            ?: error("supplier.cash.original_required")
        check(value.target == supplierCashDomain(requirement).target)
        return if (value.outcome is LocalSupplierCashOutcome.Unknown) LocalPhysicalOriginalKind.UNKNOWN else LocalPhysicalOriginalKind.KNOWN
    }
    override fun validateResolution(requirement: LocalPhysicalJournalRequirementV1, original: StoredBytes,
        resolverActorId: DomainId, resolverAuthorization: StoredBytes, body: StoredBytes) {
        check(classifyOriginal(requirement, original) == LocalPhysicalOriginalKind.UNKNOWN &&
            resolverActorId == requirement.originalActorId && LocalSupplierCashCodec.normalize(body) == body)
        val value = LocalSupplierCashCodec.decode(body) as? LocalSupplierCashRequest.RecordResolution
            ?: error("supplier.cash.resolution_required")
        check(value.target == supplierCashDomain(requirement).target && value.resolverActorId == resolverActorId &&
            value.originalBodyDigest == sha256(original.copyBytes()) && value.outcome !is LocalSupplierCashOutcome.Unknown &&
            resolverAuthorization == supplierCashResolutionDescriptor(requirement, original, resolverActorId, body))
    }
}

@Serializable
public data class LocalSupplierCashSourceReference(public val allocationId: DomainId, public val payloadDigest: PayloadDigest,
    public val headerDigest: PayloadDigest, public val allocationDigest: PayloadDigest, public val slotIndex: Int) {
    public companion object {
        public fun from(value: LocalPhysicalJournalSourceProjection): LocalSupplierCashSourceReference =
            LocalSupplierCashSourceReference(value.allocationId, sha256(value.sourceCommitment.copyBytes()),
                value.headerDigest, value.allocationDigest, value.slotIndex)
    }
}

/** Immutable original source pair and full canonical requirement. Never consumed or recycled. */
@Serializable
public data class LocalSupplierCashSlot(
    public val version: Int, public val scope: BranchScope, public val originalActorId: DomainId,
    public val intentSemanticDigest: PayloadDigest, public val domain: LocalSupplierCashDomainData,
    public val masterAllocationId: DomainId, public val clientAllocationId: DomainId,
    public val masterStorageProfile: LocalPhysicalSpoolStorageProfileV1, public val clientStorageProfile: LocalPhysicalSpoolStorageProfileV1,
    public val masterSource: LocalSupplierCashSourceReference, public val clientSource: LocalSupplierCashSourceReference,
) {
    public val target: LocalSupplierCashTarget get() = domain.target
    public fun requirement(): LocalPhysicalJournalRequirementV1 = LocalPhysicalJournalRequirementV1(scope, originalActorId,
        target.intentOperationId, intentSemanticDigest, target.recordingOperationId, target.resolutionOperationId, target.attemptId,
        LocalSupplierCashProfile.DOMAIN, LocalSupplierCashProfile.COMMAND, supplierCashTargetBinding(target), masterAllocationId,
        clientAllocationId, domain.masterOwner.deviceId, domain.clientOwner.deviceId, masterStorageProfile, clientStorageProfile,
        supplierCashDomainBytes(domain)).also { LocalSupplierCashJournalDomain.validateRequirement(it) }
}

public class LocalSupplierCashStartSources internal constructor(public val slot: LocalSupplierCashSlot,
    public val changes: List<AggregateChange>, public val claims: List<ClaimChange>)

public object LocalSupplierCashJournal {
    public suspend fun requirement(view: CommandReadView, context: CommandContext,
        owners: TransactionRetailJournalParticipantsSource, request: LocalSupplierCashRequest.StartCash,
        basisDigest: PayloadDigest, financialResources: Set<RecoveryResourceKey>): LocalPhysicalJournalRequirementV1? {
        val participants = context.physicalParticipants ?: return null
        val installed = owners.current(view, participants) ?: return null
        check(context.scope == view.scope && context.key == LocalSupplierCashProfile.COMMAND &&
            context.originalActorId == participants.originalActorId && installed.masterOwner.scope == view.scope &&
            installed.clientOwner.scope == view.scope && installed.masterOwner.deviceId == participants.authority.deviceId &&
            installed.clientOwner.deviceId == participants.clientDeviceId)
        if (financialResources.size !in 1..LocalSupplierCashProfile.MAX_FINANCIAL_RESOURCES) return null
        val domain = LocalSupplierCashDomainData(1, request.target(context.operationId), request.source, request.amount,
            request.expectedSessionRevision, request.expectedDrawerRevision, request.expectedCustodianRevision,
            request.expectedWorkRevision, request.reasonCode, basisDigest, participants.authority,
            LocalSupplierCashJournalOwner.from(installed.masterOwner), LocalSupplierCashJournalOwner.from(installed.clientOwner),
            supplierCashResources(financialResources))
        return try {
            LocalPhysicalJournalRequirementV1(context.scope, context.originalActorId, context.operationId, context.semanticDigest,
                request.recordingOperationId, request.resolutionOperationId, request.attemptId, LocalSupplierCashProfile.DOMAIN,
                LocalSupplierCashProfile.COMMAND, supplierCashTargetBinding(domain.target), request.masterAllocationId,
                request.clientAllocationId, installed.masterOwner.deviceId, installed.clientOwner.deviceId,
                installed.masterStorageProfile, installed.clientStorageProfile, supplierCashDomainBytes(domain))
                .also { LocalSupplierCashJournalDomain.validateRequirement(it) }
        } catch (_: LocalSalesWireCapacityExceeded) { null }
          catch (_: IllegalArgumentException) { null }
    }

    public fun admission(requirement: LocalPhysicalJournalRequirementV1, action: PhysicalRecordingAction): PhysicalRecordingAdmission =
        PhysicalRecordingAdmission(action, LocalSupplierCashProfile.DOMAIN, requirement.canonicalBytes)
    public fun fenceBinding(target: LocalSupplierCashTarget, resolution: Boolean = false): PayloadDigest = supplierCashFenceBinding(target, resolution)
    public fun slotKey(operationId: OperationId): AggregateKey = AggregateKey(LocalSupplierCashProfile.SLOT, DomainId(operationId.value))
    public fun rawKey(operationId: OperationId): AggregateKey = AggregateKey(LocalSupplierCashProfile.ORIGINAL, DomainId(operationId.value))
    public fun resolutionKey(operationId: OperationId): AggregateKey = AggregateKey(LocalSupplierCashProfile.RESOLUTION, DomainId(operationId.value))
    public fun applicationKey(operationId: OperationId): AggregateKey = AggregateKey(LocalSupplierCashProfile.APPLICATION, DomainId(operationId.value))
    public fun slotWrite(slot: LocalSupplierCashSlot): AggregateChange = AggregateChange(slotKey(slot.target.recordingOperationId), null,
        LocalSupplierCashProfile.schema(LocalSupplierCashProfile.SLOT), localSalesEncode(slot, LocalSupplierCashSlot.serializer(), LocalSupplierCashProfile.MAX_SLOT_BYTES))
    public fun decodeSlot(bytes: StoredBytes): LocalSupplierCashSlot = localSalesDecode(bytes,
        LocalSupplierCashSlot.serializer(), LocalSupplierCashProfile.MAX_SLOT_BYTES).also {
        check(it.version == 1 && localSalesEncode(it, LocalSupplierCashSlot.serializer(), LocalSupplierCashProfile.MAX_SLOT_BYTES) == bytes)
        it.requirement()
    }

    /** The final kernel-owned native pair is the sole source of Start effects. */
    public fun startSources(context: CommandContext, requirement: LocalPhysicalJournalRequirementV1): LocalSupplierCashStartSources? {
        val native = context.physicalRecording ?: return null
        if (!native.matches(admission(requirement, PhysicalRecordingAction.START_PAYMENT))) return null
        val domain = supplierCashDomain(requirement)
        check(context.scope == requirement.scope && context.originalActorId == requirement.originalActorId &&
            context.operationId == requirement.intentOperationId && context.key == requirement.command &&
            context.semanticDigest == requirement.intentSemanticDigest && context.physicalParticipants?.let {
                it.authority == domain.authority && it.clientDeviceId == requirement.clientDeviceId && it.originalActorId == requirement.originalActorId
            } == true)
        val pair = sourcePair(requirement, native.sourceChanges)
        val slot = LocalSupplierCashSlot(1, requirement.scope, requirement.originalActorId, requirement.intentSemanticDigest, domain,
            requirement.masterAllocationId, requirement.clientAllocationId, requirement.masterStorageProfile, requirement.clientStorageProfile,
            LocalSupplierCashSourceReference.from(pair.first), LocalSupplierCashSourceReference.from(pair.second))
        return LocalSupplierCashStartSources(slot, native.sourceChanges + slotWrite(slot) + fences(requirement), lineageClaims(requirement))
    }
    public fun sourcePair(requirement: LocalPhysicalJournalRequirementV1, changes: List<AggregateChange>): Pair<LocalPhysicalJournalSourceProjection, LocalPhysicalJournalSourceProjection> {
        LocalSupplierCashJournalDomain.validateRequirement(requirement)
        check(changes.size == 2 && changes.map { it.key }.toSet() == setOf(
            AggregateKey(PhysicalRecordingSourceProfileV1.KIND, requirement.masterAllocationId),
            AggregateKey(PhysicalRecordingSourceProfileV1.KIND, requirement.clientAllocationId)))
        val domain = supplierCashDomain(requirement)
        val parsed = changes.map { LocalPhysicalJournalSourceProjection.decode(it) }
        val master = parsed.single { it.owner.role == LocalPhysicalSpoolRole.MASTER }; val client = parsed.single { it.owner.role == LocalPhysicalSpoolRole.CLIENT }
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
    public fun fences(requirement: LocalPhysicalJournalRequirementV1): List<AggregateChange> = listOf(false, true).map { resolution ->
        ReservedOperations.create(FutureOperationReservation(if (resolution) requirement.resolutionOperationId else requirement.recordingOperationId,
            requirement.originalActorId, requirement.command, supplierCashFenceBinding(supplierCashDomain(requirement).target, resolution))) }
    public fun lineageClaims(requirement: LocalPhysicalJournalRequirementV1): List<ClaimChange> = listOf(
        ClaimChange.Acquire(ClaimKey(LocalSupplierCashProfile.OPERATION_CLAIM, requirement.recordingOperationId.value), requirement.attemptId),
        ClaimChange.Acquire(ClaimKey(LocalSupplierCashProfile.OPERATION_CLAIM, requirement.resolutionOperationId.value), requirement.attemptId),
        ClaimChange.Acquire(ClaimKey(LocalSupplierCashProfile.ALLOCATION_CLAIM, requirement.masterAllocationId.value), requirement.attemptId),
        ClaimChange.Acquire(ClaimKey(LocalSupplierCashProfile.ALLOCATION_CLAIM, requirement.clientAllocationId.value), requirement.attemptId),
        ClaimChange.Acquire(ClaimKey(LocalSupplierCashProfile.ATTEMPT_CLAIM, requirement.attemptId.value), DomainId(requirement.recordingOperationId.value)),
    )
    public fun vacantKeys(requirement: LocalPhysicalJournalRequirementV1): List<AggregateKey> = listOf(
        slotKey(requirement.recordingOperationId), rawKey(requirement.recordingOperationId), resolutionKey(requirement.resolutionOperationId),
        LocalSupplierCashStorage.movementKey(requirement.attemptId)) +
        listOf(requirement.recordingOperationId, requirement.resolutionOperationId).flatMap { listOf(ReservedOperations.key(it), applicationKey(it)) } +
        listOf(requirement.masterAllocationId, requirement.clientAllocationId).map { AggregateKey(PhysicalRecordingSourceProfileV1.KIND, it) }
    public suspend fun vacant(view: CommandReadView, requirement: LocalPhysicalJournalRequirementV1): Boolean {
        for (key in vacantKeys(requirement)) if (view.aggregate(key) != null) return false
        for (claim in lineageClaims(requirement)) if (view.claim(claim.key) != null) return false
        for (operation in listOf(requirement.recordingOperationId, requirement.resolutionOperationId)) if (view.retainedCommand(operation) != null) return false
        return LocalSupplierCashStorage.requireUnusedSource(view, requirement.attemptId)
    }
    public fun authorityResources(requirement: LocalPhysicalJournalRequirementV1): Set<RecoveryResourceKey> {
        val domain = supplierCashDomain(requirement)
        return (setOf(requirement.originalActorId, requirement.attemptId, DomainId(requirement.intentOperationId.value),
            DomainId(requirement.recordingOperationId.value), DomainId(requirement.resolutionOperationId.value),
            requirement.masterAllocationId, requirement.clientAllocationId, domain.authority.assignmentId) +
            listOf(domain.masterOwner, domain.clientOwner).flatMap { listOf(it.installationId, it.deviceId, it.catalogueId, it.keyId) })
            .map { RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it) }.toSet()
    }
}

public object LocalSupplierCashResolutionDescriptor {
    public fun create(requirement: LocalPhysicalJournalRequirementV1, original: StoredBytes,
        resolverActorId: DomainId, resolution: StoredBytes): StoredBytes = supplierCashResolutionDescriptor(requirement, original, resolverActorId, resolution)
        .also { LocalSupplierCashJournalDomain.validateResolution(requirement, original, resolverActorId, it, resolution) }
}
internal fun supplierCashDomainBytes(value: LocalSupplierCashDomainData): StoredBytes =
    localSalesEncode(value, LocalSupplierCashDomainData.serializer(), LocalSupplierCashProfile.MAX_DOMAIN_BYTES)
internal fun supplierCashDomain(requirement: LocalPhysicalJournalRequirementV1): LocalSupplierCashDomainData =
    localSalesDecode(requirement.canonicalDomainRequirement, LocalSupplierCashDomainData.serializer(), LocalSupplierCashProfile.MAX_DOMAIN_BYTES)
internal fun supplierCashResources(values: Set<RecoveryResourceKey>): List<RecoveryResourceKey> =
    values.sortedWith(compareBy<RecoveryResourceKey> { it.kind.name }.thenBy { it.resourceId.value })
internal fun supplierResource(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, id)
internal fun supplierCashResource(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, id)
internal fun supplierCashResolutionDescriptor(requirement: LocalPhysicalJournalRequirementV1, original: StoredBytes,
    actorId: DomainId, resolution: StoredBytes): StoredBytes = StoredBytes(journalFrame(listOf(
    "purchasing.supplier-cash-resolution-linkage/v1", requirement.scope.organizationId.value, requirement.scope.companyId.value,
    requirement.scope.branchId.value, requirement.originalActorId.value, requirement.intentOperationId.value, requirement.recordingOperationId.value,
    sha256(original.copyBytes()).value, requirement.attemptId.value, requirement.resolutionOperationId.value, actorId.value,
    sha256(resolution.copyBytes()).value, requirement.targetBinding.value, requirement.intentSemanticDigest.value).map(::journalText), 4_096))
