package com.ukkera.pos.rpc.independent.physicalsession

import com.ukkera.pos.rpc.LocalRpcIdentityBinding
import com.ukkera.pos.rpc.LocalRpcPeerProvenance
import com.ukkera.pos.rpc.LocalRpcPhysicalPeerProvenance
import com.ukkera.pos.rpc.independent.identityv1.CallerIdentitySecret
import com.ukkera.pos.rpc.independent.identityv1.ControlledIdentityVerifier
import com.ukkera.pos.rpc.independent.identityv1.ControlledPhysicalLifetime
import com.ukkera.pos.rpc.independent.identityv1.IdentityBarrier
import com.ukkera.pos.rpc.independent.identityv1.IdentityClock
import com.ukkera.pos.rpc.independent.identityv1.IdentityFrozen
import com.ukkera.pos.rpc.independent.identityv1.number
import com.ukkera.pos.rpc.independent.identityv1.string
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAuthenticatedPeer
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.TrustedPrincipalFactory
import com.ukkera.pos.security.VerifiedLocalIdentity
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.ConnectionState
import dev.p2pkit.core.PeerId
import dev.p2pkit.core.PeerIdentity
import dev.p2pkit.rpc.RpcApplicationSession
import dev.p2pkit.rpc.RpcAuthenticationContext
import dev.p2pkit.rpc.RpcAuthorizationContext
import dev.p2pkit.rpc.RpcAuthorizationOperation
import dev.p2pkit.rpc.RpcAuthorizationScope
import dev.p2pkit.rpc.RpcCallContext
import dev.p2pkit.rpc.RpcPhysicalSession
import dev.p2pkit.rpc.RpcRequestId
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Exact frozen input copy. Labels are synthetic coordinates, never authenticated identities. */
internal object PhysicalSessionFrozen {
    val fixtures = Json.parseToJsonElement("""
{
  "schema_version": 1,
  "design_version": "physical-session-v1",
  "status": "FROZEN_NOT_RUN",
  "data_class": "synthetic_local_only",
  "case_ids": [
    "PHS-01", "PHS-02", "PHS-03", "PHS-04", "PHS-05",
    "PHS-06", "PHS-07", "PHS-08", "PHS-09", "PHS-10",
    "PHS-11", "PHS-12", "PHS-13", "PHS-14", "PHS-15"
  ],
  "credential_policy": {
    "values": "No bearer, password, PIN, private key or copied credential is stored here.",
    "execution": "Provision fresh synthetic local credentials in protected storage; expose only ownership/erasure observations, never secret contents.",
    "caller_storage": "A separately caller-owned mutable synthetic credential buffer.",
    "retained_copy": "Private binding-owned copy; nonserializable, redacted, released on failed binding or retirement."
  },
  "original": {
    "organization": "O-PHS-1",
    "company": "C-PHS-1",
    "branch": "B-PHS-1",
    "employee": "E-PHS-A",
    "device": "D-PHS-1",
    "auth_session": "S-A0",
    "pos_session_generation": 3,
    "admission_revision": 11,
    "purpose": "interactive_local_employee",
    "policy_revision": 20,
    "sdk_application_session": "APP-A",
    "sdk_physical_lifetime": "L-A",
    "sdk_callback_context": "X-A",
    "trusted_pos_peer": "PEER-A",
    "peer_fingerprint": "SYNTHETIC-KEY-PHS-1",
    "sdk_generation": 7,
    "sdk_lifetime_seconds": 30,
    "original_work_deadline_seconds": 60,
    "initial_authority": "enabled, unrevoked, unexpired, exact original identity",
    "initial_native_evidence": "qualified currentness and continuity for the exact original lifetime"
  },
  "same_key_successor": {
    "device": "D-PHS-1",
    "branch": "B-PHS-1",
    "peer_fingerprint": "SYNTHETIC-KEY-PHS-1",
    "sdk_generation": 7,
    "sdk_physical_lifetime": "L-B",
    "sdk_callback_context": "X-B",
    "trusted_pos_peer": "PEER-B",
    "sdk_application_session": "APP-B",
    "independence": "A distinct SDK-owned authenticated lifetime, never an alias for L-A.",
    "native_limit": "Obtain only through a genuine supported SDK callback in native/RPC execution. These labels and a controlled seam cannot grant native currentness."
  },
  "session_successors": {
    "renewed_A": {
      "employee": "E-PHS-A",
      "auth_session": "S-A1",
      "device": "D-PHS-1",
      "branch": "B-PHS-1",
      "pos_session_generation": 4,
      "admission_revision": 11,
      "original_work_deadline_seconds": 60,
      "lineage": "Authoritative durable rotation from S-A0; both token/session issuance identities rotate."
    },
    "new_employee_B": {
      "employee": "E-PHS-B",
      "auth_session": "S-B0",
      "device": "D-PHS-1",
      "branch": "B-PHS-1",
      "pos_session_generation": 4,
      "admission_revision": 11,
      "recovery_permission_for_A": "none"
    }
  },
  "binding_refusal_variants": [
    {"id": "no-callback-lifetime", "change": "Actual SDK callback lifetime absent."},
    {"id": "no-trusted-peer", "change": "Trusted native POS peer absent."},
    {"id": "wrong-fingerprint", "change": "Actual authenticated peer fingerprint is SYNTHETIC-KEY-PHS-OTHER."},
    {"id": "mixed-lifetimes-same-key", "change": "L-A combined with peer/context belonging to L-B, despite equal fingerprint and SDK generation."},
    {"id": "descriptor-only", "change": "Matching descriptor/latest-fingerprint result supplied without actual authenticated callback association."},
    {"id": "body-or-discovery-only", "change": "Body IDs/discovery/login labels claim the original context; native evidence absent."}
  ],
  "successor_attempts": [
    "original-bound identity check with X-B",
    "original request execution with X-B",
    "private original result delivery to X-B",
    "original control/status access with X-B"
  ],
  "handler_refusal_variants": [
    "no original scoped login",
    "no actual physical context",
    "descriptor-only legacy/device entry",
    "original scoped login combined with X-B",
    "X-A original operation combined with X-B delivery/control context"
  ],
  "principal_refusal_variants": [
    {"id": "null", "change": "Current principal unavailable/null."},
    {"id": "revoked", "change": "Original session revoked in current authority."},
    {"id": "disabled", "change": "Original employee disabled."},
    {"id": "expired", "change": "Original session expired."},
    {"id": "wrong-purpose", "change": "Credential resolves to a noninteractive service-purpose principal."},
    {"id": "employee", "change": "Employee E-PHS-B returned in place of E-PHS-A."},
    {"id": "session", "change": "Session S-A1 returned in place of S-A0."},
    {"id": "device", "change": "Device D-PHS-2 returned in place of D-PHS-1."},
    {"id": "branch", "change": "Branch B-PHS-2 returned in place of B-PHS-1."},
    {"id": "pos-generation", "change": "POS session generation 4 returned in place of 3."},
    {"id": "admission", "change": "Admission revision 12 returned in place of 11."}
  ],
  "invalid_sdk_bounds": ["unspecified", "zero", "negative", "infinite"],
  "currentness_refusal_variants": [
    "missing native currentness evidence",
    "changed physical peer",
    "changed authenticated lifetime",
    "original native lifetime not current",
    "original native continuity not qualified"
  ],
  "expiry_windows": [
    {
      "id": "native-first",
      "sdk_deadline_seconds": 30,
      "qualified_native_deadline_seconds": 20,
      "work_deadline_seconds": 60,
      "eligible_identity_check_at_seconds": 19,
      "refuse_identity_check_at_seconds": 20,
      "reason": "Native evidence is expired even though the finite SDK bound has not elapsed."
    },
    {
      "id": "sdk-first",
      "sdk_deadline_seconds": 30,
      "qualified_native_deadline_seconds": 60,
      "work_deadline_seconds": 60,
      "eligible_identity_check_at_seconds": 29,
      "refuse_identity_check_at_seconds": 30,
      "reason": "The finite SDK bound has elapsed even if native evidence is otherwise current."
    }
  ],
  "policy_activation": {
    "before_revision": 20,
    "after_revision": 21,
    "resource": "OWNER-PRIVATE-RESOURCE-A",
    "before": "original employee explicitly permitted",
    "after": "current local policy explicitly denies this protected resource action",
    "identity_fields_changed": false,
    "permission_hint": "stale client allow; not authority"
  },
  "session_transition_variants": [
    "successful transactional S-A0 to S-A1 rotation with original work lineage/deadline",
    "lost or ambiguous refresh response; no successful renewal assumption",
    "A logout followed by B login on the same admitted device and late original events"
  ],
  "confirmed_original": {
    "target": "L-A",
    "native_outcome": "Exact original lifetime teardown confirmed by SDK/native evidence.",
    "successor_targeted": false,
    "local_login": "irreversibly unavailable",
    "retained_owned_credentials": 0,
    "original_sdk_application_authority": "revoked"
  },
  "unconfirmed_close_variants": [
    {"id": "terminal-failure", "native_outcome": "Retained terminal exact-A close failure; no A confirmation."},
    {"id": "pending-no-confirmation", "native_outcome": "A remains open/closing or unconfirmed at a bounded observation point."},
    {"id": "successor-only-confirmation", "native_outcome": "B is independently confirmed closed while A remains unconfirmed; B's closure cannot confirm A."}
  ],
  "cleanup_after_caller_cancellation": [
    "Owned exact-A cleanup continues and eventually yields confirmed A closure.",
    "Owned exact-A cleanup continues and retains its actual terminal failure/incomplete outcome."
  ],
  "business_originals": {
    "common": {
      "currency": "EGP",
      "opening_cash_minor": 100000,
      "initial_stock_units": 10,
      "sale_quantity_units": 1,
      "unit_price_minor": 10000,
      "fixture_tax_minor": 0,
      "sale_total_minor": 10000,
      "cash_payment_minor": 10000,
      "operation": "OP-PHS-A-1",
      "original_actor": "E-PHS-A",
      "historical_auth_session": "S-A0",
      "original_device": "D-PHS-1",
      "original_context": "X-A/L-A",
      "new_employee_recovery_grant": "none",
      "scope": "Independent synthetic local fixture; no real payment or fiscal claim."
    },
    "committed_reply_lost": {
      "state": "One sale committed before response loss; original client operation still unknown until scoped recovery.",
      "expected_cash_minor": 110000,
      "expected_stock_units": 9,
      "expected_sale_count": 1,
      "expected_cash_tender_count": 1,
      "expected_committed_result_count": 1,
      "retirement_business_deltas": 0,
      "expected_actor": "E-PHS-A",
      "expected_historical_session": "S-A0"
    },
    "physically_paid_unresolved_precommit": {
      "state": "Actual synthetic payment attestation exists; fixture's POS ledger is known precommit, client must not infer a committed result.",
      "expected_cash_minor": 100000,
      "expected_stock_units": 10,
      "expected_sale_count": 0,
      "expected_committed_result_count": 0,
      "retained_unresolved_attestation_minor": 10000,
      "retirement_business_deltas": 0,
      "expected_actor": "E-PHS-A",
      "expected_historical_session": "S-A0",
      "automatic_recharge_or_resale": false
    }
  },
  "rejected_business_delta_vector": {
    "new_orders": 0,
    "sales": 0,
    "tenders_or_cash_movements": 0,
    "stock_units": 0,
    "preparation_issues_or_tickets": 0,
    "new_committed_results": 0,
    "private_payloads_disclosed": 0
  },
  "execution_prerequisites": {
    "controlled": "Declared principal/currentness/capability/close observation seams with deterministic bounded barriers; unit evidence only.",
    "genuine": "Official matching SDK artifacts; actual authenticated callback lifetimes; protected production POS native admission; exact close outcome; real Master registry/policy/operation persistence and independent committed reads.",
    "platforms": ["Windows", "macOS", "Android", "iPhone", "iPad"],
    "minimum_os": "Not selected by this design; each actual run must record qualified minimum OS, native provider, architecture and device prerequisites.",
    "central": "Implementation and verification remain held."
  }
}
    """.trimIndent()).jsonObject
    val original = fixtures.getValue("original").jsonObject
    val successor = fixtures.getValue("same_key_successor").jsonObject
    val employeeSuccessor = fixtures.getValue("session_successors").jsonObject
        .getValue("new_employee_B").jsonObject
    val resource = fixtures.getValue("policy_activation").jsonObject.string("resource")
    val lifetime = original.number("sdk_lifetime_seconds").seconds
    val sdkGeneration = original.number("sdk_generation")

