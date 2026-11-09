package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.inventory.store.LocalStockRelationalQueriesV2
import com.ukkera.pos.sales.FoodIngredientReturnDisposition
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

internal val foodActions: Set<LocalAction> = setOf(LocalAction.FOOD_CREATE, LocalAction.FOOD_CREATE_DRAFT, LocalAction.FOOD_ADD, LocalAction.FOOD_SEND,
    LocalAction.FOOD_STATION_START, LocalAction.FOOD_STATION_READY, LocalAction.FOOD_CONFIRM_UNSTARTED, LocalAction.FOOD_SERVE,
    LocalAction.FOOD_CANCEL_PORTION, LocalAction.FOOD_DISCARD_STARTED, LocalAction.FOOD_OFFER_HANDOFF,
    LocalAction.FOOD_ACCEPT_HANDOFF, LocalAction.FOOD_CANCEL_HANDOFF, LocalAction.FOOD_EDIT_SERVICE_DETAILS)

internal fun foodSecurityAction(action: LocalAction): SecurityAction = when (action) {
    LocalAction.FOOD_CREATE, LocalAction.FOOD_CREATE_DRAFT -> SecurityAction.ORDER_CREATE
    LocalAction.FOOD_STATION_START, LocalAction.FOOD_STATION_READY, LocalAction.FOOD_CONFIRM_UNSTARTED,
    LocalAction.FOOD_DISCARD_STARTED -> SecurityAction.KITCHEN_TASK
    LocalAction.FOOD_OFFER_HANDOFF, LocalAction.FOOD_ACCEPT_HANDOFF, LocalAction.FOOD_CANCEL_HANDOFF -> SecurityAction.ORDER_HANDOFF
    LocalAction.FOOD_ADD, LocalAction.FOOD_SEND, LocalAction.FOOD_SERVE, LocalAction.FOOD_CANCEL_PORTION,
    LocalAction.FOOD_EDIT_SERVICE_DETAILS -> SecurityAction.ORDER_EDIT
    else -> invalid()
}

internal fun foodOrderFields(orderId: DomainId, revision: Revision): List<LocalFormField> = listOf(
    idField(LocalField.ORDER_ID, orderId), revisionField(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, revision))

internal fun foodMenuForm(access: GatewayAccess, query: LocalFoodUiQuery.Menu, menu: LocalFoodUiBody.Menu,
    quotationInstalled: Boolean = false): LocalActionForm? {
    val revision = menu.catalogRevision ?: return null
    val target = query.orderId
    val action = if (target == null) LocalAction.FOOD_CREATE else LocalAction.FOOD_ADD
    val command = if (action == LocalAction.FOOD_ADD) LocalFoodStockCodecV2.COMMAND else LocalFoodProfileV1.COMMAND
    if (!access.installed.has(command) || !access.allow(action, target) || menu.recipes.none { it.active }) return null
    val fields = buildList<LocalFormField> {
        if (target == null) {
            // Filled once by explicit menu selection, not allocated on refresh/recomposition.
            add(idField(LocalField.ORDER_ID, readOnly = true))
            add(choiceField(LocalField.ACTIVITY, listOf(LocalChoice("RESTAURANT", text("Restaurant", "مطعم")),
                LocalChoice("CAFE", text("Café", "مقهى")))))
            add(choiceField(LocalField.CHANNEL, listOf(LocalChoice("DINE_IN", text("Dine-in", "داخل المكان")),
                LocalChoice("COUNTER", text("Counter", "الكاونتر")), LocalChoice("TAKEAWAY", text("Takeaway", "سفري")))))
            add(scalar(LocalField.SERVICE_REFERENCE, LocalInputKind.TEXT, maximum = 80,
                help = text("A label, not managed table occupancy or ownership.", "وصف فقط، وليس إشغالًا أو ملكية لطاولة مُدارة.")))
            add(scalar(LocalField.SEAT_NOTE, LocalInputKind.TEXT, required = false, maximum = 160))
        } else addAll(foodOrderFields(target, checkNotNull(query.expectedOrderStorageRevision)))
        add(revisionField(LocalField.EXPECTED_CATALOG_REVISION, revision))
    }
    // Capacity counts independently prepared servings, not merely commercial rows.
    return form(action, fields, text("Select complete configured choices and a whole serving count. One commercial line may contain multiple independently prepared servings (maximum 8 total). No food payment may be taken in this flow.",
        "اختر اختيارات مُعدّة كاملة وعددًا صحيحًا من الحصص. قد يحتوي السطر التجاري على حصص مستقلة التحضير (بحد أقصى ٨ إجمالًا). لا يجوز استلام مدفوعات طعام عبر هذا المسار."),
        version = if (action == LocalAction.FOOD_ADD) {
            if (quotationInstalled && access.installed.has(LocalFoodQuotationProfileReadCodecV1.QUERY)) 3 else 2
        } else 1)
}

