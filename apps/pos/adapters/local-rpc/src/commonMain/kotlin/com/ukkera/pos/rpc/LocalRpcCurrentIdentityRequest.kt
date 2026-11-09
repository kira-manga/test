package com.ukkera.pos.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

internal const val CURRENT_IDENTITY_REQUEST_LIMIT_BYTES = 512

/** Exact access-only wire input. Each decoded buffer belongs to its callback or decoder custody. */
@Serializable(with = LocalRpcCurrentIdentityRequestSerializer::class)
internal class LocalRpcCurrentIdentityRequest(val access: ByteArray) {
    private enum class Lifetime { UNCLAIMED, CLAIMED, DESTROYING, DESTROYED }
    private val lifetime = MutableStateFlow(Lifetime.UNCLAIMED)
    private val cleanupCompleted = CompletableDeferred<Unit>()

    internal val isCleanupComplete: Boolean get() = lifetime.value == Lifetime.DESTROYED

    /** Claim at actual callback entry, before admission or any other suspension. */
    internal fun claimForCallback(): Boolean = lifetime.compareAndSet(Lifetime.UNCLAIMED, Lifetime.CLAIMED)

    /** A request already claimed by its callback cannot be cleared by ordinary expiry. */
    internal fun destroyIfUnclaimed() {
        if (lifetime.compareAndSet(Lifetime.UNCLAIMED, Lifetime.DESTROYING)) finishDestruction()
    }

    /** Only decoder/refusal, the entered callback, or drained-owner cleanup ends this input. */
    fun destroy() {
        while (true) {
            val before = lifetime.value
            if (before == Lifetime.DESTROYING || before == Lifetime.DESTROYED) return
            if (lifetime.compareAndSet(before, Lifetime.DESTROYING)) {
                finishDestruction()
                return
            }
        }
    }

    internal suspend fun awaitCleanup() { cleanupCompleted.await() }

    private fun finishDestruction() {
        try { access.fill(0) }
        catch (failure: Throwable) {
            cleanupCompleted.completeExceptionally(failure)
            throw failure
        }
        lifetime.value = Lifetime.DESTROYED
        cleanupCompleted.complete(Unit)
    }

    override fun toString(): String = "LocalRpcCurrentIdentityRequest([REDACTED])"
}

/** Same serial name/field/ByteArray representation, with ownership during failed decoding. */
@OptIn(ExperimentalSerializationApi::class)
internal object LocalRpcCurrentIdentityRequestSerializer : KSerializer<LocalRpcCurrentIdentityRequest> {
    override val descriptor = buildClassSerialDescriptor("com.ukkera.pos.rpc.LocalRpcCurrentIdentityRequest") {
        element<ByteArray>("access")
    }

    override fun serialize(encoder: Encoder, value: LocalRpcCurrentIdentityRequest) = encoder.encodeStructure(descriptor) {
        encodeSerializableElement(descriptor, 0, ByteArraySerializer(), value.access)
    }

    override fun deserialize(decoder: Decoder): LocalRpcCurrentIdentityRequest {
        var access: ByteArray? = null
        try {
            decoder.decodeStructure(descriptor) {
                if (decodeSequentially()) {
                    access = decodeSerializableElement(descriptor, 0, CurrentIdentityAccessSerializer)
                } else {
                    while (true) when (val index = decodeElementIndex(descriptor)) {
                        CompositeDecoder.DECODE_DONE -> break
                        0 -> {
                            // Preserve the generated codec's last-value behavior without losing
                            // an earlier owned array when a repeated field replaces it.
                            access?.fill(0)
                            access = decodeSerializableElement(descriptor, index, CurrentIdentityAccessSerializer)
                        }
                        else -> throw SerializationException("Unexpected self-session request field")
                    }
                }
            }
            return LocalRpcCurrentIdentityRequest(access ?: throw SerializationException("Missing self-session access"))
                .also { access = null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Decoder failures may contain input text. Do not retain their message or cause.
            throw SerializationException("Malformed self-session request")
        } finally { access?.fill(0) }
    }
}

/** Own partial bytes from the first element; a valid wire request cannot exceed its byte limit. */
@OptIn(ExperimentalSerializationApi::class)
private object CurrentIdentityAccessSerializer : KSerializer<ByteArray> {
    override val descriptor = ByteArraySerializer().descriptor
    override fun serialize(encoder: Encoder, value: ByteArray) = ByteArraySerializer().serialize(encoder, value)

    override fun deserialize(decoder: Decoder): ByteArray {
        val scratch = ByteArray(CURRENT_IDENTITY_REQUEST_LIMIT_BYTES)
        var count = 0
        try {
            decoder.decodeStructure(descriptor) {
                if (decodeSequentially()) {
                    val size = decodeCollectionSize(descriptor)
                    if (size !in 0..scratch.size) throw SerializationException("Self-session access exceeds request bounds")
                    repeat(size) { index -> scratch[index] = decodeByteElement(descriptor, index) }
                    count = size
                } else {
                    while (true) {
                        val index = decodeElementIndex(descriptor)
                        if (index == CompositeDecoder.DECODE_DONE) break
                        if (index != count || count >= scratch.size)
                            throw SerializationException("Self-session access exceeds request bounds")
                        scratch[count++] = decodeByteElement(descriptor, index)
                    }
                }
            }
            return scratch.copyOf(count)
        } finally { scratch.fill(0) }
    }
}
