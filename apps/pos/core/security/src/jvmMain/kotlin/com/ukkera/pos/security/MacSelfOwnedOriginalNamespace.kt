package com.ukkera.pos.security

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Concrete macOS original-target namespace/storage primitive for LIVE and the two training
 * compositions. It owns one private APFS/HFS target directory, its fixed lock file, its fixed
 * database directory, the cross-process exclusive lease and the exact intent/journal files.
 *
 * This is native custody of files only. It is not an installation approval, P1 registry,
 * target key, boot-session observation, history owner or current Master. [openExisting] never
 * creates a missing target, lock, database directory or intent; [createNew] is the only entry
 * that may create the original namespace, and only with an explicit new source/nonce. A remnant
 * that is missing, unreadable, unknown or conflicting is preserved and reported as unresolved.
 */
internal class MacSelfOwnedOriginalNamespace private constructor(
    private val expectedTarget: SelfOwnedMacUiIdentityV2,
    private val supportDirectory: Path,
    private val directory: Path,
    private val databaseDirectory: Path,
    private val lockFile: Path,
    private var supportStamp: MacOriginalStamp,
    private var directoryStamp: MacOriginalStamp,
    private var lockStamp: MacOriginalStamp,
    private var databaseStamp: MacOriginalStamp,
    private var volumeUuid: String,
    private var intentSnapshot: MacOriginalSnapshot?,
    private var journalSnapshot: MacOriginalSnapshot?,
    private var intentValue: SelfOwnedNativeIntentV2?,
    private val supportDescriptor: MacOriginalFd,
    private val directoryDescriptor: MacOriginalFd,
    private val lockDescriptor: MacOriginalFd,
    private val databaseDescriptor: MacOriginalFd,
    val createdThisLifetime: Boolean,
) : AutoCloseable {
    private enum class Phase { OPEN, CLOSE_STARTED, CLOSED, CLOSE_UNCERTAIN }

    private var phase: Phase = Phase.OPEN

    /** Actual local namespace facts; no device number, credential or key material is exposed. */
    @Synchronized
    fun identity(): SelfOwnedNativeNamespaceV2.Mac {
        assertContinuity()
        return currentIdentity()
    }

    /** The one fixed database directory. It remains owned by this retained namespace lease. */
    @Synchronized
    fun databaseDirectoryPath(): String {
        assertContinuity()
        return databaseDirectory.toString()
    }

    /** Exact canonical V2 intent bytes or null before the one initial control write. */
    @Synchronized
    fun readIntent(): ByteArray? {
        assertContinuity()
        return intentSnapshot?.bytes?.copyOf()
    }

    /** Exact canonical V2 journal envelope bytes, without parsing or authenticating them here. */
    @Synchronized
    fun readJournal(): ByteArray? {
        assertContinuity()
        return journalSnapshot?.bytes?.copyOf()
    }

    /**
     * Replaces the single intent file atomically after the initial create. The source, nonce,
     * key ids, namespace and target are immutable; revisions advance only and no intent write is
     * possible after the binding-started marker.
     */
    @Synchronized
    fun replaceIntent(bytes: ByteArray) {
        assertContinuity()
        val previous = intentValue ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        macOriginalKnown(journalSnapshot == null)
        val next = validateIntent(bytes, previous)
        macOriginalKnown(next.revision > previous.revision)
        replaceFile(INTENT, bytes, SelfOwnedNativeIdentityProfileV2.MAXIMUM_INTENT_BYTES)
        intentValue = next
    }

    /**
     * Replaces or first installs the exact journal envelope file atomically. Envelope
     * authentication/decryption belongs to the separate journal owner; this storage helper only
     * enforces canonical framing, the completed-intent ordering and native file custody.
     */
    @Synchronized
    fun replaceJournal(bytes: ByteArray) {
        assertContinuity()
        requireJournalConsistent(bytes)
        replaceFile(JOURNAL, bytes, SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_BYTES)
    }

    /** Namespace/file continuity only; it does not prove a valid Room schema or a current Master. */
    @Synchronized
    fun assertContinuity() {
        MacOriginalCloseState.requireConfirmed()
        macOriginalKnown(phase == Phase.OPEN)
        assertBase(null)
        checkSnapshotFiles()
        assertBase(null)
    }

    /**
     * Releases the cross-process lease only after the owner confirms it is finished with this
     * namespace. A failed release or close is terminal; it is never retried or reported closed.
     */
    @Synchronized
    override fun close() {
        when (phase) {
            Phase.CLOSED -> return
            Phase.OPEN -> Unit
            Phase.CLOSE_STARTED, Phase.CLOSE_UNCERTAIN ->
                selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        }
        phase = Phase.CLOSE_STARTED
        var failure: Throwable? = null
        // Keep the flock descriptor (and therefore the kernel lease) open until every other
        // namespace descriptor has closed, then close it last. close(2) releases the lease only
        // when the close is actually confirmed.
        val closingOrder = listOf(databaseDescriptor, directoryDescriptor, supportDescriptor)
        for (descriptor in closingOrder) {
            try {
                descriptor.closeConfirmed()
            } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure.addSuppressed(problem)
            }
        }
        if (failure == null) {
            try {
                MacOriginalCloseState.releaseLease(lockDescriptor)
            } catch (problem: Throwable) {
                failure = problem
            }
        }
        intentSnapshot?.clear()
        journalSnapshot?.clear()
        intentSnapshot = null
        journalSnapshot = null
        intentValue = null
        if (failure != null) {
            phase = Phase.CLOSE_UNCERTAIN
            val unresolved = SelfOwnedFailureV1(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
            unresolved.addSuppressed(requireNotNull(failure))
            throw unresolved
        }
        phase = Phase.CLOSED
    }

    private fun initializeNewIntent(source: SelfOwnedNativeSourceV2, nonce: ByteArray) {
        assertContinuity()
        macOriginalKnown(intentSnapshot == null && journalSnapshot == null)
        val coordinates = source.coordinates
        val keys = listOf(
            SelfOwnedTargetKeyIntentV2(
                SelfOwnedTargetKeyRoleV1.TARGET_SIGNING, coordinates.targetSigningKeyId,
                SelfOwnedKeyIntentPhaseV1.NOT_STARTED, null, null, SelfOwnedOctetsV1(ByteArray(0)),
            ),
            SelfOwnedTargetKeyIntentV2(
                SelfOwnedTargetKeyRoleV1.STORAGE, coordinates.storageKeyId,
                SelfOwnedKeyIntentPhaseV1.NOT_STARTED, null, null, SelfOwnedOctetsV1(ByteArray(0)),
            ),
            SelfOwnedTargetKeyIntentV2(
                SelfOwnedTargetKeyRoleV1.PEPPER, coordinates.pepperKeyId,
                SelfOwnedKeyIntentPhaseV1.NOT_STARTED, null, null, SelfOwnedOctetsV1(ByteArray(0)),
            ),
        )
        val intent = SelfOwnedNativeIntentV2(
            0L, source, currentIdentity(), SelfOwnedOctetsV1(nonce), keys, false,
        )
        val bytes = SelfOwnedNativeInstallationWireV2.encodeIntent(intent)
        try {
            val validated = validateIntent(bytes, null)
            macOriginalKnown(validated.revision == 0L && !validated.bindingStarted &&
                validated.keys.all { it.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED })
            writeInitialIntent(bytes)
            intentValue = validated
        } finally {
            bytes.fill(0)
        }
    }

    private fun loadExisting() {
        assertBase(null)
        val intent = readSnapshot(INTENT)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val decoded = try {
            validateIntent(intent.bytes, null)
        } catch (failure: Throwable) {
            intent.clear()
            throw failure
        }
        intentSnapshot = intent
        intentValue = decoded
        val journal = readSnapshot(JOURNAL)
        if (journal != null) {
            try {
                requireJournalConsistent(journal.bytes)
                journalSnapshot = journal
            } catch (failure: Throwable) {
                journal.clear()
                throw failure
            }
        } else {
            // A retained binding-start marker without its first journal record is an interrupted
            // original transition. It is never repaired by creating a replacement state.
            macOriginalKnown(!(decoded.complete() && decoded.bindingStarted))
        }
        assertContinuity()
    }

    private fun writeInitialIntent(bytes: ByteArray) {
        assertContinuity()
        macOriginalKnown(intentSnapshot == null && journalSnapshot == null &&
            bytes.size in 1..SelfOwnedNativeIdentityProfileV2.MAXIMUM_INTENT_BYTES)
        val path = directory.resolve(INTENT)
        macOriginalKnown(MacOriginalNative.lstat(path) == null)
        val descriptor = MacOriginalNative.createExclusiveFile(path)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        macOriginalUsing(descriptor) {
            MacOriginalNative.fchmod(descriptor.fd, MacOriginalNative.FILE_MODE)
            macOriginalKnown(MacOriginalNative.fstat(descriptor.fd).size == 0L)
            MacOriginalNative.writeAll(descriptor.fd, bytes)
            MacOriginalNative.fsync(descriptor.fd)
            val written = requireOwnedFile(
                path, descriptor, SelfOwnedNativeIdentityProfileV2.MAXIMUM_INTENT_BYTES,
                allowEmpty = false, expectedDevice = directoryStamp.device,
            )
            macOriginalKnown(written.size == bytes.size.toLong())
            MacOriginalNative.rewind(descriptor.fd)
            val readback = MacOriginalNative.readExact(descriptor.fd, bytes.size)
            try {
                macOriginalKnown(readback.contentEquals(bytes))
            } finally {
                readback.fill(0)
            }
            macOriginalKnown(written.sameFile(MacOriginalNative.fstat(descriptor.fd)))
            macOriginalKnown(written.sameFile(
                MacOriginalNative.lstat(path)
                    ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN),
            ))
        }
        MacOriginalNative.fsync(directoryDescriptor.fd)
        val actual = readSnapshot(INTENT)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        try {
            macOriginalKnown(actual.bytes.contentEquals(bytes))
            intentSnapshot?.clear()
            intentSnapshot = actual
        } catch (failure: Throwable) {
            actual.clear()
            throw failure
        }
        assertContinuity()
    }

    /** Exact own temp file then atomic forced rename/readback. A remnant is never deleted. */
    private fun replaceFile(name: String, bytes: ByteArray, maximum: Int) {
        assertContinuity()
        macOriginalKnown(bytes.size in 1..maximum)
        val temporaryName = "$name.pending"
        val temporary = directory.resolve(temporaryName)
        macOriginalKnown(MacOriginalNative.lstat(temporary) == null)
        val descriptor = MacOriginalNative.createExclusiveFile(temporary)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val written = macOriginalUsing(descriptor) {
            MacOriginalNative.fchmod(descriptor.fd, MacOriginalNative.FILE_MODE)
            macOriginalKnown(MacOriginalNative.fstat(descriptor.fd).size == 0L)
            MacOriginalNative.writeAll(descriptor.fd, bytes)
            MacOriginalNative.fsync(descriptor.fd)
            val stamp = requireOwnedFile(
                temporary, descriptor, maximum, allowEmpty = false,
                expectedDevice = directoryStamp.device,
            )
            macOriginalKnown(stamp.size == bytes.size.toLong())
            MacOriginalNative.rewind(descriptor.fd)
            val readback = MacOriginalNative.readExact(descriptor.fd, bytes.size)
            try {
                macOriginalKnown(readback.contentEquals(bytes))
            } finally {
                readback.fill(0)
            }
            macOriginalKnown(stamp.sameFile(MacOriginalNative.fstat(descriptor.fd)))
            macOriginalKnown(stamp.sameFile(
                MacOriginalNative.lstat(temporary)
                    ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN),
            ))
            stamp
        }
        assertBase(temporaryName)
        MacOriginalNative.rename(temporary, directory.resolve(name))
        MacOriginalNative.fsync(directoryDescriptor.fd)
        val actual = readSnapshot(name)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        try {
            // rename keeps the written inode, mode and data; it does not change mtime. ctime may
            // advance for the directory entry change and is not used as content identity here.
            macOriginalKnown(actual.stamp.sameIdentity(written) && actual.stamp.size == written.size &&
                actual.stamp.modifiedSeconds == written.modifiedSeconds &&
                actual.stamp.modifiedNanos == written.modifiedNanos &&
                actual.bytes.contentEquals(bytes))
            if (name == INTENT) {
                intentSnapshot?.clear()
                intentSnapshot = actual
            } else {
                journalSnapshot?.clear()
                journalSnapshot = actual
            }
        } catch (failure: Throwable) {
            actual.clear()
            throw failure
        }
        assertContinuity()
    }

    private fun readSnapshot(name: String): MacOriginalSnapshot? {
        val path = directory.resolve(name)
        val before = MacOriginalNative.lstat(path) ?: return null
        macOriginalKnown(before.isRegularFile && before.uid == MacOriginalNative.effectiveUid() &&
            before.mode and 511 == MacOriginalNative.FILE_MODE && before.links == 1 &&
            before.size in 1..maximum(name).toLong() && before.device == directoryStamp.device)
        val descriptor = MacOriginalNative.openReadFile(path)
        var bytes: ByteArray? = null
        try {
            val opened = MacOriginalNative.fstat(descriptor.fd)
            macOriginalKnown(before.sameFile(opened))
            MacOriginalNative.requireNoExtendedAcl(descriptor.fd)
            bytes = MacOriginalNative.readExact(descriptor.fd, opened.size.toInt())
            macOriginalKnown(before.sameFile(MacOriginalNative.fstat(descriptor.fd)))
            macOriginalKnown(before.sameFile(
                MacOriginalNative.lstat(path)
                    ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN),
            ))
            val snapshot = MacOriginalSnapshot(before, bytes)
            bytes = null
            return snapshot
        } finally {
            try {
                descriptor.closeConfirmed()
            } finally {
                bytes?.fill(0)
            }
        }
    }

    private fun validateIntent(
        bytes: ByteArray,
        previous: SelfOwnedNativeIntentV2?,
    ): SelfOwnedNativeIntentV2 {
        val next = when (val result = SelfOwnedNativeInstallationWireV2.decodeIntent(bytes)) {
            is SelfOwnedInstalledIdentityResultV2.Data -> result.value
            is SelfOwnedInstalledIdentityResultV2.Refused ->
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        macOriginalKnown(next.namespace == currentIdentity())
        if (next.source.platform != SelfOwnedNativePlatformV2.MACOS ||
            !SelfOwnedInstalledIdentityV2.matches(next.source.installedTarget.target, expectedTarget)) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        }
        if (journalSnapshot != null) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        if (previous != null) {
            if (!next.source.same(previous.source) || next.nonce != previous.nonce ||
                next.namespace != previous.namespace ||
                next.keys.map { it.role to it.keyId } != previous.keys.map { it.role to it.keyId } ||
                next.revision <= previous.revision || previous.bindingStarted) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            }
        }
        return next
    }

    private fun requireJournalConsistent(bytes: ByteArray) {
        val envelope = when (val result = SelfOwnedNativeInstallationWireV2.decodeEnvelope(bytes)) {
            is SelfOwnedInstalledIdentityResultV2.Data -> result.value
            is SelfOwnedInstalledIdentityResultV2.Refused ->
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        try {
            val original = intentValue
                ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            if (!original.complete() || !original.bindingStarted) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            }
        } finally {
            envelope.clear()
        }
    }

    private fun assertBase(pending: String?) {
        MacOriginalCloseState.requireConfirmed()
        macOriginalKnown(phase == Phase.OPEN)
        macOriginalKnown(canonicalSupport(supportDirectory) == supportDirectory)
        val supportNow = MacOriginalNative.lstat(supportDirectory)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        macOriginalKnown(supportStamp.sameDirectory(supportNow) &&
            supportStamp.sameDirectory(MacOriginalNative.fstat(supportDescriptor.fd)))
        MacOriginalNative.requireNoExtendedAcl(supportDescriptor.fd)
        macOriginalKnown(MacOriginalNative.requireLocalVolume(supportDirectory).uuid == volumeUuid)

        val directoryNow = MacOriginalNative.lstat(directory)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        macOriginalKnown(directoryStamp.sameDirectory(directoryNow) &&
            directoryStamp.sameDirectory(MacOriginalNative.fstat(directoryDescriptor.fd)))
        MacOriginalNative.requireNoExtendedAcl(directoryDescriptor.fd)

        val databaseNow = MacOriginalNative.lstat(databaseDirectory)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        macOriginalKnown(databaseStamp.sameDirectory(databaseNow) &&
            databaseStamp.sameDirectory(MacOriginalNative.fstat(databaseDescriptor.fd)))
        MacOriginalNative.requireNoExtendedAcl(databaseDescriptor.fd)

        val lockNow = MacOriginalNative.lstat(lockFile)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        macOriginalKnown(lockStamp.sameFile(lockNow) &&
            lockStamp.sameFile(MacOriginalNative.fstat(lockDescriptor.fd)))
        MacOriginalNative.requireNoExtendedAcl(lockDescriptor.fd)

        macOriginalKnown(directoryStamp.device == lockStamp.device &&
            directoryStamp.device == databaseStamp.device)
        assertNames(pending)
    }

    private fun assertNames(pending: String?) {
        val allowed = setOf(LOCK, DATABASE, INTENT, JOURNAL) + listOfNotNull(pending)
        val names = macOriginalListNames(directory)
        macOriginalKnown(names.size == names.toSet().size && names.all { it in allowed } &&
            LOCK in names && DATABASE in names)
    }

    private fun checkSnapshotFiles() {
        for ((name, expected) in listOf(INTENT to intentSnapshot, JOURNAL to journalSnapshot)) {
            val actual = readSnapshot(name)
            try {
                if (expected == null) {
                    macOriginalKnown(actual == null)
                } else {
                    macOriginalKnown(actual != null && expected.stamp.sameFile(actual.stamp) &&
                        expected.bytes.contentEquals(actual.bytes))
                }
            } finally {
                actual?.clear()
            }
        }
    }

    private fun maximum(name: String): Int = when (name) {
        INTENT -> SelfOwnedNativeIdentityProfileV2.MAXIMUM_INTENT_BYTES
        JOURNAL -> SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_BYTES
        else -> error("Unknown macOS original file role")
    }

    private fun currentIdentity(): SelfOwnedNativeNamespaceV2.Mac {
        val directoryInode = directoryStamp.inode
        val lockInode = lockStamp.inode
        val databaseInode = databaseStamp.inode
        val path = directory.toString()
        macOriginalKnown(path.length in 1..SelfOwnedNativeIdentityProfileV2.MAXIMUM_PATH_BYTES &&
            setOf(directoryInode, lockInode, databaseInode).size == 3)
        return SelfOwnedNativeNamespaceV2.Mac(
            path, MacOriginalNative.effectiveUid(), volumeUuid,
            directoryInode, lockInode, databaseInode,
        )
    }

    companion object {
        /**
         * Bounded creation after an explicit original source/nonce intent. An existing target is
         * never adopted; a failed create leaves every partially created remnant in place.
         */
        fun createNew(
            profile: LocalCompositionSdkProfileV1,
            supportDirectory: Path,
            expectedTarget: SelfOwnedMacUiIdentityV2,
            source: SelfOwnedNativeSourceV2,
            nonce: ByteArray,
        ): MacSelfOwnedOriginalNamespace {
            MacOriginalNative.requireSupported()
            MacOriginalCloseState.requireConfirmed()
            requireCurrentTarget(expectedTarget)
            if (nonce.size != SelfOwnedNativeIdentityProfileV2.NONCE_BYTES) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
            }
            if (source.platform != SelfOwnedNativePlatformV2.MACOS ||
                !SelfOwnedInstalledIdentityV2.matches(source.installedTarget.target, expectedTarget)) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
            }
            val targetName = targetDirectoryName(profile)
            val support = canonicalSupport(supportDirectory)
            val descriptors = mutableListOf<MacOriginalFd>()
            var leaseDescriptor: MacOriginalFd? = null
            try {
                val supportDescriptor = MacOriginalNative.openDirectory(support).also { descriptors += it }
                val supportStamp = MacOriginalNative.fstat(supportDescriptor.fd)
                requireSupportDirectory(support, supportDescriptor, supportStamp)
                val supportVolume = MacOriginalNative.requireLocalVolume(support)

                val directory = support.resolve(targetName)
                macOriginalKnown(directory.parent == support)
                macOriginalKnown(MacOriginalNative.lstat(directory) == null)
                if (!MacOriginalNative.mkdir(directory)) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
                }
                val directoryDescriptor = MacOriginalNative.openDirectory(directory).also { descriptors += it }
                MacOriginalNative.fchmod(directoryDescriptor.fd, MacOriginalNative.DIRECTORY_MODE)
                val directoryStamp = requireOwnedDirectory(directory, directoryDescriptor)
                macOriginalKnown(directoryStamp.device == supportStamp.device)
                macOriginalKnown(MacOriginalNative.requireLocalVolume(directory).uuid == supportVolume.uuid)
                MacOriginalNative.fsync(directoryDescriptor.fd)
                MacOriginalNative.fsync(supportDescriptor.fd)

                val lockFile = directory.resolve(LOCK)
                val createdLock = MacOriginalNative.createExclusiveFile(lockFile)
                    ?: selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
                descriptors += createdLock
                leaseDescriptor = createdLock
                MacOriginalNative.fchmod(createdLock.fd, MacOriginalNative.FILE_MODE)
                val lockStamp = requireOwnedFile(
                    lockFile, createdLock, 0, allowEmpty = true, expectedDevice = directoryStamp.device,
                )
                MacOriginalNative.fsync(createdLock.fd)
                if (!MacOriginalNative.flockExclusive(createdLock.fd)) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
                }

                val databaseDirectory = directory.resolve(DATABASE)
                if (!MacOriginalNative.mkdir(databaseDirectory)) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
                }
                val databaseDescriptor = MacOriginalNative.openDirectory(databaseDirectory)
                    .also { descriptors += it }
                MacOriginalNative.fchmod(databaseDescriptor.fd, MacOriginalNative.DIRECTORY_MODE)
                val databaseStamp = requireOwnedDirectory(databaseDirectory, databaseDescriptor)
                macOriginalKnown(databaseStamp.device == directoryStamp.device &&
                    MacOriginalNative.lstat(lockFile)?.let(lockStamp::sameFile) == true)
                macOriginalKnown(macOriginalListNames(databaseDirectory).isEmpty())
                MacOriginalNative.fsync(databaseDescriptor.fd)
                MacOriginalNative.fsync(directoryDescriptor.fd)

                val volumeUuid = MacOriginalNative.requireLocalVolume(directory).uuid
                macOriginalKnown(volumeUuid == supportVolume.uuid)
                val namespace = MacSelfOwnedOriginalNamespace(
                    expectedTarget, support, directory, databaseDirectory, lockFile,
                    supportStamp, directoryStamp, lockStamp, databaseStamp, volumeUuid,
                    null, null, null, supportDescriptor, directoryDescriptor,
                    createdLock, databaseDescriptor, true,
                )
                try {
                    namespace.initializeNewIntent(source, nonce)
                    requireCurrentTarget(expectedTarget)
                    namespace.assertContinuity()
                    return namespace
                } catch (failure: Throwable) {
                    try {
                        namespace.close()
                    } catch (close: Throwable) {
                        failure.addSuppressed(close)
                    }
                    throw failure
                }
            } catch (failure: Throwable) {
                for (descriptor in descriptors.asReversed().filter { it !== leaseDescriptor }) {
                    try {
                        descriptor.closeConfirmed()
                    } catch (close: Throwable) {
                        failure.addSuppressed(close)
                    }
                }
                leaseDescriptor?.let { lease ->
                    try {
                        MacOriginalCloseState.releaseLease(lease)
                    } catch (close: Throwable) {
                        failure.addSuppressed(close)
                    }
                }
                throw failure
            }
        }

        /** Existing-only open. Missing lock, database directory or intent is never recreated. */
        fun openExisting(
            profile: LocalCompositionSdkProfileV1,
            supportDirectory: Path,
            expectedTarget: SelfOwnedMacUiIdentityV2,
        ): MacSelfOwnedOriginalNamespace {
            MacOriginalNative.requireSupported()
            MacOriginalCloseState.requireConfirmed()
            requireCurrentTarget(expectedTarget)
            val targetName = targetDirectoryName(profile)
            val support = canonicalSupport(supportDirectory)
            val descriptors = mutableListOf<MacOriginalFd>()
            var leaseDescriptor: MacOriginalFd? = null
            try {
                val supportDescriptor = MacOriginalNative.openDirectory(support).also { descriptors += it }
                val supportStamp = MacOriginalNative.fstat(supportDescriptor.fd)
                requireSupportDirectory(support, supportDescriptor, supportStamp)
                val supportVolume = MacOriginalNative.requireLocalVolume(support)

                val directory = support.resolve(targetName)
                macOriginalKnown(directory.parent == support)
                val directoryPathStamp = MacOriginalNative.lstat(directory)
                    ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
                macOriginalKnown(directoryPathStamp.isDirectory)
                val directoryDescriptor = MacOriginalNative.openDirectory(directory).also { descriptors += it }
                val directoryStamp = requireOwnedDirectory(directory, directoryDescriptor)
                macOriginalKnown(directoryPathStamp.sameDirectory(directoryStamp) &&
                    directoryStamp.device == supportStamp.device)
                val volumeUuid = MacOriginalNative.requireLocalVolume(directory).uuid
                macOriginalKnown(volumeUuid == supportVolume.uuid)

                val lockFile = directory.resolve(LOCK)
                val lockPathStamp = MacOriginalNative.lstat(lockFile)
                    ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                macOriginalKnown(lockPathStamp.isRegularFile)
                val lockDescriptor = MacOriginalNative.openReadWriteFile(lockFile).also {
                    descriptors += it
                    leaseDescriptor = it
                }
                val lockStamp = requireOwnedFile(
                    lockFile, lockDescriptor, 0, allowEmpty = true, expectedDevice = directoryStamp.device,
                )
                macOriginalKnown(lockPathStamp.sameFile(lockStamp))
                if (!MacOriginalNative.flockExclusive(lockDescriptor.fd)) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
                }

                val databaseDirectory = directory.resolve(DATABASE)
                val databasePathStamp = MacOriginalNative.lstat(databaseDirectory)
                    ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                macOriginalKnown(databasePathStamp.isDirectory)
                val databaseDescriptor = MacOriginalNative.openDirectory(databaseDirectory)
                    .also { descriptors += it }
                val databaseStamp = requireOwnedDirectory(databaseDirectory, databaseDescriptor)
                macOriginalKnown(databasePathStamp.sameDirectory(databaseStamp) &&
                    databaseStamp.device == directoryStamp.device)

                val namespace = MacSelfOwnedOriginalNamespace(
                    expectedTarget, support, directory, databaseDirectory, lockFile,
                    supportStamp, directoryStamp, lockStamp, databaseStamp, volumeUuid,
                    null, null, null, supportDescriptor, directoryDescriptor,
                    lockDescriptor, databaseDescriptor, false,
                )
                try {
                    namespace.loadExisting()
                    requireCurrentTarget(expectedTarget)
                    namespace.assertContinuity()
                    return namespace
                } catch (failure: Throwable) {
                    try {
                        namespace.close()
                    } catch (close: Throwable) {
                        failure.addSuppressed(close)
                    }
                    throw failure
                }
            } catch (failure: Throwable) {
                for (descriptor in descriptors.asReversed().filter { it !== leaseDescriptor }) {
                    try {
                        descriptor.closeConfirmed()
                    } catch (close: Throwable) {
                        failure.addSuppressed(close)
                    }
                }
                leaseDescriptor?.let { lease ->
                    try {
                        MacOriginalCloseState.releaseLease(lease)
                    } catch (close: Throwable) {
                        failure.addSuppressed(close)
                    }
                }
                throw failure
            }
        }

        private fun targetDirectoryName(profile: LocalCompositionSdkProfileV1): String {
            val name = when {
                profile === LocalMasterSdkProfileV1 -> SelfOwnedCompositionNames.LIVE_TARGET_DIRECTORY
                profile is LocalTrainingSdkProfileV1 ->
                    SelfOwnedCompositionNames.trainingTargetDirectory(profile.slot)
                else -> selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
            }
            macOriginalKnown(SelfOwnedCompositionNames.isTargetPath("/ukkera/$name"))
            return name
        }

        private fun canonicalSupport(value: Path): Path {
            if (!value.isAbsolute) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
            val normalized = value.normalize()
            if (normalized.nameCount == 0) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
            val real = try {
                normalized.toRealPath()
            } catch (_: NoSuchFileException) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            } catch (_: IOException) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            if (real != normalized) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            return real
        }

        private fun requireCurrentTarget(expected: SelfOwnedMacUiIdentityV2) {
            when (val result = MacInstalledApplicationIdentity.captureUi(expected)) {
                is SelfOwnedInstalledIdentityResultV2.Data -> {
                    if (!SelfOwnedInstalledIdentityV2.matches(expected, result.value)) {
                        selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
                    }
                }
                is SelfOwnedInstalledIdentityResultV2.Refused -> when (result.code) {
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH ->
                        selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE ->
                        selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
                    else -> selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
                }
            }
        }
    }
}

