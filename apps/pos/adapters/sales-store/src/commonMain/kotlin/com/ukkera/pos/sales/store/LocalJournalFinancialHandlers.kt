package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.MasterPhysicalInteractiveAuthorizer
import com.ukkera.pos.database.PhysicalRecordingAction
import com.ukkera.pos.database.PhysicalRecordingAdmission
import com.ukkera.pos.database.PhysicalRecordingOutcome
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.ReservedOperations
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.LocalStockFailure
import com.ukkera.pos.inventory.store.LocalStockRejected
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.SalesCommand
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Explicit v3 dispatch, with no direct-execute or default-capacity bypass of the core gate. */
public class LocalJournalCollectionHandler(
    private val stock: LocalStockRepository,
    private val tenderSource: TransactionSalesTenderSource,
    private val participantsSource: TransactionRetailJournalParticipantsSource = UnavailableRetailJournalParticipantsSource,
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1 = tenderSource as? HistoricalSalesTenderPolicySourceV1
        ?: UnavailableHistoricalSalesTenderPolicySourceV1,
) : MasterCommandHandler {
    override val physicalInteractiveAuthorizer: MasterPhysicalInteractiveAuthorizer =
        LocalCollectionInteractiveAuthorization(stock, tenderSource, tenderPolicies)
    override val key: CommandKey get() = LocalFinancialJournalProfileV3.COLLECTION_COMMAND
    override val writableKinds: Set<String> = (LocalStockStoreProfile.inventoryKinds - LocalStockStoreProfile.SERIAL_ORIGIN) +
        retailFinancialKinds + setOf(LocalSalesStoreProfile.COLLECTION_KIND, LocalSalesStoreProfile.COLLECTION_BEFORE_KIND,
            com.ukkera.pos.database.StockReadEvidenceV1.KIND, COMMERCE_STOCK_LINK_KIND_V2)
    override val claimNamespaces: Set<String> = LocalStockStoreProfile.inventoryClaimNamespaces + retailFinancialClaims
    override val physicalRecordingKinds: Set<String> get() = LocalFinancialJournalProfileV3.gatedKinds
    override val physicalRecordingClaimNamespaces: Set<String> get() = LocalFinancialJournalProfileV3.gatedClaimNamespaces
    override fun normalize(input: StoredBytes): StoredBytes = LocalCollectionCommandCodecV3.normalize(input)
    override fun physicalRecordingAction(normalized: StoredBytes): PhysicalRecordingAction? = when (LocalCollectionCommandCodecV3.decode(normalized)) {
        is LocalCollectionRequestV3.Start -> PhysicalRecordingAction.START_PAYMENT
        is LocalCollectionRequestV3.RecordOriginal -> PhysicalRecordingAction.RETAIN_ORIGINAL
        is LocalCollectionRequestV3.RecordResolution -> PhysicalRecordingAction.RETAIN_RESOLUTION
        is LocalCollectionRequestV3.ApplyObservation -> null
    }
    override fun reservedOperationBinding(normalized: StoredBytes): PayloadDigest? = when (val request = LocalCollectionCommandCodecV3.decode(normalized)) {
        is LocalCollectionRequestV3.RecordOriginal -> retailJournalFenceBindingV3(request.target, LocalJournalFactPhaseV3.ORIGINAL)
        is LocalCollectionRequestV3.RecordResolution -> retailJournalFenceBindingV3(request.target, LocalJournalFactPhaseV3.RESOLUTION)
        is LocalCollectionRequestV3.Start, is LocalCollectionRequestV3.ApplyObservation -> null
    }
    override fun physicalRecordingOutcome(normalized: StoredBytes, effectiveOutcome: ProposedOutcome,
        context: CommandContext): PhysicalRecordingOutcome? = retailPhysicalOutcomeV3(normalized, effectiveOutcome, context, tenderPolicies)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        requireRetailContext(normalized, view, context, key)
        val loan = commerceLoan(view, stock, tenderPolicies)
        return when (val request = LocalCollectionCommandCodecV3.decode(normalized)) {
            is LocalCollectionRequestV3.Start -> prepareRetailPaymentStart(request, loan, context, stock, tenderSource, participantsSource)
            is LocalCollectionRequestV3.RecordOriginal -> prepareRetailFact(request.target, LocalJournalFactPhaseV3.ORIGINAL, normalized, loan, context)
            is LocalCollectionRequestV3.RecordResolution -> prepareRetailFact(request.target, LocalJournalFactPhaseV3.RESOLUTION, normalized, loan, context)
            is LocalCollectionRequestV3.ApplyObservation -> prepareRetailApplication(SalesRecordingKind.PAYMENT_ATTESTATION,
                request.recordingOperationId, request.phase, loan, context)
        }
    }
}

