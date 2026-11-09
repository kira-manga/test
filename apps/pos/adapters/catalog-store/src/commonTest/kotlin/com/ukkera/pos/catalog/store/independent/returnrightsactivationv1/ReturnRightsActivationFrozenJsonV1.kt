package com.ukkera.pos.catalog.store.independent.returnrightsactivationv1

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Byte-preserving edits of the frozen canonical literals, without decoding/re-encoding the wire. */
internal object ReturnRightsActivationFrozenJsonV1 {
    data class Span(val start: Int, val end: Int)
    private data class Member(val key: String, val start: Int, val end: Int, val value: Span)

    fun valueSpan(raw: String, path: String): Span {
        var span = Span(0, raw.length)
        if (path.isEmpty()) return span
        require(path.startsWith('/'))
        path.substring(1).split('/').forEach { part ->
            span = when (raw[span.start]) {
                '{' -> members(raw, span).single { it.key == part }.value
                '[' -> elements(raw, span)[part.toInt()]
                else -> error("Path does not identify an object or array")
            }
        }
        return span
    }

    fun value(raw: String, path: String): String = valueSpan(raw, path).let { raw.substring(it.start, it.end) }

    fun replace(raw: String, path: String, token: String): String {
        val span = valueSpan(raw, path)
        return raw.substring(0, span.start) + token + raw.substring(span.end)
    }

    fun patch(raw: String, objectPath: String, fields: Map<String, JsonElement>): String {
        var result = raw
        fields.forEach { (key, value) -> result = replace(result, "$objectPath/$key", token(value)) }
        return result
    }

    fun omit(raw: String, path: String): String {
        val parentPath = path.substringBeforeLast('/', "")
        val key = path.substringAfterLast('/')
        val parent = valueSpan(raw, parentPath)
        val values = members(raw, parent)
        require(values.count { it.key == key } == 1)
        val replacement = values.filterNot { it.key == key }.joinToString(",", "{", "}") {
            raw.substring(it.start, it.end)
        }
        return replace(raw, parentPath, replacement)
    }

    fun insert(raw: String, objectPath: String, key: String, token: String): String {
        val parent = valueSpan(raw, objectPath)
        require(raw[parent.start] == '{' && raw[parent.end - 1] == '}')
        val comma = if (members(raw, parent).isEmpty()) "" else ","
        return raw.substring(0, parent.end - 1) + comma + quoted(key) + ":" + token + raw.substring(parent.end - 1)
    }

    fun duplicate(raw: String, objectPath: String, key: String): String {
        val parent = valueSpan(raw, objectPath)
        val member = members(raw, parent).single { it.key == key }
        return raw.substring(0, parent.end - 1) + "," + raw.substring(member.start, member.end) + raw.substring(parent.end - 1)
    }

    fun swap(raw: String, objectPath: String, first: String, second: String): String {
        val values = members(raw, valueSpan(raw, objectPath)).toMutableList()
        val a = values.indexOfFirst { it.key == first }
        val b = values.indexOfFirst { it.key == second }
        require(a >= 0 && b >= 0 && a != b)
        val retained = values[a]
        values[a] = values[b]
        values[b] = retained
        return replace(raw, objectPath, values.joinToString(",", "{", "}") { raw.substring(it.start, it.end) })
    }

    fun reverseArray(raw: String, path: String): String {
        val values = elements(raw, valueSpan(raw, path))
        return replace(raw, path, values.reversed().joinToString(",", "[", "]") { raw.substring(it.start, it.end) })
    }

    fun arrayValues(raw: String, path: String): List<String> = elements(raw, valueSpan(raw, path)).map {
        raw.substring(it.start, it.end)
    }

    fun token(value: JsonElement): String =
        if (value is JsonPrimitive && value.isString) quoted(value.content) else value.toString()

    /** Literal paired Unicode is preserved; invalid isolated code units can be represented as wire escapes. */
    fun quoted(value: String): String = buildString {
        append('"')
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character.code < 0x20 -> append("\\u" + character.code.toString(16).padStart(4, '0'))
                character.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                    append(character)
                    append(value[++index])
                }
                character.isHighSurrogate() || character.isLowSurrogate() ->
                    append("\\u" + character.code.toString(16).padStart(4, '0'))
                else -> append(character)
            }
            index++
        }
        append('"')
    }

    fun hexBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun byteOffset(raw: String, characterOffset: Int): Int =
        raw.substring(0, characterOffset).encodeToByteArray(throwOnInvalidSequence = true).size

    fun insertBytes(source: ByteArray, offset: Int, inserted: ByteArray): ByteArray =
        source.copyOfRange(0, offset) + inserted + source.copyOfRange(offset, source.size)

    private fun members(raw: String, objectSpan: Span): List<Member> {
        require(raw[objectSpan.start] == '{' && raw[objectSpan.end - 1] == '}')
        val values = mutableListOf<Member>()
        var index = objectSpan.start + 1
        while (index < objectSpan.end - 1) {
            val keyStart = index
            val keyEnd = stringEnd(raw, keyStart)
            val key = raw.substring(keyStart + 1, keyEnd - 1)
            require('\\' !in key && raw[keyEnd] == ':')
            val valueStart = keyEnd + 1
            val end = valueEnd(raw, valueStart)
            values += Member(key, keyStart, end, Span(valueStart, end))
            index = end
            if (index < objectSpan.end - 1) {
                require(raw[index] == ',')
                index++
            }
        }
        require(index == objectSpan.end - 1)
        return values
    }

    private fun elements(raw: String, arraySpan: Span): List<Span> {
        require(raw[arraySpan.start] == '[' && raw[arraySpan.end - 1] == ']')
        val values = mutableListOf<Span>()
        var index = arraySpan.start + 1
        while (index < arraySpan.end - 1) {
            val end = valueEnd(raw, index)
            values += Span(index, end)
            index = end
            if (index < arraySpan.end - 1) {
                require(raw[index] == ',')
                index++
            }
        }
        require(index == arraySpan.end - 1)
        return values
    }

    private fun valueEnd(raw: String, start: Int): Int = when (raw[start]) {
        '"' -> stringEnd(raw, start)
        '{', '[' -> {
            val closing = if (raw[start] == '{') '}' else ']'
            var index = start + 1
            while (raw[index] != closing) {
                index = when (raw[index]) {
                    '"' -> stringEnd(raw, index)
                    '{', '[' -> valueEnd(raw, index)
                    else -> index + 1
                }
            }
            index + 1
        }
        else -> {
            var index = start
            while (index < raw.length && raw[index] != ',' && raw[index] != '}' && raw[index] != ']') index++
            index
        }
    }

    private fun stringEnd(raw: String, start: Int): Int {
        require(raw[start] == '"')
        var index = start + 1
        while (index < raw.length) {
            if (raw[index] == '\\') index += 2
            else if (raw[index++] == '"') return index
        }
        error("Unterminated fixture string")
    }
}
