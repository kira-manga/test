@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.rpc.independent.authenticationexchange

import com.ukkera.pos.rpc.LocalRpcAuthenticationAction
import com.ukkera.pos.rpc.LocalRpcAuthenticationPayload
import com.ukkera.pos.rpc.LocalRpcAuthenticationRequest
import com.ukkera.pos.rpc.LocalRpcAuthenticationResponse
import com.ukkera.pos.rpc.LocalRpcAuthenticationResponseSerializer
import com.ukkera.pos.rpc.LocalRpcAuthenticationStatus
import com.ukkera.pos.rpc.LocalRpcSessionView
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalSessionTokens
import com.ukkera.pos.security.OpaqueCredential
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.CharArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Frozen REMP-C04 mutable-buffer ownership slices. JSON cases use the actual production codec;
 * staged-failure cases use that same serializer with an observing decoder/failed encoder.
 * Only arrays actually returned to the serializer are observed. Immutable codec intermediates,
 * SDK packets, automatic Host cleanup and private binding memory are not claimed erased.
 */
class LocalRpcAuthenticationOwnershipSupportingTest {
    @Test fun rempC04DecodedLoginDestroyClearsItsOwnedPassword() = decodedRequestCleanup("login")
    @Test fun rempC04DecodedRefreshDestroyClearsItsOwnedBearer() = decodedRequestCleanup("refresh")
    @Test fun rempC04DecodedCurrentDestroyClearsItsOwnedBearer() = decodedRequestCleanup("current")
    @Test fun rempC04DecodedLogoutDestroyClearsItsOwnedBearer() = decodedRequestCleanup("logout")

    @Test
    fun rempC04UnclaimedDecodedRequestExpiryClearsSecretsAndRefusesLateClaim() {
        withEachDecodedRequest { id, request ->
            assertTrue(request.validateShape())
            assertRequestSecretFromFrozen(id, request)
            request.destroyIfUnclaimed()
            assertTrue(request.isDestroyed)
            assertFalse(request.validateShape())
            assertFalse(request.claimForCallback(), "Expired decoded request regained callback custody")
            assertDecodedRequestCleared(id, request)
            request.destroyIfUnclaimed()
            request.destroy()
            assertFalse(request.claimForCallback(), "Repeated cleanup revived decoded request custody")
            assertDecodedRequestCleared(id, request)
        }
        // Actual DTO custody cuts only; no Host callback or native admission is invoked.
    }

    @Test
    fun rempC04ClaimedDecodedRequestCustodySurvivesExpiryUntilTerminalCleanup() {
        withEachDecodedRequest { id, request ->
            assertTrue(request.claimForCallback())
            assertFalse(request.claimForCallback(), "Decoded request admitted a second custody claim")
            assertRequestSecretFromFrozen(id, request)
            request.destroyIfUnclaimed()
            request.destroyIfUnclaimed()
            assertFalse(request.isDestroyed)
            assertTrue(request.validateShape())
            assertRequestSecretFromFrozen(id, request)
            request.destroy()
            assertTrue(request.isDestroyed)
            assertFalse(request.validateShape())
            assertDecodedRequestCleared(id, request)
            request.destroy()
            request.destroyIfUnclaimed()
            assertFalse(request.claimForCallback(), "Terminal cleanup revived claimed request custody")
            assertDecodedRequestCleared(id, request)
        }
        // The concrete request owner is observed, not automatic Host/service lifecycle execution.
    }

    @Test
    fun rempC04SerializedResponseClearsItsOwnedPairOnSuccess() {
        withInspectedResponse { fixture ->
            Json.encodeToJsonElement(LocalRpcAuthenticationResponseSerializer, fixture.response)
            fixture.assertCleared()
            assertFailsWith<SerializationException> { fixture.response.takePayload() }
            fixture.response.destroy()
            fixture.assertCleared()
        }
    }

    @Test
    fun rempC04FailedSerializationClearsItsOwnedPairWithoutRevival() {
        withInspectedResponse { fixture ->
            assertFailsWith<SerializationException> {
                LocalRpcAuthenticationResponseSerializer.serialize(FailedAuthenticationEncoder(), fixture.response)
            }
            fixture.assertCleared()
            assertFailsWith<SerializationException> { fixture.response.takePayload() }
            fixture.response.destroy()
            fixture.assertCleared()
        }
    }

