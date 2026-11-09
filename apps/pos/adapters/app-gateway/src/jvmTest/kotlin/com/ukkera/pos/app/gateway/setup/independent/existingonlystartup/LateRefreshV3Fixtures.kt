package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Frozen late-refresh-v3 loader. The eleven files are byte-identical to the reviewed
 * design candidate (canonical manifest
 * 2258d5eb53c92415eb2a58bb1625d677d851a43073bccc2cd9018cfcf31a49a6). The inventory and
 * fixtures are read as DATA and are never rewritten; no expectation is authored here.
 */
internal object LateRefreshV3Fixtures {
    private const val DIRECTORY = "foundation-existing-only-startup-late-refresh-v3"
    private const val CASE_COUNT = 4
    private const val VARIANT_COUNT = 9
    private const val ALTERNATE_COUNT = 6
    private const val BRANCH_OUTCOME_COUNT = 10
    private const val OPTIONAL_DIAGNOSTIC_COUNT = 1
    private const val MANIFEST_DIGEST =
        "2258d5eb53c92415eb2a58bb1625d677d851a43073bccc2cd9018cfcf31a49a6"
    private val json = Json

    private val expectedFiles: Map<String, Pair<Int, String>> = mapOf(
        "README.md" to (30412 to "77e0a51638a99a5455200762157c634e18241e6b7ff119f65230885dd09f1c81"),
        "artifact-sha256.txt" to (833 to "16395ca12cc7b9ae32d91e76bb278f643204d179f55a0578006ebfc2ddba7e91"),
        "cases-late-refresh.md" to (31070 to "7771f7c1880a2481c8664aab21d45e454d08e61a7599674f4ea3064a32823ef4"),
        "coverage-late-refresh.md" to (30620 to "0ce7c16e5030d20c94dfdf1399c27d2747aa3dab8448f9e2da2245b895fd9c02"),
        "fixtures.json" to (53179 to "85e9a73d0b5c6a34bcb9f45cb5b4d2ee4c545ec11fc82a86ca639133a044bae3"),
        "inventory.json" to (195324 to "e233041c9fc674a3ec6885a85c38749c3d9dec563d207a1a2e767d7b7900b348"),
        "lineage.json" to (10325 to "29bb3a68635eaae024073aae31fdb66cc31743ed995624798477c63cc30a9cd6"),
        "negative-controls.txt" to (10117 to "9236665352a7012327b3846bbe61d5eba319ae4a5eb1b5600414c5ebefd2d159"),
        "predecessor-pins.json" to (13319 to "a84af03129d500b7df509c66c7d6dd967156b1dded23d867b31d5f76bdbe3bb6"),
        "static-result.txt" to (1411 to "15e052346ee8afa5f9b0f787bcd11015b3aeb68dec5e71b8b55ddd1c3814e065"),
        "validate.py" to (101638 to "07fdc3b5628ec2cfed0f78177a21d1be4c91043af740caa68d9d38cb2a0db570"),
    )

    private val expectedCaseIds = listOf("E3LR-L1", "E3LR-L2", "E3LR-L3", "E3LR-L4")
    private val allowedClasses = setOf("REFRESH_SAME_ORIGINAL", "REFRESH_REFUSED_ACCOUNTED")

    private val design: Path = locateDesign()
    private val inventory: JsonObject
    private val fixtures: JsonObject

    val cases: List<LateRefreshCase>
    val variants: Map<String, LateRefreshVariant>

