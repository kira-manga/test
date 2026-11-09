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
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalEmployeeEnrollmentProfileV1
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.SelfOwnedStorageKeySecurity
import com.ukkera.pos.types.DomainId
import java.io.File
import java.io.FileDescriptor
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

public enum class AndroidOriginalRetailPhysicalAction {
    /** Explicit once-only admission on the actual acknowledged, untouched original C1. */
    ENROLL_ORIGINAL,
    /** Existing protected catalogues/journals only; no missing path or file is created. */
    REOPEN_EXISTING,
}

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
        val coordinates = original.material.coordinates
        val keySecurity = when (original.material.storageKeySecurity) {
            SelfOwnedStorageKeySecurity.SOFTWARE -> AndroidPhysicalSpoolKeySecurity.SOFTWARE
            SelfOwnedStorageKeySecurity.TRUSTED_ENVIRONMENT -> AndroidPhysicalSpoolKeySecurity.TRUSTED_ENVIRONMENT
            SelfOwnedStorageKeySecurity.STRONGBOX -> AndroidPhysicalSpoolKeySecurity.STRONGBOX
            SelfOwnedStorageKeySecurity.HARDWARE_KIND_UNSPECIFIED -> AndroidPhysicalSpoolKeySecurity.HARDWARE_KIND_UNSPECIFIED
        }
        val master = LocalPhysicalCatalogueBinding(coordinates.scope, coordinates.installationId,
            coordinates.deviceId, originalPhysicalCatalogueId(original, "master"), LocalPhysicalSpoolRole.MASTER,
            coordinates.storageKeyId)
        val client = LocalPhysicalCatalogueBinding(coordinates.scope, coordinates.installationId,
            coordinates.deviceId, originalPhysicalCatalogueId(original, "client"), LocalPhysicalSpoolRole.CLIENT,
            coordinates.storageKeyId)
        val identity = OriginalAndroidPhysicalOwnerIdentity(master, client,
            AuthorityIdentity(coordinates.assignmentId, coordinates.deviceId, original.receipt.core.authorityEpoch),
            coordinates.firstActorId, original.receipt.digest, original)
        val history = when (val consumed = original.originalConsumedPhysicalEmployees()) {
            is LocalCompanyAuthorityResult.Value -> consumed.value
            // Preserve the exact founding path. Missing actual history cannot census an
            // employee allocation or qualify an employee; no account/SDK/row fallback exists.
            is LocalCompanyAuthorityResult.Unavailable -> emptyList()
        }
        if (history.size > LocalEmployeeEnrollmentProfileV1.MAXIMUM_ACTIONS ||
            history.map { it.actorId }.distinct().size != history.size ||
            history.map { it.originalActionId }.distinct().size != history.size ||
            !history.all { it.belongsTo(original) }) return false
        val enrolled = backend.installOriginalAndroidOwners(identity, history) { attempt ->
            val enroll = action == AndroidOriginalRetailPhysicalAction.ENROLL_ORIGINAL
            if (!original.belongsTo(application) || original.physicalEndpoint(backend.registeredOwner) == null ||
                (enroll && !original.permitsFirstEnrollment())) return@installOriginalAndroidOwners false
            val namespace = OriginalAndroidPhysicalNamespace(application, original, listOf(master, client), history,
                enroll, composition)
            // Capture the preparation owner before its first mkdir/open/fsync. The same gate
            // mutex serializes master close, including cleanup of partial native acquisition.
            attempt.nativePreparation = namespace
            namespace.prepare()
            attempt.unreturnedNativeOwner = true
            val first = if (enroll) AndroidPreallocatedPhysicalJournal.createCatalogue(application, master, keySecurity, composition)
                else AndroidPreallocatedPhysicalJournal.openCatalogue(application, master, keySecurity, composition)
            if (first !is LocalPhysicalCatalogueOpen.Opened) {
                if (first === LocalPhysicalCatalogueOpen.Busy) attempt.unreturnedNativeOwner = false
                return@installOriginalAndroidOwners false
            }
            attempt.master = first.catalogue
            // The master is now captured; reserve this same uncertainty slot for the client.
            attempt.unreturnedNativeOwner = true
            val second = if (enroll) AndroidPreallocatedPhysicalJournal.createCatalogue(application, client, keySecurity, composition)
                else AndroidPreallocatedPhysicalJournal.openCatalogue(application, client, keySecurity, composition)
            if (second !is LocalPhysicalCatalogueOpen.Opened) {
                if (second === LocalPhysicalCatalogueOpen.Busy) attempt.unreturnedNativeOwner = false
                return@installOriginalAndroidOwners false
            }
            attempt.client = second.catalogue
            attempt.unreturnedNativeOwner = false
            val masterReceipts = namespace.verifyCatalogue(first.catalogue, attempt)
            val clientReceipts = namespace.verifyCatalogue(second.catalogue, attempt)
            requireMatchingOriginalPhysicalReceipts(masterReceipts, clientReceipts)
            if (!original.belongsTo(application) || original.physicalEndpoint(backend.registeredOwner) == null ||
                (enroll && !original.permitsFirstEnrollment())) return@installOriginalAndroidOwners false
            if (namespace.close() != LocalPhysicalSpoolClose.CLOSED) return@installOriginalAndroidOwners false
            attempt.nativePreparation = null
            true
        }
        currentCoroutineContext().ensureActive()
        return enrolled && original.belongsTo(application) && history.all { it.belongsTo(original) } &&
            original.physicalEndpoint(backend.registeredOwner) != null
    }
}

