package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.sales.FoodCollectionStatus
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CancellationException

internal data class FoodFinancialReadCut(val reply: ReadReply.Data, val result: LocalFoodFinancialReadResultV1)

/** One authorized response. Missing provenance/stock/capacity is never an empty financial page. */
internal suspend fun foodFinancialRead(access: GatewayAccess, query: LocalFoodFinancialQueryV1): FoodFinancialReadCut {
    val reply = access.read(LocalFoodFinancialReadCodecV1.read(query))
    val result = LocalFoodFinancialReadCodecV1.result(reply.payload)
    if (result is LocalFoodFinancialReadResultV1.Unavailable) throw GatewayFailure(when (result.reason) {
        LocalFoodFinancialUnavailableV1.TARGET_UNAVAILABLE -> LocalServiceProblem.FOOD_TARGET_UNAVAILABLE
        LocalFoodFinancialUnavailableV1.CAPACITY_REQUIRED -> LocalServiceProblem.FOOD_CAPACITY_REQUIRED
    })
    when (query) {
        is LocalFoodFinancialQueryV1.Collection -> {
            val value = (result as? LocalFoodFinancialReadResultV1.Collection)?.value ?: unavailable()
            if (value.collectionId != query.collectionId || value.executionProfile !in setOf("NONPHYSICAL_ONLY", "GENUINE_FOOD_CASH_V1") ||
                FoodCollectionStatus.entries.none { it.name == value.status } || value.portions.size !in 1..32 ||
                value.portions.map { it.portionId }.distinct().size != value.portions.size || value.attempts.size > 64 ||
                value.attempts.map { it.attemptId }.distinct().size != value.attempts.size || value.unresolvedClaimIds.size > 64 ||
                value.unresolvedClaimIds.distinct().size != value.unresolvedClaimIds.size) unavailable()
        }
        is LocalFoodFinancialQueryV1.Receipt -> {
            val value = (result as? LocalFoodFinancialReadResultV1.Receipt)?.value ?: unavailable()
            if (value.saleId != query.saleId || value.lines.size !in 1..32 ||
                value.lines.map { it.portionId }.distinct().size != value.lines.size) unavailable()
        }
        is LocalFoodFinancialQueryV1.Refund -> {
            val value = (result as? LocalFoodFinancialReadResultV1.Refund)?.value ?: unavailable()
            if (value.refundId != query.refundId || value.attempts.size > 64 ||
                value.attempts.map { it.attemptId }.distinct().size != value.attempts.size ||
                value.attempts.any { !it.ownAttempt && (it.intentOperationId != null || it.appliedRefundObservationId != null) }) unavailable()
        }
        is LocalFoodFinancialQueryV1.Claim -> {
            val value = result as? LocalFoodFinancialReadResultV1.Claims ?: unavailable()
            if (value.claims.singleOrNull()?.claimId != query.claimId || value.nextAfter != null) unavailable()
        }
        is LocalFoodFinancialQueryV1.OwnClaims -> {
            val value = result as? LocalFoodFinancialReadResultV1.Claims ?: unavailable()
            foodPageCoordinates(value.claims.map { it.claimId }, query.after, value.nextAfter, query.limit)
        }
    }
    return FoodFinancialReadCut(reply, result)
}

