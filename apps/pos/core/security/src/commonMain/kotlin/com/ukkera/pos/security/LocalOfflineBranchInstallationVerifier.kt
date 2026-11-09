package com.ukkera.pos.security

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Actual production consumer of the immutable verified context. No public accepting constructor,
 * injectable signature Boolean, transport proof DTO, package-only success or registration route.
 * Independently verifies fresh signatures by the named human, exact device and native custody
 * keys over role-separated challenges binding the complete service-computed requested digest.
 */
public class LocalOfflineBranchInstallationVerifier internal constructor(
    private val context: InstalledOfflineInstallationContext,
) : LocalBranchInstallationVerifier {
    private val slot: Semaphore = Semaphore(1)

    override suspend fun verify(request: LocalBranchInstallationRequest): VerifiedLocalBranchInstallationCeremony? {
        currentCoroutineContext().ensureActive()
        if (request.identity.sourceKind != LocalInstallationSourceKind.AUTHENTICATED_OFFLINE_PACKAGE ||
            request != context.approvedRequest || !slot.tryAcquire()) return null
        return try {
            val result = withTimeoutOrNull(OfflineInstallationFormat.CEREMONY_MILLIS) { verifyCurrent(request) }
            currentCoroutineContext().ensureActive()
            if (result == null) context.retire()
            result
        } catch (cancelled: CancellationException) { context.retire(); throw cancelled }
        catch (_: Exception) { context.retire(); currentCoroutineContext().ensureActive(); null }
        finally { slot.release() }
    }

    private suspend fun verifyCurrent(request: LocalBranchInstallationRequest): VerifiedLocalBranchInstallationCeremony? {
        val artifacts = context.artifacts
        val owner = context.owner
        val start = context.sampleCurrent() ?: return null
        // Per-ceremony progress only; never a competing shared writer high-water.
        var lastObservedElapsed = start.moment.elapsedMillis
        val deadline = offlineInstallationDeadline(artifacts, start)
        val nonce = offlineInstallationRandomChallenge() ?: return null
        try {
            val probe = offlineInstallationProtectionProbe(artifacts, context.protection, context.pepper, nonce) ?: return null
            suspend fun prove(role: OfflineChallengeRole, key: String, operation: suspend (ByteArray) -> ByteArray?): Boolean {
                currentCoroutineContext().ensureActive()
                val current = context.sampleCurrent(lastObservedElapsed) ?: return false
                if (current.moment.elapsedMillis >= deadline) return false
                lastObservedElapsed = current.moment.elapsedMillis
                val message = offlineInstallationChallenge(artifacts, role, nonce, start, deadline, probe)
                return try { offlineVerifiedNativeSignature(key, message, operation) } finally { message.fill(0) }
            }
            if (!prove(OfflineChallengeRole.HUMAN_APPROVAL, artifacts.delegation.humanPublicKeyHex) { message ->
                owner.approveNamedHuman(artifacts.delegation.humanId, artifacts.delegation.humanKeyId,
                    artifacts.lease.humanSessionId, request, message)
            }) return null
            if (!prove(OfflineChallengeRole.DEVICE_POSSESSION, artifacts.source.device.publicKeyHex) { message ->
                owner.proveDevicePossession(artifacts.source.device.deviceId, artifacts.source.device.keyId, request, message)
            }) return null
            if (!prove(OfflineChallengeRole.CEREMONY_CUSTODY, artifacts.binding.custodyPublicKeyHex) { message ->
                owner.proveCustody(artifacts.binding.custodyKeyId, artifacts.lease.documentId, request, probe, message)
            }) return null
            currentCoroutineContext().ensureActive()
            // LAST native callback; only local immutable comparison/proof construction follows.
            val final = context.sampleCurrent(lastObservedElapsed) ?: return null
            if (final.moment.elapsedMillis >= deadline) return null
            val lifetime = { context.ceremonyCurrent(request, final.moment.elapsedMillis, deadline) }
            val identity = request.identity
            val human = VerifiedInstallationHuman(request, identity.responsibleHumanId, identity.humanAuthorizationReference, lifetime)
            val target = VerifiedInstallationTarget(request, identity.masterDeviceId, identity.sourceDigest, identity.deviceTrustReference, lifetime)
            val custody = VerifiedInstallationCustody(request, identity.storageInstanceId, identity.storageKeyId, identity.pepperKeyId,
                identity.custodyReference, identity.freshHistoryOriginReference, identity.monotonicContextReference, lifetime)
            return VerifiedLocalBranchInstallationCeremony(request, human, target, custody,
                LocalAuthenticationObservation(final.issuerContextId, final.moment), deadline)
        } finally { nonce.fill(0) }
    }
}
