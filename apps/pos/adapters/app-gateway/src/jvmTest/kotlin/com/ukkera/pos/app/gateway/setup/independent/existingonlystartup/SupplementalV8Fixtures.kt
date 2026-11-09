package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Frozen supplemental-v8 loader. The eleven reviewed files are byte-identical to the independent
 * review/lead-acceptance candidate; the inventory is read as DATA and never rewritten. This
 * loader builds no expectation and runs no validator; it only binds the exact frozen inputs to
 * the controlled executor.
 */
internal object SupplementalV8Fixtures {
    private const val DIRECTORY = "foundation-existing-only-startup-review-addendum-v8"
    private const val CASE_COUNT = 11
    private const val VARIANT_COUNT = 52
    private const val ALTERNATE_COUNT = 8
    private const val WITHDRAWN_COUNT = 5
    private const val MANIFEST_DIGEST =
        "e82410565f46c6c6eca0a688f26ce885580d20c0b1a3923e541e7565ac0c8e4d"
    private val json = Json

    private val expectedFiles: Map<String, Pair<Int, String>> = mapOf(
        "README.md" to (32652 to "529d0fa5bd5994fe8054071f28b38e74960d47c4169626dc9df0cc0a264becf9"),
        "cases-addendum.md" to (74857 to "af98abfcc264d262b5c67111f0b0e4f66686160b1b9f2f585759fe99acaf2625"),
        "corrections-v8.md" to (38447 to "354606d72db4e81a7b46973cb3b1ff255d1831f677312302bbadaf3c63b72ba3"),
        "coverage-addendum.md" to (43885 to "51bf4a0c2570fbfb8eebf67a6014c3453384f61237b2fcbd8e94994cf26713d3"),
        "fixtures.json" to (65101 to "18edf9011b506c5c4f2109d06a6e4cd41ca6f86cac7794343e8b61d5b690cd47"),
        "inventory.json" to (263727 to "d7aa62460977d174c28159be84435382628422ab26fc4aec7bdd236fdf0359c1"),
        "predecessor-pins.json" to (11505 to "f2a37d6b7e03b5b13aaae39fdbb2fe263e9edff443cfda97a8383ec14d3f8aeb"),
        "reviewed-corrections.json" to (11451 to "413bc206cfcf38024ddcf5efc8559cda55d3a32ec4056da118fccb4171534031"),
        "static-result.txt" to (870 to "1acbb1051db532d432b15a99e0ba67b8c4bd1e5e80ad02d406fcd79260459871"),
        "validate.py" to (116652 to "acf6fe2631a8703310b756dc19ac7090936f2ca0c7c8918dbd33c64793ff0f33"),
        "artifact-sha256.txt" to (834 to "2529475bb71726e1c760d54b7ab1562e0642182d93536cb33cc582a9f0ee08f8"),
    )

    private val expectedCaseIds = listOf(
        "E3RA-R1", "E3RA-R2", "E3RA-R3", "E3RA-R4", "E3RA-R5",
        "E3RA-B1", "E3RA-B2", "E3RA-P1", "E3RA-P2", "E3RA-P3", "E3RA-P4",
    )
    private val allowedClasses = setOf(
        "REFRESH_SAME_ORIGINAL", "REFRESH_REFUSED_ACCOUNTED", "REFRESH_REFUSED_RETIRED",
        "RETIRED_CLOSED", "RETIRED_UNRESOLVED",
    )

    private val design: Path = locateDesign()
    private val inventory: JsonObject
    private val fixtures: JsonObject

    val cases: List<SupplementalCase>
    val variants: Map<String, SupplementalVariant>
    val withdrawn: Set<String>
    val survivingAlternates: Set<String>

