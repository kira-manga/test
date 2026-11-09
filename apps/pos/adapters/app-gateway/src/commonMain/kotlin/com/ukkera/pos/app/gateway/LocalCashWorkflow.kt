package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId

internal suspend fun cashWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val selection = query.cash ?: invalid()
    val selected = cashSelection(query)
    if (selection.section != LocalCashSection.MY_CASH && selected == null) {
        // Empty lookup controls contain no private data. Target-limited grants are checked
        // against the actual ID once entered, not against an invented branch-wide target.
        return LocalWorkflowPage(LocalWorkflow.CASH, emptyList(), emptyList(), text(
            "Enter the exact original ID and refresh. A read never acknowledges or resubmits an operation. Closed review does not expose active drawers or other cashiers' current work.",
            "أدخل المعرف الأصلي المحدد ثم حدّث. القراءة لا تؤكد نتيجة عملية ولا تعيد إرسالها. مراجعة الإغلاق لا تعرض الأدراج النشطة أو العمل الحالي للكاشيرات الآخرين."))
    }
    if (selection.section == LocalCashSection.CLOSED_REVIEW) {
        if (selected?.kind != LocalRecordKind.CASH_SESSION) invalid()
        if (selection.showCounts) return cashCountHistoryWorkflow(access, query, selected.id, review = true)
        return cashClosedWorkflow(access, selected.id, review = true, cursor = query.cursor)
    }
    val forms = mutableListOf<LocalActionForm>()
    val records = mutableListOf<LocalRecord>()
    val commandInstalled = access.installed.has(LocalSalesStoreProfile.CASH_COMMAND)
    fun offer(action: LocalAction, fields: List<LocalFormField>, explanation: LocalText? = null, target: DomainId? = null) {
        if (commandInstalled && access.allow(action, target)) forms += form(action, fields, explanation)
    }
    offer(LocalAction.DRAWER_CONFIGURE, listOf(newIdField(LocalField.DRAWER_ID), scalar(LocalField.LABEL, LocalInputKind.TEXT, maximum = 160)),
        text("Creates a new empty operational drawer, not opening money.", "ينشئ درجًا تشغيليًا جديدًا فارغًا، وليس رصيدًا نقديًا افتتاحيًا."))
    offer(LocalAction.CUSTODIAN_CONFIGURE, listOf(idField(LocalField.CUSTODIAN_ID, access.view.actorId, readOnly = false)),
        text("Operational custody only. This does not create an employee, password, role or permission.",
            "إعداد عهدة تشغيلية فقط. لا ينشئ موظفًا أو كلمة مرور أو دورًا أو صلاحية."))
    if (!access.hasHint(Permission(SecurityAction.CASH_READ, PermissionVerb.PERFORM), selected?.id ?: access.view.actorId)) {
        if (selection.section == LocalCashSection.MY_CASH && access.allow(LocalAction.CASH_REVIEW_CLOSE))
            return LocalWorkflowPage(LocalWorkflow.CASH, emptyList(), forms, text(
                "Select Closed review and enter the original session ID. Your review permission does not grant another cashier's active session or safe balance.",
                "اختر مراجعة الإغلاق وأدخل معرف الجلسة الأصلية. صلاحية المراجعة لا تمنح الاطلاع على جلسة كاشير آخر النشطة أو رصيد خزنته."))
        if (forms.isEmpty()) throw GatewayFailure(LocalServiceProblem.DENIED)
        return LocalWorkflowPage(LocalWorkflow.CASH, emptyList(), forms,
            text("Only configuration is offered. No cash-session or balance projection was requested.",
                "تُعرض الإعدادات فقط. لم يُطلب عرض جلسة نقدية أو رصيد."))
    }
    if (selected != null && selected.kind !in setOf(LocalRecordKind.CUSTODIAN, LocalRecordKind.DRAWER, LocalRecordKind.CASH_SESSION, LocalRecordKind.TRANSFER, LocalRecordKind.SAFE)) invalid()
    if (selection.showCounts && selected != null && selected.kind != LocalRecordKind.CASH_SESSION) invalid()
    if (selected?.kind == LocalRecordKind.TRANSFER) return cashTransferWorkflow(access, selected.id)
    if ((selection.section == LocalCashSection.SAFE_BALANCE || query.search.startsWith("safe:")) && selected != null)
        return cashSafeWorkflow(access, selected.id)
    if (selected?.kind == LocalRecordKind.CASH_SESSION) {
        if (selection.showCounts) return cashCountHistoryWorkflow(access, query, selected.id, review = false)
        val reply = access.read(LocalCashReadCodec.request(LocalCashQueryV1.Session(selected.id)))
        val result = LocalCashReadCodec.decode(reply.payload)
        if (result == LocalCashReadResultV1.NotAvailable) return cashSessionUnavailable()
        val session = (result as? LocalCashReadResultV1.Session)?.session ?: unavailable()
        if (session.id != selected.id || session.custodianId != access.view.actorId) unavailable()
        if (session.status in setOf("SUBMITTED_CLOSED", "REVIEWED")) return cashClosedWorkflow(access, selected.id, review = false, cursor = query.cursor)
        // Active forms also need the actual current work/custodian revision below.
    }
    val browsingSafes = query.search == "safes" || selected?.kind == LocalRecordKind.SAFE || query.cursor?.startsWith("cash-safes:") == true
    val destinationRead = if (browsingSafes) safeChoices(access, selected?.takeIf { it.kind == LocalRecordKind.SAFE }?.id,
        if (selected == null) parseCursor(query.cursor, "cash-safes") else null) else null
    val ownReply = access.read(LocalCashReadCodec.request(LocalCashQueryV1.OwnCash))
    val own = when (val result = LocalCashReadCodec.decode(ownReply.payload)) {
        is LocalCashReadResultV1.NotConfigured -> {
            if (result.custodianId != access.view.actorId) unavailable()
            return LocalWorkflowPage(LocalWorkflow.CASH, listOf(LocalRecord(
                LocalRecordReference(LocalRecordKind.CUSTODIAN, result.custodianId), text("Cash custody not configured", "العهدة النقدية غير مهيأة"))), forms,
                text("Current authorized read found no operational custody setup. No session or zero balance is implied.",
                    "لم تجد القراءة المصرح بها إعدادًا للعهدة التشغيلية. هذا لا يعني وجود جلسة أو رصيد صفري."))
        }
        is LocalCashReadResultV1.OwnCash -> result
        LocalCashReadResultV1.NotAvailable -> return LocalWorkflowPage(LocalWorkflow.CASH, emptyList(), emptyList(),
            text("Your cash custody is not currently available. No session or balance was inferred.", "عهدتك النقدية غير متاحة حاليًا. لم تُستنتج جلسة أو رصيد."))
        else -> unavailable()
    }
    if (own.custodianId != access.view.actorId) unavailable()
    if (selected?.kind == LocalRecordKind.CUSTODIAN && selected.id != own.custodianId) invalid()
    offer(LocalAction.SAFE_CONFIGURE, listOf(newIdField(LocalField.CUSTODY_ID), idField(LocalField.CUSTODIAN_ID, own.custodianId, readOnly = false),
        scalar(LocalField.LABEL, LocalInputKind.TEXT, maximum = 160)),
        text("New empty safe receipt ledger. Existing cash requires a separately counted source and transfer.",
            "سجل استلام خزنة جديد فارغ. النقدية الموجودة تتطلب مصدرًا معدودًا وتحويلًا منفصلًا."))
    records += LocalRecord(LocalRecordReference(LocalRecordKind.CUSTODIAN, own.custodianId, own.assignmentRevision),
        text("Your operational custody", "عهدتك التشغيلية"), values = listOf(
            recordValue(fieldLabel(LocalField.EXPECTED_WORK_REVISION), literal(own.workRevision.value.toString())),
            recordValue(text("Unsettled orders", "الطلبات غير المسواة"), literal(own.unsettledOrderIds.size.toString())),
            recordValue(text("Unresolved returns", "المرتجعات غير المحسومة"), literal(own.unresolvedReturnIds.size.toString()))))

    val session = own.session
    if (selected?.kind == LocalRecordKind.CASH_SESSION && session?.id != selected.id) unavailable()
    if (selection.showCounts) return if (session != null) cashCountHistoryWorkflow(access, query, session.id, review = false)
        else LocalWorkflowPage(LocalWorkflow.CASH, emptyList(), emptyList(), text(
            "You have no current assigned session in this read. Select My session by ID to read a previous session's immutable count history.",
            "لا توجد جلسة معينة حاليًا في هذه القراءة. اختر جلستي بالمعرف لقراءة سجل العد الثابت لجلسة سابقة."))
    if (session != null) {
        if (session.custodianId != own.custodianId) unavailable()
        if (selected?.kind == LocalRecordKind.CASH_SESSION && selected.id != session.id) invalid()
        if (session.status == "CLOSING") {
            val counts = readCashCounts(access, session.id, expectedRevision = session.revision) ?: unavailable()
            if (counts.session != session) unavailable()
            val destinations = destinationRead ?: if (selection.closeToSafe && counts.currentCountId != null &&
                counts.transferId == null && access.allow(LocalAction.CASH_DELIVER_COUNTED, session.id)) safeChoices(access) else null
            val page = cashCountedClosingPage(access, counts, selection.closeToSafe, destinations?.safes.orEmpty())
            val missingDestination = selection.closeToSafe && counts.currentCountId != null && counts.transferId == null &&
                destinations?.safes.orEmpty().none { it.currency == session.expectedCash.currency }
            val destinationNotice = if (missingDestination) text(
                " No compatible destination is on this page. Browse Safe destinations or use destination:ID; no safe or delivery was invented.",
                " لا توجد وجهة متوافقة في هذه الصفحة. تصفح وجهات الخزنة أو استخدم destination:ID؛ لم تُفترض خزنة أو عملية تسليم.") else text("", "")
            if (!browsingSafes && query.cursor != null) invalid()
            return page.copy(records = records + page.records + (if (browsingSafes) destinations?.safes.orEmpty().map(::safeChoiceRecord) else emptyList()),
                forms = forms + page.forms, notice = text(
                    "${page.notice?.english.orEmpty()}${destinationNotice.english} ${readCut(ownReply).english}",
                    "${page.notice?.arabic.orEmpty()}${destinationNotice.arabic} ${readCut(ownReply).arabic}"),
                nextCursor = if (browsingSafes) cursor("cash-safes", destinations?.nextAfter) else null)
        }
        records += cashSessionRecord(session)
        val transferOffset = if (browsingSafes) 0 else offsetCursor(query.cursor, "cash-transfers", session.pendingTransferIds.size)
        val transferIds = if (browsingSafes) emptyList() else session.pendingTransferIds.drop(transferOffset).take(25)
        transferIds.forEach { id -> records += LocalRecord(LocalRecordReference(LocalRecordKind.TRANSFER, id),
            text("Pending safe-transfer receipt", "استلام تحويل خزنة قيد المتابعة")) }
        val destinations = destinationRead ?: if (session.status == "OPEN" && access.allow(LocalAction.CASH_DROP_TO_SAFE, session.id)) safeChoices(access) else null
        if (browsingSafes) records += destinations?.safes.orEmpty().map(::safeChoiceRecord)
        val base = listOf(idField(LocalField.SESSION_ID, session.id), revisionField(LocalField.EXPECTED_REVISION, session.revision))
        when (session.status) {
            "OPEN" -> {
                offer(LocalAction.CASH_BEGIN_CLOSE, base + revisionField(LocalField.EXPECTED_WORK_REVISION, own.workRevision), target = session.id)
                offer(LocalAction.CASH_NON_SALE, base + listOf(newIdField(LocalField.SOURCE_ID), choiceField(LocalField.MODE, listOf(
                    LocalChoice(NonSaleCashKind.NON_SALE_PAY_IN.name, text("Non-sale pay-in", "إيداع غير بيعي")),
                    LocalChoice(NonSaleCashKind.OPERATING_EXPENSE.name, text("Operating expense", "مصروف تشغيلي")))),
                    moneyField(LocalField.AMOUNT), newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT),
                    scalar(LocalField.REASON, LocalInputKind.MULTILINE, maximum = 256)),
                    text("Not customer collection/refund, supplier settlement or a balance override. Retain original evidence and do not repeat physical cash movement.",
                        "ليست تحصيلًا من عميل أو رد مبلغ أو تسوية مورد أو تعديل رصيد. احتفظ بالإثبات الأصلي ولا تكرر الحركة النقدية الفعلية."), target = session.id)
                val choices = destinations?.safes.orEmpty().filter { it.currency == session.expectedCash.currency }.map {
                    LocalReferenceChoice(LocalRecordReference(LocalRecordKind.SAFE, it.id, it.revision), literal(it.label))
                }
                if (choices.isNotEmpty()) offer(LocalAction.CASH_DROP_TO_SAFE, base + listOf(newIdField(LocalField.TRANSFER_ID),
                    scalar(LocalField.RECEIVING_CUSTODY_ID, LocalInputKind.REFERENCE).copy(references = choices),
                    moneyField(LocalField.AMOUNT), newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT)),
                    text("Record this actual, evidenced safe delivery; saving is not an instruction to move money or a recipient acknowledgement. Choices are this authorized page only; use safes or destination:ID for more. The receiving owner must separately record actual receipt. Never repeat the physical movement after an uncertain reply.",
                        "سجل تسليم الخزنة الفعلي المثبت؛ الحفظ ليس أمرًا بنقل النقدية أو تأكيدًا من المستلم. الخيارات هي الصفحة المصرح بها فقط؛ استخدم safes أو destination:ID للمزيد. يسجل صاحب عهدة الاستلام الاستلام الفعلي منفصلًا. لا تكرر الحركة الفعلية بعد رد غير محسوم."), target = session.id)
            }
            else -> unavailable()
        }
        return LocalWorkflowPage(LocalWorkflow.CASH, records, forms, text(
            "Showing ${transferIds.size} of ${session.pendingTransferIds.size} pending transfers. Use transfer:ID for an exact authorized sender/receiver lookup, safe:ID for your safe balance, or safes to browse destination metadata without foreign balances. ${readCut(ownReply).english}",
            "تُعرض ${transferIds.size} من ${session.pendingTransferIds.size} تحويلات قيد المتابعة. استخدم transfer:ID لقراءة محددة مصرح بها للمرسل أو المستلم، وsafe:ID لرصيد خزنتك، أو safes لتصفح بيانات الوجهات دون أرصدة الآخرين. ${readCut(ownReply).arabic}"),
            if (browsingSafes) cursor("cash-safes", destinations?.nextAfter) else if (transferOffset + transferIds.size < session.pendingTransferIds.size)
                "cash-transfers:${transferOffset + transferIds.size}" else null)
    }

    if (browsingSafes) return LocalWorkflowPage(LocalWorkflow.CASH, records + destinationRead?.safes.orEmpty().map(::safeChoiceRecord), forms,
        text("Destination metadata only, not another custodian's balance or permission to send money. Use safe:ID to request your own actual safe ledger.",
            "بيانات الوجهة فقط، لا رصيد أمين عهدة آخر أو إذنًا بإرسال المال. استخدم safe:ID لطلب سجل خزنتك الفعلي."), cursor("cash-safes", destinationRead?.nextAfter))

    val after = parseCursor(query.cursor, "drawers")
    val drawersReply = access.read(LocalCashReadCodec.request(LocalCashQueryV1.Drawers(after)))
    val drawers = LocalCashReadCodec.decode(drawersReply.payload) as? LocalCashReadResultV1.Drawers ?: unavailable()
    val choices = drawers.drawers.filter { !it.assigned && !it.awaitingClosingReceipt }.map {
        LocalReferenceChoice(LocalRecordReference(LocalRecordKind.DRAWER, it.id, it.revision), literal(it.label))
    }
    records += drawers.drawers.filter { query.search.isBlank() || it.label.contains(query.search, ignoreCase = true) || it.id.value == query.search }.map {
        LocalRecord(LocalRecordReference(LocalRecordKind.DRAWER, it.id, it.revision), literal(it.label),
            if (it.assigned || it.awaitingClosingReceipt) text("Unavailable for opening", "غير متاح للفتح") else text("Available at this read cut", "متاح في مراجعة القراءة الحالية"))
    }
    if (choices.isNotEmpty()) offer(LocalAction.CASH_OPEN, listOf(newIdField(LocalField.SESSION_ID),
        scalar(LocalField.DRAWER_ID, LocalInputKind.REFERENCE).copy(references = choices),
        revisionField(LocalField.EXPECTED_CUSTODIAN_REVISION, own.assignmentRevision), scalar(LocalField.BUSINESS_DATE, LocalInputKind.DATE, maximum = 10),
        newIdField(LocalField.SOURCE_ID), moneyField(LocalField.OPENING_FLOAT), newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT)),
        text("Select from this authorized drawer page. The date and counted opening money are explicit facts, not device-clock or balance guesses.",
            "اختر من صفحة الأدراج المصرح بها. التاريخ والنقدية الافتتاحية المعدودة بيانات صريحة، وليست تخمينًا من ساعة الجهاز أو الرصيد."))
    return LocalWorkflowPage(LocalWorkflow.CASH, records, forms, readCut(drawersReply), cursor("drawers", drawers.nextAfter))
}