/** Installation discovery only. It grants no activity, employee or physical permission and
 * never runs while reconstructing a saved original. No inventory-read grant is borrowed. */
internal suspend fun foodQuotationInstallationAvailable(access: GatewayAccess): Boolean {
    if (!access.installed.has(LocalFoodStockCodecV2.COMMAND) ||
        !access.installed.has(LocalFoodQuotationProfileReadCodecV1.QUERY)) return false
    return LocalFoodQuotationProfileReadCodecV1.result(
        access.read(LocalFoodQuotationProfileReadCodecV1.read()).payload).installed
}

internal fun foodCheckForms(access: GatewayAccess, body: LocalFoodUiBody.Check): List<LocalActionForm> {
    if (!access.installed.has(LocalFoodProfileV1.COMMAND) || body.check.workClosed) return emptyList()
    val order = body.check
    val base = foodOrderFields(order.orderId, order.orderStorageRevision)
    val result = mutableListOf<LocalActionForm>()
    fun references(portions: List<LocalFoodUiPortion>) = portions.map {
        LocalReferenceChoice(LocalRecordReference(LocalRecordKind.FOOD_PORTION, it.portionId, it.preparationDomainRevision, order.orderId),
            text("${it.description} · ${it.portionId.value}", "${it.description} · ${it.portionId.value}"))
    }
    fun portionForm(action: LocalAction, portions: List<LocalFoodUiPortion>, multiple: Boolean = false, extra: List<LocalFormField> = emptyList()) {
        if (portions.isNotEmpty() && access.allow(action, order.orderId)) result += form(action, base +
            scalar(LocalField.PORTION_ID, if (multiple) LocalInputKind.MULTI_REFERENCE else LocalInputKind.REFERENCE)
                .copy(references = references(portions)) + extra, foodPreparationNotice())
    }
    if (order.handoffId == null) {
        if (access.allow(LocalAction.FOOD_EDIT_SERVICE_DETAILS, order.orderId))
            result += form(LocalAction.FOOD_EDIT_SERVICE_DETAILS, base + listOf(
                scalar(LocalField.SERVICE_REFERENCE, LocalInputKind.TEXT, LocalScalarValue.Text(order.service.reference), maximum = 80),
                scalar(LocalField.SEAT_NOTE, LocalInputKind.TEXT, LocalScalarValue.Text(order.service.seatNote), required = false, maximum = 160)),
                foodServiceDetailsNotice())
        if (order.service.channel != "DELIVERY" && order.collectionId == null && body.financial == null &&
            !body.kitchenUnavailableForUnsentWork) portionForm(LocalAction.FOOD_SEND,
            body.portions.filter { it.preparation == LocalFoodPreparation.NOT_SENT }, multiple = true)
        if (order.service.channel == "DINE_IN") portionForm(LocalAction.FOOD_SERVE, body.portions.filter {
            it.preparation == LocalFoodPreparation.READY && it.fulfillment == LocalFoodFulfillment.NOT_HANDED_OVER })
        portionForm(LocalAction.FOOD_CANCEL_PORTION, body.portions.filter {
            (order.collectionId == null || it.preparation != LocalFoodPreparation.NOT_SENT) &&
                it.fulfillment == LocalFoodFulfillment.NOT_HANDED_OVER && it.preparation in setOf(LocalFoodPreparation.NOT_SENT,
                LocalFoodPreparation.QUEUED, LocalFoodPreparation.PREPARING, LocalFoodPreparation.READY)
        }, extra = listOf(foodReasonField()))
        if (order.portionCount > 0 && (order.collectionId == null || body.financial != null))
            foodOfferSelectionForm(access, order.orderId, order.orderStorageRevision, body.financial)?.let { result += it }
    } else if ((order.collectionId == null || body.financial != null) && access.allow(LocalAction.FOOD_CANCEL_HANDOFF, order.orderId)) result += form(LocalAction.FOOD_CANCEL_HANDOFF,
        base + idField(LocalField.HANDOFF_ID, order.handoffId) + foodHandoffFinancialFields(body.financial), foodHandoffNotice())
    return result
}

/** The UI fills the read-only recipient only after an actual eligible-recipient selection. */
internal fun foodOfferSelectionForm(access: GatewayAccess, orderId: DomainId, revision: Revision,
    financial: LocalFoodUiFinancial? = null): LocalActionForm? {
    if (!access.installed.has(LocalFoodProfileV1.COMMAND) || !access.installed.has(LocalFoodHandoffProfileV2.QUERY) ||
        !access.installed.has(LocalFoodHandoffResponsibilityCodecV1.QUERY) || !access.allow(LocalAction.FOOD_OFFER_HANDOFF, orderId) ||
        financial?.paymentUnresolved == true) return null
    return form(LocalAction.FOOD_OFFER_HANDOFF, foodOrderFields(orderId, revision) +
        listOf(idField(LocalField.HANDOFF_ID, readOnly = true), idField(LocalField.RECIPIENT_ID, readOnly = true)) +
        foodHandoffFinancialFields(financial), foodHandoffNotice())
        .copy(title = text("Choose an eligible recipient", "اختيار مستلم مؤهل"))
}

