package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.*

/** Values at an actual retained index generation, not today's mutable balance. */
internal fun PurchasingSettlementLedger.at(source: PurchasingSettlementIndexSource?): Pair<Money, Money> {
    if (source == null) return purchaseZero() to purchaseZero()
    val image = indexHistory.singleOrNull { it.source == source.source && it.record.producer.operationId == source.operationId }
        ?: error("Purchasing original settlement generation unavailable")
    return image.allocated to image.reserved
}

internal fun purchasingAvailableToAllocate(cut: PurchasingInvoiceCut): Money? = purchasingDue(cut)?.let {
    check(cut.settlements.reservedTotal.currency == it.remainingDue.currency &&
        cut.settlements.reservedTotal.minor in 0L..it.remainingDue.minor)
    it.remainingDue - cut.settlements.reservedTotal
}

/** Strict current ownership of immutable source funds and both allocation indexes. */
internal suspend fun PurchasingReader.advance(id: DomainId): PurchasingAdvanceCut? {
    val advance = row(LocalSupplierSettlementProfile.ADVANCE, id) ?: run {
        check(row(LocalSupplierSettlementProfile.ADVANCE_ALLOCATIONS, id) == null)
        return null
    }
    val value = (advance.value.body as PurchasingBody.SupplierAdvance).value
    check(advance.row.revision == Revision(1) && value.id == id && value.amount.currency == Currency.EGP && value.amount.minor > 0L)
    val payment = row(LocalSupplierSettlementProfile.PAYMENT, value.paymentId) ?: error("Purchasing advance payment unavailable")
    val paid = (payment.value.body as PurchasingBody.SupplierPayment).value
    supplierAttempt(paid.target.settlementId) ?: error("Purchasing advance payout attempt unavailable")
    check(payment.row.revision == Revision(1) && paid.id == id && value.paymentId == id && paid.supplierId == value.supplierId &&
        paid.target.invoiceId == null && paid.target.allocationId == null && value.amount == paid.amount &&
        value.paymentSource == PurchasingSource.from(payment.row))
    val index = row(LocalSupplierSettlementProfile.ADVANCE_ALLOCATIONS, id) ?: error("Purchasing advance allocation index unavailable")
    val state = index.value.body as PurchasingBody.AdvanceAllocations
    check(state.advanceId == id && state.allocationIds.size <= LocalSupplierSettlementProfile.MAX_ENTRIES &&
        state.allocationIds.distinct().size == state.allocationIds.size && index.row.revision.value == state.allocationIds.size.toLong() + 1L)
    var allocated = purchaseZero()
    var previousSource = originalSource(advance, LocalSupplierSettlementProfile.ADVANCE_ALLOCATIONS, id)
    val initial = originalCommand(advance.value.producer.operationId).aggregateWrites.single {
        it.key == purchasingKey(LocalSupplierSettlementProfile.ADVANCE_ALLOCATIONS, id)
    }
    check(decodePurchasingRecord(initial.payload).body == PurchasingBody.AdvanceAllocations(id, emptyList()))
    val entries = state.allocationIds.mapIndexed { position, allocationId ->
        val entry = row(LocalSupplierSettlementProfile.ALLOCATION, allocationId) ?: error("Purchasing advance allocation unavailable")
        val allocation = (entry.value.body as PurchasingBody.SettlementAllocation).value
        check(entry.row.revision == Revision(1) && allocation.id == allocationId && allocation.advanceId == id &&
            allocation.supplierId == value.supplierId && allocation.paymentId == paid.id && allocation.paymentSource == value.paymentSource &&
            allocation.advanceSource == PurchasingSource.from(advance.row) && allocation.advanceAllocationSource == previousSource &&
            allocation.unappliedAdvanceBefore == value.amount - allocated && allocation.amount.currency == value.amount.currency &&
            allocation.amount.minor in 1L..(value.amount - allocated).minor)
        val invoiceIndex = row(LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS, allocation.invoiceId)
            ?: error("Purchasing advance allocation lost invoice membership")
        check(allocationId in (invoiceIndex.value.body as PurchasingBody.InvoiceSettlements).allocationIds)
        val original = originalCommand(entry.value.producer.operationId)
        val after = original.aggregateWrites.single { it.key == purchasingKey(LocalSupplierSettlementProfile.ADVANCE_ALLOCATIONS, id) }
        check(after.storageGeneration == Revision(position.toLong() + 2L) &&
            decodePurchasingRecord(after.payload).body == PurchasingBody.AdvanceAllocations(id, state.allocationIds.take(position + 1)))
        previousSource = purchasingSource(after)
        allocated += allocation.amount
        entry
    }
    check(previousSource == PurchasingSource.from(index.row) && allocated.minor <= value.amount.minor)
    return PurchasingAdvanceCut(advance, payment, index, entries, allocated, value.amount - allocated).also { consumeSupplierAdvance(it) }
}

