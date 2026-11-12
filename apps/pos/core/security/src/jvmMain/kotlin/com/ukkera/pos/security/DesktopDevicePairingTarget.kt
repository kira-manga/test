package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.nio.file.Path

/** OS key custody of one desktop Client target key (Windows CNG / macOS Keychain). */
internal interface DesktopClientTargetKey : AutoCloseable {
    val security: SelfOwnedNativeSecurityV1
    fun publicPoint(): ByteArray
    /** Signs a fixed owner message; returns a verified canonical 64-byte signature. */
    fun sign(message: ByteArray): ByteArray
}

/** Native facts and key custody for the desktop Client target; every value is an actual observation. */
internal interface DesktopClientTargetPlatform {
    val slot: LocalTrainingCompositionSlot?
    /** Fixed application-private parent directory (Windows package LocalState, macOS Application Support). */
    fun parentDirectory(): Path
    fun origin(directory: Path): TargetOrigin.Desktop
    fun bootSessionId(): String
    fun elapsedMillis(): Long
    fun createKey(preparationId: DomainId, keyId: DomainId): DesktopClientTargetKey
    fun openKey(preparationId: DomainId, keyId: DomainId, originalPoint: ByteArray): DesktopClientTargetKey
}

/**
 * Desktop (Windows/macOS) Client original pairing target. The record sequence mirrors the Android
 * target: identity is forced before the only key generation, then the key intent, then the signed
 * ready snapshot. Every record is write-once; an interrupted step is reviewable DATA and is never
 * retried or repaired. Public points and descriptors are DATA, not attestation or authority.
 *
 * Convergence note: the sequencing duplicates the Android native state machine, which cannot be
 * moved without device re-qualification; both use the shared record codecs.
 */
