package com.ukkera.pos.security

/** Public AndroidKeyStore evidence; a hardware boolean never identifies a hardware kind. */
public enum class AndroidNativeKeySecurity(public val securityLevel: Int) {
    SOFTWARE(0),
    TRUSTED_ENVIRONMENT(1),
    STRONGBOX(2),
    /** The public API establishes secure hardware, but cannot identify its exact kind. */
    HARDWARE_KIND_UNSPECIFIED(-1),
}

/** Shared selection and original-evidence policy, independent of native key acquisition. */
public object AndroidNativeKeyPolicy {
    public const val MINIMUM_ANDROID_API: Int = 24
    public const val MINIMUM_EXACT_STRONGBOX_API: Int = 31

    /** StrongBox requests need exact public evidence on creation and every later open. */
    public fun supports(apiLevel: Int, protection: SelfOwnedKeyProtectionV1): Boolean =
        apiLevel >= MINIMUM_ANDROID_API &&
            (protection != SelfOwnedKeyProtectionV1.STRONGBOX || apiLevel >= MINIMUM_EXACT_STRONGBOX_API)

    public fun permits(protection: SelfOwnedKeyProtectionV1, observed: AndroidNativeKeySecurity): Boolean =
        when (protection) {
            SelfOwnedKeyProtectionV1.OS_ISOLATED -> true
            SelfOwnedKeyProtectionV1.SECURE_HARDWARE -> observed != AndroidNativeKeySecurity.SOFTWARE
            SelfOwnedKeyProtectionV1.STRONGBOX -> observed == AndroidNativeKeySecurity.STRONGBOX
        }

    /**
     * Preserve the original generic hardware witness after an OS upgrade exposes its kind.
     * The current requested policy must independently permit [observed]. The caller must also
     * verify the original alias, provider, key-possession proof and complete retained lineage.
     * This projection grants no key continuity and never rewrites the original signed bytes.
     * Software and exact-kind witnesses always require their unchanged original observation.
     */
    public fun originalWitness(
        observed: AndroidNativeKeySecurity,
        retained: AndroidNativeKeySecurity,
    ): AndroidNativeKeySecurity =
        if (retained == AndroidNativeKeySecurity.HARDWARE_KIND_UNSPECIFIED &&
            observed != AndroidNativeKeySecurity.SOFTWARE) retained else observed
}
