package com.ukkera.pos.security

/** Read-only projection of the originally authenticated native storage-key security. */
public enum class SelfOwnedStorageKeySecurity { SOFTWARE, TRUSTED_ENVIRONMENT, STRONGBOX, HARDWARE_KIND_UNSPECIFIED }

/** Immutable native-material description for display, never a complete installation/readiness proof. */
public class SelfOwnedNativeMaterialView internal constructor(
    public val coordinates: SelfOwnedAllocationCoordinatesV1,
    /** Platform-explicit installed target; Windows/macOS never require Android-only fields. */
    public val installedTarget: SelfOwnedInstalledTargetBindingV1,
    public val branchName: String,
    public val protection: SelfOwnedKeyProtectionV1,
    public val targetSigningFingerprint: String,
    public val originalNativeBindingDigest: String,
    /** Recorded in the verified original binding, never inferred from the requested protection. */
    public val storageKeySecurity: SelfOwnedStorageKeySecurity,
    /** Durable native permission AND this lifetime's denial ceiling; no full-ceremony approval. */
    public val newCeremoniesAllowed: Boolean,
) {
    /** Legacy Android V1 constructor bridge used by the existing Android original owner. */
    internal constructor(
        coordinates: SelfOwnedAllocationCoordinatesV1,
        target: SelfOwnedTargetDescriptorV1,
        branchName: String,
        protection: SelfOwnedKeyProtectionV1,
        targetSigningFingerprint: String,
        originalNativeBindingDigest: String,
        storageKeySecurity: SelfOwnedStorageKeySecurity,
        newCeremoniesAllowed: Boolean,
    ) : this(coordinates, SelfOwnedInstalledTargetBindingV1.android(target), branchName, protection,
        targetSigningFingerprint, originalNativeBindingDigest, storageKeySecurity, newCeremoniesAllowed)

    /**
     * Android-only legacy projection. Windows/macOS consumers must use [installedTarget]; asking
     * this view for an Android descriptor is refused rather than fabricating UID/APK/AndroidId.
     */
    public val target: SelfOwnedTargetDescriptorV1
        get() = installedTarget.androidTarget
            ?: throw IllegalStateException("legacy Android target is unavailable")

    init {
        selfOwnedBranchLabel(branchName); selfOwnedHexDigest(targetSigningFingerprint)
        selfOwnedHexDigest(originalNativeBindingDigest)
    }
    override fun toString(): String = "SelfOwnedNativeMaterialViewV1([REDACTED])"
}

public sealed interface SelfOwnedNativeReadResult {
    /** Original N-only positive record. Not absence, an installation receipt, Room or live history. */
    public class Retained(public val material: SelfOwnedNativeMaterialView,
        public val binding: SelfOwnedNativeBindingV1) : SelfOwnedNativeReadResult {
        /** Installed-target DATA retained for this exact native material read. */
        public val installedTarget: SelfOwnedInstalledTargetBindingV1 get() = material.installedTarget
    }
    public data class Unresolved(public val reason: SelfOwnedInstallationFailureV1) : SelfOwnedNativeReadResult
}

public sealed interface SelfOwnedNativeWithdrawal {
    public data object Retained : SelfOwnedNativeWithdrawal
    public data class Unavailable(public val reason: SelfOwnedInstallationFailureV1) : SelfOwnedNativeWithdrawal
}

/**
 * Actual native factory's bounded UI-facing handle. It owns no Room pool or employee session;
 * there is deliberately no accepting DeviceProtected port or full ceremony verifier here.
 * Historical native read does not renew an installer/cashier grant. No caller selects a root,
 * packet, path, key alias, custody callback or readiness Boolean.
 */
public class SelfOwnedLocalSecurityContext internal constructor(
    internal val controller: SelfOwnedNativeContextController,
) {
    /** Immutable acquisition-time description; later status requires original native readback. */
    public val materialAtOpen: SelfOwnedNativeMaterialView = controller.materialAtOpen
    /** Same acquisition-time installed-target DATA, explicit for non-Android native producers. */
    public val installedTargetAtOpen: SelfOwnedInstalledTargetBindingV1
        get() = materialAtOpen.installedTarget
    public val compositionProfile: LocalCompositionSdkProfileV1 get() = controller.compositionProfile
    public val protectionStatus: KeyProtectionStatus get() = KeyProtectionStatus.UNQUALIFIED_CONTEXT

    public suspend fun readOriginalNativeBinding(): SelfOwnedNativeReadResult = controller.readOriginalNativeBinding()
    /** Additive Windows/macOS retained V2 binding; the default controller path is a bounded refusal. */
    public suspend fun readOriginalNativeBindingV2(): SelfOwnedNativeReadResultV2 = controller.readOriginalNativeBindingV2()
    public suspend fun withdrawNewCeremonies(): SelfOwnedNativeWithdrawal = controller.withdrawNewCeremonies()
    /** Bounded denial while this handle still owns custody; inert after successful transfer. */
    public fun denyNewCeremonies(): Unit = controller.denyContextNewCeremonies()
    /** One-way denial before transfer, not proof of process death or resource closure. */
    public fun denyDataUse(): Unit = controller.denyContextDataUse()
    /** After transfer only the new owner may close the native lifetime; this stale handle is inert. */
    public suspend fun close(): Unit = controller.closeContext()
    internal suspend fun claimOriginalHistoryCustody(): SelfOwnedNativeHistoryClaimResult = controller.claimOriginalHistoryCustody()
    override fun toString(): String = "SelfOwnedLocalSecurityContextV1([REDACTED])"
}

/** Module-private concrete ownership control, not a caller-supplied verifier/provider boundary. */
internal interface SelfOwnedNativeContextController {
    val materialAtOpen: SelfOwnedNativeMaterialView
    val compositionProfile: LocalCompositionSdkProfileV1
    suspend fun readOriginalNativeBinding(): SelfOwnedNativeReadResult
    /** Existing Android/macOS/Windows owners that do not implement V2 keep a bounded refusal. */
    suspend fun readOriginalNativeBindingV2(): SelfOwnedNativeReadResultV2 =
        SelfOwnedNativeReadResultV2.Unresolved(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    suspend fun withdrawNewCeremonies(): SelfOwnedNativeWithdrawal
    fun denyContextNewCeremonies()
    fun denyContextDataUse()
    suspend fun closeContext()
    suspend fun claimOriginalHistoryCustody(): SelfOwnedNativeHistoryClaimResult
}

public sealed interface SelfOwnedNativeOpenResult {
    public class Opened(public val context: SelfOwnedLocalSecurityContext) : SelfOwnedNativeOpenResult
    /** No safe-absence/new-company/Room authorization can be inferred from this refusal. */
    public data class Unresolved(public val reason: SelfOwnedInstallationFailureV1) : SelfOwnedNativeOpenResult
}
