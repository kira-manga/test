@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.rpc.independent.foodfinancialrpcv1

import com.ukkera.pos.rpc.LocalRpcIdentityBinding
import com.ukkera.pos.rpc.LocalRpcPhysicalPeerProvenance
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CentralGatewayCredentials
import com.ukkera.pos.security.DeviceGatewayCredentials
import com.ukkera.pos.security.LocalAuthenticatedPeer
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.PrincipalVerifier
import com.ukkera.pos.security.ServiceGatewayCredentials
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.TrustedPrincipalFactory
import com.ukkera.pos.security.VerifiedCentralIdentity
import com.ukkera.pos.security.VerifiedDeviceIdentity
import com.ukkera.pos.security.VerifiedLocalIdentity
import com.ukkera.pos.security.VerifiedServiceIdentity
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.ConnectionState
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerId
import dev.p2pkit.core.PeerIdentity
import dev.p2pkit.rpc.RpcAuthenticationContext
import dev.p2pkit.rpc.RpcAuthorizationContext
import dev.p2pkit.rpc.RpcAuthorizationOperation
import dev.p2pkit.rpc.RpcAuthorizationScope
import dev.p2pkit.rpc.RpcCallContext
import dev.p2pkit.rpc.RpcPhysicalSession
import dev.p2pkit.rpc.RpcPhysicalSessionClosure
import dev.p2pkit.rpc.RpcRequestId
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Synthetic identity/callback DATA only. No registered device, financial grant or native source is proved. */
class FoodFinancialRpcV1IdentitySupportingTest {
    @Test
    fun ffrc018And021CapturedScopesRequireCurrentControlledOriginalIdentity() = runTest(timeout = 15.seconds) {
        val variants: List<Pair<String, (ControlledFoodIdentityFixture) -> Unit>> = listOf(
            "missing-expired-revoked-or-disabled" to { it.verifier.current = null },
            "different-original-actor" to { it.verifier.current = it.original.copy(actorId = FoodFinancialRpcV1Frozen.id("B")) },
            "different-original-session" to { it.verifier.current = it.original.copy(sessionId = FoodFinancialRpcV1Frozen.id("S2")) },
            "different-device" to { it.verifier.current = it.original.copy(deviceId = FoodFinancialRpcV1Frozen.id("D2")) },
            "wrong-branch" to { it.verifier.current = it.original.copy(scope = it.scope.copy(branchId = FoodFinancialRpcV1Frozen.id("R2"))) },
            "wrong-company" to { it.verifier.current = it.original.copy(scope = it.scope.copy(companyId = FoodFinancialRpcV1Frozen.id("OTHER_COMPANY"))) },
            "wrong-organization" to { it.verifier.current = it.original.copy(scope = it.scope.copy(organizationId = FoodFinancialRpcV1Frozen.id("OTHER_ORGANIZATION"))) },
            "retired-device-source" to { it.peerCurrent = false },
            "missing-peer-source" to { it.peerAvailable = false },
        )
        for ((variant, change) in variants) withFoodIdentity { fixture ->
            val binding = assertNotNull(fixture.bind())
            val captured = assertNotNull(binding.authorizationScope(fixture.authentication(), "pos.food-cash-financial.read"))
            assertEquals(FoodFinancialRpcV1Frozen.id("A").value, captured.session.principal)
            assertNotNull(binding.currentOriginalPrincipal(fixture.handler(captured)))
            change(fixture)
            assertNull(binding.authorizationScope(fixture.authentication(), "pos.food-cash-financial.read"), variant)
            for (procedure in listOf("pos.food-cash-financial.read", "pos.food-cash-financial.execute", "pos.food-cash-financial.lookup")) {
                for (operation in listOf(RpcAuthorizationOperation.Execute, RpcAuthorizationOperation.Success, RpcAuthorizationOperation.Status)) {
                    assertNull(binding.currentOriginalPrincipal(fixture.authorization(captured, procedure, operation = operation)), variant)
                }
            }
            assertNull(binding.currentOriginalPrincipal(fixture.handler(captured)), variant)
            assertEquals(FoodFinancialRpcV1Frozen.id("A").value, captured.session.principal)
            fixture.assertSourceCopiesReleased()
            // Null/mismatched source answers are controlled; persisted expiry/revocation/grants are not exercised.
        }
    }

