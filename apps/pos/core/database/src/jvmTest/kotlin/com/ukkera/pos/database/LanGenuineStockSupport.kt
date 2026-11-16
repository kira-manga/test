package com.ukkera.pos.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import dev.p2pkit.rpc.RpcFailure
import dev.p2pkit.rpc.RpcFailureKind
import dev.p2pkit.rpc.RpcExecutionEvidence
import kotlinx.coroutines.delay

/** Self-authored, NOT independent. New, separately mapped real M/C1/C2 stock support.
 * Genuine signed catalog/opening and actual protected LAN_BUSINESS commands only.
 * Zero-total sales are not frozen positive paid-retail cases. Executor files retain
 * requests/results; they are not production UI journals or native force capabilities. */
internal object LanGenuineStockSupport {
    const val ENABLE_PROPERTY = "ukkera.lan.stockSupporting"
    const val MAPPING_VERSION = "lan-stock-supporting-v2"
    val clientActions = setOf(SecurityAction.CASH_OPEN, SecurityAction.ORDER_CREATE,
        SecurityAction.ORDER_EDIT, SecurityAction.ORDER_READ, SecurityAction.COLLECTION_BEGIN,
        SecurityAction.COLLECTION_FINALIZE_ZERO, SecurityAction.INVENTORY_COST_READ,
        SecurityAction.OPERATION_RESULT_RECOVERY)
    val masterAdminActions = setOf(SecurityAction.CATALOG_ACTIVATE,
        SecurityAction.WAREHOUSE_CONFIGURE, SecurityAction.OPENING_INVENTORY_APPROVE)
    private val roles = listOf("client1", "client2")
    private val at = UtcInstant.parseCanonical("2026-10-23T12:00:00Z")
    private fun money(value: Long) = Money(Currency.EGP, value)
    private fun requireMapping(exchange: LanFixtureExchange) {
        check(java.lang.Boolean.getBoolean(ENABLE_PROPERTY))
        val bytes = checkNotNull(exchange.read("executor.frozen-input-mapping"))
        val mapping = try { Json.parseToJsonElement(bytes.decodeToString()).jsonObject } finally { bytes.fill(0) }
        check(mapping["id"]?.jsonPrimitive?.content == "POS-LAN-GENUINE-STOCK-SUPPORTING-EXECUTOR" &&
            mapping["version"]?.jsonPrimitive?.content == "1.0.2" &&
            mapping["supporting_extension"]?.jsonPrimitive?.content == MAPPING_VERSION &&
            mapping["status"]?.jsonPrimitive?.content == "FROZEN_BEFORE_FIRST_EXECUTION")
    }

