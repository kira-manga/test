package com.ukkera.pos.rpc.independent.authenticationcurrentrenewalv2

import com.ukkera.pos.rpc.LocalRpcAuthenticationRequest
import com.ukkera.pos.rpc.authenticationCurrentProcedure
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals

/** Frozen v2 inputs only. The three-byte sentinels below are not issued bearer credentials. */
internal object AuthenticationCurrentRenewalV2Frozen {
    private const val directory = "qa/pos/designs/authentication-current-renewal-v2"
    private val location: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve(directory) }
        .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
        ?: error("Frozen authentication CURRENT renewal v2 inputs are unavailable")

    val fixtures: JsonObject = run {
        val hashes = linkedMapOf(
            "README.md" to "5db645ee089a22eec330c5568284d8d00e72efa85c5269247819105a282b1212",
            "cases.md" to "6d119644a5c818b2d53bcdb5797235acc158d6748f3f4f4a624653d8b7b29521",
            "fixtures.json" to "a2ced087b55789e4466f26825057a74debb294da78188830f33474a860fb3329",
            "source-manifest.json" to "33077434a3d0b27b267878844b600cfd7bb23e61a71e202df2325e46ba6eec2c",
        )
        val artifacts = hashes.mapValues { (file, expectedHash) ->
            Files.readAllBytes(location.resolve(file)).also {
                assertEquals(expectedHash, hash(it), "Frozen CURRENT renewal v2 artifact changed: $file")
            }
        }
        val manifest = Json.parseToJsonElement(artifacts.getValue("source-manifest.json").decodeToString()).jsonObject
        assertEquals(2, manifest.acr2Int("freeze_version"))
        assertEquals("FROZEN", manifest.acr2String("freeze_state"))
        Json.parseToJsonElement(artifacts.getValue("fixtures.json").decodeToString()).jsonObject.also {
            assertEquals("authentication-current-renewal-v2", it.acr2String("specification_id"))
            assertEquals(2, it.acr2Int("specification_version"))
            assertEquals("FROZEN", it.acr2String("freeze_state"))
        }
    }

    val common: JsonObject get() = fixtures.acr2Object("common_synthetic_values")

    fun rows(group: String): List<AuthenticationCurrentRenewalV2Variant> = fixtures
        .acr2Object("support_variants").getValue(group).jsonArray
        .map { AuthenticationCurrentRenewalV2Variant(it.jsonObject) }

    fun row(group: String, id: String): AuthenticationCurrentRenewalV2Variant = rows(group).single { it.id == id }

    /** Decode each owned instance separately through the actual CURRENT request serializer. */
    fun decodedCurrentRequest(ownedSentinel: ByteArray): LocalRpcAuthenticationRequest = Json.decodeFromJsonElement(
        authenticationCurrentProcedure.request,
        JsonObject(linkedMapOf(
            "exchangeId" to JsonPrimitive(common.acr2String("uuid_O")),
            "action" to JsonPrimitive("CURRENT"),
            "bearer" to JsonArray(ownedSentinel.map { JsonPrimitive(it) }),
        )),
    )

    /**
     * Syntax-only logical-to-codec adaptation. Sentinel bytes are preserved exactly, not padded
     * into credentials or represented as a valid admitted request. Construction/decoding is
     * used solely for descriptor selection and custody; it grants no route or native authority.
     */
    fun decodedRouteRequest(row: AuthenticationCurrentRenewalV2Variant): LocalRpcAuthenticationRequest {
        val body = common.acr2Object("logical_request_bodies").acr2Object(row.inputs.acr2String("body_fixture"))
        val wire = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "exchangeId" to JsonPrimitive(body.acr2String("original_exchange_uuid")),
            "action" to JsonPrimitive(body.acr2String("action")),
        )
        body["bearer_buffer_ref"]?.let {
            wire["bearer"] = JsonArray(common.acr2Bytes(it.jsonPrimitive.content).map { byte -> JsonPrimitive(byte) })
        }
        body["password_buffer_ref"]?.let {
            wire["password"] = JsonArray(common.acr2Bytes(it.jsonPrimitive.content)
                .map { byte -> JsonPrimitive(byte.toInt().toChar().toString()) })
        }
        body["exact_local_login_identifier"]?.let { wire["login"] = it }
        body["explicit_device_move_consent"]?.let { wire["explicitDeviceMove"] = it }
        return Json.decodeFromJsonElement(LocalRpcAuthenticationRequest.serializer(), JsonObject(wire))
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal class AuthenticationCurrentRenewalV2Variant(private val frozen: JsonObject) {
    val id: String get() = frozen.acr2String("id")
    val inputs: JsonObject get() = frozen.acr2Object("inputs_and_schedule")
    val expected: JsonObject get() = frozen.acr2Object("expected")
}

internal fun JsonObject.acr2Object(key: String): JsonObject = getValue(key).jsonObject
internal fun JsonObject.acr2String(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.acr2Boolean(key: String): Boolean = getValue(key).jsonPrimitive.boolean
internal fun JsonObject.acr2Int(key: String): Int = getValue(key).jsonPrimitive.int
internal fun JsonObject.acr2Bytes(key: String): ByteArray = getValue(key).jsonArray
    .map { it.jsonPrimitive.int.toByte() }.toByteArray()