    // Reuse only canonical label conversion, not the identityv1 expected identity or its clocks.
    fun id(label: String): DomainId = IdentityFrozen.id(label)
    val scope = BranchScope(id(original.string("organization")), id(original.string("company")),
        id(original.string("branch")))
    val peer = PeerIdentity(PeerId(original.string("device")),
        IdentityFrozen.fingerprint(original.string("peer_fingerprint")))

    fun originalIdentity(): VerifiedLocalIdentity = VerifiedLocalIdentity(
        id(original.string("employee")), scope, id(original.string("device")),
        id(original.string("auth_session")), SessionGeneration(original.number("pos_session_generation")),
        Revision(original.number("admission_revision")), AuthenticationPurpose.INTERACTIVE,
    )

    fun successorIdentity(): VerifiedLocalIdentity = originalIdentity().copy(
        actorId = id(employeeSuccessor.string("employee")),
        sessionId = id(employeeSuccessor.string("auth_session")),
        sessionGeneration = SessionGeneration(employeeSuccessor.number("pos_session_generation")),
        admissionRevision = Revision(employeeSuccessor.number("admission_revision")),
    )
}

/**
 * SYNTHETIC L1 ONLY. Invoke JVM-public constructors of these SDK data classes.
 * No accessibility override, private-member write, RPC engine/transport injection or real callback.
 * A missing public constructor is a wiring failure; there is no fallback to private SDK access.
 */
