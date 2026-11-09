package com.ukkera.pos.security.independent.qualifiedarrivalmatchingv1

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

internal enum class QamRoute { DIRECT, SEPARATE, NAMED_SELF }

/** Synthetic bindings for the unchanged qualified-arrival-matching-v1 fixture symbols. */
internal object QamInputs {
    fun id(ordinal: Int): DomainId = DomainId("40000000-0000-4000-8000-${ordinal.toString().padStart(12, '0')}")

    val organization = id(1)
    val company = id(2)
    val branch = id(3)
    val otherCompany = id(4)
    val otherBranch = id(5)
    val scope = BranchScope(organization, company, branch)
    val otherBranchScope = BranchScope(organization, company, otherBranch)
    val otherCompanyScope = BranchScope(organization, otherCompany, otherBranch)
    val requester = id(10)
    val approver = id(11)
    val otherRequester = id(12)
    val device = id(20)
    val boot = id(21)
    val policyId = id(30)
    val publicationId = id(31)
    val supplierId = id(32)
    val otherSupplierId = id(33)
    val catalogReleaseId = id(35)
    val otherCatalogReleaseId = id(36)
    val riceProductId = id(40)
    val oilProductId = id(41)
    val excludedProductId = id(42)
    val riceSkuId = id(43)
    val oilSkuId = id(44)
    val otherSkuId = id(45)
    val categoryId = id(46)
    val directRuleId = id(50)
    val separateRuleId = id(51)
    val namedSelfRuleId = id(52)
    val unknownRuleId = id(53)
    val otherRuleId = id(54)
    val delegationGrantId = id(55)
    val retentionClassId = id(60)
    val otherRetentionClassId = id(61)
    val jpegId = id(70)
    val pdfId = id(71)
    val overflowId = id(72)
    val changedOriginalId = id(73)
    val operationId = OperationId(id(80).value)
    val otherOperationId = OperationId(id(81).value)
    val targetId = id(82)
    val otherTargetId = id(83)
    val separateApprovalId = id(90)
    val selfApprovalId = id(91)
    val otherApprovalId = id(92)
    val requesterPolicyRevision = Revision(11)
    val approverPolicyRevision = Revision(12)
    val publicationRevision = Revision(8)
    val businessAction = PurchasingRuleActionV1.ARRIVAL_QUALIFY
    val publicPermission = Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM)

    // Explicit syntactic support values, never a signed publication, installed policy or deadline.
    private val supportDigest = PayloadDigest("1".repeat(64))
    fun validity(): PurchasingValidityV1 = PurchasingValidityV1(
        UtcInstant.parseCanonical("2026-11-01T00:00:00Z"),
        UtcInstant.parseCanonical("2026-11-02T00:00:00Z"),
    )

    fun catalog(releaseId: DomainId = catalogReleaseId, revision: Revision = Revision(7)):
        PurchasingCatalogReferenceV1 = PurchasingCatalogReferenceV1(releaseId, revision)

    fun supplier(id: DomainId = supplierId, revision: Revision = Revision(3)):
        PurchasingSupplierReferenceV1 = PurchasingSupplierReferenceV1(id, revision)

    fun products(): List<PurchasingSkuReferenceV1> = listOf(
        PurchasingSkuReferenceV1(riceSkuId, Revision(4), riceProductId, Revision(4), catalog()),
        PurchasingSkuReferenceV1(oilSkuId, Revision(5), oilProductId, Revision(5), catalog()),
    )

    fun productLike(source: PurchasingSkuReferenceV1, skuId: DomainId = source.skuId,
        skuRevision: Revision = source.skuRevision, productId: DomainId = source.productId,
        productRevision: Revision = source.productRevision,
        catalog: PurchasingCatalogReferenceV1 = source.catalog): PurchasingSkuReferenceV1 =
        PurchasingSkuReferenceV1(skuId, skuRevision, productId, productRevision, catalog)

    /** Called separately for operation selections and resolved metadata; neither list aliases the other. */
    fun documents(): List<PurchasingArrivalDocumentReference> = listOf(
        PurchasingArrivalDocumentReference(scope, supplierId, jpegId, Revision(1),
            PayloadDigest("3f93bab029856dfaa0d8d9eceeceaa5977e4381e9209ed61f6f7af51c622147f"),
            155, PurchasingEvidenceMediaV1.JPEG, PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY),
        PurchasingArrivalDocumentReference(scope, supplierId, pdfId, Revision(1),
            PayloadDigest("96c72b8140b230b776d119cce2bac959aa9f2211af5e85e9b5652571568c1728"),
            640, PurchasingEvidenceMediaV1.PDF, PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY),
    )

    fun rule(route: QamRoute, ruleId: DomainId = QamInputs.ruleId(route),
        suppliers: List<PurchasingSupplierReferenceV1> = listOf(supplier()),
        products: PurchasingProductSelectionV1 = PurchasingProductSelectionV1(QamInputs.products(), emptyList()),
        approval: PurchasingApprovalRouteV1 = approvalRoute(route),
        evidence: PurchasingEvidenceRuleV1 = evidence(route)): PurchasingPolicyRuleV1 =
        PurchasingPolicyRuleV1(ruleId, delegationGrantId, businessAction,
            PurchasingSelectionV1(suppliers, products), PurchasingLimitsV1.NoMonetaryEffect,
            evidence, approval, PurchasingAcquisitionProfileV1.NOT_APPLICABLE)

    fun ruleLike(source: PurchasingPolicyRuleV1,
        suppliers: List<PurchasingSupplierReferenceV1> = source.selection.suppliers,
        products: PurchasingProductSelectionV1 = source.selection.products,
        approval: PurchasingApprovalRouteV1 = source.approval,
        evidence: PurchasingEvidenceRuleV1 = source.evidence): PurchasingPolicyRuleV1 =
        PurchasingPolicyRuleV1(source.ruleId, source.delegationGrantId, source.action,
            PurchasingSelectionV1(suppliers, products), source.limits, evidence, approval, source.acquisition)

    fun ruleId(route: QamRoute): DomainId = when (route) {
        QamRoute.DIRECT -> directRuleId
        QamRoute.SEPARATE -> separateRuleId
        QamRoute.NAMED_SELF -> namedSelfRuleId
    }

    fun approvalRoute(route: QamRoute): PurchasingApprovalRouteV1 = when (route) {
        QamRoute.DIRECT -> PurchasingApprovalRouteV1.Direct(PurchasingActorSelectionV1.CurrentBranchGrantees)
        QamRoute.SEPARATE -> PurchasingApprovalRouteV1.Separate(
            PurchasingActorSelectionV1.CurrentBranchGrantees, PurchasingActorSelectionV1.CurrentBranchGrantees)
        QamRoute.NAMED_SELF -> PurchasingApprovalRouteV1.NamedSelf(
            PurchasingActorSelectionV1.CurrentBranchGrantees, PurchasingActorSelectionV1.CurrentBranchGrantees,
            listOf(requester))
    }

    fun evidence(route: QamRoute, retentionId: DomainId = retentionClassId,
        retentionRevision: Revision = Revision(2)): PurchasingEvidenceRuleV1 = PurchasingEvidenceRuleV1(
        if (route == QamRoute.DIRECT) PurchasingEvidenceDecisionV1.NOT_REQUIRED else PurchasingEvidenceDecisionV1.REQUIRED,
        listOf(PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY),
        listOf(PurchasingEvidenceMediaV1.JPEG, PurchasingEvidenceMediaV1.PDF),
        if (route == QamRoute.DIRECT) 0 else 1, 2, retentionId, retentionRevision,
    )

    fun policy(rules: List<PurchasingPolicyRuleV1> = listOf(
        rule(QamRoute.DIRECT), rule(QamRoute.SEPARATE), rule(QamRoute.NAMED_SELF),
        rule(QamRoute.DIRECT, otherRuleId)), branch: BranchScope = scope,
        policyId: DomainId = this.policyId, publicationId: DomainId = this.publicationId,
        revision: Revision = publicationRevision, validity: PurchasingValidityV1 = QamInputs.validity(),
        retentionClasses: List<PurchasingRetentionClassV1> = listOf(retentionClass())):
        PurchasingPolicyPublicationV1 = PurchasingPolicyPublicationV1(
        branch, PurchasingRootReferenceV1(id(100), supportDigest), policyId, publicationId, revision,
        if (revision.value == 1L) null else PurchasingPublicationReferenceV1(id(101), Revision(revision.value - 1), supportDigest),
        PurchasingDelegatedIssuerV1(id(102), Revision(1), id(103), id(104),
            PurchasingPublicationReferenceV1(id(105), Revision(1), supportDigest)),
        validity, retentionClasses, rules,
    )

    fun policyLike(source: PurchasingPolicyPublicationV1,
        rules: List<PurchasingPolicyRuleV1> = source.rules, branch: BranchScope = source.branch,
        policyId: DomainId = source.policyId, publicationId: DomainId = source.publicationId,
        revision: Revision = source.revision, retentionClasses: List<PurchasingRetentionClassV1> = source.retentionClasses):
        PurchasingPolicyPublicationV1 = policy(rules, branch, policyId, publicationId, revision, source.validity, retentionClasses)

    fun retentionClass(id: DomainId = retentionClassId, revision: Revision = Revision(2)):
        PurchasingRetentionClassV1 = PurchasingRetentionClassV1(id, revision, 0, emptyList())

    fun selection(route: QamRoute): PurchasingArrivalRuleSelection = PurchasingArrivalRuleSelection(
        scope, policyId, publicationId, publicationRevision, ruleId(route), validity())

    suspend fun baseline(route: QamRoute = QamRoute.DIRECT,
        selectedDocuments: List<PurchasingArrivalDocumentReference> = documents(),
        originalDocuments: List<PurchasingArrivalDocumentReference> = documents()): QamInvocation {
        val operation = QamOperation(route, scope, operationId, targetId, Revision(3), requester,
            ruleId(route), supplier(), products(), selectedDocuments,
            PurchasingArrivalRetentionSelection(retentionClassId, Revision(2)))
        val provision = QamProofs.provision(route, operation.requirement())
        return QamInvocation(policy(), selection(route), rule(route), businessAction, supplier(), products(), catalog(),
            selectedDocuments, originalDocuments, operation.retention, provision.authorization, requester,
            requesterPolicyRevision, operation.requirement(), operation, provision)
    }

    fun scopeText(scope: BranchScope): String = "${scope.organizationId.value}/${scope.companyId.value}/${scope.branchId.value}"
    fun catalogText(catalog: PurchasingCatalogReferenceV1): String = "${catalog.publicationId.value}/${catalog.revision.value}"
    fun productText(product: PurchasingSkuReferenceV1): String =
        "${product.skuId.value}/${product.skuRevision.value}/${product.productId.value}/${product.productRevision.value}/${catalogText(product.catalog)}"
    fun documentText(document: PurchasingArrivalDocumentReference): String =
        "${scopeText(document.scope)}/${document.supplierId.value}/${document.documentId.value}/${document.storageRevision.value}/" +
            "${document.byteLength}/${document.exactBytesDigest.value}/${document.media.name}/${document.purpose.name}"
}

