package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

@Serializable
public data class LocalSupplierCashRaw(
    public val version: Int, public val scope: BranchScope, public val target: LocalSupplierCashTarget,
    public val originalActorId: DomainId, public val recordingActorId: DomainId, public val normalizedBody: String,
    public val bodyDigest: PayloadDigest, public val semanticDigest: PayloadDigest, public val slotDigest: PayloadDigest,
    public val recordedAt: UtcInstant, public val phase: LocalSupplierCashPhase,
) {
    public fun body(): StoredBytes = StoredBytes(normalizedBody.encodeToByteArray(throwOnInvalidSequence = true))
}

@Serializable
public data class LocalSupplierCashApplication(
    public val version: Int, public val scope: BranchScope, public val target: LocalSupplierCashTarget,
    public val originalActorId: DomainId, public val phase: LocalSupplierCashPhase, public val bodyDigest: PayloadDigest,
    public val applicationOperationId: OperationId?, public val result: LocalSupplierCashResult?,
)

public class LocalSupplierCashStoredSlot internal constructor(public val row: StoredAggregate,
    public val value: LocalSupplierCashSlot, public val start: RetainedCommandEvidence)
public class LocalSupplierCashStoredFact internal constructor(public val slot: LocalSupplierCashStoredSlot,
    public val row: StoredAggregate, public val value: LocalSupplierCashRaw, public val original: RetainedCommandEvidence,
    public val applicationRow: StoredAggregate, public val application: LocalSupplierCashApplication)

/** Typed literal lineage over actual kernel originals. No row/Boolean creates native custody. */
public object LocalSupplierCashFacts {
    public fun raw(slot: LocalSupplierCashSlot, phase: LocalSupplierCashPhase, actorId: DomainId,
        body: StoredBytes, slotDigest: PayloadDigest, at: UtcInstant): LocalSupplierCashRaw {
        val requirement = slot.requirement()
        if (phase == LocalSupplierCashPhase.ORIGINAL) LocalSupplierCashJournalDomain.classifyOriginal(requirement, body)
        else {
            val resolution = LocalSupplierCashCodec.decode(body) as? LocalSupplierCashRequest.RecordResolution ?: error("supplier.cash.resolution_required")
            check(resolution.target == slot.target && resolution.resolverActorId == actorId && resolution.outcome !is LocalSupplierCashOutcome.Unknown)
        }
        check(actorId == slot.originalActorId && LocalSupplierCashCodec.normalize(body) == body)
        return LocalSupplierCashRaw(1, slot.scope, slot.target, slot.originalActorId, actorId,
            body.copyBytes().decodeToString(throwOnInvalidSequence = true), sha256(body.copyBytes()),
            semanticDigest(slot.scope, actorId, LocalSupplierCashProfile.COMMAND, body), slotDigest, at, phase)
    }
    public fun rawWrite(value: LocalSupplierCashRaw): AggregateChange = AggregateChange(
        if (value.phase == LocalSupplierCashPhase.ORIGINAL) LocalSupplierCashJournal.rawKey(value.target.recordingOperationId)
            else LocalSupplierCashJournal.resolutionKey(value.target.resolutionOperationId), null,
        LocalSupplierCashProfile.schema(if (value.phase == LocalSupplierCashPhase.ORIGINAL) LocalSupplierCashProfile.ORIGINAL else LocalSupplierCashProfile.RESOLUTION),
        localSalesEncode(value, LocalSupplierCashRaw.serializer(), LocalSupplierCashProfile.MAX_RAW_BYTES))
    public fun initial(value: LocalSupplierCashRaw): LocalSupplierCashApplication = LocalSupplierCashApplication(1, value.scope,
        value.target, value.originalActorId, value.phase, value.bodyDigest, null, null)
    public fun applicationWrite(value: LocalSupplierCashApplication, previousRevision: Revision?): AggregateChange {
        check(value.version == 1 && (value.applicationOperationId == null) == (value.result == null) &&
            if (previousRevision == null) value.applicationOperationId == null else previousRevision == Revision(1) && value.applicationOperationId != null)
        return AggregateChange(LocalSupplierCashJournal.applicationKey(value.target.factOperation(value.phase)), previousRevision,
            LocalSupplierCashProfile.schema(LocalSupplierCashProfile.APPLICATION), localSalesEncode(value, LocalSupplierCashApplication.serializer(), 8_192))
    }
    public fun decodeRaw(bytes: StoredBytes): LocalSupplierCashRaw = localSalesDecode(bytes, LocalSupplierCashRaw.serializer(),
        LocalSupplierCashProfile.MAX_RAW_BYTES).also { check(rawWrite(it).payload == bytes && it.version == 1 &&
        it.bodyDigest == sha256(it.body().copyBytes()) && LocalSupplierCashCodec.normalize(it.body()) == it.body() &&
        it.semanticDigest == semanticDigest(it.scope, it.recordingActorId, LocalSupplierCashProfile.COMMAND, it.body())) }
    public fun decodeApplication(bytes: StoredBytes): LocalSupplierCashApplication = localSalesDecode(bytes,
        LocalSupplierCashApplication.serializer(), 8_192).also {
        check(it.version == 1 && (it.applicationOperationId == null) == (it.result == null) &&
            localSalesEncode(it, LocalSupplierCashApplication.serializer(), 8_192) == bytes)
    }

