package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.AppId

/**
 * Stable transport/SDK identity profile for one setup composition mode.
 *
 * LIVE remains the existing branch-wide production namespace byte-for-byte. TRAINING uses a
 * disjoint AppId and device-name namespace so the official P2pKit secure identity, transport
 * discovery and peer admission of a training composition cannot be mistaken for live. A profile
 * is descriptive DATA, not authority, admission or a live fiscal identity.
 */
public interface LocalCompositionSdkProfileV1 {
    public val mode: LocalSetupCompositionMode
    public fun appId(scope: BranchScope): AppId
    public fun deviceName(deviceId: DomainId): String
}

/** Separate training transport namespace; never admitted as a live branch AppId. */
public class LocalTrainingSdkProfileV1(public val slot: LocalTrainingCompositionSlot) :
    LocalCompositionSdkProfileV1 {
    override val mode: LocalSetupCompositionMode get() = LocalSetupCompositionMode.TRAINING

    override fun appId(scope: BranchScope): AppId = AppId(
        "ukkera-pos.rpc.training.v1:${slot.name.lowercase()}:" +
            "${scope.organizationId.value}:${scope.companyId.value}:${scope.branchId.value}",
    )

    override fun deviceName(deviceId: DomainId): String =
        "Training ${slot.name.lowercase()} Master ${deviceId.value}"

    override fun equals(other: Any?): Boolean = other is LocalTrainingSdkProfileV1 && other.slot == slot
    override fun hashCode(): Int = slot.hashCode()
    override fun toString(): String = "LocalTrainingSdkProfileV1(${slot.name})"

    public companion object {
        public fun forAppId(appId: AppId, scope: BranchScope): LocalTrainingSdkProfileV1? =
            LocalTrainingCompositionSlot.entries.firstOrNull { LocalTrainingSdkProfileV1(it).appId(scope) == appId }
                ?.let(::LocalTrainingSdkProfileV1)
    }
}
