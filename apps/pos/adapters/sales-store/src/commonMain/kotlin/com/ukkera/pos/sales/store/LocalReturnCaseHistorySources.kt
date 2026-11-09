package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.RetainedAggregateWrite
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalAggregateSource
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Native BEFORE sources for CASE intake, qualification and acceptance history.
 * Every present row is selected by core aggregateSource, with its exact already fully joined original.
 * Immediate predecessor images come from earlierOriginal in the same active native scan;
 * no SQL/current-view adapter, supplied retained reader or pointer-only proof is accepted.
 * These inert rows retain the existing bounded CASE/work reader profile and dependencies. */
internal class LocalReturnCaseHistorySources(private val command: RuntimeHistoricalCommand) {
    private val originals = linkedMapOf<OperationId, RetainedCommandEvidence>()
    private val events = linkedMapOf<OperationId, EventSource>()

    private class EventSource(val row: LocalSalesRow<LocalReturnCaseEventV1>,
        val original: RetainedCommandEvidence, val request: LocalReturnCaseRequestV1)

    suspend fun recordDuplicate(caseId: DomainId): Boolean =
        command.aggregate(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, caseId)) != null ||
            command.claim(LocalReturnCaseProfileV1.source("case", caseId)) != null ||
            command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_INTAKE_KIND, caseId)) != null ||
            command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, caseId)) != null ||
            command.claim(LocalSalesStoreProfile.unique("return_intake", caseId)) != null ||
            command.claim(LocalSalesStoreProfile.unique("return_claim", caseId)) != null ||
            command.claim(LocalReturnCaseProfileV1.source("claim", caseId)) != null

    suspend fun claimExists(caseId: DomainId): Boolean =
        command.claim(LocalSalesStoreProfile.unique("return_claim", caseId)) != null ||
            command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, caseId)) != null ||
            command.claim(LocalReturnCaseProfileV1.source("claim", caseId)) != null

    suspend fun acceptanceDuplicate(request: LocalReturnCaseRequestV1.Accept): Boolean =
        command.claim(LocalSalesStoreProfile.unique("accepted_return", request.returnId)) != null ||
            command.claim(LocalSalesStoreProfile.unique("claim_acceptance", request.caseId)) != null ||
            command.claim(LocalSalesStoreProfile.unique("return_claim_source", request.returnId)) != null ||
            command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_KIND, request.returnId)) != null

    suspend fun requireNewEvent() {
        val id = DomainId(command.operationId.value)
        check(command.aggregate(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.EVENT, id)) == null &&
            command.claim(LocalReturnCaseProfileV1.source("event", id)) == null &&
            listOf(LocalReturnCaseProfileV1.ENTITLEMENT_BEFORE, LocalReturnCaseProfileV1.CLAIM_BEFORE, LocalReturnCaseProfileV1.WORK_BEFORE)
                .all { command.aggregate(LocalReturnCaseProfileV1.key(it, id)) == null })
    }

    suspend fun findWork(ownerId: DomainId, dependencies: LocalReturnCaseDependencies): LocalSalesRow<LocalReturnCaseWorkV1>? {
        val key = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.WORK, ownerId)
        val source = command.aggregateSource(key)
        val claim = command.claim(LocalReturnCaseProfileV1.source("work", ownerId))
        if (source == null) {
            check(claim == null)
            return null
        }
        val origin = sourceOriginal(source)
        val row = source.row
        check(claim == ownerId && row.schema == LocalSalesStoreProfile.schema(key.kind) && row.key == key)
        val value = decodeLocalSalesSource(row.payload, LocalReturnCaseWorkV1.serializer())
        validateReturnCaseWork(value)
        check(value.scope.value() == command.scope && value.ownerId == ownerId && value.revision == row.domainRevision() &&
            value.lastOperationId == origin.operationId)
        val event = loadEvent(value.lastOperationId)
        check(event.original === origin && event.row.value.after.ownerId == ownerId &&
            value.entries.singleOrNull { it.caseId == event.row.value.after.caseId } ==
                LocalReturnCaseWorkEntryV1(event.row.value.after.caseId, event.row.value.after.revision, event.row.value.after.stage))
        addEventDependencies(event, dependencies, indexOnly = true)
        return LocalSalesRow(row, value)
    }

    suspend fun readCase(caseId: DomainId,
        dependencies: LocalReturnCaseDependencies = LocalReturnCaseDependencies()): LocalReturnCaseCut {
        val key = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, caseId)
        val source = command.aggregateSource(key) ?: error("sales.return.case_history.source_missing")
        val origin = sourceOriginal(source)
        val row = source.row
        check(row.key == key && row.schema == LocalSalesStoreProfile.schema(key.kind))
        val value = decodeLocalSalesSource(row.payload, LocalReturnCaseStateV1.serializer())
        validateReturnCaseState(value)
        check(value.scope.value() == command.scope && value.caseId == caseId &&
            command.claim(LocalReturnCaseProfileV1.source("case", caseId)) == value.ownerId &&
            value.revision == row.domainRevision() && value.lastOperationId == origin.operationId &&
            command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_INTAKE_KIND, caseId)) == null &&
            command.claim(LocalSalesStoreProfile.unique("return_intake", caseId)) == null)
        val current = readEvent(value.lastOperationId, dependencies)
        check(current.value.after == value && loadEvent(value.lastOperationId).original === origin)
        val first = if (value.originalOperationId == value.lastOperationId) current else readEvent(value.originalOperationId, dependencies)
        check(first.value.action == LocalReturnCaseActionV1.RECORDED && first.value.after.caseId == caseId &&
            first.value.after.ownerId == value.ownerId && first.value.after.scope == value.scope &&
            first.value.after.facts == value.facts && first.value.after.recordedAt == value.recordedAt)
        val work = findWork(value.ownerId, dependencies) ?: error("sales.return.case_history.work_missing")
        check(work.value.entries.singleOrNull { it.caseId == caseId } == LocalReturnCaseWorkEntryV1(caseId, value.revision, value.stage))
        val binding = value.bindingOperationId?.let { operationId ->
            val selected = if (operationId == value.lastOperationId) current else readEvent(operationId, dependencies)
            requireBinding(value, loadEvent(operationId))
            selected
        }
        val decision = value.decisionOperationId?.let { operationId ->
            val selected = if (operationId == value.lastOperationId) current else readEvent(operationId, dependencies)
            check(loadEvent(operationId).request is LocalReturnCaseRequestV1.Qualify && binding != null &&
                selected.value.after.caseId == caseId && selected.value.assessment != null &&
                selected.value.assessedBindingDigest == binding.stored.digest)
            selected
        }
        if (value.stage == LocalReturnCaseStageV1.PENDING) check(value.acceptedReturnId == null &&
            command.claim(LocalReturnCaseProfileV1.source("claim", caseId)) == null &&
            command.claim(LocalSalesStoreProfile.unique("return_claim", caseId)) == null &&
            command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, caseId)) == null)
        else check(decision != null && decision.value.action == LocalReturnCaseActionV1.BOUND &&
            decision.value.assessment?.outcome == LocalReturnOrdinaryAssessmentV1.ELIGIBLE &&
            value.facts.requestedRemedy == LocalReturnRequestedRemedyV1.REFUND &&
            command.claim(LocalReturnCaseProfileV1.source("claim", caseId)) == caseId)
        return LocalReturnCaseCut(LocalSalesRow(row, value), current, work, binding, decision, dependencies.snapshot())
    }

    suspend fun readClaim(originalSale: LocalEntitlementCut, caseId: DomainId): LocalClaimCut {
        val dependencies = LocalReturnCaseDependencies()
        val cut = readCase(caseId, dependencies)
        val state = cut.row.value
        val binding = returnCaseRequired(cut.binding?.value?.binding)
        val decision = returnCaseRequired(cut.decision?.value)
        val assessment = returnCaseRequired(decision.assessment)
        check(state.stage != LocalReturnCaseStageV1.PENDING && binding.saleId == originalSale.sale.value.id &&
            command.claim(LocalReturnCaseProfileV1.source("claim", caseId)) == caseId &&
            command.claim(LocalSalesStoreProfile.unique("return_claim", caseId)) == binding.saleId)
        requireReturnCaseHistoryPolicyForSale(command, originalSale, binding, assessment)
        val key = LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, caseId)
        val source = command.aggregateSource(key) ?: error("sales.return.case_history.claim_missing")
        val row = source.row
        check(row.key == key && row.schema == LocalSalesStoreProfile.schema(key.kind))
        val value = SalesSnapshotCodec.decodeReturnClaim(row.text(),
            SalesSnapshotBinding(SalesSnapshotKind.RETURN_CLAIM, command.scope, caseId, row.domainRevision()), originalSale.entitlement.value)
        check(value.claimantOperatorId == state.ownerId && value.portionId == binding.portionId &&
            value.quantity == binding.quantity && value.acceptedReturnId == state.acceptedReturnId &&
            value.evidence == returnCaseQualifiedClaimEvidence(state, assessment, decision.ordinaryInspection))
        val operation = if (state.acceptedReturnId == null) returnCaseRequired(state.decisionOperationId) else state.lastOperationId
        val producer = evidence(operation, dependencies)
        check(sourceOriginal(source) === producer)
        return LocalClaimCut(originalSale, null, LocalSalesRow(row, value),
            returnCaseResources(cut) + dependencies.snapshot() + entitlementSourceResources(originalSale))
    }

    suspend fun evidence(operationId: OperationId,
        dependencies: LocalReturnCaseDependencies = LocalReturnCaseDependencies()): RetainedCommandEvidence =
        original(operationId).also { producerDependency(it, dependencies) }

    internal suspend fun readEvent(operationId: OperationId, dependencies: LocalReturnCaseDependencies): LocalSalesRow<LocalReturnCaseEventV1> {
        val event = loadEvent(operationId)
        addEventDependencies(event, dependencies, indexOnly = false)
        requireReturnCaseHistorySources(command, event.row.value.qualificationSources)
        check(event.row.value.assessment?.sources().orEmpty().all { it in event.row.value.qualificationSources } &&
            event.row.value.sourceRecoveryResources.containsAll(returnCaseSourceResources(event.row.value.qualificationSources)))
        return event.row
    }

    private suspend fun loadEvent(operationId: OperationId): EventSource {
        events[operationId]?.let { return it }
        val key = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.EVENT, DomainId(operationId.value))
        val source = command.aggregateSource(key) ?: error("sales.return.case_history.event_missing")
        val original = sourceOriginal(source)
        val request = LocalReturnCaseCommandCodecV1.decode(original.normalized)
        val row = source.row
        val value = decodeLocalSalesSource(row.payload, LocalReturnCaseEventV1.serializer())
        validateReturnCaseState(value.after)
        check(row.key == key && row.revision == Revision(1) && row.schema == LocalSalesStoreProfile.schema(key.kind) &&
            row.digest == sha256(row.payload.copyBytes()) && original.operationId == operationId &&
            value.operationId == operationId && value.after.lastOperationId == operationId && value.after.scope.value() == command.scope &&
            value.after.ownerId == original.originalActorId && value.after.caseId == request.caseId() &&
            value.recordedAt == original.recordedAt && command.claim(LocalReturnCaseProfileV1.source("event", DomainId(operationId.value))) == value.after.caseId &&
            original.result == LocalReturnCaseCommandCodecV1.result(value.result) &&
            value.result.caseId == value.after.caseId && value.result.caseRevision == value.after.revision &&
            value.result.stage == value.after.stage && value.result.action == value.action && value.result.eventOperationId == operationId &&
            value.result.returnId == value.after.acceptedReturnId &&
            value.sourceRecoveryResources.size <= 128 && value.sourceRecoveryResources.distinct().size == value.sourceRecoveryResources.size &&
            value.sourceRecoveryResources == value.sourceRecoveryResources.sortedWith(compareBy({ it.kind.name }, { it.resourceId.value })) &&
            value.result.entitlementRevision == value.entitlementRevision)
        if (request !is LocalReturnCaseRequestV1.Qualify) check(value.assessment == null &&
            value.assessedBindingDigest == null && value.qualificationSources.isEmpty() && value.sourceRecoveryResources.isEmpty() &&
            value.ordinaryInspection == null)
        else check(value.ordinaryInspection == request.ordinaryInspection)
        if (request !is LocalReturnCaseRequestV1.Accept) check(value.beforeClaimDigest == null &&
            value.beforeCashWorkDigest == null && value.result.originalPayable == null)
        if (request is LocalReturnCaseRequestV1.Record || request is LocalReturnCaseRequestV1.AppendReceiptEvidence) check(
            value.after.stage == LocalReturnCaseStageV1.PENDING && value.after.acceptedReturnId == null &&
            value.after.decisionOperationId == null && value.expectedEntitlementRevision == null &&
            value.entitlementRevision == null && value.beforeEntitlementDigest == null && value.result.claimRevision == null)
        check(when (request) {
            is LocalReturnCaseRequestV1.Record -> value.action == LocalReturnCaseActionV1.RECORDED && value.after.facts == request.facts &&
                value.after.originalOperationId == operationId && value.after.revision == Revision(0) && value.after.events == listOf(operationId) &&
                value.after.recordedAt == value.recordedAt && value.binding == initialReturnCaseBinding(request.facts)
            is LocalReturnCaseRequestV1.AppendReceiptEvidence -> value.action == LocalReturnCaseActionV1.EVIDENCE_APPENDED &&
                value.after.revision == request.expectedCaseRevision.next() && value.binding == request.binding && value.after.bindingOperationId == operationId
            is LocalReturnCaseRequestV1.Qualify -> supportedQualificationProfile(value, request, operationId)
            is LocalReturnCaseRequestV1.Accept -> value.action == LocalReturnCaseActionV1.ACCEPTED &&
                value.after.stage == LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING && value.after.acceptedReturnId == request.returnId &&
                value.after.revision == request.expectedCaseRevision.next() && value.after.decisionOperationId == request.decisionOperationId &&
                value.expectedEntitlementRevision == request.expectedEntitlementRevision &&
                value.entitlementRevision == request.expectedEntitlementRevision.next() && value.beforeEntitlementDigest != null &&
                value.beforeClaimDigest != null && value.result.claimRevision == request.expectedClaimRevision.next() &&
                value.result.originalPayable != null
        })
        return EventSource(LocalSalesRow(row, value), original, request).also { events[operationId] = it }
    }

    /** Preserve the existing immediate index-producer profile, including another case's
     * before-image and index predecessor. Both entire originals have completed the installed
     * core/producer join in this same native scan; their literal writes are also matched here. */
    private suspend fun addEventDependencies(source: EventSource, dependencies: LocalReturnCaseDependencies, indexOnly: Boolean) {
        val event = source.row.value
        val after = event.after
        producerDependency(source.original, dependencies)
        val caseKey = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, after.caseId)
        val before = event.beforeCaseOperationId?.let { operationId ->
            val origin = original(operationId)
            producerDependency(origin, dependencies)
            val write = origin.write(caseKey)
            check(write.schema == LocalSalesStoreProfile.schema(caseKey.kind) && write.payloadDigest == event.beforeCaseDigest)
            decodeLocalSalesSource(write.payload, LocalReturnCaseStateV1.serializer()).also {
                validateReturnCaseState(it)
                check(it.lastOperationId == operationId && write.storageGeneration == it.revision.next() &&
                    it.scope == after.scope && it.caseId == after.caseId && it.ownerId == after.ownerId &&
                    it.facts == after.facts && it.recordedAt == after.recordedAt && it.originalOperationId == after.originalOperationId &&
                    it.revision.next() == after.revision && source.request.expectedRevision() == it.revision && after.events == it.events + event.operationId)
            }
        }
        check((before == null) == (event.beforeCaseDigest == null) && (before == null) == (source.request is LocalReturnCaseRequestV1.Record))
        val workKey = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.WORK, after.ownerId)
        val indexWrite = source.original.write(workKey)
        val index = decodeLocalSalesSource(indexWrite.payload, LocalReturnCaseWorkV1.serializer())
        validateReturnCaseWork(index)
        check(indexWrite.schema == LocalSalesStoreProfile.schema(workKey.kind) && indexWrite.storageGeneration == index.revision.next() &&
            index.scope == after.scope && index.ownerId == after.ownerId && index.lastOperationId == event.operationId)
        val priorIndex = event.beforeIndexOperationId?.let { operationId ->
            val origin = original(operationId)
            producerDependency(origin, dependencies)
            val write = origin.write(workKey)
            check(write.schema == indexWrite.schema && write.payloadDigest == event.beforeIndexDigest)
            decodeLocalSalesSource(write.payload, LocalReturnCaseWorkV1.serializer()).also {
                validateReturnCaseWork(it)
                check(it.scope == after.scope && it.ownerId == after.ownerId && it.lastOperationId == operationId && write.storageGeneration == it.revision.next())
            }
        }
        check((priorIndex == null) == (event.beforeIndexDigest == null) && index.revision == (priorIndex?.revision?.next() ?: Revision(0)) &&
            index.entries == (priorIndex?.entries.orEmpty().filterNot { it.caseId == after.caseId } +
                LocalReturnCaseWorkEntryV1(after.caseId, after.revision, after.stage)).sortedBy { it.caseId.value })
        if (before != null) check(priorIndex?.entries?.singleOrNull { it.caseId == before.caseId } ==
            LocalReturnCaseWorkEntryV1(before.caseId, before.revision, before.stage))
        else check(priorIndex?.entries.orEmpty().none { it.caseId == after.caseId })
        if (!indexOnly && source.request is LocalReturnCaseRequestV1.AppendReceiptEvidence) {
            val previous = returnCaseRequired(before)
            requireReturnCaseBinding(after.facts, source.request.binding)
            previous.bindingOperationId?.let { operationId ->
                val bindingSource = loadEvent(operationId)
                producerDependency(bindingSource.original, dependencies)
                requireBinding(previous, bindingSource)
                val old = returnCaseRequired(bindingSource.row.value.binding)
                check(old.saleId == source.request.binding.saleId && old.portionId == source.request.binding.portionId &&
                    old.quantity == source.request.binding.quantity)
            }
        }
        if (!indexOnly && source.request is LocalReturnCaseRequestV1.Qualify) {
            val previous = returnCaseRequired(before)
            val bindingOperationId = returnCaseRequired(previous.bindingOperationId)
            val bindingSource = loadEvent(bindingOperationId)
            producerDependency(bindingSource.original, dependencies)
            requireBinding(previous, bindingSource)
            check(previous.stage == LocalReturnCaseStageV1.PENDING && after.bindingOperationId == bindingOperationId &&
                event.assessedBindingDigest == bindingSource.row.stored.digest)
            if (event.entitlementRevision != null) {
                val binding = returnCaseRequired(bindingSource.row.value.binding)
                val witnessKey = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.ENTITLEMENT_BEFORE, DomainId(event.operationId.value))
                val witness = command.aggregateSource(witnessKey) ?: error("sales.return.case_history.witness_missing")
                check(sourceOriginal(witness) === source.original && witness.row.revision == Revision(1) &&
                    witness.row.schema == LocalSalesStoreProfile.schema(witnessKey.kind) && witness.row.digest == event.beforeEntitlementDigest)
                val snapshot = SalesSnapshotCodec.decodeReceiptEntitlement(witness.row.text(),
                    SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, command.scope, binding.saleId, event.entitlementRevision))
                val sale = readReturnCaseHistorySaleSource(command, binding.saleId)
                check(SalesSnapshotCodec.encodePaidSale(snapshot.sale) == SalesSnapshotCodec.encodePaidSale(sale.sale.value))
                val captureResources = entitlementReturnPolicyResources(sale)
                if (captureResources.isNotEmpty()) {
                    // Match the live reader's invocation-local immutable capture dependencies;
                    // no current entitlement, source reference or grant is manufactured.
                    dependencies.qualifiedEvent(event.copy(sourceRecoveryResources = event.sourceRecoveryResources + captureResources))
                }
                for (capture in event.qualificationSources.filter { it.kind == LocalSalesReturnPolicyCaptureCodecV1.KIND }) {
                    val placeholder = LocalReturnCaseAssessmentV1(LocalReturnOrdinaryAssessmentV1.PENDING, "PREPARED", "PENDING",
                        salePolicyCapture = capture)
                    val promise = requireReturnCaseHistoryPolicyForSale(command, sale, binding, placeholder)
                    promise?.let { check(event.sourceRecoveryResources.containsAll(returnCaseSourceResources(listOf(it.promiseSource)))) }
                }
                event.assessment?.salePolicyCapture?.takeIf { it.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA }?.let {
                    requireReturnCaseHistoryPolicyForSale(command, sale, binding, checkNotNull(event.assessment))
                }
                val assessment = checkNotNull(event.assessment)
                val receiptEvents = if (assessment.originalCommercialTiming != null || assessment.ordinaryTiming != null)
                    readReturnCommercialReceiptEvents(command.scope, event.qualificationSources) { command.aggregate(it) } else emptyMap()
                requireReturnCommercialTiming(assessment, source.request, previous, binding, sale.returnPolicyCapture?.value,
                    snapshot.cursors.singleOrNull { it.portionId == binding.portionId }?.originalQuantity, receiptEvents)
                if (assessment.ordinaryTiming != null || after.facts.claimedReason != null || event.ordinaryInspection != null)
                    requireReturnOrdinaryTiming(assessment, source.request, previous, binding, sale.returnPolicyCapture?.value,
                        snapshot.cursors.singleOrNull { it.portionId == binding.portionId }?.originalQuantity, receiptEvents)
            } else check(event.assessment?.originalCommercialTiming == null && event.assessment?.ordinaryTiming == null)
            dependencies.qualifiedEvent(event)
        }
        if (!indexOnly && source.request is LocalReturnCaseRequestV1.Accept) {
            val request = source.request
            val previous = returnCaseRequired(before)
            val bindingSource = loadEvent(returnCaseRequired(previous.bindingOperationId))
            producerDependency(bindingSource.original, dependencies)
            requireBinding(previous, bindingSource)
            val binding = returnCaseRequired(bindingSource.row.value.binding)
            val decision = readEvent(request.decisionOperationId, dependencies)
            check(previous.stage == LocalReturnCaseStageV1.BOUND_UNACCEPTED && decision.value.after == previous &&
                decision.value.action == LocalReturnCaseActionV1.BOUND && previous.lastOperationId == request.decisionOperationId &&
                after.bindingOperationId == previous.bindingOperationId && after.decisionOperationId == previous.decisionOperationId)
            val entKey = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.ENTITLEMENT_BEFORE, DomainId(event.operationId.value))
            val claimKey = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CLAIM_BEFORE, DomainId(event.operationId.value))
            val entSource = command.aggregateSource(entKey) ?: error("sales.return.case_history.accept_entitlement_before_missing")
            val claimSource = command.aggregateSource(claimKey) ?: error("sales.return.case_history.accept_claim_before_missing")
            check(sourceOriginal(entSource) === source.original && sourceOriginal(claimSource) === source.original &&
                entSource.row.revision == Revision(1) && claimSource.row.revision == Revision(1) &&
                entSource.row.schema == LocalSalesStoreProfile.schema(entKey.kind) && claimSource.row.schema == LocalSalesStoreProfile.schema(claimKey.kind) &&
                entSource.row.digest == event.beforeEntitlementDigest && claimSource.row.digest == event.beforeClaimDigest)
            val entitlement = SalesSnapshotCodec.decodeReceiptEntitlement(entSource.row.text(),
                SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, command.scope, binding.saleId, request.expectedEntitlementRevision))
            val sale = readReturnCaseHistorySaleSource(command, binding.saleId)
            check(SalesSnapshotCodec.encodePaidSale(entitlement.sale) == SalesSnapshotCodec.encodePaidSale(sale.sale.value))
            val qualified = loadEvent(request.decisionOperationId).original.write(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, after.caseId))
            check(claimSource.row.payload == qualified.payload && claimSource.row.digest == qualified.payloadDigest &&
                qualified.storageGeneration == Revision(1) && request.expectedClaimRevision == Revision(0))
        }
    }

    private fun requireBinding(state: LocalReturnCaseStateV1, source: EventSource) {
        val event = source.row.value
        val operationId = event.operationId
        check(operationId in state.events && event.after.bindingOperationId == operationId &&
            event.after.scope == state.scope && event.after.caseId == state.caseId && event.after.ownerId == state.ownerId &&
            event.after.facts == state.facts && event.after.recordedAt == state.recordedAt &&
            event.after.originalOperationId == state.originalOperationId && event.after.events == state.events.take(event.after.events.size))
        val binding = when (val request = source.request) {
            is LocalReturnCaseRequestV1.Record -> {
                check(request.facts == state.facts && operationId == state.originalOperationId)
                returnCaseRequired(initialReturnCaseBinding(request.facts))
            }
            is LocalReturnCaseRequestV1.AppendReceiptEvidence -> request.binding
            else -> error("sales.return.case_history.binding_invalid")
        }
        check(event.binding == binding)
        requireReturnCaseBinding(state.facts, binding)
    }

    private suspend fun sourceOriginal(source: RuntimeHistoricalAggregateSource): RetainedCommandEvidence {
        val evidence = (source.original as? RetainedOriginalV2.Legacy)?.evidence ?: error("sales.return.case_history.original_unavailable")
        check(evidence === original(evidence.operationId))
        return evidence
    }

    private suspend fun original(operationId: OperationId): RetainedCommandEvidence {
        originals[operationId]?.let { return it }
        val evidence = (command.earlierOriginal(operationId) as? RetainedOriginalV2.Legacy)?.evidence
            ?: error("sales.return.case_history.original_missing")
        check(evidence.committed && evidence.nativeOriginalProvenance == null && evidence.scope == command.scope &&
            evidence.operationId == operationId && evidence.command == LocalReturnCaseProfileV1.COMMAND && evidence.jobs.isEmpty() &&
            LocalReturnCaseCommandCodecV1.normalize(evidence.normalized) == evidence.normalized &&
            semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest)
        check(when (val request = LocalReturnCaseCommandCodecV1.decode(evidence.normalized)) {
            is LocalReturnCaseRequestV1.Record, is LocalReturnCaseRequestV1.AppendReceiptEvidence -> true
            is LocalReturnCaseRequestV1.Qualify -> returnCaseHistorySupportedQualification(evidence, request)
            is LocalReturnCaseRequestV1.Accept -> true
        })
        return evidence.also { originals[operationId] = it }
    }

    private fun producerDependency(original: RetainedCommandEvidence, dependencies: LocalReturnCaseDependencies) {
        dependencies.producer(original.operationId, original.originalActorId, LocalReturnCaseCommandCodecV1.decode(original.normalized).caseId())
        dependencies.retained(original)
    }
}

