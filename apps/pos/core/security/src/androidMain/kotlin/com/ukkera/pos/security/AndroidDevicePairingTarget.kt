package com.ukkera.pos.security

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.ukkera.pos.android.files.AndroidNativeFiles
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.AppId
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Source-compatible Android names for reusable retained DATA; neither is native custody. */
public typealias AndroidDevicePairingTargetPhase = LocalDevicePairingTargetPhase
public typealias AndroidDevicePairingTargetReference = LocalDevicePairingTargetReference

public sealed interface AndroidDevicePairingTargetOpenResult {
    public class Opened internal constructor(public val target: AndroidDevicePairingTarget): AndroidDevicePairingTargetOpenResult
    public data class Unresolved(public val reason: SelfOwnedInstallationFailureV1,
        public val original: AndroidDevicePairingTargetReference? = null): AndroidDevicePairingTargetOpenResult
}
public sealed interface AndroidDevicePairingTargetResult<out T> {
    public class Value<T>(public val value: T): AndroidDevicePairingTargetResult<T>
    public data class Unavailable(public val reason: SelfOwnedInstallationFailureV1): AndroidDevicePairingTargetResult<Nothing>
}

/** Actual new physical target's ONE private native P-256 key, never a P1 root/master slot.
 * No supplied signer/path/clock/provider, RPC, admission or automatic subsequent enrollment.
 * Public points and unsigned challenges remain DATA: this native local custody observation
 * does NOT remotely attest nonexportability, hardware, the master or head authority. */
