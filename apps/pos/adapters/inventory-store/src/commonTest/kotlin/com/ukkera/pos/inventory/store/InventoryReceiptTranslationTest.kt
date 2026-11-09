package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.CollectionBinding
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryEffect
import com.ukkera.pos.inventory.InventoryFailure
import com.ukkera.pos.inventory.InventoryPlan
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.InventoryRejection
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.InventorySnapshotCodec
import com.ukkera.pos.inventory.ReservationIssueResult
import com.ukkera.pos.inventory.StockAllocation
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockOwnership
import com.ukkera.pos.inventory.StockRestoration
import com.ukkera.pos.inventory.ValuationFact
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real domain plans and adapter-owned seams; no kernel/evidence constructor is made public. */
class InventoryReceiptTranslationTest {
    private val f = ReceiptFixtures

    @Test fun explicitGenesisIsDomainZeroAndTwoGenerationOneInsertsWithNoReceiptClaims() {
        val writes = f.genesis()
        val current = f.current(writes)
        assertEquals(InventoryScope(f.scope.companyId, f.scope.branchId), writes.snapshot.scope)
        assertEquals(Revision(0), writes.snapshot.revision)
        assertEquals(Revision(1), current.storageGeneration)
        assertEquals(emptyList(), writes.snapshot.positions)
        assertEquals(emptyList(), writes.snapshot.pools)
        assertEquals(emptyList(), writes.snapshot.reservations)
        assertEquals(emptyList(), writes.snapshot.issues)
        assertEquals(emptyList(), writes.snapshot.operationIds)
        assertEquals(emptyList(), writes.snapshot.sourceIds)
        assertEquals(emptyList(), writes.claims)
        assertEquals(listOf(InventoryReceiptStoreProfile.cutKey(f.scope.branchId),
            InventoryReceiptStoreProfile.companionKey(f.scope.branchId)), writes.changes.map { it.key })
        assertEquals(listOf(InventoryReceiptStoreProfile.CUT_SCHEMA, InventoryReceiptStoreProfile.COMPANION_SCHEMA),
            writes.changes.map { it.schema })
        writes.changes.forEach { assertNull(it.expectedRevision) }
        assertEquals(current.cutRow.digest, InventoryReceiptResultCodec.decode(writes.result).cutDigest)
        assertEquals(current.companionRow.digest, InventoryReceiptResultCodec.decode(writes.result).companionDigest)
    }

    @Test fun successiveRealReceiptsAdvanceDomainRevisionAndBothCasGenerationsTogether() {
        var current = f.current(f.genesis())
        for (n in 1..2) {
            val request = f.request(n, current.snapshot.revision)
            val context = f.context(request, n)
            val plan = plan(current, context, request)
            val writes = translateReceipt(current, context, request, plan)
            assertSame(plan.nextSnapshot, writes.snapshot)
            val valuation = assertIs<InventoryEffect.Valuation>(plan.effects[0])
            assertSame(valuation.plan.next, writes.snapshot.pool(valuation.pool)!!.value)
            current.snapshot.pool(valuation.pool)?.let { assertSame(it.value, valuation.plan.sourceBasis) }
            assertEquals(Revision(n.toLong()), writes.snapshot.revision)
            assertEquals(listOf(current.storageGeneration, current.storageGeneration), writes.changes.map { it.expectedRevision })
            assertEquals(listOf(InventoryReceiptStoreProfile.cutKey(f.scope.branchId),
                InventoryReceiptStoreProfile.companionKey(f.scope.branchId)), writes.changes.map { it.key })
            val claims = writes.claims.map { assertIs<ClaimChange.Acquire>(it) }
            assertEquals(listOf(InventoryReceiptStoreProfile.sourceClaim(request.receiptId),
                InventoryReceiptStoreProfile.originClaim(request.origin)), claims.map { it.key })
            assertEquals(listOf(DomainId(context.operationId.value), request.receiptId), claims.map { it.ownerId })
            val result = InventoryReceiptResultCodec.decode(writes.result)
            assertEquals(InventoryReceiptResultKind.RECEIVED, result.kind)
            assertEquals(context.operationId, result.operationId)
            assertEquals(request.receiptId, result.receiptId)
            assertEquals(writes.snapshot.revision, result.inventoryRevision)
            assertEquals(f.scope, result.scope)
            current = f.current(writes)
            assertEquals(Revision(n.toLong() + 1), current.storageGeneration)
            assertEquals(current.snapshot.revision, InventoryReceiptStoreProfile.toDomainRevision(current.storageGeneration))
            assertEquals(current.storageGeneration, InventoryReceiptStoreProfile.toStorageRevision(current.snapshot.revision))
            assertEquals(current.cutRow.digest, result.cutDigest)
            assertEquals(current.companionRow.digest, result.companionDigest)
            assertEquals(Quantity(f.unit, n.toLong() * 10, 0), current.snapshot.positions.single().onHand)
            assertEquals(Money(Currency.EGP, n.toLong() * 101), current.snapshot.pools.single().value.carryingValue)
        }
    }

