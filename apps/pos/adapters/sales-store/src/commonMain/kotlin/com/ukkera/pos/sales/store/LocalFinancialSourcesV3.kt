package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.FutureOperationReservation
import com.ukkera.pos.database.PhysicalRecordingAction
import com.ukkera.pos.database.ReservedOperations
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.UtcInstant

internal class LocalRetailJournalStartSourcesV3(
    val slot: LocalRetailJournalSlotV3, val changes: List<AggregateChange>, val claims: List<ClaimChange>,
)

/** Denial probes only. Kernel complete-history future-ID fencing remains mandatory at commit. */
internal suspend fun retailJournalVacantV3(view: CommandReadView, requirement: LocalPhysicalJournalRequirementV1): Boolean {
    val target = retailDomainRequirementV3(requirement).target
    val operations = listOf(target.recordingOperationId, target.resolutionOperationId)
    for (operation in operations) {
        if (view.retainedCommand(operation) != null || view.aggregate(ReservedOperations.key(operation)) != null ||
            view.claim(retailJournalOperationClaimV3(operation)) != null || view.aggregate(retailJournalApplicationKeyV3(operation)) != null) return false
    }
    if (view.aggregate(retailJournalSlotKeyV3(target.recordingOperationId)) != null ||
        view.aggregate(retailJournalFactKeyV3(LocalJournalFactPhaseV3.ORIGINAL, target.recordingOperationId)) != null ||
        view.aggregate(retailJournalFactKeyV3(LocalJournalFactPhaseV3.RESOLUTION, target.resolutionOperationId)) != null ||
        view.claim(retailJournalAttemptClaimV3(target.attemptId)) != null) return false
    for (allocation in listOf(requirement.masterAllocationId, requirement.clientAllocationId)) {
        if (view.aggregate(AggregateKey(LocalPhysicalJournalFormatV1.SOURCE_KIND, allocation)) != null ||
            view.claim(retailJournalAllocationClaimV3(allocation)) != null) return false
    }
    return true
}

/**
 * The ONLY start-source path. There is no capacity interface, fabricated backing, or preexisting
 * SQL receipt reader. All five inserts are derived from this kernel-owned transaction's EXACT
 * authentic native pair after rederiving the full native-free requirement in the real writer.
 */
internal fun retailJournalStartSourcesV3(context: CommandContext,
    requirement: LocalPhysicalJournalRequirementV1): LocalRetailJournalStartSourcesV3? {
    val projection = context.physicalRecording ?: return null
    val domain = retailDomainRequirementV3(requirement)
    val action = if (domain.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION)
        PhysicalRecordingAction.START_PAYMENT else PhysicalRecordingAction.START_REFUND
    if (!projection.matches(retailAdmission(requirement, action))) return null
    check(context.scope == requirement.scope && context.originalActorId == requirement.originalActorId &&
        context.operationId == requirement.intentOperationId && context.key == requirement.command &&
        context.semanticDigest == requirement.intentSemanticDigest && context.physicalParticipants?.let {
            it.authority == domain.authority && it.clientDeviceId == requirement.clientDeviceId
        } == true) { "sales.journal.writer_binding" }
    val changes = projection.sourceChanges
    val pair = requireRetailSourcePairV3(requirement, changes)
    val slot = LocalRetailJournalSlotV3(3, LocalSalesScope.from(requirement.scope), requirement.originalActorId,
        requirement.intentSemanticDigest.value, domain, requirement.masterAllocationId, requirement.clientAllocationId,
        requirement.masterStorageProfile.name, requirement.clientStorageProfile.name,
        retailSourceReferenceV3(pair.first), retailSourceReferenceV3(pair.second))
    check(slot.requirement().canonicalBytes == requirement.canonicalBytes)
    return LocalRetailJournalStartSourcesV3(slot, changes + retailSlotWriteV3(slot) + retailFutureFencesV3(requirement),
        retailLineageClaimsV3(requirement))
}

internal fun requireRetailSourcePairV3(requirement: LocalPhysicalJournalRequirementV1,
    changes: List<AggregateChange>): Pair<LocalPhysicalJournalSourceProjection, LocalPhysicalJournalSourceProjection> {
    retailJournalDomain(retailDomainRequirementV3(requirement).target.kind).validateRequirement(requirement)
    check(changes.size == 2 && changes.map { it.key }.toSet() == setOf(
        AggregateKey(LocalPhysicalJournalFormatV1.SOURCE_KIND, requirement.masterAllocationId),
        AggregateKey(LocalPhysicalJournalFormatV1.SOURCE_KIND, requirement.clientAllocationId))) { "sales.journal.source_set" }
    val sources = changes.map { LocalPhysicalJournalSourceProjection.decode(it) }
    val master = sources.single { it.owner.role == LocalPhysicalSpoolRole.MASTER }
    val client = sources.single { it.owner.role == LocalPhysicalSpoolRole.CLIENT }
    val domain = retailDomainRequirementV3(requirement)
    check(master.requirement.canonicalBytes == requirement.canonicalBytes && client.requirement.canonicalBytes == requirement.canonicalBytes &&
        master.owner == domain.masterOwner.binding(requirement.scope, LocalPhysicalSpoolRole.MASTER) &&
        client.owner == domain.clientOwner.binding(requirement.scope, LocalPhysicalSpoolRole.CLIENT) &&
        master.allocationId == requirement.masterAllocationId && client.allocationId == requirement.clientAllocationId &&
        master.nativeIdentity.profile == requirement.masterStorageProfile && client.nativeIdentity.profile == requirement.clientStorageProfile &&
        changes.single { it.key.id == master.allocationId }.payload == master.sourceCommitment &&
        changes.single { it.key.id == client.allocationId }.payload == client.sourceCommitment) { "sales.journal.source_binding" }
    // Same device is supported, but one native inode/file may not satisfy both protected roles.
    check(requirement.masterDeviceId != requirement.clientDeviceId || master.nativeIdentity.fileIdentity != client.nativeIdentity.fileIdentity) {
        "sales.journal.role_alias"
    }
    return master to client
}

