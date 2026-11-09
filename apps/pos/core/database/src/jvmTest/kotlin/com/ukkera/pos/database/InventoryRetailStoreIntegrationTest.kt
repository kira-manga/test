package com.ukkera.pos.database

import androidx.room3.useWriterConnection
import com.ukkera.pos.inventory.InventoryPostingEffect
import com.ukkera.pos.inventory.InventorySnapshotCodec
import com.ukkera.pos.inventory.store.FixtureReceiptOrigin
import com.ukkera.pos.inventory.store.InventoryReceiptRepository
import com.ukkera.pos.inventory.store.InventoryReceiptStoreRejected
import com.ukkera.pos.inventory.store.InventoryRetailCommandCodec
import com.ukkera.pos.inventory.store.InventoryRetailCompanionCodec
import com.ukkera.pos.inventory.store.InventoryRetailFixtureResultCodec
import com.ukkera.pos.inventory.store.InventoryRetailFixtureResultKind
import com.ukkera.pos.inventory.store.InventoryRetailObservedFacts
import com.ukkera.pos.inventory.store.InventoryRetailRepository
import com.ukkera.pos.inventory.store.InventoryRetailStoreFailure
import com.ukkera.pos.inventory.store.InventoryRetailStoreProfile
import com.ukkera.pos.inventory.store.InventoryRetailStoreRejected
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Actual Room commits through the public A2 APIs. Every identity, purchase and
 * runtime/custody adapter is synthetic. No composite sale producer is admitted.
 * Faulty/unsupported producer controls establish refusal, never supported setup.
 */
