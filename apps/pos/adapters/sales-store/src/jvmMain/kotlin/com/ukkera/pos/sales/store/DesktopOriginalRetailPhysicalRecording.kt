package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.SelfOwnedMasterHostOwner
import com.ukkera.pos.security.DesktopMasterClientJournalCustody
import com.ukkera.pos.security.LocalSetupCompositionMode
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Genuine original desktop (Windows/macOS) Master SERVICE cohost backing for retail payment,
 * existing accepted RETAIL refund obligations and FOOD CASH collection, over the shared original
 * acquisition used by Android. The actual Master owns the SAME gate installed before activation.
 *
 * Every native input comes from the Master service's own [DesktopMasterClientJournalCustody]:
 * the fixed owner-only directory below its application data root (no caller path, no UI-process
 * store) and ONE non-exportable AES-256-GCM physical key in its OS custody, addressed through its
 * never-replaced secret-store index. The key is created only by explicit original enrollment,
 * after the installation directory was proved absent; reopen requires that exact key (its
 * key-check identity is bound into every native file identity). No key replacement, repair,
 * adoption, free-space sample or ordinary retry exists; incomplete enrollment stays held.
 *
 * macOS uses the JVM POSIX full-write/force profile (`unix:` attributes, 0700/0600). Windows uses
 * the NTFS full-write/flush profile inside the service-private directory (protected service DACL,
 * handle-observed file IDs and owner SID, mandatory exclusive locks). Neither falls back to the other.
 */
public object DesktopOriginalRetailPhysicalRecording {
    /** Bound once before master activation. This backend is not an authorizing core SPI. */
    public fun createBackend(owner: LocalPhysicalJournalExecutionOwner): LocalOriginalPhysicalJournalBackend =
        LocalOriginalPhysicalJournalBackend.awaitingOriginal(owner, Dispatchers.IO, Dispatchers.IO)

    public suspend fun acquire(host: SelfOwnedMasterHostOwner, original: AndroidOriginalLocalClient,
        backend: LocalOriginalPhysicalJournalBackend, custody: DesktopMasterClientJournalCustody,
        action: AndroidOriginalRetailPhysicalAction): Boolean {
        currentCoroutineContext().ensureActive()
        if (!original.belongsTo(host) || original.physicalEndpoint(backend.registeredOwner) == null) return false
        val mode = original.compositionMode
        val enroll = action == AndroidOriginalRetailPhysicalAction.ENROLL_ORIGINAL
        val profile = (if (mode == LocalSetupCompositionMode.TRAINING) DESKTOP_TRAINING_PROFILE else DESKTOP_LIVE_PROFILE)
            .forHost(windowsPhysicalProfile(custody.parentDirectory))
        val keyId = LocalOriginalRetailPhysicalAcquisition.originalPhysicalCatalogueId(original, "key", profile.catalogueIdDomain)
        val keyName = profile.keyNamespace + LocalOriginalRetailPhysicalAcquisition.originalPhysicalCatalogueId(original,
            "key-index", profile.catalogueIdDomain).value + "/" + keyId.value
        val stillOwned = { original.belongsTo(host) && original.physicalEndpoint(backend.registeredOwner) != null }
        val storageRoot = custody.parentDirectory.resolve(profile.containerName)
        val cipher = { DesktopMasterPhysicalSpoolCipher(custody, keyName) }
        return LocalOriginalRetailPhysicalAcquisition.acquire(original, backend, enroll, profile.recording, keyId, stillOwned,
            namespace = { owners, _ ->
                DesktopMasterPhysicalNamespace(custody, original, owners, enroll, keyName, storageRoot, stillOwned)
            },
            openCatalogue = { owner ->
                if (enroll) JvmPreallocatedPhysicalJournal.createCatalogue(storageRoot, owner, cipher)
                else JvmPreallocatedPhysicalJournal.openCatalogue(storageRoot, owner, cipher)
            })
    }
}

private class DesktopPhysicalProfile(val recording: OriginalPhysicalRecordingProfile, val containerName: String,
    val keyNamespace: String) {
    val catalogueIdDomain: String get() = recording.catalogueIdDomain
    /** Windows hosts use the NTFS profile; the catalogue domain is unchanged (same IDs, different native files). */
    fun forHost(windows: Boolean): DesktopPhysicalProfile = if (!windows) this else DesktopPhysicalProfile(
        OriginalPhysicalRecordingProfile(recording.catalogueIdDomain, LocalPhysicalSpoolStorageProfileV1.JVM_WINDOWS_NTFS_FULL_WRITE_FLUSH_V1),
        containerName, keyNamespace)
}

private val DESKTOP_LIVE_PROFILE = DesktopPhysicalProfile(OriginalPhysicalRecordingProfile(
    "ukkera.desktop.original-retail-physical-catalogue/v1", LocalPhysicalSpoolStorageProfileV1.JVM_POSIX_FULL_WRITE_FORCE_V1),
    "ukkera-desktop-master-physical-journals-v1", "ukkera.desktop-master.physical-journal-key.v1/")
