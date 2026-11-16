package com.ukkera.pos.sales.store

import com.ukkera.pos.database.LocalMasterPhysicalRpcClientV1
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.SelfOwnedMasterHostOwner
import com.ukkera.pos.security.LocalSetupCompositionMode
import com.ukkera.pos.security.DesktopMasterClientJournalCustody
import com.ukkera.pos.security.DesktopPhysicalDirectoryOwner
import com.ukkera.pos.security.DesktopPairedDevicePhysicalJournalCustody
import com.ukkera.pos.security.DesktopPairedDevicePhysicalStorageKind
import com.ukkera.pos.security.LocalMasterSdkProfileV1
import com.ukkera.pos.security.LocalTrainingSdkProfileV1
import com.ukkera.pos.security.MacClientJournalKeyStore
import com.ukkera.pos.security.MacDesktopFiles
import com.ukkera.pos.security.MacInstalledApplicationIdentity
import com.ukkera.pos.security.PairedDeviceCredentialJournalCustody
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.SelfOwnedMacUiIdentityV2
import com.ukkera.pos.security.SelfOwnedWindowsPackagedAppIdentityV2
import com.ukkera.pos.security.WindowsClientJournalKeyStore
import com.ukkera.pos.security.WindowsDesktopFiles
import com.ukkera.pos.security.WindowsPrivateClientPhysicalDirectory
import com.ukkera.pos.types.DomainId
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * Genuine one-role native Client acquisition for the enrolled desktop original. The gateway
 * supplies a counted loan of its SAME successful actor journal and rechecks that actual owner;
 * scalar metadata alone cannot install the internal remote business/physical binding.
 */
public object DesktopDistributedRetailPhysicalRecording {
    public fun acquireClient(rootOwner: Any, original: PairedDeviceCredentialJournalCustody,
        rpc: LocalMasterPhysicalRpcClientV1, actorId: DomainId, actorIntentJournalId: DomainId,
        actorCustody: LocalPhysicalExternalCustody,
        action: DistributedPhysicalNativeAction): DistributedPhysicalClientNativeAcquisition? =
        acquireClientOwned(rootOwner, original, rpc, actorId, actorIntentJournalId, actorCustody,
            action, DistributedPhysicalClientPurpose.INTERACTIVE)

    /** Actual native existing-only recovery. Scalar selectors cannot create files/keys, arm
     * an action, supply native proof or expose a mutable employee/session continuation. */
    public fun acquireRawOriginalRecovery(rootOwner: Any, original: PairedDeviceCredentialJournalCustody,
        rpc: LocalMasterPhysicalRpcClientV1, actorId: DomainId, actorIntentJournalId: DomainId,
        allocationCustody: LocalPhysicalExternalCustody): DistributedPhysicalRawRecoveryAcquisition? =
        acquireClientOwned(rootOwner, original, rpc, actorId, actorIntentJournalId, allocationCustody,
            DistributedPhysicalNativeAction.REOPEN_EXISTING, DistributedPhysicalClientPurpose.RAW_ORIGINAL_RECOVERY)
            ?.let { DistributedPhysicalRawRecoveryAcquisition(it, rpc) }

    /** Existing recording-actor catalogue only. Its actual allocation-ledger loan is retained
     * independently of the current resolver's separately authenticated intent journal. */
    public fun acquireExistingResolution(rootOwner: Any, original: PairedDeviceCredentialJournalCustody,
        rpc: LocalMasterPhysicalRpcClientV1, recordedActorId: DomainId, actorIntentJournalId: DomainId,
        allocationCustody: LocalPhysicalExternalCustody): DistributedPhysicalClientNativeAcquisition? =
        acquireClientOwned(rootOwner, original, rpc, recordedActorId, actorIntentJournalId, allocationCustody,
            DistributedPhysicalNativeAction.REOPEN_EXISTING, DistributedPhysicalClientPurpose.EXISTING_RESOLUTION)

