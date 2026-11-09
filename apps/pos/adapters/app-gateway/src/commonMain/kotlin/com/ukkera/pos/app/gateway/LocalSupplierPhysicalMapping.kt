package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.LocalSupplierCashViewV1
import com.ukkera.pos.catalog.store.LocalSupplierSettlementSourceV1
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

/** Distinct supplier economics, sharing only the genuine one-shot native lifetime. */
internal val supplierPhysicalActions: Set<LocalAction> = setOf(LocalAction.SUPPLIER_CASH_START,
    LocalAction.SUPPLIER_CASH_APPLY, LocalAction.SUPPLIER_CASH_RESOLUTION, LocalAction.SUPPLIER_CASH_RESOLUTION_APPLY)

internal fun supplierPhysicalRequestAction(request: MutationRequest): LocalAction? {
    if (request.command != LocalSupplierCashProfile.COMMAND || request.actionApprovalId != null) return null
    return when (LocalSupplierCashCodec.decode(request.payload)) {
        is LocalSupplierCashRequest.StartCash -> LocalAction.SUPPLIER_CASH_START
        is LocalSupplierCashRequest.ApplyOriginal -> LocalAction.SUPPLIER_CASH_APPLY
        is LocalSupplierCashRequest.RecordResolution -> LocalAction.SUPPLIER_CASH_RESOLUTION
        is LocalSupplierCashRequest.ApplyResolution -> LocalAction.SUPPLIER_CASH_RESOLUTION_APPLY
        is LocalSupplierCashRequest.RecordOriginal -> null // Native capture only, never a generic form.
    }
}

internal fun supplierPhysicalIntent(owner: LocalClientIdentity, request: MutationRequest,
    label: LocalText, summary: List<LocalRecordValue>): StoredClientIntentV1 {
    val action = supplierPhysicalRequestAction(request) ?: invalid()
    require(LocalSupplierCashCodec.normalize(request.payload) == request.payload)
    val digest = semanticDigest(owner.scope, owner.actorId, request.command, request.payload)
    return StoredClientIntentV1.staged(owner, physicalUiIntent(request.operationId, action, digest), request, label, summary)
}

internal fun requireSupplierEntry(entry: StoredClientIntentV1) {
    entry.validate(entry.owner)
    require(entry.localAction in supplierPhysicalActions && entry.formVersion == 2 &&
        supplierPhysicalRequestAction(entry.request()) == entry.localAction &&
        entry.inputDigest == inputDigest(physicalUiIntent(entry.operationId, entry.localAction, entry.semanticSha256)))
    when (val body = LocalSupplierCashCodec.decode(entry.request().payload)) {
        is LocalSupplierCashRequest.StartCash -> require(entry.operationId !in setOf(body.recordingOperationId, body.resolutionOperationId))
        is LocalSupplierCashRequest.ApplyOriginal -> require(entry.operationId != body.recordingOperationId)
        is LocalSupplierCashRequest.ApplyResolution -> require(entry.operationId != body.resolutionOperationId)
        is LocalSupplierCashRequest.RecordResolution -> require(entry.operationId == body.target.resolutionOperationId &&
            body.resolverActorId == entry.owner.actorId && body.outcome !is LocalSupplierCashOutcome.Unknown)
        is LocalSupplierCashRequest.RecordOriginal -> unavailable()
    }
}

internal fun supplierDisplay(target: LocalSupplierCashTarget, amount: Money): LocalRetailInstructionDisplay =
    LocalRetailInstructionDisplay(target.intentOperationId, target.recordingOperationId, target.resolutionOperationId,
        LocalRetailPhysicalKind.SUPPLIER_CASH_OUT, target.invoiceId ?: target.supplierId, target.sessionId,
        amount, "CASH", false, false, LocalPaymentSubject.SUPPLIER)

