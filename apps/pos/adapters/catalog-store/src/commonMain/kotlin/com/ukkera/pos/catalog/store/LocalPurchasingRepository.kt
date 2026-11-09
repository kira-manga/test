package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

/** Only purchasing-owned hydration budgets throw this; never a storage/provenance classifier. */
internal class PurchasingHydrationCapacityUnavailable : RuntimeException()

/** Only actual current loans and core-created native historical cuts implement this port.
 * It has no session, authorization, query pagination, writer, proposal or handler capability. */
private sealed interface PurchasingReadObservation {
    val scope: BranchScope
    val documents: PurchasingDocumentReader
    suspend fun aggregate(key: AggregateKey): StoredAggregate?
    suspend fun claim(key: ClaimKey): DomainId?
    suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence?
}

private class CurrentPurchasingObservation(private val view: CommandReadView) : PurchasingReadObservation {
    override val scope: BranchScope get() = view.scope
    override val documents: PurchasingDocumentReader = CurrentPurchasingDocumentReader(view)
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = view.aggregate(key)
    override suspend fun claim(key: ClaimKey): DomainId? = view.claim(key)
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = view.retainedCommand(operationId)
}

private class HistoricalPurchasingObservation(private val command: RuntimeHistoricalCommand) : PurchasingReadObservation {
    override val scope: BranchScope get() = command.scope
    override val documents: PurchasingDocumentReader = HistoricalPurchasingDocumentReader(command)
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = command.aggregateSource(key)?.row
    override suspend fun claim(key: ClaimKey): DomainId? = command.claim(key)
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = when (val original = command.earlierOriginal(operationId)) {
        is RetainedOriginalV2.Legacy -> original.evidence
        is RetainedOriginalV2.Streamed -> error("Unsupported purchasing original")
        null -> null
    }
}

/** One actual observation source; existing complete hydration/account checks and budgets
 * are shared with native history, never by constructing a historical CommandReadView. */
internal class PurchasingReader private constructor(private val source: PurchasingReadObservation) {
    constructor(view: CommandReadView) : this(CurrentPurchasingObservation(view))
    constructor(command: RuntimeHistoricalCommand) : this(HistoricalPurchasingObservation(command))
    internal val scope: BranchScope get() = source.scope
    internal val documents: PurchasingDocumentReader get() = source.documents
    private val rows = mutableMapOf<AggregateKey, PurchasingRow?>()
    private val capturePublications = mutableMapOf<DomainId, StoredAggregate>()
    private val producers = mutableMapOf<OperationId, RetainedCommandEvidence>()
    private var loadedBytes = 0L
    private val consumedSupplierResources = mutableSetOf<RecoveryResourceKey>()
    private val consumedSupplierAttempts = mutableMapOf<DomainId, PurchasingSupplierAttempt>()
    private val consumedSupplierAdvances = mutableMapOf<DomainId, PurchasingAdvanceCut>()
    internal val supplierAdvances: List<PurchasingAdvanceCut> get() = consumedSupplierAdvances.values.toList()
    internal fun consumeSupplierAdvance(advance: PurchasingAdvanceCut) { consumedSupplierAdvances[advance.advance.row.key.id] = advance }
    internal val supplierAttempts: List<PurchasingSupplierAttempt> get() = consumedSupplierAttempts.values.toList()
    internal fun consumeSupplierAttempt(attempt: PurchasingSupplierAttempt) { consumedSupplierAttempts[attempt.target.settlementId] = attempt }
    internal fun hasOriginal(operationId: OperationId): Boolean = operationId in producers
    internal val supplierRecoveryResources: Set<RecoveryResourceKey> get() = consumedSupplierResources.toSet()
    internal fun consumeSupplierResources(resources: Set<RecoveryResourceKey>) { consumedSupplierResources += resources }

