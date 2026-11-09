package com.ukkera.pos.app.gateway.independent.sessionpresentationv1

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads the original immutable specification; it does not supply new test expectations. */
internal class FrozenSessionPresentationV1 private constructor(
    private val cases: JsonObject,
    private val fixtures: JsonObject,
) {
    internal fun assertion(caseId: String, assertionId: String, variantId: String? = null): FrozenAssertion {
        val case = cases.getValue("cases").jsonArray.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == caseId
        }.jsonObject
        val base = case.getValue("assertions").jsonArray.map { it.jsonObject }
        if (variantId == null) return FrozenAssertion(caseId, null, base.single { it.id() == assertionId })

        val variant = case.getValue("variants").jsonArray.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == variantId
        }.jsonObject
        val overrides = variant.getValue("assertion_overrides").jsonArray.map { it.jsonObject }
        overrides.singleOrNull { it.id() == assertionId }?.let {
            return FrozenAssertion(caseId, variantId, it)
        }
        check(overrides.none {
            it.getValue("replaces_base_assertion").jsonPrimitive.content == assertionId
        }) { "The frozen variant replaces $assertionId; the executor cannot restore its base expectation." }
        check(variant.getValue("inherit_base_assertions").jsonPrimitive.content == "true" ||
            variant["base_assertions_except_overrides_apply"]?.jsonPrimitive?.content == "true") {
            "The frozen variant does not inherit $assertionId."
        }
        return FrozenAssertion(caseId, variantId, base.single { it.id() == assertionId })
    }

    internal fun fixture(id: String): JsonObject = fixtures.getValue("fixtures").jsonArray.single {
        it.jsonObject.getValue("id").jsonPrimitive.content == id
    }.jsonObject

    internal fun sharedAssertion(id: String): String =
        fixtures.getValue("shared_assertions").jsonObject.getValue(id).jsonPrimitive.content

    internal companion object {
        private const val DIRECTORY = "qa/pos/freezes/local-session-presentation-v1"
        private val json = Json { ignoreUnknownKeys = false }
        private val hashes = mapOf(
            "FREEZE.json" to "3dc17f0ce7fc990156abdbfde22e8d421bab65fe71523ce302de31b9ac7b536a",
            "README.md" to "d6946fa2b7b749214ae3e94a597bf223f120ec1806b42853132fc942b940a9cd",
            "cases.json" to "600eca0f7fabc528338eb91f9fb38ce6eef40cb4e50b9c89f2696f8dc0a3f124",
            "fixtures.json" to "32f005a31903267406e83e27c502c18bcdebfe326eca995a5fbb3be4c28ffee2",
            "PUBLIC-BEHAVIOR-INPUT.md" to "3c2ac89ba4a4dcb72f11dcb2e2a7b1e619c83a2e163dd4706d953c5becf31303",
        )

        internal fun load(): FrozenSessionPresentationV1 {
            val directory = repositoryRoot().resolve(DIRECTORY)
            val bytes = hashes.mapValues { (name, expected) ->
                Files.readAllBytes(directory.resolve(name)).also {
                    check(sha256(it) == expected) { "Frozen session file hash mismatch: $name" }
                }
            }
            val freeze = json.parseToJsonElement(bytes.getValue("FREEZE.json").decodeToString()).jsonObject
            check(freeze.getValue("packet").jsonPrimitive.content == "local-session-presentation-v1")
            check(freeze.getValue("status").jsonPrimitive.content == "FROZEN_NOT_EXECUTED")
            val manifest = freeze.getValue("files").jsonArray.map { it.jsonObject }
            hashes.filterKeys { it != "FREEZE.json" }.forEach { (name, expected) ->
                val entry = manifest.single { it.getValue("path").jsonPrimitive.content == name }
                check(entry.getValue("sha256").jsonPrimitive.content == expected)
                check(entry.getValue("bytes").jsonPrimitive.content.toInt() == bytes.getValue(name).size)
            }
            val cases = json.parseToJsonElement(bytes.getValue("cases.json").decodeToString()).jsonObject
            val fixtures = json.parseToJsonElement(bytes.getValue("fixtures.json").decodeToString()).jsonObject
            check(cases.getValue("version").jsonPrimitive.content == "LSP-CASES-1")
            check(fixtures.getValue("fixture_version").jsonPrimitive.content == "LSP-FIXTURES-1")
            return FrozenSessionPresentationV1(cases, fixtures)
        }

        private fun repositoryRoot(): Path {
            val configured = System.getProperty("ukkera.pos.repositoryRoot")
            if (configured != null) {
                return Paths.get(configured).toAbsolutePath().normalize().also {
                    check(Files.isDirectory(it.resolve(DIRECTORY))) { "Configured session freeze is unavailable." }
                }
            }
            return generateSequence(Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()) { it.parent }
                .firstOrNull { Files.isDirectory(it.resolve(DIRECTORY)) }
                ?: error("Session freeze is unavailable; supply the repository root for this harness.")
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

/** The assertion object and expected text are retained directly from the hash-checked freeze. */
internal class FrozenAssertion(
    internal val caseId: String,
    internal val variantId: String?,
    internal val original: JsonObject,
) {
    internal val id: String get() = original.id()
    internal val expected: String get() = original.getValue("expected").jsonPrimitive.content

    internal fun message(observedSlice: String): String =
        "$id: $observedSlice. Frozen expectation: $expected"
}

private fun JsonObject.id(): String = getValue("id").jsonPrimitive.content
