package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

internal fun supplierStartEffects(writes: SupplierSettlementWrites, operationId: OperationId,
    request: LocalSupplierCashRequest.StartCash, supplier: PurchasingSupplierCut, cut: PurchasingInvoiceCut?,
    cash: LocalSupplierCashCut, after: CashSession, slot: LocalSupplierCashSlot,
    nativeChanges: List<AggregateChange>, nativeClaims: List<ClaimChange>): ProposedOutcome.Apply {
    val basis = purchasingSupplierCashBasis(supplier.row, cut)
    check(purchasingSupplierSelectionMatches(request.source, basis) && slot.domain.basisDigest == basis.digest() &&
        slot.target == request.target(operationId))
    val eventId = DomainId(operationId.value)
    val sources = cash.rows.map(PurchasingSource::from)
    val attempt = PurchasingSupplierAttempt(slot.target, request.source, request.amount, basis, sources,
        sha256(LocalSupplierCashJournal.slotWrite(slot).payload.copyBytes()), PurchasingSupplierAttemptStatus.AWAITING_ATTESTATION)
    val event = PurchasingSettlementEvent(eventId, PurchasingSettlementEventKind.START_CASH, slot.target.invoiceId,
        basis, null, null, null, sources, null, null, null)
    val changes = listOf(LocalSupplierCashStorage.sessionWrite(cash, after),
        writes.change(LocalSupplierSettlementProfile.ATTEMPT, request.settlementId, null, PurchasingBody.SettlementAttempt(attempt)),
        writes.change(LocalSupplierSettlementProfile.EVENT, eventId, null, PurchasingBody.SettlementEvent(event))) +
        listOfNotNull(cut?.let { writes.change(LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS, it.invoice.value.id, it.settlements.index,
            supplierInvoiceIndex(it, eventId, settlementId = request.settlementId)) }) + nativeChanges
    val claims = listOf(purchasingAcquisition(LocalSupplierSettlementProfile.ATTEMPT, request.settlementId),
        purchasingAcquisition(LocalSupplierSettlementProfile.EVENT, eventId),
        ClaimChange.Acquire(LocalSupplierCashStorage.sourceClaim(request.attemptId), request.sessionId)) +
        listOfNotNull(cut?.takeIf { it.settlements.index == null }?.let {
            purchasingAcquisition(LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS, it.invoice.value.id) }) + nativeClaims
    return ProposedOutcome.Apply(LocalSupplierCashCodec.result(LocalSupplierCashResult.AttemptStarted(slot.target, request.amount,
        sha256(slot.requirement().canonicalBytes.copyBytes()), supplierCashRevisions(changes))), changes, claims)
}

/** Exact observed raw/application rows; no outcome or missing original is manufactured. */
internal data class SupplierApplicationFact(val slot: LocalSupplierCashSlot, val original: LocalSupplierCashRaw,
    val observed: LocalSupplierCashRaw, val originalSource: PurchasingSource, val resolutionSource: PurchasingSource?,
    val applicationBefore: LocalSupplierCashApplication, val applicationBeforeRevision: Revision)

