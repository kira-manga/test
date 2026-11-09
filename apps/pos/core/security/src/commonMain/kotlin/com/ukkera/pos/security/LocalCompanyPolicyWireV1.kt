package com.ukkera.pos.security

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Pure canonical framing of UNTRUSTED values. The three body encoders return the exact
 * bytes-to-be-signed, including domain, format version and distinct body role. No hashing,
 * signing, verification, key lookup, authority construction, time or ID generation occurs.
 *
 * Every record field is tag:u8, length:u32be, value:length bytes, in the one defined order.
 * Integers are fixed-width big endian. Lists have count:u16be followed by count field-1
 * elements; all list lengths, unique ordering and enclosing byte bounds apply together.
 * Optional values begin with exactly 0 or 1; present values use their ordinary encoding.
 * Strings are strict UTF-8, with canonical scalar spellings. Unknown/duplicate/reordered
 * fields, enum values, invalid UTF-8, trailing bytes and noncanonical scalars are rejected.
 *
 * Standalone bodies are at most 48 KiB and envelopes at most 64 KiB, including all framing.
 * An envelope embeds each body ONCE, not a JSON/base64/hex duplicate. Delegation documents
 * form a bounded canonical set, not a verified chain. The later activation command must
 * separately fit its own 64 KiB bound; a maximum-size envelope is not promised to fit it.
 *
 * Successful decoding is not policy acceptance, public-key qualification or a low-S check.
 */
public object LocalCompanyPolicyWireV1 {
    public fun encodeDelegationBody(value: PurchasingAuthorityDelegationV1): ByteArray =
        encode(LocalCompanyPolicyFormatV1.MAX_BODY_BYTES) { delegationBody(value) }

    public fun decodeDelegationBody(bytes: ByteArray): PurchasingAuthorityDelegationV1 =
        decode(bytes, LocalCompanyPolicyFormatV1.MAX_BODY_BYTES) { delegationBody() }

    public fun encodePolicyBody(value: PurchasingPolicyPublicationV1): ByteArray =
        encode(LocalCompanyPolicyFormatV1.MAX_BODY_BYTES) { policyBody(value) }

    public fun decodePolicyBody(bytes: ByteArray): PurchasingPolicyPublicationV1 =
        decode(bytes, LocalCompanyPolicyFormatV1.MAX_BODY_BYTES) { policyBody() }

    public fun encodeSerialBody(value: PurchasingSerialCertificateV1): ByteArray =
        encode(LocalCompanyPolicyFormatV1.MAX_BODY_BYTES) { serialBody(value) }

    public fun decodeSerialBody(bytes: ByteArray): PurchasingSerialCertificateV1 =
        decode(bytes, LocalCompanyPolicyFormatV1.MAX_BODY_BYTES) { serialBody() }

    public fun encodeSignedDelegation(value: PurchasingSignedDelegationV1): ByteArray =
        encode(LocalCompanyPolicyFormatV1.MAX_ENVELOPE_BYTES) { signedDelegation(value) }

    public fun decodeSignedDelegation(bytes: ByteArray): PurchasingSignedDelegationV1 =
        decode(bytes, LocalCompanyPolicyFormatV1.MAX_ENVELOPE_BYTES) { signedDelegation() }

    public fun encodeSignedPolicy(value: PurchasingSignedPolicyV1): ByteArray =
        encode(LocalCompanyPolicyFormatV1.MAX_ENVELOPE_BYTES) { signedPolicy(value) }

    public fun decodeSignedPolicy(bytes: ByteArray): PurchasingSignedPolicyV1 =
        decode(bytes, LocalCompanyPolicyFormatV1.MAX_ENVELOPE_BYTES) { signedPolicy() }

    public fun encodeSignedSerial(value: PurchasingSignedSerialV1): ByteArray =
        encode(LocalCompanyPolicyFormatV1.MAX_ENVELOPE_BYTES) { signedSerial(value) }

    public fun decodeSignedSerial(bytes: ByteArray): PurchasingSignedSerialV1 =
        decode(bytes, LocalCompanyPolicyFormatV1.MAX_ENVELOPE_BYTES) { signedSerial() }
}

private const val DELEGATION_BODY_ROLE: String = "authority-delegation"
private const val POLICY_BODY_ROLE: String = "policy-publication"
private const val SERIAL_BODY_ROLE: String = "serial-certificate"
private const val DELEGATION_ENVELOPE_ROLE: String = "signed-authority-delegation"
private const val POLICY_ENVELOPE_ROLE: String = "signed-policy-publication"
private const val SERIAL_ENVELOPE_ROLE: String = "signed-serial-certificate"

private fun PurchasingWireWriterV1.header(role: String) {
    field(1) { text(LocalCompanyPolicyFormatV1.DOMAIN, 64) }
    field(2) { u16(LocalCompanyPolicyFormatV1.VERSION) }
    field(3) { text(role, 64) }
}

private fun PurchasingWireReaderV1.header(role: String) {
    require(field(1, 64) { text(64) } == LocalCompanyPolicyFormatV1.DOMAIN) { "Wrong purchasing domain" }
    require(field(2, 2) { u16() } == LocalCompanyPolicyFormatV1.VERSION) { "Unknown purchasing format" }
    require(field(3, 64) { text(64) } == role) { "Wrong purchasing document role" }
}

