package com.ukkera.pos.types

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The caller explicitly selects a versioned normalization scheme before uniqueness checks.
 * Only exact string identity is implemented here. No implicit trimming, case folding, Unicode
 * normalization, barcode symbology interpretation or numeric conversion is approved by this type.
 */
@Serializable
public enum class CodeNormalization {
    EXACT_V1,
}

@Serializable
public data class Code(
    @Serializable(with = CodeTextSerializer::class)
    public val original: String,
    @Serializable(with = CodeTextSerializer::class)
    public val normalizedKey: String,
    public val normalization: CodeNormalization,
) {
    init {
        validateCodePair(original, normalizedKey, normalization)
    }

    public companion object {
        public fun exact(original: String): Code = Code(original, original, CodeNormalization.EXACT_V1)
    }
}

/** Barcode namespace/symbology and company-wide uniqueness belong to the owning catalog contract. */
@Serializable
public data class Barcode(
    @Serializable(with = CodeTextSerializer::class)
    public val original: String,
    @Serializable(with = CodeTextSerializer::class)
    public val normalizedKey: String,
    public val normalization: CodeNormalization,
) {
    init {
        validateCodePair(original, normalizedKey, normalization)
    }

    public companion object {
        public fun exact(original: String): Barcode = Barcode(original, original, CodeNormalization.EXACT_V1)
    }
}

private fun validateCodePair(original: String, key: String, normalization: CodeNormalization) {
    validateCodeText(original)
    validateCodeText(key)
    when (normalization) {
        CodeNormalization.EXACT_V1 -> require(key == original) { "Code key does not match its normalization version" }
    }
}

private fun validateCodeText(text: String) {
    require(text.isNotEmpty() && text.length <= 256) { "Code text must contain 1..256 UTF-16 code units" }
    var index = 0
    while (index < text.length) {
        val code = text[index].code
        when (code) {
            in 0xD800..0xDBFF -> {
                require(index + 1 < text.length && text[index + 1].code in 0xDC00..0xDFFF) {
                    "Code text contains an unpaired Unicode surrogate"
                }
                index += 2
            }
            in 0xDC00..0xDFFF -> throw IllegalArgumentException("Code text contains an unpaired Unicode surrogate")
            else -> index++
        }
    }
}

public object CodeTextSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CodeText", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        validateCodeText(value)
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String = decodeScalar(decoder, "CodeText", 256) { text ->
        validateCodeText(text)
        text
    }
}