private object SyntheticSdkData {
    private val permitted = setOf(
        RpcPhysicalSession::class.java, RpcAuthenticationContext::class.java,
        RpcRequestId::class.java, RpcAuthorizationContext::class.java, RpcCallContext::class.java,
    )

    fun <T : Any> create(type: Class<T>, signature: Array<Class<*>>, vararg values: Any?): T {
        require(type in permitted)
        return type.getConstructor(*signature).newInstance(*values)
    }

    val longType: Class<*> = java.lang.Long.TYPE
    val intType: Class<*> = java.lang.Integer.TYPE
    val boxedLongType: Class<*> = java.lang.Long::class.java
    val suspendFunctionType: Class<*> = Class.forName("kotlin.jvm.functions.Function1")
}

/** Preserve the declared source cause across JVM coroutine stack-trace recovery. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class SyntheticRetainedCloseFailure(message: String) : RuntimeException(message),
    CopyableThrowable<SyntheticRetainedCloseFailure> {
    // A copied diagnostic wrapper is not another independently declared source outcome.
    override fun createCopy(): SyntheticRetainedCloseFailure? = null
}

/** SDK close wrapper around a declared synthetic close action; it owns no socket/native handle. */
internal class SyntheticSdkPhysicalLifetime(
    val label: String,
    val peer: PeerIdentity = PhysicalSessionFrozen.peer,
    val generation: Long = PhysicalSessionFrozen.sdkGeneration,
) {
    val transportState = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    var closeBarrier: IdentityBarrier? = null
    var closeFailure: Throwable? = null
    val closeTargets = mutableListOf<SyntheticSdkPhysicalLifetime>()
    val capability: RpcPhysicalSession

    init {
        val syntheticClose: suspend () -> Unit = {
            closeTargets += this
            closeBarrier?.pause()
            closeFailure?.let { throw it }
            transportState.value = ConnectionState.Closed
        }
        capability = SyntheticSdkData.create(
            RpcPhysicalSession::class.java,
            arrayOf(PeerIdentity::class.java, SyntheticSdkData.longType, StateFlow::class.java,
                SyntheticSdkData.suspendFunctionType),
            peer, generation, transportState, syntheticClose,
        )
    }
}

