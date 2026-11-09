package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.types.*

/** Original financial posting at the core-native BEFORE cut, with no recreated employee session. */
internal class LocalPurchasingCreditHistoryProducer(private val stock: LocalStockRepository) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalPurchasingProfile.RECORD_CREDIT)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.original !is RetainedOriginalV2.Legacy) return false
        requirePurchasingProducer((command.original as RetainedOriginalV2.Legacy).evidence)
        val request = LocalPurchasingCodec.decodeCredit(command.normalized)
        val reader = PurchasingReader(command)
        val cut = reader.invoice(request.invoiceId) ?: error("Original credit invoice unavailable")
        val invoice = purchasingInvoiceClaim(cut.invoice)
        val order = (cut.order.value.body as PurchasingBody.Order).value
        command.requireRequirement(purchasingRequirement(command.scope, invoice.id, request.expectedProgressRevision,
            command.operationId, command.semanticDigest, request.reasonCode, invoice.total()))
        requirePurchasingCrossAccounts(reader, cut, stock, command)
        check(request.kind == LocalSupplierAdjustmentKindV1.CREDIT && request.amount.currency == Currency.EGP &&
            request.amount.minor > 0 && request.agreed && request.supplierId == invoice.supplierId)
        check(reader.row(LocalPurchasingProfile.CREDIT, request.creditId) == null &&
            command.claim(purchasingReferenceClaim(command.scope, "credit", invoice.supplierId, request.reference)) == null &&
            command.claim(purchasingCreditSourceClaim(request.sourceEvidenceId)) == null)
        check(cut.invoice.row.revision == request.expectedInvoiceRevision && cut.progress.row.revision == request.expectedProgressRevision &&
            cut.payable?.row?.revision == request.expectedPayableRevision && cut.credits.index?.row?.revision == request.expectedCreditRevision &&
            purchasingCreditSettlementCutMatches(request, cut))
        val due = purchasingDue(cut) ?: error("Original credit requires recognized payable")
        val available = checkNotNull(purchasingAvailableToAllocate(cut))
        check(request.expectedRemainingDue == due.remainingDue && request.amount.minor <= available.minor &&
            cut.credits.entries.size < LocalPurchasingProfile.MAX_CREDITS)
        reader.requireSupplierHydrationCapacity()
        val revision = (cut.credits.index?.row?.revision ?: Revision(0)).next()
        val resources = purchasingCreditResources(command.scope, invoice, request, order,
            listOf(cut.order.value.producer.actorId, cut.invoice.value.producer.actorId)) + reader.supplierRecoveryResources +
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId)
        command.requireLegacyEffects(ProposedOutcome.Apply(purchasingCreditResult(request.creditId, revision), listOf(
            change(command, LocalPurchasingProfile.CREDIT, request.creditId, null, purchasingCreditBody(request, cut)),
            change(command, LocalPurchasingProfile.INVOICE_CREDITS, request.invoiceId, cut.credits.index,
                purchasingNextCredits(request, cut))),
            purchasingCreditClaims(command.scope, invoice.supplierId, request, cut.credits.index == null)), resources)
        return true
    }

    /** AFTER records use actual original time/policy/actor; core checks each observed BEFORE generation. */
    private fun change(command: RuntimeHistoricalCommand, kind: String, id: DomainId, before: PurchasingRow?, body: PurchasingBody): AggregateChange =
        AggregateChange(purchasingKey(kind, id), before?.row?.revision, LocalPurchasingProfile.schema(kind),
            purchasingEncode(PurchasingRecord(command.scope, kind, id, (before?.row?.revision ?: Revision(0)).next(),
                PurchasingProducer(command.operationId, command.actorId, command.command.name, command.semanticDigest.value),
                command.recordedAt, command.policyRevision, body), PurchasingRecord.serializer()))
}
