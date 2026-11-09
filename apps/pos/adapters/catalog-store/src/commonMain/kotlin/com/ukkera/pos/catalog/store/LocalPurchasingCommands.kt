package com.ukkera.pos.catalog.store

import com.ukkera.pos.catalog.ProductUnitConversion
import com.ukkera.pos.catalog.StockTracking
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** The initiating client durably saves this operation ID and payload before first submission. */
public object LocalPurchasingCommands {
    public fun approve(operationId: OperationId, request: LocalApprovePurchaseOrderV1): MutationRequest =
        MutationRequest(operationId, LocalPurchasingProfile.APPROVE_ORDER, LocalPurchasingCodec.approve(request))
    public fun invoice(operationId: OperationId, request: LocalRecordSupplierInvoiceV1): MutationRequest =
        MutationRequest(operationId, LocalPurchasingProfile.RECORD_INVOICE, LocalPurchasingCodec.invoice(request))
    public fun captureInvoice(operationId: OperationId, request: LocalCaptureSupplierInvoiceV2): MutationRequest =
        MutationRequest(operationId, LocalPurchasingProfile.CAPTURE_INVOICE, LocalPurchasingCodec.captureInvoice(request))
    public fun match(operationId: OperationId, request: LocalMatchSupplierInvoiceV1): MutationRequest =
        MutationRequest(operationId, LocalPurchasingProfile.MATCH_INVOICE, LocalPurchasingCodec.match(request))
    public fun matchUncontested(operationId: OperationId, request: LocalMatchUncontestedSupplierInvoiceV1): MutationRequest =
        MutationRequest(operationId, LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE, LocalPurchasingCodec.matchUncontested(request))
    public fun matchCapturedInvoice(operationId: OperationId, request: LocalMatchCapturedSupplierInvoiceV2): MutationRequest =
        MutationRequest(operationId, LocalPurchasingProfile.MATCH_CAPTURED_INVOICE, LocalPurchasingCodec.matchCapturedInvoice(request))
    public fun credit(operationId: OperationId, request: LocalRecordSupplierCreditV1): MutationRequest =
        MutationRequest(operationId, LocalPurchasingProfile.RECORD_CREDIT, LocalPurchasingCodec.credit(request))
}

/** Install additively in the real master; use the SAME stock repository as receiving/sales. */
public class LocalPurchasingServices(signatures: CompanyPublicationSignatureVerifier, stock: LocalStockRepository,
    nativePublications: LocalCompanyBusinessPublications? = null) {
    private val configuration: LocalConfigurationRepository = LocalConfigurationRepository(signatures, nativePublications)
    private val captureCatalog = PurchasingInvoiceCaptureCatalogAuthorityV2(signatures, nativePublications)
    public val commands: List<MasterCommandHandler> = listOf(
        PurchasingOrderHandler(configuration, stock), PurchasingInvoiceHandler(), PurchasingInvoiceCaptureHandlerV2(captureCatalog), PurchasingMatchHandler(stock),
        PurchasingMatchHandler(stock, LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE), PurchasingCapturedMatchHandlerV2(stock), PurchasingCreditHandler(stock))
    public val reads: List<MasterReadHandler> = listOf(PurchasingReadHandler(stock), PurchasingInvoiceReadV2(stock), PurchasingMatchSourceRead(stock),
        PurchasingMatchSourceRead(stock, LocalPurchasingProfile.READ_UNCONTESTED_MATCH_SOURCE), PurchasingCreditSourceRead(stock),
        PurchasingReceiveSourceRead(configuration, stock), PurchasingCapturedMatchSourceReadV2(stock))
}