private fun PurchasingWireWriterV1.delegationBody(value: PurchasingAuthorityDelegationV1) {
    header(DELEGATION_BODY_ROLE)
    field(4) {
        field(1) { company(value.company) }
        field(2) { root(value.root) }
        field(3) { id(value.delegationId) }
        field(4) { id(value.publicationId) }
        field(5) { revision(value.revision) }
        field(6) { optional(value.predecessor) { publicationReference(it) } }
        field(7) { authorityIssuer(value.issuer) }
        field(8) { namedAuthority(value.subject) }
        field(9) { list(value.grants, LocalCompanyPolicyFormatV1.MAX_AUTHORITY_GRANTS) { grant(it) } }
        field(10) { validity(value.validity) }
    }
}

private fun PurchasingWireReaderV1.delegationBody(): PurchasingAuthorityDelegationV1 {
    header(DELEGATION_BODY_ROLE)
    return field(4) {
        PurchasingAuthorityDelegationV1(
            company = field(1) { company() },
            root = field(2) { root() },
            delegationId = field(3, 36) { id() },
            publicationId = field(4, 36) { id() },
            revision = field(5, 8) { revision() },
            predecessor = field(6) { optional { publicationReference() } },
            issuer = field(7) { authorityIssuer() },
            subject = field(8) { namedAuthority() },
            grants = field(9) { list(LocalCompanyPolicyFormatV1.MAX_AUTHORITY_GRANTS) { grant() } },
            validity = field(10) { validity() },
        )
    }
}

private fun PurchasingWireWriterV1.policyBody(value: PurchasingPolicyPublicationV1) {
    header(POLICY_BODY_ROLE)
    field(4) {
        field(1) { branch(value.branch) }
        field(2) { root(value.root) }
        field(3) { id(value.policyId) }
        field(4) { id(value.publicationId) }
        field(5) { revision(value.revision) }
        field(6) { optional(value.predecessor) { publicationReference(it) } }
        field(7) { delegatedIssuer(value.author) }
        field(8) { validity(value.validity) }
        field(9) { list(value.retentionClasses, LocalCompanyPolicyFormatV1.MAX_RETENTION_CLASSES) { retention(it) } }
        field(10) { list(value.rules, LocalCompanyPolicyFormatV1.MAX_RULES) { rule(it) } }
    }
}

private fun PurchasingWireReaderV1.policyBody(): PurchasingPolicyPublicationV1 {
    header(POLICY_BODY_ROLE)
    return field(4) {
        PurchasingPolicyPublicationV1(
            branch = field(1) { branch() },
            root = field(2) { root() },
            policyId = field(3, 36) { id() },
            publicationId = field(4, 36) { id() },
            revision = field(5, 8) { revision() },
            predecessor = field(6) { optional { publicationReference() } },
            author = field(7) { delegatedIssuer() },
            validity = field(8) { validity() },
            retentionClasses = field(9) { list(LocalCompanyPolicyFormatV1.MAX_RETENTION_CLASSES) { retention() } },
            rules = field(10) { list(LocalCompanyPolicyFormatV1.MAX_RULES) { rule() } },
        )
    }
}

private fun PurchasingWireWriterV1.serialBody(value: PurchasingSerialCertificateV1) {
    header(SERIAL_BODY_ROLE)
    field(4) {
        field(1) { id(value.certificateId) }
        field(2) { branch(value.branch) }
        field(3) { root(value.root) }
        field(4) { delegatedIssuer(value.issuer) }
        field(5) { id(value.delegationGrantId) }
        field(6) { reservation(value.reservation) }
        field(7) { instant(value.issuedAt) }
        field(8) { id(value.registrationId) }
        field(9) { revision(value.registrationRevision) }
        field(10) { id(value.serialId) }
        field(11) { text(value.serialCode, 256) }
        field(12) { sku(value.sku) }
        field(13) { enumeration(value.provenance) }
        field(14) { validity(value.validity) }
    }
}

private fun PurchasingWireReaderV1.serialBody(): PurchasingSerialCertificateV1 {
    header(SERIAL_BODY_ROLE)
    return field(4) {
        PurchasingSerialCertificateV1(
            certificateId = field(1, 36) { id() },
            branch = field(2) { branch() },
            root = field(3) { root() },
            issuer = field(4) { delegatedIssuer() },
            delegationGrantId = field(5, 36) { id() },
            reservation = field(6) { reservation() },
            issuedAt = field(7, 64) { instant() },
            registrationId = field(8, 36) { id() },
            registrationRevision = field(9, 8) { revision() },
            serialId = field(10, 36) { id() },
            serialCode = field(11, 256) { text(256) },
            sku = field(12) { sku() },
            provenance = field(13, 64) { enumeration(PurchasingSerialProvenanceV1.entries) },
            validity = field(14) { validity() },
        )
    }
}

private fun PurchasingWireWriterV1.signedDelegation(value: PurchasingSignedDelegationV1) {
    header(DELEGATION_ENVELOPE_ROLE)
    field(4, LocalCompanyPolicyFormatV1.MAX_BODY_BYTES) { delegationBody(value.body) }
    field(5, 64) { fixedBytes(value.signature.copyBytes(), 64) }
}

