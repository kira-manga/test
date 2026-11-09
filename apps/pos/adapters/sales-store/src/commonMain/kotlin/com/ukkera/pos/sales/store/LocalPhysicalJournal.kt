package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.PhysicalRecordingCommittedStart
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

/** Full native requirement, including domain-owned amount/tender/target/lineage commitments. */
public class LocalPhysicalJournalRequirementV1(
    public val scope: BranchScope,
    public val originalActorId: DomainId,
    public val intentOperationId: OperationId,
    public val intentSemanticDigest: PayloadDigest,
    public val recordingOperationId: OperationId,
    public val resolutionOperationId: OperationId,
    public val attemptId: DomainId,
    public val domainProfile: String,
    public val command: CommandKey,
    public val targetBinding: PayloadDigest,
    public val masterAllocationId: DomainId,
    public val clientAllocationId: DomainId,
    public val masterDeviceId: DomainId,
    public val clientDeviceId: DomainId,
    public val masterStorageProfile: LocalPhysicalSpoolStorageProfileV1,
    public val clientStorageProfile: LocalPhysicalSpoolStorageProfileV1,
    public val canonicalDomainRequirement: StoredBytes,
) {
    init {
        require(setOf(intentOperationId, recordingOperationId, resolutionOperationId).size == 3)
        require(masterAllocationId != clientAllocationId)
        physicalSpoolTextBound(domainProfile, 96)
        require(canonicalDomainRequirement.size in 1..LocalPhysicalJournalFormatV1.MAX_REQUIREMENT_BYTES)
        // TOTAL includes shared fields/framing, not 8KiB domain data plus uncounted metadata.
        journalRequirementBytes(this).withSpoolBytes { require(it.size <= LocalPhysicalJournalFormatV1.MAX_REQUIREMENT_BYTES) }
    }
    public val canonicalBytes: StoredBytes get() = StoredBytes(journalRequirementBytes(this))
    public companion object {
        /** Pure canonical data decoding only, never a native-backed admission assertion. */
        public fun decode(bytes: StoredBytes): LocalPhysicalJournalRequirementV1 {
            require(bytes.size in 1..LocalPhysicalJournalFormatV1.MAX_REQUIREMENT_BYTES)
            return bytes.copyBytes().withSpoolBytes(::journalReadRequirement)
        }
    }
    override fun toString(): String = "LocalPhysicalJournalRequirementV1([REDACTED])"
}

public enum class LocalPhysicalJournalStartKind { PAYMENT, REFUND }
public enum class LocalPhysicalOriginalKind { KNOWN, UNKNOWN }

/**
 * Trusted installed domain codec, not a callback decoded from RPC. Kernel composition must close
 * the command/profile set. Retail/food/exchange validate their own exact canonical requirements
 * and bodies; no domain may manufacture another domain's document or tender lineage.
 * These are bounded semantic checks, not financial application or authority issuance.
 */
public interface LocalPhysicalJournalDomain {
    public val domainProfile: String
    public val command: CommandKey
    public val startKind: LocalPhysicalJournalStartKind
    public fun validateRequirement(requirement: LocalPhysicalJournalRequirementV1)
    public fun classifyOriginal(requirement: LocalPhysicalJournalRequirementV1, body: StoredBytes): LocalPhysicalOriginalKind
    public fun validateResolution(requirement: LocalPhysicalJournalRequirementV1, original: StoredBytes,
        resolverActorId: DomainId, resolverAuthorization: StoredBytes, body: StoredBytes)
}

public enum class LocalPhysicalJournalWrite {
    RETAINED, ALREADY_RETAINED, IDENTITY_CONFLICT, INVALID_INPUT, START_REQUIRED,
    CONSUMED_REQUIRED, ORIGINAL_UNKNOWN_REQUIRED, BUSY, HELD,
}

public enum class LocalPhysicalJournalState {
    ALLOCATED, INTENT_COMMITTED, ACTION_MAY_HAVE_STARTED, RAW_KNOWN_RETAINED, RAW_UNKNOWN_RETAINED,
}

