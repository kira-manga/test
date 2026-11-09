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
 * Accepted signing errata v2 effective oracle. The v3 packet remains frozen and is read
 * independently; this loader verifies the accepted v2 manifest, its v1 parent preservation,
 * its v3 pins, the 78 active new mutation bytes and the 10 full-framed controls.
 *
 * The v2 mutations replace the 78 v3 signed-body/signed-envelope rows. The v3 rows remain
 * historical diagnostic DATA and earn no active coverage in this harness. Every active result
 * is a membership check against the packet's `accepted_codes`; `expected` is the preserved v3
 * reference only.
 */
internal class FrozenInstalledIdentitySigningV2Inputs private constructor(
    val designDirectory: Path,
    val vectors: JsonObject,
) {
    val mutations: List<JsonObject> = vectors.getValue("mutations").jsonArray.map { it.jsonObject }
    val controls: List<JsonObject> = vectors.getValue("controls").jsonArray.map { it.jsonObject }
    val canonicalPreparations: Map<String, ExpectedBytes> =
        canonicalParents("preparations")
    val canonicalSignedBodies: Map<String, ExpectedBytes> =
        canonicalParents("signed_bodies")
    val canonicalSignedEnvelopes: Map<String, ExpectedBytes> =
        canonicalParents("signed_envelopes")
    val boundaryParents: Map<String, BoundaryParent> = vectors.getValue("boundary_parents").jsonArray
        .map { it.jsonObject }
        .associate { value ->
            val id = value.text("id")
            id to BoundaryParent(
                id = id,
                targetVector = value.text("target_vector"),
                kind = value.text("kind"),
                preparation = ExpectedBytes(
                    value.hex("prep_bytes_hex"),
                    value.getValue("prep_length").jsonPrimitive.int,
                    value.text("prep_sha256"),
                ),
                signedBody = ExpectedBytes(
                    value.hex("signed_body_bytes_hex"),
                    value.getValue("signed_body_length").jsonPrimitive.int,
                    value.text("signed_body_sha256"),
                ),
                signedEnvelope = ExpectedBytes(
                    value.hex("signed_envelope_bytes_hex"),
                    value.getValue("signed_envelope_length").jsonPrimitive.int,
                    value.text("signed_envelope_sha256"),
                ),
            )
        }

    fun parentBytes(mutation: JsonObject): ByteArray {
        val artifact = mutation.text("artifact")
        val parent = mutation.text("parent")
        return when (artifact) {
            "signed_body" -> canonicalSignedBodies.getValue(parent).bytes
            "signed_envelope" -> canonicalSignedEnvelopes.getValue(parent).bytes
            else -> error("Unknown signing artifact $artifact")
        }
    }

    fun controlParentBytes(control: JsonObject): ByteArray {
        val parent = boundaryParents.getValue(control.text("parent"))
        return when (control.text("artifact")) {
            "signed_body" -> parent.signedBody.bytes
            "signed_envelope" -> parent.signedEnvelope.bytes
            else -> error("Unknown control artifact ${control.text("artifact")}")
        }
    }

    private fun canonicalParents(key: String): Map<String, ExpectedBytes> =
        vectors.getValue("canonical_parents").jsonObject.getValue(key).jsonArray
            .map { it.jsonObject }
            .associate { value ->
                value.text("id") to ExpectedBytes(
                    value.hex("bytes_hex"),
                    value.getValue("length").jsonPrimitive.int,
                    value.text("sha256"),
                )
            }

    companion object {
        private const val DESIGN_PATH =
            "qa/pos/designs/foundation-installed-application-identity-signing-errata-v2"
        private const val V1_PATH =
            "qa/pos/designs/foundation-installed-application-identity-signing-errata-v1"
        private const val V3_PATH = "qa/pos/designs/foundation-installed-application-identity-v3"
        private const val MANIFEST_SHA256 =
            "e88265dcc5f3d3426e37722e4a4d7d4507333a34d5eb96ad7f66d46f6b9bd41c"
        private const val V1_MANIFEST_SHA256 =
            "b70aa4d11533136142f44bdaace689365ed1981d302202d993ddf0b68cfe64d4"
        private const val V3_MANIFEST_SHA256 =
            "d0ca056f7104892a6b9ed47e76aab2c7d8adfda85e936785f2b45869d9a7c7d4"

        private val V2_FILES = listOf(
            "README.md", "actual-static-result.json", "cases.md", "corrections.md",
            "manifest.json", "manifest.sha256", "parent-pins.json", "pins.json",
            "validate.py", "vectors.json",
        )
        private val V1_FILES = listOf(
            "README.md", "actual-static-result.json", "cases.md", "corrections.md",
            "manifest.json", "manifest.sha256", "pins.json", "validate.py", "vectors.json",
        )
        private val V3_FILES = listOf(
            "CONTRACT.md", "IOS-ADJUDICATION.md", "P1-DECISION-RECORD.md", "PLATFORM-TRUST.md",
            "README.md", "STATIC-CHECKS.md", "cases.md", "fixtures.json", "manifest.json",
            "manifest.sha256", "validate.py", "vectors.json",
        )

        fun load(): FrozenInstalledIdentitySigningV2Inputs {
            val design = find(DESIGN_PATH)
            check(files(design) == V2_FILES) { "Accepted signing v2 directory must contain exactly ten files" }
            val manifestBytes = Files.readAllBytes(design.resolve("manifest.json"))
            check(sha256Hex(manifestBytes) == MANIFEST_SHA256) { "Accepted signing v2 manifest hash changed" }
            check(
                Files.readAllBytes(design.resolve("manifest.sha256")).decodeToString().trim() ==
                    "$MANIFEST_SHA256  manifest.json",
            ) { "Accepted signing v2 manifest.sha256 record changed" }
            val manifest = parse(manifestBytes)
            check(manifest.text("design_id") == "foundation-installed-application-identity-signing-errata-v2")
            check(manifest.text("erratum_of") == "foundation-installed-application-identity-v3")
            val declared = manifest.getValue("files").jsonArray
            check(declared.size == 8) { "Accepted signing v2 manifest must declare eight content files" }
            for (entry in declared) {
                val file = entry.jsonObject
                val name = file.text("path")
                val bytes = Files.readAllBytes(design.resolve(name))
                check(bytes.size == file.getValue("bytes").jsonPrimitive.int) { "Signing v2 bytes changed: $name" }
                check(sha256Hex(bytes) == file.text("sha256")) { "Signing v2 SHA-256 changed: $name" }
            }

            verifyV1(design)
            verifyV3(design)

            val vectorsBytes = Files.readAllBytes(design.resolve("vectors.json"))
            val vectors = parse(vectorsBytes)
            check(vectors.text("design_id") == "foundation-installed-application-identity-signing-errata-v2")
            check(vectors.text("erratum_of") == "foundation-installed-application-identity-v3")
            check(vectors.getValue("mutations").jsonArray.size == 78)
            check(vectors.getValue("controls").jsonArray.size == 10)
            val counts = vectors.getValue("counts").jsonObject
            check(counts.getValue("corrected").jsonPrimitive.int == 54)
            check(counts.getValue("retained").jsonPrimitive.int == 24)
            check(counts.getValue("mutations_total").jsonPrimitive.int == 78)
            check(counts.getValue("controls").jsonPrimitive.int == 10)
            val oracle = vectors.getValue("oracle_contract").jsonObject
            check(oracle.getValue("active_body_only_dual_refusal_rows").jsonPrimitive.int == 12)
            check(oracle.getValue("active_body_only_dual_refusal_controls").jsonPrimitive.int == 2)
            check(oracle.getValue("historical_body_only_corrected_rows").jsonPrimitive.int == 54)
            check(oracle.getValue("old_two_order_dependency_rows").jsonPrimitive.int == 30)
            check(oracle.getValue("all_codes_must_be_refusals").jsonPrimitive.content.toBoolean())
            check(vectors.getValue("runtime_status").jsonPrimitive.content == "NOT_RUN")
            check(vectors.getValue("signature_verification").jsonPrimitive.content == "NOT_RUN")
            check(vectors.getValue("p1_status").jsonPrimitive.content == "PENDING_LEAD_OWNER_CONFIRMATION")
            return FrozenInstalledIdentitySigningV2Inputs(design, vectors)
        }

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

        private fun verifyV1(v2Directory: Path) {
            val v1 = find(V1_PATH)
            check(files(v1) == V1_FILES) { "Historical signing v1 parent must contain exactly nine files" }
            val parentPins = parse(Files.readAllBytes(v2Directory.resolve("parent-pins.json")))
            check(parentPins.text("parent_manifest_sha256") == V1_MANIFEST_SHA256)
            val v1Manifest = Files.readAllBytes(v1.resolve("manifest.json"))
            check(sha256Hex(v1Manifest) == V1_MANIFEST_SHA256) { "Historical signing v1 manifest changed" }
            val declared = parentPins.getValue("files").jsonArray
            check(declared.size == 9)
            for (entry in declared) {
                val file = entry.jsonObject
                val name = file.text("path")
                val bytes = Files.readAllBytes(v1.resolve(name))
                check(bytes.size == file.getValue("bytes").jsonPrimitive.int) { "Signing v1 bytes changed: $name" }
                check(sha256Hex(bytes) == file.text("sha256")) { "Signing v1 SHA-256 changed: $name" }
            }
        }

        private fun verifyV3(v2Directory: Path) {
            val v3 = find(V3_PATH)
            check(files(v3) == V3_FILES) { "Accepted v3 directory must contain exactly twelve files" }
            val pins = parse(Files.readAllBytes(v2Directory.resolve("pins.json")))
            check(pins.text("erratum_of") == "foundation-installed-application-identity-v3")
            check(pins.text("manifest_sha256") == V3_MANIFEST_SHA256)
            val declared = pins.getValue("files").jsonArray
            check(declared.size == 12)
            for (entry in declared) {
                val file = entry.jsonObject
                val name = file.text("path")
                val bytes = Files.readAllBytes(v3.resolve(name))
                check(bytes.size == file.getValue("bytes").jsonPrimitive.int) { "Pinned v3 bytes changed: $name" }
                check(sha256Hex(bytes) == file.text("sha256")) { "Pinned v3 SHA-256 changed: $name" }
            }
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

internal data class BoundaryParent(
    val id: String,
    val targetVector: String,
    val kind: String,
    val preparation: ExpectedBytes,
    val signedBody: ExpectedBytes,
    val signedEnvelope: ExpectedBytes,
)

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.hex(key: String): ByteArray {
    val value = text(key)
    return if (value.isEmpty()) ByteArray(0) else ByteArray(value.length / 2) {
        value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
