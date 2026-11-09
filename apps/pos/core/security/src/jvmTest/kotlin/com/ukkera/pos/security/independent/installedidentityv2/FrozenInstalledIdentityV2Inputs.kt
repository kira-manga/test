package com.ukkera.pos.security.independent.installedidentityv2

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedIosActiveAppIdentityV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityPreparationV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityTargetV2
import com.ukkera.pos.security.SelfOwnedMacServiceIdentityV2
import com.ukkera.pos.security.SelfOwnedMacUiIdentityV2
import com.ukkera.pos.security.SelfOwnedPreparationBodyV1
import com.ukkera.pos.security.SelfOwnedTargetDescriptorV1
import com.ukkera.pos.security.SelfOwnedWindowsPackagedAppIdentityV2
import com.ukkera.pos.security.SelfOwnedWindowsPackagedServiceIdentityV2
import com.ukkera.pos.security.SelfOwnedWindowsServiceIdentityV2
import com.ukkera.pos.types.DomainId
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Frozen independent E3-008 v3 inputs. Expected bytes, lengths, SHA-256 values, case IDs,
 * mutation codes and iOS observations are read from the accepted packet; this class only wires
 * them into actual production consumers. It does not author or adjust an oracle.
 *
 * The `FrozenInputWriter` below is fixture wiring for constructing mutated *inputs*. It is
 * cross-checked against every production canonical vector and every frozen mutated length/hash
 * before a production parser is asked for a result.
 */
