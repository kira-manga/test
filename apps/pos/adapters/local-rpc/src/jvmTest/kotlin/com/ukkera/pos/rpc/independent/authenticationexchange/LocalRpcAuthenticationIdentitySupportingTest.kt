package com.ukkera.pos.rpc.independent.authenticationexchange

import com.ukkera.pos.rpc.LocalRpcAuthenticationAction
import com.ukkera.pos.rpc.LocalRpcAuthenticationResponse
import com.ukkera.pos.rpc.LocalRpcAuthenticationResponseSerializer
import com.ukkera.pos.rpc.LocalRpcIdentityBinding
import com.ukkera.pos.rpc.LocalRpcPeerProvenance
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.CentralGatewayCredentials
import com.ukkera.pos.security.DeviceGatewayCredentials
import com.ukkera.pos.security.LocalAuthenticatedPeer
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalSessionTokens
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.PrincipalVerifier
import com.ukkera.pos.security.ServiceGatewayCredentials
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.TrustedPrincipalFactory
import com.ukkera.pos.security.VerifiedCentralIdentity
import com.ukkera.pos.security.VerifiedDeviceIdentity
import com.ukkera.pos.security.VerifiedLocalIdentity
import com.ukkera.pos.security.VerifiedServiceIdentity
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerId
import dev.p2pkit.core.PeerIdentity
import dev.p2pkit.rpc.RpcAuthorizationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Frozen REMP-C04/C05/C06/C07 shared identity-only slices. The actual immutable binding and
 * official SDK application session are used, but principal/native observations are controlled
 * and the compatibility descriptor overload captures NO RpcPhysicalSession capability.
 * No native approval, physical/Host/private-output gate or durable issuance is established.
 */
class LocalRpcAuthenticationIdentitySupportingTest {
    @Test
    fun rempC04BindingSourcesReceiveDisposableCopiesAndCallerCredentialsRemainIntact() = runTest(timeout = 5.seconds) {
        withIdentityFixture { f ->
            val binding = assertNotNull(f.bind())
            assertNotNull(f.scope(binding))
            f.assertCallerIntact()
            f.verifier.assertReleased()
            assertRemoteRedacted(binding, f.caller, f.credentials, f.native)
        }
    }

    @Test
    fun rempC04FailedBindingSourceReleasesDisposableCopiesWithoutPrivateDiagnostics() = runTest(timeout = 5.seconds) {
        withIdentityFixture { f ->
            f.verifier.failure = IllegalStateException(RemoteEmployeeAuthenticationFrozen.secretMarkers.last())
            assertNull(f.bind())
            f.assertCallerIntact()
            f.verifier.assertReleased()
            assertRemoteRedacted(f.caller, f.credentials, f.native)
        }
    }

    @Test
    fun rempC04CancelledBindingWaiterReleasesCopiesAfterLateControlledSourceCompletion() = runTest(timeout = 5.seconds) {
        withIdentityFixture { f ->
            val barrier = IdentitySourceBarrier()
            f.verifier.barrier = barrier
            val pending = async(start = CoroutineStart.UNDISPATCHED) { f.bind() }
            try {
                assertTrue(barrier.entered.isCompleted)
                pending.cancel()
                barrier.release.complete(Unit)
                pending.join()
                assertFailsWith<CancellationException> { pending.await() }
                f.assertCallerIntact()
                f.verifier.assertReleased()
            } finally {
                barrier.release.complete(Unit)
                withContext(NonCancellable) { pending.cancelAndJoin() }
            }
        }
        // Cancellation after actual authentication admission/transaction remains unbound.
    }