    init {
        verifyFiles()
        inventory = readJson(design.resolve("inventory.json"))
        fixtures = readJson(design.resolve("fixtures.json"))
        require(inventory.requiredString("version") == "late-refresh-v3") {
            "late inventory is not late-refresh-v3"
        }
        require(inventory.requiredString("status") == "NOT RUN") {
            "late inventory must remain NOT RUN"
        }
        require(fixtures.requiredString("version") == "late-refresh-v3") {
            "late fixtures are not late-refresh-v3"
        }
        require(fixtures.requiredString("status") == "NOT RUN") {
            "late fixtures must remain NOT RUN"
        }
        require(inventory.requiredInt("case_count") == CASE_COUNT) { "late case count changed" }
        require(inventory.requiredInt("variant_count") == VARIANT_COUNT) { "late variant count changed" }
        val loadedCases = inventory.requiredArray("cases").map { element ->
            val raw = element.jsonObject
            val caseId = raw.requiredString("case_id")
            val variants = raw.requiredArray("variants").map { variantElement ->
                LateRefreshVariant(caseId, raw.requiredString("title"), variantElement.jsonObject)
            }
            LateRefreshCase(caseId, raw.requiredString("title"), variants)
        }
        require(loadedCases.size == CASE_COUNT) { "late case list changed" }
        require(loadedCases.map { it.caseId } == expectedCaseIds) { "late case IDs/order changed" }
        val flat = loadedCases.flatMap { it.variants }
        require(flat.size == VARIANT_COUNT) { "late flat variant count changed" }
        require(flat.map { it.variantId }.distinct().size == VARIANT_COUNT) {
            "late variant IDs are not unique"
        }
        flat.forEach { variant ->
            require(variant.expectedClass in allowedClasses) {
                "${variant.variantId} has an unexpected class ${variant.expectedClass}"
            }
            require(variant.status == "NOT RUN") { "${variant.variantId} must remain NOT RUN" }
            require(variant.disposition == "new") { "${variant.variantId} must be disposition=new" }
            require(variant.family == "REFRESH") { "${variant.variantId} must be family=REFRESH" }
            require(variant.checkpointForced.not()) { "${variant.variantId} forces the optional read" }
            require(variant.checkpointOptional) { "${variant.variantId} setup read must be optional" }
            require(variant.checkpointOptionalRead == "readOriginalSetup") {
                "${variant.variantId} has an unexpected optional read"
            }
        }
        cases = loadedCases
        variants = flat.associateBy { it.variantId }
        val counts = inventory.requiredObject("counts")
        require(counts.requiredObject("by_expected_class").mapValues {
            it.value.jsonPrimitive.content.toInt()
        } == mapOf("REFRESH_REFUSED_ACCOUNTED" to 7, "REFRESH_SAME_ORIGINAL" to 2)) {
            "late expected-class counts differ from the frozen inventory"
        }
        require(counts.requiredInt("conditional_alternate_variants") == ALTERNATE_COUNT) {
            "late alternate count differs from the frozen inventory"
        }
        require(counts.requiredInt("conditional_branch_outcomes") == BRANCH_OUTCOME_COUNT) {
            "late branch-outcome count differs from the frozen inventory"
        }
        require(counts.requiredInt("optional_boundary_diagnostics") == OPTIONAL_DIAGNOSTIC_COUNT) {
            "late optional-diagnostic count differs from the frozen inventory"
        }
        require(flat.sumOf { it.alternateOutcomes.size } == ALTERNATE_COUNT) {
            "late alternate rows differ from the frozen count"
        }
        require(flat.sumOf { it.branchOutcomes.size } == BRANCH_OUTCOME_COUNT) {
            "late branch rows differ from the frozen count"
        }
        require(flat.count { it.optionalBoundaryDiagnostic } == OPTIONAL_DIAGNOSTIC_COUNT) {
            "late optional diagnostic rows differ from the frozen count"
        }
        require(inventory.requiredObject("base").requiredInt("v8_case_count") == 11 &&
            inventory.requiredObject("base").requiredInt("v8_variant_count") == 52 &&
            inventory.requiredObject("base").requiredInt("v8_conditional_alternates") == 8) {
            "late packet no longer pins the accepted v8 counts"
        }
        verifyHumanInventoryAgreement(loadedCases)
    }

    fun case(caseId: String): LateRefreshCase =
        cases.singleOrNull { it.caseId == caseId } ?: error("Unknown late case $caseId")

    fun recordProfile(id: String): JsonObject =
        fixtures.requiredObject("late_refresh").requiredArray("late_record_profiles")
            .map { it.jsonObject }.singleOrNull { it.requiredString("id") == id }
            ?: error("Unknown late record profile $id")

    fun lifecycleProfile(id: String): JsonObject =
        fixtures.requiredObject("late_refresh").requiredArray("late_lifecycle_profiles")
            .map { it.jsonObject }.singleOrNull { it.requiredString("id") == id }
            ?: error("Unknown late lifecycle profile $id")

