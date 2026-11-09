package com.ukkera.pos.security

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Verified, immutable installed configuration for ONE exact request/native lease. Only the real
 * factory below constructs it: installed roots -> signed scoped trust/revocations -> all six raw
 * documents -> current native identity -> actual AEAD/HMAC -> fresh custody-key possession.
 * Installation still requires the separate fresh named-human and device proofs in [verifier].
 *
 * Genuine cryptographic consumer; a real installed OS-custody/root-pin provider and supervised
 * host composition are STILL ABSENT/UNQUALIFIED. No public pin/config DTO, constructor, status,
 * package or context by itself establishes that native boundary. No central provider is called.
 * Use the exact exposed components in the SAME LocalMasterSecurity owner; its typed offline
 * composition hook additionally checks reference identity and submitted admission descriptors.
 */
public class InstalledOfflineInstallationContext private constructor(
    internal val owner: OfflineInstallationNativeOwner,
    internal val artifacts: OfflineVerifiedArtifacts,
    public val protection: DeviceProtectedLocalStorage,
    internal val pepper: DeviceProtectedPepper,
    public val passwords: LocalPasswordHasher,
    private val installedAt: OfflineInstallationNativeObservation,
) {
    private val retired = MutableStateFlow(false)
    private val observing = MutableStateFlow(false)
    public val scope: BranchScope = artifacts.request.identity.scope
    public val storageKey: ProtectedStorageKeyReference = ProtectedStorageKeyReference(artifacts.request.identity.storageKeyId)
    public val pepperReference: ProtectedPepperReference = ProtectedPepperReference(artifacts.request.identity.pepperKeyId)
    public val approvedRequest: LocalBranchInstallationRequest get() = artifacts.request
    public val environment: LocalAuthenticationEnvironment = LocalAuthenticationEnvironment {
        sampleCurrent()?.let { LocalAuthenticationObservation(it.issuerContextId, it.moment) }
    }
    public val verifier: LocalBranchInstallationVerifier = LocalOfflineBranchInstallationVerifier(this)

    /** Irreversible in this object. Does NOT physically stop a service, close a key or release native custody. */
    public fun retire(): Unit { retired.value = true }

    /** Additional definition/descriptor binding only, NOT an authorization Boolean or installation proof. */
    public fun matchesDefinition(definition: LocalBranchInstallationDefinition): Boolean = try {
        !retired.value && artifacts.matchesDefinition(definition)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    /**
     * Nonblocking/non-reentrant, with a one-way private retirement latch. No captured coroutine
     * Job survives a completed inner scope. Native current observation is the LAST external
     * callback: only immutable comparisons/local latch state follow it. The writer retains its
     * existing single authorization source/high-water; this creates no competing writer clock.
     */
    internal fun sampleCurrent(minimumElapsedMillis: Long = installedAt.moment.elapsedMillis): OfflineInstallationNativeObservation? {
        if (retired.value) return null
        if (!observing.compareAndSet(false, true)) { retire(); return null }
        return try {
            if (retired.value) return null
            val observed = readCurrent(owner, protection, pepper, artifacts,
                maxOf(minimumElapsedMillis, installedAt.moment.elapsedMillis))
            if (observed == null || retired.value) { retire(); null } else observed
        } catch (cancelled: CancellationException) { retire(); throw cancelled }
        catch (_: Exception) { retire(); null }
        finally { observing.value = false }
    }

    internal fun ceremonyCurrent(request: LocalBranchInstallationRequest, validFrom: Long, expiresAt: Long): Boolean {
        if (request != artifacts.request) return false
        val sample = sampleCurrent(validFrom) ?: return false
        return sample.moment.elapsedMillis < expiresAt
    }

    override fun toString(): String = "InstalledOfflineInstallationContext([REDACTED])"

    public companion object {
        private val factorySlot: Semaphore = Semaphore(1)

        /**
         * Read-only, bounded, no waiters. The mandatory native owner already owns its real lease;
         * this method neither enrols roots, opens Room, acquires OS locks nor generates packages.
         * Unavailable/invalid/busy/timeout returns null; caller cancellation is preserved. A hung
         * or uncertain native operation still requires physical supervisor retirement/reaping.
         */
        public suspend fun verifyInstalled(owner: OfflineInstallationNativeOwner): InstalledOfflineInstallationContext? {
            currentCoroutineContext().ensureActive()
            if (!factorySlot.tryAcquire()) return null
            var created: InstalledOfflineInstallationContext? = null
            try {
                val result = withTimeoutOrNull(OfflineInstallationFormat.CEREMONY_MILLIS) {
                    val storage = owner.storage
                    val pepper = owner.pepper
                    val evidence = owner.readInstalledEvidence() ?: return@withTimeoutOrNull null
                    currentCoroutineContext().ensureActive()
                    val initial = owner.observe() ?: return@withTimeoutOrNull null
                    val artifacts = OfflineVerifiedArtifacts.read(evidence, initial)
                    currentCoroutineContext().ensureActive()
                    val start = readCurrent(owner, storage, pepper, artifacts, initial.moment.elapsedMillis)
                        ?: return@withTimeoutOrNull null
                    val passwords = offlineInstallationPasswordHasher(pepper, PasswordHashParameters(artifacts.binding.passwordHashIterations))
                        ?: return@withTimeoutOrNull null
                    val nonce = offlineInstallationRandomChallenge() ?: return@withTimeoutOrNull null
                    try {
                        val deadline = offlineInstallationDeadline(artifacts, start)
                        val probe = offlineInstallationProtectionProbe(artifacts, storage, pepper, nonce)
                            ?: return@withTimeoutOrNull null
                        val challenge = offlineInstallationChallenge(artifacts, OfflineChallengeRole.CONTEXT_CUSTODY, nonce, start, deadline, probe)
                        val custody = try {
                            offlineVerifiedNativeSignature(artifacts.binding.custodyPublicKeyHex, challenge) { message ->
                                owner.proveCustody(artifacts.binding.custodyKeyId, artifacts.lease.documentId, artifacts.request, probe, message)
                            }
                        } finally { challenge.fill(0) }
                        if (!custody) return@withTimeoutOrNull null
                        currentCoroutineContext().ensureActive()
                        // Actual final native sample, then ONLY captured comparison/construction.
                        val final = readCurrent(owner, storage, pepper, artifacts, start.moment.elapsedMillis)
                            ?: return@withTimeoutOrNull null
                        if (final.moment.elapsedMillis >= deadline) return@withTimeoutOrNull null
                        InstalledOfflineInstallationContext(owner, artifacts, storage, pepper, passwords, final).also { created = it }
                    } finally { nonce.fill(0) }
                }
                // Active OUTER context, not the completed withTimeoutOrNull child Job.
                currentCoroutineContext().ensureActive()
                if (result == null) created?.retire()
                return result
            } catch (cancelled: CancellationException) { created?.retire(); throw cancelled }
            catch (_: Exception) { created?.retire(); currentCoroutineContext().ensureActive(); return null }
            finally { factorySlot.release() }
        }

        private fun readCurrent(owner: OfflineInstallationNativeOwner, storage: DeviceProtectedLocalStorage,
            pepper: DeviceProtectedPepper, artifacts: OfflineVerifiedArtifacts, minimumElapsedMillis: Long): OfflineInstallationNativeObservation? {
            if (owner.storage !== storage || owner.pepper !== pepper ||
                storage.protectionStatus != KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT ||
                pepper.protectionStatus != KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT) return null
            val current = owner.observe() ?: return null
            return if (artifacts.validAt(current) && current.moment.elapsedMillis >= minimumElapsedMillis) current else null
        }
    }
}