    public fun requireStart(evidence: RetainedCommandEvidence): LocalSupplierCashSlot {
        check(evidence.committed && evidence.command == LocalSupplierCashProfile.COMMAND && evidence.nativeOriginalProvenance == null &&
            LocalSupplierCashCodec.normalize(evidence.normalized) == evidence.normalized && evidence.jobs.isEmpty())
        val request = LocalSupplierCashCodec.decode(evidence.normalized) as? LocalSupplierCashRequest.StartCash ?: error("supplier.cash.start_required")
        val slotRow = evidence.aggregateWrites.single { it.key == LocalSupplierCashJournal.slotKey(request.recordingOperationId) }
        check(slotRow.storageGeneration == Revision(1) && slotRow.schema == LocalSupplierCashProfile.schema(LocalSupplierCashProfile.SLOT))
        val slot = LocalSupplierCashJournal.decodeSlot(slotRow.payload)
        val requirement = slot.requirement(); val domain = slot.domain
        check(slot.scope == evidence.scope && slot.originalActorId == evidence.originalActorId && slot.intentSemanticDigest == evidence.semanticDigest &&
            slot.target == request.target(evidence.operationId) && domain.source == request.source && domain.amount == request.amount &&
            domain.sessionRevision == request.expectedSessionRevision && domain.drawerRevision == request.expectedDrawerRevision &&
            domain.custodianRevision == request.expectedCustodianRevision && domain.workRevision == request.expectedWorkRevision && domain.reasonCode == request.reasonCode &&
            domain.authority.assignmentId == evidence.assignmentId && domain.authority.epoch == evidence.authorityEpoch)
        val sourceRows = evidence.aggregateWrites.filter { it.key.kind == PhysicalRecordingSourceProfileV1.KIND }.map(::supplierCashInsert)
        val pair = LocalSupplierCashJournal.sourcePair(requirement, sourceRows)
        check(slot.masterSource == LocalSupplierCashSourceReference.from(pair.first) && slot.clientSource == LocalSupplierCashSourceReference.from(pair.second))
        val gated = evidence.aggregateWrites.filter { it.key.kind in LocalSupplierCashProfile.gatedKinds || it.key.kind == ReservedOperations.KIND }
        requireWrites(gated, sourceRows + LocalSupplierCashJournal.slotWrite(slot) + LocalSupplierCashJournal.fences(requirement))
        val claims = evidence.claimChanges.filter { it.key.namespace in LocalSupplierCashProfile.gatedClaims }
        val expectedClaims = LocalSupplierCashJournal.lineageClaims(requirement)
        check(claims.size == expectedClaims.size && claims.all { value -> value.action == RetainedClaimAction.ACQUIRE &&
            expectedClaims.singleOrNull { it.key == value.key }?.ownerId == value.ownerId })
        val result = LocalSupplierCashCodec.result(evidence.result) as? LocalSupplierCashResult.AttemptStarted ?: error("supplier.cash.start_result_required")
        check(result.target == slot.target && result.amount == domain.amount && result.requirementDigest == sha256(requirement.canonicalBytes.copyBytes()))
        return slot
    }

