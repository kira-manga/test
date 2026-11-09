package com.ukkera.pos.catalog.store.independent.arrivalapproval

import com.ukkera.pos.catalog.store.LocalConfigurationProfile
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentProfileV1
import com.ukkera.pos.catalog.store.LocalPurchasingArrivalDocumentUseProfileV2
import com.ukkera.pos.catalog.store.PurchasingArrivalDocumentUseRecordV2
import com.ukkera.pos.catalog.store.purchasingArrivalDocumentaryProjectionV2
import com.ukkera.pos.catalog.store.purchasingDocumentDecode
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

internal enum class ArrivalApprovalMode { DIRECT, SEPARATE, NAMED_SELF }

/** Immutable DATA only; these coordinates are not an issued approval or accepting proof. */
internal data class ArrivalApprovalTuple(
    val approvalId: DomainId?, val approverId: DomainId?, val approverPolicyRevision: Revision?,
)

/**
 * Bindings of unchanged QAM v1 constraint inputs and QAM-02 expected provenance.
 * Fixture SHA-256: 5c944a21d3169e777ebbcc3bbdc34c9b280170472ad716e1a4be6414d51d5a96.
 * Frozen APV-01–03 also require null Direct, actual Separate and nonnull NamedSelf actors.
 *
 * UUIDs bind semantic labels. Organization, SKU revisions, catalog ID, authentication,
 * generation, source-pointer kinds/digests, time/validity, policy predecessor/issuer,
 * reason/digest and zero signature bytes are inert representation carriers. No native
 * source, signature validation, policy installation, grant, issuance or consumption is
 * supplied. The retention duration is a zero carrier, not a statutory or configured
 * outcome oracle. The operation requirement is content-bound DATA for this projection,
 * not the production normalized command or an AuthorizedAction.
 *
 * Bound: literal route consistency and prospective documentary representation only.
 * All complete QAM/APV/UAD2 cases remain NOT_RUN. Actual original-source qualification,
 * native approval consumption/CAS, atomic custody/history/result/audit/outbox, current
 * grants, protected retrieval/RPC, persistence and restart remain UNBOUND here.
 */
internal object ArrivalApprovalFrozenInputs {
    private fun id(ordinal: Int): DomainId =
        DomainId("40000000-0000-4000-8000-${ordinal.toString().padStart(12, '0')}")

    val scope = BranchScope(id(1), id(2), id(3))
    val requester = id(10)
    val approver = id(11)
    val requesterPolicyRevision = Revision(11)
    val approverPolicyRevision = Revision(12)
    val separateApprovalId = id(90)
    val selfApprovalId = id(91)
    val operationId = OperationId(id(80).value)
    val otherOperationId = OperationId(id(81).value)
    val targetId = id(82)
    val supplierId = id(32)
    val retentionClassId = id(60)
    val differentRetentionClassId = id(61)
    val jpegId = id(70)
    val pdfId = id(71)
    val contentDigestCarrier = PayloadDigest("1".repeat(64))
    val authenticationIdCarrier = id(22)
    val generationCarrier = SessionGeneration(1)
    val recordedAtCarrier = UtcInstant.parseCanonical("2026-11-01T00:05:00Z")

    val direct = ArrivalApprovalTuple(null, null, null)
    val separate = ArrivalApprovalTuple(separateApprovalId, approver, approverPolicyRevision)
    val namedSelf = ArrivalApprovalTuple(selfApprovalId, requester, requesterPolicyRevision)

    fun partialTuples(complete: ArrivalApprovalTuple = separate): List<ArrivalApprovalTuple> =
        (0 until 7).map { mask -> ArrivalApprovalTuple(
            if (mask and 1 != 0) complete.approvalId else null,
            if (mask and 2 != 0) complete.approverId else null,
            if (mask and 4 != 0) complete.approverPolicyRevision else null,
        ) }

    fun nonemptyTuples(complete: ArrivalApprovalTuple = separate): List<ArrivalApprovalTuple> =
        partialTuples(complete).drop(1) + complete

