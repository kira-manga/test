package com.ukkera.pos.security

/**
 * Explicit installed-target DATA for an original setup/context boundary. It never grants native
 * custody, key possession, installability or a current writer. A platform is part of the value;
 * there is no accepting `OTHER`/`UNKNOWN`/Android-V2 discriminator and no fabricated Android UID,
 * APK signer or AndroidId commitment for Windows/macOS/iOS.
 *
 * Android keeps the exact legacy four-field [SelfOwnedTargetDescriptorV1]. Windows/macOS retain
 * their existing V2 target record unchanged. iOS uses the additive owner-approved P1 policy
 * boundary; the historical factory/decode paths still refuse it with `REFUSE_POLICY_PENDING`.
 */
public enum class SelfOwnedInstalledTargetPlatformV1 { ANDROID, WINDOWS, MACOS, IOS }

public sealed interface SelfOwnedInstalledTargetBindingV1 {
    public val platform: SelfOwnedInstalledTargetPlatformV1

    /** Frozen discriminator text; the Android legacy record has no V2 kind. */
    public val targetKind: String

    /** Android-only legacy projection; null for a native Windows/macOS/iOS binding. */
    public val androidTarget: SelfOwnedTargetDescriptorV1? get() = null

    /** Windows/macOS/iOS V2 target DATA; null for the legacy Android binding. */
    public val nativeTarget: SelfOwnedInstalledIdentityTargetV2? get() = null

    /** Exact platform-local target match. Cross-platform values never match by default. */
    public fun matches(other: SelfOwnedInstalledTargetBindingV1): Boolean

    /** Additive framed bytes; the contained target record remains the canonical V1/V2 record. */
    public fun copyWireBytes(): ByteArray = SelfOwnedInstalledTargetBindingWireV1.encode(this)