    public fun requireRaw(evidence: RetainedCommandEvidence, start: RetainedCommandEvidence,
        phase: LocalSupplierCashPhase): LocalSupplierCashRaw {
        val slot = requireStart(start)
        check(evidence.committed && evidence.command == LocalSupplierCashProfile.COMMAND && evidence.originalActorId == slot.originalActorId &&
            evidence.operationId == slot.target.factOperation(phase) && LocalSupplierCashCodec.normalize(evidence.normalized) == evidence.normalized &&
            evidence.claimChanges.isEmpty() && evidence.jobs.isEmpty())
        val key = if (phase == LocalSupplierCashPhase.ORIGINAL) LocalSupplierCashJournal.rawKey(evidence.operationId)
            else LocalSupplierCashJournal.resolutionKey(evidence.operationId)
        val raw = decodeRaw(evidence.aggregateWrites.single { it.key == key }.payload)
        check(raw.scope == evidence.scope && raw.target == slot.target && raw.phase == phase && raw.originalActorId == slot.originalActorId &&
            raw.recordingActorId == slot.originalActorId && raw.body() == evidence.normalized && raw.semanticDigest == evidence.semanticDigest &&
            raw.recordedAt == evidence.recordedAt && raw.slotDigest == sha256(LocalSupplierCashJournal.slotWrite(slot).payload.copyBytes()))
        if (phase == LocalSupplierCashPhase.ORIGINAL) {
            LocalSupplierCashJournalDomain.classifyOriginal(slot.requirement(), raw.body())
            evidence.nativeOriginalProvenance?.let { requireNativeOriginalEvidence(evidence, start, slot) }
        } else check(evidence.nativeOriginalProvenance == null)
        requireWrites(evidence.aggregateWrites, listOf(rawWrite(raw), applicationWrite(initial(raw), null)))
        check(LocalSupplierCashCodec.result(evidence.result) == LocalSupplierCashResult.RawRetained(slot.target, phase))
        return raw
    }

    public suspend fun slot(view: CommandReadView, operationId: OperationId): LocalSupplierCashStoredSlot? =
        slot(CurrentSupplierCashObservation(view), operationId)
    public suspend fun slot(command: RuntimeHistoricalCommand, operationId: OperationId): LocalSupplierCashStoredSlot? =
        slot(HistoricalSupplierCashObservation(command), operationId)
    private suspend fun slot(observation: SupplierCashFactObservation, operationId: OperationId): LocalSupplierCashStoredSlot? {
        val row = observation.aggregate(LocalSupplierCashJournal.slotKey(operationId)) ?: run {
            check(observation.claim(ClaimKey(LocalSupplierCashProfile.OPERATION_CLAIM, operationId.value)) == null &&
                observation.aggregate(LocalSupplierCashJournal.rawKey(operationId)) == null &&
                observation.aggregate(LocalSupplierCashJournal.applicationKey(operationId)) == null &&
                observation.aggregate(ReservedOperations.key(operationId)) == null) { "supplier.cash.slot_companion_without_start" }
            return null
        }
        check(row.revision == Revision(1) && row.schema == LocalSupplierCashProfile.schema(LocalSupplierCashProfile.SLOT) &&
            row.digest == sha256(row.payload.copyBytes()))
        val value = LocalSupplierCashJournal.decodeSlot(row.payload)
        check(value.scope == observation.scope && value.target.recordingOperationId == operationId)
        val start = observation.retained(value.target.intentOperationId) ?: error("supplier.cash.start_unavailable")
        check(requireStart(start) == value)
        requireRetainedRow(start, row)
        val requirement = value.requirement()
        val sources = listOf(requirement.masterAllocationId, requirement.clientAllocationId).map { id ->
            val source = observation.aggregate(AggregateKey(PhysicalRecordingSourceProfileV1.KIND, id)) ?: error("supplier.cash.source_unavailable")
            requireRetainedRow(start, source); supplierCashInsert(source)
        }
        LocalSupplierCashJournal.sourcePair(requirement, sources)
        for (claim in LocalSupplierCashJournal.lineageClaims(requirement)) check(observation.claim(claim.key) == claim.ownerId)
        for (fence in LocalSupplierCashJournal.fences(requirement)) {
            val current = observation.aggregate(fence.key) ?: error("supplier.cash.fence_unavailable")
            check(current.revision == Revision(1) && current.schema == fence.schema && current.payload == fence.payload); requireRetainedRow(start, current)
        }
        return LocalSupplierCashStoredSlot(row, value, start)
    }