/** No SQL/financial success is represented here; absent bytes never mean no human action. */
public class LocalPhysicalOriginalSnapshot internal constructor(
    public val receipt: LocalPhysicalJournalReceipt,
    public val certificate: StoredBytes?,
    public val actionMayHaveStarted: Boolean,
    public val original: StoredBytes?,
    public val originalKind: LocalPhysicalOriginalKind?,
) {
    public val originalDigest: PayloadDigest? get() = original?.let(::journalBodyDigest)
    public val originalSemanticDigest: PayloadDigest? get() = original?.let { body -> receipt.binding.requirement.let { r ->
        semanticDigest(r.scope, r.originalActorId, r.command, body)
    } }
    public val state: LocalPhysicalJournalState get() = when {
        originalKind == LocalPhysicalOriginalKind.KNOWN -> LocalPhysicalJournalState.RAW_KNOWN_RETAINED
        originalKind == LocalPhysicalOriginalKind.UNKNOWN -> LocalPhysicalJournalState.RAW_UNKNOWN_RETAINED
        actionMayHaveStarted -> LocalPhysicalJournalState.ACTION_MAY_HAVE_STARTED
        certificate != null -> LocalPhysicalJournalState.INTENT_COMMITTED
        else -> LocalPhysicalJournalState.ALLOCATED
    }
    override fun toString(): String = "LocalPhysicalOriginalSnapshotV1([REDACTED])"
}

public sealed interface LocalPhysicalOriginalRead {
    public class Read internal constructor(public val snapshot: LocalPhysicalOriginalSnapshot) : LocalPhysicalOriginalRead
    public data object Busy : LocalPhysicalOriginalRead
    public data object Held : LocalPhysicalOriginalRead
}

public class LocalPhysicalLinkedResolution internal constructor(
    public val originalOperationId: OperationId,
    public val originalDigest: PayloadDigest,
    public val originalActorId: DomainId,
    public val attemptId: DomainId,
    public val resolutionOperationId: OperationId,
    public val resolverActorId: DomainId,
    public val resolverAuthorization: StoredBytes,
    public val body: StoredBytes,
) {
    public val bodyDigest: PayloadDigest get() = journalBodyDigest(body)
    override fun toString(): String = "LocalPhysicalLinkedResolutionV1([REDACTED])"
}

public sealed interface LocalPhysicalResolutionRead {
    public class Read internal constructor(public val resolution: LocalPhysicalLinkedResolution?) : LocalPhysicalResolutionRead
    /** A damaged later cell does not retire access to the intact original UNKNOWN. */
    public data object Damaged : LocalPhysicalResolutionRead
    public data object Busy : LocalPhysicalResolutionRead
    public data object Held : LocalPhysicalResolutionRead
}

public enum class LocalPhysicalReplyCacheKind { MASTER_RAW, SQL_IMPORT, SQL_APPLICATION }
public sealed interface LocalPhysicalReplyCacheRead {
    /** Cached bytes only, never authenticated financial result authority. */
    public class Read internal constructor(public val body: StoredBytes?) : LocalPhysicalReplyCacheRead
    public data object Damaged : LocalPhysicalReplyCacheRead
    public data object Busy : LocalPhysicalReplyCacheRead
    public data object Held : LocalPhysicalReplyCacheRead
}

public sealed interface LocalPhysicalStartWrite {
    /** Ephemeral proof of a NEW local force. Not restored from files, replay or positive lookup. */
    public class Fresh internal constructor(internal val journal: LocalPhysicalJournal,
        internal val certificate: StoredBytes) : LocalPhysicalStartWrite
    /** A genuine certificate was newly forced, but required new-action capacity was unavailable. */
    public data object Retained : LocalPhysicalStartWrite
    public data object AlreadyRetained : LocalPhysicalStartWrite
    public data object IdentityConflict : LocalPhysicalStartWrite
    public data object Busy : LocalPhysicalStartWrite
    public data object Held : LocalPhysicalStartWrite
}

/**
 * Eight independently authenticated fixed cells backed by the real native FD/key owner. All IO
 * happens on the trusted worker, never a Room borrowed loan or UI thread. Required-origin damage
 * holds this obligation. Optional ACK/resolution damage never creates vacancy or hides an intact
 * original. This object grants neither new cashier rights nor financial/result disclosure rights.
 */