    @Test fun rempC05DifferentEmployeeCannotReplaceOriginalIdentity() = changedIdentity("different_employee") {
        it.copy(actorId = DomainId(RemoteEmployeeAuthenticationFrozen.fixtures.getValue("accounts").jsonObject
            .getValue("account_b").jsonObject.string("actorId")))
    }
    @Test fun rempC05DifferentSessionCannotReplaceOriginalIdentity() = changedIdentity("different_session") {
        it.copy(sessionId = RemoteEmployeeAuthenticationFrozen.successorSessionId())
    }
    @Test fun rempC05DifferentGenerationCannotReplaceOriginalIdentity() = changedIdentity("different_generation") {
        it.copy(sessionGeneration = SessionGeneration(it.sessionGeneration.value + 1))
    }
    @Test fun rempC05DifferentDeviceCannotReplaceOriginalIdentity() = changedIdentity("different_device") {
        it.copy(deviceId = DomainId(RemoteEmployeeAuthenticationFrozen.fixtures.getValue("devices").jsonObject.string("device_b")))
    }
    @Test fun rempC05DifferentBranchCannotReplaceOriginalIdentity() = changedIdentity("different_branch") {
        it.copy(scope = it.scope.copy(branchId = DomainId(RemoteEmployeeAuthenticationFrozen.fixtures.getValue("accounts").jsonObject
            .getValue("other_branch_account").jsonObject.string("branchId"))))
    }
    @Test fun rempC05DifferentAdmissionRevisionCannotReplaceOriginalIdentity() = changedIdentity("different_admission_revision") {
        it.copy(admissionRevision = Revision(it.admissionRevision.value + 1))
    }
    @Test fun rempC05WrongPurposeCannotReplaceInteractiveOriginalIdentity() = changedIdentity("wrong_purpose") {
        it.copy(purpose = AuthenticationPurpose.PRIVILEGED_APPROVAL)
    }

    @Test
    fun rempC05FreshControlledPrincipalRefusalProducesNoOriginalScope() = runTest(timeout = 5.seconds) {
        withIdentityFixture { f ->
            val binding = assertNotNull(f.bind())
            val original = assertNotNull(f.scope(binding))
            f.verifier.identity = null
            assertNull(f.scope(binding))
            assertOriginalMetadata(original)
            f.assertCallerIntact()
            f.verifier.assertReleased()
        }
        // Source-null propagation only: actual missing/invalid/revoked credentials, disabled
        // account, expiry and retired Master lifetime require protected production state.
    }

    @Test
    fun rempC05MissingControlledPeerObservationProducesNoOriginalScope() = runTest(timeout = 5.seconds) {
        withIdentityFixture { f ->
            val binding = assertNotNull(f.bind())
            f.provenance.current = null
            assertNull(f.scope(binding))
            f.assertCallerIntact()
            f.verifier.assertReleased()
        }
    }

    @Test
    fun rempC05SameKeyGenerationSuccessorObservationCannotReplaceOriginalLifetime() = runTest(timeout = 5.seconds) {
        withIdentityFixture { f ->
            val binding = assertNotNull(f.bind())
            val original = assertNotNull(f.scope(binding))
            f.provenance.current = f.successorNative()
            assertNull(f.scope(binding))
            assertOriginalMetadata(original)
            f.assertCallerIntact()
            f.verifier.assertReleased()
        }
        // Different controlled connection observations, not fabricated RpcPhysicalSession tokens.
    }

    @Test
    fun rempC05ExpiredControlledNativeObservationRefusesBeforeBindingEvenWithFiniteSdkCap() = runTest(timeout = 5.seconds) {
        withIdentityFixture { f ->
            f.lifetime.retire()
            assertNull(f.bind())
            assertTrue(f.verifier.calls.isEmpty())
            f.assertCallerIntact()
        }
    }

    @Test
    fun rempC05TrustedPositiveFiniteSdkCapIsEligibleOnlyForLegacyIdentityMetadata() = runTest(timeout = 5.seconds) {
        withIdentityFixture { f ->
            val binding = assertNotNull(f.bind())
            assertOriginalMetadata(assertNotNull(f.scope(binding)))
            assertNull(binding.physicalClosure, "Descriptor-only binding manufactured physical cleanup evidence")
            f.verifier.assertReleased()
        }
        // Positive native/physical/employee authority is not supplied by this fixture.
    }

