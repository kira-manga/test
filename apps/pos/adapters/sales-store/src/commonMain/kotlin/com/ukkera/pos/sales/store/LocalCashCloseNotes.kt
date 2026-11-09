package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Explanatory follow-up only. No amount, tender substitution or physical-money instruction. */
@Serializable
public data class LocalCashCloseNoteRequestV1(
    public val sessionId: DomainId,
    public val expectedSessionRevision: Revision,
    public val expectedLatestNoteId: DomainId?,
    public val evidence: LocalCashEvidenceV1,
    public val reason: String,
) { init { localReason(reason) } }

@Serializable
public data class LocalCashCloseNoteQueryV1(
    public val sessionId: DomainId,
    public val review: Boolean,
    /** Exclusive, session-bound backward cursor. Null starts at the latest retained note. */
    public val before: DomainId? = null,
    public val limit: Int = 5,
) { init { require(limit in 1..5) } }

@Serializable
public data class LocalCashCloseNoteViewV1(
    public val sessionId: DomainId,
    public val originalCloseOperationId: OperationId,
    public val position: Revision,
    public val previousNoteId: DomainId?,
    public val evidence: LocalCashActorEvidenceV1,
    public val reason: String,
    public val operationId: OperationId,
    public val recordedAt: UtcInstant,
) {
    public val id: DomainId get() = evidence.evidenceId
    init {
        localReason(reason)
        require((position.value == 0L) == (previousNoteId == null))
        require(previousNoteId != evidence.evidenceId)
    }
}

@Serializable
public enum class LocalCashCloseNoteRefusalV1 { SESSION_NOT_CLOSED, SELF_REVIEW, STALE_REVIEW, DUPLICATE_NOTE, CAPACITY_REQUIRED, SESSION_NOT_AVAILABLE }

@Serializable
public sealed interface LocalCashCloseNoteResultV1 {
    @Serializable @SerialName("RECORDED")
    public data class Recorded(public val note: LocalCashCloseNoteViewV1, public val sessionRevision: Revision) : LocalCashCloseNoteResultV1
    @Serializable @SerialName("REFUSED")
    public data class Refused(public val reason: LocalCashCloseNoteRefusalV1) : LocalCashCloseNoteResultV1
}

@Serializable
public sealed interface LocalCashCloseNotesPageV1 {
    @Serializable @SerialName("NOT_AVAILABLE")
    public data object NotAvailable : LocalCashCloseNotesPageV1
    @Serializable @SerialName("PAGE")
    public data class Page(
        public val sessionId: DomainId,
        public val sessionRevision: Revision,
        public val originalCloseOperationId: OperationId,
        public val latestNoteId: DomainId?,
        public val totalNotes: Long,
        public val before: DomainId?,
        public val notes: List<LocalCashCloseNoteViewV1>,
        public val nextBefore: DomainId?,
    ) : LocalCashCloseNotesPageV1
}

public object LocalCashCloseNoteCodec {
    public val COMMAND: CommandKey = CommandKey("sales.cash.close-note.write", 1)
    public val QUERY: ReadKey = ReadKey("sales.cash.close-note.read", 1)
    public fun encode(value: LocalCashCloseNoteRequestV1): StoredBytes = localSalesEncode(value, LocalCashCloseNoteRequestV1.serializer(), 8_192)
    public fun decode(value: StoredBytes): LocalCashCloseNoteRequestV1 = localSalesDecode(value, LocalCashCloseNoteRequestV1.serializer(), 8_192)
    public fun normalize(value: StoredBytes): StoredBytes = encode(decode(value))
    public fun request(value: LocalCashCloseNoteQueryV1): ReadRequest = ReadRequest(QUERY, localSalesEncode(value, LocalCashCloseNoteQueryV1.serializer(), 2_048))
    public fun result(value: StoredBytes): LocalCashCloseNoteResultV1 = localSalesDecode(value, LocalCashCloseNoteResultV1.serializer(), 8_192)
    public fun page(value: StoredBytes): LocalCashCloseNotesPageV1 = localSalesDecode(value, LocalCashCloseNotesPageV1.serializer())
    internal fun result(value: LocalCashCloseNoteResultV1): StoredBytes = localSalesEncode(value, LocalCashCloseNoteResultV1.serializer(), 8_192)
}

/** Separate immutable records and a CAS head: history cannot outgrow one aggregate or be evicted. */
@Serializable
private data class CloseNoteRecordV1(val scope: LocalSalesScope, val note: LocalCashCloseNoteViewV1)
@Serializable
private data class CloseNoteHeadV1(val scope: LocalSalesScope, val sessionId: DomainId,
    val originalCloseOperationId: OperationId, val revision: Revision, val latestNoteId: DomainId)

