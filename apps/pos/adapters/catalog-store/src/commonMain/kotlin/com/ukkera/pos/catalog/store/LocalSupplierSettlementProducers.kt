package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.purchaseZero
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

internal fun supplierCashRevisions(changes: List<AggregateChange>): List<LocalSupplierCashRevision> = changes
    .filter { supplierRevisionKind(it.key.kind) }.sortedWith(compareBy<AggregateChange> { it.key.kind }.thenBy { it.key.id.value })
    .map { LocalSupplierCashRevision(it.key.kind, it.key.id, (it.expectedRevision ?: Revision(0)).next()) }
private fun supplierRetainedRevisions(writes: List<RetainedAggregateWrite>): List<LocalSupplierCashRevision> = writes
    .filter { supplierRevisionKind(it.key.kind) }.sortedWith(compareBy<RetainedAggregateWrite> { it.key.kind }.thenBy { it.key.id.value })
    .map { LocalSupplierCashRevision(it.key.kind, it.key.id, it.storageGeneration) }
private fun supplierRevisionKind(kind: String): Boolean = kind in LocalSupplierSettlementProfile.kinds ||
    kind in setOf(CashNonSaleStoreProfile.SESSION_KIND, LocalSupplierCashProfile.SLOT, LocalSupplierCashProfile.APPLICATION, LocalSalesStoreProfile.MOVEMENT_KIND)

