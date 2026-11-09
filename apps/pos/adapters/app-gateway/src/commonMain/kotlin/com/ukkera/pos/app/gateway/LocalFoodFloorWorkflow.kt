package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

internal val floorConfigurationActions: Set<LocalAction> = setOf(LocalAction.FLOOR_CONFIGURE, LocalAction.TABLE_CONFIGURE)

internal suspend fun foodFloorWorkflow(access: GatewayAccess, selected: LocalFoodUiQuery): LocalWorkflowPage {
    val assignment = selected.tableAssignmentTarget()
    assignment?.let { access.requireAction(LocalAction.FOOD_ASSIGN_TABLE, it.orderId) }
    val query = when (selected) {
        is LocalFoodUiQuery.FloorDirectory -> LocalFoodFloorQueryV1.Floors(selected.after)
        is LocalFoodUiQuery.FloorTables -> LocalFoodFloorQueryV1.Tables(selected.floorId, selected.after)
        is LocalFoodUiQuery.TableDefinition -> LocalFoodFloorQueryV1.Table(selected.tableId)
        else -> invalid()
    }
    val reply = access.read(LocalFoodFloorReadCodecV1.read(query))
    val source = LocalFoodFloorReadCodecV1.decodeResult(reply.payload)
    if (source.query != query || !source.configured &&
        (source.floors.isNotEmpty() || source.tables.isNotEmpty() || source.floor != null || source.nextAfter != null)) unavailable()
    val body: LocalFoodUiBody
    val next: LocalFoodUiQuery?
    val forms: List<LocalActionForm>
    when (selected) {
        is LocalFoodUiQuery.FloorDirectory -> {
            if (source.tables.isNotEmpty() || source.floor != null) unavailable()
            foodPageCoordinates(source.floors.map { it.definition.id }, selected.after, source.nextAfter, 20)
            body = LocalFoodUiBody.Floors(source.floors.map(::floorUi))
            next = source.nextAfter?.let { selected.copy(after = it) }
            forms = if (assignment == null) listOfNotNull(floorForm(access, null)) else emptyList()
        }
        is LocalFoodUiQuery.FloorTables -> {
            if (source.floors.isNotEmpty()) unavailable()
            val floor = source.floor ?: unavailable()
            if (floor.definition.id != selected.floorId || source.tables.any { it.definition.floorId != selected.floorId }) unavailable()
            foodPageCoordinates(source.tables.map { it.definition.id }, selected.after, source.nextAfter, 20)
            body = LocalFoodUiBody.Tables(floorUi(floor), source.tables.map(::tableUi))
            next = source.nextAfter?.let { selected.copy(after = it) }
            forms = if (assignment == null) listOfNotNull(floorForm(access, floor), tableForm(access, floor, null)) else emptyList()
        }
        is LocalFoodUiQuery.TableDefinition -> {
            if (source.floors.isNotEmpty() || source.nextAfter != null) unavailable()
            val table = source.tables.singleOrNull() ?: unavailable()
            val floor = source.floor ?: unavailable()
            if (table.definition.id != selected.tableId || table.definition.floorId != floor.definition.id) unavailable()
            body = LocalFoodUiBody.Table(floorUi(floor), tableUi(table))
            next = null
            forms = if (assignment == null) listOfNotNull(tableForm(access, floor, table))
                else listOfNotNull(tableAssignmentForm(access, assignment, floor, table))
        }
        else -> invalid()
    }
    return LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(), forms, food = LocalFoodUiPage(selected,
        access.view.scope.companyId, access.view.scope.branchId, access.view.actorId,
        reply.branchRevision, reply.observedAt, body, next))
}

private fun floorUi(row: LocalFoodFloorRowV1): LocalFoodUiFloor = LocalFoodUiFloor(row.definition.id,
    row.storageRevision, names(row.definition.names, row.definition.id), row.definition.layoutOrder, row.definition.active)

private fun tableUi(row: LocalFoodTableRowV1): LocalFoodUiTable = LocalFoodUiTable(row.definition.id,
    row.definition.floorId, row.storageRevision, names(row.definition.names, row.definition.id), row.definition.layoutOrder, row.definition.active)

private fun floorForm(access: GatewayAccess, row: LocalFoodFloorRowV1?): LocalActionForm? {
    if (!access.installed.has(LocalFoodFloorProfileV1.COMMAND) || !access.allow(LocalAction.FLOOR_CONFIGURE, row?.definition?.id)) return null
    return form(LocalAction.FLOOR_CONFIGURE,
        listOf(idField(LocalField.FLOOR_ID, row?.definition?.id, readOnly = true)) +
            floorDefinitionFields(row?.storageRevision, row?.definition?.names, row?.definition?.layoutOrder, row?.definition?.active),
        floorConfigurationNotice())
}

private fun tableForm(access: GatewayAccess, floor: LocalFoodFloorRowV1, row: LocalFoodTableRowV1?): LocalActionForm? {
    if (!access.installed.has(LocalFoodFloorProfileV1.COMMAND) || !access.allow(LocalAction.TABLE_CONFIGURE, row?.definition?.id)) return null
    return form(LocalAction.TABLE_CONFIGURE, listOf(idField(LocalField.TABLE_ID, row?.definition?.id, readOnly = true),
        idField(LocalField.FLOOR_ID, floor.definition.id)) +
        floorDefinitionFields(row?.storageRevision, row?.definition?.names, row?.definition?.layoutOrder, row?.definition?.active),
        floorConfigurationNotice())
}