    @Test fun rempC05ZeroSdkLifetimeRefusesBeforeSources() = refusedLifetime("zero", Duration.ZERO)
    @Test fun rempC05InfiniteSdkLifetimeRefusesBeforeSources() = refusedLifetime("infinite_or_inherited_default", Duration.INFINITE)

    @Test
    fun rempC06OldImmutableBindingNeverAdoptsScriptedRenewalSession() = runTest(timeout = 5.seconds) {
        withIdentityFixture { f ->
            val oldBinding = assertNotNull(f.bind())
            val oldScope = assertNotNull(f.scope(oldBinding))
            f.verifier.identity = RemoteEmployeeAuthenticationFrozen.verifiedIdentity().copy(
                sessionId = RemoteEmployeeAuthenticationFrozen.successorSessionId())
            assertNull(f.scope(oldBinding))
            assertOriginalMetadata(oldScope)
            f.assertCallerIntact()
            f.verifier.assertReleased()
        }
    }

    @Test
    fun rempC06SeparateFreshControlledBindingCanUseSameObservationWithoutAdoption() = runTest(timeout = 5.seconds) {
        withIdentityFixture { f ->
            val oldBinding = assertNotNull(f.bind())
            val oldScope = assertNotNull(f.scope(oldBinding))
            val successorBytes = RemoteEmployeeAuthenticationFrozen.successorAccess()
            val successorCaller = OpaqueCredential(successorBytes)
            try {
                f.verifier.rememberCaller(successorCaller, successorBytes)
                f.verifier.expected = successorBytes
                f.verifier.identity = RemoteEmployeeAuthenticationFrozen.verifiedIdentity().copy(
                    sessionId = RemoteEmployeeAuthenticationFrozen.successorSessionId())
                val successorBinding = assertNotNull(f.bind(LocalGatewayCredentials(f.native.connectionId, successorCaller)))
                val successorScope = assertNotNull(f.scope(successorBinding))
                assertNotSame(oldBinding, successorBinding)
                assertNotSame(oldScope.session, successorScope.session)
                assertEquals(oldScope.session.peer, successorScope.session.peer)
                assertEquals(RemoteEmployeeAuthenticationFrozen.successorSessionId().value, successorScope.session.sessionId)
                assertNull(f.scope(oldBinding))
                assertOriginalMetadata(oldScope)
                assertTrue(successorCaller.useBytes { it.contentEquals(successorBytes) }, "Fresh caller credential changed")
                f.verifier.assertReleased()
            } finally { successorCaller.destroy(); successorBytes.fill(0) }
        }
        // Same exact controlled peer observation only. Real successor qualification on the
        // same still-live RpcPhysicalSession, actual rotation/work lineage and no competing
        // active service session remain unbound.
    }

