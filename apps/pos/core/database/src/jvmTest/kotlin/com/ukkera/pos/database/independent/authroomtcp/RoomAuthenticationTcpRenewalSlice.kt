package com.ukkera.pos.database

import com.ukkera.pos.rpc.LocalRpcAuthenticationError
import com.ukkera.pos.rpc.LocalRpcIdentityBinding
import com.ukkera.pos.rpc.LocalRpcPhysicalPeerProvenance
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** REMP-R05 executor, not a new test design or production ingress. Actual Room
 * principal factory, public binding and SDK physical callbacks run together.
 * The fixture peer -> SDK association and clock are explicitly synthetic DATA.
 * No native registry/registration or original-owner capability is constructed. */
internal class RoomAuthenticationTcpRenewalSlice(repository: Path) : LocalAuthenticationEnvironment {
    private val inputs: JsonObject
    private val issuer = authV1EphemeralId()
    private val moment = AtomicReference(MonotonicMoment(authV1EphemeralId(), 0))
    private val workDeadline: Long
    val tokenPolicy: LocalTokenPolicy
    private class Original(val credentials: LocalGatewayCredentials, val view: LocalSessionView,
        val facts: AuthV1AccountFacts) {
        var binding: LocalRpcIdentityBinding? = null
        var scope: RpcAuthorizationScope? = null
    }
    private val originals = linkedMapOf<String, Original>()
    private var physical: RpcPhysicalSession? = null
    private var mappedPeer: LocalAuthenticatedPeer? = null
    private var refusedOriginal = 0
    private val servedSlots = mutableSetOf<String>()
    private val procedure = RpcProcedure("test.auth.fixture.bound-current", 1, String.serializer(),
        String.serializer(), LocalRpcAuthenticationError.serializer())

    init {
        val directory = repository.resolve("qa/pos/designs/remote-employee-authentication")
        val bytes = Files.readAllBytes(directory.resolve("fixtures.json"))
        assertEquals("922509c2936525d54f02e541bd9d521d825ab5451fd75dd5e00dec0ea5f7c9ee", authV1Sha256(bytes))
        assertEquals("11733db43b1db894b73ded18006f0a6cdb86a653f0e94917826c16efc80ea46b",
            authV1Sha256(Files.readAllBytes(directory.resolve("cases.md"))))
        inputs = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("renewal_observations").jsonObject
        workDeadline = inputs.getValue("controlled_original_work_deadline_elapsed_ms").jsonPrimitive.long
        tokenPolicy = LocalTokenPolicy(workDeadline, workDeadline)
    }

    override fun observe(): LocalAuthenticationObservation = LocalAuthenticationObservation(issuer, moment.get())
    fun advanceForRefresh() { moment.set(moment.get().copy(elapsedMillis = workDeadline / 2)) }

    /** Called before the production serializer consumes/wipes the actual issued
     * response. Only a private access copy is retained for the binding executor. */
    suspend fun remember(result: LocalAuthenticationResult, fixture: AuthV1Fixture) {
        if (result !is LocalAuthenticationResult.Authenticated) return
        val slot = if (originals.isEmpty()) "original" else "successor"
        check(slot !in originals)
        val tokens = result.tokens
        val bearer = tokens.access.useBytes(::OpaqueCredential)
        try {
            val facts = fixture.snapshot("renewal-$slot").accounts.getValue("A")
            originals[slot] = Original(LocalGatewayCredentials(fixture.connections.getValue("A"), bearer), tokens.view, facts)
        } catch (failure: Throwable) { bearer.destroy(); throw failure }
    }

    fun install(configuration: RpcHostConfiguration, fixture: AuthV1Fixture,
        fingerprint: PeerFingerprint, gate: Mutex) {
        // Read the SAME grouped factory in JVM TEST code only. No replacement
        // verifier, new Room pool, public runtime seam or native capability.
        val field = LocalMasterEndpoint::class.java.getDeclaredField("principals").apply { isAccessible = true }
        val principals = field.get(fixture.endpoint) as TrustedPrincipalFactory
        configuration.registerScopedWithPhysicalSession(procedure,
            authenticate = { context, slot -> gate.withLock {
                val retained = originals[slot]
                if (retained == null || context.peer.fingerprint != fingerprint || !context.physicalSession.isConnected) null
                else {
                    val sdkPhysical = context.physicalSession
                    if (physical == null) {
                        physical = sdkPhysical
                        val native = fixture.captureClientPeer("A")
                        // Controlled association to the fixture's existing protected
                        // admission, not production enrollment/native provenance.
                        mappedPeer = LocalAuthenticatedPeer(native.connectionId, native.scope, native.deviceId,
                            native.publicKeyIdentifier, fingerprint.value) { native.isCurrent() && sdkPhysical.isConnected }
                    }
                    assertSame(physical, sdkPhysical, "Renewal must retain the exact SDK physical session")
                    val provenance = LocalRpcPhysicalPeerProvenance { current ->
                        mappedPeer.takeIf { current === physical && current.isConnected }
                    }
                    val binding = retained.binding ?: LocalRpcIdentityBinding.bind(context, retained.credentials,
                        principals, provenance, 30.seconds)?.also { retained.binding = it }
                    val scope = binding?.authorizationScope(context, "test.auth.fixture.renewal")
                    if (scope == null && slot == "original") refusedOriginal++
                    if (scope != null) {
                        retained.scope?.let { assertSame(it.session, scope.session) }
                        retained.scope = scope
                    }
                    scope
                }
            } },
            authorize = { context -> gate.withLock {
                val retained = originals.values.singleOrNull { it.scope?.session === context.scope.session }
                retained?.binding?.currentOriginalPrincipal(context) != null
            } },
            handler = { context, slot -> gate.withLock {
                val retained = checkNotNull(originals[slot])
                val principal = checkNotNull(retained.binding).currentOriginalPrincipal(context)
                if (principal == null) RpcReply.ApplicationError(LocalRpcAuthenticationError.UNAVAILABLE)
                else {
                    assertEquals(retained.view.sessionId, principal.sessionId)
                    servedSlots.add(slot)
                    RpcReply.Success(principal.sessionId.value)
                }
            } })
    }