private fun floorDefinitionFields(revision: Revision?, names: LocalNamesV1?, order: Int?, active: Boolean?): List<LocalFormField> = listOf(
    scalar(LocalField.EXPECTED_STORAGE_REVISION, LocalInputKind.INTEGER, revision?.let { LocalScalarValue.Integer(it.value) },
        required = false, readOnly = true, maximum = 19),
    scalar(LocalField.NAME_ENGLISH, LocalInputKind.TEXT, names?.let { LocalScalarValue.Text(it.english) }, required = false, maximum = 160),
    scalar(LocalField.NAME_ARABIC, LocalInputKind.TEXT, names?.let { LocalScalarValue.Text(it.arabic) }, required = false, maximum = 160),
    scalar(LocalField.LAYOUT_ORDER, LocalInputKind.INTEGER, LocalScalarValue.Integer((order ?: 0).toLong()), maximum = 4),
    scalar(LocalField.ACTIVE, LocalInputKind.CHECKBOX, LocalScalarValue.Flag(active ?: true), required = false))

private fun floorConfigurationNotice(): LocalText = text(
    "Save configuration only. Both names are supported; at least one is required. Layout order is 0–9999. Disable active tables before disabling a floor. This does not assign/move orders, transfer ownership, split bills or change stock/money. A table's floor is fixed in this editor.",
    "حفظ الإعداد فقط. يُدعم الاسمان ويجب إدخال واحد منهما على الأقل. ترتيب المخطط من ٠ إلى ٩٩٩٩. عطّل الطاولات المفعّلة قبل تعطيل القاعة. لا يعيّن ذلك الطلبات أو ينقلها أو ينقل الملكية أو يقسم الفواتير أو يغير المخزون والنقد. قاعة الطاولة ثابتة في هذا المحرر.")

internal suspend fun prepareFoodFloor(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.action !in floorConfigurationActions || intent.formVersion != 1 || intent.lines.isNotEmpty() ||
        intent.lineField != null || intent.reference != null) invalid()
    val values = InputValues(intent.values)
    val id = values.id(if (intent.action == LocalAction.FLOOR_CONFIGURE) LocalField.FLOOR_ID else LocalField.TABLE_ID)
    access.requireAction(intent.action, id)
    val expected = values.optionalRevision(LocalField.EXPECTED_STORAGE_REVISION)
    val names = LocalNamesV1(values.optionalText(LocalField.NAME_ENGLISH).orEmpty(), values.optionalText(LocalField.NAME_ARABIC).orEmpty())
    val order = values.integer(LocalField.LAYOUT_ORDER)
    if (order !in 0L..9999L) invalid(LocalField.LAYOUT_ORDER)
    val active = values.flag(LocalField.ACTIVE)
    val request = if (intent.action == LocalAction.FLOOR_CONFIGURE)
        LocalFoodFloorConfigurationRequestV1.Floor(expected, LocalFoodFloorDefinitionV1(id, names, order.toInt(), active))
    else LocalFoodFloorConfigurationRequestV1.Table(expected,
        LocalFoodTableDefinitionV1(id, values.id(LocalField.FLOOR_ID), names, order.toInt(), active))
    values.finish()
    return LocalFoodFloorCodecV1.command(intent.operationId, request)
}

internal fun floorConfigurationTarget(request: LocalFoodFloorConfigurationRequestV1): DomainId = when (request) {
    is LocalFoodFloorConfigurationRequestV1.Floor -> request.floor.id
    is LocalFoodFloorConfigurationRequestV1.Table -> request.table.id
}

internal fun floorConfigurationAction(request: LocalFoodFloorConfigurationRequestV1): LocalAction = when (request) {
    is LocalFoodFloorConfigurationRequestV1.Floor -> LocalAction.FLOOR_CONFIGURE
    is LocalFoodFloorConfigurationRequestV1.Table -> LocalAction.TABLE_CONFIGURE
}

internal fun floorConfigurationRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    val saved = entry.request()
    if (reply.operationId != saved.operationId || reply.originalActorId != entry.owner.actorId || reply.physicalStart != null ||
        !actionMatches(entry.localAction, saved)) unavailable()
    val request = LocalFoodFloorCodecV1.decode(saved.payload)
    val value = LocalFoodFloorCodecV1.decodeResult(reply.result)
    if (reply.code != if (reply.committed) "committed" else value.code) unavailable()
    val expected = when (request) {
        is LocalFoodFloorConfigurationRequestV1.Floor -> request.expectedRevision
        is LocalFoodFloorConfigurationRequestV1.Table -> request.expectedRevision
    }
    if (reply.committed) {
        if (value.code != "food.floor.saved" || value.id != floorConfigurationTarget(request) ||
            value.revision != (expected?.next() ?: Revision(1))) unavailable()
    } else if (value.id != null || value.revision != null || value.code !in setOf("food.floor.stale", "food.floor.capacity",
            "food.floor.identity_conflict", "food.floor.active_tables", "food.table.floor_required", "food.table.floor_changed")) unavailable()
    val target = if (reply.committed) " ${value.id!!.value} · storage revision ${value.revision!!.value}." else ""
    val arabicTarget = if (reply.committed) " ${value.id!!.value} · مراجعة التخزين ${value.revision!!.value}." else ""
    val message = text("Floor/table configuration result: ${value.code}.$target This is not occupancy, order movement or a financial result.",
        "نتيجة إعداد القاعة أو الطاولة: ${value.code}.$arabicTarget ليست إشغالًا أو نقل طلب أو نتيجة مالية.")
    return LocalOperationResult.Recorded(reply.operationId, if (reply.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED,
        message, replayed = reply.replayed)
}