internal fun retailSourceReferenceV3(source: LocalPhysicalJournalSourceProjection): LocalRetailSourceReferenceV3 =
    LocalRetailSourceReferenceV3(source.allocationId, retailJournalDigestV3(source.sourceCommitment).value,
        source.allocationDigest.value, source.headerDigest.value, source.slotIndex)

internal fun retailFutureFencesV3(requirement: LocalPhysicalJournalRequirementV1): List<AggregateChange> =
    LocalJournalFactPhaseV3.entries.map { phase ->
        val target = retailDomainRequirementV3(requirement).target
        ReservedOperations.create(FutureOperationReservation(target.factOperation(phase), requirement.originalActorId,
            requirement.command, retailJournalFenceBindingV3(target, phase)))
    }

/** Permanent lineage. SQL application never releases one to permit reuse of storage or an attempt. */
internal fun retailLineageClaimsV3(requirement: LocalPhysicalJournalRequirementV1): List<ClaimChange> = listOf(
    ClaimChange.Acquire(retailJournalOperationClaimV3(requirement.recordingOperationId), requirement.attemptId),
    ClaimChange.Acquire(retailJournalOperationClaimV3(requirement.resolutionOperationId), requirement.attemptId),
    ClaimChange.Acquire(retailJournalAllocationClaimV3(requirement.masterAllocationId), requirement.attemptId),
    ClaimChange.Acquire(retailJournalAllocationClaimV3(requirement.clientAllocationId), requirement.attemptId),
    ClaimChange.Acquire(retailJournalAttemptClaimV3(requirement.attemptId), DomainId(requirement.recordingOperationId.value)),
)

/** Integrity of a complete proposal shape, never native evidence or authority on its own. */
internal fun retailEffectsDigestV3(changes: List<AggregateChange>, claims: List<ClaimChange>, deadline: UtcInstant?): PayloadDigest {
    val rows = changes.filter { it.key.kind != LocalStockStoreProfile.POSTING }
    check(rows.map { it.key }.distinct().size == rows.size && claims.map { it.key }.distinct().size == claims.size)
    val rowBytes = rows.sortedWith(compareBy<AggregateChange> { it.key.kind }.thenBy { it.key.id.value }).map { row ->
        journalFrame(listOf(row.key.kind, row.key.id.value, row.expectedRevision?.value?.toString() ?: "insert",
            row.schema, row.payload.size.toString(), retailJournalDigestV3(row.payload).value).map(::journalText))
    }
    val claimBytes = claims.sortedWith(compareBy<ClaimChange> { it.key.namespace }.thenBy { it.key.key }).map { claim ->
        journalFrame(listOf(if (claim is ClaimChange.Acquire) "acquire" else "release", claim.key.namespace,
            claim.key.key, claim.ownerId.value).map(::journalText))
    }
    // Stock may have >64 rows. Frame each member separately, then hash the ordered bounded list.
    val bytes = listOf(journalText("sales.retail-complete-effects/v3"), journalText(deadline?.canonicalString() ?: "-"),
        journalText(rowBytes.size.toString())) + rowBytes + listOf(journalText(claimBytes.size.toString())) + claimBytes
    val size = bytes.sumOf { it.size.toLong() + 4L }
    check(size <= 131_072)
    val framed = ByteArray(size.toInt())
    var at = 0
    for (part in bytes) { journalPutInt(framed, at, part.size); part.copyInto(framed, at + 4); at += 4 + part.size }
    return sha256(framed)
}

internal fun sameRetailChangeV3(first: AggregateChange, second: AggregateChange): Boolean = first.key == second.key &&
    first.expectedRevision == second.expectedRevision && first.schema == second.schema && first.payload == second.payload

internal fun requireRetailExactChangesV3(actual: List<AggregateChange>, expected: List<AggregateChange>) {
    check(actual.size == expected.size && actual.map { it.key }.distinct().size == actual.size &&
        expected.all { wanted -> actual.singleOrNull { it.key == wanted.key }?.let { sameRetailChangeV3(it, wanted) } == true }) {
        "sales.journal.exact_changes"
    }
}

internal fun retailRequirementFromAdmissionV3(body: StoredBytes): LocalPhysicalJournalRequirementV1 =
    journalReadRequirement(body.copyBytes()).also { check(it.canonicalBytes == body) }
