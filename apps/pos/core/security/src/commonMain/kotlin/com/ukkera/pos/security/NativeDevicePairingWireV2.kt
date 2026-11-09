package com.ukkera.pos.security

import com.ukkera.pos.types.Revision

/** Separate profile domains. The original ledger/body fields and every V1 encoding stay
 * unchanged; only newly captured SDK-bound definitions and proofs use these domains. */
internal object NativeDevicePairingWireV2 {
    fun isV2Domain(bytes: ByteArray, v1Domain: String): Boolean {
        require(v1Domain.endsWith("/v1"))
        val reader = SelfOwnedWireReaderV1(bytes)
        require(reader.string(128) == SelfOwnedInstallationProfileV1.ID)
        val domain = reader.string(128)
        require(domain == v1Domain || domain == v1Domain.removeSuffix("/v1") + "/v2")
        return domain != v1Domain
    }

    fun encodeDefinition(value: DevicePairingDefinitionFieldsV1): ByteArray = selfOwnedEncode(
        "full-one-new-device-pairing-definition/v2", LocalDevicePairingProfileV1.MAXIMUM_DEFINITION_BYTES) {
        string(LocalDevicePairingProfileV2.PURPOSE)
        scope(value.request.scope); id(value.request.requestId); string(value.request.action.name)
        id(value.request.targetId); string(value.request.contentDigest.value)
        val target = value.target.copyWireBytes(); try { field(target) } finally { target.fill(0) }
        octets(value.serviceAdmission); int(value.admissions.size); value.admissions.forEach { admission(it) }
        val sdk = requireNotNull(value.sdkIdentity).copyWireBytes(); try { field(sdk) } finally { sdk.fill(0) }
    }

    fun decodeDefinition(bytes: ByteArray): DevicePairingDefinitionFieldsV1 = selfOwnedDecode(
        bytes, "full-one-new-device-pairing-definition/v2") {
        require(string(128) == LocalDevicePairingProfileV2.PURPOSE)
        val scope = scope(); val requestId = id(); val actionName = string(128)
        val action = requireNotNull(LocalProvisioningAction.entries.singleOrNull { it.name == actionName })
        val request = LocalProvisioningRequest(scope, requestId, action, id(), PayloadDigest(string(64)))
        val targetBytes = field(ProtectedLocalTable.ADMISSION.maximumPlaintextBytes + 1024)
        val target = try { LocalDevicePairingTargetDescriptor.decode(targetBytes) } finally { targetBytes.fill(0) }
        val service = octets(ProtectedLocalTable.ADMISSION.maximumPlaintextBytes)
        val count = int(); require(count in 1..256)
        val admissions = List(count) { admission() }
        val sdkBytes = field(LocalDevicePairingProfileV2.MAXIMUM_SDK_IDENTITY_BYTES)
        val sdk = try { LocalDevicePairingSdkIdentityV2.decode(sdkBytes) } finally { sdkBytes.fill(0) }
        DevicePairingDefinitionFieldsV1(request, target, service, admissions, sdk)
    }

    fun encodePossession(value: NativeDevicePairingPossessionV1): ByteArray = selfOwnedEncode(
        "target-device-pairing-possession-data/v2", LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES) {
        string(LocalDevicePairingProfileV2.PURPOSE); id(value.originalActionId); string(value.challengeDigest.value)
        id(value.deviceId); id(value.keyId); string(value.descriptorDigest.value)
        string(requireNotNull(value.sdkIdentityDigest).value); octets(value.signature)
    }

    fun decodePossession(bytes: ByteArray): NativeDevicePairingPossessionV1 = selfOwnedDecode(
        bytes, "target-device-pairing-possession-data/v2") {
        require(string(128) == LocalDevicePairingProfileV2.PURPOSE)
        val action = id(); val challenge = PayloadDigest(string(64)); val device = id(); val key = id()
        val descriptor = PayloadDigest(string(64)); val sdk = PayloadDigest(string(64))
        NativeDevicePairingPossessionV1(action, challenge, device, key, descriptor, octets(64), sdk)
    }

    private fun SelfOwnedWireWriterV1.scope(value: BranchScope) {
        id(value.organizationId); id(value.companyId); id(value.branchId)
    }
    private fun SelfOwnedWireReaderV1.scope(): BranchScope = BranchScope(id(), id(), id())
    private fun SelfOwnedWireWriterV1.admission(value: LocalDeviceAdmission) {
        scope(value.scope); id(value.deviceId); long(value.revision.value); bool(value.admitted)
        bool(value.publicKeyIdentifier != null); value.publicKeyIdentifier?.let { string(it) }
        bool(value.publicFingerprint != null); value.publicFingerprint?.let { string(it) }
        int(value.capabilities.size); value.capabilities.sorted().forEach { string(it) }
    }
    private fun SelfOwnedWireReaderV1.admission(): LocalDeviceAdmission {
        val scope = scope(); val device = id(); val revision = Revision(long()); val admitted = bool()
        val key = if (bool()) string(ProtectedLocalStorageFormat.MAX_ADMISSION_DESCRIPTOR_BYTES) else null
        val fingerprint = if (bool()) string(ProtectedLocalStorageFormat.MAX_ADMISSION_DESCRIPTOR_BYTES) else null
        val count = int(); require(count in 0..ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITIES)
        val capabilities = List(count) { string(ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITY_BYTES) }
        require(capabilities == capabilities.sorted() && capabilities.distinct().size == capabilities.size)
        return LocalDeviceAdmission(scope, device, revision, admitted, key, fingerprint, capabilities.toSet())
    }
}
