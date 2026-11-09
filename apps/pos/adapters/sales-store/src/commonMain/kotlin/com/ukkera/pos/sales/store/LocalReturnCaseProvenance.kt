package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedAggregateWrite
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.sales.ReturnClaim
import com.ukkera.pos.sales.ReturnDisposition
import com.ukkera.pos.sales.Returns
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

internal suspend fun returnCaseEvidence(view: CommandReadView, operationId: OperationId,
    dependencies: LocalReturnCaseDependencies): RetainedCommandEvidence {
    val evidence = view.retainedCommand(operationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(evidence.committed && evidence.scope == view.scope && evidence.operationId == operationId &&
        evidence.command == LocalReturnCaseProfileV1.COMMAND && evidence.jobs.isEmpty() &&
        semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest &&
        validateLocalSalesSource { LocalReturnCaseCommandCodecV1.normalize(evidence.normalized) } == evidence.normalized)
    dependencies.producer(operationId, evidence.originalActorId, LocalReturnCaseCommandCodecV1.decode(evidence.normalized).caseId())
    dependencies.retained(evidence)
    return evidence
}

/** Complete literal producer shape and immediate predecessor/delta, not an accepting command allowlist. */
internal suspend fun requireReturnCaseProducer(view: CommandReadView, row: StoredAggregate, event: LocalReturnCaseEventV1,
    dependencies: LocalReturnCaseDependencies): Unit = requireReturnCaseProducerCut(view, row, event, dependencies, indexOnly = false)

/** Index authority only: exact local predecessor/result/delta; never a qualified claim source. */
internal suspend fun requireReturnCaseWorkProducer(view: CommandReadView, row: StoredAggregate, event: LocalReturnCaseEventV1,
    dependencies: LocalReturnCaseDependencies): Unit = requireReturnCaseProducerCut(view, row, event, dependencies, indexOnly = true)

private suspend fun requireReturnCaseProducerCut(view: CommandReadView, row: StoredAggregate,
    event: LocalReturnCaseEventV1, dependencies: LocalReturnCaseDependencies, indexOnly: Boolean) {
    val evidence = returnCaseEvidence(view, event.operationId, dependencies)
    val request = LocalReturnCaseCommandCodecV1.decode(evidence.normalized)
    val after = event.after
    requireLocalSalesSource(request.caseId() == after.caseId && evidence.originalActorId == after.ownerId &&
        evidence.recordedAt == event.recordedAt && row.key.id.value == event.operationId.value &&
        evidence.result == LocalReturnCaseCommandCodecV1.result(event.result) &&
        event.result.caseId == after.caseId && event.result.caseRevision == after.revision &&
        event.result.stage == after.stage && event.result.action == event.action &&
        event.result.eventOperationId == event.operationId && event.result.returnId == after.acceptedReturnId &&
        event.result.entitlementRevision == event.entitlementRevision)
    val eventLimit = when (event.action) {
        LocalReturnCaseActionV1.ACCEPTED -> LocalReturnCaseProfileV1.MAX_EVENTS
        LocalReturnCaseActionV1.BOUND -> LocalReturnCaseProfileV1.MAX_EVENTS - 1
        else -> LocalReturnCaseProfileV1.MAX_EVENTS - 2
    }
    requireLocalSalesSource(after.events.size <= eventLimit)
    if (request !is LocalReturnCaseRequestV1.Qualify) requireLocalSalesSource(event.assessment == null &&
        event.assessedBindingDigest == null && event.qualificationSources.isEmpty() && event.sourceRecoveryResources.isEmpty() &&
        event.ordinaryInspection == null)
    else requireLocalSalesSource(event.ordinaryInspection == request.ordinaryInspection)
    if (request !is LocalReturnCaseRequestV1.Accept) requireLocalSalesSource(event.beforeClaimDigest == null &&
        event.beforeCashWorkDigest == null && event.result.originalPayable == null)
    if (request is LocalReturnCaseRequestV1.Record || request is LocalReturnCaseRequestV1.AppendReceiptEvidence) {
        requireLocalSalesSource(event.expectedEntitlementRevision == null && event.entitlementRevision == null &&
            event.beforeEntitlementDigest == null && event.result.claimRevision == null)
    }
    val keys = mutableSetOf(row.key, LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, after.caseId),
        LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.WORK, after.ownerId))
    val eventWrite = evidence.write(row.key)
    requireLocalSalesSource(eventWrite.storageGeneration == Revision(1) && eventWrite.schema == row.schema && eventWrite.payload == row.payload)
    val caseWrite = evidence.write(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, after.caseId))
    requireLocalSalesSource(caseWrite.storageGeneration == after.revision.next() && caseWrite.schema == LocalSalesStoreProfile.schema(caseWrite.key.kind) &&
        decodeLocalSalesSource(caseWrite.payload, LocalReturnCaseStateV1.serializer()) == after)
    val before = event.beforeCaseOperationId?.let { op ->
        val write = returnCaseEvidence(view, op, dependencies).write(caseWrite.key)
        requireLocalSalesSource(write.payloadDigest == event.beforeCaseDigest && write.schema == caseWrite.schema)
        decodeLocalSalesSource(write.payload, LocalReturnCaseStateV1.serializer()).also {
            validateReturnCaseState(it)
            requireLocalSalesSource(it.lastOperationId == op && write.storageGeneration == it.revision.next())
        }
    }
    requireLocalSalesSource((before == null) == (event.beforeCaseDigest == null) && (before == null) == (request is LocalReturnCaseRequestV1.Record))
    if (before != null) requireLocalSalesSource(before.scope == after.scope && before.caseId == after.caseId &&
        before.ownerId == after.ownerId && before.facts == after.facts && before.recordedAt == after.recordedAt &&
        before.originalOperationId == after.originalOperationId && before.revision.next() == after.revision &&
        request.expectedRevision() == before.revision && after.events == before.events + event.operationId)
    val indexWrite = evidence.write(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.WORK, after.ownerId))
    val index = decodeLocalSalesSource(indexWrite.payload, LocalReturnCaseWorkV1.serializer())
    validateReturnCaseWork(index)
    requireLocalSalesSource(index.scope == after.scope && index.ownerId == after.ownerId && index.lastOperationId == event.operationId &&
        indexWrite.schema == LocalSalesStoreProfile.schema(indexWrite.key.kind) && indexWrite.storageGeneration == index.revision.next())
    val priorIndex = event.beforeIndexOperationId?.let { op ->
        val write = returnCaseEvidence(view, op, dependencies).write(indexWrite.key)
        requireLocalSalesSource(write.payloadDigest == event.beforeIndexDigest && write.schema == indexWrite.schema)
        decodeLocalSalesSource(write.payload, LocalReturnCaseWorkV1.serializer()).also {
            validateReturnCaseWork(it)
            requireLocalSalesSource(it.lastOperationId == op && it.scope == after.scope && it.ownerId == after.ownerId && write.storageGeneration == it.revision.next())
        }
    }
    requireLocalSalesSource((priorIndex == null) == (event.beforeIndexDigest == null) &&
        index.revision == (priorIndex?.revision?.next() ?: Revision(0)) &&
        index.entries == (priorIndex?.entries.orEmpty().filterNot { it.caseId == after.caseId } +
            LocalReturnCaseWorkEntryV1(after.caseId, after.revision, after.stage)).sortedBy { it.caseId.value })
    if (before != null) requireLocalSalesSource(priorIndex?.entries?.singleOrNull { it.caseId == before.caseId } ==
        LocalReturnCaseWorkEntryV1(before.caseId, before.revision, before.stage))
    else requireLocalSalesSource(priorIndex?.entries.orEmpty().none { it.caseId == after.caseId })
    val claims = mutableListOf<ClaimChange>(ClaimChange.Acquire(LocalReturnCaseProfileV1.source("event", DomainId(event.operationId.value)), after.caseId))
    if (priorIndex == null) claims += ClaimChange.Acquire(LocalReturnCaseProfileV1.source("work", after.ownerId), after.ownerId)
    requireLocalSalesSource(when (request) {
        is LocalReturnCaseRequestV1.Record -> event.action == LocalReturnCaseActionV1.RECORDED &&
            after.stage == LocalReturnCaseStageV1.PENDING && after.facts == request.facts && after.recordedAt == event.recordedAt
        is LocalReturnCaseRequestV1.AppendReceiptEvidence -> event.action == LocalReturnCaseActionV1.EVIDENCE_APPENDED &&
            after.stage == LocalReturnCaseStageV1.PENDING && event.binding == request.binding
        is LocalReturnCaseRequestV1.Qualify -> event.action == LocalReturnCaseActionV1.BOUND && after.stage == LocalReturnCaseStageV1.BOUND_UNACCEPTED ||
            event.action == LocalReturnCaseActionV1.ASSESSMENT_PENDING && after.stage == LocalReturnCaseStageV1.PENDING
        is LocalReturnCaseRequestV1.Accept -> event.action == LocalReturnCaseActionV1.ACCEPTED &&
            after.stage == LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING && after.acceptedReturnId == request.returnId
    })
    if (indexOnly) return
    when (request) {
        is LocalReturnCaseRequestV1.Record -> {
            requireLocalSalesSource(after.revision == Revision(0) && after.originalOperationId == event.operationId &&
                after.recordedAt == event.recordedAt && after.facts == request.facts && after.events == listOf(event.operationId) &&
                after.stage == LocalReturnCaseStageV1.PENDING && event.action == LocalReturnCaseActionV1.RECORDED &&
                event.binding == initialReturnCaseBinding(request.facts) &&
                after.bindingOperationId == event.binding?.let { event.operationId } && after.decisionOperationId == null &&
                after.acceptedReturnId == null && event.assessment == null)
            claims += ClaimChange.Acquire(LocalReturnCaseProfileV1.source("case", after.caseId), after.ownerId)
        }
        is LocalReturnCaseRequestV1.AppendReceiptEvidence -> {
            val previous = returnCaseRequired(before)
            requireLocalSalesSource(previous.stage == LocalReturnCaseStageV1.PENDING && after.stage == previous.stage &&
                event.action == LocalReturnCaseActionV1.EVIDENCE_APPENDED && event.binding == request.binding &&
                after.bindingOperationId == event.operationId && after.decisionOperationId == null && after.acceptedReturnId == null && event.assessment == null)
            requireReturnCaseBinding(after.facts, request.binding)
            previous.bindingOperationId?.let { op ->
                val old = readReturnCaseBindingLiteral(view, op, previous, dependencies)
                requireLocalSalesSource(old.saleId == request.binding.saleId && old.portionId == request.binding.portionId && old.quantity == request.binding.quantity)
            }
        }
        is LocalReturnCaseRequestV1.Qualify -> {
            val previous = returnCaseRequired(before)
            val bindingOp = returnCaseRequired(previous.bindingOperationId)
            val bindingWrite = returnCaseEvidence(view, bindingOp, dependencies).write(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.EVENT, DomainId(bindingOp.value)))
            val binding = readReturnCaseBindingLiteral(view, bindingOp, previous, dependencies)
            val assessment = returnCaseRequired(event.assessment)
            requireLocalSalesSource(previous.stage == LocalReturnCaseStageV1.PENDING && after.bindingOperationId == bindingOp &&
                after.decisionOperationId == event.operationId && event.binding == null &&
                after.acceptedReturnId == null && event.assessedBindingDigest == bindingWrite.payloadDigest &&
                event.expectedEntitlementRevision == request.expectedEntitlementRevision &&
                (event.entitlementRevision == null || event.entitlementRevision == request.expectedEntitlementRevision))
            requireLocalSalesSource(assessment.salePolicyCapture == null ||
                assessment.salePolicyCapture.kind == LocalSalesReturnPolicyCaptureCodecV1.KIND &&
                    assessment.salePolicyCapture.schema in setOf(LocalSalesReturnPolicyCaptureCodecV1.SCHEMA, LocalSalesReturnPolicyCaptureCodecV2.SCHEMA))
            if (assessment.outcome != LocalReturnOrdinaryAssessmentV1.PENDING) requireLocalSalesSource(
                event.entitlementRevision != null && assessment.customerReceivedAt != null &&
                    assessment.customerReceivedAt <= returnCaseSubmissionTime(after, assessment) &&
                    returnCaseSubmissionTime(after, assessment) <= event.recordedAt)
            val original = if (event.entitlementRevision == null) {
                requireLocalSalesSource(event.beforeEntitlementDigest == null && event.qualificationSources.isEmpty() &&
                    event.sourceRecoveryResources.isEmpty() && assessment.sources().isEmpty() &&
                    assessment.outcome == LocalReturnOrdinaryAssessmentV1.PENDING)
                null
            } else {
                val observed = returnCaseWitnessWrite(view, evidence, LocalReturnCaseProfileV1.ENTITLEMENT_BEFORE)
                keys += observed.key
                requireLocalSalesSource(observed.schema == LocalSalesStoreProfile.schema(observed.key.kind) &&
                    observed.storageGeneration == Revision(1) && observed.payloadDigest == event.beforeEntitlementDigest)
                val snapshot = SalesSnapshotCodec.decodeReceiptEntitlement(observed.text(), SalesSnapshotBinding(
                    SalesSnapshotKind.RECEIPT_ENTITLEMENT, view.scope, binding.saleId, event.entitlementRevision))
                // This immutable witness is compared to the actual paid-sale/quote source.
                // It is not a grant from today's potentially advanced entitlement row.
                val current = readReturnSaleSource(view, binding.saleId)
                requireLocalSalesSource(SalesSnapshotCodec.encodePaidSale(snapshot.sale) == SalesSnapshotCodec.encodePaidSale(current.sale.value))
                val captureResources = entitlementReturnPolicyResources(current)
                if (captureResources.isNotEmpty()) {
                    // Resource-only projection for this invocation, never a persisted or admitted event.
                    dependencies.qualifiedEvent(event.copy(sourceRecoveryResources = event.sourceRecoveryResources + captureResources))
                }
                for (capture in event.qualificationSources.filter { it.kind == LocalSalesReturnPolicyCaptureCodecV1.KIND }) {
                    val portion = requireReturnCasePolicyForSale(view, current, binding,
                        LocalReturnCaseAssessmentV1(LocalReturnOrdinaryAssessmentV1.PENDING, "PREPARED", "PENDING", salePolicyCapture = capture))
                    portion?.let { requireLocalSalesSource(event.sourceRecoveryResources.containsAll(returnCaseSourceResources(listOf(it.promiseSource)))) }
                }
                // Prepared placeholders do not validate the later actual assessment. A V2
                // decision must still match the selected literal promise, or remain PENDING
                // for that exact original signed absence. Never manufacture eligibility.
                assessment.salePolicyCapture?.takeIf { it.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA }?.let {
                    requireReturnCasePolicyForSale(view, current, binding, assessment)
                }
                val receiptEvents = if (assessment.originalCommercialTiming != null || assessment.ordinaryTiming != null)
                    readReturnCommercialReceiptEvents(view.scope, event.qualificationSources) { view.aggregate(it) } else emptyMap()
                requireReturnCommercialTiming(assessment, request, previous, binding, current.returnPolicyCapture?.value,
                    snapshot.cursors.singleOrNull { it.portionId == binding.portionId }?.originalQuantity,
                    receiptEvents)
                if (assessment.ordinaryTiming != null || after.facts.claimedReason != null || event.ordinaryInspection != null)
                    requireReturnOrdinaryTiming(assessment, request, previous, binding, current.returnPolicyCapture?.value,
                        snapshot.cursors.singleOrNull { it.portionId == binding.portionId }?.originalQuantity, receiptEvents)
                snapshot
            }
            if (original == null) requireLocalSalesSource(assessment.originalCommercialTiming == null && assessment.ordinaryTiming == null)
            if (event.action == LocalReturnCaseActionV1.BOUND) {
                requireLocalSalesSource(assessment.outcome == LocalReturnOrdinaryAssessmentV1.ELIGIBLE &&
                    after.facts.requestedRemedy == LocalReturnRequestedRemedyV1.REFUND && after.stage == LocalReturnCaseStageV1.BOUND_UNACCEPTED)
                requireLocalSalesSource(original != null && assessment.customerReceivedAt != null &&
                    assessment.customerReceivedAt <= returnCaseSubmissionTime(after, assessment) &&
                    returnCaseSubmissionTime(after, assessment) <= event.recordedAt &&
                    (assessment.remedyDueAt == null || assessment.remedyDueAt >= returnCaseSubmissionTime(after, assessment)))
                val claimKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, after.caseId)
                keys += claimKey
                val claimWrite = evidence.write(claimKey)
                requireLocalSalesSource(claimWrite.storageGeneration == Revision(1) && claimWrite.schema == LocalSalesStoreProfile.schema(claimKey.kind))
                val claim = SalesSnapshotCodec.decodeReturnClaim(claimWrite.text(),
                    SalesSnapshotBinding(SalesSnapshotKind.RETURN_CLAIM, view.scope, after.caseId, Revision(0)), returnCaseRequired(original))
                requireLocalSalesSource(claim.acceptedReturnId == null && claim.saleId == binding.saleId &&
                    claim.portionId == binding.portionId && claim.quantity == binding.quantity && claim.claimantOperatorId == after.ownerId &&
                    claim.evidence == returnCaseQualifiedClaimEvidence(after, assessment, event.ordinaryInspection) &&
                    event.result.claimRevision == Revision(0))
                claims += ClaimChange.Acquire(LocalSalesStoreProfile.unique("return_claim", after.caseId), binding.saleId)
                claims += ClaimChange.Acquire(LocalReturnCaseProfileV1.source("claim", after.caseId), after.caseId)
            } else requireLocalSalesSource(event.action == LocalReturnCaseActionV1.ASSESSMENT_PENDING &&
                after.stage == LocalReturnCaseStageV1.PENDING && event.result.claimRevision == null &&
                !(assessment.outcome == LocalReturnOrdinaryAssessmentV1.ELIGIBLE && after.facts.requestedRemedy == LocalReturnRequestedRemedyV1.REFUND))
        }
        is LocalReturnCaseRequestV1.Accept -> {
            val previous = returnCaseRequired(before)
            requireLocalSalesSource(previous.stage == LocalReturnCaseStageV1.BOUND_UNACCEPTED &&
                after.stage == LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING && event.action == LocalReturnCaseActionV1.ACCEPTED &&
                previous.decisionOperationId == request.decisionOperationId && after.decisionOperationId == previous.decisionOperationId &&
                after.bindingOperationId == previous.bindingOperationId && after.acceptedReturnId == request.returnId &&
                event.binding == null && event.assessment == null && event.expectedEntitlementRevision == request.expectedEntitlementRevision &&
                event.entitlementRevision == request.expectedEntitlementRevision.next())
            requireReturnCaseAcceptance(view, evidence, event, request, previous,
                readReturnCaseBindingLiteral(view, returnCaseRequired(previous.bindingOperationId), previous, dependencies), keys, claims, dependencies)
        }
    }
    requireLocalSalesSource(evidence.aggregateWrites.size == keys.size && evidence.aggregateWrites.map { it.key }.toSet() == keys &&
        evidence.claimChanges.size == claims.size && claims.all { expected -> evidence.claimChanges.any {
            it.action == RetainedClaimAction.ACQUIRE && it.key == expected.key && it.ownerId == expected.ownerId
        } })
}

