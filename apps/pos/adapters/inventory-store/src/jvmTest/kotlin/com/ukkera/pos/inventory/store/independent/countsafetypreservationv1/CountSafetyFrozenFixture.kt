package com.ukkera.pos.inventory.store.independent.countsafetypreservationv1

import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.StockTracking
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.assertEquals

/** Exact frozen inputs; extra IDs and revisions are inert, consistent format bindings. */
internal object CountSafetyFrozen {
    private val directory = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/stock-count-safety-preservation-v1") }
        .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
        ?: error("Frozen stock-count safety inputs are unavailable")
    val root = Json.parseToJsonElement(Files.readString(directory.resolve("fixtures.json"))).jsonObject.also {
        assertEquals("stock-count-safety-preservation-v1", it.text("version"))
        assertEquals("2026-11-04", it.text("frozen_on"))
    }
    val history = root.obj("history")
    val scope = BranchScope(id("ORGANIZATION"), id("COMPANY"), id("BRANCH"))
    val unit = id("BASE-UNIT")
    val scale = root.obj("conventions").getValue("quantity_precision").jsonPrimitive.int
    val now = UtcInstant.parseCanonical(root.obj("conventions").text("now_utc"))
    fun id(label: String) = DomainId(UUID.nameUUIDFromBytes("stock-count-safety-preservation-v1:$label".encodeToByteArray()).toString())
    fun operation(label: String) = OperationId(id(label).value)
    fun quantity(value: String) = Quantity.parse(unit, value, scale)
    fun fixture(name: String) = root.obj("fixtures").obj(name)
    fun unsafe(reason: String) = root.obj("unsafe_variants").obj(reason)
    fun countMatrix(observed: String): JsonObject = fixture("F-COUNT").getValue("bounded_count_matrix").jsonArray
        .map { it.jsonObject }.single { it.text("observed") == observed }
}

internal fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject
internal fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
internal fun JsonObject.quantity(name: String): Quantity = CountSafetyFrozen.quantity(text(name))
internal fun JsonObject.money(name: String): Money = Money(Currency.EGP, text(name).toLong())

/** Records actual normalized planning output; it cannot commit or certify an original. */
internal class CountSafetyOutput : CountOutput() {
    val records = linkedMapOf<AggregateKey, StockRelationalBody>()
    val claims = linkedMapOf<ClaimKey, DomainId>()
    val documents = linkedMapOf<AggregateKey, Pair<StoredAggregate?, StockCountWire>>()
    lateinit var plan: CountPlan
    override suspend fun record(value: StockRelationalBody) {
        val previous = records.put(stockKey(value), value)
        check(previous == null || previous == value) { "Conflicting scalar recording outputs" }
    }
    override suspend fun claim(key: ClaimKey, owner: DomainId) { check(claims.put(key, owner) == null) }
    override suspend fun document(key: AggregateKey, before: StoredAggregate?, value: StockCountWire) {
        check(documents.put(key, before to value) == null)
    }
}

/**
 * Complete, small synthetic scalar inputs for L1 only. Canonical row encoding is real,
 * but the source origins, activation and permission/basis coordinates are not native
 * qualification or a production CommandReadView. No real current authority is supplied.
 */
