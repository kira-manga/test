package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.LocalFoodFloorRowV1
import com.ukkera.pos.catalog.store.LocalFoodTableRowV1
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.Revision

/** One actual protected assignment response, not a join of cached check/configuration pages. */
internal suspend fun foodTableAssignmentWorkflow(access: GatewayAccess,
    selected: LocalFoodUiQuery.TableAssignment): LocalWorkflowPage {
    if (!access.installed.has(LocalFoodTableAssignmentReadCodecV1.QUERY))
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val reply = access.read(LocalFoodTableAssignmentReadCodecV1.read(LocalFoodTableAssignmentQueryV1(selected.orderId)))
    val value = LocalFoodTableAssignmentReadCodecV1.decodeResult(reply.payload)
    if (value.orderId != selected.orderId || value.ownerId != access.view.actorId ||
        value.orderRevision != selected.expectedOrderStorageRevision || value.channel != LocalSalesChannel.DINE_IN ||
        value.activity !in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE))
        throw GatewayFailure(LocalServiceProblem.FOOD_TARGET_UNAVAILABLE)
    val binding = value.assignment
    val target = LocalFoodUiTableTarget(value.orderId, value.orderRevision, binding?.revision)
    val body = LocalFoodUiBody.TableAssignment(target, binding?.floorId, binding?.tableId,
        value.workClosed, value.handoffPending, access.installed.has(LocalFoodTableAssignmentProfileV1.COMMAND) &&
            access.allow(LocalAction.FOOD_ASSIGN_TABLE, value.orderId))
    return LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(), emptyList(), food = LocalFoodUiPage(selected,
        access.view.scope.companyId, access.view.scope.branchId, access.view.actorId, reply.branchRevision,
        reply.observedAt, body))
}

internal fun tableAssignmentForm(access: GatewayAccess, target: LocalFoodUiTableTarget,
    floor: LocalFoodFloorRowV1, table: LocalFoodTableRowV1): LocalActionForm? {
    if (!floor.definition.active || !table.definition.active || table.definition.floorId != floor.definition.id ||
        !access.installed.has(LocalFoodTableAssignmentProfileV1.COMMAND) ||
        !access.allow(LocalAction.FOOD_ASSIGN_TABLE, target.orderId)) return null
    return form(LocalAction.FOOD_ASSIGN_TABLE, foodOrderFields(target.orderId, target.expectedOrderStorageRevision) + listOf(
        idField(LocalField.FLOOR_ID, floor.definition.id),
        revisionField(LocalField.EXPECTED_FLOOR_STORAGE_REVISION, floor.storageRevision),
        idField(LocalField.TABLE_ID, table.definition.id),
        revisionField(LocalField.EXPECTED_TABLE_STORAGE_REVISION, table.storageRevision),
        scalar(LocalField.EXPECTED_TABLE_ASSIGNMENT_STORAGE_REVISION, LocalInputKind.INTEGER,
            target.expectedAssignmentStorageRevision?.let { LocalScalarValue.Integer(it.value) },
            required = false, readOnly = true, maximum = 19)), tableAssignmentNotice())
}

private fun tableAssignmentNotice(): LocalText = text(
    "Confirm only this check's managed table assignment. The Master checks current owner, permission and all selected revisions. This does not change service labels, ownership, stock, preparation or payments. A stale selection requires a fresh review, not automatic retry.",
    "أكّد تعيين الطاولة المُدارة لهذا الطلب فقط. يتحقق الجهاز الرئيسي من المالك الحالي والصلاحية وجميع المراجعات المختارة. لا يغير ذلك أوصاف الخدمة أو الملكية أو المخزون أو التحضير أو المدفوعات. يتطلب الاختيار القديم مراجعة جديدة، وليس إعادة المحاولة تلقائيًا.")

