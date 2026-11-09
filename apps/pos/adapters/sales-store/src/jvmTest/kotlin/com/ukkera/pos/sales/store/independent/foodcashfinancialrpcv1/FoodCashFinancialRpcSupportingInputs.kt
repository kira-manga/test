package com.ukkera.pos.sales.store.independent.foodcashfinancialrpcv1

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.UnavailableLocalStockAuthority
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.assertEquals

/** FFRC v1.0.0 semantic bindings only. Deterministic UUIDs, revisions and times below
 * describe unqualified supporting inputs, never admitted identities or native originals. */
internal object FoodCashFinancialRpcSupportingInputs {
    private val directory = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/food-financial-rpc-v1") }
        .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
        ?: error("Frozen FOOD financial RPC inputs are unavailable")
    private val freeze = Json.parseToJsonElement(Files.readString(directory.resolve("freeze.json"))).jsonObject.also {
        assertEquals("food-financial-rpc", it.text("design"))
        assertEquals("1.0.0", it.text("version"))
        assertEquals("FROZEN", it.text("freeze_state"))
    }
    private val fixtureBytes = Files.readAllBytes(directory.resolve("fixtures.json"))
    val root = Json.parseToJsonElement(fixtureBytes.decodeToString()).jsonObject.also {
        assertEquals("food-financial-rpc-v1.0.0", it.text("fixture_version"))
        val frozen = freeze.getValue("frozen_files").jsonObject.getValue("fixtures.json").jsonObject
        assertEquals(frozen.getValue("bytes").jsonPrimitive.int, fixtureBytes.size)
        assertEquals(frozen.text("sha256"), sha256(fixtureBytes).value)
    }
    val mixed = root.getValue("MIXED").jsonObject
    val originalCash = mixed.getValue("original_cash_fact").jsonObject
    val actor = id("A")
    val otherActor = id("B")
    val scope = BranchScope(id("organization"), id("company"), id("R1"))
    val order = id(mixed.text("order"))
    val collection = id(originalCash.text("collection"))
    val session = id(originalCash.text("cash_session"))
    val sale = id("M-paid-document")
    val portions = mixed.getValue("portions").jsonArray.map { id(it.jsonObject.text("portion")) }
    val batches = mixed.getValue("saved_batches").jsonArray.map { id(it.jsonObject.text("batch")) }
    val recording = operation(originalCash.text("recording"))
    val application = operation("Apply-RM")
    val target = LocalFoodPaymentTarget(order, collection, session, id(originalCash.text("attempt")),
        operation("Start-AM"), recording, operation("Resolution-AM"))
    val orderRevision = Revision(7)
    val collectionRevision = Revision(3)
    val sessionRevision = Revision(1)
    val at = UtcInstant.parseCanonical("2026-11-06T12:00:00Z")
    val evidence = LocalCashEvidenceV1(id("M-cash-evidence"), at)
    val payable = egp(mixed.getValue("original_quote").jsonObject.long("payable_minor"))
    val cashOutcome = LocalFoodCashOutcome.CashReceived(egp(originalCash.long("received_minor")),
        egp(originalCash.long("change_minor")), evidence)
    val originalBody = LocalFoodPaymentCodec.encode(LocalFoodPaymentRequest.RecordOriginal(target, cashOutcome))
    val begin = LocalFoodStockRequestV2.Financial(LocalFoodFinancialRequestV1.BeginCollection(order,
        orderRevision, collection, session, sessionRevision))
    val finalize = LocalFoodStockRequestV2.Financial(LocalFoodFinancialRequestV1.FinalizePaid(collection,
        collectionRevision, sale))
    val apply = LocalFoodPaymentRequest.ApplyOriginal(recording)
    val service = LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "Synthetic M")
    val selection = LocalFoodPortionSelectionV1(portions[3], id("product"), id("recipe"), Revision(1),
        id("choice"), emptyList(), Quantity(id("portion-unit"), 1, 0))
    val add = LocalFoodRequestV1.Add(order, orderRevision, Revision(1), listOf(selection))
    val draft = LocalFoodDraftCreateV1(order, LocalSalesActivity.RESTAURANT, service)

    fun id(label: String): DomainId = DomainId(UUID.nameUUIDFromBytes(
        "food-financial-rpc-v1-support:$label".encodeToByteArray()).toString())
    fun operation(label: String): OperationId = OperationId(id(label).value)
    fun egp(minor: Long): Money = Money(Currency.EGP, minor)
    fun bytes(text: String): StoredBytes = StoredBytes(text.encodeToByteArray())
    fun commandContext(key: CommandKey, body: StoredBytes, actor: DomainId = this.actor,
        operation: OperationId = application, scope: BranchScope = this.scope,
        digest: PayloadDigest = semanticDigest(scope, actor, key, body)): CommandContext =
        CommandContext(scope, actor, operation, key, digest)
    fun readContext(key: ReadKey, body: StoredBytes, actor: DomainId = this.actor,
        scope: BranchScope = this.scope): ReadContext = ReadContext(scope, actor, key,
        queryDigest(scope, actor, key, body))

    fun excludedStockBodies(): List<LocalFoodStockRequestV2> = listOf(
        LocalFoodStockRequestV2.ActivityProtected(draft), LocalFoodStockRequestV2.CreateDraft(draft),
        LocalFoodStockRequestV2.ActivityProtectedAdd(add), LocalFoodStockRequestV2.ActivityProtectedQuotedAdd(add),
        LocalFoodStockRequestV2.Preparation(LocalFoodRequestV1.Create(order, LocalSalesActivity.RESTAURANT,
            service, Revision(1), listOf(selection))),
        LocalFoodStockRequestV2.Preparation(add),
        LocalFoodStockRequestV2.Preparation(LocalFoodRequestV1.Send(order, orderRevision, listOf(portions[3]))),
        LocalFoodStockRequestV2.Financial(LocalFoodFinancialRequestV1.SendReserved(order, orderRevision,
            collection, collectionRevision, listOf(portions[3]))),
        LocalFoodStockRequestV2.Financial(LocalFoodFinancialRequestV1.PaidPickup(order, orderRevision,
            collection, portions[1], Revision(3))),
        LocalFoodStockRequestV2.Financial(LocalFoodFinancialRequestV1.CancelWholeUnsent(collection,
            collectionRevision, orderRevision, id("refund"), id("reason"))),
        LocalFoodStockRequestV2.Financial(LocalFoodFinancialRequestV1.CloseResolvedWork(order,
            orderRevision, collection, collectionRevision)),
        LocalFoodStockRequestV2.Financial(LocalFoodFinancialRequestV1.RecordQualityClaim(id("claim"),
            LocalFoodClaimFactsV1(LocalFoodClaimKindV1.QUALITY, claimedAt = at,
                reason = "Synthetic claim", evidence = evidence))),
    )

    fun excludedPaymentBodies(): List<LocalFoodPaymentRequest> = listOf(
        LocalFoodPaymentRequest.StartCash(collection, orderRevision, collectionRevision, session,
            sessionRevision, target.attemptId, payable, recordingOperationId = recording,
            resolutionOperationId = target.resolutionOperationId, masterAllocationId = id("master-allocation"),
            clientAllocationId = id("client-allocation")),
        LocalFoodPaymentRequest.RecordOriginal(target, cashOutcome),
        LocalFoodPaymentRequest.RecordResolution(target, sha256(originalBody.copyBytes()), otherActor,
            LocalFoodCashOutcome.NotPaid(evidence, "synthetic-not-paid")),
        LocalFoodPaymentRequest.ApplyResolution(target.resolutionOperationId),
    )

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
}

