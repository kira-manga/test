package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_NULL
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.store.FixtureReceiptOrigin
import com.ukkera.pos.inventory.store.InventoryFixtureReceiveRequest
import com.ukkera.pos.inventory.store.InventoryRetailCommandCodec
import com.ukkera.pos.inventory.store.InventoryRetailObservedFacts
import com.ukkera.pos.inventory.store.InventoryRetailReceiveBatch
import com.ukkera.pos.inventory.store.InventoryRetailRepository
import com.ukkera.pos.inventory.store.InventoryRetailStoreProfile
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.CollectionLock
import com.ukkera.pos.sales.OwnedOrder
import com.ukkera.pos.sales.PaidSale
import com.ukkera.pos.sales.ReceiptEntitlement
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.store.SalesStorageRevision
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Lead-owned test orchestration. Every positive state comes from actual kernel commits. */
internal class CheckoutJourney(var fixture: CheckoutFixture = CheckoutFixture()) {
    private var serial = 410_000
    fun id(): DomainId = testId(serial++)
    fun operation(): OperationId = OperationId(id().value)
    val sessionId = id()
    val unitId = id()
    val history = mutableListOf<MutationRequest>()
    val nowMillis: Long get() = fixture.base.clockValue.value.toEpochMilliseconds()

    fun evidence(): CheckoutEvidence = CheckoutEvidence(id(), fixture.base.clockValue)

    fun request(body: CheckoutRequest, operation: OperationId = operation()): MutationRequest =
        MutationRequest(operation, CheckoutFixtureCodec.keyOf(body), CheckoutFixtureCodec.encode(body))

    suspend fun execute(request: MutationRequest, store: AtomicMasterStore = fixture.store()): MutationReply.Recorded {
        val result = checkoutCommitted(store.execute(testPrincipal(), request))
        if (!result.replayed) history += request
        return result
    }

    suspend fun execute(body: CheckoutRequest): MutationReply.Recorded = execute(request(body))

    suspend fun install(opening: Long = 500) {
        fixture.installFreshSyntheticAuthority()
        execute(fixture.genesisRequest(), fixture.store(initializing = true))
        execute(CheckoutRequest.Open(CHECKOUT_DRAWER, Revision(0), Revision(0), sessionId,
            BusinessDate.parseCanonical("2026-10-23"), checkoutMoney(opening), id(), evidence()))
    }

    suspend fun inventory(): InventoryRetailObservedFacts = fixture.withView { view ->
        InventoryRetailRepository.observe(view).also(::requireCheckoutInventoryProducer)
    }

    suspend fun cash(): CashSession = fixture.withView { view -> checkoutCash(view, sessionId) }

    suspend fun cashRef(): CheckoutCashRef = fixture.withView { view ->
        val cash = checkoutCash(view, sessionId)
        val drawer = assertNotNull(view.aggregate(CheckoutFixtureProfile.drawerKey(cash.drawerId)))
        val custodian = assertNotNull(view.aggregate(CheckoutFixtureProfile.custodianKey(cash.custodianId)))
        CheckoutCashRef(CheckoutRef(cash.id, cash.revision), SalesStorageRevision.toDomain(drawer.revision),
            SalesStorageRevision.toDomain(custodian.revision))
    }

    suspend fun create(
        basket: CheckoutBasket = CheckoutBasket.SIMPLE_100,
        reservationExpiry: Long? = nowMillis + 60_000,
        lotExpiry: Long? = null,
        receiveQuantity: Long = 10,
        begin: Boolean = true,
    ): CheckoutTarget {
        val lines = List(if (basket == CheckoutBasket.SPLIT_33_33_34) 3 else 1) {
            CheckoutLine(id(), id(), id(), id(), id(), Quantity(unitId, 1, 0))
        }
        val target = CheckoutTarget(id(), id(), id(), id(), lines, reservationExpiry)
        execute(CheckoutRequest.Create(target.orderId, target.quoteId, basket, lines))
        receive(lines, receiveQuantity, lotExpiry)
        if (begin) begin(target)
        return target
    }

    suspend fun receive(lines: List<CheckoutLine>, quantity: Long = 10, lotExpiry: Long? = null): MutationReply.Recorded {
        val revision = inventory().snapshot.revision
        val batch = InventoryRetailReceiveBatch(lines.map { line ->
            InventoryFixtureReceiveRequest(revision, id(), line.skuId, line.warehouseId, line.lotId,
                Quantity(line.quantity.unitId, quantity, line.quantity.scale), checkoutMoney(quantity * 20), lotExpiry,
                FixtureReceiptOrigin(id(), id()))
        })
        return execute(MutationRequest(operation(), InventoryRetailStoreProfile.RECEIVE_COMMAND,
            InventoryRetailCommandCodec.encodeReceive(batch)))
    }

