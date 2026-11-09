package com.ukkera.pos.security.independent.installedidentityv2

import com.ukkera.pos.security.SelfOwnedBranchFactsV1
import com.ukkera.pos.security.SelfOwnedInstalledIdentityFactoryV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityProfileV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityRefusalV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityTargetV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityWireV2
import com.ukkera.pos.security.SelfOwnedInstallationWireV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.SelfOwnedLegacyInstallationCompatibilityV1
import com.ukkera.pos.security.SelfOwnedMacServiceIdentityV2
import com.ukkera.pos.security.SelfOwnedNativeMaterialView
import com.ukkera.pos.security.SelfOwnedStorageKeySecurity
import com.ukkera.pos.security.selfOwnedHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * E3-008 compatibility erratum v2 wiring. This exercises the accepted legacy/V2/macOS/UTF-8/
 * role-tag/cap/factory/cross-version/iOS DATA oracles and the producer/body/material domain
 * split. It does not execute a native observation, Keychain read, code-signing/trust check,
 * signature verification, installed lifecycle or owner decision. P1 remains pending.
 */
class InstalledIdentityCompatibilityV2ContractTest {
    private val v3 = FrozenInstalledIdentityV2Inputs.load()
    private val packet = FrozenInstalledIdentityCompatibilityV2Inputs.load()
    private val legacyCanonical = v3.baseBytes("LEGACY-PREP")
    private val legacyBranch = v3.legacyCommon.text("branchName").encodeToByteArray()
    private val legacyPurpose = v3.legacyCommon.text("preparationPurpose").encodeToByteArray()
    private val v2Canonical = v3.baseBytes("V2-PREP-WIN-APP")
    private val v2Branch = v3.v2Common.text("branchName").encodeToByteArray()
    private val v2Purpose = SelfOwnedInstalledIdentityProfileV2.PREPARATION_PURPOSE.encodeToByteArray()
    private val macCanonical = v3.baseBytes("V2-TARGET-MAC-SVC")
    private val macSigning = v3.canonicalTarget("MACOS_SERVICE_V1").identity.text("signingIdentifier")
        .encodeToByteArray()
    private val v2Signing = v3.signingVector("V2-BODY-WIN-APP")
    private val v2BodyCanonical = v3.canonicalSigningBytes(v2Signing)
    private val v2EnvelopeCanonical = v3.canonicalEnvelopeBytes(v2Signing)
    private val v2RoleTag = SelfOwnedInstalledIdentityProfileV2.ROLE_TAG.encodeToByteArray()

    @Test
    fun acceptedPacketInventoryAndCanonical42RemainPinned() {
        assertEquals(76, packet.legacyLabelVectors.size)
        assertEquals(16, packet.v2BranchVectors.size)
        assertEquals(19, packet.macosSigningVectors.size)
        assertEquals(24, packet.utf8Vectors.size)
        assertEquals(8, packet.roleTagVectors.size)
        assertEquals(2, packet.legacyCapVectors.size)
        assertEquals(36, packet.factoryCells.size)
        assertEquals(6, packet.specialFactoryCells.size)
        assertEquals(7, packet.unsupportedTargets.size)
        assertEquals(12, packet.crossVersion.size)
        assertEquals(15, packet.iosOracles.size)
        assertEquals(5, packet.nativeObservations.size)
        assertEquals(42, packet.canonical42.size)
        assertEquals(28, packet.vectors.getValue("trim_whitespace_set_codepoints").jsonArray.size)
        assertEquals(
            "PENDING_LEAD_OWNER_CONFIRMATION",
            packet.vectors.getValue("p1").jsonObject.text("status"),
        )
        for (entry in packet.canonical42) {
            val id = entry.text("id")
            val bytes = canonicalBytes(id)
            assertEquals(entry.value("length"), bytes.size, "$id canonical length")
            assertEquals(entry.text("sha256"), sha256Hex(bytes), "$id canonical SHA-256")
        }
        assertEquals(0, packet.vectors.getValue("no_authority").jsonObject
            .getValue("grants").jsonArray.size)
        assertTrue(packet.vectors.getValue("no_authority").jsonObject
            .getValue("refusal_side_effects").jsonObject.values
            .all { it.jsonPrimitive.content == "0" })
        for (observation in packet.nativeObservations) {
            assertEquals("NATIVE_ONLY_NOT_DATA", observation.text("representation"))
            assertEquals("NOT_RUN", observation.text("current_result"))
        }
    }

