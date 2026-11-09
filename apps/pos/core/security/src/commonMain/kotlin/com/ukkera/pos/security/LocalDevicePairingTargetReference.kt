package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** Target-only retained phases. They do not establish admission or remote attestation. */
public enum class LocalDevicePairingTargetPhase {
    KEY_GENERATION_INTENT, KEY_METADATA_SIGNING_INTENT, KEY_READY, RESPONSE_SIGNING_INTENT, RESPONSE_COMPLETED,
    RESPONSE_ALLOCATION_SIGNING_INTENT, RESPONSE_ALLOCATION_READY,
    SDK_ACQUISITION_INTENT, SDK_METADATA_SIGNING_INTENT, SDK_READY,
}

/** Original retained IDs as DATA; an unresolved reference is not a current-custody witness. */
public data class LocalDevicePairingTargetReference(public val originalPreparationId: DomainId,
    public val deviceId: DomainId, public val keyId: DomainId, public val phase: LocalDevicePairingTargetPhase,
    public val originalResponseId: DomainId? = null, public val originalPairingActionId: DomainId? = null,
    public val challengeDigest: PayloadDigest? = null, public val originalSdkAcquisitionId: DomainId? = null,
    public val sdkIdentityDigest: PayloadDigest? = null)
