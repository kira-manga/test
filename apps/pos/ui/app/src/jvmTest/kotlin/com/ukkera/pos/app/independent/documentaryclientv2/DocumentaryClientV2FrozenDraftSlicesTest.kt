package com.ukkera.pos.app.independent.documentaryclientv2

import com.ukkera.pos.app.LocalPurchasingDocumentCaptureInput
import com.ukkera.pos.app.LocalPurchasingDocumentFileSelection
import com.ukkera.pos.app.capture
import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Shared input-validation slices only; no completed original, native history or Z evidence. */
class DocumentaryClientV2FrozenDraftSlicesTest {
    private val inputs = FrozenDocumentaryClientV2Inputs

    @Test
    fun di2_001_exactOriginalMetadataPassesThroughTheSharedDraft() {
        val parsed = assertIs<LocalPurchasingDocumentCaptureInput.Valid>(inputs.draft("PRIMARY-A")
            .capture(inputs.selectedFile("PDF-MULTI")))
        assertEquals(inputs.captureInput("PRIMARY-A"), parsed.capture)
        assertEquals("0007-A", parsed.capture.supplierReferenceOriginal)
        assertEquals(inputs.documentDate, parsed.capture.documentDate?.canonicalString())
        assertNull(parsed.capture.parentOriginalId)
        assertNull(parsed.capture.expectedParentAttachmentsRevision)
        assertNull(parsed.capture.correctionOf)
        assertNull(parsed.capture.correctionReason)
    }

    @Test
    fun di2_002_allFrozenPurposesAndDeclaredMediaRemainExactInputs() {
        for (purpose in inputs.purposes) {
            val parsed = assertIs<LocalPurchasingDocumentCaptureInput.Valid>(inputs.draft("PRIMARY-A")
                .copy(purpose = purpose).capture(inputs.selectedFile("PNG-A")))
            assertEquals(purpose, parsed.capture.purpose)
            assertEquals(PurchasingEvidenceMediaV1.PNG, parsed.capture.media)
        }
        for (file in listOf("PDF-A", "PNG-A", "JPEG-A")) {
            val selected = inputs.selectedFile(file)
            val parsed = assertIs<LocalPurchasingDocumentCaptureInput.Valid>(inputs.draft("PRIMARY-A").capture(selected))
            assertEquals(inputs.media(file), parsed.capture.media)
            assertEquals(inputs.fileName(file), parsed.capture.originalFileName)
            val bytes = inputs.fileBytes(file)
            val original = LocalPurchasingDocumentFileSelection.Selected(bytes, selected.originalFileName, selected.media)
            try {
                assertEquals(inputs.fileLength(file), original.byteCount)
                kotlin.test.assertContentEquals(bytes, original.copyBytes())
            } finally { original.destroy(); bytes.fill(0) }
        }
    }

    @Test
    fun di2_011_childAndDi2_015CorrectionKeepTheirDistinctFrozenLinks() {
        for (document in listOf("SMALL-CHILD", "CORRECTION-B")) {
            val parsed = assertIs<LocalPurchasingDocumentCaptureInput.Valid>(inputs.draft(document)
                .capture(inputs.selectedFile(inputs.fileLabel(document))))
            assertEquals(inputs.captureInput(document), parsed.capture)
        }
        val child = inputs.captureInput("SMALL-CHILD")
        assertEquals(inputs.documentId("PRIMARY-A"), child.parentOriginalId)
        assertEquals(inputs.parentRevision, child.expectedParentAttachmentsRevision)
        assertNull(child.correctionOf)
        val correction = inputs.captureInput("CORRECTION-B")
        assertEquals(inputs.documentId("PRIMARY-A"), correction.correctionOf)
        assertEquals("Supplier sent a revised documentary claim.", correction.correctionReason)
        assertNull(correction.parentOriginalId)
    }

    @Test
    fun di2_010_mutuallyPresentParentAndCorrectionCannotCreateAPublicCapture() {
        val child = inputs.captureInput("SMALL-CHILD")
        val correction = inputs.captureInput("CORRECTION-B")
        assertFailsWith<IllegalArgumentException> {
            child.copy(correctionOf = correction.correctionOf, correctionReason = correction.correctionReason)
        }
    }

    @Test
    fun di2_013_missingChildRevisionIsNotSilentlyDefaulted() {
        val child = inputs.draft("SMALL-CHILD")
        assertIs<LocalPurchasingDocumentCaptureInput.Invalid>(child.copy(parentAttachmentsRevision = "")
            .capture(inputs.selectedFile("PNG-A")))
        assertFailsWith<IllegalArgumentException> {
            inputs.captureInput("SMALL-CHILD").copy(expectedParentAttachmentsRevision = null)
        }
    }

