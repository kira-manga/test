package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.types.DomainId
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loads the immutable v3 specification, the additive v3-errata-v1 overlay and the reviewed
 * v3-errata-v2 candidate, applies both frozen RFC 6902 subsets in the accepted order before
 * scene resolution, rebuilds every launch-record vector from its frozen builder and resolves
 * every effective scene.
 *
 * This is executor fixture setup only. It does not parse production output as golden input,
 * grant authority or replace any frozen expectation.
 */
internal object ExistingOnlyStartupFixtures {
    private const val V3_DIRECTORY = "foundation-existing-only-startup-v3"
    private const val V1_DIRECTORY = "foundation-existing-only-startup-v3-errata-v1"
    private const val V2_DIRECTORY = "foundation-existing-only-startup-v3-errata-v2"
    private const val V3_COMMIT = "83dba361fc492de3f28c4c1334fde8a29ae73f91"
    private const val V1_COMMIT = "3ac3a0cdebb5d4be93bf0d6f15bb9f447261284b"
    private const val V2_CANDIDATE_COMMIT = "3cfeefe7470f369049ca1a3a053d1fdf0f421b4f"
    private const val HEADER = "ukkera.local-master-launch/v1"
    private val hex = HexFormat.of()
    private val json = Json

    /**
     * Exact reviewed local candidate: commit 3cfeefe7 plus the single prose-only D8-03
     * Sequence correction (editorial-sequence-correction.patch). The original candidate
     * SHA-256 for cases-errata-v2.md was 51e5b7f17a0c3e9e25328861b061f31805af346d73cb6c93f591430c8eb7428b;
     * the reviewed local file is 5249b940aa1baf683189bec982082994407f8aa70ce48ff95753685b2bca25a2.
     * All six other files are byte-identical to their 3cfeefe7 candidate hashes.
     */
    private val v2ReviewedPins: Map<String, FilePin> = mapOf(
        "README.md" to FilePin(24161, "c0b446addf0de5fdc3b3c2158bbda628a61ff8a6450d27098e888c26b8adbd9b"),
        "cases-errata-v2.md" to FilePin(
            46754, "5249b940aa1baf683189bec982082994407f8aa70ce48ff95753685b2bca25a2",
            "3cfeefe7 original 51e5b7f17a0c3e9e25328861b061f31805af346d73cb6c93f591430c8eb7428b",
        ),
        "coverage-errata-v2.md" to FilePin(
            35208, "dd7f8637c8d72cae4581da275402765fbe00563f01388bab9c169fc22593edab",
        ),
        "inventory-v2.json" to FilePin(
            80782, "023579d69a298776224e8174e71ea260050559d6bb183d1dc4742073eddb2b57",
        ),
        "overlay-v2.json" to FilePin(
            84064, "c4174fde44a84e6da3292625f62c7e34da09b206990cb2a0a9977d4e94f3ec03",
        ),
        "resolve.py" to FilePin(
            48071, "5d9e3e684e1e4f67f2ae2c6e514c9b07661b1964d5f1bf80b119d8af612e155b",
        ),
        "static-result.txt" to FilePin(
            1657, "919a5a9f6de3bd0d593addfcb2f491845e728fbae3c6ec7b09da6db38d13ffa4",
        ),
    )

    private class FilePin(val bytes: Int, val sha256: String, val provenance: String? = null)

    private val designRoot: Path = locateDesignRoot()
    private val v3: JsonObject = readJson(designRoot.resolve(V3_DIRECTORY).resolve("fixtures.json"))
    private val v1Overlay: JsonObject = readJson(designRoot.resolve(V1_DIRECTORY).resolve("overlay.json"))
    private val v1Inventory: JsonObject = readJson(designRoot.resolve(V1_DIRECTORY).resolve("inventory.json"))
    private val v2Overlay: JsonObject = readJson(designRoot.resolve(V2_DIRECTORY).resolve("overlay-v2.json"))
    private val v2Inventory: JsonObject = readJson(designRoot.resolve(V2_DIRECTORY).resolve("inventory-v2.json"))
    private val inventoryVariants = linkedMapOf<String, List<String>>()

    init {
        verifyBaseFiles()
        verifyV2CandidateFiles()
    }

    val coordinateOrder: List<String> = v3.requiredArray("coordinate_order").map { it.jsonPrimitive.content }
    val canonical: List<String> = coordinates("canonical")
    val foreign: List<String> = coordinates("foreign")

    val records: Map<String, FrozenLaunchRecord> = buildRecords()
    val recordVectors: Map<String, JsonObject> = v3.requiredObject("launch_record")
        .requiredArray("vectors").associate { element ->
            val vector = element.jsonObject
            vector.requiredString("id") to vector
        }

    val patched: JsonObject = applyOverlay(applyOverlay(v3, v1Overlay, "v1"), v2Overlay, "v2")
    val effectiveCases: List<FrozenCase> = resolveCases(patched)
    val variants: Map<String, FrozenVariant> = effectiveCases.flatMap { it.variants }.associateBy { it.variantId }
    val templateCount: Int = patched.requiredObject("templates").size

    init {
        verifyStaticInventory()
        verifyRecordVectors()
        verifyCoordinateDeltas()
        verifyCoordinateProfileIndexes()
        verifyEffectiveReferences()
    }

    fun case(caseId: String): FrozenCase = effectiveCases.singleOrNull { it.caseId == caseId }
        ?: error("Unknown effective case $caseId")