    /**
     * Keep the original 512-row / 256-producer / 16-MiB profile. Reserve the unchanged
     * maximum retained producer envelope and row bytes before publishing any new cut.
     * The conservative reservation can refuse BEFORE native payout, never after cash
     * has already left solely because an allowed UNKNOWN/resolution suffix was omitted.
     */
    internal fun requireSupplierHydrationHeadroom(additionalProducers: Int, additionalRows: Int) {
        check(additionalProducers >= 0 && additionalRows >= 0)
        val bytes = additionalProducers.toLong() * (1_048_576L + 32_768L) + additionalRows.toLong() * 65_536L
        purchaseRequire(producers.size + additionalProducers <= 256 && rows.size + capturePublications.size + additionalRows <= 512 &&
            bytes <= 16_777_216L - loadedBytes, "purchasing.capacity_required")
    }

    suspend fun row(kind: String, id: DomainId): PurchasingRow? {
        check(kind in purchasingKinds)
        val key = purchasingKey(kind, id)
        if (key in rows) return rows[key]
        if (rows.size + capturePublications.size >= 512) throw PurchasingHydrationCapacityUnavailable()
        val row = source.aggregate(key)
        val claim = source.claim(purchasingClaim(kind, id))
        check(if (row == null) claim == null else claim == id) { "Purchasing row/claim mismatch" }
        if (row == null) { rows[key] = null; return null }
        // A single oversized stored row is invalid storage, not a page-budget condition.
        check(row.payload.size <= 65_536 && row.digest == sha256(row.payload.copyBytes()))
        accountBytes(row.payload.size.toLong())
        val record = decodePurchasingRecord(row.payload)
        check(row.schema == LocalPurchasingProfile.schema(kind) && record.scope == source.scope && record.kind == kind &&
            record.id == id && record.revision == row.revision && row.revision.value >= 1)
        val evidence = producers[record.producer.operationId] ?: source.retainedCommand(record.producer.operationId) ?: error("Purchasing producer unavailable")
        check(evidence.scope == source.scope && evidence.operationId == record.producer.operationId)
        val original = evidence.aggregateWrites.single { it.key == key }
        check(original.storageGeneration == row.revision && original.schema == row.schema && original.payload == row.payload && original.payloadDigest == row.digest)
        if (evidence.operationId !in producers) {
            val bytes = evidence.normalized.size.toLong() + evidence.aggregateWrites.sumOf { it.payload.size.toLong() }
            // Existing proposal envelope plus this profile's input bound, not a new business cap.
            check(bytes <= 1_048_576L + 32_768L)
            requirePurchasingProducer(evidence)
            if (producers.size >= 256) throw PurchasingHydrationCapacityUnavailable()
            accountBytes(bytes)
            producers[evidence.operationId] = evidence
        }
        val result = PurchasingRow(row, record)
        rows[key] = result
        observeSupplierSettlementRow(result)
        when (val body = record.body) {
            is PurchasingBody.Order -> check(source.claim(purchasingReferenceClaim(source.scope, "order", body.value.supplierId, body.value.reference)) == id)
            is PurchasingBody.Invoice -> check(source.claim(purchasingReferenceClaim(source.scope, "invoice", body.value.supplierId, body.value.reference)) == id)
            is PurchasingBody.InvoiceCapture -> check(source.claim(purchasingReferenceClaim(source.scope, "invoice", body.value.supplierId, body.value.reference)) == id)
            is PurchasingBody.Credit -> {
                check(source.claim(purchasingReferenceClaim(source.scope, "credit", body.supplierId, body.request.reference)) == id)
                check(source.claim(purchasingCreditSourceClaim(body.request.sourceEvidenceId)) == id)
            }
            else -> Unit
        }
        return result
    }

    /** Real retained originals, bounded and fully shape-checked before any source image is used. */
    internal suspend fun originalCommand(operationId: OperationId): RetainedCommandEvidence {
        producers[operationId]?.let { return it }
        val original = source.retainedCommand(operationId) ?: error("Purchasing original unavailable")
        check(original.scope == source.scope && original.operationId == operationId)
        val bytes = original.normalized.size.toLong() + original.aggregateWrites.sumOf { it.payload.size.toLong() }
        check(bytes <= 1_048_576L + 32_768L)
        requirePurchasingProducer(original)
        if (producers.size >= 256) throw PurchasingHydrationCapacityUnavailable()
        accountBytes(bytes)
        producers[operationId] = original
        return original
    }

