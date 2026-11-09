package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

/** Genuine RETAIL refund Start/raw/known-or-UNKNOWN accounting only. It reuses the complete
 * production literal refund verifier and actual BEFORE sources, never executes a handler,
 * builds a historical CommandReadView, restores a session, or qualifies a missing accepted
 * return/policy/delivery source. Unsupported ancestral qualification remains a history hold.
 * Audited rejected Review and payment-supervisor paths are not refund business roots. */
public class LocalRetailRefundHistoryProducer : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalFinancialJournalProfileV3.RETURN_COMMAND, LocalRetailSupervisorCodecV1.COMMAND)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands) return false
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        check(original.scope == command.scope && original.operationId == command.operationId && original.originalActorId == command.actorId &&
            original.command == command.command && original.semanticDigest == command.semanticDigest && original.recordedAt == command.recordedAt)
        if (command.command == LocalRetailSupervisorCodecV1.COMMAND) {
            check(LocalRetailSupervisorCodecV1.normalize(command.normalized) == command.normalized)
            val request = LocalRetailSupervisorCodecV1.decode(command.normalized) as? LocalRetailSupervisorRequestV1.Apply ?: return false
            if (request.kind != SalesRecordingKind.REFUND_ATTESTATION) return false
            return apply(command, original, request.recordingOperationId, request.phase, supervised = true)
        }
        check(LocalReturnCommandCodecV3.normalize(command.normalized) == command.normalized)
        return when (val request = LocalReturnCommandCodecV3.decode(command.normalized)) {
            is LocalReturnRequestV3.Start -> start(command, original, request)
            is LocalReturnRequestV3.RecordOriginal -> raw(command, original, request.target, LocalJournalFactPhaseV3.ORIGINAL)
            is LocalReturnRequestV3.RecordResolution -> raw(command, original, request.target, LocalJournalFactPhaseV3.RESOLUTION)
            is LocalReturnRequestV3.ApplyObservation -> apply(command, original, request.recordingOperationId, request.phase, supervised = false)
        }
    }

    private fun startSlot(original: RetainedCommandEvidence): LocalRetailJournalSlotV3 {
        val request = LocalReturnCommandCodecV3.decode(original.normalized) as? LocalReturnRequestV3.Start ?: refundHistoryUnavailable()
        val write = original.aggregateWrites.singleOrNull { it.key == retailJournalSlotKeyV3(request.recordingOperationId) }
            ?: refundHistoryUnavailable()
        check(write.storageGeneration == Revision(1) && write.schema == LocalFinancialJournalProfileV3.SLOT_SCHEMA)
        val value = localSalesDecode(write.payload, LocalRetailJournalSlotV3.serializer(), LocalFinancialJournalProfileV3.MAX_SLOT_BYTES)
        check(value.target.kind == SalesRecordingKind.REFUND_ATTESTATION && value.target == request.target(original.operationId) &&
            retailSlotWriteV3(value).payload == write.payload)
        requireRetailStartEvidenceV3(original, value) // Entire unchanged native envelope + eight BEFORE images + refund transition.
        return value
    }

    private suspend fun start(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        request: LocalReturnRequestV3.Start): Boolean {
        if (command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            LocalFinancialResultCodecV3.decode(original.result) !is LocalFinancialResultV3.AttemptStarted) return false
        val slot = startSlot(original)
        val sources = retailRefundHistorySources(command, slot.target)
        val images = retailRefundBeforeRolesV3.map { role ->
            val write = original.aggregateWrites.single { it.key == retailBeforeKeyV3(command.operationId, role) }
            check(write.storageGeneration == Revision(1) && write.schema == "sales.journal.before/v3")
            localSalesDecode(write.payload, LocalRetailBeforeV3.serializer(), LocalFinancialJournalProfileV3.MAX_BEFORE_BYTES)
        }
        requireSourceImages(command, images, sources)
        check(slot.domain.financialResources.toSet() == sources.resources)
        requireStartVacancy(command, slot.requirement())
        command.requireRequirement(salesAuthorizationRequirement(SecurityAction.REFUND_RECORD, command.scope, request.returnId,
            request.expectedReturnRevision, command.operationId, command.semanticDigest, "refund.start",
            sources.cash.session.value.custodianId, request.amount))
        command.requireLegacyEffects(literalEffects(command, original), sources.resources + retailJournalAuthorityResources(slot.requirement()) +
            recordingAuthority(command.actorId))
        return true
    }

    /** Kernel independently closes operation-ID uniqueness. Do not probe a own/future original
     * through earlierOriginal: a genuine later raw in this complete scan is not Start-time state. */
    private suspend fun requireStartVacancy(command: RuntimeHistoricalCommand, requirement: LocalPhysicalJournalRequirementV1) {
        val target = retailDomainRequirementV3(requirement).target
        for (phase in LocalJournalFactPhaseV3.entries) {
            val operation = target.factOperation(phase)
            check(command.aggregate(ReservedOperations.key(operation)) == null &&
                command.claim(retailJournalOperationClaimV3(operation)) == null &&
                command.aggregate(retailJournalApplicationKeyV3(operation)) == null &&
                command.aggregate(retailJournalFactKeyV3(phase, operation)) == null)
        }
        check(command.aggregate(retailJournalSlotKeyV3(target.recordingOperationId)) == null &&
            command.claim(retailJournalAttemptClaimV3(target.attemptId)) == null)
        for (allocation in listOf(requirement.masterAllocationId, requirement.clientAllocationId)) check(
            command.aggregate(AggregateKey(LocalPhysicalJournalFormatV1.SOURCE_KIND, allocation)) == null &&
                command.claim(retailJournalAllocationClaimV3(allocation)) == null)
        check(command.claim(LocalSalesStoreProfile.unique("payment_attempt", target.attemptId)) == null &&
            command.claim(LocalSalesStoreProfile.unique("refund_attempt", target.attemptId)) == null &&
            command.claim(CashNonSaleStoreProfile.cashSourceClaim(target.attemptId)) == null &&
            command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, target.attemptId)) == null)
    }

    private class Slot(val value: LocalRetailJournalSlotV3, val start: RetainedOriginalV2.Legacy,
        val sources: Pair<LocalPhysicalJournalSourceProjection, LocalPhysicalJournalSourceProjection>)
    private class Fact(val value: LocalRetailJournalFactV3, val raw: RetainedOriginalV2.Legacy, val slot: Slot)
    private class Application(val value: LocalRetailJournalApplicationV3, val source: RuntimeHistoricalAggregateSource)

    private suspend fun slot(command: RuntimeHistoricalCommand, target: LocalJournalTargetV3): Slot {
        check(target.kind == SalesRecordingKind.REFUND_ATTESTATION)
        val paired = refundHistoryRequired(command, retailJournalSlotKeyV3(target.recordingOperationId))
        val row = paired.row
        check(row.schema == LocalFinancialJournalProfileV3.SLOT_SCHEMA && row.revision == Revision(1))
        val value = localSalesDecode(row.payload, LocalRetailJournalSlotV3.serializer(), LocalFinancialJournalProfileV3.MAX_SLOT_BYTES)
        val earlier = command.earlierOriginal(target.intentOperationId) as? RetainedOriginalV2.Legacy ?: refundHistoryUnavailable()
        check(value.target == target && value.scope.value() == command.scope && retailSlotWriteV3(value).payload == row.payload &&
            paired.original === earlier && startSlot(earlier.evidence) == value)
        val requirement = value.requirement()
        LocalRetailRefundJournalDomain.validateRequirement(requirement)
        val rows = listOf(requirement.masterAllocationId, requirement.clientAllocationId).map { allocation ->
            val source = refundHistoryRequired(command, AggregateKey(LocalPhysicalJournalFormatV1.SOURCE_KIND, allocation))
            check(source.original === earlier && source.row.revision == Revision(1) && source.row.schema == LocalPhysicalJournalFormatV1.SOURCE_SCHEMA)
            requireRetailRetainedRowV3(earlier.evidence, source.row)
            source.row
        }
        val sources = requireRetailSourcePairV3(requirement, rows.map { AggregateChange(it.key, null, it.schema, it.payload) })
        check(value.masterSource == retailSourceReferenceV3(sources.first) && value.clientSource == retailSourceReferenceV3(sources.second))
        for (expected in retailFutureFencesV3(requirement)) {
            val fence = refundHistoryRequired(command, expected.key)
            check(fence.original === earlier && fence.row.revision == Revision(1) && fence.row.schema == expected.schema && fence.row.payload == expected.payload)
            requireRetailRetainedRowV3(earlier.evidence, fence.row)
        }
        for (claim in retailLineageClaimsV3(requirement)) check(command.claim(claim.key) == claim.ownerId)
        check(command.claim(LocalSalesStoreProfile.unique("refund_attempt", target.attemptId)) == target.documentId)
        return Slot(value, earlier, sources)
    }

    private fun factAfter(original: RetainedCommandEvidence, target: LocalJournalTargetV3,
        phase: LocalJournalFactPhaseV3): LocalRetailJournalFactV3 {
        val write = original.aggregateWrites.singleOrNull { it.key == retailJournalFactKeyV3(phase, target.factOperation(phase)) }
            ?: refundHistoryUnavailable()
        check(write.storageGeneration == Revision(1) && write.schema == (if (phase == LocalJournalFactPhaseV3.ORIGINAL)
            LocalFinancialJournalProfileV3.ORIGINAL_SCHEMA else LocalFinancialJournalProfileV3.RESOLUTION_SCHEMA))
        return localSalesDecode(write.payload, LocalRetailJournalFactV3.serializer(), LocalFinancialJournalProfileV3.MAX_FACT_BYTES).also {
            check(it.target.kind == SalesRecordingKind.REFUND_ATTESTATION && it.target == target && it.phase == phase &&
                it.recordedAt == original.recordedAt && retailFactWriteV3(it).payload == write.payload)
        }
    }

    private suspend fun factBefore(command: RuntimeHistoricalCommand, operation: OperationId, phase: LocalJournalFactPhaseV3): Fact {
        val paired = refundHistoryRequired(command, retailJournalFactKeyV3(phase, operation))
        val earlier = command.earlierOriginal(operation) as? RetainedOriginalV2.Legacy ?: refundHistoryUnavailable()
        check(paired.original === earlier && paired.row.revision == Revision(1))
        val fact = localSalesDecode(paired.row.payload, LocalRetailJournalFactV3.serializer(), LocalFinancialJournalProfileV3.MAX_FACT_BYTES)
        val slot = slot(command, fact.target)
        check(fact.phase == phase && fact.target.factOperation(phase) == operation && factAfter(earlier.evidence, fact.target, phase) == fact)
        requireRetailRawEvidenceV3(earlier.evidence, fact, slot.value)
        if (earlier.evidence.nativeOriginalProvenance != null) requireRetailNativeOriginalEvidence(earlier.evidence, slot.start.evidence, fact, slot.value)
        if (phase == LocalJournalFactPhaseV3.RESOLUTION) {
            val parent = factBefore(command, fact.target.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL)
            requireResolution(parent.value, fact, slot.value)
        }
        return Fact(fact, earlier, slot)
    }

    private fun initialApplication(fact: LocalRetailJournalFactV3): LocalRetailJournalApplicationV3 =
        LocalRetailJournalApplicationV3(3, fact.scope, fact.target, fact.phase, fact.recordingActorId, Revision(0),
            LocalJournalApplicationStateV3.PENDING_RECONCILIATION, LocalJournalPendingReasonV3.AWAITING_FINANCIAL_APPLICATION,
            fact.recordedAt, null, null, emptyList())

    private suspend fun applicationBefore(command: RuntimeHistoricalCommand, fact: Fact): Application {
        val paired = refundHistoryRequired(command, retailJournalApplicationKeyV3(fact.value.target.factOperation(fact.value.phase)))
        val row = paired.row
        check(row.schema == LocalFinancialJournalProfileV3.APPLICATION_SCHEMA)
        val application = localSalesDecode(row.payload, LocalRetailJournalApplicationV3.serializer(), LocalFinancialJournalProfileV3.MAX_APPLICATION_BYTES)
        check(application.schemaVersion == 3 && application.scope == fact.value.scope && application.target == fact.value.target &&
            application.phase == fact.value.phase && application.actorId == fact.value.recordingActorId && application.revision.next() == row.revision)
        if (application.revision == Revision(0)) {
            check(paired.original === fact.raw && application == initialApplication(fact.value))
            requireRetailRetainedRowV3(fact.raw.evidence, row)
        } else {
            val previous = paired.original as? RetainedOriginalV2.Legacy ?: refundHistoryUnavailable()
            check(previous.evidence.nativeOriginalProvenance == null && previous.evidence.operationId == application.lastApplicationOperationId &&
                previous.evidence.recordedAt == application.updatedAt && previous.evidence.command in commands)
            val parent = if (fact.value.phase == LocalJournalFactPhaseV3.RESOLUTION && application.state == LocalJournalApplicationStateV3.APPLIED)
                unknownParent(command, fact) else null
            requireRetailApplicationOriginalV3(previous.evidence, fact.value, application, fact.slot.value, parent)
        }
        return Application(application, paired)
    }

    private suspend fun unknownParent(command: RuntimeHistoricalCommand, fact: Fact): RetailApplicationParentV3 {
        val parent = factBefore(command, fact.value.target.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL)
        val application = applicationBefore(command, parent)
        val previous = application.source.original as? RetainedOriginalV2.Legacy ?: refundHistoryUnavailable()
        val reference = LocalRetailParentApplicationV3(previous.evidence.operationId, application.source.row.revision, application.source.row.digest.value)
        check(application.value.state == LocalJournalApplicationStateV3.APPLIED && application.value.appliedAction == LocalSalesAction.REFUND_UNKNOWN &&
            application.value.lastApplicationOperationId == reference.operationId && parent.value.bodyDigest == fact.value.originalBodyDigest)
        return RetailApplicationParentV3(parent.value, application.value, reference)
    }

    private fun requireResolution(parent: LocalRetailJournalFactV3, resolution: LocalRetailJournalFactV3, slot: LocalRetailJournalSlotV3) {
        check(parent.target == resolution.target && parent.phase == LocalJournalFactPhaseV3.ORIGINAL && resolution.phase == LocalJournalFactPhaseV3.RESOLUTION &&
            LocalRetailRefundJournalDomain.classifyOriginal(slot.requirement(), parent.body()) == LocalPhysicalOriginalKind.UNKNOWN &&
            resolution.originalBodyDigest == parent.bodyDigest)
        LocalRetailRefundJournalDomain.validateResolution(slot.requirement(), parent.body(), resolution.recordingActorId,
            retailResolutionDescriptorV3(slot.requirement(), parent.body(), resolution.recordingActorId, resolution.body()), resolution.body())
    }

    private suspend fun raw(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        target: LocalJournalTargetV3, phase: LocalJournalFactPhaseV3): Boolean {
        val slot = slot(command, target)
        val fact = factAfter(original, target, phase)
        requireRetailRawEvidenceV3(original, fact, slot.value)
        check(command.aggregate(retailJournalFactKeyV3(phase, command.operationId)) == null &&
            command.aggregate(retailJournalApplicationKeyV3(command.operationId)) == null)
        if (phase == LocalJournalFactPhaseV3.RESOLUTION) {
            if (command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
            val parent = factBefore(command, target.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL)
            applicationBefore(command, parent)
            requireResolution(parent.value, fact, slot.value)
        }
        val expected = ProposedOutcome.Apply(original.result,
            listOf(retailFactWriteV3(fact), retailApplicationWriteV3(initialApplication(fact), null)).sortedWith(compareBy({ it.key.kind }, { it.key.id.value })))
        val requirement = slot.value.requirement()
        if (command.authority == RuntimeHistoricalAuthority.NATIVE_ORIGINAL) {
            check(phase == LocalJournalFactPhaseV3.ORIGINAL)
            requireRetailNativeOriginalEvidence(original, slot.start.evidence, fact, slot.value)
            val native = checkNotNull(command.nativeOriginal)
            check(native.provenance == original.nativeOriginalProvenance)
            requireNativeSources(native.provenance, slot.sources)
            command.requireNativeOriginalRequirement(requirement.canonicalBytes)
            command.requireNativeOriginalEffects(expected, slot.value.domain.financialResources.toSet() + retailJournalAuthorityResources(requirement))
        } else {
            command.requireRequirement(salesAuthorizationRequirement(retailFactAction(target.kind, phase), command.scope, slot.value.id,
                Revision(0), command.operationId, command.semanticDigest, "financial.journal.retain", slot.value.actorId, slot.value.domain.amount))
            command.requireLegacyEffects(expected, retailJournalAuthorityResources(requirement) + recordingAuthority(command.actorId))
        }
        return true
    }

    private suspend fun apply(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence, operation: OperationId,
        phase: LocalJournalFactPhaseV3, supervised: Boolean): Boolean {
        if (command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
        val result = LocalFinancialResultCodecV3.decode(original.result)
        if (result !is LocalFinancialResultV3.Applied && result !is LocalFinancialResultV3.Pending) return false
        val fact = factBefore(command, operation, phase)
        if (!supervised && (command.actorId != fact.slot.value.actorId || fact.value.recordingActorId != fact.slot.value.actorId)) return false
        val before = applicationBefore(command, fact)
        if (before.value.state != LocalJournalApplicationStateV3.PENDING_RECONCILIATION) return false
        val write = original.aggregateWrites.singleOrNull { it.key == before.source.row.key } ?: refundHistoryUnavailable()
        check(write.schema == LocalFinancialJournalProfileV3.APPLICATION_SCHEMA && write.storageGeneration == before.source.row.revision.next())
        val application = localSalesDecode(write.payload, LocalRetailJournalApplicationV3.serializer(), LocalFinancialJournalProfileV3.MAX_APPLICATION_BYTES)
        if (result is LocalFinancialResultV3.Pending) return pending(command, original, fact, before, application, supervised)
        check(application.state == LocalJournalApplicationStateV3.APPLIED)
        val parent = if (phase == LocalJournalFactPhaseV3.RESOLUTION) unknownParent(command, fact) else null
        val proof = checkNotNull(application.financialProof)
        val sources = retailRefundHistorySources(command, fact.value.target)
        requireSourceImages(command, proof.sources, sources)
        requireRetailApplicationOriginalV3(original, fact.value, application, fact.slot.value, parent) // Whole unchanged accounting producer.
        check(application == before.value.copy(revision = before.value.revision.next(), state = LocalJournalApplicationStateV3.APPLIED,
            reason = null, updatedAt = original.recordedAt, lastApplicationOperationId = original.operationId,
            appliedAction = retailRefundAction(retailRefundObservation(fact.value)), financialRevisions = application.financialRevisions, financialProof = proof) &&
            retailApplicationWriteV3(application, before.source.row).payload == write.payload)
        command.requireRequirement(salesAuthorizationRequirement(retailFactAction(SalesRecordingKind.REFUND_ATTESTATION,
            if (supervised) LocalJournalFactPhaseV3.RESOLUTION else phase), command.scope, sources.returned.accepted.value.id,
            sources.returned.accepted.value.revision, command.operationId, command.semanticDigest, "financial.journal.refund.apply",
            fact.slot.value.actorId, fact.slot.value.domain.amount))
        command.requireLegacyEffects(literalEffects(command, original), applicationResources(fact) + sources.resources + recordingAuthority(command.actorId))
        return true
    }

    /** Pure retained reconciliation bookkeeping only. This acknowledges neither payout nor
     * source absence/unused rights. Corrupt/unsupported qualification still fails closed. */
    private suspend fun pending(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence, fact: Fact,
        before: Application, application: LocalRetailJournalApplicationV3, supervised: Boolean): Boolean {
        val reason = application.reason ?: refundHistoryUnavailable()
        check(application == before.value.copy(revision = before.value.revision.next(), reason = reason,
            updatedAt = original.recordedAt, lastApplicationOperationId = original.operationId))
        requireRetailApplicationOriginalV3(original, fact.value, application, fact.slot.value, null)
        var sources: RetailRefundHistorySources? = null
        var late = false
        when (reason) {
            LocalJournalPendingReasonV3.ORIGINAL_APPLICATION_REQUIRED -> {
                if (fact.value.phase != LocalJournalFactPhaseV3.RESOLUTION) return false
                val parent = factBefore(command, fact.value.target.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL)
                val app = applicationBefore(command, parent)
                check(app.value.state != LocalJournalApplicationStateV3.APPLIED || app.value.appliedAction != LocalSalesAction.REFUND_UNKNOWN)
            }
            LocalJournalPendingReasonV3.FINANCIAL_SOURCE_MISSING -> {
                val target = fact.value.target
                val keys = listOf(LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, checkNotNull(target.originalSaleId)),
                    LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, checkNotNull(target.originalSaleId)),
                    LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, checkNotNull(target.originalClaimId)),
                    LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_KIND, target.documentId), CashNonSaleStoreProfile.sessionKey(target.sessionId))
                var absent = false
                for (key in keys) if (command.aggregate(key) == null) absent = true
                if (!absent) return false // No guessed missing policy/receipt/custody or swallowed malformed source.
            }
            LocalJournalPendingReasonV3.ORIGINAL_ATTEMPT_MISMATCH,
            LocalJournalPendingReasonV3.FINANCIAL_STATE_REQUIRES_RECONCILIATION,
            LocalJournalPendingReasonV3.NUMERIC_CAPACITY_REQUIRED,
            LocalJournalPendingReasonV3.STORAGE_CAPACITY_REQUIRED -> {
                if (fact.value.phase == LocalJournalFactPhaseV3.RESOLUTION) unknownParent(command, fact)
                sources = retailRefundHistorySources(command, fact.value.target)
                val attemptMatches = attemptMatches(command, fact, sources)
                val conflict = financialClaimConflict(command, fact.value.target)
                when {
                    !attemptMatches -> check(reason == LocalJournalPendingReasonV3.ORIGINAL_ATTEMPT_MISMATCH)
                    conflict -> check(reason == LocalJournalPendingReasonV3.FINANCIAL_STATE_REQUIRES_RECONCILIATION)
                    else -> {
                        check(reason != LocalJournalPendingReasonV3.ORIGINAL_ATTEMPT_MISMATCH)
                        late = true // The real transition was authorized but deferred; no money/rights effect is accepted.
                    }
                }
            }
            else -> return false // In particular malformed sources are never recast as a clean historical proof.
        }
        val target = if (late) checkNotNull(sources).returned.accepted.value.id else fact.slot.value.id
        val revision = if (late) checkNotNull(sources).returned.accepted.value.revision else Revision(0)
        command.requireRequirement(salesAuthorizationRequirement(retailFactAction(SalesRecordingKind.REFUND_ATTESTATION,
            if (supervised) LocalJournalFactPhaseV3.RESOLUTION else fact.value.phase), command.scope, target, revision,
            command.operationId, command.semanticDigest, if (late) "financial.journal.refund.apply" else "financial.journal.pending",
            fact.slot.value.actorId, fact.slot.value.domain.amount))
        command.requireLegacyEffects(literalEffects(command, original), applicationResources(fact) + sources?.resources.orEmpty() + recordingAuthority(command.actorId))
        return true
    }

    private suspend fun attemptMatches(command: RuntimeHistoricalCommand, fact: Fact, sources: RetailRefundHistorySources): Boolean {
        val target = fact.value.target
        val accepted = sources.returned.accepted.value
        val cash = sources.cash.session.value
        val attempt = accepted.attempts.singleOrNull { it.id == target.attemptId } ?: return false
        val parent = if (fact.value.phase == LocalJournalFactPhaseV3.RESOLUTION)
            factBefore(command, target.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL) else null
        return accepted.saleId == target.originalSaleId && accepted.claimId == target.originalClaimId &&
            attempt.originalOperationId == target.intentOperationId && attempt.originalActorId == fact.slot.value.actorId &&
            cash.custodianId == fact.slot.value.actorId && attempt.originalCashSessionId == target.sessionId && attempt.originalPaymentId == target.originalPaymentId &&
            attempt.amount == fact.slot.value.domain.amount && tenderView(attempt.tender) == fact.slot.value.domain.tender &&
            attempt.status == (if (fact.value.phase == LocalJournalFactPhaseV3.ORIGINAL) AttemptStatus.AWAITING_ATTESTATION else AttemptStatus.OUTCOME_UNKNOWN) &&
            attempt.id in cash.pendingAttemptIds && command.claim(LocalSalesStoreProfile.unique("refund_attempt", attempt.id)) == accepted.id &&
            (if (parent == null) attempt.latestObservation == null else attempt.latestObservation == retailRefundObservation(parent.value).journalOutcome(parent.value.recordingActorId))
    }

    private suspend fun financialClaimConflict(command: RuntimeHistoricalCommand, target: LocalJournalTargetV3): Boolean =
        command.claim(LocalSalesStoreProfile.unique("refund_record", target.attemptId)) != null ||
            command.claim(CashNonSaleStoreProfile.cashSourceClaim(target.attemptId)) != null ||
            command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, target.attemptId)) != null

    private fun applicationResources(fact: Fact): Set<RecoveryResourceKey> =
        fact.slot.value.domain.financialResources.toSet() + retailJournalAuthorityResources(fact.slot.value.requirement())

    private suspend fun requireSourceImages(command: RuntimeHistoricalCommand, images: List<LocalRetailBeforeV3>, sources: RetailRefundHistorySources) {
        check(images.size == retailRefundBeforeRolesV3.size && images.map { it.role }.toSet() == retailRefundBeforeRolesV3)
        for (image in images) {
            val row = sources.rows.single { it.first == image.role }.second
            check(image.scope.value() == command.scope && image.capturingOperationId == command.operationId && image.sourceKind == row.key.kind &&
                image.sourceId == row.key.id && image.storageRevision == row.revision && image.sourceSchema == row.schema &&
                image.sourceDigest == row.digest.value && image.body() == row.payload)
        }
    }

    private fun requireNativeSources(p: NativeOriginalJournalProvenanceV1,
        sources: Pair<LocalPhysicalJournalSourceProjection, LocalPhysicalJournalSourceProjection>) {
        for ((actual, retained) in listOf(sources.first, sources.second).zip(listOf(p.nativeOriginal.masterSource, p.nativeOriginal.clientSource))) {
            check(retained.role == (if (actual.owner.role == LocalPhysicalSpoolRole.MASTER) NativeOriginalJournalProvenanceV1.SourceRole.MASTER
                else NativeOriginalJournalProvenanceV1.SourceRole.CLIENT))
            check(retained.installationId == actual.owner.installationId && retained.deviceId == actual.owner.deviceId &&
                retained.catalogueId == actual.owner.catalogueId && retained.keyId == actual.owner.keyId && retained.allocationId == actual.allocationId &&
                retained.nativeSlotIndex == actual.slotIndex && retained.sourcePayloadDigest == retailJournalDigestV3(actual.sourceCommitment) &&
                retained.headerDigest == actual.headerDigest && retained.allocationDigest == actual.allocationDigest &&
                retained.nativeIdentityDigest == sha256(journalNativeBytes(actual.nativeIdentity)))
        }
    }

    /** Expected inert effects ONLY after the complete literal business producer validation.
     * Core independently checks the exact native mutations, resources, claims and final clock. */
    private suspend fun literalEffects(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence): ProposedOutcome.Apply {
        check(original.jobs.isEmpty() && original.aggregateWrites.none { it.key.kind == StockReadEvidenceV1.KIND || it.key.kind in StockRelationalKindsV2.all })
        val changes = original.aggregateWrites.map { write ->
            val before = command.aggregate(write.key)
            check(write.storageGeneration == (before?.revision?.next() ?: Revision(1)))
            AggregateChange(write.key, before?.revision, write.schema, write.payload)
        }
        val claims = original.claimChanges.map { if (it.action == RetainedClaimAction.ACQUIRE) ClaimChange.Acquire(it.key, it.ownerId)
            else ClaimChange.Release(it.key, it.ownerId) }
        return ProposedOutcome.Apply(original.result, changes, claims)
    }
}
