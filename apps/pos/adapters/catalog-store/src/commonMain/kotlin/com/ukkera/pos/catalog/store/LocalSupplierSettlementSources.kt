package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.purchaseZero
import com.ukkera.pos.recovery.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

internal data class PurchasingSupplierCut(val row: StoredAggregate, val value: LocalSupplierV1, val actorId: DomainId)
internal suspend fun supplierSettlementSupplier(view: CommandReadView, id: DomainId): PurchasingSupplierCut? {
    val key = LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, id)
    val row = view.aggregate(key) ?: return null
    val record = decodeRecord(view.scope, row)
    val value = (record.body as ConfigurationBody.Supplier).value
    check(value.id == id)
    return PurchasingSupplierCut(row, value, record.authorizedBy)
}
internal suspend fun supplierSettlementSupplier(command: RuntimeHistoricalCommand, id: DomainId): PurchasingSupplierCut? {
    val row = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, id))?.row ?: return null
    val record = decodeRecord(command.scope, row)
    val value = (record.body as ConfigurationBody.Supplier).value
    check(value.id == id)
    return PurchasingSupplierCut(row, value, record.authorizedBy)
}

internal fun supplierCashCommitment(source: LocalSupplierCashSource, settlementId: DomainId, amount: Money): SupplierCashCommitment =
    SupplierCashCommitment(source.supplierId, (source as? LocalSupplierCashSource.Invoice)?.invoiceId, settlementId,
        when (source) { is LocalSupplierCashSource.Invoice -> source.expectedProgressRevision; is LocalSupplierCashSource.Advance -> source.expectedSupplierRevision }, amount)
internal fun supplierEvidence(outcome: LocalSupplierCashOutcome): LocalCashEvidenceV1 = when (outcome) {
    is LocalSupplierCashOutcome.Unknown -> outcome.evidence
    is LocalSupplierCashOutcome.NotPaid -> outcome.evidence
    is LocalSupplierCashOutcome.CashPaid -> outcome.evidence
    is LocalSupplierCashOutcome.ExternalAttested -> outcome.evidence
}
internal fun LocalCashEvidenceV1.supplierDomain(actorId: DomainId): CashierEvidence = CashierEvidence(evidenceId, actorId, occurredAt.value)
internal fun LocalSupplierCashStoredFact.outcome(): LocalSupplierCashOutcome = when (val request = LocalSupplierCashCodec.decode(value.body())) {
    is LocalSupplierCashRequest.RecordOriginal -> request.outcome
    is LocalSupplierCashRequest.RecordResolution -> request.outcome
    else -> error("Purchasing supplier physical fact unavailable")
}

internal val supplierCashBarriers: Set<RecoveryResourceKind> = purchasingRecoveryBarriers + setOf(RecoveryResourceKind.CASH_CUSTODY, RecoveryResourceKind.ORDER_CUSTODY)
internal fun supplierSettlementResources(supplier: PurchasingSupplierCut, cut: PurchasingInvoiceCut?, target: LocalSupplierCashTarget,
    cash: LocalSupplierCashCut?): Set<RecoveryResourceKey> = setOf(
    supplierSettlementResource(target.supplierId), supplierSettlementResource(target.settlementId), supplierSettlementResource(target.attemptId),
    RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, supplier.actorId), RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, target.sessionId),
) + listOfNotNull(target.invoiceId, target.allocationId).map(::supplierSettlementResource) + cash?.resources.orEmpty() +
    cut?.let { purchasingResources((it.order.value.body as PurchasingBody.Order).value) + setOf(
        RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, purchasingInvoiceClaim(it.invoice).sourceEvidenceId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.order.value.producer.actorId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.invoice.value.producer.actorId)) }.orEmpty()
internal fun supplierSettlementResource(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, id)