    suspend fun prepareMaster(exchange: LanFixtureExchange, endpoint: LocalMasterClient,
        credentials: LocalGatewayCredentials, publications: LocalCompanyBusinessPublications,
        scope: BranchScope, actors: Map<String, DomainId>, root: Path,
        headLogin: String, headPassword: () -> CharArray) {
        requireMapping(exchange)
        check(actors.keys == roles.toSet() && actors.values.toSet().size == 2)
        val evidence = Evidence(root.resolve("stock-executor-inputs"))
        suspend fun dispatch(request: MutationRequest) {
            evidence.request(request)
            evidence.result(request.operationId.value + ".recorded", applied(endpoint.execute(credentials, request)))
        }
        suspend fun configure(body: LocalConfigurationRequestV1) = dispatch(MutationRequest(OperationId.random(),
            LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(body)))
        val each = DomainId.random(); val sku = DomainId.random(); val warehouse = DomainId.random()
        exchange.putText("stock.unit", each.value); exchange.putText("stock.sku", sku.value)
        exchange.putText("stock.warehouse", warehouse.value)
        dispatch(LocalStockRelationalCommandsV2.initializeFresh(OperationId.random(), DomainId.random(), "synthetic.lan.stock.opening"))
        val published = LocalPublishedSkuV1(CompanyScope(scope.organizationId, scope.companyId), DomainId.random(), sku,
            DomainId.random(), Revision(1), Revision(1), LocalNamesV1("Synthetic LAN zero-total SKU", "صنف شبكة اختبار"),
            "SYNTHETIC-LAN-STOCK", emptyList(), each, 0, LocalTrackingV1.QUANTITY, LocalFulfillmentV1.RETAIL,
            false, true, true, true, emptyList(), listOf(LocalPriceV1(DomainId.random(), Revision(1), null, 0,
                Quantity(each, 1, 0), effectiveFrom = at, unitPrice = UnitPrice.parse(Currency.EGP, "0"),
                inclusion = TaxInclusion.EXCLUSIVE,
                tax = TaxSnapshot(DomainId.random(), Revision(1), "SYNTHETIC_ZERO", PercentageRate.parse("0")))),
            unitDefinitions = listOf(CatalogUnit(each, Code.exact("EA"), UnitDimension.COUNT, 0)))
        val proposal = CompanyBusinessPublicationProposal(published.company, CompanyBusinessPublicationKind.CATALOG_SKU_V1,
            published.publicationId, sku, LocalConfigurationCodec.canonicalPublication(published))
        val action = publications.prepare(proposal).value("actual stock publication preparation")
        val review = publications.review(action.originalActionId).value("actual stock publication review")
        val password = headPassword()
        val signed = try { publications.approve(review, headLogin, password).value("actual named-head stock approval").readNativeCompanySku() }
            finally { password.fill('\u0000') }
        configure(LocalConfigurationRequestV1.InstallCompanySku(signed))
        configure(LocalConfigurationRequestV1.ConfigureWarehouse(null,
            LocalWarehouseV1(warehouse, LocalNamesV1("Synthetic LAN stock warehouse", "مخزن شبكة اختبار"), true)))
        configure(LocalConfigurationRequestV1.ConfigureSalesRoute(null, LocalSalesRouteV1(DomainId.random(),
            LocalSalesActivity.CLOTHING, LocalSalesChannel.COUNTER, warehouse, true)))
        val basis = DomainId.random(); val allocation = DomainId.random()
        configure(LocalConfigurationRequestV1.ApproveReceiving(LocalReceivingApprovalV1(basis, LocalBasisKindV1.OPENING,
            null, at, null, listOf(LocalApprovedStockLineV1(allocation, sku, warehouse,
                Quantity(each, 12, 0), money(48_000), false)))))
        dispatch(LocalStockRelationalCommandsV2.opening(OperationId.random(), LocalGoodsReceiptRequest(DomainId.random(), basis,
            Revision(0), listOf(LocalStockItemVersion(sku, null)), listOf(LocalGoodsReceiptLine(DomainId.random(), allocation,
                DomainId.random(), sku, warehouse, Quantity(each, 12, 0), null, null, null,
                LocalReceiptDisposition.RELEASED, money(48_000))), "synthetic.lan.stock.opening")))
        for (role in roles) {
            val drawer = DomainId.random(); val session = DomainId.random()
            exchange.putText("$role.stock.drawer", drawer.value); exchange.putText("$role.stock.session", session.value)
            for (body in listOf(LocalCashRequestV1.ConfigureCustodian(actors.getValue(role)),
                LocalCashRequestV1.ConfigureDrawer(drawer, "Synthetic $role stock drawer", Currency.EGP))) {
                dispatch(MutationRequest(OperationId.random(), LocalSalesStoreProfile.CASH_COMMAND, LocalCashCommandCodec.encode(body)))
            }
        }
        observeStock(endpoint, credentials, sku, each, 12, 0, 48_000)
        exchange.putText("stock.provisioned", MAPPING_VERSION)
    }