internal fun supplierApplyEffects(writes: SupplierSettlementWrites, operationId: OperationId, fact: SupplierApplicationFact,
    attempt: PurchasingRow, supplier: PurchasingSupplierCut, cut: PurchasingInvoiceCut?, cash: LocalSupplierCashCut,
    cashChange: SupplierCashChange?, movementWrite: AggregateChange?): ProposedOutcome.Apply {
    val before = (attempt.value.body as PurchasingBody.SettlementAttempt).value
    val target = fact.slot.target
    val outcome = when (val request = LocalSupplierCashCodec.decode(fact.observed.body())) {
        is LocalSupplierCashRequest.RecordOriginal -> request.outcome
        is LocalSupplierCashRequest.RecordResolution -> request.outcome
        else -> error("Purchasing physical outcome required")
    }
    val action = checkNotNull(supplierCashKnownAction(outcome, before.amount))
    val paid = action == LocalSupplierCashAction.CASH_PAID
    check(before.target == target && fact.applicationBefore == LocalSupplierCashFacts.initial(fact.observed) &&
        fact.applicationBeforeRevision == Revision(1) && (cashChange == null) == (action == LocalSupplierCashAction.UNKNOWN) &&
        (movementWrite != null) == paid)
    val phase = fact.observed.phase
    val after = before.copy(status = when (action) {
        LocalSupplierCashAction.UNKNOWN -> PurchasingSupplierAttemptStatus.OUTCOME_UNKNOWN
        LocalSupplierCashAction.NOT_PAID -> PurchasingSupplierAttemptStatus.NOT_PAID
        LocalSupplierCashAction.CASH_PAID -> PurchasingSupplierAttemptStatus.CASH_PAID
    }, originalBodyDigest = fact.original.bodyDigest, resolutionBodyDigest = if (phase == LocalSupplierCashPhase.RESOLUTION) fact.observed.bodyDigest else null,
        applicationOperationIds = before.applicationOperationIds + operationId)
    requireSupplierAttemptTransition(before, after, operationId, action, phase)
    val basis = purchasingSupplierCashBasis(supplier.row, cut)
    val eventId = DomainId(operationId.value)
    val event = PurchasingSettlementEvent(eventId, if (phase == LocalSupplierCashPhase.ORIGINAL) PurchasingSettlementEventKind.APPLY_ORIGINAL
        else PurchasingSettlementEventKind.APPLY_RESOLUTION, target.invoiceId, basis, PurchasingSource.from(attempt.row), null, null,
        cash.rows.map(PurchasingSource::from), fact.originalSource, fact.resolutionSource,
        if (phase == LocalSupplierCashPhase.RESOLUTION) before.applicationOperationIds.single() else null)
    val changes = mutableListOf(writes.change(LocalSupplierSettlementProfile.ATTEMPT, target.settlementId, attempt,
        PurchasingBody.SettlementAttempt(after)), writes.change(LocalSupplierSettlementProfile.EVENT, eventId, null, PurchasingBody.SettlementEvent(event)))
    val claims = mutableListOf(purchasingAcquisition(LocalSupplierSettlementProfile.EVENT, eventId))
    cashChange?.let { changes += LocalSupplierCashStorage.sessionWrite(cash, it.session) }
    movementWrite?.let { changes += it }
    if (paid) {
        val payment = PurchasingSupplierPayment(target.settlementId, target.supplierId, target, before.amount, phase,
            fact.original.bodyDigest, fact.observed.bodyDigest, supplierEvidence(outcome), PurchasingSource.from(attempt.row), target.attemptId)
        val paymentWrite = writes.change(LocalSupplierSettlementProfile.PAYMENT, target.settlementId, null, PurchasingBody.SupplierPayment(payment))
        changes += paymentWrite; claims += purchasingAcquisition(LocalSupplierSettlementProfile.PAYMENT, target.settlementId)
        val paymentSource = PurchasingSource(paymentWrite.key.kind, paymentWrite.key.id, Revision(1), sha256(paymentWrite.payload.copyBytes()).value)
        if (cut != null) {
            val allocationId = checkNotNull(target.allocationId)
            val allocation = PurchasingSupplierAllocation(allocationId, checkNotNull(target.invoiceId), target.supplierId, payment.id, null,
                before.amount, basis, paymentSource, null, null, null, fact.slot.domain.reasonCode)
            changes += writes.change(LocalSupplierSettlementProfile.ALLOCATION, allocationId, null, PurchasingBody.SettlementAllocation(allocation))
            claims += purchasingAcquisition(LocalSupplierSettlementProfile.ALLOCATION, allocationId)
        } else {
            changes += writes.change(LocalSupplierSettlementProfile.ADVANCE, target.settlementId, null,
                PurchasingBody.SupplierAdvance(PurchasingSupplierAdvance(target.settlementId, target.supplierId, payment.id, paymentSource, before.amount)))
            changes += writes.change(LocalSupplierSettlementProfile.ADVANCE_ALLOCATIONS, target.settlementId, null,
                PurchasingBody.AdvanceAllocations(target.settlementId, emptyList()))
            claims += purchasingAcquisition(LocalSupplierSettlementProfile.ADVANCE, target.settlementId)
            claims += purchasingAcquisition(LocalSupplierSettlementProfile.ADVANCE_ALLOCATIONS, target.settlementId)
        }
    }
    cut?.let { changes += writes.change(LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS, it.invoice.value.id, it.settlements.index,
        supplierInvoiceIndex(it, eventId, allocationId = if (paid) target.allocationId else null)) }
    val revisions = (supplierCashRevisions(changes) + LocalSupplierCashRevision(LocalSupplierCashProfile.APPLICATION,
        DomainId(target.factOperation(phase).value), Revision(2))).sortedWith(compareBy<LocalSupplierCashRevision> { it.kind }.thenBy { it.id.value })
    val result = LocalSupplierCashResult.Applied(target, action, operationId, if (paid) before.amount else null,
        basis.remainingDue?.let { if (paid) it - before.amount else it }, if (paid && cut == null) before.amount else null, revisions, phase)
    changes += LocalSupplierCashFacts.applicationWrite(fact.applicationBefore.copy(applicationOperationId = operationId, result = result), fact.applicationBeforeRevision)
    return ProposedOutcome.Apply(LocalSupplierCashCodec.result(result), changes, claims)
}

