package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Exact canonical request decoding. Lookup never reads or refreshes a preparation. */
internal fun foodCashFinancialRequest(action: LocalAction, request: MutationRequest): LocalFoodCashFinancialOriginal {
    if (action !in foodCashFinancialActions || request.actionApprovalId != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    try {
        return when (request.command) {
            LocalFoodStockCodecV2.COMMAND -> {
                val wrapped = LocalFoodStockCodecV2.decode(request.payload)
                if (LocalFoodStockCodecV2.encode(wrapped) != request.payload) uncertainFoodCashFinancial()
                val original = (wrapped as? LocalFoodStockRequestV2.Financial)?.request
                    ?: throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                when (original) {
                    is LocalFoodFinancialRequestV1.BeginCollection -> {
                        if (action != LocalAction.FOOD_BEGIN_COLLECTION) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                        require(original.expectedOrderRevision.value in 1 until Long.MAX_VALUE)
                        LocalFoodCashFinancialOriginal.BeginCollection(original.orderId, original.expectedOrderRevision,
                            original.collectionId, original.sessionId, original.expectedSessionRevision)
                    }
                    is LocalFoodFinancialRequestV1.FinalizePaid -> {
                        if (action != LocalAction.FOOD_FINALIZE_PAID) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                        require(original.expectedCollectionRevision.value < Long.MAX_VALUE)
                        LocalFoodCashFinancialOriginal.FinalizePaid(original.collectionId, original.expectedCollectionRevision, original.saleId)
                    }
                    else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                }
            }
            LocalFoodPaymentProfile.COMMAND -> {
                val original = LocalFoodPaymentCodec.decode(request.payload)
                if (LocalFoodPaymentCodec.encode(original) != request.payload) uncertainFoodCashFinancial()
                if (action != LocalAction.FOOD_PAYMENT_APPLY || original !is LocalFoodPaymentRequest.ApplyOriginal)
                    throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                require(request.operationId != original.recordingOperationId)
                LocalFoodCashFinancialOriginal.ApplyOriginal(original.recordingOperationId)
            }
            else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        }
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { uncertainFoodCashFinancial() }
}

internal fun savedFoodCashFinancialRequest(entry: StoredClientIntentV1): LocalFoodCashFinancialOriginal {
    if (entry.formVersion != 1 || entry.actionApproval != null || entry.actionApprovalId != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val original = foodCashFinancialRequest(entry.localAction, entry.request())
    try {
        entry.validate(entry.owner)
        entry.recorded?.let { retained ->
            require(retained.sequence.value > 0)
            foodCashFinancialOutcome(original, retained.committed, retained.code,
                decodeHex(retained.payloadHex, 65_536).useWiped { StoredBytes(it) }, entry.operationId)
        }
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { uncertainFoodCashFinancial() }
    return original
}

internal sealed interface FoodCashFinancialOutcome {
    data class Financial(val result: LocalFoodFinancialResultV1) : FoodCashFinancialOutcome
    data class Payment(val result: LocalFoodPaymentResult) : FoodCashFinancialOutcome
}

internal fun foodCashFinancialRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): FoodCashFinancialOutcome {
    val original = savedFoodCashFinancialRequest(entry)
    if (reply.operationId != entry.operationId || reply.originalActorId != entry.owner.actorId ||
        reply.physicalStart != null || reply.sequence.value <= 0L) uncertainFoodCashFinancial()
    return foodCashFinancialOutcome(original, reply.committed, reply.code, reply.result, entry.operationId)
}

private fun foodCashFinancialOutcome(original: LocalFoodCashFinancialOriginal, committed: Boolean,
    code: String, payload: StoredBytes, applicationId: com.ukkera.pos.types.OperationId? = null): FoodCashFinancialOutcome {
    try {
        if (original is LocalFoodCashFinancialOriginal.ApplyOriginal) {
            val result = LocalFoodPaymentCodec.result(payload)
            when (result) {
                is LocalFoodPaymentResult.Applied -> {
                    require(committed && code == "committed" && result.phase == LocalFoodPaymentFactPhase.ORIGINAL &&
                        result.target.recordingOperationId == original.recordingOperationId &&
                        (applicationId == null || result.applicationOperationId == applicationId) &&
                        result.applicationOperationId !in setOf(result.target.intentOperationId,
                            result.target.recordingOperationId, result.target.resolutionOperationId))
                    val rows = result.revisions
                    requireFoodCashFinancialRevisions(rows)
                    require(rows.size == if (result.action == LocalFoodPaymentAction.UNKNOWN) 2 else 3)
                    require(rows.singleOrNull { it.kind == LocalFoodFinancialProfileV1.COLLECTION }?.let {
                        it.id == result.target.collectionId && it.storageRevision.value >= 2
                    } == true)
                    require(rows.singleOrNull { it.kind == LocalFoodPaymentProfile.APPLICATION } ==
                        LocalFoodStorageRevisionV1(LocalFoodPaymentProfile.APPLICATION,
                            DomainId(original.recordingOperationId.value), Revision(2)))
                    if (result.action != LocalFoodPaymentAction.UNKNOWN) require(rows.singleOrNull {
                        it.kind == CashNonSaleStoreProfile.SESSION_KIND
                    }?.let { it.id == result.target.sessionId && it.storageRevision.value >= 2 } == true)
                }
                is LocalFoodPaymentResult.Pending -> require(!committed && code == "food.payment.pending_reconciliation" &&
                    result.phase == LocalFoodPaymentFactPhase.ORIGINAL && result.target.recordingOperationId == original.recordingOperationId &&
                    (applicationId == null || applicationId !in setOf(result.target.intentOperationId,
                        result.target.recordingOperationId, result.target.resolutionOperationId)))
                is LocalFoodPaymentResult.AlreadyApplied -> require(!committed && code == "food.payment.already_applied" &&
                    result.phase == LocalFoodPaymentFactPhase.ORIGINAL && result.recordingOperationId == original.recordingOperationId &&
                    result.applicationOperationId != result.recordingOperationId && result.applicationOperationId != applicationId)
                is LocalFoodPaymentResult.Unavailable -> require(!committed && code == "food.payment.not_available" && result.code == code)
                else -> uncertainFoodCashFinancial() // No Start, raw intake or resolution result.
            }
            return FoodCashFinancialOutcome.Payment(result)
        }
        val result = LocalFoodFinancialCodecV1.result(payload)
        require(result.recordingOperationId == null && result.applicationOperationId == null && result.pendingReason == null)
        if (!committed) {
            require(code in foodCashFinancialRefusalCodes && result.code == code && result.documentId == null &&
                result.amount == null && result.revisions.isEmpty())
        } else {
            require(code == "committed" && result.amount?.let { it.currency == Currency.EGP && it.minor >= 0 } == true)
            requireFoodCashFinancialRevisions(result.revisions)
            when (original) {
                is LocalFoodCashFinancialOriginal.BeginCollection -> {
                    require(result.code == "food.collection.locked" && result.documentId == original.collectionId &&
                        result.revisions.size in 2..34)
                    require(result.revisions.singleOrNull { it.kind == LocalFoodProfileV1.ORDER } ==
                        LocalFoodStorageRevisionV1(LocalFoodProfileV1.ORDER, original.orderId, original.expectedOrderStorageRevision.next()))
                    require(result.revisions.singleOrNull { it.kind == LocalFoodFinancialProfileV1.COLLECTION } ==
                        LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.COLLECTION, original.collectionId, Revision(1)))
                    require(result.revisions.all { it.kind == LocalFoodProfileV1.ORDER || it.kind == LocalFoodFinancialProfileV1.COLLECTION ||
                        it.kind == LocalFoodProfileV1.PORTION && it.storageRevision.value >= 2 })
                }
                is LocalFoodCashFinancialOriginal.FinalizePaid -> {
                    require(result.code == "food.sale.finalized" && result.documentId == original.saleId && result.revisions.size == 3)
                    // The original guard is a DOMAIN revision. Result generations are STORAGE;
                    // neither becomes a domain-only record reference or a refreshed guard.
                    require(result.revisions.singleOrNull { it.kind == LocalFoodFinancialProfileV1.COLLECTION }?.let {
                        it.id == original.collectionId && it.storageRevision.value >= 2
                    } == true)
                    require(result.revisions.singleOrNull { it.kind == LocalFoodFinancialProfileV1.SALE } ==
                        LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.SALE, original.saleId, Revision(1)))
                    require(result.revisions.singleOrNull { it.kind == LocalFoodFinancialProfileV1.ENTITLEMENT } ==
                        LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.ENTITLEMENT, original.saleId, Revision(1)))
                }
                else -> uncertainFoodCashFinancial()
            }
        }
        return FoodCashFinancialOutcome.Financial(result)
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { uncertainFoodCashFinancial() }
}

private val foodCashFinancialRefusalCodes: Set<String> = SalesRule.entries.map { "food.stock.rule.${it.name.lowercase()}" }.toSet() +
    setOf("food.stock.capacity_required", "food.stock.current_recipe_required", "food.stock.unavailable")

private fun requireFoodCashFinancialRevisions(rows: List<LocalFoodStorageRevisionV1>) {
    require(rows.isNotEmpty() && rows.map { it.kind to it.id }.distinct().size == rows.size &&
        rows.all { it.storageRevision.value >= 1 })
}

/** Saved target only. These current display hints do not authorize financial accounting. */
internal fun foodCashFinancialContinuationHint(access: GatewayAccess, entry: StoredClientIntentV1): Boolean =
    when (val original = savedFoodCashFinancialRequest(entry)) {
        is LocalFoodCashFinancialOriginal.BeginCollection -> access.allow(entry.localAction, original.orderId)
        is LocalFoodCashFinancialOriginal.FinalizePaid -> access.hasHint(Permission(SecurityAction.COLLECTION_FINALIZE,
            PermissionVerb.PERFORM), original.collectionId) || access.hasHint(Permission(SecurityAction.COLLECTION_FINALIZE_ZERO,
            PermissionVerb.PERFORM), original.collectionId)
        is LocalFoodCashFinancialOriginal.ApplyOriginal -> access.hasHint(Permission(SecurityAction.CASH_READ,
            PermissionVerb.PERFORM), access.view.actorId)
    }

internal fun foodCashFinancialPending(entry: StoredClientIntentV1, submitAllowed: Boolean): LocalPendingOperation {
    val original = savedFoodCashFinancialRequest(entry)
    val decision = entry.recorded?.let { retained ->
        foodCashFinancialDecision(foodCashFinancialOutcome(original, retained.committed, retained.code,
            decodeHex(retained.payloadHex, 65_536).useWiped { StoredBytes(it) }, entry.operationId), retained.committed)
    }
    return LocalPendingOperation(entry.operationId, entry.localAction, entry.label.ui(), entry.summary.map { it.ui() },
        submitAllowed && entry.recorded == null && !entry.acknowledged, entry.localPhase, decision)
}

internal fun foodCashFinancialRecordedUi(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    val original = savedFoodCashFinancialRequest(entry)
    val outcome = foodCashFinancialRecorded(entry, reply)
    val decision = foodCashFinancialDecision(outcome, reply.committed)
    var record: LocalRecord? = null
    val details: LocalFoodCashFinancialOperationDetails
    val message: LocalText
    when (outcome) {
        is FoodCashFinancialOutcome.Financial -> {
            val result = outcome.result
            val collection = if (reply.committed) when (original) {
                is LocalFoodCashFinancialOriginal.BeginCollection -> original.collectionId
                is LocalFoodCashFinancialOriginal.FinalizePaid -> original.collectionId
                else -> uncertainFoodCashFinancial()
            } else null
            val sale = (original as? LocalFoodCashFinancialOriginal.FinalizePaid)?.saleId.takeIf { reply.committed }
            val followUp = if (!reply.committed) null else sale?.let { LocalFoodUiQuery.Receipt(it) }
                ?: collection?.let { LocalFoodUiQuery.Collection(it) }
            details = LocalFoodCashFinancialOperationDetails(entry.operationId, entry.owner.actorId, original, reply.committed,
                result.code, collection, sale, amount = result.amount, followUp = followUp)
            if (reply.committed) record = LocalRecord(LocalRecordReference(
                if (sale != null) LocalRecordKind.FOOD_SALE else LocalRecordKind.FOOD_COLLECTION,
                sale ?: checkNotNull(collection)), entry.label.ui(), literal(result.code),
                listOf(recordValue(text("Original amount — no new cash collection", "المبلغ الأصلي — لا تحصيل نقدي جديد"),
                    moneyText(checkNotNull(result.amount)))) + foodCashFinancialRevisionValues(result.revisions))
            message = if (!reply.committed) text("The original financial command was refused. Preserve its operation ID; no new cash collection or ingredient issue is authorized.",
                "رُفض الأمر المالي الأصلي. احتفظ بمعرف عمليته؛ لا يُصرح بتحصيل نقدي أو صرف مكونات جديد.")
            else if (sale != null) text("Actual stored coverage finalized once. This is not a new collection, ingredient issue, pickup or physical receipt delivery.",
                "ثُبتت التغطية المحفوظة الفعلية مرة واحدة. ليس هذا تحصيلًا جديدًا أو صرف مكونات أو استلام عميل أو تسليم إيصال فعليًا.")
            else text("Original quote locked; any unsent ingredients reserved. Already-issued ingredients were not issued again. Begin collected no cash and issued no ingredients.",
                "قُفل التسعير الأصلي وحُجزت المكونات غير المرسلة إن وجدت. لم تُصرف المكونات المصروفة سابقًا مرة أخرى. لم يُحصّل البدء نقدًا ولم يصرف مكونات.")
        }
        is FoodCashFinancialOutcome.Payment -> {
            val selected = original as? LocalFoodCashFinancialOriginal.ApplyOriginal ?: uncertainFoodCashFinancial()
            val result = outcome.result
            val target = when (result) {
                is LocalFoodPaymentResult.Applied -> result.target
                is LocalFoodPaymentResult.Pending -> result.target
                else -> null
            }
            val application = when (result) {
                is LocalFoodPaymentResult.Applied -> LocalFoodCashFinancialApplication.valueOf(result.action.name)
                is LocalFoodPaymentResult.Pending -> LocalFoodCashFinancialApplication.PENDING_RECONCILIATION
                is LocalFoodPaymentResult.AlreadyApplied -> LocalFoodCashFinancialApplication.ALREADY_APPLIED
                else -> null
            }
            val applicationId = when (result) {
                is LocalFoodPaymentResult.Applied -> result.applicationOperationId
                is LocalFoodPaymentResult.AlreadyApplied -> result.applicationOperationId
                else -> null
            }
            val followUp = when (result) {
                is LocalFoodPaymentResult.Applied -> LocalFoodUiQuery.Collection(result.target.collectionId)
                is LocalFoodPaymentResult.Pending, is LocalFoodPaymentResult.AlreadyApplied -> LocalFoodUiQuery.PaymentOriginal(selected.recordingOperationId)
                else -> null
            }
            details = LocalFoodCashFinancialOperationDetails(entry.operationId, entry.owner.actorId, original, reply.committed,
                reply.code, target?.collectionId, application = application, applicationOperationId = applicationId,
                pendingReason = (result as? LocalFoodPaymentResult.Pending)?.reason?.name, followUp = followUp)
            target?.let { record = LocalRecord(LocalRecordReference(LocalRecordKind.FOOD_COLLECTION, it.collectionId),
                entry.label.ui(), values = (result as? LocalFoodPaymentResult.Applied)?.let { applied ->
                    foodCashFinancialRevisionValues(applied.revisions)
                }.orEmpty()) }
            message = when (application) {
                LocalFoodCashFinancialApplication.CASH_PAID -> text("Retained FOOD CASH fact accounted once. Sale finalization is separate; do not collect again.",
                    "حوسبت إفادة نقدية الطعام المحفوظة مرة واحدة. تثبيت البيع منفصل؛ لا تكرر التحصيل.")
                LocalFoodCashFinancialApplication.NOT_PAID -> text("Retained NOT PAID observation applied. No money movement was posted.",
                    "طُبقت إفادة عدم الدفع المحفوظة. لم تُسجل حركة مالية.")
                LocalFoodCashFinancialApplication.UNKNOWN -> text("Retained UNKNOWN observation applied; the physical outcome remains unresolved. Preserve evidence; do not collect again.",
                    "طُبقت الإفادة المجهولة المحفوظة؛ تظل النتيجة الفعلية غير محسومة. احتفظ بالدليل ولا تكرر التحصيل.")
                LocalFoodCashFinancialApplication.PENDING_RECONCILIATION -> text("Immutable original retained; accounting remains pending: ${details.pendingReason}. Do not repeat the physical action.",
                    "حُفظ الأصل الثابت؛ المحاسبة معلقة: ${details.pendingReason}. لا تكرر الحركة الفعلية.")
                LocalFoodCashFinancialApplication.ALREADY_APPLIED -> text("Original already applied; no effects repeated. Read the original for its actual outcome, which may remain UNKNOWN.",
                    "طُبق الأصل بالفعل؛ لم تتكرر الآثار. اقرأ الأصل لمعرفة النتيجة الفعلية فقد تظل مجهولة.")
                null -> text("Original application was refused as unavailable. Preserve earlier physical IDs and evidence; absence is not proof that no cash action occurred.",
                    "رُفض تطبيق الأصل لعدم الإتاحة. احتفظ بمعرفات الحركة السابقة وأدلتها؛ الغياب ليس دليلًا على عدم وقوع حركة نقدية.")
            }
        }
    }
    return LocalOperationResult.Recorded(entry.operationId, decision, message, record, reply.replayed,
        originalAction = entry.localAction, foodCashFinancial = details)
}

private fun foodCashFinancialDecision(outcome: FoodCashFinancialOutcome, committed: Boolean): LocalRecordedDecision = when (outcome) {
    is FoodCashFinancialOutcome.Financial -> if (committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED
    is FoodCashFinancialOutcome.Payment -> when (val result = outcome.result) {
        is LocalFoodPaymentResult.Applied -> if (result.action == LocalFoodPaymentAction.CASH_PAID)
            LocalRecordedDecision.APPLIED else LocalRecordedDecision.RECORDED_ONLY
        is LocalFoodPaymentResult.Unavailable -> LocalRecordedDecision.REJECTED
        else -> LocalRecordedDecision.RECORDED_ONLY
    }
}

private fun foodCashFinancialRevisionValues(rows: List<LocalFoodStorageRevisionV1>): List<LocalRecordValue> {
    val blocks = mutableListOf<LocalRecordValue>()
    var block = ""
    fun flush() {
        if (block.isNotEmpty()) blocks += recordValue(text("Recorded storage generations", "مراجعات التخزين المسجلة"), literal(block))
        block = ""
    }
    for (row in rows) {
        val line = "${row.kind} · \u2066${row.id.value}\u2069 · ${row.storageRevision.value}"
        if (line.length > 2048) uncertainFoodCashFinancial()
        if (block.length + line.length + 1 > 2048) flush()
        block += (if (block.isEmpty()) "" else "\n") + line
    }
    flush()
    if (blocks.size > 31) uncertainFoodCashFinancial()
    return blocks
}

private fun uncertainFoodCashFinancial(): Nothing = throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