/** Scheduled POS peer/currentness data, not native admission, trust or continuity evidence. */
internal class ControlledPhysicalDataProvenance(
    private val sdkA: RpcPhysicalSession,
    private val sdkB: RpcPhysicalSession,
    var originalPeer: LocalAuthenticatedPeer?,
    var successorPeer: LocalAuthenticatedPeer?,
) : LocalRpcPhysicalPeerProvenance {
    val captures = mutableListOf<RpcPhysicalSession>()
    var barrier: IdentityBarrier? = null
    var failure: Throwable? = null

    override suspend fun capture(physicalSession: RpcPhysicalSession): LocalAuthenticatedPeer? {
        captures += physicalSession
        barrier?.pause()
        failure?.let { throw it }
        return if (physicalSession === sdkA) originalPeer
            else if (physicalSession === sdkB) successorPeer else null
    }
}

internal class PhysicalSessionUnitFixture {
    val callerA = CallerIdentitySecret()
    val callerB = CallerIdentitySecret()
    val clock = IdentityClock(second = 0)
    val nativeLifetimeA = ControlledPhysicalLifetime(clock)
    val nativeLifetimeB = ControlledPhysicalLifetime(clock)
    val sdkA = SyntheticSdkPhysicalLifetime(PhysicalSessionFrozen.original.string("sdk_physical_lifetime"))
    val sdkB = SyntheticSdkPhysicalLifetime(PhysicalSessionFrozen.successor.string("sdk_physical_lifetime"))
    val peer: PeerIdentity get() = sdkA.peer
    val originalIdentity = PhysicalSessionFrozen.originalIdentity()
    val successorIdentity = PhysicalSessionFrozen.successorIdentity()
    val nativeA = nativePeer(PhysicalSessionFrozen.original.string("sdk_callback_context"), nativeLifetimeA)
    val nativeB = nativePeer(PhysicalSessionFrozen.successor.string("sdk_callback_context"), nativeLifetimeB)
    val provenance = ControlledPhysicalDataProvenance(sdkA.capability, sdkB.capability, nativeA, nativeB)
    val verifier = ControlledIdentityVerifier(callerA, callerB, clock, originalIdentity, "CRED-A").apply {
        // No native/SDK elapsed-expiry assertion is made through this scheduled verifier.
        expiry = Long.MAX_VALUE
    }
    val factory = TrustedPrincipalFactory(verifier)
    val credentials = LocalGatewayCredentials(nativeA.connectionId, callerA.bearer)
    val successorCredentials = LocalGatewayCredentials(nativeB.connectionId, callerB.bearer)
    private val bindings = mutableListOf<LocalRpcIdentityBinding>()