    @Test
    fun rempC04AbandonedNeverSerializedResponseDisposalClearsItsOwnedPair() {
        withInspectedResponse { fixture ->
            fixture.response.destroy()
            fixture.assertCleared()
            fixture.response.destroy()
            assertFailsWith<SerializationException> { fixture.response.takePayload() }
            assertRemoteRedacted(fixture.response, fixture.payload)
        }
        // The real disposal hook is tested; automatic owner/Host abandonment remains separate.
    }

    @Test
    fun rempC04DecodedResponseConsumptionClearsWireCopiesButTransfersPrivateCredentials() {
        val buffers = AuthenticationDecodedBuffers()
        var response: LocalRpcAuthenticationResponse? = null
        var tokens: LocalSessionTokens? = null
        try {
            val reply = LocalRpcAuthenticationResponseSerializer.deserialize(buffers.decoder()).also { response = it }
            val received = assertIs<LocalAuthenticationResult.Authenticated>(reply.authenticationResult()).tokens.also { tokens = it }
            buffers.assertCleared()
            val expectedAccess = RemoteEmployeeAuthenticationFrozen.originalAccess()
            val expectedRefresh = RemoteEmployeeAuthenticationFrozen.originalRefresh()
            try {
                assertTrue(received.access.useBytes { it.contentEquals(expectedAccess) }, "Transferred access credential changed")
                assertTrue(received.refresh.useBytes { it.contentEquals(expectedRefresh) }, "Transferred refresh credential changed")
            } finally { expectedAccess.fill(0); expectedRefresh.fill(0) }
            assertRemoteRedacted(reply, received, received.access, received.refresh)
        } finally { tokens?.destroy(); response?.destroy(); buffers.clear() }
    }

    @Test
    fun rempC04StagedRequestMissingIdentityClearsAlreadyDecodedPassword() {
        val password = RemoteEmployeeAuthenticationFrozen.request("login").string("password").toCharArray()
        try {
            val decoder = AuthenticationFieldsDecoder(listOf(1, 2, 3, 5), password = password,
                strings = mapOf(2 to RemoteEmployeeAuthenticationFrozen.request("login").string("loginIdentifier")))
            assertFailsWith<SerializationException> { LocalRpcAuthenticationRequest.serializer().deserialize(decoder) }
            assertTrue(password.all { it == '\u0000' }, "Staged request retained its decoded password")
        } finally { password.fill('\u0000') }
    }

    @Test
    fun rempC04StagedRequestCancellationClearsAlreadyDecodedSecretBuffers() {
        val password = RemoteEmployeeAuthenticationFrozen.request("login").string("password").toCharArray()
        val bearer = RemoteEmployeeAuthenticationFrozen.originalAccess()
        try {
            val decoder = AuthenticationFieldsDecoder(listOf(0, 1, 2, 3, 4, 5), password = password,
                byteFields = mapOf(4 to bearer), strings = requestStrings(),
                afterFields = { throw CancellationException("Controlled decode cancellation") })
            assertFailsWith<CancellationException> { LocalRpcAuthenticationRequest.serializer().deserialize(decoder) }
            assertTrue(password.all { it == '\u0000' }, "Cancelled request decoder retained its password")
            assertTrue(bearer.all { it == 0.toByte() }, "Cancelled request decoder retained its bearer")
        } finally { password.fill('\u0000'); bearer.fill(0) }
        // This is serializer unwinding, not cancellation after admitted authentication work.
    }

    @Test
    fun rempC04StagedResponseMissingStatusClearsAlreadyDecodedPair() {
        val buffers = AuthenticationDecodedBuffers()
        try {
            assertFailsWith<SerializationException> {
                LocalRpcAuthenticationResponseSerializer.deserialize(buffers.decoder(indices = listOf(0, 3, 4, 5)))
            }
            buffers.assertCleared()
        } finally { buffers.clear() }
    }

    @Test
    fun rempC04StagedResponseCancellationClearsAlreadyDecodedPair() {
        val buffers = AuthenticationDecodedBuffers()
        try {
            assertFailsWith<CancellationException> {
                LocalRpcAuthenticationResponseSerializer.deserialize(buffers.decoder(
                    afterFields = { throw CancellationException("Controlled decode cancellation") }))
            }
            buffers.assertCleared()
        } finally { buffers.clear() }
    }

    @Test
    fun rempC02C04CurrentCannotOwnLoginCredentialsAndRejectedPayloadClearsPair() {
        val buffers = AuthenticationDecodedBuffers()
        try {
            assertFailsWith<IllegalArgumentException> {
                LocalRpcAuthenticationResponseSerializer.deserialize(buffers.decoder(action = LocalRpcAuthenticationAction.CURRENT,
                    status = LocalRpcAuthenticationStatus.CURRENT))
            }
            buffers.assertCleared()
        } finally { buffers.clear() }
    }

