package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.pricing.PriceComponentKind
import com.ukkera.pos.sales.AttemptStatus
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/**
 * Uses actual current Room aggregate/claim/companion readers with explicit financial-claim
 * consistency. These checks do not prove complete retained history or freshness of a restore. There
 * is no mutable report cache, source seeding, fabricated zero document, or repricing. Exactly
 * one bounded key page is hydrated in this borrowed transaction. Do not add page totals and
 * advertise them as a branch total: a later request can observe a different branch revision.
 *
 * Retail and FOOD use distinct durable source sections, not a mixed-kind UUID cursor. A
 * cash-session opening date is NOT a sale posting date. Period reports require an actual
 * durable posting-calendar/index profile; this reader does not invent one from today's clock.
 */
public class LocalBranchReportReadHandler(
    private val stock: LocalStockRepository? = null,
    private val foodRecipes: TransactionFoodRecipeSourceV1? = null,
) : MasterReadHandler {
    override val key: ReadKey = LocalBranchReportReadCodec.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = LocalBranchReportReadCodec.encode(
        localSalesDecode(input, LocalBranchReportQueryV1.serializer(), 2_048))

    // Classification only: prepare/project still require the current actual VIEW/EXPORT grants.
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.REPORTS

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key)
        val query = localSalesDecode(normalized, LocalBranchReportQueryV1.serializer(), 2_048)
        val food = query.section in setOf(LocalBranchReportSectionV1.FOOD_COLLECTIONS,
            LocalBranchReportSectionV1.FOOD_PAID_SALES, LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS)
        // Missing installed FOOD provenance is unavailable even for an empty key page.
        // The legacy no-argument reader never becomes an accepting FOOD/stock substitute.
        if (food) check(stock != null && foodRecipes != null) { "report.food.sources_not_installed" }
        if (query.section in setOf(LocalBranchReportSectionV1.PAYMENT_CORRECTIONS, LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS))
            check(stock != null) { "report.payment_correction.sources_not_installed" }
        val loan = stock?.let { commerceLoan(view, it) } ?: view
        val sources = if (food) foodReadView(loan) else loan
        val kind = when (query.section) {
            LocalBranchReportSectionV1.CASH_SESSIONS -> CashNonSaleStoreProfile.SESSION_KIND
            LocalBranchReportSectionV1.COLLECTIONS -> LocalSalesStoreProfile.COLLECTION_KIND
            LocalBranchReportSectionV1.PAID_SALES, LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS -> LocalSalesStoreProfile.SALE_KIND
            LocalBranchReportSectionV1.ACCEPTED_RETURNS -> LocalSalesStoreProfile.RETURN_KIND
            LocalBranchReportSectionV1.SAFE_TRANSFERS -> LocalSalesStoreProfile.TRANSFER_KIND
            LocalBranchReportSectionV1.FOOD_COLLECTIONS -> LocalFoodFinancialProfileV1.COLLECTION
            LocalBranchReportSectionV1.FOOD_PAID_SALES -> LocalFoodFinancialProfileV1.SALE
            LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS -> LocalFoodFinancialProfileV1.REFUND
            LocalBranchReportSectionV1.PAYMENT_CORRECTIONS -> LocalPaymentCorrectionProfileV1.RECORD
        }
        val rows: List<LocalBranchReportRowV1>
        val nextAfter: DomainId?
        if (query.section == LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS) {
            rows = listOf(retailTenderBreakdownReportRow(sources, checkNotNull(query.selectedSaleId)))
            nextAfter = null
        } else {
            val page = sources.aggregateKeys(kind, query.after, query.limit)
            check(page.keys.size <= query.limit && page.keys.all { it.kind == kind } &&
                page.keys.map { it.id.value } == page.keys.map { it.id.value }.distinct().sorted() &&
                page.keys.all { query.after == null || it.id.value > query.after.value } &&
                (page.nextAfter == null || page.nextAfter == page.keys.lastOrNull()?.id))
            rows = page.keys.map {
                if (query.profile == LocalBranchReportProjectionV1.FOOD_REFUND_PAYOUT_V2) foodRefundReportRow(sources, it.id)
                else reportRow(sources, query.section, it.id, foodRecipes)
            }
            nextAfter = page.nextAfter
        }
        val binding = ActionBinding(view.scope.branchId, Revision(0), context.semanticDigest, null, "report.current_documents")
        val required = listOf(AuthorizationRequirement(Permission(SecurityAction.BRANCH_REPORT, PermissionVerb.PERFORM),
            context.scope, binding)) + if (query.delivery == LocalBranchReportDeliveryV1.EXPORT)
            listOf(AuthorizationRequirement(Permission(SecurityAction.BRANCH_REPORT, PermissionVerb.EXPORT), context.scope, binding))
        else emptyList()
        val result = LocalBranchReportResultV1.Page(query.section, query.delivery, query.after, rows, nextAfter, query.profile, query.selectedSaleId)
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = required
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == required.size && authorizations.zip(required).all { (proof, requirement) ->
                    proof.principalKind == PrincipalKind.LOCAL_EMPLOYEE && proof.isBoundTo(requirement, context.actorId)
                })
                if (query.section == LocalBranchReportSectionV1.PAYMENT_CORRECTIONS)
                    requireLocalSalesSource(rows.all { validPaymentCorrectionReportRow(it, observedAt) })
                if (query.section == LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS)
                    requireLocalSalesSource(rows.all { validRetailTenderBreakdownReportRow(it, observedAt) })
                return localSalesEncode(result, LocalBranchReportResultV1.serializer())
            }
        }
    }
}