internal suspend fun foodFinancialWorkflow(access: GatewayAccess, query: LocalFoodUiQuery,
    fulfillmentHandover: Boolean = false): LocalWorkflowPage {
    if (fulfillmentHandover && query !is LocalFoodUiQuery.Collection) invalid()
    if (query is LocalFoodUiQuery.Refund && access.installed.has(LocalFoodRefundReadCodec.QUERY)) {
        // Genuine liability read uses today's scoped payer permission, not the old accepting
        // employee's private collection read. It still authenticates the full accepted source.
        val cut = foodRefundObligationRead(access, query.refundId)
        val projection = FoodFinancialProjection()
        projection.refund(cut.result.value)
        val page = LocalFoodUiPage(query, access.view.scope.companyId, access.view.scope.branchId, access.view.actorId,
            cut.reply.branchRevision, cut.reply.observedAt, projection.body(query))
        return LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(), emptyList(), food = page)
    }
    val selected = when (query) {
        is LocalFoodUiQuery.Collection -> LocalFoodFinancialQueryV1.Collection(query.collectionId)
        is LocalFoodUiQuery.Receipt -> LocalFoodFinancialQueryV1.Receipt(query.saleId)
        is LocalFoodUiQuery.Refund -> LocalFoodFinancialQueryV1.Refund(query.refundId)
        is LocalFoodUiQuery.Claim -> LocalFoodFinancialQueryV1.Claim(query.claimId)
        is LocalFoodUiQuery.OwnClaims -> LocalFoodFinancialQueryV1.OwnClaims(query.after)
        else -> invalid()
    }
    val cut = foodFinancialRead(access, selected)
    val projection = FoodFinancialProjection()
    var forms = emptyList<LocalActionForm>()
    var next: LocalFoodUiQuery? = null
    when (val result = cut.result) {
        is LocalFoodFinancialReadResultV1.Collection -> {
            projection.collection(result.value)
            forms = foodFinancialForms(access, result.value)
            val narrowSend = forms.any { it.action == LocalAction.FOOD_SEND_RESERVED }
            val narrowPickup = fulfillmentHandover && forms.any { it.action == LocalAction.FOOD_PAID_PICKUP }
            if (narrowSend || narrowPickup) {
                // This separate owner cut narrows FRESH operational forms only. Its absence
                // must not retire or rewrite the genuine financial body or its recovery forms.
                val operational = foodCollectionFreshOperationCut(access, result.value)
                val channel = operational?.result?.detail?.summary?.service?.channel
                forms = forms.filter { when (it.action) {
                    LocalAction.FOOD_SEND_RESERVED -> operational != null && channel != LocalSalesChannel.DELIVERY &&
                        !operational.kitchenUnavailableForUnsentWork
                    LocalAction.FOOD_PAID_PICKUP -> !narrowPickup || operational != null &&
                        channel in setOf(LocalSalesChannel.COUNTER, LocalSalesChannel.TAKEAWAY)
                    else -> true
                } }
            }
        }
        is LocalFoodFinancialReadResultV1.Receipt -> projection.receipt(result.value)
        is LocalFoodFinancialReadResultV1.Refund -> projection.refund(result.value)
        is LocalFoodFinancialReadResultV1.Claims -> {
            result.claims.forEach { projection.claim(it) }
            if (query is LocalFoodUiQuery.OwnClaims) {
                forms = listOfNotNull(foodQualityClaimForm(access))
                next = result.nextAfter?.let { query.copy(after = it) }
            }
        }
        is LocalFoodFinancialReadResultV1.Unavailable -> unavailable()
    }
    val page = LocalFoodUiPage(query, access.view.scope.companyId, access.view.scope.branchId, access.view.actorId,
        cut.reply.branchRevision, cut.reply.observedAt, projection.body(query), next)
    return LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(), forms, food = page)
}

