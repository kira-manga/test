package com.ukkera.pos.app.independent.foodfinancialrpcv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*

/** Frozen semantic values with synthetic UI-only source/revision DATA; no native original is manufactured. */
internal object FrozenFoodFinancialUi {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private const val relative = "qa/pos/designs/food-financial-rpc-v1"
    private val directory = generateSequence(File(".").canonicalFile) { it.parentFile }
        .map { File(it, relative) }.firstOrNull { File(it, "freeze.json").isFile }
        ?: error("Frozen FOOD financial RPC specification is required")
    private val freeze = json.parseToJsonElement(File(directory, "freeze.json").readText()).jsonObject
    private val cases: String
    private val fixtures: JsonObject
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
    private fun uuid(logical: String) = UUID.nameUUIDFromBytes(
        "food-financial-rpc/v1.0.0/$logical".toByteArray(Charsets.UTF_8)).toString()
    fun id(logical: String) = DomainId(uuid(logical))
    fun operation(logical: String) = OperationId(uuid(logical))
    private val mixed = fixtures.getValue("MIXED").jsonObject
    private val cash = mixed.getValue("original_cash_fact").jsonObject
    val actor = id(mixed.getValue("owner").jsonPrimitive.content)
    val otherActor = id("B")
    val company = id("company")
    val branch = id("R1")
    val order = id(mixed.getValue("order").jsonPrimitive.content)
    val collection = id(cash.getValue("collection").jsonPrimitive.content)
    val cashSession = id(cash.getValue("cash_session").jsonPrimitive.content)
    val recording = operation(cash.getValue("recording").jsonPrimitive.content)
    val sale = id("paid-sale/M")
    val payable = Money(Currency.EGP,
        mixed.getValue("original_quote").jsonObject.getValue("payable_minor").jsonPrimitive.long)
    // Explicit bounded runtime DATA. These do not claim frozen revisions or genuine retained source authority.
    val orderStorageRevision = Revision(41)
    val sessionDomainRevision = Revision(7)
    val collectionDomainRevision = Revision(2)
    val coveredOrderStorageRevision = Revision(43)
    val bodyDigest = PayloadDigest("c".repeat(64))
    private val at = UtcInstant.parseCanonical("2026-11-06T13:00:00Z")
    val actions = listOf(LocalAction.FOOD_BEGIN_COLLECTION, LocalAction.FOOD_PAYMENT_APPLY, LocalAction.FOOD_FINALIZE_PAID)
    fun text(en: String, ar: String = en) = LocalText(en, ar)
    fun display(mode: LocalSessionWorkspaceMode = LocalSessionWorkspaceMode.FOOD_CASH_FINANCIAL_ONLY,
        areas: Set<AppArea> = setOf(AppArea.ORDERS)) = SessionDisplayContext(actor, company, branch,
        "Synthetic financial actor", "Synthetic company", "Synthetic branch", areas, workspaceMode = mode)
    private fun decimal(amount: Money) = amount.minor.toString().padStart(3, '0').let {
        it.dropLast(2) + "." + it.takeLast(2)
    }
    fun query(action: LocalAction): LocalFoodUiQuery = when (action) {
        LocalAction.FOOD_BEGIN_COLLECTION -> LocalFoodUiQuery.Check(order)
        LocalAction.FOOD_FINALIZE_PAID -> LocalFoodUiQuery.Collection(collection)
        LocalAction.FOOD_PAYMENT_APPLY -> LocalFoodUiQuery.PaymentOriginal(recording)
        else -> error("No frozen financial UI action")
    }

