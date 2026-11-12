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
) : LocalOriginalPairingTarget {
    private val denied = AtomicBoolean(false)
    /** Fixed composition profile binding of this actual native target; descriptive DATA only. */
    override val compositionProfile: LocalCompositionSdkProfileV1 get() = native.compositionProfile
    override suspend fun recoverSdkOriginalData(): LocalOriginalPairingTargetResult<LocalDevicePairingSdkOriginal> =
        when (val result = recoverSdkOriginal()) {
            is AndroidDevicePairingTargetResult.Value -> LocalOriginalPairingTargetResult.Value(result.value)
            is AndroidDevicePairingTargetResult.Unavailable -> LocalOriginalPairingTargetResult.Unavailable(result.reason)
        }
    override suspend fun recoverApprovedMasterData(originalPairingActionId: DomainId):
        LocalOriginalPairingTargetResult<LocalDevicePairingApprovedMaster> =
        when (val result = recoverApprovedMaster(originalPairingActionId)) {
            is AndroidDevicePairingTargetResult.Value -> LocalOriginalPairingTargetResult.Value(result.value)
            is AndroidDevicePairingTargetResult.Unavailable -> LocalOriginalPairingTargetResult.Unavailable(result.reason)
        }
    private class CredentialCustodyIssue {
        val custody = AtomicReference<PairedDeviceCredentialJournalCustody?>(null)
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
        AndroidDevicePairingTargetResult<PairedDeviceCredentialJournalCustody> {
        currentCoroutineContext().ensureActive()
        if (denied.get()) return AndroidDevicePairingTargetResult.Unavailable(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        val issue = CredentialCustodyIssue()
        if (!credentialCustodyIssue.compareAndSet(null, issue))
            return AndroidDevicePairingTargetResult.Unavailable(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        var prepared = false
        try {
            return when (val result = work.run {
                object : AndroidOwnedTask<PairedDeviceCredentialJournalCustody> {
                    override fun execute(): PairedDeviceCredentialJournalCustody {
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
                    override fun discard(value: PairedDeviceCredentialJournalCustody) { value.denyUndelivered() }
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

    /** Internal adapter: this public target cannot expose the internal source interface itself. */
    internal val custodySource: PairedDeviceCredentialCustodySource = object : PairedDeviceCredentialCustodySource {
        override fun credentialJournalCustodyCurrent(custody: PairedDeviceCredentialJournalCustody): Boolean =
            this@AndroidDevicePairingTarget.credentialJournalCustodyCurrent(custody)
        override suspend fun revalidateCredentialJournalCustody(custody: PairedDeviceCredentialJournalCustody,
            validation: PairedDeviceCredentialJournalCustody.NativeValidation): Boolean =
            this@AndroidDevicePairingTarget.revalidateCredentialJournalCustody(custody, validation)
    }

    internal fun credentialJournalCustodyCurrent(custody: PairedDeviceCredentialJournalCustody): Boolean {
        if (denied.get() || credentialCustodyIssue.get()?.custody?.get() !== custody) return false
        // Reuse the actual source's original process/clock/terminal-work fence, not a
        // manufactured positive current marker. Protected key/evidence IO stays owned.
        try { work.requireActive() } catch (_: Throwable) { return false }
        return !denied.get() && credentialCustodyIssue.get()?.custody?.get() === custody
    }

    internal suspend fun revalidateCredentialJournalCustody(custody: PairedDeviceCredentialJournalCustody,
        validation: PairedDeviceCredentialJournalCustody.NativeValidation): Boolean {
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
    override fun denyNewWork() { denied.set(true) }
    override suspend fun close() {
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
    private val work: AndroidOwnedInstallationWork, private val profile: SelfOwnedNativeProfileV1) : DevicePairingTargetNative {
    val compositionProfile: LocalCompositionSdkProfileV1 get() = profile.sdkProfile
    /** Shared Android/desktop ceremony; this class supplies only Android native custody. */
    private val core = DevicePairingTargetCore(this)
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
    private val snapshots = linkedMapOf<String, Snapshot?>().apply { DevicePairingTargetFiles.BASE_ROLES.forEach { put(it, null) } }

    // ---- shared ceremony (unchanged public behavior) ----
    fun lastKnownOriginalNoIo(): AndroidDevicePairingTargetReference? = core.lastKnownOriginalNoIo()
    fun prepareNewNative(scope: BranchScope, capabilities: List<String>) = core.prepareNew(scope, capabilities)
    fun openExistingNative() = core.openExisting()
    fun requireReadable() = core.requireReadable()
    fun original(): AndroidDevicePairingTargetReference = core.original()
    fun descriptor(): LocalDevicePairingTargetDescriptor = core.descriptor()
    fun acquireSdkIdentity(appId: AppId, deviceName: String): LocalDevicePairingSdkIdentityV2 = core.acquireSdkIdentity(appId, deviceName)
    fun recoverSdkIdentity(): LocalDevicePairingSdkIdentityV2 = core.recoverSdkIdentity()
    fun recoverSdkOriginal(): LocalDevicePairingSdkOriginal = core.recoverSdkOriginal()
    fun respond(challenge: LocalDevicePairingChallenge): LocalDevicePairingPossession = core.respond(challenge)
    fun recoverPossession(id: DomainId): LocalDevicePairingPossession = core.recoverPossession(id)
    fun importApprovedMaster(certificate: ByteArray, rootAnnouncement: ByteArray): LocalDevicePairingApprovedMaster =
        core.importApprovedMaster(certificate, rootAnnouncement)
    fun recoverApprovedMaster(originalPairingActionId: DomainId): LocalDevicePairingApprovedMaster =
        core.recoverApprovedMaster(originalPairingActionId)
    fun beginAfterKnownUnsigned(proof: LocalDevicePairingUnsignedRetirement): AndroidDevicePairingTargetReference =
        core.beginAfterKnownUnsigned(proof)
    fun recoverAfterKnownUnsigned(proof: LocalDevicePairingUnsignedRetirement): AndroidDevicePairingTargetReference =
        core.recoverAfterKnownUnsigned(proof)
    fun acquireCredentialJournalCustody(target: AndroidDevicePairingTarget, originalPairingActionId: DomainId,
        action: LocalPairedDeviceCredentialJournalAction, issueSettled: CompletableDeferred<Unit>):
        PairedDeviceCredentialJournalCustody {
        val (approved, sdk, expected) = core.allocateCredentialJournal(originalPairingActionId, action)
        return PairedDeviceCredentialJournalCustody(application, target.custodySource, action, approved, sdk, expected, issueSettled)
    }
    fun requireCredentialJournalCustody(custody: PairedDeviceCredentialJournalCustody) {
        known(custody.belongsTo(application) && custody.originalOpenClaimed())
        core.requireCredentialAllocation(custody.originalRecord)
    }
    fun closeNative() = core.closeNative()

    // ---- Android native seam ----
    override val variant: TargetWireVariant get() = TargetWireVariant.ANDROID_V1
    override val sdkProfile: LocalCompositionSdkProfileV1 get() = profile.sdkProfile
    override fun open(creating: Boolean) = openNamespace(creating)
    override fun snapshot(name: String): ByteArray? = snapshots[name]?.bytes
    override fun pending(name: String, maximum: Int): ByteArray? = readSnapshot("$name.pending", maximum)?.let { candidate ->
        try { candidate.bytes.copyOf() } finally { candidate.clear() }
    }
    override fun retainedBytes(): Long = snapshots.values.filterNotNull().sumOf { it.bytes.size.toLong() }
    override fun origin(): TargetOrigin = TargetOrigin.Android(env().target, capturedNamespace())
    override fun originMatches(original: TargetOrigin): Boolean =
        original is TargetOrigin.Android && original.target.same(env().target) && original.namespace == capturedNamespace()
    override fun boot(): TargetBoot = TargetBoot.Android(env().bootCount)
    override fun clock(): Long = work.clock()
    override fun sdkApplicationMatches(sdk: LocalDevicePairingSdkIdentityV2, original: TargetOrigin): Boolean {
        val application = try { sdk.nativeApplication } catch (_: IllegalStateException) { return false }
        return original is TargetOrigin.Android && application.same(original.target) && application.same(env().target)
    }
    override fun newSdkOwner(): DevicePairingTargetSdkOwner = object : DevicePairingTargetSdkOwner {
        private val owned = AndroidDevicePairingSdkOwnerV2(application, work)
        override fun create(appId: AppId, deviceName: String) = owned.createNative(appId, deviceName)
        override fun capture(acquisitionId: DomainId, preparationId: DomainId, target: LocalDevicePairingTargetDescriptor) =
            owned.captureNative(acquisitionId, preparationId, target, env().target)
        override fun requireMatches(original: LocalDevicePairingSdkIdentityV2, deviceName: String) =
            owned.requireMatchesNative(original, deviceName)
        override fun close() = owned.closeNative()
    }

    private fun keyStore(): KeyStore = checkNotNull(store)
    private fun aliasPrefix(): String = SelfOwnedCompositionNames.devicePairingAliasPrefix(profile.slot)
    private fun alias(preparationId: DomainId, keyId: DomainId): String = "${aliasPrefix()}${preparationId.value}.${keyId.value}"
    override fun assertKeyNamespace(expected: Pair<DomainId, DomainId>?, keyRequired: Boolean) {
        assertBase()
        val wanted = expected?.let { alias(it.first, it.second) }; val aliases = keyStore().aliases(); var count = 0; var seen = false
        while (aliases.hasMoreElements()) {
            if (++count > 65_536) keyFailure()
            val value = aliases.nextElement()
            if (value.startsWith(aliasPrefix())) {
                if (value != wanted || seen) keyFailure()
                seen = true
            }
        }
        if (keyRequired && !seen) keyFailure()
        assertBase()
    }
    override fun keyAbsent(preparationId: DomainId, keyId: DomainId): Boolean = !keyStore().containsAlias(alias(preparationId, keyId))
    override fun generateKey(preparationId: DomainId, keyId: DomainId) {
        val builder = KeyGenParameterSpec.Builder(alias(preparationId, keyId), KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(256).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
        AndroidNativeKeyInfo.configureService(builder, SelfOwnedKeyProtectionV1.OS_ISOLATED)
        try { KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply { initialize(builder.build()) }.generateKeyPair() }
        catch (_: Exception) { keyFailure() }
    }
    private fun loadNativeKey(preparationId: DomainId, keyId: DomainId): PrivateKey {
        val key = keyStore().getKey(alias(preparationId, keyId), null) as? PrivateKey ?: keyFailure()
        if (key.algorithm != KeyProperties.KEY_ALGORITHM_EC) keyFailure()
        requireNonexportable(key)
        return key
    }
    override fun keyPoint(preparationId: DomainId, keyId: DomainId): ByteArray = AndroidP256Primitives.point(
        keyStore().getCertificate(alias(preparationId, keyId))?.publicKey as? ECPublicKey ?: keyFailure())
    override fun keySecurity(preparationId: DomainId, keyId: DomainId, retained: SelfOwnedNativeSecurityV1?): SelfOwnedNativeSecurityV1 {
        val key = loadNativeKey(preparationId, keyId)
        val info = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        if (info.keystoreAlias != alias(preparationId, keyId) || info.keySize != 256 || info.origin != KeyProperties.ORIGIN_GENERATED ||
            info.purposes != (KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY) ||
            info.digests.toSet() != setOf(KeyProperties.DIGEST_SHA256) || info.blockModes.isNotEmpty() ||
            info.encryptionPaddings.isNotEmpty() || info.signaturePaddings.isNotEmpty() ||
            AndroidNativeKeyInfo.hasServiceRestrictions(info)) keyFailure()
        requireNonexportable(key)
        // Same supported OS_ISOLATED policy: actual KeyInfo facts, no stronger hardware claim.
        val observed = try { AndroidNativeKeyInfo.observe(info) } catch (_: Exception) { keyFailure() }
        if (!AndroidNativeKeyPolicy.permits(SelfOwnedKeyProtectionV1.OS_ISOLATED, observed)) keyFailure()
        return observed.selfOwnedSecurity().originalWitness(retained)
    }
    override fun sign(preparationId: DomainId, keyId: DomainId, message: ByteArray): ByteArray =
        AndroidP256Primitives.sign(loadNativeKey(preparationId, keyId), message)
    private fun requireNonexportable(key: PrivateKey) {
        val exposed = key.encoded
        if (exposed != null) { exposed.fill(0); keyFailure() }
    }
    private fun requireAppendBytes(additional: Long) {
        if (additional < 0 || retainedBytes() > TOTAL_SNAPSHOT_MAX.toLong() - additional)
            selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
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
    override fun assertContinuity() { assertBase(); checkNames(); checkSnapshots(); assertBase() }
    override fun forceNew(name: String, bytes: ByteArray) {
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
    private fun closeTransient(value: OwnedDescriptor) { value.closeOnce(); descriptors.remove(value) }    override fun releaseNative() {
        if (phase == Phase.CLOSED) return
        if (phase == Phase.CLOSE_STARTED) { work.nativeClosureUnknown(); selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED) }
        phase = Phase.CLOSE_STARTED
        try {
            lock?.let { known(it.isValid); it.release(); known(!it.isValid); lock = null }
            for (descriptor in descriptors.toList().asReversed()) closeTransient(descriptor)
            known(lockChannel?.isOpen != true)
            snapshots.values.forEach { it?.clear() }; snapshots.clear(); store = null
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
    private fun maximum(name: String): Int = DevicePairingTargetFiles.maximum(name)
    private fun known(value: Boolean) { if (!value) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
    private fun keyFailure(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
    companion object {
        const val LOCK = DevicePairingTargetFiles.LOCK
        const val TOTAL_SNAPSHOT_MAX = DevicePairingTargetLimitsV1.TOTAL_SNAPSHOT_MAX
        const val APPROVAL_CERTIFICATE_MAX = DevicePairingTargetLimitsV1.APPROVAL_CERTIFICATE_MAX
        const val DIRECTORY_MODE = 448 //0700
        const val FILE_MODE = 384 //0600
        val SDK_ROLES = DevicePairingTargetFiles.SDK_ROLES
        val APPROVED_MASTER_ROLES = DevicePairingTargetFiles.APPROVED_MASTER_ROLES
        val CREDENTIAL_ROLES = DevicePairingTargetFiles.CREDENTIAL_ROLES
        val FILE_ROLES = DevicePairingTargetFiles.FILE_ROLES
    }
}
