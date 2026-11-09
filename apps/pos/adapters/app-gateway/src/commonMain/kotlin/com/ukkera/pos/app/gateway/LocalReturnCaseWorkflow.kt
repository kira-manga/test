package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CancellationException

/** Legal intake, original receipt lookup and financial visibility are distinct production reads. */
internal suspend fun returnCaseWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    if (query.reference?.kind in customerReceiptRecordKinds || isCustomerReceiptSearch(query.search)) return customerReceiptWorkflow(access, query)
    val section = query.returns?.section ?: invalid()
    val ref = query.reference
    if (ref?.kind == LocalRecordKind.RETURN_CASE_HISTORY) {
        requireCaseReference(ref)
        return returnCaseHistory(access, query, ref.id)
    }
    if (ref?.kind == LocalRecordKind.RETURN_FINANCE) {
        val caseId = ref.parentId ?: invalid()
        if (ref.revision != null) invalid()
        return returnCaseFinance(access, query, caseId, ref.id)
    }
    if (ref?.kind == LocalRecordKind.RETURN_STOCK) {
        val caseId = ref.parentId ?: invalid()
        if (ref.revision != null) invalid()
        return returnStockWorkflow(access, query, caseId, ref.id)
    }
    if (ref?.kind in setOf(LocalRecordKind.SALE, LocalRecordKind.RETURN_RECEIPT_PORTION) ||
        ref == null && section == LocalReturnSection.RECEIPTS) return returnReceiptWorkflow(access, query)
    if (ref != null) requireCaseReference(ref)
    val id = ref?.id ?: query.search.takeIf { it.isNotBlank() }?.let(::searchedId)
    if (id != null) {
        if (query.cursor != null) invalid()
        return returnCaseDetails(access, query, id)
    }
    val page = returnPageCursor(query.cursor, "return-cases")
    val after = page?.after?.let(::searchedId)
    val (reply, value) = returnCaseRead(access, LocalReturnCaseQueryV1.OwnWork(after))
    val work = value as? LocalReturnCaseReadResultV1.Work ?: unavailable()
    val ids = work.cases.map { it.caseId }
    if (work.cases.size > 10 || ids != ids.distinct().sortedBy { it.value } ||
        after != null && ids.any { it.value <= after.value } ||
        work.indexRevision == null && (ids.isNotEmpty() || work.nextAfter != null) ||
        work.nextAfter != null && (ids.isEmpty() || work.nextAfter != ids.last())) unavailable()
    if (page != null && page.revision != work.indexRevision) returnRestart()
    return LocalWorkflowPage(query.workflow, work.cases.map(::returnCaseSummaryRecord),
        listOfNotNull(returnIntakeForm(access)), text(
            "Your retained remedy cases. A pending or ordinary-ineligible assessment does not erase other rights. Intake does not reserve entitlement, receive stock or pay a refund. ${readCut(reply).english}",
            "مطالبات المعالجة المحفوظة الخاصة بك. التقييم المعلق أو عدم أهلية المعالجة العادية لا يمحو الحقوق الأخرى. التسجيل لا يحجز استحقاقًا أو يستلم مخزونًا أو يصرف مبلغًا. ${readCut(reply).arabic}"),
        work.nextAfter?.let { returnPageCursor("return-cases", work.indexRevision ?: unavailable(), it.value) })
}

internal suspend fun returnCaseRead(access: GatewayAccess, query: LocalReturnCaseQueryV1): Pair<ReadReply.Data, LocalReturnCaseReadResultV1> {
    val reply = access.read(LocalReturnCaseReadCodecV1.request(query))
    val value = LocalReturnCaseReadCodecV1.decode(reply.payload)
    if (value == LocalReturnCaseReadResultV1.CapacityRequired) throw GatewayFailure(LocalServiceProblem.RETURN_CAPACITY_REQUIRED)
    return reply to value
}

internal suspend fun returnCaseRead(access: GatewayAccess, id: DomainId): LocalReturnCaseViewV1? {
    val (_, result) = returnCaseRead(access, LocalReturnCaseQueryV1.Case(id))
    if (result == LocalReturnCaseReadResultV1.NotAvailable) return null
    val value = (result as? LocalReturnCaseReadResultV1.Case)?.value ?: unavailable()
    if (value.summary.caseId != id || value.eventCount !in 1..LocalReturnCaseProfileV1.MAX_EVENTS ||
        value.summary.revision.value != value.eventCount - 1L ||
        value.summary.saleId != value.binding?.saleId || value.summary.portionId != value.binding?.portionId ||
        value.summary.decisionOperationId != value.decision?.operationId ||
        value.summary.ordinaryAssessment != value.decision?.outcome) unavailable()
    when (value.summary.stage) {
        LocalReturnCaseStageV1.PENDING -> if (value.qualifiedClaimRevision != null ||
            value.summary.acceptedReturnId != null || value.decision?.outcome == LocalReturnOrdinaryAssessmentV1.ELIGIBLE) unavailable()
        LocalReturnCaseStageV1.BOUND_UNACCEPTED -> if (value.binding == null || value.qualifiedClaimRevision != Revision(0) ||
            value.summary.acceptedReturnId != null || value.decision?.outcome != LocalReturnOrdinaryAssessmentV1.ELIGIBLE) unavailable()
        LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING -> if (value.binding == null || value.qualifiedClaimRevision != Revision(1) ||
            value.summary.acceptedReturnId == null || value.decision?.outcome != LocalReturnOrdinaryAssessmentV1.ELIGIBLE) unavailable()
    }
    value.decision?.let { requireOrdinaryDecision(value, it) }
    return value
}

