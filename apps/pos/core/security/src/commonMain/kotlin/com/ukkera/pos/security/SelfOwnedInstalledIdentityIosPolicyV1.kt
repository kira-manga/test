package com.ukkera.pos.security

/**
 * Distinct owner-approved P1 policy boundary for the iOS active full application.
 *
 * The frozen [SelfOwnedInstalledIdentityV2] surface still returns
 * [SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING] for every iOS
 * identity-bearing path. This additive boundary is the only path that applies the
 * 9 November 2026 owner approval. It requires [SelfOwnedIosNativeCodeSignatureEvidenceV1],
 * which only the native iOS observation can create after an explicit App ID keychain
 * access-group read succeeds.
 *
 * This object accepts identity/preparation DATA only. It never creates or proves an
 * installation key, key possession, a native owner, device admission, employee
 * authentication or Master authority. Key custody and the separate possession proof
 * remain E3-009/010 obligations and are still required before any admission claim.
 */
public object SelfOwnedInstalledIdentityIosPolicyV1 {
    /** Additive policy identity; it is not a new V2 target/preparation/signing wire profile. */
    public const val POLICY_ID: String = "ukkera.installation/ios-active-app-approved/v1"
    public const val POLICY_VERSION: Int = 1
    public const val OWNER_DECISION: String =
        "P1_APPROVED_SAME_APPLE_TEAM_SAME_BUNDLE_ID_UPDATE_OR_REPLACEMENT"
    public const val NATIVE_PROOF: String =
        "OS_ENFORCED_CODE_SIGNING_APP_ID_KEYCHAIN_ACCESS_GROUP"

