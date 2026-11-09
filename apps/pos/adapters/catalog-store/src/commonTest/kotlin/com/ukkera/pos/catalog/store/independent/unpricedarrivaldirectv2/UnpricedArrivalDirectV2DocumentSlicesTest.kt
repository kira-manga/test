package com.ukkera.pos.catalog.store.independent.unpricedarrivaldirectv2

import com.ukkera.pos.catalog.store.LocalPurchasingDocumentCodecV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentProfileV1
import com.ukkera.pos.catalog.store.LocalWritePurchasingDocumentChunkV1
import com.ukkera.pos.catalog.store.purchasingDocumentDecode
import com.ukkera.pos.catalog.store.purchasingDocumentEncode
import com.ukkera.pos.inventory.store.LocalArrivalDocumentSelectionV2
import com.ukkera.pos.inventory.store.LocalArrivalEvidenceSelectionV2
import com.ukkera.pos.security.PurchasingArrivalDocumentReference
import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import com.ukkera.pos.security.PurchasingEvidencePurposeV1
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exact frozen UAD2-03 public byte/manifest/request mappings only.
 * They are prerequisites/carriers, NOT genuine captured originals, current source
 * qualification, selected signed-rule validation or immutable action-use acceptance.
 * All UAD2 whole cases remain NOT_RUN; native completion/history and protected
 * parent/head membership, actual retention and atomic custody/use are UNBOUND.
 */
class UnpricedArrivalDirectV2DocumentSlicesTest {
    private val beginCarriers = mapOf(
        "DOC-A" to OperationId("90000000-0000-4000-8000-000000000051"),
        "DOC-B" to OperationId("90000000-0000-4000-8000-000000000052"),
        "DOC-S" to OperationId("90000000-0000-4000-8000-000000000053"),
    )
    private val chunkCarriers = mapOf(
        "DOC-A" to OperationId("90000000-0000-4000-8000-000000000054"),
        "DOC-B" to OperationId("90000000-0000-4000-8000-000000000055"),
        "DOC-S" to OperationId("90000000-0000-4000-8000-000000000056"),
    )

    @Test
    fun uad2_03_exact_pdf_recipes_match_frozen_byte_lengths_full_digests_and_chunk_digests() {
        for (file in Uad2DocumentInputs.files) {
            val bytes = file.bytes()
            try {
                val content = LocalPurchasingDocumentCodecV1.content(bytes)
                assertEquals(file.byteLength, content.byteLength, file.label)
                assertEquals(file.digest, content.exactBytesDigest, file.label)
                assertEquals(file.chunkDigests, content.chunkDigests, file.label)
                assertEquals(8192, LocalPurchasingDocumentProfileV1.CHUNK_BYTES)
                assertEquals(1, content.chunkDigests.size, file.label)
            } finally { bytes.fill(0) }
        }
        // Hash agreement proves this recipe mapping only, not native completion/authenticity.
    }

    @Test
    fun uad2_03_public_chunk_hex_round_trip_preserves_every_exact_original_byte() {
        for (file in Uad2DocumentInputs.files) {
            val bytes = file.bytes()
            var restored: ByteArray? = null
            try {
                val exact = LocalPurchasingDocumentCodecV1.decodeChunkHex(LocalPurchasingDocumentCodecV1.hex(bytes))
                restored = exact
                assertContentEquals(bytes, exact, file.label)
                assertEquals(file.digest, LocalPurchasingDocumentCodecV1.content(exact).exactBytesDigest)
            } finally { restored?.fill(0); bytes.fill(0) }
        }
    }

    @Test
    fun uad2_03_begin_and_chunk_codec_carriers_keep_exact_frozen_manifest_and_file_bytes() {
        for (file in Uad2DocumentInputs.files) {
            val begin = Uad2DocumentInputs.begin(file)
            val mutation = LocalPurchasingDocumentCodecV1.begin(beginCarriers.getValue(file.label), begin)
            val decoded = LocalPurchasingDocumentCodecV1.decodeBegin(LocalPurchasingDocumentCodecV1.normalize(mutation.command, mutation.payload))
            assertEquals(begin, decoded)
            assertEquals(file.digest, decoded.exactBytesDigest)
            assertEquals(file.byteLength, decoded.byteLength)
            assertEquals(file.fileName, decoded.originalFileName)
            assertEquals(file.parentId, decoded.parentOriginalId)
            assertEquals(Revision(3), decoded.expectedSupplierRevision)
            val bytes = file.bytes()
            var restored: ByteArray? = null
            try {
                val chunk = LocalWritePurchasingDocumentChunkV1(file.id, mutation.operationId, 0, LocalPurchasingDocumentCodecV1.hex(bytes))
                val write = LocalPurchasingDocumentCodecV1.writeChunk(chunkCarriers.getValue(file.label), chunk)
                val returned = LocalPurchasingDocumentCodecV1.decodeChunk(LocalPurchasingDocumentCodecV1.normalize(write.command, write.payload))
                assertEquals(chunk, returned)
                val exact = LocalPurchasingDocumentCodecV1.decodeChunkHex(returned.chunkHex)
                restored = exact
                assertContentEquals(bytes, exact)
            } finally { restored?.fill(0); bytes.fill(0) }
        }
        // No Begin/Chunk/Complete command was executed or installed in a native store.
    }

