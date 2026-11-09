package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

internal class PurchasingReadHandler(private val stock: LocalStockRepository) : MasterReadHandler {
    override val key: ReadKey = LocalPurchasingProfile.READ
    override fun normalize(input: StoredBytes): StoredBytes = LocalPurchasingCodec.query(purchasingDecode(input, LocalPurchasingQueryV1.serializer()))
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        if (profile !in setOf(LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE, LocalMasterRpcWorkflowProfile.INVOICE_MATCHING)) return false
        val query = LocalPurchasingCodec.decodeQuery(normalized)
        return query.section == LocalPurchasingSectionV1.ORDER && !query.export && normalize(normalized) == normalized
    }

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        requirePurchasingReadContext(view, context, key, normalized)
        val query = purchasingDecode(normalized, LocalPurchasingQueryV1.serializer())
        val kind = when (query.section) {
            LocalPurchasingSectionV1.ORDER -> LocalPurchasingProfile.ORDER
            LocalPurchasingSectionV1.INVOICE -> LocalPurchasingProfile.INVOICE
            LocalPurchasingSectionV1.PAYABLE -> LocalPurchasingProfile.PAYABLE
        }
        val reader = PurchasingReader(view)
        val page = if (query.id == null) view.aggregateKeys(kind, query.after, query.limit) else null
        val keys = page?.keys ?: listOf(purchasingKey(kind, requireNotNull(query.id)))
        check(keys.size <= query.limit && keys.distinct().size == keys.size && keys.all { it.kind == kind })
        // At most five bounded root rows/producers fit before any recursive history hydration.
        // These exact selected bindings are required even when the later history exceeds capacity.
        val rows = keys.mapNotNull { key ->
            val row = reader.row(kind, key.id)
            check(row != null || page == null) { "Purchasing page lost a selected document" }
            row
        }
        val bindings = rows.map { it.row.key.id to it.row.revision }.ifEmpty { listOf((query.id ?: view.scope.branchId) to Revision(0)) }
        return boundedPurchasingRead(context, bindings, query.includeCosts, query.export, { purchasingUnavailablePage(query) },
            { purchasingUnavailablePage(query, LocalPurchasingReadAvailabilityV1.UNSUPPORTED_INVOICE_CAPTURE) }) {
            query.id?.let { id -> when (query.section) {
                LocalPurchasingSectionV1.ORDER -> { if (reader.order(id) == null) check(reader.row(LocalPurchasingProfile.ORDER_MATCHES, id) == null); Unit }
                LocalPurchasingSectionV1.INVOICE, LocalPurchasingSectionV1.PAYABLE -> { reader.invoice(id); Unit }
            } }
            val projected = rows.map { record -> when (query.section) {
                LocalPurchasingSectionV1.ORDER -> orderView(reader, view, record.row.key.id, query)
                LocalPurchasingSectionV1.INVOICE, LocalPurchasingSectionV1.PAYABLE -> invoiceView(reader, view, record.row.key.id, query)
            } }
            purchasingEncode(LocalPurchasingPageV1(query.section, projected, page?.nextAfter, query.includeCosts), LocalPurchasingPageV1.serializer())
        }
    }

    private suspend fun orderView(reader: PurchasingReader, view: CommandReadView, id: DomainId, query: LocalPurchasingQueryV1): LocalPurchasingDocumentViewV1 {
        val record = reader.order(id) ?: error("Purchasing order unavailable")
        val order = (record.value.body as PurchasingBody.Order).value
        val details = order.lines.map { line ->
            val billedRow = reader.orderBilling(order, line.allocationId)
            val billed = billedRow.value.body as PurchasingBody.OrderBilled
            val allowance = stock.purchaseAllowanceFacts(view, line.allocationId)
            if (allowance == null) check(billed.quantity.scaled == 0L)
            else check(allowance.basisId == order.id && allowance.supplierId == order.supplierId && allowance.skuId == line.goods.skuId &&
                allowance.warehouseId == line.warehouseId && allowance.committedQuantity <= line.goods.baseQuantity() &&
                allowance.committedOwnedValue.minor <= line.goods.net.minor && billed.quantity <= allowance.acceptedQuantity)
            val goods = line.goods
            LocalPurchasingLineViewV1(line.allocationId, line.allocationId, goods.skuId, billedRow.row.revision,
                line.warehouseId, goods.units, goods.quantity, goods.baseQuantity(), billed.quantity,
                allowance?.committedQuantity, allowance?.acceptedQuantity, allowance?.pendingInspectionQuantity,
                goods.unitPrice.takeIf { query.includeCosts }, goods.discount.takeIf { query.includeCosts }, goods.tax.takeIf { query.includeCosts },
                goods.net.takeIf { query.includeCosts }, goods.taxAmount.takeIf { query.includeCosts },
                billed.net.takeIf { query.includeCosts }, billed.tax.takeIf { query.includeCosts })
        }
        // Approval is not a supplier acknowledgement or an automatically ORDERED/received status.
        return LocalPurchasingDocumentViewV1(order.id, record.row.revision, order.supplierId, order.id, "APPROVED", order.orderDate, null,
            null, null, if (query.id != null) details else emptyList(), emptyList(), order.reference.takeIf { query.includeCosts }, null,
            order.paymentTerms.takeIf { query.includeCosts }, order.charges.takeIf { query.includeCosts }, null, null, null, null)
    }

    internal suspend fun invoiceView(reader: PurchasingReader, view: CommandReadView, id: DomainId, query: LocalPurchasingQueryV1): LocalPurchasingDocumentViewV1 {
        val cut = reader.invoice(id) ?: error("Purchasing invoice unavailable")
        requirePurchasingCrossAccounts(reader, cut, stock, view)
        val invoice = purchasingInvoiceClaim(cut.invoice)
        if (invoice.captured && query.section != LocalPurchasingSectionV1.PAYABLE)
            throw PurchasingInvoiceCaptureUnqualified(checkNotNull(reader.captureInvoice(id)))
        val originalDiscrepancies = when (val body = cut.invoice.value.body) {
            is PurchasingBody.Invoice -> body.discrepancies
            is PurchasingBody.InvoiceCapture -> body.discrepancies
            else -> error("Purchasing invoice claim format required")
        }
        val order = (cut.order.value.body as PurchasingBody.Order).value
        val state = cut.progress.value.body as PurchasingBody.Progress
        val payable = cut.payable?.value?.body as? PurchasingBody.Payable
        val due = purchasingDue(cut)
        if (query.section == LocalPurchasingSectionV1.PAYABLE) check(payable != null)
        val uncontestedEvents = cut.matches.filter { it.uncontested != null }
        val disputedPriceClaim = if (uncontestedEvents.isEmpty()) null else uncontestedEvents.fold(purchaseZero()) { total, event ->
            total + requireNotNull(event.uncontested).disputed()
        }
        val details = invoice.lines.mapNotNull { line ->
            // V1 requires a real allocation ID. Unmatched capture lines remain only
            // in the unchanged raw V2 document and its complete recognition overlay.
            val allocationId = if (invoice.captured) {
                val selected = cut.matches.filter { it.request.invoiceLineId == line.lineId }.map { it.allocationId }.distinct()
                check(selected.size <= 1)
                selected.singleOrNull() ?: return@mapNotNull null
            } else checkNotNull(line.allocationId)
            val goods = line.goods
            val progress = state.lines.single { it.lineId == line.lineId }
            val original = order.lines.singleOrNull { it.allocationId == allocationId }
            val billing = original?.let { reader.orderBilling(order, it.allocationId) }
            LocalPurchasingLineViewV1(line.lineId, allocationId, goods.skuId, billing?.row?.revision, original?.warehouseId,
                goods.units, goods.quantity, goods.baseQuantity(), progress.quantity, null, null, null,
                goods.unitPrice.takeIf { query.includeCosts }, goods.discount.takeIf { query.includeCosts }, goods.tax.takeIf { query.includeCosts },
                goods.net.takeIf { query.includeCosts }, goods.taxAmount.takeIf { query.includeCosts },
                progress.net.takeIf { query.includeCosts }, progress.tax.takeIf { query.includeCosts },
                uncontestedEvents.filter { it.request.invoiceLineId == line.lineId }.takeIf { it.isNotEmpty() && query.includeCosts }
                    ?.fold(purchaseZero()) { total, event -> total + requireNotNull(event.uncontested).disputed() })
        }
        val fullyMatched = invoice.lines.all { line -> state.lines.single { it.lineId == line.lineId }.quantity == line.goods.baseQuantity() }
        val settlementStatus = if (!query.includeCosts || due == null) null else when {
            cut.settlements.allocatedTotal.minor > 0L -> when {
                due.remainingDue.minor == 0L -> if (disputedPriceClaim == null) "RECOGNIZED_DUE_CLEARED_BY_SETTLEMENT"
                    else "RECOGNIZED_DUE_CLEARED_BY_SETTLEMENT_WITH_PRICE_DISPUTE"
                disputedPriceClaim != null -> "PARTIALLY_SETTLED_WITH_PRICE_DISPUTE"
                else -> "PARTIALLY_SETTLED"
            }
            cut.settlements.reservedTotal.minor > 0L -> "UNSETTLED_AWAITING_SUPPLIER_CASH"
            cut.credits.index != null -> when {
                requireNotNull(due).remainingDue.minor == 0L -> if (disputedPriceClaim == null) "RECOGNIZED_DUE_CLEARED_BY_CREDIT"
                    else "RECOGNIZED_DUE_CLEARED_BY_CREDIT_WITH_PRICE_DISPUTE"
                disputedPriceClaim != null -> "UNSETTLED_WITH_CREDIT_AND_PRICE_DISPUTE"
                else -> "UNSETTLED_WITH_CREDIT"
            }
            else -> if (disputedPriceClaim == null) "UNSETTLED" else "UNSETTLED_WITH_PRICE_DISPUTE"
        }
        val status = when {
            query.section == LocalPurchasingSectionV1.PAYABLE -> checkNotNull(settlementStatus)
            state.matchIds.isEmpty() -> "UNMATCHED"
            query.includeCosts && disputedPriceClaim != null -> if (fullyMatched) "GOODS_ALLOCATED_PRICE_DISPUTED" else "PARTIALLY_MATCHED_PRICE_DISPUTED"
            !fullyMatched -> "PARTIALLY_MATCHED"
            query.includeCosts && invoice.charges.isNotEmpty() -> "GOODS_MATCHED_CHARGES_UNMATCHED"
            else -> "MATCHED_GOODS"
        }
        val matched = due?.recognized
        val discrepancies = if (query.includeCosts) originalDiscrepancies else originalDiscrepancies.filter {
            it in setOf(PurchaseDiscrepancyV1.ORDER_LINE_REQUIRED, PurchaseDiscrepancyV1.ITEM_OR_UNIT_VARIANCE, PurchaseDiscrepancyV1.QUANTITY_VARIANCE)
        }
        return LocalPurchasingDocumentViewV1(invoice.id,
            if (query.section == LocalPurchasingSectionV1.PAYABLE) requireNotNull(cut.payable).row.revision else cut.invoice.row.revision,
            invoice.supplierId, order.id, status, invoice.invoiceDate, invoice.dueDate.takeIf { query.includeCosts },
            cut.progress.row.revision, cut.payable?.row?.revision.takeIf { query.includeCosts }, if (query.id != null) details else emptyList(), discrepancies,
            invoice.reference.takeIf { query.includeCosts }, invoice.sourceEvidenceId.takeIf { query.includeCosts }, null,
            invoice.charges.takeIf { query.includeCosts }, invoice.total().takeIf { query.includeCosts }, matched.takeIf { query.includeCosts },
            (invoice.total() - (matched ?: purchaseZero())).takeIf { query.includeCosts }, due?.remainingDue.takeIf { query.includeCosts },
            disputedPriceClaim.takeIf { query.includeCosts },
            creditRevision = cut.credits.index?.row?.revision.takeIf { query.includeCosts },
            appliedCredit = due?.appliedCredit.takeIf { query.includeCosts && cut.credits.index != null },
            credits = cut.credits.entries.takeIf { query.id != null && query.includeCosts && it.isNotEmpty() }?.map { it.creditView() },
            settlementRevision = cut.settlements.index?.row?.revision.takeIf { query.includeCosts },
            allocatedPayment = due?.allocatedPayment.takeIf { query.includeCosts && cut.settlements.index != null },
            reservedSupplierCash = cut.settlements.reservedTotal.takeIf { query.includeCosts && (invoice.captured || cut.settlements.index != null) },
            availableToAllocate = purchasingAvailableToAllocate(cut).takeIf { query.includeCosts && (invoice.captured || cut.settlements.index != null) },
            settlementStatus = settlementStatus.takeIf { cut.settlements.index != null },
            settlementAllocations = cut.settlements.allocations.takeIf { query.id != null && query.includeCosts && it.isNotEmpty() }?.map { row ->
                val allocation = (row.value.body as PurchasingBody.SettlementAllocation).value
                val before = checkNotNull(allocation.basisBefore.remainingDue)
                LocalSupplierAllocationViewV1(allocation.id, allocation.paymentId, allocation.advanceId, allocation.amount,
                    row.value.recordedAt, before, before - allocation.amount)
            }, capturedInvoice = invoice.captured)
    }
}

