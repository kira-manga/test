package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.Revision

/**
 * The two actual retail interpreters. Registered ONLY by the genuine P4 original owner beside
 * the SAME native gate. No employee principal, arbitrary mutation/provider or financial reducer.
 */
public class LocalRetailNativeOriginalImport(override val domain: NativeOriginalDomain,
    private val stock: com.ukkera.pos.inventory.store.LocalStockRepository? = null,
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1) : NativeOriginalImportInterpreter {
    init {
        require(domain in setOf(NativeOriginalDomain.RETAIL_PAYMENT_V3, NativeOriginalDomain.RETAIL_REFUND_V3))
    }

    override fun project(native: NativeOriginalRetained): NativeOriginalProjection {
        val projection = LocalRetailPhysicalProjection.parse(native.canonicalRequirement)
        val requirement = projection.requirement
        check(native.command == domain.command && native.domainProfile == domain.domainProfile &&
            requirement.command == native.command && requirement.domainProfile == native.domainProfile &&
            (domain == NativeOriginalDomain.RETAIL_PAYMENT_V3) ==
                (projection.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION))
        retailJournalDomain(projection.target.kind).classifyOriginal(requirement, native.originalBody)
        val sources = requireRetailSourcePairV3(requirement, native.sourceRows)
        fun source(value: LocalPhysicalJournalSourceProjection): NativeOriginalJournalProvenanceV1.Source =
            NativeOriginalJournalProvenanceV1.Source(
                if (value.owner.role == LocalPhysicalSpoolRole.MASTER) NativeOriginalJournalProvenanceV1.SourceRole.MASTER
                else NativeOriginalJournalProvenanceV1.SourceRole.CLIENT,
                value.owner.installationId, value.owner.deviceId, value.owner.catalogueId, value.owner.keyId,
                value.allocationId, value.slotIndex, retailJournalDigestV3(value.sourceCommitment), value.headerDigest,
                value.allocationDigest, com.ukkera.pos.security.sha256(journalNativeBytes(value.nativeIdentity)))
        return NativeOriginalProjection(domain, requirement.scope, requirement.originalActorId,
            requirement.intentOperationId, requirement.intentSemanticDigest, requirement.recordingOperationId,
            requirement.resolutionOperationId, retailJournalFenceBindingV3(projection.target, LocalJournalFactPhaseV3.ORIGINAL),
            source(sources.first), source(sources.second),
            retailDomainRequirementV3(requirement).financialResources.toSet() + retailJournalAuthorityResources(requirement))
    }

    override suspend fun prepare(borrowed: CommandReadView, context: NativeOriginalImportContext): NativeOriginalImportPlan? {
        val view = stock?.let { commerceLoan(borrowed, it, tenderPolicies) } ?: borrowed
        val native = context.native
        val projection = LocalRetailPhysicalProjection.parse(native.canonicalRequirement)
        val requirement = projection.requirement
        val kind = projection.target.kind
        if ((domain == NativeOriginalDomain.RETAIL_PAYMENT_V3) != (kind == SalesRecordingKind.PAYMENT_ATTESTATION) ||
            native.command != requirement.command || native.domainProfile != requirement.domainProfile || view.scope != requirement.scope) return null
        val original = when (kind) {
            SalesRecordingKind.PAYMENT_ATTESTATION -> (LocalCollectionCommandCodecV3.decode(native.originalBody)
                as? LocalCollectionRequestV3.RecordOriginal)?.target
            SalesRecordingKind.REFUND_ATTESTATION -> (LocalReturnCommandCodecV3.decode(native.originalBody)
                as? LocalReturnRequestV3.RecordOriginal)?.target
        } ?: return null
        if (original != projection.target) return null
        retailJournalDomain(kind).classifyOriginal(requirement, native.originalBody)
        val slot = readRetailSlot(view, original.recordingOperationId)
        if (slot.value.requirement().canonicalBytes != requirement.canonicalBytes) return null
        val start = context.historicalStart
        requireRetailStartEvidenceV3(start, slot.value, tenderPolicies)
        if (start.aggregateWrites.any { it.key.kind == StockReadEvidenceV1.KIND }) requireNormalizedPaymentOrigin(view, start)
        check(start.nativeOriginalProvenance == null) // Historical Start was actually employee-authorized.
        val certificate = PhysicalCommittedStartProjectionV1.parse(native.certificate)
        check(certificate.scope == start.scope && certificate.originalActorId == start.originalActorId &&
            certificate.intentOperationId == start.operationId && certificate.command == start.command &&
            certificate.intentSemanticDigest == start.semanticDigest && certificate.assignmentId == start.assignmentId &&
            certificate.epoch == start.authorityEpoch && certificate.sequence == start.sequence && certificate.journalHash == start.journalHash &&
            certificate.resultDigest == start.resultHash && certificate.recordedAt == start.recordedAt &&
            certificate.requirementBytes == requirement.canonicalBytes && certificate.domainProfile == requirement.domainProfile &&
            certificate.masterDeviceId == requirement.masterDeviceId)
        val sources = requireRetailSourcePairV3(requirement, native.sourceRows)
        check(slot.value.masterSource == retailSourceReferenceV3(sources.first) && slot.value.clientSource == retailSourceReferenceV3(sources.second))
        for (source in native.sourceRows) {
            val row = view.aggregate(source.key) ?: return null
            check(row.revision == Revision(1) && row.schema == source.schema && row.payload == source.payload)
            requireRetailRetainedRowV3(start, row)
            check(certificate.sourceStamps.singleOrNull { it.key == row.key }?.let {
                it.schema == row.schema && it.generation == row.revision && it.payloadDigest == row.digest
            } == true)
        }
        val operation = original.recordingOperationId
        // Recognition/retry belongs to core BEFORE this materializer. An advanced application
        // row is never overwritten by replaying the original initial-pending constructor.
        if (view.aggregate(retailJournalFactKeyV3(LocalJournalFactPhaseV3.ORIGINAL, operation)) != null ||
            view.aggregate(retailJournalApplicationKeyV3(operation)) != null) return null
        val fact = LocalRetailJournalFactV3(3, LocalSalesScope.from(view.scope), original, LocalJournalFactPhaseV3.ORIGINAL,
            requirement.originalActorId, requirement.originalActorId, native.originalBody.copyBytes().decodeToString(throwOnInvalidSequence = true),
            retailJournalDigestV3(native.originalBody).value,
            semanticDigest(view.scope, requirement.originalActorId, requirement.command, native.originalBody).value,
            slot.stored.digest.value, null, context.recordedAt)
        val pending = LocalRetailJournalApplicationV3(3, fact.scope, original, LocalJournalFactPhaseV3.ORIGINAL,
            requirement.originalActorId, Revision(0), LocalJournalApplicationStateV3.PENDING_RECONCILIATION,
            LocalJournalPendingReasonV3.AWAITING_FINANCIAL_APPLICATION, context.recordedAt, null, null, emptyList())
        return NativeOriginalImportPlan(domain, operation, requirement.originalActorId, requirement.command,
            native.originalBody, PayloadDigest(fact.semanticDigest), retailJournalFenceBindingV3(original, LocalJournalFactPhaseV3.ORIGINAL),
            retailFactWriteV3(fact), retailApplicationWriteV3(pending, null), LocalFinancialResultCodecV3.encode(
                retailPending(original, LocalJournalFactPhaseV3.ORIGINAL, LocalJournalPendingReasonV3.AWAITING_FINANCIAL_APPLICATION)))
    }

    override suspend fun verifyExisting(borrowed: CommandReadView, context: NativeOriginalImportContext,
        original: RetainedCommandEvidence): Boolean {
        val view = stock?.let { commerceLoan(borrowed, it, tenderPolicies) } ?: borrowed
        return if (domain == NativeOriginalDomain.RETAIL_PAYMENT_V3 && view is CommerceStockView &&
            context.historicalStart.aggregateWrites.any { it.key.kind == StockReadEvidenceV1.KIND }) {
            withNativePaymentOriginalProof(view) { verifyRetailExisting(it, context, original) }
        } else verifyRetailExisting(view, context, original)
    }

    private suspend fun verifyRetailExisting(view: CommandReadView, context: NativeOriginalImportContext,
        original: RetainedCommandEvidence): Boolean {
        val projection = project(context.native)
        if (view.scope != projection.scope || original.operationId != projection.recordingOperationId ||
            original.scope != view.scope || original.command != domain.command ||
            original.originalActorId != projection.originalActorId || original.normalized != context.native.originalBody)
            return false
        // The ACTUAL complete reader validates the current slot/whole Start/source/fences,
        // immutable raw producer, and initial OR advanced application+financial lineage. In
        // particular an already-applied row is never rematerialized as initial pending.
        val cut = readRetailFact(view, projection.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL)
        val currentOrigin = view.retainedCommand(projection.recordingOperationId) ?: return false
        if (currentOrigin.journalHash != original.journalHash || currentOrigin.resultHash != original.resultHash ||
            currentOrigin.sequence != original.sequence || currentOrigin.assignmentId != original.assignmentId ||
            currentOrigin.authorityEpoch != original.authorityEpoch) return false
        val requirement = cut.slot.value.requirement()
        if (requirement.canonicalBytes != context.native.canonicalRequirement || cut.fact.value.body() != context.native.originalBody ||
            cut.fact.value.originalActorId != projection.originalActorId || cut.fact.value.recordingActorId != projection.originalActorId)
            return false
        requireRetailStartEvidenceV3(context.historicalStart, cut.slot.value, tenderPolicies)
        if (context.historicalStart.aggregateWrites.any { it.key.kind == StockReadEvidenceV1.KIND })
            requireNormalizedPaymentOrigin(view, context.historicalStart)
        requireRetailRawEvidenceV3(original, cut.fact.value, cut.slot.value)
        if (original.nativeOriginalProvenance != null)
            requireRetailNativeOriginalEvidence(original, context.historicalStart, cut.fact.value, cut.slot.value, tenderPolicies)
        // Certificate/scalar/source projection DATA are also independently matched by core
        // to this exact native lease and the same historical Start; none is a permission DTO.
        return true
    }
}

