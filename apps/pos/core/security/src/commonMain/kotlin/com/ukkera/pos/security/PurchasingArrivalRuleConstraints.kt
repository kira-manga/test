package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Independently expected coordinates for one explicit rule. The public policy model has one
 * signed publication revision; this value does not invent a separate policy revision.
 * Construction neither selects a fallback rule nor authenticates the publication or time.
 */
public data class PurchasingArrivalRuleSelection(
    public val scope: BranchScope,
    public val policyId: DomainId,
    public val publicationId: DomainId,
    public val publicationRevision: Revision,
    public val ruleId: DomainId?,
    public val expectedValidity: PurchasingValidityV1,
) : PurchasingUntrustedValueV1()

/**
 * The public original-file manifest fields needed for exact comparison. These values do not
 * authenticate the supplier, establish byte/history custody or qualify evidence for an arrival.
 * Supplier revision at capture is not available in the public original-file model.
 */
public data class PurchasingArrivalDocumentReference(
    public val scope: BranchScope,
    public val supplierId: DomainId,
    public val documentId: DomainId,
    public val storageRevision: Revision,
    public val exactBytesDigest: PayloadDigest,
    public val byteLength: Int,
    public val media: PurchasingEvidenceMediaV1,
    public val purpose: PurchasingEvidencePurposeV1,
) : PurchasingUntrustedValueV1()

/** An explicit action selection, including when the rule permits zero documentary originals. */
public data class PurchasingArrivalRetentionSelection(
    public val classId: DomainId,
    public val revision: Revision,
) : PurchasingUntrustedValueV1()

public enum class PurchasingArrivalRuleConstraintResult {
    MISMATCH,
    /** Agreement of supplied constraints only; never authority, qualification or a commit permit. */
    NO_MISMATCH,
}

/**
 * Pure, rejection-only comparison for cost-free arrival rules. The explicit rule must be the
 * complete body in the supplied publication; selectors and actor/evidence choices from other
 * rules are never merged. Expected requester, employee-policy revision, authorization
 * requirement, catalog and ordered document selections must be materialized independently
 * from the original normalized operation, rather than copied from the values being checked.
 *
 * NO_MISMATCH grants no authority, consumes no approval, qualifies no source and writes no
 * effects. The protected action separately establishes authenticated current policy/source
 * loading, qualified current time, each actor's current grants/session/device state, real
 * approval validity/consumption and original byte/history custody in its transaction. Exact
 * validity fields are compared here without defining a new clock or expiry boundary.
 *
 * Public supplier/SKU/catalog references have no scope, and the public policy has no separate
 * policy revision. Missing company/source and retention-policy provenance remains an external
 * refusal gate. The in-publication retention reference below does not establish installation,
 * assign a duration, authorize disposal or create immutable evidence-use/arrival history.
 */
public object PurchasingArrivalRuleConstraints {
    public fun compare(
        policy: PurchasingPolicyPublicationV1,
        selection: PurchasingArrivalRuleSelection?,
        rule: PurchasingPolicyRuleV1?,
        action: PurchasingRuleActionV1,
        supplier: PurchasingSupplierReferenceV1,
        products: List<PurchasingSkuReferenceV1>,
        expectedCatalog: PurchasingCatalogReferenceV1?,
        selectedDocuments: List<PurchasingArrivalDocumentReference>,
        originalDocuments: List<PurchasingArrivalDocumentReference>,
        retention: PurchasingArrivalRetentionSelection?,
        authorization: AuthorizedAction,
        originalRequesterId: DomainId,
        expectedRequesterPolicyRevision: Revision,
        requirement: AuthorizationRequirement,
    ): PurchasingArrivalRuleConstraintResult {
        val expected = selection ?: return PurchasingArrivalRuleConstraintResult.MISMATCH
        val ruleId = expected.ruleId ?: return PurchasingArrivalRuleConstraintResult.MISMATCH
        val selectedRule = rule ?: return PurchasingArrivalRuleConstraintResult.MISMATCH
        if (authorization.principalKind != PrincipalKind.LOCAL_EMPLOYEE ||
            requirement.permission != Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM) ||
            requirement.binding?.operationId == null || requirement.limitUse != LimitUse.NotApplicable ||
            requirement.scope != expected.scope ||
            authorization.policyRevision != expectedRequesterPolicyRevision ||
            !authorization.isBoundTo(requirement, originalRequesterId)
        ) return PurchasingArrivalRuleConstraintResult.MISMATCH
        if (action !in arrivalActions || policy.branch != expected.scope ||
            policy.policyId != expected.policyId || policy.publicationId != expected.publicationId ||
            policy.revision != expected.publicationRevision ||
            policy.validity.validFrom != expected.expectedValidity.validFrom ||
            policy.validity.validUntil != expected.expectedValidity.validUntil
        ) return PurchasingArrivalRuleConstraintResult.MISMATCH

        val publicationRule = policy.rules.singleOrNull { it.ruleId == ruleId }
            ?: return PurchasingArrivalRuleConstraintResult.MISMATCH
        if (selectedRule.ruleId != ruleId || selectedRule.action != action ||
            selectedRule.limits !is PurchasingLimitsV1.NoMonetaryEffect ||
            selectedRule.acquisition != PurchasingAcquisitionProfileV1.NOT_APPLICABLE ||
            !sameRuleBody(policy, publicationRule, selectedRule)
        ) return PurchasingArrivalRuleConstraintResult.MISMATCH
        if (selectedRule.selection.suppliers.none {
                it.supplierId == supplier.supplierId && it.revision == supplier.revision
            }
        ) return PurchasingArrivalRuleConstraintResult.MISMATCH

