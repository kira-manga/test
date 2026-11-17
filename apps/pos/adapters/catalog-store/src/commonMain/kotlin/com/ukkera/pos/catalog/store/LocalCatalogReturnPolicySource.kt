package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.sales.store.FactualSalesReturnPolicySourceV1
import com.ukkera.pos.sales.store.LocalReturnCaseFactualCutV1
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.store.LocalPreparedSalesReturnPolicyV1
import com.ukkera.pos.sales.store.LocalReturnCaseFactualSourcesV1
import com.ukkera.pos.sales.store.LocalReturnPolicyApplicableRuleV1
import com.ukkera.pos.sales.store.LocalReturnPolicyBasisV1
import com.ukkera.pos.sales.store.LocalReturnPolicyPortionBasisV1
import com.ukkera.pos.sales.store.LocalReturnPolicyReceiptBasisV1
import com.ukkera.pos.sales.store.LocalReturnPolicyRightsBasisV1
import com.ukkera.pos.sales.store.LocalSalesReturnPromiseStateV2
import com.ukkera.pos.sales.store.PreparedSalesReturnPolicy
import com.ukkera.pos.sales.store.TransactionSalesReturnPolicySource
import com.ukkera.pos.types.DomainId

/**
 * Production Master return-policy source for the original-receipt claim binding.
 *
 * Inside the writer transaction it reads only genuine immutable sources: the exact paid sale
 * and its quote-time captured signed SKU promises (each re-verified against its original
 * signed publication and installer), the branch's currently ACTIVE authenticated return-rights
 * publication and the complete recorded customer-receipt census. A sale without a captured
 * promise yields null (claim stays pending; no backfill with today's policy). Damaged,
 * unauthenticated or partial sources throw and remain uncertainty, never an accepting default.
 * Eligibility itself is decided by [com.ukkera.pos.sales.store.LocalReturnPolicyEntitlementV1].
 */
public class LocalCatalogReturnPolicySource(
    private val signatures: CompanyPublicationSignatureVerifier,
) : TransactionSalesReturnPolicySource, FactualSalesReturnPolicySourceV1 {
    override public suspend fun current(view: CommandReadView, saleId: DomainId): PreparedSalesReturnPolicy? {
        val facts = LocalReturnCaseFactualSourcesV1.readCaptured(view, saleId) ?: return null
        return prepare(view.scope, saleId, facts, CurrentReturnRightsReaderV1(view),
            readRow = { view.aggregate(it) }, readOriginal = { view.retainedCommand(it) })
    }

    override public suspend fun before(command: BusinessOriginalProgram, saleId: DomainId): PreparedSalesReturnPolicy? {
        val facts = LocalReturnCaseFactualSourcesV1.readHistory(command, saleId) ?: return null
        return prepare(command.scope, saleId, facts, HistoricalReturnRightsReaderV1(command),
            readRow = { command.aggregate(it) },
            readOriginal = { (command.earlierOriginal(it) as? RetainedOriginalV2.Legacy)?.evidence })
    }

    private suspend fun prepare(scope: BranchScope, saleId: DomainId, facts: LocalReturnCaseFactualCutV1,
        reader: ReturnRightsSourceReaderV1, readRow: suspend (AggregateKey) -> StoredAggregate?,
        readOriginal: suspend (OperationId) -> RetainedCommandEvidence?): PreparedSalesReturnPolicy {
        val capture = checkNotNull(facts.returnPolicyCapture)
        check(facts.scope == scope && facts.originalSale.id == saleId) { "sales.return.policy_scope" }
        val publications = capture.value.input.publications.associateBy { it.publicationId }
        val resources = facts.recoveryResources.toMutableSet()
        for (publication in publications.values) if (publication.state == LocalSalesReturnPromiseStateV2.SIGNED_PRESENT) {
            resources += requireCapturedReturnPublication(signatures, scope, publication,
                readRow = readRow, readOriginal = readOriginal)
        }
        val sold = facts.originalCustomerReceipt.portions.associate { it.portionId to it.quantity }
        val portions = capture.value.input.portions.map { portion ->
            val publication = publications.getValue(portion.publicationId)
            LocalReturnPolicyPortionBasisV1(portion.portionId,
                sold[portion.portionId] ?: error("sales.return.policy_sold_portion"), publication.state, publication.promise)
        }

        val active = reader.active()
        reader.sourcePolicies().forEach { requireReturnRightsSource(authenticReturnRights(signatures, scope, it)) }
        resources += reader.resources
        val rights = active?.let { loaded ->
            LocalReturnPolicyRightsBasisV1(loaded.publication.reference.policyId, loaded.active.record.recordedAt,
                loaded.publication.signed.definition.rules.filter {
                    it.reason == LocalReturnRightsReason.CHANGE_OF_MIND &&
                        it.treatment == LocalReturnRightsTreatment.RECEIPT_WINDOW
                }.map { LocalReturnPolicyApplicableRuleV1(it.categoryCode, checkNotNull(it.receiptWindowDays)) })
        }
        val receipts = facts.customerReceiptEvents.map { event ->
            LocalReturnPolicyReceiptBasisV1(event.source.id, event.value.receivedAt, event.value.recordedAt,
                event.value.portions.associate { it.portionId to it.quantity })
        }
        return LocalPreparedSalesReturnPolicyV1(LocalReturnPolicyBasisV1(scope, saleId,
            SalesSnapshotCodec.encodePaidSale(facts.originalSale), portions, rights, receipts, resources))
    }
}
