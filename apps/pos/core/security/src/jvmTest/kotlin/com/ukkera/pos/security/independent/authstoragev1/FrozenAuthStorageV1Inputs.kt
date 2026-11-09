package com.ukkera.pos.security.independent.authstoragev1

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads the unchanged v1 inputs; no compatibility vector or payload grammar is supplied here. */
internal class FrozenAuthStorageV1Inputs private constructor(private val input: JsonObject) {
    val profiles: List<FrozenAuthStorageV1Profile> = input.getValue("protectedProfiles").jsonArray.map { item ->
        val profile = item.jsonObject
        FrozenAuthStorageV1Profile(
            profile.getValue("wireIdentity").jsonPrimitive.content,
            profile.getValue("schema").jsonPrimitive.int,
            profile.getValue("maximumPlaintextBytes").jsonPrimitive.int,
            profile.getValue("oversizedRejectedBytes").jsonPrimitive.int,
            profile.getValue("emptyRejectedBytes").jsonPrimitive.int,
        )
    }

    val envelope: JsonObject get() = input.getValue("protectedEnvelope").jsonObject

    fun actor(label: String): DomainId = DomainId(input.getValue("accounts").jsonObject
        .getValue(label).jsonObject.getValue("actorId").jsonPrimitive.content)

    fun device(label: String): DomainId = DomainId(input.getValue("devices").jsonObject
        .getValue(label).jsonObject.getValue("deviceId").jsonPrimitive.content)

    fun scope(label: String): BranchScope {
        val scopes = input.getValue("scopes").jsonObject
        val branch = scopes.getValue(label).jsonObject
        val company = scopes.getValue(branch.getValue("company").jsonPrimitive.content).jsonObject
        val organization = scopes.getValue(branch.getValue("organization").jsonPrimitive.content).jsonObject
        return BranchScope(
            DomainId(organization.getValue("organizationId").jsonPrimitive.content),
            DomainId(company.getValue("companyId").jsonPrimitive.content),
            DomainId(branch.getValue("branchId").jsonPrimitive.content),
        )
    }

    /** A frozen UUID used only as an inert lookup coordinate, never as a claimed issuance. */
    fun lookupCoordinate(index: Int): DomainId = DomainId(input.getValue("requests").jsonObject
        .getValue("LAS-002").jsonArray[index].jsonPrimitive.content)

    override fun toString(): String = "FrozenAuthStorageV1Inputs([REDACTED])"

    companion object {
        private const val DESIGN_PATH = "qa/pos/designs/protected-auth-storage-contract-v1"
        private val expectedHashes = linkedMapOf(
            "README.md" to "10212505f9ea0c38d1b2664f8af4af891df8ba1953239a3ab5223b8e9c622ae8",
            "cases.md" to "8467a072b2ca2a3430bb3e3580d5c0dc2893669698f806c290f31edad6c1c195",
            "fixtures.json" to "25e989ee1d2ff91d353d0b3a9d4540c4c2f2b5a2c0f238f3f87a7f879f07783e",
            "binding-questions.md" to "af24e858307e0b11b691d2ebabf7f7042e0b1d495be07a8f453a707a0c09264e",
        )

        fun load(): FrozenAuthStorageV1Inputs {
            val design = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
                .map { it.resolve(DESIGN_PATH) }
                .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
                ?: error("Frozen authentication/storage v1 inputs are unavailable from the test working directory")
            var fixtures: ByteArray? = null
            for ((name, expected) in expectedHashes) {
                val bytes = Files.readAllBytes(design.resolve(name))
                val observed = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                check(observed == expected) { "Frozen authentication/storage v1 input hash mismatch: $name" }
                if (name == "fixtures.json") fixtures = bytes
            }
            val input = Json.parseToJsonElement(checkNotNull(fixtures).decodeToString()).jsonObject
            check(input.getValue("fixtureVersion").jsonPrimitive.contentOrNull == "protected-auth-storage-contract-v1")
            return FrozenAuthStorageV1Inputs(input)
        }
    }
}

internal data class FrozenAuthStorageV1Profile(
    val wireIdentity: String,
    val schema: Int,
    val maximumPlaintextBytes: Int,
    val oversizedRejectedBytes: Int,
    val emptyRejectedBytes: Int,
)