private fun PurchasingWireReaderV1.signedDelegation(): PurchasingSignedDelegationV1 {
    header(DELEGATION_ENVELOPE_ROLE)
    return PurchasingSignedDelegationV1(
        body = field(4, LocalCompanyPolicyFormatV1.MAX_BODY_BYTES) { delegationBody() },
        signature = field(5, 64) { PurchasingSignatureV1(fixedBytes(64)) },
    )
}

private fun PurchasingWireWriterV1.signedPolicy(value: PurchasingSignedPolicyV1) {
    header(POLICY_ENVELOPE_ROLE)
    field(4, LocalCompanyPolicyFormatV1.MAX_BODY_BYTES) { policyBody(value.body) }
    field(5, 64) { fixedBytes(value.signature.copyBytes(), 64) }
    field(6) { delegationDocuments(value.delegations) }
}

private fun PurchasingWireReaderV1.signedPolicy(): PurchasingSignedPolicyV1 {
    header(POLICY_ENVELOPE_ROLE)
    return PurchasingSignedPolicyV1(
        body = field(4, LocalCompanyPolicyFormatV1.MAX_BODY_BYTES) { policyBody() },
        signature = field(5, 64) { PurchasingSignatureV1(fixedBytes(64)) },
        delegations = field(6) { delegationDocuments() },
    )
}

private fun PurchasingWireWriterV1.signedSerial(value: PurchasingSignedSerialV1) {
    header(SERIAL_ENVELOPE_ROLE)
    field(4, LocalCompanyPolicyFormatV1.MAX_BODY_BYTES) { serialBody(value.body) }
    field(5, 64) { fixedBytes(value.signature.copyBytes(), 64) }
    field(6) { delegationDocuments(value.delegations) }
}

private fun PurchasingWireReaderV1.signedSerial(): PurchasingSignedSerialV1 {
    header(SERIAL_ENVELOPE_ROLE)
    return PurchasingSignedSerialV1(
        body = field(4, LocalCompanyPolicyFormatV1.MAX_BODY_BYTES) { serialBody() },
        signature = field(5, 64) { PurchasingSignatureV1(fixedBytes(64)) },
        delegations = field(6) { delegationDocuments() },
    )
}

private fun PurchasingWireWriterV1.delegationDocuments(value: PurchasingDelegationDocumentsV1) {
    list(value.documents, LocalCompanyPolicyFormatV1.MAX_DELEGATION_DOCUMENTS) { signedDelegation(it) }
}

private fun PurchasingWireReaderV1.delegationDocuments(): PurchasingDelegationDocumentsV1 =
    PurchasingDelegationDocumentsV1(list(LocalCompanyPolicyFormatV1.MAX_DELEGATION_DOCUMENTS) { signedDelegation() })

private fun PurchasingWireWriterV1.company(value: CompanyScope) {
    field(1) { id(value.organizationId) }
    field(2) { id(value.companyId) }
}

private fun PurchasingWireReaderV1.company(): CompanyScope = CompanyScope(
    organizationId = field(1, 36) { id() },
    companyId = field(2, 36) { id() },
)

private fun PurchasingWireWriterV1.branch(value: BranchScope) {
    field(1) { id(value.organizationId) }
    field(2) { id(value.companyId) }
    field(3) { id(value.branchId) }
}

private fun PurchasingWireReaderV1.branch(): BranchScope = BranchScope(
    organizationId = field(1, 36) { id() },
    companyId = field(2, 36) { id() },
    branchId = field(3, 36) { id() },
)

private fun PurchasingWireWriterV1.root(value: PurchasingRootReferenceV1) {
    field(1) { id(value.keyId) }
    field(2) { digest(value.fingerprint) }
}

private fun PurchasingWireReaderV1.root(): PurchasingRootReferenceV1 = PurchasingRootReferenceV1(
    keyId = field(1, 36) { id() },
    fingerprint = field(2, 64) { digest() },
)

private fun PurchasingWireWriterV1.publicationReference(value: PurchasingPublicationReferenceV1) {
    field(1) { id(value.publicationId) }
    field(2) { revision(value.revision) }
    field(3) { digest(value.bodyDigest) }
}

private fun PurchasingWireReaderV1.publicationReference(): PurchasingPublicationReferenceV1 = PurchasingPublicationReferenceV1(
    publicationId = field(1, 36) { id() },
    revision = field(2, 8) { revision() },
    bodyDigest = field(3, 64) { digest() },
)

private fun PurchasingWireWriterV1.validity(value: PurchasingValidityV1) {
    field(1) { instant(value.validFrom) }
    field(2) { optional(value.validUntil) { instant(it) } }
}

private fun PurchasingWireReaderV1.validity(): PurchasingValidityV1 = PurchasingValidityV1(
    validFrom = field(1, 64) { instant() },
    validUntil = field(2, 65) { optional { instant() } },
)

private fun PurchasingWireWriterV1.authorityIssuer(value: PurchasingAuthorityIssuerV1) {
    when (value) {
        is PurchasingAuthorityIssuerV1.Root -> {
            field(1) { text("ROOT", 64) }
            field(2) { id(value.keyId) }
        }
        is PurchasingAuthorityIssuerV1.Delegated -> {
            field(1) { text("DELEGATED", 64) }
            field(2) { delegatedIssuer(value.issuer) }
        }
    }
}

