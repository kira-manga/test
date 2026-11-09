package com.ukkera.pos.rpc.independent.identityv1

import com.ukkera.pos.rpc.LocalRpcIdentityBinding
import com.ukkera.pos.rpc.LocalRpcPeerProvenance
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerId
import dev.p2pkit.core.PeerIdentity
import dev.p2pkit.rpc.RpcAuthorizationScope
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Frozen semantic fixture labels are schema coordinates, never native admission evidence. */
internal object IdentityFrozen {
    val fixtures = Json.parseToJsonElement("""
{
  "design_version": "v1",
  "frozen_utc": "2026-10-30T12:03:40Z",
  "status": "FROZEN_NOT_RUN",
  "schema_note": "Semantic test inputs only. Names below are fixture labels, not production record fields, constructors, procedure names or accepted credentials.",
  "F-ID": {
    "scope_A": {
      "organization": "ORG-A",
      "company": "COMP-A",
      "branch": "BRANCH-A",
      "namespace": "QUALIFICATION-A"
    },
    "scope_other": {
      "organization": "ORG-B",
      "company": "COMP-B",
      "branch": "BRANCH-B",
      "namespace": "QUALIFICATION-B"
    },
    "original": {
      "employee": "EMP-A",
      "device": "DEVICE-A",
      "session": "SESSION-A",
      "generation": "GEN-7",
      "admission_revision": "ADM-17",
      "purpose": "interactive branch employee",
      "state": "currently verified, enabled and unrevoked",
      "expires_at_clock_second": 1100
    },
    "successors": [
      "EMP-B / SESSION-B / GEN-8 on DEVICE-A",
      "EMP-A / SESSION-A2 / GEN-8 on DEVICE-A",
      "EMP-A / SESSION-A2 / GEN-8 on DEVICE-B"
    ],
    "verification": "The authoritative test source verifies the credential presented to it and returns the scheduled current principal or refusal. It records only credential labels and buffer identity, never bearer bytes.",
    "original_tuple": [
      "organization",
      "company",
      "branch",
      "namespace",
      "employee",
      "device",
      "session",
      "generation",
      "admission_revision"
    ],
    "clock": {
      "initial_second": 1000,
      "before_principal_expiry": 1099,
      "at_principal_expiry": 1100,
      "after_principal_expiry": 1101
    },
    "negative_purposes": [
      "company administration",
      "master service",
      "kitchen display service",
      "recovery authority",
      "missing or unverified purpose"
    ]
  },
  "F-PEER": {
    "admission": "DEVICE-A is admitted to scope_A at ADM-17 with trusted fingerprint FP-A; DEVICE-B has a separate valid admission.",
    "original": {
      "actual_device": "DEVICE-A",
      "authenticated_fingerprint": "FP-A",
      "physical_lifetime": "LIFE-A",
      "authenticated_connection": "CONNECTION-A",
      "current": true,
      "authenticated": true
    },
    "native_expiry": {
      "qualified_expiry_second": 1020,
      "before": 1019,
      "at": 1020,
      "after": 1021
    },
    "new_connection": "Same admitted DEVICE-A and FP-A, but LIFE-A2 / CONNECTION-A2. This is fresh trusted evidence for a new binding, never continuity of the old one.",
    "decoys": "RPC body, discovery, human login label, cached permissions and endpoint labels can claim any tuple; none constitutes authenticated physical evidence."
  },
  "F-TTL": {
    "trusted_positive_sdk_lifetime": "30 seconds, supplied explicitly by trusted composition",
    "invalid_lifetimes": [
      "absent",
      "zero",
      "negative finite",
      "positive infinity",
      "negative infinity",
      "other nonfinite value if representable"
    ],
    "relationship": "SDK lifetime is an additional finite bound. Native expiry at second 1020 and principal expiry at 1100 remain independent. Untrusted payload lifetime hints cannot replace any bound.",
    "sdk_expiry_observation": "Use the actual SDK deadline and clock semantics during integration; no assumed private timer or constructor is part of this fixture."
  },
  "F-SECRET": {
    "material": "Generate separate in-memory mutable synthetic buffers for credential labels CRED-A and CRED-B. Use test-only recognizable canaries; do not put raw bytes in case files or evidence.",
    "ownership": "Caller retains each original buffer. A lifetime probe records copy/borrow/release events and whether a buffer remains usable, without exporting contents.",
    "source_probe": "At a verification barrier compare argument buffer identity with caller buffer, then optionally overwrite the received argument. Verify caller buffer separately in memory.",
    "redaction_probe": "Search public representations and permitted serialization/error surfaces for raw and common hex/base64 encodings of the generated canary; record booleans only.",
    "release_cut": "After failed binding completes, or retirement completes and any in-flight verification has unwound, no binding-owned usable bearer remains. Production observability must be qualified separately."
  },
  "F-RACE": {
    "barriers": [
      "before principal verification resolves",
      "before identity outcome is published",
      "before final Master authorization/commit",
      "after durable commit before response",
      "before SDK private outbound commitment"
    ],
    "coordination": "Use explicit barriers and bounded completion checks. A source may finish late after cancellation/retirement to challenge late-success suppression; no sleeps or retry-until-pass."
  },
  "F-MASTER": {
    "provisioning": "Isolated real local Master and native clients with a clearly labeled synthetic qualification provisioning fixture; no central service substitute and no real credentials or transactions.",
    "orders": "ORDER-A is owned by EMP-A; ORDER-B by EMP-B. Ordinary employee access to the other active order is denied, including direct IDs, reads and subscriptions.",
    "sale": {
      "operation": "OP-A",
      "origin_actor": "EMP-A",
      "item_quantity": 1,
      "unit_price_minor": 100,
      "currency": "EGP",
      "unit_cost_minor": 40,
      "payment": "synthetic cash attestation of 100 minor"
    },
    "before": {
      "stock_quantity": 1,
      "stock_value_minor": 40,
      "drawer_cash_minor": 1000,
      "sales_minor": 0,
      "cost_of_goods_minor": 0,
      "receipt_count": 0,
      "operation_result_count": 0
    },
    "after_one_commit": {
      "stock_quantity": 0,
      "stock_value_minor": 0,
      "drawer_cash_minor": 1100,
      "sales_minor": 100,
      "cost_of_goods_minor": 40,
      "receipt_count": 1,
      "operation_result_count": 1,
      "sale_actor": "EMP-A",
      "operation": "OP-A"
    },
    "observations": "Read committed ledgers, original result, audit actor and required outbox effects independently through a fresh production read path. Refusal preserves before; retries preserve after_one_commit.",
    "physical_safety": "Cash is synthetic ledger data; never move actual money or operate real payment providers."
  },
  "F-SDK": {
    "official_modules": {
      "core": "0.8.0-rc8",
      "LAN": "0.8.0-rc8",
      "RPC": "0.8.0-rc8"
    },
    "private_boundary": "Real official scoped registration, original immutable binding, explicit positive finite lifetime and qualified current native evidence.",
    "negative_configuration": [
      "scoped registration unavailable",
      "finite lifetime omitted",
      "native provider/trust unavailable",
      "proposed legacy device-only registration fallback"
    ],
    "profile": "Selected invoke-only profile must be documented by its public allowed procedures and denied capabilities before integration wiring; discovery, status and errors must obey the same privacy boundary."
  },
  "F-PLATFORM": {
    "required_platforms": [
      "Windows",
      "macOS",
      "Android",
      "physical iPhone",
      "physical iPad"
    ],
    "networks": [
      "N0 healthy admitted LAN",
      "N1 WAN/central unavailable",
      "N2 WAN/central and standby unavailable",
      "N3 partition and reconnect",
      "N4 lost/duplicate responses",
      "N5 process termination/restart/native permission loss"
    ],
    "scope": "Use real packaged native providers and named physical environments. Shared/JVM or simulator evidence cannot satisfy native admission, expiry, secure storage, OS lifecycle or physical-platform results.",
    "pairs": "Critical integrated login/financial flow qualification retains all 25 ordered Master/client platform pairs from original quality plan section 4.3. Wider identity faults use recorded risk-based pairs without dropping any native provider."
  }
}
    """.trimIndent()).jsonObject
    val identity = fixtures.getValue("F-ID").jsonObject
    val peer = fixtures.getValue("F-PEER").jsonObject
    val ttl = fixtures.getValue("F-TTL").jsonObject
    val original = identity.getValue("original").jsonObject
    val scopeA = scope(identity.getValue("scope_A").jsonObject)
    val originalPeer = peer.getValue("original").jsonObject
    val initialSecond = identity.getValue("clock").jsonObject.number("initial_second")
    val principalExpiry = original.number("expires_at_clock_second")
    val nativeExpiry = peer.getValue("native_expiry").jsonObject.number("qualified_expiry_second")
    val sdkLifetime = ttl.string("trusted_positive_sdk_lifetime").substringBefore(" seconds").toLong().seconds
    val resource = "ORDER-A"