/** Whole mutable attempt history and one literal original payout, not a current-row shortcut. */
internal suspend fun PurchasingReader.supplierAttempt(id: DomainId): PurchasingRow? {
    val current = row(LocalSupplierSettlementProfile.ATTEMPT, id) ?: return null
    val value = (current.value.body as PurchasingBody.SettlementAttempt).value
    val start = originalCommand(value.target.intentOperationId)
    val slot = LocalSupplierCashFacts.requireStart(start)
    observeSupplierCashStart(slot)
    val startWrite = start.aggregateWrites.single { it.key == purchasingKey(LocalSupplierSettlementProfile.ATTEMPT, id) }
    var before = (decodePurchasingRecord(startWrite.payload).body as PurchasingBody.SettlementAttempt).value
    var source = purchasingSource(startWrite)
    check(slot.target == value.target && before.basisBefore.digest() == slot.domain.basisDigest && before.target == value.target &&
        before.status == PurchasingSupplierAttemptStatus.AWAITING_ATTESTATION && before.applicationOperationIds.isEmpty())
    for (applicationId in value.applicationOperationIds) {
        val producer = originalCommand(applicationId)
        val result = LocalSupplierCashCodec.result(producer.result) as LocalSupplierCashResult.Applied
        val event = (decodePurchasingRecord(producer.aggregateWrites.single {
            it.key == purchasingKey(LocalSupplierSettlementProfile.EVENT, DomainId(applicationId.value))
        }.payload).body as PurchasingBody.SettlementEvent).value
        check(event.attemptSource == source && result.target == value.target)
        val afterWrite = producer.aggregateWrites.single { it.key == purchasingKey(LocalSupplierSettlementProfile.ATTEMPT, id) }
        val after = (decodePurchasingRecord(afterWrite.payload).body as PurchasingBody.SettlementAttempt).value
        requireSupplierAttemptTransition(before, after, applicationId, result.action, result.phase)
        requireSupplierApplicationOriginals(producer, before, event, result)
        if (result.action == LocalSupplierCashAction.CASH_PAID) {
            val paymentWrite = producer.aggregateWrites.single { it.key == purchasingKey(LocalSupplierSettlementProfile.PAYMENT, id) }
            val payment = (decodePurchasingRecord(paymentWrite.payload).body as PurchasingBody.SupplierPayment).value
            val rawProducer = originalCommand(value.target.factOperation(result.phase))
            val raw = LocalSupplierCashFacts.requireRaw(rawProducer, start, result.phase)
            val outcome = when (val request = LocalSupplierCashCodec.decode(raw.body())) {
                is LocalSupplierCashRequest.RecordOriginal -> request.outcome
                is LocalSupplierCashRequest.RecordResolution -> request.outcome
                else -> error("Purchasing original payout unavailable")
            }
            check(payment.evidence == supplierEvidence(outcome) && payment.amount == value.amount)
            val movement = SupplierCashSettlements.payoutMovement(value.commitment(), value.target.sessionId, slot.originalActorId,
                value.target.attemptId, applicationId, payment.evidence.supplierDomain(slot.originalActorId), slot.domain.reasonCode)
            LocalSupplierCashStorage.requirePayoutWrite(producer, movement)
            val retainedPayment = row(LocalSupplierSettlementProfile.PAYMENT, id) ?: error("Purchasing payment unavailable")
            check(PurchasingSource.from(retainedPayment.row) == purchasingSource(paymentWrite))
            val retainedMovement = observedAggregate(LocalSupplierCashStorage.movementKey(value.target.attemptId))
                ?: error("Purchasing payout movement unavailable")
            LocalSupplierCashFacts.requireRetainedRow(producer, retainedMovement)
        }
        before = after; source = purchasingSource(afterWrite)
    }
    check(before == value && source == PurchasingSource.from(current.row) &&
        current.row.revision.value == value.applicationOperationIds.size.toLong() + 1L && value.applicationOperationIds.size <= 2 &&
        observedClaim(LocalSupplierCashStorage.sourceClaim(value.target.attemptId)) == value.target.sessionId)
    if (value.status != PurchasingSupplierAttemptStatus.CASH_PAID) check(row(LocalSupplierSettlementProfile.PAYMENT, id) == null &&
        observedAggregate(LocalSupplierCashStorage.movementKey(value.target.attemptId)) == null)
    return current
}

