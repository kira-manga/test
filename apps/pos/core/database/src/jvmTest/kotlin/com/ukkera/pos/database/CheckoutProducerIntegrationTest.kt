package com.ukkera.pos.database

import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.store.InventoryRetailRepository
import com.ukkera.pos.inventory.store.InventoryRetailStoreProfile
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Whole producer checks, not merely equal current inventory bytes or a matching command name. */
class CheckoutProducerIntegrationTest {
    @Test fun issuedProducerRejectsEveryOmittedRowAndExtraWriteClaimJobOrWrongResult() = runTest {
        checkoutFaultyProducer(ProducerStage.ISSUED, listOf(CheckoutProducerFault.OMIT_CUT, CheckoutProducerFault.OMIT_COMPANION,
            CheckoutProducerFault.OMIT_ORDER, CheckoutProducerFault.OMIT_COLLECTION, CheckoutProducerFault.OMIT_SALE,
            CheckoutProducerFault.OMIT_ENTITLEMENT, CheckoutProducerFault.EXTRA_WRITE, CheckoutProducerFault.WRONG_RESULT,
            CheckoutProducerFault.EXTRA_CLAIM, CheckoutProducerFault.OMIT_CLAIM, CheckoutProducerFault.WRONG_CLAIM_OWNER,
            CheckoutProducerFault.RELEASE_RECEIPT_SOURCE, CheckoutProducerFault.EXTRA_JOB))
    }
    @Test fun heldProducerRejectsEitherOmittedInventoryRowAndAnyExtraFactOrClaimOrJob() = runTest {
        checkoutFaultyProducer(ProducerStage.HELD, listOf(CheckoutProducerFault.OMIT_CUT, CheckoutProducerFault.OMIT_COMPANION,
            CheckoutProducerFault.EXTRA_WRITE, CheckoutProducerFault.WRONG_RESULT, CheckoutProducerFault.EXTRA_CLAIM,
            CheckoutProducerFault.RELEASE_RECEIPT_SOURCE, CheckoutProducerFault.EXTRA_JOB))
    }
    @Test fun beginProducerCannotHideAnOmittedOrderCollectionOrClaimBehindValidInventory() = runTest {
        checkoutFaultyProducer(ProducerStage.BEGIN, listOf(CheckoutProducerFault.OMIT_ORDER, CheckoutProducerFault.OMIT_COLLECTION,
            CheckoutProducerFault.OMIT_CLAIM, CheckoutProducerFault.WRONG_CLAIM_OWNER, CheckoutProducerFault.WRONG_RESULT,
            CheckoutProducerFault.EXTRA_JOB))
    }
    @Test fun attemptProducerCannotHideMissingCashCollectionOrAttemptClaimBehindValidInventory() = runTest {
        checkoutFaultyProducer(ProducerStage.ATTEMPT, listOf(CheckoutProducerFault.OMIT_CASH, CheckoutProducerFault.OMIT_COLLECTION,
            CheckoutProducerFault.OMIT_CLAIM, CheckoutProducerFault.WRONG_CLAIM_OWNER, CheckoutProducerFault.EXTRA_WRITE,
            CheckoutProducerFault.EXTRA_JOB))
    }
    @Test fun derivedCashProducerCannotDiscardCashCollectionInventoryOrAnyFinancialClaim() = runTest {
        checkoutFaultyProducer(ProducerStage.CASH_DERIVE, listOf(CheckoutProducerFault.OMIT_CUT, CheckoutProducerFault.OMIT_COMPANION,
            CheckoutProducerFault.OMIT_CASH, CheckoutProducerFault.OMIT_COLLECTION, CheckoutProducerFault.OMIT_CLAIM,
            CheckoutProducerFault.WRONG_CLAIM_OWNER, CheckoutProducerFault.EXTRA_JOB))
    }
    @Test fun derivedUnknownProducerKeepsOriginalObservationAndNoCashRewriteOrPaymentClaim() = runTest {
        checkoutFaultyProducer(ProducerStage.UNKNOWN_DERIVE, listOf(CheckoutProducerFault.OMIT_CUT, CheckoutProducerFault.OMIT_COMPANION,
            CheckoutProducerFault.OMIT_COLLECTION, CheckoutProducerFault.EXTRA_WRITE, CheckoutProducerFault.EXTRA_CLAIM,
            CheckoutProducerFault.WRONG_RESULT, CheckoutProducerFault.EXTRA_JOB))
    }

