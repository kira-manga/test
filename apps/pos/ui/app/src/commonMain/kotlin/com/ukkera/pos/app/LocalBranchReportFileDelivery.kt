package com.ukkera.pos.app

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Metadata only. None of these IDs is a session, master lease, credential or export grant. */
@Serializable
data class LocalBranchReportFileOwner(
    val organizationId: DomainId,
    val companyId: DomainId,
    val branchId: DomainId,
    val actorId: DomainId,
    val deviceId: DomainId,
    val sessionId: DomainId,
    val sessionGeneration: Long,
    val connectionId: DomainId,
) {
    init { require(sessionGeneration > 0) }
    fun sameActor(other: LocalBranchReportFileOwner): Boolean = organizationId == other.organizationId &&
        companyId == other.companyId && branchId == other.branchId && actorId == other.actorId && deviceId == other.deviceId
}

/** Explicit source/CSV profile retained with the original native export query. */
@Serializable
enum class LocalBranchReportFileProfile { FOOD_REFUND_PAYOUT_V2, PAYMENT_CORRECTION_V2, RETAIL_TENDER_BREAKDOWN_V3 }

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class LocalBranchReportFileQuery(val section: String, val after: DomainId? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val profile: LocalBranchReportFileProfile? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val coverage: LocalBranchReportExportCoverage? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val selectedSaleId: DomainId? = null) {
    init {
        val source = LocalBranchReportSection.valueOf(section)
        require(when (profile) {
            null -> source != LocalBranchReportSection.PAYMENT_CORRECTIONS && source != LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS
            LocalBranchReportFileProfile.FOOD_REFUND_PAYOUT_V2 -> source == LocalBranchReportSection.FOOD_REFUND_OBLIGATIONS
            LocalBranchReportFileProfile.PAYMENT_CORRECTION_V2 -> source == LocalBranchReportSection.PAYMENT_CORRECTIONS
            LocalBranchReportFileProfile.RETAIL_TENDER_BREAKDOWN_V3 -> source == LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS
        })
        require(coverage == null || after == null)
        require(if (source == LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS)
            selectedSaleId != null && after == null && coverage == null else selectedSaleId == null)
    }
}

/** Source cut of the fresh EXPORT read, deliberately NOT the displayed cut or an audit revision. */
@Serializable
data class LocalBranchReportExportSource(
    val branchRevision: Revision,
    val observedAt: UtcInstant,
    val contentSha256: String,
    val byteCount: Int,
    val nextAfter: DomainId?,
) {
    init { PayloadDigest(contentSha256); require(byteCount in 1..MAX_LOCAL_REPORT_CSV_BYTES) }
}

/**
 * One native bridge's short local end/commit-start gate. Blocks contain bounded memory
 * observations/transitions only: never storage IO, hashing, encoding or dialog disposal.
 */
internal interface LocalReportCommitGate {
    fun <T> locked(block: () -> T): T
}

/** Synchronous same-instance observation, not remote authorization or a transferable grant. */
class LocalBranchReportExportLifetime internal constructor(
    internal val commitGate: LocalReportCommitGate,
    private val sameOwner: () -> Boolean,
) {
    private val ended = MutableStateFlow(false)
    fun isCurrent(): Boolean = !ended.value && try { sameOwner() } catch (_: Exception) { false }
    fun end() { commitGate.locked { ended.value = true } }
    /** A gateway child must share the interactive parent's gate, not invent another end race. */
    fun child(sameOwner: () -> Boolean): LocalBranchReportExportLifetime =
        LocalBranchReportExportLifetime(commitGate) { isCurrent() && sameOwner() }
    override fun toString(): String = "LocalBranchReportExportLifetime([LOCAL CAPABILITY])"
}

/**
 * One-shot LOCAL commit-start decision, made only by the native primitive after its preparation.
 * STARTED wins against a later end; it does not certify a syscall or revoke a remote permission.
 * There is no suspend, IO or second owner check between this decision and the native operation.
 */
internal class LocalReportCommitStart(private val gate: LocalReportCommitGate, private val sameOwner: () -> Boolean) {
    private enum class Decision { PREPARED, STARTED, REFUSED }
    private var decision = Decision.PREPARED
    fun admit(): Boolean = gate.locked {
        check(decision == Decision.PREPARED)
        val current = sameOwner()
        decision = if (current) Decision.STARTED else Decision.REFUSED
        current
    }
}

@Serializable
enum class LocalBranchReportFilePhase { SELECTED, SPOOLED, PUBLICATION_ATTEMPTED, PUBLISHED, CANCELLED, OUTCOME_UNKNOWN }

@Serializable
enum class LocalBranchReportFileDestinationKind { DESKTOP_LOCAL_FILE, ANDROID_LOCAL_DOCUMENT, IOS_LOCAL_DOCUMENT }

@Serializable
enum class LocalBranchReportAuditSlot { BEGIN, FINISH, RECOVER_PUBLISHED }