internal class DesktopDevicePairingTarget private constructor(
    private val platform: DesktopClientTargetPlatform,
    private val store: DesktopTargetSnapshotStore,
) : AutoCloseable {
    private var identity: TargetIdentity? = null
    private var keyIntent: TargetKeyIntent? = null
    private var ready: TargetReady? = null
    private var key: DesktopClientTargetKey? = null
    private var reference: LocalDevicePairingTargetReference? = null
    @Volatile private var denied = false
    private var closed = false

    @Synchronized fun original(): LocalDevicePairingTargetReference {
        requireUsable()
        return checkNotNull(reference)
    }

    @Synchronized fun descriptor(): LocalDevicePairingTargetDescriptor {
        requireUsable()
        val signed = ready ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireContinuity()
        val bytes = TargetWire.decodeReadyMessage(signed.message, VARIANT).descriptor.copyBytes()
        return try { LocalDevicePairingTargetDescriptor.decode(bytes) } finally { bytes.fill(0) }
    }

    /** Immediate denial of new work; retained records and key are unchanged. */
    fun denyNewWork() { denied = true }

    @Synchronized override fun close() {
        if (closed) return
        closed = true; denied = true
        var failure: Throwable? = null
        try { key?.close() } catch (error: Throwable) { failure = error }
        try { store.close() } catch (error: Throwable) { failure?.addSuppressed(error) ?: run { failure = error } }
        failure?.let { throw it }
    }

    private fun requireUsable() {
        if (closed || denied) selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    }

    private fun phase(value: LocalDevicePairingTargetPhase) {
        val original = checkNotNull(identity)
        reference = LocalDevicePairingTargetReference(original.preparationId, original.deviceId, original.keyId, value,
            original.responseId)
    }

    /** Current origin, boot order and key point still match every retained record. */
    private fun requireContinuity() {
        val original = checkNotNull(identity)
        known(platform.origin(store.directory) == original.origin)
        val current = DesktopTargetBootLedger.current(store) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        known(current.sessionId == platform.bootSessionId() && original.osBoot <= current)
        val intent = keyIntent ?: return
        val actual = checkNotNull(key).publicPoint()
        val retained = intent.point.copyBytes()
        try { known(actual.contentEquals(retained)) } finally { actual.fill(0); retained.fill(0) }
    }

    private fun prepare(scope: BranchScope, capabilities: List<String>) {
        val boot = DesktopTargetBootLedger.observe(store, platform.bootSessionId())
        val forbidden = setOf(scope.organizationId, scope.companyId, scope.branchId)
        val ids = List(4) { JvmP256Primitives.newId() }
        known(ids.distinct().size == 4 && ids.none { it in forbidden })
        val nonce = JvmP256Primitives.randomBytes(32)
        val original = try {
            TargetIdentity(ids[0], ids[1], ids[2], ids[3], scope, capabilities, platform.origin(store.directory), boot,
                platform.elapsedMillis(), SelfOwnedOctetsV1(nonce, 32))
        } finally { nonce.fill(0) }
        val identityBytes = TargetWire.identity(original, VARIANT)
        try { store.forceNew(IDENTITY, identityBytes) } finally { identityBytes.fill(0) }
        identity = original; phase(LocalDevicePairingTargetPhase.KEY_GENERATION_INTENT)
        // The forced identity precedes the only generation; an unknown outcome is never retried.
        val created = platform.createKey(original.preparationId, original.keyId)
        key = created
        val point = created.publicPoint()
        try {
            known(DesktopTargetBootLedger.current(store)?.sameBoot(boot) == true)
            val intent = TargetKeyIntent(sha256(read(IDENTITY, DevicePairingTargetLimitsV1.IDENTITY_MAX)), boot,
                platform.elapsedMillis(), created.security, SelfOwnedOctetsV1(point, 65))
            val intentBytes = TargetWire.keyIntent(intent, VARIANT)
            try { store.forceNew(KEY_INTENT, intentBytes) } finally { intentBytes.fill(0) }
            keyIntent = intent; phase(LocalDevicePairingTargetPhase.KEY_METADATA_SIGNING_INTENT)
            val descriptor = expectedDescriptor(original, point).copyWireBytes()
            val message = try {
                TargetWire.readyMessage(read(IDENTITY, DevicePairingTargetLimitsV1.IDENTITY_MAX),
                    read(KEY_INTENT, DevicePairingTargetLimitsV1.KEY_INTENT_MAX), descriptor, VARIANT)
            } finally { descriptor.fill(0) }
            try {
                val signature = created.sign(message)
                try {
                    known(JvmP256Primitives.verify(point, message, signature))
                    val signed = TargetReady(SelfOwnedOctetsV1(message, DevicePairingTargetLimitsV1.READY_MAX),
                        SelfOwnedOctetsV1(signature, 64))
                    val readyBytes = TargetWire.ready(signed, VARIANT)
                    try { store.forceNew(READY, readyBytes) } finally { readyBytes.fill(0) }
                    ready = signed
                } finally { signature.fill(0) }
            } finally { message.fill(0) }
        } finally { point.fill(0) }
        validate()
    }

    private fun openExisting() {
        DesktopTargetBootLedger.observe(store, platform.bootSessionId())
        if (IDENTITY in store.pendingNames()) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val identityBytes = read(IDENTITY, DevicePairingTargetLimitsV1.IDENTITY_MAX)
        identity = TargetWire.decodeIdentity(identityBytes, VARIANT)
        phase(LocalDevicePairingTargetPhase.KEY_GENERATION_INTENT)
        store.read(KEY_INTENT, DevicePairingTargetLimitsV1.KEY_INTENT_MAX)?.let { bytes ->
            keyIntent = try { TargetWire.decodeKeyIntent(bytes, VARIANT) } finally { bytes.fill(0) }
            val original = checkNotNull(identity)
            val point = checkNotNull(keyIntent).point.copyBytes()
            try { key = platform.openKey(original.preparationId, original.keyId, point) } finally { point.fill(0) }
            phase(LocalDevicePairingTargetPhase.KEY_METADATA_SIGNING_INTENT)
        }
        store.read(READY, DevicePairingTargetLimitsV1.READY_MAX)?.let { bytes ->
            known(keyIntent != null)
            ready = try { TargetWire.decodeReady(bytes, VARIANT) } finally { bytes.fill(0) }
        }
        validate()
    }

    private fun validate() {
        val original = checkNotNull(identity)
        val identityBytes = read(IDENTITY, DevicePairingTargetLimitsV1.IDENTITY_MAX)
        try {
            val encoded = TargetWire.identity(original, VARIANT)
            try { known(encoded.contentEquals(identityBytes)) } finally { encoded.fill(0) }
            requireContinuity()
            val intent = keyIntent ?: return
            known(intent.identityDigest == sha256(identityBytes) && intent.osBoot.sameBoot(original.osBoot) &&
                intent.observedElapsed >= original.observedElapsed)
            val signed = ready ?: return
            val point = intent.point.copyBytes()
            val fields = TargetWire.decodeReadyMessage(signed.message, VARIANT)
            val message = signed.message.copyBytes(); val signature = signed.signature.copyBytes()
            val intentBytes = read(KEY_INTENT, DevicePairingTargetLimitsV1.KEY_INTENT_MAX)
            try {
                known(fields.identity.same(identityBytes) && fields.keyIntent.same(intentBytes))
                val actual = fields.descriptor.copyBytes(); val wanted = expectedDescriptor(original, point).copyWireBytes()
                try { known(actual.contentEquals(wanted)) } finally { actual.fill(0); wanted.fill(0) }
                known(JvmP256Primitives.verify(point, message, signature))
            } finally { point.fill(0); message.fill(0); signature.fill(0); intentBytes.fill(0) }
            phase(LocalDevicePairingTargetPhase.KEY_READY)
        } finally { identityBytes.fill(0) }
    }

    private fun read(name: String, maximum: Int): ByteArray =
        store.read(name, maximum) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)

    override fun toString(): String = "DesktopDevicePairingTarget([REDACTED])"

    companion object {
        private val VARIANT = TargetWireVariant.DESKTOP_V2
        const val IDENTITY = "identity.v2"
        const val KEY_INTENT = "key-signing-intent.v2"
        const val READY = "key-ready.v2"

        private fun known(value: Boolean) { if (!value) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }

        private fun expectedDescriptor(original: TargetIdentity, point: ByteArray): LocalDevicePairingTargetDescriptor =
            LocalDevicePairingTargetDescriptor.capture(LocalDeviceAdmission(original.scope, original.deviceId, Revision(0),
                true, original.keyId.value, sha256(point).value, original.capabilities.toSet()), original.keyId, point)

        private fun directory(platform: DesktopClientTargetPlatform): Path =
            platform.parentDirectory().resolve(SelfOwnedCompositionNames.devicePairingTargetDirectory(platform.slot))

        /** Explicit FIRST preparation only; an existing target directory is never adopted. */
        fun prepareNew(platform: DesktopClientTargetPlatform, scope: BranchScope, capabilities: Set<String>): DesktopDevicePairingTarget {
            val copied = capabilities.sorted()
            if (copied.size > ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITIES || copied.any { code ->
                    code.length !in 1..ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITY_BYTES ||
                        code.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it !in "._-" } }) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
            }
            val target = DesktopDevicePairingTarget(platform, DesktopTargetSnapshotStore.create(directory(platform)))
            try { target.prepare(scope, copied); return target } catch (failure: Throwable) {
                try { target.close() } catch (close: Throwable) { failure.addSuppressed(close) }
                throw failure
            }
        }

        /** Existing only; never creates a missing directory, record or key. */
        fun openExisting(platform: DesktopClientTargetPlatform): DesktopDevicePairingTarget {
            val target = DesktopDevicePairingTarget(platform, DesktopTargetSnapshotStore.openExisting(directory(platform)))
            try { target.openExisting(); return target } catch (failure: Throwable) {
                try { target.close() } catch (close: Throwable) { failure.addSuppressed(close) }
                throw failure
            }
        }
    }
}