/** Reader validation of actual v2 lineage against the immutable original Start, never a permit. */
internal fun requireRetailNativeOriginalEvidence(origin: RetainedCommandEvidence, start: RetainedCommandEvidence,
    fact: LocalRetailJournalFactV3, slot: LocalRetailJournalSlotV3,
    tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1) {
    requireRetailStartEvidenceV3(start, slot, tenderPolicies)
    val p = checkNotNull(origin.nativeOriginalProvenance)
    val requirement = slot.requirement()
    val historical = p.historicalStart
    check(start.nativeOriginalProvenance == null && fact.phase == LocalJournalFactPhaseV3.ORIGINAL &&
        fact.recordingActorId == requirement.originalActorId && fact.originalActorId == requirement.originalActorId &&
        p.scope == origin.scope && p.scope == start.scope && historical.originalActorId == start.originalActorId &&
        historical.intentOperationId == start.operationId && historical.command == start.command && historical.intentSemanticDigest == start.semanticDigest &&
        historical.authenticationId == start.authenticationId && historical.policyRevision == start.policyRevision &&
        historical.assignmentId == start.assignmentId && historical.epoch == start.authorityEpoch && historical.sequence == start.sequence &&
        historical.journalHash == start.journalHash && historical.resultDigest == start.resultHash && historical.recordedAt == start.recordedAt &&
        historical.masterDeviceId == requirement.masterDeviceId &&
        p.nativeOriginal.recordingOperationId == fact.target.recordingOperationId && p.nativeOriginal.domainProfile == requirement.domainProfile &&
        p.nativeOriginal.requirementDigest == retailJournalDigestV3(requirement.canonicalBytes) &&
        p.nativeOriginal.originalBodyDigest.value == fact.bodyDigest && p.nativeOriginal.originalSemanticDigest.value == fact.semanticDigest &&
        p.currentWriter.assignmentId == origin.assignmentId && p.currentWriter.epoch == origin.authorityEpoch)
    val sourceRefs = listOf(slot.masterSource, slot.clientSource)
    val provenance = listOf(p.nativeOriginal.masterSource, p.nativeOriginal.clientSource)
    check(provenance.map { it.allocationId }.toSet() == setOf(requirement.masterAllocationId, requirement.clientAllocationId))
    for ((reference, source) in sourceRefs.zip(provenance)) {
        check(reference.allocationId == source.allocationId && reference.sourceDigest == source.sourcePayloadDigest.value &&
            reference.headerDigest == source.headerDigest.value && reference.allocationDigest == source.allocationDigest.value &&
            reference.slotIndex == source.nativeSlotIndex)
    }
    val owners = listOf(slot.domain.masterOwner, slot.domain.clientOwner)
    for ((owner, source) in owners.zip(provenance)) check(owner.installationId == source.installationId &&
        owner.deviceId == source.deviceId && owner.catalogueId == source.catalogueId && owner.keyId == source.keyId)
    check(provenance[0].allocationId == requirement.masterAllocationId &&
        provenance[1].allocationId == requirement.clientAllocationId && p.runtimeParent.sequence.value > 0)
}
