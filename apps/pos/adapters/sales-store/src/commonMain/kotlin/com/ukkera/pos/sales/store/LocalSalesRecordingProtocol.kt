package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.FutureOperationReservation
import com.ukkera.pos.database.ReservedOperations
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.sales.PaymentAttempt
import com.ukkera.pos.sales.RefundAttempt
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

internal enum class LocalSalesRecordingProtocol { LEGACY_V1, PHYSICAL_V2, JOURNAL_V3 }

/** Positive retained evidence only. Missing/unknown/mismatched history is NEVER a legacy classification. */
internal suspend fun originalPaymentRecordingProtocol(view: CommandReadView, collectionId: DomainId,
    attempt: PaymentAttempt): LocalSalesRecordingProtocol {
    val origin = originalRecordingIntent(view, attempt.originalOperationId, attempt.originalActorId)
    val protocol = originalRecordingProtocol(origin, SalesRecordingKind.PAYMENT_ATTESTATION)
    val request = when (protocol) {
        LocalSalesRecordingProtocol.LEGACY_V1 -> {
            requireLocalSalesSource(LocalCollectionCommandCodec.normalize(origin.normalized) == origin.normalized)
            LocalCollectionCommandCodec.decode(origin.normalized) as? LocalCollectionRequestV1.StartAttempt ?: invalidRecordingIntent()
        }
        LocalSalesRecordingProtocol.PHYSICAL_V2 -> {
            requireLocalSalesSource(LocalCollectionCommandCodecV2.normalize(origin.normalized) == origin.normalized)
            val value = LocalCollectionCommandCodecV2.decode(origin.normalized) as? LocalCollectionRequestV2.StartAttempt ?: invalidRecordingIntent()
            requireLocalSalesSource(value.sessionId == attempt.originalCashSessionId)
            value.intentV1()
        }
        LocalSalesRecordingProtocol.JOURNAL_V3 -> {
            requireLocalSalesSource(LocalCollectionCommandCodecV3.normalize(origin.normalized) == origin.normalized)
            val value = LocalCollectionCommandCodecV3.decode(origin.normalized) as? LocalCollectionRequestV3.Start ?: invalidRecordingIntent()
            val slot = readRetailSlot(view, value.recordingOperationId).value
            requireLocalSalesSource(slot.target == value.target(origin.operationId) && slot.actorId == origin.originalActorId &&
                value.sessionId == attempt.originalCashSessionId && slot.domain.tender == tenderView(attempt.tender))
            value.intentV1() // Scalar comparison only; no v3 row/body passes through a v2 verifier.
        }
    }
    requireLocalSalesSource(request.collectionId == collectionId && request.attemptId == attempt.id &&
        request.amount == attempt.amount && request.payerReference == attempt.payerReference &&
        when (val tender = request.tender) {
            LocalTenderSelectionV1.Cash -> attempt.tender == Tender.Cash
            is LocalTenderSelectionV1.External -> (attempt.tender as? Tender.External)?.let {
                it.tenderId == tender.tenderId && it.policyRevision == tender.expectedPolicyRevision
            } == true
        })
    if (protocol != LocalSalesRecordingProtocol.JOURNAL_V3) {
        requireOriginalRecordingAdmission(origin, protocol, SalesRecordingKind.PAYMENT_ATTESTATION, collectionId,
            attempt.originalCashSessionId, attempt.id, attempt.amount, request.recordingOperationId,
            request.expectedCollectionRevision, request.expectedSessionRevision)
        // Pure inspection of genuine retained inputs, not a mutable inventory read or
        // a stock recovery barrier on already incurred physical evidence.
        LocalCollectionCompositeVerifier().requireComplete(origin)
    }
    return protocol
}