/** Literal PENDING dispatch only. The current original still needs exact preparation and
 * complete producer/core comparison; prior originals must already have passed that join. */
internal fun returnCaseHistoryPendingQualification(original: RetainedCommandEvidence,
    request: LocalReturnCaseRequestV1.Qualify): Boolean {
    val key = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.EVENT, DomainId(original.operationId.value))
    val write = original.aggregateWrites.singleOrNull { it.key == key } ?: return false
    if (write.storageGeneration != Revision(1) || write.schema != LocalSalesStoreProfile.schema(key.kind)) return false
    return pendingQualificationProfile(decodeLocalSalesSource(write.payload, LocalReturnCaseEventV1.serializer()), request, original.operationId)
}

internal fun returnCaseHistorySupportedQualification(original: RetainedCommandEvidence,
    request: LocalReturnCaseRequestV1.Qualify): Boolean {
    val key = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.EVENT, DomainId(original.operationId.value))
    val write = original.aggregateWrites.singleOrNull { it.key == key } ?: return false
    if (write.storageGeneration != Revision(1) || write.schema != LocalSalesStoreProfile.schema(key.kind)) return false
    return supportedQualificationProfile(decodeLocalSalesSource(write.payload, LocalReturnCaseEventV1.serializer()), request, original.operationId)
}