    fun record(id: String): FrozenLaunchRecord = records[id] ?: error("Unknown frozen record $id")

    fun recordCoordinates(id: String): List<String>? = record(id).coordinates

    fun custodyClass(variantId: String): String =
        inventoryVariants.getValue(variantId)[2]

    fun coordinateValues(vectorId: String): List<String> = when (vectorId) {
        "canonical" -> canonical
        "foreign" -> foreign
        else -> error("Unknown coordinate source $vectorId")
    }

    fun coordinates(values: List<String>): SelfOwnedAllocationCoordinatesV1 {
        require(values.size == 12) { "A coordinate vector must contain twelve values" }
        return SelfOwnedAllocationCoordinatesV1(
            allocationId = DomainId(values[0]),
            scope = BranchScope(DomainId(values[1]), DomainId(values[2]), DomainId(values[3])),
            installationId = DomainId(values[4]),
            assignmentId = DomainId(values[5]),
            deviceId = DomainId(values[6]),
            storageInstanceId = DomainId(values[7]),
            storageKeyId = DomainId(values[8]),
            pepperKeyId = DomainId(values[9]),
            targetSigningKeyId = DomainId(values[10]),
            firstActorId = DomainId(values[11]),
        )
    }

    fun profile(kind: String, id: String): JsonObject {
        val profiles = patched.requiredObject("profiles").requiredObject(kind)
        return profiles[id]?.jsonObject ?: error("Unknown $kind profile $id")
    }

    fun hasProfile(kind: String, id: String): Boolean =
        patched.requiredObject("profiles").requiredObject(kind).containsKey(id)

    fun gate(id: String): String = patched.requiredObject("gates").requiredString(id)
    fun event(id: String): String = patched.requiredObject("events").requiredString(id)

    fun expectedClass(id: String): String = patched.requiredObject("expected_classes").requiredString(id)

    private fun coordinates(kind: String): List<String> {
        val values = v3.requiredObject("coordinates").requiredArray(kind).map { element ->
            val value = element.jsonObject
            value.requiredString("uuid").also { require(DomainId(it).value == it) { "Non-canonical $kind UUID $it" } }
        }
        require(values.size == 12 && values.distinct().size == 12) { "$kind coordinates must be twelve distinct values" }
        require(canonicalUuid(values)) { "$kind coordinates must use canonical lower-case UUIDs" }
        return values
    }

