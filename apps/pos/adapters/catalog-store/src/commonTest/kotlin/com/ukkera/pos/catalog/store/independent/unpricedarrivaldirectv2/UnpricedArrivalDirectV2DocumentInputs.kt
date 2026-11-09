package com.ukkera.pos.catalog.store.independent.unpricedarrivaldirectv2

import com.ukkera.pos.catalog.store.LocalBeginPurchasingDocumentV1
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/**
 * Exact file recipes/manifests projected from unchanged frozen UAD2 version 2.
 * Fixture SHA-256: c3e974d641c26e85fdc01f76d3928f7642d4d59145f141caee55eb48259c9032.
 * File text, lengths, hashes, original IDs, supplier/revision/reference/date and
 * supplemental parent/head revision are frozen inputs, not captured originals.
 *
 * Organization, retention UUID, policy-pointer coordinates/validity and parent-head
 * digest are inert carriers for unpublished native bindings. No authenticated policy,
 * retention duration/deadline, real parent membership or completed history is supplied.
 * DOC-S's actual head digest/current source remains UNBOUND; the carrier is not DOC-A's
 * byte hash and cannot be mistaken for its genuine authenticated attachment head.
 *
 * Bound: exact public content/hex/manifest/typed-request mappings in UAD2-03.
 * All seven whole cases remain NOT_RUN. The private documentary completion entry
 * needs a kernel-created CommandContext; no fabricated context/runtime is installed
 * here. Actual use/retention atomicity, current sources/grants, native capture/retrieval,
 * original history/restart, persistence, RPC and all native UI lanes remain UNBOUND.
 */
internal object Uad2DocumentInputs {
    internal data class FileInput(val label: String, val id: DomainId, val exactUtf8: String,
        val byteLength: Int, val digest: PayloadDigest, val fileName: String,
        val chunkDigests: List<PayloadDigest>, val parentId: DomainId?) {
        fun bytes(): ByteArray = exactUtf8.encodeToByteArray(throwOnInvalidSequence = true)
    }

    val scope = BranchScope(DomainId("90000000-0000-4000-8000-000000000001"),
        DomainId("8891f6af-3003-4a10-a40c-56830e000324"), DomainId("17256bce-b955-4c06-898f-b740d9c1f3de"))
    val supplier = DomainId("0d92ac11-e8ca-46e1-afcb-e57f219a7277")
    val policyId = DomainId("bab7c46a-cfcc-4b24-9691-4100b966c048")
    val publicationId = DomainId("6d698fcb-05b5-4c68-8fb3-28167865b648")
    val catalogId = DomainId("f62e89ab-9991-4419-a3e5-b3b629045189")
    val releaseId = DomainId("999805ba-c8de-4949-a775-a3ea5d185976")
    val recordRule = DomainId("af19e1f8-1764-47f0-b148-eb52d5a50511")
    val optionalRule = DomainId("96486728-e87f-4d62-a4e9-838d0f46bf2a")
    val retentionCarrier = DomainId("90000000-0000-4000-8000-000000000007")
    private val sourceDigestCarrier = PayloadDigest("0".repeat(64))
    val parentHeadDigestCarrier = PayloadDigest("f".repeat(64))