    @Test
    fun legacyLabelVectorsUseHistoricalDomainAndRetainExactBytes() {
        for (row in packet.legacyLabelVectors) {
            val raw = row.text("label_utf8_hex").hexBytes()
            val record = legacyRecord(raw)
            assertEquals(row.value("record_length"), record.size, "${row.text("id")} record length")
            assertEquals(row.text("record_sha256"), sha256Hex(record), "${row.text("id")} record SHA-256")
            assertEquals(row.value("label_bytes"), raw.size, "${row.text("id")} label bytes")
            val decoded = raw.decodeToString(throwOnInvalidSequence = true)
            assertEquals(row.value("tested_utf16_units"), decoded.length, "${row.text("id")} UTF-16 units")
            val result = SelfOwnedLegacyInstallationCompatibilityV1.decodePreparation(record)
            val expected = expectedCode(row.text("expected"))
            if (expected == ACCEPT) {
                val data = requireData(result)
                assertEquals(decoded, data.branchName, "${row.text("id")} exact label retained")
                assertEquals(
                    record.toHex(),
                    SelfOwnedInstallationWireV1.encodePreparation(data).toHex(),
                    "${row.text("id")} exact record re-encoding",
                )
            } else {
                assertEquals(expected, code(result), "${row.text("id")} outcome")
            }
        }
    }

    @Test
    fun v2BranchAndUtf8VectorsKeepStrictNewProfileGrammar() {
        for (row in packet.v2BranchVectors) {
            val raw = row.text("label_utf8_hex").hexBytes()
            val record = v2Record(raw)
            assertEquals(row.value("record_length"), record.size, "${row.text("id")} record length")
            assertEquals(row.text("record_sha256"), sha256Hex(record), "${row.text("id")} record SHA-256")
            val result = SelfOwnedInstalledIdentityWireV2.decodePreparation(record)
            val expected = expectedCode(row.text("expected"))
            if (expected == ACCEPT) {
                val data = requireData(result)
                assertEquals(raw.decodeToString(), data.branchName, "${row.text("id")} exact V2 label")
                assertEquals(
                    record.toHex(),
                    SelfOwnedInstalledIdentityWireV2.encodePreparation(data).toHex(),
                    "${row.text("id")} exact V2 re-encoding",
                )
            } else {
                assertEquals(expected, code(result), "${row.text("id")} outcome")
            }
        }
        for (row in packet.utf8Vectors) {
            val raw = row.text("value_utf8_hex").hexBytes()
            val field = row.text("field")
            val payload = when (field) {
                "legacy_branch" -> legacyRecord(raw)
                "v2_branch" -> v2Record(raw)
                "macos_signing_identifier" -> macTarget(raw)
                else -> error("Unknown UTF-8 field $field")
            }
            assertEquals(row.value("payload_length"), payload.size, "${row.text("id")} payload length")
            assertEquals(row.text("payload_sha256"), sha256Hex(payload), "${row.text("id")} payload SHA-256")
            val actual = when (field) {
                "legacy_branch" -> code(SelfOwnedLegacyInstallationCompatibilityV1.decodePreparation(payload))
                "v2_branch" -> code(SelfOwnedInstalledIdentityWireV2.decodePreparation(payload))
                "macos_signing_identifier" -> code(SelfOwnedInstalledIdentityWireV2.decodeTarget(payload))
                else -> error("Unknown UTF-8 field $field")
            }
            assertEquals(expectedCode(row.text("expected")), actual, "${row.text("id")} UTF-8 outcome")
        }
    }

