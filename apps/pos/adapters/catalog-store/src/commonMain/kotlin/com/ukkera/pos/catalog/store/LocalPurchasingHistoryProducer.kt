package com.ukkera.pos.catalog.store

import com.ukkera.pos.catalog.StockTracking
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Actual nonphysical purchasing history over the core-native BEFORE cut and the SAME
 * installed configuration/stock repositories. No handler, employee session, historical
 * CommandReadView, source-free producer certificate or mutable current row is used.
 *
 * Finite approvals require the original native final-deadline decision as well as the
 * original evaluation interval. Preparation time alone cannot prove that final decision.
 * Older histories without it remain unqualified; dependants require an earlier fully
 * joined original. This creates no authority, settlement, stock effect or migration. */
internal class LocalPurchasingHistoryProducer(
    private val configuration: LocalConfigurationRepository,
    private val stock: LocalStockRepository,
) : RuntimeHistoryBusinessProducer {
    private val credit: LocalPurchasingCreditHistoryProducer = LocalPurchasingCreditHistoryProducer(stock)
    override val commands: Set<CommandKey> = setOf(LocalPurchasingProfile.APPROVE_ORDER,
        LocalPurchasingProfile.RECORD_INVOICE, LocalPurchasingProfile.MATCH_INVOICE, LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE,
        LocalPurchasingProfile.RECORD_CREDIT)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.original !is RetainedOriginalV2.Legacy) return false
        val original = (command.original as RetainedOriginalV2.Legacy).evidence
        requirePurchasingProducer(original)
        return when (command.command) {
            LocalPurchasingProfile.APPROVE_ORDER -> approve(command, purchasingDecode(command.normalized, LocalApprovePurchaseOrderV1.serializer()))
            LocalPurchasingProfile.RECORD_INVOICE -> invoice(command, purchasingDecode(command.normalized, LocalRecordSupplierInvoiceV1.serializer()))
            LocalPurchasingProfile.MATCH_INVOICE -> match(command, purchasingDecode(command.normalized, LocalMatchSupplierInvoiceV1.serializer()))
            LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE -> LocalPurchasingCodec.decodeUncontestedMatch(command.normalized).let {
                match(command, it.match, it.amounts)
            }
            LocalPurchasingProfile.RECORD_CREDIT -> credit.join(command)
            else -> false
        }
    }

    private suspend fun approve(command: RuntimeHistoricalCommand, request: LocalApprovePurchaseOrderV1): Boolean {
        val order = request.order
        val deadline = order.validUntil
        if (deadline != null && !command.tryRequireFinalDeadlineDecision(deadline)) return false
        requirement(command, order.id, Revision(0), request.reasonCode, order.total())
        val reader = PurchasingReader(command)
        check(reader.row(LocalPurchasingProfile.ORDER, order.id) == null &&
            reader.row(LocalPurchasingProfile.ORDER_MATCHES, order.id) == null &&
            command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.BASIS, order.id)) == null)
        val supplier = supplier(command, order.supplierId) ?: error("Original purchasing supplier unavailable")
        val items = order.lines.map { it.goods.skuId }.distinct().associateWith { configuration.historicalStockItem(command, it) }
        val warehouses = order.lines.map { it.warehouseId }.distinct().associateWith { configuration.historicalStockWarehouse(command, it) }
        val reference = purchasingReferenceClaim(command.scope, "order", order.supplierId, order.reference)
        check(command.claim(reference) == null)
        for (line in order.lines) {
            check(command.claim(ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, "allocation/${line.allocationId.value}")) == null &&
                reader.row(LocalPurchasingProfile.ORDER_BILLED, line.allocationId) == null &&
                stock.purchaseAllowanceFacts(command, line.allocationId) == null)
        }
        val stamps = (items.values.filterNotNull().flatMap { it.sources } + warehouses.values.filterNotNull().flatMap { it.sources })
            .groupBy { it.key }.map { (_, list) -> check(list.distinct().size == 1); list.first() }
        val sources = stamps.map { stamp ->
            val row = command.aggregateSource(stamp.key)?.row ?: error("Original purchasing source unavailable")
            check(row.revision == stamp.storageRevision && row.digest == stamp.digest)
            row
        } + supplier.first
        check(supplier.second.active && supplier.first.revision == request.expectedSupplierRevision &&
            order.isApprovalEvaluationCurrent(command.recordedAt))
        for (line in order.lines) {
            val goods = line.goods
            val item = items[goods.skuId] ?: error("Original purchasing item unavailable")
            check(item.scope == command.scope && item.active && item.canPurchase && item.tracking != StockTracking.NONE &&
                item.productId == goods.units.productId && item.baseUnitId == goods.units.baseUnitId && item.quantityScale == goods.units.baseScale)
            check(warehouses[line.warehouseId]?.let { it.scope == command.scope && it.active } == true)
            val conversion = goods.units.conversionId?.let { id -> item.conversions.singleOrNull { it.id == id && it.revision == goods.units.conversionRevision } }
            check(conversion == goods.units.conversion() && goods.amountsAgree())
            item.unitDefinitions?.let { units ->
                check(units.singleOrNull { it.id == goods.quantity.unitId }
                    ?.let { goods.quantity.scale <= it.maximumScale } == true)
            }
            if (item.tracking == StockTracking.SERIAL) goods.baseQuantity().requireWholeUnits()
        }
        // Preserve the document's exact legacy source list, while the full
        // BEFORE publication join and protected resource set remain above/below.
        val body = PurchasingBody.Order(order, normalizedPurchaseReference(order.reference),
            sources.filter { it.key.kind != LocalConfigurationProfile.PUBLICATION }.map(PurchasingSource::from)
            .sortedWith(compareBy<PurchasingSource> { it.kind }.thenBy { it.id.value }))
        val changes = mutableListOf(change(command, LocalPurchasingProfile.ORDER, order.id, null, body),
            change(command, LocalPurchasingProfile.ORDER_MATCHES, order.id, null, PurchasingBody.OrderMatches(order.id, emptyList())),
            AggregateChange(LocalConfigurationProfile.key(LocalConfigurationProfile.BASIS, order.id), null,
                LocalConfigurationProfile.schema(LocalConfigurationProfile.BASIS), encodeValue(ConfigurationRecord(command.scope,
                    LocalConfigurationProfile.BASIS, order.id, Revision(0), ConfigurationBody.Basis(purchasingBasis(order)),
                    command.actorId, command.policyRevision, command.recordedAt), ConfigurationRecord.serializer())))
        val claims = mutableListOf(purchasingAcquisition(LocalPurchasingProfile.ORDER, order.id),
            purchasingAcquisition(LocalPurchasingProfile.ORDER_MATCHES, order.id), ClaimChange.Acquire(reference, order.id))
        for (line in order.lines) {
            changes += change(command, LocalPurchasingProfile.ORDER_BILLED, line.allocationId, null,
                PurchasingBody.OrderBilled(order.id, line.allocationId, line.goods.baseQuantity().copy(scaled = 0), purchaseZero(), purchaseZero(), emptyList()))
            claims += purchasingAcquisition(LocalPurchasingProfile.ORDER_BILLED, line.allocationId)
            claims += ClaimChange.Acquire(ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, "allocation/${line.allocationId.value}"), order.id)
        }
        val resources = purchasingResources(order) + sources.flatMap { row ->
            val record = decodeRecord(command.scope, row)
            listOf(authorizationResource(row.key.id), authorizationResource(record.authorizedBy))
        } + RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, purchasingReferenceResource(reference)) + authorizationResource(command.actorId)
        command.requireLegacyEffects(ProposedOutcome.Apply(purchasingResult("purchasing.order_approved", order.id, Revision(1)),
            changes, claims, mustCommitBefore = order.validUntil), resources)
        return true
    }

    private suspend fun invoice(command: RuntimeHistoricalCommand, request: LocalRecordSupplierInvoiceV1): Boolean {
        val invoice = request.invoice
        requirement(command, invoice.id, Revision(0), request.reasonCode, invoice.total())
        val reader = PurchasingReader(command)
        check(reader.invoice(invoice.id) == null)
        val orderRow = reader.order(invoice.orderId) ?: error("Original purchasing order unavailable")
        val order = (orderRow.value.body as PurchasingBody.Order).value
        val supplier = supplier(command, invoice.supplierId) ?: error("Original purchasing supplier unavailable")
        val reference = purchasingReferenceClaim(command.scope, "invoice", invoice.supplierId, invoice.reference)
        check(command.claim(reference) == null && orderRow.row.revision == request.expectedOrderRevision &&
            order.supplierId == invoice.supplierId && supplier.second.active && supplier.first.revision == request.expectedSupplierRevision)
        val body = PurchasingBody.Invoice(invoice, normalizedPurchaseReference(invoice.reference), PurchasingSource.from(orderRow.row),
            PurchasingSource.from(supplier.first), purchaseDiscrepancies(order, invoice).sortedBy { it.name })
        val resources = purchasingResources(order) + setOf(
            RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, invoice.orderId),
            RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, invoice.id),
            RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, invoice.supplierId),
            RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, purchasingReferenceResource(reference)),
            RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, invoice.sourceEvidenceId),
            authorizationResource(orderRow.value.producer.actorId), authorizationResource(decodeRecord(command.scope, supplier.first).authorizedBy),
            authorizationResource(command.actorId))
        command.requireLegacyEffects(ProposedOutcome.Apply(purchasingResult("purchasing.invoice_recorded", invoice.id, Revision(1), Revision(1)),
            listOf(change(command, LocalPurchasingProfile.INVOICE, invoice.id, null, body),
                change(command, LocalPurchasingProfile.PROGRESS, invoice.id, null, purchasingInitialProgress(invoice))),
            listOf(purchasingAcquisition(LocalPurchasingProfile.INVOICE, invoice.id), purchasingAcquisition(LocalPurchasingProfile.PROGRESS, invoice.id),
                ClaimChange.Acquire(reference, invoice.id))), resources)
        return true
    }

    private suspend fun match(
        command: RuntimeHistoricalCommand, request: LocalMatchSupplierInvoiceV1, reviewed: PurchaseUncontestedAmountsV1? = null,
    ): Boolean {
        check((reviewed == null) == (command.command == LocalPurchasingProfile.MATCH_INVOICE))
        val reader = PurchasingReader(command)
        val cut = reader.invoice(request.invoiceId) ?: error("Original purchasing invoice unavailable")
        val invoice = (cut.invoice.value.body as PurchasingBody.Invoice).value
        val order = (cut.order.value.body as PurchasingBody.Order).value
        requirement(command, invoice.id, request.expectedProgressRevision, request.reasonCode, invoice.total())
        val progress = cut.progress.value.body as PurchasingBody.Progress
        val orderMatchRow = reader.orderMatches(order.id)
        val orderMatches = orderMatchRow.value.body as PurchasingBody.OrderMatches
        val line = invoice.lines.singleOrNull { it.lineId == request.invoiceLineId } ?: error("Original purchasing invoice line unavailable")
        val ordered = order.lines.singleOrNull { it.allocationId == line.allocationId } ?: error("Original purchasing order line unavailable")
        check(reader.row(LocalPurchasingProfile.MATCH, request.matchId) == null)
        val orderBill = reader.orderBilling(order, ordered.allocationId)
        val receipt = stock.purchaseReceiptFacts(command, request.receiptId, request.receiptLineId) ?: error("Original stock receipt unavailable")
        val receiptBill = reader.receiptBilling(receipt.sourceId)
        requirePurchasingCrossAccounts(reader, cut, stock, command)
        check(cut.invoice.row.revision == request.expectedInvoiceRevision && cut.progress.row.revision == request.expectedProgressRevision &&
            orderMatchRow.row.revision == request.expectedOrderMatchRevision && cut.payable?.row?.revision == request.expectedPayableRevision &&
            orderBill.row.revision == request.expectedOrderBillingRevision && receiptBill?.row?.revision == request.expectedReceiptBillingRevision &&
            receipt.receiptRevision == request.expectedReceiptRevision)
        check((if (reviewed == null) purchaseLineDiscrepancies(ordered.goods, line.goods).isEmpty()
            else purchaseUncontestedPriceIncrease(ordered.goods, line.goods)) && receipt.scope == command.scope && receipt.basisId == order.id &&
            receipt.basisRevision == Revision(0) && receipt.basisKind == LocalStockBasisKind.LOCAL_PURCHASE && receipt.supplierId == invoice.supplierId &&
            receipt.allocationId == ordered.allocationId && receipt.skuId == ordered.goods.skuId && receipt.warehouseId == ordered.warehouseId &&
            purchasingReceiptUnitsAgree(ordered, receipt) && purchasingReceiptOrderSourceAgrees(cut.order, receipt.poValuation))
        check(receipt.disposition in setOf(LocalReceiptDisposition.RELEASED, LocalReceiptDisposition.QUARANTINE) &&
            receipt.originalOwnedValue != null && receipt.acceptedQuantity.scaled > 0 && receipt.allowance != null)
        val beforeInvoice = progress.lines.single { it.lineId == line.lineId }
        val beforeOrder = orderBill.value.body as PurchasingBody.OrderBilled
        val beforeReceipt = receiptBill?.value?.body as? PurchasingBody.ReceiptBilled
        if (beforeReceipt != null) check(beforeReceipt.orderId == order.id && beforeReceipt.allocationId == ordered.allocationId &&
            beforeReceipt.receiptId == receipt.receiptId && beforeReceipt.lineId == receipt.lineId)
        // The optional billing row/claim was genuinely absent. The existing first-match
        // state starts at zero in the OBSERVED receipt's units; unavailable data never does.
        val receiptQuantity = beforeReceipt?.quantity ?: receipt.baseQuantity.copy(scaled = 0)
        check(orderMatches.matchIds.size < LocalPurchasingProfile.MAX_MATCHES && progress.matchIds.size < LocalPurchasingProfile.MAX_MATCHES &&
            beforeOrder.matchIds.size < LocalPurchasingProfile.MAX_MATCHES && (beforeReceipt?.matchIds?.size ?: 0) < LocalPurchasingProfile.MAX_MATCHES)
        check(request.quantity.unitId == receipt.baseQuantity.unitId && request.quantity.scale == receipt.baseQuantity.scale &&
            request.quantity <= line.goods.baseQuantity() - beforeInvoice.quantity &&
            request.quantity <= ordered.goods.baseQuantity() - beforeOrder.quantity && request.quantity <= receipt.acceptedQuantity - receiptQuantity &&
            request.quantity <= requireNotNull(receipt.allowance).acceptedQuantity - beforeOrder.quantity)
        check(receipt.serialId == null || request.quantity == receipt.baseQuantity)
        check(purchasingReceiptValueAgrees(order, ordered, receipt))
        val after = beforeInvoice.quantity + request.quantity
        val uncontested = if (reviewed == null) null else {
            purchaseUncontestedAmounts(ordered.goods, line.goods, beforeOrder.quantity, beforeInvoice.quantity, request.quantity)
                ?: error("Original purchasing rounding policy required")
        }
        if (reviewed != null) check(uncontested == reviewed)
        val net = uncontested?.net ?: (purchaseCumulativeAmount(line.goods.net, after, line.goods.baseQuantity()) - beforeInvoice.net)
        val tax = uncontested?.tax ?: (purchaseCumulativeAmount(line.goods.taxAmount, after, line.goods.baseQuantity()) - beforeInvoice.tax)
        check(net == purchaseCumulativeAmount(ordered.goods.net, beforeOrder.quantity + request.quantity, ordered.goods.baseQuantity()) - beforeOrder.net &&
            tax == purchaseCumulativeAmount(ordered.goods.taxAmount, beforeOrder.quantity + request.quantity, ordered.goods.baseQuantity()) - beforeOrder.tax)
        reader.requireSupplierHydrationCapacity()
        val event = PurchasingBody.Match(request, order.id, ordered.allocationId, PurchasingReceiptSnapshot.from(receipt),
            beforeInvoice.quantity, beforeOrder.quantity, receiptQuantity, net, tax, uncontested)
        requirePurchasingMatch(order, invoice, event)
        val nextProgress = progress.copy(lines = progress.lines.map { if (it.lineId == line.lineId)
            it.copy(quantity = after, net = it.net + net, tax = it.tax + tax) else it }, matchIds = progress.matchIds + request.matchId)
        val nextOrder = beforeOrder.copy(quantity = beforeOrder.quantity + request.quantity, net = beforeOrder.net + net,
            tax = beforeOrder.tax + tax, matchIds = beforeOrder.matchIds + request.matchId)
        val nextReceipt = PurchasingBody.ReceiptBilled(receipt.receiptId, receipt.lineId, receipt.sourceId, order.id, ordered.allocationId,
            receiptQuantity + request.quantity, (beforeReceipt?.net ?: purchaseZero()) + net, (beforeReceipt?.tax ?: purchaseZero()) + tax,
            beforeReceipt?.matchIds.orEmpty() + request.matchId)
        val nextPayable = PurchasingBody.Payable(invoice.id, order.id, invoice.supplierId, invoice.dueDate, PurchasingPayableStatus.UNSETTLED,
            nextProgress.lines.fold(purchaseZero()) { sum, it -> sum + it.net }, nextProgress.lines.fold(purchaseZero()) { sum, it -> sum + it.tax }, nextProgress.matchIds)
        val resources = purchasingResources(order) + receipt.recoveryResources + setOf(
            RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, invoice.id),
            RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, request.matchId),
            RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.receiptId),
            RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.receiptLineId),
            RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, invoice.sourceEvidenceId),
            authorizationResource(cut.order.value.producer.actorId), authorizationResource(cut.invoice.value.producer.actorId), authorizationResource(command.actorId)) + reader.supplierRecoveryResources
        val claims = listOf(purchasingAcquisition(LocalPurchasingProfile.MATCH, request.matchId)) +
            (if (receiptBill == null) listOf(purchasingAcquisition(LocalPurchasingProfile.RECEIPT_BILLED, receipt.sourceId)) else emptyList()) +
            (if (cut.payable == null) listOf(purchasingAcquisition(LocalPurchasingProfile.PAYABLE, invoice.id)) else emptyList())
        command.requireLegacyEffects(ProposedOutcome.Apply(purchasingResult(if (reviewed == null) "purchasing.invoice_matched" else "purchasing.invoice_uncontested_matched",
            request.matchId, Revision(1), cut.progress.row.revision.next()),
            listOf(change(command, LocalPurchasingProfile.MATCH, request.matchId, null, event),
                change(command, LocalPurchasingProfile.ORDER_MATCHES, order.id, orderMatchRow, orderMatches.copy(matchIds = orderMatches.matchIds + request.matchId)),
                change(command, LocalPurchasingProfile.PROGRESS, invoice.id, cut.progress, nextProgress),
                change(command, LocalPurchasingProfile.ORDER_BILLED, ordered.allocationId, orderBill, nextOrder),
                change(command, LocalPurchasingProfile.RECEIPT_BILLED, receipt.sourceId, receiptBill, nextReceipt),
                change(command, LocalPurchasingProfile.PAYABLE, invoice.id, cut.payable, nextPayable)), claims), resources)
        return true
    }

    private suspend fun supplier(command: RuntimeHistoricalCommand, id: DomainId): Pair<StoredAggregate, LocalSupplierV1>? {
        val row = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, id))?.row ?: return null
        val value = (decodeRecord(command.scope, row).body as? ConfigurationBody.Supplier)?.value ?: error("Original purchasing supplier unavailable")
        check(value.id == id)
        return row to value
    }

    private suspend fun requirement(command: RuntimeHistoricalCommand, id: DomainId, revision: Revision, reason: String, amount: Money) {
        command.requireRequirement(purchasingRequirement(command.scope, id, revision, command.operationId, command.semanticDigest, reason, amount))
    }

    /** Historical AFTER data, carrying actual captured identity/time/policy. No authorization
     * or CommandContext is recreated, and core compares original BEFORE generations. */
    private fun change(command: RuntimeHistoricalCommand, kind: String, id: DomainId, before: PurchasingRow?, body: PurchasingBody): AggregateChange =
        AggregateChange(purchasingKey(kind, id), before?.row?.revision, LocalPurchasingProfile.schema(kind),
            purchasingEncode(PurchasingRecord(command.scope, kind, id, (before?.row?.revision ?: Revision(0)).next(),
                PurchasingProducer(command.operationId, command.actorId, command.command.name, command.semanticDigest.value),
                command.recordedAt, command.policyRevision, body), PurchasingRecord.serializer()))

    private fun authorizationResource(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id)
}
