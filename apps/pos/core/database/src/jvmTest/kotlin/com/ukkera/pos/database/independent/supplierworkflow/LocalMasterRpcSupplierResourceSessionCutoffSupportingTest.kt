package com.ukkera.pos.database.independent.supplierworkflow

import com.ukkera.pos.database.LocalMasterRpcExchangeDeviceIdentity
import com.ukkera.pos.database.LocalMasterRpcSessionEpoch
import com.ukkera.pos.database.LocalMasterRpcSessionFence
import com.ukkera.pos.database.LocalMasterRpcSessionIdentity
import com.ukkera.pos.database.LocalMasterRpcSessionInvalidation
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CredentialBinding
import com.ukkera.pos.security.LocalAccountSessionState
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalSessionRecord
import com.ukkera.pos.security.PasswordHashParameters
import com.ukkera.pos.security.PasswordHashRecord
import com.ukkera.pos.security.ProtectedLocalAccount
import com.ukkera.pos.security.ProtectedPepperReference
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.SessionStatus
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.RpcApplicationSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * SUPPORT slices of the unchanged supplier-rpc-workflow v1 SWF-036–041 criteria.
 * The production session fence and public official SDK application-session objects run over
 * inert identity/device metadata and a referential owner marker. Policy/Account/Admission are
 * internal pre-write classifications, not actual activated policy or protected SQL writes.
 * No principal verification, native Host/physical lifetime, supplier command/result, private
 * output commitment/receipt, Client UI or STORE/RPC/APP qualification is established here.
 * The final public SDK session API supplies no revoke-failure hook; that variant is unbound.
 */
class LocalMasterRpcSupplierResourceSessionCutoffSupportingTest {
    @Test
    fun swf037And041PolicyCutsOnlyAffectedActorBranchResourceSessions() = runTest(timeout = 5.seconds) {
        withSupplierFence { f ->
            val identity = f.employee(SupplierFenceInputs.original)
            val resource = f.employee(SupplierFenceInputs.original)
            val sameActorOtherDeviceResource = f.employee(SupplierFenceInputs.otherDevice)
            val otherActorResource = f.employee(SupplierFenceInputs.otherActor)
            val sameActorOtherBranchResource = f.employee(SupplierFenceInputs.otherBranch)
            val pendingExchange = f.exchange(SupplierFenceInputs.device)
            val attachedExchange = f.exchange(SupplierFenceInputs.device)
            val epoch = f.epoch()
            f.retainIdentity(epoch, identity)
            f.retainResource(epoch, resource)
            f.retainResource(epoch, sameActorOtherDeviceResource)
            f.retainResource(epoch, otherActorResource)
            f.retainResource(epoch, sameActorOtherBranchResource)
            f.retainExchange(epoch, pendingExchange)
            f.retainExchange(epoch, attachedExchange)
            f.attach(epoch, attachedExchange, SupplierFenceInputs.original)
            assertNotSame(identity.sdk, resource.sdk)
            assertEquals(identity.sdk.peer, resource.sdk.peer)
            assertEquals(identity.sdk.principal, resource.sdk.principal)
            assertEquals(identity.sdk.sessionId, resource.sdk.sessionId)

            f.fence.beforeProtectedWrite(SupplierFenceInputs.policy())

            assertFalse(resource.sdk.isActive)
            assertFalse(sameActorOtherDeviceResource.sdk.isActive)
            assertTrue(identity.sdk.isActive)
            assertTrue(otherActorResource.sdk.isActive)
            assertTrue(sameActorOtherBranchResource.sdk.isActive)
            assertTrue(pendingExchange.sdk.isActive)
            assertTrue(attachedExchange.sdk.isActive)
            val current = f.epoch()
            f.retainIdentity(current, identity)
            f.retainResource(current, otherActorResource)
            f.retainResource(current, sameActorOtherBranchResource)
            f.retainExchange(current, pendingExchange)
            f.retainExchange(current, attachedExchange)
            f.attach(current, attachedExchange, SupplierFenceInputs.original)
        }
        // Same scalar login identifiers do not merge the two exact SDK objects. Neither the
        // surviving identity/exchanges nor a new epoch is evidence of resource permission.
    }

