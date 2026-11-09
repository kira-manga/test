package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

internal fun foodCashFinancialNotice(): LocalText = text(
    "This explicit connection locks original FOOD quotes, applies already retained original CASH facts and finalizes actual stored coverage. It cannot capture cash, create an instruction, Send food, refund or resolve UNKNOWN. Fresh physical collection needs its separate genuine native connection.",
    "يقفل هذا الاتصال الصريح تسعير الطعام الأصلي ويطبق إفادات النقدية الأصلية المحفوظة ويثبت التغطية المخزنة الفعلية. لا يمكنه تحصيل نقد أو إنشاء تعليمات أو إرسال طعام أو رد مبلغ أو حسم نتيجة مجهولة. يتطلب التحصيل الفعلي الجديد اتصاله الأصلي المستقل.")

/** One exact response per page. No draft/Add, kitchen, claim, refund or native forms are built. */
internal suspend fun foodCashFinancialWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    requireFoodCashFinancialWorkflow(query)
    val selected = query.food ?: invalid()
    val reply: ReadReply.Data
    val body: LocalFoodUiBody
    var forms = emptyList<LocalActionForm>()
    var next: LocalFoodUiQuery? = null
    when (selected) {
        is LocalFoodUiQuery.MyChecks -> {
            val cut = foodRead(access, LocalFoodQueryV1.Own(selected.after))
            reply = cut.reply
            if (cut.result.orders.any { it.revision.value < 1 }) unavailable()
            body = LocalFoodUiBody.Checks(cut.result.orders.map(::foodCheck))
            next = cut.result.nextAfter?.let { selected.copy(after = it) }
        }
        is LocalFoodUiQuery.Check -> {
            val cut = foodRead(access, LocalFoodQueryV1.Order(selected.orderId, selected.afterPortionId))
            reply = cut.reply
            val detail = cut.result.detail ?: foodMissing()
            val kitchen = cut.kitchen ?: unavailable()
            if (detail.summary.revision.value < 1 || detail.originalQuotedTotal.currency != Currency.EGP ||
                detail.originalQuotedTotal.minor < 0 ||
                kitchen.unsentPortionCount == 0 && kitchen.status != LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK) unavailable()
            val check = LocalFoodUiBody.Check(foodCheck(detail.summary), detail.portions.map { portion ->
                LocalFoodUiPortion(portion.portionId, portion.description, portion.quantity, portion.itemNote,
                    portion.preparation.revision, LocalFoodPreparation.valueOf(portion.preparation.status.name),
                    LocalFoodFulfillment.valueOf(portion.preparation.fulfillment.name), portion.preparation.started,
                    portion.preparation.cancellationReasonId != null, portion.lineId, portion.seatNote)
            }, detail.originalQuotedTotal, detail.chargeResolutionRequired, detail.financialHandoff?.let(::foodFinancialHandoff),
                LocalFoodUiKitchenObservation(kitchen.orderId, kitchen.orderStorageRevision, kitchen.unsentPortionCount,
                    LocalFoodUiKitchenStatus.valueOf(kitchen.status.name)),
                cut.delivery?.let { LocalFoodUiDeliveryObservation(it.orderId, it.orderStorageRevision,
                    LocalFoodUiDeliveryDetails(it.details.recipient, it.details.contact, it.details.address)) },
                cut.currentUnpaidPayable, tableAssignmentAvailable = false,
                currentUnpaidPayableProfile = cut.currentUnpaidPayableProfile)
            body = check
            forms = listOfNotNull(foodBeginCollectionForm(access, check))
            next = cut.result.nextAfter?.let { selected.copy(afterPortionId = it) }
        }
        is LocalFoodUiQuery.Collection, is LocalFoodUiQuery.Receipt -> {
            val selector = when (selected) {
                is LocalFoodUiQuery.Collection -> LocalFoodFinancialQueryV1.Collection(selected.collectionId)
                is LocalFoodUiQuery.Receipt -> LocalFoodFinancialQueryV1.Receipt(selected.saleId)
                else -> invalid()
            }
            val cut = foodFinancialRead(access, selector)
            reply = cut.reply
            val projection = FoodFinancialProjection(cashFinancialOnly = true)
            val source = when (val result = cut.result) {
                is LocalFoodFinancialReadResultV1.Collection -> {
                    val value = result.value
                    if (value.orderStorageRevision.value < 1 || value.collectionStorageRevision.value < 1) unavailable()
                    projection.collection(value)
                    forms = listOfNotNull(foodFinalizePaidForm(access, value))
                    LocalFoodCashFinancialReadSource.Collection(value.collectionId, value.orderId,
                        value.collectionDomainRevision, value.orderStorageRevision)
                }
                is LocalFoodFinancialReadResultV1.Receipt -> {
                    projection.receipt(result.value)
                    LocalFoodCashFinancialReadSource.ReadOnly(selected)
                }
                else -> unavailable()
            }
            body = projection.body(selected, source)
        }
        is LocalFoodUiQuery.PaymentOriginal -> {
            val cut = foodCashOriginalRead(access, selected.recordingOperationId)
            reply = cut.first
            val original = cut.second
            val summary = original.summary
            val projection = FoodFinancialProjection(cashFinancialOnly = true)
            foodCashOriginalSummary(projection, summary)
            projection.group("original-outcome", text("Immutable observed physical outcome", "النتيجة الفعلية المرصودة الثابتة"),
                retailObservationSummary(original.outcome.foodUiObservation()))
            foodCashOriginalLinks(projection, summary)
            val pending = summary.applicationState == LocalFoodPaymentApplicationState.PENDING_RECONCILIATION
            forms = if (pending) listOf(foodCashApplyOriginalForm(summary)) else emptyList()
            body = projection.body(selected, LocalFoodCashFinancialReadSource.Original(summary.target.recordingOperationId,
                summary.target.orderId, summary.target.collectionId, summary.originalActorId, summary.recordingActorId,
                summary.amount, summary.bodyDigest, pending))
        }
        is LocalFoodUiQuery.PaymentAttempt -> {
            val selector = LocalFoodPaymentQuery.AttemptRecording(selected.orderId, selected.collectionId,
                selected.sessionId, selected.attemptId, selected.intentOperationId)
            val cut = foodCashPaymentRead(access, selector)
            reply = cut.first
            val attempt = cut.second as? LocalFoodPaymentReadResult.AttemptRecording ?: unavailable()
            requireFoodCashAttempt(access, selected, attempt)
            val projection = FoodFinancialProjection(cashFinancialOnly = true)
            projection.group("attempt", text("Original FOOD CASH attempt — DATA only", "محاولة نقدية الطعام الأصلية — بيانات فقط"), listOf(
                foodFinancialId(LocalField.ORDER_ID, attempt.target.orderId),
                foodFinancialId(LocalField.COLLECTION_ID, attempt.target.collectionId),
                foodFinancialId(LocalField.SESSION_ID, attempt.target.sessionId),
                recordValue(text("Attempt ID", "معرف المحاولة"), literal(attempt.target.attemptId.value)),
                recordValue(text("Original intent operation", "عملية الطلب الأصلية"), literal(attempt.target.intentOperationId.value)),
                recordValue(text("Original recording operation", "عملية التسجيل الأصلية"), literal(attempt.target.recordingOperationId.value)),
                recordValue(text("Reserved resolution operation — unavailable here", "عملية الحسم المحجوزة — غير متاحة هنا"), literal(attempt.target.resolutionOperationId.value)),
                recordValue(text("Original amount — not proof of receipt", "المبلغ الأصلي — ليس إثبات استلام"), moneyText(attempt.amount)),
                recordValue(text("Requirement digest — not a capability", "بصمة المتطلب — ليست قدرة تنفيذ"), literal(attempt.requirementDigest.value)),
                recordValue(text("Original SQL fact available", "الإفادة الأصلية متاحة في قاعدة البيانات"), literal((attempt.original != null).toString())),
                recordValue(text("Physical boundary", "حدود الحركة الفعلية"), text(
                    "This read contains only linkage DATA. It cannot supply an instruction or native source; missing SQL original is not proof that no human action occurred.",
                    "تحتوي القراءة على بيانات ربط فقط. لا تقدم تعليمات أو مصدرًا أصليًا؛ غياب الأصل من قاعدة البيانات ليس دليلًا على عدم وقوع حركة بشرية."))))
            attempt.original?.let { foodCashOriginalSummary(projection, it) }
            projection.link(text("Read exact original recording", "قراءة التسجيل الأصلي الدقيق"),
                LocalFoodUiQuery.PaymentOriginal(attempt.target.recordingOperationId))
            body = projection.body(selected, LocalFoodCashFinancialReadSource.ReadOnly(selected))
        }
        is LocalFoodUiQuery.OwnPendingPayments -> {
            val cut = foodCashPaymentRead(access, LocalFoodPaymentQuery.OwnPending(selected.after))
            reply = cut.first
            val pending = cut.second as? LocalFoodPaymentReadResult.OwnPending ?: unavailable()
            requireFoodCashPending(access, selected, pending)
            val projection = FoodFinancialProjection(cashFinancialOnly = true)
            projection.group("pending-page", text("Own pending original CASH accounting", "محاسبة إفاداتي النقدية الأصلية المعلقة"), listOf(
                recordValue(text("Visible own originals", "الأصول الخاصة الظاهرة"), literal(pending.originals.size.toString())),
                recordValue(text("Page boundary", "حدود الصفحة"), text(
                    "The frontier scans accounting rows and may advance past an empty visible page. No original, payment or native action is invented from absence.",
                    "يمسح مؤشر الصفحة صفوف المحاسبة وقد يتقدم بعد صفحة ظاهرة فارغة. لا يُفترض أصل أو دفع أو حركة فعلية من الغياب."))))
            pending.originals.forEach { foodCashOriginalSummary(projection, it); foodCashOriginalLinks(projection, it) }
            next = pending.nextAfter?.let { selected.copy(after = it) }
            body = projection.body(selected, LocalFoodCashFinancialReadSource.ReadOnly(selected))
        }
        else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    access.checkCurrent()
    return LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(), forms, food = LocalFoodUiPage(selected,
        access.view.scope.companyId, access.view.scope.branchId, access.view.actorId, reply.branchRevision, reply.observedAt, body, next))
}

