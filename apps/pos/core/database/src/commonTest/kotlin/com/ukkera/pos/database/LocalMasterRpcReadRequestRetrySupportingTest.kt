package com.ukkera.pos.database

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import dev.p2pkit.rpc.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Self-authored, NOT independent. Real owned-carrier serialization and controlled
 * undelivered transport failures. No native identity, grant, writer or TCP is qualified. */
class LocalMasterRpcReadRequestRetrySupportingTest {
    private fun id(n: Int) = DomainId("00000002-0000-4000-8000-${n.toString().padStart(12, '0')}")
    private fun selection() = ReadRequest(ReadKey("supporting.retry.read", 1), StoredBytes(byteArrayOf(1, 2, 3)))
    private fun cursor() = LocalMasterChangeCursorV1(
        BranchScope(id(1), id(2), id(3)), AuthorityIdentity(id(4), id(5), Revision(1)),
        Revision(7), JournalSequence(12), sha256(byteArrayOf(1)), id(6), id(7), id(8),
        SessionGeneration(1), Revision(2), Revision(3), LocalMasterRpcWorkflowProfile.LAN_BUSINESS,
        sha256(byteArrayOf(2)), sha256(byteArrayOf(3)))
    private fun credentials() = LocalGatewayCredentials(id(9),
        OpaqueCredential(ByteArray(LocalMasterClientRpcProtocolV1.ACCESS_BEARER_BYTES) { it.toByte() }))

    @Test fun allFourReadRoutesRecaptureIdenticalArgumentsIntoDistinctOwnedCarriers() {
        val credentials = credentials()
        fun <Q : LocalMasterRpcOwnedRequestV1> checkCopies(template: Q, serializer: KSerializer<Q>,
            capture: (LocalGatewayCredentials) -> Q) {
            var first: PayloadDigest? = null
            var previous: Q? = null
            try {
                repeat(3) {
                    val fresh = freshRpcReadRequest(template, credentials.connectionId, capture)
                    try {
                        assertNotSame(template, fresh)
                        assertNotSame(previous, fresh)
                        val encoded = Json.encodeToString(serializer, fresh)
                        val digest = sha256(encoded.encodeToByteArray())
                        if (first == null) first = digest else assertEquals(first, digest)
                        assertTrue(fresh.isCleanupComplete)
                        assertFalse(template.isDestroyed)
                        template.useBearer { owned -> credentials.bearer.useBytes { caller -> assertTrue(owned.contentEquals(caller)) } }
                        previous = fresh
                    } finally { fresh.destroy() }
                }
            } finally { template.destroy() }
            assertTrue(template.isCleanupComplete)
            credentials.bearer.useBytes { assertEquals(LocalMasterClientRpcProtocolV1.ACCESS_BEARER_BYTES, it.size) }
        }
        try {
            val read = LocalMasterRpcReadRequestV1.capture(credentials, selection())
            checkCopies(read, LocalMasterRpcReadRequestSerializerV1) { LocalMasterRpcReadRequestV1.capture(it, read.request) }
            for (cursor in listOf(null, cursor())) {
                val changes = LocalMasterRpcChangesRequestV1.capture(credentials, selection(), cursor)
                checkCopies(changes, LocalMasterRpcChangesRequestSerializerV1) {
                    LocalMasterRpcChangesRequestV1.capture(it, changes.selection, changes.cursor)
                }
            }
            val batch = LocalMasterRpcChangesBatchRequestV1.capture(credentials, listOf(selection(),
                ReadRequest(ReadKey("supporting.retry.other", 1), StoredBytes(byteArrayOf(4)))))
            checkCopies(batch, LocalMasterRpcChangesBatchRequestSerializerV1) {
                LocalMasterRpcChangesBatchRequestV1.capture(it, batch.selections)
            }
        } finally { credentials.bearer.destroy() }
    }

    @Test fun permittedUndeliveredRetriesCanActuallyEncodeThreeFreshRequests() = runTest {
        val credentials = credentials()
        val template = LocalMasterRpcReadRequestV1.capture(credentials, selection())
        val requests = mutableListOf<LocalMasterRpcReadRequestV1>()
        try {
            val result = recoverUndeliveredRpcRead(current = {}) {
                val fresh = freshRpcReadRequest(template, credentials.connectionId) {
                    LocalMasterRpcReadRequestV1.capture(it, template.request)
                }
                requests += fresh
                try {
                    Json.encodeToString(LocalMasterRpcReadRequestSerializerV1, fresh)
                    if (requests.size < 3) throw RpcFailure(RpcFailureKind.DeadlineExceeded,
                        RpcFailurePhase.AwaitingResponse, executionEvidence = RpcExecutionEvidence.MayHaveExecuted)
                    42
                } finally { fresh.destroy() }
            }
            assertEquals(42, result)
            assertEquals(3, requests.size)
            assertTrue(requests.all { it.isCleanupComplete })
            assertTrue(requests.zipWithNext().all { (a, b) -> a !== b })
            assertFalse(template.isDestroyed)
        } finally { template.destroy(); credentials.bearer.destroy() }
    }

    @Test fun retiredTemplateCannotMintAnotherAttemptOrInvokeRecapture() {
        val credentials = credentials()
        val template = LocalMasterRpcReadRequestV1.capture(credentials, selection())
        var captures = 0
        try {
            template.destroy()
            assertFailsWith<IllegalStateException> {
                freshRpcReadRequest(template, credentials.connectionId) {
                    captures++
                    LocalMasterRpcReadRequestV1.capture(it, template.request)
                }
            }
            assertEquals(0, captures)
        } finally { template.destroy(); credentials.bearer.destroy() }
    }
}
