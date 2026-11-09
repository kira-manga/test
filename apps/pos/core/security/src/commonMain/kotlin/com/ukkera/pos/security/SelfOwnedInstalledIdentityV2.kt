package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/**
 * Additive installed-identity profile E3-008. It is a future-facing public DATA contract:
 * no native Windows/macOS/iOS provider captures these facts in this card, and a decoded
 * target, preparation, signing message or signed document NEVER grants native context,
 * signer trust, employee/device/Master authority, SDK peer identity or readiness.
 *
 * The legacy Android V1 profile and bytes are untouched. This profile has no Android target
 * kind; Android continues to use [SelfOwnedInstallationProfileV1] and [SelfOwnedTargetDescriptorV1].
 */
public object SelfOwnedInstalledIdentityProfileV2 {
    public const val ID: String = "ukkera.installation/self-owned-local-p256/v2"
    public const val TARGET_DOMAIN: String = "native-target/v2"
    public const val PREPARATION_DOMAIN: String = "native-preparation/v2"
    public const val SIGNING_BODY_DOMAIN: String = "signed-body/v2"
    public const val SIGNED_DOCUMENT_DOMAIN: String = "signed-document/v2"
    public const val ROLE_TAG: String = "INSTALLED_NATIVE_IDENTITY_PREPARATION_V2"
    public const val FORMAT_VERSION: Int = 2
    public const val MAXIMUM_TARGET_BYTES: Int = 8_192
    public const val MAXIMUM_PREPARATION_BYTES: Int = 65_024
    public const val MAXIMUM_BODY_BYTES: Int = 65_024
    public const val MAXIMUM_DOCUMENT_BYTES: Int = 65_536
    public const val NONCE_BYTES: Int = 32
    public const val SIGNATURE_BYTES: Int = 64
    public const val MAXIMUM_LABEL_BYTES: Int = 640
    public const val MAXIMUM_PROFILE_OR_DOMAIN_BYTES: Int = 128
    public const val MAXIMUM_ROLE_TAG_BYTES: Int = 64
    public const val PREPARATION_PURPOSE: String =
        "BIND_INSTALLED_NATIVE_IDENTITY_ONLY_NO_KEY_OR_ROOM_AUTHORITY"

    internal const val PFN_MINIMUM: Int = 17
    internal const val PFN_MAXIMUM: Int = 64
    internal const val PACKAGE_NAME_MINIMUM: Int = 3
    internal const val PACKAGE_NAME_MAXIMUM: Int = 50
    internal const val PUBLISHER_ID_LENGTH: Int = 13
    internal const val AUMID_MINIMUM: Int = 20
    internal const val AUMID_MAXIMUM: Int = 129
    internal const val RELATIVE_ID_MINIMUM: Int = 1
    internal const val RELATIVE_ID_MAXIMUM: Int = 64
    internal const val SERVICE_SID_MINIMUM: Int = 18
    internal const val SERVICE_SID_MAXIMUM: Int = 63
    internal const val ACCOUNT_SID_MINIMUM: Int = 7
    internal const val ACCOUNT_SID_MAXIMUM: Int = 184
    internal const val IOS_APP_ID_GROUP_MINIMUM: Int = 12
    internal const val IOS_APP_ID_GROUP_MAXIMUM: Int = 266