    private fun acquireClientOwned(rootOwner: Any, original: PairedDeviceCredentialJournalCustody,
        rpc: LocalMasterPhysicalRpcClientV1, actorId: DomainId, actorIntentJournalId: DomainId,
        actorCustody: LocalPhysicalExternalCustody, action: DistributedPhysicalNativeAction,
        purpose: DistributedPhysicalClientPurpose): DistributedPhysicalClientNativeAcquisition? {
        val qualification = PhysicalJournalExternalCustodyOwnership(actorCustody)
        var transferred = false
        try {
            if (!original.belongsTo(rootOwner) || !rpc.hasOriginal(original.sdkOriginal, original.approvedOriginal) ||
                !rpc.isCurrent() || qualification.denied || original.protection != SelfOwnedKeyProtectionV1.OS_ISOLATED) return null
            val source = original.physicalJournalSource(rootOwner) as? DesktopPairedDevicePhysicalJournalCustody ?: return null
            if (!source.belongsTo(rootOwner, original)) return null
            val keys = DistributedDesktopPhysicalKeys.PairedClient(source)
            val suffix = when (val profile = original.approvedOriginal.masterSdkOriginal.compositionProfile) {
                LocalMasterSdkProfileV1 -> "live"
                is LocalTrainingSdkProfileV1 -> profile.slot.name.lowercase().replace('_', '-')
                else -> return null
            }
            val domain = "ukkera.desktop.distributed-physical-client.$suffix/v1"
            fun id(role: String) = distributedPhysicalId(original.bindingCommitment, actorId, actorIntentJournalId, domain, role)
            val binding = LocalPhysicalCatalogueBinding(original.scope, id("installation"), original.deviceId,
                id("catalogue"), LocalPhysicalSpoolRole.CLIENT, id("key"))
            val namespace = DistributedDesktopPhysicalNamespace(keys, binding,
                "ukkera-distributed-physical-client-$suffix-v1", "ukkera.desktop.distributed-physical-client.$suffix.v1/",
                action == DistributedPhysicalNativeAction.ENROLL_ORIGINAL)
            val held = DistributedPhysicalClientNativeAcquisitions.retain(binding) {
                DistributedPhysicalClientNativeAcquisition(original, rpc, actorId, actorIntentJournalId, binding,
                    action, purpose, keys.storageProfile, qualification, namespace) {
                    if (action == DistributedPhysicalNativeAction.ENROLL_ORIGINAL)
                        JvmPreallocatedPhysicalJournal.createDistributedCatalogue(namespace.root, binding, namespace)
                    else JvmPreallocatedPhysicalJournal.openDistributedCatalogue(namespace.root, binding, namespace)
                }
            } ?: return null
            transferred = true
            return held
        } finally { if (!transferred) check(qualification.releaseAfterNativeCleanup()) }
    }
    public fun acquireMaster(host: SelfOwnedMasterHostOwner, original: AndroidOriginalLocalClient,
        registeredOwner: LocalPhysicalJournalExecutionOwner, custody: DesktopMasterClientJournalCustody,
        action: DistributedPhysicalNativeAction): DistributedPhysicalMasterNativeAcquisition? {
        if (!original.belongsTo(host) || original.physicalEndpoint(registeredOwner) == null ||
            (action == DistributedPhysicalNativeAction.ENROLL_ORIGINAL && !original.permitsFirstEnrollment())) return null
        val suffix = if (original.compositionMode == LocalSetupCompositionMode.TRAINING) "training" else "live"
        val domain = "ukkera.desktop.distributed-physical-master.$suffix/v1"
        fun id(role: String) = LocalOriginalRetailPhysicalAcquisition.originalPhysicalCatalogueId(original, role, domain)
        val binding = LocalPhysicalCatalogueBinding(original.peer.scope, id("installation"), original.peer.deviceId,
            id("catalogue"), LocalPhysicalSpoolRole.MASTER, id("key"))
        val keys = DistributedDesktopPhysicalKeys.Master(custody)
        val enroll = action == DistributedPhysicalNativeAction.ENROLL_ORIGINAL
        val namespace = DistributedDesktopPhysicalNamespace(keys, binding,
            "ukkera-distributed-physical-master-$suffix-v1", "ukkera.desktop.distributed-physical-master.$suffix.v1/", enroll)
        return DistributedPhysicalMasterNativeAcquisitions.retain(binding) {
            DistributedPhysicalMasterNativeAcquisition(original, registeredOwner, binding, keys.storageProfile, enroll, namespace) {
                if (enroll) JvmPreallocatedPhysicalJournal.createDistributedCatalogue(namespace.root, binding, namespace)
                else JvmPreallocatedPhysicalJournal.openDistributedCatalogue(namespace.root, binding, namespace)
            }
        }
    }

}

