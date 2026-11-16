package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.RpcApplicationSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Self-authored, NOT independent. Exact-original correlation DATA controls using
 * real SDK objects and a controlled verifier, not protected native authentication.
 * Neither matching DATA nor this verifier supplies query permission or revocation proof. */
class ProtectedSnapshotOriginalSupportingTest {
    private fun id(n: Int) = DomainId("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))
    private val original = VerifiedLocalIdentity(id(4), BranchScope(id(1), id(2), id(3)), id(5), id(6),
        SessionGeneration(1), Revision(0), AuthenticationPurpose.INTERACTIVE)
    private fun resource(session: RpcApplicationSession, active: Boolean = true) = LocalMasterRpcResourceSession(
        LocalMasterRpcSessionIdentity(original.scope, original.actorId, original.deviceId, original.sessionId,
            original.sessionGeneration, original.admissionRevision, original.purpose), session,
        MutableStateFlow(active), MutableStateFlow(false))
    private suspend fun principal(value: VerifiedLocalIdentity): LocalEmployeePrincipal? {
        val source = object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials) = value
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }
        val credentials = LocalGatewayCredentials(id(7), OpaqueCredential(ByteArray(52) { 1 }))
        return try { TrustedPrincipalFactory(source).authenticateLocal(credentials) }
        finally { credentials.bearer.destroy() }
    }
    private fun session() = RpcApplicationSession(PeerFingerprint("p2f1-" + "a".repeat(52)),
        original.actorId.value, original.sessionId.value, 60.seconds)

    @Test fun matchingPrincipalMustKeepEveryOriginalCoordinate() = runTest {
        val session = session()
        try {
            val retained = resource(session)
            assertTrue(retained.matchesOriginal(assertNotNull(principal(original))))
            val changes = listOf(original.copy(actorId = id(8)), original.copy(deviceId = id(8)), original.copy(sessionId = id(8)),
                original.copy(sessionGeneration = SessionGeneration(2)), original.copy(admissionRevision = Revision(1)),
                original.copy(scope = original.scope.copy(organizationId = id(8))),
                original.copy(scope = original.scope.copy(companyId = id(8))), original.copy(scope = original.scope.copy(branchId = id(8))))
            for (changed in changes) assertFalse(retained.matchesOriginal(assertNotNull(principal(changed))))
        } finally { session.revoke() }
    }

    @Test fun noninteractivePrincipalsCannotMatchTheResourceOriginal() = runTest {
        val session = session()
        try {
            val retained = resource(session)
            for (purpose in AuthenticationPurpose.entries.filter { it != AuthenticationPurpose.INTERACTIVE }) {
                val changed = principal(original.copy(purpose = purpose))
                if (changed != null) assertFalse(retained.matchesOriginal(changed))
            }
        } finally { session.revoke() }
    }

    @Test fun matchingIdentityDataDoesNotConferCurrentResourceAuthority() = runTest {
        val session = session()
        try {
            val denied = resource(session, active = false)
            assertTrue(denied.matchesOriginal(assertNotNull(principal(original))))
            assertFalse(denied.current())
            session.revoke()
            assertFalse(resource(session).current())
        } finally { session.revoke() }
    }
}
