package com.ukkera.pos.catalog.store.independent.purchasingdocumentaryv2

import com.ukkera.pos.catalog.store.LocalBeginPurchasingDocumentV1
import com.ukkera.pos.catalog.store.LocalCompletePurchasingDocumentV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentChunkQueryV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentChunkReadResultV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentCodecV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentProfileV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentQueryV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentReadResultV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentResultV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentStateV1
import com.ukkera.pos.catalog.store.LocalWritePurchasingDocumentChunkV1
import com.ukkera.pos.catalog.store.purchasingDocumentDecode
import com.ukkera.pos.catalog.store.purchasingDocumentEncode
import com.ukkera.pos.catalog.store.purchasingDocumentMediaMatches
import com.ukkera.pos.catalog.store.purchasingDocumentReferencePrefix
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import com.ukkera.pos.security.PurchasingEvidencePurposeV1
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen v2 U fragments only: codecs, literal bytes, signatures and finite framing.
 * No native producer/result/history, supplier provisioning, grants, business ledger,
 * capture/Complete success, protected output or operation recovery is supplied.
 * Every whole DI2 case and all N/native profiles remain UNBOUND / NOT_RUN.
 */
class PurchasingDocumentaryV2FrozenUnitTest {
    private val fixture = PurchasingDocumentaryV2FrozenFixture

    @Test fun di2Profile_publicKeysAndFiniteCeilingsMatchTheFrozenContract() {
        val profile = LocalPurchasingDocumentProfileV1
        assertEquals("purchasing-document-capture/v1", profile.PROFILE)
        assertEquals("purchasing-document-exact-bytes/v1", profile.HASH_FORMAT)
        assertEquals("purchasing-document-exact-reference/v1", profile.REFERENCE_GROUP_FORMAT)
        assertEquals(CommandKey("purchasing.document.begin", 1), profile.BEGIN)
        assertEquals(CommandKey("purchasing.document.chunk", 1), profile.CHUNK)
        assertEquals(CommandKey("purchasing.document.complete", 1), profile.COMPLETE)
        assertEquals(ReadKey("purchasing.document.read", 1), profile.READ)
        assertEquals(ReadKey("purchasing.document.bytes", 1), profile.BYTES)
        assertEquals(524_288, profile.MAX_ORIGINAL_BYTES)
        assertEquals(262_144, profile.MAX_ATTACHMENT_BYTES)
        assertEquals(4, profile.MAX_ATTACHMENT_COUNT)
        assertEquals(1_048_576, profile.MAX_TOTAL_ATTACHMENT_BYTES)
        assertEquals(8192, profile.CHUNK_BYTES)
        assertEquals(32_768, profile.MAX_INPUT_BYTES)
        assertEquals(32_768, profile.MAX_OUTPUT_BYTES)
        assertEquals(5, profile.PAGE_SIZE)
        assertEquals(5, profile.MAX_DUPLICATE_CANDIDATES)
        assertEquals(786_432, 3 * profile.MAX_ATTACHMENT_BYTES)
        assertEquals(1_048_576, 4 * profile.MAX_ATTACHMENT_BYTES)
        assertEquals(1_310_720, 5 * profile.MAX_ATTACHMENT_BYTES)
        assertEquals(listOf("INCOMPLETE", "CAPTURED_AT_BRANCH"), LocalPurchasingDocumentStateV1.entries.map { it.name })
        // Constants/arithmetic do not observe completed child capacity or page membership.
    }