public class LocalPhysicalJournal internal constructor(
    internal val catalogue: LocalPhysicalAllocationCatalogue,
    public val receipt: LocalPhysicalJournalReceipt,
    private val owned: PhysicalNativeOwnedFileV1,
    internal val domain: LocalPhysicalJournalDomain,
) {
    public val lifetimeId: DomainId = DomainId.random()
    private val expectedFrames: MutableMap<PhysicalJournalCellV1, PayloadDigest> = mutableMapOf()
    private val usedNonces: MutableSet<String> = mutableSetOf()
    private val failedOptionalWrites: MutableSet<PhysicalJournalCellV1> = mutableSetOf()
    // Tail damage denies new action for this owner lifetime, not custody of an intact original.
    private var newActionCapacityDenied = false
    private var published = false
    private var freshStart: LocalPhysicalStartWrite.Fresh? = null
    private var consumedFresh: LocalPhysicalStartWrite.Fresh? = null
    private var instructionTaken = false

    public fun readOriginal(): LocalPhysicalOriginalRead = when (val result = owned.file.access {
        check(published)
        val snapshot = readPrimary()
        verifyHeader()
        snapshot
    }) {
        is PhysicalSpoolAccess.Value -> LocalPhysicalOriginalRead.Read(result.value)
        PhysicalSpoolAccess.Busy -> LocalPhysicalOriginalRead.Busy
        PhysicalSpoolAccess.Held -> LocalPhysicalOriginalRead.Held
    }

    /** The core type has no public constructor and can only come from a real nonreplayed start. */
    internal fun persistTrueStart(start: PhysicalRecordingCommittedStart): LocalPhysicalStartWrite {
        if (!validJournalInput {
            val r = receipt.binding.requirement
            require(start.scope == r.scope && start.originalActorId == r.originalActorId && start.operationId == r.intentOperationId &&
                start.command == r.command && start.semanticDigest == r.intentSemanticDigest && start.domainProfile == r.domainProfile &&
                start.requirementBytes == r.canonicalBytes && start.startKind.name == domain.startKind.name &&
                start.authority.deviceId == r.masterDeviceId && start.sourceCommitments.size == 2 &&
                start.sourceCommitments.singleOrNull { it.key == receipt.sourceChange.key }?.let {
                    it.schema == LocalPhysicalJournalFormatV1.SOURCE_SCHEMA &&
                        it.revision.value == 1L && it.digest == journalBodyDigest(receipt.sourceCommitment)
                } == true &&
                start.projection.size in 1..LocalPhysicalJournalFormatV1.MAX_START_BYTES)
        }) return LocalPhysicalStartWrite.IdentityConflict
        return when (val result = owned.file.access {
            check(published)
            val prior = readPrimary()
            when {
                prior.certificate != null -> if (prior.certificate == start.projection) LocalPhysicalStartWrite.AlreadyRetained
                    else LocalPhysicalStartWrite.IdentityConflict
                else -> {
                    val capacityBefore = readNewActionCapacity() != null
                    // SQL has already committed. Unavailable future capacity must not suppress
                    // retention of the genuine obligation or mislabel it as historical replay.
                    writeCell(PhysicalJournalCellV1.TRUE_START_CERT, start.projection)
                    check(readPrimary().certificate == start.projection)
                    val capacityAfter = readNewActionCapacity() != null
                    if (capacityBefore && capacityAfter)
                        LocalPhysicalStartWrite.Fresh(this, start.projection).also { freshStart = it }
                    else LocalPhysicalStartWrite.Retained
                }
            }.also { verifyHeader() }
        }) {
            is PhysicalSpoolAccess.Value -> result.value
            PhysicalSpoolAccess.Busy -> LocalPhysicalStartWrite.Busy
            PhysicalSpoolAccess.Held -> LocalPhysicalStartWrite.Held
        }
    }

    /** Retains exact original known/UNKNOWN bytes. Rights loss may hold application, not erase these. */
    public fun retainOriginal(operationId: OperationId, body: StoredBytes): LocalPhysicalJournalWrite {
        val r = receipt.binding.requirement
        if (operationId != r.recordingOperationId) return LocalPhysicalJournalWrite.IDENTITY_CONFLICT
        if (!validJournalInput { validateOriginal(body) }) return LocalPhysicalJournalWrite.INVALID_INPUT
        return write {
            val prior = readPrimary()
            when {
                prior.certificate == null -> LocalPhysicalJournalWrite.START_REQUIRED
                !prior.actionMayHaveStarted -> LocalPhysicalJournalWrite.CONSUMED_REQUIRED
                prior.original != null -> if (prior.original == body) LocalPhysicalJournalWrite.ALREADY_RETAINED
                    else LocalPhysicalJournalWrite.IDENTITY_CONFLICT
                else -> { writeCell(PhysicalJournalCellV1.ORIGINAL, body); check(readPrimary().original == body); LocalPhysicalJournalWrite.RETAINED }
            }
        }
    }

    /**
     * One bounded linked evidence record; NEVER another physical start. Service composition must
     * authenticate the resolver route, and SQL separately authorizes resolution/application. The
     * recorded authorization bytes/actor are preserved evidence, not a bearer or Boolean grant.
     */
    public fun retainResolution(operationId: OperationId, resolverActorId: DomainId,
        resolverAuthorization: StoredBytes, body: StoredBytes): LocalPhysicalJournalWrite {
        if (operationId != receipt.binding.requirement.resolutionOperationId) return LocalPhysicalJournalWrite.IDENTITY_CONFLICT
        if (body.size !in 1..LocalPhysicalJournalFormatV1.MAX_ORIGINAL_BYTES ||
            resolverAuthorization.size !in 1..LocalPhysicalJournalFormatV1.MAX_ACK_BYTES) return LocalPhysicalJournalWrite.INVALID_INPUT
        return write {
            val original = readPrimary()
            if (original.originalKind != LocalPhysicalOriginalKind.UNKNOWN || original.original == null)
                return@write LocalPhysicalJournalWrite.ORIGINAL_UNKNOWN_REQUIRED
            val r = receipt.binding.requirement
            if (!validJournalInput { domain.validateResolution(r, original.original, resolverActorId, resolverAuthorization, body) })
                return@write LocalPhysicalJournalWrite.INVALID_INPUT
            val record = LocalPhysicalLinkedResolution(r.recordingOperationId, original.originalDigest ?: error("physical.journal.original"),
                r.originalActorId, r.attemptId, r.resolutionOperationId, resolverActorId, resolverAuthorization, body)
            val encoded = StoredBytes(resolutionBytes(record))
            val previous = optionalCell(PhysicalJournalCellV1.LINKED_RESOLUTION)
            if (previous is OptionalCell.Damaged) return@write LocalPhysicalJournalWrite.HELD
            val previousBody = (previous as OptionalCell.Read).body
            when {
                previousBody != null -> if (previousBody == encoded) LocalPhysicalJournalWrite.ALREADY_RETAINED else LocalPhysicalJournalWrite.IDENTITY_CONFLICT
                else -> {
                    val retained = optionalWrite(PhysicalJournalCellV1.LINKED_RESOLUTION) {
                        writeCell(PhysicalJournalCellV1.LINKED_RESOLUTION, encoded)
                        check(readResolutionRecord(original) == encoded)
                    }
                    // A damaged optional tail may not poison the independently readable origin.
                    // Required/native damage still escapes here and permanently holds this owner.
                    readPrimary()
                    if (retained) LocalPhysicalJournalWrite.RETAINED else LocalPhysicalJournalWrite.HELD
                }
            }
        }
    }

    public fun readResolution(): LocalPhysicalResolutionRead = when (val result = owned.file.access {
        check(published)
        val original = readPrimary()
        val answer = when (val cell = optionalCell(PhysicalJournalCellV1.LINKED_RESOLUTION)) {
            OptionalCell.Damaged -> LocalPhysicalResolutionRead.Damaged
            is OptionalCell.Read -> try {
                LocalPhysicalResolutionRead.Read(cell.body?.let { decodeResolution(it, original) })
            } catch (_: Exception) { LocalPhysicalResolutionRead.Damaged }
        }
        verifyHeader()
        answer
    }) {
        is PhysicalSpoolAccess.Value -> result.value
        PhysicalSpoolAccess.Busy -> LocalPhysicalResolutionRead.Busy
        PhysicalSpoolAccess.Held -> LocalPhysicalResolutionRead.Held
    }

    /** Optional immutable cache only. Never used by the arming/financial-result authority path. */
    public fun retainReplyCache(kind: LocalPhysicalReplyCacheKind, body: StoredBytes): LocalPhysicalJournalWrite {
        val cell = kind.cell()
        if (body.size !in 1..cell.maximumBody) return LocalPhysicalJournalWrite.INVALID_INPUT
        return write {
            if (readPrimary().original == null) return@write LocalPhysicalJournalWrite.CONSUMED_REQUIRED
            when (val previous = optionalCell(cell)) {
                OptionalCell.Damaged -> LocalPhysicalJournalWrite.HELD
                is OptionalCell.Read -> when {
                    previous.body != null -> if (previous.body == body) LocalPhysicalJournalWrite.ALREADY_RETAINED else LocalPhysicalJournalWrite.IDENTITY_CONFLICT
                    else -> {
                        val retained = optionalWrite(cell) { writeCell(cell, body); check(readCell(cell) == body) }
                        readPrimary()
                        if (retained) LocalPhysicalJournalWrite.RETAINED else LocalPhysicalJournalWrite.HELD
                    }
                }
            }
        }
    }

    public fun readReplyCache(kind: LocalPhysicalReplyCacheKind): LocalPhysicalReplyCacheRead = when (val result = owned.file.access {
        check(published)
        readPrimary()
        val answer = when (val cell = optionalCell(kind.cell())) {
            OptionalCell.Damaged -> LocalPhysicalReplyCacheRead.Damaged
            is OptionalCell.Read -> LocalPhysicalReplyCacheRead.Read(cell.body)
        }
        verifyHeader()
        answer
    }) {
        is PhysicalSpoolAccess.Value -> result.value
        PhysicalSpoolAccess.Busy -> LocalPhysicalReplyCacheRead.Busy
        PhysicalSpoolAccess.Held -> LocalPhysicalReplyCacheRead.Held
    }

    public fun close(): LocalPhysicalSpoolClose = owned.file.close()

    internal fun consumeFresh(start: LocalPhysicalStartWrite.Fresh): LocalPhysicalJournalWrite = write {
        if (start.journal !== this || freshStart !== start) return@write LocalPhysicalJournalWrite.HELD
        // Burn the ephemeral right BEFORE any native write; a failed/uncertain cut cannot retry it.
        freshStart = null
        val original = readNewActionCapacity() ?: return@write LocalPhysicalJournalWrite.HELD
        if (original.certificate != start.certificate || original.actionMayHaveStarted || original.original != null)
            return@write LocalPhysicalJournalWrite.HELD
        writeCell(PhysicalJournalCellV1.ACTION_MAY_HAVE_STARTED, consumedBytes(start.certificate))
        val consumed = readNewActionCapacity() ?: return@write LocalPhysicalJournalWrite.HELD
        check(consumed.actionMayHaveStarted && consumed.certificate == start.certificate)
        consumedFresh = start
        LocalPhysicalJournalWrite.RETAINED
    }

    internal fun takeInstruction(start: LocalPhysicalStartWrite.Fresh): Boolean = when (val result = owned.file.access {
        check(published)
        if (consumedFresh !== start || instructionTaken) false else {
            instructionTaken = true
            val snapshot = readNewActionCapacity() ?: return@access false
            snapshot.actionMayHaveStarted && snapshot.certificate == start.certificate && snapshot.original == null
        }
    }) {
        is PhysicalSpoolAccess.Value -> result.value
        else -> false
    }

    /** Existing evidence/certificate custody deliberately does not depend on optional tail cells. */
    internal fun capture(onRetirement: () -> Unit): PhysicalSpoolLease? =
        captureNative(newAction = false, allocatedOnly = false, onRetirement = onRetirement)

    /** Real complete preallocated capacity, not a receipt/DTO assertion or an arming grant. */
    internal fun captureNewAction(allocatedOnly: Boolean = false, onRetirement: () -> Unit): PhysicalSpoolLease? =
        captureNative(newAction = true, allocatedOnly = allocatedOnly, onRetirement = onRetirement)

    private fun captureNative(newAction: Boolean, allocatedOnly: Boolean, onRetirement: () -> Unit): PhysicalSpoolLease? {
        var captured: PhysicalSpoolLease? = null
        var transferred = false
        var failure: Throwable? = null
        try {
            val result = owned.file.access {
                check(published)
                if (newAction) {
                    val snapshot = readNewActionCapacity() ?: return@access false
                    if (allocatedOnly && snapshot.state != LocalPhysicalJournalState.ALLOCATED) return@access false
                } else {
                    readPrimary()
                    owned.file.force()
                    verifyHeader()
                }
                // This reference owns the lease before the enclosing access checks/current
                // result wrapper run. A wrapper failure cannot bypass the finally cleanup.
                captured = owned.file.capture(onRetirement) ?: error("physical.journal.lease")
                true
            }
            if (result is PhysicalSpoolAccess.Value && result.value) {
                val lease = captured ?: error("physical.journal.lease")
                transferred = true
                return lease
            }
            return null
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            if (!transferred) cleanupUntransferredPhysicalSpoolCapture(captured, onRetirement, failure)
        }
    }

    internal fun markPublished() { published = true }

    internal fun establish(initial: ByteArray? = null) {
        validateDomain()
        val expectedProfile = if (receipt.binding.owner.role == LocalPhysicalSpoolRole.MASTER)
            receipt.binding.requirement.masterStorageProfile else receipt.binding.requirement.clientStorageProfile
        check(owned.native == receipt.nativeIdentity && owned.cipher.identity == receipt.nativeIdentity.cryptographyIdentity &&
            expectedProfile == receipt.nativeIdentity.profile)
        val result = owned.file.access {
            if (initial != null) {
                check(initial.size == LocalPhysicalJournalFormatV1.JOURNAL_BYTES)
                for (cell in PhysicalJournalCellV1.entries) {
                    initial.copyOfRange(cell.offset, cell.offset + LocalPhysicalJournalFormatV1.CELL_BYTES).withSpoolBytes { encrypted ->
                        expectedFrames[cell] = sha256(encrypted)
                        check(usedNonces.add(physicalSpoolNonceIdentity(encrypted)))
                    }
                }
                owned.file.initialize(initial)
                // New admission must read back EVERY initialized capacity cell. Reopening a
                // previously used journal deliberately isolates optional tail damage instead.
                for (cell in PhysicalJournalCellV1.entries) {
                    val value = readCell(cell)
                    check(if (cell == PhysicalJournalCellV1.HEADER) value == StoredBytes(receipt.plan.bytes) else value == null)
                }
            }
            val first = readPrimary()
            if (initial != null) check(first.state == LocalPhysicalJournalState.ALLOCATED)
            owned.file.force()
            readPrimary()
            verifyHeader()
            // Do NOT run the legacy all-cell validator here. Optional tail damage is isolated.
            // Fresh work must separately pass captureNewAction, including on this reopen path.
        }
        check(result is PhysicalSpoolAccess.Value)
    }

    private fun validateDomain() {
        require(domain.domainProfile == receipt.binding.requirement.domainProfile && domain.command == receipt.binding.requirement.command)
        domain.validateRequirement(receipt.binding.requirement)
    }

    private fun validateOriginal(body: StoredBytes): LocalPhysicalOriginalKind {
        require(body.size in 1..LocalPhysicalJournalFormatV1.MAX_ORIGINAL_BYTES)
        return domain.classifyOriginal(receipt.binding.requirement, body)
    }

    /**
     * NEW-action readiness only, always inside the actual native owner's access. Before accepting,
     * authenticate all eight cells before and after force: primary phase/identity, vacant linked
     * resolution and every ACK cell. Reopened ALLOCATED files get the same scrutiny as new ones.
     * A damaged/occupied tail returns denial, never an exception that retires healthy primary
     * evidence. Actual required-cell/native/force failures still escape to native owner retirement.
     * No write, reset, repair, adoption or replacement allocation is performed by this check.
     */
    private fun readNewActionCapacity(): LocalPhysicalOriginalSnapshot? {
        if (newActionCapacityDenied) return null
        val before = readPrimary()
        if (before.original != null) return null
        val vacantBefore = readVacantFutureCells()
        owned.file.force()
        val after = readPrimary()
        val vacantAfter = readVacantFutureCells()
        verifyHeader()
        if (!vacantBefore || !vacantAfter || after.original != null || before.certificate != after.certificate ||
            before.actionMayHaveStarted != after.actionMayHaveStarted) {
            newActionCapacityDenied = true
            freshStart = null
            return null
        }
        return after
    }

    private fun readVacantFutureCells(): Boolean {
        var vacant = true
        for (cell in PhysicalJournalCellV1.entries) {
            if (cell.index < PhysicalJournalCellV1.LINKED_RESOLUTION.index) continue
            val value = optionalCell(cell)
            if (value !is OptionalCell.Read || value.body != null) vacant = false
        }
        return vacant
    }

    private fun readPrimary(): LocalPhysicalOriginalSnapshot {
        verifyHeader()
        val certificate = readCell(PhysicalJournalCellV1.TRUE_START_CERT)
        val consumed = readCell(PhysicalJournalCellV1.ACTION_MAY_HAVE_STARTED)
        val original = readCell(PhysicalJournalCellV1.ORIGINAL)
        check(certificate != null || consumed == null && original == null)
        check(consumed != null || original == null)
        if (consumed != null) check(certificate != null && consumed == consumedBytes(certificate))
        return LocalPhysicalOriginalSnapshot(receipt, certificate, consumed != null, original, original?.let(::validateOriginal))
    }

    private fun verifyHeader() {
        val header = readCell(PhysicalJournalCellV1.HEADER) ?: error("physical.journal.header")
        check(header == StoredBytes(receipt.plan.bytes))
    }

    private fun readCell(cell: PhysicalJournalCellV1): StoredBytes? = owned.file.read(cell.offset,
        LocalPhysicalJournalFormatV1.CELL_BYTES).withSpoolBytes { encrypted ->
        val digest = sha256(encrypted)
        expectedFrames[cell]?.let { check(it == digest) }
        if (cell == PhysicalJournalCellV1.HEADER) check(digest == receipt.headerDigest)
        val body = journalOpen(owned.cipher, "journal", receipt.plan.digest, cell.index, cell.maximumBody,
            if (cell == PhysicalJournalCellV1.HEADER) null else receipt.headerDigest, encrypted)
        try {
            if (cell == PhysicalJournalCellV1.HEADER) check(body != null && body.contentEquals(receipt.plan.bytes))
            expectedFrames[cell] = digest
            usedNonces.add(physicalSpoolNonceIdentity(encrypted))
            body?.let(::StoredBytes)
        } finally { body?.fill(0) }
    }

    private sealed interface OptionalCell {
        class Read(val body: StoredBytes?) : OptionalCell
        data object Damaged : OptionalCell
    }

    private fun optionalCell(cell: PhysicalJournalCellV1): OptionalCell {
        check(cell.index >= PhysicalJournalCellV1.LINKED_RESOLUTION.index)
        if (cell in failedOptionalWrites) return OptionalCell.Damaged
        return try { OptionalCell.Read(readCell(cell)) } catch (_: Exception) { OptionalCell.Damaged }
    }

    private fun optionalWrite(cell: PhysicalJournalCellV1, block: () -> Unit): Boolean {
        check(cell.index >= PhysicalJournalCellV1.LINKED_RESOLUTION.index && cell !in failedOptionalWrites)
        return try { block(); true } catch (_: Exception) { failedOptionalWrites += cell; false }
    }

    private fun writeCell(cell: PhysicalJournalCellV1, body: StoredBytes) {
        check(cell != PhysicalJournalCellV1.HEADER && readCell(cell) == null)
        body.copyBytes().withSpoolBytes { bytes -> journalSeal(owned.cipher, "journal", receipt.plan.digest,
            cell.index, cell.maximumBody, receipt.headerDigest, bytes).withSpoolBytes { encrypted ->
            check(usedNonces.add(physicalSpoolNonceIdentity(encrypted)))
            val digest = sha256(encrypted)
            owned.file.overwrite(cell.offset, encrypted)
            owned.file.force()
            expectedFrames[cell] = digest
        } }
        check(readCell(cell) == body)
    }

    private fun consumedBytes(certificate: StoredBytes): StoredBytes = StoredBytes(journalFrame(listOf(
        "physical.journal-first/ACTION_MAY_HAVE_STARTED-v1", journalBodyDigest(receipt.binding.requirement.canonicalBytes).value,
        journalBodyDigest(certificate).value, receipt.binding.requirement.intentOperationId.value,
        receipt.binding.requirement.recordingOperationId.value,
    ).map(::journalText), LocalPhysicalJournalFormatV1.MAX_CONSUMED_BYTES))

    private fun readResolutionRecord(original: LocalPhysicalOriginalSnapshot): StoredBytes? =
        readCell(PhysicalJournalCellV1.LINKED_RESOLUTION)?.also { decodeResolution(it, original) }

    private fun decodeResolution(bytes: StoredBytes, original: LocalPhysicalOriginalSnapshot): LocalPhysicalLinkedResolution {
        val r = receipt.binding.requirement
        val fields = bytes.copyBytes().withSpoolBytes { journalFields(it, 9) }
        check(journalString(fields[0]) == "physical.journal-first/linked-resolution-v1")
        val value = LocalPhysicalLinkedResolution(OperationId(journalString(fields[1])), PayloadDigest(journalString(fields[2])),
            DomainId(journalString(fields[3])), DomainId(journalString(fields[4])), OperationId(journalString(fields[5])),
            DomainId(journalString(fields[6])), StoredBytes(fields[7]), StoredBytes(fields[8]))
        check(original.original != null && original.originalKind == LocalPhysicalOriginalKind.UNKNOWN &&
            value.originalOperationId == r.recordingOperationId && value.originalDigest == original.originalDigest &&
            value.originalActorId == r.originalActorId && value.attemptId == r.attemptId && value.resolutionOperationId == r.resolutionOperationId &&
            value.resolverAuthorization.size in 1..LocalPhysicalJournalFormatV1.MAX_ACK_BYTES &&
            value.body.size in 1..LocalPhysicalJournalFormatV1.MAX_ORIGINAL_BYTES)
        domain.validateResolution(r, original.original, value.resolverActorId, value.resolverAuthorization, value.body)
        check(StoredBytes(resolutionBytes(value)) == bytes)
        return value
    }

    private fun write(block: () -> LocalPhysicalJournalWrite): LocalPhysicalJournalWrite = when (val result = owned.file.access {
        check(published)
        val answer = block()
        verifyHeader()
        answer
    }) {
        is PhysicalSpoolAccess.Value -> result.value
        PhysicalSpoolAccess.Busy -> LocalPhysicalJournalWrite.BUSY
        PhysicalSpoolAccess.Held -> LocalPhysicalJournalWrite.HELD
    }

    override fun toString(): String = "LocalPhysicalJournalV1([REDACTED])"
}

