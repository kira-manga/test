package com.ukkera.pos.security

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Closed, UNTRUSTED purchasing document values. Construction, decoding, IDs, role labels,
 * public points and signature bytes confer no authority, employee grant or stock identity.
 * The original native company owner must separately authenticate the root, enrolled human,
 * purpose, complete delegation chain, current revisions, signatures and qualified time.
 *
 * This is not the installation domain, an employee-policy DTO or an accepting policy source.
 * Collection arguments must already be canonically ordered; nothing is silently sorted,
 * deduplicated, normalized or truncated. Encoding additionally enforces total byte bounds.
 */
public object LocalCompanyPolicyFormatV1 {
    public const val DOMAIN: String = "ukkera.local-company-purchasing/p256/v1"
    public const val VERSION: Int = 1
    public const val MAX_BODY_BYTES: Int = 48 * 1024
    public const val MAX_ENVELOPE_BYTES: Int = 64 * 1024
    public const val MAX_RULES: Int = 64
    public const val MAX_AUTHORITY_GRANTS: Int = 64
    public const val MAX_SELECTORS: Int = 64
    public const val MAX_BRANCHES: Int = 64
    public const val MAX_NAMED_EMPLOYEES: Int = 64
    public const val MAX_RETENTION_CLASSES: Int = 16
    public const val MAX_DELEGATION_DOCUMENTS: Int = 4
    public const val MAX_EVIDENCE_MANIFESTS: Int = 2
}

/** Deliberately prevents automatic document/proof dumps, including from nested values. */
public sealed class PurchasingUntrustedValueV1 {
    final override fun toString(): String = "LocalCompanyPurchasingV1([UNTRUSTED, REDACTED])"
}

/** Shape only: 65-byte SEC1 uncompressed encoding. No curve or key qualification occurs. */
public class PurchasingPublicPointV1(bytes: ByteArray) : PurchasingUntrustedValueV1() {
    private val owned: ByteArray = exactOwnedBytes(bytes, 65)

    init {
        require(owned[0] == 0x04.toByte()) { "Expected uncompressed public-point shape" }
    }

    public fun copyBytes(): ByteArray = owned.copyOf()
}

/**
 * Public signature bytes only. All 64-byte strings are still untrusted. The actual native
 * verifier must enforce P-256 r/s ranges, low-S, SHA-256 and signature validity; this value
 * neither computes nor verifies them and cannot hold a private signing credential.
 */
public class PurchasingSignatureV1(bytes: ByteArray) : PurchasingUntrustedValueV1() {
    private val owned: ByteArray = exactOwnedBytes(bytes, 64)

    public fun copyBytes(): ByteArray = owned.copyOf()
}

/** Fingerprint syntax does not authenticate a root obtained from the same proposed file. */
public class PurchasingRootReferenceV1(
    public val keyId: DomainId,
    public val fingerprint: PayloadDigest,
) : PurchasingUntrustedValueV1()

/** Exact immutable publication coordinates; the supplied digest is not recomputed here. */
public class PurchasingPublicationReferenceV1(
    public val publicationId: DomainId,
    public val revision: Revision,
    public val bodyDigest: PayloadDigest,
) : PurchasingUntrustedValueV1() {
    init {
        requirePositiveRevision(revision)
    }
}

/** A null end is an explicit unbounded validity assertion, never a qualified clock result. */
public class PurchasingValidityV1(
    public val validFrom: UtcInstant,
    public val validUntil: UtcInstant?,
) : PurchasingUntrustedValueV1() {
    init {
        require(validUntil == null || validUntil > validFrom) { "Invalid purchasing validity interval" }
    }
}

public enum class PurchasingAuthorityRoleV1 {
    COMPANY_HEAD,
    DELEGATE,
}

public enum class PurchasingAuthorityPurposeV1 {
    POLICY_PUBLISH,
    SERIAL_PUBLISH,
}

/**
 * The name, role and account coordinates describe an asserted enrollment, not a principal.
 * External account/catalog/reservation revisions retain Revision's nonnegative scalar
 * contract; this format does not invent the future owner's eligible enrollment states.
 */