private fun PurchasingWireReaderV1.authorityIssuer(): PurchasingAuthorityIssuerV1 = when (field(1, 64) { text(64) }) {
    "ROOT" -> PurchasingAuthorityIssuerV1.Root(field(2, 36) { id() })
    "DELEGATED" -> PurchasingAuthorityIssuerV1.Delegated(field(2) { delegatedIssuer() })
    else -> invalidVariant()
}

private fun PurchasingWireWriterV1.namedAuthority(value: PurchasingNamedAuthorityV1) {
    field(1) { id(value.authorityId) }
    field(2) { text(value.name, 256) }
    field(3) { enumeration(value.role) }
    field(4) { revision(value.accountRevision) }
    field(5) { id(value.keyId) }
    field(6, 65) { fixedBytes(value.publicPoint.copyBytes(), 65) }
}

private fun PurchasingWireReaderV1.namedAuthority(): PurchasingNamedAuthorityV1 = PurchasingNamedAuthorityV1(
    authorityId = field(1, 36) { id() },
    name = field(2, 256) { text(256) },
    role = field(3, 64) { enumeration(PurchasingAuthorityRoleV1.entries) },
    accountRevision = field(4, 8) { revision() },
    keyId = field(5, 36) { id() },
    publicPoint = field(6, 65) { PurchasingPublicPointV1(fixedBytes(65)) },
)

private fun PurchasingWireWriterV1.delegatedIssuer(value: PurchasingDelegatedIssuerV1) {
    field(1) { id(value.authorityId) }
    field(2) { revision(value.accountRevision) }
    field(3) { id(value.keyId) }
    field(4) { id(value.delegationId) }
    field(5) { publicationReference(value.delegationPublication) }
}

private fun PurchasingWireReaderV1.delegatedIssuer(): PurchasingDelegatedIssuerV1 = PurchasingDelegatedIssuerV1(
    authorityId = field(1, 36) { id() },
    accountRevision = field(2, 8) { revision() },
    keyId = field(3, 36) { id() },
    delegationId = field(4, 36) { id() },
    delegationPublication = field(5) { publicationReference() },
)

private fun PurchasingWireWriterV1.catalog(value: PurchasingCatalogReferenceV1) {
    field(1) { id(value.publicationId) }
    field(2) { revision(value.revision) }
}

private fun PurchasingWireReaderV1.catalog(): PurchasingCatalogReferenceV1 = PurchasingCatalogReferenceV1(
    publicationId = field(1, 36) { id() },
    revision = field(2, 8) { revision() },
)

private fun PurchasingWireWriterV1.supplier(value: PurchasingSupplierReferenceV1) {
    field(1) { id(value.supplierId) }
    field(2) { revision(value.revision) }
}

private fun PurchasingWireReaderV1.supplier(): PurchasingSupplierReferenceV1 = PurchasingSupplierReferenceV1(
    supplierId = field(1, 36) { id() },
    revision = field(2, 8) { revision() },
)

private fun PurchasingWireWriterV1.sku(value: PurchasingSkuReferenceV1) {
    field(1) { id(value.skuId) }
    field(2) { revision(value.skuRevision) }
    field(3) { id(value.productId) }
    field(4) { revision(value.productRevision) }
    field(5) { catalog(value.catalog) }
}

private fun PurchasingWireReaderV1.sku(): PurchasingSkuReferenceV1 = PurchasingSkuReferenceV1(
    skuId = field(1, 36) { id() },
    skuRevision = field(2, 8) { revision() },
    productId = field(3, 36) { id() },
    productRevision = field(4, 8) { revision() },
    catalog = field(5) { catalog() },
)

private fun PurchasingWireWriterV1.category(value: PurchasingCategoryReferenceV1) {
    field(1) { id(value.categoryId) }
    field(2) { revision(value.revision) }
    field(3) { catalog(value.catalog) }
}

private fun PurchasingWireReaderV1.category(): PurchasingCategoryReferenceV1 = PurchasingCategoryReferenceV1(
    categoryId = field(1, 36) { id() },
    revision = field(2, 8) { revision() },
    catalog = field(3) { catalog() },
)

private fun PurchasingWireWriterV1.products(value: PurchasingProductSelectionV1) {
    field(1) { list(value.skus, LocalCompanyPolicyFormatV1.MAX_SELECTORS) { sku(it) } }
    field(2) { list(value.categories, LocalCompanyPolicyFormatV1.MAX_SELECTORS) { category(it) } }
}

private fun PurchasingWireReaderV1.products(): PurchasingProductSelectionV1 = PurchasingProductSelectionV1(
    skus = field(1) { list(LocalCompanyPolicyFormatV1.MAX_SELECTORS) { sku() } },
    categories = field(2) { list(LocalCompanyPolicyFormatV1.MAX_SELECTORS) { category() } },
)

private fun PurchasingWireWriterV1.selection(value: PurchasingSelectionV1) {
    field(1) { list(value.suppliers, LocalCompanyPolicyFormatV1.MAX_SELECTORS) { supplier(it) } }
    field(2) { products(value.products) }
}

private fun PurchasingWireReaderV1.selection(): PurchasingSelectionV1 = PurchasingSelectionV1(
    suppliers = field(1) { list(LocalCompanyPolicyFormatV1.MAX_SELECTORS) { supplier() } },
    products = field(2) { products() },
)

