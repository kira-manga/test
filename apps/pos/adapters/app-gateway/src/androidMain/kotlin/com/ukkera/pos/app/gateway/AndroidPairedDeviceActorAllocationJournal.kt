package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.AndroidPairedDeviceCredentialJournalCustody
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import com.ukkera.pos.types.DomainId
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** Actual protected device allocation history, separate from both credentials and financial
 * intents. Only its fully admitted AES/file/native-head owner can issue an actor open. The
 * original empty history is created before source transfer by explicit fresh native ENROLL;
 * reopening never creates a missing history or interprets a missing actor file as pristine. */
internal class AndroidPairedDeviceActorAllocationJournal private constructor(
    private val file: AndroidClientJournalFile,
    val nativeOriginal: AndroidPairedDeviceCredentialJournalCustody,
) {
    private val successfulOwner = AtomicReference<AndroidLocalClientJournalOwner?>(null)

    fun attachSuccessfulOwner(owner: AndroidLocalClientJournalOwner) {
        check(owner.actorAllocationJournal === this && successfulOwner.compareAndSet(null, owner))
    }

    fun isCurrent(): Boolean = successfulOwner.get()?.let {
        it.actorAllocationJournal === this && it.isCurrent()
    } == true

    suspend fun prepareActorCustody(current: AndroidPairedDeviceCurrentActor,
        actorIntentAction: LocalPairedDeviceCredentialJournalAction = nativeOriginal.action): AndroidPairedDeviceActorJournalCustody? {
        if (!isCurrent() || !current.belongsTo(nativeOriginal) || !current.isCurrent()) return null
        val result = file.owned {
            object : AndroidClientJournalTask<AndroidPairedDeviceActorJournalCustody?> {
                override fun execute(): AndroidPairedDeviceActorJournalCustody? {
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
                            val issued = pairedActorRecord(nativeOriginal, current.identity.actorId,
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
                    return AndroidPairedDeviceActorJournalCustody.issue(this@AndroidPairedDeviceActorAllocationJournal,
                        current, actorIntentAction, record)
                }

                override fun discard(value: AndroidPairedDeviceActorJournalCustody?) { value?.denyUndelivered() }
                override fun clearInputs() = Unit
            }
        }
        return when (result) {
            is AndroidClientJournalOutcome.Value -> result.value
            AndroidClientJournalOutcome.Unavailable -> null
        }
    }

    /** Complete canonical history plus the SAME retained native file/key/head cut, not a
     * copied allocation record or a supplied positive validator. */
    suspend fun recheckOriginal(record: PairedDeviceActorAllocationRecord,
        validation: AndroidPairedDeviceActorJournalCustody.NativeValidation): Boolean {
        try {
            if (!isCurrent()) return false
            val result = file.owned {
                object : AndroidClientJournalTask<Boolean> {
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
                is AndroidClientJournalOutcome.Value -> result.value
                AndroidClientJournalOutcome.Unavailable -> false
            }
        } finally {
            if (!validation.prepared) validation.finish()
        }
    }

    private fun readState(): PairedDeviceActorAllocationState =
        file.readManifest(MAX_PAIRED_ACTOR_ALLOCATION_BYTES).useWiped { bytes ->
            decodePairedActorAllocationState(bytes, nativeOriginal, file.journalId, file.keyId)
        }

    private fun encodeState(state: PairedDeviceActorAllocationState): ByteArray =
        encodePairedActorAllocationState(state, nativeOriginal, file.journalId, file.keyId)

    companion object {
        fun fromExisting(file: AndroidClientJournalFile): AndroidPairedDeviceActorAllocationJournal {
            check(file.kind == AndroidClientJournalKind.ACTOR_ALLOCATIONS && file.actorId == null)
            return AndroidPairedDeviceActorAllocationJournal(file, checkNotNull(file.pairedDeviceAllocationCustody))
        }

        fun originalEmptyManifest(file: AndroidClientJournalFile): ByteArray = fromExisting(file)
            .encodeState(PairedDeviceActorAllocationState(0L, emptyList()))

        fun validateExisting(file: AndroidClientJournalFile) { fromExisting(file).readState() }
    }
}

internal const val MAX_PAIRED_ACTOR_ALLOCATIONS: Int = 256
internal const val MAX_PAIRED_ACTOR_ALLOCATION_BYTES: Int = 128 * 1024
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

private data class PairedDeviceActorAllocationState(
    val revision: Long,
    val records: List<PairedDeviceActorAllocationRecord>,
)

internal fun pairedActorAllocationJournalId(original: AndroidPairedDeviceCredentialJournalCustody,
    purpose: String): DomainId = pairedJournalId("ukkera.android.paired-device-actor-allocation-id/v1",
    original.bindingCommitment, purpose)

internal fun pairedActorRecord(original: AndroidPairedDeviceCredentialJournalCustody, actorId: DomainId,
    allocationId: DomainId, sequence: Int): PairedDeviceActorAllocationRecord {
    check(ANDROID_CLIENT_UUID_V1.matches(actorId.value) && ANDROID_CLIENT_UUID_V1.matches(allocationId.value) &&
        sequence in 1..MAX_PAIRED_ACTOR_ALLOCATIONS)
    val binding = pairedDigest("ukkera.android.paired-device-actor-original-binding/v1",
        original.bindingCommitment, pairedActorAllocationJournalId(original, "journal").value,
        pairedActorAllocationJournalId(original, "key").value, actorId.value, allocationId.value, sequence.toString())
    return PairedDeviceActorAllocationRecord(actorId, allocationId, sequence,
        pairedJournalId("ukkera.android.paired-device-actor-journal-id/v1", binding, "journal"),
        pairedJournalId("ukkera.android.paired-device-actor-journal-id/v1", binding, "key"), binding)
}

private fun validatePairedActorAllocationState(state: PairedDeviceActorAllocationState,
    original: AndroidPairedDeviceCredentialJournalCustody, journalId: DomainId, keyId: DomainId) {
    check(journalId == pairedActorAllocationJournalId(original, "journal") &&
        keyId == pairedActorAllocationJournalId(original, "key") && journalId != keyId)
    check(state.records.size <= MAX_PAIRED_ACTOR_ALLOCATIONS && state.revision == state.records.size.toLong())
    check(state.records.map { it.actorId }.toSet().size == state.records.size)
    val reserved = listOf(original.scope.organizationId, original.scope.companyId, original.scope.branchId,
        original.deviceId, original.targetKeyId, original.originalPairingActionId, original.journalId,
        original.keyId, journalId, keyId, original.approvedOriginal.originalRequestId,
        original.approvedOriginal.rootId, original.approvedOriginal.rootKeyId,
        original.approvedOriginal.foundingActionId, original.sdkOriginal.identity.originalPreparationId,
        original.sdkOriginal.identity.originalAcquisitionId,
        original.approvedOriginal.masterSdkOriginal.originalAcquisitionId) + state.records.map { it.actorId }
    val ownedIds = state.records.flatMap { listOf(it.allocationId, it.journalId, it.keyId) }
    check(ownedIds.toSet().size == ownedIds.size && ownedIds.none { it in reserved })
    state.records.forEachIndexed { index, record ->
        check(record.sequence == index + 1 && record == pairedActorRecord(original, record.actorId,
            record.allocationId, record.sequence))
    }
}

private fun encodePairedActorAllocationState(state: PairedDeviceActorAllocationState,
    original: AndroidPairedDeviceCredentialJournalCustody, journalId: DomainId, keyId: DomainId): ByteArray {
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

private fun decodePairedActorAllocationState(bytes: ByteArray,
    original: AndroidPairedDeviceCredentialJournalCustody, journalId: DomainId, keyId: DomainId): PairedDeviceActorAllocationState {
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
    fun id(): DomainId = DomainId(text(36)).also { check(ANDROID_CLIENT_UUID_V1.matches(it.value)) }
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