    internal suspend fun originalImage(source: PurchasingSettlementIndexSource): PurchasingSettlementIndexImage {
        val original = originalCommand(source.operationId)
        val write = original.aggregateWrites.single { it.key == purchasingKey(source.source.kind, source.source.id) }
        check(purchasingSource(write) == source.source)
        val record = decodePurchasingRecord(write.payload)
        check(record.kind == LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS && record.id == source.source.id &&
            record.revision == source.source.revision && record.producer.operationId == source.operationId)
        return PurchasingSettlementIndexImage(source.source, record, record.body as PurchasingBody.InvoiceSettlements)
    }

    private fun accountBytes(bytes: Long) {
        check(bytes >= 0 && loadedBytes in 0L..16_777_216L)
        if (bytes > 16_777_216L - loadedBytes) throw PurchasingHydrationCapacityUnavailable()
        loadedBytes += bytes
    }

    internal suspend fun observedAggregate(key: AggregateKey): StoredAggregate? = source.aggregate(key)
    internal suspend fun observedClaim(key: ClaimKey): DomainId? = source.claim(key)

    /** Bounded immutable configuration hydration belongs to the same purchasing budget. */
    internal suspend fun capturePublication(id: DomainId): StoredAggregate {
        capturePublications[id]?.let { return it }
        if (rows.size + capturePublications.size >= 512) throw PurchasingHydrationCapacityUnavailable()
        val row = source.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, id))
            ?: error("Capture original publication unavailable")
        check(row.payload.size <= 65_536 && row.digest == sha256(row.payload.copyBytes()))
        accountBytes(row.payload.size.toLong())
        decodeRecord(scope, row)
        capturePublications[id] = row
        return row
    }

    /** Exact earlier source write from an already hydrated, fully checked producer. */
    internal fun originalSource(producer: PurchasingRow, kind: String, id: DomainId): PurchasingSource {
        val write = producers.getValue(producer.value.producer.operationId).aggregateWrites.single { it.key == purchasingKey(kind, id) }
        return PurchasingSource(kind, id, write.storageGeneration, write.payloadDigest.value)
    }

    internal fun originalCreditIndex(producer: PurchasingRow, invoiceId: DomainId): PurchasingBody.InvoiceCredits {
        val write = producers.getValue(producer.value.producer.operationId).aggregateWrites.single {
            it.key == purchasingKey(LocalPurchasingProfile.INVOICE_CREDITS, invoiceId)
        }
        return decodePurchasingRecord(write.payload).body as PurchasingBody.InvoiceCredits
    }

    suspend fun order(id: DomainId): PurchasingRow? {
        val order = row(LocalPurchasingProfile.ORDER, id) ?: return null
        val value = (order.value.body as PurchasingBody.Order).value
        val basis = source.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.BASIS, id)) ?: error("Purchasing basis unavailable")
        val actual = decodeRecord(source.scope, basis)
        check(actual == ConfigurationRecord(source.scope, LocalConfigurationProfile.BASIS, id, Revision(0),
            ConfigurationBody.Basis(purchasingBasis(value)), order.value.producer.actorId, order.value.policyRevision, order.value.recordedAt))
        val original = producers.getValue(order.value.producer.operationId).aggregateWrites.single { it.key == basis.key }
        check(original.storageGeneration == basis.revision && original.schema == basis.schema &&
            original.payload == basis.payload && original.payloadDigest == basis.digest)
        for (line in value.lines) check(source.claim(ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, "allocation/${line.allocationId.value}")) == id)
        return order
    }

    suspend fun matches(ids: List<DomainId>): List<PurchasingMatchFact> {
        check(ids.size <= LocalPurchasingProfile.MAX_MATCHES && ids.distinct().size == ids.size)
        return ids.map { id -> PurchasingMatchFact(row(LocalPurchasingProfile.MATCH, id)?.value?.body
            ?: error("Purchasing match unavailable")) }
    }

    suspend fun orderMatches(orderId: DomainId): PurchasingRow {
        val row = row(LocalPurchasingProfile.ORDER_MATCHES, orderId) ?: error("Purchasing order match index unavailable")
        val state = row.value.body as PurchasingBody.OrderMatches
        val events = matches(state.matchIds)
        check(state.orderId == orderId && row.row.revision.value == events.size.toLong() + 1)
        for ((index, event) in events.withIndex()) check(event.orderId == orderId && event.request.expectedOrderMatchRevision.value == index.toLong() + 1)
        return row
    }

    /** Both supported invoice formats occupy the same permanent identity/reference domain. */
    internal suspend fun invoiceDocument(id: DomainId): PurchasingRow? = when (row(LocalPurchasingProfile.INVOICE, id)?.value?.body) {
        is PurchasingBody.Invoice -> invoice(id)?.invoice
        is PurchasingBody.InvoiceCapture -> captureInvoice(id)?.invoice
        null -> { requireInvoiceFinancialAbsence(id); null }
        else -> error("Unsupported purchasing invoice body")
    }

    internal suspend fun requireInvoiceReferenceOwner(id: DomainId, reference: ClaimKey) {
        val owner = invoiceDocument(id) ?: error("Purchasing invoice reference lost its owner")
        val actual = when (val body = owner.value.body) {
            is PurchasingBody.Invoice -> purchasingReferenceClaim(scope, "invoice", body.value.supplierId, body.value.reference)
            is PurchasingBody.InvoiceCapture -> purchasingReferenceClaim(scope, "invoice", body.value.supplierId, body.value.reference)
            else -> error("Unsupported purchasing invoice reference owner")
        }
        check(actual == reference)
    }

    private suspend fun requireInvoiceFinancialAbsence(id: DomainId) {
        check(row(LocalPurchasingProfile.PROGRESS, id) == null && row(LocalPurchasingProfile.PAYABLE, id) == null &&
            row(LocalPurchasingProfile.INVOICE_CREDITS, id) == null && row(LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS, id) == null)
    }

    /** Validate original documentary facts and any separately committed recognition history. */
    internal suspend fun captureInvoice(id: DomainId): PurchasingInvoiceCaptureCut? {
        val cut = capturedInvoiceOriginal(id) ?: return null
        if (row(LocalPurchasingProfile.PROGRESS, id) == null) requireInvoiceFinancialAbsence(id)
        else checkNotNull(invoice(id))
        return cut
    }

    /** The saved claim and its original context never change when a later explicit match commits. */
    internal suspend fun capturedInvoiceOriginal(id: DomainId): PurchasingInvoiceCaptureCut? {
        val invoice = row(LocalPurchasingProfile.INVOICE, id) ?: run {
            requireInvoiceFinancialAbsence(id)
            return null
        }
        val body = invoice.value.body as? PurchasingBody.InvoiceCapture ?: error("Purchasing capture format required")
        val value = body.value
        val order = value.internalOrderLink?.let { link ->
            val stamp = checkNotNull(body.orderSource)
            val original = originalCommand(checkNotNull(body.orderOperationId))
            check(original.command == LocalPurchasingProfile.APPROVE_ORDER)
            val write = original.aggregateWrites.single { it.key == purchasingKey(LocalPurchasingProfile.ORDER, link.orderId) }
            check(PurchasingSource(write.key.kind, write.key.id, write.storageGeneration, write.payloadDigest.value) == stamp &&
                stamp.revision == link.revision)
            val record = decodePurchasingRecord(write.payload)
            check(record.producer.operationId == original.operationId && record.scope == scope)
            (record.body as? PurchasingBody.Order)?.value ?: error("Capture original order unavailable")
        }
        check((order == null) == (body.orderSource == null) && (order == null) == (body.orderOperationId == null))
        if (order != null) check(order.id == value.internalOrderLink?.orderId && order.supplierId == value.supplierId &&
            value.lines.all { line -> line.internalAllocationId?.let { allocation -> order.lines.any { it.allocationId == allocation } } != false })
        val catalog = body.catalogSources.associate { it.skuSource.id to captureCatalogSnapshot(it) }
        check(catalog.size == body.catalogSources.size && catalog.keys == value.lines.map { it.goods.skuId }.toSet())
        for ((skuId, snapshot) in catalog) check(snapshot.units.map { it.id }.toSet() == value.lines.filter { it.goods.skuId == skuId }
            .flatMap { listOf(it.goods.units.purchaseUnitId, it.goods.units.baseUnitId) }.toSet())
        for (line in value.lines) {
            val snapshot = catalog.getValue(line.goods.skuId)
            check(purchasingCaptureGoodsRejection(line.goods, snapshot.definition, snapshot.units) == null)
            if (line.provenance == PurchaseInvoiceEntryProvenanceV2.SELECTED_ORDER_LINE) {
                val selected = order?.lines?.singleOrNull { it.allocationId == line.internalAllocationId }
                    ?: error("Capture selected original order line unavailable")
                check(selected.goods.skuId == line.goods.skuId && selected.goods.units == line.goods.units)
            }
        }
        check(body.discrepancies == purchaseInvoiceCaptureDiscrepanciesV2(order, value).sortedBy { it.name })
        return PurchasingInvoiceCaptureCut(invoice, order)
    }

    suspend fun invoice(id: DomainId): PurchasingInvoiceCut? {
        val invoice = row(LocalPurchasingProfile.INVOICE, id) ?: run {
            requireInvoiceFinancialAbsence(id)
            return null
        }
        val value = purchasingInvoiceClaim(invoice)
        val captured = invoice.value.body as? PurchasingBody.InvoiceCapture
        val capture = captured?.let { checkNotNull(capturedInvoiceOriginal(id)) }
        val progress = row(LocalPurchasingProfile.PROGRESS, id) ?: run {
            if (capture == null) error("Purchasing progress unavailable")
            requireInvoiceFinancialAbsence(id)
            throw PurchasingInvoiceCaptureUnqualified(capture)
        }
        val state = progress.value.body as PurchasingBody.Progress
        check(state.invoiceId == id && state.lines.map { it.lineId } == value.lines.map { it.lineId })
        val events = matches(state.matchIds)
        val orderId = if (captured == null) {
            check(events.all { it.legacy != null })
            (invoice.value.body as PurchasingBody.Invoice).value.orderId
        } else {
            // A capture has no empty financial progress. Its first actual event selects
            // one real PO, without changing or manufacturing an original association.
            check(events.isNotEmpty() && events.all { it.captured != null })
            check(events.map { it.orderId }.distinct().size == 1)
            events.first().orderId.also { selected ->
                check(captured.value.internalOrderLink?.orderId?.let { it == selected } != false)
            }
        }
        val order = order(orderId) ?: error("Purchasing order unavailable")
        val original = (order.value.body as PurchasingBody.Order).value
        check(value.supplierId == original.supplierId)
        if (captured == null) {
            val body = invoice.value.body as PurchasingBody.Invoice
            check(body.orderSource == PurchasingSource.from(order.row) &&
                body.discrepancies == purchaseDiscrepancies(original, body.value).sortedBy { it.name })
        } else {
            captured.value.internalOrderLink?.let { link ->
                check(link.revision == order.row.revision && captured.orderSource == PurchasingSource.from(order.row) &&
                    captured.orderOperationId == order.value.producer.operationId && capture?.originalOrder == original)
            }
        }
        val allMatches = (orderMatches(orderId).value.body as PurchasingBody.OrderMatches).matchIds
        check(matches(allMatches).filter { it.request.invoiceId == id }.map { it.request.matchId } == state.matchIds)
        val offset = purchasingProgressOffset(invoice)
        check(progress.row.revision.value == events.size.toLong() + offset)
        val accumulated = purchasingInitialProgress(value).lines.associateBy { it.lineId }.toMutableMap()
        val selections = mutableMapOf<DomainId, DomainId>()
        for ((index, event) in events.withIndex()) {
            requirePurchasingMatch(original, value, event)
            check(purchasingReceiptOrderSourceAgrees(order, event.receipt.poValuation))
            if (captured != null) {
                val fact = checkNotNull(event.captured)
                check(fact.invoiceSource == PurchasingSource.from(invoice.row) && fact.invoiceOperationId == invoice.value.producer.operationId &&
                    fact.orderSource == PurchasingSource.from(order.row) && fact.orderOperationId == order.value.producer.operationId &&
                    fact.request.expectedOrderRevision == order.row.revision && fact.request.originalDocumentId == value.sourceEvidenceId)
                val document = purchasingCapturedOriginal(documents, captured.value, fact.request.expectedOriginalRevision)
                check(document.refusalCode == null && document.source == fact.originalSource)
                consumeSupplierResources(document.resources)
                val matching = row(LocalPurchasingProfile.MATCH, fact.request.matchId) ?: error("Captured match original unavailable")
                val result = LocalPurchasingCodec.capturedMatchResult(originalCommand(matching.value.producer.operationId).result)
                check(result.invoiceId == id && result.invoiceRevision == invoice.row.revision && result.originalInvoiceClaim == value.total())
                val selected = selections.put(fact.request.invoiceLineId, fact.request.allocationId)
                check(selected == null || selected == fact.request.allocationId)
            }
            val before = accumulated[event.request.invoiceLineId] ?: error("Purchasing line unavailable")
            val expectedProgress = if (captured != null && index == 0) null else Revision(index.toLong() + offset)
            check(event.request.invoiceId == id && event.request.expectedInvoiceRevision == invoice.row.revision &&
                event.request.expectedProgressRevision == expectedProgress && event.invoiceBefore == before.quantity &&
                event.request.expectedPayableRevision == if (index == 0) null else Revision(index.toLong()))
            accumulated[before.lineId] = before.copy(quantity = before.quantity + event.request.quantity,
                net = before.net + event.net, tax = before.tax + event.tax)
        }
        check(state.lines == value.lines.map { accumulated.getValue(it.lineId) })
        val payable = row(LocalPurchasingProfile.PAYABLE, id)
        check((payable == null) == events.isEmpty())
        if (payable != null) {
            val amount = state.lines.fold(purchaseZero()) { total, line -> total + line.net }
            val tax = state.lines.fold(purchaseZero()) { total, line -> total + line.tax }
            check(payable.row.revision.value == events.size.toLong() && payable.value.body == PurchasingBody.Payable(id, original.id,
                original.supplierId, value.dueDate, PurchasingPayableStatus.UNSETTLED, amount, tax, state.matchIds))
            check((amount + tax).minor <= value.total().minor)
        }
        val settlements = invoiceSettlements(invoice, payable, events)
        val credits = invoiceCredits(invoice, payable, events, settlements)
        val cut = PurchasingInvoiceCut(invoice, order, progress, payable, events, credits, settlements)
        purchasingDue(cut)?.let { check(settlements.reservedTotal.minor <= it.remainingDue.minor) }
        return cut
    }

    suspend fun orderBilling(order: PurchaseOrderV1, allocationId: DomainId): PurchasingRow {
        val record = row(LocalPurchasingProfile.ORDER_BILLED, allocationId) ?: error("Purchasing allocation billing unavailable")
        val state = record.value.body as PurchasingBody.OrderBilled
        val line = order.lines.single { it.allocationId == allocationId }
        check(state.orderId == order.id && state.allocationId == allocationId)
        val events = matches(state.matchIds)
        val allMatches = (orderMatches(order.id).value.body as PurchasingBody.OrderMatches).matchIds
        check(matches(allMatches).filter { it.allocationId == allocationId }.map { it.request.matchId } == state.matchIds)
        var quantity = line.goods.baseQuantity().copy(scaled = 0)
        var net = purchaseZero(); var tax = purchaseZero()
        for ((index, event) in events.withIndex()) {
            val origin = invoice(event.request.invoiceId) ?: error("Purchasing matched invoice unavailable")
            check(event.request.matchId in (origin.progress.value.body as PurchasingBody.Progress).matchIds)
            check(event.orderId == order.id && event.allocationId == allocationId && event.orderBefore == quantity &&
                event.request.expectedOrderBillingRevision.value == index.toLong() + 1)
            quantity += event.request.quantity; net += event.net; tax += event.tax
            check(quantity <= line.goods.baseQuantity())
            check(net == purchaseCumulativeAmount(line.goods.net, quantity, line.goods.baseQuantity()) &&
                tax == purchaseCumulativeAmount(line.goods.taxAmount, quantity, line.goods.baseQuantity()))
        }
        check(state.quantity == quantity && state.net == net && state.tax == tax && record.row.revision.value == events.size.toLong() + 1)
        return record
    }

    suspend fun receiptBilling(sourceId: DomainId): PurchasingRow? {
        val record = row(LocalPurchasingProfile.RECEIPT_BILLED, sourceId) ?: return null
        val state = record.value.body as PurchasingBody.ReceiptBilled
        val events = matches(state.matchIds)
        val allMatches = (orderMatches(state.orderId).value.body as PurchasingBody.OrderMatches).matchIds
        check(matches(allMatches).filter { it.receipt.sourceId == sourceId }.map { it.request.matchId } == state.matchIds)
        check(events.isNotEmpty() && state.sourceId == sourceId)
        var quantity = state.quantity.copy(scaled = 0)
        var net = purchaseZero(); var tax = purchaseZero()
        for ((index, event) in events.withIndex()) {
            val origin = invoice(event.request.invoiceId) ?: error("Purchasing matched invoice unavailable")
            check(event.request.matchId in (origin.progress.value.body as PurchasingBody.Progress).matchIds)
            check(event.receipt.sourceId == sourceId && event.receipt.receiptId == state.receiptId && event.receipt.lineId == state.lineId &&
                event.orderId == state.orderId && event.allocationId == state.allocationId && event.receiptBefore == quantity &&
                event.request.expectedReceiptBillingRevision == if (index == 0) null else Revision(index.toLong()))
            quantity += event.request.quantity; net += event.net; tax += event.tax
            check(quantity <= event.receipt.acceptedQuantity)
        }
        check(state.quantity == quantity && state.net == net && state.tax == tax && record.row.revision.value == events.size.toLong())
        return record
    }
}