/** Review of the exact fresh source/custody cut saved into this one Start, never authority. */
internal fun supplierCashStartSummary(value: LocalSupplierCashRequest.StartCash,
    observed: LocalSupplierSettlementSourceV1, operationId: OperationId): List<LocalRecordValue> = buildList {
    require(observed.source == value.source && observed.query.sessionId == value.sessionId &&
        observed.sessionRevision == value.expectedSessionRevision && observed.drawerRevision == value.expectedDrawerRevision &&
        observed.custodianRevision == value.expectedCustodianRevision && observed.workRevision == value.expectedWorkRevision &&
        observed.availableCash == value.expectedAvailableCash)
    add(recordValue(text("Supplier", "المورد"), literal(value.source.supplierId.value)))
    add(recordValue(text("Allocation", "التخصيص"), (value.source as? LocalSupplierCashSource.Invoice)?.invoiceId?.let { literal(it.value) }
        ?: text("Unapplied advance", "دفعة مقدمة غير مخصصة")))
    add(recordValue(text("Today's actual new payout", "الصرف الجديد الفعلي اليوم"), moneyText(value.amount)))
    add(recordValue(text("Original paying cash session", "جلسة النقدية الدافعة الأصلية"), literal(value.sessionId.value)))
    val supplierRevision = when (val source = value.source) {
        is LocalSupplierCashSource.Invoice -> source.expectedSupplierRevision
        is LocalSupplierCashSource.Advance -> source.expectedSupplierRevision
    }
    add(recordValue(fieldLabel(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION), literal(supplierRevision.value.toString())))
    (value.source as? LocalSupplierCashSource.Invoice)?.let { invoice ->
        add(recordValue(fieldLabel(LocalField.EXPECTED_STORAGE_REVISION), literal(invoice.expectedInvoiceRevision.value.toString())))
        add(recordValue(fieldLabel(LocalField.EXPECTED_PROGRESS_REVISION), literal(invoice.expectedProgressRevision.value.toString())))
        add(recordValue(fieldLabel(LocalField.EXPECTED_PAYABLE_REVISION), literal(invoice.expectedPayableRevision.value.toString())))
        add(recordValue(fieldLabel(LocalField.EXPECTED_CREDIT_REVISION), literal(invoice.expectedCreditRevision?.value?.toString() ?: "ABSENT")))
        add(recordValue(fieldLabel(LocalField.EXPECTED_SETTLEMENT_REVISION), literal(invoice.expectedSettlementRevision?.value?.toString() ?: "ABSENT")))
        add(recordValue(text("Actual previously allocated payment", "الدفع السابق المخصص الفعلي"), moneyText(checkNotNull(observed.allocatedPayment))))
        add(recordValue(text("Actual applied agreed credit", "الخصم المتفق عليه المطبق الفعلي"), moneyText(checkNotNull(observed.appliedCredit))))
        add(recordValue(text("Recognized due before today's payout", "المستحق المعترف به قبل صرف اليوم"), moneyText(invoice.expectedRemainingDue)))
        add(recordValue(text("Reserved supplier payout — NOT payment", "صرف المورد المحجوز — ليس دفعًا"), moneyText(invoice.expectedReservedCash)))
        add(recordValue(text("Actually available to allocate", "المتاح الفعلي للتخصيص"), moneyText(checkNotNull(observed.availableToAllocate))))
    }
    add(recordValue(text("Cash session STORAGE revision", "إصدار تخزين جلسة النقدية"), literal(value.expectedSessionRevision.value.toString())))
    add(recordValue(text("Drawer STORAGE revision", "إصدار تخزين الدرج"), literal(value.expectedDrawerRevision.value.toString())))
    add(recordValue(text("Custodian STORAGE revision", "إصدار تخزين موظف العهدة"), literal(value.expectedCustodianRevision.value.toString())))
    add(recordValue(text("Work STORAGE revision", "إصدار تخزين العمل"), literal(value.expectedWorkRevision.value.toString())))
    add(recordValue(text("Actually available own cash", "النقدية الخاصة المتاحة فعليًا"), moneyText(value.expectedAvailableCash)))
    add(recordValue(text("Designated posting branch", "فرع الترحيل المعين"), literal(checkNotNull(observed.designatedPostingBranch).value)))
    add(recordValue(text("Original Start ID", "معرف البدء الأصلي"), literal(operationId.value)))
    add(recordValue(text("Original recording ID", "معرف الإفادة الأصلي"), literal(value.recordingOperationId.value)))
    add(recordValue(text("Original fixed resolution ID", "معرف التسوية الأصلي الثابت"), literal(value.resolutionOperationId.value)))
    add(recordValue(text("Settlement ID", "معرف دفع المورد"), literal(value.settlementId.value)))
    add(recordValue(text("Invoice allocation ID", "معرف تخصيص الفاتورة"), literal(value.allocationId?.value ?: "ABSENT")))
    add(recordValue(text("Original attempt ID", "معرف المحاولة الأصلي"), literal(value.attemptId.value)))
    add(recordValue(text("Original master recording allocation", "تخصيص التسجيل الأصلي لدى الرئيسي"), literal(value.masterAllocationId.value)))
    add(recordValue(text("Original client recording allocation", "تخصيص التسجيل الأصلي لدى العميل"), literal(value.clientAllocationId.value)))
    add(recordValue(fieldLabel(LocalField.REASON), literal(value.reasonCode)))
    add(recordValue(text("Prior funds do not increase this outflow", "الأموال السابقة لا تزيد هذا الصرف"), text(
        "Gross PAYABLE recognition is unchanged. Prior advances require a separate nonphysical allocation. Save then Start once; pay only on the original live native instruction.",
        "يبقى الاعتراف الإجمالي بالمستحق دون تغيير. تتطلب الدفعات السابقة تخصيصًا منفصلًا غير فعلي. احفظ ثم ابدأ مرة واحدة؛ وادفع فقط وفق التعليمات الأصلية السارية.")))
}

