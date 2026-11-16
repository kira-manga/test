package com.ukkera.pos.rpc

import com.ukkera.pos.security.sha256
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.RpcApplicationSession
import dev.p2pkit.rpc.RpcRequestId
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Self-authored, NOT independent. SDK object/request DATA correlation only, no native
 * force, employee authority or publication. Inert objects deliberately stand for withheld
 * reply metadata; they cannot create a production LocalMasterPhysicalRpcDelivery. */
class PhysicalReplyRetentionSupportingTest {
    private fun actual() = RpcApplicationSession(PeerFingerprint("p2f1-" + "a".repeat(52)),
        "synthetic-employee", "synthetic-session", 60.seconds)
    private fun callData(n: Int) = LocalRpcPhysicalCallScopes.Call(sha256("reply-body-$n".encodeToByteArray()), "synthetic-body-$n")
    // This SDK constructor is JVM-public/Kotlin-internal. Like the existing frozen
    // physical-session DATA fixture, construct syntax-only SDK values through that
    // public JVM surface; no session, admission, grant or native capability is created.
    private fun request(n: Int): RpcRequestId = RpcRequestId::class.java.getConstructor(String::class.java)
        .newInstance("synthetic-physical-request-$n")

    @Test fun noReplyIsAvailableUntilTheExactOneShotSlotRetainsItsOriginal() = runTest {
        val pool = LocalRpcPhysicalReplyRetention<Any>(); val session = actual(); val call = callData(1)
        try {
            assertNull(pool.capture(session, request(1), call))
            val slot = assertNotNull(pool.reserve(session, request(1), call))
            assertNull(pool.capture(session, request(1), call))
            val original = Any(); assertTrue(pool.retain(session, slot, original))
            assertSame(original, pool.capture(session, request(1), call))
            assertFalse(pool.retain(session, slot, Any()))
            assertNull(pool.reserve(session, request(1), call))
            assertSame(original, pool.capture(session, request(1), call))
        } finally { session.revoke(); pool.removeRevoked(session) }
    }

    @Test fun laterSameBodyPollCannotReplaceAnEarlierCachedReply() = runTest {
        val pool = LocalRpcPhysicalReplyRetention<Any>(); val session = actual(); val call = callData(1)
        try {
            val first = assertNotNull(pool.reserve(session, request(1), call)); val original = Any()
            assertTrue(pool.retain(session, first, original))
            val next = assertNotNull(pool.reserve(session, request(2), call)); val later = Any()
            assertTrue(pool.retain(session, next, later))
            assertSame(original, pool.capture(session, request(1), call))
            assertSame(later, pool.capture(session, request(2), call))
            assertNull(pool.capture(session, request(3), call))
        } finally { session.revoke(); pool.removeRevoked(session) }
    }

    @Test fun sameIdentifierSdkTwinCannotRetainOrDiscloseTheOriginalReply() = runTest {
        val pool = LocalRpcPhysicalReplyRetention<Any>(); val original = actual(); val twin = actual(); val call = callData(1)
        try {
            val slot = assertNotNull(pool.reserve(original, request(1), call))
            assertFalse(pool.retain(twin, slot, Any()))
            val value = Any(); assertTrue(pool.retain(original, slot, value))
            assertNull(pool.capture(twin, request(1), call))
            assertSame(value, pool.capture(original, request(1), call))
        } finally { original.revoke(); twin.revoke(); pool.removeAllRevoked() }
    }

    @Test fun changedBodyOrEqualCopiedCallMetadataCannotBorrowAReply() = runTest {
        val pool = LocalRpcPhysicalReplyRetention<Any>(); val session = actual(); val call = callData(1)
        try {
            val slot = assertNotNull(pool.reserve(session, request(1), call)); val value = Any()
            assertTrue(pool.retain(session, slot, value))
            assertNull(pool.capture(session, request(1), callData(2)))
            assertNull(pool.capture(session, request(1), callData(1)))
            assertSame(value, pool.capture(session, request(1), call))
        } finally { session.revoke(); pool.removeRevoked(session) }
    }

    @Test fun fullLivePoolDoesNotEvictOrOverwriteAnEarlierOrUnknownReply() = runTest {
        val pool = LocalRpcPhysicalReplyRetention<Any>(); val session = actual(); val call = callData(1)
        try {
            val first = assertNotNull(pool.reserve(session, request(0), call))
            for (n in 1 until 1024) assertNotNull(pool.reserve(session, request(n), call))
            assertNull(pool.reserve(session, request(1024), call))
            assertNull(pool.capture(session, request(0), call))
            val original = Any(); assertTrue(pool.retain(session, first, original))
            assertSame(original, pool.capture(session, request(0), call))
            assertNull(pool.reserve(session, request(0), call))
        } finally { session.revoke(); pool.removeRevoked(session) }
    }

    @Test fun revokedSessionCannotFinishOrDiscloseAReplyAndLiveSlotsCannotBeRemoved() = runTest {
        val pool = LocalRpcPhysicalReplyRetention<Any>(); val session = actual(); val call = callData(1)
        try {
            val slot = assertNotNull(pool.reserve(session, request(1), call)); val pending = assertNotNull(pool.reserve(session, request(2), call))
            assertTrue(pool.retain(session, slot, Any()))
            assertFailsWith<IllegalStateException> { pool.removeRevoked(session) }
            session.revoke()
            assertNull(pool.capture(session, request(1), call))
            assertFalse(pool.retain(session, pending, Any()))
            assertNull(pool.reserve(session, request(3), call))
            pool.removeRevoked(session)
        } finally { session.revoke(); pool.removeAllRevoked() }
    }
}
