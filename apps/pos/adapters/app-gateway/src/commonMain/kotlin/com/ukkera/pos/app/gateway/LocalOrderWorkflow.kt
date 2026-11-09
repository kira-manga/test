package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.LocalConfigurationDetailV1
import com.ukkera.pos.catalog.store.LocalConfigurationProfile
import com.ukkera.pos.catalog.store.LocalConfigurationQueryV1
import com.ukkera.pos.catalog.store.LocalFulfillmentV1
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity

internal data class OrderWorkflowCut(val reply: ReadReply.Data, val orders: List<LocalOrderViewV1>,
    val nextAfter: DomainId?, val stockProfiles: Map<DomainId, LocalOrderStockProfileV2>)

/** Old readers remain usable for display, but a missing discriminator cannot select a writer. */
internal suspend fun orderRead(access: GatewayAccess, query: LocalOrderQueryV1): OrderWorkflowCut {
    if (access.installed.has(LocalOrderReadCodecV2.QUERY)) {
        val reply = access.read(LocalOrderReadCodecV2.request(query))
        val result = LocalOrderReadCodecV2.decode(reply.payload)
        if (result.orders.map { it.order.orderId }.distinct().size != result.orders.size) unavailable()
        return OrderWorkflowCut(reply, result.orders.map { it.order }, result.nextAfter,
            result.orders.associate { it.order.orderId to it.stockProfile })
    }
    val reply = access.read(LocalOrderReadCodec.request(query))
    val result = LocalOrderReadCodec.decode(reply.payload)
    return OrderWorkflowCut(reply, result.orders, result.nextAfter, emptyMap())
}

private fun orderFormVersion(access: GatewayAccess, profile: LocalOrderStockProfileV2?): Int? = when (profile) {
    LocalOrderStockProfileV2.NORMALIZED -> 2.takeIf { access.installed.has(LocalCollectionStockCodecV2.COMMAND) }
    LocalOrderStockProfileV2.LEGACY_BOUNDED -> 1.takeIf { access.installed.has(LocalSalesStoreProfile.ORDER_COMMAND) }
    null -> null
}

internal fun requireStockProfile(profile: LocalOrderStockProfileV2?, formVersion: Int) {
    if (profile == null) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    if (formVersion != when (profile) {
        LocalOrderStockProfileV2.LEGACY_BOUNDED -> 1
        LocalOrderStockProfileV2.NORMALIZED -> 2
    }) invalid()
}