    @Test
    fun swf037And039SameSdkObjectCannotChangeIdentityResourceOrExchangeRole() = runTest(timeout = 5.seconds) {
        withSupplierFence { f ->
            val identity = f.employee(SupplierFenceInputs.original)
            val resource = f.employee(SupplierFenceInputs.original)
            // Deliberate scalar overlap isolates role refusal from SDK identifier mismatch.
            val exchange = f.exchange(SupplierFenceInputs.device,
                principal = SupplierFenceInputs.original.actorId.value,
                sessionId = SupplierFenceInputs.original.sessionId.value)
            val epoch = f.epoch()
            f.retainIdentity(epoch, identity)
            f.retainResource(epoch, resource)
            f.retainExchange(epoch, exchange)
            f.attach(epoch, exchange, SupplierFenceInputs.original)
            assertNotSame(identity.sdk, resource.sdk)
            assertNotSame(resource.sdk, exchange.sdk)

            assertFalse(f.fence.retainResourceApplicationSession(f.owner, epoch, identity.sdk, identity.identity))
            assertFalse(f.fence.retainApplicationSession(f.owner, epoch, resource.sdk, resource.identity))
            assertFalse(f.fence.retainAuthenticationExchangeSession(f.owner, epoch, identity.sdk, exchange.device))
            assertFalse(f.fence.retainAuthenticationExchangeSession(f.owner, epoch, resource.sdk, exchange.device))
            assertFalse(f.fence.retainApplicationSession(f.owner, epoch, exchange.sdk, SupplierFenceInputs.original))
            assertFalse(f.fence.retainResourceApplicationSession(f.owner, epoch, exchange.sdk, SupplierFenceInputs.original))
            f.retainIdentity(epoch, identity)
            f.retainResource(epoch, resource)
            f.retainExchange(epoch, exchange)
            f.attach(epoch, exchange, SupplierFenceInputs.original)

            f.fence.beforeProtectedWrite(SupplierFenceInputs.policy())
            assertFalse(resource.sdk.isActive)
            assertTrue(identity.sdk.isActive)
            assertTrue(exchange.sdk.isActive)
            assertEquals(SupplierFenceInputs.original.actorId.value, exchange.sdk.principal)
            assertEquals(SupplierFenceInputs.original.sessionId.value, exchange.sdk.sessionId)
        }
        // Attaching result metadata is a revocation census operation, never a business scope.
    }

