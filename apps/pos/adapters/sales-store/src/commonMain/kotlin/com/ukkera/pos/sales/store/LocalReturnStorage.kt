package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.AcceptedReturn
import com.ukkera.pos.sales.PaidSale
import com.ukkera.pos.sales.ReceiptEntitlement
import com.ukkera.pos.sales.ReturnClaim
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

@Serializable
internal data class LocalReturnIntake(
    val scope: LocalSalesScope, val claimId: DomainId, val revision: Revision,
    val originalActorId: DomainId, val originalOperationId: OperationId, val recordedAt: UtcInstant,
    val facts: LocalReturnClaimFactsV1, val boundSaleId: DomainId? = null,
)

/** Immutable sale/capture DATA shared by source readers; it conveys no current entitlement. */
internal interface LocalReturnSaleEvidence {
    val sale: LocalSalesRow<PaidSale>
    val returnPolicyCapture: LocalSalesRow<LocalSalesReturnPolicyCaptureV2>?
}
internal data class LocalReturnSaleCut(
    override val sale: LocalSalesRow<PaidSale>, val priceSources: LocalSalesRow<LocalSalesPriceSourcesV2>?,
    override val returnPolicyCapture: LocalSalesRow<LocalSalesReturnPolicyCaptureV2>?,
) : LocalReturnSaleEvidence
internal data class LocalEntitlementCut(
    override val sale: LocalSalesRow<PaidSale>, val entitlement: LocalSalesRow<ReceiptEntitlement>,
    /** Fully verified NEW companion; explicit historical constructors retain their absent profile. */
    override val returnPolicyCapture: LocalSalesRow<LocalSalesReturnPolicyCaptureV2>? = null,
    val priceSources: LocalSalesRow<LocalSalesPriceSourcesV2>? = null,
    /** Complete normalized producer lineage only; absent for explicit legacy/current-writer compatibility. */
    val normalizedSource: LocalReturnEntitlementSourceCut? = null,
) : LocalReturnSaleEvidence
internal data class LocalClaimCut(
    val original: LocalEntitlementCut,
    /** Null ONLY for the explicitly source-verified CASE companion profile, never fabricated v1 intake. */
    val intake: LocalSalesRow<LocalReturnIntake>?,
    val claim: LocalSalesRow<ReturnClaim>,
    val caseRecoveryResources: Set<RecoveryResourceKey> = emptySet(),
)
internal data class LocalReturnCut(val original: LocalClaimCut, val accepted: LocalSalesRow<AcceptedReturn>, val work: LocalSalesRow<LocalWorkState>)

internal suspend fun readIntake(view: CommandReadView, id: DomainId): LocalSalesRow<LocalReturnIntake> {
    val row = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_INTAKE_KIND, id))
    val value = decodeLocalSalesSource(row.payload, LocalReturnIntake.serializer(), 8_192)
    requireLocalSalesSource(value.scope.value() == view.scope && value.claimId == id && value.revision == row.domainRevision() &&
        value.revision == (if (value.boundSaleId == null) Revision(0) else Revision(1))) { "sales.claim.intake" }
    localReason(value.facts.reason)
    requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("return_intake", id)) == value.originalActorId) { "sales.source.unavailable" }
    val work = LocalSalesStorage.work(view, value.originalActorId)
    requireLocalSalesSource((id in work.value.pendingClaimIds) == (value.boundSaleId == null)) { "sales.claim.work" }
    return LocalSalesRow(row, value)
}