private const val NOTE_KIND = "sales.cash.close-note"
private const val HEAD_KIND = "sales.cash.close-note-head"
private fun noteClaim(id: DomainId): ClaimKey = LocalSalesStoreProfile.unique("cash_close_note", id)
private fun historyClaim(id: DomainId): ClaimKey = LocalSalesStoreProfile.unique("cash_close_notes", id)
private fun positionClaim(session: DomainId, position: Revision): ClaimKey =
    ClaimKey(LocalSalesStoreProfile.UNIQUE_NAMESPACE, "cash_close_note_position/${session.value}/${position.value}")
private fun closed(session: CashSession): Boolean = session.status in setOf(CashSessionStatus.SUBMITTED_CLOSED, CashSessionStatus.REVIEWED)

private suspend fun storedNote(view: CommandReadView, id: DomainId): LocalCashCloseNoteViewV1 {
    val row = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(NOTE_KIND, id))
    val value = decodeLocalSalesSource(row.payload, CloseNoteRecordV1.serializer())
    val note = value.note
    requireLocalSalesSource(row.domainRevision() == Revision(0) && value.scope.value() == view.scope &&
        note.id == id && note.position.value < Long.MAX_VALUE && view.claim(noteClaim(id)) == note.sessionId &&
        view.claim(positionClaim(note.sessionId, note.position)) == id)
    return note
}

private fun requireSession(note: LocalCashCloseNoteViewV1, session: CashSession) {
    requireLocalSalesSource(note.sessionId == session.id &&
        note.originalCloseOperationId == checkNotNull(session.closeReport).operationId &&
        note.evidence.actorId != session.custodianId)
}

private suspend fun note(view: CommandReadView, session: CashSession, id: DomainId): LocalCashCloseNoteViewV1 =
    storedNote(view, id).also { requireSession(it, session) }

private suspend fun head(view: CommandReadView, session: CashSession): LocalSalesRow<CloseNoteHeadV1>? {
    val key = LocalSalesStoreProfile.key(HEAD_KIND, session.id)
    if (!localSalesSourcePresent(view, key, listOf(historyClaim(session.id)))) return null
    val row = LocalSalesStorage.required(view, key)
    val value = decodeLocalSalesSource(row.payload, CloseNoteHeadV1.serializer())
    requireLocalSalesSource(value.scope.value() == view.scope && value.sessionId == session.id &&
        value.originalCloseOperationId == checkNotNull(session.closeReport).operationId && value.revision == row.domainRevision() &&
        value.revision.value < Long.MAX_VALUE && view.claim(historyClaim(session.id)) == session.id)
    requireLocalSalesSource(note(view, session, value.latestNoteId).position == value.revision)
    return LocalSalesRow(row, value)
}

/** Current review authority is rechecked by the kernel; this handler never writes a cash/session row. */
public class LocalCashCloseNoteHandler : MasterCommandHandler {
    override public val key: CommandKey get() = LocalCashCloseNoteCodec.COMMAND
    override public val writableKinds: Set<String> = setOf(NOTE_KIND, HEAD_KIND)
    override public val claimNamespaces: Set<String> = setOf(LocalSalesStoreProfile.UNIQUE_NAMESPACE)
    override public fun normalize(input: StoredBytes): StoredBytes = LocalCashCloseNoteCodec.normalize(input)

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(view.scope == context.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalCashCloseNoteCodec.decode(normalized)
        if (!localSalesSourcePresent(view, CashNonSaleStoreProfile.sessionKey(request.sessionId),
                listOf(LocalSalesStoreProfile.unique("cash_session", request.sessionId)),
                listOf(LocalSalesStoreProfile.key(HEAD_KIND, request.sessionId))))
            return LocalSalesPrepared(context, SecurityAction.CASH_REVIEW, request.sessionId, request.expectedSessionRevision,
                context.originalActorId, "cash.close.note", resources = setOf(cashResource(request.sessionId))) { _, _ ->
                ProposedOutcome.Reject("cash.close.note.refused",
                    LocalCashCloseNoteCodec.result(LocalCashCloseNoteResultV1.Refused(LocalCashCloseNoteRefusalV1.SESSION_NOT_AVAILABLE)))
            }
        val session = LocalSalesStorage.cash(view, request.sessionId).session.value
        val previous = if (closed(session)) head(view, session) else null
        val id = request.evidence.evidenceId
        val duplicate = localSalesSourcePresent(view, LocalSalesStoreProfile.key(NOTE_KIND, id), listOf(noteClaim(id)))
        if (duplicate) storedNote(view, id) // A conflicting valid identity is not a missing/corrupt source.
        return LocalSalesPrepared(context, SecurityAction.CASH_REVIEW, session.id, request.expectedSessionRevision,
            session.custodianId, "cash.close.note", resources = setOf(cashResource(session.id))) { _, now ->
            fun refuse(reason: LocalCashCloseNoteRefusalV1): ProposedOutcome = ProposedOutcome.Reject("cash.close.note.refused",
                LocalCashCloseNoteCodec.result(LocalCashCloseNoteResultV1.Refused(reason)))
            when {
                !closed(session) -> refuse(LocalCashCloseNoteRefusalV1.SESSION_NOT_CLOSED)
                session.custodianId == context.originalActorId -> refuse(LocalCashCloseNoteRefusalV1.SELF_REVIEW)
                session.revision != request.expectedSessionRevision || previous?.value?.latestNoteId != request.expectedLatestNoteId ->
                    refuse(LocalCashCloseNoteRefusalV1.STALE_REVIEW)
                duplicate -> refuse(LocalCashCloseNoteRefusalV1.DUPLICATE_NOTE)
                previous?.value?.revision?.value == Long.MAX_VALUE - 1 -> refuse(LocalCashCloseNoteRefusalV1.CAPACITY_REQUIRED)
                else -> closeNoteOutcome(request, session, previous, context.originalActorId, context.operationId, now)
            }
        }
    }
}

