package com.ukkera.pos.catalog.store.independent.openingobligationv1

import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalOpeningSupplierDocumentaryEvidenceV1
import com.ukkera.pos.catalog.store.LocalOpeningSupplierDocumentaryMetadataV1
import com.ukkera.pos.catalog.store.LocalOpeningSupplierObligationProfile
import com.ukkera.pos.catalog.store.LocalOpeningSupplierObligationViewV1
import com.ukkera.pos.catalog.store.LocalRecordOpeningSupplierObligationV1
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.JsonPrimitive

/** Exact synthetic inputs for frozen opening-supplier-obligation v1; no storage or authority. */
internal object OpeningSupplierObligationFrozenInputs {
    val scope = BranchScope(DomainId("11111111-1111-4111-8111-111111111111"),
        DomainId("31111111-1111-4111-8111-111111111111"), DomainId("41111111-1111-4111-8111-111111111111"))
    val actor = DomainId("61111111-1111-4111-8111-111111111111")
    val recordedAt = UtcInstant.parseCanonical("2026-10-30T12:00:00Z")
    const val reason = "opening.synthetic.migration"

    val originals = listOf(
        Original("A", "81111111-1111-4111-8111-111111111111", "51111111-1111-4111-8111-111111111111",
            "71111111-1111-4111-8111-111111111111", "91111111-1111-4111-8111-111111111111",
            "synthetic-opening-migration/source-0007", " 0007 / OCT-26 ", "2026-09-30", 125075,
            "Original terms: due 2026-10-30; settlement authority is branch B1.",
            """
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
            """.trimIndent() + "\n", 777, "72fa3dc34f946e755b0a207e48b2af8cc23cf496763153e90274d03cf04c6071"),
        Original("B", "82222222-2222-4222-8222-222222222222", "51111111-1111-4111-8111-111111111111",
            "72222222-2222-4222-8222-222222222222", "92222222-2222-4222-8222-222222222222",
            "synthetic-opening-migration/source-0008", "0008 / OCT-26", "2026-10-05", 55270,
            "Original terms: due 2026-11-04; settlement authority is branch B1.",
            """
                SYNTHETIC QUALIFICATION DOCUMENT — NOT A REAL BUSINESS CLAIM
                Original label: B
                Organization identity: 11111111-1111-4111-8111-111111111111
                Company identity: 31111111-1111-4111-8111-111111111111
                Posting/settlement branch identity: 41111111-1111-4111-8111-111111111111
                Supplier identity: 51111111-1111-4111-8111-111111111111
                Stable migration source identity: synthetic-opening-migration/source-0008
                Original documentary reference between brackets: [0008 / OCT-26]
                Original documentary date: 2026-10-05
                Outstanding opening supplier obligation: EGP 552.70; minor units 55270
                Original terms: due 2026-11-04; settlement authority is branch B1.
                This is migrated documentary supplier debt only. It records no new delivery, purchase, customer sale, payment or cash custody event.
            """.trimIndent() + "\n", 773, "99adced139504fd38c653654d803713df8251ec09f9fb38bdbe8b871fb142ce0"),
        Original("C", "83333333-3333-4333-8333-333333333333", "52222222-2222-4222-8222-222222222222",
            "73333333-3333-4333-8333-333333333333", "93333333-3333-4333-8333-333333333333",
            "synthetic-opening-migration/source-0012", "0012 / OCT-26", "2026-10-10", 99655,
            "Original terms: due 2026-11-15; settlement authority is branch B1.",
            """
                SYNTHETIC QUALIFICATION DOCUMENT — NOT A REAL BUSINESS CLAIM
                Original label: C
                Organization identity: 11111111-1111-4111-8111-111111111111
                Company identity: 31111111-1111-4111-8111-111111111111
                Posting/settlement branch identity: 41111111-1111-4111-8111-111111111111
                Supplier identity: 52222222-2222-4222-8222-222222222222
                Stable migration source identity: synthetic-opening-migration/source-0012
                Original documentary reference between brackets: [0012 / OCT-26]
                Original documentary date: 2026-10-10
                Outstanding opening supplier obligation: EGP 996.55; minor units 99655
                Original terms: due 2026-11-15; settlement authority is branch B1.
                This is migrated documentary supplier debt only. It records no new delivery, purchase, customer sale, payment or cash custody event.
            """.trimIndent() + "\n", 773, "d89d0f9d205847ca9d957508a15e0031023a4a2bd4389663ff01d157d44a2c83"),
    )

    val invalidMinor = listOf("125075.1", "1e4", "NaN", "Infinity", "9223372036854775808", "-9223372036854775809")

    data class Original(
        val label: String, val obligation: String, val supplier: String, val operation: String, val attachment: String,
        val source: String, val reference: String, val date: String, val minor: Long, val terms: String,
        val document: String, val byteSize: Int, val hash: String,
    ) {
        fun request() = LocalRecordOpeningSupplierObligationV1(
            obligationId = DomainId(obligation), supplierId = DomainId(supplier), expectedSupplierRevision = Revision(1),
            sourceEvidenceId = DomainId(attachment), sourceReference = reference,
            sourceDocument = LocalOpeningSupplierDocumentaryEvidenceV1(source, document),
            openingDate = BusinessDate.parseCanonical(date), amount = Money(Currency.EGP, minor),
            paymentTerms = terms, reasonCode = reason)

        /** Supplied capture metadata for read-wire assertions; never an observed Master record. */
        fun view() = LocalOpeningSupplierObligationViewV1(
            id = DomainId(obligation), revision = Revision(1), supplierId = DomainId(supplier), supplierRevision = Revision(1),
            supplierNames = LocalNamesV1("Synthetic supplier ${if (label == "C") "S2" else "S1"}", ""),
            sourceEvidenceId = DomainId(attachment), sourceReference = reference,
            sourceDocument = LocalOpeningSupplierDocumentaryEvidenceV1(source, document),
            sourceDocumentMetadata = LocalOpeningSupplierDocumentaryMetadataV1(scope, DomainId(attachment), DomainId(obligation),
                DomainId(supplier), OperationId(operation), actor, recordedAt, byteSize, hash,
                LocalOpeningSupplierObligationProfile.DOCUMENT_MEDIA_TYPE, LocalOpeningSupplierObligationProfile.DOCUMENT_PURPOSE,
                LocalOpeningSupplierObligationProfile.DOCUMENT_RETENTION, LocalOpeningSupplierObligationProfile.DOCUMENT_ACCESS_POLICY,
                LocalOpeningSupplierObligationProfile.DOCUMENT_UPLOAD_STATUS_AT_CAPTURE),
            openingDate = BusinessDate.parseCanonical(date), amount = Money(Currency.EGP, minor), paymentTerms = terms,
            reasonCode = reason, operationId = OperationId(operation), actorId = actor, recordedAt = recordedAt,
            policyRevision = Revision(10))

        /** Literal public v1 JSON shape with independently supplied documentary values. */
        fun rawRequest(minorToken: String = quote(minor.toString()), tail: String = ""): String =
            """{"obligationId":${quote(obligation)},"supplierId":${quote(supplier)},"expectedSupplierRevision":"1","sourceEvidenceId":${quote(attachment)},"sourceReference":${quote(reference)},"sourceDocument":{"sourceIdentity":${quote(source)},"text":${quote(document)}},"openingDate":${quote(date)},"amount":{"currency":"EGP","minor":$minorToken},"paymentTerms":${quote(terms)},"reasonCode":${quote(reason)}$tail}"""
    }

    fun quote(value: String): String = JsonPrimitive(value).toString()
}