    @Test fun everyIssuedRowRequiresExactKnownSchemaAndDomainToStorageGeneration() = runTest {
        val j = CheckoutJourney()
        try {
            val original = checkoutValidIssued(j)
            for (row in original.aggregateWrites) {
                for (mutant in listOf(row.checkoutMutant(schema = "qualification.wrong/v1"),
                    row.checkoutMutant(generation = row.storageGeneration.next()),
                    row.checkoutMutant(key = row.key.copy(id = j.id())))) {
                    val changed = checkoutEvidenceMutant(original, writes = original.aggregateWrites.map { if (it === row) mutant else it })
                    assertFailsWith<CheckoutContractRejected> { requireCheckoutAfterFacts(changed) }
                }
            }
            requireCheckoutAfterFacts(original)
        } finally { j.close() }
    }

    @Test fun everyIssuedClaimRequiresExactNamespaceKeyAndRelationalOwner() = runTest {
        val j = CheckoutJourney()
        try {
            val original = checkoutValidIssued(j)
            for (claim in original.claimChanges) {
                val mutants = listOf(
                    RetainedClaimChange(claim.action, claim.key, j.id()),
                    RetainedClaimChange(claim.action, claim.key.copy(key = j.id().value), claim.ownerId),
                    RetainedClaimChange(claim.action, claim.key.copy(namespace = "qualification.wrong"), claim.ownerId),
                    RetainedClaimChange(RetainedClaimAction.RELEASE, claim.key, claim.ownerId),
                )
                for (mutant in mutants) assertFailsWith<CheckoutContractRejected> {
                    requireCheckoutAfterFacts(checkoutEvidenceMutant(original,
                        claims = original.claimChanges.map { if (it === claim) mutant else it }))
                }
                assertFailsWith<CheckoutContractRejected> {
                    requireCheckoutAfterFacts(checkoutEvidenceMutant(original, claims = original.claimChanges.filterNot { it === claim }))
                }
            }
        } finally { j.close() }
    }

    @Test fun actualIssuedResultCannotLieAboutSaleIssueRevisionCashDependencyOrResultVariant() = runTest {
        val j = CheckoutJourney()
        try {
            val original = checkoutValidIssued(j)
            val result = assertIs<CheckoutResult.Issued>(CheckoutFixtureCodec.decodeResult(original.command, original.result))
            val values = listOf(result.copy(saleId = j.id()), result.copy(issueId = j.id()),
                result.copy(stock = result.stock.copy(inventoryRevision = result.stock.inventoryRevision.next())),
                result.copy(sales = result.sales.copy(cash = result.sales.cash.copy(session =
                    result.sales.cash.session.copy(revision = result.sales.cash.session.revision.next())))),
                CheckoutResult.Held(result.sales, result.stock))
            for (changed in values) assertFailsWith<CheckoutContractRejected> {
                requireCheckoutAfterFacts(checkoutEvidenceMutant(original, result = CheckoutFixtureCodec.encodeResult(changed)))
            }
        } finally { j.close() }
    }

    @Test fun historicalDispatcherRejectsUnknownNameVersionActorAndOperationEvenWithRehashedKernelEnvelope() = runTest {
        val j = CheckoutJourney()
        try {
            val original = checkoutValidIssued(j)
            for (mutant in listOf(
                checkoutEvidenceMutant(original, command = CommandKey(original.command.name, 2)),
                checkoutEvidenceMutant(original, command = CommandKey("fixture.checkout.unsupported", 1)),
                checkoutEvidenceMutant(original, actor = j.id()),
                checkoutEvidenceMutant(original, operation = j.operation()),
            )) assertFailsWith<CheckoutContractRejected> { requireCheckoutAfterFacts(mutant) }
        } finally { j.close() }
    }

