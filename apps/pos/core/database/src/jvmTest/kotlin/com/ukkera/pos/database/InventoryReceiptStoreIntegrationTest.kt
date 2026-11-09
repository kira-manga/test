package com.ukkera.pos.database

import androidx.room3.PooledConnection
import androidx.room3.useWriterConnection
import com.ukkera.pos.inventory.ReceiptValueOrigin
import com.ukkera.pos.inventory.StockMovementReason
import com.ukkera.pos.inventory.ValuationFact
import com.ukkera.pos.inventory.store.FixtureReceiptOrigin
import com.ukkera.pos.inventory.store.InventoryFixtureReceiveRequest
import com.ukkera.pos.inventory.store.InventoryReceiptCompanionBinding
import com.ukkera.pos.inventory.store.InventoryReceiptCompanionCodec
import com.ukkera.pos.inventory.store.InventoryReceiptPostedEffect
import com.ukkera.pos.inventory.store.InventoryReceiptReadCut
import com.ukkera.pos.inventory.store.InventoryReceiptResultCodec
import com.ukkera.pos.inventory.store.InventoryReceiptResultKind
import com.ukkera.pos.inventory.store.InventoryReceiptStoreFailure
import com.ukkera.pos.inventory.store.InventoryReceiptStoreProfile
import com.ukkera.pos.inventory.store.InventoryReceiptStoreRejected
import com.ukkera.pos.recovery.RecoveryAdmissionPolicy
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.recovery.RecoveryResourceSelector
import com.ukkera.pos.recovery.ResourceAdmissionDecision
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real disposable Room files, real command commits and deliberately SYNTHETIC
 * identity, custody, upstream receipt origins and exclusive producer composition.
 * No production receive route, import/frontier qualification or purchase exists.
 */
