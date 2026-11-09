package com.ukkera.pos.security

/** Additive inner definition only. V1/V2 bytes, outer action purposes and ledger remain exact. */
internal object NativeDevicePairingWireV3 {
    private const val DOMAIN = "full-one-new-device-pairing-definition/v3"

    fun isDefinition(bytes: ByteArray): Boolean {
        val reader = SelfOwnedWireReaderV1(bytes)
        require(reader.string(128) == SelfOwnedInstallationProfileV1.ID)
        val domain = reader.string(128)
        require(domain in setOf("full-one-new-device-pairing-definition/v1",
            "full-one-new-device-pairing-definition/v2", DOMAIN))
        return domain == DOMAIN
    }

    fun encodeDefinition(value: DevicePairingDefinitionFieldsV1): ByteArray = selfOwnedEncode(
        DOMAIN, LocalDevicePairingProfileV1.MAXIMUM_DEFINITION_BYTES) {
        string(LocalDevicePairingProfileV2.PURPOSE)
        // Reuse the unchanged canonical V2 definition instead of duplicating its admission codec.
        val targetDefinition = NativeDevicePairingWireV2.encodeDefinition(value.copy(masterSdkOriginal = null))
        val master = requireNotNull(value.masterSdkOriginal).copyWireBytes()
        try { field(targetDefinition); field(master) } finally { targetDefinition.fill(0); master.fill(0) }
    }

    fun decodeDefinition(bytes: ByteArray): DevicePairingDefinitionFieldsV1 = selfOwnedDecode(bytes, DOMAIN) {
        require(string(128) == LocalDevicePairingProfileV2.PURPOSE)
        val target = field(LocalDevicePairingProfileV1.MAXIMUM_DEFINITION_BYTES)
        val master = field(LocalMasterSdkProfileV1.MAXIMUM_ORIGINAL_BYTES)
        try {
            NativeDevicePairingWireV2.decodeDefinition(target).copy(
                masterSdkOriginal = LocalMasterSdkOriginalV1.decode(master))
        } finally { target.fill(0); master.fill(0) }
    }
}