private suspend fun requireReturnCaseAcceptance(view: CommandReadView, evidence: RetainedCommandEvidence, event: LocalReturnCaseEventV1,
    request: LocalReturnCaseRequestV1.Accept, previous: LocalReturnCaseStateV1, binding: LocalReturnCaseReceiptBindingV1,
    keys: MutableSet<AggregateKey>, claims: MutableList<ClaimChange>, dependencies: LocalReturnCaseDependencies) {
    val saleId = binding.saleId
    val caseId = event.after.caseId
    val beforeEnt = returnCaseWitnessWrite(view, evidence, LocalReturnCaseProfileV1.ENTITLEMENT_BEFORE)
    val beforeClaim = returnCaseWitnessWrite(view, evidence, LocalReturnCaseProfileV1.CLAIM_BEFORE)
    val ent = evidence.write(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, saleId))
    val claim = evidence.write(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, caseId))
    val accepted = evidence.write(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_KIND, request.returnId))
    val requiredWrites = listOf(beforeEnt, beforeClaim, ent, claim, accepted)
    keys += requiredWrites.map { it.key }
    requireLocalSalesSource(requiredWrites.all { it.schema == LocalSalesStoreProfile.schema(it.key.kind) } &&
        beforeEnt.storageGeneration == Revision(1) && beforeClaim.storageGeneration == Revision(1) &&
        beforeEnt.payloadDigest == event.beforeEntitlementDigest && beforeClaim.payloadDigest == event.beforeClaimDigest &&
        request.expectedClaimRevision == Revision(0) && ent.storageGeneration == request.expectedEntitlementRevision.next().next() &&
        claim.storageGeneration == request.expectedClaimRevision.next().next() && accepted.storageGeneration == Revision(1))
    val decision = readReturnCaseEvent(view, request.decisionOperationId, dependencies)
    requireLocalSalesSource(decision.value.action == LocalReturnCaseActionV1.BOUND &&
        decision.value.after.scope == previous.scope && decision.value.after.caseId == caseId &&
        decision.value.after.ownerId == previous.ownerId && decision.value.after.facts == previous.facts &&
        decision.value.after.bindingOperationId == previous.bindingOperationId &&
        decision.value.after == previous && previous.lastOperationId == request.decisionOperationId)
    val qualifiedClaim = returnCaseEvidence(view, request.decisionOperationId, dependencies).write(claim.key)
    requireLocalSalesSource(qualifiedClaim.schema == claim.schema && qualifiedClaim.storageGeneration == Revision(1) &&
        beforeClaim.payload == qualifiedClaim.payload && beforeClaim.payloadDigest == qualifiedClaim.payloadDigest)
    val beforeValue = SalesSnapshotCodec.decodeReceiptEntitlement(beforeEnt.text(),
        SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, evidence.scope, saleId, request.expectedEntitlementRevision))
    val beforeClaimValue = SalesSnapshotCodec.decodeReturnClaim(beforeClaim.text(),
        SalesSnapshotBinding(SalesSnapshotKind.RETURN_CLAIM, evidence.scope, caseId, request.expectedClaimRevision), beforeValue)
    val afterValue = SalesSnapshotCodec.decodeReceiptEntitlement(ent.text(),
        SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, evidence.scope, saleId, request.expectedEntitlementRevision.next()))
    val afterClaim = SalesSnapshotCodec.decodeReturnClaim(claim.text(),
        SalesSnapshotBinding(SalesSnapshotKind.RETURN_CLAIM, evidence.scope, caseId, request.expectedClaimRevision.next()), afterValue)
    val returned = SalesSnapshotCodec.decodeAcceptedReturn(accepted.text(),
        SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, evidence.scope, request.returnId, Revision(0)), afterValue, afterClaim)
    requireLocalSalesSource(beforeClaimValue.acceptedReturnId == null && beforeClaimValue.quantity == binding.quantity &&
        beforeClaimValue.portionId == binding.portionId && afterClaim.acceptedReturnId == request.returnId &&
        unchangedCaseClaim(beforeClaimValue, afterClaim) && beforeClaimValue.claimantOperatorId == previous.ownerId &&
        returned.acceptingOperatorId == evidence.originalActorId && returned.requestedDisposition.name == request.disposition.name &&
        returned.inspectionEvidence == request.inspectionEvidence.domain(evidence.originalActorId) && returned.attempts.isEmpty() &&
        event.result.claimRevision == afterClaim.revision && event.result.entitlementRevision == afterValue.revision &&
        event.result.originalPayable == returned.originalPayable)
    val expected = Returns.originalAcceptanceValue(beforeValue, request.expectedEntitlementRevision, beforeClaimValue,
        request.expectedClaimRevision, request.returnId, ReturnDisposition.valueOf(request.disposition.name),
        request.inspectionEvidence.domain(evidence.originalActorId), evidence.originalActorId)
    requireLocalSalesSource(SalesSnapshotCodec.encodeReceiptEntitlement(afterValue) == SalesSnapshotCodec.encodeReceiptEntitlement(expected.entitlement) &&
        SalesSnapshotCodec.encodeReturnClaim(afterClaim) == SalesSnapshotCodec.encodeReturnClaim(expected.claim) &&
        SalesSnapshotCodec.encodeAcceptedReturn(returned, afterValue, afterClaim) ==
            SalesSnapshotCodec.encodeAcceptedReturn(expected.acceptedReturn, expected.entitlement, expected.claim))
    val original = readReturnSaleSource(view, saleId)
    requireLocalSalesSource(SalesSnapshotCodec.encodePaidSale(beforeValue.sale) == SalesSnapshotCodec.encodePaidSale(original.sale.value))
    requireReturnCaseEntitlementContribution(view, evidence, request, saleId, beforeEnt, ent, keys, claims)
    if (returned.originalPayable.minor > 0L) {
        val beforeWork = returnCaseWitnessWrite(view, evidence, LocalReturnCaseProfileV1.WORK_BEFORE)
        val work = evidence.write(LocalSalesStoreProfile.key(LocalSalesStoreProfile.WORK_KIND, evidence.originalActorId))
        keys += listOf(beforeWork.key, work.key)
        val first = decodeLocalSalesSource(beforeWork.payload, LocalWorkState.serializer())
        val second = decodeLocalSalesSource(work.payload, LocalWorkState.serializer())
        requireLocalSalesSource(listOf(first.orderIds, first.returnIds, first.pendingClaimIds, second.orderIds, second.returnIds, second.pendingClaimIds).all {
            it.size <= LocalSalesStoreProfile.MAX_OPEN_WORK && it.distinct().size == it.size
        })
        requireLocalSalesSource(beforeWork.storageGeneration == Revision(1) && beforeWork.payloadDigest == event.beforeCashWorkDigest &&
            beforeWork.schema == LocalSalesStoreProfile.schema(beforeWork.key.kind) && work.schema == LocalSalesStoreProfile.schema(work.key.kind) &&
            work.storageGeneration == second.revision.next() && first.scope.value() == evidence.scope && first.id == evidence.originalActorId &&
            request.returnId !in first.returnIds && second == first.copy(revision = first.revision.next(), returnIds = first.returnIds + request.returnId))
    } else requireLocalSalesSource(event.beforeCashWorkDigest == null)
    claims += ClaimChange.Acquire(LocalSalesStoreProfile.unique("accepted_return", request.returnId), saleId)
    claims += ClaimChange.Acquire(LocalSalesStoreProfile.unique("claim_acceptance", caseId), request.returnId)
    claims += ClaimChange.Acquire(LocalSalesStoreProfile.unique("return_claim_source", request.returnId), caseId)
}