    @Test
    fun ffrc018And021LateControlledRevocationSuppressesPublicationFromAnExistingScope() = runTest(timeout = 15.seconds) {
        withFoodIdentity { fixture ->
            val binding = assertNotNull(fixture.bind())
            val captured = assertNotNull(binding.authorizationScope(fixture.authentication(), "pos.food-cash-financial.lookup"))
            val barrier = FoodIdentityBarrier()
            fixture.verifier.barrier = barrier
            val work = async { binding.currentOriginalPrincipal(fixture.authorization(captured, "pos.food-cash-financial.lookup")) }
            runCurrent()
            assertTrue(barrier.entered.isCompleted)
            fixture.verifier.current = null
            barrier.release.complete(Unit)
            assertNull(work.await())
            assertEquals(FoodFinancialRpcV1Frozen.id("A").value, captured.session.principal)
            fixture.assertSourceCopiesReleased()
            // This proves no identity publication after a controlled denial, not transaction/commit serialization.
        }
    }

    @Test
    fun ffrc012And027EqualDescriptorAndRepeatedGenerationCannotAdoptTheOriginalScope() = runTest(timeout = 15.seconds) {
        withFoodIdentity { fixture ->
            val binding = assertNotNull(fixture.bind())
            val captured = assertNotNull(binding.authorizationScope(fixture.authentication(), "pos.food-cash-financial.lookup"))
            assertSame(fixture.l1.peer, fixture.l2.peer)
            assertEquals(fixture.l1.capability.generation, fixture.l2.capability.generation)
            assertNotSame(fixture.l1.capability, fixture.l2.capability)
            assertNull(binding.authorizationScope(fixture.authentication(fixture.l2.capability), "pos.food-cash-financial.lookup"))
            assertNull(binding.authorizationScope(fixture.l1.peer, "pos.food-cash-financial.lookup"))
            assertNull(binding.currentOriginalPrincipal(fixture.handler()))
            assertNull(binding.currentOriginalPrincipal(fixture.handler(captured, null)))
            assertNull(binding.currentOriginalPrincipal(fixture.handler(captured, fixture.l2.capability)))
            for (procedure in listOf("pos.food-cash-financial.read", "pos.food-cash-financial.execute", "pos.food-cash-financial.lookup")) {
                assertNull(binding.currentOriginalPrincipal(fixture.authorization(captured, procedure,
                    original = fixture.l2.capability, physical = fixture.l1.capability)))
                assertNull(binding.currentOriginalPrincipal(fixture.authorization(captured, procedure,
                    original = fixture.l1.capability, physical = fixture.l2.capability)))
            }
            assertNotNull(binding.currentOriginalPrincipal(fixture.handler(captured)))
            fixture.assertSourceCopiesReleased()
            // No payment source, private result, genuine callback or native Start is constructed.
        }
    }

    @Test
    fun ffrc027CancelledRetirementKeepsTheExactSyntheticCloseTargetAndItsTerminalOutcome() = runTest(timeout = 15.seconds) {
        for (failClose in listOf(false, true)) withFoodIdentity { fixture ->
            val binding = assertNotNull(fixture.bind())
            val captured = assertNotNull(binding.authorizationScope(fixture.authentication(), "pos.food-cash-financial.lookup"))
            val barrier = FoodIdentityBarrier()
            val declared = if (failClose) FoodDeclaredCloseFailure() else null
            fixture.l1.barrier = barrier
            fixture.l1.failure = declared
            var returnedNormally = false
            var cancellationObserved = false
            var callerFailure: Exception? = null
            val caller = launch {
                try { binding.retire(); returnedNormally = true }
                catch (_: CancellationException) { cancellationObserved = true }
                catch (failure: Exception) { callerFailure = failure }
            }
            runCurrent()
            assertTrue(barrier.entered.isCompleted)
            caller.cancel()
            runCurrent()
            assertEquals(RpcPhysicalSessionClosure.Closing, binding.physicalClosure!!.value)
            assertFalse(captured.session.isActive)
            assertNull(binding.authorizationScope(fixture.authentication(), "pos.food-cash-financial.lookup"))
            assertNull(binding.currentOriginalPrincipal(fixture.handler(captured)))
            val observer = async { observeFoodRetirement(binding) }
            runCurrent()
            assertFalse(observer.isCompleted)
            assertFalse(returnedNormally)
            barrier.release.complete(Unit)
            caller.join()
            val observed = observer.await()
            assertTrue(caller.isCancelled)
            assertFalse(returnedNormally)
            if (failClose) {
                assertSame(declared, observed)
                assertTrue(cancellationObserved || callerFailure === declared)
                assertEquals(RpcPhysicalSessionClosure.Incomplete, binding.physicalClosure!!.value)
                assertSame(declared, observeFoodRetirement(binding))
            } else {
                assertNull(observed)
                assertTrue(cancellationObserved)
                assertNull(callerFailure)
                assertEquals(RpcPhysicalSessionClosure.Closed, binding.physicalClosure!!.value)
                assertNull(observeFoodRetirement(binding))
            }
            assertTrue(fixture.l1.closeTargets.isNotEmpty())
            assertTrue(fixture.l1.closeTargets.all { it === fixture.l1 })
            assertTrue(fixture.l2.closeTargets.isEmpty(), "Original retirement targeted the successor")
            assertEquals(RpcPhysicalSessionClosure.Open, fixture.l2.capability.closure.value)
            assertTrue(fixture.l2.capability.isConnected)
            fixture.assertSourceCopiesReleased()
            // Closed/Incomplete here describe an SDK wrapper around a synthetic action, not native closure.
        }
    }
}