    @Test fun di2001_exactRecipesHashesAndSavedStepBodiesKeepIndependentIdentities() {
        for ((alias, file) in fixture.files) {
            val bytes = fixture.bytes(alias)
            assertEquals(file.byteLength, bytes.size, alias)
            assertEquals(file.digest, sha256(bytes), alias)
            val expanded = file.chunkRuns.flatMap { run ->
                List(run.count) { offset -> Triple(run.firstIndex + offset, run.byteLength, run.digest) }
            }
            for ((index, length, digest) in expanded) {
                val slot = fixture.slot(alias, index)
                assertEquals(length, slot.size, "$alias/$index")
                assertEquals(digest, sha256(slot), "$alias/$index")
            }
            if (bytes.size in 1..524_288) {
                val prepared = LocalPurchasingDocumentCodecV1.content(bytes)
                assertEquals(file.byteLength, prepared.byteLength, alias)
                assertEquals(file.digest, prepared.exactBytesDigest, alias)
                assertEquals(file.chunkDigests, prepared.chunkDigests, alias)
            } else {
                assertFailsWith<IllegalArgumentException>(alias) { LocalPurchasingDocumentCodecV1.content(bytes) }
            }
        }
        val main = fixture.document("PRIMARY-A")
        val request = fixture.begin("PRIMARY-A")
        val begin = LocalPurchasingDocumentCodecV1.begin(main.beginOperation, request)
        assertEquals(LocalPurchasingDocumentProfileV1.BEGIN, begin.command)
        assertEquals(main.beginOperation, begin.operationId)
        assertEquals(request, decodeBegin(begin.payload))
        assertEquals("0007-A", request.supplierReferenceOriginal)
        assertEquals(BusinessDate.parseCanonical("2026-10-21"), request.documentDate)
        assertEquals("supplier-0007-A.pdf", request.originalFileName)
        assertEquals(listOf(8192, 8192, 23), fixture.file("PDF-MULTI").chunkLengths)
        val operations = mutableListOf(begin.operationId)
        for (index in 0..2) {
            val input = LocalWritePurchasingDocumentChunkV1(main.id, main.beginOperation, index,
                fixture.exactHex(fixture.slot("PDF-MULTI", index)))
            val write = LocalPurchasingDocumentCodecV1.writeChunk(main.chunkOperations[index], input)
            assertEquals(LocalPurchasingDocumentProfileV1.CHUNK, write.command)
            assertEquals(input, purchasingDocumentDecode(write.payload, LocalWritePurchasingDocumentChunkV1.serializer()))
            operations += write.operationId
        }
        val completion = LocalCompletePurchasingDocumentV1(main.id, main.beginOperation)
        val complete = LocalPurchasingDocumentCodecV1.complete(main.completeOperation, completion)
        assertEquals(LocalPurchasingDocumentProfileV1.COMPLETE, complete.command)
        assertEquals(completion, purchasingDocumentDecode(complete.payload, LocalCompletePurchasingDocumentV1.serializer()))
        operations += complete.operationId
        assertEquals(listOf(
            "10000000-0000-4000-8000-000000101001",
            "10000000-0000-4000-8000-000000101100",
            "10000000-0000-4000-8000-000000101101",
            "10000000-0000-4000-8000-000000101102",
            "10000000-0000-4000-8000-000000101900",
        ), operations.map { it.value })
        assertEquals(5, operations.distinct().size)
        assertNull(completion.expectedParentAttachmentsRevision)
        for (document in fixture.documents.values) {
            assertEquals(document.id, fixture.documentId(document.ordinal), document.alias)
            assertEquals(document.beginOperation, fixture.operationId(document.ordinal, 1), document.alias)
            assertEquals(document.completeOperation, fixture.operationId(document.ordinal, 900), document.alias)
            assertEquals(fixture.file(document.fileAlias).chunkDigests.size, document.chunkOperations.size, document.alias)
            for ((index, operation) in document.chunkOperations.withIndex()) {
                assertEquals(fixture.operationId(document.ordinal, 100 + index), operation, document.alias)
            }
        }
        // These saved input values do not prove accepted slots, completion or durability.
    }