internal suspend fun orderWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    if (access.installed.isPaymentCorrectionsOnly || query.reference?.kind in paymentCorrectionRecordKinds || isPaymentCorrectionSearch(query.search)) return paymentCorrectionWorkflow(access, query)
    if (access.installed.isCustomerReceiptsOnly || query.reference?.kind in customerReceiptRecordKinds || isCustomerReceiptSearch(query.search)) return customerReceiptWorkflow(access, query)
    if (query.search.startsWith("manager:")) return managerOrderWorkflow(access, query)
    if (query.search.startsWith("reassign:")) return managerReassignmentWorkflow(access, query)
    val financialReference = query.reference?.takeIf { it.kind in setOf(LocalRecordKind.COLLECTION, LocalRecordKind.SALE) } ?: when {
        query.search.startsWith("collection:") -> LocalRecordReference(LocalRecordKind.COLLECTION, searchedId(query.search.removePrefix("collection:")))
        query.search.startsWith("sale:") -> LocalRecordReference(LocalRecordKind.SALE, searchedId(query.search.removePrefix("sale:")))
        else -> null
    }
    if (financialReference != null) return collectionWorkflow(access, query.copy(reference = financialReference))
    val incoming = query.search.startsWith("handoff:")
    val searchedOrder = when {
        incoming -> searchedId(query.search.removePrefix("handoff:"))
        query.search.startsWith("order:") -> searchedId(query.search.removePrefix("order:"))
        query.workflow == LocalWorkflow.ORDERS && query.search.length == 36 -> runCatching { DomainId(query.search) }.getOrNull()
        else -> null
    }
    val orderId = query.reference?.takeIf { it.kind == LocalRecordKind.ORDER }?.id ?: searchedOrder
    query.reference?.let { if (it.kind !in setOf(LocalRecordKind.ORDER, LocalRecordKind.SKU, LocalRecordKind.COLLECTION)) invalid() }
    if (orderId != null) {
        val result = orderRead(access, if (incoming) LocalOrderQueryV1.Handoff(orderId) else LocalOrderQueryV1.Order(orderId))
        val read = result.reply
        val order = result.orders.singleOrNull() ?: return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
            text("No order is available to this actor for that request.", "لا يوجد طلب متاح لهذا المستخدم وفق هذا الطلب."))
        if (order.orderId != orderId || !incoming && order.ownerId != access.view.actorId || incoming && order.handoffRecipientId != access.view.actorId) unavailable()
        val forms = mutableListOf<LocalActionForm>()
        val base = listOf(idField(LocalField.ORDER_ID, order.orderId), revisionField(LocalField.EXPECTED_REVISION, order.revision))
        val profile = result.stockProfiles[orderId]
        val version = orderFormVersion(access, profile)
        if (version != null) {
            if (incoming && query.workflow == LocalWorkflow.ORDERS && order.handoffId != null && access.allow(LocalAction.ORDER_ACCEPT_HANDOFF, orderId))
                forms += form(LocalAction.ORDER_ACCEPT_HANDOFF, base + listOf(idField(LocalField.HANDOFF_ID, order.handoffId), newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT)), version = version)
            else if (!incoming && order.collectionId == null && order.saleId == null) {
                if (order.handoffId == null && access.allow(LocalAction.ORDER_REPLACE, orderId)) orderSelectionForm(access, version, order)?.let(forms::add)
                if (query.workflow == LocalWorkflow.ORDERS) {
                    if (order.handoffId == null && access.allow(LocalAction.ORDER_OFFER_HANDOFF, orderId)) forms += form(LocalAction.ORDER_OFFER_HANDOFF,
                        base + listOf(newIdField(LocalField.HANDOFF_ID), idField(LocalField.RECIPIENT_ID), newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT)),
                        text("An offer only: the named employee must separately authenticate and accept. Ownership is not silently reassigned.",
                            "عرض فقط: يجب على الموظف المقصود تسجيل الدخول والقبول بشكل منفصل. لا تُنقل الملكية ضمنيًا."), version = version)
                    if (order.handoffId != null && access.allow(LocalAction.ORDER_CANCEL_HANDOFF, orderId)) forms += form(LocalAction.ORDER_CANCEL_HANDOFF,
                        base + idField(LocalField.HANDOFF_ID, order.handoffId), version = version)
                }
            }
        }
        if (!incoming) collectionBeginForm(access, order, profile)?.let(forms::add)
        val offset = offsetCursor(query.cursor, "order-lines", order.selection.lines.size)
        val lines = order.selection.lines.drop(offset).take(20)
        val records = mutableListOf(orderRecord(order))
        records += lines.map { line ->
            val price = order.lines.singleOrNull { it.portionId == line.portionId }
            LocalRecord(LocalRecordReference(LocalRecordKind.ORDER_LINE, line.portionId), text("Order portion", "جزء الطلب"), values = buildList {
                add(recordValue(fieldLabel(LocalField.SKU_ID), literal(line.itemId.value)))
                add(recordValue(fieldLabel(LocalField.LINE_ID), literal(line.lineId.value)))
                add(recordValue(fieldLabel(LocalField.QUANTITY), quantityText(line.quantity)))
                price?.let { add(recordValue(text("Recorded payable", "المستحق المسجل"), moneyText(it.payable))) }
                line.variantId?.let { add(recordValue(fieldLabel(LocalField.VARIANT_ID), literal(it.value))) }
                if (line.modifierIds.isNotEmpty()) add(recordValue(fieldLabel(LocalField.MODIFIER_IDS), literal(line.modifierIds.joinToString(", ") { it.value })))
            }, selectable = false)
        }
        order.collectionId?.let { records += LocalRecord(LocalRecordReference(LocalRecordKind.COLLECTION, it), text("Prepared collection", "التحصيل المُعد")) }
        order.saleId?.let { records += LocalRecord(LocalRecordReference(LocalRecordKind.SALE, it), text("Recorded paid sale", "البيع المدفوع المسجل")) }
        return LocalWorkflowPage(query.workflow, records, forms, text(
            "Positive-payment initiation is unavailable until protected physical-outcome recording is qualified. An unlocked order or prepared collection is NOT a completed sale. ${readCut(read).english}",
            "بدء الدفع غير متاح حتى تأهيل التسجيل المحمي لنتيجة الحركة الفعلية. الطلب غير المقفل أو التحصيل المُعد ليس بيعًا مكتملًا. ${readCut(read).arabic}"),
            if (offset + lines.size < order.selection.lines.size) "order-lines:${offset + lines.size}" else null)
    }

    if (query.workflow == LocalWorkflow.ORDERS) {
        val result = orderRead(access, LocalOrderQueryV1.OwnOrders(parseCursor(query.cursor, "orders")))
        val read = result.reply
        if (result.orders.any { it.ownerId != access.view.actorId }) unavailable()
        return LocalWorkflowPage(query.workflow, result.orders.filter { query.search.isBlank() || it.orderId.value.contains(query.search) }.map(::orderRecord), emptyList(),
            text("Your unsettled orders. For an explicit incoming handoff use handoff:ORDER_ID; for a permitted read-only manager review use manager:ORDER_ID. This page is not a historical sales report. ${readCut(read).english}",
                "طلباتك غير المسواة. للتسليم الموجه إليك استخدم handoff:ORDER_ID؛ ولمراجعة مدير مصرح بها للقراءة فقط استخدم manager:ORDER_ID. هذه الصفحة ليست تقرير مبيعات تاريخيًا. ${readCut(read).arabic}"), cursor("orders", result.nextAfter))
    }

    val reference = query.reference
    val barcode = query.search.takeIf { it.isNotEmpty() }
    val cut = configurationRead(access, LocalConfigurationQueryV1(LocalConfigurationProfile.SKU, id = reference?.id,
        after = if (reference == null && barcode == null) parseCursor(query.cursor, "sell-skus") else null,
        limit = 25, barcode = if (reference == null) barcode else null))
    val records = cut.page.items.map { row ->
        val detail = (cut.page.detail as? LocalConfigurationDetailV1.Sku)?.takeIf { it.skuId == row.id }
        LocalRecord(LocalRecordReference(LocalRecordKind.SKU, row.id, row.revision), names(row.names, row.id),
            row.code?.let(::literal), buildList {
                detail?.let {
                    add(recordValue(fieldLabel(LocalField.UNIT), literal(it.baseUnitId.value)))
                    add(recordValue(text("Quantity precision", "دقة الكمية"), literal(it.quantityScale.toString())))
                    add(recordValue(text("Fulfillment", "التنفيذ"), literal(it.fulfillment.name)))
                }
            })
    }
    val version = when {
        access.installed.has(LocalCollectionStockCodecV2.COMMAND) -> 2
        access.installed.has(LocalSalesStoreProfile.ORDER_COMMAND) -> 1
        else -> null
    }
    val forms = if (version != null && access.allow(LocalAction.ORDER_CREATE))
        listOfNotNull(orderSelectionForm(access, version, selectedSku = reference?.id, suppliedCut = cut)) else emptyList()
    return LocalWorkflowPage(query.workflow, records, forms,
        text("Scan an installed SKU barcode, or use order:ORDER_ID to reopen an owned order. This editor currently submits base-unit retail selections; it does not claim kitchen, recipe or variant authoring. ${readCut(cut.reply).english}",
            "امسح باركود صنف مثبت، أو استخدم order:ORDER_ID لفتح طلب تملكه. يرسل هذا المحرر حاليًا أصناف التجزئة بالوحدة الأساسية؛ ولا يدّعي تحرير المطبخ أو الوصفات أو المتغيرات. ${readCut(cut.reply).arabic}"),
        cursor("sell-skus", cut.page.nextAfter))
}