/** Receipt-bound fixed identities, never fresh random IDs chosen after a failed open. */
private fun originalPhysicalCatalogueId(original: AndroidOriginalLocalClient, role: String): DomainId {
    val coordinates = original.material.coordinates
    val body = listOf("ukkera.android.original-retail-physical-catalogue/v1",
        coordinates.scope.organizationId.value, coordinates.scope.companyId.value, coordinates.scope.branchId.value,
        coordinates.deviceId.value, coordinates.installationId.value, coordinates.storageKeyId.value,
        coordinates.firstActorId.value, original.receipt.core.originalRequestId.value, original.receipt.digest.value, role)
        .joinToString("\n").encodeToByteArray(throwOnInvalidSequence = true)
    return try {
        val hash = MessageDigest.getInstance("SHA-256").digest(body)
        try {
            val hex = buildString(32) {
                for (index in 0 until 16) {
                    val value = hash[index].toInt() and 255
                    append("0123456789abcdef"[value ushr 4]); append("0123456789abcdef"[value and 15])
                }
            }
            DomainId("${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
        } finally { hash.fill(0) }
    } finally { body.fill(0) }
}

private fun requireMatchingOriginalPhysicalReceipts(master: List<LocalPhysicalJournalReceipt>,
    client: List<LocalPhysicalJournalReceipt>) {
    val left = master.associateBy { it.binding.requirement.masterAllocationId }
    val right = client.associateBy { it.binding.requirement.masterAllocationId }
    check(left.size == master.size && right.size == client.size && left.keys == right.keys)
    for ((id, receipt) in left) {
        val paired = checkNotNull(right[id])
        check(receipt.binding.requirement.canonicalBytes == paired.binding.requirement.canonicalBytes &&
            receipt.binding.allocationId != paired.binding.allocationId &&
            receipt.nativeIdentity.fileIdentity != paired.nativeIdentity.fileIdentity &&
            receipt.nativeIdentity.directoryIdentity != paired.nativeIdentity.directoryIdentity)
    }
}

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
) : PhysicalJournalNativePreparation {
    private val uid = Process.myUid()
    private val pid = Process.myPid()
    private val directories = linkedMapOf<File, OriginalPhysicalDirectory>()
    private var data: File? = null
    private var framework: File? = null
    private var closing = false
    private var closeResult: LocalPhysicalSpoolClose? = null

    fun prepare() {
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

    /** Selectors come from the authenticated catalogue; filesystem bytes never supply a receipt. */
    fun verifyCatalogue(catalogue: LocalPhysicalAllocationCatalogue,
        attempt: PhysicalJournalGateOwnerAttempt): List<LocalPhysicalJournalReceipt> {
        requireCurrent()
        check(catalogue.binding in owners && catalogue.storageProfile == LocalPhysicalSpoolStorageProfileV1.ANDROID_API30_APP_PRIVATE_FALLOCATE_V1)
        val selectors = catalogue.retainedAllocationSelectors() ?: error("physical.original.catalogue")
        check(selectors.size <= LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES *
            LocalPhysicalJournalFormatV1.MAX_CATALOGUE_GENERATIONS && selectors.distinct().size == selectors.size)
        val receipts = ArrayList<LocalPhysicalJournalReceipt>(selectors.size)
        for (id in selectors) {
            requireCurrent()
            val receipt = catalogue.readExpectedReceipt(id) ?: error("physical.original.catalogue_receipt")
            val requirement = receipt.binding.requirement
            val foundingActor = requirement.originalActorId == original.material.coordinates.firstActorId
            val employee = if (foundingActor) null else employees.singleOrNull { witness ->
                witness.actorId == requirement.originalActorId && witness.belongsTo(original) &&
                    witness.authority == AuthorityIdentity(original.material.coordinates.assignmentId,
                        original.peer.deviceId, original.receipt.core.authorityEpoch) &&
                    witness.receiptDigest == original.receipt.digest && witness.firstActorId == original.material.coordinates.firstActorId &&
                    witness.installationId == catalogue.binding.installationId && witness.scope == requirement.scope &&
                    witness.deviceId == requirement.clientDeviceId
            }
            check(receipt.binding.owner == catalogue.binding && (foundingActor || employee != null) &&
                requirement.scope == original.peer.scope && requirement.masterDeviceId == original.peer.deviceId &&
                requirement.clientDeviceId == original.peer.deviceId &&
                requirement.masterStorageProfile == catalogue.storageProfile && requirement.clientStorageProfile == catalogue.storageProfile)
            val domain = localPhysicalDomainOwners(requirement)
            check((domain.domain === LocalRetailPaymentJournalDomain || domain.domain === LocalRetailRefundJournalDomain ||
                    domain.domain === LocalFoodCashJournalDomain || domain.domain === LocalSupplierCashJournalDomain) &&
                domain.authority == AuthorityIdentity(original.material.coordinates.assignmentId,
                    original.peer.deviceId, original.receipt.core.authorityEpoch) &&
                domain.master == owners.single { it.role == LocalPhysicalSpoolRole.MASTER } &&
                domain.client == owners.single { it.role == LocalPhysicalSpoolRole.CLIENT })
            attempt.unreturnedNativeOwner = true
            val opened = catalogue.openExisting(id, domain.domain)
            if (opened !is LocalPhysicalJournalOpen.Opened) {
                if (opened !== LocalPhysicalJournalOpen.Held) attempt.unreturnedNativeOwner = false
                error("physical.original.journal")
            }
            attempt.journals.add(opened.journal)
            attempt.unreturnedNativeOwner = false
            val snapshot = (opened.journal.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot
                ?: error("physical.original.journal_origin")
            check(opened.journal.receipt.sourceCommitment == receipt.sourceCommitment && snapshot.receipt.sourceCommitment == receipt.sourceCommitment)
            // A readable ALLOCATED/Started cell without an observation is still exactly that
            // retained state. It never becomes NOT_PAID, unused return rights or another instruction.
            check(opened.journal.close() == LocalPhysicalSpoolClose.CLOSED) { "physical.original.journal_close" }
            check(attempt.journals.remove(opened.journal))
            receipts.add(receipt)
        }
        check(receipts.size == selectors.size)
        val census = catalogue.nativeFileCensus()
        val role = roleDirectory(catalogue.binding)
        requireEntries(File(role, "anchor"), census.anchorFilenames)
        requireEntries(File(role, "catalogue"), census.catalogueFilenames)
        for (index in 0 until LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES) {
            requireEntries(File(role, slotName(index)), census.journalFilenames[index] ?: emptySet())
        }
        if (enroll) check(receipts.isEmpty())
        requireCurrent()
        return receipts
    }

    private fun roleDirectory(owner: LocalPhysicalCatalogueBinding): File {
        val parent = checkNotNull(framework)
        return File(File(File(File(parent, composition.journalDirectoryName), owner.installationId.value), owner.catalogueId.value), owner.role.name.lowercase())
    }

    private fun requireCurrent() {
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

private enum class OriginalPhysicalDirectoryRole { APP_DATA, FRAMEWORK_NO_BACKUP, PRIVATE }

/** No borrowed stream, close retry or descriptor replacement. */
private class OriginalPhysicalDirectory(private val path: File, private val role: OriginalPhysicalDirectoryRole) {
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

private data class OriginalPhysicalDirectoryStamp(val device: Long, val inode: Long, val uid: Int, val gid: Int, val mode: Int) {
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