internal suspend fun prepareCash(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.lines.isNotEmpty() || intent.lineField != null) invalid(LocalField.LINES)
    val input = InputValues(intent.values)
    val request: LocalCashRequestV1 = when (intent.action) {
        LocalAction.DRAWER_CONFIGURE -> LocalCashRequestV1.ConfigureDrawer(input.id(LocalField.DRAWER_ID), input.text(LocalField.LABEL), Currency.EGP)
        LocalAction.CUSTODIAN_CONFIGURE -> LocalCashRequestV1.ConfigureCustodian(input.id(LocalField.CUSTODIAN_ID))
        LocalAction.SAFE_CONFIGURE -> LocalCashRequestV1.ConfigureSafe(input.id(LocalField.CUSTODY_ID), input.id(LocalField.CUSTODIAN_ID), input.text(LocalField.LABEL), Currency.EGP)
        LocalAction.CASH_OPEN -> {
            val drawer = input.reference(LocalField.DRAWER_ID, LocalRecordKind.DRAWER)
            LocalCashRequestV1.Open(input.id(LocalField.SESSION_ID), drawer.id, drawer.revision ?: invalid(LocalField.DRAWER_ID),
                input.revision(LocalField.EXPECTED_CUSTODIAN_REVISION), input.date(LocalField.BUSINESS_DATE), input.id(LocalField.SOURCE_ID),
                input.money(LocalField.OPENING_FLOAT), input.cashEvidence())
        }
        LocalAction.CASH_BEGIN_CLOSE -> LocalCashRequestV1.BeginClosing(input.id(LocalField.SESSION_ID), input.revision(LocalField.EXPECTED_REVISION),
            input.revision(LocalField.EXPECTED_WORK_REVISION))
        LocalAction.CASH_CANCEL_CLOSE -> LocalCashRequestV1.CancelClosing(input.id(LocalField.SESSION_ID), input.revision(LocalField.EXPECTED_REVISION))
        LocalAction.CASH_RECORD_COUNT -> LocalCashRequestV1.RecordClosingCount(input.id(LocalField.SESSION_ID), input.revision(LocalField.EXPECTED_REVISION),
            input.optionalId(LocalField.PREVIOUS_CASH_COUNT_ID), input.money(LocalField.COUNTED_CASH), input.cashEvidence(), input.optionalText(LocalField.REASON))
        LocalAction.CASH_DELIVER_COUNTED -> {
            val safe = input.reference(LocalField.RECEIVING_CUSTODY_ID, LocalRecordKind.SAFE)
            LocalCashRequestV1.DeliverCountedClose(input.id(LocalField.SESSION_ID), input.revision(LocalField.EXPECTED_REVISION),
                input.id(LocalField.CASH_COUNT_ID), input.money(LocalField.COUNTED_CASH), input.money(LocalField.RETAINED_FLOAT),
                input.id(LocalField.TRANSFER_ID), safe.id, safe.revision ?: invalid(LocalField.RECEIVING_CUSTODY_ID),
                LocalCashEvidenceV1(input.id(LocalField.DELIVERY_EVIDENCE_ID), input.instant(LocalField.DELIVERY_OCCURRED_AT)))
        }
        LocalAction.CASH_SUBMIT_COUNTED -> LocalCashRequestV1.SubmitCountedClose(input.id(LocalField.SESSION_ID), input.revision(LocalField.EXPECTED_REVISION),
            input.id(LocalField.CASH_COUNT_ID), input.money(LocalField.COUNTED_CASH), input.money(LocalField.RETAINED_FLOAT),
            input.optionalRevision(LocalField.EXPECTED_TRANSFER_REVISION), input.optionalText(LocalField.VARIANCE_REASON))
        LocalAction.CASH_SUBMIT_CLOSE -> {
            val sessionId = input.id(LocalField.SESSION_ID)
            val revision = input.revision(LocalField.EXPECTED_REVISION)
            val counted = input.money(LocalField.COUNTED_CASH)
            val retained = input.money(LocalField.RETAINED_FLOAT)
            val evidence = input.cashEvidence()
            val transferId = input.optionalId(LocalField.TRANSFER_ID)
            // v1 saved scalar preconditions retain their meaning. v2 uses a selected safe
            // reference with its actual domain revision, never a guessed zero.
            val safe = if (intent.formVersion == 2) input.optionalReference(LocalField.RECEIVING_CUSTODY_ID, LocalRecordKind.SAFE) else null
            val safeId = if (intent.formVersion == 2) safe?.id else input.optionalId(LocalField.RECEIVING_CUSTODY_ID)
            val safeRevision = if (intent.formVersion == 2) safe?.let { it.revision ?: invalid(LocalField.RECEIVING_CUSTODY_ID) }
                else input.optionalRevision(LocalField.EXPECTED_SAFE_REVISION)
            val deliveryId = input.optionalId(LocalField.DELIVERY_EVIDENCE_ID)
            val deliveryAt = input.optionalInstant(LocalField.DELIVERY_OCCURRED_AT)
            if (counted.minor < 0L || retained.minor < 0L || retained.minor > counted.minor) invalid(LocalField.RETAINED_FLOAT)
            val delivery = listOf(transferId, safeId, safeRevision, deliveryId, deliveryAt)
            if (counted == retained && delivery.any { it != null } || counted != retained && delivery.any { it == null })
                invalid(LocalField.RECEIVING_CUSTODY_ID)
            LocalCashRequestV1.SubmitClose(sessionId, revision, counted, evidence, retained, transferId, safeId, safeRevision,
                if (deliveryId != null && deliveryAt != null) LocalCashEvidenceV1(deliveryId, deliveryAt) else null, input.optionalText(LocalField.VARIANCE_REASON))
        }
        LocalAction.CASH_REVIEW_CLOSE -> LocalCashRequestV1.Review(input.id(LocalField.SESSION_ID), input.revision(LocalField.EXPECTED_REVISION),
            input.cashEvidence(), input.text(LocalField.REASON))
        LocalAction.CASH_NON_SALE -> {
            val session = input.id(LocalField.SESSION_ID)
            val revision = input.revision(LocalField.EXPECTED_REVISION)
            val source = input.id(LocalField.SOURCE_ID)
            val kind = when (input.choice(LocalField.MODE)) {
                NonSaleCashKind.NON_SALE_PAY_IN.name -> NonSaleCashKind.NON_SALE_PAY_IN
                NonSaleCashKind.OPERATING_EXPENSE.name -> NonSaleCashKind.OPERATING_EXPENSE
                else -> invalid(LocalField.MODE) // No inventory settlement / paid sale under this label.
            }
            LocalCashRequestV1.NonSale(session, revision, source, kind, input.money(LocalField.AMOUNT), input.cashEvidence(), input.text(LocalField.REASON))
        }
        LocalAction.CASH_DROP_TO_SAFE -> {
            val safe = input.reference(LocalField.RECEIVING_CUSTODY_ID, LocalRecordKind.SAFE)
            LocalCashRequestV1.DropToSafe(input.id(LocalField.SESSION_ID), input.revision(LocalField.EXPECTED_REVISION),
                input.id(LocalField.TRANSFER_ID), safe.id, safe.revision ?: invalid(LocalField.RECEIVING_CUSTODY_ID), input.money(LocalField.AMOUNT), input.cashEvidence())
        }
        LocalAction.CASH_RECEIVE_SAFE -> LocalCashRequestV1.ReceiveSafe(input.id(LocalField.TRANSFER_ID), input.revision(LocalField.EXPECTED_REVISION),
            input.revision(LocalField.EXPECTED_SOURCE_REVISION), input.revision(LocalField.EXPECTED_SAFE_REVISION), input.cashEvidence())
        else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    input.finish()
    access.requireAction(intent.action, cashRequestTarget(request))
    return MutationRequest(intent.operationId, LocalSalesStoreProfile.CASH_COMMAND, LocalCashCommandCodec.encode(request))
}

