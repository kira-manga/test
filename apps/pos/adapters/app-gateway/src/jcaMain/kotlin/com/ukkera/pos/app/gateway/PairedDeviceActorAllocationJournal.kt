package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PairedDeviceCredentialJournalCustody
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import com.ukkera.pos.types.DomainId
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** The successful paired journal owner facet (Android or desktop) that issued a ledger or actor
 * journal. Only the platform owner's private constructor, after full admission, provides one. */
internal interface PairedClientJournalOwner {
    val intentJournal: LocalClientIntentJournal?
    val actorAllocationJournal: PairedDeviceActorAllocationJournal?
    fun isCurrent(): Boolean
    suspend fun close()
}

/** Actual protected device allocation history, separate from both credentials and financial
 * intents, shared by Android and desktop (persisted bytes frozen: paired-actor-allocation-format-v1).
 * Only its fully admitted AES/file/native-head owner can issue an actor open. The
 * original empty history is created before source transfer by explicit fresh native ENROLL;
 * reopening never creates a missing history or interprets a missing actor file as pristine. */
internal class PairedDeviceActorAllocationJournal private constructor(
    private val file: ClientJournalFileCore,
    val nativeOriginal: PairedDeviceCredentialJournalCustody,
) {
    private val origin = nativeOriginal.actorAllocationOrigin()
    private val successfulOwner = AtomicReference<PairedClientJournalOwner?>(null)

    fun attachSuccessfulOwner(owner: PairedClientJournalOwner) {
        check(owner.actorAllocationJournal === this && successfulOwner.compareAndSet(null, owner))
    }

    fun isCurrent(): Boolean = successfulOwner.get()?.let {
        it.actorAllocationJournal === this && it.isCurrent()
    } == true

    suspend fun prepareActorCustody(current: PairedDeviceCurrentActor,
        actorIntentAction: LocalPairedDeviceCredentialJournalAction = nativeOriginal.action): PairedDeviceActorJournalCustody? {
        if (!isCurrent() || !current.belongsTo(nativeOriginal) || !current.isCurrent()) return null
        val result = file.owned {
            object : ClientJournalTask<PairedDeviceActorJournalCustody?> {
                override fun execute(): PairedDeviceActorJournalCustody? {
                    val before = readState()
                    if (!isCurrent() || !current.isCurrent()) return null
                    val existing = before.records.singleOrNull { it.actorId == current.identity.actorId }
                    val record = when (LocalPairedActorIntentOpeningPolicy.decide(actorIntentAction,
                        existing != null, before.records.size, MAX_PAIRED_ACTOR_ALLOCATIONS)) {
                        LocalPairedActorIntentOpeningDecision.ALLOCATE_NEW -> {
                            // An allocated but unknown/undelivered original is not recoverable
                            // by silently changing this explicit disposition to an existing open.
                            check(existing == null && before.records.size < MAX_PAIRED_ACTOR_ALLOCATIONS)
                            val allocationId = DomainId.random()
                            val issued = pairedActorRecord(origin, current.identity.actorId,
                                allocationId, before.records.size + 1)
                            val next = before.copy(revision = Math.addExact(before.revision, 1L),
                                records = before.records + issued)
                            // Full immutable history and native successor are forced BEFORE
                            // the actor's first AES alias, file, lock or head is attempted.
                            encodeState(next).useWiped { file.replaceManifest(it, MAX_PAIRED_ACTOR_ALLOCATION_BYTES) }
                            check(readState() == next)
                            issued
                        }
                        LocalPairedActorIntentOpeningDecision.OPEN_EXISTING -> checkNotNull(existing)
                        LocalPairedActorIntentOpeningDecision.REFUSE -> return null
                    }
                    if (!isCurrent() || !current.isCurrent()) return null
                    return PairedDeviceActorJournalCustody.issue(this@PairedDeviceActorAllocationJournal,
                        current, actorIntentAction, record)
                }

                override fun discard(value: PairedDeviceActorJournalCustody?) { value?.denyUndelivered() }
                override fun clearInputs() = Unit
            }
        }
        return when (result) {
            is ClientJournalOutcome.Value -> result.value
            ClientJournalOutcome.Unavailable -> null
        }
    }

    /** Complete canonical history plus the SAME retained native file/key/head cut, not a
     * copied allocation record or a supplied positive validator. */
    suspend fun recheckOriginal(record: PairedDeviceActorAllocationRecord,
        validation: PairedDeviceActorJournalCustody.NativeValidation): Boolean {
        try {
            if (!isCurrent()) return false
            val result = file.owned {
                object : ClientJournalTask<Boolean> {
                    override fun execute(): Boolean = isCurrent() && readState().records.singleOrNull {
                        it.actorId == record.actorId
                    } == record && isCurrent()
                    override fun discard(value: Boolean) = Unit
                    // This executes after the ACTUAL admitted native task, including the
                    // file wrapper's final continuity check, even if its waiter cancelled.
                    override fun clearInputs() { validation.finish() }
                }.also { validation.prepared = true }
            }
            return when (result) {
                is ClientJournalOutcome.Value -> result.value
                ClientJournalOutcome.Unavailable -> false
            }
        } finally {
            if (!validation.prepared) validation.finish()
        }
    }

    private fun readState(): PairedDeviceActorAllocationState =
        file.readManifest(MAX_PAIRED_ACTOR_ALLOCATION_BYTES).useWiped { bytes ->
            decodePairedActorAllocationState(bytes, origin, file.journalId, file.keyId)
        }

    private fun encodeState(state: PairedDeviceActorAllocationState): ByteArray =
        encodePairedActorAllocationState(state, origin, file.journalId, file.keyId)

    companion object {
        /** [custody] is the platform file's own acquisition binding, never a caller-supplied value. */
        fun fromExisting(file: ClientJournalFileCore, custody: PairedDeviceCredentialJournalCustody): PairedDeviceActorAllocationJournal {
            check(file.kind == ClientJournalKind.ACTOR_ALLOCATIONS && file.actorId == null)
            return PairedDeviceActorAllocationJournal(file, custody)
        }

        fun originalEmptyManifest(file: ClientJournalFileCore, custody: PairedDeviceCredentialJournalCustody): ByteArray =
            fromExisting(file, custody).encodeState(PairedDeviceActorAllocationState(0L, emptyList()))

        fun validateExisting(file: ClientJournalFileCore, custody: PairedDeviceCredentialJournalCustody) {
            fromExisting(file, custody).readState()
        }
    }
}