    public companion object {
        public const val ANDROID_LEGACY_TARGET_KIND: String = "ANDROID_LEGACY_V1"

        /** Retain the exact already-inspected Android V1 target. */
        public fun android(target: SelfOwnedTargetDescriptorV1): SelfOwnedInstalledTargetBindingV1 =
            SelfOwnedAndroidInstalledTargetBindingV1(target)

        /**
         * Apply the existing closed V2 factory/kind/role matrix first, then wrap its Windows or
         * macOS result as DATA. This historical surface still refuses the iOS active-app factory
         * with `REFUSE_POLICY_PENDING` and the extension factories with their existing
         * native-unavailable/platform results. Use [acceptApprovedIos] for the distinct
         * owner-approved P1 boundary.
         */
        public fun accept(
            factory: SelfOwnedInstalledIdentityFactoryV2,
            target: SelfOwnedInstalledIdentityTargetV2,
        ): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledTargetBindingV1> =
            when (val result = SelfOwnedInstalledIdentityV2.accept(factory, target)) {
                is SelfOwnedInstalledIdentityResultV2.Data -> native(result.value)
                is SelfOwnedInstalledIdentityResultV2.Refused ->
                    SelfOwnedInstalledIdentityResultV2.Refused(result.code)
            }

        /** Parse + expected-target/factory checks over the existing V2 target record. */
        public fun acceptTarget(
            bytes: ByteArray,
            expected: SelfOwnedInstalledIdentityTargetV2,
            factory: SelfOwnedInstalledIdentityFactoryV2,
        ): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledTargetBindingV1> =
            when (val result = SelfOwnedInstalledIdentityV2.acceptTarget(bytes, expected, factory)) {
                is SelfOwnedInstalledIdentityResultV2.Data -> native(result.value)
                is SelfOwnedInstalledIdentityResultV2.Refused ->
                    SelfOwnedInstalledIdentityResultV2.Refused(result.code)
            }

        /**
         * Owner-approved P1 iOS active-app binding. [evidence] is the native
         * code-signature-backed observation; it is not a caller Boolean. The returned binding is
         * DATA and does not prove key custody or Master authority.
         */
        public fun acceptApprovedIos(
            target: SelfOwnedIosActiveAppIdentityV2,
            evidence: SelfOwnedIosNativeCodeSignatureEvidenceV1,
        ): SelfOwnedInstalledIdentityResultV2<SelfOwnedIosInstalledTargetBindingV1> =
            when (val accepted = SelfOwnedInstalledIdentityIosPolicyV1.acceptTarget(target, evidence)) {
                is SelfOwnedInstalledIdentityResultV2.Data ->
                    SelfOwnedInstalledIdentityResultV2.Data(
                        SelfOwnedIosInstalledTargetBindingV1(accepted.value),
                    )
                is SelfOwnedInstalledIdentityResultV2.Refused ->
                    SelfOwnedInstalledIdentityResultV2.Refused(accepted.code)
            }

        /** Parse the canonical V2 target, then apply the approved P1 native evidence boundary. */
        public fun acceptApprovedIosTarget(
            bytes: ByteArray,
            expected: SelfOwnedIosActiveAppIdentityV2,
            evidence: SelfOwnedIosNativeCodeSignatureEvidenceV1,
        ): SelfOwnedInstalledIdentityResultV2<SelfOwnedIosInstalledTargetBindingV1> =
            when (val accepted =
                SelfOwnedInstalledIdentityIosPolicyV1.acceptTarget(bytes, expected, evidence)
            ) {
                is SelfOwnedInstalledIdentityResultV2.Data ->
                    SelfOwnedInstalledIdentityResultV2.Data(
                        SelfOwnedIosInstalledTargetBindingV1(accepted.value),
                    )
                is SelfOwnedInstalledIdentityResultV2.Refused ->
                    SelfOwnedInstalledIdentityResultV2.Refused(accepted.code)
            }

        /**
         * Bounded pure DATA decode. The historical iOS policy boundary is preserved: an iOS
         * binding is refused `REFUSE_POLICY_PENDING`; use [decodeApprovedIos] for the approved
         * P1 path. Neither decode path proves custody, signer trust or readiness.
         */
        public fun decode(bytes: ByteArray): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledTargetBindingV1> =
            SelfOwnedInstalledTargetBindingWireV1.decode(bytes)

        /** Approved P1 decode requires the exact target and a current native observation. */
        public fun decodeApprovedIos(
            bytes: ByteArray,
            expected: SelfOwnedIosActiveAppIdentityV2,
            evidence: SelfOwnedIosNativeCodeSignatureEvidenceV1,
        ): SelfOwnedInstalledIdentityResultV2<SelfOwnedIosInstalledTargetBindingV1> =
            SelfOwnedInstalledTargetBindingWireV1.decodeApprovedIos(bytes, expected, evidence)

        private fun native(
            target: SelfOwnedInstalledIdentityTargetV2,
        ): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledTargetBindingV1> = when (target.kind.platform) {
            SelfOwnedInstalledIdentityPlatformV2.WINDOWS,
            SelfOwnedInstalledIdentityPlatformV2.MACOS ->
                SelfOwnedInstalledIdentityResultV2.Data(SelfOwnedNativeInstalledTargetBindingV1(target))
            SelfOwnedInstalledIdentityPlatformV2.IOS ->
                // The historical factory path stays P1-pending; use acceptApprovedIos instead.
                SelfOwnedInstalledIdentityResultV2.Refused(
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING,
                )
        }
    }
}

/** Exact legacy Android installed-app facts. This value is DATA, not Android UID authority. */
public class SelfOwnedAndroidInstalledTargetBindingV1 internal constructor(
    public val target: SelfOwnedTargetDescriptorV1,
) : SelfOwnedInstalledTargetBindingV1 {
    override val platform: SelfOwnedInstalledTargetPlatformV1 = SelfOwnedInstalledTargetPlatformV1.ANDROID
    override val targetKind: String =
        SelfOwnedInstalledTargetBindingV1.ANDROID_LEGACY_TARGET_KIND
    override val androidTarget: SelfOwnedTargetDescriptorV1 get() = target

    override fun matches(other: SelfOwnedInstalledTargetBindingV1): Boolean =
        other is SelfOwnedAndroidInstalledTargetBindingV1 && target.same(other.target)

    override fun toString(): String = "SelfOwnedAndroidInstalledTargetBindingV1([REDACTED])"
}

