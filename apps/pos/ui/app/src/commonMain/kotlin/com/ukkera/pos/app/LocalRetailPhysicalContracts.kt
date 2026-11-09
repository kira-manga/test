package com.ukkera.pos.app

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

enum class LocalRetailPhysicalKind { PREPARE_COLLECTION, PAYMENT, REFUND, APPLICATION, SUPERVISOR_REVIEW, RESOLUTION, SUPPLIER_CASH_OUT }
enum class LocalRetailObservationPhase { ORIGINAL, RESOLUTION }
/** Display/routing of a validated financial target, not a new native domain or permission. */
enum class LocalRetailFinancialKind { PAYMENT, REFUND, SUPPLIER_CASH }
/** Display/request routing only; the native requirement and command remain authoritative. */
enum class LocalPaymentSubject { RETAIL, FOOD, SUPPLIER }

/** Selectors and attestation inputs, never native/provenance/capacity or employee authority. */
sealed interface LocalRetailPhysicalRequest {
    data class BeginCollection(val orderId: DomainId) : LocalRetailPhysicalRequest
    data class Payment(val collectionId: DomainId, val amount: Money? = null,
        val externalTenderId: DomainId? = null, val expectedTenderRevision: Revision? = null,
        val subject: LocalPaymentSubject = LocalPaymentSubject.RETAIL) : LocalRetailPhysicalRequest {
        init { require((externalTenderId == null) == (expectedTenderRevision == null)) }
    }
    data class Refund(val saleId: DomainId, val claimId: DomainId, val returnId: DomainId,
        val originalPaymentId: DomainId) : LocalRetailPhysicalRequest
    /** FOOD cancellation may have no finalized sale. Never reuse the retail sale/claim selector. */
    data class FoodRefund(val refundId: DomainId, val originalPaymentId: DomainId) : LocalRetailPhysicalRequest
    /** Supplier payout selectors only; real source/session/capacity are re-read by the gateway. */
    data class SupplierCash(val supplierId: DomainId, val invoiceId: DomainId?, val amount: Money,
        val reason: String) : LocalRetailPhysicalRequest
    data class SupplierCashApply(val originalRecordingOperationId: OperationId,
        val phase: LocalRetailObservationPhase) : LocalRetailPhysicalRequest
    data class SupplierCashResolve(val originalRecordingOperationId: OperationId,
        val observation: LocalRetailObservation) : LocalRetailPhysicalRequest
    data class FoodRefundApply(val factOperationId: OperationId,
        val phase: LocalRetailObservationPhase) : LocalRetailPhysicalRequest
    data class FoodRefundResolve(val originalRecordingOperationId: OperationId,
        val observation: LocalRetailObservation) : LocalRetailPhysicalRequest
    data class Apply(val recordingOperationId: OperationId, val phase: LocalRetailObservationPhase,
        val subject: LocalPaymentSubject = LocalPaymentSubject.RETAIL) : LocalRetailPhysicalRequest
    data class SupervisorReview(val recordingOperationId: OperationId,
        val subject: LocalPaymentSubject = LocalPaymentSubject.RETAIL) : LocalRetailPhysicalRequest
    data class SupervisorApply(val reviewOperationId: OperationId, val phase: LocalRetailObservationPhase,
        val subject: LocalPaymentSubject = LocalPaymentSubject.RETAIL) : LocalRetailPhysicalRequest
    data class Resolve(val reviewOperationId: OperationId, val observation: LocalRetailObservation,
        val subject: LocalPaymentSubject = LocalPaymentSubject.RETAIL) : LocalRetailPhysicalRequest
}