internal class PurchasingMatchSourceRead(
    private val stock: LocalStockRepository, override val key: ReadKey = LocalPurchasingProfile.READ_MATCH_SOURCE,
) : MasterReadHandler {
    init { require(key == LocalPurchasingProfile.READ_MATCH_SOURCE || key == LocalPurchasingProfile.READ_UNCONTESTED_MATCH_SOURCE) }
    override fun normalize(input: StoredBytes): StoredBytes = if (key == LocalPurchasingProfile.READ_UNCONTESTED_MATCH_SOURCE)
        LocalPurchasingCodec.uncontestedMatchSource(purchasingDecode(input, LocalPurchasingUncontestedMatchSourceQueryV1.serializer()))
        else LocalPurchasingCodec.matchSource(purchasingDecode(input, LocalPurchasingMatchSourceQueryV1.serializer()))
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        requirePurchasingReadContext(view, context, key, normalized)
        val requested = if (key == LocalPurchasingProfile.READ_UNCONTESTED_MATCH_SOURCE)
            purchasingDecode(normalized, LocalPurchasingUncontestedMatchSourceQueryV1.serializer()) else null
        val query = requested?.source ?: purchasingDecode(normalized, LocalPurchasingMatchSourceQueryV1.serializer())
        val reader = PurchasingReader(view)
        val selected = reader.row(LocalPurchasingProfile.INVOICE, query.invoiceId)
        val facts = stock.purchaseReceiptFacts(view, query.receiptId, query.receiptLineId)
        val bindings = listOf(query.invoiceId to (selected?.row?.revision ?: Revision(0)),
            query.receiptId to (facts?.receiptRevision ?: Revision(0)))
        return boundedPurchasingRead(context, bindings, true, false, { purchasingUnavailableMatchSource(query, requested) },
            { purchasingUnavailableMatchSource(query, requested, LocalPurchasingReadAvailabilityV1.UNSUPPORTED_INVOICE_CAPTURE) }) {
            if (selected?.value?.body is PurchasingBody.InvoiceCapture)
                throw PurchasingInvoiceCaptureUnqualified(checkNotNull(reader.captureInvoice(query.invoiceId)))
            val cut = reader.invoice(query.invoiceId)
            val invoice = (cut?.invoice?.value?.body as? PurchasingBody.Invoice)?.value
            val order = (cut?.order?.value?.body as? PurchasingBody.Order)?.value
            val line = invoice?.lines?.singleOrNull { it.lineId == query.invoiceLineId }
            val ordered = order?.lines?.singleOrNull { it.allocationId == line?.allocationId }
            val billing = if (order != null && ordered != null) reader.orderBilling(order, ordered.allocationId) else null
            val orderMatches = order?.let { reader.orderMatches(it.id) }
            val receiptBilling = facts?.let { reader.receiptBilling(it.sourceId) }
            if (cut != null) requirePurchasingCrossAccounts(reader, cut, stock, view)
            val invoiceState = cut?.progress?.value?.body as? PurchasingBody.Progress
            val lineState = invoiceState?.lines?.singleOrNull { it.lineId == query.invoiceLineId }
            val orderState = billing?.value?.body as? PurchasingBody.OrderBilled
            val receiptState = receiptBilling?.value?.body as? PurchasingBody.ReceiptBilled
            if (facts != null && receiptState != null) check(receiptState.receiptId == facts.receiptId && receiptState.lineId == facts.lineId &&
                receiptState.orderId == facts.basisId && receiptState.allocationId == facts.allocationId && receiptState.quantity <= facts.acceptedQuantity)
            val compatible = facts != null && order != null && line != null && ordered != null &&
                facts.scope == view.scope && facts.basisId == order.id && facts.basisRevision == Revision(0) &&
                facts.basisKind == LocalStockBasisKind.LOCAL_PURCHASE && facts.supplierId == order.supplierId && facts.allocationId == ordered.allocationId &&
                facts.skuId == ordered.goods.skuId && facts.warehouseId == ordered.warehouseId && purchasingReceiptUnitsAgree(ordered, facts) &&
                purchasingReceiptOrderSourceAgrees(requireNotNull(cut).order, facts.poValuation) && facts.originalOwnedValue != null && facts.allowance != null &&
                facts.disposition in setOf(LocalReceiptDisposition.RELEASED, LocalReceiptDisposition.QUARANTINE) &&
                (if (requested == null) purchaseLineDiscrepancies(ordered.goods, line.goods).isEmpty()
                    else purchaseUncontestedPriceIncrease(ordered.goods, line.goods)) &&
                purchasingReceiptValueAgrees(order, ordered, facts)
            val result = LocalPurchasingMatchSourceV1(query, cut != null && line != null && ordered != null && facts != null,
                cut?.invoice?.row?.revision, cut?.progress?.row?.revision, billing?.row?.revision, cut?.payable?.row?.revision,
                facts?.receiptRevision, receiptBilling?.row?.revision, facts?.sourceId,
                if (line != null && lineState != null) line.goods.baseQuantity() - lineState.quantity else null,
                if (ordered != null && orderState != null) ordered.goods.baseQuantity() - orderState.quantity else null,
                facts?.let { it.acceptedQuantity - (receiptState?.quantity ?: it.baseQuantity.copy(scaled = 0)) }, compatible, orderMatches?.row?.revision)
            if (requested == null) purchasingEncode(result, LocalPurchasingMatchSourceV1.serializer())
            else {
                val quantity = requested.quantity
                val amounts = if (compatible && line != null && ordered != null && facts != null && lineState != null && orderState != null &&
                    quantity.unitId == facts.baseQuantity.unitId && quantity.scale == facts.baseQuantity.scale &&
                    listOf(result.invoiceRemaining, result.orderRemaining, result.acceptedReceiptRemaining).all { remaining ->
                        remaining != null && remaining.unitId == quantity.unitId && remaining.scale == quantity.scale &&
                            remaining.scaled >= 0 && quantity <= remaining
                    } && quantity <= requireNotNull(facts.allowance).acceptedQuantity - orderState.quantity &&
                    (facts.serialId == null || quantity == facts.baseQuantity)) {
                    purchaseUncontestedAmounts(ordered.goods, line.goods, orderState.quantity, lineState.quantity, quantity)
                } else null
                purchasingEncode(LocalPurchasingUncontestedMatchSourceV1(requested, result, amounts), LocalPurchasingUncontestedMatchSourceV1.serializer())
            }
        }
    }
}