    @Test fun completeCrosswalkPreservesSamePoolDifferentLotsAndDifferentWarehouseReceipts() {
        val requests = listOf(f.request(),
            f.request(2).copy(lotId = f.id(23), quantity = Quantity(f.unit, 7, 0), originalValue = Money(Currency.EGP, 53),
                expiresAtEpochMillis = 2_000_000_001_000),
            f.request(3).copy(warehouseId = f.id(24), quantity = Quantity(f.unit, 3, 0), originalValue = Money(Currency.EGP, 22)),
            f.request(4).copy(quantity = Quantity(f.unit, 2, 0), originalValue = Money(Currency.EGP, 0)))
        var current = f.current(f.genesis())
        for ((index, request) in requests.withIndex()) current = f.current(f.receive(current, index + 1, request))
        assertEquals(4, current.companion.sources.size)
        assertEquals(3, current.snapshot.positions.size)
        assertEquals(2, current.snapshot.pools.size)
        for ((index, request) in requests.withIndex()) {
            val source = current.companion.sources[index]
            assertEquals(InventoryReceiptSourceBinding(request.receiptId, f.op(1_001 + index), request.origin,
                request.toReceipt(f.scope).position, request.quantity, request.originalValue, request.expiresAtEpochMillis), source)
        }
        assertEquals(listOf(12L, 7L, 3L), requests.take(3).map { current.snapshot.position(it.toReceipt(f.scope).position)!!.onHand.scaled })
        assertEquals(Revision(3), current.snapshot.pool(requests[0].toReceipt(f.scope).position.pool)!!.value.revision)
        assertEquals(Revision(1), current.snapshot.pool(requests[2].toReceipt(f.scope).position.pool)!!.value.revision)
        assertEquals(requests.map { it.receiptId }, current.snapshot.sourceIds)
        assertEquals(current.companion.sources.map { it.producingOperationId }, current.snapshot.operationIds)
        assertEquals(InventorySnapshotCodec.encode(current.snapshot), InventorySnapshotCodec.encode(f.roundTrip(current.snapshot)))
        val valuation = assertIs<InventoryReceiptPostedEffect.Valuation>(current.companion.effects[0])
        val movement = assertIs<InventoryReceiptPostedEffect.Movement>(current.companion.effects[1])
        assertEquals(Revision(2), valuation.requiredPoolRevision)
        assertEquals(requests.last().originalValue, valuation.originalValue)
        assertEquals(requests.last().toReceipt(f.scope).position, movement.position)
    }

    @Test fun untrackedReceiptRetainsExplicitNullLotAndExpiryAndZeroOriginalValue() {
        val request = f.request().copy(lotId = null, expiresAtEpochMillis = null, originalValue = Money(Currency.EGP, 0))
        val current = f.current(f.receive(f.current(f.genesis()), request = request))
        assertNull(current.snapshot.positions.single().key.lotId)
        assertNull(current.snapshot.positions.single().expiresAtEpochMillis)
        assertNull(current.companion.sources.single().position.lotId)
        assertNull(current.companion.sources.single().expiresAtEpochMillis)
        assertEquals(Money(Currency.EGP, 0), current.snapshot.pools.single().value.carryingValue)
    }

    @Test fun decodedSameByteSourceCloneFailsEvenWhenDomainRequireCurrentAcceptsIt() {
        val current = f.one()
        val clone = f.roundTrip(current.snapshot)
        assertNotSame(current.snapshot, clone)
        assertEquals(InventorySnapshotCodec.encode(current.snapshot), InventorySnapshotCodec.encode(clone))
        val request = f.request(2)
        val context = f.context(request, 2)
        val detachedPlan = InventoryPlanner.receive(clone, request.expectedRevision, context.operationId, listOf(request.toReceipt(f.scope)))
        InventoryPlanner.requireCurrent(detachedPlan, current.snapshot) // Revision/scope alone are not the adapter's source binding.
        receiptFailure(InventoryReceiptStoreFailure.BINDING_MISMATCH) { translateReceipt(current, context, request, detachedPlan) }
        assertSame(current.snapshot, plan(current, context, request).sourceSnapshot)
        assertEquals(Revision(2), translateReceipt(current, context, request, plan(current, context, request)).snapshot.revision)
    }