public class PurchasingNamedAuthorityV1(
    public val authorityId: DomainId,
    public val name: String,
    public val role: PurchasingAuthorityRoleV1,
    public val accountRevision: Revision,
    public val keyId: DomainId,
    public val publicPoint: PurchasingPublicPointV1,
) : PurchasingUntrustedValueV1() {
    init {
        requireBoundedExactText(name, 128, 256)
    }
}

/** Policy/serial authors must name a delegation; no direct-root publishing shortcut exists. */
public class PurchasingDelegatedIssuerV1(
    public val authorityId: DomainId,
    public val accountRevision: Revision,
    public val keyId: DomainId,
    public val delegationId: DomainId,
    public val delegationPublication: PurchasingPublicationReferenceV1,
) : PurchasingUntrustedValueV1()

public sealed class PurchasingAuthorityIssuerV1 : PurchasingUntrustedValueV1() {
    /** Only a delegation body can syntactically name the founding root as issuer. */
    public class Root(public val keyId: DomainId) : PurchasingAuthorityIssuerV1()

    public class Delegated(public val issuer: PurchasingDelegatedIssuerV1) : PurchasingAuthorityIssuerV1()
}

public class PurchasingCatalogReferenceV1(
    public val publicationId: DomainId,
    public val revision: Revision,
) : PurchasingUntrustedValueV1()

public class PurchasingSupplierReferenceV1(
    public val supplierId: DomainId,
    public val revision: Revision,
) : PurchasingUntrustedValueV1()

public class PurchasingSkuReferenceV1(
    public val skuId: DomainId,
    public val skuRevision: Revision,
    public val productId: DomainId,
    public val productRevision: Revision,
    public val catalog: PurchasingCatalogReferenceV1,
) : PurchasingUntrustedValueV1()

/**
 * Exact category ID/revision data ONLY. No display label, membership set or installed
 * category qualification is supplied. A genuine catalog-revision-bound category source
 * remains mandatory before a consumer may interpret this selector.
 */
public class PurchasingCategoryReferenceV1(
    public val categoryId: DomainId,
    public val revision: Revision,
    public val catalog: PurchasingCatalogReferenceV1,
) : PurchasingUntrustedValueV1()

/** Explicit union of named SKUs and category references; an empty union never means all. */
public class PurchasingProductSelectionV1(
    skus: List<PurchasingSkuReferenceV1>,
    categories: List<PurchasingCategoryReferenceV1>,
) : PurchasingUntrustedValueV1() {
    private val ownedSkus: List<PurchasingSkuReferenceV1> =
        orderedSnapshot(skus, 0, LocalCompanyPolicyFormatV1.MAX_SELECTORS) { it.skuId.value }
    private val ownedCategories: List<PurchasingCategoryReferenceV1> =
        orderedSnapshot(categories, 0, LocalCompanyPolicyFormatV1.MAX_SELECTORS) { it.categoryId.value }

    internal val selectorCount: Int = ownedSkus.size + ownedCategories.size

    init {
        require(selectorCount in 1..LocalCompanyPolicyFormatV1.MAX_SELECTORS) { "Invalid product selector count" }
    }

    public val skus: List<PurchasingSkuReferenceV1> get() = ownedSkus.toList()
    public val categories: List<PurchasingCategoryReferenceV1> get() = ownedCategories.toList()
}

/** At most 64 selectors TOTAL across suppliers, SKUs and categories in one complete rule. */
public class PurchasingSelectionV1(
    suppliers: List<PurchasingSupplierReferenceV1>,
    public val products: PurchasingProductSelectionV1,
) : PurchasingUntrustedValueV1() {
    private val ownedSuppliers: List<PurchasingSupplierReferenceV1> =
        orderedSnapshot(suppliers, 1, LocalCompanyPolicyFormatV1.MAX_SELECTORS) { it.supplierId.value }

    init {
        require(ownedSuppliers.size + products.selectorCount <= LocalCompanyPolicyFormatV1.MAX_SELECTORS) {
            "Combined purchasing selector count exceeds the profile"
        }
    }

    public val suppliers: List<PurchasingSupplierReferenceV1> get() = ownedSuppliers.toList()
}