/**
 * Every index mutation has an immutable event and exact retained predecessor. Walking this
 * chain validates the original reservation and allocation prefixes even after later matches,
 * credits, settlements, UNKNOWN applications or known-not-paid releases. No synthetic row,
 * employee session, CommandReadView or historical authorization is constructed.
 */
internal suspend fun PurchasingReader.invoiceSettlements(
    invoice: PurchasingRow, payable: PurchasingRow?, matches: List<PurchasingMatchFact>,
): PurchasingSettlementLedger {
    val value = purchasingInvoiceClaim(invoice)
    val index = row(LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS, value.id)
        ?: return PurchasingSettlementLedger(null, emptyList(), emptyList(), purchaseZero(), purchaseZero(), emptyList())
    check(payable != null)
    var image = PurchasingSettlementIndexImage(PurchasingSource.from(index.row), index.value,
        index.value.body as PurchasingBody.InvoiceSettlements)
    val reverse = mutableListOf<PurchasingSettlementIndexImage>()
    val eventIds = mutableSetOf<DomainId>()
    while (true) {
        if (reverse.size >= LocalSupplierSettlementProfile.MAX_INDEX_HISTORY) throw PurchasingHydrationCapacityUnavailable()
        val state = image.body
        check(state.invoiceId == value.id && image.source.id == value.id &&
            image.source.kind == LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS && image.source.revision.value >= 1L &&
            state.allocationIds.size <= LocalSupplierSettlementProfile.MAX_ENTRIES &&
            state.settlementIds.size <= LocalSupplierSettlementProfile.MAX_ENTRIES &&
            state.allocationIds.distinct().size == state.allocationIds.size && state.settlementIds.distinct().size == state.settlementIds.size &&
            eventIds.add(state.lastEventId))
        val original = originalCommand(image.record.producer.operationId)
        val eventWrite = original.aggregateWrites.single { it.key == purchasingKey(LocalSupplierSettlementProfile.EVENT, state.lastEventId) }
        val eventRecord = decodePurchasingRecord(eventWrite.payload)
        val event = (eventRecord.body as PurchasingBody.SettlementEvent).value
        check(eventWrite.storageGeneration == Revision(1) && event.id == state.lastEventId &&
            event.id.value == original.operationId.value && event.invoiceId == value.id && eventRecord.producer == image.record.producer)
        reverse += image
        val predecessor = event.basisBefore.settlementSource
        if (predecessor == null) { check(image.source.revision == Revision(1)); break }
        check(predecessor.source.kind == image.source.kind && predecessor.source.id == value.id &&
            predecessor.source.revision.next() == image.source.revision && predecessor.operationId != original.operationId)
        image = originalImage(predecessor)
    }
    val images = mutableListOf<PurchasingSettlementIndexImage>()
    var allocationIds = emptyList<DomainId>()
    var settlementIds = emptyList<DomainId>()
    val attempts = mutableMapOf<DomainId, PurchasingSupplierAttempt>()
    val attemptSources = mutableMapOf<DomainId, PurchasingSource>()
    var allocated = purchaseZero()
    var reserved = purchaseZero()
    for (current in reverse.asReversed()) {
        val original = originalCommand(current.record.producer.operationId)
        val event = (decodePurchasingRecord(original.aggregateWrites.single {
            it.key == purchasingKey(LocalSupplierSettlementProfile.EVENT, current.body.lastEventId)
        }.payload).body as PurchasingBody.SettlementEvent).value
        requireSettlementRecognitionBasis(invoice, matches, event.basisBefore)
        val credit = creditPrefix(invoice, event.basisBefore.creditSource)
        check(event.basisBefore.appliedCredit == credit && event.basisBefore.allocated == allocated &&
            event.basisBefore.reserved == reserved && event.basisBefore.remainingDue ==
            purchasingRemainingDue(checkNotNull(event.basisBefore.recognized), credit, allocated).remainingDue &&
            reserved.minor <= checkNotNull(event.basisBefore.remainingDue).minor)
        check(event.basisBefore.settlementSource == images.lastOrNull()?.let {
            PurchasingSettlementIndexSource(it.source, it.record.producer.operationId)
        })
        when (event.kind) {
            PurchasingSettlementEventKind.START_CASH -> {
                val request = LocalSupplierCashCodec.decode(original.normalized) as LocalSupplierCashRequest.StartCash
                val selected = request.source as LocalSupplierCashSource.Invoice
                observeSupplierCashStart(LocalSupplierCashFacts.requireStart(original))
                check(selected.invoiceId == value.id && selected.supplierId == value.supplierId &&
                    purchasingSupplierSelectionMatches(selected, event.basisBefore) && request.settlementId !in settlementIds &&
                    event.attemptSource == null && event.originalSource == null && event.resolutionSource == null)
                val write = original.aggregateWrites.single { it.key == purchasingKey(LocalSupplierSettlementProfile.ATTEMPT, request.settlementId) }
                val attempt = (decodePurchasingRecord(write.payload).body as PurchasingBody.SettlementAttempt).value
                check(attempt.status == PurchasingSupplierAttemptStatus.AWAITING_ATTESTATION && attempt.basisBefore == event.basisBefore &&
                    attempt.cashSourcesBefore == event.cashSourcesBefore && attempt.amount == request.amount &&
                    attempt.amount.minor <= (checkNotNull(event.basisBefore.remainingDue) - reserved).minor)
                attempts[request.settlementId] = attempt; attemptSources[request.settlementId] = purchasingSource(write)
                settlementIds += request.settlementId; reserved += attempt.amount
            }
            PurchasingSettlementEventKind.APPLY_ORIGINAL, PurchasingSettlementEventKind.APPLY_RESOLUTION -> {
                val result = LocalSupplierCashCodec.result(original.result) as LocalSupplierCashResult.Applied
                val id = result.target.settlementId
                val before = attempts[id] ?: error("Purchasing application lost its Start")
                check(event.attemptSource == attemptSources.getValue(id) && result.target == before.target)
                val write = original.aggregateWrites.single { it.key == purchasingKey(LocalSupplierSettlementProfile.ATTEMPT, id) }
                val after = (decodePurchasingRecord(write.payload).body as PurchasingBody.SettlementAttempt).value
                requireSupplierAttemptTransition(before, after, original.operationId, result.action, result.phase)
                requireSupplierApplicationOriginals(original, before, event, result)
                if (result.action != LocalSupplierCashAction.UNKNOWN) reserved -= before.amount
                if (result.action == LocalSupplierCashAction.CASH_PAID) {
                    val allocationId = checkNotNull(before.target.allocationId)
                    val allocation = row(LocalSupplierSettlementProfile.ALLOCATION, allocationId)
                        ?: error("Purchasing paid allocation unavailable")
                    val allocatedValue = (allocation.value.body as PurchasingBody.SettlementAllocation).value
                    check(allocatedValue.basisBefore == event.basisBefore && allocatedValue.advanceId == null &&
                        allocatedValue.id == allocationId && allocatedValue.invoiceId == value.id &&
                        allocatedValue.supplierId == value.supplierId && allocatedValue.amount == before.amount)
                    requireAllocationFunds(allocatedValue)
                    allocationIds += allocationId; allocated += before.amount
                }
                attempts[id] = after; attemptSources[id] = purchasingSource(write)
            }
            PurchasingSettlementEventKind.ALLOCATE_ADVANCE -> {
                val request = LocalSupplierSettlementCodec.allocation(original.normalized)
                check(request.invoice.invoiceId == value.id && purchasingSupplierSelectionMatches(request.invoice, event.basisBefore))
                val entry = row(LocalSupplierSettlementProfile.ALLOCATION, request.allocationId)
                    ?: error("Purchasing explicit advance allocation unavailable")
                val allocation = (entry.value.body as PurchasingBody.SettlementAllocation).value
                check(allocation.basisBefore == event.basisBefore && allocation.invoiceId == value.id &&
                    allocation.supplierId == value.supplierId && allocation.advanceId == request.advanceId && allocation.amount == request.amount &&
                    request.amount.minor <= (checkNotNull(event.basisBefore.remainingDue) - reserved).minor)
                requireAllocationFunds(allocation)
                allocationIds += request.allocationId; allocated += request.amount
            }
        }
        check(current.body.allocationIds == allocationIds && current.body.settlementIds == settlementIds)
        check(supplierInvoiceHeadroomFits(images.size + 1, allocationIds.size, attempts.values))
        check(allocated.minor >= 0L && reserved.minor >= 0L)
        images += current.copy(allocated = allocated, reserved = reserved)
    }
    val currentAllocations = allocationIds.map { row(LocalSupplierSettlementProfile.ALLOCATION, it) ?: error("Purchasing allocation unavailable") }
    val currentAttempts = settlementIds.map { id ->
        val current = row(LocalSupplierSettlementProfile.ATTEMPT, id) ?: error("Purchasing attempt unavailable")
        check(PurchasingSource.from(current.row) == attemptSources.getValue(id) &&
            (current.value.body as PurchasingBody.SettlementAttempt).value == attempts.getValue(id))
        current
    }
    return PurchasingSettlementLedger(index, currentAllocations, currentAttempts, allocated, reserved, images)
}