/** BOTH saved selectors and recorded Start must match the WHOLE real native supplier requirement. */
internal fun requireSupplierProjection(entry: StoredClientIntentV1, value: LocalSupplierCashPhysicalProjection,
    started: LocalSupplierCashResult.AttemptStarted) {
    requireSupplierEntry(entry)
    val body = LocalSupplierCashCodec.decode(entry.request().payload) as? LocalSupplierCashRequest.StartCash ?: unavailable()
    val requirement = value.requirement
    val domain = value.domain
    require(entry.localAction == LocalAction.SUPPLIER_CASH_START && domain.target == body.target(entry.operationId) &&
        domain.target == started.target && domain.source == body.source && domain.amount == body.amount && domain.amount == started.amount &&
        domain.sessionRevision == body.expectedSessionRevision && domain.drawerRevision == body.expectedDrawerRevision &&
        domain.custodianRevision == body.expectedCustodianRevision && domain.workRevision == body.expectedWorkRevision && domain.reasonCode == body.reasonCode &&
        sha256(requirement.canonicalBytes.copyBytes()) == started.requirementDigest &&
        requirement.scope == entry.owner.scope && requirement.originalActorId == entry.owner.actorId &&
        requirement.clientDeviceId == entry.owner.deviceId && requirement.intentOperationId == entry.operationId &&
        requirement.intentSemanticDigest == entry.semanticSha256 && requirement.command == entry.request().command &&
        requirement.domainProfile == LocalSupplierCashProfile.DOMAIN && requirement.masterAllocationId == body.masterAllocationId &&
        requirement.clientAllocationId == body.clientAllocationId)
}

