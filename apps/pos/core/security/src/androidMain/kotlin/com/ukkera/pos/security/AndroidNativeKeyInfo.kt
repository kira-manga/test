package com.ukkera.pos.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties

/** Guarded public AndroidKeyStore observations, never exported key material or attestation. */
public object AndroidNativeKeyInfo {
    @Suppress("DEPRECATION")
    public fun observe(info: KeyInfo): AndroidNativeKeySecurity {
        check(Build.VERSION.SDK_INT >= AndroidNativeKeyPolicy.MINIMUM_ANDROID_API)
        return if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> AndroidNativeKeySecurity.SOFTWARE
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> AndroidNativeKeySecurity.TRUSTED_ENVIRONMENT
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> AndroidNativeKeySecurity.STRONGBOX
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> AndroidNativeKeySecurity.HARDWARE_KIND_UNSPECIFIED
                else -> throw UnsupportedOperationException("AndroidKeyStore security evidence unavailable")
            }
        } else if (info.isInsideSecureHardware) {
            AndroidNativeKeySecurity.HARDWARE_KIND_UNSPECIFIED
        } else {
            AndroidNativeKeySecurity.SOFTWARE
        }
    }

    /**
     * Service keys have no authentication, presence, confirmation, expiry or usage limit.
     * Presence/confirmation were not configurable before API 28; usage limits were not
     * configurable before API 31. Older systems cannot supply those newer KeyInfo getters.
     */
    public fun hasServiceRestrictions(info: KeyInfo): Boolean =
        info.isUserAuthenticationRequired || info.isUserAuthenticationValidWhileOnBody ||
            info.keyValidityStart != null || info.keyValidityForOriginationEnd != null ||
            info.keyValidityForConsumptionEnd != null ||
            (Build.VERSION.SDK_INT >= 28 &&
                (info.isTrustedUserPresenceRequired || info.isUserConfirmationRequired)) ||
            (Build.VERSION.SDK_INT >= 31 && info.remainingUsageCount != KeyProperties.UNRESTRICTED_USAGE_COUNT)

    /** Configure the selected service profile once; never retry a hardware request in software. */
    public fun configureService(
        builder: KeyGenParameterSpec.Builder,
        protection: SelfOwnedKeyProtectionV1,
    ): KeyGenParameterSpec.Builder {
        if (!AndroidNativeKeyPolicy.supports(Build.VERSION.SDK_INT, protection)) {
            throw UnsupportedOperationException(
                if (protection == SelfOwnedKeyProtectionV1.STRONGBOX)
                    "Exact StrongBox key evidence requires Android API 31 or later"
                else "AndroidKeyStore service keys require Android API 24 or later",
            )
        }
        builder.setUserAuthenticationRequired(false).setUserAuthenticationValidWhileOnBody(false)
            .setInvalidatedByBiometricEnrollment(false)
        if (Build.VERSION.SDK_INT >= 28) {
            builder.setUserPresenceRequired(false).setUserConfirmationRequired(false).setUnlockedDeviceRequired(false)
            if (protection == SelfOwnedKeyProtectionV1.STRONGBOX) builder.setIsStrongBoxBacked(true)
        }
        if (Build.VERSION.SDK_INT >= 31) builder.setMaxUsageCount(KeyProperties.UNRESTRICTED_USAGE_COUNT)
        return builder
    }
}

/** Internal native-only codec mapping. Existing ordinal values 0, 1 and 2 remain unchanged. */
internal fun AndroidNativeKeySecurity.selfOwnedSecurity(): SelfOwnedNativeSecurityV1 = when (this) {
    AndroidNativeKeySecurity.SOFTWARE -> SelfOwnedNativeSecurityV1.SOFTWARE
    AndroidNativeKeySecurity.TRUSTED_ENVIRONMENT -> SelfOwnedNativeSecurityV1.TRUSTED_ENVIRONMENT
    AndroidNativeKeySecurity.STRONGBOX -> SelfOwnedNativeSecurityV1.STRONGBOX
    AndroidNativeKeySecurity.HARDWARE_KIND_UNSPECIFIED -> SelfOwnedNativeSecurityV1.HARDWARE_KIND_UNSPECIFIED
}

internal fun SelfOwnedNativeSecurityV1.androidSecurity(): AndroidNativeKeySecurity = when (this) {
    SelfOwnedNativeSecurityV1.SOFTWARE -> AndroidNativeKeySecurity.SOFTWARE
    SelfOwnedNativeSecurityV1.TRUSTED_ENVIRONMENT -> AndroidNativeKeySecurity.TRUSTED_ENVIRONMENT
    SelfOwnedNativeSecurityV1.STRONGBOX -> AndroidNativeKeySecurity.STRONGBOX
    SelfOwnedNativeSecurityV1.HARDWARE_KIND_UNSPECIFIED -> AndroidNativeKeySecurity.HARDWARE_KIND_UNSPECIFIED
}

internal fun SelfOwnedNativeSecurityV1.originalWitness(retained: SelfOwnedNativeSecurityV1?): SelfOwnedNativeSecurityV1 =
    if (retained == null) this else AndroidNativeKeyPolicy.originalWitness(androidSecurity(), retained.androidSecurity()).selfOwnedSecurity()

internal fun requireNativeSelfOwnedProtection(protection: SelfOwnedKeyProtectionV1) {
    if (!AndroidNativeKeyPolicy.supports(Build.VERSION.SDK_INT, protection)) {
        selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
    }
}
