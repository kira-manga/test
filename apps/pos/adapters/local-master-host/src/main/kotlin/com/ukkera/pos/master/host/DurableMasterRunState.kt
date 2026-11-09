package com.ukkera.pos.master.host

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.store.ControlledLocalRestore
import com.ukkera.pos.recovery.store.JvmPrivateFileLock
import com.ukkera.pos.recovery.store.RestoreInstallIntent
import com.ukkera.pos.recovery.store.VerifiedLocalBackup
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val hostJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }
internal const val MAX_RUN_STATE_BYTES = 128 * 1024
private val ownerDirectory = PosixFilePermissions.fromString("rwx------")
private val ownerFile = PosixFilePermissions.fromString("rw-------")

@Serializable
internal enum class RunPhase { CLEAN_STOPPED, CUSTODY_ACQUIRING, STARTING, ACTIVE, STOPPING, MAINTENANCE, RESTORE_HELD }

@Serializable
internal data class MaintenanceRecord(
    val loanId: DomainId,
    val decisionId: DomainId,
    val subjectId: DomainId,
    val authorizationKind: String,
    val restore: ControlledLocalRestore?,
    val selectedBackup: VerifiedLocalBackup?,
)

@Serializable
internal data class HelperRecord(
    val loanId: DomainId,
    val stagePath: String,
    val maximumRunMillis: Long,
    val processId: Long? = null,
    val processStartEpochMillis: Long? = null,
)

@Serializable
internal data class RestoreIntentRecord(
    val request: ControlledLocalRestore,
    val backup: VerifiedLocalBackup,
    val installedDatabaseSha256: String,
    val previousBackup: VerifiedLocalBackup?,
) {
    fun value(): RestoreInstallIntent = RestoreInstallIntent(request, backup, installedDatabaseSha256, previousBackup)
    companion object {
        fun capture(value: RestoreInstallIntent): RestoreIntentRecord = RestoreIntentRecord(
            value.request, value.backup, value.installedDatabaseSha256, value.previousBackup)
    }
}

@Serializable
internal data class RunRecord(
    val version: Int,
    val installationId: DomainId,
    val localDeviceId: DomainId,
    val scope: BranchScope,
    val authority: AuthorityIdentity,
    val databasePath: String,
    val backupDirectory: String,
    val revision: Long,
    val phase: RunPhase,
    val acquisitionId: DomainId?,
    val serviceIncarnationId: DomainId?,
    val maintenance: MaintenanceRecord?,
    val helper: HelperRecord?,
    val intent: RestoreIntentRecord?,
) {
    init {
        require(version == 1 && revision >= 0 && databasePath.length in 1..4096 && backupDirectory.length in 1..4096)
        require(helper == null || (maintenance != null && helper.stagePath.length in 1..4096 &&
            helper.maximumRunMillis in 1_000..300_000 && (helper.processId == null || helper.processId > 0)))
        require(maintenance == null || maintenance.authorizationKind in LocalMaintenanceAuthorizationKind.entries.map { it.name })
        require(intent == null || isHostSha256(intent.installedDatabaseSha256))
        when (phase) {
            RunPhase.CLEAN_STOPPED -> require(maintenance == null && helper == null && intent == null)
            RunPhase.RESTORE_HELD -> require(maintenance == null && helper == null && intent != null)
            RunPhase.STARTING, RunPhase.ACTIVE -> require(serviceIncarnationId != null && maintenance == null && helper == null && intent == null)
            RunPhase.MAINTENANCE -> require(maintenance != null)
            else -> Unit
        }
    }

    fun matches(configuration: JvmLocalMasterHostConfiguration): Boolean =
        installationId == configuration.installationId && localDeviceId == configuration.localDeviceId &&
            scope == configuration.scope && authority == configuration.authority &&
            databasePath == configuration.databasePath.toString() && backupDirectory == configuration.backups.backupDirectory.toString()
}

/**
 * Real owner-private file lock + atomic, directory-forced ledger. No automatic repair, deletion,
 * rollback or "missing means fresh" path. A failed write poisons this owner even if rename may
 * already have completed. The caller retains its lock/custody until safe closure or OS reap.
 */
