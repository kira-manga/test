package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Revision

/** Concrete FOOD interpreter registered ONLY beside the SAME actual P4/native owner. */
public class LocalFoodRefundNativeOriginalImport(private val stock: LocalStockRepository) : NativeOriginalImportInterpreter {
    override val domain: NativeOriginalDomain get() = NativeOriginalDomain.FOOD_REFUND_V1

    override fun project(native: NativeOriginalRetained): NativeOriginalProjection {
        val requirement = LocalFoodRefundPhysicalProjection.parse(native.canonicalRequirement).requirement
        check(native.command == domain.command && native.domainProfile == domain.domainProfile && requirement.command == domain.command &&
            requirement.domainProfile == domain.domainProfile)
        LocalFoodRefundCashJournalDomain.classifyOriginal(requirement, native.originalBody)
        val pair = requireFoodRefundSourcePair(requirement, native.sourceRows)
        fun source(value: LocalPhysicalJournalSourceProjection): NativeOriginalJournalProvenanceV1.Source =
            NativeOriginalJournalProvenanceV1.Source(
                if (value.owner.role == LocalPhysicalSpoolRole.MASTER) NativeOriginalJournalProvenanceV1.SourceRole.MASTER
                else NativeOriginalJournalProvenanceV1.SourceRole.CLIENT,
                value.owner.installationId, value.owner.deviceId, value.owner.catalogueId, value.owner.keyId, value.allocationId,
                value.slotIndex, sha256(value.sourceCommitment.copyBytes()), value.headerDigest, value.allocationDigest,
                sha256(journalNativeBytes(value.nativeIdentity)))
        return NativeOriginalProjection(domain, requirement.scope, requirement.originalActorId, requirement.intentOperationId,
            requirement.intentSemanticDigest, requirement.recordingOperationId, requirement.resolutionOperationId,
            foodRefundFenceBinding(foodRefundDomain(requirement).target), source(pair.first), source(pair.second),
            foodRefundDomain(requirement).financialResources.toSet() + foodRefundAuthorityResources(requirement))
    }

    override suspend fun prepare(borrowed: CommandReadView, context: NativeOriginalImportContext): NativeOriginalImportPlan? {
        val view = FoodReadView(commerceLoan(borrowed, stock))
        val native = context.native
        val projection = project(native)
        if (view.scope != projection.scope) return null
        val requirement = LocalFoodRefundPhysicalProjection.parse(native.canonicalRequirement).requirement
        val request = LocalFoodRefundCodec.decode(native.originalBody) as? LocalFoodRefundRequest.RecordOriginal ?: return null
        val slot = readFoodRefundSlot(view, request.target.recordingOperationId, stock)
        if (slot.value.target != request.target || slot.value.requirement().canonicalBytes != requirement.canonicalBytes) return null
        val start = context.historicalStart
        check(start.nativeOriginalProvenance == null && LocalFoodRefundProducer.requireStart(start).slot == slot.value)
        val actual = view.retainedCommand(start.operationId) ?: return null
        requireSameFoodRefundOriginal(actual, start)
        requireFoodRefundOriginalCustody(view, start, stock)
        val certificate = requireFoodRefundStartCertificate(native.certificate, start, requirement)
        val pair = requireFoodRefundSourcePair(requirement, native.sourceRows)
        check(slot.value.masterSource == LocalFoodRefundSourceReference.from(pair.first) && slot.value.clientSource == LocalFoodRefundSourceReference.from(pair.second))
        for (source in native.sourceRows) {
            val row = view.aggregate(source.key) ?: return null
            check(row.revision == Revision(1) && row.schema == source.schema && row.payload == source.payload && row.digest == sha256(source.payload.copyBytes()))
            requireFoodRefundRetainedRow(start, row)
            check(certificate.sourceStamps.singleOrNull { it.key == row.key }?.let {
                it.schema == row.schema && it.generation == row.revision && it.payloadDigest == row.digest
            } == true)
        }
        val operation = projection.recordingOperationId
        // Core recognition runs BEFORE this insert-only materializer. Partial rows remain
        // held, and an advanced application is NEVER replaced with initial pending.
        if (view.aggregate(foodRefundRawKey(operation)) != null || view.aggregate(foodRefundApplicationKey(operation)) != null) return null
        val raw = LocalFoodRefundRaw(1, view.scope, slot.value.target, requirement.originalActorId, requirement.originalActorId,
            native.originalBody.copyBytes().decodeToString(throwOnInvalidSequence = true), sha256(native.originalBody.copyBytes()),
            semanticDigest(view.scope, requirement.originalActorId, requirement.command, native.originalBody), slot.stored.digest, context.recordedAt)
        return NativeOriginalImportPlan(domain, operation, requirement.originalActorId, requirement.command, native.originalBody,
            raw.semanticDigest, projection.reservedBinding, foodRefundRawWrite(raw), foodRefundApplicationWrite(foodRefundInitialApplication(raw), null),
            LocalFoodRefundCodec.result(LocalFoodRefundResult.RawRetained(slot.value.target)))
    }