public sealed class PurchasingMonetaryCeilingV1 : PurchasingUntrustedValueV1() {
    public class Finite(public val maximum: Money) : PurchasingMonetaryCeilingV1() {
        init {
            require(maximum.currency == Currency.EGP && maximum.minor >= 0) { "Invalid EGP monetary ceiling" }
        }
    }

    /** Reuses the existing explicit currency-bound scalar; a missing field is not this choice. */
    public class ExplicitNoCeiling(public val choice: NoMonetaryCeiling) : PurchasingMonetaryCeilingV1() {
        init {
            require(choice.currency == Currency.EGP) { "Unsupported purchasing currency" }
        }
    }
}

public sealed class PurchasingVarianceLimitV1 : PurchasingUntrustedValueV1() {
    public object Exact : PurchasingVarianceLimitV1()

    /**
     * BOTH ceilings apply to the same absolute variance against the full eligible source
     * basis; neither can be selected as an alternative. PercentageRate remains exact and
     * nonnegative with its existing precision bound, not a floating-point ratio.
     */
    public class AmountAndPercentage(
        public val amount: PurchasingMonetaryCeilingV1,
        public val percentage: PercentageRate,
    ) : PurchasingVarianceLimitV1()
}

public sealed class PurchasingLimitsV1 : PurchasingUntrustedValueV1() {
    public object NoMonetaryEffect : PurchasingLimitsV1()

    public class TotalAmount(public val maximum: PurchasingMonetaryCeilingV1) : PurchasingLimitsV1()

    /** Whole original invoice ceiling AND exact/configured matching variance limits. */
    public class TotalAmountAndVariance(
        public val maximum: PurchasingMonetaryCeilingV1,
        public val variance: PurchasingVarianceLimitV1,
    ) : PurchasingLimitsV1()
}

/**
 * Signed business-action descriptions, NOT SecurityAction grants. No missing action is
 * enabled. Adding other PO/settlement/return profiles requires an explicit format decision;
 * those required local workflows are not implemented or waived by this first grammar.
 */
public enum class PurchasingRuleActionV1 {
    ARRIVAL_RECORD,
    ARRIVAL_QUALIFY,
    ARRIVAL_REJECT,
    ARRIVAL_DISPATCH,
    QUICK_AGREE,
    ARRIVAL_ADOPT,
    INVOICE_RECORD,
    INVOICE_MATCH,
    INVOICE_DISPUTE,
    EVIDENCE_LINK,
}

/** One complete grant must cover the selected rule/certificate; grants are never merged. */
public sealed class PurchasingAuthorityGrantV1(
    public val grantId: DomainId,
    branchIds: List<DomainId>,
) : PurchasingUntrustedValueV1() {
    private val ownedBranches: List<DomainId> =
        orderedSnapshot(branchIds, 1, LocalCompanyPolicyFormatV1.MAX_BRANCHES) { it.value }

    public val branchIds: List<DomainId> get() = ownedBranches.toList()
    public abstract val purpose: PurchasingAuthorityPurposeV1

    public class PolicyPublish(
        grantId: DomainId,
        branchIds: List<DomainId>,
        public val action: PurchasingRuleActionV1,
        public val selection: PurchasingSelectionV1,
        public val limits: PurchasingLimitsV1,
    ) : PurchasingAuthorityGrantV1(grantId, branchIds) {
        override val purpose: PurchasingAuthorityPurposeV1 = PurchasingAuthorityPurposeV1.POLICY_PUBLISH

        init {
            requireActionLimits(action, limits)
        }
    }

    public class SerialPublish(
        grantId: DomainId,
        branchIds: List<DomainId>,
        public val products: PurchasingProductSelectionV1,
    ) : PurchasingAuthorityGrantV1(grantId, branchIds) {
        override val purpose: PurchasingAuthorityPurposeV1 = PurchasingAuthorityPurposeV1.SERIAL_PUBLISH
    }
}

public enum class PurchasingEvidenceDecisionV1 {
    NOT_REQUIRED,
    REQUIRED,
}

public enum class PurchasingEvidencePurposeV1 {
    SUPPLIER_DELIVERY,
    OWNERSHIP_ATTESTATION,
    INSPECTION,
    PURCHASE_AGREEMENT,
    SUPPLIER_INVOICE,
    SUPPLIER_DISPATCH,
    CLAIM_SUPPORT,
}

