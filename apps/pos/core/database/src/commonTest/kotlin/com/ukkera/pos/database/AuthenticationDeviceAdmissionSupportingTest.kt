package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.RpcApplicationSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Self-authored, NOT independent. Real SDK objects and the production denial census.
 * Latches model the protected native-read interval; they do not supply native admission
 * or employee authority. The production registration must perform that actual read.
 * Supporting LAN-A02/A08/R05; frozen inputs and whole-case qualification are unchanged. */
class AuthenticationDeviceAdmissionSupportingTest {
    @Test fun unrelatedWriteDuringNativeReadPreservesDeviceCandidateWithoutGrantingEmployeeScope() = runTest {
        fixture { f ->
            val epoch = assertNotNull(f.fence.captureSessionEpoch(f.owner))
            assertTrue(f.prepare())
            assertFalse(f.fence.prepareAuthenticationExchangeResult(f.owner, f.session, f.device, f.identity))
            assertNull(f.fence.captureResourceSession(f.owner, f.session))
            val release = CompletableDeferred<Unit>()
            val complete = async(start = CoroutineStart.UNDISPATCHED) { release.await(); f.complete() }
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.None)
            assertFalse(f.fence.retainAuthenticationExchangeSession(f.owner, epoch, f.session, f.device))
            release.complete(Unit)
            assertTrue(complete.await())
            assertTrue(f.fence.prepareAuthenticationExchangeResult(f.owner, f.session, f.device, f.identity))
            assertNull(f.fence.captureResourceSession(f.owner, f.session))
        }
    }

    @Test fun matchingWithdrawalWinningNativeReadIrreversiblyDeniesCompletion() = cutDuringRead {
        LocalMasterRpcSessionInvalidation.Admission(LocalDeviceAdmission(it.device.scope,
            it.device.deviceId, it.device.admissionRevision, false, null, null, emptySet()))
    }

    @Test fun unknownWriteWinningNativeReadIrreversiblyDeniesCompletion() = cutDuringRead {
        LocalMasterRpcSessionInvalidation.All
    }

    @Test fun absentCandidateForeignSdkObjectAndForeignOwnerCannotComplete() = runTest {
        fixture { f ->
            assertFalse(f.complete()); assertTrue(f.prepare())
            val twin = RpcApplicationSession(f.session.peer, f.session.principal, f.session.sessionId, 60.seconds)
            try {
                assertFalse(f.fence.completeAuthenticationExchangeSession(f.owner, twin, f.device))
                assertFalse(f.fence.completeAuthenticationExchangeSession(Any(), f.session, f.device))
                assertTrue(f.complete())
            } finally { twin.revoke() }
        }
    }

    @Test fun candidateDeviceScopeAndRevisionCannotBeRetargeted() = runTest {
        fixture { f ->
            assertTrue(f.prepare())
            for (other in listOf(f.device.copy(deviceId = id(8)), f.device.copy(admissionRevision = Revision(2)),
                f.device.copy(scope = f.device.scope.copy(branchId = id(9))))) {
                assertFalse(f.fence.prepareAuthenticationExchangeSession(f.owner, f.session, other))
                assertFalse(f.fence.completeAuthenticationExchangeSession(f.owner, f.session, other))
            }
            assertTrue(f.complete()); assertTrue(f.prepare()); assertTrue(f.complete())
        }
    }

    @Test fun unrelatedDeviceCutPreservesCandidateButHostDenialCannotBeRearmed() = runTest {
        fixture { f ->
            assertTrue(f.prepare())
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Admission(LocalDeviceAdmission(
                f.device.scope, id(8), Revision(1), false, null, null, emptySet())))
            assertTrue(f.complete())
            f.fence.deny(); assertFalse(f.prepare()); assertFalse(f.complete())
            f.fence.awaitSessionRevocation(); assertFalse(f.complete())
        }
    }

    /** Self-authored supporting native-census input v1: opaque origin is denial DATA,
     * never a supplied native proof. Production alone performs the real fresh read. */
    @Test fun unknownDeviceOriginSurvivesNoneButCannotExposeIdentityBeforeNativeCompletion() = runTest {
        fixture { f ->
            val origin = Any()
            assertTrue(f.fence.prepareNativeAuthenticationExchange(f.owner, f.session, origin))
            assertFalse(f.fence.prepareAuthenticationExchangeResult(f.owner, f.session, f.device, f.identity))
            assertNull(f.fence.captureResourceSession(f.owner, f.session))
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.None)
            assertTrue(f.fence.completeNativeAuthenticationExchange(f.owner, f.session, origin, f.device))
            assertTrue(f.fence.prepareAuthenticationExchangeResult(f.owner, f.session, f.device, f.identity))
            assertTrue(f.fence.completeAuthenticationExchangeResult(f.owner, f.session, f.device, f.identity))
            // A retry of this same attached original keeps exactly the same SDK/origin.
            assertTrue(f.fence.prepareNativeAuthenticationExchange(f.owner, f.session, origin))
            assertTrue(f.fence.completeNativeAuthenticationExchange(f.owner, f.session, origin, f.device))
            assertNull(f.fence.captureResourceSession(f.owner, f.session))
        }
    }

    @Test fun anyAdmissionOrUnknownCutDuringUnidentifiedNativeReadIrreversiblyDenies() = runTest {
        for (cut in listOf(LocalMasterRpcSessionInvalidation.All,
            LocalMasterRpcSessionInvalidation.Admission(LocalDeviceAdmission(
                BranchScope(id(1), id(2), id(3)), id(8), Revision(1), false, null, null, emptySet())))) fixture { f ->
            val origin = Any()
            assertTrue(f.fence.prepareNativeAuthenticationExchange(f.owner, f.session, origin))
            f.fence.beforeProtectedWrite(cut)
            assertFalse(f.fence.completeNativeAuthenticationExchange(f.owner, f.session, origin, f.device))
            assertFalse(f.fence.prepareNativeAuthenticationExchange(f.owner, f.session, origin))
            assertFalse(f.session.isActive)
        }
    }

    @Test fun pendingNativeOriginCannotChangeOwnerPhysicalObjectOrSdkObject() = runTest {
        fixture { f ->
            val origin = Any()
            assertFalse(f.fence.completeNativeAuthenticationExchange(f.owner, f.session, origin, f.device))
            assertTrue(f.fence.prepareNativeAuthenticationExchange(f.owner, f.session, origin))
            val twin = RpcApplicationSession(f.session.peer, f.session.principal, f.session.sessionId, 60.seconds)
            try {
                assertFalse(f.fence.prepareNativeAuthenticationExchange(f.owner, f.session, Any()))
                assertFalse(f.fence.completeNativeAuthenticationExchange(f.owner, f.session, Any(), f.device))
                assertFalse(f.fence.completeNativeAuthenticationExchange(Any(), f.session, origin, f.device))
                assertFalse(f.fence.completeNativeAuthenticationExchange(f.owner, twin, origin, f.device))
                assertTrue(f.fence.completeNativeAuthenticationExchange(f.owner, f.session, origin, f.device))
            } finally { twin.revoke() }
        }
    }

    @Test fun completedNativeOriginRemainsDeviceSelectiveAndCannotRetarget() = runTest {
        fixture { f ->
            val origin = Any()
            assertTrue(f.fence.prepareNativeAuthenticationExchange(f.owner, f.session, origin))
            assertTrue(f.fence.completeNativeAuthenticationExchange(f.owner, f.session, origin, f.device))
            assertFalse(f.fence.completeNativeAuthenticationExchange(f.owner, f.session, origin, f.device.copy(deviceId = id(8))))
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Admission(LocalDeviceAdmission(
                f.device.scope, id(8), Revision(1), false, null, null, emptySet())))
            assertTrue(f.fence.completeNativeAuthenticationExchange(f.owner, f.session, origin, f.device))
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Admission(LocalDeviceAdmission(
                f.device.scope, f.device.deviceId, Revision(2), false, null, null, emptySet())))
            assertFalse(f.fence.prepareNativeAuthenticationExchange(f.owner, f.session, origin))
            assertFalse(f.fence.completeNativeAuthenticationExchange(f.owner, f.session, origin, f.device))
        }
    }

    /** Supporting physical-host inputs v1. Device-only evidence delivery must never
     * promote a pending admission, a same-ID SDK twin or an attached employee result. */
    @Test fun immutableOriginalScopeRequiresCompletedExactDeviceAndCannotBecomeEmployeeResource() = runTest {
        fixture { f ->
            assertFalse(f.fence.deviceOnlyExchangeCurrent(f.owner, f.session, f.device))
            assertTrue(f.prepare())
            assertFalse(f.fence.deviceOnlyExchangeCurrent(f.owner, f.session, f.device))
            assertTrue(f.complete())
            assertTrue(f.fence.deviceOnlyExchangeCurrent(f.owner, f.session, f.device))
            val twin = RpcApplicationSession(f.session.peer, f.session.principal, f.session.sessionId, 60.seconds)
            try {
                assertFalse(f.fence.deviceOnlyExchangeCurrent(f.owner, twin, f.device))
                assertFalse(f.fence.deviceOnlyExchangeCurrent(Any(), f.session, f.device))
                assertFalse(f.fence.deviceOnlyExchangeCurrent(f.owner, f.session, f.device.copy(deviceId = id(8))))
                assertFalse(f.fence.deviceOnlyExchangeCurrent(f.owner, f.session, f.device.copy(admissionRevision = Revision(2))))
                assertTrue(f.fence.prepareAuthenticationExchangeResult(f.owner, f.session, f.device, f.identity))
                assertFalse(f.fence.deviceOnlyExchangeCurrent(f.owner, f.session, f.device))
                assertTrue(f.fence.completeAuthenticationExchangeResult(f.owner, f.session, f.device, f.identity))
                assertFalse(f.fence.deviceOnlyExchangeCurrent(f.owner, f.session, f.device))
                assertNull(f.fence.captureResourceSession(f.owner, f.session))
            } finally { twin.revoke() }
        }
    }

    @Test fun immutableOriginalDeviceScopeCannotSurviveMatchingWithdrawalOrHostRetirement() = runTest {
        for (all in listOf(false, true)) fixture { f ->
            assertTrue(f.prepare()); assertTrue(f.complete())
            assertTrue(f.fence.deviceOnlyExchangeCurrent(f.owner, f.session, f.device))
            if (all) f.fence.deny() else f.fence.beforeProtectedWrite(
                LocalMasterRpcSessionInvalidation.Admission(LocalDeviceAdmission(f.device.scope,
                    f.device.deviceId, Revision(2), false, null, null, emptySet())))
            assertFalse(f.fence.deviceOnlyExchangeCurrent(f.owner, f.session, f.device))
            assertFalse(f.prepare()); assertFalse(f.complete())
        }
    }

    private fun cutDuringRead(invalidation: (Fixture) -> LocalMasterRpcSessionInvalidation) = runTest {
        fixture { f ->
            assertTrue(f.prepare())
            val release = CompletableDeferred<Unit>()
            val complete = async(start = CoroutineStart.UNDISPATCHED) { release.await(); f.complete() }
            f.fence.beforeProtectedWrite(invalidation(f)); release.complete(Unit)
            assertFalse(complete.await()); assertFalse(f.session.isActive)
            assertFalse(f.prepare()); assertFalse(f.complete())
        }
    }

    private class Fixture {
        val owner = Any()
        val fence = LocalMasterRpcSessionFence().also { it.bind(owner) }
        val device = LocalMasterRpcExchangeDeviceIdentity(BranchScope(id(1), id(2), id(3)), id(4), Revision(1))
        val identity = LocalMasterRpcSessionIdentity(device.scope, id(5), device.deviceId, id(6),
            SessionGeneration(1), device.admissionRevision, AuthenticationPurpose.INTERACTIVE)
        val session = RpcApplicationSession(PeerFingerprint("p2f1-" + "a".repeat(52)), "device-exchange", id(7).value, 60.seconds)
        suspend fun prepare() = fence.prepareAuthenticationExchangeSession(owner, session, device)
        suspend fun complete() = fence.completeAuthenticationExchangeSession(owner, session, device)
    }
    private companion object {
        fun id(n: Int) = DomainId("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))
        suspend fun fixture(work: suspend (Fixture) -> Unit) {
            val f = Fixture()
            try { work(f) } finally { f.session.revoke() }
        }
    }
}