    private fun canonicalUuid(values: List<String>): Boolean = values.all { value ->
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)
    }

    private fun verifyBaseFiles() {
        val v3Base = v2Overlay.requiredObject("base").requiredObject("v3")
        require(v3Base.requiredString("commit") == V3_COMMIT) { "v3 base commit changed" }
        require(v3Base.requiredString("path") == "qa/pos/designs/$V3_DIRECTORY") { "v3 base path changed" }
        for ((name, element) in v3Base.requiredObject("files")) {
            verifyPinnedFile(designRoot.resolve(V3_DIRECTORY).resolve(name), element.jsonObject, "v3 $name")
        }
        val v1Base = v2Overlay.requiredObject("base").requiredObject("v1")
        require(v1Base.requiredString("commit") == V1_COMMIT) { "v1 base commit changed" }
        require(v1Base.requiredString("path") == "qa/pos/designs/$V1_DIRECTORY") { "v1 base path changed" }
        for ((name, element) in v1Base.requiredObject("files")) {
            verifyPinnedFile(designRoot.resolve(V1_DIRECTORY).resolve(name), element.jsonObject, "v1 $name")
        }
        require(v1Inventory.requiredInt("case_count") == 40 && v1Inventory.requiredInt("variant_count") == 162) {
            "v1 inventory is not 40 cases / 162 variants"
        }
    }

    private fun verifyV2CandidateFiles() {
        require(v2Overlay.requiredString("schema") == "ukkera-pos/e3-002-existing-only-startup/v3-errata-v2/overlay") {
            "Unexpected v2 overlay schema"
        }
        require(v2Inventory.requiredString("schema") == "ukkera-pos/e3-002-existing-only-startup/v3-errata-v2/inventory") {
            "Unexpected v2 inventory schema"
        }
        for ((name, pin) in v2ReviewedPins) {
            val path = designRoot.resolve(V2_DIRECTORY).resolve(name)
            require(Files.isRegularFile(path)) { "Missing reviewed v2 candidate file $path" }
            val bytes = Files.readAllBytes(path)
            require(bytes.size == pin.bytes) { "v2 candidate $name byte length changed" }
            require(sha256(bytes) == pin.sha256) {
                "v2 candidate $name SHA-256 changed" +
                    (pin.provenance?.let { " ($it)" } ?: "")
            }
        }
        val inventoryOverlayPin = v2Inventory.requiredObject("base").requiredObject("v2_overlay")
        verifyPinnedFile(designRoot.resolve(V2_DIRECTORY).resolve("overlay-v2.json"), inventoryOverlayPin, "v2 overlay")
        require(V2_CANDIDATE_COMMIT == "3cfeefe7470f369049ca1a3a053d1fdf0f421b4f") {
            "v2 candidate provenance constant changed"
        }
    }

    private fun verifyPinnedFile(path: Path, spec: JsonObject, label: String) {
        require(Files.isRegularFile(path)) { "Missing frozen design file $path" }
        val bytes = Files.readAllBytes(path)
        require(bytes.size == spec.requiredInt("bytes")) { "$label byte length changed" }
        require(sha256(bytes) == spec.requiredString("sha256")) { "$label SHA-256 changed" }
    }

    private fun applyOverlay(root: JsonObject, overlay: JsonObject, label: String): JsonObject {
        val patch = overlay.requiredObject("patch")
        require(patch.requiredString("format") == "RFC 6902 subset (test/replace/add/remove) with exact JSON pointers") {
            "Unexpected $label overlay patch format"
        }
        val allowed = patch.requiredArray("allowed_operations").map { it.jsonPrimitive.content }.toSet()
        require(allowed == setOf("test", "replace", "add", "remove")) { "Unexpected $label overlay operations" }
        val ops = patch.requiredArray("ops")
        require(ops.size == if (label == "v1") 119 else 172) { "$label overlay operation count changed" }
        var current: JsonElement = root
        for ((index, element) in ops.withIndex()) {
            val op = element.jsonObject
            val kind = op.requiredString("op")
            if (kind == "replace" || kind == "remove") {
                val path = op.requiredString("path")
                require(index > 0) { "$label $kind at $path lacks an immediate test" }
                val previous = ops[index - 1].jsonObject
                require(previous.requiredString("op") == "test" && previous.requiredString("path") == path) {
                    "$label $kind at $path lacks an immediate same-path test"
                }
            }
            current = applyOperation(current, op, label)
        }
        return current.jsonObject
    }

    private fun applyOperation(root: JsonElement, op: JsonObject, label: String): JsonElement {
        val kind = op.requiredString("op")
        val parts = pointer(op.requiredString("path"))
        return when (kind) {
            "test" -> {
                val expected = op.getValue("value")
                val actual = elementAt(root, parts)
                require(actual == expected) { "$label overlay test failed at ${op.requiredString("path")}" }
                root
            }
            "replace" -> replaceAt(root, parts, op.getValue("value"), label)
            "add" -> addAt(root, parts, op.getValue("value"), label)
            "remove" -> removeAt(root, parts, label)
            else -> error("$label forbidden overlay operation $kind")
        }
    }

    private fun pointer(path: String): List<String> {
        require(path.startsWith('/')) { "Invalid JSON pointer $path" }
        if (path == "/") return emptyList()
        return path.substring(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
    }

    private fun elementAt(element: JsonElement, parts: List<String>): JsonElement {
        var current = element
        for (part in parts) {
            current = when (current) {
                is JsonObject -> current[part] ?: error("Missing pointer segment $part")
                is JsonArray -> current[part.toInt()]
                else -> error("Pointer traverses a scalar at $part")
            }
        }
        return current
    }

    private fun replaceAt(element: JsonElement, parts: List<String>, value: JsonElement, label: String): JsonElement {
        if (parts.isEmpty()) return value
        val head = parts.first()
        val tail = parts.drop(1)
        return when (element) {
            is JsonObject -> {
                require(element.containsKey(head)) { "$label replace target $head is absent" }
                JsonObject(element.toMutableMap().apply { put(head, replaceAt(element.getValue(head), tail, value, label)) })
            }
            is JsonArray -> {
                val index = head.toInt()
                require(index in element.indices) { "$label replace index $index is out of range" }
                JsonArray(element.toMutableList().apply { set(index, replaceAt(element[index], tail, value, label)) })
            }
            else -> error("$label replace traverses a scalar")
        }
    }

    private fun addAt(element: JsonElement, parts: List<String>, value: JsonElement, label: String): JsonElement {
        require(parts.isNotEmpty()) { "$label add requires a non-root pointer" }
        val head = parts.first()
        val tail = parts.drop(1)
        return when (element) {
            is JsonObject -> {
                if (tail.isEmpty()) {
                    require(!element.containsKey(head)) { "$label add target $head already exists" }
                    JsonObject(element.toMutableMap().apply { put(head, value) })
                } else {
                    require(element.containsKey(head)) { "$label add parent $head is absent" }
                    JsonObject(element.toMutableMap().apply { put(head, addAt(element.getValue(head), tail, value, label)) })
                }
            }
            is JsonArray -> {
                val list = element.toMutableList()
                if (tail.isEmpty()) {
                    if (head == "-") list.add(value)
                    else list.add(head.toInt(), value)
                    JsonArray(list)
                } else {
                    val index = head.toInt()
                    require(index in element.indices) { "$label add index $index is out of range" }
                    list[index] = addAt(element[index], tail, value, label)
                    JsonArray(list)
                }
            }
            else -> error("$label add traverses a scalar")
        }
    }

    private fun removeAt(element: JsonElement, parts: List<String>, label: String): JsonElement {
        require(parts.isNotEmpty()) { "$label remove requires a non-root pointer" }
        val head = parts.first()
        val tail = parts.drop(1)
        return when (element) {
            is JsonObject -> {
                require(element.containsKey(head)) { "$label remove target $head is absent" }
                if (tail.isEmpty()) JsonObject(element.toMutableMap().apply { remove(head) })
                else JsonObject(element.toMutableMap().apply { put(head, removeAt(element.getValue(head), tail, label)) })
            }
            is JsonArray -> {
                val list = element.toMutableList()
                if (tail.isEmpty()) {
                    val index = head.toInt()
                    require(index in element.indices) { "$label remove index $index is out of range" }
                    list.removeAt(index)
                } else {
                    val index = head.toInt()
                    require(index in element.indices) { "$label remove index $index is out of range" }
                    list[index] = removeAt(element[index], tail, label)
                }
                JsonArray(list)
            }
            else -> error("$label remove traverses a scalar")
        }
    }

    private fun resolveCases(root: JsonObject): List<FrozenCase> {
        require(root.requiredInt("case_count") == 41) { "Effective case count must be 41" }
        require(root.requiredInt("variant_count") == 168) { "Effective variant count must be 168" }
        val templates = root.requiredObject("templates")
        require(templates.size == 35) { "Effective template count must be 35" }
        val requiredKeys = root.requiredObject("scene_resolution").requiredArray("required_keys")
            .map { it.jsonPrimitive.content }
        val cases = root.requiredArray("cases").map { element ->
            val case = element.jsonObject
            val caseId = case.requiredString("case_id")
            val variants = case.requiredArray("variants").map { variantElement ->
                val variant = variantElement.jsonObject
                val variantId = variant.requiredString("variant_id")
                val base = variant.requiredString("base")
                val template = templates[base]?.jsonObject ?: error("$variantId has unknown base template $base")
                require(template.keys == requiredKeys.toSet()) {
                    "$variantId template $base does not declare exactly the required scene keys"
                }
                val scene = template.toMutableMap()
                for ((key, value) in variant.requiredObject("set")) {
                    require(scene.containsKey(key)) { "$variantId overrides unknown scene key $key" }
                    scene[key] = value
                }
                require(scene.keys == requiredKeys.toSet()) { "$variantId declares unknown scene keys" }
                val expected = variant.requiredString("expected")
                require(scene.getValue("expected").jsonPrimitive.content == expected) {
                    "$variantId effective expected does not equal variant.expected"
                }
                FrozenVariant(
                    caseId = caseId,
                    variantId = variantId,
                    baseTemplate = base,
                    scene = scene.mapValues { it.value },
                    extra = variant.optionalString("extra") ?: "",
                )
            }
            FrozenCase(caseId, case.requiredString("title"), variants)
        }
        require(cases.size == 41 && cases.flatMap { it.variants }.size == 168) {
            "Resolved case/variant count differs from the effective inventory"
        }
        require(cases.flatMap { it.variants }.map { it.variantId }.distinct().size == 168) {
            "Effective variant IDs are not unique"
        }
        return cases
    }

    /**
     * Independent custody classification from the frozen scene shapes and the reviewed public
     * contract. This is the executor's own check of inventory-v2's custody_class column; it is
     * not copied from that column and it changes no expected outcome.
     */
    private fun derivedCustodyClass(variant: FrozenVariant): String {
        val scene = variant.scene
        val targetId = scene.optionalString("target")?.takeIf { it != "NONE" } ?: return "NO_NATIVE_OWNER"
        val target = TargetProfile.from(this, targetId)
        if (target.result != "opened") return "NO_TARGET"
        val masterId = scene.optionalString("master")?.takeIf { it != "NONE" }
        val master = masterId?.let { MasterProfile.from(this, it) }
        if (master != null && master.result in setOf("unresolved", "throw")) {
            return "TARGET_CUSTODY_INDETERMINATE"
        }
        if (scene.requiredString("expected") == "TARGET_HELD_MISMATCH") {
            return "TARGET_NATIVE_PRE_TRANSFER"
        }
        val bindingId = scene.optionalString("binding")?.takeIf { it != "NONE" }
        if (bindingId != null) {
            val binding = BindingProfile.from(this, bindingId)
            if (binding.result != "retained" || binding.bindingSource == "canonical_with_foreign_at") {
                return "TARGET_NATIVE_PRE_TRANSFER"
            }
        }
        if (retirementRequestedWhileTargetHeld(scene)) return "TARGET_NATIVE_PRE_TRANSFER"
        return "MASTER_NATIVE_TARGET_STALE"
    }

    private fun retirementRequestedWhileTargetHeld(scene: Map<String, JsonElement>): Boolean {
        val race = scene["race"]?.takeIf { it !is JsonNull }?.jsonObject ?: return false
        val stages = race["stages"]?.jsonArray?.map { it.jsonObject } ?: listOf(race)
        return stages.any { stage ->
            stage.optionalString("hold") == "G-TARGET" &&
                stage["on_enter"]?.jsonArray?.any { it.jsonPrimitive.content == "EV-REQUEST-RETIREMENT" } == true
        }
    }

    private fun verifyStaticInventory() {
        require(v2Inventory.requiredInt("case_count") == 41) { "Inventory case count changed" }
        require(v2Inventory.requiredInt("variant_count") == 168) { "Inventory variant count changed" }
        val columns = v2Inventory.requiredArray("variant_columns").map { it.jsonPrimitive.content }
        require(columns == listOf("variant_id", "base_template", "custody_class", "withdraw_profile",
            "presentation_profile", "close_expectation", "expected_class", "status", "disposition", "changed")) {
            "Unexpected inventory variant columns"
        }
        inventoryVariants.clear()
        for (caseElement in v2Inventory.requiredArray("cases")) {
            for (row in caseElement.jsonObject.requiredArray("variants")) {
                val elements = row.jsonArray
                require(elements.size == columns.size) { "Inventory row width changed" }
                require(elements.last() is JsonArray) { "Inventory changed column must be an array" }
                val values = elements.take(columns.size - 1).map { it.jsonPrimitive.content }
                require(inventoryVariants.put(values[0], values) == null) { "Duplicate inventory variant ${values[0]}" }
            }
        }
        require(inventoryVariants.size == 168) { "Inventory must list 168 variants" }
        val effective = effectiveCases.flatMap { it.variants }
        require(effective.map { it.variantId }.toSet() == inventoryVariants.keys) {
            "Effective and inventory variant IDs differ"
        }
        for (variant in effective) {
            val row = inventoryVariants.getValue(variant.variantId)
            val scene = variant.scene
            require(row[1] == variant.baseTemplate) {
                "${variant.variantId} inventory base template differs"
            }
            require(row[2] == derivedCustodyClass(variant)) {
                "${variant.variantId} inventory custody class differs: expected ${derivedCustodyClass(variant)} actual ${row[2]}"
            }
            require(row[3] == scene.requiredString("withdraw")) { "${variant.variantId} inventory withdraw differs" }
            require(row[4] == scene.requiredString("presentation")) { "${variant.variantId} inventory presentation differs" }
            require(row[5] == scene.requiredString("close_expectation")) { "${variant.variantId} inventory close expectation differs" }
            require(row[6] == scene.requiredString("expected")) { "${variant.variantId} inventory expected class differs" }
            require(row[7] == "NOT RUN") { "${variant.variantId} inventory status must remain NOT RUN" }
        }
        val counts = v2Inventory.requiredObject("counts")
        assertCount(counts.requiredObject("expected_classes"), effective.groupingBy { it.scene.requiredString("expected") }.eachCount())
        assertCount(counts.requiredObject("withdraw_profiles"), effective.groupingBy { it.scene.requiredString("withdraw") }.eachCount())
        assertCount(counts.requiredObject("presentation_profiles"), effective.groupingBy { it.scene.requiredString("presentation") }.eachCount())
        assertCount(counts.requiredObject("close_expectations"), effective.groupingBy { it.scene.requiredString("close_expectation") }.eachCount())
        assertCount(counts.requiredObject("custody_classes"), effective.groupingBy { derivedCustodyClass(it) }.eachCount())
        assertCount(counts.requiredObject("records"), effective.groupingBy { it.scene.requiredString("record") }.eachCount())
    }

    private fun assertCount(expected: JsonObject, actual: Map<String, Int>) {
        val expectedMap = expected.mapValues { it.value.jsonPrimitive.int }
        require(expectedMap == actual) { "Inventory count differs: expected $expectedMap actual $actual" }
    }

    private fun verifyRecordVectors() {
        require(records.size == 34) { "Expected 34 launch-record vectors, found ${records.size}" }
        val classification = records.values.groupingBy { it.classification }.eachCount()
        require(classification == mapOf("RUN" to 14, "STOP" to 1, "ABSENT" to 1, "READ_ERROR" to 1, "UNRESOLVED" to 17)) {
            "Launch-record classification census changed: $classification"
        }
        require(records.getValue("REC-STOP").bytes?.size == 35) { "STOP record must be 35 bytes" }
        require(records.getValue("REC-RUN-CANONICAL").bytes?.size == 478) { "Canonical RUN must be 478 bytes" }
        require(records.getValue("REC-EMPTY").bytes?.isEmpty() == true) { "Empty record must be zero bytes" }
        require((records.getValue("REC-OVERSIZE").bytes?.size ?: 0) > 1024) { "Oversize record must exceed 1024 bytes" }
    }

    private fun buildRecords(): Map<String, FrozenLaunchRecord> {
        val vectorObjects = v3.requiredObject("launch_record").requiredArray("vectors").map { it.jsonObject }
        val byId = vectorObjects.associateBy { it.requiredString("id") }
        val built = linkedMapOf<String, FrozenLaunchRecord>()
        fun build(id: String, stack: Set<String>): FrozenLaunchRecord {
            built[id]?.let { return it }
            require(id !in stack) { "Cyclic launch-record builder at $id" }
            val vector = byId[id] ?: error("Unknown launch-record vector $id")
            val builder = vector.requiredObject("builder")
            val bytes: ByteArray?
            var readThrows = false
            when (builder.requiredString("kind")) {
                "absent" -> bytes = null
                "read_throw" -> { bytes = null; readThrows = true }
                "run" -> {
                    val source = builder.requiredString("coordinate_source")
                    val values = coordinateValues(source).toMutableList()
                    builder.optionalInt("replace_index")?.let { index ->
                        require(index in 0..11) { "$id replace_index is out of range" }
                        values[index] = coordinateValues(builder.requiredString("replacement_source"))[index]
                    }
                    bytes = canonicalRun(values)
                }
                "stop" -> bytes = "$HEADER\nSTOP\n".encodeToByteArray()
                "literal_utf8" -> bytes = builder.requiredString("text").encodeToByteArray()
                "literal_hex" -> bytes = hex.parseHex(builder.requiredString("hex"))
                "prepend_hex" -> {
                    val base = build(builder.requiredString("base"), stack + id).bytes ?: error("$id base has no bytes")
                    bytes = hex.parseHex(builder.requiredString("hex")) + base
                }
                "replace_text" -> {
                    val base = build(builder.requiredString("base"), stack + id).bytes ?: error("$id base has no bytes")
                    val text = base.decodeToString(throwOnInvalidSequence = true)
                    val find = builder.requiredString("find")
                    val replace = builder.requiredString("replace")
                    val count = text.windowed(find.length).count { it == find }
                    when (builder.requiredString("occurrence")) {
                        "all" -> bytes = text.replace(find, replace).encodeToByteArray()
                        "first" -> {
                            require(count == 1) { "$id first replacement must have exactly one occurrence" }
                            bytes = text.replaceFirst(find, replace).encodeToByteArray()
                        }
                        else -> error("$id has unknown replacement occurrence")
                    }
                }
                "remove_text" -> {
                    val base = build(builder.requiredString("base"), stack + id).bytes ?: error("$id base has no bytes")
                    val text = base.decodeToString(throwOnInvalidSequence = true)
                    val find = builder.requiredString("find")
                    val count = text.windowed(find.length).count { it == find }
                    require(count == builder.requiredInt("expected_count")) { "$id removal count differs" }
                    bytes = text.replace(find, "").encodeToByteArray()
                }
                "truncate_bytes" -> {
                    val base = build(builder.requiredString("base"), stack + id).bytes ?: error("$id base has no bytes")
                    bytes = base.copyOf(builder.requiredInt("length"))
                }
                "append_utf8" -> {
                    val base = build(builder.requiredString("base"), stack + id).bytes ?: error("$id base has no bytes")
                    bytes = base + builder.requiredString("text").encodeToByteArray()
                }
                "insert_hex_at" -> {
                    val base = build(builder.requiredString("base"), stack + id).bytes ?: error("$id base has no bytes")
                    val index = builder.requiredInt("index")
                    require(index in 0..base.size) { "$id insertion index is out of range" }
                    val inserted = hex.parseHex(builder.requiredString("hex"))
                    bytes = base.copyOfRange(0, index) + inserted + base.copyOfRange(index, base.size)
                }
                "replace_byte_at" -> {
                    val base = build(builder.requiredString("base"), stack + id).bytes ?: error("$id base has no bytes")
                    val index = builder.requiredInt("index")
                    require(index in base.indices) { "$id replacement index is out of range" }
                    val replacement = hex.parseHex(builder.requiredString("hex"))
                    require(replacement.size == 1) { "$id replacement must be one byte" }
                    bytes = base.copyOf().also { it[index] = replacement[0] }
                }
                else -> error("$id has unknown launch-record builder ${builder.requiredString("kind")}")
            }
            if (bytes != null) {
                require(bytes.size == vector.requiredInt("byte_length")) { "$id byte length differs" }
                require(sha256(bytes) == vector.requiredString("sha256")) { "$id SHA-256 differs" }
            }
            val classification = vector.requiredString("classification")
            val record = FrozenLaunchRecord(
                id = id,
                bytes = bytes,
                readThrows = readThrows,
                classification = classification,
                coordinates = if (classification == "RUN") {
                    val lines = requireNotNull(bytes).decodeToString().split('\n')
                    require(lines.size == 15 && lines[1] == "RUN") { "$id RUN structure differs" }
                    lines.subList(2, 14).also { values ->
                        require(values.size == 12 && values.distinct().size == 12) { "$id RUN coordinates differ" }
                    }
                } else null,
            )
            built[id] = record
            return record
        }
        for (vector in vectorObjects) build(vector.requiredString("id"), emptySet())
        return built
    }

    private fun verifyCoordinateDeltas() {
        require(canonical.toSet().intersect(foreign.toSet()).isEmpty()) { "Canonical and foreign coordinates overlap" }
        val foreignRecord = records.getValue("REC-RUN-FOREIGN-01").coordinates ?: error("Foreign RUN record is missing")
        val delta = canonical.indices.filter { canonical[it] != foreignRecord[it] }
        require(delta == listOf(0)) { "Stored foreign RUN must differ at exactly allocation" }
        for (index in 0..11) {
            val vector = records.getValue("REC-RUN-FOREIGN-${(index + 1).toString().padStart(2, '0')}").coordinates
                ?: error("Foreign RUN vector is missing")
            val changed = canonical.indices.filter { canonical[it] != vector[it] }
            require(changed == listOf(index)) { "Foreign RUN vector $index must differ at exactly one position" }
        }
    }

    private fun verifyCoordinateProfileIndexes() {
        val targets = patched.requiredObject("profiles").requiredObject("targets")
        val bindings = patched.requiredObject("profiles").requiredObject("bindings")
        for (index in 0..11) {
            val suffix = (index + 1).toString().padStart(2, '0')
            val targetId = "TGT-FOREIGN-$suffix"
            val target = targets[targetId]?.jsonObject ?: error("Missing target profile $targetId")
            require(target.requiredString("result") == "opened") { "$targetId must open" }
            val coordinates = target.requiredObject("coordinates")
            require(coordinates.requiredString("source") == "canonical") { "$targetId coordinate source" }
            require(coordinates.requiredInt("replace_index") == index) { "$targetId replace_index" }
            require(coordinates.requiredString("replacement_source") == "foreign") { "$targetId replacement source" }
            val bindingId = "BND-CHANGED-$suffix"
            val binding = bindings[bindingId]?.jsonObject ?: error("Missing binding profile $bindingId")
            require(binding.requiredString("result") == "retained") { "$bindingId must retain" }
            require(binding.requiredString("material_source") == "canonical") { "$bindingId material source" }
            require(binding.requiredString("binding_source") == "canonical_with_foreign_at") { "$bindingId binding source" }
            require(binding.requiredInt("foreign_index") == index) { "$bindingId foreign_index" }
        }
        val match = targets["TGT-MATCH"]?.jsonObject ?: error("Missing TGT-MATCH")
        require(match.requiredObject("coordinates").requiredString("source") == "canonical") { "TGT-MATCH source" }
        require(match.requiredObject("coordinates")["replace_index"] == null) { "TGT-MATCH must not replace" }
        val same = bindings["BND-SAME-AS-TARGET"]?.jsonObject ?: error("Missing BND-SAME-AS-TARGET")
        require(same.requiredString("material_source") == "target" && same.requiredString("binding_source") == "target") {
            "BND-SAME-AS-TARGET sources"
        }
        val canonicalCoordinates = ExistingOnlyStartupData.canonicalCoordinates
        val canonicalBindingDigest = run {
            val bytes = ExistingOnlyStartupData.binding(canonicalCoordinates).copyWireBytes()
            try { sha256(bytes) } finally { bytes.fill(0) }
        }
        val changedDigests = (0..11).associateWith { index ->
            val bytes = ExistingOnlyStartupData.binding(
                ExistingOnlyStartupData.coordinatesWith("canonical", index),
            ).copyWireBytes()
            try { sha256(bytes) } finally { bytes.fill(0) }
        }
        require(changedDigests.values.none { it == canonicalBindingDigest }) {
            "Changed binding must differ from the canonical binding"
        }
        require(changedDigests.values.toSet().size == 12) {
            "Each changed binding coordinate must produce a distinct controlled binding"
        }
    }

    private fun verifyEffectiveReferences() {
        val profileKinds = mapOf(
            "target" to "targets", "binding" to "bindings", "master" to "masters", "setup" to "setups",
            "client" to "clients", "retirement" to "retirements", "withdraw" to "withdraws",
            "close_target" to "closes", "close_master" to "closes", "close_client" to "closes",
            "presentation" to "presentations", "first_close" to "closes",
        )
        val gates = patched.requiredObject("gates")
        val events = patched.requiredObject("events")
        val expectedClasses = patched.requiredObject("expected_classes")
        for (variant in variants.values) {
            val scene = variant.scene
            require(record(scene.requiredString("record")).id == scene.requiredString("record")) {
                "${variant.variantId} record is unresolved"
            }
            for ((key, kind) in profileKinds) {
                val id = scene.requiredString(key)
                if (id == "NONE") continue
                require(hasProfile(kind, id)) { "${variant.variantId} has unknown $key profile $id" }
            }
            require(expectedClasses.containsKey(scene.requiredString("expected"))) {
                "${variant.variantId} has unknown expected class"
            }
            require(scene.requiredString("withdraw") != "WD-FORBIDDEN" ||
                record(scene.requiredString("record")).classification != "RUN") {
                "${variant.variantId} uses WD-FORBIDDEN on an acquired RUN disposition"
            }
            val race = scene["race"]?.takeIf { it !is JsonNull }?.jsonObject
            if (race != null) {
                val allowedRaceKeys = setOf(
                    "hold", "on_enter", "release", "after_settle", "after_outcome", "after_closed", "stages",
                )
                require(race.keys.all { it in allowedRaceKeys }) {
                    "${variant.variantId} has unknown race keys ${race.keys - allowedRaceKeys}"
                }
                val explicitStages = race["stages"] != null
                val stageObjects = race["stages"]?.let { stagesElement ->
                    require(race.keys.none { it in setOf("hold", "on_enter", "release",
                        "after_settle", "after_outcome", "after_closed") }) {
                        "${variant.variantId} mixes staged and flat race fields"
                    }
                    stagesElement.jsonArray.map { it.jsonObject }
                } ?: listOf(race)
                require(stageObjects.isNotEmpty()) { "${variant.variantId} has an empty race" }
                for (stage in stageObjects) {
                    val allowedStageKeys = setOf(
                        "hold", "on_enter", "release", "after_settle", "after_outcome", "after_closed",
                    )
                    require(stage.keys.all { it in allowedStageKeys }) {
                        "${variant.variantId} has unknown race stage keys ${stage.keys - allowedStageKeys}"
                    }
                    val hold = stage.optionalString("hold")
                    if (explicitStages || hold != null) {
                        require(hold != null && gates.containsKey(hold)) {
                            "${variant.variantId} has unknown race hold $hold"
                        }
                    }
                    stage.optionalString("release")?.let { release ->
                        require(gates.containsKey(release)) { "${variant.variantId} has unknown gate $release" }
                        require(release == hold) {
                            "${variant.variantId} releases $release while holding $hold"
                        }
                    }
                    for (key in listOf("on_enter", "after_settle", "after_outcome", "after_closed")) {
                        stage[key]?.jsonArray?.forEach { element ->
                            val event = element.jsonPrimitive.content
                            require(events.containsKey(event)) { "${variant.variantId} has unknown event $event" }
                        }
                    }
                }
            }
            val presentation = profile("presentations", scene.requiredString("presentation"))
            require(presentation.requiredString("privateContent") == "none") {
                "${variant.variantId} presentation profile permits private content"
            }
            require(presentation.requiredInt("employeeSessionCalls") == 0 &&
                presentation.requiredInt("privateUnlockCalls") == 0 &&
                presentation.requiredInt("credentialReconstructionCalls") == 0 &&
                presentation.requiredString("secretMaterialExposure") == "none") {
                "${variant.variantId} presentation profile weakens privacy"
            }
            require(!scene.requiredString("withdraw").startsWith("WD-NOT-REQUIRED") &&
                !scene.requiredString("withdraw").startsWith("WD-MATCHING-OWNER")) {
                "${variant.variantId} references a retired withdraw profile"
            }
        }
        val fourCombinations = listOf("SET-VALID", "SET-DEF-NULL-RECEIPT-PRESENT", "SET-RECEIPT-NULL", "SET-BOTH-NULL")
        for (setup in fourCombinations) {
            require(hasProfile("setups", setup)) { "Missing C9 setup combination $setup" }
            val published = variants.values.filter {
                it.scene.requiredString("setup") == setup && it.scene.requiredString("expected") == "PUBLISHED"
            }
            require(published.isNotEmpty()) { "C9 combination $setup is not bound to a PUBLISHED variant" }
        }
        require(variants.values.count { it.scene.requiredString("withdraw") == "WD-DEFENSIVE-THROW" } == 2) {
            "WD-DEFENSIVE-THROW must be selected by exactly the two new D7-08 variants"
        }
        val optionalTargetClose = variants.values.filter { variant ->
            val id = variant.scene.requiredString("close_target")
            id != "NONE" && CloseProfile.from(this, id).optional
        }
        require(optionalTargetClose.map { it.variantId }.toSet() ==
            setOf("E3V3-D8-06.V01-TARGET-CLOSE-UNIT", "E3V3-D8-06.V02-TARGET-CLOSE-THROW")) {
            "Optional target-close diagnostics are not confined to D8-06 V01/V02"
        }
        val custodyCounts = variants.values.groupingBy { derivedCustodyClass(it) }.eachCount()
        require(custodyCounts == mapOf(
            "MASTER_NATIVE_TARGET_STALE" to 84,
            "NO_NATIVE_OWNER" to 28,
            "NO_TARGET" to 7,
            "TARGET_CUSTODY_INDETERMINATE" to 8,
            "TARGET_NATIVE_PRE_TRANSFER" to 41,
        )) { "Derived custody census differs: $custodyCounts" }
    }

    private fun canonicalRun(values: List<String>): ByteArray = buildString {
        append(HEADER).append('\n').append("RUN\n")
        values.forEach { append(it).append('\n') }
    }.encodeToByteArray()

    private fun locateDesignRoot(): Path {
        val starts = listOfNotNull(
            System.getProperty("user.dir")?.let { Path.of(it).toAbsolutePath().normalize() },
            Path.of("").toAbsolutePath().normalize(),
        ).distinct()
        for (start in starts) {
            var current: Path? = start
            while (current != null) {
                val candidate = current.resolve("qa/pos/designs")
                if (Files.isRegularFile(candidate.resolve(V3_DIRECTORY).resolve("fixtures.json")) &&
                    Files.isRegularFile(candidate.resolve(V1_DIRECTORY).resolve("overlay.json")) &&
                    Files.isRegularFile(candidate.resolve(V2_DIRECTORY).resolve("overlay-v2.json"))) {
                    return candidate
                }
                current = current.parent
            }
        }
        error("E3-002 frozen designs were not found under qa/pos/designs. Searched upward from ${starts.joinToString()}.")
    }

    private fun readJson(path: Path): JsonObject {
        require(Files.isRegularFile(path)) { "Missing frozen design file $path" }
        return json.parseToJsonElement(Files.readString(path)).jsonObject
    }
}