    private fun nativePeer(label: String, lifetime: ControlledPhysicalLifetime): LocalAuthenticatedPeer =
        LocalAuthenticatedPeer(PhysicalSessionFrozen.id(label), PhysicalSessionFrozen.scope,
            originalIdentity.deviceId, "synthetic-key:" + peer.fingerprint!!.value,
            peer.fingerprint!!.value, lifetime::current)

    fun authenticationData(
        physical: RpcPhysicalSession = sdkA.capability,
        peer: PeerIdentity = physical.peer,
    ): RpcAuthenticationContext = SyntheticSdkData.create(
        RpcAuthenticationContext::class.java,
        arrayOf(PeerIdentity::class.java, RpcPhysicalSession::class.java), peer, physical,
    )

    private fun requestIdData(): RpcRequestId = SyntheticSdkData.create(
        RpcRequestId::class.java, arrayOf(String::class.java), "synthetic-phs-request-a",
    )

    fun authorizationData(
        scope: RpcAuthorizationScope,
        original: RpcPhysicalSession? = sdkA.capability,
        physical: RpcPhysicalSession? = sdkA.capability,
        operation: RpcAuthorizationOperation = RpcAuthorizationOperation.Invoke,
        peer: PeerIdentity = this.peer,
    ): RpcAuthorizationContext {
        val prefixTypes = arrayOf<Class<*>>(PeerIdentity::class.java, RpcRequestId::class.java, String::class.java,
            SyntheticSdkData.intType, String::class.java, RpcAuthorizationScope::class.java,
            RpcAuthorizationOperation::class.java)
        val prefixValues = arrayOf(peer, requestIdData(), "synthetic.physical-session", 1,
            "synthetic-phs-host", scope, operation)
        if (physical == null) {
            require(original == null) // The public data constructor leaves BOTH associations absent.
            return SyntheticSdkData.create(RpcAuthorizationContext::class.java, prefixTypes, *prefixValues)
        }
        return SyntheticSdkData.create(RpcAuthorizationContext::class.java,
            prefixTypes + arrayOf(RpcPhysicalSession::class.java, RpcPhysicalSession::class.java),
            *prefixValues, original, physical)
    }

