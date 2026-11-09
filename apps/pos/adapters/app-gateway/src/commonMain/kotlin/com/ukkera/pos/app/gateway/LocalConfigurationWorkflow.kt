package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

internal data class ConfigurationCut(val page: LocalConfigurationPageV1, val reply: ReadReply.Data)
internal suspend fun configurationRead(access: GatewayAccess, query: LocalConfigurationQueryV1): ConfigurationCut {
    val reply = access.read(ReadRequest(LocalConfigurationProfile.QUERY, LocalConfigurationCodec.query(query)))
    val page = LocalConfigurationCodec.decodePage(reply.payload)
    if (page.kind != query.kind || page.items.size > query.limit || page.items.map { it.id }.distinct().size != page.items.size) unavailable()
    if (query.kind == LocalConfigurationProfile.WAREHOUSE && page.catalogRevision != null) unavailable()
    if (query.kind in setOf(LocalConfigurationProfile.SUPPLIER, LocalConfigurationProfile.WAREHOUSE)) {
        val after = query.after
        val id = query.id
        // Validate the entire original directory projection before search/filtering or form
        // construction. Wrong-kind details or a broken frontier never become a partial page.
        if (page.items.any { it.names == null || it.active == null || it.code != null ||
                it.revision.value == Long.MAX_VALUE || it.storageRevision != it.revision.next() } ||
            page.items.zipWithNext().any { (a, b) -> a.id.value >= b.id.value } ||
            after != null && page.items.any { it.id.value <= after.value } ||
            page.nextAfter != null && (id != null || page.items.size != query.limit || page.nextAfter != page.items.lastOrNull()?.id)) unavailable()
        if (id == null) {
            if (page.detail != null) unavailable()
        } else {
            if (page.items.size > 1 || page.items.any { it.id != id }) unavailable()
            val row = page.items.singleOrNull()
            if (row == null) {
                // Only this genuine, current Master reply can represent an absent selection.
                if (page.detail != null) unavailable()
            } else {
                val matches = when (query.kind) {
                    LocalConfigurationProfile.SUPPLIER -> (page.detail as? LocalConfigurationDetailV1.Supplier)?.value?.let {
                        it.id == id && it.id == row.id && it.names == row.names && it.active == row.active
                    }
                    LocalConfigurationProfile.WAREHOUSE -> (page.detail as? LocalConfigurationDetailV1.Warehouse)?.value?.let {
                        it.id == id && it.id == row.id && it.names == row.names && it.active == row.active
                    }
                    else -> false
                }
                if (matches != true) unavailable()
            }
        }
    }
    return ConfigurationCut(page, reply)
}

internal val supplierConfigurationActions: Set<LocalAction> = setOf(LocalAction.SUPPLIER_CREATE, LocalAction.SUPPLIER_UPDATE)

internal fun directoryConfigurationProfile(action: LocalAction): LocalMasterRpcWorkflowProfile? = when (action) {
    LocalAction.SUPPLIER_CREATE, LocalAction.SUPPLIER_UPDATE -> LocalMasterRpcWorkflowProfile.SUPPLIERS
    LocalAction.WAREHOUSE_CONFIGURE -> LocalMasterRpcWorkflowProfile.WAREHOUSES
    else -> null
}

