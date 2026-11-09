package com.ukkera.pos.catalog.store

import com.ukkera.pos.inventory.PurchaseInvoiceCaptureV2
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.types.Revision

/** An immutable documentary source association, without changing its capture qualification. */
internal data class PurchasingCapturedOriginalResolutionV1(
    val source: PurchasingDocumentSource?,
    val refusalCode: String?,
    val resources: Set<RecoveryResourceKey>,
) {
    init { check((source == null) == (refusalCode != null)) }
}

/**
 * Uses the supplied reader's exact cut, including genuine BEFORE sources during history joins.
 * A consistent absent, incomplete or incompatible selection has a business refusal. Damaged
 * producers, identities, source links or captured bytes propagate as integrity uncertainty.
 * This association supplies neither supplier authenticity nor approval or financial authority.
 */
internal suspend fun purchasingCapturedOriginal(
    reader: PurchasingDocumentReader,
    invoice: PurchaseInvoiceCaptureV2,
    expectedOriginalRevision: Revision,
): PurchasingCapturedOriginalResolutionV1 {
    require(expectedOriginalRevision.value >= 1)
    val id = invoice.sourceEvidenceId
    val intake = reader.row(LocalPurchasingDocumentProfileV1.INTAKE, id)
    val file = reader.file(id)
    val ownHead = reader.row(LocalPurchasingDocumentProfileV1.ATTACHMENTS, id)
    check(file == null || intake != null)
    check(ownHead == null || file?.value?.kind == LocalPurchasingDocumentProfileV1.ORIGINAL)

    val declaration = (intake?.value?.body as? PurchasingDocumentBody.Intake)?.value
    if (intake != null) check(declaration != null)
    if (file != null) {
        reader.requireFile(file)
    } else if (intake != null) {
        // Missing transfer slots are legitimate only while the selected capture is incomplete.
        // The reader still validates every present slot and rejects dangling slot identities.
        val (temporaryBytes, _) = reader.bytes(intake, allowIncomplete = true)
        temporaryBytes?.fill(0)
    }

    declaration?.let { captured ->
        captured.parentSource?.let { source ->
            val parent = reader.requireSource(source)
            reader.requireFile(parent)
            check((parent.value.body as PurchasingDocumentBody.Captured).intake.request.supplierId ==
                captured.request.supplierId)
        }
        captured.correctionSource?.let { source ->
            val corrected = reader.requireSource(source)
            reader.requireFile(corrected)
            check((corrected.value.body as PurchasingDocumentBody.Captured).intake.request.supplierId ==
                captured.request.supplierId)
        }
        val referenceOwner = reader.claim(purchasingDocumentReferenceClaim(reader.scope, captured.request))
        check(referenceOwner == if (file?.value?.kind == LocalPurchasingDocumentProfileV1.ORIGINAL) id else null)
    }

    if (file == null || file.value.kind != LocalPurchasingDocumentProfileV1.ORIGINAL) {
        return PurchasingCapturedOriginalResolutionV1(null, "purchasing.original_required", reader.resources.toSet())
    }
    reader.head(file)
    if (file.row.revision != expectedOriginalRevision) {
        return PurchasingCapturedOriginalResolutionV1(null, "purchasing.original_revision_conflict", reader.resources.toSet())
    }
    val original = (file.value.body as PurchasingDocumentBody.Captured).intake.request
    if (original.supplierId != invoice.supplierId || original.supplierReferenceOriginal != invoice.reference ||
        original.documentDate != invoice.invoiceDate) {
        return PurchasingCapturedOriginalResolutionV1(null, "purchasing.original_association_mismatch", reader.resources.toSet())
    }
    return PurchasingCapturedOriginalResolutionV1(purchasingDocumentSource(file), null, reader.resources.toSet())
}