/** All rich paths use this complete catalogue-owned application lineage join. */
internal suspend fun supplierCashFact(view: CommandReadView, operationId: OperationId, phase: LocalSupplierCashPhase,
    reader: PurchasingReader = PurchasingReader(view)): LocalSupplierCashStoredFact? {
    val fact = LocalSupplierCashFacts.fact(view, operationId, phase) ?: return null
    return validateSupplierCashFact(fact, reader)
}
internal suspend fun supplierCashFact(command: RuntimeHistoricalCommand, operationId: OperationId, phase: LocalSupplierCashPhase,
    reader: PurchasingReader = PurchasingReader(command)): LocalSupplierCashStoredFact? {
    val fact = LocalSupplierCashFacts.fact(command, operationId, phase) ?: return null
    return validateSupplierCashFact(fact, reader)
}
private suspend fun validateSupplierCashFact(fact: LocalSupplierCashStoredFact, reader: PurchasingReader): LocalSupplierCashStoredFact {
    val phase = fact.value.phase
    requirePurchasingProducer(fact.slot.start)
    requirePurchasingProducer(fact.original)
    val attempt = reader.supplierAttempt(fact.value.target.settlementId) ?: error("Purchasing raw fact lost original attempt")
    val current = (attempt.value.body as PurchasingBody.SettlementAttempt).value
    check(current.target == fact.value.target && current.slotDigest == fact.value.slotDigest)
    fact.application.applicationOperationId?.let { operation ->
        val producer = reader.originalCommand(operation)
        val result = LocalSupplierCashCodec.result(producer.result) as LocalSupplierCashResult.Applied
        check(result == fact.application.result && result.target == fact.value.target && result.phase == phase &&
            operation in current.applicationOperationIds)
    }
    if (fact.value.target.invoiceId != null) reader.invoice(checkNotNull(fact.value.target.invoiceId))
        ?: error("Purchasing supplier fact lost invoice")
    else if (current.status == PurchasingSupplierAttemptStatus.CASH_PAID) reader.advance(current.target.settlementId)
        ?: error("Purchasing supplier fact lost advance")
    return fact
}

/** Exact literal record builder for the actual current command OR core-native history cut. */
internal class SupplierSettlementWrites private constructor(
    private val scope: BranchScope, private val operationId: OperationId, private val actorId: DomainId,
    private val command: CommandKey, private val digest: PayloadDigest, private val at: UtcInstant, private val policy: Revision,
) {
    constructor(context: CommandContext, at: UtcInstant, authorization: AuthorizedAction) : this(context.scope, context.operationId,
        context.originalActorId, context.key, context.semanticDigest, at, authorization.policyRevision)
    constructor(original: RuntimeHistoricalCommand) : this(original.scope, original.operationId, original.actorId,
        original.command, original.semanticDigest, original.recordedAt, original.policyRevision)
    fun change(kind: String, id: DomainId, before: PurchasingRow?, body: PurchasingBody): AggregateChange =
        AggregateChange(purchasingKey(kind, id), before?.row?.revision, LocalPurchasingProfile.schema(kind),
            purchasingEncode(PurchasingRecord(scope, kind, id, (before?.row?.revision ?: Revision(0)).next(),
                PurchasingProducer(operationId, actorId, command.name, digest.value), at, policy, body), PurchasingRecord.serializer()))
}

internal fun supplierInvoiceIndex(cut: PurchasingInvoiceCut, eventId: DomainId, allocationId: DomainId? = null,
    settlementId: DomainId? = null): PurchasingBody.InvoiceSettlements {
    val before = cut.settlements.index?.value?.body as? PurchasingBody.InvoiceSettlements
    return PurchasingBody.InvoiceSettlements(cut.invoice.value.id, before?.allocationIds.orEmpty() + listOfNotNull(allocationId),
        before?.settlementIds.orEmpty() + listOfNotNull(settlementId), eventId)
}