    @Test
    fun swf036And039PolicyEpochRefusesLateResourceAndOtherStaleCandidates() = runTest(timeout = 5.seconds) {
        withSupplierFence { f ->
            val resource = f.employee(SupplierFenceInputs.original)
            val identity = f.employee(SupplierFenceInputs.original)
            val exchange = f.exchange(SupplierFenceInputs.device)
            val candidateResource = f.employee(SupplierFenceInputs.sibling)
            val candidateIdentity = f.employee(SupplierFenceInputs.sibling)
            val candidateExchange = f.exchange(SupplierFenceInputs.device)
            val earlier = f.epoch()
            f.retainResource(earlier, resource)
            f.retainIdentity(earlier, identity)
            f.retainExchange(earlier, exchange)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val late = async(start = CoroutineStart.UNDISPATCHED) {
                entered.complete(Unit)
                release.await()
                f.fence.retainResourceApplicationSession(f.owner, earlier, candidateResource.sdk, candidateResource.identity)
            }
            try {
                assertTrue(entered.isCompleted)
                f.fence.beforeProtectedWrite(SupplierFenceInputs.policy())
                assertFalse(resource.sdk.isActive)
                assertTrue(identity.sdk.isActive)
                assertTrue(exchange.sdk.isActive)
                release.complete(Unit)
                assertFalse(late.await())
                assertFalse(f.fence.retainApplicationSession(f.owner, earlier, candidateIdentity.sdk, candidateIdentity.identity))
                assertFalse(f.fence.retainAuthenticationExchangeSession(f.owner, earlier, candidateExchange.sdk, candidateExchange.device))
                assertFalse(f.fence.attachAuthenticationExchangeResult(f.owner, earlier, exchange.sdk, exchange.device,
                    SupplierFenceInputs.original))
                assertTrue(candidateResource.sdk.isActive)
                assertTrue(candidateIdentity.sdk.isActive)
                assertTrue(candidateExchange.sdk.isActive)
                val current = f.epoch()
                f.retainResource(current, candidateResource)
                f.retainIdentity(current, candidateIdentity)
                f.retainExchange(current, candidateExchange)
                f.attach(current, exchange, SupplierFenceInputs.original)
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) { late.cancelAndJoin() }
            }
        }
        // The observed barrier is before helper entry, not principal verification/SQL/output.
        // Current-epoch retention checks only the seam; genuine fresh authority remains required.
    }

    @Test
    fun swf037And041OrdinaryNoneKeepsResourceIdentityAndExchangeSessions() = runTest(timeout = 5.seconds) {
        withSupplierFence { f ->
            val identity = f.employee(SupplierFenceInputs.original)
            val resource = f.employee(SupplierFenceInputs.original)
            val pendingExchange = f.exchange(SupplierFenceInputs.device)
            val attachedExchange = f.exchange(SupplierFenceInputs.device)
            val epoch = f.epoch()
            f.retainIdentity(epoch, identity)
            f.retainResource(epoch, resource)
            f.retainExchange(epoch, pendingExchange)
            f.retainExchange(epoch, attachedExchange)
            f.attach(epoch, attachedExchange, SupplierFenceInputs.original)

            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.None)

            assertTrue(identity.sdk.isActive)
            assertTrue(resource.sdk.isActive)
            assertTrue(pendingExchange.sdk.isActive)
            assertTrue(attachedExchange.sdk.isActive)
            val current = f.epoch()
            f.retainIdentity(current, identity)
            f.retainResource(current, resource)
            f.retainExchange(current, pendingExchange)
            f.retainExchange(current, attachedExchange)
            f.attach(current, attachedExchange, SupplierFenceInputs.original)
        }
        // None denotes ordinary non-invalidating writes, not an activated-policy write or grant.
    }

    @Test
    fun swf037And041LaterPolicySelectionStillCutsItsOwnAffectedActor() = runTest(timeout = 5.seconds) {
        withSupplierFence { f ->
            val identityA = f.employee(SupplierFenceInputs.original)
            val resourceA = f.employee(SupplierFenceInputs.original)
            val identityB = f.employee(SupplierFenceInputs.otherActor)
            val resourceB = f.employee(SupplierFenceInputs.otherActor)
            val epoch = f.epoch()
            f.retainIdentity(epoch, identityA)
            f.retainResource(epoch, resourceA)
            f.retainIdentity(epoch, identityB)
            f.retainResource(epoch, resourceB)

            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.None)
            assertTrue(resourceA.sdk.isActive)
            assertTrue(resourceB.sdk.isActive)
            f.fence.beforeProtectedWrite(SupplierFenceInputs.policy())
            assertFalse(resourceA.sdk.isActive)
            assertTrue(resourceB.sdk.isActive)
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Policy(
                SupplierFenceInputs.scope, SupplierFenceInputs.otherActor.actorId))
            assertFalse(resourceB.sdk.isActive)
            assertTrue(identityA.sdk.isActive)
            assertTrue(identityB.sdk.isActive)
        }
        // Separate direct calls do not qualify paired actual protected mutations or rollback.
    }

    @Test
    fun swf036And041AccountSessionRevocationCutsResourceIdentityAndAttachedResultOnly() =
        accountCut(enabled = true, siblingSurvives = true)

    @Test
    fun swf036And041AccountDisableCutsActorRolesButPreservesOtherActorAndPendingExchange() =
        accountCut(enabled = false, siblingSurvives = false)

    @Test
    fun swf036And041WithdrawnAdmissionCutsAllMatchingDeviceSessionRoles() =
        admissionCut(SupplierFenceInputs.admission(admitted = false))

    @Test
    fun swf036And041ChangedAdmissionRevisionCutsAllMatchingDeviceSessionRoles() =
        admissionCut(SupplierFenceInputs.admission(admitted = true, revision = Revision(4)))

    @Test
    fun swf036And039AllInvalidationCutsRetainedRolesWithoutRetargetingUnretainedObject() = runTest(timeout = 5.seconds) {
        withSupplierFence { f ->
            val identity = f.employee(SupplierFenceInputs.original)
            val resource = f.employee(SupplierFenceInputs.original)
            val otherActorResource = f.employee(SupplierFenceInputs.otherActor)
            val otherBranchResource = f.employee(SupplierFenceInputs.otherBranch)
            val pendingExchange = f.exchange(SupplierFenceInputs.device)
            val attachedExchange = f.exchange(SupplierFenceInputs.device)
            val unretained = f.employee(SupplierFenceInputs.sibling)
            val epoch = f.epoch()
            f.retainIdentity(epoch, identity)
            f.retainResource(epoch, resource)
            f.retainResource(epoch, otherActorResource)
            f.retainResource(epoch, otherBranchResource)
            f.retainExchange(epoch, pendingExchange)
            f.retainExchange(epoch, attachedExchange)
            f.attach(epoch, attachedExchange, SupplierFenceInputs.original)

            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.All)

            assertFalse(identity.sdk.isActive)
            assertFalse(resource.sdk.isActive)
            assertFalse(otherActorResource.sdk.isActive)
            assertFalse(otherBranchResource.sdk.isActive)
            assertFalse(pendingExchange.sdk.isActive)
            assertFalse(attachedExchange.sdk.isActive)
            assertTrue(unretained.sdk.isActive)
            f.retainResource(f.epoch(), unretained)
        }
        // All revokes the exact retained census. New helper retention is not a restored login.
    }

    @Test
    fun swf036And039DeniedFenceBlocksNewResourceRetentionAndDrainsExactCensus() = runTest(timeout = 5.seconds) {
        withSupplierFence { f ->
            val identity = f.employee(SupplierFenceInputs.original)
            val resource = f.employee(SupplierFenceInputs.original)
            val exchange = f.exchange(SupplierFenceInputs.device)
            val unretained = f.employee(SupplierFenceInputs.sibling)
            val earlier = f.epoch()
            f.retainIdentity(earlier, identity)
            f.retainResource(earlier, resource)
            f.retainExchange(earlier, exchange)

            f.fence.deny()
            assertNull(f.fence.captureSessionEpoch(f.owner))
            assertFalse(f.fence.retainResourceApplicationSession(f.owner, earlier, unretained.sdk, unretained.identity))
            assertTrue(unretained.sdk.isActive)
            f.fence.awaitSessionRevocation()
            assertFalse(identity.sdk.isActive)
            assertFalse(resource.sdk.isActive)
            assertFalse(exchange.sdk.isActive)
            assertTrue(unretained.sdk.isActive)
            f.fence.bind(f.owner)
            f.fence.awaitSessionRevocation()
            assertNull(f.fence.captureSessionEpoch(f.owner))
            assertFalse(f.fence.retainResourceApplicationSession(f.owner, earlier, unretained.sdk, unretained.identity))
            assertTrue(unretained.sdk.isActive)
        }
        // Direct helper denial is observed; no public SDK revoke failure/native close is injected.
    }

    private fun accountCut(enabled: Boolean, siblingSurvives: Boolean) = runTest(timeout = 5.seconds) {
        withSupplierFence { f ->
            val identity = f.employee(SupplierFenceInputs.original)
            val resource = f.employee(SupplierFenceInputs.original)
            val siblingIdentity = f.employee(SupplierFenceInputs.sibling)
            val siblingResource = f.employee(SupplierFenceInputs.sibling)
            val otherActorResource = f.employee(SupplierFenceInputs.otherActor)
            val otherBranchResource = f.employee(SupplierFenceInputs.otherBranch)
            val pendingExchange = f.exchange(SupplierFenceInputs.device)
            val attachedExchange = f.exchange(SupplierFenceInputs.device)
            val epoch = f.epoch()
            f.retainIdentity(epoch, identity)
            f.retainResource(epoch, resource)
            f.retainIdentity(epoch, siblingIdentity)
            f.retainResource(epoch, siblingResource)
            f.retainResource(epoch, otherActorResource)
            f.retainResource(epoch, otherBranchResource)
            f.retainExchange(epoch, pendingExchange)
            f.retainExchange(epoch, attachedExchange)
            f.attach(epoch, attachedExchange, SupplierFenceInputs.original)

            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Account(
                SupplierFenceInputs.account(enabled), SupplierFenceInputs.credentialRevision))

            assertFalse(identity.sdk.isActive)
            assertFalse(resource.sdk.isActive)
            assertFalse(attachedExchange.sdk.isActive)
            assertEquals(siblingSurvives, siblingIdentity.sdk.isActive)
            assertEquals(siblingSurvives, siblingResource.sdk.isActive)
            assertTrue(otherActorResource.sdk.isActive)
            assertTrue(otherBranchResource.sdk.isActive)
            assertTrue(pendingExchange.sdk.isActive)
        }
        // Inert account/session DTOs isolate regression selection, not authentication or storage.
    }

    private fun admissionCut(next: LocalDeviceAdmission) = runTest(timeout = 5.seconds) {
        withSupplierFence { f ->
            val identity = f.employee(SupplierFenceInputs.original)
            val resource = f.employee(SupplierFenceInputs.original)
            val otherActorSameDeviceResource = f.employee(SupplierFenceInputs.otherActor)
            val otherDeviceIdentity = f.employee(SupplierFenceInputs.otherDevice)
            val otherDeviceResource = f.employee(SupplierFenceInputs.otherDevice)
            val otherBranchResource = f.employee(SupplierFenceInputs.otherBranch)
            val pendingExchange = f.exchange(SupplierFenceInputs.device)
            val attachedExchange = f.exchange(SupplierFenceInputs.device)
            val otherBranchExchange = f.exchange(SupplierFenceInputs.device.copy(scope = SupplierFenceInputs.otherBranch.scope))
            val epoch = f.epoch()
            f.retainIdentity(epoch, identity)
            f.retainResource(epoch, resource)
            f.retainResource(epoch, otherActorSameDeviceResource)
            f.retainIdentity(epoch, otherDeviceIdentity)
            f.retainResource(epoch, otherDeviceResource)
            f.retainResource(epoch, otherBranchResource)
            f.retainExchange(epoch, pendingExchange)
            f.retainExchange(epoch, attachedExchange)
            f.retainExchange(epoch, otherBranchExchange)
            f.attach(epoch, attachedExchange, SupplierFenceInputs.original)

            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Admission(next))

            assertFalse(identity.sdk.isActive)
            assertFalse(resource.sdk.isActive)
            assertFalse(otherActorSameDeviceResource.sdk.isActive)
            assertFalse(pendingExchange.sdk.isActive)
            assertFalse(attachedExchange.sdk.isActive)
            assertTrue(otherDeviceIdentity.sdk.isActive)
            assertTrue(otherDeviceResource.sdk.isActive)
            assertTrue(otherBranchResource.sdk.isActive)
            assertTrue(otherBranchExchange.sdk.isActive)
        }
        // Admission metadata is not native admission proof or physical connection closure.
    }
}