class InventoryReceiptStoreIntegrationTest {
    @Test fun genesisThenReceiptsReopenAndUnrelatedJournalTailPreserveExactPairsAndEffects() = runTest {
        var f = InventoryReceiptFixture()
        try {
            val genesis = initialize(f)
            val empty = f.read()
            assertEquals(InventoryReceiptResultKind.GENESIS, InventoryReceiptResultCodec.decode(genesis.result).kind)
            assertEquals(Revision(0), empty.snapshot.revision)
            assertEquals(Revision(1), empty.storageGeneration)
            assertTrue(empty.companion.sources.isEmpty() && empty.companion.effects.isEmpty())
            assertNull(empty.companion.previous)

            val first = fixtureReceipt(31_001, 0, 2, 201).copy(origin = FixtureReceiptOrigin(testId(31_101), testId(31_102)))
            val firstRequest = f.request(32_001, first)
            val firstReply = receive(f, firstRequest)
            assertCurrentReceipt(f, first, firstRequest, firstReply, 1)
            val firstRows = f.state().rows
            val second = fixtureReceipt(31_002, 1, 3, 299)
            val secondRequest = f.request(32_002, second)
            val secondReply = receive(f, secondRequest)
            val secondCut = assertCurrentReceipt(f, second, secondRequest, secondReply, 2)
            assertEquals(firstRows.first { it?.kind == InventoryReceiptStoreProfile.CUT_KIND }!!.digest,
                secondCut.companion.previous!!.cutDigest.value)
            assertEquals(firstRows.first { it?.kind == InventoryReceiptStoreProfile.COMPANION_KIND }!!.digest,
                secondCut.companion.previous!!.companionDigest.value)
            for (id in listOf(first.receiptId, first.origin.documentId, first.origin.allocationId,
                second.receiptId, second.origin.documentId, second.origin.allocationId)) {
                assertTrue(RecoveryResourceKey(RecoveryResourceKind.STOCK, id) in f.receiver.lastFootprint)
            }
            assertEquals(5L, secondCut.snapshot.positions.single().onHand.scaled)
            assertEquals(500L, secondCut.snapshot.pools.single().value.carryingValue.minor)
            val beforeReopen = f.state()
            val path = f.path
            f.close()
            f = InventoryReceiptFixture(path)
            assertEquals(beforeReopen, f.state())
            assertEquals(Revision(2), f.read().snapshot.revision)
            assertFailsWith<IllegalArgumentException> { f.store(initializing = true) }

            val counter = f.base.request(32_003, CounterInput(id = testId(31_103)))
            assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), counter)).committed)
            assertEquals(secondRequest.operationId, f.read().companion.producer.operationId)
            assertEquals(4L, f.state().branch.sequence) // Latest inventory producer is not the global journal head.
            val beforeRead = f.state()
            val changes = f.totalChanges()
            repeat(3) { assertEquals(Revision(2), f.read().snapshot.revision) }
            assertEquals(changes, f.totalChanges())
            assertEquals(beforeRead, f.state())

            val third = fixtureReceipt(31_003, 2, 5, 1000)
            val thirdRequest = f.request(32_004, third)
            val thirdReply = receive(f, thirdRequest)
            val thirdCut = assertCurrentReceipt(f, third, thirdRequest, thirdReply, 3)
            assertEquals(10L, thirdCut.snapshot.positions.single().onHand.scaled)
            assertEquals(1500L, thirdCut.snapshot.pools.single().value.carryingValue.minor)
            assertEquals(listOf(first.receiptId, second.receiptId, third.receiptId), thirdCut.snapshot.sourceIds)
            assertEquals(listOf(firstRequest.operationId, secondRequest.operationId, thirdRequest.operationId), thirdCut.snapshot.operationIds)
            assertEquals(listOf(5L, 5L, 5L, 6L, 6L, 4L), f.state().counts)
            f.withView { view ->
                val retained = assertNotNull(view.retainedCommand(firstRequest.operationId))
                assertEquals(firstReply.result, retained.result)
                assertTrue(retained.aggregateWrites.all { it.storageGeneration == Revision(2) })
                assertEquals(0, retained.jobs.size)
            }
        } finally { f.close() }
    }

    @Test fun onlyAnExplicitFreshFixtureMayRegisterGenesisAndWrongIdentityCannotInitialize() = runTest {
        val f = InventoryReceiptFixture()
        try {
            assertFailsWith<IllegalArgumentException> { f.store(initializing = true) }
            f.installFreshSyntheticAuthority()
            val before = f.state()
            val changes = f.totalChanges()
            assertDeferred(f.store().execute(testPrincipal(), f.genesisRequest()), DeferredReason.UNKNOWN_COMMAND)
            assertDeferred(f.store(initializing = true).execute(testPrincipal(), f.genesisRequest(OperationId(testId(32_010).value))),
                DeferredReason.STORAGE_UNCERTAIN)
            assertEquals(before, f.state())
            assertEquals(changes, f.totalChanges())
            assertTrue(assertIs<MutationReply.Recorded>(f.store(initializing = true).execute(testPrincipal(), f.genesisRequest())).committed)
            assertEquals(Revision(0), f.read().snapshot.revision)
            assertFailsWith<IllegalStateException> { f.installFreshSyntheticAuthority() }
        } finally { f.close() }
    }

    @Test fun uncertainGenesisRetriesItsOriginalIdentityWithoutSeedingAHigherGeneration() = runTest {
        for (point in listOf(StoreFaultPoint.BEFORE_COMMIT, StoreFaultPoint.AFTER_COMMIT)) {
            val f = InventoryReceiptFixture()
            try {
                f.installFreshSyntheticAuthority()
                val request = f.genesisRequest()
                val before = f.state()
                var reached = false
                assertDeferred(f.store(initializing = true, fault = StoreFaultProbe {
                    if (it == point) { reached = true; throw IOException("Synthetic genesis uncertainty") }
                }).execute(testPrincipal(), request), DeferredReason.STORAGE_UNCERTAIN)
                assertTrue(reached, "Genesis must reach the requested $point boundary")
                if (point == StoreFaultPoint.BEFORE_COMMIT) assertEquals(before, f.state())
                val original = assertIs<MutationReply.Recorded>(f.store(initializing = true).execute(testPrincipal(), request))
                assertTrue(original.committed)
                assertEquals(point == StoreFaultPoint.AFTER_COMMIT, original.replayed)
                assertEquals(Revision(0), f.read().snapshot.revision)
                assertTrue(f.state().rows.all { it?.revision == 1L })
                assertEquals(listOf(1L, 1L, 1L, 1L, 0L, 3L), f.state().counts)
            } finally { f.close() }
        }
    }

    @Test fun missingEitherOrBothCurrentRowsNeverInitializesOnReadOrReceive() = runTest {
        for (missing in listOf(setOf(InventoryReceiptStoreProfile.CUT_KIND), setOf(InventoryReceiptStoreProfile.COMPANION_KIND),
            setOf(InventoryReceiptStoreProfile.CUT_KIND, InventoryReceiptStoreProfile.COMPANION_KIND))) {
            val f = InventoryReceiptFixture()
            try {
                initialize(f)
                f.corruptProjection { connection ->
                    for (kind in missing) connection.usePrepared("DELETE FROM aggregate_state WHERE scope=? AND kind=?") {
                        it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, kind); it.step()
                    }
                }
                assertReaderAndCommandFail(f, InventoryReceiptStoreFailure.CURRENT_PAIR_REQUIRED)
            } finally { f.close() }
        }
        val neverInitialized = InventoryReceiptFixture()
        try {
            neverInitialized.installFreshSyntheticAuthority()
            assertReaderAndCommandFail(neverInitialized, InventoryReceiptStoreFailure.CURRENT_PAIR_REQUIRED)
            assertEquals(listOf(null, null), neverInitialized.state().rows)
        } finally { neverInitialized.close() }
    }

    @Test fun duplicateReceiptAndDuplicateUpstreamAllocationCannotCreateSecondStock() = runTest {
        val f = InventoryReceiptFixture()
        try {
            initialize(f)
            val input = fixtureReceipt(31_020, 0)
            receive(f, f.request(32_020, input))
            val before = f.state()
            val duplicateReceipt = assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(),
                f.request(32_021, input.copy(expectedRevision = Revision(1), origin = FixtureReceiptOrigin(testId(31_021), testId(31_022))))))
            assertFalse(duplicateReceipt.committed)
            assertEquals("fixture.inventory.duplicate-source", duplicateReceipt.code)
            val duplicateOriginRequest = f.request(32_022, input.copy(expectedRevision = Revision(1), receiptId = testId(31_023)))
            val duplicateOrigin = assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), duplicateOriginRequest))
            assertFalse(duplicateOrigin.committed)
            assertEquals("fixture.inventory.source-conflict", duplicateOrigin.code)
            assertEquals(before.rows, f.state().rows)
            assertEquals(before.claims, f.state().claims)
            assertEquals(Revision(1), f.read().snapshot.revision)
            val after = f.state()
            val replay = assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), duplicateOriginRequest))
            assertTrue(replay.replayed)
            assertEquals(duplicateOrigin.result, replay.result)
            assertEquals(after, f.state())
            assertEquals(listOf(4L, 4L, 4L, 4L, 2L, 3L), after.counts)
        } finally { f.close() }
    }

    @Test fun competingExpectedRevisionsSerializeToOneReceiptAndOneDurableStaleResult() = runTest {
        val f = InventoryReceiptFixture()
        try {
            initialize(f)
            val store = f.store()
            val principal = testPrincipal()
            val requests = listOf(f.request(32_030, fixtureReceipt(31_030, 0)), f.request(32_031, fixtureReceipt(31_031, 0)))
            val replies = coroutineScope { requests.map { async(Dispatchers.Default) { store.execute(principal, it) } }.awaitAll() }
                .map { assertIs<MutationReply.Recorded>(it) }
            assertEquals(1, replies.count { it.committed })
            assertEquals("fixture.inventory.stale-revision", replies.single { !it.committed }.code)
            assertEquals(Revision(1), f.read().snapshot.revision)
            assertEquals(1, f.read().companion.sources.size)
            assertEquals(listOf(3L, 3L, 3L, 3L, 2L, 3L), f.state().counts)
            val before = f.state()
            for (request in requests) assertTrue(assertIs<MutationReply.Recorded>(store.execute(principal, request)).replayed)
            assertEquals(before, f.state())
        } finally { f.close() }
    }

    @Test fun concurrentSameIdentityCommitsOnceAndChangedBodyIsAnIdentityConflict() = runTest {
        val f = InventoryReceiptFixture()
        try {
            initialize(f)
            val input = fixtureReceipt(31_032, 0)
            val request = f.request(32_032, input)
            val store = f.store()
            val principal = testPrincipal()
            val replies = coroutineScope { List(8) { async(Dispatchers.Default) { store.execute(principal, request) } }.awaitAll() }
                .map { assertIs<MutationReply.Recorded>(it) }
            assertTrue(replies.all { it.committed })
            assertEquals(1, replies.count { !it.replayed })
            assertEquals(1, f.receiver.evaluations)
            val before = f.state()
            assertIs<MutationReply.IdentityConflict>(store.execute(principal, f.request(32_032, input.copy(receiptId = testId(31_033)))))
            assertEquals(before, f.state())
            assertEquals(listOf(2L, 2L, 2L, 2L, 2L, 3L), before.counts)
        } finally { f.close() }
    }

    @Test fun everySemanticFaultReopensWithEitherNoEffectOrOneWholeReceiptAndRetriesSameId() = runTest {
        for (point in StoreFaultPoint.entries) {
            var f = InventoryReceiptFixture()
            try {
                initialize(f)
                val request = f.request(32_040, fixtureReceipt(31_040, 0, 7, 701))
                val before = f.state()
                var reached = false
                assertDeferred(f.store(fault = StoreFaultProbe { if (it == point) {
                    reached = true; throw IOException("Synthetic receipt uncertainty")
                } })
                    .execute(testPrincipal(), request), DeferredReason.STORAGE_UNCERTAIN)
                assertTrue(reached, "Receive must reach the requested $point boundary")
                if (point != StoreFaultPoint.AFTER_COMMIT) assertEquals(before, f.state(), point.name)
                val path = f.path
                f.close()
                f = InventoryReceiptFixture(path)
                if (point != StoreFaultPoint.AFTER_COMMIT) assertEquals(before, f.state(), point.name)
                val reply = receive(f, request, replayed = point == StoreFaultPoint.AFTER_COMMIT)
                assertCurrentReceipt(f, fixtureReceipt(31_040, 0, 7, 701), request, reply, 1)
                assertEquals(listOf(2L, 2L, 2L, 2L, 2L, 3L), f.state().counts)
                assertEquals(if (point == StoreFaultPoint.AFTER_COMMIT) 0 else 1, f.receiver.evaluations)
            } finally { f.close() }
        }
    }

    @Test fun actualOuterCommitFailureBeforeSQLiteCommitRollsBackBothRowsClaimsAndJournal() = runTest {
        exerciseActualCommitFailure(CommitFailureCut.BEFORE_SQLITE_COMMIT)
    }

    @Test fun actualOuterCommitFailureAfterSQLiteCommitRecoversOneOriginalReceipt() = runTest {
        exerciseActualCommitFailure(CommitFailureCut.AFTER_SQLITE_COMMIT)
    }

    @Test fun genuinelyReadmittedUnrelatedStockAliasBlocksNewEvaluationButNotAuthorizedOriginalReplay() = runTest {
        val f = InventoryReceiptFixture()
        try {
            initialize(f)
            val principal = testPrincipal()
            val request = f.request(32_050, fixtureReceipt(31_050, 0))
            val original = receive(f, request)
            val alias = RecoveryResourceKey(RecoveryResourceKind.STOCK, testId(31_059))
            val auth = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, TEST_ACTOR)
            val state = syntheticBarrierRecovery(listOf(RecoveryResourceSelector.Resource(alias)), setOf(auth, alias))
            assertEquals(ResourceAdmissionDecision.ADMITTED,
                RecoveryAdmissionPolicy.evaluate(state, TEST_SCOPE.branchId, f.receiver.lastFootprint))
            assertTrue(state.readmissions.any { it.resource == alias })
            assertFalse(alias in f.receiver.lastFootprint)
            f.base.installSyntheticBarrierRecovery(state)
            val before = f.state()
            val changes = f.totalChanges()
            val replay = receive(f, request, replayed = true)
            assertEquals(original.result, replay.result)
            assertEquals(original.sequence, replay.sequence)
            assertEquals(original.originalActorId, replay.originalActorId)
            assertEquals(1, f.receiver.prepares)
            assertEquals(1, f.receiver.evaluations)
            assertDeferred(f.store().execute(principal, f.request(32_051, fixtureReceipt(31_051, 1))),
                DeferredReason.RESOURCE_RECONCILIATION_REQUIRED)
            assertEquals(2, f.receiver.prepares)
            assertEquals(1, f.receiver.evaluations)
            assertEquals(before, f.state())
            assertEquals(changes, f.totalChanges())
            f.base.setAuth("disabled")
            val disabled = f.state()
            assertDeferred(f.store().execute(principal, request), DeferredReason.AUTHORIZATION_DENIED)
            assertEquals(disabled, f.state())
            assertEquals(2, f.receiver.prepares)
        } finally { f.close() }
    }

    @Test fun mixedCurrentGenerationsAndSchemasAndMalformedBodiesFailClosedWithoutNewWrites() = runTest {
        val mutations: List<Pair<String, (InventoryFixtureRow) -> InventoryFixtureRow>> = listOf(
            "wrong cut generation" to { row -> row.copy(revision = row.revision + 1) },
            "wrong cut schema" to { row -> row.copy(schema = "inventory.cut/v99") },
            "malformed cut" to { row -> row.withPayload(StoredBytes("{broken".encodeToByteArray())) },
            "wrong cut digest" to { row -> row.copy(digest = "0".repeat(64)) },
        )
        for ((label, mutation) in mutations) {
            val f = InventoryReceiptFixture()
            try {
                initialize(f)
                receive(f, f.request(32_060, fixtureReceipt(31_060, 0)))
                rewriteRow(f, InventoryReceiptStoreProfile.CUT_KIND, mutation)
                assertReaderAndCommandFail(f, label = label)
            } finally { f.close() }
        }
        for (kind in listOf(InventoryReceiptStoreProfile.CUT_KIND, InventoryReceiptStoreProfile.COMPANION_KIND)) {
            val f = InventoryReceiptFixture()
            try {
                initialize(f)
                receive(f, f.request(32_061, fixtureReceipt(31_061, 0)))
                val old = f.state().rows
                receive(f, f.request(32_062, fixtureReceipt(31_062, 1)))
                val current = f.state().rows
                f.restoreProjectionForLimitationTest(current.map { row -> if (row!!.kind == kind) old.single { it!!.kind == kind } else row })
                assertReaderAndCommandFail(f)
            } finally { f.close() }
        }
        val companion = InventoryReceiptFixture()
        try {
            initialize(companion)
            receive(companion, companion.request(32_063, fixtureReceipt(31_063, 0)))
            rewriteRow(companion, InventoryReceiptStoreProfile.COMPANION_KIND) { it.copy(schema = "inventory.companion/receipt-v99") }
            assertReaderAndCommandFail(companion)
        } finally { companion.close() }
    }

    @Test fun eitherMissingOrWrongReceiptAndUpstreamClaimOwnerPreventsReadAndNewMutation() = runTest {
        for (sourceClaim in listOf(true, false)) for (missing in listOf(true, false)) {
            val f = InventoryReceiptFixture()
            try {
                initialize(f)
                val input = fixtureReceipt(31_070, 0)
                receive(f, f.request(32_070, input))
                // The corrupted claim belongs to an OLDER source. Checking only
                // the last source/producer must not make this test pass.
                receive(f, f.request(32_071, fixtureReceipt(31_071, 1)))
                val claim = if (sourceClaim) InventoryReceiptStoreProfile.sourceClaim(input.receiptId)
                    else InventoryReceiptStoreProfile.originClaim(input.origin)
                f.corruptProjection { connection ->
                    if (missing) connection.usePrepared("DELETE FROM unique_claim WHERE scope=? AND namespace=? AND `key`=?") {
                        it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, claim.namespace); it.bindText(3, claim.key); it.step()
                    } else connection.usePrepared("UPDATE unique_claim SET ownerId=? WHERE scope=? AND namespace=? AND `key`=?") {
                        it.bindText(1, testId(31_079).value); it.bindText(2, TEST_SCOPE.storageKey())
                        it.bindText(3, claim.namespace); it.bindText(4, claim.key); it.step()
                    }
                }
                assertReaderAndCommandFail(f, InventoryReceiptStoreFailure.CLAIM_MISMATCH)
            } finally { f.close() }
        }
    }

    @Test fun actuallyAbsentOrOrphanRetainedProducerNeverBecomesInitializerAuthority() = runTest {
        for ((missingResult, missingJournal) in listOf(true to true, true to false, false to true)) {
            val f = InventoryReceiptFixture()
            try {
                initialize(f)
                val request = f.request(32_080, fixtureReceipt(31_080, 0))
                receive(f, request)
                f.removeRetainedForCorruptionTest(request.operationId, missingResult, missingJournal)
                assertReaderAndCommandFail(f, if (missingResult && missingJournal)
                    InventoryReceiptStoreFailure.RETAINED_PRODUCER_REQUIRED else null)
            } finally { f.close() }
        }
    }

    @Test fun uniqueEarlierOriginRewriteRemainsCanonicalButCannotReplaceTheExactRetainedCompanion() = runTest {
        val f = InventoryReceiptFixture()
        try {
            initialize(f)
            val first = fixtureReceipt(31_082, 0).copy(origin = FixtureReceiptOrigin(testId(31_182), testId(31_183)))
            receive(f, f.request(32_082, first))
            val second = fixtureReceipt(31_083, 1)
            receive(f, f.request(32_083, second))
            val before = f.read()
            val row = assertNotNull(f.state().rows.single { it?.kind == InventoryReceiptStoreProfile.COMPANION_KIND })
            val changed = StoredBytes(row.payload.copyBytes().decodeToString()
                .replaceFirst(first.origin.documentId.value, testId(31_184).value).encodeToByteArray())
            val decoded = InventoryReceiptCompanionCodec.decode(changed,
                InventoryReceiptCompanionBinding(TEST_SCOPE, TEST_SCOPE.branchId, Revision(2)))
            assertEquals(testId(31_184), decoded.sources.first().origin.documentId)
            assertEquals(second.origin, decoded.sources.last().origin)
            assertEquals(before.companion.producer, decoded.producer)
            assertEquals(before.companion.effects, decoded.effects)
            assertEquals(changed, InventoryReceiptCompanionCodec.encode(decoded))
            rewriteRow(f, InventoryReceiptStoreProfile.COMPANION_KIND) { it.withPayload(changed) }
            assertReaderAndCommandFail(f, InventoryReceiptStoreFailure.RETAINED_PRODUCER_REQUIRED)
        } finally { f.close() }
    }

    @Test fun sqliteCoercionsCannotMasqueradeAsAnExactCommittedReceiptAndClaimPair() = runTest {
        for (field in listOf("fractional revision", "text payload", "blob owner")) {
            val f = InventoryReceiptFixture()
            try {
                initialize(f)
                val input = fixtureReceipt(31_081, 0)
                receive(f, f.request(32_081, input))
                f.corruptProjection { connection ->
                    when (field) {
                        "fractional revision" -> connection.usePrepared(
                            "UPDATE aggregate_state SET revision=2.25 WHERE scope=? AND kind=? AND id=?") {
                            it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, InventoryReceiptStoreProfile.CUT_KIND)
                            it.bindText(3, TEST_SCOPE.branchId.value); it.step()
                        }
                        "text payload" -> connection.usePrepared(
                            "UPDATE aggregate_state SET payload=CAST(payload AS TEXT) WHERE scope=? AND kind=? AND id=?") {
                            it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, InventoryReceiptStoreProfile.CUT_KIND)
                            it.bindText(3, TEST_SCOPE.branchId.value); it.step()
                        }
                        else -> connection.usePrepared("UPDATE unique_claim SET ownerId=CAST(ownerId AS BLOB) WHERE scope=? AND namespace=? AND `key`=?") {
                            val claim = InventoryReceiptStoreProfile.sourceClaim(input.receiptId)
                            it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, claim.namespace); it.bindText(3, claim.key); it.step()
                        }
                    }
                    // SQLite INTEGER affinity normalizes2.0;2.25 really remains REAL.
                    if (field != "blob owner") connection.usePrepared(
                        "SELECT typeof(revision),typeof(payload) FROM aggregate_state WHERE scope=? AND kind=? AND id=?") {
                        it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, InventoryReceiptStoreProfile.CUT_KIND)
                        it.bindText(3, TEST_SCOPE.branchId.value); assertTrue(it.step())
                        assertEquals(if (field == "fractional revision") "real" else "integer", it.getText(0))
                        assertEquals(if (field == "text payload") "text" else "blob", it.getText(1))
                    } else connection.usePrepared("SELECT typeof(ownerId) FROM unique_claim WHERE scope=? AND namespace=? AND `key`=?") {
                        val claim = InventoryReceiptStoreProfile.sourceClaim(input.receiptId)
                        it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, claim.namespace); it.bindText(3, claim.key)
                        assertTrue(it.step()); assertEquals("blob", it.getText(0))
                    }
                }
                assertReaderAndCommandFail(f, label = field)
            } finally { f.close() }
        }
    }

    @Test fun exactSourceObjectIsRequiredEvenForIdenticalOrSelfConsistentSameRevisionCuts() = runTest {
        for (mode in listOf(FixtureReceiptSourceMode.IDENTICAL_DETACHED, FixtureReceiptSourceMode.ALTERNATE_SAME_REVISION)) {
            val f = InventoryReceiptFixture()
            try {
                initialize(f)
                receive(f, f.request(32_090, fixtureReceipt(31_090, 0)))
                f.receiver.sourceMode = mode
                val before = f.state()
                val changes = f.totalChanges()
                assertDeferred(f.store().execute(testPrincipal(), f.request(32_091, fixtureReceipt(31_091, 1))),
                    DeferredReason.STORAGE_UNCERTAIN)
                assertEquals(before, f.state(), mode.name)
                assertEquals(changes, f.totalChanges(), mode.name)
                assertEquals(2, f.receiver.evaluations)
                assertEquals(Revision(1), f.read().snapshot.revision)
            } finally { f.close() }
        }
    }

    @Test fun thirtyTwoSmallSamePoolReceiptsFitButThirtyThirdIsDurablyRejectedWithoutTruncation() = runTest {
        val f = InventoryReceiptFixture()
        try {
            initialize(f)
            repeat(32) { index -> receive(f, f.request(32_100 + index, fixtureReceipt(31_100 + index, index.toLong()))) }
            val cut = f.read()
            assertEquals(Revision(32), cut.snapshot.revision)
            assertEquals(Revision(33), cut.storageGeneration)
            assertEquals(32, cut.snapshot.sourceIds.size)
            assertEquals(32, cut.companion.sources.size)
            assertEquals(32, cut.snapshot.pools.single().value.facts.size)
            assertEquals(32L, cut.snapshot.positions.single().onHand.scaled)
            val before = f.state()
            assertTrue(before.rows.all { it!!.payload.size <= InventoryReceiptStoreProfile.MAX_ROW_BYTES })
            val request = f.request(32_132, fixtureReceipt(31_132, 32))
            val rejected = assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), request))
            assertFalse(rejected.committed)
            assertEquals("fixture.inventory.capacity", rejected.code)
            assertEquals(before.rows, f.state().rows)
            assertEquals(before.claims, f.state().claims)
            assertEquals(64, f.state().claims.size)
            assertEquals(34L, f.base.count("operation_result"))
            val after = f.state()
            assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), request)).replayed)
            assertEquals(after, f.state())
            // This fixture does NOT claim that 32 arbitrary lots/pools always fit a 64KiB row.
        } finally { f.close() }
    }

    @Test fun deliberatelyFaultyProducerWithUnexpectedWriteOrJobCannotPassTheCurrentCutLoader() = runTest {
        for (extraJob in listOf(true, false)) {
            val f = InventoryReceiptFixture()
            try {
                initialize(f)
                f.receiver.unexpectedJob = extraJob
                f.receiver.unexpectedWrite = !extraJob
                val request = f.request(32_140, fixtureReceipt(31_140, 0))
                // Negative-control producer is a real kernel commit, not forged retained evidence.
                receive(f, request)
                f.withView { view ->
                    val retained = assertNotNull(view.retainedCommand(request.operationId))
                    assertEquals(if (extraJob) 2 else 3, retained.aggregateWrites.size)
                    assertEquals(if (extraJob) 1 else 0, retained.jobs.size)
                }
                f.receiver.unexpectedJob = false
                f.receiver.unexpectedWrite = false
                assertReaderAndCommandFail(f, InventoryReceiptStoreFailure.RETAINED_PRODUCER_REQUIRED)
            } finally { f.close() }
        }
    }

    @Test fun canonicalButWrongProducerResultOrExtraRetainedClaimCannotPassCurrentLoader() = runTest {
        for (wrongResult in listOf(true, false)) {
            val f = InventoryReceiptFixture()
            try {
                initialize(f)
                f.receiver.wrongResult = wrongResult
                f.receiver.unexpectedClaim = !wrongResult
                val request = f.request(32_141, fixtureReceipt(31_141, 0))
                val committed = receive(f, request)
                // A wrong result is still valid canonical receipt-result data, not
                // simply a broken JSON body. Only the exact producer check refuses it.
                val decoded = InventoryReceiptResultCodec.decode(committed.result)
                assertEquals(Revision(1), decoded.inventoryRevision)
                assertEquals(if (wrongResult) OperationId(testId(29_995).value) else request.operationId, decoded.operationId)
                f.withView { view ->
                    val retained = assertNotNull(view.retainedCommand(request.operationId))
                    assertEquals(2, retained.aggregateWrites.size)
                    assertEquals(if (wrongResult) 2 else 3, retained.claimChanges.size)
                    assertTrue(retained.jobs.isEmpty())
                }
                f.receiver.wrongResult = false
                f.receiver.unexpectedClaim = false
                assertReaderAndCommandFail(f, InventoryReceiptStoreFailure.RETAINED_PRODUCER_REQUIRED)
            } finally { f.close() }
        }
    }

    @Test fun factoryRejectsEitherInventoryKindOrClaimNamespaceOverlapBeforeRegistration() = runTest {
        for (kindOverlap in listOf(true, false)) {
            val f = InventoryReceiptFixture()
            try {
                initialize(f)
                if (kindOverlap) f.expansionProbe.declaredKinds += InventoryReceiptStoreProfile.CUT_KIND
                else f.expansionProbe.declaredClaims += InventoryReceiptStoreProfile.SOURCE_NAMESPACE
                val before = f.state()
                assertFailsWith<IllegalArgumentException> { f.store(includeExpansionProbe = true) }
                assertEquals(before, f.state())
            } finally { f.close() }
        }
    }

    @Test fun postCaptureKindAndClaimDeclarationMutationCannotExpandCapturedRightsIndependently() = runTest {
        for (kindExpansion in listOf(true, false)) {
            val f = InventoryReceiptFixture()
            try {
                initialize(f)
                f.expansionProbe.attemptKindExpansion = kindExpansion
                f.expansionProbe.attemptClaimExpansion = !kindExpansion
                val store = f.store(includeExpansionProbe = true)
                if (kindExpansion) f.expansionProbe.declaredKinds += InventoryReceiptStoreProfile.CUT_KIND
                else f.expansionProbe.declaredClaims += InventoryReceiptStoreProfile.SOURCE_NAMESPACE
                val before = f.state()
                val changes = f.totalChanges()
                val request = MutationRequest(OperationId(testId(32_150).value), f.expansionProbe.key, StoredBytes(byteArrayOf(1)))
                assertDeferred(store.execute(testPrincipal(), request), DeferredReason.STORAGE_UNCERTAIN)
                assertEquals(before, f.state())
                assertEquals(changes, f.totalChanges())
                assertEquals(Revision(0), f.read().snapshot.revision)
            } finally { f.close() }
        }
    }

    @Test fun coherentHistoricalPairCanStillPassWithNewerJournalAndClaimsUnderSyntheticRuntime() = runTest {
        val f = InventoryReceiptFixture()
        try {
            initialize(f)
            val first = f.request(32_160, fixtureReceipt(31_160, 0))
            val firstReply = receive(f, first)
            val old = f.state()
            val secondInput = fixtureReceipt(31_161, 1)
            val second = f.request(32_161, secondInput)
            receive(f, second)
            val newer = f.state()
            assertEquals(4, newer.claims.size)
            f.restoreProjectionForLimitationTest(old.rows)
            val rolledBack = f.state()
            assertEquals(newer.branch, rolledBack.branch)
            assertEquals(newer.counts, rolledBack.counts)
            assertEquals(newer.claims, rolledBack.claims)
            val changes = f.totalChanges()
            val acceptedOld = f.read()
            assertEquals(Revision(1), acceptedOld.snapshot.revision)
            assertEquals(first.operationId, acceptedOld.companion.producer.operationId)
            f.withView { view ->
                assertEquals(firstReply.result, assertNotNull(view.retainedCommand(first.operationId)).result)
                assertNotNull(view.retainedCommand(second.operationId))
                assertEquals(DomainId(second.operationId.value), view.claim(InventoryReceiptStoreProfile.sourceClaim(secondInput.receiptId)))
                assertEquals(secondInput.receiptId, view.claim(InventoryReceiptStoreProfile.originClaim(secondInput.origin)))
            }
            assertEquals(rolledBack, f.state())
            assertEquals(changes, f.totalChanges())
            // EXPECTED LIMITATION, not freshness detection. Retained point evidence and
            // known claims cannot enumerate an omitted tail. No new mutation is attempted.
        } finally { f.close() }
    }
}