internal fun purchasingSupplierSelectionMatches(selection: LocalSupplierCashSource, basis: PurchasingSupplierCashBasis): Boolean {
    if (basis.supplierSource.kind != LocalConfigurationProfile.SUPPLIER || basis.supplierSource.id != selection.supplierId) return false
    return when (selection) {
        is LocalSupplierCashSource.Advance -> basis.invoiceSource == null && basis.progressSource == null && basis.payableSource == null &&
            basis.creditSource == null && basis.settlementSource == null && basis.recognized == null && basis.appliedCredit == null &&
            basis.allocated == null && basis.reserved == null && basis.remainingDue == null && !basis.capturedInvoice &&
            selection.expectedSupplierRevision == basis.supplierSource.revision
        is LocalSupplierCashSource.Invoice -> selection.expectedSupplierRevision == basis.supplierSource.revision &&
            basis.invoiceSource?.let { it.kind == LocalPurchasingProfile.INVOICE && it.id == selection.invoiceId && it.revision == selection.expectedInvoiceRevision } == true &&
            basis.progressSource?.let { it.kind == LocalPurchasingProfile.PROGRESS && it.id == selection.invoiceId && it.revision == selection.expectedProgressRevision } == true &&
            basis.payableSource?.let { it.kind == LocalPurchasingProfile.PAYABLE && it.id == selection.invoiceId && it.revision == selection.expectedPayableRevision } == true &&
            basis.creditSource?.revision == selection.expectedCreditRevision &&
            basis.settlementSource?.source?.revision == selection.expectedSettlementRevision &&
            basis.remainingDue == selection.expectedRemainingDue && basis.reserved == selection.expectedReservedCash
    }
}

