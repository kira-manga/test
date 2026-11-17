package com.ukkera.pos.database

import androidx.room3.Transactor
import com.ukkera.pos.security.*
import kotlin.test.*
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.RpcApplicationSession
import kotlin.time.Duration.Companion.seconds

/** Self-authored, NOT independent. Actual file Room/AEAD/token/current-state reads;
 * controlled peer/time/keys, no native custody or production runtime acceptance.
 * Genuine two-Client TCP is a separate mandatory check of the optimized composition. */
class ProtectedReadAuthenticationSupportingTest {
    // A genuine SDK session object with CONTROLLED metadata, not an admitted native peer.
    // It supplies no authentication: the tests still use the actual protected bearer path.
    private fun resource(principal: LocalEmployeePrincipal, wrongActor: Boolean = false) = LocalMasterRpcResourceSession(
        LocalMasterRpcSessionIdentity(principal.scope, if (wrongActor) testId(9999) else principal.actorId,
            principal.deviceId, principal.sessionId, principal.sessionGeneration, principal.admissionRevision,
            principal.authenticationPurpose),
        RpcApplicationSession(PeerFingerprint("p2f1-" + "a".repeat(52)), "controlled-read", testId(9998).value, 60.seconds),
        MutableStateFlow(true), MutableStateFlow(false))