private abstract class PurchasingHandler(final override val key: CommandKey) : MasterCommandHandler {
    override val writableKinds: Set<String> get() = when (key) {
        LocalPurchasingProfile.APPROVE_ORDER -> setOf(LocalPurchasingProfile.ORDER, LocalPurchasingProfile.ORDER_BILLED, LocalPurchasingProfile.ORDER_MATCHES, LocalConfigurationProfile.BASIS)
        LocalPurchasingProfile.RECORD_INVOICE -> setOf(LocalPurchasingProfile.INVOICE, LocalPurchasingProfile.PROGRESS)
        else -> setOf(LocalPurchasingProfile.MATCH, LocalPurchasingProfile.PROGRESS, LocalPurchasingProfile.ORDER_BILLED, LocalPurchasingProfile.ORDER_MATCHES,
            LocalPurchasingProfile.RECEIPT_BILLED, LocalPurchasingProfile.PAYABLE)
    }
    override val claimNamespaces: Set<String> get() = when (key) {
        LocalPurchasingProfile.APPROVE_ORDER -> setOf(LocalPurchasingProfile.DOCUMENT_CLAIM, LocalPurchasingProfile.REFERENCE_CLAIM, LocalConfigurationProfile.SOURCE_CLAIM)
        LocalPurchasingProfile.RECORD_INVOICE -> setOf(LocalPurchasingProfile.DOCUMENT_CLAIM, LocalPurchasingProfile.REFERENCE_CLAIM)
        else -> setOf(LocalPurchasingProfile.DOCUMENT_CLAIM)
    }
    override fun normalize(input: StoredBytes): StoredBytes = try {
        purchasingNormalize(key, input)
    } catch (_: ArithmeticException) {
        // This boundary sees only external input. Retained producer normalization stays strict.
        throw IllegalArgumentException("Unsupported purchasing input precision or range")
    }
    final override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key && semanticDigest(view.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        return preparePurchasing(normalized, view, context)
    }
    protected abstract suspend fun preparePurchasing(input: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation
}

internal class PurchaseRejected(val code: String) : RuntimeException()
internal fun purchaseRequire(condition: Boolean, code: String) { if (!condition) throw PurchaseRejected(code) }

internal val purchasingRecoveryBarriers: Set<RecoveryResourceKind> = setOf(RecoveryResourceKind.PO_ALLOWANCE,
    RecoveryResourceKind.SUPPLIER_SETTLEMENT, RecoveryResourceKind.PURCHASE_CUSTODY, RecoveryResourceKind.STOCK, RecoveryResourceKind.AUTHORIZATION)

/** The unchanged live full-document requirement is also compared to native history. */
internal fun purchasingRequirement(
    scope: BranchScope, id: DomainId, revision: Revision, operationId: OperationId,
    digest: PayloadDigest, reason: String, amount: Money,
): AuthorizationRequirement = AuthorizationRequirement(Permission(SecurityAction.PURCHASING_APPROVE, PermissionVerb.PERFORM),
    scope, ActionBinding(id, revision, digest, operationId, reason), LimitUse.Monetary(amount))

internal fun preparedPurchasing(
    context: CommandContext, id: DomainId, revision: Revision, amount: Money, reason: String,
    resources: Set<RecoveryResourceKey>, admissionRestriction: PreparedMutationAdmissionRestriction? = null,
    block: (AuthorizedAction, UtcInstant) -> ProposedOutcome,
): PreparedMutation = object : PreparedMutation() {
    override val requirement: AuthorizationRequirement = purchasingRequirement(context.scope, id, revision,
        context.operationId, context.semanticDigest, reason, amount)
    override val allowsActionApproval: Boolean = false
    override val admissionRestriction: PreparedMutationAdmissionRestriction? = admissionRestriction
    override val affectedResources: Set<RecoveryResourceKey> = resources + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId)
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = purchasingRecoveryBarriers
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.originalActorId) && authorization.approvalId == null)
        check(admissionRestriction == null)
        return try { block(authorization, recordedAt) }
        catch (rejected: PurchaseRejected) { ProposedOutcome.Reject(rejected.code, purchasingResult(rejected.code, id)) }
        // Storage/parser/provenance/overflow failures are uncertainty, never a durable rejection or fabricated zero.
    }
}

/** A capacity-only refusal still needs the actual full-document monetary approval. */
internal suspend fun boundedPurchasingPreparation(
    context: CommandContext, id: DomainId, revision: Revision, amount: Money, reason: String,
    refusalResources: Set<RecoveryResourceKey>, prepare: suspend () -> PreparedMutation,
): PreparedMutation = try {
    prepare()
} catch (_: PurchasingHydrationCapacityUnavailable) {
    preparedPurchasing(context, id, revision, amount, reason, refusalResources) { _, _ ->
        ProposedOutcome.Reject("purchasing.capacity_unavailable", purchasingResult("purchasing.capacity_unavailable", id))
    }
}

/**
 * Only the claim-checked invoice/progress/payable ALL-ABSENT path uses this diagnostic.
 * The caller's expected revision is a selection token, NOT an observed invoice revision.
 * No monetary value is fabricated; this read-authorized path can never propose business effects.
 */
