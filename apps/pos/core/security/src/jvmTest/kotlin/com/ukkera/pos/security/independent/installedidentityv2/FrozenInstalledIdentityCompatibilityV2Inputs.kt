package com.ukkera.pos.security.independent.installedidentityv2

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Accepted compatibility erratum v2 packet. The loader verifies the exact nine-file manifest,
 * the eight-file rejected v1 parent preservation, the twelve-file v3 pin and every frozen count.
 * It does not turn any DATA vector into runtime, native, Keychain, signature or owner evidence.
 */
internal class FrozenInstalledIdentityCompatibilityV2Inputs private constructor(
    val designDirectory: Path,
    val vectors: JsonObject,
) {
    val legacyLabelVectors: List<JsonObject> = rows("legacy_label_vectors")
    val v2BranchVectors: List<JsonObject> = rows("v2_branch_vectors")
    val macosSigningVectors: List<JsonObject> = rows("macos_signing_vectors")
    val utf8Vectors: List<JsonObject> = rows("utf8_vectors")
    val roleTagVectors: List<JsonObject> = rows("role_tag_vectors")
    val legacyCapVectors: List<JsonObject> = rows("legacy_cap_vectors")
    val specialFactoryCells: List<JsonObject> = rows("special_factory_cells")
    val unsupportedTargets: List<JsonObject> = rows("unsupported_targets")
    val crossVersion: List<JsonObject> = rows("cross_version")
    val iosOracles: List<JsonObject> = rows("ios_oracles")
    val nativeObservations: List<JsonObject> = rows("native_observations")
    val canonical42: List<JsonObject> = rows("canonical42")
    val factoryCells: List<JsonObject> =
        vectors.getValue("factory_matrix").jsonObject.getValue("cells").jsonArray.map { it.jsonObject }

    fun text(key: String): String = vectors.getValue(key).jsonPrimitive.content

    private fun rows(key: String): List<JsonObject> =
        vectors.getValue(key).jsonArray.map { it.jsonObject }

    companion object {
        private const val DESIGN_PATH =
            "qa/pos/designs/foundation-installed-application-identity-compatibility-errata-v2"
        private const val V1_PATH =
            "qa/pos/designs/foundation-installed-application-identity-compatibility-errata-v1"
        private const val V3_PATH = "qa/pos/designs/foundation-installed-application-identity-v3"
        private const val MANIFEST_SHA256 =
            "f5be38034c57aada255f319d1942c488f38877f51ba868720574074490f7c0f5"
        private const val V1_MANIFEST_SHA256 =
            "cc19e6a96574e9d82263d9cec92c469442020e9e54445f58bc9d35af4a6f594b"
        private const val V3_MANIFEST_SHA256 =
            "d0ca056f7104892a6b9ed47e76aab2c7d8adfda85e936785f2b45869d9a7c7d4"

        private val V2_FILES = listOf(
            "CASES.md", "ERRATUM.md", "README.md", "STATIC-CHECKS.md", "manifest.json",
            "manifest.sha256", "parent-pins.json", "validate.py", "vectors.json",
        )
        private val V1_FILES = listOf(
            "CASES.md", "ERRATUM.md", "README.md", "STATIC-CHECKS.md", "manifest.json",
            "manifest.sha256", "validate.py", "vectors.json",
        )
        private val V3_FILES = listOf(
            "CONTRACT.md", "IOS-ADJUDICATION.md", "P1-DECISION-RECORD.md", "PLATFORM-TRUST.md",
            "README.md", "STATIC-CHECKS.md", "cases.md", "fixtures.json", "manifest.json",
            "manifest.sha256", "validate.py", "vectors.json",
        )

        fun load(): FrozenInstalledIdentityCompatibilityV2Inputs {
            val design = find(DESIGN_PATH)
            check(files(design) == V2_FILES) { "Compatibility v2 directory must contain exactly nine files" }
            val manifestBytes = Files.readAllBytes(design.resolve("manifest.json"))
            check(sha256Hex(manifestBytes) == MANIFEST_SHA256) { "Compatibility v2 manifest hash changed" }
            check(
                Files.readAllBytes(design.resolve("manifest.sha256")).decodeToString().trim() ==
                    "$MANIFEST_SHA256  manifest.json",
            ) { "Compatibility v2 manifest.sha256 record changed" }
            val manifest = parse(manifestBytes)
            check(manifest.text("design_id") ==
                "foundation-installed-application-identity-compatibility-errata-v2")
            check(manifest.text("status") == "FROZEN_ERRATUM_DESIGN_NOT_RUN")
            check(manifest.text("runtime_status") == "NOT_RUN")
            check(manifest.text("policy_status") == "P1_PENDING")
            check(manifest.text("preserved_manifest_sha256") == V3_MANIFEST_SHA256)
            val declared = manifest.getValue("files").jsonArray
            check(declared.size == 7) { "Compatibility v2 manifest must declare seven content files" }
            for (entry in declared) verifyFile(design, entry.jsonObject, "compatibility v2")
            verifyV1(design)
            verifyV3(design, manifest)
            verifyCounts(manifest)

            val vectors = parse(Files.readAllBytes(design.resolve("vectors.json")))
            check(vectors.text("design_id") ==
                "foundation-installed-application-identity-compatibility-errata-v2")
            check(vectors.text("runtime_status") == "NOT_RUN")
            check(vectors.text("policy_status") == "P1_PENDING")
            check(vectors.getValue("p1").jsonObject.text("status") == "PENDING_LEAD_OWNER_CONFIRMATION")
            check(vectors.getValue("no_authority").jsonObject.getValue("grants").jsonArray.isEmpty())
            check(vectors.getValue("no_authority").jsonObject
                .getValue("refusal_side_effects").jsonObject.values.all { it.jsonPrimitive.content == "0" })
            check(vectors.getValue("native_observations").jsonArray.all {
                it.jsonObject.text("representation") == "NATIVE_ONLY_NOT_DATA" &&
                    it.jsonObject.text("current_result") == "NOT_RUN"
            })
            check(vectors.getValue("trim_whitespace_set_codepoints").jsonArray.size == 28)
            return FrozenInstalledIdentityCompatibilityV2Inputs(design, vectors)
        }

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

        private fun verifyCounts(manifest: JsonObject) {
            val counts = manifest.getValue("counts").jsonObject
            val expected = mapOf(
                "canonical42" to 42, "legacy_label_vectors" to 76, "v2_branch_vectors" to 16,
                "macos_signing_vectors" to 19, "utf8_vectors" to 24, "role_tag_vectors" to 8,
                "legacy_cap_vectors" to 2, "factory_cells" to 36, "special_factory_cells" to 6,
                "unsupported_targets" to 7, "cross_version" to 12, "ios_oracles" to 15,
                "native_observations" to 5, "cases" to 14,
            )
            for ((key, value) in expected) {
                check(counts.getValue(key).jsonPrimitive.int == value) { "Compatibility count changed: $key" }
            }
        }

        private fun verifyV1(design: Path) {
            val v1 = find(V1_PATH)
            check(files(v1) == V1_FILES) { "Compatibility v1 parent must contain exactly eight files" }
            val parent = parse(Files.readAllBytes(design.resolve("parent-pins.json")))
            check(parent.text("parent_manifest_sha256") == V1_MANIFEST_SHA256)
            check(sha256Hex(Files.readAllBytes(v1.resolve("manifest.json"))) == V1_MANIFEST_SHA256)
            val declared = parent.getValue("files").jsonArray
            check(declared.size == 8)
            for (entry in declared) verifyFile(v1, entry.jsonObject, "compatibility v1")
        }

        private fun verifyV3(design: Path, manifest: JsonObject) {
            val v3 = find(V3_PATH)
            check(files(v3) == V3_FILES) { "Accepted v3 directory must contain exactly twelve files" }
            val declared = manifest.getValue("preserved_v3").jsonArray
            check(declared.size == 12)
            for (entry in declared) verifyFile(v3, entry.jsonObject, "v3")
            check(manifest.text("preserved_manifest_file_sha256") ==
                sha256Hex(Files.readAllBytes(v3.resolve("manifest.sha256"))))
        }

        private fun verifyFile(directory: Path, entry: JsonObject, label: String) {
            val name = entry.text("path")
            val bytes = Files.readAllBytes(directory.resolve(name))
            check(bytes.size == entry.getValue("bytes").jsonPrimitive.int) { "$label bytes changed: $name" }
            check(sha256Hex(bytes) == entry.text("sha256")) { "$label SHA-256 changed: $name" }
        }

        private fun find(relative: String): Path =
            generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
                .map { it.resolve(relative) }
                .firstOrNull { Files.isRegularFile(it.resolve("manifest.json")) }
                ?: error("Frozen packet is unavailable from the test working directory: $relative")

        private fun files(directory: Path): List<String> =
            Files.list(directory).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }

        private fun parse(bytes: ByteArray): JsonObject =
            Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
