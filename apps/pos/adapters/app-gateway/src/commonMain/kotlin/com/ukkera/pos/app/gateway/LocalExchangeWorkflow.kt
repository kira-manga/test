package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.sales.store.*

/**
 * Exchange (RET-05/06/07) from an accepted return's finance page. The Master alone decides
 * eligibility: the replacement collection must be the current actor's normalized collection on
 * their OPEN drawer session and the offset must exactly settle what remains payable. The client
 * form never moves cash and never grants authority. One obligation slice per form.
 */
internal fun exchangeForm(access: GatewayAccess, value: LocalAcceptedReturnViewV1): LocalActionForm? {
    if (!access.installed.has(LocalExchangeCodec.COMMAND) || value.due.minor <= 0L ||
        !access.allow(LocalAction.RETURN_EXCHANGE_APPLY, value.returnId)) return null
    val single = value.obligations.singleOrNull()?.originalPaymentId
    return form(LocalAction.RETURN_EXCHANGE_APPLY, listOf(
        idField(LocalField.RETURN_ID, value.returnId), idField(LocalField.SALE_ID, value.saleId),
        idField(LocalField.CASE_ID, value.claimId), revisionField(LocalField.EXPECTED_REVISION, value.revision),
        idField(LocalField.COLLECTION_ID, null, readOnly = false),
        scalar(LocalField.EXPECTED_COLLECTION_REVISION, LocalInputKind.INTEGER, maximum = 19),
        idField(LocalField.PAYMENT_ID, single, readOnly = single != null), moneyField(LocalField.AMOUNT),
        newIdField(LocalField.TRANSFER_ID), newIdField(LocalField.SOURCE_ID), newIdField(LocalField.EVIDENCE_ID),
        instantField(LocalField.OCCURRED_AT)),
        text("Exchange: apply this return's remaining refund to your own open replacement sale. Collect any residual first by the normal payment step; the offset must exactly cover what is still payable. No cash moves for the offset portion, and a later return of the replacement refunds it by the original tender.",
            "استبدال: طبّق مبلغ الرد المتبقي لهذا المرتجع على عملية البيع البديلة المفتوحة لديك. حصّل أي فرق أولًا بخطوة الدفع العادية؛ ويجب أن تغطي المقاصة المتبقي المستحق تمامًا. لا تتحرك نقدية لجزء المقاصة، ويُرد لاحقًا عند إرجاع البديل بوسيلة الدفع الأصلية."))
}

internal suspend fun prepareExchange(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.action != LocalAction.RETURN_EXCHANGE_APPLY || intent.lines.isNotEmpty() || intent.lineField != null) invalid()
    val input = InputValues(intent.values)
    val amount = input.money(LocalField.AMOUNT).also { if (it.minor <= 0L) invalid(LocalField.AMOUNT) }
    val value = LocalExchangeRequestV1(input.id(LocalField.TRANSFER_ID), input.id(LocalField.SALE_ID), input.id(LocalField.CASE_ID),
        input.id(LocalField.RETURN_ID), input.revision(LocalField.EXPECTED_REVISION), input.id(LocalField.COLLECTION_ID),
        input.revision(LocalField.EXPECTED_COLLECTION_REVISION),
        listOf(LocalExchangeSliceV1(input.id(LocalField.SOURCE_ID), input.id(LocalField.PAYMENT_ID), amount)), input.cashEvidence())
    input.finish()
    access.requireAction(intent.action, value.returnId)
    return MutationRequest(intent.operationId, LocalExchangeCodec.COMMAND, LocalExchangeCodec.encode(value))
}