/** Fresh Stage only; actual protected original DATA, never caller metadata, supplies this source. */
internal suspend fun prepareFoodCashFinancialIntent(access: GatewayAccess, intent: LocalCommandIntent): PreparedLocalIntent {
    requireFoodCashFinancialIntent(intent)
    if (intent.action != LocalAction.FOOD_PAYMENT_APPLY) {
        val request = prepareFoodFinancial(access, intent)
        foodCashFinancialRequest(intent.action, request)
        access.checkCurrent()
        return PreparedLocalIntent(request, actionLabel(intent.action), foodFinancialIntentSummary(request, intent))
    }
    val input = InputValues(intent.values)
    val recording = OperationId(input.id(LocalField.FOOD_PAYMENT_RECORDING_OPERATION_ID).value)
    val original = foodCashOriginalRead(access, recording).second
    val summary = original.summary
    if (summary.applicationState != LocalFoodPaymentApplicationState.PENDING_RECONCILIATION)
        invalid(LocalField.FOOD_PAYMENT_RECORDING_OPERATION_ID)
    if (intent.operationId in setOf(summary.target.intentOperationId, summary.target.recordingOperationId,
            summary.target.resolutionOperationId)) invalid(LocalField.FOOD_PAYMENT_RECORDING_OPERATION_ID)
    if (input.id(LocalField.ORDER_ID) != summary.target.orderId) invalid(LocalField.ORDER_ID)
    if (input.id(LocalField.COLLECTION_ID) != summary.target.collectionId) invalid(LocalField.COLLECTION_ID)
    if (input.money(LocalField.AMOUNT) != summary.amount) invalid(LocalField.AMOUNT)
    if (input.text(LocalField.FOOD_PAYMENT_ORIGINAL_BODY_DIGEST) != summary.bodyDigest.value)
        invalid(LocalField.FOOD_PAYMENT_ORIGINAL_BODY_DIGEST)
    input.finish()
    val request = LocalFoodPaymentCodec.command(intent.operationId, LocalFoodPaymentRequest.ApplyOriginal(recording))
    foodCashFinancialRequest(intent.action, request)
    access.checkCurrent()
    return PreparedLocalIntent(request, actionLabel(intent.action), listOf(
        foodFinancialId(LocalField.ORDER_ID, summary.target.orderId),
        foodFinancialId(LocalField.COLLECTION_ID, summary.target.collectionId),
        foodFinancialId(LocalField.SESSION_ID, summary.target.sessionId),
        recordValue(text("Original recording operation", "عملية التسجيل الأصلية"), literal(recording.value)),
        recordValue(text("Original intent operation", "عملية الطلب الأصلية"), literal(summary.target.intentOperationId.value)),
        recordValue(text("Original attempt ID", "معرف المحاولة الأصلية"), literal(summary.target.attemptId.value)),
        recordValue(text("Immutable original body digest", "بصمة الإفادة الأصلية الثابتة"), literal(summary.bodyDigest.value)),
        recordValue(text("Original amount", "المبلغ الأصلي"), moneyText(summary.amount))) +
        retailObservationSummary(original.outcome.foodUiObservation()) + listOf(
            recordValue(text("SQL-only application", "تطبيق محاسبي فقط"), foodCashFinancialNotice())))
}

