package com.ukkera.pos.sales.store.independent.reportcompletev2

/** Test observation of the public quoted-CSV/CRLF container, independent of its encoder. */
internal fun observeQuotedReportCsv(bytes: ByteArray): List<List<String>> {
    val text = bytes.decodeToString(throwOnInvalidSequence = true)
    val records = mutableListOf<List<String>>()
    var index = 0
    while (index < text.length) {
        val fields = mutableListOf<String>()
        while (true) {
            check(index < text.length && text[index++] == '"') { "Expected a quoted CSV field" }
            val field = StringBuilder()
            while (true) {
                check(index < text.length) { "Unterminated quoted CSV field" }
                val character = text[index++]
                if (character != '"') {
                    field.append(character)
                } else if (index < text.length && text[index] == '"') {
                    index++
                    field.append('"')
                } else break
            }
            fields += field.toString()
            if (index < text.length && text[index] == ',') {
                index++
            } else {
                check(index + 1 < text.length && text[index] == '\r' && text[index + 1] == '\n') {
                    "Expected a CRLF record terminator"
                }
                index += 2
                break
            }
        }
        if (records.isNotEmpty()) check(fields.size == records.first().size) { "CSV field count changed" }
        records += fields
    }
    return records
}