    @Test
    fun rempC04InvalidActionCaptureStillDestroysTransferredServiceCredentials() {
        val access = RemoteEmployeeAuthenticationFrozen.originalAccess()
        val refresh = RemoteEmployeeAuthenticationFrozen.originalRefresh()
        val tokens = LocalSessionTokens(RemoteEmployeeAuthenticationFrozen.view(), OpaqueCredential(access), OpaqueCredential(refresh))
        try {
            assertFailsWith<IllegalArgumentException> {
                LocalRpcAuthenticationResponse.capture(LocalRpcAuthenticationAction.CURRENT, LocalAuthenticationResult.Authenticated(tokens))
            }
            assertRemoteCredentialDestroyed(tokens.access)
            assertRemoteCredentialDestroyed(tokens.refresh)
            val originalAccess = RemoteEmployeeAuthenticationFrozen.originalAccess()
            val originalRefresh = RemoteEmployeeAuthenticationFrozen.originalRefresh()
            try {
                assertTrue(access.contentEquals(originalAccess), "Caller-owned input bytes changed during rejected transfer")
                assertTrue(refresh.contentEquals(originalRefresh), "Caller-owned input bytes changed during rejected transfer")
            } finally { originalAccess.fill(0); originalRefresh.fill(0) }
            assertRemoteRedacted(tokens)
        } finally { tokens.destroy(); access.fill(0); refresh.fill(0) }
    }

    private fun decodedRequestCleanup(id: String) {
        val request = Json.decodeFromJsonElement(LocalRpcAuthenticationRequest.serializer(), RemoteEmployeeAuthenticationFrozen.requestWire(id))
        val password = request.password
        val bearer = request.bearer
        try {
            assertTrue(request.validateShape())
            assertRemoteRedacted(request)
            request.destroy()
            request.destroy()
            if (id == "login") assertTrue(assertNotNull(password).all { it == '\u0000' }, "Decoded password was not cleared")
            else assertTrue(assertNotNull(bearer).all { it == 0.toByte() }, "Decoded bearer was not cleared")
        } finally { request.destroy() }
    }

    private fun withEachDecodedRequest(block: (String, LocalRpcAuthenticationRequest) -> Unit) {
        for (id in listOf("login", "refresh", "current", "logout")) {
            val request = Json.decodeFromJsonElement(LocalRpcAuthenticationRequest.serializer(),
                RemoteEmployeeAuthenticationFrozen.requestWire(id))
            try { block(id, request) }
            finally { request.destroy() }
        }
    }

    private fun assertRequestSecretFromFrozen(id: String, request: LocalRpcAuthenticationRequest) {
        if (id == "login") {
            val expected = RemoteEmployeeAuthenticationFrozen.request(id).string("password").toCharArray()
            try { assertTrue(assertNotNull(request.password).contentEquals(expected), "Owned decoded password changed") }
            finally { expected.fill('\u0000') }
        } else {
            val expected = RemoteEmployeeAuthenticationFrozen.requestBearer(id)
            try { assertTrue(assertNotNull(request.bearer).contentEquals(expected), "Owned decoded bearer changed") }
            finally { expected.fill(0) }
        }
    }

    private fun assertDecodedRequestCleared(id: String, request: LocalRpcAuthenticationRequest) {
        if (id == "login") assertTrue(assertNotNull(request.password).all { it == '\u0000' }, "Owned decoded password was retained")
        else assertTrue(assertNotNull(request.bearer).all { it == 0.toByte() }, "Owned decoded bearer was retained")
    }
}

/** Decoder-created mutable outputs, not caller-owned credentials or issued service results. */
internal class AuthenticationDecodedBuffers {
    val access: ByteArray = RemoteEmployeeAuthenticationFrozen.originalAccess()
    val refresh: ByteArray = RemoteEmployeeAuthenticationFrozen.originalRefresh()
    private val view = LocalRpcSessionView.capture(RemoteEmployeeAuthenticationFrozen.view())

    fun decoder(
        action: LocalRpcAuthenticationAction = LocalRpcAuthenticationAction.LOGIN,
        status: LocalRpcAuthenticationStatus = LocalRpcAuthenticationStatus.AUTHENTICATED,
        indices: List<Int> = listOf(0, 1, 3, 4, 5),
        afterFields: (() -> Unit)? = null,
    ): AuthenticationFieldsDecoder = AuthenticationFieldsDecoder(indices, action, status,
        byteFields = mapOf(4 to access, 5 to refresh), view = view, afterFields = afterFields)

