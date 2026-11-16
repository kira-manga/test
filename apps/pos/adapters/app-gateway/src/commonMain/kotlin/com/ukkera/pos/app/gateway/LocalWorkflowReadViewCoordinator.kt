package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalWorkflowPage
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.LocalWorkflowReadUpdate
import com.ukkera.pos.app.rootReadSelection
import com.ukkera.pos.database.LocalMasterChangeCursorV1
import com.ukkera.pos.database.LocalMasterChangesBatchClient
import com.ukkera.pos.database.LocalMasterChangesBatchReplyV1
import com.ukkera.pos.database.LocalMasterChangesClient
import com.ukkera.pos.database.LocalMasterChangesFailureV1
import com.ukkera.pos.database.LocalMasterChangesReplyV1
import com.ukkera.pos.database.LocalMasterProjectionCacheV1
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.types.DomainId

/**
 * Maps an imperative UI read using ONE Master batch observation. Discovery results are never
 * published; response-dependent selectors trigger a fresh whole batch and a complete remap.
 * This layer has no mutation/ACK API and cannot replay financial work during recovery.
 */
internal class LocalWorkflowReadViewCoordinator(
    private val client: LocalMasterChangesClient,
    private val store: LocalClientReadViewStore,
    private val profile: LocalMasterRpcWorkflowProfile,
) {
    private val lifetimeId = DomainId.random()
    private var retainedQuery: LocalWorkflowQuery? = null

    suspend fun read(access: GatewayAccess, query: LocalWorkflowQuery,
        render: suspend (GatewayAccess, LocalWorkflowQuery) -> LocalWorkflowPage): LocalWorkflowPage {
        repeat(3) {
            try { return readOnce(access, query, render) }
            catch (refused: ReadRefused) { throw refused.failure }
            catch (_: MissingCut) {
                if (query != query.rootReadSelection()) throw GatewayFailure(LocalServiceProblem.READ_RESTART_REQUIRED)
            }
        }
        throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
    }

    private suspend fun readOnce(access: GatewayAccess, query: LocalWorkflowQuery,
        render: suspend (GatewayAccess, LocalWorkflowQuery) -> LocalWorkflowPage): LocalWorkflowPage {
        access.checkCurrent()
        val previous = stored()
        if (previous != null && !bound(previous, access)) invalidate()
        val currentBatch = previous?.takeIf { bound(it, access) }
        val expectedCut = if (query != query.rootReadSelection()) {
            if (retainedQuery?.rootReadSelection() != query.rootReadSelection() || currentBatch == null)
                throw GatewayFailure(LocalServiceProblem.READ_RESTART_REQUIRED)
            currentBatch.pages.first().snapshot().cursor
        } else null
        val selections = mutableListOf<ReadRequest>()
        if (retainedQuery == query) currentBatch?.pages?.forEach { selections += it.selection() }
        if (selections.isEmpty()) discover(access, query, expectedCut, selections, render)
        // A selection-only UI exposes no private typed projection. Current session observation
        // still precedes this pure rendering, and no fictitious cursor is created.
        if (selections.isEmpty()) {
            invalidate()
            return render(access, query)
        }
        repeat(MAX_CLIENT_READ_VIEW_SELECTORS_V1 + 1) {
            access.checkCurrent()
            val snapshots = snapshotBatch(access, selections)
            if (expectedCut != null && snapshots.any { !sameClientReadCut(expectedCut, it.cursor) })
                throw GatewayFailure(LocalServiceProblem.READ_RESTART_REQUIRED)
            val used = mutableListOf<Int>()
            try {
                val page = render(access.withReadOverride { request ->
                    requireInstalled(access, request)
                    val index = selections.indexOfFirst { sameSelection(it, request) }
                    if (index < 0) throw MissingSelection(request)
                    if (index !in used) used += index
                    LocalMasterProjectionCacheV1.copy(snapshots[index]).data
                }, query)
                access.checkCurrent()
                require(used.isNotEmpty())
                val batch = StoredClientReadViewBatchV1(store.owner, access.lease.credentials.connectionId,
                    lifetimeId, used.map { index -> StoredClientReadViewV1.from(selections[index], snapshots[index]) })
                batch.validate(store.owner)
                if (store.replace(batch) != JournalWrite.DURABLE)
                    throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                access.checkCurrent()
                retainedQuery = query
                return page
            } catch (missing: MissingSelection) {
                addSelection(selections, missing.request)
            }
        }
        throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
    }

    /** Pull EVERY contributing selector: a clock-sensitive secondary projection may change
     * even when the anchor and durable branch sequence do not. */
    suspend fun poll(access: GatewayAccess, query: LocalWorkflowQuery,
        render: suspend (GatewayAccess, LocalWorkflowQuery) -> LocalWorkflowPage): LocalWorkflowReadUpdate? {
        access.checkCurrent()
        val batch = stored()
        if (batch == null || retainedQuery != query)
            return LocalWorkflowReadUpdate.Available(read(access, query, render))
        if (!bound(batch, access)) {
            invalidate()
            return LocalWorkflowReadUpdate.Unavailable(LocalServiceProblem.SESSION_ENDED)
        }
        var changed = false
        for (page in batch.pages) {
            val original = page.snapshot()
            when (val reply = client.pull(access.lease.credentials, profile, page.selection(), original.cursor)) {
                is LocalMasterChangesReplyV1.Unchanged -> {
                    // Never retain an advancing cursor without a new page, even if a peer labels
                    // it Unchanged. Clock-only projection changes require a replacement too.
                    if (reply.cursor != original.cursor)
                        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                }
                is LocalMasterChangesReplyV1.Snapshot -> { changed = true; break }
                is LocalMasterChangesReplyV1.Unavailable -> {
                    if (reply.reason == LocalMasterChangesFailureV1.CURSOR_EXPIRED) { changed = true; break }
                    throw GatewayFailure(changesProblem(reply.reason))
                }
            }
            access.checkCurrent()
        }
        access.checkCurrent()
        if (!changed) return null
        if (query != query.rootReadSelection()) {
            invalidate()
            return LocalWorkflowReadUpdate.RestartRequired
        }
        return LocalWorkflowReadUpdate.Available(read(access, query, render))
    }

    suspend fun invalidate() {
        retainedQuery = null
        if (store.replace(null) != JournalWrite.DURABLE)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    }

    private suspend fun stored(): StoredClientReadViewBatchV1? = when (val result = store.load()) {
        is JournalRead.Value -> result.value?.also { it.validate(store.owner) }
        JournalRead.Unavailable -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    }

    private fun bound(batch: StoredClientReadViewBatchV1, access: GatewayAccess): Boolean {
        if (batch.owner != store.owner || batch.clientLifetimeId != lifetimeId ||
            batch.connectionId != access.lease.credentials.connectionId) return false
        return batch.pages.all { page ->
            val cursor = page.snapshot().cursor
            cursor.scope == access.view.scope && cursor.actorId == access.view.actorId &&
                cursor.deviceId == access.view.deviceId && cursor.sessionId == access.view.sessionId &&
                cursor.sessionGeneration == access.view.generation && cursor.policyRevision == access.view.policy.revision &&
                cursor.profile == profile
        }
    }

    private suspend fun discover(access: GatewayAccess, query: LocalWorkflowQuery,
        expected: LocalMasterChangeCursorV1?, selections: MutableList<ReadRequest>,
        render: suspend (GatewayAccess, LocalWorkflowQuery) -> LocalWorkflowPage) {
        var cut = expected
        var failure: GatewayFailure? = null
        render(access.withReadOverride { request ->
            access.checkCurrent()
            requireInstalled(access, request)
            addSelection(selections, request)
            when (val reply = client.snapshot(access.lease.credentials, profile, request, cut)) {
                is LocalMasterChangesReplyV1.Snapshot -> {
                    if (cut != null && !sameClientReadCut(checkNotNull(cut), reply.cursor))
                        throw MissingCut()
                    cut = reply.cursor
                    LocalMasterProjectionCacheV1.copy(reply).data
                }
                is LocalMasterChangesReplyV1.Unchanged -> throw MissingCut()
                is LocalMasterChangesReplyV1.Unavailable -> {
                    if (reply.reason == LocalMasterChangesFailureV1.CURSOR_EXPIRED) throw MissingCut()
                    val refused = GatewayFailure(changesProblem(reply.reason))
                    failure = refused
                    throw refused
                }
            }
        }, query)
        // Some existing mappings show partial read-only information on a dependency refusal.
        // Such a page is not eligible for publication as one complete synchronized view.
        failure?.let { throw it }
        access.checkCurrent()
    }

    private suspend fun snapshotBatch(access: GatewayAccess, selections: List<ReadRequest>): List<LocalMasterChangesReplyV1.Snapshot> {
        val result = if (profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS) {
            val batchClient = client as? LocalMasterChangesBatchClient
                ?: throw GatewayFailure(LocalServiceProblem.NOT_CONFIGURED)
            when (val reply = batchClient.snapshotBatch(access.lease.credentials, profile, selections.toList())) {
                is LocalMasterChangesBatchReplyV1.Snapshot -> reply.snapshots
                is LocalMasterChangesBatchReplyV1.Unavailable -> throw GatewayFailure(changesProblem(reply.reason))
            }
        } else {
            // Existing narrow audiences retain their original one-shot/display lifetimes.
            // Never manufacture multi-selector consistency through sequential calls.
            if (selections.size != 1) throw GatewayFailure(LocalServiceProblem.NOT_CONFIGURED)
            when (val reply = client.snapshot(access.lease.credentials, profile, selections.single())) {
                is LocalMasterChangesReplyV1.Snapshot -> listOf(reply)
                is LocalMasterChangesReplyV1.Unavailable -> throw GatewayFailure(changesProblem(reply.reason))
                is LocalMasterChangesReplyV1.Unchanged -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            }
        }
        access.checkCurrent()
        if (result.size != selections.size || result.isEmpty()) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        val first = result.first()
        result.forEach { snapshot ->
            LocalMasterProjectionCacheV1.copy(snapshot)
            if (!sameClientReadCut(first.cursor, snapshot.cursor) || snapshot.data.observedAt != first.data.observedAt ||
                snapshot.cursor.profile != profile || snapshot.cursor.scope != store.owner.scope ||
                snapshot.cursor.actorId != store.owner.actorId || snapshot.cursor.deviceId != store.owner.deviceId ||
                snapshot.cursor.sessionId != access.view.sessionId || snapshot.cursor.sessionGeneration != access.view.generation ||
                snapshot.cursor.policyRevision != access.view.policy.revision)
                throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        }
        return result
    }

    private fun addSelection(selections: MutableList<ReadRequest>, request: ReadRequest) {
        if (selections.any { sameSelection(it, request) }) return
        if (selections.size == MAX_CLIENT_READ_VIEW_SELECTORS_V1 ||
            selections.sumOf { it.payload.size.toLong() } + request.payload.size > 65_536)
            throw GatewayFailure(LocalServiceProblem.READ_CAPACITY_REQUIRED)
        selections += ReadRequest(request.query, request.payload)
    }

    private class MissingSelection(val request: ReadRequest) : Throwable()
    private class ReadRefused(val failure: GatewayFailure) : Throwable()
    /** Bypasses partial-projection Exception handlers in existing imperative mappings. */
    private class MissingCut : Throwable()

    companion object {
        private fun requireInstalled(access: GatewayAccess, request: ReadRequest) {
            try { access.installed.requireRead(request) }
            catch (failure: GatewayFailure) { throw ReadRefused(failure) }
        }
        private fun sameSelection(a: ReadRequest, b: ReadRequest): Boolean = a.query == b.query && a.payload == b.payload
    }
}

internal fun changesProblem(reason: LocalMasterChangesFailureV1): LocalServiceProblem = when (reason) {
    LocalMasterChangesFailureV1.AUTHORIZATION_DENIED, LocalMasterChangesFailureV1.WRONG_SCOPE -> LocalServiceProblem.DENIED
    LocalMasterChangesFailureV1.STORAGE_UNCERTAIN -> LocalServiceProblem.STORAGE_UNCERTAIN
    LocalMasterChangesFailureV1.SERVICE_BUSY -> LocalServiceProblem.RETRY_LATER
    LocalMasterChangesFailureV1.CURSOR_EXPIRED -> LocalServiceProblem.READ_RESTART_REQUIRED
    LocalMasterChangesFailureV1.CAPACITY_EXCEEDED -> LocalServiceProblem.READ_CAPACITY_REQUIRED
    LocalMasterChangesFailureV1.UNKNOWN_QUERY -> LocalServiceProblem.NOT_IMPLEMENTED
    LocalMasterChangesFailureV1.MALFORMED_REQUEST -> LocalServiceProblem.INVALID_INPUT
    LocalMasterChangesFailureV1.WRITER_NOT_QUALIFIED -> LocalServiceProblem.UNAVAILABLE
}