    @Test
    fun rempC06RenewalCodecPreservesChangedPairIdentityAndOriginalMasterDeadlineData() {
        val oldView = RemoteEmployeeAuthenticationFrozen.view()
        val successorView = RemoteEmployeeAuthenticationFrozen.view(RemoteEmployeeAuthenticationFrozen.successorSessionId())
        val oldAccess = RemoteEmployeeAuthenticationFrozen.originalAccess()
        val oldRefresh = RemoteEmployeeAuthenticationFrozen.originalRefresh()
        val access = RemoteEmployeeAuthenticationFrozen.successorAccess()
        val refresh = RemoteEmployeeAuthenticationFrozen.successorRefresh()
        val tokens = LocalSessionTokens(successorView, OpaqueCredential(access), OpaqueCredential(refresh))
        var response: LocalRpcAuthenticationResponse? = null
        var decoded: LocalRpcAuthenticationResponse? = null
        var received: LocalSessionTokens? = null
        try {
            assertFalse(access.contentEquals(oldAccess))
            assertFalse(refresh.contentEquals(oldRefresh))
            val outgoing = LocalRpcAuthenticationResponse.capture(LocalRpcAuthenticationAction.REFRESH,
                LocalAuthenticationResult.Authenticated(tokens)).also { response = it }
            val wire = Json.encodeToJsonElement(LocalRpcAuthenticationResponseSerializer, outgoing)
            val reply = Json.decodeFromJsonElement(LocalRpcAuthenticationResponseSerializer, wire).also { decoded = it }
            val result = assertIs<LocalAuthenticationResult.Authenticated>(reply.authenticationResult()).tokens.also { received = it }
            assertRemoteView(successorView, result.view)
            assertFalse(result.view.sessionId == oldView.sessionId)
            assertEquals(oldView.actorId, result.view.actorId)
            assertEquals(oldView.deviceId, result.view.deviceId)
            assertEquals(oldView.scope, result.view.scope)
            assertEquals(oldView.workExpiresAt, result.view.workExpiresAt)
            assertEquals(RemoteEmployeeAuthenticationFrozen.renewal.number("controlled_original_work_deadline_elapsed_ms"),
                result.view.workExpiresAt.elapsedMillis)
            assertFalse(result.view.workExpiresAt.elapsedMillis == RemoteEmployeeAuthenticationFrozen.renewal.number("unrelated_client_clock_ms"))
            assertTrue(result.access.useBytes { it.contentEquals(access) }, "Renewal access carrier changed")
            assertTrue(result.refresh.useBytes { it.contentEquals(refresh) }, "Renewal refresh carrier changed")
        } finally {
            tokens.destroy(); received?.destroy(); response?.destroy(); decoded?.destroy()
            oldAccess.fill(0); oldRefresh.fill(0); access.fill(0); refresh.fill(0)
        }
        // Scripted issuance DATA only: no actual renewal transaction or qualified clock is asserted.
    }

    @Test
    fun rempC07RepeatedRetirementRevokesExactOriginalSdkSessionNotSameKeySuccessor() = runTest(timeout = 5.seconds) {
        withIdentityFixture { f ->
            val originalBinding = assertNotNull(f.bind())
            val originalScope = assertNotNull(f.scope(originalBinding))
            val successorNative = f.successorNative()
            f.provenance.current = successorNative
            f.verifier.identity = RemoteEmployeeAuthenticationFrozen.verifiedIdentity().copy(
                sessionId = RemoteEmployeeAuthenticationFrozen.successorSessionId())
            val successorBinding = assertNotNull(f.bind(LocalGatewayCredentials(successorNative.connectionId, f.caller)))
            val successorScope = assertNotNull(f.scope(successorBinding))
            assertNotSame(originalScope.session, successorScope.session)
            assertEquals(originalScope.session.peer, successorScope.session.peer)
            originalBinding.retire()
            originalBinding.retire()
            val sourceCount = f.verifier.calls.size
            assertNull(f.scope(originalBinding))
            assertEquals(sourceCount, f.verifier.calls.size, "Retired original started a fresh verification")
            assertFalse(originalScope.session.isActive)
            assertTrue(successorScope.session.isActive)
            assertNull(originalBinding.physicalClosure)
            f.assertCallerIntact()
            f.verifier.assertReleased()
        }
        // Exact SDK application-session target only; physical_a close/physical_b preservation
        // and release-before-suspended-native-cleanup ordering remain unbound.
    }

