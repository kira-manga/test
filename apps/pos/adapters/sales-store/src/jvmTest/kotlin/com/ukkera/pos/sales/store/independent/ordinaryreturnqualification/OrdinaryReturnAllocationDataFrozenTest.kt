@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.sales.store.independent.ordinaryreturnqualification

import com.ukkera.pos.database.*
import com.ukkera.pos.pricing.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer
import kotlin.test.*
import kotlin.time.Instant

/** RQ-002/023/024/025/039 allocation and shared-proposal DATA support only.
 * Final original postings are the frozen F1 scalars. The quote below deliberately
 * starts from those final net prices; it does not reproduce original discount
 * capture. No SalesCommand, grant, source/history provider, writer or commit exists.
 * One pure prior acceptance advances logical revision 0 to 1, NOT native F1
 * revision 7. DATA decision coordinates never prove qualification or authority. */
class OrdinaryReturnAllocationDataFrozenTest {
    private fun id(suffix: Int) = DomainId("00000000-0000-4000-8000-${suffix.toString().padStart(12, '0')}")
    private fun at(literal: String) = UtcInstant(Instant.parse(literal))
    private fun money(minor: Long) = Money(Currency.EGP, minor)
    private val scope = BranchScope(id(101), id(201), id(301))
    private val saleOwner = id(401)
    private val owner = id(402)
    private val priorOwner = id(403)
    private val saleId = id(501)
    private val portionId = id(502)
    private val receiptId = id(503)
    private val inspectionId = id(504)
    private val recordOperation = OperationId(id(602).value)
    private val decisionOperation = OperationId(id(604).value)
    private val acceptOperation = OperationId(id(605).value)
    private val caseId = id(760)
    private val returnId = id(761)
    private val priorReturnId = id(762)
    private val unit = id(763)
    private val cashPaymentId = id(764)
    private val cardPaymentId = id(765)
    private val packagingId = id(766)
    private val deliveryId = id(767)
    private val recordedAt = at("2026-11-03T14:27:18.124Z")
    private val receivedAt = at("2026-10-20T14:27:18.125Z")
    private val qualifyAt = at("2026-11-06T09:00:00.000Z")
    private val acceptAt = at("2026-11-07T09:00:00.000Z")
    private val inspectionAt = at("2026-11-05T08:00:00.987Z")
    private val inspection = LocalReturnOrdinaryInspectionV1(LocalReturnInspectionConditionV1.UNCHANGED,
        LocalCashEvidenceV1(inspectionId, inspectionAt), "2026-11-05T10:00:00.987+02:00")

    @Test
    fun rq002_rq025_pureOriginalAllocationRetainsPriorDueAndExactFrozenComponents() {
        val data = boundData()
        assertEquals(Revision(1), data.original.entitlement.value.revision)
        assertRemaining(data.original.entitlement.value, 1, 5, 58_710, 30_000, 28_710)
        assertDue(data.prior.acceptedReturn, 11_742, 6_000, 5_742)
        val accepted = Returns.originalAcceptanceValue(data.original.entitlement.value, data.request.expectedEntitlementRevision,
            data.qualified.claim.value, data.request.expectedClaimRevision, returnId, ReturnDisposition.INSPECTED_SELLABLE,
            CashierEvidence(inspectionId, owner, inspectionAt.value), owner)
        assertDue(accepted.acceptedReturn, 23_484, 12_000, 11_484)
        assertEquals(mapOf(PriceComponentKind.ITEM_NET to money(20_000), PriceComponentKind.ITEM_TAX to money(2_800),
            PriceComponentKind.CHARGE_NET to money(600), PriceComponentKind.CHARGE_TAX to money(84)),
            accepted.acceptedReturn.components.associate { it.kind to it.amount })
        assertEquals(quantity(1), accepted.acceptedReturn.consumedQuantityBefore)
        assertEquals(quantity(2), accepted.acceptedReturn.quantity)
        assertRemaining(accepted.entitlement, 3, 3, 35_226, 18_000, 17_226)
        assertEquals(money(35_226), data.prior.acceptedReturn.due + accepted.acceptedReturn.due)
        assertDue(data.prior.acceptedReturn, 11_742, 6_000, 5_742)
        assertRemaining(data.original.entitlement.value, 1, 5, 58_710, 30_000, 28_710)
        assertNull(data.qualified.claim.value.acceptedReturnId)
        assertEquals(money(71_250), accepted.entitlement.sale.quote.payable)
        assertEquals(money(798), retainedDelivery(accepted.entitlement))
    }