private suspend fun initialize(f: InventoryReceiptFixture): MutationReply.Recorded {
    f.installFreshSyntheticAuthority()
    return assertIs<MutationReply.Recorded>(f.store(initializing = true).execute(testPrincipal(), f.genesisRequest())).also {
        assertTrue(it.committed)
        assertFalse(it.replayed)
        assertEquals(Revision(0), f.read().snapshot.revision)
    }
}

private suspend fun receive(f: InventoryReceiptFixture, request: MutationRequest, replayed: Boolean = false): MutationReply.Recorded =
    assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), request)).also {
        assertTrue(it.committed)
        assertEquals(replayed, it.replayed)
    }

private fun assertDeferred(reply: MutationReply, reason: DeferredReason) {
    assertEquals(reason, assertIs<MutationReply.Deferred>(reply).reason)
}

private suspend fun assertCurrentReceipt(f: InventoryReceiptFixture, input: InventoryFixtureReceiveRequest,
    request: MutationRequest, reply: MutationReply.Recorded, expectedRevision: Long): InventoryReceiptReadCut {
    val current = f.read()
    assertEquals(Revision(expectedRevision), current.snapshot.revision)
    assertEquals(Revision(expectedRevision + 1), current.storageGeneration)
    assertEquals(request.operationId, current.companion.producer.operationId)
    assertEquals(TEST_ACTOR, current.companion.producer.originalActorId)
    assertEquals(input.receiptId, current.companion.sources.last().receiptId)
    assertEquals(input.origin, current.companion.sources.last().origin)
    assertEquals(2, current.companion.effects.size)
    val valuation = assertIs<InventoryReceiptPostedEffect.Valuation>(current.companion.effects[0])
    val movement = assertIs<InventoryReceiptPostedEffect.Movement>(current.companion.effects[1])
    assertEquals(ReceiptValueOrigin.PURCHASE, valuation.origin)
    assertEquals(input.receiptId, valuation.receiptId)
    assertEquals(input.quantity, valuation.quantityDelta)
    assertEquals(input.originalValue, valuation.carryingValueDelta)
    assertEquals(StockMovementReason.RECEIPT, movement.reason)
    assertEquals(input.receiptId, movement.sourceId)
    assertEquals(input.quantity, movement.quantityDelta)
    assertEquals(input.toReceipt(TEST_SCOPE).position, movement.position)
    assertNull(movement.sourceAllocationId)
    val fact = assertIs<ValuationFact.Receipt>(current.snapshot.pools.single { it.key == valuation.pool }.value.facts.last())
    assertEquals(input.receiptId, fact.factId)
    assertEquals(input.quantity, fact.quantity)
    assertEquals(input.originalValue, fact.originalValue)
    val decoded = InventoryReceiptResultCodec.decode(reply.result)
    assertEquals(InventoryReceiptResultKind.RECEIVED, decoded.kind)
    assertEquals(TEST_SCOPE, decoded.scope)
    assertEquals(request.operationId, decoded.operationId)
    assertEquals(current.snapshot.revision, decoded.inventoryRevision)
    assertEquals(input.receiptId, decoded.receiptId)
    f.withView { view ->
        val retained = assertNotNull(view.retainedCommand(request.operationId))
        assertEquals(reply.result, retained.result)
        assertEquals(reply.sequence, retained.sequence)
        assertEquals(2, retained.aggregateWrites.size)
        assertEquals(2, retained.claimChanges.size)
        assertTrue(retained.jobs.isEmpty())
        for (row in retained.aggregateWrites) {
            val live = assertNotNull(view.aggregate(row.key))
            assertEquals(live.revision, row.storageGeneration)
            assertEquals(live.schema, row.schema)
            assertEquals(live.payload, row.payload)
            assertEquals(live.digest, row.payloadDigest)
        }
        val liveCut = assertNotNull(view.aggregate(InventoryReceiptStoreProfile.cutKey(TEST_SCOPE.branchId)))
        val liveCompanion = assertNotNull(view.aggregate(InventoryReceiptStoreProfile.companionKey(TEST_SCOPE.branchId)))
        assertEquals(decoded.cutDigest, liveCut.digest)
        assertEquals(decoded.companionDigest, liveCompanion.digest)
        for (source in current.companion.sources) {
            assertEquals(DomainId(source.producingOperationId.value), view.claim(InventoryReceiptStoreProfile.sourceClaim(source.receiptId)))
            assertEquals(source.receiptId, view.claim(InventoryReceiptStoreProfile.originClaim(source.origin)))
        }
    }
    return current
}

