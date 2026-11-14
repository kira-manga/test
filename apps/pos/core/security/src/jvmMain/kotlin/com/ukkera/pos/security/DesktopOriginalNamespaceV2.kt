package com.ukkera.pos.security

/**
 * Windows/macOS original target namespace (V2): the owner-only target directory held under its
 * exclusive lock, intent/state/history files with verified atomic replacement, and the pinned Room
 * database directory. Same contract as the Android namespace; the identities are the V2 desktop
 * ones. No caller-supplied path; an existing directory is never adopted without its lock file.
 */
internal class DesktopOriginalNamespaceV2(
    private val files: DesktopFiles,
    private val environment: SelfOwnedOriginalEnvironment,
    private val work: AndroidOwnedInstallationWork,
    private val ownerToken: Any,
) : SelfOwnedOriginalDatabaseNamespace {
    private enum class Phase { NEW, OPEN, CLOSED }
    private var phase = Phase.NEW
    private var directory: DesktopLockedDirectory? = null
    private var databasePath: String = ""
    private var databaseStamp: DesktopStamp? = null
    private var databaseHandle: DesktopHandle? = null
    private val snapshots = linkedMapOf<String, Pair<DesktopStamp, ByteArray>?>(INTENT to null, STATE to null,
        HISTORY_V2 to null, HISTORY_ORIGIN to null)
    private val pins = linkedMapOf<OriginalDatabasePin, Pair<DesktopHandle, DesktopStamp>>()
    private val closedStablePins = linkedMapOf<OriginalDatabasePin, DesktopStamp>()
    private val transient = mutableListOf<DesktopHandle>()

    override var createdThisLifetime: Boolean = false
        private set

    override fun openNative(approval: SelfOwnedOriginalApproval?) {
        check(phase == Phase.NEW)
        environment.requireCurrent()
        approval?.let(::requireApproval)
        val absent = files.observe(files.child(files.baseDirectory, environment.profile.targetDirectoryName)) == null
        if (absent && approval == null) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val opened = DesktopLockedDirectory.open(files, environment.profile.targetDirectoryName, create = absent,
            allowed = setOf(INTENT, STATE, HISTORY_V2, HISTORY_ORIGIN, DATABASE_DIRECTORY))
        directory = opened
        try {
            createdThisLifetime = opened.createdThisLifetime
            databasePath = files.child(opened.path, DATABASE_DIRECTORY)
            if (createdThisLifetime) { requireApproval(requireNotNull(approval)); files.createDirectory(databasePath) }
            val database = files.observe(databasePath) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            opened.requireOwnedDirectory(database)
            desktopKnown(database.volume == opened.directory.volume)
            val handle = files.openDirectory(databasePath)
            databaseHandle = handle; databaseStamp = database
            desktopKnown(database.sameDirectory(handle.stamp()))
            if (createdThisLifetime) { desktopKnown(files.names(databasePath).isEmpty()); handle.sync(); opened.sync() }
            else desktopKnown(DATABASE_DIRECTORY in opened.names())
            for (name in snapshots.keys.toList()) snapshots[name] = opened.read(name, maximum(name))
            desktopKnown(createdThisLifetime || snapshots[INTENT] != null)
            desktopKnown(snapshots[STATE] == null || snapshots[INTENT] != null)
            desktopKnown(snapshots[HISTORY_V2] == null || snapshots[STATE] != null)
            phase = Phase.OPEN
            assertContinuity()
        } catch (failure: Throwable) {
            runCatching { databaseHandle?.close() }; runCatching { opened.close() }
            snapshots.values.forEach { it?.second?.fill(0) }
            phase = Phase.CLOSED
            throw failure
        }
    }

    override fun identity(): SelfOwnedOriginalNamespaceIdentity = SelfOwnedOriginalNamespaceIdentity.V2(identityV2())

    private fun identityV2(): SelfOwnedNativeNamespaceV2 {
        assertContinuity()
        val opened = requireNotNull(directory)
        return files.targetNamespace(opened.path, opened.volume, opened.directory, opened.lock, requireNotNull(databaseStamp))
    }

    override fun readIntent(): ByteArray? { assertContinuity(); return snapshots[INTENT]?.second?.copyOf() }
    override fun readState(): ByteArray? { assertContinuity(); return snapshots[STATE]?.second?.copyOf() }
    override fun retainIntent(bytes: ByteArray) = replace(INTENT, bytes)
    override fun retainState(bytes: ByteArray) = replace(STATE, bytes)

    override fun readHistoryAnchorBytes(): ByteArray? {
        assertContinuity()
        val actual = requireNotNull(directory).read(HISTORY_V2, maximum(HISTORY_V2))
        try {
            val expected = snapshots[HISTORY_V2]
            desktopKnown(if (expected == null) actual == null else actual != null &&
                expected.first.sameFile(actual.first) && expected.second.contentEquals(actual.second))
            return actual?.second?.copyOf()
        } finally { actual?.second?.fill(0) }
    }

    override fun retainHistoryAnchorBytes(bytes: ByteArray) = replace(HISTORY_V2, bytes)

    override fun retainHistoryOriginIntent(allocation: PayloadDigest) {
        desktopKnown(createdThisLifetime && snapshots[HISTORY_ORIGIN] == null && snapshots[HISTORY_V2] == null)
        val bytes = ("ukkera.original-history-intention/v2\n" + allocation.value).encodeToByteArray()
        try { replace(HISTORY_ORIGIN, bytes) } finally { bytes.fill(0) }
    }

    override fun prepareRoomDatabaseFile(original: NativeHistoryAnchorRecordV2): String {
        environment.requireExistingOriginalObservation(); assertContinuity()
        desktopKnown(pins.isEmpty() && closedStablePins.isEmpty())
        val path = databaseFile()
        val binding = original.nativeDatabase
        if (binding != null) {
            requireDatabaseIdentity(files.observe(path) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN), binding)
            ensureRoomCoordinationLock()
            return path
        }
        desktopKnown(original.attempt?.phase == NativeHistoryAttemptPhaseV2.SQL_SUBMISSION_ARMED &&
            original.parent == null && original.pending == null && files.names(databasePath).isEmpty())
        val handle = files.createFile(path)
        transient += handle
        val observed = handle.stamp()
        desktopKnown(observed.sameIdentity(files.observe(path) ?: observed.copy(fileId = "")))
        ensureRoomCoordinationLock()
        handle.sync(); requireNotNull(databaseHandle).sync()
        // The create handle is retained until native close; restart never adopts a missing bind.
        return path
    }

    /** Room's JVM coordination lock would otherwise be created with the process umask; the
     * owner-only namespace refuses any group/world-readable entry, so it is pre-created here. */
    private fun ensureRoomCoordinationLock() {
        val lock = files.child(databasePath, ROOM_COORDINATION_LOCK)
        val existing = files.observe(lock)
        if (existing != null) {
            requireNotNull(directory).requireOwnedFile(existing, 0L, allowEmpty = true); return
        }
        files.createFile(lock).use { it.sync() }
    }

    override fun observeRoomDatabaseIdentity(path: String, roomIdentity: String): NativeHistoryDatabaseIdentity {
        environment.requireExistingOriginalObservation(); assertContinuity()
        desktopKnown(path == databaseFile())
        val value = files.observe(path) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val identity = files.databaseIdentity(value, historyNamespaceDigest(), roomIdentity)
        requireDatabaseIdentity(value, identity)
        return identity
    }

    override fun historyNamespaceDigest(): PayloadDigest {
        val value = identityV2()
        val writer = NativeHistoryWriterV2(4096)
        return try {
            writer.string("ukkera.original-history-namespace/desktop-v2")
            writer.string(value.platform.name); writer.string(value.canonicalPath)
            when (value) {
                is SelfOwnedNativeNamespaceV2.Windows -> {
                    writer.string(value.ownerSid); writer.string(value.volumeId); writer.string(value.directoryFileId)
                    writer.string(value.lockFileId); writer.string(value.databaseDirectoryFileId)
                }
                is SelfOwnedNativeNamespaceV2.Mac -> {
                    writer.int(value.uid); writer.string(value.volumeUuid); writer.long(value.directoryInode)
                    writer.long(value.lockInode); writer.long(value.databaseDirectoryInode)
                }
            }
            val bytes = writer.finish()
            try { sha256(bytes) } finally { bytes.fill(0) }
        } finally { writer.clear() }
    }

    override fun pinOriginalDatabase(binding: NativeHistoryDatabaseIdentity, use: OriginalDatabaseUse): OriginalDatabasePin {
        environment.requireExistingOriginalObservation()
        assertContinuity()
        desktopKnown(pins.isEmpty() && closedStablePins.isEmpty() && binding.boundNamespaceDigest == historyNamespaceDigest())
        val path = databaseFile()
        val before = files.observe(path) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requireDatabaseIdentity(before, binding)
        if (use == OriginalDatabaseUse.STABLE_READ) requireStableFootprint(before)
        val handle = files.openFile(path, write = use == OriginalDatabaseUse.ROLLBACK_PAGER)
        val pin = OriginalDatabasePin(path, binding, use)
        pins[pin] = handle to before
        desktopKnown(before.sameIdentity(handle.stamp()))
        assertOriginalDatabasePin(pin)
        if (use == OriginalDatabaseUse.STABLE_READ) {
            val header = ByteArray(100)
            try {
                desktopKnown(handle.pread(header, 0L) == header.size)
                desktopKnown(header.copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))
                val encoded = ((header[16].toInt() and 255) shl 8) or (header[17].toInt() and 255)
                val pageSize = if (encoded == 1) 65_536 else encoded
                desktopKnown(pageSize in 512..65_536 && pageSize and (pageSize - 1) == 0 && before.size % pageSize == 0L)
                desktopKnown(header[18].toInt() == 1 && header[19].toInt() == 1)
            } finally { header.fill(0) }
            assertOriginalDatabasePin(pin)
        }
        return pin
    }

    override fun assertOriginalDatabasePin(pin: OriginalDatabasePin) {
        environment.requireExistingOriginalObservation()
        assertContinuity()
        val (handle, initial) = pins[pin] ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val held = handle.stamp(); val path = files.observe(pin.path) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requireDatabaseIdentity(held, pin.binding); requireDatabaseIdentity(path, pin.binding)
        desktopKnown(initial.sameIdentity(held) && initial.sameIdentity(path))
        if (pin.use == OriginalDatabaseUse.STABLE_READ) {
            desktopKnown(initial.sameFile(held) && initial.sameFile(path)); requireStableFootprint(path)
        }
    }

    override fun closeOriginalDatabasePin(pin: OriginalDatabasePin) {
        val retained = pins[pin] ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        try { retained.first.close() } catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
        if (pin.use == OriginalDatabaseUse.STABLE_READ) closedStablePins[pin] = retained.second
        pins.remove(pin)
    }

    override fun reobserveOriginalDatabaseAfterActualClose(pin: OriginalDatabasePin) {
        environment.requireExistingOriginalObservation()
        assertContinuity()
        desktopKnown(pin.use == OriginalDatabaseUse.STABLE_READ && pins.isEmpty() && closedStablePins.size == 1 &&
            pin.binding.boundNamespaceDigest == historyNamespaceDigest())
        val initial = closedStablePins[pin] ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        repeat(2) {
            val observed = files.observe(pin.path) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            requireDatabaseIdentity(observed, pin.binding)
            desktopKnown(initial.sameFile(observed)); requireStableFootprint(observed)
            if (it == 0) assertContinuity()
        }
        closedStablePins.remove(pin)
    }

    override fun assertContinuity() {
        desktopKnown(phase == Phase.OPEN)
        environment.requireCurrent()
        val opened = requireNotNull(directory)
        opened.assertContinuity()
        val database = requireNotNull(databaseStamp)
        desktopKnown(database.sameDirectory(files.observe(databasePath) ?: database.copy(fileId = "")) &&
            database.sameDirectory(requireNotNull(databaseHandle).stamp()))
        val names = opened.names()
        desktopKnown(DATABASE_DIRECTORY in names && names.none { it.endsWith(DesktopLockedDirectory.PENDING) })
        val databaseNames = files.names(databasePath)
        desktopKnown(databaseNames.size <= DATABASE_NAMES.size && databaseNames.all { it in DATABASE_NAMES })
        for (name in databaseNames) {
            val observed = files.observe(files.child(databasePath, name)) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            opened.requireOwnedFile(observed, Long.MAX_VALUE, allowEmpty = true)
            if (name == ROOM_COORDINATION_LOCK) desktopKnown(observed.size == 0L)
        }
        for ((name, expected) in snapshots) {
            val actual = files.observe(files.child(opened.path, name))
            desktopKnown(if (expected == null) actual == null else actual != null && expected.first.sameFile(actual))
        }
        environment.requireCurrent()
    }

    private fun replace(name: String, bytes: ByteArray) {
        assertContinuity()
        val opened = requireNotNull(directory)
        opened.replace(name, bytes, maximum(name))
        val actual = opened.read(name, maximum(name)) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (!actual.second.contentEquals(bytes)) { actual.second.fill(0); selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
        snapshots[name]?.second?.fill(0); snapshots[name] = actual
        assertContinuity()
    }

    override fun closeNative() {
        if (phase == Phase.CLOSED) return
        phase = Phase.CLOSED
        try {
            desktopKnown(pins.isEmpty())
            closedStablePins.clear()
            for (handle in transient.asReversed()) handle.close()
            transient.clear()
            databaseHandle?.close(); databaseHandle = null
            directory?.close(); directory = null
            snapshots.values.forEach { it?.second?.fill(0) }; snapshots.clear()
        } catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
    }

    private fun databaseFile(): String = files.child(databasePath, "master.db")

    private fun requireDatabaseIdentity(value: DesktopStamp, identity: NativeHistoryDatabaseIdentity) {
        requireNotNull(directory).requireOwnedFile(value, Long.MAX_VALUE, allowEmpty = true)
        desktopKnown(files.matchesDatabase(value, identity) && value.volume == requireNotNull(databaseStamp).volume)
    }

    private fun requireStableFootprint(database: DesktopStamp) {
        desktopKnown(database.size >= 100)
        desktopKnown(files.observe(files.child(databasePath, "master.db-wal")) == null &&
            files.observe(files.child(databasePath, "master.db-shm")) == null)
        val rollback = files.observe(files.child(databasePath, "master.db-journal"))
        desktopKnown(rollback == null || rollback.size == 0L)
    }

    private fun requireApproval(value: SelfOwnedOriginalApproval) {
        environment.requireCurrent()
        if (!value.current(ownerToken, environment, work::clock)) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    }

    private fun maximum(name: String): Int = when (name) {
        INTENT -> SelfOwnedNativeIdentityProfileV2.MAXIMUM_INTENT_BYTES
        STATE -> SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_BYTES
        HISTORY_ORIGIN -> 256
        HISTORY_V2 -> SelfOwnedNativeHistoryWireV2.MAX_ANCHOR_BYTES + SelfOwnedNativeHistoryWireV2.ENVELOPE_OVERHEAD
        else -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }

    override fun toString(): String = "DesktopOriginalNamespaceV2([REDACTED])"

    private companion object {
        const val DATABASE_DIRECTORY = "database"
        const val INTENT = "native-intent.v2"
        const val STATE = "native-state.v2"
        const val HISTORY_V2 = "native-history.v2"
        const val HISTORY_ORIGIN = "native-history-origin.v2"
        const val ROOM_COORDINATION_LOCK = "master.db.lck"
        val DATABASE_NAMES = setOf("master.db", "master.db-wal", "master.db-shm", "master.db-journal", ROOM_COORDINATION_LOCK)
    }
}
