@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.rpc.independent.remotepeers

import com.ukkera.pos.rpc.LocalRpcBusinessRequestCustody
import com.ukkera.pos.rpc.LocalRpcCurrentIdentityRequest
import com.ukkera.pos.rpc.LocalRpcCurrentIdentityResponse
import com.ukkera.pos.rpc.currentIdentityProcedureWithCustody
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Frozen remote-peer registry v2 RPR-024/026/027/028 supporting DTO/custody slices only.
 * These are unusable sentinel payloads and synthetic codec identities, never accepting native
 * admission, a Host callback, registry registration, authenticated employee or self-session result.
 * The staged decoder observes returned access arrays; isolated custody observes their retirement.
 * Neither observes native verification/binding copies, partial-array scratch, SDK packets or an
 * actual SDK pre-handler refusal. No product expiry duration is inferred from these controls.
 */
class LocalRpcCurrentIdentitySupportingTest {
    @Test
    fun rpr024RequestRenderingOmitsFrozenSensitiveSentinels() {
        for (sentinel in RemotePeerCodecInputs.sensitiveSentinels) {
            val request = LocalRpcCurrentIdentityRequest(sentinel.encodeToByteArray())
            try {
                assertSentinelAbsent(request.toString(), sentinel)
            } finally {
                request.destroy()
            }
        }
    }

    @Test
    fun rpr026RequestCodecKeepsAccessOnlyAndOwnedDtoDestructionClearsBytes() {
        val request = LocalRpcCurrentIdentityRequest(RemotePeerCodecInputs.credentialSentinel.encodeToByteArray())
        var decoded: LocalRpcCurrentIdentityRequest? = null
        try {
            // The wire carries the supplied bytes. It is never used as a diagnostic or acceptance result.
            val wire = Json.encodeToJsonElement(LocalRpcCurrentIdentityRequest.serializer(), request).jsonObject
            assertEquals(setOf("access"), wire.keys)
            val copy = Json.decodeFromJsonElement(LocalRpcCurrentIdentityRequest.serializer(), wire)
            decoded = copy
            assertTrue(copy.access.contentEquals(request.access), "The supplied credential DATA changed in the codec")
            assertSentinelAbsent(copy.toString(), RemotePeerCodecInputs.credentialSentinel)
            request.destroy()
            copy.destroy()
            assertTrue(request.access.all { it == 0.toByte() }, "Owned request DTO bytes were not cleared")
            assertTrue(copy.access.all { it == 0.toByte() }, "Owned decoded DTO bytes were not cleared")
        } finally {
            request.destroy()
            decoded?.destroy()
        }
        // RPR-026/028 ownership support only; no verification/retirement lifecycle is exercised.
    }

    @Test
    fun rpr026MissingAccessCannotBeReplacedByCallerIdentityAssertions() {
        val assertions = buildJsonObject {
            put("actorId", RemotePeerCodecInputs.id("UA"))
            put("deviceId", RemotePeerCodecInputs.id("DA"))
            put("branchId", RemotePeerCodecInputs.id("B0"))
            put("connectionId", RemotePeerCodecInputs.id("CA0"))
            put("current", true)
        }
        // Ignore unrecognized DATA to isolate the mandatory access field, rather than an unknown-key error.
        val controlledCodec = Json { ignoreUnknownKeys = true }
        for (wire in listOf(JsonObject(emptyMap()), assertions)) {
            assertFailsWith<SerializationException> {
                controlledCodec.decodeFromJsonElement(LocalRpcCurrentIdentityRequest.serializer(), wire)
            }
        }
        // Two missing-access shape variants only. Credential validity/expiry/revocation need the real Master.
    }

