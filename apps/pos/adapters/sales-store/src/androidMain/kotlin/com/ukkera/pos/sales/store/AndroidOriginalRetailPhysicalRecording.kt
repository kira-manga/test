package com.ukkera.pos.sales.store

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.system.ErrnoException
import android.system.Os
import com.ukkera.pos.android.files.AndroidNativeFiles
import com.ukkera.pos.security.AndroidNativeKeyPolicy
import android.system.OsConstants
import android.system.StructStat
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.OriginalPhysicalEmployeeEnrollment
import com.ukkera.pos.database.belongsTo
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.SelfOwnedStorageKeySecurity
import java.io.File
import java.io.FileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Genuine original Android cohost backing for retail payment, existing accepted RETAIL refund
 * obligations and FOOD CASH collection. The actual master owns the SAME gate installed before
 * activation; employee/UI connection lifetime does not own its native close. Refund Start
 * still needs the genuine original sale/accepted obligation and today's paying custody.
 * Original rights/delivery qualification is not supplied here, and no FOOD refund, exchange,
 * remote-device or old-capacity authority is issued. Known/UNKNOWN facts and separately
 * authorized resolution use the same retained allocation, never another physical payout.
 * SQLite application remains separate from durable native original-event retention.
 *
 * The original authenticated AES key is reused with the existing physical domain-separated
 * AAD and exact signed KeyInfo expectation. No key generation/replacement, free-space sample,
 * safe-absence history, orphan adoption, capacity reclaim or ordinary physical retry exists.
 * Incomplete original enrollment/census is held. Existing nonphysical clients may still use
 * their own valid journals while this isolated physical connection remains unavailable.
 */
public object AndroidOriginalRetailPhysicalRecording {
    /** Bound once before master activation. This backend is not an authorizing core SPI. */
    public fun createBackend(owner: LocalPhysicalJournalExecutionOwner): LocalOriginalPhysicalJournalBackend =
        LocalOriginalPhysicalJournalBackend.awaitingOriginal(owner, Dispatchers.IO, Dispatchers.IO)

    public suspend fun acquire(application: Application, original: AndroidOriginalLocalClient,
        backend: LocalOriginalPhysicalJournalBackend, action: AndroidOriginalRetailPhysicalAction,
        composition: AndroidPhysicalCompositionNamespaceV1 = AndroidPhysicalCompositionNamespaceV1.LIVE): Boolean {
        currentCoroutineContext().ensureActive()
        if (!original.belongsTo(application) || original.physicalEndpoint(backend.registeredOwner) == null ||
            composition.mode != original.compositionMode) return false
        val keySecurity = when (original.material.storageKeySecurity) {
            SelfOwnedStorageKeySecurity.SOFTWARE -> AndroidPhysicalSpoolKeySecurity.SOFTWARE
            SelfOwnedStorageKeySecurity.TRUSTED_ENVIRONMENT -> AndroidPhysicalSpoolKeySecurity.TRUSTED_ENVIRONMENT
            SelfOwnedStorageKeySecurity.STRONGBOX -> AndroidPhysicalSpoolKeySecurity.STRONGBOX
            SelfOwnedStorageKeySecurity.HARDWARE_KIND_UNSPECIFIED -> AndroidPhysicalSpoolKeySecurity.HARDWARE_KIND_UNSPECIFIED
        }
        val enroll = action == AndroidOriginalRetailPhysicalAction.ENROLL_ORIGINAL
        return LocalOriginalRetailPhysicalAcquisition.acquire(original, backend, enroll, ANDROID_ORIGINAL_PHYSICAL_PROFILE,
            original.material.coordinates.storageKeyId,
            stillOwned = { original.belongsTo(application) && original.physicalEndpoint(backend.registeredOwner) != null },
            namespace = { owners, history -> OriginalAndroidPhysicalNamespace(application, original, owners, history, enroll, composition) },
            openCatalogue = { owner ->
                if (enroll) AndroidPreallocatedPhysicalJournal.createCatalogue(application, owner, keySecurity, composition)
                else AndroidPreallocatedPhysicalJournal.openCatalogue(application, owner, keySecurity, composition)
            })
    }
}

private val ANDROID_ORIGINAL_PHYSICAL_PROFILE = OriginalPhysicalRecordingProfile(
    "ukkera.android.original-retail-physical-catalogue/v1",
    LocalPhysicalSpoolStorageProfileV1.ANDROID_API30_APP_PRIVATE_FALLOCATE_V1)

/**
 * Bounded private provisioning and complete existing-only census. This object owns temporary
 * directory descriptors and any uncertain close. The native journal/catalogue objects retain
 * their own distinct locks/descriptors after preparation closes. Nothing is unlinked/repaired.
 */