    @Test fun entitlementEmbeddingAnotherOtherwiseValidSaleCannotPassStandaloneSaleCorrespondence() = runTest {
        val j = CheckoutJourney()
        try {
            val original = checkoutValidIssued(j)
            val result = assertIs<CheckoutResult.Issued>(CheckoutFixtureCodec.decodeResult(original.command, original.result))
            val entitlement = original.aggregateWrites.single { it.key.kind == CheckoutFixtureProfile.ENTITLEMENT_KIND }
            val originalText = entitlement.payload.copyBytes().decodeToString()
            val marker = "\"stockIssueId\":\"${result.issueId.value}\""
            assertEquals(1, originalText.windowed(marker.length).count { it == marker })
            val bytes = StoredBytes(originalText.replace(marker, "\"stockIssueId\":\"${j.id().value}\"").encodeToByteArray())
            val mutant = entitlement.checkoutMutant(payload = bytes)
            // The individual entitlement/sale DTO remains canonical and intrinsically valid.
            CheckoutRows.entitlement(CheckoutRow.from(mutant), TEST_SCOPE, result.saleId)
            assertFailsWith<CheckoutContractRejected> {
                requireCheckoutAfterFacts(checkoutEvidenceMutant(original,
                    writes = original.aggregateWrites.map { if (it === entitlement) mutant else it }))
            }
        } finally { j.close() }
    }

    @Test fun historicalCreateChecksOriginalLiteralBasketWithoutCallingTodaysPricingEngine() = runTest {
        val j = CheckoutJourney()
        try {
            j.install(); j.create()
            val request = j.history.single { it.command == CheckoutFixtureProfile.CREATE }
            val original = j.retained(request)
            val input = assertIs<CheckoutRequest.Create>(CheckoutFixtureCodec.decode(original.command, original.normalized))
            val mutant = checkoutEvidenceMutant(original, normalized = CheckoutFixtureCodec.encode(input.copy(basket = CheckoutBasket.ZERO)))
            assertFailsWith<CheckoutContractRejected> { requireCheckoutAfterFacts(mutant) }
            requireCheckoutAfterFacts(original)
        } finally { j.close() }
    }