/** Unit normalization of the frozen semantic inputs; not the production arrival wire codec. */
internal data class QamOperation(
    val route: QamRoute, val scope: BranchScope, val operationId: OperationId,
    val targetId: DomainId, val targetRevision: Revision, val requesterId: DomainId,
    val ruleId: DomainId, val supplier: PurchasingSupplierReferenceV1,
    val products: List<PurchasingSkuReferenceV1>, val documents: List<PurchasingArrivalDocumentReference>,
    val retention: PurchasingArrivalRetentionSelection,
) {
    fun requirement(): AuthorizationRequirement {
        val bytes = buildString {
            append("qualified-arrival-matching-v1.shared\n")
            append("scope=${QamInputs.scopeText(scope)}\nrequester=${requesterId.value}\n")
            append("action=UNPRICED_ARRIVAL\nrule=${ruleId.value}\n")
            append("supplier=${supplier.supplierId.value}/${supplier.revision.value}\n")
            products.forEachIndexed { index, product -> append("product[$index]=${QamInputs.productText(product)}\n") }
            documents.forEachIndexed { index, document -> append("original[$index]=${QamInputs.documentText(document)}\n") }
            append("retention=${retention.classId.value}/${retention.revision.value}\n")
        }.encodeToByteArray()
        val digest = try { sha256(bytes) } finally { bytes.fill(0) }
        return AuthorizationRequirement(QamInputs.publicPermission, scope,
            ActionBinding(targetId, targetRevision, digest, operationId, "purchasing.arrival.qualified"),
            LimitUse.NotApplicable, OwnerConstraint.Unrestricted)
    }
}