private fun foodCashApplyOriginalForm(summary: LocalFoodPaymentOriginalSummary): LocalActionForm = form(LocalAction.FOOD_PAYMENT_APPLY,
    listOf(idField(LocalField.FOOD_PAYMENT_RECORDING_OPERATION_ID, DomainId(summary.target.recordingOperationId.value)),
        idField(LocalField.ORDER_ID, summary.target.orderId), idField(LocalField.COLLECTION_ID, summary.target.collectionId),
        moneyField(LocalField.AMOUNT, summary.amount).copy(readOnly = true),
        scalar(LocalField.FOOD_PAYMENT_ORIGINAL_BODY_DIGEST, LocalInputKind.TEXT,
            LocalScalarValue.Text(summary.bodyDigest.value), readOnly = true, maximum = 64)), foodCashFinancialNotice())

private suspend fun foodCashPaymentRead(access: GatewayAccess, query: LocalFoodPaymentQuery): Pair<ReadReply.Data, LocalFoodPaymentReadResult> {
    val reply = access.read(LocalFoodPaymentReadCodec.request(query))
    val value = LocalFoodPaymentReadCodec.decode(reply.payload)
    if (value is LocalFoodPaymentReadResult.NotAvailable) foodMissing()
    if (value is LocalFoodPaymentReadResult.CapacityRequired) throw GatewayFailure(LocalServiceProblem.FOOD_CAPACITY_REQUIRED)
    return reply to value
}