    @Test fun historicalCashAfterLowerBoundsDoNotClaimMissingPreimageArithmetic() = runTest {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(); j.payCash(target)
            val original = j.retained(j.history.last())
            val cash = original.aggregateWrites.single { it.key.kind == CheckoutFixtureProfile.SESSION_KIND }
            // An intentionally hypothetical higher opening float and expected cash remain intrinsic
            // AFTER facts. The checker lacks the before-image; this is a LIMITATION, not valid staging.
            val text = cash.payload.copyBytes().decodeToString()
            assertTrue("\"minor\":\"500\"" in text && "\"minor\":\"600\"" in text)
            val changed = text.replace("\"minor\":\"500\"", "\"minor\":\"700\"")
                .replace("\"minor\":\"600\"", "\"minor\":\"800\"")
            val row = cash.checkoutMutant(payload = StoredBytes(changed.encodeToByteArray()))
            CheckoutRows.cash(CheckoutRow.from(row), TEST_SCOPE, j.sessionId)
            requireCheckoutAfterFacts(checkoutEvidenceMutant(original, writes = original.aggregateWrites.map { if (it === cash) row else it }))
            assertEquals(checkoutMoney(600), j.cash().expectedCash) // Hypothetical evidence never entered the file.
        } finally { j.close() }
    }

    @Test fun adversarialUncollectedAfterFactsReachAndFailIssuedPreflightRatherThanProvenance() = runTest {
        checkoutReservationGate(CheckoutReservationCorruption.UNCOLLECTED, false)
    }
    @Test fun adversarialUncollectedAfterFactsReachAndFailHeldPreflightRatherThanProvenance() = runTest {
        checkoutReservationGate(CheckoutReservationCorruption.UNCOLLECTED, true)
    }
    @Test fun retainedReservationIdsActorQuoteAndAllocationValuesMustMatchTheCurrentIssuedTarget() = runTest {
        for (control in CheckoutReservationCorruption.entries - CheckoutReservationCorruption.UNCOLLECTED) checkoutReservationGate(control, false)
    }
    @Test fun retainedReservationIdsActorQuoteAndAllocationValuesMustMatchTheCurrentHeldTarget() = runTest {
        for (control in CheckoutReservationCorruption.entries - CheckoutReservationCorruption.UNCOLLECTED) checkoutReservationGate(control, true)
    }

    @Test fun factoryRejectsEveryProtectedKindAndClaimOverlapBeforeOrAfterDeclarationFreeze() = runTest {
        val j = CheckoutJourney()
        try {
            j.install()
            val kinds = listOf(InventoryRetailStoreProfile.CUT_KIND, InventoryRetailStoreProfile.COMPANION_KIND,
                CheckoutFixtureProfile.ORDER_KIND, CheckoutFixtureProfile.COLLECTION_KIND, CheckoutFixtureProfile.SESSION_KIND,
                CheckoutFixtureProfile.SALE_KIND, CheckoutFixtureProfile.ENTITLEMENT_KIND, CheckoutFixtureProfile.DRAWER_KIND,
                CheckoutFixtureProfile.CUSTODIAN_KIND, AUTH_KEY.kind, "qualification.checkout.policy")
            val namespaces = listOf(InventoryRetailStoreProfile.SOURCE_NAMESPACE, InventoryRetailStoreProfile.ORIGIN_NAMESPACE) +
                listOf(SalesUniqueKind.CASH_SESSION, SalesUniqueKind.OPENING_FLOAT_SOURCE, SalesUniqueKind.ACTIVE_DRAWER,
                    SalesUniqueKind.ACTIVE_CUSTODIAN, SalesUniqueKind.CASH_SOURCE, SalesUniqueKind.ORDER, SalesUniqueKind.COLLECTION,
                    SalesUniqueKind.PAYMENT_ATTEMPT, SalesUniqueKind.PAYMENT_RECORD, SalesUniqueKind.SALE,
                    SalesUniqueKind.SALE_ORDER, SalesUniqueKind.RETURN_ENTITLEMENT).map { CheckoutFixtureProfile.claim(it, j.id()).namespace }
            for (kind in kinds) {
                assertFailsWith<IllegalArgumentException> { j.fixture.negativeDeclarationControlStore(protectedKind = kind) }
                val before = j.fixture.databaseState()
                val request = MutationRequest(j.operation(), CHECKOUT_DECLARATION_CONTROL, StoredBytes(byteArrayOf()))
                checkoutDeferred(j.fixture.negativeDeclarationControlStore(protectedKind = kind, expandAfterFreeze = true)
                    .execute(testPrincipal(), request), DeferredReason.STORAGE_UNCERTAIN)
                assertEquals(before, j.fixture.databaseState())
            }
            for (namespace in namespaces) {
                assertFailsWith<IllegalArgumentException> { j.fixture.negativeDeclarationControlStore(protectedNamespace = namespace) }
                val before = j.fixture.databaseState()
                val request = MutationRequest(j.operation(), CHECKOUT_DECLARATION_CONTROL, StoredBytes(byteArrayOf()))
                checkoutDeferred(j.fixture.negativeDeclarationControlStore(protectedNamespace = namespace, expandAfterFreeze = true)
                    .execute(testPrincipal(), request), DeferredReason.STORAGE_UNCERTAIN)
                assertEquals(before, j.fixture.databaseState())
            }
        } finally { j.close() }
    }

    @Test fun coherentOldInventoryPairCanStillBeObservedButCurrentFinalizedOrderPreventsSecondSale() = runTest {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(); j.payCash(target)
            val old = j.inventory()
            val request = j.finalRequest(target)
            j.execute(request)
            val latestSequence = assertNotNull(j.fixture.database.facts().branch(TEST_SCOPE.storageKey())).sequence
            // Explicit limitation regression: original selected producer still exists, but a disk
            // editor restores only its coherent older inventory projection. No current-frontier proof.
            j.fixture.corruptProjection { connection ->
                for ((key, bytes) in listOf(InventoryRetailStoreProfile.cutKey(TEST_SCOPE.branchId) to old.cutBytes,
                    InventoryRetailStoreProfile.companionKey(TEST_SCOPE.branchId) to old.companionBytes)) {
                    connection.usePrepared("UPDATE aggregate_state SET revision=?,payload=?,payloadHash=? WHERE scope=? AND kind=? AND id=?") {
                        it.bindLong(1, old.storageGeneration.value); it.bindBlob(2, bytes.copyBytes()); it.bindText(3, sha256(bytes.copyBytes()).value)
                        it.bindText(4, TEST_SCOPE.storageKey()); it.bindText(5, key.kind); it.bindText(6, key.id.value); it.step()
                    }
                }
            }
            j.reopen()
            assertEquals(old.retained.operationId, j.inventory().retained.operationId)
            assertTrue(j.inventory().retained.sequence.value < latestSequence)
            assertCheckoutPreflightRejection(j, j.finalRequest(target), SalesRule.IMMUTABLE_RECORD)
            assertEquals(checkoutMoney(600), j.cash().expectedCash)
        } finally { j.close() }
    }

    @Test fun sixCommandShapeCannotReachOneMebibyteEvenAtAllPublishedPayloadCaps() {
        // Source-derived upper bound, NOT an attained business payload or durable capacity promise.
        // 20 top-level length prefixes; conservative585-byte scalar header; six write frames and
        // four UUID claim frames. Actual bounded profile metadata is below these maxima.
        val writeKinds = listOf(InventoryRetailStoreProfile.CUT_KIND, InventoryRetailStoreProfile.COMPANION_KIND,
            CheckoutFixtureProfile.ORDER_KIND, CheckoutFixtureProfile.COLLECTION_KIND, CheckoutFixtureProfile.SESSION_KIND,
            CheckoutFixtureProfile.SALE_KIND, CheckoutFixtureProfile.ENTITLEMENT_KIND, CheckoutFixtureProfile.DRAWER_KIND,
            CheckoutFixtureProfile.CUSTODIAN_KIND)
        val schemas = listOf(InventoryRetailStoreProfile.CUT_SCHEMA, InventoryRetailStoreProfile.COMPANION_SCHEMA,
            CheckoutFixtureProfile.ORDER_SCHEMA, CheckoutFixtureProfile.COLLECTION_SCHEMA, CheckoutFixtureProfile.SESSION_SCHEMA,
            CheckoutFixtureProfile.SALE_SCHEMA, CheckoutFixtureProfile.ENTITLEMENT_SCHEMA, CheckoutFixtureProfile.DRAWER_SCHEMA,
            CheckoutFixtureProfile.CUSTODIAN_SCHEMA)
        assertTrue(writeKinds.all { it.encodeToByteArray().size <= 28 })
        assertTrue(schemas.all { it.encodeToByteArray().size <= 31 })
        val bound = 20L * 4 + 585 + CheckoutFixtureProfile.MAX_REQUEST_BYTES + CheckoutFixtureProfile.MAX_RESULT_BYTES +
            6L * (CheckoutFixtureProfile.MAX_ROW_BYTES + 24 + 28 + 36 + 19 + 31) + 4L * (20 + 13 + 37 + 36 + 36)
        assertEquals(432_141L, bound)
        assertTrue(bound < CheckoutFixtureProfile.MAX_PROPOSAL_BYTES)
    }
}