    @Test
    fun macosSigningIdentifierVectorsUseStrictCodePointGrammar() {
        for (row in packet.macosSigningVectors) {
            val raw = row.text("value_utf8_hex").hexBytes()
            val target = macTarget(raw)
            assertEquals(row.value("target_length"), target.size, "${row.text("id")} target length")
            assertEquals(row.text("target_sha256"), sha256Hex(target), "${row.text("id")} target SHA-256")
            val result = SelfOwnedInstalledIdentityWireV2.decodeTarget(target)
            val expected = expectedCode(row.text("expected"))
            if (expected == ACCEPT) {
                val data = assertIs<SelfOwnedMacServiceIdentityV2>(requireData(result))
                assertEquals(raw.decodeToString(), data.signingIdentifier, "${row.text("id")} exact identifier")
                assertEquals(
                    target.toHex(),
                    SelfOwnedInstalledIdentityWireV2.encodeTarget(data).toHex(),
                    "${row.text("id")} exact target re-encoding",
                )
            } else {
                assertEquals(expected, code(result), "${row.text("id")} outcome")
            }
        }
    }

    @Test
    fun roleTagAndLegacyPreparationCapVectorsUseExplicitOracleCodes() {
        for (row in packet.roleTagVectors) {
            val raw = row.text("tag_utf8_hex").hexBytes()
            val expected = expectedCode(row.text("expected"))
            if (row.text("layer") == "signed_body") {
                val payload = signedBody(raw)
                assertEquals(row.value("payload_length"), payload.size, "${row.text("id")} payload length")
                assertEquals(row.text("payload_sha256"), sha256Hex(payload), "${row.text("id")} payload SHA-256")
                val result = SelfOwnedInstalledIdentityWireV2.decodeSignedBody(payload)
                assertEquals(expected, code(result), "${row.text("id")} outcome")
                if (expected == ACCEPT) {
                    val data = requireData(result)
                    assertEquals(
                        payload.toHex(),
                        SelfOwnedInstalledIdentityWireV2.encodeSignedBody(data).toHex(),
                        "${row.text("id")} body re-encoding",
                    )
                }
            } else {
                val payload = signedEnvelope(raw)
                assertEquals(row.value("payload_length"), payload.size, "${row.text("id")} payload length")
                assertEquals(row.text("payload_sha256"), sha256Hex(payload), "${row.text("id")} payload SHA-256")
                val result = SelfOwnedInstalledIdentityWireV2.decodeSignedDocument(payload)
                assertEquals(expected, code(result), "${row.text("id")} outcome")
                if (expected == ACCEPT) {
                    val data = requireData(result)
                    assertEquals(
                        payload.toHex(),
                        SelfOwnedInstalledIdentityWireV2.encodeSignedDocument(data).toHex(),
                        "${row.text("id")} envelope re-encoding",
                    )
                }
            }
        }
        for (row in packet.legacyCapVectors) {
            val record = legacyCanonical.copyOf(row.value("record_length"))
            assertEquals(row.value("record_length"), record.size, "${row.text("id")} padded length")
            assertEquals(row.text("record_sha256"), sha256Hex(record), "${row.text("id")} padded SHA-256")
            assertEquals(
                row.text("expected"),
                code(SelfOwnedLegacyInstallationCompatibilityV1.decodePreparation(record)),
                "${row.text("id")} cap outcome",
            )
        }
    }

