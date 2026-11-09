package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.*

internal data class PricingCut(val page: LocalPricingPageV1, val reply: ReadReply.Data)
internal val pricingRecordKinds: Set<LocalRecordKind> = setOf(LocalRecordKind.PRICE_RULE,
    LocalRecordKind.PRICE_HISTORY, LocalRecordKind.PRICE_POLICY_PUBLICATION, LocalRecordKind.PRICE_HISTORY_INDEX)

internal suspend fun pricingRead(access: GatewayAccess, query: LocalPricingQueryV1): PricingCut {
    val reply = access.read(ReadRequest(LocalPricingProfile.QUERY, LocalPricingCodec.query(query)))
    val page = LocalPricingCodec.decodePage(reply.payload)
    when (query) {
        is LocalPricingQueryV1.Context -> {
            if (page.preview != null || page.policy != null || page.companyPublication != null ||
                page.history.isNotEmpty() || page.nextEventId != null || page.indexStatus != null || page.indexStorageRevision != null) unavailable()
            page.context?.let {
                if (it.skuId != query.skuId || it.ruleId != query.ruleId || it.companyPrice?.ruleId?.let { id -> id != query.ruleId } == true) unavailable()
                it.policy?.let { policy ->
                    validatePricePolicyView(policy)
                    if (policy.skuId != query.skuId || policy.ruleId != query.ruleId) unavailable()
                }
            }
        }
        is LocalPricingQueryV1.Preview -> {
            if (page.context != null || page.policy != null || page.companyPublication != null ||
                page.history.isNotEmpty() || page.nextEventId != null || page.indexStatus != null || page.indexStorageRevision != null) unavailable()
            val preview = page.preview ?: unavailable()
            if (preview.skuId != query.skuId || preview.channel != query.channel || preview.quantity != query.quantity ||
                preview.selected?.skuId?.let { it != query.skuId } == true) unavailable()
            preview.selected?.let { selected ->
                if (selected.companyPrice?.ruleId?.let { it != selected.ruleId } == true) unavailable()
                selected.policy?.let { policy ->
                    validatePricePolicyView(policy)
                    if (policy.skuId != query.skuId || policy.ruleId != selected.ruleId) unavailable()
                }
            }
        }
        is LocalPricingQueryV1.History -> {
            if (page.context != null || page.preview != null || page.policy != null || page.companyPublication != null ||
                page.indexStatus != null || page.indexStorageRevision != null || page.history.size > query.limit ||
                page.history.any { it.skuId != query.skuId } ||
                page.history.map { it.eventId }.distinct().size != page.history.size ||
                page.nextEventId != null && page.nextEventId != page.history.lastOrNull()?.eventId) unavailable()
        }
        is LocalPricingQueryV1.PolicyPublication -> {
            if (page.context != null || page.preview != null || page.companyPublication != null ||
                page.history.isNotEmpty() || page.nextEventId != null || page.indexStatus != null || page.indexStorageRevision != null) unavailable()
            page.policy?.let {
                validatePricePolicyView(it)
                if (it.publicationId != query.publicationId) unavailable()
            }
        }
        is LocalPricingQueryV1.IndexStatus -> {
            if (page.context != null || page.preview != null || page.policy != null || page.companyPublication != null ||
                page.history.isNotEmpty() || page.nextEventId != null || page.indexStatus !in setOf("UNINDEXED", "INDEXING", "COMPLETE") ||
                (page.indexStatus == "UNINDEXED") != (page.indexStorageRevision == null) ||
                page.indexStorageRevision?.value?.let { it < 1 } == true) unavailable()
        }
        is LocalPricingQueryV1.CompanyPublication -> {
            if (page.context != null || page.preview != null || page.policy != null || page.history.isNotEmpty() ||
                page.nextEventId != null || page.indexStatus != null || page.indexStorageRevision != null) unavailable()
            page.companyPublication?.let {
                if (it.publicationId != query.publicationId || it.prices.size > 64 ||
                    it.prices.map { price -> price.ruleId }.distinct().size != it.prices.size) unavailable()
            }
        }
    }
    return PricingCut(page, reply)
}

private fun validatePricePolicyView(policy: LocalPricePolicyViewV1) {
    if (policy.authorizationReferenceQuantity.scaled <= 0 ||
        policy.effectiveUntil?.let { it <= policy.effectiveFrom } == true ||
        policy.activeStorageRevision?.value?.let { it < 1 } == true ||
        policy.localStatus !in setOf("ACTIVE", "EXPIRED", "RECEIVED", "RECEIVED_EXPIRED", "RECEIVED_SUPERSEDED", "SCHEDULED_RECEIVED")) unavailable()
}

internal fun priceNavigation(access: GatewayAccess, sku: LocalConfigurationDetailV1.Sku): List<LocalRecord> {
    if (!access.installed.has(LocalPricingProfile.QUERY)) return emptyList()
    if (sku.prices.size > 64 || sku.prices.map { it.ruleId }.distinct().size != sku.prices.size) unavailable()
    return sku.prices.map { price ->
        LocalRecord(LocalRecordReference(LocalRecordKind.PRICE_RULE, price.ruleId, parentId = sku.skuId),
            text("Inspect price rule", "عرض قاعدة السعر"), values = listOf(
                recordValue(fieldLabel(LocalField.PRICE_RULE_ID), literal(price.ruleId.value)),
                recordValue(fieldLabel(LocalField.CHANNEL), price.channel?.let(::literal) ?: text("All channels", "كل القنوات")),
                recordValue(fieldLabel(LocalField.EXPECTED_COMPANY_PRICE_REVISION), literal(price.revision.value.toString()))))
    } + if (access.hasHint(Permission(SecurityAction.CATALOG_AUDIT_READ, PermissionVerb.PERFORM))) listOf(
        LocalRecord(LocalRecordReference(LocalRecordKind.PRICE_HISTORY, sku.skuId),
            text("Protected price history", "سجل الأسعار المحمي"))) else emptyList()
}