    @Test fun aDifferentValidHistoryAtTheSameRevisionCannotReplaceTheLoadedSource() {
        val current = f.one()
        val other = f.current(f.receive(f.current(f.genesis()), request = f.request().copy(
            quantity = Quantity(f.unit, 9, 0), originalValue = Money(Currency.EGP, 97))))
        assertEquals(current.snapshot.revision, other.snapshot.revision)
        assertEquals(current.snapshot.operationIds, other.snapshot.operationIds)
        assertEquals(current.snapshot.sourceIds, other.snapshot.sourceIds)
        assertNotEquals(current.cutRow.payload, other.cutRow.payload)
        val request = f.request(2)
        val context = f.context(request, 2)
        val foreignPlan = plan(other, context, request)
        InventoryPlanner.requireCurrent(foreignPlan, current.snapshot)
        receiptFailure(InventoryReceiptStoreFailure.BINDING_MISMATCH) { translateReceipt(current, context, request, foreignPlan) }
    }

    @Test fun sourceContextBindsScopeOriginalActorFixedCommandOperationAndSemanticDigest() {
        val current = f.current(f.genesis())
        val request = f.request()
        val context = f.context(request)
        val plan = plan(current, context, request)
        for (scope in listOf(BranchScope(f.id(80), f.scope.companyId, f.scope.branchId),
            BranchScope(f.scope.organizationId, f.id(81), f.scope.branchId),
            BranchScope(f.scope.organizationId, f.scope.companyId, f.id(82)))) {
            val changed = context.copy(scope = scope, semanticDigest = semanticDigest(scope, context.originalActorId,
                context.command, InventoryReceiptCommandCodec.encode(request)))
            receiptFailure(InventoryReceiptStoreFailure.BINDING_MISMATCH) { translateReceipt(current, changed, request, plan) }
        }
        val otherKey = CommandKey(InventoryReceiptStoreProfile.RECEIVE_COMMAND.name, 2)
        for (changed in listOf(context.copy(originalActorId = f.id(83)), context.copy(operationId = f.op(84)),
            context.copy(semanticDigest = PayloadDigest("f".repeat(64))),
            context.copy(command = otherKey, semanticDigest = semanticDigest(context.scope, context.originalActorId,
                otherKey, InventoryReceiptCommandCodec.encode(request))), context.copy(command = InventoryReceiptStoreProfile.GENESIS_COMMAND))) {
            receiptFailure(InventoryReceiptStoreFailure.BINDING_MISMATCH) { translateReceipt(current, changed, request, plan) }
        }
    }

    @Test fun validActorBindingIsNotAnAuthorizationDecisionInThePureRepository() {
        val current = f.current(f.genesis())
        val request = f.request()
        val original = f.context(request)
        val differentActor = original.copy(originalActorId = f.id(87), semanticDigest = semanticDigest(f.scope, f.id(87),
            original.command, InventoryReceiptCommandCodec.encode(request)))
        val writes = translateReceipt(current, differentActor, request, plan(current, differentActor, request))
        assertEquals(f.id(87), writes.companion.producer.originalActorId)
        // The closed fixture handler must supply actual current authorization; this is only translation.
    }

    @Test fun fixtureGenesisRejectsAnUnboundActorKeyOrDigest() {
        val context = f.context(InventoryReceiptStoreProfile.GENESIS_COMMAND, InventoryReceiptCommandCodec.genesisRequest(), f.op(999))
        for (changed in listOf(context.copy(originalActorId = f.id(86)), context.copy(command = InventoryReceiptStoreProfile.RECEIVE_COMMAND),
            context.copy(semanticDigest = PayloadDigest("f".repeat(64))))) {
            receiptFailure(InventoryReceiptStoreFailure.BINDING_MISMATCH) { fixtureReceiptGenesis(changed) }
        }
    }