    fun handlerData(
        scope: RpcAuthorizationScope? = null,
        physical: RpcPhysicalSession? = null,
        peer: PeerIdentity = this.peer,
    ): RpcCallContext {
        val prefixTypes = arrayOf<Class<*>>(PeerIdentity::class.java, RpcRequestId::class.java,
            SyntheticSdkData.longType, String::class.java, SyntheticSdkData.boxedLongType)
        val prefixValues = arrayOf(peer, requestIdData(), 1_000L, "synthetic-phs-host", null)
        if (scope == null) {
            require(physical == null) // No unscoped-with-physical constructor is invented.
            return SyntheticSdkData.create(RpcCallContext::class.java, prefixTypes, *prefixValues)
        }
        return if (physical == null) SyntheticSdkData.create(RpcCallContext::class.java,
            prefixTypes + RpcAuthorizationScope::class.java, *prefixValues, scope)
        else SyntheticSdkData.create(RpcCallContext::class.java,
            prefixTypes + arrayOf(RpcAuthorizationScope::class.java, RpcPhysicalSession::class.java),
            *prefixValues, scope, physical)
    }

    suspend fun bind(
        context: RpcAuthenticationContext = authenticationData(),
        credentials: LocalGatewayCredentials = this.credentials,
        lifetime: Duration = PhysicalSessionFrozen.lifetime,
    ): LocalRpcIdentityBinding? = LocalRpcIdentityBinding.bind(context, credentials, factory,
        provenance, lifetime).also { if (it != null) bindings += it }

    suspend fun legacyBind(): LocalRpcIdentityBinding? = LocalRpcIdentityBinding.bind(peer, credentials,
        factory, LocalRpcPeerProvenance { provenance.originalPeer }, PhysicalSessionFrozen.lifetime)
        .also { if (it != null) bindings += it }

    suspend fun scope(
        binding: LocalRpcIdentityBinding,
        context: RpcAuthenticationContext = authenticationData(),
    ): RpcAuthorizationScope? = binding.authorizationScope(context, PhysicalSessionFrozen.resource)

    suspend fun close() = withContext(NonCancellable) {
        sdkA.closeBarrier?.release?.complete(Unit)
        sdkB.closeBarrier?.release?.complete(Unit)
        provenance.barrier?.release?.complete(Unit)
        verifier.barrier?.release?.complete(Unit)
        try {
            for (binding in bindings) {
                try { binding.retire() } catch (failure: Throwable) {
                    // Preserve only the exact declared synthetic terminal outcome.
                    if (failure !== sdkA.closeFailure && failure !== sdkB.closeFailure) throw failure
                }
            }
        } finally { callerA.close(); callerB.close() }
    }
}

internal suspend fun physicalSessionFixture(
    block: suspend (PhysicalSessionUnitFixture) -> Unit,
) {
    val fixture = PhysicalSessionUnitFixture()
    try { block(fixture) } finally { fixture.close() }
}

internal fun assertOriginalPhysicalPrincipal(
    actual: LocalEmployeePrincipal,
    expected: VerifiedLocalIdentity = PhysicalSessionFrozen.originalIdentity(),
) {
    assertEquals(expected.actorId, actual.actorId)
    assertEquals(expected.scope, actual.scope)
    assertEquals(expected.deviceId, actual.deviceId)
    assertEquals(expected.sessionId, actual.sessionId)
    assertEquals(expected.sessionGeneration, actual.sessionGeneration)
    assertEquals(expected.admissionRevision, actual.admissionRevision)
    assertEquals(expected.purpose, actual.authenticationPurpose)
}

internal fun assertOriginalPhysicalScope(
    scope: RpcAuthorizationScope,
    fixture: PhysicalSessionUnitFixture,
    expected: VerifiedLocalIdentity = PhysicalSessionFrozen.originalIdentity(),
) {
    assertEquals(expected.actorId.value, scope.session.principal)
    assertEquals(expected.sessionId.value, scope.session.sessionId)
    assertEquals(fixture.peer.fingerprint, scope.session.peer)
    assertEquals(PhysicalSessionFrozen.resource, scope.resource)
    assertTrue(scope.session.isActive)
}

internal fun assertPhysicalRepresentationsRedacted(
    fixture: PhysicalSessionUnitFixture,
    representations: List<String>,
    privateDiagnostics: List<String> = emptyList(),
) {
    val privateValues = fixture.callerA.canaries() + fixture.callerB.canaries() + privateDiagnostics
    for (representation in representations) for (privateValue in privateValues)
        assertFalse(representation.contains(privateValue), "Public output exposed a synthetic private canary")
}

internal fun sameIdentifierDifferentSdkSession(scope: RpcAuthorizationScope): RpcAuthorizationScope =
    RpcAuthorizationScope(RpcApplicationSession(scope.session.peer, scope.session.principal,
        scope.session.sessionId, PhysicalSessionFrozen.lifetime), scope.resource)