    init {
        verifyFiles()
        inventory = readJson(design.resolve("inventory.json"))
        fixtures = readJson(design.resolve("fixtures.json"))
        require(inventory.requiredString("version") == "v8") { "supplement inventory is not v8" }
        require(inventory.requiredString("status") == "NOT RUN") { "supplement inventory must remain NOT RUN" }
        require(fixtures.requiredString("version") == "v8") { "supplement fixtures are not v8" }
        require(inventory.requiredInt("case_count") == CASE_COUNT) { "supplement case count changed" }
        require(inventory.requiredInt("variant_count") == VARIANT_COUNT) { "supplement variant count changed" }
        require(fixtures.requiredString("status") == "NOT RUN") { "supplement fixtures must remain NOT RUN" }
        val loadedCases = inventory.requiredArray("cases").map { element ->
            val raw = element.jsonObject
            val caseId = raw.requiredString("case_id")
            val variants = raw.requiredArray("variants").map { variantElement ->
                SupplementalVariant(caseId, raw.requiredString("title"), variantElement.jsonObject)
            }
            SupplementalCase(caseId, raw.requiredString("title"), variants)
        }
        require(loadedCases.size == CASE_COUNT) { "supplement case list changed" }
        require(loadedCases.map { it.caseId } == expectedCaseIds) { "supplement case IDs/order changed" }
        val flat = loadedCases.flatMap { it.variants }
        require(flat.size == VARIANT_COUNT) { "supplement flat variant count changed" }
        require(flat.map { it.variantId }.distinct().size == VARIANT_COUNT) {
            "supplement variant IDs are not unique"
        }
        flat.forEach { variant ->
            require(variant.expectedClass in allowedClasses) {
                "${variant.variantId} has an unexpected class ${variant.expectedClass}"
            }
            require(variant.status == "NOT RUN") { "${variant.variantId} must remain NOT RUN" }
            require(variant.disposition == "new") { "${variant.variantId} must be disposition=new" }
        }
        cases = loadedCases
        variants = flat.associateBy { it.variantId }
        withdrawn = fixtures.requiredArray("withdrawn_conditional_alternates").map {
            it.jsonObject.requiredString("variant_id")
        }.toSet()
        require(withdrawn.size == WITHDRAWN_COUNT) { "withdrawn alternate list changed" }
        val alternates = flat.filter { it.alternateOutcomes.isNotEmpty() }.map { it.variantId }.toSet()
        require(alternates.size == ALTERNATE_COUNT) { "surviving alternate count changed" }
        require(alternates.none { it in withdrawn }) { "withdrawn row still carries an alternate" }
        survivingAlternates = alternates
        require(inventory.requiredObject("counts").requiredObject("by_expected_class").mapValues {
            it.value.jsonPrimitive.content.toInt()
        } == flat.groupingBy { it.expectedClass }.eachCount()) {
            "supplement expected-class counts differ from the inventory"
        }
        require(inventory.requiredObject("counts").requiredInt("conditional_alternate_variants") == ALTERNATE_COUNT) {
            "supplement alternate count differs from the inventory"
        }
        verifyHumanInventoryAgreement(loadedCases)
    }

    fun case(caseId: String): SupplementalCase =
        cases.singleOrNull { it.caseId == caseId } ?: error("Unknown supplemental case $caseId")

    fun refresh(): JsonObject = fixtures.requiredObject("refresh")
    fun provisioner(): JsonObject = fixtures.requiredObject("provisioner")
    fun presentationProfiles(): JsonObject = fixtures.requiredObject("presentation_profiles")

