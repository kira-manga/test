package com.ukkera.pos.security

import com.ukkera.pos.types.Revision
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class OfflineChallengeRole { CONTEXT_CUSTODY, HUMAN_APPROVAL, DEVICE_POSSESSION, CEREMONY_CUSTODY }

/** Fresh random challenge, exact service-computed request, independent roles and all artifact cuts. */
internal fun offlineInstallationChallenge(
    artifacts: OfflineVerifiedArtifacts,
    role: OfflineChallengeRole,
    nonce: ByteArray,
    start: OfflineInstallationNativeObservation,
    expiresAt: Long,
    protectionProbeDigest: PayloadDigest,
): ByteArray {
    check(nonce.size == OfflineInstallationFormat.CHALLENGE_BYTES && expiresAt > start.moment.elapsedMillis)
    val request = OfflineInstallationWire.encode(LocalBranchInstallationRequest.serializer(), artifacts.request, 8192)
    val fields = listOf(role.name, artifacts.rootDigest.value, artifacts.trustDigest.value,
        artifacts.bindingDigest.value, artifacts.leaseDigest.value, artifacts.sourceDigest.value,
        artifacts.delegationDigest.value, artifacts.approvalDigest.value, start.nativeOwnerId.value,
        start.issuerContextId.value, start.moment.bootId.value, start.custodyLeaseId.value, start.humanSessionId.value,
        start.deviceKeyId.value, start.humanKeyId.value, start.custodyKeyId.value, start.moment.elapsedMillis.toString(),
        expiresAt.toString(), protectionProbeDigest.value).map(String::encodeToByteArray)
    return try {
        val bytes = offlineFrame("ukkera.offline-installation/challenge/v1", fields + listOf(nonce, request))
        if (bytes.size > 8192) { bytes.fill(0); error("Offline installation challenge exceeds bound") }
        bytes
    } finally { request.fill(0); fields.forEach { it.fill(0) } }
}

/** No waiting queue, overflowing deadline, wall-clock lifetime extension or beyond-lease proof. */
internal fun offlineInstallationDeadline(artifacts: OfflineVerifiedArtifacts, start: OfflineInstallationNativeObservation): Long {
    val now = start.moment.elapsedMillis
    check(now <= Long.MAX_VALUE - OfflineInstallationFormat.CEREMONY_MILLIS)
    return minOf(now + OfflineInstallationFormat.CEREMONY_MILLIS, artifacts.lease.expiresAtElapsedMillis).also { check(it > now) }
}

/** The returned raw signature never confers authority before THIS fixed Ed25519 verification. */
internal suspend fun offlineVerifiedNativeSignature(
    keyHex: String,
    message: ByteArray,
    operation: suspend (ByteArray) -> ByteArray?,
): Boolean {
    val key = offlinePublicKeyBytes(keyHex)
    val borrowed = message.copyOf()
    var returned: ByteArray? = null
    var signature: ByteArray? = null
    return try {
        currentCoroutineContext().ensureActive()
        returned = operation(borrowed)
        currentCoroutineContext().ensureActive()
        if (!offlineConstantEquals(borrowed, message)) return false
        val raw = returned ?: return false
        // Reject an oversized provider response BEFORE copying or scanning its unbounded bytes.
        if (raw.size != OfflineInstallationFormat.SIGNATURE_BYTES) return false
        val snapshot = raw.copyOf()
        signature = snapshot
        val verified = offlineInstallationVerifyEd25519(key, message, snapshot)
        currentCoroutineContext().ensureActive()
        verified
    } finally {
        borrowed.fill(0); key.fill(0); signature?.fill(0)
        returned?.let { if (it.size == OfflineInstallationFormat.SIGNATURE_BYTES) it.fill(0) }
    }
}

/**
 * Actual bounded AEAD round-trip and repeat HMAC operation through the SAME retained providers.
 * The nonpersisted auth-event binding carries random probe bytes, never a valid stored record.
 * Successful operations alone do NOT qualify the keys, origin, custody or a protected namespace;
 * the signed native binding/lease, fresh custody proof and actual owner remain mandatory.
 */