private enum class ProducerStage { BEGIN, ATTEMPT, CASH_DERIVE, UNKNOWN_DERIVE, HELD, ISSUED }

private suspend fun checkoutFaultyProducer(stage: ProducerStage, faults: List<CheckoutProducerFault>) {
    for (fault in faults) {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(begin = stage != ProducerStage.BEGIN)
            val request = when (stage) {
                ProducerStage.BEGIN -> j.request(CheckoutRequest.Begin(CheckoutRef(target.orderId, Revision(0)), j.cashRef(),
                    target.collectionId, j.inventory().snapshot.revision, target.reservationId, target.reservationExpiry))
                ProducerStage.ATTEMPT -> j.request(CheckoutRequest.Attempt(j.salesRef(target), j.stockRef(target), j.id(),
                    CheckoutTender.Cash, checkoutMoney(100), null))
                ProducerStage.CASH_DERIVE, ProducerStage.UNKNOWN_DERIVE -> {
                    val attempt = j.start(target, 100)
                    val outcome = if (stage == ProducerStage.UNKNOWN_DERIVE) CheckoutOutcome.Unknown(j.evidence())
                        else CheckoutOutcome.CashReceived(checkoutMoney(100), checkoutMoney(0), j.evidence())
                    j.request(CheckoutRequest.Outcome(j.salesRef(target), attempt, outcome, CheckoutExposureUpdate.Derive(j.stockRef(target))))
                }
                ProducerStage.HELD, ProducerStage.ISSUED -> {
                    j.payCash(target)
                    if (stage == ProducerStage.HELD) j.at(assertNotNull(target.reservationExpiry))
                    j.finalRequest(target)
                }
            }
            // Only this explicitly named negative factory can commit the deliberately faulty producer.
            checkoutCommitted(j.fixture.negativeProducerControlStore(request.command, fault).execute(testPrincipal(), request))
            val raw = j.retained(request)
            assertFailsWith<CheckoutContractRejected>("$stage/$fault") { requireCheckoutAfterFacts(raw) }
            val before = j.fixture.databaseState()
            assertFails("$stage/$fault must not become accepted inventory") { j.inventory() }
            assertEquals(before, j.fixture.databaseState())
            // Ordinary RECEIVE must invoke the complete producer gate, not accept an inventory subset.
            val next = j.fixture.receiveRequest(j.operation(), fixtureReceipt(439_990, 99))
            checkoutDeferred(j.fixture.store().execute(testPrincipal(), next), DeferredReason.STORAGE_UNCERTAIN)
            assertEquals(before, j.fixture.databaseState())
        } finally { j.close() }
    }
}