/** Only preparation's private purchasing capacity signal is translated; finalization is outside this helper. */
internal suspend fun boundedPurchasingRead(
    context: ReadContext, bindings: List<Pair<DomainId, Revision>>, costs: Boolean, export: Boolean,
    onCapacity: () -> StoredBytes, onUnsupportedCapture: (() -> StoredBytes)? = null, prepare: suspend () -> StoredBytes,
): PreparedRead {
    val projection = try { prepare() }
        catch (_: PurchasingHydrationCapacityUnavailable) { onCapacity() }
        catch (unsupported: PurchasingInvoiceCaptureUnqualified) { onUnsupportedCapture?.invoke() ?: throw unsupported }
    return preparedPurchasingRead(context, bindings, costs, export) { projection }
}

private fun purchasingUnavailablePage(query: LocalPurchasingQueryV1,
    availability: LocalPurchasingReadAvailabilityV1 = LocalPurchasingReadAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE): StoredBytes = purchasingEncode(
    LocalPurchasingPageV1(query.section, null, null, query.includeCosts,
        availability,
        if (availability == LocalPurchasingReadAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE && query.id == null && query.limit > 1)
            maxOf(1, query.limit / 2) else null), LocalPurchasingPageV1.serializer())

private fun purchasingUnavailableMatchSource(
    query: LocalPurchasingMatchSourceQueryV1, requested: LocalPurchasingUncontestedMatchSourceQueryV1? = null,
    availability: LocalPurchasingReadAvailabilityV1 = LocalPurchasingReadAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE,
): StoredBytes {
    val unavailable = LocalPurchasingMatchSourceV1(query = query, sourcePresent = null,
        invoiceRevision = null, progressRevision = null, orderBillingRevision = null, payableRevision = null,
        receiptRevision = null, receiptBillingRevision = null, sourceId = null, invoiceRemaining = null,
        orderRemaining = null, acceptedReceiptRemaining = null, sourceAndTermsCompatible = null, orderMatchRevision = null,
        availability = availability)
    return if (requested == null) purchasingEncode(unavailable, LocalPurchasingMatchSourceV1.serializer())
        else purchasingEncode(LocalPurchasingUncontestedMatchSourceV1(requested, unavailable, null), LocalPurchasingUncontestedMatchSourceV1.serializer())
}

internal fun requirePurchasingReadContext(view: CommandReadView, context: ReadContext, key: ReadKey, normalized: StoredBytes) {
    check(context.key == key && context.scope == view.scope && queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
}

private fun preparedPurchasingRead(
    context: ReadContext, bindings: List<Pair<DomainId, Revision>>, costs: Boolean, export: Boolean, projection: () -> StoredBytes,
): PreparedRead = object : PreparedRead() {
    override val requirements: List<AuthorizationRequirement> = bindings.distinct().flatMap { (id, revision) ->
        val actions = listOf(SecurityAction.PURCHASING_READ, SecurityAction.INVENTORY_QUANTITY_READ) +
            if (costs) listOf(SecurityAction.INVENTORY_COST_READ) else emptyList()
        actions.flatMap { action -> (listOf(PermissionVerb.PERFORM) + if (export) listOf(PermissionVerb.EXPORT) else emptyList()).map { verb ->
            AuthorizationRequirement(Permission(action, verb), context.scope, ActionBinding(id, revision, context.semanticDigest, reasonCode = "purchasing.read"))
        } }
    }
    override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
        check(authorizations.size == requirements.size && authorizations.zip(requirements).all { (authorization, requirement) ->
            authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.actorId)
        })
        return projection()
    }
}
