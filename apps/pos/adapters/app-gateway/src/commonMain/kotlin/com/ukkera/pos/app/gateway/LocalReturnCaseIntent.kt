package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import kotlin.time.Instant

/** Fresh preparation only: recovery keeps the saved original command, actor and IDs. */
internal suspend fun prepareReturnCase(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.lineField != null || intent.lines.isNotEmpty()) invalid(LocalField.LINES)
    val input = InputValues(intent.values)
    val id = input.id(LocalField.CASE_ID)
    val request = if (intent.action == LocalAction.RETURN_CASE_RECORD) {
        access.requireAction(intent.action, id)
        val facts = LocalReturnCaseFactsV1(LocalReturnClaimFactsV1(input.optionalId(LocalField.SALE_ID),
            input.optionalId(LocalField.PORTION_ID), returnQuantity(input, optional = true),
            input.optionalInstant(LocalField.ASSERTED_RECEIVED_AT), input.instant(LocalField.CLAIMED_AT),
            input.text(LocalField.REASON), returnEvidence(input)), input.text(LocalField.CONDITION),
            enumChoice<LocalReturnRequestedRemedyV1>(input.choice(LocalField.REQUESTED_REMEDY), LocalField.REQUESTED_REMEDY),
            input.text(LocalField.CLAIM_TIME_ZONE), input.optionalChoice(LocalField.CLAIMED_REASON)?.let {
                enumChoice<LocalReturnClaimReasonV1>(it, LocalField.CLAIMED_REASON)
            })
        val ref = intent.reference
        if (ref != null) when (ref.kind) {
            LocalRecordKind.SALE -> if (ref.id != facts.asserted.saleId || ref.parentId != null || ref.revision != null) invalid(LocalField.SALE_ID)
            LocalRecordKind.RETURN_RECEIPT_PORTION -> {
                val sale = ref.parentId ?: invalid(LocalField.SALE_ID)
                val revision = ref.revision ?: invalid(LocalField.EXPECTED_ENTITLEMENT_REVISION)
                if (facts.asserted.saleId != sale || facts.asserted.portionId != ref.id) invalid(LocalField.PORTION_ID)
                val original = returnReceiptPortion(access, sale, ref.id, revision) ?: invalid(LocalField.PORTION_ID)
                val quantity = facts.asserted.quantity ?: invalid(LocalField.QUANTITY)
                if (quantity.unitId != original.originalQuantity.unitId || quantity.scale != original.originalQuantity.scale) invalid(LocalField.UNIT)
            }
            else -> invalid(LocalField.CASE_ID)
        }
        LocalReturnCaseRequestV1.Record(id, facts)
    } else {
        val ref = intent.reference
        if (ref != null && (ref.kind != LocalRecordKind.RETURN_CASE || ref.id != id || ref.parentId != null || ref.revision != null)) invalid(LocalField.CASE_ID)
        val value = returnCaseRead(access, id) ?: invalid(LocalField.CASE_ID)
        val expected = input.revision(LocalField.EXPECTED_CASE_REVISION)
        if (value.summary.revision != expected) invalid(LocalField.EXPECTED_CASE_REVISION)
        when (intent.action) {
            LocalAction.RETURN_CASE_RECEIPT -> {
                access.requireAction(intent.action, id)
                if (value.summary.stage != LocalReturnCaseStageV1.PENDING) invalid(LocalField.CASE_ID)
                val binding = LocalReturnCaseReceiptBindingV1(input.id(LocalField.SALE_ID), input.id(LocalField.PORTION_ID),
                    returnQuantity(input, optional = false) ?: invalid(LocalField.QUANTITY), returnEvidence(input))
                val facts = value.facts.asserted
                val old = value.binding
                if (facts.saleId != null && facts.saleId != binding.saleId || facts.portionId != null && facts.portionId != binding.portionId ||
                    facts.quantity != null && facts.quantity != binding.quantity || old != null &&
                    (old.saleId != binding.saleId || old.portionId != binding.portionId || old.quantity != binding.quantity)) invalid(LocalField.PORTION_ID)
                LocalReturnCaseRequestV1.AppendReceiptEvidence(id, expected, binding)
            }
            LocalAction.RETURN_CASE_QUALIFY -> {
                access.requireAction(intent.action, id)
                val binding = value.binding ?: invalid(LocalField.CASE_ID)
                if (value.summary.stage != LocalReturnCaseStageV1.PENDING) invalid(LocalField.CASE_ID)
                val entitlement = input.optionalRevision(LocalField.EXPECTED_ENTITLEMENT_REVISION)
                val recordOriginalCommercialTiming = input.optionalFlag(LocalField.RECORD_ORIGINAL_COMMERCIAL_TIMING) ?: false
                val inspection = returnOrdinaryInspection(input)
                if (inspection != null && (value.facts.claimedReason != LocalReturnClaimReasonV1.CHANGE_OF_MIND ||
                    value.facts.requestedRemedy != LocalReturnRequestedRemedyV1.REFUND)) invalid(LocalField.ORDINARY_INSPECTION_CONDITION)
                // A missing client projection is never proof of original absence. Send
                // no invented revision; the Master rechecks the actual original cut.
                val receipt = returnReceiptRead(access, binding.saleId, limit = 1)?.second
                if (receipt == null && entitlement != null) invalid(LocalField.SALE_ID)
                if (receipt != null && receipt.entitlementRevision != entitlement) {
                    invalid(LocalField.EXPECTED_ENTITLEMENT_REVISION)
                }
                // Absent/unchecked documentary timing preserves the canonical request.
                // Recovery continues its saved body, never removes a selected option.
                LocalReturnCaseRequestV1.Qualify(id, expected, entitlement,
                    recordOriginalCommercialTiming = recordOriginalCommercialTiming, ordinaryInspection = inspection)
            }
            LocalAction.RETURN_CASE_ACCEPT -> {
                val binding = value.binding ?: invalid(LocalField.CASE_ID)
                access.requireAction(intent.action, binding.saleId)
                val decision = value.decision ?: invalid(LocalField.DECISION_OPERATION_ID)
                val operation = OperationId(input.id(LocalField.DECISION_OPERATION_ID).value)
                if (value.summary.stage != LocalReturnCaseStageV1.BOUND_UNACCEPTED ||
                    value.facts.requestedRemedy != LocalReturnRequestedRemedyV1.REFUND ||
                    value.facts.claimedReason != LocalReturnClaimReasonV1.CHANGE_OF_MIND ||
                    decision.ordinaryInspection?.condition != LocalReturnInspectionConditionV1.UNCHANGED ||
                    decision.ordinaryTiming?.status != LocalReturnCommercialWindowStatusV1.WITHIN ||
                    decision.outcome != LocalReturnOrdinaryAssessmentV1.ELIGIBLE || operation != decision.operationId) invalid(LocalField.DECISION_OPERATION_ID)
                val entitlement = requireReturnEntitlement(access, binding.saleId, input)
                val claim = input.revision(LocalField.EXPECTED_CLAIM_REVISION)
                if (claim != value.qualifiedClaimRevision) invalid(LocalField.EXPECTED_CLAIM_REVISION)
                LocalReturnCaseRequestV1.Accept(id, expected, operation, entitlement, claim, input.id(LocalField.RETURN_ID),
                    enumChoice<LocalReturnDispositionV1>(input.choice(LocalField.DISPOSITION), LocalField.DISPOSITION), returnEvidence(input))
            }
            else -> invalid()
        }
    }
    input.finish()
    return LocalReturnCaseCommandCodecV1.command(intent.operationId, request)
}