internal fun foodHandoffFinancialFields(value: LocalFoodUiFinancial?): List<LocalFormField> = value?.let {
    listOf(idField(LocalField.COLLECTION_ID, it.collectionId), revisionField(LocalField.EXPECTED_COLLECTION_REVISION, it.collectionDomainRevision))
}.orEmpty()

/** The reason is an explicit evidence coordinate, not an inferred/free-text policy decision. */
private fun foodReasonField(): LocalFormField.Scalar = scalar(LocalField.REASON_ID, LocalInputKind.IDENTIFIER,
    maximum = 36, help = text("Exact cancellation reason/evidence ID; this does not authorize a waiver or refund.",
        "معرف سبب الإلغاء أو إثباته بدقة؛ لا يمنح إعفاءً من الرسوم أو ردًا للمبلغ."))

internal fun foodTaskForms(access: GatewayAccess, task: LocalFoodUiTask): List<LocalActionForm> {
    if (!access.installed.has(LocalFoodProfileV1.COMMAND)) return emptyList()
    val base = foodOrderFields(task.orderId, task.orderStorageRevision) + listOf(idField(LocalField.PORTION_ID, task.portionId),
        revisionField(LocalField.EXPECTED_FOOD_PORTION_DOMAIN_REVISION, task.preparationDomainRevision),
        idField(LocalField.STATION_ID, task.stationId), idField(LocalField.TASK_ID, task.taskId))
    return buildList {
        fun addAction(action: LocalAction, enabled: Boolean, extra: List<LocalFormField> = emptyList()) {
            if (enabled && access.allow(action, task.stationId)) add(form(action, base + extra, foodPreparationNotice()))
        }
        addAction(LocalAction.FOOD_STATION_START, task.taskState == LocalFoodTaskState.QUEUED && task.preparation in setOf(
            LocalFoodPreparation.QUEUED, LocalFoodPreparation.PREPARING, LocalFoodPreparation.CANCEL_PENDING))
        addAction(LocalAction.FOOD_STATION_READY, task.taskState == LocalFoodTaskState.STARTED && task.preparation == LocalFoodPreparation.PREPARING)
        addAction(LocalAction.FOOD_CONFIRM_UNSTARTED, task.taskState == LocalFoodTaskState.QUEUED && task.preparation == LocalFoodPreparation.CANCEL_PENDING,
            listOf(idField(LocalField.CONFIRMATION_ID, readOnly = true), choiceField(LocalField.DISPOSITION, listOf(
                LocalChoice("RELEASED", text("Return safe ingredients to available stock", "إعادة المكونات الآمنة إلى المخزون المتاح")),
                LocalChoice("QUARANTINE", text("Keep ingredients in quarantine", "إبقاء المكونات في الحجر"))))))
        addAction(LocalAction.FOOD_DISCARD_STARTED, task.preparation == LocalFoodPreparation.CANCEL_PENDING &&
            task.taskState in setOf(LocalFoodTaskState.STARTED, LocalFoodTaskState.READY))
    }
}

private fun foodServiceDetailsNotice(): LocalText = text(
    "Update only the table/pickup reference and seat/service note. These are labels, not managed table occupancy. Service channel, recipes, prices, stock, payments and handover remain unchanged.",
    "يُعدّل مرجع الطاولة أو الاستلام وملاحظة المقعد أو الخدمة فقط. هذه أوصاف وليست إدارة لإشغال الطاولات. تبقى قناة الخدمة والوصفات والأسعار والمخزون والمدفوعات والتسليم دون تغيير.")

internal fun foodPreparationNotice(): LocalText = text(
    "Preparation, handover, cancellation and money are separate. Kitchen delivery/printing intent is not physical acknowledgement. Cancellation is not a waiver or refund; started ingredients stay consumed.",
    "التحضير والتسليم والإلغاء والنقد حالات منفصلة. نية الإرسال أو الطباعة ليست تأكيدًا فعليًا. الإلغاء ليس إعفاءً أو ردًا للمبلغ؛ وتظل المكونات التي بدأ استخدامها مستهلكة.")
internal fun foodHandoffNotice(): LocalText = text(
    "The named recipient accepts current food responsibility, including any settled collection and remaining work. In-flight payments must first be resolved. An offer leaves the old owner responsible until acceptance. Earlier payments, stock, receipts and cash custody stay with their original records; no drawer is transferred.",
    "يقبل المستلم المقصود مسؤولية الطلب الحالية بما فيها التحصيل المستقر والعمل المتبقي. يجب معالجة المدفوعات المعلقة أولًا. تظل المسؤولية لدى المالك السابق حتى القبول. تبقى المدفوعات السابقة والمخزون والإيصالات والعهدة النقدية بسجلاتها الأصلية؛ لا يُنقل درج نقدي.")

