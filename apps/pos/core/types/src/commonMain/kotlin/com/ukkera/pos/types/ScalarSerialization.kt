package com.ukkera.pos.types

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/** Lenient JSON string decoding may accept number tokens; scalar wire types must not depend on that setting. */
internal fun Decoder.strictScalarString(type: String, maximumLength: Int): String {
    val text = if (this is JsonDecoder) {
        val element = decodeJsonElement()
        if (element !is JsonPrimitive || !element.isString) {
            throw SerializationException("$type must be a string")
        }
        element.content
    } else {
        decodeString()
    }
    if (text.length > maximumLength) throw SerializationException("$type exceeds its wire length bound")
    return text
}

internal inline fun <T> decodeScalar(
    decoder: Decoder,
    type: String,
    maximumLength: Int,
    parse: (String) -> T,
): T {
    val text = decoder.strictScalarString(type, maximumLength)
    return try {
        parse(text)
    } catch (error: IllegalArgumentException) {
        throw SerializationException("Invalid $type", error)
    } catch (error: ArithmeticException) {
        throw SerializationException("Invalid $type", error)
    }
}
