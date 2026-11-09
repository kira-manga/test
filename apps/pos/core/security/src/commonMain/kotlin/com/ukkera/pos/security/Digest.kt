package com.ukkera.pos.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * SHA-256 syntax only, NOT authenticated evidence. A gateway recomputes the hash from its own
 * canonical semantic bytes. Accepting a digest supplied in a request does not verify its payload.
 */
@Serializable(with = PayloadDigestSerializer::class)
public data class PayloadDigest(public val value: String) {
    init {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "SHA-256 must be 64 canonical lowercase hexadecimal characters"
        }
    }
}

public object PayloadDigestSerializer : KSerializer<PayloadDigest> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PayloadDigest", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: PayloadDigest): Unit = encoder.encodeString(value.value)
    override fun deserialize(decoder: Decoder): PayloadDigest {
        val text = if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element !is JsonPrimitive || !element.isString) {
                throw SerializationException("SHA-256 must be a canonical hexadecimal string")
            }
            element.content
        } else {
            decoder.decodeString()
        }
        return try {
            PayloadDigest(text)
        } catch (_: IllegalArgumentException) {
            throw SerializationException("Invalid SHA-256 syntax")
        }
    }
}

public typealias Sha256Digest = PayloadDigest

/** Platform-reviewed digest primitive, not password hashing, encryption, signing, or key storage. */
public expect fun sha256(bytes: ByteArray): PayloadDigest

internal fun ByteArray.hexDigest(): PayloadDigest =
    PayloadDigest(joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