    private fun verifyFiles() {
        require(expectedFiles.size == 11) { "frozen supplemental file list must be eleven" }
        val manifestJson = expectedFiles.entries.joinToString(prefix = "[", postfix = "]", separator = ",") {
            (name, spec) -> "{\"bytes\":${spec.first},\"path\":\"$name\",\"sha256\":\"${spec.second}\"}"
        }
        require(sha256(manifestJson.encodeToByteArray()) == MANIFEST_DIGEST) {
            "Frozen supplemental eleven-file manifest digest changed"
        }
        for ((name, spec) in expectedFiles) {
            val path = design.resolve(name)
            require(Files.isRegularFile(path)) { "Missing frozen supplemental file $path" }
            val bytes = Files.readAllBytes(path)
            require(bytes.size == spec.first) { "Frozen supplemental file $name byte length changed" }
            require(sha256(bytes) == spec.second) { "Frozen supplemental file $name SHA-256 changed" }
        }
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

    private fun verifyHumanInventoryAgreement(loadedCases: List<SupplementalCase>) {
        val casesText = Files.readString(design.resolve("cases-addendum.md"))
        for (case in loadedCases) {
            require("### ${case.caseId}" in casesText) {
                "cases-addendum.md is missing heading for ${case.caseId}"
            }
            for (variant in case.variants) {
                val line = casesText.lineSequence().firstOrNull { variant.variantId in it } ?: ""
                require(line.isNotEmpty()) { "cases-addendum.md is missing ${variant.variantId}" }
                require(variant.expectedClass in line) {
                    "cases-addendum.md row ${variant.variantId} does not show ${variant.expectedClass}"
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
        error("Frozen supplemental v8 design was not found under qa/pos/designs")
    }

    private fun readJson(path: Path): JsonObject {
        require(Files.isRegularFile(path)) { "Missing frozen supplemental file $path" }
        return json.parseToJsonElement(Files.readString(path)).jsonObject
    }

}

internal data class SupplementalCase(
    val caseId: String,
    val title: String,
    val variants: List<SupplementalVariant>,
)

internal class SupplementalVariant(
    val caseId: String,
    val caseTitle: String,
    val raw: JsonObject,
) {
    val variantId: String get() = raw.requiredString("variant_id")
    val scene: String get() = raw.requiredString("scene")
    val refreshMode: String? get() = raw.optionalString("refresh_mode")
    val recordProfile: String get() = raw.requiredString("record_profile")
    val setupProfile: String get() = raw.requiredString("setup_profile")
    val bindingProfile: String get() = raw.requiredString("binding_profile")
    val nativeProfile: String get() = raw.requiredString("native_profile")
    val retirementProfile: String get() = raw.requiredString("retirement_profile")
    val denialCoverage: String get() = raw.requiredString("denial_coverage")
    val closeExpectation: String get() = raw.requiredString("close_expectation")
    val presentationProfile: String get() = raw.requiredString("presentation_profile")
    val expectedClass: String get() = raw.requiredString("expected_class")
    val admissionProfile: String get() = raw.requiredString("admission_profile")
    val admissionObservations: List<String> get() = raw.stringList("admission_observations")
    val provisionerInspection: String get() = raw.requiredString("provisioner_inspection")
    val holderBindingStatus: String get() = raw.requiredString("holder_binding_status")
    val blockedStatus: String get() = raw.requiredString("blocked_status")
    val actualTargetClose: JsonElement? get() = raw["actual_target_close"]?.takeIf { it !is JsonNull }
    val clientHandleDisposition: JsonElement? get() = raw["client_handle_disposition"]?.takeIf { it !is JsonNull }
    val targetDisposition: JsonElement? get() = raw["target_disposition"]?.takeIf { it !is JsonNull }
    val lockDiagnostic: JsonElement? get() = raw["lock_diagnostic"]?.takeIf { it !is JsonNull }
    val retirementDeferredObservation: List<String> get() = raw.stringList("retirement_deferred_observation")
    val provisionerDenialOutcomes: JsonObject? get() = raw["provisioner_denial_outcomes"] as? JsonObject
    val provisionerCloseOutcome: String? get() = raw.optionalString("provisioner_close_outcome")
    val unresolvedReason: String? get() = raw.optionalString("unresolved_reason")
    val postRetirementRefusal: List<String> get() = raw.stringList("post_retirement_refusal")
    val alternatePublicationExpectation: String? get() = raw.optionalString("alternate_publication_expectation")
    val alternatePresentationProfile: String? get() = raw.optionalString("alternate_presentation_profile")
    val transferAccounting: JsonElement? get() = raw["transfer_accounting"]?.takeIf { it !is JsonNull }
    val issuanceProbe: List<String> get() = raw.stringList("issuance_probe")
    val modeRequiredObservations: JsonObject? get() = raw["mode_required_observations"] as? JsonObject
    val custodyRequirements: JsonObject? get() = raw["custody_requirements"] as? JsonObject
    val oldOwnerAccounting: JsonObject? get() = raw["old_owner_accounting"] as? JsonObject
    val masterDisposition: String? get() = raw.optionalString("master_disposition")
    val publicationExpectation: String get() = raw.requiredString("publication_expectation")
    val currentnessExpected: String? get() = raw.optionalString("currentness_expected")
    val gates: List<String> get() = raw.stringList("gates")
    val conditionalDiagnostics: List<String> get() = raw.stringList("conditional_diagnostics")
    val boundaryUnderTest: String? get() = raw.optionalString("boundary_under_test")
    val status: String get() = raw.requiredString("status")
    val disposition: String get() = raw.requiredString("disposition")
    val requiredObservations: List<String> get() = raw.stringList("required_observations")
    val alternateRequiredObservations: List<String> get() = raw.stringList("alternate_required_observations")
    val optionalObservations: List<String> get() = raw.stringList("optional_observations")
    val optionalAdmissionObservations: List<String> get() = raw.stringList("optional_admission_observations")
    val alternateOutcomes: List<JsonObject> get() = raw.array("alternate_outcomes").map { it.jsonObject }

    fun hasRequired(token: String): Boolean = token in requiredObservations
    fun hasAlternate(token: String): Boolean = token in alternateRequiredObservations
}

internal fun JsonObject.stringList(name: String): List<String> =
    array(name).map { it.jsonPrimitive.content }

internal fun JsonObject.array(name: String): JsonArray =
    get(name)?.let { element -> if (element is JsonNull) JsonArray(emptyList()) else element.jsonArray }
        ?: JsonArray(emptyList())

internal fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeIf { it !is JsonNull }?.let { (it as JsonPrimitive).content }