private fun missingPurchasingInvoice(context: CommandContext, request: LocalMatchSupplierInvoiceV1): PreparedMutation = object : PreparedMutation() {
    override val requirement: AuthorizationRequirement = AuthorizationRequirement(Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM),
        context.scope, ActionBinding(request.invoiceId, request.expectedInvoiceRevision, context.semanticDigest, context.operationId, request.reasonCode))
    override val allowsActionApproval: Boolean = false
    override val affectedResources: Set<RecoveryResourceKey> = setOf(
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId),
        RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, request.invoiceId),
        RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, request.matchId),
        RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.receiptId),
        RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.receiptLineId))
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = purchasingRecoveryBarriers
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.originalActorId) && authorization.approvalId == null)
        return ProposedOutcome.Reject("purchasing.invoice_required", purchasingResult("purchasing.invoice_required", request.invoiceId))
    }
}

internal fun purchasingResources(order: PurchaseOrderV1): Set<RecoveryResourceKey> = buildSet {
    add(RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, order.id))
    add(RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, order.supplierId))
    for (line in order.lines) {
        add(RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, line.allocationId))
        listOf(line.goods.skuId, line.warehouseId).forEach { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it)) }
        listOfNotNull(line.goods.units.productId, line.goods.units.conversionId, line.goods.units.dimensionalEvidenceId, line.goods.tax.sourceId).forEach {
            add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it))
        }
    }
    order.charges.forEach { add(RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, it.id));
        add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it.sourceEvidenceId)) }
}

private suspend fun purchasingSupplier(view: CommandReadView, id: DomainId): Pair<StoredAggregate, LocalSupplierV1>? {
    val row = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, id)) ?: return null
    val supplier = (decodeRecord(view.scope, row).body as? ConfigurationBody.Supplier)?.value ?: error("Purchasing supplier source unavailable")
    check(supplier.id == id)
    return row to supplier
}