private fun PurchasingWireWriterV1.monetaryCeiling(value: PurchasingMonetaryCeilingV1) {
    when (value) {
        is PurchasingMonetaryCeilingV1.Finite -> {
            field(1) { text("FINITE", 64) }
            field(2) { currency(value.maximum.currency) }
            field(3) { nonnegativeLong(value.maximum.minor) }
        }
        is PurchasingMonetaryCeilingV1.ExplicitNoCeiling -> {
            field(1) { text("NO_MONETARY_CEILING", 64) }
            field(2) { currency(value.choice.currency) }
        }
    }
}

private fun PurchasingWireReaderV1.monetaryCeiling(): PurchasingMonetaryCeilingV1 = when (field(1, 64) { text(64) }) {
    "FINITE" -> PurchasingMonetaryCeilingV1.Finite(
        Money(
            currency = field(2, 3) { currency() },
            minor = field(3, 8) { nonnegativeLong() },
        ),
    )
    "NO_MONETARY_CEILING" ->
        PurchasingMonetaryCeilingV1.ExplicitNoCeiling(NoMonetaryCeiling(field(2, 3) { currency() }))
    else -> invalidVariant()
}

private fun PurchasingWireWriterV1.variance(value: PurchasingVarianceLimitV1) {
    when (value) {
        PurchasingVarianceLimitV1.Exact -> field(1) { text("EXACT", 64) }
        is PurchasingVarianceLimitV1.AmountAndPercentage -> {
            field(1) { text("AMOUNT_AND_PERCENTAGE", 64) }
            field(2) { monetaryCeiling(value.amount) }
            field(3) { percentage(value.percentage) }
        }
    }
}

private fun PurchasingWireReaderV1.variance(): PurchasingVarianceLimitV1 = when (field(1, 64) { text(64) }) {
    "EXACT" -> PurchasingVarianceLimitV1.Exact
    "AMOUNT_AND_PERCENTAGE" -> PurchasingVarianceLimitV1.AmountAndPercentage(
        amount = field(2) { monetaryCeiling() },
        percentage = field(3, 40) { percentage() },
    )
    else -> invalidVariant()
}

private fun PurchasingWireWriterV1.limits(value: PurchasingLimitsV1) {
    when (value) {
        PurchasingLimitsV1.NoMonetaryEffect -> field(1) { text("NO_MONETARY_EFFECT", 64) }
        is PurchasingLimitsV1.TotalAmount -> {
            field(1) { text("TOTAL_AMOUNT", 64) }
            field(2) { monetaryCeiling(value.maximum) }
        }
        is PurchasingLimitsV1.TotalAmountAndVariance -> {
            field(1) { text("TOTAL_AMOUNT_AND_VARIANCE", 64) }
            field(2) { monetaryCeiling(value.maximum) }
            field(3) { variance(value.variance) }
        }
    }
}

private fun PurchasingWireReaderV1.limits(): PurchasingLimitsV1 = when (field(1, 64) { text(64) }) {
    "NO_MONETARY_EFFECT" -> PurchasingLimitsV1.NoMonetaryEffect
    "TOTAL_AMOUNT" -> PurchasingLimitsV1.TotalAmount(field(2) { monetaryCeiling() })
    "TOTAL_AMOUNT_AND_VARIANCE" -> PurchasingLimitsV1.TotalAmountAndVariance(
        maximum = field(2) { monetaryCeiling() },
        variance = field(3) { variance() },
    )
    else -> invalidVariant()
}

private fun PurchasingWireWriterV1.grant(value: PurchasingAuthorityGrantV1) {
    field(1) { enumeration(value.purpose) }
    field(2) { id(value.grantId) }
    field(3) { ids(value.branchIds, LocalCompanyPolicyFormatV1.MAX_BRANCHES) }
    when (value) {
        is PurchasingAuthorityGrantV1.PolicyPublish -> {
            field(4) { enumeration(value.action) }
            field(5) { selection(value.selection) }
            field(6) { limits(value.limits) }
        }
        is PurchasingAuthorityGrantV1.SerialPublish -> field(4) { products(value.products) }
    }
}

private fun PurchasingWireReaderV1.grant(): PurchasingAuthorityGrantV1 {
    val purpose = field(1, 64) { enumeration(PurchasingAuthorityPurposeV1.entries) }
    val grantId = field(2, 36) { id() }
    val branches = field(3) { ids(LocalCompanyPolicyFormatV1.MAX_BRANCHES) }
    return when (purpose) {
        PurchasingAuthorityPurposeV1.POLICY_PUBLISH -> PurchasingAuthorityGrantV1.PolicyPublish(
            grantId = grantId,
            branchIds = branches,
            action = field(4, 64) { enumeration(PurchasingRuleActionV1.entries) },
            selection = field(5) { selection() },
            limits = field(6) { limits() },
        )
        PurchasingAuthorityPurposeV1.SERIAL_PUBLISH -> PurchasingAuthorityGrantV1.SerialPublish(
            grantId = grantId,
            branchIds = branches,
            products = field(4) { products() },
        )
    }
}

private fun PurchasingWireWriterV1.retention(value: PurchasingRetentionClassV1) {
    field(1) { id(value.classId) }
    field(2) { revision(value.revision) }
    field(3) { nonnegativeLong(value.minimumRetentionSeconds) }
    field(4) { list(value.obligations, PurchasingRetentionObligationV1.entries.size) { enumeration(it) } }
}