internal class FrozenInstalledIdentityV2Inputs private constructor(
    val designDirectory: Path,
    val vectors: JsonObject,
    val fixtures: JsonObject,
) {
    val legacyCommon: JsonObject = vectors.getValue("legacy").jsonObject.getValue("common").jsonObject
    val legacyTargetJson: JsonObject = vectors.getValue("legacy").jsonObject.getValue("target").jsonObject
    val legacySignatureHex: String = vectors.getValue("legacy").jsonObject.getValue("synthetic_signature_hex").jsonPrimitive.content
    val v2Common: JsonObject = vectors.getValue("v2").jsonObject.getValue("common").jsonObject
    val v2SignatureHex: String = vectors.getValue("v2").jsonObject.getValue("synthetic_signature_hex").jsonPrimitive.content

    val targets: List<TargetVector> = vectors.getValue("v2").jsonObject.getValue("targets").jsonArray.map {
        val value = it.jsonObject
        TargetVector(
            value.getValue("id").jsonPrimitive.content,
            value.getValue("kind").jsonPrimitive.content,
            value.getValue("variant").jsonPrimitive.content,
            value.getValue("identity").jsonObject,
            expected(value.getValue("expected").jsonObject),
        )
    }
    val preparations: List<PreparationVector> = vectors.getValue("v2").jsonObject.getValue("preparations").jsonArray.map {
        val value = it.jsonObject
        PreparationVector(
            value.getValue("id").jsonPrimitive.content,
            value.getValue("kind").jsonPrimitive.content,
            value.getValue("identity").jsonObject,
            expected(value.getValue("expected").jsonObject),
        )
    }
    val signing: List<SigningVector> = vectors.getValue("v2").jsonObject.getValue("signing").jsonArray.map {
        val value = it.jsonObject
        SigningVector(
            value.getValue("id").jsonPrimitive.content,
            value.getValue("kind").jsonPrimitive.content,
            expected(value.getValue("signed_body").jsonObject),
            expected(value.getValue("signed_envelope").jsonObject),
        )
    }
    val capControls: List<CapVector> = vectors.getValue("v2").jsonObject.getValue("cap_controls").jsonArray.map {
        val value = it.jsonObject
        CapVector(
            value.getValue("id").jsonPrimitive.content,
            value.getValue("base").jsonPrimitive.content,
            value.getValue("expected").jsonPrimitive.content,
            expected(value),
        )
    }
    val mutations: List<JsonObject> = vectors.getValue("mutations").jsonArray.map { it.jsonObject }
    val caseIndex: List<JsonObject> = fixtures.getValue("case_index").jsonArray.map { it.jsonObject }
    val iosObservations: List<JsonObject> = mutations.filter {
        it.getValue("kind").jsonPrimitive.content == "observation"
    }

    fun targetVector(id: String): TargetVector = targets.single { it.id == id }
    fun preparationVector(id: String): PreparationVector = preparations.single { it.id == id }
    fun signingVector(id: String): SigningVector = signing.single { it.id == id }
    fun canonicalTarget(kind: String): TargetVector = targets.single { it.kind == kind && it.variant == "CANONICAL" }

    fun target(kind: String, identity: JsonObject): SelfOwnedInstalledIdentityTargetV2 = when (kind) {
        "WINDOWS_PACKAGED_APP_V1" -> SelfOwnedWindowsPackagedAppIdentityV2(
            identity.text("packageFamilyName"), identity.text("applicationUserModelId"),
        )
        "WINDOWS_SERVICE_V1" -> SelfOwnedWindowsServiceIdentityV2(
            identity.text("serviceSid"), identity.text("serviceAccountSid"),
        )
        "WINDOWS_PACKAGED_SERVICE_V1" -> SelfOwnedWindowsPackagedServiceIdentityV2(
            identity.text("packageFamilyName"), identity.text("applicationUserModelId"),
            identity.text("serviceSid"), identity.text("serviceAccountSid"),
        )
        "MACOS_SERVICE_V1" -> SelfOwnedMacServiceIdentityV2(
            identity.text("signingIdentifier"), identity.text("teamIdentifier"),
            identity.text("designatedRequirementSha256"),
        )
        "MACOS_UI_V1" -> SelfOwnedMacUiIdentityV2(
            identity.text("signingIdentifier"), identity.text("teamIdentifier"),
            identity.text("designatedRequirementSha256"),
        )
        "IOS_ACTIVE_APP_V1" -> SelfOwnedIosActiveAppIdentityV2(
            identity.text("bundleIdentifier"), identity.text("appIdGroup"),
        )
        else -> error("Unsupported frozen target kind $kind")
    }

    fun legacyTarget(): SelfOwnedTargetDescriptorV1 = SelfOwnedTargetDescriptorV1(
        legacyTargetJson.text("applicationId"),
        legacyTargetJson.getValue("applicationUid").jsonPrimitive.int,
        legacyTargetJson.text("apkSignerSha256"),
        legacyTargetJson.text("androidIdCommitment"),
    )

    fun legacyPreparation(
        branchName: String = legacyCommon.text("branchName"),
    ): SelfOwnedPreparationBodyV1 {
        val authority = legacyCommon.getValue("authority").jsonObject
        val coordinates = legacyCommon.getValue("coordinates").jsonObject
        return SelfOwnedPreparationBodyV1(
            DomainId(authority.text("rootId")),
            authority.text("rootDocumentDigest"),
            DomainId(authority.text("issuerId")),
            authority.text("delegationDigest"),
            DomainId(authority.text("installerId")),
            legacyCommon.getValue("installerRevision").jsonPrimitive.long,
            legacyCommon.getValue("delegationRevision").jsonPrimitive.long,
            coordinates(coordinates),
            legacyCommon.getValue("serial").jsonPrimitive.long,
            octets(authority.text("nonceHex")),
            legacyTarget(),
            branchName,
            com.ukkera.pos.security.SelfOwnedKeyProtectionV1.entries[
                legacyCommon.getValue("protection").jsonPrimitive.int,
            ],
        )
    }

    fun preparation(
        kind: String,
        identity: JsonObject,
        branchName: String = v2Common.text("branchName"),
    ): SelfOwnedInstalledIdentityPreparationV2 {
        val authority = v2Common.getValue("authority").jsonObject
        return SelfOwnedInstalledIdentityPreparationV2(
            DomainId(authority.text("rootId")),
            authority.text("rootDocumentDigest"),
            DomainId(authority.text("issuerId")),
            authority.text("delegationDigest"),
            DomainId(authority.text("installerId")),
            v2Common.getValue("installerRevision").jsonPrimitive.long,
            v2Common.getValue("delegationRevision").jsonPrimitive.long,
            coordinates(v2Common.getValue("coordinates").jsonObject),
            v2Common.getValue("serial").jsonPrimitive.long,
            hexBytes(authority.text("nonceHex")),
            target(kind, identity),
            branchName,
        )
    }

    fun coordinates(value: JsonObject): SelfOwnedAllocationCoordinatesV1 = SelfOwnedAllocationCoordinatesV1(
        DomainId(value.text("allocationId")),
        BranchScope(
            DomainId(value.text("organizationId")),
            DomainId(value.text("companyId")),
            DomainId(value.text("branchId")),
        ),
        DomainId(value.text("installationId")),
        DomainId(value.text("assignmentId")),
        DomainId(value.text("deviceId")),
        DomainId(value.text("storageInstanceId")),
        DomainId(value.text("storageKeyId")),
        DomainId(value.text("pepperKeyId")),
        DomainId(value.text("targetSigningKeyId")),
        DomainId(value.text("firstActorId")),
    )

    fun octets(hex: String): com.ukkera.pos.security.SelfOwnedOctetsV1 =
        com.ukkera.pos.security.SelfOwnedOctetsV1(hexBytes(hex))

    fun overrides(mutation: JsonObject): Map<String, JsonElement> {
        val fields = mutation["fields"]
        if (fields != null) return fields.jsonObject.toMap()
        val field = mutation["field"]?.jsonPrimitive?.contentOrNull ?: return emptyMap()
        return mapOf(field to mutation.getValue("value"))
    }

    fun baseBytes(base: String, overrides: Map<String, JsonElement> = emptyMap()): ByteArray = when {
        base == "LEGACY-TARGET" -> legacyTargetBytes(overrides)
        base == "LEGACY-PREP" -> legacyRecordBytes(overrides)
        base == "LEGACY-SIGN" -> legacySigningBytes(overrides)
        base == "LEGACY-ENV" -> legacyEnvelopeBytes(overrides)
        base.startsWith("V2-TARGET-") -> targetVector(base).let {
            v2TargetBytes(it.kind, it.identity, overrides)
        }
        base.startsWith("V2-PREP-") -> preparationVector(base).let {
            v2RecordBytes(it.kind, it.identity, overrides)
        }
        base.startsWith("V2-BODY-") -> signingVector(base).let {
            v2SigningBytes(it.kind, canonicalTarget(it.kind).identity, overrides)
        }
        base.startsWith("V2-ENV-") -> signingVector("V2-BODY-" + base.removePrefix("V2-ENV-")).let {
            v2EnvelopeBytes(it.kind, canonicalTarget(it.kind).identity, overrides)
        }
        else -> error("Unknown frozen base $base")
    }

    fun mutatedBytes(mutation: JsonObject): ByteArray {
        val base = mutation.getValue("base").jsonPrimitive.content
        val operation = mutation["operation"]?.jsonPrimitive?.contentOrNull
        return if (operation == null) {
            baseBytes(base, overrides(mutation))
        } else {
            val input = baseBytes(base)
            when (operation) {
                "append_hex" -> input + hexBytes(
                    mutation.getValue("value_hex").jsonPrimitive.content,
                ) * (mutation["count"]?.jsonPrimitive?.int ?: 1)
                "truncate_last" -> input.copyOf(input.size - (mutation["count"]?.jsonPrimitive?.int ?: 1))
                "replace_hex" -> replaceFirst(
                    input,
                    hexBytes(mutation.getValue("find_hex").jsonPrimitive.content),
                    hexBytes(mutation.getValue("value_hex").jsonPrimitive.content),
                )
                else -> error("Unknown frozen byte operation $operation")
            }
        }
    }

    private fun legacyTargetBytes(overrides: Map<String, JsonElement>): ByteArray {
        val target = overrides.withPrefix("target.", legacyTargetJson)
        return string(target.text("applicationId")) +
            int(target.getValue("applicationUid").jsonPrimitive.int) +
            string(target.text("apkSignerSha256")) +
            string(target.text("androidIdCommitment"))
    }

    private fun legacyBodyBytes(overrides: Map<String, JsonElement>): ByteArray {
        val common = overrides.withPrefix("preparation.", legacyCommon)
        val authority = overrides.withPrefix("authority.", legacyCommon.getValue("authority").jsonObject)
        val coordinates = overrides.withPrefix("coordinates.", legacyCommon.getValue("coordinates").jsonObject)
        val target = overrides.withPrefix("target.", legacyTargetJson)
        return string(authority.text("rootId")) +
            string(authority.text("rootDocumentDigest")) +
            string(authority.text("issuerId")) +
            string(authority.text("delegationDigest")) +
            string(authority.text("installerId")) +
            long(common.getValue("installerRevision").jsonPrimitive.long) +
            long(common.getValue("delegationRevision").jsonPrimitive.long) +
            string(coordinates.text("allocationId")) +
            string(coordinates.text("organizationId")) +
            string(coordinates.text("companyId")) +
            string(coordinates.text("branchId")) +
            string(coordinates.text("installationId")) +
            string(coordinates.text("assignmentId")) +
            string(coordinates.text("deviceId")) +
            string(coordinates.text("storageInstanceId")) +
            string(coordinates.text("storageKeyId")) +
            string(coordinates.text("pepperKeyId")) +
            string(coordinates.text("targetSigningKeyId")) +
            string(coordinates.text("firstActorId")) +
            long(common.getValue("serial").jsonPrimitive.long) +
            field(hexBytes(authority.text("nonceHex"))) +
            string(target.text("applicationId")) +
            int(target.getValue("applicationUid").jsonPrimitive.int) +
            string(target.text("apkSignerSha256")) +
            string(target.text("androidIdCommitment")) +
            string(common.text("branchName")) +
            int(common.getValue("protection").jsonPrimitive.int) +
            string(common.text("preparationPurpose"))
    }

    private fun legacyRecordBytes(overrides: Map<String, JsonElement>): ByteArray = frame(
        overrides.textOr("profile", com.ukkera.pos.security.SelfOwnedInstallationProfileV1.ID),
        overrides.textOr("domain", "native-preparation/v1"),
        legacyBodyBytes(overrides),
    )

    private fun legacySigningBytes(overrides: Map<String, JsonElement>): ByteArray {
        val recordOverrides = overrides.withoutFrameKeys()
        val body = overrides["body_hex"]?.jsonPrimitive?.contentOrNull?.let(::hexBytes)
            ?: legacyRecordBytes(recordOverrides)
        return frame(
            overrides.textOr("profile", com.ukkera.pos.security.SelfOwnedInstallationProfileV1.ID),
            overrides.textOr("domain", "signed-body/v1"),
            int(overrides.intOr("role", 3)) + field(body),
        )
    }

    private fun legacyEnvelopeBytes(overrides: Map<String, JsonElement>): ByteArray {
        val recordOverrides = overrides.withoutFrameKeys()
        val body = overrides["body_hex"]?.jsonPrimitive?.contentOrNull?.let(::hexBytes)
            ?: legacyRecordBytes(recordOverrides)
        val signature = overrides["signature_hex"]?.jsonPrimitive?.contentOrNull?.let(::hexBytes)
            ?: hexBytes(legacySignatureHex)
        return frame(
            overrides.textOr("profile", com.ukkera.pos.security.SelfOwnedInstallationProfileV1.ID),
            overrides.textOr("domain", "signed-document/v1"),
            int(overrides.intOr("role", 3)) + field(body) + field(signature),
        )
    }

    private fun v2TargetBytes(
        kind: String,
        identity: JsonObject,
        overrides: Map<String, JsonElement>,
    ): ByteArray {
        val value = overrides.withPrefix("identity.", identity)
        val profile = overrides.textOr(
            "target_profile",
            overrides.textOr("profile", com.ukkera.pos.security.SelfOwnedInstalledIdentityProfileV2.ID),
        )
        val domain = overrides.textOr(
            "target_domain",
            overrides.textOr("domain", com.ukkera.pos.security.SelfOwnedInstalledIdentityProfileV2.TARGET_DOMAIN),
        )
        val version = overrides.intOr("target_format_version", overrides.intOr("format_version", 2))
        return frame(
            profile,
            domain,
            int(version) +
                string(overrides.textOr("target_kind", kind)) +
                identityBytes(kind, value),
        )
    }

    private fun identityBytes(kind: String, identity: JsonObject): ByteArray = when (kind) {
        "WINDOWS_PACKAGED_APP_V1" -> string(identity.text("packageFamilyName")) +
            string(identity.text("applicationUserModelId"))
        "WINDOWS_SERVICE_V1" -> string(identity.text("serviceSid")) +
            string(identity.text("serviceAccountSid"))
        "WINDOWS_PACKAGED_SERVICE_V1" -> string(identity.text("packageFamilyName")) +
            string(identity.text("applicationUserModelId")) +
            string(identity.text("serviceSid")) +
            string(identity.text("serviceAccountSid"))
        "MACOS_SERVICE_V1", "MACOS_UI_V1" -> string(identity.text("signingIdentifier")) +
            string(identity.text("teamIdentifier")) +
            string(identity.text("designatedRequirementSha256"))
        "IOS_ACTIVE_APP_V1" -> string(identity.text("bundleIdentifier")) +
            string(identity.text("appIdGroup"))
        else -> error("Unsupported frozen target kind $kind")
    }

    private fun v2PreparationBody(
        kind: String,
        identity: JsonObject,
        overrides: Map<String, JsonElement>,
        common: JsonObject = v2Common,
    ): ByteArray {
        val preparation = overrides.withPrefix("preparation.", common)
        val authority = overrides.withPrefix("authority.", common.getValue("authority").jsonObject)
        val coordinates = overrides.withPrefix("coordinates.", common.getValue("coordinates").jsonObject)
        val targetOverrides = overrides.filterKeys {
            it.startsWith("identity.") ||
                it in setOf("target_kind", "target_domain", "target_profile", "target_format_version")
        }
        val targetRecord = overrides["target_record_hex"]?.jsonPrimitive?.contentOrNull?.let(::hexBytes)
            ?: v2TargetBytes(kind, identity, targetOverrides)
        return int(overrides.intOr("format_version", 2)) +
            string(authority.text("rootId")) +
            string(authority.text("rootDocumentDigest")) +
            string(authority.text("issuerId")) +
            string(authority.text("delegationDigest")) +
            string(authority.text("installerId")) +
            long(preparation.getValue("installerRevision").jsonPrimitive.long) +
            long(preparation.getValue("delegationRevision").jsonPrimitive.long) +
            string(coordinates.text("allocationId")) +
            string(coordinates.text("organizationId")) +
            string(coordinates.text("companyId")) +
            string(coordinates.text("branchId")) +
            string(coordinates.text("installationId")) +
            string(coordinates.text("assignmentId")) +
            string(coordinates.text("deviceId")) +
            string(coordinates.text("storageInstanceId")) +
            string(coordinates.text("storageKeyId")) +
            string(coordinates.text("pepperKeyId")) +
            string(coordinates.text("targetSigningKeyId")) +
            string(coordinates.text("firstActorId")) +
            long(preparation.getValue("serial").jsonPrimitive.long) +
            field(hexBytes(authority.text("nonceHex"))) +
            field(targetRecord) +
            string(preparation.text("branchName")) +
            string(preparation.text("preparationPurpose"))
    }

    private fun v2RecordBytes(
        kind: String,
        identity: JsonObject,
        overrides: Map<String, JsonElement>,
    ): ByteArray = frame(
        overrides.textOr("profile", com.ukkera.pos.security.SelfOwnedInstalledIdentityProfileV2.ID),
        overrides.textOr("domain", com.ukkera.pos.security.SelfOwnedInstalledIdentityProfileV2.PREPARATION_DOMAIN),
        v2PreparationBody(kind, identity, overrides),
    )

    private fun v2SigningBytes(
        kind: String,
        identity: JsonObject,
        overrides: Map<String, JsonElement>,
        framedBody: Boolean = false,
    ): ByteArray {
        val recordOverrides = overrides.withoutFrameKeys()
        val record = overrides["body_hex"]?.jsonPrimitive?.contentOrNull?.let(::hexBytes)
            ?: if (framedBody) v2RecordBytes(kind, identity, recordOverrides)
            else v2PreparationBody(kind, identity, recordOverrides)
        return frame(
            overrides.textOr("profile", com.ukkera.pos.security.SelfOwnedInstalledIdentityProfileV2.ID),
            overrides.textOr("domain", com.ukkera.pos.security.SelfOwnedInstalledIdentityProfileV2.SIGNING_BODY_DOMAIN),
            string(overrides.textOr("role_tag", com.ukkera.pos.security.SelfOwnedInstalledIdentityProfileV2.ROLE_TAG)) +
                field(record),
        )
    }

    private fun v2EnvelopeBytes(
        kind: String,
        identity: JsonObject,
        overrides: Map<String, JsonElement>,
        framedBody: Boolean = false,
    ): ByteArray {
        val recordOverrides = overrides.withoutFrameKeys()
        val record = overrides["body_hex"]?.jsonPrimitive?.contentOrNull?.let(::hexBytes)
            ?: if (framedBody) v2RecordBytes(kind, identity, recordOverrides)
            else v2PreparationBody(kind, identity, recordOverrides)
        val signature = overrides["signature_hex"]?.jsonPrimitive?.contentOrNull?.let(::hexBytes)
            ?: hexBytes(v2SignatureHex)
        return frame(
            overrides.textOr("profile", com.ukkera.pos.security.SelfOwnedInstalledIdentityProfileV2.ID),
            overrides.textOr("domain", com.ukkera.pos.security.SelfOwnedInstalledIdentityProfileV2.SIGNED_DOCUMENT_DOMAIN),
            string(overrides.textOr("role_tag", com.ukkera.pos.security.SelfOwnedInstalledIdentityProfileV2.ROLE_TAG)) +
                field(record) +
                field(signature),
        )
    }

    /**
     * Frozen mutation inputs for `v2.signed_body`/`v2.signed_envelope` were generated from an
     * unframed preparation body (the static validator's local builder), while the accepted
     * contract and every canonical signed vector use the entire framed record. The mutation
     * outcomes remain the frozen role/signature/trailing/body-only/profile refusals. These
     * helpers reproduce the canonical full-framed production bytes for wiring checks; mutation
     * inputs deliberately reproduce the frozen unframed base so their lengths/SHA-256 match.
     */
    fun canonicalSigningBytes(vector: SigningVector): ByteArray =
        v2SigningBytes(vector.kind, canonicalTarget(vector.kind).identity, emptyMap(), framedBody = true)

    fun canonicalEnvelopeBytes(vector: SigningVector): ByteArray =
        v2EnvelopeBytes(vector.kind, canonicalTarget(vector.kind).identity, emptyMap(), framedBody = true)

    companion object {
        private const val DESIGN_PATH = "qa/pos/designs/foundation-installed-application-identity-v3"
        private const val MANIFEST_SHA256 = "d0ca056f7104892a6b9ed47e76aab2c7d8adfda85e936785f2b45869d9a7c7d4"

        fun load(): FrozenInstalledIdentityV2Inputs {
            val design = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
                .map { it.resolve(DESIGN_PATH) }
                .firstOrNull { Files.isRegularFile(it.resolve("manifest.json")) }
                ?: error("Frozen installed-identity v3 inputs are unavailable from the test working directory")
            val files = Files.list(design).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }
            check(files == listOf(
                "CONTRACT.md", "IOS-ADJUDICATION.md", "P1-DECISION-RECORD.md", "PLATFORM-TRUST.md",
                "README.md", "STATIC-CHECKS.md", "cases.md", "fixtures.json", "manifest.json",
                "manifest.sha256", "validate.py", "vectors.json",
            )) { "Frozen design directory must contain exactly the declared twelve files: $files" }
            val manifestBytes = Files.readAllBytes(design.resolve("manifest.json"))
            check(sha256Hex(manifestBytes) == MANIFEST_SHA256) { "Frozen manifest hash changed" }
            check(
                Files.readAllBytes(design.resolve("manifest.sha256")).decodeToString().trim() ==
                    "$MANIFEST_SHA256  manifest.json",
            ) { "Frozen manifest.sha256 record changed" }
            val manifest = Json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject
            val declared = manifest.getValue("files").jsonArray
            check(declared.size == 10) { "Frozen manifest must declare ten content files" }
            for (entry in declared) {
                val file = entry.jsonObject
                val name = file.getValue("path").jsonPrimitive.content
                val bytes = Files.readAllBytes(design.resolve(name))
                check(bytes.size == file.getValue("bytes").jsonPrimitive.int) { "Frozen byte count changed: $name" }
                check(sha256Hex(bytes) == file.getValue("sha256").jsonPrimitive.content) {
                    "Frozen SHA-256 changed: $name"
                }
            }
            val vectorsBytes = Files.readAllBytes(design.resolve("vectors.json"))
            val fixturesBytes = Files.readAllBytes(design.resolve("fixtures.json"))
            val vectors = Json.parseToJsonElement(vectorsBytes.decodeToString()).jsonObject
            val fixtures = Json.parseToJsonElement(fixturesBytes.decodeToString()).jsonObject
            val counts = manifest.getValue("counts").jsonObject
            check(vectors.getValue("mutations").jsonArray.size == counts.getValue("mutations_total").jsonPrimitive.int)
            check(fixtures.getValue("case_index").jsonArray.size == counts.getValue("cases").jsonPrimitive.int)
            return FrozenInstalledIdentityV2Inputs(design, vectors, fixtures)
        }

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}

