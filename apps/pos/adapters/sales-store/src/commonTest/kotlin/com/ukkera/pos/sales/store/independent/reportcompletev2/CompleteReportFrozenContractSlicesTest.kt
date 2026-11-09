package com.ukkera.pos.sales.store.independent.reportcompletev2

import com.ukkera.pos.sales.store.LocalBranchReportExportCodecV1
import com.ukkera.pos.sales.store.LocalBranchReportExportCoverageV1
import com.ukkera.pos.sales.store.LocalBranchReportExportDestinationV1
import com.ukkera.pos.sales.store.LocalBranchReportExportFileV1
import com.ukkera.pos.sales.store.LocalBranchReportExportRequestV1
import com.ukkera.pos.security.PayloadDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** L0 contract slices only; legacy literal bytes and actual native delivery remain separate. */
class CompleteReportFrozenContractSlicesTest {
    private val f = FrozenCompleteReportInputs

    @Test
    fun RE32_001_legacyDefaultOmitsCoverageAndRetainsTheSelectedCursor() {
        val query = f.rootQuery.copy(after = f.key(6))
        val omitted = LocalBranchReportExportRequestV1.Begin(f.exportId, query, f.receiptId,
            LocalBranchReportExportDestinationV1.DESKTOP_LOCAL_FILE)
        val explicitDefault = omitted.copy(coverage = null)
        for (request in listOf(omitted, explicitDefault)) {
            val encoded = LocalBranchReportExportCodecV1.encode(request)
            val body = encoded.copyBytes()
            try {
                val json = Json.parseToJsonElement(body.decodeToString()).jsonObject
                assertFalse("coverage" in json)
                val decoded = assertIs<LocalBranchReportExportRequestV1.Begin>(
                    LocalBranchReportExportCodecV1.decodeRequest(encoded))
                assertEquals(query, decoded.query)
                assertEquals(f.exportId, decoded.exportId)
                assertEquals(f.receiptId, decoded.nativeReceiptId)
                assertEquals(null, decoded.coverage)
            } finally { body.fill(0) }
        }
    }

    @Test
    fun RE32_002_completeCoverageRetainsExactIdentityAndRoot() {
        val request = LocalBranchReportExportRequestV1.Begin(f.exportId, f.rootQuery, f.receiptId,
            LocalBranchReportExportDestinationV1.DESKTOP_LOCAL_FILE,
            coverage = LocalBranchReportExportCoverageV1.COMPLETE_CURRENT_SECTION_V2)
        val decoded = assertIs<LocalBranchReportExportRequestV1.Begin>(
            LocalBranchReportExportCodecV1.decodeRequest(LocalBranchReportExportCodecV1.encode(request)))
        assertEquals(request, decoded)
    }

    @Test
    fun RE32_008_finalUtf8ArithmeticAndMetadataSizeBoundary() {
        // These exact frozen M/é vectors are mathematical buffers, not business CSV acceptance.
        val metadata = "M".repeat(255) + "\n"
        val vectors = listOf(
            ByteVector("é".repeat(32639) + "x", 65279, 65535, 32896, true),
            ByteVector("é".repeat(32640), 65280, 65536, 32896, true),
            ByteVector("é".repeat(32640) + "x", 65281, 65537, 32897, false),
            ByteVector("x".repeat(65536), 65536, 65792, 65792, false),
        )
        assertEquals(256, metadata.encodeToByteArray().size)
        for (vector in vectors) {
            assertEquals(vector.dataBytes, vector.data.encodeToByteArray().size)
            val final = metadata + vector.data
            val bytes = final.encodeToByteArray()
            try {
                assertEquals(vector.finalBytes, bytes.size)
                assertEquals(vector.finalCodePoints, final.length)
                if (vector.accepted) {
                    val file = LocalBranchReportExportFileV1(f.sourceRevision, f.observedAt,
                        PayloadDigest("0".repeat(64)), bytes.size)
                    assertEquals(vector.finalBytes, file.byteCount)
                } else {
                    assertFailsWith<IllegalArgumentException> {
                        LocalBranchReportExportFileV1(f.sourceRevision, f.observedAt,
                            PayloadDigest("0".repeat(64)), bytes.size)
                    }
                }
            } finally { bytes.fill(0) }
        }
    }

    private data class ByteVector(val data: String, val dataBytes: Int, val finalBytes: Int,
        val finalCodePoints: Int, val accepted: Boolean)
}