    suspend fun runMaster(exchange: LanFixtureExchange, endpoint: LocalMasterClient,
        credentials: LocalGatewayCredentials, root: Path, scope: BranchScope, actors: Map<String, DomainId>) {
        requireMapping(exchange)
        val sku = DomainId(exchange.awaitText("stock.sku")); val each = DomainId(exchange.awaitText("stock.unit"))
        roles.forEach { exchange.await("$it.stock.opened") }
        exchange.putText("stock.round1.start", "two-real-counted-openings-and-staged-originals")
        roles.forEach { exchange.await("$it.stock.round1.begun") }
        observeStock(endpoint, credentials, sku, each, 12, 2, 48_000)
        exchange.putText("stock.round1.finalize", "two-real-reservations")
        roles.forEach { exchange.await("$it.stock.round1.done") }
        observeStock(endpoint, credentials, sku, each, 10, 0, 40_000)
        exchange.putText("stock.round1.verified", "onhand10-reserved0-value40000")
        roles.forEach { exchange.await("$it.stock.last.ready") }
        exchange.putText("stock.last.begin", "both-exact-last-stock-originals-retained")
        roles.forEach { exchange.await("$it.stock.last.result") }
        check(roles.count { exchange.read("$it.stock.last.won")?.decodeToString() == "true" } == 1)
        observeStock(endpoint, credentials, sku, each, 10, 10, 40_000)
        exchange.putText("stock.last.finalize", "exactly-one-real-last-stock-reservation")
        roles.forEach { exchange.await("$it.stock.last.done") }
        observeStock(endpoint, credentials, sku, each, 0, 0, 0)
        verifyCardinality(exchange, root, scope, actors)
        exchange.putText("stock.verified", "three-actual-zero-sales-stock0-value0-cash100000-each-exact-originals")
        roles.forEach { exchange.await("$it.stock.finished") }
    }

