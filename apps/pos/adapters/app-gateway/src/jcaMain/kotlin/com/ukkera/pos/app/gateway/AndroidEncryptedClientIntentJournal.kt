package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentProfileV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Durable Android pending intent/result storage over the retained native journal owner. It
 * never creates a missing journal, fabricates a result, or supplies a master/employee session.
 * Acknowledgement archives the COMPLETE exact original record before active removal. An
 * interrupted duplicate remains pending until the same explicit acknowledgement is repeated.
 */
public class AndroidEncryptedClientIntentJournal private constructor(
    private val file: ClientJournalFileCore,
) : LocalClientIntentJournal(LocalClientIdentity(file.deviceOwner.scope,
    requireNotNull(file.actorId), file.deviceOwner.deviceId)) {

    override internal suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> =
        readResult(file.owned {
            task {
                val state = readState(operationId)
                state.manifest.entries.singleOrNull { it.operationId == operationId } ?: state.selectedArchive
            }
        })

    override internal suspend fun pending(): JournalRead<List<StoredClientIntentV1>> =
        readResult(file.owned {
            task { readState().manifest.entries.sortedBy { it.operationId.value } }
        })

    override internal suspend fun documentarySource(documentId: DomainId): JournalRead<StoredClientDocumentarySourceV1?> =
        readResult(file.owned {
            task { readState().manifest.sourceRecords.singleOrNull { it.documentId == documentId } }
        })

    override internal suspend fun documentarySources(): JournalRead<List<StoredClientDocumentarySourceV1>> =
        readResult(file.owned {
            task { readState().manifest.sourceRecords.sortedBy { it.documentId.value } }
        })

    override internal suspend fun saveDocumentarySource(source: StoredClientDocumentarySourceV1): JournalWrite =
        writeResult(file.owned {
            val bytes = encodeSource(source)
            task(bytes) {
                val captured = decodeSource(bytes)
                val operations = captured.operationIds.toSet()
                val state = readState(checkOperations = operations)
                val existing = state.manifest.sourceRecords.singleOrNull { it.documentId == captured.documentId }
                when {
                    existing != null -> if (existing == captured) JournalWrite.DURABLE else JournalWrite.IDENTITY_CONFLICT
                    captured.completionAttemptCount != 1 -> JournalWrite.IDENTITY_CONFLICT
                    state.operationConflict || state.manifest.sourceRecords.any { retained ->
                        retained.operationIds.any { it in operations }
                    } -> JournalWrite.IDENTITY_CONFLICT
                    state.manifest.sourceRecords.size >= MAX_CLIENT_DOCUMENTARY_SOURCES -> JournalWrite.NO_CAPACITY
                    else -> {
                        writeManifest(state.manifest.withSource(captured))
                        JournalWrite.DURABLE
                    }
                }
            }
        })

    override internal suspend fun appendDocumentaryCompletionRetry(source: StoredClientDocumentarySourceV1): JournalWrite =
        writeResult(file.owned {
            val bytes = encodeSource(source)
            task(bytes) {
                val captured = decodeSource(bytes)
                val previousId = captured.priorCompletionOperationIds.lastOrNull()
                val newOperationId = captured.latestCompleteOperationId
                val state = readState(previousId, setOf(newOperationId))
                val existing = state.manifest.sourceRecords.singleOrNull { it.documentId == captured.documentId }
                when {
                    existing == null || captured.completionAttemptCount == 1 -> JournalWrite.IDENTITY_CONFLICT
                    existing == captured -> JournalWrite.DURABLE
                    !captured.isOneCompletionAppendOf(existing) -> JournalWrite.IDENTITY_CONFLICT
                    existing.completionAttemptCount >= MAX_CLIENT_DOCUMENTARY_COMPLETION_ATTEMPTS -> JournalWrite.NO_CAPACITY
                    state.operationConflict || state.manifest.sourceRecords.any { newOperationId in it.operationIds } ->
                        JournalWrite.IDENTITY_CONFLICT
                    state.manifest.entries.any { it.operationId != existing.latestCompleteOperationId } -> JournalWrite.UNAVAILABLE
                    else -> {
                        val previous = state.manifest.entries.singleOrNull { it.operationId == existing.latestCompleteOperationId }
                            ?: state.selectedArchive ?: return@task JournalWrite.UNAVAILABLE
                        if (!existing.hasClosedStaleCompletion(previous)) return@task JournalWrite.UNAVAILABLE
                        writeManifest(state.manifest.replaceSource(captured))
                        JournalWrite.DURABLE
                    }
                }
            }
        })

    override internal suspend fun stage(intent: StoredClientIntentV1): JournalWrite =
        writeResult(file.owned {
            // No private mutable input is copied or retained before actual work admission.
            val bytes = encodeEntry(intent)
            task(bytes) {
                val captured = decodeEntry(bytes)
                check(captured.localPhase == LocalPendingPhase.STAGED && !captured.acknowledged)
                val state = readState(captured.operationId)
                val existing = state.manifest.entries.singleOrNull { it.operationId == captured.operationId }
                    ?: state.selectedArchive
                val source = state.manifest.sourceFor(captured.operationId)
                when {
                    source != null && !source.accepts(captured) -> JournalWrite.IDENTITY_CONFLICT
                    captured.documentaryCommand() && source == null -> JournalWrite.IDENTITY_CONFLICT
                    existing != null -> if (existing.sameIntent(captured))
                        JournalWrite.DURABLE else JournalWrite.IDENTITY_CONFLICT
                    state.manifest.entries.size >= MAX_CLIENT_PENDING -> JournalWrite.NO_CAPACITY
                    state.manifest.archivedCount + state.manifest.entries.size >= CLIENT_JOURNAL_MAX_ARCHIVES_V1 ->
                        JournalWrite.NO_CAPACITY
                    else -> {
                        writeManifest(state.manifest.next(state.manifest.entries + captured))
                        JournalWrite.DURABLE
                    }
                }
            }
        })

    override internal suspend fun markDispatched(operationId: OperationId): JournalWrite =
        writeResult(file.owned {
            task {
                val state = readState(operationId)
                val entry = state.manifest.entries.singleOrNull { it.operationId == operationId }
                    ?: state.selectedArchive ?: error("client.intent.required")
                if (entry.documentaryCommand() && state.manifest.sourceFor(operationId) == null)
                    return@task JournalWrite.UNAVAILABLE
                if (entry.actionApproval != null && entry.actionApproval.localPhase != com.ukkera.pos.app.LocalActionApprovalPhase.ATTACHED)
                    return@task JournalWrite.UNAVAILABLE
                if (entry.localPhase == LocalPendingPhase.STAGED) {
                    writeManifest(state.manifest.replace(entry.copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED.name)))
                }
                // Recorded/archived evidence is not reverted to an unresolved phase.
                JournalWrite.DURABLE
            }
        })

    override internal suspend fun updateActionApproval(previous: StoredClientIntentV1, next: StoredClientIntentV1): JournalWrite =
        writeResult(file.owned {
            val previousBytes = encodeEntry(previous)
            val nextBytes = try { encodeEntry(next) } catch (failure: Throwable) { previousBytes.fill(0); throw failure }
            task(previousBytes, nextBytes) {
                val before = decodeEntry(previousBytes)
                val after = decodeEntry(nextBytes)
                if (!validClientActionApprovalTransition(before, after)) return@task JournalWrite.IDENTITY_CONFLICT
                val current = readState(before.operationId).manifest
                val retained = current.entries.singleOrNull { it.operationId == before.operationId }
                    ?: return@task JournalWrite.IDENTITY_CONFLICT
                when {
                    retained == after -> JournalWrite.DURABLE
                    retained != before -> JournalWrite.IDENTITY_CONFLICT
                    else -> { writeManifest(current.replace(after)); JournalWrite.DURABLE }
                }
            }
        })

    override internal suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite =
        writeResult(file.owned {
            val bytes = encodeResult(result)
            task(bytes) {
                val captured = decodeResult(bytes)
                if (captured.operationId != operationId || captured.originalActorId != owner.actorId)
                    return@task JournalWrite.IDENTITY_CONFLICT
                val state = readState(operationId)
                val entry = state.manifest.entries.singleOrNull { it.operationId == operationId }
                    ?: state.selectedArchive ?: error("client.intent.required")
                when {
                    entry.recorded != null && entry.recorded != captured -> JournalWrite.IDENTITY_CONFLICT
                    entry.recorded != null -> JournalWrite.DURABLE
                    else -> {
                        writeManifest(state.manifest.replace(entry.copy(
                            phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = captured)))
                        JournalWrite.DURABLE
                    }
                }
            }
        })

    override internal suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite =
        writeResult(file.owned {
            val bytes = encodeResult(result)
            task(bytes) {
                val captured = decodeResult(bytes)
                if (captured.operationId != operationId || captured.originalActorId != owner.actorId)
                    return@task JournalWrite.IDENTITY_CONFLICT
                val state = readState(operationId)
                val active = state.manifest.entries.singleOrNull { it.operationId == operationId }
                val archived = state.selectedArchive
                if (active == null) {
                    return@task if (archived != null && archived.recorded == captured && archived.acknowledged)
                        JournalWrite.DURABLE else JournalWrite.IDENTITY_CONFLICT
                }
                if (active.recorded != captured || active.acknowledged) return@task JournalWrite.IDENTITY_CONFLICT
                val retained = active.copy(acknowledged = true)
                if (archived != null) {
                    // readState already verified every duplicate, not only this selected result.
                    check(archived == retained)
                } else {
                    encodeEntry(retained).useWiped { file.appendArchive(operationId, it) }
                }
                // Exact forced/read-back archive precedes this removal. If the second write
                // fails or the caller disappears, no substitute ID or recollection is invented.
                writeManifest(state.manifest.next(
                    entries = state.manifest.entries.filterNot { it.operationId == operationId },
                    archivedCount = Math.addExact(state.manifest.archivedCount, 1L)))
                JournalWrite.DURABLE
            }
        })

    /** Retires this client view, not the branch master; native lifetime belongs to the root owner. */
    override public suspend fun close() { file.closeView() }
    override fun toString(): String = "AndroidEncryptedClientIntentJournalV1([REDACTED])"

    private data class ReadState(
        val manifest: AndroidClientIntentManifestV1,
        val selectedArchive: StoredClientIntentV1?,
        val operationConflict: Boolean,
    )

    private fun readState(selectedId: OperationId? = null, checkOperations: Set<OperationId> = emptySet()): ReadState {
        val manifest = file.readManifest(MAX_CLIENT_INTENT_MANIFEST_BYTES).useWiped { bytes ->
            journalJson.decodeFromString(AndroidClientIntentManifestV1.serializer(),
                bytes.decodeToString(throwOnInvalidSequence = true)).also { decoded ->
                decoded.validate(owner, file.journalId, file.keyId)
                encodeManifest(decoded).useWiped { check(it.contentEquals(bytes)) }
            }
        }
        var selected: StoredClientIntentV1? = null
        var duplicates = 0L
        var operationConflict = manifest.entries.any { it.operationId in checkOperations }
        val requiredCompletions = buildMap<OperationId, StoredClientDocumentarySourceV1> {
            manifest.sourceRecords.forEach { source -> source.priorCompletionOperationIds.forEach { put(it, source) } }
        }
        val observedCompletions = mutableSetOf<OperationId>()
        manifest.entries.forEach { entry -> requiredCompletions[entry.operationId]?.let { source ->
            check(source.hasClosedStaleCompletion(entry))
            observedCompletions += entry.operationId
        } }
        val count = file.visitArchives { operationId, bytes ->
            val entry = decodeEntry(bytes)
            check(entry.operationId == operationId && entry.acknowledged && entry.recorded != null)
            val source = manifest.sourceFor(operationId)
            check(!entry.documentaryCommand() || source != null)
            source?.let { check(it.accepts(entry)) }
            requiredCompletions[operationId]?.let { retained ->
                check(retained.hasClosedStaleCompletion(entry))
                observedCompletions += operationId
            }
            if (operationId in checkOperations) operationConflict = true
            val active = manifest.entries.singleOrNull { it.operationId == operationId }
            if (active != null) {
                check(active.recorded != null && !active.acknowledged &&
                    entry == active.copy(acknowledged = true))
                duplicates = Math.addExact(duplicates, 1L)
            }
            if (operationId == selectedId) {
                check(selected == null)
                selected = entry
            }
        }
        // All original archives count, not merely a requested archive or active duplicates.
        // An archive forced before interrupted active removal contributes exactly one duplicate.
        check(count == Math.addExact(manifest.archivedCount, duplicates))
        check(observedCompletions == requiredCompletions.keys)
        return ReadState(manifest, selected, operationConflict)
    }

    private fun writeManifest(manifest: AndroidClientIntentManifestV1) {
        encodeManifest(manifest).useWiped { file.replaceManifest(it, MAX_CLIENT_INTENT_MANIFEST_BYTES) }
    }

    private fun encodeManifest(manifest: AndroidClientIntentManifestV1): ByteArray {
        manifest.validate(owner, file.journalId, file.keyId)
        val bytes = journalJson.encodeToString(AndroidClientIntentManifestV1.serializer(), manifest)
            .encodeToByteArray(throwOnInvalidSequence = true)
        try { check(bytes.size in 1..MAX_CLIENT_INTENT_MANIFEST_BYTES); return bytes }
        catch (failure: Throwable) { bytes.fill(0); throw failure }
    }

    private fun encodeEntry(entry: StoredClientIntentV1): ByteArray {
        entry.validate(owner)
        val bytes = journalJson.encodeToString(StoredClientIntentV1.serializer(), entry)
            .encodeToByteArray(throwOnInvalidSequence = true)
        try { check(bytes.size in 1..MAX_CLIENT_ENTRY_BYTES); return bytes }
        catch (failure: Throwable) { bytes.fill(0); throw failure }
    }

    private fun decodeEntry(bytes: ByteArray): StoredClientIntentV1 {
        check(bytes.size in 1..MAX_CLIENT_ENTRY_BYTES)
        val entry = journalJson.decodeFromString(StoredClientIntentV1.serializer(),
            bytes.decodeToString(throwOnInvalidSequence = true))
        entry.validate(owner)
        encodeEntry(entry).useWiped { check(it.contentEquals(bytes)) }
        return entry
    }

    private fun encodeResult(result: StoredKernelResultV1): ByteArray {
        result.validate()
        val bytes = journalJson.encodeToString(StoredKernelResultV1.serializer(), result)
            .encodeToByteArray(throwOnInvalidSequence = true)
        try { check(bytes.size in 1..MAX_CLIENT_ENTRY_BYTES); return bytes }
        catch (failure: Throwable) { bytes.fill(0); throw failure }
    }

    private fun decodeResult(bytes: ByteArray): StoredKernelResultV1 {
        check(bytes.size in 1..MAX_CLIENT_ENTRY_BYTES)
        val result = journalJson.decodeFromString(StoredKernelResultV1.serializer(),
            bytes.decodeToString(throwOnInvalidSequence = true))
        encodeResult(result).useWiped { check(it.contentEquals(bytes)) }
        return result
    }

    private fun encodeSource(source: StoredClientDocumentarySourceV1): ByteArray {
        source.validate(owner)
        val bytes = journalJson.encodeToString(StoredClientDocumentarySourceV1.serializer(), source)
            .encodeToByteArray(throwOnInvalidSequence = true)
        try { check(bytes.size in 1..MAX_CLIENT_DOCUMENTARY_SOURCE_BYTES); return bytes }
        catch (failure: Throwable) { bytes.fill(0); throw failure }
    }

    private fun decodeSource(bytes: ByteArray): StoredClientDocumentarySourceV1 {
        check(bytes.size in 1..MAX_CLIENT_DOCUMENTARY_SOURCE_BYTES)
        val source = journalJson.decodeFromString(StoredClientDocumentarySourceV1.serializer(),
            bytes.decodeToString(throwOnInvalidSequence = true))
        source.validate(owner)
        encodeSource(source).useWiped { check(it.contentEquals(bytes)) }
        return source
    }

    /** Private authored operations, not a caller-provided verifier/native work implementation. */
    private fun <T> task(vararg inputs: ByteArray, execute: () -> T): ClientJournalTask<T> =
        try {
            object : ClientJournalTask<T> {
                override fun execute(): T = execute.invoke()
                override fun discard(value: T) = Unit // these complete records contain no token/native handles
                override fun clearInputs() { inputs.forEach { it.fill(0) } }
            }
        } catch (failure: Throwable) { inputs.forEach { it.fill(0) }; throw failure }

    private fun <T> readResult(value: ClientJournalOutcome<T>): JournalRead<T> = when (value) {
        is ClientJournalOutcome.Value -> JournalRead.Value(value.value)
        ClientJournalOutcome.Unavailable -> JournalRead.Unavailable
    }
    private fun writeResult(value: ClientJournalOutcome<JournalWrite>): JournalWrite = when (value) {
        is ClientJournalOutcome.Value -> value.value
        ClientJournalOutcome.Unavailable -> JournalWrite.UNAVAILABLE
    }

    internal companion object {
        /** Initial data only; the caller still needs the real once-only native enrollment. */
        fun originalEmptyManifest(file: ClientJournalFileCore): ByteArray {
            val adapter = fromExisting(file)
            return adapter.encodeManifest(AndroidClientIntentManifestV1(owner = adapter.owner,
                journalId = file.journalId, keyId = file.keyId, revision = 0, archivedCount = 0, entries = emptyList()))
        }

        fun fromExisting(file: ClientJournalFileCore): AndroidEncryptedClientIntentJournal {
            check(file.kind == ClientJournalKind.INTENTS && file.actorId != null)
            return AndroidEncryptedClientIntentJournal(file)
        }

        /** Actual complete decode/census under the original retained acquisition before delivery. */
        fun validateExisting(file: ClientJournalFileCore) {
            fromExisting(file).readState()
        }
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
private data class AndroidClientIntentManifestV1(
    val profile: String = "ukkera.android.client-intents/v1",
    val owner: LocalClientIdentity,
    val journalId: DomainId,
    val keyId: DomainId,
    val revision: Long,
    val archivedCount: Long,
    val entries: List<StoredClientIntentV1>,
    /**
     * Absence in the original v1 format means no source was recorded. Omitting that exact
     * default preserves the original canonical manifest bytes; reads never migrate/rewrite.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val documentarySources: List<StoredClientDocumentarySourceV1>? = null,
) {
    val sourceRecords: List<StoredClientDocumentarySourceV1> get() = documentarySources.orEmpty()

    fun validate(expectedOwner: LocalClientIdentity, expectedJournalId: DomainId, expectedKeyId: DomainId) {
        check(profile == "ukkera.android.client-intents/v1" && owner == expectedOwner &&
            journalId == expectedJournalId && keyId == expectedKeyId)
        check(revision >= 0 && archivedCount in 0L..CLIENT_JOURNAL_MAX_ARCHIVES_V1.toLong() &&
            entries.size <= MAX_CLIENT_PENDING && archivedCount + entries.size <= CLIENT_JOURNAL_MAX_ARCHIVES_V1)
        check(entries.map { it.operationId }.toSet().size == entries.size && entries.none { it.acknowledged })
        check(documentarySources == null || sourceRecords.size in 1..MAX_CLIENT_DOCUMENTARY_SOURCES)
        check(sourceRecords.map { it.documentId }.toSet().size == sourceRecords.size)
        val sourceOperations = sourceRecords.flatMap { it.operationIds }
        check(sourceOperations.toSet().size == sourceOperations.size)
        sourceRecords.forEach { it.validate(expectedOwner) }
        entries.forEach { entry ->
            entry.validate(expectedOwner)
            val source = sourceFor(entry.operationId)
            check(!entry.documentaryCommand() || source != null)
            source?.let { check(it.accepts(entry)) }
        }
    }

    fun next(entries: List<StoredClientIntentV1>, archivedCount: Long = this.archivedCount): AndroidClientIntentManifestV1 =
        copy(revision = Math.addExact(revision, 1L), archivedCount = archivedCount, entries = entries)

    fun replace(entry: StoredClientIntentV1): AndroidClientIntentManifestV1 {
        check(entries.count { it.operationId == entry.operationId } == 1)
        return next(entries.map { if (it.operationId == entry.operationId) entry else it })
    }

    fun sourceFor(operationId: OperationId): StoredClientDocumentarySourceV1? =
        sourceRecords.singleOrNull { operationId in it.operationIds }

    fun withSource(source: StoredClientDocumentarySourceV1): AndroidClientIntentManifestV1 =
        copy(revision = Math.addExact(revision, 1L), documentarySources = sourceRecords + source)

    fun replaceSource(source: StoredClientDocumentarySourceV1): AndroidClientIntentManifestV1 {
        check(sourceRecords.count { it.documentId == source.documentId } == 1)
        return copy(revision = Math.addExact(revision, 1L),
            documentarySources = sourceRecords.map { if (it.documentId == source.documentId) source else it })
    }
}

private fun StoredClientIntentV1.documentaryCommand(): Boolean =
    commandVersion == 1 && commandName in setOf(LocalPurchasingDocumentProfileV1.BEGIN.name,
        LocalPurchasingDocumentProfileV1.CHUNK.name, LocalPurchasingDocumentProfileV1.COMPLETE.name)