private suspend fun checkoutValidIssued(j: CheckoutJourney): RetainedCommandEvidence {
    j.install(); val target = j.create(); j.payCash(target)
    val request = j.finalRequest(target)
    j.execute(request)
    return j.retained(request).also { requireCheckoutAfterFacts(it) }
}

private suspend fun checkoutReservationGate(control: CheckoutReservationCorruption, held: Boolean) {
    val j = CheckoutJourney()
    try {
        j.install(); val target = j.create(); j.payCash(target)
        j.receive(target.lines, quantity = 1) // Latest producer has genuine receipt-only effects, not reservation before-images.
        j.forgeLatestReceiptReservationForNegativeControl(control)
        j.reopen() // Restored immutable-trigger definitions must pass the real Room open guard.
        val observed = j.inventory()
        assertEquals(InventoryRetailStoreProfile.RECEIVE_COMMAND, observed.retained.command)
        if (control == CheckoutReservationCorruption.UNCOLLECTED)
            assertEquals(CollectionExposure.UNCOLLECTED, observed.snapshot.reservationFor(target).binding.exposure)
        if (held) j.at(assertNotNull(target.reservationExpiry))
        assertCheckoutPreflightRejection(j, j.finalRequest(target), SalesRule.STOCK_PROOF_MISMATCH)
        assertEquals(checkoutMoney(600), j.cash().expectedCash)
    } finally { j.close() }
}