internal const val MAX_PAIRED_ACTOR_ALLOCATIONS: Int = 256
private const val ALLOCATION_PROFILE: String = "ukkera.android.paired-device-actor-allocations/v1"

internal data class PairedDeviceActorAllocationRecord(
    val actorId: DomainId,
    val allocationId: DomainId,
    val sequence: Int,
    val journalId: DomainId,
    val keyId: DomainId,
    val bindingCommitment: String,
) {
    override fun toString(): String = "PairedDeviceActorAllocationRecord([REDACTED])"
}

internal data class PairedDeviceActorAllocationState(
    val revision: Long,
    val records: List<PairedDeviceActorAllocationRecord>,
)

/** Device facts the ledger format binds: scope, device, device binding commitment and the
 * identifiers an allocation may never reuse (ledger IDs and actors are added during validation). */
internal class PairedActorAllocationOrigin(
    val scope: BranchScope,
    val deviceId: DomainId,
    val bindingCommitment: String,
    val reservedIds: List<DomainId>,
) {
    override fun toString(): String = "PairedActorAllocationOrigin([REDACTED])"
}

internal fun PairedDeviceCredentialJournalCustody.actorAllocationOrigin(): PairedActorAllocationOrigin =
    PairedActorAllocationOrigin(scope, deviceId, bindingCommitment, listOf(scope.organizationId, scope.companyId,
        scope.branchId, deviceId, targetKeyId, originalPairingActionId, journalId, keyId,
        approvedOriginal.originalRequestId, approvedOriginal.rootId, approvedOriginal.rootKeyId,
        approvedOriginal.foundingActionId, sdkOriginal.identity.originalPreparationId,
        sdkOriginal.identity.originalAcquisitionId, approvedOriginal.masterSdkOriginal.originalAcquisitionId))

internal fun pairedActorAllocationJournalId(original: PairedDeviceCredentialJournalCustody, purpose: String): DomainId =
    pairedActorAllocationJournalId(original.bindingCommitment, purpose)

internal fun pairedActorAllocationJournalId(bindingCommitment: String, purpose: String): DomainId =
    pairedJournalId("ukkera.android.paired-device-actor-allocation-id/v1", bindingCommitment, purpose)

internal fun pairedActorRecord(original: PairedActorAllocationOrigin, actorId: DomainId,
    allocationId: DomainId, sequence: Int): PairedDeviceActorAllocationRecord {
    check(CLIENT_JOURNAL_UUID_V1.matches(actorId.value) && CLIENT_JOURNAL_UUID_V1.matches(allocationId.value) &&
        sequence in 1..MAX_PAIRED_ACTOR_ALLOCATIONS)
    val binding = pairedDigest("ukkera.android.paired-device-actor-original-binding/v1",
        original.bindingCommitment, pairedActorAllocationJournalId(original.bindingCommitment, "journal").value,
        pairedActorAllocationJournalId(original.bindingCommitment, "key").value, actorId.value, allocationId.value, sequence.toString())
    return PairedDeviceActorAllocationRecord(actorId, allocationId, sequence,
        pairedJournalId("ukkera.android.paired-device-actor-journal-id/v1", binding, "journal"),
        pairedJournalId("ukkera.android.paired-device-actor-journal-id/v1", binding, "key"), binding)
}