/** Explicit additive ordinary profiles do not relax the original legacy PENDING discriminant.
 * Selection still needs genuine preparation and complete shared producer/core comparison. */
private fun supportedQualificationProfile(event: LocalReturnCaseEventV1,
    request: LocalReturnCaseRequestV1.Qualify, operationId: OperationId): Boolean =
    pendingQualificationProfile(event, request, operationId) || ordinaryQualificationProfile(event, request, operationId)

private fun ordinaryQualificationProfile(event: LocalReturnCaseEventV1,
    request: LocalReturnCaseRequestV1.Qualify, operationId: OperationId): Boolean {
    val assessment = event.assessment ?: return false
    val ordinaryInput = event.after.facts.claimedReason != null || request.ordinaryInspection != null
    val bound = assessment.outcome == LocalReturnOrdinaryAssessmentV1.ELIGIBLE
    if (!ordinaryInput || assessment.outcome == LocalReturnOrdinaryAssessmentV1.INELIGIBLE) return false
    return event.operationId == operationId && event.after.lastOperationId == operationId && event.after.caseId == request.caseId &&
        event.after.revision == request.expectedCaseRevision.next() && event.after.decisionOperationId == operationId &&
        event.after.bindingOperationId != null && event.after.acceptedReturnId == null && event.binding == null &&
        event.assessedBindingDigest != null && event.ordinaryInspection == request.ordinaryInspection &&
        event.expectedEntitlementRevision == request.expectedEntitlementRevision &&
        (event.entitlementRevision == null || event.entitlementRevision == request.expectedEntitlementRevision) &&
        (event.beforeEntitlementDigest == null) == (event.entitlementRevision == null) &&
        event.after.stage == (if (bound) LocalReturnCaseStageV1.BOUND_UNACCEPTED else LocalReturnCaseStageV1.PENDING) &&
        event.action == (if (bound) LocalReturnCaseActionV1.BOUND else LocalReturnCaseActionV1.ASSESSMENT_PENDING) &&
        (!bound || event.entitlementRevision != null && assessment.ordinaryTiming?.status == LocalReturnCommercialWindowStatusV1.WITHIN &&
            event.after.facts.claimedReason == LocalReturnClaimReasonV1.CHANGE_OF_MIND &&
            event.after.facts.requestedRemedy == LocalReturnRequestedRemedyV1.REFUND &&
            request.ordinaryInspection?.condition == LocalReturnInspectionConditionV1.UNCHANGED) &&
        event.qualificationSources.size <= 17 && event.qualificationSources.distinct().size == event.qualificationSources.size &&
        assessment.sources().all { it in event.qualificationSources } &&
        event.sourceRecoveryResources.size <= 128 && event.sourceRecoveryResources.distinct().size == event.sourceRecoveryResources.size &&
        event.sourceRecoveryResources == event.sourceRecoveryResources.sortedWith(compareBy({ it.kind.name }, { it.resourceId.value })) &&
        event.sourceRecoveryResources.containsAll(returnCaseSourceResources(event.qualificationSources)) &&
        (event.entitlementRevision != null || event.qualificationSources.isEmpty() && event.sourceRecoveryResources.isEmpty() && assessment.sources().isEmpty()) &&
        event.beforeClaimDigest == null && event.beforeCashWorkDigest == null && event.result.action == event.action &&
        event.result.stage == event.after.stage && event.result.claimRevision == (if (bound) Revision(0) else null) &&
        event.result.returnId == null && event.result.entitlementRevision == event.entitlementRevision && event.result.originalPayable == null
}