    suspend fun begin(target: CheckoutTarget): MutationReply.Recorded {
        val order = fixture.withView { checkoutOrder(it, target.orderId) }
        return execute(CheckoutRequest.Begin(CheckoutRef(order.id, order.revision), cashRef(), target.collectionId,
            inventory().snapshot.revision, target.reservationId, target.reservationExpiry))
    }

    suspend fun current(target: CheckoutTarget): CheckoutTargetCut = fixture.withView { view ->
        val order = checkoutOrder(view, target.orderId)
        val cash = checkoutCash(view, sessionId)
        val row = assertNotNull(view.aggregate(CheckoutFixtureProfile.salesKey(SalesSnapshotKind.COLLECTION, target.collectionId)))
        val collection = SalesSnapshotCodec.decodeCollection(checkoutRowText(row), SalesSnapshotBinding(
            SalesSnapshotKind.COLLECTION, TEST_SCOPE, target.collectionId, SalesStorageRevision.toDomain(row.revision)), order, cash)
        CheckoutTargetCut(order, collection, cash)
    }

    suspend fun salesRef(target: CheckoutTarget): CheckoutSalesRef {
        val current = current(target)
        return CheckoutSalesRef(CheckoutRef(current.order.id, current.order.revision),
            CheckoutRef(current.collection.id, current.collection.revision), cashRef())
    }

    suspend fun stockRef(target: CheckoutTarget): CheckoutReservationRef {
        val inventory = inventory().snapshot
        val reservation = assertNotNull(inventory.reservation(target.reservationId))
        return CheckoutReservationRef(inventory.revision, CheckoutRef(reservation.reservationId, reservation.revision))
    }

    suspend fun start(
        target: CheckoutTarget,
        amount: Long,
        tender: CheckoutTender = CheckoutTender.Cash,
        attemptId: DomainId = id(),
        payer: String? = "synthetic payer",
    ): DomainId {
        execute(CheckoutRequest.Attempt(salesRef(target), stockRef(target), attemptId, tender, checkoutMoney(amount), payer))
        return attemptId
    }

    suspend fun outcome(
        target: CheckoutTarget,
        attempt: DomainId,
        outcome: CheckoutOutcome,
        derive: Boolean = false,
    ): MutationReply.Recorded = execute(CheckoutRequest.Outcome(salesRef(target), attempt, outcome,
        if (derive) CheckoutExposureUpdate.Derive(stockRef(target)) else CheckoutExposureUpdate.Preserve))

    suspend fun payCash(target: CheckoutTarget, amount: Long = 100, received: Long = amount, change: Long = 0,
        derive: Boolean = false, attemptId: DomainId = id()): DomainId {
        val attempt = start(target, amount, attemptId = attemptId)
        outcome(target, attempt, CheckoutOutcome.CashReceived(checkoutMoney(received), checkoutMoney(change), evidence()), derive)
        return attempt
    }

    fun externalTender(): CheckoutTender = CheckoutTender.External(id(), Revision(3), CheckoutExternalKind.CARD, true, true)

    suspend fun payExternal(target: CheckoutTarget, amount: Long = 100, derive: Boolean = false,
        attemptId: DomainId = id()): DomainId {
        val attempt = start(target, amount, externalTender(), attemptId)
        outcome(target, attempt, CheckoutOutcome.ExternalAttested(evidence(), "synthetic-terminal", "cashier-reference"), derive)
        return attempt
    }

    suspend fun finalRequest(target: CheckoutTarget, issueId: DomainId = id(), saleId: DomainId = id()): MutationRequest =
        request(CheckoutRequest.Finalize(salesRef(target), stockRef(target), issueId, saleId))

    suspend fun retained(request: MutationRequest): RetainedCommandEvidence = fixture.withView { view ->
        assertNotNull(view.retainedCommand(request.operationId))
    }

    suspend fun validateBusinessHistory() {
        for (request in history) if (request.command != InventoryRetailStoreProfile.GENESIS_COMMAND &&
            request.command != InventoryRetailStoreProfile.RECEIVE_COMMAND) requireCheckoutAfterFacts(retained(request))
    }

    suspend fun reopen() {
        val path = fixture.path
        val clock = fixture.base.clockValue
        fixture.close()
        fixture = CheckoutFixture(path)
        fixture.base.clockValue = clock
    }

    fun at(epochMillis: Long) { fixture.base.clockValue = UtcInstant(Instant.fromEpochMilliseconds(epochMillis)) }
    fun close() = fixture.close()
}