private class PurchasingOrderHandler(
    private val configuration: LocalConfigurationRepository, private val stock: LocalStockRepository,
) : PurchasingHandler(LocalPurchasingProfile.APPROVE_ORDER) {
    override suspend fun preparePurchasing(input: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val request = purchasingDecode(input, LocalApprovePurchaseOrderV1.serializer())
        val order = request.order
        return boundedPurchasingPreparation(context, order.id, Revision(0), order.total(), request.reasonCode, purchasingResources(order)) {
            val reader = PurchasingReader(view)
            val current = reader.row(LocalPurchasingProfile.ORDER, order.id)
            val currentMatches = reader.row(LocalPurchasingProfile.ORDER_MATCHES, order.id)
            val basis = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.BASIS, order.id))
            basis?.let { decodeRecord(view.scope, it) }
            val supplier = purchasingSupplier(view, order.supplierId)
            val items = order.lines.map { it.goods.skuId }.distinct().associateWith { configuration.item(view, it) }
            val warehouses = order.lines.map { it.warehouseId }.distinct().associateWith { configuration.warehouse(view, it) }
            val ref = purchasingReferenceClaim(context.scope, "order", order.supplierId, order.reference)
            val refOwner = view.claim(ref)
            if (refOwner != null) {
                val duplicate = reader.order(refOwner) ?: error("Purchasing reference lost its owner")
                val value = (duplicate.value.body as PurchasingBody.Order).value
                check(purchasingReferenceClaim(view.scope, "order", value.supplierId, value.reference) == ref)
            }
            var allocationUsed = false
            for (line in order.lines) {
                if (view.claim(ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, "allocation/${line.allocationId.value}")) != null ||
                    reader.row(LocalPurchasingProfile.ORDER_BILLED, line.allocationId) != null || stock.purchaseAllowanceFacts(view, line.allocationId) != null) allocationUsed = true
            }
            val stamps = (items.values.filterNotNull().flatMap { it.sources } + warehouses.values.filterNotNull().flatMap { it.sources })
                .groupBy { it.key }.map { (_, list) -> check(list.distinct().size == 1); list.first() }
            val sources = stamps.map { stamp ->
                val row = view.aggregate(stamp.key) ?: error("Purchasing current source unavailable")
                check(row.revision == stamp.storageRevision && row.digest == stamp.digest)
                row
            } + listOfNotNull(supplier?.first)
            val resources = purchasingResources(order) + sources.flatMap { row ->
                val value = decodeRecord(view.scope, row)
                listOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, row.key.id),
                    RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, value.authorizedBy))
            } + RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, purchasingReferenceResource(ref))
            preparedPurchasing(context, order.id, Revision(0), order.total(), request.reasonCode, resources.toSet()) { auth, now ->
                purchaseRequire(current == null && currentMatches == null && basis == null && !allocationUsed, "purchasing.identity_already_used")
                purchaseRequire(refOwner == null, "purchasing.duplicate_reference")
                purchaseRequire(supplier?.second?.active == true && supplier?.first?.revision == request.expectedSupplierRevision, "purchasing.supplier_required")
                purchaseRequire(order.isApprovalEvaluationCurrent(now), "purchasing.approval_not_current")
                for (line in order.lines) {
                    val goods = line.goods
                    val item = items[goods.skuId]
                    purchaseRequire(item != null && item.scope == view.scope && item.active && item.canPurchase && item.tracking != StockTracking.NONE &&
                        item.productId == goods.units.productId && item.baseUnitId == goods.units.baseUnitId && item.quantityScale == goods.units.baseScale,
                        "purchasing.current_item_required")
                    purchaseRequire(warehouses[line.warehouseId]?.let { it.scope == view.scope && it.active } == true, "purchasing.warehouse_required")
                    val conversion = goods.units.conversionId?.let { id -> requireNotNull(item).conversions.singleOrNull { it.id == id && it.revision == goods.units.conversionRevision } }
                    purchaseRequire(conversion == goods.units.conversion(), "purchasing.unit_snapshot_required")
                    item?.unitDefinitions?.let { units ->
                        purchaseRequire(units.singleOrNull { it.id == goods.quantity.unitId }
                            ?.let { goods.quantity.scale <= it.maximumScale } == true, "purchasing.unit_snapshot_required")
                    }
                    if (item?.tracking == StockTracking.SERIAL) goods.baseQuantity().requireWholeUnits()
                    purchaseRequire(goods.amountsAgree(), "purchasing.order_amount_variance")
                }
                // Keep the existing exact document source shape. The publication is
                // still a protected read/resource above and is joined through the
                // signed SKU original; it is not a duplicate document source field.
                val ordered = PurchasingBody.Order(order, normalizedPurchaseReference(order.reference),
                    sources.filter { it.key.kind != LocalConfigurationProfile.PUBLICATION }.map(PurchasingSource::from)
                    .sortedWith(compareBy<PurchasingSource> { it.kind }.thenBy { it.id.value }))
                val changes = mutableListOf(purchasingChange(context, LocalPurchasingProfile.ORDER, order.id, null, ordered, now, auth.policyRevision),
                    purchasingChange(context, LocalPurchasingProfile.ORDER_MATCHES, order.id, null, PurchasingBody.OrderMatches(order.id, emptyList()), now, auth.policyRevision),
                    AggregateChange(LocalConfigurationProfile.key(LocalConfigurationProfile.BASIS, order.id), null, LocalConfigurationProfile.schema(LocalConfigurationProfile.BASIS),
                        encodeValue(ConfigurationRecord(context.scope, LocalConfigurationProfile.BASIS, order.id, Revision(0),
                            ConfigurationBody.Basis(purchasingBasis(order)), auth.actorId, auth.policyRevision, now), ConfigurationRecord.serializer())))
                val claims = mutableListOf(purchasingAcquisition(LocalPurchasingProfile.ORDER, order.id), purchasingAcquisition(LocalPurchasingProfile.ORDER_MATCHES, order.id), ClaimChange.Acquire(ref, order.id))
                for (line in order.lines) {
                    changes += purchasingChange(context, LocalPurchasingProfile.ORDER_BILLED, line.allocationId, null,
                        PurchasingBody.OrderBilled(order.id, line.allocationId, line.goods.baseQuantity().copy(scaled = 0), purchaseZero(), purchaseZero(), emptyList()), now, auth.policyRevision)
                    claims += purchasingAcquisition(LocalPurchasingProfile.ORDER_BILLED, line.allocationId)
                    claims += ClaimChange.Acquire(ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, "allocation/${line.allocationId.value}"), order.id)
                }
                ProposedOutcome.Apply(purchasingResult("purchasing.order_approved", order.id, Revision(1)), changes, claims, mustCommitBefore = order.validUntil)
            }
        }
    }
}

internal fun PurchaseUnitsV1.conversion(): ProductUnitConversion? = conversionId?.let {
    ProductUnitConversion(it, requireNotNull(conversionRevision), productId, purchaseUnitId, baseUnitId, rate, dimensionalEvidenceId)
}

/** Evaluation only. Native final-decision qualification remains a separate requirement. */
internal fun PurchaseOrderV1.isApprovalEvaluationCurrent(at: UtcInstant): Boolean =
    at >= validFrom && (validUntil?.let { at < it } != false)