    @Test fun di2002_smallMediaSignaturesAndSevenPurposeInputsAreRetained() {
        assertEquals(listOf("JPEG", "PNG", "PDF"), PurchasingEvidenceMediaV1.entries.map { it.name })
        val small = listOf(Triple("PDF-A", 160, 640), Triple("PNG-A", 161, 69), Triple("JPEG-A", 162, 155))
        for ((alias, ordinal, length) in small) {
            val file = fixture.file(alias)
            val bytes = fixture.bytes(alias)
            assertEquals(length, bytes.size, alias)
            assertTrue(purchasingDocumentMediaMatches(file.media, bytes), alias)
            val request = fixture.beginFor(ordinal, alias)
            val encoded = LocalPurchasingDocumentCodecV1.begin(fixture.operationId(ordinal, 1), request)
            assertEquals(request, decodeBegin(encoded.payload), alias)
            assertEquals(file.media, decodeBegin(encoded.payload).media, alias)
        }
        val purposes = listOf(
            PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY,
            PurchasingEvidencePurposeV1.OWNERSHIP_ATTESTATION,
            PurchasingEvidencePurposeV1.INSPECTION,
            PurchasingEvidencePurposeV1.PURCHASE_AGREEMENT,
            PurchasingEvidencePurposeV1.SUPPLIER_INVOICE,
            PurchasingEvidencePurposeV1.SUPPLIER_DISPATCH,
            PurchasingEvidencePurposeV1.CLAIM_SUPPORT,
        )
        assertEquals(purposes, PurchasingEvidencePurposeV1.entries)
        for ((index, purpose) in purposes.withIndex()) {
            val ordinal = 170 + index
            val request = fixture.beginFor(ordinal, "PNG-A", purpose = purpose)
            assertEquals(purpose, decodeBegin(LocalPurchasingDocumentCodecV1.begin(
                fixture.operationId(ordinal, 1), request).payload).purpose)
        }
        // Signature classification is not renderer support, authenticity or captured evidence.
    }

    @Test fun di2004_exactPrimaryAndChildByteBoundariesRemainSeparate() {
        val primary = fixture.beginFor(101, "PDF-PRIMARY-MAX")
        assertEquals(524_288, primary.byteLength)
        assertEquals(64, primary.chunkDigests.size)
        assertTrue(purchasingDocumentMediaMatches(primary.media, fixture.bytes("PDF-PRIMARY-MAX")))
        assertEquals(primary, decodeBegin(LocalPurchasingDocumentCodecV1.begin(
            fixture.document("PRIMARY-A").beginOperation, primary).payload))
        assertFailsWith<IllegalArgumentException> { fixture.beginFor(101, "PDF-PRIMARY-OVER") }
        assertFailsWith<IllegalArgumentException> { fixture.beginFor(101, "EMPTY") }
        val parent = fixture.document("PARENT").id
        val child = fixture.beginFor(106, "PDF-SUPPLEMENT-MAX", parentOriginalId = parent)
        assertEquals(262_144, child.byteLength)
        assertEquals(32, child.chunkDigests.size)
        assertEquals(child, decodeBegin(LocalPurchasingDocumentCodecV1.begin(
            fixture.document("CHILD-1").beginOperation, child).payload))
        assertFailsWith<IllegalArgumentException> {
            fixture.beginFor(106, "PDF-SUPPLEMENT-OVER", parentOriginalId = parent)
        }
        for ((alias, lengths) in listOf("PDF-SLOT-8192" to listOf(8192), "PDF-SLOT-8193" to listOf(8192, 1))) {
            val prepared = LocalPurchasingDocumentCodecV1.content(fixture.bytes(alias))
            assertEquals(lengths, fixture.file(alias).chunkLengths)
            assertEquals(lengths.size, prepared.chunkDigests.size)
            assertEquals(fixture.file(alias).chunkDigests, prepared.chunkDigests)
        }
        val one = fixture.beginFor(101, "ONE-INVALID-PDF")
        assertEquals(1, one.byteLength)
        assertEquals(1, one.chunkDigests.size)
        assertFalse(purchasingDocumentMediaMatches(one.media, fixture.bytes("ONE-INVALID-PDF")))
        // A permitted one-byte Begin shape is never a completed/recognized PDF claim.
    }