/** First-stage source checks only; continuation never re-prices, rebuilds or changes saved bytes. */
internal suspend fun prepareFood(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.action == LocalAction.FOOD_CREATE_DRAFT) return prepareFoodDraft(access, intent)
    if (intent.action !in foodActions || (intent.formVersion != 1 &&
            !(intent.action == LocalAction.FOOD_ADD && intent.formVersion in 2..3)) || intent.reference != null) invalid()
    val command = if (intent.action == LocalAction.FOOD_ADD && intent.formVersion in 2..3)
        LocalFoodStockCodecV2.COMMAND else LocalFoodProfileV1.COMMAND
    if (!access.installed.has(command)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    if (intent.formVersion == 3 && !foodQuotationInstallationAvailable(access))
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val values = InputValues(intent.values)
    val orderId = values.id(LocalField.ORDER_ID)
    val action = intent.action
    var originalDraft = false
    val request: LocalFoodRequestV1
    if (action in setOf(LocalAction.FOOD_CREATE, LocalAction.FOOD_ADD)) {
        val selected = parseFoodSelection(access, action, orderId, values, intent.lineField, intent.lines,
            preserveInputOrder = intent.formVersion == 3)
        request = selected.request
        originalDraft = selected.originalDraft
    } else {
        if (intent.lineField != null || intent.lines.isNotEmpty()) invalid(LocalField.LINES)
        val expected = values.revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION)
        request = when (action) {
            LocalAction.FOOD_EDIT_SERVICE_DETAILS -> {
                access.requireAction(action, orderId)
                val current = foodOwnerAt(access, orderId, expected)
                originalDraft = current.summary.portionCount == 0
                if (current.summary.handoffId != null || current.summary.workClosed) invalid(LocalField.ORDER_ID)
                LocalFoodRequestV1.EditServiceDetails(orderId, expected, values.text(LocalField.SERVICE_REFERENCE),
                    values.optionalText(LocalField.SEAT_NOTE).orEmpty())
            }
            LocalAction.FOOD_SEND, LocalAction.FOOD_SERVE, LocalAction.FOOD_CANCEL_PORTION -> {
                access.requireAction(action, orderId)
                val selected = if (action == LocalAction.FOOD_SEND) values.references(LocalField.PORTION_ID, LocalRecordKind.FOOD_PORTION)
                    else listOf(values.reference(LocalField.PORTION_ID, LocalRecordKind.FOOD_PORTION))
                if (selected.size !in 1..8 || selected.map { it.id }.distinct().size != selected.size ||
                    selected.any { it.parentId != orderId || it.revision == null }) invalid(LocalField.PORTION_ID)
                val portions = foodSelectedPortions(access, orderId, expected, selected)
                when (action) {
                    LocalAction.FOOD_SEND -> {
                        val current = foodRead(access, LocalFoodQueryV1.Order(orderId))
                        val currentDetail = current.result.detail ?: foodMissing()
                        if (currentDetail.summary.revision != expected) invalid(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION)
                        if (currentDetail.summary.service.channel == LocalSalesChannel.DELIVERY)
                            throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                        if (current.kitchenUnavailableForUnsentWork) throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                        if (currentDetail.summary.financialCollectionId != null) invalid(LocalField.ORDER_ID)
                        if (portions.any { it.preparation.status != FoodPreparationStatus.NOT_SENT }) invalid(LocalField.PORTION_ID)
                        LocalFoodRequestV1.Send(orderId, expected, selected.map { it.id })
                    }
                    LocalAction.FOOD_SERVE -> {
                        val source = foodOwnerAt(access, orderId, expected)
                        if (source.summary.service.channel != LocalSalesChannel.DINE_IN) invalid(LocalField.PORTION_ID)
                        LocalFoodRequestV1.Serve(orderId, expected, selected.single().id, checkNotNull(selected.single().revision))
                    }
                    else -> LocalFoodRequestV1.Cancel(orderId, expected, selected.single().id,
                        checkNotNull(selected.single().revision), values.id(LocalField.REASON_ID))
                }
            }
            LocalAction.FOOD_STATION_START, LocalAction.FOOD_STATION_READY, LocalAction.FOOD_CONFIRM_UNSTARTED, LocalAction.FOOD_DISCARD_STARTED -> {
                val portionId = values.id(LocalField.PORTION_ID)
                val portionRevision = values.revision(LocalField.EXPECTED_FOOD_PORTION_DOMAIN_REVISION)
                val stationId = values.id(LocalField.STATION_ID)
                val taskId = values.id(LocalField.TASK_ID)
                access.requireAction(action, stationId)
                val task = foodRead(access, LocalFoodQueryV1.Task(stationId, taskId)).result.tasks.single()
                if (task.orderId != orderId || task.orderRevision != expected || task.portionId != portionId || task.portionRevision != portionRevision)
                    invalid(LocalField.TASK_ID)
                if (foodTaskForms(access, foodTask(task)).none { it.action == action }) invalid(LocalField.TASK_ID)
                if (action == LocalAction.FOOD_DISCARD_STARTED) LocalFoodRequestV1.DiscardStarted(orderId, expected, portionId, portionRevision, stationId)
                else LocalFoodRequestV1.Station(orderId, expected, portionId, portionRevision, stationId, when (action) {
                    LocalAction.FOOD_STATION_START -> LocalFoodStationActionV1.START
                    LocalAction.FOOD_STATION_READY -> LocalFoodStationActionV1.READY
                    else -> LocalFoodStationActionV1.CONFIRM_UNSTARTED
                }, if (action == LocalAction.FOOD_CONFIRM_UNSTARTED) values.id(LocalField.CONFIRMATION_ID) else null,
                    if (action == LocalAction.FOOD_CONFIRM_UNSTARTED) FoodIngredientReturnDisposition.valueOf(values.choice(LocalField.DISPOSITION)) else null)
            }
            LocalAction.FOOD_OFFER_HANDOFF, LocalAction.FOOD_ACCEPT_HANDOFF, LocalAction.FOOD_CANCEL_HANDOFF -> {
                access.requireAction(action, orderId)
                val handoffId = values.id(LocalField.HANDOFF_ID)
                val collectionId = values.optionalId(LocalField.COLLECTION_ID)
                val collectionRevision = values.optionalRevision(LocalField.EXPECTED_COLLECTION_REVISION)
                if ((collectionId == null) != (collectionRevision == null)) invalid(LocalField.COLLECTION_ID)
                val financial = collectionId?.let { LocalFoodHandoffFinancialExpectationV1(it, checkNotNull(collectionRevision)) }
                if (action == LocalAction.FOOD_ACCEPT_HANDOFF) {
                    val current = foodResponsibility(access, orderId, handoffId, expected).responsibility
                    if (current.financial?.expectation != financial) invalid(LocalField.EXPECTED_COLLECTION_REVISION)
                    LocalFoodRequestV1.AcceptHandoff(orderId, expected, handoffId, financial)
                } else {
                    val current = foodOwnerAt(access, orderId, expected)
                    // Recheck, never replace the expectation captured with the displayed responsibility.
                    if (current.financialHandoff?.expectation != financial) invalid(LocalField.EXPECTED_COLLECTION_REVISION)
                    if (action == LocalAction.FOOD_OFFER_HANDOFF) {
                        if (current.summary.handoffId != null) invalid(LocalField.HANDOFF_ID)
                        if (!access.installed.has(LocalFoodHandoffProfileV2.QUERY)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                        requireFoodResponsibilityInstalled(access)
                        // The selection is not a grant. Final protected eligibility is read by
                        // the installed handler on the actual mutation writer, not copied here.
                        val recipientId = values.id(LocalField.RECIPIENT_ID)
                        if (recipientId == access.view.actorId) invalid(LocalField.RECIPIENT_ID)
                        LocalFoodRequestV1.OfferHandoff(orderId, expected, handoffId, recipientId, financial)
                    } else {
                        if (current.summary.handoffId != handoffId) invalid(LocalField.HANDOFF_ID)
                        LocalFoodRequestV1.CancelHandoff(orderId, expected, handoffId, financial)
                    }
                }
            }
            else -> invalid()
        }
    }
    values.finish()
    return routeFreshFoodPreparation(access, intent, request, originalDraft)
}

