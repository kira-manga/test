package com.ukkera.pos.app.gateway.independent.openingobligationv1

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Exact frozen OSD-BND v1 input bytes; source fragments are not presentation part oracles. */
internal class OpeningDocumentaryBoundaryUiOriginal(
    val caseId: String, val fixtureId: String, val operation: OperationId, val obligation: DomainId,
    val expectedByteSize: Int, val assetSha256: String, input: ByteArray,
) {
    private val frozen = input.copyOf()
    val raw: ByteArray get() = frozen.copyOf()
    fun text(): String = frozen.decodeToString(throwOnInvalidSequence = true)
}

internal object OpeningSupplierDocumentaryBoundaryUiInputs {
    val scope = BranchScope(DomainId("e567a673-2e7e-4a0f-9d60-911849d2d99d"),
        DomainId("01cb8257-2c15-46be-a77d-4351bde5a20f"), DomainId("b48cbef5-12df-475c-8246-f0a075fe0d9d"))
    val actor = DomainId("1f292f7a-706f-40d4-bbe0-523b0306d0dc")
    val supplier = DomainId("6f6358e2-55b2-41d3-8f08-4e6074952918")
    val evidence = DomainId("a13be7b9-d480-4855-9167-1d1994a82803")
    const val source = "original-opening-ledger-2026-A"
    const val reference = "SYN-OPEN-A-2026-010"
    const val date = "2026-10-20"
    const val terms = "Payable on 2026-11-30; no opening payment."
    const val reason = "opening.synthetic.boundary"
    const val minor = 125075L

    // Explicit controlled client/session/capture bindings, not observed native authority.
    val device = DomainId("a1111111-1111-4111-8111-111111111111")
    val session = DomainId("a2222222-2222-4222-8222-222222222222")
    val connection = DomainId("a3333333-3333-4333-8333-333333333333")
    val clock = DomainId("a4444444-4444-4444-8444-444444444444")
    val recordedAt = UtcInstant.parseCanonical("2026-10-30T12:00:00Z")
    private val names = LocalNamesV1("Synthetic documentary boundary supplier", "مورد اصطناعي")

    fun hint(action: SecurityAction) = PermissionGrant(Permission(action, PermissionVerb.PERFORM), scope,
        GrantEffect.ALLOW, PermissionLimits(maximumAmount = if (action == SecurityAction.PURCHASING_APPROVE)
            Money(Currency.EGP, minor) else null), namedSubjectId = actor)

    fun sessionView() = LocalSessionView(actor, scope, device, session, SessionGeneration(1),
        LocalLoginIdentifier("Synthetic documentary boundary operator"),
        ActivatedLocalPolicy(scope, actor, Revision(10), listOf(hint(SecurityAction.PURCHASING_APPROVE),
            hint(SecurityAction.PURCHASING_READ), hint(SecurityAction.INVENTORY_COST_READ)),
            LocalSessionPolicy(), ThrottlePolicy()), MonotonicMoment(clock, 1), MonotonicMoment(clock, 2))

    fun supplierReply(): ReadReply.Data {
        val page = LocalConfigurationPageV1(LocalConfigurationProfile.SUPPLIER,
            listOf(LocalConfigurationListItemV1(supplier, Revision(0), Revision(1), names, true, null)), null,
            detail = LocalConfigurationDetailV1.Supplier(LocalSupplierV1(supplier, names, true)))
        return ReadReply.Data(OpeningSupplierObligationUiInputs.body(page, LocalConfigurationPageV1.serializer()),
            Revision(1), recordedAt)
    }

    fun request(original: OpeningDocumentaryBoundaryUiOriginal) = LocalRecordOpeningSupplierObligationV1(
        original.obligation, supplier, Revision(1), evidence, reference,
        LocalOpeningSupplierDocumentaryEvidenceV1(source, original.text()), BusinessDate.parseCanonical(date),
        Money(Currency.EGP, minor), terms, reason)

    fun intent(original: OpeningDocumentaryBoundaryUiOriginal, formVersion: Int) = LocalCommandIntent(
        original.operation, LocalAction.SUPPLIER_OPENING_RECORD, formVersion, null, listOf(
            LocalScalarInput(LocalField.OPENING_OBLIGATION_ID, LocalScalarValue.Identifier(original.obligation)),
            LocalScalarInput(LocalField.SUPPLIER_ID, LocalScalarValue.Reference(
                LocalRecordReference(LocalRecordKind.SUPPLIER, supplier, Revision(0)))),
            LocalScalarInput(LocalField.EVIDENCE_ID, LocalScalarValue.Identifier(evidence)),
            LocalScalarInput(LocalField.SOURCE_DOCUMENT_IDENTITY, LocalScalarValue.Text(source)),
            LocalScalarInput(LocalField.SOURCE_DOCUMENT_TEXT, LocalScalarValue.Text(original.text())),
            LocalScalarInput(LocalField.EXTERNAL_REFERENCE, LocalScalarValue.Text(reference)),
            LocalScalarInput(LocalField.BUSINESS_DATE, LocalScalarValue.Date(date)),
            LocalScalarInput(LocalField.AMOUNT, LocalScalarValue.Decimal("1250.75")),
            LocalScalarInput(LocalField.PAYMENT_TERMS, LocalScalarValue.Text(terms)),
            LocalScalarInput(LocalField.REASON, LocalScalarValue.Text(reason))))

