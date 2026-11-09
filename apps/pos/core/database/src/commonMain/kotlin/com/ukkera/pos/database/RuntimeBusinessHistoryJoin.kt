package com.ukkera.pos.database

import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Bounded ordinary original history, wholly selected by the active native read session.
 * These copies are a verification workspace, not a database/cache/current view. Overflow
 * fails closed, never truncates. Unknown events abandon this proof and retain scanner holds.
 * Every accepted descriptor is compared byte-for-byte with its independent native lineage;
 * all before-cuts use COMPLETE lineages and every original is read to its genuine EOF.
 * Native-enrolled employees use a separate registered historical access proof, not a
 * relaxation of the founding actor path. Native originals remain rooted in their genuine
 * earlier authorized Start and whole original certificate/source/writer authority. */
internal class RuntimeBusinessHistoryJoin(
    private val session: SelfOwnedNativeHistoryReadSession,
    private val producer: RuntimeHistoryBusinessProducer,
    codec: StockCanonicalProjectionV2,
    private val coldStore: RuntimeHistoryColdBusinessStore? = null,
    private val coldOriginalRejoin: RuntimeHistoryColdOriginalRejoin? = null,
    private val receiptMembershipBuilder: RuntimeRetailCustomerReceiptMembershipBuilder? = null,
    private val guardedMemory: RuntimeHistoryColdMemory? = null,
) : RetainedStreamedCommandReader {
    private val coordinates = session.original.record.allocation.coordinates
    private val scope = coordinates.scope
    private val epoch = checkNotNull(session.original.record.definition).definition.authorityEpoch
    private val supportedCommands = producer.commands.toSet().also { historyCapacityV2(it.size <= 128) }
    private val attributionSupport = supportedCommands.associateWith(producer::actionApprovalBusinessAttribution)
    private val projection = StockRelationalProjection(codec)
    private val captures = linkedMapOf<Long, CommandCapture>()
    private val byOperation = linkedMapOf<OperationId, CommandCapture>()
    private val histories = linkedMapOf<String, MutableList<Mutation>>()
    private val originals = linkedMapOf<OperationId, RetainedOriginalV2>()
    private val coldOriginalLoans = linkedMapOf<OperationId, RuntimeHistoryColdOriginalLoan>()
    private val coldOwnedAggregates = mutableListOf<StoredAggregate>()
    private val businessPublicationSources = session.businessPublicationObservations.let { selected ->
        selected.associateBy { it.child.historySequence }.also { historyShapeV2(it.size == selected.size) }
    }
    private var seedBranch: ByteArray? = null
    private var retainedBytes = 0L
    private var coldRecipientBytes = 0L
    // Shared capacity belongs to this owner's capture, never its borrowed aliases.
    private var coldOwnedCommandVersions: Int? = null
    private val recipients = RuntimeHistoricalLocalHandoffRecipientTimeline(session, ::chargeRecipient)
    private var versionCount = 0
    private var lineageClosed = false
    private var active: RuntimeHistoricalCommand? = null
    private var heldColdOriginal: RuntimeHistoricalCommand? = null
    private var currentFood: RuntimeHistoricalFoodCurrentState? = null
    private var coldCurrentFoodScope: Any? = null
    private var coldCurrentFoodCopies = 0
    private var historyComplete = false
    private var context: CoroutineContext? = null
    private var coldCommandEvents = 0L
    private var coldCommandMutations = 0L
    private var coldEmployeeCommandEvents = 0L
    private var coldEmployeeCommandMutations = 0L
    private var coldEmployeeNativeOriginalEvents = 0L
    private var coldEmployeeNativeOriginalMutations = 0L
    private var coldFoodPaymentRejectionEvents = 0L
    private var coldFoodPaymentRejectionMutations = 0L
    private var coldEmployeeFoodPaymentRejectionEvents = 0L
    private var coldEmployeeFoodPaymentRejectionMutations = 0L
    private var coldLastCaptureSequence = 0L
    private var coldLastRetiredSequence = 0L
    private var coldJoinedBusinessPublicationSources = 0L
    private var coldObservationTarget: RuntimeHistoryColdReplayTarget? = null
    private var coldLastObservedNativeSequence = 0L
    private var coldLastObservedNativeCommandSequence = 0L

    init {
        historyShapeV2(coldStore == null && coldOriginalRejoin == null ||
            coldStore?.session === session && coldOriginalRejoin != null)
        historyShapeV2(coldStore == null || guardedMemory == null)
        historyShapeV2(receiptMembershipBuilder == null || coldStore != null || guardedMemory != null)
    }
    var enabled: Boolean = true
        private set
    var joinedCommands: Long = 0L
        private set
    val commandEvents: Long get() = if (coldStore == null) captures.size.toLong() else coldCommandEvents
    val commandMutations: Long get() = if (coldStore == null) captures.values.sumOf { it.mutations.size.toLong() } else coldCommandMutations
    val employeeCommandEvents: Long get() = if (coldStore == null) captures.values.count { it.employeeHistory != null }.toLong() else coldEmployeeCommandEvents
    val employeeCommandMutations: Long get() = if (coldStore == null) captures.values.filter { it.employeeHistory != null }.sumOf { it.mutations.size.toLong() } else coldEmployeeCommandMutations
    val approvedCommandEvents: Long get() = captures.values.count { it.approvalHistory != null }.toLong()
    val approvedCommandMutations: Long get() = captures.values.filter { it.approvalHistory != null }.sumOf { it.mutations.size.toLong() }
    val employeeApprovedCommandEvents: Long get() = captures.values.count { it.approvalHistory?.requesterIsOriginal == false }.toLong()
    val employeeApprovedCommandMutations: Long get() = captures.values.filter { it.approvalHistory?.requesterIsOriginal == false }
        .sumOf { it.mutations.size.toLong() }
    val employeeNativeOriginalEvents: Long get() = if (coldStore == null)
        captures.values.count { it.nativeEmployeeStart != null }.toLong() else coldEmployeeNativeOriginalEvents
    val employeeNativeOriginalMutations: Long get() = if (coldStore == null)
        captures.values.filter { it.nativeEmployeeStart != null }.sumOf { it.mutations.size.toLong() } else coldEmployeeNativeOriginalMutations
    val supervisorReviewEvents: Long get() = captures.values.count { it.supervisorReview != null }.toLong()
    val supervisorReviewMutations: Long get() = captures.values.filter { it.supervisorReview != null }.sumOf { it.mutations.size.toLong() }
    val employeeSupervisorReviewEvents: Long get() = captures.values.count { it.employeeSupervisorReview != null }.toLong()
    val employeeSupervisorReviewMutations: Long get() = captures.values.filter { it.employeeSupervisorReview != null }.sumOf { it.mutations.size.toLong() }
    val purchasingPolicyRejectionEvents: Long get() = captures.values.count { it.purchasingPolicyRejection != null }.toLong()
    val purchasingPolicyRejectionMutations: Long get() = captures.values.filter { it.purchasingPolicyRejection != null }.sumOf { it.mutations.size.toLong() }
    val employeePurchasingPolicyRejectionEvents: Long get() = captures.values.count { it.employeePurchasingPolicyRejection != null }.toLong()
    val employeePurchasingPolicyRejectionMutations: Long get() = captures.values.filter { it.employeePurchasingPolicyRejection != null }.sumOf { it.mutations.size.toLong() }
    val orderReassignmentRejectionEvents: Long get() = captures.values.count { it.orderReassignmentRejection != null }.toLong()
    val orderReassignmentRejectionMutations: Long get() = captures.values.filter { it.orderReassignmentRejection != null }.sumOf { it.mutations.size.toLong() }
    val employeeOrderReassignmentRejectionEvents: Long get() = captures.values.count { it.employeeOrderReassignmentRejection != null }.toLong()
    val employeeOrderReassignmentRejectionMutations: Long get() = captures.values.filter { it.employeeOrderReassignmentRejection != null }.sumOf { it.mutations.size.toLong() }
    val openingSupplierObligationRejectionEvents: Long get() = captures.values.count { it.openingSupplierObligationRejection != null }.toLong()
    val openingSupplierObligationRejectionMutations: Long get() = captures.values.filter { it.openingSupplierObligationRejection != null }.sumOf { it.mutations.size.toLong() }
    val employeeOpeningSupplierObligationRejectionEvents: Long get() = captures.values.count { it.employeeOpeningSupplierObligationRejection != null }.toLong()
    val employeeOpeningSupplierObligationRejectionMutations: Long get() = captures.values.filter { it.employeeOpeningSupplierObligationRejection != null }.sumOf { it.mutations.size.toLong() }
    val invoiceCaptureRejectionEvents: Long get() = captures.values.count { it.invoiceCaptureRejection != null }.toLong()
    val invoiceCaptureRejectionMutations: Long get() = captures.values.filter { it.invoiceCaptureRejection != null }.sumOf { it.mutations.size.toLong() }
    val employeeInvoiceCaptureRejectionEvents: Long get() = captures.values.count { it.employeeInvoiceCaptureRejection != null }.toLong()
    val employeeInvoiceCaptureRejectionMutations: Long get() = captures.values.filter { it.employeeInvoiceCaptureRejection != null }.sumOf { it.mutations.size.toLong() }
    val capturedInvoiceMatchRejectionEvents: Long get() = captures.values.count { it.capturedInvoiceMatchRejection != null }.toLong()
    val capturedInvoiceMatchRejectionMutations: Long get() = captures.values.filter { it.capturedInvoiceMatchRejection != null }.sumOf { it.mutations.size.toLong() }
    val employeeCapturedInvoiceMatchRejectionEvents: Long get() = captures.values.count { it.employeeCapturedInvoiceMatchRejection != null }.toLong()
    val employeeCapturedInvoiceMatchRejectionMutations: Long get() = captures.values.filter { it.employeeCapturedInvoiceMatchRejection != null }.sumOf { it.mutations.size.toLong() }
    val branchActivityActivationRejectionEvents: Long get() = captures.values.count { it.branchActivityActivationRejection != null }.toLong()
    val branchActivityActivationRejectionMutations: Long get() = captures.values.filter { it.branchActivityActivationRejection != null }.sumOf { it.mutations.size.toLong() }
    val employeeBranchActivityActivationRejectionEvents: Long get() = captures.values.count { it.employeeBranchActivityActivationRejection != null }.toLong()
    val employeeBranchActivityActivationRejectionMutations: Long get() = captures.values.filter { it.employeeBranchActivityActivationRejection != null }.sumOf { it.mutations.size.toLong() }
    val floorConfigurationRejectionEvents: Long get() = captures.values.count { it.floorConfigurationRejection != null }.toLong()
    val floorConfigurationRejectionMutations: Long get() = captures.values.filter { it.floorConfigurationRejection != null }.sumOf { it.mutations.size.toLong() }
    val employeeFloorConfigurationRejectionEvents: Long get() = captures.values.count { it.employeeFloorConfigurationRejection != null }.toLong()
    val employeeFloorConfigurationRejectionMutations: Long get() = captures.values.filter { it.employeeFloorConfigurationRejection != null }.sumOf { it.mutations.size.toLong() }
    val configurationRejectionEvents: Long get() = captures.values.count { it.configurationRejection != null }.toLong()
    val configurationRejectionMutations: Long get() = captures.values.filter { it.configurationRejection != null }.sumOf { it.mutations.size.toLong() }
    val employeeConfigurationRejectionEvents: Long get() = captures.values.count { it.employeeConfigurationRejection != null }.toLong()
    val employeeConfigurationRejectionMutations: Long get() = captures.values.filter { it.employeeConfigurationRejection != null }.sumOf { it.mutations.size.toLong() }
    val returnRightsActivationRejectionEvents: Long get() = captures.values.count { it.returnRightsActivationRejection != null }.toLong()
    val returnRightsActivationRejectionMutations: Long get() = captures.values.filter { it.returnRightsActivationRejection != null }.sumOf { it.mutations.size.toLong() }
    val employeeReturnRightsActivationRejectionEvents: Long get() = captures.values.count { it.employeeReturnRightsActivationRejection != null }.toLong()
    val employeeReturnRightsActivationRejectionMutations: Long get() = captures.values.filter { it.employeeReturnRightsActivationRejection != null }.sumOf { it.mutations.size.toLong() }
    val retailCustomerReceiptRejectionEvents: Long get() = captures.values.count { it.retailCustomerReceiptRejection != null }.toLong()
    val retailCustomerReceiptRejectionMutations: Long get() = captures.values.filter { it.retailCustomerReceiptRejection != null }.sumOf { it.mutations.size.toLong() }
    val employeeRetailCustomerReceiptRejectionEvents: Long get() = captures.values.count { it.employeeRetailCustomerReceiptRejection != null }.toLong()
    val employeeRetailCustomerReceiptRejectionMutations: Long get() = captures.values.filter { it.employeeRetailCustomerReceiptRejection != null }.sumOf { it.mutations.size.toLong() }
    val foodCheckRejectionEvents: Long get() = captures.values.count { it.foodCheckRejection != null }.toLong()
    val foodCheckRejectionMutations: Long get() = captures.values.filter { it.foodCheckRejection != null }.sumOf { it.mutations.size.toLong() }
    val employeeFoodCheckRejectionEvents: Long get() = captures.values.count { it.employeeFoodCheckRejection != null }.toLong()
    val employeeFoodCheckRejectionMutations: Long get() = captures.values.filter { it.employeeFoodCheckRejection != null }.sumOf { it.mutations.size.toLong() }
    val foodPaymentRejectionEvents: Long get() = if (coldStore == null)
        captures.values.count { it.foodPaymentRejection != null }.toLong() else coldFoodPaymentRejectionEvents
    val foodPaymentRejectionMutations: Long get() = if (coldStore == null)
        captures.values.filter { it.foodPaymentRejection != null }.sumOf { it.mutations.size.toLong() } else coldFoodPaymentRejectionMutations
    val employeeFoodPaymentRejectionEvents: Long get() = if (coldStore == null)
        captures.values.count { it.employeeFoodPaymentRejection != null }.toLong() else coldEmployeeFoodPaymentRejectionEvents
    val employeeFoodPaymentRejectionMutations: Long get() = if (coldStore == null)
        captures.values.filter { it.employeeFoodPaymentRejection != null }.sumOf { it.mutations.size.toLong() } else coldEmployeeFoodPaymentRejectionMutations
    val returnCaseQualificationRejectionEvents: Long get() = captures.values.count { it.returnCaseQualificationRejection != null }.toLong()
    val returnStockDispositionRejectionEvents: Long get() = captures.values.count { it.returnStockDispositionRejection != null }.toLong()
    val returnCaseQualificationRejectionMutations: Long get() = captures.values.filter { it.returnCaseQualificationRejection != null }.sumOf { it.mutations.size.toLong() }
    val returnStockDispositionRejectionMutations: Long get() = captures.values.filter { it.returnStockDispositionRejection != null }.sumOf { it.mutations.size.toLong() }
    val employeeReturnCaseQualificationRejectionEvents: Long get() = captures.values.count { it.employeeReturnCaseQualificationRejection != null }.toLong()
    val employeeReturnStockDispositionRejectionEvents: Long get() = captures.values.count { it.employeeReturnStockDispositionRejection != null }.toLong()
    val employeeReturnCaseQualificationRejectionMutations: Long get() = captures.values.filter { it.employeeReturnCaseQualificationRejection != null }.sumOf { it.mutations.size.toLong() }
    val employeeReturnStockDispositionRejectionMutations: Long get() = captures.values.filter { it.employeeReturnStockDispositionRejection != null }.sumOf { it.mutations.size.toLong() }
    val tipDelta: Long get() = coldStore?.tipDelta ?: histories.values.count { it.last().table != RuntimeHistoryTableV2.BRANCH }.toLong()
    fun currentCount(table: RuntimeHistoryTableV2): Long = coldStore?.currentCount(table) ?: if (table == RuntimeHistoryTableV2.BRANCH) 0L else
        histories.values.count { it.last().table == table && it.last().descriptor.afterKind != RuntimeHistoryAfterKindV2.TOMBSTONE.tag }.toLong()

    internal class Mutation(val descriptor: RuntimeHistoryRowVersionRowV2, val wire: ByteArray) {
        val table = RuntimeHistoryTableV2.fromTag(descriptor.tableTag)
        val token = token(table, descriptor.key)
        var seen = false
        var image: ByteArray? = null
        fun clear() { descriptor.key.fill(0); wire.fill(0); image?.fill(0); image = null }
    }
    internal class CommandCapture(
        val header: RuntimeHistoryEventHeaderV2,
        val eventDigest: PayloadDigest,
        val employee: RuntimeCommandAuthorizationEvidenceV1?,
        val nativeOriginal: RuntimeNativeOriginalImportEvidenceV1?,
        val originalIntentDigest: PayloadDigest,
        val resources: Set<RecoveryResourceKey>,
        val dependencies: ByteArray,
        val mutations: List<Mutation>,
        val employeeHistory: RuntimeEmployeeCommandHistoryAuthorization?,
        val nativeEmployeeStart: CommandCapture?,
        val supervisorReview: RuntimeHistoricalSupervisorReviewProfile?,
        val employeeSupervisorReview: RuntimeEmployeeSupervisorReviewHistoryAuthorization?,
        val purchasingPolicyRejection: RuntimeHistoricalPurchasingPolicyRejectionProfile?,
        val employeePurchasingPolicyRejection: RuntimeEmployeePurchasingPolicyRejectionHistoryAuthorization?,
        val orderReassignmentRejection: RuntimeHistoricalOrderReassignmentRejectionProfile?,
        val employeeOrderReassignmentRejection: RuntimeEmployeeOrderReassignmentRejectionHistoryAuthorization?,
        val openingSupplierObligationRejection: RuntimeHistoricalOpeningSupplierObligationRejectionProfile?,
        val employeeOpeningSupplierObligationRejection: RuntimeEmployeeOpeningSupplierObligationRejectionHistoryAuthorization?,
        val invoiceCaptureRejection: RuntimeHistoricalInvoiceCaptureRejectionProfile?,
        val employeeInvoiceCaptureRejection: RuntimeEmployeeInvoiceCaptureRejectionHistoryAuthorization?,
        val capturedInvoiceMatchRejection: RuntimeHistoricalCapturedInvoiceMatchRejectionProfile?,
        val employeeCapturedInvoiceMatchRejection: RuntimeEmployeeCapturedInvoiceMatchRejectionHistoryAuthorization?,
        val branchActivityActivationRejection: RuntimeHistoricalBranchActivityActivationRejectionProfile?,
        val employeeBranchActivityActivationRejection: RuntimeEmployeeBranchActivityActivationRejectionHistoryAuthorization?,
        val floorConfigurationRejection: RuntimeHistoricalFloorConfigurationRejectionProfile?,
        val employeeFloorConfigurationRejection: RuntimeEmployeeFloorConfigurationRejectionHistoryAuthorization?,
        val configurationRejection: RuntimeHistoricalConfigurationRejectionProfile?,
        val employeeConfigurationRejection: RuntimeEmployeeConfigurationRejectionHistoryAuthorization?,
        val returnRightsActivationRejection: RuntimeHistoricalReturnRightsActivationRejectionProfile?,
        val employeeReturnRightsActivationRejection: RuntimeEmployeeReturnRightsActivationRejectionHistoryAuthorization?,
        val retailCustomerReceiptRejection: RuntimeHistoricalRetailCustomerReceiptRejectionProfile?,
        val employeeRetailCustomerReceiptRejection: RuntimeEmployeeRetailCustomerReceiptRejectionHistoryAuthorization?,
        val returnCaseQualificationRejection: RuntimeHistoricalReturnCaseQualificationRejectionProfile?,
        val employeeReturnCaseQualificationRejection: RuntimeEmployeeReturnCaseQualificationRejectionHistoryAuthorization?,
        val returnStockDispositionRejection: RuntimeHistoricalReturnStockDispositionRejectionProfile?,
        val employeeReturnStockDispositionRejection: RuntimeEmployeeReturnStockDispositionRejectionHistoryAuthorization?,
        val foodCheckRejection: RuntimeHistoricalFoodCheckRejectionProfile?,
        val employeeFoodCheckRejection: RuntimeEmployeeFoodCheckRejectionHistoryAuthorization?,
        val foodPaymentRejection: RuntimeHistoricalFoodPaymentRejectionProfile?,
        val employeeFoodPaymentRejection: RuntimeEmployeeFoodPaymentRejectionHistoryAuthorization?,
        val approvalHistory: RuntimeActionApprovalHistoryAuthorization? = null,
    ) {
        init {
            historyShapeV2((employee == null) != (nativeOriginal == null) &&
                (employeeHistory == null || employee != null && employeeHistory.evidence === employee) &&
                (nativeEmployeeStart == null || nativeOriginal != null && nativeEmployeeStart.employeeHistory != null &&
                    nativeEmployeeStart.nativeOriginal == null) && (employeeHistory == null || nativeEmployeeStart == null) &&
                (supervisorReview == null || employee != null && nativeOriginal == null && employeeHistory == null &&
                    nativeEmployeeStart == null && supervisorReview.matches(header, employee)) &&
                (employeeSupervisorReview == null || supervisorReview != null && employee != null &&
                    employeeSupervisorReview.profile == supervisorReview && employeeSupervisorReview.evidence === employee) &&
                (purchasingPolicyRejection == null || employee != null && nativeOriginal == null && employeeHistory == null &&
                    nativeEmployeeStart == null && supervisorReview == null && employeeSupervisorReview == null &&
                    purchasingPolicyRejection.matches(header, employee)) &&
                (employeePurchasingPolicyRejection == null || purchasingPolicyRejection != null && employee != null &&
                    employeePurchasingPolicyRejection.profile == purchasingPolicyRejection && employeePurchasingPolicyRejection.evidence === employee) &&
                (orderReassignmentRejection == null || employee != null && nativeOriginal == null && employeeHistory == null &&
                    nativeEmployeeStart == null && supervisorReview == null && employeeSupervisorReview == null &&
                    purchasingPolicyRejection == null && employeePurchasingPolicyRejection == null &&
                    orderReassignmentRejection.matches(header, employee)) &&
                (employeeOrderReassignmentRejection == null || orderReassignmentRejection != null && employee != null &&
                    employeeOrderReassignmentRejection.profile == orderReassignmentRejection && employeeOrderReassignmentRejection.evidence === employee) &&
                (openingSupplierObligationRejection == null || employee != null && nativeOriginal == null && employeeHistory == null &&
                    nativeEmployeeStart == null && supervisorReview == null && employeeSupervisorReview == null &&
                    purchasingPolicyRejection == null && employeePurchasingPolicyRejection == null &&
                    orderReassignmentRejection == null && employeeOrderReassignmentRejection == null &&
                    openingSupplierObligationRejection.matches(header, employee)) &&
                (employeeOpeningSupplierObligationRejection == null || openingSupplierObligationRejection != null && employee != null &&
                    employeeOpeningSupplierObligationRejection.profile == openingSupplierObligationRejection &&
                    employeeOpeningSupplierObligationRejection.evidence === employee) &&
                (invoiceCaptureRejection == null || employee != null && nativeOriginal == null && employeeHistory == null &&
                    nativeEmployeeStart == null && supervisorReview == null && employeeSupervisorReview == null &&
                    purchasingPolicyRejection == null && employeePurchasingPolicyRejection == null &&
                    orderReassignmentRejection == null && employeeOrderReassignmentRejection == null &&
                    openingSupplierObligationRejection == null && employeeOpeningSupplierObligationRejection == null &&
                    branchActivityActivationRejection == null && employeeBranchActivityActivationRejection == null &&
                    floorConfigurationRejection == null && employeeFloorConfigurationRejection == null &&
                    configurationRejection == null && employeeConfigurationRejection == null &&
                    returnRightsActivationRejection == null && employeeReturnRightsActivationRejection == null &&
                    retailCustomerReceiptRejection == null && employeeRetailCustomerReceiptRejection == null &&
                    foodCheckRejection == null && employeeFoodCheckRejection == null &&
                    returnCaseQualificationRejection == null && employeeReturnCaseQualificationRejection == null &&
                    returnStockDispositionRejection == null && employeeReturnStockDispositionRejection == null &&
                    approvalHistory == null && invoiceCaptureRejection.matches(header, employee)) &&
                (employeeInvoiceCaptureRejection == null || invoiceCaptureRejection != null && employee != null &&
                    employeeInvoiceCaptureRejection.profile == invoiceCaptureRejection &&
                    employeeInvoiceCaptureRejection.evidence === employee) &&
                (capturedInvoiceMatchRejection == null || employee != null && nativeOriginal == null && employeeHistory == null &&
                    nativeEmployeeStart == null && supervisorReview == null && employeeSupervisorReview == null &&
                    purchasingPolicyRejection == null && employeePurchasingPolicyRejection == null &&
                    orderReassignmentRejection == null && employeeOrderReassignmentRejection == null &&
                    openingSupplierObligationRejection == null && employeeOpeningSupplierObligationRejection == null &&
                    invoiceCaptureRejection == null && employeeInvoiceCaptureRejection == null &&
                    branchActivityActivationRejection == null && employeeBranchActivityActivationRejection == null &&
                    floorConfigurationRejection == null && employeeFloorConfigurationRejection == null &&
                    configurationRejection == null && employeeConfigurationRejection == null &&
                    returnRightsActivationRejection == null && employeeReturnRightsActivationRejection == null &&
                    retailCustomerReceiptRejection == null && employeeRetailCustomerReceiptRejection == null &&
                    foodCheckRejection == null && employeeFoodCheckRejection == null &&
                    returnCaseQualificationRejection == null && employeeReturnCaseQualificationRejection == null &&
                    returnStockDispositionRejection == null && employeeReturnStockDispositionRejection == null &&
                    approvalHistory == null && capturedInvoiceMatchRejection.matches(header, employee)) &&
                (employeeCapturedInvoiceMatchRejection == null || capturedInvoiceMatchRejection != null && employee != null &&
                    employeeCapturedInvoiceMatchRejection.profile == capturedInvoiceMatchRejection &&
                    employeeCapturedInvoiceMatchRejection.evidence === employee) &&
                (branchActivityActivationRejection == null || employee != null && nativeOriginal == null && employeeHistory == null &&
                    nativeEmployeeStart == null && supervisorReview == null && employeeSupervisorReview == null &&
                    purchasingPolicyRejection == null && employeePurchasingPolicyRejection == null &&
                    orderReassignmentRejection == null && employeeOrderReassignmentRejection == null &&
                    openingSupplierObligationRejection == null && employeeOpeningSupplierObligationRejection == null &&
                    branchActivityActivationRejection.matches(header, employee)) &&
                (employeeBranchActivityActivationRejection == null || branchActivityActivationRejection != null && employee != null &&
                    employeeBranchActivityActivationRejection.profile == branchActivityActivationRejection &&
                    employeeBranchActivityActivationRejection.evidence === employee) &&
                (floorConfigurationRejection == null || employee != null && nativeOriginal == null && employeeHistory == null &&
                    nativeEmployeeStart == null && supervisorReview == null && employeeSupervisorReview == null &&
                    purchasingPolicyRejection == null && employeePurchasingPolicyRejection == null &&
                    orderReassignmentRejection == null && employeeOrderReassignmentRejection == null &&
                    openingSupplierObligationRejection == null && employeeOpeningSupplierObligationRejection == null &&
                    branchActivityActivationRejection == null && employeeBranchActivityActivationRejection == null &&
                    floorConfigurationRejection.matches(header, employee)) &&
                (employeeFloorConfigurationRejection == null || floorConfigurationRejection != null && employee != null &&
                    employeeFloorConfigurationRejection.profile == floorConfigurationRejection &&
                    employeeFloorConfigurationRejection.evidence === employee) &&
                (configurationRejection == null || employee != null && nativeOriginal == null && employeeHistory == null &&
                    nativeEmployeeStart == null && supervisorReview == null && employeeSupervisorReview == null &&
                    purchasingPolicyRejection == null && employeePurchasingPolicyRejection == null &&
                    orderReassignmentRejection == null && employeeOrderReassignmentRejection == null &&
                    openingSupplierObligationRejection == null && employeeOpeningSupplierObligationRejection == null &&
                    branchActivityActivationRejection == null && employeeBranchActivityActivationRejection == null &&
                    floorConfigurationRejection == null && employeeFloorConfigurationRejection == null &&
                    returnRightsActivationRejection == null && employeeReturnRightsActivationRejection == null &&
                    retailCustomerReceiptRejection == null && employeeRetailCustomerReceiptRejection == null &&
                    returnCaseQualificationRejection == null && employeeReturnCaseQualificationRejection == null &&
                    returnStockDispositionRejection == null && employeeReturnStockDispositionRejection == null &&
                    configurationRejection.matches(header, employee)) &&
                (employeeConfigurationRejection == null || configurationRejection != null && employee != null &&
                    employeeConfigurationRejection.profile == configurationRejection &&
                    employeeConfigurationRejection.evidence === employee) &&
                (returnRightsActivationRejection == null || employee != null && nativeOriginal == null && employeeHistory == null &&
                    nativeEmployeeStart == null && supervisorReview == null && employeeSupervisorReview == null &&
                    purchasingPolicyRejection == null && employeePurchasingPolicyRejection == null &&
                    orderReassignmentRejection == null && employeeOrderReassignmentRejection == null &&
                    openingSupplierObligationRejection == null && employeeOpeningSupplierObligationRejection == null &&
                    branchActivityActivationRejection == null && employeeBranchActivityActivationRejection == null &&
                    floorConfigurationRejection == null && employeeFloorConfigurationRejection == null &&
                    configurationRejection == null && employeeConfigurationRejection == null &&
                    returnCaseQualificationRejection == null && employeeReturnCaseQualificationRejection == null &&
                    returnStockDispositionRejection == null && employeeReturnStockDispositionRejection == null &&
                    returnRightsActivationRejection.matches(header, employee)) &&
                (employeeReturnRightsActivationRejection == null || returnRightsActivationRejection != null && employee != null &&
                    employeeReturnRightsActivationRejection.profile == returnRightsActivationRejection &&
                    employeeReturnRightsActivationRejection.evidence === employee) &&
                (retailCustomerReceiptRejection == null || employee != null && nativeOriginal == null && employeeHistory == null &&
                    nativeEmployeeStart == null && supervisorReview == null && employeeSupervisorReview == null &&
                    purchasingPolicyRejection == null && employeePurchasingPolicyRejection == null &&
                    orderReassignmentRejection == null && employeeOrderReassignmentRejection == null &&
                    openingSupplierObligationRejection == null && employeeOpeningSupplierObligationRejection == null &&
                    branchActivityActivationRejection == null && employeeBranchActivityActivationRejection == null &&
                    floorConfigurationRejection == null && employeeFloorConfigurationRejection == null &&
                    configurationRejection == null && employeeConfigurationRejection == null &&
                    returnRightsActivationRejection == null && employeeReturnRightsActivationRejection == null &&
                    returnCaseQualificationRejection == null && employeeReturnCaseQualificationRejection == null &&
                    returnStockDispositionRejection == null && employeeReturnStockDispositionRejection == null &&
                    retailCustomerReceiptRejection.matches(header, employee)) &&
                (employeeRetailCustomerReceiptRejection == null || retailCustomerReceiptRejection != null && employee != null &&
                    employeeRetailCustomerReceiptRejection.profile == retailCustomerReceiptRejection &&
                    employeeRetailCustomerReceiptRejection.evidence === employee) &&
                (returnCaseQualificationRejection == null || employee != null && nativeOriginal == null && employeeHistory == null &&
                    nativeEmployeeStart == null && supervisorReview == null && employeeSupervisorReview == null &&
                    purchasingPolicyRejection == null && employeePurchasingPolicyRejection == null &&
                    orderReassignmentRejection == null && employeeOrderReassignmentRejection == null &&
                    openingSupplierObligationRejection == null && employeeOpeningSupplierObligationRejection == null &&
                    branchActivityActivationRejection == null && employeeBranchActivityActivationRejection == null &&
                    floorConfigurationRejection == null && employeeFloorConfigurationRejection == null &&
                    configurationRejection == null && employeeConfigurationRejection == null &&
                    returnRightsActivationRejection == null && employeeReturnRightsActivationRejection == null &&
                    retailCustomerReceiptRejection == null && employeeRetailCustomerReceiptRejection == null &&
                    returnStockDispositionRejection == null && employeeReturnStockDispositionRejection == null &&
                    approvalHistory == null && returnCaseQualificationRejection.matches(header, employee)) &&
                (employeeReturnCaseQualificationRejection == null || returnCaseQualificationRejection != null && employee != null &&
                    employeeReturnCaseQualificationRejection.profile == returnCaseQualificationRejection &&
                    employeeReturnCaseQualificationRejection.evidence === employee) &&
                (returnStockDispositionRejection == null || employee != null && nativeOriginal == null && employeeHistory == null &&
                    nativeEmployeeStart == null && supervisorReview == null && employeeSupervisorReview == null &&
                    purchasingPolicyRejection == null && employeePurchasingPolicyRejection == null &&
                    orderReassignmentRejection == null && employeeOrderReassignmentRejection == null &&
                    openingSupplierObligationRejection == null && employeeOpeningSupplierObligationRejection == null &&
                    branchActivityActivationRejection == null && employeeBranchActivityActivationRejection == null &&
                    floorConfigurationRejection == null && employeeFloorConfigurationRejection == null &&
                    configurationRejection == null && employeeConfigurationRejection == null &&
                    returnRightsActivationRejection == null && employeeReturnRightsActivationRejection == null &&
                    retailCustomerReceiptRejection == null && employeeRetailCustomerReceiptRejection == null &&
                    returnCaseQualificationRejection == null && employeeReturnCaseQualificationRejection == null &&
                    approvalHistory == null && returnStockDispositionRejection.matches(header, employee)) &&
                (employeeReturnStockDispositionRejection == null || returnStockDispositionRejection != null && employee != null &&
                    employeeReturnStockDispositionRejection.profile == returnStockDispositionRejection &&
                    employeeReturnStockDispositionRejection.evidence === employee) &&
                (approvalHistory == null || employee != null && approvalHistory.evidence === employee && nativeOriginal == null &&
                    employeeHistory == null && nativeEmployeeStart == null && supervisorReview == null && employeeSupervisorReview == null &&
                    purchasingPolicyRejection == null && employeePurchasingPolicyRejection == null && orderReassignmentRejection == null &&
                    employeeOrderReassignmentRejection == null && openingSupplierObligationRejection == null &&
                    employeeOpeningSupplierObligationRejection == null && branchActivityActivationRejection == null &&
                    employeeBranchActivityActivationRejection == null && floorConfigurationRejection == null &&
                    employeeFloorConfigurationRejection == null && configurationRejection == null && employeeConfigurationRejection == null &&
                    returnRightsActivationRejection == null && employeeReturnRightsActivationRejection == null &&
                    retailCustomerReceiptRejection == null && employeeRetailCustomerReceiptRejection == null &&
                    returnCaseQualificationRejection == null && employeeReturnCaseQualificationRejection == null &&
                    returnStockDispositionRejection == null && employeeReturnStockDispositionRejection == null) &&
                (foodCheckRejection == null || employee != null && nativeOriginal == null && employeeHistory == null &&
                    nativeEmployeeStart == null && supervisorReview == null && employeeSupervisorReview == null &&
                    purchasingPolicyRejection == null && employeePurchasingPolicyRejection == null &&
                    orderReassignmentRejection == null && employeeOrderReassignmentRejection == null &&
                    openingSupplierObligationRejection == null && employeeOpeningSupplierObligationRejection == null &&
                    branchActivityActivationRejection == null && employeeBranchActivityActivationRejection == null &&
                    floorConfigurationRejection == null && employeeFloorConfigurationRejection == null &&
                    configurationRejection == null && employeeConfigurationRejection == null &&
                    returnRightsActivationRejection == null && employeeReturnRightsActivationRejection == null &&
                    retailCustomerReceiptRejection == null && employeeRetailCustomerReceiptRejection == null &&
                    returnCaseQualificationRejection == null && employeeReturnCaseQualificationRejection == null &&
                    returnStockDispositionRejection == null && employeeReturnStockDispositionRejection == null &&
                    approvalHistory == null && foodCheckRejection.matches(header, employee)) &&
                (employeeFoodCheckRejection == null || foodCheckRejection != null && employee != null &&
                    employeeFoodCheckRejection.profile == foodCheckRejection && employeeFoodCheckRejection.evidence === employee))
            historyShapeV2((foodPaymentRejection == null || employee != null && nativeOriginal == null &&
                employeeHistory == null && nativeEmployeeStart == null && approvalHistory == null &&
                supervisorReview == null && employeeSupervisorReview == null &&
                purchasingPolicyRejection == null && employeePurchasingPolicyRejection == null &&
                orderReassignmentRejection == null && employeeOrderReassignmentRejection == null &&
                openingSupplierObligationRejection == null && employeeOpeningSupplierObligationRejection == null &&
                invoiceCaptureRejection == null && employeeInvoiceCaptureRejection == null &&
                capturedInvoiceMatchRejection == null && employeeCapturedInvoiceMatchRejection == null &&
                branchActivityActivationRejection == null && employeeBranchActivityActivationRejection == null &&
                floorConfigurationRejection == null && employeeFloorConfigurationRejection == null &&
                configurationRejection == null && employeeConfigurationRejection == null &&
                returnRightsActivationRejection == null && employeeReturnRightsActivationRejection == null &&
                retailCustomerReceiptRejection == null && employeeRetailCustomerReceiptRejection == null &&
                returnCaseQualificationRejection == null && employeeReturnCaseQualificationRejection == null &&
                returnStockDispositionRejection == null && employeeReturnStockDispositionRejection == null &&
                foodCheckRejection == null && employeeFoodCheckRejection == null && foodPaymentRejection.matches(header, employee)) &&
                (employeeFoodPaymentRejection == null || foodPaymentRejection != null && employee != null &&
                    employeeFoodPaymentRejection.profile == foodPaymentRejection && employeeFoodPaymentRejection.evidence === employee))
        }
        // Native protected images have completed before an installed original producer can
        // observe this DATA. The earlier EVENTS capture retains only the registered proof.
        val originalApproval: RuntimeHistoricalActionApproval? get() = approvalHistory?.originalApproval()
        val approvalRejected: Boolean get() = approvalHistory?.wrappedEvidence?.approvalUse?.kind ==
            RuntimeActionApprovalUseKindV1.REJECTED_UNCONSUMED
        val producer: StreamedProducerIdentityV2 get() = nativeOriginal?.producer ?: checkNotNull(employee).let {
            StreamedProducerIdentityV2(it.operationId, CommandKey(it.commandName, it.commandVersion), it.authorization.actorId, it.semanticDigest)
        }
        val scope: BranchScope get() = nativeOriginal?.scope ?: checkNotNull(employee).authorization.scope
        val authenticationId get() = nativeOriginal?.provenance?.historicalStart?.authenticationId ?:
            approvalHistory?.businessAuthenticationId ?: checkNotNull(employee).authorization.sessionId
        val businessGeneration: SessionGeneration get() = approvalHistory?.businessGeneration ?: checkNotNull(employee).authorization.generation
        val policyRevision get() = nativeOriginal?.provenance?.historicalStart?.policyRevision ?: checkNotNull(employee).authorization.policyRevision
        var joined: Boolean = false
        fun clear() { employeeHistory?.clear(); employeeSupervisorReview?.clear(); employeePurchasingPolicyRejection?.clear()
            employeeOrderReassignmentRejection?.clear()
            employeeOpeningSupplierObligationRejection?.clear()
            employeeInvoiceCaptureRejection?.clear()
            employeeCapturedInvoiceMatchRejection?.clear()
            employeeBranchActivityActivationRejection?.clear()
            employeeFloorConfigurationRejection?.clear()
            employeeConfigurationRejection?.clear()
            employeeReturnRightsActivationRejection?.clear()
            employeeRetailCustomerReceiptRejection?.clear()
            employeeFoodCheckRejection?.clear()
            employeeFoodPaymentRejection?.clear()
            employeeReturnCaseQualificationRejection?.clear()
            employeeReturnStockDispositionRejection?.clear()
            dependencies.fill(0); mutations.forEach { it.clear() } }
    }

    fun captureFoundingRecipientOrigin(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        replay: RuntimeOriginalAuthenticationReplay) {
        if (enabled) recipients.founding(header, eventDigest, replay)
    }
    fun captureEmployeeRecipientOrigin(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        replay: RuntimeEmployeeAuthenticationReplay) {
        if (enabled) recipients.enrollment(header, eventDigest, replay)
    }
    fun captureRecipientAccountAfter(header: RuntimeHistoryEventHeaderV2, replay: RuntimeOriginalAuthenticationReplay) {
        if (enabled) recipients.accountAfter(header, replay)
    }
    fun captureRecipientAccountAfter(header: RuntimeHistoryEventHeaderV2, replay: RuntimeEmployeeAuthenticationReplay) {
        if (enabled) recipients.accountAfter(header, replay)
    }
    fun captureRecipientPolicyAfter(header: RuntimeHistoryEventHeaderV2, replay: RuntimeOriginalAuthenticationReplay) {
        if (enabled) recipients.policyAfter(header, replay)
    }
    fun captureRecipientPolicyAfter(header: RuntimeHistoryEventHeaderV2, replay: RuntimeEmployeeAuthenticationReplay) {
        if (enabled) recipients.policyAfter(header, replay)
    }
    fun closeRecipientEvents() { if (enabled) recipients.closeEvents() }
    fun abandonRecipientHistory() {
        recipients.abandon()
        if (coldStore != null) {
            val released = coldRecipientBytes; coldRecipientBytes = 0L
            retainedBytes -= released; coldStore.releaseOwnedBytes(released)
        }
    }

    fun capture(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest, evidence: RuntimeCommandAuthorizationEvidenceV1,
        intent: NativeHistoryOriginalIntentV2, dependencies: ByteArray,
        mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        if (!enabled || header.purpose != RuntimeWritePurposeV2.COMMAND_APPLY || evidence.actionApprovalId != null ||
            CommandKey(evidence.commandName, evidence.commandVersion) !in supportedCommands ||
            mutations.size != header.changeCount || mutations.any { RuntimeHistoryTableV2.fromTag(it.tableTag) !in TABLES }) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            CommandKey(evidence.commandName, evidence.commandVersion), evidence.authorization.actorId, evidence.semanticDigest),
            evidence.authorization.scope, evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations)
    }

    /** Separate registered approved lane, never a relaxation of direct/legacy capture.
     * Every protected sibling must be in the owner's exact complete use census. Reject is
     * the closed five-row nonbusiness terminal result and never consumes the approval. */
    fun captureApproved(proof: RuntimeActionApprovalHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val header = proof.header; val evidence = proof.evidence
        val command = CommandKey(evidence.commandName, evidence.commandVersion)
        if (!enabled || command !in supportedCommands || proof.requiresPreservedBusinessAttribution &&
            attributionSupport[command] != ActionApprovalBusinessAttributionSupport.PRESERVE_ORIGINAL_REQUESTER) return false
        proof.requireCaptured(session, header, proof.eventDigest, evidence)
        proof.requireIntent(intent); proof.requireVersions(mutations)
        val rejected = proof.wrappedEvidence.approvalUse.kind == RuntimeActionApprovalUseKindV1.REJECTED_UNCONSUMED
        val businessTables = if (rejected) REJECTION_TABLES else TABLES
        if (mutations.any { version ->
                val table = RuntimeHistoryTableV2.fromTag(version.tableTag)
                table !in businessTables && table !in setOf(RuntimeHistoryTableV2.APPROVAL, RuntimeHistoryTableV2.AUTH_EVENT)
            }) return false
        historyShapeV2(evidence.actionApprovalId != null && evidence.authorization.scope == scope &&
            header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.deviceId == coordinates.deviceId &&
            (!rejected || mutations.size == REJECTION_TABLES.size && mutations.all { RuntimeHistoryTableV2.fromTag(it.tableTag) in REJECTION_TABLES }))
        return captureBound(header, proof.eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            CommandKey(evidence.commandName, evidence.commandVersion), evidence.authorization.actorId, evidence.semanticDigest),
            evidence.authorization.scope, evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations, approvalHistory = proof)
    }

    /** Distinct enrolled-employee path. An actor/session/hash tuple or policy Boolean cannot
     * enter it: the exact native-seeded event-order proof must bind this same actual read.
     * The original founding capture predicate above is deliberately unchanged. */
    fun captureEmployee(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        proof: RuntimeEmployeeCommandHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val evidence = proof.evidence
        if (!enabled || header.purpose != RuntimeWritePurposeV2.COMMAND_APPLY || evidence.actionApprovalId != null ||
            CommandKey(evidence.commandName, evidence.commandVersion) !in supportedCommands ||
            mutations.size != header.changeCount || mutations.any { RuntimeHistoryTableV2.fromTag(it.tableTag) !in TABLES }) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId != coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        proof.bind(session, header, eventDigest)
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            CommandKey(evidence.commandName, evidence.commandVersion), evidence.authorization.actorId, evidence.semanticDigest),
            evidence.authorization.scope, evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations,
            employeeHistory = proof)
    }

    /** CLOSED founding-actor audited Review capture. The unchanged founding replay is
     * invoked only after this profile's exact rejection gate; no generic Reject is captured. */
    fun captureSupervisorReview(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val profile = RuntimeHistoricalSupervisorReviewProfile.fromCommand(CommandKey(evidence.commandName, evidence.commandVersion)) ?: return false
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalSupervisorReviewProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations, supervisorReview = profile)
    }

    /** Separate registered enrolled-employee Review proof; the Apply-only employee command
     * proof and native Start authorization are deliberately not reused or relaxed. */
    fun captureEmployeeSupervisorReview(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        proof: RuntimeEmployeeSupervisorReviewHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val evidence = proof.evidence; val profile = proof.profile
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalSupervisorReviewProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId != coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        proof.bind(session, header, eventDigest)
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations,
            supervisorReview = profile, employeeSupervisorReview = proof)
    }

    /** Exact founding-actor policy rejection capture, after original interactive replay.
     * Only this command/requirement and five non-domain mutations can enter the lane. */
    fun capturePurchasingPolicyRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val profile = RuntimeHistoricalPurchasingPolicyRejectionProfile.fromCommand(CommandKey(evidence.commandName, evidence.commandVersion)) ?: return false
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalPurchasingPolicyRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations, purchasingPolicyRejection = profile)
    }

    /** Distinct registered native-enrolled authorization; no supervisor or Apply proof is
     * reused. The later business pass must still derive the exact original refusal. */
    fun captureEmployeePurchasingPolicyRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        proof: RuntimeEmployeePurchasingPolicyRejectionHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val evidence = proof.evidence; val profile = proof.profile
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalPurchasingPolicyRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId != coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        proof.bind(session, header, eventDigest)
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations,
            purchasingPolicyRejection = profile, employeePurchasingPolicyRejection = proof)
    }

    /** Provisional founding-operator capture for the exact reassignment requirement and
     * five nonbusiness tables. The installed sales producer must still prove the exact
     * Legacy subtype, first refusal and resources before this candidate can complete. */
    fun captureOrderReassignmentRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val profile = RuntimeHistoricalOrderReassignmentRejectionProfile.fromCommand(CommandKey(evidence.commandName, evidence.commandVersion)) ?: return false
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalOrderReassignmentRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations, orderReassignmentRejection = profile)
    }

    /** Distinct native-enrolled rejection proof, never the Apply-only command proof or a
     * current/recipient grant. Registration and all final native joins remain mandatory. */
    fun captureEmployeeOrderReassignmentRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        proof: RuntimeEmployeeOrderReassignmentRejectionHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val evidence = proof.evidence; val profile = proof.profile
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalOrderReassignmentRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId != coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        proof.bind(session, header, eventDigest)
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations,
            orderReassignmentRejection = profile, employeeOrderReassignmentRejection = proof)
    }

    /** CLOSED founding-actor opening-obligation refusal after unchanged original interactive
     * replay. The installed producer must still derive the canonical first refusal from
     * genuine BEFORE sources; only the five fixed nonbusiness mutations are captured. */
    fun captureOpeningSupplierObligationRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val profile = RuntimeHistoricalOpeningSupplierObligationRejectionProfile.fromCommand(CommandKey(evidence.commandName, evidence.commandVersion)) ?: return false
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalOpeningSupplierObligationRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations, openingSupplierObligationRejection = profile)
    }

    /** Distinct registered original native-enrolled operator proof. Apply-only command,
     * native Start and other rejection proofs cannot enter this finite amount-bound lane. */
    fun captureEmployeeOpeningSupplierObligationRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        proof: RuntimeEmployeeOpeningSupplierObligationRejectionHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val evidence = proof.evidence; val profile = proof.profile
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalOpeningSupplierObligationRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId != coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        proof.bind(session, header, eventDigest)
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations,
            openingSupplierObligationRejection = profile, employeeOpeningSupplierObligationRejection = proof)
    }

    /** CLOSED founding-actor V2 invoice-capture refusal after original interactive replay.
     * Only the exact capture requirement and five nonbusiness mutations enter this lane;
     * the installed producer still derives the canonical first refusal from BEFORE sources. */
    fun captureInvoiceCaptureRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val profile = RuntimeHistoricalInvoiceCaptureRejectionProfile.fromCommand(CommandKey(evidence.commandName, evidence.commandVersion)) ?: return false
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalInvoiceCaptureRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations, invoiceCaptureRejection = profile)
    }

    /** Distinct original native-enrolled operator proof for a V2 capture refusal. Its
     * native read and policy remain bound while exact sources/result/mutations still join. */
    fun captureEmployeeInvoiceCaptureRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        proof: RuntimeEmployeeInvoiceCaptureRejectionHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val evidence = proof.evidence; val profile = proof.profile
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalInvoiceCaptureRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId != coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        proof.bind(session, header, eventDigest)
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations,
            invoiceCaptureRejection = profile, employeeInvoiceCaptureRejection = proof)
    }

    /** CLOSED founding-actor matching/recognition refusal after original interactive replay.
     * The actual full-claim requirement and five nonbusiness mutations stay provisional until
     * the installed purchasing producer joins the exact first refusal and native sources. */
    fun captureCapturedInvoiceMatchRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val profile = RuntimeHistoricalCapturedInvoiceMatchRejectionProfile.fromCommand(CommandKey(evidence.commandName, evidence.commandVersion)) ?: return false
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalCapturedInvoiceMatchRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations, capturedInvoiceMatchRejection = profile)
    }

    /** Distinct registered enrolled-operator proof. The original native enrollment, event
     * and policy remain bound; no Apply, capture-only or approval proof enters this lane. */
    fun captureEmployeeCapturedInvoiceMatchRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        proof: RuntimeEmployeeCapturedInvoiceMatchRejectionHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val evidence = proof.evidence; val profile = proof.profile
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalCapturedInvoiceMatchRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId != coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        proof.bind(session, header, eventDigest)
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations,
            capturedInvoiceMatchRejection = profile, employeeCapturedInvoiceMatchRejection = proof)
    }

    /** CLOSED founding-actor initial-activity refusal after unchanged original interactive
     * replay. Only the exact requirement and five nonbusiness mutations enter this lane;
     * the installed activity producer must still prove the candidate and prior activation. */
    fun captureBranchActivityActivationRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val profile = RuntimeHistoricalBranchActivityActivationRejectionProfile.fromCommand(CommandKey(evidence.commandName, evidence.commandVersion)) ?: return false
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalBranchActivityActivationRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations, branchActivityActivationRejection = profile)
    }

    /** Distinct registered native-enrolled initial-activity proof. Apply, supervisor,
     * purchasing, native Start/import and current employee/recipient grants cannot enter
     * it; every original source, refusal/result/resource and native mutation still joins. */
    fun captureEmployeeBranchActivityActivationRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        proof: RuntimeEmployeeBranchActivityActivationRejectionHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val evidence = proof.evidence; val profile = proof.profile
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalBranchActivityActivationRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId != coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        proof.bind(session, header, eventDigest)
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations,
            branchActivityActivationRejection = profile, employeeBranchActivityActivationRejection = proof)
    }

    /** CLOSED founding-actor floor/table refusal after unchanged original interactive
     * replay. Only the exact configuration requirement and five nonbusiness mutations
     * enter; the directory producer must still prove the native BEFORE first refusal. */
    fun captureFloorConfigurationRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val profile = RuntimeHistoricalFloorConfigurationRejectionProfile.fromCommand(CommandKey(evidence.commandName, evidence.commandVersion)) ?: return false
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalFloorConfigurationRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations, floorConfigurationRejection = profile)
    }

    /** Distinct registered native-enrolled directory refusal proof. Apply, other refusal
     * proofs, native Start/import and current grants cannot enter. Exact original sources,
     * requirement, first refusal/result/resources and every native mutation still join. */
    fun captureEmployeeFloorConfigurationRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        proof: RuntimeEmployeeFloorConfigurationRejectionHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val evidence = proof.evidence; val profile = proof.profile
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalFloorConfigurationRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId != coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        proof.bind(session, header, eventDigest)
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations,
            floorConfigurationRejection = profile, employeeFloorConfigurationRejection = proof)
    }

    /** CLOSED founding-actor configuration candidate after unchanged original interactive
     * replay. Only the bounded requirement and five nonbusiness mutations enter; the
     * installed producer must still prove the exact subtype and native BEFORE first refusal. */
    fun captureConfigurationRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val profile = RuntimeHistoricalConfigurationRejectionProfile.fromCommand(CommandKey(evidence.commandName, evidence.commandVersion)) ?: return false
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalConfigurationRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations, configurationRejection = profile)
    }

    /** Separate registered native-enrolled configuration/pricing refusal proof. Apply, other
     * rejection proofs, native Start/import and current grants cannot enter. Every original
     * source, requirement, first refusal/result/resource and native mutation still joins. */
    fun captureEmployeeConfigurationRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        proof: RuntimeEmployeeConfigurationRejectionHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val evidence = proof.evidence; val profile = proof.profile
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalConfigurationRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId != coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        proof.bind(session, header, eventDigest)
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations,
            configurationRejection = profile, employeeConfigurationRejection = proof)
    }

    /** CLOSED founding-actor documentary return-rights refusal after unchanged original
     * interactive replay. Only the exact activation requirement and five nonbusiness
     * mutations enter; the installed producer must still prove positive signed native
     * BEFORE sources and the exact first refusal/result/resources. */
    fun captureReturnRightsActivationRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val profile = RuntimeHistoricalReturnRightsActivationRejectionProfile.fromCommand(CommandKey(evidence.commandName, evidence.commandVersion)) ?: return false
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalReturnRightsActivationRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations, returnRightsActivationRejection = profile)
    }

    /** Distinct registered native-enrolled documentary activation refusal proof. Apply,
     * other refusal proofs, native Start/import and current grants cannot enter. Exact
     * positive signed sources, original requirement, first refusal/result/resources and
     * every native mutation still join before this candidate becomes history proof. */
    fun captureEmployeeReturnRightsActivationRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        proof: RuntimeEmployeeReturnRightsActivationRejectionHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val evidence = proof.evidence; val profile = proof.profile
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalReturnRightsActivationRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId != coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        proof.bind(session, header, eventDigest)
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations,
            returnRightsActivationRejection = profile, employeeReturnRightsActivationRejection = proof)
    }

    /** CLOSED founding-actor receipt refusal after unchanged original interactive replay.
     * Only this collector-bound requirement and five nonbusiness mutations enter. Genuine
     * paid Finalize/receipt sources and the exact first refusal remain separate obligations. */
    fun captureRetailCustomerReceiptRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val profile = RuntimeHistoricalRetailCustomerReceiptRejectionProfile.fromCommand(CommandKey(evidence.commandName, evidence.commandVersion)) ?: return false
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalRetailCustomerReceiptRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations, retailCustomerReceiptRejection = profile)
    }

    /** Distinct native-enrolled receipt-refusal proof. No Apply, other rejection, native
     * Start/import or current grant is borrowed. Exact sources, original requirement,
     * first refusal/result/resources and all native mutations must still join. */
    fun captureEmployeeRetailCustomerReceiptRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        proof: RuntimeEmployeeRetailCustomerReceiptRejectionHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val evidence = proof.evidence; val profile = proof.profile
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalRetailCustomerReceiptRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId != coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        proof.bind(session, header, eventDigest)
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations,
            retailCustomerReceiptRejection = profile, employeeRetailCustomerReceiptRejection = proof)
    }

    /** Newly qualified FOOD scalar refusal only, after the original founding interactive
     * replay. The new-record marker does not replace the genuine BEFORE business join. */
    fun captureFoodCheckRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val profile = RuntimeHistoricalFoodCheckRejectionProfile.fromCommand(CommandKey(evidence.commandName, evidence.commandVersion)) ?: return false
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            !profile.hasMarker(header, evidence, dependencies) ||
            mutations.size != RuntimeHistoricalFoodCheckRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations, foodCheckRejection = profile)
    }

    /** Separate exact registered enrolled-employee FOOD refusal. No founding, Apply,
     * approval, native-original or other rejection proof can enter this lane. */
    fun captureEmployeeFoodCheckRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        proof: RuntimeEmployeeFoodCheckRejectionHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val evidence = proof.evidence; val profile = proof.profile
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            !profile.hasMarker(header, evidence, dependencies) ||
            mutations.size != RuntimeHistoricalFoodCheckRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId != coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        proof.bind(session, header, eventDigest)
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations,
            foodCheckRejection = profile, employeeFoodCheckRejection = proof)
    }

    /** CLOSED ordinary AlreadyApplied candidate from the original founding employee.
     * Genuine native application and earlier Apply/Start/raw writers still have to join. */
    fun captureFoodPaymentRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val profile = RuntimeHistoricalFoodPaymentRejectionProfile.fromCommand(CommandKey(evidence.commandName, evidence.commandVersion)) ?: return false
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalFoodPaymentRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations, foodPaymentRejection = profile)
    }

    /** Exact registered original enrolled-employee AlreadyApplied provenance.
     * Successful commands, approvals and other refusals cannot use this proof. */
    fun captureEmployeeFoodPaymentRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        proof: RuntimeEmployeeFoodPaymentRejectionHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val evidence = proof.evidence; val profile = proof.profile
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalFoodPaymentRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId != coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        proof.bind(session, header, eventDigest)
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations,
            foodPaymentRejection = profile, employeeFoodPaymentRejection = proof)
    }

    /** Closed founding-actor QUALIFY/Accept refusal after unchanged original interactive
     * replay. This exact requirement and five nonbusiness mutations are only a candidate;
     * genuine CASE/entitlement, exact live dispatch and first refusal must still join. */
    fun captureReturnCaseQualificationRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val profile = RuntimeHistoricalReturnCaseQualificationRejectionProfile.fromCommand(CommandKey(evidence.commandName, evidence.commandVersion)) ?: return false
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalReturnCaseQualificationRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations, returnCaseQualificationRejection = profile)
    }

    /** Provisional founding-employee stale-disposition RETURN refusal capture after
     * genuine original interactive replay. Five nonbusiness changes and the exact
     * inventory requirement are only a candidate; first refusal/sources still join. */
    fun captureReturnStockDispositionRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val profile = RuntimeHistoricalReturnStockDispositionRejectionProfile.fromCommand(CommandKey(evidence.commandName, evidence.commandVersion)) ?: return false
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalReturnStockDispositionRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations, returnStockDispositionRejection = profile)
    }

    /** Distinct registered native-enrolled QUALIFY/Accept refusal proof. No Apply,
     * approval, other refusal, native Start/import or current grant is reused. Exact genuine
     * sources, original requirement/result/resources and every native mutation still join. */
    fun captureEmployeeReturnCaseQualificationRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        proof: RuntimeEmployeeReturnCaseQualificationRejectionHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val evidence = proof.evidence; val profile = proof.profile
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalReturnCaseQualificationRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId != coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        proof.bind(session, header, eventDigest)
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations,
            returnCaseQualificationRejection = profile, employeeReturnCaseQualificationRejection = proof)
    }

    /** Distinct native-enrolled stale-disposition RETURN refusal capture. Apply,
     * approval, native Start/import and other refusal proofs cannot enter this lane. */
    fun captureEmployeeReturnStockDispositionRejection(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        proof: RuntimeEmployeeReturnStockDispositionRejectionHistoryAuthorization, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        val evidence = proof.evidence; val profile = proof.profile
        if (!enabled || !profile.matches(header, evidence) || profile.command !in supportedCommands ||
            mutations.size != RuntimeHistoricalReturnStockDispositionRejectionProfile.CAPTURED_CHANGES ||
            mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() != REJECTION_TABLES) return false
        historyShapeV2(header.commandEpoch == epoch.value && header.assignmentId == coordinates.assignmentId &&
            evidence.authorization.actorId != coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            header.sequence !in captures && evidence.operationId !in byOperation)
        evidence.validate()
        proof.bind(session, header, eventDigest)
        return captureBound(header, eventDigest, evidence, null, StreamedProducerIdentityV2(evidence.operationId,
            profile.command, evidence.authorization.actorId, evidence.semanticDigest), evidence.authorization.scope,
            evidence.purposeTag, evidence.capturedChanges, intent, dependencies, mutations,
            returnStockDispositionRejection = profile, employeeReturnStockDispositionRejection = proof)
    }

    /** No authentication replay or current employee grant is involved. The genuine Start,
     * certificate, writer, sources and closed raw effects are joined after full lineages. */
    fun captureNative(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest, evidence: RuntimeNativeOriginalImportEvidenceV1,
        intent: NativeHistoryOriginalIntentV2, dependencies: ByteArray,
        mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        if (!enabled || header.purpose != RuntimeWritePurposeV2.COMMAND_APPLY || evidence.producer.command !in supportedCommands ||
            coldStore != null && (!supportsColdNativeOriginal(evidence) || !session.hasChronologicalAccountImageTraversal) ||
            mutations.size != RuntimeNativeOriginalImportEvidenceV1.CAPTURED_CHANGES || mutations.any {
                RuntimeHistoryTableV2.fromTag(it.tableTag) !in TABLES }) return false
        evidence.validate()
        val writer = evidence.provenance.currentWriter
        val binding = session.original.originalBinding.copyWireBytes()
        try { historyShapeV2(evidence.scope == scope && evidence.producer.originalActorId == coordinates.firstActorId &&
            writer.installationId == coordinates.installationId && writer.masterDeviceId == coordinates.deviceId &&
            writer.assignmentId == coordinates.assignmentId && writer.epoch == epoch && writer.runtimeProfile == SelfOwnedNativeHistoryWireV2.PROFILE &&
            writer.runtimeBindingCommitment == sha256(binding) && header.commandEpoch == epoch.value && header.assignmentId == writer.assignmentId &&
            evidence.provenance.runtimeParent.sequence.value == header.parentSequence &&
            evidence.provenance.runtimeParent.frameHash == header.parentDigest)
        } finally { binding.fill(0) }
        return captureBound(header, eventDigest, null, evidence, evidence.producer, evidence.scope, RuntimeWritePurposeV2.COMMAND_APPLY.tag,
            evidence.capturedChanges, intent, dependencies, mutations)
    }

    /** Read-only DATA in a genuinely earlier replay, never a joined command or source.
     * Its complete event commitment/canonical capsule has already streamed. Only this
     * closed unprotected seven-version shape can pass without running a security reducer;
     * every main-scan event and any actually requested original still needs the full join.
     * In particular, this does not authorize its decoded Start or financial application. */
    internal fun observeEarlierColdNativeOriginal(target: RuntimeHistoryColdReplayTarget,
        header: RuntimeHistoryEventHeaderV2, evidence: RuntimeNativeOriginalImportEvidenceV1,
        intent: NativeHistoryOriginalIntentV2, dependencies: ByteArray,
        mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        if (!enabled || coldStore == null || !session.hasChronologicalAccountImageTraversal ||
            evidence.producer.command !in supportedCommands ||
            header.purpose != RuntimeWritePurposeV2.COMMAND_APPLY ||
            mutations.size != RuntimeNativeOriginalImportEvidenceV1.CAPTURED_CHANGES) return false
        val domain = supportedColdNativeOriginalDomain(evidence) ?: return false
        val counts = mutations.groupingBy { RuntimeHistoryTableV2.fromTag(it.tableTag) }.eachCount()
        if (counts != COLD_NATIVE_ORIGINAL_TABLE_COUNTS) return false
        historyShapeV2(active == null && heldColdOriginal == null && currentFood == null && !historyComplete &&
            captures.isEmpty() && originals.isEmpty() && coldOriginalLoans.isEmpty() && coldOwnedCommandVersions == null &&
            (coldObservationTarget == null || coldObservationTarget === target) &&
            header.sequence > coldLastObservedNativeSequence && header.sequence < target.header.sequence &&
            header.commandSequence > coldLastObservedNativeCommandSequence && header.commandSequence < target.header.commandSequence &&
            header.scope == target.header.scope && header.assignmentId == target.header.assignmentId &&
            header.commandEpoch == target.header.commandEpoch && header.installationGenesisDigest == target.header.installationGenesisDigest)
        evidence.validate()
        val producer = evidence.producer
        val writer = evidence.provenance.currentWriter
        val binding = session.original.originalBinding.copyWireBytes()
        try { historyShapeV2(evidence.scope == scope && writer.installationId == coordinates.installationId &&
            writer.masterDeviceId == coordinates.deviceId && writer.assignmentId == coordinates.assignmentId && writer.epoch == epoch &&
            writer.runtimeProfile == SelfOwnedNativeHistoryWireV2.PROFILE && writer.runtimeBindingCommitment == sha256(binding) &&
            header.commandEpoch == epoch.value && header.assignmentId == writer.assignmentId &&
            evidence.provenance.runtimeParent.sequence.value == header.parentSequence &&
            evidence.provenance.runtimeParent.frameHash == header.parentDigest &&
            evidence.capturedChanges == header.changeCount && intent.purposeTag == header.purpose.tag &&
            intent.originalRequestId.value == producer.operationId.value && intent.originalOperationId?.value == producer.operationId.value &&
            intent.originalActorId == producer.originalActorId && intent.originalRequestDigest == producer.semanticDigest)
        } finally { binding.fill(0) }
        val resources = intent.resources
        historyShapeV2(resources.isNotEmpty() && resources.all {
            it.selectorTag == 3 && it.preserveHold && it.originalEvidenceDigest == producer.semanticDigest
        } && resources.map { it.kind to it.resourceId }.toSet().size == resources.size)
        val aggregateTokens = setOf(
            aggregateToken(AggregateKey(domain.originalKind, DomainId(producer.operationId.value))),
            aggregateToken(AggregateKey(domain.applicationKind, DomainId(producer.operationId.value))))
        val observedTokens = mutableSetOf<String>()
        val observedAggregateTokens = mutableSetOf<String>()
        for ((ordinal, value) in mutations.withIndex()) {
            val table = RuntimeHistoryTableV2.fromTag(value.tableTag)
            val token = token(table, value.key)
            historyShapeV2(value.sequence == header.sequence && value.ordinal == ordinal && observedTokens.add(token))
            if (table == RuntimeHistoryTableV2.BRANCH) historyShapeV2(
                value.mutationKind == RuntimeHistoryMutationKindV2.UPDATE.tag && value.version > 1L &&
                    value.beforeRowId != null && value.beforeDigest != null && value.afterKind == RuntimeHistoryAfterKindV2.IMAGE.tag)
            else historyShapeV2(value.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag && value.version == 1L &&
                value.beforeRowId == null && value.beforeDigest == null &&
                value.afterKind == (if (table.immutable) RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag else RuntimeHistoryAfterKindV2.IMAGE.tag))
            if (table == RuntimeHistoryTableV2.AGGREGATE) historyShapeV2(observedAggregateTokens.add(token))
        }
        historyShapeV2(observedAggregateTokens == aggregateTokens)
        // Parse the whole dependency grammar, but issue no native source/custody or
        // BEFORE-row authority. The full target/main join independently closes those.
        var nativeMarker = false
        val observations = fields(dependencies)
        try { for (encoded in observations) {
            val values = fields(encoded)
            try {
                val text = values.map { historyUtf8V2(it) }
                when (text.firstOrNull()) {
                    "aggregate" -> {
                        historyShapeV2(text.size == 6)
                        AggregateKey(text[1], DomainId(text[2]))
                        if (text[3] == "absent") historyShapeV2(text[4].isEmpty() && text[5].isEmpty())
                        else {
                            val revision = text[3].toLongOrNull() ?: malformed()
                            historyShapeV2(revision > 0L && revision.toString() == text[3])
                            requireProtocolName(text[4]); PayloadDigest(text[5])
                        }
                    }
                    "claim" -> {
                        historyShapeV2(text.size == 4)
                        ClaimKey(text[1], text[2])
                        if (text[3] != "absent") DomainId(text[3])
                    }
                    "native-original" -> {
                        val p = evidence.provenance
                        historyShapeV2(!nativeMarker && text == listOf("native-original", p.currentWriter.invocationId.value,
                            p.nativeOriginal.clientSource.allocationId.value, p.nativeOriginal.certificateDigest.value,
                            p.nativeOriginal.originalBodyDigest.value))
                        nativeMarker = true
                    }
                    else -> malformed()
                }
            } catch (_: IllegalArgumentException) { malformed() }
            finally { values.forEach { it.fill(0) } }
        } } finally { observations.forEach { it.fill(0) } }
        historyShapeV2(nativeMarker)
        coldObservationTarget = target
        coldLastObservedNativeSequence = header.sequence
        coldLastObservedNativeCommandSequence = header.commandSequence
        return true
    }

    /** An auth-only ordinary prefix observation cannot hide security or protocol-row
     * mutations. This shape grants no business/effect/source authorization. */
    internal fun hasUnprotectedColdOrdinaryMutations(header: RuntimeHistoryEventHeaderV2,
        evidence: RuntimeCommandAuthorizationEvidenceV1, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean =
        enabled && coldStore != null && header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY &&
            evidence.purposeTag == header.purpose.tag && evidence.actionApprovalId == null &&
            evidence.capturedChanges == header.changeCount && mutations.size == header.changeCount && mutations.all {
                RuntimeHistoryTableV2.fromTag(it.tableTag).let { table -> table in TABLES && table.protectedTable == null }
            }

    /** Native ORIGINAL retention is not a fresh employee command. The guarded path needs
     * the actual strictly earlier Start capture; the cold path must independently rejoin
     * that Start and retain its exact native-enrolled historical access owner. Both then
     * join the whole original result/certificate, source pair, fences and raw-only effects.
     * No current account/grant, SIGNING_INTENT or native resolution import is admitted. */
    fun captureEmployeeNative(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeNativeOriginalImportEvidenceV1, intent: NativeHistoryOriginalIntentV2,
        dependencies: ByteArray, mutations: List<RuntimeHistoryRowVersionRowV2>): Boolean {
        if (!enabled || header.purpose != RuntimeWritePurposeV2.COMMAND_APPLY || evidence.producer.command !in supportedCommands ||
            evidence.producer.originalActorId == coordinates.firstActorId ||
            coldStore != null && (!supportsColdNativeOriginal(evidence) || !session.hasChronologicalAccountImageTraversal) ||
            mutations.size != RuntimeNativeOriginalImportEvidenceV1.CAPTURED_CHANGES || mutations.any {
                RuntimeHistoryTableV2.fromTag(it.tableTag) !in TABLES }) return false
        evidence.validate()
        val historical = evidence.provenance.historicalStart
        // Cold captures retain no old Start proof. The active original join below must
        // independently replay and pin that exact Start's genuine authorization owner.
        val start = if (coldStore == null) {
            val captured = byOperation[historical.intentOperationId] ?: return false
            val originalAuthorization = captured.employee ?: return false
            val proof = captured.employeeHistory ?: return false
            historyShapeV2(captured.nativeOriginal == null && captured.header.sequence < header.sequence && captured.scope == scope &&
                captured.header.commandEpoch == header.commandEpoch && captured.header.assignmentId == header.assignmentId &&
                captured.producer.originalActorId == historical.originalActorId && captured.producer.command == historical.command &&
                captured.producer.semanticDigest == historical.intentSemanticDigest && captured.authenticationId == historical.authenticationId &&
                captured.policyRevision == historical.policyRevision && captured.header.commandSequence == historical.sequence.value &&
                captured.header.commandDigest == historical.journalHash && originalAuthorization.authorization.deviceId == historical.masterDeviceId)
            proof.requireCaptured(session, captured.header, captured.eventDigest, originalAuthorization)
            captured
        } else null
        val writer = evidence.provenance.currentWriter
        val binding = session.original.originalBinding.copyWireBytes()
        try { historyShapeV2(evidence.scope == scope && writer.installationId == coordinates.installationId &&
            writer.masterDeviceId == coordinates.deviceId && writer.assignmentId == coordinates.assignmentId && writer.epoch == epoch &&
            writer.runtimeProfile == SelfOwnedNativeHistoryWireV2.PROFILE && writer.runtimeBindingCommitment == sha256(binding) &&
            header.commandEpoch == epoch.value && header.assignmentId == writer.assignmentId &&
            evidence.provenance.runtimeParent.sequence.value == header.parentSequence &&
            evidence.provenance.runtimeParent.frameHash == header.parentDigest)
        } finally { binding.fill(0) }
        return captureBound(header, eventDigest, null, evidence, evidence.producer, evidence.scope, RuntimeWritePurposeV2.COMMAND_APPLY.tag,
            evidence.capturedChanges, intent, dependencies, mutations, nativeEmployeeStart = start)
    }

    private fun captureBound(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest, employee: RuntimeCommandAuthorizationEvidenceV1?,
        nativeOriginal: RuntimeNativeOriginalImportEvidenceV1?, producer: StreamedProducerIdentityV2, capturedScope: BranchScope,
        purposeTag: Int, capturedChanges: Int, intent: NativeHistoryOriginalIntentV2, dependencies: ByteArray,
        mutations: List<RuntimeHistoryRowVersionRowV2>, employeeHistory: RuntimeEmployeeCommandHistoryAuthorization? = null,
        nativeEmployeeStart: CommandCapture? = null, supervisorReview: RuntimeHistoricalSupervisorReviewProfile? = null,
        employeeSupervisorReview: RuntimeEmployeeSupervisorReviewHistoryAuthorization? = null,
        purchasingPolicyRejection: RuntimeHistoricalPurchasingPolicyRejectionProfile? = null,
        employeePurchasingPolicyRejection: RuntimeEmployeePurchasingPolicyRejectionHistoryAuthorization? = null,
        orderReassignmentRejection: RuntimeHistoricalOrderReassignmentRejectionProfile? = null,
        employeeOrderReassignmentRejection: RuntimeEmployeeOrderReassignmentRejectionHistoryAuthorization? = null,
        openingSupplierObligationRejection: RuntimeHistoricalOpeningSupplierObligationRejectionProfile? = null,
        employeeOpeningSupplierObligationRejection: RuntimeEmployeeOpeningSupplierObligationRejectionHistoryAuthorization? = null,
        invoiceCaptureRejection: RuntimeHistoricalInvoiceCaptureRejectionProfile? = null,
        employeeInvoiceCaptureRejection: RuntimeEmployeeInvoiceCaptureRejectionHistoryAuthorization? = null,
        capturedInvoiceMatchRejection: RuntimeHistoricalCapturedInvoiceMatchRejectionProfile? = null,
        employeeCapturedInvoiceMatchRejection: RuntimeEmployeeCapturedInvoiceMatchRejectionHistoryAuthorization? = null,
        branchActivityActivationRejection: RuntimeHistoricalBranchActivityActivationRejectionProfile? = null,
        employeeBranchActivityActivationRejection: RuntimeEmployeeBranchActivityActivationRejectionHistoryAuthorization? = null,
        floorConfigurationRejection: RuntimeHistoricalFloorConfigurationRejectionProfile? = null,
        employeeFloorConfigurationRejection: RuntimeEmployeeFloorConfigurationRejectionHistoryAuthorization? = null,
        configurationRejection: RuntimeHistoricalConfigurationRejectionProfile? = null,
        employeeConfigurationRejection: RuntimeEmployeeConfigurationRejectionHistoryAuthorization? = null,
        returnRightsActivationRejection: RuntimeHistoricalReturnRightsActivationRejectionProfile? = null,
        employeeReturnRightsActivationRejection: RuntimeEmployeeReturnRightsActivationRejectionHistoryAuthorization? = null,
        retailCustomerReceiptRejection: RuntimeHistoricalRetailCustomerReceiptRejectionProfile? = null,
        employeeRetailCustomerReceiptRejection: RuntimeEmployeeRetailCustomerReceiptRejectionHistoryAuthorization? = null,
        returnCaseQualificationRejection: RuntimeHistoricalReturnCaseQualificationRejectionProfile? = null,
        employeeReturnCaseQualificationRejection: RuntimeEmployeeReturnCaseQualificationRejectionHistoryAuthorization? = null,
        returnStockDispositionRejection: RuntimeHistoricalReturnStockDispositionRejectionProfile? = null,
        employeeReturnStockDispositionRejection: RuntimeEmployeeReturnStockDispositionRejectionHistoryAuthorization? = null,
        foodCheckRejection: RuntimeHistoricalFoodCheckRejectionProfile? = null,
        employeeFoodCheckRejection: RuntimeEmployeeFoodCheckRejectionHistoryAuthorization? = null,
        foodPaymentRejection: RuntimeHistoricalFoodPaymentRejectionProfile? = null,
        employeeFoodPaymentRejection: RuntimeEmployeeFoodPaymentRejectionHistoryAuthorization? = null,
        approvalHistory: RuntimeActionApprovalHistoryAuthorization? = null): Boolean {
        if (coldStore != null && (nativeOriginal != null && !supportsColdNativeOriginal(nativeOriginal) ||
                nativeEmployeeStart != null || supervisorReview != null ||
                employeeSupervisorReview != null || purchasingPolicyRejection != null || employeePurchasingPolicyRejection != null ||
                orderReassignmentRejection != null || employeeOrderReassignmentRejection != null ||
                openingSupplierObligationRejection != null || employeeOpeningSupplierObligationRejection != null ||
                invoiceCaptureRejection != null || employeeInvoiceCaptureRejection != null ||
                capturedInvoiceMatchRejection != null || employeeCapturedInvoiceMatchRejection != null ||
                branchActivityActivationRejection != null || employeeBranchActivityActivationRejection != null ||
                floorConfigurationRejection != null || employeeFloorConfigurationRejection != null ||
                configurationRejection != null || employeeConfigurationRejection != null ||
                returnRightsActivationRejection != null || employeeReturnRightsActivationRejection != null ||
                retailCustomerReceiptRejection != null || employeeRetailCustomerReceiptRejection != null ||
                returnCaseQualificationRejection != null || employeeReturnCaseQualificationRejection != null ||
                returnStockDispositionRejection != null || employeeReturnStockDispositionRejection != null ||
                foodCheckRejection != null || employeeFoodCheckRejection != null || approvalHistory != null ||
                (header.purpose != RuntimeWritePurposeV2.COMMAND_APPLY &&
                    (header.purpose != RuntimeWritePurposeV2.COMMAND_REJECT || foodPaymentRejection == null)))) return false
        historyCapacityV2(captures.size < MAX_COMMANDS && mutations.size <= MAX_VERSIONS - versionCount)
        historyShapeV2(capturedScope == scope && header.sequence !in captures && producer.operationId !in byOperation &&
            (coldStore == null || active == null && captures.isEmpty() && coldOwnedCommandVersions == null &&
                header.sequence > coldLastCaptureSequence) &&
            capturedChanges == header.changeCount && mutations.size == capturedChanges && purposeTag == header.purpose.tag &&
            intent.purposeTag == purposeTag && intent.originalRequestId.value == producer.operationId.value &&
            intent.originalOperationId?.value == producer.operationId.value && intent.originalActorId == producer.originalActorId &&
            intent.originalRequestDigest == producer.semanticDigest)
        val copied = mutableListOf<Mutation>()
        var dependencyCopy: ByteArray? = null
        var nativeCopy: RuntimeNativeOriginalImportEvidenceV1? = null
        var ownedCapture: CommandCapture? = null
        var commandReserved = false; var captureComplete = false
        var failure: Throwable? = null
        try {
            if (coldStore != null) {
                // One atomic shared reservation, before any owned capture copy.
                // Recursive original owners use this same store; pinning a loan
                // only adds aliases and does not reserve its values a second time.
                coldStore.reserveOwnedCommand(mutations.size)
                coldOwnedCommandVersions = mutations.size; commandReserved = true
                charge(512L + intent.resources.size.toLong() * 256L + mutations.size.toLong() * 24L)
                if (nativeOriginal != null) {
                    // The decoder's event/target DATA reservation ends separately. Retain
                    // private bounded bytes and decoded provenance in this capture's SAME
                    // shared envelope before allocating any copy.
                    charge(4096L + 64L * (nativeOriginal.canonicalRequirement.size.toLong() +
                        nativeOriginal.certificate.size + 4096L))
                    var requirement: StoredBytes? = null
                    var certificate: StoredBytes? = null
                    try {
                        requirement = StoredBytes(nativeOriginal.canonicalRequirement.bytes())
                        certificate = StoredBytes(nativeOriginal.certificate.bytes())
                        nativeCopy = RuntimeNativeOriginalImportEvidenceV1(nativeOriginal.scope, nativeOriginal.producer,
                            checkNotNull(requirement), checkNotNull(certificate), nativeOriginal.provenance,
                            nativeOriginal.nativeRecordDigest, nativeOriginal.capturedChanges)
                    } finally {
                        if (nativeCopy == null) {
                            requirement?.bytes()?.fill(0)
                            certificate?.bytes()?.fill(0)
                        }
                    }
                }
            }
            val resources = intent.resources.map { value ->
                historyShapeV2(value.selectorTag == 3 && value.preserveHold && value.originalEvidenceDigest == producer.semanticDigest)
                RecoveryResourceKey(RecoveryResourceKind.valueOf(checkNotNull(value.kind).name), checkNotNull(value.resourceId))
            }.toSet()
            historyShapeV2(resources.size == intent.resources.size && resources.isNotEmpty())
            for ((ordinal, value) in mutations.withIndex()) {
                historyShapeV2(value.sequence == header.sequence && value.ordinal == ordinal)
                // Cover the fixed descriptor writer, its growth/copies and key/token
                // allocation before any copy. Retained descriptors then take their own
                // conservative reservation in the SAME working envelope.
                val workBytes = if (coldStore == null) 0L else 8_192L + value.key.size.toLong() * 4L
                coldStore?.reserveOwnedBytes(workBytes)
                try {
                    val descriptor = value.copy(key = value.key.copyOf())
                    try {
                        val wire = RuntimeHistoryCodecV2.encodeMutation(descriptor)
                        try {
                            charge(wire.size.toLong() + descriptor.key.size.toLong() * (if (coldStore == null) 1L else 5L) +
                                if (coldStore == null) 0L else 256L)
                            copied += Mutation(descriptor, wire)
                        } catch (failure: Throwable) { wire.fill(0); throw failure }
                    } catch (failure: Throwable) { descriptor.key.fill(0); throw failure }
                } finally { coldStore?.releaseOwnedBytes(workBytes) }
            }
            historyShapeV2(copied.map { it.token }.distinct().size == copied.size)
            charge(dependencies.size.toLong())
            val owned = dependencies.copyOf(); dependencyCopy = owned
            val intentWorkBytes = if (coldStore == null) 0L else header.intentBytes.toLong() * 4L + 256L
            coldStore?.reserveOwnedBytes(intentWorkBytes)
            val intentDigest = try {
                val intentBytes = SelfOwnedNativeHistoryWireV2.encodeIntent(intent)
                try { sha256(intentBytes) } finally { intentBytes.fill(0) }
            } finally { coldStore?.releaseOwnedBytes(intentWorkBytes) }
            val captured = CommandCapture(header, eventDigest, employee, nativeCopy ?: nativeOriginal, intentDigest, resources, owned, copied.toList(),
                employeeHistory, nativeEmployeeStart, supervisorReview, employeeSupervisorReview,
                purchasingPolicyRejection, employeePurchasingPolicyRejection, orderReassignmentRejection, employeeOrderReassignmentRejection,
                openingSupplierObligationRejection, employeeOpeningSupplierObligationRejection,
                invoiceCaptureRejection, employeeInvoiceCaptureRejection,
                capturedInvoiceMatchRejection, employeeCapturedInvoiceMatchRejection,
                branchActivityActivationRejection, employeeBranchActivityActivationRejection,
                floorConfigurationRejection, employeeFloorConfigurationRejection,
                configurationRejection, employeeConfigurationRejection,
                returnRightsActivationRejection, employeeReturnRightsActivationRejection,
                retailCustomerReceiptRejection, employeeRetailCustomerReceiptRejection,
                returnCaseQualificationRejection, employeeReturnCaseQualificationRejection,
                returnStockDispositionRejection, employeeReturnStockDispositionRejection,
                foodCheckRejection, employeeFoodCheckRejection, foodPaymentRejection, employeeFoodPaymentRejection, approvalHistory)
            ownedCapture = captured
            captures[header.sequence] = captured; byOperation[producer.operationId] = captured
            // All ordinals/counts remain complete. Protected siblings have their own native
            // approval owner and never become domain BEFORE/effect/current/tip histories.
            for (mutation in copied) if (coldStore == null && mutation.table.protectedTable == null)
                histories.getOrPut(mutation.token) { mutableListOf() } += mutation
            versionCount += copied.size
            if (coldStore != null) {
                coldCommandEvents = historyAddV2(coldCommandEvents, 1L, Long.MAX_VALUE)
                coldCommandMutations = historyAddV2(coldCommandMutations, copied.size.toLong(), Long.MAX_VALUE)
                if (employeeHistory != null) {
                    coldEmployeeCommandEvents = historyAddV2(coldEmployeeCommandEvents, 1L, Long.MAX_VALUE)
                    coldEmployeeCommandMutations = historyAddV2(coldEmployeeCommandMutations, copied.size.toLong(), Long.MAX_VALUE)
                }
                if (nativeOriginal != null && nativeOriginal.producer.originalActorId != coordinates.firstActorId) {
                    coldEmployeeNativeOriginalEvents = historyAddV2(coldEmployeeNativeOriginalEvents, 1L, Long.MAX_VALUE)
                    coldEmployeeNativeOriginalMutations = historyAddV2(coldEmployeeNativeOriginalMutations, copied.size.toLong(), Long.MAX_VALUE)
                }
                if (foodPaymentRejection != null) {
                    coldFoodPaymentRejectionEvents = historyAddV2(coldFoodPaymentRejectionEvents, 1L, Long.MAX_VALUE)
                    coldFoodPaymentRejectionMutations = historyAddV2(coldFoodPaymentRejectionMutations, copied.size.toLong(), Long.MAX_VALUE)
                }
                if (employeeFoodPaymentRejection != null) {
                    coldEmployeeFoodPaymentRejectionEvents = historyAddV2(coldEmployeeFoodPaymentRejectionEvents, 1L, Long.MAX_VALUE)
                    coldEmployeeFoodPaymentRejectionMutations = historyAddV2(coldEmployeeFoodPaymentRejectionMutations, copied.size.toLong(), Long.MAX_VALUE)
                }
                coldLastCaptureSequence = header.sequence
            }
            dependencyCopy = null; copied.clear()
            captureComplete = true
            return true
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            dependencyCopy?.fill(0); copied.forEach { it.clear() }
            if (!captureComplete) {
                nativeCopy?.canonicalRequirement?.bytes()?.fill(0)
                nativeCopy?.certificate?.bytes()?.fill(0)
            }
            if (commandReserved && !captureComplete) {
                // Partial construction never retains cleared values or consumes
                // global command/version capacity. The authorization scope still
                // owns its proof cleanup; only these local copies are retired here.
                ownedCapture?.let { captured ->
                    if (captures[header.sequence] === captured) captures.remove(header.sequence)
                    if (byOperation[producer.operationId] === captured) byOperation.remove(producer.operationId)
                }
                versionCount = 0; enabled = false
                try { releaseColdOwnedCommand() } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup
                    if (failure !== cleanup) checkNotNull(failure).addSuppressed(cleanup)
                }
            }
        }
    }

    fun lineageVersion(value: RuntimeHistoryRowVersionRowV2) {
        if (!enabled || value.sequence == 1L || RuntimeHistoryTableV2.fromTag(value.tableTag).protectedTable != null) return
        historyShapeV2(coldStore == null)
        val expected = captures[value.sequence]?.mutations?.getOrNull(value.ordinal) ?: malformed()
        val wire = RuntimeHistoryCodecV2.encodeMutation(value)
        try { historyShapeV2(!expected.seen && wire.contentEquals(expected.wire)); expected.seen = true }
        finally { wire.fill(0) }
    }
    fun lineageImage(version: RuntimeHistoryRowVersionRowV2, row: RuntimeHistoryRowViewV2) {
        if (!enabled) return
        historyShapeV2(coldStore == null)
        if (version.sequence == 1L) {
            if (row.table == RuntimeHistoryTableV2.BRANCH) {
                historyShapeV2(seedBranch == null)
                val bytes = RuntimeHistoryRowCodecV2.encode(row.physical)
                try { charge(bytes.size.toLong()); seedBranch = bytes } catch (failure: Throwable) { bytes.fill(0); throw failure }
            }
            return
        }
        if (row.table.protectedTable != null) return
        val expected = captures[version.sequence]?.mutations?.getOrNull(version.ordinal) ?: malformed()
        historyShapeV2(expected.seen && expected.image == null && row.table == expected.table)
        val bytes = RuntimeHistoryRowCodecV2.encode(row.physical)
        try { charge(bytes.size.toLong()); expected.image = bytes } catch (failure: Throwable) { bytes.fill(0); throw failure }
    }
    fun closeLineages() {
        if (!enabled) return
        historyShapeV2(coldStore == null)
        historyShapeV2(!lineageClosed && seedBranch != null)
        for (capture in captures.values) {
            capture.approvalHistory?.requireImagesJoined()
            for (mutation in capture.mutations) {
                if (mutation.table.protectedTable != null) {
                    val approval = capture.approvalHistory ?: malformed()
                    approval.requireProtectedVersion(mutation.descriptor)
                    historyShapeV2(!mutation.seen && mutation.image == null)
                    mutation.seen = true
                } else historyShapeV2(mutation.seen &&
                    (mutation.image == null) == (mutation.descriptor.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag))
            }
        }
        lineageClosed = true
    }

    /** One genuine chronological command, after the complete same-session native
     * transcript has closed. Every independent version is crossmatched here; images
     * remain scoped store loans when consumed, rather than a second retained estate. */
    suspend fun prepareColdCommand(operationId: OperationId) {
        val store = coldStore ?: malformed()
        historyShapeV2(enabled && active == null && currentFood == null && !historyComplete &&
            coldOriginalLoans.isEmpty() && captures.size == 1 && originals.isEmpty())
        val captured = byOperation[operationId] ?: malformed()
        historyShapeV2(captures[captured.header.sequence] === captured && !captured.joined &&
            coldOwnedCommandVersions == captured.mutations.size)
        for (mutation in captured.mutations) store.withVersion(captured.header.sequence, mutation.descriptor.ordinal) { observed ->
            historyShapeV2(!mutation.seen && mutation.image == null && observed.seen &&
                mutation.wire.contentEquals(observed.wire) && mutation.table == observed.table &&
                (observed.image == null) == (mutation.descriptor.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag))
            observed.image?.let { bytes -> withRow(bytes) { row -> requireMutationImage(mutation, row) } }
            mutation.seen = true
        }
        lineageClosed = true
    }

    /** Exact capture owned by this cold join, never an alias of an earlier loan. */
    internal fun coldCapture(operationId: OperationId): CommandCapture {
        historyShapeV2(enabled && lineageClosed && coldStore != null)
        val captured = byOperation[operationId] ?: malformed()
        historyShapeV2(captured.producer.operationId == operationId && captures[captured.header.sequence] === captured &&
            coldOwnedCommandVersions == captured.mutations.size && coldOriginalLoans[operationId] == null)
        return captured
    }

    /** Retire only after the complete original, business producer and resource join.
     * The scanner retires its exact authorization proof separately before proceeding. */
    suspend fun retireColdCommand(operationId: OperationId) {
        val store = coldStore ?: malformed()
        historyShapeV2(enabled && lineageClosed && active == null && currentFood == null && context == null &&
            coldOriginalLoans.isEmpty() && captures.size == 1 && originals.size == 1)
        val captured = byOperation[operationId] ?: malformed()
        historyShapeV2(captured.joined && captured.header.sequence > coldLastRetiredSequence &&
            captures[captured.header.sequence] === captured && coldOwnedCommandVersions == captured.mutations.size &&
            originals[operationId]?.let { producerIdentity(it) } == captured.producer)
        coldLastRetiredSequence = captured.header.sequence
        clearOwnedColdValues()
        captured.clear(); captures.clear(); byOperation.clear(); originals.clear(); versionCount = 0
        releaseColdOwnedCommand()
        val released = retainedBytes - coldRecipientBytes; retainedBytes = coldRecipientBytes
        store.releaseOwnedBytes(released)
    }

    suspend fun join(journal: JournalRow, operation: OperationRow): Boolean =
        joinBound(journal, operation, holdColdOriginal = false)

    /** A fresh genuine replay may lend only its fully joined, still-active original.
     * The caller owns the surrounding authorization scope and exact proof retirement.
     * No DATA joined marker is written for this rejoin. */
    suspend fun joinForColdLoan(journal: JournalRow, operation: OperationRow): RuntimeHistoricalCommand? {
        historyShapeV2(coldStore != null && heldColdOriginal == null)
        if (!joinBound(journal, operation, holdColdOriginal = true)) return null
        return heldColdOriginal ?: malformed()
    }

    /** Deactivate only this exact held owner, after releasing its independent earlier
     * loans. Cleanup deliberately does not require a live/cancellable read context.
     * Its current capture/proof stays owned until the caller retires authorization and
     * calls retireColdCommand; no second activation is possible. */
    suspend fun closeColdLoan(command: RuntimeHistoricalCommand) {
        historyShapeV2(coldStore != null && heldColdOriginal === command && active === command &&
            command.capture.joined && captures[command.capture.header.sequence] === command.capture &&
            byOperation[command.operationId] === command.capture && originals[command.operationId] === command.original)
        try { closeColdOriginalLoans() }
        finally { heldColdOriginal = null; active = null; context = null }
    }

    private suspend fun joinBound(journal: JournalRow, operation: OperationRow, holdColdOriginal: Boolean): Boolean {
        if (!enabled) return false
        historyShapeV2(lineageClosed && active == null && heldColdOriginal == null && currentFood == null && !historyComplete &&
            (!holdColdOriginal || coldStore != null))
        val captured = byOperation[OperationId(operation.operationId)] ?: malformed()
        val opMutation = captured.one(RuntimeHistoryTableV2.OPERATION)
        val journalMutation = captured.one(RuntimeHistoryTableV2.JOURNAL)
        val nativeOperation = withImage(opMutation) { row ->
            // These independent copies outlive withRow's scoped decode reservation.
            // The complete image also bounds their decoded scalar metadata.
            if (coldStore != null) charge(checkNotNull(opMutation.descriptor.imageBytes).toLong() * 4L)
            row.operation()
        }
        val nativeJournal = try { withImage(journalMutation) { row ->
            if (coldStore != null) charge(checkNotNull(journalMutation.descriptor.imageBytes).toLong() * 4L)
            row.journal()
        } }
        catch (failure: Throwable) { nativeOperation.result.fill(0); throw failure }
        try {
            historyShapeV2(nativeOperation.copy(result = operation.result) == operation && nativeOperation.result.contentEquals(operation.result) &&
                nativeJournal.copy(envelope = journal.envelope) == journal && nativeJournal.envelope.contentEquals(journal.envelope))
            // A well-formed refusal outside its closed profile remains unsupported, not a
            // malformed native history. No rejection proof or joined count is issued for it.
            if (captured.purchasingPolicyRejection?.let { nativeOperation.outcome !in it.rejectionCodes } == true) return false
            if (captured.orderReassignmentRejection?.let { nativeOperation.outcome !in it.rejectionCodes } == true) return false
            if (captured.openingSupplierObligationRejection?.let { nativeOperation.outcome !in it.rejectionCodes } == true) return false
            if (captured.invoiceCaptureRejection?.let { nativeOperation.outcome !in it.rejectionCodes } == true) return false
            if (captured.capturedInvoiceMatchRejection?.let { nativeOperation.outcome !in it.rejectionCodes } == true) return false
            if (captured.branchActivityActivationRejection?.let { nativeOperation.outcome !in it.rejectionCodes } == true) return false
            if (captured.floorConfigurationRejection?.let { nativeOperation.outcome !in it.rejectionCodes } == true) return false
            if (captured.configurationRejection?.let {
                !it.acceptsRejection(checkNotNull(captured.employee), nativeOperation.outcome) } == true) return false
            if (captured.returnRightsActivationRejection?.let { nativeOperation.outcome !in it.rejectionCodes } == true) return false
            if (captured.retailCustomerReceiptRejection?.let { nativeOperation.outcome != it.rejectionCode } == true) return false
            if (captured.foodCheckRejection?.let { nativeOperation.outcome !in it.rejectionCodes } == true) return false
            if (captured.foodPaymentRejection?.let { !it.acceptsRejection(nativeOperation.outcome) } == true) return false
            if (captured.returnCaseQualificationRejection?.let { nativeOperation.outcome != it.rejectionCode } == true) return false
            if (captured.returnStockDispositionRejection?.let { nativeOperation.outcome != it.rejectionCode } == true) return false
            requireIdentity(captured, nativeOperation, nativeJournal)
            captured.approvalHistory?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            captured.employeeHistory?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            captured.employeeSupervisorReview?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            captured.employeePurchasingPolicyRejection?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            captured.employeeOrderReassignmentRejection?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            captured.employeeOpeningSupplierObligationRejection?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            captured.employeeInvoiceCaptureRejection?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            captured.employeeCapturedInvoiceMatchRejection?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            captured.employeeBranchActivityActivationRejection?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            captured.employeeFloorConfigurationRejection?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            captured.employeeConfigurationRejection?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            captured.employeeReturnRightsActivationRejection?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            captured.employeeRetailCustomerReceiptRejection?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            captured.employeeFoodCheckRejection?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            captured.employeeFoodPaymentRejection?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            captured.employeeReturnCaseQualificationRejection?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            captured.employeeReturnStockDispositionRejection?.requireCaptured(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
            val originalBytes = nativeOperation.result.size.toLong() + nativeJournal.envelope.size.toLong()
            // Cold retained originals and their transient decoding/canonical copies
            // share the store envelope; preserve the guarded path's exact charge.
            charge(originalBytes * (if (coldStore == null) 1L else 4L))
            val original = if (RetainedCommandFramesV2.isRoot(nativeJournal.envelope)) {
                historyShapeV2(captured.nativeOriginal == null && captured.supervisorReview == null && captured.purchasingPolicyRejection == null &&
                    captured.orderReassignmentRejection == null && captured.openingSupplierObligationRejection == null &&
                    captured.invoiceCaptureRejection == null && captured.employeeInvoiceCaptureRejection == null &&
                    captured.capturedInvoiceMatchRejection == null && captured.employeeCapturedInvoiceMatchRejection == null &&
                    captured.branchActivityActivationRejection == null && captured.floorConfigurationRejection == null &&
                    captured.configurationRejection == null && captured.employeeConfigurationRejection == null &&
                    captured.returnRightsActivationRejection == null && captured.employeeReturnRightsActivationRejection == null &&
                    captured.retailCustomerReceiptRejection == null && captured.employeeRetailCustomerReceiptRejection == null &&
                    captured.foodCheckRejection == null && captured.employeeFoodCheckRejection == null &&
                    captured.foodPaymentRejection == null && captured.employeeFoodPaymentRejection == null &&
                    captured.returnCaseQualificationRejection == null && captured.employeeReturnCaseQualificationRejection == null &&
                    captured.returnStockDispositionRejection == null && captured.employeeReturnStockDispositionRejection == null)
                val root = RetainedCommandFramesV2.decodeRoot(nativeJournal.envelope)
                var normalized: StoredBytes? = null; var result: StoredBytes? = null
                try {
                    scanFrames(captured, root) { when (it) {
                        is RetainedCommandFrameV2.Normalized -> {
                            historyShapeV2(normalized == null)
                            if (coldStore != null) charge(it.body.size.toLong() * 4L)
                            normalized = it.body
                        }
                        is RetainedCommandFrameV2.Result -> {
                            historyShapeV2(result == null)
                            if (coldStore != null) charge(it.body.size.toLong() * 4L)
                            result = it.body
                        }
                        else -> Unit
                    } }
                    historyShapeV2(root.identity.scope == scope && root.identity.producer == StreamedProducerIdentityV2(captured.producer.operationId,
                        CommandKey(captured.producer.command.name, captured.producer.command.version), captured.producer.originalActorId,
                        captured.producer.semanticDigest) && root.identity.assignmentId == coordinates.assignmentId &&
                        root.identity.authorityEpoch == epoch && root.identity.sequence.value == operation.sequence &&
                        root.identity.recordedAt.toString() == operation.committedAt && root.identity.authenticationId.value == operation.authenticationId &&
                        root.identity.policyRevision.value == operation.policyRevision && root.identity.resultHash.value == operation.resultHash &&
                        root.identity.previousJournalHash.value == journal.previousHash)
                    val n = checkNotNull(normalized); val r = checkNotNull(result)
                    if (coldStore == null) charge(n.size.toLong() + r.size)
                    val raw = r.copyBytes()
                    try { historyShapeV2(raw.contentEquals(nativeOperation.result)) } finally { raw.fill(0) }
                    historyShapeV2(semanticDigest(scope, captured.producer.originalActorId,
                        root.identity.producer.command, n) == captured.producer.semanticDigest)
                    RetainedOriginalV2.Streamed(RetainedStreamedCommand(this, root, PayloadDigest(journal.hash), n, r))
                } catch (failure: Throwable) {
                    if (coldStore != null) { normalized?.bytes()?.fill(0); result?.bytes()?.fill(0) }
                    throw failure
                }
            } else RetainedOriginalV2.Legacy(checkNotNull(decodeRetainedCommand(scope, captured.producer.operationId, nativeOperation, nativeJournal)))
            historyShapeV2(originals.put(captured.producer.operationId, original) == null)
            val command = RuntimeHistoricalCommand(this, captured, original)
            context = currentCoroutineContext(); active = command
            var failure: Throwable? = null
            return try {
                requireNativeMutations(captured, original)
                requireDependencies(captured, original)
                if (captured.nativeOriginal != null) requireNativeOriginalAuthority(captured, original)
                // Closed rejections have no aggregate writes; requireDependencies above
                // closes their native read observations. The v1 aggregate trace is Apply-only.
                if (original is RetainedOriginalV2.Legacy && original.evidence.committed)
                    StockReadEvidenceV1.read(original.evidence)?.let { requireLegacyReadEvidence(captured, it) }
                if (!producer.join(command)) false else {
                    command.requireJoined()
                    historyShapeV2(!captured.joined)
                    captured.employeeHistory?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.employeeSupervisorReview?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.employeePurchasingPolicyRejection?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.employeeOrderReassignmentRejection?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.employeeOpeningSupplierObligationRejection?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.employeeInvoiceCaptureRejection?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.employeeCapturedInvoiceMatchRejection?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.employeeBranchActivityActivationRejection?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.employeeFloorConfigurationRejection?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.employeeConfigurationRejection?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.employeeReturnRightsActivationRejection?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.employeeRetailCustomerReceiptRejection?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.employeeFoodCheckRejection?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.employeeFoodPaymentRejection?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.employeeReturnCaseQualificationRejection?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.employeeReturnStockDispositionRejection?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.approvalHistory?.markJoined(session, captured.header, captured.eventDigest, checkNotNull(captured.employee))
                    captured.joined = true
                    if (!holdColdOriginal && coldStore != null) {
                        coldStore.markBusinessJoined(captured.header, captured.eventDigest, captured.producer)
                        // Only the main chronological join contributes to full-source
                        // closure. A borrowed earlier original cannot satisfy it. Each
                        // source has one unique sequence and captures advance strictly;
                        // no branch-sized visited-source set is needed.
                        if (businessPublicationSourceBefore(captured) != null)
                            coldJoinedBusinessPublicationSources = historyAddV2(
                                coldJoinedBusinessPublicationSources, 1L, Long.MAX_VALUE)
                    }
                    if (!holdColdOriginal) projectRetailCustomerReceiptEvents(captured, original)
                    joinedCommands = historyAddV2(joinedCommands, 1L, Long.MAX_VALUE)
                    if (holdColdOriginal) heldColdOriginal = command
                    true
                }
            } catch (caught: Throwable) { failure = caught; throw caught }
            finally {
                if (heldColdOriginal !== command) {
                    try { cleanupAfter(failure) { closeColdOriginalLoans() } }
                    finally { active = null; context = null }
                }
            }
        } finally { nativeOperation.result.fill(0); nativeJournal.envelope.fill(0) }
    }

    suspend fun requireActive(command: RuntimeHistoricalCommand) {
        val actual = currentCoroutineContext(); actual.ensureActive()
        historyShapeV2(enabled && lineageClosed && actual == context && active === command)
    }

    /** A historical rejoin can be requested only by this actual active cold owner. */
    internal suspend fun requireColdRejoinRequest(capture: CommandCapture) {
        val command = active ?: malformed()
        requireActive(command)
        historyShapeV2(coldStore != null && command.capture === capture &&
            captures[capture.header.sequence] === capture && byOperation[capture.producer.operationId] === capture &&
            coldOwnedCommandVersions == capture.mutations.size && coldOriginalLoans[capture.producer.operationId] == null)
    }

    /** Only this same-session, fully closed current FOOD computation may borrow through
     * its actual native parent. There is no invented later command/BEFORE capture, and the
     * returned head is DATA, never an authorization, admission or main-coverage proof. */
    internal suspend fun requireColdCurrentFoodRejoin(rejoin: RuntimeHistoryColdOriginalRejoin,
        readSession: SelfOwnedNativeHistoryReadSession): NativeHistoryRecordedHeadV2 {
        val actual = currentCoroutineContext(); actual.ensureActive()
        historyShapeV2(enabled && historyComplete && lineageClosed && coldCurrentFoodScope != null && actual == context &&
            rejoin === coldOriginalRejoin && readSession === session && coldStore?.session === session &&
            active == null && heldColdOriginal == null && coldOwnedCommandVersions == null && coldObservationTarget == null &&
            captures.size == coldOriginalLoans.size && byOperation.size == coldOriginalLoans.size &&
            originals.size == coldOriginalLoans.size && coldOriginalLoans.values.all { loan ->
                captures[loan.capture.header.sequence] === loan.capture &&
                    byOperation[loan.capture.producer.operationId] === loan.capture &&
                    originals[loan.capture.producer.operationId] === loan.original
            })
        // A reconciled pending child is not the completed native parent's current cut.
        // Keep that native outcome held instead of borrowing a favorable parent prefix.
        if (session.original.record.pending != null) unavailable()
        return session.original.record.parent ?: malformed()
    }

    /** Select only this exact original event from the actual native read session.
     * A signed membership for another operation/cut is not a substitute. The native
     * source independently authenticates its original anchor and immutable outputs. */
    internal fun businessPublicationSourceBefore(captured: CommandCapture): NativeCompanyBusinessPublicationHistorySource? {
        historyShapeV2(enabled && lineageClosed && captures[captured.header.sequence] === captured &&
            byOperation[captured.producer.operationId] === captured)
        if (!session.hasBusinessPublicationObservationTraversal) return null
        val source = businessPublicationSources[captured.header.sequence] ?: return null
        val header = captured.header
        val child = NativeHistoryRecordedHeadV2(header.sequence, captured.eventDigest, header.installationGenesisDigest,
            header.commandEpoch, header.commandSequence, header.commandDigest, header.roleCommitment, header.recoveryCommitment)
        val employee = captured.employee ?: malformed()
        val publicationRequirement = (captured.producer.command == CommandKey("local.configuration.write", 1) &&
            employee.requirement.action == SecurityAction.CATALOG_ACTIVATE) ||
            (RuntimeHistoricalConfigurationRejectionProfile.CATALOG_RELEASE.matchesCatalogReleaseActivationRequirement(employee) &&
                source.originalAnchorDigest == employee.nativeRecordDigest) ||
            (RuntimeHistoricalConfigurationRejectionProfile.PRICING.matchesPricingReceiptRequirement(employee) &&
                source.originalAnchorDigest == employee.nativeRecordDigest) ||
            (RuntimeHistoricalUnpricedArrivalProfileV2.matches(employee) &&
                captured.producer.command == CommandKey(employee.commandName, employee.commandVersion) &&
                source.originalAnchorDigest == employee.nativeRecordDigest)
        historyShapeV2(source.hasActualOriginalEvent && captured.nativeOriginal == null && publicationRequirement &&
            source.scope == scope && source.snapshot.company == scope.company() &&
            source.originalAllocationDigest == header.originalAllocationDigest &&
            source.originalAllocationDigest == session.original.record.allocation.digest &&
            source.frameId == header.transactionId && source.child == child &&
            source.preparationParent.historySequence == header.parentSequence &&
            source.preparationParent.historyDigest == header.parentDigest &&
            source.preparationParent.installationGenesisDigest == header.installationGenesisDigest &&
            source.originalRequestId.value == captured.producer.operationId.value &&
            source.originalOperationId == source.originalRequestId &&
            source.originalActorId == captured.producer.originalActorId &&
            source.semanticDigest == captured.producer.semanticDigest && source.purposeTag == header.purpose.tag &&
            source.originalIntentDigest == captured.originalIntentDigest && source.originalIntentDigest == header.intentDigest &&
            source.dependenciesDigest == header.dependencyDigest)
        return source
    }

    /** Exact original ownership for the combined monetary refusal port. The enrolled proof
     * is mandatory exactly when this event belongs to an actor other than the founder. */
    internal suspend fun requireCapturedInvoiceMatchRejectionOriginal(command: RuntimeHistoricalCommand) {
        requireActive(command)
        val captured = command.capture
        val profile = captured.capturedInvoiceMatchRejection ?: malformed()
        val evidence = captured.employee ?: malformed()
        historyShapeV2(!captured.joined && captures[captured.header.sequence] === captured &&
            byOperation[captured.producer.operationId] === captured && originals[captured.producer.operationId] === command.original &&
            captured.scope == scope && captured.header.scope == scope.storageKey() && captured.header.commandEpoch == epoch.value &&
            captured.header.assignmentId == coordinates.assignmentId &&
            captured.nativeOriginal == null && evidence.authorization.deviceId == coordinates.deviceId &&
            profile.matches(captured.header, evidence) &&
            (captured.employeeCapturedInvoiceMatchRejection != null) == (evidence.authorization.actorId != coordinates.firstActorId))
        captured.employeeCapturedInvoiceMatchRejection?.requireCaptured(session, captured.header, captured.eventDigest, evidence)
    }

    /** Exact active original ownership for the closed floor refusal port. A retained DTO,
     * earlier command, forged capture or proof from another native read cannot complete it. */
    internal suspend fun requireFloorConfigurationRejectionOriginal(command: RuntimeHistoricalCommand) {
        requireActive(command)
        val captured = command.capture
        val profile = captured.floorConfigurationRejection ?: malformed()
        val evidence = captured.employee ?: malformed()
        historyShapeV2(!captured.joined && captures[captured.header.sequence] === captured &&
            byOperation[captured.producer.operationId] === captured && originals[captured.producer.operationId] === command.original &&
            captured.scope == scope && captured.header.scope == scope.storageKey() && captured.header.commandEpoch == epoch.value &&
            captured.header.assignmentId == coordinates.assignmentId &&
            captured.nativeOriginal == null && evidence.authorization.deviceId == coordinates.deviceId &&
            profile.matches(captured.header, evidence) &&
            (captured.employeeFloorConfigurationRejection != null) == (evidence.authorization.actorId != coordinates.firstActorId))
        captured.employeeFloorConfigurationRejection?.requireCaptured(session, captured.header, captured.eventDigest, evidence)
    }

    /** Exact active original ownership for the closed configuration/pricing refusal ports. A
     * retained DTO, earlier command or proof from another native read cannot complete it.
     * The registered enrolled proof is mandatory exactly when the actor is not founding. */
    internal suspend fun requireConfigurationRejectionOriginal(command: RuntimeHistoricalCommand) {
        requireActive(command)
        val captured = command.capture
        val profile = captured.configurationRejection ?: malformed()
        val evidence = captured.employee ?: malformed()
        historyShapeV2(!captured.joined && captures[captured.header.sequence] === captured &&
            byOperation[captured.producer.operationId] === captured && originals[captured.producer.operationId] === command.original &&
            captured.scope == scope && captured.header.scope == scope.storageKey() && captured.header.commandEpoch == epoch.value &&
            captured.header.assignmentId == coordinates.assignmentId &&
            captured.nativeOriginal == null && evidence.authorization.deviceId == coordinates.deviceId &&
            profile.matches(captured.header, evidence) &&
            (captured.employeeConfigurationRejection != null) == (evidence.authorization.actorId != coordinates.firstActorId))
        captured.employeeConfigurationRejection?.requireCaptured(session, captured.header, captured.eventDigest, evidence)
    }

    /** Exact active original ownership for the closed documentary return-rights refusal
     * port. A retained DTO, earlier command, forged capture or proof from another genuine
     * native read cannot complete it. The distinct enrolled proof is mandatory iff needed. */
    internal suspend fun requireReturnRightsActivationRejectionOriginal(command: RuntimeHistoricalCommand) {
        requireActive(command)
        val captured = command.capture
        val profile = captured.returnRightsActivationRejection ?: malformed()
        val evidence = captured.employee ?: malformed()
        historyShapeV2(!captured.joined && captures[captured.header.sequence] === captured &&
            byOperation[captured.producer.operationId] === captured && originals[captured.producer.operationId] === command.original &&
            captured.scope == scope && captured.header.scope == scope.storageKey() && captured.header.commandEpoch == epoch.value &&
            captured.header.assignmentId == coordinates.assignmentId &&
            captured.nativeOriginal == null && evidence.authorization.deviceId == coordinates.deviceId &&
            profile.matches(captured.header, evidence) &&
            (captured.employeeReturnRightsActivationRejection != null) == (evidence.authorization.actorId != coordinates.firstActorId))
        captured.employeeReturnRightsActivationRejection?.requireCaptured(session, captured.header, captured.eventDigest, evidence)
    }
    /** Exact active original ownership for the closed receipt-refusal port. A copied DTO,
     * earlier command or proof from another native read cannot complete it. The original
     * enrolled proof is required exactly when the actor is not the founding employee. */
    internal suspend fun requireRetailCustomerReceiptRejectionOriginal(command: RuntimeHistoricalCommand) {
        requireActive(command)
        val captured = command.capture
        val profile = captured.retailCustomerReceiptRejection ?: malformed()
        val evidence = captured.employee ?: malformed()
        historyShapeV2(!captured.joined && captures[captured.header.sequence] === captured &&
            byOperation[captured.producer.operationId] === captured && originals[captured.producer.operationId] === command.original &&
            captured.scope == scope && captured.header.scope == scope.storageKey() && captured.header.commandEpoch == epoch.value &&
            captured.header.assignmentId == coordinates.assignmentId &&
            captured.nativeOriginal == null && evidence.authorization.deviceId == coordinates.deviceId &&
            profile.matches(captured.header, evidence) &&
            (captured.employeeRetailCustomerReceiptRejection != null) == (evidence.authorization.actorId != coordinates.firstActorId))
        captured.employeeRetailCustomerReceiptRejection?.requireCaptured(session, captured.header, captured.eventDigest, evidence)
    }

    /** Exact current active scan/original ownership for the new-record FOOD refusal.
     * Nonfounding provenance needs this event's distinct registered enrolled proof. */
    internal suspend fun requireFoodCheckRejectionOriginal(command: RuntimeHistoricalCommand) {
        requireActive(command)
        val captured = command.capture
        val profile = captured.foodCheckRejection ?: malformed()
        val evidence = captured.employee ?: malformed()
        historyShapeV2(!captured.joined && captures[captured.header.sequence] === captured &&
            byOperation[captured.producer.operationId] === captured && originals[captured.producer.operationId] === command.original &&
            captured.scope == scope && captured.header.scope == scope.storageKey() && captured.header.commandEpoch == epoch.value &&
            captured.header.assignmentId == coordinates.assignmentId && captured.nativeOriginal == null &&
            evidence.authorization.deviceId == coordinates.deviceId && profile.matches(captured.header, evidence) &&
            profile.hasMarker(captured.header, evidence, captured.dependencies) &&
            (captured.employeeFoodCheckRejection != null) == (evidence.authorization.actorId != coordinates.firstActorId))
        captured.employeeFoodCheckRejection?.requireCaptured(session, captured.header, captured.eventDigest, evidence)
    }

    /** This exact active native command owns the closed ordinary AlreadyApplied cut.
     * A copied original or another read cannot substitute for enrolled provenance. */
    internal suspend fun requireFoodPaymentRejectionOriginal(command: RuntimeHistoricalCommand) {
        requireActive(command)
        val captured = command.capture
        val profile = captured.foodPaymentRejection ?: malformed()
        val evidence = captured.employee ?: malformed()
        historyShapeV2(!captured.joined && captures[captured.header.sequence] === captured &&
            byOperation[captured.producer.operationId] === captured && originals[captured.producer.operationId] === command.original &&
            captured.scope == scope && captured.header.scope == scope.storageKey() && captured.header.commandEpoch == epoch.value &&
            captured.header.assignmentId == coordinates.assignmentId && captured.nativeOriginal == null &&
            evidence.authorization.deviceId == coordinates.deviceId && profile.matches(captured.header, evidence) &&
            (captured.employeeFoodPaymentRejection != null) == (evidence.authorization.actorId != coordinates.firstActorId))
        captured.employeeFoodPaymentRejection?.requireCaptured(session, captured.header, captured.eventDigest, evidence)
    }

    /** Exact active original ownership for the closed QUALIFY/Accept refusal port.
     * A copied command, another native read/cut or an Apply/rejection proof cannot complete
     * it. Nonfounding actors require this exact registered native-enrolled refusal proof. */
    internal suspend fun requireReturnCaseQualificationRejectionOriginal(command: RuntimeHistoricalCommand) {
        requireActive(command)
        val captured = command.capture
        val profile = captured.returnCaseQualificationRejection ?: malformed()
        val evidence = captured.employee ?: malformed()
        historyShapeV2(!captured.joined && captures[captured.header.sequence] === captured &&
            byOperation[captured.producer.operationId] === captured && originals[captured.producer.operationId] === command.original &&
            captured.scope == scope && captured.header.scope == scope.storageKey() && captured.header.commandEpoch == epoch.value &&
            captured.header.assignmentId == coordinates.assignmentId &&
            captured.nativeOriginal == null && evidence.authorization.deviceId == coordinates.deviceId &&
            profile.matches(captured.header, evidence) &&
            (captured.employeeReturnCaseQualificationRejection != null) == (evidence.authorization.actorId != coordinates.firstActorId))
        captured.employeeReturnCaseQualificationRejection?.requireCaptured(session, captured.header, captured.eventDigest, evidence)
    }

    /** Exact active original ownership of the stale-disposition RETURN refusal port.
     * Nonfounding employees require this event's registered native-enrolled proof;
     * copied commands, other reads and Apply/approval/refusal proofs cannot enter. */
    internal suspend fun requireReturnStockDispositionRejectionOriginal(command: RuntimeHistoricalCommand) {
        requireActive(command)
        val captured = command.capture
        val profile = captured.returnStockDispositionRejection ?: malformed()
        val evidence = captured.employee ?: malformed()
        historyShapeV2(!captured.joined && captures[captured.header.sequence] === captured &&
            byOperation[captured.producer.operationId] === captured && originals[captured.producer.operationId] === command.original &&
            captured.scope == scope && captured.header.scope == scope.storageKey() && captured.header.commandEpoch == epoch.value &&
            captured.header.assignmentId == coordinates.assignmentId &&
            captured.nativeOriginal == null && evidence.authorization.deviceId == coordinates.deviceId &&
            profile.matches(captured.header, evidence) &&
            (captured.employeeReturnStockDispositionRejection != null) == (evidence.authorization.actorId != coordinates.firstActorId))
        captured.employeeReturnStockDispositionRejection?.requireCaptured(session, captured.header, captured.eventDigest, evidence)
    }

    internal fun localHandoffRecipientBefore(capture: CommandCapture, orderId: DomainId,
        recipientId: DomainId): RuntimeHistoricalLocalHandoffRecipient? {
        historyShapeV2(enabled && lineageClosed && active?.capture === capture &&
            captures[capture.header.sequence] === capture && byOperation[capture.producer.operationId] === capture &&
            capture.scope == scope && capture.header.commandEpoch == epoch.value &&
            capture.header.assignmentId == coordinates.assignmentId)
        return recipients.before(capture, orderId, recipientId)
    }
    override suspend fun requireCurrent(evidence: RetainedStreamedCommand) {
        val command = active ?: malformed(); requireActive(command)
        val operation = evidence.identity.producer.operationId
        val actual = originals[operation] as? RetainedOriginalV2.Streamed ?: malformed()
        val captured = byOperation[operation] ?: malformed()
        historyShapeV2(actual.evidence === evidence && captured.header.sequence <= command.capture.header.sequence &&
            (captured === command.capture || captured.joined))
        if (coldStore != null) captured.employeeHistory?.requireCaptured(session, captured.header, captured.eventDigest,
            checkNotNull(captured.employee))
    }
    override suspend fun frames(evidence: RetainedStreamedCommand, consume: suspend (RetainedCommandFrameV2) -> Unit) {
        requireCurrent(evidence)
        scanFrames(checkNotNull(byOperation[evidence.identity.producer.operationId]), evidence.root, consume)
        requireCurrent(evidence)
    }
    override suspend fun read(owner: RetainedStreamedCommand, operationId: OperationId): RetainedOriginalV2? {
        requireCurrent(owner)
        val from = checkNotNull(byOperation[owner.identity.producer.operationId])
        if (coldStore != null) return originalBefore(from, operationId)
        val found = byOperation[operationId] ?: return null
        historyShapeV2(found.header.sequence < from.header.sequence && found.header.commandEpoch == from.header.commandEpoch &&
            found.header.assignmentId == from.header.assignmentId && found.scope == scope && found.scope == from.scope && found.joined)
        return originals[operationId] ?: malformed()
    }
    override suspend fun completedNativeBusinessPublicationsBefore(evidence: RetainedStreamedCommand): List<CompanyBusinessPublicationOutput> {
        requireCurrent(evidence)
        val captured = byOperation[evidence.identity.producer.operationId] ?: malformed()
        val source = businessPublicationSourceBefore(captured) ?: malformed()
        requireCurrent(evidence)
        return source.snapshot.completedPublications
    }

    private suspend fun scanFrames(captured: CommandCapture, root: JournalRootV2,
        consume: suspend (RetainedCommandFrameV2) -> Unit) {
        historyShapeV2(enabled && lineageClosed)
        val store = coldStore
        // One maximum frame, its decode/canonical working copies and the bounded
        // current-command descriptor ordering; never a stream-sized reservation.
        val frameWorkBytes = if (store == null) 0L else COLD_FRAME_WORK_BYTES
        store?.reserveOwnedBytes(frameWorkBytes)
        var failure: Throwable? = null
        try {
            val fragments = captured.mutations.filter { it.table == RuntimeHistoryTableV2.JOURNAL_FRAGMENT }
                .map { it to withImage(it) { row -> row.number("ordinal") } }.sortedBy { it.second }.map { it.first }
            val chain = StockFragmentChainV2(); val order = StockFrameOrderV2()
            val parser = StockFrameStreamDecoderV2 { bytes ->
                try {
                    currentCoroutineContext().ensureActive()
                    val frame = RetainedCommandFramesV2.decode(bytes)
                    val canonical = RetainedCommandFramesV2.encode(frame)
                    try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
                    order.append(frame, bytes.size); consume(frame)
                } finally { bytes.fill(0) }
            }
            var ordinal = 0L; var previousSize: Int? = null
            try {
                for (fragment in fragments) {
                    currentCoroutineContext().ensureActive()
                    withImage(fragment) { row ->
                        historyShapeV2(row.text("scope") == scope.storageKey() && row.text("operationId") == captured.producer.operationId.value &&
                            row.number("ordinal") == ordinal && (previousSize == null || previousSize == STOCK_JOURNAL_FRAGMENT_BYTES_V2))
                        val bytes = row.bytes("payload")
                        try {
                            historyShapeV2(bytes.size.toLong() == row.number("byteCount") && sha256(bytes).value == row.text("payloadHash"))
                            chain.append(ordinal, bytes); parser.append(bytes); previousSize = bytes.size; ordinal++
                        } finally { bytes.fill(0) }
                    }
                }
                parser.finish(); order.finish(root.counts, root.sectionBytes)
                val result = chain.finish()
                historyShapeV2(ordinal == root.fragmentCount && result.count == root.fragmentCount && result.bytes == root.streamBytes &&
                    result.commitment == root.commitment)
            } finally { parser.clear() }
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { if (store != null) cleanupAfter(failure) { store.releaseOwnedBytes(frameWorkBytes) } }
    }

    internal suspend fun aggregateBefore(capture: CommandCapture, key: AggregateKey): StoredAggregate? =
        withBefore(RuntimeHistoryTableV2.AGGREGATE, aggregateToken(key), capture.header.sequence) { it?.let { value -> storedAggregate(value) } }
    internal suspend fun originalBefore(capture: CommandCapture, operationId: OperationId): RetainedOriginalV2? {
        historyShapeV2(enabled && lineageClosed)
        if (coldStore != null) return pinnedEarlierOriginal(capture, operationId)?.original
        val prior = byOperation[operationId] ?: return null
        historyShapeV2(prior.header.sequence < capture.header.sequence && prior.header.commandEpoch == capture.header.commandEpoch &&
            prior.header.assignmentId == capture.header.assignmentId && prior.scope == capture.scope &&
            prior.scope == scope && prior.joined)
        return originals[operationId] ?: malformed()
    }
    internal suspend fun aggregateSourceBefore(capture: CommandCapture, key: AggregateKey): RuntimeHistoricalAggregateSource? {
        return withBefore(RuntimeHistoryTableV2.AGGREGATE, aggregateToken(key), capture.header.sequence) { mutation ->
            if (mutation == null) return@withBefore null
            val original = if (coldStore == null) {
                val prior = captures[mutation.descriptor.sequence] ?: malformed()
                originalBefore(capture, prior.producer.operationId) ?: malformed()
            } else pinnedEarlierOriginalAt(capture, mutation.descriptor.sequence).original
            val row = storedAggregate(mutation)
            when (original) {
                is RetainedOriginalV2.Legacy -> {
                    val write = original.evidence.aggregateWrites.singleOrNull { it.key == key } ?: malformed()
                    historyShapeV2(write.key == row.key && write.storageGeneration == row.revision && write.schema == row.schema &&
                        write.payload == row.payload && write.payloadDigest == row.digest)
                }
                is RetainedOriginalV2.Streamed -> {
                    val write = original.evidence.writeRow(key) ?: malformed()
                    historyShapeV2(write.key == row.key && write.revision == row.revision && write.schema == row.schema &&
                        write.payload == row.payload && write.digest == row.digest)
                }
            }
            RuntimeHistoricalAggregateSource(row, original)
        }
    }
    internal suspend fun aggregatesBefore(capture: CommandCapture): List<StoredAggregate> {
        if (coldStore != null) unavailable()
        return beforeRows(RuntimeHistoryTableV2.AGGREGATE, capture.header.sequence).map { storedAggregate(it) }
    }
    internal suspend fun forEachAggregateBefore(capture: CommandCapture, kind: String,
        consume: suspend (StoredAggregate) -> Unit) {
        forEachBeforeRow(RuntimeHistoryTableV2.AGGREGATE, capture.header.sequence) { mutation ->
            withImage(mutation) { row ->
                if (row.text("kind") == kind) withAggregate(mutation, row, consume)
            }
        }
    }
    internal suspend fun claimBefore(capture: CommandCapture, key: ClaimKey): DomainId? =
        withBefore(RuntimeHistoryTableV2.CLAIM, claimToken(key), capture.header.sequence) { value ->
            value?.let { withImage(it) { row -> DomainId(row.text("ownerId")) } }
        }
    internal suspend fun claimKeysBefore(capture: CommandCapture, namespace: String, prefix: String): List<ClaimKey> {
        if (coldStore != null) unavailable()
        historyShapeV2(enabled && lineageClosed && capture.scope == scope)
        return beforeRows(RuntimeHistoryTableV2.CLAIM, capture.header.sequence).mapNotNull { mutation ->
            val prior = captures[mutation.descriptor.sequence] ?: malformed()
            historyShapeV2(mutation.seen && prior.joined && prior.scope == scope &&
                prior.header.commandEpoch == capture.header.commandEpoch &&
                prior.header.assignmentId == capture.header.assignmentId)
            withImage(mutation) { row ->
                val claim = row.claim()
                val key = ClaimKey(claim.namespace, claim.key)
                historyShapeV2(claim.scope == scope.storageKey() && mutation.token == claimToken(key))
                key.takeIf { it.namespace == namespace && it.key.startsWith(prefix) }
            }
        }.sortedBy { it.key }
    }

    internal suspend fun forEachClaimKeyBefore(capture: CommandCapture, namespace: String, prefix: String,
        consume: suspend (ClaimKey) -> Unit) {
        historyShapeV2(enabled && lineageClosed && capture.scope == scope)
        forEachBeforeRow(RuntimeHistoryTableV2.CLAIM, capture.header.sequence, RuntimeHistoryColdRowOrder.CANONICAL_KEY) { mutation ->
            if (coldStore == null) {
                val prior = captures[mutation.descriptor.sequence] ?: malformed()
                requireEarlierCapture(capture, prior)
            }
            withImage(mutation) { row ->
                val claim = row.claim()
                val key = ClaimKey(claim.namespace, claim.key)
                historyShapeV2(claim.scope == scope.storageKey() && mutation.token == claimToken(key))
                if (key.namespace == namespace && key.key.startsWith(prefix)) {
                    if (coldStore != null) pinnedEarlierOriginalAt(capture, mutation.descriptor.sequence)
                    consume(key)
                }
            }
        }
    }

    internal suspend fun <T> withOriginalBefore(capture: CommandCapture, operationId: OperationId,
        use: suspend (RetainedOriginalV2?) -> T): T {
        if (coldStore == null) return use(originalBefore(capture, operationId))
        coldOriginalLoans[operationId]?.let { loan ->
            requireEarlierLoan(capture, loan)
            return use(loan.original)
        }
        val loan = checkNotNull(coldOriginalRejoin).byOperation(this, capture, operationId) ?: return use(null)
        var failure: Throwable? = null
        return try {
            requireEarlierLoan(capture, loan)
            historyShapeV2(loan.capture.producer.operationId == operationId)
            use(loan.original)
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { cleanupAfter(failure) { loan.close() } }
    }

    private suspend fun pinnedEarlierOriginal(capture: CommandCapture, operationId: OperationId): RuntimeHistoryColdOriginalLoan? {
        coldOriginalLoans[operationId]?.let { requireEarlierLoan(capture, it); return it }
        historyCapacityV2(captures.size < MAX_COMMANDS)
        charge(256L)
        val loan = checkNotNull(coldOriginalRejoin).byOperation(this, capture, operationId) ?: return null
        try { historyShapeV2(loan.capture.producer.operationId == operationId) }
        catch (failure: Throwable) { cleanupAfter(failure) { loan.close() }; throw failure }
        return pinEarlierLoan(capture, loan)
    }

    private suspend fun pinnedEarlierOriginalAt(capture: CommandCapture, sequence: Long): RuntimeHistoryColdOriginalLoan {
        captures[sequence]?.let { prior ->
            val loan = coldOriginalLoans[prior.producer.operationId] ?: malformed()
            requireEarlierLoan(capture, loan)
            return loan
        }
        historyCapacityV2(captures.size < MAX_COMMANDS)
        charge(256L)
        val loan = checkNotNull(coldOriginalRejoin).bySequence(this, capture, sequence) ?: malformed()
        try {
            historyShapeV2(loan.capture.header.sequence == sequence)
        } catch (failure: Throwable) {
            cleanupAfter(failure) { loan.close() }
            throw failure
        }
        return pinEarlierLoan(capture, loan)
    }

    private suspend fun pinEarlierLoan(capture: CommandCapture, loan: RuntimeHistoryColdOriginalLoan): RuntimeHistoryColdOriginalLoan {
        var retained = false; var failure: Throwable? = null
        return try {
            requireEarlierLoan(capture, loan)
            val prior = loan.capture
            historyShapeV2(prior.header.sequence !in captures && prior.producer.operationId !in byOperation &&
                prior.producer.operationId !in originals && prior.producer.operationId !in coldOriginalLoans)
            coldOriginalLoans[prior.producer.operationId] = loan
            captures[prior.header.sequence] = prior; byOperation[prior.producer.operationId] = prior
            originals[prior.producer.operationId] = loan.original
            retained = true
            loan
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { if (!retained) { detachColdLoan(loan); cleanupAfter(failure) { loan.close() } } }
    }

    private fun requireEarlierCapture(capture: CommandCapture, prior: CommandCapture) {
        historyShapeV2(prior.header.sequence < capture.header.sequence && prior.header.commandEpoch == capture.header.commandEpoch &&
            prior.header.assignmentId == capture.header.assignmentId && prior.scope == capture.scope &&
            prior.scope == scope && prior.joined)
    }

    private suspend fun requireEarlierLoan(capture: CommandCapture, loan: RuntimeHistoryColdOriginalLoan) {
        historyShapeV2(enabled && lineageClosed && coldStore != null && loan.session === session &&
            producerIdentity(loan.original) == loan.capture.producer)
        requireEarlierCapture(capture, loan.capture)
        val prior = loan.capture
        prior.employeeHistory?.requireCaptured(session, prior.header, prior.eventDigest, checkNotNull(prior.employee))
        when (val original = loan.original) {
            is RetainedOriginalV2.Legacy -> historyShapeV2(original.evidence.scope == scope &&
                original.evidence.assignmentId == coordinates.assignmentId && original.evidence.authorityEpoch == epoch &&
                original.evidence.sequence.value == prior.header.commandSequence)
            is RetainedOriginalV2.Streamed -> historyShapeV2(original.evidence.identity.scope == scope &&
                original.evidence.identity.assignmentId == coordinates.assignmentId && original.evidence.identity.authorityEpoch == epoch &&
                original.evidence.identity.sequence.value == prior.header.commandSequence)
        }
    }

    private suspend fun coldCurrentFoodBeforeSequence(): Long {
        val head = requireColdCurrentFoodRejoin(checkNotNull(coldOriginalRejoin), session)
        // This is only an exclusive DATA cursor bound for the actual current parent.
        // Overflow cannot become a truncated current census or a fabricated command.
        historyCapacityV2(head.historySequence < Long.MAX_VALUE)
        return head.historySequence + 1L
    }

    private suspend fun pinnedCurrentFoodOriginalAt(sequence: Long): RuntimeHistoryColdOriginalLoan {
        requireColdCurrentFoodRejoin(checkNotNull(coldOriginalRejoin), session)
        captures[sequence]?.let { captured ->
            val loan = coldOriginalLoans[captured.producer.operationId] ?: malformed()
            historyShapeV2(loan.capture === captured)
            requireCurrentFoodLoan(loan)
            return loan
        }
        historyCapacityV2(captures.size < MAX_COMMANDS)
        charge(256L)
        val loan = checkNotNull(coldOriginalRejoin).byCurrentFoodSequence(this, sequence) ?: malformed()
        var retained = false; var failure: Throwable? = null
        return try {
            requireCurrentFoodLoan(loan)
            val captured = loan.capture; val operationId = captured.producer.operationId
            historyShapeV2(captured.header.sequence == sequence && sequence !in captures && operationId !in byOperation &&
                operationId !in originals && operationId !in coldOriginalLoans)
            coldOriginalLoans[operationId] = loan
            captures[sequence] = captured; byOperation[operationId] = captured; originals[operationId] = loan.original
            retained = true
            loan
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { if (!retained) { detachColdLoan(loan); cleanupAfter(failure) { loan.close() } } }
    }

    private suspend fun requireCurrentFoodLoan(loan: RuntimeHistoryColdOriginalLoan) {
        val head = requireColdCurrentFoodRejoin(checkNotNull(coldOriginalRejoin), session)
        val captured = loan.capture; val header = captured.header
        historyShapeV2(loan.session === session && captured.joined && producerIdentity(loan.original) == captured.producer &&
            captured.scope == scope && header.scope == scope.storageKey() && header.assignmentId == coordinates.assignmentId &&
            header.commandEpoch == epoch.value && header.commandEpoch == head.commandEpoch &&
            header.installationGenesisDigest == head.installationGenesisDigest && header.sequence > 1L &&
            header.sequence <= head.historySequence && header.commandSequence <= head.commandSequence &&
            header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY && captured.employee?.actionApprovalId == null &&
            captured.approvalHistory == null)
        if (header.sequence == head.historySequence) historyShapeV2(
            NativeHistoryRecordedHeadV2(header.sequence, captured.eventDigest, header.installationGenesisDigest,
                header.commandEpoch, header.commandSequence, header.commandDigest,
                header.roleCommitment, header.recoveryCommitment) == head)
        captured.employeeHistory?.requireCaptured(session, header, captured.eventDigest, checkNotNull(captured.employee))
        when (val original = loan.original) {
            is RetainedOriginalV2.Legacy -> historyShapeV2(original.evidence.scope == scope &&
                original.evidence.assignmentId == coordinates.assignmentId && original.evidence.authorityEpoch == epoch &&
                original.evidence.sequence.value == header.commandSequence)
            is RetainedOriginalV2.Streamed -> historyShapeV2(original.evidence.identity.scope == scope &&
                original.evidence.identity.assignmentId == coordinates.assignmentId && original.evidence.identity.authorityEpoch == epoch &&
                original.evidence.identity.sequence.value == header.commandSequence)
        }
    }

    private suspend fun requireCurrentFoodMutation(loan: RuntimeHistoryColdOriginalLoan, mutation: Mutation) {
        requireCurrentFoodLoan(loan)
        val captured = loan.capture
        val expected = captured.mutations.getOrNull(mutation.descriptor.ordinal) ?: malformed()
        historyShapeV2(captures[captured.header.sequence] === captured &&
            coldOriginalLoans[captured.producer.operationId] === loan && mutation.seen && expected.seen &&
            mutation.descriptor.sequence == captured.header.sequence && mutation.table == expected.table &&
            mutation.wire.contentEquals(expected.wire))
    }

    private suspend fun <T> withBefore(table: RuntimeHistoryTableV2, token: String, sequence: Long,
        use: suspend (Mutation?) -> T): T {
        historyShapeV2(enabled && lineageClosed)
        val store = coldStore ?: return use(before(table, token, sequence))
        val key = tokenKey(table, token)
        return try { store.withBefore(table, key, sequence) { mutation ->
            historyShapeV2(mutation == null || mutation.table == table && mutation.token == token &&
                mutation.descriptor.sequence < sequence && mutation.seen)
            use(mutation?.takeIf { it.descriptor.afterKind != RuntimeHistoryAfterKindV2.TOMBSTONE.tag })
        } } finally { key.fill(0) }
    }

    private suspend fun forEachBeforeRow(table: RuntimeHistoryTableV2, sequence: Long,
        order: RuntimeHistoryColdRowOrder = RuntimeHistoryColdRowOrder.AFTER_ROW_ID,
        use: suspend (Mutation) -> Unit) {
        historyShapeV2(enabled && lineageClosed)
        val store = coldStore
        if (store == null) {
            val rows = beforeRows(table, sequence)
            val ordered = if (order == RuntimeHistoryColdRowOrder.CANONICAL_KEY) rows.map { mutation ->
                val key = withImage(mutation) { row -> when (table) {
                    RuntimeHistoryTableV2.AGGREGATE -> row.text("kind") + '\u0000' + row.text("id")
                    RuntimeHistoryTableV2.CLAIM -> row.text("namespace") + '\u0000' + row.text("key")
                    else -> mutation.token
                } }
                key to mutation
            }.sortedBy { it.first }.map { it.second } else rows
            for (mutation in ordered) use(mutation)
            return
        }
        val cursor = store.openBeforeRows(table, sequence, order)
        var failure: Throwable? = null
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val mutation = cursor.next() ?: break
                historyShapeV2(mutation.seen && mutation.table == table && mutation.descriptor.sequence < sequence &&
                    mutation.descriptor.afterKind != RuntimeHistoryAfterKindV2.TOMBSTONE.tag)
                use(mutation)
            }
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { cleanupAfter(failure) { cursor.close() } }
    }

    private fun before(table: RuntimeHistoryTableV2, token: String, sequence: Long): Mutation? {
        historyShapeV2(enabled && lineageClosed)
        val latest = histories[token]?.lastOrNull { it.descriptor.sequence < sequence } ?: return null
        historyShapeV2(latest.table == table && latest.seen)
        return latest.takeIf { it.descriptor.afterKind != RuntimeHistoryAfterKindV2.TOMBSTONE.tag }
    }
    private fun beforeRows(table: RuntimeHistoryTableV2, sequence: Long): List<Mutation> = histories.values.mapNotNull { values ->
        values.lastOrNull { it.table == table && it.descriptor.sequence < sequence }
            ?.takeIf { it.descriptor.afterKind != RuntimeHistoryAfterKindV2.TOMBSTONE.tag }
    }.sortedBy { checkNotNull(it.descriptor.afterRowId) }
    private suspend fun branchBefore(capture: CommandCapture): BranchRow {
        if (coldStore != null) return withBefore(RuntimeHistoryTableV2.BRANCH,
            keyToken(RuntimeHistoryTableV2.BRANCH, scope.storageKey()), capture.header.sequence) { prior ->
            if (prior != null) withImage(prior) { it.branch() } else coldStore.withSeedBranch { it.branch() }
        }
        val prior = captures.values.lastOrNull { it.header.sequence < capture.header.sequence }
        return if (prior != null) withImage(prior.one(RuntimeHistoryTableV2.BRANCH)) { it.branch() }
        else withRow(checkNotNull(seedBranch)) { it.branch() }
    }
    private suspend fun storedAggregate(mutation: Mutation): StoredAggregate {
        if (coldStore != null) charge(historyAddV2(0L, checkNotNull(mutation.descriptor.imageBytes).toLong() * 4L, MAX_RETAINED_BYTES))
        return withImage(mutation) { row -> decodeStoredAggregate(row) }.also {
            if (coldStore != null) coldOwnedAggregates += it
        }
    }
    private fun decodeStoredAggregate(row: RuntimeHistoryRowViewV2): StoredAggregate {
        val aggregate = row.aggregate()
        return try { StoredAggregate(AggregateKey(aggregate.kind, DomainId(aggregate.id)), Revision(aggregate.revision), aggregate.schema,
            StoredBytes(aggregate.payload), PayloadDigest(aggregate.payloadHash)) } finally { aggregate.payload.fill(0) }
    }
    private suspend fun <T> withAggregate(mutation: Mutation, row: RuntimeHistoryRowViewV2,
        use: suspend (StoredAggregate) -> T): T {
        val bytes = if (coldStore == null) 0L else checkNotNull(mutation.descriptor.imageBytes).toLong() * 4L
        coldStore?.reserveOwnedBytes(bytes)
        return try {
            val aggregate = decodeStoredAggregate(row)
            try { use(aggregate) } finally { if (coldStore != null) aggregate.payload.bytes().fill(0) }
        } finally { coldStore?.releaseOwnedBytes(bytes) }
    }

    private fun requireIdentity(captured: CommandCapture, operation: OperationRow, journal: JournalRow) {
        val value = captured.producer; val header = captured.header
        val outcomeMatches = if (captured.approvalRejected) operation.outcome != "committed"
            else captured.purchasingPolicyRejection?.let { operation.outcome in it.rejectionCodes }
            ?: captured.orderReassignmentRejection?.let { operation.outcome in it.rejectionCodes }
            ?: captured.openingSupplierObligationRejection?.let { operation.outcome in it.rejectionCodes }
            ?: captured.branchActivityActivationRejection?.let { operation.outcome in it.rejectionCodes }
            ?: captured.floorConfigurationRejection?.let { operation.outcome in it.rejectionCodes }
            ?: captured.configurationRejection?.let { it.acceptsRejection(checkNotNull(captured.employee), operation.outcome) }
            ?: captured.returnRightsActivationRejection?.let { operation.outcome in it.rejectionCodes }
            ?: captured.retailCustomerReceiptRejection?.let { operation.outcome == it.rejectionCode }
            ?: captured.foodCheckRejection?.let { operation.outcome in it.rejectionCodes }
            ?: captured.foodPaymentRejection?.let { it.acceptsRejection(operation.outcome) }
            ?: captured.returnCaseQualificationRejection?.let { operation.outcome == it.rejectionCode }
            ?: captured.returnStockDispositionRejection?.let { operation.outcome == it.rejectionCode }
            ?: (operation.outcome == (captured.supervisorReview?.rejectionCode ?: "committed"))
        historyShapeV2(operation.scope == scope.storageKey() && journal.scope == operation.scope && operation.operationId == value.operationId.value &&
            journal.operationId == operation.operationId && operation.actorId == value.originalActorId.value &&
            operation.authenticationId == captured.authenticationId.value && operation.policyRevision == captured.policyRevision.value &&
            operation.command == value.command.name && operation.commandVersion == value.command.version && operation.digest == value.semanticDigest.value &&
            outcomeMatches && operation.epoch == epoch.value && journal.epoch == operation.epoch &&
            operation.sequence == header.commandSequence && journal.sequence == operation.sequence &&
            journal.assignmentId == coordinates.assignmentId.value && journal.hash == header.commandDigest.value)
    }
    private suspend fun requireNativeMutations(captured: CommandCapture, original: RetainedOriginalV2) {
        val outcome = if (captured.approvalRejected) {
            val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: malformed()
            historyShapeV2(!retained.committed && retained.nativeOriginalProvenance == null)
            retained.code
        } else captured.purchasingPolicyRejection?.let { profile ->
            val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: malformed()
            historyShapeV2(retained.code in profile.rejectionCodes)
            retained.code
        } ?: captured.orderReassignmentRejection?.let { profile ->
            val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: malformed()
            historyShapeV2(retained.code in profile.rejectionCodes)
            retained.code
        } ?: captured.openingSupplierObligationRejection?.let { profile ->
            val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: malformed()
            historyShapeV2(retained.code in profile.rejectionCodes)
            retained.code
        } ?: captured.branchActivityActivationRejection?.let { profile ->
            val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: malformed()
            historyShapeV2(retained.code in profile.rejectionCodes)
            retained.code
        } ?: captured.floorConfigurationRejection?.let { profile ->
            val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: malformed()
            historyShapeV2(retained.code in profile.rejectionCodes)
            retained.code
        } ?: captured.configurationRejection?.let { profile ->
            val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: malformed()
            historyShapeV2(profile.acceptsRejection(checkNotNull(captured.employee), retained.code))
            retained.code
        } ?: captured.returnRightsActivationRejection?.let { profile ->
            val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: malformed()
            historyShapeV2(retained.code in profile.rejectionCodes)
            retained.code
        } ?: captured.foodCheckRejection?.let { profile ->
            val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: malformed()
            historyShapeV2(retained.code in profile.rejectionCodes)
            retained.code
        } ?: captured.foodPaymentRejection?.rejectionCode ?: captured.retailCustomerReceiptRejection?.rejectionCode
            ?: captured.returnCaseQualificationRejection?.rejectionCode
            ?: captured.returnStockDispositionRejection?.rejectionCode
            ?: (captured.supervisorReview?.rejectionCode ?: "committed")
        val closed = mutableSetOf<Int>()
        fun mark(value: Mutation) { historyShapeV2(closed.add(value.descriptor.ordinal)) }
        fun inserted(value: Mutation) {
            historyShapeV2(value.descriptor.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag && value.descriptor.version == 1L &&
                value.descriptor.beforeRowId == null && value.descriptor.beforeDigest == null && value.table.immutable &&
                value.descriptor.afterKind == RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag)
            mark(value)
        }
        captured.mutations.filter { it.table.protectedTable != null }.forEach { mutation ->
            val approval = captured.approvalHistory ?: malformed()
            approval.requireProtectedVersion(mutation.descriptor)
            historyShapeV2(mutation.seen && mutation.image == null)
            mark(mutation)
        }
        inserted(captured.one(RuntimeHistoryTableV2.OPERATION)); inserted(captured.one(RuntimeHistoryTableV2.JOURNAL))
        val branch = captured.one(RuntimeHistoryTableV2.BRANCH); val beforeBranch = branchBefore(captured)
        val afterBranch = withImage(branch) { it.branch() }
        historyShapeV2(branch.descriptor.mutationKind == RuntimeHistoryMutationKindV2.UPDATE.tag &&
            afterBranch == beforeBranch.copy(revision = Revision(beforeBranch.revision).next().value,
                sequence = captured.header.commandSequence, journalHash = captured.header.commandDigest.value) &&
            beforeBranch.epoch == epoch.value && beforeBranch.sequence + 1 == afterBranch.sequence)
        mark(branch)
        val audit = captured.one(RuntimeHistoryTableV2.AUDIT)
        withImage(audit) { row ->
            val value = row.audit()
            historyShapeV2(value.scope == scope.storageKey() && value.operationId == captured.producer.operationId.value &&
                value.kind == (if (captured.nativeOriginal == null) "command.$outcome" else "native-original.retained") &&
                value.actorId == captured.producer.originalActorId.value && value.originalActorId == captured.producer.originalActorId.value &&
                value.policyRevision == captured.policyRevision.value &&
                value.recordedAt == recordedAt(original).toString() && value.digest == captured.producer.semanticDigest.value)
        }; inserted(audit)
        val upload = captured.mutations.singleOrNull { it.table == RuntimeHistoryTableV2.OUTBOX && withImage(it) { row -> row.int("ordinal") == 0 } } ?: malformed()
        val uploadBytes = framedText(listOf("ukkera.journal-upload/v1", scope.branchId.value, epoch.value.toString(),
            captured.header.commandSequence.toString(), captured.header.commandDigest.value))
        try { requireOutbox(upload, captured, 0, SYNC_JOB, uploadBytes) } finally { uploadBytes.fill(0) }; inserted(upload)

        suspend fun write(value: RetainedAggregateWrite) {
            val mutation = captured.mutations.singleOrNull { it.table == RuntimeHistoryTableV2.AGGREGATE && it.token == aggregateToken(value.key) } ?: malformed()
            val beforeAggregate = aggregateBefore(captured, value.key); val after = storedAggregate(mutation)
            historyShapeV2(after.key == value.key && after.revision == value.storageGeneration && after.schema == value.schema &&
                after.payload == value.payload && after.digest == value.payloadDigest &&
                after.revision == (beforeAggregate?.revision?.next() ?: Revision(1)) &&
                mutation.descriptor.mutationKind == (if (beforeAggregate == null) RuntimeHistoryMutationKindV2.INSERT.tag else RuntimeHistoryMutationKindV2.UPDATE.tag))
            mark(mutation)
            val projected = projection.project(scope, after)
            if (projected != null) {
                val table = runtimeStockProjectionTable(value.key.kind) ?: malformed()
                val indexToken = keyToken(table, scope.storageKey(), value.key.kind, value.key.id.value)
                val index = captured.mutations.singleOrNull { it.table == table && it.token == indexToken } ?: malformed()
                val previousProjection = beforeAggregate?.let { projection.project(scope, it) }
                withBefore(table, indexToken, captured.header.sequence) { previousIndex ->
                    historyShapeV2((previousIndex == null) == (previousProjection == null) &&
                        index.descriptor.mutationKind == (if (previousIndex == null) RuntimeHistoryMutationKindV2.INSERT.tag else RuntimeHistoryMutationKindV2.UPDATE.tag) &&
                        projected.identity.producerOperationId == captured.producer.operationId && withImage(index) { it.runtimeStockIndex() } == projected)
                    previousIndex?.let { historyShapeV2(withImage(it) { row -> row.runtimeStockIndex() } == previousProjection) }
                }
                mark(index)
            }
        }
        suspend fun claim(value: RetainedClaimChange) {
            val before = claimBefore(captured, value.key)
            val mutation = captured.mutations.singleOrNull { it.table == RuntimeHistoryTableV2.CLAIM && it.token == claimToken(value.key) }
            when (value.action) {
                RetainedClaimAction.ACQUIRE -> if (before == value.ownerId) historyShapeV2(mutation == null) else {
                    historyShapeV2(before == null && mutation != null && mutation.descriptor.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag &&
                        withImage(checkNotNull(mutation)) { row -> row.claim() } == ClaimRow(scope.storageKey(), value.key.namespace, value.key.key, value.ownerId.value))
                    mark(checkNotNull(mutation))
                }
                RetainedClaimAction.RELEASE -> {
                    historyShapeV2(before == value.ownerId && mutation != null && mutation.descriptor.mutationKind == RuntimeHistoryMutationKindV2.DELETE.tag &&
                        mutation.descriptor.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag)
                    mark(checkNotNull(mutation))
                }
            }
        }
        suspend fun job(ordinal: Int, value: RetainedJobIntent) {
            val mutation = captured.mutations.singleOrNull { it.table == RuntimeHistoryTableV2.OUTBOX && withImage(it) { row -> row.int("ordinal") == ordinal } } ?: malformed()
            val raw = value.body.copyBytes()
            try { requireOutbox(mutation, captured, ordinal, value.kind, raw) } finally { raw.fill(0) }
            inserted(mutation)
        }
        when (original) {
            is RetainedOriginalV2.Legacy -> {
                val retained = original.evidence
                if (captured.approvalRejected) historyShapeV2(!retained.committed && retained.nativeOriginalProvenance == null &&
                    retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                    captured.nativeOriginal == null && captured.mutations.size == REJECTION_TABLES.size)
                else if (captured.supervisorReview != null) historyShapeV2(!retained.committed &&
                    retained.code == captured.supervisorReview.rejectionCode && retained.command == captured.supervisorReview.command &&
                    captured.nativeOriginal == null && retained.nativeOriginalProvenance == null && retained.aggregateWrites.isEmpty() &&
                    retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                    captured.mutations.size == RuntimeHistoricalSupervisorReviewProfile.CAPTURED_CHANGES)
                else if (captured.purchasingPolicyRejection != null) historyShapeV2(!retained.committed &&
                    retained.code in captured.purchasingPolicyRejection.rejectionCodes && retained.command == captured.purchasingPolicyRejection.command &&
                    captured.nativeOriginal == null && retained.nativeOriginalProvenance == null && retained.aggregateWrites.isEmpty() &&
                    retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                    captured.mutations.size == RuntimeHistoricalPurchasingPolicyRejectionProfile.CAPTURED_CHANGES)
                else if (captured.orderReassignmentRejection != null) historyShapeV2(!retained.committed &&
                    retained.code in captured.orderReassignmentRejection.rejectionCodes && retained.command == captured.orderReassignmentRejection.command &&
                    captured.nativeOriginal == null && retained.nativeOriginalProvenance == null && retained.aggregateWrites.isEmpty() &&
                    retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                    captured.mutations.size == RuntimeHistoricalOrderReassignmentRejectionProfile.CAPTURED_CHANGES)
                else if (captured.openingSupplierObligationRejection != null) historyShapeV2(!retained.committed &&
                    retained.code in captured.openingSupplierObligationRejection.rejectionCodes &&
                    retained.command == captured.openingSupplierObligationRejection.command &&
                    captured.nativeOriginal == null && retained.nativeOriginalProvenance == null && retained.aggregateWrites.isEmpty() &&
                    retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                    captured.mutations.size == RuntimeHistoricalOpeningSupplierObligationRejectionProfile.CAPTURED_CHANGES)
                else if (captured.invoiceCaptureRejection != null) historyShapeV2(!retained.committed &&
                    retained.code in captured.invoiceCaptureRejection.rejectionCodes &&
                    retained.command == captured.invoiceCaptureRejection.command &&
                    captured.nativeOriginal == null && retained.nativeOriginalProvenance == null && retained.aggregateWrites.isEmpty() &&
                    retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                    captured.mutations.size == RuntimeHistoricalInvoiceCaptureRejectionProfile.CAPTURED_CHANGES)
                else if (captured.capturedInvoiceMatchRejection != null) historyShapeV2(!retained.committed &&
                    retained.code in captured.capturedInvoiceMatchRejection.rejectionCodes &&
                    retained.command == captured.capturedInvoiceMatchRejection.command &&
                    captured.nativeOriginal == null && retained.nativeOriginalProvenance == null && retained.aggregateWrites.isEmpty() &&
                    retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                    captured.mutations.size == RuntimeHistoricalCapturedInvoiceMatchRejectionProfile.CAPTURED_CHANGES)
                else if (captured.branchActivityActivationRejection != null) historyShapeV2(!retained.committed &&
                    retained.code in captured.branchActivityActivationRejection.rejectionCodes &&
                    retained.command == captured.branchActivityActivationRejection.command &&
                    captured.nativeOriginal == null && retained.nativeOriginalProvenance == null && retained.aggregateWrites.isEmpty() &&
                    retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                    captured.mutations.size == RuntimeHistoricalBranchActivityActivationRejectionProfile.CAPTURED_CHANGES)
                else if (captured.floorConfigurationRejection != null) historyShapeV2(!retained.committed &&
                    retained.code in captured.floorConfigurationRejection.rejectionCodes &&
                    retained.command == captured.floorConfigurationRejection.command &&
                    captured.nativeOriginal == null && retained.nativeOriginalProvenance == null && retained.aggregateWrites.isEmpty() &&
                    retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                    captured.mutations.size == RuntimeHistoricalFloorConfigurationRejectionProfile.CAPTURED_CHANGES)
                else if (captured.configurationRejection != null) historyShapeV2(!retained.committed &&
                    captured.configurationRejection.acceptsRejection(checkNotNull(captured.employee), retained.code) &&
                    retained.command == captured.configurationRejection.command &&
                    captured.nativeOriginal == null && retained.nativeOriginalProvenance == null && retained.aggregateWrites.isEmpty() &&
                    retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                    captured.mutations.size == RuntimeHistoricalConfigurationRejectionProfile.CAPTURED_CHANGES)
                else if (captured.returnRightsActivationRejection != null) historyShapeV2(!retained.committed &&
                    retained.code in captured.returnRightsActivationRejection.rejectionCodes &&
                    retained.command == captured.returnRightsActivationRejection.command &&
                    captured.nativeOriginal == null && retained.nativeOriginalProvenance == null && retained.aggregateWrites.isEmpty() &&
                    retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                    captured.mutations.size == RuntimeHistoricalReturnRightsActivationRejectionProfile.CAPTURED_CHANGES)
                else if (captured.retailCustomerReceiptRejection != null) historyShapeV2(!retained.committed &&
                    retained.code == captured.retailCustomerReceiptRejection.rejectionCode &&
                    retained.command == captured.retailCustomerReceiptRejection.command &&
                    captured.nativeOriginal == null && retained.nativeOriginalProvenance == null && retained.aggregateWrites.isEmpty() &&
                    retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                    captured.mutations.size == RuntimeHistoricalRetailCustomerReceiptRejectionProfile.CAPTURED_CHANGES)
                else if (captured.foodCheckRejection != null) historyShapeV2(!retained.committed &&
                    retained.code in captured.foodCheckRejection.rejectionCodes && retained.command == captured.foodCheckRejection.command &&
                    captured.nativeOriginal == null && retained.nativeOriginalProvenance == null && retained.aggregateWrites.isEmpty() &&
                    retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                    captured.mutations.size == RuntimeHistoricalFoodCheckRejectionProfile.CAPTURED_CHANGES)
                else if (captured.foodPaymentRejection != null) historyShapeV2(!retained.committed &&
                    captured.foodPaymentRejection.acceptsRejection(retained.code) && retained.command == captured.foodPaymentRejection.command &&
                    captured.nativeOriginal == null && retained.nativeOriginalProvenance == null && retained.aggregateWrites.isEmpty() &&
                    retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                    captured.mutations.size == RuntimeHistoricalFoodPaymentRejectionProfile.CAPTURED_CHANGES)
                else if (captured.returnCaseQualificationRejection != null) historyShapeV2(!retained.committed &&
                    retained.code == captured.returnCaseQualificationRejection.rejectionCode &&
                    retained.command == captured.returnCaseQualificationRejection.command &&
                    captured.nativeOriginal == null && retained.nativeOriginalProvenance == null && retained.aggregateWrites.isEmpty() &&
                    retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                    captured.mutations.size == RuntimeHistoricalReturnCaseQualificationRejectionProfile.CAPTURED_CHANGES)
                else if (captured.returnStockDispositionRejection != null) historyShapeV2(!retained.committed &&
                    retained.code == captured.returnStockDispositionRejection.rejectionCode &&
                    retained.command == captured.returnStockDispositionRejection.command &&
                    captured.nativeOriginal == null && retained.nativeOriginalProvenance == null && retained.aggregateWrites.isEmpty() &&
                    retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                    captured.mutations.size == RuntimeHistoricalReturnStockDispositionRejectionProfile.CAPTURED_CHANGES)
                else historyShapeV2(retained.committed && retained.nativeOriginalProvenance == captured.nativeOriginal?.provenance)
                if (captured.nativeOriginal != null) {
                    historyShapeV2(retained.aggregateWrites.size == 2 && retained.aggregateWrites.all { it.storageGeneration == Revision(1) &&
                        aggregateBefore(captured, it.key) == null } && retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
                        captured.mutations.size == RuntimeNativeOriginalImportEvidenceV1.CAPTURED_CHANGES)
                    val domain = NativeOriginalRetainedCodecV1.requireDomain(retained.command,
                        captured.nativeOriginal.provenance.nativeOriginal.domainProfile)
                    NativeOriginalRetainedCodecV1.requireChanges(domain, retained.operationId,
                        retained.aggregateWrites.map { AggregateChange(it.key, null, it.schema, it.payload) })
                }
                for (value in original.evidence.aggregateWrites) write(value)
                for (value in original.evidence.claimChanges) claim(value)
                original.evidence.jobs.forEachIndexed { ordinal, value -> job(ordinal + 1, value) }
            }
            is RetainedOriginalV2.Streamed -> {
                var jobs = 0
                original.evidence.frames { frame -> when (frame) {
                    is RetainedCommandFrameV2.Write -> write(frame.value)
                    is RetainedCommandFrameV2.Claim -> claim(frame.value)
                    is RetainedCommandFrameV2.Job -> { historyShapeV2(frame.ordinal == (++jobs).toLong()); job(jobs, frame.value) }
                    else -> Unit
                } }
                captured.mutations.filter { it.table == RuntimeHistoryTableV2.JOURNAL_FRAGMENT }.forEach(::inserted)
            }
        }
        historyShapeV2(closed.size == captured.mutations.size)
    }
    private suspend fun requireOutbox(mutation: Mutation, captured: CommandCapture, ordinal: Int, kind: String, bytes: ByteArray) = withImage(mutation) { row ->
        val outbox = row.outbox()
        try { historyShapeV2(outbox.scope == scope.storageKey() && outbox.operationId == captured.producer.operationId.value &&
            outbox.ordinal == ordinal && outbox.kind == kind && outbox.body.contentEquals(bytes) && outbox.bodyHash == sha256(bytes).value) }
        finally { outbox.body.fill(0) }
    }

    internal fun hasRetainedFinalDeadlineDecision(captured: CommandCapture): Boolean =
        session.original.record.finalDeadlineDecisions.any {
            it.child.historySequence == captured.header.sequence
        }

    /** This original native snapshot alone selects a retained decision. A matching caller
     * DTO, the operation recordedAt, restart clock or today's policy can supply none. */
    internal fun requireFinalDeadlineDecision(captured: CommandCapture, expected: UtcInstant?) {
        val decision = session.original.record.finalDeadlineDecisions.singleOrNull {
            it.child.historySequence == captured.header.sequence
        }
        if (expected == null) { historyShapeV2(decision == null); return }
        val value = decision ?: malformed()
        val header = captured.header
        val child = NativeHistoryRecordedHeadV2(header.sequence, captured.eventDigest, header.installationGenesisDigest,
            header.commandEpoch, header.commandSequence, header.commandDigest, header.roleCommitment, header.recoveryCommitment)
        historyShapeV2(captured.nativeOriginal == null && header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY &&
            value.scope == scope && value.originalAllocationDigest == header.originalAllocationDigest && value.child == child &&
            value.originalIntentDigest == captured.originalIntentDigest && value.declaration.originalRequestId.value == captured.producer.operationId.value &&
            value.declaration.originalOperationId.value == captured.producer.operationId.value &&
            value.declaration.originalRequestDigest == captured.producer.semanticDigest && value.declaration.mustCommitBefore == expected &&
            value.finalWall < expected)
    }

    private suspend fun requireNativeOriginalAuthority(captured: CommandCapture, original: RetainedOriginalV2) {
        val evidence = checkNotNull(captured.nativeOriginal)
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: malformed()
        val p = evidence.provenance; val h = p.historicalStart
        historyShapeV2(retained.nativeOriginalProvenance == p && retained.normalized.size in 1..49152 &&
            sha256(retained.normalized.bytes()) == p.nativeOriginal.originalBodyDigest &&
            semanticDigest(scope, retained.originalActorId, retained.command, retained.normalized) == p.nativeOriginal.originalSemanticDigest)
        val earlier = originalBefore(captured, h.intentOperationId) ?: malformed()
        val start = (earlier as? RetainedOriginalV2.Legacy)?.evidence ?: malformed()
        val startCapture = byOperation[h.intentOperationId] ?: malformed()
        if (coldStore != null) {
            // This exact independent owner remains active until all native source,
            // result, resource and installed-producer checks have finished. A decoded
            // historical operation ID or a DATA joined marker cannot replace it.
            val loan = coldOriginalLoans[h.intentOperationId] ?: malformed()
            historyShapeV2(captured.nativeEmployeeStart == null && loan.capture === startCapture && loan.original === earlier)
            requireEarlierLoan(captured, loan)
        }
        historyShapeV2(startCapture.approvalHistory == null) // Existing physical Start profile remains direct-only.
        if (h.originalActorId != coordinates.firstActorId) {
            historyShapeV2((coldStore != null || startCapture === captured.nativeEmployeeStart) &&
                startCapture.joined && startCapture.employeeHistory != null)
            checkNotNull(startCapture.employeeHistory).requireCaptured(session, startCapture.header, startCapture.eventDigest,
                checkNotNull(startCapture.employee))
        } else historyShapeV2(captured.nativeEmployeeStart == null && startCapture.employeeHistory == null)
        historyShapeV2(startCapture.employee != null && startCapture.nativeOriginal == null && start.nativeOriginalProvenance == null &&
            start.committed && start.scope == scope && start.originalActorId == h.originalActorId && start.operationId == h.intentOperationId &&
            start.command == h.command && start.semanticDigest == h.intentSemanticDigest && start.authenticationId == h.authenticationId &&
            start.policyRevision == h.policyRevision && start.assignmentId == h.assignmentId && start.authorityEpoch == h.epoch &&
            start.sequence == h.sequence && start.journalHash == h.journalHash && start.resultHash == h.resultDigest && start.recordedAt == h.recordedAt &&
            startCapture.employee.authorization.deviceId == h.masterDeviceId && h.masterDeviceId == coordinates.deviceId)
        val certificate = PhysicalCommittedStartProjectionV1.parse(evidence.certificate)
        val sources = listOf(p.nativeOriginal.masterSource, p.nativeOriginal.clientSource)
        historyShapeV2(certificate.sourceStamps.map { it.key }.toSet() == sources.map {
            AggregateKey(PhysicalRecordingSourceProfileV1.KIND, it.allocationId) }.toSet())
        for (source in sources) {
            val key = AggregateKey(PhysicalRecordingSourceProfileV1.KIND, source.allocationId)
            val paired = aggregateSourceBefore(captured, key) ?: malformed()
            val stamp = certificate.sourceStamps.singleOrNull { it.key == key } ?: malformed()
            val row = paired.row
            historyShapeV2(paired.original === earlier && row.revision == Revision(1) && row.schema == PhysicalRecordingSourceProfileV1.SCHEMA &&
                row.digest == source.sourcePayloadDigest && sha256(row.payload.bytes()) == row.digest &&
                stamp.generation == row.revision && stamp.schema == row.schema && stamp.payloadDigest == row.digest)
        }
        val fence = aggregateSourceBefore(captured, ReservedOperations.key(retained.operationId)) ?: malformed()
        val reserved = decodeReservation(fence.row.key, fence.row.schema, fence.row.payload)
        historyShapeV2(fence.original === earlier && fence.row.revision == Revision(1) && reserved.operationId == retained.operationId &&
            reserved.actorId == h.originalActorId && reserved.command == h.command)
        // Both fixed fences/claims, full domain source identities and the original body
        // subtype are additionally compared by the installed closed financial producer.
    }

    internal suspend fun requireLegacyReadEvidence(captured: CommandCapture, observed: StockReadObservations) {
        historyShapeV2(observed.scope == scope && captured.nativeOriginal == null &&
            captured.configurationRejection == null && captured.employeeConfigurationRejection == null)
        val ranges = linkedMapOf<StockQueryV2, RangeClosure>()
        suspend fun range(query: StockQueryV2): RangeClosure {
            ranges[query]?.let { return it }
            if (coldStore != null) { historyCapacityV2(ranges.size < MAX_VERSIONS); charge(512L) }
            return openRangeBefore(captured, query).also { ranges[query] = it }
        }
        var failure: Throwable? = null
        try { for (source in observed.sources) when (source) {
            is StreamedSourceObservationV2.Current -> {
                val paired = aggregateSourceBefore(captured, source.key) ?: malformed()
                val row = paired.row
                historyShapeV2(row.key == source.key && row.revision == source.generation && row.schema == source.schema &&
                    row.payload == source.payload && row.digest == source.payloadHash)
            }
            is StreamedSourceObservationV2.StockItemAbsent -> historyShapeV2(aggregateBefore(captured, source.key) == null)
            is StreamedSourceObservationV2.CurrentAbsent -> historyShapeV2(aggregateBefore(captured, source.key) == null)
            is StreamedSourceObservationV2.Claim -> historyShapeV2(claimBefore(captured, source.key) == source.ownerId)
            is StreamedSourceObservationV2.StockMember -> range(source.query).member(source)
            is StreamedSourceObservationV2.StockRange -> range(source.query).end(source)
            else -> malformed()
        }
        for (range in ranges.values) range.finish()
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { cleanupAfter(failure) { closeHistoryCursors(ranges.values, null) } }
    }

    internal suspend fun requireStreamedSourcesAndResources(captured: CommandCapture, original: RetainedStreamedCommand) {
        requireCurrent(original)
        historyShapeV2(captured.configurationRejection == null && captured.employeeConfigurationRejection == null &&
            captured.returnCaseQualificationRejection == null && captured.employeeReturnCaseQualificationRejection == null &&
            captured.returnStockDispositionRejection == null && captured.employeeReturnStockDispositionRejection == null)
        val resources = mutableSetOf<RecoveryResourceKey>()
        val ranges = linkedMapOf<StockQueryV2, RangeClosure>()
        var estate: EstateClosure? = null
        suspend fun range(query: StockQueryV2): RangeClosure {
            ranges[query]?.let { return it }
            if (coldStore != null) { historyCapacityV2(ranges.size < MAX_VERSIONS); charge(512L) }
            return openRangeBefore(captured, query).also { ranges[query] = it }
        }
        fun estateClosure(): EstateClosure = estate ?: EstateClosure(captured).also {
            if (coldStore != null) charge(512L)
            estate = it
        }
        var failure: Throwable? = null
        try { original.frames { frame -> when (frame) {
            is RetainedCommandFrameV2.Resource -> historyShapeV2(resources.add(frame.value))
            is RetainedCommandFrameV2.Source -> when (val source = frame.value) {
                is StreamedSourceObservationV2.Aggregate -> {
                    requireSourceRow(captured, source.key, source.generation, source.schema, source.payload, source.payloadHash)
                    val prior = read(original, source.producer.operationId) ?: malformed()
                    historyShapeV2(producerIdentity(prior) == source.producer)
                    val priorWrite = when (prior) {
                        is RetainedOriginalV2.Legacy -> prior.evidence.aggregateWrites.singleOrNull { it.key == source.key }?.let {
                            StoredAggregate(it.key, it.storageGeneration, it.schema, it.payload, it.payloadDigest)
                        }
                        is RetainedOriginalV2.Streamed -> prior.evidence.writeRow(source.key)
                    } ?: malformed()
                    historyShapeV2(priorWrite.revision == source.generation && priorWrite.schema == source.schema &&
                        priorWrite.payload == source.payload && priorWrite.digest == source.payloadHash)
                }
                is StreamedSourceObservationV2.Current -> requireSourceRow(captured, source.key, source.generation, source.schema, source.payload, source.payloadHash)
                // No streamed producer declares typed normalized-item absence. Its
                // semantics are confined to the legacy stock-read evidence codec.
                is StreamedSourceObservationV2.StockItemAbsent -> malformed()
                is StreamedSourceObservationV2.CurrentAbsent -> historyShapeV2(aggregateBefore(captured, source.key) == null)
                is StreamedSourceObservationV2.Claim -> historyShapeV2(claimBefore(captured, source.key) == source.ownerId)
                is StreamedSourceObservationV2.StockMember -> range(source.query).member(source)
                is StreamedSourceObservationV2.StockRange -> range(source.query).end(source)
                is StreamedSourceObservationV2.EstateMember -> estateClosure().member(source)
                is StreamedSourceObservationV2.EstateEnd -> estateClosure().end(source)
            }
            else -> Unit
        } }
        historyShapeV2(resources == captured.resources)
        estate?.finish()
        for (range in ranges.values) range.finish()
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { cleanupAfter(failure) { closeHistoryCursors(ranges.values, estate) } }
    }
    private suspend fun requireSourceRow(captured: CommandCapture, key: AggregateKey, generation: Revision, schema: String,
        payload: StoredBytes, digest: PayloadDigest) {
        withBefore(RuntimeHistoryTableV2.AGGREGATE, aggregateToken(key), captured.header.sequence) { mutation ->
            val actual = mutation ?: malformed()
            withImage(actual) { row -> withAggregate(actual, row) { aggregate ->
                historyShapeV2(aggregate.key == key && aggregate.revision == generation && aggregate.schema == schema &&
                    aggregate.payload == payload && aggregate.digest == digest)
            } }
        }
    }
    private class ListCursor<T>(values: List<T>) : RuntimeHistoryColdCursor<T> {
        private var values: List<T>? = values
        private var at = 0
        override suspend fun next(): T? {
            val actual = values ?: malformed()
            return if (at == actual.size) null else actual[at++]
        }
        override suspend fun close() { values = null }
    }
    private class RangeClosure(private val query: StockQueryV2,
        private val expected: RuntimeHistoryColdCursor<StockIndexRecordV2>) {
        private val chain = StockRangeChainV2(query)
        private var ended = false
        suspend fun member(value: StreamedSourceObservationV2.StockMember) {
            historyShapeV2(!ended && value.query == query && value.ordinal == chain.count && value.record == expected.next())
            chain.append(value.record)
        }
        suspend fun end(value: StreamedSourceObservationV2.StockRange) {
            historyShapeV2(!ended && value.query == query && expected.next() == null && chain.finish() == value); ended = true
        }
        fun finish() { historyShapeV2(ended) }
        suspend fun close() = expected.close()
    }
    private suspend fun openRangeBefore(captured: CommandCapture, query: StockQueryV2): RangeClosure {
        val cursor = coldStore?.openStockRangeBefore(query, captured.header.sequence) ?: ListCursor(indexesBefore(captured, query))
        return RangeClosure(query, cursor)
    }
    private suspend fun indexesBefore(captured: CommandCapture, query: StockQueryV2): List<StockIndexRecordV2> {
        historyShapeV2(coldStore == null)
        val indexes = mutableListOf<StockIndexRecordV2>()
        for (table in STOCK_TABLES) for (mutation in beforeRows(table, captured.header.sequence))
            indexes += withImage(mutation) { it.runtimeStockIndex() }
        return runtimeStockSelect(query, indexes)
    }

    /** Exact rowid-ordered aggregate/claim membership, followed by canonical original
     * identifiers. A cold estate never reconstructs a plaintext estate/original list;
     * every original is genuinely rejoined only for the current scoped member. */
    private inner class EstateClosure(private val captured: CommandCapture) {
        private val chain = StockEstateChainV2()
        private var terminal: StreamedSourceObservationV2.EstateEnd? = null
        private var phase = 0
        private var rows: RuntimeHistoryColdCursor<Mutation>? = null
        private var operations: RuntimeHistoryColdCursor<OperationId>? = null
        private var previousRowId: Long? = null
        private var previousOperation: OperationId? = null

        suspend fun member(value: StreamedSourceObservationV2.EstateMember) {
            historyShapeV2(value.ordinal == chain.count)
            withNextActual { actual -> historyShapeV2(actual != null && actual == value.record) }
            chain.append(value.record)
        }
        fun end(value: StreamedSourceObservationV2.EstateEnd) {
            // Canonical estate.end precedes estate.member. Compare only after every
            // actual member and genuine original has reached its complete native EOF.
            historyShapeV2(terminal == null); terminal = value
        }
        suspend fun finish() {
            historyShapeV2(terminal != null)
            withNextActual { actual -> historyShapeV2(actual == null) }
            historyShapeV2(chain.finish() == terminal)
        }
        private suspend fun withNextActual(use: suspend (StockEstateRecordV2?) -> Unit) {
            while (phase < 2) {
                val table = if (phase == 0) RuntimeHistoryTableV2.AGGREGATE else RuntimeHistoryTableV2.CLAIM
                val cursor = rows ?: (coldStore?.openBeforeRows(table, captured.header.sequence) ?:
                    ListCursor(beforeRows(table, captured.header.sequence))).also { rows = it }
                val mutation = cursor.next()
                if (mutation == null) {
                    rows = null; cleanupAfter(null) { cursor.close() }; phase++; previousRowId = null
                    continue
                }
                historyShapeV2(mutation.seen && mutation.table == table && mutation.descriptor.sequence < captured.header.sequence &&
                    mutation.descriptor.afterKind != RuntimeHistoryAfterKindV2.TOMBSTONE.tag)
                val rowId = checkNotNull(mutation.descriptor.afterRowId)
                historyShapeV2(previousRowId == null || rowId > checkNotNull(previousRowId)); previousRowId = rowId
                withImage(mutation) { row ->
                    if (table == RuntimeHistoryTableV2.AGGREGATE) withAggregate(mutation, row) { aggregate ->
                        use(StockEstateRecordV2.Aggregate(aggregate.key, aggregate.revision, aggregate.schema, aggregate.payload, aggregate.digest))
                    } else {
                        val claim = row.claim()
                        historyShapeV2(claim.scope == scope.storageKey())
                        use(StockEstateRecordV2.Claim(ClaimKey(claim.namespace, claim.key), DomainId(claim.ownerId)))
                    }
                }
                return
            }
            if (phase == 2) {
                val cursor = operations ?: (coldStore?.openEarlierOperations(captured.header.sequence) ?: ListCursor(
                    captures.values.filter { it.header.sequence < captured.header.sequence }.sortedBy { it.producer.operationId.value }
                        .map { it.producer.operationId })).also { operations = it }
                val operationId = cursor.next()
                if (operationId == null) {
                    operations = null; cleanupAfter(null) { cursor.close() }; phase++
                } else {
                    historyShapeV2(previousOperation == null || checkNotNull(previousOperation).value < operationId.value)
                    previousOperation = operationId
                    withOriginalBefore(captured, operationId) { original ->
                        use(stockEstateOriginalV2(original ?: malformed()))
                    }
                    return
                }
            }
            use(null)
        }
        suspend fun close() {
            val selectedRows = rows; rows = null
            val selectedOperations = operations; operations = null
            var failure: Throwable? = null
            try { selectedRows?.close() } catch (caught: Throwable) { failure = caught }
            try { selectedOperations?.close() } catch (caught: Throwable) {
                if (failure == null) failure = caught else if (failure !== caught) checkNotNull(failure).addSuppressed(caught)
            }
            failure?.let { throw it }
        }
    }

    private suspend fun closeHistoryCursors(ranges: Collection<RangeClosure>, estate: EstateClosure?) {
        var failure: Throwable? = null
        try { estate?.close() } catch (caught: Throwable) { failure = caught }
        for (range in ranges) try { range.close() } catch (caught: Throwable) {
            if (failure == null) failure = caught else if (failure !== caught) checkNotNull(failure).addSuppressed(caught)
        }
        failure?.let { throw it }
    }

    /** Close every dependency observation, not only the ones a favorable producer consumes.
     * Old/unsupported dependency domains do not acquire a default accepting meaning. */
    private suspend fun requireDependencies(captured: CommandCapture, original: RetainedOriginalV2) {
        var command = false; var stock = false; var native = false; var businessPublication = false; var foodCheckRefusal = false
        val publicationSource = businessPublicationSourceBefore(captured)
        val joinedRejectionSources = captured.supervisorReview != null || captured.purchasingPolicyRejection != null ||
            captured.orderReassignmentRejection != null || captured.openingSupplierObligationRejection != null ||
            captured.capturedInvoiceMatchRejection != null ||
            captured.branchActivityActivationRejection != null || captured.floorConfigurationRejection != null ||
            captured.configurationRejection != null ||
            captured.returnRightsActivationRejection != null || captured.retailCustomerReceiptRejection != null ||
            captured.foodCheckRejection != null || captured.foodPaymentRejection != null ||
            captured.returnCaseQualificationRejection != null || captured.returnStockDispositionRejection != null || captured.approvalRejected
        val futureOperations = mutableSetOf<OperationId>()
        val observations = fields(captured.dependencies)
        try { for (encoded in observations) {
            val values = fields(encoded)
            try {
                val text = values.map { historyUtf8V2(it) }
                when (text.firstOrNull()) {
                    "aggregate" -> {
                        historyShapeV2(text.size == 6)
                        val key = AggregateKey(text[1], DomainId(text[2]))
                        val row = if (joinedRejectionSources) aggregateSourceBefore(captured, key)?.row
                            else aggregateBefore(captured, key)
                        historyShapeV2(text[3] == (row?.revision?.value?.toString() ?: "absent") && text[4] == (row?.schema ?: "") && text[5] == (row?.digest?.value ?: ""))
                    }
                    "claim" -> {
                        historyShapeV2(text.size == 4)
                        val key = ClaimKey(text[1], text[2])
                        historyShapeV2(text[3] == (claimBefore(captured, key)?.value ?: "absent"))
                        if (joinedRejectionSources) withBefore(RuntimeHistoryTableV2.CLAIM, claimToken(key), captured.header.sequence) { mutation ->
                            mutation?.let {
                                val prior = if (coldStore == null) captures[it.descriptor.sequence] ?: malformed()
                                    else pinnedEarlierOriginalAt(captured, it.descriptor.sequence).capture
                                historyShapeV2(originalBefore(captured, prior.producer.operationId) != null)
                            }
                        }
                    }
                    RuntimeHistoricalFoodCheckRejectionProfile.MARKER -> {
                        val profile = captured.foodCheckRejection ?: malformed()
                        val value = captured.employee ?: malformed()
                        historyShapeV2(!foodCheckRefusal && text == profile.markerFields(captured.header, value))
                        foodCheckRefusal = true
                    }
                    "future-operation-absence" -> historyShapeV2(futureOperations.add(
                        requireFutureOperationAbsence(captured, original, text)))
                    "native-business-publications/v1" -> {
                        historyShapeV2(!businessPublication && publicationSource != null &&
                            text == publicationSource.dependencyFields())
                        businessPublication = true
                    }
                    "command" -> {
                        val value = captured.employee ?: malformed(); val authorization = value.authorization
                        historyShapeV2(!command && text == listOf("command", value.commandName, value.commandVersion.toString(), authorization.actorId.value,
                            authorization.sessionId.value, authorization.deviceId.value, authorization.sessionId.value, authorization.generation.value.toString(), value.semanticDigest.value))
                        command = true
                    }
                    "native-original" -> {
                        val value = captured.nativeOriginal ?: malformed()
                        val p = value.provenance
                        historyShapeV2(!native && text == listOf("native-original", p.currentWriter.invocationId.value,
                            p.nativeOriginal.clientSource.allocationId.value, p.nativeOriginal.certificateDigest.value,
                            p.nativeOriginal.originalBodyDigest.value))
                        native = true
                    }
                    "stock.stream/v2" -> {
                        val evidence = (original as? RetainedOriginalV2.Streamed)?.evidence ?: malformed()
                        val sources = evidence.sectionSummary(StreamedSectionV2.SOURCES); val effects = evidence.sectionSummary(StreamedSectionV2.EFFECTS)
                        historyShapeV2(!stock && text == listOf("stock.stream/v2", sources.count.toString(), sources.bytes.toString(), sources.commitment.value,
                            effects.count.toString(), effects.bytes.toString(), effects.commitment.value)); stock = true
                    }
                    else -> malformed()
                }
            } finally { values.forEach { it.fill(0) } }
        } } finally { observations.forEach { it.fill(0) } }
        historyShapeV2(command == (captured.employee != null) && native == (captured.nativeOriginal != null) &&
            stock == (original is RetainedOriginalV2.Streamed) && businessPublication == (publicationSource != null) &&
            foodCheckRefusal == (captured.foodCheckRejection != null))
        val reservations = (original as? RetainedOriginalV2.Legacy)?.evidence?.aggregateWrites.orEmpty()
            .filter { it.key.kind == ReservedOperations.KIND }.map { OperationId(it.key.id.value) }.toSet()
        historyShapeV2(futureOperations == reservations)
    }

    /** The native verifier observed this SAME Start's parent cut before inserting the exact
     * immutable future fence. Inspect EVERY earlier version, including tombstones; current
     * absence and a later raw original cannot establish or disprove this earlier absence.
     * The installed producer separately closes the whole fence/target/effect/resource set. */
    private suspend fun requireFutureOperationAbsence(captured: CommandCapture, original: RetainedOriginalV2,
        observation: List<String>): OperationId {
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: malformed()
        val header = captured.header
        historyShapeV2(enabled && lineageClosed && observation.size == 5 && captured.employee != null &&
            captured.nativeOriginal == null && captured.supervisorReview == null && captured.purchasingPolicyRejection == null &&
            captured.orderReassignmentRejection == null && captured.openingSupplierObligationRejection == null &&
            captured.branchActivityActivationRejection == null && captured.floorConfigurationRejection == null &&
            captured.configurationRejection == null && captured.employeeConfigurationRejection == null &&
            captured.returnRightsActivationRejection == null && captured.employeeReturnRightsActivationRejection == null &&
            captured.retailCustomerReceiptRejection == null && captured.employeeRetailCustomerReceiptRejection == null &&
            captured.returnCaseQualificationRejection == null && captured.employeeReturnCaseQualificationRejection == null &&
            captured.returnStockDispositionRejection == null && captured.employeeReturnStockDispositionRejection == null &&
            header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY && retained.committed && retained.nativeOriginalProvenance == null &&
            observation[2] == captured.producer.originalActorId.value && observation[3] == header.parentSequence.toString() &&
            observation[4] == header.parentDigest.value)
        val operation = OperationId(observation[1])
        historyShapeV2(operation != captured.producer.operationId)
        val key = ReservedOperations.key(operation)
        val write = retained.aggregateWrites.singleOrNull { it.key == key } ?: malformed()
        val reservation = decodeReservation(key, write.schema, write.payload)
        val mutation = captured.mutations.singleOrNull {
            it.table == RuntimeHistoryTableV2.AGGREGATE && it.token == aggregateToken(key)
        } ?: malformed()
        historyShapeV2(reservation.operationId == operation && reservation.actorId == captured.producer.originalActorId &&
            write.storageGeneration == Revision(1) && mutation.seen && mutation.descriptor.version == 1L &&
            mutation.descriptor.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag &&
            mutation.descriptor.beforeRowId == null && mutation.descriptor.beforeDigest == null &&
            mutation.descriptor.afterKind == RuntimeHistoryAfterKindV2.IMAGE.tag)
        forEachEarlierVersion(RuntimeHistoryTableV2.AGGREGATE, aggregateToken(key), header.sequence) { malformed() }
        forEachEarlierVersion(RuntimeHistoryTableV2.OPERATION,
            keyToken(RuntimeHistoryTableV2.OPERATION, scope.storageKey(), operation.value), header.sequence) { malformed() }
        // The journal key is epoch/sequence, not operation ID. Inspect every earlier
        // literal version, not only the final version of its key or a joined prefix.
        forEachEarlierVersion(RuntimeHistoryTableV2.JOURNAL, null, header.sequence) { prior ->
            if (coldStore == null) historyShapeV2(captures[prior.descriptor.sequence]?.joined == true)
            if (prior.descriptor.afterKind != RuntimeHistoryAfterKindV2.TOMBSTONE.tag) withImage(prior) { row ->
                historyShapeV2(row.text("operationId") != operation.value)
            }
        }
        return operation
    }
    private suspend fun forEachEarlierVersion(table: RuntimeHistoryTableV2, selectedToken: String?, sequence: Long,
        use: suspend (Mutation) -> Unit) {
        historyShapeV2(enabled && lineageClosed)
        val store = coldStore
        if (store == null) {
            for (values in histories.values) for (mutation in values) if (mutation.table == table &&
                mutation.descriptor.sequence < sequence && (selectedToken == null || mutation.token == selectedToken)) use(mutation)
            return
        }
        val key = selectedToken?.let { tokenKey(table, it) }
        var cursor: RuntimeHistoryColdCursor<Mutation>? = null; var failure: Throwable? = null
        try {
            val selected = store.openEarlierVersions(table, key, sequence); cursor = selected
            while (true) {
                currentCoroutineContext().ensureActive()
                val mutation = selected.next() ?: break
                historyShapeV2(mutation.seen && mutation.table == table && mutation.descriptor.sequence < sequence &&
                    (selectedToken == null || mutation.token == selectedToken))
                use(mutation)
            }
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            try { cleanupAfter(failure) { cursor?.close() } } finally { key?.fill(0) }
        }
    }
    suspend fun finish(expectedCommands: Long, expectedMutations: Long) {
        if (coldStore != null) {
            historyShapeV2(enabled && lineageClosed && active == null && heldColdOriginal == null && currentFood == null && context == null &&
                !historyComplete && coldObservationTarget == null && joinedCommands == expectedCommands && commandEvents == expectedCommands &&
                commandMutations == expectedMutations && captures.isEmpty() && byOperation.isEmpty() && originals.isEmpty() &&
                coldOriginalLoans.isEmpty() && coldOwnedCommandVersions == null && coldLastRetiredSequence == coldLastCaptureSequence)
            // All counted main joins must have actually retired above. Pending native
            // preparation without a SQL event remains the scanner's unresolved-outcome
            // hold; every other native source must match its exact joined command.
            val expectedSources = businessPublicationSources.values.count { source ->
                !isUnobservedPendingBusinessPublication(source)
            }.toLong()
            historyShapeV2(coldJoinedBusinessPublicationSources == expectedSources)
            coldStore.requireBusinessClosure(expectedCommands, expectedMutations)
            historyComplete = true
            return
        }
        historyShapeV2(enabled && lineageClosed && active == null && currentFood == null && joinedCommands == expectedCommands && commandEvents == expectedCommands &&
            commandMutations == expectedMutations && originals.size.toLong() == expectedCommands && captures.values.all { it.joined })
        if (session.hasBusinessPublicationObservationTraversal) {
            for (source in businessPublicationSources.values) {
                // A native pending capsule without its actual SQL event is not an
                // original source. Keep the scanner's unresolved native-outcome hold;
                // every settled source and every observed pending source is compulsory.
                if (isUnobservedPendingBusinessPublication(source)) continue
                val captured = captures[source.child.historySequence] ?: malformed()
                historyShapeV2(businessPublicationSourceBefore(captured) === source)
            }
        }
        historyComplete = true
    }

    private fun isUnobservedPendingBusinessPublication(source: NativeCompanyBusinessPublicationHistorySource): Boolean =
        !source.hasActualOriginalEvent && session.original.record.pending?.businessPublications?.let {
            it.child == source.child && it.frameId == source.frameId &&
                it.observationDigest == source.observationDigest
        } == true

    /** Project only a successful main join while its genuine original is still active.
     * Borrowed earlier-original rejoins never contribute another EVENT. This emits inert
     * locator DATA; the scanner independently closes every immutable lineage/current row
     * and the complete census before the candidate can be parked and delivered. */
    private suspend fun projectRetailCustomerReceiptEvents(captured: CommandCapture, original: RetainedOriginalV2) {
        val builder = receiptMembershipBuilder ?: return
        val classifier = checkNotNull(producer.retailCustomerReceiptEventClassifier)
        builder.requireClassifier(classifier)
        val command = checkNotNull(active)
        requireActive(command)
        historyShapeV2(command.capture === captured && command.original === original && captured.joined)
        for (mutation in captured.mutations) {
            currentCoroutineContext().ensureActive()
            if (mutation.table != RuntimeHistoryTableV2.AGGREGATE ||
                mutation.descriptor.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag) continue
            withImage(mutation) { image ->
                if (image.text("kind") != RuntimeRetailCustomerReceiptMembershipProfile.EVENT_KIND) return@withImage
                val version = mutation.descriptor
                historyShapeV2(mutation.seen && version.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag &&
                    version.version == 1L && version.previousSequence == null && version.previousOrdinal == null &&
                    version.previousVersion == null && version.beforeRowId == null && version.beforeDigest == null &&
                    captured.nativeOriginal == null && captured.employee != null &&
                    captured.header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY && captured.scope == scope &&
                    captured.header.commandEpoch == epoch.value && captured.header.assignmentId == coordinates.assignmentId &&
                    captured.producer.command == RuntimeRetailCustomerReceiptMembershipProfile.COMMAND &&
                    image.text("scope") == scope.storageKey())
                val evidence = (original as? RetainedOriginalV2.Legacy)?.evidence ?: malformed()
                val bytes = historyAddV2(8_192L, checkNotNull(version.imageBytes).toLong() * 4L, MAX_RETAINED_BYTES)
                reserveSharedBytes(bytes)
                try {
                    val row = decodeStoredAggregate(image)
                    try {
                        historyShapeV2(evidence.committed && evidence.nativeOriginalProvenance == null && evidence.scope == scope &&
                            evidence.assignmentId == coordinates.assignmentId && evidence.authorityEpoch == epoch &&
                            evidence.command == captured.producer.command && evidence.operationId == captured.producer.operationId &&
                            evidence.originalActorId == captured.producer.originalActorId && evidence.semanticDigest == captured.producer.semanticDigest &&
                            row.key == AggregateKey(RuntimeRetailCustomerReceiptMembershipProfile.EVENT_KIND, DomainId(evidence.operationId.value)) &&
                            row.revision == Revision(1) && row.schema == RuntimeRetailCustomerReceiptMembershipProfile.EVENT_SCHEMA)
                        val write = evidence.aggregateWrites.singleOrNull { it.key == row.key } ?: malformed()
                        historyShapeV2(write.storageGeneration == row.revision && write.schema == row.schema &&
                            write.payload == row.payload && write.payloadDigest == row.digest)
                        val saleId = classifier.saleId(scope, row, evidence)
                        builder.append(saleId, RuntimeRetailCustomerReceiptEventLocator(row.key, row.revision, row.schema, row.digest))
                    } finally { row.payload.bytes().fill(0) }
                } finally { releaseSharedBytes(bytes) }
            }
        }
    }

    /** The projection's existence is not admission. Call only after the complete
     * producer/native closure; the scanner adds its independent immutable EVENT census. */
    fun requireRetailCustomerReceiptMembershipClosed(classifier: RuntimeRetailCustomerReceiptEventClassifier,
        builder: RuntimeRetailCustomerReceiptMembershipBuilder) {
        historyShapeV2(enabled && historyComplete && lineageClosed && active == null && currentFood == null && context == null &&
            producer.retailCustomerReceiptEventClassifier === classifier && receiptMembershipBuilder === builder)
        builder.requireClassifier(classifier)
    }

    /** Called only AFTER the scanner has closed every event/lineage/current-tip, retained
     * command and complete producer/resource comparison. Fixed-kind current FOOD membership
     * and ALL entire FOOD claim namespaces come from those same native lineages, never SQL filters
     * or callback-supplied sources. Each native current row is again compared to its exact
     * immutable original AFTER write, reading each needed original to genuine EOF once. */
    suspend fun joinCurrentFood(): Boolean {
        historyShapeV2(enabled && historyComplete && lineageClosed && active == null && heldColdOriginal == null &&
            currentFood == null && coldCurrentFoodScope == null && context == null)
        if (coldStore != null) return joinColdCurrentFood()
        val sources = mutableListOf<RuntimeHistoricalAggregateSource>()
        val writers = linkedMapOf<AggregateKey, OperationId>()
        val claims = linkedMapOf<ClaimKey, DomainId>()
        val grouped = linkedMapOf<OperationId, MutableList<StoredAggregate>>()
        for (values in histories.values) {
            currentCoroutineContext().ensureActive()
            val mutation = values.last()
            if (mutation.descriptor.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag) continue
            val captured = captures[mutation.descriptor.sequence] ?: malformed()
            historyShapeV2(mutation.seen && captured.joined && captured.scope == scope &&
                captured.header.commandEpoch == epoch.value && captured.header.assignmentId == coordinates.assignmentId)
            when (mutation.table) {
                RuntimeHistoryTableV2.AGGREGATE -> {
                    val selected = withImage(mutation) { it.text("kind") in RuntimeHistoricalFoodCurrentState.KINDS }
                    if (selected) {
                        val row = storedAggregate(mutation)
                        historyCapacityV2(sources.size < MAX_VERSIONS)
                        charge(row.payload.size.toLong())
                        val original = originals[captured.producer.operationId] ?: malformed()
                        historyShapeV2(producerIdentity(original) == captured.producer &&
                            writers.put(row.key, captured.producer.operationId) == null)
                        sources += RuntimeHistoricalAggregateSource(row, original)
                        grouped.getOrPut(captured.producer.operationId) { mutableListOf() } += row
                    }
                }
                RuntimeHistoryTableV2.CLAIM -> withImage(mutation) { nativeRow ->
                    val claim = nativeRow.claim()
                    if (claim.namespace in RuntimeHistoricalFoodCurrentState.CLAIM_NAMESPACES) {
                        historyCapacityV2(claims.size < MAX_VERSIONS)
                        historyShapeV2(claims.put(ClaimKey(claim.namespace, claim.key), DomainId(claim.ownerId)) == null)
                    }
                }
                else -> Unit
            }
        }
        for ((operationId, rows) in grouped) {
            val original = originals[operationId] ?: malformed()
            val remaining = rows.associateBy { it.key }.toMutableMap()
            fun write(value: RetainedAggregateWrite) {
                val row = remaining.remove(value.key) ?: return
                historyShapeV2(row.revision == value.storageGeneration && row.schema == value.schema &&
                    row.payload == value.payload && row.digest == value.payloadDigest)
            }
            when (original) {
                is RetainedOriginalV2.Legacy -> original.evidence.aggregateWrites.forEach(::write)
                is RetainedOriginalV2.Streamed -> scanFrames(checkNotNull(byOperation[operationId]), original.evidence.root) { frame ->
                    if (frame is RetainedCommandFrameV2.Write) write(frame.value)
                }
            }
            historyShapeV2(remaining.isEmpty())
        }
        val state = RuntimeHistoricalFoodCurrentState(this, scope, sources, writers, claims)
        context = currentCoroutineContext(); currentFood = state
        return try {
            val supported = producer.joinCurrentFood(state)
            requireCurrentFoodState(state)
            if (supported) state.requireClosed()
            supported
        } finally { state.clear(); currentFood = null; context = null }
    }

    private class ColdCurrentFoodCensus {
        val sources = mutableListOf<RuntimeHistoricalAggregateSource>()
        val writers = linkedMapOf<AggregateKey, OperationId>()
        val claims = linkedMapOf<ClaimKey, DomainId>()
        val rowsByWriter = linkedMapOf<OperationId, MutableMap<AggregateKey, StoredAggregate>>()
        val claimsByWriter = linkedMapOf<OperationId, MutableMap<ClaimKey, DomainId>>()
        fun clear() {
            sources.clear(); writers.clear(); claims.clear()
            rowsByWriter.values.forEach { it.clear() }; rowsByWriter.clear()
            claimsByWriter.values.forEach { it.clear() }; claimsByWriter.clear()
        }
    }

    /** Bounded selected current FOOD copies, not a retained branch history. The scope
     * opens before collection, but the typed state exists only after the complete native
     * census and literal original AFTER comparisons. Genuine owners remain pinned until
     * the typed FOOD/stock closure ends, including unsupported, failed and cancelled exits. */
    private suspend fun joinColdCurrentFood(): Boolean {
        val store = coldStore ?: malformed()
        historyShapeV2(captures.isEmpty() && byOperation.isEmpty() && originals.isEmpty() && coldOriginalLoans.isEmpty() &&
            coldOwnedCommandVersions == null && coldObservationTarget == null && coldOwnedAggregates.isEmpty() &&
            coldCurrentFoodCopies == 0)
        val retainedBefore = retainedBytes
        var census: ColdCurrentFoodCensus? = null
        var state: RuntimeHistoricalFoodCurrentState? = null
        var failure: Throwable? = null
        context = currentCoroutineContext(); coldCurrentFoodScope = Any()
        return try {
            charge(COLD_CURRENT_FOOD_BASE_BYTES)
            val selected = ColdCurrentFoodCensus().also { census = it }
            val before = coldCurrentFoodBeforeSequence()
            for (table in listOf(RuntimeHistoryTableV2.AGGREGATE, RuntimeHistoryTableV2.CLAIM)) {
                var count = 0L
                forEachBeforeRow(table, before) { mutation ->
                    val included = withImage(mutation) { row ->
                        historyShapeV2(row.table == table && row.text("scope") == scope.storageKey())
                        if (table == RuntimeHistoryTableV2.AGGREGATE)
                            row.text("kind") in RuntimeHistoricalFoodCurrentState.KINDS
                        else row.text("namespace") in RuntimeHistoricalFoodCurrentState.CLAIM_NAMESPACES
                    }
                    if (included) {
                        val loan = pinnedCurrentFoodOriginalAt(mutation.descriptor.sequence)
                        requireCurrentFoodMutation(loan, mutation)
                        val original = currentFoodOriginal(loan)
                        val operationId = loan.capture.producer.operationId
                        if (table == RuntimeHistoryTableV2.AGGREGATE) {
                            retainColdCurrentFoodCopy()
                            val row = storedAggregate(mutation)
                            historyShapeV2(mutation.token == aggregateToken(row.key) &&
                                selected.writers.put(row.key, operationId) == null)
                            selected.sources += RuntimeHistoricalAggregateSource(row, original)
                            historyShapeV2(selected.rowsByWriter.getOrPut(operationId) { linkedMapOf() }.put(row.key, row) == null)
                        } else {
                            retainColdCurrentFoodCopy(checkNotNull(mutation.descriptor.imageBytes).toLong())
                            withImage(mutation) { row ->
                                val claim = row.claim(); val key = ClaimKey(claim.namespace, claim.key); val owner = DomainId(claim.ownerId)
                                historyShapeV2(claim.scope == scope.storageKey() && mutation.token == claimToken(key) &&
                                    selected.claims.put(key, owner) == null &&
                                    selected.claimsByWriter.getOrPut(operationId) { linkedMapOf() }.put(key, owner) == null)
                            }
                        }
                    }
                    count = historyAddV2(count, 1L, Long.MAX_VALUE)
                }
                // Never silently omit a current row at the numeric cursor boundary.
                historyShapeV2(count == store.currentCount(table))
            }
            for ((operationId, loan) in coldOriginalLoans) {
                requireCurrentFoodLoan(loan)
                val original = currentFoodOriginal(loan)
                val rows = selected.rowsByWriter[operationId]
                val claims = selected.claimsByWriter[operationId]
                fun write(value: RetainedAggregateWrite) {
                    rows?.remove(value.key)?.let { row ->
                        historyShapeV2(row.revision == value.storageGeneration && row.schema == value.schema &&
                            row.payload == value.payload && row.digest == value.payloadDigest)
                    }
                }
                fun claim(value: RetainedClaimChange) {
                    claims?.remove(value.key)?.let { owner ->
                        historyShapeV2(value.action == RetainedClaimAction.ACQUIRE && value.ownerId == owner)
                    }
                }
                when (original) {
                    is RetainedOriginalV2.Streamed -> original.evidence.frames { frame -> when (frame) {
                        is RetainedCommandFrameV2.Write -> write(frame.value)
                        is RetainedCommandFrameV2.Claim -> claim(frame.value)
                        else -> Unit
                    } }
                    is RetainedOriginalV2.Legacy -> {
                        original.evidence.aggregateWrites.forEach(::write)
                        original.evidence.claimChanges.forEach(::claim)
                    }
                }
                historyShapeV2(rows.isNullOrEmpty() && claims.isNullOrEmpty())
            }
            val current = RuntimeHistoricalFoodCurrentState(this, scope, selected.sources, selected.writers, selected.claims)
                .also { state = it; currentFood = it }
            val supported = producer.joinCurrentFood(current)
            requireCurrentFoodState(current)
            if (supported) current.requireClosed()
            supported
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            cleanupAfter(failure) {
                census?.clear()
                closeColdCurrentFood(state, retainedBefore)
            }
        }
    }

    /** Current documents retain the same genuine last-writer loan. Ordinary FOOD uses
     * streamed writes; existing CASH Start/Apply writers use their complete legacy bundle.
     * The installed FOOD owner still checks every document/chunk and typed relationship.
     * A native raw observation is not a financial document writer, and unrelated legacy
     * commands or FOOD refund writers do not become supported through this selection. */
    private fun currentFoodOriginal(loan: RuntimeHistoryColdOriginalLoan): RetainedOriginalV2 {
        val original = loan.original
        if (loan.capture.nativeOriginal != null) unavailable()
        when (original) {
            is RetainedOriginalV2.Streamed ->
                if (original.evidence.identity.producer.command != StockStreamingCommandsV2.FOOD) unavailable()
            is RetainedOriginalV2.Legacy -> if (!original.evidence.committed ||
                original.evidence.nativeOriginalProvenance != null ||
                original.evidence.command != NativeOriginalDomain.FOOD_PAYMENT_V1.command) unavailable()
        }
        return original
    }

    /** Selected copies and their list/map/typed-decoder metadata share the same envelope
     * as every genuine original owner. Limits fail closed; no selected prefix is published. */
    private fun retainColdCurrentFoodCopy(imageBytes: Long = 0L) {
        historyShapeV2(coldStore != null && coldCurrentFoodScope != null && imageBytes >= 0L)
        historyCapacityV2(coldCurrentFoodCopies < MAX_VERSIONS)
        charge(historyAddV2(COLD_CURRENT_FOOD_COPY_BYTES, imageBytes * 4L, MAX_RETAINED_BYTES))
        coldCurrentFoodCopies++
    }

    private suspend fun closeColdCurrentFood(state: RuntimeHistoricalFoodCurrentState?, retainedBefore: Long): Unit = withContext(NonCancellable) {
        var failure: Throwable? = null
        fun failed(caught: Throwable) {
            if (failure == null) failure = caught else if (failure !== caught) checkNotNull(failure).addSuppressed(caught)
        }
        try { state?.clear() } catch (caught: Throwable) { failed(caught) }
        currentFood = null; coldCurrentFoodScope = null; context = null
        try { closeColdOriginalLoans() } catch (caught: Throwable) { failed(caught) }
        try { clearOwnedColdValues() } catch (caught: Throwable) { failed(caught) }
        coldCurrentFoodCopies = 0
        try {
            historyShapeV2(retainedBefore <= retainedBytes && coldOwnedCommandVersions == null)
            checkNotNull(coldStore).releaseOwnedBytes(retainedBytes - retainedBefore)
            retainedBytes = retainedBefore
        } catch (caught: Throwable) { failed(caught) }
        failure?.let { enabled = false; throw it }
        Unit
    }

    /** Reuse the SAME already joined native stock mutations and current indexes, rather than
     * build another stock snapshot. Cold sources retain their genuine original owners while
     * comparing literal AFTER writes; the typed adapter receives only inert current facts.
     * Ranges come from EVERY live native index lineage and close the FOOD/stock relationship. */
    internal suspend fun currentFoodIssueSources(state: RuntimeHistoricalFoodCurrentState,
        portionId: DomainId, reservationId: DomainId, issueId: DomainId): RuntimeHistoricalFoodCurrentIssueSources {
        requireCurrentFoodState(state)
        val reservation = currentFoodStockSource(state, AggregateKey(StockRelationalKindsV2.RESERVATION, reservationId))
        val issueKey = AggregateKey(StockRelationalKindsV2.ISSUE, issueId)
        val issue = currentFoodStockSource(state, issueKey)
        val firstIssue = if (coldStore != null) coldCurrentFoodFirstIssueSource(state, issueKey, issue) else {
            val first = histories[aggregateToken(issueKey)]?.firstOrNull() ?: malformed()
            historyShapeV2(first.table == RuntimeHistoryTableV2.AGGREGATE && first.descriptor.version == 1L &&
                first.descriptor.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag &&
                first.descriptor.beforeRowId == null && first.descriptor.beforeDigest == null)
            if (first === histories[aggregateToken(issueKey)]?.last()) issue else currentFoodStockSource(state, first)
        }
        val reservationMembers = currentFoodStockMembers(state, StockQueryV2.ReservationMembers(reservationId))
        val issueMembers = currentFoodStockMembers(state, StockQueryV2.IssueValues(issueId))
        val claims = linkedMapOf<ClaimKey, DomainId?>()
        suspend fun claim(namespace: String, id: DomainId) {
            val key = ClaimKey(namespace, id.value)
            if (key !in claims) claims[key] = currentFoodStockClaim(key)
        }
        claim(RuntimeHistoricalFoodCurrentIssueSources.RESERVATION_CLAIM, reservationId)
        claim(RuntimeHistoricalFoodCurrentIssueSources.SOURCE_CLAIM, issueId)
        for ((index, _) in reservationMembers) {
            val member = index as? StockIndexRecordV2.Allocation ?: malformed()
            member.allocationId?.let { claim(RuntimeHistoricalFoodCurrentIssueSources.ALLOCATION_CLAIM, it) }
        }
        for ((index, _) in issueMembers) {
            val member = index as? StockIndexRecordV2.Value ?: malformed()
            claim(RuntimeHistoricalFoodCurrentIssueSources.SOURCE_CLAIM, member.sourceId)
        }
        val sources = listOf(reservation, issue) + reservationMembers.map { it.second } + issueMembers.map { it.second }
        historyCapacityV2(sources.size <= MAX_VERSIONS)
        historyShapeV2(sources.map { it.row.key }.distinct().size == sources.size)
        requireCurrentFoodState(state)
        return RuntimeHistoricalFoodCurrentIssueSources(state, scope, portionId, reservationId, issueId, sources, firstIssue, claims)
    }

    /** The same already joined native reservation/index lineage, including held or released
     * members. This makes no stock read view and does not turn held ingredients into available
     * stock. A missing issue is proved from its actual native key and permanent claim. */
    internal suspend fun currentFoodReservationSources(state: RuntimeHistoricalFoodCurrentState,
        portionId: DomainId, reservationId: DomainId, unusedIssueId: DomainId): RuntimeHistoricalFoodCurrentReservationSources {
        requireCurrentFoodState(state)
        val issueToken = aggregateToken(AggregateKey(StockRelationalKindsV2.ISSUE, unusedIssueId))
        val issueAbsent = if (coldStore != null) withBefore(RuntimeHistoryTableV2.AGGREGATE, issueToken,
            coldCurrentFoodBeforeSequence()) { it == null } else {
            val issue = histories[issueToken]?.lastOrNull()
            issue == null || issue.descriptor.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag
        }
        historyShapeV2(issueAbsent &&
            currentFoodStockClaim(ClaimKey(RuntimeHistoricalFoodCurrentIssueSources.SOURCE_CLAIM, unusedIssueId.value)) == null)
        val reservation = currentFoodStockSource(state, AggregateKey(StockRelationalKindsV2.RESERVATION, reservationId))
        val members = currentFoodStockMembers(state, StockQueryV2.ReservationMembers(reservationId))
        val claims = linkedMapOf<ClaimKey, DomainId?>()
        val reservationClaim = ClaimKey(RuntimeHistoricalFoodCurrentIssueSources.RESERVATION_CLAIM, reservationId.value)
        claims[reservationClaim] = currentFoodStockClaim(reservationClaim)
        for ((index, _) in members) {
            val member = index as? StockIndexRecordV2.Allocation ?: malformed()
            member.allocationId?.let { id ->
                val key = ClaimKey(RuntimeHistoricalFoodCurrentIssueSources.ALLOCATION_CLAIM, id.value)
                historyShapeV2(key !in claims)
                claims[key] = currentFoodStockClaim(key)
            }
        }
        val sources = listOf(reservation) + members.map { it.second }
        historyCapacityV2(sources.size <= MAX_VERSIONS)
        historyShapeV2(sources.map { it.row.key }.distinct().size == sources.size)
        requireCurrentFoodState(state)
        return RuntimeHistoricalFoodCurrentReservationSources(state, scope, portionId, reservationId, unusedIssueId, sources, claims)
    }

    /** Literal attempt identities decoded from every current FOOD money document. These
     * claims are independently selected from the SAME complete native lineages; no current
     * SQL, caller approval Boolean, session restoration or accounting replay participates. */
    internal suspend fun requireCurrentFoodTenderClaims(state: RuntimeHistoricalFoodCurrentState,
        tenders: List<RuntimeHistoricalFoodCurrentTenderIdentity>) {
        requireCurrentFoodState(state)
        for (tender in tenders) {
            currentCoroutineContext().ensureActive()
            val role = when (tender.document.kind) {
                RuntimeHistoricalFoodCurrentState.COLLECTION -> "payment"
                RuntimeHistoricalFoodCurrentState.REFUND -> "refund"
                else -> malformed()
            }
            historyShapeV2(currentFoodStockClaim(ClaimKey("sales.unique", "${role}_attempt/${tender.attemptId.value}")) == tender.document.id &&
                currentFoodStockClaim(ClaimKey("sales.unique", "${role}_record/${tender.attemptId.value}")) ==
                    (if (tender.recorded) tender.document.id else null))
            if (tender.recorded && tender.cash)
                historyShapeV2(currentFoodStockClaim(ClaimKey("sales.cash.source", tender.attemptId.value)) == tender.originalCashSessionId)
        }
        requireCurrentFoodState(state)
    }

    private suspend fun currentFoodStockSource(state: RuntimeHistoricalFoodCurrentState,
        key: AggregateKey): RuntimeHistoricalAggregateSource {
        if (coldStore != null) return withBefore(RuntimeHistoryTableV2.AGGREGATE, aggregateToken(key),
            coldCurrentFoodBeforeSequence()) { mutation ->
            val value = currentFoodStockSource(state, mutation ?: malformed())
            historyShapeV2(value.row.key == key)
            value
        }
        val mutation = histories[aggregateToken(key)]?.lastOrNull() ?: malformed()
        val value = currentFoodStockSource(state, mutation)
        historyShapeV2(value.row.key == key)
        return value
    }

    private suspend fun currentFoodStockSource(state: RuntimeHistoricalFoodCurrentState,
        mutation: Mutation): RuntimeHistoricalAggregateSource {
        requireCurrentFoodState(state)
        if (coldStore != null) {
            val loan = pinnedCurrentFoodOriginalAt(mutation.descriptor.sequence)
            requireCurrentFoodMutation(loan, mutation)
            historyShapeV2(mutation.table == RuntimeHistoryTableV2.AGGREGATE &&
                mutation.descriptor.afterKind != RuntimeHistoryAfterKindV2.TOMBSTONE.tag)
            val original = loan.original as? RetainedOriginalV2.Streamed ?: unavailable()
            retainColdCurrentFoodCopy()
            val row = storedAggregate(mutation)
            historyShapeV2(row.key.kind in StockRelationalKindsV2.all && mutation.token == aggregateToken(row.key))
            var found = false
            original.evidence.frames { frame ->
                val write = (frame as? RetainedCommandFrameV2.Write)?.value
                if (write?.key == row.key) {
                    historyShapeV2(!found && row.revision == write.storageGeneration && row.schema == write.schema &&
                        row.payload == write.payload && row.digest == write.payloadDigest)
                    found = true
                }
            }
            historyShapeV2(found)
            requireCurrentFoodState(state)
            return RuntimeHistoricalAggregateSource(row, original)
        }
        val captured = captures[mutation.descriptor.sequence] ?: malformed()
        historyShapeV2(mutation.table == RuntimeHistoryTableV2.AGGREGATE && mutation.seen &&
            mutation.descriptor.afterKind != RuntimeHistoryAfterKindV2.TOMBSTONE.tag && captured.joined &&
            captured.scope == scope && captured.header.commandEpoch == epoch.value &&
            captured.header.assignmentId == coordinates.assignmentId)
        val row = storedAggregate(mutation)
        historyShapeV2(row.key.kind in StockRelationalKindsV2.all)
        charge(row.payload.size.toLong())
        val original = originals[captured.producer.operationId] ?: malformed()
        historyShapeV2(producerIdentity(original) == captured.producer)
        // requireNativeMutations already matched EVERY original AFTER field to this same
        // native mutation; full producer/resource and current-tip/index joins also completed.
        return RuntimeHistoricalAggregateSource(row, original)
    }

    private suspend fun coldCurrentFoodFirstIssueSource(state: RuntimeHistoricalFoodCurrentState, key: AggregateKey,
        current: RuntimeHistoricalAggregateSource): RuntimeHistoricalAggregateSource {
        requireCurrentFoodState(state)
        val store = coldStore ?: malformed()
        val token = aggregateToken(key); val canonicalKey = tokenKey(RuntimeHistoryTableV2.AGGREGATE, token)
        var cursor: RuntimeHistoryColdCursor<Mutation>? = null; var failure: Throwable? = null
        return try {
            val versions = store.openEarlierVersions(RuntimeHistoryTableV2.AGGREGATE, canonicalKey,
                coldCurrentFoodBeforeSequence()).also { cursor = it }
            val first = versions.next() ?: malformed()
            val descriptor = first.descriptor
            historyShapeV2(first.seen && first.table == RuntimeHistoryTableV2.AGGREGATE && first.token == token &&
                descriptor.version == 1L && descriptor.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag &&
                descriptor.previousSequence == null && descriptor.previousOrdinal == null && descriptor.previousVersion == null &&
                descriptor.beforeRowId == null && descriptor.beforeDigest == null &&
                descriptor.afterKind == RuntimeHistoryAfterKindV2.IMAGE.tag)
            val loan = pinnedCurrentFoodOriginalAt(descriptor.sequence)
            requireCurrentFoodMutation(loan, first)
            val currentWriter = byOperation[producerIdentity(current.original).operationId] ?: malformed()
            val source = if (descriptor.sequence == currentWriter.header.sequence) {
                historyShapeV2(current.original === loan.original && current.row.revision == Revision(1))
                current
            } else currentFoodStockSource(state, first)
            historyShapeV2(source.row.key == key && source.row.revision == Revision(1))
            requireCurrentFoodState(state)
            source
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { try { cleanupAfter(failure) { cursor?.close() } } finally { canonicalKey.fill(0) } }
    }

    private suspend fun currentFoodStockMembers(state: RuntimeHistoricalFoodCurrentState,
        query: StockQueryV2): List<Pair<StockIndexRecordV2, RuntimeHistoricalAggregateSource>> {
        requireCurrentFoodState(state)
        historyShapeV2(query is StockQueryV2.ReservationMembers || query is StockQueryV2.IssueValues)
        val table = if (query is StockQueryV2.ReservationMembers) RuntimeHistoryTableV2.STOCK_ALLOCATION else RuntimeHistoryTableV2.STOCK_VALUE
        val selected = mutableListOf<StockIndexRecordV2>()
        if (coldStore != null) {
            var count = 0L
            forEachBeforeRow(table, coldCurrentFoodBeforeSequence()) { mutation ->
                val index = withImage(mutation) { row ->
                    historyShapeV2(row.text("scope") == scope.storageKey())
                    val value = row.runtimeStockIndex()
                    if (runtimeStockSelect(query, listOf(value)).isEmpty()) null else {
                        historyCapacityV2(selected.size < MAX_VERSIONS)
                        // Retain scalar index/order metadata before the native cursor advances.
                        charge(historyAddV2(COLD_CURRENT_FOOD_COPY_BYTES,
                            checkNotNull(mutation.descriptor.imageBytes).toLong() * 4L, MAX_RETAINED_BYTES))
                        value
                    }
                }
                if (index != null) {
                    val loan = pinnedCurrentFoodOriginalAt(mutation.descriptor.sequence)
                    requireCurrentFoodMutation(loan, mutation)
                    historyShapeV2(index.identity.producerOperationId == loan.capture.producer.operationId)
                    selected += index
                }
                count = historyAddV2(count, 1L, Long.MAX_VALUE)
            }
            historyShapeV2(count == coldStore.currentCount(table))
        } else {
            for (values in histories.values) {
                currentCoroutineContext().ensureActive()
                val mutation = values.last()
                if (mutation.table != table || mutation.descriptor.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag) continue
                val captured = captures[mutation.descriptor.sequence] ?: malformed()
                historyShapeV2(mutation.seen && captured.joined)
                val index = withImage(mutation) { it.runtimeStockIndex() }
                if (runtimeStockSelect(query, listOf(index)).isNotEmpty()) selected += index
            }
        }
        // SAME fixed SQL ordering/selection as the completed historical stock-range joins;
        // no callback-supplied members, pagination cutoff, amount-only or count-only proof.
        return runtimeStockSelect(query, selected).map { index ->
            val source = currentFoodStockSource(state, index.identity.key)
            historyShapeV2(source.row.revision == index.identity.generation && source.row.digest == index.identity.payloadHash &&
                producerIdentity(source.original).operationId == index.identity.producerOperationId &&
                projection.project(scope, source.row) == index)
            index to source
        }
    }

    private suspend fun currentFoodStockClaim(key: ClaimKey): DomainId? {
        if (coldStore != null) return withBefore(RuntimeHistoryTableV2.CLAIM, claimToken(key),
            coldCurrentFoodBeforeSequence()) { mutation ->
            if (mutation == null) return@withBefore null
            val loan = pinnedCurrentFoodOriginalAt(mutation.descriptor.sequence)
            requireCurrentFoodMutation(loan, mutation)
            retainColdCurrentFoodCopy(checkNotNull(mutation.descriptor.imageBytes).toLong())
            val owner = withImage(mutation) { row ->
                val claim = row.claim()
                historyShapeV2(claim.scope == scope.storageKey() && ClaimKey(claim.namespace, claim.key) == key &&
                    mutation.token == claimToken(key))
                DomainId(claim.ownerId)
            }
            var found = false
            fun claim(value: RetainedClaimChange) {
                if (value.key == key) {
                    historyShapeV2(!found && value.action == RetainedClaimAction.ACQUIRE && value.ownerId == owner)
                    found = true
                }
            }
            when (val original = loan.original) {
                is RetainedOriginalV2.Legacy -> original.evidence.claimChanges.forEach(::claim)
                is RetainedOriginalV2.Streamed -> original.evidence.frames { frame ->
                    if (frame is RetainedCommandFrameV2.Claim) claim(frame.value)
                }
            }
            historyShapeV2(found)
            owner
        }
        val mutation = histories[claimToken(key)]?.lastOrNull() ?: return null
        if (mutation.descriptor.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag) return null
        val captured = captures[mutation.descriptor.sequence] ?: malformed()
        historyShapeV2(mutation.table == RuntimeHistoryTableV2.CLAIM && mutation.seen && captured.joined && captured.scope == scope &&
            captured.header.commandEpoch == epoch.value && captured.header.assignmentId == coordinates.assignmentId)
        return withImage(mutation) { row ->
            val claim = row.claim()
            historyShapeV2(claim.scope == scope.storageKey() && ClaimKey(claim.namespace, claim.key) == key)
            DomainId(claim.ownerId)
        }
    }

    internal suspend fun requireCurrentFoodState(state: RuntimeHistoricalFoodCurrentState) {
        val actual = currentCoroutineContext(); actual.ensureActive()
        historyShapeV2(enabled && historyComplete && lineageClosed && active == null && heldColdOriginal == null &&
            actual == context && currentFood === state && (coldStore == null ||
                coldCurrentFoodScope != null && coldOwnedCommandVersions == null && coldStore.session === session))
    }

    private fun detachColdLoan(loan: RuntimeHistoryColdOriginalLoan) {
        val captured = loan.capture; val operationId = captured.producer.operationId
        if (coldOriginalLoans[operationId] === loan) coldOriginalLoans.remove(operationId)
        if (captures[captured.header.sequence] === captured) captures.remove(captured.header.sequence)
        if (byOperation[operationId] === captured) byOperation.remove(operationId)
        if (originals[operationId] === loan.original) originals.remove(operationId)
    }
    private suspend fun closeColdOriginalLoans(): Unit = withContext(NonCancellable) {
        var failure: Throwable? = null
        while (coldOriginalLoans.isNotEmpty()) {
            val loan = coldOriginalLoans.entries.first().value
            detachColdLoan(loan)
            // The genuine earlier owner alone clears its captured proof/original.
            // Always try every independent loan even if one close fails.
            try { loan.close() } catch (caught: Throwable) {
                if (failure == null) failure = caught else if (failure !== caught) checkNotNull(failure).addSuppressed(caught)
            }
        }
        failure?.let { enabled = false; throw it }
        Unit
    }
    private suspend fun cleanupAfter(primary: Throwable?, cleanup: suspend () -> Unit) = withContext(NonCancellable) {
        try { cleanup() } catch (failure: Throwable) {
            if (primary == null) throw failure
            if (primary !== failure) primary.addSuppressed(failure)
        }
    }
    private fun clearOwnedColdValues() {
        if (coldStore == null) return
        for (aggregate in coldOwnedAggregates) aggregate.payload.bytes().fill(0)
        coldOwnedAggregates.clear()
        for (captured in captures.values) if (coldOriginalLoans[captured.producer.operationId]?.capture !== captured) {
            captured.nativeOriginal?.canonicalRequirement?.bytes()?.fill(0)
            captured.nativeOriginal?.certificate?.bytes()?.fill(0)
        }
        for ((operationId, original) in originals) if (coldOriginalLoans[operationId]?.original !== original) when (original) {
            is RetainedOriginalV2.Legacy -> {
                original.evidence.normalized.bytes().fill(0); original.evidence.result.bytes().fill(0)
                for (write in original.evidence.aggregateWrites) write.payload.bytes().fill(0)
                for (job in original.evidence.jobs) job.body.bytes().fill(0)
            }
            is RetainedOriginalV2.Streamed -> {
                original.evidence.normalized.bytes().fill(0); original.evidence.result.bytes().fill(0)
            }
        }
    }
    fun abandon() {
        enabled = false; active = null; heldColdOriginal = null; currentFood?.clear(); currentFood = null; historyComplete = false; context = null
        coldCurrentFoodScope = null; coldCurrentFoodCopies = 0
        recipients.abandon()
        clearOwnedColdValues()
        for (captured in captures.values) if (coldOriginalLoans[captured.producer.operationId]?.capture !== captured) captured.clear()
        captures.clear(); byOperation.clear(); histories.clear(); originals.clear()
        coldObservationTarget = null
        seedBranch?.fill(0); seedBranch = null; versionCount = 0; coldRecipientBytes = 0L
        releaseColdOwnedCommand()
        val remaining = coldOriginalLoans.size.toLong() * 256L
        val released = retainedBytes - remaining; retainedBytes = remaining
        releaseSharedBytes(released)
    }
    fun clear() = abandon()
    /** Scanner finally hook. Synchronous abandon never clears a borrowed capture or
     * pretends to close its suspendable genuine owner; this settles every loan first. */
    suspend fun clearCold() {
        if (coldStore == null) { abandon(); return }
        withContext(NonCancellable) {
            var failure: Throwable? = null
            try { abandon() } catch (caught: Throwable) { failure = caught }
            try { closeColdOriginalLoans() } catch (caught: Throwable) {
                if (failure == null) failure = caught else if (failure !== caught) checkNotNull(failure).addSuppressed(caught)
            }
            val released = retainedBytes; retainedBytes = 0L
            try { coldStore.releaseOwnedBytes(released) } catch (caught: Throwable) {
                if (failure == null) failure = caught else if (failure !== caught) checkNotNull(failure).addSuppressed(caught)
            }
            failure?.let { throw it }
        }
    }
    private fun chargeRecipient(bytes: Long) {
        val next = if (coldStore == null) 0L else historyAddV2(coldRecipientBytes, bytes, MAX_RETAINED_BYTES)
        charge(bytes)
        coldRecipientBytes = next
    }
    private fun releaseColdOwnedCommand() {
        val versions = coldOwnedCommandVersions ?: return
        checkNotNull(coldStore).releaseOwnedCommand(versions)
        coldOwnedCommandVersions = null
    }
    private fun charge(bytes: Long) {
        val next = historyAddV2(retainedBytes, bytes, MAX_RETAINED_BYTES)
        reserveSharedBytes(bytes)
        retainedBytes = next
    }
    private fun reserveSharedBytes(bytes: Long) {
        if (coldStore != null) coldStore.reserveOwnedBytes(bytes) else guardedMemory?.reserve(bytes)
    }
    private fun releaseSharedBytes(bytes: Long) {
        if (coldStore != null) coldStore.releaseOwnedBytes(bytes) else guardedMemory?.release(bytes)
    }
    private fun CommandCapture.one(table: RuntimeHistoryTableV2): Mutation = mutations.singleOrNull { it.table == table } ?: malformed()
    private suspend fun <T> withImage(value: Mutation, body: suspend (RuntimeHistoryRowViewV2) -> T): T {
        val store = coldStore
        if (store == null || value.image != null) return withRow(checkNotNull(value.image), body)
        historyShapeV2(value.seen && value.descriptor.afterKind != RuntimeHistoryAfterKindV2.TOMBSTONE.tag)
        return store.withVersion(value.descriptor.sequence, value.descriptor.ordinal) { observed ->
            historyShapeV2(observed.seen && observed.wire.contentEquals(value.wire))
            withRow(checkNotNull(observed.image)) { row -> requireMutationImage(value, row); body(row) }
        }
    }
    private suspend fun <T> withRow(bytes: ByteArray, body: suspend (RuntimeHistoryRowViewV2) -> T): T {
        val reserved = if (coldStore == null && guardedMemory == null) 0L else bytes.size.toLong() * 4L
        reserveSharedBytes(reserved)
        return try {
            val row = RuntimeHistoryRowViewV2(RuntimeHistoryRowCodecV2.decode(bytes))
            try { body(row) } finally { row.clear() }
        } finally { releaseSharedBytes(reserved) }
    }
    private fun requireMutationImage(value: Mutation, row: RuntimeHistoryRowViewV2) {
        val key = row.key()
        try {
            historyShapeV2(row.table == value.table && key.contentEquals(value.descriptor.key) &&
                row.rowId == value.descriptor.afterRowId && row.digest().value == value.descriptor.afterDigest)
        } finally { key.fill(0) }
    }
    private fun tokenKey(table: RuntimeHistoryTableV2, value: String): ByteArray {
        val prefix = table.tag.toString() + ':'
        historyShapeV2(value.startsWith(prefix) && (value.length - prefix.length) % 2 == 0)
        val bytes = ByteArray((value.length - prefix.length) / 2)
        for (index in bytes.indices) {
            val start = prefix.length + index * 2
            val high = value[start].digitToIntOrNull(16) ?: malformed()
            val low = value[start + 1].digitToIntOrNull(16) ?: malformed()
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }
    private fun aggregateToken(key: AggregateKey): String = keyToken(RuntimeHistoryTableV2.AGGREGATE, scope.storageKey(), key.kind, key.id.value)
    private fun claimToken(key: ClaimKey): String = keyToken(RuntimeHistoryTableV2.CLAIM, scope.storageKey(), key.namespace, key.key)
    private fun keyToken(table: RuntimeHistoryTableV2, vararg values: String): String {
        val cells = values.map { text ->
            val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
            try { NativeHistoryPhysicalCellV2.Text(bytes) } finally { bytes.fill(0) }
        }
        return try {
            val bytes = RuntimeHistoryRowCodecV2.encodeKey(table, cells)
            try { token(table, bytes) } finally { bytes.fill(0) }
        } finally { cells.forEach { it.clear() } }
    }
    companion object {
        const val MAX_COMMANDS: Int = 256
        const val MAX_VERSIONS: Int = 4096
        internal const val MAX_RETAINED_BYTES: Long = 64L * 1024 * 1024
        private const val COLD_FRAME_WORK_BYTES: Long = 8L * STOCK_FRAME_BYTES_V2 + 128L * MAX_VERSIONS + 4096L
        private const val COLD_CURRENT_FOOD_BASE_BYTES: Long = 16_384L
        private const val COLD_CURRENT_FOOD_COPY_BYTES: Long = 4_096L
        /** Closed existing retail PAYMENT/REFUND, FOOD PAYMENT and supplier CASH ORIGINAL families only.
         * This selects DATA handling; genuine earlier Start authority and every original effect still join. */
        internal fun supportedColdNativeOriginalDomain(evidence: RuntimeNativeOriginalImportEvidenceV1): NativeOriginalDomain? = when {
            evidence.producer.command == NativeOriginalDomain.RETAIL_PAYMENT_V3.command &&
                evidence.provenance.nativeOriginal.domainProfile == NativeOriginalDomain.RETAIL_PAYMENT_V3.domainProfile -> NativeOriginalDomain.RETAIL_PAYMENT_V3
            evidence.producer.command == NativeOriginalDomain.RETAIL_REFUND_V3.command &&
                evidence.provenance.nativeOriginal.domainProfile == NativeOriginalDomain.RETAIL_REFUND_V3.domainProfile -> NativeOriginalDomain.RETAIL_REFUND_V3
            evidence.producer.command == NativeOriginalDomain.FOOD_PAYMENT_V1.command &&
                evidence.provenance.nativeOriginal.domainProfile == NativeOriginalDomain.FOOD_PAYMENT_V1.domainProfile -> NativeOriginalDomain.FOOD_PAYMENT_V1
            evidence.producer.command == NativeOriginalDomain.SUPPLIER_CASH_V1.command &&
                evidence.provenance.nativeOriginal.domainProfile == NativeOriginalDomain.SUPPLIER_CASH_V1.domainProfile -> NativeOriginalDomain.SUPPLIER_CASH_V1
            else -> null
        }
        internal fun supportsColdNativeOriginal(evidence: RuntimeNativeOriginalImportEvidenceV1): Boolean =
            supportedColdNativeOriginalDomain(evidence) != null
        private val COLD_NATIVE_ORIGINAL_TABLE_COUNTS = mapOf(
            RuntimeHistoryTableV2.OPERATION to 1, RuntimeHistoryTableV2.JOURNAL to 1,
            RuntimeHistoryTableV2.AUDIT to 1, RuntimeHistoryTableV2.OUTBOX to 1,
            RuntimeHistoryTableV2.BRANCH to 1, RuntimeHistoryTableV2.AGGREGATE to 2)
        private val STOCK_TABLES = setOf(RuntimeHistoryTableV2.STOCK_POSITION, RuntimeHistoryTableV2.STOCK_ALLOCATION,
            RuntimeHistoryTableV2.STOCK_RESERVATION, RuntimeHistoryTableV2.STOCK_VALUE)
        private val REJECTION_TABLES = setOf(RuntimeHistoryTableV2.OPERATION, RuntimeHistoryTableV2.JOURNAL,
            RuntimeHistoryTableV2.AUDIT, RuntimeHistoryTableV2.OUTBOX, RuntimeHistoryTableV2.BRANCH)
        private val TABLES = setOf(RuntimeHistoryTableV2.OPERATION, RuntimeHistoryTableV2.JOURNAL, RuntimeHistoryTableV2.AUDIT,
            RuntimeHistoryTableV2.OUTBOX, RuntimeHistoryTableV2.JOURNAL_FRAGMENT, RuntimeHistoryTableV2.BRANCH,
            RuntimeHistoryTableV2.AGGREGATE, RuntimeHistoryTableV2.CLAIM) + STOCK_TABLES
        private fun token(table: RuntimeHistoryTableV2, bytes: ByteArray): String = table.tag.toString() + ":" +
            bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun producerIdentity(original: RetainedOriginalV2): StreamedProducerIdentityV2 = when (original) {
            is RetainedOriginalV2.Legacy -> with(original.evidence) { StreamedProducerIdentityV2(operationId, command, originalActorId, semanticDigest) }
            is RetainedOriginalV2.Streamed -> original.evidence.identity.producer
        }
        private fun recordedAt(original: RetainedOriginalV2) = when (original) {
            is RetainedOriginalV2.Legacy -> original.evidence.recordedAt
            is RetainedOriginalV2.Streamed -> original.evidence.identity.recordedAt
        }
        private fun framedText(values: List<String>): ByteArray {
            val fields = values.map { it.encodeToByteArray(throwOnInvalidSequence = true) }
            return try { frame(fields) } finally { fields.forEach { it.fill(0) } }
        }
        private fun fields(bytes: ByteArray): List<ByteArray> {
            val values = mutableListOf<ByteArray>(); var at = 0
            try {
                while (at < bytes.size) {
                    historyShapeV2(bytes.size - at >= 4 && values.size < 1024)
                    var size = 0L
                    repeat(4) { size = (size shl 8) or (bytes[at++].toLong() and 255) }
                    historyShapeV2(size <= bytes.size - at)
                    val end = at + size.toInt(); values += bytes.copyOfRange(at, end); at = end
                }
                return values
            } catch (failure: Throwable) { values.forEach { it.fill(0) }; throw failure }
        }
        private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
        private fun unavailable(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.UNRESOLVED_PROTOCOL)
    }
}