    @Test
    fun completeFactoryMatrixAndUnsupportedKindsStayNonAccepting() {
        for (cell in packet.factoryCells) {
            val factory = factory(cell.text("factory"))
            val expected = if (cell.text("discrimination") == "MATCH_TARGET_KIND") {
                ACCEPT
            } else {
                cell.text("discrimination")
            }
            assertEquals(expected, code(SelfOwnedInstalledIdentityV2.acceptKind(factory, cell.text("target"))),
                "factory ${cell.text("factory")}/${cell.text("target")}")
            if (cell.text("policy_gate") == "REFUSE_POLICY_PENDING") {
                val target = v3.canonicalTarget(cell.text("target")).let { v3.target(it.kind, it.identity) }
                assertEquals(
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING.name,
                    code(SelfOwnedInstalledIdentityV2.accept(factory, target)),
                    "iOS diagonal still policy-pending in full acceptance",
                )
            } else if (expected == ACCEPT) {
                val target = v3.canonicalTarget(cell.text("target")).let { v3.target(it.kind, it.identity) }
                assertTrue(
                    SelfOwnedInstalledIdentityV2.accept(factory, target) is SelfOwnedInstalledIdentityResultV2.Data,
                    "non-iOS exact kind is DATA-only discrimination",
                )
            }
        }
        for (cell in packet.specialFactoryCells) {
            val factory = if (cell.text("factory") == "V2_IDENTITY") {
                SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI
            } else {
                factory(cell.text("factory"))
            }
            assertEquals(
                cell.text("discrimination"),
                code(SelfOwnedInstalledIdentityV2.acceptKind(factory, cell.text("target"))),
                "special ${cell.text("factory")}/${cell.text("target")}",
            )
        }
        for (row in packet.unsupportedTargets) {
            val actual = if (row.text("factory") == "V2_TARGET_PARSER") {
                code(SelfOwnedInstalledIdentityWireV2.decodeTarget(v2TargetWithKind(row.text("kind_utf8_hex").hexBytes())))
            } else {
                code(SelfOwnedInstalledIdentityV2.acceptKind(factory(row.text("factory")), row.text("kind")))
            }
            assertEquals(row.text("expected"), actual, "${row.text("id")} unsupported target")
        }
    }

    @Test
    fun crossVersionConsumersRefuseAtTheExactLayer() {
        val sources = mapOf(
            "V1_TARGET" to SelfOwnedInstallationWireV1.encodeTarget(v3.legacyTarget()),
            "V1_PREPARATION" to SelfOwnedInstallationWireV1.encodePreparation(v3.legacyPreparation()),
            "V2_TARGET" to v3.targetVector("V2-TARGET-WIN-APP").expected.bytes,
            "V2_PREPARATION" to v3.preparationVector("V2-PREP-WIN-APP").expected.bytes,
        )
        for (row in packet.crossVersion) {
            val bytes = sources.getValue(row.text("presentation"))
            val actual = when (row.text("consumer")) {
                "LEGACY_TARGET_MATCHER" -> code(
                    SelfOwnedLegacyInstallationCompatibilityV1.matchTarget(bytes, v3.legacyTarget()),
                )
                "LEGACY_PREPARATION_PARSER" ->
                    code(SelfOwnedLegacyInstallationCompatibilityV1.decodePreparation(bytes))
                "V2_TARGET_PARSER" -> code(SelfOwnedInstalledIdentityWireV2.decodeTarget(bytes))
                "V2_PREPARATION_PARSER" -> code(SelfOwnedInstalledIdentityWireV2.decodePreparation(bytes))
                else -> error("Unknown cross-version consumer ${row.text("consumer")}")
            }
            assertEquals(expectedCode(row.text("expected")), actual, "${row.text("id")} layer outcome")
        }
    }

    @Test
    fun iosDecisionOrderAndContextAdjudicationAreRefusalOnly() {
        val canonical = v3.canonicalTarget("IOS_ACTIVE_APP_V1")
        val bundle = canonical.identity.text("bundleIdentifier")
        val group = canonical.identity.text("appIdGroup")
        for (row in packet.iosOracles) {
            val observation = row.getValue("observation").jsonObject
            val actual = iosDecision(observation, bundle, group)
            assertEquals(expectedCode(row.text("expected")), actual, "${row.text("id")} iOS oracle")
            assertTrue(actual != ACCEPT, "${row.text("id")} is not identity acceptance")
            observation["context_expected"]?.jsonPrimitive?.contentOrNull?.let {
                assertEquals(it, actual, "${row.text("id")} context adjudication")
            }
        }
        assertEquals(25, v3.iosObservations.size)
        assertEquals(19, v3.iosObservations.count { it.text("platform") == "IOS" })
        assertEquals(
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING.name,
            iosDecision(
                JsonObject(
                    mapOf(
                        "context" to kotlinx.serialization.json.JsonPrimitive("ACTIVE_FULL_APP"),
                        "observed_bundle" to kotlinx.serialization.json.JsonPrimitive(bundle),
                        "group" to kotlinx.serialization.json.JsonPrimitive(group),
                        "marker" to kotlinx.serialization.json.JsonPrimitive("PRESENT_EXACT"),
                        "key_proof" to kotlinx.serialization.json.JsonPrimitive("PRESENT"),
                        "p1" to kotlinx.serialization.json.JsonPrimitive("PENDING"),
                    ),
                ),
                bundle,
                group,
            ),
            "same-bundle complete proof remains P1",
        )
        assertFalse(
            packet.iosOracles.any { expectedCode(it.text("expected")) == ACCEPT },
            "no compatibility iOS oracle accepts while P1 is pending",
        )
    }