/** Closed whole producer shape; core-native BEFORE joins validate the real financial sources. */
internal fun requireSupplierSettlementProducer(original: RetainedCommandEvidence) {
    check(original.committed && original.jobs.isEmpty() && purchasingNormalize(original.command, original.normalized) == original.normalized &&
        semanticDigest(original.scope, original.originalActorId, original.command, original.normalized) == original.semanticDigest)
    val expectedKeys = mutableSetOf<AggregateKey>()
    val expectedClaims = mutableListOf<ClaimChange>()
    val records = original.aggregateWrites.filter { it.key.kind in LocalSupplierSettlementProfile.kinds }.associate { write ->
        check(write.schema == LocalPurchasingProfile.schema(write.key.kind) && write.payloadDigest == sha256(write.payload.copyBytes()))
        val value = decodePurchasingRecord(write.payload)
        check(value.scope == original.scope && value.revision == write.storageGeneration && value.kind == write.key.kind && value.id == write.key.id &&
            value.producer == PurchasingProducer(original.operationId, original.originalActorId, original.command.name, original.semanticDigest.value) &&
            value.policyRevision == original.policyRevision && value.recordedAt == original.recordedAt)
        write.key to value
    }
    fun record(kind: String, id: DomainId, revision: Revision): PurchasingRecord {
        val key = purchasingKey(kind, id); expectedKeys += key
        return records[key]?.also { check(it.revision == revision) } ?: error("Purchasing supplier producer lost a row")
    }
    fun acquire(kind: String, id: DomainId) { expectedClaims += purchasingAcquisition(kind, id) }
    fun event(): PurchasingSettlementEvent {
        val value = (record(LocalSupplierSettlementProfile.EVENT, DomainId(original.operationId.value), Revision(1)).body as PurchasingBody.SettlementEvent).value
        requireSupplierBasisShape(value.basisBefore)
        check(value.id.value == original.operationId.value && value.invoiceId == value.basisBefore.invoiceSource?.id)
        acquire(LocalSupplierSettlementProfile.EVENT, value.id)
        return value
    }
    fun invoiceIndex(basis: PurchasingSupplierCashBasis, allocationId: DomainId?, settlementId: DomainId?) {
        val invoiceId = checkNotNull(basis.invoiceSource).id
        val index = record(LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS, invoiceId,
            (basis.settlementSource?.source?.revision ?: Revision(0)).next()).body as PurchasingBody.InvoiceSettlements
        check(index.invoiceId == invoiceId && index.lastEventId.value == original.operationId.value &&
            index.allocationIds.size <= LocalSupplierSettlementProfile.MAX_ENTRIES && index.settlementIds.size <= LocalSupplierSettlementProfile.MAX_ENTRIES &&
            index.allocationIds.distinct().size == index.allocationIds.size && index.settlementIds.distinct().size == index.settlementIds.size)
        allocationId?.let { check(index.allocationIds.lastOrNull() == it) }
        settlementId?.let { check(index.settlementIds.lastOrNull() == it) }
        if (basis.settlementSource == null) acquire(LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS, invoiceId)
    }
    val expectedResult: StoredBytes
    if (original.command == LocalSupplierSettlementProfile.ALLOCATE_ADVANCE) {
        check(original.nativeOriginalProvenance == null)
        val request = LocalSupplierSettlementCodec.allocation(original.normalized)
        val event = event()
        check(event.kind == PurchasingSettlementEventKind.ALLOCATE_ADVANCE && purchasingSupplierSelectionMatches(request.invoice, event.basisBefore) &&
            event.attemptSource == null && event.cashSourcesBefore.isEmpty() && event.originalSource == null && event.resolutionSource == null &&
            event.originalUnknownApplicationOperationId == null)
        val allocation = (record(LocalSupplierSettlementProfile.ALLOCATION, request.allocationId, Revision(1)).body as PurchasingBody.SettlementAllocation).value
        requireSupplierAllocationShape(allocation)
        check(allocation.id == request.allocationId && allocation.invoiceId == request.invoice.invoiceId && allocation.advanceId == request.advanceId &&
            allocation.supplierId == request.invoice.supplierId && allocation.amount == request.amount && allocation.basisBefore == event.basisBefore &&
            allocation.advanceSource == event.advanceSource && allocation.advanceAllocationSource == event.advanceAllocationSource &&
            allocation.advanceSource?.revision == request.expectedAdvanceRevision &&
            allocation.advanceAllocationSource?.revision == request.expectedAdvanceAllocationRevision &&
            allocation.unappliedAdvanceBefore == request.expectedUnappliedAdvance && allocation.reasonCode == request.reasonCode &&
            request.amount.minor <= request.expectedUnappliedAdvance.minor &&
            request.amount.minor <= (checkNotNull(event.basisBefore.remainingDue) - checkNotNull(event.basisBefore.reserved)).minor)
        acquire(LocalSupplierSettlementProfile.ALLOCATION, request.allocationId)
        invoiceIndex(event.basisBefore, request.allocationId, null)
        val advanceIndex = record(LocalSupplierSettlementProfile.ADVANCE_ALLOCATIONS, request.advanceId,
            request.expectedAdvanceAllocationRevision.next()).body as PurchasingBody.AdvanceAllocations
        check(advanceIndex.advanceId == request.advanceId && advanceIndex.allocationIds.last() == request.allocationId &&
            advanceIndex.allocationIds.size.toLong() == request.expectedAdvanceAllocationRevision.value &&
            advanceIndex.allocationIds.distinct().size == advanceIndex.allocationIds.size && advanceIndex.allocationIds.size <= LocalSupplierSettlementProfile.MAX_ENTRIES)
        expectedResult = purchasingEncode(LocalSupplierAdvanceAllocationResultV1(request.allocationId, request.invoice.invoiceId,
            request.advanceId, request.amount, checkNotNull(event.basisBefore.remainingDue) - request.amount,
            request.expectedUnappliedAdvance - request.amount, (request.invoice.expectedSettlementRevision ?: Revision(0)).next(),
            request.expectedAdvanceAllocationRevision.next()), LocalSupplierAdvanceAllocationResultV1.serializer())
    } else {
        check(original.command == LocalSupplierCashProfile.COMMAND)
        when (val request = LocalSupplierCashCodec.decode(original.normalized)) {
            is LocalSupplierCashRequest.StartCash -> {
                val slot = LocalSupplierCashFacts.requireStart(original)
                check(original.nativeOriginalProvenance == null)
                val event = event()
                check(event.kind == PurchasingSettlementEventKind.START_CASH && event.attemptSource == null &&
                    event.advanceSource == null && event.advanceAllocationSource == null && event.originalSource == null && event.resolutionSource == null &&
                    event.originalUnknownApplicationOperationId == null && purchasingSupplierSelectionMatches(request.source, event.basisBefore) &&
                    event.basisBefore.digest() == slot.domain.basisDigest)
                val attempt = (record(LocalSupplierSettlementProfile.ATTEMPT, request.settlementId, Revision(1)).body as PurchasingBody.SettlementAttempt).value
                check(attempt == PurchasingSupplierAttempt(slot.target, request.source, request.amount, event.basisBefore, event.cashSourcesBefore,
                    sha256(LocalSupplierCashJournal.slotWrite(slot).payload.copyBytes()), PurchasingSupplierAttemptStatus.AWAITING_ATTESTATION))
                requireSupplierCashSourcesShape(event.cashSourcesBefore, slot, starting = true)
                acquire(LocalSupplierSettlementProfile.ATTEMPT, request.settlementId)
                if (slot.target.invoiceId != null) invoiceIndex(event.basisBefore, null, request.settlementId)
                expectedKeys += LocalSupplierCashJournal.vacantKeys(slot.requirement()).filter { key ->
                    key.kind in setOf(LocalSupplierCashProfile.SLOT, PhysicalRecordingSourceProfileV1.KIND, ReservedOperations.KIND)
                }
                expectedKeys += LocalSupplierCashStorage.sessionKey(request.sessionId)
                expectedClaims += LocalSupplierCashJournal.lineageClaims(slot.requirement())
                expectedClaims += ClaimChange.Acquire(LocalSupplierCashStorage.sourceClaim(request.attemptId), request.sessionId)
                val result = LocalSupplierCashCodec.result(original.result) as LocalSupplierCashResult.AttemptStarted
                check(result.revisions == supplierRetainedRevisions(original.aggregateWrites))
                expectedResult = original.result
            }
            is LocalSupplierCashRequest.RecordOriginal, is LocalSupplierCashRequest.RecordResolution -> {
                val phase = if (request is LocalSupplierCashRequest.RecordOriginal) LocalSupplierCashPhase.ORIGINAL else LocalSupplierCashPhase.RESOLUTION
                val target = when (request) {
                    is LocalSupplierCashRequest.RecordOriginal -> request.target
                    is LocalSupplierCashRequest.RecordResolution -> request.target
                    else -> error("Purchasing raw target unavailable")
                }
                check(original.operationId == target.factOperation(phase) && records.isEmpty() && original.claimChanges.isEmpty())
                val rawKey = if (phase == LocalSupplierCashPhase.ORIGINAL) LocalSupplierCashJournal.rawKey(original.operationId)
                    else LocalSupplierCashJournal.resolutionKey(original.operationId)
                val raw = LocalSupplierCashFacts.decodeRaw(original.aggregateWrites.single { it.key == rawKey }.payload)
                check(raw.scope == original.scope && raw.target == target && raw.originalActorId == original.originalActorId &&
                    raw.recordingActorId == original.originalActorId && raw.phase == phase && raw.recordedAt == original.recordedAt &&
                    raw.semanticDigest == original.semanticDigest && raw.body() == original.normalized)
                if (phase == LocalSupplierCashPhase.RESOLUTION) check(original.nativeOriginalProvenance == null)
                LocalSupplierCashFacts.requireWrites(original.aggregateWrites, listOf(LocalSupplierCashFacts.rawWrite(raw),
                    LocalSupplierCashFacts.applicationWrite(LocalSupplierCashFacts.initial(raw), null)))
                expectedKeys += rawKey; expectedKeys += LocalSupplierCashJournal.applicationKey(original.operationId)
                expectedResult = LocalSupplierCashCodec.result(LocalSupplierCashResult.RawRetained(target, phase))
            }
            is LocalSupplierCashRequest.ApplyOriginal, is LocalSupplierCashRequest.ApplyResolution -> {
                check(original.nativeOriginalProvenance == null)
                val result = LocalSupplierCashCodec.result(original.result) as LocalSupplierCashResult.Applied
                val target = result.target
                val phase = if (request is LocalSupplierCashRequest.ApplyOriginal) LocalSupplierCashPhase.ORIGINAL else LocalSupplierCashPhase.RESOLUTION
                val operation = if (request is LocalSupplierCashRequest.ApplyOriginal) request.recordingOperationId
                    else (request as LocalSupplierCashRequest.ApplyResolution).resolutionOperationId
                check(operation == target.factOperation(phase) && result.phase == phase && result.applicationOperationId == original.operationId &&
                    original.operationId !in setOf(target.intentOperationId, target.recordingOperationId, target.resolutionOperationId))
                val event = event()
                check(event.kind == (if (phase == LocalSupplierCashPhase.ORIGINAL) PurchasingSettlementEventKind.APPLY_ORIGINAL
                    else PurchasingSettlementEventKind.APPLY_RESOLUTION))
                val source = checkNotNull(event.attemptSource)
                check(source.kind == LocalSupplierSettlementProfile.ATTEMPT && source.id == target.settlementId &&
                    source.revision == Revision(if (phase == LocalSupplierCashPhase.ORIGINAL) 1L else 2L))
                val after = (record(LocalSupplierSettlementProfile.ATTEMPT, target.settlementId, source.revision.next()).body as PurchasingBody.SettlementAttempt).value
                check(after.target == target && after.applicationOperationIds.last() == original.operationId &&
                    after.applicationOperationIds.size == (if (phase == LocalSupplierCashPhase.ORIGINAL) 1 else 2))
                requireSupplierBasisShape(after.basisBefore)
                check(after.originalBodyDigest != null && (after.resolutionBodyDigest != null) == (phase == LocalSupplierCashPhase.RESOLUTION) &&
                    after.status == (when (result.action) {
                        LocalSupplierCashAction.UNKNOWN -> PurchasingSupplierAttemptStatus.OUTCOME_UNKNOWN
                        LocalSupplierCashAction.CASH_PAID -> PurchasingSupplierAttemptStatus.CASH_PAID
                        LocalSupplierCashAction.NOT_PAID -> PurchasingSupplierAttemptStatus.NOT_PAID
                    }) && event.originalSource?.let { it.kind == LocalSupplierCashProfile.ORIGINAL && it.id.value == target.recordingOperationId.value && it.revision == Revision(1) } == true &&
                    (event.resolutionSource != null) == (phase == LocalSupplierCashPhase.RESOLUTION))
                if (phase == LocalSupplierCashPhase.RESOLUTION) check(result.action != LocalSupplierCashAction.UNKNOWN &&
                    event.originalUnknownApplicationOperationId == after.applicationOperationIds.first() &&
                    event.resolutionSource?.let { it.kind == LocalSupplierCashProfile.RESOLUTION && it.id.value == target.resolutionOperationId.value && it.revision == Revision(1) } == true)
                else check(event.originalUnknownApplicationOperationId == null)
                val appKey = LocalSupplierCashJournal.applicationKey(operation)
                expectedKeys += appKey
                val appWrite = original.aggregateWrites.single { it.key == appKey }
                val app = LocalSupplierCashFacts.decodeApplication(appWrite.payload)
                check(appWrite.storageGeneration == Revision(2) && appWrite.schema == LocalSupplierCashProfile.schema(LocalSupplierCashProfile.APPLICATION) &&
                    app.scope == original.scope && app.target == target && app.phase == phase && app.originalActorId == original.originalActorId &&
                    app.applicationOperationId == original.operationId && app.result == result && app.bodyDigest ==
                    (if (phase == LocalSupplierCashPhase.ORIGINAL) after.originalBodyDigest else after.resolutionBodyDigest))
                val paid = result.action == LocalSupplierCashAction.CASH_PAID
                if (target.invoiceId != null) invoiceIndex(event.basisBefore, if (paid) target.allocationId else null, null)
                if (result.action != LocalSupplierCashAction.UNKNOWN) expectedKeys += LocalSupplierCashStorage.sessionKey(target.sessionId)
                if (paid) {
                    val payment = (record(LocalSupplierSettlementProfile.PAYMENT, target.settlementId, Revision(1)).body as PurchasingBody.SupplierPayment).value
                    check(payment.id == target.settlementId && payment.supplierId == target.supplierId && payment.target == target &&
                        payment.amount == after.amount && payment.sourceAttempt == source && payment.phase == phase &&
                        payment.cashMovementId == target.attemptId && payment.originalBodyDigest == after.originalBodyDigest &&
                        payment.observedBodyDigest == app.bodyDigest)
                    expectedKeys += LocalSupplierCashStorage.movementKey(target.attemptId)
                    acquire(LocalSupplierSettlementProfile.PAYMENT, target.settlementId)
                    val paymentSource = purchasingSource(original.aggregateWrites.single { it.key == purchasingKey(LocalSupplierSettlementProfile.PAYMENT, target.settlementId) })
                    if (target.invoiceId != null) {
                        val allocationId = checkNotNull(target.allocationId)
                        val allocation = (record(LocalSupplierSettlementProfile.ALLOCATION, allocationId, Revision(1)).body as PurchasingBody.SettlementAllocation).value
                        requireSupplierAllocationShape(allocation)
                        check(allocation.id == allocationId && allocation.invoiceId == target.invoiceId && allocation.supplierId == target.supplierId &&
                            allocation.paymentId == payment.id && allocation.paymentSource == paymentSource && allocation.amount == after.amount &&
                            allocation.basisBefore == event.basisBefore && allocation.advanceId == null)
                        acquire(LocalSupplierSettlementProfile.ALLOCATION, allocationId)
                    } else {
                        val advance = (record(LocalSupplierSettlementProfile.ADVANCE, target.settlementId, Revision(1)).body as PurchasingBody.SupplierAdvance).value
                        check(advance == PurchasingSupplierAdvance(target.settlementId, target.supplierId, payment.id, paymentSource, payment.amount))
                        check(record(LocalSupplierSettlementProfile.ADVANCE_ALLOCATIONS, target.settlementId, Revision(1)).body ==
                            PurchasingBody.AdvanceAllocations(target.settlementId, emptyList()))
                        acquire(LocalSupplierSettlementProfile.ADVANCE, target.settlementId); acquire(LocalSupplierSettlementProfile.ADVANCE_ALLOCATIONS, target.settlementId)
                    }
                }
                check(result.amount == (if (paid) after.amount else null) && result.remainingDue == event.basisBefore.remainingDue?.let {
                    if (paid) it - after.amount else it
                } && result.unappliedAdvance == (if (paid && target.invoiceId == null) after.amount else null) &&
                    result.revisions == supplierRetainedRevisions(original.aggregateWrites))
                expectedResult = original.result
            }
        }
    }
    check(original.aggregateWrites.size == expectedKeys.size && original.aggregateWrites.map { it.key }.toSet() == expectedKeys && original.result == expectedResult)
    check(original.claimChanges.size == expectedClaims.size && original.claimChanges.all { actual ->
        actual.action == RetainedClaimAction.ACQUIRE && expectedClaims.singleOrNull { it.key == actual.key }?.ownerId == actual.ownerId
    })
}