        val catalog = expectedCatalog ?: return PurchasingArrivalRuleConstraintResult.MISMATCH
        if (products.isEmpty() || products.size > LocalCompanyPolicyFormatV1.MAX_SELECTORS ||
            products.map { it.skuId }.distinct().size != products.size ||
            products.any { current ->
                !sameCatalog(current.catalog, catalog) ||
                    selectedRule.selection.products.skus.none { sameProduct(it, current) }
            }
        ) return PurchasingArrivalRuleConstraintResult.MISMATCH
        // Category references carry no authoritative membership. Every actual product must
        // match an explicit complete SKU selector; names and category-only rules do not suffice.
        if (!routeMatches(selectedRule.approval, authorization))
            return PurchasingArrivalRuleConstraintResult.MISMATCH

        val evidence = selectedRule.evidence
        val selectedRetention = retention ?: return PurchasingArrivalRuleConstraintResult.MISMATCH
        if (selectedRetention.classId != evidence.retentionClassId ||
            selectedRetention.revision != evidence.retentionClassRevision ||
            policy.retentionClasses.none {
                it.classId == selectedRetention.classId && it.revision == selectedRetention.revision
            }
        ) return PurchasingArrivalRuleConstraintResult.MISMATCH
        if (selectedDocuments.size !in evidence.minimumCount..evidence.maximumCount ||
            selectedDocuments.size > LocalCompanyPolicyFormatV1.MAX_EVIDENCE_MANIFESTS ||
            originalDocuments.size != selectedDocuments.size ||
            selectedDocuments.map { it.documentId }.distinct().size != selectedDocuments.size
        ) return PurchasingArrivalRuleConstraintResult.MISMATCH
        for (index in selectedDocuments.indices) {
            val selected = selectedDocuments[index]
            val original = originalDocuments[index]
            if (selected != original || original.scope != expected.scope ||
                original.supplierId != supplier.supplierId || original.storageRevision.value < 1 ||
                original.byteLength < 1 || original.media !in evidence.media ||
                original.purpose !in evidence.purposes
            ) return PurchasingArrivalRuleConstraintResult.MISMATCH
        }
        return PurchasingArrivalRuleConstraintResult.NO_MISMATCH
    }

    private val arrivalActions: Set<PurchasingRuleActionV1> = setOf(
        PurchasingRuleActionV1.ARRIVAL_RECORD,
        PurchasingRuleActionV1.ARRIVAL_QUALIFY,
        PurchasingRuleActionV1.ARRIVAL_REJECT,
        PurchasingRuleActionV1.ARRIVAL_DISPATCH,
    )

    private fun sameCatalog(left: PurchasingCatalogReferenceV1, right: PurchasingCatalogReferenceV1): Boolean =
        left.publicationId == right.publicationId && left.revision == right.revision

    private fun sameProduct(selected: PurchasingSkuReferenceV1, actual: PurchasingSkuReferenceV1): Boolean =
        selected.skuId == actual.skuId && selected.skuRevision == actual.skuRevision &&
            selected.productId == actual.productId && selected.productRevision == actual.productRevision &&
            sameCatalog(selected.catalog, actual.catalog)

    private fun selectedActor(selection: PurchasingActorSelectionV1, actorId: DomainId): Boolean = when (selection) {
        // Only this selector constraint agrees; separate current grants remain mandatory.
        PurchasingActorSelectionV1.CurrentBranchGrantees -> true
        is PurchasingActorSelectionV1.NamedEmployees -> actorId in selection.employeeIds
    }

    private fun routeMatches(route: PurchasingApprovalRouteV1, proof: AuthorizedAction): Boolean {
        val approver = proof.approverId
        return when (route) {
            is PurchasingApprovalRouteV1.Direct -> proof.approvalId == null && approver == null &&
                proof.approverPolicyRevision == null && selectedActor(route.performers, proof.actorId)
            is PurchasingApprovalRouteV1.Separate -> proof.approvalId != null && approver != null &&
                proof.approverPolicyRevision != null && proof.actorId != approver &&
                selectedActor(route.requesters, proof.actorId) && selectedActor(route.approvers, approver)
            is PurchasingApprovalRouteV1.NamedSelf -> proof.approvalId != null && approver != null &&
                proof.approverPolicyRevision != null && selectedActor(route.requesters, proof.actorId) &&
                selectedActor(route.approvers, approver) &&
                (proof.actorId != approver || proof.actorId in route.namedEmployeeIds)
        }
    }

    /** Compare the entire rule using the existing pure canonical public format, not object identity. */
    private fun sameRuleBody(
        policy: PurchasingPolicyPublicationV1,
        publicationRule: PurchasingPolicyRuleV1,
        selectedRule: PurchasingPolicyRuleV1,
    ): Boolean = try {
        val original = ruleBytes(policy, publicationRule)
        try {
            val selected = ruleBytes(policy, selectedRule)
            try { original.contentEquals(selected) } finally { selected.fill(0) }
        } finally { original.fill(0) }
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun ruleBytes(policy: PurchasingPolicyPublicationV1, rule: PurchasingPolicyRuleV1): ByteArray =
        LocalCompanyPolicyWireV1.encodePolicyBody(PurchasingPolicyPublicationV1(
            policy.branch, policy.root, policy.policyId, policy.publicationId, policy.revision,
            policy.predecessor, policy.author, policy.validity, policy.retentionClasses, listOf(rule),
        ))
}
