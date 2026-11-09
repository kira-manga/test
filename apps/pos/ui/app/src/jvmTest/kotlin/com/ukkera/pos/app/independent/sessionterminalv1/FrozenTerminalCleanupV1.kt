package com.ukkera.pos.app.independent.sessionterminalv1

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads the frozen packet; these bindings do not amend its whole-case expectations. */
internal class FrozenTerminalCleanupV1 private constructor(
    private val cases: String,
    private val fixtures: JsonObject,
) {
    fun case(id: String): String {
        val marker = "### $id —"
        check(cases.contains(marker)) { "The assigned frozen terminal case is unavailable: $id" }
        val section = cases.substringAfter(marker).substringBefore("\n### ")
        check(section.contains("**Expected:**")) { "The frozen expected result is unavailable: $id" }
        return "$id / bounded JVM lifecycle slice"
    }

    fun id(name: String): DomainId = DomainId(fixtures.getValue("identities").jsonObject
        .getValue(name).jsonPrimitive.content)
    fun privateCanary(index: Int): String = fixtures.getValue("F-STC-PRIVATE").jsonObject
        .getValue("a_private_canaries").jsonArray[index].jsonPrimitive.content
    fun aOrder(): DomainId = DomainId(fixtures.getValue("F-STC-PRIVATE").jsonObject
        .getValue("a_order").jsonPrimitive.content)
    fun paymentOperation(): OperationId = OperationId(fixtures.getValue("F-STC-RETAIL").jsonObject
        .getValue("operation").jsonPrimitive.content)
    fun failed(name: String): Boolean = fixtures.getValue("F-STC-LIFECYCLE").jsonObject
        .getValue("expected_failure").jsonObject.getValue(name).jsonPrimitive.boolean
    fun failedCount(name: String): Int = fixtures.getValue("F-STC-LIFECYCLE").jsonObject
        .getValue("expected_failure").jsonObject.getValue(name).jsonPrimitive.int

    companion object {
        private const val DIRECTORY = "qa/pos/freezes/session-terminal-cleanup-v1"

        fun load(): FrozenTerminalCleanupV1 {
            val directory = repositoryRoot().resolve(DIRECTORY)
            check(Files.readString(directory.resolve("README.md")).contains("FROZEN v1")) {
                "Terminal expectations must remain frozen before this harness is run."
            }
            val fixtures = Json.parseToJsonElement(Files.readString(directory.resolve("fixtures.json"))).jsonObject
            check(fixtures.getValue("design_id").jsonPrimitive.content == "session-terminal-cleanup")
            return FrozenTerminalCleanupV1(Files.readString(directory.resolve("cases.md")), fixtures)
        }

        private fun repositoryRoot(): Path = generateSequence(Paths.get("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve(DIRECTORY)) }
            ?: error("The frozen terminal-cleanup packet is unavailable from this test checkout.")
    }
}