private class FoodIdentityBarrier {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    suspend fun hold() { entered.complete(Unit); release.await() }
}

private class FoodDeclaredCloseFailure : RuntimeException("Declared synthetic FOOD close failure"),
    CopyableThrowable<FoodDeclaredCloseFailure> {
    override fun createCopy(): FoodDeclaredCloseFailure? = null
}

/** JVM-public SDK data constructors only; no private SDK access, RPC engine or native transport. */
private object FoodSyntheticSdkData {
    val longType: Class<*> = java.lang.Long.TYPE
    val intType: Class<*> = java.lang.Integer.TYPE
    val boxedLongType: Class<*> = java.lang.Long::class.java
    val suspendFunctionType: Class<*> = Class.forName("kotlin.jvm.functions.Function1")
    private val permitted = setOf(RpcPhysicalSession::class.java, RpcAuthenticationContext::class.java,
        RpcAuthorizationContext::class.java, RpcCallContext::class.java, RpcRequestId::class.java)
    fun <T : Any> create(type: Class<T>, signature: Array<Class<*>>, vararg values: Any?): T {
        require(type in permitted)
        return type.getConstructor(*signature).newInstance(*values)
    }
}

private class FoodSyntheticLifetime(val peer: PeerIdentity) {
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    var barrier: FoodIdentityBarrier? = null
    var failure: FoodDeclaredCloseFailure? = null
    val closeTargets = mutableListOf<FoodSyntheticLifetime>()
    val capability: RpcPhysicalSession
    init {
        val close: suspend () -> Unit = {
            closeTargets.add(this)
            barrier?.hold()
            failure?.let { throw it }
            state.value = ConnectionState.Closed
        }
        capability = FoodSyntheticSdkData.create(RpcPhysicalSession::class.java,
            arrayOf(PeerIdentity::class.java, FoodSyntheticSdkData.longType, StateFlow::class.java,
                FoodSyntheticSdkData.suspendFunctionType), peer, 1L, state, close)
    }
}

private class ControlledFoodVerifier(var current: VerifiedLocalIdentity?) : PrincipalVerifier {
    var barrier: FoodIdentityBarrier? = null
    val borrowed = mutableListOf<ByteArray>()
    val credentials = mutableListOf<OpaqueCredential>()
    override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity? {
        val matches = credentials.connectionId == FoodFinancialRpcV1Frozen.id("L1") && credentials.bearer.useBytes { bytes ->
            borrowed.add(bytes)
            bytes.contentEquals(FoodFinancialRpcV1Frozen.bearer())
        }
        this.credentials.add(credentials.bearer)
        barrier?.hold()
        return if (matches) current else null
    }
    override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
    override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
    override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
}