private class MacOriginalSnapshot(val stamp: MacOriginalStamp, val bytes: ByteArray) {
    fun clear() {
        bytes.fill(0)
    }
}

private fun requireSupportDirectory(path: Path, descriptor: MacOriginalFd, stamp: MacOriginalStamp) {
    macOriginalKnown(stamp.isDirectory && stamp.uid == MacOriginalNative.effectiveUid() &&
        stamp.mode and 511 == MacOriginalNative.DIRECTORY_MODE)
    macOriginalKnown(stamp.sameDirectory(MacOriginalNative.fstat(descriptor.fd)))
    macOriginalKnown(stamp.sameDirectory(
        MacOriginalNative.lstat(path)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED),
    ))
    MacOriginalNative.requireNoExtendedAcl(descriptor.fd)
}

private fun requireOwnedDirectory(path: Path, descriptor: MacOriginalFd): MacOriginalStamp {
    val stamp = MacOriginalNative.fstat(descriptor.fd)
    macOriginalKnown(stamp.isDirectory && stamp.uid == MacOriginalNative.effectiveUid() &&
        stamp.mode and 511 == MacOriginalNative.DIRECTORY_MODE)
    macOriginalKnown(stamp.sameDirectory(
        MacOriginalNative.lstat(path)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN),
    ))
    MacOriginalNative.requireNoExtendedAcl(descriptor.fd)
    return stamp
}