private val DESKTOP_TRAINING_PROFILE = DesktopPhysicalProfile(OriginalPhysicalRecordingProfile(
    "ukkera.desktop.original-retail-physical-catalogue.training/v1", LocalPhysicalSpoolStorageProfileV1.JVM_POSIX_FULL_WRITE_FORCE_V1),
    "ukkera-desktop-master-physical-journals-training-v1", "ukkera.desktop-master.physical-journal-key.training.v1/")

/**
 * The service custody's existing non-exportable AES-256-GCM key: random 12-byte IV, 128-bit tag,
 * the physical engine's own domain-separated AAD. Only ciphertexts/tags cross this seam. Its
 * identity binds the stable key-check observation, so a different key can never reopen a file.
 */
internal class DesktopMasterPhysicalSpoolCipher(
    private val custody: DesktopMasterClientJournalCustody,
    private val keyName: String,
) : PhysicalSpoolCipher {
    override val identity: String

    init {
        check(!Thread.currentThread().isInterrupted)
        custody.requireCurrent()
        identity = "desktop-master-service-aes256-gcm/v1:" + custody.inspectKey(keyName)
        physicalSpoolTextBound(identity, 512)
    }

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        check(!Thread.currentThread().isInterrupted)
        val (nonce, sealed) = custody.encrypt(keyName, aad, plaintext)
        try {
            check(nonce.size == PHYSICAL_SPOOL_NONCE_BYTES && sealed.size == plaintext.size + PHYSICAL_SPOOL_TAG_BYTES)
            return physicalSpoolEnvelope(nonce, sealed)
        } finally {
            nonce.fill(0)
            sealed.fill(0)
        }
    }

    override fun open(envelope: ByteArray, aad: ByteArray): ByteArray = physicalSpoolNonce(envelope).withSpoolBytes { nonce ->
        check(!Thread.currentThread().isInterrupted && envelope.size >= PHYSICAL_SPOOL_ENVELOPE_BYTES)
        envelope.copyOfRange(PHYSICAL_SPOOL_ENVELOPE_BYTES - PHYSICAL_SPOOL_TAG_BYTES, envelope.size).withSpoolBytes { sealed ->
            val plain = custody.decrypt(keyName, nonce, sealed, aad)
            var transferred = false
            try {
                check(plain.size + PHYSICAL_SPOOL_ENVELOPE_BYTES == envelope.size)
                transferred = true
                plain
            } finally { if (!transferred) plain.fill(0) }
        }
    }

    override fun toString(): String = "DesktopMasterPhysicalSpoolCipher([REDACTED])"
}

/**
 * Bounded owner-only provisioning (explicit enrollment) or complete existing-only hold of the
 * fixed catalogue tree below the Master service custody directory. Every directory is a real,
 * link-free, non-symlinked, owner-only (0700) directory of the custody owner; enrollment creates
 * each one exclusively and forces it and its parent. Each identity is re-observed on every check.
 * Nothing is unlinked, repaired or adopted, and no directory handle outlives this preparation.
 */