public class LocalJournalReturnHandler(
    private val participantsSource: TransactionRetailJournalParticipantsSource = UnavailableRetailJournalParticipantsSource,
    private val stock: LocalStockRepository? = null,
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1,
) : MasterCommandHandler {
    override val physicalInteractiveAuthorizer: MasterPhysicalInteractiveAuthorizer = LocalReturnInteractiveAuthorization(stock, tenderPolicies)
    override val key: CommandKey get() = LocalFinancialJournalProfileV3.RETURN_COMMAND
    override val writableKinds: Set<String> = retailFinancialKinds + setOf(LocalSalesStoreProfile.RETURN_KIND,
        LocalFinancialJournalProfileV3.BEFORE_KIND)
    override val claimNamespaces: Set<String> get() = retailFinancialClaims
    override val physicalRecordingKinds: Set<String> get() = LocalFinancialJournalProfileV3.gatedKinds
    override val physicalRecordingClaimNamespaces: Set<String> get() = LocalFinancialJournalProfileV3.gatedClaimNamespaces
    override fun normalize(input: StoredBytes): StoredBytes = LocalReturnCommandCodecV3.normalize(input)
    override fun physicalRecordingAction(normalized: StoredBytes): PhysicalRecordingAction? = when (LocalReturnCommandCodecV3.decode(normalized)) {
        is LocalReturnRequestV3.Start -> PhysicalRecordingAction.START_REFUND
        is LocalReturnRequestV3.RecordOriginal -> PhysicalRecordingAction.RETAIN_ORIGINAL
        is LocalReturnRequestV3.RecordResolution -> PhysicalRecordingAction.RETAIN_RESOLUTION
        is LocalReturnRequestV3.ApplyObservation -> null
    }
    override fun reservedOperationBinding(normalized: StoredBytes): PayloadDigest? = when (val request = LocalReturnCommandCodecV3.decode(normalized)) {
        is LocalReturnRequestV3.RecordOriginal -> retailJournalFenceBindingV3(request.target, LocalJournalFactPhaseV3.ORIGINAL)
        is LocalReturnRequestV3.RecordResolution -> retailJournalFenceBindingV3(request.target, LocalJournalFactPhaseV3.RESOLUTION)
        is LocalReturnRequestV3.Start, is LocalReturnRequestV3.ApplyObservation -> null
    }
    override fun physicalRecordingOutcome(normalized: StoredBytes, effectiveOutcome: ProposedOutcome,
        context: CommandContext): PhysicalRecordingOutcome? = retailPhysicalOutcomeV3(normalized, effectiveOutcome, context, tenderPolicies)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        requireRetailContext(normalized, view, context, key)
        val loan = stock?.let { commerceLoan(view, it, tenderPolicies) } ?: view
        return when (val request = LocalReturnCommandCodecV3.decode(normalized)) {
            is LocalReturnRequestV3.Start -> prepareRetailRefundStart(request, loan, context, participantsSource)
            is LocalReturnRequestV3.RecordOriginal -> prepareRetailFact(request.target, LocalJournalFactPhaseV3.ORIGINAL, normalized, loan, context)
            is LocalReturnRequestV3.RecordResolution -> prepareRetailFact(request.target, LocalJournalFactPhaseV3.RESOLUTION, normalized, loan, context)
            is LocalReturnRequestV3.ApplyObservation -> prepareRetailApplication(SalesRecordingKind.REFUND_ATTESTATION,
                request.recordingOperationId, request.phase, loan, context)
        }
    }
}