internal data class ExpectedBytes(val bytes: ByteArray, val length: Int, val sha256: String) {
    override fun toString(): String = "ExpectedBytes(length=$length, sha256=$sha256)"
}

internal data class TargetVector(
    val id: String,
    val kind: String,
    val variant: String,
    val identity: JsonObject,
    val expected: ExpectedBytes,
)

internal data class PreparationVector(
    val id: String,
    val kind: String,
    val identity: JsonObject,
    val expected: ExpectedBytes,
)

internal data class SigningVector(
    val id: String,
    val kind: String,
    val signedBody: ExpectedBytes,
    val signedEnvelope: ExpectedBytes,
)

internal data class CapVector(
    val id: String,
    val base: String,
    val expectedCode: String,
    val expected: ExpectedBytes,
)

private fun expected(value: JsonObject): ExpectedBytes = ExpectedBytes(
    hexBytes(value.getValue("bytes_hex").jsonPrimitive.content),
    value.getValue("length").jsonPrimitive.int,
    value.getValue("sha256").jsonPrimitive.content,
)

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.textOr(key: String, default: String): String =
    get(key)?.jsonPrimitive?.contentOrNull ?: default

private fun JsonObject.intOr(key: String, default: Int): Int =
    get(key)?.jsonPrimitive?.int ?: default