public enum class PurchasingEvidenceMediaV1 {
    JPEG,
    PNG,
    PDF,
}

public enum class PurchasingRetentionObligationV1 {
    FINANCIAL_TRANSACTION,
    WARRANTY,
    SUPPLIER_CLAIM,
    LEGAL_HOLD,
}

/**
 * Configured minimum only, not permission to erase or a selected statutory period.
 * Unresolved uses and longer actual obligations must still be retained by the real owner.
 */
public class PurchasingRetentionClassV1(
    public val classId: DomainId,
    public val revision: Revision,
    public val minimumRetentionSeconds: Long,
    obligations: List<PurchasingRetentionObligationV1>,
) : PurchasingUntrustedValueV1() {
    private val ownedObligations: List<PurchasingRetentionObligationV1> =
        orderedSnapshot(obligations, 0, PurchasingRetentionObligationV1.entries.size) { it.name }

    init {
        requirePositiveRevision(revision)
        require(minimumRetentionSeconds >= 0) { "Negative retention period" }
    }

    public val obligations: List<PurchasingRetentionObligationV1> get() = ownedObligations.toList()
}

/**
 * No optional-evidence default: every rule supplies every field. NOT_REQUIRED has minimum
 * zero, while REQUIRED has a positive minimum. Even voluntary evidence has explicitly
 * selected purposes, formats, maximum count and an exact in-publication retention class.
 * This profile admits one or two optional/required manifests, not arbitrary proof bodies.
 */
public class PurchasingEvidenceRuleV1(
    public val decision: PurchasingEvidenceDecisionV1,
    purposes: List<PurchasingEvidencePurposeV1>,
    media: List<PurchasingEvidenceMediaV1>,
    public val minimumCount: Int,
    public val maximumCount: Int,
    public val retentionClassId: DomainId,
    public val retentionClassRevision: Revision,
) : PurchasingUntrustedValueV1() {
    private val ownedPurposes: List<PurchasingEvidencePurposeV1> =
        orderedSnapshot(purposes, 1, PurchasingEvidencePurposeV1.entries.size) { it.name }
    private val ownedMedia: List<PurchasingEvidenceMediaV1> =
        orderedSnapshot(media, 1, PurchasingEvidenceMediaV1.entries.size) { it.name }

    init {
        requirePositiveRevision(retentionClassRevision)
        require(maximumCount in 1..LocalCompanyPolicyFormatV1.MAX_EVIDENCE_MANIFESTS) {
            "Invalid evidence maximum"
        }
        require(minimumCount in 0..maximumCount) { "Invalid evidence minimum" }
        require(
            (decision == PurchasingEvidenceDecisionV1.NOT_REQUIRED && minimumCount == 0) ||
                (decision == PurchasingEvidenceDecisionV1.REQUIRED && minimumCount > 0),
        ) { "Evidence decision and minimum disagree" }
    }

    public val purposes: List<PurchasingEvidencePurposeV1> get() = ownedPurposes.toList()
    public val media: List<PurchasingEvidenceMediaV1> get() = ownedMedia.toList()
}

public sealed class PurchasingActorSelectionV1 : PurchasingUntrustedValueV1() {
    /** Explicit constraint to current enabled branch employees with the separate real grant. */
    public object CurrentBranchGrantees : PurchasingActorSelectionV1()

    /** Named selection does not exempt any employee from the same current permission checks. */
    public class NamedEmployees(employeeIds: List<DomainId>) : PurchasingActorSelectionV1() {
        private val ownedIds: List<DomainId> =
            orderedSnapshot(employeeIds, 1, LocalCompanyPolicyFormatV1.MAX_NAMED_EMPLOYEES) { it.value }

        public val employeeIds: List<DomainId> get() = ownedIds.toList()
    }
}

public sealed class PurchasingApprovalRouteV1 : PurchasingUntrustedValueV1() {
    public class Direct(public val performers: PurchasingActorSelectionV1) : PurchasingApprovalRouteV1()