internal suspend fun originalRefundRecordingProtocol(view: CommandReadView, saleId: DomainId, claimId: DomainId,
    returnId: DomainId, attempt: RefundAttempt): LocalSalesRecordingProtocol {
    val origin = originalRecordingIntent(view, attempt.originalOperationId, attempt.originalActorId)
    val protocol = originalRecordingProtocol(origin, SalesRecordingKind.REFUND_ATTESTATION)
    val request = when (protocol) {
        LocalSalesRecordingProtocol.LEGACY_V1 -> {
            requireLocalSalesSource(LocalReturnCommandCodec.normalize(origin.normalized) == origin.normalized)
            LocalReturnCommandCodec.decode(origin.normalized) as? LocalReturnRequestV1.StartRefund ?: invalidRecordingIntent()
        }
        LocalSalesRecordingProtocol.PHYSICAL_V2 -> {
            requireLocalSalesSource(LocalReturnCommandCodecV2.normalize(origin.normalized) == origin.normalized)
            (LocalReturnCommandCodecV2.decode(origin.normalized) as? LocalReturnRequestV2.StartRefund ?: invalidRecordingIntent()).intentV1()
        }
        LocalSalesRecordingProtocol.JOURNAL_V3 -> {
            requireLocalSalesSource(LocalReturnCommandCodecV3.normalize(origin.normalized) == origin.normalized)
            val value = LocalReturnCommandCodecV3.decode(origin.normalized) as? LocalReturnRequestV3.Start ?: invalidRecordingIntent()
            val slot = readRetailSlot(view, value.recordingOperationId).value
            requireLocalSalesSource(slot.target == value.target(origin.operationId) && slot.actorId == origin.originalActorId &&
                slot.domain.tender == tenderView(attempt.tender))
            value.intentV1()
        }
    }
    requireLocalSalesSource(request.saleId == saleId && request.claimId == claimId && request.returnId == returnId &&
        request.sessionId == attempt.originalCashSessionId && request.attemptId == attempt.id &&
        request.originalPaymentId == attempt.originalPaymentId && request.amount == attempt.amount)
    if (protocol != LocalSalesRecordingProtocol.JOURNAL_V3) {
        requireOriginalRecordingAdmission(origin, protocol, SalesRecordingKind.REFUND_ATTESTATION, returnId,
            attempt.originalCashSessionId, attempt.id, attempt.amount, request.recordingOperationId,
            request.expectedReturnRevision, request.expectedSessionRevision)
    }
    return protocol
}