private val retailFinancialKinds: Set<String> = LocalFinancialJournalProfileV3.gatedKinds + setOf(
    LocalFinancialJournalProfileV3.APPLICATION_KIND, ReservedOperations.KIND, CashNonSaleStoreProfile.SESSION_KIND,
    LocalSalesStoreProfile.MOVEMENT_KIND, LocalSalesStoreProfile.WORK_KIND)
private val retailFinancialClaims: Set<String> = LocalFinancialJournalProfileV3.gatedClaimNamespaces + setOf(
    LocalSalesStoreProfile.UNIQUE_NAMESPACE, CashNonSaleStoreProfile.CASH_SOURCE_NAMESPACE)

private fun requireRetailContext(normalized: StoredBytes, view: CommandReadView, context: CommandContext, key: CommandKey) {
    check(context.scope == view.scope && context.key == key &&
        semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest) { "sales.journal.context" }
}

/** Same current employee/amount/owner authorization as retail, plus a separate pure admission. */
internal class LocalJournalPrepared(
    private val context: CommandContext,
    action: SecurityAction, targetId: DomainId, revision: Revision, ownerId: DomainId,
    private val reason: String, amount: Money? = null, resources: Set<RecoveryResourceKey>,
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = emptySet(),
    private val admission: ((SalesCommand, UtcInstant) -> PhysicalRecordingAdmission?)? = null,
    private val proposal: (SalesCommand, UtcInstant) -> ProposedOutcome,
) : PreparedMutation() {
    override val requirement: AuthorizationRequirement = salesAuthorizationRequirement(action, context.scope, targetId,
        revision, context.operationId, context.semanticDigest, reason, ownerId, amount)
    override val affectedResources: Set<RecoveryResourceKey> = resources.toSet() + recordingAuthority(context.originalActorId)
    private fun command(authorization: AuthorizedAction, now: UtcInstant): SalesCommand {
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            authorization.isBoundTo(requirement, context.originalActorId)) { "sales.journal.authorization" }
        return SalesCommand(context.operationId, authorization, now.value, context.semanticDigest, reason)
    }
    override fun physicalRecordingAdmission(authorization: AuthorizedAction, recordedAt: UtcInstant): PhysicalRecordingAdmission? =
        admission?.invoke(command(authorization, recordedAt), recordedAt)
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome = try {
        proposal(command(authorization, recordedAt), recordedAt)
    } catch (violation: SalesRuleViolation) {
        retailRejected(violation.rule)
    } catch (_: LocalSalesCapacityExceeded) {
        retailUnavailable(LocalJournalUnavailableV3.CAPACITY_REQUIRED)
    } catch (_: LocalSalesWireCapacityExceeded) {
        retailUnavailable(LocalJournalUnavailableV3.CAPACITY_REQUIRED)
    } catch (rejected: LocalStockRejected) {
        // The trusted stock project/compose producer enforces its own framed-byte
        // cap. A known size refusal cannot leave a Start/held proposal half-built.
        if (rejected.failure != LocalStockFailure.BOUND_EXCEEDED) throw rejected
        retailUnavailable(LocalJournalUnavailableV3.CAPACITY_REQUIRED)
    }
}

internal fun retailRejected(rule: SalesRule): ProposedOutcome.Reject =
    ProposedOutcome.Reject("sales.journal.rejected", LocalFinancialResultCodecV3.encode(LocalFinancialResultV3.Rejected(rule)))
internal fun retailUnavailable(reason: LocalJournalUnavailableV3): ProposedOutcome.Reject =
    ProposedOutcome.Reject("sales.journal.unavailable", LocalFinancialResultCodecV3.encode(LocalFinancialResultV3.Unavailable(reason)))

/** Keeps all legacy codecs/results readable; the new machine code closes only fresh starts/reserves. */
internal fun legacyJournalRequired(context: CommandContext, action: SecurityAction, targetId: DomainId,
    revision: Revision, ownerId: DomainId, amount: Money?, resources: Set<RecoveryResourceKey>): PreparedMutation =
    LocalSalesPrepared(context, action, targetId, revision, ownerId, "financial.journal_required", amount, resources) { _, _ ->
        ProposedOutcome.Reject("sales.journal-first.required",
            LocalSalesResultCodec.encode(LocalSalesResultV1.Unavailable(LocalSalesUnavailable.SOURCE_REQUIRED)))
    }