    /** Requester and approver must be different actual enabled local employees. */
    public class Separate(
        public val requesters: PurchasingActorSelectionV1,
        public val approvers: PurchasingActorSelectionV1,
    ) : PurchasingApprovalRouteV1()

    /**
     * The separate route, with an explicit action-scoped exception for these exact employees.
     * The actual approval owner must still bind/consume the real protected approval.
     */
    public class NamedSelf(
        public val requesters: PurchasingActorSelectionV1,
        public val approvers: PurchasingActorSelectionV1,
        namedEmployeeIds: List<DomainId>,
    ) : PurchasingApprovalRouteV1() {
        private val ownedIds: List<DomainId> =
            orderedSnapshot(namedEmployeeIds, 1, LocalCompanyPolicyFormatV1.MAX_NAMED_EMPLOYEES) { it.value }

        init {
            if (requesters is PurchasingActorSelectionV1.NamedEmployees) {
                require(ownedIds.all { it in requesters.employeeIds }) { "Self approver outside requester selection" }
            }
            if (approvers is PurchasingActorSelectionV1.NamedEmployees) {
                require(ownedIds.all { it in approvers.employeeIds }) { "Self approver outside approver selection" }
            }
        }

        public val namedEmployeeIds: List<DomainId> get() = ownedIds.toList()
    }
}

public enum class PurchasingAcquisitionProfileV1 {
    NOT_APPLICABLE,
    /** Net goods only; actual inspection before acquisition; company certificate if serialized. */
    NET_GOODS_ONLY_INSPECTED_COMPANY_SERIAL,
}

/**
 * The ONE complete signed rule model. Future activation/consumer DTOs reference this model,
 * not a second employee/business policy with missing limits or optional-evidence defaults.
 * The grant ID belongs to the publication author's exact referenced delegation document.
 */
public class PurchasingPolicyRuleV1(
    public val ruleId: DomainId,
    public val delegationGrantId: DomainId,
    public val action: PurchasingRuleActionV1,
    public val selection: PurchasingSelectionV1,
    public val limits: PurchasingLimitsV1,
    public val evidence: PurchasingEvidenceRuleV1,
    public val approval: PurchasingApprovalRouteV1,
    public val acquisition: PurchasingAcquisitionProfileV1,
) : PurchasingUntrustedValueV1() {
    init {
        requireActionLimits(action, limits)
        val expected = when (action) {
            PurchasingRuleActionV1.QUICK_AGREE,
            PurchasingRuleActionV1.ARRIVAL_ADOPT,
            PurchasingRuleActionV1.INVOICE_MATCH -> PurchasingAcquisitionProfileV1.NET_GOODS_ONLY_INSPECTED_COMPANY_SERIAL
            else -> PurchasingAcquisitionProfileV1.NOT_APPLICABLE
        }
        require(acquisition == expected) { "Action and acquisition profile disagree" }
    }
}

public class PurchasingAuthorityDelegationV1(
    public val company: CompanyScope,
    public val root: PurchasingRootReferenceV1,
    public val delegationId: DomainId,
    public val publicationId: DomainId,
    public val revision: Revision,
    public val predecessor: PurchasingPublicationReferenceV1?,
    public val issuer: PurchasingAuthorityIssuerV1,
    public val subject: PurchasingNamedAuthorityV1,
    grants: List<PurchasingAuthorityGrantV1>,
    public val validity: PurchasingValidityV1,
) : PurchasingUntrustedValueV1() {
    private val ownedGrants: List<PurchasingAuthorityGrantV1> =
        orderedSnapshot(grants, 1, LocalCompanyPolicyFormatV1.MAX_AUTHORITY_GRANTS) { it.grantId.value }

    init {
        requirePublicationSequence(publicationId, revision, predecessor)
        when (issuer) {
            is PurchasingAuthorityIssuerV1.Root ->
                require(issuer.keyId == root.keyId) { "Delegation root issuer mismatch" }
            is PurchasingAuthorityIssuerV1.Delegated -> {
                require(subject.role == PurchasingAuthorityRoleV1.DELEGATE) { "Only a root may describe head enrollment" }
                require(issuer.issuer.authorityId != subject.authorityId &&
                    issuer.issuer.delegationId != delegationId) { "Self-delegation is not a supported shape" }
            }
        }
    }

    public val grants: List<PurchasingAuthorityGrantV1> get() = ownedGrants.toList()
}