private suspend fun originalRecordingIntent(view: CommandReadView, operationId: OperationId,
    actorId: DomainId): RetainedCommandEvidence {
    val origin = view.retainedCommand(operationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(origin.committed && origin.operationId == operationId && origin.scope == view.scope &&
        origin.originalActorId == actorId && origin.jobs.isEmpty() &&
        origin.aggregateWrites.map { it.key }.distinct().size == origin.aggregateWrites.size &&
        semanticDigest(origin.scope, origin.originalActorId, origin.command, origin.normalized) == origin.semanticDigest)
    return origin
}

private fun originalRecordingProtocol(origin: RetainedCommandEvidence, kind: SalesRecordingKind): LocalSalesRecordingProtocol {
    val legacy = if (kind == SalesRecordingKind.PAYMENT_ATTESTATION) LocalSalesStoreProfile.COLLECTION_COMMAND else LocalSalesStoreProfile.RETURN_COMMAND
    return when (origin.command) {
        legacy -> LocalSalesRecordingProtocol.LEGACY_V1
        physicalCommandV2(kind) -> LocalSalesRecordingProtocol.PHYSICAL_V2
        retailJournalCommandV3(kind) -> LocalSalesRecordingProtocol.JOURNAL_V3
        else -> invalidRecordingIntent()
    }
}

private fun requireOriginalRecordingAdmission(origin: RetainedCommandEvidence, protocol: LocalSalesRecordingProtocol,
    kind: SalesRecordingKind, documentId: DomainId, sessionId: DomainId, attemptId: DomainId, amount: Money,
    recordingOperationId: OperationId, documentRevision: Revision, sessionRevision: Revision) {
    requireLocalSalesSource(protocol != LocalSalesRecordingProtocol.JOURNAL_V3)
    val payment = kind == SalesRecordingKind.PAYMENT_ATTESTATION
    val slotId = DomainId(recordingOperationId.value)
    val slotKind = if (protocol == LocalSalesRecordingProtocol.LEGACY_V1)
        LocalSalesStoreProfile.RECORDING_SLOT_KIND else LocalSalesStoreProfile.PHYSICAL_SLOT_KIND
    val documentKind = if (payment) LocalSalesStoreProfile.COLLECTION_KIND else LocalSalesStoreProfile.RETURN_KIND
    val result = LocalSalesResultCodec.decode(origin.result) as? LocalSalesResultV1.Applied ?: invalidRecordingIntent()
    requireLocalSalesSource(LocalSalesResultCodec.encode(result) == origin.result &&
        result.action == (if (payment) LocalSalesAction.PAYMENT_ATTEMPT_STARTED else LocalSalesAction.REFUND_ATTEMPT_STARTED) &&
        result.documentId == documentId && result.amount == amount && result.revisions == listOf(
            LocalSalesRevision(documentKind, documentId, documentRevision.next()),
            LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, sessionId, sessionRevision.next()),
            LocalSalesRevision(slotKind, slotId, Revision(0))) && (payment || result.stockItems.isEmpty()))
    val documentKey = LocalSalesStoreProfile.key(documentKind, documentId)
    val cashKey = LocalSalesStoreProfile.key(CashNonSaleStoreProfile.SESSION_KIND, sessionId)
    for ((key, revision) in listOf(documentKey to documentRevision, cashKey to sessionRevision)) {
        val row = origin.aggregateWrites.singleOrNull { it.key == key } ?: invalidRecordingIntent()
        requireLocalSalesSource(row.schema == LocalSalesStoreProfile.schema(key.kind) && row.storageGeneration == revision.next().next())
    }
    val slotKey = LocalSalesStoreProfile.key(slotKind, slotId)
    val slotRow = origin.aggregateWrites.singleOrNull { it.key == slotKey } ?: invalidRecordingIntent()
    val lineage: List<Pair<ClaimKey, DomainId>>
    val binding: com.ukkera.pos.security.PayloadDigest
    // The legacy payment request omitted sessionId. Its ORIGINAL retained slot and
    // immutable fence must supply that exact binding; never infer it from absence.
    // Read the retained initial value, not today's possibly consumed mutable v1 slot.
    if (protocol == LocalSalesRecordingProtocol.LEGACY_V1) {
        requireLocalSalesSource(slotRow.schema == LocalSalesStoreProfile.schema(slotKind) && slotRow.storageGeneration == Revision(1))
        val slot = decodeLocalSalesSource(slotRow.payload, LocalRecordingSlot.serializer(), 4_096)
        requireLocalSalesSource(slot.scope.value() == origin.scope && slot.id == slotId && slot.revision == Revision(0) &&
            slot.consumedAt == null && slot.actorId == origin.originalActorId && slot.reservationOperationId == origin.operationId &&
            slot.intentOperationId == origin.operationId && slot.recordingOperationId == recordingOperationId &&
            slot.attemptId == attemptId && slot.documentId == documentId && slot.cashSessionId == sessionId && slot.kind == kind &&
            slot.budgetProfile == SalesRecordingBudget.PROFILE && recordingOperationId != origin.operationId)
        binding = salesRecordingTargetBinding(kind, documentId, sessionId, attemptId, origin.operationId)
        lineage = listOf(
            physicalOperationClaim(slotId) to attemptId,
            physicalMasterAllocationClaim(slot.allocationId) to attemptId,
            physicalAttemptClaim(attemptId) to slotId,
        )
    } else {
        requireLocalSalesSource(slotRow.schema == LocalSalesStoreProfile.PHYSICAL_SLOT_SCHEMA && slotRow.storageGeneration == Revision(1))
        val slot = decodeLocalSalesSource(slotRow.payload, LocalPhysicalRecordingSlotV2.serializer(), 16_384)
        requireLocalSalesSource(slot.scope.value() == origin.scope && slot.id == slotId && slot.revision == Revision(0) &&
            slot.actorId == origin.originalActorId && slot.reservationOperationId == origin.operationId &&
            slot.intentOperationId == origin.operationId && slot.recordingOperationId == recordingOperationId &&
            slot.attemptId == attemptId && slot.documentId == documentId && slot.cashSessionId == sessionId &&
            slot.kind == kind && slot.amount == amount && !slot.resolving &&
            slot.budgetProfile == SalesPhysicalRecordingBudgetV2.PROFILE && recordingOperationId != origin.operationId)
        binding = slot.targetBinding()
        lineage = physicalLineageClaimsV2(slot)
    }
    val expected = ReservedOperations.create(FutureOperationReservation(recordingOperationId, origin.originalActorId, origin.command, binding))
    val fence = origin.aggregateWrites.singleOrNull { it.key == expected.key } ?: invalidRecordingIntent()
    requireLocalSalesSource(fence.storageGeneration == Revision(1) && fence.schema == expected.schema && fence.payload == expected.payload)
    requireHistoricalRecordingClaims(origin, lineage +
        (LocalSalesStoreProfile.unique(if (payment) "payment_attempt" else "refund_attempt", attemptId) to documentId))
    // Historical refunds had no before bundle. Bind their exact closed retained
    // four-row envelope; do not invent a modern before-image proof for that history.
    if (!payment) requireLocalSalesSource(origin.aggregateWrites.map { it.key }.toSet() == setOf(documentKey, cashKey, slotKey, expected.key) &&
        origin.aggregateWrites.size == 4)
}

