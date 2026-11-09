package com.ukkera.pos.app

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId

/** Genuine accepted FOOD actual-tender dues, not a quoted unpaid balance or a new remedy. */
data class LocalFoodRefundDue(val refundId: DomainId, val originalPaymentId: DomainId,
    val amount: Money, val tender: String, val unresolved: Boolean)

/** Own immutable claim/slot selectors only. Recovery never creates or rearms an instruction. */
data class LocalFoodRefundRecovery(val refundId: DomainId, val attemptId: DomainId,
    val originalPaymentId: DomainId, val amount: Money, val status: String,
    val intentOperationId: OperationId, val cashSessionId: DomainId,
    val recordingOperationId: OperationId, val resolutionOperationId: OperationId)

data class LocalFoodRefundContext(val refundId: DomainId, val orderId: DomainId,
    val originalQuotedTotal: Money, val collected: Money, val refunded: Money, val due: Money,
    val dues: List<LocalFoodRefundDue>, val ownRecoveries: List<LocalFoodRefundRecovery>)

/** Authenticated own original-read DATA. This is not a supervisor audit, native handle or grant. */
data class LocalFoodRefundOriginalSelection(val display: LocalRetailInstructionDisplay,
    val original: LocalRetailPhysicalProgress, val resolution: LocalRetailPhysicalProgress?)
