package com.ukkera.pos.app.gateway.independent.documentaryclientv2

import com.ukkera.pos.app.gateway.StoredClientDocumentarySourceV1
import com.ukkera.pos.catalog.store.LocalCompletePurchasingDocumentV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentCodecV1
import com.ukkera.pos.types.OperationId
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Frozen U byte/identity/bounded-source slices; not complete cases or native custody. */
class DocumentaryClientFrozenSourceSlicesTest {
    private val inputs = FrozenDocumentaryClientInputsV2

    @Test
    fun di2_001_exactOriginalBytesAndFiveSavedStepIdentities() {
        val document = inputs.document("PRIMARY-A")
        val source = inputs.source(document)
        assertEquals(16_407, document.file.bytes.size)
        assertEquals(document.file.digest.value, inputs.hash(document.file.bytes))
        assertEquals(listOf(8192, 8192, 23), document.file.slots.map { it.byteLength })
        assertEquals(listOf(document.beginId) + document.chunkIds + document.completeId, source.operationIds)
        assertEquals(5, source.operationIds.toSet().size)
        assertEquals("0007-A", source.begin.supplierReferenceOriginal)
        assertEquals("2026-10-21", source.begin.documentDate?.canonicalString())
        assertEquals(document.beginId, source.complete.beginOperationId)
        assertNotEquals(source.beginOperationId, source.completeOperationId)
        assertContentEquals(document.file.bytes, source.copyOriginalBytes())
        source.chunkOperationIds.indices.forEach { index ->
            val request = source.chunkRequest(index)
            val chunk = LocalPurchasingDocumentCodecV1.decodeChunk(request.payload)
            val expected = document.file.slot(index)
            assertEquals(document.chunkIds[index], request.operationId)
            assertEquals(document.id, chunk.documentId)
            assertEquals(document.beginId, chunk.beginOperationId)
            assertEquals(index, chunk.chunkIndex)
            assertEquals(inputs.hex(expected), chunk.chunkHex)
            assertEquals(document.file.slots[index].digest.value, inputs.hash(expected))
            assertContentEquals(expected, LocalPurchasingDocumentCodecV1.decodeChunkHex(chunk.chunkHex))
            assertTrue(request.payload.size <= 32_768)
        }
    }

    @Test
    fun di2_003_008_callerBytesAndListsCannotReplaceSavedOriginalBodies() {
        val document = inputs.document("PRIMARY-A")
        val original = document.file.bytes.copyOf()
        val operations = document.chunkIds.toMutableList()
        val begin = inputs.begin(document)
        val source = StoredClientDocumentarySourceV1.prepare(inputs.owner, document.beginId, begin, operations,
            document.completeId, LocalCompletePurchasingDocumentV1(document.id, document.beginId), original)
        val firstBodies = inputs.steps(source).map { it.payload.copyBytes() }
        original.fill(0)
        operations[0] = inputs.document("PRIMARY-B").chunkIds.single()
        val disclosedCopy = source.copyOriginalBytes()
        disclosedCopy.fill(0)
        assertContentEquals(document.file.bytes, source.copyOriginalBytes())
        assertEquals(document.chunkIds, source.chunkOperationIds)
        inputs.steps(source).forEachIndexed { index, request -> assertContentEquals(firstBodies[index], request.payload.copyBytes()) }
    }

    @Test
    fun di2_002_004_exactSmallFilesSlotEdgesAndPrimarySupplementBounds() {
        listOf("PDF-A" to 640, "PNG-A" to 69, "JPEG-A" to 155, "PDF-SLOT-8192" to 8192,
            "PDF-SLOT-8193" to 8193, "PDF-PRIMARY-MAX" to 524_288).forEachIndexed { index, (name, length) ->
            val document = inputs.document(180 + index, name)
            val source = inputs.source(document)
            assertEquals(length, source.copyOriginalBytes().size)
            assertEquals(document.file.digest.value, inputs.hash(source.copyOriginalBytes()))
            assertEquals(document.file.slots.size, source.chunkOperationIds.size)
            source.chunkOperationIds.indices.forEach { slot ->
                val chunk = LocalPurchasingDocumentCodecV1.decodeChunk(source.chunkRequest(slot).payload)
                assertEquals(document.file.slots[slot].byteLength * 2, chunk.chunkHex.length)
                assertEquals(inputs.hex(document.file.slot(slot)), chunk.chunkHex)
            }
        }
        val supplement = inputs.document("CHILD-1")
        val supplementalSource = inputs.source(supplement)
        assertEquals(262_144, supplementalSource.copyOriginalBytes().size)
        assertEquals(32, supplementalSource.chunkOperationIds.size)
        assertEquals(supplement.parent, supplementalSource.begin.parentOriginalId)
        assertEquals(inputs.parentRevision, supplementalSource.complete.expectedParentAttachmentsRevision)
        listOf("PDF-PRIMARY-OVER", "EMPTY").forEach { name -> assertFails { inputs.source(inputs.document(186, name)) } }
        assertFails { inputs.source(inputs.document(187, "PDF-SUPPLEMENT-OVER", inputs.document("PARENT").id)) }
    }