private suspend fun orderSelectionForm(access: GatewayAccess, version: Int, existing: LocalOrderViewV1? = null,
                                       selectedSku: DomainId? = null, suppliedCut: ConfigurationCut? = null): LocalActionForm? {
    // Never erase a valid variant/modifier/portion split merely because this bounded editor does
    // not yet represent it. Such orders remain read-only until the actual editor is installed.
    if (existing != null && !editableSelection(existing)) return null
    val cut = suppliedCut ?: configurationRead(access, LocalConfigurationQueryV1(LocalConfigurationProfile.SKU, limit = 1))
    val revision = cut.page.catalogRevision ?: return null
    // Retain only exact initial labels. The line picker reads all authorized pages itself;
    // the editor's catalog revision remains pinned, never silently updated while selecting.
    val choices = cut.page.items.filter { it.active == true && it.id == selectedSku }.map {
        LocalReferenceChoice(LocalRecordReference(LocalRecordKind.SKU, it.id, it.revision), names(it.names, it.id))
    }.toMutableList()
    val initial = mutableListOf<LocalInputLine>()
    if (existing != null) for (line in existing.selection.lines) {
        val detail = skuDetail(access, line.itemId).first
        if (!detail.active || !detail.canSell || detail.fulfillment != LocalFulfillmentV1.RETAIL || detail.baseUnitId != line.quantity.unitId || detail.quantityScale != line.quantity.scale) return null
        val ref = LocalRecordReference(LocalRecordKind.SKU, detail.skuId, detail.skuRevision)
        choices.removeAll { it.reference.id == detail.skuId }
        choices += LocalReferenceChoice(ref, names(detail.names, detail.skuId))
        initial += LocalInputLine(line.lineId, listOf(LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Reference(ref)),
            LocalScalarInput(LocalField.PORTION_ID, LocalScalarValue.Identifier(line.portionId)),
            LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal(fixedDecimal(line.quantity.scaled, line.quantity.scale)))))
    }
    val selected = selectedSku?.let { id -> choices.singleOrNull { it.reference.id == id } }
    val fields = buildList<LocalFormField> {
        add(idField(LocalField.ORDER_ID, existing?.orderId ?: DomainId.random()))
        existing?.let { add(revisionField(LocalField.EXPECTED_REVISION, it.revision)) }
        add(revisionField(LocalField.EXPECTED_CATALOG_REVISION, revision))
        add(choiceField(LocalField.ACTIVITY, activityChoices(), existing?.selection?.activity?.name))
        add(choiceField(LocalField.CHANNEL, channelChoices(), existing?.selection?.channel?.name))
        add(idField(LocalField.SERVICE_CONTEXT_ID, existing?.selection?.serviceContextId, required = false, readOnly = false))
        add(LocalFormField.Lines(LocalField.LINES, fieldLabel(LocalField.LINES), listOf(
            pagedReferenceField(LocalField.SKU_ID, LocalRecordKind.SKU, initial = selected).copy(references = choices.distinctBy { it.reference.id }),
            idField(LocalField.PORTION_ID, required = false, readOnly = true),
            scalar(LocalField.QUANTITY, LocalInputKind.DECIMAL, LocalScalarValue.Decimal("1"), maximum = 32,
                help = text("Exact current SKU base-unit quantity; unit and precision are checked against its actual definition.",
                    "كمية دقيقة بالوحدة الأساسية للصنف؛ تُراجع الوحدة والدقة وفق تعريفه الفعلي."))), maximumLines = 16, initialLines = initial))
    }
    return form(if (existing == null) LocalAction.ORDER_CREATE else LocalAction.ORDER_REPLACE, fields,
        text("Browse SKU pages or look up an exact ID. No client price or tax is trusted. The master validates the pinned catalog revision and computes the quote; changing pages does not update that revision. Saving this form does not collect money.",
            "تصفح صفحات الأصناف أو ابحث بالمعرف الدقيق. لا يعتمد الجهاز الرئيسي أسعارًا أو ضرائب يحددها العميل. يتحقق من مراجعة الكتالوج المثبتة ويحسب عرض السعر؛ تغيير الصفحة لا يحدّث المراجعة. حفظ هذا النموذج لا يحصّل مالًا."), version = version)
}