    public suspend fun fact(view: CommandReadView, operationId: OperationId, phase: LocalSupplierCashPhase): LocalSupplierCashStoredFact? =
        fact(CurrentSupplierCashObservation(view), operationId, phase)
    public suspend fun fact(command: RuntimeHistoricalCommand, operationId: OperationId, phase: LocalSupplierCashPhase): LocalSupplierCashStoredFact? =
        fact(HistoricalSupplierCashObservation(command), operationId, phase)
    private suspend fun fact(observation: SupplierCashFactObservation, operationId: OperationId,
        phase: LocalSupplierCashPhase): LocalSupplierCashStoredFact? {
        val key = if (phase == LocalSupplierCashPhase.ORIGINAL) LocalSupplierCashJournal.rawKey(operationId)
            else LocalSupplierCashJournal.resolutionKey(operationId)
        val row = observation.aggregate(key) ?: run {
            check(observation.aggregate(LocalSupplierCashJournal.applicationKey(operationId)) == null) { "supplier.cash.application_without_raw" }
            val retained = observation.retained(operationId)
            if (retained?.committed == true && retained.command == LocalSupplierCashProfile.COMMAND) {
                val request = LocalSupplierCashCodec.decode(retained.normalized)
                check(request !is LocalSupplierCashRequest.RecordOriginal && request !is LocalSupplierCashRequest.RecordResolution) {
                    "supplier.cash.retained_original_without_raw"
                }
            }
            return null
        }
        check(row.revision == Revision(1) && row.schema == LocalSupplierCashProfile.schema(
            if (phase == LocalSupplierCashPhase.ORIGINAL) LocalSupplierCashProfile.ORIGINAL else LocalSupplierCashProfile.RESOLUTION) &&
            row.digest == sha256(row.payload.copyBytes()))
        val value = decodeRaw(row.payload)
        val slot = slot(observation, value.target.recordingOperationId) ?: error("supplier.cash.slot_unavailable")
        val original = observation.retained(operationId) ?: error("supplier.cash.raw_original_unavailable")
        check(requireRaw(original, slot.start, phase) == value); requireRetainedRow(original, row)
        val appRow = observation.aggregate(LocalSupplierCashJournal.applicationKey(operationId)) ?: error("supplier.cash.application_unavailable")
        check(appRow.schema == LocalSupplierCashProfile.schema(LocalSupplierCashProfile.APPLICATION) && appRow.digest == sha256(appRow.payload.copyBytes()))
        val application = decodeApplication(appRow.payload)
        check(application.scope == observation.scope && application.target == value.target && application.phase == phase &&
            application.originalActorId == value.originalActorId && application.bodyDigest == value.bodyDigest)
        if (application.applicationOperationId == null) {
            check(appRow.revision == Revision(1) && application == initial(value)); requireRetainedRow(original, appRow)
        } else {
            check(appRow.revision == Revision(2))
            val producer = observation.retained(checkNotNull(application.applicationOperationId)) ?: error("supplier.cash.application_original_unavailable")
            check(producer.committed && producer.command == LocalSupplierCashProfile.COMMAND && producer.originalActorId == value.originalActorId &&
                LocalSupplierCashCodec.result(producer.result) == application.result)
            requireRetainedRow(producer, appRow)
        }
        return LocalSupplierCashStoredFact(slot, row, value, original, appRow, application)
    }