internal class CountSafetyFixture(val reason: String, private val claimVariant: String? = null) : StockReceiptAccess {
    private val frozen = CountSafetyFrozen
    override val scope = frozen.scope
    override var operationId = frozen.operation("COUNT-CAPTURE")
        private set
    override val actorId = frozen.id(frozen.history.text("originating_count_actor"))
    override val now = frozen.now
    val millis = now.value.toEpochMilliseconds()
    val zero = frozen.quantity("0")
    val countId = frozen.id(frozen.history.text("count_operation"))
    val countKey = AggregateKey(LocalStockStoreProfile.COUNT_DOCUMENT, countId)
    private val sku = frozen.id(frozen.history.text("sku"))
    private val warehouse = frozen.id("WAREHOUSE")
    private val lot = frozen.id(frozen.history.text("lot"))
    private val poolKey = StockPoolKey(InventoryScope(scope.companyId, scope.branchId), sku, warehouse)
    val initialPosition = StockPosition(StockPositionKey(poolKey, lot,
        if (reason == "QUARANTINED") StockCustody.QUARANTINE else StockCustody.RELEASED),
        frozen.history.quantity("current_physical_before_count"), when (reason) {
            "RECALL" -> StockSafety.RECALLED
            "EXPIRED" -> StockSafety.EXPIRED
            "QUARANTINED" -> StockSafety.CLEAR
            else -> error("BLOCKED has no faithful generic-quality binding at this boundary")
        }, UtcInstant.parseCanonical(frozen.unsafe(reason).text("expires_utc")).value.toEpochMilliseconds())
    val itemBody = StockRelationalBody.Item(sku, frozen.unit, frozen.scale, false)
    private val rows = linkedMapOf<AggregateKey, StockRelationalRecordV2>()
    private val documents = linkedMapOf<AggregateKey, StoredAggregate>()
    private val claims = linkedMapOf<ClaimKey, DomainId>()
    private val authorityStamps = linkedMapOf<AggregateKey, LocalStockSourceStamp>()
    private val receiptId = frozen.id(frozen.history.text("receipt_id"))
    private val saleId = frozen.id(frozen.history.text("sale_id"))
    private val saleAllocationId = frozen.id("HISTORICAL-SALE-ALLOCATION")
    private val reservationId = frozen.id(frozen.fixture("F-CLAIMS").text("reservation_id"))
    private var claimAllocationId: DomainId? = null
    var originalCount: StockCountWire? = null
        private set
    val historicalCollectionBinding = CollectionBinding(saleId, Revision(1), frozen.id("HISTORICAL-COLLECTION"),
        frozen.id("HISTORICAL-CASHIER"), CollectionExposure.PAID)
    val originalHistoryKeys: Set<AggregateKey>

    init {
        val marker = StockRelationalBody.Activation(frozen.id("SCALAR-ACTIVATION-INPUT"), 0, 2,
            sha256("synthetic L1 activation input".encodeToByteArray()), STOCK_FRESH_PROFILE_V2, "local.stock.relational/v2")
        putInitial(marker, "SCALAR-ACTIVATION", LocalStockRelationalProfileV2.activationKey(scope.branchId))
        putInitial(itemBody, "SCALAR-ITEM")
        putInitial(StockRelationalBody.Position(initialPosition), "SCALAR-POSITION")
        claims[LocalStockStoreProfile.itemClaim(sku)] = sku

        // Incidental scalar setup, not a count-loss oracle: 12 received for 2,400 and
        // 2 historically issued for the frozen 400 leave 10 carrying 2,000. Neither
        // current/count-loss value is asserted as a new v1 expected outcome (U5).
        val empty = InventoryPoolProjection(poolKey, Revision(1), zero, Money(Currency.EGP, 0), 0)
        val received = InventoryPoolProjection(poolKey, Revision(2), frozen.history.quantity("received_quantity"), Money(Currency.EGP, 2_400), 1)
        val current = InventoryPoolProjection(poolKey, Revision(3), initialPosition.onHand, Money(Currency.EGP, 2_000), 2)
        val receipt = StockRelationalBody.ValueEvent(poolKey, receiptId, 1, StockValueEventKind.RECEIVE,
            empty, received, received.onHand, received.carryingValue, received.carryingValue, 1, null)
        val receiptSlice = StockRelationalBody.ValueSlice(poolKey, receiptId, stockKey(receipt).id, 1, null,
            frozen.id("ORIGINAL-RECEIPT-LINE"), received.onHand, received.carryingValue)
        val historicalAllocation = StockAllocation(saleAllocationId, frozen.id("HISTORICAL-SALE-LINE"),
            initialPosition.key.copy(custody = StockCustody.RELEASED), frozen.history.quantity("sold_before_any_safety_restriction"))
        val issue = StockRelationalBody.Issue(LocalStockIssueHeader(saleId, frozen.id("HISTORICAL-RESERVATION"),
            historicalCollectionBinding, ValueIssuePurpose.RETAIL, Revision(1), 1,
            frozen.history.money("historical_issue_cost_minor"), null, emptyList(), emptyList(), null))
        val issueSlice = StockRelationalBody.IssueSlice(InventoryIssueSlice(saleId, historicalAllocation,
            frozen.history.money("historical_issue_cost_minor"), zero))
        val sale = StockRelationalBody.ValueEvent(poolKey, saleId, 2, StockValueEventKind.ISSUE, received, current,
            -historicalAllocation.quantity, -issue.value.originalValue, -issue.value.originalValue, 1, saleId)
        val valueSlice = StockRelationalBody.ValueSlice(poolKey, saleId, stockKey(sale).id, 2, saleId,
            saleAllocationId, historicalAllocation.quantity, issue.value.originalValue)
        listOf(receipt, receiptSlice).forEach { putInitial(it, "ORIGINAL-RECEIPT") }
        listOf(issue, issueSlice, sale, valueSlice).forEach {
            putInitial(it, "HISTORICAL-SALE", command = LocalStockRelationalProfileV2.COLLECTION_COMMAND,
                producerActor = historicalCollectionBinding.originalActorId)
        }
        putInitial(StockRelationalBody.Pool(current), "SCALAR-CURRENT-POOL")
        claims[LocalStockStoreProfile.sourceClaim(saleId)] = saleId
        originalHistoryKeys = listOf(receipt, receiptSlice, issue, issueSlice, sale, valueSlice).map(::stockKey).toSet()
        if (claimVariant != null) addClaim(claimVariant)
    }

