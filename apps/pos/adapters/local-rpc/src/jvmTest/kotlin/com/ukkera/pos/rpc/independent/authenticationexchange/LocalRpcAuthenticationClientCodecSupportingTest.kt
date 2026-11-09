@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.rpc.independent.authenticationexchange

import com.ukkera.pos.rpc.ClientAuthenticationCodec
import com.ukkera.pos.rpc.LocalRpcAuthenticationAction
import com.ukkera.pos.rpc.LocalRpcAuthenticationRequest
import com.ukkera.pos.rpc.LocalRpcAuthenticationResponse
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalSessionTokens
import dev.p2pkit.rpc.RpcRetrySafety
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Frozen REMP-C04 concrete Client serializer-custody slices, plus C03's no-reinvoke setting.
 * These exercise the production ClientAuthenticationCodec, not a replacement lifecycle.
 * Controlled decoder outputs and a deterministic thread barrier establish no connected Client,
 * native admission, Host invocation, worker census, output commitment or remote receipt.
 */
class LocalRpcAuthenticationClientCodecSupportingTest {
    @Test
    fun rempC03ConcreteClientProcedureDeclaresNeverReinvoke() {
        withCodec { codec, _ -> assertEquals(RpcRetrySafety.NeverReinvoke, codec.procedure.retrySafety) }
        // Configuration only; exact one-shot physical Host/service invocation remains unbound.
    }

    @Test
    fun rempC04ConcreteClientRequestSerializerClearsOwnedPasswordAfterEncoding() {
        withCodec { codec, request ->
            val password = checkNotNull(request.password)
            Json.encodeToJsonElement(codec.procedure.request, request)
            assertTrue(password.all { it == '\u0000' }, "Client encoder retained decoded request secrets")
            assertRemoteRedacted(request)
        }
    }

    @Test
    fun rempC04ConcreteClientRequestSerializerClearsOwnedPasswordAfterFailure() {
        withCodec { codec, request ->
            val password = checkNotNull(request.password)
            assertFailsWith<SerializationException> { codec.procedure.request.serialize(FailedRequestEncoder(), request) }
            assertTrue(password.all { it == '\u0000' }, "Failed Client encoder retained decoded request secrets")
        }
    }

    @Test
    fun rempC04HeldDecodedResponseIsClearedOnAbandonmentAndCannotBeTransferred() {
        withCodec { codec, _ ->
            val buffers = AuthenticationDecodedBuffers()
            try {
                val response = codec.procedure.response.deserialize(buffers.decoder())
                buffers.assertPresent()
                codec.destroy()
                buffers.assertCleared()
                assertFailsWith<IllegalStateException> { codec.transfer(response) }
                codec.destroy()
                buffers.assertCleared()
                assertRemoteRedacted(response)
            } finally { buffers.clear() }
        }
    }

    @Test
    fun rempC04TransferredResponseKeepsConsumerOwnershipAfterCodecDisposal() {
        withCodec { codec, _ ->
            val buffers = AuthenticationDecodedBuffers()
            var response: LocalRpcAuthenticationResponse? = null
            var tokens: LocalSessionTokens? = null
            try {
                val decoded = codec.procedure.response.deserialize(buffers.decoder()).also { response = it }
                assertSame(decoded, codec.transfer(decoded))
                assertFailsWith<IllegalStateException> { codec.transfer(decoded) }
                codec.destroy()
                buffers.assertPresent()
                val received = assertIs<LocalAuthenticationResult.Authenticated>(decoded.authenticationResult()).tokens.also { tokens = it }
                buffers.assertCleared()
                assertTrue(received.access.useBytes { it.any { byte -> byte != 0.toByte() } },
                    "Codec disposal destroyed the separately transferred credential owner")
                assertTrue(received.refresh.useBytes { it.any { byte -> byte != 0.toByte() } },
                    "Codec disposal destroyed the separately transferred credential owner")
                assertRemoteRedacted(codec, decoded, received, received.access, received.refresh)
            } finally { tokens?.destroy(); response?.destroy(); buffers.clear() }
        }
    }