    @Test fun di2005_exactShortAndCorruptSlotRecipesKeepTheirPublishedHashes() {
        val exact = fixture.slot("PDF-MULTI", 0)
        val short = exact.copyOfRange(0, 8191)
        val corrupt = exact.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertEquals(8191, short.size)
        assertEquals(8192, corrupt.size)
        assertEquals(fixture.shortSlotDigest, sha256(short))
        assertEquals(fixture.corruptSlotDigest, sha256(corrupt))
        assertNotEquals(fixture.file("PDF-MULTI").chunkDigests[0], sha256(short))
        assertNotEquals(fixture.file("PDF-MULTI").chunkDigests[0], sha256(corrupt))
        for (bytes in listOf(short, corrupt)) {
            val hex = LocalPurchasingDocumentCodecV1.hex(bytes)
            assertEquals(fixture.exactHex(bytes), hex)
            assertContentEquals(bytes, LocalPurchasingDocumentCodecV1.decodeChunkHex(hex))
        }
        assertNotEquals(fixture.operationId(101, 100), fixture.operationId(101, 200))
        assertNotEquals(fixture.operationId(101, 900), fixture.operationId(101, 901))
        // Length/hash facts do not execute chunk_length_mismatch, integrity or replay gates.
    }

    @Test fun di2006_falseFullDigestAndWrongMediaStayDistinctFromExactContent() {
        val bytes = fixture.bytes("PDF-MULTI")
        val prepared = LocalPurchasingDocumentCodecV1.content(bytes)
        val falseHash = fixture.begin("BAD-FULL-HASH").copy(exactBytesDigest = fixture.falseFullDigest)
        assertEquals(fixture.file("PDF-MULTI").chunkDigests, falseHash.chunkDigests)
        assertEquals(fixture.falseFullDigest, falseHash.exactBytesDigest)
        assertNotEquals(prepared.exactBytesDigest, falseHash.exactBytesDigest)
        assertEquals(falseHash, decodeBegin(LocalPurchasingDocumentCodecV1.begin(
            fixture.document("BAD-FULL-HASH").beginOperation, falseHash).payload))
        val png = fixture.bytes("PNG-A")
        val wrongMedia = fixture.beginFor(101, "PNG-A").copy(media = PurchasingEvidenceMediaV1.PDF)
        assertEquals(fixture.file("PNG-A").digest, wrongMedia.exactBytesDigest)
        assertEquals(fixture.file("PNG-A").chunkDigests, wrongMedia.chunkDigests)
        assertFalse(purchasingDocumentMediaMatches(wrongMedia.media, png))
        assertTrue(purchasingDocumentMediaMatches(PurchasingEvidenceMediaV1.PNG, png))
        // No fake Complete/result/history is constructed to assert documentary refusal codes.
    }

    @Test fun di2008_encodedOriginalRequestIdentityCannotBeRewrittenByCallerCopies() {
        val original = fixture.begin("PRIMARY-A")
        val mutableDigests = original.chunkDigests.toMutableList()
        val supplied = original.copy(chunkDigests = mutableDigests)
        val operation = fixture.document("PRIMARY-A").beginOperation
        val saved = LocalPurchasingDocumentCodecV1.begin(operation, supplied)
        val before = saved.payload.copyBytes()
        mutableDigests.reverse()
        mutableDigests.clear()
        saved.payload.copyBytes().fill(0)
        assertContentEquals(before, saved.payload.copyBytes())
        assertEquals(operation, saved.operationId)
        assertEquals(original, decodeBegin(saved.payload))
        val identical = LocalPurchasingDocumentCodecV1.begin(operation, original)
        assertContentEquals(before, identical.payload.copyBytes())
        val changed = LocalPurchasingDocumentCodecV1.begin(operation, original.copy(supplierReferenceOriginal = " 0007-A"))
        assertEquals(operation, changed.operationId)
        assertFalse(before.contentEquals(changed.payload.copyBytes()))
        assertContentEquals(before, saved.payload.copyBytes())
        // This preserves an encoded input snapshot; it does not enforce operation/body conflict.
    }