internal fun priceIndexNavigation(access: GatewayAccess): List<LocalRecord> =
    if (access.installed.has(LocalPricingProfile.QUERY)) listOf(LocalRecord(
        LocalRecordReference(LocalRecordKind.PRICE_HISTORY_INDEX, access.view.scope.branchId),
        text("Retained company price-history index", "فهرس سجل أسعار الشركة المحفوظ"),
        text("Read status / one explicit bounded step", "قراءة الحالة أو خطوة محدودة صريحة واحدة"))) else emptyList()

internal suspend fun pricingWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    if (query.workflow != LocalWorkflow.CATALOG) invalid()
    val reference = query.reference ?: invalid()
    val previewInput = query.pricePreview
    if (previewInput != null) {
        if (reference.kind != LocalRecordKind.SKU || reference.id != previewInput.skuId || query.cursor != null) invalid()
        val sku = skuDetail(access, previewInput.skuId).first
        val quantity = Quantity.parse(sku.baseUnitId, previewInput.quantity.canonical, sku.quantityScale)
        val cut = pricingRead(access, LocalPricingQueryV1.Preview(sku.skuId, previewInput.channel, quantity))
        val preview = cut.page.preview ?: unavailable()
        val record = LocalRecord(LocalRecordReference(LocalRecordKind.SKU, sku.skuId), text("Master price preview", "معاينة سعر الجهاز الرئيسي"),
            literal(preview.code), listOf(
                recordValue(fieldLabel(LocalField.CHANNEL), literal(preview.channel)),
                recordValue(fieldLabel(LocalField.QUANTITY), quantityText(preview.quantity)),
                recordValue(text("Selected effective unit price", "سعر الوحدة الفعلي المختار"),
                    preview.effectiveUnitPrice?.let(::priceText) ?: text("Unavailable; not zero", "غير متاح؛ ليس صفرًا"))), selectable = false)
        val selected = preview.selected?.let { priceContextRecord(it) }
        return LocalWorkflowPage(query.workflow, listOfNotNull(record, selected), emptyList(),
            text("Read-only server selection; no quote, stock reservation or sale is locked by this preview. ${readCut(cut.reply).english}",
                "اختيار للقراءة فقط من الجهاز الرئيسي؛ لا تثبت المعاينة عرض سعر أو حجز مخزون أو بيعًا. ${readCut(cut.reply).arabic}"),
            priceControls = LocalPriceReadControls(sku.skuId, sku.baseUnitId, sku.quantityScale))
    }
    return when (reference.kind) {
        LocalRecordKind.PRICE_RULE -> {
            if (query.cursor != null) invalid()
            val skuId = reference.parentId ?: invalid(LocalField.SKU_ID)
            val cut = pricingRead(access, LocalPricingQueryV1.Context(skuId, reference.id))
            val context = cut.page.context ?: return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
                text("No current price context is available for that exact SKU/rule.", "لا يوجد سياق سعر حالي متاح لهذا الصنف وهذه القاعدة."))
            val forms = priceForms(access, context)
            LocalWorkflowPage(query.workflow, listOf(priceContextRecord(context)) +
                context.policy?.let { listOf(pricePolicyLink(it.publicationId)) }.orEmpty() + priceBackLinks(access, skuId), forms,
                text("Direct branch-price authority only. A policy/preview is not permission; the master checks current limits, history and exact revisions. ${readCut(cut.reply).english}",
                    "صلاحية مباشرة لسعر الفرع فقط. السياسة أو المعاينة ليست إذنًا؛ يتحقق الجهاز الرئيسي من الحدود والسجل والمراجعات الدقيقة الحالية. ${readCut(cut.reply).arabic}"))
        }
        LocalRecordKind.PRICE_HISTORY -> {
            val cut = pricingRead(access, LocalPricingQueryV1.History(reference.id, parseCursor(query.cursor, "price-history"), 20))
            LocalWorkflowPage(query.workflow, cut.page.history.map { event ->
                LocalRecord(LocalRecordReference(LocalRecordKind.PRICE_EVENT, event.eventId, parentId = event.skuId),
                    text("Recorded price event", "واقعة سعر مسجلة"), literal(event.kind), buildList {
                        add(recordValue(fieldLabel(LocalField.SKU_ID), literal(event.skuId.value)))
                        event.ruleId?.let { add(recordValue(fieldLabel(LocalField.PRICE_RULE_ID), literal(it.value))) }
                        event.companyPublicationId?.let { add(recordValue(text("Company publication", "منشور الشركة"), literal(it.value))) }
                        event.policyPublicationId?.let { add(recordValue(text("Policy publication", "منشور السياسة"), literal(it.value))) }
                        event.branchUnitPrice?.let { add(recordValue(fieldLabel(LocalField.UNIT_PRICE), priceText(it))) }
                        add(recordValue(text("Recorded actor", "الموظف المسجل"), literal(event.changedBy.value)))
                        add(recordValue(text("Recorded at", "وقت التسجيل"), literal(event.recordedAt.canonicalString())))
                        event.reasonCode?.let { add(recordValue(fieldLabel(LocalField.REASON), literal(it))) }
                    }, selectable = false)
            } + cut.page.history.mapNotNull { it.policyPublicationId }.distinct().map(::pricePolicyLink) +
                LocalRecord(LocalRecordReference(LocalRecordKind.SKU, reference.id), text("Back to SKU", "العودة إلى الصنف")) + priceIndexNavigation(access),
                emptyList(), readCut(cut.reply), cursor("price-history", cut.page.nextEventId))
        }
        LocalRecordKind.PRICE_POLICY_PUBLICATION -> {
            if (reference.revision != null || reference.parentId != null || query.cursor != null) invalid()
            val cut = pricingRead(access, LocalPricingQueryV1.PolicyPublication(reference.id))
            val policy = cut.page.policy ?: return LocalWorkflowPage(query.workflow, priceIndexNavigation(access), emptyList(),
                text("No received policy is available for this exact publication. Company signing alone does not receive or activate it.",
                    "لا توجد سياسة مستلمة متاحة لهذا المنشور الدقيق. توقيع الشركة وحده لا يستلمه أو يفعله."))
            val context = pricingRead(access, LocalPricingQueryV1.Context(policy.skuId, policy.ruleId)).page.context
            val index = pricingRead(access, LocalPricingQueryV1.IndexStatus).page
            val activation = pricePolicyActivationForm(access, policy, context, cut.reply.observedAt, index)
            LocalWorkflowPage(query.workflow, listOf(pricePolicyRecord(policy)) + priceBackLinks(access, policy.skuId),
                listOfNotNull(activation), text(
                    "This publication was received. Activation is a separate explicit current employee operation with the displayed original expected revision. Signing and receipt do not change an effective price. ${readCut(cut.reply).english}",
                    "استُلم هذا المنشور. التفعيل عملية منفصلة صريحة للموظف الحالي باستخدام المراجعة المتوقعة الأصلية المعروضة. لا يغير التوقيع أو الاستلام السعر الفعلي. ${readCut(cut.reply).arabic}"))
        }
        LocalRecordKind.PRICE_HISTORY_INDEX -> {
            if (reference.id != access.view.scope.branchId || reference.revision != null || reference.parentId != null || query.cursor != null) invalid()
            val cut = pricingRead(access, LocalPricingQueryV1.IndexStatus)
            LocalWorkflowPage(query.workflow, listOf(LocalRecord(reference,
                text("Retained company price-history index", "فهرس سجل أسعار الشركة المحفوظ"),
                priceIndexStatusText(checkNotNull(cut.page.indexStatus)), listOf(
                    recordValue(fieldLabel(LocalField.EXPECTED_PRICE_HISTORY_INDEX_STORAGE_REVISION),
                        optionalStorageRevisionText(cut.page.indexStorageRevision))))), listOfNotNull(priceIndexForm(access, cut.page)),
                text("Each saved request indexes at most one retained company publication. Review and submit one original step, acknowledge its result, then refresh before choosing another step. No price, stock or payment is changed. ${readCut(cut.reply).english}",
                    "يفهرس كل طلب محفوظ منشور شركة محفوظًا واحدًا على الأكثر. راجع خطوة أصلية واحدة وأرسلها وأكد نتيجتها، ثم حدّث قبل اختيار خطوة أخرى. لا يتغير سعر أو مخزون أو دفع. ${readCut(cut.reply).arabic}"))
        }
        else -> invalid()
    }
}

