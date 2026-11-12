package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.AppId
import java.nio.file.Path

/** OS key custody of one desktop Client target key (Windows CNG / macOS Keychain). */
internal interface DesktopClientTargetKey : AutoCloseable {
    val security: SelfOwnedNativeSecurityV1
    fun publicPoint(): ByteArray
    /** Signs a fixed owner message; returns a verified canonical 64-byte signature. */
    fun sign(message: ByteArray): ByteArray
}

/** Native facts, key custody and SDK owner for the desktop Client target; every value is observed. */
internal interface DesktopClientTargetPlatform {
    val slot: LocalTrainingCompositionSlot?
    /** Fixed application-private parent directory (Windows package LocalState, macOS Application Support). */
    fun parentDirectory(): Path
    fun origin(directory: Path): TargetOrigin.Desktop
    fun bootSessionId(): String
    fun elapsedMillis(): Long
    fun keyExists(preparationId: DomainId, keyId: DomainId): Boolean
    fun createKey(preparationId: DomainId, keyId: DomainId): DesktopClientTargetKey
    fun openKey(preparationId: DomainId, keyId: DomainId, originalPoint: ByteArray): DesktopClientTargetKey
    fun newSdkOwner(installed: SelfOwnedInstalledIdentityTargetV2): DevicePairingTargetSdkOwner
}

/**
 * Desktop (Windows/macOS) native seam of the SHARED pairing ceremony [DevicePairingTargetCore]:
 * write-once snapshot store, boot ledger, in-app OS key and JVM SDK owner. The ceremony itself is
 * identical to Android's. Unlike Android KeyStore, CNG/Keychain do not enumerate a key namespace, so
 * key-namespace checks require exactly the expected key rather than proving no other key exists.
 */
internal class DesktopDevicePairingTargetNative(private val platform: DesktopClientTargetPlatform) : DevicePairingTargetNative {
    val core = DevicePairingTargetCore(this)
    private var store: DesktopTargetSnapshotStore? = null
    private var openedPending: Set<String> = emptySet()
    private val snapshots = linkedMapOf<String, ByteArray?>()
    private var key: DesktopClientTargetKey? = null
    private var keyIds: Pair<DomainId, DomainId>? = null

    private fun store(): DesktopTargetSnapshotStore = store ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    private fun directory(): Path =
        platform.parentDirectory().resolve(SelfOwnedCompositionNames.devicePairingTargetDirectory(platform.slot))

    override val variant: TargetWireVariant get() = TargetWireVariant.DESKTOP_V2
    override val sdkProfile: LocalCompositionSdkProfileV1
        get() = platform.slot?.let { LocalTrainingSdkProfileV1(it) } ?: LocalMasterSdkProfileV1

    override fun open(creating: Boolean) {
        check(store == null)
        val opened = if (creating) DesktopTargetSnapshotStore.create(directory()) else DesktopTargetSnapshotStore.openExisting(directory())
        store = opened
        DesktopTargetBootLedger.observe(opened, platform.bootSessionId())
        val names = opened.names()
        known(names.all { it in DevicePairingTargetFiles.FILE_ROLES || it.startsWith(LEDGER_PREFIX) })
        openedPending = opened.pendingNames()
        known(openedPending.all { it in DevicePairingTargetFiles.FILE_ROLES })
        for (name in (DevicePairingTargetFiles.BASE_ROLES + names.filter { it in DevicePairingTargetFiles.FILE_ROLES }).distinct()) {
            snapshots[name] = opened.read(name, DevicePairingTargetFiles.maximum(name))
        }
        known(retainedBytes() <= DevicePairingTargetLimitsV1.TOTAL_SNAPSHOT_MAX)
    }

    override fun snapshot(name: String): ByteArray? = snapshots[name]

    override fun pending(name: String, maximum: Int): ByteArray? = store().readPending(name, maximum)

