package com.ukkera.pos.security.independent.installedidentityv2

import com.ukkera.pos.security.SelfOwnedDocumentRoleV1
import com.ukkera.pos.security.SelfOwnedInstalledIdentityFactoryV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityProfileV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityRefusalV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityTargetV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityWireV2
import com.ukkera.pos.security.SelfOwnedInstallationWireV1
import com.ukkera.pos.security.SelfOwnedIosActiveAppIdentityV2
import com.ukkera.pos.security.SelfOwnedLegacyInstallationCompatibilityV1
import com.ukkera.pos.security.SelfOwnedSignedDocumentV1
import com.ukkera.pos.security.selfOwnedHex
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * E3-008 v3 shared implementation wiring against the accepted frozen packet.
 *
 * These are device-free L0/L1 shared checks only. They do not execute a native Windows/macOS/iOS
 * observation, an installed package, Keychain, Secure Enclave, code-signing trust, an owner
 * decision or any physical lifecycle. All 36 frozen cases remain NOT_RUN and P1 remains pending.
 * The real P-256 test below exercises JVM JCA over the production signing-message bytes; it is
 * not production signature verification, native key custody or signer trust.
 */
class InstalledIdentityV2ContractTest {
    private val inputs = FrozenInstalledIdentityV2Inputs.load()
    private val signing = FrozenInstalledIdentitySigningV2Inputs.load()
    private val signingIds: Set<String> = signing.mutations.map { it.text("id") }.toSet()

    @Test
    fun frozenPacketIntegrityAndInventoryRemainUnchanged() {
        val counts = mapOf(
            "legacy" to inputs.mutations.count { it.text("kind") in setOf("semantic", "bytes") && it.text("base").startsWith("LEGACY") },
            "v2" to inputs.mutations.count {
                it.text("kind") in setOf("semantic", "bytes") &&
                    it.text("base").startsWith("V2-")
            },
            "factory" to inputs.mutations.count { it.text("kind") == "factory" },
            "cross" to inputs.mutations.count { it.text("kind") == "cross_version" },
            "observation" to inputs.mutations.count { it.text("kind") == "observation" },
            "total" to inputs.mutations.size,
        )
        assertEquals(40, counts.getValue("legacy"), "frozen legacy mutation count")
        assertEquals(220, counts.getValue("v2"), "frozen V2 mutation count")
        assertEquals(11, counts.getValue("factory"), "frozen factory mutation count")
        assertEquals(12, counts.getValue("cross"), "frozen cross-version mutation count")
        assertEquals(25, counts.getValue("observation"), "frozen observation mutation count")
        assertEquals(308, counts.getValue("total"), "frozen total mutation count")
        assertEquals(19, inputs.targets.size, "frozen target vector count")
        assertEquals(6, inputs.preparations.size, "frozen preparation vector count")
        assertEquals(6, inputs.signing.size, "frozen signing vector count")
        assertEquals(8, inputs.capControls.size, "frozen cap control count")
        assertEquals(78, inputs.mutations.count { it.text("id") in signingIds }, "replaced v3 signed rows")
        assertEquals(230, inputs.mutations.count { it.text("id") !in signingIds }, "active non-signing v3 rows")
        assertEquals(78, signing.mutations.size, "accepted signing v2 mutation count")
        assertEquals(10, signing.controls.size, "accepted signing v2 control count")
        assertEquals(
            inputs.mutations.size,
            inputs.mutations.count { it.text("id") !in signingIds } + signing.mutations.size,
            "signing v2 replaces the 78 signed rows instead of double counting",
        )
        assertEquals(54, signing.mutations.count { it.text("disposition") == "CORRECTED" }, "corrected rows")
        assertEquals(24, signing.mutations.count { it.text("disposition") == "RETAINED" }, "retained rows")
        assertEquals(
            12,
            signing.mutations.count { it.getValue("accepted_codes").jsonArray.size == 2 },
            "12 dual-refusal body-only rows",
        )
        assertEquals(
            2,
            signing.controls.count { it.getValue("accepted_codes").jsonArray.size == 2 },
            "2 dual-refusal body-only controls",
        )
        assertEquals(
            signingIds,
            inputs.mutations.filter {
                it.text("kind") in setOf("semantic", "bytes") &&
                    (it.text("base").startsWith("V2-BODY-") || it.text("base").startsWith("V2-ENV-"))
            }.map { it.text("id") }.toSet(),
            "stable v3-to-v2 signed mutation IDs",
        )
        assertEquals(36, inputs.caseIndex.size, "frozen case count")
        assertEquals(
            (1..36).map { "E3-008-%03d".format(it) },
            inputs.caseIndex.map { it.text("id") },
            "frozen case IDs and order",
        )
        assertTrue(inputs.caseIndex.all { it.text("status") == "NOT_RUN" }, "all frozen cases remain NOT_RUN")
        assertEquals(
            "PENDING_LEAD_OWNER_CONFIRMATION",
            inputs.vectors.getValue("ios").jsonObject
                .getValue("policy_decision").jsonObject.text("status"),
            "P1 remains pending",
        )
        assertEquals(
            "NOT_RUN",
            inputs.fixtures.getValue("execution").jsonObject.text("case_status_default"),
            "fixture execution state remains NOT_RUN",
        )
    }