internal suspend fun offlineInstallationProtectionProbe(
    artifacts: OfflineVerifiedArtifacts,
    storage: DeviceProtectedLocalStorage,
    pepper: DeviceProtectedPepper,
    nonce: ByteArray,
): PayloadDigest? {
    val identity = artifacts.request.identity
    val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.AuthEvent(identity.scope, artifacts.binding.documentId), Revision(0))
    val storageKey = ProtectedStorageKeyReference(identity.storageKeyId)
    val pepperKey = ProtectedPepperReference(identity.pepperKeyId)
    val coordinateBytes = offlineFrameStrings("ukkera.offline-installation/protection-coordinates/v1", listOf(
        artifacts.rootDigest.value, artifacts.trustDigest.value, artifacts.bindingDigest.value, artifacts.leaseDigest.value,
        artifacts.request.definitionDigest.value, identity.scope.organizationId.value, identity.scope.companyId.value,
        identity.scope.branchId.value, identity.installationId.value, identity.storageInstanceId.value,
        identity.storageKeyId.value, identity.pepperKeyId.value))
    val owned = mutableListOf(coordinateBytes)
    try {
        check(nonce.size == OfflineInstallationFormat.CHALLENGE_BYTES)
        currentCoroutineContext().ensureActive()
        if (storage.protectionStatus != KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT ||
            pepper.protectionStatus != KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT) return null
        val plaintext = offlineFrame("ukkera.offline-installation/aead-probe/v1", listOf(coordinateBytes, nonce)).also { owned.add(it) }
        binding.requirePlaintextSize(plaintext.size)
        val borrowedPlaintext = plaintext.copyOf().also { owned.add(it) }
        val sealed = when (val result = storage.seal(storageKey, binding, borrowedPlaintext)) {
            is LocalSealResult.Sealed -> result.record
            is LocalSealResult.Unavailable -> return null
        }
        currentCoroutineContext().ensureActive()
        if (!offlineConstantEquals(borrowedPlaintext, plaintext) || sealed.binding != binding || sealed.keyReference != storageKey ||
            sealed.formatVersion != ProtectedLocalStorageFormat.FORMAT_VERSION || sealed.algorithm != ProtectedLocalStorageFormat.ALGORITHM ||
            sealed.ciphertextAndTagSize != plaintext.size + ProtectedLocalStorageFormat.TAG_BYTES) return null
        val sealedNonce = sealed.copyNonce().also { owned.add(it) }
        val ciphertext = sealed.copyCiphertextAndTag().also { owned.add(it) }
        if (sealedNonce.size != ProtectedLocalStorageFormat.NONCE_BYTES) return null
        val opened = when (val result = storage.open(storageKey, binding, sealed)) {
            is LocalOpenResult.Opened -> result
            is LocalOpenResult.Unavailable -> return null
        }
        currentCoroutineContext().ensureActive()
        if (opened.size != plaintext.size) return null
        val recovered = opened.copyPlaintext().also { owned.add(it) }
        if (!offlineConstantEquals(plaintext, recovered)) return null
        val message = offlineFrame("ukkera.offline-installation/pepper-probe/v1", listOf(coordinateBytes, nonce)).also { owned.add(it) }
        suspend fun tag(): ByteArray? {
            val borrowed = message.copyOf().also { owned.add(it) }
            currentCoroutineContext().ensureActive()
            val result = pepper.hmacSha256(pepperKey, borrowed)
            currentCoroutineContext().ensureActive()
            if (!offlineConstantEquals(borrowed, message) || result !is PepperMacResult.Authenticated) return null
            return result.copyTag().also { owned.add(it) }
        }
        val first = tag() ?: return null
        val second = tag() ?: return null
        if (first.size != 32 || second.size != 32 || !offlineConstantEquals(first, second)) return null
        return offlineHashFrame("ukkera.offline-installation/protection-transcript/v1",
            listOf(coordinateBytes, nonce, sealedNonce, ciphertext, first))
    } finally { owned.forEach { it.fill(0) } }
}