private fun PurchasingWireReaderV1.retention(): PurchasingRetentionClassV1 = PurchasingRetentionClassV1(
    classId = field(1, 36) { id() },
    revision = field(2, 8) { revision() },
    minimumRetentionSeconds = field(3, 8) { nonnegativeLong() },
    obligations = field(4) {
        list(PurchasingRetentionObligationV1.entries.size) { enumeration(PurchasingRetentionObligationV1.entries) }
    },
)

private fun PurchasingWireWriterV1.evidence(value: PurchasingEvidenceRuleV1) {
    field(1) { enumeration(value.decision) }
    field(2) { list(value.purposes, PurchasingEvidencePurposeV1.entries.size) { enumeration(it) } }
    field(3) { list(value.media, PurchasingEvidenceMediaV1.entries.size) { enumeration(it) } }
    field(4) { u16(value.minimumCount) }
    field(5) { u16(value.maximumCount) }
    field(6) { id(value.retentionClassId) }
    field(7) { revision(value.retentionClassRevision) }
}

private fun PurchasingWireReaderV1.evidence(): PurchasingEvidenceRuleV1 = PurchasingEvidenceRuleV1(
    decision = field(1, 64) { enumeration(PurchasingEvidenceDecisionV1.entries) },
    purposes = field(2) {
        list(PurchasingEvidencePurposeV1.entries.size) { enumeration(PurchasingEvidencePurposeV1.entries) }
    },
    media = field(3) { list(PurchasingEvidenceMediaV1.entries.size) { enumeration(PurchasingEvidenceMediaV1.entries) } },
    minimumCount = field(4, 2) { u16() },
    maximumCount = field(5, 2) { u16() },
    retentionClassId = field(6, 36) { id() },
    retentionClassRevision = field(7, 8) { revision() },
)

private fun PurchasingWireWriterV1.actorSelection(value: PurchasingActorSelectionV1) {
    when (value) {
        PurchasingActorSelectionV1.CurrentBranchGrantees -> field(1) { text("CURRENT_BRANCH_GRANTEES", 64) }
        is PurchasingActorSelectionV1.NamedEmployees -> {
            field(1) { text("NAMED_EMPLOYEES", 64) }
            field(2) { ids(value.employeeIds, LocalCompanyPolicyFormatV1.MAX_NAMED_EMPLOYEES) }
        }
    }
}

private fun PurchasingWireReaderV1.actorSelection(): PurchasingActorSelectionV1 = when (field(1, 64) { text(64) }) {
    "CURRENT_BRANCH_GRANTEES" -> PurchasingActorSelectionV1.CurrentBranchGrantees
    "NAMED_EMPLOYEES" -> PurchasingActorSelectionV1.NamedEmployees(
        field(2) { ids(LocalCompanyPolicyFormatV1.MAX_NAMED_EMPLOYEES) },
    )
    else -> invalidVariant()
}

private fun PurchasingWireWriterV1.approval(value: PurchasingApprovalRouteV1) {
    when (value) {
        is PurchasingApprovalRouteV1.Direct -> {
            field(1) { text("DIRECT", 64) }
            field(2) { actorSelection(value.performers) }
        }
        is PurchasingApprovalRouteV1.Separate -> {
            field(1) { text("SEPARATE", 64) }
            field(2) { actorSelection(value.requesters) }
            field(3) { actorSelection(value.approvers) }
        }
        is PurchasingApprovalRouteV1.NamedSelf -> {
            field(1) { text("NAMED_SELF", 64) }
            field(2) { actorSelection(value.requesters) }
            field(3) { actorSelection(value.approvers) }
            field(4) { ids(value.namedEmployeeIds, LocalCompanyPolicyFormatV1.MAX_NAMED_EMPLOYEES) }
        }
    }
}

private fun PurchasingWireReaderV1.approval(): PurchasingApprovalRouteV1 = when (field(1, 64) { text(64) }) {
    "DIRECT" -> PurchasingApprovalRouteV1.Direct(field(2) { actorSelection() })
    "SEPARATE" -> PurchasingApprovalRouteV1.Separate(
        requesters = field(2) { actorSelection() },
        approvers = field(3) { actorSelection() },
    )
    "NAMED_SELF" -> PurchasingApprovalRouteV1.NamedSelf(
        requesters = field(2) { actorSelection() },
        approvers = field(3) { actorSelection() },
        namedEmployeeIds = field(4) { ids(LocalCompanyPolicyFormatV1.MAX_NAMED_EMPLOYEES) },
    )
    else -> invalidVariant()
}

private fun PurchasingWireWriterV1.rule(value: PurchasingPolicyRuleV1) {
    field(1) { id(value.ruleId) }
    field(2) { id(value.delegationGrantId) }
    field(3) { enumeration(value.action) }
    field(4) { selection(value.selection) }
    field(5) { limits(value.limits) }
    field(6) { evidence(value.evidence) }
    field(7) { approval(value.approval) }
    field(8) { enumeration(value.acquisition) }
}