    public fun nativeProjection(native: NativeOriginalRetained): NativeOriginalProjection {
        val requirement = LocalSupplierCashPhysicalProjection.parse(native.canonicalRequirement).requirement
        check(native.command == LocalSupplierCashProfile.COMMAND && native.domainProfile == LocalSupplierCashProfile.DOMAIN)
        LocalSupplierCashJournalDomain.classifyOriginal(requirement, native.originalBody)
        val pair = LocalSupplierCashJournal.sourcePair(requirement, native.sourceRows)
        return NativeOriginalProjection(NativeOriginalDomain.SUPPLIER_CASH_V1, requirement.scope, requirement.originalActorId,
            requirement.intentOperationId, requirement.intentSemanticDigest, requirement.recordingOperationId, requirement.resolutionOperationId,
            supplierCashFenceBinding(supplierCashDomain(requirement).target), provenanceSource(pair.first), provenanceSource(pair.second),
            supplierCashDomain(requirement).financialResources.toSet() + LocalSupplierCashJournal.authorityResources(requirement))
    }

    public fun requireStartCertificate(bytes: StoredBytes, start: RetainedCommandEvidence,
        requirement: LocalPhysicalJournalRequirementV1): PhysicalCommittedStartProjectionV1 {
        requireStart(start)
        val certificate = PhysicalCommittedStartProjectionV1.parse(bytes)
        check(certificate.scope == start.scope && certificate.originalActorId == start.originalActorId &&
            certificate.intentOperationId == start.operationId && certificate.command == start.command && certificate.intentSemanticDigest == start.semanticDigest &&
            certificate.assignmentId == start.assignmentId && certificate.epoch == start.authorityEpoch && certificate.sequence == start.sequence &&
            certificate.journalHash == start.journalHash && certificate.resultDigest == start.resultHash && certificate.recordedAt == start.recordedAt &&
            certificate.requirementBytes == requirement.canonicalBytes && certificate.domainProfile == requirement.domainProfile &&
            certificate.masterDeviceId == requirement.masterDeviceId && certificate.startKind == PhysicalRecordingStartKind.PAYMENT)
        val sources = start.aggregateWrites.filter { it.key.kind == PhysicalRecordingSourceProfileV1.KIND }
        check(certificate.sourceStamps.size == 2 && certificate.sourceStamps.map { it.key }.toSet() == sources.map { it.key }.toSet())
        for (source in sources) check(certificate.sourceStamps.single { it.key == source.key }.let {
            it.schema == source.schema && it.generation == source.storageGeneration && it.payloadDigest == source.payloadDigest })
        return certificate
    }

    public fun requireNativeOriginalEvidence(original: RetainedCommandEvidence, start: RetainedCommandEvidence, slot: LocalSupplierCashSlot): Unit {
        val provenance = checkNotNull(original.nativeOriginalProvenance); val requirement = slot.requirement(); val history = provenance.historicalStart
        check(start.nativeOriginalProvenance == null && provenance.scope == original.scope && provenance.scope == start.scope &&
            history.originalActorId == start.originalActorId && history.intentOperationId == start.operationId && history.command == start.command &&
            history.intentSemanticDigest == start.semanticDigest && history.authenticationId == start.authenticationId && history.policyRevision == start.policyRevision &&
            history.assignmentId == start.assignmentId && history.epoch == start.authorityEpoch && history.sequence == start.sequence && history.journalHash == start.journalHash &&
            history.resultDigest == start.resultHash && history.recordedAt == start.recordedAt && history.masterDeviceId == requirement.masterDeviceId &&
            provenance.nativeOriginal.recordingOperationId == requirement.recordingOperationId && provenance.nativeOriginal.domainProfile == requirement.domainProfile &&
            provenance.nativeOriginal.requirementDigest == sha256(requirement.canonicalBytes.copyBytes()) &&
            provenance.nativeOriginal.originalBodyDigest == sha256(original.normalized.copyBytes()) && provenance.nativeOriginal.originalSemanticDigest == original.semanticDigest &&
            provenance.currentWriter.assignmentId == original.assignmentId && provenance.currentWriter.epoch == original.authorityEpoch && provenance.runtimeParent.sequence.value > 0L)
        val pair = LocalSupplierCashJournal.sourcePair(requirement, start.aggregateWrites.filter { it.key.kind == PhysicalRecordingSourceProfileV1.KIND }.map(::supplierCashInsert))
        check(provenance.nativeOriginal.masterSource == provenanceSource(pair.first) && provenance.nativeOriginal.clientSource == provenanceSource(pair.second))
    }