internal class FoodCashFinancialRpcSupportingFixture {
    val source = UncalledFoodCashFinancialSource()
    val stock = LocalStockRepository()
    val stockHandler = LocalFoodStockServices.handler(stock, UnavailableLocalStockAuthority, source.value)
    val paymentHandler = LocalFoodPaymentHandler(stock, source.value)
    val foodRead = LocalFoodReadHandler(source.value, stock)
    val kitchenReads: List<MasterReadHandler> = listOf(LocalFoodCheckKitchenReadHandler(source.value, stock),
        LocalFoodCheckKitchenReadHandlerV2(source.value, stock), LocalFoodCheckKitchenReadHandlerV3(source.value, stock))
    val financialRead = LocalFoodFinancialReadHandler(source.value, stock)
    val paymentRead = LocalFoodPaymentReadHandler(stock, source.value)
}

/** Fails every dependency call: no accepting catalog, current authority or native source. */
internal class UncalledFoodCashFinancialSource {
    var calls: Int = 0
        private set
    val value: TransactionFoodStockSource = Proxy.newProxyInstance(TransactionFoodStockSource::class.java.classLoader,
        arrayOf(TransactionFoodStockSource::class.java)) { _, _, _ ->
        calls++
        throw AssertionError("No qualified FOOD source is supplied by this supporting fixture")
    } as TransactionFoodStockSource
}