/** Source-backed PENDING remains bounded to the original native initial-entitlement
 * profile. Literal fields are a discriminant, never proof of source preparation or rights. */
private fun pendingQualificationProfile(event: LocalReturnCaseEventV1,
    request: LocalReturnCaseRequestV1.Qualify, operationId: OperationId): Boolean {
    val assessment = event.assessment ?: return false
    return event.operationId == operationId && event.after.lastOperationId == operationId && event.after.caseId == request.caseId &&
        event.after.revision == request.expectedCaseRevision.next() && event.after.decisionOperationId == operationId &&
        event.after.bindingOperationId != null && event.after.stage == LocalReturnCaseStageV1.PENDING && event.after.acceptedReturnId == null &&
        event.action == LocalReturnCaseActionV1.ASSESSMENT_PENDING && event.binding == null && event.assessedBindingDigest != null &&
        event.expectedEntitlementRevision == request.expectedEntitlementRevision &&
        event.ordinaryInspection == request.ordinaryInspection &&
        (event.entitlementRevision == null || event.entitlementRevision == Revision(0) && event.entitlementRevision == request.expectedEntitlementRevision) &&
        (event.beforeEntitlementDigest == null) == (event.entitlementRevision == null) &&
        assessment.outcome == LocalReturnOrdinaryAssessmentV1.PENDING &&
        event.qualificationSources.size <= 17 && event.qualificationSources.distinct().size == event.qualificationSources.size &&
        assessment.sources().all { it in event.qualificationSources } &&
        event.sourceRecoveryResources.size <= 128 && event.sourceRecoveryResources.distinct().size == event.sourceRecoveryResources.size &&
        event.sourceRecoveryResources == event.sourceRecoveryResources.sortedWith(compareBy({ it.kind.name }, { it.resourceId.value })) &&
        event.sourceRecoveryResources.containsAll(returnCaseSourceResources(event.qualificationSources)) &&
        (event.entitlementRevision != null || event.qualificationSources.isEmpty() && event.sourceRecoveryResources.isEmpty() && assessment.sources().isEmpty()) &&
        event.beforeClaimDigest == null && event.beforeCashWorkDigest == null && event.result.action == event.action &&
        event.result.stage == event.after.stage && event.result.claimRevision == null && event.result.returnId == null &&
        event.result.entitlementRevision == event.entitlementRevision && event.result.originalPayable == null
}