/** Full immutable source evidence, deliberately independent of today's advanced entitlement. */
internal suspend fun readReturnSaleSource(view: CommandReadView, id: DomainId): LocalReturnSaleCut {
    val saleRow = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, id))
    val sale = SalesSnapshotCodec.decodePaidSale(saleRow.text(), SalesSnapshotBinding(SalesSnapshotKind.PAID_SALE, view.scope, id, saleRow.domainRevision()))
    val priceSources = readPriceSources(view, sale.orderId, sale.quote, sale.priceSourceReference)
    val returnPolicyCapture = readReturnPolicyCapture(view, sale.orderId, sale.quote, sale.returnPolicySourceReference,
        priceSources?.value?.sources.orEmpty())
    if (returnPolicyCapture != null) requireLocalSalesSource(priceSources != null && priceSources.value.normalizedStock &&
        returnPolicyCapture.value.originalQuoteOperationId == priceSources.value.originalQuoteOperationId &&
        returnPolicyCapture.value.id == priceSources.value.id)
    requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("sale", id)) == sale.orderId &&
        view.claim(LocalSalesStoreProfile.unique("sale_order", sale.orderId)) == id &&
        view.claim(LocalSalesStoreProfile.unique("return_entitlement", id)) == id) { "sales.source.unavailable" }
    return LocalReturnSaleCut(LocalSalesRow(saleRow, sale), priceSources, returnPolicyCapture)
}

internal suspend fun readEntitlement(view: CommandReadView, id: DomainId, selectedCaseId: DomainId? = null): LocalEntitlementCut {
    val sale = readReturnSaleSource(view, id)
    val entitlementRow = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, id))
    val entitlement = SalesSnapshotCodec.decodeReceiptEntitlement(entitlementRow.text(),
        SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, view.scope, id, entitlementRow.domainRevision()))
    requireLocalSalesSource(SalesSnapshotCodec.encodePaidSale(entitlement.sale) == SalesSnapshotCodec.encodePaidSale(sale.sale.value)) { "sales.entitlement.source" }
    val normalizedSource = if (sale.priceSources?.value?.normalizedStock == true)
        requireNormalizedReturnEntitlementSource(view, sale, entitlementRow, selectedCaseId)
        else null
    return LocalEntitlementCut(sale.sale, LocalSalesRow(entitlementRow, entitlement), sale.returnPolicyCapture, sale.priceSources, normalizedSource)
}

/** Full immutable companion/publication dependency cut, not a selected claim-portion subtotal. */
internal fun entitlementReturnPolicyResources(cut: LocalReturnSaleEvidence?): Set<RecoveryResourceKey> {
    val sale = cut?.sale?.value ?: return emptySet()
    val reference = sale.returnPolicySourceReference ?: return emptySet()
    val capture = cut.returnPolicyCapture ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(capture.stored.key.kind == LocalSalesReturnPolicyCaptureCodecV2.KIND &&
        capture.stored.key.id == reference.id && capture.stored.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA &&
        capture.stored.revision == Revision(1) && capture.stored.digest == reference.digest &&
        capture.value.scope == sale.scope && capture.value.id == reference.id && capture.value.orderId == sale.orderId &&
        capture.value.quoteRevision == sale.quote.revision)
    return returnPolicyCaptureResources(capture.value)
}

/** Complete source absence only; a surviving entitlement/locator/claim is damaged evidence. */
internal suspend fun returnSaleSourcePresent(view: CommandReadView, id: DomainId): Boolean {
    if (view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, id)) == null) {
        // Only complete local source absence is ordinary. Partial retained state still
        // fails closed instead of turning a damaged receipt into a clean negative.
        requireLocalSalesSource(view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, id)) == null &&
            view.claim(LocalSalesStoreProfile.unique("sale", id)) == null &&
            view.claim(LocalSalesStoreProfile.unique("return_entitlement", id)) == null &&
            view.aggregate(LocalReturnEntitlementProvenanceProfile.locator(id)) == null &&
            view.claim(LocalReturnEntitlementProvenanceProfile.locatorClaim(id)) == null) { "sales.entitlement.source" }
        return false
    }
    return true
}

/** An asserted unknown receipt is normal pending intake, not fabricated refund rights. */
internal suspend fun findEntitlement(view: CommandReadView, id: DomainId): LocalEntitlementCut? =
    if (returnSaleSourcePresent(view, id)) readEntitlement(view, id) else null

