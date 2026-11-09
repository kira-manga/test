package com.ukkera.pos.app.gateway.independent.foodfinancialrpcv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Frozen semantic inputs, bound to synthetic UUIDs and explicitly controlled revision DATA. */
internal object FrozenFoodFinancialRpc {
    val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    private const val relative = "qa/pos/designs/food-financial-rpc-v1"
    private val directory = generateSequence(File(".").canonicalFile) { it.parentFile }
        .map { File(it, relative) }.firstOrNull { File(it, "freeze.json").isFile }
        ?: error("Frozen FOOD financial RPC specification is required")
    val freeze = json.parseToJsonElement(File(directory, "freeze.json").readText()).jsonObject
    val cases: String
    val fixtures: JsonObject

    init {
        require(freeze.getValue("version").jsonPrimitive.content == "1.0.0")
        require(freeze.getValue("freeze_state").jsonPrimitive.content == "FROZEN")
        val files = freeze.getValue("frozen_files").jsonObject
        for (name in listOf("README.md", "cases.md", "fixtures.json")) {
            val bytes = File(directory, name).readBytes()
            val expected = files.getValue(name).jsonObject
            require(bytes.size == expected.getValue("bytes").jsonPrimitive.int)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            require(digest == expected.getValue("sha256").jsonPrimitive.content)
        }
        cases = File(directory, "cases.md").readText()
        fixtures = json.parseToJsonElement(File(directory, "fixtures.json").readText()).jsonObject
        require(fixtures.getValue("fixture_version").jsonPrimitive.content == "food-financial-rpc-v1.0.0")
    }

    fun case(id: String) { require(cases.contains("## $id — ")) }
    private fun uuid(logical: String): String = UUID.nameUUIDFromBytes(
        "food-financial-rpc/v1.0.0/$logical".toByteArray(Charsets.UTF_8)).toString()
    fun id(logical: String): DomainId = DomainId(uuid(logical))
    fun operation(logical: String): OperationId = OperationId(uuid(logical))
    val mixed: JsonObject = fixtures.getValue("MIXED").jsonObject
    val originalCash: JsonObject = mixed.getValue("original_cash_fact").jsonObject
    val actor: DomainId = id(mixed.getValue("owner").jsonPrimitive.content)
    val otherActor: DomainId = id("B")
    val order: DomainId = id(mixed.getValue("order").jsonPrimitive.content)
    val collection: DomainId = id(originalCash.getValue("collection").jsonPrimitive.content)
    val cashSession: DomainId = id(originalCash.getValue("cash_session").jsonPrimitive.content)
    val recording: OperationId = operation(originalCash.getValue("recording").jsonPrimitive.content)
    val scope = BranchScope(id("organization"), id("company"), id("R1"))
    val device = id("D1")
    val loginSession = id("S1")
    val owner = LocalClientIdentity(scope, actor, device)
    val at: UtcInstant = UtcInstant.parseCanonical("2026-11-06T13:00:00Z")

    // These are controlled runtime bindings, not revisions or credentials supplied by the freeze.
    val orderStorageRevision = Revision(41)
    val sessionDomainRevision = Revision(7)
    val collectionDomainRevision = Revision(2)
    val coveredOrderStorageRevision = Revision(43)
    val coveredCollectionStorageRevision = Revision(2)
    fun money(minor: Long) = Money(Currency.EGP, minor)
    val payable: Money = money(mixed.getValue("original_quote").jsonObject.getValue("payable_minor").jsonPrimitive.long)
    fun decimal(amount: Money): String = amount.minor.toString().padStart(3, '0').let {
        it.dropLast(2) + "." + it.takeLast(2)
    }