internal suspend fun PurchasingReader.creditPrefix(invoice: PurchasingRow, selected: PurchasingSource?): Money {
    if (selected == null) return purchaseZero()
    val id = invoice.value.id
    check(selected.kind == LocalPurchasingProfile.INVOICE_CREDITS && selected.id == id &&
        selected.revision.value in 1L..LocalPurchasingProfile.MAX_CREDITS.toLong())
    PayloadDigest(selected.digest)
    val index = row(LocalPurchasingProfile.INVOICE_CREDITS, id) ?: error("Purchasing credit prefix unavailable")
    val state = index.value.body as PurchasingBody.InvoiceCredits
    check(selected.revision <= index.row.revision)
    val ids = state.creditIds.take(selected.revision.value.toInt())
    check(ids.size.toLong() == selected.revision.value)
    var total = purchaseZero()
    var last: PurchasingRow? = null
    for (creditId in ids) {
        val entry = row(LocalPurchasingProfile.CREDIT, creditId) ?: error("Purchasing credit prefix source unavailable")
        val body = entry.value.body as PurchasingBody.Credit
        check(body.request.invoiceId == id && body.supplierId == purchasingInvoiceClaim(invoice).supplierId &&
            body.request.amount.currency == Currency.EGP && body.request.amount.minor > 0L)
        total += body.request.amount; last = entry
    }
    val final = checkNotNull(last)
    check(originalSource(final, LocalPurchasingProfile.INVOICE_CREDITS, id) == selected &&
        originalCreditIndex(final, id) == PurchasingBody.InvoiceCredits(id, ids))
    return total
}