private fun priceBackLinks(access: GatewayAccess, skuId: DomainId): List<LocalRecord> = listOf(
    LocalRecord(LocalRecordReference(LocalRecordKind.SKU, skuId), text("Back to SKU / preview", "العودة إلى الصنف / المعاينة"))) +
    (if (access.hasHint(Permission(SecurityAction.CATALOG_AUDIT_READ, PermissionVerb.PERFORM))) listOf(
        LocalRecord(LocalRecordReference(LocalRecordKind.PRICE_HISTORY, skuId), text("Protected price history", "سجل الأسعار المحمي"))) else emptyList()) +
        priceIndexNavigation(access)

private fun pricePolicyLink(publicationId: DomainId): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.PRICE_POLICY_PUBLICATION, publicationId),
    text("Inspect received price-policy publication", "عرض منشور سياسة السعر المستلم"))

private fun pricePolicyRecord(policy: LocalPricePolicyViewV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.PRICE_POLICY_PUBLICATION, policy.publicationId),
    text("Exact received price-policy publication", "منشور سياسة السعر المستلم الدقيق"), pricePolicyStatusText(policy.localStatus), listOf(
        recordValue(text("Publication ID", "معرف المنشور"), literal(policy.publicationId.value)),
        recordValue(fieldLabel(LocalField.POLICY_ID), literal(policy.entryId.value)),
        recordValue(fieldLabel(LocalField.EXPECTED_PRICE_POLICY_REVISION), literal(policy.entryRevision.value.toString())),
        recordValue(fieldLabel(LocalField.SKU_ID), literal(policy.skuId.value)),
        recordValue(fieldLabel(LocalField.PRICE_RULE_ID), literal(policy.ruleId.value)),
        recordValue(text("Branch overrides enabled by policy", "السياسة تسمح بسعر الفرع"), yesNo(policy.branchOverridesEnabled)),
        recordValue(text("Authorization reference quantity", "الكمية المرجعية للصلاحية"), quantityText(policy.authorizationReferenceQuantity)),
        recordValue(text("Policy effective from", "السياسة سارية من"), literal(policy.effectiveFrom.canonicalString())),
        recordValue(text("Policy effective until", "السياسة سارية حتى"), policy.effectiveUntil?.let { literal(it.canonicalString()) }
            ?: text("No expiry specified", "لم يُحدد انتهاء")),
        recordValue(fieldLabel(LocalField.EXPECTED_PRICE_POLICY_STORAGE_REVISION), optionalStorageRevisionText(policy.activeStorageRevision))))