internal fun initializePhysicalJournal(catalogue: LocalPhysicalAllocationCatalogue,
    binding: LocalPhysicalJournalBinding, owned: PhysicalNativeOwnedFileV1,
    domain: LocalPhysicalJournalDomain): LocalPhysicalJournal {
    val plan = PhysicalJournalPlanV1(binding, owned.native)
    val header = journalSeal(owned.cipher, "journal", plan.digest, 0, LocalPhysicalJournalFormatV1.MAX_HEADER_BYTES, null, plan.bytes)
    val receipt = LocalPhysicalJournalReceipt(binding, owned.native, sha256(header))
    val all = ByteArray(LocalPhysicalJournalFormatV1.JOURNAL_BYTES)
    try {
        header.copyInto(all)
        val nonces = mutableSetOf(physicalSpoolNonceIdentity(header))
        for (cell in PhysicalJournalCellV1.entries.drop(1)) {
            journalSeal(owned.cipher, "journal", plan.digest, cell.index, cell.maximumBody, receipt.headerDigest, null).withSpoolBytes { encrypted ->
                check(nonces.add(physicalSpoolNonceIdentity(encrypted)))
                encrypted.copyInto(all, cell.offset)
            }
        }
        return LocalPhysicalJournal(catalogue, receipt, owned, domain).also { it.establish(all) }
    } finally { header.fill(0); all.fill(0) }
}