internal suspend fun readClaim(view: CommandReadView, saleId: DomainId, claimId: DomainId): LocalClaimCut {
    val caseOrigin = view.claim(LocalReturnCaseProfileV1.source("claim", claimId))
    val caseRow = view.aggregate(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, claimId))
    val caseOwner = view.claim(LocalReturnCaseProfileV1.source("case", claimId))
    if (caseOrigin != null || caseRow != null || caseOwner != null) {
        requireLocalSalesSource(caseOrigin == claimId && caseRow != null && caseOwner != null &&
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_INTAKE_KIND, claimId)) == null &&
            view.claim(LocalSalesStoreProfile.unique("return_intake", claimId)) == null)
        return readReturnCaseClaim(view, readEntitlement(view, saleId, selectedCaseId = claimId), claimId)
    }
    // Historical v1 decoding remains explicit and unchanged; missing CASE provenance is not inferred.
    val original = readEntitlement(view, saleId)
    val intake = readIntake(view, claimId)
    val row = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, claimId))
    val claim = SalesSnapshotCodec.decodeReturnClaim(row.text(),
        SalesSnapshotBinding(SalesSnapshotKind.RETURN_CLAIM, view.scope, claimId, row.domainRevision()), original.entitlement.value)
    requireLocalSalesSource(intake.value.boundSaleId == saleId && intake.value.facts.saleId == saleId && claim.claimantOperatorId == intake.value.originalActorId &&
        claim.portionId == intake.value.facts.portionId && claim.quantity == intake.value.facts.quantity &&
        view.claim(LocalSalesStoreProfile.unique("return_claim", claimId)) == saleId) { "sales.claim.source" }
    return LocalClaimCut(original, intake, LocalSalesRow(row, claim), entitlementSourceResources(original))
}

internal suspend fun readAcceptedReturn(view: CommandReadView, saleId: DomainId, claimId: DomainId, returnId: DomainId): LocalReturnCut {
    val original = readClaim(view, saleId, claimId)
    val row = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_KIND, returnId))
    val accepted = SalesSnapshotCodec.decodeAcceptedReturn(row.text(),
        SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, view.scope, returnId, row.domainRevision()),
        original.original.entitlement.value, original.claim.value)
    val verifiedOriginal = if (original.intake == null) original.copy(caseRecoveryResources = original.caseRecoveryResources +
        requireReturnCaseAcceptedOrigin(view, original, LocalSalesRow(row, accepted))) else original
    requireNormalizedAcceptedReturnSource(view, verifiedOriginal, LocalSalesRow(row, accepted))
    requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("accepted_return", returnId)) == saleId &&
        view.claim(LocalSalesStoreProfile.unique("return_claim_source", returnId)) == claimId &&
        view.claim(LocalSalesStoreProfile.unique("claim_acceptance", claimId)) == returnId) { "sales.return.source" }
    val work = LocalSalesStorage.work(view, accepted.acceptingOperatorId)
    requireLocalSalesSource((returnId in work.value.returnIds) == (accepted.due.minor != 0L || accepted.unresolved)) { "sales.return.work" }
    return LocalReturnCut(verifiedOriginal, LocalSalesRow(row, accepted), work)
}

internal fun entitlementWrite(value: ReceiptEntitlement, before: StoredAggregate?): AggregateChange =
    domainWrite(LocalSalesStoreProfile.ENTITLEMENT_KIND, value.saleId, value.revision, SalesSnapshotCodec.encodeReceiptEntitlement(value), before)

internal fun claimWrite(value: ReturnClaim, before: StoredAggregate?): AggregateChange =
    domainWrite(LocalSalesStoreProfile.RETURN_CLAIM_KIND, value.id, value.revision, SalesSnapshotCodec.encodeReturnClaim(value), before)

internal fun acceptedReturnWrite(value: AcceptedReturn, entitlement: ReceiptEntitlement, claim: ReturnClaim, before: StoredAggregate?): AggregateChange =
    domainWrite(LocalSalesStoreProfile.RETURN_KIND, value.id, value.revision, SalesSnapshotCodec.encodeAcceptedReturn(value, entitlement, claim), before)
