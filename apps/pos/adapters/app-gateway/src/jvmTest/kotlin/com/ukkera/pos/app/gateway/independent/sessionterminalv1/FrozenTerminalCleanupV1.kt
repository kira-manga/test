package com.ukkera.pos.app.gateway.independent.sessionterminalv1

import com.ukkera.pos.types.DomainId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads the unchanged frozen packet, not an implementation-derived replacement oracle. */
internal class FrozenTerminalCleanupV1 private constructor(
    private val cases: String,
    private val fixtures: JsonObject,
) {
    fun case(id: String): String {
        val marker = "### $id —"
        check(cases.contains(marker)) { "The assigned frozen terminal case is unavailable: $id" }
        check(cases.substringAfter(marker).substringBefore("\n### ").contains("**Expected:**")) {
            "The frozen terminal expected result is unavailable: $id"
        }
        return "$id / bounded JVM gateway slice"
    }

    fun id(name: String): DomainId = DomainId(fixtures.getValue("identities").jsonObject
        .getValue(name).jsonPrimitive.content)
    fun retailProduct(): DomainId = DomainId(fixtures.getValue("F-STC-RETAIL").jsonObject
        .getValue("product").jsonPrimitive.content)
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