internal fun requirePurchasingMatch(order: PurchaseOrderV1, invoice: PurchaseInvoiceV1, event: PurchasingBody.Match) {
    check(invoice.orderId == order.id)
    requirePurchasingMatch(order, purchasingInvoiceClaim(invoice), PurchasingMatchFact(event))
}

/** Literal format-aware claim and event semantics. In particular a captured match is
 * never allowed to enter the legacy uncontested-price path. */
internal fun requirePurchasingMatch(order: PurchaseOrderV1, invoice: PurchasingInvoiceClaim, event: PurchasingMatchFact) {
    check(invoice.captured == (event.captured != null))
    val request = event.request
    val line = invoice.lines.single { it.lineId == request.invoiceLineId }
    val ordered = order.lines.single { it.allocationId == event.allocationId }
    val receipt = event.receipt
    check((if (invoice.captured) purchasingCapturedLineTermsAgree(ordered.goods, line.goods)
        else if (event.uncontested == null) purchaseLineDiscrepancies(ordered.goods, line.goods).isEmpty()
        else !invoice.captured && purchaseUncontestedPriceIncrease(ordered.goods, line.goods)) && event.orderId == order.id &&
        line.allocationId?.let { it == event.allocationId } != false && event.allocationId == ordered.allocationId &&
        invoice.supplierId == order.supplierId && receipt.supplierId == order.supplierId &&
        receipt.basisId == order.id && receipt.basisRevision == Revision(0) && receipt.allocationId == ordered.allocationId &&
        receipt.skuId == ordered.goods.skuId && receipt.warehouseId == ordered.warehouseId && request.receiptId == receipt.receiptId &&
        request.receiptLineId == receipt.lineId && request.expectedReceiptRevision == receipt.source.revision &&
        receipt.source.kind == com.ukkera.pos.inventory.store.LocalStockStoreProfile.RECEIPT_DOCUMENT && receipt.source.id == receipt.receiptId &&
        receipt.allowanceSource.kind == com.ukkera.pos.inventory.store.LocalStockStoreProfile.ALLOWANCE && receipt.allowanceSource.id == ordered.allocationId)
    val after = event.invoiceBefore + request.quantity
    check(after <= line.goods.baseQuantity() && event.orderBefore + request.quantity <= ordered.goods.baseQuantity() &&
        event.receiptBefore + request.quantity <= receipt.acceptedQuantity && receipt.acceptedQuantity <= receipt.originalQuantity)
    if (receipt.serialId != null) check(request.quantity == receipt.originalQuantity)
    val net = purchaseCumulativeAmount(line.goods.net, after, line.goods.baseQuantity()) -
        purchaseCumulativeAmount(line.goods.net, event.invoiceBefore, line.goods.baseQuantity())
    val tax = purchaseCumulativeAmount(line.goods.taxAmount, after, line.goods.baseQuantity()) -
        purchaseCumulativeAmount(line.goods.taxAmount, event.invoiceBefore, line.goods.baseQuantity())
    if (event.uncontested == null) check(event.net == net && event.tax == tax)
    else {
        val expected = purchaseUncontestedAmounts(ordered.goods, line.goods, event.orderBefore, event.invoiceBefore, request.quantity)
            ?: error("Purchasing uncontested slice requires rounding policy")
        check(event.uncontested == expected && expected.claimedNet == net && expected.claimedTax == tax &&
            event.net == expected.net && event.tax == expected.tax)
    }
    check(event.net == purchaseCumulativeAmount(ordered.goods.net, event.orderBefore + request.quantity,
        ordered.goods.baseQuantity()) - purchaseCumulativeAmount(ordered.goods.net, event.orderBefore, ordered.goods.baseQuantity()) &&
        event.tax == purchaseCumulativeAmount(ordered.goods.taxAmount, event.orderBefore + request.quantity, ordered.goods.baseQuantity()) -
        purchaseCumulativeAmount(ordered.goods.taxAmount, event.orderBefore, ordered.goods.baseQuantity()))
    check(purchasingReceiptValueAgrees(order, ordered, receipt))
}