internal suspend fun PurchasingReader.requireSettlementRecognitionBasis(invoice: PurchasingRow,
    matches: List<PurchasingMatchFact>, basis: PurchasingSupplierCashBasis) {
    val id = invoice.value.id
    val count = checkNotNull(basis.payableSource).revision.value
    check(basis.invoiceSource == PurchasingSource.from(invoice.row) && count in 1L..matches.size.toLong() &&
        basis.progressSource?.revision?.value == count + purchasingProgressOffset(invoice) &&
        basis.capturedInvoice == purchasingInvoiceClaim(invoice).captured)
    check(purchasingCaptureSettlementPrefixEligible(invoice, matches.take(count.toInt()))) {
        "Unresolved captured invoice cannot authorize settlement"
    }
    val matching = row(LocalPurchasingProfile.MATCH, matches[count.toInt() - 1].request.matchId)
        ?: error("Purchasing original recognition unavailable")
    check(basis.progressSource == originalSource(matching, LocalPurchasingProfile.PROGRESS, id) &&
        basis.payableSource == originalSource(matching, LocalPurchasingProfile.PAYABLE, id) &&
        basis.recognized == matches.take(count.toInt()).fold(purchaseZero()) { total, event -> total + event.net + event.tax })
}

internal suspend fun PurchasingReader.requireAllocationFunds(allocation: PurchasingSupplierAllocation) {
    check(allocation.amount.currency == Currency.EGP && allocation.amount.minor > 0L)
    val payment = row(LocalSupplierSettlementProfile.PAYMENT, allocation.paymentId) ?: error("Purchasing source payment unavailable")
    val paid = (payment.value.body as PurchasingBody.SupplierPayment).value
    check(payment.row.revision == Revision(1) && PurchasingSource.from(payment.row) == allocation.paymentSource && paid.supplierId == allocation.supplierId)
    supplierAttempt(paid.target.settlementId) ?: error("Purchasing source payout attempt unavailable")
    if (allocation.advanceId == null) {
        check(paid.target.invoiceId == allocation.invoiceId && paid.target.allocationId == allocation.id && paid.amount == allocation.amount &&
            allocation.advanceSource == null && allocation.advanceAllocationSource == null && allocation.unappliedAdvanceBefore == null)
    } else {
        val advance = advance(allocation.advanceId) ?: error("Purchasing source advance unavailable")
        check(advance.payment.row.key.id == paid.id && advance.advance.row.key.id == allocation.advanceId &&
            allocation.id in (advance.index.value.body as PurchasingBody.AdvanceAllocations).allocationIds &&
            PurchasingSource.from(advance.advance.row) == allocation.advanceSource)
    }
}

internal fun requireSupplierAttemptTransition(before: PurchasingSupplierAttempt, after: PurchasingSupplierAttempt,
    applicationOperationId: OperationId, action: LocalSupplierCashAction, phase: LocalSupplierCashPhase) {
    check(after.target == before.target && after.source == before.source && after.amount == before.amount &&
        after.basisBefore == before.basisBefore && after.cashSourcesBefore == before.cashSourcesBefore && after.slotDigest == before.slotDigest &&
        after.applicationOperationIds == before.applicationOperationIds + applicationOperationId)
    if (phase == LocalSupplierCashPhase.ORIGINAL) check(before.status == PurchasingSupplierAttemptStatus.AWAITING_ATTESTATION &&
        before.originalBodyDigest == null && before.resolutionBodyDigest == null && before.applicationOperationIds.isEmpty() &&
        after.originalBodyDigest != null && after.resolutionBodyDigest == null)
    else check(before.status == PurchasingSupplierAttemptStatus.OUTCOME_UNKNOWN && before.originalBodyDigest != null &&
        before.resolutionBodyDigest == null && before.applicationOperationIds.size == 1 && after.originalBodyDigest == before.originalBodyDigest &&
        after.resolutionBodyDigest != null && action != LocalSupplierCashAction.UNKNOWN)
    check(after.status == when (action) {
        LocalSupplierCashAction.UNKNOWN -> PurchasingSupplierAttemptStatus.OUTCOME_UNKNOWN
        LocalSupplierCashAction.CASH_PAID -> PurchasingSupplierAttemptStatus.CASH_PAID
        LocalSupplierCashAction.NOT_PAID -> PurchasingSupplierAttemptStatus.NOT_PAID
    })
}