private fun PurchasingWireReaderV1.rule(): PurchasingPolicyRuleV1 = PurchasingPolicyRuleV1(
    ruleId = field(1, 36) { id() },
    delegationGrantId = field(2, 36) { id() },
    action = field(3, 64) { enumeration(PurchasingRuleActionV1.entries) },
    selection = field(4) { selection() },
    limits = field(5) { limits() },
    evidence = field(6) { evidence() },
    approval = field(7) { approval() },
    acquisition = field(8, 64) { enumeration(PurchasingAcquisitionProfileV1.entries) },
)

private fun PurchasingWireWriterV1.reservation(value: PurchasingSerialReservationV1) {
    field(1) { id(value.registryId) }
    field(2) { revision(value.registryRevision) }
    field(3) { id(value.reservationId) }
    field(4) { revision(value.reservationRevision) }
    field(5) { operation(value.originalIssueOperationId) }
    field(6) { digest(value.originalRequestDigest) }
}

private fun PurchasingWireReaderV1.reservation(): PurchasingSerialReservationV1 = PurchasingSerialReservationV1(
    registryId = field(1, 36) { id() },
    registryRevision = field(2, 8) { revision() },
    reservationId = field(3, 36) { id() },
    reservationRevision = field(4, 8) { revision() },
    originalIssueOperationId = field(5, 36) { operation() },
    originalRequestDigest = field(6, 64) { digest() },
)

private fun PurchasingWireWriterV1.id(value: DomainId) {
    text(value.value, 36)
}

private fun PurchasingWireReaderV1.id(): DomainId = DomainId(text(36))

private fun PurchasingWireWriterV1.ids(values: List<DomainId>, maximum: Int) {
    list(values, maximum) { id(it) }
}

private fun PurchasingWireReaderV1.ids(maximum: Int): List<DomainId> = list(maximum) { id() }

private fun PurchasingWireWriterV1.operation(value: OperationId) {
    text(value.value, 36)
}

private fun PurchasingWireReaderV1.operation(): OperationId = OperationId(text(36))

private fun PurchasingWireWriterV1.revision(value: Revision) {
    nonnegativeLong(value.value)
}

private fun PurchasingWireReaderV1.revision(): Revision = Revision(nonnegativeLong())

private fun PurchasingWireWriterV1.digest(value: PayloadDigest) {
    text(value.value, 64)
}

private fun PurchasingWireReaderV1.digest(): PayloadDigest = PayloadDigest(text(64))

private fun PurchasingWireWriterV1.instant(value: UtcInstant) {
    text(value.canonicalString(), 64)
}

private fun PurchasingWireReaderV1.instant(): UtcInstant = UtcInstant.parseCanonical(text(64))

private fun PurchasingWireWriterV1.percentage(value: PercentageRate) {
    text(value.percent.canonicalString(), 40)
}

private fun PurchasingWireReaderV1.percentage(): PercentageRate = PercentageRate(ExactDecimal.parseCanonical(text(40), 8))

private fun PurchasingWireWriterV1.currency(value: Currency) {
    require(value == Currency.EGP) { "Unsupported purchasing currency" }
    text("EGP", 3)
}

private fun PurchasingWireReaderV1.currency(): Currency {
    require(text(3) == "EGP") { "Unsupported purchasing currency" }
    return Currency.EGP
}

private fun PurchasingWireWriterV1.enumeration(value: Enum<*>) {
    text(value.name, 64)
}

private fun <E : Enum<E>> PurchasingWireReaderV1.enumeration(entries: List<E>): E {
    val label = text(64)
    return entries.firstOrNull { it.name == label } ?: invalidVariant()
}

private fun <T : Any> PurchasingWireWriterV1.optional(value: T?, write: PurchasingWireWriterV1.(T) -> Unit) {
    if (value == null) {
        u8(0)
    } else {
        u8(1)
        write(value)
    }
}

private fun <T : Any> PurchasingWireReaderV1.optional(read: PurchasingWireReaderV1.() -> T): T? = when (u8()) {
    0 -> null
    1 -> read()
    else -> invalidVariant()
}

private fun invalidVariant(): Nothing = throw IllegalArgumentException("Unknown purchasing value variant")

private fun encode(maximum: Int, write: PurchasingWireWriterV1.() -> Unit): ByteArray {
    val writer = PurchasingWireWriterV1(maximum)
    writer.write()
    return writer.finish()
}

private fun <T> decode(bytes: ByteArray, maximum: Int, read: PurchasingWireReaderV1.() -> T): T {
    try {
        // Reject outer size before making any owned copy. Decoding never borrows caller bytes.
        require(bytes.isNotEmpty() && bytes.size <= maximum) { "Purchasing input exceeds its profile" }
        val owned = bytes.copyOf()
        val reader = PurchasingWireReaderV1(owned, 0, owned.size)
        val value = reader.read()
        reader.requireEnd()
        return value
    } catch (_: Exception) {
        // Scalar/UTF-8 parsers may include input in their own errors. Do not retain a cause.
        throw IllegalArgumentException("Invalid untrusted local-company purchasing encoding")
    }
}

/**
 * One fixed-capacity buffer per public encode. Nested records share it: count maxima cannot
 * allocate a sequence of oversized body copies before the enclosing envelope limit is known.
 */
private class PurchasingWireWriterV1(maximum: Int) {
    private val buffer: ByteArray = ByteArray(maximum)
    private var position: Int = 0