private fun validatePairedActorAllocationState(state: PairedDeviceActorAllocationState,
    original: PairedActorAllocationOrigin, journalId: DomainId, keyId: DomainId) {
    check(journalId == pairedActorAllocationJournalId(original.bindingCommitment, "journal") &&
        keyId == pairedActorAllocationJournalId(original.bindingCommitment, "key") && journalId != keyId)
    check(state.records.size <= MAX_PAIRED_ACTOR_ALLOCATIONS && state.revision == state.records.size.toLong())
    check(state.records.map { it.actorId }.toSet().size == state.records.size)
    val reserved = original.reservedIds + listOf(journalId, keyId) + state.records.map { it.actorId }
    val ownedIds = state.records.flatMap { listOf(it.allocationId, it.journalId, it.keyId) }
    check(ownedIds.toSet().size == ownedIds.size && ownedIds.none { it in reserved })
    state.records.forEachIndexed { index, record ->
        check(record.sequence == index + 1 && record == pairedActorRecord(original, record.actorId,
            record.allocationId, record.sequence))
    }
}

internal fun encodePairedActorAllocationState(state: PairedDeviceActorAllocationState,
    original: PairedActorAllocationOrigin, journalId: DomainId, keyId: DomainId): ByteArray {
    validatePairedActorAllocationState(state, original, journalId, keyId)
    val out = ByteBuffer.allocate(MAX_PAIRED_ACTOR_ALLOCATION_BYTES)
    try {
        fun text(value: String) { value.encodeToByteArray(throwOnInvalidSequence = true).useWiped {
            out.putInt(it.size); out.put(it)
        } }
        text(ALLOCATION_PROFILE)
        text(original.scope.organizationId.value); text(original.scope.companyId.value)
        text(original.scope.branchId.value); text(original.deviceId.value)
        text(original.bindingCommitment); text(journalId.value); text(keyId.value)
        out.putLong(state.revision); out.putInt(state.records.size)
        state.records.forEach { record ->
            text(record.actorId.value); text(record.allocationId.value); out.putInt(record.sequence)
            text(record.journalId.value); text(record.keyId.value); text(record.bindingCommitment)
        }
        return out.array().copyOf(out.position())
    } finally { out.array().fill(0) }
}

internal fun decodePairedActorAllocationState(bytes: ByteArray,
    original: PairedActorAllocationOrigin, journalId: DomainId, keyId: DomainId): PairedDeviceActorAllocationState {
    check(bytes.size in 1..MAX_PAIRED_ACTOR_ALLOCATION_BYTES)
    val input = ByteBuffer.wrap(bytes)
    fun text(maximum: Int): String {
        check(input.remaining() >= 4)
        val size = input.int
        check(size in 1..maximum && input.remaining() >= size)
        val value = ByteArray(size)
        return try { input.get(value); value.decodeToString(throwOnInvalidSequence = true) }
        finally { value.fill(0) }
    }
    fun id(): DomainId = DomainId(text(36)).also { check(CLIENT_JOURNAL_UUID_V1.matches(it.value)) }
    check(text(128) == ALLOCATION_PROFILE)
    check(id() == original.scope.organizationId && id() == original.scope.companyId &&
        id() == original.scope.branchId && id() == original.deviceId)
    check(text(64) == original.bindingCommitment && id() == journalId && id() == keyId)
    check(input.remaining() >= 12)
    val revision = input.long; val count = input.int
    check(count in 0..MAX_PAIRED_ACTOR_ALLOCATIONS)
    val records = List(count) {
        val actor = id(); val allocation = id()
        check(input.remaining() >= 4)
        val sequence = input.int
        PairedDeviceActorAllocationRecord(actor, allocation, sequence, id(), id(), text(64))
    }
    check(!input.hasRemaining())
    return PairedDeviceActorAllocationState(revision, records).also { state ->
        encodePairedActorAllocationState(state, original, journalId, keyId).useWiped {
            check(it.contentEquals(bytes))
        }
    }
}

private fun pairedDigest(vararg fields: String): String {
    val hash = MessageDigest.getInstance("SHA-256")
    fields.forEach { value -> value.encodeToByteArray(throwOnInvalidSequence = true).useWiped {
        ByteBuffer.allocate(4).putInt(it.size).array().useWiped(hash::update); hash.update(it)
    } }
    return hash.digest().useWiped(::encodeHex)
}

private fun pairedJournalId(domain: String, binding: String, purpose: String): DomainId {
    check(purpose == "journal" || purpose == "key")
    val hex = pairedDigest(domain, binding, purpose).take(32)
    return DomainId("${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
}