internal fun InputValues.cashEvidence(): LocalCashEvidenceV1 = LocalCashEvidenceV1(id(LocalField.EVIDENCE_ID), instant(LocalField.OCCURRED_AT))

internal fun cashSessionRecord(session: LocalCashSessionViewV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.CASH_SESSION, session.id, session.revision), text("Your cash session", "جلستك النقدية"), literal(session.status), buildList {
        add(recordValue(fieldLabel(LocalField.DRAWER_ID), literal(session.drawerId.value)))
        add(recordValue(fieldLabel(LocalField.BUSINESS_DATE), literal(session.businessDate.canonicalString())))
        add(recordValue(text("Expected cash", "النقدية المتوقعة"), moneyText(session.expectedCash)))
        add(recordValue(text("Reserved cash out", "النقدية المحجوزة للصرف"), moneyText(session.reservedCashOut)))
        add(recordValue(text("Opening cash", "النقدية الافتتاحية"), moneyText(session.opening)))
        add(recordValue(text("Customer cash received", "النقدية المستلمة من العملاء"), moneyText(session.received)))
        add(recordValue(text("Change delivered", "الباقي المدفوع"), moneyText(session.change)))
        add(recordValue(text("Cash refunds", "المبالغ المستردة نقدًا"), moneyText(session.refunds)))
        add(recordValue(text("Non-sale pay-ins", "الإيداعات غير البيعية"), moneyText(session.payIns)))
        add(recordValue(text("Operating pay-outs", "المصروفات التشغيلية"), moneyText(session.payOuts)))
        add(recordValue(text("Safe transfers sent", "التحويلات المرسلة للخزنة"), moneyText(session.safeDrops)))
        add(recordValue(text("External collections", "التحصيلات الخارجية"), moneyText(session.externalCollections)))
        add(recordValue(text("External refunds", "الاستردادات الخارجية"), moneyText(session.externalRefunds)))
        session.counted?.let { add(recordValue(fieldLabel(LocalField.COUNTED_CASH), moneyText(it))) }
        session.variance?.let { add(recordValue(text("Recorded variance", "الفرق المسجل"), moneyText(it))) }
        session.retainedFloat?.let { add(recordValue(fieldLabel(LocalField.RETAINED_FLOAT), moneyText(it))) }
        session.actualSafeDelivery?.let { add(recordValue(text("Actual safe delivery", "التسليم الفعلي للخزنة"), moneyText(it))) }
        add(recordValue(text("Unresolved attempts", "المحاولات غير المحسومة"), literal(session.pendingAttemptIds.size.toString())))
        add(recordValue(text("Pending safe-transfer receipts", "استلامات تحويل الخزنة قيد المتابعة"), literal(session.pendingTransferIds.size.toString())))
    })