internal class DurableMasterRunState private constructor(
    private val directory: Path,
    private val lock: JvmPrivateFileLock,
    initial: RunRecord?,
) {
    private var record = initial
    private var poisoned = false
    private var closed = false
    private val path = directory.resolve("run-state.json")

    @Synchronized fun read(): RunRecord = record ?: hostFail(LocalMasterHostFailure.STARTUP_HELD)
    @Synchronized fun isHealthy(): Boolean = !poisoned && !closed && lock.isLocallyHeld

    @Synchronized fun initialize(value: RunRecord) {
        check(record == null && !Files.exists(path, NOFOLLOW_LINKS))
        write(value)
    }

    @Synchronized fun update(transform: (RunRecord) -> RunRecord): RunRecord {
        if (!isHealthy()) hostFail(LocalMasterHostFailure.STORAGE_UNAVAILABLE)
        val before = read()
        if (before.revision == Long.MAX_VALUE) hostFail(LocalMasterHostFailure.STORAGE_UNAVAILABLE)
        val after = transform(before).copy(revision = before.revision + 1)
        check(after.installationId == before.installationId && after.localDeviceId == before.localDeviceId && after.scope == before.scope &&
            after.authority == before.authority && after.databasePath == before.databasePath && after.backupDirectory == before.backupDirectory)
        write(after)
        return after
    }

    private fun write(value: RunRecord) {
        if (!isHealthy()) hostFail(LocalMasterHostFailure.STORAGE_UNAVAILABLE)
        val bytes = hostJson.encodeToString(value).encodeToByteArray()
        if (bytes.size > MAX_RUN_STATE_BYTES) hostFail(LocalMasterHostFailure.STORAGE_UNAVAILABLE)
        // Leave partial files as evidence; never remove/reuse another invocation's file.
        val partial = directory.resolve(".run-state-${DomainId.random().value}")
        poisoned = true
        try {
            FileChannel.open(partial, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(ownerFile)).use { output ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) check(output.write(buffer) > 0)
                output.force(true)
            }
            if (Files.isSymbolicLink(path)) hostFail(LocalMasterHostFailure.PRIVATE_STORAGE_REQUIRED)
            Files.move(partial, path, ATOMIC_MOVE, REPLACE_EXISTING)
            forceHostDirectory(directory)
            record = value
            poisoned = false
        } finally { bytes.fill(0) }
    }

    /** Only the owner invokes this after actual safe shutdown; never after an uncertain write. */
    @Synchronized fun closeSafe() {
        if (!isHealthy()) hostFail(LocalMasterHostFailure.CUSTODY_UNAVAILABLE)
        lock.close()
        closed = true
        RetainedHostResources.release(this)
    }

    companion object {
        fun acquire(configuration: JvmLocalMasterHostConfiguration, enrollment: Boolean): DurableMasterRunState {
            val directory = privateHostDirectory(configuration.stateDirectory, create = enrollment)
            privateHostDirectory(configuration.databasePath.parent)
            val lockPath = directory.resolve("owner.lock")
            val lock = JvmPrivateFileLock.acquire(lockPath)
            try {
                val path = directory.resolve("run-state.json")
                val exists = Files.exists(path, NOFOLLOW_LINKS)
                if (enrollment == exists) hostFail(LocalMasterHostFailure.STARTUP_HELD)
                val record = if (exists) readRunRecord(path) else null
                if (record != null && !record.matches(configuration)) hostFail(LocalMasterHostFailure.STARTUP_HELD)
                if (enrollment) Files.newDirectoryStream(directory).use { entries ->
                    // An interrupted enrollment/unknown artifact must not silently become fresh.
                    for (entry in entries) if (entry.fileName.toString() != "owner.lock") hostFail(LocalMasterHostFailure.STARTUP_HELD)
                }
                return DurableMasterRunState(directory, lock, record).also(RetainedHostResources::retain)
            } catch (failure: Throwable) {
                lock.close()
                throw failure
            }
        }
    }
}

/** Strong ownership survives an undelivered factory result; no GC-based custody release. */
internal object RetainedHostResources {
    private val resources: MutableSet<Any> = Collections.synchronizedSet(mutableSetOf())
    fun retain(value: Any) { resources.add(value) }
    fun release(value: Any) { resources.remove(value) }
}

private fun readRunRecord(path: Path): RunRecord {
    val attributes = requirePrivateHostFile(path)
    if (attributes.size() !in 1..MAX_RUN_STATE_BYTES.toLong()) hostFail(LocalMasterHostFailure.STARTUP_HELD)
    val bytes = FileChannel.open(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
        val buffer = ByteBuffer.allocate(attributes.size().toInt())
        while (buffer.hasRemaining()) if (channel.read(buffer) <= 0) hostFail(LocalMasterHostFailure.STARTUP_HELD)
        if (channel.read(ByteBuffer.allocate(1)) != -1) hostFail(LocalMasterHostFailure.STARTUP_HELD)
        buffer.array()
    }
    try {
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        requireBoundedHostJson(text)
        val value = hostJson.decodeFromString<RunRecord>(text)
        if (hostJson.encodeToString(value) != text) hostFail(LocalMasterHostFailure.STARTUP_HELD)
        return value
    } finally { bytes.fill(0) }
}

internal fun privateHostDirectory(path: Path, create: Boolean = false): Path {
    if (!path.isAbsolute || path != path.normalize() || path.parent == null) hostFail(LocalMasterHostFailure.PRIVATE_STORAGE_REQUIRED)
    var current = path.root
    for (segment in path) {
        current = current.resolve(segment)
        if (Files.isSymbolicLink(current)) hostFail(LocalMasterHostFailure.PRIVATE_STORAGE_REQUIRED)
    }
    if (create && !Files.exists(path, NOFOLLOW_LINKS)) {
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(ownerDirectory))
        forceHostDirectory(path.parent)
    }
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
    if (!attributes.isDirectory || view == null || view.readAttributes().permissions().any { it !in ownerDirectory }) {
        hostFail(LocalMasterHostFailure.PRIVATE_STORAGE_REQUIRED)
    }
    return path
}

internal fun requirePrivateHostFile(path: Path): BasicFileAttributes {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
    if (!attributes.isRegularFile || attributes.isSymbolicLink || view == null ||
        view.readAttributes().permissions().any { it !in ownerFile }) hostFail(LocalMasterHostFailure.PRIVATE_STORAGE_REQUIRED)
    return attributes
}

internal fun forceHostDirectory(path: Path) { FileChannel.open(path, READ).use { it.force(true) } }
internal fun isHostSha256(value: String): Boolean = value.length == 64 && value.all { it in "0123456789abcdef" }

private fun requireBoundedHostJson(text: String) {
    var quoted = false
    var escaped = false
    var depth = 0
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> { depth++; if (depth > 24) hostFail(LocalMasterHostFailure.STARTUP_HELD) }
            '}', ']' -> { depth--; if (depth < 0) hostFail(LocalMasterHostFailure.STARTUP_HELD) }
        }
    }
    if (quoted || depth != 0) hostFail(LocalMasterHostFailure.STARTUP_HELD)
}