private fun requireOrdinaryDecision(value: LocalReturnCaseViewV1, decision: LocalReturnCaseDecisionViewV1) {
    if (decision.outcome == LocalReturnOrdinaryAssessmentV1.ELIGIBLE &&
        (value.facts.claimedReason != LocalReturnClaimReasonV1.CHANGE_OF_MIND ||
            value.facts.requestedRemedy != LocalReturnRequestedRemedyV1.REFUND ||
            decision.ordinaryInspection?.condition != LocalReturnInspectionConditionV1.UNCHANGED ||
            decision.ordinaryTiming?.status != LocalReturnCommercialWindowStatusV1.WITHIN ||
            decision.policyId == null || decision.policyRevision == null || decision.salePromiseCaptureId == null ||
            decision.applicableRightsIds.isEmpty() || decision.applicableRightsIds.distinct() != decision.applicableRightsIds ||
            decision.customerReceiptIds.size != 1)) unavailable()
    decision.ordinaryTiming?.let { timing ->
        if (value.facts.claimedReason != LocalReturnClaimReasonV1.CHANGE_OF_MIND ||
            value.facts.requestedRemedy != LocalReturnRequestedRemedyV1.REFUND ||
            decision.ordinaryInspection == null || timing.submissionOperationId != value.originalOperationId ||
            timing.submittedAt != value.summary.recordedAt || timing.receiptEventId !in decision.customerReceiptIds ||
            decision.customerReceivedAt != timing.receivedAt || decision.receiptTimeZoneId != timing.receiptTimeZoneId ||
            decision.outcome == LocalReturnOrdinaryAssessmentV1.INELIGIBLE ||
            decision.outcome == LocalReturnOrdinaryAssessmentV1.ELIGIBLE && timing.status != LocalReturnCommercialWindowStatusV1.WITHIN) unavailable()
    }
}

private suspend fun returnCaseDetails(access: GatewayAccess, query: LocalWorkflowQuery, id: DomainId): LocalWorkflowPage {
    val value = returnCaseRead(access, id) ?: return returnNotAvailable(query.workflow)
    val records = mutableListOf(returnCaseRecord(value))
    value.decision?.let { records += returnDecisionRecord(it) }
    records += LocalRecord(LocalRecordReference(LocalRecordKind.RETURN_CASE_HISTORY, id),
        text("Original case event history", "سجل أحداث المطالبة الأصلي"), values = listOf(
            recordValue(text("Retained events", "الأحداث المحفوظة"), literal(value.eventCount.toString()))))
    value.binding?.let { records += LocalRecord(LocalRecordReference(LocalRecordKind.SALE, it.saleId),
        text("Read original receipt / remaining entitlement", "قراءة الإيصال الأصلي والاستحقاق المتبقي")) }
    value.summary.acceptedReturnId?.let { records += LocalRecord(LocalRecordReference(LocalRecordKind.RETURN_FINANCE, it, parentId = id),
        text("Read actual refund obligations and attempts", "قراءة التزامات الرد والمحاولات الفعلية")) }
    value.summary.acceptedReturnId?.let { records += LocalRecord(LocalRecordReference(LocalRecordKind.RETURN_STOCK, it, parentId = id),
        text("Read separate original stock disposition", "قراءة التصرف المستقل بالمخزون الأصلي"),
        status = text("Acceptance and payout do not restock goods", "القبول والصرف لا يعيدان البضاعة للمخزون")) }
    var extra: LocalText? = null
    val forms = mutableListOf<LocalActionForm>()
    returnReceiptEvidenceForm(access, value)?.let(forms::add)
    // A secondary receipt/policy read cannot hide successfully read original legal facts.
    val binding = value.binding
    if (binding != null && value.summary.stage != LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING) {
        try {
            val receipt = returnReceiptRead(access, binding.saleId, limit = 1)?.second
            if (receipt == null) {
                extra = text("Original receipt unavailable; claim retained without guessed rights.",
                    "الإيصال الأصلي غير متاح؛ تظل المطالبة محفوظة دون افتراض حقوق.")
                returnCaseDecisionForm(access, value, null)?.let(forms::add)
            }
            else returnCaseDecisionForm(access, value, receipt.entitlementRevision)?.let(forms::add)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) {
            if (failure.problem == LocalServiceProblem.SESSION_ENDED) throw failure
            extra = text("Receipt / decision preparation unavailable: ${failure.problem.name}. The case remains retained.",
                "إعداد الإيصال أو القرار غير متاح: ${failure.problem.name}. تظل المطالبة محفوظة.")
        } catch (_: Exception) {
            extra = text("Receipt / decision preparation unavailable. No entitlement or liability is assumed absent.",
                "إعداد الإيصال أو القرار غير متاح. لا يُفترض غياب الاستحقاق أو الالتزام.")
        }
    }
    return LocalWorkflowPage(query.workflow, records, forms, text(
        "Original assertions are immutable. Receipt evidence is not verified customer delivery. Qualification uses retained promises, applicable rights and actual receipt evidence; missing authority stays pending. Acceptance leaves disposition and payout separate. ${extra?.english.orEmpty()}",
        "الإفادات الأصلية ثابتة. إثبات الإيصال ليس تسليمًا مؤكدًا للعميل. يستخدم التأهيل الوعود المحفوظة والحقوق السارية وإثبات الاستلام الفعلي؛ ويظل معلقًا عند غياب المصدر المعتمد. يبقى التصرف بالمخزون والصرف منفصلين عن القبول. ${extra?.arabic.orEmpty()}"))
}