    @Test
    fun legacyAndroidFiveCanonicalVectorsRemainByteExactAndBodyOnlyIsRefused() {
        val legacy = inputs.vectors.getValue("legacy").jsonObject.getValue("expected").jsonObject
        val target = inputs.legacyTarget()
        val preparation = inputs.legacyPreparation()
        val record = SelfOwnedInstallationWireV1.encodePreparation(preparation)
        val body = SelfOwnedInstallationWireV1.encodePreparationBody(preparation)
        val signature = inputs.legacySignatureHex.hexBytes()
        val signing = SelfOwnedInstallationWireV1.signingMessage(
            SelfOwnedDocumentRoleV1.NATIVE_PREPARATION, record,
        )
        val envelope = SelfOwnedSignedDocumentV1(
            SelfOwnedDocumentRoleV1.NATIVE_PREPARATION, record, signature,
        ).copyWireBytes()

        assertExpected("LEGACY-TARGET", legacy.expected("LEGACY-TARGET").expectedBytes(), SelfOwnedInstallationWireV1.encodeTarget(target))
        assertExpected("LEGACY-BODY", legacy.expected("LEGACY-BODY").expectedBytes(), body)
        assertExpected("LEGACY-RECORD", legacy.expected("LEGACY-RECORD").expectedBytes(), record)
        assertExpected("LEGACY-SIGN", legacy.expected("LEGACY-SIGN").expectedBytes(), signing)
        assertExpected("LEGACY-ENV", legacy.expected("LEGACY-ENV").expectedBytes(), envelope)
        assertEquals(168, targetLength(), "legacy target length")
        assertEquals(1_043, body.size, "legacy body length")
        assertEquals(1_116, record.size, "legacy framed record length")
        assertEquals(1_190, signing.size, "legacy full-record signing length")
        assertEquals(1_262, envelope.size, "legacy full-record envelope length")

        val decodedRecord = SelfOwnedLegacyInstallationCompatibilityV1.decodePreparation(record)
        assertTrue(decodedRecord is SelfOwnedInstalledIdentityResultV2.Data, "canonical legacy record parses")
        assertTrue(requireData(decodedRecord).target.same(target), "legacy target exact match")

        val bodyOnlySigning = SelfOwnedInstallationWireV1.signingMessage(
            SelfOwnedDocumentRoleV1.NATIVE_PREPARATION, body,
        )
        assertEquals(
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED,
            refusal(SelfOwnedLegacyInstallationCompatibilityV1.decodeSignedBody(bodyOnlySigning)),
            "body-only legacy signing is not a compatible variant",
        )
        val bodyOnlyEnvelope = SelfOwnedSignedDocumentV1(
            SelfOwnedDocumentRoleV1.NATIVE_PREPARATION, body, signature,
        ).copyWireBytes()
        assertEquals(
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED,
            refusal(SelfOwnedLegacyInstallationCompatibilityV1.decodeEnvelope(bodyOnlyEnvelope)),
            "body-only legacy envelope is not a compatible variant",
        )
    }

    @Test
    fun v2CanonicalTargetsPreparationsAndSigningVectorsRemainByteExact() {
        var canonical = 0
        for (vector in inputs.targets) {
            val target = inputs.target(vector.kind, vector.identity)
            val bytes = SelfOwnedInstalledIdentityWireV2.encodeTarget(target)
            assertExpected(vector.id, vector.expected, bytes)
            val decoded = SelfOwnedInstalledIdentityWireV2.decodeTarget(vector.expected.bytes)
            assertTrue(decoded is SelfOwnedInstalledIdentityResultV2.Data, "${vector.id} decodes")
            assertTrue(
                SelfOwnedInstalledIdentityV2.matches(target, requireData(decoded)),
                "${vector.id} field-specific match",
            )
            canonical++
        }
        for (vector in inputs.preparations) {
            val preparation = inputs.preparation(vector.kind, vector.identity)
            val bytes = SelfOwnedInstalledIdentityWireV2.encodePreparation(preparation)
            assertExpected(vector.id, vector.expected, bytes)
            val decoded = SelfOwnedInstalledIdentityWireV2.decodePreparation(vector.expected.bytes)
            assertTrue(decoded is SelfOwnedInstalledIdentityResultV2.Data, "${vector.id} decodes")
            assertTrue(
                SelfOwnedInstalledIdentityV2.matches(
                    inputs.target(vector.kind, vector.identity), requireData(decoded).target,
                ),
                "${vector.id} target match",
            )
            canonical++
        }
        for (vector in inputs.signing) {
            val identity = inputs.canonicalTarget(vector.kind).identity
            val preparation = inputs.preparation(vector.kind, identity)
            val body = SelfOwnedInstalledIdentityWireV2.signingMessage(preparation)
            assertExpected(vector.id + " body", vector.signedBody, body)
            val document = SelfOwnedInstalledIdentityWireV2.encodeSignedDocument(
                preparation, inputs.v2SignatureHex.hexBytes(),
            )
            assertExpected(vector.id + " envelope", vector.signedEnvelope, document)
            val decodedBody = SelfOwnedInstalledIdentityWireV2.decodeSignedBody(vector.signedBody.bytes)
            assertTrue(decodedBody is SelfOwnedInstalledIdentityResultV2.Data, "${vector.id} body decodes")
            val decodedDocument = SelfOwnedInstalledIdentityWireV2.decodeSignedDocument(vector.signedEnvelope.bytes)
            assertTrue(decodedDocument is SelfOwnedInstalledIdentityResultV2.Data, "${vector.id} envelope decodes")
            assertTrue(
                SelfOwnedInstalledIdentityV2.matches(
                    inputs.target(vector.kind, identity),
                    requireData(decodedDocument).preparation.target,
                ),
                "${vector.id} envelope target match",
            )
            canonical += 2
        }
        assertEquals(37, canonical, "37 V2 canonical vectors reproduced; the five legacy vectors are reproduced in the sibling test")
    }

    @Test
    fun frozenInputWiringMatchesEveryProductionCanonicalVector() {
        assertEquals(
            SelfOwnedInstallationWireV1.encodeTarget(inputs.legacyTarget()).toHex(),
            inputs.baseBytes("LEGACY-TARGET").toHex(),
        )
        assertEquals(
            SelfOwnedInstallationWireV1.encodePreparation(inputs.legacyPreparation()).toHex(),
            inputs.baseBytes("LEGACY-PREP").toHex(),
        )
        assertEquals(
            SelfOwnedInstallationWireV1.signingMessage(
                SelfOwnedDocumentRoleV1.NATIVE_PREPARATION,
                SelfOwnedInstallationWireV1.encodePreparation(inputs.legacyPreparation()),
            ).toHex(),
            inputs.baseBytes("LEGACY-SIGN").toHex(),
        )
        assertEquals(
            SelfOwnedSignedDocumentV1(
                SelfOwnedDocumentRoleV1.NATIVE_PREPARATION,
                SelfOwnedInstallationWireV1.encodePreparation(inputs.legacyPreparation()),
                inputs.legacySignatureHex.hexBytes(),
            ).copyWireBytes().toHex(),
            inputs.baseBytes("LEGACY-ENV").toHex(),
        )
        for (vector in inputs.targets) {
            assertEquals(
                SelfOwnedInstalledIdentityWireV2.encodeTarget(inputs.target(vector.kind, vector.identity)).toHex(),
                inputs.baseBytes(vector.id).toHex(),
                "${vector.id} frozen input wiring",
            )
        }
        for (vector in inputs.preparations) {
            assertEquals(
                SelfOwnedInstalledIdentityWireV2.encodePreparation(inputs.preparation(vector.kind, vector.identity)).toHex(),
                inputs.baseBytes(vector.id).toHex(),
                "${vector.id} frozen input wiring",
            )
        }
        for (vector in inputs.signing) {
            val identity = inputs.canonicalTarget(vector.kind).identity
            val preparation = inputs.preparation(vector.kind, identity)
            assertEquals(
                SelfOwnedInstalledIdentityWireV2.signingMessage(preparation).toHex(),
                inputs.canonicalSigningBytes(vector).toHex(),
                "${vector.id} framed canonical body wiring",
            )
            assertEquals(
                SelfOwnedInstalledIdentityWireV2.encodeSignedDocument(
                    preparation, inputs.v2SignatureHex.hexBytes(),
                ).toHex(),
                inputs.canonicalEnvelopeBytes(vector).toHex(),
                "${vector.id} framed canonical envelope wiring",
            )
        }
    }