private fun pricePolicyActivationForm(access: GatewayAccess, policy: LocalPricePolicyViewV1,
    context: LocalPriceContextViewV1?, observedAt: UtcInstant, index: LocalPricingPageV1): LocalActionForm? {
    if (!access.installed.has(LocalPricingProfile.COMMAND) || context?.companyPrice == null ||
        context.policy?.activeStorageRevision != policy.activeStorageRevision || index.indexStatus != "COMPLETE" ||
        observedAt < policy.effectiveFrom || policy.effectiveUntil?.let { observedAt >= it } == true ||
        policy.localStatus != "RECEIVED" || !access.allow(LocalAction.PRICE_POLICY_ACTIVATE, context.contextId)) return null
    return form(LocalAction.PRICE_POLICY_ACTIVATE, listOf(idField(LocalField.SOURCE_ID, policy.publicationId),
        idField(LocalField.POLICY_ID, policy.entryId), idField(LocalField.SKU_ID, policy.skuId), idField(LocalField.PRICE_RULE_ID, policy.ruleId),
        optionalStorageRevisionField(LocalField.EXPECTED_PRICE_POLICY_STORAGE_REVISION, policy.activeStorageRevision),
        scalar(LocalField.CONFIRMED, LocalInputKind.CHECKBOX, LocalScalarValue.Flag(false))), text(
        "Activate only this currently received and effective publication. The current Master checks history, exact units, version continuity and the saved expected active-policy storage revision again. This does not authorize an employee price change.",
        "تفعيل هذا المنشور المستلم والساري حاليًا فقط. يتحقق الجهاز الرئيسي الحالي مجددًا من السجل والوحدات الدقيقة واستمرار الإصدار ومراجعة تخزين السياسة النشطة المتوقعة المحفوظة. لا يمنح ذلك الموظف صلاحية تغيير السعر."))
}

private fun priceIndexForm(access: GatewayAccess, page: LocalPricingPageV1): LocalActionForm? =
    if (page.indexStatus == "COMPLETE" || !access.installed.has(LocalPricingProfile.COMMAND) ||
        !access.allow(LocalAction.PRICE_HISTORY_INDEX, access.view.scope.branchId)) null
    else form(LocalAction.PRICE_HISTORY_INDEX, listOf(idField(LocalField.SOURCE_ID, access.view.scope.branchId),
        optionalStorageRevisionField(LocalField.EXPECTED_PRICE_HISTORY_INDEX_STORAGE_REVISION, page.indexStorageRevision),
        scalar(LocalField.CONFIRMED, LocalInputKind.CHECKBOX, LocalScalarValue.Flag(false))), text(
        "Save one bounded indexing step with this exact expected index revision. Completeness is decided by the Master from retained publications, never by a client checkbox. No automatic loop or second original is submitted.",
        "احفظ خطوة فهرسة محدودة واحدة بهذه المراجعة المتوقعة الدقيقة للفهرس. يحدد الجهاز الرئيسي الاكتمال من المنشورات المحفوظة؛ لا يحدده اختيار العميل. لا تُرسل حلقة تلقائية أو عملية أصلية ثانية."))

private fun optionalStorageRevisionField(field: LocalField, revision: Revision?): LocalFormField.Scalar =
    scalar(field, LocalInputKind.INTEGER, revision?.let { LocalScalarValue.Integer(it.value) },
        required = false, readOnly = true, maximum = 19,
        help = text("Blank means this protected read found no current row; it is part of the original request and is never refreshed on retry.",
            "الفراغ يعني أن هذه القراءة المحمية لم تجد سجلًا حاليًا؛ وهو جزء من الطلب الأصلي ولا يُحدّث عند المحاولة."))

private fun optionalStorageRevisionText(revision: Revision?): LocalText = revision?.let { literal(it.value.toString()) }
    ?: text("Absent at this read cut", "غير موجود في مراجعة القراءة هذه")

private fun yesNo(value: Boolean): LocalText = if (value) text("Yes", "نعم") else text("No", "لا")

private fun priceIndexStatusText(status: String): LocalText = when (status) {
    "UNINDEXED" -> text("Unindexed", "غير مفهرس")
    "INDEXING" -> text("Indexing incomplete", "الفهرسة غير مكتملة")
    "COMPLETE" -> text("Index complete", "الفهرس مكتمل")
    else -> unavailable()
}

private fun pricePolicyStatusText(status: String): LocalText = when (status) {
    "ACTIVE" -> text("Active", "نشطة")
    "EXPIRED" -> text("Active publication expired", "انتهت صلاحية المنشور النشط")
    "RECEIVED" -> text("Received; activation pending", "مستلمة؛ بانتظار التفعيل")
    "RECEIVED_EXPIRED" -> text("Received publication expired", "انتهت صلاحية المنشور المستلم")
    "RECEIVED_SUPERSEDED" -> text("Received publication superseded", "حُل منشور أحدث محل المنشور المستلم")
    "SCHEDULED_RECEIVED" -> text("Received; effective date pending", "مستلمة؛ لم يحن وقت سريانها")
    else -> unavailable()
}