private class PurchasingInvoiceHandler : PurchasingHandler(LocalPurchasingProfile.RECORD_INVOICE) {
    override suspend fun preparePurchasing(input: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val request = purchasingDecode(input, LocalRecordSupplierInvoiceV1.serializer())
        val invoice = request.invoice
        return boundedPurchasingPreparation(context, invoice.id, Revision(0), invoice.total(), request.reasonCode, setOf(
            RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, invoice.orderId),
            RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, invoice.id),
            RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, invoice.supplierId),
            RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, invoice.sourceEvidenceId))) {
            val reader = PurchasingReader(view)
            val current = reader.invoiceDocument(invoice.id)
            val order = reader.order(invoice.orderId)
            val supplier = purchasingSupplier(view, invoice.supplierId)
            val ref = purchasingReferenceClaim(view.scope, "invoice", invoice.supplierId, invoice.reference)
            val refOwner = view.claim(ref)
            if (refOwner != null) {
                reader.requireInvoiceReferenceOwner(refOwner, ref)
            }
            val original = (order?.value?.body as? PurchasingBody.Order)?.value
            val resources = original?.let(::purchasingResources).orEmpty() + setOf(
                RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, invoice.orderId),
                RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, invoice.id),
                RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, invoice.supplierId),
                RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, purchasingReferenceResource(ref)),
                RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, invoice.sourceEvidenceId)) +
                listOfNotNull(order?.value?.producer?.actorId, supplier?.first?.let { decodeRecord(view.scope, it).authorizedBy }).map {
                    RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it)
                }
            preparedPurchasing(context, invoice.id, Revision(0), invoice.total(), request.reasonCode, resources) { auth, now ->
                purchaseRequire(current == null, "purchasing.identity_already_used")
                purchaseRequire(refOwner == null, "purchasing.duplicate_reference")
                purchaseRequire(order?.row?.revision == request.expectedOrderRevision && original?.supplierId == invoice.supplierId, "purchasing.order_required")
                purchaseRequire(supplier?.second?.active == true && supplier?.first?.revision == request.expectedSupplierRevision, "purchasing.supplier_required")
                val body = PurchasingBody.Invoice(invoice, normalizedPurchaseReference(invoice.reference), PurchasingSource.from(requireNotNull(order).row),
                    PurchasingSource.from(requireNotNull(supplier).first), purchaseDiscrepancies(requireNotNull(original), invoice).sortedBy { it.name })
                ProposedOutcome.Apply(purchasingResult("purchasing.invoice_recorded", invoice.id, Revision(1), Revision(1)),
                    listOf(purchasingChange(context, LocalPurchasingProfile.INVOICE, invoice.id, null, body, now, auth.policyRevision),
                        purchasingChange(context, LocalPurchasingProfile.PROGRESS, invoice.id, null, purchasingInitialProgress(invoice), now, auth.policyRevision)),
                    listOf(purchasingAcquisition(LocalPurchasingProfile.INVOICE, invoice.id), purchasingAcquisition(LocalPurchasingProfile.PROGRESS, invoice.id),
                        ClaimChange.Acquire(ref, invoice.id)))
            }
        }
    }
}