    fun intent(action: LocalAction, operationId: OperationId = operation(action.name)): LocalCommandIntent {
        fun identifier(field: LocalField, value: DomainId) = LocalScalarInput(field, LocalScalarValue.Identifier(value))
        fun revision(field: LocalField, value: Revision) = LocalScalarInput(field, LocalScalarValue.Integer(value.value))
        val values = when (action) {
            LocalAction.FOOD_BEGIN_COLLECTION -> listOf(identifier(LocalField.ORDER_ID, order),
                revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, orderStorageRevision),
                identifier(LocalField.COLLECTION_ID, collection), identifier(LocalField.SESSION_ID, cashSession),
                revision(LocalField.EXPECTED_SESSION_REVISION, sessionDomainRevision),
                LocalScalarInput(LocalField.AMOUNT, LocalScalarValue.Decimal(decimal(payable))))
            LocalAction.FOOD_FINALIZE_PAID -> listOf(identifier(LocalField.COLLECTION_ID, collection),
                revision(LocalField.EXPECTED_COLLECTION_REVISION, collectionDomainRevision), identifier(LocalField.ORDER_ID, order),
                revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, coveredOrderStorageRevision), identifier(LocalField.SALE_ID, sale))
            LocalAction.FOOD_PAYMENT_APPLY -> listOf(identifier(LocalField.FOOD_PAYMENT_RECORDING_OPERATION_ID, DomainId(recording.value)),
                identifier(LocalField.ORDER_ID, order), identifier(LocalField.COLLECTION_ID, collection),
                LocalScalarInput(LocalField.AMOUNT, LocalScalarValue.Decimal(decimal(payable))),
                LocalScalarInput(LocalField.FOOD_PAYMENT_ORIGINAL_BODY_DIGEST, LocalScalarValue.Text(bodyDigest.value)))
            else -> error("No frozen financial UI input")
        }
        return LocalCommandIntent(operationId, action, 1, null, values)
    }

    fun form(action: LocalAction): LocalActionForm {
        val fields = intent(action).values.map { input ->
            val kind = when (input.value) {
                is LocalScalarValue.Identifier -> LocalInputKind.IDENTIFIER
                is LocalScalarValue.Integer -> LocalInputKind.INTEGER
                is LocalScalarValue.Decimal -> LocalInputKind.DECIMAL
                is LocalScalarValue.Text -> LocalInputKind.TEXT
                else -> error("No broader field in this frozen UI binding")
            }
            LocalFormField.Scalar(input.field, text(input.field.name), kind, initialValue = input.value,
                readOnly = action != LocalAction.FOOD_BEGIN_COLLECTION || input.field !in setOf(
                    LocalField.SESSION_ID, LocalField.EXPECTED_SESSION_REVISION))
        }
        return LocalActionForm(action, 1, text("Review retained FOOD financial input", "مراجعة إدخال الطعام المالي المحفوظ"), fields,
            confirmation = text("Confirm this exact original; no new cash capture", "تأكيد هذا الأصل الدقيق؛ بلا تحصيل نقدي جديد"))
    }

    val originalSource get() = LocalFoodCashFinancialReadSource.Original(recording, order, collection,
        actor, actor, payable, bodyDigest, applicationPending = true)
    private fun check() = LocalFoodUiCheck(order, actor, orderStorageRevision, "RESTAURANT",
        LocalFoodUiService("DINE_IN", "MIXED", ""), mixed.getValue("portions").jsonArray.size, null, null)
    private fun checkBody(): LocalFoodUiBody.Check {
        val rows = mixed.getValue("portions").jsonArray
        val portions = rows.map { value ->
            val row = value.jsonObject
            val status = LocalFoodPreparation.valueOf(row.getValue("preparation").jsonPrimitive.content)
            val served = row.getValue("fulfillment").jsonPrimitive.content == "SERVED"
            val started = status == LocalFoodPreparation.READY || status == LocalFoodPreparation.PREPARING
            val name = row.getValue("portion").jsonPrimitive.content
            LocalFoodUiPortion(id(name), name, Quantity(id("portion-unit"), 1, 0), "",
                Revision(if (served) 4 else if (started) 2 else if (status == LocalFoodPreparation.QUEUED) 1 else 0),
                status, if (served) LocalFoodFulfillment.SERVED else LocalFoodFulfillment.NOT_HANDED_OVER, started, false)
        }
        return LocalFoodUiBody.Check(check(), portions, payable, false, kitchen = LocalFoodUiKitchenObservation(
            order, orderStorageRevision, rows.count { it.jsonObject.getValue("preparation").jsonPrimitive.content == "NOT_SENT" },
            LocalFoodUiKitchenStatus.UNKNOWN), currentUnpaidPayable = payable,
            currentUnpaidPayableProfile = LocalFoodUiCurrentUnpaidPayableProfile.ORDINARY_UNPAID_V3)
    }

    fun page(query: LocalFoodUiQuery = LocalFoodUiQuery.MyChecks(), forms: List<LocalActionForm>? = null,
        next: LocalFoodUiQuery? = null): LocalWorkflowPage {
        val body: LocalFoodUiBody
        val selectedForms: List<LocalActionForm>
        when (query) {
            is LocalFoodUiQuery.MyChecks -> { body = LocalFoodUiBody.Checks(listOf(check())); selectedForms = emptyList() }
            is LocalFoodUiQuery.Check -> { body = checkBody(); selectedForms = listOf(form(LocalAction.FOOD_BEGIN_COLLECTION)) }
            is LocalFoodUiQuery.Collection -> {
                body = LocalFoodUiBody.Financial(query, emptyList(), emptyList(), LocalFoodCashFinancialReadSource.Collection(
                    query.collectionId, order, collectionDomainRevision, coveredOrderStorageRevision))
                selectedForms = listOf(form(LocalAction.FOOD_FINALIZE_PAID))
            }
            is LocalFoodUiQuery.PaymentOriginal -> {
                body = LocalFoodUiBody.Financial(query, emptyList(), emptyList(), originalSource)
                selectedForms = listOf(form(LocalAction.FOOD_PAYMENT_APPLY))
            }
            is LocalFoodUiQuery.Receipt, is LocalFoodUiQuery.PaymentAttempt, is LocalFoodUiQuery.OwnPendingPayments -> {
                body = LocalFoodUiBody.Financial(query, emptyList(), emptyList(), LocalFoodCashFinancialReadSource.ReadOnly(query))
                selectedForms = emptyList()
            }
            else -> error("Excluded financial UI selector")
        }
        return LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(), forms ?: selectedForms,
            food = LocalFoodUiPage(query, company, branch, actor, Revision(51), at, body, next))
    }

    fun pending(intent: LocalCommandIntent, phase: LocalPendingPhase = LocalPendingPhase.STAGED,
        decision: LocalRecordedDecision? = null) = LocalPendingOperation(intent.operationId, intent.action,
        text("Controlled immutable original"), listOf(LocalRecordValue(text("Original operation"), text(intent.operationId.value))),
        submitAllowed = decision == null, phase = phase, recordedDecision = decision)

    /** Well-formed presentation DATA only; durable terminal acceptance belongs to the actual gateway journal. */
    fun recorded(intent: LocalCommandIntent): LocalOperationResult.Recorded {
        val original: LocalFoodCashFinancialOriginal
        val details: LocalFoodCashFinancialOperationDetails
        val reference: LocalRecordReference
        when (intent.action) {
            LocalAction.FOOD_BEGIN_COLLECTION -> {
                original = LocalFoodCashFinancialOriginal.BeginCollection(order, orderStorageRevision, collection, cashSession, sessionDomainRevision)
                details = LocalFoodCashFinancialOperationDetails(intent.operationId, actor, original, true, "food.collection.locked",
                    collectionId = collection, amount = payable, followUp = LocalFoodUiQuery.Collection(collection))
                reference = LocalRecordReference(LocalRecordKind.FOOD_COLLECTION, collection)
            }
            LocalAction.FOOD_FINALIZE_PAID -> {
                original = LocalFoodCashFinancialOriginal.FinalizePaid(collection, collectionDomainRevision, sale)
                details = LocalFoodCashFinancialOperationDetails(intent.operationId, actor, original, true, "food.sale.finalized",
                    collectionId = collection, saleId = sale, amount = payable, followUp = LocalFoodUiQuery.Receipt(sale))
                reference = LocalRecordReference(LocalRecordKind.FOOD_SALE, sale)
            }
            LocalAction.FOOD_PAYMENT_APPLY -> {
                original = LocalFoodCashFinancialOriginal.ApplyOriginal(recording)
                details = LocalFoodCashFinancialOperationDetails(intent.operationId, actor, original, true, "committed",
                    collectionId = collection, application = LocalFoodCashFinancialApplication.CASH_PAID,
                    applicationOperationId = intent.operationId, followUp = LocalFoodUiQuery.Collection(collection))
                reference = LocalRecordReference(LocalRecordKind.FOOD_COLLECTION, collection)
            }
            else -> error("Excluded financial UI result")
        }
        return LocalOperationResult.Recorded(intent.operationId, LocalRecordedDecision.APPLIED,
            text("Controlled original result; do not collect again", "نتيجة أصلية مضبوطة؛ لا تكرر التحصيل"),
            LocalRecord(reference, text("Original result")), originalAction = intent.action, foodCashFinancial = details)
    }

    fun continuation(intent: LocalCommandIntent) = LocalWorkflowContinuation(LocalWorkspaceState(workflow = LocalWorkflow.FOOD,
        foodQuery = query(intent.action), operationId = intent.operationId,
        operation = LocalOperationResult.Unresolved(intent.operationId, LocalServiceProblem.UNAVAILABLE)), intent, intent.action, null, null)

    fun excludedQueries(): List<LocalFoodUiQuery> = listOf(LocalFoodUiQuery.Menu(), LocalFoodUiQuery.ConfiguredStations(),
        LocalFoodUiQuery.StationLabel(id("station")), LocalFoodUiQuery.StationTasks(id("station")),
        LocalFoodUiQuery.Task(id("station"), id("task/P1")), LocalFoodUiQuery.Recipients(order, orderStorageRevision),
        LocalFoodUiQuery.Incoming(), LocalFoodUiQuery.IncomingKnownOrder(order), LocalFoodUiQuery.Refund(id("refund")),
        LocalFoodUiQuery.Claim(id("claim")), LocalFoodUiQuery.OwnClaims(), LocalFoodUiQuery.TableAssignment(order, orderStorageRevision),
        LocalFoodUiQuery.FloorDirectory(), LocalFoodUiQuery.FloorTables(id("floor")), LocalFoodUiQuery.TableDefinition(id("table")))
}

internal fun LocalActionForm.financialUiChange(field: LocalField, value: LocalScalarValue) = copy(fields = fields.map {
    if (it is LocalFormField.Scalar && it.field == field) it.copy(initialValue = value) else it
})