private suspend fun safeChoices(access: GatewayAccess, id: DomainId? = null, after: DomainId? = null): LocalCashReadResultV1.SafeChoices? {
    val result = LocalCashReadCodec.decode(access.read(LocalCashReadCodec.request(if (id == null) LocalCashQueryV1.SafeChoices(after)
        else LocalCashQueryV1.SafeChoice(id))).payload)
    if (result == LocalCashReadResultV1.NotAvailable) return null
    val choices = result as? LocalCashReadResultV1.SafeChoices ?: unavailable()
    if (id != null && (choices.safes.singleOrNull()?.id != id || choices.nextAfter != null)) unavailable()
    return choices
}

private fun safeChoiceRecord(value: LocalSafeChoiceV1): LocalRecord = LocalRecord(LocalRecordReference(LocalRecordKind.SAFE, value.id, value.revision),
    literal(value.label), text("Destination metadata only", "بيانات الوجهة فقط"), listOf(recordValue(fieldLabel(LocalField.CURRENCY), literal(value.currency.name))))

private suspend fun cashSafeWorkflow(access: GatewayAccess, id: DomainId): LocalWorkflowPage {
    val read = access.read(LocalCashReadCodec.request(LocalCashQueryV1.Safe(id)))
    val result = LocalCashReadCodec.decode(read.payload)
    if (result == LocalCashReadResultV1.NotAvailable) return LocalWorkflowPage(LocalWorkflow.CASH, emptyList(), emptyList(),
        text("No safe ledger is available to this actor for that ID. No foreign balance is disclosed or treated as zero.",
            "لا يوجد سجل خزنة متاح لهذا المستخدم لهذا المعرف. لا يُكشف رصيد الآخرين أو يُعامل كصفر."))
    val safe = result as? LocalCashReadResultV1.Safe ?: unavailable()
    if (safe.custodyId != id || safe.custodianId != access.view.actorId) unavailable()
    return LocalWorkflowPage(LocalWorkflow.CASH, listOf(LocalRecord(LocalRecordReference(LocalRecordKind.SAFE, id, safe.revision), literal(safe.label),
        values = listOf(recordValue(text("Recorded received", "المستلم المسجل"), moneyText(safe.received))), selectable = false)), emptyList(), readCut(read))
}