/** The CURRENT unconsumed v1 slot can be the original Start or a genuine later old Reserve. */
internal suspend fun requireLegacyRecordingProducer(view: CommandReadView, row: LocalSalesRow<LocalRecordingSlot>,
    amount: Money, saleId: DomainId? = null, claimId: DomainId? = null) {
    val slot = row.value
    val producer = originalRecordingIntent(view, slot.reservationOperationId, slot.actorId)
    requireLocalSalesSource(originalRecordingProtocol(producer, slot.kind) == LocalSalesRecordingProtocol.LEGACY_V1 &&
        slot.recordingOperationId != producer.operationId)
    val original = slot.reservationOperationId == slot.intentOperationId
    val matches = when (slot.kind) {
        SalesRecordingKind.PAYMENT_ATTESTATION -> {
            requireLocalSalesSource(LocalCollectionCommandCodec.normalize(producer.normalized) == producer.normalized)
            when (val request = LocalCollectionCommandCodec.decode(producer.normalized)) {
                is LocalCollectionRequestV1.StartAttempt -> original && request.collectionId == slot.documentId &&
                    request.attemptId == slot.attemptId && request.recordingOperationId == slot.recordingOperationId && request.amount == amount
                is LocalCollectionRequestV1.ReserveRecording -> !original && request.collectionId == slot.documentId &&
                    request.sessionId == slot.cashSessionId && request.attemptId == slot.attemptId && request.recordingOperationId == slot.recordingOperationId
                else -> false
            }
        }
        SalesRecordingKind.REFUND_ATTESTATION -> {
            requireLocalSalesSource(LocalReturnCommandCodec.normalize(producer.normalized) == producer.normalized)
            when (val request = LocalReturnCommandCodec.decode(producer.normalized)) {
                is LocalReturnRequestV1.StartRefund -> original && request.returnId == slot.documentId && request.saleId == saleId &&
                    request.claimId == claimId && request.sessionId == slot.cashSessionId && request.attemptId == slot.attemptId &&
                    request.recordingOperationId == slot.recordingOperationId && request.amount == amount
                is LocalReturnRequestV1.ReserveRefundRecording -> !original && request.returnId == slot.documentId && request.saleId == saleId &&
                    request.claimId == claimId && request.sessionId == slot.cashSessionId && request.attemptId == slot.attemptId &&
                    request.recordingOperationId == slot.recordingOperationId
                else -> false
            }
        }
    }
    requireLocalSalesSource(matches)
    requireRetainedPhysicalWrite(producer, row.stored)
    val fence = LocalSalesStorage.required(view, ReservedOperations.key(slot.recordingOperationId), ReservedOperations.SCHEMA)
    requireRetainedPhysicalWrite(producer, fence)
    if (!original) requireHistoricalReservationEnvelope(producer, slot.kind, slot.documentId, amount, row.stored.key,
        slot.id, listOf(physicalOperationClaim(slot.id) to slot.attemptId,
            physicalMasterAllocationClaim(slot.allocationId) to slot.attemptId, physicalAttemptClaim(slot.attemptId) to slot.id))
}