    @Test fun di2010_childAndCorrectionRequestsKeepExactLinksAndIdentityFields() {
        val child = fixture.begin("SMALL-CHILD").copy(purpose = PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY)
        assertEquals(fixture.document("PRIMARY-A").id, child.parentOriginalId)
        assertNull(child.correctionOf)
        assertNull(child.correctionReason)
        val correction = fixture.begin("CORRECTION-B")
        assertEquals(fixture.document("PRIMARY-A").id, correction.correctionOf)
        assertEquals("Supplier sent a revised documentary claim.", correction.correctionReason)
        assertNull(correction.parentOriginalId)
        for ((alias, request) in listOf("SMALL-CHILD" to child, "CORRECTION-B" to correction)) {
            val input = fixture.document(alias)
            assertEquals(request, decodeBegin(LocalPurchasingDocumentCodecV1.begin(input.beginOperation, request).payload))
            assertNotEquals(input.id, fixture.document("PRIMARY-A").id)
            assertNotEquals(input.beginOperation, input.completeOperation)
        }
        assertFailsWith<IllegalArgumentException> { correction.copy(parentOriginalId = fixture.document("PARENT").id) }
        assertFailsWith<IllegalArgumentException> { correction.copy(correctionReason = null) }
        assertFailsWith<IllegalArgumentException> { child.copy(correctionReason = correction.correctionReason) }
        val completion = LocalCompletePurchasingDocumentV1(fixture.document("SMALL-CHILD").id,
            fixture.document("SMALL-CHILD").beginOperation, Revision(1))
        assertEquals(completion, purchasingDocumentDecode(LocalPurchasingDocumentCodecV1.complete(
            fixture.document("SMALL-CHILD").completeOperation, completion).payload, LocalCompletePurchasingDocumentV1.serializer()))
        // Revision1 here is an inert optional-field carrier, not an observed parent head or CAS.
    }

    @Test fun di2016_referenceGroupKeysRetainExactTextDateAndScope() {
        val base = fixture.beginFor(146, "PDF-A")
        val key = purchasingDocumentReferencePrefix(fixture.scopeCarrier, base)
        for (ordinal in 140..145) {
            assertEquals(key, purchasingDocumentReferencePrefix(fixture.scopeCarrier, fixture.beginFor(ordinal, "PDF-A")))
        }
        val variants = listOf(
            fixture.beginFor(147, "PDF-A", supplierReferenceOriginal = " 0007-A"),
            fixture.beginFor(148, "PDF-A", supplierReferenceOriginal = "0007-a"),
            fixture.beginFor(149, "PDF-A", documentDate = BusinessDate.parseCanonical("2026-10-22")),
            fixture.beginFor(150, "PDF-A", documentDate = null),
        )
        val variantKeys = variants.map { purchasingDocumentReferencePrefix(fixture.scopeCarrier, it) }
        assertEquals(4, variantKeys.distinct().size)
        assertTrue(variantKeys.none { it == key })
        assertEquals(variantKeys[3], purchasingDocumentReferencePrefix(fixture.scopeCarrier,
            fixture.beginFor(151, "PDF-A", documentDate = null)))
        assertNotEquals(key, purchasingDocumentReferencePrefix(fixture.scopeCarrier.copy(
            branchId = DomainId("d0000000-0000-4000-8000-000000000005")), base))
        assertNotEquals(key, purchasingDocumentReferencePrefix(fixture.scopeCarrier,
            base.copy(supplierId = DomainId("d0000000-0000-4000-8000-000000000006"))))
        // No completed candidate list, sorting/exclusion, additional flag or disclosure is faked.
    }

