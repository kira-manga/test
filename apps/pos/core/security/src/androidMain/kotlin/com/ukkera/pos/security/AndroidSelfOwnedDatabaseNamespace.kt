package com.ukkera.pos.security

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import android.app.Application
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.ukkera.pos.android.files.AndroidNativeFiles
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * ONE native target/database-directory namespace, not a SQLite/Room connection owner. Only
 * private noncreating original-file pins are opened here; no path/lock is loaned to a public
 * caller. All-writer history composition must retain this SAME owner until actual raw/Room close.
 */
internal class AndroidSelfOwnedDatabaseNamespace(
    private val application: Application,
    private val environment: AndroidSelfOwnedEnvironment,
    private val work: AndroidOwnedInstallationWork,
    private val ownerToken: Any,
): SelfOwnedOriginalDatabaseNamespace {
    private enum class Phase { NEW, OPENING, OPEN, CLOSE_STARTED, CLOSED }
    private var phase = Phase.NEW
    private val directory = File(environment.noBackupDirectory, environment.profile.targetDirectoryName)
    private val databaseDirectory = File(directory, DATABASE_DIRECTORY)
    private val descriptors = mutableListOf<OwnedDescriptor>()
    private val ancestors = linkedMapOf<File, Stamp>()
    private var dataDescriptor: OwnedDescriptor? = null
    private var parentDescriptor: OwnedDescriptor? = null
    private var directoryDescriptor: OwnedDescriptor? = null
    private var databaseDescriptor: OwnedDescriptor? = null
    private var directoryStamp: Stamp? = null
    private var databaseStamp: Stamp? = null
    private var lockDescriptor: OwnedDescriptor? = null
    private var lockChannel: FileChannel? = null
    private var lock: FileLock? = null
    private var lockStamp: Stamp? = null
    private var volumeUuid: String? = null
    private val snapshots = linkedMapOf<String, Snapshot?>(INTENT to null, STATE to null, HISTORY_V2 to null, HISTORY_ORIGIN to null)
    private val originalDatabasePins = linkedMapOf<OriginalDatabasePin, Pair<OwnedDescriptor, Stamp>>()
    // Identity observations only, never descriptors or proof that SQLite used this OS pin.
    // A stable read must consume its exact post-close observation before another pin opens.
    private val closedStableDatabasePins = linkedMapOf<OriginalDatabasePin, Stamp>()
    override var createdThisLifetime: Boolean = false
        private set

    /** Null approval never creates this owned target/database namespace or keys; no repair. */
    override fun openNative(approval: SelfOwnedNativeApproval?) {
        check(phase == Phase.NEW)
        phase = Phase.OPENING
        environment.requireCurrent()
        if (approval != null) requireApproval(approval)
        val chain = generateSequence(environment.noBackupDirectory) { it.parentFile }.toList().asReversed()
        for (path in chain) {
            val actual = stamp(Os.lstat(path.path))
            requireDirectory(actual, owned = false)
            if (path == environment.dataDirectory || path == environment.noBackupDirectory) known(actual.uid == environment.uid)
            ancestors[path] = actual
        }
        dataDescriptor = openDirectory(environment.dataDirectory, requireNotNull(ancestors[environment.dataDirectory]))
        parentDescriptor = openDirectory(environment.noBackupDirectory, requireNotNull(ancestors[environment.noBackupDirectory]))
        val absent = lstatOrNull(directory) == null
        if (absent) {
            if (approval == null) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            requireApproval(approval)
            Os.mkdir(directory.path, DIRECTORY_MODE)
            createdThisLifetime = true
        }
        val observed = stamp(Os.lstat(directory.path))
        requireDirectory(observed, owned = true)
        directoryStamp = observed
        directoryDescriptor = openDirectory(directory, observed)
        if (absent) Os.fchmod(requireNotNull(directoryDescriptor).fd, DIRECTORY_MODE)
        known(observed.sameDirectory(stamp(Os.fstat(requireNotNull(directoryDescriptor).fd))))

        val priorNames = androidOwnedDirectoryNames(directory, 6, work)
        known(priorNames.all { it in setOf(LOCK, INTENT, STATE, HISTORY_V2, HISTORY_ORIGIN, DATABASE_DIRECTORY) })
        // An existing directory without its original lock cannot mint a replacement lock inode.
        known(if (absent) priorNames.isEmpty() else LOCK in priorNames && DATABASE_DIRECTORY in priorNames)
        val lockFile = File(directory, LOCK)
        val descriptor = openDescriptor(lockFile, OsConstants.O_RDWR or OsConstants.O_NOFOLLOW or
            (if (absent) OsConstants.O_CREAT or OsConstants.O_EXCL else 0), FILE_MODE)
        lockDescriptor = descriptor
        val beforeLock = stamp(Os.fstat(descriptor.fd))
        requireFile(beforeLock, 0, allowEmpty = true)
        known(beforeLock.sameFile(stamp(Os.lstat(lockFile.path))))
        val stream = FileOutputStream(descriptor.fd)
        descriptor.streamOwner = stream
        val channel = stream.channel
        lockChannel = channel
        lock = channel.tryLock() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
        lockStamp = stamp(Os.fstat(descriptor.fd))
        known(requireNotNull(lockStamp).sameFile(stamp(Os.lstat(lockFile.path))))

        if (absent) {
            requireApproval(requireNotNull(approval))
            Os.mkdir(databaseDirectory.path, DIRECTORY_MODE)
        }
        val databaseObserved = stamp(Os.lstat(databaseDirectory.path))
        requireDirectory(databaseObserved, owned = true)
        databaseStamp = databaseObserved
        databaseDescriptor = openDirectory(databaseDirectory, databaseObserved)
        if (absent) Os.fchmod(requireNotNull(databaseDescriptor).fd, DIRECTORY_MODE)
        known(databaseObserved.sameDirectory(stamp(Os.fstat(requireNotNull(databaseDescriptor).fd))))
        known(databaseObserved.device == observed.device && requireNotNull(lockStamp).device == observed.device)
        volumeUuid = observeVolumeUuid()
        if (absent) {
            known(androidOwnedDirectoryNames(databaseDirectory, 0, work).isEmpty())
            Os.fsync(descriptor.fd); Os.fsync(requireNotNull(databaseDescriptor).fd)
            Os.fsync(requireNotNull(directoryDescriptor).fd); Os.fsync(requireNotNull(parentDescriptor).fd)
            Os.fsync(requireNotNull(dataDescriptor).fd)
        }
        checkNames()
        snapshots[INTENT] = readSnapshot(INTENT)
        snapshots[STATE] = readSnapshot(STATE)
        snapshots[HISTORY_V2] = readSnapshot(HISTORY_V2)
        snapshots[HISTORY_ORIGIN] = readSnapshot(HISTORY_ORIGIN)
        // Only this lifetime's genuinely new namespace may lack its original prior intent.
        known(absent || snapshots[INTENT] != null)
        known(snapshots[STATE] == null || snapshots[INTENT] != null)
        known(snapshots[HISTORY_V2] == null || snapshots[STATE] != null)
        phase = Phase.OPEN
        assertContinuity()
    }

    override fun identity(): SelfOwnedNativeNamespaceV1 {
        assertContinuity()
        return SelfOwnedNativeNamespaceV1(directory.path, requireNotNull(volumeUuid), environment.uid,
            requireNotNull(directoryStamp).inode, requireNotNull(lockStamp).inode, requireNotNull(databaseStamp).inode)
    }

    override fun readIntent(): ByteArray? { assertContinuity(); return snapshots[INTENT]?.bytes?.copyOf() }
    override fun readState(): ByteArray? { assertContinuity(); return snapshots[STATE]?.bytes?.copyOf() }
    override fun retainIntent(bytes: ByteArray): Unit = replaceFile(INTENT, bytes)
    override fun retainState(bytes: ByteArray): Unit = replaceFile(STATE, bytes)

    /** Actual V2 companion reread; N-only intent/state signing domains remain unchanged. */
    override fun readHistoryAnchorBytes(): ByteArray? {
        assertContinuity()
        val actual = readSnapshot(HISTORY_V2)
        try {
            val expected = snapshots[HISTORY_V2]
            known(if (expected == null) actual == null else actual != null &&
                expected.stamp.sameFile(actual.stamp) && expected.bytes.contentEquals(actual.bytes))
            return actual?.bytes?.copyOf()
        } finally { actual?.clear() }
    }
    override fun retainHistoryAnchorBytes(bytes: ByteArray): Unit = replaceFile(HISTORY_V2, bytes)
    override fun retainHistoryOriginIntent(allocation: PayloadDigest) {
        known(createdThisLifetime && snapshots[HISTORY_ORIGIN] == null && snapshots[HISTORY_V2] == null)
        val bytes = ("ukkera.original-history-intention/v2\n" + allocation.value).encodeToByteArray()
        try { replaceFile(HISTORY_ORIGIN, bytes) } finally { bytes.fill(0) }
    }

    /** Only after this exact original attempt's durable ARMED record. No caller-supplied path. */
    override fun prepareRoomDatabaseFile(original: NativeHistoryAnchorRecordV2): String {
        environment.requireExistingOriginalObservation(); assertContinuity()
        known(originalDatabasePins.isEmpty() && closedStableDatabasePins.isEmpty())
        val path = File(databaseDirectory, "master.db")
        val binding = original.database
        if (binding != null) {
            requireDatabaseIdentity(stamp(Os.lstat(path.path)), binding)
            return path.path
        }
        known(original.attempt?.phase == NativeHistoryAttemptPhaseV2.SQL_SUBMISSION_ARMED &&
            original.parent == null && original.pending == null && androidOwnedDirectoryNames(databaseDirectory, 0, work).isEmpty())
        val descriptor = openDescriptor(path, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL or
            OsConstants.O_NOFOLLOW, FILE_MODE)
        val observed = stamp(Os.fstat(descriptor.fd))
        known(observed.sameIdentity(stamp(Os.lstat(path.path))))
        Os.fchmod(descriptor.fd, FILE_MODE); Os.fsync(descriptor.fd); Os.fsync(requireNotNull(databaseDescriptor).fd)
        // Retain the create descriptor through the real SQLite/Room lifetime. No missing-file
        // branch can adopt it on restart if native DATABASE_BOUND was never retained.
        return path.path
    }

    override fun observeRoomDatabaseIdentity(path: String, roomIdentity: String): NativeHistoryDatabaseIdentityV2 {
        environment.requireExistingOriginalObservation(); assertContinuity()
        known(path == File(databaseDirectory, "master.db").path)
        val value = stamp(Os.lstat(path))
        val identity = NativeHistoryDatabaseIdentityV2(value.device, value.inode, value.uid, historyNamespaceDigest(), roomIdentity)
        requireDatabaseIdentity(value, identity)
        return identity
    }

    /** This binds live namespace/inode coordinates, NOT stable A/D7 (which exclude inodes). */
    override fun historyNamespaceDigest(): PayloadDigest {
        val value = identity()
        val writer = NativeHistoryWriterV2(2048)
        return try {
            writer.string("ukkera.original-history-namespace/v2"); writer.string(value.canonicalPath)
            writer.string(value.volumeUuid); writer.int(value.applicationUid); writer.long(value.directoryInode)
            writer.long(value.lockInode); writer.long(value.databaseDirectoryInode)
            val bytes = writer.finish()
            try { sha256(bytes) } finally { bytes.fill(0) }
        } finally { writer.clear() }
    }


    /**
     * Existing original only. The actual descriptor is retained before the raw driver opens;
     * no caller supplies a path and neither branch may use O_CREAT. Pager preparation checks
     * identity/type/ownership, NOT stable page/header/size, so genuine hot rollback is possible.
     */
    override fun pinOriginalDatabase(binding: NativeHistoryDatabaseIdentityV2, use: OriginalDatabaseUse): OriginalDatabasePin {
        environment.requireExistingOriginalObservation() // Reject a creating/preparation context FIRST.
        assertContinuity()
        known(originalDatabasePins.isEmpty() && closedStableDatabasePins.isEmpty() &&
            binding.namespaceDigest == historyNamespaceDigest())
        val path = File(databaseDirectory, "master.db")
        val before = lstatOrNull(path)?.let(::stamp) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requireDatabaseIdentity(before, binding)
        if (use == OriginalDatabaseUse.STABLE_READ) requireStableDatabaseFootprint(before)
        val descriptor = openDescriptor(path, OsConstants.O_NOFOLLOW or
            if (use == OriginalDatabaseUse.STABLE_READ) OsConstants.O_RDONLY else OsConstants.O_RDWR, 0)
        val pin = OriginalDatabasePin(path.path, binding, use)
        originalDatabasePins[pin] = descriptor to before
        known(before.sameIdentity(stamp(Os.fstat(descriptor.fd))))
        assertOriginalDatabasePin(pin)
        if (use == OriginalDatabaseUse.STABLE_READ) {
            // This is only stable-reader preflight. It is NEVER used before rollback recovery.
            val header = ByteArray(100)
            try {
                known(Os.pread(descriptor.fd, header, 0, header.size, 0L) == header.size)
                val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
                known(header.copyOfRange(0,16).contentEquals(magic))
                val encodedPageSize = ((header[16].toInt() and 255) shl 8) or (header[17].toInt() and 255)
                val pageSize = if (encodedPageSize == 1) 65_536 else encodedPageSize
                known(pageSize in 512..65_536 && pageSize and (pageSize-1) == 0 && before.size % pageSize == 0L)
                known(header[18].toInt() == 1 && header[19].toInt() == 1) // Pinned non-WAL profile.
            } finally { header.fill(0) }
            assertOriginalDatabasePin(pin)
        }
        return pin
    }

    override fun assertOriginalDatabasePin(pin: OriginalDatabasePin) {
        environment.requireExistingOriginalObservation()
        assertContinuity()
        val (descriptor, initial) = originalDatabasePins[pin] ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val fd = stamp(Os.fstat(descriptor.fd)); val path = stamp(Os.lstat(pin.path))
        requireDatabaseIdentity(fd, pin.binding); requireDatabaseIdentity(path, pin.binding)
        known(initial.sameIdentity(fd) && initial.sameIdentity(path))
        if (pin.use == OriginalDatabaseUse.STABLE_READ) {
            known(initial.sameFile(fd) && initial.sameFile(path)); requireStableDatabaseFootprint(path)
        }
    }

    override fun closeOriginalDatabasePin(pin: OriginalDatabasePin) {
        val retained = originalDatabasePins[pin] ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        closeTransient(retained.first) // Unknown close retains the pin and requests process exclusion.
        if (pin.use == OriginalDatabaseUse.STABLE_READ) closedStableDatabasePins[pin] = retained.second
        originalDatabasePins.remove(pin)
    }

    /**
     * Consumes only an exact stable pin whose actual close returned. Reobserves the original
     * namespace/path/inode/size/times and non-WAL footprint AFTER SQLite/OS-pin close, without
     * creating or reopening SQLite. This does NOT qualify the driver's hidden SQLite FD race.
     */
    override fun reobserveOriginalDatabaseAfterActualClose(pin: OriginalDatabasePin) {
        environment.requireExistingOriginalObservation()
        assertContinuity()
        known(pin.use == OriginalDatabaseUse.STABLE_READ && originalDatabasePins.isEmpty() &&
            closedStableDatabasePins.size == 1 && pin.binding.namespaceDigest == historyNamespaceDigest())
        val initial = closedStableDatabasePins[pin] ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val observed = stamp(Os.lstat(pin.path))
        requireDatabaseIdentity(observed, pin.binding)
        known(initial.sameFile(observed)); requireStableDatabaseFootprint(observed)
        assertContinuity()
        // Recheck the fixed original path after the complete native namespace observation.
        val finalObservation = stamp(Os.lstat(pin.path))
        requireDatabaseIdentity(finalObservation, pin.binding)
        known(initial.sameFile(finalObservation)); requireStableDatabaseFootprint(finalObservation)
        closedStableDatabasePins.remove(pin)
    }

    private fun requireDatabaseIdentity(value: Stamp, binding: NativeHistoryDatabaseIdentityV2) {
        known(OsConstants.S_ISREG(value.mode) && value.mode and 4095 == FILE_MODE && value.links == 1L && value.size >= 0 &&
            value.uid == environment.uid && value.uid == binding.uid && value.device == binding.device && value.inode == binding.inode &&
            value.device == requireNotNull(databaseStamp).device)
    }
    private fun requireStableDatabaseFootprint(database: Stamp) {
        known(database.size >= 100)
        known(lstatOrNull(File(databaseDirectory,"master.db-wal")) == null && lstatOrNull(File(databaseDirectory,"master.db-shm")) == null)
        val rollback = lstatOrNull(File(databaseDirectory,"master.db-journal"))
        known(rollback == null || rollback.st_size == 0L) // P3 must not recover a hot journal.
    }

    /** File/directory/lock continuity only, NOT supported SQL schema or complete history. */
    override fun assertContinuity() {
        known(phase == Phase.OPEN)
        // One fixed, synchronous native observation. Fresh installed identity/deadline
        // checks bracket BOTH unchanged ancestry/descriptor/volume passes and the full
        // name/snapshot census. No callback, suspension or cached observation escapes.
        environment.requireCurrent()
        assertBaseNative(); checkNames(); checkSnapshotFiles(); assertBaseNative()
        environment.requireCurrent()
    }

    /** Standalone acquisition/write callers retain their own fresh observation boundary. */
    private fun assertBase() {
        environment.requireCurrent()
        assertBaseNative()
        environment.requireCurrent()
    }

    /** Only inside the fixed fresh-observation boundaries above. */
    private fun assertBaseNative() {
        for ((path, expected) in ancestors) known(expected.sameDirectory(stamp(Os.lstat(path.path))))
        known(requireNotNull(ancestors[environment.dataDirectory]).sameDirectory(stamp(Os.fstat(requireNotNull(dataDescriptor).fd))))
        known(requireNotNull(ancestors[environment.noBackupDirectory]).sameDirectory(stamp(Os.fstat(requireNotNull(parentDescriptor).fd))))
        val base = requireNotNull(directoryStamp); val database = requireNotNull(databaseStamp); val locked = requireNotNull(lockStamp)
        known(base.sameDirectory(stamp(Os.lstat(directory.path))) && base.sameDirectory(stamp(Os.fstat(requireNotNull(directoryDescriptor).fd))))
        known(database.sameDirectory(stamp(Os.lstat(databaseDirectory.path))) &&
            database.sameDirectory(stamp(Os.fstat(requireNotNull(databaseDescriptor).fd))))
        known(requireNotNull(lock).isValid && lockChannel?.isOpen == true)
        known(locked.sameFile(stamp(Os.lstat(File(directory, LOCK).path))) &&
            locked.sameFile(stamp(Os.fstat(requireNotNull(lockDescriptor).fd))))
        known(base.device == locked.device && base.device == database.device && observeVolumeUuidNative() == volumeUuid)
    }

    private fun observeVolumeUuid(): String {
        environment.requireCurrent()
        val volume = observeVolumeUuidNative()
        environment.requireCurrent()
        return volume
    }

    /** Actual OS volume observations; the calling fixed operation brackets currentness. */
    private fun observeVolumeUuidNative(): String {
        val base = AndroidNativeStorageVolume.uuidForPath(application, directory)
        known(AndroidNativeStorageVolume.uuidForPath(application, databaseDirectory) == base)
        return base.toString()
    }

    private fun checkNames(pending: String? = null) {
        val allowed = setOf(LOCK, INTENT, STATE, HISTORY_V2, HISTORY_ORIGIN, DATABASE_DIRECTORY) + listOfNotNull(pending)
        val names = androidOwnedDirectoryNames(directory, allowed.size, work)
        known(names.size <= allowed.size && names.toSet().size == names.size && names.all { it in allowed } &&
            LOCK in names && DATABASE_DIRECTORY in names)
        if (pending != null) known(pending in names)
        val databaseNames = androidOwnedDirectoryNames(databaseDirectory, DATABASE_NAMES.size, work)
        known(databaseNames.size <= DATABASE_NAMES.size && databaseNames.toSet().size == databaseNames.size &&
            databaseNames.all { it in DATABASE_NAMES })
        for (name in databaseNames) {
            val observed = stamp(Os.lstat(File(databaseDirectory, name).path))
            // Metadata safety only. This does NOT authenticate SQL bytes, pin database-file
            // history or establish a fresh store. P2 never opens or creates these files.
            known(OsConstants.S_ISREG(observed.mode) && observed.mode and 4095 == FILE_MODE &&
                observed.uid == environment.uid && observed.links == 1L && observed.size >= 0 &&
                observed.device == requireNotNull(databaseStamp).device)
            // Pinned Room3 creates this exact empty coordination file beside the DB.
            // It carries no native ownership, history, absence or recovery evidence.
            if (name == ROOM_COORDINATION_LOCK) known(observed.size == 0L)
        }
    }

    private fun checkSnapshotFiles() {
        for ((name, expected) in snapshots) {
            if (name == HISTORY_V2) {
                // Full authenticated companion read occurs at each native cut/reobservation,
                // not a 32MiB copy for every 64KiB database chunk. Namespace continuity is
                // still checked on each raw operation; original close rechecks complete bytes.
                val actual = lstatOrNull(File(directory,name))?.let(::stamp)
                known(if (expected == null) actual == null else actual != null && expected.stamp.sameFile(actual))
                continue
            }
            val actual = readSnapshot(name)
            try {
                known(if (expected == null) actual == null else actual != null &&
                    expected.stamp.sameFile(actual.stamp) && expected.bytes.contentEquals(actual.bytes))
            } finally { actual?.clear() }
        }
    }

    /** Exact known own force/rename/readback only. Never delete a pending or foreign file. */
    private fun replaceFile(name: String, bytes: ByteArray) {
        assertContinuity()
        known(name in snapshots && bytes.size in 1..maximum(name))
        val temporaryName = "$name.pending"
        val temporary = File(directory, temporaryName)
        val descriptor = openDescriptor(temporary, OsConstants.O_RDWR or OsConstants.O_NOFOLLOW or
            OsConstants.O_CREAT or OsConstants.O_EXCL, FILE_MODE)
        val created = stamp(Os.fstat(descriptor.fd))
        requireFile(created, maximum(name), allowEmpty = true)
        known(created.size == 0L && created.sameFile(stamp(Os.lstat(temporary.path))))
        try {
            var offset = 0
            while (offset < bytes.size) {
                val written = Os.write(descriptor.fd, bytes, offset, bytes.size - offset)
                known(written > 0 && written <= bytes.size - offset); offset += written
            }
            Os.fsync(descriptor.fd)
            val written = stamp(Os.fstat(descriptor.fd))
            requireFile(written, maximum(name), allowEmpty = false)
            known(created.sameIdentity(written) && written.size == bytes.size.toLong())
            Os.lseek(descriptor.fd, 0, OsConstants.SEEK_SET)
            val readback = readExact(descriptor.fd, bytes.size)
            try { known(readback.contentEquals(bytes)) } finally { readback.fill(0) }
            known(written.sameFile(stamp(Os.fstat(descriptor.fd))) && written.sameFile(stamp(Os.lstat(temporary.path))))
            assertBase(); checkNames(temporaryName); checkSnapshotFiles()
            closeTransient(descriptor)
            Os.rename(temporary.path, File(directory, name).path)
            Os.fsync(requireNotNull(directoryDescriptor).fd)
            val actual = readSnapshot(name) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            try {
                // Known own rename can change ctime, but not the written inode/mode/bytes.
                known(written.sameIdentity(actual.stamp) && written.size == actual.stamp.size &&
                    written.modified == actual.stamp.modified && actual.bytes.contentEquals(bytes))
                snapshots[name]?.clear(); snapshots[name] = actual
            } catch (failure: Throwable) { actual.clear(); throw failure }
            checkNames(); assertBase()
        } finally { if (!descriptor.closed) closeTransient(descriptor) }
    }

    private fun readSnapshot(name: String): Snapshot? {
        val path = File(directory, name); val found = lstatOrNull(path) ?: return null
        val expected = stamp(found)
        requireFile(expected, maximum(name), allowEmpty = false)
        val descriptor = openDescriptor(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        var bytes: ByteArray? = null
        var closeAttempted = false
        try {
            known(expected.sameFile(stamp(Os.fstat(descriptor.fd))))
            bytes = readExact(descriptor.fd, expected.size.toInt())
            known(expected.sameFile(stamp(Os.fstat(descriptor.fd))) && expected.sameFile(stamp(Os.lstat(path.path))))
            closeAttempted = true
            closeTransient(descriptor) // Do not transfer a buffer before actual close returns.
            return Snapshot(expected, requireNotNull(bytes)).also { bytes = null }
        } finally {
            bytes?.fill(0)
            if (!closeAttempted) closeTransient(descriptor) // An uncertain close is never retried.
        }
    }

    private fun readExact(fd: FileDescriptor, size: Int): ByteArray {
        val bytes = ByteArray(size); val tail = ByteArray(1)
        try {
            var offset = 0
            while (offset < size) {
                val count = Os.read(fd, bytes, offset, size - offset)
                known(count > 0 && count <= size - offset); offset += count
            }
            known(Os.read(fd, tail, 0, 1) == 0)
            return bytes
        } catch (failure: Throwable) { bytes.fill(0); throw failure }
        finally { tail.fill(0) }
    }

    private fun openDirectory(path: File, expected: Stamp): OwnedDescriptor {
        // O_DIRECTORY is not a public Android OsConstants field. Do not guess an ABI value.
        // Keep the open nonblocking/no-follow and validate the actual directory before use.
        known(OsConstants.S_ISDIR(expected.mode))
        val descriptor = openDescriptor(path,
            OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0)
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

    /** Actual native close only; a later Room loan MUST finish actual DB close before entering. */
    override fun closeNative() {
        if (phase == Phase.CLOSED) return
        if (phase == Phase.CLOSE_STARTED) {
            work.nativeClosureUnknown(); selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        }
        phase = Phase.CLOSE_STARTED
        try {
            known(originalDatabasePins.isEmpty()) // Raw connection/statement close must return first.
            closedStableDatabasePins.clear() // Only descriptor-free observations remain here.
            lock?.let { held -> known(held.isValid); held.release(); known(!held.isValid); lock = null }
            for (descriptor in descriptors.toList().asReversed()) closeTransient(descriptor)
            known(lockChannel?.isOpen != true)
            snapshots.values.forEach { it?.clear() }; snapshots.clear()
            phase = Phase.CLOSED
        } catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
    }

    private inner class OwnedDescriptor {
        var native: FileDescriptor? = null
        val fd: FileDescriptor get() = requireNotNull(native)
        var streamOwner: FileOutputStream? = null
        private var closeStarted = false
        var closed = false
            private set
        fun closeOnce() {
            if (closed) return
            if (closeStarted) { work.nativeClosureUnknown(); selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED) }
            closeStarted = true
            val actual = native
            if (actual == null) { closed = true; return }
            try {
                // Android FileOutputStream(fd) borrows fd; close its borrower/channel first.
                // The closeStarted guard forbids retry after either close stage is uncertain.
                streamOwner?.close()
                AndroidNativeFiles.close(actual)
                closed = true
            } catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
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
    private fun stamp(value: StructStat): Stamp = Stamp(value.st_dev, value.st_ino, value.st_uid, value.st_gid,
        value.st_mode, value.st_nlink, value.st_size, value.st_mtime, value.st_ctime)
    private fun requireDirectory(value: Stamp, owned: Boolean) {
        known(OsConstants.S_ISDIR(value.mode) && value.mode and 2 == 0)
        if (owned) known(value.uid == environment.uid && value.mode and 4095 == DIRECTORY_MODE)
    }
    private fun requireFile(value: Stamp, maximum: Int, allowEmpty: Boolean) {
        known(OsConstants.S_ISREG(value.mode) && value.uid == environment.uid && value.mode and 4095 == FILE_MODE &&
            value.links == 1L && value.size in (if (allowEmpty) 0L else 1L)..maximum.toLong() &&
            value.device == requireNotNull(directoryStamp).device)
    }
    private fun requireApproval(value: SelfOwnedNativeApproval) {
        environment.requireCurrent()
        if (value.owner !== ownerToken || value.bootCount != environment.bootCount || work.clock() >= value.expiresElapsedMillis ||
            !value.source.preparationBody.target.same(environment.target)) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    }
    private fun lstatOrNull(path: File): StructStat? = try { Os.lstat(path.path) } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT) null else throw failure
    }
    private fun maximum(name: String): Int = when (name) {
        INTENT -> SelfOwnedNativeInstallationFormatV1.MAXIMUM_INTENT_BYTES
        STATE -> SelfOwnedNativeInstallationFormatV1.MAXIMUM_STATE_BYTES
        HISTORY_ORIGIN -> 256
        HISTORY_V2 -> SelfOwnedNativeHistoryWireV2.MAX_ANCHOR_BYTES + SelfOwnedNativeHistoryWireV2.ENVELOPE_OVERHEAD
        else -> error("Unknown native target file role")
    }
    private fun known(value: Boolean) { if (!value) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }

    companion object {
        private const val DATABASE_DIRECTORY = "database"
        private const val LOCK = "lock.v1"
        private const val INTENT = "native-intent.v1"
        private const val STATE = "native-state.v1"
        private const val HISTORY_V2 = "native-history.v2"
        private const val HISTORY_ORIGIN = "native-history-origin.v2"
        private const val DIRECTORY_MODE = 448 // 0700
        private const val FILE_MODE = 384 // 0600
        // Room3 3.0.3 BaseRoomConnectionManager -> ExclusiveMutex -> FileLock
        // uses the resolved database filename plus ".lck" and leaves it on unlock.
        // The independent native namespace lease remains the ownership authority.
        private const val ROOM_COORDINATION_LOCK = "master.db.lck"
        private val DATABASE_NAMES = setOf("master.db", "master.db-wal", "master.db-shm", "master.db-journal", ROOM_COORDINATION_LOCK)
    }
}