/** The same append-only projection serves a currently authorized command and an inert
 * native historical equality check. No session, count, assignment or ledger write exists. */
private fun closeNoteOutcome(request: LocalCashCloseNoteRequestV1, session: CashSession,
    previous: LocalSalesRow<CloseNoteHeadV1>?, actorId: DomainId, operationId: OperationId,
    recordedAt: UtcInstant): ProposedOutcome.Apply {
    val position = previous?.value?.revision?.next() ?: Revision(0)
    val id = request.evidence.evidenceId
    val value = LocalCashCloseNoteViewV1(session.id, checkNotNull(session.closeReport).operationId,
        position, previous?.value?.latestNoteId, LocalCashActorEvidenceV1(id, actorId, request.evidence.occurredAt),
        request.reason, operationId, recordedAt)
    val next = CloseNoteHeadV1(LocalSalesScope.from(session.scope), session.id, value.originalCloseOperationId, position, id)
    return ProposedOutcome.Apply(LocalCashCloseNoteCodec.result(LocalCashCloseNoteResultV1.Recorded(value, session.revision)),
        listOf(rootWrite(NOTE_KIND, id, CloseNoteRecordV1(next.scope, value), CloseNoteRecordV1.serializer(), null),
            rootWrite(HEAD_KIND, session.id, next, CloseNoteHeadV1.serializer(), previous?.stored)),
        listOf(ClaimChange.Acquire(noteClaim(id), session.id), ClaimChange.Acquire(positionClaim(session.id, position), id)) +
            if (previous == null) listOf(ClaimChange.Acquire(historyClaim(session.id), session.id)) else emptyList())
}

/** Actual native BEFORE head + complete earlier note original, not today's history page. */
internal suspend fun joinCashCloseNoteHistory(command: RuntimeHistoricalCommand): Boolean {
    if (command.original !is RetainedOriginalV2.Legacy || command.command != LocalCashCloseNoteCodec.COMMAND) return false
    val request = LocalCashCloseNoteCodec.decode(command.normalized)
    check(LocalCashCloseNoteCodec.encode(request) == command.normalized)
    val session = LocalCashHistoryRead(command).cash(request.sessionId).session.value
    check(closed(session) && session.custodianId != command.actorId && session.revision == request.expectedSessionRevision)
    val headKey = LocalSalesStoreProfile.key(HEAD_KIND, session.id)
    val source = command.aggregateSource(headKey)?.row
    val headOwner = command.claim(historyClaim(session.id))
    val previous = source?.let { row ->
        check(row.schema == LocalSalesStoreProfile.schema(HEAD_KIND) && headOwner == session.id)
        val value = decodeLocalSalesSource(row.payload, CloseNoteHeadV1.serializer())
        check(value.scope.value() == command.scope && value.sessionId == session.id &&
            value.originalCloseOperationId == checkNotNull(session.closeReport).operationId &&
            value.revision == row.domainRevision() && value.revision.value < Long.MAX_VALUE)
        val key = LocalSalesStoreProfile.key(NOTE_KIND, value.latestNoteId)
        val earlier = command.aggregateSource(key)?.row ?: error("cash.history.note_missing")
        check(earlier.schema == LocalSalesStoreProfile.schema(NOTE_KIND) && earlier.domainRevision() == Revision(0))
        val stored = decodeLocalSalesSource(earlier.payload, CloseNoteRecordV1.serializer())
        val note = stored.note
        check(stored.scope.value() == command.scope && note.id == value.latestNoteId && note.position == value.revision &&
            command.claim(noteClaim(note.id)) == session.id && command.claim(positionClaim(session.id, note.position)) == note.id)
        requireSession(note, session)
        LocalSalesRow(row, value)
    }
    if (source == null) check(headOwner == null)
    check(previous?.value?.latestNoteId == request.expectedLatestNoteId && previous?.value?.revision?.value != Long.MAX_VALUE - 1)
    val position = previous?.value?.revision?.next() ?: Revision(0)
    val id = request.evidence.evidenceId
    check(command.aggregateSource(LocalSalesStoreProfile.key(NOTE_KIND, id)) == null && command.claim(noteClaim(id)) == null &&
        command.claim(positionClaim(session.id, position)) == null)
    command.requireRequirement(com.ukkera.pos.sales.salesAuthorizationRequirement(SecurityAction.CASH_REVIEW, command.scope,
        session.id, request.expectedSessionRevision, command.operationId, command.semanticDigest, "cash.close.note", session.custodianId))
    command.requireLegacyEffects(closeNoteOutcome(request, session, previous, command.actorId, command.operationId, command.recordedAt),
        setOf(cashResource(session.id), com.ukkera.pos.recovery.RecoveryResourceKey(
            com.ukkera.pos.recovery.RecoveryResourceKind.AUTHORIZATION, command.actorId)))
    return true
}