    @Test
    fun di2_016_referenceSpellingSpacesAndSuppliedOrAbsentDateAreNotNormalized() {
        for (variant in inputs.duplicateVariants) {
            val input = variant.jsonObject
            val reference = input.text("reference")
            val date = input.optionalText("documentDate")
            val parsed = assertIs<LocalPurchasingDocumentCaptureInput.Valid>(inputs.draft("PRIMARY-A")
                .copy(supplierReferenceOriginal = reference, documentDate = date.orEmpty())
                .capture(inputs.selectedFile("PDF-A")))
            assertEquals(reference, parsed.capture.supplierReferenceOriginal)
            assertEquals(date, parsed.capture.documentDate?.canonicalString())
        }
    }

    @Test
    fun di2_027_exactAsciiTextCeilingsAreRetainedAndOneCharacterExcessIsRejected() {
        val primary = inputs.draft("PRIMARY-A")
        val validReference = inputs.textBoundary("supplierReference192")
        val validName = inputs.textBoundary("fileName192")
        val parsed = assertIs<LocalPurchasingDocumentCaptureInput.Valid>(primary
            .copy(supplierReferenceOriginal = validReference).capture(inputs.selectedFile("PDF-MULTI", validName)))
        assertEquals(validReference, parsed.capture.supplierReferenceOriginal)
        assertEquals(validName, parsed.capture.originalFileName)
        assertIs<LocalPurchasingDocumentCaptureInput.Invalid>(primary
            .copy(supplierReferenceOriginal = inputs.textBoundary("supplierReference193"))
            .capture(inputs.selectedFile("PDF-MULTI")))
        assertIs<LocalPurchasingDocumentCaptureInput.Invalid>(primary
            .capture(inputs.selectedFile("PDF-MULTI", inputs.textBoundary("fileName193"))))

        val correction = inputs.draft("CORRECTION-B")
        val validReason = inputs.textBoundary("correctionReason256")
        val corrected = assertIs<LocalPurchasingDocumentCaptureInput.Valid>(correction
            .copy(correctionReason = validReason).capture(inputs.selectedFile("PDF-B")))
        assertEquals(validReason, corrected.capture.correctionReason)
        assertIs<LocalPurchasingDocumentCaptureInput.Invalid>(correction
            .copy(correctionReason = inputs.textBoundary("correctionReason257"))
            .capture(inputs.selectedFile("PDF-B")))
    }

    @Test
    fun di2_027_frozenControlTextIsRejectedRatherThanCleanedOrTruncated() {
        assertIs<LocalPurchasingDocumentCaptureInput.Invalid>(inputs.draft("PRIMARY-A")
            .copy(supplierReferenceOriginal = inputs.controlText("supplierReferenceOriginal"))
            .capture(inputs.selectedFile("PDF-MULTI")))
        assertIs<LocalPurchasingDocumentCaptureInput.Invalid>(inputs.draft("PRIMARY-A")
            .capture(inputs.selectedFile("PDF-MULTI", inputs.controlText("originalFileName"))))
        assertIs<LocalPurchasingDocumentCaptureInput.Invalid>(inputs.draft("CORRECTION-B")
            .copy(correctionReason = inputs.controlText("correctionReason"))
            .capture(inputs.selectedFile("PDF-B")))
    }

    @Test
    fun di2_004_primaryAndChildLengthCeilingsRejectOnlyTheFrozenOverBoundInputs() {
        assertIs<LocalPurchasingDocumentCaptureInput.Valid>(inputs.draft("PRIMARY-A")
            .capture(inputs.selectedFile("PDF-PRIMARY-MAX")))
        assertIs<LocalPurchasingDocumentCaptureInput.Invalid>(inputs.draft("PRIMARY-A")
            .capture(inputs.selectedFile("PDF-PRIMARY-OVER")))
        assertIs<LocalPurchasingDocumentCaptureInput.Invalid>(inputs.draft("PRIMARY-A")
            .capture(inputs.selectedFile("EMPTY")))
        assertIs<LocalPurchasingDocumentCaptureInput.Valid>(inputs.draft("SMALL-CHILD")
            .capture(inputs.selectedFile("PDF-SUPPLEMENT-MAX")))
        assertIs<LocalPurchasingDocumentCaptureInput.Invalid>(inputs.draft("SMALL-CHILD")
            .capture(inputs.selectedFile("PDF-SUPPLEMENT-OVER")))
    }
}