    @Test
    fun legacyProducerBodyMaterialAndV2ProducerPreserveTheirSeparateDomains() {
        val accepted = packet.legacyLabelVectors.filter { it.text("expected") == ACCEPT }
        for (row in accepted) {
            val label = row.text("label_utf8_hex").hexBytes().decodeToString()
            val body = v3.legacyPreparation(label)
            assertEquals(label, body.branchName)
            assertEquals(
                legacyRecord(row.text("label_utf8_hex").hexBytes()).toHex(),
                SelfOwnedInstallationWireV1.encodePreparation(body).toHex(),
                "${row.text("id")} producer exact bytes",
            )
            SelfOwnedBranchFactsV1("0".repeat(64), label, SelfOwnedKeyProtectionV1.OS_ISOLATED)
            SelfOwnedNativeMaterialView(
                v3.coordinates(v3.legacyCommon.getValue("coordinates").jsonObject),
                v3.legacyTarget(),
                label,
                SelfOwnedKeyProtectionV1.OS_ISOLATED,
                v3.legacyTarget().apkSignerSha256,
                v3.legacyTarget().androidIdCommitment,
                SelfOwnedStorageKeySecurity.SOFTWARE,
                false,
            )
        }
        for (id in listOf(
            "L-102-ASCII-161", "L-103-ASCII-640", "L-105-3BYTE-161", "L-107-SUPP-81",
            "L-108-SUPP-160", "L-109-TRIM-SPACE-ALL", "L-110-TRIM-SPACE-LEAD",
            "L-120-NUL", "L-121-C0-01", "L-128-SINGLE-NBSP",
        )) {
            val label = packet.legacyLabelVectors.single { it.text("id") == id }
                .text("label_utf8_hex").hexBytes().decodeToString()
            assertFailsWith<IllegalArgumentException>("$id producer") {
                v3.legacyPreparation(label)
            }
            assertFailsWith<IllegalArgumentException>("$id branch facts") {
                SelfOwnedBranchFactsV1("0".repeat(64), label, SelfOwnedKeyProtectionV1.OS_ISOLATED)
            }
            assertFailsWith<IllegalArgumentException>("$id material view") {
                SelfOwnedNativeMaterialView(
                    v3.coordinates(v3.legacyCommon.getValue("coordinates").jsonObject),
                    v3.legacyTarget(),
                    label,
                    SelfOwnedKeyProtectionV1.OS_ISOLATED,
                    v3.legacyTarget().apkSignerSha256,
                    v3.legacyTarget().androidIdCommitment,
                    SelfOwnedStorageKeySecurity.SOFTWARE,
                    false,
                )
            }
        }
        val canonical = v3.canonicalTarget("WINDOWS_PACKAGED_APP_V1")
        val c1 = packet.legacyLabelVectors.single { it.text("id") == "L-012-C1-0085" }
            .text("label_utf8_hex").hexBytes().decodeToString()
        assertFailsWith<IllegalArgumentException>("V2 producer rejects C1") {
            v3.preparation(canonical.kind, canonical.identity, c1)
        }
        val cf = packet.legacyLabelVectors.single { it.text("id") == "L-016-CF-110BD" }
            .text("label_utf8_hex").hexBytes().decodeToString()
        assertFailsWith<IllegalArgumentException>("V2 producer rejects supplementary Cf") {
            v3.preparation(canonical.kind, canonical.identity, cf)
        }
        v3.preparation(canonical.kind, canonical.identity, "b".repeat(640))
        assertFailsWith<IllegalArgumentException>("V2 producer caps 640 bytes") {
            v3.preparation(canonical.kind, canonical.identity, "c".repeat(641))
        }
    }