private suspend fun reportRow(view: CommandReadView, section: LocalBranchReportSectionV1, id: DomainId,
    foodRecipes: TransactionFoodRecipeSourceV1?): LocalBranchReportRowV1 =
    when (section) {
        LocalBranchReportSectionV1.CASH_SESSIONS -> {
            val value = LocalSalesStorage.cash(view, id).session.value
            val data = cashView(value)
            LocalBranchReportRowV1(id, value.revision, relatedDocumentId = value.drawerId, cashSessionId = id,
                status = value.status.name, businessDate = data.businessDate,
                pendingCount = data.pendingAttemptIds.size + data.pendingTransferIds.size,
                amounts = listOfNotNull(
                    amount(LocalBranchReportMetricV1.OPENING_FLOAT, data.opening),
                    amount(LocalBranchReportMetricV1.CASH_RECEIVED, data.received),
                    amount(LocalBranchReportMetricV1.CHANGE_GIVEN, data.change),
                    amount(LocalBranchReportMetricV1.CASH_REFUNDS, data.refunds),
                    amount(LocalBranchReportMetricV1.PAY_INS, data.payIns),
                    amount(LocalBranchReportMetricV1.PAY_OUTS, data.payOuts),
                    amount(LocalBranchReportMetricV1.SAFE_DROPS, data.safeDrops),
                    amount(LocalBranchReportMetricV1.EXPECTED_CASH, data.expectedCash),
                    amount(LocalBranchReportMetricV1.RESERVED_CASH_OUT, data.reservedCashOut),
                    amount(LocalBranchReportMetricV1.EXTERNAL_COLLECTIONS, data.externalCollections),
                    amount(LocalBranchReportMetricV1.EXTERNAL_REFUNDS, data.externalRefunds),
                    amount(LocalBranchReportMetricV1.CLOSING_EXPECTED, data.closingExpected),
                    amount(LocalBranchReportMetricV1.ACTUAL_COUNT, data.counted),
                    amount(LocalBranchReportMetricV1.VARIANCE, data.variance),
                    amount(LocalBranchReportMetricV1.RETAINED_FLOAT, data.retainedFloat),
                    amount(LocalBranchReportMetricV1.ACTUAL_SAFE_DELIVERY, data.actualSafeDelivery)))
        }
        LocalBranchReportSectionV1.PAYMENT_CORRECTIONS -> paymentCorrectionReportRow(view, id)
        LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS -> error("report.retail_tenders.selection_required")
        LocalBranchReportSectionV1.COLLECTIONS -> {
            val value = readCollection(view, id).collection.value
            // Also reject a retained record claim when this current attempt has no payment.
            // No claim absence is converted into an invented zero or a guessed cash movement.
            for (attempt in value.attempts) {
                requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("payment_record", attempt.id)) ==
                    if (attempt.payment == null) null else value.id)
            }
            val currency = value.quote.currency
            val payments = value.attempts.mapNotNull { it.payment }
            LocalBranchReportRowV1(id, value.revision, value.orderId, value.cashSessionId, value.status.name,
                pendingCount = value.attempts.count { it.status in setOf(AttemptStatus.AWAITING_ATTESTATION, AttemptStatus.OUTCOME_UNKNOWN) },
                amounts = listOfNotNull(
                    amount(LocalBranchReportMetricV1.LOCKED_PAYABLE, value.quote.payable),
                    amount(LocalBranchReportMetricV1.ACTUAL_COLLECTIONS, value.collected),
                    amount(LocalBranchReportMetricV1.REMAINING_COLLECTION, value.remainingPayable),
                    amount(LocalBranchReportMetricV1.CASH_COLLECTIONS, payments.filter { it.tender == Tender.Cash }.map { it.amount }.sumMoney(currency)),
                    amount(LocalBranchReportMetricV1.EXTERNAL_COLLECTIONS, payments.filter { it.tender is Tender.External }.map { it.amount }.sumMoney(currency))))
        }
        LocalBranchReportSectionV1.PAID_SALES -> {
            // Keep the historical paid/entitlement reader independent of current order/work.
            // Its normalized price-source path already requires the actual permanent sale
            // and entitlement producers in this same installed commerce loan. A damaged
            // normalized entitlement remains unavailable; it is never treated as zero rights.
            val cut = readEntitlement(view, id)
            val sale = cut.sale.value
            LocalBranchReportRowV1(id, cut.sale.stored.domainRevision(), sale.orderId, sale.collectingSessionId, "PAID_DOCUMENT",
                amounts = listOfNotNull(
                    amount(LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL_BEFORE_DISCOUNTS, sale.quote.beforeDiscountPayable),
                    amount(LocalBranchReportMetricV1.ADVERTISED_DISCOUNT, sale.quote.advertisedDiscount),
                    amount(LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL, sale.quote.payable)) +
                    componentAmounts(sale.quote.currency, sale.quote.components.map { it.kind to it.posted }))
        }
        LocalBranchReportSectionV1.ACCEPTED_RETURNS -> {
            val saleId = view.claim(LocalSalesStoreProfile.unique("accepted_return", id)) ?: error("report.return.source")
            val claimId = view.claim(LocalSalesStoreProfile.unique("return_claim_source", id)) ?: error("report.return.source")
            val value = readAcceptedReturn(view, saleId, claimId, id).accepted.value
            // Accepted-return decoding validates intrinsic amounts/links, but the legacy and
            // CASE readers do not themselves read these per-attempt financial source claims.
            // The decoded document caps this loop at 64 attempts. Refuse the entire page on
            // a missing/wrong/orphan claim; never present a guessed refund or settled liability.
            for (attempt in value.attempts) {
                requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("refund_attempt", attempt.id)) == value.id)
                requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("refund_record", attempt.id)) ==
                    if (attempt.refund == null) null else value.id)
            }
            LocalBranchReportRowV1(id, value.revision, saleId, status = value.refundStatus.name,
                pendingCount = value.attempts.count { it.status in setOf(AttemptStatus.AWAITING_ATTESTATION, AttemptStatus.OUTCOME_UNKNOWN) },
                amounts = listOfNotNull(
                    amount(LocalBranchReportMetricV1.ACCEPTED_RETURN_AMOUNT, value.originalPayable),
                    amount(LocalBranchReportMetricV1.ACTUAL_REFUNDS, value.refunded),
                    amount(LocalBranchReportMetricV1.REFUND_DUE, value.due),
                    amount(LocalBranchReportMetricV1.CASH_REFUNDS, value.attempts.mapNotNull { it.refund }
                        .filter { it.tender == Tender.Cash }.map { it.amount }.sumMoney(value.originalPayable.currency)),
                    amount(LocalBranchReportMetricV1.EXTERNAL_REFUNDS, value.attempts.mapNotNull { it.refund }
                        .filter { it.tender is Tender.External }.map { it.amount }.sumMoney(value.originalPayable.currency))) +
                    componentAmounts(value.originalPayable.currency, value.components.map { it.kind to it.amount }))
        }
        LocalBranchReportSectionV1.SAFE_TRANSFERS -> {
            val value = LocalSalesStorage.transfer(view, id).value
            val session = LocalSalesStorage.cash(view, value.senderSessionId).session.value
            val safe = LocalSalesStorage.safe(view, value.receivingCustodyId).value
            SalesSnapshotLinks.requireSafeTransfer(value, session)
            check(safe.received.currency == value.amount.currency &&
                view.claim(ClaimKey(LocalSalesStoreProfile.SAFE_RECEIPT_NAMESPACE, id.value)) ==
                (if (value.receipt == null) null else safe.id))
            LocalBranchReportRowV1(id, value.revision, value.receivingCustodyId, value.senderSessionId, value.status.name,
                pendingCount = if (value.receipt == null) 1 else 0,
                amounts = listOfNotNull(amount(LocalBranchReportMetricV1.TRANSFER_AMOUNT, value.amount)))
        }
        LocalBranchReportSectionV1.FOOD_COLLECTIONS, LocalBranchReportSectionV1.FOOD_PAID_SALES,
        LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS ->
            foodReportRow(view, checkNotNull(foodRecipes), section, id)
    }