    fun route(mode: ArrivalApprovalMode, namedAccount: DomainId = requester): PurchasingApprovalRouteV1 = when (mode) {
        ArrivalApprovalMode.DIRECT -> PurchasingApprovalRouteV1.Direct(PurchasingActorSelectionV1.CurrentBranchGrantees)
        ArrivalApprovalMode.SEPARATE -> PurchasingApprovalRouteV1.Separate(
            PurchasingActorSelectionV1.CurrentBranchGrantees, PurchasingActorSelectionV1.CurrentBranchGrantees)
        ArrivalApprovalMode.NAMED_SELF -> PurchasingApprovalRouteV1.NamedSelf(
            PurchasingActorSelectionV1.CurrentBranchGrantees, PurchasingActorSelectionV1.CurrentBranchGrantees,
            listOf(namedAccount))
    }

    fun documents(): List<LocalArrivalDocumentSelectionV2> = listOf(
        LocalArrivalDocumentSelectionV2(scope, supplierId, jpegId, Revision(1),
            PayloadDigest("3f93bab029856dfaa0d8d9eceeceaa5977e4381e9209ed61f6f7af51c622147f"),
            155, "JPEG", "SUPPLIER_DELIVERY"),
        LocalArrivalDocumentSelectionV2(scope, supplierId, pdfId, Revision(1),
            PayloadDigest("96c72b8140b230b776d119cce2bac959aa9f2211af5e85e9b5652571568c1728"),
            640, "PDF", "SUPPLIER_DELIVERY"),
    )

    fun products(): List<LocalArrivalProductReferenceV2> = listOf(
        LocalArrivalProductReferenceV2(id(43), Revision(4), id(40), Revision(4)),
        LocalArrivalProductReferenceV2(id(44), Revision(5), id(41), Revision(5)),
    )

    private fun source(kind: String, id: DomainId, revision: Revision = Revision(1)) =
        LocalArrivalSourceSelectionV2(kind, id, revision, contentDigestCarrier)

    fun selection(mode: ArrivalApprovalMode,
        documents: List<LocalArrivalDocumentSelectionV2> = documents(),
        retention: LocalArrivalRetentionSelectionV2 = LocalArrivalRetentionSelectionV2(retentionClassId, Revision(2)),
    ): LocalArrivalEvidenceSelectionV2 = LocalArrivalEvidenceSelectionV2("SYN-QAM-ARRIVAL-001",
        LocalArrivalPolicySelectionV2(scope, id(30), id(31), Revision(8),
            source("arrivalapproval.policy-active-carrier", scope.branchId),
            source("arrivalapproval.policy-publication-carrier", id(31)),
            UtcInstant.parseCanonical("2026-11-01T00:00:00Z"), null),
        LocalArrivalCatalogSelectionV2(scope, id(34), id(35), Revision(7),
            source("arrivalapproval.catalog-active-carrier", scope.branchId),
            source("arrivalapproval.catalog-publication-carrier", id(35))),
        ruleId(mode), documents, retention)

    fun sources(selection: LocalArrivalEvidenceSelectionV2): List<LocalStockSourceStamp> =
        (listOf(selection.policy.activePointer, selection.policy.publication,
            selection.catalog.activePointer, selection.catalog.publication,
            source(LocalConfigurationProfile.SUPPLIER, supplierId, Revision(3))) +
            selection.documents.map { source(LocalPurchasingDocumentProfileV1.ORIGINAL, it.documentId) })
            .map(LocalArrivalSourceSelectionV2::stamp)

    private fun ruleId(mode: ArrivalApprovalMode): DomainId = when (mode) {
        ArrivalApprovalMode.DIRECT -> id(50)
        ArrivalApprovalMode.SEPARATE -> id(51)
        ArrivalApprovalMode.NAMED_SELF -> id(52)
    }