class InventoryRetailStoreIntegrationTest {
    @Test fun actualGenesisBatchReceiptsReopenAndUnrelatedTailRetainTheExactInventoryProducer() = runTest {
        var f = InventoryRetailFixture()
        try {
            val genesis = initializeRetail(f)
            val empty = f.read()
            val genesisResult = InventoryRetailFixtureResultCodec.decode(genesis.result)
            assertEquals(InventoryRetailFixtureResultKind.GENESIS, genesisResult.kind)
            assertEquals(Revision(0), empty.snapshot.revision)
            assertEquals(Revision(1), empty.storageGeneration)
            assertTrue(empty.companion.receipts.isEmpty() && empty.companion.issueSources.isEmpty())
            assertNull(empty.companion.previous)
            assertNull(empty.companion.latestBatch)
            assertTrue(genesisResult.receiptIds.isEmpty())

            val a = fixtureReceipt(203_001, 0, 2, 201)
            val b = fixtureReceipt(203_002, 0, 3, 299)
            val request = f.request(204_001, a, b)
            val reply = receiveRetail(f, request)
            val current = assertRetailPair(f, request, reply, 1)
            assertEquals(listOf(a.receiptId, b.receiptId), current.snapshot.sourceIds)
            assertEquals(listOf(request.operationId), current.snapshot.operationIds)
            assertEquals(listOf(request.operationId, request.operationId), current.companion.receipts.map { it.producingOperationId })
            assertEquals(5L, current.snapshot.positions.single().onHand.scaled)
            assertEquals(500L, current.snapshot.pools.single().value.carryingValue.minor)
            val effects = assertNotNull(current.companion.latestBatch).effects
            assertEquals(4, effects.size)
            val first = assertIs<InventoryPostingEffect.Valuation>(effects[0])
            val second = assertIs<InventoryPostingEffect.Valuation>(effects[2])
            assertEquals(first.resultingPoolRevision, second.requiredPoolRevision)
            assertEquals(empty.cutDigest, current.companion.previous!!.cutDigest)
            assertEquals(empty.companionDigest, current.companion.previous!!.companionDigest)
            for (id in listOf(a.receiptId, a.origin.documentId, a.origin.allocationId, b.receiptId, b.origin.allocationId)) {
                assertTrue(RecoveryResourceKey(RecoveryResourceKind.STOCK, id) in f.receiver.lastFootprint)
            }

            val beforeReopen = f.state()
            val path = f.path
            f.close()
            f = InventoryRetailFixture(path)
            assertEquals(beforeReopen, f.state())
            assertEquals(current.companionBytes, f.read().companionBytes)
            assertFailsWith<IllegalArgumentException> { f.store(initializing = true) }
            val counter = f.base.request(204_002, CounterInput(id = testId(204_003)))
            assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), counter)).committed)
            assertEquals(request.operationId, f.read().retained.operationId)
            assertEquals(3L, f.state().branch.sequence)
            val beforeReads = f.state()
            val changes = f.totalChanges()
            repeat(3) { assertEquals(current.companionBytes, f.read().companionBytes) }
            assertEquals(beforeReads, f.state())
            assertEquals(changes, f.totalChanges())

            val c = fixtureReceipt(203_003, 1, 7, 701).copy(warehouseId = testId(203_004), lotId = testId(203_005),
                expiresAtEpochMillis = f.base.clockValue.value.toEpochMilliseconds() + 60_000)
            val next = f.request(204_004, c)
            assertRetailPair(f, next, receiveRetail(f, next), 2)
            assertEquals(2, f.read().snapshot.positions.size)
            assertEquals(listOf(a.receiptId, b.receiptId, c.receiptId), f.read().snapshot.sourceIds)
            assertEquals(listOf(4L, 4L, 4L, 5L, 6L, 4L), f.state().counts)
        } finally { f.close() }
    }

    @Test fun explicitFreshCeremonyAndExactGenesisIdentityAreRequired() = runTest {
        val f = InventoryRetailFixture()
        try {
            assertFailsWith<IllegalArgumentException> { f.store(initializing = true) }
            f.installFreshSyntheticAuthority()
            val before = f.state()
            assertRetailDeferred(f.store().execute(testPrincipal(), f.genesisRequest()), DeferredReason.UNKNOWN_COMMAND)
            assertRetailDeferred(f.store(initializing = true).execute(testPrincipal(),
                f.genesisRequest(OperationId(testId(204_010).value))), DeferredReason.STORAGE_UNCERTAIN)
            assertEquals(before, f.state())
            assertTrue(assertIs<MutationReply.Recorded>(f.store(initializing = true).execute(testPrincipal(), f.genesisRequest())).committed)
            assertEquals(Revision(0), f.read().snapshot.revision)
            assertFailsWith<IllegalStateException> { f.installFreshSyntheticAuthority() }
        } finally { f.close() }
    }

    @Test fun everySemanticCommitFaultRetainsEitherNoBatchOrExactlyOneCompleteBatchAfterReopen() = runTest {
        for (point in StoreFaultPoint.entries) {
            var f = InventoryRetailFixture()
            try {
                initializeRetail(f)
                val request = f.request(204_020, fixtureReceipt(203_020, 0, 2, 201), fixtureReceipt(203_021, 0, 3, 302))
                val before = f.state()
                var reached = false
                assertRetailDeferred(f.store(fault = StoreFaultProbe {
                    if (it == point) { reached = true; throw IOException("Synthetic retail commit uncertainty") }
                }).execute(testPrincipal(), request), DeferredReason.STORAGE_UNCERTAIN)
                assertTrue(reached, point.name)
                if (point != StoreFaultPoint.AFTER_COMMIT) assertEquals(before, f.state(), point.name)
                val path = f.path
                f.close()
                f = InventoryRetailFixture(path)
                if (point != StoreFaultPoint.AFTER_COMMIT) assertEquals(before, f.state(), point.name)
                val reply = receiveRetail(f, request, replayed = point == StoreFaultPoint.AFTER_COMMIT)
                assertRetailPair(f, request, reply, 1)
                assertEquals(listOf(2L, 2L, 2L, 2L, 4L, 3L), f.state().counts)
                assertEquals(if (point == StoreFaultPoint.AFTER_COMMIT) 0 else 1, f.receiver.evaluations)
            } finally { f.close() }
        }
    }

    @Test fun actualOuterCommitFailureBeforeSqliteCommitRollsBackTheEntireBatch() = runTest {
        exerciseRetailCommitFailure(CommitFailureCut.BEFORE_SQLITE_COMMIT)
    }

    @Test fun actualOuterCommitFailureAfterSqliteCommitReplaysTheOriginalBatch() = runTest {
        exerciseRetailCommitFailure(CommitFailureCut.AFTER_SQLITE_COMMIT)
    }

    @Test fun concurrentSameIdentityCommitsOnceAndChangedCanonicalBatchConflicts() = runTest {
        val f = InventoryRetailFixture()
        try {
            initializeRetail(f)
            val a = fixtureReceipt(203_030, 0)
            val b = fixtureReceipt(203_031, 0)
            val request = f.request(204_030, a, b)
            val store = f.store()
            val principal = testPrincipal()
            val replies = coroutineScope { List(6) { async(Dispatchers.Default) { store.execute(principal, request) } }.awaitAll() }
                .map { assertIs<MutationReply.Recorded>(it) }
            assertTrue(replies.all { it.committed })
            assertEquals(1, replies.count { !it.replayed })
            assertEquals(1, f.receiver.evaluations)
            val before = f.state()
            assertIs<MutationReply.IdentityConflict>(store.execute(principal, f.request(204_030, b, a)))
            assertEquals(before, f.state())
            assertEquals(4, before.claims.size)
            assertEquals(Revision(1), f.read().snapshot.revision)
        } finally { f.close() }
    }

    @Test fun competingExpectedRevisionsProduceOneWholeBatchAndOneDurableStaleResult() = runTest {
        val f = InventoryRetailFixture()
        try {
            initializeRetail(f)
            val requests = listOf(f.request(204_040, fixtureReceipt(203_040, 0), fixtureReceipt(203_041, 0)),
                f.request(204_041, fixtureReceipt(203_042, 0), fixtureReceipt(203_043, 0)))
            val store = f.store()
            val principal = testPrincipal()
            val replies = coroutineScope { requests.map { async(Dispatchers.Default) { store.execute(principal, it) } }.awaitAll() }
                .map { assertIs<MutationReply.Recorded>(it) }
            assertEquals(1, replies.count { it.committed })
            assertEquals("fixture.inventory.retail.stale-revision", replies.single { !it.committed }.code)
            assertEquals(2, f.read().companion.receipts.size)
            val before = f.state()
            requests.forEach { assertTrue(assertIs<MutationReply.Recorded>(store.execute(principal, it)).replayed) }
            assertEquals(before, f.state())
            assertEquals(listOf(3L, 3L, 3L, 3L, 4L, 3L), before.counts)
        } finally { f.close() }
    }

    @Test fun duplicateReceiptOrOlderUpstreamOriginCannotCreateMoreStock() = runTest {
        val f = InventoryRetailFixture()
        try {
            initializeRetail(f)
            val a = fixtureReceipt(203_050, 0)
            receiveRetail(f, f.request(204_050, a))
            receiveRetail(f, f.request(204_051, fixtureReceipt(203_051, 1)))
            val before = f.state()
            val duplicateId = f.request(204_052, a.copy(expectedRevision = Revision(2),
                origin = FixtureReceiptOrigin(testId(203_052), testId(203_053))))
            val duplicateOrigin = f.request(204_053, a.copy(expectedRevision = Revision(2), receiptId = testId(203_054)))
            val idReply = assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), duplicateId))
            val originReply = assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), duplicateOrigin))
            assertFalse(idReply.committed)
            assertFalse(originReply.committed)
            assertEquals("fixture.inventory.retail.duplicate-source", idReply.code)
            assertEquals("fixture.inventory.retail.source-conflict", originReply.code)
            assertEquals(before.rows, f.state().rows)
            assertEquals(before.claims, f.state().claims)
            assertEquals(Revision(2), f.read().snapshot.revision)
        } finally { f.close() }
    }

    @Test fun currentPairAbsenceNeverInitializesOnReadOrReceive() = runTest {
        for (missing in listOf(setOf(InventoryRetailStoreProfile.CUT_KIND), setOf(InventoryRetailStoreProfile.COMPANION_KIND),
            setOf(InventoryRetailStoreProfile.CUT_KIND, InventoryRetailStoreProfile.COMPANION_KIND))) {
            val f = InventoryRetailFixture()
            try {
                initializeRetail(f)
                f.corruptProjection { connection ->
                    for (kind in missing) connection.usePrepared("DELETE FROM aggregate_state WHERE scope=? AND kind=?") {
                        it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, kind); it.step()
                    }
                }
                assertRetailReadAndNextFail(f, InventoryRetailStoreFailure.CURRENT_PAIR_REQUIRED)
            } finally { f.close() }
        }
        val f = InventoryRetailFixture()
        try {
            f.installFreshSyntheticAuthority()
            assertRetailReadAndNextFail(f, InventoryRetailStoreFailure.CURRENT_PAIR_REQUIRED)
            assertEquals(listOf(null, null), f.state().rows)
        } finally { f.close() }
    }

    @Test fun legacyReceiptProfileIsNotAutomaticallyUpgradedOrAcceptedByRetail() = runTest {
        val legacy = InventoryReceiptFixture()
        val path = legacy.path
        try {
            legacy.installFreshSyntheticAuthority()
            assertTrue(assertIs<MutationReply.Recorded>(legacy.store(initializing = true).execute(testPrincipal(), legacy.genesisRequest())).committed)
            assertTrue(assertIs<MutationReply.Recorded>(legacy.store().execute(testPrincipal(),
                legacy.request(204_060, fixtureReceipt(203_060, 0)))).committed)
            assertEquals(Revision(1), legacy.read().snapshot.revision)
        } finally { legacy.close() }
        val retail = InventoryRetailFixture(path)
        try {
            assertRetailReadAndNextFail(retail, InventoryRetailStoreFailure.BINDING_MISMATCH)
            assertFailsWith<IllegalArgumentException> { retail.store(initializing = true) }
            retail.withView { assertEquals(Revision(1), InventoryReceiptRepository.read(it).snapshot.revision) }
        } finally { retail.close() }
        val newProfile = InventoryRetailFixture()
        try {
            initializeRetail(newProfile)
            newProfile.withView { assertFailsWith<InventoryReceiptStoreRejected> { InventoryReceiptRepository.read(it) } }
            assertEquals(Revision(0), newProfile.read().snapshot.revision)
        } finally { newProfile.close() }
    }

    @Test fun olderReceiptSourceAndNaturalOriginOwnersAreAllRechecked() = runTest {
        for (case in listOf("source-owner", "origin-owner", "missing-origin")) {
            val f = InventoryRetailFixture()
            try {
                initializeRetail(f)
                val first = fixtureReceipt(203_070, 0)
                receiveRetail(f, f.request(204_070, first))
                receiveRetail(f, f.request(204_071, fixtureReceipt(203_071, 1)))
                val old = f.read()
                val key = if (case == "source-owner") InventoryRetailStoreProfile.sourceClaim(first.receiptId)
                    else InventoryRetailStoreProfile.originClaim(first.origin)
                f.corruptProjection { connection ->
                    if (case == "missing-origin") {
                        connection.usePrepared("DELETE FROM unique_claim WHERE scope=? AND namespace=? AND `key`=?") {
                            it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, key.namespace); it.bindText(3, key.key); it.step()
                        }
                    } else connection.usePrepared("UPDATE unique_claim SET ownerId=? WHERE scope=? AND namespace=? AND `key`=?") {
                            it.bindText(1, testId(203_072).value); it.bindText(2, TEST_SCOPE.storageKey())
                            it.bindText(3, key.namespace); it.bindText(4, key.key); it.step()
                        }
                }
                assertEquals(old.companionBytes, f.state().rows.single { it?.kind == InventoryRetailStoreProfile.COMPANION_KIND }!!.payload)
                assertRetailReadAndNextFail(f, InventoryRetailStoreFailure.CLAIM_MISMATCH)
            } finally { f.close() }
        }
    }

    @Test fun observationsDoNotApproveARealProducerWithExtraClaimJobOrWrongCanonicalResult() = runTest {
        for (fault in listOf(RetailProducerFault.NONE, RetailProducerFault.EXTRA_CLAIM,
            RetailProducerFault.EXTRA_JOB, RetailProducerFault.WRONG_RESULT)) {
            val f = InventoryRetailFixture()
            try {
                initializeRetail(f)
                f.receiver.fault = fault
                val request = f.request(204_080, fixtureReceipt(203_080, 0))
                val reply = receiveRetail(f, request)
                val observed = f.observe() // Deliberately not complete-producer acceptance.
                assertEquals(request.operationId, observed.retained.operationId)
                assertEquals(Revision(1), observed.snapshot.revision)
                val decoded = InventoryRetailFixtureResultCodec.decode(reply.result)
                assertEquals(if (fault == RetailProducerFault.WRONG_RESULT) OperationId(testId(202_991).value) else request.operationId,
                    decoded.operationId)
                assertEquals(2, observed.retained.aggregateWrites.size)
                assertEquals(if (fault == RetailProducerFault.EXTRA_CLAIM) 3 else 2, observed.retained.claimChanges.size)
                assertEquals(if (fault == RetailProducerFault.EXTRA_JOB) 1 else 0, observed.retained.jobs.size)
                f.receiver.fault = RetailProducerFault.NONE
                if (fault == RetailProducerFault.NONE) assertEquals(observed.companionBytes, f.read().companionBytes)
                else assertRetailReadAndNextFail(f, InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED)
            } finally { f.close() }
        }
    }

    @Test fun missingOrWrongCurrentSourceClaimAndReleasingAnOlderSourceRejectEvenObservation() = runTest {
        for (fault in listOf(RetailProducerFault.OMIT_SOURCE_CLAIM, RetailProducerFault.WRONG_SOURCE_OWNER,
            RetailProducerFault.RELEASE_OLD_SOURCE)) {
            val f = InventoryRetailFixture()
            try {
                initializeRetail(f)
                receiveRetail(f, f.request(204_090, fixtureReceipt(203_090, 0)))
                assertEquals(Revision(1), f.read().snapshot.revision)
                f.receiver.fault = fault
                receiveRetail(f, f.request(204_091, fixtureReceipt(203_091, 1)))
                assertEquals(InventoryRetailStoreFailure.CLAIM_MISMATCH,
                    assertFailsWith<InventoryRetailStoreRejected> { f.observe() }.failure)
                f.receiver.fault = RetailProducerFault.NONE
                assertRetailReadAndNextFail(f, InventoryRetailStoreFailure.CLAIM_MISMATCH)
            } finally { f.close() }
        }
    }

    @Test fun missingOrExtraInventoryWriteCannotMasqueradeAsAnObservedCurrentPair() = runTest {
        for (fault in listOf(RetailProducerFault.OMIT_CUT, RetailProducerFault.OMIT_COMPANION, RetailProducerFault.EXTRA_WRITE)) {
            val f = InventoryRetailFixture()
            try {
                initializeRetail(f)
                f.receiver.fault = fault
                val request = f.request(204_100, fixtureReceipt(203_100, 0))
                receiveRetail(f, request)
                f.withView { assertEquals(if (fault == RetailProducerFault.EXTRA_WRITE) 3 else 1,
                    assertNotNull(it.retainedCommand(request.operationId)).aggregateWrites.size) }
                val expected = if (fault == RetailProducerFault.EXTRA_WRITE) InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED
                    else InventoryRetailStoreFailure.BINDING_MISMATCH
                assertEquals(expected, assertFailsWith<InventoryRetailStoreRejected> { f.observe() }.failure)
                f.receiver.fault = RetailProducerFault.NONE
                assertRetailReadAndNextFail(f, expected)
            } finally { f.close() }
        }
    }

    @Test fun unrecognizedCommittedProducerCanBeObservedButNeverAcceptedAsAReceiptProducer() = runTest {
        val f = InventoryRetailFixture()
        try {
            initializeRetail(f)
            val ordinary = f.request(204_110, fixtureReceipt(203_110, 0))
            val unsupported = MutationRequest(ordinary.operationId, RETAIL_UNSUPPORTED_COMMAND, ordinary.payload)
            val before = f.state()
            assertRetailDeferred(f.store().execute(testPrincipal(), unsupported), DeferredReason.UNKNOWN_COMMAND)
            assertEquals(before, f.state())
            // Deliberately faulty control factory, not accepted retail/sales composition.
            assertTrue(assertIs<MutationReply.Recorded>(f.unsupportedProducerControlStore().execute(testPrincipal(), unsupported)).committed)
            val observed = f.observe()
            assertEquals(RETAIL_UNSUPPORTED_COMMAND, observed.retained.command)
            assertEquals(RETAIL_UNSUPPORTED_COMMAND, observed.companion.producer.command)
            assertEquals(2, observed.retained.aggregateWrites.size)
            assertEquals(InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED,
                assertFailsWith<InventoryRetailStoreRejected> { InventoryRetailRepository.requireFixtureProducer(observed) }.failure)
            assertRetailReadAndNextFail(f, InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED)
        } finally { f.close() }
    }

    @Test fun missingOrOrphanedSelectedRetainedProducerNeverBecomesCurrentAuthority() = runTest {
        for ((missingResult, missingJournal) in listOf(true to true, true to false, false to true)) {
            val f = InventoryRetailFixture()
            try {
                initializeRetail(f)
                val request = f.request(204_120, fixtureReceipt(203_120, 0))
                receiveRetail(f, request)
                assertEquals(request.operationId, f.read().retained.operationId)
                f.removeRetainedForCorruptionTest(request.operationId, missingResult, missingJournal)
                assertRetailReadAndNextFail(f, if (missingResult && missingJournal) InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED
                    else InventoryRetailStoreFailure.INCONSISTENT_STATE)
            } finally { f.close() }
        }
    }

    @Test fun identicalDetachedAndAlternativeSameRevisionPlansFailWithoutWrites() = runTest {
        for (mode in listOf(FixtureReceiptSourceMode.IDENTICAL_DETACHED, FixtureReceiptSourceMode.ALTERNATE_SAME_REVISION)) {
            val f = InventoryRetailFixture()
            try {
                initializeRetail(f)
                receiveRetail(f, f.request(204_130, fixtureReceipt(203_130, 0)))
                f.receiver.sourceMode = mode
                val before = f.state()
                val changes = f.totalChanges()
                assertRetailDeferred(f.store().execute(testPrincipal(), f.request(204_131, fixtureReceipt(203_131, 1))),
                    DeferredReason.STORAGE_UNCERTAIN)
                assertEquals(before, f.state())
                assertEquals(changes, f.totalChanges())
                assertEquals(2, f.receiver.evaluations)
                assertEquals(Revision(1), f.read().snapshot.revision)
            } finally { f.close() }
        }
    }

    @Test fun frozenFactoryRejectsLegacyProducerAndEitherReservedNamespaceOverlap() = runTest {
        val f = InventoryRetailFixture()
        try {
            initializeRetail(f)
            assertFailsWith<IllegalArgumentException> { f.store(includeLegacyProducer = true) }
            for (kind in listOf(true, false)) {
                f.expansionProbe.declaredKinds.remove(InventoryRetailStoreProfile.CUT_KIND)
                f.expansionProbe.declaredClaims.remove(InventoryRetailStoreProfile.SOURCE_NAMESPACE)
                if (kind) f.expansionProbe.declaredKinds += InventoryRetailStoreProfile.CUT_KIND
                else f.expansionProbe.declaredClaims += InventoryRetailStoreProfile.SOURCE_NAMESPACE
                val before = f.state()
                assertFailsWith<IllegalArgumentException> { f.store(includeExpansionProbe = true) }
                assertEquals(before, f.state())
            }
        } finally { f.close() }
    }

    @Test fun postCaptureDeclarationExpansionCannotGainInventoryWriteOrClaimRights() = runTest {
        for (kind in listOf(true, false)) {
            val f = InventoryRetailFixture()
            try {
                initializeRetail(f)
                f.expansionProbe.attemptKindExpansion = kind
                f.expansionProbe.attemptClaimExpansion = !kind
                val store = f.store(includeExpansionProbe = true)
                if (kind) f.expansionProbe.declaredKinds += InventoryRetailStoreProfile.CUT_KIND
                else f.expansionProbe.declaredClaims += InventoryRetailStoreProfile.SOURCE_NAMESPACE
                val before = f.state()
                val changes = f.totalChanges()
                assertRetailDeferred(store.execute(testPrincipal(), MutationRequest(OperationId(testId(204_140).value),
                    f.expansionProbe.key, StoredBytes(byteArrayOf(1)))), DeferredReason.STORAGE_UNCERTAIN)
                assertEquals(before, f.state())
                assertEquals(changes, f.totalChanges())
                assertEquals(Revision(0), f.read().snapshot.revision)
            } finally { f.close() }
        }
    }

    @Test fun stockKindBarrierBlocksFreshReceiveButCurrentAuthorizationStillControlsOriginalReplay() = runTest {
        val f = InventoryRetailFixture()
        try {
            initializeRetail(f)
            val request = f.request(204_150, fixtureReceipt(203_150, 0))
            val original = receiveRetail(f, request)
            val alias = RecoveryResourceKey(RecoveryResourceKind.STOCK, testId(203_151))
            val auth = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, TEST_ACTOR)
            val recovery = syntheticBarrierRecovery(listOf(RecoveryResourceSelector.Resource(alias)), setOf(alias, auth))
            assertEquals(ResourceAdmissionDecision.ADMITTED, RecoveryAdmissionPolicy.evaluate(recovery, TEST_SCOPE.branchId, f.receiver.lastFootprint))
            assertFalse(alias in f.receiver.lastFootprint)
            f.base.installSyntheticBarrierRecovery(recovery)
            val before = f.state()
            val replay = receiveRetail(f, request, replayed = true)
            assertEquals(original.result, replay.result)
            assertRetailDeferred(f.store().execute(testPrincipal(), f.request(204_151, fixtureReceipt(203_152, 1))),
                DeferredReason.RESOURCE_RECONCILIATION_REQUIRED)
            assertEquals(1, f.receiver.evaluations)
            assertEquals(before, f.state())
            f.base.setAuth("disabled")
            val disabled = f.state()
            assertRetailDeferred(f.store().execute(testPrincipal(), request), DeferredReason.AUTHORIZATION_DENIED)
            assertEquals(disabled, f.state())
        } finally { f.close() }
    }

    @Test fun coherentHistoricalPairRollbackStillPassesUnderSyntheticRuntimeAndIsNotFreshnessEvidence() = runTest {
        val f = InventoryRetailFixture()
        try {
            initializeRetail(f)
            val first = f.request(204_160, fixtureReceipt(203_160, 0))
            receiveRetail(f, first)
            val old = f.state()
            val secondInput = fixtureReceipt(203_161, 1)
            val second = f.request(204_161, secondInput)
            receiveRetail(f, second)
            val newer = f.state()
            f.restoreProjectionForLimitationTest(old.rows)
            val rolledBack = f.state()
            assertEquals(newer.branch, rolledBack.branch)
            assertEquals(newer.counts, rolledBack.counts)
            assertEquals(newer.claims, rolledBack.claims)
            val changes = f.totalChanges()
            val acceptedOld = f.read()
            assertEquals(Revision(1), acceptedOld.snapshot.revision)
            assertEquals(first.operationId, acceptedOld.retained.operationId)
            f.withView {
                assertNotNull(it.retainedCommand(second.operationId))
                assertEquals(DomainId(second.operationId.value), it.claim(InventoryRetailStoreProfile.sourceClaim(secondInput.receiptId)))
            }
            assertEquals(changes, f.totalChanges())
            assertEquals(rolledBack, f.state())
            // Explicit passing limitation; point reads cannot enumerate the omitted tail.
            // No new mutation is attempted from this rolled-back cut.
        } finally { f.close() }
    }

    @Test fun thirtyTwoSmallSequentialReceiptsFitButAThirtyThirdRejectsWithoutPruning() = runTest {
        val f = InventoryRetailFixture()
        try {
            initializeRetail(f)
            repeat(32) { index -> receiveRetail(f, f.request(205_000 + index, fixtureReceipt(206_000 + index, index.toLong()))) }
            val current = f.read()
            assertEquals(Revision(32), current.snapshot.revision)
            assertEquals(32, current.companion.receipts.size)
            assertTrue(current.cutBytes.size <= 65_536 && current.companionBytes.size <= 65_536)
            val before = f.state()
            val request = f.request(205_032, fixtureReceipt(206_032, 32))
            val rejected = assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), request))
            assertFalse(rejected.committed)
            assertEquals("fixture.inventory.retail.capacity", rejected.code)
            assertEquals(before.rows, f.state().rows)
            assertEquals(before.claims, f.state().claims)
            assertEquals(current.companionBytes, f.read().companionBytes)
            val after = f.state()
            assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), request)).replayed)
            assertEquals(after, f.state())
            // Not a promise that one 32-receipt latest batch or arbitrary 32 pools fits.
        } finally { f.close() }
    }

    @Test fun observationCancellationAtEachBorrowedReadBoundaryPropagatesUnchangedWithoutWrites() = runTest {
        val f = InventoryRetailFixture()
        try {
            initializeRetail(f)
            receiveRetail(f, f.request(204_170, fixtureReceipt(203_170, 0)))
            for (boundary in listOf("aggregate", "retained", "claim")) {
                val sentinel = LocalStorageCancellationSentinel("synthetic-retail-$boundary")
                val before = f.state()
                val changes = f.totalChanges()
                val failure = assertFailsWith<LocalStorageCancellationSentinel> {
                    f.withView { real ->
                        val view = object : CommandReadView by real {
                            override suspend fun aggregate(key: AggregateKey): StoredAggregate? {
                                if (boundary == "aggregate") throw sentinel
                                return real.aggregate(key)
                            }
                            override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? {
                                if (boundary == "retained") throw sentinel
                                return real.retainedCommand(operationId)
                            }
                            override suspend fun claim(key: ClaimKey): DomainId? {
                                if (boundary == "claim") throw sentinel
                                return real.claim(key)
                            }
                        }
                        InventoryRetailRepository.observe(view)
                    }
                }
                assertSame(sentinel, failure)
                assertEquals(before, f.state())
                assertEquals(changes, f.totalChanges())
                assertEquals(Revision(1), f.read().snapshot.revision)
            }
        } finally { f.close() }
    }
}