/** Fully retained physical originals and exact application producer, not an ID/result-only join. */
internal suspend fun PurchasingReader.requireSupplierApplicationOriginals(producer: RetainedCommandEvidence,
    before: PurchasingSupplierAttempt, event: PurchasingSettlementEvent, result: LocalSupplierCashResult.Applied) {
    val target = before.target
    val start = originalCommand(target.intentOperationId)
    val slot = LocalSupplierCashFacts.requireStart(start)
    observeSupplierCashStart(slot)
    observeSupplierCashSources(event.cashSourcesBefore)
    check(slot.target == target && slot.domain.basisDigest == before.basisBefore.digest() &&
        before.slotDigest == com.ukkera.pos.security.sha256(LocalSupplierCashJournal.slotWrite(slot).payload.copyBytes()))
    val original = originalCommand(target.recordingOperationId)
    val raw = LocalSupplierCashFacts.requireRaw(original, start, LocalSupplierCashPhase.ORIGINAL)
    check(event.originalSource == purchasingSource(original.aggregateWrites.single { it.key == LocalSupplierCashJournal.rawKey(target.recordingOperationId) }))
    val observed = if (result.phase == LocalSupplierCashPhase.RESOLUTION) {
        val resolution = originalCommand(target.resolutionOperationId)
        val resolved = LocalSupplierCashFacts.requireRaw(resolution, start, LocalSupplierCashPhase.RESOLUTION)
        val request = LocalSupplierCashCodec.decode(resolved.body()) as LocalSupplierCashRequest.RecordResolution
        LocalSupplierCashResolutionDescriptor.create(slot.requirement(), raw.body(), slot.originalActorId, resolved.body())
        check(request.originalBodyDigest == raw.bodyDigest && before.originalBodyDigest == raw.bodyDigest &&
            event.resolutionSource == purchasingSource(resolution.aggregateWrites.single { it.key == LocalSupplierCashJournal.resolutionKey(target.resolutionOperationId) }) &&
            event.originalUnknownApplicationOperationId == before.applicationOperationIds.single())
        resolved
    } else { check(event.resolutionSource == null && event.originalUnknownApplicationOperationId == null); raw }
    val outcome = when (val request = LocalSupplierCashCodec.decode(observed.body())) {
        is LocalSupplierCashRequest.RecordOriginal -> request.outcome
        is LocalSupplierCashRequest.RecordResolution -> request.outcome
        else -> error("Purchasing physical outcome unavailable")
    }
    check(result.action == supplierCashKnownAction(outcome, before.amount))
    val application = LocalSupplierCashFacts.decodeApplication(producer.aggregateWrites.single {
        it.key == LocalSupplierCashJournal.applicationKey(target.factOperation(result.phase))
    }.payload)
    check(application == LocalSupplierCashFacts.initial(observed).copy(applicationOperationId = producer.operationId, result = result))
    val after = (decodePurchasingRecord(producer.aggregateWrites.single {
        it.key == purchasingKey(LocalSupplierSettlementProfile.ATTEMPT, target.settlementId)
    }.payload).body as PurchasingBody.SettlementAttempt).value
    check(after.originalBodyDigest == raw.bodyDigest && after.resolutionBodyDigest ==
        if (result.phase == LocalSupplierCashPhase.RESOLUTION) observed.bodyDigest else null)
}

internal fun supplierCashKnownAction(outcome: LocalSupplierCashOutcome, authorizedAmount: Money): LocalSupplierCashAction? = when (outcome) {
    is LocalSupplierCashOutcome.Unknown -> LocalSupplierCashAction.UNKNOWN
    is LocalSupplierCashOutcome.NotPaid -> LocalSupplierCashAction.NOT_PAID
    is LocalSupplierCashOutcome.CashPaid -> if (outcome.supportedAmountOrNull() == authorizedAmount) LocalSupplierCashAction.CASH_PAID else null
    is LocalSupplierCashOutcome.ExternalAttested -> null
}
