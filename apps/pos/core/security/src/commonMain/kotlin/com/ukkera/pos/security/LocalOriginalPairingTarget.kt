package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** DATA-only result of a retained original pairing target read. */
public sealed interface LocalOriginalPairingTargetResult<out T> {
    public class Value<T>(public val value: T) : LocalOriginalPairingTargetResult<T>
    public data class Unavailable(public val reason: SelfOwnedInstallationFailureV1) : LocalOriginalPairingTargetResult<Nothing>
}

/**
 * Platform-neutral view of one actual retained original Client pairing target (Android or desktop)
 * used by the shared LAN RPC client. Reads are existing original DATA only: no Kit construction,
 * signature, import, repair or authority. Closing releases the native target's custody.
 */
public interface LocalOriginalPairingTarget {
    public val compositionProfile: LocalCompositionSdkProfileV1
    public suspend fun recoverSdkOriginalData(): LocalOriginalPairingTargetResult<LocalDevicePairingSdkOriginal>
    public suspend fun recoverApprovedMasterData(originalPairingActionId: DomainId):
        LocalOriginalPairingTargetResult<LocalDevicePairingApprovedMaster>
    public fun denyNewWork()
    public suspend fun close()
}