private fun unchangedCaseClaim(before: ReturnClaim, after: ReturnClaim): Boolean =
    before.id == after.id && before.scope == after.scope && before.saleId == after.saleId &&
        before.portionId == after.portionId && before.quantity == after.quantity &&
        before.evidence == after.evidence && before.claimantOperatorId == after.claimantOperatorId

/** An immutable witness must still match its actual retained insert, not just an event's asserted hash. */
private suspend fun returnCaseWitnessWrite(view: CommandReadView, evidence: RetainedCommandEvidence, kind: String): RetainedAggregateWrite {
    val key = LocalReturnCaseProfileV1.key(kind, DomainId(evidence.operationId.value))
    val write = evidence.write(key)
    val row = LocalSalesStorage.required(view, key)
    requireLocalSalesSource(row.revision == Revision(1) && write.storageGeneration == row.revision &&
        write.schema == row.schema && write.payload == row.payload && write.payloadDigest == row.digest)
    return write
}

internal suspend fun readReturnCaseBindingLiteral(view: CommandReadView, operationId: OperationId,
    state: LocalReturnCaseStateV1, dependencies: LocalReturnCaseDependencies): LocalReturnCaseReceiptBindingV1 {
    val evidence = returnCaseEvidence(view, operationId, dependencies)
    val write = evidence.write(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.EVENT, DomainId(operationId.value)))
    val event = decodeLocalSalesSource(write.payload, LocalReturnCaseEventV1.serializer())
    val request = LocalReturnCaseCommandCodecV1.decode(evidence.normalized)
    validateReturnCaseState(event.after)
    requireLocalSalesSource(operationId in state.events && evidence.originalActorId == state.ownerId && request.caseId() == state.caseId &&
        write.storageGeneration == Revision(1) && write.schema == LocalSalesStoreProfile.schema(write.key.kind) &&
        event.operationId == operationId && event.after.lastOperationId == operationId && event.after.bindingOperationId == operationId &&
        event.after.scope == state.scope && event.after.caseId == state.caseId && event.after.ownerId == state.ownerId &&
        event.after.facts == state.facts && event.after.recordedAt == state.recordedAt &&
        event.after.originalOperationId == state.originalOperationId && event.after.events == state.events.take(event.after.events.size))
    val binding = when (request) {
        is LocalReturnCaseRequestV1.Record -> {
            requireLocalSalesSource(request.facts == state.facts && operationId == state.originalOperationId &&
                event.action == LocalReturnCaseActionV1.RECORDED && event.after.revision == Revision(0))
            returnCaseRequired(initialReturnCaseBinding(request.facts))
        }
        is LocalReturnCaseRequestV1.AppendReceiptEvidence -> {
            requireLocalSalesSource(event.action == LocalReturnCaseActionV1.EVIDENCE_APPENDED &&
                request.expectedCaseRevision.next() == event.after.revision && event.after.stage == LocalReturnCaseStageV1.PENDING)
            request.binding
        }
        else -> throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    }
    requireLocalSalesSource(event.binding == binding)
    requireReturnCaseBinding(state.facts, binding)
    return binding
}

internal fun initialReturnCaseBinding(facts: LocalReturnCaseFactsV1): LocalReturnCaseReceiptBindingV1? {
    val value = facts.asserted
    return if (value.saleId != null && value.portionId != null && value.quantity != null)
        LocalReturnCaseReceiptBindingV1(value.saleId, value.portionId, value.quantity, value.evidence) else null
}

internal fun requireReturnCaseBinding(facts: LocalReturnCaseFactsV1, binding: LocalReturnCaseReceiptBindingV1) {
    requireLocalSalesSource((facts.asserted.saleId == null || facts.asserted.saleId == binding.saleId) &&
        (facts.asserted.portionId == null || facts.asserted.portionId == binding.portionId) &&
        (facts.asserted.quantity == null || facts.asserted.quantity == binding.quantity))
}

private fun RetainedCommandEvidence.write(key: AggregateKey): RetainedAggregateWrite = aggregateWrites.singleOrNull { it.key == key }
    ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
private fun RetainedAggregateWrite.text(): String = payload.copyBytes().decodeToString(throwOnInvalidSequence = true)