data class LocalRetailEvidence(val evidenceId: DomainId, val occurredAt: UtcInstant)
/** Literal supplier-only evidence, not posted Money or currency/settlement authority. */
data class LocalSupplierObservedAmount(val currencyIdentifier: String, val decimal: String) {
    init {
        require(currencyIdentifier.length in 1..32 && currencyIdentifier.all { it.isLetterOrDigit() || it in "._-" })
        require(decimal.length in 1..96 && Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?").matches(decimal))
    }
}
sealed interface LocalRetailObservation {
    val evidence: LocalRetailEvidence
    data class Unknown(override val evidence: LocalRetailEvidence) : LocalRetailObservation
    data class NotPaid(override val evidence: LocalRetailEvidence, val reason: String) : LocalRetailObservation
    data class CashReceived(override val evidence: LocalRetailEvidence, val received: Money,
        val change: Money) : LocalRetailObservation
    /** FOOD requires actual Money; supplier accepts literal evidence. Old retail attestation is unchanged. */
    data class CashPaid(override val evidence: LocalRetailEvidence, val amount: Money? = null,
        val literalSupplierAmount: LocalSupplierObservedAmount? = null) : LocalRetailObservation
    data class ExternalAttested(override val evidence: LocalRetailEvidence, val terminal: String?,
        val reference: String?, val amount: Money? = null,
        val literalSupplierAmount: LocalSupplierObservedAmount? = null) : LocalRetailObservation
}

/** Gateway-owned immutable input handle. Its public fields are display, not dispatch authority. */
interface LocalRetailPhysicalPreparation {
    val operationId: OperationId
    val kind: LocalRetailPhysicalKind
    val title: LocalText
    val summary: List<LocalRecordValue>
    /** Explicit routing of the gateway's exact saved body; old implementations stay retail-only. */
    val subject: LocalPaymentSubject get() = LocalPaymentSubject.RETAIL
    val financialKind: LocalRetailFinancialKind? get() = null
}

data class LocalRetailInstructionDisplay(val operationId: OperationId, val recordingOperationId: OperationId,
    val resolutionOperationId: OperationId, val kind: LocalRetailPhysicalKind, val documentId: DomainId,
    val cashSessionId: DomainId, val amount: Money, val tender: String, val requiresTerminal: Boolean,
    val requiresReference: Boolean, val subject: LocalPaymentSubject = LocalPaymentSubject.RETAIL)

/** No public implementation is trusted by the concrete gateway; it retains its exact native handle. */
interface LocalRetailPhysicalInstruction : LocalRetailPhysicalDenial {
    val display: LocalRetailInstructionDisplay
    fun deliverable(): Boolean
    /** Synchronous canonical-body capture before any coroutine or worker hop. */
    fun capture(observation: LocalRetailObservation): LocalRetailPhysicalCapture
}

sealed interface LocalRetailPhysicalCapture {
    /** In-memory custody ONLY. Neither durable native/SQL storage nor money success. */
    data class CapturedInMemory(val recordingOperationId: OperationId, val bodyDigest: String) : LocalRetailPhysicalCapture
    data object Rejected : LocalRetailPhysicalCapture
    data object InvalidInput : LocalRetailPhysicalCapture
    data object Uncertain : LocalRetailPhysicalCapture
}

enum class LocalRetailDurableState {
    SAVED_NOT_DISPATCHED, DISPATCH_UNRESOLVED, START_RECORDED_NO_INSTRUCTION,
    COLLECTION_PREPARED, STOCK_HELD_NO_INSTRUCTION, SQL_ORIGINAL_PENDING, SQL_RESOLUTION_PENDING,
    OBSERVATION_APPLIED_UNRESOLVED, OBSERVATION_APPLIED_NO_MONEY, OBSERVATION_ALREADY_APPLIED,
    FINANCIALLY_APPLIED, REJECTED, SUPERVISOR_REVIEW_RECORDED,
}

data class LocalRetailPhysicalProgress(val operationId: OperationId, val state: LocalRetailDurableState,
    val message: LocalText, val recordingOperationId: OperationId? = null,
    val resolutionOperationId: OperationId? = null, val phase: LocalRetailObservationPhase? = null,
    val originalUnknown: Boolean = false, val collectionId: DomainId? = null,
    val reviewDisplay: LocalRetailInstructionDisplay? = null,
    val originalApplied: Boolean = false, val resolutionRecorded: Boolean = false,
    val originalActorId: DomainId? = null, val recordingActorId: DomainId? = null,
    /** Audited historical observation details only, never a live instruction. */
    val reviewValues: List<LocalRecordValue> = emptyList(),
    /** Immutable original selector for fresh audited review; recordingOperationId is the current phase's fact ID. */
    val originalRecordingOperationId: OperationId? = null,
    val subject: LocalPaymentSubject = LocalPaymentSubject.RETAIL,
    /** Validated closed financial target only; null is never inferred as a refund or payment. */
    val financialKind: LocalRetailFinancialKind? = null)

data class LocalRetailSavedOperation(val operationId: OperationId, val label: LocalText,
    val dispatched: Boolean, val isPhysicalStart: Boolean, val recorded: Boolean = false,
    val summary: List<LocalRecordValue> = emptyList(),
    /** Saved input only; genuine authorized native retention precedes later SQL accounting. */
    val requiresNativeRetention: Boolean = false,
    /** A saved supervisor Review has no asserted kind before its separate audit. */
    val financialKind: LocalRetailFinancialKind? = null,
    val subject: LocalPaymentSubject = LocalPaymentSubject.RETAIL)

data class LocalRetailTenderChoice(val id: DomainId, val revision: Revision, val label: LocalText)
data class LocalRetailPaymentContext(val collectionId: DomainId, val remaining: Money,
    val externalTenders: List<LocalRetailTenderChoice>, val nextAfter: DomainId?,
    val tenderProblem: LocalServiceProblem? = null, val subject: LocalPaymentSubject = LocalPaymentSubject.RETAIL)

data class LocalRetailRefundDue(val saleId: DomainId, val claimId: DomainId, val returnId: DomainId,
    val originalPaymentId: DomainId, val amount: Money, val tender: String, val unresolved: Boolean = false)

/** Own existing attempt selectors from an authenticated accepted-return read; never a prompt. */
data class LocalRetailRefundRecovery(val saleId: DomainId, val claimId: DomainId, val returnId: DomainId,
    val attemptId: DomainId, val originalPaymentId: DomainId, val amount: Money, val tender: String,
    val status: String, val intentOperationId: OperationId, val cashSessionId: DomainId,
    val recordingOperationId: OperationId?, val protocolVersion: Int?)

sealed interface LocalRetailPhysicalReply {
    data class Prepared(val value: LocalRetailPhysicalPreparation) : LocalRetailPhysicalReply
    data class Staged(val value: LocalRetailSavedOperation) : LocalRetailPhysicalReply
    data class Instruction(val value: LocalRetailPhysicalInstruction) : LocalRetailPhysicalReply
    data class Progress(val value: LocalRetailPhysicalProgress) : LocalRetailPhysicalReply
    data class Saved(val values: List<LocalRetailSavedOperation>, val facts: List<LocalRetailPhysicalProgress>,
        val nextFactAfter: DomainId?) : LocalRetailPhysicalReply
    data class PaymentContext(val value: LocalRetailPaymentContext) : LocalRetailPhysicalReply
    data class RefundDues(val values: List<LocalRetailRefundDue>, val nextAfter: DomainId?,
        val recoveries: List<LocalRetailRefundRecovery> = emptyList()) : LocalRetailPhysicalReply
    data class FoodRefundContext(val value: LocalFoodRefundContext) : LocalRetailPhysicalReply
    data class FoodRefundOriginal(val value: LocalFoodRefundOriginalSelection) : LocalRetailPhysicalReply
    data class Unavailable(val problem: LocalServiceProblem, val operationId: OperationId? = null) : LocalRetailPhysicalReply
}

interface LocalRetailPhysicalGateway {
    /** Current read controls only; preparation and the kernel re-read all authoritative sources. */
    suspend fun paymentContext(collectionId: DomainId, afterTender: DomainId? = null): LocalRetailPhysicalReply
    suspend fun foodPaymentContext(collectionId: DomainId): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    suspend fun recoverFood(operationId: OperationId): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED, operationId)
    suspend fun pendingFood(afterFact: DomainId? = null): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    /** Separate own/private resolution page; it does not share the original cursor. */
    suspend fun pendingFoodResolutions(afterFact: DomainId? = null): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    suspend fun foodRefundContext(refundId: DomainId): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    suspend fun recoverFoodRefund(operationId: OperationId): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED, operationId)
    suspend fun recoverFoodRefundAttempt(refundId: DomainId, attemptId: DomainId): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    suspend fun foodRefundOriginal(recordingOperationId: OperationId): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED, recordingOperationId)
    suspend fun pendingFoodRefunds(afterFact: DomainId? = null): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    suspend fun pendingFoodRefundResolutions(afterFact: DomainId? = null): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    /** Own retained supplier IDs and observations only; never another Start/instruction. */
    suspend fun recoverSupplierCash(operationId: OperationId): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED, operationId)
    suspend fun pendingSupplierCash(afterFact: DomainId? = null): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    suspend fun prepare(request: LocalRetailPhysicalRequest): LocalRetailPhysicalReply
    suspend fun stage(preparation: LocalRetailPhysicalPreparation): LocalRetailPhysicalReply
    suspend fun start(operationId: OperationId, originalEntry: LocalRetailPhysicalLifetime): LocalRetailPhysicalReply
    /** Already-saved SQL accounting/review or authorized linked retention; never a physical Start. */
    suspend fun continueSql(operationId: OperationId): LocalRetailPhysicalReply
    /** Authenticated SQL result/fact reads only; never absent => new action or outcome. */
    suspend fun recover(operationId: OperationId): LocalRetailPhysicalReply
    suspend fun pending(afterFact: DomainId? = null): LocalRetailPhysicalReply
    suspend fun refundDues(after: DomainId? = null): LocalRetailPhysicalReply
    /** Actual accepted case/binding and original accepted-return sources only; absence is unavailable. */
    suspend fun acceptedRefundDues(caseId: DomainId, returnId: DomainId): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    /** One real SQL scan page, filtered for refunds without discarding its underlying cursor. */
    suspend fun pendingRefunds(afterFact: DomainId? = null): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    /** Refund entries/facts only; an untyped supervisor Review reveals kind only after audit. */
    suspend fun recoverRefundOperation(operationId: OperationId): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED, operationId)
    /** Re-reads the exact own V3 attempt/slot/fact lineage; never repeats Start or original capture. */
    suspend fun recoverRefund(saleId: DomainId, claimId: DomainId, returnId: DomainId,
        attemptId: DomainId): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
}

object UnavailableLocalRetailPhysical : LocalRetailPhysicalGateway {
    private fun unavailable(id: OperationId? = null) = LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED, id)
    override suspend fun paymentContext(collectionId: DomainId, afterTender: DomainId?) = unavailable()
    override suspend fun prepare(request: LocalRetailPhysicalRequest) = unavailable()
    override suspend fun stage(preparation: LocalRetailPhysicalPreparation) = unavailable(preparation.operationId)
    override suspend fun start(operationId: OperationId, originalEntry: LocalRetailPhysicalLifetime): LocalRetailPhysicalReply {
        originalEntry.deny(); return unavailable(operationId)
    }
    override suspend fun continueSql(operationId: OperationId) = unavailable(operationId)
    override suspend fun recover(operationId: OperationId) = unavailable(operationId)
    override suspend fun pending(afterFact: DomainId?) = unavailable()
    override suspend fun refundDues(after: DomainId?) = unavailable()
}