internal suspend fun prepareFoodTableAssignment(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (!access.installed.has(LocalFoodTableAssignmentProfileV1.COMMAND))
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    if (intent.action != LocalAction.FOOD_ASSIGN_TABLE || intent.formVersion != 1 || intent.reference != null ||
        intent.lines.isNotEmpty() || intent.lineField != null) invalid()
    val values = InputValues(intent.values)
    val orderId = values.id(LocalField.ORDER_ID)
    access.requireAction(intent.action, orderId)
    val request = LocalFoodTableAssignmentRequestV1(orderId,
        values.revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION), values.id(LocalField.FLOOR_ID),
        values.revision(LocalField.EXPECTED_FLOOR_STORAGE_REVISION), values.id(LocalField.TABLE_ID),
        values.revision(LocalField.EXPECTED_TABLE_STORAGE_REVISION),
        values.optionalRevision(LocalField.EXPECTED_TABLE_ASSIGNMENT_STORAGE_REVISION))
    values.finish()
    return LocalFoodTableAssignmentCodecV1.command(intent.operationId, request)
}

internal fun tableAssignmentIntentSummary(request: MutationRequest): List<LocalRecordValue> {
    val value = LocalFoodTableAssignmentCodecV1.decode(request.payload)
    return listOf(
        recordValue(fieldLabel(LocalField.ORDER_ID), literal(value.orderId.value)),
        recordValue(fieldLabel(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION), literal(value.expectedOrderRevision.value.toString())),
        recordValue(fieldLabel(LocalField.FLOOR_ID), literal(value.floorId.value)),
        recordValue(fieldLabel(LocalField.EXPECTED_FLOOR_STORAGE_REVISION), literal(value.expectedFloorRevision.value.toString())),
        recordValue(fieldLabel(LocalField.TABLE_ID), literal(value.tableId.value)),
        recordValue(fieldLabel(LocalField.EXPECTED_TABLE_STORAGE_REVISION), literal(value.expectedTableRevision.value.toString())),
        recordValue(fieldLabel(LocalField.EXPECTED_TABLE_ASSIGNMENT_STORAGE_REVISION),
            value.expectedAssignmentRevision?.let { literal(it.value.toString()) }
                ?: text("First assignment (no prior binding)", "تعيين أول (لا يوجد ربط سابق)")),
        recordValue(text("Effect", "الأثر"), tableAssignmentNotice()))
}

internal fun tableAssignmentRecorded(entry: StoredClientIntentV1,
    reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    val saved = entry.request()
    if (entry.localAction != LocalAction.FOOD_ASSIGN_TABLE || entry.formVersion != 1 || reply.operationId != saved.operationId ||
        reply.originalActorId != entry.owner.actorId || reply.physicalStart != null ||
        !actionMatches(entry.localAction, saved)) unavailable()
    val request = LocalFoodTableAssignmentCodecV1.decode(saved.payload)
    val value = tableAssignmentOutcome(request, reply.committed, reply.code, reply.result)
    return LocalOperationResult.Recorded(reply.operationId,
        if (reply.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED,
        text("Table assignment result: ${value.code}. Stock, preparation and payments are unchanged.",
            "نتيجة تعيين الطاولة: ${value.code}. المخزون والتحضير والمدفوعات دون تغيير."), replayed = reply.replayed)
}

/** Shared exact-original comparison for live and already remembered terminal outcomes. */
internal fun tableAssignmentOutcome(request: LocalFoodTableAssignmentRequestV1, committed: Boolean,
    code: String, payload: StoredBytes): LocalFoodTableAssignmentResultV1 {
    val value = LocalFoodTableAssignmentCodecV1.decodeResult(payload)
    if (code != if (committed) "committed" else value.code) unavailable()
    if (committed) {
        val binding = value.assignment ?: unavailable()
        if (value.code != "food.table.assigned" || value.orderId != request.orderId ||
            value.orderRevision != request.expectedOrderRevision || binding.observedOrderRevision != request.expectedOrderRevision ||
            binding.revision != (request.expectedAssignmentRevision?.next() ?: Revision(1)) ||
            binding.floorId != request.floorId || binding.floorRevision != request.expectedFloorRevision ||
            binding.tableId != request.tableId || binding.tableRevision != request.expectedTableRevision) unavailable()
    } else if (value.orderId != null || value.orderRevision != null || value.assignment != null ||
        value.code !in LocalFoodTableAssignmentProfileV1.REJECTION_CODES) unavailable()
    return value
}