    suspend fun captureCount() {
        operationId = frozen.operation("COUNT-CAPTURE")
        val output = CountSafetyOutput()
        val item = checkNotNull(rows[LocalStockRelationalProfileV2.itemKey(sku)])
        output.plan = countPlan(LocalStockRelationalCommand.CaptureCount(operationId, LocalCountCaptureRequest(countId,
            LocalStockPositionRef(sku, warehouse, lot, initialPosition.key.custody), item.generation, "verified-count-cut")), this, null, output)
        originalCount = output.documents.getValue(countKey).second
        apply(output)
    }

    suspend fun postCount(observed: Quantity): CountSafetyOutput {
        operationId = frozen.operation(frozen.history.text("count_operation"))
        val output = CountSafetyOutput()
        output.plan = countPlan(LocalStockRelationalCommand.PostCount(operationId, LocalCountPostRequest(countId,
            documents.getValue(countKey).revision, observed, "verified-count-adjustment")), this, null, output)
        return output
    }

    fun apply(output: CountSafetyOutput) {
        output.records.forEach { (key, value) -> rows[key] = canonicalRow(value, operationId, key, rows[key]) }
        output.claims.forEach { (key, owner) -> check(claims.put(key, owner) == null) }
        output.documents.forEach { (key, value) ->
            check(documents[key] === value.first)
            documents[key] = canonicalDocument(key, stockBody(value.second, StockCountWire.serializer()), value.first)
        }
    }

    fun apply(output: StockReceiptPlan) {
        output.records.forEach { (key, value) -> rows[key] = canonicalRow(value, operationId, key, rows[key]) }
        output.claims.forEach { (key, owner) -> check(claims.put(key, owner) == null) }
        output.documents.forEach {
            check(documents[it.key] === it.before)
            documents[it.key] = canonicalDocument(it.key, it.body, it.before)
        }
    }