private suspend fun initializeRetail(f: InventoryRetailFixture): MutationReply.Recorded {
    f.installFreshSyntheticAuthority()
    return assertIs<MutationReply.Recorded>(f.store(initializing = true).execute(testPrincipal(), f.genesisRequest())).also {
        assertTrue(it.committed)
        assertFalse(it.replayed)
        assertEquals(Revision(0), f.read().snapshot.revision)
    }
}

private suspend fun receiveRetail(f: InventoryRetailFixture, request: MutationRequest, replayed: Boolean = false): MutationReply.Recorded =
    assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), request)).also {
        assertTrue(it.committed)
        assertEquals(replayed, it.replayed)
    }

private suspend fun assertRetailPair(f: InventoryRetailFixture, request: MutationRequest, reply: MutationReply.Recorded,
    revision: Long): InventoryRetailObservedFacts {
    val current = f.read()
    val input = InventoryRetailCommandCodec.decodeReceive(request.payload)
    val result = InventoryRetailFixtureResultCodec.decode(reply.result)
    assertEquals(InventoryRetailFixtureResultKind.RECEIVED, result.kind)
    assertEquals(Revision(revision), current.snapshot.revision)
    assertEquals(Revision(revision + 1), current.storageGeneration)
    assertEquals(result.inventoryRevision, current.snapshot.revision)
    assertEquals(input.receipts.map { it.receiptId }, result.receiptIds)
    assertEquals(request.operationId, result.operationId)
    assertEquals(TEST_SCOPE, result.scope)
    assertEquals(current.cutDigest, result.cutDigest)
    assertEquals(current.companionDigest, result.companionDigest)
    assertEquals(current.cutBytes.copyBytes().decodeToString(), InventorySnapshotCodec.encode(current.snapshot))
    assertEquals(current.companionBytes, InventoryRetailCompanionCodec.encode(current.companion))
    assertEquals(sha256(current.cutBytes.copyBytes()), current.cutDigest)
    assertEquals(sha256(current.companionBytes.copyBytes()), current.companionDigest)
    val retained = current.retained
    assertEquals(reply.result, retained.result)
    assertEquals(request.payload, retained.normalized)
    assertEquals(request.operationId, retained.operationId)
    assertEquals(TEST_ACTOR, retained.originalActorId)
    assertEquals(2, retained.aggregateWrites.size)
    assertEquals(input.receipts.size * 2, retained.claimChanges.size)
    assertTrue(retained.jobs.isEmpty())
    f.withView { view ->
        for (write in retained.aggregateWrites) {
            val row = assertNotNull(view.aggregate(write.key))
            assertEquals(row.revision, write.storageGeneration)
            assertEquals(row.schema, write.schema)
            assertEquals(row.payload, write.payload)
            assertEquals(row.digest, write.payloadDigest)
        }
        for (source in current.companion.receipts) {
            assertEquals(DomainId(source.producingOperationId.value), view.claim(InventoryRetailStoreProfile.sourceClaim(source.receiptId)))
            assertEquals(source.receiptId, view.claim(InventoryRetailStoreProfile.originClaim(source.origin)))
        }
    }
    return current
}