    override suspend fun verifyExisting(borrowed: CommandReadView, context: NativeOriginalImportContext,
        original: RetainedCommandEvidence): Boolean {
        val view = FoodReadView(commerceLoan(borrowed, stock))
        val projection = project(context.native)
        if (view.scope != projection.scope || original.scope != view.scope || original.command != domain.command ||
            original.operationId != projection.recordingOperationId || original.originalActorId != projection.originalActorId ||
            original.normalized != context.native.originalBody) return false
        val current = view.retainedCommand(original.operationId) ?: return false
        requireSameFoodRefundOriginal(current, original)
        val cut = readFoodRefundRaw(view, original.operationId, stock)
        val requirement = cut.slot.value.requirement()
        if (requirement.canonicalBytes != context.native.canonicalRequirement || cut.raw.value.body() != context.native.originalBody) return false
        requireSameFoodRefundOriginal(cut.startOriginal, context.historicalStart)
        requireFoodRefundStartCertificate(context.native.certificate, context.historicalStart, requirement)
        requireFoodRefundSourcePair(requirement, context.native.sourceRows)
        check(LocalFoodRefundProducer.requireRaw(original, context.historicalStart).raw == cut.raw.value)
        if (original.nativeOriginalProvenance != null) {
            requireFoodRefundNativeOriginalEvidence(original, context.historicalStart, cut.raw.value, cut.slot.value)
            check(original.nativeOriginalProvenance?.nativeOriginal?.certificateDigest == sha256(context.native.certificate.copyBytes()))
        }
        // readFoodRefundRaw validated initial OR advanced exact accounting lineage. No
        // rematerialization, cash replay, current-employee grant or native capacity is returned.
        return true
    }
}

internal fun requireFoodRefundStartCertificate(bytes: StoredBytes, start: RetainedCommandEvidence,
    requirement: LocalPhysicalJournalRequirementV1): PhysicalCommittedStartProjectionV1 = PhysicalCommittedStartProjectionV1.parse(bytes).also { certificate ->
    check(start.nativeOriginalProvenance == null && certificate.scope == start.scope && certificate.originalActorId == start.originalActorId &&
        certificate.intentOperationId == start.operationId && certificate.command == start.command && certificate.intentSemanticDigest == start.semanticDigest &&
        certificate.assignmentId == start.assignmentId && certificate.epoch == start.authorityEpoch && certificate.sequence == start.sequence &&
        certificate.journalHash == start.journalHash && certificate.resultDigest == start.resultHash && certificate.recordedAt == start.recordedAt &&
        certificate.requirementBytes == requirement.canonicalBytes && certificate.domainProfile == requirement.domainProfile &&
        certificate.masterDeviceId == requirement.masterDeviceId && certificate.sourceStamps.size == 2)
    val sources = start.aggregateWrites.filter { it.key.kind == PhysicalRecordingSourceProfileV1.KIND }
    check(sources.size == 2 && certificate.sourceStamps.map { it.key }.toSet() == sources.map { it.key }.toSet())
    for (source in sources) check(certificate.sourceStamps.single { it.key == source.key }.let {
        it.schema == source.schema && it.generation == source.storageGeneration && it.payloadDigest == source.payloadDigest
    })
}