private suspend fun requireReturnEntitlement(access: GatewayAccess, saleId: DomainId, input: InputValues): Revision {
    val expected = input.revision(LocalField.EXPECTED_ENTITLEMENT_REVISION)
    val receipt = returnReceiptRead(access, saleId, limit = 1)?.second ?: invalid(LocalField.SALE_ID)
    if (receipt.entitlementRevision != expected) invalid(LocalField.EXPECTED_ENTITLEMENT_REVISION)
    return expected
}

private fun returnQuantity(input: InputValues, optional: Boolean): Quantity? {
    val unit = input.optionalId(LocalField.UNIT)
    val scale = input.optionalInteger(LocalField.QUANTITY_SCALE)
    val quantity = input.optionalDecimal(LocalField.QUANTITY)
    if (optional && unit == null && scale == null && quantity == null) return null
    if (unit == null) invalid(LocalField.UNIT)
    if (scale == null || scale !in 0L..6L) invalid(LocalField.QUANTITY_SCALE)
    return Quantity.parse(unit, quantity ?: invalid(LocalField.QUANTITY), scale.toInt()).requirePositive()
}

private fun returnEvidence(input: InputValues): LocalCashEvidenceV1 = LocalCashEvidenceV1(
    input.id(LocalField.EVIDENCE_ID), input.instant(LocalField.OCCURRED_AT))