    @Test fun normalizedRequestCannotChangeAfterItsContextDigestWasEstablished() {
        val current = f.current(f.genesis())
        val request = f.request()
        val context = f.context(request)
        val plan = plan(current, context, request)
        val changed = changedRequests(request) + listOf(request.copy(origin = request.origin.copy(documentId = f.id(85))),
            request.copy(origin = request.origin.copy(allocationId = f.id(86))))
        for (replacement in changed) receiptFailure(InventoryReceiptStoreFailure.BINDING_MISMATCH) {
            translateReceipt(current, context, replacement, plan)
        }
    }

    @Test fun separatelyBoundRequestCannotReuseAPlanForDifferentReceiptFacts() {
        val current = f.one()
        val request = f.request(2)
        val context = f.context(request, 2)
        val plan = plan(current, context, request)
        for (replacement in changedRequests(request)) receiptFailure {
            translateReceipt(current, f.context(replacement, 2), replacement, plan)
        }
        assertEquals(Revision(2), translateReceipt(current, context, request, plan).snapshot.revision)
    }

    @Test fun freshReceiptIdCannotReuseAnUpstreamAllocationButANewAllocationCanShareItsDocument() {
        val current = f.one()
        val origin = current.companion.sources.single().origin
        val request = f.request(2).copy(origin = origin)
        val context = f.context(request, 2)
        receiptFailure(InventoryReceiptStoreFailure.SOURCE_CONFLICT) { translateReceipt(current, context, request, plan(current, context, request)) }
        val independent = request.copy(origin = origin.copy(allocationId = f.id(880)))
        val writes = f.receive(current, 2, independent)
        assertEquals(listOf(origin, independent.origin), writes.companion.sources.map { it.origin })
        assertEquals(InventoryReceiptStoreProfile.originClaim(independent.origin), writes.claims.last().key)
        assertNotEquals(InventoryReceiptStoreProfile.originClaim(origin), writes.claims.last().key)
    }

    @Test fun realPlannerRejectsReusedReceiptAndOperationIdsBeforeTranslation() {
        val current = f.one()
        val repeated = f.request(2).copy(receiptId = current.companion.sources.single().receiptId)
        val failure = assertFailsWith<InventoryRejection> { plan(current, f.context(repeated, 2), repeated) }
        assertEquals(InventoryFailure.DUPLICATE_SOURCE, failure.failure)
        val fresh = f.request(2)
        val operationFailure = assertFailsWith<InventoryRejection> {
            plan(current, f.context(fresh, 2).copy(operationId = current.companion.producer.operationId), fresh)
        }
        assertEquals(InventoryFailure.DUPLICATE_SOURCE, operationFailure.failure)
    }

    @Test fun realMultiReceiptBatchIsRejectedRatherThanSelectingOnlyTwoOfItsEffects() {
        val current = f.current(f.genesis())
        val request = f.request()
        val context = f.context(request)
        val batch = InventoryPlanner.receive(current.snapshot, Revision(0), context.operationId,
            listOf(request.toReceipt(f.scope), f.request(2, Revision(0)).toReceipt(f.scope)))
        assertEquals(4, batch.effects.size)
        assertEquals(2, f.roundTrip(batch.nextSnapshot).sourceIds.size)
        receiptFailure(InventoryReceiptStoreFailure.UNSUPPORTED_STATE) { translateReceipt(current, context, request, batch) }
        assertEquals(Revision(0), current.snapshot.revision)
    }

    @Test fun supplierCustodyAndOwnedQuarantinePlansCannotBePostedAsOwnedReleasedReceipts() {
        val current = f.current(f.genesis())
        val request = f.request()
        val context = f.context(request)
        val original = request.toReceipt(f.scope)
        val supplier = original.copy(position = original.position.copy(pool = original.position.pool.copy(
            ownership = StockOwnership.SupplierOwned(f.id(88))), custody = StockCustody.REJECTED_HELD), originalValue = null)
        val quarantine = original.copy(position = original.position.copy(custody = StockCustody.QUARANTINE))
        for (receipt in listOf(supplier, quarantine)) {
            val plan = InventoryPlanner.receive(current.snapshot, Revision(0), context.operationId, listOf(receipt))
            assertEquals(receipt.position, f.roundTrip(plan.nextSnapshot).positions.single().key)
            receiptFailure { translateReceipt(current, context, request, plan) }
        }
    }

