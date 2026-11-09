package com.ukkera.pos.app.independent.foodcheckrpcprofilev1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.io.File
import kotlinx.serialization.json.*

/** Executable closed membership inputs only. No actual order revision or native original is supplied. */
internal object FrozenFoodCheckUiInputs {
    private val wire = Json { ignoreUnknownKeys = false; isLenient = false }
    private val relative = "qa/pos/designs/food-check-rpc-profile/fixtures.json"
    private val file = generateSequence(File(".").canonicalFile) { it.parentFile }
        .map { File(it, relative) }.firstOrNull { it.isFile } ?: error("Frozen FOOD_CHECKS input not found: $relative")
    val frozen = wire.parseToJsonElement(file.readText()).jsonObject
    private val catalog = frozen.getValue("fixture_catalog").jsonObject
    val profile = catalog.getValue("F_PROFILE").jsonObject
    private val ids = catalog.getValue("F_IDS").jsonObject
    val draftFixture = catalog.getValue("F_DRAFT").jsonObject
    val addFixture = catalog.getValue("F_ADD_TWO").jsonObject
    private val request = wire.parseToJsonElement(draftFixture.string("canonical_support_body"))
        .jsonObject.getValue("request").jsonObject
    private val service = request.getValue("service").jsonObject
    val order = DomainId(ids.string("order"))
    val actor = DomainId(ids.string("actorA"))
    val company = DomainId(ids.string("company"))
    val branch = DomainId(ids.string("branch"))
    val draftOperation = OperationId(ids.string("draftOperation"))
    val addOperation = OperationId(ids.string("addOperation"))
    val portion = DomainId(ids.getValue("portions").jsonArray.first().jsonPrimitive.content)
    val selectorId = DomainId(ids.string("recipe"))

    fun text(value: String) = LocalText(value, value)
    fun display(areas: Set<AppArea> = setOf(AppArea.ORDERS)) = SessionDisplayContext(actor, company, branch,
        "Synthetic check actor", "Synthetic company", "Synthetic branch", areas,
        workspaceMode = LocalSessionWorkspaceMode.FOOD_CHECKS_ONLY)

    fun draftIntent(version: Int = 2) = LocalCommandIntent(draftOperation, LocalAction.FOOD_CREATE_DRAFT, version,
        null, listOf(
            LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(order)),
            LocalScalarInput(LocalField.ACTIVITY, LocalScalarValue.Choice(request.string("activity"))),
            LocalScalarInput(LocalField.CHANNEL, LocalScalarValue.Choice(service.string("channel"))),
            LocalScalarInput(LocalField.SERVICE_REFERENCE, LocalScalarValue.Text(service.string("reference"))),
            LocalScalarInput(LocalField.SEAT_NOTE, LocalScalarValue.Text(service.string("seatNote"))),
        ))

    /** The unchanged Add template has no bound revisions. This incomplete UI input stays inert. */
    fun unboundAddIntent() = LocalCommandIntent(addOperation, LocalAction.FOOD_ADD, 3, null,
        listOf(LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(order))), LocalField.LINES,
        listOf(LocalInputLine(portion, emptyList())))

    fun draftForm(version: Int = 2) = LocalActionForm(LocalAction.FOOD_CREATE_DRAFT, version,
        text("Supplied draft editor shape"), listOf(
            LocalFormField.Scalar(LocalField.ORDER_ID, text("Order"), LocalInputKind.IDENTIFIER,
                initialValue = LocalScalarValue.Identifier(order), readOnly = true),
            LocalFormField.Scalar(LocalField.ACTIVITY, text("Activity"), LocalInputKind.CHOICE,
                choices = listOf(LocalChoice(request.string("activity"), text(request.string("activity")))),
                initialValue = LocalScalarValue.Choice(request.string("activity"))),
            LocalFormField.Scalar(LocalField.CHANNEL, text("Channel"), LocalInputKind.CHOICE,
                choices = listOf(LocalChoice(service.string("channel"), text(service.string("channel")))),
                initialValue = LocalScalarValue.Choice(service.string("channel"))),
            LocalFormField.Scalar(LocalField.SERVICE_REFERENCE, text("Reference"), LocalInputKind.TEXT,
                initialValue = LocalScalarValue.Text(service.string("reference"))),
            LocalFormField.Scalar(LocalField.SEAT_NOTE, text("Seat note"), LocalInputKind.TEXT, required = false),
            LocalFormField.Scalar(LocalField.FOOD_DELIVERY_RECIPIENT, text("Recipient"), LocalInputKind.TEXT, required = false),
            LocalFormField.Scalar(LocalField.FOOD_DELIVERY_CONTACT, text("Contact"), LocalInputKind.TEXT, required = false),
            LocalFormField.Scalar(LocalField.FOOD_DELIVERY_ADDRESS, text("Address"), LocalInputKind.MULTILINE, required = false),
        ), confirmation = text("Explicit review required"))

    fun pending(action: LocalAction = LocalAction.FOOD_CREATE_DRAFT, operation: OperationId = draftOperation,
        decision: LocalRecordedDecision? = null) = LocalPendingOperation(operation, action, text("Supplied original shape"),
        phase = if (decision == null) LocalPendingPhase.STAGED else LocalPendingPhase.RECORDED_UNACKNOWLEDGED,
        recordedDecision = decision)

    fun unresolved(intent: LocalCommandIntent = draftIntent()) = LocalWorkflowContinuation(
        LocalWorkspaceState(workflow = LocalWorkflow.FOOD, operationId = intent.operationId,
            operation = LocalOperationResult.Unresolved(intent.operationId, LocalServiceProblem.UNAVAILABLE)),
        intent, intent.action, null, null)

    fun excludedQueries(): List<LocalFoodUiQuery> = listOf(
        LocalFoodUiQuery.ConfiguredStations(), LocalFoodUiQuery.StationLabel(selectorId),
        LocalFoodUiQuery.StationTasks(selectorId), LocalFoodUiQuery.Task(selectorId, portion),
        LocalFoodUiQuery.Incoming(), LocalFoodUiQuery.IncomingKnownOrder(order),
        LocalFoodUiQuery.Collection(order), LocalFoodUiQuery.Receipt(order), LocalFoodUiQuery.Refund(order),
        LocalFoodUiQuery.Claim(order), LocalFoodUiQuery.OwnClaims(), LocalFoodUiQuery.FloorDirectory(),
        LocalFoodUiQuery.FloorTables(selectorId), LocalFoodUiQuery.TableDefinition(selectorId),
    )

    /** Branch revision0/time are inert DTO constructor DATA, not an actual same-cut read or empty-check proof. */
    fun ownPage(forms: List<LocalActionForm> = emptyList()) = LocalWorkflowPage(LocalWorkflow.FOOD,
        emptyList(), forms, food = LocalFoodUiPage(LocalFoodUiQuery.MyChecks(), company, branch, actor,
            Revision(0), UtcInstant.parseCanonical("1970-01-01T00:00:00Z"), LocalFoodUiBody.Checks(emptyList())))
}

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