    internal val reservedPackageStrings: Set<String> = setOf(
        ".", "..", "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )
    internal val reservedPackagePrefixes: List<String> = reservedPackageStrings
        .filter { it != "." && it != ".." }
        .map { "$it." } + "xn--"
}

/** Frozen stable refusal categories from the accepted conditional v3 contract. */
public enum class SelfOwnedInstalledIdentityRefusalV2 {
    REFUSE_PROFILE_UNSUPPORTED,
    REFUSE_DOMAIN,
    REFUSE_VERSION,
    REFUSE_TARGET_KIND,
    REFUSE_ROLE,
    REFUSE_ROLE_MISMATCH,
    REFUSE_GRAMMAR,
    REFUSE_ENCODING,
    REFUSE_TRAILING,
    REFUSE_TRUNCATED,
    REFUSE_OVERSIZE,
    REFUSE_BODY_EMPTY,
    REFUSE_NONNEGATIVE,
    REFUSE_DUPLICATE_COORDINATE,
    REFUSE_IDENTITY_MISMATCH,
    REFUSE_NATIVE_OBSERVATION_UNAVAILABLE,
    REFUSE_UNRESOLVED,
    REFUSE_KEYCHAIN_SCOPE,
    REFUSE_KEYCHAIN_AUTHORIZATION_UNRESOLVED,
    REFUSE_NON_EXCLUSIVE_CONTEXT,
    REFUSE_UNSUPPORTED_CONTEXT,
    REFUSE_PLATFORM_MISMATCH,
    REFUSE_SIGNATURE_SHAPE,
    REFUSE_SIGNER_TRUST,
    REFUSE_PROCESS_IDENTITY_UNSUPPORTED,
    REFUSE_POLICY_PENDING,
}

/** Bounded DATA result. Neither arm is an authority claim. */
public sealed class SelfOwnedInstalledIdentityResultV2<out T> {
    public class Data<out T> internal constructor(public val value: T) : SelfOwnedInstalledIdentityResultV2<T>()
    public class Refused internal constructor(public val code: SelfOwnedInstalledIdentityRefusalV2) :
        SelfOwnedInstalledIdentityResultV2<Nothing>()
}

public enum class SelfOwnedInstalledIdentityPlatformV2 { WINDOWS, MACOS, IOS }

public enum class SelfOwnedInstalledIdentityRoleV2 { UI_CLIENT, MASTER_SERVICE, ACTIVE_APP }

public enum class SelfOwnedInstalledIdentityTargetKindV2(
    public val wireName: String,
    public val platform: SelfOwnedInstalledIdentityPlatformV2,
    public val role: SelfOwnedInstalledIdentityRoleV2,
) {
    WINDOWS_PACKAGED_APP_V1(
        "WINDOWS_PACKAGED_APP_V1",
        SelfOwnedInstalledIdentityPlatformV2.WINDOWS,
        SelfOwnedInstalledIdentityRoleV2.UI_CLIENT,
    ),
    WINDOWS_SERVICE_V1(
        "WINDOWS_SERVICE_V1",
        SelfOwnedInstalledIdentityPlatformV2.WINDOWS,
        SelfOwnedInstalledIdentityRoleV2.MASTER_SERVICE,
    ),
    WINDOWS_PACKAGED_SERVICE_V1(
        "WINDOWS_PACKAGED_SERVICE_V1",
        SelfOwnedInstalledIdentityPlatformV2.WINDOWS,
        SelfOwnedInstalledIdentityRoleV2.MASTER_SERVICE,
    ),
    MACOS_SERVICE_V1(
        "MACOS_SERVICE_V1",
        SelfOwnedInstalledIdentityPlatformV2.MACOS,
        SelfOwnedInstalledIdentityRoleV2.MASTER_SERVICE,
    ),
    MACOS_UI_V1(
        "MACOS_UI_V1",
        SelfOwnedInstalledIdentityPlatformV2.MACOS,
        SelfOwnedInstalledIdentityRoleV2.UI_CLIENT,
    ),
    IOS_ACTIVE_APP_V1(
        "IOS_ACTIVE_APP_V1",
        SelfOwnedInstalledIdentityPlatformV2.IOS,
        SelfOwnedInstalledIdentityRoleV2.ACTIVE_APP,
    );

    public companion object {
        public fun fromWireName(value: String): SelfOwnedInstalledIdentityTargetKindV2? =
            entries.singleOrNull { it.wireName == value }
    }
}

/**
 * Explicit, closed platform/role factory discriminator. `IOS_EXTENSION_APP` is a deliberately
 * unavailable native context, not an accepting target kind. There is no `OTHER`, `UNKNOWN`,
 * Android V2 or accepting default.
 */
public enum class SelfOwnedInstalledIdentityFactoryV2(
    public val platform: SelfOwnedInstalledIdentityPlatformV2,
    public val role: SelfOwnedInstalledIdentityRoleV2,
    public val targetKind: SelfOwnedInstalledIdentityTargetKindV2?,
) {
    WINDOWS_UI(
        SelfOwnedInstalledIdentityPlatformV2.WINDOWS,
        SelfOwnedInstalledIdentityRoleV2.UI_CLIENT,
        SelfOwnedInstalledIdentityTargetKindV2.WINDOWS_PACKAGED_APP_V1,
    ),
    WINDOWS_SERVICE(
        SelfOwnedInstalledIdentityPlatformV2.WINDOWS,
        SelfOwnedInstalledIdentityRoleV2.MASTER_SERVICE,
        SelfOwnedInstalledIdentityTargetKindV2.WINDOWS_SERVICE_V1,
    ),
    WINDOWS_PACKAGED_SERVICE(
        SelfOwnedInstalledIdentityPlatformV2.WINDOWS,
        SelfOwnedInstalledIdentityRoleV2.MASTER_SERVICE,
        SelfOwnedInstalledIdentityTargetKindV2.WINDOWS_PACKAGED_SERVICE_V1,
    ),
    MACOS_UI(
        SelfOwnedInstalledIdentityPlatformV2.MACOS,
        SelfOwnedInstalledIdentityRoleV2.UI_CLIENT,
        SelfOwnedInstalledIdentityTargetKindV2.MACOS_UI_V1,
    ),
    MACOS_SERVICE(
        SelfOwnedInstalledIdentityPlatformV2.MACOS,
        SelfOwnedInstalledIdentityRoleV2.MASTER_SERVICE,
        SelfOwnedInstalledIdentityTargetKindV2.MACOS_SERVICE_V1,
    ),
    IOS_ACTIVE_APP(
        SelfOwnedInstalledIdentityPlatformV2.IOS,
        SelfOwnedInstalledIdentityRoleV2.ACTIVE_APP,
        SelfOwnedInstalledIdentityTargetKindV2.IOS_ACTIVE_APP_V1,
    ),
    IOS_EXTENSION_APP(
        SelfOwnedInstalledIdentityPlatformV2.IOS,
        SelfOwnedInstalledIdentityRoleV2.ACTIVE_APP,
        null,
    ),
}

/** Closed identity target. Every variant is public DATA and carries no authority operation. */
public sealed interface SelfOwnedInstalledIdentityTargetV2 {
    public val kind: SelfOwnedInstalledIdentityTargetKindV2
}

public data class SelfOwnedWindowsPackagedAppIdentityV2(
    public val packageFamilyName: String,
    public val applicationUserModelId: String,
) : SelfOwnedInstalledIdentityTargetV2 {
    init {
        require(SelfOwnedInstalledIdentityGrammarV2.packageFamilyName(packageFamilyName))
        require(SelfOwnedInstalledIdentityGrammarV2.applicationUserModelId(applicationUserModelId))
        require(
            SelfOwnedInstalledIdentityGrammarV2.asciiEqualsIgnoreCase(
                applicationUserModelId.substring(0, applicationUserModelId.indexOf('!')),
                packageFamilyName,
            ),
        )
    }
    override val kind: SelfOwnedInstalledIdentityTargetKindV2
        get() = SelfOwnedInstalledIdentityTargetKindV2.WINDOWS_PACKAGED_APP_V1
    override fun toString(): String = "SelfOwnedWindowsPackagedAppIdentityV2([REDACTED])"
}

public data class SelfOwnedWindowsServiceIdentityV2(
    public val serviceSid: String,
    public val serviceAccountSid: String,
) : SelfOwnedInstalledIdentityTargetV2 {
    init {
        require(SelfOwnedInstalledIdentityGrammarV2.serviceSid(serviceSid))
        require(SelfOwnedInstalledIdentityGrammarV2.accountSid(serviceAccountSid))
    }
    override val kind: SelfOwnedInstalledIdentityTargetKindV2
        get() = SelfOwnedInstalledIdentityTargetKindV2.WINDOWS_SERVICE_V1
    override fun toString(): String = "SelfOwnedWindowsServiceIdentityV2([REDACTED])"
}

public data class SelfOwnedWindowsPackagedServiceIdentityV2(
    public val packageFamilyName: String,
    public val applicationUserModelId: String,
    public val serviceSid: String,
    public val serviceAccountSid: String,
) : SelfOwnedInstalledIdentityTargetV2 {
    init {
        require(SelfOwnedInstalledIdentityGrammarV2.packageFamilyName(packageFamilyName))
        require(SelfOwnedInstalledIdentityGrammarV2.applicationUserModelId(applicationUserModelId))
        require(
            SelfOwnedInstalledIdentityGrammarV2.asciiEqualsIgnoreCase(
                applicationUserModelId.substring(0, applicationUserModelId.indexOf('!')),
                packageFamilyName,
            ),
        )
        require(SelfOwnedInstalledIdentityGrammarV2.serviceSid(serviceSid))
        require(SelfOwnedInstalledIdentityGrammarV2.accountSid(serviceAccountSid))
    }
    override val kind: SelfOwnedInstalledIdentityTargetKindV2
        get() = SelfOwnedInstalledIdentityTargetKindV2.WINDOWS_PACKAGED_SERVICE_V1
    override fun toString(): String = "SelfOwnedWindowsPackagedServiceIdentityV2([REDACTED])"
}

public data class SelfOwnedMacServiceIdentityV2(
    public val signingIdentifier: String,
    public val teamIdentifier: String,
    public val designatedRequirementSha256: String,
) : SelfOwnedInstalledIdentityTargetV2 {
    init {
        require(SelfOwnedInstalledIdentityGrammarV2.signingIdentifier(signingIdentifier))
        require(SelfOwnedInstalledIdentityGrammarV2.teamIdentifier(teamIdentifier))
        require(SelfOwnedInstalledIdentityGrammarV2.digest64(designatedRequirementSha256))
    }
    override val kind: SelfOwnedInstalledIdentityTargetKindV2
        get() = SelfOwnedInstalledIdentityTargetKindV2.MACOS_SERVICE_V1
    override fun toString(): String = "SelfOwnedMacServiceIdentityV2([REDACTED])"
}

public data class SelfOwnedMacUiIdentityV2(
    public val signingIdentifier: String,
    public val teamIdentifier: String,
    public val designatedRequirementSha256: String,
) : SelfOwnedInstalledIdentityTargetV2 {
    init {
        require(SelfOwnedInstalledIdentityGrammarV2.signingIdentifier(signingIdentifier))
        require(SelfOwnedInstalledIdentityGrammarV2.teamIdentifier(teamIdentifier))
        require(SelfOwnedInstalledIdentityGrammarV2.digest64(designatedRequirementSha256))
    }
    override val kind: SelfOwnedInstalledIdentityTargetKindV2
        get() = SelfOwnedInstalledIdentityTargetKindV2.MACOS_UI_V1
    override fun toString(): String = "SelfOwnedMacUiIdentityV2([REDACTED])"
}

public data class SelfOwnedIosActiveAppIdentityV2(
    public val bundleIdentifier: String,
    public val appIdGroup: String,
) : SelfOwnedInstalledIdentityTargetV2 {
    init {
        require(SelfOwnedInstalledIdentityGrammarV2.bundleIdentifier(bundleIdentifier))
        require(SelfOwnedInstalledIdentityGrammarV2.appIdGroup(appIdGroup, bundleIdentifier))
    }
    override val kind: SelfOwnedInstalledIdentityTargetKindV2
        get() = SelfOwnedInstalledIdentityTargetKindV2.IOS_ACTIVE_APP_V1
    override fun toString(): String = "SelfOwnedIosActiveAppIdentityV2([REDACTED])"
}

/**
 * V2 preparation DATA. The target is a complete parsed target record; the twelve coordinates
 * reuse the existing shared distinctness rule. No key, protection, namespace, boot, employee
 * or SDK field is present because those remain dependent cards.
 */
public class SelfOwnedInstalledIdentityPreparationV2(
    public val rootId: DomainId,
    public val rootDocumentDigest: String,
    public val issuerId: DomainId,
    public val delegationDigest: String,
    public val installerId: DomainId,
    public val installerRevision: Long,
    public val delegationRevision: Long,
    public val coordinates: SelfOwnedAllocationCoordinatesV1,
    public val serial: Long,
    nonce: ByteArray,
    public val target: SelfOwnedInstalledIdentityTargetV2,
    public val branchName: String,
) {
    private val nonceValue: SelfOwnedOctetsV1

    init {
        selfOwnedHexDigest(rootDocumentDigest)
        selfOwnedHexDigest(delegationDigest)
        require(installerRevision >= 0 && delegationRevision >= 0 && serial >= 0)
        require(nonce.size == SelfOwnedInstalledIdentityProfileV2.NONCE_BYTES)
        SelfOwnedInstalledIdentityGrammarV2.requireLabel(branchName)
        nonceValue = SelfOwnedOctetsV1(nonce)
    }

    public val formatVersion: Int get() = SelfOwnedInstalledIdentityProfileV2.FORMAT_VERSION
    public val preparationPurpose: String get() = SelfOwnedInstalledIdentityProfileV2.PREPARATION_PURPOSE
    public fun copyNonce(): ByteArray = nonceValue.copyBytes()
    public fun copyWireBytes(): ByteArray = SelfOwnedInstalledIdentityWireV2.encodePreparation(this)
    override fun toString(): String = "SelfOwnedInstalledIdentityPreparationV2([REDACTED])"
}

/** Canonical V2 signing-message DATA; it is not evidence that a signature was produced. */
public class SelfOwnedInstalledIdentitySignedBodyV2 internal constructor(
    public val preparation: SelfOwnedInstalledIdentityPreparationV2,
) {
    public val roleTag: String get() = SelfOwnedInstalledIdentityProfileV2.ROLE_TAG
    public fun copyWireBytes(): ByteArray = SelfOwnedInstalledIdentityWireV2.encodeSignedBody(this)
    override fun toString(): String = "SelfOwnedInstalledIdentitySignedBodyV2([REDACTED])"
}

/**
 * Parsed V2 signed-envelope DATA. Shape is checked; cryptographic verification and signer
 * trust are separate layers that this value never performs or implies.
 */
public class SelfOwnedInstalledIdentitySignedDocumentV2 internal constructor(
    public val signedBody: SelfOwnedInstalledIdentitySignedBodyV2,
    signature: ByteArray,
) {
    private val signatureValue: SelfOwnedOctetsV1

    init {
        require(signature.size == SelfOwnedInstalledIdentityProfileV2.SIGNATURE_BYTES)
        signatureValue = SelfOwnedOctetsV1(signature)
    }

    public val preparation: SelfOwnedInstalledIdentityPreparationV2 get() = signedBody.preparation
    public val roleTag: String get() = SelfOwnedInstalledIdentityProfileV2.ROLE_TAG
    public fun copySignature(): ByteArray = signatureValue.copyBytes()
    public fun copyWireBytes(): ByteArray = SelfOwnedInstalledIdentityWireV2.encodeSignedDocument(this)
    override fun toString(): String = "SelfOwnedInstalledIdentitySignedDocumentV2([REDACTED])"
}

/**
 * Shared factory/matcher/consumer surface. `accept*` returns bounded DATA only. The iOS
 * active-app factory is intentionally hard-refused `REFUSE_POLICY_PENDING` while P1 is
 * unanswered; there is no caller-supplied policy switch or accepting fallback.
 */
public object SelfOwnedInstalledIdentityV2 {
    /** Full parsed-target factory check. Wrong platform wins over role mismatch. */
    public fun accept(
        factory: SelfOwnedInstalledIdentityFactoryV2,
        target: SelfOwnedInstalledIdentityTargetV2,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledIdentityTargetV2> {
        if (factory == SelfOwnedInstalledIdentityFactoryV2.IOS_EXTENSION_APP) {
            return if (target.kind.platform != SelfOwnedInstalledIdentityPlatformV2.IOS) {
                SelfOwnedInstalledIdentityResultV2.Refused(
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH,
                )
            } else {
                SelfOwnedInstalledIdentityResultV2.Refused(
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE,
                )
            }
        }
        val discrimination = factoryKindRefusal(factory, target.kind)
        if (discrimination != null) {
            return SelfOwnedInstalledIdentityResultV2.Refused(discrimination)
        }
        if (factory == SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP) {
            // P1 is PENDING_LEAD_OWNER_CONFIRMATION. No schema flag, caller policy Boolean or
            // accepting fallback can enable identity-bearing iOS acceptance.
            return SelfOwnedInstalledIdentityResultV2.Refused(
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING,
            )
        }
        return SelfOwnedInstalledIdentityResultV2.Data(target)
    }

    /**
     * Discriminator-only factory check over the exact wire kind text. Used before a record is
     * parsed and for the frozen factory matrix; unknown/empty/`OTHER`/`ANDROID_V1` never map to
     * an accepting kind, and a v1 `IOS_APP_CONTEXT_V1` is explicitly non-exclusive context.
     */
    public fun acceptKind(
        factory: SelfOwnedInstalledIdentityFactoryV2,
        targetKind: String,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledIdentityTargetKindV2> {
        if (targetKind == "IOS_APP_CONTEXT_V1") {
            return SelfOwnedInstalledIdentityResultV2.Refused(
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_NON_EXCLUSIVE_CONTEXT,
            )
        }
        val kind = SelfOwnedInstalledIdentityTargetKindV2.fromWireName(targetKind)
            ?: return SelfOwnedInstalledIdentityResultV2.Refused(
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_TARGET_KIND,
            )
        if (factory == SelfOwnedInstalledIdentityFactoryV2.IOS_EXTENSION_APP) {
            return if (kind.platform != SelfOwnedInstalledIdentityPlatformV2.IOS) {
                SelfOwnedInstalledIdentityResultV2.Refused(
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH,
                )
            } else {
                SelfOwnedInstalledIdentityResultV2.Refused(
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE,
                )
            }
        }
        val discrimination = factoryKindRefusal(factory, kind)
        if (discrimination != null) {
            return SelfOwnedInstalledIdentityResultV2.Refused(discrimination)
        }
        // Discrimination only. The iOS policy gate is applied by the full `accept*` paths;
        // this matrix result is not identity acceptance or authority.
        return SelfOwnedInstalledIdentityResultV2.Data(kind)
    }

    /**
     * Same-platform kind discrimination from the complete accepted 6×6 matrix. A UI/service
     * substitution is a role mismatch; packaged/unpackaged service kinds are distinct target
     * kinds. Platform mismatch always wins.
     */
    private fun factoryKindRefusal(
        factory: SelfOwnedInstalledIdentityFactoryV2,
        kind: SelfOwnedInstalledIdentityTargetKindV2,
    ): SelfOwnedInstalledIdentityRefusalV2? {
        if (factory.platform != kind.platform) {
            return SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH
        }
        if (factory.targetKind == kind) return null
        return if (factory.role != kind.role) {
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE_MISMATCH
        } else {
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_TARGET_KIND
        }
    }

    /** Field-specific exact comparison; there is no blanket case fold. */
    public fun refusalFor(
        expected: SelfOwnedInstalledIdentityTargetV2,
        actual: SelfOwnedInstalledIdentityTargetV2,
    ): SelfOwnedInstalledIdentityRefusalV2? {
        if (expected.kind != actual.kind) return SelfOwnedInstalledIdentityRefusalV2.REFUSE_TARGET_KIND
        return when (expected.kind) {
            SelfOwnedInstalledIdentityTargetKindV2.WINDOWS_PACKAGED_APP_V1 -> {
                expected as SelfOwnedWindowsPackagedAppIdentityV2
                actual as SelfOwnedWindowsPackagedAppIdentityV2
                if (!SelfOwnedInstalledIdentityGrammarV2.asciiEqualsIgnoreCase(
                        expected.packageFamilyName, actual.packageFamilyName,
                    ) || expected.applicationUserModelId != actual.applicationUserModelId
                ) SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH else null
            }
            SelfOwnedInstalledIdentityTargetKindV2.WINDOWS_SERVICE_V1 -> {
                expected as SelfOwnedWindowsServiceIdentityV2
                actual as SelfOwnedWindowsServiceIdentityV2
                if (expected.serviceSid != actual.serviceSid ||
                    expected.serviceAccountSid != actual.serviceAccountSid
                ) SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH else null
            }
            SelfOwnedInstalledIdentityTargetKindV2.WINDOWS_PACKAGED_SERVICE_V1 -> {
                expected as SelfOwnedWindowsPackagedServiceIdentityV2
                actual as SelfOwnedWindowsPackagedServiceIdentityV2
                if (!SelfOwnedInstalledIdentityGrammarV2.asciiEqualsIgnoreCase(
                        expected.packageFamilyName, actual.packageFamilyName,
                    ) || expected.applicationUserModelId != actual.applicationUserModelId ||
                    expected.serviceSid != actual.serviceSid ||
                    expected.serviceAccountSid != actual.serviceAccountSid
                ) SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH else null
            }
            SelfOwnedInstalledIdentityTargetKindV2.MACOS_SERVICE_V1 -> {
                expected as SelfOwnedMacServiceIdentityV2
                actual as SelfOwnedMacServiceIdentityV2
                if (expected.signingIdentifier != actual.signingIdentifier ||
                    expected.teamIdentifier != actual.teamIdentifier ||
                    expected.designatedRequirementSha256 != actual.designatedRequirementSha256
                ) SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH else null
            }
            SelfOwnedInstalledIdentityTargetKindV2.MACOS_UI_V1 -> {
                expected as SelfOwnedMacUiIdentityV2
                actual as SelfOwnedMacUiIdentityV2
                if (expected.signingIdentifier != actual.signingIdentifier ||
                    expected.teamIdentifier != actual.teamIdentifier ||
                    expected.designatedRequirementSha256 != actual.designatedRequirementSha256
                ) SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH else null
            }
            SelfOwnedInstalledIdentityTargetKindV2.IOS_ACTIVE_APP_V1 -> {
                expected as SelfOwnedIosActiveAppIdentityV2
                actual as SelfOwnedIosActiveAppIdentityV2
                if (!SelfOwnedInstalledIdentityGrammarV2.asciiEqualsIgnoreCase(
                        expected.bundleIdentifier, actual.bundleIdentifier,
                    ) || expected.appIdGroup != actual.appIdGroup
                ) SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH else null
            }
        }
    }

    public fun matches(
        expected: SelfOwnedInstalledIdentityTargetV2,
        actual: SelfOwnedInstalledIdentityTargetV2,
    ): Boolean = refusalFor(expected, actual) == null

    /** Parse + factory + expected-target validation for a standalone V2 target. */
    public fun acceptTarget(
        bytes: ByteArray,
        expected: SelfOwnedInstalledIdentityTargetV2,
        factory: SelfOwnedInstalledIdentityFactoryV2,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledIdentityTargetV2> =
        when (val decoded = SelfOwnedInstalledIdentityWireV2.decodeTarget(bytes)) {
            is SelfOwnedInstalledIdentityResultV2.Refused -> decoded
            is SelfOwnedInstalledIdentityResultV2.Data -> {
                val refusal = acceptanceRefusal(factory, expected, decoded.value)
                if (refusal == null) SelfOwnedInstalledIdentityResultV2.Data(decoded.value)
                else SelfOwnedInstalledIdentityResultV2.Refused(refusal)
            }
        }

    /** Parse + factory + expected-target validation for a V2 signed body. */
    public fun acceptSignedBody(
        bytes: ByteArray,
        expected: SelfOwnedInstalledIdentityTargetV2,
        factory: SelfOwnedInstalledIdentityFactoryV2,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledIdentitySignedBodyV2> =
        when (val decoded = SelfOwnedInstalledIdentityWireV2.decodeSignedBody(bytes)) {
            is SelfOwnedInstalledIdentityResultV2.Refused -> decoded
            is SelfOwnedInstalledIdentityResultV2.Data -> {
                val refusal = acceptanceRefusal(factory, expected, decoded.value.preparation.target)
                if (refusal == null) SelfOwnedInstalledIdentityResultV2.Data(decoded.value)
                else SelfOwnedInstalledIdentityResultV2.Refused(refusal)
            }
        }

    /** Parse + factory + expected-target validation for a V2 signed document. */
    public fun acceptSignedDocument(
        bytes: ByteArray,
        expected: SelfOwnedInstalledIdentityTargetV2,
        factory: SelfOwnedInstalledIdentityFactoryV2,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledIdentitySignedDocumentV2> =
        when (val decoded = SelfOwnedInstalledIdentityWireV2.decodeSignedDocument(bytes)) {
            is SelfOwnedInstalledIdentityResultV2.Refused -> decoded
            is SelfOwnedInstalledIdentityResultV2.Data -> {
                val refusal = acceptanceRefusal(factory, expected, decoded.value.preparation.target)
                if (refusal == null) SelfOwnedInstalledIdentityResultV2.Data(decoded.value)
                else SelfOwnedInstalledIdentityResultV2.Refused(refusal)
            }
        }

    /**
     * Factory and expected-target precedence for an actual parsed target. Platform/kind/role
     * checks stay first. A differing bundle/group is an identity mismatch even while the iOS
     * active-app factory is policy-pending; only a same-bundle replacement reaches the frozen
     * `REFUSE_POLICY_PENDING` result. There is no expected-target-less P1 acceptance.
     */
    private fun acceptanceRefusal(
        factory: SelfOwnedInstalledIdentityFactoryV2,
        expected: SelfOwnedInstalledIdentityTargetV2,
        actual: SelfOwnedInstalledIdentityTargetV2,
    ): SelfOwnedInstalledIdentityRefusalV2? =
        when (val factoryResult = accept(factory, actual)) {
            is SelfOwnedInstalledIdentityResultV2.Refused -> {
                if (factory == SelfOwnedInstalledIdentityFactoryV2.IOS_ACTIVE_APP &&
                    factoryResult.code == SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING
                ) {
                    refusalFor(expected, actual) ?: factoryResult.code
                } else {
                    factoryResult.code
                }
            }
            is SelfOwnedInstalledIdentityResultV2.Data -> refusalFor(expected, actual)
        }
}

/** Shared per-field grammar; exact code mapping for over-bound labels belongs to the parser. */
internal object SelfOwnedInstalledIdentityGrammarV2 {
    private val hex64Pattern = Regex("[0-9a-f]{64}")
    private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val serviceSidPattern = Regex("S-1-5-80-([0-9]+-){4}[0-9]+")
    private val accountSidPattern = Regex("S-1-[0-9]+(-[0-9]+)+")
    private val publisherIdPattern = Regex("[a-hj-km-np-tv-z0-9]{13}", RegexOption.IGNORE_CASE)
    private val packageStringPattern = Regex("[A-Za-z0-9.-]+")
    private val bundlePattern = Regex("[A-Za-z0-9.-]+")

    fun requireLabel(value: String) {
        val refusal = label(value)
        require(refusal == null) { "invalid branch label" }
    }

    /** `null` accepted; otherwise the frozen over-bound/encoding category. */
    fun label(value: String): SelfOwnedInstalledIdentityRefusalV2? {
        try { selfOwnedUtf16(value) } catch (_: IllegalArgumentException) {
            return SelfOwnedInstalledIdentityRefusalV2.REFUSE_ENCODING
        }
        val encoded = try { value.encodeToByteArray() } catch (_: Exception) {
            return SelfOwnedInstalledIdentityRefusalV2.REFUSE_ENCODING
        }
        return try {
            when {
                encoded.size > SelfOwnedInstalledIdentityProfileV2.MAXIMUM_LABEL_BYTES ->
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE
                encoded.isEmpty() -> SelfOwnedInstalledIdentityRefusalV2.REFUSE_ENCODING
                '\u0000' in value -> SelfOwnedInstalledIdentityRefusalV2.REFUSE_ENCODING
                selfOwnedHasUnicodeCcOrCf(value) -> SelfOwnedInstalledIdentityRefusalV2.REFUSE_ENCODING
                else -> null
            }
        } finally { encoded.fill(0) }
    }

    fun digest64(value: String): Boolean = hex64Pattern.matches(value)

    fun canonicalUuid(value: String): Boolean = uuidPattern.matches(value)

    fun packageFamilyName(value: String): Boolean {
        if (value.length !in SelfOwnedInstalledIdentityProfileV2.PFN_MINIMUM..
            SelfOwnedInstalledIdentityProfileV2.PFN_MAXIMUM
        ) return false
        val separator = value.indexOf('_')
        if (separator < 0 || value.indexOf('_', separator + 1) >= 0) return false
        val name = value.substring(0, separator)
        val publisher = value.substring(separator + 1)
        return packageStringPart(
            name,
            SelfOwnedInstalledIdentityProfileV2.PACKAGE_NAME_MINIMUM,
            SelfOwnedInstalledIdentityProfileV2.PACKAGE_NAME_MAXIMUM,
        ) && publisherIdPattern.matches(publisher)
    }

    fun applicationUserModelId(value: String): Boolean {
        if (value.length !in SelfOwnedInstalledIdentityProfileV2.AUMID_MINIMUM..
            SelfOwnedInstalledIdentityProfileV2.AUMID_MAXIMUM
        ) return false
        val separator = value.indexOf('!')
        if (separator < 0 || value.indexOf('!', separator + 1) >= 0) return false
        return packageFamilyName(value.substring(0, separator)) &&
            packageStringPart(
                value.substring(separator + 1),
                SelfOwnedInstalledIdentityProfileV2.RELATIVE_ID_MINIMUM,
                SelfOwnedInstalledIdentityProfileV2.RELATIVE_ID_MAXIMUM,
            )
    }

    fun serviceSid(value: String): Boolean {
        if (value.length !in SelfOwnedInstalledIdentityProfileV2.SERVICE_SID_MINIMUM..
            SelfOwnedInstalledIdentityProfileV2.SERVICE_SID_MAXIMUM
        ) return false
        if (!serviceSidPattern.matches(value)) return false
        return value.substring("S-1-5-80-".length).split('-').all {
            canonicalDecimal(it) && it.toUIntOrNull() != null
        }
    }

    fun accountSid(value: String): Boolean {
        if (value.length !in SelfOwnedInstalledIdentityProfileV2.ACCOUNT_SID_MINIMUM..
            SelfOwnedInstalledIdentityProfileV2.ACCOUNT_SID_MAXIMUM
        ) return false
        if (!accountSidPattern.matches(value)) return false
        val parts = value.substring("S-1-".length).split('-')
        if (parts.size !in 2..16) return false
        val authority = parts.first()
        if (!canonicalDecimal(authority) || authority.toULongOrNull() == null ||
            authority.toULong() > 281_474_976_710_655UL
        ) return false
        return parts.drop(1).all { canonicalDecimal(it) && it.toUIntOrNull() != null }
    }

    fun signingIdentifier(value: String): Boolean {
        if (value.isEmpty() || '"' in value || '\\' in value || '\u0000' in value) return false
        try { selfOwnedUtf16(value) } catch (_: IllegalArgumentException) { return false }
        val encoded = try { value.encodeToByteArray() } catch (_: Exception) { return false }
        return try {
            encoded.size in 1..255 && !selfOwnedHasUnicodeCcOrCf(value)
        } finally { encoded.fill(0) }
    }

    fun teamIdentifier(value: String): Boolean =
        value.length == 10 && value.all { it in 'A'..'Z' || it in '0'..'9' }

    fun bundleIdentifier(value: String): Boolean {
        try { selfOwnedUtf16(value) } catch (_: IllegalArgumentException) { return false }
        val encoded = try { value.encodeToByteArray() } catch (_: Exception) { return false }
        return try {
            encoded.size in 1..255 && bundlePattern.matches(value) &&
                !value.startsWith('.') && !value.endsWith('.') && ".." !in value
        } finally { encoded.fill(0) }
    }

    fun appIdGroup(group: String, bundleIdentifier: String): Boolean {
        try { selfOwnedUtf16(group) } catch (_: IllegalArgumentException) { return false }
        val encoded = try { group.encodeToByteArray() } catch (_: Exception) { return false }
        return try {
            if (encoded.size !in SelfOwnedInstalledIdentityProfileV2.IOS_APP_ID_GROUP_MINIMUM..
                SelfOwnedInstalledIdentityProfileV2.IOS_APP_ID_GROUP_MAXIMUM
            ) return false
            if (group.startsWith("group.") || '*' in group) return false
            val dot = group.indexOf('.')
            if (dot != 10) return false
            val prefix = group.substring(0, dot)
            if (prefix.length != 10 || !prefix.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }) {
                return false
            }
            val suffix = group.substring(dot + 1)
            bundleIdentifier(suffix) && asciiEqualsIgnoreCase(suffix, bundleIdentifier)
        } finally { encoded.fill(0) }
    }

    fun asciiEqualsIgnoreCase(left: String, right: String): Boolean {
        if (left.length != right.length) return false
        for (index in left.indices) {
            val a = left[index]
            val b = right[index]
            if (a == b) continue
            if (asciiLower(a) != asciiLower(b)) return false
        }
        return true
    }

    fun asciiLower(value: String): String = buildString(value.length) {
        value.forEach { append(asciiLower(it)) }
    }

    private fun asciiLower(value: Char): Char = if (value in 'A'..'Z') value + 32 else value

    private fun packageStringPart(value: String, minimum: Int, maximum: Int): Boolean {
        if (value.length !in minimum..maximum || !packageStringPattern.matches(value)) return false
        val lower = asciiLower(value)
        if (lower in SelfOwnedInstalledIdentityProfileV2.reservedPackageStrings) return false
        if (lower.startsWith("xn--") || ".xn--" in lower) return false
        if (SelfOwnedInstalledIdentityProfileV2.reservedPackagePrefixes.any { lower.startsWith(it) }) return false
        return !lower.endsWith(".")
    }

    private fun canonicalDecimal(value: String): Boolean =
        value.isNotEmpty() && value.all { it in '0'..'9' } && (value == "0" || value[0] != '0')
}