    public fun requireRetainedRow(original: RetainedCommandEvidence, row: StoredAggregate): Unit {
        val write = original.aggregateWrites.single { it.key == row.key }
        check(write.storageGeneration == row.revision && write.schema == row.schema && write.payload == row.payload && write.payloadDigest == row.digest)
    }
    public fun requireWrites(actual: List<RetainedAggregateWrite>, expected: List<AggregateChange>): Unit {
        check(actual.size == expected.size && actual.map { it.key }.toSet() == expected.map { it.key }.toSet())
        for (row in actual) {
            val wanted = expected.single { it.key == row.key }
            check(row.storageGeneration == (wanted.expectedRevision ?: Revision(0)).next() && row.schema == wanted.schema &&
                row.payload == wanted.payload && row.payloadDigest == sha256(row.payload.copyBytes()))
        }
    }
}

private fun provenanceSource(value: LocalPhysicalJournalSourceProjection): NativeOriginalJournalProvenanceV1.Source =
    NativeOriginalJournalProvenanceV1.Source(if (value.owner.role == LocalPhysicalSpoolRole.MASTER) NativeOriginalJournalProvenanceV1.SourceRole.MASTER
        else NativeOriginalJournalProvenanceV1.SourceRole.CLIENT, value.owner.installationId, value.owner.deviceId, value.owner.catalogueId,
        value.owner.keyId, value.allocationId, value.slotIndex, sha256(value.sourceCommitment.copyBytes()), value.headerDigest,
        value.allocationDigest, sha256(journalNativeBytes(value.nativeIdentity)))
private fun supplierCashInsert(value: RetainedAggregateWrite): AggregateChange {
    check(value.storageGeneration == Revision(1) && value.payloadDigest == sha256(value.payload.copyBytes()))
    return AggregateChange(value.key, null, value.schema, value.payload)
}
private fun supplierCashInsert(value: StoredAggregate): AggregateChange {
    check(value.revision == Revision(1) && value.digest == sha256(value.payload.copyBytes()))
    return AggregateChange(value.key, null, value.schema, value.payload)
}

/** Only real current loans and core-created historical observations enter this read port. */
private sealed interface SupplierCashFactObservation {
    val scope: BranchScope
    suspend fun aggregate(key: AggregateKey): StoredAggregate?
    suspend fun claim(key: ClaimKey): DomainId?
    suspend fun retained(operation: OperationId): RetainedCommandEvidence?
}
private class CurrentSupplierCashObservation(private val view: CommandReadView) : SupplierCashFactObservation {
    override val scope: BranchScope get() = view.scope
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = view.aggregate(key)
    override suspend fun claim(key: ClaimKey): DomainId? = view.claim(key)
    override suspend fun retained(operation: OperationId): RetainedCommandEvidence? = view.retainedCommand(operation)
}
private class HistoricalSupplierCashObservation(private val command: RuntimeHistoricalCommand) : SupplierCashFactObservation {
    override val scope: BranchScope get() = command.scope
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = command.aggregateSource(key)?.row
    override suspend fun claim(key: ClaimKey): DomainId? = command.claim(key)
    override suspend fun retained(operation: OperationId): RetainedCommandEvidence? = when (val original = command.earlierOriginal(operation)) {
        is RetainedOriginalV2.Legacy -> original.evidence
        is RetainedOriginalV2.Streamed -> error("Unsupported supplier cash original")
        null -> null
    }
}