/** Exact original profile and genuine BEFORE capture resources, never source.prepare-null provenance.
 * It permits attempting the exact no-source-equivalent PENDING join, never accepting it: the
 * shared live producer/core still compare the full BEFORE cut, requirement, result/effects/resources. */
internal fun returnCaseHistoryUnavailableQualification(original: RetainedCommandEvidence,
    request: LocalReturnCaseRequestV1.Qualify, expectedSourceResources: Set<RecoveryResourceKey>): Boolean {
    val key = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.EVENT, DomainId(original.operationId.value))
    val write = original.aggregateWrites.singleOrNull { it.key == key } ?: return false
    if (write.storageGeneration != Revision(1) || write.schema != LocalSalesStoreProfile.schema(key.kind)) return false
    val event = decodeLocalSalesSource(write.payload, LocalReturnCaseEventV1.serializer())
    return event.sourceRecoveryResources == expectedSourceResources.sortedWith(compareBy({ it.kind.name }, { it.resourceId.value })) &&
        unavailableQualificationProfile(event, request, original.operationId)
}

/** New typed ordinary no-source-equivalent PENDING can retain an advanced, genuinely joined
 * entitlement. It does not widen the old initial-entitlement profile or infer prepare-null. */
internal fun returnCaseHistoryUnavailableOrdinaryQualification(original: RetainedCommandEvidence,
    request: LocalReturnCaseRequestV1.Qualify, expectedSourceResources: Set<RecoveryResourceKey>): Boolean {
    val key = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.EVENT, DomainId(original.operationId.value))
    val write = original.aggregateWrites.singleOrNull { it.key == key } ?: return false
    if (write.storageGeneration != Revision(1) || write.schema != LocalSalesStoreProfile.schema(key.kind)) return false
    val event = decodeLocalSalesSource(write.payload, LocalReturnCaseEventV1.serializer())
    return ordinaryQualificationProfile(event, request, original.operationId) &&
        event.action == LocalReturnCaseActionV1.ASSESSMENT_PENDING &&
        event.assessment == returnCaseUnavailableAssessment(event.entitlementRevision != null) &&
        event.qualificationSources.isEmpty() &&
        event.sourceRecoveryResources == expectedSourceResources.sortedWith(compareBy({ it.kind.name }, { it.resourceId.value }))
}

