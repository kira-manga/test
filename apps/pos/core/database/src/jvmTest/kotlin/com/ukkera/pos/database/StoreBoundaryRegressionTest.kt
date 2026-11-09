package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.nio.ByteBuffer
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class StoreBoundaryRegressionTest {
    @Test fun outOfProfileSupervisorAuditTimeDoesNotAppendOrDiscloseAnyOutcome() = runTest {
        val f = StoreFixture(); f.seed()
        try {
            val store = f.store()
            val request = f.request()
            val ownerResult = assertIs<MutationReply.Recorded>(store.execute(testPrincipal(), request))
            val supervisor = testPrincipal(testId(610))
            val before = f.count("audit_event")
            for (text in listOf("+10000-01-01T00:00:00Z", "-0001-01-01T00:00:00Z")) {
                f.clockValue = UtcInstant.parseCanonical(text)
                assertEquals(DeferredReason.AUTHORIZATION_DENIED,
                    assertIs<LookupReply.Deferred>(store.lookup(supervisor, f.identity(request))).reason)
                assertEquals(DeferredReason.AUTHORIZATION_DENIED,
                    assertIs<MutationReply.Deferred>(store.execute(supervisor, request)).reason)
                assertEquals(before, f.count("audit_event"))
                // An existing valid fact does not need a new audit for its own actor.
                assertEquals(ownerResult.result,
                    assertIs<LookupReply.Found>(store.lookup(testPrincipal(), f.identity(request))).result.result)
            }
            f.clockValue = TEST_TIME
            assertIs<LookupReply.Found>(store.lookup(supervisor, f.identity(request)))
            assertEquals(before + 1, f.count("audit_event"))
            assertEquals(1L, f.count("operation_result"))
        } finally { f.close() }
    }

    @Test fun authorizationFootprintMustIncludeActualActorNotJustAnyEmployeeKey() = runTest {
        val f = StoreFixture(); f.seed()
        try {
            var evaluated = false
            val handler = object : MasterCommandHandler by f.handler {
                override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
                    val prepared = f.handler.prepare(normalized, view, context)
                    return object : PreparedMutation() {
                        override val requirement = prepared.requirement
                        override val affectedResources = setOf(
                            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, testId(620)),
                            RecoveryResourceKey(RecoveryResourceKind.STOCK, testId(10)),
                        )
                        override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                            evaluated = true
                            return prepared.evaluate(authorization, recordedAt)
                        }
                    }
                }
            }
            val store = AtomicMasterStore(f.database, TEST_SCOPE, TEST_AUTHORITY, listOf(handler), f.auth, f.runtime, MasterClock { TEST_TIME })
            assertEquals(DeferredReason.RESOURCE_RECONCILIATION_REQUIRED,
                assertIs<MutationReply.Deferred>(store.execute(testPrincipal(), f.request())).reason)
            assertFalse(evaluated)
            assertEquals(0L, f.count("operation_result"))
            assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), f.request())).committed)
        } finally { f.close() }
    }

    @Test fun epochLocalPositionsCoexistWhileOperationIdentityAndOriginalResultsSpanEpochs() = runTest {
        val f = StoreFixture(); f.seed()
        try {
            val principal = testPrincipal()
            val first = f.request(630)
            val oldStore = f.store()
            val oldResult = assertIs<MutationReply.Recorded>(oldStore.execute(principal, first))
            val successor = AuthorityIdentity(testId(631), testId(632), Revision(2))
            // SYNTHETIC database appointment solely to test position/index shape.
            // This is NOT an implemented/authorized transfer or proof of old-writer fencing.
            f.database.useWriterConnection { c ->
                c.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    usePrepared("UPDATE branch_authority SET assignmentId=?, deviceId=?, epoch=2, sequence=0, revision=revision+1 WHERE scope=?") {
                        it.bindText(1, successor.assignmentId.value); it.bindText(2, successor.deviceId.value)
                        it.bindText(3, TEST_SCOPE.storageKey()); it.step()
                    }
                }
            }
            val newStore = f.store(authority = successor)
            val next = f.request(633, CounterInput(id = testId(634)))
            val newResult = assertIs<MutationReply.Recorded>(newStore.execute(principal, next))
            assertTrue(newResult.committed)
            assertEquals(Revision(1), oldResult.authorityEpoch)
            assertEquals(Revision(2), newResult.authorityEpoch)
            assertEquals(JournalSequence(1), oldResult.sequence)
            assertEquals(JournalSequence(1), newResult.sequence)
            val recovered = assertIs<MutationReply.Recorded>(newStore.execute(principal, first))
            assertTrue(recovered.replayed)
            assertEquals(oldResult.authorityEpoch, recovered.authorityEpoch)
            assertEquals(oldResult.sequence, recovered.sequence)
            assertEquals(oldResult.result, recovered.result)
            assertEquals(DeferredReason.WRITER_NOT_QUALIFIED,
                assertIs<MutationReply.Deferred>(oldStore.execute(principal, f.request(635))).reason)
            assertIs<MutationReply.IdentityConflict>(newStore.execute(principal, f.request(630, CounterInput(delta = 2))))
            assertEquals(2L, f.count("operation_result")); assertEquals(2L, f.count("journal_envelope"))
            f.database.useWriterConnection { c ->
                c.usePrepared("SELECT j.epoch, j.sequence, j.hash, o.body FROM journal_envelope j JOIN outbox_intent o ON o.scope=j.scope AND o.operationId=j.operationId AND o.ordinal=0 ORDER BY j.epoch") { s ->
                    var count = 0
                    while (s.step()) {
                        val frames = independentlyDecodeFrames(s.getBlob(3))
                        assertEquals(listOf("ukkera.journal-upload/v1", TEST_SCOPE.branchId.value,
                            s.getLong(0).toString(), s.getLong(1).toString(), s.getText(2)), frames)
                        count++
                    }
                    assertEquals(2, count)
                }
                val collision = assertFails {
                    c.usePrepared("INSERT OR REPLACE INTO operation_result SELECT scope, operationId, actorId, command, commandVersion, digest, outcome, result, resultHash, 2, 2, committedAt, authenticationId, policyRevision FROM operation_result WHERE epoch=1") { it.step() }
                }
                assertTrue(collision.message.orEmpty().contains("identity already exists"))
            }
        } finally { f.close() }
    }

    private fun independentlyDecodeFrames(bytes: ByteArray): List<String> {
        val buffer = ByteBuffer.wrap(bytes)
        return buildList {
            while (buffer.hasRemaining()) {
                require(buffer.remaining() >= 4)
                val size = buffer.int
                require(size >= 0 && size <= buffer.remaining())
                val field = ByteArray(size); buffer.get(field)
                add(field.toString(Charsets.UTF_8))
            }
        }
    }
}
