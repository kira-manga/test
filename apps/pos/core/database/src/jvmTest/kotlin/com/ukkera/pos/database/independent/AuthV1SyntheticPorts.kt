package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * EXECUTOR FIXTURE PLUMBING ONLY. These ephemeral JCA keys are NOT an OS key store,
 * supervised-service qualification, pairing proof or production fallback. The status
 * enum below is an explicitly synthetic input to the real production adapters.
 * There is deliberately no authentication/session/repository implementation here.
 *
 * May be reused by another frozen-case executor as synthetic plumbing, not as an oracle.
 * Ownership: /root/backend; LOCAL ONLY; no secret is persisted or returned for evidence.
 */
internal class AuthV1SyntheticKeyPorts(
    val storageReference: ProtectedStorageKeyReference,
    val pepperReference: ProtectedPepperReference,
    private val random: SecureRandom = SecureRandom(),
) : DeviceProtectedLocalStorage, DeviceProtectedPepper, AutoCloseable {
    private val storageKey = ByteArray(32).also(random::nextBytes)
    private val pepperKey = ByteArray(32).also(random::nextBytes)
    private val active = AtomicBoolean(true)

    override val protectionStatus: KeyProtectionStatus
        get() = if (active.get()) KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT
        else KeyProtectionStatus.UNQUALIFIED_CONTEXT

    override suspend fun seal(
        reference: ProtectedStorageKeyReference,
        binding: ProtectedLocalBinding,
        plaintext: ByteArray,
    ): LocalSealResult {
        if (!active.get() || reference != storageReference) {
            return LocalSealResult.Unavailable(ProtectedKeyFailure.KEY_UNAVAILABLE)
        }
        binding.requirePlaintextSize(plaintext.size)
        val nonce = ByteArray(ProtectedLocalStorageFormat.NONCE_BYTES).also(random::nextBytes)
        val aad = binding.aad(reference)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(storageKey, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plaintext)
            try {
                LocalSealResult.Sealed(SealedLocalRecord(binding, reference, nonce, ciphertext))
            } finally { ciphertext.fill(0) }
        } catch (_: GeneralSecurityException) {
            LocalSealResult.Unavailable(ProtectedKeyFailure.PROVIDER_FAILURE)
        } finally {
            nonce.fill(0)
            aad.fill(0)
        }
    }

    override suspend fun open(
        reference: ProtectedStorageKeyReference,
        expected: ProtectedLocalBinding,
        sealed: SealedLocalRecord,
    ): LocalOpenResult {
        if (!active.get() || reference != storageReference) {
            return LocalOpenResult.Unavailable(ProtectedKeyFailure.KEY_UNAVAILABLE)
        }
        if (sealed.binding != expected || sealed.keyReference != reference) {
            return LocalOpenResult.Unavailable(ProtectedKeyFailure.CORRUPT_PROTECTED_STATE)
        }
        val nonce = sealed.copyNonce()
        val ciphertext = sealed.copyCiphertextAndTag()
        val aad = expected.aad(reference)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(storageKey, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val plaintext = cipher.doFinal(ciphertext)
            try {
                expected.requirePlaintextSize(plaintext.size)
                LocalOpenResult.Opened(plaintext)
            } finally { plaintext.fill(0) }
        } catch (_: GeneralSecurityException) {
            LocalOpenResult.Unavailable(ProtectedKeyFailure.CORRUPT_PROTECTED_STATE)
        } finally {
            nonce.fill(0)
            ciphertext.fill(0)
            aad.fill(0)
        }
    }

    override suspend fun hmacSha256(reference: ProtectedPepperReference, message: ByteArray): PepperMacResult {
        if (!active.get() || reference != pepperReference) {
            return PepperMacResult.Unavailable(ProtectedKeyFailure.KEY_UNAVAILABLE)
        }
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(pepperKey, "HmacSHA256"))
            val tag = mac.doFinal(message)
            try { PepperMacResult.Authenticated(tag) } finally { tag.fill(0) }
        } catch (_: GeneralSecurityException) {
            PepperMacResult.Unavailable(ProtectedKeyFailure.PROVIDER_FAILURE)
        }
    }

    override fun close() {
        active.set(false)
        storageKey.fill(0)
        pepperKey.fill(0)
    }

    override fun toString(): String = "AuthV1SyntheticKeyPorts(SYNTHETIC,[REDACTED])"
}

/** Fixed monotonic observation, NOT a real clock/expiry/suspend/restart qualification. */
internal class AuthV1SyntheticEnvironment(
    val issuerContextId: DomainId,
    val moment: MonotonicMoment,
) : LocalAuthenticationEnvironment, AutoCloseable {
    private val active = AtomicBoolean(true)
    override fun observe(): LocalAuthenticationObservation? =
        if (active.get()) LocalAuthenticationObservation(issuerContextId, moment) else null
    override fun close() { active.set(false) }
}

/** Descriptors below assert NO real transport authentication, encryption or physical pairing. */
internal class AuthV1SyntheticPeers : LocalAuthenticatedPeerSource, AutoCloseable {
    private class Entry(val peer: LocalAuthenticatedPeer, val live: AtomicBoolean)
    private val entries = ConcurrentHashMap<DomainId, Entry>()

    fun install(
        connectionId: DomainId,
        scope: BranchScope,
        deviceId: DomainId,
        publicKeyIdentifier: String,
    ) {
        val live = AtomicBoolean(true)
        val entry = Entry(LocalAuthenticatedPeer(connectionId, scope, deviceId,
            publicKeyIdentifier, null, live::get), live)
        check(entries.putIfAbsent(connectionId, entry) == null) { "Synthetic connection already registered" }
    }

    override suspend fun capture(connectionId: DomainId): LocalAuthenticatedPeer? = entries[connectionId]?.peer
    override fun close() { entries.values.forEach { it.live.set(false) } }
}

/**
 * Single initial-qualification window. Only predeclared request/action/target tuples
 * may be authorized; the first full service-computed digest is retained for the tuple.
 * This is NOT a signed/delegated production provisioning ceremony. Close after setup.
 */
internal class AuthV1SyntheticProvisioning(
    private val scope: BranchScope,
    allowed: Map<DomainId, Pair<LocalProvisioningAction, DomainId>>,
) : LocalProvisioningAuthority, AutoCloseable {
    private val allowed = allowed.toMap()
    private val requests = ConcurrentHashMap<DomainId, LocalProvisioningRequest>()
    private val active = AtomicBoolean(true)

    override suspend fun authorize(request: LocalProvisioningRequest): LocalProvisioningPermit? {
        if (!active.get() || request.scope != scope ||
            allowed[request.requestId] != (request.action to request.targetId)) return null
        val previous = requests.putIfAbsent(request.requestId, request)
        if (previous != null && previous != request) return null
        return LocalProvisioningPermit(request) { active.get() && requests[request.requestId] == request }
    }

    override fun close() { active.set(false) }
}

/** Disposable identifiers for wiring resources only; frozen business/actor IDs come from the design. */
internal fun authV1EphemeralId(): DomainId = DomainId(UUID.randomUUID().toString())