private suspend fun foodCollectionFreshOperationCut(access: GatewayAccess,
    collection: LocalFoodCollectionViewV1): FoodReadCut? {
    try {
        val cut = foodRead(access, LocalFoodQueryV1.Order(collection.orderId))
        val detail = cut.result.detail ?: return null
        val order = detail.summary
        val financial = detail.financialHandoff?.expectation ?: return null
        if (order.orderId != collection.orderId || order.revision != collection.orderStorageRevision ||
            order.financialCollectionId != collection.collectionId || order.workClosed || order.handoffId != null ||
            financial.collectionId != collection.collectionId ||
            financial.expectedCollectionRevision != collection.collectionDomainRevision) return null
        return cut
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { return null }
}

/** Complete bounded rendering, not a 32-value LocalRecord with the remaining ledger truncated. */
internal class FoodFinancialProjection(private val cashFinancialOnly: Boolean = false) {
    private val groups = mutableListOf<LocalFoodFinancialGroup>()
    private val links = mutableListOf<LocalFoodFinancialLink>()

    fun group(key: String, title: LocalText, values: List<LocalRecordValue>) {
        if (groups.size >= 2048 || values.size > 32) throw GatewayFailure(LocalServiceProblem.FOOD_CAPACITY_REQUIRED)
        groups += LocalFoodFinancialGroup(key, title, values)
    }
    fun link(title: LocalText, query: LocalFoodUiQuery) {
        val link = LocalFoodFinancialLink(title, query)
        if (link in links) return
        if (links.size >= 96) throw GatewayFailure(LocalServiceProblem.FOOD_CAPACITY_REQUIRED)
        links += link
    }
    fun body(query: LocalFoodUiQuery, source: LocalFoodCashFinancialReadSource? = null): LocalFoodUiBody.Financial =
        LocalFoodUiBody.Financial(query, groups.toList(), links.toList(), source)

    fun collection(value: LocalFoodCollectionViewV1) {
        group("collection", text("Food collection and responsibility", "تحصيل الطعام والمسؤولية"), buildList {
            add(foodFinancialId(LocalField.COLLECTION_ID, value.collectionId))
            add(foodFinancialId(LocalField.ORDER_ID, value.orderId))
            add(foodFinancialRevision("Collection storage revision", "مراجعة تخزين التحصيل", value.collectionStorageRevision))
            add(foodFinancialRevision("Collection domain revision", "مراجعة مجال التحصيل", value.collectionDomainRevision))
            add(foodFinancialRevision("Order storage revision", "مراجعة تخزين الطلب", value.orderStorageRevision))
            add(foodFinancialRevision("Locked commercial storage revision", "مراجعة التخزين التجاري المقفلة", value.lockedCommercialStorageRevision))
            add(recordValue(text("Collection state", "حالة التحصيل"), foodFinancialState(value.status)))
            add(recordValue(text("Original payable", "المبلغ الأصلي المستحق"), moneyText(value.originalPayable)))
            add(recordValue(text("Actually recorded collections", "التحصيل الفعلي المسجل"), moneyText(value.collected)))
            add(recordValue(text("Unpaid remaining", "المتبقي غير المدفوع"), moneyText(value.unpaidRemaining)))
            add(recordValue(text("Refund liability still due — not paid", "التزام الرد المستحق — غير مدفوع"), moneyText(value.refundLiabilityDue)))
            add(recordValue(text("Work responsibility closed", "أُغلقت مسؤولية العمل"), foodFinancialBoolean(value.workClosed)))
            add(recordValue(text("Portions / payment attempts / unresolved claims", "الحصص / محاولات الدفع / المطالبات غير المحسومة"),
                literal("${value.portions.size} / ${value.attempts.size} / ${value.unresolvedClaimIds.size}")))
            add(recordValue(text("Execution profile", "ملف التنفيذ"), literal(value.executionProfile)))
            value.saleId?.let { add(foodFinancialId(LocalField.SALE_ID, it)) }
            value.cancellationRefundId?.let { add(foodFinancialId(LocalField.RETURN_ID, it)) }
            add(recordValue(text("Money boundary", "حدود التعامل بالمال"), if (value.executionProfile == "GENUINE_FOOD_CASH_V1") text(
                "Cash collection uses the separate original native instruction. This financial read grants no payment authority. Refund and UNKNOWN resolution remain unavailable in that payment lane.",
                "يستخدم التحصيل النقدي تعليمات أصلية مستقلة. لا تمنح هذه القراءة المالية صلاحية دفع. ما زال رد المبلغ وتسوية النتيجة المجهولة غير متاحين في مسار الدفع هذا.")
            else foodFinancialNotice()))
        })
        value.portions.forEach { portion -> group("portion-${portion.portionId.value}", text("Portion responsibility", "مسؤولية الحصة"), listOf(
            foodFinancialId(LocalField.PORTION_ID, portion.portionId),
            recordValue(text("Original quantity", "الكمية الأصلية"), quantityText(portion.originalQuantity)),
            foodFinancialRevision("Preparation domain revision", "مراجعة مجال التحضير", portion.preparationDomainRevision),
            recordValue(text("Preparation", "التحضير"), foodFinancialState(portion.preparationStatus.name)),
            recordValue(text("Fulfillment", "التسليم"), foodFinancialState(portion.fulfillment.name)),
            recordValue(text("Ingredient origin", "مصدر المكونات"), foodFinancialState(portion.stockOrigin.name)),
            recordValue(text("Reservation state", "حالة الحجز"), foodFinancialState(portion.reservationStatus)),
            recordValue(text("Charge decision required", "يلزم قرار الرسوم"), foodFinancialBoolean(portion.chargeDecisionRequired)))) }
        value.attempts.forEach { attempt -> group("payment-${attempt.attemptId.value}", text("Original payment attempt", "محاولة الدفع الأصلية"), buildList {
            add(recordValue(text("Attempt ID", "معرف المحاولة"), foodFinancialIdentifier(attempt.attemptId.value)))
            add(recordValue(text("State", "الحالة"), foodFinancialState(attempt.status)))
            addAll(foodFinancialTenderValues(attempt.tender))
            add(recordValue(text("Attempt amount — not itself proof of receipt", "مبلغ المحاولة — ليس وحده إثبات استلام"), moneyText(attempt.amount)))
            add(recordValue(text("Original intent operation", "عملية الطلب الأصلية"), foodFinancialIdentifier(attempt.intentOperationId.value)))
            attempt.appliedPaymentObservationId?.let { add(recordValue(text("Applied payment observation", "ملاحظة الدفع المطبقة"), foodFinancialIdentifier(it.value))) }
        }) }
        value.unresolvedClaimIds.forEach { id ->
            group("pending-claim-${id.value}", text("Unresolved claim", "مطالبة غير محسومة"), listOf(foodFinancialId(LocalField.CASE_ID, id)))
            if (!cashFinancialOnly) link(text("Read claim ${id.value}", "قراءة المطالبة ${id.value}"), LocalFoodUiQuery.Claim(id))
        }
        link(text("Read owned check", "قراءة طلب أملكه"), LocalFoodUiQuery.Check(value.orderId))
        value.saleId?.let { link(text("Read original paid receipt", "قراءة إيصال البيع المدفوع الأصلي"), LocalFoodUiQuery.Receipt(it)) }
        if (!cashFinancialOnly) value.cancellationRefundId?.let {
            link(text("Read refund obligation — not payout", "قراءة التزام الرد — ليس صرفًا"), LocalFoodUiQuery.Refund(it))
        }
    }

    fun receipt(value: LocalFoodPaidReceiptViewV1) {
        group("receipt", text("Original food paid document", "مستند بيع الطعام المدفوع الأصلي"), listOf(
            foodFinancialId(LocalField.SALE_ID, value.saleId),
            foodFinancialRevision("Sale storage revision", "مراجعة تخزين البيع", value.saleStorageRevision),
            foodFinancialRevision("Entitlement storage revision", "مراجعة تخزين الاستحقاق", value.entitlementStorageRevision),
            foodFinancialRevision("Entitlement domain revision", "مراجعة مجال الاستحقاق", value.entitlementDomainRevision),
            recordValue(text("Finalized at", "وقت تثبيت البيع"), literal(value.finalizedAt.canonicalString())),
            recordValue(text("Original payable", "المبلغ الأصلي المستحق"), moneyText(value.originalPayable)),
            recordValue(text("Remedy qualification", "تأهيل المعالجة"), literal(value.remedyQualification)),
            recordValue(text("Receipt boundary", "حدود الإيصال"), text(
                "Original allocations are not approved refunds, delivery evidence or fiscal acceptance. This view does not print or deliver a receipt. No current-price or stock substitution is made.",
                "التخصيصات الأصلية ليست ردًا معتمدًا أو إثبات تسليم أو قبولًا ضريبيًا. هذه الشاشة لا تطبع الإيصال أو تسلمه. لا يُستبدل السعر أو المخزون الحالي بالمصدر الأصلي."))))
        value.lines.forEach { line -> group("receipt-line-${line.portionId.value}", text("Original receipt portion", "حصة الإيصال الأصلية"), listOf(
            foodFinancialId(LocalField.PORTION_ID, line.portionId),
            recordValue(text("Original price batch", "دفعة التسعير الأصلية"), foodFinancialIdentifier(line.batchId.value)),
            recordValue(text("Original quantity", "الكمية الأصلية"), quantityText(line.originalQuantity)),
            recordValue(text("Remaining original quantity", "الكمية الأصلية المتبقية"), quantityText(line.remainingOriginalQuantity)),
            recordValue(text("Original allocated value", "القيمة الأصلية المخصصة"), moneyText(line.originalValue)),
            recordValue(text("Remaining original value — not approved payout", "القيمة الأصلية المتبقية — ليست صرفًا معتمدًا"), moneyText(line.remainingOriginalValue)))) }
        value.components.forEachIndexed { index, component -> group("component-$index", text("Original price component ${index + 1}", "مكوّن السعر الأصلي ${index + 1}"), listOf(
            recordValue(text("Price batch", "دفعة التسعير"), foodFinancialIdentifier(component.batchId.value)),
            recordValue(text("Original component key", "مفتاح المكوّن الأصلي"), literal(component.originalKey)),
            foodFinancialId(LocalField.PORTION_ID, component.portionId),
            recordValue(text("Component kind", "نوع المكوّن"), literal(component.kind)),
            recordValue(text("Original posted amount", "المبلغ الأصلي المثبت"), moneyText(component.originalPosted)))) }
        value.originalTenders.forEachIndexed { index, tender -> group("original-tender-$index", text("Original tender allocation", "تخصيص وسيلة الدفع الأصلية"),
            listOf(recordValue(text("Original payment ID", "معرف الدفع الأصلي"), foodFinancialIdentifier(tender.originalPaymentId.value)),
                recordValue(text("Original allocation", "التخصيص الأصلي"), moneyText(tender.amount))) + foodFinancialTenderValues(tender.tender)) }
        value.remainingComponentTenders.forEachIndexed { componentIndex, component ->
            group("remaining-component-$componentIndex", text("Remaining original component allocation", "تخصيص المكوّن الأصلي المتبقي"), listOf(
                recordValue(text("Component key", "مفتاح المكوّن"), literal(component.componentKey)),
                recordValue(text("Original payment share count", "عدد حصص المدفوعات الأصلية"), literal(component.shares.size.toString()))))
            component.shares.forEachIndexed { shareIndex, share -> group("share-$componentIndex-$shareIndex", text("Remaining original tender share", "حصة وسيلة الدفع الأصلية المتبقية"), listOf(
                recordValue(text("Component key", "مفتاح المكوّن"), literal(component.componentKey)),
                recordValue(text("Original payment ID", "معرف الدفع الأصلي"), foodFinancialIdentifier(share.originalPaymentId.value)),
                recordValue(text("Remaining allocation", "التخصيص المتبقي"), moneyText(share.amount)))) }
        }
    }

    fun refund(value: LocalFoodRefundViewV1) {
        group("refund", text("Food refund obligation — not a payout", "التزام رد مبلغ الطعام — ليس صرفًا"), buildList {
            add(foodFinancialId(LocalField.RETURN_ID, value.refundId))
            add(foodFinancialId(LocalField.COLLECTION_ID, value.collectionId))
            value.saleId?.let { add(foodFinancialId(LocalField.SALE_ID, it)) }
            add(foodFinancialRevision("Refund storage revision", "مراجعة تخزين الرد", value.refundStorageRevision))
            add(foodFinancialRevision("Refund domain revision", "مراجعة مجال الرد", value.refundDomainRevision))
            add(recordValue(text("Original quoted total", "إجمالي التسعير الأصلي"), moneyText(value.originalQuotedTotal)))
            add(recordValue(text("Actual original-tender liability", "الالتزام الفعلي بوسيلة الدفع الأصلية"), moneyText(value.actualTenderLiability)))
            add(recordValue(text("Actually recorded refunds", "المبالغ المردودة فعليًا والمسجلة"), moneyText(value.refunded)))
            add(recordValue(text("Refund still due", "المبلغ المستحق رده"), moneyText(value.due)))
            add(recordValue(text("State", "الحالة"), foodFinancialState(value.status)))
            add(recordValue(text("Refund boundary", "حدود رد المبلغ"), text("No physical refund is initiated here. Due is a liability, never spendable store credit or proof of payout.",
                "لا يبدأ رد مبلغ فعلي هنا. المستحق التزام وليس رصيد متجر قابلًا للإنفاق أو إثبات صرف.")))
        })
        value.obligations.forEachIndexed { index, obligation -> group("obligation-$index", text("Original tender obligation", "الالتزام بوسيلة الدفع الأصلية"),
            listOf(recordValue(text("Original payment ID", "معرف الدفع الأصلي"), foodFinancialIdentifier(obligation.originalPaymentId.value)),
                recordValue(text("Liability allocation", "تخصيص الالتزام"), moneyText(obligation.amount))) + foodFinancialTenderValues(obligation.tender)) }
        value.attempts.forEach { attempt -> group("refund-attempt-${attempt.attemptId.value}", text("Original refund attempt", "محاولة الرد الأصلية"), buildList {
            add(recordValue(text("Attempt ID", "معرف المحاولة"), foodFinancialIdentifier(attempt.attemptId.value)))
            add(recordValue(text("Original payment ID", "معرف الدفع الأصلي"), foodFinancialIdentifier(attempt.originalPaymentId.value)))
            add(recordValue(text("State", "الحالة"), foodFinancialState(attempt.status)))
            addAll(foodFinancialTenderValues(attempt.tender))
            add(recordValue(text("Attempt amount — not itself proof of payout", "مبلغ المحاولة — ليس وحده إثبات صرف"), moneyText(attempt.amount)))
            add(recordValue(text("Your original attempt", "محاولتك الأصلية"), foodFinancialBoolean(attempt.ownAttempt)))
            attempt.intentOperationId?.let { add(recordValue(text("Original intent operation", "عملية الطلب الأصلية"), foodFinancialIdentifier(it.value))) }
            attempt.appliedRefundObservationId?.let { add(recordValue(text("Applied refund observation", "ملاحظة الرد المطبقة"), foodFinancialIdentifier(it.value))) }
        }) }
        link(text("Read collection responsibility", "قراءة مسؤولية التحصيل"), LocalFoodUiQuery.Collection(value.collectionId))
        value.saleId?.let { link(text("Read original paid receipt", "قراءة إيصال البيع المدفوع الأصلي"), LocalFoodUiQuery.Receipt(it)) }
    }

    fun claim(value: LocalFoodQualityClaimViewV1) {
        group("claim-${value.claimId.value}", text("Unresolved customer quality/fault claim", "مطالبة العميل غير المحسومة بالجودة أو الخطأ"), listOf(
            foodFinancialId(LocalField.CASE_ID, value.claimId),
            foodFinancialRevision("Claim storage revision", "مراجعة تخزين المطالبة", value.storageRevision),
            recordValue(text("Recorded at master", "وقت التسجيل بالجهاز الرئيسي"), literal(value.recordedAt.canonicalString())),
            recordValue(text("Disposition", "حالة المعالجة"), literal(value.disposition))) + foodQualityClaimValues(value.facts))
        link(text("Read claim ${value.claimId.value}", "قراءة المطالبة ${value.claimId.value}"), LocalFoodUiQuery.Claim(value.claimId))
        value.facts.assertedOrderId?.let { link(text("Read asserted order ${it.value} — separate authorization", "قراءة الطلب المزعوم ${it.value} — صلاحية منفصلة"), LocalFoodUiQuery.Check(it)) }
        value.facts.assertedSaleId?.let { link(text("Read asserted receipt ${it.value} — not yet verified", "قراءة الإيصال المزعوم ${it.value} — لم يُتحقق منه"), LocalFoodUiQuery.Receipt(it)) }
    }
}

internal fun foodFinancialIdentifier(value: String): LocalText = literal("\u2066$value\u2069")
internal fun foodFinancialId(field: LocalField, id: DomainId): LocalRecordValue = recordValue(fieldLabel(field), foodFinancialIdentifier(id.value))
internal fun foodFinancialRevision(english: String, arabic: String, revision: Revision): LocalRecordValue =
    recordValue(text(english, arabic), literal(revision.value.toString()))
private fun foodFinancialBoolean(value: Boolean): LocalText = if (value) text("Yes", "نعم") else text("No", "لا")
private fun foodFinancialTenderValues(value: LocalTenderViewV1): List<LocalRecordValue> = buildList {
    add(recordValue(text("Tender kind", "نوع وسيلة الدفع"), foodFinancialState(value.kind)))
    value.tenderId?.let { add(foodFinancialId(LocalField.TENDER_ID, it)) }
    value.policyRevision?.let { add(foodFinancialRevision("Original tender policy revision", "مراجعة سياسة وسيلة الدفع الأصلية", it)) }
    add(recordValue(text("Terminal evidence required", "يلزم إثبات جهاز الدفع"), foodFinancialBoolean(value.requiresTerminal)))
    add(recordValue(text("Reference evidence required", "يلزم إثبات المرجع"), foodFinancialBoolean(value.requiresReference)))
}

internal fun foodQualityClaimValues(value: LocalFoodClaimFactsV1): List<LocalRecordValue> = listOf(
    recordValue(text("Claim category", "فئة المطالبة"), foodFinancialState(value.kind.name)),
    recordValue(text("Asserted order — unverified", "الطلب المذكور — غير متحقق منه"), value.assertedOrderId?.let { foodFinancialIdentifier(it.value) } ?: foodNotAsserted()),
    recordValue(text("Asserted receipt — unverified", "الإيصال المذكور — غير متحقق منه"), value.assertedSaleId?.let { foodFinancialIdentifier(it.value) } ?: foodNotAsserted()),
    recordValue(text("Asserted portion — unverified", "الحصة المذكورة — غير متحقق منها"), value.assertedPortionId?.let { foodFinancialIdentifier(it.value) } ?: foodNotAsserted()),
    recordValue(text("Asserted quantity / unit — unverified", "الكمية والوحدة المذكورتان — غير متحقق منهما"), value.assertedQuantity?.let(::quantityText) ?: foodNotAsserted()),
    recordValue(text("Asserted quantity scale", "دقة الكمية المذكورة"), value.assertedQuantity?.let { literal(it.scale.toString()) } ?: foodNotAsserted()),
    recordValue(text("Asserted received time — not delivery proof", "وقت الاستلام المذكور — ليس إثبات تسليم"), value.assertedReceivedAt?.let { literal(it.canonicalString()) } ?: foodNotAsserted()),
    recordValue(fieldLabel(LocalField.CLAIMED_AT), literal(value.claimedAt.canonicalString())),
    recordValue(fieldLabel(LocalField.REASON), literal(value.reason)),
    foodFinancialId(LocalField.EVIDENCE_ID, value.evidence.evidenceId),
    recordValue(fieldLabel(LocalField.OCCURRED_AT), literal(value.evidence.occurredAt.canonicalString())))

private fun foodNotAsserted(): LocalText = text("Not asserted / unknown", "غير مذكور / غير معلوم")
private fun foodFinancialState(value: String): LocalText = when (value) {
    "LOCKED_UNPAID" -> text("Locked, unpaid", "مقفل، غير مدفوع")
    "PART_PAID" -> text("Partly paid", "مدفوع جزئيًا")
    "OUTCOME_UNKNOWN" -> text("Outcome unresolved", "النتيجة غير محسومة")
    "COVERED" -> text("Fully covered, not finalized", "مغطى بالكامل، لم يُثبت البيع")
    "FINALIZED_PAID" -> text("Paid sale retained", "البيع المدفوع محفوظ")
    "CANCELLED_REFUND_OBLIGATION" -> text("Cancelled; refund obligation retained", "ملغى؛ التزام الرد محفوظ")
    "NOT_SENT" -> text("Not sent", "لم يُرسل")
    "QUEUED" -> text("Queued, not started", "في الانتظار، لم يبدأ")
    "PREPARING" -> text("Preparation started", "بدأ التحضير")
    "READY" -> text("Ready — not customer receipt", "جاهز — ليس استلام العميل")
    "CANCEL_PENDING" -> text("Kitchen cancellation unresolved", "إلغاء المطبخ غير محسوم")
    "CANCELLED_BEFORE_START" -> text("Cancelled before start", "أُلغي قبل البدء")
    "WASTE_AFTER_START" -> text("Waste after start; ingredients consumed", "هالك بعد البدء؛ المكونات مستهلكة")
    "NOT_HANDED_OVER" -> text("Not handed over", "لم يُسلّم")
    "SERVED" -> text("Served", "تم التقديم")
    "PICKED_UP" -> text("Picked up", "تم الاستلام")
    "RESERVED_FOR_COLLECTION" -> text("Reserved before collection", "محجوز قبل التحصيل")
    "ISSUED_BEFORE_COLLECTION" -> text("Issued before collection", "صُرف قبل التحصيل")
    "RESERVED" -> text("Reserved", "محجوز")
    "ISSUED" -> text("Issued", "مصروف")
    "RELEASED" -> text("Reservation released", "أُلغي الحجز")
    "AWAITING_ATTESTATION" -> text("Awaiting actual outcome attestation", "بانتظار إفادة النتيجة الفعلية")
    "NOT_PAID" -> text("Not paid", "غير مدفوع")
    "ATTESTED_PAID" -> text("Actual payment attested", "أُفيد بالدفع الفعلي")
    "CASH" -> text("Cash", "نقد")
    "EXTERNAL" -> text("External tender", "وسيلة دفع خارجية")
    "QUALITY" -> text("Quality complaint", "شكوى جودة")
    "MERCHANT_FAULT" -> text("Merchant fault claim", "مطالبة بخطأ التاجر")
    "OTHER" -> text("Other claim", "مطالبة أخرى")
    else -> literal(value)
}