    suspend fun runClient(role: String, root: Path, exchange: LanFixtureExchange,
        client: LocalMasterClientResultAcceptance, credentials: LocalGatewayCredentials, scope: BranchScope, actor: DomainId) {
        requireMapping(exchange); check(role in roles)
        check(exchange.awaitText("stock.provisioned") == MAPPING_VERSION)
        val each = DomainId(exchange.awaitText("stock.unit")); val sku = DomainId(exchange.awaitText("stock.sku"))
        val drawer = DomainId(exchange.awaitText("$role.stock.drawer")); val cash = DomainId(exchange.awaitText("$role.stock.session"))
        val evidence = Evidence(root.resolve(role).resolve("stock-executor-inputs"))
        val operations = mutableListOf<OperationId>()
        // The transport's MayHaveExecuted result is unresolved. Use the exact forced
        // original identity through the actual protected retained-result RPC, without
        // a new operation, a second effect or a longer RPC/native deadline.
        suspend fun lookupOriginal(identity: OperationIdentity, label: String): LookupReply {
            for (attempt in 0 until 3) {
                try { return client.lookupAccepting(credentials, identity) { evidence.result(label, it) } }
                catch (failure: RpcFailure) {
                    if (attempt == 2 || failure.kind !in setOf(RpcFailureKind.DeadlineExceeded, RpcFailureKind.UnknownOutcome) ||
                        failure.executionEvidence !in setOf(RpcExecutionEvidence.MayHaveExecuted, RpcExecutionEvidence.HandlerFinished)) throw failure
                    evidence.uncertain(identity.operationId.value + ".read$attempt.uncertain." + label, failure.executionEvidence)
                    delay(250)
                }
            }
            error("Exact original read recovery exhausted")
        }
        suspend fun executeOriginal(request: MutationRequest, label: String): MutationReply.Recorded {
            val normalized = when (request.command) {
                LocalSalesStoreProfile.CASH_COMMAND -> LocalCashCommandCodec.encode(LocalCashCommandCodec.decode(request.payload))
                StockStreamingCommandsV2.COLLECTION -> LocalCollectionStockCodecV2.normalize(request.payload)
                else -> error("Exact stock supporting command required")
            }
            check(normalized == request.payload)
            val identity = OperationIdentity(scope.branchId, request.operationId, actor, 1,
                semanticDigest(scope, actor, request.command, normalized).value)
            return try {
                client.executeAccepting(credentials, request) { evidence.result(label, it) } as? MutationReply.Recorded
                    ?: error("Actual stock command not recorded")
            } catch (failure: RpcFailure) {
                if (failure.kind != RpcFailureKind.DeadlineExceeded ||
                    failure.executionEvidence !in setOf(RpcExecutionEvidence.MayHaveExecuted, RpcExecutionEvidence.HandlerFinished)) throw failure
                evidence.uncertain(request.operationId.value + "." + label + ".uncertain", failure.executionEvidence)
                val recovered = lookupOriginal(identity, label + ".recovered")
                val recorded = (recovered as? LookupReply.Found)?.result
                    ?: error("Exact unresolved stock original was not recovered: ${recovered::class.simpleName}")
                check(recorded.replayed && recorded.operationId == request.operationId && recorded.originalActorId == actor)
                recorded
            }
        }
        suspend fun dispatch(request: MutationRequest, retained: Boolean = false): MutationReply.Recorded {
            if (!retained) evidence.request(request)
            val reply = executeOriginal(request, request.operationId.value + ".accepted")
            operations += request.operationId
            return reply
        }
        fun command(body: LocalCollectionStockRequestV2) = LocalCollectionStockCodecV2.command(OperationId.random(), body)
        fun selection(units: Long) = LocalOrderSelectionV1(LocalSalesActivity.CLOTHING, LocalSalesChannel.COUNTER,
            Currency.EGP, Revision(0), listOf(LocalOrderLineV1(DomainId.random(), DomainId.random(), sku, Quantity(each, units, 0))))
        suspend fun repeatAndLookup(request: MutationRequest, first: MutationReply.Recorded) {
            repeat(3) { index ->
                val again = executeOriginal(request, request.operationId.value + ".repeat$index")
                sameOriginal(first, again)
            }
            val identity = OperationIdentity(scope.branchId, request.operationId, actor, 1,
                semanticDigest(scope, actor, request.command, LocalCollectionStockCodecV2.normalize(request.payload)).value)
            val lookup = lookupOriginal(identity, request.operationId.value + ".lookup")
            sameOriginal(first, (lookup as? LookupReply.Found)?.result ?: error("Actual original lookup unavailable"))
        }
        val open = MutationRequest(OperationId.random(), LocalSalesStoreProfile.CASH_COMMAND, LocalCashCommandCodec.encode(
            LocalCashRequestV1.Open(cash, drawer, Revision(0), Revision(0), BusinessDate.parseCanonical("2026-11-15"),
                DomainId.random(), money(100_000), LocalCashEvidenceV1(DomainId.random(), at))))
        applied(dispatch(open))
        val order = DomainId.random(); val collection = DomainId.random(); val reservation = DomainId.random()
        applied(dispatch(command(LocalCollectionStockRequestV2.CreateOrder(order, selection(1)))))
        val changedSelection = selection(1)
        applied(dispatch(command(LocalCollectionStockRequestV2.ReplaceUnlockedOrder(order, Revision(0), changedSelection))))
        val stale = command(LocalCollectionStockRequestV2.ReplaceUnlockedOrder(order, Revision(0), changedSelection))
        val refused = dispatch(stale)
        check(!refused.committed && LocalSalesResultCodec.decode(refused.result) == LocalSalesResultV1.Rejected(SalesRule.REVISION_CONFLICT))
        repeatAndLookup(stale, refused)
        check(readOrder(client, credentials, order).let { it.ownerId == actor && it.revision == Revision(1) && it.collectionId == null && it.saleId == null })
        val begin = command(LocalCollectionStockRequestV2.Begin(order, Revision(1), cash, Revision(0), collection, reservation))
        evidence.request(begin)
        exchange.putText("$role.stock.opened", "actual-own-opening100000-and-stale-CAS-refused-without-stock-effects")
        exchange.await("stock.round1.start")
        applied(dispatch(begin, true))
        exchange.putText("$role.stock.round1.begun", "own-real-reservation")
        exchange.await("stock.round1.finalize")
        val sale = DomainId.random()
        val finalBody = LocalCollectionStockRequestV2.Finalize(order, Revision(2), collection, Revision(0), Revision(1), sale, DomainId.random())
        val finalize = command(finalBody)
        val result = applied(dispatch(finalize))
        repeatAndLookup(finalize, result)
        val changed = LocalCollectionStockCodecV2.command(finalize.operationId, finalBody.copy(issueId = DomainId.random()))
        evidence.request(changed, ".changed-body")
        check(client.execute(credentials, changed) is MutationReply.IdentityConflict)
        check(readOrder(client, credentials, order).let { it.ownerId == actor && it.saleId == sale })
        observeCash(client, credentials, cash, actor, drawer)
        exchange.putText("$role.stock.round1.done", "own-finalized-zero-total-original-and-one-sale")
        exchange.await("stock.round1.verified")
        val lastOrder = DomainId.random(); val lastCollection = DomainId.random(); val lastReservation = DomainId.random()
        applied(dispatch(command(LocalCollectionStockRequestV2.CreateOrder(lastOrder, selection(10)))))
        val session = readCash(client, credentials, cash)
        val lastBegin = command(LocalCollectionStockRequestV2.Begin(lastOrder, Revision(0), cash, session.revision, lastCollection, lastReservation))
        evidence.request(lastBegin)
        exchange.putText("$role.stock.last.ready", "last-ten-unit-original-forced-before-shared-start")
        exchange.await("stock.last.begin")
        val last = dispatch(lastBegin, true)
        if (!last.committed) check(LocalSalesResultCodec.decode(last.result) == LocalSalesResultV1.Unavailable(LocalSalesUnavailable.STOCK_REQUIRED))
        exchange.putText("$role.stock.last.won", last.committed.toString())
        exchange.putText("$role.stock.last.result", "actual-last-stock-result")
        exchange.await("stock.last.finalize")
        if (last.committed) {
            val lastSale = DomainId.random()
            val request = command(LocalCollectionStockRequestV2.Finalize(lastOrder, Revision(1), lastCollection,
                Revision(0), Revision(1), lastSale, DomainId.random()))
            val final = applied(dispatch(request)); repeatAndLookup(request, final)
            check(readOrder(client, credentials, lastOrder).saleId == lastSale)
        } else check(readOrder(client, credentials, lastOrder).let { it.collectionId == null && it.saleId == null })
        repeatAndLookup(lastBegin, last)
        observeCash(client, credentials, cash, actor, drawer)
        exchange.putText("$role.stock.recorded-operations", operations.joinToString("\n") { it.value })
        exchange.putText("$role.stock.last.done", "one-winner-no-overissue-no-cash-change")
        exchange.await("stock.verified")
        observeStock(client, credentials, sku, each, 0, 0, 0)
        exchange.putText("$role.stock.finished", "exact-stock-value-cash-and-original-results")
    }

