package com.ukkera.pos.app.gateway.independent.openingobligationv1

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Frozen A documentary values and explicit controlled UI bindings; no native authority. */
internal object OpeningSupplierObligationUiInputs {
    val scope = BranchScope(DomainId("11111111-1111-4111-8111-111111111111"),
        DomainId("31111111-1111-4111-8111-111111111111"), DomainId("41111111-1111-4111-8111-111111111111"))
    val actor = DomainId("61111111-1111-4111-8111-111111111111")
    val supplier = DomainId("51111111-1111-4111-8111-111111111111")
    val attachment = DomainId("91111111-1111-4111-8111-111111111111")
    val device = DomainId("a1111111-1111-4111-8111-111111111111")
    val session = DomainId("a2222222-2222-4222-8222-222222222222")
    val connection = DomainId("a3333333-3333-4333-8333-333333333333")
    val clock = DomainId("a4444444-4444-4444-8444-444444444444")
    val recordedAt = UtcInstant.parseCanonical("2026-10-30T12:00:00Z")
    const val source = "synthetic-opening-migration/source-0007"
    const val reference = " 0007 / OCT-26 "
    const val date = "2026-09-30"
    const val terms = "Original terms: due 2026-10-30; settlement authority is branch B1."
    const val reason = "opening.synthetic.migration"
    const val hash = "72fa3dc34f946e755b0a207e48b2af8cc23cf496763153e90274d03cf04c6071"
    val document = """
        SYNTHETIC QUALIFICATION DOCUMENT — NOT A REAL BUSINESS CLAIM
        Original label: A
        Organization identity: 11111111-1111-4111-8111-111111111111
        Company identity: 31111111-1111-4111-8111-111111111111
        Posting/settlement branch identity: 41111111-1111-4111-8111-111111111111
        Supplier identity: 51111111-1111-4111-8111-111111111111
        Stable migration source identity: synthetic-opening-migration/source-0007
        Original documentary reference between brackets: [ 0007 / OCT-26 ]
        Original documentary date: 2026-09-30
        Outstanding opening supplier obligation: EGP 1250.75; minor units 125075
        Original terms: due 2026-10-30; settlement authority is branch B1.
        This is migrated documentary supplier debt only. It records no new delivery, purchase, customer sale, payment or cash custody event.
    """.trimIndent() + "\n"

    private val wire = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
    fun <T> body(value: T, serializer: KSerializer<T>): StoredBytes =
        StoredBytes(wire.encodeToString(serializer, value).encodeToByteArray())

    fun request(obligation: DomainId) = LocalRecordOpeningSupplierObligationV1(
        obligationId = obligation, supplierId = supplier, expectedSupplierRevision = Revision(1), sourceEvidenceId = attachment,
        sourceReference = reference, sourceDocument = LocalOpeningSupplierDocumentaryEvidenceV1(source, document),
        openingDate = BusinessDate.parseCanonical(date), amount = Money(Currency.EGP, 125075), paymentTerms = terms, reasonCode = reason)

    fun hint(action: SecurityAction): PermissionGrant = PermissionGrant(Permission(action, PermissionVerb.PERFORM), scope,
        GrantEffect.ALLOW, PermissionLimits(maximumAmount = if (action == SecurityAction.PURCHASING_APPROVE) Money(Currency.EGP, 125075) else null),
        namedSubjectId = actor)
    fun sessionView(grants: List<PermissionGrant>): LocalSessionView = LocalSessionView(actor, scope, device, session,
        SessionGeneration(1), LocalLoginIdentifier("Synthetic opening operator"),
        ActivatedLocalPolicy(scope, actor, Revision(10), grants, LocalSessionPolicy(), ThrottlePolicy()),
        MonotonicMoment(clock, 1), MonotonicMoment(clock, 2))

    fun supplierReply(): ReadReply.Data {
        val names = LocalNamesV1("Synthetic supplier S1", "")
        val page = LocalConfigurationPageV1(LocalConfigurationProfile.SUPPLIER,
            listOf(LocalConfigurationListItemV1(supplier, Revision(0), Revision(1), names, true, null)), null,
            detail = LocalConfigurationDetailV1.Supplier(LocalSupplierV1(supplier, names, true)))
        return ReadReply.Data(body(page, LocalConfigurationPageV1.serializer()), Revision(1), recordedAt)
    }

    /** Supplied original view for gateway rendering only, never a Master/persistence observation. */
    fun view(obligation: DomainId, operation: OperationId) = LocalOpeningSupplierObligationViewV1(
        obligation, Revision(1), supplier, Revision(1), LocalNamesV1("Synthetic supplier S1", ""), attachment, reference,
        LocalOpeningSupplierDocumentaryEvidenceV1(source, document),
        LocalOpeningSupplierDocumentaryMetadataV1(scope, attachment, obligation, supplier, operation, actor, recordedAt, 777, hash,
            LocalOpeningSupplierObligationProfile.DOCUMENT_MEDIA_TYPE, LocalOpeningSupplierObligationProfile.DOCUMENT_PURPOSE,
            LocalOpeningSupplierObligationProfile.DOCUMENT_RETENTION, LocalOpeningSupplierObligationProfile.DOCUMENT_ACCESS_POLICY,
            LocalOpeningSupplierObligationProfile.DOCUMENT_UPLOAD_STATUS_AT_CAPTURE),
        BusinessDate.parseCanonical(date), Money(Currency.EGP, 125075), terms, reason, operation, actor, recordedAt, Revision(10))

    /** Controlled committed reply marker for the UI ACK path; not evidence of a business commit. */
    fun recordedReply(request: MutationRequest): MutationReply.Recorded {
        val original = LocalOpeningSupplierObligationCodec.decodeRequest(request.payload)
        return MutationReply.Recorded(request.operationId, actor, true, LocalOpeningSupplierObligationProfile.RECORDED,
            LocalOpeningSupplierObligationCodec.result(LocalOpeningSupplierObligationResultV1(
                LocalOpeningSupplierObligationProfile.RECORDED, original.obligationId, Revision(1))),
            Revision(1), JournalSequence(1), recordedAt, false)
    }
}