private class ControlledFoodIdentityFixture {
    val caller = FoodFinancialRpcV1Frozen.bearer()
    val scope = BranchScope(FoodFinancialRpcV1Frozen.id("ORG-R"), FoodFinancialRpcV1Frozen.id("COMPANY-R"), FoodFinancialRpcV1Frozen.id("R1"))
    val original = VerifiedLocalIdentity(FoodFinancialRpcV1Frozen.id("A"), scope,
        FoodFinancialRpcV1Frozen.id("D1"), FoodFinancialRpcV1Frozen.id("S1"), SessionGeneration(1), Revision(1),
        AuthenticationPurpose.INTERACTIVE)
    val peer = PeerIdentity(PeerId("FFRC-SYNTHETIC-D1"), foodSyntheticFingerprint())
    val l1 = FoodSyntheticLifetime(peer)
    val l2 = FoodSyntheticLifetime(peer)
    var peerCurrent = true
    var peerAvailable = true
    private val peerData = LocalAuthenticatedPeer(FoodFinancialRpcV1Frozen.id("L1"), scope, original.deviceId,
        "FFRC-SYNTHETIC-KEY-D1", checkNotNull(peer.fingerprint).value, { peerCurrent })
    val verifier = ControlledFoodVerifier(original)
    private val factory = TrustedPrincipalFactory(verifier)
    private val credentials = LocalGatewayCredentials(FoodFinancialRpcV1Frozen.id("L1"), OpaqueCredential(caller))
    private val bindings = mutableListOf<LocalRpcIdentityBinding>()
    private val provenance = LocalRpcPhysicalPeerProvenance { physical ->
        if (physical === l1.capability && peerAvailable) peerData else null
    }
    fun authentication(physical: RpcPhysicalSession = l1.capability): RpcAuthenticationContext =
        FoodSyntheticSdkData.create(RpcAuthenticationContext::class.java,
            arrayOf(PeerIdentity::class.java, RpcPhysicalSession::class.java), physical.peer, physical)
    private fun requestId(): RpcRequestId = FoodSyntheticSdkData.create(RpcRequestId::class.java,
        arrayOf(String::class.java), "FFRC-SYNTHETIC-REQUEST")
    fun authorization(scope: RpcAuthorizationScope, procedure: String,
        original: RpcPhysicalSession = l1.capability, physical: RpcPhysicalSession = l1.capability,
        operation: RpcAuthorizationOperation = RpcAuthorizationOperation.Success): RpcAuthorizationContext =
        FoodSyntheticSdkData.create(RpcAuthorizationContext::class.java,
            arrayOf(PeerIdentity::class.java, RpcRequestId::class.java, String::class.java, FoodSyntheticSdkData.intType,
                String::class.java, RpcAuthorizationScope::class.java, RpcAuthorizationOperation::class.java,
                RpcPhysicalSession::class.java, RpcPhysicalSession::class.java),
            peer, requestId(), procedure, 1, "FFRC-SYNTHETIC-HOST", scope, operation, original, physical)
    fun handler(scope: RpcAuthorizationScope? = null, physical: RpcPhysicalSession? = l1.capability): RpcCallContext {
        val prefixTypes = arrayOf<Class<*>>(PeerIdentity::class.java, RpcRequestId::class.java, FoodSyntheticSdkData.longType,
            String::class.java, FoodSyntheticSdkData.boxedLongType)
        val prefixValues = arrayOf(peer, requestId(), 1_000L, "FFRC-SYNTHETIC-HOST", null)
        return if (scope == null) FoodSyntheticSdkData.create(RpcCallContext::class.java, prefixTypes, *prefixValues)
            else if (physical == null) FoodSyntheticSdkData.create(RpcCallContext::class.java,
                prefixTypes + RpcAuthorizationScope::class.java, *prefixValues, scope)
            else FoodSyntheticSdkData.create(RpcCallContext::class.java,
                prefixTypes + arrayOf(RpcAuthorizationScope::class.java, RpcPhysicalSession::class.java), *prefixValues, scope, physical)
    }
    suspend fun bind(): LocalRpcIdentityBinding? = LocalRpcIdentityBinding.bind(authentication(), credentials, factory,
        provenance, 60.seconds).also { if (it != null) bindings.add(it) }
    fun assertSourceCopiesReleased() {
        assertTrue(verifier.credentials.isNotEmpty())
        verifier.credentials.forEach { bearer ->
            assertFailsWith<IllegalStateException>("Verifier disposable credential remains usable") { bearer.useBytes {} }
        }
        verifier.borrowed.forEach { assertFoodCleared(it, "Verifier disposable borrow") }
        assertFoodBearer(FoodFinancialRpcV1Frozen.bearer(), caller, "Caller buffer preserved")
        credentials.bearer.useBytes { assertFoodBearer(FoodFinancialRpcV1Frozen.bearer(), it, "Caller credential preserved") }
    }
    suspend fun close() = withContext(NonCancellable) {
        l1.barrier?.release?.complete(Unit)
        l2.barrier?.release?.complete(Unit)
        verifier.barrier?.release?.complete(Unit)
        try {
            bindings.forEach { binding ->
                try { binding.retire() }
                catch (failure: Exception) { if (failure !== l1.failure) throw failure }
            }
        } finally { credentials.bearer.destroy(); caller.fill(0) }
    }
}

private suspend fun withFoodIdentity(block: suspend (ControlledFoodIdentityFixture) -> Unit) {
    val fixture = ControlledFoodIdentityFixture()
    try { block(fixture) } finally { fixture.close() }
}

private suspend fun observeFoodRetirement(binding: LocalRpcIdentityBinding): Exception? =
    try { binding.retire(); null } catch (failure: Exception) { failure }

/** Deterministic descriptor syntax only; a matching digest is never authenticated key custody. */
private fun foodSyntheticFingerprint(): PeerFingerprint {
    val digest = MessageDigest.getInstance("SHA-256").digest("FFRC-SYNTHETIC-KEY-D1".encodeToByteArray())
    val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
    var accumulator = 0
    var bits = 0
    val encoded = StringBuilder()
    digest.forEach { byte ->
        accumulator = (accumulator shl 8) or (byte.toInt() and 255)
        bits += 8
        while (bits >= 5) { bits -= 5; encoded.append(alphabet[(accumulator ushr bits) and 31]) }
    }
    if (bits > 0) encoded.append(alphabet[(accumulator shl (5 - bits)) and 31])
    return PeerFingerprint.parse("p2f1-" + encoded)
}
