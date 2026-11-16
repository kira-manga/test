package com.ukkera.pos.rpc

import com.ukkera.pos.security.sha256
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.RpcApplicationSession
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Self-authored, NOT independent. Fixed physical-host supporting inputs v1 test
 * actual SDK object correlation and bounded immutable DATA only. Neither these SDK
 * objects nor a digest supplies native admission, employee grants or a physical cap. */
class PhysicalCallScopesSupportingTest {
    private fun actual() = RpcApplicationSession(PeerFingerprint("p2f1-" + "a".repeat(52)),
        "synthetic-employee", "synthetic-session", 60.seconds)
    private fun body(n: Int) = sha256("supporting-complete-body-$n".encodeToByteArray())

    @Test fun sameActualBodyReusesMetadataWhileDistinctBodyGetsDistinctCorrelation() = runTest {
        val scopes = LocalRpcPhysicalCallScopes(); val session = actual()
        try {
            val first = assertNotNull(scopes.admit(session, body(1)))
            assertSame(first, scopes.admit(session, body(1)))
            val next = assertNotNull(scopes.admit(session, body(2)))
            assertNotEquals(first.resource, next.resource)
            assertSame(first, scopes.capture(session, first.resource))
            assertSame(next, scopes.capture(session, next.resource))
            assertNull(scopes.capture(session, "pos.session.current"))
        } finally { session.revoke(); scopes.removeRevoked(session) }
    }

    @Test fun sameIdentifierSdkTwinCannotBorrowTheOriginalBodyScope() = runTest {
        val scopes = LocalRpcPhysicalCallScopes(); val original = actual(); val twin = actual()
        try {
            val call = assertNotNull(scopes.admit(original, body(1)))
            assertNull(scopes.capture(twin, call.resource))
            assertFalse(scopes.current(twin, call))
            val twinCall = assertNotNull(scopes.admit(twin, body(1)))
            assertNotSame(call, twinCall)
            assertFalse(scopes.current(twin, call))
            original.revoke(); scopes.removeRevoked(original)
            assertTrue(scopes.current(twin, twinCall))
        } finally { original.revoke(); twin.revoke(); scopes.removeAllRevoked() }
    }

    @Test fun fullLivePoolRefusesNewBodiesWithoutEvictingAnOriginal() = runTest {
        val scopes = LocalRpcPhysicalCallScopes(); val session = actual()
        try {
            val first = assertNotNull(scopes.admit(session, body(0)))
            for (n in 1 until 1024) assertNotNull(scopes.admit(session, body(n)))
            assertNull(scopes.admit(session, body(1024)))
            assertSame(first, scopes.admit(session, body(0)))
            assertTrue(scopes.current(session, first))
        } finally { session.revoke(); scopes.removeRevoked(session) }
    }

    @Test fun revocationDeniesReuseAndLiveScopesCannotBeRemoved() = runTest {
        val scopes = LocalRpcPhysicalCallScopes(); val session = actual()
        try {
            val call = assertNotNull(scopes.admit(session, body(1)))
            assertFailsWith<IllegalStateException> { scopes.removeRevoked(session) }
            assertTrue(scopes.current(session, call))
            session.revoke()
            assertFalse(scopes.current(session, call)); assertNull(scopes.capture(session, call.resource))
            assertNull(scopes.admit(session, body(1)))
            scopes.removeRevoked(session)
        } finally { session.revoke(); scopes.removeAllRevoked() }
    }
}