    @Test
    fun rpr026StagedDecodeErrorClearsReturnedAccessAndRedactsTheFailure() {
        val probe = CurrentIdentityAccessProbe {
            throw SerializationException(RemotePeerCodecInputs.credentialSentinel)
        }
        try {
            val failure = assertFailsWith<SerializationException> {
                LocalRpcCurrentIdentityRequest.serializer().deserialize(probe)
            }
            probe.assertReturnedAccessCleared()
            assertSentinelAbsent(failure.toString(), RemotePeerCodecInputs.credentialSentinel)
        } finally { probe.clear() }
        // RPR-026 refusal/error cleanup and diagnostics portion, not fresh Master verification.
    }

    @Test
    fun rpr026StagedDecodeCancellationClearsReturnedAccess() {
        val probe = CurrentIdentityAccessProbe { throw CancellationException("Controlled self-session decode cancellation") }
        try {
            assertFailsWith<CancellationException> {
                LocalRpcCurrentIdentityRequest.serializer().deserialize(probe)
            }
            probe.assertReturnedAccessCleared()
        } finally { probe.clear() }
    }

    @Test
    fun rpr028UnclaimedDecodedAccessIsClearedByCustodyRetirement() = runTest {
        val custody = LocalRpcBusinessRequestCustody(isRetired = { false })
        val probe = CurrentIdentityAccessProbe()
        var request: LocalRpcCurrentIdentityRequest? = null
        try {
            val decoded = currentIdentityProcedureWithCustody(custody).request.deserialize(probe)
                .also { request = it }
            // No callback claims this decode. This is the real serializer/census, not a Host.
            custody.deny()
            custody.destroyAfterDrain()
            probe.assertReturnedAccessCleared()
            assertTrue(decoded.isCleanupComplete, "Custody retirement did not complete owned access cleanup")
            assertFalse(decoded.claimForCallback(), "Retired access custody was revived")
        } finally { request?.destroy(); probe.clear() }
    }

    @Test
    fun rpr028RetiredCustodyRefusesAndClearsNewDecodedAccess() = runTest {
        val custody = LocalRpcBusinessRequestCustody(isRetired = { false })
        val probe = CurrentIdentityAccessProbe()
        try {
            custody.deny()
            assertFailsWith<SerializationException> {
                currentIdentityProcedureWithCustody(custody).request.deserialize(probe)
            }
            probe.assertReturnedAccessCleared()
            custody.destroyAfterDrain()
        } finally { probe.clear() }
        // Denied decoder retention is observed; original Host closing/publication is not.
    }

    @Test
    fun rpr028ClaimedAccessCleanupMustCompleteBeforeCustodyRetirement() = runTest {
        val custody = LocalRpcBusinessRequestCustody(isRetired = { false })
        val probe = CurrentIdentityAccessProbe()
        var request: LocalRpcCurrentIdentityRequest? = null
        try {
            val decoded = currentIdentityProcedureWithCustody(custody).request.deserialize(probe)
                .also { request = it }
            assertTrue(decoded.claimForCallback(), "Supporting consumer could not claim its decoded input")
            custody.deny()
            val retirement = async(start = CoroutineStart.UNDISPATCHED) { custody.destroyAfterDrain() }
            try {
                assertFalse(retirement.isCompleted, "Incomplete owned cleanup became confirmed retirement")
                decoded.destroy()
                retirement.await()
                probe.assertReturnedAccessCleared()
                assertTrue(decoded.isCleanupComplete, "Retirement completed before the owned access wipe")
            } finally { decoded.destroy(); retirement.await() }
        } finally { request?.destroy(); probe.clear() }
        // RPR-028 incomplete-cleanup/zero-owned-copy portion; no current-authority race is exercised.
    }