    fun assertCleared() {
        assertTrue(access.all { it == 0.toByte() }, "Owned decoded access buffer was retained")
        assertTrue(refresh.all { it == 0.toByte() }, "Owned decoded refresh buffer was retained")
    }
    fun assertPresent() {
        assertTrue(access.any { it != 0.toByte() }, "Owned decoded access buffer was prematurely cleared")
        assertTrue(refresh.any { it != 0.toByte() }, "Owned decoded refresh buffer was prematurely cleared")
    }
    fun clear() { access.fill(0); refresh.fill(0) }
}

/** Real staged serializer, with observable returned arrays and a deterministic completion cut. */
internal class AuthenticationFieldsDecoder(
    private val indices: List<Int>,
    private val action: LocalRpcAuthenticationAction = LocalRpcAuthenticationAction.LOGIN,
    private val status: LocalRpcAuthenticationStatus = LocalRpcAuthenticationStatus.AUTHENTICATED,
    private val password: CharArray? = null,
    private val byteFields: Map<Int, ByteArray> = emptyMap(),
    private val strings: Map<Int, String> = emptyMap(),
    private val view: LocalRpcSessionView? = null,
    private val afterFields: (() -> Unit)? = null,
) : AbstractDecoder() {
    override val serializersModule = EmptySerializersModule()
    private var position = 0
    private var field = -1
    private var completed = false

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (position < indices.size) return indices[position++].also { field = it }
        if (!completed) { completed = true; afterFields?.invoke() }
        return CompositeDecoder.DECODE_DONE
    }
    override fun decodeString(): String = strings.getValue(field)
    override fun decodeBoolean(): Boolean = false
    override fun decodeNotNullMark(): Boolean = true
    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = when (enumDescriptor.serialName) {
        LocalRpcAuthenticationAction.serializer().descriptor.serialName -> action.ordinal
        LocalRpcAuthenticationStatus.serializer().descriptor.serialName -> status.ordinal
        else -> error("Unsupported controlled enum")
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T = when (deserializer.descriptor.serialName) {
        CharArraySerializer().descriptor.serialName -> checkNotNull(password) as T
        ByteArraySerializer().descriptor.serialName -> byteFields.getValue(field) as T
        LocalRpcSessionView.serializer().descriptor.serialName -> checkNotNull(view) as T
        else -> super.decodeSerializableValue(deserializer)
    }
}

private class FailedAuthenticationEncoder : AbstractEncoder() {
    override val serializersModule = EmptySerializersModule()
    override fun encodeValue(value: Any) = Unit
    override fun encodeNull() = Unit
    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        if (descriptor.serialName == LocalRpcAuthenticationPayload.serializer().descriptor.serialName && index == 5)
            throw SerializationException("Controlled authentication encoding failure")
        return true
    }
}

private class InspectedAuthenticationResponse(val response: LocalRpcAuthenticationResponse, val payload: LocalRpcAuthenticationPayload) {
    fun assertCleared() {
        assertTrue(assertNotNull(payload.access).all { it == 0.toByte() }, "Response-owned access buffer was retained")
        assertTrue(assertNotNull(payload.refresh).all { it == 0.toByte() }, "Response-owned refresh buffer was retained")
    }
}

private inline fun withInspectedResponse(block: (InspectedAuthenticationResponse) -> Unit) {
    val access = RemoteEmployeeAuthenticationFrozen.originalAccess()
    val refresh = RemoteEmployeeAuthenticationFrozen.originalRefresh()
    val tokens = LocalSessionTokens(RemoteEmployeeAuthenticationFrozen.view(), OpaqueCredential(access), OpaqueCredential(refresh))
    var response: LocalRpcAuthenticationResponse? = null
    try {
        val captured = LocalRpcAuthenticationResponse.capture(LocalRpcAuthenticationAction.LOGIN, LocalAuthenticationResult.Authenticated(tokens))
        // Observe only the real payload arrays; return ownership to the production response owner.
        val payload = captured.takePayload()
        val owner = LocalRpcAuthenticationResponse.decoded(payload).also { response = it }
        block(InspectedAuthenticationResponse(owner, payload))
    } finally { response?.destroy(); tokens.destroy(); access.fill(0); refresh.fill(0) }
}

private fun requestStrings(): Map<Int, String> = RemoteEmployeeAuthenticationFrozen.request("login").let {
    mapOf(0 to it.string("exchangeId"), 2 to it.string("loginIdentifier"))
}