    fun field(
        tag: Int,
        maximum: Int = LocalCompanyPolicyFormatV1.MAX_ENVELOPE_BYTES,
        write: PurchasingWireWriterV1.() -> Unit,
    ) {
        require(tag in 1..255) { "Invalid purchasing field tag" }
        reserve(5)
        u8(tag)
        val lengthPosition = position
        repeat(4) { u8(0) }
        val start = position
        write()
        val size = position - start
        require(size <= maximum) { "Purchasing field exceeds its byte bound" }
        for (index in 0 until 4) {
            buffer[lengthPosition + index] = (size ushr ((3 - index) * 8)).toByte()
        }
    }

    fun u8(value: Int) {
        require(value in 0..255) { "Purchasing byte overflow" }
        reserve(1)
        buffer[position++] = value.toByte()
    }

    fun u16(value: Int) {
        require(value in 0..65535) { "Purchasing short overflow" }
        reserve(2)
        u8(value ushr 8)
        u8(value and 255)
    }

    fun nonnegativeLong(value: Long) {
        require(value >= 0) { "Negative purchasing integer" }
        reserve(8)
        for (index in 0 until 8) {
            buffer[position++] = (value ushr ((7 - index) * 8)).toByte()
        }
    }

    fun text(value: String, maximumBytes: Int) {
        // UTF-8 byte length cannot be smaller than the UTF-16 code-unit length.
        require(value.length <= maximumBytes) { "Purchasing string exceeds its bound" }
        val bytes = try {
            value.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid purchasing UTF-8 string")
        }
        require(bytes.size <= maximumBytes) { "Purchasing string exceeds its UTF-8 bound" }
        put(bytes)
    }

    fun fixedBytes(value: ByteArray, size: Int) {
        require(value.size == size) { "Wrong purchasing public-byte size" }
        put(value)
    }

    fun <T> list(values: List<T>, maximum: Int, write: PurchasingWireWriterV1.(T) -> Unit) {
        val count = values.size
        require(count <= maximum) { "Purchasing list count exceeds its profile" }
        u16(count)
        var seen = 0
        for (value in values) {
            require(seen < count) { "Purchasing list changed during encoding" }
            field(1) { write(value) }
            seen++
        }
        require(seen == count) { "Purchasing list changed during encoding" }
    }

    fun finish(): ByteArray = buffer.copyOf(position)

    private fun put(value: ByteArray) {
        reserve(value.size)
        value.copyInto(buffer, position)
        position += value.size
    }

    private fun reserve(size: Int) {
        // Subtraction rather than position + size prevents integer wraparound.
        require(size >= 0 && size <= buffer.size - position) { "Purchasing encoding exceeds its byte bound" }
    }
}

/** Bounded slices share only the decoder's private snapshot, never an external array. */
private class PurchasingWireReaderV1(
    private val buffer: ByteArray,
    start: Int,
    private val end: Int,
) {
    private var position: Int = start
    private val remaining: Int get() = end - position

    fun <T> field(
        tag: Int,
        maximum: Int = LocalCompanyPolicyFormatV1.MAX_ENVELOPE_BYTES,
        read: PurchasingWireReaderV1.() -> T,
    ): T {
        requireRemaining(5)
        require(u8() == tag) { "Missing, unknown or noncanonical purchasing field" }
        var length = 0L
        repeat(4) { length = (length shl 8) or u8().toLong() }
        // The untrusted u32 is checked as Long before conversion, addition or allocation.
        require(length <= maximum.toLong() && length <= remaining.toLong()) {
            "Purchasing field length exceeds its enclosing bound"
        }
        val next = position + length.toInt()
        val fieldReader = PurchasingWireReaderV1(buffer, position, next)
        position = next
        val result = fieldReader.read()
        fieldReader.requireEnd()
        return result
    }

    fun u8(): Int {
        requireRemaining(1)
        return buffer[position++].toInt() and 255
    }

    fun u16(): Int {
        requireRemaining(2)
        return (u8() shl 8) or u8()
    }

    fun nonnegativeLong(): Long {
        require(remaining == 8) { "Purchasing integer has the wrong fixed width" }
        var value = 0L
        for (index in 0 until 8) {
            val octet = u8()
            require(index != 0 || octet < 128) { "Purchasing integer exceeds signed 64-bit range" }
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    fun text(maximumBytes: Int): String {
        require(remaining <= maximumBytes) { "Purchasing string exceeds its byte bound" }
        val value = buffer.decodeToString(position, end, throwOnInvalidSequence = true)
        position = end
        return value
    }

    fun fixedBytes(size: Int): ByteArray {
        require(remaining == size) { "Purchasing public bytes have the wrong fixed width" }
        val value = buffer.copyOfRange(position, end)
        position = end
        return value
    }

    fun <T> list(maximum: Int, read: PurchasingWireReaderV1.() -> T): List<T> {
        val count = u16()
        // Every element needs at least its five-byte field header; no multiplication overflow.
        require(count <= maximum && count <= remaining / 5) { "Purchasing list exceeds its enclosing bound" }
        val values = ArrayList<T>(count)
        repeat(count) { values.add(field(1) { read() }) }
        return values
    }

    fun requireEnd() {
        require(position == end) { "Trailing purchasing bytes" }
    }

    private fun requireRemaining(size: Int) {
        require(size >= 0 && size <= remaining) { "Truncated purchasing encoding" }
    }
}