    fun id(label: String): DomainId = DomainId(UUID.nameUUIDFromBytes(label.encodeToByteArray()).toString())
    fun scope(value: JsonObject) = BranchScope(id(value.string("organization")), id(value.string("company")), id(value.string("branch")))
    fun originalIdentity() = VerifiedLocalIdentity(id(original.string("employee")), scopeA,
        id(original.string("device")), id(original.string("session")),
        SessionGeneration(original.string("generation").substringAfter('-').toLong()),
        Revision(original.string("admission_revision").substringAfter('-').toLong()), AuthenticationPurpose.INTERACTIVE)

    /** Exact label-to-canonical-descriptor conversion only; it does not authenticate a key. */
    fun fingerprint(label: String): PeerFingerprint {
        val digest = MessageDigest.getInstance("SHA-256").digest(label.encodeToByteArray())
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        var bits = 0
        var accumulator = 0
        val encoded = StringBuilder()
        for (byte in digest) {
            accumulator = (accumulator shl 8) or (byte.toInt() and 255)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                encoded.append(alphabet[(accumulator ushr bits) and 31])
            }
        }
        if (bits > 0) encoded.append(alphabet[(accumulator shl (5 - bits)) and 31])
        return PeerFingerprint.parse("p2f1-" + encoded)
    }

    fun sdkPeer(device: String = originalPeer.string("actual_device"), fingerprintLabel: String = originalPeer.string("authenticated_fingerprint")) =
        PeerIdentity(PeerId(device), fingerprint(fingerprintLabel))

    fun successor(value: String): VerifiedLocalIdentity {
        val match = assertNotNull(Regex("(EMP-[^ ]+) / (SESSION-[^ ]+) / GEN-([0-9]+) on (DEVICE-[^ ]+)").matchEntire(value))
        return originalIdentity().copy(actorId = id(match.groupValues[1]), sessionId = id(match.groupValues[2]),
            sessionGeneration = SessionGeneration(match.groupValues[3].toLong()), deviceId = id(match.groupValues[4]))
    }
}

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.long