/** Exact frozen F-AUTHORITY actor/device/branch inputs; all remaining coordinates are DATA. */
private object SupplierFenceInputs {
    val scope = BranchScope(DomainId("11111111-1111-4111-8111-111111111111"),
        DomainId("22222222-2222-4222-8222-222222222222"), DomainId("33333333-3333-4333-8333-333333333331"))
    private val actorA = DomainId("44444444-4444-4444-8444-444444444441")
    private val actorB = DomainId("44444444-4444-4444-8444-444444444442")
    private val deviceD = DomainId("55555555-5555-4555-8555-555555555551")
    private val deviceOther = DomainId("55555555-5555-4555-8555-555555555552")
    // Canonical symbolic session carriers only; no issuer, policy generation or native revision.
    val original = LocalMasterRpcSessionIdentity(scope, actorA, deviceD, sessionCarrier(1),
        SessionGeneration(7), Revision(3), AuthenticationPurpose.INTERACTIVE)
    val sibling = original.copy(sessionId = sessionCarrier(2), generation = SessionGeneration(8))
    val otherActor = original.copy(actorId = actorB, sessionId = sessionCarrier(3))
    val otherDevice = original.copy(deviceId = deviceOther, sessionId = sessionCarrier(4))
    val otherBranch = original.copy(scope = scope.copy(branchId = DomainId("33333333-3333-4333-8333-333333333332")),
        sessionId = sessionCarrier(5))
    val device = LocalMasterRpcExchangeDeviceIdentity(scope, deviceD, original.admissionRevision)
    val credentialRevision = Revision(0)