/**
 * Positive historical v2 lineage for both ingress and application. Reads only immutable
 * slots/fences/claims and genuine retained history, never today's money/stock projection.
 * A missing slot remains the existing explicit NotAvailable path, not a legacy inference.
 */
internal suspend fun requireHistoricalPhysicalLineageV2(view: CommandReadView, recordingOperationId: OperationId) {
    if (view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_SLOT_KIND, DomainId(recordingOperationId.value))) == null) return
    val slot = readPhysicalSlotV2(view, recordingOperationId, requireActive = false).value
    val origin = originalRecordingIntent(view, slot.intentOperationId, slot.actorId)
    requireLocalSalesSource(originalRecordingProtocol(origin, slot.kind) == LocalSalesRecordingProtocol.PHYSICAL_V2)
    when (slot.kind) {
        SalesRecordingKind.PAYMENT_ATTESTATION -> {
            requireLocalSalesSource(LocalCollectionCommandCodecV2.normalize(origin.normalized) == origin.normalized)
            val request = LocalCollectionCommandCodecV2.decode(origin.normalized) as? LocalCollectionRequestV2.StartAttempt ?: invalidRecordingIntent()
            requireLocalSalesSource(request.collectionId == slot.documentId && request.sessionId == slot.cashSessionId &&
                request.attemptId == slot.attemptId && request.amount == slot.amount && when (val tender = request.tender) {
                    LocalTenderSelectionV1.Cash -> slot.tender == tenderView(Tender.Cash)
                    is LocalTenderSelectionV1.External -> slot.tender.kind != "CASH" &&
                        slot.tender.tenderId == tender.tenderId && slot.tender.policyRevision == tender.expectedPolicyRevision
                })
            requireOriginalRecordingAdmission(origin, LocalSalesRecordingProtocol.PHYSICAL_V2, slot.kind, slot.documentId,
                slot.cashSessionId, slot.attemptId, slot.amount, request.recordingOperationId,
                request.expectedCollectionRevision, request.expectedSessionRevision)
            LocalCollectionCompositeVerifier().requireComplete(origin)
        }
        SalesRecordingKind.REFUND_ATTESTATION -> {
            requireLocalSalesSource(LocalReturnCommandCodecV2.normalize(origin.normalized) == origin.normalized)
            val request = LocalReturnCommandCodecV2.decode(origin.normalized) as? LocalReturnRequestV2.StartRefund ?: invalidRecordingIntent()
            requireLocalSalesSource(request.returnId == slot.documentId && request.sessionId == slot.cashSessionId && request.saleId == slot.originalSaleId &&
                request.claimId == slot.originalClaimId && request.originalPaymentId == slot.originalPaymentId &&
                request.attemptId == slot.attemptId && request.amount == slot.amount)
            requireOriginalRecordingAdmission(origin, LocalSalesRecordingProtocol.PHYSICAL_V2, slot.kind, slot.documentId,
                slot.cashSessionId, slot.attemptId, slot.amount, request.recordingOperationId,
                request.expectedReturnRevision, request.expectedSessionRevision)
        }
    }
    requireOriginalPhysicalIntentV2(view, slot.kind, slot.intentOperationId, slot.attemptId, slot.documentId,
        slot.cashSessionId, slot.actorId, slot.amount, slot.tender, slot.originalSaleId, slot.originalClaimId, slot.originalPaymentId)
    if (slot.reservationOperationId != slot.intentOperationId) {
        val producer = originalRecordingIntent(view, slot.reservationOperationId, slot.actorId)
        requireLocalSalesSource(producer.command == physicalCommandV2(slot.kind))
        val matches = when (slot.kind) {
            SalesRecordingKind.PAYMENT_ATTESTATION -> {
                requireLocalSalesSource(LocalCollectionCommandCodecV2.normalize(producer.normalized) == producer.normalized)
                val request = LocalCollectionCommandCodecV2.decode(producer.normalized) as? LocalCollectionRequestV2.ReserveRecording
                    ?: invalidRecordingIntent()
                request.collectionId == slot.documentId && request.sessionId == slot.cashSessionId && request.attemptId == slot.attemptId &&
                    request.recordingOperationId == slot.recordingOperationId && request.clientAllocationId == slot.clientAllocationId
            }
            SalesRecordingKind.REFUND_ATTESTATION -> {
                requireLocalSalesSource(LocalReturnCommandCodecV2.normalize(producer.normalized) == producer.normalized)
                val request = LocalReturnCommandCodecV2.decode(producer.normalized) as? LocalReturnRequestV2.ReserveRefundRecording
                    ?: invalidRecordingIntent()
                request.returnId == slot.documentId && request.saleId == slot.originalSaleId && request.claimId == slot.originalClaimId &&
                    request.sessionId == slot.cashSessionId && request.attemptId == slot.attemptId &&
                    request.recordingOperationId == slot.recordingOperationId && request.clientAllocationId == slot.clientAllocationId
            }
        }
        requireLocalSalesSource(matches)
        // readPhysicalSlotV2 already bound this exact slot/fence pair to this producer;
        // close its whole result/claim/write envelope, not an any/subset match.
        requireHistoricalReservationEnvelope(producer, slot.kind, slot.documentId, slot.amount,
            LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_SLOT_KIND, slot.id), slot.id, physicalLineageClaimsV2(slot))
    }
}