internal class IdentityClock(var second: Long = IdentityFrozen.initialSecond)

/** Controlled unit observation only. Ending this exact lifetime is irreversible. */
internal class ControlledPhysicalLifetime(val clock: IdentityClock) {
    private var ended = false
    var expiry: Long? = null
    var failure: Throwable? = null
    fun end() { ended = true }
    fun current(): Boolean {
        failure?.let { throw it }
        if (expiry?.let { clock.second >= it } == true) ended = true
        return !ended
    }
}

internal class IdentityBarrier(private val finishAfterCancellation: Boolean = false) {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    suspend fun pause() {
        entered.complete(Unit)
        if (finishAfterCancellation) withContext(NonCancellable) { release.await() }
        else release.await()
    }
}

/** Caller-owned canaries are generated in memory and never included in assertion output. */
internal class CallerIdentitySecret {
    val bytes: ByteArray = ByteArray(48).also { output ->
        val random = SecureRandom()
        for (index in output.indices) output[index] = ('A'.code + random.nextInt(26)).toByte()
    }
    private val original = bytes.copyOf()
    val bearer = OpaqueCredential(bytes)
    fun matches(value: ByteArray): Boolean = value.contentEquals(original)
    fun assertIntact() {
        assertTrue(bytes.contentEquals(original), "Caller buffer changed")
        assertTrue(bearer.useBytes(::matches), "Caller-owned credential changed")
    }
    fun canaries(): List<String> = listOf(original.decodeToString(),
        original.joinToString("") { "%02x".format(it.toInt() and 255) }, Base64.getEncoder().encodeToString(original))
    fun clearCaller() { bytes.fill(0); bearer.destroy() }
    fun substituteCaller(other: CallerIdentitySecret) { other.bytes.copyInto(bytes); bearer.destroy() }
    fun close() { bytes.fill(0); original.fill(0); bearer.destroy() }
}

internal data class IdentitySourceObservation(val label: String, val connection: DomainId, val bearer: OpaqueCredential)

/** Scheduled current identity is a unit seam, not a native admission or production registry. */
internal class ControlledIdentityVerifier(
    val a: CallerIdentitySecret,
    val b: CallerIdentitySecret,
    val clock: IdentityClock,
    var identity: VerifiedLocalIdentity?,
    var acceptedLabel: String,
) : PrincipalVerifier {
    val observations = mutableListOf<IdentitySourceObservation>()
    val borrowedBuffers = mutableListOf<ByteArray>()
    var barrier: IdentityBarrier? = null
    var failure: Throwable? = null
    var expiry = IdentityFrozen.principalExpiry
    var sourceAction: ((LocalGatewayCredentials) -> Unit)? = null
    override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity? {
        val label = credentials.bearer.useBytes { buffer ->
            assertTrue(buffer !== a.bytes && buffer !== b.bytes, "Source borrowed caller mutable memory")
            borrowedBuffers += buffer
            when { a.matches(buffer) -> "CRED-A"; b.matches(buffer) -> "CRED-B"; else -> "UNRECOGNIZED" }
        }
        observations += IdentitySourceObservation(label, credentials.connectionId, credentials.bearer)
        assertTrue(credentials.bearer !== a.bearer && credentials.bearer !== b.bearer, "Source received a caller credential")
        barrier?.pause()
        sourceAction?.invoke(credentials)
        failure?.let { throw it }
        if (label != acceptedLabel || clock.second >= expiry) return null
        return identity
    }
    override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
    override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
    override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
    fun assertOriginalLabel(label: String = "CRED-A") {
        assertTrue(observations.isNotEmpty())
        assertTrue(observations.all { it.label == label }, "A successor credential substituted the original")
    }
    fun assertReleasedSourceCopies() {
        assertTrue(observations.all { !identityCredentialUsable(it.bearer) }, "A source-disposable credential remains usable")
        assertTrue(borrowedBuffers.all { buffer -> buffer.all { it == 0.toByte() } }, "A source borrow was not cleared")
    }
}