@Serializable
enum class LocalBranchReportAuditStage { REQUESTED, CLIENT_ATTESTED_PUBLISHED, CANCELLED_BEFORE_PUBLICATION, PUBLICATION_OUTCOME_UNKNOWN, REJECTED }

/** Actual retained master result coordinates; explicitly not a native durability certificate. */
@Serializable
data class LocalBranchReportAuditAcknowledgement(
    val stage: LocalBranchReportAuditStage,
    val auditRevision: Revision?,
    val resultSha256: String,
    val authorityEpoch: Revision,
    val journalSequence: Long,
    val recordedAt: UtcInstant,
) {
    init {
        PayloadDigest(resultSha256)
        require(journalSequence >= 0 && (stage == LocalBranchReportAuditStage.REJECTED) == (auditRevision == null))
        require(auditRevision == null || auditRevision.value in 0L..2L)
    }
}

/** Exact canonical bounded command bytes and original derived identity, persisted BEFORE send. */
@Serializable
data class LocalBranchReportAuditCommand(
    val operationId: OperationId,
    val canonicalBodyHex: String,
    val semanticSha256: String,
    val acknowledgement: LocalBranchReportAuditAcknowledgement? = null,
) {
    init {
        require(canonicalBodyHex.length in 2..8192 && canonicalBodyHex.length % 2 == 0 &&
            canonicalBodyHex.all { it in '0'..'9' || it in 'a'..'f' })
        PayloadDigest(semanticSha256)
    }
    override fun toString(): String = "LocalBranchReportAuditCommand([PRIVATE METADATA])"
}

/** Current inspection is separate from the historical, durable lifecycle record. */
enum class LocalBranchReportFilePresence { NOT_INSPECTED, VERIFIED_PRESENT, NOT_VERIFIED }

/**
 * Bounded, private native recovery record. No report amounts, CSV bytes, bearer, native path or
 * reusable authority is serialized. Original operation identities survive every caller/process
 * loss. A REQUESTED master audit does not establish that this file was not published.
 */
@Serializable
data class LocalBranchReportFileReceipt(
    val exportId: DomainId,
    val receiptId: DomainId,
    val beginOperationId: OperationId,
    val finishOperationId: OperationId,
    val recoveryOperationId: OperationId,
    val owner: LocalBranchReportFileOwner,
    val query: LocalBranchReportFileQuery,
    val fileName: String,
    val destinationKind: LocalBranchReportFileDestinationKind,
    val phase: LocalBranchReportFilePhase = LocalBranchReportFilePhase.SELECTED,
    val source: LocalBranchReportExportSource? = null,
    val spoolIdentity: String? = null,
    val begin: LocalBranchReportAuditCommand? = null,
    val finish: LocalBranchReportAuditCommand? = null,
    val recovery: LocalBranchReportAuditCommand? = null,
    val sequence: Int = 0,
    val previousSha256: String? = null,
    val version: Int = 1,
) {
    init {
        require(version == 1 && validLocalReportFileName(fileName) && sequence in 0 until MAX_LOCAL_REPORT_SNAPSHOTS)
        previousSha256?.let(::PayloadDigest)
        require((sequence == 0) == (previousSha256 == null))
        require(setOf(beginOperationId, finishOperationId, recoveryOperationId).size == 3)
        require(begin == null || begin.operationId == beginOperationId)
        require(finish == null || finish.operationId == finishOperationId)
        require(recovery == null || recovery.operationId == recoveryOperationId)
        require(finish == null || begin != null)
        require(recovery == null || finish != null)
        require(spoolIdentity == null || spoolIdentity.length in 1..256 && spoolIdentity.none { it.isISOControl() })
        require(query.coverage == null || source?.nextAfter == null)
        if (phase in setOf(LocalBranchReportFilePhase.SPOOLED, LocalBranchReportFilePhase.PUBLICATION_ATTEMPTED,
                LocalBranchReportFilePhase.PUBLISHED, LocalBranchReportFilePhase.OUTCOME_UNKNOWN)) {
            require(source != null && spoolIdentity != null)
        }
    }
    override fun toString(): String = "LocalBranchReportFileReceipt([PRIVATE METADATA])"
}

enum class LocalBranchReportFileProblem {
    NOT_BOUND, UNSUPPORTED_STORAGE, DESTINATION_REJECTED, FILE_EXISTS, CAPACITY_REACHED,
    OWNER_ENDED, RECOVERY_REQUIRED, STORAGE_UNCERTAIN,
}

sealed interface LocalBranchReportFileResult {
    data class Recorded(val receipt: LocalBranchReportFileReceipt, val presence: LocalBranchReportFilePresence) : LocalBranchReportFileResult
    data class Unavailable(val problem: LocalBranchReportFileProblem, val exportId: DomainId? = null) : LocalBranchReportFileResult
}

