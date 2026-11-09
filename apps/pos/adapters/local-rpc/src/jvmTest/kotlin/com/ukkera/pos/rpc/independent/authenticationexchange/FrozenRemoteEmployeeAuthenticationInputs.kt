package com.ukkera.pos.rpc.independent.authenticationexchange

import com.ukkera.pos.rpc.LocalRpcAuthenticationAction
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.security.VerifiedLocalIdentity
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unchanged v1 inputs. Symbolic bearer/physical coordinates below are DATA, not admission. */
internal object RemoteEmployeeAuthenticationFrozen {
    private const val fixtureHash = "922509c2936525d54f02e541bd9d521d825ab5451fd75dd5e00dec0ea5f7c9ee"
    private const val caseHash = "11733db43b1db894b73ded18006f0a6cdb86a653f0e94917826c16efc80ea46b"
    private const val directory = "qa/pos/designs/remote-employee-authentication"

    private val location: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve(directory) }
        .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
        ?: error("Frozen remote employee authentication inputs are unavailable")

    val fixtures: JsonObject = run {
        val bytes = Files.readAllBytes(location.resolve("fixtures.json"))
        assertEquals(fixtureHash, hash(bytes), "Frozen remote authentication fixture changed")
        assertEquals(caseHash, hash(Files.readAllBytes(location.resolve("cases.md"))),
            "Frozen remote authentication expectations changed")
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject.also {
            assertEquals("remote-employee-authentication-v1", it.string("version"))
        }
    }

    val identity: JsonObject = fixtures.getValue("original_identity").jsonObject
    val scope: BranchScope = BranchScope(id("organizationId"), id("companyId"), id("branchId"))
    val physical: JsonObject = fixtures.getValue("physical_observations").jsonObject
    val renewal: JsonObject = fixtures.getValue("renewal_observations").jsonObject
    val secretMarkers: List<String> = fixtures.getValue("secret_markers").jsonArray.map { it.jsonPrimitive.content }

    fun id(key: String): DomainId = DomainId(identity.string(key))
    fun request(id: String): JsonObject = fixtures.getValue("requests").jsonArray
        .map { it.jsonObject }.single { it.string("id") == id }
    fun action(id: String): LocalRpcAuthenticationAction = LocalRpcAuthenticationAction.valueOf(request(id).string("action"))
    fun requestWire(id: String): JsonObject = semanticWire(request(id))

    fun refusalWire(id: String, omitMissing: Boolean = false): JsonObject {
        val failure = fixtures.getValue("request_shape_failures").jsonArray
            .map { it.jsonObject }.single { it.string("id") == id }
        val semantic = request(failure.string("base")).toMutableMap()
        failure["change"]?.let { change ->
            val key = change.jsonPrimitive.content
            val value = failure.getValue("value")
            if (omitMissing && value == JsonNull) semantic.remove(key) else semantic[key] = value
        }
        failure["add"]?.jsonObject?.let(semantic::putAll)
        return semanticWire(JsonObject(semantic))
    }

    /**
     * The freeze's bearer strings are symbols, not issued ULA1/ULR1 tokens. Padding their
     * synthetic bytes binds the production codec's 52-byte carrier only; the prefix is
     * intentionally not a valid issued-token prefix. No service receives these credentials.
     */
    fun carrier(symbol: String): ByteArray {
        val bytes = symbol.encodeToByteArray()
        return ByteArray(52) { index -> if (index < bytes.size) bytes[index] else 0 }
    }

    fun requestBearer(id: String): ByteArray = carrier(request(id).string("bearer"))
    fun originalAccess(): ByteArray = requestBearer("current")
    fun originalRefresh(): ByteArray = requestBearer("refresh")
    fun successorAccess(): ByteArray = carrier("access:" + renewal.string("access_pair"))
    fun successorRefresh(): ByteArray = carrier("refresh:" + renewal.string("access_pair"))

    /** Stable syntax-only UUID conversion for the freeze's non-UUID owner/successor symbols. */
    fun symbolId(symbol: String): DomainId = DomainId(UUID.nameUUIDFromBytes(symbol.encodeToByteArray()).toString())
    fun physicalId(label: String): DomainId = symbolId(physical.getValue(label).jsonObject.string("exact_owner_symbol"))
    fun successorSessionId(): DomainId = symbolId(renewal.string("successor_session_id_symbol"))

    fun verifiedIdentity(): VerifiedLocalIdentity = VerifiedLocalIdentity(id("actorId"), scope, id("deviceId"),
        id("sessionId"), SessionGeneration(identity.number("generation")), Revision(identity.number("admissionRevision")),
        AuthenticationPurpose.INTERACTIVE)

    /**
     * Controlled display carrier only. The freeze specifies one interactive session and the
     * throttle/deadline DATA. Empty grants, revision zero and equal access/work deadlines are
     * explicit codec inputs, not inferred default policy, TTL or actual issuance facts.
     * The boot coordinate is also inert DATA, not a qualified native clock observation.
     */
    fun view(sessionId: DomainId = id("sessionId")): LocalSessionView {
        assertEquals("one_interactive_session", fixtures.getValue("accounts").jsonObject
            .getValue("account_a").jsonObject.string("policy"))
        val throttle = fixtures.getValue("throttle_policy").jsonObject
        val policy = ActivatedLocalPolicy(scope, id("actorId"), Revision(0), emptyList(), LocalSessionPolicy(1),
            ThrottlePolicy(throttle.number("freeFailures").toInt(), throttle.number("initialDelayMillis"),
                throttle.number("maximumDelayMillis"), throttle.number("attemptTimeoutMillis")))
        val deadline = MonotonicMoment(id("sessionId"), renewal.number("controlled_original_work_deadline_elapsed_ms"))
        return LocalSessionView(id("actorId"), scope, id("deviceId"), sessionId,
            SessionGeneration(identity.number("generation")), LocalLoginIdentifier(request("login").string("loginIdentifier")),
            policy, deadline, deadline)
    }

    private fun semanticWire(value: JsonObject): JsonObject {
        val output = linkedMapOf<String, JsonElement>()
        for ((key, data) in value) when (key) {
            "exchangeId", "action" -> output[key] = data
            "loginIdentifier" -> output["login"] = data
            "deviceMoveConsent" -> output["explicitDeviceMove"] = data
            "password" -> output[key] = if (data == JsonNull) JsonNull
                else JsonArray(data.jsonPrimitive.content.map { JsonPrimitive(it.toString()) })
            "bearer" -> output[key] = if (data == JsonNull) JsonNull
                else JsonArray(carrier(data.jsonPrimitive.content).map { JsonPrimitive(it) })
        }
        return JsonObject(output)
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.long

internal fun assertRemoteView(expected: LocalSessionView, actual: LocalSessionView) {
    assertEquals(expected.actorId, actual.actorId)
    assertEquals(expected.scope, actual.scope)
    assertEquals(expected.deviceId, actual.deviceId)
    assertEquals(expected.sessionId, actual.sessionId)
    assertEquals(expected.generation, actual.generation)
    assertEquals(expected.login.value, actual.login.value)
    assertEquals(expected.accessExpiresAt, actual.accessExpiresAt)
    assertEquals(expected.workExpiresAt, actual.workExpiresAt)
    assertEquals(expected.policy.scope, actual.policy.scope)
    assertEquals(expected.policy.actorId, actual.policy.actorId)
    assertEquals(expected.policy.revision, actual.policy.revision)
    assertEquals(expected.policy.sessionPolicy.maximumConcurrentSessions, actual.policy.sessionPolicy.maximumConcurrentSessions)
    assertEquals(expected.policy.throttlePolicy, actual.policy.throttlePolicy)
    assertTrue(actual.policy.grants.isEmpty(), "Controlled empty display grants changed")
}

internal fun assertRemoteCredentialDestroyed(value: OpaqueCredential) {
    assertFailsWith<IllegalStateException> { value.useBytes { Unit } }
}

internal fun assertRemoteRedacted(vararg values: Any?) {
    val markers = RemoteEmployeeAuthenticationFrozen.secretMarkers
    val probes = markers.flatMap { marker ->
        val raw = marker.encodeToByteArray()
        val carrier = RemoteEmployeeAuthenticationFrozen.carrier(marker)
        listOf(marker, raw.joinToString("") { "%02x".format(it.toInt() and 255) },
            Base64.getEncoder().encodeToString(raw),
            carrier.joinToString("") { "%02x".format(it.toInt() and 255) },
            Base64.getEncoder().encodeToString(carrier))
    }
    for (value in values) for (probe in probes)
        assertFalse(value.toString().contains(probe), "Public rendering exposed synthetic private data")
}