/** Separate own/reviewer projections. An explanatory note grants no access to an active till. */
public class LocalCashCloseNotesReadHandler : MasterReadHandler {
    override public val key: ReadKey get() = LocalCashCloseNoteCodec.QUERY
    override public fun normalize(input: StoredBytes): StoredBytes = LocalCashCloseNoteCodec.request(
        localSalesDecode(input, LocalCashCloseNoteQueryV1.serializer(), 2_048)).payload

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(view.scope == context.scope && context.key == key)
        val query = localSalesDecode(normalized, LocalCashCloseNoteQueryV1.serializer(), 2_048)
        val action = if (query.review) SecurityAction.CASH_REVIEW else SecurityAction.CASH_READ
        fun unavailable(): PreparedRead = localSalesRead(context, action, query.sessionId, Revision(0), context.actorId,
            "cash.close.notes.unavailable", LocalCashCloseNotesPageV1.NotAvailable, LocalCashCloseNotesPageV1.serializer())
        if (!localSalesSourcePresent(view, CashNonSaleStoreProfile.sessionKey(query.sessionId),
                listOf(LocalSalesStoreProfile.unique("cash_session", query.sessionId)),
                listOf(LocalSalesStoreProfile.key(HEAD_KIND, query.sessionId)))) return unavailable()
        val session = LocalSalesStorage.cash(view, query.sessionId).session.value
        if (!closed(session) || (session.custodianId == context.actorId) == query.review) return unavailable()
        val latest = head(view, session)
        var newer: LocalCashCloseNoteViewV1? = null
        var current = if (query.before == null) latest?.value?.latestNoteId else {
            if (latest == null || !localSalesSourcePresent(view, LocalSalesStoreProfile.key(NOTE_KIND, query.before), listOf(noteClaim(query.before))))
                return unavailable()
            val cursor = storedNote(view, query.before)
            // Caller input may legitimately name another close. Validate its stored claims,
            // then refuse the foreign cursor without treating healthy storage as corruption.
            if (cursor.sessionId != session.id) return unavailable()
            requireSession(cursor, session)
            requireLocalSalesSource(cursor.position <= latest.value.revision)
            newer = cursor
            cursor.previousNoteId
        }
        val rows = mutableListOf<LocalCashCloseNoteViewV1>()
        while (current != null && rows.size < query.limit) {
            val value = note(view, session, current)
            newer?.let { requireLocalSalesSource(it.previousNoteId == value.id && it.position.value > 0L &&
                value.position.value == it.position.value - 1) }
            rows += value
            newer = value
            current = value.previousNoteId
        }
        val result = LocalCashCloseNotesPageV1.Page(session.id, session.revision, checkNotNull(session.closeReport).operationId,
            latest?.value?.latestNoteId, latest?.value?.revision?.value?.plus(1) ?: 0L, query.before, rows,
            if (current != null) rows.last().id else null)
        return localSalesRead(context, action, session.id, session.revision, session.custodianId,
            "cash.close.notes.read", result, LocalCashCloseNotesPageV1.serializer())
    }
}