internal fun requireSupplierRead(value: LocalSupplierCashViewV1, owner: LocalClientIdentity): LocalSupplierCashPhysicalProjection {
    require(value.unavailableCode == null)
    val projection = LocalSupplierCashPhysicalProjection.parse(value.requirementBytes())
    val requirement = projection.requirement
    val target = checkNotNull(value.target)
    require(requirement.scope == owner.scope && requirement.originalActorId == owner.actorId &&
        requirement.clientDeviceId == owner.deviceId && target == projection.domain.target && value.amount == projection.domain.amount)
    val original = value.originalBody?.let { body ->
        val bytes = StoredBytes(body.encodeToByteArray(throwOnInvalidSequence = true))
        require(LocalSupplierCashCodec.normalize(bytes) == bytes)
        val request = LocalSupplierCashCodec.decode(bytes) as? LocalSupplierCashRequest.RecordOriginal ?: unavailable()
        require(request.target == target)
        bytes
    }
    val resolution = value.resolutionBody?.let { body ->
        val bytes = StoredBytes(body.encodeToByteArray(throwOnInvalidSequence = true))
        LocalSupplierCashResolutionDescriptor.create(requirement, checkNotNull(original), owner.actorId, bytes)
        bytes
    }
    fun application(result: LocalSupplierCashResult?, phase: LocalSupplierCashPhase, body: StoredBytes?) {
        if (result == null) return
        require(body != null)
        val applied = result as? LocalSupplierCashResult.Applied ?: unavailable()
        val outcome = when (val request = LocalSupplierCashCodec.decode(body)) {
            is LocalSupplierCashRequest.RecordOriginal -> request.outcome
            is LocalSupplierCashRequest.RecordResolution -> request.outcome
            else -> unavailable()
        }
        val expected = when (outcome) {
            is LocalSupplierCashOutcome.Unknown -> LocalSupplierCashAction.UNKNOWN
            is LocalSupplierCashOutcome.NotPaid -> LocalSupplierCashAction.NOT_PAID
            is LocalSupplierCashOutcome.CashPaid -> {
                require(outcome.supportedAmountOrNull() == projection.domain.amount); LocalSupplierCashAction.CASH_PAID
            }
            is LocalSupplierCashOutcome.ExternalAttested -> unavailable()
        }
        require(applied.target == target && applied.phase == phase && applied.action == expected &&
            applied.applicationOperationId !in setOf(target.intentOperationId, target.recordingOperationId, target.resolutionOperationId) &&
            applied.amount == (if (expected == LocalSupplierCashAction.CASH_PAID) projection.domain.amount else null))
        requireSupplierApplicationValues(applied)
    }
    application(value.originalApplied, LocalSupplierCashPhase.ORIGINAL, original)
    application(value.resolutionApplied, LocalSupplierCashPhase.RESOLUTION, resolution)
    if (resolution != null) require((value.originalApplied as? LocalSupplierCashResult.Applied)?.action == LocalSupplierCashAction.UNKNOWN)
    val state = when {
        value.resolutionApplied != null -> "RESOLUTION_APPLIED"
        resolution != null -> "RESOLUTION_AWAITING_APPLICATION"
        value.originalApplied != null -> (value.originalApplied as LocalSupplierCashResult.Applied).action.name
        original != null -> "RAW_AWAITING_APPLICATION"
        else -> "AWAITING_ATTESTATION"
    }
    require(value.status == state)
    return projection
}

internal fun supplierFactProgress(value: LocalSupplierCashViewV1, owner: LocalClientIdentity,
    operationId: OperationId = value.recordingOperationId): LocalRetailPhysicalProgress {
    val projection = requireSupplierRead(value, owner)
    val target = projection.domain.target
    val resolution = value.resolutionBody != null
    val body = if (resolution) checkNotNull(value.resolutionBody) else value.originalBody
    val applied = (if (resolution) value.resolutionApplied else value.originalApplied) as? LocalSupplierCashResult.Applied
    val outcome = body?.let { text -> when (val request = LocalSupplierCashCodec.decode(StoredBytes(text.encodeToByteArray()))) {
        is LocalSupplierCashRequest.RecordOriginal -> request.outcome
        is LocalSupplierCashRequest.RecordResolution -> request.outcome
        else -> unavailable()
    } }
    val unknown = outcome is LocalSupplierCashOutcome.Unknown || applied?.action == LocalSupplierCashAction.UNKNOWN
    val phase = if (resolution) LocalRetailObservationPhase.RESOLUTION else LocalRetailObservationPhase.ORIGINAL
    val state = when {
        body == null -> LocalRetailDurableState.START_RECORDED_NO_INSTRUCTION
        applied == null -> if (resolution) LocalRetailDurableState.SQL_RESOLUTION_PENDING else LocalRetailDurableState.SQL_ORIGINAL_PENDING
        unknown -> LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED
        applied.action == LocalSupplierCashAction.NOT_PAID -> LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY
        else -> LocalRetailDurableState.FINANCIALLY_APPLIED
    }
    return LocalRetailPhysicalProgress(operationId, state, supplierProgressMessage(state), target.factOperation(
        if (resolution) LocalSupplierCashPhase.RESOLUTION else LocalSupplierCashPhase.ORIGINAL), target.resolutionOperationId,
        phase, unknown, reviewDisplay = supplierDisplay(target, projection.domain.amount), originalApplied = value.originalApplied != null,
        resolutionRecorded = resolution, originalActorId = owner.actorId, recordingActorId = owner.actorId,
        reviewValues = outcome?.let { supplierObservationSummary(it.supplierUiObservation()) }.orEmpty() + applied?.let(::supplierApplicationSummary).orEmpty(),
        originalRecordingOperationId = target.recordingOperationId, subject = LocalPaymentSubject.SUPPLIER,
        financialKind = LocalRetailFinancialKind.SUPPLIER_CASH)
}