private fun priceContextRecord(context: LocalPriceContextViewV1): LocalRecord {
    val company = context.companyPrice
    val policy = context.policy
    val branch = context.branch
    return LocalRecord(LocalRecordReference(LocalRecordKind.PRICE_RULE, context.ruleId, parentId = context.skuId),
        text("Exact price context", "سياق السعر الدقيق"), values = buildList {
            add(recordValue(fieldLabel(LocalField.SKU_ID), literal(context.skuId.value)))
            add(recordValue(fieldLabel(LocalField.PRICE_RULE_ID), literal(context.ruleId.value)))
            add(recordValue(text("Price context ID", "معرف سياق السعر"), literal(context.contextId.value)))
            add(recordValue(text("Company price", "سعر الشركة"), company?.unitPrice?.let(::priceText) ?: text("Unavailable; not zero", "غير متاح؛ ليس صفرًا")))
            company?.let {
                add(recordValue(fieldLabel(LocalField.EXPECTED_COMPANY_PRICE_REVISION), literal(it.revision.value.toString())))
                add(recordValue(text("Minimum quantity", "الحد الأدنى للكمية"), quantityText(it.minimumQuantity)))
                it.maximumQuantityExclusive?.let { maximum -> add(recordValue(text("Quantity upper bound (exclusive)", "حد الكمية الأعلى (غير شامل)"), quantityText(maximum))) }
                add(recordValue(text("Company effective from", "سعر الشركة سارٍ من"), literal(it.effectiveFrom.canonicalString())))
                it.effectiveUntil?.let { until -> add(recordValue(text("Company effective until", "سعر الشركة سارٍ حتى"), literal(until.canonicalString()))) }
            }
            context.highestCompanyPriceRevision?.let { add(recordValue(text("Highest company price revision", "أعلى مراجعة لسعر الشركة"), literal(it.value.toString()))) }
            if (policy == null) add(recordValue(text("Price policy", "سياسة السعر"), text("Unavailable", "غير متاحة")))
            else {
                if (policy.skuId != context.skuId || policy.ruleId != context.ruleId) unavailable()
                add(recordValue(text("Price policy status", "حالة سياسة السعر"), literal(policy.localStatus)))
                add(recordValue(fieldLabel(LocalField.EXPECTED_PRICE_POLICY_REVISION), literal(policy.entryRevision.value.toString())))
                add(recordValue(text("Authorization reference quantity", "الكمية المرجعية للصلاحية"), quantityText(policy.authorizationReferenceQuantity)))
                add(recordValue(text("Branch overrides enabled by policy", "السياسة تسمح بسعر الفرع"), literal(policy.branchOverridesEnabled.toString())))
                add(recordValue(text("Policy effective from", "السياسة سارية من"), literal(policy.effectiveFrom.canonicalString())))
                policy.effectiveUntil?.let { add(recordValue(text("Policy effective until", "السياسة سارية حتى"), literal(it.canonicalString()))) }
            }
            if (branch == null) add(recordValue(text("Branch override", "سعر الفرع"), text("No stored override at this read cut", "لا يوجد سعر فرع مخزن في مراجعة القراءة هذه")))
            else {
                add(recordValue(text("Branch override disposition", "حالة سعر الفرع"), literal(branch.disposition)))
                add(recordValue(fieldLabel(LocalField.EXPECTED_OVERRIDE_STORAGE_REVISION), literal(branch.storageRevision.value.toString())))
                add(recordValue(text("Stored branch price", "سعر الفرع المخزن"), branch.unitPrice?.let(::priceText)
                    ?: text("Reset / company inheritance", "إعادة تعيين / اتباع الشركة")))
                add(recordValue(text("Based on company / policy revisions", "مستند إلى مراجعتي الشركة / السياسة"),
                    literal("${branch.basedOnCompanyPriceRevision.value} / ${branch.basedOnPolicyEntryRevision.value}")))
                branch.effectiveUntil?.let { add(recordValue(text("Branch override expires", "انتهاء سعر الفرع"), literal(it.canonicalString()))) }
            }
        })
}

private fun priceForms(access: GatewayAccess, context: LocalPriceContextViewV1): List<LocalActionForm> {
    if (!access.installed.has(LocalPricingProfile.COMMAND) || !access.allow(LocalAction.PRICE_SET, context.contextId)) return emptyList()
    val company = context.companyPrice ?: return emptyList()
    val policy = context.policy ?: return emptyList()
    val base = listOf(idField(LocalField.SKU_ID, context.skuId), idField(LocalField.PRICE_RULE_ID, context.ruleId),
        revisionField(LocalField.EXPECTED_COMPANY_PRICE_REVISION, company.revision),
        revisionField(LocalField.EXPECTED_PRICE_POLICY_REVISION, policy.entryRevision),
        scalar(LocalField.EXPECTED_OVERRIDE_STORAGE_REVISION, LocalInputKind.INTEGER,
            context.branch?.storageRevision?.let { LocalScalarValue.Integer(it.value) }, required = false, readOnly = true, maximum = 19))
    return buildList {
        if (policy.branchOverridesEnabled) add(form(LocalAction.PRICE_SET, base + listOf(
            scalar(LocalField.UNIT_PRICE, LocalInputKind.DECIMAL, maximum = 40,
                help = text("Exact EGP unit price, up to eight decimal places; never a posted amount or rounded percent.", "سعر وحدة دقيق بالجنيه حتى ثماني منازل عشرية؛ ليس مبلغًا مرحلًا أو نسبة مقربة.")),
            instantField(LocalField.VALID_UNTIL, required = false), stockReasonField())))
        if (context.branch?.unitPrice != null) add(form(LocalAction.PRICE_RESET, base + stockReasonField(),
            text("Explicitly clear the branch override and inherit the company price. This is not a zero-price override and does not reprice protected orders.",
                "إزالة سعر الفرع صراحةً واتباع سعر الشركة. ليس سعرًا صفريًا ولا يعيد تسعير الطلبات المحمية.")))
    }
}