    @Test fun di2026_exactHexAndSuppliedChunkWireRoundTripRetainAllThreeSlots() {
        val decodedSlots = mutableListOf<ByteArray>()
        for (index in 0..2) {
            val bytes = fixture.slot("PDF-MULTI", index)
            val supplied = suppliedChunk(index)
            assertEquals(fixture.exactHex(bytes), supplied.chunkHex)
            assertEquals(supplied.chunkHex, LocalPurchasingDocumentCodecV1.hex(bytes))
            val encoded = purchasingDocumentEncode(supplied, LocalPurchasingDocumentChunkReadResultV1.serializer(), 32_768)
            assertTrue(encoded.size <= 32_768)
            val decoded = LocalPurchasingDocumentCodecV1.chunkResult(encoded)
            assertEquals(supplied, decoded)
            assertEquals(listOf(8192, 8192, 23)[index], decoded.chunkByteLength)
            if (index < 2) assertEquals(16_384, decoded.chunkHex.length)
            val originalSlot = LocalPurchasingDocumentCodecV1.decodeChunkHex(decoded.chunkHex)
            assertContentEquals(bytes, originalSlot)
            assertEquals(decoded.chunkDigest, sha256(originalSlot))
            decodedSlots += originalSlot
        }
        val reconstruction = decodedSlots.fold(ByteArray(0)) { bytes, slot -> bytes + slot }
        assertEquals(16_407, reconstruction.size)
        assertContentEquals(fixture.bytes("PDF-MULTI"), reconstruction)
        assertEquals(fixture.file("PDF-MULTI").digest, LocalPurchasingDocumentCodecV1.content(reconstruction).exactBytesDigest)
        for (export in listOf(false, true)) {
            val request = LocalPurchasingDocumentChunkQueryV1(fixture.document("PRIMARY-A").id, 0, export)
            val query = LocalPurchasingDocumentCodecV1.chunkQuery(request)
            assertEquals(LocalPurchasingDocumentProfileV1.BYTES, query.query)
            assertEquals(request, purchasingDocumentDecode(query.payload, LocalPurchasingDocumentChunkQueryV1.serializer()))
        }
        // Supplied wire values are not protected read/export or verified-download boundary results.
    }

    @Test fun di2027_asciiTextBoundariesAndControlInputsFailClosed() {
        val primary = fixture.begin("PRIMARY-A")
        val correction = fixture.begin("CORRECTION-B")
        assertEquals(192, fixture.supplierReference192.length)
        assertEquals(192, fixture.fileName192.length)
        assertEquals(256, fixture.correctionReason256.length)
        for ((request, operation) in listOf(
            primary.copy(supplierReferenceOriginal = fixture.supplierReference192) to fixture.document("PRIMARY-A").beginOperation,
            primary.copy(originalFileName = fixture.fileName192) to fixture.document("PRIMARY-A").beginOperation,
            correction.copy(correctionReason = fixture.correctionReason256) to fixture.document("CORRECTION-B").beginOperation)) {
            assertEquals(request, decodeBegin(LocalPurchasingDocumentCodecV1.begin(
                operation, request).payload))
        }
        assertEquals(193, fixture.supplierReference193.length)
        assertEquals(193, fixture.fileName193.length)
        assertEquals(257, fixture.correctionReason257.length)
        assertFailsWith<IllegalArgumentException> { primary.copy(supplierReferenceOriginal = fixture.supplierReference193) }
        assertFailsWith<IllegalArgumentException> { primary.copy(originalFileName = fixture.fileName193) }
        assertFailsWith<IllegalArgumentException> { correction.copy(correctionReason = fixture.correctionReason257) }
        assertFailsWith<IllegalArgumentException> { primary.copy(supplierReferenceOriginal = fixture.controlReference) }
        assertFailsWith<IllegalArgumentException> { primary.copy(originalFileName = fixture.controlFileName) }
        assertFailsWith<IllegalArgumentException> { correction.copy(correctionReason = fixture.controlReason) }
        // ASCII constructor/codec fragments do not prove normal completion or non-BMP counting.
    }

    @Test fun di2027_malformedHexDigestEnumAndIdInputsFailClosed() {
        val main = fixture.document("PRIMARY-A")
        for (hex in fixture.malformedHex) {
            assertFailsWith<IllegalArgumentException>(hex) { LocalPurchasingDocumentCodecV1.decodeChunkHex(hex) }
            assertFailsWith<IllegalArgumentException>(hex) { LocalWritePurchasingDocumentChunkV1(main.id, main.beginOperation, 0, hex) }
        }
        for (digest in fixture.malformedDigests) {
            assertFailsWith<IllegalArgumentException>(digest) { PayloadDigest(digest) }
            assertFails(digest) { decodeBegin(changedBegin { put("exactBytesDigest", JsonPrimitive(digest)) }) }
        }
        for ((field, value) in listOf("media" to "UNKNOWN_MEDIA", "purpose" to "UNKNOWN_PURPOSE",
            "documentId" to "not-a-canonical-uuid")) {
            assertFails(field) { decodeBegin(changedBegin { put(field, JsonPrimitive(value)) }) }
        }
        // These unspecified malformed lexical sentinels are not native input/output identities.
    }