    @Test
    fun rempC07CancelledRetirementCallerStillCompletesOwnedSdkRevocation() = runTest(timeout = 5.seconds) {
        withIdentityFixture { f ->
            val binding = assertNotNull(f.bind())
            val originalScope = assertNotNull(f.scope(binding))
            var cancellationPropagated = false
            val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                try { binding.retire() }
                catch (_: CancellationException) { cancellationPropagated = true }
            }
            caller.join()
            assertTrue(cancellationPropagated)
            assertTrue(caller.isCancelled)
            assertFalse(originalScope.session.isActive)
            assertNull(f.scope(binding))
            f.assertCallerIntact()
            f.verifier.assertReleased()
        }
        // Cancelled at entry; there is no public suspended SDK/physical close seam here.
    }

    @Test
    fun rempC07RetirementWinningBeforeLatePrincipalPublicationPreventsOriginalScope() = runTest(timeout = 5.seconds) {
        withIdentityFixture { f ->
            val binding = assertNotNull(f.bind())
            val originalScope = assertNotNull(f.scope(binding))
            val barrier = IdentitySourceBarrier()
            f.verifier.barrier = barrier
            val pending = async(start = CoroutineStart.UNDISPATCHED) { f.scope(binding) }
            try {
                assertTrue(barrier.entered.isCompleted)
                binding.retire()
                assertFalse(originalScope.session.isActive)
                barrier.release.complete(Unit)
                assertNull(pending.await())
                f.assertCallerIntact()
                f.verifier.assertReleased()
            } finally {
                barrier.release.complete(Unit)
                withContext(NonCancellable) { pending.cancelAndJoin() }
            }
        }
        // Real helper publication cut, not a private RPC output commitment/receipt race.
    }

    private fun changedIdentity(variant: String, change: (VerifiedLocalIdentity) -> VerifiedLocalIdentity) = runTest(timeout = 5.seconds) {
        assertTrue(RemoteEmployeeAuthenticationFrozen.fixtures.getValue("identity_refusals").jsonArray
            .any { it.jsonPrimitive.content == variant })
        withIdentityFixture { f ->
            val binding = assertNotNull(f.bind())
            val originalScope = assertNotNull(f.scope(binding))
            f.verifier.identity = change(RemoteEmployeeAuthenticationFrozen.verifiedIdentity())
            assertNull(f.scope(binding))
            assertOriginalMetadata(originalScope)
            f.assertCallerIntact()
            f.verifier.assertReleased()
        }
    }

    private fun refusedLifetime(variant: String, duration: Duration) = runTest(timeout = 5.seconds) {
        assertTrue(RemoteEmployeeAuthenticationFrozen.fixtures.getValue("lifetime_variants").jsonArray
            .any { it.jsonObject.string("id") == variant && it.jsonObject.string("expected") == "refuse" })
        withIdentityFixture { f ->
            assertNull(f.bind(duration = duration))
            assertTrue(f.verifier.calls.isEmpty())
            f.assertCallerIntact()
        }
    }
}

private class ControlledIdentityLifetime {
    private var retired = false
    fun retire() { retired = true }
    fun current(): Boolean = !retired
}

private class IdentitySourceBarrier {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    suspend fun pause() {
        entered.complete(Unit)
        withContext(NonCancellable) { release.await() }
    }
}

private class ControlledIdentitySource(private val caller: OpaqueCredential, private val callerBytes: ByteArray) : PrincipalVerifier {
    var identity: VerifiedLocalIdentity? = RemoteEmployeeAuthenticationFrozen.verifiedIdentity()
    var expected: ByteArray = callerBytes
    var failure: Throwable? = null
    var barrier: IdentitySourceBarrier? = null
    val calls = mutableListOf<LocalGatewayCredentials>()
    private val borrows = mutableListOf<ByteArray>()
    private val callerObjects = mutableListOf(caller)
    private val callerBuffers = mutableListOf(callerBytes)

    fun rememberCaller(value: OpaqueCredential, bytes: ByteArray) {
        callerObjects += value
        callerBuffers += bytes
    }

    override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity? {
        for (value in callerObjects) assertNotSame(value, credentials.bearer, "Source received a caller-owned credential object")
        val matches = credentials.bearer.useBytes {
            for (buffer in callerBuffers) assertNotSame(buffer, it, "Source received a caller-owned mutable buffer")
            borrows += it
            it.contentEquals(expected)
        }
        calls += credentials
        barrier?.pause()
        failure?.let { throw it }
        return identity.takeIf { matches }
    }
    override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
    override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
    override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null

