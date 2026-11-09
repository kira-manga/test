package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.coroutines.cancellation.CancellationException

internal const val MAX_RETAINED_ENVELOPE_BYTES: Int = 4_194_304
private const val MAX_PROPOSAL_BYTES: Long = 1_048_576

internal enum class RetainedCommandIssue {
    INCOMPLETE_PAIR, INVALID_STORAGE, INVALID_FRAME, UNSUPPORTED_VERSION,
    INVALID_METADATA, PAIR_MISMATCH, HASH_MISMATCH, INVALID_EFFECTS, LIMIT_EXCEEDED,
}

/** Only a fixed internal reason survives. Never retain payload, SQL or scalar-parser causes. */
internal fun retainedCommandUnavailable(issue: RetainedCommandIssue): Nothing =
    throw IllegalStateException("Retained command unavailable: ${issue.name}")

private class InvalidRetainedCommand(val issue: RetainedCommandIssue) : Exception()

private fun expectRetained(condition: Boolean, issue: RetainedCommandIssue) {
    if (!condition) throw InvalidRetainedCommand(issue)
}

/** No handler lookup/normalization, current authorization, aggregate hydration or head replay. */
internal fun decodeRetainedCommand(
    scope: BranchScope,
    operationId: OperationId,
    operation: OperationRow?,
    journal: JournalRow?,
): RetainedCommandEvidence? = try {
    if (operation == null && journal == null) null else {
        expectRetained(operation != null && journal != null, RetainedCommandIssue.INCOMPLETE_PAIR)
        decodeRetainedPair(scope, operationId, checkNotNull(operation), checkNotNull(journal))
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (invalid: InvalidRetainedCommand) {
    retainedCommandUnavailable(invalid.issue)
} catch (_: Exception) {
    // Existing scalar constructors/strict UTF-8 decoders may report their input.
    retainedCommandUnavailable(RetainedCommandIssue.INVALID_METADATA)
}

private fun decodeRetainedPair(
    scope: BranchScope,
    operationId: OperationId,
    operation: OperationRow,
    journal: JournalRow,
): RetainedCommandEvidence {
    expectRetained(operation.result.size <= MAX_STORED_BYTES &&
        journal.envelope.size <= MAX_RETAINED_ENVELOPE_BYTES, RetainedCommandIssue.LIMIT_EXCEEDED)
    expectRetained(operation.scope == scope.storageKey() && journal.scope == operation.scope &&
        operation.operationId == operationId.value && journal.operationId == operation.operationId,
        RetainedCommandIssue.PAIR_MISMATCH)
    // These comparisons bound stored metadata even for direct internal decoder calls.
    expectRetained(operation.actorId.length == 36 && operation.command.length in 1..96 &&
        operation.authenticationId.length == 36 && operation.committedAt.length in 20..30 &&
        operation.outcome.length in 1..96 && operation.digest.length == 64 &&
        operation.resultHash.length == 64 && journal.assignmentId.length == 36 &&
        journal.previousHash.length == 64 && journal.hash.length == 64,
        RetainedCommandIssue.INVALID_METADATA)
    expectRetained(operation.commandVersion in 1..1000 && operation.epoch >= 0 && operation.sequence >= 1 &&
        operation.policyRevision >= 0 && journal.epoch >= 0 && journal.sequence >= 1,
        RetainedCommandIssue.INVALID_METADATA)
    val journalHash = PayloadDigest(journal.hash)
    expectRetained(sha256(journal.envelope) == journalHash, RetainedCommandIssue.HASH_MISMATCH)
    val fields = retainedFields(RetainedSlice(journal.envelope), 21)
    expectRetained(fields.isNotEmpty(), RetainedCommandIssue.INVALID_FRAME)
    val nativeOriginal = when (fields[0].text(64)) {
        "ukkera.journal/v1" -> false
        NativeOriginalRetainedCodecV1.JOURNAL_TAG -> true
        else -> throw InvalidRetainedCommand(RetainedCommandIssue.UNSUPPORTED_VERSION)
    }
    expectRetained(fields.size == (if (nativeOriginal) 21 else 20), RetainedCommandIssue.INVALID_FRAME)
    val storedScope = fields[1].text(110)
    val assignment = DomainId(fields[2].text(36))
    val epoch = Revision(fields[3].counter())
    val sequence = JournalSequence(fields[4].counter(minimum = 1))
    val storedOperation = OperationId(fields[5].text(36))
    val actor = DomainId(fields[6].text(36))
    val name = fields[7].text(96)
    val version = fields[8].counter(minimum = 1)
    expectRetained(version <= 1000, RetainedCommandIssue.INVALID_METADATA)
    val command = CommandKey(name, version.toInt())
    val authentication = DomainId(fields[9].text(36))
    val policyRevision = Revision(fields[10].counter())
    val digest = PayloadDigest(fields[11].text(64))
    val code = fields[12].text(96).also(::requireProtocolName)
    val at = UtcInstant.parseCanonical(fields[13].text(30)).also(::requireEvidenceV1Time)
    val resultHash = PayloadDigest(fields[14].text(64))
    val previousHash = PayloadDigest(fields[15].text(64))
    expectRetained(storedScope == operation.scope && assignment.value == journal.assignmentId &&
        epoch.value == operation.epoch && epoch.value == journal.epoch &&
        sequence.value == operation.sequence && sequence.value == journal.sequence &&
        storedOperation == operationId && actor.value == operation.actorId &&
        command.name == operation.command && command.version == operation.commandVersion &&
        authentication.value == operation.authenticationId && policyRevision.value == operation.policyRevision &&
        digest.value == operation.digest && code == operation.outcome && at.toString() == operation.committedAt &&
        resultHash.value == operation.resultHash && previousHash.value == journal.previousHash,
        RetainedCommandIssue.PAIR_MISMATCH)
    val normalized = fields[16].stored()
    val result = fields[17].stored()
    expectRetained(result.bytes().contentEquals(operation.result), RetainedCommandIssue.PAIR_MISMATCH)
    expectRetained(sha256(result.bytes()) == resultHash &&
        semanticDigest(scope, actor, command, normalized) == digest, RetainedCommandIssue.HASH_MISMATCH)

    val writes = mutableListOf<RetainedAggregateWrite>()
    val claims = mutableListOf<RetainedClaimChange>()
    var proposalBytes = result.size.toLong()
    var lastWrite: Pair<String, String>? = null
    var lastClaim: Pair<String, String>? = null
    for (effect in retainedFields(fields[18], MAX_EFFECTS * 2)) {
        val values = retainedFields(effect, 5)
        when (values.size) {
            5 -> {
                expectRetained(claims.isEmpty() && writes.size < MAX_EFFECTS, RetainedCommandIssue.INVALID_EFFECTS)
                val key = AggregateKey(values[0].text(96), DomainId(values[1].text(36)))
                val generation = Revision(values[2].counter(minimum = 1))
                val schema = values[3].text(96).also(::requireProtocolName)
                val order = key.kind to key.id.value
                expectRetained(lastWrite == null || orderedBefore(checkNotNull(lastWrite), order), RetainedCommandIssue.INVALID_EFFECTS)
                lastWrite = order
                proposalBytes += values[4].size
                expectRetained(proposalBytes <= MAX_PROPOSAL_BYTES, RetainedCommandIssue.LIMIT_EXCEEDED)
                val payload = values[4].stored()
                writes += RetainedAggregateWrite(key, generation, schema, payload, sha256(payload.bytes()))
            }
            4 -> {
                expectRetained(claims.size < MAX_EFFECTS, RetainedCommandIssue.INVALID_EFFECTS)
                val action = when (values[0].text(96)) {
                    "claim.acquire" -> RetainedClaimAction.ACQUIRE
                    "claim.release" -> RetainedClaimAction.RELEASE
                    else -> throw InvalidRetainedCommand(RetainedCommandIssue.INVALID_EFFECTS)
                }
                val key = ClaimKey(values[1].text(96), values[2].text(256))
                val owner = DomainId(values[3].text(36))
                val order = key.namespace to key.key
                expectRetained(lastClaim == null || orderedBefore(checkNotNull(lastClaim), order), RetainedCommandIssue.INVALID_EFFECTS)
                lastClaim = order
                claims += RetainedClaimChange(action, key, owner)
            }
            else -> throw InvalidRetainedCommand(RetainedCommandIssue.INVALID_EFFECTS)
        }
    }
    val jobs = retainedFields(fields[19], MAX_JOBS).map { job ->
        val values = retainedFields(job, 2)
        expectRetained(values.size == 2, RetainedCommandIssue.INVALID_EFFECTS)
        val kind = values[0].text(96).also(::requireProtocolName)
        expectRetained(kind != SYNC_JOB, RetainedCommandIssue.INVALID_EFFECTS)
        proposalBytes += values[1].size
        expectRetained(proposalBytes <= MAX_PROPOSAL_BYTES, RetainedCommandIssue.LIMIT_EXCEEDED)
        RetainedJobIntent(kind, values[1].stored())
    }
    expectRetained(if (code == "committed") writes.isNotEmpty() || claims.isNotEmpty()
        else writes.isEmpty() && claims.isEmpty() && jobs.isEmpty(), RetainedCommandIssue.INVALID_EFFECTS)
    val nativeProvenance = if (nativeOriginal) NativeOriginalRetainedCodecV1.validate(fields[20].stored(),
        scope, operationId, actor, command, assignment, epoch, sequence, at, authentication,
        policyRevision, digest, code, normalized, result, writes, claims, jobs) else null
    return RetainedCommandEvidence(scope, operationId, actor, command, assignment, epoch, sequence, at,
        authentication, policyRevision, digest, code, normalized, result, resultHash, previousHash, journalHash,
        writes, claims, jobs, nativeProvenance)
}

private fun orderedBefore(left: Pair<String, String>, right: Pair<String, String>): Boolean =
    left.first < right.first || (left.first == right.first && left.second < right.second)

/** A bounded view: framing validates unsigned lengths before allocation or UTF-8 decoding. */
private class RetainedSlice(val bytes: ByteArray, val start: Int = 0, val end: Int = bytes.size) {
    val size: Int get() = end - start

    fun text(maximum: Int): String {
        expectRetained(size in 1..maximum, RetainedCommandIssue.INVALID_METADATA)
        return bytes.decodeToString(start, end, throwOnInvalidSequence = true)
    }

    fun counter(minimum: Long = 0): Long {
        val text = text(19)
        val value = text.toLongOrNull()
        expectRetained(value != null && value >= minimum && value.toString() == text, RetainedCommandIssue.INVALID_METADATA)
        return checkNotNull(value)
    }

    fun stored(): StoredBytes {
        expectRetained(size <= MAX_STORED_BYTES, RetainedCommandIssue.LIMIT_EXCEEDED)
        return StoredBytes(bytes.copyOfRange(start, end))
    }
}

/** Called at exactly three known levels; no recursive, attacker-selected nesting. */
private fun retainedFields(slice: RetainedSlice, maximumFields: Int): List<RetainedSlice> {
    expectRetained(slice.size <= MAX_RETAINED_ENVELOPE_BYTES, RetainedCommandIssue.LIMIT_EXCEEDED)
    val fields = mutableListOf<RetainedSlice>()
    var cursor = slice.start
    while (cursor < slice.end) {
        expectRetained(fields.size < maximumFields && slice.end - cursor >= 4, RetainedCommandIssue.INVALID_FRAME)
        var length = 0L
        repeat(4) { length = (length shl 8) or (slice.bytes[cursor++].toLong() and 0xff) }
        expectRetained(length <= (slice.end - cursor).toLong(), RetainedCommandIssue.INVALID_FRAME)
        val end = cursor + length.toInt()
        fields += RetainedSlice(slice.bytes, cursor, end)
        cursor = end
    }
    return fields
}