internal fun reopenPhysicalJournal(catalogue: LocalPhysicalAllocationCatalogue,
    receipt: LocalPhysicalJournalReceipt, owned: PhysicalNativeOwnedFileV1,
    domain: LocalPhysicalJournalDomain): LocalPhysicalJournal =
    LocalPhysicalJournal(catalogue, receipt, owned, domain).also { it.establish() }

private fun resolutionBytes(value: LocalPhysicalLinkedResolution): ByteArray = journalFrame(listOf(
    journalText("physical.journal-first/linked-resolution-v1"), journalText(value.originalOperationId.value),
    journalText(value.originalDigest.value), journalText(value.originalActorId.value), journalText(value.attemptId.value),
    journalText(value.resolutionOperationId.value), journalText(value.resolverActorId.value),
    value.resolverAuthorization.copyBytes(), value.body.copyBytes(),
), LocalPhysicalJournalFormatV1.MAX_RESOLUTION_BYTES)

private fun LocalPhysicalReplyCacheKind.cell(): PhysicalJournalCellV1 = when (this) {
    LocalPhysicalReplyCacheKind.MASTER_RAW -> PhysicalJournalCellV1.MASTER_RAW_ACK
    LocalPhysicalReplyCacheKind.SQL_IMPORT -> PhysicalJournalCellV1.SQL_IMPORT_ACK
    LocalPhysicalReplyCacheKind.SQL_APPLICATION -> PhysicalJournalCellV1.SQL_APPLICATION_ACK
}