internal fun exchangeSummary(request: MutationRequest): List<LocalRecordValue> {
    if (request.command != LocalExchangeCodec.COMMAND) unavailable()
    val value = LocalExchangeCodec.decode(request.payload)
    return listOf(
        recordValue(fieldLabel(LocalField.RETURN_ID), literal(value.returnId.value)),
        recordValue(fieldLabel(LocalField.COLLECTION_ID), literal(value.collectionId.value)),
        recordValue(fieldLabel(LocalField.TRANSFER_ID), literal(value.exchangeId.value)),
        recordValue(fieldLabel(LocalField.AMOUNT), moneyText(value.slices.drop(1).fold(value.slices.first().amount) { sum, it -> sum + it.amount })),
        recordValue(fieldLabel(LocalField.EVIDENCE_ID), literal(value.evidence.evidenceId.value)),
        recordValue(fieldLabel(LocalField.OCCURRED_AT), literal(value.evidence.occurredAt.canonicalString())),
        recordValue(text("Effect", "الأثر"), text(
            "Restricted refund-to-replacement offset only; no cash leaves or enters a drawer. Do not repeat after an uncertain reply — keep the original operation ID.",
            "مقاصة مقيدة من الرد إلى البديل فقط؛ لا تخرج نقدية من الدرج ولا تدخله. لا تكرر بعد رد غير محسوم — احتفظ بمعرف العملية الأصلي.")))
}

internal fun exchangeRequestValid(request: MutationRequest, action: LocalAction): Boolean =
    request.actionApprovalId == null && action == LocalAction.RETURN_EXCHANGE_APPLY &&
        LocalExchangeCodec.normalize(request.payload) == request.payload

/** The definite kernel decision survives a failed detail decoder; never replace the saved identity. */
internal fun exchangeRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    val decision = if (reply.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED
    val message = try {
        val saved = entry.request()
        if (reply.operationId != entry.operationId || !actionMatches(entry.localAction, saved) ||
            entry.localAction != LocalAction.RETURN_EXCHANGE_APPLY) unavailable()
        val request = LocalExchangeCodec.decode(saved.payload)
        when (val result = LocalExchangeCodec.result(reply.result)) {
            is LocalExchangeResultV1.Applied -> {
                if (!reply.committed || result.exchangeId != request.exchangeId || result.returnId != request.returnId ||
                    result.collectionId != request.collectionId) unavailable()
                text("Exchange offset of ${moneyText(result.amount).english} applied. The replacement collection is covered; refund still due on the return: ${moneyText(result.returnDue).english}. Finalize the replacement sale normally.",
                    "طُبقت مقاصة استبدال بقيمة ${moneyText(result.amount).arabic}. اكتمل تحصيل البديل؛ مبلغ الرد المستحق المتبقي على المرتجع: ${moneyText(result.returnDue).arabic}. أنهِ عملية البيع البديلة كالمعتاد.")
            }
            is LocalExchangeResultV1.Refused -> {
                if (reply.committed) unavailable()
                when (result.reason) {
                    LocalExchangeRefusalV1.NOT_AVAILABLE -> text("Refused: the return or replacement collection is unavailable, changed, not yours, or the offset does not exactly cover what remains payable. Nothing changed.",
                        "رُفض: المرتجع أو تحصيل البديل غير متاح أو تغير أو ليس لك، أو المقاصة لا تغطي المتبقي المستحق تمامًا. لم يتغير شيء.")
                    LocalExchangeRefusalV1.DUPLICATE -> text("Refused: that exchange or attempt identity is already used.", "رُفض: معرف الاستبدال أو المحاولة مستخدم بالفعل.")
                }
            }
        }
    } catch (_: Exception) {
        text("The master recorded a ${if (reply.committed) "committed" else "rejected"} exchange operation, but its details cannot be displayed. Keep the original operation ID; do not repeat it.",
            "سجل الجهاز الرئيسي عملية استبدال ${if (reply.committed) "مثبتة" else "مرفوضة"}، لكن لا يمكن عرض تفاصيلها. احتفظ بمعرف العملية الأصلي؛ لا تكررها.")
    }
    return LocalOperationResult.Recorded(reply.operationId, decision, message, null, reply.replayed)
}