    override fun forceNew(name: String, bytes: ByteArray) {
        assertContinuity()
        known(name in DevicePairingTargetFiles.FILE_ROLES && snapshots[name] == null &&
            bytes.size in 1..DevicePairingTargetFiles.maximum(name))
        if (retainedBytes() > DevicePairingTargetLimitsV1.TOTAL_SNAPSHOT_MAX.toLong() - bytes.size) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        }
        store().forceNew(name, bytes)
        val actual = store().read(name, DevicePairingTargetFiles.maximum(name)) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        known(actual.contentEquals(bytes))
        snapshots[name] = actual // Only this exact known-own force/readback, never pending adoption.
        assertContinuity()
    }

    override fun retainedBytes(): Long = snapshots.values.filterNotNull().sumOf { it.size.toLong() }

    /** Same names, same bytes, no new pending write and no reboot since this custody opened. */
    override fun assertContinuity() {
        val opened = store()
        val names = opened.names().filterNot { it.startsWith(LEDGER_PREFIX) }.toSet()
        known(names == snapshots.filterValues { it != null }.keys && opened.pendingNames() == openedPending)
        for ((name, expected) in snapshots) {
            val actual = opened.read(name, DevicePairingTargetFiles.maximum(name))
            try { known(if (expected == null) actual == null else actual?.contentEquals(expected) == true) }
            finally { actual?.fill(0) }
        }
        boot()
    }

    override fun assertKeyNamespace(expected: Pair<DomainId, DomainId>?, keyRequired: Boolean) {
        assertContinuity()
        if (!keyRequired) return
        val ids = expected ?: keyFailure()
        loadedKey(ids.first, ids.second)
    }

    override fun keyAbsent(preparationId: DomainId, keyId: DomainId): Boolean =
        key == null && !platform.keyExists(preparationId, keyId)

    override fun generateKey(preparationId: DomainId, keyId: DomainId) {
        known(key == null)
        keyIds = preparationId to keyId
        key = try { platform.createKey(preparationId, keyId) } catch (_: Exception) { keyFailure() }
    }

    /** The retained original key, opened existing-only against the retained key-intent point. */
    private fun loadedKey(preparationId: DomainId, keyId: DomainId): DesktopClientTargetKey {
        key?.let { if (keyIds != preparationId to keyId) keyFailure(); return it }
        val intentBytes = snapshots[DevicePairingTargetFiles.KEY_INTENT] ?: keyFailure()
        val point = TargetWire.decodeKeyIntent(intentBytes, variant).point.copyBytes()
        try {
            keyIds = preparationId to keyId
            return (try { platform.openKey(preparationId, keyId, point) } catch (_: Exception) { keyFailure() }).also { key = it }
        } finally { point.fill(0) }
    }

    override fun keyPoint(preparationId: DomainId, keyId: DomainId): ByteArray = loadedKey(preparationId, keyId).publicPoint()
    override fun keySecurity(preparationId: DomainId, keyId: DomainId, retained: SelfOwnedNativeSecurityV1?): SelfOwnedNativeSecurityV1 =
        loadedKey(preparationId, keyId).security
    override fun sign(preparationId: DomainId, keyId: DomainId, message: ByteArray): ByteArray =
        loadedKey(preparationId, keyId).sign(message)

    override fun origin(): TargetOrigin = platform.origin(store().directory)
    override fun originMatches(original: TargetOrigin): Boolean = original == origin()

    override fun boot(): TargetBoot {
        val current = DesktopTargetBootLedger.current(store()) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        known(current.sessionId == platform.bootSessionId())
        return current
    }

    override fun clock(): Long = platform.elapsedMillis()

    override fun newSdkOwner(): DevicePairingTargetSdkOwner =
        platform.newSdkOwner((origin() as TargetOrigin.Desktop).installed)

    override fun sdkApplicationMatches(sdk: LocalDevicePairingSdkIdentityV2, original: TargetOrigin): Boolean =
        original is TargetOrigin.Desktop && sdk.installedApplication == original.installed

    override fun releaseNative() {
        var failure: Throwable? = null
        try { key?.close() } catch (error: Throwable) { failure = error }
        key = null
        try { store?.close() } catch (error: Throwable) { failure?.addSuppressed(error) ?: run { failure = error } }
        store = null
        snapshots.values.forEach { it?.fill(0) }; snapshots.clear()
        failure?.let { throw it }
    }

    private companion object {
        const val LEDGER_PREFIX = "boot-ledger-"
        fun known(value: Boolean) { if (!value) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
        fun keyFailure(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
    }
}

/**
 * Desktop Client original pairing target: the SHARED ceremony over desktop native custody. Calls are
 * serialized; [denyNewWork] fences new work immediately and [close] releases key and storage.
 */
internal class DesktopDevicePairingTarget private constructor(private val native: DesktopDevicePairingTargetNative) : AutoCloseable {
    private val core get() = native.core
    @Volatile private var denied = false
    private var closed = false

    @Synchronized private fun <T> run(body: () -> T): T {
        if (closed || denied) selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
        core.requireReadable()
        val value = body()
        core.requireReadable()
        if (denied) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        return value
    }

    fun original(): LocalDevicePairingTargetReference = run { core.original() }
    fun descriptor(): LocalDevicePairingTargetDescriptor = run { core.descriptor() }
    fun acquireSdkIdentity(appId: AppId, deviceName: String): LocalDevicePairingSdkIdentityV2 = run { core.acquireSdkIdentity(appId, deviceName) }
    fun recoverSdkIdentity(): LocalDevicePairingSdkIdentityV2 = run { core.recoverSdkIdentity() }
    fun recoverSdkOriginal(): LocalDevicePairingSdkOriginal = run { core.recoverSdkOriginal() }
    fun respond(challenge: LocalDevicePairingChallenge): LocalDevicePairingPossession = run { core.respond(challenge) }
    fun recoverPossession(originalPairingActionId: DomainId): LocalDevicePairingPossession = run { core.recoverPossession(originalPairingActionId) }
    fun importApprovedMaster(certificate: ByteArray, rootAnnouncement: ByteArray): LocalDevicePairingApprovedMaster {
        require(certificate.size in 1..DevicePairingTargetLimitsV1.APPROVAL_CERTIFICATE_MAX &&
            rootAnnouncement.size in 1..SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES)
        return run { core.importApprovedMaster(certificate.copyOf(), rootAnnouncement.copyOf()) }
    }
    fun recoverApprovedMaster(originalPairingActionId: DomainId): LocalDevicePairingApprovedMaster =
        run { core.recoverApprovedMaster(originalPairingActionId) }
    fun beginAfterKnownUnsigned(retirement: LocalDevicePairingUnsignedRetirement): LocalDevicePairingTargetReference =
        run { core.beginAfterKnownUnsigned(retirement) }
    fun recoverAfterKnownUnsigned(retirement: LocalDevicePairingUnsignedRetirement): LocalDevicePairingTargetReference =
        run { core.recoverAfterKnownUnsigned(retirement) }

    val compositionProfile: LocalCompositionSdkProfileV1 get() = native.sdkProfile
    fun denyNewWork() { denied = true }

    private val issuedCustody = java.util.concurrent.atomic.AtomicReference<PairedDeviceCredentialJournalCustody?>(null)
    private var custodyIssued = false

    /** Revalidation seam for the ONE custody this lifetime issues; same checks as the Android target. */
    private val custodySource = object : PairedDeviceCredentialCustodySource {
        override fun credentialJournalCustodyCurrent(custody: PairedDeviceCredentialJournalCustody): Boolean =
            !denied && !closed && issuedCustody.get() === custody

        override suspend fun revalidateCredentialJournalCustody(custody: PairedDeviceCredentialJournalCustody,
            validation: PairedDeviceCredentialJournalCustody.NativeValidation): Boolean {
            validation.prepared = true
            return try {
                synchronized(this@DesktopDevicePairingTarget) {
                    if (!custody.isCurrent()) return@synchronized false
                    core.requireReadable()
                    if (!custody.originalOpenClaimed()) return@synchronized false
                    core.requireCredentialAllocation(custody.originalRecord)
                    core.requireReadable()
                    custody.isCurrent()
                }
            } catch (_: Exception) { false } finally { validation.finish() }
        }
    }

    /**
     * Explicit protected credential-journal custody on THIS approved original, before the target is
     * transferred to RPC. ONE issue per lifetime; a lost/uncertain issue is never reissued.
     */
    fun acquireCredentialJournalCustody(owner: Any, originalPairingActionId: DomainId,
        action: LocalPairedDeviceCredentialJournalAction): PairedDeviceCredentialJournalCustody = run {
        check(!custodyIssued) { "Credential custody was already issued for this target lifetime" }
        custodyIssued = true
        val (approved, sdk, record) = core.allocateCredentialJournal(originalPairingActionId, action)
        val settled = kotlinx.coroutines.CompletableDeferred(Unit)
        PairedDeviceCredentialJournalCustody(owner, custodySource, action, approved, sdk, record, settled)
            .also { issuedCustody.set(it) }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true; denied = true
        core.closeNative()
    }

    override fun toString(): String = "DesktopDevicePairingTarget([REDACTED])"

    companion object {
        /** Explicit FIRST preparation only; an existing target directory is never adopted. */
        fun prepareNew(platform: DesktopClientTargetPlatform, scope: BranchScope, capabilities: Set<String>): DesktopDevicePairingTarget {
            val copied = capabilities.sorted()
            if (copied.size > ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITIES || copied.any { code ->
                    code.length !in 1..ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITY_BYTES ||
                        code.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it !in "._-" } }) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
            }
            val native = DesktopDevicePairingTargetNative(platform)
            try { native.core.prepareNew(scope, copied); return DesktopDevicePairingTarget(native) } catch (failure: Throwable) {
                try { native.core.closeNative() } catch (close: Throwable) { failure.addSuppressed(close) }
                throw failure
            }
        }

        /** Existing only; never creates a missing directory, record or key. */
        fun openExisting(platform: DesktopClientTargetPlatform): DesktopDevicePairingTarget {
            val native = DesktopDevicePairingTargetNative(platform)
            try { native.core.openExisting(); return DesktopDevicePairingTarget(native) } catch (failure: Throwable) {
                try { native.core.closeNative() } catch (close: Throwable) { failure.addSuppressed(close) }
                throw failure
            }
        }
    }
}