    private fun policy(mode: ArrivalApprovalMode, namedAccount: DomainId): PurchasingSignedPolicyV1 {
        val catalog = PurchasingCatalogReferenceV1(id(35), Revision(7))
        val evidence = PurchasingEvidenceRuleV1(
            if (mode == ArrivalApprovalMode.DIRECT) PurchasingEvidenceDecisionV1.NOT_REQUIRED
            else PurchasingEvidenceDecisionV1.REQUIRED,
            listOf(PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY),
            listOf(PurchasingEvidenceMediaV1.JPEG, PurchasingEvidenceMediaV1.PDF),
            if (mode == ArrivalApprovalMode.DIRECT) 0 else 1, 2, retentionClassId, Revision(2))
        val rule = PurchasingPolicyRuleV1(ruleId(mode), id(55), PurchasingRuleActionV1.ARRIVAL_QUALIFY,
            PurchasingSelectionV1(listOf(PurchasingSupplierReferenceV1(supplierId, Revision(3))),
                PurchasingProductSelectionV1(products().map {
                    PurchasingSkuReferenceV1(it.skuId, it.skuRevision, it.productId, it.productRevision, catalog)
                }, emptyList())), PurchasingLimitsV1.NoMonetaryEffect, evidence,
            route(mode, namedAccount), PurchasingAcquisitionProfileV1.NOT_APPLICABLE)
        val body = PurchasingPolicyPublicationV1(scope, PurchasingRootReferenceV1(id(100), contentDigestCarrier),
            id(30), id(31), Revision(8), PurchasingPublicationReferenceV1(id(101), Revision(7), contentDigestCarrier),
            PurchasingDelegatedIssuerV1(id(102), Revision(1), id(103), id(104),
                PurchasingPublicationReferenceV1(id(105), Revision(1), contentDigestCarrier)),
            PurchasingValidityV1(UtcInstant.parseCanonical("2026-11-01T00:00:00Z"), null),
            listOf(PurchasingRetentionClassV1(retentionClassId, Revision(2), 0, emptyList())), listOf(rule))
        return PurchasingSignedPolicyV1(body, PurchasingSignatureV1(ByteArray(64)), PurchasingDelegationDocumentsV1(emptyList()))
    }

    fun project(mode: ArrivalApprovalMode, tuple: ArrivalApprovalTuple = direct,
        operation: OperationId = operationId, actor: DomainId = requester,
        selected: LocalArrivalEvidenceSelectionV2 = selection(mode),
        selectedProducts: List<LocalArrivalProductReferenceV2> = products(),
        originals: List<LocalArrivalDocumentSelectionV2> = documents(),
        originalSources: List<LocalStockSourceStamp> = sources(selected),
        namedAccount: DomainId = requester, omitDirectTupleArguments: Boolean = false,
    ): LocalArrivalDocumentaryEffectsV2 {
        val requirement = AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM),
            scope, ActionBinding(targetId, Revision(3), contentDigestCarrier, operation, "syn.qam.arrival"),
            LimitUse.NotApplicable, OwnerConstraint.Unrestricted)
        val policy = policy(mode, namedAccount)
        if (omitDirectTupleArguments) {
            check(mode == ArrivalApprovalMode.DIRECT && tuple == direct)
            return purchasingArrivalDocumentaryProjectionV2(scope, operation, actor, LocalUnpricedArrivalProfileV2.QUALIFY,
                contentDigestCarrier, authenticationIdCarrier, generationCarrier, requirement, requesterPolicyRevision,
                recordedAtCarrier, selected, policy, selectedProducts, originals, originalSources)
        }
        return purchasingArrivalDocumentaryProjectionV2(scope, operation, actor, LocalUnpricedArrivalProfileV2.QUALIFY,
            contentDigestCarrier, authenticationIdCarrier, generationCarrier, requirement, requesterPolicyRevision,
            recordedAtCarrier, selected, policy, selectedProducts, originals, originalSources,
            tuple.approvalId, tuple.approverId, tuple.approverPolicyRevision)
    }

    fun record(effects: LocalArrivalDocumentaryEffectsV2): PurchasingArrivalDocumentUseRecordV2 =
        purchasingDocumentDecode(effects.changes.single().payload, PurchasingArrivalDocumentUseRecordV2.serializer(),
            LocalPurchasingArrivalDocumentUseProfileV2.MAX_ROW_BYTES)
}