sealed interface LocalBranchReportFileSelection {
    class Selected(val destination: LocalBranchReportDestination) : LocalBranchReportFileSelection
    data object Cancelled : LocalBranchReportFileSelection
    data class Unavailable(val problem: LocalBranchReportFileProblem, val exportId: DomainId? = null) : LocalBranchReportFileSelection
}

sealed interface LocalBranchReportFileHistory {
    data class Available(val receipts: List<LocalBranchReportFileReceipt>, val nextAfter: DomainId?) : LocalBranchReportFileHistory
    data class Unavailable(val problem: LocalBranchReportFileProblem) : LocalBranchReportFileHistory
}

/** Opaque, process/host/lifetime-owned destination. Deserialized receipts cannot construct one. */
class LocalBranchReportDestination internal constructor(
    internal val delivery: LocalBranchReportFileDelivery,
    internal val host: Any,
    internal val lifetime: LocalBranchReportExportLifetime,
    val initialReceipt: LocalBranchReportFileReceipt,
    val localDirectoryLabel: String,
) {
    val exportId: DomainId get() = initialReceipt.exportId
    override fun toString(): String = "LocalBranchReportDestination([LOCAL CAPABILITY])"
}

/**
 * Native implementations only, using real file/dir force and an exclusive non-overwriting link.
 * No in-memory implementation returns saved success. All native IO failure tails are retained;
 * they are not repaired, truncated, retried under a new identity or automatically republished.
 * Instantiation/binding is availability, not independent filesystem/device qualification.
 */
abstract class LocalBranchReportFileDelivery internal constructor() {
    abstract val bound: Boolean
    internal abstract val commitGate: LocalReportCommitGate?
    internal abstract suspend fun nativeChoice(suggestedName: String, lifetime: LocalBranchReportExportLifetime): LocalReportNativeChoice?
    internal abstract fun hostCurrent(host: Any): Boolean
    /** Platform holds its actual cross-process native lock for the whole non-suspending block. */
    internal abstract suspend fun <T> disk(block: (LocalReportReceiptDisk) -> T): T

    /** Predicates must be bounded side-effect-free local state reads; choose still checks availability. */
    fun newLifetime(sameOwner: () -> Boolean): LocalBranchReportExportLifetime =
        LocalBranchReportExportLifetime(commitGate ?: fileFailure(LocalBranchReportFileProblem.NOT_BOUND), sameOwner)

    suspend fun choose(owner: LocalBranchReportFileOwner, query: LocalBranchReportFileQuery,
        lifetime: LocalBranchReportExportLifetime): LocalBranchReportFileSelection {
        if (!bound) return LocalBranchReportFileSelection.Unavailable(LocalBranchReportFileProblem.NOT_BOUND)
        if (lifetime.commitGate !== commitGate) return LocalBranchReportFileSelection.Unavailable(LocalBranchReportFileProblem.OWNER_ENDED)
        if (!lifetime.isCurrent()) return LocalBranchReportFileSelection.Cancelled
        val exportId = DomainId.random()
        return try {
            val choice = nativeChoice("report-${query.section.lowercase()}-${exportId.value.take(8)}.csv", lifetime)
                ?: return LocalBranchReportFileSelection.Cancelled
            if (!lifetime.isCurrent() || !hostCurrent(choice.host)) return LocalBranchReportFileSelection.Cancelled
            if (!validLocalReportFileName(choice.name)) return LocalBranchReportFileSelection.Unavailable(LocalBranchReportFileProblem.DESTINATION_REJECTED)
            val receipt = disk { store ->
                if (!lifetime.isCurrent() || !hostCurrent(choice.host)) fileFailure(LocalBranchReportFileProblem.OWNER_ENDED)
                val existing = store.receipts()
                if (existing.size >= MAX_LOCAL_REPORT_EXPORTS) fileFailure(LocalBranchReportFileProblem.CAPACITY_REACHED)
                existing.firstOrNull { it.owner.sameActor(owner) && it.phase !in setOf(
                    LocalBranchReportFilePhase.PUBLISHED, LocalBranchReportFilePhase.CANCELLED) }?.let {
                    throw LocalReportFileFailure(LocalBranchReportFileProblem.RECOVERY_REQUIRED, it.exportId)
                }
                if (store.exists(store.published(choice.name))) fileFailure(LocalBranchReportFileProblem.FILE_EXISTS)
                store.create(LocalBranchReportFileReceipt(exportId, DomainId.random(), OperationId.random(), OperationId.random(),
                    OperationId.random(), owner, query, choice.name, choice.kind))
            }
            LocalBranchReportFileSelection.Selected(LocalBranchReportDestination(this, choice.host, lifetime, receipt, choice.directoryLabel))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LocalReportFileFailure) { LocalBranchReportFileSelection.Unavailable(failure.problem, failure.exportId ?: exportId) }
        catch (_: Exception) { LocalBranchReportFileSelection.Unavailable(LocalBranchReportFileProblem.STORAGE_UNCERTAIN, exportId) }
    }