/** Qualifies the actual normalized body, never merely the shared configuration command key. */
internal fun directoryConfigurationRequest(profile: LocalMasterRpcWorkflowProfile, action: LocalAction,
    request: MutationRequest): LocalConfigurationRequestV1 {
    if (request.command != LocalConfigurationProfile.COMMAND || directoryConfigurationProfile(action) != profile || request.actionApprovalId != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val saved = try { LocalConfigurationCodec.decode(request.payload) }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    val revision = when (profile) {
        LocalMasterRpcWorkflowProfile.SUPPLIERS -> {
            if (saved !is LocalConfigurationRequestV1.ConfigureSupplier) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
            saved.expectedStorageRevision
        }
        LocalMasterRpcWorkflowProfile.WAREHOUSES -> {
            if (saved !is LocalConfigurationRequestV1.ConfigureWarehouse) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
            saved.expectedStorageRevision
        }
        LocalMasterRpcWorkflowProfile.REPORTS, LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS,
        LocalMasterRpcWorkflowProfile.KITCHEN, LocalMasterRpcWorkflowProfile.RETAIL_ORDERS,
        LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS, LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS,
        LocalMasterRpcWorkflowProfile.FOOD_CHECKS, LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL,
        LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS,
        LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE, LocalMasterRpcWorkflowProfile.INVOICE_MATCHING ->
            throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    if (LocalConfigurationCodec.encode(saved) != request.payload ||
        revision?.value == 0L || profile == LocalMasterRpcWorkflowProfile.SUPPLIERS &&
        action != (if (revision == null) LocalAction.SUPPLIER_CREATE else LocalAction.SUPPLIER_UPDATE))
        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return saved
}

internal fun supplierConfigurationRequest(action: LocalAction, request: MutationRequest): LocalConfigurationRequestV1.ConfigureSupplier =
    directoryConfigurationRequest(LocalMasterRpcWorkflowProfile.SUPPLIERS, action, request) as LocalConfigurationRequestV1.ConfigureSupplier

internal fun savedDirectoryConfigurationRequest(profile: LocalMasterRpcWorkflowProfile,
    entry: StoredClientIntentV1): LocalConfigurationRequestV1 {
    if (entry.formVersion != 1 || entry.actionApproval != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val saved = directoryConfigurationRequest(profile, entry.localAction, entry.request())
    try {
        entry.validate(entry.owner)
        entry.recorded?.let { retained ->
            // Validate retained outcomes before pending-set cursors or continuation hints.
            // Valid hexadecimal and matching original identity do not qualify its payload.
            val payload = decodeHex(retained.payloadHex, 65_536).useWiped { StoredBytes(it) }
            directoryConfigurationOutcome(saved, retained.committed, retained.code, payload)
        }
    }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    return saved
}

internal fun savedSupplierConfigurationRequest(entry: StoredClientIntentV1): LocalConfigurationRequestV1.ConfigureSupplier =
    savedDirectoryConfigurationRequest(LocalMasterRpcWorkflowProfile.SUPPLIERS, entry) as LocalConfigurationRequestV1.ConfigureSupplier

/** The original typed outcome is validated before retention, projection or acknowledgement. */
internal fun directoryConfigurationRecorded(profile: LocalMasterRpcWorkflowProfile,
    entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalConfigurationResultV1 {
    val saved = savedDirectoryConfigurationRequest(profile, entry)
    if (reply.operationId != entry.operationId || reply.originalActorId != entry.owner.actorId || reply.physicalStart != null)
        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return directoryConfigurationOutcome(saved, reply.committed, reply.code, reply.result)
}

internal fun supplierConfigurationRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalConfigurationResultV1 =
    directoryConfigurationRecorded(LocalMasterRpcWorkflowProfile.SUPPLIERS, entry, reply)

private fun directoryConfigurationOutcome(saved: LocalConfigurationRequestV1,
    committed: Boolean, code: String, payload: StoredBytes): LocalConfigurationResultV1 {
    val result = try { LocalConfigurationCodec.decodeResult(payload) }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    val (id, expectedStorageRevision) = when (saved) {
        is LocalConfigurationRequestV1.ConfigureSupplier -> saved.supplier.id to saved.expectedStorageRevision
        is LocalConfigurationRequestV1.ConfigureWarehouse -> saved.warehouse.id to saved.expectedStorageRevision
        else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    val warehouse = saved as? LocalConfigurationRequestV1.ConfigureWarehouse
    // Both directory variants use this exact production carrier. Reject changed bytes
    // before a result can enter the original journal, presentation or acknowledgement.
    val canonical = journalJson.encodeToString(LocalConfigurationResultV1.serializer(), result)
        .encodeToByteArray(throwOnInvalidSequence = true).useWiped { StoredBytes(it) }
    if (canonical != payload || committed && warehouse?.warehouse?.active == false)
        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    val revision = result.revision
    if (committed) {
        if (code != "committed" || result.code != "configuration.applied" || result.id != id || revision == null ||
            (expectedStorageRevision == null) != (revision.value == 0L))
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        // The production configuration store joins storage revision to domain revision + 1;
        // an edit's actual resulting domain revision is exactly its original storage CAS.
        if (revision != (expectedStorageRevision ?: Revision(0)) || revision.value == Long.MAX_VALUE)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    } else {
        val refusals = if (warehouse?.warehouse?.active == false) setOf("warehouse.controlled_retirement_required")
            else setOf("configuration.stale_revision", "configuration.capacity_required")
        if (code != result.code || result.code !in refusals || result.id != null || result.revision != null)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    }
    return result
}

internal suspend fun skuDetail(access: GatewayAccess, id: DomainId): Pair<LocalConfigurationDetailV1.Sku, LocalConfigurationListItemV1> {
    val result = configurationRead(access, LocalConfigurationQueryV1(LocalConfigurationProfile.SKU, id = id)).page
    val detail = result.detail as? LocalConfigurationDetailV1.Sku ?: invalid(LocalField.SKU_ID)
    val row = result.items.singleOrNull() ?: invalid(LocalField.SKU_ID)
    if (row.id != id || detail.skuId != id) unavailable()
    return detail to row
}

internal suspend fun configurationWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    access.installed.requireWorkflow(query)
    val reference = query.reference
    val kinds = when (query.workflow) {
        LocalWorkflow.CATALOG -> listOf(LocalConfigurationProfile.SKU)
        LocalWorkflow.PURCHASING -> when (query.purchasing?.section) {
            LocalPurchasingSection.SUPPLIERS -> listOf(LocalConfigurationProfile.SUPPLIER)
            LocalPurchasingSection.RECEIVING_ALLOWANCES -> listOf(LocalConfigurationProfile.BASIS)
            else -> invalid()
        }
        LocalWorkflow.SETUP -> if (access.installed.isWarehouseOnly) listOf(LocalConfigurationProfile.WAREHOUSE)
            else listOf(LocalConfigurationProfile.WAREHOUSE, LocalConfigurationProfile.TENDER, LocalConfigurationProfile.SALES_ROUTE)
        else -> invalid()
    }
    val selectedKind = reference?.kind?.let { record -> configurationKind(record) ?: invalid() }
    if (selectedKind != null && selectedKind !in kinds) invalid()
    val pageParts = query.cursor?.split('|')
    if (pageParts != null && (pageParts.size != 3 || pageParts[0] != "cfg" || pageParts[1] !in kinds)) invalid()
    val kind = selectedKind ?: pageParts?.get(1) ?: kinds.first()
    val after = pageParts?.get(2)?.takeIf { it != "-" }?.let { try { DomainId(it) } catch (_: IllegalArgumentException) { invalid() } }
    if (kind == LocalConfigurationProfile.SUPPLIER && access.installed.has(LocalSupplierProfileV2.QUERY))
        return supplierWorkflowV2(access, query, after)
    val barcode = if (kind == LocalConfigurationProfile.SKU && reference == null && query.search.isNotEmpty() && query.search.all { it.code in 0x21..0x7e }) query.search else null
    val cut = configurationRead(access, LocalConfigurationQueryV1(kind, id = reference?.id, after = if (barcode == null) after else null, limit = 25, barcode = barcode))
    val detail = cut.page.detail
    val records = cut.page.items.filter { query.search.isBlank() || barcode != null || it.id.value == query.search ||
        it.code?.contains(query.search, ignoreCase = true) == true || it.names?.english?.contains(query.search, ignoreCase = true) == true ||
        it.names?.arabic?.contains(query.search) == true }.map { row -> configurationRecord(kind, row, detail) }
    val forms = mutableListOf<LocalActionForm>()
    if (access.installed.has(LocalConfigurationProfile.COMMAND)) {
        when (query.workflow) {
            LocalWorkflow.SETUP -> {
                val selectedWarehouse = (detail as? LocalConfigurationDetailV1.Warehouse)?.value
                if (access.allow(LocalAction.WAREHOUSE_CONFIGURE, selectedWarehouse?.id) &&
                    (!access.installed.isWarehouseOnly || selectedWarehouse != null || reference == null)) {
                    val selected = selectedWarehouse
                    val row = selected?.let { value -> cut.page.items.singleOrNull { it.id == value.id } }
                    forms += form(LocalAction.WAREHOUSE_CONFIGURE, configurationBase(LocalField.WAREHOUSE_ID, selected?.id, row?.storageRevision,
                        selected?.names, selected?.active ?: if (access.installed.isWarehouseOnly) true else null),
                        text("Create a warehouse or rename the selected warehouse using its displayed storage revision. An inactive request is recorded as requiring controlled retirement; directory configuration cannot retire a warehouse.",
                            "أنشئ مخزنًا أو أعد تسمية المخزن المختار باستخدام مراجعة التخزين المعروضة. يُسجل طلب إلغاء النشاط بوصفه متطلبًا لتقاعد مضبوط؛ ولا تتيح إعدادات الدليل إيقاف المخزن."))
                }
                if (access.allow(LocalAction.TENDER_CONFIGURE)) {
                    val selected = (detail as? LocalConfigurationDetailV1.ExternalTender)?.value
                    val row = selected?.let { value -> cut.page.items.singleOrNull { it.id == value.id } }
                    forms += form(LocalAction.TENDER_CONFIGURE, configurationBase(LocalField.TENDER_ID, selected?.id, row?.storageRevision,
                        selected?.names, selected?.active) + listOf(choiceField(LocalField.MODE, LocalExternalTenderKindV1.entries.map { LocalChoice(it.name, tenderKind(it)) }, selected?.kind?.name),
                        scalar(LocalField.REQUIRES_TERMINAL, LocalInputKind.CHECKBOX, LocalScalarValue.Flag(selected?.requiresTerminal ?: false), required = false),
                        scalar(LocalField.REQUIRES_REFERENCE, LocalInputKind.CHECKBOX, LocalScalarValue.Flag(selected?.requiresReference ?: false), required = false)))
                }
                if (access.allow(LocalAction.SALES_ROUTE_CONFIGURE)) {
                    val selected = (detail as? LocalConfigurationDetailV1.SalesRoute)?.value
                    val row = selected?.let { value -> cut.page.items.singleOrNull { it.id == value.id } }
                    val warehouse = selected?.let { route ->
                        configurationRead(access, LocalConfigurationQueryV1(LocalConfigurationProfile.WAREHOUSE, id = route.warehouseId))
                            .page.items.singleOrNull()?.takeIf { it.active == true && it.id == route.warehouseId }?.let {
                                LocalReferenceChoice(LocalRecordReference(LocalRecordKind.WAREHOUSE, it.id, it.revision), names(it.names, it.id))
                            }
                    }
                    forms += form(LocalAction.SALES_ROUTE_CONFIGURE, configurationIdentity(LocalField.DOCUMENT_ID, selected?.id, row?.storageRevision) + listOf(
                            choiceField(LocalField.ACTIVITY, activityChoices(), selected?.activity?.name), choiceField(LocalField.CHANNEL, channelChoices(), selected?.channel?.name),
                            pagedReferenceField(LocalField.WAREHOUSE_ID, LocalRecordKind.WAREHOUSE, initial = warehouse),
                            scalar(LocalField.ACTIVE, LocalInputKind.CHECKBOX, LocalScalarValue.Flag(selected?.active ?: false), required = false)),
                            text("An explicit same-branch warehouse route, never the first warehouse guessed by the client. Browse authorized pages or look up its exact ID; an inactive destination must be reselected.",
                                "مسار مخزن صريح داخل الفرع، وليس أول مخزن يخمنه العميل. تصفح الصفحات المصرح بها أو ابحث بالمعرف الدقيق؛ يجب إعادة اختيار الوجهة غير النشطة."))
                }
            }
            LocalWorkflow.PURCHASING -> {
                val supplier = (detail as? LocalConfigurationDetailV1.Supplier)?.value
                val supplierRow = supplier?.let { value -> cut.page.items.singleOrNull { it.id == value.id } }
                val action = if (supplier == null) LocalAction.SUPPLIER_CREATE else LocalAction.SUPPLIER_UPDATE
                if (kind == LocalConfigurationProfile.SUPPLIER && access.allow(action, supplier?.id) &&
                    (!access.installed.isSupplierOnly || supplier != null || reference == null))
                    forms += form(action, configurationBase(LocalField.SUPPLIER_ID, supplier?.id, supplierRow?.storageRevision, supplier?.names, supplier?.active))
                if (kind == LocalConfigurationProfile.BASIS) receivingApprovalForm(access)?.let(forms::add)
            }
            else -> Unit // No arbitrary branch product publication/create authority is supplied.
        }
    }
    val next = if (reference != null || barcode != null) null else cut.page.nextAfter?.let { "cfg|$kind|${it.value}" }
        ?: kinds.getOrNull(kinds.indexOf(kind) + 1)?.let { "cfg|$it|-" }
    val notice = if (access.installed.isWarehouseOnly) text(
        "Warehouse directory. Only this current page is shown. Changes and original-result recovery require their own current Master permission. ${readCut(cut.reply).english}",
        "دليل المخازن. تُعرض هذه الصفحة الحالية فقط. يتطلب التعديل واستعادة النتيجة الأصلية إذنًا ساريًا خاصًا من الجهاز الرئيسي. ${readCut(cut.reply).arabic}")
    else if (query.workflow == LocalWorkflow.CATALOG) text(
        "Installed, locally authorized catalog only. New company publications require an authentic signed import; recipes/variant authoring is not supplied by this view. ${readCut(cut.reply).english}",
        "الكتالوج المثبت والمصرح به محليًا فقط. تتطلب منشورات الشركة الجديدة استيرادًا موقعًا أصليًا؛ هذا العرض لا يوفر تحرير الوصفات أو المتغيرات. ${readCut(cut.reply).arabic}")
    else text("${kindLabel(kind).english}. Only this current page is shown; receiving allowances are not invoices or supplier settlement. ${readCut(cut.reply).english}",
        "${kindLabel(kind).arabic}. تُعرض الصفحة الحالية فقط؛ سماح الاستلام ليس فاتورة أو تسوية مورد. ${readCut(cut.reply).arabic}")
    val pricingSku = (detail as? LocalConfigurationDetailV1.Sku)?.takeIf {
        query.workflow == LocalWorkflow.CATALOG && access.installed.has(LocalPricingProfile.QUERY)
    }
    val rightsNavigation = if (!access.installed.isRestricted && reference == null && query.search.isEmpty() && query.cursor == null &&
        query.workflow in setOf(LocalWorkflow.CATALOG, LocalWorkflow.SETUP)) returnRightsNavigation(access) else emptyList()
    return LocalWorkflowPage(query.workflow, records + pricingSku?.let { priceNavigation(access, it) }.orEmpty() + rightsNavigation, forms, notice, next,
        priceControls = pricingSku?.let { LocalPriceReadControls(it.skuId, it.baseUnitId, it.quantityScale) })
}

internal fun receivingApprovalForm(access: GatewayAccess, openingOnly: Boolean = false): LocalActionForm? {
    if (!access.installed.allows(LocalAction.RECEIVING_APPROVE)) return null
    val purchase = !openingOnly && access.hasHint(Permission(SecurityAction.PURCHASING_APPROVE, PermissionVerb.ADMINISTER))
    val opening = access.hasHint(Permission(SecurityAction.OPENING_INVENTORY_APPROVE, PermissionVerb.ADMINISTER))
    if ((!purchase && !opening) || !access.installed.has(LocalConfigurationProfile.QUERY)) return null
    val result = form(LocalAction.RECEIVING_APPROVE, listOf(newIdField(LocalField.BASIS_ID), choiceField(LocalField.MODE, buildList {
        if (opening) add(LocalChoice(LocalBasisKindV1.OPENING.name, text("Protected opening", "افتتاحي معتمد")))
        if (purchase) add(LocalChoice(LocalBasisKindV1.LOCAL_PURCHASE.name, text("Local purchase allowance", "سماح شراء محلي")))
    }, if (openingOnly) LocalBasisKindV1.OPENING.name else null).copy(readOnly = openingOnly)) +
        (if (openingOnly) emptyList() else listOf(pagedReferenceField(LocalField.SUPPLIER_ID, LocalRecordKind.SUPPLIER, required = false))) + listOf(
        instantField(LocalField.VALID_FROM), instantField(LocalField.VALID_UNTIL, required = false),
        LocalFormField.Lines(LocalField.LINES, fieldLabel(LocalField.LINES), listOf(
            pagedReferenceField(LocalField.SKU_ID, LocalRecordKind.SKU),
            pagedReferenceField(LocalField.WAREHOUSE_ID, LocalRecordKind.WAREHOUSE),
            scalar(LocalField.MAXIMUM_QUANTITY, LocalInputKind.DECIMAL, maximum = 32), moneyField(LocalField.MAXIMUM_OWNED_VALUE),
            scalar(LocalField.INSPECTION_REQUIRED, LocalInputKind.CHECKBOX, LocalScalarValue.Flag(false), required = false)), maximumLines = 16)),
        if (openingOnly) text(
            "APPROVAL ONLY: request finite quantity/value ceilings and validity for counted first stock of existing approved catalog SKUs and actual warehouses. This approval does not post inventory, costs, sales, revenue, supplier debt or drawer float. Opening has no supplier. Select the recorded opening basis and review its counted slices in a separate posting step; ceilings are not remaining balances. Base units come from actual SKU definitions and the master enforces the value-bound opening-approval authority.",
            "اعتماد فقط: اطلب حدودًا محددة للكمية والقيمة ومدة صلاحية للمخزون الأول المعدود لأصناف كتالوج قائمة معتمدة ومخازن فعلية. لا يرحل هذا الاعتماد المخزون أو التكلفة أو المبيعات أو الإيراد أو دين المورد أو رصيد الدرج. الافتتاحي بلا مورد. اختر الأساس الافتتاحي المسجل وراجع أجزاءه المعدودة في خطوة ترحيل منفصلة؛ الحدود ليست أرصدة متبقية. تأتي الوحدات الأساسية من تعريفات الأصناف الفعلية ويتحقق الجهاز الرئيسي من صلاحية اعتماد الافتتاحي المرتبطة بالقيمة.")
        else text("A bounded requested allowance, not authority by itself, an invoice or permission to pay a supplier. Opening requires no supplier; local purchase requires an active supplier. Base units are read from actual SKU definitions.",
            "سماح مطلوب محدود، وليس صلاحية بحد ذاته أو فاتورة أو إذنًا بدفع مورد. الافتتاحي بلا مورد؛ الشراء المحلي يتطلب موردًا نشطًا. تُقرأ الوحدات الأساسية من تعريفات الأصناف الفعلية."))
    return if (openingOnly) result.copy(title = text("Approve opening ceilings — do not post stock", "اعتماد حدود الافتتاحي — بلا ترحيل مخزون"),
        confirmation = text("I reviewed the exact existing SKU/warehouse identities, quantity/value ceilings and validity. Save one immutable APPROVAL request for separate submission; it does not record counted opening stock. Posting is a separate authorized action and uses its own original operation.",
            "راجعت معرفات الأصناف والمخازن القائمة الدقيقة وحدود الكمية والقيمة ومدة الصلاحية. احفظ طلب اعتماد ثابتًا واحدًا لإرساله منفصلًا؛ لا يسجل المخزون الافتتاحي المعدود. الترحيل إجراء مخول منفصل بعمليته الأصلية الخاصة.")) else result
}

internal suspend fun prepareConfiguration(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.action in supplierConfigurationActions && intent.formVersion == 2)
        return prepareSupplierConfigurationV2(access, intent)
    val input = InputValues(intent.values)
    if (intent.action != LocalAction.RECEIVING_APPROVE) {
        val reference = intent.reference
        val targetField = when (intent.action) {
            in supplierConfigurationActions -> LocalField.SUPPLIER_ID
            LocalAction.WAREHOUSE_CONFIGURE -> LocalField.WAREHOUSE_ID
            else -> null
        }
        val target = targetField?.let(input::id)
        access.requireAction(intent.action, target)
        if (intent.lines.isNotEmpty() || intent.lineField != null) invalid(LocalField.LINES)
        if (access.installed.isDirectoryOnly && reference != null && reference.id != target) invalid(targetField)
    }
    val request: LocalConfigurationRequestV1 = when (intent.action) {
        LocalAction.WAREHOUSE_CONFIGURE -> {
            val revision = input.configurationRevision()
            val reference = intent.reference
            val displayedRevision = reference?.revision
            if (access.installed.isWarehouseOnly && ((reference == null) != (revision == null) ||
                    reference != null && (displayedRevision == null ||
                        displayedRevision.value == Long.MAX_VALUE || displayedRevision.next() != revision)))
                invalid(LocalField.EXPECTED_STORAGE_REVISION)
            LocalConfigurationRequestV1.ConfigureWarehouse(revision,
                LocalWarehouseV1(input.id(LocalField.WAREHOUSE_ID), input.names(), input.flag(LocalField.ACTIVE)))
        }
        LocalAction.TENDER_CONFIGURE -> LocalConfigurationRequestV1.ConfigureTender(input.configurationRevision(),
            LocalTenderV1(input.id(LocalField.TENDER_ID), input.names(), enumChoice<LocalExternalTenderKindV1>(input.choice(LocalField.MODE), LocalField.MODE),
                input.flag(LocalField.REQUIRES_TERMINAL), input.flag(LocalField.REQUIRES_REFERENCE), input.flag(LocalField.ACTIVE)))
        LocalAction.SUPPLIER_CREATE, LocalAction.SUPPLIER_UPDATE -> {
            val revision = input.configurationRevision()
            if ((intent.action == LocalAction.SUPPLIER_CREATE) != (revision == null)) invalid(LocalField.NEW_ITEM)
            LocalConfigurationRequestV1.ConfigureSupplier(revision, LocalSupplierV1(input.id(LocalField.SUPPLIER_ID), input.names(), input.flag(LocalField.ACTIVE)))
        }
        LocalAction.SALES_ROUTE_CONFIGURE -> {
            val warehouse = input.reference(LocalField.WAREHOUSE_ID, LocalRecordKind.WAREHOUSE)
            currentConfigurationReference(access, warehouse, LocalRecordKind.WAREHOUSE, LocalField.WAREHOUSE_ID)
            LocalConfigurationRequestV1.ConfigureSalesRoute(input.configurationRevision(),
                LocalSalesRouteV1(input.id(LocalField.DOCUMENT_ID), enumChoice<LocalSalesActivity>(input.choice(LocalField.ACTIVITY), LocalField.ACTIVITY),
                    enumChoice<LocalSalesChannel>(input.choice(LocalField.CHANNEL), LocalField.CHANNEL), warehouse.id, input.flag(LocalField.ACTIVE)))
        }
        LocalAction.RECEIVING_APPROVE -> {
            val id = input.id(LocalField.BASIS_ID)
            val kind = enumChoice<LocalBasisKindV1>(input.choice(LocalField.MODE), LocalField.MODE)
            if (kind == LocalBasisKindV1.PURCHASE_ORDER_ALLOCATION) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
            val action = if (kind == LocalBasisKindV1.OPENING) SecurityAction.OPENING_INVENTORY_APPROVE else SecurityAction.PURCHASING_APPROVE
            if (!access.hasHint(Permission(action, PermissionVerb.ADMINISTER), id)) throw GatewayFailure(LocalServiceProblem.DENIED)
            val supplier = input.optionalReference(LocalField.SUPPLIER_ID, LocalRecordKind.SUPPLIER)?.let {
                currentConfigurationReference(access, it, LocalRecordKind.SUPPLIER, LocalField.SUPPLIER_ID).id
            }
            val validFrom = input.instant(LocalField.VALID_FROM)
            val validUntil = input.optionalInstant(LocalField.VALID_UNTIL)
            if (intent.lineField != LocalField.LINES || intent.lines.size !in 1..16) invalid(LocalField.LINES)
            val lines = intent.lines.map { row ->
                val line = InputValues(row.values)
                val sku = line.reference(LocalField.SKU_ID, LocalRecordKind.SKU)
                val detail = skuDetail(access, sku.id).first
                if (detail.skuRevision != sku.revision || !detail.active || detail.tracking == LocalTrackingV1.NONE ||
                    kind != LocalBasisKindV1.OPENING && !detail.canPurchase) invalid(LocalField.SKU_ID)
                val warehouse = line.reference(LocalField.WAREHOUSE_ID, LocalRecordKind.WAREHOUSE)
                currentConfigurationReference(access, warehouse, LocalRecordKind.WAREHOUSE, LocalField.WAREHOUSE_ID)
                LocalApprovedStockLineV1(row.lineId, sku.id, warehouse.id,
                    Quantity.parse(detail.baseUnitId, line.decimal(LocalField.MAXIMUM_QUANTITY), detail.quantityScale),
                    line.money(LocalField.MAXIMUM_OWNED_VALUE), line.flag(LocalField.INSPECTION_REQUIRED)).also { line.finish() }
            }
            LocalConfigurationRequestV1.ApproveReceiving(LocalReceivingApprovalV1(id, kind, supplier, validFrom, validUntil, lines))
        }
        else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    input.finish()
    return MutationRequest(intent.operationId, LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(request))
}

internal fun InputValues.configurationRevision(): Revision? {
    val new = flag(LocalField.NEW_ITEM)
    val revision = optionalRevision(LocalField.EXPECTED_STORAGE_REVISION)
    if (new && revision != null || !new && (revision == null || revision.value == 0L)) invalid(LocalField.EXPECTED_STORAGE_REVISION)
    return revision
}
internal fun InputValues.names(): LocalNamesV1 = LocalNamesV1(optionalText(LocalField.NAME_ENGLISH).orEmpty(), optionalText(LocalField.NAME_ARABIC).orEmpty())
internal inline fun <reified T : Enum<T>> enumChoice(value: String, field: LocalField): T = enumValues<T>().singleOrNull { it.name == value } ?: invalid(field)
internal fun names(value: LocalNamesV1?, fallback: DomainId): LocalText = value?.let { text(it.english.ifBlank { it.arabic }, it.arabic.ifBlank { it.english }) } ?: literal(fallback.value)

private fun configurationIdentity(field: LocalField, id: DomainId?, revision: Revision?): List<LocalFormField> = buildList {
    add(idField(field, id ?: DomainId.random()))
    add(scalar(LocalField.NEW_ITEM, LocalInputKind.CHECKBOX, LocalScalarValue.Flag(id == null), required = false, readOnly = true))
    if (revision != null) add(revisionField(LocalField.EXPECTED_STORAGE_REVISION, revision))
}
internal fun configurationBase(field: LocalField, id: DomainId?, revision: Revision?, names: LocalNamesV1?, active: Boolean?): List<LocalFormField> =
    configurationIdentity(field, id, revision) + listOf(
        scalar(LocalField.NAME_ENGLISH, LocalInputKind.TEXT, names?.let { LocalScalarValue.Text(it.english) }, required = false, maximum = 160),
        scalar(LocalField.NAME_ARABIC, LocalInputKind.TEXT, names?.let { LocalScalarValue.Text(it.arabic) }, required = false, maximum = 160),
        scalar(LocalField.ACTIVE, LocalInputKind.CHECKBOX, LocalScalarValue.Flag(active ?: false), required = false))

internal fun activityChoices(): List<LocalChoice> = listOf(
    LocalChoice(LocalSalesActivity.RESTAURANT.name, text("Restaurant", "مطعم")), LocalChoice(LocalSalesActivity.CAFE.name, text("Café", "مقهى")),
    LocalChoice(LocalSalesActivity.CLOTHING.name, text("Clothing", "ملابس")), LocalChoice(LocalSalesActivity.SUPERMARKET.name, text("Supermarket", "سوبر ماركت")))
internal fun channelChoices(): List<LocalChoice> = listOf(
    LocalChoice(LocalSalesChannel.COUNTER.name, text("Counter", "كاشير")), LocalChoice(LocalSalesChannel.DINE_IN.name, text("Dine in", "داخل المكان")),
    LocalChoice(LocalSalesChannel.TAKEAWAY.name, text("Takeaway", "سفري")), LocalChoice(LocalSalesChannel.DELIVERY.name, text("Delivery", "توصيل")))
private fun tenderKind(kind: LocalExternalTenderKindV1): LocalText = when (kind) {
    LocalExternalTenderKindV1.CARD -> text("Card attestation", "إفادة دفع ببطاقة")
    LocalExternalTenderKindV1.EXTERNAL_WALLET -> text("External-wallet attestation", "إفادة دفع بمحفظة خارجية")
    LocalExternalTenderKindV1.OTHER -> text("Other external attestation", "إفادة دفع خارجي أخرى")
}

internal fun configurationKind(kind: LocalRecordKind): String? = when (kind) {
    LocalRecordKind.SKU -> LocalConfigurationProfile.SKU
    LocalRecordKind.WAREHOUSE -> LocalConfigurationProfile.WAREHOUSE
    LocalRecordKind.RECEIVING_BASIS -> LocalConfigurationProfile.BASIS
    LocalRecordKind.TENDER -> LocalConfigurationProfile.TENDER
    LocalRecordKind.SUPPLIER -> LocalConfigurationProfile.SUPPLIER
    LocalRecordKind.SALES_ROUTE -> LocalConfigurationProfile.SALES_ROUTE
    else -> null
}
internal fun configurationRecordKind(kind: String): LocalRecordKind = when (kind) {
    LocalConfigurationProfile.SKU -> LocalRecordKind.SKU
    LocalConfigurationProfile.WAREHOUSE -> LocalRecordKind.WAREHOUSE
    LocalConfigurationProfile.BASIS -> LocalRecordKind.RECEIVING_BASIS
    LocalConfigurationProfile.TENDER -> LocalRecordKind.TENDER
    LocalConfigurationProfile.SUPPLIER -> LocalRecordKind.SUPPLIER
    LocalConfigurationProfile.SALES_ROUTE -> LocalRecordKind.SALES_ROUTE
    else -> unavailable()
}
private fun kindLabel(kind: String): LocalText = when (kind) {
    LocalConfigurationProfile.SKU -> text("Installed SKUs", "الأصناف المثبتة")
    LocalConfigurationProfile.WAREHOUSE -> text("Warehouses", "المخازن")
    LocalConfigurationProfile.BASIS -> text("Approved receiving allowances", "سماحات الاستلام المعتمدة")
    LocalConfigurationProfile.TENDER -> text("External tenders", "وسائل الدفع الخارجية")
    LocalConfigurationProfile.SUPPLIER -> text("Suppliers", "الموردون")
    LocalConfigurationProfile.SALES_ROUTE -> text("Sales stock routes", "مسارات مخزون المبيعات")
    else -> unavailable()
}
private fun configurationRecord(kind: String, row: LocalConfigurationListItemV1, detail: LocalConfigurationDetailV1?): LocalRecord =
    LocalRecord(LocalRecordReference(configurationRecordKind(kind), row.id, row.revision), names(row.names, row.id),
        row.active?.let { if (it) text("Active", "نشط") else text("Inactive", "غير نشط") }, buildList {
            add(recordValue(fieldLabel(LocalField.EXPECTED_STORAGE_REVISION), literal(row.storageRevision.value.toString())))
            row.code?.let { add(recordValue(text("Code", "الرمز"), literal(it))) }
            when (detail) {
                is LocalConfigurationDetailV1.Sku -> {
                    add(recordValue(fieldLabel(LocalField.UNIT), literal(detail.baseUnitId.value)))
                    add(recordValue(text("Quantity precision", "دقة الكمية"), literal(detail.quantityScale.toString())))
                    add(recordValue(text("Tracking / fulfillment", "التتبع / التنفيذ"), literal("${detail.tracking.name} / ${detail.fulfillment.name}")))
                    add(recordValue(text("Purchasable / sellable", "قابل للشراء / البيع"), literal("${detail.canPurchase} / ${detail.canSell}")))
                    add(recordValue(text("Published conversions / price rows", "التحويلات / صفوف الأسعار المنشورة"), literal("${detail.conversions.size} / ${detail.prices.size}")))
                    addAll(productDetailValues(detail))
                }
                is LocalConfigurationDetailV1.ReceivingBasis -> {
                    add(recordValue(fieldLabel(LocalField.MODE), literal(detail.value.kind.name)))
                    detail.value.supplierId?.let { add(recordValue(fieldLabel(LocalField.SUPPLIER_ID), literal(it.value))) }
                    add(recordValue(fieldLabel(LocalField.VALID_FROM), literal(detail.value.validFrom.canonicalString())))
                    detail.value.validUntil?.let { add(recordValue(fieldLabel(LocalField.VALID_UNTIL), literal(it.canonicalString()))) }
                    add(recordValue(text("Approved lines (not received quantities)", "السطور المعتمدة (وليست الكميات المستلمة)"), literal(detail.value.lines.size.toString())))
                }
                is LocalConfigurationDetailV1.SalesRoute -> {
                    add(recordValue(fieldLabel(LocalField.ACTIVITY), literal(detail.value.activity.name)))
                    add(recordValue(fieldLabel(LocalField.CHANNEL), literal(detail.value.channel.name)))
                    add(recordValue(fieldLabel(LocalField.WAREHOUSE_ID), literal(detail.value.warehouseId.value)))
                }
                is LocalConfigurationDetailV1.ExternalTender -> {
                    add(recordValue(fieldLabel(LocalField.MODE), tenderKind(detail.value.kind)))
                    add(recordValue(fieldLabel(LocalField.REQUIRES_TERMINAL), literal(detail.value.requiresTerminal.toString())))
                    add(recordValue(fieldLabel(LocalField.REQUIRES_REFERENCE), literal(detail.value.requiresReference.toString())))
                }
                else -> Unit
            }
        })

/** Only the Master's qualified, pinned product body is displayed; raw SKU IDs imply no version. */
internal fun productDetailValues(detail: LocalConfigurationDetailV1.Sku): List<LocalRecordValue> {
    val product = detail.productDefinition ?: return listOf(recordValue(
        text("Product version", "مراجعة المنتج"),
        text("Authenticated product definition unavailable", "تعريف المنتج الموثق غير متاح")))
    val selections = detail.variantValues ?: unavailable()
    if (product.id != detail.productId) unavailable()
    val metadata = buildString {
        if (product.categoryIds.isNotEmpty()) {
            append("Categories / الفئات\n")
            product.categoryIds.forEach { append(it.value).append('\n') }
        }
        if (product.variantAxes.isNotEmpty()) {
            append("Declared axes and values / المحاور والقيم المعلنة\n")
            product.variantAxes.forEach { axis ->
                append(axis.id.value).append(" / ").append(axis.code.original).append(": ")
                append(axis.values.joinToString(", ") { it.original }).append('\n')
            }
        }
        product.allowedVariantCombinations?.let { rows ->
            append("Allowed combinations / المجموعات المسموح بها\n")
            rows.forEachIndexed { index, row ->
                append(index + 1).append(": ")
                if (row.isEmpty()) append("Simple product / منتج بسيط")
                else row.forEachIndexed { valueIndex, choice ->
                    if (valueIndex > 0) append("; ")
                    append(choice.axisId.value).append(" / ").append(choice.value.original)
                }
                append('\n')
            }
        }
        append("Selected values / القيم المختارة\n")
        if (selections.isEmpty()) append("Simple product — no variant axes / منتج بسيط — بلا محاور متغيرات\n")
        else selections.forEach { append(it.axisId.value).append(" / ").append(it.value.original).append('\n') }
        if (product.attributes.isNotEmpty()) {
            append("Descriptive attributes, not business rules / سمات وصفية وليست قواعد أعمال\n")
            product.attributes.forEach { attribute ->
                append(attribute.key.original).append(": ")
                when (val body = attribute.value) {
                    is LocalProductAttributeValueV1.Text -> append("TEXT / ").append(body.value.original)
                    is LocalProductAttributeValueV1.Decimal -> append("DECIMAL / ").append(body.value.canonicalString())
                    is LocalProductAttributeValueV1.Flag -> append("FLAG / ").append(body.value)
                }
                append('\n')
            }
        }
    }
    return buildList {
        add(recordValue(text("Product / independent revision", "المنتج / مراجعته المستقلة"),
            literal("${product.id.value} / ${product.revision.value}")))
        add(recordValue(text("Product name — English", "اسم المنتج — الإنجليزية"), literal(product.names.english)))
        add(recordValue(text("Product name — Arabic", "اسم المنتج — العربية"), literal(product.names.arabic)))
        add(recordValue(text("Product status", "حالة المنتج"), if (product.status == LocalProductStatusV1.ACTIVE)
            text("Active", "نشط") else text("Archived", "مؤرشف")))
        // One contiguous metadata stream keeps the complete signed ordering and fits the
        // record's 32-row bound even for maximum-size product bodies and selected values.
        var offset = 0
        var part = 1
        while (offset < metadata.length) {
            var end = minOf(offset + 2048, metadata.length)
            if (end < metadata.length && metadata[end - 1].isHighSurrogate() && metadata[end].isLowSurrogate()) end--
            add(recordValue(text("Product metadata — part $part", "بيانات المنتج — الجزء $part"),
                literal(metadata.substring(offset, end))))
            offset = end
            part++
        }
    }
}