    fun position(custody: StockCustody = initialPosition.key.custody): StockPosition =
        (rows.getValue(LocalStockRelationalProfileV2.positionKey(initialPosition.key.copy(custody = custody))).body as StockRelationalBody.Position).value
    fun pool(): InventoryPoolProjection = (rows.getValue(LocalStockRelationalProfileV2.poolKey(poolKey)).body as StockRelationalBody.Pool).value
    fun countDocument(): StockCountWire = receiptDocument(documents.getValue(countKey), scope, StockCountWire.serializer()).value
    fun laterReceipt(): StockReceiptWire = receiptDocument(documents.getValue(AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT,
        frozen.id(frozen.fixture("F-LATER-LOCATION").text("new_receipt_id")))), scope, StockReceiptWire.serializer()).value
    fun receiptEvent() = rows.getValue(stockValueEventKey(poolKey, receiptId)).body as StockRelationalBody.ValueEvent
    fun saleEvent() = rows.getValue(stockValueEventKey(poolKey, saleId)).body as StockRelationalBody.ValueEvent
    fun saleSlice() = (rows.getValue(LocalStockRelationalProfileV2.issueSliceKey(saleId, saleAllocationId)).body as StockRelationalBody.IssueSlice).value
    fun saleHeader() = (rows.getValue(LocalStockRelationalProfileV2.issueKey(saleId)).body as StockRelationalBody.Issue).value
    fun reservation(): StockRelationalBody.Reservation? = rows[LocalStockRelationalProfileV2.reservationKey(reservationId)]?.body as? StockRelationalBody.Reservation
    fun claimAllocation(): InventoryAllocationRecord? = claimAllocationId?.let {
        (rows.getValue(LocalStockRelationalProfileV2.allocationKey(it)).body as StockRelationalBody.Allocation).value
    }
    fun originalClaimOwnershipRetained(): Boolean = claims[LocalStockStoreProfile.reservationClaim(reservationId)] == reservationId &&
        claimAllocationId?.let { claims[LocalStockStoreProfile.allocationClaim(it)] == reservationId } == true

    suspend fun availability(position: StockPosition): InventoryPositionAvailability = InventoryRelationalPlanner.availability(position,
        InventoryMembershipStream { consume ->
            range(StockQueryV2.PositionMembers(LocalStockRelationalProfileV2.positionKey(position.key).id)) { row ->
                (row.body as? StockRelationalBody.Allocation)?.value?.let { allocation ->
                    val header = rows.getValue(LocalStockRelationalProfileV2.reservationKey(allocation.reservationId)).body as StockRelationalBody.Reservation
                    consume(header.value, allocation)
                }
            }
        })

    private fun addClaim(variant: String) {
        check(reason == "RECALL" || reason == "EXPIRED") { "Quarantine cannot stand in for an unchanged originally released allocation key" }
        val input = frozen.fixture("F-CLAIMS")
        val variantInput = input.obj("reservation_variants").obj(variant)
        val binding = CollectionBinding(frozen.id(input.text("order_id")), Revision(1), frozen.id(input.text("collection_lock_id")),
            frozen.id(input.obj("reservation_variants").obj("UNKNOWN").text("original_collecting_actor")),
            if (variant == "UNKNOWN") CollectionExposure.UNKNOWN else CollectionExposure.UNCOLLECTED)
        val setId = frozen.id("ORIGINAL-ALLOCATION-SET")
        val lineId = frozen.id("ORIGINAL-ALLOCATION-LINE")
        val allocationId = LocalStockRelationalProfileV2.allocationId(setId, Revision(1), lineId, initialPosition.key)
        claimAllocationId = allocationId
        val header = StockRelationalBody.Reservation(InventoryReservationHeader(reservationId, binding, Revision(1), ReservationStatus.HELD,
            UtcInstant.parseCanonical(variantInput.text("normal_expiry_utc")).value.toEpochMilliseconds(), 1, 1), setId, Revision(1))
        val allocation = StockRelationalBody.Allocation(InventoryAllocationRecord(reservationId,
            StockAllocation(allocationId, lineId, initialPosition.key, input.obj("allocation").quantity("quantity"))))
        val hold = StockRelationalBody.Hold(InventoryHoldRecord(reservationId, ReservationHold(
            if (reason == "RECALL") ReservationHoldReason.RECALL else ReservationHoldReason.EXPIRY, initialPosition.key)))
        listOf(header, allocation, hold).forEach { putInitial(it, "ORIGINAL-CLAIM") }
        claims[LocalStockStoreProfile.reservationClaim(reservationId)] = reservationId
        claims[LocalStockStoreProfile.allocationClaim(allocationId)] = reservationId
    }

    suspend fun receiveHeldSameLot(): StockReceiptPlan {
        val input = frozen.fixture("F-LATER-LOCATION")
        val quantity = input.obj("new_receipt").quantity("quantity")
        val label = input.text("new_receipt_id")
        val receiptId = frozen.id(label)
        val allocationId = frozen.id("$label:allocation")
        val basisId = frozen.id("$label:basis")
        operationId = frozen.operation("$label:operation")
        // As with the initial scalar valuation, this incidental value is not a U5 assertion.
        val receivedValue = Money(Currency.EGP, 1_000)
        fun stamp(label: String): LocalStockSourceStamp {
            val key = AggregateKey("scs.fixture.source", frozen.id(label))
            return LocalStockSourceStamp(key, Revision(1), sha256(label.encodeToByteArray())).also { authorityStamps[key] = it }
        }
        val item = LocalStockItemAuthority(scope, sku, frozen.id("PRODUCT"), Revision(1), Revision(1), frozen.unit,
            frozen.scale, StockTracking.LOT, true, true, true, emptyList(), listOf(stamp("ITEM-INPUT")),
            listOf(CatalogUnit(frozen.unit, Code.exact("each"), UnitDimension.COUNT, frozen.scale)))
        val warehouseInput = LocalStockWarehouseAuthority(scope, warehouse, true, listOf(stamp("WAREHOUSE-INPUT")))
        val basis = LocalStockReceivingBasis(scope, basisId, Revision(1), LocalStockBasisKind.LOCAL_PURCHASE,
            frozen.id("SUPPLIER"), actorId, frozen.id("RECEIVING-POLICY"), now, null,
            listOf(LocalStockApprovedLine(allocationId, sku, warehouse, quantity, receivedValue, true)), listOf(stamp("BASIS-INPUT")))
        val claim = StreamedSourceObservationV2.Claim(ClaimKey("local.configuration.source", "allocation/${allocationId.value}"), basisId)
        check(claims.put(claim.key, checkNotNull(claim.ownerId)) == null)
        val request = LocalGoodsReceiptRequest(receiptId, basisId, Revision(1),
            listOf(LocalStockItemVersion(sku, rows.getValue(LocalStockRelationalProfileV2.itemKey(sku)).generation)),
            listOf(LocalGoodsReceiptLine(frozen.id("$label:line"), allocationId, frozen.id("$label:physical-source"), sku,
                warehouse, quantity, null, lot, UtcInstant.parseCanonical(frozen.unsafe(reason).text("expires_utc")),
                LocalReceiptDisposition.QUARANTINE, receivedValue)), "held-same-lot-receipt")
        return receiptPlan(StockReceivingInputs(LocalStockRelationalCommand.Receive(operationId, request, false),
            StockReceiptPreparationInput.legacy(request), basis, mapOf(sku to item), mapOf(warehouse to warehouseInput),
            emptyMap(), null, ScalarReceivingClaims(basisId, claim)), this)
    }

    override suspend fun normal(key: AggregateKey): StockRelationalRecordV2? = rows[key]
    override suspend fun document(key: AggregateKey): StoredAggregate? = documents[key]
    override suspend fun claim(key: ClaimKey): DomainId? = claims[key]
    override suspend fun current(stamp: LocalStockSourceStamp) { check(authorityStamps[stamp.key] == stamp) }
    override suspend fun absent(key: AggregateKey) { check(key !in rows && key !in documents) }
    override suspend fun arrivalV2NegativeProfile(): Boolean = true
    override suspend fun range(query: StockQueryV2, consume: suspend (StockRelationalRecordV2) -> Unit) {
        val selected = rows.values.filter { row -> when (query) {
            is StockQueryV2.SkuPositions -> (row.body as? StockRelationalBody.Position)?.value?.key?.pool?.skuId == query.skuId
            is StockQueryV2.Positions -> (row.body as? StockRelationalBody.Position)?.value?.key?.pool?.let {
                it.skuId == query.skuId && it.warehouseId == query.warehouseId } == true
            is StockQueryV2.PositionMembers -> when (val body = row.body) {
                is StockRelationalBody.Allocation -> LocalStockRelationalProfileV2.positionKey(body.value.allocation.position).id == query.positionId
                is StockRelationalBody.Hold -> body.value.hold.position?.let { LocalStockRelationalProfileV2.positionKey(it).id } == query.positionId
                else -> false
            }
            is StockQueryV2.ReservationMembers -> when (val body = row.body) {
                is StockRelationalBody.Allocation -> body.value.reservationId == query.reservationId
                is StockRelationalBody.Hold -> body.value.reservationId == query.reservationId
                else -> false
            }
            is StockQueryV2.PoolValues -> when (val body = row.body) {
                is StockRelationalBody.ValueEvent -> LocalStockRelationalProfileV2.poolKey(body.pool).id == query.poolId
                is StockRelationalBody.ValueSlice -> LocalStockRelationalProfileV2.poolKey(body.pool).id == query.poolId
                is StockRelationalBody.IssueSlice -> LocalStockRelationalProfileV2.poolKey(body.value.allocation.position.pool).id == query.poolId
                else -> false
            }
            else -> error("Query outside the frozen scalar fixture")
        } }
        val ordered = if (query is StockQueryV2.PoolValues) selected.sortedWith(compareBy<StockRelationalRecordV2> {
            when (val body = it.body) {
                is StockRelationalBody.ValueEvent -> body.ordinal
                is StockRelationalBody.ValueSlice -> body.eventOrdinal
                else -> 0L
            }
        }.thenBy { if (it.body is StockRelationalBody.ValueSlice) 1 else 0 }) else selected
        for (row in ordered) consume(row)
    }

    private fun putInitial(body: StockRelationalBody, label: String, key: AggregateKey = stockKey(body),
        command: CommandKey = LocalStockRelationalProfileV2.COMMAND, producerActor: DomainId = actorId) {
        check(rows.put(key, canonicalRow(body, frozen.operation(label), key, null, command, producerActor)) == null)
    }

    private fun producer(operation: OperationId, command: CommandKey = LocalStockRelationalProfileV2.COMMAND,
        producerActor: DomainId = actorId) = StockProducerWire(operation, command.name, command.version, producerActor,
        sha256("L1 coordinate/${operation.value}".encodeToByteArray()).value)

    private fun canonicalRow(body: StockRelationalBody, operation: OperationId, key: AggregateKey, previous: StockRelationalRecordV2?,
        command: CommandKey = LocalStockRelationalProfileV2.COMMAND, producerActor: DomainId = actorId): StockRelationalRecordV2 {
        val revision = previous?.generation?.next() ?: Revision(1)
        val wire = StockRecordV2Wire(2, StockScopeWire.from(scope), key.kind, key.id, revision, producer(operation, command, producerActor),
            previous?.let { StockPreviousWire(it.producer.operationId, it.generation, it.digest.value) }, LocalStockRelationalCodecV2.body(body))
        val payload = stockEncode(wire, StockRecordV2Wire.serializer())
        return LocalStockRelationalCodecV2.decode(scope, key, revision, StockRelationalKindsV2.schema(key.kind), payload, sha256(payload.copyBytes()))
    }

    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    private fun canonicalDocument(key: AggregateKey, body: String, previous: StoredAggregate?): StoredAggregate {
        val payload = stockRecord(scope, key, previous?.revision, producer(operationId), body, previous)
        // Passive encoded row for this test-only read boundary, never a native provenance token.
        return StoredAggregate(key, previous?.revision?.next() ?: Revision(1), stockSchema(key.kind, body), payload, sha256(payload.copyBytes()))
    }
}