    /** Copies/wipes its input snapshot; the gateway still owns and must clear its original bytes. */
    suspend fun spool(destination: LocalBranchReportDestination, source: LocalBranchReportExportSource, bytes: ByteArray): LocalBranchReportFileResult {
        if (bytes.size != source.byteCount || bytes.size !in 1..MAX_LOCAL_REPORT_CSV_BYTES || sha256(bytes).value != source.contentSha256)
            return LocalBranchReportFileResult.Unavailable(LocalBranchReportFileProblem.STORAGE_UNCERTAIN, destination.exportId)
        val snapshot = bytes.copyOf()
        return try { access(destination.exportId) { store ->
            current(destination)
            val receipt = store.receipt(destination.exportId)
            check(receipt == destination.initialReceipt || receipt.owner == destination.initialReceipt.owner)
            check(receipt.phase == LocalBranchReportFilePhase.SELECTED && receipt.source == null && receipt.spoolIdentity == null)
            val withSource = store.append(receipt, receipt.copy(source = source))
            val spool = store.spool(receipt.exportId)
            store.writeExclusive(spool, snapshot)
            store.forceDirectory(store.directory(receipt.exportId))
            val identity = store.identity(spool)
            check(store.readBounded(spool, MAX_LOCAL_REPORT_CSV_BYTES).useCleared { sha256(it).value } == source.contentSha256)
            val recorded = store.append(withSource, withSource.copy(phase = LocalBranchReportFilePhase.SPOOLED, spoolIdentity = identity))
            LocalBranchReportFileResult.Recorded(recorded, LocalBranchReportFilePresence.NOT_INSPECTED)
        } } finally { snapshot.fill(0) }
    }

    /**
     * Called only after a fresh EXPORT read. Same original native host/interactive/connection
     * lifetime is observed at each prepared native commit-start under the local end gate. End
     * winning first prevents a new commit; commit-start winning first retains its genuine result.
     * The gate does not span IO, atomically revoke remote authority or expire a delivered file.
     */
    suspend fun publish(destination: LocalBranchReportDestination): LocalBranchReportFileResult = access(destination.exportId) { store ->
        current(destination)
        var receipt = store.receipt(destination.exportId)
        check(receipt.owner == destination.initialReceipt.owner && receipt.phase == LocalBranchReportFilePhase.SPOOLED)
        store.verifySpool(receipt)
        receipt = store.append(receipt, receipt.copy(phase = LocalBranchReportFilePhase.PUBLICATION_ATTEMPTED))
        if (!destinationCurrent(destination)) {
            val cancelled = store.append(receipt, receipt.copy(phase = LocalBranchReportFilePhase.CANCELLED))
            return@access LocalBranchReportFileResult.Recorded(cancelled, LocalBranchReportFilePresence.NOT_INSPECTED)
        }
        // The native primitive performs all preparation BEFORE its short commit-start decision.
        when (store.linkComplete(receipt, commitStart(destination))) {
            LocalReportLinkOutcome.NOT_ATTEMPTED -> {
                receipt = store.append(receipt, receipt.copy(phase = LocalBranchReportFilePhase.CANCELLED))
                LocalBranchReportFileResult.Recorded(receipt, LocalBranchReportFilePresence.NOT_INSPECTED)
            }
            LocalReportLinkOutcome.UNCERTAIN -> {
                receipt = store.append(receipt, receipt.copy(phase = LocalBranchReportFilePhase.OUTCOME_UNKNOWN))
                LocalBranchReportFileResult.Recorded(receipt, LocalBranchReportFilePresence.NOT_VERIFIED)
            }
            LocalReportLinkOutcome.DURABLE -> {
                // Desktop's verified link already disclosed the file: a later lock/cancel
                // cannot undo that fact. Android provider admission is a second disclosure
                // boundary; an ended owner must leave its still-private artifact unadmitted.
                if (receipt.destinationKind == LocalBranchReportFileDestinationKind.ANDROID_LOCAL_DOCUMENT) {
                    // Chain reread/fsync, successor hash/validation/encoding and native path/
                    // parent preparation all precede the admitting write's FINAL gated check.
                    // Refusal writes no PUBLISHED snapshot; recovery cannot promote this link.
                    val admitted = store.appendAtCommitStart(receipt, receipt.copy(phase = LocalBranchReportFilePhase.PUBLISHED),
                        commitStart(destination))
                    if (admitted == null) {
                        receipt = store.append(receipt, receipt.copy(phase = LocalBranchReportFilePhase.OUTCOME_UNKNOWN))
                        LocalBranchReportFileResult.Recorded(receipt, LocalBranchReportFilePresence.NOT_VERIFIED)
                    } else {
                        // An end after commit-start does not relabel actual publication cancelled.
                        LocalBranchReportFileResult.Recorded(admitted, LocalBranchReportFilePresence.VERIFIED_PRESENT)
                    }
                } else {
                    receipt = store.append(receipt, receipt.copy(phase = LocalBranchReportFilePhase.PUBLISHED))
                    LocalBranchReportFileResult.Recorded(receipt, LocalBranchReportFilePresence.VERIFIED_PRESENT)
                }
            }
        }
    }