internal suspend fun preparePricing(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.lineField != null || intent.lines.isNotEmpty()) invalid(LocalField.LINES)
    val input = InputValues(intent.values)
    val request = when (intent.action) {
        LocalAction.PRICE_POLICY_ACTIVATE -> {
            val publicationId = input.id(LocalField.SOURCE_ID)
            if (intent.reference != LocalRecordReference(LocalRecordKind.PRICE_POLICY_PUBLICATION, publicationId)) invalid(LocalField.SOURCE_ID)
            val policy = pricingRead(access, LocalPricingQueryV1.PolicyPublication(publicationId)).page.policy ?: invalid(LocalField.SOURCE_ID)
            if (input.id(LocalField.POLICY_ID) != policy.entryId) invalid(LocalField.POLICY_ID)
            if (input.id(LocalField.SKU_ID) != policy.skuId) invalid(LocalField.SKU_ID)
            if (input.id(LocalField.PRICE_RULE_ID) != policy.ruleId) invalid(LocalField.PRICE_RULE_ID)
            val expected = input.optionalRevision(LocalField.EXPECTED_PRICE_POLICY_STORAGE_REVISION)
            if (expected != policy.activeStorageRevision) invalid(LocalField.EXPECTED_PRICE_POLICY_STORAGE_REVISION)
            val context = pricingRead(access, LocalPricingQueryV1.Context(policy.skuId, policy.ruleId)).page.context ?: invalid(LocalField.PRICE_RULE_ID)
            if (context.policy?.activeStorageRevision != expected) invalid(LocalField.EXPECTED_PRICE_POLICY_STORAGE_REVISION)
            access.requireAction(intent.action, context.contextId)
            if (!input.flag(LocalField.CONFIRMED)) invalid(LocalField.CONFIRMED)
            LocalPricingRequestV1.ActivatePolicy(publicationId, expected)
        }
        LocalAction.PRICE_HISTORY_INDEX -> {
            val branchId = input.id(LocalField.SOURCE_ID)
            if (branchId != access.view.scope.branchId ||
                intent.reference != LocalRecordReference(LocalRecordKind.PRICE_HISTORY_INDEX, branchId)) invalid(LocalField.SOURCE_ID)
            access.requireAction(intent.action, branchId)
            val expected = input.optionalRevision(LocalField.EXPECTED_PRICE_HISTORY_INDEX_STORAGE_REVISION)
            val index = pricingRead(access, LocalPricingQueryV1.IndexStatus).page
            if (expected != index.indexStorageRevision) invalid(LocalField.EXPECTED_PRICE_HISTORY_INDEX_STORAGE_REVISION)
            if (index.indexStatus == "COMPLETE" || !input.flag(LocalField.CONFIRMED)) invalid(LocalField.CONFIRMED)
            LocalPricingRequestV1.IndexRetainedCompanyHistory(expected)
        }
        LocalAction.PRICE_SET, LocalAction.PRICE_RESET -> {
            val skuId = input.id(LocalField.SKU_ID)
            val ruleId = input.id(LocalField.PRICE_RULE_ID)
            val context = pricingRead(access, LocalPricingQueryV1.Context(skuId, ruleId)).page.context ?: invalid(LocalField.PRICE_RULE_ID)
            access.requireAction(intent.action, context.contextId)
            val companyRevision = input.revision(LocalField.EXPECTED_COMPANY_PRICE_REVISION)
            val policyRevision = input.revision(LocalField.EXPECTED_PRICE_POLICY_REVISION)
            val overrideRevision = input.optionalRevision(LocalField.EXPECTED_OVERRIDE_STORAGE_REVISION)
            if (context.companyPrice?.revision != companyRevision) invalid(LocalField.EXPECTED_COMPANY_PRICE_REVISION)
            if (context.policy?.entryRevision != policyRevision) invalid(LocalField.EXPECTED_PRICE_POLICY_REVISION)
            if (context.branch?.storageRevision != overrideRevision) invalid(LocalField.EXPECTED_OVERRIDE_STORAGE_REVISION)
            val resetting = intent.action == LocalAction.PRICE_RESET
            val price = if (resetting) null else UnitPrice.parse(Currency.EGP, input.decimal(LocalField.UNIT_PRICE))
            val expiry = if (resetting) null else input.optionalInstant(LocalField.VALID_UNTIL)
            LocalPricingRequestV1.ChangeBranchPrice(skuId, ruleId, companyRevision, policyRevision, overrideRevision,
                price, expiry, input.text(LocalField.REASON))
        }
        // Receipt bytes can originate only from the native-completed publication gateway.
        else -> invalid()
    }
    input.finish()
    return MutationRequest(intent.operationId, LocalPricingProfile.COMMAND, LocalPricingCodec.encode(request))
}