internal data class FrozenCase(
    val caseId: String,
    val title: String,
    val variants: List<FrozenVariant>,
)

internal data class FrozenVariant(
    val caseId: String,
    val variantId: String,
    val baseTemplate: String,
    val scene: Map<String, JsonElement>,
    val extra: String,
) {
    fun string(key: String): String = scene.requiredString(key)
    fun optionalString(key: String): String? = scene[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
}

internal class FrozenLaunchRecord(
    val id: String,
    val bytes: ByteArray?,
    val readThrows: Boolean,
    val classification: String,
    val coordinates: List<String>?,
)

internal fun sha256(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

internal fun JsonObject.requiredObject(name: String): JsonObject = getValue(name).jsonObject
internal fun JsonObject.requiredArray(name: String): JsonArray = getValue(name).jsonArray
internal fun JsonObject.requiredString(name: String): String = getValue(name).jsonPrimitive.content
internal fun JsonObject.requiredInt(name: String): Int = getValue(name).jsonPrimitive.int
internal fun JsonObject.requiredBoolean(name: String): Boolean = getValue(name).jsonPrimitive.boolean
internal fun JsonObject.optionalInt(name: String): Int? = get(name)?.takeIf { it !is JsonNull }?.jsonPrimitive?.int
internal fun JsonObject.optionalString(name: String): String? =
    get(name)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

internal fun Map<String, JsonElement>.requiredString(key: String): String = getValue(key).jsonPrimitive.content
internal fun Map<String, JsonElement>.requiredBoolean(key: String): Boolean = getValue(key).jsonPrimitive.boolean
internal fun Map<String, JsonElement>.optionalString(key: String): String? =
    get(key)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