public class AndroidDevicePairingTarget private constructor(
    private val native: AndroidDevicePairingTargetNative,
    private val work: AndroidOwnedInstallationWork,
) {
    private val denied = AtomicBoolean(false)
    /** Fixed composition profile binding of this actual native target; descriptive DATA only. */
    public val compositionProfile: LocalCompositionSdkProfileV1 get() = native.compositionProfile
    private class CredentialCustodyIssue {
        val custody = AtomicReference<AndroidPairedDeviceCredentialJournalCustody?>(null)
        val settled = CompletableDeferred<Unit>()
    }
    private val credentialCustodyIssue = AtomicReference<CredentialCustodyIssue?>(null)
    public suspend fun original(): AndroidDevicePairingTargetResult<AndroidDevicePairingTargetReference> = run { native.original() }
    public suspend fun descriptor(): AndroidDevicePairingTargetResult<LocalDevicePairingTargetDescriptor> = run { native.descriptor() }
    /** Explicit original SDK acquisition, BEFORE the complete new-profile pairing action.
     * The actual official Android Kit is target-owned, unstarted and rejects unknown peers.
     * Completed same-input retries return original DATA without SDK construction. No supplied
     * Kit/key, implicit replacement, peer admission or private serving. */
    public suspend fun acquireSdkIdentity(appId: AppId, deviceName: String): AndroidDevicePairingTargetResult<LocalDevicePairingSdkIdentityV2> =
        run { native.acquireSdkIdentity(appId, deviceName) }
    /** Exact signed original SDK association DATA only. Does not construct a Kit, sign,
     * renew an action or establish a live original physical SDK/session lifetime. */
    public suspend fun recoverSdkIdentity(): AndroidDevicePairingTargetResult<LocalDevicePairingSdkIdentityV2> =
        run { native.recoverSdkIdentity() }
    /** Exact signed original SDK association and separately retained acquisition name DATA.
     * Existing originals only; does not construct a Kit, sign, renew or establish a live peer. */
    public suspend fun recoverSdkOriginal(): AndroidDevicePairingTargetResult<LocalDevicePairingSdkOriginal> =
        run { native.recoverSdkOriginal() }
    /** Explicit signing of THIS full unsigned challenge only. Never compares the master's
     * boot/elapsed deadline to this device's unrelated clock; only the master validates it. */
    public suspend fun respond(challenge: LocalDevicePairingChallenge): AndroidDevicePairingTargetResult<LocalDevicePairingPossession> =
        run { native.respond(challenge) }
    /** Existing original response DATA only. No regeneration, re-signing or deadline renewal. */
    public suspend fun recoverPossession(originalPairingActionId: DomainId): AndroidDevicePairingTargetResult<LocalDevicePairingPossession> =
        run { native.recoverPossession(originalPairingActionId) }
    /** Explicit import for the already native-signed exact original C/P. The original
     * root document and endorsement must match that selection; no alternate trust anchor,
     * new signature/ID/deadline, replacement, admission or networking grant is accepted.
     * Bounded input copies remain owned until the actual work drains, even on cancellation. */
    public suspend fun importApprovedMaster(approvalCertificate: ByteArray, rootAnnouncement: ByteArray):
        AndroidDevicePairingTargetResult<LocalDevicePairingApprovedMaster> {
        val result = work.run {
            require(approvalCertificate.size in 1..AndroidDevicePairingTargetNative.APPROVAL_CERTIFICATE_MAX &&
                rootAnnouncement.size in 1..SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES)
            val certificate = approvalCertificate.copyOf()
            var copiedRoot: ByteArray? = null
            try {
                val root = rootAnnouncement.copyOf(); copiedRoot = root
                object : AndroidOwnedTask<LocalDevicePairingApprovedMaster> {
                    override fun execute(): LocalDevicePairingApprovedMaster {
                        if (denied.get()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
                        native.requireReadable()
                        val value = native.importApprovedMaster(certificate, root)
                        native.requireReadable()
                        if (denied.get()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
                        return value
                    }
                    override fun discard(value: LocalDevicePairingApprovedMaster) = Unit
                    override fun clearInputs() { certificate.fill(0); root.fill(0) }
                }
            } catch (failure: Throwable) { certificate.fill(0); copiedRoot?.fill(0); throw failure }
        }
        return when (result) {
            is AndroidOwnedResult.Value -> AndroidDevicePairingTargetResult.Value(result.value)
            is AndroidOwnedResult.Unavailable -> AndroidDevicePairingTargetResult.Unavailable(result.reason)
        }
    }
    /** Existing immutable approved-Master identification only. Revalidates the exact
     * native C/P and both original root signatures; never reconstructs a Kit, captures,
     * signs, imports missing evidence, repairs a partial write or renews a deadline. */
    public suspend fun recoverApprovedMaster(originalPairingActionId: DomainId):
        AndroidDevicePairingTargetResult<LocalDevicePairingApprovedMaster> = run { native.recoverApprovedMaster(originalPairingActionId) }
    /** Explicit protected storage acquisition on THIS actual approved original, before RPC
     * consumes/closes this source. A returned DATA readback cannot create this capability.
     * ONE retained issue per source lifetime; lost/uncertain issuance is never reissued. */
    public suspend fun acquireCredentialJournalCustody(originalPairingActionId: DomainId,
        action: LocalPairedDeviceCredentialJournalAction):
        AndroidDevicePairingTargetResult<AndroidPairedDeviceCredentialJournalCustody> {
        currentCoroutineContext().ensureActive()
        if (denied.get()) return AndroidDevicePairingTargetResult.Unavailable(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        val issue = CredentialCustodyIssue()
        if (!credentialCustodyIssue.compareAndSet(null, issue))
            return AndroidDevicePairingTargetResult.Unavailable(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        var prepared = false
        try {
            return when (val result = work.run {
                object : AndroidOwnedTask<AndroidPairedDeviceCredentialJournalCustody> {
                    override fun execute(): AndroidPairedDeviceCredentialJournalCustody {
                        if (denied.get()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
                        native.requireReadable()
                        val value = native.acquireCredentialJournalCustody(this@AndroidDevicePairingTarget,
                            originalPairingActionId, action, issue.settled)
                        // Retain the actual capability before post-check/delivery can fail.
                        issue.custody.set(value)
                        native.requireReadable()
                        if (denied.get()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
                        return value
                    }
                    override fun discard(value: AndroidPairedDeviceCredentialJournalCustody) { value.denyUndelivered() }
                    override fun clearInputs() { issue.settled.complete(Unit) }
                }.also { prepared = true }
            }) {
                is AndroidOwnedResult.Value -> AndroidDevicePairingTargetResult.Value(result.value)
                is AndroidOwnedResult.Unavailable -> AndroidDevicePairingTargetResult.Unavailable(result.reason)
            }
        } finally {
            // A pre-admission refusal owns no native task; an admitted task owns completion
            // even after waiter cancellation. Never let cancellation declare native drainage.
            if (!prepared) issue.settled.complete(Unit)
        }
    }

    internal fun credentialJournalCustodyCurrent(custody: AndroidPairedDeviceCredentialJournalCustody): Boolean {
        if (denied.get() || credentialCustodyIssue.get()?.custody?.get() !== custody) return false
        // Reuse the actual source's original process/clock/terminal-work fence, not a
        // manufactured positive current marker. Protected key/evidence IO stays owned.
        try { work.requireActive() } catch (_: Throwable) { return false }
        return !denied.get() && credentialCustodyIssue.get()?.custody?.get() === custody
    }

    internal suspend fun revalidateCredentialJournalCustody(custody: AndroidPairedDeviceCredentialJournalCustody,
        validation: AndroidPairedDeviceCredentialJournalCustody.NativeValidation): Boolean {
        try {
            return when (work.run {
                object : AndroidOwnedTask<Unit> {
                    override fun execute() {
                        if (!custody.isCurrent()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
                        native.requireReadable()
                        native.requireCredentialJournalCustody(custody)
                        native.requireReadable()
                        if (!custody.isCurrent()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
                    }
                    override fun discard(value: Unit) = Unit
                    override fun clearInputs() { validation.finish() }
                }.also { validation.prepared = true }
            }) {
                is AndroidOwnedResult.Value -> true
                is AndroidOwnedResult.Unavailable -> false
            }
        } finally { if (!validation.prepared) validation.finish() }
    }
    /** Explicit consumption of exact completed known-unsigned terminal DATA, never expiry.
     * Keeps this key/descriptor and every old response; appends one new response allocation.
     * An unknown allocation/response signature is not retireable or repeatable. */
    public suspend fun beginAfterKnownUnsigned(retirement: LocalDevicePairingUnsignedRetirement):
        AndroidDevicePairingTargetResult<AndroidDevicePairingTargetReference> = run { native.beginAfterKnownUnsigned(retirement) }
    /** Existing completed current allocation DATA, correlated to THIS exact retirement.
     * Never allocates, signs, reconstructs a Kit or resets an uncertain response. */
    public suspend fun recoverAfterKnownUnsigned(retirement: LocalDevicePairingUnsignedRetirement):
        AndroidDevicePairingTargetResult<AndroidDevicePairingTargetReference> = run { native.recoverAfterKnownUnsigned(retirement) }
    /** Immediate work fence. Actual owned work still drains through awaited close. */
    public fun denyNewWork() { denied.set(true) }
    public suspend fun close() {
        denyNewWork()
        work.requestClose { native.closeNative() }.await()
    }
    private suspend fun <T> run(body: () -> T): AndroidDevicePairingTargetResult<T> = when (val result = work.run {
        object : AndroidOwnedTask<T> {
            override fun execute(): T {
                if (denied.get()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
                native.requireReadable()
                val result = body()
                native.requireReadable()
                if (denied.get()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
                return result
            }
            override fun discard(value: T) = Unit
            override fun clearInputs() = Unit
        }
    }) {
        is AndroidOwnedResult.Value -> AndroidDevicePairingTargetResult.Value(result.value)
        is AndroidOwnedResult.Unavailable -> AndroidDevicePairingTargetResult.Unavailable(result.reason)
    }
    override fun toString(): String = "AndroidDevicePairingTarget([REDACTED])"
    public companion object {
        /** Explicit FIRST preparation only. IDs/nonce/STARTED are forced before generation.
         * Scope/capabilities describe requested DATA, not ownership or permission grants. */
        public suspend fun prepareNew(application: Application, scope: BranchScope,
            capabilities: Set<String> = emptySet()): AndroidDevicePairingTargetOpenResult =
            acquire(application, scope, capabilities, SelfOwnedNativeProfileV1.LIVE)
        /** Fixed training-slot preparation; neither a caller-selected path nor a live namespace. */
        public suspend fun prepareNewForTraining(application: Application, scope: BranchScope,
            slot: LocalTrainingCompositionSlot,
            capabilities: Set<String> = emptySet()): AndroidDevicePairingTargetOpenResult =
            acquire(application, scope, capabilities, SelfOwnedNativeProfileV1.training(slot))
        /** Existing-only, including partial original read/review. Never creates missing
         * directories/locks/keys, adopts orphan aliases, or retries UNKNOWN generation/sign. */
        public suspend fun openExisting(application: Application): AndroidDevicePairingTargetOpenResult =
            acquire(application, null, emptySet(), SelfOwnedNativeProfileV1.LIVE)
        /** Existing-only training-slot target; a different slot/mode directory is never adopted. */
        public suspend fun openExistingForTraining(application: Application,
            slot: LocalTrainingCompositionSlot): AndroidDevicePairingTargetOpenResult =
            acquire(application, null, emptySet(), SelfOwnedNativeProfileV1.training(slot))
        private suspend fun acquire(application: Application, scope: BranchScope?, capabilities: Set<String>,
            profile: SelfOwnedNativeProfileV1): AndroidDevicePairingTargetOpenResult {
            val lease = AndroidProvisionerProcessLease.acquire(profile)
                ?: return AndroidDevicePairingTargetOpenResult.Unresolved(SelfOwnedInstallationFailureV1.BUSY)
            val work = try { AndroidOwnedInstallationWork(lease) } catch (_: Throwable) {
                lease.releaseUnusedBeforeNativeOpen()
                return AndroidDevicePairingTargetOpenResult.Unresolved(SelfOwnedInstallationFailureV1.UNAVAILABLE)
            }
            val retained = AtomicReference<AndroidDevicePairingTargetNative?>(null)
            var delivered = false
            try {
                val native = AndroidDevicePairingTargetNative(application, work, profile)
                retained.set(native) // Partial resource owner is retained BEFORE native IO.
                return when (val result = work.run {
                    val copied = capabilities.toList().sorted()
                    if (copied.size > ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITIES || copied.distinct().size != copied.size ||
                        copied.any { code -> code.length !in 1..ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITY_BYTES ||
                            code.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it !in "._-" } })
                        selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
                    object : AndroidOwnedTask<AndroidDevicePairingTargetNative> {
                        override fun execute(): AndroidDevicePairingTargetNative {
                            if (scope == null) native.openExistingNative() else native.prepareNewNative(scope, copied)
                            return native
                        }
                        override fun discard(value: AndroidDevicePairingTargetNative) { value.closeNative() }
                        override fun clearInputs() = Unit
                    }
                }) {
                    is AndroidOwnedResult.Value -> {
                        val target = AndroidDevicePairingTarget(result.value, work)
                        val opened = AndroidDevicePairingTargetOpenResult.Opened(target)
                        delivered = true
                        opened
                    }
                    is AndroidOwnedResult.Unavailable -> AndroidDevicePairingTargetOpenResult.Unresolved(result.reason, native.lastKnownOriginalNoIo())
                }
            } finally {
                if (!delivered) work.requestClose { retained.get()?.closeNative() }
            }
        }
    }
}

/** Dedicated directory/alias and this actual process lease/watchdog; no second executor.
 * Immutable forced files form a one-shot sequence, not a repairing/replacement registry.
 * An unfinished intent is reviewable DATA but never permission to try its sign again. */
private class AndroidDevicePairingTargetNative(private val application: Application,
    private val work: AndroidOwnedInstallationWork, private val profile: SelfOwnedNativeProfileV1) {
    val compositionProfile: LocalCompositionSdkProfileV1 get() = profile.sdkProfile
    private enum class Phase { NEW, OPENING, OPEN, CLOSE_STARTED, CLOSED }
    private var phase = Phase.NEW
    private var environment: AndroidSelfOwnedEnvironment? = null
    private var directory: File? = null
    private val descriptors = mutableListOf<OwnedDescriptor>()
    private val ancestors = linkedMapOf<File, Stamp>()
    private var dataDescriptor: OwnedDescriptor? = null
    private var parentDescriptor: OwnedDescriptor? = null
    private var directoryDescriptor: OwnedDescriptor? = null
    private var directoryStamp: Stamp? = null
    private var lockDescriptor: OwnedDescriptor? = null
    private var lockChannel: FileChannel? = null
    private var lock: FileLock? = null
    private var lockStamp: Stamp? = null
    private var volumeUuid: String? = null
    private var store: KeyStore? = null
    private val snapshots = linkedMapOf<String, Snapshot?>().apply {
        (FILE_ROLES.take(5) + SDK_ROLES + APPROVED_MASTER_ROLES + CREDENTIAL_ROLES).forEach { put(it, null) }
    }
    private var identity: TargetIdentity? = null
    private var keyIntent: TargetKeyIntent? = null
    private var ready: TargetReady? = null
    private var sdkAcquisition: TargetSdkAcquisition? = null
    private var sdkSigningIntent: TargetSdkSigningIntent? = null
    private var sdkReady: TargetSdkReady? = null
    private var sdkOwner: AndroidDevicePairingSdkOwnerV2? = null
    private var sdkOwnerRejected = false
    private var preparedHere = false
    private var credentialAllocation: NativePairedDeviceCredentialJournalRecord? = null
    private val slots = linkedMapOf(0 to TargetResponseSlot())
    private var responseCycle = 0
    private val verifiedAllocationCycles = mutableSetOf<Int>()
    private fun slot(): TargetResponseSlot = checkNotNull(slots[responseCycle])
    private var responseIntent: TargetResponseIntent?
        get() = slot().responseIntent
        set(value) { slot().responseIntent = value }
    private var complete: TargetComplete?
        get() = slot().complete
        set(value) { slot().complete = value }
    private fun responseIntentName(index: Int = responseCycle): String = responseRole(index, RESPONSE_INTENT)
    private fun completeName(index: Int = responseCycle): String = responseRole(index, COMPLETE)
    private fun allocationIntentName(index: Int = responseCycle): String = responseRole(index, ALLOCATION_INTENT)
    private fun allocationReadyName(index: Int = responseCycle): String = responseRole(index, ALLOCATION_READY)
    private fun approvedMasterImportIntentName(index: Int = responseCycle): String = responseRole(index, APPROVED_MASTER_IMPORT_INTENT)
    private fun approvedMasterEvidenceName(index: Int = responseCycle): String = responseRole(index, APPROVED_MASTER_EVIDENCE)
    private fun allocationBytes(index: Int = responseCycle): ByteArray =
        if (index == 0) snapshotBytes(IDENTITY) else snapshotBytes(allocationReadyName(index))
    private fun allocatedResponseId(): DomainId = slot().allocation?.responseId ?: checkNotNull(identity).responseId
    private fun allocatedNonce(): SelfOwnedOctetsV1 = slot().allocation?.nonce ?: checkNotNull(identity).nonce
    @Volatile private var lastKnown: AndroidDevicePairingTargetReference? = null

    fun lastKnownOriginalNoIo(): AndroidDevicePairingTargetReference? = lastKnown
    fun prepareNewNative(scope: BranchScope, capabilities: List<String>) {
        openNamespace(creating = true)
        assertAliasNamespace()
        val forbidden = setOf(scope.organizationId, scope.companyId, scope.branchId)
        requireAppendBytes(IDENTITY_MAX.toLong() + KEY_INTENT_MAX + READY_MAX + 8192L)
        val ids = List(4) { AndroidP256Primitives.newId() }
        known(ids.distinct().size == ids.size && ids.none { it in forbidden })
        val nonce = AndroidP256Primitives.randomBytes(32)
        val original = try { TargetIdentity(ids[0], ids[1], ids[2], ids[3], scope, capabilities.toList(),
            env().target, capturedNamespace(), env().bootCount, work.clock(), SelfOwnedOctetsV1(nonce, 32)) }
        finally { nonce.fill(0) }
        identity = original; updateReference(AndroidDevicePairingTargetPhase.KEY_GENERATION_INTENT)
        val bytes = TargetWire.identity(original)
        try { forceNew(IDENTITY, bytes) } finally { bytes.fill(0) }
        assertAliasNamespace()
        known(keyIntent == null && ready == null && !keyStore().containsAlias(alias()))
        // Exact original forced STARTED precedes the only native generation. Any exception,
        // cancellation/lost output or partial file leaves the same IDs, never another attempt.
        known(original.osBoot.ordinal == env().bootCount)
        val builder = KeyGenParameterSpec.Builder(alias(), KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(256).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
        AndroidNativeKeyInfo.configureService(builder, SelfOwnedKeyProtectionV1.OS_ISOLATED)
        try { KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply { initialize(builder.build()) }.generateKeyPair() }
        catch (_: Exception) { keyFailure() }
        assertContinuity(); assertAliasNamespace()
        val key = loadNativeKey(); val security = inspectNativeKey(key); val point = nativePoint()
        try {
            val descriptor = LocalDevicePairingTargetDescriptor.capture(LocalDeviceAdmission(scope, original.deviceId,
                Revision(0), true, original.keyId.value, sha256(point).value, capabilities.toSet()), original.keyId, point)
            val intent = TargetKeyIntent(identityDigest(), env().bootCount, work.clock(), security, SelfOwnedOctetsV1(point, 65))
            keyIntent = intent; updateReference(AndroidDevicePairingTargetPhase.KEY_METADATA_SIGNING_INTENT)
            val intentBytes = TargetWire.keyIntent(intent)
            try { forceNew(KEY_INTENT, intentBytes) } finally { intentBytes.fill(0) }
            val descriptorBytes = descriptor.copyWireBytes()
            val message = try { TargetWire.readyMessage(snapshotBytes(IDENTITY), snapshotBytes(KEY_INTENT), descriptorBytes) }
            finally { descriptorBytes.fill(0) }
            val signature = try { AndroidP256Primitives.sign(checkedNativeKey(), message) }
            catch (failure: Throwable) { message.fill(0); throw failure }
            try {
                checkedNativeKey()
                known(AndroidP256Primitives.verify(point, message, signature))
                val signed = TargetReady(SelfOwnedOctetsV1(message, READY_MAX), SelfOwnedOctetsV1(signature, 64))
                val encoded = TargetWire.ready(signed)
                try { forceNew(READY, encoded) } finally { encoded.fill(0) }
                ready = signed; validateReady(); updateReference(AndroidDevicePairingTargetPhase.KEY_READY)
            } finally { message.fill(0); signature.fill(0) }
        } finally { point.fill(0) }
        requireReadable()
        preparedHere = true // Only actual successful prepareNew; existing-only never sets this.
    }

    fun openExistingNative() {
        openNamespace(creating = false)
        // Read the canonical original (or exact pending original DATA) before refusing a
        // pending/unknown write. No pending file is adopted, renamed, removed or repaired.
        val identitySnapshot = snapshots[IDENTITY]
        if (identitySnapshot == null) {
            val pending = readSnapshot("$IDENTITY.pending", IDENTITY_MAX)
            try { pending?.let { candidate ->
                try { identity = TargetWire.decodeIdentity(candidate.bytes); updateReference(AndroidDevicePairingTargetPhase.KEY_GENERATION_INTENT) }
                catch (_: Exception) { /* Incomplete canonical DATA cannot identify an original. */ }
            } } finally { pending?.clear() }
            known(false)
        }
        identity = TargetWire.decodeIdentity(checkNotNull(identitySnapshot).bytes)
        updateReference(AndroidDevicePairingTargetPhase.KEY_GENERATION_INTENT)
        credentialAllocation = snapshots[CREDENTIAL_ALLOCATION]?.let {
            NativePairedDeviceCredentialJournalWire.decodeRecord(it.bytes)
        }
        sdkAcquisition = snapshots[SDK_ACQUISITION]?.let { TargetSdkWireV2.decodeAcquisition(it.bytes) }
        sdkAcquisition?.let { updateReference(AndroidDevicePairingTargetPhase.SDK_ACQUISITION_INTENT) }
        if (sdkAcquisition == null) {
            val pending = readSnapshot("$SDK_ACQUISITION.pending", SDK_ACQUISITION_MAX)
            try { pending?.let { candidate ->
                try { sdkAcquisition = TargetSdkWireV2.decodeAcquisition(candidate.bytes)
                    updateReference(AndroidDevicePairingTargetPhase.SDK_ACQUISITION_INTENT) }
                catch (_: Exception) { /* Retained pending DATA is not adopted or repaired. */ }
            } } finally { pending?.clear() }
        }
        checkNames()
        validateIdentity()
        keyIntent = snapshots[KEY_INTENT]?.let { TargetWire.decodeKeyIntent(it.bytes) }
        keyIntent?.let { validateKeyIntent(); updateReference(AndroidDevicePairingTargetPhase.KEY_METADATA_SIGNING_INTENT) }
        ready = snapshots[READY]?.let { TargetWire.decodeReady(it.bytes) }
        ready?.let { validateReady(); updateReference(AndroidDevicePairingTargetPhase.KEY_READY) }
        sdkAcquisition?.let { validateSdkAcquisition(); updateReference(AndroidDevicePairingTargetPhase.SDK_ACQUISITION_INTENT) }
        sdkSigningIntent = snapshots[SDK_SIGNING_INTENT]?.let { TargetSdkWireV2.decodeSigningIntent(it.bytes) }
        sdkSigningIntent?.let { validateSdkSigningIntent(); updateReference(AndroidDevicePairingTargetPhase.SDK_METADATA_SIGNING_INTENT) }
        sdkReady = snapshots[SDK_READY]?.let { TargetSdkWireV2.decodeReady(it.bytes) }
        sdkReady?.let { validateSdkReady(); updateReference(AndroidDevicePairingTargetPhase.SDK_READY) }
        known(sdkAcquisition != null || sdkSigningIntent == null && sdkReady == null)
        known(sdkSigningIntent != null || sdkReady == null)
        for (index in 0 until MAXIMUM_RESPONSE_CYCLES) {
            val allocation = if (index == 0) null else snapshots[allocationIntentName(index)]
            val allocated = if (index == 0) null else snapshots[allocationReadyName(index)]
            val intent = snapshots[responseIntentName(index)]; val completed = snapshots[completeName(index)]
            val masterImport = snapshots[approvedMasterImportIntentName(index)]
            val masterEvidence = snapshots[approvedMasterEvidenceName(index)]
            if (index > 0 && listOf(allocation, allocated, intent, completed, masterImport, masterEvidence).all { it == null }) continue
            known(index == 0 || index == slots.keys.last() + 1)
            if (index > 0) slots[index] = TargetResponseSlot()
            responseCycle = index
            if (index > 0) {
                slot().allocation = allocation?.let { TargetWire.decodeAllocation(it.bytes) }
                updateReference(AndroidDevicePairingTargetPhase.RESPONSE_ALLOCATION_SIGNING_INTENT)
                validateAllocationIntent()
                slot().allocationReady = allocated?.let { TargetWire.decodeAllocationReady(it.bytes) }
                slot().allocationReady?.let { validateAllocationReady(); updateReference(AndroidDevicePairingTargetPhase.RESPONSE_ALLOCATION_READY) }
                known(slot().allocationReady != null || intent == null && completed == null && masterImport == null && masterEvidence == null)
            }
            responseIntent = intent?.let { TargetWire.decodeResponseIntent(it.bytes) }
            responseIntent?.let { validateResponseIntent(); updateReference(AndroidDevicePairingTargetPhase.RESPONSE_SIGNING_INTENT) }
            complete = completed?.let { TargetWire.decodeComplete(it.bytes) }
            complete?.let { validateComplete(); updateReference(AndroidDevicePairingTargetPhase.RESPONSE_COMPLETED) }
            slot().approvedMasterImportIntent = masterImport?.let { TargetApprovedMasterWireV1.decodeImportIntent(it.bytes) }
            slot().approvedMasterImportIntent?.let { validateApprovedMasterImportIntent() }
            known(slot().approvedMasterImportIntent != null || masterEvidence == null)
            slot().approvedMasterEvidence = masterEvidence?.let { TargetApprovedMasterWireV1.decodeEvidence(it.bytes) }
            slot().approvedMasterEvidence?.let { validateApprovedMaster() }
        }
        if (sdkAcquisition != null && sdkReady == null) updateReference(if (sdkSigningIntent == null)
            AndroidDevicePairingTargetPhase.SDK_ACQUISITION_INTENT else AndroidDevicePairingTargetPhase.SDK_METADATA_SIGNING_INTENT)
        assertAliasNamespace()
        requireReadable() // Partial original owners expose only DATA/review, not descriptor/sign.
        credentialAllocation?.let { requireCredentialAllocation(it) }
    }

    fun original(): AndroidDevicePairingTargetReference { requireReadable(); return checkNotNull(lastKnown) }
    fun descriptor(): LocalDevicePairingTargetDescriptor {
        requireReadable(); validateReady()
        val value = TargetWire.decodeReadyMessage(checkNotNull(ready).message).descriptor
        val bytes = value.copyBytes()
        return try { LocalDevicePairingTargetDescriptor.decode(bytes) } finally { bytes.fill(0) }
    }
    fun acquireSdkIdentity(appId: AppId, deviceName: String): LocalDevicePairingSdkIdentityV2 {
        requireReadable(); validateReady(); requireAllocationReady()
        try { AndroidDevicePairingSdkOwnerV2.validateAcquisitionText(appId.value)
            AndroidDevicePairingSdkOwnerV2.validateAcquisitionText(deviceName) }
        catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
        sdkAcquisition?.let { retained ->
            if (retained.appId != appId || retained.deviceName != deviceName) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            if (sdkReady == null) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            return recoverSdkIdentity() // Completed same original DATA, never another Kit/sign.
        }
        // A response for an old definition must first reach an explicit known-unsigned
        // terminal and a separately retained append allocation. Never extend its approval.
        if (responseIntent != null || complete != null) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        known(sdkSigningIntent == null && sdkReady == null && sdkOwner == null && !sdkOwnerRejected)
        val originalScope = checkNotNull(identity).scope
        if (setOf(originalScope.organizationId, originalScope.companyId, originalScope.branchId).size != 3)
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        // New acquisitions only. Exact completed historical inputs above remain DATA;
        // an incompatible old namespace is never relabelled, repaired or reacquired.
        requireSdkBranchNamespaceForNewWork(appId, SelfOwnedInstallationFailureV1.INVALID_INPUT)
        requireAppendBytes(SDK_ACQUISITION_MAX.toLong() + SDK_SIGNING_INTENT_MAX + SDK_READY_MAX + 8192L)
        val acquisitionId = AndroidP256Primitives.newId()
        known(acquisitionId !in retainedTargetIds(responseCycle))
        val nonce = AndroidP256Primitives.randomBytes(32)
        val acquisition = try { TargetSdkAcquisition(acquisitionId, identityDigest(), readyDigest(), appId, deviceName,
            env().bootCount, work.clock(), SelfOwnedOctetsV1(nonce, 32)) }
        finally { nonce.fill(0) }
        sdkAcquisition = acquisition; updateReference(AndroidDevicePairingTargetPhase.SDK_ACQUISITION_INTENT)
        val acquisitionBytes = TargetSdkWireV2.acquisition(acquisition)
        try { forceNew(SDK_ACQUISITION, acquisitionBytes) } finally { acquisitionBytes.fill(0) }
        validateSdkAcquisition()
        known(acquisition.osBoot.ordinal == env().bootCount && work.clock() >= acquisition.observedElapsed)
        val owned = AndroidDevicePairingSdkOwnerV2(application, work)
        sdkOwner = owned // Actual partial owner retained BEFORE SDK storage/construction work.
        val sdk = try {
            owned.createNative(appId, deviceName)
            owned.captureNative(acquisitionId, checkNotNull(identity).preparationId, descriptorWithoutReentry(), env().target)
        } catch (failure: Throwable) { closeRejectedSdkOwner(); throw failure }
        val descriptorBytes = sdk.copyWireBytes()
        val intent = try { TargetSdkSigningIntent(identityDigest(), readyDigest(), sha256(snapshotBytes(SDK_ACQUISITION)),
            env().bootCount, work.clock(), SelfOwnedOctetsV1(descriptorBytes, LocalDevicePairingProfileV2.MAXIMUM_SDK_IDENTITY_BYTES)) }
        finally { descriptorBytes.fill(0) }
        val intentBytes = TargetSdkWireV2.signingIntent(intent)
        try {
            val completionBound = snapshotBytes(IDENTITY).size.toLong() + snapshotBytes(READY).size.toLong() +
                snapshotBytes(SDK_ACQUISITION).size.toLong() + intentBytes.size.toLong() + 2048L
            if (completionBound > SDK_READY_MAX) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
            requireAppendBytes(intentBytes.size.toLong() + completionBound)
            sdkSigningIntent = intent; updateReference(AndroidDevicePairingTargetPhase.SDK_METADATA_SIGNING_INTENT)
            forceNew(SDK_SIGNING_INTENT, intentBytes)
        } finally { intentBytes.fill(0) }
        validateSdkSigningIntent(); requireCurrentSdkMatches()
        known(intent.osBoot.ordinal == env().bootCount && work.clock() >= intent.observedElapsed)
        val message = TargetSdkWireV2.readyMessage(snapshotBytes(IDENTITY), snapshotBytes(READY),
            snapshotBytes(SDK_ACQUISITION), snapshotBytes(SDK_SIGNING_INTENT))
        val signature = try { AndroidP256Primitives.sign(checkedNativeKey(), message) }
        catch (failure: Throwable) { message.fill(0); throw failure }
        try {
            checkedNativeKey(); validateSdkSigningIntent(); requireCurrentSdkMatches()
            val point = nativePoint()
            try { known(AndroidP256Primitives.verify(point, message, signature)) } finally { point.fill(0) }
            val signed = TargetSdkReady(SelfOwnedOctetsV1(message, SDK_READY_MAX - 1024), SelfOwnedOctetsV1(signature, 64))
            val encoded = TargetSdkWireV2.ready(signed)
            try { forceNew(SDK_READY, encoded) } finally { encoded.fill(0) }
            sdkReady = signed; validateSdkReady(); requireCurrentSdkMatches()
            updateReference(AndroidDevicePairingTargetPhase.SDK_READY)
            return sdkIdentityWithoutReentry()
        } finally { message.fill(0); signature.fill(0) }
    }
    fun recoverSdkIdentity(): LocalDevicePairingSdkIdentityV2 = recoverSdkOriginal().identity
    fun recoverSdkOriginal(): LocalDevicePairingSdkOriginal {
        requireReadable(); validateSdkReady()
        // DATA only; no SDK reconstruction or new signature.
        return LocalDevicePairingSdkOriginal(sdkIdentityWithoutReentry(), checkNotNull(sdkAcquisition).deviceName)
    }
    fun respond(challenge: LocalDevicePairingChallenge): LocalDevicePairingPossession {
        requireReadable(); validateReady(); requireAllocationReady(); requireOwnDescriptor(challenge)
        requireChallengeRetirementLink(challenge)
        val challengeBytes = challenge.copyWireBytes()
        try {
            complete?.let {
                val intent = checkNotNull(responseIntent)
                if (!intent.challenge.same(challengeBytes)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
                validateComplete()
                return completedPossession() // Exact original disclosure; no new capture/sign/ID.
            }
            if (responseIntent != null) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            requireSdkForNewResponse(challenge)
            val id = allocatedResponseId()
            known(id !in listOf(challenge.originalActionId, challenge.requested.request.requestId,
                challenge.requested.admission.deviceId, challenge.requested.target.keyId) +
                challenge.requested.sdkIdentity?.let { listOf(it.originalAcquisitionId, it.originalPreparationId) }.orEmpty() &&
                id !in challenge.body.origin.reservedOriginalIds())
            // Response ID/nonce are already forced in the original generation identity or
            // this separately explicit signed append allocation. A failed response intent
            // never allocates replacements or repeats a signature.
            val intent = TargetResponseIntent(id, responseCycle, sha256(allocationBytes()), readyDigest(),
                env().bootCount, work.clock(), allocatedNonce(),
                SelfOwnedOctetsV1(challengeBytes, LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES))
            val intentBytes = TargetWire.responseIntent(intent)
            try {
                // Complete signed snapshot adds one bounded original ready body/proof/root-free
                // framing. Actual byte-fit is reserved BEFORE signing; no truncated challenge.
                // Both immutable intent and full private completion snapshot are reserved
                // against actual retained directory bytes BEFORE either signature/force.
                val completionBound = snapshotBytes(READY).size.toLong() + allocationBytes().size.toLong() +
                    intentBytes.size.toLong() + LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES + 8192L
                requireAppendBytes(intentBytes.size.toLong() + completionBound)
                if (completionBound > COMPLETE_MAX) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
                responseIntent = intent; updateReference(AndroidDevicePairingTargetPhase.RESPONSE_SIGNING_INTENT)
                forceNew(responseIntentName(), intentBytes)
            } finally { intentBytes.fill(0) }
            validateResponseIntent()
            // The target's own boot/elapsed observations govern only its local key/work life.
            // Master boot IDs/deadlines are unsigned remote DATA, not comparable local clocks.
            known(intent.osBoot.ordinal == env().bootCount && work.clock() >= intent.observedElapsed)
            requireSdkForNewResponse(challenge)
            val message = NativeDevicePairingWireV1.possessionMessage(challenge)
            val signature = try { AndroidP256Primitives.sign(checkedNativeKey(), message) }
            catch (failure: Throwable) { message.fill(0); throw failure }
            try {
                checkedNativeKey(); validateResponseIntent(); requireSdkForNewResponse(challenge)
                val point = nativePoint()
                try { known(AndroidP256Primitives.verify(point, message, signature)) } finally { point.fill(0) }
                val proof = LocalDevicePairingPossession.capture(challenge, signature)
                val proofBytes = proof.copyWireBytes()
                val completionMessage = try { TargetWire.completeMessage(snapshotBytes(READY), allocationBytes(), snapshotBytes(responseIntentName()), proofBytes) }
                finally { proofBytes.fill(0) }
                // The SAME forced intent explicitly reserves both possession and final private
                // metadata authentication signatures. Restart/unknown work may perform neither.
                val snapshotSignature = try {
                    requireSdkForNewResponse(challenge)
                    AndroidP256Primitives.sign(checkedNativeKey(), completionMessage)
                }
                catch (failure: Throwable) { completionMessage.fill(0); throw failure }
                try {
                    checkedNativeKey(); validateResponseIntent(); requireSdkForNewResponse(challenge)
                    val signed = TargetComplete(SelfOwnedOctetsV1(completionMessage, COMPLETE_MAX - 1024), SelfOwnedOctetsV1(snapshotSignature, 64))
                    val encoded = TargetWire.complete(signed)
                    try { forceNew(completeName(), encoded) } finally { encoded.fill(0) }
                    complete = signed; validateComplete(); updateReference(AndroidDevicePairingTargetPhase.RESPONSE_COMPLETED)
                    return completedPossession()
                } finally { completionMessage.fill(0); snapshotSignature.fill(0) }
            } finally { message.fill(0); signature.fill(0) }
        } finally { challengeBytes.fill(0) }
    }
    fun recoverPossession(id: DomainId): LocalDevicePairingPossession {
        requireReadable()
        val index = slots.keys.singleOrNull { cycle -> withResponseCycle(cycle) {
            complete != null && completedPossession().originalActionId == id
        } } ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        return withResponseCycle(index) { validateComplete(); completedPossession() }
    }
    private fun completedPossession(): LocalDevicePairingPossession {
        val fields = TargetWire.decodeCompleteMessage(checkNotNull(complete).message)
        val bytes = fields.possession.copyBytes()
        return try { LocalDevicePairingPossession.decode(bytes) } finally { bytes.fill(0) }
    }

    fun importApprovedMaster(certificate: ByteArray, rootAnnouncement: ByteArray): LocalDevicePairingApprovedMaster {
        requireReadable()
        // This try covers pure bounded DATA decoding only, never native/storage operations.
        val record = try { NativeDevicePairingWireV1.decodeApprovedCertificate(certificate) }
        catch (_: Exception) { selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
        if (record.body.requested().masterSdkOriginal == null) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireApprovedMasterProfile(record)
        val index = responseCycleForAction(record.body.originalActionId)
        return withResponseCycle(index) {
            slot().approvedMasterEvidence?.let { evidence ->
                val original = validateApprovedMaster()
                val retainedCertificate = NativeDevicePairingWireV1.certificate(approvedMasterRecord(evidence))
                try {
                    if (!retainedCertificate.contentEquals(certificate) || !evidence.rootAnnouncement.same(rootAnnouncement))
                        selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
                } finally { retainedCertificate.fill(0) }
                return@withResponseCycle original // Existing-only; no capture, force, signature or replacement.
            }
            // A known first import never resumes its missing final write, even for equal inputs.
            if (slot().approvedMasterImportIntent != null) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            if (slots.keys.any { it > index }) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            validateComplete(); validateSdkReady()
            val challenge = retainedChallenge(); val possession = completedPossession()
            val check = try { NativeDevicePairingApprovedMasterPolicyV1.prepare(record, rootAnnouncement, challenge, possession) }
            catch (_: Exception) { selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT) }
            verifyApprovedMasterSignatures(check, SelfOwnedInstallationFailureV1.CONFLICT)
            checkedNativeKey() // Actual original key/storage continuity, never a proof re-signature.
            val importIntent = TargetApprovedMasterImportIntent(challenge.originalActionId, responseCycle,
                allocatedResponseId(), sha256(snapshotBytes(responseIntentName())), sha256(snapshotBytes(completeName())),
                check.identity.approvalCertificateDigest, check.identity.rootDocumentDigest)
            val intentBytes = TargetApprovedMasterWireV1.importIntent(importIntent)
            try {
                val evidence = TargetApprovedMasterEvidence(sha256(intentBytes), checkNotNull(record.consent),
                    checkNotNull(record.approvedHead), record.rootEndorsement,
                    SelfOwnedOctetsV1(rootAnnouncement, SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES))
                val evidenceBytes = TargetApprovedMasterWireV1.evidence(evidence)
                try {
                    // Both actual bounded files are reserved BEFORE the first immutable effect.
                    // Neither duplicates the already signed full C/P or raises total capacity.
                    requireAppendBytes(intentBytes.size.toLong() + evidenceBytes.size.toLong())
                    slot().approvedMasterImportIntent = importIntent
                    forceNew(approvedMasterImportIntentName(), intentBytes)
                    validateApprovedMasterImportIntent()
                    slot().approvedMasterEvidence = evidence
                    forceNew(approvedMasterEvidenceName(), evidenceBytes)
                } finally { evidenceBytes.fill(0) }
            } finally { intentBytes.fill(0) }
            validateApprovedMaster() // Construct qualified readback only after exact immutable readback.
        }
    }
    fun recoverApprovedMaster(originalPairingActionId: DomainId): LocalDevicePairingApprovedMaster {
        requireReadable()
        return withResponseCycle(responseCycleForAction(originalPairingActionId)) { validateApprovedMaster() }
    }
    fun acquireCredentialJournalCustody(target: AndroidDevicePairingTarget, originalPairingActionId: DomainId,
        action: LocalPairedDeviceCredentialJournalAction, issueSettled: CompletableDeferred<Unit>):
        AndroidPairedDeviceCredentialJournalCustody {
        requireReadable()
        val approved = recoverApprovedMaster(originalPairingActionId)
        val sdk = recoverSdkOriginal()
        val original = checkNotNull(identity)
        known(approved.scope == original.scope && approved.targetDeviceId == original.deviceId &&
            approved.targetKeyId == original.keyId && sdk.identity.originalPreparationId == original.preparationId &&
            sdk.identity.descriptorDigest == approved.targetSdkIdentity.descriptorDigest)
        checkedNativeKey() // Genuine original OS-isolated target custody, no stronger claim.
        val expected = NativePairedDeviceCredentialJournalWire.allocation(approved, sdk, identityDigest(), readyDigest(),
            SelfOwnedKeyProtectionV1.OS_ISOLATED)
        known(expected.journalId !in retainedTargetIds(responseCycle) && expected.keyId !in retainedTargetIds(responseCycle))
        if (action == LocalPairedDeviceCredentialJournalAction.ENROLL) {
            // Absence is NOT pristine proof. A reopened/legacy target cannot infer a first
            // credential history after losing allocation/key/files; it remains unavailable.
            if (!preparedHere) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            if (credentialAllocation != null || snapshots[CREDENTIAL_ALLOCATION] != null)
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            val bytes = NativePairedDeviceCredentialJournalWire.record(expected)
            try {
                requireAppendBytes(bytes.size.toLong())
                credentialAllocation = expected // Consume before immutable native effect.
                forceNew(CREDENTIAL_ALLOCATION, bytes)
            } finally { bytes.fill(0) }
        } else {
            if (credentialAllocation == null) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            known(credentialAllocation == expected)
        }
        requireCredentialAllocation(expected)
        return AndroidPairedDeviceCredentialJournalCustody(application, target, action, approved, sdk, expected, issueSettled)
    }
    fun requireCredentialJournalCustody(custody: AndroidPairedDeviceCredentialJournalCustody) {
        known(custody.belongsTo(application) && custody.originalOpenClaimed())
        requireCredentialAllocation(custody.originalRecord)
    }
    private fun requireCredentialAllocation(expected: NativePairedDeviceCredentialJournalRecord) {
        requireReadable()
        val encoded = NativePairedDeviceCredentialJournalWire.record(expected)
        try { known(credentialAllocation == expected && snapshots[CREDENTIAL_ALLOCATION]?.bytes?.contentEquals(encoded) == true) }
        finally { encoded.fill(0) }
        val approved = recoverApprovedMaster(expected.originalPairingActionId)
        val sdk = recoverSdkOriginal()
        known(NativePairedDeviceCredentialJournalWire.allocation(approved, sdk, identityDigest(), readyDigest(),
            SelfOwnedKeyProtectionV1.OS_ISOLATED) == expected)
        checkedNativeKey()
    }
    private fun responseCycleForAction(id: DomainId): Int = slots.keys.singleOrNull { index -> withResponseCycle(index) {
        complete != null && retainedChallenge().originalActionId == id
    } } ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    private fun retainedChallenge(): LocalDevicePairingChallenge {
        val bytes = checkNotNull(responseIntent).challenge.copyBytes()
        return try { LocalDevicePairingChallenge.decode(bytes) } finally { bytes.fill(0) }
    }
    private fun validateApprovedMasterImportIntent() {
        validateComplete(); validateSdkReady()
        val intent = slot().approvedMasterImportIntent ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val encoded = TargetApprovedMasterWireV1.importIntent(intent)
        try { known(snapshots[approvedMasterImportIntentName()]?.bytes?.contentEquals(encoded) == true) }
        finally { encoded.fill(0) }
        val challenge = retainedChallenge()
        known(challenge.requested.masterSdkOriginal != null && intent.originalPairingActionId == challenge.originalActionId &&
            intent.allocationIndex == responseCycle && intent.responseId == allocatedResponseId() &&
            intent.responseIntentDigest == sha256(snapshotBytes(responseIntentName())) &&
            intent.completeDigest == sha256(snapshotBytes(completeName())) &&
            intent.rootAnnouncementDigest == challenge.body.origin.rootDocumentDigest)
    }
    private fun approvedMasterRecord(evidence: TargetApprovedMasterEvidence): NativeDevicePairingRecordV1 {
        val challenge = retainedChallenge(); val possession = completedPossession().copyWireBytes()
        return try { NativeDevicePairingRecordV1(challenge.body, LocalDevicePairingPhase.APPROVED,
            SelfOwnedOctetsV1(possession, LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES),
            evidence.consent, evidence.approvedHead, evidence.rootEndorsement) }
        finally { possession.fill(0) }
    }
    /** The target imports only a Master original of its own fixed composition profile. */
    private fun requireApprovedMasterProfile(record: NativeDevicePairingRecordV1) {
        val master = record.body.requested().masterSdkOriginal
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (master.compositionProfile != profile.sdkProfile)
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
    }
    private fun validateApprovedMaster(): LocalDevicePairingApprovedMaster {
        if (slot().approvedMasterImportIntent == null) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        validateApprovedMasterImportIntent()
        val evidence = slot().approvedMasterEvidence ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val encoded = TargetApprovedMasterWireV1.evidence(evidence)
        try { known(snapshots[approvedMasterEvidenceName()]?.bytes?.contentEquals(encoded) == true) }
        finally { encoded.fill(0) }
        known(evidence.importIntentDigest == sha256(snapshotBytes(approvedMasterImportIntentName())))
        val record = approvedMasterRecord(evidence)
        requireApprovedMasterProfile(record)
        val rootAnnouncement = evidence.rootAnnouncement.copyBytes()
        val check = try { NativeDevicePairingApprovedMasterPolicyV1.prepare(record, rootAnnouncement,
            retainedChallenge(), completedPossession()) } finally { rootAnnouncement.fill(0) }
        val intent = checkNotNull(slot().approvedMasterImportIntent)
        known(check.identity.approvalCertificateDigest == intent.approvalCertificateDigest &&
            check.identity.rootDocumentDigest == intent.rootAnnouncementDigest)
        verifyApprovedMasterSignatures(check, SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        checkedNativeKey()
        return LocalDevicePairingApprovedMaster(check.identity)
    }
    private fun verifyApprovedMasterSignatures(value: NativeDevicePairingApprovedMasterCheckV1,
        mismatch: SelfOwnedInstallationFailureV1) {
        val copies = ArrayList<ByteArray>(5)
        try {
            val point = value.rootPoint.copyBytes().also { copies.add(it) }
            val rootMessage = value.rootMessage.copyBytes().also { copies.add(it) }
            val rootSignature = value.rootSignature.copyBytes().also { copies.add(it) }
            val endorsementMessage = value.endorsementMessage.copyBytes().also { copies.add(it) }
            val endorsementSignature = value.endorsementSignature.copyBytes().also { copies.add(it) }
            if (!AndroidP256Primitives.verify(point, rootMessage, rootSignature) ||
                !AndroidP256Primitives.verify(point, endorsementMessage, endorsementSignature)) selfOwnedFail(mismatch)
        } finally { copies.forEach { it.fill(0) } }
    }

    /** Explicit append after exact completed terminal DATA. Every old snapshot is retained.
     * Unknown response/allocation work is never cleared, adopted, retried or reallocated. */
    fun beginAfterKnownUnsigned(proof: LocalDevicePairingUnsignedRetirement): AndroidDevicePairingTargetReference {
        requireReadable(); validateReady(); requireAllocationReady()
        requireNoApprovedMasterImportForRetirement(responseCycle)
        sdkAcquisition?.let {
            requireSdkBranchNamespaceForNewWork(it.appId)
            ensureOriginalSdkOwner()
        }
        val priorIndex = responseCycle
        if (priorIndex >= MAXIMUM_RESPONSE_CYCLES - 1) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        validateRetirementForPrior(proof, priorIndex)
        val bytes = proof.copyWireBytes()
        try {
            val intentBound = bytes.size.toLong() + 4096L
            val readyBound = snapshotBytes(READY).size.toLong() + intentBound + 4096L
            requireAppendBytes(intentBound + readyBound + 8192L)
            if (intentBound > ALLOCATION_INTENT_MAX || readyBound > ALLOCATION_READY_MAX)
                selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
            // Capacity and the exact known prior proof chain were checked BEFORE IDs/nonce.
            val forbidden = retainedTargetIds(priorIndex) + proof.record.nativeIds() + proof.record.body.origin.reservedOriginalIds()
            val id = AndroidP256Primitives.newId()
            known(id !in forbidden)
            val nonce = AndroidP256Primitives.randomBytes(32)
            val allocation = try { TargetResponseAllocation(priorIndex + 1, id, SelfOwnedOctetsV1(nonce, 32), identityDigest(),
                sha256(allocationBytes(priorIndex)), snapshots[responseIntentName(priorIndex)]?.bytes?.let { sha256(it) },
                snapshots[completeName(priorIndex)]?.bytes?.let { sha256(it) },
                SelfOwnedOctetsV1(bytes, LocalDevicePairingProfileV1.MAXIMUM_RETIREMENT_BYTES), env().bootCount, work.clock()) }
            finally { nonce.fill(0) }
            slots[allocation.index] = TargetResponseSlot(allocation = allocation); responseCycle = allocation.index
            updateReference(AndroidDevicePairingTargetPhase.RESPONSE_ALLOCATION_SIGNING_INTENT)
            val encoded = TargetWire.allocation(allocation)
            try { forceNew(allocationIntentName(), encoded) } finally { encoded.fill(0) }
            validateAllocationIntent()
            val message = TargetWire.allocationReadyMessage(snapshotBytes(READY), snapshotBytes(allocationIntentName()))
            val signature = try {
                if (sdkAcquisition != null) requireCurrentSdkMatches()
                AndroidP256Primitives.sign(checkedNativeKey(), message)
            }
            catch (failure: Throwable) { message.fill(0); throw failure }
            try {
                checkedNativeKey(); validateAllocationIntent()
                if (sdkAcquisition != null) requireCurrentSdkMatches()
                val point = nativePoint()
                try { known(AndroidP256Primitives.verify(point, message, signature)) } finally { point.fill(0) }
                val ready = TargetAllocationReady(SelfOwnedOctetsV1(message, ALLOCATION_READY_MAX - 1024), SelfOwnedOctetsV1(signature, 64))
                val completed = TargetWire.allocationReady(ready)
                try { forceNew(allocationReadyName(), completed) } finally { completed.fill(0) }
                slot().allocationReady = ready; validateAllocationReady()
                updateReference(AndroidDevicePairingTargetPhase.RESPONSE_ALLOCATION_READY)
                return checkNotNull(lastKnown)
            } finally { message.fill(0); signature.fill(0) }
        } finally { bytes.fill(0) }
    }
    /** Read only the CURRENT completed allocation for these exact terminal bytes. Older
     * allocations cannot replace the current one, and unknown work is never resumed here. */
    fun recoverAfterKnownUnsigned(proof: LocalDevicePairingUnsignedRetirement): AndroidDevicePairingTargetReference {
        requireReadable(); validateReady()
        if (responseCycle == 0) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val allocation = slot().allocation ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val bytes = proof.copyWireBytes()
        try {
            if (!allocation.retirement.same(bytes)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        } finally { bytes.fill(0) }
        // Rejoin the original identity, prior response and producer-root certificate chain
        // even when this lifetime already validated the allocation's ready signature.
        validateAllocationIntent(); validateAllocationReady()
        if (sdkAcquisition != null) validateSdkReady() // Original DATA only; no Kit construction.
        if (responseIntent != null && complete == null) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (complete != null) validateComplete()
        // Refresh only observed reference DATA after actual signed snapshot validation;
        // interruption may have preceded the original in-memory phase publication.
        updateReference(if (complete == null) AndroidDevicePairingTargetPhase.RESPONSE_ALLOCATION_READY
            else AndroidDevicePairingTargetPhase.RESPONSE_COMPLETED)
        return original()
    }
    private fun requireAppendBytes(additional: Long) {
        val retained = snapshots.values.filterNotNull().sumOf { it.bytes.size.toLong() }
        if (additional < 0 || retained > TOTAL_SNAPSHOT_MAX.toLong() - additional)
            selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
    }
    private fun <T> withResponseCycle(index: Int, body: () -> T): T {
        val previous = responseCycle
        return try { responseCycle = index; body() } finally { responseCycle = previous }
    }
    private fun allocationProof(index: Int = responseCycle): LocalDevicePairingUnsignedRetirement? {
        val retained = slots[index]?.allocation?.retirement ?: return null
        val bytes = retained.copyBytes()
        return try { LocalDevicePairingUnsignedRetirement.decode(bytes) } finally { bytes.fill(0) }
    }
    private fun retainedTargetIds(upTo: Int, includeSdk: Boolean = true): Set<DomainId> {
        val ids = checkNotNull(identity).let { mutableSetOf(it.preparationId, it.deviceId, it.keyId, it.responseId,
            it.scope.organizationId, it.scope.companyId, it.scope.branchId) }
        if (includeSdk) sdkAcquisition?.let { ids.add(it.acquisitionId) }
        for (index in slots.keys.filter { it <= upTo }) {
            slots[index]?.allocation?.responseId?.let { ids.add(it) }
            allocationProof(index)?.let {
                if (includeSdk) ids.addAll(it.record.nativeIds())
                else { ids.addAll(it.record.ownedIds()); ids.add(it.record.body.requested().admission.deviceId)
                    ids.add(it.record.body.requested().target.keyId) }
                ids.addAll(it.record.body.origin.reservedOriginalIds())
            }
            slots[index]?.responseIntent?.challenge?.let { retained ->
                val bytes = retained.copyBytes()
                val challenge = try { LocalDevicePairingChallenge.decode(bytes) } finally { bytes.fill(0) }
                ids.add(challenge.originalActionId); ids.add(challenge.requested.request.requestId)
                ids.addAll(challenge.body.origin.reservedOriginalIds())
            }
        }
        return ids
    }
    private fun requireAllocationReady() {
        if (responseCycle > 0) validateAllocationReady()
    }
    private fun requireChallengeRetirementLink(challenge: LocalDevicePairingChallenge) {
        val proof = allocationProof()
        known(challenge.body.previousUnsignedRetirementDigest == proof?.dispositionDigest)
        if (proof != null) requireSameRetainedOrigin(proof.record.body.origin, challenge.body.origin)
    }
    private fun requireSameRetainedOrigin(before: NativeDevicePairingOriginV1, after: NativeDevicePairingOriginV1) {
        // Original custody/root identity is stable; old business/employee cut is NOT renewed
        // or required to remain unchanged when a distinctly new ceremony is prepared.
        known(before == after.copy(preparationParent = before.preparationParent,
            preparationAnchorDigest = before.preparationAnchorDigest))
    }
    private fun verifyTerminalData(proof: LocalDevicePairingUnsignedRetirement) {
        val origin = proof.record.body.origin
        val rootBytes = proof.value.rootDocument.copyBytes()
        try {
            known(sha256(rootBytes) == origin.rootDocumentDigest)
            val root = SelfOwnedInstallationWireV1.decodeSigned(rootBytes) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            known(root.role == SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT)
            val canonical = root.copyWireBytes()
            try { known(canonical.contentEquals(rootBytes)) } finally { canonical.fill(0) }
            val bodyBytes = root.body()
            val body = try { SelfOwnedInstallationWireV1.decodeRoot(bodyBytes) }
            finally { bodyBytes.fill(0) }
            known(body.rootId == origin.rootId && body.rootKeyId == origin.rootKeyId &&
                body.organizationId == origin.coordinates.scope.organizationId && body.companyId == origin.coordinates.scope.companyId &&
                body.bootstrapActionId == origin.foundingActionId && AndroidP256Primitives.rootFingerprint(body) == origin.rootFingerprint)
            val point = body.rootPublicKey.copyBytes(); val unsigned = root.body()
            val message = try { SelfOwnedInstallationWireV1.signingMessage(root.role, unsigned) } finally { unsigned.fill(0) }
            val signature = root.signature(); val terminal = NativeDevicePairingWireV1.retirementMessage(proof.record)
            val terminalSignature = proof.record.retirementEndorsement.copyBytes()
            try { known(AndroidP256Primitives.verify(point, message, signature) &&
                AndroidP256Primitives.verify(point, terminal, terminalSignature)) }
            finally { point.fill(0); message.fill(0); signature.fill(0); terminal.fill(0); terminalSignature.fill(0) }
        } finally { rootBytes.fill(0) }
        // Same root/challenge continuity is DATA, not remote Android/head/master trust,
        // cancellation authority, admission, revocation or a future permission grant.
    }
    private fun validateRetirementForPrior(proof: LocalDevicePairingUnsignedRetirement, index: Int) {
        requireNoApprovedMasterImportForRetirement(index)
        verifyTerminalData(proof); requireOwnDescriptor(proof.originalChallenge)
        val previous = checkNotNull(slots[index])
        if (index > 0) withResponseCycle(index) { requireAllocationReady() }
        if (previous.responseIntent != null && previous.complete == null)
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val parentProof = allocationProof(index)
        known(proof.record.body.previousUnsignedRetirementDigest == parentProof?.dispositionDigest)
        parentProof?.let { requireSameRetainedOrigin(it.record.body.origin, proof.record.body.origin) }
        val intent = previous.responseIntent
        if (intent == null) known(proof.record.possession == null)
        else withResponseCycle(index) {
            validateComplete()
            val challenge = proof.originalChallenge.copyWireBytes()
            try { known(intent.challenge.same(challenge)) } finally { challenge.fill(0) }
            proof.record.possession?.let { retained ->
                val original = completedPossession().copyWireBytes()
                try { known(retained.same(original)) } finally { original.fill(0) }
            }
        }
    }
    private fun requireNoApprovedMasterImportForRetirement(index: Int) {
        val retained = checkNotNull(slots[index])
        if (retained.approvedMasterImportIntent != null) selfOwnedFail(if (retained.approvedMasterEvidence == null)
            SelfOwnedInstallationFailureV1.STATE_UNCERTAIN else SelfOwnedInstallationFailureV1.CONFLICT)
    }
    private fun validateAllocationIntent() {
        val allocation = slot().allocation ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val encoded = TargetWire.allocation(allocation)
        try { known(snapshots[allocationIntentName()]?.bytes?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        val previous = responseCycle - 1
        known(allocation.index == responseCycle && allocation.identityDigest == identityDigest() && slots.containsKey(previous) &&
            allocation.parentAllocationDigest == sha256(allocationBytes(previous)) &&
            allocation.previousIntentDigest == snapshots[responseIntentName(previous)]?.bytes?.let { sha256(it) } &&
            allocation.previousCompleteDigest == snapshots[completeName(previous)]?.bytes?.let { sha256(it) } &&
            allocation.osBoot.ordinal >= checkNotNull(identity).osBoot.ordinal && allocation.osBoot.ordinal <= env().bootCount)
        val proof = checkNotNull(allocationProof())
        validateRetirementForPrior(proof, previous)
        val oldIds = retainedTargetIds(previous)
        known(allocation.responseId !in oldIds && allocation.responseId !in proof.record.nativeIds() &&
            allocation.responseId !in proof.record.body.origin.reservedOriginalIds())
    }
    private fun validateAllocationReady() {
        val signed = slot().allocationReady ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        // Cached only after exact signed validation in this actual opened custody lifetime;
        // every run/checked key still rechecks ALL retained bytes/inodes and pending census.
        if (responseCycle in verifiedAllocationCycles) return
        validateAllocationIntent()
        val fields = TargetWire.decodeAllocationReadyMessage(signed.message)
        val encoded = TargetWire.allocationReady(signed)
        try { known(snapshots[allocationReadyName()]?.bytes?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        known(fields.ready.same(snapshotBytes(READY)) && fields.allocation.same(snapshotBytes(allocationIntentName())))
        val point = nativePoint(); val message = signed.message.copyBytes(); val signature = signed.signature.copyBytes()
        try { known(AndroidP256Primitives.verify(point, message, signature)) }
        finally { point.fill(0); message.fill(0); signature.fill(0) }
        verifiedAllocationCycles.add(responseCycle)
    }

    fun requireReadable() {
        known(phase == Phase.OPEN)
        assertContinuity(); validateIdentity(); assertAliasNamespace()
        known(snapshots[KEY_INTENT] == null || keyIntent != null)
        known(snapshots[READY] == null || ready != null)
        known(snapshots[SDK_ACQUISITION] == null || sdkAcquisition != null)
        known(snapshots[SDK_SIGNING_INTENT] == null || sdkSigningIntent != null)
        known(snapshots[SDK_READY] == null || sdkReady != null)
        known((snapshots[CREDENTIAL_ALLOCATION] != null) == (credentialAllocation != null))
        credentialAllocation?.let { record ->
            val encoded = NativePairedDeviceCredentialJournalWire.record(record)
            try { known(snapshots[CREDENTIAL_ALLOCATION]?.bytes?.contentEquals(encoded) == true) }
            finally { encoded.fill(0) }
        }
        known(snapshots[responseIntentName()] == null || responseIntent != null)
        known(snapshots[completeName()] == null || complete != null)
        for ((index, response) in slots) {
            known((snapshots[approvedMasterImportIntentName(index)] != null) == (response.approvedMasterImportIntent != null))
            known((snapshots[approvedMasterEvidenceName(index)] != null) == (response.approvedMasterEvidence != null))
            known(response.approvedMasterImportIntent == null || response.responseIntent != null && response.complete != null)
            known(response.approvedMasterEvidence == null || response.approvedMasterImportIntent != null)
        }
        if (responseCycle > 0) {
            known(snapshots[allocationIntentName()] == null || slot().allocation != null)
            known(snapshots[allocationReadyName()] == null || slot().allocationReady != null)
        }
        if (ready != null) checkedNativeKey()
        if (sdkAcquisition != null) validateSdkAcquisition()
        if (sdkSigningIntent != null) validateSdkSigningIntent()
        if (sdkReady != null) validateSdkReady()
    }
    private fun validateIdentity() {
        val original = identity ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val bytes = TargetWire.identity(original)
        try { known(snapshots[IDENTITY]?.bytes?.contentEquals(bytes) == true) } finally { bytes.fill(0) }
        known(original.target.same(env().target) && original.namespace == capturedNamespace() && original.osBoot.ordinal <= env().bootCount)
    }
    private fun validateKeyIntent() {
        val intent = keyIntent ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val original = checkNotNull(identity)
        val encoded = TargetWire.keyIntent(intent)
        try { known(snapshots[KEY_INTENT]?.bytes?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        known(intent.identityDigest == identityDigest() && intent.osBoot.ordinal == original.osBoot.ordinal && intent.observedElapsed >= original.observedElapsed)
        val point = intent.point.copyBytes()
        try { AndroidP256Primitives.publicKey(point) } finally { point.fill(0) }
    }
    private fun validateReady() {
        validateIdentity(); validateKeyIntent()
        val signed = ready ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val fields = TargetWire.decodeReadyMessage(signed.message)
        val encoded = TargetWire.ready(signed)
        try { known(snapshots[READY]?.bytes?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        known(fields.identity.same(snapshotBytes(IDENTITY)) && fields.keyIntent.same(snapshotBytes(KEY_INTENT)))
        val descriptorBytes = fields.descriptor.copyBytes()
        val descriptor = try { LocalDevicePairingTargetDescriptor.decode(descriptorBytes) } finally { descriptorBytes.fill(0) }
        val original = checkNotNull(identity); val point = checkNotNull(keyIntent).point.copyBytes()
        try {
            val expected = LocalDevicePairingTargetDescriptor.capture(LocalDeviceAdmission(original.scope, original.deviceId,
                Revision(0), true, original.keyId.value, sha256(point).value, original.capabilities.toSet()), original.keyId, point)
            val actual = descriptor.copyWireBytes(); val wanted = expected.copyWireBytes()
            try { known(actual.contentEquals(wanted)) } finally { actual.fill(0); wanted.fill(0) }
            checkedNativeKey()
            val message = signed.message.copyBytes(); val signature = signed.signature.copyBytes()
            try { known(AndroidP256Primitives.verify(point, message, signature)) } finally { message.fill(0); signature.fill(0) }
        } finally { point.fill(0) }
    }
    private fun validateSdkAcquisition() {
        validateReady()
        val acquisition = sdkAcquisition ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val encoded = TargetSdkWireV2.acquisition(acquisition)
        try { known(snapshots[SDK_ACQUISITION]?.bytes?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        val original = checkNotNull(identity)
        known(acquisition.identityDigest == identityDigest() && acquisition.readyDigest == readyDigest() &&
            acquisition.appId == profile.sdkProfile.appId(original.scope) &&
            acquisition.osBoot.ordinal >= original.osBoot.ordinal && acquisition.osBoot.ordinal <= env().bootCount &&
            (acquisition.osBoot.ordinal != original.osBoot.ordinal || acquisition.observedElapsed >= original.observedElapsed) &&
            acquisition.acquisitionId !in retainedTargetIds(responseCycle, includeSdk = false))
    }
    private fun validateSdkSigningIntent() {
        validateSdkAcquisition()
        val intent = sdkSigningIntent ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val encoded = TargetSdkWireV2.signingIntent(intent)
        try { known(snapshots[SDK_SIGNING_INTENT]?.bytes?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        val acquisition = checkNotNull(sdkAcquisition); val original = checkNotNull(identity)
        known(intent.identityDigest == identityDigest() && intent.readyDigest == readyDigest() &&
            intent.acquisitionDigest == sha256(snapshotBytes(SDK_ACQUISITION)) && intent.osBoot.ordinal == acquisition.osBoot.ordinal &&
            intent.observedElapsed >= acquisition.observedElapsed && intent.osBoot.ordinal <= env().bootCount)
        val sdk = sdkIdentityWithoutReentry(); val expected = descriptorWithoutReentry()
        val actualBytes = sdk.target.copyWireBytes(); val expectedBytes = expected.copyWireBytes()
        try { known(actualBytes.contentEquals(expectedBytes)) } finally { actualBytes.fill(0); expectedBytes.fill(0) }
        known(sdk.originalAcquisitionId == acquisition.acquisitionId && sdk.originalPreparationId == original.preparationId &&
            sdk.nativeApplication.same(original.target) && sdk.nativeApplication.same(env().target) && sdk.appId == acquisition.appId)
    }
    private fun validateSdkReady() {
        if (sdkAcquisition == null) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        validateSdkSigningIntent()
        val signed = sdkReady ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val fields = TargetSdkWireV2.decodeReadyMessage(signed.message)
        val encoded = TargetSdkWireV2.ready(signed)
        try { known(snapshots[SDK_READY]?.bytes?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        known(fields.identity.same(snapshotBytes(IDENTITY)) && fields.ready.same(snapshotBytes(READY)) &&
            fields.acquisition.same(snapshotBytes(SDK_ACQUISITION)) && fields.intent.same(snapshotBytes(SDK_SIGNING_INTENT)))
        checkedNativeKey()
        val point = nativePoint(); val message = signed.message.copyBytes(); val signature = signed.signature.copyBytes()
        try { known(AndroidP256Primitives.verify(point, message, signature)) }
        finally { point.fill(0); message.fill(0); signature.fill(0) }
    }
    private fun sdkIdentityWithoutReentry(): LocalDevicePairingSdkIdentityV2 {
        val bytes = checkNotNull(sdkSigningIntent).descriptor.copyBytes()
        return try { LocalDevicePairingSdkIdentityV2.decode(bytes) } finally { bytes.fill(0) }
    }
    private fun ensureOriginalSdkOwner() {
        validateSdkReady()
        if (sdkOwnerRejected) selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        if (sdkOwner == null) {
            val acquisition = checkNotNull(sdkAcquisition)
            val owned = AndroidDevicePairingSdkOwnerV2(application, work)
            sdkOwner = owned // Exact reconstructed owner retained BEFORE create or comparison.
            try { owned.createNative(acquisition.appId, acquisition.deviceName) }
            catch (failure: Throwable) { closeRejectedSdkOwner(); throw failure }
        }
        requireCurrentSdkMatches()
    }
    private fun requireCurrentSdkMatches() {
        if (sdkOwnerRejected) selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        val owned = sdkOwner ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        try { owned.requireMatchesNative(sdkIdentityWithoutReentry(), checkNotNull(sdkAcquisition).deviceName) }
        catch (failure: Throwable) { closeRejectedSdkOwner(); throw failure }
    }
    private fun closeRejectedSdkOwner() {
        sdkOwnerRejected = true
        sdkOwner?.let { it.closeNative(); sdkOwner = null } // Clear only after actual successful stop.
    }
    private fun requireSdkForNewResponse(challenge: LocalDevicePairingChallenge) {
        val offered = challenge.requested.sdkIdentity
        val master = challenge.requested.masterSdkOriginal
        if (master != null && master.compositionProfile != profile.sdkProfile)
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        if (offered == null) {
            if (sdkAcquisition != null) selfOwnedFail(if (sdkReady == null)
                SelfOwnedInstallationFailureV1.STATE_UNCERTAIN else SelfOwnedInstallationFailureV1.CONFLICT)
        } else {
            requireOwnSdkDescriptorData(challenge)
            requireSdkBranchNamespaceForNewWork(offered.appId)
            ensureOriginalSdkOwner()
        }
    }
    /** New SDK/key effects use this target's fixed original composition profile, never a
     * supplied label or another mode's namespace. Recovery of already completed original bytes
     * is validated separately against the same profile. */
    private fun requireSdkBranchNamespaceForNewWork(appId: AppId,
        mismatch: SelfOwnedInstallationFailureV1 = SelfOwnedInstallationFailureV1.CONFLICT) {
        if (appId != profile.sdkProfile.appId(checkNotNull(identity).scope)) selfOwnedFail(mismatch)
    }
    private fun requireOwnSdkDescriptorData(challenge: LocalDevicePairingChallenge) {
        val offered = challenge.requested.sdkIdentity ?: return
        validateSdkReady()
        val actual = offered.copyWireBytes(); val retained = sdkIdentityWithoutReentry().copyWireBytes()
        try { if (!actual.contentEquals(retained)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT) }
        finally { actual.fill(0); retained.fill(0) }
    }
    private fun validateResponseIntent() {
        validateReady()
        val intent = responseIntent ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val encoded = TargetWire.responseIntent(intent)
        try { known(snapshots[responseIntentName()]?.bytes?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        known(intent.readyDigest == readyDigest() && intent.osBoot.ordinal >= checkNotNull(identity).osBoot.ordinal && intent.osBoot.ordinal <= env().bootCount)
        val bytes = intent.challenge.copyBytes()
        val challenge = try { LocalDevicePairingChallenge.decode(bytes) } finally { bytes.fill(0) }
        requireOwnDescriptor(challenge)
        requireAllocationReady(); requireChallengeRetirementLink(challenge)
        known(intent.responseId == allocatedResponseId() && intent.nonce == allocatedNonce() &&
            intent.allocationIndex == responseCycle && intent.allocationDigest == sha256(allocationBytes()) &&
            intent.responseId !in listOf(challenge.originalActionId, challenge.requested.request.requestId,
                challenge.requested.admission.deviceId, challenge.requested.target.keyId) +
                challenge.requested.sdkIdentity?.let { listOf(it.originalAcquisitionId, it.originalPreparationId) }.orEmpty() &&
                intent.responseId !in challenge.body.origin.reservedOriginalIds())
    }
    private fun validateComplete() {
        validateResponseIntent()
        val signed = complete ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val fields = TargetWire.decodeCompleteMessage(signed.message)
        val encoded = TargetWire.complete(signed)
        try { known(snapshots[completeName()]?.bytes?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        known(fields.ready.same(snapshotBytes(READY)) && fields.allocation.same(allocationBytes()) &&
            fields.intent.same(snapshotBytes(responseIntentName())))
        val proofBytes = fields.possession.copyBytes(); val challengeBytes = checkNotNull(responseIntent).challenge.copyBytes()
        val proof = try { LocalDevicePairingPossession.decode(proofBytes) } finally { proofBytes.fill(0) }
        val challenge = try { LocalDevicePairingChallenge.decode(challengeBytes) } finally { challengeBytes.fill(0) }
        val descriptor = challenge.requested.target
        known(proof.originalActionId == challenge.originalActionId && proof.challengeDigest == challenge.challengeDigest &&
            proof.deviceId == descriptor.admission.deviceId && proof.keyId == descriptor.keyId && proof.descriptorDigest == descriptor.descriptorDigest &&
            proof.sdkIdentityDigest == challenge.requested.sdkIdentity?.descriptorDigest)
        checkedNativeKey()
        val point = nativePoint(); val message = signed.message.copyBytes(); val signature = signed.signature.copyBytes()
        val possessionMessage = NativeDevicePairingWireV1.possessionMessage(challenge); val possessionSignature = proof.copySignature()
        try { known(AndroidP256Primitives.verify(point, message, signature) &&
            AndroidP256Primitives.verify(point, possessionMessage, possessionSignature)) }
        finally { point.fill(0); message.fill(0); signature.fill(0); possessionMessage.fill(0); possessionSignature.fill(0) }
    }
    private fun requireOwnDescriptor(challenge: LocalDevicePairingChallenge) {
        val expected = descriptorWithoutReentry(); val supplied = challenge.requested.target
        val actual = supplied.copyWireBytes(); val wanted = expected.copyWireBytes()
        try { if (!actual.contentEquals(wanted)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT) }
        finally { actual.fill(0); wanted.fill(0) }
        requireOwnSdkDescriptorData(challenge)
        // Deliberately no master authentication or remote-clock comparison from this DATA.
    }
    private fun descriptorWithoutReentry(): LocalDevicePairingTargetDescriptor {
        val fields = TargetWire.decodeReadyMessage(checkNotNull(ready).message)
        val bytes = fields.descriptor.copyBytes()
        return try { LocalDevicePairingTargetDescriptor.decode(bytes) } finally { bytes.fill(0) }
    }
    private fun identityDigest(): PayloadDigest = sha256(snapshotBytes(IDENTITY))
    private fun readyDigest(): PayloadDigest = sha256(snapshotBytes(READY))
    private fun snapshotBytes(name: String): ByteArray = checkNotNull(snapshots[name]).bytes
    private fun updateReference(value: AndroidDevicePairingTargetPhase) {
        val original = checkNotNull(identity); val intent = responseIntent
        val challenge = intent?.challenge?.copyBytes()?.let { bytes ->
            try { LocalDevicePairingChallenge.decode(bytes) } finally { bytes.fill(0) }
        }
        lastKnown = AndroidDevicePairingTargetReference(original.preparationId, original.deviceId, original.keyId, value,
            allocatedResponseId(), challenge?.originalActionId, challenge?.challengeDigest,
            sdkAcquisition?.acquisitionId, if (sdkReady != null) sdkIdentityWithoutReentry().descriptorDigest else null)
    }
    private fun keyStore(): KeyStore = checkNotNull(store)
    private fun aliasPrefix(): String = SelfOwnedCompositionNames.devicePairingAliasPrefix(profile.slot)
    private fun alias(): String = checkNotNull(identity).let { "${aliasPrefix()}${it.preparationId.value}.${it.keyId.value}" }
    private fun assertAliasNamespace() {
        assertBase()
        val expected = identity?.let { alias() }; val aliases = keyStore().aliases(); var count = 0; var seen = false
        while (aliases.hasMoreElements()) {
            if (++count > 65_536) keyFailure()
            val value = aliases.nextElement()
            if (value.startsWith(aliasPrefix())) {
                if (value != expected || seen) keyFailure()
                seen = true
            }
        }
        if (ready != null && !seen) keyFailure()
        assertBase()
    }
    private fun loadNativeKey(): PrivateKey {
        val key = keyStore().getKey(alias(), null) as? PrivateKey ?: keyFailure()
        if (key.algorithm != KeyProperties.KEY_ALGORITHM_EC) keyFailure()
        requireNonexportable(key)
        return key
    }
    private fun nativePoint(): ByteArray = AndroidP256Primitives.point(
        keyStore().getCertificate(alias())?.publicKey as? ECPublicKey ?: keyFailure())
    private fun inspectNativeKey(key: PrivateKey): SelfOwnedNativeSecurityV1 {
        val info = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        if (info.keystoreAlias != alias() || info.keySize != 256 || info.origin != KeyProperties.ORIGIN_GENERATED ||
            info.purposes != (KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY) ||
            info.digests.toSet() != setOf(KeyProperties.DIGEST_SHA256) || info.blockModes.isNotEmpty() ||
            info.encryptionPaddings.isNotEmpty() || info.signaturePaddings.isNotEmpty() ||
            AndroidNativeKeyInfo.hasServiceRestrictions(info)) keyFailure()
        requireNonexportable(key)
        // Same supported OS_ISOLATED policy: actual KeyInfo facts, no stronger hardware claim.
        val observed = try { AndroidNativeKeyInfo.observe(info) } catch (_: Exception) { keyFailure() }
        if (!AndroidNativeKeyPolicy.permits(SelfOwnedKeyProtectionV1.OS_ISOLATED, observed)) keyFailure()
        return observed.selfOwnedSecurity().originalWitness(keyIntent?.security)
    }
    private fun requireNonexportable(key: PrivateKey) {
        val exposed = key.encoded
        if (exposed != null) { exposed.fill(0); keyFailure() }
    }
    private fun checkedNativeKey(): PrivateKey {
        assertContinuity(); validateKeyIntent(); assertAliasNamespace()
        val intent = checkNotNull(keyIntent); val key = loadNativeKey()
        if (inspectNativeKey(key) != intent.security) keyFailure()
        val point = nativePoint()
        try { if (!intent.point.same(point)) keyFailure() } finally { point.fill(0) }
        assertContinuity()
        if (inspectNativeKey(loadNativeKey()) != intent.security) keyFailure()
        return key // No fresh proof signature on reads/current checks; original snapshot verifies it.
    }

    private fun openNamespace(creating: Boolean) {
        check(phase == Phase.NEW); phase = Phase.OPENING; work.requireActive()
        if (creating) AndroidSelfOwnedEnvironment.capture(application, work, profile) // Explicit framework ancestry preparation only.
        val observed = AndroidSelfOwnedEnvironment.captureExistingOriginal(application, work, profile)
        environment = observed
        directory = File(observed.noBackupDirectory, SelfOwnedCompositionNames.devicePairingTargetDirectory(profile.slot))
        for (path in generateSequence(observed.noBackupDirectory) { it.parentFile }.toList().asReversed()) {
            val value = stamp(Os.lstat(path.path)); requireDirectory(value, owned = false)
            if (path == observed.dataDirectory || path == observed.noBackupDirectory) known(value.uid == observed.uid)
            ancestors[path] = value
        }
        dataDescriptor = openDirectory(observed.dataDirectory, checkNotNull(ancestors[observed.dataDirectory]))
        parentDescriptor = openDirectory(observed.noBackupDirectory, checkNotNull(ancestors[observed.noBackupDirectory]))
        requireFreshNativeRole() // Before target namespace creation or any key/SDK intent.
        val absent = lstatOrNull(dir()) == null
        if (creating) known(absent) else known(!absent)
        if (absent) Os.mkdir(dir().path, DIRECTORY_MODE)
        val value = stamp(Os.lstat(dir().path)); requireDirectory(value, owned = true)
        directoryStamp = value; directoryDescriptor = openDirectory(dir(), value)
        if (absent) Os.fchmod(checkNotNull(directoryDescriptor).fd, DIRECTORY_MODE)
        if (creating) { Os.fsync(checkNotNull(parentDescriptor).fd); Os.fsync(checkNotNull(dataDescriptor).fd) }
        val names = androidOwnedDirectoryNames(dir(), FILE_ROLES.size + 1, work)
        known(if (absent) names.isEmpty() else LOCK in names)
        known(names.all { it == LOCK || it in FILE_ROLES || it.removeSuffix(".pending") in FILE_ROLES })
        lockDescriptor = openDescriptor(File(dir(), LOCK), OsConstants.O_RDWR or OsConstants.O_NOFOLLOW or
            (if (absent) OsConstants.O_CREAT or OsConstants.O_EXCL else 0), FILE_MODE)
        val lockFd = checkNotNull(lockDescriptor); val before = stamp(Os.fstat(lockFd.fd))
        requireFile(before, 0, allowEmpty = true)
        known(before.sameFile(stamp(Os.lstat(File(dir(), LOCK).path))) && before.device == value.device)
        val stream = FileOutputStream(lockFd.fd); lockFd.streamOwner = stream; lockChannel = stream.channel
        lock = checkNotNull(lockChannel).tryLock() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
        if (absent) { Os.fsync(lockFd.fd); Os.fsync(checkNotNull(directoryDescriptor).fd) }
        lockStamp = stamp(Os.fstat(lockFd.fd)); known(checkNotNull(lockStamp).sameFile(stamp(Os.lstat(File(dir(), LOCK).path))))
        volumeUuid = currentVolumeUuid()
        val retainedNames = names.filter { it in FILE_ROLES }
        val retainedSize = retainedNames.sumOf { name ->
            val item = stamp(Os.lstat(File(dir(), name).path)); requireFile(item, maximum(name), allowEmpty = false); item.size
        }
        known(retainedSize <= TOTAL_SNAPSHOT_MAX)
        for (name in (FILE_ROLES.take(5) + SDK_ROLES + APPROVED_MASTER_ROLES + CREDENTIAL_ROLES + retainedNames).distinct())
            snapshots[name] = readSnapshot(name, maximum(name))
        store = KeyStore.getInstance("AndroidKeyStore").apply { load(null, null) }
        phase = Phase.OPEN
        assertBase()
    }
    private fun env(): AndroidSelfOwnedEnvironment = checkNotNull(environment)
    private fun dir(): File = checkNotNull(directory)
    private fun currentVolumeUuid(): String {
        env().requireExistingOriginalObservation()
        val uuid = AndroidNativeStorageVolume.uuidForPath(application, dir()).toString()
        env().requireExistingOriginalObservation()
        return uuid
    }
    private fun capturedNamespace(): TargetNamespace = TargetNamespace(dir().path, env().uid, checkNotNull(volumeUuid),
        checkNotNull(directoryStamp).inode, checkNotNull(lockStamp).inode)
    /**
     * Existing installation/provisioner namespaces exclude a fresh physical target. This is
     * a negative native role check only: their presence, contents or absence never confer
     * Master authority. Pending, corrupt, non-directory and symlink originals remain intact
     * and refuse this path; only actual ENOENT is accepted, never an unreadable fallback.
     */
    private fun requireFreshNativeRole() {
        env().requireExistingOriginalObservation()
        for (name in originalMasterNamespaces()) {
            if (lstatOrNull(File(env().noBackupDirectory, name)) != null)
                selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        }
        env().requireExistingOriginalObservation()
    }
    /** Same-profile original Master namespaces only; live and training slots never cross-block. */
    private fun originalMasterNamespaces(): List<String> =
        listOf(profile.targetDirectoryName, profile.provisionerDirectoryName)
    private fun assertBase() {
        known(phase == Phase.OPEN); env().requireExistingOriginalObservation()
        for ((path, expected) in ancestors) known(expected.sameDirectory(stamp(Os.lstat(path.path))))
        known(checkNotNull(ancestors[env().dataDirectory]).sameDirectory(stamp(Os.fstat(checkNotNull(dataDescriptor).fd))) &&
            checkNotNull(ancestors[env().noBackupDirectory]).sameDirectory(stamp(Os.fstat(checkNotNull(parentDescriptor).fd))))
        requireFreshNativeRole() // Recheck pinned ancestry before later native acquisition/signing.
        val expected = checkNotNull(directoryStamp)
        known(expected.sameDirectory(stamp(Os.lstat(dir().path))) && expected.sameDirectory(stamp(Os.fstat(checkNotNull(directoryDescriptor).fd))))
        known(lock?.isValid == true && lockChannel?.isOpen == true)
        val held = checkNotNull(lockStamp)
        known(held.sameFile(stamp(Os.lstat(File(dir(), LOCK).path))) && held.sameFile(stamp(Os.fstat(checkNotNull(lockDescriptor).fd))) &&
            held.device == expected.device && currentVolumeUuid() == volumeUuid)
    }
    private fun checkNames(pending: String? = null) {
        val names = androidOwnedDirectoryNames(dir(), FILE_ROLES.size + 2, work)
        val allowed = snapshots.filterValues { it != null }.keys + LOCK + listOfNotNull(pending)
        known(names.size <= allowed.size && names.distinct().size == names.size && names.all { it in allowed } && LOCK in names)
        if (pending != null) known(pending in names)
    }
    private fun checkSnapshots() {
        for ((name, expected) in snapshots) {
            val actual = readSnapshot(name, maximum(name))
            try { known(if (expected == null) actual == null else actual != null &&
                expected.stamp.sameFile(actual.stamp) && expected.bytes.contentEquals(actual.bytes)) }
            finally { actual?.clear() }
        }
    }
    private fun assertContinuity() { assertBase(); checkNames(); checkSnapshots(); assertBase() }
    private fun forceNew(name: String, bytes: ByteArray) {
        assertContinuity(); known(name in FILE_ROLES && snapshots[name] == null && bytes.size in 1..maximum(name))
        requireAppendBytes(bytes.size.toLong())
        val pendingName = "$name.pending"; val pending = File(dir(), pendingName)
        val descriptor = openDescriptor(pending, OsConstants.O_RDWR or OsConstants.O_NOFOLLOW or
            OsConstants.O_CREAT or OsConstants.O_EXCL, FILE_MODE)
        val created = stamp(Os.fstat(descriptor.fd)); requireFile(created, maximum(name), allowEmpty = true)
        known(created.size == 0L && created.sameFile(stamp(Os.lstat(pending.path))))
        try {
            var offset = 0
            while (offset < bytes.size) {
                val count = Os.write(descriptor.fd, bytes, offset, bytes.size - offset)
                known(count > 0 && count <= bytes.size - offset); offset += count
            }
            Os.fsync(descriptor.fd)
            val written = stamp(Os.fstat(descriptor.fd)); requireFile(written, maximum(name), allowEmpty = false)
            known(written.sameIdentity(created) && written.size == bytes.size.toLong())
            Os.lseek(descriptor.fd, 0, OsConstants.SEEK_SET)
            val readback = readExact(descriptor.fd, bytes.size)
            try { known(readback.contentEquals(bytes)) } finally { readback.fill(0) }
            known(written.sameFile(stamp(Os.fstat(descriptor.fd))) && written.sameFile(stamp(Os.lstat(pending.path))))
            assertBase(); checkNames(pendingName); checkSnapshots(); known(lstatOrNull(File(dir(), name)) == null)
            closeTransient(descriptor)
            Os.rename(pending.path, File(dir(), name).path); Os.fsync(checkNotNull(directoryDescriptor).fd)
            val actual = readSnapshot(name, maximum(name)) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            try {
                known(written.sameIdentity(actual.stamp) && written.size == actual.stamp.size &&
                    written.modified == actual.stamp.modified && actual.bytes.contentEquals(bytes))
                snapshots[name] = actual // Only this exact known-own force/readback, never orphan/pending adoption.
            } catch (failure: Throwable) { actual.clear(); throw failure }
            checkNames(); assertBase()
        } finally { if (!descriptor.closed) closeTransient(descriptor) }
    }
    private fun readSnapshot(name: String, maximum: Int): Snapshot? {
        val path = File(dir(), name); val seen = lstatOrNull(path) ?: return null
        val expected = stamp(seen); requireFile(expected, maximum, allowEmpty = false)
        val descriptor = openDescriptor(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        var bytes: ByteArray? = null
        try {
            known(expected.sameFile(stamp(Os.fstat(descriptor.fd)))); bytes = readExact(descriptor.fd, expected.size.toInt())
            known(expected.sameFile(stamp(Os.fstat(descriptor.fd))) && expected.sameFile(stamp(Os.lstat(path.path))))
            return Snapshot(expected, checkNotNull(bytes)).also { bytes = null }
        } finally { bytes?.fill(0); closeTransient(descriptor) }
    }
    private fun readExact(fd: FileDescriptor, size: Int): ByteArray {
        val bytes = ByteArray(size); val tail = ByteArray(1)
        try {
            var offset = 0
            while (offset < size) { val count = Os.read(fd, bytes, offset, size - offset); known(count > 0 && count <= size - offset); offset += count }
            known(Os.read(fd, tail, 0, 1) == 0); return bytes
        } catch (failure: Throwable) { bytes.fill(0); throw failure }
        finally { tail.fill(0) }
    }
    private fun openDirectory(path: File, expected: Stamp): OwnedDescriptor {
        known(OsConstants.S_ISDIR(expected.mode))
        val descriptor = openDescriptor(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0)
        known(expected.sameDirectory(stamp(Os.fstat(descriptor.fd))) && expected.sameDirectory(stamp(Os.lstat(path.path))))
        return descriptor
    }
    private fun openDescriptor(path: File, flags: Int, mode: Int): OwnedDescriptor {
        val owned = OwnedDescriptor()
        descriptors.add(owned)
        owned.native = androidOwnedOpenDescriptor(path, flags, mode, work)
        known(AndroidNativeFiles.closeOnExec(owned.fd))
        return owned
    }
    private fun closeTransient(value: OwnedDescriptor) { value.closeOnce(); descriptors.remove(value) }
    fun closeNative() {
        if (phase == Phase.CLOSED) return
        if (phase == Phase.CLOSE_STARTED) { work.nativeClosureUnknown(); selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED) }
        phase = Phase.CLOSE_STARTED
        try {
            sdkOwner?.let { it.closeNative(); sdkOwner = null }
            lock?.let { known(it.isValid); it.release(); known(!it.isValid); lock = null }
            for (descriptor in descriptors.toList().asReversed()) closeTransient(descriptor)
            known(lockChannel?.isOpen != true)
            snapshots.values.forEach { it?.clear() }; snapshots.clear(); store = null
            identity = null; keyIntent = null; ready = null; responseIntent = null; complete = null
            sdkAcquisition = null; sdkSigningIntent = null; sdkReady = null
            credentialAllocation = null; preparedHere = false
            slots.values.forEach { it.approvedMasterImportIntent = null; it.approvedMasterEvidence = null }
            phase = Phase.CLOSED
        } catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
    }
    private inner class OwnedDescriptor {
        var native: FileDescriptor? = null
        val fd: FileDescriptor get() = requireNotNull(native)
        var streamOwner: FileOutputStream? = null
        private var closeStarted = false
        var closed = false; private set
        fun closeOnce() {
            if (closed) return
            if (closeStarted) { work.nativeClosureUnknown(); selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED) }
            closeStarted = true
            val actual = native
            if (actual == null) { closed = true; return }
            try { streamOwner?.close(); AndroidNativeFiles.close(actual); closed = true }
            catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
        }
    }
    private class Snapshot(val stamp: Stamp, val bytes: ByteArray) { fun clear() { bytes.fill(0) } }
    private data class Stamp(val device: Long, val inode: Long, val uid: Int, val gid: Int, val mode: Int,
        val links: Long, val size: Long, val modified: Long, val changed: Long) {
        fun sameIdentity(other: Stamp): Boolean = device == other.device && inode == other.inode && uid == other.uid &&
            gid == other.gid && mode == other.mode && links == other.links
        fun sameDirectory(other: Stamp): Boolean = device == other.device && inode == other.inode && uid == other.uid &&
            gid == other.gid && mode == other.mode && OsConstants.S_ISDIR(other.mode)
        fun sameFile(other: Stamp): Boolean = sameIdentity(other) && size == other.size && modified == other.modified && changed == other.changed
    }
    private fun stamp(value: StructStat): Stamp = Stamp(value.st_dev, value.st_ino, value.st_uid, value.st_gid, value.st_mode,
        value.st_nlink, value.st_size, value.st_mtime, value.st_ctime)
    private fun requireDirectory(value: Stamp, owned: Boolean) {
        known(OsConstants.S_ISDIR(value.mode) && value.mode and 2 == 0)
        if (owned) known(value.uid == env().uid && value.mode and 4095 == DIRECTORY_MODE)
    }
    private fun requireFile(value: Stamp, maximum: Int, allowEmpty: Boolean) {
        known(OsConstants.S_ISREG(value.mode) && value.uid == env().uid && value.mode and 4095 == FILE_MODE &&
            value.links == 1L && value.size in (if (allowEmpty) 0L else 1L)..maximum.toLong())
    }
    private fun lstatOrNull(path: File): StructStat? = try { Os.lstat(path.path) } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT) null else throw failure
    }
    private fun maximum(name: String): Int = when (name) {
        IDENTITY -> IDENTITY_MAX; KEY_INTENT -> KEY_INTENT_MAX; READY -> READY_MAX
        RESPONSE_INTENT -> RESPONSE_INTENT_MAX; COMPLETE -> COMPLETE_MAX
        SDK_ACQUISITION -> SDK_ACQUISITION_MAX; SDK_SIGNING_INTENT -> SDK_SIGNING_INTENT_MAX; SDK_READY -> SDK_READY_MAX
        CREDENTIAL_ALLOCATION -> NativePairedDeviceCredentialJournalWire.MAXIMUM_RECORD_BYTES
        APPROVED_MASTER_IMPORT_INTENT -> APPROVED_MASTER_IMPORT_INTENT_MAX; APPROVED_MASTER_EVIDENCE -> APPROVED_MASTER_EVIDENCE_MAX
        else -> {
            known(name in FILE_ROLES)
            when {
                name.endsWith(ALLOCATION_INTENT) -> ALLOCATION_INTENT_MAX
                name.endsWith(ALLOCATION_READY) -> ALLOCATION_READY_MAX
                name.endsWith(RESPONSE_INTENT) -> RESPONSE_INTENT_MAX
                name.endsWith(COMPLETE) -> COMPLETE_MAX
                name.endsWith(APPROVED_MASTER_IMPORT_INTENT) -> APPROVED_MASTER_IMPORT_INTENT_MAX
                name.endsWith(APPROVED_MASTER_EVIDENCE) -> APPROVED_MASTER_EVIDENCE_MAX
                else -> error("Unknown target-only append role")
            }
        }
    }
    private fun known(value: Boolean) { if (!value) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
    private fun keyFailure(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
    companion object {
        // Fixed live production values retained in SelfOwnedCompositionNames; training adds
        // its own fixed slot suffix and never interprets a caller-selected path.
        const val LOCK = "owner.lock"
        const val IDENTITY = "identity.v1"
        const val KEY_INTENT = "key-signing-intent.v1"
        const val READY = "key-ready.v1"
        const val RESPONSE_INTENT = "response-signing-intent.v1"
        const val COMPLETE = "response-completed.v1"
        const val ALLOCATION_INTENT = "response-allocation-signing-intent.v1"
        const val ALLOCATION_READY = "response-allocation-ready.v1"
        const val SDK_ACQUISITION = "sdk-acquisition-intent.v2"
        const val SDK_SIGNING_INTENT = "sdk-metadata-signing-intent.v2"
        const val SDK_READY = "sdk-ready.v2"
        const val APPROVED_MASTER_IMPORT_INTENT = "approved-master-import-intent.v1"
        const val APPROVED_MASTER_EVIDENCE = "approved-master-evidence.v1"
        const val CREDENTIAL_ALLOCATION = "paired-device-credential-allocation.v1"
        const val MAXIMUM_RESPONSE_CYCLES = DevicePairingTargetLimitsV1.MAXIMUM_RESPONSE_CYCLES
        const val TOTAL_SNAPSHOT_MAX = DevicePairingTargetLimitsV1.TOTAL_SNAPSHOT_MAX
        const val ALLOCATION_INTENT_MAX = DevicePairingTargetLimitsV1.ALLOCATION_INTENT_MAX
        const val ALLOCATION_READY_MAX = DevicePairingTargetLimitsV1.ALLOCATION_READY_MAX
        const val IDENTITY_MAX = DevicePairingTargetLimitsV1.IDENTITY_MAX
        const val KEY_INTENT_MAX = DevicePairingTargetLimitsV1.KEY_INTENT_MAX
        const val READY_MAX = DevicePairingTargetLimitsV1.READY_MAX
        const val SDK_ACQUISITION_MAX = DevicePairingTargetLimitsV1.SDK_ACQUISITION_MAX
        const val SDK_SIGNING_INTENT_MAX = DevicePairingTargetLimitsV1.SDK_SIGNING_INTENT_MAX
        const val SDK_READY_MAX = DevicePairingTargetLimitsV1.SDK_READY_MAX
        const val RESPONSE_INTENT_MAX = DevicePairingTargetLimitsV1.RESPONSE_INTENT_MAX
        const val COMPLETE_MAX = DevicePairingTargetLimitsV1.COMPLETE_MAX
        const val APPROVAL_CERTIFICATE_MAX = DevicePairingTargetLimitsV1.APPROVAL_CERTIFICATE_MAX
        const val APPROVED_MASTER_IMPORT_INTENT_MAX = DevicePairingTargetLimitsV1.APPROVED_MASTER_IMPORT_INTENT_MAX
        const val APPROVED_MASTER_EVIDENCE_MAX = DevicePairingTargetLimitsV1.APPROVED_MASTER_EVIDENCE_MAX
        const val DIRECTORY_MODE = 448 //0700
        const val FILE_MODE = 384 //0600
        fun responseRole(index: Int, role: String): String {
            require(index in 0 until MAXIMUM_RESPONSE_CYCLES)
            return if (index == 0) role else "append-${index.toString().padStart(3, '0')}-$role"
        }
        val SDK_ROLES = listOf(SDK_ACQUISITION, SDK_SIGNING_INTENT, SDK_READY)
        val APPROVED_MASTER_ROLES = listOf(APPROVED_MASTER_IMPORT_INTENT, APPROVED_MASTER_EVIDENCE)
        val CREDENTIAL_ROLES = listOf(CREDENTIAL_ALLOCATION)
        val FILE_ROLES = listOf(IDENTITY, KEY_INTENT, READY, RESPONSE_INTENT, COMPLETE) + SDK_ROLES + APPROVED_MASTER_ROLES +
            (1 until MAXIMUM_RESPONSE_CYCLES).flatMap { index ->
                (listOf(ALLOCATION_INTENT, ALLOCATION_READY, RESPONSE_INTENT, COMPLETE) + APPROVED_MASTER_ROLES).map { responseRole(index, it) }
            } + CREDENTIAL_ROLES
    }
}

