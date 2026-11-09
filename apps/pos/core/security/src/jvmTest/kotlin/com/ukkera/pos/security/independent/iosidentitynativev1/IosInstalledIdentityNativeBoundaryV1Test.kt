package com.ukkera.pos.security.independent.iosidentitynativev1

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedInstalledIdentityFactoryV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityIosPolicyV1
import com.ukkera.pos.security.SelfOwnedInstalledIdentityPreparationV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityRefusalV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityWireV2
import com.ukkera.pos.security.SelfOwnedInstalledTargetBindingV1
import com.ukkera.pos.security.SelfOwnedInstalledTargetBindingWireV1
import com.ukkera.pos.security.SelfOwnedInstalledTargetPlatformV1
import com.ukkera.pos.security.SelfOwnedIosActiveAppIdentityV2
import com.ukkera.pos.security.SelfOwnedIosNativeCodeSignatureEvidenceV1
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent controlled common/JVM qualification for the owner-approved iOS installed-identity
 * boundary. Frozen cases: qa/pos/freezes/ios-installed-identity-native-boundary-v1 (IOSID-01..17).
 *
 * Every observation here is synthetic test DATA supplied through the real internal native-evidence
 * seam. A pass proves policy enforcement only. It is not an Apple code-signature, Keychain,
 * physical-device, simulator or native-signing proof, and the synthetic values must never be
 * presented as such.
 */
class IosInstalledIdentityNativeBoundaryV1Test {
    @Test
    fun iosid01_bindingAcceptedOnlyWithExpectedTargetAndCurrentFullAppObservation() {
        val target = expectedA()
        var observations = 0
        val evidence = evidence(reobserve = { observations++; null })

        val binding = requireData(SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, evidence))
        assertEquals(SelfOwnedInstalledTargetPlatformV1.IOS, binding.platform)
        assertEquals(target, binding.target)
        assertEquals(1, observations, "acceptance must re-observe the native seam")