private fun editableSelection(order: LocalOrderViewV1): Boolean = order.selection.lines.size in 1..16 &&
    order.selection.lines.none { it.variantId != null || it.modifierIds.isNotEmpty() || it.quantity.scaled <= 0L } &&
    order.selection.lines.map { it.lineId }.distinct().size == order.selection.lines.size

internal suspend fun prepareOrder(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    val input = InputValues(intent.values)
    val id = input.id(LocalField.ORDER_ID)
    access.requireAction(intent.action, id)
    // A form opened before normalized installation may be refreshed, not silently translated.
    // Already-staged legacy requests are recovered before this fresh-intent path is entered.
    if (intent.action == LocalAction.ORDER_CREATE && intent.formVersion == 1 && access.installed.has(LocalCollectionStockCodecV2.COMMAND)) invalid()
    val existing = if (intent.action == LocalAction.ORDER_CREATE) null else {
        val incoming = intent.action == LocalAction.ORDER_ACCEPT_HANDOFF
        val cut = orderRead(access, if (incoming) LocalOrderQueryV1.Handoff(id) else LocalOrderQueryV1.Order(id))
        val order = cut.orders.singleOrNull() ?: invalid(LocalField.ORDER_ID)
        if (order.orderId != id || (if (incoming) order.handoffRecipientId != access.view.actorId else order.ownerId != access.view.actorId))
            throw GatewayFailure(LocalServiceProblem.DENIED)
        requireStockProfile(cut.stockProfiles[id], intent.formVersion)
        order
    }
    val request = when (intent.action) {
        LocalAction.ORDER_CREATE, LocalAction.ORDER_REPLACE -> {
            if (intent.lineField != LocalField.LINES || intent.lines.size !in 1..16) invalid(LocalField.LINES)
            val expected = if (intent.action == LocalAction.ORDER_REPLACE) input.revision(LocalField.EXPECTED_REVISION) else null
            if (existing != null) {
                if (existing.revision != expected || existing.collectionId != null || existing.saleId != null || existing.handoffId != null)
                    invalid(LocalField.EXPECTED_REVISION)
                // Apply the same preservation guard to direct intents, not just visible forms.
                if (!editableSelection(existing)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED, LocalField.LINES)
                for (line in existing.selection.lines) {
                    val detail = skuDetail(access, line.itemId).first
                    if (!detail.active || !detail.canSell || detail.fulfillment != LocalFulfillmentV1.RETAIL ||
                        detail.baseUnitId != line.quantity.unitId || detail.quantityScale != line.quantity.scale)
                        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED, LocalField.QUANTITY)
                }
            }
            val revision = input.revision(LocalField.EXPECTED_CATALOG_REVISION)
            val activity = enumChoice<LocalSalesActivity>(input.choice(LocalField.ACTIVITY), LocalField.ACTIVITY)
            val channel = enumChoice<LocalSalesChannel>(input.choice(LocalField.CHANNEL), LocalField.CHANNEL)
            val context = input.optionalId(LocalField.SERVICE_CONTEXT_ID)
            val lines = intent.lines.map { row ->
                val line = InputValues(row.values)
                val sku = line.reference(LocalField.SKU_ID, LocalRecordKind.SKU)
                val detail = skuDetail(access, sku.id).first
                if (detail.skuRevision != sku.revision || !detail.active || !detail.canSell || detail.fulfillment != LocalFulfillmentV1.RETAIL) invalid(LocalField.SKU_ID)
                LocalOrderLineV1(row.lineId, line.optionalId(LocalField.PORTION_ID) ?: row.lineId, sku.id,
                    Quantity.parse(detail.baseUnitId, line.decimal(LocalField.QUANTITY), detail.quantityScale)).also { line.finish() }
            }
            val selection = LocalOrderSelectionV1(activity, channel, Currency.EGP, revision, lines, context)
            if (expected == null) LocalOrderRequestV1.Create(id, selection) else LocalOrderRequestV1.ReplaceUnlocked(id, expected, selection)
        }
        LocalAction.ORDER_OFFER_HANDOFF -> LocalOrderRequestV1.OfferHandoff(id, input.revision(LocalField.EXPECTED_REVISION),
            input.id(LocalField.HANDOFF_ID), input.id(LocalField.RECIPIENT_ID), input.cashEvidence())
        LocalAction.ORDER_CANCEL_HANDOFF -> LocalOrderRequestV1.CancelHandoff(id, input.revision(LocalField.EXPECTED_REVISION), input.id(LocalField.HANDOFF_ID))
        LocalAction.ORDER_ACCEPT_HANDOFF -> LocalOrderRequestV1.AcceptHandoff(id, input.revision(LocalField.EXPECTED_REVISION), input.id(LocalField.HANDOFF_ID), input.cashEvidence())
        else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    if (intent.action !in setOf(LocalAction.ORDER_CREATE, LocalAction.ORDER_REPLACE) && (intent.lines.isNotEmpty() || intent.lineField != null)) invalid(LocalField.LINES)
    input.finish()
    if (intent.formVersion == 2) return LocalCollectionStockCodecV2.command(intent.operationId, when (request) {
        is LocalOrderRequestV1.Create -> LocalCollectionStockRequestV2.CreateOrder(request.orderId, request.selection)
        is LocalOrderRequestV1.ReplaceUnlocked -> LocalCollectionStockRequestV2.ReplaceUnlockedOrder(request.orderId, request.expectedRevision, request.selection)
        is LocalOrderRequestV1.OfferHandoff -> LocalCollectionStockRequestV2.OfferHandoff(request.orderId, request.expectedRevision, request.handoffId, request.recipientId, request.evidence)
        is LocalOrderRequestV1.CancelHandoff -> LocalCollectionStockRequestV2.CancelHandoff(request.orderId, request.expectedRevision, request.handoffId)
        is LocalOrderRequestV1.AcceptHandoff -> LocalCollectionStockRequestV2.AcceptHandoff(request.orderId, request.expectedRevision, request.handoffId, request.evidence)
    })
    return MutationRequest(intent.operationId, LocalSalesStoreProfile.ORDER_COMMAND, LocalOrderCommandCodec.encode(request))
}