internal class ControlledIdentityProvenance(var current: LocalAuthenticatedPeer?) : LocalRpcPeerProvenance {
    var barrier: IdentityBarrier? = null
    var failure: Throwable? = null
    override suspend fun capture(peer: PeerIdentity): LocalAuthenticatedPeer? {
        barrier?.pause()
        failure?.let { throw it }
        return current
    }
}

internal class IdentityUnitFixture(
    identity: VerifiedLocalIdentity = IdentityFrozen.originalIdentity(),
    deviceLabel: String = IdentityFrozen.originalPeer.string("actual_device"),
    connectionLabel: String = IdentityFrozen.originalPeer.string("authenticated_connection"),
    fingerprintLabel: String = IdentityFrozen.originalPeer.string("authenticated_fingerprint"),
    credentialLabel: String = "CRED-A",
) {
    val a = CallerIdentitySecret()
    val b = CallerIdentitySecret()
    val clock = IdentityClock()
    val lifetime = ControlledPhysicalLifetime(clock)
    val sdkPeer = IdentityFrozen.sdkPeer(deviceLabel, fingerprintLabel)
    val connection = IdentityFrozen.id(connectionLabel)
    val physicalPeer = LocalAuthenticatedPeer(connection, identity.scope, IdentityFrozen.id(deviceLabel),
        "synthetic-key:" + sdkPeer.fingerprint!!.value, sdkPeer.fingerprint!!.value, lifetime::current)
    val provenance = ControlledIdentityProvenance(physicalPeer)
    val verifier = ControlledIdentityVerifier(a, b, clock, identity, credentialLabel)
    val factory = TrustedPrincipalFactory(verifier)
    val credentials = LocalGatewayCredentials(connection, if (credentialLabel == "CRED-A") a.bearer else b.bearer)
    private val bindings = mutableListOf<LocalRpcIdentityBinding>()
    suspend fun bind(peer: PeerIdentity = sdkPeer, credentials: LocalGatewayCredentials = this.credentials,
        duration: Duration = IdentityFrozen.sdkLifetime): LocalRpcIdentityBinding? =
        LocalRpcIdentityBinding.bind(peer, credentials, factory, provenance, duration).also { if (it != null) bindings += it }
    suspend fun scope(binding: LocalRpcIdentityBinding, peer: PeerIdentity = sdkPeer, resource: String = IdentityFrozen.resource): RpcAuthorizationScope? =
        binding.authorizationScope(peer, resource)
    suspend fun close() { for (binding in bindings) binding.retire(); a.close(); b.close() }
}

internal suspend fun identityFixture(
    fixture: IdentityUnitFixture = IdentityUnitFixture(),
    block: suspend (IdentityUnitFixture) -> Unit,
) { try { block(fixture) } finally { fixture.close() } }

internal fun identityCredentialUsable(value: OpaqueCredential): Boolean = try { value.useBytes { true } }
    catch (_: IllegalStateException) { false }

internal fun assertIdentityScope(scope: RpcAuthorizationScope, fixture: IdentityUnitFixture,
    expected: VerifiedLocalIdentity = IdentityFrozen.originalIdentity(), resource: String = IdentityFrozen.resource) {
    assertEquals(expected.actorId.value, scope.session.principal)
    assertEquals(expected.sessionId.value, scope.session.sessionId)
    assertEquals(fixture.sdkPeer.fingerprint, scope.session.peer)
    assertEquals(resource, scope.resource)
    assertTrue(scope.session.isActive)
}

internal fun assertIdentityRedacted(representations: List<String>, fixture: IdentityUnitFixture, diagnostics: List<String> = emptyList()) {
    val canaries = fixture.a.canaries() + fixture.b.canaries() + diagnostics
    for (representation in representations) for (canary in canaries)
        assertFalse(representation.contains(canary), "A public representation exposed a synthetic private canary")
}