private class PurchasingMatchHandler(
    private val stock: LocalStockRepository, command: CommandKey = LocalPurchasingProfile.MATCH_INVOICE,
) : PurchasingHandler(command) {
    init { require(command == LocalPurchasingProfile.MATCH_INVOICE || command == LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE) }
    override suspend fun preparePurchasing(input: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val reviewed = if (key == LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE) LocalPurchasingCodec.decodeUncontestedMatch(input) else null
        val request = reviewed?.match ?: purchasingDecode(input, LocalMatchSupplierInvoiceV1.serializer())
        val reader = PurchasingReader(view)
        // A bounded immutable header establishes a real full-document amount before deep hydration.
        val selected = reader.row(LocalPurchasingProfile.INVOICE, request.invoiceId)
        if (selected == null) {
            check(reader.invoice(request.invoiceId) == null) // Also checks progress/payable rows and claims.
            val previous = reader.row(LocalPurchasingProfile.MATCH, request.matchId)?.value?.body as? PurchasingBody.Match
            check(previous == null || previous.request.invoiceId != request.invoiceId) { "Purchasing match lost its invoice" }
            return missingPurchasingInvoice(context, request)
        }
        if (selected.value.body is PurchasingBody.InvoiceCapture) {
            return unqualifiedCaptureFinancialDeferral(context, reader, selected, request.expectedProgressRevision, request.reasonCode, setOf(
                RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, request.matchId),
                RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.receiptId),
                RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.receiptLineId)))
        }
        val selectedInvoice = (selected.value.body as PurchasingBody.Invoice).value
        return boundedPurchasingPreparation(context, selectedInvoice.id, request.expectedProgressRevision,
            selectedInvoice.total(), request.reasonCode, setOf(
                RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, selectedInvoice.orderId),
                RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, selectedInvoice.id),
                RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, selectedInvoice.supplierId),
                RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, request.matchId),
                RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.receiptId),
                RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.receiptLineId),
                RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, selectedInvoice.sourceEvidenceId),
                RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, selected.value.producer.actorId))) {
            val cut = reader.invoice(request.invoiceId) ?: error("Purchasing invoice unavailable")
            val invoice = (cut.invoice.value.body as PurchasingBody.Invoice).value
            val order = (cut.order.value.body as PurchasingBody.Order).value
            val progress = cut.progress.value.body as PurchasingBody.Progress
            val orderMatchRow = reader.orderMatches(order.id)
            val orderMatches = orderMatchRow.value.body as PurchasingBody.OrderMatches
            val line = invoice.lines.singleOrNull { it.lineId == request.invoiceLineId }
            val ordered = line?.let { l -> order.lines.singleOrNull { it.allocationId == l.allocationId } }
            val oldMatch = reader.row(LocalPurchasingProfile.MATCH, request.matchId)
            val orderBill = ordered?.let { reader.orderBilling(order, it.allocationId) }
            val facts = stock.purchaseReceiptFacts(view, request.receiptId, request.receiptLineId)
            val receiptBill = facts?.let { reader.receiptBilling(it.sourceId) }
            requirePurchasingCrossAccounts(reader, cut, stock, view)
            val resources = purchasingResources(order) + facts?.recoveryResources.orEmpty() + setOf(
                RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, invoice.id),
                RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, request.matchId),
                RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.receiptId),
                RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.receiptLineId),
                RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, invoice.sourceEvidenceId),
                RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, cut.order.value.producer.actorId),
                RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, cut.invoice.value.producer.actorId)) + reader.supplierRecoveryResources
            preparedPurchasing(context, invoice.id, request.expectedProgressRevision, invoice.total(), request.reasonCode, resources) { auth, now ->
                purchaseRequire(oldMatch == null, "purchasing.identity_already_used")
                purchaseRequire(cut.invoice.row.revision == request.expectedInvoiceRevision && cut.progress.row.revision == request.expectedProgressRevision &&
                    orderMatchRow.row.revision == request.expectedOrderMatchRevision &&
                    cut.payable?.row?.revision == request.expectedPayableRevision && orderBill?.row?.revision == request.expectedOrderBillingRevision &&
                    receiptBill?.row?.revision == request.expectedReceiptBillingRevision && facts?.receiptRevision == request.expectedReceiptRevision, "purchasing.stale_revision")
                val original = ordered ?: throw PurchaseRejected("purchasing.order_line_required")
                val billedLine = line ?: throw PurchaseRejected("purchasing.invoice_line_required")
                purchaseRequire(if (reviewed == null) purchaseLineDiscrepancies(original.goods, billedLine.goods).isEmpty()
                    else purchaseUncontestedPriceIncrease(original.goods, billedLine.goods), "purchasing.variance_requires_approval")
                val receipt = facts ?: throw PurchaseRejected("purchasing.receipt_required")
                purchaseRequire(receipt.scope == view.scope && receipt.basisId == order.id && receipt.basisRevision == Revision(0) &&
                    receipt.basisKind == LocalStockBasisKind.LOCAL_PURCHASE && receipt.supplierId == invoice.supplierId &&
                    receipt.allocationId == original.allocationId && receipt.skuId == original.goods.skuId && receipt.warehouseId == original.warehouseId &&
                    purchasingReceiptUnitsAgree(original, receipt) && purchasingReceiptOrderSourceAgrees(cut.order, receipt.poValuation),
                    "purchasing.receipt_source_mismatch")
                purchaseRequire(receipt.disposition in setOf(LocalReceiptDisposition.RELEASED, LocalReceiptDisposition.QUARANTINE) &&
                    receipt.originalOwnedValue != null && receipt.acceptedQuantity.scaled > 0 && receipt.allowance != null, "purchasing.accepted_receipt_required")
                val beforeInvoice = progress.lines.single { it.lineId == billedLine.lineId }
                val beforeOrder = requireNotNull(orderBill).value.body as PurchasingBody.OrderBilled
                val beforeReceipt = receiptBill?.value?.body as? PurchasingBody.ReceiptBilled
                if (beforeReceipt != null) check(beforeReceipt.orderId == order.id && beforeReceipt.allocationId == original.allocationId &&
                    beforeReceipt.receiptId == receipt.receiptId && beforeReceipt.lineId == receipt.lineId)
                val receiptQuantity = beforeReceipt?.quantity ?: receipt.baseQuantity.copy(scaled = 0)
                purchaseRequire(orderMatches.matchIds.size < LocalPurchasingProfile.MAX_MATCHES && progress.matchIds.size < LocalPurchasingProfile.MAX_MATCHES && beforeOrder.matchIds.size < LocalPurchasingProfile.MAX_MATCHES &&
                    (beforeReceipt?.matchIds?.size ?: 0) < LocalPurchasingProfile.MAX_MATCHES, "purchasing.capacity_required")
                purchaseRequire(request.quantity.unitId == receipt.baseQuantity.unitId && request.quantity.scale == receipt.baseQuantity.scale,
                    "purchasing.quantity_unit_mismatch")
                // Compare REMAINING capacity first; unchecked addition cannot convert exhaustion to overflow.
                purchaseRequire(request.quantity <= billedLine.goods.baseQuantity() - beforeInvoice.quantity &&
                    request.quantity <= original.goods.baseQuantity() - beforeOrder.quantity &&
                    request.quantity <= receipt.acceptedQuantity - receiptQuantity &&
                    request.quantity <= requireNotNull(receipt.allowance).acceptedQuantity - beforeOrder.quantity,
                    "purchasing.remaining_quantity_exceeded")
                purchaseRequire(receipt.serialId == null || request.quantity == receipt.baseQuantity, "purchasing.whole_serial_required")
                purchaseRequire(purchasingReceiptValueAgrees(order, original, receipt), "purchasing.receipt_cost_variance")
                val after = beforeInvoice.quantity + request.quantity
                val uncontested = if (reviewed == null) null else {
                    purchaseUncontestedAmounts(original.goods, billedLine.goods, beforeOrder.quantity, beforeInvoice.quantity, request.quantity)
                        ?: throw PurchaseRejected("purchasing.rounding_variance")
                }
                if (reviewed != null) purchaseRequire(uncontested == reviewed.amounts, "purchasing.uncontested_amounts_changed")
                val net = uncontested?.net ?: (purchaseCumulativeAmount(billedLine.goods.net, after, billedLine.goods.baseQuantity()) - beforeInvoice.net)
                val tax = uncontested?.tax ?: (purchaseCumulativeAmount(billedLine.goods.taxAmount, after, billedLine.goods.baseQuantity()) - beforeInvoice.tax)
                purchaseRequire(net == purchaseCumulativeAmount(original.goods.net, beforeOrder.quantity + request.quantity, original.goods.baseQuantity()) - beforeOrder.net &&
                    tax == purchaseCumulativeAmount(original.goods.taxAmount, beforeOrder.quantity + request.quantity, original.goods.baseQuantity()) - beforeOrder.tax,
                    "purchasing.rounding_variance")
                reader.requireSupplierHydrationCapacity()
                val event = PurchasingBody.Match(request, order.id, original.allocationId, PurchasingReceiptSnapshot.from(receipt),
                    beforeInvoice.quantity, beforeOrder.quantity, receiptQuantity, net, tax, uncontested)
                requirePurchasingMatch(order, invoice, event)
                val nextProgress = progress.copy(lines = progress.lines.map { if (it.lineId == billedLine.lineId)
                    it.copy(quantity = after, net = it.net + net, tax = it.tax + tax) else it }, matchIds = progress.matchIds + request.matchId)
                val nextOrder = beforeOrder.copy(quantity = beforeOrder.quantity + request.quantity, net = beforeOrder.net + net,
                    tax = beforeOrder.tax + tax, matchIds = beforeOrder.matchIds + request.matchId)
                val nextReceipt = PurchasingBody.ReceiptBilled(receipt.receiptId, receipt.lineId, receipt.sourceId, order.id, original.allocationId,
                    receiptQuantity + request.quantity, (beforeReceipt?.net ?: purchaseZero()) + net, (beforeReceipt?.tax ?: purchaseZero()) + tax,
                    beforeReceipt?.matchIds.orEmpty() + request.matchId)
                val nextPayable = PurchasingBody.Payable(invoice.id, order.id, invoice.supplierId, invoice.dueDate, PurchasingPayableStatus.UNSETTLED,
                    nextProgress.lines.fold(purchaseZero()) { sum, it -> sum + it.net }, nextProgress.lines.fold(purchaseZero()) { sum, it -> sum + it.tax }, nextProgress.matchIds)
                val changes = listOf(
                    purchasingChange(context, LocalPurchasingProfile.MATCH, request.matchId, null, event, now, auth.policyRevision),
                    purchasingChange(context, LocalPurchasingProfile.ORDER_MATCHES, order.id, orderMatchRow,
                        orderMatches.copy(matchIds = orderMatches.matchIds + request.matchId), now, auth.policyRevision),
                    purchasingChange(context, LocalPurchasingProfile.PROGRESS, invoice.id, cut.progress, nextProgress, now, auth.policyRevision),
                    purchasingChange(context, LocalPurchasingProfile.ORDER_BILLED, original.allocationId, orderBill, nextOrder, now, auth.policyRevision),
                    purchasingChange(context, LocalPurchasingProfile.RECEIPT_BILLED, receipt.sourceId, receiptBill, nextReceipt, now, auth.policyRevision),
                    purchasingChange(context, LocalPurchasingProfile.PAYABLE, invoice.id, cut.payable, nextPayable, now, auth.policyRevision))
                val claims = listOf(purchasingAcquisition(LocalPurchasingProfile.MATCH, request.matchId)) +
                    (if (receiptBill == null) listOf(purchasingAcquisition(LocalPurchasingProfile.RECEIPT_BILLED, receipt.sourceId)) else emptyList()) +
                    (if (cut.payable == null) listOf(purchasingAcquisition(LocalPurchasingProfile.PAYABLE, invoice.id)) else emptyList())
                ProposedOutcome.Apply(purchasingResult(if (reviewed == null) "purchasing.invoice_matched" else "purchasing.invoice_uncontested_matched",
                    request.matchId, Revision(1), cut.progress.row.revision.next()), changes, claims)
            }
        }
    }
}