internal fun supplierAdvanceAllocationEffects(writes: SupplierSettlementWrites, operationId: OperationId,
    request: LocalAllocateSupplierAdvanceV1, supplier: PurchasingSupplierCut, cut: PurchasingInvoiceCut,
    advance: PurchasingAdvanceCut): ProposedOutcome.Apply {
    val basis = purchasingSupplierCashBasis(supplier.row, cut)
    val payment = (advance.payment.value.body as PurchasingBody.SupplierPayment).value
    val allocation = PurchasingSupplierAllocation(request.allocationId, request.invoice.invoiceId, request.invoice.supplierId,
        payment.id, request.advanceId, request.amount, basis, PurchasingSource.from(advance.payment.row),
        PurchasingSource.from(advance.advance.row), PurchasingSource.from(advance.index.row), advance.unapplied, request.reasonCode)
    val eventId = DomainId(operationId.value)
    val event = PurchasingSettlementEvent(eventId, PurchasingSettlementEventKind.ALLOCATE_ADVANCE, request.invoice.invoiceId,
        basis, null, PurchasingSource.from(advance.advance.row), PurchasingSource.from(advance.index.row), emptyList(), null, null, null)
    val previous = advance.index.value.body as PurchasingBody.AdvanceAllocations
    val changes = listOf(writes.change(LocalSupplierSettlementProfile.ALLOCATION, request.allocationId, null, PurchasingBody.SettlementAllocation(allocation)),
        writes.change(LocalSupplierSettlementProfile.EVENT, eventId, null, PurchasingBody.SettlementEvent(event)),
        writes.change(LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS, request.invoice.invoiceId, cut.settlements.index,
            supplierInvoiceIndex(cut, eventId, allocationId = request.allocationId)),
        writes.change(LocalSupplierSettlementProfile.ADVANCE_ALLOCATIONS, request.advanceId, advance.index,
            previous.copy(allocationIds = previous.allocationIds + request.allocationId)))
    val claims = listOf(purchasingAcquisition(LocalSupplierSettlementProfile.ALLOCATION, request.allocationId),
        purchasingAcquisition(LocalSupplierSettlementProfile.EVENT, eventId)) + if (cut.settlements.index == null)
        listOf(purchasingAcquisition(LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS, request.invoice.invoiceId)) else emptyList()
    val result = LocalSupplierAdvanceAllocationResultV1(request.allocationId, request.invoice.invoiceId, request.advanceId, request.amount,
        checkNotNull(basis.remainingDue) - request.amount, advance.unapplied - request.amount,
        (cut.settlements.index?.row?.revision ?: Revision(0)).next(), advance.index.row.revision.next())
    return ProposedOutcome.Apply(purchasingEncode(result, LocalSupplierAdvanceAllocationResultV1.serializer()), changes, claims)
}