private suspend fun assertReaderAndCommandFail(f: InventoryReceiptFixture,
    expected: InventoryReceiptStoreFailure? = null, label: String = "corrupt fixture") {
    val before = f.state()
    val changes = f.totalChanges()
    val evaluations = f.receiver.evaluations
    val failure = assertFailsWith<Exception>(label) { f.read() }
    if (expected != null) assertEquals(expected, assertIs<InventoryReceiptStoreRejected>(failure).failure, label)
    assertDeferred(f.store().execute(testPrincipal(), f.request(32_199, fixtureReceipt(31_199, 1))), DeferredReason.STORAGE_UNCERTAIN)
    assertEquals(evaluations, f.receiver.evaluations, label)
    assertEquals(before, f.state(), label)
    assertEquals(changes, f.totalChanges(), label)
}

private fun InventoryFixtureRow.withPayload(value: StoredBytes): InventoryFixtureRow =
    copy(payload = value, digest = sha256(value.copyBytes()).value)

private suspend fun rewriteRow(f: InventoryReceiptFixture, kind: String, transform: (InventoryFixtureRow) -> InventoryFixtureRow) {
    val before = assertNotNull(f.state().rows.single { it?.kind == kind })
    val after = transform(before)
    f.corruptProjection { connection -> updateRow(connection, after) }
}