internal suspend fun requirePurchasingCrossAccounts(
    reader: PurchasingReader, cut: PurchasingInvoiceCut, stock: LocalStockRepository, view: CommandReadView,
) = requirePurchasingCrossAccountFacts(reader, cut, view.scope) { receiptId, lineId ->
    stock.purchaseReceiptFacts(view, receiptId, lineId)
}

/** Same complete cross-account conservation, observed at the genuine native BEFORE cut. */
internal suspend fun requirePurchasingCrossAccounts(
    reader: PurchasingReader, cut: PurchasingInvoiceCut, stock: LocalStockRepository, command: RuntimeHistoricalCommand,
) = requirePurchasingCrossAccountFacts(reader, cut, command.scope) { receiptId, lineId ->
    stock.purchaseReceiptFacts(command, receiptId, lineId)
}

private suspend fun requirePurchasingCrossAccountFacts(
    reader: PurchasingReader, cut: PurchasingInvoiceCut, scope: BranchScope,
    receiptFacts: suspend (DomainId, DomainId) -> LocalPurchaseReceiptFacts?,
) {
    val order = (cut.order.value.body as PurchasingBody.Order).value
    for (event in cut.matches) {
        val allocation = reader.orderBilling(order, event.allocationId).value.body as PurchasingBody.OrderBilled
        val receipt = reader.receiptBilling(event.receipt.sourceId)?.value?.body as? PurchasingBody.ReceiptBilled
            ?: error("Purchasing receipt billing unavailable")
        check(event.request.matchId in allocation.matchIds && event.request.matchId in receipt.matchIds)
        val actual = receiptFacts(event.receipt.receiptId, event.receipt.lineId)
            ?: error("Purchasing original stock receipt unavailable")
        val original = order.lines.single { it.allocationId == event.allocationId }
        check(actual.scope == scope && actual.sourceId == event.receipt.sourceId && actual.supplierId == order.supplierId &&
            actual.basisId == order.id && actual.basisRevision == Revision(0) && actual.basisKind == LocalStockBasisKind.LOCAL_PURCHASE &&
            actual.allocationId == event.allocationId && actual.skuId == original.goods.skuId && actual.warehouseId == original.warehouseId &&
            actual.baseQuantity == event.receipt.originalQuantity && actual.originalOwnedValue == event.receipt.originalOwnedValue &&
            actual.lotId == event.receipt.lotId && actual.serialId == event.receipt.serialId && actual.poValuation == event.receipt.poValuation &&
            purchasingReceiptUnitsAgree(original, actual) && purchasingReceiptValueAgrees(order, original, actual) &&
            purchasingReceiptOrderSourceAgrees(cut.order, actual.poValuation) &&
            actual.receiptRevision >= event.receipt.source.revision && actual.acceptedQuantity >= event.receipt.acceptedQuantity &&
            actual.acceptedQuantity >= receipt.quantity && requireNotNull(actual.allowance).acceptedQuantity >= allocation.quantity)
        if (actual.receiptRevision == event.receipt.source.revision) check(PurchasingSource.from(actual.source) == event.receipt.source)
    }
    reader.observeSupplierOrderDependencies(order.id)
}