/** Complete provenance DATA match to actual immutable FOOD Start, never current authority. */
internal fun requireFoodRefundNativeOriginalEvidence(origin: RetainedCommandEvidence, start: RetainedCommandEvidence,
    raw: LocalFoodRefundRaw, slot: LocalFoodRefundSlot) {
    val provenance = checkNotNull(origin.nativeOriginalProvenance)
    val requirement = slot.requirement()
    val historical = provenance.historicalStart
    check(start.nativeOriginalProvenance == null && raw.originalActorId == requirement.originalActorId && raw.recordingActorId == requirement.originalActorId &&
        provenance.scope == origin.scope && provenance.scope == start.scope && historical.originalActorId == start.originalActorId &&
        historical.intentOperationId == start.operationId && historical.command == start.command && historical.intentSemanticDigest == start.semanticDigest &&
        historical.authenticationId == start.authenticationId && historical.policyRevision == start.policyRevision && historical.assignmentId == start.assignmentId &&
        historical.epoch == start.authorityEpoch && historical.sequence == start.sequence && historical.journalHash == start.journalHash &&
        historical.resultDigest == start.resultHash && historical.recordedAt == start.recordedAt && historical.masterDeviceId == requirement.masterDeviceId &&
        provenance.nativeOriginal.recordingOperationId == raw.target.recordingOperationId && provenance.nativeOriginal.domainProfile == requirement.domainProfile &&
        provenance.nativeOriginal.requirementDigest == sha256(requirement.canonicalBytes.copyBytes()) &&
        provenance.nativeOriginal.originalBodyDigest == raw.bodyDigest && provenance.nativeOriginal.originalSemanticDigest == raw.semanticDigest &&
        provenance.currentWriter.assignmentId == origin.assignmentId && provenance.currentWriter.epoch == origin.authorityEpoch &&
        provenance.runtimeParent.sequence.value > 0)
    val pair = requireFoodRefundSourcePair(requirement, start.aggregateWrites.filter { it.key.kind == PhysicalRecordingSourceProfileV1.KIND }.map(::foodRefundInsert))
    val literal = listOf(pair.first, pair.second)
    val retained = listOf(provenance.nativeOriginal.masterSource, provenance.nativeOriginal.clientSource)
    check(retained[0].allocationId == requirement.masterAllocationId && retained[1].allocationId == requirement.clientAllocationId)
    for ((actual, candidate) in literal.zip(retained)) check(candidate.role == (if (actual.owner.role == LocalPhysicalSpoolRole.MASTER)
            NativeOriginalJournalProvenanceV1.SourceRole.MASTER else NativeOriginalJournalProvenanceV1.SourceRole.CLIENT) &&
        candidate.installationId == actual.owner.installationId && candidate.deviceId == actual.owner.deviceId &&
        candidate.catalogueId == actual.owner.catalogueId && candidate.keyId == actual.owner.keyId && candidate.allocationId == actual.allocationId &&
        candidate.nativeSlotIndex == actual.slotIndex && candidate.sourcePayloadDigest == sha256(actual.sourceCommitment.copyBytes()) &&
        candidate.headerDigest == actual.headerDigest && candidate.allocationDigest == actual.allocationDigest &&
        candidate.nativeIdentityDigest == sha256(journalNativeBytes(actual.nativeIdentity)))
}

/** Equality of actual owner-returned originals, not an ID-only or mirrored-authority shortcut. */
internal fun requireSameFoodRefundOriginal(actual: RetainedCommandEvidence, expected: RetainedCommandEvidence) {
    check(actual.scope == expected.scope && actual.operationId == expected.operationId && actual.originalActorId == expected.originalActorId &&
        actual.command == expected.command && actual.assignmentId == expected.assignmentId && actual.authorityEpoch == expected.authorityEpoch &&
        actual.sequence == expected.sequence && actual.recordedAt == expected.recordedAt && actual.semanticDigest == expected.semanticDigest &&
        actual.code == expected.code && actual.normalized == expected.normalized && actual.result == expected.result && actual.resultHash == expected.resultHash &&
        actual.previousJournalHash == expected.previousJournalHash && actual.journalHash == expected.journalHash &&
        actual.nativeOriginalProvenance == expected.nativeOriginalProvenance && actual.aggregateWrites.size == expected.aggregateWrites.size &&
        actual.aggregateWrites.zip(expected.aggregateWrites).all { (a, b) -> a.key == b.key && a.storageGeneration == b.storageGeneration &&
            a.schema == b.schema && a.payload == b.payload && a.payloadDigest == b.payloadDigest } &&
        actual.claimChanges.size == expected.claimChanges.size && actual.claimChanges.zip(expected.claimChanges).all { (a, b) ->
            a.key == b.key && a.ownerId == b.ownerId && a.action == b.action } && actual.jobs.size == expected.jobs.size &&
        actual.jobs.zip(expected.jobs).all { (a, b) -> a.kind == b.kind && a.body == b.body })
    if (actual.nativeOriginalProvenance == null) check(actual.authenticationId == expected.authenticationId && actual.policyRevision == expected.policyRevision)
}