    @Test
    fun rq002_rq023_rq025_rq039_sharedAcceptanceProposalBindsExactRequirementAndSuccessors() {
        val data = boundData()
        val beforeEntitlement = SalesSnapshotCodec.encodeReceiptEntitlement(data.original.entitlement.value)
        val beforeClaim = SalesSnapshotCodec.encodeReturnClaim(data.qualified.claim.value)
        val production = production(data)
        assertEquals(Permission(SecurityAction.RETURN_ACCEPT, PermissionVerb.PERFORM), production.requirement.permission)
        assertEquals(scope, production.requirement.scope)
        val binding = assertNotNull(production.requirement.binding)
        assertEquals(saleId, binding.documentId)
        assertEquals(data.request.expectedEntitlementRevision, binding.revision)
        assertEquals(acceptOperation, binding.operationId)
        assertEquals(data.identity.semanticDigest, binding.payloadDigest)
        assertEquals("return.case.accept", binding.reasonCode)
        assertEquals(money(23_484), assertIs<LimitUse.Monetary>(production.requirement.limitUse).amount)
        assertEquals(OwnerConstraint.Unrestricted, production.requirement.owner)
        // This is a requirement value, not current permission/ceiling evaluation or a grant.
        val proposal = assertIs<ProposedOutcome.Apply>(production.propose(acceptAt))
        val result = assertIs<LocalReturnCaseResultV1.Applied>(LocalReturnCaseCommandCodecV1.decodeResult(proposal.result))
        assertEquals(LocalReturnCaseActionV1.ACCEPTED, result.action)
        assertEquals(LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING, result.stage)
        assertEquals(data.request.expectedCaseRevision.next(), result.caseRevision)
        assertEquals(data.request.expectedClaimRevision.next(), result.claimRevision)
        assertEquals(data.request.expectedEntitlementRevision.next(), result.entitlementRevision)
        assertEquals(returnId, result.returnId)
        assertEquals(money(23_484), result.originalPayable)
        val entitlement = decodeEntitlement(proposal, assertNotNull(result.entitlementRevision))
        val claim = SalesSnapshotCodec.decodeReturnClaim(change(proposal, LocalSalesStoreProfile.RETURN_CLAIM_KIND, caseId).payload
            .copyBytes().decodeToString(), SalesSnapshotBinding(SalesSnapshotKind.RETURN_CLAIM, scope, caseId,
                assertNotNull(result.claimRevision)), entitlement)
        val accepted = SalesSnapshotCodec.decodeAcceptedReturn(change(proposal, LocalSalesStoreProfile.RETURN_KIND, returnId).payload
            .copyBytes().decodeToString(), SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, scope, returnId, Revision(0)),
            entitlement, claim)
        assertEquals(returnId, claim.acceptedReturnId)
        assertDue(accepted, 23_484, 12_000, 11_484)
        assertRemaining(entitlement, 3, 3, 35_226, 18_000, 17_226)
        val event = localSalesDecode(change(proposal, LocalReturnCaseProfileV1.EVENT, DomainId(acceptOperation.value)).payload,
            LocalReturnCaseEventV1.serializer())
        assertEquals(recordOperation, event.after.originalOperationId)
        assertEquals(recordedAt, event.after.recordedAt)
        assertEquals(acceptAt, event.recordedAt)
        assertEquals(data.cut.row.value.facts, event.after.facts)
        assertEquals(decisionOperation, event.after.decisionOperationId)
        assertEquals(data.original.entitlement.stored.digest, event.beforeEntitlementDigest)
        assertEquals(data.qualified.claim.stored.digest, event.beforeClaimDigest)
        assertEquals(data.work.stored.digest, event.beforeCashWorkDigest)
        val work = localSalesDecode(change(proposal, LocalSalesStoreProfile.WORK_KIND, owner).payload, LocalWorkState.serializer())
        assertEquals(listOf(returnId), work.returnIds)
        // The prior obligation belongs to OTHER_RETURN_OWNER; this owner's work must not adopt it.
        assertTrue(proposal.changes.none { it.key.id == priorReturnId || it.key == data.priorWork.stored.key })
        assertDue(data.prior.acceptedReturn, 11_742, 6_000, 5_742)
        assertEquals(listOf(priorReturnId), data.priorWork.value.returnIds)
        assertEquals(money(35_226), data.prior.acceptedReturn.due + accepted.due)
        assertEquals(listOf(
            ClaimChange.Acquire(LocalReturnCaseProfileV1.source("event", DomainId(acceptOperation.value)), caseId),
            ClaimChange.Acquire(LocalSalesStoreProfile.unique("accepted_return", returnId), saleId),
            ClaimChange.Acquire(LocalSalesStoreProfile.unique("claim_acceptance", caseId), returnId),
            ClaimChange.Acquire(LocalSalesStoreProfile.unique("return_claim_source", returnId), caseId)), proposal.claims)
        assertTrue(proposal.changes.all { it.key.kind.startsWith("sales.return.") || it.key.kind == LocalSalesStoreProfile.WORK_KIND })
        assertTrue(proposal.jobs.isEmpty())
        assertEquals(money(71_250), entitlement.sale.quote.payable)
        assertEquals(money(798), retainedDelivery(entitlement))
        assertEquals(beforeEntitlement, SalesSnapshotCodec.encodeReceiptEntitlement(data.original.entitlement.value))
        assertEquals(beforeClaim, SalesSnapshotCodec.encodeReturnClaim(data.qualified.claim.value))
        assertEquals(emptyList(), data.work.value.returnIds)
        assertEquals(LocalReturnCaseStageV1.BOUND_UNACCEPTED, data.cut.row.value.stage)
        assertEquals(inspection, data.cut.decision?.value?.ordinaryInspection)
        // No real drawer, inventory, native work/history join, commit or payout is exercised.
    }

    @Test
    fun rq023_sharedOwnerRevisionDecisionAndDuplicateRefusalsLeaveDataUnchanged() {
        val data = boundData()
        val beforeEntitlement = SalesSnapshotCodec.encodeReceiptEntitlement(data.original.entitlement.value)
        val beforeClaim = SalesSnapshotCodec.encodeReturnClaim(data.qualified.claim.value)
        assertRefused("OWNER_REQUIRED", production(data, identity = data.identity.copy(actorId = priorOwner)))
        for (revision in listOf(Revision(0), data.request.expectedCaseRevision.next())) {
            assertRefused("REVISION_CONFLICT", production(data, request = data.request.copy(expectedCaseRevision = revision)))
        }
        assertRefused("DECISION_CHANGED", production(data, request = data.request.copy(decisionOperationId = OperationId(id(780).value))))
        assertRefused("QUALIFIED_DECISION_REQUIRED", production(data, cut = data.cut.copy(decision = null)))
        assertRefused("DUPLICATE_SOURCE", production(data, duplicate = true))
        assertRefused("REVISION_CONFLICT", production(data, request = data.request.copy(expectedEntitlementRevision = Revision(0))))
        assertRefused("REVISION_CONFLICT", production(data, request = data.request.copy(expectedClaimRevision = Revision(1))))
        assertEquals(beforeEntitlement, SalesSnapshotCodec.encodeReceiptEntitlement(data.original.entitlement.value))
        assertEquals(beforeClaim, SalesSnapshotCodec.encodeReturnClaim(data.qualified.claim.value))
        assertRemaining(data.original.entitlement.value, 1, 5, 58_710, 30_000, 28_710)
        assertDue(data.prior.acceptedReturn, 11_742, 6_000, 5_742)
        assertEquals(LocalReturnCaseStageV1.BOUND_UNACCEPTED, data.cut.row.value.stage)
        // Scalar DATA guards do not verify live authority, policy ceilings or uniqueness races.
    }

    @Test
    fun rq024_sharedExactExhaustionUsesRemainingFiveAndCannotResetFromOriginalSix() {
        val oversized = boundData(requested = 6)
        assertRefused("RETURN_ENTITLEMENT_EXHAUSTED", production(oversized))
        assertRemaining(oversized.original.entitlement.value, 1, 5, 58_710, 30_000, 28_710)
        val data = boundData(requested = 5)
        val proposal = assertIs<ProposedOutcome.Apply>(production(data).propose(acceptAt))
        val result = assertIs<LocalReturnCaseResultV1.Applied>(LocalReturnCaseCommandCodecV1.decodeResult(proposal.result))
        assertEquals(money(58_710), result.originalPayable)
        val entitlement = decodeEntitlement(proposal, assertNotNull(result.entitlementRevision))
        assertRemaining(entitlement, 6, 0, 0, 0, 0)
        val claim = SalesSnapshotCodec.decodeReturnClaim(change(proposal, LocalSalesStoreProfile.RETURN_CLAIM_KIND, caseId).payload
            .copyBytes().decodeToString(), SalesSnapshotBinding(SalesSnapshotKind.RETURN_CLAIM, scope, caseId,
                assertNotNull(result.claimRevision)), entitlement)
        val accepted = SalesSnapshotCodec.decodeAcceptedReturn(change(proposal, LocalSalesStoreProfile.RETURN_KIND, returnId).payload
            .copyBytes().decodeToString(), SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, scope, returnId, Revision(0)),
            entitlement, claim)
        assertDue(accepted, 58_710, 30_000, 28_710)
        assertEquals(money(70_452), data.prior.acceptedReturn.due + accepted.due)
        val exhausted = boundData(requested = 1, entitlementOverride = entitlement)
        assertRefused("RETURN_ENTITLEMENT_EXHAUSTED", production(exhausted))
        assertRemaining(entitlement, 6, 0, 0, 0, 0)
        assertDue(data.prior.acceptedReturn, 11_742, 6_000, 5_742)
    }

    private fun production(data: BoundData, request: LocalReturnCaseRequestV1.Accept = data.request,
        identity: LocalReturnCaseProductionIdentity = data.identity, cut: LocalReturnCaseCut = data.cut, duplicate: Boolean = false) =
        returnCaseAcceptanceProduction(identity, request, cut, data.original, data.qualified, data.custodian,
            cash = null, work = data.work, duplicate = duplicate, entitlementAppend = null)

    private fun assertRefused(reason: String, production: LocalReturnCaseIntakeProduction) {
        val outcome = assertIs<ProposedOutcome.Reject>(production.propose(acceptAt))
        assertEquals(reason, assertIs<LocalReturnCaseResultV1.Refused>(LocalReturnCaseCommandCodecV1.decodeResult(outcome.result)).reason)
    }

    private fun assertDue(value: AcceptedReturn, due: Long, cash: Long, card: Long) {
        assertEquals(money(due), value.originalPayable)
        assertEquals(money(due), value.due)
        assertEquals(money(0), value.refunded)
        assertEquals(RefundStatus.DUE, value.refundStatus)
        assertTrue(value.attempts.isEmpty())
        assertFalse(value.unresolved)
        assertEquals(mapOf(cashPaymentId to money(cash), cardPaymentId to money(card)),
            value.tenderObligations.associate { it.originalPaymentId to it.amount })
    }

    private fun assertRemaining(value: ReceiptEntitlement, consumed: Long, remaining: Long, refundable: Long, cash: Long, card: Long) {
        val cursor = value.cursors.single()
        assertEquals(quantity(6), cursor.originalQuantity)
        assertEquals(quantity(consumed), cursor.consumedQuantity)
        assertEquals(quantity(remaining), cursor.remainingQuantity)
        assertEquals(money(refundable), cursor.remainingPayable)
        val keys = cursor.originalComponents.map { it.key }.toSet()
        val tenders = value.remainingComponentTenders.filter { it.componentKey in keys }.flatMap { it.shares }
        assertEquals(money(cash), tenders.filter { it.paymentId == cashPaymentId }.fold(money(0)) { sum, share -> sum + share.amount })
        assertEquals(money(card), tenders.filter { it.paymentId == cardPaymentId }.fold(money(0)) { sum, share -> sum + share.amount })
    }

    private fun retainedDelivery(value: ReceiptEntitlement) = value.sale.quote.components.filter { it.chargeId == deliveryId }
        .fold(money(0)) { sum, component -> sum + component.posted }
    private fun quantity(scaled: Long) = Quantity(unit, scaled, 0)
    private fun change(proposal: ProposedOutcome.Apply, kind: String, id: DomainId) =
        proposal.changes.single { it.key == AggregateKey(kind, id) }
    private fun decodeEntitlement(proposal: ProposedOutcome.Apply, revision: Revision) = SalesSnapshotCodec.decodeReceiptEntitlement(
        change(proposal, LocalSalesStoreProfile.ENTITLEMENT_KIND, saleId).payload.copyBytes().decodeToString(),
        SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, scope, saleId, revision))

    private data class BoundData(val prior: ReturnAccepted, val original: LocalEntitlementCut, val qualified: LocalClaimCut,
        val cut: LocalReturnCaseCut, val custodian: LocalSalesRow<LocalCustodianState>, val work: LocalSalesRow<LocalWorkState>,
        val priorWork: LocalSalesRow<LocalWorkState>, val request: LocalReturnCaseRequestV1.Accept,
        val identity: LocalReturnCaseProductionIdentity)

    /** All records are inert in-memory DATA. Null intake/companions are not a claim
     * of a verified compatible or normalized native source profile. */
    private fun boundData(requested: Long = 2, entitlementOverride: ReceiptEntitlement? = null): BoundData {
        val initial = initialAllocationData()
        val priorClaim = Returns.originalClaimValue(initial, Revision(0), id(768), portionId, quantity(1),
            claimEvidence(priorOwner, at("2026-10-25T12:00:00.000Z")), priorOwner)
        val prior = Returns.originalAcceptanceValue(initial, Revision(0), priorClaim, Revision(0), priorReturnId,
            ReturnDisposition.INSPECTED_SELLABLE, CashierEvidence(id(769), priorOwner, at("2026-10-25T12:00:00.000Z").value), priorOwner)
        val entitlement = entitlementOverride ?: prior.entitlement
        val original = LocalEntitlementCut(snapshotRow(LocalSalesStoreProfile.SALE_KIND, saleId, Revision(0), initial.sale,
            SalesSnapshotCodec.encodePaidSale(initial.sale)), snapshotRow(LocalSalesStoreProfile.ENTITLEMENT_KIND, saleId,
            entitlement.revision, entitlement, SalesSnapshotCodec.encodeReceiptEntitlement(entitlement)))
        val quantity = quantity(requested)
        val customerClaimAt = at("2026-11-03T16:10:00.000+02:00")
        val facts = LocalReturnCaseFactsV1(LocalReturnClaimFactsV1(saleId, portionId, quantity, null, customerClaimAt,
            "The size feels different. I request a return; staff have not inspected it yet.", LocalCashEvidenceV1(id(770), customerClaimAt)),
            "Unworn; customer assertion only, not employee inspection", LocalReturnRequestedRemedyV1.REFUND, "Africa/Cairo",
            LocalReturnClaimReasonV1.CHANGE_OF_MIND)
        val recordRequest = LocalReturnCaseRequestV1.Record(caseId, facts)
        val recorded = assertIs<ProposedOutcome.Apply>(returnCaseRecordProduction(identity(recordOperation, recordRequest), recordRequest,
            work = null, dependencies = emptySet(), duplicate = false).propose(recordedAt))
        val state = dataRow(change(recorded, LocalReturnCaseProfileV1.CASE, caseId), Revision(1), LocalReturnCaseStateV1.serializer())
        val event = dataRow(change(recorded, LocalReturnCaseProfileV1.EVENT, DomainId(recordOperation.value)), Revision(1), LocalReturnCaseEventV1.serializer())
        val caseWork = dataRow(change(recorded, LocalReturnCaseProfileV1.WORK, owner), Revision(1), LocalReturnCaseWorkV1.serializer())
        val timing = LocalReturnOrdinaryTimingV1.observe("ordinary_clothing_unchanged", 14, 14, receiptId,
            receivedAt, "Africa/Cairo", recordOperation, recordedAt)
        // Opaque references fill assessment shape only; no source provider ever returns them.
        val assessment = LocalReturnCaseAssessmentV1(LocalReturnOrdinaryAssessmentV1.ELIGIBLE, "ORDINARY_REVIEW", "ORDINARY_REVIEW",
            id(771), Revision(1), reference(772), listOf(reference(773)), listOf(reference(503)), receivedAt, "Africa/Cairo",
            ordinaryTiming = timing)
        val boundState = advanceCase(state.value, decisionOperation).copy(stage = LocalReturnCaseStageV1.BOUND_UNACCEPTED,
            decisionOperationId = decisionOperation)
        val bound = assertIs<ProposedOutcome.Apply>(returnCaseApplied(decisionOperation, state, caseWork, boundState,
            LocalReturnCaseActionV1.BOUND, qualifyAt, assessment = assessment, assessedBindingDigest = event.stored.digest,
            expectedEntitlementRevision = entitlement.revision, entitlementRevision = entitlement.revision, claimRevision = Revision(0),
            ordinaryInspection = inspection))
        val boundEvent = dataRow(change(bound, LocalReturnCaseProfileV1.EVENT, DomainId(decisionOperation.value)), Revision(1),
            LocalReturnCaseEventV1.serializer())
        val cut = LocalReturnCaseCut(dataRow(change(bound, LocalReturnCaseProfileV1.CASE, caseId), Revision(2), LocalReturnCaseStateV1.serializer()),
            boundEvent, dataRow(change(bound, LocalReturnCaseProfileV1.WORK, owner), Revision(2), LocalReturnCaseWorkV1.serializer()),
            event, boundEvent, emptySet())
        val claim = Returns.originalClaimValue(entitlement, entitlement.revision, caseId, portionId, quantity, claimEvidence(owner, recordedAt), owner)
        val qualified = LocalClaimCut(original, null, snapshotRow(LocalSalesStoreProfile.RETURN_CLAIM_KIND, caseId, claim.revision,
            claim, SalesSnapshotCodec.encodeReturnClaim(claim)))
        val custodian = rootDataRow(LocalSalesStoreProfile.CUSTODIAN_KIND, owner, LocalCustodianState(LocalSalesScope.from(scope), owner,
            Revision(0)), Revision(0), LocalCustodianState.serializer())
        val work = rootDataRow(LocalSalesStoreProfile.WORK_KIND, owner, LocalWorkState(LocalSalesScope.from(scope), owner, Revision(0)),
            Revision(0), LocalWorkState.serializer())
        val priorWork = rootDataRow(LocalSalesStoreProfile.WORK_KIND, priorOwner,
            LocalWorkState(LocalSalesScope.from(scope), priorOwner, Revision(1), returnIds = listOf(priorReturnId)), Revision(1), LocalWorkState.serializer())
        val request = LocalReturnCaseRequestV1.Accept(caseId, cut.row.value.revision, decisionOperation, entitlement.revision, claim.revision,
            returnId, LocalReturnDispositionV1.INSPECTED_SELLABLE, inspection.evidence)
        return BoundData(prior, original, qualified, cut, custodian, work, priorWork, request, identity(acceptOperation, request))
    }

    private fun initialAllocationData(): ReceiptEntitlement {
        val tax = TaxSnapshot(id(774), Revision(1), "ordinary_clothing_unchanged", PercentageRate.parse("14"))
        val quote = OperationalPricingEngine.calculate(PricingRequest(id(775), Revision(0), Currency.EGP,
            listOf(ResolvedPriceLine(id(776), portionId, Revision(1), quantity(6), UnitPrice.parse(Currency.EGP, "100"),
                TaxInclusion.EXCLUSIVE, tax)), charges = listOf(
                PricingCharge.Percentage(chargeId = packagingId, revision = Revision(1), kind = ChargeKind.PACKAGING,
                    rate = PercentageRate.parse("3"), eligiblePortionIds = setOf(portionId), tax = tax,
                    refundTreatment = ChargeRefundTreatment.PROPORTIONAL_WITH_ITEMS),
                PricingCharge.Fixed(deliveryId, Revision(1), ChargeKind.DELIVERY, UnitPrice.parse(Currency.EGP, "7"),
                    TaxInclusion.EXCLUSIVE, tax, ChargeRefundTreatment.NEVER))))
        val payments = listOf(payment(cashPaymentId, Tender.Cash, 36_000), payment(cardPaymentId,
            Tender.External(id(777), Revision(1), ExternalTenderKind.CARD, false, false), 35_250))
        val allocations = quote.components.map { component ->
            val cash = if (component.kind == PriceComponentKind.ITEM_NET) 36_000L else 0L
            OriginalComponentTenderAllocation(component.key, listOf(OriginalTenderShare(cashPaymentId, money(cash)),
                OriginalTenderShare(cardPaymentId, component.posted - money(cash))))
        }
        val sale = PaidSale(saleId, scope, id(778), saleOwner, saleOwner, id(779), quote, id(781), payments, allocations)
        return ReceiptEntitlement(sale, Revision(0), listOf(quote.openReturnCursor(portionId)), allocations)
    }

    private fun payment(id: DomainId, tender: Tender, amount: Long): PaymentRecord {
        val evidence = CashierEvidence(id, saleOwner, at("2026-10-10T09:00:00.000Z").value)
        return PaymentRecord(id, OperationId(id.value), OperationId(id.value), this.id(782), saleOwner, this.id(779), null,
            tender, money(amount), if (tender == Tender.Cash) money(amount) else null, if (tender == Tender.Cash) money(0) else null,
            evidence, if (tender is Tender.External) ExternalTenderEvidence(evidence, null, null) else null)
    }
    private fun claimEvidence(actor: DomainId, claimedAt: UtcInstant) = ReturnClaimEvidence(id(771), Revision(1), receivedAt.value,
        claimedAt.value, null, "ORDINARY_REVIEW", CashierEvidence(id(783), actor, claimedAt.value))
    private fun identity(operation: OperationId, request: LocalReturnCaseRequestV1) = LocalReturnCaseProductionIdentity(scope, owner, operation,
        semanticDigest(scope, owner, LocalReturnCaseProfileV1.COMMAND, LocalReturnCaseCommandCodecV1.encode(request)))
    private fun reference(suffix: Int) = LocalReturnCaseSourceReferenceV1("ordinary.return.data", id(suffix), "ordinary.return.data/v1", Revision(1),
        sha256("Opaque allocation DATA coordinate $suffix".encodeToByteArray()), OperationId(id(suffix).value), owner, "sales.return.case.write", 1)
    private fun <T> snapshotRow(kind: String, id: DomainId, revision: Revision, value: T, body: String): LocalSalesRow<T> {
        val payload = StoredBytes(body.encodeToByteArray())
        return LocalSalesRow(StoredAggregate(AggregateKey(kind, id), revision.next(), LocalSalesStoreProfile.schema(kind), payload,
            sha256(payload.copyBytes())), value)
    }
    private fun <T> rootDataRow(kind: String, id: DomainId, value: T, revision: Revision, serializer: KSerializer<T>) =
        snapshotRow(kind, id, revision, value, localSalesEncode(value, serializer).copyBytes().decodeToString())
    private fun <T> dataRow(change: AggregateChange, storageRevision: Revision, serializer: KSerializer<T>): LocalSalesRow<T> =
        LocalSalesRow(StoredAggregate(change.key, storageRevision, change.schema, change.payload, sha256(change.payload.copyBytes())),
            localSalesDecode(change.payload, serializer))
}
