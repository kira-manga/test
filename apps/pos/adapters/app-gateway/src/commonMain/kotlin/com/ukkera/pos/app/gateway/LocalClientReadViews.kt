package com.ukkera.pos.app.gateway

import com.ukkera.pos.database.LocalMasterChangeCursorV1
import com.ukkera.pos.database.LocalMasterChangesReplyV1
import com.ukkera.pos.database.LocalMasterProjectionCacheV1
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.DomainId
import kotlinx.serialization.Serializable

/** Disposable read data only. Neither this store nor a restored cursor authorizes work. */
internal abstract class LocalClientReadViewStore(val owner: LocalClientIdentity) {
    abstract suspend fun load(): JournalRead<StoredClientReadViewBatchV1?>
    abstract suspend fun replace(batch: StoredClientReadViewBatchV1?): JournalWrite
    abstract suspend fun close()
}

internal const val MAX_CLIENT_READ_VIEW_BYTES_V1: Int = 2 * 1024 * 1024
internal const val MAX_CLIENT_READ_VIEW_SELECTORS_V1: Int = 32

@Serializable
internal data class StoredClientReadViewV1(
    val query: String,
    val version: Int,
    val selectionHex: String,
    val projectionHex: String,
) {
    fun selection(): ReadRequest = ReadRequest(ReadKey(query, version),
        decodeHex(selectionHex, 65_536).useWiped(::StoredBytes))

    fun snapshot(): LocalMasterChangesReplyV1.Snapshot =
        decodeHex(projectionHex, LocalMasterProjectionCacheV1.MAX_ENCODED_BYTES)
            .useWiped(LocalMasterProjectionCacheV1::decode)

    override fun toString(): String = "StoredClientReadViewV1([REDACTED])"

    companion object {
        fun from(selection: ReadRequest, snapshot: LocalMasterChangesReplyV1.Snapshot): StoredClientReadViewV1 =
            StoredClientReadViewV1(selection.query.name, selection.query.version,
                selection.payload.copyBytes().useWiped(::encodeHex),
                LocalMasterProjectionCacheV1.encode(snapshot).useWiped(::encodeHex))
    }
}

/** One indivisible page set: a cursor can never survive without its corresponding projection. */
@Serializable
internal data class StoredClientReadViewBatchV1(
    val owner: LocalClientIdentity,
    val connectionId: DomainId,
    val clientLifetimeId: DomainId,
    val pages: List<StoredClientReadViewV1>,
) {
    fun validate(expected: LocalClientIdentity) {
        require(owner == expected && pages.size in 1..MAX_CLIENT_READ_VIEW_SELECTORS_V1)
        require(pages.map { Triple(it.query, it.version, it.selectionHex) }.distinct().size == pages.size)
        var cut: LocalMasterChangeCursorV1? = null
        var observedAt: com.ukkera.pos.types.UtcInstant? = null
        var selectionBytes = 0L
        var projectionBytes = 0L
        pages.forEach { page ->
            val selection = page.selection()
            selectionBytes += selection.payload.size
            val snapshot = page.snapshot()
            projectionBytes += snapshot.data.payload.size
            val cursor = snapshot.cursor
            require(cursor.scope == owner.scope && cursor.actorId == owner.actorId && cursor.deviceId == owner.deviceId)
            cut?.let { require(sameClientReadCut(it, cursor)) }
            observedAt?.let { require(it == snapshot.data.observedAt) }
            cut = cursor
            observedAt = snapshot.data.observedAt
        }
        // LAN batches use the same aggregate bounds as their Master contract. A single
        // restricted-profile snapshot remains bounded by its original per-query envelope.
        require(selectionBytes <= 65_536 && projectionBytes <= 65_536)
    }

    override fun toString(): String = "StoredClientReadViewBatchV1([REDACTED])"
}

internal fun sameClientReadCut(a: LocalMasterChangeCursorV1, b: LocalMasterChangeCursorV1): Boolean =
    a.scope == b.scope && a.authority == b.authority && a.branchRevision == b.branchRevision &&
        a.sequence == b.sequence && a.journalHash == b.journalHash && a.actorId == b.actorId &&
        a.deviceId == b.deviceId && a.sessionId == b.sessionId && a.sessionGeneration == b.sessionGeneration &&
        a.admissionRevision == b.admissionRevision && a.policyRevision == b.policyRevision && a.profile == b.profile
