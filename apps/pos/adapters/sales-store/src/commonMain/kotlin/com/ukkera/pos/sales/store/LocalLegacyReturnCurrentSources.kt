package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind

/** Current compatibility reads require the actual native-selected writer and its WHOLE
 * original program. A missing CASE locator, matching row hash or caller-supplied operation
 * cannot establish remaining rights. This supplies neither a CASE contribution nor a grant. */
private suspend fun currentLegacyAcceptance(view: CommandReadView,
    row: StoredAggregate): LocalReturnRequestV1.Accept? {
    val services = (view as? CommerceStockView)?.commerceOriginalSources ?: return null
    val original = services.currentLegacyOriginal(view, row, LocalSalesStoreProfile.RETURN_COMMAND) ?: return null
    val request = LocalReturnCommandCodec.decode(original.normalized) as? LocalReturnRequestV1.Accept ?: return null
    requireLocalSalesSource(original.scope == view.scope && original.nativeOriginalProvenance == null &&
        view.claim(LocalSalesStoreProfile.unique("return_claim", request.claimId)) == request.saleId &&
        view.claim(LocalSalesStoreProfile.unique("accepted_return", request.returnId)) == request.saleId &&
        view.claim(LocalSalesStoreProfile.unique("return_claim_source", request.returnId)) == request.claimId &&
        view.claim(LocalSalesStoreProfile.unique("claim_acceptance", request.claimId)) == request.returnId)
    return request
}

internal suspend fun requireCurrentLegacyEntitlement(view: CommandReadView,
    sale: LocalReturnSaleCut, row: StoredAggregate): Boolean {
    val request = currentLegacyAcceptance(view, row) ?: return false
    requireLocalSalesSource(row.key.kind == LocalSalesStoreProfile.ENTITLEMENT_KIND && row.key.id == request.saleId &&
        request.saleId == sale.sale.value.id)
    return true
}

internal suspend fun requireCurrentLegacyAccepted(view: CommandReadView,
    claim: LocalClaimCut, row: StoredAggregate): Boolean {
    val services = (view as? CommerceStockView)?.commerceOriginalSources ?: return false
    val original = services.currentLegacyOriginal(view, row, setOf(LocalSalesStoreProfile.RETURN_COMMAND,
        LocalExchangeCodec.COMMAND, LocalFinancialJournalProfileV3.RETURN_COMMAND, LocalRetailSupervisorCodecV1.COMMAND)) ?: return false
    requireLocalSalesSource(claim.intake != null && original.scope == view.scope && original.nativeOriginalProvenance == null &&
        row.key.kind == LocalSalesStoreProfile.RETURN_KIND)
    val accepted = SalesSnapshotCodec.decodeAcceptedReturn(row.text(),
        SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, view.scope, row.key.id, row.domainRevision()),
        claim.original.entitlement.value, claim.claim.value)
    requireLocalSalesSource(accepted.id == row.key.id && accepted.saleId == claim.original.sale.value.id &&
        accepted.claimId == claim.claim.value.id &&
        view.claim(LocalSalesStoreProfile.unique("return_claim", accepted.claimId)) == accepted.saleId &&
        view.claim(LocalSalesStoreProfile.unique("accepted_return", accepted.id)) == accepted.saleId &&
        view.claim(LocalSalesStoreProfile.unique("return_claim_source", accepted.id)) == accepted.claimId &&
        view.claim(LocalSalesStoreProfile.unique("claim_acceptance", accepted.claimId)) == accepted.id)
    if (original.command == LocalSalesStoreProfile.RETURN_COMMAND) {
        val request = LocalReturnCommandCodec.decode(original.normalized) as? LocalReturnRequestV1.Accept ?: return false
        requireLocalSalesSource(request.returnId == accepted.id && request.saleId == accepted.saleId && request.claimId == accepted.claimId)
    } else requireRefundLiabilityWriter(original, row, accepted)
    return true
}