private class OriginalAndroidPhysicalNamespace(
    private val application: Application,
    private val original: AndroidOriginalLocalClient,
    private val owners: List<LocalPhysicalCatalogueBinding>,
    private val employees: List<OriginalPhysicalEmployeeEnrollment>,
    private val enroll: Boolean,
    private val composition: AndroidPhysicalCompositionNamespaceV1,
) : OriginalPhysicalNativeNamespace {
    private val uid = Process.myUid()
    private val pid = Process.myPid()
    private val directories = linkedMapOf<File, OriginalPhysicalDirectory>()
    private var data: File? = null
    private var framework: File? = null
    private var closing = false
    private var closeResult: LocalPhysicalSpoolClose? = null

    override fun prepare() {
        requireCurrent()
        check(owners.size == 2 && owners.map { it.catalogueId }.distinct().size == 2 &&
            owners.all { it.scope == original.peer.scope && it.deviceId == original.peer.deviceId &&
                it.installationId == original.material.coordinates.installationId && it.keyId == original.material.coordinates.storageKeyId })
        val actualData = application.dataDir.canonicalFile
        data = actualData
        hold(actualData, OriginalPhysicalDirectoryRole.APP_DATA)
        val parent = File(actualData, "no_backup")
        framework = parent
        // Deliberately do not call the creating noBackupFilesDir getter.
        hold(parent, OriginalPhysicalDirectoryRole.FRAMEWORK_NO_BACKUP)
        val root = File(parent, composition.journalDirectoryName)
        val installation = File(root, original.material.coordinates.installationId.value)
        if (enroll) {
            check(absent(installation))
            if (absent(root)) create(root, parent) else hold(root, OriginalPhysicalDirectoryRole.PRIVATE)
            create(installation, root)
        } else {
            hold(root, OriginalPhysicalDirectoryRole.PRIVATE)
            hold(installation, OriginalPhysicalDirectoryRole.PRIVATE)
        }
        for (owner in owners) {
            val catalogue = File(installation, owner.catalogueId.value)
            val role = File(catalogue, owner.role.name.lowercase())
            if (enroll) { create(catalogue, installation); create(role, catalogue) }
            else { hold(catalogue, OriginalPhysicalDirectoryRole.PRIVATE); hold(role, OriginalPhysicalDirectoryRole.PRIVATE) }
            val leaves = listOf("anchor", "catalogue") + (0 until LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES).map(::slotName)
            for (name in leaves) {
                val leaf = File(role, name)
                if (enroll) create(leaf, role) else hold(leaf, OriginalPhysicalDirectoryRole.PRIVATE)
            }
            requireEntries(catalogue, setOf(owner.role.name.lowercase()))
            requireEntries(role, leaves.toSet())
            if (enroll) leaves.forEach { requireEntries(File(role, it), emptySet()) }
        }
        requireEntries(installation, owners.map { it.catalogueId.value }.toSet())
        requireCurrent()
    }

    /** The authenticated catalogue's own census; filesystem names never supply a receipt. */
    override fun requireCensus(owner: LocalPhysicalCatalogueBinding, census: LocalPhysicalCatalogueNativeCensus) {
        val role = roleDirectory(owner)
        requireEntries(File(role, "anchor"), census.anchorFilenames)
        requireEntries(File(role, "catalogue"), census.catalogueFilenames)
        for (index in 0 until LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES) {
            requireEntries(File(role, slotName(index)), census.journalFilenames[index] ?: emptySet())
        }
    }

    private fun roleDirectory(owner: LocalPhysicalCatalogueBinding): File {
        val parent = checkNotNull(framework)
        return File(File(File(File(parent, composition.journalDirectoryName), owner.installationId.value), owner.catalogueId.value), owner.role.name.lowercase())
    }

    override fun requireCurrent() {
        check(!closing && !Thread.currentThread().isInterrupted && Build.VERSION.SDK_INT >= AndroidNativeKeyPolicy.MINIMUM_ANDROID_API &&
            Process.myUid() == uid && Process.myPid() == pid && uid == application.applicationInfo.uid &&
            application.applicationContext === application && !application.isDeviceProtectedStorage &&
            application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 && original.belongsTo(application) &&
            composition.mode == original.compositionMode && (!enroll || original.permitsFirstEnrollment()))
        check(application.getSystemService(UserManager::class.java)?.isUserUnlocked == true)
        data?.let { check(application.dataDir.canonicalFile == it) }
        directories.values.forEach { it.requireCurrent(uid) }
    }

    private fun hold(path: File, role: OriginalPhysicalDirectoryRole) {
        requireCurrent()
        check(path.isAbsolute && path.absoluteFile == path.canonicalFile && path !in directories)
        val directory = OriginalPhysicalDirectory(path, role)
        directories[path] = directory // own the descriptor slot before native acquisition
        directory.open(uid)
        requireCurrent()
    }

    private fun create(path: File, parent: File) {
        requireCurrent()
        check(enroll && absent(path) && path.parentFile == parent)
        val heldParent = checkNotNull(directories[parent])
        heldParent.requireCurrent(uid)
        Os.mkdir(path.path, 0x1c0) // exclusive 0700; no mkdirs/repair/fallback
        hold(path, OriginalPhysicalDirectoryRole.PRIVATE)
        directories.getValue(path).force(uid)
        heldParent.force(uid)
        requireCurrent()
    }

    private fun requireEntries(path: File, expected: Set<String>) {
        requireCurrent()
        val directory = checkNotNull(directories[path])
        directory.requireCurrent(uid)
        val names = mutableSetOf<String>()
        try {
            AndroidNativeFiles.openDirectory(path.path).use { cursor ->
                while (true) {
                    val name = cursor.nextName() ?: break
                    check(names.size < expected.size && name in expected && names.add(name))
                }
            }
        } catch (failure: Throwable) {
            if (failure is AndroidNativeFiles.ClosureUncertainException ||
                failure.suppressed.any { it is AndroidNativeFiles.ClosureUncertainException }) directory.markClosureUncertain()
            throw failure
        }
        check(names == expected)
        directory.requireCurrent(uid)
        requireCurrent()
    }

    private fun absent(path: File): Boolean = try { Os.lstat(path.path); false }
        catch (missing: ErrnoException) { if (missing.errno == OsConstants.ENOENT) true else throw missing }

    override fun close(): LocalPhysicalSpoolClose {
        closeResult?.let { return it }
        closing = true
        var complete = true
        directories.values.toList().asReversed().forEach { if (it.close() != LocalPhysicalSpoolClose.CLOSED) complete = false }
        return (if (complete) LocalPhysicalSpoolClose.CLOSED else LocalPhysicalSpoolClose.HELD).also { closeResult = it }
    }
}