/** Select the persisted stock route ONLY before saving a new intent. Stored retries never
 * re-enter this function and never change their command identity, normalized bytes or ID. */
private suspend fun routeFreshFoodPreparation(access: GatewayAccess, intent: LocalCommandIntent,
    request: LocalFoodRequestV1, originalDraft: Boolean = false): MutationRequest {
    if (intent.formVersion == 3 && request is LocalFoodRequestV1.Add) {
        // Discovery was checked during first staging. Never downgrade this selected format.
        return LocalFoodStockCodecV2.command(intent.operationId, LocalFoodStockRequestV2.ActivityProtectedQuotedAdd(request))
    }
    if (intent.formVersion == 2 && request is LocalFoodRequestV1.Add) {
        if (!access.installed.has(LocalFoodStockCodecV2.COMMAND)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        return LocalFoodStockCodecV2.command(intent.operationId, LocalFoodStockRequestV2.ActivityProtectedAdd(request))
    }
    // The actual protected empty-order cut has an explicit normalized-only schema.
    // Do not borrow inventory-read authority or reroute a saved original through a profile query.
    if (originalDraft) {
        if (!access.installed.has(LocalFoodStockCodecV2.COMMAND)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        return LocalFoodStockCodecV2.command(intent.operationId, LocalFoodStockRequestV2.Preparation(request))
    }
    if (request is LocalFoodRequestV1.OfferHandoff || request is LocalFoodRequestV1.AcceptHandoff ||
        request is LocalFoodRequestV1.CancelHandoff) return LocalFoodCodecV1.command(intent.operationId, request)
    val normalized = if (request is LocalFoodRequestV1.Station &&
        (request.action == LocalFoodStationActionV1.START || request.action == LocalFoodStationActionV1.READY))
        LocalStockRelationalQueriesV2.normalized(access.read(LocalStockRelationalQueriesV2.stationProfile(request.stationId)).payload)
    else access.installed.has(LocalStockRelationalQueriesV2.PROFILE) &&
        LocalStockRelationalQueriesV2.normalized(access.read(LocalStockRelationalQueriesV2.profile()).payload)
    if (!normalized) return LocalFoodCodecV1.command(intent.operationId, request)
    if (!access.installed.has(LocalFoodStockCodecV2.COMMAND) ||
        request is LocalFoodRequestV1.Station && request.action == LocalFoodStationActionV1.CONFIRM_UNSTARTED)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    return LocalFoodStockCodecV2.command(intent.operationId, LocalFoodStockRequestV2.Preparation(request))
}

/** Exact persisted request decoder for both installed preparation profiles, not rerouting. */
internal fun savedFoodPreparation(request: MutationRequest): LocalFoodRequestV1 = when (request.command) {
    LocalFoodProfileV1.COMMAND -> LocalFoodCodecV1.decode(request.payload)
    LocalFoodStockCodecV2.COMMAND -> when (val decoded = LocalFoodStockCodecV2.decode(request.payload)) {
        is LocalFoodStockRequestV2.ActivityProtectedAdd -> decoded.request
        is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> decoded.request
        is LocalFoodStockRequestV2.Preparation -> decoded.request
        else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

internal suspend fun foodOwnerAt(access: GatewayAccess, orderId: DomainId, expected: Revision,
    after: DomainId? = null): LocalFoodOrderDetailV1 {
    val detail = foodRead(access, LocalFoodQueryV1.Order(orderId, after)).result.detail ?: foodMissing()
    if (detail.summary.revision != expected) invalid(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION)
    return detail
}

private suspend fun foodSelectedPortions(access: GatewayAccess, orderId: DomainId, expected: Revision,
    selected: List<LocalRecordReference>): List<LocalFoodPortionViewV1> {
    val found = mutableMapOf<DomainId, LocalFoodPortionViewV1>()
    var after: DomainId? = null
    // At most four public pages for the actual 32-portion order. Every page must still name
    // the same expected storage generation. This is first-stage validation, not a new snapshot.
    for (page in 0 until 4) {
        val cut = foodRead(access, LocalFoodQueryV1.Order(orderId, after))
        val detail = cut.result.detail ?: foodMissing()
        if (detail.summary.revision != expected) invalid(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION)
        if (detail.summary.handoffId != null) invalid(LocalField.ORDER_ID)
        for (portion in detail.portions) if (selected.any { it.id == portion.portionId }) found[portion.portionId] = portion
        if (found.size == selected.size) break
        after = cut.result.nextAfter ?: break
    }
    return selected.map { reference ->
        (found[reference.id] ?: foodMissing()).also {
            if (it.preparation.revision != reference.revision) invalid(LocalField.PORTION_ID)
        }
    }
}

internal fun foodRequestAction(request: LocalFoodRequestV1): LocalAction = when (request) {
    is LocalFoodRequestV1.Create -> LocalAction.FOOD_CREATE
    is LocalFoodRequestV1.Add -> LocalAction.FOOD_ADD
    is LocalFoodRequestV1.EditServiceDetails -> LocalAction.FOOD_EDIT_SERVICE_DETAILS
    is LocalFoodRequestV1.Send -> LocalAction.FOOD_SEND
    is LocalFoodRequestV1.Serve -> LocalAction.FOOD_SERVE
    is LocalFoodRequestV1.Cancel -> LocalAction.FOOD_CANCEL_PORTION
    is LocalFoodRequestV1.DiscardStarted -> LocalAction.FOOD_DISCARD_STARTED
    is LocalFoodRequestV1.OfferHandoff -> LocalAction.FOOD_OFFER_HANDOFF
    is LocalFoodRequestV1.AcceptHandoff -> LocalAction.FOOD_ACCEPT_HANDOFF
    is LocalFoodRequestV1.CancelHandoff -> LocalAction.FOOD_CANCEL_HANDOFF
    is LocalFoodRequestV1.Station -> when (request.action) {
        LocalFoodStationActionV1.START -> LocalAction.FOOD_STATION_START
        LocalFoodStationActionV1.READY -> LocalAction.FOOD_STATION_READY
        LocalFoodStationActionV1.CONFIRM_UNSTARTED -> LocalAction.FOOD_CONFIRM_UNSTARTED
    }
}
internal fun foodRequestOrderId(request: LocalFoodRequestV1): DomainId = when (request) {
    is LocalFoodRequestV1.Create -> request.orderId
    is LocalFoodRequestV1.Add -> request.orderId
    is LocalFoodRequestV1.EditServiceDetails -> request.orderId
    is LocalFoodRequestV1.Send -> request.orderId
    is LocalFoodRequestV1.Serve -> request.orderId
    is LocalFoodRequestV1.Cancel -> request.orderId
    is LocalFoodRequestV1.DiscardStarted -> request.orderId
    is LocalFoodRequestV1.OfferHandoff -> request.orderId
    is LocalFoodRequestV1.AcceptHandoff -> request.orderId
    is LocalFoodRequestV1.CancelHandoff -> request.orderId
    is LocalFoodRequestV1.Station -> request.orderId
}
internal fun foodRequestTarget(request: LocalFoodRequestV1): DomainId = when (request) {
    is LocalFoodRequestV1.Station -> request.stationId
    is LocalFoodRequestV1.DiscardStarted -> request.stationId
    else -> foodRequestOrderId(request)
}

/** Owner-private immutable-request review; retain intake fields, never use as a service projection. */
internal fun foodIntentSummary(intent: LocalCommandIntent, draft: LocalFoodDraftCreateV1? = null): List<LocalRecordValue> = buildList {
    fun display(value: LocalScalarValue): LocalText = when (value) {
        is LocalScalarValue.Text -> literal(value.value)
        is LocalScalarValue.Identifier -> literal("\u2066${value.value.value}\u2069")
        is LocalScalarValue.Integer -> literal(value.value.toString())
        is LocalScalarValue.Decimal -> literal(value.canonical)
        is LocalScalarValue.Choice -> when (value.code) {
            "RESTAURANT" -> text("Restaurant (RESTAURANT)", "مطعم (RESTAURANT)")
            "CAFE" -> text("Café (CAFE)", "مقهى (CAFE)")
            "DINE_IN" -> text("Dine-in (DINE_IN)", "داخل المكان (DINE_IN)")
            "COUNTER" -> text("Counter (COUNTER)", "الكاونتر (COUNTER)")
            "TAKEAWAY" -> text("Takeaway (TAKEAWAY)", "سفري (TAKEAWAY)")
            "DELIVERY" -> text("Delivery (DELIVERY)", "توصيل (DELIVERY)")
            "RELEASED" -> text("Return to available stock (RELEASED)", "إعادة إلى المخزون المتاح (RELEASED)")
            "QUARANTINE" -> text("Keep in quarantine (QUARANTINE)", "إبقاء في الحجر (QUARANTINE)")
            else -> invalid()
        }
        is LocalScalarValue.Reference -> text("\u2066${value.value.id.value}\u2069 · preparation domain ${value.value.revision?.value}" +
            " · order \u2066${value.value.parentId?.value}\u2069",
            "\u2066${value.value.id.value}\u2069 · مراجعة مجال التحضير ${value.value.revision?.value} · الطلب \u2066${value.value.parentId?.value}\u2069")
        is LocalScalarValue.References -> value.values.map { display(LocalScalarValue.Reference(it)) }.let { refs ->
            text(refs.joinToString("\n") { it.english }, refs.joinToString("\n") { it.arabic })
        }
        else -> invalid()
    }
    val deliveryDraft = draft?.takeIf { it.service.channel == LocalSalesChannel.DELIVERY }
    intent.values.forEach { input ->
        val value = if (input.field == LocalField.SERVICE_REFERENCE && deliveryDraft != null)
            literal(deliveryDraft.service.reference) else display(input.value)
        add(recordValue(fieldLabel(input.field), value))
    }
    if (deliveryDraft != null && intent.values.none { it.field == LocalField.SERVICE_REFERENCE })
        add(recordValue(fieldLabel(LocalField.SERVICE_REFERENCE), literal(deliveryDraft.service.reference)))
    intent.lines.forEachIndexed { index, line ->
        val rows = listOf(recordValue(text("Original commercial line / first serving ID", "معرف السطر التجاري الأصلي / الحصة الأولى"), literal("\u2066${line.lineId.value}\u2069"))) +
            line.values.map { recordValue(fieldLabel(it.field), display(it.value)) }
        var english = ""
        var arabic = ""
        fun flush() {
            if (english.isNotEmpty()) add(recordValue(text("Commercial line ${index + 1}", "السطر التجاري ${index + 1}"), text(english, arabic)))
            english = ""; arabic = ""
        }
        for (row in rows) {
            val en = "${row.label.english}: ${row.value.english}"
            val ar = "${row.label.arabic}: ${row.value.arabic}"
            if (en.length > 2048 || ar.length > 2048) invalid(LocalField.LINES)
            if (english.length + en.length + 1 > 2048 || arabic.length + ar.length + 1 > 2048) flush()
            english += (if (english.isEmpty()) "" else "\n") + en
            arabic += (if (arabic.isEmpty()) "" else "\n") + ar
        }
        flush()
    }
    if (size !in 1..32) invalid(LocalField.LINES)
}

internal fun foodActionLabel(action: LocalAction): LocalText = when (action) {
    LocalAction.FOOD_CREATE_DRAFT -> text("Open an empty food check", "فتح طلب طعام فارغ")
    LocalAction.FOOD_CREATE -> text("Create food check", "إنشاء طلب طعام")
    LocalAction.FOOD_ADD -> text("Add configured servings", "إضافة حصص مُعدّة")
    LocalAction.FOOD_EDIT_SERVICE_DETAILS -> text("Edit table/pickup reference and seat note", "تعديل مرجع الطاولة أو الاستلام وملاحظة المقعد")
    LocalAction.FOOD_SEND -> text("Send selected servings to kitchen", "إرسال الحصص المحددة إلى المطبخ")
    LocalAction.FOOD_STATION_START -> text("Record preparation started here", "تسجيل بدء التحضير هنا")
    LocalAction.FOOD_STATION_READY -> text("Record ready at this station", "تسجيل الجاهزية في هذه المحطة")
    LocalAction.FOOD_CONFIRM_UNSTARTED -> text("Confirm this station has not started", "تأكيد عدم البدء في هذه المحطة")
    LocalAction.FOOD_SERVE -> text("Record dine-in serving — not payment", "تسجيل التقديم داخل المكان — ليس دفعًا")
    LocalAction.FOOD_CANCEL_PORTION -> text("Request portion cancellation", "طلب إلغاء الحصة")
    LocalAction.FOOD_DISCARD_STARTED -> text("Classify started work as waste", "تصنيف العمل الذي بدأ كهالك")
    LocalAction.FOOD_OFFER_HANDOFF -> text("Offer food responsibility handoff", "عرض تسليم مسؤولية الطلب")
    LocalAction.FOOD_ACCEPT_HANDOFF -> text("Accept reviewed food responsibility", "قبول مسؤولية الطعام بعد المراجعة")
    LocalAction.FOOD_CANCEL_HANDOFF -> text("Cancel responsibility handoff offer", "إلغاء عرض تسليم المسؤولية")
    else -> invalid()
}
internal fun foodFieldLabel(field: LocalField): LocalText = when (field) {
    LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION -> text("Expected food order storage revision", "مراجعة تخزين طلب الطعام المتوقعة")
    LocalField.EXPECTED_FOOD_PORTION_DOMAIN_REVISION -> text("Expected preparation domain revision", "مراجعة مجال التحضير المتوقعة")
    LocalField.EXPECTED_FOOD_RECIPE_REVISION -> text("Expected signed recipe revision", "مراجعة الوصفة الموقعة المتوقعة")
    LocalField.RECIPE_ID -> text("Configured recipe ID", "معرف الوصفة المُعدّة")
    LocalField.CHOICE_ID -> text("Complete configured choice ID", "معرف الاختيار المُعدّ الكامل")
    LocalField.STATION_ID -> text("Station ID", "معرف المحطة")
    LocalField.TASK_ID -> text("Station task ID", "معرف مهمة المحطة")
    LocalField.CONFIRMATION_ID -> text("Unstarted confirmation ID", "معرف تأكيد عدم البدء")
    LocalField.SERVICE_REFERENCE -> text("Table/reference label", "وصف الطاولة أو مرجع الطلب")
    LocalField.SEAT_NOTE -> text("Seat/service note", "ملاحظة المقعد أو الخدمة")
    LocalField.FOOD_DELIVERY_RECIPIENT -> text("Delivery recipient", "مستلم التوصيل")
    LocalField.FOOD_DELIVERY_CONTACT -> text("Delivery contact", "بيانات التواصل للتوصيل")
    LocalField.FOOD_DELIVERY_ADDRESS -> text("Delivery address", "عنوان التوصيل")
    LocalField.LINE_SEAT_NOTE -> text("Commercial line seat note", "ملاحظة مقعد السطر التجاري")
    LocalField.FOOD_SERVING_COUNT -> text("Number of configured servings", "عدد الحصص المُعدّة")
    LocalField.FOOD_PORTION_IDS -> text("Original preparation member IDs", "معرفات حصص التحضير الأصلية")
    LocalField.ITEM_NOTE -> text("Serving note (not a recipe change)", "ملاحظة الحصة (ليست تغييرًا للوصفة)")
    LocalField.REASON_ID -> text("Cancellation reason/evidence ID", "معرف سبب الإلغاء أو إثباته")
    else -> invalid()
}