    val files = listOf(
        FileInput("DOC-A", DomainId("d7b0f975-8ae0-40d7-a9ec-34e698c25c2e"), "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>\nendobj\n4 0 obj\n<< /Length 71 >>\nstream\nBT\n/F1 12 Tf\n72 720 Td\n(SYN delivery evidence A - 24 units held) Tj\nET\nendstream\nendobj\n5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\nxref\n0 6\n0000000000 65535 f \n0000000009 00000 n \n0000000058 00000 n \n0000000115 00000 n \n0000000241 00000 n \n0000000361 00000 n \ntrailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n431\n%%EOF\n", 614, PayloadDigest("58d3e9e632f1b54b8237e895eef84f80cf5d1da8e5f69370b9ccb70bbe058797"), "doc-a.pdf", listOf(PayloadDigest("58d3e9e632f1b54b8237e895eef84f80cf5d1da8e5f69370b9ccb70bbe058797")), null),
        FileInput("DOC-B", DomainId("a3e37624-c21b-4c3b-b218-6bbd2d0e1459"), "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>\nendobj\n4 0 obj\n<< /Length 70 >>\nstream\nBT\n/F1 12 Tf\n72 720 Td\n(SYN delivery evidence B - 6 units held) Tj\nET\nendstream\nendobj\n5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\nxref\n0 6\n0000000000 65535 f \n0000000009 00000 n \n0000000058 00000 n \n0000000115 00000 n \n0000000241 00000 n \n0000000360 00000 n \ntrailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n430\n%%EOF\n", 613, PayloadDigest("2ea317ea2f23340a4f4bd5976f09d3019a26d8db465c5700f84ede93e2deeeb1"), "doc-b.pdf", listOf(PayloadDigest("2ea317ea2f23340a4f4bd5976f09d3019a26d8db465c5700f84ede93e2deeeb1")), null),
        FileInput("DOC-S", DomainId("f9b8538a-2981-4b62-84f8-5553528d0ab4"), "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>\nendobj\n4 0 obj\n<< /Length 66 >>\nstream\nBT\n/F1 12 Tf\n72 720 Td\n(SYN supplemental delivery evidence) Tj\nET\nendstream\nendobj\n5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\nxref\n0 6\n0000000000 65535 f \n0000000009 00000 n \n0000000058 00000 n \n0000000115 00000 n \n0000000241 00000 n \n0000000356 00000 n \ntrailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n426\n%%EOF\n", 609, PayloadDigest("28559abd8a96f8dce523b85b59994a6ac126b71309fa51d7fc48778b54afe33e"), "doc-s.pdf", listOf(PayloadDigest("28559abd8a96f8dce523b85b59994a6ac126b71309fa51d7fc48778b54afe33e")), DomainId("d7b0f975-8ae0-40d7-a9ec-34e698c25c2e")),
    )
    fun file(label: String): FileInput = files.single { it.label == label }

    fun manifest(file: FileInput): LocalArrivalDocumentSelectionV2 = LocalArrivalDocumentSelectionV2(scope,
        supplier, file.id, Revision(1), file.digest, file.byteLength, "PDF", "SUPPLIER_DELIVERY", file.parentId,
        file.parentId?.let { LocalArrivalSourceSelectionV2("purchasing.document.attachments", it, Revision(2), parentHeadDigestCarrier) })
    fun originals(): List<LocalArrivalDocumentSelectionV2> = listOf(manifest(file("DOC-A")), manifest(file("DOC-B")))

    fun evidence(documents: List<LocalArrivalDocumentSelectionV2> = Uad2DocumentInputs.originals(), optional: Boolean = false): LocalArrivalEvidenceSelectionV2 =
        LocalArrivalEvidenceSelectionV2("SYN Delivery 001",
            LocalArrivalPolicySelectionV2(scope, policyId, publicationId, Revision(8),
                LocalArrivalSourceSelectionV2("uad2.policy-active-carrier", scope.branchId, Revision(1), sourceDigestCarrier),
                LocalArrivalSourceSelectionV2("uad2.policy-publication-carrier", publicationId, Revision(1), sourceDigestCarrier),
                UtcInstant.parseCanonical("2026-11-01T00:00:00Z"), null),
            LocalArrivalCatalogSelectionV2(scope, catalogId, releaseId, Revision(7),
                LocalArrivalSourceSelectionV2("uad2.catalog-active-carrier", scope.branchId, Revision(1), sourceDigestCarrier),
                LocalArrivalSourceSelectionV2("uad2.catalog-publication-carrier", releaseId, Revision(1), sourceDigestCarrier)),
            if (optional) optionalRule else recordRule, documents, LocalArrivalRetentionSelectionV2(retentionCarrier, Revision(2)))

    fun begin(file: FileInput): LocalBeginPurchasingDocumentV1 = LocalBeginPurchasingDocumentV1(file.id, supplier,
        Revision(3), "SYN Delivery 001", BusinessDate.parseCanonical("2026-11-01"), PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY,
        PurchasingEvidenceMediaV1.PDF, file.fileName, file.byteLength, file.digest, file.chunkDigests, parentOriginalId = file.parentId)
}