    val target = LocalFoodPaymentTarget(order, collection, cashSession,
        id(originalCash.getValue("attempt").jsonPrimitive.content), operation("cash-intent/AM"), recording,
        operation("reserved-resolution/AM"))
    val cashOutcome = LocalFoodCashOutcome.CashReceived(
        money(originalCash.getValue("received_minor").jsonPrimitive.long),
        money(originalCash.getValue("change_minor").jsonPrimitive.long), LocalCashEvidenceV1(id("cash-evidence/RM"), at))
    // RecordOriginal is encoded solely to represent immutable read DATA; it is never dispatched.
    val originalBodyDigest = sha256(LocalFoodPaymentCodec.encode(
        LocalFoodPaymentRequest.RecordOriginal(target, cashOutcome)).copyBytes())
    val original = LocalFoodPaymentReadResult.Original(LocalFoodPaymentOriginalSummary(target, actor, actor,
        originalBodyDigest, at, payable, LocalFoodPaymentApplicationState.PENDING_RECONCILIATION,
        LocalFoodPaymentPendingReason.AWAITING_FINANCIAL_APPLICATION, null, null), cashOutcome)

    fun intent(action: LocalAction, operationId: OperationId = operation(action.name)): LocalCommandIntent {
        fun identifier(field: LocalField, id: DomainId) = LocalScalarInput(field, LocalScalarValue.Identifier(id))
        fun revision(field: LocalField, value: Revision) = LocalScalarInput(field, LocalScalarValue.Integer(value.value))
        val values = when (action) {
            LocalAction.FOOD_BEGIN_COLLECTION -> listOf(identifier(LocalField.ORDER_ID, order),
                revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, orderStorageRevision),
                identifier(LocalField.COLLECTION_ID, collection), identifier(LocalField.SESSION_ID, cashSession),
                revision(LocalField.EXPECTED_SESSION_REVISION, sessionDomainRevision),
                LocalScalarInput(LocalField.AMOUNT, LocalScalarValue.Decimal(decimal(payable))))
            LocalAction.FOOD_FINALIZE_PAID -> listOf(identifier(LocalField.COLLECTION_ID, collection),
                revision(LocalField.EXPECTED_COLLECTION_REVISION, collectionDomainRevision),
                identifier(LocalField.ORDER_ID, order),
                revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, coveredOrderStorageRevision),
                identifier(LocalField.SALE_ID, id("paid-sale/M")))
            LocalAction.FOOD_PAYMENT_APPLY -> listOf(identifier(LocalField.FOOD_PAYMENT_RECORDING_OPERATION_ID,
                DomainId(recording.value)), identifier(LocalField.ORDER_ID, order),
                identifier(LocalField.COLLECTION_ID, collection),
                LocalScalarInput(LocalField.AMOUNT, LocalScalarValue.Decimal(decimal(payable))),
                LocalScalarInput(LocalField.FOOD_PAYMENT_ORIGINAL_BODY_DIGEST, LocalScalarValue.Text(originalBodyDigest.value)))
            else -> error("No frozen financial action binding")
        }
        return LocalCommandIntent(operationId, action, 1, null, values)
    }

    val actions = listOf(LocalAction.FOOD_BEGIN_COLLECTION, LocalAction.FOOD_PAYMENT_APPLY, LocalAction.FOOD_FINALIZE_PAID)
    fun request(intent: LocalCommandIntent): MutationRequest = when (intent.action) {
        LocalAction.FOOD_BEGIN_COLLECTION -> LocalFoodStockCodecV2.command(intent.operationId,
            LocalFoodStockRequestV2.Financial(LocalFoodFinancialRequestV1.BeginCollection(order,
                orderStorageRevision, collection, cashSession, sessionDomainRevision)))
        LocalAction.FOOD_FINALIZE_PAID -> LocalFoodStockCodecV2.command(intent.operationId,
            LocalFoodStockRequestV2.Financial(LocalFoodFinancialRequestV1.FinalizePaid(collection,
                collectionDomainRevision, id("paid-sale/M"))))
        LocalAction.FOOD_PAYMENT_APPLY -> LocalFoodPaymentCodec.command(intent.operationId,
            LocalFoodPaymentRequest.ApplyOriginal(recording))
        else -> error("No frozen canonical request binding")
    }

    fun assertOriginal(entry: StoredClientIntentV1, intent: LocalCommandIntent) {
        val expected = request(intent)
        assertEquals(owner, entry.owner)
        assertEquals(intent.operationId, entry.operationId)
        assertEquals(intent.action, entry.localAction)
        assertEquals(1, entry.formVersion)
        assertEquals(expected.command, entry.request().command)
        assertEquals(expected.payload, entry.request().payload)
        assertEquals(inputDigest(intent), entry.inputDigest)
        assertEquals(semanticDigest(scope, actor, expected.command, expected.payload), entry.semanticSha256)
        assertNull(entry.actionApprovalId)
        assertNull(entry.actionApproval)
        entry.validate(owner)
    }

    fun view(actorId: DomainId = actor) = LocalSessionView(actorId, scope, device, loginSession,
        SessionGeneration(1), LocalLoginIdentifier("synthetic-financial-owner"),
        ActivatedLocalPolicy(scope, actorId, Revision(1), listOf(SecurityAction.ORDER_READ,
            SecurityAction.INVENTORY_QUANTITY_READ, SecurityAction.COLLECTION_BEGIN,
            SecurityAction.COLLECTION_ATTEST, SecurityAction.COLLECTION_FINALIZE,
            SecurityAction.COLLECTION_FINALIZE_ZERO, SecurityAction.CASH_READ,
            SecurityAction.RETURN_RECEIPT_LOOKUP).map {
                PermissionGrant(Permission(it, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW)
            }, LocalSessionPolicy(), ThrottlePolicy()),
        MonotonicMoment(loginSession, 1_000), MonotonicMoment(loginSession, 2_000))

    fun mixedRead(): LocalFoodReadResultV1 {
        val portions = mixed.getValue("portions").jsonArray.map { input ->
            val row = input.jsonObject
            val logical = row.getValue("portion").jsonPrimitive.content
            val portionId = id(logical)
            val status = FoodPreparationStatus.valueOf(row.getValue("preparation").jsonPrimitive.content)
            val served = row.getValue("fulfillment").jsonPrimitive.content == "SERVED"
            val prepared = status != FoodPreparationStatus.NOT_SENT
            val started = status == FoodPreparationStatus.PREPARING || status == FoodPreparationStatus.READY
            val tasks = if (!prepared) emptyList() else listOf(FoodStationTask(id("task/$logical"), id("station"),
                FoodStationDeliveryMode.DISPLAY, when (status) {
                    FoodPreparationStatus.READY -> FoodStationStatus.READY
                    FoodPreparationStatus.PREPARING -> FoodStationStatus.STARTED
                    else -> FoodStationStatus.QUEUED
                }, startedAt = at.takeIf { started }, readyAt = at.takeIf { status == FoodPreparationStatus.READY }))
            val state = FoodPreparationState(portionId, Revision(if (served) 4 else if (started) 2 else if (prepared) 1 else 0),
                status, if (served) FoodFulfillmentStatus.SERVED else FoodFulfillmentStatus.NOT_HANDED_OVER, tasks,
                id("reservation/$logical").takeIf { prepared }, id("issue/$logical").takeIf { prepared },
                Revision(if (started) 2 else 1).takeIf { prepared }, at.takeIf { prepared }, servedAt = at.takeIf { served })
            LocalFoodPortionViewV1(portionId, logical, Quantity(id("portion-unit"), 1, 0), emptyList(), "", state)
        }.sortedBy { it.portionId.value }
        return LocalFoodReadResultV1(detail = LocalFoodOrderDetailV1(LocalFoodOrderSummaryV1(order, actor,
            orderStorageRevision, LocalSalesActivity.RESTAURANT, LocalFoodServiceV1(LocalSalesChannel.DINE_IN,
                "MIXED", ""), portions.size, null, null), portions, payable, false))
    }

    fun checkPayload(key: ReadKey, current: Money? = payable, missingKitchen: Boolean = false): StoredBytes {
        val food = mixedRead()
        val kitchen = if (missingKitchen) null else LocalFoodCheckKitchenObservationV1(order, orderStorageRevision,
            mixed.getValue("portions").jsonArray.count { it.jsonObject.getValue("preparation").jsonPrimitive.content == "NOT_SENT" },
            LocalFoodCheckKitchenStatusV1.UNKNOWN)
        val text = when (key) {
            LocalFoodCheckKitchenCodecV3.QUERY -> json.encodeToString(LocalFoodCheckKitchenResultV3.serializer(),
                LocalFoodCheckKitchenResultV3(food, kitchen, currentUnpaidPayable = current))
            // MIXED includes sent/served work; older versions do not claim its current payable.
            LocalFoodCheckKitchenCodecV2.QUERY -> json.encodeToString(LocalFoodCheckKitchenResultV2.serializer(),
                LocalFoodCheckKitchenResultV2(food, kitchen, currentUnpaidPayable = null))
            LocalFoodCheckKitchenCodecV1.QUERY -> json.encodeToString(LocalFoodCheckKitchenResultV1.serializer(),
                LocalFoodCheckKitchenResultV1(food, kitchen))
            else -> error("No selected kitchen version binding")
        }
        return bytes(text)
    }

    fun coveredCollection(): LocalFoodCollectionViewV1 = LocalFoodCollectionViewV1(collection, order,
        coveredCollectionStorageRevision, collectionDomainRevision, coveredOrderStorageRevision,
        orderStorageRevision, "COVERED", payable, payable, money(0), null, null, false,
        mixedRead().detail!!.portions.map { portion -> LocalFoodFinancialPortionWorkV1(portion.portionId,
            portion.quantity, portion.preparation.revision, portion.preparation.status, portion.preparation.fulfillment,
            if (portion.preparation.issueId != null) FoodStockOrigin.ISSUED_BEFORE_COLLECTION else FoodStockOrigin.RESERVED_FOR_COLLECTION,
            if (portion.preparation.issueId != null) "ISSUED" else "RESERVED", false) },
        emptyList(), money(0), emptyList(), "GENUINE_FOOD_CASH_V1")

    /** Well-formed controlled result DATA. It is not an authoritative commit/native-source claim. */
    fun recorded(intent: LocalCommandIntent): MutationReply.Recorded {
        val payload = if (intent.action == LocalAction.FOOD_PAYMENT_APPLY) LocalFoodPaymentCodec.result(
            LocalFoodPaymentResult.Applied(target, LocalFoodPaymentAction.CASH_PAID, intent.operationId, listOf(
                LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.COLLECTION, collection, Revision(2)),
                LocalFoodStorageRevisionV1(LocalFoodPaymentProfile.APPLICATION, DomainId(recording.value), Revision(2)),
                LocalFoodStorageRevisionV1(CashNonSaleStoreProfile.SESSION_KIND, cashSession, Revision(2)))))
        else {
            val begin = intent.action == LocalAction.FOOD_BEGIN_COLLECTION
            val result = LocalFoodFinancialResultV1(if (begin) "food.collection.locked" else "food.sale.finalized",
                if (begin) collection else id("paid-sale/M"), if (begin) listOf(
                    LocalFoodStorageRevisionV1(LocalFoodProfileV1.ORDER, order, orderStorageRevision.next()),
                    LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.COLLECTION, collection, Revision(1)))
                else listOf(LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.COLLECTION, collection, Revision(3)),
                    LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.SALE, id("paid-sale/M"), Revision(1)),
                    LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.ENTITLEMENT, id("paid-sale/M"), Revision(1))), payable)
            bytes(json.encodeToString(LocalFoodFinancialResultV1.serializer(), result))
        }
        return MutationReply.Recorded(intent.operationId, actor, true, "committed", payload, Revision(1),
            JournalSequence(11 + actions.indexOf(intent.action).toLong()), at, false)
    }

    fun bytes(text: String) = StoredBytes(text.encodeToByteArray())
    fun data(payload: StoredBytes) = ReadReply.Data(payload, Revision(51), at)
}

internal fun LocalCommandIntent.financialChange(field: LocalField, value: LocalScalarValue): LocalCommandIntent =
    LocalCommandIntent(operationId, action, formVersion, reference,
        values.map { if (it.field == field) LocalScalarInput(field, value) else it }, lineField, lines, arrivalEvidence)