    @Test fun di2027_wrongMissingAndExtraSlotManifestsCannotDecode() {
        val original = fixture.begin("PRIMARY-A")
        assertEquals(3, original.chunkDigests.size)
        for (digests in listOf(emptyList(), original.chunkDigests.take(2), original.chunkDigests + original.chunkDigests[0])) {
            assertFailsWith<IllegalArgumentException> { original.copy(chunkDigests = digests) }
        }
        assertFails { decodeBegin(changedBegin { remove("chunkDigests") }) }
        assertFails { decodeBegin(changedBegin {
            val digests = getValue("chunkDigests").jsonArray
            put("chunkDigests", JsonArray(digests.take(2)))
        }) }
        assertFails { decodeBegin(changedBegin {
            val digests = getValue("chunkDigests").jsonArray
            put("chunkDigests", JsonArray(digests + digests[0]))
        }) }
    }

    @Test fun di2027_mutuallyCombinedReadSelectorsCannotDecode() {
        val documentId = fixture.document("PRIMARY-A").id
        val after = fixture.document("PRIMARY-B").id
        for (request in listOf(LocalPurchasingDocumentQueryV1(), LocalPurchasingDocumentQueryV1(documentId = documentId),
            LocalPurchasingDocumentQueryV1(after = after), LocalPurchasingDocumentQueryV1(pendingDocumentId = documentId))) {
            val query = LocalPurchasingDocumentCodecV1.query(request)
            assertEquals(LocalPurchasingDocumentProfileV1.READ, query.query)
            assertEquals(request, purchasingDocumentDecode(query.payload, LocalPurchasingDocumentQueryV1.serializer()))
        }
        for (fields in listOf(mapOf("documentId" to documentId.value, "after" to after.value),
            mapOf("pendingDocumentId" to documentId.value, "documentId" to documentId.value),
            mapOf("pendingDocumentId" to documentId.value, "after" to after.value))) {
            val root = Json.parseToJsonElement(LocalPurchasingDocumentCodecV1.query(
                LocalPurchasingDocumentQueryV1()).payload.copyBytes().decodeToString()).jsonObject
            val changed = JsonObject(root.toMutableMap().apply { fields.forEach { (key, value) -> put(key, JsonPrimitive(value)) } })
            assertFails { purchasingDocumentDecode(StoredBytes(changed.toString().encodeToByteArray()), LocalPurchasingDocumentQueryV1.serializer()) }
        }
        // Paging size/union and initiating-employee pending access remain integration gaps.
    }