/** Literal no-source-equivalent PENDING discriminant, not historical source.prepare-null
 * proof or authority. Identical complete bytes/resources may match source-backed PENDING;
 * current originals still require the whole join, and prior originals must have passed it. */
private fun unavailableQualificationProfile(event: LocalReturnCaseEventV1,
    request: LocalReturnCaseRequestV1.Qualify, operationId: OperationId): Boolean =
    event.operationId == operationId && event.after.lastOperationId == operationId && event.after.caseId == request.caseId &&
        event.after.revision == request.expectedCaseRevision.next() && event.after.decisionOperationId == operationId &&
        event.after.stage == LocalReturnCaseStageV1.PENDING && event.after.acceptedReturnId == null &&
        event.action == LocalReturnCaseActionV1.ASSESSMENT_PENDING && event.binding == null && event.assessedBindingDigest != null &&
        event.ordinaryInspection == request.ordinaryInspection &&
        event.expectedEntitlementRevision == request.expectedEntitlementRevision &&
        (event.entitlementRevision == null || event.entitlementRevision == Revision(0) && event.entitlementRevision == request.expectedEntitlementRevision) &&
        (event.beforeEntitlementDigest == null) == (event.entitlementRevision == null) &&
        event.assessment == returnCaseUnavailableAssessment(event.entitlementRevision != null) && event.qualificationSources.isEmpty() &&
        (event.entitlementRevision != null || event.sourceRecoveryResources.isEmpty()) &&
        event.beforeClaimDigest == null && event.beforeCashWorkDigest == null && event.result.action == event.action &&
        event.result.stage == event.after.stage && event.result.claimRevision == null && event.result.returnId == null &&
        event.result.entitlementRevision == event.entitlementRevision && event.result.originalPayable == null

private fun RetainedCommandEvidence.write(key: AggregateKey): RetainedAggregateWrite =
    aggregateWrites.singleOrNull { it.key == key } ?: error("sales.return.case_history.write_missing")
