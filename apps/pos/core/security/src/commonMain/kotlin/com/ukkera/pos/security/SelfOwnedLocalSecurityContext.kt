package com.ukkera.pos.security

/** Read-only projection of the originally authenticated native storage-key security. */
public enum class SelfOwnedStorageKeySecurity { SOFTWARE, TRUSTED_ENVIRONMENT, STRONGBOX, HARDWARE_KIND_UNSPECIFIED }

/** Immutable native-material description for display, never a complete installation/readiness proof. */
public class SelfOwnedNativeMaterialView internal constructor(
    public val coordinates: SelfOwnedAllocationCoordinatesV1,
    public val target: SelfOwnedTargetDescriptorV1,
    public val branchName: String,
    public val protection: SelfOwnedKeyProtectionV1,
    public val targetSigningFingerprint: String,
    public val originalNativeBindingDigest: String,
    /** Recorded in the verified original binding, never inferred from the requested protection. */
    public val storageKeySecurity: SelfOwnedStorageKeySecurity,
    /** Durable native permission AND this lifetime's denial ceiling; no full-ceremony approval. */
    public val newCeremoniesAllowed: Boolean,
) {
    init {
        selfOwnedBranchLabel(branchName); selfOwnedHexDigest(targetSigningFingerprint)
        selfOwnedHexDigest(originalNativeBindingDigest)
    }
    override fun toString(): String = "SelfOwnedNativeMaterialViewV1([REDACTED])"
}

public sealed interface SelfOwnedNativeReadResult {
    /** Original N-only positive record. Not absence, an installation receipt, Room or live history. */
    public class Retained(public val material: SelfOwnedNativeMaterialView,
        public val binding: SelfOwnedNativeBindingV1) : SelfOwnedNativeReadResult
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
    public val protectionStatus: KeyProtectionStatus get() = KeyProtectionStatus.UNQUALIFIED_CONTEXT

    public suspend fun readOriginalNativeBinding(): SelfOwnedNativeReadResult = controller.readOriginalNativeBinding()
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
    suspend fun readOriginalNativeBinding(): SelfOwnedNativeReadResult
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