    @Test fun di2027_oversizedMutationAndProtectedOutputFramesFailClosed() {
        val original = fixture.begin("PRIMARY-A")
        val input = LocalPurchasingDocumentCodecV1.begin(fixture.document("PRIMARY-A").beginOperation, original).payload.copyBytes()
        val atInputBound = paddedFrame(input, 32_768)
        val overInputBound = paddedFrame(input, 32_769)
        assertEquals(32_768, atInputBound.size)
        assertEquals(original, decodeBegin(StoredBytes(atInputBound)))
        assertEquals(32_769, overInputBound.size)
        assertFailsWith<IllegalArgumentException> { decodeBegin(StoredBytes(overInputBound)) }
        val supplied = suppliedChunk(0)
        val output = purchasingDocumentEncode(supplied, LocalPurchasingDocumentChunkReadResultV1.serializer(), 32_768).copyBytes()
        val atOutputBound = paddedFrame(output, 32_768)
        val overOutputBound = paddedFrame(output, 32_769)
        assertEquals(32_768, atOutputBound.size)
        assertEquals(32_769, overOutputBound.size)
        assertEquals(supplied, LocalPurchasingDocumentCodecV1.chunkResult(StoredBytes(atOutputBound)))
        assertFailsWith<IllegalArgumentException> { LocalPurchasingDocumentCodecV1.chunkResult(StoredBytes(overOutputBound)) }
        // Inert codec input only: the sentinel is not a native outcome or refusal code.
        val suppliedResult = LocalPurchasingDocumentResultV1("supplied-codec-framing-only", fixture.document("PRIMARY-A").id)
        val resultOutput = purchasingDocumentEncode(suppliedResult, LocalPurchasingDocumentResultV1.serializer(), 32_768).copyBytes()
        val atResultBound = paddedFrame(resultOutput, 32_768)
        val overResultBound = paddedFrame(resultOutput, 32_769)
        assertEquals(32_768, atResultBound.size)
        assertEquals(32_769, overResultBound.size)
        assertEquals(suppliedResult, LocalPurchasingDocumentCodecV1.result(StoredBytes(atResultBound)))
        assertFailsWith<IllegalArgumentException> { LocalPurchasingDocumentCodecV1.result(StoredBytes(overResultBound)) }
        // An empty supplied read DTO does not assert an authoritative empty page.
        val suppliedRead = LocalPurchasingDocumentReadResultV1()
        val readOutput = purchasingDocumentEncode(suppliedRead, LocalPurchasingDocumentReadResultV1.serializer(), 32_768).copyBytes()
        val atReadBound = paddedFrame(readOutput, 32_768)
        val overReadBound = paddedFrame(readOutput, 32_769)
        assertEquals(32_768, atReadBound.size)
        assertEquals(32_769, overReadBound.size)
        assertEquals(suppliedRead, LocalPurchasingDocumentCodecV1.readResult(StoredBytes(atReadBound)))
        assertFailsWith<IllegalArgumentException> { LocalPurchasingDocumentCodecV1.readResult(StoredBytes(overReadBound)) }
        // JSON whitespace probes exercise framing only, not an accepted command/output history.
    }

    @Test fun di2018_callerForgedTrustedFieldsCannotEnterTheRequestSchema() {
        for (field in listOf("scope", "actorId", "semanticDigest")) {
            assertFails(field) { decodeBegin(changedBegin { put(field, JsonPrimitive("caller-supplied")) }) }
        }
        // Strict unknown-field refusal is not scope/session/device/grant enforcement.
    }

    private fun decodeBegin(bytes: StoredBytes): LocalBeginPurchasingDocumentV1 =
        purchasingDocumentDecode(bytes, LocalBeginPurchasingDocumentV1.serializer())

    private fun changedBegin(change: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit): StoredBytes {
        val saved = LocalPurchasingDocumentCodecV1.begin(fixture.document("PRIMARY-A").beginOperation,
            fixture.begin("PRIMARY-A")).payload.copyBytes()
        val root = Json.parseToJsonElement(saved.decodeToString()).jsonObject
        val changed = JsonObject(root.toMutableMap().apply(change))
        return StoredBytes(changed.toString().encodeToByteArray())
    }

    /** Codec input only: no fabricated native producer/history/actor/grant is attached. */
    private fun suppliedChunk(index: Int): LocalPurchasingDocumentChunkReadResultV1 {
        val file = fixture.file("PDF-MULTI")
        val bytes = fixture.slot("PDF-MULTI", index)
        return LocalPurchasingDocumentChunkReadResultV1(fixture.document("PRIMARY-A").id,
            file.byteLength, file.digest, file.media, "purchasing-document-exact-bytes/v1", index,
            bytes.size, file.chunkDigests[index], fixture.exactHex(bytes))
    }

    private fun paddedFrame(bytes: ByteArray, size: Int): ByteArray {
        require(bytes.size <= size)
        return bytes + ByteArray(size - bytes.size) { 32.toByte() }
    }
}