private fun supplierApplicationSummary(value: LocalSupplierCashResult.Applied): List<LocalRecordValue> = buildList {
    requireSupplierApplicationValues(value)
    add(recordValue(text("Actual applied financial phase", "المرحلة المالية المطبقة الفعلية"), literal("${value.phase.name} — ${value.action.name}")))
    add(recordValue(text("Actual application operation", "عملية التطبيق الفعلية"), literal(value.applicationOperationId.value)))
    add(recordValue(text("Original supplier payment/advance source", "مصدر دفع المورد أو الدفعة المقدمة الأصلي"), literal(value.target.settlementId.value)))
    value.amount?.let { add(recordValue(text("This original payout posted exactly once — not earlier advances", "رُحل هذا الصرف الأصلي مرة واحدة — دون دفعات سابقة"), moneyText(it))) }
        ?: add(recordValue(text("No physical money movement posted by this application", "لم يُرحل هذا التطبيق حركة مالية فعلية"), literal(value.action.name)))
    value.remainingDue?.let { add(recordValue(text("Invoice due at this applied cut — gross recognition unchanged", "مستحق الفاتورة عند هذا التطبيق — الاعتراف الإجمالي دون تغيير"), moneyText(it))) }
    value.unappliedAdvance?.let { add(recordValue(text("Advance unapplied at this payout cut — later allocation is separate", "الدفعة غير المخصصة عند هذا الصرف — التخصيص اللاحق منفصل"), moneyText(it))) }
}

private fun requireSupplierApplicationValues(value: LocalSupplierCashResult.Applied) {
    val amount = value.amount
    val remaining = value.remainingDue
    val unapplied = value.unappliedAdvance
    require((amount != null) == (value.action == LocalSupplierCashAction.CASH_PAID) &&
        (amount == null || amount.currency == Currency.EGP && amount.minor > 0L) &&
        (remaining != null) == (value.target.invoiceId != null) &&
        (remaining == null || remaining.currency == Currency.EGP && remaining.minor >= 0L) &&
        (unapplied == null || unapplied.currency == Currency.EGP && unapplied.minor >= 0L) &&
        (if (value.action == LocalSupplierCashAction.CASH_PAID && value.target.invoiceId == null) unapplied == amount else unapplied == null))
}

private fun requireSupplierRecorded(entry: StoredClientIntentV1, value: LocalSupplierCashResult, committed: Boolean, code: String) {
    requireSupplierEntry(entry)
    val request = LocalSupplierCashCodec.decode(entry.request().payload)
    fun target(value: LocalSupplierCashTarget, phase: LocalSupplierCashPhase) {
        require(phase == when (request) {
            is LocalSupplierCashRequest.ApplyOriginal -> LocalSupplierCashPhase.ORIGINAL
            is LocalSupplierCashRequest.ApplyResolution, is LocalSupplierCashRequest.RecordResolution -> LocalSupplierCashPhase.RESOLUTION
            else -> unavailable()
        })
        require(value.factOperation(phase) == when (request) {
            is LocalSupplierCashRequest.ApplyOriginal -> request.recordingOperationId
            is LocalSupplierCashRequest.ApplyResolution -> request.resolutionOperationId
            is LocalSupplierCashRequest.RecordResolution -> request.target.resolutionOperationId
            else -> unavailable()
        })
        if (request is LocalSupplierCashRequest.RecordResolution) require(value == request.target)
    }
    when (value) {
        is LocalSupplierCashResult.AttemptStarted -> {
            val start = request as? LocalSupplierCashRequest.StartCash ?: unavailable()
            require(committed && value.target == start.target(entry.operationId) && value.amount == start.amount)
        }
        is LocalSupplierCashResult.RawRetained -> {
            require(committed && request is LocalSupplierCashRequest.RecordResolution && value.phase == LocalSupplierCashPhase.RESOLUTION)
            target(value.target, value.phase)
        }
        is LocalSupplierCashResult.Applied -> {
            require(committed && (request is LocalSupplierCashRequest.ApplyOriginal || request is LocalSupplierCashRequest.ApplyResolution) &&
                value.phase == (if (request is LocalSupplierCashRequest.ApplyOriginal) LocalSupplierCashPhase.ORIGINAL else LocalSupplierCashPhase.RESOLUTION) &&
                value.applicationOperationId == entry.operationId && value.applicationOperationId !in setOf(value.target.intentOperationId,
                    value.target.recordingOperationId, value.target.resolutionOperationId))
            requireSupplierApplicationValues(value)
            target(value.target, value.phase)
        }
        is LocalSupplierCashResult.Pending -> {
            require(!committed && code == "purchasing.supplier_cash_pending" &&
                (request is LocalSupplierCashRequest.ApplyOriginal || request is LocalSupplierCashRequest.ApplyResolution))
            target(value.target, value.phase)
        }
        is LocalSupplierCashResult.AlreadyApplied -> {
            require(!committed && code == "purchasing.supplier_cash_already_applied" && value.applicationOperationId != entry.operationId &&
                (request is LocalSupplierCashRequest.ApplyOriginal || request is LocalSupplierCashRequest.ApplyResolution))
            target(value.target, value.phase)
        }
        is LocalSupplierCashResult.Unavailable -> require(!committed && value.code == code)
    }
}