/** Typed claim DATA only; every actual current/historical authority method stays unavailable. */
private class ScalarReceivingClaims(private val basisId: DomainId, private val observation: StreamedSourceObservationV2.Claim) :
    LocalStockRelationalAuthorityReader, LocalStockAuthorityReader by UnavailableLocalStockAuthority {
    override fun receivingClaims(basis: LocalStockReceivingBasis, purchase: LocalStockApprovedPurchaseOrderV1?): List<StreamedSourceObservationV2.Claim> {
        check(basis.basisId == basisId && purchase == null)
        return listOf(observation)
    }
    override suspend fun currentPurchaseOrder(view: CommandReadView, orderId: DomainId): LocalStockApprovedPurchaseOrderV1? = null
    override suspend fun retainedItem(original: RetainedStreamedCommand, skuId: DomainId): LocalStockItemAuthority = unavailable()
    override suspend fun retainedWarehouse(original: RetainedStreamedCommand, warehouseId: DomainId): LocalStockWarehouseAuthority = unavailable()
    override suspend fun retainedBasis(original: RetainedStreamedCommand, basisId: DomainId): LocalStockReceivingBasis = unavailable()
    override suspend fun retainedSerialRegistration(original: RetainedStreamedCommand, serialId: DomainId): LocalStockSerialRegistration? = null
    override suspend fun retainedCountSurplus(original: RetainedStreamedCommand, basisId: DomainId): LocalCountSurplusBasis = unavailable()
    override suspend fun retainedPurchaseOrder(original: RetainedStreamedCommand, orderId: DomainId): LocalStockApprovedPurchaseOrderV1 = unavailable()
    private fun unavailable(): Nothing = error("Native/current authority is outside this scalar fixture")
}