    fun policy(): LocalMasterRpcSessionInvalidation = LocalMasterRpcSessionInvalidation.Policy(scope, actorA)

    fun admission(admitted: Boolean, revision: Revision = original.admissionRevision): LocalDeviceAdmission =
        LocalDeviceAdmission(scope, deviceD, revision, admitted, "SWF-SUPPORTING-DATA", null, emptySet())

    fun account(enabled: Boolean): ProtectedLocalAccount {
        val state = LocalAccountSessionState(scope, actorA, enabled, Revision(2), SessionGeneration(9), listOf(
            record(original, SessionStatus.REVOKED),
            record(sibling, if (enabled) SessionStatus.ACTIVE else SessionStatus.REVOKED),
        ))
        // Non-null syntax-only sentinel distinguishes unchanged credential revision from removal.
        // No hasher, password, key owner, protected store or activated policy consumes this DTO.
        val credential = PasswordHashRecord(CredentialBinding(scope, actorA, credentialRevision),
            PasswordHashParameters(iterations = 600_000),
            ProtectedPepperReference(DomainId("99999999-9999-4999-8999-999999999991")), ByteArray(16), ByteArray(32))
        return ProtectedLocalAccount(state, credentialRevision, credential)
    }

    private fun record(identity: LocalMasterRpcSessionIdentity, status: SessionStatus): LocalSessionRecord =
        LocalSessionRecord(identity.sessionId, identity.scope, identity.actorId, identity.deviceId,
            identity.generation, status, identity.purpose)