private class DesktopMasterPhysicalNamespace(
    private val custody: DesktopMasterClientJournalCustody,
    private val original: AndroidOriginalLocalClient,
    private val owners: List<LocalPhysicalCatalogueBinding>,
    private val enroll: Boolean,
    private val keyName: String,
    private val container: Path,
    private val stillOwned: () -> Boolean,
) : OriginalPhysicalNativeNamespace {
    private val parent: Path = custody.parentDirectory
    private val stamps = linkedMapOf<Path, DirectoryStamp>()
    private var owner: String? = null
    private var closing = false
    private var closeResult: LocalPhysicalSpoolClose? = null

    override fun prepare() {
        requireCurrent()
        check(owners.size == 2 && owners.map { it.catalogueId }.distinct().size == 2 &&
            owners.all { it.scope == original.peer.scope && it.deviceId == original.peer.deviceId &&
                it.installationId == original.material.coordinates.installationId && it.keyId == owners.first().keyId })
        check(parent.isAbsolute && parent.normalize() == parent && parent.fileSystem === FileSystems.getDefault() &&
            container.parent == parent)
        hold(parent, parentDirectory = true)
        val root = container.resolve("physical-journal-v1")
        val installation = root.resolve(original.material.coordinates.installationId.value)
        if (enroll) {
            if (absent(container)) create(container, parent) else hold(container)
            if (absent(root)) create(root, container) else hold(root)
            check(absent(installation))
            create(installation, root)
        } else {
            hold(container)
            hold(root)
            hold(installation)
        }
        for (owner in owners) {
            val catalogue = installation.resolve(owner.catalogueId.value)
            val role = catalogue.resolve(owner.role.name.lowercase())
            if (enroll) { create(catalogue, installation); create(role, catalogue) }
            else { hold(catalogue); hold(role) }
            val leaves = listOf("anchor", "catalogue") + (0 until LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES).map(::desktopSlotName)
            for (name in leaves) {
                val leaf = role.resolve(name)
                if (enroll) create(leaf, role) else hold(leaf)
            }
            requireEntries(catalogue, setOf(owner.role.name.lowercase()))
            requireEntries(role, leaves.toSet())
            if (enroll) leaves.forEach { requireEntries(role.resolve(it), emptySet()) }
        }
        requireEntries(installation, owners.map { it.catalogueId.value }.toSet())
        // The physical key is never replaced. Enrollment creates it only after the installation
        // directory was proved absent and exclusively created; reopen requires the exact key.
        if (enroll) custody.createKey(keyName) else check(custody.keyExists(keyName))
        requireCurrent()
    }

    override fun requireCensus(owner: LocalPhysicalCatalogueBinding, census: LocalPhysicalCatalogueNativeCensus) {
        check(owner in owners)
        val role = container.resolve("physical-journal-v1").resolve(owner.installationId.value)
            .resolve(owner.catalogueId.value).resolve(owner.role.name.lowercase())
        requireEntries(role.resolve("anchor"), census.anchorFilenames)
        requireEntries(role.resolve("catalogue"), census.catalogueFilenames)
        for (index in 0 until LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES) {
            requireEntries(role.resolve(desktopSlotName(index)), census.journalFilenames[index] ?: emptySet())
        }
    }

    override fun requireCurrent() {
        check(!closing && !Thread.currentThread().isInterrupted && stillOwned() &&
            (!enroll || original.permitsFirstEnrollment()))
        custody.requireCurrent()
        for ((path, stamp) in stamps) check(DirectoryStamp.of(path) == stamp)
    }

    private fun hold(path: Path, parentDirectory: Boolean = false) {
        requireCurrent()
        check(path.isAbsolute && path.normalize() == path && path.toRealPath() == path && path !in stamps)
        val stamp = DirectoryStamp.of(path)
        if (parentDirectory) {
            check(stamp.noForeignWrite)
            owner = stamp.owner
        } else {
            check(stamp.owner == checkNotNull(owner) && stamp.ownerOnly)
        }
        stamps[path] = stamp
        requireCurrent()
    }

    private fun create(path: Path, parentPath: Path) {
        requireCurrent()
        check(enroll && path.parent == parentPath && parentPath in stamps && absent(path))
        // Exclusive; no mkdirs/repair. Windows children inherit the service directory's protected DACL.
        if (windows) Files.createDirectory(path) else Files.createDirectory(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        hold(path)
        force(path)
        force(parentPath)
        requireCurrent()
    }

    private fun force(path: Path) {
        check(path in stamps)
        // NTFS directory metadata is journaled; Java cannot flush a Windows directory handle.
        if (!windows) FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        check(DirectoryStamp.of(path) == stamps[path])
    }

    private fun requireEntries(path: Path, expected: Set<String>) {
        requireCurrent()
        check(path in stamps)
        val names = mutableSetOf<String>()
        Files.newDirectoryStream(path).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                check(names.size < expected.size && name in expected && names.add(name))
            }
        }
        check(names == expected)
        requireCurrent()
    }

    private val windows: Boolean = windowsPhysicalProfile(parent)

    private fun absent(path: Path): Boolean = try {
        Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS); false
    } catch (_: NoSuchFileException) { true }

    override fun close(): LocalPhysicalSpoolClose {
        closeResult?.let { return it }
        closing = true
        stamps.clear() // Identity observations only; no descriptor survives this preparation.
        return LocalPhysicalSpoolClose.CLOSED.also { closeResult = it }
    }
}

private val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")

private fun desktopSlotName(index: Int): String = "slot-${index.toString().padStart(3, '0')}"

/** POSIX: device/inode, uid and exact mode bits. Windows: volume serial/file ID and owner SID (no reparse point). */
private data class DirectoryStamp(val key: String, val owner: String, val ownerOnly: Boolean, val noForeignWrite: Boolean) {
    companion object {
        fun of(path: Path): DirectoryStamp {
            if (windowsPhysicalProfile(path)) {
                val observed = com.ukkera.pos.security.WindowsDesktopFiles.observe(path)
                check(observed.directory)
                // Access is the inherited protected service DACL, verified on the custody directory itself.
                return DirectoryStamp("ntfs:%016x:%s".format(observed.volumeSerial, observed.fileId), observed.ownerSid, true, true)
            }
            val attributes = Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther)
            val key = attributes.fileKey() ?: error("physical.desktop.directory_identity")
            val uid = (Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) as? Number)?.toLong()
                ?: error("physical.desktop.directory_owner")
            val permissions = attributes.permissions()
            return DirectoryStamp("${key.javaClass.name}:$key", uid.toString(), permissions == OWNER_ONLY,
                PosixFilePermission.GROUP_WRITE !in permissions && PosixFilePermission.OTHERS_WRITE !in permissions)
        }
    }
}