internal data class QamInvocation(
    val policy: PurchasingPolicyPublicationV1, val selection: PurchasingArrivalRuleSelection?,
    val rule: PurchasingPolicyRuleV1?, val action: PurchasingRuleActionV1,
    val supplier: PurchasingSupplierReferenceV1, val products: List<PurchasingSkuReferenceV1>,
    val expectedCatalog: PurchasingCatalogReferenceV1?,
    val selectedDocuments: List<PurchasingArrivalDocumentReference>,
    val originalDocuments: List<PurchasingArrivalDocumentReference>,
    val retention: PurchasingArrivalRetentionSelection?, val authorization: AuthorizedAction,
    val originalRequesterId: DomainId, val expectedRequesterPolicyRevision: Revision,
    val requirement: AuthorizationRequirement, val operation: QamOperation,
    val provision: QamProvisionedProof,
) {
    fun compare(): PurchasingArrivalRuleConstraintResult = PurchasingArrivalRuleConstraints.compare(
        policy, selection, rule, action, supplier, products, expectedCatalog, selectedDocuments,
        originalDocuments, retention, authorization, originalRequesterId, expectedRequesterPolicyRevision, requirement)

    /** One selected-rule fixture variation is installed in both required body representations. */
    fun replacingRule(updated: PurchasingPolicyRuleV1): QamInvocation = copy(rule = updated,
        policy = QamInputs.policyLike(policy, rules = policy.rules.map { if (it.ruleId == updated.ruleId) updated else it }))

    fun observation(): QamInputObservation {
        fun actors(value: PurchasingActorSelectionV1): String = when (value) {
            PurchasingActorSelectionV1.CurrentBranchGrantees -> "current-branch-grantees"
            is PurchasingActorSelectionV1.NamedEmployees -> value.employeeIds.joinToString { it.value }
        }
        fun route(value: PurchasingApprovalRouteV1): String = when (value) {
            is PurchasingApprovalRouteV1.Direct -> "direct/${actors(value.performers)}"
            is PurchasingApprovalRouteV1.Separate -> "separate/${actors(value.requesters)}/${actors(value.approvers)}"
            is PurchasingApprovalRouteV1.NamedSelf -> "named/${actors(value.requesters)}/${actors(value.approvers)}/" +
                value.namedEmployeeIds.joinToString { it.value }
        }
        val selectedRule = rule
        val ruleFields = if (selectedRule == null) emptyList() else listOf(
            selectedRule.ruleId.value, selectedRule.delegationGrantId.value, selectedRule.action.name,
            selectedRule.selection.suppliers.joinToString { "${it.supplierId.value}/${it.revision.value}" },
            selectedRule.selection.products.skus.joinToString { QamInputs.productText(it) },
            selectedRule.selection.products.categories.joinToString { "${it.categoryId.value}/${it.revision.value}/${QamInputs.catalogText(it.catalog)}" },
            selectedRule.limits::class.toString(), selectedRule.acquisition.name,
            selectedRule.evidence.decision.name, selectedRule.evidence.minimumCount.toString(),
            selectedRule.evidence.maximumCount.toString(), selectedRule.evidence.retentionClassId.value,
            selectedRule.evidence.retentionClassRevision.value.toString(), selectedRule.evidence.media.joinToString(),
            selectedRule.evidence.purposes.joinToString(), route(selectedRule.approval),
        )
        val selected = selection
        val coordinates = if (selected == null) emptyList() else listOf(QamInputs.scopeText(selected.scope),
            selected.policyId.value, selected.publicationId.value, selected.publicationRevision.value.toString(),
            selected.ruleId?.value.orEmpty(), selected.expectedValidity.validFrom.toString(), selected.expectedValidity.validUntil.toString())
        val bytes = LocalCompanyPolicyWireV1.encodePolicyBody(policy)
        val policyBytes = try { bytes.toList() } finally { bytes.fill(0) }
        return QamInputObservation(policyBytes, ruleFields, coordinates, action,
            "${supplier.supplierId.value}/${supplier.revision.value}", products.map { QamInputs.productText(it) },
            expectedCatalog?.let { QamInputs.catalogText(it) }, selectedDocuments.toList(), originalDocuments.toList(),
            retention, QamProofObservation(authorization), originalRequesterId, expectedRequesterPolicyRevision,
            requirement, provision.counts.snapshot())
    }
}

internal data class QamInputObservation(
    val policyBytes: List<Byte>, val ruleFields: List<String>, val selectionFields: List<String>,
    val action: PurchasingRuleActionV1, val supplier: String, val products: List<String>, val catalog: String?,
    val selected: List<PurchasingArrivalDocumentReference>, val originals: List<PurchasingArrivalDocumentReference>,
    val retention: PurchasingArrivalRetentionSelection?, val proof: QamProofObservation,
    val requester: DomainId, val requesterRevision: Revision, val requirement: AuthorizationRequirement,
    val proofCalls: QamProofCallSnapshot,
)

internal data class QamProofObservation(
    val actor: DomainId, val kind: PrincipalKind, val requirement: AuthorizationRequirement,
    val revision: Revision, val authentication: DomainId, val generation: SessionGeneration,
    val approval: DomainId?, val approver: DomainId?, val approverRevision: Revision?,
) {
    constructor(proof: AuthorizedAction) : this(proof.actorId, proof.principalKind, proof.requirement,
        proof.policyRevision, proof.authenticationId, proof.generation, proof.approvalId,
        proof.approverId, proof.approverPolicyRevision)
}