/**
 * Exact genuinely consumed supplier lineage footprint, shared by current and native
 * historical observations. Nothing is recorded for absent settlement rows, so legacy
 * CREDIT at a zero/absent cut retains its original footprint and canonical effects.
 * These are exact keys, NOT an unrelated whole-kind CASH_CUSTODY barrier.
 */
internal fun PurchasingReader.observeSupplierSettlementRow(row: PurchasingRow) {
    (row.value.body as? PurchasingBody.SettlementAttempt)?.value?.let { consumeSupplierAttempt(it) }
    val ids: List<DomainId> = when (val body = row.value.body) {
        is PurchasingBody.SettlementAttempt -> with(body.value.target) {
            listOf(supplierId, settlementId, attemptId) + listOfNotNull(invoiceId, allocationId)
        }
        is PurchasingBody.SupplierPayment -> with(body.value) {
            listOf(id, supplierId, cashMovementId) + listOfNotNull(target.invoiceId, target.allocationId)
        }
        is PurchasingBody.SupplierAdvance -> with(body.value) { listOf(id, supplierId, paymentId) }
        is PurchasingBody.SettlementAllocation -> with(body.value) { listOf(id, invoiceId, supplierId, paymentId) + listOfNotNull(advanceId) }
        is PurchasingBody.AdvanceAllocations -> listOf(body.advanceId) + body.allocationIds
        is PurchasingBody.InvoiceSettlements -> listOf(body.invoiceId, body.lastEventId) + body.allocationIds + body.settlementIds
        is PurchasingBody.SettlementEvent -> listOf(body.value.id, body.value.basisBefore.supplierSource.id) + listOfNotNull(body.value.invoiceId)
        else -> return
    }
    consumeSupplierResources(ids.map(::supplierSettlementResource).toSet() +
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, row.value.producer.actorId))
    val sessionId = when (val body = row.value.body) {
        is PurchasingBody.SettlementAttempt -> body.value.target.sessionId
        is PurchasingBody.SupplierPayment -> body.value.target.sessionId
        else -> null
    }
    sessionId?.let { consumeSupplierResources(setOf(RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, it))) }
}

internal fun PurchasingReader.observeSupplierCashStart(slot: LocalSupplierCashSlot) {
    consumeSupplierResources(slot.domain.financialResources.toSet() + LocalSupplierCashJournal.authorityResources(slot.requirement()))
}

internal fun PurchasingReader.observeSupplierCashSources(sources: List<PurchasingSource>) {
    consumeSupplierResources(sources.map { source -> RecoveryResourceKey(when (source.kind) {
        CashNonSaleStoreProfile.SESSION_KIND, LocalSalesStoreProfile.DRAWER_KIND, LocalSalesStoreProfile.CUSTODIAN_KIND -> RecoveryResourceKind.CASH_CUSTODY
        LocalSalesStoreProfile.WORK_KIND -> RecoveryResourceKind.ORDER_CUSTODY
        else -> error("Purchasing supplier consumed cash source unavailable")
    }, source.id) }.toSet())
}