/** Actual OS custodians only. No key/provider/currentness callback is accepted from a caller. */
internal sealed class DistributedDesktopPhysicalKeys {
    abstract val parent: Path
    abstract val storageProfile: LocalPhysicalSpoolStorageProfileV1
    abstract fun requireCurrent()
    abstract fun createKey(name: String)
    abstract fun keyNames(prefix: String): List<String>
    abstract fun inspectKey(name: String): String
    abstract fun encrypt(name: String, aad: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray>
    abstract fun decrypt(name: String, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray
    abstract fun directory(path: Path): DistributedDesktopPhysicalDirectory

    class WindowsClient(val expected: SelfOwnedWindowsPackagedAppIdentityV2) : DistributedDesktopPhysicalKeys() {
        private var held: WindowsClientJournalKeyStore? = null
        private fun store(): WindowsClientJournalKeyStore = held ?: WindowsClientJournalKeyStore.open(expected).also { held = it }
        override val parent: Path get() = Paths.get(checkNotNull(System.getenv("LOCALAPPDATA")), "Packages",
            expected.packageFamilyName, "LocalState").toAbsolutePath().normalize()
        override val storageProfile = LocalPhysicalSpoolStorageProfileV1.JVM_WINDOWS_NTFS_FULL_WRITE_FLUSH_V1
        override fun requireCurrent() { WindowsDesktopFiles.currentUserSid(expected) }
        override fun createKey(name: String) = store().createKey(name)
        override fun keyNames(prefix: String): List<String> = store().keyNames(prefix)
        override fun inspectKey(name: String): String = store().inspectKey(name)
        override fun encrypt(name: String, aad: ByteArray, plain: ByteArray) = store().encrypt(name, aad, plain)
        override fun decrypt(name: String, nonce: ByteArray, sealed: ByteArray, aad: ByteArray) = store().decrypt(name, nonce, sealed, aad)
        override fun directory(path: Path): DistributedDesktopPhysicalDirectory =
            DistributedDesktopPhysicalDirectory.Windows(WindowsPrivateClientPhysicalDirectory.reserve(expected, path))
    }

    class MacClient(val expected: SelfOwnedMacUiIdentityV2) : DistributedDesktopPhysicalKeys() {
        private var held: MacClientJournalKeyStore? = null
        private fun store(): MacClientJournalKeyStore = held ?: MacClientJournalKeyStore.open().also { held = it }
        override val parent: Path get() = Paths.get(checkNotNull(System.getProperty("user.home")), "Library",
            "Application Support", MacDesktopFiles.BUNDLE_ID).toAbsolutePath().normalize()
        override val storageProfile = LocalPhysicalSpoolStorageProfileV1.JVM_POSIX_FULL_WRITE_FORCE_V1
        override fun requireCurrent() {
            check(MacInstalledApplicationIdentity.captureUi(expected) is SelfOwnedInstalledIdentityResultV2.Data)
        }
        override fun createKey(name: String) = store().createKey(name)
        override fun keyNames(prefix: String): List<String> = store().keyNames(prefix)
        override fun inspectKey(name: String): String = store().inspectKey(name)
        override fun encrypt(name: String, aad: ByteArray, plain: ByteArray) = store().encrypt(name, aad, plain)
        override fun decrypt(name: String, nonce: ByteArray, sealed: ByteArray, aad: ByteArray) = store().decrypt(name, nonce, sealed, aad)
        override fun directory(path: Path): DistributedDesktopPhysicalDirectory =
            DistributedDesktopPhysicalDirectory.Desktop(DesktopPhysicalDirectoryOwner.reserveMacClient(expected, path))
    }

    class PairedClient(private val custody: DesktopPairedDevicePhysicalJournalCustody) : DistributedDesktopPhysicalKeys() {
        override val parent: Path get() = custody.parentDirectory
        override val storageProfile: LocalPhysicalSpoolStorageProfileV1 get() = when (custody.kind) {
            DesktopPairedDevicePhysicalStorageKind.POSIX_FULL_WRITE_FORCE_V1 -> LocalPhysicalSpoolStorageProfileV1.JVM_POSIX_FULL_WRITE_FORCE_V1
            DesktopPairedDevicePhysicalStorageKind.WINDOWS_NTFS_FULL_WRITE_FLUSH_V1 -> LocalPhysicalSpoolStorageProfileV1.JVM_WINDOWS_NTFS_FULL_WRITE_FLUSH_V1
        }
        override fun requireCurrent() = custody.requireCurrent()
        override fun createKey(name: String) = custody.createKey(name)
        override fun keyNames(prefix: String): List<String> = custody.keyNames(prefix)
        override fun inspectKey(name: String): String = custody.inspectKey(name)
        override fun encrypt(name: String, aad: ByteArray, plain: ByteArray) = custody.encrypt(name, aad, plain)
        override fun decrypt(name: String, nonce: ByteArray, sealed: ByteArray, aad: ByteArray) = custody.decrypt(name, nonce, sealed, aad)
        override fun directory(path: Path): DistributedDesktopPhysicalDirectory = when (custody.kind) {
            DesktopPairedDevicePhysicalStorageKind.POSIX_FULL_WRITE_FORCE_V1 ->
                DistributedDesktopPhysicalDirectory.Desktop(DesktopPhysicalDirectoryOwner.reservePairedClient(custody, path))
            DesktopPairedDevicePhysicalStorageKind.WINDOWS_NTFS_FULL_WRITE_FLUSH_V1 ->
                DistributedDesktopPhysicalDirectory.Windows(WindowsPrivateClientPhysicalDirectory.reserve(checkNotNull(custody.windowsIdentity), path))
        }
    }

    class Master(val custody: DesktopMasterClientJournalCustody) : DistributedDesktopPhysicalKeys() {
        override val parent: Path get() = custody.parentDirectory
        override val storageProfile: LocalPhysicalSpoolStorageProfileV1 get() =
            if (windowsPhysicalProfile(parent)) LocalPhysicalSpoolStorageProfileV1.JVM_WINDOWS_NTFS_FULL_WRITE_FLUSH_V1
            else LocalPhysicalSpoolStorageProfileV1.JVM_POSIX_FULL_WRITE_FORCE_V1
        override fun requireCurrent() = custody.requireCurrent()
        override fun createKey(name: String) = custody.createKey(name)
        override fun keyNames(prefix: String): List<String> = custody.keyNames(prefix)
        override fun inspectKey(name: String): String = custody.inspectKey(name)
        override fun encrypt(name: String, aad: ByteArray, plain: ByteArray) = custody.encrypt(name, aad, plain)
        override fun decrypt(name: String, nonce: ByteArray, sealed: ByteArray, aad: ByteArray) = custody.decrypt(name, nonce, sealed, aad)
        override fun directory(path: Path): DistributedDesktopPhysicalDirectory =
            DistributedDesktopPhysicalDirectory.Desktop(DesktopPhysicalDirectoryOwner.reserveMaster(custody, path))
    }
}

internal sealed class DistributedDesktopPhysicalDirectory {
    abstract fun open(enroll: Boolean)
    abstract fun requireCurrent()
    abstract fun force()
    abstract fun names(): Set<String>
    abstract fun close(): Boolean
    class Desktop(private val actual: DesktopPhysicalDirectoryOwner) : DistributedDesktopPhysicalDirectory() {
        override fun open(enroll: Boolean) = actual.open(enroll)
        override fun requireCurrent() = actual.requireCurrent()
        override fun force() = actual.force()
        override fun names(): Set<String> = actual.names()
        override fun close(): Boolean = actual.closeConfirmed()
    }
    class Windows(private val actual: WindowsPrivateClientPhysicalDirectory) : DistributedDesktopPhysicalDirectory() {
        override fun open(enroll: Boolean) = actual.open(enroll)
        override fun requireCurrent() = actual.requireCurrent()
        // The selected existing NTFS profile relies on journaled directory metadata; no false
        // claim that a Java read-only directory descriptor was force-flushed is made.
        override fun force() = actual.requireCurrent()
        override fun names(): Set<String> = actual.names()
        override fun close(): Boolean = actual.closeConfirmed()
    }
}

/**
 * ONE role, fixed immutable original namespace. All actual directory owners are retained for
 * the native catalogue lifetime and per-file ciphers recheck exactly their own ancestor path.
 * Unknown/open/close outcomes remain owned; enrollment never repairs or replaces a path/key.
 */
internal class DistributedDesktopPhysicalNamespace(
    private val keys: DistributedDesktopPhysicalKeys,
    private val binding: LocalPhysicalCatalogueBinding,
    private val containerName: String,
    private val keyNamespace: String,
    private val enroll: Boolean,
) : OriginalPhysicalNativeNamespace {
    private var parent: Path? = null
    private val directories = linkedMapOf<Path, DistributedDesktopPhysicalDirectory>()
    private var prepared = false
    private var closing = false
    private var outcome: LocalPhysicalSpoolClose? = null
    val root: Path get() = checkNotNull(parent).resolve(containerName)
    private val keyPrefix: String get() = keyNamespace + binding.installationId.value + "/"
    private val keyName: String get() = keyPrefix + binding.keyId.value

    @Synchronized override fun prepare() {
        check(!prepared && !closing)
        check(containerName.matches(Regex("[a-z0-9][a-z0-9.-]{0,95}")))
        keys.requireCurrent()
        val actualParent = keys.parent
        check(actualParent.isAbsolute && actualParent.normalize() == actualParent && actualParent.toRealPath() == actualParent &&
            Files.isDirectory(actualParent, LinkOption.NOFOLLOW_LINKS))
        parent = actualParent
        hold(root, create = enroll && absent(root))
        val journalRoot = root.resolve("physical-journal-v1")
        hold(journalRoot, create = enroll && absent(journalRoot))
        val installation = journalRoot.resolve(binding.installationId.value)
        if (enroll) check(absent(installation))
        hold(installation, create = enroll)
        val catalogue = installation.resolve(binding.catalogueId.value)
        val role = catalogue.resolve(binding.role.name.lowercase())
        hold(catalogue, create = enroll); hold(role, create = enroll)
        val leaves = listOf("anchor", "catalogue") + (0 until LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES).map(::slot)
        for (leaf in leaves) hold(role.resolve(leaf), create = enroll)
        requireEntries(installation, setOf(binding.catalogueId.value))
        requireEntries(catalogue, setOf(binding.role.name.lowercase()))
        requireEntries(role, leaves.toSet())
        if (enroll) leaves.forEach { requireEntries(role.resolve(it), emptySet()) }
        val existing = keys.keyNames(keyPrefix)
        if (enroll) { check(existing.isEmpty()); keys.createKey(keyName) }
        else check(existing == listOf(keyName))
        check(keys.keyNames(keyPrefix) == listOf(keyName))
        keys.inspectKey(keyName)
        prepared = true
        requireCurrent()
    }

    @Synchronized override fun requireCurrent() {
        check(!closing && !Thread.currentThread().isInterrupted)
        keys.requireCurrent()
        parent?.let { check(keys.parent == it && it.toRealPath() == it) }
        directories.values.forEach { it.requireCurrent() }
    }

    @Synchronized fun cipherFor(spec: PhysicalNativeFileSpecV1): PhysicalSpoolCipher {
        check(prepared && spec.installationId == binding.installationId && spec.keyId == binding.keyId &&
            spec.segments.take(4) == listOf("physical-journal-v1", binding.installationId.value,
                binding.catalogueId.value, binding.role.name.lowercase()))
        var path = root
        val selected = mutableListOf<DistributedDesktopPhysicalDirectory>(checkNotNull(directories[path]))
        for (segment in spec.segments) {
            path = path.resolve(segment)
            selected += checkNotNull(directories[path])
        }
        return DistributedDesktopPhysicalCipher(this, keys, keyName, selected)
    }

    @Synchronized fun requireFileCurrent(selected: List<DistributedDesktopPhysicalDirectory>) {
        check(prepared && !closing && !Thread.currentThread().isInterrupted)
        keys.requireCurrent()
        check(parent == keys.parent)
        selected.forEach { it.requireCurrent() }
    }

    @Synchronized override fun requireCensus(owner: LocalPhysicalCatalogueBinding, census: LocalPhysicalCatalogueNativeCensus) {
        check(owner == binding)
        val role = root.resolve("physical-journal-v1").resolve(binding.installationId.value)
            .resolve(binding.catalogueId.value).resolve(binding.role.name.lowercase())
        requireEntries(role.resolve("anchor"), census.anchorFilenames)
        requireEntries(role.resolve("catalogue"), census.catalogueFilenames)
        for (index in 0 until LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES)
            requireEntries(role.resolve(slot(index)), census.journalFilenames[index] ?: emptySet())
    }

    private fun hold(path: Path, create: Boolean) {
        check(!closing && path !in directories)
        keys.requireCurrent()
        val actual = keys.directory(path)
        directories[path] = actual // Own before native create/open or any subsequent check.
        actual.open(create)
        if (create) { actual.force(); directories[path.parent]?.force() }
    }

    private fun requireEntries(path: Path, expected: Set<String>) {
        checkNotNull(directories[path]).let { check(it.names() == expected) }
    }
    private fun absent(path: Path): Boolean = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS); false
    } catch (_: NoSuchFileException) { true }
    private fun slot(index: Int): String = "slot-${index.toString().padStart(3, '0')}"