private suspend fun updateRow(connection: PooledConnection, row: InventoryFixtureRow) {
    connection.usePrepared("UPDATE aggregate_state SET revision=?,schema=?,payloadHash=?,payload=? WHERE scope=? AND kind=? AND id=?") {
        it.bindLong(1, row.revision); it.bindText(2, row.schema); it.bindText(3, row.digest); it.bindBlob(4, row.payload.copyBytes())
        it.bindText(5, TEST_SCOPE.storageKey()); it.bindText(6, row.kind); it.bindText(7, row.id); it.step()
    }
}

private suspend fun exerciseActualCommitFailure(cut: CommitFailureCut) {
    val seed = InventoryReceiptFixture()
    val before = try { initialize(seed); seed.state() } finally { seed.close() }
    val request = seed.request(32_045, fixtureReceipt(31_045, 0, 9, 903))
    val driver = CommitFaultDriver(cut)
    val uncertain = InventoryReceiptFixture(seed.path, driver)
    try {
        uncertain.database.useWriterConnection { requireDurableWriter(it) }
        assertDeferred(uncertain.store(fault = StoreFaultProbe { if (it == StoreFaultPoint.BEFORE_COMMIT) driver.arm() })
            .execute(testPrincipal(), request), DeferredReason.STORAGE_UNCERTAIN)
        driver.assertInjectedAtExpectedBoundary()
        uncertain.database.useWriterConnection { connection ->
            assertFalse(connection.inTransaction())
            requireDurableWriter(connection)
        }
        if (cut == CommitFailureCut.BEFORE_SQLITE_COMMIT) assertEquals(before, uncertain.state())
        else assertEquals(Revision(1), uncertain.read().snapshot.revision)
    } finally { uncertain.close() }
    val reopened = InventoryReceiptFixture(seed.path)
    try {
        if (cut == CommitFailureCut.BEFORE_SQLITE_COMMIT) assertEquals(before, reopened.state())
        val reply = receive(reopened, request, replayed = cut == CommitFailureCut.AFTER_SQLITE_COMMIT)
        assertCurrentReceipt(reopened, fixtureReceipt(31_045, 0, 9, 903), request, reply, 1)
        assertEquals(listOf(2L, 2L, 2L, 2L, 2L, 3L), reopened.state().counts)
        assertEquals(if (cut == CommitFailureCut.AFTER_SQLITE_COMMIT) 0 else 1, reopened.receiver.evaluations)
    } finally { reopened.close() }
}