    private fun canonicalBytes(id: String): ByteArray = when {
        id == "LEGACY-TARGET" -> legacyExpected("LEGACY-TARGET")
        id == "LEGACY-BODY" -> legacyExpected("LEGACY-BODY")
        id == "LEGACY-RECORD" -> legacyExpected("LEGACY-RECORD")
        id == "LEGACY-SIGN" -> legacyExpected("LEGACY-SIGN")
        id == "LEGACY-ENV" -> legacyExpected("LEGACY-ENV")
        id.startsWith("V2-TARGET-") -> v3.targetVector(id).expected.bytes
        id.startsWith("V2-PREP-") -> v3.preparationVector(id).expected.bytes
        id.startsWith("V2-BODY-") -> v3.signingVector(id).signedBody.bytes
        id.startsWith("V2-ENV-") -> v3.signingVector(
            "V2-BODY-" + id.removePrefix("V2-ENV-"),
        ).signedEnvelope.bytes
        else -> error("Unknown canonical42 ID $id")
    }

    private fun legacyExpected(id: String): ByteArray = v3.vectors.getValue("legacy").jsonObject
        .getValue("expected").jsonObject.getValue(id).jsonObject.text("bytes_hex").hexBytes()

    private fun legacyRecord(raw: ByteArray): ByteArray {
        val branchField = field(legacyBranch)
        val suffix = int(0) + field(legacyPurpose)
        val start = legacyCanonical.size - suffix.size - branchField.size
        require(legacyCanonical.copyOfRange(start, start + branchField.size).contentEquals(branchField))
        require(legacyCanonical.copyOfRange(legacyCanonical.size - suffix.size, legacyCanonical.size)
            .contentEquals(suffix))
        return legacyCanonical.copyOfRange(0, start) + field(raw) +
            legacyCanonical.copyOfRange(legacyCanonical.size - suffix.size, legacyCanonical.size)
    }

    private fun v2Record(raw: ByteArray): ByteArray {
        val branchField = field(v2Branch)
        val suffix = field(v2Purpose)
        val start = v2Canonical.size - suffix.size - branchField.size
        require(v2Canonical.copyOfRange(start, start + branchField.size).contentEquals(branchField))
        require(v2Canonical.copyOfRange(v2Canonical.size - suffix.size, v2Canonical.size)
            .contentEquals(suffix))
        return v2Canonical.copyOfRange(0, start) + field(raw) +
            v2Canonical.copyOfRange(v2Canonical.size - suffix.size, v2Canonical.size)
    }

    private fun macTarget(raw: ByteArray): ByteArray =
        replaceUnique(macCanonical, field(macSigning), field(raw))

    private fun signedBody(rawTag: ByteArray): ByteArray =
        replaceUnique(v2BodyCanonical, field(v2RoleTag), field(rawTag))

    private fun signedEnvelope(rawTag: ByteArray): ByteArray =
        replaceUnique(v2EnvelopeCanonical, field(v2RoleTag), field(rawTag))

    private fun v2TargetWithKind(rawKind: ByteArray): ByteArray {
        val canonical = v3.baseBytes("V2-TARGET-WIN-APP")
        val kind = "WINDOWS_PACKAGED_APP_V1".encodeToByteArray()
        return replaceUnique(canonical, field(kind), field(rawKind))
    }

    private fun replaceUnique(base: ByteArray, find: ByteArray, replace: ByteArray): ByteArray {
        val first = base.indexOfSequence(find)
        require(first >= 0) { "Frozen field not found" }
        require(base.indexOfSequence(find, first + 1) < 0) { "Frozen field is not unique" }
        return base.copyOfRange(0, first) + replace + base.copyOfRange(first + find.size, base.size)
    }