    @Test
    fun di2_006_007_008_sourceRequiresOriginalHashBeginOwnerAndDistinctOperations() {
        val document = inputs.document("PRIMARY-A")
        val begin = inputs.begin(document)
        fun prepare(complete: LocalCompletePurchasingDocumentV1 = LocalCompletePurchasingDocumentV1(document.id, document.beginId),
            operations: List<OperationId> = document.chunkIds) = StoredClientDocumentarySourceV1.prepare(inputs.owner,
            document.beginId, begin, operations, document.completeId, complete, document.file.bytes)
        assertFails { StoredClientDocumentarySourceV1.prepare(inputs.owner, document.beginId,
            begin.copy(exactBytesDigest = inputs.wrongFullDigest), document.chunkIds, document.completeId,
            LocalCompletePurchasingDocumentV1(document.id, document.beginId), document.file.bytes) }
        assertFails { prepare(LocalCompletePurchasingDocumentV1(document.id, inputs.document("PRIMARY-B").beginId)) }
        assertFails { prepare(operations = listOf(document.beginId) + document.chunkIds.drop(1)) }
        assertFails { prepare().validate(inputs.otherOwner) }
        assertFails { prepare().validate(inputs.foreignOwner) }
        assertFails { StoredClientDocumentarySourceV1.prepare(inputs.owner, document.beginId, begin, document.chunkIds,
            document.completeId, LocalCompletePurchasingDocumentV1(document.id, document.beginId), document.file.bytes.copyOf(8191)) }
    }

    @Test
    fun di2_027_frozenAsciiTextBoundariesRemainExactAndExcessControlsFailClosed() {
        val text = inputs.fixture.getValue("textBoundaries").jsonObject
        val negative = inputs.fixture.getValue("negativeInputs").jsonObject.getValue("CONTROL-TEXT").jsonObject
        val document = inputs.document("CORRECTION-B")
        val original = inputs.begin(document)
        fun prepare(reference: String = original.supplierReferenceOriginal, filename: String = original.originalFileName,
            reason: String = requireNotNull(original.correctionReason)): StoredClientDocumentarySourceV1 =
            StoredClientDocumentarySourceV1.prepare(inputs.owner, document.beginId,
                original.copy(supplierReferenceOriginal = reference, originalFileName = filename, correctionReason = reason),
                document.chunkIds, document.completeId, LocalCompletePurchasingDocumentV1(document.id, document.beginId), document.file.bytes)
        val valid = prepare(text.getValue("supplierReference192").jsonPrimitive.content,
            text.getValue("fileName192").jsonPrimitive.content, text.getValue("correctionReason256").jsonPrimitive.content)
        assertEquals(text.getValue("supplierReference192").jsonPrimitive.content, valid.begin.supplierReferenceOriginal)
        assertEquals(text.getValue("fileName192").jsonPrimitive.content, valid.begin.originalFileName)
        assertEquals(text.getValue("correctionReason256").jsonPrimitive.content, valid.begin.correctionReason)
        assertFails { prepare(reference = text.getValue("supplierReference193").jsonPrimitive.content) }
        assertFails { prepare(filename = text.getValue("fileName193").jsonPrimitive.content) }
        assertFails { prepare(reason = text.getValue("correctionReason257").jsonPrimitive.content) }
        assertFails { prepare(reference = negative.getValue("supplierReferenceOriginal").jsonPrimitive.content) }
        assertFails { prepare(filename = negative.getValue("originalFileName").jsonPrimitive.content) }
        assertFails { prepare(reason = negative.getValue("correctionReason").jsonPrimitive.content) }
        inputs.fixture.getValue("negativeInputs").jsonObject.getValue("MALFORMED-HEX").jsonObject
            .getValue("values").jsonArray.forEach { element ->
                assertFails { LocalPurchasingDocumentCodecV1.decodeChunkHex(element.jsonPrimitive.content) }
            }
    }
}