    /**
     * Accept an already-observed target only when the native code-signature evidence and
     * the exact expected bundle/App-ID-group both match. The result is DATA.
     */
    public fun acceptTarget(
        expected: SelfOwnedIosActiveAppIdentityV2,
        evidence: SelfOwnedIosNativeCodeSignatureEvidenceV1,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedIosActiveAppIdentityV2> {
        val refusal = evidenceRefusal(expected, evidence)
        return if (refusal == null) {
            SelfOwnedInstalledIdentityResultV2.Data(expected)
        } else {
            SelfOwnedInstalledIdentityResultV2.Refused(refusal)
        }
    }

    /** Parse the canonical V2 target, then apply native evidence and expected-target checks. */
    public fun acceptTarget(
        bytes: ByteArray,
        expected: SelfOwnedIosActiveAppIdentityV2,
        evidence: SelfOwnedIosNativeCodeSignatureEvidenceV1,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedIosActiveAppIdentityV2> {
        evidenceRefusal(expected, evidence)?.let {
            return SelfOwnedInstalledIdentityResultV2.Refused(it)
        }
        return when (val decoded = SelfOwnedInstalledIdentityWireV2.decodeTarget(bytes)) {
            is SelfOwnedInstalledIdentityResultV2.Refused -> decoded
            is SelfOwnedInstalledIdentityResultV2.Data -> {
                val target = decoded.value
                if (target !is SelfOwnedIosActiveAppIdentityV2) {
                    SelfOwnedInstalledIdentityResultV2.Refused(
                        SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH,
                    )
                } else {
                    val refusal = SelfOwnedInstalledIdentityV2.refusalFor(expected, target)
                    if (refusal == null) {
                        SelfOwnedInstalledIdentityResultV2.Data(target)
                    } else {
                        SelfOwnedInstalledIdentityResultV2.Refused(refusal)
                    }
                }
            }
        }
    }

    /** Parse the canonical V2 preparation record, then apply native evidence and target checks. */
    public fun acceptPreparation(
        bytes: ByteArray,
        expected: SelfOwnedIosActiveAppIdentityV2,
        evidence: SelfOwnedIosNativeCodeSignatureEvidenceV1,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledIdentityPreparationV2> {
        evidenceRefusal(expected, evidence)?.let {
            return SelfOwnedInstalledIdentityResultV2.Refused(it)
        }
        return when (val decoded = SelfOwnedInstalledIdentityWireV2.decodePreparation(bytes)) {
            is SelfOwnedInstalledIdentityResultV2.Refused -> decoded
            is SelfOwnedInstalledIdentityResultV2.Data -> {
                val refusal = targetRefusal(expected, decoded.value.target)
                if (refusal == null) {
                    SelfOwnedInstalledIdentityResultV2.Data(decoded.value)
                } else {
                    SelfOwnedInstalledIdentityResultV2.Refused(refusal)
                }
            }
        }
    }

    /** Parse the canonical V2 signing message, then apply native evidence and target checks. */
    public fun acceptSignedBody(
        bytes: ByteArray,
        expected: SelfOwnedIosActiveAppIdentityV2,
        evidence: SelfOwnedIosNativeCodeSignatureEvidenceV1,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledIdentitySignedBodyV2> {
        evidenceRefusal(expected, evidence)?.let {
            return SelfOwnedInstalledIdentityResultV2.Refused(it)
        }
        return when (val decoded = SelfOwnedInstalledIdentityWireV2.decodeSignedBody(bytes)) {
            is SelfOwnedInstalledIdentityResultV2.Refused -> decoded
            is SelfOwnedInstalledIdentityResultV2.Data -> {
                val refusal = targetRefusal(expected, decoded.value.preparation.target)
                if (refusal == null) {
                    SelfOwnedInstalledIdentityResultV2.Data(decoded.value)
                } else {
                    SelfOwnedInstalledIdentityResultV2.Refused(refusal)
                }
            }
        }
    }

    /**
     * Parse the canonical V2 signed envelope, then apply native evidence and target checks.
     * The envelope signature is still DATA: this function does not verify it and never grants
     * key custody or authority.
     */
    public fun acceptSignedDocument(
        bytes: ByteArray,
        expected: SelfOwnedIosActiveAppIdentityV2,
        evidence: SelfOwnedIosNativeCodeSignatureEvidenceV1,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledIdentitySignedDocumentV2> {
        evidenceRefusal(expected, evidence)?.let {
            return SelfOwnedInstalledIdentityResultV2.Refused(it)
        }
        return when (val decoded = SelfOwnedInstalledIdentityWireV2.decodeSignedDocument(bytes)) {
            is SelfOwnedInstalledIdentityResultV2.Refused -> decoded
            is SelfOwnedInstalledIdentityResultV2.Data -> {
                val refusal = targetRefusal(expected, decoded.value.preparation.target)
                if (refusal == null) {
                    SelfOwnedInstalledIdentityResultV2.Data(decoded.value)
                } else {
                    SelfOwnedInstalledIdentityResultV2.Refused(refusal)
                }
            }
        }
    }

    private fun evidenceRefusal(
        expected: SelfOwnedIosActiveAppIdentityV2,
        evidence: SelfOwnedIosNativeCodeSignatureEvidenceV1,
    ): SelfOwnedInstalledIdentityRefusalV2? {
        if (!SelfOwnedInstalledIdentityGrammarV2.asciiEqualsIgnoreCase(
                expected.bundleIdentifier, evidence.observedBundleIdentifier,
            ) ||
            expected.appIdGroup != evidence.observedAppIdGroup ||
            !SelfOwnedInstalledIdentityGrammarV2.asciiEqualsIgnoreCase(
                expected.bundleIdentifier, evidence.target.bundleIdentifier,
            ) ||
            expected.appIdGroup != evidence.target.appIdGroup
        ) {
            return SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH
        }
        // Re-observe the native context for every acceptance. Retaining this DATA value
        // cannot retain an active/unlocked application or a previous Keychain entitlement read.
        return evidence.currentRefusal()
    }

    private fun targetRefusal(
        expected: SelfOwnedIosActiveAppIdentityV2,
        actual: SelfOwnedInstalledIdentityTargetV2,
    ): SelfOwnedInstalledIdentityRefusalV2? {
        if (actual !is SelfOwnedIosActiveAppIdentityV2) {
            return SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH
        }
        return SelfOwnedInstalledIdentityV2.refusalFor(expected, actual)
    }
}

/** The only native proof kind that can satisfy the approved P1 policy boundary. */
public enum class SelfOwnedIosNativeCodeSignatureProofV1 {
    /**
     * The running active full app read an existing item through an explicit App ID keychain
     * access group and received the exact OS-maintained `kSecAttrAccessGroup` and marker value.
     * iOS enforces the code-signed `application-identifier`/`keychain-access-groups` entitlement
     * before Keychain Services returns the item.
     */
    OS_ENFORCED_CODE_SIGNING_APP_ID_KEYCHAIN_ACCESS_GROUP,
}

/**
 * Native iOS code-signature-backed observation. The constructor is internal so ordinary
 * consumers cannot manufacture a caller Boolean or synthetic signing result. Trusted module
 * code supplies the native re-observation; every policy acceptance invokes it afresh. The
 * value is DATA: it proves neither key custody nor Master/device/employee authority.
 */
public class SelfOwnedIosNativeCodeSignatureEvidenceV1 internal constructor(
    public val target: SelfOwnedIosActiveAppIdentityV2,
    public val observedBundleIdentifier: String,
    public val observedAppIdGroup: String,
    private val reobserve: () -> SelfOwnedInstalledIdentityRefusalV2?,
) {
    public val proof: SelfOwnedIosNativeCodeSignatureProofV1
        get() = SelfOwnedIosNativeCodeSignatureProofV1.OS_ENFORCED_CODE_SIGNING_APP_ID_KEYCHAIN_ACCESS_GROUP

    init {
        require(SelfOwnedInstalledIdentityGrammarV2.bundleIdentifier(observedBundleIdentifier))
        require(
            SelfOwnedInstalledIdentityGrammarV2.appIdGroup(
                observedAppIdGroup, observedBundleIdentifier,
            ),
        )
        require(
            SelfOwnedInstalledIdentityGrammarV2.asciiEqualsIgnoreCase(
                observedBundleIdentifier, target.bundleIdentifier,
            ),
        )
        require(observedAppIdGroup == target.appIdGroup)
    }

    internal fun currentRefusal(): SelfOwnedInstalledIdentityRefusalV2? = try {
        reobserve()
    } catch (_: Exception) {
        SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE
    }

    override fun toString(): String = "SelfOwnedIosNativeCodeSignatureEvidenceV1([REDACTED])"
}