    /** Evidence-only cleanup may finish after caller cancellation; it never deletes any artifact. */
    suspend fun abandon(destination: LocalBranchReportDestination): LocalBranchReportFileResult = withContext(NonCancellable) {
        access(destination.exportId) { store ->
            check(destination.delivery === this@LocalBranchReportFileDelivery)
            val receipt = store.receipt(destination.exportId)
            check(receipt.owner == destination.initialReceipt.owner)
            val retained = if (receipt.phase in setOf(LocalBranchReportFilePhase.SELECTED, LocalBranchReportFilePhase.SPOOLED))
                store.append(receipt, receipt.copy(phase = LocalBranchReportFilePhase.CANCELLED)) else receipt
            LocalBranchReportFileResult.Recorded(retained, LocalBranchReportFilePresence.NOT_INSPECTED)
        }
    }

    /** Only canonical audit metadata is retained here; a prepared command is NOT an acknowledgement. */
    suspend fun prepareAudit(exportId: DomainId, owner: LocalBranchReportFileOwner, slot: LocalBranchReportAuditSlot,
        command: LocalBranchReportAuditCommand): LocalBranchReportFileResult = access(exportId) { store ->
        val receipt = store.receipt(exportId)
        check(receipt.owner.sameActor(owner) && command.acknowledgement == null)
        val previous = receipt.auditCommand(slot)
        if (previous != null) {
            check(previous.copy(acknowledgement = null) == command)
            LocalBranchReportFileResult.Recorded(receipt, LocalBranchReportFilePresence.NOT_INSPECTED)
        } else {
            val updated = when (slot) {
                LocalBranchReportAuditSlot.BEGIN -> receipt.copy(begin = command)
                LocalBranchReportAuditSlot.FINISH -> receipt.copy(finish = command)
                LocalBranchReportAuditSlot.RECOVER_PUBLISHED -> receipt.copy(recovery = command)
            }
            LocalBranchReportFileResult.Recorded(store.append(receipt, updated), LocalBranchReportFilePresence.NOT_INSPECTED)
        }
    }

    /** The gateway supplies only an actual matched retained kernel result after current authorization. */
    suspend fun rememberAudit(exportId: DomainId, owner: LocalBranchReportFileOwner, slot: LocalBranchReportAuditSlot,
        command: LocalBranchReportAuditCommand, acknowledgement: LocalBranchReportAuditAcknowledgement): LocalBranchReportFileResult = access(exportId) { store ->
        val receipt = store.receipt(exportId)
        check(receipt.owner.sameActor(owner))
        val original = checkNotNull(receipt.auditCommand(slot))
        check(original.copy(acknowledgement = null) == command.copy(acknowledgement = null))
        if (original.acknowledgement != null) {
            check(original.acknowledgement == acknowledgement)
            LocalBranchReportFileResult.Recorded(receipt, LocalBranchReportFilePresence.NOT_INSPECTED)
        } else {
            val acknowledged = original.copy(acknowledgement = acknowledgement)
            val updated = when (slot) {
                LocalBranchReportAuditSlot.BEGIN -> receipt.copy(begin = acknowledged)
                LocalBranchReportAuditSlot.FINISH -> receipt.copy(finish = acknowledged)
                LocalBranchReportAuditSlot.RECOVER_PUBLISHED -> receipt.copy(recovery = acknowledged)
            }
            LocalBranchReportFileResult.Recorded(store.append(receipt, updated), LocalBranchReportFilePresence.NOT_INSPECTED)
        }
    }

    /** Caller MUST first authenticate own-actor metadata access. No new destination/handle is returned. */
    suspend fun retained(exportId: DomainId, owner: LocalBranchReportFileOwner): LocalBranchReportFileResult = access(exportId) { store ->
        val receipt = store.receipt(exportId)
        check(receipt.owner.sameActor(owner))
        LocalBranchReportFileResult.Recorded(receipt, LocalBranchReportFilePresence.NOT_INSPECTED)
    }