internal fun pricingRequestAction(request: LocalPricingRequestV1): LocalAction = when (request) {
    is LocalPricingRequestV1.ReceivePolicy -> LocalAction.PRICE_POLICY_RECEIVE
    is LocalPricingRequestV1.ActivatePolicy -> LocalAction.PRICE_POLICY_ACTIVATE
    is LocalPricingRequestV1.IndexRetainedCompanyHistory -> LocalAction.PRICE_HISTORY_INDEX
    is LocalPricingRequestV1.ChangeBranchPrice -> if (request.unitPrice == null) LocalAction.PRICE_RESET else LocalAction.PRICE_SET
}

internal suspend fun pricingContinuationHint(access: GatewayAccess, entry: StoredClientIntentV1): Boolean {
    val saved = entry.request()
    if (saved.command != LocalPricingProfile.COMMAND || !actionMatches(entry.localAction, saved)) return false
    return when (val request = LocalPricingCodec.decode(saved.payload)) {
        is LocalPricingRequestV1.ReceivePolicy -> request.publication.definition.company == access.view.scope.company() &&
            access.allow(entry.localAction, request.publication.definition.publicationId)
        is LocalPricingRequestV1.ActivatePolicy -> {
            val policy = pricingRead(access, LocalPricingQueryV1.PolicyPublication(request.publicationId)).page.policy ?: return false
            val context = pricingRead(access, LocalPricingQueryV1.Context(policy.skuId, policy.ruleId)).page.context ?: return false
            access.allow(entry.localAction, context.contextId)
        }
        is LocalPricingRequestV1.IndexRetainedCompanyHistory -> access.allow(entry.localAction, access.view.scope.branchId)
        is LocalPricingRequestV1.ChangeBranchPrice -> {
            // A fresh read locates only the saved target. Prices and every saved CAS stay unchanged.
            val context = pricingRead(access, LocalPricingQueryV1.Context(request.skuId, request.ruleId)).page.context ?: return false
            access.allow(entry.localAction, context.contextId)
        }
    }
}

internal fun pricingIntentSummary(intent: LocalCommandIntent, request: MutationRequest): List<LocalRecordValue> = buildList {
    when (val saved = LocalPricingCodec.decode(request.payload)) {
        is LocalPricingRequestV1.ReceivePolicy -> unavailable()
        is LocalPricingRequestV1.ActivatePolicy -> {
            add(recordValue(text("Exact received publication", "المنشور المستلم الدقيق"), literal(saved.publicationId.value)))
            listOf(LocalField.POLICY_ID, LocalField.SKU_ID, LocalField.PRICE_RULE_ID).forEach { field ->
                val id = (intent.values.singleOrNull { it.field == field }?.value as? LocalScalarValue.Identifier)?.value ?: unavailable()
                add(recordValue(fieldLabel(field), literal(id.value)))
            }
            add(recordValue(fieldLabel(LocalField.EXPECTED_PRICE_POLICY_STORAGE_REVISION), optionalStorageRevisionText(saved.expectedPolicyStorageRevision)))
        }
        is LocalPricingRequestV1.IndexRetainedCompanyHistory -> {
            val branch = (intent.values.singleOrNull { it.field == LocalField.SOURCE_ID }?.value as? LocalScalarValue.Identifier)?.value ?: unavailable()
            add(recordValue(text("Branch index identity", "هوية فهرس الفرع"), literal(branch.value)))
            add(recordValue(fieldLabel(LocalField.EXPECTED_PRICE_HISTORY_INDEX_STORAGE_REVISION), optionalStorageRevisionText(saved.expectedIndexStorageRevision)))
            add(recordValue(text("Requested work", "العمل المطلوب"), text("At most one retained company publication", "منشور شركة محفوظ واحد على الأكثر")))
        }
        is LocalPricingRequestV1.ChangeBranchPrice -> {
            add(recordValue(fieldLabel(LocalField.SKU_ID), literal(saved.skuId.value)))
            add(recordValue(fieldLabel(LocalField.PRICE_RULE_ID), literal(saved.ruleId.value)))
            add(recordValue(fieldLabel(LocalField.EXPECTED_COMPANY_PRICE_REVISION), literal(saved.expectedCompanyPriceRevision.value.toString())))
            add(recordValue(fieldLabel(LocalField.EXPECTED_PRICE_POLICY_REVISION), literal(saved.expectedPolicyEntryRevision.value.toString())))
            add(recordValue(fieldLabel(LocalField.EXPECTED_OVERRIDE_STORAGE_REVISION), optionalStorageRevisionText(saved.expectedOverrideStorageRevision)))
            add(recordValue(fieldLabel(LocalField.UNIT_PRICE), saved.unitPrice?.let(::priceText) ?: text("Reset / company inheritance", "إعادة تعيين / اتباع الشركة")))
            add(recordValue(fieldLabel(LocalField.VALID_UNTIL), saved.effectiveUntil?.let { literal(it.canonicalString()) } ?: text("No expiry specified", "لم يُحدد انتهاء")))
            add(recordValue(fieldLabel(LocalField.REASON), literal(saved.reasonCode)))
        }
    }
}