    private fun iosDecision(
        observation: JsonObject,
        expectedBundle: String,
        expectedGroup: String,
    ): String {
        when (observation.text("context")) {
            "ACTIVE_FULL_APP" -> Unit
            "EXTENSION", "BACKGROUND" ->
                return SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE.name
            "APP_CLIP", "MAC_CATALYST" ->
                return SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT.name
            else -> error("Unknown iOS context ${observation.text("context")}")
        }
        val bundle = observation["observed_bundle"]?.jsonPrimitive?.contentOrNull
            ?: return SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE.name
        if (!bundle.equals(expectedBundle, ignoreCase = true)) {
            return SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH.name
        }
        when (val group = observation.text("group")) {
            "OMITTED" -> return SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE.name
            "ERR_SEC_MISSING_ENTITLEMENT" ->
                return SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_AUTHORIZATION_UNRESOLVED.name
            else -> if (group != expectedGroup) {
                return SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_SCOPE.name
            }
        }
        when (observation.text("marker")) {
            "PRESENT_EXACT" -> Unit
            "ERR_SEC_ITEM_NOT_FOUND" ->
                return SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE.name
            "PRESENT_WRONG_VALUE" ->
                return SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_SCOPE.name
            else -> error("Unknown iOS marker ${observation.text("marker")}")
        }
        if (observation.text("key_proof") == "ABSENT") {
            return SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNRESOLVED.name
        }
        if (observation.text("p1") == "PENDING") {
            return SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING.name
        }
        error("Unexpected iOS P1 state ${observation.text("p1")}")
    }

    private fun factory(value: String): SelfOwnedInstalledIdentityFactoryV2 = when (value) {
        "WINDOWS_UI" -> SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI
        "WINDOWS_SERVICE" -> SelfOwnedInstalledIdentityFactoryV2.WINDOWS_SERVICE
        "WINDOWS_PACKAGED_SERVICE" -> SelfOwnedInstalledIdentityFactoryV2.WINDOWS_PACKAGED_SERVICE
        "MACOS_UI" -> SelfOwnedInstalledIdentityFactoryV2.MACOS_UI
        "MACOS_SERVICE" -> SelfOwnedInstalledIdentityFactoryV2.MACOS_SERVICE
        "IOS_ACTIVE_APP" -> SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP
        "IOS_EXTENSION_APP" -> SelfOwnedInstalledIdentityFactoryV2.IOS_EXTENSION_APP
        // The frozen generic V2 discriminator label; unknown-kind refusal precedes this mapping.
        "V2_IDENTITY" -> SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI
        else -> error("Unknown factory $value")
    }

    private fun expectedCode(value: String): String = if (value == PACKET_ACCEPT) ACCEPT else value

    private fun code(result: SelfOwnedInstalledIdentityResultV2<*>): String = when (result) {
        is SelfOwnedInstalledIdentityResultV2.Data -> ACCEPT
        is SelfOwnedInstalledIdentityResultV2.Refused -> result.code.name
    }

    private fun <T> requireData(result: SelfOwnedInstalledIdentityResultV2<T>): T = when (result) {
        is SelfOwnedInstalledIdentityResultV2.Data -> result.value
        is SelfOwnedInstalledIdentityResultV2.Refused -> error("Unexpected refusal ${result.code}")
    }

    private fun sha256Hex(bytes: ByteArray): String = FrozenInstalledIdentityV2Inputs.sha256Hex(bytes)

    private fun String.hexBytes(): ByteArray =
        if (isEmpty()) ByteArray(0) else ByteArray(length / 2) {
            substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }

    private fun ByteArray.toHex(): String = selfOwnedHex(this)

    private fun field(value: ByteArray): ByteArray = int(value.size) + value

    private fun int(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun ByteArray.indexOfSequence(find: ByteArray, startAt: Int = 0): Int {
        if (find.isEmpty() || find.size > size) return -1
        for (start in startAt..size - find.size) {
            var matches = true
            for (index in find.indices) {
                if (this[start + index] != find[index]) { matches = false; break }
            }
            if (matches) return start
        }
        return -1
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.value(key: String): Int = getValue(key).jsonPrimitive.int

    private companion object {
        const val ACCEPT = "ACCEPT_IDENTITY"
        const val PACKET_ACCEPT = "ACCEPT"
    }
}