    @Test
    fun capControlsProduceFrozenRefusalCodes() {
        for (control in inputs.capControls) {
            assertEquals(control.expected.length, control.expected.bytes.size, "${control.id} length")
            assertEquals(control.expected.sha256, sha256Hex(control.expected.bytes), "${control.id} SHA-256")
            val actual = when {
                control.id.startsWith("CAP-TARGET-") ->
                    resultCode(SelfOwnedInstalledIdentityWireV2.decodeTarget(control.expected.bytes))
                control.id.startsWith("CAP-PREP-") ->
                    resultCode(SelfOwnedInstalledIdentityWireV2.decodePreparation(control.expected.bytes))
                control.id.startsWith("CAP-BODY-") ->
                    resultCode(SelfOwnedInstalledIdentityWireV2.decodeSignedBody(control.expected.bytes))
                control.id.startsWith("CAP-ENV-") ->
                    resultCode(SelfOwnedInstalledIdentityWireV2.decodeSignedDocument(control.expected.bytes))
                else -> error("Unknown cap control ${control.id}")
            }
            assertEquals(control.expectedCode, actual, "${control.id} frozen outcome")
        }
    }

    @Test
    fun legacyMutationsProduceFrozenCodes() {
        val legacy = inputs.mutations.filter {
            it.text("kind") in setOf("semantic", "bytes") && it.text("base").startsWith("LEGACY")
        }
        assertEquals(40, legacy.size, "legacy mutation count")
        for (mutation in legacy) assertMutation(mutation)
    }

    @Test
    fun v2MutationsProduceFrozenCodes() {
        val v2 = inputs.mutations.filter {
            it.text("kind") in setOf("semantic", "bytes") && it.text("base").startsWith("V2-") &&
                it.text("id") !in signingIds
        }
        assertEquals(142, v2.size, "non-signing V2 mutation count")
        for (mutation in v2) assertMutation(mutation)
    }

    @Test
    fun signingV2CorrectedAndRetainedRowsUseAcceptedCodes() {
        assertEquals(78, signingIds.size)
        for ((id, expected) in signing.canonicalSignedBodies) {
            assertExpected("$id signing v2 parent", expected, inputs.signingVector(id).signedBody.bytes)
        }
        for ((id, expected) in signing.canonicalSignedEnvelopes) {
            assertExpected(
                "$id signing v2 parent",
                expected,
                inputs.signingVector("V2-BODY-" + id.removePrefix("V2-ENV-")).signedEnvelope.bytes,
            )
        }
        for ((id, expected) in signing.canonicalPreparations) {
            assertExpected("$id signing v2 parent", expected, inputs.preparationVector(id).expected.bytes)
        }
        for (parent in signing.boundaryParents.values) {
            assertExpected("${parent.id} prep", parent.preparation, parent.preparation.bytes)
            val decoded = SelfOwnedInstalledIdentityWireV2.decodePreparation(parent.preparation.bytes)
            assertTrue(decoded is SelfOwnedInstalledIdentityResultV2.Data, "${parent.id} boundary prep parses")
            val expectedTarget = inputs.targetVector(parent.targetVector)
                .let { inputs.target(it.kind, it.identity) }
            assertTrue(
                SelfOwnedInstalledIdentityV2.matches(expectedTarget, requireData(decoded).target),
                "${parent.id} embeds the pinned v3 boundary target",
            )
        }
        for (mutation in signing.mutations) assertSigningV2Mutation(mutation)
        for (control in signing.controls) assertSigningV2Control(control)
    }

    @Test
    fun crossVersionConsumersAreLayerExact() {
        val cross = inputs.mutations.filter { it.text("kind") == "cross_version" }
        assertEquals(12, cross.size, "cross-version count")
        for (mutation in cross) {
            val bytes = sourceBytes(mutation.text("source"))
            assertEquals(mutation.value("source_length"), bytes.size, "${mutation.text("id")} source length")
            assertEquals(mutation.text("source_sha256"), sha256Hex(bytes), "${mutation.text("id")} source SHA-256")
            val actual = when (mutation.text("consumer")) {
                "LEGACY_V1_TARGET_MATCHER" ->
                    if (SelfOwnedLegacyInstallationCompatibilityV1.matchTarget(bytes, inputs.legacyTarget())
                        is SelfOwnedInstalledIdentityResultV2.Data
                    ) ACCEPT else refusal(
                        SelfOwnedLegacyInstallationCompatibilityV1.matchTarget(bytes, inputs.legacyTarget()),
                    ).name
                "LEGACY_V1_PREPARATION" ->
                    resultCode(SelfOwnedLegacyInstallationCompatibilityV1.decodePreparation(bytes))
                "V2_TARGET" -> resultCode(SelfOwnedInstalledIdentityWireV2.decodeTarget(bytes))
                "V2_PREPARATION" -> resultCode(SelfOwnedInstalledIdentityWireV2.decodePreparation(bytes))
                else -> error("Unknown frozen consumer ${mutation.text("consumer")}")
            }
            assertEquals(mutation.text("expected"), actual, "${mutation.text("id")} consumer-layer outcome")
        }
    }