    @Test fun genuineReservationRecallExpiryCountAndCostPlansCannotEnterReceiptTranslation() {
        val current = f.one()
        val request = f.request(2)
        val context = f.context(request, 2)
        for (plan in nonReceiptPlans(current.snapshot, context.operationId)) {
            f.roundTrip(plan.nextSnapshot) // These are real supported domain plans, not malformed adapter fixtures.
            receiptFailure { translateReceipt(current, context, request, plan) }
            receiptFailure(InventoryReceiptStoreFailure.UNSUPPORTED_STATE) { validateReceiptCut(plan.nextSnapshot, current.companion) }
        }
    }

    @Test fun cancelledZeroCostTargetStillRetainsItsSourceAndHistoricalAdjustmentsAndIsUnsupported() {
        val current = f.one()
        val pool = current.snapshot.pools.single()
        val receipt = current.companion.sources.single().receiptId
        val source = f.id(720)
        val adjusted = InventoryPlanner.adjustReceiptCost(current.snapshot, Revision(1), f.op(721), pool.key,
            pool.value.revision, f.id(722), source, receipt, Money(Currency.EGP, 20)).nextSnapshot
        val cancelled = InventoryPlanner.adjustReceiptCost(adjusted, adjusted.revision, f.op(723), pool.key,
            adjusted.pools.single().value.revision, f.id(724), source, receipt, Money(Currency.EGP, 0)).nextSnapshot
        val restored = f.roundTrip(cancelled)
        val value = restored.pools.single().value
        assertEquals(0L, value.costTargets.single().targetDelta.minor)
        assertEquals(source, value.costTargets.single().sourceId)
        assertEquals(2, value.adjustments.size)
        assertEquals(current.snapshot.pools.single().value.carryingValue, value.carryingValue)
        assertEquals(listOf(receipt, f.id(722), f.id(724)), restored.sourceIds)
        assertTrue(source !in restored.sourceIds) // A natural cumulative cost source is not its per-change event ID.
        receiptFailure(InventoryReceiptStoreFailure.UNSUPPORTED_STATE) { validateReceiptCut(restored, current.companion) }
    }

    @Test fun fullyRestoredIssueIsStillNonReceiptHistoryEvenWhenQuantityAndValueReturnToTheirOriginalTotals() {
        val current = f.one()
        val reserved = reserve(current.snapshot, f.op(740)).nextSnapshot
        val reservation = reserved.reservations.single()
        val issued = assertIs<ReservationIssueResult.Issued>(InventoryPlanner.issueReservation(reserved, reserved.revision,
            f.op(741), reservation.reservationId, reservation.revision, f.id(742), 1))
        val issueCut = issued.plan.nextSnapshot
        val issue = issueCut.issues.single()
        val allocation = issue.allocations.single().allocation
        val returned = InventoryPlanner.restoreOriginalRetail(issueCut, issueCut.revision, f.op(743), f.id(744),
            issue.issueId, issue.revision, listOf(StockRestoration(allocation.allocationId, allocation.quantity, allocation.position)), 1).nextSnapshot
        val restored = f.roundTrip(returned)
        assertEquals(current.snapshot.positions.single().onHand, restored.positions.single().onHand)
        assertEquals(current.snapshot.pools.single().value.carryingValue, restored.pools.single().value.carryingValue)
        assertTrue(restored.pools.single().value.facts.any { it is ValuationFact.Issue })
        assertTrue(restored.pools.single().value.facts.any { it is ValuationFact.Restoration })
        receiptFailure(InventoryReceiptStoreFailure.UNSUPPORTED_STATE) { validateReceiptCut(restored, current.companion) }
    }

    @Test fun countCeilingRejectsThirtyThirdRealReceiptWithoutPretendingCountGuaranteesByteCapacity() {
        var current = f.current(f.genesis())
        for (n in 1..InventoryReceiptStoreProfile.MAX_RECEIPTS) {
            val writes = f.receive(current, n)
            assertEquals(n, writes.companion.sources.size)
            assertTrue(writes.changes.all { it.payload.size <= InventoryReceiptStoreProfile.MAX_ROW_BYTES })
            assertTrue(writes.changes.sumOf { it.payload.size } + writes.result.size <= InventoryReceiptStoreProfile.MAX_PROPOSAL_BYTES)
            current = f.current(writes)
        }
        assertEquals(Revision(32), current.snapshot.revision)
        assertEquals(Revision(33), current.storageGeneration)
        val request = f.request(33)
        val context = f.context(request, 33)
        val plan = plan(current, context, request)
        assertEquals(33, plan.nextSnapshot.sourceIds.size)
        assertEquals(2, plan.effects.size)
        receiptFailure(InventoryReceiptStoreFailure.TOO_LARGE) { translateReceipt(current, context, request, plan) }
    }

