package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** Local append-only allocation DATA. It is neither remote approval nor an accepting head.
 * Existing-only acquisition requires this original AND the actual native AES/head/file state. */
internal data class NativePairedDeviceCredentialJournalRecord(
    val originalPairingActionId: DomainId,
    val bindingDigest: PayloadDigest,
    val journalId: DomainId,
    val keyId: DomainId,
    val protection: SelfOwnedKeyProtectionV1,
) {
    init { require(journalId != keyId) }
}

internal object NativePairedDeviceCredentialJournalWire {
    const val MAXIMUM_RECORD_BYTES = 2048
    private const val DOMAIN = "native-paired-device-credential-allocation/v1"
    private const val PROFILE = "EXACT_APPROVED_ORIGINAL_FIRST_OPEN_NO_COHOST_OR_MISSING_HISTORY_REPLACEMENT"

    fun record(value: NativePairedDeviceCredentialJournalRecord): ByteArray = selfOwnedEncode(DOMAIN, MAXIMUM_RECORD_BYTES) {
        string(PROFILE); id(value.originalPairingActionId); string(value.bindingDigest.value)
        id(value.journalId); id(value.keyId); int(value.protection.ordinal + 1)
    }

    fun decodeRecord(bytes: ByteArray): NativePairedDeviceCredentialJournalRecord = selfOwnedDecode(bytes, DOMAIN) {
        require(string(128) == PROFILE)
        val action = id(); val binding = PayloadDigest(string(64)); val journal = id(); val key = id()
        val protection = SelfOwnedKeyProtectionV1.entries.getOrNull(int() - 1) ?: error("paired.credentials.protection")
        NativePairedDeviceCredentialJournalRecord(action, binding, journal, key, protection)
    }.also { value ->
        val canonical = record(value)
        try { require(bytes.contentEquals(canonical)) } finally { canonical.fill(0) }
    }

    fun allocation(approved: LocalDevicePairingApprovedMaster, sdk: LocalDevicePairingSdkOriginal,
        nativeIdentityDigest: PayloadDigest, nativeReadyDigest: PayloadDigest,
        protection: SelfOwnedKeyProtectionV1): NativePairedDeviceCredentialJournalRecord {
        val name = sdk.exactDeviceName.encodeToByteArray(throwOnInvalidSequence = true)
        val nameDigest = try { sha256(name) } finally { name.fill(0) }
        val binding = selfOwnedEncode("native-paired-device-credential-original-binding/v1", 4096) {
            string(PROFILE)
            id(approved.scope.organizationId); id(approved.scope.companyId); id(approved.scope.branchId)
            id(approved.targetDeviceId); id(approved.targetKeyId)
            id(approved.originalPairingActionId); id(approved.originalRequestId)
            id(sdk.identity.originalPreparationId); id(sdk.identity.originalAcquisitionId)
            string(sdk.identity.descriptorDigest.value); string(nameDigest.value)
            id(approved.rootId); id(approved.rootKeyId); id(approved.foundingActionId)
            string(approved.rootFingerprint); string(approved.rootDocumentDigest.value)
            string(approved.approvalCertificateDigest.value); string(approved.challengeDigest.value)
            string(approved.possessionDigest.value); string(nativeIdentityDigest.value); string(nativeReadyDigest.value)
            int(protection.ordinal + 1)
        }
        val commitment = try { sha256(binding) } finally { binding.fill(0) }
        return NativePairedDeviceCredentialJournalRecord(approved.originalPairingActionId, commitment,
            id(commitment, "journal"), id(commitment, "key"), protection)
    }

    private fun id(binding: PayloadDigest, purpose: String): DomainId {
        val input = selfOwnedEncode("native-paired-device-credential-id/v1", 512) {
            string(binding.value); string(purpose)
        }
        val hex = try { sha256(input).value.take(32) } finally { input.fill(0) }
        return DomainId("${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
    }
}