    /** Caller MUST first authenticate own-actor metadata access. No new destination/handle is returned. */
    suspend fun history(owner: LocalBranchReportFileOwner, after: DomainId? = null): LocalBranchReportFileHistory = try {
        disk { store ->
            val all = store.receipts().filter { it.owner.sameActor(owner) && (after == null || it.exportId.value > after.value) }
                .sortedBy { it.exportId.value }
            val page = all.take(8)
            LocalBranchReportFileHistory.Available(page, if (all.size > page.size) page.last().exportId else null)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: LocalReportFileFailure) { LocalBranchReportFileHistory.Unavailable(failure.problem) }
    catch (_: Exception) { LocalBranchReportFileHistory.Unavailable(LocalBranchReportFileProblem.STORAGE_UNCERTAIN) }

    /**
     * Explicit recovery only. A present retained hard link may be verified/forced. An absent or
     * changed target cannot prove no earlier disclosure; there is NEVER an automatic second link.
     */
    suspend fun inspect(exportId: DomainId, owner: LocalBranchReportFileOwner): LocalBranchReportFileResult = access(exportId) { store ->
        var receipt = store.receipt(exportId)
        check(receipt.owner.sameActor(owner))
        val inspection = if (receipt.source != null && receipt.spoolIdentity != null) store.recoverPublication(receipt)
            else LocalReportRecoveredPublication.NOT_VERIFIED
        val verified = inspection == LocalReportRecoveredPublication.ALREADY_PUBLISHED_VERIFIED
        if (verified && receipt.phase in setOf(LocalBranchReportFilePhase.PUBLICATION_ATTEMPTED, LocalBranchReportFilePhase.OUTCOME_UNKNOWN))
            receipt = store.append(receipt, receipt.copy(phase = LocalBranchReportFilePhase.PUBLISHED))
        // SELECTED/SPOOLED prove that this implementation did not start its publication syscall.
        // Retain their private spool and metadata as abandoned evidence rather than erasing them.
        if (receipt.phase in setOf(LocalBranchReportFilePhase.SELECTED, LocalBranchReportFilePhase.SPOOLED))
            receipt = store.append(receipt, receipt.copy(phase = LocalBranchReportFilePhase.CANCELLED))
        LocalBranchReportFileResult.Recorded(receipt, if (verified) LocalBranchReportFilePresence.VERIFIED_PRESENT
            else LocalBranchReportFilePresence.NOT_VERIFIED)
    }

    private fun destinationCurrent(destination: LocalBranchReportDestination): Boolean = destination.delivery === this &&
        destination.lifetime.commitGate === commitGate &&
        destination.lifetime.isCurrent() && hostCurrent(destination.host)
    private fun commitStart(destination: LocalBranchReportDestination): LocalReportCommitStart =
        LocalReportCommitStart(commitGate ?: fileFailure(LocalBranchReportFileProblem.NOT_BOUND)) { destinationCurrent(destination) }
    private fun current(destination: LocalBranchReportDestination) {
        if (!destinationCurrent(destination)) fileFailure(LocalBranchReportFileProblem.OWNER_ENDED)
    }
    private suspend fun access(exportId: DomainId, block: (LocalReportReceiptDisk) -> LocalBranchReportFileResult): LocalBranchReportFileResult = try {
        disk(block)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: LocalReportFileFailure) { LocalBranchReportFileResult.Unavailable(failure.problem, exportId) }
    catch (_: Exception) { LocalBranchReportFileResult.Unavailable(LocalBranchReportFileProblem.STORAGE_UNCERTAIN, exportId) }
}

object UnavailableLocalBranchReportFiles : LocalBranchReportFileDelivery() {
    override val bound: Boolean = false
    internal override val commitGate: LocalReportCommitGate? = null
    override suspend fun nativeChoice(suggestedName: String, lifetime: LocalBranchReportExportLifetime): LocalReportNativeChoice? = null
    override fun hostCurrent(host: Any): Boolean = false
    override suspend fun <T> disk(block: (LocalReportReceiptDisk) -> T): T = fileFailure(LocalBranchReportFileProblem.NOT_BOUND)
}

internal data class LocalReportNativeChoice(val host: Any, val name: String, val directoryLabel: String,
    val kind: LocalBranchReportFileDestinationKind)
internal class LocalReportFileFailure(val problem: LocalBranchReportFileProblem, val exportId: DomainId? = null) : Exception()
internal fun fileFailure(problem: LocalBranchReportFileProblem): Nothing = throw LocalReportFileFailure(problem)
internal enum class LocalReportLinkOutcome { NOT_ATTEMPTED, DURABLE, UNCERTAIN }
internal enum class LocalReportRecoveredPublication { ALREADY_PUBLISHED_VERIFIED, PRIVATE_FILE_ONLY, NOT_VERIFIED }

// The audited native profile is narrower than the pure CSV encoder's 131072-byte upper bound.
internal const val MAX_LOCAL_REPORT_CSV_BYTES = 65_536
internal const val MAX_LOCAL_REPORT_EXPORTS = 64
internal const val MAX_LOCAL_REPORT_SNAPSHOTS = 32
internal const val MAX_LOCAL_REPORT_RECEIPT_BYTES = 32_768
/**
 * A single exact local filename, including Arabic letters/digits and combining marks. Keep the
 * user's bytes: no transliteration, Unicode normalization, path expansion or automatic suffix.
 * This extends (and still accepts) the original bounded ASCII profile so retained v1 receipts
 * remain readable. Separators, controls, bidi formatting and lone surrogates are not names.
 */
internal fun validLocalReportFileName(name: String): Boolean {
    if (name.length !in 5..84 || !name.endsWith(".csv") || !name.first().isLetterOrDigit() ||
        name.contains("..") || name[name.length - 5] == ' ') return false
    if (!name.all { character -> character.isLetterOrDigit() || character in " ._-" ||
            character.category == CharCategory.NON_SPACING_MARK ||
            character.category == CharCategory.COMBINING_SPACING_MARK ||
            character.category == CharCategory.ENCLOSING_MARK }) return false
    // The fixed character bound precedes this small allocation. Native storage still owns exact
    // destination/custody/no-replace checks; this lexical check supplies no publication authority.
    return name.encodeToByteArray().size <= 255
}

private val receiptJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; allowStructuredMapKeys = false }
internal fun encodeLocalReportReceipt(receipt: LocalBranchReportFileReceipt): ByteArray =
    receiptJson.encodeToString(LocalBranchReportFileReceipt.serializer(), receipt).encodeToByteArray().also {
        require(it.size <= MAX_LOCAL_REPORT_RECEIPT_BYTES)
    }