/** Actual original FOOD readers only. Nothing invokes today's recipe/pricing planner, a raw
 * private payment query, physical-money prompt, order command or refund-policy decision. */
private suspend fun foodReportRow(view: CommandReadView, recipes: TransactionFoodRecipeSourceV1,
    section: LocalBranchReportSectionV1, id: DomainId): LocalBranchReportRowV1 = when (section) {
    LocalBranchReportSectionV1.FOOD_COLLECTIONS -> {
        val cut = foodFinancialCollectionCut(view, id, recipes, includeClosed = true)
        val value = cut.value
        requireFoodReportPaymentClaims(view, value)
        value.saleId?.let { requireFoodReportPaid(view, value, it) }
        requireFoodReportCancellation(view, value)
        val payments = value.attempts.mapNotNull { it.payment }
        LocalBranchReportRowV1(id, value.revision, value.orderId, value.cashSessionId, value.status.name,
            pendingCount = value.attempts.count { it.status in setOf(AttemptStatus.AWAITING_ATTESTATION, AttemptStatus.OUTCOME_UNKNOWN) },
            amounts = listOfNotNull(
                amount(LocalBranchReportMetricV1.LOCKED_PAYABLE, value.quote.payable),
                amount(LocalBranchReportMetricV1.ACTUAL_COLLECTIONS, value.collected),
                amount(LocalBranchReportMetricV1.REMAINING_COLLECTION, value.remaining),
                amount(LocalBranchReportMetricV1.CASH_COLLECTIONS, payments.filter { it.tender == Tender.Cash }.map { it.amount }.sumMoney(Currency.EGP)),
                amount(LocalBranchReportMetricV1.EXTERNAL_COLLECTIONS, payments.filter { it.tender is Tender.External }.map { it.amount }.sumMoney(Currency.EGP))),
            storageRevision = cut.row.row.revision)
    }
    LocalBranchReportSectionV1.FOOD_PAID_SALES -> {
        val paid = requiredFoodReportSource(view, LocalFoodFinancialProfileV1.SALE, id)
        val sale = foodFinancialSale(paid.envelope)
        val original = foodFinancialCollectionCut(view, sale.collection.id, recipes, includeClosed = true)
        requireFoodReportPaymentClaims(view, original.value)
        requireFoodReportPaid(view, original.value, id)
        requireFoodReportCancellation(view, original.value)
        val batches = sale.quote.batches.map { it.quote }
        LocalBranchReportRowV1(id, Revision(paid.envelope.revision.value - 1), sale.orderId,
            sale.collection.cashSessionId, "PAID_DOCUMENT",
            amounts = listOfNotNull(
                amount(LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL_BEFORE_DISCOUNTS, batches.map { it.beforeDiscountPayable }.sumMoney(Currency.EGP)),
                amount(LocalBranchReportMetricV1.ADVERTISED_DISCOUNT, batches.map { it.advertisedDiscount }.sumMoney(Currency.EGP)),
                amount(LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL, sale.quote.payable)) +
                componentAmounts(Currency.EGP, batches.flatMap { it.components }.map { it.kind to it.posted }, includeAbsentKinds = false),
            storageRevision = paid.row.revision, finalizedAt = sale.finalizedAt)
    }
    LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS -> {
        val row = requiredFoodReportSource(view, LocalFoodFinancialProfileV1.REFUND, id)
        val value = foodFinancialRefund(row.envelope)
        val original = foodFinancialCollectionCut(view, value.originalCollection.id, recipes, includeClosed = true)
        requireFoodRefundSource(original.value, value)
        requireFoodReportPaymentClaims(view, original.value)
        requireFoodReportCancellation(view, original.value)
        value.saleId?.let { requireFoodReportPaid(view, original.value, it) }
        // Preserve the original absent-profile projection: it never represented payout
        // attempts. Current payout reads explicitly select FOOD_REFUND_PAYOUT_V2 instead;
        // do not silently reinterpret a retained query/export's original format.
        requireLocalSalesSource(value.attempts.isEmpty())
        LocalBranchReportRowV1(id, value.revision, relatedDocumentId = value.originalCollection.id,
            status = value.status.name, amounts = listOfNotNull(
                amount(LocalBranchReportMetricV1.LOCKED_PAYABLE, value.originalQuotedTotal),
                amount(LocalBranchReportMetricV1.CANCELLED_COLLECTION_REFUND_LIABILITY, value.amount),
                amount(LocalBranchReportMetricV1.REFUND_DUE, value.due)),
            storageRevision = row.row.revision, originalPaidDocumentId = value.saleId)
    }
    else -> error("report.food.wrong_source_section")
}