private suspend fun cashTransferWorkflow(access: GatewayAccess, id: DomainId): LocalWorkflowPage {
    val read = access.read(LocalCashReadCodec.request(LocalCashQueryV1.Transfer(id)))
    val result = LocalCashReadCodec.decode(read.payload)
    if (result == LocalCashReadResultV1.NotAvailable) return LocalWorkflowPage(LocalWorkflow.CASH, emptyList(), emptyList(),
        text("No transfer is available to this actor for that ID. It was not acknowledged or inferred absent.",
            "لا يوجد تحويل متاح لهذا المستخدم لهذا المعرف. لم يُؤكد استلامه أو يُستنتج غيابه."))
    val transfer = result as? LocalCashReadResultV1.Transfer ?: unavailable()
    if (transfer.transferId != id) unavailable()
    val record = LocalRecord(LocalRecordReference(LocalRecordKind.TRANSFER, id, transfer.revision), text("Safe transfer", "تحويل الخزنة"), literal(transfer.status), buildList {
        add(recordValue(fieldLabel(LocalField.AMOUNT), moneyText(transfer.amount)))
        add(recordValue(fieldLabel(LocalField.RECEIVING_CUSTODY_ID), literal(transfer.receivingCustodyId.value)))
        add(recordValue(text("Original sender session", "جلسة المرسل الأصلية"), literal(transfer.senderSessionId.value)))
        add(recordValue(text("Sent at", "أُرسل في"), literal(transfer.sentAt.canonicalString())))
        transfer.receivedAt?.let { add(recordValue(text("Received at", "استُلم في"), literal(it.canonicalString()))) }
    })
    val forms = mutableListOf<LocalActionForm>()
    if (transfer.status == "AWAITING_RECEIPT" && access.installed.has(LocalSalesStoreProfile.CASH_COMMAND) && access.allow(LocalAction.CASH_RECEIVE_SAFE, id)) {
        val safe = LocalCashReadCodec.decode(access.read(LocalCashReadCodec.request(LocalCashQueryV1.Safe(transfer.receivingCustodyId))).payload)
        if (safe is LocalCashReadResultV1.Safe) {
            if (safe.custodyId != transfer.receivingCustodyId || safe.custodianId != access.view.actorId) unavailable()
            forms += form(LocalAction.CASH_RECEIVE_SAFE, listOf(idField(LocalField.TRANSFER_ID, id),
                revisionField(LocalField.EXPECTED_REVISION, transfer.revision), revisionField(LocalField.EXPECTED_SOURCE_REVISION, transfer.senderSessionRevision),
                revisionField(LocalField.EXPECTED_SAFE_REVISION, safe.revision), newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT)),
                text("Acknowledge actual receipt of this original transfer in your own safe. The original sender-session revision comes from the protected transfer read, not your current session. No new cash movement is instructed.",
                    "أكد الاستلام الفعلي لهذا التحويل الأصلي في خزنتك. تأتي مراجعة جلسة المرسل الأصلية من قراءة التحويل المحمية، لا من جلستك الحالية. لا يُطلب نقل نقدية جديد."))
        } else if (safe != LocalCashReadResultV1.NotAvailable) unavailable()
    }
    return LocalWorkflowPage(LocalWorkflow.CASH, listOf(record), forms, readCut(read))
}

internal fun parseCursor(value: String?, family: String): DomainId? = value?.let {
    if (!it.startsWith("$family:")) invalid()
    try { DomainId(it.removePrefix("$family:")) } catch (_: IllegalArgumentException) { invalid() }
}
internal fun cursor(family: String, after: DomainId?): String? = after?.let { "$family:${it.value}" }
