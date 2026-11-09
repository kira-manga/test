package com.ukkera.pos.app.independent.kitchenboard

import com.ukkera.pos.app.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Literal semantic inputs from kitchen-board-actions/v1; no generated command identity oracle. */
internal object KbaInputs {
    val company = DomainId("10000000-0000-4000-8000-000000000002")
    val branch = DomainId("10000000-0000-4000-8000-000000000003")
    val stationA = DomainId("20000000-0000-4000-8000-000000000001")
    val stationB = DomainId("20000000-0000-4000-8000-000000000002")
    val order = DomainId("30000000-0000-4000-8000-000000000001")
    val otherOrder = DomainId("30000000-0000-4000-8000-000000000099")
    val portionP1 = DomainId("40000000-0000-4000-8000-000000000001")
    val portionP2 = DomainId("40000000-0000-4000-8000-000000000002")
    val otherPortion = DomainId("40000000-0000-4000-8000-000000000099")
    val cursorA = DomainId("50000000-0000-4000-8000-000000000010")
    val taskP1A = DomainId("50000000-0000-4000-8000-000000000011")
    val taskP1B = DomainId("50000000-0000-4000-8000-000000000012")
    val taskP2A = DomainId("50000000-0000-4000-8000-000000000021")
    val otherTask = DomainId("50000000-0000-4000-8000-000000000099")
    val actorA = DomainId("60000000-0000-4000-8000-000000000001")
    val actorB = DomainId("60000000-0000-4000-8000-000000000002")
    val originalStart = OperationId("80000000-0000-4000-8000-000000000001")
    val originalReady = OperationId("80000000-0000-4000-8000-000000000002")

    val first = LocalFoodUiQuery.StationTasks(stationA)
    val paged = LocalFoodUiQuery.StationTasks(stationA, cursorA)
    val otherStation = LocalFoodUiQuery.StationTasks(stationB)
    val task = LocalFoodUiQuery.Task(stationA, taskP1A)

    // Required non-coordinate presentation metadata has no numerical oracle in the freeze.
    private val presentationUnit = DomainId("90000000-0000-4000-8000-000000000001")
    val queued = LocalFoodUiTask(order, Revision(41), portionP1, Revision(17), taskP1A, stationA,
        "Synthetic issued portion", Quantity(presentationUnit, 1, 0), "", "Synthetic issued order",
        LocalFoodTaskState.QUEUED, LocalFoodPreparation.QUEUED, LocalFoodDeliveryMode.DISPLAY)
    val started = queued.copy(orderStorageRevision = Revision(42), preparationDomainRevision = Revision(18),
        taskState = LocalFoodTaskState.STARTED, preparation = LocalFoodPreparation.PREPARING)
    val stationBQueued = queued.copy(stationId = stationB, taskId = taskP1B)
    val returnRows = listOf(started, queued.copy(taskId = taskP2A, portionId = portionP2,
        orderStorageRevision = Revision(42), preparationDomainRevision = Revision(18)))
    val observedAt = UtcInstant.parseCanonical("2026-11-01T12:00:00Z")

    fun taskQuery(row: LocalFoodUiTask) = LocalFoodUiQuery.Task(row.stationId, row.taskId)

    fun values(row: LocalFoodUiTask) = listOf(
        LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(row.orderId)),
        LocalScalarInput(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION,
            LocalScalarValue.Integer(row.orderStorageRevision.value)),
        LocalScalarInput(LocalField.PORTION_ID, LocalScalarValue.Identifier(row.portionId)),
        LocalScalarInput(LocalField.EXPECTED_FOOD_PORTION_DOMAIN_REVISION,
            LocalScalarValue.Integer(row.preparationDomainRevision.value)),
        LocalScalarInput(LocalField.STATION_ID, LocalScalarValue.Identifier(row.stationId)),
        LocalScalarInput(LocalField.TASK_ID, LocalScalarValue.Identifier(row.taskId)),
    )

    fun form(row: LocalFoodUiTask, action: LocalAction): LocalActionForm = LocalActionForm(action, 1,
        LocalText(action.name, action.name), values(row).map { input ->
            LocalFormField.Scalar(input.field, LocalText(input.field.name, input.field.name),
                if (input.value is LocalScalarValue.Identifier) LocalInputKind.IDENTIFIER else LocalInputKind.INTEGER,
                required = true, readOnly = true, initialValue = input.value)
        }, confirmation = LocalText("Confirm selected task", "تأكيد المهمة المختارة"))

    fun summary(row: LocalFoodUiTask, actor: DomainId = actorA): List<LocalRecordValue> =
        listOf(LocalRecordValue(LocalText("Actor", "الموظف"), LocalText(actor.value, actor.value))) +
            values(row).map { input ->
                val literal = when (val value = input.value) {
                    is LocalScalarValue.Identifier -> value.value.value
                    is LocalScalarValue.Integer -> value.value.toString()
                    else -> error("No other frozen task coordinate")
                }
                LocalRecordValue(LocalText(input.field.name, input.field.name), LocalText(literal, literal))
            }

    fun original(staged: Boolean): KbaOriginal {
        val row = if (staged) queued else started
        val action = if (staged) LocalAction.FOOD_STATION_START else LocalAction.FOOD_STATION_READY
        val id = if (staged) originalStart else originalReady
        return KbaOriginal(actorA, row, LocalCommandIntent(id, action, 1, null, values(row)),
            if (staged) LocalPendingPhase.STAGED else LocalPendingPhase.DISPATCH_UNRESOLVED)
    }
}

/** A frozen existing-original input supplied through pending/lookup, never production durability. */
internal data class KbaOriginal(val actor: DomainId, val row: LocalFoodUiTask,
    val intent: LocalCommandIntent, val phase: LocalPendingPhase) {
    fun pending() = LocalPendingOperation(intent.operationId, intent.action,
        LocalText("Synthetic retained kitchen original", "أصل مطبخ اصطناعي محفوظ"),
        KbaInputs.summary(row, actor), submitAllowed = true, phase = phase)
}