internal fun requireSupplierBasisShape(basis: PurchasingSupplierCashBasis) {
    val sources = listOfNotNull(basis.supplierSource, basis.invoiceSource, basis.progressSource, basis.payableSource, basis.creditSource, basis.settlementSource?.source)
    sources.forEach { check(it.revision.value >= 1L); PayloadDigest(it.digest) }
    check(basis.supplierSource.kind == LocalConfigurationProfile.SUPPLIER)
    if (basis.invoiceSource == null) check(basis.progressSource == null && basis.payableSource == null && basis.creditSource == null &&
        basis.settlementSource == null && basis.recognized == null && basis.appliedCredit == null && basis.allocated == null && basis.reserved == null &&
        basis.remainingDue == null && !basis.capturedInvoice)
    else {
        val id = basis.invoiceSource.id
        check(basis.invoiceSource.kind == LocalPurchasingProfile.INVOICE && basis.progressSource?.let { it.kind == LocalPurchasingProfile.PROGRESS && it.id == id } == true &&
            basis.payableSource?.let { it.kind == LocalPurchasingProfile.PAYABLE && it.id == id } == true &&
            (basis.creditSource == null || basis.creditSource.kind == LocalPurchasingProfile.INVOICE_CREDITS && basis.creditSource.id == id) &&
            (basis.settlementSource == null || basis.settlementSource.source.kind == LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS && basis.settlementSource.source.id == id))
        check(checkNotNull(basis.progressSource).revision.value == checkNotNull(basis.payableSource).revision.value +
            if (basis.capturedInvoice) 0L else 1L)
        val due = purchasingRemainingDue(checkNotNull(basis.recognized), checkNotNull(basis.appliedCredit), checkNotNull(basis.allocated))
        check(due.remainingDue == basis.remainingDue && checkNotNull(basis.reserved).currency == Currency.EGP && basis.reserved.minor in 0L..due.remainingDue.minor)
    }
}