    private fun sessionCarrier(index: Int): DomainId =
        DomainId("88888888-8888-4888-8888-88888888888$index")
}

private class SupplierEmployeeSdkSession(val identity: LocalMasterRpcSessionIdentity, val sdk: RpcApplicationSession)
private class SupplierExchangeSdkSession(val device: LocalMasterRpcExchangeDeviceIdentity, val sdk: RpcApplicationSession)

private class SupplierFenceFixture {
    val owner: Any = Any()
    val fence = LocalMasterRpcSessionFence().also { it.bind(owner) }
    private val owned = mutableListOf<RpcApplicationSession>()
    // Canonical fingerprint and positive lifetime are controlled SDK carrier data, not Q04 bounds.
    private val fingerprint = PeerFingerprint("p2f1-" + "a".repeat(52))

    fun employee(identity: LocalMasterRpcSessionIdentity): SupplierEmployeeSdkSession =
        SupplierEmployeeSdkSession(identity, own(RpcApplicationSession(fingerprint,
            identity.actorId.value, identity.sessionId.value, 60.seconds)))

    fun exchange(device: LocalMasterRpcExchangeDeviceIdentity,
        principal: String = "supplier-supporting-device-exchange",
        sessionId: String = "supplier-supporting-exchange-${owned.size}",
    ): SupplierExchangeSdkSession =
        SupplierExchangeSdkSession(device, own(RpcApplicationSession(fingerprint, principal, sessionId, 60.seconds)))

    suspend fun epoch(): LocalMasterRpcSessionEpoch = assertNotNull(fence.captureSessionEpoch(owner))

    suspend fun retainIdentity(epoch: LocalMasterRpcSessionEpoch, login: SupplierEmployeeSdkSession) {
        assertTrue(fence.retainApplicationSession(owner, epoch, login.sdk, login.identity))
    }

    suspend fun retainResource(epoch: LocalMasterRpcSessionEpoch, login: SupplierEmployeeSdkSession) {
        assertTrue(fence.retainResourceApplicationSession(owner, epoch, login.sdk, login.identity))
    }

    suspend fun retainExchange(epoch: LocalMasterRpcSessionEpoch, exchange: SupplierExchangeSdkSession) {
        assertTrue(fence.retainAuthenticationExchangeSession(owner, epoch, exchange.sdk, exchange.device))
    }

    suspend fun attach(epoch: LocalMasterRpcSessionEpoch, exchange: SupplierExchangeSdkSession,
        identity: LocalMasterRpcSessionIdentity) {
        assertTrue(fence.attachAuthenticationExchangeResult(owner, epoch, exchange.sdk, exchange.device, identity))
    }

    suspend fun dispose() = withContext(NonCancellable) {
        try { fence.awaitSessionRevocation() }
        finally { for (session in owned) session.revoke() }
    }

    private fun own(session: RpcApplicationSession): RpcApplicationSession = session.also { owned.add(it) }
}

private suspend fun withSupplierFence(block: suspend (SupplierFenceFixture) -> Unit) {
    val fixture = SupplierFenceFixture()
    try { block(fixture) }
    finally { fixture.dispose() }
}