private suspend fun foodCashOriginalRead(access: GatewayAccess, recording: OperationId): Pair<ReadReply.Data, LocalFoodPaymentReadResult.Original> {
    val cut = foodCashPaymentRead(access, LocalFoodPaymentQuery.Original(recording))
    val original = cut.second as? LocalFoodPaymentReadResult.Original ?: unavailable()
    requireFoodOriginal(original)
    if (original.summary.target.recordingOperationId != recording || original.summary.originalActorId != access.view.actorId ||
        original.summary.recordingActorId != access.view.actorId) unavailable()
    return cut.first to original
}

private fun requireFoodCashAttempt(access: GatewayAccess, selected: LocalFoodUiQuery.PaymentAttempt,
    value: LocalFoodPaymentReadResult.AttemptRecording) {
    val target = value.target
    // This is historical DATA for the current original actor, not a physical capability.
    // Bind the original device to its original requirement below, not to today's device.
    if (target.orderId != selected.orderId || target.collectionId != selected.collectionId || target.sessionId != selected.sessionId ||
        target.attemptId != selected.attemptId || target.intentOperationId != selected.intentOperationId ||
        value.amount.currency != Currency.EGP || value.amount.minor <= 0 ||
        value.masterAllocationId == value.clientAllocationId) unavailable()
    val projection = LocalFoodPaymentPhysicalProjection.parse(value.requirementBytes())
    val requirement = projection.requirement
    if (projection.target != target || projection.amount != value.amount || requirement.scope != access.view.scope ||
        requirement.originalActorId != access.view.actorId || requirement.clientDeviceId != value.clientDeviceId ||
        requirement.intentOperationId != target.intentOperationId || requirement.command != LocalFoodPaymentProfile.COMMAND ||
        requirement.domainProfile != LocalFoodPaymentProfile.DOMAIN || requirement.masterAllocationId != value.masterAllocationId ||
        requirement.clientAllocationId != value.clientAllocationId) unavailable()
    value.original?.let { summary ->
        requireFoodSummary(summary)
        if (summary.target != target || summary.originalActorId != access.view.actorId ||
            summary.recordingActorId != access.view.actorId || summary.amount != value.amount) unavailable()
    }
}