private fun requireSupplierAllocationShape(allocation: PurchasingSupplierAllocation) {
    requireSupplierBasisShape(allocation.basisBefore)
    check(allocation.amount.currency == Currency.EGP && allocation.amount.minor > 0L && allocation.invoiceId == allocation.basisBefore.invoiceSource?.id &&
        allocation.supplierId == allocation.basisBefore.supplierSource.id && allocation.paymentSource.kind == LocalSupplierSettlementProfile.PAYMENT &&
        allocation.paymentSource.id == allocation.paymentId && allocation.paymentSource.revision == Revision(1))
    PayloadDigest(allocation.paymentSource.digest)
    if (allocation.advanceId == null) check(allocation.advanceSource == null && allocation.advanceAllocationSource == null && allocation.unappliedAdvanceBefore == null)
    else check(allocation.advanceSource?.let { it.kind == LocalSupplierSettlementProfile.ADVANCE && it.id == allocation.advanceId && it.revision == Revision(1) } == true &&
        allocation.advanceAllocationSource?.let { it.kind == LocalSupplierSettlementProfile.ADVANCE_ALLOCATIONS && it.id == allocation.advanceId && it.revision.value >= 1L } == true &&
        allocation.unappliedAdvanceBefore != null)
    purchasingReason(allocation.reasonCode)
}