    private suspend fun read(f: LocalAccessVerifierFixture, bytes: ByteArray = localAccessWire(), caller: CoroutineContext? = null):
        RoomLocalAccessPrincipalVerifier.ReadVerification? {
        val requestContext = caller ?: currentCoroutineContext()
        val secret = OpaqueCredential(bytes)
        return try {
            f.base.database.runtimeWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    f.verifier.authenticateAtReadWriter(f.base.database, connection,
                        LocalGatewayCredentials(ACCESS_CONNECTION, secret), requestContext)
                }
            }
        } finally { secret.destroy() }
    }

    @Test fun sameProtectedWriterAuthenticatesExactIdentityWithoutMutation() = runTest {
        val f = LocalAccessVerifierFixture()
        try {
            f.base.seed()
            val before = f.base.snapshot()
            val verified = assertNotNull(read(f))
            assertEquals(TEST_ACTOR, verified.principal.actorId)
            assertEquals(TEST_SCOPE, verified.principal.scope)
            assertEquals(TEST_AUTHORITY.deviceId, verified.principal.deviceId)
            assertTrue(verified.currentAfterRelease())
            assertEquals(before, f.base.snapshot())
        } finally { f.close() }
    }

    @Test fun wrongBearerAndUnknownLocatorCannotAuthenticateOnBorrowedWriter() = runTest {
        val f = LocalAccessVerifierFixture()
        try {
            f.base.seed()
            val before = f.base.snapshot()
            assertNull(read(f, localAccessWire(secret = ByteArray(32))))
            assertNull(read(f, localAccessWire(sessionId = testId(9999))))
            assertNull(read(f, ByteArray(52)))
            assertEquals(before, f.base.snapshot())
            assertNotNull(read(f)) // Failed reads do not invent a successful principal or destroy valid access.
        } finally { f.close() }
    }

    @Test fun everyRequiredPhysicalPeerDescriptorRemainsMandatory() = runTest {
        for (field in listOf("device", "scope", "key", "fingerprint", "retired")) {
            val f = LocalAccessVerifierFixture()
            try {
                f.base.seed()
                when (field) {
                    "device" -> f.peers.deviceId = testId(9999)
                    "scope" -> f.peers.scope = TEST_SCOPE.copy(branchId = testId(9999))
                    "key" -> f.peers.key = "wrong-key"
                    "fingerprint" -> f.peers.fingerprint = "wrong-fingerprint"
                    "retired" -> f.peers.current = false
                }
                assertNull(read(f), field)
            } finally { f.close() }
        }
    }

    @Test fun actualCurrentSessionRevocationIsNotRestoredByTheReadPath() = runTest {
        val f = LocalAccessVerifierFixture()
        try {
            f.base.seed()
            assertNotNull(read(f))
            f.revoke()
            assertNull(read(f))
        } finally { f.close() }
    }

    @Test fun outerReleaseStillRequiresSamePhysicalLifetimeAndUnexpiredCurrentEnvironment() = runTest {
        for (cut in listOf("peer", "time", "context")) {
            val f = LocalAccessVerifierFixture()
            try {
                f.base.seed()
                val verified = assertNotNull(read(f))
                when (cut) {
                    "peer" -> f.peers.lifetime = Any()
                    "time" -> f.base.environment.elapsed = 2_000L
                    "context" -> f.base.environment.contextId = testId(9999)
                }
                if (cut == "context") {
                    assertFailsWith<RoomCurrentLocalAuthorizationDenied> { verified.currentAfterRelease() }
                } else assertFalse(verified.currentAfterRelease(), cut)
            } finally { f.close() }
        }
    }

    @Test fun anotherDatabaseOrAuthorizationSourceCannotSelectThisVerifier() = runTest {
        val a = LocalAccessVerifierFixture()
        val b = LocalAccessVerifierFixture()
        try {
            assertTrue(a.verifier.belongsTo(a.base.database, a.base.source))
            assertFalse(a.verifier.belongsTo(b.base.database, a.base.source))
            assertFalse(a.verifier.belongsTo(a.base.database, b.base.source))
        } finally { a.close(); b.close() }
    }

    @Test fun cancellationOfTheOwningRequestStillPreventsPostLoanPublication() = runTest {
        val f = LocalAccessVerifierFixture()
        val requestJob = Job()
        try {
            f.base.seed()
            val verified = assertNotNull(read(f, caller = currentCoroutineContext() + requestJob))
            requestJob.cancel()
            assertFailsWith<CancellationException> { verified.currentAfterRelease() }
        } finally { requestJob.cancel(); f.close() }
    }

    @Test fun actualSingleWriterCapacityCheckNeverGrantsDataAndStillSeesRevocation() = runTest {
        val f = LocalAccessVerifierFixture()
        try {
            f.base.seed()
            val principal = assertNotNull(f.authenticate())
            val original = resource(principal)
            val bearer = OpaqueCredential(localAccessWire())
            try {
                val credentials = LocalGatewayCredentials(ACCESS_CONNECTION, bearer)
                val kernel = f.base.kernel()
                val before = f.base.snapshot()
                val allowed = kernel.authorizeChangesCapacityAtWriter(credentials, LocalMasterRpcWorkflowProfile.LAN_BUSINESS,
                    original, f.verifier)
                assertEquals(LocalMasterChangesRpcAccessResultV1.CAPACITY_ONLY, allowed.result)
                assertNull(allowed.binding); assertNull(allowed.readProjectionBinding)
                assertEquals(before, f.base.snapshot())
                f.revoke()
                assertEquals(LocalMasterChangesRpcAccessResultV1.DENIED,
                    kernel.authorizeChangesCapacityAtWriter(credentials, LocalMasterRpcWorkflowProfile.LAN_BUSINESS,
                        original, f.verifier).result)
            } finally { bearer.destroy() }
        } finally { f.close() }
    }

    @Test fun capacityCheckRejectsWrongBearerAndOriginalIdentityWithoutCreatingAnEffect() = runTest {
        val f = LocalAccessVerifierFixture()
        try {
            f.base.seed()
            val principal = assertNotNull(f.authenticate())
            val kernel = f.base.kernel()
            val before = f.base.snapshot()
            for (badBearer in listOf(false, true)) {
                val bearer = OpaqueCredential(if (badBearer) localAccessWire(secret = ByteArray(32)) else localAccessWire())
                try {
                    val result = kernel.authorizeChangesCapacityAtWriter(LocalGatewayCredentials(ACCESS_CONNECTION, bearer),
                        LocalMasterRpcWorkflowProfile.LAN_BUSINESS, resource(principal, wrongActor = !badBearer), f.verifier)
                    assertEquals(LocalMasterChangesRpcAccessResultV1.DENIED, result.result)
                    assertNull(result.binding); assertNull(result.readProjectionBinding)
                } finally { bearer.destroy() }
            }
            assertEquals(before, f.base.snapshot())
        } finally { f.close() }
    }
}