/** An explicit empty rules list enables nothing; it is never an omitted/all-actions rule. */
public class PurchasingPolicyPublicationV1(
    public val branch: BranchScope,
    public val root: PurchasingRootReferenceV1,
    public val policyId: DomainId,
    public val publicationId: DomainId,
    public val revision: Revision,
    public val predecessor: PurchasingPublicationReferenceV1?,
    public val author: PurchasingDelegatedIssuerV1,
    public val validity: PurchasingValidityV1,
    retentionClasses: List<PurchasingRetentionClassV1>,
    rules: List<PurchasingPolicyRuleV1>,
) : PurchasingUntrustedValueV1() {
    private val ownedRetention: List<PurchasingRetentionClassV1> =
        orderedSnapshot(retentionClasses, 0, LocalCompanyPolicyFormatV1.MAX_RETENTION_CLASSES) { it.classId.value }
    private val ownedRules: List<PurchasingPolicyRuleV1> =
        orderedSnapshot(rules, 0, LocalCompanyPolicyFormatV1.MAX_RULES) { it.ruleId.value }

    init {
        requirePublicationSequence(publicationId, revision, predecessor)
        require(ownedRules.all { rule ->
            ownedRetention.any { retention ->
                retention.classId == rule.evidence.retentionClassId &&
                    retention.revision == rule.evidence.retentionClassRevision
            }
        }) { "Rule retention reference is absent from this publication" }
    }

    public val retentionClasses: List<PurchasingRetentionClassV1> get() = ownedRetention.toList()
    public val rules: List<PurchasingPolicyRuleV1> get() = ownedRules.toList()
}

public enum class PurchasingSerialProvenanceV1 {
    ORIGINAL_COMPANY_RESERVATION,
}

/**
 * Coordinates of a claimed durable original issuance, never a reservation implementation.
 * The original request digest and registry revisions require the actual issuer's readback.
 */
public class PurchasingSerialReservationV1(
    public val registryId: DomainId,
    public val registryRevision: Revision,
    public val reservationId: DomainId,
    public val reservationRevision: Revision,
    public val originalIssueOperationId: OperationId,
    public val originalRequestDigest: PayloadDigest,
) : PurchasingUntrustedValueV1()

/**
 * One asserted whole-unit certificate. Higher registration revisions cannot establish a
 * changed SKU/code for an existing identity: the real serialized issuer and historical
 * registration/claim source must enforce that. This constructor does not register stock.
 */
public class PurchasingSerialCertificateV1(
    public val certificateId: DomainId,
    public val branch: BranchScope,
    public val root: PurchasingRootReferenceV1,
    public val issuer: PurchasingDelegatedIssuerV1,
    public val delegationGrantId: DomainId,
    public val reservation: PurchasingSerialReservationV1,
    public val issuedAt: UtcInstant,
    public val registrationId: DomainId,
    public val registrationRevision: Revision,
    public val serialId: DomainId,
    public val serialCode: String,
    public val sku: PurchasingSkuReferenceV1,
    public val provenance: PurchasingSerialProvenanceV1,
    public val validity: PurchasingValidityV1,
) : PurchasingUntrustedValueV1() {
    init {
        requirePositiveRevision(registrationRevision)
        // Preserve the existing exact code grammar, including leading zeros and case.
        requireBoundedExactText(serialCode, 128, 256)
    }
}

public class PurchasingSignedDelegationV1(
    public val body: PurchasingAuthorityDelegationV1,
    public val signature: PurchasingSignatureV1,
) : PurchasingUntrustedValueV1()

/**
 * Canonical document set, NOT an authenticated path. Sorted by immutable publication ID,
 * with at most one publication per stable delegation. Empty means "not embedded", not
 * "unnecessary/verified"; the actual native owner must resolve all exact references and
 * enforce the four-document depth bound even when documents come from retained storage.
 */