private fun physicalLineageClaimsV2(slot: LocalPhysicalRecordingSlotV2): List<Pair<ClaimKey, DomainId>> = listOf(
    physicalOperationClaim(slot.id) to slot.attemptId, physicalMasterAllocationClaim(slot.masterAllocationId) to slot.attemptId,
    physicalClientAllocationClaim(slot.clientAllocationId) to slot.attemptId, physicalAttemptClaim(slot.attemptId) to slot.id)

private fun requireHistoricalReservationEnvelope(producer: RetainedCommandEvidence, kind: SalesRecordingKind, documentId: DomainId,
    amount: Money, slotKey: com.ukkera.pos.database.AggregateKey, slotId: DomainId, claims: List<Pair<ClaimKey, DomainId>>) {
    val expected = LocalSalesResultV1.Applied(if (kind == SalesRecordingKind.PAYMENT_ATTESTATION)
        LocalSalesAction.PAYMENT_RECORDING_RESERVED else LocalSalesAction.REFUND_RECORDING_RESERVED, documentId,
        listOf(LocalSalesRevision(slotKey.kind, slotId, Revision(0))), amount)
    requireLocalSalesSource(LocalSalesResultCodec.encode(expected) == producer.result && producer.aggregateWrites.size == 2 &&
        producer.aggregateWrites.map { it.key }.toSet() == setOf(slotKey, ReservedOperations.key(OperationId(slotId.value))) &&
        producer.aggregateWrites.all { it.storageGeneration == Revision(1) })
    requireHistoricalRecordingClaims(producer, claims)
}

private fun requireHistoricalRecordingClaims(producer: RetainedCommandEvidence, expected: List<Pair<ClaimKey, DomainId>>) {
    requireLocalSalesSource(producer.claimChanges.size == expected.size && producer.claimChanges.all { it.action == RetainedClaimAction.ACQUIRE } &&
        producer.claimChanges.map { it.key to it.ownerId }.toSet() == expected.toSet())
}

private fun invalidRecordingIntent(): Nothing = throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