    suspend fun assertBoundCurrent(client: RpcClient, slot: String, session: DomainId) {
        val result = assertIs<RpcReply.Success<String>>(client.call(procedure, slot, timeout = 15.seconds,
            retry = RpcRetry.RecoverOnly(maxAttempts = 1)))
        assertEquals(session.value, result.value)
    }

    suspend fun assertOriginalRefused(client: RpcClient) {
        val refusal = assertFailsWith<RpcFailure>("Original binding cannot adopt the successor") {
            client.call(procedure, "original", timeout = 15.seconds,
                retry = RpcRetry.RecoverOnly(maxAttempts = 1))
        }
        assertEquals(RpcFailureKind.Unauthorized, refusal.kind)
        assertEquals(RpcConnectionState.Ready, client.state.value, "Refusal must preserve the same live connection")
    }

    fun assertRotation(original: LocalSessionTokens, successor: LocalSessionTokens) {
        assertNotEquals(original.view.sessionId, successor.view.sessionId)
        original.access.useBytes { before -> successor.access.useBytes { after -> assertFalse(before.contentEquals(after)) } }
        original.refresh.useBytes { before -> successor.refresh.useBytes { after -> assertFalse(before.contentEquals(after)) } }
        assertEquals(original.view.actorId, successor.view.actorId)
        assertEquals(original.view.scope, successor.view.scope)
        assertEquals(original.view.deviceId, successor.view.deviceId)
        assertEquals(original.view.workExpiresAt, successor.view.workExpiresAt)
        assertEquals(workDeadline, successor.view.workExpiresAt.elapsedMillis)
    }

    fun verify(after: AuthV1Snapshot) {
        assertEquals(setOf("original", "successor"), servedSlots)
        assertTrue(refusedOriginal >= 2, "Old binding was actually refused by authentication")
        val old = originals.getValue("original")
        val next = originals.getValue("successor")
        assertNotSame(old.binding, next.binding)
        assertNotSame(checkNotNull(old.scope).session, checkNotNull(next.scope).session)
        assertTrue(checkNotNull(physical).isConnected)
        val account = after.accounts.getValue("A")
        val originalFacts = old.facts.issuances.getValue(old.view.sessionId)
        val retainedFacts = account.issuances.getValue(old.view.sessionId)
        val successorFacts = account.issuances.getValue(next.view.sessionId)
        // Immutable historical issuance/actors are not replaced by rotation.
        assertEquals(originalFacts.json(), retainedFacts.json())
        assertEquals(checkNotNull(originalFacts.access).tokenHash, checkNotNull(retainedFacts.access).tokenHash)
        assertEquals(checkNotNull(originalFacts.refresh).tokenHash, checkNotNull(retainedFacts.refresh).tokenHash)
        val before = checkNotNull(originalFacts.refresh)
        val current = checkNotNull(successorFacts.refresh)
        assertEquals(before.actorId, current.actorId)
        assertEquals(before.deviceId, current.deviceId)
        assertEquals(before.scope, current.scope)
        assertEquals(before.originalSessionId, current.originalSessionId)
        assertEquals(before.workStartedAt, current.workStartedAt)
        assertEquals(before.workLifetimeMillis, current.workLifetimeMillis)
        assertEquals(before.expiresAtElapsedMillis, current.expiresAtElapsedMillis)
        assertEquals(workDeadline, current.expiresAtElapsedMillis)
        assertNotEquals(before.tokenHash, current.tokenHash)
        assertNotEquals(originalFacts.access.tokenHash, checkNotNull(successorFacts.access).tokenHash)
        assertEquals(old.facts.issuances.size + 1, account.issuances.size)
        assertEquals(next.view.sessionId, account.authV1Active().single().sessionId)
        assertEquals(1, after.events.count { it.kind == LocalAuthEventKind.REFRESHED && it.targetId == current.actorId })
    }

    suspend fun dispose() {
        // Host has already closed. Actual exact-session retirement, never a new
        // admission/financial operation, clears each retained credential copy.
        try { originals.values.forEach { it.binding?.retire() } }
        finally { originals.values.forEach { it.credentials.bearer.destroy() }; originals.clear() }
    }
}