private fun assertRetailDeferred(reply: MutationReply, reason: DeferredReason) =
    assertEquals(reason, assertIs<MutationReply.Deferred>(reply).reason)

private suspend fun assertRetailReadAndNextFail(f: InventoryRetailFixture, expected: InventoryRetailStoreFailure) {
    val before = f.state()
    val changes = f.totalChanges()
    val evaluations = f.receiver.evaluations
    val failure = assertFailsWith<InventoryRetailStoreRejected> { f.read() }
    assertEquals(expected, failure.failure)
    assertEquals(failure.failure.name, failure.message)
    assertNull(failure.cause)
    assertRetailDeferred(f.store().execute(testPrincipal(), f.request(204_999, fixtureReceipt(203_999, 1))),
        DeferredReason.STORAGE_UNCERTAIN)
    assertEquals(before, f.state())
    assertEquals(changes, f.totalChanges())
    assertEquals(evaluations, f.receiver.evaluations)
}

private suspend fun exerciseRetailCommitFailure(cut: CommitFailureCut) {
    val seed = InventoryRetailFixture()
    val before = try { initializeRetail(seed); seed.state() } finally { seed.close() }
    val request = seed.request(204_180, fixtureReceipt(203_180, 0, 2, 201), fixtureReceipt(203_181, 0, 4, 402))
    val driver = CommitFaultDriver(cut)
    val uncertain = InventoryRetailFixture(seed.path, driver)
    try {
        uncertain.database.useWriterConnection { requireDurableWriter(it) }
        assertRetailDeferred(uncertain.store(fault = StoreFaultProbe { if (it == StoreFaultPoint.BEFORE_COMMIT) driver.arm() })
            .execute(testPrincipal(), request), DeferredReason.STORAGE_UNCERTAIN)
        driver.assertInjectedAtExpectedBoundary()
        uncertain.database.useWriterConnection {
            assertFalse(it.inTransaction())
            requireDurableWriter(it)
        }
        if (cut == CommitFailureCut.BEFORE_SQLITE_COMMIT) assertEquals(before, uncertain.state())
        else assertEquals(Revision(1), uncertain.read().snapshot.revision)
    } finally { uncertain.close() }
    val reopened = InventoryRetailFixture(seed.path)
    try {
        if (cut == CommitFailureCut.BEFORE_SQLITE_COMMIT) assertEquals(before, reopened.state())
        val reply = receiveRetail(reopened, request, replayed = cut == CommitFailureCut.AFTER_SQLITE_COMMIT)
        assertRetailPair(reopened, request, reply, 1)
        assertEquals(listOf(2L, 2L, 2L, 2L, 4L, 3L), reopened.state().counts)
        assertEquals(if (cut == CommitFailureCut.AFTER_SQLITE_COMMIT) 0 else 1, reopened.receiver.evaluations)
    } finally { reopened.close() }
}
