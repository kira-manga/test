package com.ukkera.pos.sales.store

import com.ukkera.pos.database.StoredBytes
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.text.CharacterCodingException

internal val localSalesJson: Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
    encodeDefaults = true
    isLenient = false
    coerceInputValues = false
}

/** This is a wire bound, not permission to truncate a financial document. */
internal const val LOCAL_SALES_MAX_BYTES: Int = 65_536
internal class LocalSalesWireCapacityExceeded : IllegalArgumentException("sales.body.too_large")

internal fun <T> localSalesEncode(value: T, serializer: KSerializer<T>, maximum: Int = LOCAL_SALES_MAX_BYTES): StoredBytes {
    val bytes = try {
        localSalesJson.encodeToString(serializer, value).encodeToByteArray(throwOnInvalidSequence = true)
    } catch (_: SerializationException) {
        throw IllegalArgumentException("sales.body.invalid")
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("sales.body.invalid")
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("sales.body.invalid")
    }
    if (bytes.size > minOf(maximum, LOCAL_SALES_MAX_BYTES)) throw LocalSalesWireCapacityExceeded()
    return StoredBytes(bytes)
}

internal fun <T> localSalesDecode(body: StoredBytes, serializer: KSerializer<T>, maximum: Int = LOCAL_SALES_MAX_BYTES): T {
    return localSalesDecodeBounded(body, serializer, maximum, previewWhitespace = false)
}

/** Fresh advisory input only; stored/history/mutation codecs remain exactly canonical. */
internal fun <T> localSalesDecodePreview(body: StoredBytes, serializer: KSerializer<T>, maximum: Int): T {
    return localSalesDecodeBounded(body, serializer, maximum, previewWhitespace = true)
}

private fun <T> localSalesDecodeBounded(body: StoredBytes, serializer: KSerializer<T>, maximum: Int,
    previewWhitespace: Boolean): T {
    require(body.size <= maximum) { "sales.body.too_large" }
    val text = try {
        body.copyBytes().decodeToString(throwOnInvalidSequence = true)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("sales.body.invalid")
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("sales.body.invalid")
    }
    // Bound the object graph before invoking the recursive JSON decoder. Text inside
    // quotes is evidence, not structure. Every public array has a smaller domain bound.
    val stack = mutableListOf<Pair<Char, Int>>()
    var quoted = false
    var escaped = false
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> {
                require(stack.size < 24) { "sales.body.too_deep" }
                stack += character to 0
            }
            '}', ']' -> {
                require(stack.isNotEmpty() && stack.last().first == (if (character == ']') '[' else '{')) { "sales.body.invalid" }
                stack.removeAt(stack.lastIndex)
            }
            ',' -> if (stack.isNotEmpty() && stack.last().first == '[') {
                val count = stack.last().second + 1
                require(count < 1_024) { "sales.body.too_large" }
                stack[stack.lastIndex] = '[' to count
            }
        }
    }
    require(stack.isEmpty() && !quoted) { "sales.body.invalid" }
    val value = try {
        localSalesJson.decodeFromString(serializer, text)
    } catch (_: SerializationException) {
        throw IllegalArgumentException("sales.body.invalid")
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("sales.body.invalid")
    }
    val canonical = localSalesJson.encodeToString(serializer, value)
    // Parse the ORIGINAL text before removing formatting whitespace. Compacting
    // first could repair malformed tokens (for example 1 2 into 12). Comparison
    // still rejects duplicate/missing fields and alternative numeric/string forms.
    val comparable = if (previewWhitespace) jsonFormattingRemoved(text) else text
    require(canonical == comparable) { "sales.body.non_canonical" }
    return value
}

private fun jsonFormattingRemoved(text: String): String = buildString(text.length) {
    var quoted = false
    var escaped = false
    for (character in text) {
        if (quoted) {
            append(character)
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else {
            if (character == '"') quoted = true
            if (character != ' ' && character != '\t' && character != '\r' && character != '\n') append(character)
        }
    }
}

internal fun localLabel(value: String) {
    require(value.isNotBlank() && value.length <= 80) { "sales.label.invalid" }
}

internal fun localReason(value: String) {
    require(value.isNotBlank() && value.length <= 256) { "sales.reason.invalid" }
}