    @Test
    fun rpr027SelfResponseCodecHasExactlyEightFrozenIdentityGroups() {
        val response = RemotePeerCodecInputs.originalResponseData()
        val wire = Json.encodeToJsonElement(LocalRpcCurrentIdentityResponse.serializer(), response).jsonObject
        val expected = buildJsonObject {
            put("organizationId", RemotePeerCodecInputs.id("O0"))
            put("companyId", RemotePeerCodecInputs.id("CO0"))
            put("branchId", RemotePeerCodecInputs.id("B0"))
            put("actorId", RemotePeerCodecInputs.id("UA"))
            put("deviceId", RemotePeerCodecInputs.id("DA"))
            put("sessionId", RemotePeerCodecInputs.id("SA0"))
            put("sessionGeneration", RemotePeerCodecInputs.codecGeneration)
            put("admissionRevision", 0L)
        }
        assertEquals(8, wire.size)
        assertEquals(expected, wire)
        // Exact DTO field/value projection only; neither AS_A0 reuse nor genuine original identity is observed.
    }

    @Test
    fun rpr024SelfResponseRenderingOmitsFrozenSensitiveSentinels() {
        for (sentinel in RemotePeerCodecInputs.sensitiveSentinels) {
            // A sentinel-bearing DTO probes rendering only; it is not an accepted F-SELF identity.
            val data = LocalRpcCurrentIdentityResponse(sentinel, sentinel, sentinel, sentinel, sentinel, sentinel,
                RemotePeerCodecInputs.codecGeneration, 0)
            assertSentinelAbsent(data.toString(), sentinel)
        }
    }
}

/** Runs the actual field serializer and observes only the mutable array it returns. */
private class CurrentIdentityAccessProbe(private val afterAccess: () -> Unit = {}) : AbstractDecoder() {
    override val serializersModule = EmptySerializersModule()
    private val source = RemotePeerCodecInputs.credentialSentinel.encodeToByteArray()
    private val returned = mutableListOf<ByteArray>()
    private var fieldEntered = false

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (!fieldEntered) { fieldEntered = true; return 0 }
        assertEquals(1, returned.size, "The supporting decoder did not witness one access field")
        assertTrue(returned.single().contentEquals(source), "The probe did not supply the frozen unusable sentinel")
        afterAccess()
        return CompositeDecoder.DECODE_DONE
    }

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T =
        deserializer.deserialize(object : AbstractDecoder() {
            override val serializersModule = EmptySerializersModule()
            private var position = 0
            override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
                if (position < source.size) position++ else CompositeDecoder.DECODE_DONE
            override fun decodeByte(): Byte = source[position - 1]
        }).also { value -> if (value is ByteArray) returned.add(value) }

    fun assertReturnedAccessCleared() {
        assertEquals(1, returned.size, "The supporting decoder did not witness one owned access array")
        assertTrue(returned.single().all { it == 0.toByte() }, "An observed owned access array was not cleared")
    }

    fun clear() { source.fill(0); returned.forEach { it.fill(0) } }
}

private object RemotePeerCodecInputs {
    const val credentialSentinel = "SYNTH-REDACT-CREDENTIAL"
    val sensitiveSentinels = listOf(credentialSentinel, "SYNTH-REDACT-NATIVE-PRIVATE-KEY",
        "SYNTH-REDACT-ACCESS-TOKEN", "SYNTH-REDACT-REFRESH-TOKEN", "SYNTH-REDACT-PIN",
        "SYNTH-REDACT-CUSTOMER", "SYNTH-REDACT-COST", "SYNTH-REDACT-MARGIN")

    // A numeric DATA binding for GEN_A0's codec slot only. No native or SDK generation is inferred.
    const val codecGeneration = 7L
    fun id(label: String): String = UUID.nameUUIDFromBytes(label.encodeToByteArray()).toString()
    fun originalResponseData() = LocalRpcCurrentIdentityResponse(id("O0"), id("CO0"), id("B0"),
        id("UA"), id("DA"), id("SA0"), codecGeneration, 0)
}

private fun assertSentinelAbsent(representation: String, sentinel: String) {
    val bytes = sentinel.encodeToByteArray()
    val canaries = listOf(sentinel, bytes.joinToString("") { "%02x".format(it.toInt() and 255) },
        Base64.getEncoder().encodeToString(bytes))
    for (canary in canaries)
        assertFalse(representation.contains(canary), "Public rendering exposed a frozen unusable sensitive sentinel")
}