private fun requireSupplierCashSourcesShape(sources: List<PurchasingSource>, slot: LocalSupplierCashSlot, starting: Boolean) {
    check(sources.size == 4 && sources.map { it.kind }.toSet() == setOf(CashNonSaleStoreProfile.SESSION_KIND,
        LocalSalesStoreProfile.DRAWER_KIND, LocalSalesStoreProfile.CUSTODIAN_KIND, LocalSalesStoreProfile.WORK_KIND))
    sources.forEach { check(it.revision.value >= 1L); PayloadDigest(it.digest) }
    check(sources.single { it.kind == CashNonSaleStoreProfile.SESSION_KIND }.id == slot.target.sessionId &&
        sources.single { it.kind == LocalSalesStoreProfile.CUSTODIAN_KIND }.id == slot.originalActorId &&
        sources.single { it.kind == LocalSalesStoreProfile.WORK_KIND }.id == slot.originalActorId)
    if (starting) check(sources.single { it.kind == CashNonSaleStoreProfile.SESSION_KIND }.revision == slot.domain.sessionRevision &&
        sources.single { it.kind == LocalSalesStoreProfile.DRAWER_KIND }.revision == slot.domain.drawerRevision &&
        sources.single { it.kind == LocalSalesStoreProfile.CUSTODIAN_KIND }.revision == slot.domain.custodianRevision &&
        sources.single { it.kind == LocalSalesStoreProfile.WORK_KIND }.revision == slot.domain.workRevision)
}