    /** Independent supplied read projection; staging does not produce or post this view. */
    fun view(original: OpeningDocumentaryBoundaryUiOriginal) = LocalOpeningSupplierObligationViewV1(
        original.obligation, Revision(1), supplier, Revision(1), names, evidence, reference,
        LocalOpeningSupplierDocumentaryEvidenceV1(source, original.text()),
        LocalOpeningSupplierDocumentaryMetadataV1(scope, evidence, original.obligation, supplier,
            original.operation, actor, recordedAt, original.raw.size, sha256(original.raw).value,
            LocalOpeningSupplierObligationProfile.DOCUMENT_MEDIA_TYPE, LocalOpeningSupplierObligationProfile.DOCUMENT_PURPOSE,
            LocalOpeningSupplierObligationProfile.DOCUMENT_RETENTION, LocalOpeningSupplierObligationProfile.DOCUMENT_ACCESS_POLICY,
            LocalOpeningSupplierObligationProfile.DOCUMENT_UPLOAD_STATUS_AT_CAPTURE),
        BusinessDate.parseCanonical(date), Money(Currency.EGP, minor), terms, reason,
        original.operation, actor, recordedAt, Revision(10))

    val accepted = listOf(
        OpeningDocumentaryBoundaryUiOriginal(
            caseId = "OSD-BND-01", fixtureId = "ascii-1",
            operation = OperationId("bb9bf447-ab7c-4bc5-a1ee-c1ce53f155db"), obligation = DomainId("87a73df0-52f9-4ac7-a80a-fb987508a072"),
            expectedByteSize = 1, assetSha256 = "4b68ab3847feda7d6c62c1fbcbeebfa35eab7351ed5e78f4ddadea5df64b8015",
            input = ("X").encodeToByteArray(throwOnInvalidSequence = true),
        ),
        OpeningDocumentaryBoundaryUiOriginal(
            caseId = "OSD-BND-01", fixtureId = "ascii-4096",
            operation = OperationId("5a0c5d06-c111-4be6-b745-cd54a3585993"), obligation = DomainId("5244b7c7-eb53-4f19-b862-a530fc0696a8"),
            expectedByteSize = 4096, assetSha256 = "ab32f483dd05f61413c31130f0dc6e7231830a1bee54092b6a8d7a1bd66354ee",
            input = ("SUPPLIER ORIGINAL STATEMENT\n" +
            "Reference: SYN-OPEN-A-2026-010\n" +
            "Amount: EGP 1250.75\n" +
            "Terms: due 2026-11-30\n" +
            "Details:\n" +
            "001 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "002 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "003 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "004 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "005 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "006 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "007 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "008 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "009 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "010 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "011 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "012 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "013 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "014 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "015 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "016 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "017 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "018 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "019 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "020 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "021 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "022 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "023 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "024 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "025 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "026 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "027 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "028 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "029 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "030 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "031 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "032 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "033 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "034 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "035 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "036 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "Retained original spacing:                                                                                       \n" +
            "END ORIGINAL 000010\n").encodeToByteArray(throwOnInvalidSequence = true),
        ),
        OpeningDocumentaryBoundaryUiOriginal(
            caseId = "OSD-BND-02", fixtureId = "unicode-4096",
            operation = OperationId("9e046d3c-75ff-42df-b92e-37db4f5ef7c0"), obligation = DomainId("4c1e20be-b9ff-47d4-b580-a9f9b08a2389"),
            expectedByteSize = 4096, assetSha256 = "b44aa3abdb56fc9e5b6c3c54a5d3c84c277e14f1a4bb05c2ff3ba92c1ac3a593",
            input = ("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA🙂BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB🚀CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCC😀DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDموردéé\n").encodeToByteArray(throwOnInvalidSequence = true),
        ),
        OpeningDocumentaryBoundaryUiOriginal(
            caseId = "OSD-BND-03", fixtureId = "whitespace-newlines",
            operation = OperationId("8282bca6-fe1c-4aab-97bc-88a3dc392283"), obligation = DomainId("9a4f0a7b-9f52-4a9c-9d4f-846b2e7fba69"),
            expectedByteSize = 148, assetSha256 = "50e40b4c041fdfd9b08f7a2feeebbe67421d45ad2b8dac85dc0343bc6c630b57",
            input = (" \t\r\n" +
            "  ORIGINAL SOURCE 0007 \t\n" +
            "Supplier: مورد\r" +
            "Reference: 000007\r\n" +
            "Amount: EGP 1250.75\n" +
            "Terms: due 2026-11-30\r\n" +
            "Spelling: café | café\r\n" +
            "\r\n" +
            "End.\t  \n" +
            " \t").encodeToByteArray(throwOnInvalidSequence = true),
        )
    )
}