internal data class CheckoutTarget(
    val orderId: DomainId,
    val collectionId: DomainId,
    val reservationId: DomainId,
    val quoteId: DomainId,
    val lines: List<CheckoutLine>,
    val reservationExpiry: Long?,
)

internal data class CheckoutTargetCut(val order: OwnedOrder, val collection: CollectionLock, val cash: CashSession)

internal fun checkoutMoney(minor: Long): Money = Money(Currency.EGP, minor)

internal fun checkoutCommitted(reply: MutationReply): MutationReply.Recorded =
    assertIs<MutationReply.Recorded>(reply).also { assertTrue(it.committed, "Expected committed; got ${it.code}") }

internal fun checkoutDeferred(reply: MutationReply, reason: DeferredReason): MutationReply.Deferred =
    assertIs<MutationReply.Deferred>(reply).also { assertEquals(reason, it.reason) }

internal fun checkoutRowText(row: StoredAggregate): String {
    assertEquals(sha256(row.payload.copyBytes()), row.digest)
    return row.payload.copyBytes().decodeToString(throwOnInvalidSequence = true)
}

internal suspend fun checkoutOrder(view: CommandReadView, id: DomainId): OwnedOrder {
    val row = assertNotNull(view.aggregate(CheckoutFixtureProfile.salesKey(SalesSnapshotKind.ORDER, id)))
    return SalesSnapshotCodec.decodeOrder(checkoutRowText(row), SalesSnapshotBinding(
        SalesSnapshotKind.ORDER, view.scope, id, SalesStorageRevision.toDomain(row.revision)))
}

internal suspend fun checkoutCash(view: CommandReadView, id: DomainId): CashSession {
    val row = assertNotNull(view.aggregate(CheckoutFixtureProfile.salesKey(SalesSnapshotKind.CASH_SESSION, id)))
    return SalesSnapshotCodec.decodeCashSession(checkoutRowText(row), SalesSnapshotBinding(
        SalesSnapshotKind.CASH_SESSION, view.scope, id, SalesStorageRevision.toDomain(row.revision)))
}

internal suspend fun CheckoutFixture.sale(id: DomainId): PaidSale = withView { view ->
    val row = assertNotNull(view.aggregate(CheckoutFixtureProfile.salesKey(SalesSnapshotKind.PAID_SALE, id)))
    assertEquals(Revision(1), row.revision)
    SalesSnapshotCodec.decodePaidSale(checkoutRowText(row), SalesSnapshotBinding(SalesSnapshotKind.PAID_SALE,
        TEST_SCOPE, id, SalesStorageRevision.toDomain(row.revision)))
}

internal suspend fun CheckoutFixture.entitlement(id: DomainId): ReceiptEntitlement = withView { view ->
    val row = assertNotNull(view.aggregate(CheckoutFixtureProfile.salesKey(SalesSnapshotKind.RECEIPT_ENTITLEMENT, id)))
    assertEquals(Revision(1), row.revision)
    SalesSnapshotCodec.decodeReceiptEntitlement(checkoutRowText(row), SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT,
        TEST_SCOPE, id, SalesStorageRevision.toDomain(row.revision)))
}

/** Complete SQLite cells, including binary payloads, not counts or a projection-only digest. */
internal suspend fun CheckoutFixture.databaseState(): Map<String, List<List<String>>> = database.useWriterConnection { connection ->
    requireDurableWriter(connection)
    connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
        buildMap {
            for ((table, order) in listOf(
                "branch_authority" to "scope", "operation_result" to "scope,operationId",
                "aggregate_state" to "scope,kind,id", "unique_claim" to "scope,namespace,`key`",
                "journal_envelope" to "scope,sequence", "audit_event" to "id",
                "outbox_intent" to "scope,operationId,ordinal",
            )) put(table, connection.usePrepared("SELECT * FROM $table ORDER BY $order") { statement ->
                buildList {
                    while (statement.step()) add(List(statement.getColumnCount()) { column ->
                        when (val type = statement.getColumnType(column)) {
                            SQLITE_DATA_BLOB -> "blob:${HexFormat.of().formatHex(statement.getBlob(column))}"
                            SQLITE_DATA_NULL -> "null"
                            else -> "$type:${statement.getText(column)}"
                        }
                    })
                }
            })
        }
    }
}

internal fun Map<String, List<List<String>>>.checkoutBusinessRows(): Map<String, List<List<String>>> =
    filterKeys { it == "aggregate_state" || it == "unique_claim" }

internal fun InventorySnapshot.reservationFor(target: CheckoutTarget) = assertNotNull(reservation(target.reservationId))
