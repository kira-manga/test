package com.ukkera.pos.recovery.store

import com.ukkera.pos.types.DomainId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public enum class MasterBackupTrigger { SCHEDULED, ON_DEMAND }

public enum class MasterBackupFailure {
    CAPTURE_UNAVAILABLE, KEY_UNAVAILABLE, STORAGE_UNAVAILABLE, AUTHENTICATION_FAILED,
    INVALID_ARCHIVE, CHAIN_BROKEN, NOT_FOUND,
}

public enum class MasterRestoreRefusal {
    APPROVAL_REQUIRED, TARGET_OCCUPIED, ARCHIVE_INVALID, AUTHENTICATION_FAILED, SNAPSHOT_MISMATCH,
    INTEGRITY_FAILED, SCHEMA_UNSUPPORTED, STORAGE_UNAVAILABLE,
}

/** Authenticated manifest facts for presentation; contains no key, path or database content. */
public class MasterBackupEntry internal constructor(
    public val backupId: DomainId,
    public val sequence: Long,
    public val createdAtEpochMillis: Long,
    public val trigger: MasterBackupTrigger,
    public val schemaVersion: Int,
    public val snapshotBytes: Long,
    public val historyGeneration: Long,
    public val installationId: DomainId,
    internal val manifest: MasterStateBackupManifestV1,
    internal val manifestSha256: String,
) {
    override fun toString(): String = "MasterBackupEntry([REDACTED])"
}

public sealed interface MasterBackupOutcome {
    public class Created(
        public val entry: MasterBackupEntry,
        /** False is a separate retention failure, never a backup failure or success claim. */
        public val retentionCompleted: Boolean,
        public val deletedBackupIds: List<DomainId>,
    ) : MasterBackupOutcome
    public data object NotDue : MasterBackupOutcome
    public data class Failed(public val failure: MasterBackupFailure) : MasterBackupOutcome
}

public sealed interface MasterBackupVerification {
    public class Verified(public val entry: MasterBackupEntry) : MasterBackupVerification
    public data class Refused(public val failure: MasterBackupFailure) : MasterBackupVerification
}

/** Chain report over every listed archive. Unreadable archives are retained, never deleted. */
public class MasterBackupChainReport internal constructor(
    public val entries: List<MasterBackupEntry>,
    public val unreadableArchives: Int,
    /** Consecutive sequence/previous-manifest links and non-decreasing history generation. */
    public val linksIntact: Boolean,
    /** The newest archive equals the durable chain head (no rollback/deletion of newest). */
    public val headMatches: Boolean,
) {
    public val intact: Boolean get() = linksIntact && headMatches && unreadableArchives == 0
}

public sealed interface MasterRestoreOutcome {
    /** A verified HELD candidate exists at the target. It is NOT an admitted Master or writer. */
    public class Held(public val backupId: DomainId, public val installationId: DomainId,
        public val restoredSha256: String) : MasterRestoreOutcome
    /** Nothing was staged; the target is unchanged. */
    public data class Refused(public val reason: MasterRestoreRefusal) : MasterRestoreOutcome
    /** Staged bytes failed verification and were preserved in quarantine. */
    public data class Quarantined(public val reason: MasterRestoreRefusal) : MasterRestoreOutcome
}

public class MasterStateBackupConfiguration(
    public val retainLatest: Int = 7,
    public val scheduleIntervalMillis: Long = 6L * 60 * 60 * 1000,
    public val chunkBytes: Int = 1024 * 1024,
    supportedRestoreSchemas: Set<Int> = setOf(7),
) {
    private val schemas = supportedRestoreSchemas.toSet()
    public val supportedRestoreSchemas: Set<Int> get() = schemas.toSet()
    init {
        require(retainLatest in 2..256)
        require(scheduleIntervalMillis in 60_000L..7L * 24 * 60 * 60 * 1000)
        require(chunkBytes in MasterStateBackupFormatV1.MIN_CHUNK..MasterStateBackupFormatV1.MAX_CHUNK)
        require(schemas.isNotEmpty() && schemas.all { it > 0 })
    }
}