private fun requireFoodCashPending(access: GatewayAccess, selected: LocalFoodUiQuery.OwnPendingPayments,
    value: LocalFoodPaymentReadResult.OwnPending) {
    val ids = value.originals.map { it.target.recordingOperationId.value }
    val after = selected.after
    val nextAfter = value.nextAfter
    if (ids.size > 25 || ids.distinct().size != ids.size || ids != ids.sorted() ||
        ids.any { after != null && it <= after.value || nextAfter != null && it > nextAfter.value } ||
        nextAfter != null && after != null && nextAfter.value <= after.value) unavailable()
    value.originals.forEach { summary ->
        requireFoodSummary(summary)
        if (summary.originalActorId != access.view.actorId || summary.recordingActorId != access.view.actorId ||
            summary.applicationState != LocalFoodPaymentApplicationState.PENDING_RECONCILIATION) unavailable()
    }
}

private fun foodCashOriginalSummary(projection: FoodFinancialProjection, summary: LocalFoodPaymentOriginalSummary) {
    val target = summary.target
    projection.group("original-${target.recordingOperationId.value}", text("Immutable original and current accounting", "الإفادة الأصلية الثابتة والمحاسبة الحالية"), buildList {
        add(foodFinancialId(LocalField.ORDER_ID, target.orderId))
        add(foodFinancialId(LocalField.COLLECTION_ID, target.collectionId))
        add(foodFinancialId(LocalField.SESSION_ID, target.sessionId))
        add(recordValue(text("Attempt ID", "معرف المحاولة"), literal(target.attemptId.value)))
        add(recordValue(text("Original intent operation", "عملية الطلب الأصلية"), literal(target.intentOperationId.value)))
        add(recordValue(text("Original recording operation", "عملية التسجيل الأصلية"), literal(target.recordingOperationId.value)))
        add(recordValue(text("Reserved resolution operation — unavailable here", "عملية الحسم المحجوزة — غير متاحة هنا"), literal(target.resolutionOperationId.value)))
        add(recordValue(text("Original actor", "الموظف الأصلي"), literal(summary.originalActorId.value)))
        add(recordValue(text("Recording actor", "موظف التسجيل"), literal(summary.recordingActorId.value)))
        add(recordValue(text("Immutable original body digest", "بصمة الإفادة الأصلية الثابتة"), literal(summary.bodyDigest.value)))
        add(recordValue(text("Original retained at", "وقت حفظ الإفادة الأصلية"), literal(summary.recordedAt.canonicalString())))
        add(recordValue(text("Original amount", "المبلغ الأصلي"), moneyText(summary.amount)))
        add(recordValue(text("Accounting state", "حالة المحاسبة"), literal(summary.applicationState.name)))
        summary.pendingReason?.let { add(recordValue(text("Pending reason", "سبب التعليق"), literal(it.name))) }
        summary.appliedAction?.let { add(recordValue(text("Applied observation — UNKNOWN stays unresolved", "الإفادة المطبقة — تظل النتيجة المجهولة غير محسومة"), literal(it.name))) }
        summary.lastApplicationOperationId?.let { add(recordValue(text("Original application operation", "عملية التطبيق الأصلية"), literal(it.value))) }
    })
}

private fun foodCashOriginalLinks(projection: FoodFinancialProjection, summary: LocalFoodPaymentOriginalSummary) {
    val target = summary.target
    projection.link(text("Read exact original recording", "قراءة التسجيل الأصلي الدقيق"), LocalFoodUiQuery.PaymentOriginal(target.recordingOperationId))
    projection.link(text("Read exact original attempt — DATA only", "قراءة المحاولة الأصلية الدقيقة — بيانات فقط"),
        LocalFoodUiQuery.PaymentAttempt(target.orderId, target.collectionId, target.sessionId, target.attemptId, target.intentOperationId))
    projection.link(text("Read collection — separate current authorization", "قراءة التحصيل — صلاحية حالية منفصلة"), LocalFoodUiQuery.Collection(target.collectionId))
}
