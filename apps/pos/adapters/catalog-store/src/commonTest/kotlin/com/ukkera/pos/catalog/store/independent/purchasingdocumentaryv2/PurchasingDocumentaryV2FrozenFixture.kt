package com.ukkera.pos.catalog.store.independent.purchasingdocumentaryv2

import com.ukkera.pos.catalog.store.LocalBeginPurchasingDocumentV1
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import com.ukkera.pos.security.PurchasingEvidencePurposeV1
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Exact portable projection of unchanged purchasing-documentary-intake-v2 inputs.
 * Bytes expand the frozen base64/padding recipes; hashes are the frozen literals.
 * Document/step IDs are case inputs, never native result/history/completion evidence.
 * Supplier/revision/scope values below are inert DTO/group-key carriers only.
 * No supplier provisioning, current grants, authoritative actor or custody is supplied.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object PurchasingDocumentaryV2FrozenFixture {
    internal data class ChunkRun(val firstIndex: Int, val count: Int, val byteLength: Int, val digest: PayloadDigest)
    internal data class FileInput(val alias: String, val media: PurchasingEvidenceMediaV1,
        val byteLength: Int, val digest: PayloadDigest, val chunkRuns: List<ChunkRun>,
        val base64: String? = null, val baseFile: String? = null, val prefixHex: String = "",
        val repeatHex: String = "", val repeatCount: Int = 0, val suffixHex: String = "") {
        val chunkDigests: List<PayloadDigest> get() = chunkRuns.flatMap { run -> List(run.count) { run.digest } }
        val chunkLengths: List<Int> get() = chunkRuns.flatMap { run -> List(run.count) { run.byteLength } }
    }
    internal data class DocumentInput(val alias: String, val ordinal: Int, val id: DomainId,
        val fileAlias: String, val beginOperation: OperationId, val chunkOperations: List<OperationId>,
        val completeOperation: OperationId, val parentAlias: String? = null,
        val correctionAlias: String? = null, val correctionReason: String? = null)

    internal val supplierIdCarrier: DomainId = DomainId("d0000000-0000-4000-8000-000000000001")
    internal val supplierRevisionCarrier: Revision = Revision(1)
    internal val scopeCarrier: BranchScope = BranchScope(
        DomainId("d0000000-0000-4000-8000-000000000002"),
        DomainId("d0000000-0000-4000-8000-000000000003"),
        DomainId("d0000000-0000-4000-8000-000000000004"))
    internal val reference: String = "0007-A"
    internal val date: BusinessDate = BusinessDate.parseCanonical("2026-10-21")
    internal val defaultFileName: String = "supplier-0007-A.pdf"
    internal val falseFullDigest: PayloadDigest = PayloadDigest("0000000000000000000000000000000000000000000000000000000000000000")
    internal val corruptSlotDigest: PayloadDigest = PayloadDigest("fb10e42630e1398e122566669d9c8cca9e8b72f4293c08070aaa5ea24b956797")
    internal val shortSlotDigest: PayloadDigest = PayloadDigest("1dead01e28b3338571de95679976a03129c1f2803c16049829bd57ff823290a6")

    internal val files: Map<String, FileInput> = listOf(
        FileInput("PDF-A", PurchasingEvidenceMediaV1.PDF, 640,
            PayloadDigest("96c72b8140b230b776d119cce2bac959aa9f2211af5e85e9b5652571568c1728"), listOf(
                ChunkRun(0, 1, 640,
                    PayloadDigest("96c72b8140b230b776d119cce2bac959aa9f2211af5e85e9b5652571568c1728")),
            ),
            base64 = "JVBERi0xLjQKMSAwIG9iago8PCAvVHlwZSAvQ2F0YWxvZyAvUGFnZXMgMiAwIFIgPj4KZW5kb2JqCjIgMCBvYmoKPDwgL1R5cGUgL1BhZ2VzIC9LaWRzIFszIDAgUl0gL0NvdW50IDEgPj4KZW5kb2JqCjMgMCBvYmoKPDwgL1R5cGUgL1BhZ2UgL1BhcmVudCAyIDAgUiAvTWVkaWFCb3ggWzAgMCAzMDAgMjAwXSAvUmVzb3VyY2VzIDw8IC9Gb250IDw8IC9GMSA0IDAgUiA+PiA+PiAvQ29udGVudHMgNSAwIFIgPj4KZW5kb2JqCjQgMCBvYmoKPDwgL1R5cGUgL0ZvbnQgL1N1YnR5cGUgL1R5cGUxIC9CYXNlRm9udCAvSGVsdmV0aWNhID4+CmVuZG9iago1IDAgb2JqCjw8IC9MZW5ndGggOTcgPj4Kc3RyZWFtCkJUIC9GMSAxMCBUZiAyMCAxNzAgVGQgKFN5bnRoZXRpYyBzdXBwbGllciByZWZlcmVuY2UgMDAwNy1BKSBUaiAwIC0xNiBUZCAoQ2xhaW06IDI0IHVuaXRzKSBUaiBFVAplbmRzdHJlYW0KZW5kb2JqCnhyZWYKMCA2CjAwMDAwMDAwMDAgNjU1MzUgZiAKMDAwMDAwMDAwOSAwMDAwMCBuIAowMDAwMDAwMDU4IDAwMDAwIG4gCjAwMDAwMDAxMTUgMDAwMDAgbiAKMDAwMDAwMDI0MSAwMDAwMCBuIAowMDAwMDAwMzExIDAwMDAwIG4gCnRyYWlsZXIKPDwgL1NpemUgNiAvUm9vdCAxIDAgUiA+PgpzdGFydHhyZWYKNDU3CiUlRU9GCg=="),
        FileInput("PDF-B", PurchasingEvidenceMediaV1.PDF, 640,
            PayloadDigest("b9c7954c51321b8d8139f019fa011b090e80e5f0fdac3f905a91f774b3edd104"), listOf(
                ChunkRun(0, 1, 640,
                    PayloadDigest("b9c7954c51321b8d8139f019fa011b090e80e5f0fdac3f905a91f774b3edd104")),
            ),
            base64 = "JVBERi0xLjQKMSAwIG9iago8PCAvVHlwZSAvQ2F0YWxvZyAvUGFnZXMgMiAwIFIgPj4KZW5kb2JqCjIgMCBvYmoKPDwgL1R5cGUgL1BhZ2VzIC9LaWRzIFszIDAgUl0gL0NvdW50IDEgPj4KZW5kb2JqCjMgMCBvYmoKPDwgL1R5cGUgL1BhZ2UgL1BhcmVudCAyIDAgUiAvTWVkaWFCb3ggWzAgMCAzMDAgMjAwXSAvUmVzb3VyY2VzIDw8IC9Gb250IDw8IC9GMSA0IDAgUiA+PiA+PiAvQ29udGVudHMgNSAwIFIgPj4KZW5kb2JqCjQgMCBvYmoKPDwgL1R5cGUgL0ZvbnQgL1N1YnR5cGUgL1R5cGUxIC9CYXNlRm9udCAvSGVsdmV0aWNhID4+CmVuZG9iago1IDAgb2JqCjw8IC9MZW5ndGggOTcgPj4Kc3RyZWFtCkJUIC9GMSAxMCBUZiAyMCAxNzAgVGQgKFN5bnRoZXRpYyBzdXBwbGllciByZWZlcmVuY2UgMDAwNy1BKSBUaiAwIC0xNiBUZCAoQ2xhaW06IDIyIHVuaXRzKSBUaiBFVAplbmRzdHJlYW0KZW5kb2JqCnhyZWYKMCA2CjAwMDAwMDAwMDAgNjU1MzUgZiAKMDAwMDAwMDAwOSAwMDAwMCBuIAowMDAwMDAwMDU4IDAwMDAwIG4gCjAwMDAwMDAxMTUgMDAwMDAgbiAKMDAwMDAwMDI0MSAwMDAwMCBuIAowMDAwMDAwMzExIDAwMDAwIG4gCnRyYWlsZXIKPDwgL1NpemUgNiAvUm9vdCAxIDAgUiA+PgpzdGFydHhyZWYKNDU3CiUlRU9GCg=="),
        FileInput("PNG-A", PurchasingEvidenceMediaV1.PNG, 69,
            PayloadDigest("be82c36a6dd76c6ed5ae06589c17f2ce8f50e24dcac22ea4e0cc9ab396374203"), listOf(
                ChunkRun(0, 1, 69,
                    PayloadDigest("be82c36a6dd76c6ed5ae06589c17f2ce8f50e24dcac22ea4e0cc9ab396374203")),
            ),
            base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR42mNoaGgAAAMEAYF1LgG8AAAAAElFTkSuQmCC"),
        FileInput("JPEG-A", PurchasingEvidenceMediaV1.JPEG, 155,
            PayloadDigest("3f93bab029856dfaa0d8d9eceeceaa5977e4381e9209ed61f6f7af51c622147f"), listOf(
                ChunkRun(0, 1, 155,
                    PayloadDigest("3f93bab029856dfaa0d8d9eceeceaa5977e4381e9209ed61f6f7af51c622147f")),
            ),
            base64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wAALCAABAAEBAREA/8QAJgABAAAAAAAAAAAAAAAAAAAAABABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQAAPwA//9k="),
        FileInput("PDF-MULTI", PurchasingEvidenceMediaV1.PDF, 16407,
            PayloadDigest("4b41fe413290807cdf3b6f60a34b68e06d0e7cb092b9ffde0ef022d24b375bbe"), listOf(
                ChunkRun(0, 1, 8192,
                    PayloadDigest("6b266d19a750c70aa21a4615af76e12c961e9cb0eeafe0c247490f3e6f2b79db")),
                ChunkRun(1, 1, 8192,
                    PayloadDigest("f8ca02c69621dd84cd1212ebfd7d6cdc9ba6ad658854f29567723531912d1a35")),
                ChunkRun(2, 1, 23,
                    PayloadDigest("e49ff8fc09127f458830d7328b0aaabed46cab5bbeb1a22e4c93d762025be281")),
            ),
            baseFile = "PDF-A", prefixHex = "25", repeatHex = "41",
            repeatCount = 15765, suffixHex = "0a"),
        FileInput("PDF-SLOT-8192", PurchasingEvidenceMediaV1.PDF, 8192,
            PayloadDigest("d343f9fddf495ebc0c68067a5f6a8c8e36b032050e3270e8970a77b77f9d8b6f"), listOf(
                ChunkRun(0, 1, 8192,
                    PayloadDigest("d343f9fddf495ebc0c68067a5f6a8c8e36b032050e3270e8970a77b77f9d8b6f")),
            ),
            baseFile = "PDF-A", prefixHex = "25", repeatHex = "41",
            repeatCount = 7550, suffixHex = "0a"),
        FileInput("PDF-SLOT-8193", PurchasingEvidenceMediaV1.PDF, 8193,
            PayloadDigest("068f0e8cd71bd1f5912f6bab2ee938f5813b480153f7b1ab4a2a63615c09800c"), listOf(
                ChunkRun(0, 1, 8192,
                    PayloadDigest("6b266d19a750c70aa21a4615af76e12c961e9cb0eeafe0c247490f3e6f2b79db")),
                ChunkRun(1, 1, 1,
                    PayloadDigest("01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b")),
            ),
            baseFile = "PDF-A", prefixHex = "25", repeatHex = "41",
            repeatCount = 7551, suffixHex = "0a"),
        FileInput("PDF-PRIMARY-MAX", PurchasingEvidenceMediaV1.PDF, 524288,
            PayloadDigest("d4be1f2da7c46e33c380f0ef1888792ced5455bbe63c9d893065f9d720ea6f75"), listOf(
                ChunkRun(0, 1, 8192,
                    PayloadDigest("6b266d19a750c70aa21a4615af76e12c961e9cb0eeafe0c247490f3e6f2b79db")),
                ChunkRun(1, 62, 8192,
                    PayloadDigest("f8ca02c69621dd84cd1212ebfd7d6cdc9ba6ad658854f29567723531912d1a35")),
                ChunkRun(63, 1, 8192,
                    PayloadDigest("e657b9d3fe62b6088694966042a878effe01a7575805c7c90543ce85864e5539")),
            ),
            baseFile = "PDF-A", prefixHex = "25", repeatHex = "41",
            repeatCount = 523646, suffixHex = "0a"),
        FileInput("PDF-PRIMARY-OVER", PurchasingEvidenceMediaV1.PDF, 524289,
            PayloadDigest("dd2ccfdfa848838a6c65b60211d910e0f09c4c9dd8ecd3f05019e83021b8434e"), listOf(
                ChunkRun(0, 1, 8192,
                    PayloadDigest("6b266d19a750c70aa21a4615af76e12c961e9cb0eeafe0c247490f3e6f2b79db")),
                ChunkRun(1, 63, 8192,
                    PayloadDigest("f8ca02c69621dd84cd1212ebfd7d6cdc9ba6ad658854f29567723531912d1a35")),
                ChunkRun(64, 1, 1,
                    PayloadDigest("01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b")),
            ),
            baseFile = "PDF-A", prefixHex = "25", repeatHex = "41",
            repeatCount = 523647, suffixHex = "0a"),
        FileInput("PDF-SUPPLEMENT-MAX", PurchasingEvidenceMediaV1.PDF, 262144,
            PayloadDigest("71aa3b6df241580eadb03902c9f420af717264875b5c012fd11fd18ad6490863"), listOf(
                ChunkRun(0, 1, 8192,
                    PayloadDigest("6b266d19a750c70aa21a4615af76e12c961e9cb0eeafe0c247490f3e6f2b79db")),
                ChunkRun(1, 30, 8192,
                    PayloadDigest("f8ca02c69621dd84cd1212ebfd7d6cdc9ba6ad658854f29567723531912d1a35")),
                ChunkRun(31, 1, 8192,
                    PayloadDigest("e657b9d3fe62b6088694966042a878effe01a7575805c7c90543ce85864e5539")),
            ),
            baseFile = "PDF-A", prefixHex = "25", repeatHex = "41",
            repeatCount = 261502, suffixHex = "0a"),
        FileInput("PDF-SUPPLEMENT-OVER", PurchasingEvidenceMediaV1.PDF, 262145,
            PayloadDigest("541b2238c593272ccfcef3ff2fbc40a8b4ca46c47b28385cd1fe9b8814ca5c69"), listOf(
                ChunkRun(0, 1, 8192,
                    PayloadDigest("6b266d19a750c70aa21a4615af76e12c961e9cb0eeafe0c247490f3e6f2b79db")),
                ChunkRun(1, 31, 8192,
                    PayloadDigest("f8ca02c69621dd84cd1212ebfd7d6cdc9ba6ad658854f29567723531912d1a35")),
                ChunkRun(32, 1, 1,
                    PayloadDigest("01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b")),
            ),
            baseFile = "PDF-A", prefixHex = "25", repeatHex = "41",
            repeatCount = 261503, suffixHex = "0a"),
        FileInput("EMPTY", PurchasingEvidenceMediaV1.PDF, 0,
            PayloadDigest("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), emptyList(),
            base64 = ""),
        FileInput("ONE-INVALID-PDF", PurchasingEvidenceMediaV1.PDF, 1,
            PayloadDigest("559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd"), listOf(
                ChunkRun(0, 1, 1,
                    PayloadDigest("559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd")),
            ),
            base64 = "QQ=="),
    ).associateBy { it.alias }

    internal val documents: Map<String, DocumentInput> = listOf(
        DocumentInput("PRIMARY-A", 101, DomainId("00000000-0000-4000-8000-000000000101"), "PDF-MULTI",
            OperationId("10000000-0000-4000-8000-000000101001"),
            listOf(OperationId("10000000-0000-4000-8000-000000101100"), OperationId("10000000-0000-4000-8000-000000101101"), OperationId("10000000-0000-4000-8000-000000101102")),
            OperationId("10000000-0000-4000-8000-000000101900")),
        DocumentInput("PRIMARY-B", 102, DomainId("00000000-0000-4000-8000-000000000102"), "PDF-B",
            OperationId("10000000-0000-4000-8000-000000102001"),
            listOf(OperationId("10000000-0000-4000-8000-000000102100")),
            OperationId("10000000-0000-4000-8000-000000102900")),
        DocumentInput("BAD-FULL-HASH", 103, DomainId("00000000-0000-4000-8000-000000000103"), "PDF-MULTI",
            OperationId("10000000-0000-4000-8000-000000103001"),
            listOf(OperationId("10000000-0000-4000-8000-000000103100"), OperationId("10000000-0000-4000-8000-000000103101"), OperationId("10000000-0000-4000-8000-000000103102")),
            OperationId("10000000-0000-4000-8000-000000103900")),
        DocumentInput("CORRECTION-B", 104, DomainId("00000000-0000-4000-8000-000000000104"), "PDF-B",
            OperationId("10000000-0000-4000-8000-000000104001"),
            listOf(OperationId("10000000-0000-4000-8000-000000104100")),
            OperationId("10000000-0000-4000-8000-000000104900"), correctionAlias = "PRIMARY-A",
            correctionReason = "Supplier sent a revised documentary claim."),
        DocumentInput("PARENT", 105, DomainId("00000000-0000-4000-8000-000000000105"), "PDF-A",
            OperationId("10000000-0000-4000-8000-000000105001"),
            listOf(OperationId("10000000-0000-4000-8000-000000105100")),
            OperationId("10000000-0000-4000-8000-000000105900")),
        DocumentInput("CHILD-1", 106, DomainId("00000000-0000-4000-8000-000000000106"), "PDF-SUPPLEMENT-MAX",
            OperationId("10000000-0000-4000-8000-000000106001"),
            List(32) { index -> operationId(106, 100 + index) },
            OperationId("10000000-0000-4000-8000-000000106900"), parentAlias = "PARENT"),
        DocumentInput("CHILD-2", 107, DomainId("00000000-0000-4000-8000-000000000107"), "PDF-SUPPLEMENT-MAX",
            OperationId("10000000-0000-4000-8000-000000107001"),
            List(32) { index -> operationId(107, 100 + index) },
            OperationId("10000000-0000-4000-8000-000000107900"), parentAlias = "PARENT"),
        DocumentInput("CHILD-3", 108, DomainId("00000000-0000-4000-8000-000000000108"), "PDF-SUPPLEMENT-MAX",
            OperationId("10000000-0000-4000-8000-000000108001"),
            List(32) { index -> operationId(108, 100 + index) },
            OperationId("10000000-0000-4000-8000-000000108900"), parentAlias = "PARENT"),
        DocumentInput("RACE-X", 109, DomainId("00000000-0000-4000-8000-000000000109"), "PDF-SUPPLEMENT-MAX",
            OperationId("10000000-0000-4000-8000-000000109001"),
            List(32) { index -> operationId(109, 100 + index) },
            OperationId("10000000-0000-4000-8000-000000109900"), parentAlias = "PARENT"),
        DocumentInput("RACE-Y", 110, DomainId("00000000-0000-4000-8000-000000000110"), "PDF-SUPPLEMENT-MAX",
            OperationId("10000000-0000-4000-8000-000000110001"),
            List(32) { index -> operationId(110, 100 + index) },
            OperationId("10000000-0000-4000-8000-000000110900"), parentAlias = "PARENT"),
        DocumentInput("SMALL-CHILD", 111, DomainId("00000000-0000-4000-8000-000000000111"), "PNG-A",
            OperationId("10000000-0000-4000-8000-000000111001"),
            listOf(OperationId("10000000-0000-4000-8000-000000111100")),
            OperationId("10000000-0000-4000-8000-000000111900"), parentAlias = "PRIMARY-A"),
    ).associateBy { it.alias }

    internal val supplierReference192: String = "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR"
    internal val supplierReference193: String = "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR"
    internal val fileName192: String = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff.pdf"
    internal val fileName193: String = "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff.pdf"
    internal val correctionReason256: String = "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR"
    internal val correctionReason257: String = "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR"

    internal val malformedHex: List<String> = listOf("0", "gg", "AF")
    internal val malformedDigests: List<String> = listOf("000000000000000000000000000000000000000000000000000000000000000", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg")
    internal val controlReference: String = "0007-A\n"
    internal val controlFileName: String = "invoice\u0000.pdf"
    internal val controlReason: String = "Changed\rreason"

    internal fun file(alias: String): FileInput = files.getValue(alias)
    internal fun document(alias: String): DocumentInput = documents.getValue(alias)

    internal fun bytes(alias: String): ByteArray {
        val file = file(alias)
        file.base64?.let { return Base64.decode(it) }
        val base = bytes(checkNotNull(file.baseFile))
        val prefix = literalHex(file.prefixHex)
        val repeated = literalHex(file.repeatHex)
        val suffix = literalHex(file.suffixHex)
        return base + prefix + ByteArray(file.repeatCount * repeated.size) { repeated[it % repeated.size] } + suffix
    }

    internal fun slot(alias: String, index: Int): ByteArray {
        val bytes = bytes(alias)
        val start = index * 8192
        return bytes.copyOfRange(start, minOf(bytes.size, start + 8192))
    }

    /** Literal frozen ID rule only; this does not allocate a production operation. */
    internal fun documentId(ordinal: Int): DomainId = DomainId(
        "00000000-0000-4000-8000-${ordinal.toString().padStart(12, '0')}")
    internal fun operationId(ordinal: Int, step: Int): OperationId = OperationId(
        "10000000-0000-4000-8000-${(1000L * ordinal + step).toString().padStart(12, '0')}")

    internal fun begin(alias: String): LocalBeginPurchasingDocumentV1 {
        val input = document(alias)
        return beginFor(input.ordinal, input.fileAlias,
            parentOriginalId = input.parentAlias?.let { document(it).id },
            correctionOf = input.correctionAlias?.let { document(it).id },
            correctionReason = input.correctionReason)
    }

    internal fun beginFor(ordinal: Int, fileAlias: String,
        parentOriginalId: DomainId? = null, correctionOf: DomainId? = null, correctionReason: String? = null,
        supplierReferenceOriginal: String = reference, documentDate: BusinessDate? = date,
        purpose: PurchasingEvidencePurposeV1 = PurchasingEvidencePurposeV1.SUPPLIER_INVOICE): LocalBeginPurchasingDocumentV1 {
        val file = file(fileAlias)
        val name = when (file.media) {
            PurchasingEvidenceMediaV1.PDF -> defaultFileName
            PurchasingEvidenceMediaV1.PNG -> defaultFileName.removeSuffix(".pdf") + ".png"
            PurchasingEvidenceMediaV1.JPEG -> defaultFileName.removeSuffix(".pdf") + ".jpeg"
        }
        return LocalBeginPurchasingDocumentV1(documentId(ordinal), supplierIdCarrier, supplierRevisionCarrier,
            supplierReferenceOriginal, documentDate, purpose, file.media, name, file.byteLength, file.digest,
            file.chunkDigests, parentOriginalId, correctionOf, correctionReason)
    }

    /** Independent literal expansion for byte/hex fixtures, not a receiver acceptance service. */
    internal fun literalHex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    internal fun exactHex(bytes: ByteArray): String = bytes.joinToString("") {
        (it.toInt() and 255).toString(16).padStart(2, '0')
    }
}