    @Test
    fun uad2_03_manifest_reference_mapping_keeps_scope_supplier_identity_revision_length_digest_media_and_purpose() {
        for (file in Uad2DocumentInputs.files) {
            val manifest = Uad2DocumentInputs.manifest(file)
            val independentlyExpected = PurchasingArrivalDocumentReference(Uad2DocumentInputs.scope, Uad2DocumentInputs.supplier,
                file.id, Revision(1), file.digest, file.byteLength, PurchasingEvidenceMediaV1.PDF, PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY)
            assertEquals(independentlyExpected, manifest.reference(), file.label)
        }
    }

    @Test
    fun uad2_03_exact_ordered_original_manifest_carriers_survive_encoding_without_sorting() {
        val expected = Uad2DocumentInputs.originals()
        val serializer = ListSerializer(LocalArrivalDocumentSelectionV2.serializer())
        val decoded = purchasingDocumentDecode(purchasingDocumentEncode(expected, serializer), serializer)
        assertEquals(listOf(Uad2DocumentInputs.file("DOC-A").id, Uad2DocumentInputs.file("DOC-B").id), decoded.map { it.documentId })
        assertEquals(expected, decoded)
        val reversed = purchasingDocumentDecode(purchasingDocumentEncode(expected.reversed(), serializer), serializer)
        assertEquals(expected.reversed(), reversed)
        assertNotEquals(decoded, reversed)
        // Only a genuine protected original loader/rule can refuse an unexpected resolved order.
    }

    @Test
    fun uad2_03_supplemental_manifest_carrier_retains_parent_and_exact_selected_head_revision() {
        val a = Uad2DocumentInputs.manifest(Uad2DocumentInputs.file("DOC-A"))
        val supplemental = Uad2DocumentInputs.manifest(Uad2DocumentInputs.file("DOC-S"))
        val serializer = ListSerializer(LocalArrivalDocumentSelectionV2.serializer())
        val decoded = purchasingDocumentDecode(purchasingDocumentEncode(listOf(a, supplemental), serializer), serializer)
        assertEquals(listOf(a, supplemental), decoded)
        assertNull(decoded.first().parentDocumentId); assertNull(decoded.first().parentHead)
        assertEquals(a.documentId, decoded.last().parentDocumentId)
        val head = assertNotNull(decoded.last().parentHead)
        assertEquals(a.documentId, head.id)
        assertEquals(Revision(2), head.storageRevision)
        assertEquals(Uad2DocumentInputs.parentHeadDigestCarrier, head.digest)
        assertNotEquals(a.exactBytesDigest, head.digest)
        // The carrier is not an actual head digest or evidence of completed parent membership.
    }

    @Test
    fun uad2_03_explicit_optional_empty_list_carrier_keeps_selected_rule_and_retention_without_inventing_a_file() {
        val expected = Uad2DocumentInputs.evidence(documents = emptyList(), optional = true)
        val decoded = purchasingDocumentDecode(purchasingDocumentEncode(expected, LocalArrivalEvidenceSelectionV2.serializer()),
            LocalArrivalEvidenceSelectionV2.serializer())
        assertTrue(decoded.documents.isEmpty())
        assertEquals(Uad2DocumentInputs.optionalRule, decoded.ruleId)
        assertEquals(Uad2DocumentInputs.retentionCarrier, decoded.retention.classId)
        assertEquals(Revision(2), decoded.retention.revision)
        assertEquals(expected, decoded)
        // This is not the signed optional rule's genuine action-use/retention outcome.
    }

    @Test
    fun uad2_03_repeated_original_identity_is_not_a_constructible_selected_manifest_list() {
        val a = Uad2DocumentInputs.manifest(Uad2DocumentInputs.file("DOC-A"))
        assertFails { Uad2DocumentInputs.evidence(documents = listOf(a, a)) }
    }

    @Test
    fun uad2_03_a_changed_exact_byte_does_not_reuse_the_frozen_original_digest_or_modify_the_recipe() {
        for (file in Uad2DocumentInputs.files) {
            val bytes = file.bytes()
            try {
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                val changed = LocalPurchasingDocumentCodecV1.content(bytes)
                assertEquals(file.byteLength, changed.byteLength)
                assertNotEquals(file.digest, changed.exactBytesDigest)
                val original = file.bytes()
                try { assertEquals(file.digest, LocalPurchasingDocumentCodecV1.content(original).exactBytesDigest) }
                finally { original.fill(0) }
            } finally { bytes.fill(0) }
        }
        // Actual incomplete/corrupt native history disclosure and preserved earlier results are UNBOUND.
    }
}