private suspend fun requiredFoodReportSource(view: CommandReadView, kind: String, id: DomainId): FoodFinancialRow =
    foodFinancialRead(view, kind, id) ?: error("report.food.original_source_missing")

private suspend fun requireFoodReportPaid(view: CommandReadView, collection: com.ukkera.pos.sales.FoodCollection, saleId: DomainId) {
    val paid = requiredFoodReportSource(view, LocalFoodFinancialProfileV1.SALE, saleId)
    val rights = requiredFoodReportSource(view, LocalFoodFinancialProfileV1.ENTITLEMENT, saleId)
    requireFoodPaidSource(collection, foodFinancialSale(paid.envelope), foodFinancialEntitlement(rights.envelope))
}

private suspend fun requireFoodReportPaymentClaims(view: CommandReadView, value: com.ukkera.pos.sales.FoodCollection) {
    for (attempt in value.attempts) {
        requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("payment_attempt", attempt.id)) == value.id)
        requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("payment_record", attempt.id)) ==
            if (attempt.payment == null) null else value.id)
    }
}

private suspend fun requireFoodReportCancellation(view: CommandReadView, collection: com.ukkera.pos.sales.FoodCollection) {
    // Read the actual index even when no cancellation is named. An orphan/mismatched
    // liability is not a clean empty link or a successful partial financial observation.
    val index = foodFinancialRead(view, LocalFoodFinancialProfileV1.REFUND_INDEX, collection.orderId)
    val ids = (index?.envelope?.body as? FoodFinancialBodyV1.RefundIndex)?.refundIds.orEmpty()
    requireLocalSalesSource(ids == listOfNotNull(collection.cancellationRefundId))
    for (id in ids) {
        val refund = foodFinancialRefund(requiredFoodReportSource(view, LocalFoodFinancialProfileV1.REFUND, id).envelope)
        requireFoodRefundSource(collection, refund)
    }
}

