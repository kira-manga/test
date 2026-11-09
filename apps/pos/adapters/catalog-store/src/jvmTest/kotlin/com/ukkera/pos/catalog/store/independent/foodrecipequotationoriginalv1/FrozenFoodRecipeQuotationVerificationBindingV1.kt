package com.ukkera.pos.catalog.store.independent.foodrecipequotationoriginalv1

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads the exact separately frozen supplement; never rewrites or re-signs its inputs. */
internal object FrozenFoodRecipeQuotationVerificationBindingV1 {
    private const val FREEZE_DIRECTORY =
        "qa/pos/freezes/food-recipe-quotation-verification-binding-v1"
    private const val FREEZE_IDENTITY =
        "8c9b6ab33eff8bf74ee098d4dc2fe4e428a2afd2d5b32ec571e9c6ab149304bf"
    private const val FREEZE_BYTES_SHA256 =
        "d1e3e50e9e76636a14d4f51d1a4241e5c764fee1006697f0ace47852e0bef851"
    private const val FIXTURE_BYTES_SHA256 =
        "32ee49f3dfeed907cb45e58c378a4a3176fbea6f9e0212268a4135c26ca16a52"

    internal val root: JsonObject = run {
        val directory = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .map { it.resolve(FREEZE_DIRECTORY) }
            .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
            ?: error("Missing frozen supplement: $FREEZE_DIRECTORY")
        val freezeBytes = Files.readAllBytes(directory.resolve("freeze.json"))
        check(sha256Hex(freezeBytes) == FREEZE_BYTES_SHA256) { "Frozen manifest bytes changed" }
        val manifest = Json.parseToJsonElement(freezeBytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        check(manifest.text("identity_sha256") == FREEZE_IDENTITY) { "Frozen identity changed" }
        for (artifact in manifest.array("artifacts")) {
            val declared = artifact.jsonObject
            val bytes = Files.readAllBytes(directory.resolve(declared.text("path")))
            check(bytes.size == declared.getValue("bytes").jsonPrimitive.int) { "Frozen artifact size changed" }
            check(sha256Hex(bytes) == declared.text("sha256")) { "Frozen artifact bytes changed" }
        }
        val fixtureBytes = Files.readAllBytes(directory.resolve("fixtures.json"))
        check(sha256Hex(fixtureBytes) == FIXTURE_BYTES_SHA256) { "Frozen fixture bytes changed" }
        val fixture = Json.parseToJsonElement(fixtureBytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        val cases = fixture.array("caseMatrix").map { it.jsonObject }
        check(cases.size == 6 && fixture.getValue("caseCount").jsonPrimitive.int == 6)
        check(cases.sumOf { it.array("variants").size } == 18 &&
            fixture.getValue("finiteVariantCount").jsonPrimitive.int == 18)
        check(cases.all { it.array("variants").size == it.getValue("variantCount").jsonPrimitive.int })
        fixture
    }

    internal fun variant(caseId: String, name: String): JsonObject = root.array("caseMatrix")
        .map { it.jsonObject }.single { it.text("caseId") == caseId }
        .array("variants").map { it.jsonObject }.single { it.text("name") == name }
}

internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
internal fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