private suspend fun returnCaseHistory(access: GatewayAccess, query: LocalWorkflowQuery, id: DomainId): LocalWorkflowPage {
    val value = returnCaseRead(access, id) ?: return returnNotAvailable(query.workflow)
    val prefix = "return-events:${id.value}"
    val cursor = returnPageCursor(query.cursor, prefix)
    if (cursor != null && cursor.revision != value.summary.revision) returnRestart()
    val after = cursor?.after?.let { returnPageNumber(it, LocalReturnCaseProfileV1.MAX_EVENTS - 1) } ?: -1
    val (reply, result) = returnCaseRead(access, LocalReturnCaseQueryV1.Events(id, after, limit = 4))
    if (result == LocalReturnCaseReadResultV1.NotAvailable) return returnNotAvailable(query.workflow)
    val events = result as? LocalReturnCaseReadResultV1.Events ?: unavailable()
    if (events.caseId != id || events.events.size > 4 || events.events.map { it.operationId }.distinct().size != events.events.size ||
        events.events.withIndex().any { it.value.ordinal != after + it.index + 1 } ||
        events.events.any { it.ordinal >= value.eventCount }) unavailable()
    if (events.caseRevision != value.summary.revision) returnRestart()
    events.events.forEach { event -> event.decision?.let { requireOrdinaryDecision(value, it) } }
    val last = events.events.lastOrNull()?.ordinal
    if (last == null || events.nextAfterOrdinal != (if (last < value.eventCount - 1) last else null)) unavailable()
    return LocalWorkflowPage(query.workflow, listOf(returnCaseSummaryRecord(value.summary)) + events.events.map(::returnCaseEventRecord),
        emptyList(), readCut(reply), events.nextAfterOrdinal?.let { returnPageCursor(prefix, events.caseRevision, it.toString()) })
}

internal fun returnNotAvailable(workflow: LocalWorkflow): LocalWorkflowPage = LocalWorkflowPage(workflow, emptyList(), emptyList(),
    text("This original record is not available to the current actor. This does not prove no claim, entitlement or refund is owed.",
        "هذا السجل الأصلي غير متاح للمستخدم الحالي. لا يثبت ذلك عدم وجود مطالبة أو استحقاق أو مبلغ واجب الرد."))

private fun requireCaseReference(ref: LocalRecordReference) {
    if (ref.kind !in setOf(LocalRecordKind.RETURN_CASE, LocalRecordKind.RETURN_CASE_HISTORY) || ref.parentId != null || ref.revision != null) invalid()
}

internal data class ReturnPageCursor(val revision: Revision, val after: String)
internal fun returnPageCursor(value: String?, prefix: String): ReturnPageCursor? {
    if (value == null) return null
    if (!value.startsWith("$prefix:")) invalid()
    val parts = value.removePrefix("$prefix:").split(':')
    if (parts.size != 2 || parts[1].isEmpty()) invalid()
    val revision = parts[0].toLongOrNull()?.takeIf { it >= 0 && it.toString() == parts[0] } ?: invalid()
    return ReturnPageCursor(Revision(revision), parts[1])
}
internal fun returnPageCursor(prefix: String, revision: Revision, after: String): String = "$prefix:${revision.value}:$after"
internal fun returnPageNumber(value: String, maximum: Int): Int = value.toIntOrNull()
    ?.takeIf { it in 0..maximum && it.toString() == value } ?: invalid()
internal fun returnRestart(): Nothing = throw GatewayFailure(LocalServiceProblem.RETURN_RESTART_REQUIRED)