    @Synchronized override fun close(): LocalPhysicalSpoolClose {
        outcome?.let { return it }
        closing = true
        var complete = true
        for (actual in directories.values.toList().asReversed()) if (!actual.close()) complete = false
        return (if (complete) LocalPhysicalSpoolClose.CLOSED else LocalPhysicalSpoolClose.HELD).also { outcome = it }
    }
}

private class DistributedDesktopPhysicalCipher(
    private val namespace: DistributedDesktopPhysicalNamespace,
    private val keys: DistributedDesktopPhysicalKeys,
    private val keyName: String,
    private val directories: List<DistributedDesktopPhysicalDirectory>,
) : PhysicalSpoolCipher {
    override val identity: String
    init {
        namespace.requireFileCurrent(directories)
        identity = "distributed-desktop-physical-aes256-gcm/v1:" + keys.inspectKey(keyName) + ":" + keyName
        physicalSpoolTextBound(identity, 512)
    }
    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        namespace.requireFileCurrent(directories)
        val (nonce, sealed) = keys.encrypt(keyName, aad, plaintext)
        try {
            check(nonce.size == PHYSICAL_SPOOL_NONCE_BYTES && sealed.size == plaintext.size + PHYSICAL_SPOOL_TAG_BYTES)
            namespace.requireFileCurrent(directories)
            return physicalSpoolEnvelope(nonce, sealed)
        } finally { nonce.fill(0); sealed.fill(0) }
    }
    override fun open(envelope: ByteArray, aad: ByteArray): ByteArray = physicalSpoolNonce(envelope).withSpoolBytes { nonce ->
        namespace.requireFileCurrent(directories)
        envelope.copyOfRange(PHYSICAL_SPOOL_ENVELOPE_BYTES - PHYSICAL_SPOOL_TAG_BYTES, envelope.size).withSpoolBytes { sealed ->
            val plain = keys.decrypt(keyName, nonce, sealed, aad)
            var returned = false
            try {
                check(plain.size + PHYSICAL_SPOOL_ENVELOPE_BYTES == envelope.size)
                namespace.requireFileCurrent(directories)
                returned = true; plain
            } finally { if (!returned) plain.fill(0) }
        }
    }
}
