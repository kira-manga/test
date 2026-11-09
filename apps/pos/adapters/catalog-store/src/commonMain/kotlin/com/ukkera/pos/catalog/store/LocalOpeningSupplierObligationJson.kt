package com.ukkera.pos.catalog.store

import kotlinx.serialization.builtins.serializer

/**
 * Typed JSON decoding still owns grammar and value validation. This bounded structural
 * pass first refuses ambiguous object members, including escaped aliases, which ordinary
 * JSON map decoding would otherwise replace with the last occurrence.
 */
internal fun requireUniqueOpeningSupplierObligationJsonFields(input: String) {
    OpeningSupplierObligationJsonFields(input).validate()
}

private class OpeningSupplierObligationJsonFields(private val input: String) {
    private var position = 0

    fun validate() {
        value(0)
        whitespace()
        require(position == input.length)
    }

    private fun value(depth: Int) {
        require(depth < 64)
        whitespace()
        require(position < input.length)
        when (input[position]) {
            '{' -> objectValue(depth)
            '[' -> arrayValue(depth)
            '"' -> quotedString()
            else -> {
                val start = position
                while (position < input.length && input[position] !in " \t\r\n,]}") position++
                require(position > start)
            }
        }
    }

    private fun objectValue(depth: Int) {
        position++
        whitespace()
        if (consume('}')) return
        val names = mutableSetOf<String>()
        while (true) {
            whitespace()
            val name = purchasingJson.decodeFromString(String.serializer(), quotedString())
            require(names.add(name)) { "Duplicate opening supplier-obligation JSON field" }
            whitespace()
            require(consume(':'))
            value(depth + 1)
            whitespace()
            if (consume('}')) return
            require(consume(','))
        }
    }

    private fun arrayValue(depth: Int) {
        position++
        whitespace()
        if (consume(']')) return
        while (true) {
            value(depth + 1)
            whitespace()
            if (consume(']')) return
            require(consume(','))
        }
    }

    private fun quotedString(): String {
        val start = position
        require(consume('"'))
        while (position < input.length) {
            when (input[position++]) {
                '"' -> return input.substring(start, position)
                '\\' -> { require(position < input.length); position++ }
            }
        }
        throw IllegalArgumentException("Unterminated opening supplier-obligation JSON string")
    }

    private fun whitespace() {
        while (position < input.length && input[position] in " \t\r\n") position++
    }

    private fun consume(character: Char): Boolean {
        if (position >= input.length || input[position] != character) return false
        position++
        return true
    }
}