    @Test
    fun rempC04DisposalWinningBeforeLateDecodeCompletionCannotReopenCustody() {
        withCodec { codec, request ->
            val buffers = AuthenticationDecodedBuffers()
            val barrier = DecodeCompletionBarrier()
            val executor = Executors.newSingleThreadExecutor()
            val late = executor.submit<LocalRpcAuthenticationResponse> {
                codec.procedure.response.deserialize(buffers.decoder(afterFields = barrier::pause))
            }
            try {
                assertTrue(barrier.entered.await(5, TimeUnit.SECONDS), "Response decoder did not reach its completion barrier")
                buffers.assertPresent()
                codec.destroy()
                assertTrue(checkNotNull(request.password).all { it == '\u0000' }, "Disposed codec retained its original request")
                barrier.release.countDown()
                val failure = assertFailsWith<ExecutionException> { late.get(5, TimeUnit.SECONDS) }
                assertIs<SerializationException>(failure.cause)
                buffers.assertCleared()
                codec.destroy()
                buffers.assertCleared()
            } finally {
                barrier.release.countDown()
                codec.destroy()
                late.cancel(true)
                executor.shutdownNow()
                check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Controlled decoder thread did not settle" }
                buffers.clear()
            }
        }
        // This proves the concrete codec's closed-before-late-publication cut, not Host/native retirement.
    }

    @Test
    fun rempC02C04WrongResponseActionIsRefusedAndItsDecodedCredentialsAreCleared() {
        withCodec { codec, _ ->
            val buffers = AuthenticationDecodedBuffers()
            try {
                assertFailsWith<SerializationException> {
                    codec.procedure.response.deserialize(buffers.decoder(action = LocalRpcAuthenticationAction.REFRESH))
                }
                buffers.assertCleared()
            } finally { buffers.clear() }
        }
    }

    @Test
    fun rempC04SecondDecodedInstanceCannotReplaceOriginalHeldResponse() {
        withCodec { codec, _ ->
            val original = AuthenticationDecodedBuffers()
            val replacement = AuthenticationDecodedBuffers()
            try {
                val first = codec.procedure.response.deserialize(original.decoder())
                assertFailsWith<SerializationException> { codec.procedure.response.deserialize(replacement.decoder()) }
                replacement.assertCleared()
                original.assertPresent()
                assertSame(first, codec.transfer(first))
                first.destroy()
                original.assertCleared()
            } finally { codec.destroy(); original.clear(); replacement.clear() }
        }
        // Multiple decoder completions are not multiple authentication-service invocations.
    }

    @Test
    fun rempC04CancelledConcreteResponseDecodeClearsBuffersAndOriginalDisposalIsFinal() {
        withCodec { codec, request ->
            val cancelled = AuthenticationDecodedBuffers()
            val late = AuthenticationDecodedBuffers()
            try {
                assertFailsWith<CancellationException> {
                    codec.procedure.response.deserialize(cancelled.decoder(
                        afterFields = { throw CancellationException("Controlled response decode cancellation") }))
                }
                cancelled.assertCleared()
                codec.destroy()
                assertTrue(checkNotNull(request.password).all { it == '\u0000' }, "Cancelled codec disposal retained request secrets")
                assertFailsWith<SerializationException> { codec.procedure.response.deserialize(late.decoder()) }
                late.assertCleared()
            } finally { cancelled.clear(); late.clear() }
        }
        // Cancellation injected into the concrete decoder does not qualify the admitted Client worker path.
    }
}

private inline fun withCodec(block: (ClientAuthenticationCodec, LocalRpcAuthenticationRequest) -> Unit) {
    val request = Json.decodeFromJsonElement(LocalRpcAuthenticationRequest.serializer(), RemoteEmployeeAuthenticationFrozen.requestWire("login"))
    val codec = ClientAuthenticationCodec(request)
    try { block(codec, request) } finally { codec.destroy(); request.destroy() }
}

private class DecodeCompletionBarrier {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    fun pause() {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "Controlled response decode was not released" }
    }
}

private class FailedRequestEncoder : AbstractEncoder() {
    override val serializersModule = EmptySerializersModule()
    override fun encodeValue(value: Any): Unit = throw SerializationException("Controlled request encoding failure")
}