    private fun verifyFiles() {
        require(expectedFiles.size == 11) { "frozen late file list must be eleven" }
        val manifestJson = expectedFiles.entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
            (name, spec) -> "\"$name\":{\"bytes\":${spec.first},\"sha256\":\"${spec.second}\"}"
        }
        require(sha256(manifestJson.encodeToByteArray()) == MANIFEST_DIGEST) {
            "Frozen late eleven-file manifest digest changed"
        }
        var total = 0
        for ((name, spec) in expectedFiles) {
            val path = design.resolve(name)
            require(Files.isRegularFile(path)) { "Missing frozen late file $path" }
            val bytes = Files.readAllBytes(path)
            total += bytes.size
            require(bytes.size == spec.first) { "Frozen late file $name byte length changed" }
            require(sha256(bytes) == spec.second) { "Frozen late file $name SHA-256 changed" }
        }
        require(total == 478248) { "Frozen late packet total byte count changed: $total" }
        val manifest = Files.readAllLines(design.resolve("artifact-sha256.txt"))
            .filter { it.isNotBlank() }
            .associate { line ->
                val parts = line.trim().split(Regex("\\s+"))
                require(parts.size == 2) { "Malformed artifact-sha256.txt line: $line" }
                parts[1] to parts[0]
            }
        require(manifest.size == 10) { "artifact-sha256.txt must list ten files" }
        for ((name, digest) in manifest) {
            require(expectedFiles[name]?.second == digest) { "artifact manifest differs for $name" }
            require(sha256(Files.readAllBytes(design.resolve(name))) == digest) {
                "artifact manifest hash does not match $name"
            }
        }
    }

    private fun verifyHumanInventoryAgreement(loadedCases: List<LateRefreshCase>) {
        val casesText = Files.readString(design.resolve("cases-late-refresh.md"))
        for (case in loadedCases) {
            require("### ${case.caseId}" in casesText) {
                "cases-late-refresh.md is missing heading for ${case.caseId}"
            }
            for (variant in case.variants) {
                val line = casesText.lineSequence().firstOrNull { variant.variantId in it } ?: ""
                require(line.isNotEmpty()) { "cases-late-refresh.md is missing ${variant.variantId}" }
                require(variant.expectedClass in line) {
                    "cases-late-refresh.md row ${variant.variantId} does not show ${variant.expectedClass}"
                }
            }
        }
    }

    private fun locateDesign(): Path {
        val starts = listOfNotNull(
            System.getProperty("user.dir")?.let { Path.of(it).toAbsolutePath().normalize() },
            Path.of("").toAbsolutePath().normalize(),
        ).distinct()
        for (start in starts) {
            var current: Path? = start
            while (current != null) {
                val candidate = current.resolve("qa/pos/designs").resolve(DIRECTORY)
                if (Files.isRegularFile(candidate.resolve("inventory.json")) &&
                    Files.isRegularFile(candidate.resolve("fixtures.json"))) {
                    return candidate
                }
                current = current.parent
            }
        }
        error("Frozen late-refresh-v3 design was not found under qa/pos/designs")
    }

    private fun readJson(path: Path): JsonObject {
        require(Files.isRegularFile(path)) { "Missing frozen late file $path" }
        return json.parseToJsonElement(Files.readString(path)).jsonObject
    }
}

internal data class LateRefreshCase(
    val caseId: String,
    val title: String,
    val variants: List<LateRefreshVariant>,
)

internal class LateRefreshVariant(
    val caseId: String,
    val caseTitle: String,
    val raw: JsonObject,
) {
    val variantId: String get() = raw.requiredString("variant_id")
    val kind: String get() = raw.requiredString("kind")
    val disposition: String get() = raw.requiredString("disposition")
    val family: String get() = raw.requiredString("family")
    val recordProfile: String get() = raw.requiredString("record_profile")
    val setupProfile: String get() = raw.requiredString("setup_profile")
    val lateSetupReleaseProfile: String get() = raw.requiredString("late_setup_release_profile")
    val bindingProfile: String get() = raw.requiredString("binding_profile")
    val nativeProfile: String get() = raw.requiredString("native_profile")
    val retirementProfile: String get() = raw.requiredString("retirement_profile")
    val expectedClass: String get() = raw.requiredString("expected_class")
    val publicationExpectation: String get() = raw.requiredString("publication_expectation")
    val status: String get() = raw.requiredString("status")
    val checkpointForced: Boolean get() = raw.requiredObject("late_checkpoint").requiredBoolean("forced")
    val checkpointOptional: Boolean get() = raw.requiredObject("late_checkpoint").requiredBoolean("optional")
    val checkpointOptionalRead: String get() =
        raw.requiredObject("late_checkpoint").requiredString("optional_read")
    val optionalBoundaryDiagnostic: Boolean get() =
        raw["optional_boundary_diagnostic"]?.jsonPrimitive?.content?.toBoolean() ?: false
    val requiredObservations: List<String> get() = raw.stringList("required_observations")
    val forbiddenObservations: List<String> get() = raw.stringList("forbidden_observations")
    val alternateOutcomes: List<JsonObject> get() = raw.array("alternate_outcomes").map { it.jsonObject }
    val branchOutcomes: List<JsonObject> get() = raw.array("branch_outcomes").map { it.jsonObject }
    val gates: List<String> get() = raw.stringList("gates")
    val lateChange: JsonObject? get() = raw["late_change"]?.let { it as? JsonObject }
    val watchdogWithdrawal: JsonObject? get() = raw["watchdog_withdrawal"]?.let { it as? JsonObject }
    val foreignPublicationLedger: JsonObject? get() =
        raw["foreign_publication_ledger"]?.let { it as? JsonObject }
    val modeTransition: JsonObject? get() = raw["mode_transition"]?.let { it as? JsonObject }
    val finalConnected: JsonObject get() = raw.requiredObject("final_connected")
}