        val bytes = SelfOwnedInstalledTargetBindingWireV1.encode(binding)
        val decoded = requireData(
            SelfOwnedInstalledTargetBindingWireV1.decodeApprovedIos(bytes, target, evidence()),
        )
        assertEquals(target, decoded.target)
        val viaCompanion = requireData(
            SelfOwnedInstalledTargetBindingV1.decodeApprovedIos(bytes, target, evidence()),
        )
        assertEquals(target, viaCompanion.target)
        assertEquals(target, requireData(SelfOwnedInstalledIdentityIosPolicyV1.acceptTarget(target, evidence())))
    }

    @Test
    fun iosid02_preparationAcceptedOnlyWithExpectedTargetAndCurrentFullAppObservation() {
        val target = expectedA()
        val preparation = preparation(target)
        val bytes = SelfOwnedInstalledIdentityWireV2.encodePreparation(preparation)

        val accepted = requireData(
            SelfOwnedInstalledIdentityIosPolicyV1.acceptPreparation(bytes, target, evidence()),
        )
        assertEquals(target, accepted.target)
        assertEquals(preparation.rootId, accepted.rootId)
        assertEquals(preparation.issuerId, accepted.issuerId)
        assertEquals(preparation.installerId, accepted.installerId)
        // The API returns preparation DATA, not a completed binding/device acceptance.
        assertTrue(accepted is SelfOwnedInstalledIdentityPreparationV2)
    }

    @Test
    fun iosid03_sameTeamSameBundleUpdateOrReplacementNeedsCurrentObservation() {
        val target = expectedA()
        var nativeCurrent = true
        val evidence = evidence(reobserve = {
            if (nativeCurrent) null
            else SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT
        })

        val first = requireData(SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, evidence))
        assertEquals(target, first.target)

        nativeCurrent = true
        val replacement = requireData(SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, evidence))
        assertEquals(target, replacement.target)

        nativeCurrent = false
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, evidence),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT,
        )
    }

    @Test
    fun iosid04_differentAppleTeamIdRefused() {
        val expected = expectedA()
        val observed = SelfOwnedIosActiveAppIdentityV2(
            bundleIdentifier = BUNDLE_A,
            appIdGroup = "TEAM999999.$BUNDLE_A",
        )
        val mismatched = evidence(target = observed)
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(expected, mismatched),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH,
        )
        assertRefused(
            SelfOwnedInstalledIdentityIosPolicyV1.acceptTarget(expected, mismatched),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH,
        )
        val bytes = SelfOwnedInstalledTargetBindingWireV1.encode(
            requireData(
                SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(
                    observed,
                    evidence(target = observed),
                ),
            ),
        )
        assertRefused(
            SelfOwnedInstalledTargetBindingWireV1.decodeApprovedIos(bytes, expected, mismatched),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH,
        )
    }

    @Test
    fun iosid05_differentBundleIdRefused() {
        val expected = expectedA()
        val observed = SelfOwnedIosActiveAppIdentityV2(
            bundleIdentifier = "com.ukkera.pos.other",
            appIdGroup = "TEAM123456.com.ukkera.pos.other",
        )
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(expected, evidence(target = observed)),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH,
        )
        val preparationBytes = SelfOwnedInstalledIdentityWireV2.encodePreparation(preparation(observed))
        assertRefused(
            SelfOwnedInstalledIdentityIosPolicyV1.acceptPreparation(
                preparationBytes,
                expected,
                evidence(),
            ),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH,
        )
    }

    @Test
    fun iosid06_missingExpectedTargetRefusedEvenWithMatchingObservation() {
        val target = expectedA()
        val accepted = requireData(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, evidence()),
        )
        val bindingBytes = SelfOwnedInstalledTargetBindingWireV1.encode(accepted)
        val targetBytes = SelfOwnedInstalledIdentityWireV2.encodeTarget(target)

        // The bytes themselves are well-formed target DATA; the expected-target-less historical
        // binding decode must still refuse rather than infer acceptance from self-declared bytes.
        assertTrue(
            SelfOwnedInstalledIdentityWireV2.decodeTarget(targetBytes) is
                SelfOwnedInstalledIdentityResultV2.Data,
            "synthetic target bytes must be well formed",
        )
        assertRefused(
            SelfOwnedInstalledTargetBindingWireV1.decode(bindingBytes),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING,
        )
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.decode(bindingBytes),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING,
        )
    }

    @Test
    fun iosid07_missingNativeObservationRefused() {
        val target = expectedA()
        var observations = 0
        val absent = evidence(reobserve = {
            observations++
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE
        })
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, absent),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE,
        )
        assertEquals(1, observations)
        assertRefused(
            SelfOwnedInstalledIdentityIosPolicyV1.acceptTarget(target, absent),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE,
        )
    }

    @Test
    fun iosid08_retainedButNonCurrentObservationRefused() {
        val target = expectedA()
        val bindingBytes = SelfOwnedInstalledTargetBindingWireV1.encode(
            requireData(SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, evidence())),
        )
        val stale = evidence(reobserve = {
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT
        })
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, stale),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT,
        )
        assertRefused(
            SelfOwnedInstalledTargetBindingWireV1.decodeApprovedIos(bindingBytes, target, stale),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT,
        )
    }

    @Test
    fun iosid09_arbitrarySerializedApprovalBytesRefused() {
        val target = expectedA()
        val accepted = requireData(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, evidence()),
        )
        val selfConsistentBytes = SelfOwnedInstalledTargetBindingWireV1.encode(accepted)

        // Self-consistent serialized binding bytes are DATA, never an approval.
        assertRefused(
            SelfOwnedInstalledTargetBindingWireV1.decode(selfConsistentBytes),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING,
        )
        // Supplying expected target + evidence does not rescue malformed or arbitrary bytes.
        assertTrue(
            SelfOwnedInstalledTargetBindingWireV1.decodeApprovedIos(
                byteArrayOf(1, 2, 3, 4),
                target,
                evidence(),
            ) is SelfOwnedInstalledIdentityResultV2.Refused,
        )
        val tampered = selfConsistentBytes.copyOf()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 0x01).toByte()
        assertTrue(
            SelfOwnedInstalledTargetBindingWireV1.decodeApprovedIos(tampered, target, evidence()) is
                SelfOwnedInstalledIdentityResultV2.Refused,
        )
    }

    @Test
    fun iosid10_retainedAcceptanceCannotSurviveLostNativeCurrentness() {
        val target = expectedA()
        var nativeCurrent = true
        var observations = 0
        val evidence = evidence(reobserve = {
            observations++
            if (nativeCurrent) null
            else SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT
        })
        val accepted = requireData(SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, evidence))
        assertEquals(1, observations)

        nativeCurrent = false
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, evidence),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT,
        )
        assertEquals(2, observations, "current policy acceptance must re-observe, not reuse DATA")
        val bindingBytes = SelfOwnedInstalledTargetBindingWireV1.encode(accepted)
        assertRefused(
            SelfOwnedInstalledTargetBindingWireV1.decodeApprovedIos(bindingBytes, target, evidence),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT,
        )
        // Historical accepted DATA is retained and unchanged, but not currently approved.
        assertEquals(target, accepted.target)
    }

    @Test
    fun iosid11_publicCallbackClaimDoesNotReplaceNativeReobservation() {
        val target = expectedA()
        var nativeCurrent = true
        var callbackSaysCurrent = false
        val evidence = evidence(reobserve = {
            if (nativeCurrent) null
            else SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE
        })
        requireData(SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, evidence))

        nativeCurrent = false
        callbackSaysCurrent = true
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, evidence),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE,
        )
        assertTrue(callbackSaysCurrent, "test-local callback claim remains set but grants nothing")
    }

    @Test
    fun iosid12_historicalP1PendingApisRemainRefused() {
        val target = expectedA()
        val bindingBytes = SelfOwnedInstalledTargetBindingWireV1.encode(
            requireData(SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, evidence())),
        )
        val targetBytes = SelfOwnedInstalledIdentityWireV2.encodeTarget(target)

        assertRefused(
            SelfOwnedInstalledIdentityV2.accept(
                SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP,
                target,
            ),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING,
        )
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.accept(
                SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP,
                target,
            ),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING,
        )
        assertRefused(
            SelfOwnedInstalledIdentityV2.acceptTarget(
                targetBytes,
                target,
                SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP,
            ),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING,
        )
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.decode(bindingBytes),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING,
        )
    }

    @Test
    fun iosid13_simulatorContextRefused() {
        val target = expectedA()
        val bindingBytes = SelfOwnedInstalledTargetBindingWireV1.encode(
            requireData(SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, evidence())),
        )
        val simulator = evidence(reobserve = {
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT
        })
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, simulator),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT,
        )
        assertRefused(
            SelfOwnedInstalledTargetBindingWireV1.decodeApprovedIos(bindingBytes, target, simulator),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT,
        )
    }

    @Test
    fun iosid14_appExtensionContextRefused() {
        val target = expectedA()
        val extensionContext = evidence(reobserve = {
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT
        })
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, extensionContext),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT,
        )
        assertRefused(
            SelfOwnedInstalledIdentityV2.accept(
                SelfOwnedInstalledIdentityFactoryV2.IOS_EXTENSION_APP,
                target,
            ),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE,
        )
        assertRefused(
            SelfOwnedInstalledIdentityV2.acceptKind(
                SelfOwnedInstalledIdentityFactoryV2.IOS_EXTENSION_APP,
                "IOS_ACTIVE_APP_V1",
            ),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE,
        )
    }

    @Test
    fun iosid15_unknownContextRefused() {
        val target = expectedA()
        val unknownContext = evidence(reobserve = {
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT
        })
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, unknownContext),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT,
        )
        assertRefused(
            SelfOwnedInstalledIdentityV2.acceptKind(
                SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP,
                "IOS_APP_CONTEXT_V1",
            ),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_NON_EXCLUSIVE_CONTEXT,
        )
        assertRefused(
            SelfOwnedInstalledIdentityV2.acceptKind(
                SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP,
                "UNKNOWN_CONTEXT",
            ),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_TARGET_KIND,
        )
    }

    @Test
    fun iosid16_validIdentityDoesNotBypassExistingCustodyOrSecurityRefusal() {
        val target = expectedA()
        for (custodyRefusal in listOf(
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_SCOPE,
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_AUTHORIZATION_UNRESOLVED,
        )) {
            val refusedEvidence = evidence(reobserve = { custodyRefusal })
            assertRefused(
                SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, refusedEvidence),
                custodyRefusal,
            )
            assertRefused(
                SelfOwnedInstalledIdentityIosPolicyV1.acceptTarget(target, refusedEvidence),
                custodyRefusal,
            )
        }
    }

    @Test
    fun iosid17_rejectedAttemptsPreservePriorAcceptedBindingAndCommittedResults() {
        val target = expectedA()
        val accepted = requireData(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, evidence()),
        )
        val baselineBytes = SelfOwnedInstalledTargetBindingWireV1.encode(accepted)
        fun assertBaselineUnchanged() {
            assertEquals(target, accepted.target)
            assertContentEquals(baselineBytes, SelfOwnedInstalledTargetBindingWireV1.encode(accepted))
        }
        assertBaselineUnchanged()

        val teamMismatch = SelfOwnedIosActiveAppIdentityV2(BUNDLE_A, "TEAM999999.$BUNDLE_A")
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(expectedA(), evidence(target = teamMismatch)),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH,
        )
        val bundleMismatch = SelfOwnedIosActiveAppIdentityV2(
            "com.ukkera.pos.other",
            "TEAM123456.com.ukkera.pos.other",
        )
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(expectedA(), evidence(target = bundleMismatch)),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH,
        )
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(
                target,
                evidence(reobserve = { SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT }),
            ),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT,
        )
        assertRefused(
            SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(
                target,
                evidence(reobserve = {
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE
                }),
            ),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE,
        )
        assertRefused(
            SelfOwnedInstalledTargetBindingWireV1.decode(baselineBytes),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING,
        )
        assertFalse(
            SelfOwnedInstalledTargetBindingWireV1.decodeApprovedIos(
                byteArrayOf(9, 9, 9),
                target,
                evidence(),
            ) is SelfOwnedInstalledIdentityResultV2.Data,
        )
        assertBaselineUnchanged()

        val fresh = requireData(SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(target, evidence()))
        assertEquals(target, fresh.target)
        assertContentEquals(baselineBytes, SelfOwnedInstalledTargetBindingWireV1.encode(fresh))
    }

    private fun expectedA(): SelfOwnedIosActiveAppIdentityV2 =
        SelfOwnedIosActiveAppIdentityV2(BUNDLE_A, "$TEAM_A.$BUNDLE_A")

    private fun evidence(
        target: SelfOwnedIosActiveAppIdentityV2 = expectedA(),
        reobserve: () -> SelfOwnedInstalledIdentityRefusalV2? = { null },
    ): SelfOwnedIosNativeCodeSignatureEvidenceV1 =
        SelfOwnedIosNativeCodeSignatureEvidenceV1(
            target = target,
            observedBundleIdentifier = target.bundleIdentifier,
            observedAppIdGroup = target.appIdGroup,
            reobserve = reobserve,
        )

    private fun preparation(
        target: SelfOwnedIosActiveAppIdentityV2,
    ): SelfOwnedInstalledIdentityPreparationV2 =
        SelfOwnedInstalledIdentityPreparationV2(
            rootId = id(1),
            rootDocumentDigest = "a".repeat(64),
            issuerId = id(2),
            delegationDigest = "b".repeat(64),
            installerId = id(3),
            installerRevision = 1,
            delegationRevision = 2,
            coordinates = SelfOwnedAllocationCoordinatesV1(
                allocationId = id(4),
                scope = BranchScope(id(5), id(6), id(7)),
                installationId = id(8),
                assignmentId = id(9),
                deviceId = id(10),
                storageInstanceId = id(11),
                storageKeyId = id(12),
                pepperKeyId = id(13),
                targetSigningKeyId = id(14),
                firstActorId = id(15),
            ),
            serial = 3,
            nonce = ByteArray(32) { index -> (index + 1).toByte() },
            target = target,
            branchName = "Synthetic Branch",
        )

    private fun id(number: Int): DomainId =
        DomainId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    private fun <T> requireData(result: SelfOwnedInstalledIdentityResultV2<T>): T {
        assertTrue(result is SelfOwnedInstalledIdentityResultV2.Data, "expected DATA but was $result")
        return (result as SelfOwnedInstalledIdentityResultV2.Data).value
    }

    private fun requireRefusal(result: SelfOwnedInstalledIdentityResultV2<*>): SelfOwnedInstalledIdentityRefusalV2 {
        assertTrue(result is SelfOwnedInstalledIdentityResultV2.Refused, "expected refusal but was $result")
        return (result as SelfOwnedInstalledIdentityResultV2.Refused).code
    }

    private fun assertRefused(
        result: SelfOwnedInstalledIdentityResultV2<*>,
        expected: SelfOwnedInstalledIdentityRefusalV2,
    ) {
        assertEquals(expected, requireRefusal(result))
    }

    private companion object {
        const val TEAM_A = "TEAM123456"
        const val BUNDLE_A = "com.ukkera.pos"
    }
}