    @Test fun smallerEnclosingRowLimitRejectsARealDomainCodecValidOversizedCut() {
        val source = f.genesis().snapshot
        val receipts = (1..100).map { f.request(it, Revision(0)).copy(skuId = f.id(10_000 + it)).toReceipt(f.scope) }
        val after = InventoryPlanner.receive(source, source.revision, f.op(2_000), receipts).nextSnapshot
        val encoded = InventorySnapshotCodec.encode(after)
        assertEquals(100, f.decodeCut(after, encoded).pools.size)
        assertTrue(encoded.encodeToByteArray().size > InventoryReceiptStoreProfile.MAX_ROW_BYTES)
        assertTrue(encoded.encodeToByteArray().size < InventorySnapshotCodec.MAX_ENCODED_BYTES)
        // This demonstrates the actual outer bound independently of the stricter receipt count ceiling.
        receiptFailure(InventoryReceiptStoreFailure.TOO_LARGE) { encodeReceiptCut(after) }
    }

    @Test fun writeSetChangesClaimsAndPayloadsCannotBeRewrittenThroughMutableAliases() {
        val writes = f.receive(f.current(f.genesis()))
        val changes = writes.changes.toMutableList()
        val claims = writes.claims.toMutableList()
        val copied = InventoryReceiptWriteSet(writes.result, writes.snapshot, writes.companion, changes, claims)
        changes.clear()
        claims.clear()
        assertEquals(writes.changes, copied.changes)
        assertEquals(writes.claims, copied.claims)
        assertFails { (copied.changes as MutableList<AggregateChange>).clear() }
        assertFails { (copied.claims as MutableList<ClaimChange>).clear() }
        val before = sha256(copied.changes.first().payload.copyBytes())
        copied.changes.first().payload.copyBytes().fill(0)
        assertEquals(before, sha256(copied.changes.first().payload.copyBytes()))
    }

    private fun plan(current: InventoryReceiptReadCut, context: ReceiptContext, request: InventoryFixtureReceiveRequest): InventoryPlan =
        InventoryPlanner.receive(current.snapshot, request.expectedRevision, context.operationId, listOf(request.toReceipt(f.scope)))

    private fun changedRequests(request: InventoryFixtureReceiveRequest): List<InventoryFixtureReceiveRequest> = listOf(
        request.copy(expectedRevision = request.expectedRevision.next()), request.copy(receiptId = f.id(90)),
        request.copy(skuId = f.id(91)), request.copy(warehouseId = f.id(92)), request.copy(lotId = f.id(93)),
        request.copy(quantity = request.quantity.copy(scaled = 11)), request.copy(quantity = request.quantity.copy(unitId = f.id(94))),
        request.copy(quantity = request.quantity.copy(scale = 1)), request.copy(originalValue = request.originalValue.copy(minor = 102)),
        request.copy(expiresAtEpochMillis = 2_000_000_000_001),
    )

    private fun reserve(snapshot: InventorySnapshot, operation: OperationId): InventoryPlan = InventoryPlanner.reserve(snapshot,
        snapshot.revision, operation, f.id(700), CollectionBinding(f.id(701), Revision(1), f.id(702), f.actor, CollectionExposure.PAID),
        listOf(StockAllocation(f.id(703), f.id(704), snapshot.positions.single().key, Quantity(f.unit, 3, 0))), 1).plan

    private fun nonReceiptPlans(snapshot: InventorySnapshot, operation: OperationId): List<InventoryPlan> {
        val position = snapshot.positions.single()
        val pool = snapshot.pools.single()
        return listOf(reserve(snapshot, operation),
            InventoryPlanner.recallLot(snapshot, snapshot.revision, operation, position.key.pool.skuId, position.key.lotId!!),
            InventoryPlanner.expireReservations(snapshot, snapshot.revision, operation, position.expiresAtEpochMillis!!),
            InventoryPlanner.recordCountShortage(snapshot, snapshot.revision, operation, f.id(705), position.key, snapshot.revision,
                position.onHand.copy(scaled = 9)),
            InventoryPlanner.adjustReceiptCost(snapshot, snapshot.revision, operation, pool.key, pool.value.revision,
                f.id(706), f.id(707), snapshot.sourceIds.single(), Money(Currency.EGP, 1)))
    }
}