internal fun decodeLocalReportReceipt(bytes: ByteArray): LocalBranchReportFileReceipt {
    require(bytes.size in 1..MAX_LOCAL_REPORT_RECEIPT_BYTES)
    return receiptJson.decodeFromString(LocalBranchReportFileReceipt.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
}
internal inline fun <T> ByteArray.useCleared(block: (ByteArray) -> T): T = try { block(this) } finally { fill(0) }
fun LocalBranchReportFileReceipt.auditCommand(slot: LocalBranchReportAuditSlot): LocalBranchReportAuditCommand? = when (slot) {
    LocalBranchReportAuditSlot.BEGIN -> begin
    LocalBranchReportAuditSlot.FINISH -> finish
    LocalBranchReportAuditSlot.RECOVER_PUBLISHED -> recovery
}

/**
 * Synchronous primitive seam implemented by actual native private-directory IO. Paths are closed
 * relative names assembled here, never caller paths/provider URIs. Each operation runs while the
 * native store holds its OS file lock and validates root/inode/owner continuity. Torn/unknown tails
 * fail closed and remain on disk; append-only snapshots are not rollback/history-completeness proof.
 */
internal abstract class LocalReportReceiptDisk {
    abstract fun entries(directory: String, maximum: Int): List<String>
    abstract fun makeDirectory(path: String)
    abstract fun exists(path: String): Boolean
    abstract fun readBounded(path: String, maximum: Int): ByteArray
    abstract fun writeExclusive(path: String, bytes: ByteArray)
    /** False means the gate refused BEFORE open/create; a started IO failure must throw instead. */
    abstract fun writeExclusiveAtCommitStart(path: String, bytes: ByteArray, commitStart: LocalReportCommitStart): Boolean
    abstract fun identity(path: String): String
    abstract fun forceDirectory(path: String)
    abstract fun linkComplete(receipt: LocalBranchReportFileReceipt, commitStart: LocalReportCommitStart): LocalReportLinkOutcome
    abstract fun verifyPublished(receipt: LocalBranchReportFileReceipt): Boolean
    /** Must not expose a private/unadmitted provider file while doing metadata-only recovery. */
    abstract fun recoverPublication(receipt: LocalBranchReportFileReceipt): LocalReportRecoveredPublication

    fun directory(id: DomainId): String = "receipts/${id.value}"
    fun spool(id: DomainId): String = "${directory(id)}/content.csv"
    fun published(name: String): String { require(validLocalReportFileName(name)); return "published/$name" }
    private fun recordPath(receipt: LocalBranchReportFileReceipt): String = "${directory(receipt.exportId)}/${receipt.sequence.toString().padStart(2, '0')}.json"

    fun receipts(): List<LocalBranchReportFileReceipt> = entries("receipts", MAX_LOCAL_REPORT_EXPORTS + 1).also {
        check(it.size <= MAX_LOCAL_REPORT_EXPORTS)
    }.map { receipt(DomainId(it)) }

    fun receipt(id: DomainId): LocalBranchReportFileReceipt {
        val names = entries(directory(id), MAX_LOCAL_REPORT_SNAPSHOTS + 2)
        check(names.all { it == "content.csv" || Regex("[0-9]{2}\\.json").matches(it) })
        val snapshots = names.filter { it != "content.csv" }.sorted()
        check(snapshots.isNotEmpty() && snapshots.size <= MAX_LOCAL_REPORT_SNAPSHOTS)
        var previous: LocalBranchReportFileReceipt? = null
        snapshots.forEachIndexed { index, name ->
            check(name == "${index.toString().padStart(2, '0')}.json")
            val current = readBounded("${directory(id)}/$name", MAX_LOCAL_REPORT_RECEIPT_BYTES).useCleared(::decodeLocalReportReceipt)
            check(current.exportId == id && current.sequence == index)
            if (index == 0) check(current.phase == LocalBranchReportFilePhase.SELECTED && current.source == null &&
                current.spoolIdentity == null && current.begin == null && current.finish == null && current.recovery == null)
            if (previous != null) validateSuccessor(checkNotNull(previous), current)
            previous = current
        }
        forceDirectory(directory(id))
        forceDirectory("receipts")
        return checkNotNull(previous)
    }

    fun create(receipt: LocalBranchReportFileReceipt): LocalBranchReportFileReceipt {
        check(receipt.sequence == 0 && receipt.phase == LocalBranchReportFilePhase.SELECTED)
        makeDirectory(directory(receipt.exportId))
        forceDirectory("receipts")
        encodeLocalReportReceipt(receipt).useCleared { writeExclusive(recordPath(receipt), it) }
        forceDirectory(directory(receipt.exportId))
        return receipt
    }

    fun append(previous: LocalBranchReportFileReceipt, next: LocalBranchReportFileReceipt): LocalBranchReportFileReceipt {
        val stamped = prepareSuccessor(previous, next)
        encodeLocalReportReceipt(stamped).useCleared { writeExclusive(recordPath(stamped), it) }
        forceDirectory(directory(stamped.exportId))
        return stamped
    }

    /** Native admission is last, never a boolean checked before this substantial preparation. */
    fun appendAtCommitStart(previous: LocalBranchReportFileReceipt, next: LocalBranchReportFileReceipt,
        commitStart: LocalReportCommitStart): LocalBranchReportFileReceipt? {
        val stamped = prepareSuccessor(previous, next)
        val admitted = encodeLocalReportReceipt(stamped).useCleared {
            writeExclusiveAtCommitStart(recordPath(stamped), it, commitStart)
        }
        if (!admitted) return null
        forceDirectory(directory(stamped.exportId))
        return stamped
    }

    private fun prepareSuccessor(previous: LocalBranchReportFileReceipt, next: LocalBranchReportFileReceipt): LocalBranchReportFileReceipt {
        check(receipt(previous.exportId) == previous)
        val stamped = next.copy(sequence = previous.sequence + 1,
            previousSha256 = encodeLocalReportReceipt(previous).useCleared { sha256(it).value })
        validateSuccessor(previous, stamped)
        return stamped
    }

    fun verifySpool(receipt: LocalBranchReportFileReceipt) {
        val source = checkNotNull(receipt.source)
        val path = spool(receipt.exportId)
        check(identity(path) == receipt.spoolIdentity)
        readBounded(path, MAX_LOCAL_REPORT_CSV_BYTES).useCleared {
            check(it.size == source.byteCount && sha256(it).value == source.contentSha256)
        }
    }

    private fun validateSuccessor(previous: LocalBranchReportFileReceipt, next: LocalBranchReportFileReceipt) {
        check(next.exportId == previous.exportId && next.receiptId == previous.receiptId && next.owner == previous.owner &&
            next.query == previous.query && next.fileName == previous.fileName && next.beginOperationId == previous.beginOperationId &&
            next.finishOperationId == previous.finishOperationId && next.recoveryOperationId == previous.recoveryOperationId &&
            next.destinationKind == previous.destinationKind && next.sequence == previous.sequence + 1 &&
            next.previousSha256 == encodeLocalReportReceipt(previous).useCleared { sha256(it).value })
        check(previous.source == null || next.source == previous.source)
        check(previous.spoolIdentity == null || next.spoolIdentity == previous.spoolIdentity)
        LocalBranchReportAuditSlot.entries.forEach { slot ->
            previous.auditCommand(slot)?.let { old ->
                val retained = checkNotNull(next.auditCommand(slot))
                check(old.copy(acknowledgement = null) == retained.copy(acknowledgement = null))
                check(old.acknowledgement == null || old.acknowledgement == retained.acknowledgement)
            }
        }
        val allowed = when (previous.phase) {
            LocalBranchReportFilePhase.SELECTED -> setOf(previous.phase, LocalBranchReportFilePhase.SPOOLED, LocalBranchReportFilePhase.CANCELLED)
            LocalBranchReportFilePhase.SPOOLED -> setOf(previous.phase, LocalBranchReportFilePhase.PUBLICATION_ATTEMPTED, LocalBranchReportFilePhase.CANCELLED)
            LocalBranchReportFilePhase.PUBLICATION_ATTEMPTED -> setOf(previous.phase, LocalBranchReportFilePhase.PUBLISHED,
                LocalBranchReportFilePhase.CANCELLED, LocalBranchReportFilePhase.OUTCOME_UNKNOWN)
            LocalBranchReportFilePhase.OUTCOME_UNKNOWN -> setOf(previous.phase, LocalBranchReportFilePhase.PUBLISHED)
            LocalBranchReportFilePhase.PUBLISHED, LocalBranchReportFilePhase.CANCELLED -> setOf(previous.phase)
        }
        check(next.phase in allowed)
    }
}