public class PurchasingDelegationDocumentsV1(documents: List<PurchasingSignedDelegationV1>) :
    PurchasingUntrustedValueV1() {
    private val ownedDocuments: List<PurchasingSignedDelegationV1> =
        orderedSnapshot(documents, 0, LocalCompanyPolicyFormatV1.MAX_DELEGATION_DOCUMENTS) { it.body.publicationId.value }

    init {
        require(ownedDocuments.map { it.body.delegationId }.toSet().size == ownedDocuments.size) {
            "Repeated stable delegation in the embedded document set"
        }
    }

    public val documents: List<PurchasingSignedDelegationV1> get() = ownedDocuments.toList()
}

public class PurchasingSignedPolicyV1(
    public val body: PurchasingPolicyPublicationV1,
    public val signature: PurchasingSignatureV1,
    public val delegations: PurchasingDelegationDocumentsV1,
) : PurchasingUntrustedValueV1()

public class PurchasingSignedSerialV1(
    public val body: PurchasingSerialCertificateV1,
    public val signature: PurchasingSignatureV1,
    public val delegations: PurchasingDelegationDocumentsV1,
) : PurchasingUntrustedValueV1()

private fun requirePositiveRevision(revision: Revision) {
    require(revision.value >= 1) { "Purchasing source revisions start at one" }
}

private fun requirePublicationSequence(
    publicationId: DomainId,
    revision: Revision,
    predecessor: PurchasingPublicationReferenceV1?,
) {
    requirePositiveRevision(revision)
    if (revision.value == 1L) {
        require(predecessor == null) { "Initial publication cannot name a predecessor" }
    } else {
        require(predecessor != null && predecessor.revision.value == revision.value - 1 &&
            predecessor.publicationId != publicationId) { "Invalid publication predecessor coordinates" }
    }
}

private fun requireActionLimits(action: PurchasingRuleActionV1, limits: PurchasingLimitsV1) {
    val valid = when (action) {
        PurchasingRuleActionV1.ARRIVAL_RECORD,
        PurchasingRuleActionV1.ARRIVAL_QUALIFY,
        PurchasingRuleActionV1.ARRIVAL_REJECT,
        PurchasingRuleActionV1.ARRIVAL_DISPATCH,
        PurchasingRuleActionV1.EVIDENCE_LINK -> limits is PurchasingLimitsV1.NoMonetaryEffect
        PurchasingRuleActionV1.QUICK_AGREE,
        PurchasingRuleActionV1.ARRIVAL_ADOPT,
        PurchasingRuleActionV1.INVOICE_RECORD,
        PurchasingRuleActionV1.INVOICE_DISPUTE -> limits is PurchasingLimitsV1.TotalAmount
        PurchasingRuleActionV1.INVOICE_MATCH -> limits is PurchasingLimitsV1.TotalAmountAndVariance
    }
    require(valid) { "Action requires a different complete limit shape" }
}

private fun requireBoundedExactText(value: String, maximumCharacters: Int, maximumBytes: Int) {
    require(value.length in 1..maximumCharacters && value == value.trim() &&
        value.none { it.code in 0..31 || it.code == 127 }) { "Invalid exact purchasing text" }
    val bytes = try {
        value.encodeToByteArray(throwOnInvalidSequence = true)
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid purchasing UTF-8 text")
    }
    require(bytes.size <= maximumBytes) { "Purchasing text exceeds its UTF-8 bound" }
}

private fun exactOwnedBytes(value: ByteArray, length: Int): ByteArray {
    require(value.size == length) { "Invalid purchasing public-byte length" }
    return value.copyOf()
}

private fun <T> orderedSnapshot(
    values: List<T>,
    minimum: Int,
    maximum: Int,
    key: (T) -> String,
): List<T> {
    require(values.size in minimum..maximum) { "Purchasing collection count exceeds its profile" }
    val owned = ArrayList<T>(maximum)
    var previous: String? = null
    for (value in values) {
        require(owned.size < maximum) { "Purchasing collection changed beyond its bound" }
        val current = key(value)
        val preceding = previous
        require(preceding == null || preceding < current) { "Purchasing collection must be sorted and unique" }
        owned.add(value)
        previous = current
    }
    require(owned.size >= minimum) { "Purchasing collection changed below its bound" }
    return owned
}