internal fun decodePurchasingRecord(payload: StoredBytes): PurchasingRecord {
    val value = purchasingDecode(payload, PurchasingRecord.serializer())
    check(purchasingEncode(value, PurchasingRecord.serializer()) == payload)
    val identity = when (val body = value.body) {
        is PurchasingBody.Order -> LocalPurchasingProfile.ORDER to body.value.id
        is PurchasingBody.Invoice -> LocalPurchasingProfile.INVOICE to body.value.id
        is PurchasingBody.InvoiceCapture -> LocalPurchasingProfile.INVOICE to body.value.id
        is PurchasingBody.Progress -> LocalPurchasingProfile.PROGRESS to body.invoiceId
        is PurchasingBody.OrderBilled -> LocalPurchasingProfile.ORDER_BILLED to body.allocationId
        is PurchasingBody.OrderMatches -> LocalPurchasingProfile.ORDER_MATCHES to body.orderId
        is PurchasingBody.ReceiptBilled -> LocalPurchasingProfile.RECEIPT_BILLED to body.sourceId
        is PurchasingBody.Match -> LocalPurchasingProfile.MATCH to body.request.matchId
        is PurchasingBody.CapturedMatch -> LocalPurchasingProfile.MATCH to body.request.matchId
        is PurchasingBody.Payable -> LocalPurchasingProfile.PAYABLE to body.invoiceId
        is PurchasingBody.Credit -> LocalPurchasingProfile.CREDIT to body.request.creditId
        is PurchasingBody.InvoiceCredits -> LocalPurchasingProfile.INVOICE_CREDITS to body.invoiceId
        is PurchasingBody.SettlementAttempt -> LocalSupplierSettlementProfile.ATTEMPT to body.value.target.settlementId
        is PurchasingBody.SupplierPayment -> LocalSupplierSettlementProfile.PAYMENT to body.value.id
        is PurchasingBody.SupplierAdvance -> LocalSupplierSettlementProfile.ADVANCE to body.value.id
        is PurchasingBody.AdvanceAllocations -> LocalSupplierSettlementProfile.ADVANCE_ALLOCATIONS to body.advanceId
        is PurchasingBody.SettlementAllocation -> LocalSupplierSettlementProfile.ALLOCATION to body.value.id
        is PurchasingBody.InvoiceSettlements -> LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS to body.invoiceId
        is PurchasingBody.SettlementEvent -> LocalSupplierSettlementProfile.EVENT to body.value.id
    }
    check(value.kind == identity.first && value.id == identity.second)
    return value
}