    @Test
    fun factoryMatrixRejectsPlatformRoleAndUnknownKinds() {
        val frozen = inputs.mutations.filter { it.text("kind") == "factory" }
        assertEquals(11, frozen.size, "frozen factory mutation count")
        for (mutation in frozen) {
            val factory = factoryFor(mutation.text("factory"))
            val target = mutation.text("target")
            val actual = resultCode(SelfOwnedInstalledIdentityV2.acceptKind(factory, target))
            assertEquals(mutation.text("expected"), actual, "${mutation.text("id")} frozen factory outcome")
        }

        data class Case(
            val factory: SelfOwnedInstalledIdentityFactoryV2,
            val kind: String,
            val expected: String,
        )
        val expanded = listOf(
            Case(SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI, "WINDOWS_PACKAGED_APP_V1", ACCEPT),
            Case(SelfOwnedInstalledIdentityFactoryV2.WINDOWS_SERVICE, "WINDOWS_SERVICE_V1", ACCEPT),
            Case(SelfOwnedInstalledIdentityFactoryV2.WINDOWS_PACKAGED_SERVICE, "WINDOWS_PACKAGED_SERVICE_V1", ACCEPT),
            Case(SelfOwnedInstalledIdentityFactoryV2.MACOS_UI, "MACOS_UI_V1", ACCEPT),
            Case(SelfOwnedInstalledIdentityFactoryV2.MACOS_SERVICE, "MACOS_SERVICE_V1", ACCEPT),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP,
                "IOS_ACTIVE_APP_V1",
                ACCEPT,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.MACOS_UI,
                "WINDOWS_PACKAGED_APP_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.MACOS_SERVICE,
                "WINDOWS_SERVICE_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.MACOS_SERVICE,
                "WINDOWS_PACKAGED_SERVICE_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.WINDOWS_SERVICE,
                "MACOS_SERVICE_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI,
                "MACOS_UI_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI,
                "IOS_ACTIVE_APP_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.WINDOWS_SERVICE,
                "IOS_ACTIVE_APP_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.MACOS_UI,
                "IOS_ACTIVE_APP_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.MACOS_SERVICE,
                "IOS_ACTIVE_APP_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI,
                "WINDOWS_SERVICE_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE_MISMATCH.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI,
                "WINDOWS_PACKAGED_SERVICE_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE_MISMATCH.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.WINDOWS_SERVICE,
                "WINDOWS_PACKAGED_APP_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE_MISMATCH.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.WINDOWS_PACKAGED_SERVICE,
                "WINDOWS_PACKAGED_APP_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE_MISMATCH.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.WINDOWS_SERVICE,
                "WINDOWS_PACKAGED_SERVICE_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_TARGET_KIND.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.WINDOWS_PACKAGED_SERVICE,
                "WINDOWS_SERVICE_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_TARGET_KIND.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.MACOS_UI,
                "MACOS_SERVICE_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE_MISMATCH.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.MACOS_SERVICE,
                "MACOS_UI_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE_MISMATCH.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI,
                "OTHER",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_TARGET_KIND.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI,
                "UNKNOWN",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_TARGET_KIND.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI,
                "",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_TARGET_KIND.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI,
                "ANDROID_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_TARGET_KIND.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP,
                "IOS_APP_CONTEXT_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_NON_EXCLUSIVE_CONTEXT.name,
            ),
            Case(
                SelfOwnedInstalledIdentityFactoryV2.IOS_EXTENSION_APP,
                "IOS_ACTIVE_APP_V1",
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE.name,
            ),
        )
        for (case in expanded) {
            assertEquals(case.expected, resultCode(SelfOwnedInstalledIdentityV2.acceptKind(case.factory, case.kind)),
                "expanded representative factory matrix ${case.factory}/${case.kind}")
        }
    }

    @Test
    fun iosIdentityBearingAcceptanceRemainsPolicyPending() {
        val canonical = inputs.canonicalTarget("IOS_ACTIVE_APP_V1")
        val target = inputs.target(canonical.kind, canonical.identity)
        assertEquals(
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING.name,
            resultCode(SelfOwnedInstalledIdentityV2.accept(SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP, target)),
            "iOS factory refuses identity-bearing acceptance while P1 is pending",
        )
        val preparation = inputs.preparation(canonical.kind, canonical.identity)
        val envelope = SelfOwnedInstalledIdentityWireV2.encodeSignedDocument(
            preparation, inputs.v2SignatureHex.hexBytes(),
        )
        assertEquals(
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING.name,
            resultCode(
                SelfOwnedInstalledIdentityV2.acceptSignedDocument(
                    envelope, target, SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP,
                ),
            ),
            "iOS signed document is not accepted while P1 is pending",
        )
        val ios = inputs.iosObservations.filter { it.text("platform") == "IOS" }
        assertEquals(19, ios.size, "frozen iOS observation count")
        for (observation in ios) {
            val current = observation.text("current_expected")
            if (current == SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING.name) {
                assertEquals(
                    "ACCEPT_IOS_IDENTITY",
                    observation.getValue("p1_yes_expected").jsonPrimitive.content,
                    "${observation.text("id")} conditional P1 expectation",
                )
            } else {
                assertTrue(observation["p1_yes_expected"] == null, "${observation.text("id")} remains refused under P1")
            }
            assertTrue(
                current == ACCEPT || SelfOwnedInstalledIdentityRefusalV2.entries.any { it.name == current },
                "${observation.text("id")} frozen observation code",
            )
        }
    }

    @Test
    fun iosExpectedTargetPrecedesPolicyPendingWithoutAcceptingFallback() {
        val canonical = inputs.canonicalTarget("IOS_ACTIVE_APP_V1")
        val canonicalTarget = inputs.target(canonical.kind, canonical.identity)
        val bundle = canonical.identity.text("bundleIdentifier")
        val group = canonical.identity.text("appIdGroup")
        val prefix = group.substringBefore('.')
        val differentBundle = "$bundle.other"
        val differentBundleIdentity = SelfOwnedIosActiveAppIdentityV2(differentBundle, "$prefix.$differentBundle")
        val differentGroupIdentity = SelfOwnedIosActiveAppIdentityV2(bundle, "A1B2C3D4E5.$bundle")

        assertEquals(
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH.name,
            resultCode(SelfOwnedInstalledIdentityV2.acceptTarget(
                SelfOwnedInstalledIdentityWireV2.encodeTarget(differentBundleIdentity),
                canonicalTarget,
                SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP,
            )),
            "different-bundle iOS target is an identity mismatch, not P1",
        )
        assertEquals(
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH.name,
            resultCode(SelfOwnedInstalledIdentityV2.acceptTarget(
                SelfOwnedInstalledIdentityWireV2.encodeTarget(differentGroupIdentity),
                canonicalTarget,
                SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP,
            )),
            "different-group iOS target is an identity mismatch, not P1",
        )
        for (identity in listOf(differentBundleIdentity, differentGroupIdentity)) {
            val preparation = inputs.preparation(canonical.kind, jsonIdentity(identity))
            val body = SelfOwnedInstalledIdentityWireV2.signingMessage(preparation)
            val envelope = SelfOwnedInstalledIdentityWireV2.encodeSignedDocument(
                preparation, inputs.v2SignatureHex.hexBytes(),
            )
            assertEquals(
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH.name,
                resultCode(
                    SelfOwnedInstalledIdentityV2.acceptSignedBody(
                        body, canonicalTarget, SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP,
                    ),
                ),
                "different iOS signed body is an identity mismatch",
            )
            assertEquals(
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH.name,
                resultCode(
                    SelfOwnedInstalledIdentityV2.acceptSignedDocument(
                        envelope, canonicalTarget, SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP,
                    ),
                ),
                "different iOS envelope is an identity mismatch",
            )
        }
        // The expected-target-less factory surface still refuses P1 for every iOS target.
        assertEquals(
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING.name,
            resultCode(
                SelfOwnedInstalledIdentityV2.accept(
                    SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP, differentBundleIdentity,
                ),
            ),
            "no expected-target-less iOS acceptance exists",
        )
        val sameBundlePreparation = inputs.preparation(canonical.kind, canonical.identity)
        assertEquals(
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING.name,
            resultCode(
                SelfOwnedInstalledIdentityV2.acceptSignedDocument(
                    SelfOwnedInstalledIdentityWireV2.encodeSignedDocument(
                        sameBundlePreparation, inputs.v2SignatureHex.hexBytes(),
                    ),
                    canonicalTarget,
                    SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP,
                ),
            ),
            "same-bundle replacement remains P1 refusal",
        )
    }

    @Test
    fun unicodeCcAndCfAreRejectedByCodePointAndValidSupplementaryTextIsPreserved() {
        val bmpControls = listOf("\u00AD", "\u200B", "\u200D")
        val supplementaryControls = listOf("\uD804\uDCBD", "\uD804\uDCCD", "\uDB40\uDC20")
        for (value in bmpControls + supplementaryControls) {
            assertEquals(
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_ENCODING.name,
                labelOutcome(value),
                "branch label rejects Unicode control/format code point ${value.unicodeCodePointsHex()}",
            )
            assertEquals(
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR.name,
                signingIdentifierOutcome(value),
                "macOS signing identifier rejects Unicode control/format code point ${value.unicodeCodePointsHex()}",
            )
        }
        for (value in listOf("\uD83D\uDE00", "\uD801\uDC00", "\uD804\uDC00")) {
            assertEquals(
                ACCEPT,
                labelOutcome(value),
                "valid supplementary branch label ${value.unicodeCodePointsHex()}",
            )
            assertEquals(
                ACCEPT,
                signingIdentifierOutcome(value),
                "valid supplementary signing identifier ${value.unicodeCodePointsHex()}",
            )
        }
    }

    @Test
    fun invalidUtf8BranchLabelIsRefusedAsEncoding() {
        val canonical = inputs.baseBytes("V2-PREP-WIN-APP")
        val branchName = inputs.v2Common.text("branchName").encodeToByteArray()
        val branchField = testInt(branchName.size) + branchName
        val purpose = SelfOwnedInstalledIdentityProfileV2.PREPARATION_PURPOSE.encodeToByteArray()
        val purposeField = testInt(purpose.size) + purpose
        assertTrue(canonical.copyOfRange(canonical.size - purposeField.size, canonical.size).contentEquals(purposeField))
        val branchStart = canonical.size - purposeField.size - branchField.size
        assertTrue(canonical.copyOfRange(branchStart, branchStart + branchField.size).contentEquals(branchField))
        for (raw in listOf(
            byteArrayOf(0xC1.toByte(), 0x81.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
        )) {
            val invalid = canonical.copyOfRange(0, branchStart) + testInt(raw.size) + raw +
                canonical.copyOfRange(canonical.size - purposeField.size, canonical.size)
            assertEquals(
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_ENCODING.name,
                resultCode(SelfOwnedInstalledIdentityWireV2.decodePreparation(invalid)),
                "invalid UTF-8 branch label is refused",
            )
        }
    }

    @Test
    fun nativeObservationOraclesRemainNotRunWithExactPlatformMapping() {
        val observations = inputs.iosObservations
        assertEquals(25, observations.size, "25 frozen native observation oracles")
        assertEquals(19, observations.count { it.text("platform") == "IOS" }, "iOS observations -> E3-028/E3-009/010")
        assertEquals(4, observations.count { it.text("platform") == "MACOS" }, "macOS observations -> E3-021")
        assertEquals(2, observations.count { it.text("platform") == "WINDOWS" }, "Windows observations -> E3-014")
        for (observation in observations) {
            val current = observation.text("current_expected")
            assertTrue(
                current == ACCEPT || SelfOwnedInstalledIdentityRefusalV2.entries.any { it.name == current },
                "${observation.text("id")} frozen native-oracle code",
            )
        }
        // Deliberately no native execution here. These oracles are NOT_RUN and are not a PASS.
    }

    @Test
    fun realP256SignatureOverProductionSigningMessageIsShapeAndCryptoChecked() {
        val canonical = inputs.canonicalTarget("WINDOWS_PACKAGED_APP_V1")
        val target = inputs.target(canonical.kind, canonical.identity)
        val preparation = inputs.preparation(canonical.kind, canonical.identity)
        val message = SelfOwnedInstalledIdentityWireV2.signingMessage(preparation)
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(keyPair.private); update(message); sign()
        }
        assertEquals(64, signature.size, "P-256 P1363 signature shape")
        val envelope = SelfOwnedInstalledIdentityWireV2.encodeSignedDocument(preparation, signature)
        val decoded = SelfOwnedInstalledIdentityWireV2.decodeSignedDocument(envelope)
        assertTrue(decoded is SelfOwnedInstalledIdentityResultV2.Data, "real signed envelope parses")
        val record = requireData(decoded).preparation.copyWireBytes()
        val exactMessage = SelfOwnedInstalledIdentityWireV2.signingMessageForPreparation(record)
        assertTrue(exactMessage is SelfOwnedInstalledIdentityResultV2.Data, "retained record signing message")
        assertTrue(
            message.contentEquals(requireData(exactMessage)),
            "signing message is the entire framed preparation record",
        )
        assertTrue(verifyP256(keyPair.public, message, signature), "real signature verifies")
        val tampered = message.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFalse(verifyP256(keyPair.public, tampered, signature), "tampered message fails")
        assertFalse(
            verifyP256(keyPair.public, message, ByteArray(64) { 0x5a }),
            "frozen synthetic signature is DATA-only and does not verify",
        )
        assertTrue(
            SelfOwnedInstalledIdentityV2.acceptSignedDocument(
                envelope, target, SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI,
            ) is SelfOwnedInstalledIdentityResultV2.Data,
            "structurally valid Windows signed envelope is accepted as DATA",
        )
    }

    @Test
    fun decodedIdentityDataExposesNoAuthorityOperation() {
        val names = SelfOwnedInstalledIdentityV2::class.java.methods.map { it.name.lowercase() }
        assertTrue(names.none { it.contains("authoriz") || it.contains("grant") || it.contains("admit") },
            "shared identity consumer exposes no authority operation")
        val targetNames = SelfOwnedInstalledIdentityTargetV2::class.java.methods.map { it.name.lowercase() }
        assertTrue(targetNames.none { it.contains("authoriz") || it.contains("grant") || it.contains("admit") },
            "decoded target exposes no authority operation")
        val preparationNames = com.ukkera.pos.security.SelfOwnedInstalledIdentityPreparationV2::class.java
            .methods.map { it.name.lowercase() }
        assertTrue(preparationNames.none { it.contains("authoriz") || it.contains("grant") || it.contains("admit") },
            "decoded preparation exposes no authority operation")
    }

    private fun assertSigningV2Mutation(mutation: JsonObject) {
        val id = mutation.text("id")
        val artifact = mutation.text("artifact")
        val parent = signing.parentBytes(mutation)
        val new = mutation.getValue("new").jsonObject
        val old = mutation.getValue("old").jsonObject
        val active = new.hexBytes()
        val v3 = inputs.mutations.single { it.text("id") == id }
        val codes = assertAcceptedCodes(id, mutation)
        val declaredField = declaredField(mutation.getValue("change").jsonObject, artifact)

        assertTrue(parent.size == mutation.value("parent_length"), "$id frozen parent length")
        assertEquals(mutation.text("parent_sha256"), sha256Hex(parent), "$id frozen parent SHA-256")
        assertEquals(new.value("length"), active.size, "$id active new length")
        assertEquals(new.text("sha256"), sha256Hex(active), "$id active new SHA-256")
        assertEquals(
            listOf(declaredField),
            new.changedFields(),
            "$id active new changes exactly the declared field",
        )
        assertFieldChange(artifact, parent, active, mutation.getValue("change").jsonObject)

        assertEquals(v3.value("mutated_length"), old.value("length"), "$id preserved v3 old length")
        assertEquals(v3.text("mutated_sha256"), old.text("sha256"), "$id preserved v3 old SHA-256")
        assertEquals(v3.text("expected"), mutation.text("expected"), "$id preserved v3 reference code")
        assertEquals(old.value("length"), old.hexBytes().size, "$id historical old literal length")
        assertEquals(old.text("sha256"), sha256Hex(old.hexBytes()), "$id historical old literal SHA-256")
        if (mutation.text("disposition") == "RETAINED") {
            assertTrue(old.hexBytes().contentEquals(active), "$id retained old bytes equal active bytes")
            assertEquals(listOf("body"), old.changedFields(), "$id retained historical change")
        } else {
            assertFalse(old.hexBytes().contentEquals(active), "$id corrected old bytes differ from active bytes")
            assertEquals(
                setOf("body", declaredField),
                old.changedFields().toSet(),
                "$id corrected historical old input has body plus the declared change",
            )
        }

        val actual = signingResultCode(artifact, active)
        assertTrue(actual in codes, "$id returned $actual outside accepted_codes $codes")
        if (codes.size == 1) {
            assertEquals(codes.single(), mutation.text("expected"), "$id preserved single reference code")
        }
    }

    private fun assertSigningV2Control(control: JsonObject) {
        val id = control.text("id")
        val artifact = control.text("artifact")
        val parent = signing.controlParentBytes(control)
        val active = control.hexBytes()
        val codes = assertAcceptedCodes(id, control)
        val declaredField = declaredField(control.getValue("change").jsonObject, artifact)

        assertTrue(parent.size == control.value("parent_length"), "$id frozen boundary parent length")
        assertEquals(control.text("parent_sha256"), sha256Hex(parent), "$id frozen boundary parent SHA-256")
        assertEquals(control.value("length"), active.size, "$id active control length")
        assertEquals(control.text("sha256"), sha256Hex(active), "$id active control SHA-256")
        assertEquals(listOf(declaredField), control.changedFields(), "$id control changes one field")
        assertFieldChange(artifact, parent, active, control.getValue("change").jsonObject)

        val actual = signingResultCode(artifact, active)
        assertTrue(actual in codes, "$id returned $actual outside accepted_codes $codes")
        if (codes.size == 1) {
            assertEquals(codes.single(), control.text("expected"), "$id control reference code")
        }
    }

    private fun assertAcceptedCodes(id: String, row: JsonObject): List<String> {
        val codes = row.getValue("accepted_codes").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(codes.isNotEmpty(), "$id has a nonempty active oracle")
        assertTrue(codes.all { it != ACCEPT }, "$id active oracle is refusal-only")
        assertTrue(
            codes.all { code -> SelfOwnedInstalledIdentityRefusalV2.entries.any { it.name == code } },
            "$id active oracle contains only frozen refusal codes",
        )
        assertTrue(row.text("expected") in codes, "$id reference code remains in accepted_codes")
        val bodyOnly = row.text("id").contains("BODYONLY")
        if (bodyOnly) {
            assertEquals(
                setOf(
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED.name,
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_ENCODING.name,
                ),
                codes.toSet(),
                "$id body-only dual refusal",
            )
        } else {
            assertEquals(1, codes.size, "$id single refusal")
        }
        return codes
    }

    private fun declaredField(change: JsonObject, artifact: String): String = when (change.text("op")) {
        "append_hex" -> "trailing"
        "replace_field" -> when (val field = change.text("field")) {
            "role_tag", "profile", "domain" -> field
            "body_hex" -> "body"
            "signature_hex" -> {
                check(artifact == "signed_envelope") { "Signature change on non-envelope artifact" }
                "signature"
            }
            else -> error("Unknown signing change field $field")
        }
        else -> error("Unknown signing change operation ${change.text("op")}")
    }

    /**
     * Test-side literal framing parser. It is deliberately independent of the production
     * decoder: the packet's parent and active bytes are compared field by field so a byte that
     * silently changes another field cannot pass as the declared single-field mutation.
     */
    private fun assertFieldChange(
        artifact: String,
        parent: ByteArray,
        active: ByteArray,
        change: JsonObject,
    ) {
        val before = parseArtifact(artifact, parent)
        val after = parseArtifact(artifact, active)
        when (change.text("op")) {
            "append_hex" -> {
                assertTrue(before.profile == after.profile)
                assertTrue(before.domain == after.domain)
                assertTrue(before.roleTag == after.roleTag)
                assertTrue(before.body.contentEquals(after.body))
                assertTrue(before.signature.contentEquals(after.signature))
                assertTrue(after.trailing.contentEquals(change.hexBytes("value_hex")))
            }
            "replace_field" -> {
                when (change.text("field")) {
                    "role_tag" -> {
                        assertEquals(change.text("value"), after.roleTag)
                        assertTrue(before.profile == after.profile)
                        assertTrue(before.domain == after.domain)
                        assertTrue(before.body.contentEquals(after.body))
                        assertTrue(before.signature.contentEquals(after.signature))
                    }
                    "profile" -> {
                        assertEquals(change.text("value"), after.profile)
                        assertTrue(before.domain == after.domain)
                        assertTrue(before.roleTag == after.roleTag)
                        assertTrue(before.body.contentEquals(after.body))
                        assertTrue(before.signature.contentEquals(after.signature))
                    }
                    "domain" -> {
                        assertEquals(change.text("value"), after.domain)
                        assertTrue(before.profile == after.profile)
                        assertTrue(before.roleTag == after.roleTag)
                        assertTrue(before.body.contentEquals(after.body))
                        assertTrue(before.signature.contentEquals(after.signature))
                    }
                    "signature_hex" -> {
                        assertTrue(after.signature.contentEquals(change.hexBytes("value")))
                        assertTrue(before.profile == after.profile)
                        assertTrue(before.domain == after.domain)
                        assertTrue(before.roleTag == after.roleTag)
                        assertTrue(before.body.contentEquals(after.body))
                    }
                    "body_hex" -> {
                        assertTrue(after.body.contentEquals(change.hexBytes("value_hex")))
                        assertTrue(before.profile == after.profile)
                        assertTrue(before.domain == after.domain)
                        assertTrue(before.roleTag == after.roleTag)
                        assertTrue(before.signature.contentEquals(after.signature))
                    }
                    else -> error("Unknown signing replacement ${change.text("field")}")
                }
                assertTrue(after.trailing.contentEquals(before.trailing))
            }
            else -> error("Unknown signing change operation ${change.text("op")}")
        }
    }

    private fun parseArtifact(artifact: String, bytes: ByteArray): ParsedArtifact {
        var at = 0
        fun int(): Int {
            require(bytes.size - at >= 4) { "Truncated test framing" }
            var value = 0
            repeat(4) { value = (value shl 8) or (bytes[at++].toInt() and 255) }
            return value
        }
        fun field(): ByteArray {
            val size = int()
            require(size >= 0 && bytes.size - at >= size) { "Truncated test field" }
            return bytes.copyOfRange(at, at + size).also { at += size }
        }
        fun string(): String = field().decodeToString(throwOnInvalidSequence = true)
        val profile = string()
        val domain = string()
        val roleTag = string()
        val body = field()
        val signature = if (artifact == "signed_envelope") field() else ByteArray(0)
        return ParsedArtifact(
            profile, domain, roleTag, body, signature, bytes.copyOfRange(at, bytes.size),
        )
    }

    private fun signingResultCode(artifact: String, bytes: ByteArray): String = when (artifact) {
        "signed_body" -> resultCode(SelfOwnedInstalledIdentityWireV2.decodeSignedBody(bytes))
        "signed_envelope" -> resultCode(SelfOwnedInstalledIdentityWireV2.decodeSignedDocument(bytes))
        else -> error("Unknown signing artifact $artifact")
    }

    private fun labelOutcome(value: String): String = resultCode(
        SelfOwnedInstalledIdentityWireV2.decodePreparation(
            inputs.baseBytes(
                "V2-PREP-WIN-APP",
                mapOf("preparation.branchName" to JsonPrimitive(value)),
            ),
        ),
    )

    private fun signingIdentifierOutcome(value: String): String = resultCode(
        SelfOwnedInstalledIdentityWireV2.decodeTarget(
            inputs.baseBytes(
                "V2-TARGET-MAC-SVC",
                mapOf("identity.signingIdentifier" to JsonPrimitive(value)),
            ),
        ),
    )

    private fun jsonIdentity(value: SelfOwnedIosActiveAppIdentityV2): JsonObject = JsonObject(
        mapOf(
            "bundleIdentifier" to JsonPrimitive(value.bundleIdentifier),
            "appIdGroup" to JsonPrimitive(value.appIdGroup),
        ),
    )

    private fun String.unicodeCodePointsHex(): String = buildString {
        val source = this@unicodeCodePointsHex
        var index = 0
        while (index < source.length) {
            val first = source[index]
            val codePoint = if (first in '\uD800'..'\uDBFF' && index + 1 < source.length) {
                ((first.code - 0xD800) shl 10) + (source[index + 1].code - 0xDC00) + 0x10000
            } else {
                first.code
            }
            append("U+").append(codePoint.toString(16).uppercase().padStart(4, '0'))
            index += if (codePoint >= 0x10000) 2 else 1
        }
    }

    private fun assertMutation(mutation: JsonObject) {
        val id = mutation.text("id")
        val bytes = inputs.mutatedBytes(mutation)
        assertEquals(mutation.value("mutated_length"), bytes.size, "$id frozen input length")
        assertEquals(mutation.text("mutated_sha256"), sha256Hex(bytes), "$id frozen input SHA-256")
        val actual = evaluateMutation(mutation, bytes)
        assertEquals(mutation.text("expected"), actual, "$id frozen outcome")
    }

    private fun evaluateMutation(mutation: JsonObject, bytes: ByteArray): String {
        val base = mutation.text("base")
        return when {
            base == "LEGACY-TARGET" -> legacyMatch(
                SelfOwnedLegacyInstallationCompatibilityV1.matchTarget(bytes, inputs.legacyTarget()),
            )
            base == "LEGACY-PREP" -> legacyPreparationOutcome(
                SelfOwnedLegacyInstallationCompatibilityV1.decodePreparation(bytes),
            )
            base == "LEGACY-SIGN" -> legacyPreparationOutcome(
                SelfOwnedLegacyInstallationCompatibilityV1.decodeSignedBody(bytes),
            )
            base == "LEGACY-ENV" -> legacyPreparationOutcome(
                SelfOwnedLegacyInstallationCompatibilityV1.decodeEnvelope(bytes),
            )
            base.startsWith("V2-TARGET-") -> {
                val expected = expectedTargetForBase(base)
                when (val decoded = SelfOwnedInstalledIdentityWireV2.decodeTarget(bytes)) {
                    is SelfOwnedInstalledIdentityResultV2.Refused -> decoded.code.name
                    is SelfOwnedInstalledIdentityResultV2.Data ->
                        SelfOwnedInstalledIdentityV2.refusalFor(expected, decoded.value)?.name ?: ACCEPT
                }
            }
            base.startsWith("V2-PREP-") -> {
                val expected = expectedTargetForBase(base)
                when (val decoded = SelfOwnedInstalledIdentityWireV2.decodePreparation(bytes)) {
                    is SelfOwnedInstalledIdentityResultV2.Refused -> decoded.code.name
                    is SelfOwnedInstalledIdentityResultV2.Data ->
                        SelfOwnedInstalledIdentityV2.refusalFor(expected, decoded.value.target)?.name ?: ACCEPT
                }
            }
            base.startsWith("V2-BODY-") -> {
                val expected = expectedTargetForBase(base)
                when (val decoded = SelfOwnedInstalledIdentityWireV2.decodeSignedBody(bytes)) {
                    is SelfOwnedInstalledIdentityResultV2.Refused -> decoded.code.name
                    is SelfOwnedInstalledIdentityResultV2.Data ->
                        SelfOwnedInstalledIdentityV2.refusalFor(expected, decoded.value.preparation.target)?.name
                            ?: ACCEPT
                }
            }
            base.startsWith("V2-ENV-") -> {
                val expected = expectedTargetForBase(base)
                when (val decoded = SelfOwnedInstalledIdentityWireV2.decodeSignedDocument(bytes)) {
                    is SelfOwnedInstalledIdentityResultV2.Refused -> decoded.code.name
                    is SelfOwnedInstalledIdentityResultV2.Data ->
                        SelfOwnedInstalledIdentityV2.refusalFor(expected, decoded.value.preparation.target)?.name
                            ?: ACCEPT
                }
            }
            else -> error("Unknown frozen mutation base $base")
        }
    }

    private fun legacyMatch(result: SelfOwnedInstalledIdentityResultV2<*>): String =
        if (result is SelfOwnedInstalledIdentityResultV2.Data) ACCEPT else refusal(result).name

    private fun legacyPreparationOutcome(
        result: SelfOwnedInstalledIdentityResultV2<com.ukkera.pos.security.SelfOwnedPreparationBodyV1>,
    ): String = when (result) {
        is SelfOwnedInstalledIdentityResultV2.Refused -> result.code.name
        is SelfOwnedInstalledIdentityResultV2.Data ->
            if (result.value.target.same(inputs.legacyTarget())) ACCEPT
            else SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH.name
    }

    private fun expectedTargetForBase(base: String): SelfOwnedInstalledIdentityTargetV2 = when {
        base.startsWith("V2-TARGET-") -> inputs.targetVector(base).let { inputs.target(it.kind, it.identity) }
        base.startsWith("V2-PREP-") -> inputs.preparationVector(base).let { inputs.target(it.kind, it.identity) }
        base.startsWith("V2-BODY-") -> inputs.signingVector(base).let {
            inputs.target(it.kind, inputs.canonicalTarget(it.kind).identity)
        }
        base.startsWith("V2-ENV-") -> inputs.signingVector(
            "V2-BODY-" + base.removePrefix("V2-ENV-"),
        ).let {
            inputs.target(it.kind, inputs.canonicalTarget(it.kind).identity)
        }
        else -> error("No expected target for $base")
    }

    private fun sourceBytes(source: String): ByteArray = when {
        source == "LEGACY-TARGET" -> SelfOwnedInstallationWireV1.encodeTarget(inputs.legacyTarget())
        source == "LEGACY-PREP" -> SelfOwnedInstallationWireV1.encodePreparation(inputs.legacyPreparation())
        source.startsWith("V2-TARGET-") -> inputs.targetVector(source).expected.bytes
        source.startsWith("V2-PREP-") -> inputs.preparationVector(source).expected.bytes
        else -> error("Unknown frozen cross-version source $source")
    }

    private fun factoryFor(factory: String): SelfOwnedInstalledIdentityFactoryV2 = when (factory) {
        "WINDOWS_UI" -> SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI
        "WINDOWS_SERVICE" -> SelfOwnedInstalledIdentityFactoryV2.WINDOWS_SERVICE
        "WINDOWS_PACKAGED_SERVICE" -> SelfOwnedInstalledIdentityFactoryV2.WINDOWS_PACKAGED_SERVICE
        "MACOS_UI" -> SelfOwnedInstalledIdentityFactoryV2.MACOS_UI
        "MACOS_SERVICE" -> SelfOwnedInstalledIdentityFactoryV2.MACOS_SERVICE
        "IOS_ACTIVE_APP" -> SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP
        "IOS_EXTENSION_APP" -> SelfOwnedInstalledIdentityFactoryV2.IOS_EXTENSION_APP
        // The frozen label names the generic V2 identity discriminator; the target kind is the
        // value under test, and any concrete V2 factory returns REFUSE_TARGET_KIND first.
        "V2_IDENTITY" -> SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI
        else -> error("Unknown frozen factory $factory")
    }

    private fun assertExpected(label: String, expected: ExpectedBytes, actual: ByteArray) {
        assertEquals(expected.length, actual.size, "$label byte length")
        assertEquals(expected.sha256, sha256Hex(actual), "$label SHA-256")
        assertTrue(expected.bytes.contentEquals(actual), "$label exact bytes")
    }

    private fun resultCode(result: SelfOwnedInstalledIdentityResultV2<*>): String = when (result) {
        is SelfOwnedInstalledIdentityResultV2.Data -> ACCEPT
        is SelfOwnedInstalledIdentityResultV2.Refused -> result.code.name
    }

    private fun refusal(result: SelfOwnedInstalledIdentityResultV2<*>): SelfOwnedInstalledIdentityRefusalV2 =
        (result as SelfOwnedInstalledIdentityResultV2.Refused).code

    private fun targetLength(): Int = SelfOwnedInstallationWireV1.encodeTarget(inputs.legacyTarget()).size

    private fun <T> requireData(result: SelfOwnedInstalledIdentityResultV2<T>): T = when (result) {
        is SelfOwnedInstalledIdentityResultV2.Data -> result.value
        is SelfOwnedInstalledIdentityResultV2.Refused -> error("Unexpected frozen refusal ${result.code}")
    }

    private fun verifyP256(publicKey: PublicKey, message: ByteArray, signature: ByteArray): Boolean =
        try {
            Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initVerify(publicKey); update(message); verify(signature)
            }
        } catch (_: Exception) { false }

    private fun sha256Hex(bytes: ByteArray): String = FrozenInstalledIdentityV2Inputs.sha256Hex(bytes)

    private fun String.hexBytes(): ByteArray =
        if (isEmpty()) ByteArray(0) else ByteArray(length / 2) {
            substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }

    private fun ByteArray.toHex(): String = selfOwnedHex(this)

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.value(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.changedFields(): List<String> =
        getValue("changed_fields_vs_parent").jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.hexBytes(): ByteArray = text("bytes_hex").hexBytes()

    private fun JsonObject.hexBytes(key: String): ByteArray = text(key).hexBytes()

    private fun JsonObject.expected(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.expectedBytes(): ExpectedBytes = ExpectedBytes(
        getValue("bytes_hex").jsonPrimitive.content.hexBytes(),
        getValue("length").jsonPrimitive.int,
        getValue("sha256").jsonPrimitive.content,
    )

    private data class ParsedArtifact(
        val profile: String,
        val domain: String,
        val roleTag: String,
        val body: ByteArray,
        val signature: ByteArray,
        val trailing: ByteArray,
    )

    private fun testInt(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private companion object {
        const val ACCEPT = "ACCEPT_IDENTITY"
    }
}