private fun slotName(index: Int): String = "slot-${index.toString().padStart(3, '0')}"

internal enum class OriginalPhysicalDirectoryRole { APP_DATA, FRAMEWORK_NO_BACKUP, PRIVATE }

/** No borrowed stream, close retry or descriptor replacement. */
internal class OriginalPhysicalDirectory(private val path: File, private val role: OriginalPhysicalDirectoryRole) {
    private var descriptor: FileDescriptor? = null
    private var stamp: OriginalPhysicalDirectoryStamp? = null
    private var closing = false
    private var result: LocalPhysicalSpoolClose? = null

    fun open(uid: Int) {
        check(descriptor == null && !closing)
        val before = OriginalPhysicalDirectoryStamp.from(Os.lstat(path.path)).also { it.require(role, uid) }
        descriptor = try { AndroidNativeFiles.open(path.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0) }
        catch (failure: AndroidNativeFiles.ClosureUncertainException) { closing = true; throw failure }
        stamp = before
        requireCurrent(uid)
    }

    fun requireCurrent(uid: Int) {
        check(!closing && path.absoluteFile == path.canonicalFile)
        val fd = checkNotNull(descriptor)
        val expected = checkNotNull(stamp)
        check(fd.valid())
        expected.require(role, uid)
        check(OriginalPhysicalDirectoryStamp.from(Os.fstat(fd)) == expected &&
            OriginalPhysicalDirectoryStamp.from(Os.lstat(path.path)) == expected)
        val flags = AndroidNativeFiles.statusFlags(fd)
        check(flags and OsConstants.O_ACCMODE == OsConstants.O_RDONLY && flags and OsConstants.O_APPEND == 0 &&
            AndroidNativeFiles.closeOnExec(fd))
    }

    fun markClosureUncertain() { closing = true }

    fun force(uid: Int) { requireCurrent(uid); Os.fsync(checkNotNull(descriptor)); requireCurrent(uid) }

    fun close(): LocalPhysicalSpoolClose {
        result?.let { return it }
        if (closing) return LocalPhysicalSpoolClose.HELD
        closing = true
        return try {
            descriptor?.let(AndroidNativeFiles::close)
            LocalPhysicalSpoolClose.CLOSED
        } catch (_: Exception) { LocalPhysicalSpoolClose.HELD }.also { result = it }
    }
}

internal data class OriginalPhysicalDirectoryStamp(val device: Long, val inode: Long, val uid: Int, val gid: Int, val mode: Int) {
    fun require(role: OriginalPhysicalDirectoryRole, actualUid: Int) {
        check(OsConstants.S_ISDIR(mode) && uid == actualUid)
        when (role) {
            OriginalPhysicalDirectoryRole.APP_DATA -> check(mode and 0x12 == 0)
            OriginalPhysicalDirectoryRole.FRAMEWORK_NO_BACKUP -> check(gid == actualUid && (mode and 0xfff == 0x1f9 || mode and 0xfff == 0x1c0))
            OriginalPhysicalDirectoryRole.PRIVATE -> check(gid == actualUid && mode and 0xfff == 0x1c0)
        }
    }

    companion object {
        fun from(stat: StructStat): OriginalPhysicalDirectoryStamp =
            OriginalPhysicalDirectoryStamp(stat.st_dev, stat.st_ino, stat.st_uid, stat.st_gid, stat.st_mode)
    }
}