private fun amount(metric: LocalBranchReportMetricV1, value: Money?): LocalBranchReportAmountV1? =
    value?.let { LocalBranchReportAmountV1(metric, it) }

/** Per-document bounded validated partitions, never an unbounded cumulative report sum. */
private fun List<Money>.sumMoney(currency: Currency): Money = fold(Money(currency, 0)) { total, value -> total + value }

private fun componentAmounts(currency: Currency, components: List<Pair<PriceComponentKind, Money>>,
    includeAbsentKinds: Boolean = true): List<LocalBranchReportAmountV1> =
    PriceComponentKind.entries.filter { includeAbsentKinds || components.any { component -> component.first == it } }.map { kind ->
        val metric = when (kind) {
            PriceComponentKind.ITEM_NET -> LocalBranchReportMetricV1.ITEM_NET
            PriceComponentKind.ITEM_TAX -> LocalBranchReportMetricV1.ITEM_TAX
            PriceComponentKind.CHARGE_NET -> LocalBranchReportMetricV1.CHARGE_NET
            PriceComponentKind.CHARGE_TAX -> LocalBranchReportMetricV1.CHARGE_TAX
        }
        LocalBranchReportAmountV1(metric, components.filter { it.first == kind }.map { it.second }.sumMoney(currency))
    }