/** Reserve the full possible native original/UNKNOWN/fixed-resolution suffix, not just index slots. */
internal fun PurchasingReader.requireSupplierHydrationCapacity(newTarget: LocalSupplierCashTarget? = null,
    applying: PurchasingSupplierAttempt? = null, action: LocalSupplierCashAction? = null) {
    check((applying == null) == (action == null) && (newTarget == null || applying == null))
    if (newTarget == null && supplierRecoveryResources.isEmpty()) return // Genuine old zero/absent CREDIT/match cut.
    val remaining = supplierAttempts.map { before ->
        if (applying != null && before.target.settlementId == applying.target.settlementId) before.copy(status = when (checkNotNull(action)) {
            LocalSupplierCashAction.UNKNOWN -> PurchasingSupplierAttemptStatus.OUTCOME_UNKNOWN
            LocalSupplierCashAction.CASH_PAID -> PurchasingSupplierAttemptStatus.CASH_PAID
            LocalSupplierCashAction.NOT_PAID -> PurchasingSupplierAttemptStatus.NOT_PAID
        }) else before
    }
    if (applying != null) check(supplierAttempts.singleOrNull { it.target == applying.target } == applying)
    var futureProducers = 1 // This command's postimage producer, in addition to already observed originals.
    var unresolved = 0
    for (attempt in remaining) when (attempt.status) {
        PurchasingSupplierAttemptStatus.AWAITING_ATTESTATION -> {
            unresolved++
            futureProducers += 2 // Original UNKNOWN Apply, then known fixed-resolution Apply.
            if (!hasOriginal(attempt.target.recordingOperationId)) futureProducers++
            if (!hasOriginal(attempt.target.resolutionOperationId)) futureProducers++
        }
        PurchasingSupplierAttemptStatus.OUTCOME_UNKNOWN -> {
            unresolved++
            futureProducers++ // Known fixed-resolution Apply.
            if (!hasOriginal(attempt.target.resolutionOperationId)) futureProducers++
        }
        PurchasingSupplierAttemptStatus.CASH_PAID, PurchasingSupplierAttemptStatus.NOT_PAID -> Unit
    }
    if (newTarget != null) { check(supplierAttempts.none { it.target.settlementId == newTarget.settlementId }); unresolved++; futureProducers += 4 }
    // Eight postimage/prepare rows plus at most payment + advance + advance-index per unresolved payout.
    // Direct invoice payouts require only payment + allocation. This also covers supplier-only advances.
    requireSupplierHydrationHeadroom(futureProducers, 8 + unresolved * 3)
}

/**
 * Shared PO match lineage can grow while a different matched invoice has native cash
 * reserved. Read its actual settlement cut before a new financial mutation can consume
 * the common reader headroom; zero/absent settlement keys add no recovery resources.
 */
internal suspend fun PurchasingReader.observeSupplierOrderDependencies(orderId: DomainId) {
    observeSupplierEconomicDependencies(orderId)
}

/** Also closes consumers of every other advance read through the mutated invoice, not only this selector. */
internal suspend fun PurchasingReader.observeSupplierAdvanceDependencies(advance: PurchasingAdvanceCut) {
    consumeSupplierAdvance(advance)
    observeSupplierEconomicDependencies(null)
}

/**
 * Exact reverse-consumer closure of genuinely read advance memberships and shared PO
 * match indexes. An invoice index mutation is visible to every member of a common
 * source advance, including invoices on other POs. Those native attempts must retain
 * headroom too. Cycles are visited once; the unchanged reader budgets bound the walk.
 * No unbounded global scan, synthetic source, or provenance/read shortcut is used.
 */
private suspend fun PurchasingReader.observeSupplierEconomicDependencies(seedOrder: DomainId?) {
    val orders = linkedSetOf<DomainId>().also { seedOrder?.let(it::add) }
    val visitedOrders = mutableSetOf<DomainId>()
    val visitedAdvances = mutableSetOf<DomainId>()
    val visitedInvoices = mutableSetOf<DomainId>()
    suspend fun observeInvoice(invoiceId: DomainId) {
        if (!visitedInvoices.add(invoiceId)) return
        val cut = invoice(invoiceId) ?: error("Purchasing shared supplier invoice unavailable")
        orders += cut.order.row.key.id
    }
    while (true) {
        val pendingOrder = orders.firstOrNull { it !in visitedOrders }
        if (pendingOrder != null) {
            visitedOrders += pendingOrder
            val index = orderMatches(pendingOrder).value.body as PurchasingBody.OrderMatches
            for (invoiceId in matches(index.matchIds).map { it.request.invoiceId }.distinct())
                if (row(LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS, invoiceId) != null) observeInvoice(invoiceId)
            continue
        }
        val pendingAdvance = supplierAdvances.firstOrNull { it.advance.row.key.id !in visitedAdvances } ?: break
        visitedAdvances += pendingAdvance.advance.row.key.id
        for (invoiceId in pendingAdvance.allocations.map {
            (it.value.body as PurchasingBody.SettlementAllocation).value.invoiceId
        }.distinct()) observeInvoice(invoiceId)
    }
}
