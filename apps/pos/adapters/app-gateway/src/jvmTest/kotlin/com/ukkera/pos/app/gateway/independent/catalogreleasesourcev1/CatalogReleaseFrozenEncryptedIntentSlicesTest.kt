package com.ukkera.pos.app.gateway.independent.catalogreleasesourcev1

import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.gateway.JournalWrite
import com.ukkera.pos.app.gateway.JvmClientJournalOpenResult
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.catalog.store.LocalCatalogReleaseCodecV1
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Real encrypted client-intent custody only; no branch transaction, committed result or native publication. */
class CatalogReleaseFrozenEncryptedIntentSlicesTest {
    private val inputs = FrozenCatalogReleaseGatewayContentInputs

    @Test
    fun csr017CancelledClientWaitCloseAndReopenRetainTheExactDispatchedIntent() = runTest {
        val fixture = SyntheticCatalogReleaseEncryptedJournal.create()
        var clientWait: Job? = null
        try {
            val original = inputs.entry()
            val durablyStaged = CompletableDeferred<Unit>()
            clientWait = launch {
                assertEquals(JournalWrite.DURABLE, fixture.journal.stage(original))
                durablyStaged.complete(Unit)
                // Only the caller's wait is cancelled; no native/Master call is simulated.
                awaitCancellation()
            }
            durablyStaged.await()
            assertEquals(JournalWrite.DURABLE, fixture.journal.markDispatched(original.operationId))
            clientWait.cancelAndJoin()
            assertRetained(original, fixture.find(original.operationId), LocalPendingPhase.DISPATCH_UNRESOLVED)
            fixture.reopenOriginal()
            assertRetained(original, fixture.find(original.operationId), LocalPendingPhase.DISPATCH_UNRESOLVED)
            assertEquals(JournalWrite.DURABLE, fixture.journal.stage(original))
            assertEquals(listOf(original.operationId), fixture.pending().map { it.operationId })
            assertRetained(original, fixture.find(original.operationId), LocalPendingPhase.DISPATCH_UNRESOLVED)
        } finally {
            clientWait?.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun csr018ExactOriginalRestageIsIdempotentButSemanticMutationsConflictAcrossReopen() = runTest {
        val fixture = SyntheticCatalogReleaseEncryptedJournal.create()
        try {
            val definition = inputs.definition(0)
            val original = inputs.entry(value = definition)
            val mutations = listOf(
                inputs.entry(original.operationId, definition.copy(publicationId = inputs.token(104))),
                inputs.entry(original.operationId, definition, Revision(9)),
                inputs.entry(original.operationId, definition.copy(members = definition.members.reversed())),
                inputs.entry(original.operationId, definition.copy(members = definition.members.mapIndexed { index, member ->
                    if (index == 0) member.copy(skuRevision = Revision(4)) else member
                })))
            assertEquals(JournalWrite.DURABLE, fixture.journal.stage(original))
            for (afterReopen in listOf(false, true)) {
                if (afterReopen) fixture.reopenOriginal()
                for (changed in mutations) {
                    assertEquals(JournalWrite.IDENTITY_CONFLICT, fixture.journal.stage(changed))
                    assertRetained(original, fixture.find(original.operationId), LocalPendingPhase.STAGED)
                }
                assertEquals(JournalWrite.DURABLE, fixture.journal.stage(original))
                assertEquals(listOf(original.operationId), fixture.pending().map { it.operationId })
            }
            // Client syntax conflict is not Master replay/freshness/CAS or native source qualification.
        } finally { fixture.close() }
    }

    @Test
    fun csr018DifferentClientOwnerCannotReopenAndRetagTheOriginalActor() = runTest {
        val fixture = SyntheticCatalogReleaseEncryptedJournal.create()
        try {
            val original = inputs.entry()
            assertEquals(JournalWrite.DURABLE, fixture.journal.stage(original))
            val otherOwner = inputs.owner.copy(actorId = inputs.token(502))
            assertIs<JvmClientJournalOpenResult.Unavailable>(fixture.openExistingAs(otherOwner))
            fixture.reopenOriginal()
            assertRetained(original, fixture.find(original.operationId), LocalPendingPhase.STAGED)
            assertEquals(inputs.owner.actorId, fixture.find(original.operationId).owner.actorId)
            // Owner DTO/custody only; account sign-in, session rotation and current recovery access remain unbound.
        } finally { fixture.close() }
    }

    @Test
    fun csr020And022NewerClientIntentNeverRewritesOriginalBodyOrderOrLiteralSummary() = runTest {
        val fixture = SyntheticCatalogReleaseEncryptedJournal.create()
        try {
            val original = inputs.entry(value = inputs.definition(0))
            val newer = inputs.entry(value = inputs.definition(3))
            assertEquals(JournalWrite.DURABLE, fixture.journal.stage(original))
            assertEquals(JournalWrite.DURABLE, fixture.journal.stage(newer))
            fixture.reopenOriginal()
            val retained = fixture.find(original.operationId)
            assertRetained(original, retained, LocalPendingPhase.STAGED)
            val definition = LocalCatalogReleaseCodecV1.decode(retained.request().payload).publication.definition
            assertEquals(Revision(0), definition.revision)
            assertEquals(inputs.orderedMembers(0), definition.members)
            assertEquals(listOf("Blue shirt / M", "Black shirt / L", "Cotton bag"),
                retained.summary.map { it.value.english })
            val laterDefinition = LocalCatalogReleaseCodecV1.decode(fixture.find(newer.operationId).request().payload).publication.definition
            assertEquals(inputs.orderedMembers(3), laterDefinition.members)
            assertEquals(2, laterDefinition.members.size)
            assertEquals(setOf(original.operationId, newer.operationId), fixture.pending().map { it.operationId }.toSet())
            // These are two saved syntax intents, NOT two native completions or branch activations.
        } finally { fixture.close() }
    }

    private fun assertRetained(original: StoredClientIntentV1, actual: StoredClientIntentV1, phase: LocalPendingPhase) {
        assertEquals(original.copy(phase = phase.name), actual)
        assertEquals(original.identity(), actual.identity())
        val before = original.request()
        val after = actual.request()
        assertEquals(before.operationId, after.operationId)
        assertEquals(before.command, after.command)
        assertEquals(before.payload, after.payload)
        assertEquals(before.actionApprovalId, after.actionApprovalId)
        assertNull(actual.recorded)
        assertFalse(actual.acknowledged)
    }
}