internal fun supplierRecordedProgress(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalRetailPhysicalProgress {
    require(reply.operationId == entry.operationId && reply.originalActorId == entry.owner.actorId)
    val value = LocalSupplierCashCodec.result(reply.result)
    requireSupplierRecorded(entry, value, reply.committed, reply.code)
    val target = when (value) {
        is LocalSupplierCashResult.AttemptStarted -> value.target
        is LocalSupplierCashResult.RawRetained -> value.target
        is LocalSupplierCashResult.Applied -> value.target
        is LocalSupplierCashResult.Pending -> value.target
        is LocalSupplierCashResult.AlreadyApplied -> value.target
        is LocalSupplierCashResult.Unavailable -> null
    }
    val phase = when (value) {
        is LocalSupplierCashResult.RawRetained -> value.phase
        is LocalSupplierCashResult.Applied -> value.phase
        is LocalSupplierCashResult.Pending -> value.phase
        is LocalSupplierCashResult.AlreadyApplied -> value.phase
        else -> LocalSupplierCashPhase.ORIGINAL
    }
    val state = when (value) {
        is LocalSupplierCashResult.AttemptStarted -> LocalRetailDurableState.START_RECORDED_NO_INSTRUCTION
        is LocalSupplierCashResult.RawRetained -> LocalRetailDurableState.SQL_RESOLUTION_PENDING
        is LocalSupplierCashResult.Applied -> when (value.action) {
            LocalSupplierCashAction.CASH_PAID -> LocalRetailDurableState.FINANCIALLY_APPLIED
            LocalSupplierCashAction.NOT_PAID -> LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY
            LocalSupplierCashAction.UNKNOWN -> LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED
        }
        is LocalSupplierCashResult.Pending -> if (phase == LocalSupplierCashPhase.ORIGINAL)
            LocalRetailDurableState.SQL_ORIGINAL_PENDING else LocalRetailDurableState.SQL_RESOLUTION_PENDING
        is LocalSupplierCashResult.AlreadyApplied -> LocalRetailDurableState.OBSERVATION_ALREADY_APPLIED
        is LocalSupplierCashResult.Unavailable -> LocalRetailDurableState.REJECTED
    }
    val amount = (value as? LocalSupplierCashResult.AttemptStarted)?.amount ?: (value as? LocalSupplierCashResult.Applied)?.amount
    return LocalRetailPhysicalProgress(entry.operationId, state,
        if (value is LocalSupplierCashResult.Unavailable) text("Supplier cash command durably refused: ${value.code}. Preserve existing payout IDs/evidence.",
            "رُفض أمر نقدية المورد بشكل دائم: ${value.code}. احتفظ بمعرفات الدفع وأدلته الموجودة.") else supplierProgressMessage(state),
        target?.factOperation(phase), target?.resolutionOperationId,
        if (phase == LocalSupplierCashPhase.ORIGINAL) LocalRetailObservationPhase.ORIGINAL else LocalRetailObservationPhase.RESOLUTION,
        originalUnknown = value is LocalSupplierCashResult.Applied && value.action == LocalSupplierCashAction.UNKNOWN,
        reviewDisplay = if (target != null && amount != null) supplierDisplay(target, amount) else null,
        reviewValues = (value as? LocalSupplierCashResult.Applied)?.let(::supplierApplicationSummary).orEmpty(),
        originalRecordingOperationId = target?.recordingOperationId, subject = LocalPaymentSubject.SUPPLIER,
        financialKind = LocalRetailFinancialKind.SUPPLIER_CASH)
}

internal fun supplierRecordedDecision(entry: StoredClientIntentV1, result: StoredKernelResultV1): LocalRecordedDecision {
    require(result.operationId == entry.operationId && result.originalActorId == entry.owner.actorId)
    val value = LocalSupplierCashCodec.result(decodeHex(result.payloadHex, 65_536).useWiped { StoredBytes(it) })
    requireSupplierRecorded(entry, value, result.committed, result.code)
    return when (value) {
        is LocalSupplierCashResult.Applied -> if (value.action == LocalSupplierCashAction.CASH_PAID) LocalRecordedDecision.APPLIED else LocalRecordedDecision.RECORDED_ONLY
        is LocalSupplierCashResult.Unavailable -> LocalRecordedDecision.REJECTED
        else -> LocalRecordedDecision.RECORDED_ONLY
    }
}

internal fun supplierRecordedUi(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded =
    LocalOperationResult.Recorded(entry.operationId, supplierRecordedDecision(entry, StoredKernelResultV1.from(reply)),
        supplierRecordedProgress(entry, reply).message, null, reply.replayed)

private fun supplierProgressMessage(state: LocalRetailDurableState): LocalText = when (state) {
    LocalRetailDurableState.START_RECORDED_NO_INSTRUCTION -> text("Supplier cash Start is durably recorded; this recovery view is NOT a payout instruction. Native/human action may exist. Do not pay or Start again.",
        "سُجل بدء نقدية المورد بشكل دائم؛ عرض الاسترداد ليس تعليمات دفع. قد توجد حركة بشرية أو أدلة أصلية. لا تكرر الدفع أو البدء.")
    LocalRetailDurableState.SQL_ORIGINAL_PENDING, LocalRetailDurableState.SQL_RESOLUTION_PENDING -> text("Immutable supplier observation retained. Financial application/reconciliation is pending; unsupported amounts/tenders are not shortened or accepted. Do not pay again.",
        "حُفظت إفادة المورد الثابتة. التطبيق المالي أو التسوية معلق؛ لا تُختصر المبالغ غير المدعومة ولا تُقبل وسائل دفعها. لا تدفع مجددًا.")
    LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED -> text("UNKNOWN applied without money. Cash and invoice reservations remain; recover the fixed original resolution ID and old-action evidence. Do not pay again.",
        "طُبقت النتيجة المجهولة دون مال. تظل حجوزات النقدية والفاتورة؛ استرد معرف التسوية الثابت ودليل الحركة السابقة. لا تدفع مجددًا.")
    LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY -> text("NOT PAID applied. Reservations released; no physical money movement was posted.",
        "طُبقت إفادة عدم الدفع. أُفرج عن الحجوزات؛ ولم تُرحل حركة مالية فعلية.")
    LocalRetailDurableState.OBSERVATION_ALREADY_APPLIED -> text("This original was already applied; no effects repeated. Read its actual retained state, which may remain UNKNOWN.",
        "طُبق هذا الأصل بالفعل؛ ولم تتكرر الآثار. اقرأ حالته المحفوظة الفعلية فقد تظل مجهولة.")
    else -> text("Supplier cash payout applied exactly once. Invoice allocation or unapplied advance is separate from gross recognition, stock, operating expense and credits.",
        "طُبقت دفعة المورد النقدية مرة واحدة بالضبط. تخصيص الفاتورة أو الدفعة المقدمة غير المخصصة منفصل عن الاعتراف الإجمالي والمخزون والمصروفات والخصومات.")
}

internal fun LocalRetailObservation.supplierCashObservation(): LocalSupplierCashOutcome {
    val fact = LocalCashEvidenceV1(evidence.evidenceId, evidence.occurredAt)
    return when (this) {
        is LocalRetailObservation.Unknown -> LocalSupplierCashOutcome.Unknown(fact)
        is LocalRetailObservation.NotPaid -> LocalSupplierCashOutcome.NotPaid(fact, reason)
        is LocalRetailObservation.CashPaid -> {
            if ((amount == null) == (literalSupplierAmount == null)) invalid()
            LocalSupplierCashOutcome.CashPaid(amount, fact, literalSupplierAmount?.supplierLiteral())
        }
        is LocalRetailObservation.ExternalAttested -> {
            if (terminal != null) invalid() // Supplier contract has no terminal/tender policy authority.
            if ((amount == null) == (literalSupplierAmount == null)) invalid()
            LocalSupplierCashOutcome.ExternalAttested(amount, fact, reference, literalSupplierAmount?.supplierLiteral())
        }
        is LocalRetailObservation.CashReceived -> invalid()
    }
}

internal fun LocalSupplierCashOutcome.supplierUiObservation(): LocalRetailObservation {
    val retainedEvidence = when (this) {
        is LocalSupplierCashOutcome.Unknown -> this.evidence
        is LocalSupplierCashOutcome.NotPaid -> this.evidence
        is LocalSupplierCashOutcome.CashPaid -> this.evidence
        is LocalSupplierCashOutcome.ExternalAttested -> this.evidence
    }.let { LocalRetailEvidence(it.evidenceId, it.occurredAt) }
    return when (this) {
        is LocalSupplierCashOutcome.Unknown -> LocalRetailObservation.Unknown(retainedEvidence)
        is LocalSupplierCashOutcome.NotPaid -> LocalRetailObservation.NotPaid(retainedEvidence, reasonCode)
        is LocalSupplierCashOutcome.CashPaid -> LocalRetailObservation.CashPaid(retainedEvidence, amount, literalAmount?.supplierUiLiteral())
        is LocalSupplierCashOutcome.ExternalAttested -> LocalRetailObservation.ExternalAttested(retainedEvidence, null, reference, amount, literalAmount?.supplierUiLiteral())
    }
}

internal fun supplierObservationSummary(value: LocalRetailObservation): List<LocalRecordValue> = buildList {
    add(recordValue(text("Original evidence", "الدليل الأصلي"), literal(value.evidence.evidenceId.value)))
    add(recordValue(text("Observed at", "وقت الإفادة"), literal(value.evidence.occurredAt.canonicalString())))
    val description = when (value) {
        is LocalRetailObservation.Unknown -> "UNKNOWN"
        is LocalRetailObservation.NotPaid -> "NOT_PAID — ${value.reason}"
        is LocalRetailObservation.CashPaid -> "CASH_PAID — ${value.literalSupplierAmount?.let { "${it.decimal} ${it.currencyIdentifier} (literal precision retained)" } ?: value.amount?.let { moneyText(it).english } ?: "MISSING"}"
        is LocalRetailObservation.ExternalAttested -> "EXTERNAL_ATTESTED — ${value.literalSupplierAmount?.let { "${it.decimal} ${it.currencyIdentifier} (literal precision retained)" } ?: value.amount?.let { moneyText(it).english } ?: "MISSING"} — ${value.reference ?: ""}"
        is LocalRetailObservation.CashReceived -> invalid()
    }
    add(recordValue(text("Literal old supplier payout observation", "إفادة دفعة المورد السابقة الحرفية"), literal(description)))
}

private fun LocalSupplierObservedAmount.supplierLiteral(): LocalSupplierCashLiteralAmount =
    LocalSupplierCashLiteralAmount(currencyIdentifier, decimal)
private fun LocalSupplierCashLiteralAmount.supplierUiLiteral(): LocalSupplierObservedAmount =
    LocalSupplierObservedAmount(currencyIdentifier, decimal)

/** Supplier literal evidence must never be ignored or treated as a customer attestation. */
internal fun LocalRetailObservation.requireNonSupplierObservation() {
    when (this) {
        is LocalRetailObservation.CashPaid -> require(literalSupplierAmount == null)
        is LocalRetailObservation.ExternalAttested -> require(literalSupplierAmount == null)
        else -> Unit
    }
}