    fun assertReleased() {
        assertTrue(calls.isNotEmpty())
        for (call in calls) assertRemoteCredentialDestroyed(call.bearer)
        assertTrue(borrows.all { buffer -> buffer.all { it == 0.toByte() } }, "Source-disposable borrow was not cleared")
    }
}

private class ControlledIdentityProvenance(var current: LocalAuthenticatedPeer?) : LocalRpcPeerProvenance {
    override suspend fun capture(peer: PeerIdentity): LocalAuthenticatedPeer? = current
}

private class AuthenticationIdentityFixture {
    val bytes = RemoteEmployeeAuthenticationFrozen.originalAccess()
    val caller = OpaqueCredential(bytes)
    val lifetime = ControlledIdentityLifetime()
    // Canonical descriptor carrier for the freeze's shared native-peer-key-a symbol only.
    private val fingerprint = PeerFingerprint("p2f1-" + "a".repeat(52))
    val peer = PeerIdentity(PeerId(RemoteEmployeeAuthenticationFrozen.physical.getValue("physical_a").jsonObject.string("peer_symbol")), fingerprint)
    val native = LocalAuthenticatedPeer(RemoteEmployeeAuthenticationFrozen.physicalId("physical_a"),
        RemoteEmployeeAuthenticationFrozen.scope, RemoteEmployeeAuthenticationFrozen.id("deviceId"),
        "synthetic-key-a", fingerprint.value, lifetime::current)
    val provenance = ControlledIdentityProvenance(native)
    val credentials = LocalGatewayCredentials(native.connectionId, caller)
    val verifier = ControlledIdentitySource(caller, bytes)
    private val factory = TrustedPrincipalFactory(verifier)
    private val bindings = mutableListOf<LocalRpcIdentityBinding>()

    suspend fun bind(credentials: LocalGatewayCredentials = this.credentials, duration: Duration = finiteLifetime()): LocalRpcIdentityBinding? =
        LocalRpcIdentityBinding.bind(peer, credentials, factory, provenance, duration).also { if (it != null) bindings += it }
    suspend fun scope(binding: LocalRpcIdentityBinding): RpcAuthorizationScope? = binding.authorizationScope(peer, "supporting-original-identity")
    fun successorNative(): LocalAuthenticatedPeer = LocalAuthenticatedPeer(RemoteEmployeeAuthenticationFrozen.physicalId("physical_b"),
        native.scope, native.deviceId, native.publicKeyIdentifier, native.publicFingerprint) { true }
    fun assertCallerIntact() {
        val expected = RemoteEmployeeAuthenticationFrozen.originalAccess()
        try {
            assertTrue(bytes.contentEquals(expected), "Caller-owned mutable bytes changed")
            assertTrue(caller.useBytes { it.contentEquals(expected) }, "Caller-owned credential changed")
        } finally { expected.fill(0) }
    }
    suspend fun close() = withContext(NonCancellable) {
        try { for (binding in bindings) binding.retire() }
        finally { caller.destroy(); bytes.fill(0) }
    }
}

private fun finiteLifetime(): Duration = RemoteEmployeeAuthenticationFrozen.fixtures.getValue("lifetime_variants").jsonArray
    .map { it.jsonObject }.single { it.string("id") == "trusted_finite_positive" }.number("sdk_lifetime_ms").milliseconds

private suspend fun withIdentityFixture(block: suspend (AuthenticationIdentityFixture) -> Unit) {
    val fixture = AuthenticationIdentityFixture()
    try { block(fixture) } finally { fixture.close() }
}

private fun assertOriginalMetadata(scope: RpcAuthorizationScope) {
    assertEquals(RemoteEmployeeAuthenticationFrozen.id("actorId").value, scope.session.principal)
    assertEquals(RemoteEmployeeAuthenticationFrozen.id("sessionId").value, scope.session.sessionId)
    assertEquals("supporting-original-identity", scope.resource)
}