private fun Map<String, JsonElement>.textOr(key: String, default: String): String =
    get(key)?.jsonPrimitive?.contentOrNull ?: default

private fun Map<String, JsonElement>.intOr(key: String, default: Int): Int =
    get(key)?.jsonPrimitive?.int ?: default

private fun Map<String, JsonElement>.withoutFrameKeys(): Map<String, JsonElement> =
    filterKeys { it !in setOf("profile", "domain", "role", "role_tag", "body_hex", "signature_hex") }

private fun Map<String, JsonElement>.withPrefix(prefix: String, base: JsonObject): JsonObject {
    val result = base.toMutableMap()
    for ((key, value) in this) {
        if (key.startsWith(prefix)) result[key.substring(prefix.length)] = value
    }
    return JsonObject(result)
}

private fun frame(profile: String, domain: String, body: ByteArray): ByteArray =
    string(profile) + string(domain) + body

private fun string(value: String): ByteArray = field(value.encodeToByteArray())

private fun field(value: ByteArray): ByteArray = int(value.size) + value

private fun int(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
)

private fun long(value: Long): ByteArray = ByteArray(8) { index ->
    (value ushr (56 - index * 8)).toByte()
}

private fun hexBytes(value: String): ByteArray =
    if (value.isEmpty()) ByteArray(0) else ByteArray(value.length / 2) {
        value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

private fun replaceFirst(input: ByteArray, find: ByteArray, replace: ByteArray): ByteArray {
    val at = input.indexOfSequence(find)
    check(at >= 0) { "Frozen replacement bytes not found" }
    return input.copyOfRange(0, at) + replace + input.copyOfRange(at + find.size, input.size)
}

private fun ByteArray.indexOfSequence(find: ByteArray): Int {
    if (find.isEmpty() || find.size > size) return -1
    for (start in 0..size - find.size) {
        var matches = true
        for (index in find.indices) if (this[start + index] != find[index]) { matches = false; break }
        if (matches) return start
    }
    return -1
}

private operator fun ByteArray.times(count: Int): ByteArray {
    val result = ByteArray(size * count)
    repeat(count) { copyInto(result, destinationOffset = it * size) }
    return result
}