internal fun pricingRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): Pair<LocalText, LocalRecord?> {
    val saved = entry.request()
    if (saved.command != LocalPricingProfile.COMMAND || !actionMatches(entry.localAction, saved)) unavailable()
    val request = LocalPricingCodec.decode(saved.payload)
    val value = LocalPricingCodec.decodeResult(reply.result)
    if (value.code != reply.code) unavailable()
    if (!reply.committed) {
        if (value.id != null || value.storageRevision != null) unavailable()
        return text("Recorded pricing rejection: ${value.code}", "رفض تسعير مسجل: ${value.code}") to null
    }
    val id = value.id ?: unavailable()
    val revision = value.storageRevision ?: unavailable()
    if (revision.value < 1) unavailable()
    val record = when (request) {
        is LocalPricingRequestV1.ReceivePolicy -> {
            val definition = request.publication.definition
            if (definition.company != entry.owner.scope.company() || value.code != "catalog.price_policy_received" ||
                id != definition.publicationId || revision != Revision(1)) unavailable()
            LocalRecord(LocalRecordReference(LocalRecordKind.PRICE_POLICY_PUBLICATION, definition.publicationId), entry.label.ui(),
                text("Received; separate activation pending", "مستلمة؛ التفعيل المنفصل معلق"), listOf(
                    recordValue(fieldLabel(LocalField.POLICY_ID), literal(definition.entryId.value)),
                    recordValue(fieldLabel(LocalField.SKU_ID), literal(definition.skuId.value)),
                    recordValue(fieldLabel(LocalField.PRICE_RULE_ID), literal(definition.ruleId.value)),
                    recordValue(text("Recorded receipt storage revision", "مراجعة تخزين الاستلام المسجلة"), literal(revision.value.toString()))))
        }
        is LocalPricingRequestV1.ActivatePolicy -> {
            if (value.code != "catalog.price_policy_activated" || revision != (request.expectedPolicyStorageRevision?.next() ?: Revision(1))) unavailable()
            LocalRecord(LocalRecordReference(LocalRecordKind.PRICE_POLICY_PUBLICATION, request.publicationId), entry.label.ui(), literal(value.code), listOf(
                recordValue(text("Exact received publication", "المنشور المستلم الدقيق"), literal(request.publicationId.value)),
                recordValue(text("Recorded active price-policy storage revision", "مراجعة تخزين سياسة السعر النشطة المسجلة"), literal(revision.value.toString()))))
        }
        is LocalPricingRequestV1.IndexRetainedCompanyHistory -> {
            if (value.code !in setOf("catalog.price_history_indexed", "catalog.price_history_index_progress") ||
                id != entry.owner.scope.branchId || revision != (request.expectedIndexStorageRevision?.next() ?: Revision(1))) unavailable()
            LocalRecord(LocalRecordReference(LocalRecordKind.PRICE_HISTORY_INDEX, id), entry.label.ui(),
                if (value.code == "catalog.price_history_indexed") priceIndexStatusText("COMPLETE") else priceIndexStatusText("INDEXING"), listOf(
                    recordValue(text("Recorded price-history index storage revision", "مراجعة تخزين فهرس سجل الأسعار المسجلة"), literal(revision.value.toString()))))
        }
        is LocalPricingRequestV1.ChangeBranchPrice -> {
            if (value.code != (if (request.unitPrice == null) "catalog.branch_price_reset" else "catalog.branch_price_changed") ||
                revision != (request.expectedOverrideStorageRevision?.next() ?: Revision(1))) unavailable()
            LocalRecord(LocalRecordReference(LocalRecordKind.PRICE_RULE, request.ruleId, parentId = request.skuId), entry.label.ui(), literal(value.code), listOf(
                recordValue(fieldLabel(LocalField.SKU_ID), literal(request.skuId.value)),
                recordValue(fieldLabel(LocalField.PRICE_RULE_ID), literal(request.ruleId.value)),
                recordValue(text("Recorded branch-override storage revision", "مراجعة تخزين سعر الفرع المسجلة"), literal(revision.value.toString()))))
        }
    }
    val message = when (request) {
        is LocalPricingRequestV1.ReceivePolicy -> text("Company price policy received. Review its separate activation; no effective price changed.",
            "استُلمت سياسة أسعار الشركة. راجع تفعيلها المنفصل؛ لم يتغير السعر الفعلي.")
        is LocalPricingRequestV1.ActivatePolicy -> text("The Master activated this exact received price-policy publication. Branch-price changes require their own current authority.",
            "فعّل الجهاز الرئيسي منشور سياسة السعر المستلم الدقيق. تتطلب تغييرات سعر الفرع صلاحياتها الحالية الخاصة.")
        is LocalPricingRequestV1.IndexRetainedCompanyHistory -> if (value.code == "catalog.price_history_indexed")
            text("The Master recorded a complete retained company price-history index.", "سجل الجهاز الرئيسي اكتمال فهرس سجل أسعار الشركة المحفوظ.")
        else text("One bounded history step was recorded. Acknowledge and refresh before explicitly choosing another step.",
            "سُجلت خطوة سجل محدودة واحدة. أكد النتيجة وحدّث قبل اختيار خطوة أخرى صراحةً.")
        is LocalPricingRequestV1.ChangeBranchPrice -> text("Price result recorded: ${value.code}", "سُجلت نتيجة السعر: ${value.code}")
    }
    return message to record
}

internal fun priceText(price: UnitPrice): LocalText = literal("${price.amount.canonicalString()} ${price.currency.name}")