/**
 * Shared Master-owned backup orchestration: scheduled and on-demand consistent encrypted
 * full-state backup, retention, archive and chain verification, and approved restore into a
 * fresh/quarantined target only. The Master composition supplies every port; [source] is null
 * when the Master is not operational (verification/restore remain available).
 *
 * Restore never replaces live state and never admits a writer: a verified candidate is HELD.
 */
public class MasterStateBackupService(
    private val configuration: MasterStateBackupConfiguration,
    private val source: MasterStateSnapshotSource?,
    private val custody: MasterBackupKeyCustody,
    private val vault: MasterBackupVault,
    private val authority: MasterRestoreAuthority,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private val f = MasterStateBackupFormatV1

    public suspend fun createBackup(trigger: MasterBackupTrigger = MasterBackupTrigger.ON_DEMAND): MasterBackupOutcome =
        mutex.withLock { guarded { create(trigger) } }

    /** Creates a SCHEDULED backup only when the newest chain head is older than the interval. */
    public suspend fun runScheduledIfDue(): MasterBackupOutcome = mutex.withLock {
        guarded {
            val head = readHead()
            val now = clock()
            if (head != null && now - head.createdAtEpochMillis in 0 until configuration.scheduleIntervalMillis) {
                MasterBackupOutcome.NotDue
            } else create(MasterBackupTrigger.SCHEDULED)
        }
    }

    public suspend fun verifyBackup(backupId: DomainId): MasterBackupVerification = mutex.withLock {
        try { MasterBackupVerification.Verified(readArchive(backupId, sink = null)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: MasterBackupFormatException) { MasterBackupVerification.Refused(failure.failure) }
        catch (_: Exception) { MasterBackupVerification.Refused(MasterBackupFailure.STORAGE_UNAVAILABLE) }
    }

    /** Authenticated manifests (not full chunk verification) plus chain links and head. */
    public suspend fun verifyChain(): MasterBackupChainReport = mutex.withLock { chain() }

    /**
     * Restore [backupId] into [target] only when it is FRESH or QUARANTINED and [approval] is a
     * verified head approval of the archive's installation. A mismatch after staging quarantines
     * the staged bytes. Success publishes a HELD candidate, never live state or a writer.
     */
    public suspend fun restore(backupId: DomainId, approval: MasterRestoreApproval,
        target: MasterRestoreTarget): MasterRestoreOutcome = mutex.withLock {
        try { restoreLocked(backupId, approval, target) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MasterRestoreOutcome.Refused(MasterRestoreRefusal.STORAGE_UNAVAILABLE) }
    }

    private suspend fun guarded(block: suspend () -> MasterBackupOutcome): MasterBackupOutcome =
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: MasterBackupFormatException) { MasterBackupOutcome.Failed(failure.failure) }
        catch (_: Exception) { MasterBackupOutcome.Failed(MasterBackupFailure.STORAGE_UNAVAILABLE) }

    private suspend fun create(trigger: MasterBackupTrigger): MasterBackupOutcome {
        val capture = source ?: return MasterBackupOutcome.Failed(MasterBackupFailure.CAPTURE_UNAVAILABLE)
        val head = readHead()
        val previous = head?.let { readArchiveHeader(DomainId(it.backupId)) }
        if (head != null && (previous == null || previous.second != head.manifestSha256)) {
            return MasterBackupOutcome.Failed(MasterBackupFailure.CHAIN_BROKEN)
        }
        val snapshot = try { capture.capture() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return MasterBackupOutcome.Failed(MasterBackupFailure.CAPTURE_UNAVAILABLE) }
        try {
            val facts = snapshot.facts
            if (head != null && (head.installationId != facts.installationId.value ||
                    facts.historyGeneration < head.historyGeneration)) {
                // A different installation or an older native history generation is a fork or
                // rollback of the Master state; never extend the chain from it.
                return MasterBackupOutcome.Failed(MasterBackupFailure.CHAIN_BROKEN)
            }
            val id = DomainId.random()
            val chunk = configuration.chunkBytes
            val manifest = MasterStateBackupManifestV1(MasterStateBackupFormatV1.VERSION, id.value,
                (head?.sequence ?: 0L) + 1, head?.backupId, head?.manifestSha256, clock(), trigger.name,
                facts.organizationId.value, facts.companyId.value, facts.branchId.value, facts.installationId.value,
                facts.schemaVersion, facts.roomIdentityHash, facts.historyGeneration, facts.historyRecordDigest,
                facts.snapshotBytes, facts.snapshotSha256, chunk, (facts.snapshotBytes + chunk - 1) / chunk, custody.keyId)
            val manifestBytes = f.encodeManifest(manifest)
            val manifestSha = MasterBackupSha256.hex(manifestBytes)
            val writer = vault.create(id)
            var published = false
            try {
                writer.append(f.MAGIC)
                val sealedManifest = seal(f.manifestAad(), manifestBytes)
                writer.append(f.u32(sealedManifest.size)); writer.append(sealedManifest)
                val digest = MasterBackupSha256()
                var offset = 0L
                var index = 0L
                while (offset < facts.snapshotBytes) {
                    currentCoroutineContext().ensureActive()
                    val want = minOf(chunk.toLong(), facts.snapshotBytes - offset).toInt()
                    val plain = snapshot.read(offset, want)
                    if (plain.size != want) throw MasterBackupFormatException(MasterBackupFailure.CAPTURE_UNAVAILABLE)
                    digest.update(plain)
                    val last = index == manifest.chunkCount - 1
                    val sealed = seal(f.chunkAad(manifestSha, index, last), plain)
                    plain.fill(0)
                    writer.append(f.u32(sealed.size)); writer.append(sealed)
                    offset += want; index++
                }
                if (index != manifest.chunkCount || digest.digestHex() != facts.snapshotSha256) {
                    throw MasterBackupFormatException(MasterBackupFailure.CAPTURE_UNAVAILABLE)
                }
                writer.publish()
                published = true
            } finally {
                if (!published) withContext(NonCancellable) { runCatching { writer.abort() } }
            }
            // Read back the complete published archive before advancing the chain head.
            val entry = readArchive(id, sink = null)
            if (entry.manifestSha256 != manifestSha) throw MasterBackupFormatException(MasterBackupFailure.INVALID_ARCHIVE)
            val newHead = MasterBackupChainHeadV1(1, manifest.sequence, id.value, manifestSha, manifest.createdAtEpochMillis,
                manifest.installationId, manifest.historyGeneration)
            vault.writeChainHead(f.json.encodeToString(MasterBackupChainHeadV1.serializer(), newHead).encodeToByteArray())
            val deleted = try { retention(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            return MasterBackupOutcome.Created(entry, deleted != null, deleted.orEmpty())
        } finally {
            withContext(NonCancellable) { runCatching { snapshot.discard() } }
        }
    }

    /** Keeps the newest [MasterStateBackupConfiguration.retainLatest] authenticated archives. */
    private suspend fun retention(newest: DomainId): List<DomainId> {
        val report = chain()
        val ordered = report.entries.sortedByDescending { it.sequence }
        val keep = ordered.take(configuration.retainLatest).map { it.backupId }.toSet() + newest
        val deleted = mutableListOf<DomainId>()
        for (entry in ordered) {
            if (entry.backupId in keep) continue
            currentCoroutineContext().ensureActive()
            vault.delete(entry.backupId)
            deleted += entry.backupId
        }
        return deleted
    }

    private suspend fun chain(): MasterBackupChainReport {
        val entries = mutableListOf<MasterBackupEntry>()
        var unreadable = 0
        for (id in vault.archiveIds()) {
            currentCoroutineContext().ensureActive()
            try {
                val header = readArchiveHeader(id)
                if (header == null) unreadable++ else entries += header.first
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { unreadable++ }
        }
        val ordered = entries.sortedBy { it.sequence }
        var links = ordered.map { it.installationId }.distinct().size <= 1
        for (i in 1 until ordered.size) {
            val prev = ordered[i - 1]; val next = ordered[i]
            if (next.sequence != prev.sequence + 1 || next.manifest.previousBackupId != prev.backupId.value ||
                next.manifest.previousManifestSha256 != prev.manifestSha256 ||
                next.historyGeneration < prev.historyGeneration) links = false
        }
        val head = try { readHead() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        val newest = ordered.lastOrNull()
        val headMatches = if (head == null) newest == null else
            newest != null && newest.sequence == head.sequence && newest.backupId.value == head.backupId &&
                newest.manifestSha256 == head.manifestSha256
        return MasterBackupChainReport(ordered, unreadable, links, headMatches)
    }

    private suspend fun restoreLocked(backupId: DomainId, approval: MasterRestoreApproval,
        target: MasterRestoreTarget): MasterRestoreOutcome {
        if (target.condition() == MasterRestoreTargetCondition.OCCUPIED) {
            return MasterRestoreOutcome.Refused(MasterRestoreRefusal.TARGET_OCCUPIED)
        }
        val header = try { readArchiveHeader(backupId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: MasterBackupFormatException) { return MasterRestoreOutcome.Refused(refusal(failure.failure)) }
            ?: return MasterRestoreOutcome.Refused(MasterRestoreRefusal.ARCHIVE_INVALID)
        val entry = header.first
        if (approval.installationId != entry.installationId ||
            !authority.verify(approval, backupId, entry.installationId)) {
            return MasterRestoreOutcome.Refused(MasterRestoreRefusal.APPROVAL_REQUIRED)
        }
        if (entry.schemaVersion !in configuration.supportedRestoreSchemas) {
            return MasterRestoreOutcome.Refused(MasterRestoreRefusal.SCHEMA_UNSUPPORTED)
        }
        val stage = target.beginStage(backupId)
        var staged = false
        try {
            try { readArchive(backupId, sink = { bytes -> staged = true; stage.append(bytes) }) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: MasterBackupFormatException) {
                return quarantineOrRefuse(stage, staged, refusal(failure.failure))
            } catch (_: Exception) {
                return quarantineOrRefuse(stage, staged, MasterRestoreRefusal.STORAGE_UNAVAILABLE)
            }
            val observed = stage.observe()
            val m = entry.manifest
            val reason = when {
                observed.bytes != m.snapshotBytes || observed.sha256 != m.snapshotSha256 -> MasterRestoreRefusal.SNAPSHOT_MISMATCH
                !observed.integrityOk -> MasterRestoreRefusal.INTEGRITY_FAILED
                observed.schemaVersion != m.schemaVersion || observed.roomIdentityHash != m.roomIdentityHash ->
                    MasterRestoreRefusal.SCHEMA_UNSUPPORTED
                else -> null
            }
            if (reason != null) return quarantineOrRefuse(stage, true, reason)
            val held = MasterRestoreHeldRecordV1(1, "HELD_NOT_ADMITTED", backupId.value, entry.manifestSha256,
                m.installationId, approval.approvalId.value, observed.sha256, m.historyGeneration, m.historyRecordDigest)
            stage.installHeld(f.json.encodeToString(MasterRestoreHeldRecordV1.serializer(), held).encodeToByteArray())
            return MasterRestoreOutcome.Held(backupId, entry.installationId, observed.sha256)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runCatching { if (staged) stage.quarantine(MasterRestoreRefusal.STORAGE_UNAVAILABLE) else stage.abort() } }
            throw cancelled
        }
    }

    private suspend fun quarantineOrRefuse(stage: MasterRestoreStage, staged: Boolean,
        reason: MasterRestoreRefusal): MasterRestoreOutcome = if (staged) {
        stage.quarantine(reason); MasterRestoreOutcome.Quarantined(reason)
    } else {
        stage.abort(); MasterRestoreOutcome.Refused(reason)
    }

    private fun refusal(failure: MasterBackupFailure): MasterRestoreRefusal = when (failure) {
        MasterBackupFailure.AUTHENTICATION_FAILED, MasterBackupFailure.KEY_UNAVAILABLE -> MasterRestoreRefusal.AUTHENTICATION_FAILED
        MasterBackupFailure.STORAGE_UNAVAILABLE -> MasterRestoreRefusal.STORAGE_UNAVAILABLE
        else -> MasterRestoreRefusal.ARCHIVE_INVALID
    }

    private suspend fun readHead(): MasterBackupChainHeadV1? {
        val bytes = vault.readChainHead() ?: return null
        return try {
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            val head = f.json.decodeFromString(MasterBackupChainHeadV1.serializer(), text)
            if (f.json.encodeToString(MasterBackupChainHeadV1.serializer(), head) != text) error("noncanonical")
            head
        } catch (_: Exception) { throw MasterBackupFormatException(MasterBackupFailure.CHAIN_BROKEN) }
    }

    /** Authenticated manifest only. Returns null when the archive does not exist. */
    private suspend fun readArchiveHeader(id: DomainId): Pair<MasterBackupEntry, String>? {
        if (id !in vault.archiveIds()) return null
        val reader = vault.open(id)
        try {
            val entry = readManifest(reader, id)
            return entry to entry.manifestSha256
        } finally { withContext(NonCancellable) { runCatching { reader.close() } } }
    }

    private suspend fun readManifest(reader: MasterBackupArchiveReader, id: DomainId): MasterBackupEntry {
        val magic = readOrInvalid(reader, f.MAGIC.size)
        if (!magic.contentEquals(f.MAGIC)) throw MasterBackupFormatException(MasterBackupFailure.INVALID_ARCHIVE)
        val length = f.readU32(readOrInvalid(reader, 4))
        if (length > f.MAX_MANIFEST + f.MAX_SEAL_OVERHEAD) throw MasterBackupFormatException(MasterBackupFailure.INVALID_ARCHIVE)
        val manifestBytes = open(f.manifestAad(), readOrInvalid(reader, length))
        val manifest = f.decodeManifest(manifestBytes)
        if (manifest.backupId != id.value) throw MasterBackupFormatException(MasterBackupFailure.INVALID_ARCHIVE)
        return MasterBackupEntry(id, manifest.sequence, manifest.createdAtEpochMillis,
            MasterBackupTrigger.valueOf(manifest.trigger), manifest.schemaVersion, manifest.snapshotBytes,
            manifest.historyGeneration, DomainId(manifest.installationId), manifest, MasterBackupSha256.hex(manifestBytes))
    }

    /** Full authenticated read: every chunk, exact count/size/digest and no trailing bytes. */
    private suspend fun readArchive(id: DomainId, sink: (suspend (ByteArray) -> Unit)?): MasterBackupEntry {
        if (id !in vault.archiveIds()) throw MasterBackupFormatException(MasterBackupFailure.NOT_FOUND)
        val reader = vault.open(id)
        try {
            val entry = readManifest(reader, id)
            val m = entry.manifest
            val digest = MasterBackupSha256()
            var total = 0L
            for (index in 0 until m.chunkCount) {
                currentCoroutineContext().ensureActive()
                val length = f.readU32(readOrInvalid(reader, 4))
                if (length > m.chunkBytes + f.MAX_SEAL_OVERHEAD) throw MasterBackupFormatException(MasterBackupFailure.INVALID_ARCHIVE)
                val last = index == m.chunkCount - 1
                val plain = open(f.chunkAad(entry.manifestSha256, index, last), readOrInvalid(reader, length))
                val expected = if (last) m.snapshotBytes - m.chunkBytes.toLong() * index else m.chunkBytes.toLong()
                if (plain.size.toLong() != expected) throw MasterBackupFormatException(MasterBackupFailure.INVALID_ARCHIVE)
                digest.update(plain)
                total += plain.size
                try { sink?.invoke(plain) } finally { plain.fill(0) }
            }
            if (!reader.atEnd() || total != m.snapshotBytes || digest.digestHex() != m.snapshotSha256) {
                throw MasterBackupFormatException(MasterBackupFailure.INVALID_ARCHIVE)
            }
            return entry
        } finally { withContext(NonCancellable) { runCatching { reader.close() } } }
    }

    private suspend fun readOrInvalid(reader: MasterBackupArchiveReader, count: Int): ByteArray =
        try { reader.readExactly(count) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw MasterBackupFormatException(MasterBackupFailure.INVALID_ARCHIVE) }

    private suspend fun seal(aad: ByteArray, plain: ByteArray): ByteArray =
        try { custody.seal(aad, plain) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw MasterBackupFormatException(MasterBackupFailure.KEY_UNAVAILABLE) }

    private suspend fun open(aad: ByteArray, sealed: ByteArray): ByteArray =
        try { custody.open(aad, sealed) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw MasterBackupFormatException(MasterBackupFailure.AUTHENTICATION_FAILED) }

    override fun toString(): String = "MasterStateBackupService([REDACTED])"
}