private fun requireOwnedFile(
    path: Path,
    descriptor: MacOriginalFd,
    maximum: Int,
    allowEmpty: Boolean,
    expectedDevice: Int,
): MacOriginalStamp {
    val stamp = MacOriginalNative.fstat(descriptor.fd)
    macOriginalKnown(stamp.isRegularFile && stamp.uid == MacOriginalNative.effectiveUid() &&
        stamp.mode and 511 == MacOriginalNative.FILE_MODE && stamp.links == 1 &&
        stamp.size in (if (allowEmpty) 0L else 1L)..maximum.toLong() &&
        stamp.device == expectedDevice)
    macOriginalKnown(stamp.sameFile(
        MacOriginalNative.lstat(path)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN),
    ))
    MacOriginalNative.requireNoExtendedAcl(descriptor.fd)
    return stamp
}

private fun <T> macOriginalUsing(descriptor: MacOriginalFd, block: () -> T): T {
    var failure: Throwable? = null
    try {
        return block()
    } catch (problem: Throwable) {
        failure = problem
        throw problem
    } finally {
        try {
            descriptor.closeConfirmed()
        } catch (close: Throwable) {
            if (failure != null) failure.addSuppressed(close) else throw close
        }
    }
}

private fun macOriginalKnown(value: Boolean) {
    if (!value) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
}

private const val DATABASE: String = "database"
private const val LOCK: String = "lock.v1"
private const val INTENT: String = "native-intent.v2"
private const val JOURNAL: String = "native-journal.v2"

private fun macOriginalListNames(path: Path): List<String> = try {
    Files.newDirectoryStream(path).use { stream -> stream.map { it.fileName.toString() } }
} catch (_: IOException) {
    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
}