/**
 * Windows/macOS installed identity retained from the existing V2 target DATA. Platform/kind/role
 * acceptance, process identity and signer trust remain separate native checks.
 */
public class SelfOwnedNativeInstalledTargetBindingV1 internal constructor(
    public val target: SelfOwnedInstalledIdentityTargetV2,
) : SelfOwnedInstalledTargetBindingV1 {
    init {
        require(target.kind.platform != SelfOwnedInstalledIdentityPlatformV2.IOS) {
            "iOS is not an available installed-target binding"
        }
    }

    override val platform: SelfOwnedInstalledTargetPlatformV1 = when (target.kind.platform) {
        SelfOwnedInstalledIdentityPlatformV2.WINDOWS -> SelfOwnedInstalledTargetPlatformV1.WINDOWS
        SelfOwnedInstalledIdentityPlatformV2.MACOS -> SelfOwnedInstalledTargetPlatformV1.MACOS
        SelfOwnedInstalledIdentityPlatformV2.IOS -> error("iOS binding is unavailable")
    }
    override val targetKind: String get() = target.kind.wireName
    override val nativeTarget: SelfOwnedInstalledIdentityTargetV2 get() = target

    override fun matches(other: SelfOwnedInstalledTargetBindingV1): Boolean =
        other is SelfOwnedNativeInstalledTargetBindingV1 &&
            SelfOwnedInstalledIdentityV2.matches(target, other.target)

    override fun toString(): String = "SelfOwnedNativeInstalledTargetBindingV1([REDACTED])"
}

/**
 * Owner-approved P1 iOS active-app binding DATA. It is created only through the native-evidence
 * policy boundary; the class itself proves neither key custody nor Master authority.
 */
public class SelfOwnedIosInstalledTargetBindingV1 internal constructor(
    public val target: SelfOwnedIosActiveAppIdentityV2,
) : SelfOwnedInstalledTargetBindingV1 {
    override val platform: SelfOwnedInstalledTargetPlatformV1 =
        SelfOwnedInstalledTargetPlatformV1.IOS
    override val targetKind: String get() = target.kind.wireName
    override val nativeTarget: SelfOwnedInstalledIdentityTargetV2 get() = target

    override fun matches(other: SelfOwnedInstalledTargetBindingV1): Boolean =
        other is SelfOwnedIosInstalledTargetBindingV1 &&
            SelfOwnedInstalledIdentityV2.matches(target, other.target)

    override fun toString(): String = "SelfOwnedIosInstalledTargetBindingV1([REDACTED])"
}

/**
 * Additive binding framing. Its embedded payload is not a new target codec: Android payload bytes
 * come from the unchanged legacy four-field encoder, and Windows/macOS/iOS payload bytes come from
 * the existing canonical V2 target encoder. Decoding this envelope grants nothing.
 */
public object SelfOwnedInstalledTargetBindingWireV1 {
    public const val ID: String = "ukkera.installation/installed-target-binding/v1"
    public const val DOMAIN: String = "native-installed-target-binding/v1"
    public const val FORMAT_VERSION: Int = 1
    internal const val MAXIMUM_LEGACY_TARGET_BYTES: Int = 65_536
    public const val MAXIMUM_BINDING_BYTES: Int = MAXIMUM_LEGACY_TARGET_BYTES + 4_096