    private suspend fun readOrder(client: LocalMasterClient, credentials: LocalGatewayCredentials, id: DomainId) =
        LocalOrderReadCodecV2.decode((client.read(credentials, LocalOrderReadCodecV2.request(LocalOrderQueryV1.Order(id))) as? ReadReply.Data
            ?: error("Actual own order unavailable")).payload).orders.single().order
    private suspend fun readCash(client: LocalMasterClient, credentials: LocalGatewayCredentials, id: DomainId) =
        (LocalCashReadCodec.decode((client.read(credentials, LocalCashReadCodec.request(LocalCashQueryV1.Session(id))) as? ReadReply.Data
            ?: error("Actual own cash unavailable")).payload) as? LocalCashReadResultV1.Session)?.session ?: error("Actual cash projection missing")
    private suspend fun observeCash(client: LocalMasterClient, credentials: LocalGatewayCredentials, id: DomainId, actor: DomainId, drawer: DomainId) {
        val cash = readCash(client, credentials, id)
        check(cash.custodianId == actor && cash.drawerId == drawer && cash.expectedCash == money(100_000) &&
            cash.opening == money(100_000) && cash.payIns == money(0) && cash.payOuts == money(0) &&
            cash.received == money(0) && cash.change == money(0) && cash.refunds == money(0) &&
            cash.externalCollections == money(0) && cash.externalRefunds == money(0) && cash.safeDrops == money(0) &&
            cash.reservedCashOut == money(0) && cash.pendingAttemptIds.isEmpty() && cash.pendingTransferIds.isEmpty())
    }
    private suspend fun observeStock(client: LocalMasterClient, credentials: LocalGatewayCredentials,
        sku: DomainId, each: DomainId, units: Long, reserved: Long, value: Long) {
        val quantities = LocalStockQueries.decodeQuantities((client.read(credentials, LocalStockCommands.quantities(sku)) as? ReadReply.Data
            ?: error("Actual stock quantities unavailable")).payload).rows.single()
        val costs = LocalStockQueries.decodeCosts((client.read(credentials, LocalStockCommands.costs(sku)) as? ReadReply.Data
            ?: error("Actual stock value unavailable")).payload).rows.single()
        check(quantities.onHand == Quantity(each, units, 0) && quantities.reserved == Quantity(each, reserved, 0) &&
            quantities.available == Quantity(each, units - reserved, 0) && costs.carryingValue == money(value))
    }
    private suspend fun verifyCardinality(exchange: LanFixtureExchange, root: Path, scope: BranchScope, actors: Map<String, DomainId>) {
        val paths = Files.walk(root).use { files -> files.filter { it.fileName.toString() == "master.db" }.toList() }
        check(paths.size == 1)
        val path = paths.single().toRealPath(); check(path.startsWith(root.toRealPath()))
        val originals = roles.associateWith { exchange.awaitText("$it.stock.recorded-operations").lines() }
        check(originals.values.flatten().let { it.size == it.toSet().size })
        BundledSQLiteDriver().open(path.toString(), SQLITE_OPEN_READONLY or SQLITE_OPEN_NOFOLLOW).use { raw ->
            for ((role, operations) in originals) for (operation in operations) {
                for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent"))
                    raw.prepare("SELECT COUNT(*) FROM $table WHERE scope=? AND operationId=?").use {
                        it.bindText(1, scope.storageKey()); it.bindText(2, operation)
                        check(it.step() && it.getLong(0) == 1L && !it.step())
                    }
                raw.prepare("SELECT actorId,originalActorId FROM audit_event WHERE scope=? AND operationId=?").use {
                    it.bindText(1, scope.storageKey()); it.bindText(2, operation)
                    check(it.step() && it.getText(0) == actors.getValue(role).value && it.getText(1) == actors.getValue(role).value && !it.step())
                }
            }
        }
    }
    private fun applied(reply: MutationReply): MutationReply.Recorded {
        check(reply is MutationReply.Recorded && reply.committed) { "Actual stock preparation/commit unavailable: ${reply::class.simpleName}" }
        return reply
    }
    private fun sameOriginal(first: MutationReply.Recorded, again: MutationReply.Recorded) {
        check(again.replayed && first.operationId == again.operationId && first.originalActorId == again.originalActorId &&
            first.committed == again.committed && first.code == again.code && first.result == again.result &&
            first.authorityEpoch == again.authorityEpoch && first.sequence == again.sequence && first.recordedAt == again.recordedAt)
    }
    private fun <T> LocalCompanyAuthorityResult<T>.value(stage: String): T = when (this) {
        is LocalCompanyAuthorityResult.Value -> value
        is LocalCompanyAuthorityResult.Unavailable -> error("$stage unavailable: $reason")
    }
    private class Evidence(root: Path) {
        private val files = LanPersistentTestFiles(root)
        fun request(request: MutationRequest, suffix: String = "") = retain(request.operationId.value + suffix + ".request") {
            writeUTF(request.operationId.value); writeUTF(request.command.name); writeInt(request.command.version)
            val payload = request.payload.copyBytes()
            try { writeInt(payload.size); write(payload) } finally { payload.fill(0) }
        }
        fun uncertain(label: String, evidence: RpcExecutionEvidence) = retain(label) {
            writeUTF("DeadlineExceeded/${evidence.name}; original preserved, no resubmit")
        }
        fun result(label: String, result: MutationReply.Recorded) = retain(label) {
            writeUTF(result.operationId.value); writeUTF(result.originalActorId.value); writeBoolean(result.committed)
            writeUTF(result.code); writeLong(result.authorityEpoch.value); writeLong(result.sequence.value)
            writeUTF(result.recordedAt.canonicalString()); writeBoolean(result.replayed)
            val payload = result.result.copyBytes()
            try { writeInt(payload.size); write(payload) } finally { payload.fill(0) }
        }
        private fun retain(label: String, body: DataOutputStream.() -> Unit) {
            val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { it.body() } }.toByteArray()
            try { files.locked { files.create(files.name(label), bytes) } } finally { bytes.fill(0) }
        }
    }
}