private fun returnOrdinaryInspection(input: InputValues): LocalReturnOrdinaryInspectionV1? {
    val condition = input.optionalChoice(LocalField.ORDINARY_INSPECTION_CONDITION)
    val evidenceId = input.optionalId(LocalField.ORDINARY_INSPECTION_EVIDENCE_ID)
    val reportedOccurredAt = input.optionalText(LocalField.ORDINARY_INSPECTION_OCCURRED_AT)
    if (condition == null && evidenceId == null && reportedOccurredAt == null) return null
    val literal = reportedOccurredAt ?: invalid(LocalField.ORDINARY_INSPECTION_OCCURRED_AT)
    if (literal.length > 64) invalid(LocalField.ORDINARY_INSPECTION_OCCURRED_AT)
    val occurredAt = try {
        UtcInstant(Instant.parse(literal))
    } catch (_: IllegalArgumentException) {
        invalid(LocalField.ORDINARY_INSPECTION_OCCURRED_AT)
    }
    return LocalReturnOrdinaryInspectionV1(
        enumChoice<LocalReturnInspectionConditionV1>(condition ?: invalid(LocalField.ORDINARY_INSPECTION_CONDITION),
            LocalField.ORDINARY_INSPECTION_CONDITION),
        LocalCashEvidenceV1(evidenceId ?: invalid(LocalField.ORDINARY_INSPECTION_EVIDENCE_ID),
            occurredAt), reportedOccurredAt = literal)
}

internal fun returnCaseAction(request: LocalReturnCaseRequestV1): LocalAction = when (request) {
    is LocalReturnCaseRequestV1.Record -> LocalAction.RETURN_CASE_RECORD
    is LocalReturnCaseRequestV1.AppendReceiptEvidence -> LocalAction.RETURN_CASE_RECEIPT
    is LocalReturnCaseRequestV1.Qualify -> LocalAction.RETURN_CASE_QUALIFY
    is LocalReturnCaseRequestV1.Accept -> LocalAction.RETURN_CASE_ACCEPT
}

internal fun returnCaseId(request: LocalReturnCaseRequestV1): DomainId = when (request) {
    is LocalReturnCaseRequestV1.Record -> request.caseId
    is LocalReturnCaseRequestV1.AppendReceiptEvidence -> request.caseId
    is LocalReturnCaseRequestV1.Qualify -> request.caseId
    is LocalReturnCaseRequestV1.Accept -> request.caseId
}

internal suspend fun returnCaseContinuation(access: GatewayAccess, entry: StoredClientIntentV1): Boolean {
    val saved = entry.request()
    if (saved.command != LocalReturnCaseProfileV1.COMMAND || !actionMatches(entry.localAction, saved)) return false
    val request = LocalReturnCaseCommandCodecV1.decode(saved.payload)
    val target = if (request is LocalReturnCaseRequestV1.Accept)
        returnCaseRead(access, request.caseId)?.binding?.saleId ?: return false else returnCaseId(request)
    // Case receipt binding is immutable. Never use current revisions to rebuild this saved body.
    return access.allow(entry.localAction, target)
}