    public fun encode(value: SelfOwnedInstalledTargetBindingV1): ByteArray {
        val targetBytes = when (value) {
            is SelfOwnedAndroidInstalledTargetBindingV1 ->
                SelfOwnedInstallationWireV1.encodeTarget(value.target)
            is SelfOwnedNativeInstalledTargetBindingV1 ->
                SelfOwnedInstalledIdentityWireV2.encodeTarget(value.target)
            is SelfOwnedIosInstalledTargetBindingV1 ->
                SelfOwnedInstalledIdentityWireV2.encodeTarget(value.target)
        }
        return try {
            SelfOwnedInstalledIdentityWireV2.frame(ID, DOMAIN, MAXIMUM_BINDING_BYTES) {
                int(FORMAT_VERSION)
                string(value.targetKind)
                field(targetBytes)
            }
        } finally { targetBytes.fill(0) }
    }

    /** Historical P1-pending decode; iOS is refused `REFUSE_POLICY_PENDING`. */
    public fun decode(
        bytes: ByteArray,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledTargetBindingV1> =
        selfOwnedIdentityGuarded { decodeOrThrow(bytes, approvedIos = false) }

    /** Decode only with an exact expected target and a fresh native evidence recheck. */
    public fun decodeApprovedIos(
        bytes: ByteArray,
        expected: SelfOwnedIosActiveAppIdentityV2,
        evidence: SelfOwnedIosNativeCodeSignatureEvidenceV1,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedIosInstalledTargetBindingV1> =
        selfOwnedIdentityGuarded {
            val binding = decodeOrThrow(bytes, approvedIos = true) as? SelfOwnedIosInstalledTargetBindingV1
                ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH)
            SelfOwnedInstalledIdentityV2.refusalFor(expected, binding.target)?.let { refuse(it) }
            when (val accepted = SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(expected, evidence)) {
                is SelfOwnedInstalledIdentityResultV2.Data -> accepted.value
                is SelfOwnedInstalledIdentityResultV2.Refused -> refuse(accepted.code)
            }
        }

    private fun decodeOrThrow(
        bytes: ByteArray,
        approvedIos: Boolean,
    ): SelfOwnedInstalledTargetBindingV1 {
        if (bytes.size > MAXIMUM_BINDING_BYTES) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
        val reader = SelfOwnedIdentityReader(bytes)
        if (reader.string() != ID) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED)
        if (reader.string() != DOMAIN) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN)
        if (reader.int() != FORMAT_VERSION) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_VERSION)
        val kind = reader.string()
        val targetBytes = reader.field(MAXIMUM_LEGACY_TARGET_BYTES)
        val binding = try {
            if (kind == SelfOwnedInstalledTargetBindingV1.ANDROID_LEGACY_TARGET_KIND) {
                when (val decoded = SelfOwnedLegacyInstallationCompatibilityV1.decodeTarget(targetBytes)) {
                    is SelfOwnedInstalledIdentityResultV2.Data ->
                        SelfOwnedInstalledTargetBindingV1.android(decoded.value)
                    is SelfOwnedInstalledIdentityResultV2.Refused -> refuse(decoded.code)
                }
            } else {
                val targetKind = SelfOwnedInstalledIdentityTargetKindV2.fromWireName(kind)
                    ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_TARGET_KIND)
                val ios = targetKind.platform == SelfOwnedInstalledIdentityPlatformV2.IOS
                if (ios && !approvedIos) {
                    refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING)
                }
                when (val decoded = SelfOwnedInstalledIdentityWireV2.decodeTarget(targetBytes)) {
                    is SelfOwnedInstalledIdentityResultV2.Data -> {
                        val target = decoded.value
                        if (target.kind != targetKind) {
                            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_TARGET_KIND)
                        }
                        if (ios) {
                            SelfOwnedIosInstalledTargetBindingV1(
                                target as? SelfOwnedIosActiveAppIdentityV2
                                    ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH),
                            )
                        } else {
                            SelfOwnedNativeInstalledTargetBindingV1(target)
                        }
                    }
                    is SelfOwnedInstalledIdentityResultV2.Refused -> refuse(decoded.code)
                }
            }
        } finally { targetBytes.fill(0) }
        reader.finish()
        return binding
    }
}