internal fun orderRecord(order: LocalOrderViewV1): LocalRecord = LocalRecord(LocalRecordReference(LocalRecordKind.ORDER, order.orderId, order.revision),
    text("Local order", "طلب محلي"), when {
        order.saleId != null -> text("Paid sale recorded", "سجل بيع مدفوع")
        order.collectionId != null -> text("Collection locked", "التحصيل مقفل")
        order.handoffId != null -> text("Handoff offered", "تم عرض التسليم")
        else -> text("Unlocked", "غير مقفل")
    }, buildList {
        add(recordValue(fieldLabel(LocalField.ACTIVITY), literal(order.selection.activity.name)))
        add(recordValue(fieldLabel(LocalField.CHANNEL), literal(order.selection.channel.name)))
        add(recordValue(text("Before discount", "قبل الخصم"), moneyText(order.beforeDiscount)))
        add(recordValue(text("Advertised discount", "الخصم المعلن"), moneyText(order.discount)))
        add(recordValue(text("Exact payable", "المستحق الدقيق"), moneyText(order.payable)))
        add(recordValue(text("Portions", "الأجزاء"), literal(order.selection.lines.size.toString())))
        add(recordValue(text("Quote revision", "مراجعة عرض السعر"), literal(order.quoteRevision.value.toString())))
        order.collectionId?.let { add(recordValue(fieldLabel(LocalField.COLLECTION_ID), literal(it.value))) }
        order.saleId?.let { add(recordValue(fieldLabel(LocalField.SALE_ID), literal(it.value))) }
        order.handoffId?.let { add(recordValue(fieldLabel(LocalField.HANDOFF_ID), literal(it.value))) }
    })

internal fun searchedId(value: String): DomainId = try { DomainId(value) } catch (_: IllegalArgumentException) { invalid() }
internal fun offsetCursor(value: String?, family: String, maximum: Int): Int {
    if (value == null) return 0
    if (!value.startsWith("$family:")) invalid()
    return value.removePrefix("$family:").toIntOrNull()?.takeIf { it in 0..maximum } ?: invalid()
}
