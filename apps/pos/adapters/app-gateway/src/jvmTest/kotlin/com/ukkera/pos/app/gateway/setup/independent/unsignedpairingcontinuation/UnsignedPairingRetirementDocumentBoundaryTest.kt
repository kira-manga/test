package com.ukkera.pos.app.gateway.setup.independent.unsignedpairingcontinuation

import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocumentExportResult
import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocumentImportResult
import com.ukkera.pos.app.gateway.setup.independent.freshdevicepairingworkflow.FreshWorkflowDocuments
import com.ukkera.pos.app.setup.LocalSetupPairingDocumentKind
import com.ukkera.pos.security.LocalDevicePairingUnsignedRetirement
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** UPRC-04 transport-only specimens; permitted size establishes no native certificate or authority. */
class UnsignedPairingRetirementDocumentBoundaryTest {
    @Test
    fun belowAndAtInclusiveRetirementBoundTransportEveryByteWithoutChangingExistingIcpBounds() = runBlocking {
        val kind = LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT
        assertEquals(2_187_264, kind.maximumBytes)
        assertEquals(16_384, LocalSetupPairingDocumentKind.SDK_IDENTITY.maximumBytes)
        assertEquals(2_113_536, LocalSetupPairingDocumentKind.CHALLENGE.maximumBytes)
        assertEquals(2_048, LocalSetupPairingDocumentKind.POSSESSION.maximumBytes)
        for (size in listOf(2_187_263, 2_187_264)) {
            val specimen = ByteArray(size) { 0xa5.toByte() }
            val completed = LocalSetupPairingDocumentImportResult.Completed(kind, specimen)
            val documents = FreshWorkflowDocuments()
            documents.offer(completed)
            val imported = assertIs<LocalSetupPairingDocumentImportResult.Completed>(documents.importDocument(kind) { true })
            assertEquals(size, imported.byteCount)
            assertBytes(specimen, imported.copyBytes())
            assertEquals(LocalSetupPairingDocumentExportResult.Completed, documents.exportDocument(kind, imported.copyBytes()) { true })
            assertBytes(specimen, documents.exports().single().bytes)
            specimen.fill(0)
            assertTrue(imported.copyBytes().all { it == 0xa5.toByte() }, "caller mutation cannot alter retained full DATA")
            assertFailsWith<IllegalArgumentException> { LocalDevicePairingUnsignedRetirement.decode(imported.copyBytes()) }
        }
    }

    @Test
    fun aboveInclusiveRetirementBoundAndEmptyInputAreRefusedWithoutTruncation() {
        val kind = LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT
        assertFailsWith<IllegalArgumentException> {
            LocalSetupPairingDocumentImportResult.Completed(kind, ByteArray(2_187_265) { 0xa5.toByte() })
        }
        assertFailsWith<IllegalArgumentException> {
            LocalSetupPairingDocumentImportResult.Completed(kind, ByteArray(0))
        }
    }
}
